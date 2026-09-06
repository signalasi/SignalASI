from concurrent.futures import ThreadPoolExecutor
from pathlib import Path
import tempfile
import threading
import unittest

from blob_artifact_contract import stored_receipt
from blob_artifact_journal import BlobArtifactJournal
from blob_protocol import BlobError
from test_blob_artifact_contract import artifact_manifest


def artifact_job(**changes):
    return {"manifest": artifact_manifest(**changes), "source_relative": "task/output/homework.png",
            "peer_fingerprint": "5" * 64, "local_fingerprint": "6" * 64,
            "source_id": "phone-signal-name", "origin": "https://blob.test"}


class BlobArtifactJournalTest(unittest.TestCase):
    def setUp(self):
        temp = tempfile.TemporaryDirectory(prefix="blob-artifact-journal-")
        self.addCleanup(temp.cleanup)
        self.path = Path(temp.name) / "jobs.sqlite3"
        self.journal = BlobArtifactJournal(self.path)

    def receive(self, receipt=None, **changes):
        return self.journal.accept_receipt(receipt or stored_receipt(artifact_manifest()), now=10,
            **{"route": "a" * 22, "source": "phone-signal-name", "peer_fingerprint": "5" * 64,
               "local_fingerprint": "6" * 64, **changes})

    def test_transport_reservation_survives_crash_before_creating_staging(self):
        self.journal.enqueue(artifact_job(), now=0)
        old = self.journal.claim_due(1, now=0)[0]
        self.assertEqual(1, self.journal.reserve_transport(old))
        restarted = BlobArtifactJournal(self.path)
        restarted.recover()
        fresh = restarted.claim_due(1, now=0)[0]
        self.assertEqual(1, fresh["transport_revision"])
        self.assertEqual(2, restarted.reserve_transport(fresh))
        with self.assertRaises(BlobError):
            restarted.reserve_transport(old)

    def test_restage_phase_survives_crash_and_receipt_can_still_win(self):
        self.journal.enqueue(artifact_job(), now=0)
        work = self.journal.claim_due(1, now=0)[0]
        self.journal.reserve_transport(work)
        self.assertTrue(self.journal.begin_restage(work))
        self.assertFalse(self.journal.begin_restage(work))
        self.journal.recover()
        resumed = self.journal.claim_due(1, now=0)[0]
        self.assertEqual("restage", resumed["phase"])
        self.assertEqual(1, resumed["transport_revision"])
        self.assertTrue(self.receive())
        self.assertFalse(self.journal.finish_restage(resumed))
        with self.assertRaises(BlobError):
            self.journal.reserve_transport(resumed)
        self.assertEqual("cleanup", self.journal.claim_due(1, now=10)[0]["phase"])

    def test_restage_completion_requires_current_claim_and_reserves_only_when_rebuilt(self):
        self.journal.enqueue(artifact_job(), now=0)
        old = self.journal.claim_due(1, now=0)[0]
        self.journal.reserve_transport(old)
        self.journal.begin_restage(old)
        self.assertTrue(self.journal.finish_restage(old))
        self.assertFalse(self.journal.finish_restage(old))
        fresh = self.journal.claim_due(1, now=0)[0]
        self.assertEqual("upload", fresh["phase"])
        self.assertEqual(1, fresh["transport_revision"])
        self.assertEqual(2, self.journal.reserve_transport(fresh))

    def test_revision_exhaustion_is_explicit_and_does_not_wrap_to_an_old_revision(self):
        body = artifact_job()
        job_id = self.journal.enqueue(body, now=0)
        with self.journal._db() as db:
            db.execute("UPDATE artifact_jobs SET body=? WHERE id=?", (self.journal._encode(job_id, body, 2**53 - 1), job_id))
        work = self.journal.claim_due(1, now=0)[0]
        with self.assertRaises(BlobError) as failure:
            self.journal.reserve_transport(work)
        self.assertEqual("artifact_blob_revision_exhausted", failure.exception.code)
        self.assertEqual(2**53 - 1, work["transport_revision"])

    def test_identity_failure_while_restaging_is_observed_instead_of_retried_forever(self):
        self.journal.enqueue(artifact_job(), now=0)
        work = self.journal.claim_due(1, now=0)[0]
        self.journal.begin_restage(work)
        self.assertTrue(self.journal.fail(work, "artifact_blob_identity_changed"))
        self.assertTrue(self.journal.failure_observed(work))
        self.assertEqual({"failed": 1}, self.journal.snapshot())

    def test_enqueue_persists_encrypted_and_duplicate_does_not_reset_claim(self):
        body = artifact_job()
        job_id = self.journal.enqueue(body, now=0)
        work = self.journal.claim_due(1, now=0)[0]
        self.assertEqual(job_id, self.journal.enqueue(body, now=0))
        self.assertTrue(self.journal.current(work))
        self.assertEqual({"running": 1}, self.journal.snapshot())
        raw = self.path.read_bytes()
        for private in (b"homework.png", b"phone-signal-name", b"https://blob.test", b"conversation"):
            self.assertNotIn(private, raw)
        restarted = BlobArtifactJournal(self.path)
        restarted.recover()
        recovered = restarted.claim_due(1, now=0)[0]
        self.assertEqual(body, recovered["body"])
        self.assertFalse(restarted.current(work))
        self.assertFalse(restarted.defer(work, now=0))

    def test_stored_receipt_wins_a_race_with_upload_and_survives_restart(self):
        self.journal.enqueue(artifact_job(), now=0)
        uploading = self.journal.claim_due(1, now=0)[0]
        self.assertFalse(self.journal.finish(uploading))
        self.assertTrue(self.receive())
        self.assertFalse(self.journal.current(uploading))
        self.assertFalse(self.journal.defer(uploading, error="late_worker_failure", now=0))
        restarted = BlobArtifactJournal(self.path)
        restarted.recover()
        cleanup = restarted.claim_due(1, now=10)[0]
        self.assertEqual("cleanup", cleanup["phase"])
        self.assertTrue(self.receive())
        self.assertTrue(restarted.current(cleanup))
        self.assertTrue(restarted.finish(cleanup))
        self.journal.enqueue(artifact_job(), now=0)
        self.assertEqual({"done": 1}, restarted.snapshot())
        self.assertEqual([], restarted.claim_due(1, now=100))

    def test_wrong_peer_route_or_identity_cannot_authorize_source_cleanup(self):
        self.journal.enqueue(artifact_job(), now=0)
        work = self.journal.claim_due(1, now=0)[0]
        for changes in ({"route": "b" * 22}, {"source": "other"},
                        {"peer_fingerprint": "7" * 64}, {"local_fingerprint": "7" * 64}):
            with self.subTest(changes=changes):
                self.assertFalse(self.receive(**changes))
                self.assertTrue(self.journal.current(work))
        for status in ("published", "uploaded", "read", "failed"):
            self.assertFalse(self.receive({**stored_receipt(artifact_manifest()), "status": status}))

    def test_same_artifact_in_new_generation_or_conversation_is_a_distinct_job(self):
        for changes in ({}, {"execution_generation": 2}, {"conversation_id": "other"}):
            self.journal.enqueue(artifact_job(**changes), now=0)
        self.assertTrue(self.receive())
        jobs = self.journal.claim_due(3, now=10)
        self.assertEqual(3, len(jobs))
        self.assertEqual(1, sum(job["phase"] == "cleanup" for job in jobs))
        self.assertEqual(2, sum(job["phase"] == "upload" for job in jobs))

    def test_origin_source_or_identity_replacement_cannot_overwrite_pending_job(self):
        body = artifact_job()
        self.journal.enqueue(body, now=0)
        for field, value in (("origin", "https://other.test"), ("source_relative", "other/output.png"),
                             ("peer_fingerprint", "8" * 64), ("local_fingerprint", "8" * 64)):
            with self.subTest(field=field), self.assertRaises(BlobError):
                self.journal.enqueue({**body, field: value}, now=0)

    def test_backoff_is_not_an_action_budget_and_claiming_is_bounded(self):
        for number in range(20):
            self.journal.enqueue(artifact_job(task_id=f"task-{number}"), now=0)
        for invalid in (0, 65, True, 1.0):
            self.assertEqual([], self.journal.claim_due(invalid, now=0))
        jobs = self.journal.claim_due(4, now=0)
        self.assertEqual(4, len(jobs))
        for job in jobs:
            job["attempts"] = 100000
            self.assertTrue(self.journal.defer(job, error="credential\nSECRET", now=0))
        self.assertEqual({"pending": 20}, self.journal.snapshot())
        remaining = self.journal.claim_due(64, now=299)
        self.assertEqual(16, len(remaining))
        self.assertEqual(4, len(self.journal.claim_due(64, now=300)))

    def test_swapped_encrypted_rows_are_quarantined_without_deleting_evidence(self):
        self.journal.enqueue(artifact_job(task_id="first"), now=0)
        self.journal.enqueue(artifact_job(task_id="second"), now=0)
        with self.journal._db() as db:
            rows = db.execute("SELECT id,body FROM artifact_jobs ORDER BY id").fetchall()
            db.execute("UPDATE artifact_jobs SET body=? WHERE id=?", (rows[1]["body"], rows[0]["id"]))
            db.execute("UPDATE artifact_jobs SET body=? WHERE id=?", (rows[0]["body"], rows[1]["id"]))
        self.assertEqual([], self.journal.claim_due(4, now=0))
        self.assertEqual({"quarantined": 2}, self.journal.snapshot())

    def test_concurrent_enqueue_and_claim_do_not_duplicate_jobs(self):
        barrier = threading.Barrier(8)
        def create(_):
            barrier.wait(timeout=10)
            return self.journal.enqueue(artifact_job(), now=0)
        with ThreadPoolExecutor(max_workers=8) as pool:
            ids = list(pool.map(create, range(8)))
            claimed = list(pool.map(lambda _: self.journal.claim_due(1, now=0), range(8)))
        self.assertEqual(1, len(set(ids)))
        self.assertEqual(1, sum(len(batch) for batch in claimed))

    def test_workspace_escape_is_rejected_before_persistence(self):
        for path in ("../secret", "C:/secret", "/secret", "task/../secret", "task\\secret", "task//secret", "task/\x00secret"):
            with self.subTest(path=path), self.assertRaises(BlobError):
                self.journal.enqueue({**artifact_job(), "source_relative": path}, now=0)
        self.assertEqual({}, self.journal.snapshot())
