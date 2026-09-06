"""Small, authenticated control offers for phone-to-Desktop input attachments."""
from __future__ import annotations

from blob_client import relay_origin
from blob_crypto import binding_hash, validate_private_descriptor
from blob_protocol import BlobError, VERSION, canonical, checked_hex
from input_attachment_transfer import validate_input_manifest

OFFER_TYPE = "input_attachment_blob_offer"
MAX_OFFER_BYTES = 32 * 1024
BINDING_FIELDS = ("client_route_id", "conversation_id", "task_id", "turn_id",
                  "attachment_id", "transfer_id", "contact_id")


def input_binding(manifest: dict) -> dict:
    return {key: manifest[key] for key in BINDING_FIELDS}


def validate_input_offer(payload: dict, route: str, source: str, configured_origin: str,
                         *, allow_loopback_http: bool = False) -> dict:
    if not configured_origin:
        raise BlobError("blob_relay_not_configured", 503)
    if not isinstance(payload, dict) or payload.get("type") != OFFER_TYPE:
        raise BlobError("invalid_input_blob_offer")
    if len(canonical(payload)) > MAX_OFFER_BYTES:
        raise BlobError("input_blob_offer_too_large", 413)
    if not isinstance(source, str) or not 1 <= len(source) <= 256:
        raise BlobError("invalid_input_blob_source")
    if any(type(payload.get(key)) is not int for key in (
            "size_bytes", "chunk_size_bytes", "chunk_count", "attachment_ordinal")):
        raise BlobError("invalid_input_blob_manifest")
    try:
        manifest = validate_input_manifest(payload, client_route_id=route)
    except (ValueError, TypeError, OverflowError):
        raise BlobError("invalid_input_blob_manifest") from None
    offer = payload.get("blob_offer")
    if (not isinstance(offer, dict) or set(offer) != {"version", "relay", "private", "read_token"}
            or type(offer["version"]) is not int or offer["version"] != VERSION):
        raise BlobError("invalid_blob_offer")
    allowed = relay_origin(configured_origin, allow_loopback_http=allow_loopback_http)
    if offer.get("relay") != allowed:
        raise BlobError("input_blob_relay_mismatch", 409)
    private = validate_private_descriptor(offer["private"])
    checked_hex(offer["read_token"])
    binding = input_binding(manifest)
    if (private["binding_sha256"] != binding_hash(binding)
            or private["size"] != manifest["size_bytes"] or private["sha256"] != manifest["sha256"]):
        raise BlobError("input_blob_binding_mismatch", 409)
    # Retain only the validated attachment contract, never arbitrary message/model fields.
    manifest.pop("created_at")
    manifest.pop("complete")
    manifest["client_message_id"] = manifest.pop("source_message_id")
    return {"route": route, "source": source, "manifest": manifest,
            "binding": binding, "offer": {**offer, "private": private}}
