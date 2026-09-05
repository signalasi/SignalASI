"""Encrypted logical replies with generation fences and compare-and-set handoff."""

from __future__ import annotations

import hashlib
import json
import logging
from pathlib import Path
import sqlite3
import time
import uuid

from agent_task_result_archive import execution_generation, identity
from secure_state import decrypt_text, encrypt_text, seal_identifier

PAGE_SIZE = 32
log = logging.getLogger(__name__)


def ensure_schema(db: sqlite3.Connection) -> None:
    ready = db.execute("SELECT value FROM delivery_metadata WHERE key='task_result_queue_schema'").fetchone()
    if ready is not None and ready[0] == "1":
        return
    db.execute("BEGIN IMMEDIATE")
    db.execute("""CREATE TABLE IF NOT EXISTS task_result_queue (
        scope TEXT PRIMARY KEY, client_route_id TEXT NOT NULL,
        generation INTEGER NOT NULL, revision TEXT NOT NULL,
        state TEXT NOT NULL, wire_payload TEXT NOT NULL, payload TEXT NOT NULL,
        created_at REAL NOT NULL, updated_at REAL NOT NULL,
        last_attempt_at REAL NOT NULL DEFAULT 0)""")
    db.execute("""CREATE INDEX IF NOT EXISTS task_result_queue_pending
        ON task_result_queue(state,last_attempt_at,created_at,scope)""")
    columns = {row[1] for row in db.execute("PRAGMA table_info(task_result_outbox)")}
    if "migration_error" not in columns:
        db.execute("ALTER TABLE task_result_outbox ADD COLUMN migration_error INTEGER NOT NULL DEFAULT 0")
    db.execute("""CREATE INDEX IF NOT EXISTS task_result_outbox_migration
        ON task_result_outbox(migration_error,created_at,task_id)""")
    db.execute("""INSERT INTO delivery_metadata(key,value) VALUES('task_result_queue_schema','1')
        ON CONFLICT(key) DO UPDATE SET value=excluded.value""")
    db.commit()


def result_identity(task_id: str, route: str, wire: dict, payload: dict) -> tuple[dict, int]:
    fields, generation = identity(payload), execution_generation(payload)
    if (fields is None or generation is None or fields["task_id"] != task_id
            or fields["client_route_id"] != route or wire.get("_client_route_id") != route):
        raise ValueError("task result requires matching complete execution identity")
    return fields, generation


class TaskResultOutbox:
    def __init__(self, path: Path, connect):
        self.path, self.connect = Path(path), connect

    def _seal(self, text: str, purpose: str) -> str:
        return seal_identifier(self.path, text, purpose=purpose)

    def _scope(self, fields: dict) -> str:
        sealed = self._seal(json.dumps(fields, ensure_ascii=False, sort_keys=True), "task-outbox-scope")
        return hashlib.sha256(sealed.encode()).hexdigest()

    def _protect(self, value: dict, field: str) -> str:
        return encrypt_text(self.path, json.dumps(value, ensure_ascii=False, separators=(",", ":")),
                            purpose=f"link-delivery-{field}")

    def _reveal(self, value: str, field: str) -> dict:
        decoded = json.loads(decrypt_text(self.path, value, purpose=f"link-delivery-{field}"))
        if not isinstance(decoded, dict):
            raise ValueError("task result must be an object")
        return decoded

    def _decode(self, row) -> dict:
        scope, route, generation, revision, state, wire, payload, created, *_ = row
        body, envelope = self._reveal(payload, "task-payload"), self._reveal(wire, "task-wire-payload")
        fields, body_generation = result_identity(body.get("task_id"), body.get("client_route_id"), envelope, body)
        if (self._scope(fields) != scope or body_generation != generation
                or self._seal(fields["client_route_id"], "link-delivery-route") != route):
            raise ValueError("task result stored identity mismatch")
        return {"task_id": fields["task_id"], "client_route_id": fields["client_route_id"],
                "wire_payload": envelope, "payload": body, "created_at": created,
                "scope": scope, "execution_generation": generation, "revision": revision}

    def _enqueue(self, db, task_id, route, wire, payload, created_at=None, *, replay=False):
        fields, generation = result_identity(task_id, route, wire, payload)
        scope = self._scope(fields)
        row = db.execute("SELECT * FROM task_result_queue WHERE scope=?", (scope,)).fetchone()
        if row is not None and generation <= row[2]:
            if generation < row[2] or row[4] == "corrupt":
                return None
            if row[4] == "pending":
                canonical = self._decode(row)
                if not replay:
                    return canonical
                payload = canonical["payload"]
            elif not replay:
                return None
        now = time.time()
        created = now if created_at is None else float(created_at)
        revision = uuid.uuid4().hex
        if replay:
            # Explicit operator replay, not automatic resurrection: a new wire
            # identity and revision cannot collide with an exhausted ciphertext.
            payload = {**payload, "message_id": str(uuid.uuid5(uuid.NAMESPACE_URL,
                       f"task-result-replay:{scope}:{generation}:{revision}"))}
        db.execute("""INSERT INTO task_result_queue
            (scope,client_route_id,generation,revision,state,wire_payload,payload,created_at,updated_at)
            VALUES(?,?,?,?,'pending',?,?,?,?)
            ON CONFLICT(scope) DO UPDATE SET generation=excluded.generation, revision=excluded.revision,
                state='pending',wire_payload=excluded.wire_payload,payload=excluded.payload,
                created_at=excluded.created_at,updated_at=excluded.updated_at,last_attempt_at=0""",
            (scope, self._seal(route, "link-delivery-route"), generation, revision,
             self._protect(wire, "task-wire-payload"), self._protect(payload, "task-payload"), created, now))
        return {"task_id": fields["task_id"], "client_route_id": fields["client_route_id"],
                "wire_payload": json.loads(json.dumps(wire)), "payload": json.loads(json.dumps(payload)),
                "created_at": created, "scope": scope, "execution_generation": generation, "revision": revision}

    def _migrate(self, db, task_id=None):
        predicate, params = ("AND task_id=?", (task_id,)) if task_id is not None else ("", ())
        rows = db.execute(f"""SELECT task_id,client_route_id,wire_payload,payload,created_at
            FROM task_result_outbox WHERE migration_error=0 {predicate}
            ORDER BY created_at,task_id LIMIT ?""", (*params, PAGE_SIZE)).fetchall()
        for old_task, sealed_route, wire, payload, created in rows:
            try:
                body = self._reveal(payload, "task-payload")
                route = str(body.get("client_route_id") or "")
                if self._seal(route, "link-delivery-route") != sealed_route:
                    raise ValueError("legacy route mismatch")
                self._enqueue(db, old_task, route, self._reveal(wire, "task-wire-payload"), body, created)
            except (ValueError, RuntimeError):
                # Retain unreadable ciphertext for diagnosis; do not starve healthy rows.
                db.execute("UPDATE task_result_outbox SET migration_error=1 WHERE task_id=?", (old_task,))
                log.error("A legacy task result was quarantined; encrypted data retained")
            else:
                db.execute("DELETE FROM task_result_outbox WHERE task_id=?", (old_task,))

    def enqueue(self, task_id: str, route: str, wire: dict, payload: dict, *, replay=False) -> dict | None:
        result_identity(task_id, route, wire, payload)
        db = self.connect()
        try:
            with db:
                db.execute("BEGIN IMMEDIATE")
                self._migrate(db, task_id)
                return self._enqueue(db, task_id, route, wire, payload, replay=replay)
        finally:
            db.close()

    def pending(self, *, limit: int = PAGE_SIZE) -> list[dict]:
        size = max(0, min(PAGE_SIZE, int(limit)))
        if not size:
            return []
        db = self.connect()
        try:
            with db:
                db.execute("BEGIN IMMEDIATE")
                self._migrate(db)
                rows = db.execute("""SELECT * FROM task_result_queue WHERE state='pending'
                    ORDER BY last_attempt_at,created_at,scope LIMIT ?""", (size,)).fetchall()
                for row in rows:
                    # Rotate deferred rows; release the writer before body decryption.
                    db.execute("UPDATE task_result_queue SET last_attempt_at=? WHERE scope=?", (time.time(), row[0]))
        finally:
            db.close()
        results = []
        for row in rows:
            try:
                results.append(self._decode(row))
            except (ValueError, RuntimeError):
                db = self.connect()
                try:
                    with db:
                        db.execute("""UPDATE task_result_queue SET state='corrupt'
                            WHERE scope=? AND generation=? AND revision=? AND state='pending'""",
                            (row[0], row[2], row[3]))
                finally:
                    db.close()
                log.error("A task result was quarantined; encrypted data retained")
        return results

    @staticmethod
    def _receipt(record: dict) -> tuple | None:
        if not isinstance(record, dict) or "execution_generation" not in record:
            return None
        scope, revision, generation = record.get("scope"), record.get("revision"), execution_generation(record)
        if not scope or not revision or generation is None:
            return None
        return scope, generation, revision

    def current(self, record: dict) -> bool:
        receipt = self._receipt(record)
        if receipt is None:
            return False
        db = self.connect()
        try:
            return db.execute("""SELECT 1 FROM task_result_queue
                WHERE scope=? AND generation=? AND revision=? AND state='pending'""", receipt).fetchone() is not None
        finally:
            db.close()

    def hand_off(self, record: dict) -> bool:
        receipt = self._receipt(record)
        if receipt is None:
            return False
        db = self.connect()
        try:
            with db:
                changed = db.execute("""UPDATE task_result_queue SET state='handed_off',
                    wire_payload='',payload='',updated_at=?
                    WHERE scope=? AND generation=? AND revision=? AND state='pending'""",
                    (time.time(), *receipt)).rowcount
                # Keep the opaque generation head after transport takes ownership.
                return bool(changed)
        finally:
            db.close()
