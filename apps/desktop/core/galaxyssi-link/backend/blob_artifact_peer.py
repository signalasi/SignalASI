"""Recoverable contact-message projection before Blob offers, without Agent routing."""
from __future__ import annotations

from pathlib import Path
import sqlite3

from blob_protocol import BlobError


def project(payload: dict) -> dict:
    from peer_chat_store import peer_chat_store
    store = peer_chat_store()
    if store.message_was_deleted(payload["message_id"]):
        raise BlobError("artifact_blob_peer_message_deleted", 409)
    local = payload["_local_message"]
    parent = (store._route_directory(payload["client_route_id"]) / store._safe_component(payload["message_id"])).resolve()
    for item in local["attachments"]:
        path = Path(item["local_path"])
        if path.is_symlink() or path.resolve().parent != parent:
            raise BlobError("artifact_blob_peer_source_invalid", 409)
        if not path.is_file() or path.stat().st_size != item["size_bytes"]:
            raise BlobError("artifact_blob_peer_source_missing", 409)
    try:
        return store.append(client_route_id=payload["client_route_id"], direction="outbound",
            content=payload["content"], sender_name=local["sender_name"], attachments=local["attachments"],
            message_id=payload["message_id"], created_at_ms=local["created_at_ms"], delivery_status="queued", idempotent=True)
    except ValueError as error:
        code = "artifact_blob_peer_message_deleted" if str(error) == "peer_message_deleted" else "artifact_blob_peer_message_conflict"
        raise BlobError(code, 409) from None


def publish_batch(runtime, batch: dict) -> bool:
    from blob_artifact_publication import validate_publication
    from peer_chat_store import peer_chat_store
    payload = validate_publication(batch["publication"], batch["bodies"])
    with runtime.bridge.phone_publish_lock:
        runtime.peer(batch["bodies"][0])
        stored = project(payload)
        if stored["delivery_status"] in {"delivered", "read"}:
            return True
        wire = {key: value for key, value in payload.items() if key != "_local_message"}
        runtime._publish(batch["bodies"][0], wire, "", message_id=payload["message_id"])
        peer_chat_store().update_delivery_status(payload["message_id"], "queued", only_if=("sending", "queued"))
    return True


def observe_failure(runtime, body: dict) -> bool:
    """Update only the matching local card; report remotely only after publication."""
    from peer_chat_store import peer_chat_store
    manifest = body["manifest"]
    batch = runtime.sender.journal.batches.publication_for(manifest)
    if batch is None or body not in batch["bodies"]:
        raise BlobError("artifact_blob_peer_message_conflict", 409)
    store = peer_chat_store()
    stored = store.get_message(manifest["source_message_id"])
    if stored is not None:
        if stored["client_route_id"] != manifest["client_route_id"] or stored["direction"] != "outbound":
            raise BlobError("artifact_blob_peer_message_conflict", 409)
        store.update_delivery_status(manifest["source_message_id"], "failed", only_if=("sending", "queued"))
    return batch["state"] == "done" and not store.message_was_deleted(manifest["source_message_id"])


def observe_stored(runtime, body: dict) -> bool:
    from peer_chat_store import peer_chat_store
    publication = runtime.sender.journal.batches.receipted_publication(body)
    if publication is None:
        return True
    store = peer_chat_store()
    message_id = publication["message_id"]
    if store.message_was_deleted(message_id):
        return True
    stored = store.get_message(message_id)
    if stored is None:
        raise BlobError("artifact_blob_peer_message_missing", 409)
    if stored["client_route_id"] != publication["client_route_id"] or stored["direction"] != "outbound":
        raise BlobError("artifact_blob_peer_message_conflict", 409)
    store.update_delivery_status(message_id, "delivered", only_if=("sending", "queued", "sent", "failed"))
    return True


def try_publish(bridge, artifacts: list, payload: dict, stored_attachments: list[dict]) -> dict | None:
    if not artifacts:
        return None
    from blob_pair_configuration import can_receive_artifacts, private_settings
    from blob_artifact_publication import prepare_result
    from blob_artifact_bridge import _get, start
    route = payload["client_route_id"]
    peer = bridge.get_client(route)
    if peer is None or not can_receive_artifacts(bridge, route):
        return None
    settings = private_settings(bridge, route)
    if not settings["enabled"]:
        return None
    if len(payload["attachments"]) != len(stored_attachments) or len(artifacts) != len(stored_attachments):
        raise BlobError("invalid_artifact_blob_peer_publication", 409)
    if any(transport["sha256"] != stored["sha256"] or transport["size_bytes"] != stored["size_bytes"]
           for transport, stored in zip(payload["attachments"], stored_attachments)):
        raise BlobError("source_changed", 409)
    local = [{**stored, **transport} for transport, stored in zip(payload["attachments"], stored_attachments)]
    publication = {**payload, "peer_chat": True, "task_id": payload["message_id"], "execution_generation": 1,
                   "_local_message": {"sender_name": payload["desktop_name"], "created_at_ms": int(payload["time"] * 1000),
                                      "attachments": local}}
    bodies, publication = prepare_result(bridge, peer, artifacts, publication, settings["origin"], peer_chat=True)
    runtime = _get(bridge)
    for body in bodies:
        runtime.sender._settings(body)
    # Local projection is recoverable from this encrypted commit. It must not be
    # the only record of an intended send if the process exits before enqueue.
    runtime.sender.enqueue_batch(bodies, publication=publication, retain_on_desktop=False)
    try:
        stored = project(publication)
    except (OSError, sqlite3.Error):
        stored = {"message_id": payload["message_id"], "client_route_id": route, "direction": "outbound",
                  "sender_name": payload["desktop_name"], "content": payload["content"],
                  "attachments": publication["attachments"], "delivery_status": "queued",
                  "created_at_ms": publication["_local_message"]["created_at_ms"]}
    except BlobError as error:
        return bridge.api_error(error.code, message_id=payload["message_id"])
    finally:
        if not runtime.sender.start():
            start(bridge)
    return bridge.api_ok("peer_message_queued", message=stored, message_id=payload["message_id"], queued=True)
