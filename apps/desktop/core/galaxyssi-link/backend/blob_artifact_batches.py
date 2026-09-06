"""Recoverable source registration barrier in the same SQLite transaction as output jobs."""
from __future__ import annotations

import json
import re
import time
import uuid

from blob_artifact_journal import PURPOSE, validate_job
from blob_protocol import BlobError, canonical, checked_hex, sha256
from secure_state import decrypt_text, encrypt_text, seal_identifier


def validate_batch_bodies(bodies: list[dict]) -> list[dict]:
    if not bodies:
        raise BlobError("invalid_artifact_blob_batch")
    values = [validate_job(body) for body in bodies]
    scopes = {(tuple(body["manifest"][key] for key in (
        "client_route_id", "task_id", "turn_id", "execution_generation", "conversation_id",
        "desktop_id", "contact_id", "source_message_id", "peer_chat")),
        tuple(body[key] for key in ("source_id", "peer_fingerprint", "local_fingerprint", "origin"))) for body in values}
    if len(scopes) != 1:
        raise BlobError("artifact_blob_batch_scope_mismatch", 409)
    if len({body["manifest"]["artifact_id"] for body in values}) != len(values):
        raise BlobError("artifact_blob_batch_duplicate", 409)
    return values


def _error(code: str) -> str:
    return code if isinstance(code, str) and re.fullmatch(r"[a-z][a-z0-9_]{0,95}", code) else "artifact_blob_batch_failed"


class BlobArtifactBatches:
    def __init__(self, jobs):
        self.jobs = jobs
        with jobs._db() as db:
            db.execute("""CREATE TABLE IF NOT EXISTS artifact_batches (
                id TEXT PRIMARY KEY,digest TEXT NOT NULL,body TEXT NOT NULL,state TEXT NOT NULL,task_key TEXT NOT NULL,
                claim TEXT NOT NULL DEFAULT '',due REAL NOT NULL DEFAULT 0,
                attempts INTEGER NOT NULL DEFAULT 0,error TEXT NOT NULL DEFAULT '')""")
            db.execute("CREATE TABLE IF NOT EXISTS artifact_batch_members ("
                       "job_id TEXT PRIMARY KEY,batch_id TEXT NOT NULL)")
            db.execute("CREATE INDEX IF NOT EXISTS artifact_batches_due ON artifact_batches(state,due,id)")
            db.execute("CREATE INDEX IF NOT EXISTS artifact_batches_task ON artifact_batches(task_key,state)")
            db.execute("CREATE INDEX IF NOT EXISTS artifact_batch_members_batch ON artifact_batch_members(batch_id,job_id)")

    def enqueue(self, bodies: list[dict], *, retain_on_desktop: bool = False, publication: dict | None = None) -> str:
        if not bodies or type(retain_on_desktop) is not bool:
            raise BlobError("invalid_artifact_blob_batch")
        values = validate_batch_bodies(bodies)
        from blob_artifact_publication import validate_publication
        publication = validate_publication(publication, values)
        ids = [self.jobs._id(body["manifest"]["transfer_id"]) for body in values]
        if len(set(ids)) != len(ids):
            raise BlobError("artifact_blob_batch_duplicate", 409)
        batch_id = sha256(canonical(sorted(ids)))
        document = {"id": batch_id, "jobs": sorted(ids), "retain_on_desktop": retain_on_desktop}
        if publication is not None:
            document["publication"] = publication
        digest = sha256(canonical({"batch": document, "jobs": sorted((job_id, sha256(canonical(body)))
                                                                     for job_id, body in zip(ids, values))}))
        encoded = encrypt_text(self.jobs.path, canonical(document).decode(), purpose=PURPOSE + ".batch")
        with self.jobs._db() as db:
            db.execute("BEGIN IMMEDIATE")
            previous = db.execute("SELECT digest FROM artifact_batches WHERE id=?", (batch_id,)).fetchone()
            if previous:
                if previous["digest"] != digest:
                    raise BlobError("artifact_blob_batch_conflict", 409)
                return batch_id
            for job_id, body in zip(ids, values):
                if db.execute("SELECT 1 FROM artifact_jobs WHERE id=?", (job_id,)).fetchone():
                    raise BlobError("artifact_blob_batch_job_owned", 409)
                db.execute("INSERT INTO artifact_jobs(id,digest,body,phase,state,due) VALUES(?,?,?,'upload','held',0)",
                           (job_id, sha256(canonical(body)), self.jobs._encode(job_id, body)))
                db.execute("INSERT INTO artifact_batch_members(job_id,batch_id) VALUES(?,?)", (job_id, batch_id))
            db.execute("INSERT INTO artifact_batches(id,digest,body,state,task_key) VALUES(?,?,?,'pending',?)",
                       (batch_id, digest, encoded, self._task_key(values[0]["manifest"]["task_id"])))
        return batch_id

    def _task_key(self, task_id: str) -> str:
        return sha256(seal_identifier(self.jobs.path, task_id, purpose=PURPOSE + ".batch-task").encode())

    def blocks_cleanup(self, task_id: str) -> bool:
        with self.jobs._db() as db:
            return db.execute("SELECT 1 FROM artifact_batches WHERE task_key=? AND state!='done' LIMIT 1",
                              (self._task_key(task_id),)).fetchone() is not None

    def recover(self):
        with self.jobs._db() as db:
            db.execute("UPDATE artifact_batches SET state='pending',claim='',due=0 WHERE state='running'")

    def _decode(self, db, row, *, require_held=False):
        from blob_artifact_publication import validate_publication
        doc = json.loads(decrypt_text(self.jobs.path, row["body"], purpose=PURPOSE + ".batch"))
        members = db.execute("SELECT j.* FROM artifact_jobs j JOIN artifact_batch_members m "
                             "ON m.job_id=j.id WHERE m.batch_id=? ORDER BY j.id", (row["id"],)).fetchall()
        if (doc["id"] != row["id"] or type(doc["retain_on_desktop"]) is not bool
                or not members or doc["jobs"] != [member["id"] for member in members]
                or (require_held and any(member["state"] != "held" for member in members))):
            raise BlobError("artifact_blob_batch_invalid", 409)
        bodies = [self.jobs._decode(member) for member in members]
        validate_batch_bodies(bodies)
        publication = validate_publication(doc.get("publication"), bodies)
        digest = sha256(canonical({"batch": doc, "jobs": [(member["id"], sha256(canonical(body)))
                                                         for member, body in zip(members, bodies)]}))
        if (row["task_key"] != self._task_key(bodies[0]["manifest"]["task_id"])
                or digest != row["digest"]):
            raise BlobError("artifact_blob_batch_invalid", 409)
        return doc, bodies, publication

    def publication_for(self, scope: dict) -> dict | None:
        from blob_artifact_publication import _SCOPE
        if (not isinstance(scope, dict) or type(scope.get("execution_generation")) is not int or scope["execution_generation"] < 1
                or any(not isinstance(scope.get(key), str) or not scope[key]
                       for key in _SCOPE if key != "execution_generation")):
            raise BlobError("invalid_artifact_blob_replay_scope", 409)
        found = None
        ambiguous = False
        with self.jobs._db() as db:
            db.execute("BEGIN")
            rows = db.execute("SELECT * FROM artifact_batches WHERE task_key=? ORDER BY id",
                              (self._task_key(scope["task_id"]),))
            for row in rows:
                try:
                    _, bodies, publication = self._decode(db, row)
                except Exception:
                    raise BlobError("artifact_blob_batch_invalid", 409) from None
                if publication is None or any(publication[key] != scope[key] for key in _SCOPE):
                    continue
                if found is not None:
                    if found["state"] == "done" and row["state"] != "done":
                        continue
                    if found["state"] == "done" and row["state"] == "done":
                        raise BlobError("artifact_blob_publication_ambiguous", 409)
                    if row["state"] != "done":
                        ambiguous = True
                        continue
                    ambiguous = False
                found = {"id": row["id"], "state": row["state"], "error": row["error"],
                         "publication": publication, "bodies": bodies}
        if ambiguous:
            raise BlobError("artifact_blob_publication_ambiguous", 409)
        return found

    def receipted_publication(self, body: dict) -> dict | None:
        """Read only this transfer's complete batch after authenticated receipts."""
        body = validate_job(body)
        job_id = self.jobs._id(body["manifest"]["transfer_id"])
        with self.jobs._db() as db:
            db.execute("BEGIN")
            row = db.execute("SELECT b.* FROM artifact_batches b JOIN artifact_batch_members m "
                             "ON m.batch_id=b.id WHERE m.job_id=?", (job_id,)).fetchone()
            if row is None:
                raise BlobError("artifact_blob_batch_missing", 409)
            _, bodies, publication = self._decode(db, row)
            if body not in bodies:
                raise BlobError("artifact_blob_batch_scope_mismatch", 409)
            if row["state"] != "done":
                return None
            incomplete = db.execute("SELECT 1 FROM artifact_jobs j JOIN artifact_batch_members m "
                "ON m.job_id=j.id WHERE m.batch_id=? AND (j.phase!='cleanup' "
                "OR j.state NOT IN ('pending','running','done')) LIMIT 1", (row["id"],)).fetchone()
            return publication if incomplete is None else None

    def claim_due(self, limit: int = 1) -> list[dict]:
        if type(limit) is not int or not 1 <= limit <= 64:
            return []
        with self.jobs._db() as db:
            db.execute("BEGIN IMMEDIATE")
            rows = db.execute("SELECT * FROM artifact_batches WHERE state='pending' AND due<=? ORDER BY due,id LIMIT ?",
                              (time.time(), limit)).fetchall()
            claimed = []
            for row in rows:
                try:
                    doc, bodies, publication = self._decode(db, row, require_held=True)
                except Exception:
                    db.execute("UPDATE artifact_batches SET state='quarantined',error='artifact_blob_batch_invalid' WHERE id=?",
                               (row["id"],))
                    continue
                claim = uuid.uuid4().hex
                db.execute("UPDATE artifact_batches SET state='running',claim=? WHERE id=?", (claim, row["id"]))
                claimed.append({"id": row["id"], "claim": claim, "attempts": row["attempts"],
                                "bodies": bodies, "retain_on_desktop": doc["retain_on_desktop"],
                                "publication": publication})
            return claimed

    def finish(self, batch: dict) -> bool:
        with self.jobs._db() as db:
            db.execute("BEGIN IMMEDIATE")
            if not db.execute("SELECT 1 FROM artifact_batches WHERE id=? AND claim=? AND state='running'",
                              (batch["id"], batch["claim"])).fetchone():
                return False
            db.execute("UPDATE artifact_jobs SET state='pending',due=0 WHERE state='held' AND id IN "
                       "(SELECT job_id FROM artifact_batch_members WHERE batch_id=?)", (batch["id"],))
            db.execute("UPDATE artifact_batches SET state='done',claim='' WHERE id=?", (batch["id"],))
            return True

    def current(self, batch: dict) -> bool:
        with self.jobs._db() as db:
            return db.execute("SELECT 1 FROM artifact_batches WHERE id=? AND claim=? AND state='running'",
                              (batch["id"], batch["claim"])).fetchone() is not None

    def defer(self, batch: dict, error: str):
        with self.jobs._db() as db:
            db.execute("UPDATE artifact_batches SET state='pending',claim='',due=?,attempts=attempts+1,error=? "
                       "WHERE id=? AND claim=? AND state='running'",
                       (time.time() + min(60, 2 ** min(batch["attempts"], 6)), _error(error), batch["id"], batch["claim"]))

    def fail(self, batch: dict, code: str) -> bool:
        code = _error(code)
        with self.jobs._db() as db:
            db.execute("BEGIN IMMEDIATE")
            if not db.execute("SELECT 1 FROM artifact_batches WHERE id=? AND claim=? AND state='running'",
                              (batch["id"], batch["claim"])).fetchone():
                return False
            db.execute("UPDATE artifact_jobs SET state='pending',phase='failure',failure=?,due=0 WHERE state='held' AND id IN "
                       "(SELECT job_id FROM artifact_batch_members WHERE batch_id=?)", (code, batch["id"]))
            db.execute("UPDATE artifact_batches SET state='failed',claim='',error=? WHERE id=?", (code, batch["id"]))
            return True

    def quarantined(self) -> list[str]:
        with self.jobs._db() as db:
            return [row[0] for row in db.execute("SELECT id FROM artifact_batches WHERE state='quarantined' ORDER BY id LIMIT 16")]

    def quarantine_observed(self, batch_id: str):
        with self.jobs._db() as db:
            db.execute("UPDATE artifact_batches SET state='quarantined_notified' WHERE id=? AND state='quarantined'",
                       (checked_hex(batch_id),))
