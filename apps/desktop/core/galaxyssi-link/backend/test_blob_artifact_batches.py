import io
from pathlib import Path
import tempfile
import unittest
from unittest.mock import Mock, patch

from blob_artifact_contract import stored_receipt
from blob_artifact_journal import BlobArtifactJournal
from blob_artifact_sender import BlobArtifactSender
from blob_protocol import BlobError
from test_blob_artifact_journal import artifact_job


class ArtifactBatchTests(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)
        self.journal = BlobArtifactJournal(self.root / "jobs.sqlite3")
        self.bodies = [artifact_job(artifact_id=char * 64, name=f"{char}.bin") for char in ("a", "b")]

    def receipt(self, body=None):
        body = body or self.bodies[0]
        return self.journal.accept_receipt(stored_receipt(body["manifest"]),
            route=body["manifest"]["client_route_id"], source=body["source_id"],
            peer_fingerprint=body["peer_fingerprint"], local_fingerprint=body["local_fingerprint"])

    def test_whole_batch_is_held_before_source_registration(self):
        self.journal.batches.enqueue(self.bodies)
        self.assertEqual({"held": 2}, self.journal.snapshot())
        self.assertEqual([], self.journal.claim_due(2))
        self.assertFalse(self.receipt())
        work = self.journal.batches.claim_due()[0]
        self.assertTrue(self.journal.batches.finish(work))
        self.assertEqual({"pending": 2}, self.journal.snapshot())
        self.assertTrue(self.receipt())

    def test_restart_recovers_registration_but_cannot_unlock_jobs(self):
        self.journal.batches.enqueue(self.bodies)
        old = self.journal.batches.claim_due()[0]
        resumed = BlobArtifactJournal(self.journal.path)
        resumed.recover()
        fresh = resumed.batches.claim_due()[0]
        self.assertNotEqual(old["claim"], fresh["claim"])
        self.assertFalse(resumed.batches.finish(old))
        self.assertFalse(resumed.batches.fail(old, "source_changed"))
        self.assertEqual([], resumed.claim_due(2))
        self.assertTrue(resumed.batches.finish(fresh))

    def test_conflict_rolls_back_preceding_member_inserts(self):
        self.journal.enqueue(self.bodies[1])
        with self.assertRaisesRegex(BlobError, "batch_job_owned"):
            self.journal.batches.enqueue(self.bodies)
        self.assertEqual({"pending": 1}, self.journal.snapshot())
        self.assertEqual([], self.journal.batches.claim_due())

    def test_repeated_batch_is_idempotent_order_independent_and_immutable(self):
        batch_id = self.journal.batches.enqueue(self.bodies)
        self.assertEqual(batch_id, self.journal.batches.enqueue(list(reversed(self.bodies))))
        with self.assertRaisesRegex(BlobError, "batch_conflict"):
            self.journal.batches.enqueue(self.bodies, retain_on_desktop=True)
        changed = [{**body, "source_relative": "different/source.bin"} for body in self.bodies]
        with self.assertRaisesRegex(BlobError, "batch_conflict"):
            self.journal.batches.enqueue(changed)
        self.assertEqual({"held": 2}, self.journal.snapshot())

    def test_mixed_identity_or_execution_is_rejected_before_insert(self):
        for other in (artifact_job(artifact_id="c" * 64, turn_id="other"),
                      artifact_job(artifact_id="c" * 64, client_route_id="b" * 22),
                      {**self.bodies[1], "peer_fingerprint": "9" * 64},
                      {**self.bodies[1], "origin": "https://different.test"}):
            with self.subTest(other=other), self.assertRaisesRegex(BlobError, "scope_mismatch"):
                self.journal.batches.enqueue([self.bodies[0], other])
        self.assertEqual({}, self.journal.snapshot())

    def test_duplicate_artifact_rejected_even_if_manifest_names_differ(self):
        with self.assertRaisesRegex(BlobError, "batch_duplicate"):
            self.journal.batches.enqueue([self.bodies[0], artifact_job(artifact_id="a" * 64, name="another.bin")])
        self.assertEqual({}, self.journal.snapshot())

    def test_corrupt_intent_is_quarantined_without_uploading_members(self):
        batch_id = self.journal.batches.enqueue(self.bodies)
        with self.journal._db() as db:
            db.execute("UPDATE artifact_batches SET body='corrupt' WHERE id=?", (batch_id,))
        self.assertEqual([], self.journal.batches.claim_due())
        self.assertEqual([batch_id], self.journal.batches.quarantined())
        self.assertEqual({"held": 2}, self.journal.snapshot())
        self.assertTrue(self.journal.batches.blocks_cleanup("task"))

    def test_tampered_membership_is_not_a_smaller_successful_batch(self):
        batch_id = self.journal.batches.enqueue(self.bodies)
        with self.journal._db() as db:
            db.execute("DELETE FROM artifact_batch_members WHERE job_id=?",
                       (self.journal._id(self.bodies[0]["manifest"]["transfer_id"]),))
        self.assertEqual([], self.journal.batches.claim_due())
        self.assertEqual([batch_id], self.journal.batches.quarantined())
        self.assertEqual([], self.journal.claim_due(2))

    def test_activation_is_atomic_if_one_member_update_fails(self):
        self.journal.batches.enqueue(self.bodies)
        work = self.journal.batches.claim_due()[0]
        target = self.journal._id(self.bodies[1]["manifest"]["transfer_id"])
        with self.journal._db() as db:
            db.execute(f"CREATE TRIGGER refuse_activation BEFORE UPDATE ON artifact_jobs WHEN NEW.id='{target}' "
                       "BEGIN SELECT RAISE(ABORT, 'injected disk failure'); END")
        with self.assertRaises(Exception):
            self.journal.batches.finish(work)
        self.assertEqual({"held": 2}, self.journal.snapshot())
        self.assertTrue(self.journal.batches.current(work))
        with self.journal._db() as db:
            db.execute("DROP TRIGGER refuse_activation")
        self.assertTrue(self.journal.batches.finish(work))

    def test_terminal_registration_failure_becomes_scoped_observations(self):
        self.journal.batches.enqueue(self.bodies)
        work = self.journal.batches.claim_due()[0]
        self.assertTrue(self.journal.batches.fail(work, "artifact_source_missing"))
        jobs = self.journal.claim_due(2)
        self.assertEqual(2, len(jobs))
        self.assertTrue(all(job["phase"] == "failure" and job["failure"] == "artifact_source_missing" for job in jobs))
        self.assertTrue(self.journal.batches.blocks_cleanup("task"))

    def test_other_pending_recipient_batch_blocks_early_workspace_cleanup(self):
        self.journal.batches.enqueue(self.bodies)
        other = artifact_job(artifact_id="c" * 64, client_route_id="b" * 22)
        self.journal.batches.enqueue([other])
        work = self.journal.batches.claim_due()[0]
        self.journal.batches.finish(work)
        self.assertTrue(self.journal.batches.blocks_cleanup("task"))
        self.journal.batches.finish(self.journal.batches.claim_due()[0])
        self.assertFalse(self.journal.batches.blocks_cleanup("task"))
        self.assertFalse(self.journal.batches.blocks_cleanup("another-task"))

    def sender(self, register):
        return BlobArtifactSender(self.root / "sender", settings=lambda body: {**body,
            "enabled": True, "provisioning_token": "e" * 64}, open_source=lambda _: io.BytesIO(),
            publish_offer=lambda *_: True, commit_receipt=lambda *_: True,
            observe_failure=lambda *_: True, observe_quarantine=lambda *_: True, register_sources=register)

    def test_sender_keeps_failed_checkpoint_claim_for_next_tick(self):
        register = Mock(return_value=True)
        sender = self.sender(register)
        sender.enqueue_batch(self.bodies)
        with patch.object(sender.journal.batches, "finish", side_effect=OSError("disk")), \
                patch.object(sender.journal.batches, "defer", side_effect=OSError("disk")):
            with self.assertRaises(OSError):
                sender._register_batches()
        self.assertEqual({"held": 2}, sender.journal.snapshot())
        self.assertIsNotNone(sender._pending_registration)
        sender._register_batches()
        self.assertEqual({"pending": 2}, sender.journal.snapshot())
        self.assertEqual(2, register.call_count)
        self.assertIsNone(sender._pending_registration)

    def test_false_registration_result_never_activates_uploads(self):
        sender = self.sender(Mock(return_value=False))
        sender.enqueue_batch(self.bodies)
        sender._register_batches()
        self.assertEqual({"held": 2}, sender.journal.snapshot())
        self.assertEqual([], sender.journal.claim_due(2))

    def test_sender_does_not_commit_source_receipt_while_another_batch_is_held(self):
        sender = self.sender(Mock(return_value=True))
        sender.commit_receipt = Mock(return_value=True)
        sender.enqueue_batch(self.bodies)
        sender.enqueue_batch([artifact_job(artifact_id="c" * 64, client_route_id="b" * 22)])
        sender._register_batches()
        active = sender.journal.claim_due(3)
        body = active[0]["body"]
        self.assertTrue(sender.accept_receipt(stored_receipt(body["manifest"]),
            route=body["manifest"]["client_route_id"], source=body["source_id"],
            peer_fingerprint=body["peer_fingerprint"], local_fingerprint=body["local_fingerprint"]))
        cleanup = sender.journal.claim_due(1)[0]
        self.assertEqual("cleanup", cleanup["phase"])
        sender._process(cleanup)
        sender.commit_receipt.assert_not_called()

    def test_batch_details_are_encrypted_on_disk(self):
        self.journal.batches.enqueue(self.bodies)
        data = self.journal.path.read_bytes()
        for secret in (b"phone-signal-name", b"https://blob.test", b"output/homework", b"retain_on_desktop"):
            self.assertNotIn(secret, data)


if __name__ == "__main__":
    unittest.main()
