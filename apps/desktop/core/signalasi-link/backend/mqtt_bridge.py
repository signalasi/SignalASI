"""SignalASI Link MQTT bridge - connects the public broker and mobile app."""
import asyncio
import base64
import json
import os
import re
import socket
import threading
import time
import logging
from pathlib import Path

import paho.mqtt.client as mqtt

from api_response import api_error, api_ok
from agent_gateway import ask_agent_sync, connector_diagnostics
from pairing_state import is_paired, record_pairing_success, validate_pairing_token
from signalasi_client import (
    decrypt_signal_envelope,
    desktop_id,
    desktop_name,
    encrypt_signal_payload,
    get_signal_bundle,
    replace_peer_signal_bundle,
)
from stt_bridge import transcribe_audio

log = logging.getLogger("signalasi.mqtt")

BROKER = "broker.emqx.io"
PORT = 1883
DEVICE_ID = "android"
TOPIC_SEND = f"signalasichat/{DEVICE_ID}/send"
TOPIC_RECV = f"signalasichat/{DEVICE_ID}/recv"
TOPIC_PC = f"signalasichat/{DEVICE_ID}/pc"
FILES_DIR = Path.home() / "signalasi_files"
MQTT_QOS = 1
MOBILE_HIDDEN_AGENT_IDS = {"cloud-model"}

client = None
running = False
pending_delivery_acks: dict[int, dict] = {}
pending_delivery_acks_lock = threading.Lock()


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
        mqttc.subscribe(TOPIC_SEND, qos=MQTT_QOS)
        local_ip = get_lan_ip()
        pc_info = {"ip": local_ip, "port": 18765, "signal": get_signal_bundle()}
        mqttc.publish(TOPIC_PC, json.dumps(pc_info, ensure_ascii=False), qos=MQTT_QOS, retain=True)
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
    }


def publish_delivery_ack(mqttc, ack: dict, reason_code=None):
    ack["broker_reason_code"] = str(reason_code or "")
    ack["delivery_trace"] = _delivery_trace(
        ack,
        _trace_event("desktop_broker_ack", f"mid source={ack.get('source_message_id')}")
    )
    try:
        encrypted = encrypt_signal_payload(ack, remote_name="android")
        info = mqttc.publish(TOPIC_RECV, json.dumps(encrypted, ensure_ascii=False), qos=MQTT_QOS)
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


def _publish_phone_payload(mqttc, wire_payload: dict, reply_payload: dict) -> None:
    if wire_payload.get("scheme") == "signal":
        encrypted_reply = encrypt_signal_payload(reply_payload, remote_name=wire_payload.get("from", "android"))
        info = mqttc.publish(TOPIC_RECV, json.dumps(encrypted_reply, ensure_ascii=False), qos=MQTT_QOS)
        track_delivery_ack(info.mid, reply_payload, "desktop_reply_broker_ack", TOPIC_RECV)
        log.info(f"MQTT encrypted reply published mid={info.mid} rc={info.rc}")
    else:
        info = mqttc.publish(TOPIC_RECV, json.dumps(reply_payload, ensure_ascii=False), qos=MQTT_QOS)
        track_delivery_ack(info.mid, reply_payload, "desktop_reply_broker_ack", TOPIC_RECV)
        log.info(f"MQTT plain reply published mid={info.mid} rc={info.rc}")


def on_message(mqttc, userdata, msg):
    try:
        wire_payload = json.loads(msg.payload.decode("utf-8"))
        if wire_payload.get("type") == "signalasi_pairing_claim":
            handle_pairing_claim(mqttc, wire_payload)
            return
        if not is_paired() and os.environ.get("SIGNALASI_ALLOW_UNPAIRED_MQTT") != "1":
            log.warning("MQTT message rejected: no paired phone is trusted")
            return
        if wire_payload.get("scheme") != "signal":
            if os.environ.get("SIGNALASI_ALLOW_PLAIN_SIGNAL") == "1" or os.environ.get("HERMES_SIGNAL_ALLOW_PLAIN") == "1":
                payload = wire_payload
                trace = _delivery_trace(payload, _trace_event("desktop_received", msg.topic), _trace_event("desktop_plain", "unencrypted debug path"))
            else:
                log.warning("Rejected unencrypted MQTT message: scheme != signal")
                return
        else:
            payload = decrypt_signal_envelope(wire_payload, remote_name=wire_payload.get("from", "android"))
            trace = _delivery_trace(payload, _trace_event("desktop_received", msg.topic), _trace_event("desktop_decrypted", "SignalASI Link"))

        content = payload.get("content", "")
        contact_id = payload.get("contact_id", "hermes")
        agent_id = _agent_id_from_contact(contact_id, payload.get("agent_id"))
        msg_type = payload.get("type", "text")
        file_id = payload.get("file_id", "")
        name = payload.get("name") or file_id or "Voice message"
        caption = payload.get("caption", "")
        audio_mode = str(payload.get("audio_mode") or "agent_reply")

        log.info(f"MQTT received: [{msg_type}] {content[:50]}")

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
                    _trace_event("desktop_transcript_publish_queued", TOPIC_RECV),
                ),
                "sender": "other",
                "time": time.time(),
            }
            _publish_phone_payload(mqttc, wire_payload, reply_payload)
            return

        if contact_id not in {"system", "me"} and content.strip():
            log.info(f"MQTT preparing Agent reply contact_id={contact_id} agent_id={agent_id}")
            trace.append(_trace_event("agent_started", agent_id))
            reply = ask_agent_sync(agent_id, content)
            trace.append(_trace_event("agent_replied", f"{agent_id} chars={len(reply)}"))
            log.info(f"MQTT Agent reply ready contact_id={contact_id} agent_id={agent_id} chars={len(reply)}")
            if msg_type in {"audio", "voice"}:
                marker = "Voice message received."
                if marker in reply:
                    reply = reply[reply.index(marker):].strip()
                reply = clean_audio_reply(reply)
            reply_payload = {
                "type": "text",
                "content": reply,
                "contact_id": contact_id,
                "agent_id": agent_id,
                "desktop_id": desktop_id(),
                "desktop_name": desktop_name(),
                "source_message_id": payload.get("client_message_id") or payload.get("message_id") or "",
                "delivery_trace": _delivery_trace({"delivery_trace": trace}, _trace_event("desktop_reply_publish_queued", TOPIC_RECV)),
                "sender": "other",
                "time": time.time(),
            }
            _publish_phone_payload(mqttc, wire_payload, reply_payload)
    except Exception as e:
        log.error(f"MQTT message handling error: {e}")


def handle_pairing_claim(mqttc, payload: dict):
    token = str(payload.get("pairing_token") or "")
    bundle = payload.get("signal_bundle")
    fingerprint = str(payload.get("identity_fingerprint") or "")
    if not validate_pairing_token(token):
        log.warning("MQTT pairing claim rejected: invalid token")
        return
    if not isinstance(bundle, dict):
        log.warning("MQTT pairing claim rejected: missing signal bundle")
        return

    revoke_payload = {
        "type": "pairing_revoked",
        "content": "This PC has been paired with a new SignalASI device. This device session is no longer valid.",
        "contact_id": "system",
        "desktop_id": desktop_id(),
        "desktop_name": desktop_name(),
        "sender": "system",
        "connector_agents": mobile_connector_agents(),
        "delivery_trace": _desktop_trace(_trace_event("desktop_pairing_revocation_queued", "new_pairing_claim")),
        "time": time.time(),
    }
    try:
        encrypted_revoke = encrypt_signal_payload(revoke_payload, remote_name="android")
        info = mqttc.publish(TOPIC_RECV, json.dumps(encrypted_revoke, ensure_ascii=False), qos=MQTT_QOS)
        log.info(f"MQTT old pairing revocation published mid={info.mid} rc={info.rc}")
    except Exception as exc:
        log.warning(f"MQTT old pairing revocation skipped: {exc}")

    result = replace_peer_signal_bundle(bundle, remote_name="android")
    record_pairing_success(fingerprint=fingerprint, remote_name="android", remote_device_id=1)
    log.info(f"MQTT pairing claim accepted fingerprint={fingerprint[:16]} result={result}")

    ack_payload = {
        "type": "pairing_confirmed",
        "content": "SignalASI Desktop completed a new secure pairing.",
        "contact_id": "system",
        "desktop_id": desktop_id(),
        "desktop_name": desktop_name(),
        "desktop_fingerprint": get_signal_bundle().get("identityKeySha256", ""),
        "signal_bundle": get_signal_bundle(),
        "sender": "system",
        "connector_agents": mobile_connector_agents(),
        "delivery_trace": _desktop_trace(_trace_event("desktop_pairing_confirmed", fingerprint[:16])),
        "time": time.time(),
    }
    info = mqttc.publish(TOPIC_RECV, json.dumps(ack_payload, ensure_ascii=False), qos=MQTT_QOS)
    log.info(f"MQTT public pairing confirmation published mid={info.mid} rc={info.rc}")


def mobile_connector_agents() -> list[dict]:
    diagnostics = connector_diagnostics()
    agents = []
    did = desktop_id()
    dname = desktop_name()
    fingerprint = get_signal_bundle().get("identityKeySha256", "")
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
            "mqtt_topic": TOPIC_SEND,
            "updated_at": time.time(),
        })
    return agents


def _agent_id_from_contact(contact_id: str, explicit_agent_id: object = None) -> str:
    explicit = str(explicit_agent_id or "").strip()
    if explicit:
        return explicit
    value = str(contact_id or "hermes").strip()
    if value.startswith("desktop_") and ":" in value:
        return value.split(":", 1)[1] or "hermes"
    return value or "hermes"


def publish_connector_status(mqttc=None, reason: str = "status_update") -> dict:
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
        "connector_agents": mobile_connector_agents(),
        "delivery_trace": _desktop_trace(_trace_event("desktop_connector_status", reason)),
        "time": time.time(),
    }
    try:
        encrypted = encrypt_signal_payload(payload, remote_name="android")
        info = mqttc.publish(TOPIC_RECV, json.dumps(encrypted, ensure_ascii=False), qos=MQTT_QOS)
        log.info(f"MQTT connector status published mid={info.mid} rc={info.rc} reason={reason}")
        return api_ok("connector_status_published", reason=reason, mid=info.mid, rc=info.rc, params={"reason": reason, "mid": info.mid, "rc": info.rc})
    except Exception as exc:
        log.warning(f"MQTT connector status skipped: {exc}")
        return api_error("publish_failed", str(exc), reason=reason, params={"reason": reason})


def publish_pairing_revoked(mqttc=None, reason: str = "forgotten_by_desktop") -> dict:
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
        encrypted_revoke = encrypt_signal_payload(revoke_payload, remote_name="android")
        info = mqttc.publish(TOPIC_RECV, json.dumps(encrypted_revoke, ensure_ascii=False), qos=MQTT_QOS)
        ok = info.rc == mqtt.MQTT_ERR_SUCCESS
        log.info(f"MQTT pairing revocation published mid={info.mid} rc={info.rc} reason={reason}")
        if ok:
            return api_ok("pairing_revocation_published", reason=reason, mid=info.mid, rc=info.rc, params={"reason": reason, "mid": info.mid, "rc": info.rc})
        return api_error("publish_failed", f"MQTT publish failed rc={info.rc}", reason=reason, mid=info.mid, rc=info.rc, params={"reason": reason, "mid": info.mid, "rc": info.rc})
    except Exception as exc:
        log.warning(f"MQTT pairing revocation skipped: {exc}")
        return api_error("publish_failed", str(exc), reason=reason, params={"reason": reason})


def publish_mobile_test_message(contact_id: str, content: str) -> dict:
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
    encrypted = encrypt_signal_payload(payload, remote_name="android")
    info = client.publish(TOPIC_RECV, json.dumps(encrypted, ensure_ascii=False), qos=MQTT_QOS)
    if info.rc == mqtt.MQTT_ERR_SUCCESS:
        return api_ok("mobile_test_published", mid=info.mid, rc=info.rc, contact_id=contact_id, params={"contact_id": contact_id, "mid": info.mid, "rc": info.rc})
    return api_error("publish_failed", f"MQTT publish failed rc={info.rc}", mid=info.mid, rc=info.rc, contact_id=contact_id, params={"contact_id": contact_id, "mid": info.mid, "rc": info.rc})


def publish_agent_push_message(contact_id: str, content: str, source: str = "agent") -> dict:
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
    encrypted = encrypt_signal_payload(payload, remote_name="android")
    info = client.publish(TOPIC_RECV, json.dumps(encrypted, ensure_ascii=False), qos=MQTT_QOS)
    params = {"contact_id": cleaned_contact_id, "source": payload["source"], "mid": info.mid, "rc": info.rc}
    if info.rc == mqtt.MQTT_ERR_SUCCESS:
        return api_ok("agent_push_published", mid=info.mid, rc=info.rc, contact_id=cleaned_contact_id, source=payload["source"], params=params)
    return api_error("publish_failed", f"MQTT publish failed rc={info.rc}", mid=info.mid, rc=info.rc, contact_id=cleaned_contact_id, source=payload["source"], params=params)


def start():
    """Start the MQTT client; this blocks and should run in a background thread."""
    global client, running
    if running:
        return

    running = True
    client_id = f"signalasi-pc-{DEVICE_ID}"
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

    try:
        mqttc.connect(BROKER, PORT, keepalive=60)
        mqttc.loop_forever()
    except Exception as e:
        log.error(f"MQTT start failed: {e}")
        running = False


def start_background():
    """Start MQTT in a background thread."""
    t = threading.Thread(target=start, daemon=True)
    t.start()
    log.info("MQTT bridge started in background")


def stop():
    global client, running
    running = False
    if client:
        client.disconnect()
        client = None


if __name__ == "__main__":
    logging.basicConfig(level=logging.INFO)
    start()

