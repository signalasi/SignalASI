"""Authenticated output-artifact metadata, independent of the bulk byte stream."""
from __future__ import annotations

from urllib.parse import urlsplit

from blob_client import relay_origin
from blob_crypto import binding_hash, validate_private_descriptor
from blob_protocol import BlobError, MAX_FILE_BYTES, canonical, checked_hex, sha256
from link_protocol import valid_route_id

OFFER_TYPE = "artifact_blob_offer"
RECEIPT_TYPE = "artifact_blob_receipt"
MAX_CONTROL_BYTES = 32 * 1024
SCOPE_FIELDS = ("client_route_id", "conversation_id", "task_id", "turn_id", "contact_id",
                "source_message_id", "desktop_id")
_TEXT_LIMITS = {**dict.fromkeys(SCOPE_FIELDS, 256), "artifact_id": 64, "artifact_uri": 2048,
                "name": 255, "relative_path": 2048, "mime_type": 255,
                "sha256": 64, "original_sha256": 64}
_NUMBER_LIMITS = {"size_bytes": MAX_FILE_BYTES, "original_size_bytes": 2**53 - 1,
                  "execution_generation": 2**53 - 1}
_FIELDS = set(_TEXT_LIMITS) | set(_NUMBER_LIMITS) | {"peer_chat"}


def _metadata(value: dict) -> dict:
    if not isinstance(value, dict) or set(value) != _FIELDS:
        raise BlobError("invalid_artifact_blob_manifest")
    for key, maximum in _TEXT_LIMITS.items():
        text = value[key]
        if (not isinstance(text, str) or not 1 <= len(text) <= maximum
                or any(ord(char) < 32 for char in text)):
            raise BlobError("invalid_artifact_blob_manifest")
    for key, maximum in _NUMBER_LIMITS.items():
        if type(value[key]) is not int or not 1 <= value[key] <= maximum:
            raise BlobError("invalid_artifact_blob_manifest")
    if type(value["peer_chat"]) is not bool or not valid_route_id(value["client_route_id"]):
        raise BlobError("invalid_artifact_blob_manifest")
    for key in ("artifact_id", "sha256", "original_sha256"):
        checked_hex(value[key])
    try:
        uri = urlsplit(value["artifact_uri"])
        if uri.scheme != "galaxyssi-artifact" or not uri.netloc or uri.query or uri.fragment:
            raise ValueError()
    except ValueError:
        raise BlobError("invalid_artifact_blob_uri") from None
    if value["original_size_bytes"] < value["size_bytes"]:
        raise BlobError("invalid_artifact_blob_manifest")
    if len(canonical(value)) > MAX_CONTROL_BYTES // 2:
        raise BlobError("artifact_blob_manifest_too_large", 413)
    return dict(value)


def make_manifest(metadata: dict) -> dict:
    value = _metadata(metadata)
    return {**value, "transfer_id": sha256(canonical(value))}


def make_scoped_manifest(metadata: dict) -> dict:
    """Build from the original prepared artifact, before publishing rich reply links.

    The same task path in another turn, device, or execution generation must not
    replace a previously displayed phone artifact. Retries of unchanged metadata
    deliberately keep the same URI and transfer identity.
    """
    value = _metadata(metadata)
    value["artifact_uri"] = "galaxyssi-artifact://blob/" + sha256(canonical(value))
    return make_manifest(value)


def validate_manifest(value: dict) -> dict:
    if not isinstance(value, dict) or set(value) != _FIELDS | {"transfer_id"}:
        raise BlobError("invalid_artifact_blob_manifest")
    expected = make_manifest({key: value[key] for key in _FIELDS})
    if checked_hex(value["transfer_id"]) != expected["transfer_id"]:
        raise BlobError("artifact_blob_manifest_mismatch", 409)
    return expected


def artifact_binding(manifest: dict) -> dict:
    value = validate_manifest(manifest)
    binding = {key: value[key] for key in SCOPE_FIELDS}
    binding.update(artifact_id=value["artifact_id"], transfer_id=value["transfer_id"],
                   execution_generation=str(value["execution_generation"]))
    binding_hash(binding)
    return binding


def validate_offer(payload: dict, route: str, desktop: str, origin: str) -> dict:
    if (not isinstance(payload, dict) or payload.get("type") != OFFER_TYPE
            or type(payload.get("version")) is not int or payload["version"] != 1):
        raise BlobError("invalid_artifact_blob_offer")
    if len(canonical(payload)) > MAX_CONTROL_BYTES:
        raise BlobError("artifact_blob_offer_too_large", 413)
    revision = payload.get("transport_revision")
    if type(revision) is not int or not 1 <= revision <= 2**53 - 1:
        raise BlobError("invalid_artifact_blob_transport_revision")
    value = validate_manifest(payload.get("manifest"))
    if value["client_route_id"] != route or value["desktop_id"] != desktop:
        raise BlobError("artifact_blob_route_mismatch", 409)
    offer = payload.get("blob_offer")
    if (not isinstance(offer, dict) or set(offer) != {"version", "relay", "private", "read_token"}
            or type(offer["version"]) is not int or offer["version"] != 1):
        raise BlobError("invalid_blob_offer")
    if offer["relay"] != relay_origin(origin):
        raise BlobError("artifact_blob_relay_mismatch", 409)
    checked_hex(offer["read_token"])
    private = validate_private_descriptor(offer["private"])
    if (private["binding_sha256"] != binding_hash(artifact_binding(value))
            or private["size"] != value["size_bytes"] or private["sha256"] != value["sha256"]):
        raise BlobError("artifact_blob_binding_mismatch", 409)
    return {"type": OFFER_TYPE, "version": 1, "manifest": value, "transport_revision": revision,
            "blob_offer": {**offer, "private": private}}


def stored_receipt(manifest: dict) -> dict:
    value = validate_manifest(manifest)
    return {"type": RECEIPT_TYPE, "version": 1, "status": "stored",
            **{key: value[key] for key in ("transfer_id", "client_route_id", "artifact_id", "sha256", "size_bytes")}}


def receipt_matches(manifest: dict, receipt: dict) -> bool:
    if not isinstance(receipt, dict):
        return False
    expected = stored_receipt(manifest)
    return (type(receipt.get("version")) is int and type(receipt.get("size_bytes")) is int
            and all(receipt.get(key) == value for key, value in expected.items()))
