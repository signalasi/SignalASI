"""Contact cards and Blob intents share an identity across crashes and retries."""
import json
import sqlite3
import time
import unittest
from unittest.mock import Mock, patch

import blob_artifact_peer as peer
import blob_artifact_publication as publication
from blob_artifact_journal import BlobArtifactJournal
from blob_protocol import BlobError
import mqtt_bridge
import peer_chat_store
import test_blob_artifact_publication as fixtures


class BlobArtifactPeerTests(unittest.TestCase):
    stop = fixtures.BlobArtifactPublicationTests.stop
    enable = fixtures.BlobArtifactPublicationTests.enable

    def setUp(self):
        fixtures.BlobArtifactPublicationTests.setUp(self)
        self.store = peer_chat_store.PeerChatStore(self.bridge.DATA_DIR / "peer.db")
        self.runtime = self.enable()
        self.bridge.api_ok = mqtt_bridge.api_ok
        self.bridge.api_error = mqtt_bridge.api_error
        replacements = {"DATA_DIR": self.bridge.DATA_DIR, "desktop_id": self.bridge.desktop_id,
            "desktop_name": lambda: "Desktop", "get_client": self.bridge.get_client,
            "client": Mock(is_connected=Mock(return_value=True)), "phone_publish_lock": self.bridge.phone_publish_lock,
            "outbound_status": self.bridge.outbound_status,
            "_publish_to_registered_client": self.bridge._publish_to_registered_client,
            "_publish_task_artifacts": self.bridge._publish_task_artifacts,
            "_publish_phone_payload": Mock(return_value=True)}
        for name, value in replacements.items():
            p = patch.object(mqtt_bridge, name, value)
            p.start()
            self.addCleanup(p.stop)
        p = patch.object(peer_chat_store, "peer_chat_store", return_value=self.store)
        p.start()
        self.addCleanup(p.stop)

    def send(self, paths=None, metadata=None):
        return mqtt_bridge.publish_peer_message(self.route, "contact file", paths or [str(self.source)], metadata)

    def queued_batch(self):
        return self.runtime.sender.journal.batches.claim_due()[0]

    def test_real_entry_enqueues_one_card_before_any_blob_upload(self):
        result = self.send()
        self.assertTrue(result["ok"], result)
        message_id = result["message_id"]
        self.assertEqual("queued", self.store.get_message(message_id)["delivery_status"])
        self.assertEqual({"held": 1}, self.runtime.sender.journal.snapshot())
        self.bridge._publish_task_artifacts.assert_not_called()
        mqtt_bridge._publish_phone_payload.assert_not_called()
        self.runtime.sender._register_batches()
        wire = self.bridge._publish_to_registered_client.call_args.args[2]
        self.assertEqual(message_id, wire["message_id"])
        self.assertEqual(message_id, wire["source_message_id"])
        self.assertEqual(f"peer:{self.route}", wire["conversation_id"])
        self.assertTrue(wire["peer_chat"])
        self.assertNotIn("_local_message", wire)
        self.assertNotIn("local_path", json.dumps(wire))
        self.assertNotIn("result_recovery", wire)
        self.assertEqual({"pending": 1}, self.runtime.sender.journal.snapshot())

    def test_large_contact_image_keeps_original_bytes_and_hash(self):
        source = self.source.with_suffix(".jpg")
        data = b"\xff\xd8\xff" + b"image-content" * 20000
        source.write_bytes(data)
        result = self.send([str(source)])
        self.assertTrue(result["ok"], result)
        batch = self.queued_batch()
        manifest = batch["bodies"][0]["manifest"]
        self.assertEqual(len(data), manifest["size_bytes"])
        self.assertEqual(manifest["original_sha256"], manifest["sha256"])
        self.assertEqual("image/jpeg", manifest["mime_type"])
        self.assertEqual(data, source.read_bytes())

    def test_opus_voice_retains_audio_type_and_duration_without_asr(self):
        source = self.source.with_suffix(".ogg")
        source.write_bytes(b"OggS" + b"OpusHead" * 40)
        result = self.send([str(source)], [{"mime_type": "audio/ogg", "duration_ms": 3456}])
        self.assertTrue(result["ok"], result)
        self.runtime.sender._register_batches()
        wire = self.bridge._publish_to_registered_client.call_args.args[2]
        self.assertEqual(3456, wire["duration_ms"])
        self.assertEqual(3456, wire["attachments"][0]["duration_ms"])
        self.assertEqual("audio/ogg", wire["attachments"][0]["mime_type"])
        self.assertEqual("contact file", wire["content"])

    def test_plain_text_keeps_existing_small_message_path(self):
        result = mqtt_bridge.publish_peer_message(self.route, "hello")
        self.assertTrue(result["ok"], result)
        mqtt_bridge._publish_phone_payload.assert_called_once()
        self.assertEqual({}, self.runtime.sender.journal.snapshot())

    def test_local_database_failure_leaves_durable_intent_then_recovers(self):
        with patch.object(self.store, "append", side_effect=sqlite3.OperationalError("busy")):
            result = self.send()
        self.assertTrue(result["ok"], result)
        self.assertIsNone(self.store.get_message(result["message_id"]))
        self.assertNotIn("local_path", json.dumps(result))
        restarted = BlobArtifactJournal(self.runtime.sender.journal.path)
        batch = restarted.batches.claim_due()[0]
        self.assertTrue(self.runtime.publish_batch(batch))
        self.assertTrue(self.runtime.publish_batch(batch))
        self.assertEqual(1, len(self.store.list_messages(self.route)))
        self.assertEqual(result["message_id"], self.store.list_messages(self.route)[0]["message_id"])

    def test_queue_failure_retries_same_id_without_duplicate_local_message(self):
        result = self.send()
        self.bridge._publish_to_registered_client.side_effect = OSError("disk busy")
        self.runtime.sender._register_batches()
        first = self.bridge._publish_to_registered_client.call_args.args[2]
        self.assertEqual({"held": 1}, self.runtime.sender.journal.snapshot())
        self.bridge._publish_to_registered_client.side_effect = None
        with patch("blob_artifact_batches.time.time", return_value=time.time() + 120):
            self.runtime.sender._register_batches()
        second = self.bridge._publish_to_registered_client.call_args.args[2]
        self.assertEqual(first, second)
        self.assertEqual(result["message_id"], second["message_id"])
        self.assertEqual(1, len(self.store.list_messages(self.route)))

    def test_fast_receipt_cannot_be_overwritten_by_queued_projection(self):
        result = self.send()
        self.bridge._publish_to_registered_client.side_effect = lambda *a, **kw: self.store.update_delivery_status(
            result["message_id"], "delivered")
        self.runtime.sender._register_batches()
        self.assertEqual("delivered", self.store.get_message(result["message_id"])["delivery_status"])

    def test_acknowledged_card_recovery_does_not_republish_or_notify(self):
        result = self.send()
        batch = self.queued_batch()
        self.store.update_delivery_status(result["message_id"], "read")
        listener = Mock()
        self.store.subscribe(listener)
        self.runtime.publish_batch(batch)
        listener.assert_not_called()
        self.bridge._publish_to_registered_client.assert_not_called()

    def test_deleted_conversation_is_not_resurrected_on_recovery(self):
        result = self.send()
        batch = self.queued_batch()
        self.store.delete_route(self.route)
        restored = peer_chat_store.PeerChatStore(self.store.database_path)
        self.assertTrue(restored.message_was_deleted(result["message_id"]))
        with self.assertRaisesRegex(BlobError, "artifact_blob_peer_message_deleted"):
            self.runtime.publish_batch(batch)
        self.assertEqual([], self.store.list_messages(self.route))
        self.bridge._publish_to_registered_client.assert_not_called()

    def test_conflicting_local_message_does_not_get_overwritten(self):
        result = self.send()
        batch = self.queued_batch()
        batch["publication"]["content"] = "conflicting replay"
        with self.assertRaisesRegex(BlobError, "artifact_blob_peer_message_conflict"):
            self.runtime.publish_batch(batch)
        self.assertEqual("contact file", self.store.get_message(result["message_id"])["content"])

    def test_local_path_outside_message_directory_cannot_publish(self):
        self.send()
        batch = self.queued_batch()
        batch["publication"]["_local_message"]["attachments"][0]["local_path"] = str(self.source)
        with self.assertRaisesRegex(BlobError, "artifact_blob_peer_source_invalid"):
            self.runtime.publish_batch(batch)
        self.bridge._publish_to_registered_client.assert_not_called()

    def test_same_name_files_keep_order_and_distinct_artifacts(self):
        other = self.source.parent / "another.txt"
        other.write_bytes(b"second file")
        result = self.send([str(self.source), str(other)], [{"name": "same.txt"}, {"name": "same.txt"}])
        self.assertTrue(result["ok"], result)
        batch = self.queued_batch()
        items = batch["publication"]["attachments"]
        self.assertEqual(["same.txt", "same-1.txt"], [item["name"] for item in items])
        self.assertNotEqual(items[0]["artifact_id"], items[1]["artifact_id"])
        self.assertTrue(self.runtime.publish_batch(batch))

    def test_wire_local_path_injection_and_duration_mismatch_are_rejected(self):
        self.send()
        batch = self.queued_batch()
        for mutate in (lambda item: item.update(local_path="private"), lambda item: item.update(duration_ms=123)):
            value = json.loads(json.dumps(batch["publication"]))
            mutate(value["attachments"][0])
            with self.assertRaises(BlobError):
                publication.validate_publication(value, batch["bodies"])

    def test_unpublished_failure_marks_local_card_without_orphan_remote_error(self):
        result = self.send()
        batch = self.queued_batch()
        self.runtime.sender.journal.batches.fail(batch, "artifact_source_missing")
        self.assertTrue(self.runtime.observe_failure(batch["bodies"][0], "artifact_source_missing"))
        self.assertEqual("failed", self.store.get_message(result["message_id"])["delivery_status"])
        self.bridge._publish_to_registered_client.assert_not_called()
        self.assertEqual(1, len(list((self.runtime.root / "incidents").glob("*.secure.json"))))

    def test_published_failure_keeps_read_card_and_reports_scoped_attachment_error(self):
        result = self.send()
        batch = self.queued_batch()
        self.runtime.publish_batch(batch)
        self.runtime.sender.journal.batches.finish(batch)
        self.store.update_delivery_status(result["message_id"], "read")
        self.assertTrue(self.runtime.observe_failure(batch["bodies"][0], "source_changed"))
        self.assertEqual("read", self.store.get_message(result["message_id"])["delivery_status"])
        wire = self.bridge._publish_to_registered_client.call_args.args[2]
        self.assertEqual("artifact_download_failed", wire["type"])
        self.assertEqual("source_changed", wire["error_code"])
        self.assertEqual(result["message_id"], wire["source_message_id"])

    def test_deleted_card_failure_remains_local(self):
        self.send()
        batch = self.queued_batch()
        self.runtime.publish_batch(batch)
        self.runtime.sender.journal.batches.finish(batch)
        self.store.delete_route(self.route)
        self.bridge._publish_to_registered_client.reset_mock()
        self.assertTrue(self.runtime.observe_failure(batch["bodies"][0], "artifact_source_missing"))
        self.assertEqual([], self.store.list_messages(self.route))
        self.bridge._publish_to_registered_client.assert_not_called()

    def test_conditional_status_noop_does_not_notify_or_downgrade(self):
        result = self.send()
        self.store.update_delivery_status(result["message_id"], "delivered")
        listener = Mock()
        self.store.subscribe(listener)
        returned = self.store.update_delivery_status(result["message_id"], "failed", only_if=("queued",))
        self.assertEqual("delivered", returned["delivery_status"])
        listener.assert_not_called()


if __name__ == "__main__":
    unittest.main()
