"""SQLite ciphertext CAS with independent read/write capabilities and resume state."""
from __future__ import annotations

from contextlib import contextmanager
import json
from pathlib import Path
import secrets
import sqlite3
import time
from typing import Callable

from blob_protocol import (
    BlobError, SESSION_PATTERN, canonical, capability_digest, checked_hex,
    manifest, missing_bitmap, sha256,
)


class BlobStore:
    def __init__(self, path: Path, *, quota_bytes: int = 10 * 1024**3,
                 max_sessions: int = 10000, ttl_seconds: int = 7 * 86400,
                 clock: Callable[[], float] = time.time):
        if quota_bytes <= 0 or max_sessions <= 0 or ttl_seconds <= 0:
            raise ValueError("Blob storage capacity must be positive")
        self.path = Path(path)
        self.path.parent.mkdir(parents=True, exist_ok=True)
        self.quota_bytes, self.max_sessions = quota_bytes, max_sessions
        self.ttl_seconds, self.clock = ttl_seconds, clock
        db = sqlite3.connect(self.path)
        try:
            db.execute("PRAGMA journal_mode=WAL")
            db.executescript("""
                CREATE TABLE IF NOT EXISTS sessions (
                    id TEXT PRIMARY KEY, root TEXT NOT NULL, manifest BLOB NOT NULL,
                    read_hash TEXT NOT NULL, write_hash TEXT NOT NULL,
                    expires REAL NOT NULL);
                CREATE INDEX IF NOT EXISTS sessions_expiry ON sessions(expires);
                CREATE TABLE IF NOT EXISTS chunks (
                    digest TEXT PRIMARY KEY, size INTEGER NOT NULL, data BLOB NOT NULL);
                CREATE TABLE IF NOT EXISTS entries (
                    session_id TEXT NOT NULL REFERENCES sessions(id) ON DELETE CASCADE,
                    ordinal INTEGER NOT NULL, digest TEXT NOT NULL, size INTEGER NOT NULL,
                    PRIMARY KEY(session_id, ordinal));
                CREATE INDEX IF NOT EXISTS entries_digest ON entries(digest);
                CREATE TABLE IF NOT EXISTS usage (id INTEGER PRIMARY KEY CHECK(id=1),
                    bytes INTEGER NOT NULL CHECK(bytes>=0));
                INSERT OR IGNORE INTO usage VALUES(1, 0);
            """)
        finally:
            db.close()

    @contextmanager
    def _db(self, *, write: bool = False):
        db = sqlite3.connect(self.path, timeout=30, isolation_level=None)
        db.row_factory = sqlite3.Row
        db.execute("PRAGMA foreign_keys=ON")
        db.execute("PRAGMA synchronous=FULL")
        try:
            db.execute("BEGIN IMMEDIATE" if write else "BEGIN")
            yield db
            db.commit()
        except BaseException:
            db.rollback()
            raise
        finally:
            db.close()

    def _session(self, db, session_id: str, token: str, *, write: bool = False):
        checked_hex(session_id, SESSION_PATTERN)
        supplied = capability_digest(token)
        row = db.execute("SELECT * FROM sessions WHERE id=?", (session_id,)).fetchone()
        key = "write_hash" if write else "read_hash"
        if row is None or not secrets.compare_digest(row[key], supplied):
            raise BlobError("blob_not_found", 404)
        if row["expires"] <= self.clock():
            raise BlobError("blob_expired", 410)
        return row

    def authorize(self, session_id: str, token: str, *, write: bool = False):
        with self._db() as db:
            self._session(db, session_id, token, write=write)

    def create(self, session_id: str, public_manifest: dict, read_token: str,
               write_token: str) -> dict:
        checked_hex(session_id, SESSION_PATTERN)
        public = manifest(public_manifest)
        raw = canonical(public)
        root = sha256(raw)
        read_hash, write_hash = capability_digest(read_token), capability_digest(write_token)
        if secrets.compare_digest(read_hash, write_hash):
            raise BlobError("capabilities_must_differ")
        with self._db(write=True) as db:
            existing = db.execute("SELECT * FROM sessions WHERE id=?", (session_id,)).fetchone()
            if existing:
                if (existing["root"] != root
                        or not secrets.compare_digest(existing["read_hash"], read_hash)
                        or not secrets.compare_digest(existing["write_hash"], write_hash)):
                    raise BlobError("blob_creation_conflict", 409)
                if existing["expires"] <= self.clock():
                    raise BlobError("blob_expired", 410)
                return {"root": root, "expires": existing["expires"]}
            count = db.execute("SELECT COUNT(*) FROM sessions").fetchone()[0]
            if count >= self.max_sessions:
                raise BlobError("relay_session_capacity", 507)
            expires = self.clock() + self.ttl_seconds
            db.execute("INSERT INTO sessions VALUES(?,?,?,?,?,?)",
                       (session_id, root, raw, read_hash, write_hash, expires))
            db.executemany("INSERT INTO entries VALUES(?,?,?,?)", (
                (session_id, index, chunk["sha256"], chunk["size"])
                for index, chunk in enumerate(public["chunks"])))
            return {"root": root, "expires": expires}

    def get_manifest(self, session_id: str, token: str) -> dict:
        with self._db() as db:
            row = self._session(db, session_id, token)
            return json.loads(row["manifest"])

    def status(self, session_id: str, token: str) -> dict:
        with self._db() as db:
            row = self._session(db, session_id, token, write=True)
            missing = db.execute("""SELECT e.ordinal FROM entries e LEFT JOIN chunks c
                ON c.digest=e.digest AND c.size=e.size WHERE e.session_id=?
                AND c.digest IS NULL ORDER BY e.ordinal""", (session_id,)).fetchall()
            count = db.execute("SELECT COUNT(*) FROM entries WHERE session_id=?",
                               (session_id,)).fetchone()[0]
            return {"root": row["root"], "chunk_count": count,
                    "missing_bitmap": missing_bitmap([item[0] for item in missing], count),
                    "complete": not missing, "expires": row["expires"]}

    def _entry(self, db, session_id: str, index: int):
        if type(index) is not int or index < 0:
            raise BlobError("invalid_chunk_index")
        entry = db.execute("SELECT digest,size FROM entries WHERE session_id=? AND ordinal=?",
                           (session_id, index)).fetchone()
        if entry is None:
            raise BlobError("chunk_not_found", 404)
        return entry

    def put(self, session_id: str, token: str, index: int, data: bytes) -> bool:
        digest = sha256(data)
        with self._db(write=True) as db:
            self._session(db, session_id, token, write=True)
            entry = self._entry(db, session_id, index)
            if len(data) != entry["size"] or digest != entry["digest"]:
                raise BlobError("ciphertext_hash_mismatch", 409)
            existing = db.execute("SELECT size,data FROM chunks WHERE digest=?", (digest,)).fetchone()
            if existing and existing["size"] == len(data) and sha256(existing["data"]) == digest:
                return False
            previous = existing["size"] if existing else 0
            used = db.execute("SELECT bytes FROM usage WHERE id=1").fetchone()[0]
            if used - previous + len(data) > self.quota_bytes:
                raise BlobError("relay_storage_capacity", 507)
            db.execute("INSERT OR REPLACE INTO chunks VALUES(?,?,?)", (digest, len(data), data))
            db.execute("UPDATE usage SET bytes=bytes+? WHERE id=1", (len(data) - previous,))
            return True

    def get(self, session_id: str, token: str, index: int) -> bytes:
        with self._db() as db:
            self._session(db, session_id, token)
            entry = self._entry(db, session_id, index)
            chunk = db.execute("SELECT size,data FROM chunks WHERE digest=?",
                               (entry["digest"],)).fetchone()
            if chunk is None:
                raise BlobError("chunk_not_ready", 404)
            data = bytes(chunk["data"])
        if len(data) != entry["size"] or sha256(data) != entry["digest"]:
            # Normal downloads take no writer lock. Recheck under the lock before
            # invalidation so a concurrent sender's repaired chunk is not deleted.
            with self._db(write=True) as db:
                current = db.execute("SELECT size,data FROM chunks WHERE digest=?",
                                     (entry["digest"],)).fetchone()
                if current and (len(current["data"]) != current["size"]
                                or sha256(current["data"]) != entry["digest"]):
                    db.execute("DELETE FROM chunks WHERE digest=?", (entry["digest"],))
                    db.execute("UPDATE usage SET bytes=bytes-? WHERE id=1", (current["size"],))
            raise BlobError("corrupt_chunk_requires_repair", 409)
        return data

    def delete(self, session_id: str, token: str):
        with self._db(write=True) as db:
            self._session(db, session_id, token, write=True)
            db.execute("DELETE FROM sessions WHERE id=?", (session_id,))

    def collect(self, *, batch: int = 32) -> dict:
        if not 1 <= batch <= 256:
            raise ValueError("Invalid maintenance batch")
        with self._db(write=True) as db:
            expired = db.execute("SELECT id FROM sessions WHERE expires<=? ORDER BY expires LIMIT ?",
                                 (self.clock(), batch)).fetchall()
            db.executemany("DELETE FROM sessions WHERE id=?", [(row[0],) for row in expired])
            orphaned = db.execute("""SELECT digest,size FROM chunks c WHERE NOT EXISTS
                (SELECT 1 FROM entries e WHERE e.digest=c.digest) LIMIT ?""", (batch,)).fetchall()
            reclaimed = sum(row["size"] for row in orphaned)
            db.executemany("DELETE FROM chunks WHERE digest=?", [(row[0],) for row in orphaned])
            db.execute("UPDATE usage SET bytes=bytes-? WHERE id=1", (reclaimed,))
            return {"expired_sessions": len(expired), "chunks": len(orphaned),
                    "reclaimed_bytes": reclaimed}
