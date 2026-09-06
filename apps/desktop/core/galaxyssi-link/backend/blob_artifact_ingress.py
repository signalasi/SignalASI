"""Durable output receipts at the authenticated MQTT boundary, never chat input."""
from __future__ import annotations

from blob_artifact_contract import RECEIPT_TYPE


def persist_receipt_before_ack(bridge, envelope: dict, route: str) -> bool | None:
    payload = envelope.get("payload")
    if not isinstance(payload, dict) or payload.get("type") != RECEIPT_TYPE:
        return None
    peer = bridge.get_client(route)
    if not peer or envelope.get("source_id") != peer.get("signal_name"):
        return False
    path = bridge.DATA_DIR / "blob-output" / "artifact-jobs.sqlite3"
    if not path.is_file():
        # An old/unknown receipt cannot create a job or initialize bulk workers.
        return False
    from blob_artifact_journal import BlobArtifactJournal
    accepted = BlobArtifactJournal(path).accept_receipt(
        payload, route=route, source=envelope["source_id"],
        peer_fingerprint=peer["identity_fingerprint"],
        local_fingerprint=peer["local_identity_fingerprint"],
        conversation_id=str(envelope.get("conversation_id") or ""),
    )
    if accepted:
        from blob_artifact_bridge import wake
        wake(bridge)
    return accepted
