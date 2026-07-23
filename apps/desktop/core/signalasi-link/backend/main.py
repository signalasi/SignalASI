"""SignalASI Link backend - FastAPI + WebSocket."""
import asyncio
import json
import logging
import os
import shutil
import uuid
from typing import Any
from datetime import datetime, timezone
from contextlib import asynccontextmanager

from fastapi import FastAPI, WebSocket, WebSocketDisconnect, Depends, Query, HTTPException, Header, Request
from fastapi.middleware.cors import CORSMiddleware
from sqlalchemy.orm import Session
from pydantic import BaseModel, Field

from models import init_db, get_session, Contact, Message, ContactType, MessageType, SenderType
from agent_gateway import (
    ask_agent_sync,
    connector_diagnostics,
    connector_self_test,
    deliver_agent_sync,
    desktop_agent_provider,
    list_agents,
    recent_agent_execution_log,
    reset_inactive_agent_runtime,
)
from agent_config import load_config, save_config
from api_response import api_error
from agent_task_manager import TERMINAL_STATES, agent_task_manager
from backend_instance_lock import BackendInstanceLock

logging.basicConfig(level=logging.INFO)
log = logging.getLogger("signalasi")

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

def signalasi_pairing_payload(include_agents: bool = False) -> dict:
    from pairing_state import new_pairing_session, server_route_id
    from link_protocol import LinkTopics, PROTOCOL_NAME, PROTOCOL_VERSION
    from signalasi_client import get_signal_verification_payload

    payload = get_signal_verification_payload()
    route_id = server_route_id()
    payload["protocol"] = PROTOCOL_NAME
    payload["version"] = PROTOCOL_VERSION
    payload["role"] = "server"
    payload["server_route_id"] = route_id
    payload["pairing_topic"] = LinkTopics(route_id).pairing
    pairing = new_pairing_session()
    payload["pairing_token"] = pairing["token"]
    payload["pairing_secret"] = pairing["secret"]
    from desktop_control import desktop_control_manager

    control_offer = desktop_control_manager().create_offer(pairing["token"])
    if control_offer is not None:
        payload["desktop_control_authorization"] = control_offer
    if include_agents:
        from mqtt_bridge import mobile_connector_agents
        payload["connector_agents"] = mobile_connector_agents()
    return payload


def signalasi_pairing_qr() -> dict:
    import base64
    import io
    import qrcode
    from mqtt_bridge import mobile_connector_agents

    payload = signalasi_pairing_payload()
    qr = qrcode.QRCode(error_correction=qrcode.constants.ERROR_CORRECT_L, border=2, box_size=10)
    qr.add_data(json.dumps(payload, ensure_ascii=False, separators=(",", ":")))
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
    }

@asynccontextmanager
async def lifespan(app: FastAPI):
    file_server_process = None
    external_services_enabled = os.environ.get("SIGNALASI_DISABLE_EXTERNAL_SERVICES") != "1"
    instance_lock = BackendInstanceLock() if external_services_enabled else None
    if instance_lock is not None:
        instance_lock.acquire()
    init_db()
    if external_services_enabled:
        # Start the local Signal Protocol sidecar.
        try:
            import signalasi_client
            signalasi_client.start_signal_sidecar()
            log.info("Signal sidecar started (:%s)", signalasi_client.SIDECAR_PORT)
        except Exception as e:
            log.warning(f"Signal sidecar start failed: {e}")
        # Start the MQTT bridge in a background thread.
        try:
            from mqtt_bridge import start_background
            start_background()
            log.info("MQTT bridge started")
        except Exception as e:
            log.warning(f"MQTT start failed: {e}")
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
    log.info("SignalASI Link backend started")
    try:
        yield
    finally:
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
                from signalasi_client import stop_signal_sidecar
                stop_signal_sidecar()
            except Exception as exc:
                log.warning("Signal sidecar shutdown failed: %s", exc)
        if instance_lock is not None:
            instance_lock.release()

app = FastAPI(title="SignalASI Link", lifespan=lifespan)
app.add_middleware(
    CORSMiddleware,
    allow_origins=["http://127.0.0.1:8765", "http://localhost:8765", "null"],
    allow_methods=["GET", "POST"],
    allow_headers=["Content-Type", "X-SignalASI-Token"],
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


@app.get("/health")
def api_health():
    return {
        "status": "ok",
        "protocol": "SignalASI Link Protocol",
        "connector": "SignalASI Desktop",
    }

@app.get("/api/agents/execution-log")
def api_agent_execution_log(limit: int = Query(50)):
    return recent_agent_execution_log(limit)

@app.get("/api/pairing/status")
def api_pairing_status():
    from pairing_state import pairing_status
    return pairing_status()

def require_loopback(request: Request) -> None:
    host = str(request.client.host if request.client else "")
    if host not in {"127.0.0.1", "::1", "localhost", "testclient"}:
        raise HTTPException(status_code=403, detail="Pairing payload is available only on the local Desktop")


@app.get("/api/pairing/payload")
def api_pairing_payload(request: Request):
    require_loopback(request)
    return signalasi_pairing_payload()


@app.get("/api/pairing/qr")
def api_pairing_qr(request: Request):
    require_loopback(request)
    return signalasi_pairing_qr()

@app.post("/api/pairing/clear")
def api_pairing_clear(client_route_id: str = Query("")):
    from pairing_state import clear_pairing_state, get_client, list_clients, pairing_status
    from mqtt_bridge import publish_pairing_revoked
    from signalasi_client import remove_peer_signal_session
    from desktop_control import desktop_control_manager
    targets = [get_client(client_route_id)] if client_route_id else list_clients()
    targets = [target for target in targets if target]
    revoke = publish_pairing_revoked(reason="forgotten_by_desktop", client_route_id=client_route_id)
    removed_sessions = []
    for target in targets:
        desktop_control_manager().revoke_for_client(
            target["client_route_id"], "pairing_revoked"
        )
        try:
            remove_peer_signal_session(target["signal_name"], int(target.get("signal_device_id") or 1))
            removed_sessions.append(target["client_route_id"])
        except Exception as exc:
            log.warning("Signal session removal failed client=%s: %s", target["client_route_id"], exc)
    clear_pairing_state(client_route_id)
    status = pairing_status()
    status["revoke"] = revoke
    status["removed_sessions"] = removed_sessions
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
    custom_agent: dict[str, str] = {}
    custom_agents: list[dict[str, str]] = []

@app.post("/api/agents/config")
def api_save_agent_config(req: AgentConfigReq):
    saved = save_config(req.dict())
    reset_inactive_agent_runtime()
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
    conversation_id: str = ""
    source_message_id: str = ""
    return_path: str = ""
    protocol: str = "1.0"
    required_features: list[str] = []


class DesktopNativeToolInvokeReq(BaseModel):
    tool_id: str
    tool_version: str = "1.0.0"
    arguments: dict = {}
    invocation_id: str = ""
    task_id: str = ""
    conversation_id: str = ""
    workspace_id: str = ""
    idempotency_key: str = ""
    confirmation: dict | None = None


class DesktopMemoryReq(BaseModel):
    content: str
    kind: str = "fact"
    importance: float = 0.6


class DesktopSkillReq(BaseModel):
    id: str
    name: str
    description: str = ""
    triggers: list[str] = Field(default_factory=list)
    instructions: str
    enabled: bool = True


class DesktopSkillEnabledReq(BaseModel):
    enabled: bool = True


class DesktopMcpReq(BaseModel):
    id: str
    name: str
    command: str
    default_tool: str = ""
    triggers: list[str] = Field(default_factory=list)
    enabled: bool = True
    auto_invoke: bool = False
    timeout_seconds: int = 20


class DesktopControlSettingsReq(BaseModel):
    enabled: bool | None = None
    require_unlocked: bool | None = None


@app.get("/api/agent-adapters")
def api_agent_adapters(request: Request):
    require_loopback(request)
    provider = desktop_agent_provider()
    return {
        "agents": provider.enumerate(),
        "recoverable_runs": [item.public() for item in provider.recover()],
    }


@app.get("/api/desktop-tools")
def api_desktop_native_tools(request: Request):
    require_loopback(request)
    from desktop_native_tools import desktop_native_tool_registry

    return desktop_native_tool_registry().manifest()


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
            "idempotency_key": req.idempotency_key,
            "confirmation": req.confirmation,
            "caller_id": "signalasi.desktop.loopback",
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
    action: str,
) -> dict:
    from desktop_control import DesktopControlError, desktop_control_manager
    from mqtt_bridge import publish_desktop_control_authorization_changed

    manager = desktop_control_manager()
    try:
        if action == "approve":
            authorization = manager.approve(authorization_id)
        elif action == "reject":
            authorization = manager.reject(authorization_id)
        elif action == "revoke":
            authorization = manager.revoke(authorization_id)
        else:
            raise HTTPException(status_code=400, detail=api_error("desktop_control_action_invalid"))
    except DesktopControlError as exc:
        status_code = 404 if exc.code == "authorization_not_found" else 409
        raise HTTPException(
            status_code=status_code,
            detail=api_error(exc.code, message=str(exc)),
        ) from exc
    publish_desktop_control_authorization_changed(authorization, reason=action)
    return manager.status(include_revoked=True)


@app.post("/api/desktop-control/authorizations/{authorization_id}/approve")
def api_desktop_control_approve(authorization_id: str, request: Request):
    require_loopback(request)
    return _desktop_control_authorization_action(authorization_id, "approve")


@app.post("/api/desktop-control/authorizations/{authorization_id}/reject")
def api_desktop_control_reject(authorization_id: str, request: Request):
    require_loopback(request)
    return _desktop_control_authorization_action(authorization_id, "reject")


@app.post("/api/desktop-control/authorizations/{authorization_id}/revoke")
def api_desktop_control_revoke(authorization_id: str, request: Request):
    require_loopback(request)
    return _desktop_control_authorization_action(authorization_id, "revoke")


@app.get("/api/desktop-memory")
def api_desktop_memory(request: Request, query: str = Query(""), limit: int = Query(100)):
    require_loopback(request)
    from desktop_memory import desktop_memory_store

    store = desktop_memory_store()
    rows = store.search(query, limit=limit) if query.strip() else store.list(limit=limit)
    return {"memories": rows, "stats": store.stats()}


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
    )
    if memory is None:
        raise HTTPException(status_code=400, detail=api_error("desktop_memory_rejected"))
    return memory


@app.delete("/api/desktop-memory/{memory_id}")
def api_forget_desktop_memory(memory_id: str, request: Request):
    require_loopback(request)
    from desktop_memory import desktop_memory_store

    return {"id": memory_id, "forgotten": desktop_memory_store().forget(memory_id)}


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

    return {"connections": desktop_mcp_registry().list(include_command=True)}


@app.post("/api/desktop-mcp")
def api_save_desktop_mcp(req: DesktopMcpReq, request: Request):
    require_loopback(request)
    from desktop_mcp import desktop_mcp_registry

    try:
        return desktop_mcp_registry().upsert(req.model_dump())
    except ValueError as exc:
        raise HTTPException(status_code=400, detail=api_error("desktop_mcp_invalid", str(exc))) from exc


@app.post("/api/desktop-mcp/{connection_id}/probe")
def api_probe_desktop_mcp(connection_id: str, request: Request):
    require_loopback(request)
    from desktop_mcp import desktop_mcp_registry

    try:
        return desktop_mcp_registry().probe(connection_id)
    except KeyError as exc:
        raise HTTPException(status_code=404, detail=api_error("desktop_mcp_not_found")) from exc


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
            conversation_id=req.conversation_id,
            source_message_id=req.source_message_id,
            return_path=req.return_path,
            protocol=req.protocol,
            required_features=tuple(req.required_features),
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
def api_agent_push(req: AgentPushReq, x_signalasi_token: str = Header(default="")):
    from push_auth import verify_agent_push_token
    from mqtt_bridge import publish_agent_push_message

    token = x_signalasi_token or req.secret
    if not verify_agent_push_token(token):
        raise HTTPException(
            status_code=401,
            detail=api_error("agent_push_token_invalid", "Invalid SignalASI Agent push token."),
        )
    return publish_agent_push_message(req.contact_id, req.content, req.source, req.client_route_id, req.broadcast)

class AgentTaskStartReq(BaseModel):
    contact_id: str
    prompt: str
    source_message_id: str = ""
    task_id: str = ""
    client_route_id: str = ""

@app.post("/api/agent/tasks")
def api_start_agent_task(req: AgentTaskStartReq, x_signalasi_token: str = Header(default="")):
    from push_auth import verify_agent_push_token
    from mqtt_bridge import start_agent_task
    if not verify_agent_push_token(x_signalasi_token):
        raise HTTPException(status_code=401, detail=api_error("agent_push_token_invalid", "Invalid SignalASI Agent push token."))
    return start_agent_task(req.contact_id, req.prompt, req.source_message_id, req.task_id, req.client_route_id)

@app.get("/api/agent/tasks")
def api_list_agent_tasks(limit: int = Query(100)):
    return {"tasks": agent_task_manager.list(limit=limit)}

@app.get("/api/agent/tasks/{task_id}")
def api_get_agent_task(task_id: str):
    task = agent_task_manager.get(task_id)
    if task is None:
        raise HTTPException(status_code=404, detail=api_error("agent_task_not_found"))
    return task.public()

@app.post("/api/agent/tasks/{task_id}/republish")
def api_republish_agent_task(task_id: str, request: Request):
    require_loopback(request)
    from mqtt_bridge import republish_agent_task_result

    result = republish_agent_task_result(task_id)
    if not result.get("ok") and result.get("error") == "agent_task_not_found":
        raise HTTPException(status_code=404, detail=result)
    return result

@app.post("/api/agent/tasks/{task_id}/cancel")
def api_cancel_agent_task(task_id: str, x_signalasi_token: str = Header(default="")):
    from push_auth import verify_agent_push_token
    from mqtt_bridge import publish_agent_task_event
    if not verify_agent_push_token(x_signalasi_token):
        raise HTTPException(status_code=401, detail=api_error("agent_push_token_invalid", "Invalid SignalASI Agent push token."))
    task = agent_task_manager.cancel(task_id, publish_agent_task_event)
    if task is None:
        raise HTTPException(status_code=404, detail=api_error("agent_task_not_found"))
    return {"task": task.public()}


class DesktopTaskStartReq(BaseModel):
    prompt: str
    agent_id: str = "auto"
    conversation_id: str = ""
    attachments: list[str] = Field(default_factory=list)
    retry_of: str = ""
    attempt: int = 1


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
    diagnostics = connector_diagnostics(quick=True)
    known = {str(item.get("id") or "") for item in diagnostics.get("agents", [])}
    if requested_id not in known:
        raise HTTPException(status_code=404, detail=api_error("desktop_agent_not_found"))
    return requested_id


def _desktop_task_prompt(prompt: str, conversation_id: str, attachment_paths: list[str]) -> str:
    from conversation_context import (
        ContextBudget,
        compacted_history_cursor,
        compile_context,
        conversation_summary_store,
        render_prompt,
        task_history_messages,
    )

    summary_store = conversation_summary_store()
    summary_key = f"desktop-task:{conversation_id}"
    summary_state = summary_store.state(summary_key)
    history = agent_task_manager.conversation_messages(
        conversation_id,
        after_cursor=summary_state.cursor,
    )
    preamble = (
        "You are executing a task from SignalASI Desktop. Work directly, use the available local tools, "
        "verify the result, and return a concise final response with artifact paths when files are created."
    )
    if attachment_paths:
        preamble += "\n\nFiles attached to this task workspace:\n" + "\n".join(
            f"- {value}" for value in attachment_paths
        )
    compiled = compile_context(
        task_history_messages(history, prompt, after_cursor=summary_state.cursor),
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


@app.post("/api/desktop/tasks")
def api_start_desktop_task(req: DesktopTaskStartReq, request: Request):
    require_loopback(request)
    prompt = str(req.prompt or "").strip()
    if not prompt and not req.attachments:
        raise HTTPException(status_code=400, detail=api_error("desktop_task_empty"))
    task_id = str(uuid.uuid4())
    conversation_id = str(req.conversation_id or "").strip() or str(uuid.uuid4())
    agent_id = _desktop_agent_for(prompt, req.agent_id)
    attachments = _copy_desktop_attachments(task_id, req.attachments)
    compiled_prompt = _desktop_task_prompt(prompt, conversation_id, attachments)

    def runner(task):
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
            )
            return outcome.reply
        if agent_id.startswith("mcp:"):
            from desktop_mcp import desktop_mcp_registry

            connection_id = agent_id.split(":", 1)[1]
            connection = desktop_mcp_registry().get(connection_id)
            agent_task_manager.add_event(
                task.task_id,
                "mcp",
                f"Using {connection.name if connection else connection_id}",
            )
            result = desktop_mcp_registry().invoke_prompt(
                connection_id,
                prompt,
                process_callback=lambda process: agent_task_manager.register_process(task.task_id, process),
            )
            agent_task_manager.add_event(
                task.task_id,
                "result",
                f"Received result from {connection.name if connection else connection_id}",
                metadata={"duration_ms": int(result.get("duration_ms") or 0)},
            )
            return str(result.get("result") or "")
        result = deliver_agent_sync(
            agent_id,
            compiled_prompt,
            task_id=task.task_id,
            conversation_id=conversation_id,
            source_message_id=task.source_message_id,
            return_path="desktop-ui",
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
    )
    payload = task.public(include_prompt=True)
    payload["attachments"] = attachments
    return payload


@app.get("/api/desktop/tasks")
def api_list_desktop_tasks(request: Request, limit: int = Query(100)):
    require_loopback(request)
    tasks = [
        item for item in agent_task_manager.list(limit=max(100, limit), include_prompt=True)
        if str(item.get("source_message_id") or "").startswith("desktop:")
    ]
    return {"tasks": tasks[:max(1, min(limit, 500))]}


@app.get("/api/desktop/tasks/{task_id}")
def api_get_desktop_task(task_id: str, request: Request):
    require_loopback(request)
    task = agent_task_manager.get(task_id)
    if task is None or not task.source_message_id.startswith("desktop:"):
        raise HTTPException(status_code=404, detail=api_error("desktop_task_not_found"))
    return task.public(include_prompt=True)


@app.post("/api/desktop/tasks/{task_id}/cancel")
def api_cancel_desktop_task(task_id: str, request: Request):
    require_loopback(request)
    task = agent_task_manager.get(task_id)
    if task is None or not task.source_message_id.startswith("desktop:"):
        raise HTTPException(status_code=404, detail=api_error("desktop_task_not_found"))
    try:
        from desktop_native_tools import desktop_native_tool_registry

        desktop_native_tool_registry().cancel_task(task_id)
    except Exception:
        pass
    runtime_agent = str(task.delegate_agent_id or task.agent_id or "")
    if runtime_agent == "codex":
        try:
            from mqtt_bridge import codex_app_server

            if codex_app_server is not None:
                codex_app_server.interrupt(task_id)
        except Exception:
            pass
    cancelled = agent_task_manager.cancel(task_id)
    return {"task": cancelled.public(include_prompt=True) if cancelled else None}


@app.post("/api/desktop/tasks/{task_id}/retry")
def api_retry_desktop_task(task_id: str, request: Request):
    require_loopback(request)
    task = agent_task_manager.get(task_id)
    if task is None or not task.source_message_id.startswith("desktop:"):
        raise HTTPException(status_code=404, detail=api_error("desktop_task_not_found"))
    if task.status not in TERMINAL_STATES or task.status == "completed":
        raise HTTPException(status_code=409, detail=api_error("desktop_task_not_retryable"))

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

    return api_start_desktop_task(
        DesktopTaskStartReq(
            prompt=task.prompt,
            agent_id=task.agent_id,
            conversation_id=task.conversation_id,
            attachments=sources,
            retry_of=task.retry_of or task.task_id,
            attempt=max(2, task.attempt + 1),
        ),
        request,
    )


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

    # Agent replies are routed through SignalASI Desktop Connector.
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

@app.get("/signalasi/verify")
def signalasi_verify_qr(request: Request):
    require_loopback(request)
    pairing = signalasi_pairing_qr()
    image_data_url = pairing["image_data_url"]
    short_hash = pairing["fingerprint"]
    pairing_type = pairing["pairing_type"]
    agent_count = pairing["agent_count"]
    return HTMLResponse(f"""<!doctype html>
<html lang="en">
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width,initial-scale=1">
  <title>SignalASI Secure Pairing</title>
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
      <h1>SignalASI Secure Pairing</h1>
      <p>Scan this QR code in the SignalASI mobile app to pair this desktop connector.</p>
      <img alt="SignalASI pairing QR" data-pairing-type="{pairing_type}" data-pairing-route="/signalasi/verify" data-agent-count="{agent_count}" src="{image_data_url}">
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

            # Agent replies are routed through SignalASI Desktop Connector.
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
