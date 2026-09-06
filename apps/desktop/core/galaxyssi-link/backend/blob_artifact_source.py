"""Workspace-backed Blob source leases and byte-exact compressed-image snapshots."""
from __future__ import annotations

from dataclasses import replace
import hashlib
import os
from pathlib import Path
import tempfile

from artifact_delivery import (ARTIFACT_CHUNK_BYTES, PreparedArtifact, acknowledge_artifact, open_registered_artifact_source,
                               register_artifact_batch)
from blob_artifact_contract import validate_manifest
from blob_artifact_journal import validate_job
from blob_protocol import BlobError
from task_workspace import _safe_component, task_artifact_path, workspace_root


def prepare_source(artifact: PreparedArtifact, manifest: dict) -> str:
    value = validate_manifest(manifest)
    fields = ("artifact_id", "task_id", "name", "relative_path", "mime_type", "size_bytes", "sha256",
              "original_size_bytes", "original_sha256")
    if any(value[key] != getattr(artifact, key) for key in fields):
        raise BlobError("artifact_blob_source_manifest_mismatch", 409)
    original = task_artifact_path(artifact.task_id, artifact.relative_path)
    if original is None or artifact.source_path.is_symlink() or original != artifact.source_path.resolve():
        raise BlobError("invalid_source_file", 409)
    source = original
    if artifact.transport_bytes is not None:
        raw = artifact.transport_bytes
        if len(raw) != value["size_bytes"] or hashlib.sha256(raw).hexdigest() != value["sha256"]:
            raise BlobError("source_changed", 409)
        # Hidden task-owned snapshots are excluded from generated deliverables.
        # Their content-addressed name is stable across retries and distinct from
        # the user's original image. They are removed with workspace handoff.
        source = original.parent / f".blob-output-{value['sha256']}.bin"
        _snapshot(source, raw)
    relative = source.resolve().relative_to(workspace_root().resolve()).as_posix()
    return relative


def register_source(artifact: PreparedArtifact, manifest: dict, *, retain_on_desktop: bool) -> str:
    relative = prepare_source(artifact, manifest)
    value = validate_manifest(manifest)
    stable = replace(artifact, source_path=workspace_root() / relative, transport_bytes=None, compress_images=False)
    register_artifact_batch([stable], client_route_id=value["client_route_id"],
        retain_on_desktop=retain_on_desktop, delivery_scopes={artifact.artifact_id: value["transfer_id"]})
    return relative


def register_source_batch(bodies: list[dict], *, retain_on_desktop: bool) -> bool:
    from blob_artifact_batches import validate_batch_bodies
    values = validate_batch_bodies(bodies)
    root = workspace_root().resolve()
    artifacts, scopes = [], {}
    for body in values:
        manifest = body["manifest"]
        safe_id = _safe_component(manifest["task_id"])
        if not safe_id:
            raise BlobError("invalid_source_file", 409)
        task_root = root / "tasks" / safe_id
        original = (task_root / manifest["relative_path"]).resolve()
        source = root / body["source_relative"]
        try:
            original.relative_to(task_root.resolve())
            if (source.is_symlink() or source.resolve().relative_to(root).as_posix() != body["source_relative"]
                    or source.resolve() not in {original, original.parent / f".blob-output-{manifest['sha256']}.bin"}):
                raise ValueError()
        except ValueError:
            raise BlobError("invalid_source_file", 409) from None
        if not source.is_file():
            raise BlobError("artifact_source_missing", 409)
        if source.stat().st_size != manifest["size_bytes"]:
            raise BlobError("source_changed", 409)
        fields = ("artifact_id", "task_id", "name", "relative_path", "artifact_uri", "mime_type", "size_bytes",
                  "sha256", "original_size_bytes", "original_sha256")
        artifacts.append(PreparedArtifact(**{key: manifest[key] for key in fields}, source_path=source,
            chunk_count=(manifest["size_bytes"] + ARTIFACT_CHUNK_BYTES - 1) // ARTIFACT_CHUNK_BYTES, compress_images=False))
        scopes[manifest["artifact_id"]] = manifest["transfer_id"]
    register_artifact_batch(artifacts, client_route_id=values[0]["manifest"]["client_route_id"],
        retain_on_desktop=retain_on_desktop, delivery_scopes=scopes)
    return True


def _snapshot(path: Path, raw: bytes):
    if path.is_symlink():
        raise BlobError("invalid_source_file", 409)
    if path.exists():
        if not path.is_file() or path.stat().st_size != len(raw):
            raise BlobError("source_changed", 409)
        with path.open("rb") as stream:
            digest = hashlib.file_digest(stream, "sha256").hexdigest()
        if digest != hashlib.sha256(raw).hexdigest():
            raise BlobError("source_changed", 409)
        return
    temporary = None
    try:
        with tempfile.NamedTemporaryFile(prefix=".blob-output-", suffix=".tmp", dir=path.parent, delete=False) as stream:
            temporary = Path(stream.name)
            stream.write(raw)
            stream.flush()
            os.fsync(stream.fileno())
        os.replace(temporary, path)
    finally:
        if temporary is not None:
            temporary.unlink(missing_ok=True)


def open_source(body: dict):
    value = validate_job(body)
    manifest = value["manifest"]
    try:
        return open_registered_artifact_source(artifact_id=manifest["artifact_id"],
            client_route_id=manifest["client_route_id"], delivery_scope=manifest["transfer_id"],
            source_relative=value["source_relative"], sha256=manifest["sha256"])
    except ValueError:
        raise BlobError("artifact_blob_source_lease_mismatch", 409) from None


def commit_receipt(body: dict) -> bool:
    value = validate_job(body)
    manifest = value["manifest"]
    return acknowledge_artifact({"artifact_id": manifest["artifact_id"], "sha256": manifest["sha256"], "status": "stored"},
        client_route_id=manifest["client_route_id"], delivery_scope=manifest["transfer_id"])
