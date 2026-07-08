from __future__ import annotations

import asyncio
import contextlib
from collections import defaultdict

from fastapi import WebSocket
from sqlalchemy.orm import Session

from agent_gateway import ask_agent_sync
from models import Message, SessionLocal, serialize_message


class ConnectionManager:
    def __init__(self) -> None:
        self.active_connections: dict[str, set[WebSocket]] = defaultdict(set)

    async def connect(self, contact_id: str, websocket: WebSocket) -> None:
        await websocket.accept()
        self.active_connections[contact_id].add(websocket)

    def disconnect(self, contact_id: str, websocket: WebSocket) -> None:
        self.active_connections[contact_id].discard(websocket)
        if not self.active_connections[contact_id]:
            self.active_connections.pop(contact_id, None)

    async def broadcast(self, contact_id: str, payload: dict) -> None:
        stale: list[WebSocket] = []
        for websocket in list(self.active_connections.get(contact_id, set())):
            try:
                await websocket.send_json(payload)
            except RuntimeError:
                stale.append(websocket)
        for websocket in stale:
            self.disconnect(contact_id, websocket)


manager = ConnectionManager()


def save_message(db: Session, contact_id: str, sender: str, content: str, message_type: str = "text") -> Message:
    message = Message(contact_id=contact_id, sender=sender, content=content, type=message_type)
    db.add(message)
    db.commit()
    db.refresh(message)
    return message


async def save_and_broadcast(contact_id: str, sender: str, content: str, message_type: str = "text") -> Message:
    with SessionLocal() as db:
        message = save_message(db, contact_id, sender, content, message_type)
        payload = {"event": "message", "message": serialize_message(message)}
    await manager.broadcast(contact_id, payload)
    return message


async def generate_hermes_reply(contact_id: str, prompt: str) -> None:
    await manager.broadcast(contact_id, {"event": "typing", "contact_id": contact_id, "typing": True})
    try:
        reply = await asyncio.to_thread(ask_agent_sync, "hermes", prompt)
        await save_and_broadcast(contact_id, "other", reply, "text")
    except Exception as exc:
        await save_and_broadcast(contact_id, "other", f"Hermes error: {exc}", "system")
    finally:
        await manager.broadcast(contact_id, {"event": "typing", "contact_id": contact_id, "typing": False})


async def heartbeat(contact_id: str, websocket: WebSocket) -> None:
    while True:
        await asyncio.sleep(25)
        await websocket.send_json({"event": "heartbeat"})


async def websocket_endpoint(websocket: WebSocket, contact_id: str) -> None:
    await manager.connect(contact_id, websocket)
    heartbeat_task = asyncio.create_task(heartbeat(contact_id, websocket))
    try:
        while True:
            data = await websocket.receive_json()
            event = data.get("event", "message")
            if event == "ping":
                await websocket.send_json({"event": "pong"})
                continue
            if event == "typing":
                await manager.broadcast(contact_id, {"event": "typing", "contact_id": contact_id, "typing": bool(data.get("typing"))})
                continue

            content = str(data.get("content", "")).strip()
            if not content:
                continue
            await save_and_broadcast(contact_id, "self", content, str(data.get("type", "text")))
            if contact_id.lower() == "hermes":
                asyncio.create_task(generate_hermes_reply(contact_id, content))
    finally:
        heartbeat_task.cancel()
        with contextlib.suppress(asyncio.CancelledError):
            await heartbeat_task
        manager.disconnect(contact_id, websocket)
