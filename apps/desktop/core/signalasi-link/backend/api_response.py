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
    "agent_push_token_invalid": "Invalid SignalASI Agent push token.",
    "publish_failed": "Publish failed.",
    "mobile_status_publish_failed": "Mobile status publish failed.",
}


def api_ok(code: str = "ok", params: dict[str, Any] | None = None, **extra: Any) -> dict[str, Any]:
    result = {
        "ok": True,
        "code": code,
        "params": params or {},
    }
    result.update(extra)
    return result


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
