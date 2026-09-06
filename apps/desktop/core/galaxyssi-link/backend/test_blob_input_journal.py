import copy
from contextlib import closing
import hashlib
import os
from pathlib import Path
import secrets
import sqlite3
import tempfile
import unittest
from unittest.mock import patch

from blob_crypto import StagedBlob
from blob_input_contract import OFFER_TYPE, input_binding, validate_input_offer
from blob_input_journal import BlobInputJournal
from blob_protocol import BlobError
from input_attachment_transfer import ATTACHMENT_CHUNK_BYTES, transfer_id_for, validate_input_manifest
from link_protocol import new_route_id


def input_manifest(content: bytes, *, route=None, suffix="1"):
    value = {"client_route_id": route or new_route_id(), "conversation_id": "中文会话-" + suffix,
             "task_id": "blob-task-" + suffix, "turn_id": "turn-" + suffix,
             "contact_id": "desktop-contact", "attachment_id": "attachment-" + suffix,
             "client_message_id": "phone-message-" + suffix, "attachment_ordinal": 0,
             "name": "测试附件.bin", "mime_type": "application/octet-stream",
             "sha256": hashlib.sha256(content).hexdigest(), "size_bytes": len(content),
             "chunk_size_bytes": ATTACHMENT_CHUNK_BYTES,
             "chunk_count": (len(content) + ATTACHMENT_CHUNK_BYTES - 1) // ATTACHMENT_CHUNK_BYTES}
    value["transfer_id"] = transfer_id_for(*(value[key] for key in (
        "client_route_id", "conversation_id", "task_id", "turn_id", "attachment_id", "sha256")))
    return value


class InputJournalFixture(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)
        env = patch.dict(os.environ, {"GALAXYSSI_WORKSPACE_ROOT": str(self.root / "workspace")})
        env.start()
        self.addCleanup(env.stop)
        self.content = b"private article contents" * 20
        self.manifest = input_manifest(self.content)
        self.route = self.manifest["client_route_id"]
        self.source = "phone-verified-identity"
        original = self.root / "original"
        original.write_bytes(self.content)
        self.staged = StagedBlob.prepare(original, self.root / "sender", input_binding(self.manifest))
        self.offer = {"version": 1, "relay": "https://blob.test", "private": self.staged.private,
                      "read_token": secrets.token_hex(32)}
        self.payload = {**self.manifest, "type": OFFER_TYPE, "blob_offer": self.offer}
        self.body = validate_input_offer(self.payload, self.route, self.source, "https://blob.test")
        self.journal = BlobInputJournal(self.root / "receiver" / "jobs.sqlite3")


class BlobInputContractTest(InputJournalFixture):
    def test_validation_has_no_workspace_side_effects(self):
        validate_input_manifest(self.payload, client_route_id=self.route)
        self.assertFalse((self.root / "workspace").exists())

    def test_rejects_wrong_route_binding_hash_size_and_relay(self):
        for field, value in (("size", len(self.content) + 1), ("sha256", "0" * 64),
                             ("binding_sha256", "0" * 64)):
            with self.subTest(field=field):
                payload = copy.deepcopy(self.payload)
                payload["blob_offer"]["private"][field] = value
                with self.assertRaisesRegex(BlobError, "binding_mismatch"):
                    validate_input_offer(payload, self.route, self.source, "https://blob.test")
        with self.assertRaisesRegex(BlobError, "manifest"):
            validate_input_offer(self.payload, new_route_id(), self.source, "https://blob.test")
        with self.assertRaisesRegex(BlobError, "relay_mismatch"):
            validate_input_offer(self.payload, self.route, self.source, "https://other.test")

    def test_offer_bounded_and_never_uses_sender_url_as_authority(self):
        with self.assertRaisesRegex(BlobError, "not_configured"):
            validate_input_offer(self.payload, self.route, self.source, "")
        with self.assertRaisesRegex(BlobError, "too_large"):
            validate_input_offer({**self.payload, "junk": "x" * 32768}, self.route, self.source, "https://blob.test")
        with self.assertRaisesRegex(BlobError, "invalid_private_descriptor"):
            validate_input_offer({**self.payload, "blob_offer": {**self.offer, "private": {}}},
                                 self.route, self.source, "https://blob.test")

    def test_transport_retry_metadata_does_not_change_durable_identity(self):
        second = validate_input_offer({**self.payload, "time": 99, "message_id": "new-envelope"},
                                      self.route, self.source, "https://blob.test")
        self.assertEqual(self.body, second)


class BlobInputJournalTest(InputJournalFixture):
    def test_private_metadata_and_capabilities_are_encrypted(self):
        self.journal.enqueue(self.body)
        for path in self.journal.path.parent.glob("jobs.sqlite3*"):
            raw = path.read_bytes()
            for private in (self.route, self.source, self.offer["read_token"], self.offer["private"]["key"],
                            self.manifest["name"], self.manifest["task_id"]):
                self.assertNotIn(private.encode(), raw)
        self.assertEqual(self.body, self.journal.claim_due(1)[0]["body"])

    def test_duplicate_cannot_replace_offer_or_live_claim(self):
        first = self.journal.enqueue(self.body)
        job = self.journal.claim_due(1)[0]
        self.assertEqual(first, self.journal.enqueue(self.body))
        self.assertEqual([], self.journal.claim_due(1))
        changed = copy.deepcopy(self.body)
        changed["offer"]["read_token"] = "a" * 64
        with self.assertRaisesRegex(BlobError, "conflict"):
            self.journal.enqueue(changed)
        self.journal.receipt_ready(job, {"status": "stored"})
        self.assertTrue(self.journal.finish(job))

    def test_process_reopen_preserves_phase_and_rejects_stale_claim(self):
        self.journal.enqueue(self.body)
        old = self.journal.claim_due(1)[0]
        self.journal.receipt_ready(old, {"status": "stored"})
        reopened = BlobInputJournal(self.journal.path)
        reopened.recover()
        new = reopened.claim_due(1)[0]
        self.assertEqual("receipt", new["phase"])
        self.assertNotIn("offer", new["body"])
        self.assertFalse(reopened.finish(old))
        reopened.retry(old, "late-worker")
        self.assertEqual({"running": 1}, reopened.snapshot())
        self.assertTrue(reopened.finish(new))
        reopened.enqueue(self.body)
        self.assertEqual("receipt", reopened.claim_due(1)[0]["phase"])

    def test_pending_query_is_bounded_and_indexed(self):
        for index in range(100):
            body = copy.deepcopy(self.body)
            body["manifest"]["transfer_id"] = hashlib.sha256(str(index).encode()).hexdigest()
            self.journal.enqueue(body, now=index)
        self.assertEqual(4, len(self.journal.claim_due(4, now=101)))
        self.assertEqual({"pending": 96, "running": 4}, self.journal.snapshot())
        with closing(sqlite3.connect(self.journal.path)) as db:
            plan = str(db.execute("EXPLAIN QUERY PLAN SELECT * FROM jobs WHERE state='pending' AND due<=100 ORDER BY due,id LIMIT 4").fetchall())
        self.assertIn("jobs_due", plan)

    def test_authenticated_rows_cannot_be_swapped(self):
        first = self.journal.enqueue(self.body, now=0)
        other = copy.deepcopy(self.body)
        other["manifest"]["transfer_id"] = "f" * 64
        second = self.journal.enqueue(other, now=0)
        with closing(sqlite3.connect(self.journal.path)) as db, db:
            one = db.execute("SELECT body FROM jobs WHERE id=?", (first,)).fetchone()[0]
            db.execute("UPDATE jobs SET body=? WHERE id=?", (one, second))
        self.assertEqual([first], [job["id"] for job in self.journal.claim_due(2, now=1)])

    def test_retry_backoff_does_not_terminalize_valid_long_transfer(self):
        self.journal.enqueue(self.body, now=0)
        for index in range(100):
            job = self.journal.claim_due(1, now=1000000 + index * 301)[0]
            self.journal.retry(job, "relay_timeout", now=1000000 + index * 301)
        self.assertEqual({"pending": 1}, self.journal.snapshot())
