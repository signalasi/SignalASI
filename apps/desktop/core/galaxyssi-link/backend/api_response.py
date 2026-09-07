"""Stable API response helpers for localizable Desktop/mobile clients."""
from __future__ import annotations

from typing import Any


DEFAULT_MESSAGES = {
    "ok": "OK",
    "phone_not_paired": "Phone is not paired.",
    "mqtt_not_initialized": "MQTT client is not initialized.",
    "mqtt_not_connected": "MQTT client is not connected.",
    "contact_id_required": "contact_id is required.",
    "content_required": "content is required.",
    "agent_push_token_invalid": "Invalid GalaxySSI Agent push token.",
    "publish_failed": "Publish failed.",
    "mobile_status_publish_failed": "Mobile status publish failed.",
    "artifact_blob_transport_required": "Large-file transport is unavailable. Restore the connection settings and retry.",
    "artifact_blob_size_exceeded": "The attachment exceeds the 1 GiB transport limit.",
    "artifact_source_unavailable": "The attachment source cannot be read. Restore the file and retry.",
    "artifact_source_empty": "The attachment is empty and cannot be sent.",
    "artifact_source_changed": "The original attachment has changed. Restore the original file before retrying this delivery.",
    "artifact_preparation_failed": "The attachment could not be prepared for transfer.",
    "artifact_blob_checkpoint_unavailable": "The transfer checkpoint could not be saved. Check local storage and retry.",
}


def api_ok(code: str = "ok", params: dict[str, Any] | None = None, **extra: Any) -> dict[str, Any]:
    result = {
        "ok": True,
        "code": code,
        "params": params or {},
    }
    result.update(extra)
    return result


api_success = api_ok


def api_error(
    code: str,
    message: str | None = None,
    params: dict[str, Any] | None = None,
    **extra: Any,
) -> dict[str, Any]:
    stable_message = message or DEFAULT_MESSAGES.get(code, code)
    result = {
        "ok": False,
        "code": code,
        "params": params or {},
        "message": stable_message,
        "error": stable_message,
    }
    result.update(extra)
    return result
