"""SignalASI Link backend - FastAPI + WebSocket."""
import asyncio
import json
import logging
import os
from datetime import datetime, timezone
from contextlib import asynccontextmanager

from fastapi import FastAPI, WebSocket, WebSocketDisconnect, Depends, Query, HTTPException, Header, Request
from fastapi.middleware.cors import CORSMiddleware
from sqlalchemy.orm import Session
from pydantic import BaseModel

from models import init_db, get_session, Contact, Message, ContactType, MessageType, SenderType
from agent_gateway import ask_agent_sync, connector_diagnostics, connector_self_test, list_agents, recent_agent_execution_log, reset_inactive_agent_runtime
from agent_config import load_config, save_config
from api_response import api_error
from agent_task_manager import agent_task_manager

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
    if include_agents:
        from mqtt_bridge import mobile_connector_agents
        payload["connector_agents"] = mobile_connector_agents()
    return payload

@asynccontextmanager
async def lifespan(app: FastAPI):
    file_server_process = None
    init_db()
    external_services_enabled = os.environ.get("SIGNALASI_DISABLE_EXTERNAL_SERVICES") != "1"
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
    return list_agents()

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

@app.post("/api/pairing/clear")
def api_pairing_clear(client_route_id: str = Query("")):
    from pairing_state import clear_pairing_state, get_client, list_clients, pairing_status
    from mqtt_bridge import publish_pairing_revoked
    from signalasi_client import remove_peer_signal_session
    targets = [get_client(client_route_id)] if client_route_id else list_clients()
    targets = [target for target in targets if target]
    revoke = publish_pairing_revoked(reason="forgotten_by_desktop", client_route_id=client_route_id)
    removed_sessions = []
    for target in targets:
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
    local_model: dict[str, str] = {}
    cloud_model: dict[str, str] = {}
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
import base64
import io

frontend_path = Path(__file__).parent.parent / "frontend" / "index.html"

@app.get("/")
def serve_index():
    return FileResponse(str(frontend_path))

@app.get("/signalasi/verify")
def signalasi_verify_qr(request: Request):
    require_loopback(request)
    import qrcode
    from mqtt_bridge import mobile_connector_agents

    payload = signalasi_pairing_payload()
    agent_count = len(mobile_connector_agents())
    qr = qrcode.QRCode(error_correction=qrcode.constants.ERROR_CORRECT_L, border=2, box_size=10)
    qr.add_data(json.dumps(payload, ensure_ascii=False, separators=(",", ":")))
    qr.make(fit=True)
    image = qr.make_image(fill_color="black", back_color="white")
    buffer = io.BytesIO()
    image.save(buffer, format="PNG")
    encoded = base64.b64encode(buffer.getvalue()).decode("ascii")
    short_hash = payload["identity_key_sha256"][:16]
    pairing_type = payload["type"]
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
      <img alt="SignalASI pairing QR" data-pairing-type="{pairing_type}" data-pairing-route="/signalasi/verify" data-agent-count="{agent_count}" src="data:image/png;base64,{encoded}">
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
