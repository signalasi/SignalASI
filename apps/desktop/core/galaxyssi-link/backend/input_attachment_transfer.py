"""Persistent, integrity-checked phone-to-Desktop attachment transfer."""
from __future__ import annotations

import base64
import binascii
import hashlib
import json
import os
import re
import shutil
import threading
import tempfile
import time
from dataclasses import dataclass
from pathlib import Path

from link_protocol import valid_route_id
from task_workspace import task_workspace, workspace_root


MAX_ATTACHMENT_BYTES = 1024 * 1024 * 1024
ATTACHMENT_CHUNK_BYTES = 256 * 1024
MAX_ATTACHMENT_CHUNKS = MAX_ATTACHMENT_BYTES // ATTACHMENT_CHUNK_BYTES
ATTACHMENT_REQUEST_WINDOW_CHUNKS = 16
MAX_ATTACHMENTS_PER_TASK = 10
TRANSFER_DIRECTORY = ".transfers"
MANIFEST_NAME = "manifest.json"
TRANSFER_TTL_SECONDS = 7 * 24 * 60 * 60
PRUNE_INTERVAL_SECONDS = 60 * 60
SHA256_PATTERN = re.compile(r"^[a-f0-9]{64}$")
_lock = threading.RLock()
_last_prune_at = 0.0


class AttachmentIntegrityError(ValueError):
    """A verified stream cannot be committed; retry requires a fresh source."""

    def __init__(self, message: str, code: str):
        super().__init__(message)
        self.code = code


@dataclass(frozen=True)
class AttachmentTransferReceipt:
    transfer_id: str
    status: str
    sha256: str
    attachment_id: str
    attachment_request_id: str
    name: str
    mime_type: str
    size_bytes: int
    client_route_id: str
    conversation_id: str
    task_id: str
    turn_id: str
    contact_id: str
    source_message_id: str
    received_bytes: int = 0
    progress: int = 0
    missing_ranges: tuple[tuple[int, int], ...] = ()
    error_code: str = ""

    def payload(self) -> dict:
        result = {
            "type": "input_attachment_receipt",
            "transfer_id": self.transfer_id,
            "status": self.status,
            "sha256": self.sha256,
            "attachment_id": self.attachment_id,
            "attachment_request_id": self.attachment_request_id,
            "name": self.name,
            "mime_type": self.mime_type,
            "size_bytes": self.size_bytes,
            "client_route_id": self.client_route_id,
            "conversation_id": self.conversation_id,
            "task_id": self.task_id,
            "turn_id": self.turn_id,
            "contact_id": self.contact_id,
            "source_message_id": self.source_message_id,
            "received_bytes": self.received_bytes,
            "progress": self.progress,
            "sender": "system",
            "time": time.time(),
        }
        if self.status == "missing":
            result["missing_ranges"] = [list(value) for value in self.missing_ranges]
        if self.status == "failed":
            from blob_failures import failure_observation
            failure_observation(self.error_code)
            result["error_code"] = self.error_code
        return result

    def descriptor(self) -> dict:
        return {
            "id": self.attachment_id,
            "transfer_id": self.transfer_id,
            "name": self.name,
            "mime_type": self.mime_type,
            "size": self.size_bytes,
            "transport_size": self.size_bytes,
            "sha256": self.sha256,
            "transport_status": "chunked",
            "attachment_request_id": self.attachment_request_id,
        }


def ingest_manifest(payload: dict, *, client_route_id: str) -> AttachmentTransferReceipt | None:
    """Persist a declaration and request the first bounded chunk window."""
    with _lock:
        prune_expired_transfers()
        manifest = _ensure_manifest(payload, client_route_id=client_route_id)
        missing = _missing_indices(manifest)
        if not missing:
            return _receipt_for(manifest)
        if bool(payload.get("eager_chunks")) and not bool(payload.get("resume")):
            manifest["requested_indices"] = missing
            _write_manifest(_transfer_directory(manifest), manifest)
            return None
        requested = missing[:ATTACHMENT_REQUEST_WINDOW_CHUNKS]
        manifest["requested_indices"] = requested
        _write_manifest(_transfer_directory(manifest), manifest)
        return _receipt_for(manifest, requested)


def ingest_chunk(
    payload: dict,
    *,
    client_route_id: str,
) -> AttachmentTransferReceipt | None:
    """Accept one idempotent chunk and return stored or missing state."""
    with _lock:
        prune_expired_transfers()
        manifest = _ensure_manifest(payload, client_route_id=client_route_id)
        if bool(manifest.get("complete")) and _completed_path(manifest).is_file():
            return _receipt_for(manifest)
        transfer_dir = _transfer_directory(manifest)
        index = _bounded_int(payload.get("chunk_index"), 0, int(manifest["chunk_count"]) - 1)
        encoded = str(payload.get("data_b64") or "")
        try:
            chunk = base64.b64decode(encoded, validate=True)
        except (ValueError, binascii.Error) as exc:
            raise ValueError("Attachment chunk encoding is invalid") from exc
        expected_size = _expected_chunk_size(manifest, index)
        if len(chunk) != expected_size:
            raise ValueError("Attachment chunk length does not match its manifest")
        chunk_digest = str(payload.get("chunk_sha256") or "").lower()
        if not SHA256_PATTERN.fullmatch(chunk_digest):
            raise ValueError("Attachment chunk hash is invalid")
        if not _constant_time_equal(_sha256_bytes(chunk), chunk_digest):
            raise ValueError("Attachment chunk integrity check failed")

        target = transfer_dir / f"{index:04d}.chunk"
        if target.is_file():
            previous = target.read_bytes()
            if previous != chunk:
                raise ValueError("Attachment chunk conflicts with an existing chunk")
        else:
            temporary = transfer_dir / f".{index:04d}.{os.getpid()}.tmp"
            temporary.write_bytes(chunk)
            os.replace(temporary, target)

        missing = _missing_indices(manifest)
        if missing:
            requested = [int(value) for value in manifest.get("requested_indices") or []]
            if any(index in missing for index in requested):
                return None
            next_window = missing[:ATTACHMENT_REQUEST_WINDOW_CHUNKS]
            manifest["requested_indices"] = next_window
            _write_manifest(transfer_dir, manifest)
            return _receipt_for(manifest, next_window)
        _assemble_verified_attachment(manifest)
        return _receipt_for(manifest)


def resume_after_rejection(payload: dict, *, client_route_id: str) -> AttachmentTransferReceipt | None:
    """Return the current missing set after an invalid or conflicting chunk."""
    with _lock:
        try:
            candidate = _validated_manifest(payload, client_route_id=client_route_id)
        except ValueError:
            return None
        manifest = _read_manifest(_transfer_directory(candidate))
        if manifest is None:
            return None
        try:
            _require_same_manifest(manifest, candidate)
        except ValueError:
            return None
        return _receipt_for(manifest, _missing_indices(manifest))


def resolved_attachment_path(
    descriptor: dict,
    *,
    client_route_id: str,
    conversation_id: str,
    task_id: str,
    turn_id: str,
) -> Path | None:
    """Resolve only a fully verified attachment bound to the active task scope."""
    transfer_id = str(descriptor.get("transfer_id") or "").lower()
    if not SHA256_PATTERN.fullmatch(transfer_id):
        return None
    with _lock:
        prune_expired_transfers()
        manifest = _read_manifest(_transfer_directory_for(task_id, transfer_id))
        if manifest is None:
            return None
        if any(
            str(manifest.get(key) or "") != expected
            for key, expected in (
                ("client_route_id", client_route_id),
                ("conversation_id", conversation_id),
                ("task_id", task_id),
                ("turn_id", turn_id),
                ("sha256", str(descriptor.get("sha256") or "").lower()),
            )
        ):
            return None
        target = _completed_path(manifest)
        if not target.is_file() or target.stat().st_size != int(manifest["size_bytes"]):
            return None
        if not bool(manifest.get("complete")):
            return None
        return target


def validate_input_manifest(payload: dict, *, client_route_id: str) -> dict:
    """Validate metadata without creating a workspace or acknowledging bytes."""
    return _validated_manifest(payload, client_route_id=client_route_id)


def ingest_verified_stream(payload: dict, blocks, *, client_route_id: str,
                           check_active=lambda: None) -> AttachmentTransferReceipt:
    """Commit an authenticated Blob stream to the existing scoped input contract."""
    with _lock:
        manifest = _ensure_manifest(payload, client_route_id=client_route_id)
        target = _completed_path(manifest)
    target.parent.mkdir(parents=True, exist_ok=True)
    descriptor, temporary_name = tempfile.mkstemp(prefix=".blob-", suffix=".part", dir=target.parent)
    temporary = Path(temporary_name)
    digest, written = hashlib.sha256(), 0
    try:
        # Network and large-file I/O must not hold the global attachment lock.
        with os.fdopen(descriptor, "wb") as output:
            for block in blocks:
                check_active()
                written += len(block)
                if written > manifest["size_bytes"]:
                    raise AttachmentIntegrityError("Attachment transfer exceeds declared length", "file_size_mismatch")
                digest.update(block)
                output.write(block)
            if written != manifest["size_bytes"] or digest.hexdigest() != manifest["sha256"]:
                raise AttachmentIntegrityError("Attachment transfer integrity check failed", "plaintext_hash_mismatch")
            output.flush()
            os.fsync(output.fileno())
        check_active()
        with _lock:
            current = _ensure_manifest(payload, client_route_id=client_route_id)
            _require_same_manifest(current, manifest)
            os.replace(temporary, target)
            current["complete"] = True
            current["completed_at"] = int(time.time() * 1000)
            _write_manifest(_transfer_directory(current), current)
            return _receipt_for(current)
    finally:
        temporary.unlink(missing_ok=True)


def prune_expired_transfers(
    *,
    now_seconds: float | None = None,
    force: bool = False,
) -> int:
    """Remove abandoned transfer state and unclaimed completed input files."""
    with _lock:
        return _prune_expired_transfers_locked(
            now_seconds=now_seconds,
            force=force,
        )


def _prune_expired_transfers_locked(
    *,
    now_seconds: float | None,
    force: bool,
) -> int:
    global _last_prune_at
    now = time.time() if now_seconds is None else float(now_seconds)
    if not force and now - _last_prune_at < PRUNE_INTERVAL_SECONDS:
        return 0
    _last_prune_at = now
    tasks_root = workspace_root() / "tasks"
    if not tasks_root.is_dir():
        return 0
    removed = 0
    cutoff_millis = int((now - TRANSFER_TTL_SECONDS) * 1000)
    for manifest_path in tasks_root.glob(
        f"*/downloads/input/{TRANSFER_DIRECTORY}/*/{MANIFEST_NAME}"
    ):
        transfer_dir = manifest_path.parent
        manifest = _read_manifest(transfer_dir)
        created_at = (
            int(manifest.get("created_at") or 0)
            if isinstance(manifest, dict)
            else int(manifest_path.stat().st_mtime * 1000)
        )
        if created_at >= cutoff_millis:
            continue
        if isinstance(manifest, dict):
            try:
                _completed_path_without_creating_workspace(manifest, transfer_dir).unlink(
                    missing_ok=True
                )
            except (OSError, TypeError, ValueError):
                pass
        shutil.rmtree(transfer_dir, ignore_errors=True)
        removed += 1
    return removed


def _ensure_manifest(payload: dict, *, client_route_id: str) -> dict:
    manifest = _validated_manifest(payload, client_route_id=client_route_id)
    transfer_dir = _transfer_directory(manifest)
    existing = _read_manifest(transfer_dir)
    if existing is not None:
        _require_same_manifest(existing, manifest)
        return existing
    attachment_root = transfer_dir.parent
    # Recovery attempts are distinct transfers of the same logical attachment.
    existing_attachments = set()
    if attachment_root.is_dir():
        for candidate in attachment_root.iterdir():
            if candidate.is_dir() and SHA256_PATTERN.fullmatch(candidate.name):
                previous = _read_manifest(candidate)
                existing_attachments.add(previous.get("attachment_id", candidate.name)
                                         if previous else candidate.name)
    if (manifest["attachment_id"] not in existing_attachments and
            len(existing_attachments) >= MAX_ATTACHMENTS_PER_TASK):
        raise ValueError("Attachment transfer count exceeds the task limit")
    transfer_dir.mkdir(parents=True, exist_ok=False)
    _write_manifest(transfer_dir, manifest)
    return manifest


def _validated_manifest(payload: dict, *, client_route_id: str) -> dict:
    route = str(payload.get("client_route_id") or "").strip()
    if route != client_route_id or not valid_route_id(route):
        raise ValueError("Attachment route identity is invalid")
    conversation_id = _identity(payload, "conversation_id")
    task_id = _identity(payload, "task_id")
    turn_id = _identity(payload, "turn_id")
    contact_id = _identity(payload, "contact_id")
    attachment_id = _identity(payload, "attachment_id")
    attachment_request_id = str(payload.get("attachment_request_id") or "").strip()
    if attachment_request_id and not re.fullmatch(r"[a-f0-9]{32}", attachment_request_id):
        raise ValueError("Attachment request identity is invalid")
    transfer_id = str(payload.get("transfer_id") or "").lower()
    digest = str(payload.get("sha256") or "").lower()
    if not SHA256_PATTERN.fullmatch(transfer_id) or not SHA256_PATTERN.fullmatch(digest):
        raise ValueError("Attachment transfer hash is invalid")
    expected_transfer_id = transfer_id_for(
        route,
        conversation_id,
        task_id,
        turn_id,
        attachment_id,
        digest,
        attachment_request_id,
    )
    if not _constant_time_equal(transfer_id, expected_transfer_id):
        raise ValueError("Attachment transfer identity does not match its scope")
    size_bytes = _bounded_int(payload.get("size_bytes"), 1, MAX_ATTACHMENT_BYTES)
    chunk_size = _bounded_int(
        payload.get("chunk_size_bytes"),
        ATTACHMENT_CHUNK_BYTES,
        ATTACHMENT_CHUNK_BYTES,
    )
    chunk_count = _bounded_int(payload.get("chunk_count"), 1, MAX_ATTACHMENT_CHUNKS)
    if chunk_count != (size_bytes + chunk_size - 1) // chunk_size:
        raise ValueError("Attachment chunk count does not match its size")
    ordinal = _bounded_int(payload.get("attachment_ordinal"), 0, MAX_ATTACHMENTS_PER_TASK - 1)
    name = _safe_name(str(payload.get("name") or f"attachment-{ordinal + 1}"))
    return {
        "transfer_id": transfer_id,
        "attachment_id": attachment_id,
        "attachment_request_id": attachment_request_id,
        "attachment_ordinal": ordinal,
        "name": name,
        "original_name": str(payload.get("original_name") or name)[:256],
        "mime_type": str(payload.get("mime_type") or "application/octet-stream")[:160],
        "size_bytes": size_bytes,
        "original_size_bytes": max(0, int(payload.get("original_size_bytes") or size_bytes)),
        "sha256": digest,
        "chunk_count": chunk_count,
        "chunk_size_bytes": chunk_size,
        "client_route_id": route,
        "conversation_id": conversation_id,
        "task_id": task_id,
        "turn_id": turn_id,
        "contact_id": contact_id,
        "source_message_id": (
            str(payload.get("client_message_id"))
            if payload.get("client_message_id") is not None
            else ""
        ),
        "created_at": int(time.time() * 1000),
        "complete": False,
    }


def _require_same_manifest(existing: dict, candidate: dict) -> None:
    immutable = (
        "transfer_id",
        "attachment_id",
        "attachment_request_id",
        "attachment_ordinal",
        "name",
        "mime_type",
        "size_bytes",
        "sha256",
        "chunk_count",
        "chunk_size_bytes",
        "client_route_id",
        "conversation_id",
        "task_id",
        "turn_id",
        "contact_id",
    )
    if any(existing.get(key) != candidate.get(key) for key in immutable):
        raise ValueError("Attachment transfer manifest conflicts with stored state")


def _receipt_for(
    manifest: dict,
    missing: list[int] | None = None,
) -> AttachmentTransferReceipt:
    complete_path = _completed_path(manifest)
    complete = bool(manifest.get("complete")) and complete_path.is_file()
    all_missing = [] if complete else _missing_indices(manifest)
    missing_bytes = sum(_expected_chunk_size(manifest, index) for index in all_missing)
    received_bytes = max(0, int(manifest["size_bytes"]) - missing_bytes)
    progress = 100 if complete else min(99, received_bytes * 100 // int(manifest["size_bytes"]))
    if complete:
        status = "stored"
        missing_ranges: tuple[tuple[int, int], ...] = ()
    else:
        status = "missing"
        missing_ranges = tuple(_compact_ranges(
            _missing_indices(manifest) if missing is None else missing
        ))
    return AttachmentTransferReceipt(
        transfer_id=str(manifest["transfer_id"]),
        status=status,
        sha256=str(manifest["sha256"]),
        attachment_id=str(manifest["attachment_id"]),
        attachment_request_id=str(manifest.get("attachment_request_id") or ""),
        name=str(manifest["name"]),
        mime_type=str(manifest["mime_type"]),
        size_bytes=int(manifest["size_bytes"]),
        client_route_id=str(manifest["client_route_id"]),
        conversation_id=str(manifest["conversation_id"]),
        task_id=str(manifest["task_id"]),
        turn_id=str(manifest["turn_id"]),
        contact_id=str(manifest["contact_id"]),
        source_message_id=str(manifest.get("source_message_id") or ""),
        received_bytes=received_bytes,
        progress=progress,
        missing_ranges=missing_ranges,
    )


def _missing_indices(manifest: dict) -> list[int]:
    if bool(manifest.get("complete")) and _completed_path(manifest).is_file():
        return []
    transfer_dir = _transfer_directory(manifest)
    return [
        index for index in range(int(manifest["chunk_count"]))
        if not (transfer_dir / f"{index:04d}.chunk").is_file()
    ]


def _assemble_verified_attachment(manifest: dict) -> None:
    transfer_dir = _transfer_directory(manifest)
    target = _completed_path(manifest)
    target.parent.mkdir(parents=True, exist_ok=True)
    temporary = target.parent / f".{target.name}.{manifest['transfer_id'][:12]}.part"
    digest = hashlib.sha256()
    written = 0
    try:
        with temporary.open("wb") as output:
            for index in range(int(manifest["chunk_count"])):
                chunk_path = transfer_dir / f"{index:04d}.chunk"
                with chunk_path.open("rb") as source:
                    while True:
                        block = source.read(64 * 1024)
                        if not block:
                            break
                        written += len(block)
                        if written > int(manifest["size_bytes"]):
                            raise ValueError("Attachment transfer exceeds declared length")
                        digest.update(block)
                        output.write(block)
        if written != int(manifest["size_bytes"]):
            raise ValueError("Attachment transfer length check failed")
        if not _constant_time_equal(digest.hexdigest(), str(manifest["sha256"])):
            raise ValueError("Attachment transfer integrity check failed")
        os.replace(temporary, target)
        manifest["complete"] = True
        manifest["completed_at"] = int(time.time() * 1000)
        _write_manifest(transfer_dir, manifest)
        for chunk_path in transfer_dir.glob("*.chunk"):
            chunk_path.unlink(missing_ok=True)
    except Exception:
        manifest["complete"] = False
        manifest.pop("completed_at", None)
        _write_manifest(transfer_dir, manifest)
        for chunk_path in transfer_dir.glob("*.chunk"):
            chunk_path.unlink(missing_ok=True)
        raise
    finally:
        temporary.unlink(missing_ok=True)


def _transfer_directory(manifest: dict) -> Path:
    return _transfer_directory_for(
        str(manifest["task_id"]),
        str(manifest["transfer_id"]),
    )


def _transfer_directory_for(task_id: str, transfer_id: str) -> Path:
    root = task_workspace(task_id) / "downloads" / "input" / TRANSFER_DIRECTORY
    root.mkdir(parents=True, exist_ok=True)
    target = (root / transfer_id).resolve()
    if target.parent != root.resolve() or not SHA256_PATTERN.fullmatch(transfer_id):
        raise ValueError("Attachment transfer path is unsafe")
    return target


def _completed_path(manifest: dict) -> Path:
    input_root = task_workspace(str(manifest["task_id"])) / "downloads" / "input"
    target = (
        input_root
        / (
            f"{int(manifest['attachment_ordinal']) + 1:02d}-"
            f"{str(manifest['transfer_id'])[:12]}-"
            f"{_safe_name(str(manifest['name']))}"
        )
    ).resolve()
    if target.parent != input_root.resolve():
        raise ValueError("Attachment output path is unsafe")
    return target


def _completed_path_without_creating_workspace(manifest: dict, transfer_dir: Path) -> Path:
    input_root = transfer_dir.parent.parent.resolve()
    target = (
        input_root
        / (
            f"{int(manifest['attachment_ordinal']) + 1:02d}-"
            f"{str(manifest['transfer_id'])[:12]}-"
            f"{_safe_name(str(manifest['name']))}"
        )
    ).resolve()
    if target.parent != input_root:
        raise ValueError("Attachment output path is unsafe")
    return target


def _read_manifest(transfer_dir: Path) -> dict | None:
    path = transfer_dir / MANIFEST_NAME
    try:
        value = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, ValueError, TypeError):
        return None
    return value if isinstance(value, dict) else None


def _write_manifest(transfer_dir: Path, manifest: dict) -> None:
    temporary = transfer_dir / f".{MANIFEST_NAME}.{os.getpid()}.tmp"
    try:
        with temporary.open("w", encoding="utf-8") as output:
            json.dump(manifest, output, ensure_ascii=True, separators=(",", ":"))
            output.flush()
            os.fsync(output.fileno())
        os.replace(temporary, transfer_dir / MANIFEST_NAME)
    finally:
        temporary.unlink(missing_ok=True)


def _expected_chunk_size(manifest: dict, index: int) -> int:
    start = index * int(manifest["chunk_size_bytes"])
    return min(
        int(manifest["chunk_size_bytes"]),
        int(manifest["size_bytes"]) - start,
    )


def _compact_ranges(indices: list[int]) -> list[tuple[int, int]]:
    values = sorted(set(indices))
    if not values:
        return []
    ranges: list[tuple[int, int]] = []
    start = previous = values[0]
    for value in values[1:]:
        if value == previous + 1:
            previous = value
            continue
        ranges.append((start, previous))
        start = previous = value
    ranges.append((start, previous))
    return ranges


def _identity(payload: dict, key: str) -> str:
    value = str(payload.get(key) or "").strip()
    if not value or len(value) > 256 or any(ord(character) < 32 for character in value):
        raise ValueError(f"Attachment {key} is invalid")
    return value


def _bounded_int(value: object, minimum: int, maximum: int) -> int:
    try:
        parsed = int(value)
    except (TypeError, ValueError) as exc:
        raise ValueError("Attachment numeric metadata is invalid") from exc
    if parsed < minimum or parsed > maximum:
        raise ValueError("Attachment numeric metadata is out of bounds")
    return parsed


def _safe_name(value: str) -> str:
    name = Path(str(value or "").replace("\\", "/")).name
    name = re.sub(r"[\x00-\x1f<>:\"/\\|?*]+", "_", name).strip(" .")[:180]
    return name or "attachment"


def transfer_id_for(
    client_route_id: str,
    conversation_id: str,
    task_id: str,
    turn_id: str,
    attachment_id: str,
    digest: str,
    attachment_request_id: str = "",
) -> str:
    canonical = "\0".join(
        (
            client_route_id,
            conversation_id,
            task_id,
            turn_id,
            attachment_id,
            digest,
        ) + ((attachment_request_id,) if attachment_request_id else ())
    )
    return hashlib.sha256(canonical.encode("utf-8")).hexdigest()


def _sha256_bytes(value: bytes) -> str:
    return hashlib.sha256(value).hexdigest()


def _constant_time_equal(first: str, second: str) -> bool:
    import secrets

    return secrets.compare_digest(str(first).lower(), str(second).lower())
