"""Reliable, phone-owned delivery for Agent task artifacts."""
from __future__ import annotations

import base64
import hashlib
import json
import mimetypes
import os
import threading
import time
from dataclasses import dataclass
from pathlib import Path
from urllib.parse import quote

from image_transport import MAX_IMAGE_TRANSPORT_BYTES, compress_image_file
from blob_protocol import MAX_FILE_BYTES, BlobError
from task_workspace import cleanup_task_workspace, task_artifact_path, task_workspace, workspace_root


MAX_ARTIFACT_BYTES = 64 * 1024 * 1024
ARTIFACT_CHUNK_BYTES = 256 * 1024
MAX_ARTIFACT_CHUNKS = MAX_ARTIFACT_BYTES // ARTIFACT_CHUNK_BYTES
LEDGER_NAME = ".artifact-delivery-ledger.json"
LEDGER_TTL_SECONDS = 7 * 24 * 60 * 60
APK_MIME_TYPE = "application/vnd.android.package-archive"
MIME_OVERRIDES = {
    ".apk": APK_MIME_TYPE,
    ".aab": "application/octet-stream",
    ".apks": "application/zip",
    ".docx": "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
    ".pptx": "application/vnd.openxmlformats-officedocument.presentationml.presentation",
    ".xlsx": "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
}
IMAGE_SUFFIXES = {".avif", ".bmp", ".gif", ".heic", ".heif", ".jpeg", ".jpg", ".png", ".webp"}
INTERNAL_SUFFIXES = (".idsig", ".sig", ".sha256", ".sha512")
_ledger_lock = threading.RLock()


def should_deliver_task_artifacts(
    *,
    fast_chat_delivery: bool,
    plan_only: bool,
    generated_output_files: list[dict] | tuple[dict, ...] = (),
    referenced_output_paths: list[Path] | tuple[Path, ...] = (),
) -> bool:
    """Keep the chat fast path unless a run actually produced a deliverable."""
    if plan_only:
        return False
    return bool(
        not fast_chat_delivery
        or generated_output_files
        or referenced_output_paths
    )


@dataclass(frozen=True)
class PreparedArtifact:
    artifact_id: str
    task_id: str
    name: str
    relative_path: str
    artifact_uri: str
    mime_type: str
    size_bytes: int
    sha256: str
    original_size_bytes: int
    original_sha256: str
    chunk_count: int
    source_path: Path
    compress_images: bool = True
    transport_bytes: bytes | None = None

    def chunks(self):
        if self.transport_bytes is not None:
            for index in range(self.chunk_count):
                start = index * ARTIFACT_CHUNK_BYTES
                yield index, self.transport_bytes[start : start + ARTIFACT_CHUNK_BYTES]
            return
        with self.source_path.open("rb") as stream:
            for index in range(self.chunk_count):
                chunk = stream.read(ARTIFACT_CHUNK_BYTES)
                if not chunk:
                    raise OSError("Artifact ended before declared chunk count")
                yield index, chunk


def prepare_artifacts(
    task_id: str,
    output_files: list[dict] | None,
    *,
    compress_images: bool = True,
    maximum_bytes: int = MAX_ARTIFACT_BYTES,
    strict: bool = False,
) -> list[PreparedArtifact]:
    if type(maximum_bytes) is not int or maximum_bytes not in {MAX_ARTIFACT_BYTES, MAX_FILE_BYTES}:
        raise ValueError("Invalid artifact preparation limit")
    prepared: list[PreparedArtifact] = []
    seen: set[str] = set()
    for item in output_files or []:
        if not isinstance(item, dict):
            if strict:
                raise BlobError("artifact_source_unavailable", 409)
            continue
        relative_path = str(item.get("relative_path") or "").replace("\\", "/").strip("/")
        source = task_artifact_path(task_id, relative_path)
        if source is None:
            if strict:
                raise BlobError("artifact_source_unavailable", 409)
            continue
        if source.name.startswith(".") or source.name.lower().endswith(INTERNAL_SUFFIXES):
            continue
        size = source.stat().st_size
        if size <= 0 or size > maximum_bytes:
            if strict:
                code = ("artifact_source_empty" if size <= 0 else
                        "artifact_blob_size_exceeded" if size > MAX_FILE_BYTES else
                        "artifact_blob_transport_required" if maximum_bytes == MAX_ARTIFACT_BYTES else
                        "artifact_blob_size_exceeded")
                raise BlobError(code, 409)
            continue
        source_key = str(source).casefold()
        if source_key in seen:
            continue
        seen.add(source_key)
        artifact = _prepare_artifact(
            task_id,
            source,
            relative_path,
            item,
            compress_images=compress_images,
            maximum_bytes=maximum_bytes,
        )
        if artifact is not None:
            prepared.append(artifact)
        elif strict:
            raise BlobError("artifact_preparation_failed", 409)
    return prepared


def artifact_chunk_payloads(
    artifact: PreparedArtifact,
    *,
    common: dict | None = None,
):
    if artifact.size_bytes > MAX_ARTIFACT_BYTES:
        raise BlobError("artifact_blob_transport_required", 409)
    base = dict(common or {})
    for chunk_index, chunk in artifact.chunks():
        payload = dict(base)
        payload.update({
            "type": "artifact_chunk",
            "artifact_id": artifact.artifact_id,
            "task_id": artifact.task_id,
            "artifact_uri": artifact.artifact_uri,
            "name": artifact.name,
            "relative_path": artifact.relative_path,
            "mime_type": artifact.mime_type,
            "size_bytes": artifact.size_bytes,
            "sha256": artifact.sha256,
            "original_size_bytes": artifact.original_size_bytes,
            "original_sha256": artifact.original_sha256,
            "chunk_index": chunk_index,
            "chunk_count": artifact.chunk_count,
            "chunk_size_bytes": len(chunk),
            "chunk_sha256": hashlib.sha256(chunk).hexdigest(),
            "data_b64": base64.b64encode(chunk).decode("ascii"),
            "phone_owned": True,
            "sender": "other",
            "time": time.time(),
        })
        yield payload


def register_artifact_batch(
    artifacts: list[PreparedArtifact],
    *,
    client_route_id: str,
    retain_on_desktop: bool,
    delivery_scopes: dict[str, str] | None = None,
) -> None:
    if not artifacts:
        return
    with _ledger_lock:
        ledger = _read_ledger()
        _prune_ledger(ledger)
        now = int(time.time())
        for artifact in artifacts:
            scope = "" if delivery_scopes is None else _delivery_scope(delivery_scopes.get(artifact.artifact_id), required=True)
            key = _delivery_key(artifact.artifact_id, client_route_id, scope)
            existing = ledger.get(key)
            if existing is not None:
                if any(existing.get(field) != value for field, value in (
                    ("task_id", artifact.task_id), ("sha256", artifact.sha256),
                    ("source_path", _workspace_relative(artifact.source_path)),
                )):
                    raise ValueError("Artifact delivery identity conflict")
                existing["retain_on_desktop"] = bool(existing.get("retain_on_desktop") or retain_on_desktop)
                continue
            ledger[key] = {
                "artifact_id": artifact.artifact_id,
                "delivery_scope": scope,
                "task_id": artifact.task_id,
                "client_route_id": str(client_route_id or ""),
                "sha256": artifact.sha256,
                "source_path": _workspace_relative(artifact.source_path),
                "retain_on_desktop": bool(retain_on_desktop),
                "compress_images": bool(artifact.compress_images),
                "state": "pending",
                "created_at": now,
            }
        _write_ledger(ledger)


def acknowledge_artifact(payload: dict, *, client_route_id: str, delivery_scope: str = "") -> bool:
    artifact_id = str(payload.get("artifact_id") or "").lower()
    digest = str(payload.get("sha256") or "").lower()
    if (
        len(artifact_id) != 64
        or len(digest) != 64
        or str(payload.get("status") or "") != "stored"
    ):
        return False
    with _ledger_lock:
        ledger = _read_ledger()
        # The scope is supplied only by the authenticated Blob job callback, not
        # read from an arbitrary legacy receipt payload.
        entry = ledger.get(_delivery_key(artifact_id, client_route_id, delivery_scope))
        if not isinstance(entry, dict):
            return False
        if (
            str(entry.get("client_route_id") or "") != str(client_route_id or "")
            or str(entry.get("sha256") or "").lower() != digest
        ):
            return False
        entry["state"] = "stored"
        entry["stored_at"] = int(time.time())
        task_id = str(entry.get("task_id") or "")
        task_entries = [
            item
            for item in ledger.values()
            if isinstance(item, dict)
            and str(item.get("task_id") or "") == task_id
        ]
        complete = bool(task_entries) and all(item.get("state") == "stored" for item in task_entries)
        retain = any(bool(item.get("retain_on_desktop")) for item in task_entries)
        # Persist the receipt before any irreversible source cleanup. All recipients
        # of this workspace participate, including those with different artifact IDs.
        _write_ledger(ledger)
        if complete:
            if task_id and not retain and not all(item.get("cleanup_done") for item in task_entries):
                if not cleanup_task_workspace(task_id, missing_ok=True):
                    raise OSError("Artifact workspace cleanup incomplete")
            for item in task_entries:
                item["cleanup_done"] = True
                item.setdefault("completed_at", int(time.time()))
        _write_ledger(ledger)
        return True


def artifact_for_redelivery(
    payload: dict,
    *,
    client_route_id: str,
) -> PreparedArtifact | None:
    """Restore a pending artifact only for the phone route that owns it."""
    artifact_id = str(payload.get("artifact_id") or "").strip().lower()
    artifact_uri = str(payload.get("artifact_uri") or "").strip()
    digest = str(payload.get("sha256") or "").strip().lower()
    if len(artifact_id) != 64 or len(digest) != 64 or not artifact_uri:
        return None
    with _ledger_lock:
        ledger = _read_ledger()
        _prune_ledger(ledger)
        entry = ledger.get(_delivery_key(artifact_id, client_route_id))
        if not isinstance(entry, dict):
            _write_ledger(ledger)
            return None
        if (
            str(entry.get("client_route_id") or "") != str(client_route_id or "")
            or str(entry.get("sha256") or "").lower() != digest
        ):
            return None
        task_id = str(entry.get("task_id") or "").strip()
        source_relative = str(entry.get("source_path") or "").replace("\\", "/").strip("/")
        source = (workspace_root() / source_relative).resolve()
        try:
            source.relative_to(workspace_root().resolve())
            relative_path = source.relative_to(task_workspace(task_id).resolve()).as_posix()
        except (OSError, ValueError):
            return None
        if not source.is_file() or source.is_symlink():
            return None
        restored = _prepare_artifact(
            task_id,
            source,
            relative_path,
            {"name": source.name, "relative_path": relative_path},
            compress_images=bool(entry.get("compress_images", True)),
        )
        if (
            restored is None
            or restored.artifact_id != artifact_id
            or restored.artifact_uri != artifact_uri
            or restored.sha256 != digest
        ):
            return None
        entry["last_redelivery_at"] = int(time.time())
        entry["redelivery_count"] = int(entry.get("redelivery_count") or 0) + 1
        _write_ledger(ledger)
        return restored


def open_registered_artifact_source(*, artifact_id: str, client_route_id: str,
                                    delivery_scope: str, source_relative: str, sha256: str):
    """A Blob worker opens only its captured source lease; no global artifact lookup."""
    scope = _delivery_scope(delivery_scope, required=True)
    with _ledger_lock:
        entry = _read_ledger().get(_delivery_key(artifact_id, client_route_id, scope))
        if not isinstance(entry, dict) or any(entry.get(key) != value for key, value in (
            ("source_path", source_relative), ("sha256", sha256), ("state", "pending"),
        )):
            raise ValueError("Artifact source lease mismatch")
        root = workspace_root().resolve()
        source = root / source_relative
        if source.is_symlink() or source.resolve().relative_to(root).as_posix() != source_relative:
            raise ValueError("Artifact source path mismatch")
        return source.open("rb")


def pending_artifacts_for_redelivery(
    *,
    limit: int = 32,
) -> list[tuple[str, PreparedArtifact]]:
    """Rebuild unacknowledged phone-owned artifacts after transport recovery."""
    with _ledger_lock:
        ledger = _read_ledger()
        _prune_ledger(ledger)
        candidates = [
            (entry["artifact_id"], dict(entry))
            for entry in ledger.values()
            if isinstance(entry, dict) and str(entry.get("state") or "pending") == "pending"
            and not entry.get("delivery_scope")
        ][:max(1, int(limit))]
        _write_ledger(ledger)
    restored: list[tuple[str, PreparedArtifact]] = []
    for artifact_id, entry in candidates:
        client_route_id = str(entry.get("client_route_id") or "")
        task_id = str(entry.get("task_id") or "")
        source_relative = str(entry.get("source_path") or "").replace("\\", "/").strip("/")
        source = (workspace_root() / source_relative).resolve()
        try:
            source.relative_to(workspace_root().resolve())
            relative_path = source.relative_to(task_workspace(task_id).resolve()).as_posix()
        except (OSError, ValueError):
            continue
        if not client_route_id or not source.is_file() or source.is_symlink():
            continue
        artifact = _prepare_artifact(
            task_id,
            source,
            relative_path,
            {"name": source.name, "relative_path": relative_path},
            compress_images=bool(entry.get("compress_images", True)),
        )
        if (
            artifact is not None
            and artifact.artifact_id == artifact_id
            and artifact.sha256 == str(entry.get("sha256") or "").lower()
        ):
            restored.append((client_route_id, artifact))
    return restored


def discard_task_workspace_if_no_artifacts(
    task_id: str,
    artifacts: list[PreparedArtifact],
    *,
    retain_on_desktop: bool,
) -> None:
    if task_id and not artifacts and not retain_on_desktop:
        cleanup_task_workspace(task_id)


def _prepare_artifact(
    task_id: str,
    source: Path,
    relative_path: str,
    metadata: dict,
    *,
    compress_images: bool = True,
    maximum_bytes: int = MAX_ARTIFACT_BYTES,
) -> PreparedArtifact | None:
    name = str(metadata.get("name") or source.name).strip() or source.name
    original_size = source.stat().st_size
    if not 0 < original_size <= maximum_bytes:
        return None
    original_digest = _file_sha256(source)
    mime_type = _guess_mime_type(name)
    transport_bytes: bytes | None = None
    transport_size = original_size
    transport_digest = original_digest
    if compress_images and source.suffix.lower() in IMAGE_SUFFIXES:
        if original_size <= MAX_IMAGE_TRANSPORT_BYTES:
            transport_bytes = source.read_bytes()
        else:
            compressed = compress_image_file(source, MAX_IMAGE_TRANSPORT_BYTES)
            if compressed is None:
                return None
            transport_bytes = compressed.data
            mime_type = compressed.mime_type
        transport_size = len(transport_bytes)
        transport_digest = hashlib.sha256(transport_bytes).hexdigest()
    chunk_count = (transport_size + ARTIFACT_CHUNK_BYTES - 1) // ARTIFACT_CHUNK_BYTES
    if chunk_count not in range(1, maximum_bytes // ARTIFACT_CHUNK_BYTES + 1):
        return None
    artifact_uri = _artifact_uri(task_id, relative_path)
    artifact_id = hashlib.sha256(
        f"{artifact_uri}\0{transport_digest}".encode("utf-8")
    ).hexdigest()
    return PreparedArtifact(
        artifact_id=artifact_id,
        task_id=str(task_id or ""),
        name=name,
        relative_path=relative_path,
        artifact_uri=artifact_uri,
        mime_type=mime_type,
        size_bytes=transport_size,
        sha256=transport_digest,
        original_size_bytes=original_size,
        original_sha256=original_digest,
        chunk_count=chunk_count,
        source_path=source,
        compress_images=compress_images,
        transport_bytes=transport_bytes,
    )


def _artifact_uri(task_id: str, relative_path: str) -> str:
    return (
        f"galaxyssi-artifact://{quote(str(task_id or 'task'), safe='')}/"
        f"{quote(relative_path, safe='/')}"
    )


def _guess_mime_type(name: str) -> str:
    suffix = Path(str(name or "")).suffix.lower()
    return MIME_OVERRIDES.get(suffix) or mimetypes.guess_type(name)[0] or "application/octet-stream"


def _file_sha256(source: Path) -> str:
    digest = hashlib.sha256()
    with source.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def _ledger_path() -> Path:
    return workspace_root() / LEDGER_NAME


def _delivery_scope(value: str, *, required: bool = False) -> str:
    if value == "" and not required:
        return ""
    if not isinstance(value, str) or len(value) != 64 or any(char not in "0123456789abcdef" for char in value):
        raise ValueError("Invalid artifact delivery scope")
    return value


def _delivery_key(artifact_id: str, client_route_id: str, delivery_scope: str = "") -> str:
    scope = _delivery_scope(delivery_scope)
    parts = [artifact_id, str(client_route_id or "")]
    if scope:
        parts.append(scope)
    identity = json.dumps(parts, separators=(",", ":"))
    return hashlib.sha256(identity.encode("utf-8")).hexdigest()


def _read_ledger() -> dict:
    path = _ledger_path()
    if not path.is_file():
        return {}
    try:
        value = json.loads(path.read_text(encoding="utf-8"))
        if not isinstance(value, dict):
            raise ValueError("Invalid artifact delivery ledger")
        normalized = {}
        for old_key, entry in value.items():
            if not isinstance(entry, dict):
                raise ValueError("Invalid artifact delivery entry")
            # Existing on-disk ownership must survive this local schema upgrade.
            artifact_id = str(entry.get("artifact_id") or old_key)
            entry["artifact_id"] = artifact_id
            key = _delivery_key(artifact_id, entry.get("client_route_id"), entry.get("delivery_scope", ""))
            if key in normalized:
                raise ValueError("Duplicate artifact delivery identity")
            normalized[key] = entry
        return normalized
    except (OSError, ValueError) as error:
        raise ValueError("Artifact delivery ledger unavailable") from error


def _write_ledger(ledger: dict) -> None:
    path = _ledger_path()
    temporary = path.with_suffix(".tmp")
    with temporary.open("w", encoding="utf-8") as stream:
        json.dump(ledger, stream, ensure_ascii=True, separators=(",", ":"))
        stream.flush()
        os.fsync(stream.fileno())
    os.replace(temporary, path)


def _prune_ledger(ledger: dict) -> None:
    cutoff = int(time.time()) - LEDGER_TTL_SECONDS
    tasks: dict[str, list[tuple[str, dict]]] = {}
    for key, entry in ledger.items():
        tasks.setdefault(str(entry.get("task_id") or ""), []).append((key, entry))
    for entries in tasks.values():
        # TTL removes completed receipts, never an offline recipient's source.
        if all(entry.get("state") == "stored" and entry.get("cleanup_done")
               and int(entry.get("completed_at") or entry.get("stored_at") or 0) < cutoff
               for _, entry in entries):
            for key, _ in entries:
                ledger.pop(key, None)


def _workspace_relative(source: Path) -> str:
    try:
        return source.resolve().relative_to(workspace_root().resolve()).as_posix()
    except ValueError:
        return ""
