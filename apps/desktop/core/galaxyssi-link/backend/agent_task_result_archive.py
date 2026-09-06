"""Encrypted, immutable final replies, retained until the phone durably accepts them."""

from __future__ import annotations

import base64
import hashlib
import json
import re
from pathlib import Path
import sqlite3
import threading

from agent_task_recovery_query import IDENTITY_FIELDS
from pairing_state import DATA_DIR
from secure_state import decrypt_text, encrypt_text, seal_identifier

PAGE_BYTES = 16 * 1024
TERMINAL_STATUSES = frozenset({"completed", "failed", "timed_out", "cancelled"})
_lock = threading.RLock()


def identity(payload: dict) -> dict | None:
    values = {field: str(payload.get(field) or "") for field in IDENTITY_FIELDS}
    return values if all(1 <= len(value) <= 200 for value in values.values()) else None


def execution_generation(payload: dict) -> int | None:
    value = payload.get("execution_generation", 1)
    return value if type(value) is int and 1 <= value <= 2**53 - 1 else None


class TaskResultArchive:
    def __init__(self, path: Path | None = None):
        self.path = Path(path) if path else Path(DATA_DIR) / "agent_result_archive.db"

    def _connect(self):
        self.path.parent.mkdir(parents=True, exist_ok=True)
        db = sqlite3.connect(self.path, timeout=10)
        db.execute("PRAGMA journal_mode=WAL")
        db.execute("PRAGMA synchronous=FULL")
        db.execute("PRAGMA foreign_keys=ON")
        db.execute("CREATE TABLE IF NOT EXISTS results (scope TEXT PRIMARY KEY, digest TEXT NOT NULL, "
                   "bytes INTEGER NOT NULL, pages INTEGER NOT NULL, acknowledged INTEGER NOT NULL DEFAULT 0)")
        db.execute("CREATE TABLE IF NOT EXISTS pages (scope TEXT NOT NULL REFERENCES results(scope) ON DELETE CASCADE, "
                   "page INTEGER NOT NULL, body TEXT NOT NULL, PRIMARY KEY(scope,page))")
        return db

    def _scope(self, value: dict, generation: int = 1) -> str:
        parts = [value[field] for field in IDENTITY_FIELDS]
        # Generation one keeps existing archives addressable; retries get independent bodies/receipts.
        if generation != 1:
            parts.append(generation)
        encoded = json.dumps(parts, ensure_ascii=False)
        sealed = seal_identifier(self.path, encoded, purpose="task-result-scope")
        return hashlib.sha256(sealed.encode()).hexdigest()

    @staticmethod
    def _purpose(scope: str, page: int) -> str:
        return f"task-result-{scope[:32]}-{page}"

    def put(self, payload: dict) -> dict | None:
        scope_fields = identity(payload)
        generation = execution_generation(payload)
        if (scope_fields is None or generation is None or payload.get("type") != "text"
                or payload.get("task_status") not in TERMINAL_STATUSES
                or not (payload.get("content") or payload.get("rich_output")
                        or (payload.get("task_status") != "completed" and payload.get("terminal_reason")))):
            return None
        scope = self._scope(scope_fields, generation)
        canonical = dict(payload)
        canonical.pop("result_recovery", None)
        body = json.dumps(canonical, ensure_ascii=False, separators=(",", ":")).encode("utf-8")
        digest = hashlib.sha256(body).hexdigest()
        pages = (len(body) + PAGE_BYTES - 1) // PAGE_BYTES
        with _lock:
            db = self._connect()
            try:
                with db:
                    row = db.execute("SELECT digest FROM results WHERE scope=?", (scope,)).fetchone()
                    if row is not None:
                        # The original user-visible result wins over later rerendering/replay.
                        return {"sha256": row[0]}
                    db.execute("INSERT INTO results(scope,digest,bytes,pages) VALUES(?,?,?,?)",
                               (scope, digest, len(body), pages))
                    for page in range(pages):
                        chunk = base64.b64encode(body[page * PAGE_BYTES:(page + 1) * PAGE_BYTES]).decode("ascii")
                        encrypted = encrypt_text(self.path, chunk, purpose=self._purpose(scope, page))
                        db.execute("INSERT INTO pages(scope,page,body) VALUES(?,?,?)", (scope, page, encrypted))
            finally:
                db.close()
        return {"sha256": digest}

    def page(self, request: dict, *, client_route_id: str) -> dict | None:
        fields = identity(request)
        generation = execution_generation(request)
        nonce, page = request.get("request_id"), request.get("page_index")
        if (fields is None or generation is None or fields["client_route_id"] != client_route_id or not client_route_id
                or not isinstance(nonce, str) or not 1 <= len(nonce) <= 128
                or type(page) is not int or not 0 <= page <= 2**31 - 1):
            return None
        response = {**fields, "request_id": nonce, "type": "agent_task_result_page",
                    "page_index": page, "status": "unavailable", "execution_generation": generation}
        scope = self._scope(fields, generation)
        with _lock:
            db = self._connect()
            try:
                row = db.execute("SELECT digest,bytes,pages,acknowledged FROM results WHERE scope=?", (scope,)).fetchone()
                if (row is None or row[3] or page >= row[2]
                        or request.get("sha256", "") not in ("", row[0])):
                    return response
                chunk = db.execute("SELECT body FROM pages WHERE scope=? AND page=?", (scope, page)).fetchone()
                if chunk is None:
                    return response
                encoded = decrypt_text(self.path, chunk[0], purpose=self._purpose(scope, page))
            finally:
                db.close()
        raw = base64.b64decode(encoded, validate=True)
        return {**response, "status": "ready", "sha256": row[0], "total_bytes": row[1], "page_count": row[2],
                "page_sha256": hashlib.sha256(raw).hexdigest(), "data_b64": encoded}

    def acknowledge(self, request: dict, *, client_route_id: str) -> bool:
        fields = identity(request)
        generation = execution_generation(request)
        if fields is None or generation is None or fields["client_route_id"] != client_route_id or not client_route_id:
            return False
        scope = self._scope(fields, generation)
        with _lock:
            db = self._connect()
            try:
                with db:
                    updated = db.execute("UPDATE results SET acknowledged=1 WHERE scope=? AND digest=?",
                                         (scope, str(request.get("sha256") or ""))).rowcount
                    if updated:
                        db.execute("DELETE FROM pages WHERE scope=?", (scope,))
                    return bool(updated)
            finally:
                db.close()

    def receipt_confirmation(self, request: dict, *, client_route_id: str) -> dict | None:
        receipt_id = request.get("receipt_id")
        if not isinstance(receipt_id, str) or not re.fullmatch(r"[a-f0-9]{64}", receipt_id):
            return None
        if not self.acknowledge(request, client_route_id=client_route_id):
            return None
        # acknowledge commits before this reply. Its retained digest tombstone
        # makes a duplicate request confirmable after a lost downlink or restart.
        return {**identity(request), "type": "agent_task_result_receipt_confirmed",
                "execution_generation": execution_generation(request), "receipt_id": receipt_id,
                "sha256": request["sha256"]}


archive = TaskResultArchive()
