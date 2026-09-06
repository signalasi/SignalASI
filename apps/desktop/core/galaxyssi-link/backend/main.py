"""GalaxySSI Link backend - FastAPI + WebSocket."""
import asyncio
import json
import logging
import os
import secrets
import shutil
import time
import uuid
from dataclasses import asdict
from typing import Any
from datetime import datetime, timezone
from contextlib import asynccontextmanager

from fastapi import FastAPI, WebSocket, WebSocketDisconnect, Depends, Query, HTTPException, Header, Request
from fastapi.responses import Response, StreamingResponse
from fastapi.middleware.cors import CORSMiddleware
from urllib.parse import quote
from sqlalchemy.orm import Session
from pydantic import BaseModel, Field

from models import init_db, get_session, Contact, Message, ContactType, MessageType, SenderType
from agent_gateway import (
    acp_runtime_manifest,
    agent_tool_manifest,
    ask_agent_sync,
    connector_diagnostics,
    connector_self_test,
    deliver_agent_sync,
    desktop_agent_provider,
    desktop_agent_runtime_server,
    external_cli_process_pool,
    external_cli_runtime_manifest,
    list_agents,
    prewarm_acp_agents,
    prewarm_external_cli_agents,
    provider_profile_catalog,
    recent_agent_execution_log,
    reset_inactive_agent_runtime,
    shutdown_acp_agent_runtime,
    shutdown_desktop_agent_runtime_server,
    shutdown_external_cli_process_pool,
)
from desktop_agent_adapters import (
    AgentAdapterRequest,
    AgentDeliveryMode,
    AgentInvocationMode,
    AgentRunPriority,
)
from agent_collaboration_channels import (
    AgentCollaborationAccessError,
    AgentCollaborationConflict,
    AgentCollaborationError,
    CollaborationScope,
    agent_collaboration_bus,
)
from agent_config import language_policy_config, load_config, save_config
from api_response import api_error
from agent_task_manager import TERMINAL_STATES, agent_task_manager
from backend_instance_lock import BackendInstanceLock
from desktop_native_tools import TOOL_VERSION as DESKTOP_NATIVE_TOOL_VERSION
from unified_commands import default_command_engine
from agent_performance_lab import current_agent_performance_report

logging.basicConfig(level=logging.INFO)
log = logging.getLogger("galaxyssi")

# WebSocket connection manager.
class ConnectionManager:
    def __init__(self):
        self.active: dict[str, list[WebSocket]] = {}

    async def connect(self, contact_id: str, ws: WebSocket):
        await ws.accept()
        self.active.setdefault(contact_id, []).append(ws)

    def disconnect(self, contact_id: str, ws: WebSocket):
        if contact_id in self.active:
            self.active[contact_id] = [w for w in self.active[contact_id] if w != ws]
            if not self.active[contact_id]:
                del self.active[contact_id]

    async def broadcast(self, contact_id: str, data: dict):
        if contact_id not in self.active:
            return
        msg = json.dumps(data, ensure_ascii=False, default=str)
        dead = []
        for ws in self.active[contact_id]:
            try:
                await ws.send_text(msg)
            except Exception:
                dead.append(ws)
        for ws in dead:
            self.disconnect(contact_id, ws)

manager = ConnectionManager()

# ── App ──
from pathlib import Path

def galaxyssi_pairing_payload(
    include_agents: bool = False,
    grant_desktop_executor: bool = False,
) -> dict:
    from pairing_state import new_pairing_session
    from pairing_access import grant_for_executor
    from galaxyssi_client import get_signal_verification_payload

    payload = get_signal_verification_payload()
    access_grant = grant_for_executor(grant_desktop_executor)
    pairing = new_pairing_session(access_grant)
    payload["pairing_token"] = pairing["token"]
    payload["pairing_secret"] = pairing["secret"]
    payload["pairing_access"] = pairing["access"]
    payload["pairing_created_at"] = pairing["created_at"]
    try:
        from mqtt_bridge import reconcile_mqtt_subscriptions

        reconcile_mqtt_subscriptions(force=True)
    except Exception:
        logging.getLogger(__name__).debug("Pairing rendezvous subscription will reconcile asynchronously")
    from desktop_control import desktop_control_manager

    control_manager = desktop_control_manager()
    if grant_desktop_executor and not control_manager.settings().get("enabled"):
        control_manager.update_settings(enabled=True)
    control_offer = (
        control_manager.create_offer(pairing["token"])
        if grant_desktop_executor else None
    )
    if control_offer is not None:
        payload["desktop_control_authorization"] = control_offer
    if include_agents:
        from mqtt_bridge import mobile_connector_agents
        payload["connector_agents"] = mobile_connector_agents()
    return payload


def compact_pairing_qr_payload(payload: dict) -> dict:
    """Keep the optical QR small; full device metadata arrives after pairing."""
    access = payload.get("pairing_access") or {}
    control_offer = payload.get("desktop_control_authorization") or {}
    compact = {
        "t": "o2",
        "n": payload.get("desktop_name") or "GalaxySSI Desktop",
        "k": payload["identity_key"],
        "h": payload["identity_key_sha256"],
        "c": payload["created_at"],
        "x": payload["pairing_token"],
        "e": payload["pairing_secret"],
        "a": 1 if access.get("profile") == "desktop_executor" else 0,
    }
    authorization_token = str(control_offer.get("token") or "")
    if authorization_token:
        compact["o"] = authorization_token
    return compact


def galaxyssi_pairing_qr(grant_desktop_executor: bool = False) -> dict:
    import base64
    import io
    import qrcode
    from mqtt_bridge import mobile_connector_agents

    payload = galaxyssi_pairing_payload(
        grant_desktop_executor=grant_desktop_executor,
    )
    qr_payload = compact_pairing_qr_payload(payload)
    encoded_payload = json.dumps(qr_payload, ensure_ascii=False, separators=(",", ":"))
    qr = qrcode.QRCode(error_correction=qrcode.constants.ERROR_CORRECT_M, border=4, box_size=10)
    qr.add_data(encoded_payload)
    qr.make(fit=True)
    image = qr.make_image(fill_color="black", back_color="white")
    buffer = io.BytesIO()
    image.save(buffer, format="PNG")
    encoded = base64.b64encode(buffer.getvalue()).decode("ascii")
    return {
        "image_data_url": f"data:image/png;base64,{encoded}",
        "fingerprint": payload["identity_key_sha256"][:16],
        "pairing_type": payload["type"],
        "agent_count": len(mobile_connector_agents()),
        "pairing_access": payload["pairing_access"],
        "desktop_device": payload.get("desktop_device") or {},
        "expires_at": int(time.time()) + 10 * 60,
        "created_at": payload["pairing_created_at"],
        "qr_payload_bytes": len(encoded_payload.encode("utf-8")),
        "qr_version": qr.version,
    }

@asynccontextmanager
async def lifespan(app: FastAPI):
    file_server_process = None
    proactive_runtime = None
    evolution_runtime = None
    memory_critic_runtime = None
    memory_telemetry_runtime = None
    memory_telemetry_subscription_id = ""
    reputation_subscription_id = ""
    runtime_server = None
    external_services_enabled = os.environ.get("GALAXYSSI_DISABLE_EXTERNAL_SERVICES") != "1"
    instance_lock = BackendInstanceLock() if external_services_enabled else None
    if instance_lock is not None:
        instance_lock.acquire()
    init_db()
    try:
        from desktop_memory_critic import desktop_memory_critic_runtime

        memory_critic_runtime = desktop_memory_critic_runtime()
        memory_critic_runtime.start()
        log.info("Desktop memory critic runtime started")
    except Exception as exc:
        log.warning("Desktop memory critic runtime start failed: %s", exc)
    try:
        runtime_server = desktop_agent_runtime_server()
        log.info(
            "Desktop Agent Runtime started (max concurrency=%s)",
            runtime_server.max_workers,
        )
    except Exception as exc:
        log.warning("Desktop Agent Runtime start failed: %s", exc)
    try:
        from agent_memory_telemetry import agent_memory_telemetry_runtime

        memory_telemetry_runtime = agent_memory_telemetry_runtime(
            lambda: agent_task_manager.list(limit=500)
        )
        memory_telemetry_runtime.start()
        memory_telemetry_subscription_id = agent_task_manager.subscribe(
            memory_telemetry_runtime.observe_task
        )
        log.info("Agent memory telemetry started")
    except Exception as exc:
        log.warning("Agent memory telemetry start failed: %s", exc)
    if external_services_enabled:
        try:
            prewarm = prewarm_external_cli_agents()
            log.info(
                "External CLI Runtime ready (prewarmed=%s)",
                prewarm.get("warmed", {}),
            )
        except Exception as exc:
            log.warning("External CLI Runtime start failed: %s", exc)
        try:
            prewarm = prewarm_acp_agents()
            log.info(
                "ACP Runtime ready (prewarmed=%s)",
                prewarm.get("warmed", {}),
            )
        except Exception as exc:
            log.warning("ACP Runtime start failed: %s", exc)
    if external_services_enabled:
        # Start the local Signal Protocol sidecar.
        signal_sidecar_ready = False
        try:
            import galaxyssi_client
            galaxyssi_client.start_signal_sidecar()
            signal_sidecar_ready = True
            log.info("Signal sidecar started (:%s)", galaxyssi_client.SIDECAR_PORT)
        except Exception as e:
            log.warning(f"Signal sidecar start failed: {e}")
        if signal_sidecar_ready:
            try:
                from agent_reputation_ledger import agent_reputation_ledger

                reputation_ledger = agent_reputation_ledger()

                def record_reputation(snapshot: dict) -> None:
                    if str(snapshot.get("status") or "") not in TERMINAL_STATES:
                        return
                    try:
                        reputation_ledger.record_task(snapshot)
                    except Exception as exc:
                        log.warning(
                            "Agent reputation receipt deferred task_id=%s: %s",
                            snapshot.get("task_id"),
                            exc,
                        )

                reputation_subscription_id = agent_task_manager.subscribe(record_reputation)
            except Exception as exc:
                log.warning("Agent reputation ledger unavailable: %s", exc)
        # Start the MQTT bridge in a background thread.
        try:
            from mqtt_bridge import start_background
            start_background()
            log.info("MQTT bridge started")
        except Exception as e:
            log.warning(f"MQTT start failed: {e}")
        try:
            from proactive_dispatcher import proactive_task_runtime

            proactive_runtime = proactive_task_runtime()
            proactive_runtime.start()
            log.info("Proactive task runtime started")
        except Exception as e:
            log.warning("Proactive task runtime start failed: %s", e)
        try:
            from evolution_v2.runtime import evolution_v2_runtime

            evolution_runtime = evolution_v2_runtime()
            evolution_runtime.start()
            log.info("Self-evolution V2 runtime started")
        except Exception as e:
            log.warning("Self-evolution V2 runtime start failed: %s", e)
        # Start the file service subprocess.
        try:
            import subprocess, sys
            file_server_script = Path(__file__).parent / "file_server.py"
            file_server_process = subprocess.Popen(
                [sys.executable, str(file_server_script)],
                stdout=subprocess.DEVNULL,
                stderr=subprocess.DEVNULL,
            )
            log.info("File service started (:18765)")
        except Exception as e:
            log.warning(f"File service start failed: {e}")
    else:
        log.info("External services disabled for isolated backend run")
    log.info("GalaxySSI Link backend started")
    try:
        yield
    finally:
        if memory_critic_runtime is not None:
            try:
                memory_critic_runtime.stop()
            except Exception as exc:
                log.warning("Desktop memory critic runtime shutdown failed: %s", exc)
        if memory_telemetry_subscription_id:
            agent_task_manager.unsubscribe(memory_telemetry_subscription_id)
        if memory_telemetry_runtime is not None:
            memory_telemetry_runtime.stop()
        if runtime_server is not None:
            shutdown_desktop_agent_runtime_server(wait=False)
        shutdown_acp_agent_runtime()
        shutdown_external_cli_process_pool()
        if reputation_subscription_id:
            agent_task_manager.unsubscribe(reputation_subscription_id)
        if evolution_runtime is not None:
            try:
                evolution_runtime.stop()
            except Exception as exc:
                log.warning("Self-evolution V2 runtime shutdown failed: %s", exc)
        if proactive_runtime is not None:
            try:
                proactive_runtime.stop()
            except Exception as exc:
                log.warning("Proactive task runtime shutdown failed: %s", exc)
        if external_services_enabled:
            try:
                from mqtt_bridge import stop
                stop()
            except Exception as exc:
                log.warning("MQTT shutdown failed: %s", exc)
        if file_server_process is not None and file_server_process.poll() is None:
            if os.name == "nt":
                subprocess.run(
                    ["taskkill", "/PID", str(file_server_process.pid), "/T", "/F"],
                    stdout=subprocess.DEVNULL,
                    stderr=subprocess.DEVNULL,
                    check=False,
                )
            else:
                file_server_process.terminate()
                try:
                    file_server_process.wait(timeout=5)
                except Exception:
                    file_server_process.kill()
        if external_services_enabled:
            try:
                from galaxyssi_client import stop_signal_sidecar
                stop_signal_sidecar()
            except Exception as exc:
                log.warning("Signal sidecar shutdown failed: %s", exc)
        if instance_lock is not None:
            instance_lock.release()

app = FastAPI(title="GalaxySSI Link", lifespan=lifespan)
from evolution_v2.api import router as evolution_v2_router
app.include_router(evolution_v2_router)
from blob_configuration_api import router as blob_configuration_router
app.include_router(blob_configuration_router)
app.add_middleware(
    CORSMiddleware,
    allow_origins=["http://127.0.0.1:8765", "http://localhost:8765", "null"],
    allow_methods=["GET", "POST", "DELETE"],
    allow_headers=[
        "Content-Type",
        "X-GalaxySSI-Token",
        "X-GalaxySSI-Timestamp",
        "X-GalaxySSI-Nonce",
        "X-GalaxySSI-Signature",
    ],
)

# ── API ──

@app.get("/api/contacts")
def list_contacts(db: Session = Depends(get_session)):
    contacts = db.query(Contact).order_by(Contact.created_at).all()
    return [{
        "id": c.id, "name": c.name, "avatar": c.avatar,
        "type": c.type.value, "status": c.status,
        "preview": c.preview, "unread": c.unread,
    } for c in contacts]

@app.get("/api/agents")
def api_list_agents():
    # The primary UI needs an immediate availability snapshot. Full version
    # probes remain available through the diagnostics endpoint.
    return list_agents(quick=True)

@app.get("/api/agents/diagnostics")
def api_agent_diagnostics():
    return connector_diagnostics()

@app.get("/api/agents/memory-telemetry")
def api_agent_memory_telemetry(request: Request):
    require_loopback(request)
    from agent_memory_telemetry import agent_memory_telemetry_runtime

    runtime = agent_memory_telemetry_runtime(
        lambda: agent_task_manager.list(limit=500)
    )
    runtime.request_capture()
    return runtime.snapshot()

@app.get("/api/link/transport-diagnostics")
def api_link_transport_diagnostics(request: Request):
    require_loopback(request)
    from link_transport_diagnostics import link_transport_diagnostics

    return link_transport_diagnostics().snapshot()


@app.get("/api/provider-profiles")
def api_provider_profiles():
    return provider_profile_catalog(quick=True)


@app.get("/health")
def api_health():
    try:
        from mqtt_bridge import mqtt_bridge_status

        message_bridge = mqtt_bridge_status()
    except Exception as exc:
        message_bridge = {
            "running": False,
            "connected": False,
            "supervised": False,
            "last_error": str(exc)[:500],
        }
    return {
        "status": "ok",
        "protocol": "GalaxySSI Link Protocol",
        "connector": "GalaxySSI Desktop",
        "message_bridge": message_bridge,
    }

@app.get("/api/agents/execution-log")
def api_agent_execution_log(limit: int = Query(50)):
    return recent_agent_execution_log(limit)


@app.get("/api/agents/performance-lab")
def api_agent_performance_lab(
    request: Request,
    window: str = Query("7d"),
):
    require_loopback(request)
    try:
        from agent_latency import tracer
        report = current_agent_performance_report(window)
        report["stage_latency"] = tracer().summary()
        return report
    except ValueError as exc:
        raise HTTPException(status_code=400, detail=str(exc)) from exc


@app.get("/api/agents/latency")
def api_agent_latency(request: Request, include_events: bool = Query(False)):
    require_loopback(request)
    from agent_latency import export_snapshot, tracer
    return export_snapshot() if include_events else tracer().summary()


@app.get("/api/agents/reputation")
def api_agent_reputation(agent_id: str = Query("")):
    from agent_reputation_ledger import agent_reputation_ledger

    ledger = agent_reputation_ledger()
    return {
        "integrity": ledger.integrity(),
        "agent": ledger.snapshot(agent_id) if agent_id else None,
    }


class UnifiedCommandReq(BaseModel):
    command_id: str = ""
    args: dict = {}
    raw: str = ""
    slash: str = ""
    source: str = "desktop"
    requested_by: str = "user"
    workspace: str = ""
    approve: bool = False


@app.get("/api/commands")
def api_list_unified_commands(root: str = Query("")):
    engine = default_command_engine()
    return {
        "catalog_size": len(engine.registry.list()),
        "roots": engine.registry.roots(),
        "commands": engine.registry.list(root.strip().lower()),
    }


@app.get("/api/commands/capabilities")
def api_unified_command_capabilities():
    result = default_command_engine().execute_payload({"command_id": "capabilities.list", "source": "desktop"})
    return result.public()


@app.get("/api/commands/runs")
def api_unified_command_runs(limit: int = Query(50)):
    return {"runs": default_command_engine().recent_runs(limit)}


@app.post("/api/commands/execute")
def api_execute_unified_command(req: UnifiedCommandReq, request: Request):
    require_loopback(request)
    payload = req.model_dump() if hasattr(req, "model_dump") else req.dict()
    result = default_command_engine().execute_payload(payload)
    return result.public()

@app.get("/api/pairing/status")
def api_pairing_status():
    from pairing_state import pairing_status
    return pairing_status()


class PairingClientRenameReq(BaseModel):
    client_route_id: str
    display_name: str


class PeerMessageReq(BaseModel):
    client_route_id: str
    content: str = ""
    attachments: list[str] = Field(default_factory=list)
    attachment_metadata: list[dict] = Field(default_factory=list)


@app.post("/api/pairing/rename")
def api_pairing_rename(req: PairingClientRenameReq, request: Request):
    require_loopback(request)
    from pairing_state import rename_client

    try:
        return {"ok": True, "client": rename_client(req.client_route_id, req.display_name)}
    except KeyError as exc:
        raise HTTPException(status_code=404, detail=str(exc)) from exc
    except ValueError as exc:
        raise HTTPException(status_code=400, detail=str(exc)) from exc

LOOPBACK_HOSTS = {"127.0.0.1", "::1", "localhost", "testclient"}


def _is_loopback_host(host: str) -> bool:
    return str(host or "").strip().lower() in LOOPBACK_HOSTS


def _desktop_task_stream_token() -> str:
    configured = str(os.environ.get("GALAXYSSI_DESKTOP_TASK_STREAM_TOKEN") or "").strip()
    if configured:
        return configured
    try:
        from pairing_state import DATA_DIR

        return (Path(DATA_DIR) / "desktop_task_stream_token").read_text(encoding="utf-8").strip()
    except (FileNotFoundError, OSError):
        return ""


def _desktop_task_stream_authorized(ws: WebSocket) -> bool:
    expected = _desktop_task_stream_token()
    offered = [
        value.strip()
        for value in str(ws.headers.get("sec-websocket-protocol") or "").split(",")
        if value.strip()
    ]
    return (
        bool(expected)
        and "galaxyssi-task-stream" in offered
        and any(secrets.compare_digest(value, expected) for value in offered)
    )


def require_loopback(request: Request) -> None:
    host = str(request.client.host if request.client else "")
    if not _is_loopback_host(host):
        raise HTTPException(status_code=403, detail="Pairing payload is available only on the local Desktop")


def require_desktop_api_token(request: Request) -> None:
    require_loopback(request)
    expected = _desktop_task_stream_token()
    offered = str(request.headers.get("x-galaxyssi-token") or "").strip()
    if not expected or not offered or not secrets.compare_digest(offered, expected):
        raise HTTPException(
            status_code=401,
            detail=api_error("desktop_api_unauthorized", "Desktop API token is invalid"),
        )


class DesktopSpeechSynthesisReq(BaseModel):
    text: str = Field(min_length=1, max_length=20_000)
    language: str = Field(default="zh-CN", max_length=16)


@app.post("/api/tts/synthesize")
async def api_synthesize_speech(req: DesktopSpeechSynthesisReq, request: Request):
    require_desktop_api_token(request)
    from edge_tts_service import synthesize_edge_speech

    try:
        speech = await asyncio.wait_for(
            synthesize_edge_speech(req.text, req.language),
            timeout=60,
        )
    except ValueError as exc:
        raise HTTPException(status_code=400, detail=api_error("tts_request_invalid", str(exc))) from exc
    except Exception as exc:
        log.warning("Microsoft Edge TTS synthesis failed: %s", exc)
        raise HTTPException(
            status_code=502,
            detail=api_error("edge_tts_synthesis_failed", str(exc)[:240]),
        ) from exc
    return Response(
        content=speech.audio,
        media_type=speech.media_type,
        headers={
            "Cache-Control": "no-store",
            "Content-Length": str(len(speech.audio)),
            "X-GalaxySSI-TTS-Voice": speech.voice,
        },
    )


@app.get("/api/peer/messages")
def api_peer_messages(
    request: Request,
    client_route_id: str = Query(""),
    limit: int = Query(500),
):
    require_desktop_api_token(request)
    from peer_chat_store import peer_chat_store

    return {
        "messages": peer_chat_store().list_messages(client_route_id, limit),
    }


@app.post("/api/peer/messages")
def api_send_peer_message(req: PeerMessageReq, request: Request):
    require_desktop_api_token(request)
    from mqtt_bridge import publish_peer_message

    return publish_peer_message(
        req.client_route_id,
        req.content,
        req.attachments,
        req.attachment_metadata,
    )


@app.delete("/api/peer/conversations/{client_route_id}")
def api_delete_peer_conversation(client_route_id: str, request: Request):
    require_desktop_api_token(request)
    from peer_chat_store import peer_chat_store

    deleted = peer_chat_store().delete_route(client_route_id)
    return {"client_route_id": client_route_id, "deleted_messages": deleted}


@app.get("/api/peer/messages/{message_id}/attachments/{attachment_index}")
def api_peer_attachment(
    message_id: str,
    attachment_index: int,
    request: Request,
):
    require_desktop_api_token(request)
    from peer_chat_store import peer_chat_store

    store = peer_chat_store()
    attachment = store.attachment_record(message_id, attachment_index)
    if attachment is None:
        raise HTTPException(status_code=404, detail=api_error("peer_attachment_not_found"))
    name = str(attachment.get("name") or f"attachment-{attachment_index}")
    return StreamingResponse(
        store.stream_attachment(message_id, attachment_index),
        media_type=str(attachment.get("mime_type") or "application/octet-stream"),
        headers={
            "Content-Disposition": f"attachment; filename*=UTF-8''{quote(name)}",
            "Content-Length": str(int(attachment.get("size_bytes") or 0)),
            "Cache-Control": "no-store",
        },
    )


@app.get("/api/pairing/payload")
def api_pairing_payload(
    request: Request,
    desktop_executor: bool = Query(False),
):
    require_loopback(request)
    return galaxyssi_pairing_payload(
        grant_desktop_executor=desktop_executor,
    )


@app.get("/api/pairing/qr")
def api_pairing_qr(
    request: Request,
    desktop_executor: bool = Query(False),
):
    require_loopback(request)
    return galaxyssi_pairing_qr(
        grant_desktop_executor=desktop_executor,
    )

@app.post("/api/pairing/clear")
def api_pairing_clear(request: Request, client_route_id: str = Query("")):
    require_desktop_api_token(request)
    from pairing_state import clear_pairing_state, get_client, list_clients, pairing_status
    from mqtt_bridge import (
        forget_paired_client_transport,
        publish_pairing_revoked,
        reconcile_mqtt_subscriptions,
    )
    from galaxyssi_client import remove_peer_signal_session
    from desktop_control import desktop_control_manager
    targets = [get_client(client_route_id)] if client_route_id else list_clients()
    targets = [target for target in targets if target]
    revoke = publish_pairing_revoked(reason="forgotten_by_desktop", client_route_id=client_route_id)
    removed_sessions = []
    transport_cleanup = {}
    for target in targets:
        desktop_control_manager().revoke_for_client(
            target["client_route_id"], "pairing_revoked"
        )
        try:
            remove_peer_signal_session(target["signal_name"], int(target.get("signal_device_id") or 1))
            removed_sessions.append(target["client_route_id"])
        except Exception as exc:
            log.warning("Signal session removal failed client=%s: %s", target["client_route_id"], exc)
        transport_cleanup[target["client_route_id"]] = forget_paired_client_transport(
            target["client_route_id"]
        )
    clear_pairing_state(client_route_id)
    subscription_reconciliation = reconcile_mqtt_subscriptions()
    status = pairing_status()
    status["revoke"] = revoke
    status["removed_sessions"] = removed_sessions
    status["transport_cleanup"] = transport_cleanup
    status["subscription_reconciliation"] = subscription_reconciliation
    status["forgotten_client_route_ids"] = [target["client_route_id"] for target in targets]
    return status

class AgentSelfTestReq(BaseModel):
    include_agent_calls: bool = False
    include_mobile_delivery: bool = True

@app.post("/api/agents/self-test")
def api_agent_self_test(req: AgentSelfTestReq):
    return connector_self_test(req.include_agent_calls, req.include_mobile_delivery)

@app.get("/api/agents/config")
def api_get_agent_config():
    return load_config(mask_secrets=True)

class AgentConfigReq(BaseModel):
    commands: dict[str, str] = {}
    local_model: dict[str, Any] = {}
    cloud_model: dict[str, Any] = {}
    language_policy: dict[str, str] = {}
    custom_agent: dict[str, str] = {}
    custom_agents: list[dict[str, Any]] = []
    cli_runtime: dict[str, Any] = {}
    acp_runtime: dict[str, Any] = {}

@app.post("/api/agents/config")
def api_save_agent_config(req: AgentConfigReq):
    saved = save_config(req.dict())
    reset_inactive_agent_runtime()
    shutdown_acp_agent_runtime()
    shutdown_external_cli_process_pool()
    saved["cli_runtime_status"] = prewarm_external_cli_agents()
    saved["acp_runtime_status"] = prewarm_acp_agents()
    try:
        from mqtt_bridge import publish_connector_status

        saved["mobile_status"] = publish_connector_status(reason="agent_config_saved")
    except Exception as exc:
        saved["mobile_status"] = api_error("mobile_status_publish_failed", str(exc), params={"reason": "agent_config_saved"})
    return saved

@app.post("/api/agents/sync-mobile-status")
def api_sync_mobile_agent_status():
    from mqtt_bridge import publish_connector_status

    return publish_connector_status(reason="manual_desktop_sync")

class AgentTestReq(BaseModel):
    prompt: str = "hello"

@app.post("/api/agents/{agent_id}/test")
def api_test_agent(agent_id: str, req: AgentTestReq):
    try:
        try:
            reply = ask_agent_sync(agent_id, req.prompt)
            return {"agent_id": agent_id, "reply": reply}
        except Exception as exc:
            log.exception("Agent test failed agent_id=%s", agent_id)
            raise HTTPException(
                status_code=502,
                detail=api_error(
                    "agent_test_failed",
                    str(exc)[:240],
                    params={"agent_id": agent_id},
                ),
            ) from exc
    finally:
        try:
            from mqtt_bridge import publish_connector_status

            publish_connector_status(reason=f"agent_test_{agent_id}")
        except Exception:
            pass


class AgentDeliveryReq(BaseModel):
    prompt: str
    task_id: str = ""
    delivery_mode: str = "respond"
    invocation_mode: str = "direct"
    caller_agent_id: str = ""
    parent_run_id: str = ""
    handoff_chain: list[str] = Field(default_factory=list)
    conversation_id: str = ""
    source_message_id: str = ""
    return_path: str = ""
    protocol: str = "1.0"
    required_features: list[str] = []
    response_language: str = ""
    client_route_id: str = ""
    turn_id: str = ""


class AgentRuntimeSubmitReq(BaseModel):
    agent_id: str
    prompt: str
    run_id: str = ""
    idempotency_key: str = ""
    delivery_mode: str = "respond"
    invocation_mode: str = "direct"
    caller_agent_id: str = ""
    parent_run_id: str = ""
    handoff_chain: list[str] = Field(default_factory=list)
    conversation_id: str = ""
    client_route_id: str = ""
    task_id: str = ""
    turn_id: str = ""
    source_message_id: str = ""
    return_path: str = ""
    protocol: str = "1.0"
    required_features: list[str] = Field(default_factory=list)
    response_language: str = ""
    priority: str = "foreground"
    desktop_access_profile: str = "restricted"
    collaboration_channel_ids: list[str] = Field(default_factory=list)
    collaboration_actor_id: str = ""
    repository_id: str = ""


class AgentToolInvokeReq(BaseModel):
    prompt: str
    invocation_mode: str = "tool"
    caller_agent_id: str
    parent_run_id: str
    handoff_chain: list[str] = Field(default_factory=list)
    run_id: str = ""
    conversation_id: str = ""
    client_route_id: str = ""
    task_id: str = ""
    turn_id: str = ""
    source_message_id: str = ""
    return_path: str = ""
    response_language: str = ""
    desktop_access_profile: str = "restricted"


class AgentCollaborationChannelReq(BaseModel):
    kind: str
    creator_agent_id: str
    participant_agent_ids: list[str] = Field(default_factory=list)
    client_route_id: str
    conversation_id: str
    task_id: str
    repository_root: str = ""
    repository_id: str = ""


class AgentCollaborationMessageReq(BaseModel):
    sender_agent_id: str
    content: str
    message_id: str = ""
    metadata: dict = Field(default_factory=dict)


class AgentCollaborationAckReq(BaseModel):
    agent_id: str
    through_sequence: int


class AgentFileAccessReq(BaseModel):
    access_kind: str
    agent_id: str
    path: str
    sha256: str = ""
    exists: bool = True
    size_bytes: int = 0
    event_id: str = ""
    client_route_id: str
    conversation_id: str
    task_id: str
    repository_id: str = ""
    workspace_id: str = ""
    collaboration_channel_ids: list[str] = Field(default_factory=list)


class AgentFileConflictResolveReq(BaseModel):
    agent_id: str
    reason: str = "reviewed"
    client_route_id: str
    conversation_id: str
    task_id: str
    repository_id: str = ""
    workspace_id: str = ""


class DesktopNativeToolInvokeReq(BaseModel):
    tool_id: str
    tool_version: str = DESKTOP_NATIVE_TOOL_VERSION
    arguments: dict = {}
    invocation_id: str = ""
    task_id: str = ""
    conversation_id: str = ""
    workspace_id: str = ""
    agent_id: str = ""
    client_route_id: str = ""
    repository_id: str = ""
    collaboration_task_id: str = ""
    collaboration_channel_ids: list[str] = Field(default_factory=list)
    idempotency_key: str = ""
    confirmation: dict | None = None


class DesktopMemoryReq(BaseModel):
    content: str
    kind: str = "fact"
    importance: float = 0.6
    namespace: str = ""
    valid_until_at: int = 0


class DesktopSkillReq(BaseModel):
    id: str
    name: str
    description: str = ""
    triggers: list[str] = Field(default_factory=list)
    instructions: str
    enabled: bool = True


class DesktopSkillEnabledReq(BaseModel):
    enabled: bool = True


class ToolMarketplaceInstallReq(BaseModel):
    configuration: dict[str, Any] = Field(default_factory=dict)
    approved_permissions: list[str] = Field(default_factory=list)


class DesktopMcpReq(BaseModel):
    id: str
    name: str
    transport: str = "local_stdio"
    command: str = ""
    command_argv: list[str] = Field(default_factory=list)
    environment_env: dict[str, str] = Field(default_factory=dict)
    endpoint: str = ""
    working_directory: str = ""
    header_env: dict[str, str] = Field(default_factory=dict)
    header_templates: dict[str, str] = Field(default_factory=dict)
    protocol_version: str = "2025-11-25"
    stdio_framing: str = "newline"
    allow_insecure_http: bool = False
    default_tool: str = ""
    triggers: list[str] = Field(default_factory=list)
    enabled: bool = True
    auto_invoke: bool = False
    permission_mode: str = "ask_for_changes"
    timeout_seconds: int = 20
    import_source: str = ""


class DesktopMcpImportPreviewReq(BaseModel):
    content: str = Field(min_length=1, max_length=1_048_576)
    file_name: str = Field(default="", max_length=260)
    base_directory: str = Field(default="", max_length=1_000)
    source_hint: str = Field(default="auto", max_length=32)


class DesktopMcpImportCommitReq(DesktopMcpImportPreviewReq):
    digest: str = Field(min_length=64, max_length=64)
    selected_ids: list[str] = Field(default_factory=list, max_length=128)


class ToolHandleOpenReq(BaseModel):
    owner_id: str = Field(default="galaxyssi.desktop.loopback", min_length=1, max_length=240)
    context_id: str = Field(default="", max_length=240)
    parent_run_id: str = Field(default="", max_length=240)
    ttl_seconds: int = Field(default=3_600, ge=1, le=2_592_000)


class DesktopMcpHandleInvokeReq(BaseModel):
    prompt: str = Field(min_length=1, max_length=262_144)
    owner_id: str = Field(default="galaxyssi.desktop.loopback", min_length=1, max_length=240)
    context_id: str = Field(default="", max_length=240)
    explicit_user_selection: bool = False
    task_id: str = Field(default="", max_length=240)


class DesktopControlSettingsReq(BaseModel):
    enabled: bool | None = None
    require_unlocked: bool | None = None


class EvolutionTaskCreateReq(BaseModel):
    problem: str
    scope: list[str] = Field(default_factory=list)
    acceptance: list[str] = Field(default_factory=list)
    reproduction_steps: list[str] = Field(default_factory=list)
    risk_level: str = "medium"
    max_attempts: int = 3
    agent_id: str = "auto"
    start: bool = True


class EvolutionCandidatePublishReq(BaseModel):
    approval_hash: str
    base_branch: str = "main"


class ProactiveTaskCreateReq(BaseModel):
    name: str
    trigger: dict[str, Any]
    action: dict[str, Any]
    policy: dict[str, Any] = Field(default_factory=dict)
    enabled: bool = True
    task_id: str = ""


class ProactiveTaskUpdateReq(BaseModel):
    name: str | None = None
    trigger: dict[str, Any] | None = None
    action: dict[str, Any] | None = None
    policy: dict[str, Any] | None = None
    enabled: bool | None = None


class ProactiveTaskTriggerReq(BaseModel):
    cause: dict[str, Any] = Field(default_factory=lambda: {"type": "manual"})


def _desktop_evolution_manager():
    from evolution_manager import (
        default_evolution_patch_agent,
        evolution_manager,
    )

    def publish_event(event: dict[str, Any]) -> None:
        try:
            from mqtt_bridge import publish_evolution_task_event_all

            publish_evolution_task_event_all(event)
        except (ImportError, AttributeError):
            pass
        except Exception as exc:
            log.warning("Evolution event publish deferred: %s", exc)

    return evolution_manager(
        patch_agent=default_evolution_patch_agent,
        event_sink=publish_event,
    )


def _desktop_evolution_timeline_item(
    evolution_manager,
    task,
    *,
    live_event: dict[str, Any] | None = None,
    audit_rows: list[dict[str, Any]] | None = None,
) -> dict[str, Any] | None:
    from evolution_task_timeline import evolution_task_timeline_item

    return evolution_task_timeline_item(
        evolution_manager,
        task,
        live_event=live_event,
        audit_rows=audit_rows,
    )


def _desktop_task_rows(limit: int, evolution_manager=None) -> list[dict[str, Any]]:
    bounded_limit = max(1, min(int(limit), 500))
    manual_tasks = [
        item
        for item in agent_task_manager.list(
            limit=max(100, bounded_limit),
            include_prompt=True,
        )
        if str(item.get("source_message_id") or "").startswith("desktop:")
    ]
    manager = evolution_manager or _desktop_evolution_manager()
    evolution_source = manager.store.list(limit=min(bounded_limit, 100))
    try:
        audit_by_task = manager.audit.list_for_tasks(
            [str(task.task_id) for task in evolution_source],
            limit_per_task=100,
        )
    except (AttributeError, OSError, RuntimeError, TypeError, ValueError):
        audit_by_task = {}
    evolution_tasks = [
        item
        for task in evolution_source
        if (
            item := _desktop_evolution_timeline_item(
                manager,
                task,
                audit_rows=audit_by_task.get(str(task.task_id)),
            )
        ) is not None
    ]
    return sorted(
        [*manual_tasks, *evolution_tasks],
        key=lambda item: int(item.get("updated_at") or 0),
        reverse=True,
    )[:bounded_limit]


def _evolution_http_error(exc: Exception) -> HTTPException:
    from evolution_manager import EvolutionError

    if not isinstance(exc, EvolutionError):
        return HTTPException(status_code=500, detail=api_error("evolution_failed", str(exc)[:500]))
    status = 404 if exc.code == "task_not_found" else 409 if exc.code in {
        "candidate_already_ready",
        "candidate_not_ready",
        "quality_gate_incomplete",
    } else 400
    return HTTPException(status_code=status, detail=api_error(exc.code, str(exc)))


def _proactive_http_error(exc: Exception) -> HTTPException:
    from proactive_tasks import ProactiveTaskError

    if not isinstance(exc, ProactiveTaskError):
        return HTTPException(
            status_code=500,
            detail=api_error("proactive_task_failed", str(exc)[:500]),
        )
    if exc.code in {"task_not_found"}:
        status = 404
    elif exc.code in {
        "duplicate_run",
        "task_disabled",
        "webhook_replay",
        "webhook_filter_mismatch",
    }:
        status = 409
    elif exc.code in {
        "webhook_signature_invalid",
        "webhook_expired",
        "webhook_nonce_invalid",
        "webhook_timestamp_invalid",
    }:
        status = 401
    elif exc.code == "webhook_too_large":
        status = 413
    else:
        status = 400
    return HTTPException(status_code=status, detail=api_error(exc.code, str(exc)))


@app.get("/api/proactive/tasks")
def api_list_proactive_tasks(request: Request, limit: int = Query(200)):
    require_desktop_api_token(request)
    from proactive_dispatcher import proactive_task_runtime

    return {
        "protocol": "galaxyssi.proactive-task.v1",
        "tasks": [
            task.public()
            for task in proactive_task_runtime().store.tasks(limit=limit)
        ],
    }


@app.post("/api/proactive/tasks")
def api_create_proactive_task(req: ProactiveTaskCreateReq, request: Request):
    require_desktop_api_token(request)
    from proactive_dispatcher import proactive_task_runtime

    try:
        return proactive_task_runtime().create(
            name=req.name,
            trigger=req.trigger,
            action=req.action,
            policy=req.policy,
            enabled=req.enabled,
            task_id=req.task_id,
        )
    except Exception as exc:
        raise _proactive_http_error(exc) from exc


@app.get("/api/proactive/tasks/{task_id}")
def api_get_proactive_task(task_id: str, request: Request):
    require_desktop_api_token(request)
    from proactive_dispatcher import proactive_task_runtime

    try:
        task = proactive_task_runtime().require_task(task_id)
        return task.public()
    except Exception as exc:
        raise _proactive_http_error(exc) from exc


@app.post("/api/proactive/tasks/{task_id}")
def api_update_proactive_task(
    task_id: str,
    req: ProactiveTaskUpdateReq,
    request: Request,
):
    require_desktop_api_token(request)
    from proactive_dispatcher import proactive_task_runtime

    try:
        return proactive_task_runtime().update(
            task_id,
            name=req.name,
            trigger=req.trigger,
            action=req.action,
            policy=req.policy,
            enabled=req.enabled,
        )
    except Exception as exc:
        raise _proactive_http_error(exc) from exc


@app.delete("/api/proactive/tasks/{task_id}")
def api_delete_proactive_task(task_id: str, request: Request):
    require_desktop_api_token(request)
    from proactive_dispatcher import proactive_task_runtime

    return {"task_id": task_id, "deleted": proactive_task_runtime().delete(task_id)}


@app.post("/api/proactive/tasks/{task_id}/trigger")
def api_trigger_proactive_task(
    task_id: str,
    req: ProactiveTaskTriggerReq,
    request: Request,
):
    require_desktop_api_token(request)
    from proactive_dispatcher import proactive_task_runtime

    try:
        return proactive_task_runtime().trigger_now(task_id, cause=req.cause).public()
    except Exception as exc:
        raise _proactive_http_error(exc) from exc


@app.post("/api/proactive/tasks/{task_id}/rotate-webhook")
def api_rotate_proactive_webhook(task_id: str, request: Request):
    require_desktop_api_token(request)
    from proactive_dispatcher import proactive_task_runtime
    from proactive_tasks import ProactiveTaskError

    runtime = proactive_task_runtime()
    try:
        task = runtime.require_task(task_id)
        if task.trigger.kind != "webhook":
            raise ProactiveTaskError(
                "webhook_unavailable",
                "Only webhook tasks have signing credentials",
            )
        trigger = asdict(task.trigger)
        trigger["webhook_id"] = secrets.token_hex(12)
        updated = runtime.update(task_id, trigger=trigger)
        updated["webhook_secret"] = runtime.webhook_secret(task_id)
        return updated
    except Exception as exc:
        raise _proactive_http_error(exc) from exc


@app.get("/api/proactive/runs")
def api_list_proactive_runs(
    request: Request,
    task_id: str = Query(""),
    limit: int = Query(100),
):
    require_desktop_api_token(request)
    from proactive_dispatcher import proactive_task_runtime

    runtime = proactive_task_runtime()
    return {
        "runs": [
            run.public()
            for run in runtime.store.runs(task_id=task_id, limit=limit)
        ]
    }


@app.get("/api/proactive/runs/{run_id}")
def api_get_proactive_run(
    run_id: str,
    request: Request,
    after_cursor: int = Query(0),
):
    require_desktop_api_token(request)
    from proactive_dispatcher import proactive_task_runtime

    runtime = proactive_task_runtime()
    run = runtime.store.run(run_id)
    if run is None:
        raise HTTPException(status_code=404, detail=api_error("proactive_run_not_found"))
    return {
        "run": run.public(),
        "events": runtime.store.events(run_id, after=max(0, after_cursor)),
    }


@app.post("/api/proactive/runs/{run_id}/cancel")
def api_cancel_proactive_run(run_id: str, request: Request):
    require_desktop_api_token(request)
    from proactive_dispatcher import proactive_task_runtime

    return {"run_id": run_id, "cancelled": proactive_task_runtime().cancel_run(run_id)}


@app.post("/api/proactive/webhooks/{task_id}")
async def api_receive_proactive_webhook(
    task_id: str,
    request: Request,
    x_galaxyssi_timestamp: str = Header(default=""),
    x_galaxyssi_nonce: str = Header(default=""),
    x_galaxyssi_signature: str = Header(default=""),
):
    from proactive_dispatcher import proactive_task_runtime

    body = await request.body()
    try:
        run = proactive_task_runtime().handle_webhook(
            task_id,
            body,
            timestamp=x_galaxyssi_timestamp,
            nonce=x_galaxyssi_nonce,
            signature=x_galaxyssi_signature,
        )
        return {
            "accepted": True,
            "run_id": run.run_id,
            "status": run.status,
        }
    except Exception as exc:
        raise _proactive_http_error(exc) from exc


@app.get("/api/evolution/tasks")
def api_list_evolution_tasks(request: Request, limit: int = Query(100)):
    require_loopback(request)
    manager = _desktop_evolution_manager()
    return {
        "tasks": [task.public() for task in manager.store.list(limit=limit)],
        "health": manager.health(limit=500).public(),
    }


@app.post("/api/evolution/tasks")
def api_create_evolution_task(req: EvolutionTaskCreateReq, request: Request):
    require_loopback(request)
    try:
        manager = _desktop_evolution_manager()
        task = manager.create(
            problem=req.problem,
            scope=req.scope,
            acceptance=req.acceptance,
            reproduction_steps=req.reproduction_steps,
            risk_level=req.risk_level,
            max_attempts=req.max_attempts,
            agent_id=req.agent_id,
        )
        if req.start:
            task = manager.start(task.task_id)
        return task.public()
    except Exception as exc:
        raise _evolution_http_error(exc) from exc


@app.get("/api/evolution/tasks/{task_id}")
def api_get_evolution_task(task_id: str, request: Request):
    require_loopback(request)
    try:
        return _desktop_evolution_manager().require(task_id).public()
    except Exception as exc:
        raise _evolution_http_error(exc) from exc


@app.post("/api/evolution/tasks/{task_id}/start")
def api_start_evolution_task(task_id: str, request: Request):
    require_loopback(request)
    try:
        return _desktop_evolution_manager().start(task_id).public()
    except Exception as exc:
        raise _evolution_http_error(exc) from exc


@app.post("/api/evolution/tasks/{task_id}/cancel")
def api_cancel_evolution_task(task_id: str, request: Request):
    require_loopback(request)
    try:
        return _desktop_evolution_manager().cancel(task_id).public()
    except Exception as exc:
        raise _evolution_http_error(exc) from exc


@app.post("/api/evolution/tasks/{task_id}/rollback")
def api_rollback_evolution_task(task_id: str, request: Request):
    require_loopback(request)
    try:
        return _desktop_evolution_manager().discard(task_id).public()
    except Exception as exc:
        raise _evolution_http_error(exc) from exc


@app.post("/api/evolution/tasks/{task_id}/publish")
def api_publish_evolution_candidate(
    task_id: str,
    req: EvolutionCandidatePublishReq,
    request: Request,
):
    require_loopback(request)
    try:
        return _desktop_evolution_manager().publish(
            task_id,
            req.approval_hash,
            base_branch=req.base_branch,
        ).public()
    except Exception as exc:
        raise _evolution_http_error(exc) from exc


@app.get("/api/agent-adapters")
def api_agent_adapters(request: Request):
    require_loopback(request)
    provider = desktop_agent_provider()
    return {
        "agents": provider.enumerate(),
        "recoverable_runs": [item.public() for item in provider.recover()],
    }


@app.get("/api/agent-runtime")
def api_agent_runtime(request: Request):
    require_loopback(request)
    return {
        **desktop_agent_runtime_server().health(),
        "collaboration": agent_collaboration_bus().health(),
        "file_access": {
            "enabled": False,
            "status": "disabled",
            "reason": "file_tree_audit_removed_from_task_runtime",
        },
        "acp_runtime": acp_runtime_manifest(),
        "external_cli_runtime": external_cli_runtime_manifest(),
    }


@app.get("/api/acp-runtime")
def api_acp_runtime(request: Request):
    require_loopback(request)
    return acp_runtime_manifest()


@app.post("/api/acp-runtime/{agent_id}/prewarm")
def api_prewarm_acp_runtime(agent_id: str, request: Request):
    require_loopback(request)
    from acp_runtime import acp_runtime

    return acp_runtime().prewarm(agent_id)


@app.post("/api/acp-runtime/{agent_id}/restart")
def api_restart_acp_runtime(agent_id: str, request: Request):
    require_loopback(request)
    from acp_runtime import acp_runtime

    return acp_runtime().restart(agent_id)


@app.get("/api/agent-runtime/cli-pool")
def api_agent_runtime_cli_pool(request: Request):
    require_loopback(request)
    return external_cli_runtime_manifest()


@app.post("/api/agent-runtime/cli-pool/prewarm")
def api_prewarm_agent_runtime_cli_pool(request: Request):
    require_loopback(request)
    return {
        **prewarm_external_cli_agents(),
        "runtime": external_cli_runtime_manifest(),
    }


@app.post("/api/agent-runtime/cli-pool/reap")
def api_reap_agent_runtime_cli_pool(request: Request):
    require_loopback(request)
    released = external_cli_process_pool().reap_idle()
    return {
        "released": released,
        "runtime": external_cli_runtime_manifest(),
    }


@app.get("/api/agent-runtime/sessions")
def api_agent_runtime_sessions(
    request: Request,
    agent_id: str = Query(""),
    client_route_id: str = Query(""),
    conversation_id: str = Query(""),
    state: str = Query(""),
    limit: int = Query(100),
):
    require_loopback(request)
    try:
        return {
            "sessions": desktop_agent_runtime_server().sessions(
                agent_id=agent_id,
                client_route_id=client_route_id,
                conversation_id=conversation_id,
                state=state,
                limit=limit,
            ),
        }
    except Exception as exc:
        raise HTTPException(
            status_code=400,
            detail=api_error("agent_runtime_session_query_failed", str(exc)[:240]),
        ) from exc


@app.get("/api/agent-runtime/sessions/{session_id}")
def api_agent_runtime_session(session_id: str, request: Request):
    require_loopback(request)
    runtime = desktop_agent_runtime_server()
    session = runtime.session(session_id)
    if session is None:
        raise HTTPException(
            status_code=404,
            detail=api_error("agent_runtime_session_not_found"),
        )
    return {
        "session": session,
        "runs": runtime.runs(session_id=session_id, limit=100),
    }


@app.get("/api/agent-runtime/runs")
def api_agent_runtime_runs(
    request: Request,
    state: str = Query(""),
    agent_id: str = Query(""),
    session_id: str = Query(""),
    invocation_mode: str = Query(""),
    caller_agent_id: str = Query(""),
    parent_run_id: str = Query(""),
    limit: int = Query(100),
):
    require_loopback(request)
    return {
        "runs": desktop_agent_runtime_server().runs(
            state=state,
            agent_id=agent_id,
            session_id=session_id,
            invocation_mode=invocation_mode,
            caller_agent_id=caller_agent_id,
            parent_run_id=parent_run_id,
            limit=limit,
        ),
    }


@app.post("/api/agent-runtime/runs")
def api_submit_agent_runtime_run(req: AgentRuntimeSubmitReq, request: Request):
    if req.collaboration_channel_ids:
        require_desktop_api_token(request)
    else:
        require_loopback(request)
    try:
        run = desktop_agent_runtime_server().submit(
            AgentAdapterRequest(
                agent_id=req.agent_id,
                prompt=req.prompt,
                run_id=req.run_id,
                idempotency_key=req.idempotency_key,
                delivery_mode=AgentDeliveryMode.parse(req.delivery_mode),
                invocation_mode=AgentInvocationMode.parse(req.invocation_mode),
                caller_agent_id=req.caller_agent_id,
                parent_run_id=req.parent_run_id,
                handoff_chain=tuple(req.handoff_chain),
                protocol=req.protocol,
                required_features=frozenset(req.required_features),
                conversation_id=req.conversation_id,
                source_message_id=req.source_message_id,
                return_path=req.return_path,
                response_language=req.response_language,
                priority=AgentRunPriority.parse(req.priority),
                checkpoint={
                    "client_route_id": req.client_route_id,
                    "conversation_id": req.conversation_id,
                    "task_id": req.task_id or req.run_id,
                    "turn_id": req.turn_id,
                    "desktop_access_profile": req.desktop_access_profile,
                    "collaboration_channel_ids": req.collaboration_channel_ids,
                    "collaboration_actor_id": (
                        req.collaboration_actor_id or req.agent_id
                    ),
                    "collaboration_task_id": req.task_id or req.run_id,
                    "repository_id": req.repository_id,
                },
            )
        )
        return {"run": run}
    except Exception as exc:
        raise HTTPException(
            status_code=409 if "Idempotency key" in str(exc) else 502,
            detail=api_error("agent_runtime_submit_failed", str(exc)[:240]),
        ) from exc


@app.get("/api/agent-runtime/agent-tools")
def api_agent_runtime_agent_tools(request: Request):
    require_loopback(request)
    return agent_tool_manifest(quick=True)


@app.post("/api/agent-runtime/agent-tools/{agent_id}/invoke")
def api_invoke_agent_tool(
    agent_id: str,
    req: AgentToolInvokeReq,
    request: Request,
):
    require_loopback(request)
    try:
        invocation_mode = AgentInvocationMode.parse(req.invocation_mode)
        if invocation_mode == AgentInvocationMode.DIRECT:
            raise ValueError("Agent tool invocation must use tool or handoff mode")
        run_id = str(req.run_id or req.task_id or uuid.uuid4()).strip()
        result = deliver_agent_sync(
            agent_id,
            req.prompt,
            task_id=req.task_id or req.parent_run_id,
            run_id=run_id,
            invocation_mode=invocation_mode,
            caller_agent_id=req.caller_agent_id,
            parent_run_id=req.parent_run_id,
            handoff_chain=tuple(req.handoff_chain),
            conversation_id=req.conversation_id,
            source_message_id=req.source_message_id,
            return_path=req.return_path,
            response_language=req.response_language,
            desktop_access_profile=req.desktop_access_profile,
            client_route_id=req.client_route_id,
            turn_id=req.turn_id,
        )
        return {
            "tool_id": f"galaxyssi.agent.{agent_id}.invoke",
            "result_role": (
                "tool_result"
                if invocation_mode == AgentInvocationMode.TOOL
                else "final_response"
            ),
            "run": result,
        }
    except Exception as exc:
        conflict_terms = ("cycle", "maximum depth", "cannot invoke itself", "requires")
        raise HTTPException(
            status_code=409 if any(term in str(exc).lower() for term in conflict_terms) else 502,
            detail=api_error(
                "agent_tool_invocation_failed",
                str(exc)[:240],
                params={"agent_id": agent_id},
            ),
        ) from exc


@app.get("/api/agent-runtime/runs/{run_id}")
def api_agent_runtime_run(
    run_id: str,
    request: Request,
    after_cursor: int = Query(0),
):
    require_loopback(request)
    runtime = desktop_agent_runtime_server()
    run = runtime.status(run_id)
    if run is None:
        raise HTTPException(status_code=404, detail=api_error("agent_runtime_run_not_found"))
    return {"run": run, "events": runtime.events(run_id, after_cursor)}


@app.post("/api/agent-runtime/runs/{run_id}/cancel")
def api_cancel_agent_runtime_run(run_id: str, request: Request):
    require_loopback(request)
    run = desktop_agent_runtime_server().cancel(run_id)
    if run is None:
        raise HTTPException(status_code=404, detail=api_error("agent_runtime_run_not_found"))
    return {"run": run}


def _agent_collaboration_http_error(exc: Exception) -> HTTPException:
    if isinstance(exc, AgentCollaborationAccessError):
        status_code = 403
    elif isinstance(exc, AgentCollaborationConflict):
        status_code = 409
    else:
        status_code = 400
    return HTTPException(
        status_code=status_code,
        detail=api_error(
            "agent_collaboration_failed",
            str(exc)[:240],
        ),
    )


@app.post("/api/agent-runtime/channels")
def api_create_agent_collaboration_channel(
    req: AgentCollaborationChannelReq,
    request: Request,
):
    require_desktop_api_token(request)
    try:
        scope = CollaborationScope.create(
            client_route_id=req.client_route_id,
            conversation_id=req.conversation_id,
            task_id=req.task_id,
            repository_root=req.repository_root,
            repository_id=req.repository_id,
        )
        channel = agent_collaboration_bus().create_channel(
            kind=req.kind,
            creator_agent_id=req.creator_agent_id,
            participant_agent_ids=req.participant_agent_ids,
            scope=scope,
        )
        return {"channel": channel}
    except AgentCollaborationError as exc:
        raise _agent_collaboration_http_error(exc) from exc


@app.get("/api/agent-runtime/channels")
def api_list_agent_collaboration_channels(
    request: Request,
    requester_agent_id: str = Query(...),
    client_route_id: str = Query(""),
    conversation_id: str = Query(""),
    task_id: str = Query(""),
    repository_id: str = Query(""),
    limit: int = Query(100),
):
    require_desktop_api_token(request)
    try:
        return {
            "channels": agent_collaboration_bus().channels(
                requester_agent_id=requester_agent_id,
                client_route_id=client_route_id,
                conversation_id=conversation_id,
                task_id=task_id,
                repository_id=repository_id,
                limit=limit,
            ),
        }
    except AgentCollaborationError as exc:
        raise _agent_collaboration_http_error(exc) from exc


@app.post("/api/agent-runtime/channels/{channel_id}/messages")
def api_publish_agent_collaboration_message(
    channel_id: str,
    req: AgentCollaborationMessageReq,
    request: Request,
):
    require_desktop_api_token(request)
    try:
        return {
            "message": agent_collaboration_bus().publish(
                channel_id,
                sender_agent_id=req.sender_agent_id,
                content=req.content,
                message_id=req.message_id,
                metadata=req.metadata,
            ),
        }
    except AgentCollaborationError as exc:
        raise _agent_collaboration_http_error(exc) from exc


@app.get("/api/agent-runtime/channels/{channel_id}/messages")
def api_list_agent_collaboration_messages(
    channel_id: str,
    request: Request,
    requester_agent_id: str = Query(...),
    after_sequence: int = Query(0),
    limit: int = Query(100),
):
    require_desktop_api_token(request)
    try:
        return {
            "messages": agent_collaboration_bus().messages(
                channel_id,
                requester_agent_id=requester_agent_id,
                after_sequence=after_sequence,
                limit=limit,
            ),
        }
    except AgentCollaborationError as exc:
        raise _agent_collaboration_http_error(exc) from exc


@app.post("/api/agent-runtime/channels/{channel_id}/ack")
def api_acknowledge_agent_collaboration_channel(
    channel_id: str,
    req: AgentCollaborationAckReq,
    request: Request,
):
    require_desktop_api_token(request)
    try:
        return {
            "channel": agent_collaboration_bus().acknowledge(
                channel_id,
                agent_id=req.agent_id,
                through_sequence=req.through_sequence,
            ),
        }
    except AgentCollaborationError as exc:
        raise _agent_collaboration_http_error(exc) from exc


@app.post("/api/agent-runtime/file-access")
def api_record_agent_file_access(req: AgentFileAccessReq, request: Request):
    require_desktop_api_token(request)
    return {
        "enabled": False,
        "status": "disabled",
        "recorded": False,
    }


@app.get("/api/agent-runtime/file-conflicts")
def api_list_agent_file_conflicts(
    request: Request,
    agent_id: str = Query(""),
    client_route_id: str = Query(...),
    conversation_id: str = Query(...),
    task_id: str = Query(...),
    repository_id: str = Query(""),
    workspace_id: str = Query(""),
    status: str = Query("open"),
    limit: int = Query(100),
):
    require_desktop_api_token(request)
    return {"enabled": False, "status": "disabled", "conflicts": []}


@app.post("/api/agent-runtime/file-conflicts/{conflict_id}/resolve")
def api_resolve_agent_file_conflict(
    conflict_id: str,
    req: AgentFileConflictResolveReq,
    request: Request,
):
    require_desktop_api_token(request)
    return {
        "enabled": False,
        "status": "disabled",
        "conflict": None,
        "conflict_id": conflict_id,
    }


@app.get("/api/desktop-tools")
def api_desktop_native_tools(request: Request):
    require_loopback(request)
    from desktop_native_tools import desktop_native_tool_registry

    return desktop_native_tool_registry().manifest()


@app.get("/api/desktop-tools/audit")
def api_desktop_native_tool_audit(
    request: Request,
    limit: int = Query(100, ge=1, le=500),
    tool_id: str = Query("", max_length=240),
    status: str = Query("", max_length=40),
):
    require_loopback(request)
    from desktop_native_tools import desktop_native_tool_registry

    return {
        "records": desktop_native_tool_registry().audit(
            limit=limit,
            tool_id=tool_id,
            status=status,
        )
    }


@app.get("/api/desktop-runtime")
def api_desktop_runtime(request: Request, refresh: bool = Query(False)):
    require_loopback(request)
    from desktop_runtime import desktop_runtime_manager

    return desktop_runtime_manager().snapshot(refresh=refresh)


@app.post("/api/desktop-tools/invoke")
def api_invoke_desktop_native_tool(req: DesktopNativeToolInvokeReq, request: Request):
    require_loopback(request)
    from desktop_native_tools import desktop_native_tool_registry

    arguments = dict(req.arguments)
    if req.workspace_id and "workspace_id" not in arguments:
        arguments["workspace_id"] = req.workspace_id
    return desktop_native_tool_registry().invoke(
        req.tool_id,
        arguments,
        {
            "tool_version": req.tool_version,
            "invocation_id": req.invocation_id,
            "task_id": req.task_id,
            "conversation_id": req.conversation_id,
            "client_route_id": req.client_route_id,
            "repository_id": req.repository_id,
            "collaboration_task_id": req.collaboration_task_id,
            "collaboration_channel_ids": req.collaboration_channel_ids,
            "idempotency_key": req.idempotency_key,
            "confirmation": req.confirmation,
            "caller_id": req.agent_id or "galaxyssi.desktop.loopback",
            "agent_id": req.agent_id,
        },
    )


@app.post("/api/desktop-tools/{invocation_id}/cancel")
def api_cancel_desktop_native_tool(invocation_id: str, request: Request):
    require_loopback(request)
    from desktop_native_tools import desktop_native_tool_registry

    return {"cancelled": desktop_native_tool_registry().cancel(invocation_id)}


@app.get("/api/desktop-control")
def api_desktop_control_status(request: Request):
    require_loopback(request)
    from desktop_control import desktop_control_manager

    return desktop_control_manager().status(include_revoked=True)


@app.post("/api/desktop-control/settings")
def api_desktop_control_settings(req: DesktopControlSettingsReq, request: Request):
    require_loopback(request)
    from desktop_control import desktop_control_manager
    from mqtt_bridge import publish_desktop_control_status_all

    result = desktop_control_manager().update_settings(
        enabled=req.enabled,
        require_unlocked=req.require_unlocked,
    )
    publish_desktop_control_status_all(reason="settings_changed")
    return result


def _desktop_control_authorization_action(
    authorization_id: str,
) -> dict:
    from desktop_control import DesktopControlError, desktop_control_manager
    from mqtt_bridge import publish_desktop_control_authorization_changed

    manager = desktop_control_manager()
    try:
        authorization = manager.revoke(authorization_id)
    except DesktopControlError as exc:
        status_code = 404 if exc.code == "authorization_not_found" else 409
        raise HTTPException(
            status_code=status_code,
            detail=api_error(exc.code, message=str(exc)),
        ) from exc
    publish_desktop_control_authorization_changed(authorization, reason="revoke")
    return manager.status(include_revoked=True)


@app.post("/api/desktop-control/authorizations/{authorization_id}/revoke")
def api_desktop_control_revoke(authorization_id: str, request: Request):
    require_loopback(request)
    return _desktop_control_authorization_action(authorization_id)


@app.get("/api/desktop-memory")
def api_desktop_memory(
    request: Request,
    query: str = Query(""),
    limit: int = Query(100),
    status: str = Query("active"),
):
    require_loopback(request)
    from desktop_memory import desktop_memory_store
    from desktop_memory_query_planner import plan_memory_query

    store = desktop_memory_store()
    plan = plan_memory_query(query) if query.strip() else None
    rows = (
        store.search(query, limit=limit, query_plan=plan)
        if query.strip() and status == "active"
        else store.list(limit=limit, status=status)
    )
    graph = (
        store.search_graph(
            query,
            limit=min(max(limit, 8), 60),
            query_plan=plan,
        )
        if query.strip()
        else store.graph_snapshot()
    )
    return {
        "memories": rows,
        "stats": store.stats(),
        "graph": graph,
        "query_plan": plan.as_dict() if plan else None,
    }


@app.get("/api/desktop-memory/inbox")
def api_desktop_memory_inbox(request: Request, limit: int = Query(100)):
    require_loopback(request)
    from desktop_memory import desktop_memory_store

    store = desktop_memory_store()
    return {
        "candidates": store.list_candidates(limit=limit),
        "stats": store.stats(),
    }


@app.get("/api/desktop-memory/evolution")
def api_desktop_memory_evolution(request: Request, limit: int = Query(100)):
    require_loopback(request)
    from desktop_memory import desktop_memory_store

    return desktop_memory_store().evolution_snapshot(limit=limit)


@app.get("/api/desktop-memory/critic")
def api_desktop_memory_critic(request: Request, limit: int = Query(20)):
    require_loopback(request)
    from desktop_memory import desktop_memory_store

    return desktop_memory_store().critic_status(history_limit=limit)


@app.post("/api/desktop-memory/critic/run")
def api_run_desktop_memory_critic(request: Request):
    require_loopback(request)
    from desktop_memory import desktop_memory_store

    return desktop_memory_store().run_critic(force=True, trigger="manual")


@app.get("/api/desktop-memory/visualization")
def api_desktop_memory_visualization(request: Request, limit: int = Query(100)):
    require_loopback(request)
    from desktop_memory import desktop_memory_store

    return desktop_memory_store().visualization_snapshot(limit=limit)


@app.post("/api/desktop-memory/inbox")
def api_propose_desktop_memory(req: DesktopMemoryReq, request: Request):
    require_loopback(request)
    from desktop_memory import desktop_memory_store

    candidate = desktop_memory_store().propose(
        req.content,
        kind=req.kind,
        importance=req.importance,
        confidence=0.8,
        namespace=req.namespace,
        tags=["api_candidate"],
        evidence=[{"source": "desktop_ui", "kind": "manual_proposal"}],
    )
    if candidate is None:
        raise HTTPException(status_code=400, detail=api_error("desktop_memory_rejected"))
    return candidate


@app.post("/api/desktop-memory")
def api_remember_desktop_memory(req: DesktopMemoryReq, request: Request):
    require_loopback(request)
    from desktop_memory import desktop_memory_store

    memory = desktop_memory_store().remember(
        req.content,
        kind=req.kind,
        importance=req.importance,
        confidence=1.0,
        tags=["manual"],
        namespace=req.namespace,
        evidence=[{"source": "desktop_ui", "kind": "manual_memory"}],
        valid_until_at=req.valid_until_at,
    )
    if memory is None:
        raise HTTPException(status_code=400, detail=api_error("desktop_memory_rejected"))
    return memory


@app.post("/api/desktop-memory/inbox/{candidate_id}/approve")
def api_approve_desktop_memory_candidate(candidate_id: str, request: Request):
    require_loopback(request)
    from desktop_memory import desktop_memory_store

    candidate = desktop_memory_store().approve_candidate(candidate_id)
    if candidate is None:
        raise HTTPException(status_code=404, detail=api_error("memory_candidate_not_pending"))
    return candidate


@app.post("/api/desktop-memory/inbox/{candidate_id}/reject")
def api_reject_desktop_memory_candidate(candidate_id: str, request: Request):
    require_loopback(request)
    from desktop_memory import desktop_memory_store

    candidate = desktop_memory_store().reject_candidate(candidate_id)
    if candidate is None:
        raise HTTPException(status_code=404, detail=api_error("memory_candidate_not_pending"))
    return candidate


@app.delete("/api/desktop-memory/{memory_id}")
def api_forget_desktop_memory(memory_id: str, request: Request):
    require_loopback(request)
    from desktop_memory import desktop_memory_store

    return {"id": memory_id, "forgotten": desktop_memory_store().forget(memory_id)}


@app.get("/api/tool-marketplace")
def api_tool_marketplace(request: Request, kind: str = Query("")):
    require_loopback(request)
    from tool_marketplace import ToolMarketplaceError, tool_marketplace

    try:
        return tool_marketplace().catalog(kind)
    except ToolMarketplaceError as exc:
        raise HTTPException(
            status_code=400,
            detail=api_error(exc.code, str(exc)),
        ) from exc


@app.post("/api/tool-marketplace/{item_id}/install")
def api_install_tool_marketplace_item(
    item_id: str,
    req: ToolMarketplaceInstallReq,
    request: Request,
):
    require_desktop_api_token(request)
    from tool_marketplace import ToolMarketplaceError, tool_marketplace

    try:
        return tool_marketplace().install(
            item_id,
            req.configuration,
            req.approved_permissions,
        )
    except ToolMarketplaceError as exc:
        status = 404 if exc.code == "item_not_found" else 409
        raise HTTPException(
            status_code=status,
            detail=api_error(exc.code, str(exc), details=exc.details),
        ) from exc
    except ValueError as exc:
        raise HTTPException(
            status_code=400,
            detail=api_error("marketplace_install_invalid", str(exc)),
        ) from exc


@app.delete("/api/tool-marketplace/{item_id}")
def api_uninstall_tool_marketplace_item(item_id: str, request: Request):
    require_desktop_api_token(request)
    from tool_marketplace import ToolMarketplaceError, tool_marketplace

    try:
        return tool_marketplace().uninstall(item_id)
    except ToolMarketplaceError as exc:
        status = 404 if exc.code == "item_not_found" else 409
        raise HTTPException(
            status_code=status,
            detail=api_error(exc.code, str(exc), details=exc.details),
        ) from exc


@app.post("/api/tool-marketplace/{item_id}/revoke")
def api_revoke_tool_marketplace_item(item_id: str, request: Request):
    require_desktop_api_token(request)
    from tool_marketplace import ToolMarketplaceError, tool_marketplace

    try:
        return tool_marketplace().revoke(item_id)
    except ToolMarketplaceError as exc:
        status = 404 if exc.code == "item_not_found" else 409
        raise HTTPException(
            status_code=status,
            detail=api_error(exc.code, str(exc), details=exc.details),
        ) from exc


@app.post("/api/tool-marketplace/{item_id}/rollback")
def api_rollback_tool_marketplace_item(item_id: str, request: Request):
    require_desktop_api_token(request)
    from tool_marketplace import ToolMarketplaceError, tool_marketplace

    try:
        return tool_marketplace().rollback(item_id)
    except ToolMarketplaceError as exc:
        status = 404 if exc.code == "item_not_found" else 409
        raise HTTPException(
            status_code=status,
            detail=api_error(exc.code, str(exc), details=exc.details),
        ) from exc


@app.get("/api/desktop-skills")
def api_desktop_skills(request: Request):
    require_loopback(request)
    from desktop_skills import desktop_skill_registry

    return {"skills": desktop_skill_registry().list(include_instructions=True)}


@app.post("/api/desktop-skills")
def api_save_desktop_skill(req: DesktopSkillReq, request: Request):
    require_loopback(request)
    from desktop_skills import desktop_skill_registry

    try:
        return desktop_skill_registry().upsert(req.model_dump())
    except ValueError as exc:
        raise HTTPException(status_code=400, detail=api_error("desktop_skill_invalid", str(exc))) from exc


@app.post("/api/desktop-skills/{skill_id}/enabled")
def api_enable_desktop_skill(skill_id: str, req: DesktopSkillEnabledReq, request: Request):
    require_loopback(request)
    from desktop_skills import desktop_skill_registry

    try:
        return desktop_skill_registry().set_enabled(skill_id, req.enabled)
    except KeyError as exc:
        raise HTTPException(status_code=404, detail=api_error("desktop_skill_not_found")) from exc


@app.delete("/api/desktop-skills/{skill_id}")
def api_delete_desktop_skill(skill_id: str, request: Request):
    require_loopback(request)
    from desktop_skills import desktop_skill_registry

    return {"id": skill_id, "deleted": desktop_skill_registry().delete(skill_id)}


@app.get("/api/desktop-mcp")
def api_desktop_mcp(request: Request):
    require_loopback(request)
    from desktop_mcp import desktop_mcp_registry

    registry = desktop_mcp_registry()
    return {
        "connections": registry.list(include_command=True),
        "audit": registry.audit(limit=100),
    }


@app.post("/api/desktop-mcp")
def api_save_desktop_mcp(req: DesktopMcpReq, request: Request):
    require_loopback(request)
    from desktop_mcp import desktop_mcp_registry

    try:
        return desktop_mcp_registry().upsert(req.model_dump())
    except ValueError as exc:
        raise HTTPException(status_code=400, detail=api_error("desktop_mcp_invalid", str(exc))) from exc


def _tool_handle_http_exception(error) -> HTTPException:
    code = str(getattr(error, "code", "") or "tool_handle_failed")
    if code in {"tool_handle_not_found"}:
        status_code = 404
    elif code in {"tool_handle_expired"}:
        status_code = 410
    elif code.endswith("_mismatch") or code == "tool_handle_capability_denied":
        status_code = 403
    else:
        status_code = 400
    return HTTPException(
        status_code=status_code,
        detail=api_error(
            code,
            str(error),
            retryable=bool(getattr(error, "retryable", False)),
        ),
    )


@app.get("/api/tool-handles")
def api_tool_handles(
    request: Request,
    owner_id: str = "",
    context_id: str = "",
    kind: str = "",
):
    require_loopback(request)
    from tool_handle_registry import ToolHandleScope, tool_handle_registry

    scope = (
        ToolHandleScope(owner_id=owner_id, context_id=context_id)
        if owner_id
        else None
    )
    try:
        registry = tool_handle_registry()
        return {
            "handles": registry.list(scope=scope, kind=kind),
            "status": registry.status(),
        }
    except Exception as exc:
        from tool_handle_registry import ToolHandleError

        if isinstance(exc, ToolHandleError):
            raise _tool_handle_http_exception(exc) from exc
        raise


@app.delete("/api/tool-handles/{handle_id}")
def api_release_tool_handle(
    handle_id: str,
    request: Request,
    owner_id: str,
    context_id: str = "",
):
    require_loopback(request)
    from tool_handle_registry import ToolHandleError, ToolHandleScope, tool_handle_registry

    try:
        released = tool_handle_registry().release(
            handle_id,
            scope=ToolHandleScope(owner_id=owner_id, context_id=context_id),
        )
        return {"handle_id": handle_id, "released": released}
    except ToolHandleError as exc:
        raise _tool_handle_http_exception(exc) from exc


@app.post("/api/desktop-mcp/{connection_id}/handles")
def api_open_desktop_mcp_handle(
    connection_id: str,
    req: ToolHandleOpenReq,
    request: Request,
):
    require_loopback(request)
    from desktop_mcp import desktop_mcp_registry
    from tool_handle_registry import ToolHandleError

    try:
        return desktop_mcp_registry().open_handle(
            connection_id,
            owner_id=req.owner_id,
            context_id=req.context_id,
            parent_run_id=req.parent_run_id,
            ttl_seconds=req.ttl_seconds,
        )
    except KeyError as exc:
        raise HTTPException(
            status_code=404,
            detail=api_error("desktop_mcp_not_found"),
        ) from exc
    except ToolHandleError as exc:
        raise _tool_handle_http_exception(exc) from exc
    except RuntimeError as exc:
        raise HTTPException(
            status_code=409,
            detail=api_error("desktop_mcp_unavailable", str(exc)),
        ) from exc


@app.post("/api/desktop-mcp/handles/{handle_id}/invoke")
def api_invoke_desktop_mcp_handle(
    handle_id: str,
    req: DesktopMcpHandleInvokeReq,
    request: Request,
):
    require_loopback(request)
    from desktop_mcp import desktop_mcp_registry
    from mcp_security import McpPermissionDenied
    from tool_handle_registry import ToolHandleError

    try:
        return desktop_mcp_registry().invoke_handle(
            handle_id,
            req.prompt,
            owner_id=req.owner_id,
            context_id=req.context_id,
            explicit_user_selection=req.explicit_user_selection,
            audit_context={
                "caller_id": req.owner_id,
                "task_id": req.task_id,
                "conversation_id": req.context_id,
            },
        )
    except ToolHandleError as exc:
        raise _tool_handle_http_exception(exc) from exc
    except McpPermissionDenied as exc:
        raise HTTPException(
            status_code=403,
            detail=api_error("desktop_mcp_permission_denied", str(exc)),
        ) from exc
    except (KeyError, RuntimeError) as exc:
        raise HTTPException(
            status_code=409,
            detail=api_error("desktop_mcp_invoke_failed", str(exc)),
        ) from exc


@app.get("/api/desktop-mcp-import/sources")
def api_desktop_mcp_import_sources(request: Request):
    require_loopback(request)
    from mcp_config_import import discover_mcp_config_sources

    return {"sources": discover_mcp_config_sources()}


@app.post("/api/desktop-mcp-import/preview")
def api_desktop_mcp_import_preview(
    req: DesktopMcpImportPreviewReq,
    request: Request,
):
    require_loopback(request)
    from desktop_mcp import desktop_mcp_registry
    from mcp_config_import import McpConfigImportError, parse_mcp_import

    registry = desktop_mcp_registry()
    try:
        document = parse_mcp_import(
            req.content,
            source_hint=req.source_hint,
            file_name=req.file_name,
            base_directory=req.base_directory,
        )
        return document.public(
            connection["id"]
            for connection in registry.list(include_configuration=True)
        )
    except McpConfigImportError as exc:
        raise HTTPException(
            status_code=400,
            detail=api_error("desktop_mcp_import_invalid", str(exc)),
        ) from exc


@app.post("/api/desktop-mcp-import/commit")
def api_desktop_mcp_import_commit(
    req: DesktopMcpImportCommitReq,
    request: Request,
):
    require_loopback(request)
    from desktop_mcp import desktop_mcp_registry
    from mcp_config_import import McpConfigImportError, parse_mcp_import

    registry = desktop_mcp_registry()
    selected = {str(value).strip().casefold() for value in req.selected_ids}
    if not selected:
        raise HTTPException(
            status_code=400,
            detail=api_error("desktop_mcp_import_empty"),
        )
    try:
        document = parse_mcp_import(
            req.content,
            source_hint=req.source_hint,
            file_name=req.file_name,
            base_directory=req.base_directory,
        )
        if document.digest != req.digest.casefold():
            raise McpConfigImportError(
                "The MCP configuration changed after preview. Preview it again."
            )
        imported = []
        skipped = []
        for candidate in document.candidates:
            connection_id = candidate.connection["id"]
            if connection_id not in selected:
                continue
            if not candidate.importable:
                skipped.append(
                    {
                        "id": connection_id,
                        "reason": "This MCP server needs a safe configuration update before import.",
                    }
                )
                continue
            imported.append(registry.upsert(candidate.connection))
        return {
            "source": document.source,
            "digest": document.digest,
            "imported": imported,
            "skipped": skipped,
        }
    except McpConfigImportError as exc:
        raise HTTPException(
            status_code=400,
            detail=api_error("desktop_mcp_import_invalid", str(exc)),
        ) from exc
    except ValueError as exc:
        raise HTTPException(
            status_code=400,
            detail=api_error("desktop_mcp_invalid", str(exc)),
        ) from exc


@app.post("/api/desktop-mcp/{connection_id}/probe")
def api_probe_desktop_mcp(connection_id: str, request: Request):
    require_loopback(request)
    from desktop_mcp import desktop_mcp_registry

    try:
        return desktop_mcp_registry().probe(connection_id)
    except KeyError as exc:
        raise HTTPException(status_code=404, detail=api_error("desktop_mcp_not_found")) from exc


@app.get("/api/desktop-mcp-audit")
def api_desktop_mcp_audit(
    request: Request,
    connection_id: str = "",
    limit: int = 100,
):
    require_loopback(request)
    from desktop_mcp import desktop_mcp_registry

    return {
        "audit": desktop_mcp_registry().audit(
            connection_id=connection_id,
            limit=max(1, min(limit, 500)),
        )
    }


@app.delete("/api/desktop-mcp/{connection_id}")
def api_delete_desktop_mcp(connection_id: str, request: Request):
    require_loopback(request)
    from desktop_mcp import desktop_mcp_registry

    return {"id": connection_id, "deleted": desktop_mcp_registry().delete(connection_id)}


@app.post("/api/agent-adapters/{agent_id}/deliver")
def api_deliver_agent(agent_id: str, req: AgentDeliveryReq, request: Request):
    require_loopback(request)
    try:
        return deliver_agent_sync(
            agent_id,
            req.prompt,
            task_id=req.task_id,
            delivery_mode=req.delivery_mode,
            invocation_mode=req.invocation_mode,
            caller_agent_id=req.caller_agent_id,
            parent_run_id=req.parent_run_id,
            handoff_chain=tuple(req.handoff_chain),
            conversation_id=req.conversation_id,
            source_message_id=req.source_message_id,
            return_path=req.return_path,
            protocol=req.protocol,
            required_features=tuple(req.required_features),
            response_language=req.response_language,
            client_route_id=req.client_route_id,
            turn_id=req.turn_id,
        )
    except Exception as exc:
        raise HTTPException(
            status_code=409 if "Idempotency key" in str(exc) else 502,
            detail=api_error("agent_adapter_delivery_failed", str(exc)[:240], params={"agent_id": agent_id}),
        ) from exc


@app.get("/api/agent-adapters/{agent_id}/runs/{run_id}")
def api_agent_adapter_run(agent_id: str, run_id: str, request: Request, after_cursor: int = Query(0)):
    require_loopback(request)
    provider = desktop_agent_provider()
    result = provider.status(agent_id, run_id)
    if result is None:
        raise HTTPException(status_code=404, detail=api_error("agent_adapter_run_not_found"))
    return {"run": result.public(), "events": provider.events(agent_id, run_id, after_cursor)}


@app.post("/api/agent-adapters/{agent_id}/runs/{run_id}/cancel")
def api_cancel_agent_adapter_run(agent_id: str, run_id: str, request: Request):
    require_loopback(request)
    result = desktop_agent_provider().cancel(agent_id, run_id)
    if result is None:
        raise HTTPException(status_code=404, detail=api_error("agent_adapter_run_not_found"))
    return {"run": result.public()}


@app.get("/api/agent-adapters/{agent_id}/observations")
def api_agent_adapter_observations(agent_id: str, request: Request, limit: int = Query(100)):
    require_loopback(request)
    return {"observations": desktop_agent_provider().observations(agent_id, limit)}

class MobileTestMessageReq(BaseModel):
    contact_id: str
    content: str
    client_route_id: str = ""
    broadcast: bool = False

@app.post("/api/mobile/test-message")
def api_mobile_test_message(req: MobileTestMessageReq):
    from mqtt_bridge import publish_mobile_test_message
    return publish_mobile_test_message(req.contact_id, req.content, req.client_route_id, req.broadcast)

class AgentPushReq(BaseModel):
    contact_id: str
    content: str
    source: str = "agent"
    secret: str = ""
    client_route_id: str = ""
    broadcast: bool = False

@app.post("/api/agent/push")
def api_agent_push(req: AgentPushReq, x_galaxyssi_token: str = Header(default="")):
    from push_auth import verify_agent_push_token
    from mqtt_bridge import publish_agent_push_message

    token = x_galaxyssi_token or req.secret
    if not verify_agent_push_token(token):
        raise HTTPException(
            status_code=401,
            detail=api_error("agent_push_token_invalid", "Invalid GalaxySSI Agent push token."),
        )
    return publish_agent_push_message(req.contact_id, req.content, req.source, req.client_route_id, req.broadcast)

class AgentTaskStartReq(BaseModel):
    contact_id: str
    prompt: str
    source_message_id: str
    task_id: str
    client_route_id: str
    conversation_id: str
    turn_id: str

@app.post("/api/agent/tasks")
def api_start_agent_task(req: AgentTaskStartReq, x_galaxyssi_token: str = Header(default="")):
    from push_auth import verify_agent_push_token
    from mqtt_bridge import start_agent_task
    if not verify_agent_push_token(x_galaxyssi_token):
        raise HTTPException(status_code=401, detail=api_error("agent_push_token_invalid", "Invalid GalaxySSI Agent push token."))
    return start_agent_task(
        req.contact_id,
        req.prompt,
        source_message_id=req.source_message_id,
        task_id=req.task_id,
        client_route_id=req.client_route_id,
        conversation_id=req.conversation_id,
        turn_id=req.turn_id,
    )

@app.get("/api/agent/tasks")
def api_list_agent_tasks(limit: int = Query(100)):
    return {"tasks": agent_task_manager.list(limit=limit)}

@app.get("/api/agent/tasks/{task_id}")
def api_get_agent_task(task_id: str):
    task = agent_task_manager.get(task_id)
    if task is None:
        raise HTTPException(status_code=404, detail=api_error("agent_task_not_found"))
    return task.public()

@app.get("/api/agent/tasks/{task_id}/run-events")
def api_get_agent_task_run_events(
    task_id: str,
    request: Request,
    after_sequence: int = Query(0, ge=0),
    limit: int = Query(250, ge=1, le=1_000),
):
    require_loopback(request)
    snapshot = agent_task_manager.run_snapshot(task_id)
    if snapshot is None:
        raise HTTPException(status_code=404, detail=api_error("agent_task_not_found"))
    return {
        "snapshot": snapshot,
        "events": agent_task_manager.run_events(
            task_id,
            after_sequence=after_sequence,
            limit=limit,
        ),
    }

@app.post("/api/agent/tasks/{task_id}/republish")
def api_republish_agent_task(task_id: str, request: Request):
    require_loopback(request)
    from mqtt_bridge import republish_agent_task_result

    result = republish_agent_task_result(task_id)
    if not result.get("ok") and result.get("error") == "agent_task_not_found":
        raise HTTPException(status_code=404, detail=result)
    return result

@app.post("/api/agent/tasks/{task_id}/cancel")
def api_cancel_agent_task(
    task_id: str,
    client_route_id: str = Query(...),
    conversation_id: str = Query(...),
    turn_id: str = Query(...),
    x_galaxyssi_token: str = Header(default=""),
):
    from push_auth import verify_agent_push_token
    from mqtt_bridge import publish_agent_task_event
    if not verify_agent_push_token(x_galaxyssi_token):
        raise HTTPException(status_code=401, detail=api_error("agent_push_token_invalid", "Invalid GalaxySSI Agent push token."))
    task = agent_task_manager.cancel_scoped(
        task_id,
        client_route_id=client_route_id,
        conversation_id=conversation_id,
        turn_id=turn_id,
        on_event=publish_agent_task_event,
    )
    if task is None:
        raise HTTPException(status_code=404, detail=api_error("agent_task_not_found"))
    return {"task": task.public()}


class DesktopTaskStartReq(BaseModel):
    prompt: str
    agent_id: str = "auto"
    execution_mode: str = "auto_complete"
    task_budget: dict = Field(default_factory=dict)
    conversation_id: str = ""
    attachments: list[str] = Field(default_factory=list)
    response_language: str = ""
    retry_of: str = ""
    attempt: int = 1


class DesktopTaskRecoveryReq(BaseModel):
    action: str
    agent_id: str = ""


class DesktopTaskControlReq(BaseModel):
    reason: str = ""
    lease_seconds: int = 900


def _desktop_agent_for(prompt: str, requested: str = "auto") -> str:
    requested_id = str(requested or "auto").strip().lower()
    if requested_id in {"", "auto", "desktop", "this-desktop"}:
        return "desktop"
    if requested_id.startswith("mcp:"):
        from desktop_mcp import desktop_mcp_registry

        connection_id = requested_id.split(":", 1)[1]
        if desktop_mcp_registry().get(connection_id) is None:
            raise HTTPException(status_code=404, detail=api_error("desktop_mcp_not_found"))
        return requested_id
    from agent_gateway import visible_agent_specs

    # Admission checks identity only; health probes belong to execution or diagnostics.
    if requested_id not in visible_agent_specs():
        raise HTTPException(status_code=404, detail=api_error("desktop_agent_not_found"))
    return requested_id


def _desktop_task_prompt(
    prompt: str,
    conversation_id: str,
    attachment_paths: list[str],
    response_language: str = "",
    execution_policy=None,
    current_task_id: str = "",
) -> str:
    from conversation_context import (
        ContextBudget,
        compacted_history_cursor,
        compile_context,
        conversation_summary_store,
        render_prompt,
        task_history_messages,
    )
    from response_policy import response_policy_prompt

    summary_store = conversation_summary_store()
    summary_key = f"desktop-task:{conversation_id}"
    summary_state = summary_store.state(summary_key)
    history = agent_task_manager.conversation_messages(
        conversation_id,
        after_cursor=summary_state.cursor,
    )
    from agent_execution_harness import AgentExecutionMode

    plan_only = (
        execution_policy is not None
        and execution_policy.execution_mode == AgentExecutionMode.PLAN_ONLY
    )
    task_instruction = (
        "You are preparing a read-only plan from GalaxySSI Desktop. Inspect only what is needed, "
        "make no changes, and return a concrete plan with assumptions and risks."
        if plan_only else
        "You are executing a task from GalaxySSI Desktop. Work directly, use the available local tools, "
        "verify the result, and return a concise final response with artifact paths when files are created."
    )
    preamble = response_policy_prompt(prompt, response_language) + "\n\n" + task_instruction
    if attachment_paths:
        preamble += "\n\nFiles attached to this task workspace:\n" + "\n".join(
            f"- {value}" for value in attachment_paths
        )
    compiled = compile_context(
        task_history_messages(
            history,
            prompt,
            current_task_id=current_task_id,
            after_cursor=summary_state.cursor,
        ),
        previous_summary=summary_state.summary,
        fixed_prompt=preamble,
        budget=ContextBudget(),
    )
    if compiled.compacted and compiled.summary:
        cursor = compacted_history_cursor(
            history,
            compiled.compacted_group_ids,
            summary_state.cursor,
        )
        summary_store.put(
            summary_key,
            compiled.summary,
            through_created_at=cursor[0],
            through_task_id=cursor[1],
        )
    return render_prompt(compiled, prompt, preamble=preamble)


def _copy_desktop_attachments(task_id: str, values: list[str]) -> list[str]:
    from task_workspace import task_workspace

    destination = task_workspace(task_id, "desktop") / "downloads" / "input"
    destination.mkdir(parents=True, exist_ok=True)
    copied: list[str] = []
    used: set[str] = set()
    for raw in list(values or [])[:12]:
        source = Path(str(raw or "")).expanduser().resolve()
        if not source.is_file() or source.stat().st_size > 512 * 1024 * 1024:
            continue
        name = source.name[:220] or f"attachment-{len(copied) + 1}"
        stem, suffix = Path(name).stem, Path(name).suffix
        candidate = name
        serial = 2
        while candidate.casefold() in used or (destination / candidate).exists():
            candidate = f"{stem}-{serial}{suffix}"
            serial += 1
        used.add(candidate.casefold())
        shutil.copy2(source, destination / candidate)
        copied.append(f"downloads/input/{candidate}")
    return copied


def _interrupt_desktop_task(task) -> dict:
    cancelled_runs: list[str] = []
    try:
        from desktop_native_tools import desktop_native_tool_registry

        desktop_native_tool_registry().cancel_task(task.task_id)
    except Exception:
        pass
    try:
        runtime = desktop_agent_runtime_server()
        candidates = [task.task_id]
        candidates.extend(
            str(item.get("run_id") or "")
            for item in runtime.runs(parent_run_id=task.task_id, limit=100)
        )
        for run_id in dict.fromkeys(value for value in candidates if value):
            try:
                if runtime.cancel(run_id) is not None:
                    cancelled_runs.append(run_id)
            except Exception:
                continue
    except Exception:
        pass
    return {
        "cancelled_runtime_runs": cancelled_runs,
        "native_tools_cancelled": True,
    }


def _desktop_resume_checkpoint(task) -> dict:
    captured: dict = {}
    try:
        from desktop_control import capture_desktop_screenshot
        from desktop_perception import DesktopPerceptionService

        captured = DesktopPerceptionService(capture_desktop_screenshot).capture(
            include_screenshot=False,
            include_ocr=True,
            include_ui_tree=True,
            max_elements=60,
            max_depth=8,
            max_ocr_chars=6_000,
        )
    except Exception as exc:
        return {
            "persisted": {
                "capture_status": "unavailable",
                "capture_error": str(exc)[:240],
            },
            "grounding": "",
        }

    active_window = (
        dict(captured.get("active_window") or {})
        if isinstance(captured.get("active_window"), dict)
        else {}
    )
    ui_tree = (
        dict(captured.get("ui_tree") or {})
        if isinstance(captured.get("ui_tree"), dict)
        else {}
    )
    ocr = (
        dict(captured.get("ocr") or {})
        if isinstance(captured.get("ocr"), dict)
        else {}
    )
    controls = [
        item
        for item in list(ui_tree.get("elements") or [])
        if isinstance(item, dict)
        and (
            item.get("actions")
            or item.get("focusable")
            or item.get("focused")
        )
    ][:30]
    control_lines = [
        (
            f"- {str(item.get('control_type') or 'control')[:80]}: "
            f"{str(item.get('name') or item.get('automation_id') or '[unnamed]')[:240]}"
        )
        for item in controls
    ]
    ocr_text = str(ocr.get("text") or "").strip()[:4_000]
    grounding_parts = [
        "Fresh Desktop state after manual takeover (untrusted observation data):",
        (
            f"Active window: {str(active_window.get('title') or '[unknown]')[:300]} "
            f"({str(active_window.get('process_name') or 'unknown')[:120]})"
        ),
    ]
    if control_lines:
        grounding_parts.extend(["Visible actionable controls:", *control_lines])
    if ocr_text:
        grounding_parts.extend(["Visible OCR text:", ocr_text])
    return {
        "persisted": {
            "capture_status": "available",
            "capture_id": str(captured.get("capture_id") or "")[:160],
            "captured_at": int(captured.get("captured_at") or 0),
            "available_layers": list(captured.get("available_layers") or []),
            "active_window": {
                "title": str(active_window.get("title") or "")[:300],
                "process_name": str(active_window.get("process_name") or "")[:120],
            },
            "ui_element_count": int(ui_tree.get("element_count") or 0),
            "ocr_character_count": len(str(ocr.get("text") or "")),
        },
        "grounding": "\n".join(grounding_parts),
    }


def _desktop_resume_runner(task, checkpoint_bundle: dict):
    from agent_execution_harness import AgentExecutionPolicy

    policy = AgentExecutionPolicy.from_public(dict(task.execution_policy or {}))
    response_language = language_policy_config()["response_language"]
    grounding = str(checkpoint_bundle.get("grounding") or "").strip()

    def runner(current_task):
        continuation = (
            f"{current_task.prompt.rstrip()}\n\n"
            "Continue the same task after a user-controlled Desktop takeover. "
            "Re-observe the current state, preserve completed work, repair any "
            "partial action, and verify the final result."
        )
        if grounding:
            continuation += f"\n\n{grounding}"
        compiled_prompt = _desktop_task_prompt(
            continuation,
            current_task.conversation_id,
            list(current_task.attachments or []),
            response_language,
            policy,
            current_task_id=current_task.task_id,
        )
        current_task_id = current_task.task_id
        current_agent_id = current_task.agent_id
        agent_task_manager.update(
            current_task_id,
            "running",
            current_step="Re-observing the Desktop after manual takeover",
        )
        if current_agent_id == "desktop":
            from desktop_super_agent import DesktopSuperAgent

            outcome = DesktopSuperAgent(
                task_manager=agent_task_manager,
                diagnostics=connector_diagnostics,
                deliver=deliver_agent_sync,
            ).run(
                task_id=current_task_id,
                conversation_id=current_task.conversation_id,
                prompt=continuation,
                compiled_prompt=compiled_prompt,
                attachments=list(current_task.attachments or []),
                response_language=response_language,
                execution_policy=policy,
            )
            return outcome.reply
        if current_agent_id.startswith("mcp:"):
            from desktop_mcp import desktop_mcp_registry

            connection_id = current_agent_id.split(":", 1)[1]
            result = desktop_mcp_registry().invoke_prompt(
                connection_id,
                compiled_prompt,
                process_callback=lambda process: agent_task_manager.register_process(
                    current_task_id,
                    process,
                ),
                explicit_user_selection=True,
                audit_context={
                    "caller_id": "galaxyssi.desktop.resume",
                    "task_id": current_task_id,
                    "conversation_id": current_task.conversation_id,
                },
            )
            reply = str(result.get("result") or "").strip()
            if not reply:
                raise RuntimeError("MCP returned no result after task continuation")
            return reply
        result = deliver_agent_sync(
            current_agent_id,
            compiled_prompt,
            task_id=current_task_id,
            run_id=(
                f"{current_task_id}:g{current_task.execution_generation}:"
                f"resume:{current_task.resume_count}"
            ),
            invocation_mode="handoff",
            caller_agent_id="galaxyssi.desktop.resume",
            parent_run_id=current_task_id,
            conversation_id=current_task.conversation_id,
            source_message_id=current_task.source_message_id,
            return_path="desktop-ui",
            response_language=response_language,
            execution_prompt=current_task.prompt,
            execution_policy=policy.public(),
        )
        return str(result.get("reply") or "")

    return runner


def _configure_desktop_run_control() -> None:
    from desktop_run_control import desktop_run_control

    def publish_remote_event(snapshot: dict) -> None:
        if not str(snapshot.get("client_route_id") or "").strip():
            return
        try:
            from mqtt_bridge import publish_agent_task_event

            publish_agent_task_event(snapshot)
        except Exception:
            pass

    def publish_remote_result(snapshot: dict) -> None:
        if not str(snapshot.get("client_route_id") or "").strip():
            return
        try:
            from mqtt_bridge import republish_agent_task_result

            republish_agent_task_result(str(snapshot.get("task_id") or ""))
        except Exception:
            pass

    desktop_run_control().configure(
        runner_factory=_desktop_resume_runner,
        interrupt_handler=_interrupt_desktop_task,
        checkpoint_provider=_desktop_resume_checkpoint,
        task_manager_provider=lambda: agent_task_manager,
        event_handler=publish_remote_event,
        result_handler=publish_remote_result,
    )


_configure_desktop_run_control()


@app.post("/api/desktop/tasks")
def api_start_desktop_task(req: DesktopTaskStartReq, request: Request):
    require_loopback(request)
    prompt = str(req.prompt or "").strip()
    if not prompt and not req.attachments:
        raise HTTPException(status_code=400, detail=api_error("desktop_task_empty"))
    task_id = str(uuid.uuid4())
    conversation_id = str(req.conversation_id or "").strip() or str(uuid.uuid4())
    attachments = _copy_desktop_attachments(task_id, req.attachments)
    from agent_execution_harness import AgentExecutionMode, execution_policy_for

    desktop_execution_policy = execution_policy_for(
        prompt,
        attachments=attachments,
        requested_execution_mode=req.execution_mode,
        requested_task_budget=req.task_budget,
    )
    agent_id = _desktop_agent_for(prompt, req.agent_id)
    if (
        desktop_execution_policy.execution_mode == AgentExecutionMode.PLAN_ONLY
        and agent_id.startswith("mcp:")
    ):
        agent_id = "desktop"
    response_language = str(req.response_language or "").strip() or language_policy_config()["response_language"]
    from agent_execution_harness import AgentClarificationMode, clarification_decision_for
    from response_policy import clarification_question, response_language_tag

    clarification = clarification_decision_for(
        prompt,
        has_attachments=bool(attachments),
        has_conversation_context=bool(
            agent_task_manager.conversation_messages(conversation_id, limit=1)
        ),
    )
    clarification_reply = (
        clarification_question(
            clarification.question.value,
            response_language_tag(prompt, response_language),
        )
        if clarification.mode == AgentClarificationMode.ASK_LOCALLY
        else ""
    )
    compiled_prompt = _desktop_task_prompt(
        prompt,
        conversation_id,
        attachments,
        response_language,
        desktop_execution_policy,
        current_task_id=task_id,
    )

    def runner(task):
        if clarification_reply:
            agent_task_manager.add_event(
                task.task_id,
                "clarification",
                "Waiting for one required detail",
                event_id=f"clarification:{task.task_id}",
                metadata={
                    "question": clarification.question.value,
                    "mode": clarification.mode.value,
                },
            )
            return clarification_reply
        agent_task_manager.update(
            task.task_id,
            "running",
            current_step="Planning the task" if agent_id == "desktop" else f"Running {agent_id}",
        )
        if agent_id == "desktop":
            from desktop_super_agent import DesktopSuperAgent

            outcome = DesktopSuperAgent(
                task_manager=agent_task_manager,
                diagnostics=connector_diagnostics,
                deliver=deliver_agent_sync,
            ).run(
                task_id=task.task_id,
                conversation_id=conversation_id,
                prompt=prompt,
                compiled_prompt=compiled_prompt,
                attachments=attachments,
                response_language=response_language,
                execution_policy=desktop_execution_policy,
            )
            return outcome.reply
        if agent_id.startswith("mcp:"):
            from agent_execution_harness import (
                AgentExecutionHarness,
                execution_contract,
                estimate_text_tokens,
                finalize_task_artifacts,
                replan_instruction,
            )
            from desktop_mcp import desktop_mcp_registry

            connection_id = agent_id.split(":", 1)[1]
            connection = desktop_mcp_registry().get(connection_id)
            label = connection.name if connection else connection_id
            harness = AgentExecutionHarness(
                task.task_id,
                agent_id,
                prompt,
                attachments=attachments,
                policy=desktop_execution_policy,
            )
            current_prompt = f"{prompt.rstrip()}\n\n{execution_contract(harness.policy)}"
            while True:
                attempt = harness.begin_attempt()
                harness.account_usage(
                    input_tokens=estimate_text_tokens(current_prompt),
                    estimated=True,
                )
                agent_task_manager.add_event(
                    task.task_id,
                    "act",
                    f"Using {label}",
                    event_id=f"execution-harness:mcp:{attempt}",
                    status="running",
                    metadata={
                        "task_kind": harness.policy.task_kind.value,
                        "reasoning_effort": harness.policy.reasoning_effort.value,
                        "attempt": attempt,
                    },
                )
                try:
                    result = desktop_mcp_registry().invoke_prompt(
                        connection_id,
                        current_prompt,
                        process_callback=lambda process: agent_task_manager.register_process(
                            task.task_id,
                            process,
                        ),
                        explicit_user_selection=True,
                        audit_context={
                            "caller_id": "galaxyssi.desktop.explicit_mcp",
                            "task_id": task.task_id,
                            "conversation_id": conversation_id,
                        },
                        tool_call_callback=lambda event: agent_task_manager.add_event(
                            task.task_id,
                            "mcp",
                            (
                                f"{event.get('connection_name') or label} · "
                                f"{event.get('tool_name') or 'unknown'}"
                            ),
                            event_id=(
                                f"mcp-tool:{event.get('invocation_id') or attempt}"
                            ),
                            status=(
                                "completed"
                                if event.get("status") == "succeeded"
                                else "failed"
                                if event.get("status") in {"failed", "denied"}
                                else "running"
                            ),
                            metadata=event,
                        ),
                    )
                    reply = str(result.get("result") or "").strip()
                    if not reply:
                        raise RuntimeError(f"{label} returned no result")
                    harness.account_usage(
                        output_tokens=estimate_text_tokens(reply),
                        estimated=True,
                    )
                    harness.progress("observe")
                    finalization = finalize_task_artifacts(
                        task.task_id,
                        prompt,
                        agent_id,
                        allow_device_install=True,
                    )
                    if finalization.verification.get("status") == "passed":
                        harness.progress(
                            "verify",
                            artifacts=finalization.verification,
                        )
                        agent_task_manager.add_event(
                            task.task_id,
                            "verify",
                            f"Verified {label}'s result",
                            event_id=f"execution-harness:mcp-verify:{attempt}",
                            metadata={
                                "duration_ms": int(result.get("duration_ms") or 0),
                                "artifact_verification": finalization.verification,
                            },
                        )
                        harness.progress("finalize")
                        return reply
                    failure = (
                        "Required artifact verification failed: "
                        + json.dumps(
                            finalization.verification,
                            ensure_ascii=False,
                            separators=(",", ":"),
                        )[:1_000]
                    )
                except Exception as exc:
                    failure = str(exc) or f"{label} failed"
                can_replan, same_failure_attempt = harness.record_failure(
                    "mcp_execution",
                    failure,
                )
                agent_task_manager.add_event(
                    task.task_id,
                    "observe",
                    f"{label} did not complete the task",
                    event_id=f"execution-harness:mcp-observe:{attempt}",
                    status="failed",
                    detail=failure[:1_000],
                    metadata={"same_failure_attempt": same_failure_attempt},
                )
                if not can_replan:
                    raise RuntimeError(failure)
                agent_task_manager.add_event(
                    task.task_id,
                    "replan",
                    "Replanning from the latest MCP checkpoint",
                    event_id=f"execution-harness:mcp-replan:{attempt}",
                    status="running",
                )
                current_prompt = (
                    f"{prompt.rstrip()}\n\n"
                    f"{replan_instruction(harness.policy, failure=failure, attempt=attempt)}"
                )
        result = deliver_agent_sync(
            agent_id,
            compiled_prompt,
            task_id=task.task_id,
            conversation_id=conversation_id,
            source_message_id=task.source_message_id,
            return_path="desktop-ui",
            response_language=response_language,
            execution_prompt=prompt,
            execution_policy=desktop_execution_policy.public(),
        )
        return str(result.get("reply") or "")

    task = agent_task_manager.create(
        agent_id=agent_id,
        contact_id=agent_id,
        source_message_id=f"desktop:{task_id}",
        prompt=prompt or "Attached files",
        runner=runner,
        on_event=lambda _event: None,
        task_id=task_id,
        conversation_id=conversation_id,
        attachments=attachments,
        retry_of=str(req.retry_of or ""),
        attempt=max(1, int(req.attempt or 1)),
        execution_prompt=prompt,
        execution_policy=desktop_execution_policy.public(),
    )
    payload = task.public(include_prompt=True)
    payload["attachments"] = attachments
    return payload


@app.get("/api/desktop/tasks")
def api_list_desktop_tasks(request: Request, limit: int = Query(100)):
    require_loopback(request)
    return {"tasks": _desktop_task_rows(limit)}


@app.get("/api/desktop/tasks/{task_id}")
def api_get_desktop_task(task_id: str, request: Request):
    require_loopback(request)
    task = agent_task_manager.get(task_id)
    if task is not None and task.source_message_id.startswith("desktop:"):
        return task.public(include_prompt=True)
    evolution_manager = _desktop_evolution_manager()
    evolution_task = evolution_manager.store.get(task_id)
    if evolution_task is not None:
        timeline_item = _desktop_evolution_timeline_item(evolution_manager, evolution_task)
        if timeline_item is not None:
            return timeline_item
    raise HTTPException(status_code=404, detail=api_error("desktop_task_not_found"))


@app.get("/api/desktop/tasks/{task_id}/output")
def api_get_desktop_task_output(
    task_id: str,
    request: Request,
    offset: int = Query(0),
    limit: int = Query(2),
):
    require_loopback(request)
    task = agent_task_manager.public_preview(task_id, include_prompt=True)
    if task is None or not str(task.get("source_message_id") or "").startswith("desktop:"):
        raise HTTPException(status_code=404, detail=api_error("desktop_task_not_found"))
    try:
        page = agent_task_manager.output_page(task_id, offset=offset, limit=limit)
    except ValueError as exc:
        raise HTTPException(
            status_code=409,
            detail=api_error("desktop_task_output_integrity_failed", str(exc)),
        ) from exc
    if page is None:
        raise HTTPException(status_code=404, detail=api_error("desktop_task_not_found"))
    return page


@app.post("/api/desktop/tasks/{task_id}/cancel")
def api_cancel_desktop_task(task_id: str, request: Request):
    require_loopback(request)
    task = agent_task_manager.get(task_id)
    if task is None or not task.source_message_id.startswith("desktop:"):
        evolution_manager = _desktop_evolution_manager()
        evolution_task = evolution_manager.store.get(task_id)
        if evolution_task is None:
            raise HTTPException(status_code=404, detail=api_error("desktop_task_not_found"))
        cancelled = evolution_manager.cancel(task_id)
        return {
            "task": _desktop_evolution_timeline_item(
                evolution_manager,
                cancelled,
            ),
        }
    try:
        _interrupt_desktop_task(task)
    except Exception:
        pass
    cancelled = agent_task_manager.cancel(task_id)
    return {"task": cancelled.public(include_prompt=True) if cancelled else None}


def _local_desktop_run_control(
    task_id: str,
    tool_id: str,
    req: DesktopTaskControlReq,
) -> dict:
    from desktop_run_control import DesktopRunControlError, desktop_run_control

    try:
        return desktop_run_control().execute(
            tool_id,
            {
                "task_id": task_id,
                "lease_seconds": req.lease_seconds,
                "reason": req.reason,
            },
            {
                "controller_id": "desktop-ui",
                "controller_name": "Desktop user",
                "controller_platform": "desktop",
            },
        )
    except DesktopRunControlError as exc:
        status_code = 404 if exc.code == "task_not_found" else 409
        raise HTTPException(
            status_code=status_code,
            detail=api_error(exc.code, str(exc)),
        ) from exc


@app.post("/api/desktop/tasks/{task_id}/pause")
def api_pause_desktop_task(
    task_id: str,
    req: DesktopTaskControlReq,
    request: Request,
):
    require_loopback(request)
    from desktop_run_control import TASK_PAUSE, TASK_RELEASE

    task = agent_task_manager.get(task_id)
    tool_id = TASK_RELEASE if task is not None and task.status == "takeover" else TASK_PAUSE
    result = _local_desktop_run_control(task_id, tool_id, req)
    current = agent_task_manager.get(task_id)
    return {
        "control": result,
        "task": current.public(include_prompt=True) if current else None,
    }


@app.post("/api/desktop/tasks/{task_id}/takeover")
def api_takeover_desktop_task(
    task_id: str,
    req: DesktopTaskControlReq,
    request: Request,
):
    require_loopback(request)
    from desktop_run_control import TASK_TAKEOVER

    result = _local_desktop_run_control(task_id, TASK_TAKEOVER, req)
    current = agent_task_manager.get(task_id)
    return {
        "control": result,
        "task": current.public(include_prompt=True) if current else None,
    }


@app.post("/api/desktop/tasks/{task_id}/continue")
def api_continue_desktop_task(
    task_id: str,
    req: DesktopTaskControlReq,
    request: Request,
):
    require_loopback(request)
    from desktop_run_control import TASK_CONTINUE

    result = _local_desktop_run_control(task_id, TASK_CONTINUE, req)
    current = agent_task_manager.get(task_id)
    return {
        "control": result,
        "task": current.public(include_prompt=True) if current else None,
    }


@app.post("/api/desktop/tasks/{task_id}/retry")
def api_retry_desktop_task(task_id: str, request: Request):
    require_loopback(request)
    task = agent_task_manager.get(task_id)
    if task is None or not task.source_message_id.startswith("desktop:"):
        evolution_manager = _desktop_evolution_manager()
        evolution_task = evolution_manager.store.get(task_id)
        if evolution_task is None:
            raise HTTPException(status_code=404, detail=api_error("desktop_task_not_found"))
        if evolution_task.status not in {"blocked", "failed", "cancelled"}:
            raise HTTPException(status_code=409, detail=api_error("desktop_task_not_retryable"))
        restarted = evolution_manager.start(task_id)
        return _desktop_evolution_timeline_item(evolution_manager, restarted)
    if task.status not in TERMINAL_STATES or task.status == "completed":
        raise HTTPException(status_code=409, detail=api_error("desktop_task_not_retryable"))

    return _restart_desktop_task(task, request)


def _desktop_task_attachment_sources(task) -> list[str]:
    from task_workspace import task_workspace

    root = task_workspace(task.task_id).resolve()
    relative_paths = list(task.attachments or [])
    if not relative_paths:
        relative_paths = [
            path.relative_to(root).as_posix()
            for path in sorted((root / "downloads" / "input").glob("*"))
            if path.is_file()
        ][:12]
    sources: list[str] = []
    for relative in relative_paths:
        candidate = (root / Path(relative)).resolve()
        try:
            candidate.relative_to(root)
        except ValueError:
            continue
        if candidate.is_file():
            sources.append(str(candidate))
    return sources


def _restart_desktop_task(
    task,
    request: Request,
    *,
    agent_id: str = "",
    execution_mode: str = "",
):
    return api_start_desktop_task(
        DesktopTaskStartReq(
            prompt=task.prompt,
            agent_id=agent_id or task.agent_id,
            execution_mode=execution_mode or str(
                (task.execution_policy or {}).get("execution_mode")
                or "auto_complete"
            ),
            task_budget=dict(
                (task.execution_policy or {}).get("task_budget")
                or {}
            ),
            conversation_id=task.conversation_id,
            attachments=_desktop_task_attachment_sources(task),
            retry_of=task.retry_of or task.task_id,
            attempt=max(2, task.attempt + 1),
        ),
        request,
    )


@app.post("/api/desktop/tasks/{task_id}/recover")
def api_recover_desktop_task(
    task_id: str,
    req: DesktopTaskRecoveryReq,
    request: Request,
):
    require_loopback(request)
    task = agent_task_manager.get(task_id)
    if (
        task is None
        or not task.source_message_id.startswith("desktop:")
    ):
        raise HTTPException(status_code=404, detail=api_error("desktop_task_not_found"))
    if task.status not in TERMINAL_STATES or task.status == "completed":
        raise HTTPException(status_code=409, detail=api_error("desktop_task_not_recoverable"))

    from agent_failure_recovery import (
        AgentFailureRecoveryAction,
        failure_diagnostic,
        recovery_choices,
    )

    action = str(req.action or "").strip().lower()
    try:
        recovery_action = AgentFailureRecoveryAction(action)
    except ValueError as exc:
        raise HTTPException(
            status_code=400,
            detail=api_error("desktop_recovery_action_invalid"),
        ) from exc

    agents = list(connector_diagnostics(quick=True).get("agents") or [])
    task_payload = task.public(include_prompt=True)
    choices = recovery_choices(task_payload, agents)
    selected = next(
        (
            choice
            for choice in choices
            if choice.get("action") == recovery_action.value
        ),
        None,
    )
    if selected is None or not selected.get("enabled"):
        raise HTTPException(
            status_code=409,
            detail=api_error(
                "desktop_recovery_action_unavailable",
                str((selected or {}).get("reason") or "Recovery action is unavailable."),
            ),
        )

    if recovery_action == AgentFailureRecoveryAction.DIAGNOSTICS:
        return {
            "action": recovery_action.value,
            "task": task_payload,
            "diagnostic": failure_diagnostic(task_payload, agents),
        }
    if recovery_action == AgentFailureRecoveryAction.SWITCH_AGENT:
        candidates = list(selected.get("candidate_agent_ids") or [])
        requested_agent = str(req.agent_id or "").strip().lower()
        target_agent = (
            requested_agent
            if requested_agent and requested_agent in candidates
            else (candidates[0] if candidates else "")
        )
        if not target_agent:
            raise HTTPException(
                status_code=409,
                detail=api_error(
                    "desktop_recovery_agent_unavailable",
                    "No alternative Agent is currently available.",
                ),
            )
        return {
            "action": recovery_action.value,
            "task": _restart_desktop_task(
                task,
                request,
                agent_id=target_agent,
            ),
        }
    if recovery_action == AgentFailureRecoveryAction.DEGRADE:
        return {
            "action": recovery_action.value,
            "task": _restart_desktop_task(
                task,
                request,
                execution_mode="plan_only",
            ),
        }
    return {
        "action": recovery_action.value,
        "task": _restart_desktop_task(task, request),
    }


@app.delete("/api/desktop/conversations/{conversation_id}")
def api_delete_desktop_conversation(conversation_id: str, request: Request):
    require_loopback(request)
    deleted = agent_task_manager.delete_conversation(conversation_id)
    from conversation_context import conversation_summary_store

    conversation_summary_store().delete_conversation(conversation_id)
    return {"conversation_id": conversation_id, "deleted_task_ids": deleted}

@app.get("/api/messages/{contact_id}")
def get_messages(contact_id: str, limit: int = Query(50), offset: int = Query(0),
                 db: Session = Depends(get_session)):
    msgs = db.query(Message).filter(
        Message.contact_id == contact_id
    ).order_by(Message.id.desc()).offset(offset).limit(limit).all()
    return [{
        "id": m.id, "sender": m.sender.value, "content": m.content,
        "type": m.type.value, "created_at": m.created_at.isoformat(),
    } for m in reversed(msgs)]

class SendMessageReq(BaseModel):
    contact_id: str
    content: str
    type: str = "text"

@app.post("/api/messages")
def send_message(req: SendMessageReq, db: Session = Depends(get_session)):
    msg = Message(contact_id=req.contact_id, sender=SenderType.SELF,
                  content=req.content, type=MessageType(req.type))
    db.add(msg)
    db.commit()
    db.refresh(msg)

    contact = db.query(Contact).filter(Contact.id == req.contact_id).first()
    if contact:
        contact.preview = req.content[:50]
        contact.unread = 0
    db.commit()

    # Agent replies are routed through GalaxySSI Desktop Connector.
    if req.contact_id not in {"system", "me"}:
        import threading
        def reply_later():
            import time
            time.sleep(0.5)
            reply = ask_agent_sync(req.contact_id, req.content)
            db2 = get_session()
            try:
                reply_msg = Message(contact_id=req.contact_id, sender=SenderType.OTHER,
                                    content=reply, type=MessageType.TEXT)
                db2.add(reply_msg)
                contact = db2.query(Contact).filter(Contact.id == req.contact_id).first()
                if contact:
                    contact.preview = reply[:80]
                db2.commit()
            finally:
                db2.close()
        threading.Thread(target=reply_later, daemon=True).start()

    return {"id": msg.id, "sender": "self", "content": msg.content}

@app.post("/api/contacts/{contact_id}/read")
def mark_read(contact_id: str, db: Session = Depends(get_session)):
    contact = db.query(Contact).filter(Contact.id == contact_id).first()
    if contact:
        contact.unread = 0
        db.commit()
    return {"ok": True}

# Static files.
from fastapi.responses import FileResponse
from fastapi.responses import HTMLResponse
import os

frontend_path = Path(__file__).parent.parent / "frontend" / "index.html"

@app.get("/")
def serve_index():
    return FileResponse(str(frontend_path))

@app.get("/galaxyssi/verify")
def galaxyssi_verify_qr(request: Request):
    require_loopback(request)
    pairing = galaxyssi_pairing_qr()
    image_data_url = pairing["image_data_url"]
    short_hash = pairing["fingerprint"]
    pairing_type = pairing["pairing_type"]
    agent_count = pairing["agent_count"]
    return HTMLResponse(f"""<!doctype html>
<html lang="en">
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width,initial-scale=1">
  <title>GalaxySSI Secure Pairing</title>
  <style>
    body {{ margin: 0; font-family: system-ui, -apple-system, Segoe UI, sans-serif; background: #f6f6f6; color: #111; }}
    main {{ min-height: 100vh; display: grid; place-items: center; padding: 24px; box-sizing: border-box; }}
    section {{ width: min(440px, 100%); text-align: center; }}
    img {{ width: min(320px, 86vw); height: auto; background: #fff; padding: 14px; border: 1px solid #ddd; }}
    h1 {{ font-size: 24px; margin: 0 0 12px; }}
    p {{ color: #555; line-height: 1.55; }}
    code {{ display: inline-block; margin-top: 8px; padding: 8px 10px; background: #fff; border: 1px solid #ddd; border-radius: 6px; }}
  </style>
</head>
<body>
  <main>
    <section>
      <h1>GalaxySSI Secure Pairing</h1>
      <p>Scan this QR code in the GalaxySSI mobile app to pair this desktop connector.</p>
      <img alt="GalaxySSI pairing QR" data-pairing-type="{pairing_type}" data-pairing-route="/galaxyssi/verify" data-agent-count="{agent_count}" src="{image_data_url}">
      <p>PC identity hash</p>
      <code>{short_hash}</code>
    </section>
  </main>
</body>
</html>""")

@app.get("/{filename:path}")
def serve_static(filename: str):
    if filename.endswith("/verify"):
        raise HTTPException(status_code=404, detail="Not found")
    file_path = Path(__file__).parent.parent / "frontend" / filename
    if file_path.exists() and file_path.is_file():
        return FileResponse(str(file_path))
    return FileResponse(str(frontend_path))

# ── WebSocket ──

@app.websocket("/ws/desktop/tasks")
async def desktop_task_stream(ws: WebSocket):
    host = str(ws.client.host if ws.client else "")
    if not _is_loopback_host(host) or not _desktop_task_stream_authorized(ws):
        await ws.close(code=1008)
        return

    await ws.accept(subprotocol="galaxyssi-task-stream")
    loop = asyncio.get_running_loop()
    updates: asyncio.Queue[dict[str, Any]] = asyncio.Queue(maxsize=64)
    evolution_manager = _desktop_evolution_manager()
    from peer_chat_store import peer_chat_store

    peers = peer_chat_store()

    def offer_update(payload: dict[str, Any]) -> None:
        def offer() -> None:
            if updates.full():
                try:
                    updates.get_nowait()
                except asyncio.QueueEmpty:
                    pass
            updates.put_nowait(payload)

        try:
            loop.call_soon_threadsafe(offer)
        except RuntimeError:
            pass

    def enqueue_agent_task(snapshot: dict) -> None:
        if not str(snapshot.get("source_message_id") or "").startswith("desktop:"):
            return
        task = agent_task_manager.get(str(snapshot.get("task_id") or ""))
        if task is None or not task.source_message_id.startswith("desktop:"):
            return
        preview = agent_task_manager.public_preview(task.task_id, include_prompt=True)
        if preview is not None:
            offer_update(preview)

    def enqueue_evolution_task(event: dict) -> None:
        task_id = str((event.get("task") or {}).get("task_id") or "")
        if not task_id:
            return
        try:
            evolution_task = evolution_manager.require(task_id)
            payload = _desktop_evolution_timeline_item(
                evolution_manager,
                evolution_task,
                live_event=event,
            )
        except Exception:
            return
        if payload is not None:
            offer_update(payload)

    def enqueue_peer_message(message: dict) -> None:
        offer_update({"type": "desktop_peer_message", "message": message})

    task_subscription_id = agent_task_manager.subscribe(enqueue_agent_task)
    evolution_subscription_id = evolution_manager.subscribe(enqueue_evolution_task)
    peer_subscription_id = peers.subscribe(enqueue_peer_message)
    try:
        tasks = _desktop_task_rows(500, evolution_manager)
        await ws.send_json({
            "type": "desktop_tasks_snapshot",
            "tasks": tasks,
            "peer_messages": peers.list_messages(limit=2_000),
        })
        while True:
            try:
                task = await asyncio.wait_for(updates.get(), timeout=20.0)
            except asyncio.TimeoutError:
                await ws.send_json({
                    "type": "heartbeat",
                    "timestamp": int(datetime.now(timezone.utc).timestamp() * 1000),
                })
                continue
            if task.get("type") == "desktop_peer_message":
                await ws.send_json(task)
            else:
                await ws.send_json({"type": "desktop_task_update", "task": task})
    except WebSocketDisconnect:
        pass
    except Exception as exc:
        log.info("Desktop task stream closed: %s", exc)
    finally:
        agent_task_manager.unsubscribe(task_subscription_id)
        evolution_manager.unsubscribe(evolution_subscription_id)
        peers.unsubscribe(peer_subscription_id)


@app.websocket("/ws/{contact_id}")
async def websocket_endpoint(ws: WebSocket, contact_id: str):
    await manager.connect(contact_id, ws)
    log.info(f"WS connected: {contact_id}")

    try:
        while True:
            raw = await ws.receive_text()
            data = json.loads(raw)
            action = data.get("action", "message")

            if action == "typing":
                await manager.broadcast(contact_id, {"action": "typing", "contact_id": contact_id})
                continue

            if action == "ping":
                await ws.send_text(json.dumps({"action": "pong"}))
                continue

            # action == "message"
            content = data.get("content", "").strip()
            if not content:
                continue

            # Persist the user message.
            db = get_session()
            try:
                msg = Message(contact_id=contact_id, sender=SenderType.SELF,
                              content=content, type=MessageType.TEXT)
                db.add(msg)
                contact = db.query(Contact).filter(Contact.id == contact_id).first()
                if contact:
                    contact.preview = content[:50]
                db.commit()

                await manager.broadcast(contact_id, {
                    "action": "message", "sender": "self",
                    "content": content, "contact_id": contact_id,
                    "id": msg.id,
                })
            finally:
                db.close()

            # Agent replies are routed through GalaxySSI Desktop Connector.
            if contact_id not in {"system", "me"}:
                await manager.broadcast(contact_id, {"action": "typing", "contact_id": contact_id})
                reply = await asyncio.to_thread(ask_agent_sync, contact_id, content)

                db = get_session()
                try:
                    reply_msg = Message(contact_id=contact_id, sender=SenderType.OTHER,
                                        content=reply, type=MessageType.TEXT)
                    db.add(reply_msg)
                    contact = db.query(Contact).filter(Contact.id == contact_id).first()
                    if contact:
                        contact.preview = reply[:50]
                    db.commit()

                    await manager.broadcast(contact_id, {
                        "action": "message", "sender": "other",
                        "content": reply, "contact_id": contact_id,
                        "id": reply_msg.id,
                    })
                finally:
                    db.close()

    except WebSocketDisconnect:
        manager.disconnect(contact_id, ws)
        log.info(f"WS disconnected: {contact_id}")
    except Exception as e:
        log.error(f"WS error {contact_id}: {e}")
        manager.disconnect(contact_id, ws)

if __name__ == "__main__":
    import uvicorn
    uvicorn.run(app, host="127.0.0.1", port=8765)
