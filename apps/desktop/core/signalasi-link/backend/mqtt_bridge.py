"""SignalASI Link MQTT bridge - connects the public broker and mobile app."""
import asyncio
import base64
import hashlib
import json
import os
import re
import secrets
import socket
import threading
import time
import logging
import uuid
from dataclasses import dataclass
from pathlib import Path
from typing import Any, Callable, Mapping

import paho.mqtt.client as mqtt

from api_response import api_error, api_ok
from agent_gateway import ask_agent_sync, connector_diagnostics
from agent_task_manager import agent_task_manager
from codex_app_server import CodexAppServer
import phone_tool_broker as phone_tool
from link_delivery import (
    acknowledge_outbound,
    claim_message,
    complete_message,
    mark_outbound_published,
    pending_outbound,
    previous_acknowledgement,
    queue_outbound,
)
from link_protocol import LinkTopics, PROTOCOL_NAME, PROTOCOL_VERSION, decrypt_pairing_claim, make_envelope, parse_topic, validate_envelope, valid_route_id
from pairing_state import (
    get_client,
    is_paired,
    list_clients,
    pairing_status,
    pairing_secret,
    record_pairing_success,
    revoke_client,
    server_route_id,
    touch_client,
    validate_pairing_token,
)
from signalasi_client import (
    decrypt_signal_envelope,
    desktop_id,
    desktop_name,
    encrypt_signal_payload,
    get_signal_bundle,
    replace_peer_signal_bundle,
    remove_peer_signal_session,
)
from stt_bridge import transcribe_audio

log = logging.getLogger("signalasi.mqtt")

BROKER = os.environ.get("SIGNALASI_MQTT_HOST", "broker.emqx.io")
PORT = int(os.environ.get("SIGNALASI_MQTT_PORT", "8883"))
MQTT_TLS = os.environ.get("SIGNALASI_MQTT_TLS", "1") != "0"
FILES_DIR = Path.home() / "signalasi_files"
MQTT_QOS = 1
MOBILE_HIDDEN_AGENT_IDS = {"cloud-model"}

client = None
running = False
codex_app_server: CodexAppServer | None = None
codex_task_callbacks: dict[str, Callable[[str, dict], None]] = {}
codex_task_callbacks_lock = threading.Lock()
pending_delivery_acks: dict[int, dict] = {}
pending_delivery_acks_lock = threading.Lock()
pending_outbound_acks: dict[int, tuple[str, str]] = {}
pending_outbound_acks_lock = threading.Lock()

TOOL_SESSION_START_TYPE = "tool_session_start"
TOOL_CALL_REQUEST_TYPE = "tool_call_request"
TOOL_CALL_RESULT_TYPE = "tool_call_result"
TOOL_CALL_CANCEL_TYPE = "tool_call_cancel"


class PhoneToolSessionRoutingError(RuntimeError):
    """Raised when a phone tool message is not bound to its paired session."""


@dataclass
class _PhoneToolSession:
    session_id: str
    task_id: str
    turn_id: str
    manifest_hash: str
    conversation_id: str
    client_route_id: str
    signal_name: str
    mqttc: Any
    broker: phone_tool.PhoneToolBroker


phone_tool_sessions: dict[str, _PhoneToolSession] = {}
phone_tool_sessions_lock = threading.RLock()


def _client_topics(client_route_id: str) -> LinkTopics:
    return LinkTopics(server_route_id(), client_route_id)


def _wire_client(wire_payload: dict) -> dict | None:
    route_id = str(wire_payload.get("_client_route_id") or "")
    return get_client(route_id) if route_id else None


def _wire_down_topic(wire_payload: dict) -> str:
    client = _wire_client(wire_payload)
    return str((client or {}).get("topics", {}).get("down") or "")


def _wire_control_topic(wire_payload: dict) -> str:
    client = _wire_client(wire_payload)
    return str((client or {}).get("topics", {}).get("control") or "")


def _wire_remote_name(wire_payload: dict) -> str:
    client = _wire_client(wire_payload)
    return str((client or {}).get("signal_name") or "")


def _phone_tool_identifier(name: str, value: object) -> str:
    text = str(value or "")
    if not text or len(text) > phone_tool.MAX_ID_CHARS or any(ord(char) < 0x20 for char in text):
        raise PhoneToolSessionRoutingError(f"invalid {name}")
    return text


def _phone_tool_manifest_hash(value: object) -> str:
    text = str(value or "")
    normalized = text.removeprefix("sha256:").lower()
    if not re.fullmatch(r"[0-9a-f]{64}", normalized):
        raise PhoneToolSessionRoutingError("invalid manifest_hash")
    return normalized


def _normalize_tool_session_start(payload: dict, application_envelope: dict) -> dict:
    candidate = dict(payload)
    candidate.setdefault("protocol", phone_tool.PROTOCOL_NAME)
    candidate.setdefault("version", phone_tool.PROTOCOL_VERSION)
    candidate.setdefault("message_id", application_envelope.get("message_id"))
    candidate.setdefault("sent_at", application_envelope.get("sent_at"))
    candidate.setdefault("expires_at", application_envelope.get("expires_at"))
    if candidate.get("protocol") != phone_tool.PROTOCOL_NAME or candidate.get("version") != phone_tool.PROTOCOL_VERSION:
        raise PhoneToolSessionRoutingError("unsupported phone tool session protocol")
    try:
        uuid.UUID(str(candidate.get("message_id") or ""))
    except (TypeError, ValueError, AttributeError) as exc:
        raise PhoneToolSessionRoutingError("invalid tool session message_id") from exc

    now_ms = int(time.time() * 1000)
    sent_at = candidate.get("sent_at")
    expires_at = candidate.get("expires_at")
    if (
        isinstance(sent_at, bool)
        or not isinstance(sent_at, int)
        or isinstance(expires_at, bool)
        or not isinstance(expires_at, int)
        or sent_at <= 0
        or sent_at - now_ms > phone_tool.MAX_CLOCK_SKEW_MS
        or expires_at <= sent_at
        or now_ms >= expires_at
    ):
        raise PhoneToolSessionRoutingError("invalid or expired tool session timestamps")
    sequence = candidate.get("sequence")
    if isinstance(sequence, bool) or not isinstance(sequence, int) or sequence <= 0:
        raise PhoneToolSessionRoutingError("invalid tool session sequence")

    start_payload = candidate.get("payload") if isinstance(candidate.get("payload"), dict) else {}
    candidate["session_id"] = _phone_tool_identifier("session_id", candidate.get("session_id"))
    candidate["task_id"] = _phone_tool_identifier("task_id", candidate.get("task_id"))
    candidate["turn_id"] = _phone_tool_identifier("turn_id", candidate.get("turn_id"))
    candidate["manifest_hash"] = _phone_tool_manifest_hash(
        candidate.get("manifest_hash") or start_payload.get("manifest_hash")
    )
    conversation_id = str(candidate.get("conversation_id") or application_envelope.get("conversation_id") or "")
    link_conversation_id = str(application_envelope.get("conversation_id") or "")
    if link_conversation_id and conversation_id != link_conversation_id:
        raise PhoneToolSessionRoutingError("tool session conversation does not match Link envelope")
    candidate["conversation_id"] = conversation_id
    return candidate


def _tool_broker_envelope(payload: dict, internal_type: str) -> dict:
    nested = payload.get("envelope")
    candidate = dict(nested) if isinstance(nested, dict) else dict(payload)
    if isinstance(nested, dict):
        for field_name in (
            "session_id",
            "task_id",
            "turn_id",
            "tool_call_id",
            "manifest_hash",
        ):
            if field_name in payload and str(payload[field_name]) != str(candidate.get(field_name, "")):
                raise PhoneToolSessionRoutingError(f"outer {field_name} does not match tool envelope")
    candidate.pop("envelope", None)
    candidate["type"] = internal_type
    return candidate


def _session_for_authenticated_route(
    session_id: str,
    client_route_id: str,
    signal_name: str,
) -> _PhoneToolSession:
    with phone_tool_sessions_lock:
        session = phone_tool_sessions.get(session_id)
    if session is None:
        raise PhoneToolSessionRoutingError(f"unknown phone tool session {session_id!r}")
    paired_client = get_client(client_route_id)
    if (
        session.client_route_id != client_route_id
        or session.signal_name != signal_name
        or paired_client is None
        or str(paired_client.get("signal_name") or "") != signal_name
    ):
        raise PhoneToolSessionRoutingError("phone tool session does not belong to authenticated client")
    return session


def _publish_phone_tool_envelope(session_id: str, envelope: dict) -> None:
    with phone_tool_sessions_lock:
        session = phone_tool_sessions.get(session_id)
    if session is None:
        raise PhoneToolSessionRoutingError("phone tool session is no longer active")
    paired_client = get_client(session.client_route_id)
    if paired_client is None or str(paired_client.get("signal_name") or "") != session.signal_name:
        raise PhoneToolSessionRoutingError("phone tool session pairing is no longer active")
    mqttc = session.mqttc
    if mqttc is None or (hasattr(mqttc, "is_connected") and not mqttc.is_connected()):
        raise PhoneToolSessionRoutingError("MQTT is not connected")

    transport_types = {
        phone_tool.REQUEST_TYPE: TOOL_CALL_REQUEST_TYPE,
        phone_tool.CANCEL_TYPE: TOOL_CALL_CANCEL_TYPE,
    }
    transport_type = transport_types.get(str(envelope.get("type") or ""))
    if not transport_type:
        raise PhoneToolSessionRoutingError("unsupported outbound phone tool envelope")
    transport_payload = {
        **envelope,
        "type": transport_type,
        "conversation_id": session.conversation_id,
    }
    with phone_publish_lock:
        info = _publish_to_registered_client(
            mqttc,
            paired_client,
            transport_payload,
            "control",
        )
    if info.rc != mqtt.MQTT_ERR_SUCCESS:
        raise PhoneToolSessionRoutingError(f"phone tool publish failed rc={info.rc}")


def _register_phone_tool_session(
    mqttc,
    paired_client: dict,
    application_envelope: dict,
    payload: dict,
) -> _PhoneToolSession:
    start = _normalize_tool_session_start(payload, application_envelope)
    session_id = start["session_id"]
    client_route_id = str(paired_client["client_route_id"])
    signal_name = str(paired_client["signal_name"])
    with phone_tool_sessions_lock:
        existing = phone_tool_sessions.get(session_id)
        if existing is not None:
            matches = (
                existing.client_route_id == client_route_id
                and existing.signal_name == signal_name
                and existing.task_id == start["task_id"]
                and existing.turn_id == start["turn_id"]
                and existing.manifest_hash == start["manifest_hash"]
                and existing.conversation_id == start["conversation_id"]
            )
            if not matches:
                raise PhoneToolSessionRoutingError("tool session identity or policy binding changed")
            existing.mqttc = mqttc
            return existing

        broker = phone_tool.PhoneToolBroker(
            lambda envelope: _publish_phone_tool_envelope(session_id, envelope)
        )
        session = _PhoneToolSession(
            session_id=session_id,
            task_id=start["task_id"],
            turn_id=start["turn_id"],
            manifest_hash=start["manifest_hash"],
            conversation_id=start["conversation_id"],
            client_route_id=client_route_id,
            signal_name=signal_name,
            mqttc=mqttc,
            broker=broker,
        )
        phone_tool_sessions[session_id] = session
    log.info("Phone tool session registered session=%s client=%s", session_id, client_route_id)
    return session


def _receive_phone_tool_result(
    mqttc,
    paired_client: dict,
    application_envelope: dict,
    payload: dict,
) -> dict:
    envelope = _tool_broker_envelope(payload, phone_tool.RESPONSE_TYPE)
    session = _session_for_authenticated_route(
        str(envelope.get("session_id") or ""),
        str(paired_client["client_route_id"]),
        str(paired_client["signal_name"]),
    )
    if str(application_envelope.get("conversation_id") or "") != session.conversation_id:
        raise PhoneToolSessionRoutingError("tool result conversation does not match phone tool session")
    if envelope.get("conversation_id") and str(envelope["conversation_id"]) != session.conversation_id:
        raise PhoneToolSessionRoutingError("tool result envelope conversation does not match phone tool session")
    session.mqttc = mqttc
    return session.broker.receive_response(envelope)


def _receive_phone_tool_cancel(
    mqttc,
    paired_client: dict,
    application_envelope: dict,
    payload: dict,
) -> dict:
    cancel = _tool_broker_envelope(payload, phone_tool.CANCEL_TYPE)
    phone_tool.validate_phone_tool_envelope(cancel, expected_type=phone_tool.CANCEL_TYPE)
    session = _session_for_authenticated_route(
        str(cancel.get("session_id") or ""),
        str(paired_client["client_route_id"]),
        str(paired_client["signal_name"]),
    )
    if str(application_envelope.get("conversation_id") or "") != session.conversation_id:
        raise PhoneToolSessionRoutingError("tool cancellation conversation does not match phone tool session")
    if cancel.get("conversation_id") and str(cancel["conversation_id"]) != session.conversation_id:
        raise PhoneToolSessionRoutingError("tool cancellation envelope conversation does not match phone tool session")
    session.mqttc = mqttc
    response = {
        **cancel,
        "type": phone_tool.RESPONSE_TYPE,
        "payload": {
            "status": "cancelled",
            "result": None,
            "error": {
                "code": "phone_cancelled",
                "message": str(cancel.get("payload", {}).get("reason") or "Phone cancelled tool call"),
            },
        },
    }
    return session.broker.receive_response(response)


def _route_phone_tool_payload(
    mqttc,
    paired_client: dict,
    application_envelope: dict,
    payload: dict,
    channel: str,
) -> bool:
    message_type = str(payload.get("type") or "")
    if message_type not in {
        TOOL_SESSION_START_TYPE,
        TOOL_CALL_RESULT_TYPE,
        TOOL_CALL_CANCEL_TYPE,
    }:
        return False
    if application_envelope.get("target_id") != desktop_id():
        log.warning("Phone tool message rejected: application target does not match this Desktop")
        return True
    if channel not in {"up", "control"}:
        log.warning("Phone tool message rejected on invalid channel=%s", channel)
        return True
    try:
        if message_type == TOOL_SESSION_START_TYPE:
            _register_phone_tool_session(mqttc, paired_client, application_envelope, payload)
        elif message_type == TOOL_CALL_RESULT_TYPE:
            _receive_phone_tool_result(mqttc, paired_client, application_envelope, payload)
        else:
            _receive_phone_tool_cancel(mqttc, paired_client, application_envelope, payload)
    except phone_tool.PhoneToolBrokerError as exc:
        log.warning("Phone tool broker message rejected type=%s: %s", message_type, exc)
    except PhoneToolSessionRoutingError as exc:
        log.warning("Phone tool route rejected type=%s: %s", message_type, exc)
    return True


def request_phone_tool_call(
    session_id: str,
    *,
    call_id: str,
    sequence: int,
    tool_id: str,
    arguments: Mapping[str, Any],
    task_id: str = "",
    turn_id: str = "",
    manifest_hash: str = "",
    parent_call_id: str = "",
    approval_handle: str = "",
    timeout_ms: int | None = None,
    expires_at: int | None = None,
    message_id: str = "",
) -> dict:
    with phone_tool_sessions_lock:
        session = phone_tool_sessions.get(str(session_id or ""))
    if session is None:
        raise PhoneToolSessionRoutingError(f"unknown phone tool session {session_id!r}")
    if task_id and task_id != session.task_id:
        raise PhoneToolSessionRoutingError("task_id does not match phone tool session")
    if turn_id and turn_id != session.turn_id:
        raise PhoneToolSessionRoutingError("turn_id does not match phone tool session")
    if manifest_hash and _phone_tool_manifest_hash(manifest_hash) != session.manifest_hash:
        raise PhoneToolSessionRoutingError("manifest_hash does not match phone tool session")
    return session.broker.start_call(
        session_id=session.session_id,
        task_id=session.task_id,
        turn_id=session.turn_id,
        call_id=call_id,
        manifest_hash=session.manifest_hash,
        sequence=sequence,
        tool_id=tool_id,
        arguments=arguments,
        parent_call_id=parent_call_id,
        approval_handle=approval_handle,
        timeout_ms=timeout_ms,
        expires_at=expires_at,
        message_id=message_id,
    )


def wait_for_phone_tool_result(
    session_id: str,
    call_id: str,
    timeout_ms: int | None = None,
) -> dict:
    with phone_tool_sessions_lock:
        session = phone_tool_sessions.get(str(session_id or ""))
    if session is None:
        raise PhoneToolSessionRoutingError(f"unknown phone tool session {session_id!r}")
    return session.broker.wait_for_result(call_id, timeout_ms)


def cancel_phone_tool_call(
    session_id: str,
    call_id: str,
    reason: str = "cancelled by Desktop",
) -> dict | None:
    with phone_tool_sessions_lock:
        session = phone_tool_sessions.get(str(session_id or ""))
    if session is None:
        raise PhoneToolSessionRoutingError(f"unknown phone tool session {session_id!r}")
    return session.broker.cancel_call(call_id, reason)


def _close_phone_tool_sessions(client_route_id: str = "", reason: str = "session closed") -> list[str]:
    with phone_tool_sessions_lock:
        sessions = [
            session
            for session in phone_tool_sessions.values()
            if not client_route_id or session.client_route_id == client_route_id
        ]
    for session in sessions:
        session.broker.close(reason)
    with phone_tool_sessions_lock:
        for session in sessions:
            if phone_tool_sessions.get(session.session_id) is session:
                phone_tool_sessions.pop(session.session_id, None)
    return [session.session_id for session in sessions]


start_phone_tool_call = request_phone_tool_call


def _subscribe_client(mqttc, client: dict) -> None:
    topics = client.get("topics") or {}
    for key in ("up", "control"):
        topic = str(topics.get(key) or "")
        if topic:
            mqttc.subscribe(topic, qos=MQTT_QOS)


def _subscribe_all_routes(mqttc) -> None:
    mqttc.subscribe(LinkTopics(server_route_id()).pairing, qos=MQTT_QOS)
    for paired_client in list_clients():
        _subscribe_client(mqttc, paired_client)


def _dispatch_codex_event(task_id: str, event: dict) -> None:
    with codex_task_callbacks_lock:
        callback = codex_task_callbacks.get(task_id)
    if callback:
        callback(task_id, event)
    if str(event.get("status") or "") in {"completed", "failed", "cancelled"}:
        with codex_task_callbacks_lock:
            codex_task_callbacks.pop(task_id, None)


def _codex_server(executable: str, env: dict) -> CodexAppServer:
    global codex_app_server
    with codex_task_callbacks_lock:
        if codex_app_server is None or codex_app_server.executable != executable:
            codex_app_server = CodexAppServer(executable, env, _dispatch_codex_event)
    return codex_app_server
phone_publish_lock = threading.RLock()
pending_task_events: dict[str, tuple[dict, dict]] = {}
pending_task_events_lock = threading.Lock()


def _trace_event(stage: str, detail: object = "") -> dict:
    return {
        "stage": str(stage),
        "at": int(time.time() * 1000),
        "detail": str(detail or "")[:240],
    }


def _delivery_trace(payload: dict | None, *events: dict) -> list[dict]:
    raw = []
    if isinstance(payload, dict):
        candidate = payload.get("delivery_trace") or payload.get("deliveryTrace") or []
        if isinstance(candidate, list):
            raw = candidate
    trace: list[dict] = []
    for item in raw:
        if not isinstance(item, dict):
            continue
        stage = str(item.get("stage") or "").strip()
        if not stage:
            continue
        trace.append({
            "stage": stage,
            "at": int(item.get("at") or int(time.time() * 1000)),
            "detail": str(item.get("detail") or "")[:240],
        })
    trace.extend(events)
    return trace[-32:]


def _desktop_trace(*events: dict) -> list[dict]:
    return _delivery_trace({}, *events)


def _reason_code_value(reason_code):
    try:
        return int(reason_code)
    except Exception:
        return getattr(reason_code, "value", reason_code)


def on_connect(mqttc, userdata, flags, reason_code, properties=None):
    if _reason_code_value(reason_code) == 0:
        log.info(f"MQTT connected {BROKER}:{PORT}")
        _subscribe_all_routes(mqttc)
        for recovered_task in agent_task_manager.drain_recovered():
            _publish_or_queue_task_event(mqttc, {"scheme": "signal", "from": "android"}, recovered_task, [])
        flush_pending_task_events(mqttc)
        flush_outbound_messages(mqttc)
    else:
        log.warning(f"MQTT connection failed rc={reason_code}")


def on_disconnect(mqttc, userdata, *args):
    reason_code = args[-2] if len(args) >= 2 else (args[0] if args else "unknown")
    log.warning(f"MQTT disconnected rc={reason_code}")


def on_publish(mqttc, userdata, mid, reason_code=None, properties=None):
    log.info(f"MQTT broker publish ack mid={mid} rc={reason_code}")
    with pending_delivery_acks_lock:
        ack = pending_delivery_acks.pop(int(mid), None)
    if ack:
        publish_delivery_ack(mqttc, ack, reason_code)
    with pending_outbound_acks_lock:
        outbound = pending_outbound_acks.pop(int(mid), None)
    if outbound:
        mark_outbound_published(outbound[0], outbound[1])


def track_delivery_ack(mid: int, payload: dict, stage: str, detail: object = ""):
    ack = build_delivery_ack_payload(payload, stage, detail)
    if not ack:
        return
    with pending_delivery_acks_lock:
        pending_delivery_acks[int(mid)] = ack


def build_delivery_ack_payload(payload: dict, stage: str, detail: object = "") -> dict:
    source_message_id = str(payload.get("source_message_id") or "").strip()
    if not source_message_id:
        return {}
    return {
        "type": "delivery_ack",
        "source_message_id": source_message_id,
        "contact_id": payload.get("contact_id", ""),
        "agent_id": payload.get("agent_id", ""),
        "desktop_id": desktop_id(),
        "desktop_name": desktop_name(),
        "sender": "system",
        "delivery_status": "broker_ack",
        "time": time.time(),
        "delivery_trace": _delivery_trace(payload, _trace_event(stage, detail)),
        "_client_route_id": str(payload.get("_client_route_id") or ""),
    }


def publish_delivery_ack(mqttc, ack: dict, reason_code=None):
    ack["broker_reason_code"] = str(reason_code or "")
    ack["delivery_trace"] = _delivery_trace(
        ack,
        _trace_event("desktop_broker_ack", f"mid source={ack.get('source_message_id')}")
    )
    client_route_id = str(ack.pop("_client_route_id", "") or "")
    paired_client = get_client(client_route_id)
    if not paired_client:
        return
    target_topic = paired_client["topics"]["control"]
    try:
        info = _publish_to_registered_client(mqttc, paired_client, ack, "control", durable=False)
        log.info(
            "MQTT delivery ack control published "
            f"source={ack.get('source_message_id')} mid={info.mid} rc={info.rc}"
        )
    except Exception as exc:
        log.warning(f"MQTT delivery ack control skipped: {exc}")


def get_lan_ip() -> str:
    try:
        with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as sock:
            sock.connect(("8.8.8.8", 80))
            return sock.getsockname()[0]
    except OSError:
        return "127.0.0.1"


def _safe_uploaded_file(file_id: str) -> Path | None:
    if not file_id or "/" in file_id or "\\" in file_id or ".." in file_id:
        return None
    path = FILES_DIR / file_id
    return path if path.is_file() else None


def _content_from_audio(file_id: str, caption: str, audio_data_b64: str = "") -> str:
    audio_path = _safe_uploaded_file(file_id)
    if audio_path is None and audio_data_b64:
        audio_path = _save_inline_audio(file_id, audio_data_b64)
    if audio_path is None:
        return caption or "Reply exactly: Voice upload was not found on the PC file server. Please try sending it again."
    try:
        transcript = transcribe_audio(audio_path)
    except Exception as exc:
        log.error(f"MQTT voice transcription failed: {exc}")
        return "Reply exactly: I received the voice message, but speech-to-text is not available on this PC. Please type the message or enable faster-whisper."
    if not transcript:
        return "Reply exactly: I received the voice message, but I could not hear any clear speech. Please try again or type the message."
    return transcript


def _save_inline_audio(file_id: str, audio_data_b64: str) -> Path | None:
    if not file_id or "/" in file_id or "\\" in file_id or ".." in file_id:
        file_id = f"inline_voice_{int(time.time())}.m4a"
    target = FILES_DIR / file_id
    try:
        target.write_bytes(base64.b64decode(audio_data_b64, validate=True))
        return target
    except Exception as exc:
        log.error(f"MQTT inline audio save failed: {exc}")
        return None


def clean_audio_reply(reply: str) -> str:
    markers = (
        "Reply directly to the user's voice transcript.",
        "Reply to the user's voice transcript directly.",
        "Do not mention transcription unless necessary.",
    )
    for marker in markers:
        if marker in reply:
            tail = reply.split(marker, 1)[1].strip()
            if tail:
                return tail

    parts = [part.strip() for part in re.split(r"(?:\r?\n){2,}", reply) if part.strip()]
    if len(parts) <= 1:
        return reply.strip()
    noisy_prefixes = ("The user sent a voice message.", "Transcript:", "VOICE_TRANSCRIPT", "Do not mention transcription")
    while len(parts) > 1 and any(parts[0].startswith(prefix) or prefix in parts[0] for prefix in noisy_prefixes):
        parts.pop(0)
    return "\n\n".join(parts).strip()


def _publish_phone_payload(mqttc, wire_payload: dict, reply_payload: dict) -> bool:
    paired_client = _wire_client(wire_payload)
    if not paired_client:
        log.warning("Phone publish skipped: no active client route")
        return False
    channel = "control" if reply_payload.get("type") in {
        "delivery_ack", "agent_task_event", "pairing_revoked", "connector_status", "capability_manifest"
    } else "down"
    target_topic = paired_client["topics"][channel]
    with phone_publish_lock:
        info = _publish_to_registered_client(
            mqttc, paired_client, reply_payload, channel,
            durable=reply_payload.get("type") != "delivery_ack",
        )
        reply_payload["_client_route_id"] = wire_payload.get("_client_route_id", "")
        if reply_payload.get("type") != "delivery_ack":
            track_delivery_ack(info.mid, reply_payload, "desktop_reply_broker_ack", target_topic)
        log.info(f"MQTT encrypted reply published mid={info.mid} rc={info.rc}")
        return info.rc == mqtt.MQTT_ERR_SUCCESS


def _agent_task_payload(task: dict, trace: list[dict]) -> dict:
    status = str(task.get("status") or "")
    stage = f"agent_{status}"
    return {
        "type": "agent_task_event",
        "task_id": task.get("task_id", ""),
        "task_status": status,
        "contact_id": task.get("contact_id", ""),
        "agent_id": task.get("agent_id", ""),
        "source_message_id": task.get("source_message_id", ""),
        "conversation_id": task.get("conversation_id", ""),
        "created_at": task.get("created_at", 0),
        "started_at": task.get("started_at", 0),
        "updated_at": task.get("updated_at", 0),
        "completed_at": task.get("completed_at", 0),
        "elapsed_ms": task.get("elapsed_ms", 0),
        "status_seq": task.get("status_seq", 0),
        "process_id": task.get("process_id", 0),
        "thread_id": task.get("thread_id", ""),
        "turn_id": task.get("turn_id", ""),
        "current_step": task.get("current_step", ""),
        "error": task.get("error", ""),
        "output_files": task.get("output_files", []),
        "desktop_id": desktop_id(),
        "desktop_name": desktop_name(),
        "sender": "system",
        "time": time.time(),
        "delivery_trace": _delivery_trace({"delivery_trace": trace}, _trace_event(stage, task.get("agent_id", ""))),
    }


def _publish_or_queue_task_event(mqttc, wire_payload: dict, task: dict, trace: list[dict]) -> bool:
    payload = _agent_task_payload(task, trace)
    task_id = str(task.get("task_id") or "")
    try:
        published = bool(mqttc is not None and mqttc.is_connected() and _publish_phone_payload(mqttc, wire_payload, payload))
    except Exception as exc:
        log.warning(f"Agent task event queued task_id={task_id}: {exc}")
        published = False
    with pending_task_events_lock:
        if published:
            pending_task_events.pop(task_id, None)
        elif task_id:
            pending_task_events[task_id] = (dict(wire_payload), payload)
    return published


def flush_pending_task_events(mqttc) -> None:
    with pending_task_events_lock:
        queued = list(pending_task_events.items())
    for task_id, (wire_payload, payload) in queued:
        try:
            if _publish_phone_payload(mqttc, wire_payload, payload):
                with pending_task_events_lock:
                    pending_task_events.pop(task_id, None)
        except Exception as exc:
            log.warning(f"Agent task event replay deferred task_id={task_id}: {exc}")


def _start_remote_agent_task(mqttc, wire_payload: dict, payload: dict, trace: list[dict], content: str, msg_type: str) -> None:
    contact_id = str(payload.get("contact_id") or "hermes")
    agent_id = _agent_id_from_contact(contact_id, payload.get("agent_id"))
    source_message_id = str(payload.get("client_message_id") or payload.get("message_id") or "")

    def publish_event(task: dict) -> None:
        _publish_or_queue_task_event(mqttc, wire_payload, task, trace)

    def run_task(task) -> str:
        log.info(f"Agent task running task_id={task.task_id} contact_id={contact_id} agent_id={agent_id}")
        reply = ask_agent_sync(agent_id, content, task_id=task.task_id)
        if msg_type in {"audio", "voice"}:
            marker = "Voice message received."
            if marker in reply:
                reply = reply[reply.index(marker):].strip()
            reply = clean_audio_reply(reply)
        return reply

    def publish_result(task: dict) -> None:
        from rich_output import build_rich_output
        reply, rich_output = build_rich_output(
            str(task.get("result") or ""),
            list(task.get("output_files") or []),
            str(task.get("task_id") or ""),
        )
        reply_payload = {
            "type": "text",
            "content": reply,
            "task_id": task.get("task_id", ""),
            "task_status": task.get("status", ""),
            "contact_id": contact_id,
            "agent_id": agent_id,
            "desktop_id": desktop_id(),
            "desktop_name": desktop_name(),
            "source_message_id": source_message_id,
            "conversation_id": task.get("conversation_id", ""),
            "turn_id": task.get("turn_id", ""),
            "delivery_trace": _delivery_trace(
                {"delivery_trace": trace},
                _trace_event("agent_replied", f"{agent_id} chars={len(reply)}"),
                _trace_event("desktop_reply_publish_queued", _wire_down_topic(wire_payload)),
            ),
            "sender": "other",
            "time": time.time(),
        }
        if rich_output:
            reply_payload["rich_output"] = rich_output
        _publish_phone_payload(mqttc, wire_payload, reply_payload)

    if agent_id == "codex":
        from agent_gateway import BASE_AGENTS, _agent_env, _find_codex_desktop_cli
        task = agent_task_manager.create_external(
            agent_id=agent_id, contact_id=contact_id, source_message_id=source_message_id,
            prompt=content, on_event=publish_event, task_id=str(payload.get("task_id") or ""),
            conversation_id=str(payload.get("conversation_id") or ""),
        )

        def app_event(task_id: str, event: dict) -> None:
            updated = agent_task_manager.update(
                task_id, str(event.get("status") or "running"), on_event=publish_event,
                thread_id=event.get("thread_id"), turn_id=event.get("turn_id"),
                current_step=event.get("current_step"), result=event.get("result"),
                error=event.get("error"),
            )
            if updated and updated.status == "completed" and updated.result:
                publish_result(updated.public())

        def start_codex() -> None:
            try:
                executable = _find_codex_desktop_cli() or "codex"
                from task_workspace import task_workspace

                with codex_task_callbacks_lock:
                    codex_task_callbacks[task.task_id] = app_event
                server = _codex_server(executable, _agent_env(BASE_AGENTS["codex"]))
                server.start_task(
                    task.task_id,
                    content,
                    str(task_workspace(task.task_id, agent_id)),
                    conversation_id=str(payload.get("conversation_id") or ""),
                )
            except Exception as exc:
                agent_task_manager.update(task.task_id, "failed", on_event=publish_event, error=str(exc)[:500])

        threading.Thread(target=start_codex, daemon=True).start()
        return

    agent_task_manager.create(
        agent_id=agent_id,
        contact_id=contact_id,
        source_message_id=source_message_id,
        prompt=content,
        runner=run_task,
        on_event=publish_event,
        on_result=publish_result,
        task_id=str(payload.get("task_id") or ""),
        conversation_id=str(payload.get("conversation_id") or ""),
    )


def on_message(mqttc, userdata, msg):
    try:
        if len(msg.payload) > 512 * 1024:
            log.warning("MQTT message rejected: envelope exceeds size limit")
            return
        route = parse_topic(msg.topic)
        if route is None or route[0] != server_route_id():
            log.warning("MQTT message rejected: invalid SignalASI Link route")
            return
        _, client_route_id, channel = route
        wire_payload = json.loads(msg.payload.decode("utf-8"))
        if channel == "pair":
            token = str(wire_payload.get("pairing_token") or "")
            secret = pairing_secret(token)
            if not secret:
                log.warning("MQTT pairing ciphertext rejected: unknown token")
                return
            try:
                claim = decrypt_pairing_claim(wire_payload, secret)
            except Exception as exc:
                log.warning("MQTT pairing ciphertext rejected: %s", exc)
                return
            if claim.get("pairing_token") != token:
                log.warning("MQTT pairing ciphertext rejected: token binding mismatch")
                return
            handle_pairing_claim(mqttc, claim)
            return
        paired_client = get_client(client_route_id)
        if paired_client is None:
            log.warning("MQTT message rejected: client route is not paired")
            return
        wire_payload["_client_route_id"] = client_route_id
        if wire_payload.get("scheme") != "signal":
            log.warning("Rejected unencrypted MQTT message: scheme != signal")
            return
        else:
            if str(wire_payload.get("from") or "") != paired_client["signal_name"]:
                log.warning("Rejected MQTT message: cryptographic sender does not match route")
                return
            application_envelope = decrypt_signal_envelope(wire_payload, remote_name=paired_client["signal_name"])
            validate_envelope(application_envelope)
            if application_envelope["source_id"] != paired_client["signal_name"]:
                log.warning("Rejected MQTT message: application sender does not match paired identity")
                return
            message_id = str(application_envelope["message_id"])
            if not claim_message(client_route_id, message_id):
                if application_envelope.get("payload", {}).get("type") == "delivery_ack":
                    return
                previous = previous_acknowledgement(client_route_id, message_id)
                _publish_phone_payload(mqttc, wire_payload, {
                    "type": "delivery_ack",
                    "message_id": message_id,
                    "delivery_status": previous.get("status", "duplicate"),
                    "duplicate": True,
                    "sender": "system",
                    "time": time.time(),
                })
                return
            payload = application_envelope["payload"]
            payload.setdefault("message_id", message_id)
            payload.setdefault("conversation_id", application_envelope.get("conversation_id", ""))
            payload.setdefault("source_message_id", message_id)
            touch_client(client_route_id)
            trace = _delivery_trace(payload, _trace_event("desktop_received", msg.topic), _trace_event("desktop_decrypted", "SignalASI Link"))
            if payload.get("type") == "delivery_ack":
                acknowledged_id = str(payload.get("source_message_id") or application_envelope.get("reply_to") or "")
                acknowledge_outbound(client_route_id, acknowledged_id)
                complete_message(client_route_id, message_id, "completed", {"status": "completed"})
                return
            complete_message(client_route_id, message_id, "accepted", {"status": "accepted"})
            _publish_phone_payload(mqttc, wire_payload, {
                "type": "delivery_ack",
                "source_message_id": message_id,
                "delivery_status": "accepted",
                "sender": "system",
                "time": time.time(),
                "delivery_trace": trace,
            })

        if _route_phone_tool_payload(
            mqttc,
            paired_client,
            application_envelope,
            payload,
            channel,
        ):
            return

        content = payload.get("content", "")
        contact_id = payload.get("contact_id", "hermes")
        agent_id = _agent_id_from_contact(contact_id, payload.get("agent_id"))
        msg_type = payload.get("type", "text")
        file_id = payload.get("file_id", "")
        name = payload.get("name") or file_id or "Voice message"
        caption = payload.get("caption", "")
        audio_mode = str(payload.get("audio_mode") or "agent_reply")

        log.info(f"MQTT received: [{msg_type}] {content[:50]}")

        if msg_type == "client_revoked":
            _close_phone_tool_sessions(client_route_id, "paired phone revoked this Desktop")
            revoke_client(client_route_id, str(payload.get("reason") or "forgotten_by_client"))
            remove_peer_signal_session(
                paired_client["signal_name"], int(paired_client.get("signal_device_id") or 1)
            )
            log.info("Client relationship revoked client=%s", client_route_id)
            return

        if msg_type == "agent_task_cancel":
            task_id = str(payload.get("task_id") or "").strip()
            existing_task = agent_task_manager.get(task_id)
            task_matches = existing_task is not None and existing_task.contact_id == str(contact_id)
            source_message_id = str(payload.get("source_message_id") or "")
            if task_matches and source_message_id and existing_task.source_message_id:
                task_matches = source_message_id == existing_task.source_message_id
            if task_matches and existing_task.agent_id == "codex" and codex_app_server is not None:
                try:
                    codex_app_server.interrupt(task_id)
                except Exception as exc:
                    log.warning(f"Codex turn interrupt failed task_id={task_id}: {exc}")
            task = agent_task_manager.cancel(
                task_id,
                lambda event: _publish_or_queue_task_event(mqttc, wire_payload, event, trace),
            ) if task_matches else None
            if task is None:
                _publish_phone_payload(mqttc, wire_payload, {
                    "type": "agent_task_event",
                    "task_id": task_id,
                    "task_status": "not_found",
                    "contact_id": contact_id,
                    "agent_id": agent_id,
                    "source_message_id": payload.get("source_message_id") or "",
                    "error": "Task was not found",
                    "sender": "system",
                    "time": time.time(),
                    "delivery_trace": _delivery_trace({"delivery_trace": trace}, _trace_event("agent_not_found", task_id)),
                })
            return

        if msg_type == "agent_conversation_delete":
            conversation_id = str(payload.get("conversation_id") or "").strip()
            requested_ids = {
                str(value).strip() for value in (payload.get("task_ids") or [])
                if str(value).strip()
            }
            deleted_ids = agent_task_manager.delete_conversation(conversation_id, requested_ids)
            if codex_app_server is not None:
                codex_app_server.delete_conversation(conversation_id)
            from task_workspace import cleanup_task_temporary_files
            cleaned_ids = cleanup_task_temporary_files(deleted_ids or requested_ids)
            log.info(
                "Agent conversation cleanup conversation_id=%s tasks=%d temporary=%d",
                conversation_id, len(deleted_ids), len(cleaned_ids),
            )
            return

        if msg_type in {"audio", "voice"}:
            content = _content_from_audio(file_id, caption, str(payload.get("audio_data_b64") or ""))
        elif not str(content).strip() and msg_type in {"image", "file_notify"}:
            content = caption or f"Received file: {name}"

        if msg_type in {"audio", "voice"} and audio_mode == "transcribe_only":
            transcript = str(content or "").strip()
            transcription_success = not transcript.startswith("Reply exactly:")
            if not transcription_success:
                transcript = transcript.removeprefix("Reply exactly:").strip()
            trace.append(_trace_event("voice_transcribed", f"success={transcription_success} chars={len(transcript)}"))
            reply_payload = {
                "type": "voice_transcript",
                "content": transcript,
                "transcription_success": transcription_success,
                "contact_id": contact_id,
                "agent_id": agent_id,
                "desktop_id": desktop_id(),
                "desktop_name": desktop_name(),
                "source_message_id": payload.get("client_message_id") or payload.get("message_id") or "",
                "delivery_trace": _delivery_trace(
                    {"delivery_trace": trace},
                    _trace_event("desktop_transcript_publish_queued", _wire_down_topic(wire_payload)),
                ),
                "sender": "other",
                "time": time.time(),
            }
            _publish_phone_payload(mqttc, wire_payload, reply_payload)
            return

        if contact_id not in {"system", "me"} and content.strip():
            log.info(f"MQTT accepted Agent task contact_id={contact_id} agent_id={agent_id}")
            _start_remote_agent_task(mqttc, wire_payload, payload, trace, content, msg_type)
    except Exception as e:
        log.error(f"MQTT message handling error: {e}")


def handle_pairing_claim(mqttc, payload: dict):
    token = str(payload.get("pairing_token") or "")
    bundle = payload.get("signal_bundle")
    fingerprint = str(payload.get("identity_fingerprint") or "")
    client_route_id = str(payload.get("client_route_id") or "")
    signal_name = str(payload.get("signal_name") or "")
    if payload.get("protocol") != PROTOCOL_NAME or payload.get("version") != PROTOCOL_VERSION:
        log.warning("MQTT pairing claim rejected: unsupported protocol")
        return
    if payload.get("server_route_id") != server_route_id() or not valid_route_id(client_route_id):
        log.warning("MQTT pairing claim rejected: invalid route binding")
        return
    if not signal_name or signal_name != str(payload.get("signalasi_id") or payload.get("from") or ""):
        log.warning("MQTT pairing claim rejected: invalid Signal identity name")
        return
    if not isinstance(bundle, dict) or not fingerprint:
        log.warning("MQTT pairing claim rejected: missing signal bundle")
        return
    try:
        bundle_fingerprint = hashlib.sha256(base64.b64decode(bundle["identityKey"], validate=True)).hexdigest()
    except Exception:
        log.warning("MQTT pairing claim rejected: invalid identity key")
        return
    if not secrets.compare_digest(bundle_fingerprint.lower(), fingerprint.lower()):
        log.warning("MQTT pairing claim rejected: bundle fingerprint mismatch")
        return
    if signal_name != f"signalasi:{fingerprint[:16]}":
        log.warning("MQTT pairing claim rejected: Signal name does not match identity")
        return
    if get_client(client_route_id, include_revoked=True) is not None:
        log.warning("MQTT pairing claim rejected: client route was already used")
        return
    if not validate_pairing_token(token, consume=True):
        log.warning("MQTT pairing claim rejected: invalid token")
        return
    result = replace_peer_signal_bundle(
        bundle,
        remote_name=signal_name,
        remote_device_id=int(payload.get("signal_device_id") or 1),
    )
    paired_client = record_pairing_success(
        fingerprint=fingerprint,
        remote_name=signal_name,
        remote_device_id=int(payload.get("signal_device_id") or 1),
        client_route_id=client_route_id,
        display_name=str(payload.get("client_name") or "SignalASI Client")[:120],
        platform=str(payload.get("platform") or "unknown")[:32],
    )
    _subscribe_client(mqttc, paired_client)
    log.info(f"MQTT pairing claim accepted fingerprint={fingerprint[:16]} result={result}")

    ack_payload = {
        "type": "pairing_confirmed",
        "content": "SignalASI Desktop completed a new secure pairing.",
        "contact_id": "system",
        "desktop_id": desktop_id(),
        "desktop_name": desktop_name(),
        "desktop_fingerprint": get_signal_bundle().get("identityKeySha256", ""),
        "protocol": PROTOCOL_NAME,
        "version": PROTOCOL_VERSION,
        "server_route_id": server_route_id(),
        "client_route_id": client_route_id,
        "routes": paired_client["topics"],
        "signal_bundle": get_signal_bundle(),
        "sender": "system",
        "connector_agents": mobile_connector_agents(client_route_id),
        "delivery_trace": _desktop_trace(_trace_event("desktop_pairing_confirmed", fingerprint[:16])),
        "time": time.time(),
    }
    info = mqttc.publish(paired_client["topics"]["down"], json.dumps(ack_payload, ensure_ascii=False), qos=MQTT_QOS)
    log.info(f"MQTT public pairing confirmation published mid={info.mid} rc={info.rc}")
    timer = threading.Timer(1.0, publish_capability_manifest, args=(mqttc, client_route_id))
    timer.daemon = True
    timer.start()


def mobile_connector_agents(client_route_id: str = "") -> list[dict]:
    diagnostics = connector_diagnostics()
    agents = []
    did = desktop_id()
    dname = desktop_name()
    fingerprint = get_signal_bundle().get("identityKeySha256", "")
    up_topic = _client_topics(client_route_id).up if client_route_id else ""
    for agent in diagnostics.get("agents", []):
        agent_id = agent.get("mobile_contact_id") or agent.get("id")
        if agent_id in MOBILE_HIDDEN_AGENT_IDS or agent.get("kind") in MOBILE_HIDDEN_AGENT_IDS:
            continue
        agents.append({
            "id": f"{did}:{agent_id}",
            "agent_id": agent_id,
            "name": agent.get("name") or agent.get("id"),
            "display_name": f"{agent.get('name') or agent.get('id')} · {dname}",
            "desktop_id": did,
            "desktop_name": dname,
            "desktop_fingerprint": fingerprint,
            "status": agent.get("status") or "needs_setup",
            "detail": agent.get("detail") or "",
            "setup": agent.get("setup") or "",
            "kind": agent.get("kind") or "",
            "mqtt_topic": up_topic,
            "updated_at": time.time(),
        })
    return agents


def capability_manifest(client_route_id: str = "") -> dict:
    diagnostics = connector_diagnostics()
    return {
        "type": "capability_manifest",
        "manifest_version": 1,
        "server": {
            "id": desktop_id(),
            "name": desktop_name(),
            "platform": "windows",
            "role": "server",
        },
        "agents": mobile_connector_agents(client_route_id),
        "models": [],
        "tools": ["agent_tasks", "voice_stt", "file_transfer"],
        "features": [
            "tasks",
            "task_events",
            "voice",
            "files",
            "reliable_delivery",
            "multi_client",
            "phone_native_tool_session_v1",
        ],
        "limits": {
            "max_parallel_tasks": int(os.environ.get("SIGNALASI_MAX_PARALLEL_TASKS", "4")),
            "max_message_bytes": 524288,
        },
        "generated_at": int(time.time() * 1000),
        "connector_agents": mobile_connector_agents(client_route_id),
    }


def publish_capability_manifest(mqttc, client_route_id: str) -> bool:
    paired_client = get_client(client_route_id)
    if not paired_client:
        return False
    try:
        info = _publish_to_registered_client(
            mqttc, paired_client, capability_manifest(client_route_id), "control"
        )
        return info.rc == mqtt.MQTT_ERR_SUCCESS
    except Exception as exc:
        log.warning("Capability manifest publish failed client=%s: %s", client_route_id, exc)
        return False


def _publish_to_registered_client(
    mqttc, paired_client: dict, payload: dict, channel: str = "down", durable: bool = True
):
    application_envelope = make_envelope(
        payload,
        source_id=desktop_id(),
        target_id=paired_client["signal_name"],
        conversation_id=str(payload.get("conversation_id") or ""),
        reply_to=str(payload.get("source_message_id") or ""),
    )
    encrypted = encrypt_signal_payload(application_envelope, remote_name=paired_client["signal_name"])
    topic = paired_client["topics"][channel]
    wire_payload = json.dumps(encrypted, ensure_ascii=False)
    message_id = application_envelope["message_id"]
    if durable:
        queue_outbound(paired_client["client_route_id"], message_id, topic, wire_payload)
    info = mqttc.publish(topic, wire_payload, qos=MQTT_QOS)
    if durable:
        with pending_outbound_acks_lock:
            pending_outbound_acks[int(info.mid)] = (paired_client["client_route_id"], message_id)
    return info


def flush_outbound_messages(mqttc) -> None:
    for pending in pending_outbound():
        paired_client = get_client(pending["client_route_id"])
        if not paired_client:
            continue
        info = mqttc.publish(pending["topic"], pending["wire_payload"], qos=MQTT_QOS)
        with pending_outbound_acks_lock:
            pending_outbound_acks[int(info.mid)] = (pending["client_route_id"], pending["message_id"])


def _target_clients(client_route_id: str = "", broadcast: bool = False) -> list[dict]:
    if client_route_id:
        paired_client = get_client(client_route_id)
        return [paired_client] if paired_client else []
    clients = list_clients()
    if broadcast or len(clients) <= 1:
        return clients
    return []


def _agent_id_from_contact(contact_id: str, explicit_agent_id: object = None) -> str:
    explicit = str(explicit_agent_id or "").strip()
    if explicit:
        return explicit
    value = str(contact_id or "hermes").strip()
    if value.startswith("desktop_") and ":" in value:
        return value.split(":", 1)[1] or "hermes"
    return value or "hermes"


def publish_connector_status(mqttc=None, reason: str = "status_update", client_route_id: str = "") -> dict:
    if not is_paired() and os.environ.get("SIGNALASI_ALLOW_UNPAIRED_MQTT") != "1":
        return api_error("phone_not_paired", "Phone is not paired", reason=reason, params={"reason": reason})
    mqttc = mqttc or client
    if mqttc is None:
        return api_error("mqtt_not_initialized", reason=reason, params={"reason": reason})
    if hasattr(mqttc, "is_connected") and not mqttc.is_connected():
        return api_error("mqtt_not_connected", reason=reason, params={"reason": reason})
    payload = {
        "type": "connector_status",
        "content": "SignalASI Desktop connector status updated.",
        "contact_id": "system",
        "desktop_id": desktop_id(),
        "desktop_name": desktop_name(),
        "desktop_fingerprint": get_signal_bundle().get("identityKeySha256", ""),
        "sender": "system",
        "reason": reason,
        "connector_agents": mobile_connector_agents(client_route_id),
        "delivery_trace": _desktop_trace(_trace_event("desktop_connector_status", reason)),
        "time": time.time(),
    }
    try:
        targets = _target_clients(client_route_id, broadcast=True)
        mids = [_publish_to_registered_client(mqttc, target, {**payload, "connector_agents": mobile_connector_agents(target["client_route_id"])}, "control").mid for target in targets]
        return api_ok("connector_status_published", reason=reason, client_count=len(targets), mids=mids, params={"reason": reason, "client_count": len(targets)})
    except Exception as exc:
        log.warning(f"MQTT connector status skipped: {exc}")
        return api_error("publish_failed", str(exc), reason=reason, params={"reason": reason})


def publish_pairing_revoked(mqttc=None, reason: str = "forgotten_by_desktop", client_route_id: str = "") -> dict:
    """Notify the previously paired phone before local trust is cleared."""
    if not is_paired() and os.environ.get("SIGNALASI_ALLOW_UNPAIRED_MQTT") != "1":
        return api_error("phone_not_paired", "Phone is not paired", reason=reason, params={"reason": reason})
    mqttc = mqttc or client
    if mqttc is None:
        return api_error("mqtt_not_initialized", reason=reason, params={"reason": reason})
    if hasattr(mqttc, "is_connected") and not mqttc.is_connected():
        return api_error("mqtt_not_connected", reason=reason, params={"reason": reason})
    revoke_payload = {
        "type": "pairing_revoked",
        "content": "This desktop connector has forgotten this phone. Scan the SignalASI QR code again before communicating.",
        "contact_id": "system",
        "desktop_id": desktop_id(),
        "desktop_name": desktop_name(),
        "sender": "system",
        "reason": reason,
        "delivery_trace": _desktop_trace(_trace_event("desktop_pairing_revocation_queued", reason)),
        "time": time.time(),
    }
    try:
        targets = _target_clients(client_route_id, broadcast=not bool(client_route_id))
        results = [_publish_to_registered_client(mqttc, target, revoke_payload, "control") for target in targets]
        ok = all(info.rc == mqtt.MQTT_ERR_SUCCESS for info in results)
        if ok:
            return api_ok("pairing_revocation_published", reason=reason, client_count=len(results), params={"reason": reason, "client_count": len(results)})
        return api_error("publish_failed", "One or more revocation messages failed", reason=reason)
    except Exception as exc:
        log.warning(f"MQTT pairing revocation skipped: {exc}")
        return api_error("publish_failed", str(exc), reason=reason, params={"reason": reason})


def publish_mobile_test_message(contact_id: str, content: str, client_route_id: str = "", broadcast: bool = False) -> dict:
    """Publish an encrypted diagnostic message to the Android app."""
    if not is_paired() and os.environ.get("SIGNALASI_ALLOW_UNPAIRED_MQTT") != "1":
        return api_error(
            "phone_not_paired",
            "Phone is not paired. Scan /signalasi/verify before sending mobile diagnostics.",
            contact_id=contact_id,
            params={"contact_id": contact_id, "route": "/signalasi/verify"},
        )
    if client is None:
        return api_error("mqtt_not_initialized", contact_id=contact_id, params={"contact_id": contact_id})
    if not client.is_connected():
        return api_error("mqtt_not_connected", contact_id=contact_id, params={"contact_id": contact_id})
    payload = {
        "type": "text",
        "content": content,
        "contact_id": contact_id,
        "agent_id": _agent_id_from_contact(contact_id),
        "desktop_id": desktop_id(),
        "desktop_name": desktop_name(),
        "sender": "other",
        "time": time.time(),
        "diagnostic": True,
        "delivery_trace": _desktop_trace(_trace_event("desktop_mobile_test_queued", contact_id)),
    }
    targets = _target_clients(client_route_id, broadcast=broadcast)
    if not targets and len(list_clients()) > 1 and not client_route_id and not broadcast:
        return api_error("client_route_required", "Multiple clients are paired; select a client or explicitly broadcast")
    results = [_publish_to_registered_client(client, target, payload) for target in targets]
    if results and all(info.rc == mqtt.MQTT_ERR_SUCCESS for info in results):
        return api_ok("mobile_test_published", client_count=len(results), contact_id=contact_id, params={"contact_id": contact_id, "client_count": len(results)})
    return api_error("publish_failed", "No target client or publish failed", contact_id=contact_id)


def publish_agent_push_message(contact_id: str, content: str, source: str = "agent", client_route_id: str = "", broadcast: bool = False) -> dict:
    """Publish an encrypted message initiated by a local Agent or automation."""
    cleaned_contact_id = str(contact_id or "").strip()
    cleaned_content = str(content or "").strip()
    if not cleaned_contact_id:
        return api_error("contact_id_required")
    if not cleaned_content:
        return api_error("content_required", contact_id=cleaned_contact_id, params={"contact_id": cleaned_contact_id})
    if not is_paired() and os.environ.get("SIGNALASI_ALLOW_UNPAIRED_MQTT") != "1":
        return api_error(
            "phone_not_paired",
            "Phone is not paired. Scan /signalasi/verify before pushing Agent messages.",
            contact_id=cleaned_contact_id,
            params={"contact_id": cleaned_contact_id, "route": "/signalasi/verify"},
        )
    if client is None:
        return api_error("mqtt_not_initialized", contact_id=cleaned_contact_id, params={"contact_id": cleaned_contact_id})
    if not client.is_connected():
        return api_error("mqtt_not_connected", contact_id=cleaned_contact_id, params={"contact_id": cleaned_contact_id})
    payload = {
        "type": "text",
        "content": cleaned_content,
        "contact_id": cleaned_contact_id,
        "agent_id": _agent_id_from_contact(cleaned_contact_id),
        "desktop_id": desktop_id(),
        "desktop_name": desktop_name(),
        "sender": "other",
        "time": time.time(),
        "source": str(source or "agent")[:64],
        "agent_push": True,
        "delivery_trace": _desktop_trace(_trace_event("desktop_agent_push_queued", cleaned_contact_id)),
    }
    targets = _target_clients(client_route_id, broadcast=broadcast)
    if not targets and len(list_clients()) > 1 and not client_route_id and not broadcast:
        return api_error("client_route_required", "Multiple clients are paired; select a client or explicitly broadcast")
    results = [_publish_to_registered_client(client, target, payload) for target in targets]
    params = {"contact_id": cleaned_contact_id, "source": payload["source"], "client_count": len(results)}
    if results and all(info.rc == mqtt.MQTT_ERR_SUCCESS for info in results):
        return api_ok("agent_push_published", contact_id=cleaned_contact_id, source=payload["source"], params=params)
    return api_error("publish_failed", "No target client or publish failed", contact_id=cleaned_contact_id, source=payload["source"], params=params)


def publish_agent_task_event(task: dict, client_route_id: str = "", broadcast: bool = False) -> bool:
    if not is_paired():
        return False
    published = False
    for paired_client in _target_clients(client_route_id, broadcast=broadcast):
        published = _publish_or_queue_task_event(client, {
            "scheme": "signal",
            "_client_route_id": paired_client["client_route_id"],
        }, task, []) or published
    return published


def start_agent_task(
    contact_id: str,
    prompt: str,
    source_message_id: str = "",
    task_id: str = "",
    client_route_id: str = "",
) -> dict:
    cleaned_contact_id = str(contact_id or "").strip()
    cleaned_prompt = str(prompt or "").strip()
    if not cleaned_contact_id:
        return api_error("contact_id_required")
    if not cleaned_prompt:
        return api_error("content_required", contact_id=cleaned_contact_id)
    if not _target_clients(client_route_id) and len(list_clients()) > 1:
        return api_error("client_route_required", "Multiple clients are paired; select a client")
    agent_id = _agent_id_from_contact(cleaned_contact_id)

    def run_task(task) -> str:
        return ask_agent_sync(agent_id, cleaned_prompt, task_id=task.task_id)

    def publish_result(task: dict) -> None:
        publish_agent_push_message(
            cleaned_contact_id,
            str(task.get("result") or ""),
            source=f"agent-task:{task.get('task_id', '')}",
            client_route_id=client_route_id,
        )

    task = agent_task_manager.create(
        agent_id=agent_id,
        contact_id=cleaned_contact_id,
        source_message_id=str(source_message_id or ""),
        prompt=cleaned_prompt,
        runner=run_task,
        on_event=lambda event: publish_agent_task_event(event, client_route_id=client_route_id),
        on_result=publish_result,
        task_id=str(task_id or ""),
    )
    return api_ok("agent_task_accepted", task=task.public())


def start():
    """Start the MQTT client; this blocks and should run in a background thread."""
    global client, running
    if running:
        return

    running = True
    stable_desktop_id = re.sub(r"[^a-zA-Z0-9_-]", "-", desktop_id())[-48:]
    client_id = f"signalasi-pc-{stable_desktop_id}"
    callback_api_version = getattr(mqtt, "CallbackAPIVersion", None)
    if callback_api_version is not None:
        mqttc = mqtt.Client(callback_api_version=callback_api_version.VERSION2, client_id=client_id, clean_session=False)
    else:
        mqttc = mqtt.Client(client_id=client_id, clean_session=False)
    client = mqttc
    mqttc.on_connect = on_connect
    mqttc.on_disconnect = on_disconnect
    mqttc.on_message = on_message
    mqttc.on_publish = on_publish
    if MQTT_TLS:
        mqttc.tls_set()
        mqttc.tls_insecure_set(False)

    mqttc.reconnect_delay_set(min_delay=1, max_delay=30)
    while running:
        try:
            mqttc.connect(BROKER, PORT, keepalive=60)
            mqttc.loop_forever(retry_first_connection=True)
        except Exception as e:
            log.error(f"MQTT connection failed; retrying in 3 seconds: {e}")
        if running:
            time.sleep(3)


def start_background():
    """Start MQTT in a background thread."""
    t = threading.Thread(target=start, daemon=True)
    t.start()
    log.info("MQTT bridge started in background")


def stop():
    global client, running
    running = False
    _close_phone_tool_sessions(reason="Desktop MQTT bridge stopped")
    if client:
        client.disconnect()
        client = None


if __name__ == "__main__":
    logging.basicConfig(level=logging.INFO)
    start()

