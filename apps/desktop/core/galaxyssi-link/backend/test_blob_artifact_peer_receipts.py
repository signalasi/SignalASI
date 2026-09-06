"""Verified recipient persistence drives recoverable contact delivery projection."""
import os
import sqlite3
import time
import unittest
from unittest.mock import Mock, patch

from blob_artifact_contract import artifact_binding, stored_receipt
from blob_artifact_journal import BlobArtifactJournal
from blob_client import BlobClient
import blob_pair_configuration as settings
from blob_protocol import BlobError
from blob_relay import create_app
from blob_store import BlobStore
from test_blob_http import LocalRelay
import test_blob_artifact_peer as fixtures


class BlobArtifactPeerReceiptTests(unittest.TestCase):
    setUp = fixtures.BlobArtifactPeerTests.setUp
    stop = fixtures.BlobArtifactPeerTests.stop
    enable = fixtures.BlobArtifactPeerTests.enable
    send = fixtures.BlobArtifactPeerTests.send

    def activate(self, paths=None):
        result = self.send(paths)
        self.assertTrue(result["ok"], result)
        self.runtime.sender._register_batches()
        wire = self.bridge._publish_to_registered_client.call_args.args[2]
        batch = self.runtime.sender.journal.batches.publication_for(wire)
        self.assertEqual("done", batch["state"])
        return result, batch["bodies"]

    def receipt(self, body, **overrides):
        identity = dict(route=self.route, source=body["source_id"],
            peer_fingerprint=body["peer_fingerprint"], local_fingerprint=body["local_fingerprint"],
            conversation_id=body["manifest"]["conversation_id"])
        identity.update(overrides)
        return self.runtime.sender.accept_receipt(stored_receipt(body["manifest"]), **identity)

    def cleanup(self):
        jobs = self.runtime.sender.journal.claim_due(12, now=time.time() + 1000)
        for job in jobs:
            if job["phase"] == "cleanup":
                self.runtime.sender._process(job)
            else:
                self.runtime.sender.journal.defer(job, delay=60)

    def status(self, result):
        return self.store.get_message(result["message_id"])["delivery_status"]

    def test_card_publication_alone_never_means_recipient_has_file(self):
        result, bodies = self.activate()
        self.assertTrue(self.runtime.observe_stored(bodies[0]))
        self.assertEqual("queued", self.status(result))

    def test_verified_receipt_updates_card_before_source_cleanup(self):
        result, bodies = self.activate()
        self.assertTrue(self.receipt(bodies[0]))
        original = self.runtime.sender.commit_receipt
        def commit(body):
            self.assertEqual("delivered", self.status(result))
            return original(body)
        self.runtime.sender.commit_receipt = commit
        self.cleanup()
        self.assertEqual("delivered", self.status(result))
        self.assertEqual({"done": 1}, self.runtime.sender.journal.snapshot())
        self.assertTrue(self.store.attachment_record(result["message_id"], 0))

    def test_two_attachments_require_both_receipts_in_reverse_order(self):
        second = self.source.parent / "second.txt"
        second.write_bytes(b"another document")
        result, bodies = self.activate([str(self.source), str(second)])
        self.assertTrue(self.receipt(bodies[1]))
        self.cleanup()
        self.assertEqual("queued", self.status(result))
        self.assertTrue(self.receipt(bodies[1]))
        self.assertEqual("queued", self.status(result))
        self.assertTrue(self.receipt(bodies[0]))
        self.cleanup()
        self.assertEqual("delivered", self.status(result))
        self.assertEqual({"done": 2}, self.runtime.sender.journal.snapshot())

    def test_wrong_route_fingerprint_or_conversation_cannot_complete_message(self):
        result, bodies = self.activate()
        for change in ({"route": "b" * 22}, {"peer_fingerprint": "1" * 64},
                       {"local_fingerprint": "2" * 64}, {"conversation_id": "other"}, {"source": "other"}):
            self.assertFalse(self.receipt(bodies[0], **change))
        self.runtime.observe_stored(bodies[0])
        self.assertEqual("queued", self.status(result))

    def test_receipt_committed_before_process_death_is_projected_after_restart(self):
        result, bodies = self.activate()
        self.receipt(bodies[0])
        self.runtime.sender.journal = BlobArtifactJournal(self.runtime.sender.journal.path)
        self.runtime.sender.journal.recover()
        self.cleanup()
        self.assertEqual("delivered", self.status(result))

    def test_database_contention_retries_projection_before_releasing_source(self):
        result, bodies = self.activate()
        self.receipt(bodies[0])
        original = self.runtime.sender.commit_receipt
        self.runtime.sender.commit_receipt = Mock(wraps=original)
        with patch.object(self.store, "update_delivery_status", side_effect=sqlite3.OperationalError("busy")):
            self.cleanup()
        self.runtime.sender.commit_receipt.assert_not_called()
        self.assertEqual({"pending": 1}, self.runtime.sender.journal.snapshot())
        self.cleanup()
        self.assertEqual("delivered", self.status(result))
        self.assertEqual({"done": 1}, self.runtime.sender.journal.snapshot())

    def test_projection_replay_does_not_repeat_ui_notifications(self):
        result, bodies = self.activate()
        self.receipt(bodies[0])
        listener = Mock()
        self.store.subscribe(listener)
        self.runtime.observe_stored(bodies[0])
        self.runtime.observe_stored(bodies[0])
        self.assertEqual(1, listener.call_count)
        self.assertEqual("delivered", self.status(result))

    def test_crash_after_projection_before_cleanup_recovers_without_duplicate_notification(self):
        result, bodies = self.activate()
        self.receipt(bodies[0])
        job = self.runtime.sender.journal.claim_due(1)[0]
        self.runtime.observe_stored(job["body"])
        listener = Mock()
        self.store.subscribe(listener)
        self.runtime.sender.journal = BlobArtifactJournal(self.runtime.sender.journal.path)
        self.runtime.sender.journal.recover()
        self.cleanup()
        listener.assert_not_called()
        self.assertEqual("delivered", self.status(result))
        self.assertEqual({"done": 1}, self.runtime.sender.journal.snapshot())

    def test_another_message_receipt_never_completes_this_message(self):
        first, first_bodies = self.activate()
        second, second_bodies = self.activate()
        self.receipt(first_bodies[0])
        self.cleanup()
        self.assertEqual("delivered", self.status(first))
        self.assertEqual("queued", self.status(second))
        self.receipt(second_bodies[0])
        self.cleanup()
        self.assertEqual("delivered", self.status(second))

    def test_cleanup_storage_failure_does_not_undo_confirmed_delivery(self):
        result, bodies = self.activate()
        self.receipt(bodies[0])
        original = self.runtime.sender.commit_receipt
        self.runtime.sender.commit_receipt = Mock(side_effect=OSError("source cleanup unavailable"))
        self.cleanup()
        self.assertEqual("delivered", self.status(result))
        self.assertEqual({"pending": 1}, self.runtime.sender.journal.snapshot())
        self.runtime.sender.commit_receipt = original
        self.cleanup()
        self.assertEqual({"done": 1}, self.runtime.sender.journal.snapshot())

    def test_read_status_is_not_downgraded_by_late_stored_receipt(self):
        result, bodies = self.activate()
        self.store.update_delivery_status(result["message_id"], "read")
        self.receipt(bodies[0])
        self.cleanup()
        self.assertEqual("read", self.status(result))

    def test_authoritative_receipt_recovers_previous_transfer_failure(self):
        result, bodies = self.activate()
        self.store.update_delivery_status(result["message_id"], "failed")
        self.receipt(bodies[0])
        self.cleanup()
        self.assertEqual("delivered", self.status(result))

    def test_deleted_message_is_not_recreated_when_receipt_arrives(self):
        result, bodies = self.activate()
        self.store.delete_route(self.route)
        self.receipt(bodies[0])
        self.cleanup()
        self.assertIsNone(self.store.get_message(result["message_id"]))
        self.assertEqual({"done": 1}, self.runtime.sender.journal.snapshot())

    def test_damaged_batch_cannot_mark_a_card_delivered(self):
        result, bodies = self.activate()
        self.receipt(bodies[0])
        with self.runtime.sender.journal._db() as db:
            db.execute("UPDATE artifact_batches SET digest=?", ("0" * 64,))
        with self.assertRaises(BlobError):
            self.runtime.observe_stored(bodies[0])
        self.assertEqual("queued", self.status(result))

    def test_real_https_contact_bytes_and_receipt_complete_the_existing_card(self):
        relay_store = BlobStore(self.bridge.DATA_DIR / "relay.sqlite3")
        relay = LocalRelay(create_app(relay_store, "c" * 64), self.bridge.DATA_DIR, tls=True)
        self.addCleanup(relay.close)
        old = settings.public_settings(self.bridge, self.route)
        settings.update_settings(self.bridge, self.route, identity_fingerprint=self.peer["identity_fingerprint"],
            identity_binding=old["identity_binding"], expected_revision=old["revision"], enabled=True,
            origin=relay.origin, provisioning_token="c" * 64)
        self.runtime.sender.client_factory = lambda origin, **kw: BlobClient(origin, **kw,
            tls_context=relay.context, trust_env=False, timeout=2)
        self.source.write_bytes(b"contact attachment bytes" * 50000)
        raw = self.source.read_bytes()
        result, bodies = self.activate()
        upload = self.runtime.sender.journal.claim_due(1)[0]
        self.runtime.sender._process(upload)
        offer = self.bridge._publish_to_registered_client.call_args.args[2]
        self.assertEqual("artifact_blob_offer", offer["type"])
        self.assertEqual("queued", self.status(result))
        binding = artifact_binding(bodies[0]["manifest"])
        with self.runtime.sender.client_factory(relay.origin) as client:
            downloaded = client.download(offer["blob_offer"], self.bridge.DATA_DIR / "receiver", binding)
        received_path = self.bridge.DATA_DIR / "received.bin"
        with received_path.open("wb") as output:
            for block in downloaded.plaintext(binding):
                output.write(block)
            output.flush()
            os.fsync(output.fileno())
        self.assertEqual(raw, received_path.read_bytes())
        self.assertTrue(self.receipt(bodies[0]))
        self.cleanup()
        self.assertEqual("delivered", self.status(result))
        self.assertEqual(1, len(self.store.list_messages(self.route)))
        self.assertEqual({"done": 1}, self.runtime.sender.journal.snapshot())


if __name__ == "__main__":
    unittest.main()
