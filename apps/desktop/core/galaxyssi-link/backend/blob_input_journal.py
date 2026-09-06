"""Encrypted, indexed receipt/download jobs; one OS-locked coordinator owns claims."""
from __future__ import annotations

from contextlib import contextmanager
import hashlib
import json
from pathlib import Path
import sqlite3
import time
import uuid

from blob_protocol import BlobError, canonical
from secure_state import decrypt_text, encrypt_text, seal_identifier

PURPOSE = "blob.input-journal.v1"


class BlobInputJournal:
    def __init__(self, path: Path):
        self.path = Path(path)
        self.path.parent.mkdir(parents=True, exist_ok=True)
        with self._db() as db:
            db.execute("PRAGMA journal_mode=WAL")
            db.execute("""CREATE TABLE IF NOT EXISTS jobs (
                id TEXT PRIMARY KEY, digest TEXT NOT NULL, body TEXT NOT NULL,
                phase TEXT NOT NULL, state TEXT NOT NULL, due REAL NOT NULL,
                attempts INTEGER NOT NULL DEFAULT 0, claim TEXT NOT NULL DEFAULT '',
                error TEXT NOT NULL DEFAULT '')""")
            db.execute("CREATE INDEX IF NOT EXISTS jobs_due ON jobs(state, due, id)")

    @contextmanager
    def _db(self):
        db = sqlite3.connect(self.path, timeout=10)
        db.row_factory = sqlite3.Row
        db.execute("PRAGMA synchronous=FULL")
        try:
            with db:
                yield db
        finally:
            db.close()

    def _encode(self, job_id: str, body: dict) -> str:
        return encrypt_text(self.path, canonical({"id": job_id, "value": body}).decode(), purpose=PURPOSE)

    def _decode(self, row) -> dict:
        try:
            value = json.loads(decrypt_text(self.path, row["body"], purpose=PURPOSE))
            if value["id"] != row["id"] or not isinstance(value["value"], dict):
                raise ValueError()
            return value["value"]
        except Exception:
            raise BlobError("input_blob_checkpoint_invalid", 409) from None

    def enqueue(self, body: dict, *, now: float | None = None) -> str:
        now = time.time() if now is None else now
        identity = body["route"] + "\0" + body["manifest"]["transfer_id"]
        job_id = hashlib.sha256(seal_identifier(self.path, identity, purpose=PURPOSE).encode()).hexdigest()
        digest = hashlib.sha256(canonical(body)).hexdigest()
        encoded = self._encode(job_id, body)
        with self._db() as db:
            db.execute("BEGIN IMMEDIATE")
            previous = db.execute("SELECT digest,state FROM jobs WHERE id=?", (job_id,)).fetchone()
            if previous:
                if previous["digest"] != digest:
                    raise BlobError("input_blob_offer_conflict", 409)
                # A repeated offer retries the stored receipt, never discards a live claim.
                if previous["state"] != "running":
                    db.execute("UPDATE jobs SET state='pending',due=? WHERE id=?", (now, job_id))
            else:
                db.execute("INSERT INTO jobs(id,digest,body,phase,state,due) VALUES(?,?,?,'download','pending',?)",
                           (job_id, digest, encoded, now))
        return job_id

    def recover(self):
        """Only call after acquiring exclusive process ownership of this journal."""
        with self._db() as db:
            db.execute("UPDATE jobs SET state='pending',due=0,claim='' WHERE state='running'")

    def claim_due(self, limit: int, *, now: float | None = None) -> list[dict]:
        now = time.time() if now is None else now
        if not 1 <= limit <= 64:
            return []
        with self._db() as db:
            db.execute("BEGIN IMMEDIATE")
            rows = db.execute("SELECT * FROM jobs WHERE state='pending' AND due<=? ORDER BY due,id LIMIT ?",
                              (now, limit)).fetchall()
            jobs = []
            for row in rows:
                token = uuid.uuid4().hex
                try:
                    body = self._decode(row)
                except BlobError:
                    db.execute("UPDATE jobs SET due=?,error='input_blob_checkpoint_invalid' WHERE id=?",
                               (now + 300, row["id"]))
                    continue
                db.execute("UPDATE jobs SET state='running',claim=? WHERE id=?", (token, row["id"]))
                jobs.append({"id": row["id"], "claim": token, "phase": row["phase"],
                             "attempts": row["attempts"], "body": body})
            return jobs

    def receipt_ready(self, job: dict, receipt: dict):
        body = {**job["body"], "receipt": receipt}
        body.pop("offer", None)
        with self._db() as db:
            count = db.execute("UPDATE jobs SET body=?,phase='receipt' WHERE id=? AND state='running' AND claim=?",
                               (self._encode(job["id"], body), job["id"], job["claim"])).rowcount
        if count != 1:
            raise BlobError("input_blob_claim_lost", 409)
        job.update(phase="receipt", body=body)

    def finish(self, job: dict) -> bool:
        with self._db() as db:
            return db.execute("UPDATE jobs SET state='done',claim='',error='' WHERE id=? AND claim=? AND phase='receipt'",
                              (job["id"], job["claim"])).rowcount == 1

    def retry(self, job: dict, error: str, *, now: float | None = None):
        now = time.time() if now is None else now
        # Retry count is diagnostic/backoff only, never an autonomous task/action budget.
        attempts = min(job["attempts"] + 1, 2147483647)
        delay = min(300, 2 ** min(attempts, 9))
        with self._db() as db:
            db.execute("UPDATE jobs SET state='pending',claim='',attempts=?,due=?,error=? WHERE id=? AND claim=?",
                       (attempts, now + delay, error, job["id"], job["claim"]))

    def next_due(self) -> float | None:
        with self._db() as db:
            row = db.execute("SELECT due FROM jobs WHERE state='pending' ORDER BY due,id LIMIT 1").fetchone()
            return row[0] if row else None

    def snapshot(self) -> dict:
        with self._db() as db:
            return {row[0]: row[1] for row in db.execute("SELECT state,count(*) FROM jobs GROUP BY state")}
