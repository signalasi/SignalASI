"""Encrypted output jobs with claim fencing and recipient-persistence receipts."""
from __future__ import annotations

from contextlib import contextmanager
import json
from pathlib import Path, PurePosixPath
import re
import sqlite3
import time
import uuid

from blob_artifact_contract import receipt_matches, validate_manifest
from blob_client import relay_origin
from blob_protocol import BlobError, canonical, checked_hex, sha256
from secure_state import decrypt_text, encrypt_text, seal_identifier

PURPOSE = "blob.artifact-journal.v1"


def validate_job(value: dict) -> dict:
    keys = {"manifest", "source_relative", "peer_fingerprint", "local_fingerprint", "origin", "source_id"}
    if not isinstance(value, dict) or set(value) != keys:
        raise BlobError("invalid_artifact_blob_job")
    manifest = validate_manifest(value["manifest"])
    path = value["source_relative"]
    if (not isinstance(path, str) or not 1 <= len(path) <= 4096 or "\\" in path or ":" in path
            or any(ord(char) < 32 for char in path)
            or PurePosixPath(path).is_absolute() or any(part in ("", ".", "..") for part in path.split("/"))):
        raise BlobError("invalid_artifact_blob_source")
    if (not isinstance(value["source_id"], str) or not 1 <= len(value["source_id"]) <= 256
            or any(ord(char) < 32 for char in value["source_id"])):
        raise BlobError("invalid_artifact_blob_source")
    return {**value, "manifest": manifest, "origin": relay_origin(value["origin"]),
            "peer_fingerprint": checked_hex(value["peer_fingerprint"]),
            "local_fingerprint": checked_hex(value["local_fingerprint"])}


class BlobArtifactJournal:
    def __init__(self, path: Path):
        self.path = Path(path)
        self.path.parent.mkdir(parents=True, exist_ok=True)
        with self._db() as db:
            db.execute("""CREATE TABLE IF NOT EXISTS artifact_jobs (
                id TEXT PRIMARY KEY, digest TEXT NOT NULL, body TEXT NOT NULL,
                phase TEXT NOT NULL, state TEXT NOT NULL, due REAL NOT NULL,
                attempts INTEGER NOT NULL DEFAULT 0, claim TEXT NOT NULL DEFAULT '',
                error TEXT NOT NULL DEFAULT '', failure TEXT NOT NULL DEFAULT '')""")
            db.execute("CREATE INDEX IF NOT EXISTS artifact_jobs_due ON artifact_jobs(state,due,id)")
        from blob_artifact_batches import BlobArtifactBatches
        self.batches = BlobArtifactBatches(self)

    @contextmanager
    def _db(self):
        db = sqlite3.connect(self.path, timeout=10)
        try:
            db.row_factory = sqlite3.Row
            db.execute("PRAGMA synchronous=FULL")
            with db:
                yield db
        finally:
            db.close()

    def _id(self, transfer_id: str) -> str:
        return sha256(seal_identifier(self.path, checked_hex(transfer_id), purpose=PURPOSE).encode())

    def _encode(self, job_id: str, body: dict, revision: int = 0) -> str:
        return encrypt_text(self.path, canonical({"id": job_id, "value": body,
            "transport_revision": revision}).decode(), purpose=PURPOSE)

    def _decode_record(self, row) -> tuple[dict, int]:
        try:
            value = json.loads(decrypt_text(self.path, row["body"], purpose=PURPOSE))
            revision = value["transport_revision"]
            if value["id"] != row["id"] or type(revision) is not int or not 0 <= revision <= 2**53 - 1:
                raise ValueError()
            return validate_job(value["value"]), revision
        except Exception:
            raise BlobError("artifact_blob_checkpoint_invalid", 409) from None

    def _decode(self, row) -> dict:
        return self._decode_record(row)[0]

    def enqueue(self, body: dict, *, now: float | None = None) -> str:
        body = validate_job(body)
        job_id = self._id(body["manifest"]["transfer_id"])
        digest, encoded = sha256(canonical(body)), self._encode(job_id, body)
        with self._db() as db:
            db.execute("BEGIN IMMEDIATE")
            previous = db.execute("SELECT digest FROM artifact_jobs WHERE id=?", (job_id,)).fetchone()
            if previous and previous["digest"] != digest:
                raise BlobError("artifact_blob_job_conflict", 409)
            if not previous:
                db.execute("INSERT INTO artifact_jobs(id,digest,body,phase,state,due) VALUES(?,?,?,'upload','pending',?)",
                           (job_id, digest, encoded, time.time() if now is None else now))
        return job_id

    def recover(self):
        """Caller must hold exclusive process ownership before clearing old claims."""
        with self._db() as db:
            db.execute("UPDATE artifact_jobs SET state='pending',claim='',due=0 WHERE state='running'")
        self.batches.recover()

    def claim_due(self, limit: int, *, now: float | None = None, exclude_ids=()) -> list[dict]:
        if type(limit) is not int or not 1 <= limit <= 64:
            return []
        excluded = tuple(checked_hex(value) for value in exclude_ids)
        if len(excluded) > 64:
            raise ValueError("Too many active artifact claims")
        exclusion = " AND id NOT IN (" + ",".join("?" for _ in excluded) + ")" if excluded else ""
        with self._db() as db:
            db.execute("BEGIN IMMEDIATE")
            rows = db.execute("SELECT * FROM artifact_jobs WHERE state='pending' AND due<=?" + exclusion + " ORDER BY due,id LIMIT ?",
                              (time.time() if now is None else now, *excluded, limit)).fetchall()
            jobs = []
            for row in rows:
                try:
                    body, revision = self._decode_record(row)
                except BlobError:
                    db.execute("UPDATE artifact_jobs SET state='quarantined',error='artifact_blob_checkpoint_invalid' WHERE id=?",
                               (row["id"],))
                    continue
                claim = uuid.uuid4().hex
                db.execute("UPDATE artifact_jobs SET state='running',claim=? WHERE id=?", (claim, row["id"]))
                jobs.append({"id": row["id"], "claim": claim, "phase": row["phase"],
                             "attempts": row["attempts"], "body": body, "failure": row["failure"],
                             "transport_revision": revision})
            return jobs

    def reserve_transport(self, job: dict) -> int:
        """Durably allocate before creating new key/nonce material; gaps are safe."""
        with self._db() as db:
            db.execute("BEGIN IMMEDIATE")
            row = db.execute("SELECT * FROM artifact_jobs WHERE id=? AND claim=? AND state='running' AND phase='upload'",
                             (job["id"], job["claim"])).fetchone()
            if row is None:
                raise BlobError("transfer_cancelled", 499)
            body, revision = self._decode_record(row)
            if revision >= 2**53 - 1:
                raise BlobError("artifact_blob_revision_exhausted", 409)
            revision += 1
            db.execute("UPDATE artifact_jobs SET body=? WHERE id=?", (self._encode(job["id"], body, revision), job["id"]))
        job["transport_revision"] = revision
        return revision

    def begin_restage(self, job: dict) -> bool:
        with self._db() as db:
            changed = db.execute("UPDATE artifact_jobs SET phase='restage' "
                "WHERE id=? AND claim=? AND state='running' AND phase='upload'", (job["id"], job["claim"])).rowcount == 1
        if changed:
            job["phase"] = "restage"
        return changed

    def finish_restage(self, job: dict) -> bool:
        with self._db() as db:
            return db.execute("UPDATE artifact_jobs SET phase='upload',state='pending',claim='',due=0 "
                "WHERE id=? AND claim=? AND state='running' AND phase='restage'", (job["id"], job["claim"])).rowcount == 1

    def current(self, job: dict) -> bool:
        with self._db() as db:
            return db.execute("SELECT 1 FROM artifact_jobs WHERE id=? AND claim=? AND state='running'",
                              (job["id"], job["claim"])).fetchone() is not None

    def defer(self, job: dict, *, error: str = "", now: float | None = None, delay: float | None = None) -> bool:
        if error and not re.fullmatch(r"[a-z][a-z0-9_]{0,95}", error):
            error = "artifact_blob_transfer_failed"
        attempts = min(job["attempts"] + 1, 2147483647)
        if delay is not None and not 1 <= delay <= 300:
            raise ValueError("Invalid artifact retry delay")
        due = (time.time() if now is None else now) + (delay if delay is not None else min(300, 2 ** min(attempts, 9)))
        with self._db() as db:
            return db.execute("UPDATE artifact_jobs SET state='pending',claim='',due=?,attempts=?,error=? "
                              "WHERE id=? AND claim=? AND state='running'",
                              (due, attempts, error, job["id"], job["claim"])).rowcount == 1

    def accept_receipt(self, receipt: dict, *, route: str, source: str, peer_fingerprint: str,
                       local_fingerprint: str, conversation_id: str | None = None,
                       now: float | None = None) -> bool:
        try:
            job_id = self._id(receipt.get("transfer_id"))
        except (BlobError, AttributeError):
            return False
        with self._db() as db:
            db.execute("BEGIN IMMEDIATE")
            row = db.execute("SELECT * FROM artifact_jobs WHERE id=?", (job_id,)).fetchone()
            if not row:
                return False
            if row["state"] == "held":
                return False
            body = self._decode(row)
            if (body["manifest"]["client_route_id"] != route or body["source_id"] != source
                    or (conversation_id is not None and body["manifest"]["conversation_id"] != conversation_id)
                    or body["peer_fingerprint"] != peer_fingerprint or body["local_fingerprint"] != local_fingerprint
                    or not receipt_matches(body["manifest"], receipt)):
                return False
            if row["state"] == "done" or row["phase"] == "cleanup":
                return True
            # A stored receipt may arrive before upload returns. Invalidate that
            # worker's claim so it cannot overwrite cleanup with an upload retry.
            db.execute("UPDATE artifact_jobs SET phase='cleanup',state='pending',claim='',due=?,error='' WHERE id=?",
                       (time.time() if now is None else now, job_id))
            return True

    def finish(self, job: dict) -> bool:
        with self._db() as db:
            return db.execute("UPDATE artifact_jobs SET state='done',claim='',error='' "
                              "WHERE id=? AND claim=? AND state='running' AND phase='cleanup'",
                              (job["id"], job["claim"])).rowcount == 1

    def fail(self, job: dict, code: str) -> bool:
        if not re.fullmatch(r"[a-z][a-z0-9_]{0,95}", code):
            code = "artifact_blob_transfer_failed"
        with self._db() as db:
            changed = db.execute("UPDATE artifact_jobs SET phase='failure',failure=? "
                                 "WHERE id=? AND claim=? AND state='running' AND phase IN ('upload','restage')",
                                 (code, job["id"], job["claim"])).rowcount == 1
        if changed:
            job.update(phase="failure", failure=code)
        return changed

    def failure_observed(self, job: dict) -> bool:
        with self._db() as db:
            return db.execute("UPDATE artifact_jobs SET state='failed',claim='' "
                              "WHERE id=? AND claim=? AND state='running' AND phase='failure'",
                              (job["id"], job["claim"])).rowcount == 1

    def quarantined(self, limit: int = 16) -> list[str]:
        if type(limit) is not int or not 1 <= limit <= 64:
            return []
        with self._db() as db:
            return [row[0] for row in db.execute("SELECT id FROM artifact_jobs WHERE state='quarantined' ORDER BY id LIMIT ?",
                                               (limit,))]

    def quarantine_observed(self, job_id: str):
        with self._db() as db:
            db.execute("UPDATE artifact_jobs SET state='quarantined_notified' WHERE id=? AND state='quarantined'", (job_id,))

    def next_due(self) -> float | None:
        with self._db() as db:
            row = db.execute("SELECT due FROM artifact_jobs WHERE state='pending' ORDER BY due,id LIMIT 1").fetchone()
            return row[0] if row else None

    def snapshot(self) -> dict:
        with self._db() as db:
            return {row[0]: row[1] for row in db.execute("SELECT state,count(*) FROM artifact_jobs GROUP BY state")}
