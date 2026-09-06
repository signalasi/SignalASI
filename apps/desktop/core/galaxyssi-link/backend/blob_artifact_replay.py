"""Explicit result replay preserves canonical Blob links after workspace handoff."""
from __future__ import annotations

from pathlib import Path
import sqlite3

from blob_protocol import BlobError


def republish(bridge, task: dict, route: str) -> dict | None:
    path = Path(bridge.DATA_DIR) / "blob-output" / "artifact-jobs.sqlite3"
    if not path.is_file():
        return None
    from blob_artifact_journal import BlobArtifactJournal
    from blob_artifact_bridge import current_peer, start, wake
    scope = {"task_id": task["task_id"], "client_route_id": route,
             "conversation_id": task.get("client_conversation_id") or task.get("conversation_id", ""),
             "turn_id": bridge._client_task_turn_id(task), "execution_generation": task.get("execution_generation", 1),
             "desktop_id": bridge.desktop_id(), "contact_id": task.get("contact_id", ""),
             "source_message_id": task.get("source_message_id", "")}
    try:
        if task.get("client_route_id") != route:
            raise BlobError("invalid_artifact_blob_replay_scope", 409)
        batch = BlobArtifactJournal(path).batches.publication_for(scope)
        if batch is None:
            return None
        with bridge.phone_publish_lock:
            for body in batch["bodies"]:
                current_peer(bridge, body)
            if batch["state"] in {"pending", "running"}:
                start(bridge)
                wake(bridge)
                return bridge.api_ok("agent_task_result_queued", task_id=task["task_id"], queued=True)
            if batch["state"] != "done":
                return bridge.api_error("artifact_delivery_failed", task_id=task["task_id"],
                                        error_code=batch["error"] or "artifact_blob_batch_invalid")
            payload = {**batch["publication"], "recovery_replay": True}
            wire = {"scheme": "signal", "_client_route_id": route}
            sent = bridge._publish_or_queue_task_result(bridge.client, wire, payload, replay=True)
        wake(bridge)
        code = "agent_task_result_republished" if sent else "agent_task_result_queued"
        return bridge.api_ok(code, task_id=task["task_id"], **({} if sent else {"queued": True}))
    except BlobError as error:
        return bridge.api_error(error.code, task_id=task["task_id"])
    except (OSError, sqlite3.Error):
        return bridge.api_error("artifact_blob_checkpoint_unavailable", task_id=task["task_id"])
