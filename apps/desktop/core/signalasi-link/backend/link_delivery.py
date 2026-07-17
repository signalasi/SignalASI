"""Durable idempotency records for SignalASI Link Protocol v1."""
from __future__ import annotations

import json
import sqlite3
import threading
import time
from pathlib import Path

from pairing_state import DATA_DIR

DB_PATH = Path(DATA_DIR) / "signalasi_link_delivery.db"
_lock = threading.RLock()
OUTBOUND_RETENTION_SECONDS = 7 * 24 * 60 * 60


def _connect() -> sqlite3.Connection:
    DB_PATH.parent.mkdir(parents=True, exist_ok=True)
    db = sqlite3.connect(DB_PATH, timeout=10)
    db.execute("PRAGMA journal_mode=WAL")
    db.execute(
        """CREATE TABLE IF NOT EXISTS inbound_messages (
            client_route_id TEXT NOT NULL,
            message_id TEXT NOT NULL,
            received_at REAL NOT NULL,
            status TEXT NOT NULL,
            acknowledgement TEXT NOT NULL DEFAULT '{}',
            PRIMARY KEY (client_route_id, message_id)
        )"""
    )
    db.execute(
        """CREATE TABLE IF NOT EXISTS outbound_messages (
            client_route_id TEXT NOT NULL,
            message_id TEXT NOT NULL,
            topic TEXT NOT NULL,
            wire_payload TEXT NOT NULL,
            created_at REAL NOT NULL,
            updated_at REAL NOT NULL,
            attempts INTEGER NOT NULL DEFAULT 0,
            status TEXT NOT NULL,
            PRIMARY KEY (client_route_id, message_id)
        )"""
    )
    db.execute(
        """CREATE TABLE IF NOT EXISTS delivery_metadata (
            key TEXT PRIMARY KEY,
            value TEXT NOT NULL
        )"""
    )
    return db


def ensure_transport_epoch(epoch: str) -> bool:
    """Clear obsolete broker-bound outbox entries once when the MQTT session epoch changes."""
    normalized = str(epoch or "").strip()
    if not normalized:
        raise ValueError("transport epoch is required")
    with _lock:
        db = _connect()
        try:
            row = db.execute(
                "SELECT value FROM delivery_metadata WHERE key='transport_epoch'"
            ).fetchone()
            if row and str(row[0]) == normalized:
                return False
            db.execute("DELETE FROM outbound_messages")
            db.execute(
                """INSERT INTO delivery_metadata(key,value) VALUES('transport_epoch',?)
                   ON CONFLICT(key) DO UPDATE SET value=excluded.value""",
                (normalized,),
            )
            db.commit()
            return True
        finally:
            db.close()


def claim_message(client_route_id: str, message_id: str) -> bool:
    """Atomically claim a message. False means it was already received."""
    with _lock:
        db = _connect()
        try:
            cursor = db.execute(
                "INSERT OR IGNORE INTO inbound_messages(client_route_id,message_id,received_at,status) VALUES(?,?,?,?)",
                (client_route_id, message_id, time.time(), "received"),
            )
            db.commit()
            return cursor.rowcount == 1
        finally:
            db.close()


def complete_message(client_route_id: str, message_id: str, status: str, acknowledgement: dict | None = None) -> None:
    with _lock:
        db = _connect()
        try:
            db.execute(
                "UPDATE inbound_messages SET status=?, acknowledgement=? WHERE client_route_id=? AND message_id=?",
                (status, json.dumps(acknowledgement or {}, ensure_ascii=False), client_route_id, message_id),
            )
            db.commit()
        finally:
            db.close()


def previous_acknowledgement(client_route_id: str, message_id: str) -> dict:
    with _lock:
        db = _connect()
        try:
            row = db.execute(
                "SELECT acknowledgement,status FROM inbound_messages WHERE client_route_id=? AND message_id=?",
                (client_route_id, message_id),
            ).fetchone()
        finally:
            db.close()
    if not row:
        return {}
    try:
        value = json.loads(row[0] or "{}")
    except json.JSONDecodeError:
        value = {}
    value.setdefault("status", row[1])
    return value


def queue_outbound(client_route_id: str, message_id: str, topic: str, wire_payload: str) -> None:
    now = time.time()
    with _lock:
        db = _connect()
        try:
            db.execute(
                """INSERT OR IGNORE INTO outbound_messages
                   (client_route_id,message_id,topic,wire_payload,created_at,updated_at,attempts,status)
                   VALUES(?,?,?,?,?,?,0,'queued')""",
                (client_route_id, message_id, topic, wire_payload, now, now),
            )
            db.commit()
        finally:
            db.close()


def mark_outbound_published(client_route_id: str, message_id: str) -> None:
    with _lock:
        db = _connect()
        try:
            db.execute(
                """UPDATE outbound_messages SET status='published', attempts=attempts+1, updated_at=?
                   WHERE client_route_id=? AND message_id=?""",
                (time.time(), client_route_id, message_id),
            )
            db.commit()
        finally:
            db.close()


def acknowledge_outbound(client_route_id: str, message_id: str) -> None:
    with _lock:
        db = _connect()
        try:
            db.execute(
                "DELETE FROM outbound_messages WHERE client_route_id=? AND message_id=?",
                (client_route_id, message_id),
            )
            db.commit()
        finally:
            db.close()


def outbound_status(client_route_id: str, message_id: str) -> str | None:
    """Return the durable delivery state without making the message replayable."""
    with _lock:
        db = _connect()
        try:
            row = db.execute(
                "SELECT status FROM outbound_messages WHERE client_route_id=? AND message_id=?",
                (client_route_id, message_id),
            ).fetchone()
        finally:
            db.close()
    return str(row[0]) if row else None


def pending_outbound(max_attempts: int = 8) -> list[dict]:
    with _lock:
        db = _connect()
        try:
            db.execute(
                "DELETE FROM outbound_messages WHERE created_at < ?",
                (time.time() - OUTBOUND_RETENTION_SECONDS,),
            )
            rows = db.execute(
                """SELECT client_route_id,message_id,topic,wire_payload,attempts,created_at
                   FROM outbound_messages
                   WHERE status='queued' AND attempts < ?
                   ORDER BY created_at""",
                (max_attempts,),
            ).fetchall()
            db.commit()
        finally:
            db.close()
    return [
        {
            "client_route_id": row[0],
            "message_id": row[1],
            "topic": row[2],
            "wire_payload": row[3],
            "attempts": row[4],
            "created_at": row[5],
        }
        for row in rows
    ]
