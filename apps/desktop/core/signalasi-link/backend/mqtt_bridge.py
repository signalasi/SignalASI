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
from typing import Callable

import paho.mqtt.client as mqtt

from api_response import api_error, api_ok
from agent_gateway import ask_agent_sync, connector_diagnostics
from agent_task_manager import agent_task_manager
from codex_app_server import CodexAppServer
from pairing_state import is_paired, pairing_status, record_pairing_success, validate_pairing_token
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
codex_app_server: CodexAppServer | None = None
codex_task_callbacks: dict[str, Callable[[str, dict], None]] = {}
codex_task_callbacks_lock = threading.Lock()
pending_delivery_acks: dict[int, dict] = {}
pending_delivery_acks_lock = threading.Lock()


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
        mqttc.subscribe(TOPIC_SEND, qos=MQTT_QOS)
        local_ip = get_lan_ip()
        pc_info = {"ip": local_ip, "port": 18765, "signal": get_signal_bundle()}
        mqttc.publish(TOPIC_PC, json.dumps(pc_info, ensure_ascii=False), qos=MQTT_QOS, retain=True)
        for recovered_task in agent_task_manager.drain_recovered():
            _publish_or_queue_task_event(mqttc, {"scheme": "signal", "from": "android"}, recovered_task, [])
        flush_pending_task_events(mqttc)
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


def _publish_phone_payload(mqttc, wire_payload: dict, reply_payload: dict) -> bool:
    with phone_publish_lock:
        if wire_payload.get("scheme") == "signal":
            encrypted_reply = encrypt_signal_payload(reply_payload, remote_name=wire_payload.get("from", "android"))
            info = mqttc.publish(TOPIC_RECV, json.dumps(encrypted_reply, ensure_ascii=False), qos=MQTT_QOS)
            track_delivery_ack(info.mid, reply_payload, "desktop_reply_broker_ack", TOPIC_RECV)
            log.info(f"MQTT encrypted reply published mid={info.mid} rc={info.rc}")
        else:
            info = mqttc.publish(TOPIC_RECV, json.dumps(reply_payload, ensure_ascii=False), qos=MQTT_QOS)
            track_delivery_ack(info.mid, reply_payload, "desktop_reply_broker_ack", TOPIC_RECV)
            log.info(f"MQTT plain reply published mid={info.mid} rc={info.rc}")
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
        reply = str(task.get("result") or "")
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
                _trace_event("desktop_reply_publish_queued", TOPIC_RECV),
            ),
            "sender": "other",
            "time": time.time(),
        }
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
        wire_payload = json.loads(msg.payload.decode("utf-8"))
        if wire_payload.get("type") == "signalasi_pairing_claim":
            handle_pairing_claim(mqttc, wire_payload)
            return
        if wire_payload.get("type") == "signal_bundle_request":
            handle_signal_bundle_request(mqttc, wire_payload)
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
                    _trace_event("desktop_transcript_publish_queued", TOPIC_RECV),
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
    if not validate_pairing_token(token):
        log.warning("MQTT pairing claim rejected: invalid token")
        return
    if not isinstance(bundle, dict):
        log.warning("MQTT pairing claim rejected: missing signal bundle")
        return

    previous_pairing = pairing_status()
    identity_changed = bool(
        previous_pairing.get("paired")
        and previous_pairing.get("identity_fingerprint")
        and previous_pairing.get("identity_fingerprint") != fingerprint
    )
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
    if identity_changed:
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


def handle_signal_bundle_request(mqttc, payload: dict):
    if not is_paired():
        log.warning("Signal bundle request rejected: no paired phone is trusted")
        return
    bundle = get_signal_bundle()
    fingerprint = str(bundle.get("identityKeySha256") or "")
    requested_fingerprint = str(payload.get("requested_fingerprint") or "").strip()
    if requested_fingerprint and requested_fingerprint != fingerprint:
        log.warning("Signal bundle request rejected: desktop fingerprint mismatch")
        return
    requester_fingerprint = str(payload.get("identity_fingerprint") or "").strip()
    requester_bundle = payload.get("signal_bundle")
    trusted_fingerprint = str(pairing_status().get("identity_fingerprint") or "").strip()
    if not requester_fingerprint or requester_fingerprint != trusted_fingerprint:
        log.warning(
            "Signal bundle request rejected: requester fingerprint mismatch "
            f"trusted={trusted_fingerprint[:16]} requester={requester_fingerprint[:16]}"
        )
        return
    if not isinstance(requester_bundle, dict):
        log.warning("Signal bundle request rejected: requester bundle missing")
        return
    replace_peer_signal_bundle(requester_bundle, remote_name="android")
    reply_topic = str(payload.get("reply_topic") or TOPIC_RECV).strip()
    if reply_topic != TOPIC_RECV and not reply_topic.startswith(f"signalasichat/{DEVICE_ID}/"):
        log.warning("Signal bundle request rejected: invalid reply topic")
        return
    requested_contact = str(payload.get("to") or "").strip()
    response = {
        "version": 1,
        "type": "signal_bundle_response",
        "from": requested_contact or desktop_id(),
        "to": str(payload.get("from") or ""),
        "desktop_id": desktop_id(),
        "desktop_name": desktop_name(),
        "desktop_fingerprint": fingerprint,
        "signal_bundle": bundle,
        "session_recovery": True,
        "time": time.time(),
    }
    info = mqttc.publish(reply_topic, json.dumps(response, ensure_ascii=False), qos=MQTT_QOS)
    log.info(f"Signal bundle response published mid={info.mid} rc={info.rc} to={reply_topic}")


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


def publish_agent_task_event(task: dict) -> bool:
    if not is_paired():
        return False
    return _publish_or_queue_task_event(client, {"scheme": "signal", "from": "android"}, task, [])


def start_agent_task(contact_id: str, prompt: str, source_message_id: str = "", task_id: str = "") -> dict:
    cleaned_contact_id = str(contact_id or "").strip()
    cleaned_prompt = str(prompt or "").strip()
    if not cleaned_contact_id:
        return api_error("contact_id_required")
    if not cleaned_prompt:
        return api_error("content_required", contact_id=cleaned_contact_id)
    agent_id = _agent_id_from_contact(cleaned_contact_id)

    def run_task(task) -> str:
        return ask_agent_sync(agent_id, cleaned_prompt, task_id=task.task_id)

    def publish_result(task: dict) -> None:
        publish_agent_push_message(
            cleaned_contact_id,
            str(task.get("result") or ""),
            source=f"agent-task:{task.get('task_id', '')}",
        )

    task = agent_task_manager.create(
        agent_id=agent_id,
        contact_id=cleaned_contact_id,
        source_message_id=str(source_message_id or ""),
        prompt=cleaned_prompt,
        runner=run_task,
        on_event=publish_agent_task_event,
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
    if client:
        client.disconnect()
        client = None


if __name__ == "__main__":
    logging.basicConfig(level=logging.INFO)
    start()

