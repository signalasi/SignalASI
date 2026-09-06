"""Preserve failed pre-publication intent without freezing it as the final answer."""
from __future__ import annotations

from pathlib import Path
import sqlite3
import threading
import uuid
import weakref

from blob_protocol import BlobError, MAX_FILE_BYTES, canonical, sha256
from secure_state import SecureStateError, read_secure_json, write_secure_json

_PURPOSE = "blob.artifact-deferred.v1"
_LOCK = threading.RLock()
_INTENT_LOCKS = weakref.WeakValueDictionary()
_FIELDS = ("task_id", "client_route_id", "conversation_id", "turn_id", "desktop_id", "contact_id",
           "source_message_id")
_BINDING = ("signal_name", "identity_fingerprint", "local_identity_fingerprint")
_REASONS = {
    "artifact_blob_transport_required": (
        "The large-file transport is not available. Restore it and retry sending; the task does not need to run again.",
        "\u5927\u6587\u4ef6\u4f20\u8f93\u901a\u9053\u5c1a\u4e0d\u53ef\u7528\u3002\u6062\u590d\u901a\u9053\u540e\u53ef\u91cd\u8bd5\u53d1\u9001\uff0c\u65e0\u9700\u91cd\u65b0\u6267\u884c\u4efb\u52a1\u3002"),
    "artifact_blob_size_exceeded": (
        "An attachment exceeds the 1 GiB transport limit.",
        "\u9644\u4ef6\u8d85\u8fc7\u4e86 1 GiB \u4f20\u8f93\u4e0a\u9650\u3002"),
    "artifact_source_unavailable": (
        "An attachment source cannot be read. Restore the file before retrying.",
        "\u65e0\u6cd5\u8bfb\u53d6\u9644\u4ef6\u6e90\u6587\u4ef6\uff0c\u8bf7\u6062\u590d\u6587\u4ef6\u540e\u91cd\u8bd5\u3002"),
    "artifact_source_empty": (
        "An attachment is empty and cannot be sent.",
        "\u9644\u4ef6\u6587\u4ef6\u4e3a\u7a7a\uff0c\u65e0\u6cd5\u53d1\u9001\u3002"),
    "artifact_source_changed": (
        "The original attachment has changed. Restore the original file before retrying this delivery.",
        "\u539f\u9644\u4ef6\u5df2\u66f4\u6539\uff0c\u8bf7\u6062\u590d\u539f\u6587\u4ef6\u540e\u91cd\u8bd5\u6b64\u6b21\u53d1\u9001\u3002"),
    "artifact_preparation_failed": (
        "An attachment could not be prepared for transfer.",
        "\u9644\u4ef6\u4f20\u8f93\u51c6\u5907\u5931\u8d25\u3002"),
    "artifact_blob_checkpoint_unavailable": (
        "The attachment transfer checkpoint could not be saved. Check local storage and retry.",
        "\u65e0\u6cd5\u4fdd\u5b58\u9644\u4ef6\u4f20\u8f93\u68c0\u67e5\u70b9\uff0c\u8bf7\u68c0\u67e5\u672c\u673a\u5b58\u50a8\u540e\u91cd\u8bd5\u3002"),
}


def _scope(payload):
    scope = {key: payload.get(key) for key in _FIELDS}
    generation = payload.get("execution_generation", 1)
    if (any(not isinstance(value, str) or not 1 <= len(value) <= 200 for value in scope.values())
            or type(generation) is not int or not 1 <= generation <= 2**53 - 1):
        raise BlobError("invalid_artifact_blob_replay_scope", 409)
    return {**scope, "execution_generation": generation}


def _path(bridge, scope):
    return Path(bridge.DATA_DIR) / "blob-output" / "deferred" / (sha256(canonical(scope)) + ".secure.json")


def _intent_lock(path):
    with _LOCK:
        key = str(path.resolve())
        lock = _INTENT_LOCKS.get(key)
        if lock is None:
            lock = threading.RLock()
            _INTENT_LOCKS[key] = lock
        return lock


def _peer(bridge, scope, binding=None):
    peer = bridge.get_client(scope["client_route_id"])
    if (not peer or peer.get("revoked") or peer.get("revoked_at")
            or bridge.desktop_id() != scope["desktop_id"]
            or peer.get("client_route_id") != scope["client_route_id"]
            or any(not peer.get(key) for key in _BINDING)
            or (binding is not None and any(peer.get(key) != binding.get(key) for key in _BINDING))):
        raise BlobError("artifact_blob_identity_changed", 409)
    return peer


def _source_hashes(task_id, output_files):
    from artifact_delivery import _file_sha256, INTERNAL_SUFFIXES
    from task_workspace import task_artifact_path
    hashes = {}
    for item in output_files:
        if not isinstance(item, dict):
            continue
        relative = str(item.get("relative_path") or "").replace("\\", "/").strip("/")
        source = task_artifact_path(task_id, relative)
        try:
            if source is not None and (source.name.startswith(".") or source.name.lower().endswith(INTERNAL_SUFFIXES)):
                continue
            if source is not None and 0 < source.stat().st_size <= MAX_FILE_BYTES:
                hashes[relative] = _file_sha256(source)
        except OSError:
            # An unreadable source has no accepted content identity yet.
            continue
    return hashes


def defer(bridge, payload, output_files, error, *, language="en-US", retain_on_desktop=False):
    """Checkpoint first; publish only a scoped failure notice, never a final archive."""
    scope = _scope(payload)
    code = error.code if isinstance(error, BlobError) else "artifact_blob_checkpoint_unavailable"
    path = _path(bridge, scope)
    peer = dict(_peer(bridge, scope))
    binding = {key: peer[key] for key in _BINDING}
    with _intent_lock(path):
        if path.exists():
            record = read_secure_json(path, purpose=_PURPOSE).value
            if record.get("scope") != scope:
                raise BlobError("artifact_blob_deferred_invalid", 409)
            _peer(bridge, scope, record.get("binding", {}))
        else:
            record = {"version": 1, "scope": scope, "payload": payload, "output_files": output_files,
                      "binding": binding, "source_hashes": _source_hashes(scope["task_id"], output_files),
                      "retain_on_desktop": bool(retain_on_desktop)}
        record["error_code"] = code
        write_secure_json(path, record, purpose=_PURPOSE)
        reason = _REASONS.get(code)
        chinese = language.lower().startswith("zh")
        detail = reason[1 if chinese else 0] if reason else (
            "\u9644\u4ef6\u4f20\u8f93\u5931\u8d25" if chinese else "Attachment transfer failed") + f" ({code})."
        content = ("\u9644\u4ef6\u672a\u53d1\u9001\uff1a" if chinese else "Attachment not sent: ") + detail
        notice = {**scope, "type": "text", "task_status": payload.get("task_status", "completed"),
                  "sender": "other", "agent_id": payload.get("agent_id", ""), "content": content,
                  "artifact_delivery": {"status": "failed", "error_code": code, "retryable": True},
                  "message_id": str(uuid.uuid5(uuid.NAMESPACE_URL, "galaxyssi:deferred-artifact:" + path.stem + code))}
        # Durable Signal outbox, but not the immutable final-result archive. A
        # successful explicit retry can still publish the original final card.
        with bridge.phone_publish_lock:
            peer = _peer(bridge, scope, record["binding"])
            bridge._publish_to_registered_client(bridge.client, peer, notice, "control", durable=True)
    return notice


def resume(bridge, task, route):
    if not (Path(bridge.DATA_DIR) / "blob-output" / "deferred").is_dir():
        return None
    try:
        scope = _scope({**task, "client_route_id": route, "desktop_id": bridge.desktop_id(),
                        "conversation_id": task.get("client_conversation_id") or task.get("conversation_id", ""),
                        "turn_id": bridge._client_task_turn_id(task)})
        path = _path(bridge, scope)
        if not path.exists():
            return None
        if task.get("client_route_id") != route:
            raise BlobError("invalid_artifact_blob_replay_scope", 409)
        with _intent_lock(path):
            record = read_secure_json(path, purpose=_PURPOSE).value
            if (record.get("version") != 1 or record.get("scope") != scope
                    or _scope(record.get("payload", {})) != scope
                    or not isinstance(record.get("output_files"), list)
                    or type(record.get("retain_on_desktop")) is not bool):
                raise BlobError("artifact_blob_deferred_invalid", 409)
            _peer(bridge, scope, record.get("binding", {}))
            from blob_artifact_publication import prepare_for_route, publish_result
            artifacts = prepare_for_route(bridge, route, scope["task_id"], record["output_files"])
            if not artifacts:
                raise BlobError("artifact_source_unavailable", 409)
            original = record.get("source_hashes")
            if not isinstance(original, dict):
                raise BlobError("artifact_blob_deferred_invalid", 409)
            actual = {item.relative_path: item.original_sha256 for item in artifacts}
            if any(actual.get(relative) != digest for relative, digest in original.items()):
                raise BlobError("artifact_source_changed", 409)
            # Large source hashing/copying must not hold the global phone send
            # lock. Enqueue captures and revalidates the original identity.
            _peer(bridge, scope, record["binding"])
            publish_result(bridge, bridge.client, {"scheme": "signal", "_client_route_id": route},
                           artifacts, record["payload"], retain_on_desktop=record["retain_on_desktop"],
                           expected_binding=record["binding"])
            path.unlink()
        return bridge.api_ok("agent_task_result_queued", task_id=scope["task_id"], queued=True)
    except BlobError as error:
        return bridge.api_error(error.code, task_id=task.get("task_id", ""))
    except (SecureStateError, TypeError, ValueError, KeyError, AttributeError):
        return bridge.api_error("artifact_blob_deferred_invalid", task_id=scope["task_id"])
    except (OSError, sqlite3.Error):
        return bridge.api_error("artifact_blob_checkpoint_unavailable", task_id=scope["task_id"])


def publish_or_defer(bridge, mqttc, wire, artifacts, payload, output_files, *,
                     preparation_error=None, language="en-US", retain_on_desktop=False):
    from blob_artifact_publication import publish_result
    if wire.get("_client_route_id") != payload.get("client_route_id"):
        raise BlobError("artifact_blob_publication_scope_mismatch", 409)
    try:
        if preparation_error is not None:
            raise preparation_error
        publish_result(bridge, mqttc, wire, artifacts, payload, retain_on_desktop=retain_on_desktop)
        return True
    except (BlobError, OSError, sqlite3.Error, SecureStateError) as error:
        if isinstance(error, BlobError) and error.code in {
            "artifact_blob_identity_changed", "artifact_blob_publication_scope_mismatch",
            "invalid_artifact_blob_replay_scope", "invalid_artifact_blob_publication",
        }:
            raise
        defer(bridge, payload, output_files, error, language=language, retain_on_desktop=retain_on_desktop)
        return False
