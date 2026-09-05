"""Durable idempotency records for GalaxySSI Link Protocol v1."""
from __future__ import annotations

import json
import sqlite3
import threading
import time
from pathlib import Path

from pairing_state import DATA_DIR
from secure_state import (
    decrypt_text,
    encrypt_text,
    seal_identifier,
    unseal_identifier,
)

DB_PATH = Path(DATA_DIR) / "galaxyssi_link_delivery.db"
_lock = threading.RLock()
OUTBOUND_RETENTION_SECONDS = 7 * 24 * 60 * 60
OUTBOUND_RETRY_BASE_SECONDS = 5.0
OUTBOUND_RETRY_MAX_SECONDS = 300.0
OUTBOUND_MAX_ATTEMPTS = 6
OUTBOUND_PRIORITY_NORMAL = 50
SECURE_STORAGE_VERSION = "1"
ROUTE_PURPOSE = "link-delivery-route"


def _connect() -> sqlite3.Connection:
    DB_PATH.parent.mkdir(parents=True, exist_ok=True)
    db = sqlite3.connect(DB_PATH, timeout=10)
    db.execute("PRAGMA journal_mode=WAL")
    db.execute("PRAGMA synchronous=FULL")
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
        """CREATE TABLE IF NOT EXISTS inbound_ciphertexts (
            client_route_id TEXT NOT NULL,
            ciphertext_digest TEXT NOT NULL,
            message_id TEXT NOT NULL,
            received_at REAL NOT NULL,
            PRIMARY KEY (client_route_id, ciphertext_digest)
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
            priority INTEGER NOT NULL DEFAULT 50,
            PRIMARY KEY (client_route_id, message_id)
        )"""
    )
    outbound_columns = {
        str(row[1])
        for row in db.execute("PRAGMA table_info(outbound_messages)").fetchall()
    }
    if "priority" not in outbound_columns:
        db.execute(
            "ALTER TABLE outbound_messages ADD COLUMN priority INTEGER NOT NULL DEFAULT 50"
        )
        db.commit()
    db.execute(
        """CREATE TABLE IF NOT EXISTS delivery_metadata (
            key TEXT PRIMARY KEY,
            value TEXT NOT NULL
        )"""
    )
    db.execute(
        """CREATE TABLE IF NOT EXISTS task_result_outbox (
            task_id TEXT PRIMARY KEY,
            client_route_id TEXT NOT NULL,
            wire_payload TEXT NOT NULL,
            payload TEXT NOT NULL,
            created_at REAL NOT NULL,
            updated_at REAL NOT NULL
        )"""
    )
    secure_version = db.execute(
        "SELECT value FROM delivery_metadata WHERE key='secure_storage_version'"
    ).fetchone()
    if secure_version is None or str(secure_version[0]) != SECURE_STORAGE_VERSION:
        db.execute("BEGIN IMMEDIATE")
        secure_version = db.execute(
            "SELECT value FROM delivery_metadata WHERE key='secure_storage_version'"
        ).fetchone()
        if secure_version is None or str(secure_version[0]) != SECURE_STORAGE_VERSION:
            # Recheck under the writer lock: concurrent first-open must not
            # discard rows committed by another process during initialization.
            db.execute("DELETE FROM inbound_messages")
            db.execute("DELETE FROM inbound_ciphertexts")
            db.execute("DELETE FROM outbound_messages")
            db.execute("DELETE FROM task_result_outbox")
            db.execute("DELETE FROM delivery_metadata")
            db.execute(
                "INSERT INTO delivery_metadata(key,value) VALUES('secure_storage_version',?)",
                (SECURE_STORAGE_VERSION,),
            )
        db.commit()
    from task_result_outbox import ensure_schema
    ensure_schema(db)
    return db


def _route(value: str) -> str:
    return seal_identifier(DB_PATH, str(value or ""), purpose=ROUTE_PURPOSE)


def _unroute(value: str) -> str:
    return unseal_identifier(DB_PATH, value, purpose=ROUTE_PURPOSE)


def _protect(value: str, field: str) -> str:
    return encrypt_text(
        DB_PATH,
        str(value or ""),
        purpose=f"link-delivery-{field}",
    )


def _reveal(value: str, field: str) -> str:
    return decrypt_text(
        DB_PATH,
        value,
        purpose=f"link-delivery-{field}",
    )


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
                (_route(client_route_id), message_id, time.time(), "received"),
            )
            db.commit()
            return cursor.rowcount == 1
        finally:
            db.close()


def bind_ciphertext(client_route_id: str, ciphertext_digest: str, message_id: str) -> None:
    """Persist the logical message behind a Signal ciphertext for pre-decrypt replay checks."""
    with _lock:
        db = _connect()
        try:
            db.execute(
                """INSERT OR IGNORE INTO inbound_ciphertexts
                   (client_route_id,ciphertext_digest,message_id,received_at)
                   VALUES(?,?,?,?)""",
                (_route(client_route_id), ciphertext_digest, message_id, time.time()),
            )
            row = db.execute(
                """SELECT message_id FROM inbound_ciphertexts
                   WHERE client_route_id=? AND ciphertext_digest=?""",
                (_route(client_route_id), ciphertext_digest),
            ).fetchone()
            if row is None or str(row[0]) != message_id:
                raise ValueError("Signal ciphertext digest is already bound to another message")
            db.commit()
        finally:
            db.close()


def message_for_ciphertext(client_route_id: str, ciphertext_digest: str) -> str | None:
    """Return a previously decrypted message ID without advancing the Signal ratchet."""
    with _lock:
        db = _connect()
        try:
            row = db.execute(
                """SELECT message_id FROM inbound_ciphertexts
                   WHERE client_route_id=? AND ciphertext_digest=?""",
                (_route(client_route_id), ciphertext_digest),
            ).fetchone()
        finally:
            db.close()
    return str(row[0]) if row else None


def complete_message(client_route_id: str, message_id: str, status: str, acknowledgement: dict | None = None) -> None:
    with _lock:
        db = _connect()
        try:
            db.execute(
                "UPDATE inbound_messages SET status=?, acknowledgement=? WHERE client_route_id=? AND message_id=?",
                (
                    status,
                    _protect(
                        json.dumps(acknowledgement or {}, ensure_ascii=False),
                        "acknowledgement",
                    ),
                    _route(client_route_id),
                    message_id,
                ),
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
                (_route(client_route_id), message_id),
            ).fetchone()
        finally:
            db.close()
    if not row:
        return {}
    try:
        value = json.loads(_reveal(row[0], "acknowledgement") or "{}")
    except (json.JSONDecodeError, RuntimeError):
        value = {}
    value.setdefault("status", row[1])
    return value


def queue_outbound(
    client_route_id: str,
    message_id: str,
    topic: str,
    wire_payload: str,
    *,
    priority: int = OUTBOUND_PRIORITY_NORMAL,
) -> None:
    now = time.time()
    with _lock:
        db = _connect()
        try:
            db.execute(
                """INSERT OR IGNORE INTO outbound_messages
                   (client_route_id,message_id,topic,wire_payload,created_at,updated_at,attempts,status,priority)
                   VALUES(?,?,?,?,?,?,0,'queued',?)""",
                (
                    _route(client_route_id),
                    message_id,
                    _protect(topic, "topic"),
                    _protect(wire_payload, "wire-payload"),
                    now,
                    now,
                    int(priority),
                ),
            )
            db.commit()
        finally:
            db.close()


def outbound_retry_delay_seconds(attempts: int) -> float:
    exponent = max(0, min(int(attempts or 0) - 1, 10))
    return min(
        OUTBOUND_RETRY_MAX_SECONDS,
        OUTBOUND_RETRY_BASE_SECONDS * (2 ** exponent),
    )


def mark_outbound_sending(client_route_id: str, message_id: str) -> None:
    with _lock:
        db = _connect()
        try:
            db.execute(
                """UPDATE outbound_messages
                   SET status='sending', attempts=attempts+1, updated_at=?
                   WHERE client_route_id=? AND message_id=?""",
                (time.time(), _route(client_route_id), message_id),
            )
            db.commit()
        finally:
            db.close()


def mark_outbound_published(client_route_id: str, message_id: str) -> None:
    with _lock:
        db = _connect()
        try:
            db.execute(
                """UPDATE outbound_messages SET status='published', updated_at=?
                   WHERE client_route_id=? AND message_id=?""",
                (time.time(), _route(client_route_id), message_id),
            )
            db.commit()
        finally:
            db.close()


def mark_outbound_retryable(client_route_id: str, message_id: str) -> None:
    with _lock:
        db = _connect()
        try:
            db.execute(
                """UPDATE outbound_messages SET status='queued', updated_at=?
                   WHERE client_route_id=? AND message_id=?""",
                (time.time(), _route(client_route_id), message_id),
            )
            db.commit()
        finally:
            db.close()


def fail_exhausted_outbound(max_attempts: int = OUTBOUND_MAX_ATTEMPTS) -> list[dict]:
    """Quarantine exhausted ciphertexts without affecting other routes."""
    normalized_max = max(1, int(max_attempts))
    with _lock:
        db = _connect()
        try:
            rows = db.execute(
                """SELECT client_route_id,message_id,attempts
                   FROM outbound_messages
                   WHERE attempts>=? AND status IN ('queued','sending','published')
                   ORDER BY created_at""",
                (normalized_max,),
            ).fetchall()
            if rows:
                db.execute(
                    """UPDATE outbound_messages SET status='failed', updated_at=?
                       WHERE attempts>=? AND status IN ('queued','sending','published')""",
                    (time.time(), normalized_max),
                )
                db.commit()
        finally:
            db.close()
    return [
        {
            "client_route_id": _unroute(row[0]),
            "message_id": str(row[1]),
            "attempts": int(row[2]),
        }
        for row in rows
    ]


def acknowledge_outbound(client_route_id: str, message_id: str) -> bool:
    with _lock:
        db = _connect()
        try:
            cursor = db.execute(
                "DELETE FROM outbound_messages WHERE client_route_id=? AND message_id=?",
                (_route(client_route_id), message_id),
            )
            db.commit()
            return cursor.rowcount > 0
        finally:
            db.close()


def discard_route(client_route_id: str) -> dict[str, int]:
    """Remove transient delivery state owned by a revoked client route."""
    normalized_route_id = str(client_route_id or "").strip()
    if not normalized_route_id:
        return {
            "inbound_messages": 0,
            "inbound_ciphertexts": 0,
            "outbound_messages": 0,
            "task_results": 0,
        }
    sealed_route_id = _route(normalized_route_id)
    tables = {
        "inbound_messages": "inbound_messages",
        "inbound_ciphertexts": "inbound_ciphertexts",
        "outbound_messages": "outbound_messages",
        "task_results": "task_result_outbox",
    }
    removed: dict[str, int] = {}
    with _lock:
        db = _connect()
        try:
            for result_key, table_name in tables.items():
                cursor = db.execute(
                    f"DELETE FROM {table_name} WHERE client_route_id=?",
                    (sealed_route_id,),
                )
                removed[result_key] = max(0, int(cursor.rowcount or 0))
            removed["task_results"] += max(0, db.execute(
                "DELETE FROM task_result_queue WHERE client_route_id=?", (sealed_route_id,),
            ).rowcount)
            db.commit()
        finally:
            db.close()
    return removed


def outbound_status(client_route_id: str, message_id: str) -> str | None:
    """Return the durable delivery state without making the message replayable."""
    with _lock:
        db = _connect()
        try:
            row = db.execute(
                "SELECT status FROM outbound_messages WHERE client_route_id=? AND message_id=?",
                (_route(client_route_id), message_id),
            ).fetchone()
        finally:
            db.close()
    return str(row[0]) if row else None


def queue_task_result(
    task_id: str,
    client_route_id: str,
    wire_payload: dict,
    payload: dict,
    *,
    replay: bool = False,
) -> dict | None:
    return _task_results().enqueue(task_id, client_route_id, wire_payload, payload, replay=replay)


def _task_results():
    from task_result_outbox import TaskResultOutbox
    return TaskResultOutbox(DB_PATH, _connect)


def pending_task_results(*, limit: int = 32) -> list[dict]:
    return _task_results().pending(limit=limit)


def task_result_is_current(record: dict) -> bool:
    return _task_results().current(record)


def remove_task_result(record: dict) -> bool:
    """Retire only the observed revision after durable transport takes ownership."""
    return _task_results().hand_off(record)


def _outbound_retry_due(status: str, attempts: int, updated_at: float, now: float) -> bool:
    if status == "queued":
        return attempts <= 0 or now >= updated_at + outbound_retry_delay_seconds(attempts)
    return now >= updated_at + outbound_retry_delay_seconds(attempts)


def outbound_inflight_count(
    now: float | None = None,
    *,
    client_route_id: str = "",
) -> int:
    observed_at = time.time() if now is None else float(now)
    normalized_route_id = str(client_route_id or "").strip()
    with _lock:
        db = _connect()
        try:
            if normalized_route_id:
                rows = db.execute(
                    """SELECT status,attempts,updated_at
                       FROM outbound_messages
                       WHERE client_route_id=? AND status IN ('sending','published')""",
                    (_route(normalized_route_id),),
                ).fetchall()
            else:
                rows = db.execute(
                    """SELECT status,attempts,updated_at
                       FROM outbound_messages
                       WHERE status IN ('sending','published')"""
                ).fetchall()
        finally:
            db.close()
    return sum(
        1
        for status, attempts, updated_at in rows
        if not _outbound_retry_due(str(status), int(attempts), float(updated_at), observed_at)
    )


def pending_outbound(
    max_attempts: int | None = None,
    *,
    limit: int | None = None,
    now: float | None = None,
    client_route_id: str = "",
) -> list[dict]:
    retry_limit = OUTBOUND_MAX_ATTEMPTS if max_attempts is None else max(1, int(max_attempts))
    observed_at = time.time() if now is None else float(now)
    normalized_route_id = str(client_route_id or "").strip()
    with _lock:
        db = _connect()
        try:
            db.execute(
                "DELETE FROM outbound_messages WHERE created_at < ?",
                (time.time() - OUTBOUND_RETENTION_SECONDS,),
            )
            if normalized_route_id:
                rows = db.execute(
                    """SELECT client_route_id,message_id,topic,wire_payload,attempts,created_at,
                               updated_at,status,priority
                       FROM outbound_messages
                       WHERE client_route_id=? AND status IN ('queued','sending','published')
                       ORDER BY priority DESC, created_at""",
                    (_route(normalized_route_id),),
                ).fetchall()
            else:
                rows = db.execute(
                    """SELECT client_route_id,message_id,topic,wire_payload,attempts,created_at,
                               updated_at,status,priority
                       FROM outbound_messages
                       WHERE status IN ('queued','sending','published')
                       ORDER BY priority DESC, CASE status WHEN 'queued' THEN 0 ELSE 1 END, created_at"""
                ).fetchall()
            db.commit()
        finally:
            db.close()
    decoded = [
        ({
            "client_route_id": _unroute(row[0]),
            "message_id": row[1],
            "topic": _reveal(row[2], "topic"),
            "wire_payload": _reveal(row[3], "wire-payload"),
            "attempts": row[4],
            "created_at": row[5],
            "updated_at": row[6],
            "status": row[7],
            "priority": int(row[8]),
        }, (
            int(row[4]) < retry_limit and
            _outbound_retry_due(str(row[7]), int(row[4]), float(row[6]), observed_at)
        ))
        for row in rows
    ]
    if normalized_route_id:
        pending = []
        for item, retry_due in decoded:
            if retry_due:
                pending.append(item)
            elif item["status"] == "queued":
                # A failed earlier ciphertext must be retried before later
                # ciphertexts for the same Signal session are published.
                break
    else:
        pending = [item for item, retry_due in decoded if retry_due]
    return pending if limit is None else pending[:max(0, int(limit))]
