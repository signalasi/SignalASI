"""Lazy bridge adapter. No relay, network worker or private store is created on import."""
from __future__ import annotations

import os
import threading

from blob_input_contract import OFFER_TYPE
from blob_protocol import BlobError

_lock = threading.Lock()
_receiver = None


def _get_receiver(bridge):
    global _receiver
    with _lock:
        if _receiver is None:
            from blob_input_receiver import BlobInputReceiver
            from blob_pair_configuration import origin_for_peer

            def is_current(route, source):
                peer = bridge.get_client(route)
                return peer.get("identity_fingerprint") if peer and peer.get("signal_name") == source else None

            def publish(route, source, fingerprint, payload):
                peer = bridge.get_client(route)
                if not peer or peer.get("signal_name") != source or peer.get("identity_fingerprint") != fingerprint:
                    return False
                # This call returns only after queue_outbound persisted the Signal envelope.
                bridge._publish_to_registered_client(bridge.client, peer, payload, "control", durable=True)
                from dataclasses import fields
                from input_attachment_transfer import AttachmentTransferReceipt
                receipt = AttachmentTransferReceipt(**{
                    field.name: payload[field.name] for field in fields(AttachmentTransferReceipt)
                    if field.name in payload
                })
                bridge.attachment_request_broker.accept_receipt(receipt)
                return True

            _receiver = BlobInputReceiver(bridge.DATA_DIR / "blob-input",
                                         configured_origin=lambda: os.environ.get("GALAXYSSI_BLOB_RELAY_URL", ""),
                                         peer_origin=lambda route, source: origin_for_peer(bridge, route, source),
                                         peer_identity=is_current, publish_receipt=publish)
        return _receiver


def persist_before_ack(bridge, envelope: dict, route: str) -> bool:
    payload = envelope.get("payload")
    if not isinstance(payload, dict) or payload.get("type") != OFFER_TYPE:
        return False
    if envelope.get("conversation_id") != payload.get("conversation_id"):
        raise BlobError("input_blob_conversation_mismatch", 409)
    receiver = _get_receiver(bridge)
    receiver.enqueue(payload, route, envelope["source_id"])
    receiver.start()
    return True


def start(bridge):
    if os.environ.get("GALAXYSSI_BLOB_RELAY_URL") or (bridge.DATA_DIR / "blob-input" / "input-jobs.sqlite3").exists():
        _get_receiver(bridge).start()


def stop():
    with _lock:
        if _receiver is not None:
            _receiver.stop()
