"""Real workspace preparation, scoped cards and restartable reply/byte handoff."""
from dataclasses import replace
import base64
import json
import threading
import time
from types import SimpleNamespace
import unittest
from unittest.mock import Mock, patch

import agent_task_result_archive
import artifact_delivery as delivery
import blob_artifact_bridge as adapter
from blob_artifact_journal import BlobArtifactJournal
import blob_artifact_publication as publication
import blob_pair_configuration as settings
from blob_protocol import BlobError, sha256
import test_artifact_delivery_ownership as fixture


class BlobArtifactPublicationTests(unittest.TestCase):
    def setUp(self):
        fixture.ArtifactOwnershipTests.setUp(self)
        self.route = "a" * 22
        self.peer = {"client_route_id": self.route, "signal_name": "phone-signal",
                     "identity_fingerprint": "5" * 64, "local_identity_fingerprint": "6" * 64}
        self.bridge = SimpleNamespace(DATA_DIR=delivery.workspace_root() / "runtime", desktop_id=lambda: "desktop",
            get_client=lambda route: self.peer if route == self.route else None, client=None,
            phone_publish_lock=threading.RLock(), outbound_status=Mock(return_value=None),
            _publish_to_registered_client=Mock(), _publish_task_artifacts=Mock(), _publish_or_queue_task_result=Mock())
        self.payload = {"type": "text", "task_status": "completed", "task_id": self.artifact.task_id,
            "client_route_id": self.route, "conversation_id": "conversation", "turn_id": "turn",
            "execution_generation": 1, "source_message_id": "input-message", "desktop_id": "desktop",
            "contact_id": "codex", "agent_id": "codex", "desktop_name": "Desktop",
            "content": f"[report]({self.artifact.artifact_uri})", "rich_output": {"blocks": [
                {"id": "file", "type": "file", "uri": self.artifact.artifact_uri, "metadata": {
                    "transport": "encrypted-fragmented", "artifact_source_uri": self.artifact.artifact_uri}}]}}
        self.archive = agent_task_result_archive.TaskResultArchive(self.bridge.DATA_DIR / "archive.db")
        archive_patch = patch.object(agent_task_result_archive, "archive", self.archive)
        archive_patch.start()
        self.addCleanup(archive_patch.stop)
        self.addCleanup(self.stop)

    def stop(self):
        adapter.stop(self.bridge)
        self.assertTrue(adapter.wait_stopped(self.bridge, 5))
        root = adapter._root(self.bridge)
        with adapter._lock:
            adapter._runtimes.pop(root, None)
            adapter._creation_locks.pop(root, None)
            adapter._starters.pop(root, None)

    def enable(self):
        previous = settings.public_settings(self.bridge, self.route)
        settings.update_settings(self.bridge, self.route, identity_fingerprint=self.peer["identity_fingerprint"],
            identity_binding=previous["identity_binding"], expected_revision=previous["revision"],
            enabled=True, origin="https://relay.test", provisioning_token="c" * 64)
        settings.record_artifact_capability(self.bridge, self.route, self.peer["signal_name"], {
            "type": "artifact_blob_capability", "version": 1, "revision": 1, "enabled": True,
            "desktop_id": "desktop", "desktop_fingerprint": self.peer["local_identity_fingerprint"],
            "client_route_id": self.route})
        runtime = adapter._get(self.bridge)
        runtime.sender.start = Mock(return_value=True)
        return runtime

    def prepare(self, artifacts=None, payload=None):
        return publication.prepare_result(self.bridge, self.peer, artifacts or [self.artifact],
                                          payload or self.payload, "https://relay.test")

    def test_prepare_rewrites_card_and_markdown_without_mutating_original_or_registering_lease(self):
        before = json.dumps(self.payload)
        bodies, reply = self.prepare()
        manifest = bodies[0]["manifest"]
        self.assertEqual(before, json.dumps(self.payload))
        self.assertFalse(delivery._ledger_path().exists())
        self.assertEqual(f"[report]({manifest['artifact_uri']})", reply["content"])
        block = reply["rich_output"]["blocks"][0]
        self.assertEqual(manifest["artifact_uri"], block["uri"])
        self.assertEqual(manifest["artifact_uri"], block["metadata"]["artifact_source_uri"])
        self.assertEqual("encrypted-blob", block["metadata"]["transport"])
        self.assertEqual(str(self.artifact.size_bytes), block["metadata"]["size_bytes"])
        self.assertEqual(self.artifact.sha256, block["metadata"]["sha256"])
        for key in ("client_route_id", "desktop_id", "conversation_id", "task_id", "turn_id", "execution_generation"):
            self.assertEqual(str(manifest[key]), block["metadata"][f"blob_{key}"])
        self.assertEqual(manifest["transfer_id"], block["metadata"]["transfer_id"])

    def test_new_turn_and_generation_get_distinct_uris_while_retry_is_stable(self):
        first, _ = self.prepare()
        repeat, _ = self.prepare()
        self.assertEqual(first, repeat)
        for change in ({"turn_id": "new-turn"}, {"execution_generation": 2}, {"conversation_id": "new-conversation"}):
            bodies, _ = self.prepare(payload={**self.payload, **change})
            self.assertNotEqual(first[0]["manifest"]["artifact_uri"], bodies[0]["manifest"]["artifact_uri"])

    def test_gallery_rows_retain_per_item_scoped_progress_metadata(self):
        payload = {**self.payload, "rich_output": {"blocks": [{"id": "gallery", "type": "gallery", "rows": [
            [self.artifact.artifact_uri, "Report", "image/png"], ["https://example.test/image.png", "Other"]]}]}}
        bodies, reply = self.prepare(payload=payload)
        manifest = bodies[0]["manifest"]
        gallery = reply["rich_output"]["blocks"][0]
        key = "blob_item_" + manifest["artifact_uri"].rsplit("/", 1)[1]
        self.assertEqual([key], list(gallery["metadata"]))
        item = json.loads(gallery["metadata"][key])
        self.assertEqual(manifest["transfer_id"], item["transfer_id"])
        self.assertEqual("conversation", item["blob_conversation_id"])
        self.assertLessEqual(len(gallery["metadata"][key]), 2000)
        self.assertNotIn("provisioning_token", json.dumps(item))

    def test_protocol_metadata_precedes_model_metadata_in_bounded_card_codec(self):
        payload = json.loads(json.dumps(self.payload))
        payload["rich_output"]["blocks"][0]["metadata"] = {f"custom-{index}": "text" for index in range(32)}
        _, reply = self.prepare(payload=payload)
        metadata = dict(list(reply["rich_output"]["blocks"][0]["metadata"].items())[:32])
        self.assertEqual("conversation", metadata["blob_conversation_id"])
        self.assertEqual("encrypted-blob", metadata["transport"])
        self.assertIn("transfer_id", metadata)

    def test_ten_gallery_items_survive_canonical_order_and_existing_metadata_limits(self):
        from rich_output import _normalize_block
        uris = ["galaxyssi-artifact://blob/" + f"{index:064x}" for index in range(10)]
        metadata = {f"custom-{index}": "text" for index in range(32)}
        for uri in uris:
            metadata["blob_item_" + uri.rsplit("/", 1)[1]] = json.dumps({"artifact_source_uri": uri, "detail": "x" * 1200})
        raw = json.loads(json.dumps({"type": "gallery", "metadata": metadata, "rows": [[uri] for uri in uris]}, sort_keys=True))
        normalized = _normalize_block(raw)
        self.assertLessEqual(len(normalized["metadata"]), 32)
        for uri in uris:
            item = json.loads(normalized["metadata"]["blob_item_" + uri.rsplit("/", 1)[1]])
            self.assertEqual(uri, item["artifact_source_uri"])

    def test_compressed_image_metadata_describes_actual_transported_bytes(self):
        raw = b"compressed"
        artifact = replace(self.artifact, transport_bytes=raw, size_bytes=len(raw), sha256=sha256(raw))
        bodies, reply = self.prepare([artifact])
        metadata = reply["rich_output"]["blocks"][0]["metadata"]
        self.assertEqual(str(len(raw)), metadata["size_bytes"])
        self.assertEqual(sha256(raw), metadata["sha256"])
        self.assertEqual(str(self.artifact.original_size_bytes), metadata["original_size_bytes"])
        self.assertEqual(b"shared source", self.source.read_bytes())
        self.assertEqual(raw, (delivery.workspace_root() / bodies[0]["source_relative"]).read_bytes())

    def test_scope_mismatch_and_non_integer_generation_never_register_sources(self):
        for changes in ({"task_id": "wrong"}, {"desktop_id": "wrong"}, {"client_route_id": "b" * 22},
                        {"execution_generation": True}, {"turn_id": ""}, {"type": "peer_message"}):
            with self.subTest(changes=changes), self.assertRaises(BlobError):
                self.prepare(payload={**self.payload, **changes})
        self.assertFalse(delivery._ledger_path().exists())

    def test_unnegotiated_receiver_uses_existing_delivery_without_creating_blob_store(self):
        publication.publish_result(self.bridge, None, {"_client_route_id": self.route}, [self.artifact], self.payload,
                                   retain_on_desktop=False)
        self.bridge._publish_task_artifacts.assert_called_once()
        self.bridge._publish_or_queue_task_result.assert_called_once()
        self.assertFalse(adapter._root(self.bridge).exists())
        self.assertEqual(1, len(delivery.pending_artifacts_for_redelivery()))

    def test_negotiated_path_persists_complete_reply_and_held_job_without_legacy_chunks(self):
        runtime = self.enable()
        publication.publish_result(self.bridge, None, {"_client_route_id": self.route}, [self.artifact], self.payload,
                                   retain_on_desktop=False)
        self.assertEqual({"held": 1}, runtime.sender.journal.snapshot())
        self.assertFalse(delivery._ledger_path().exists())
        self.bridge._publish_task_artifacts.assert_not_called()
        self.bridge._publish_or_queue_task_result.assert_not_called()
        restarted = BlobArtifactJournal(runtime.sender.journal.path)
        batch = restarted.batches.claim_due()[0]
        self.assertEqual("conversation", batch["publication"]["conversation_id"])
        self.assertIn("galaxyssi-artifact://blob/", batch["publication"]["content"])

    def test_reply_queue_failure_keeps_jobs_held_and_recovers_with_same_card_identity(self):
        runtime = self.enable()
        publication.enqueue_result(self.bridge, {"_client_route_id": self.route}, [self.artifact], self.payload,
                                   retain_on_desktop=False)
        self.bridge._publish_to_registered_client.side_effect = OSError("queue disk failure")
        runtime.sender._register_batches()
        first = self.bridge._publish_to_registered_client.call_args.args[2]
        self.assertEqual({"held": 1}, runtime.sender.journal.snapshot())
        self.assertTrue(delivery._ledger_path().exists())
        self.bridge._publish_to_registered_client.side_effect = None
        with patch("blob_artifact_batches.time.time", return_value=time.time() + 120):
            runtime.sender._register_batches()
        second = self.bridge._publish_to_registered_client.call_args.args[2]
        self.assertEqual(first, second)
        self.assertEqual({"pending": 1}, runtime.sender.journal.snapshot())
        self.assertEqual([], delivery.pending_artifacts_for_redelivery())

    def test_final_card_is_archived_with_scoped_uri_before_publication(self):
        runtime = self.enable()
        bodies, reply = self.prepare()
        runtime.sender.enqueue_batch(bodies, publication=reply)
        def published(*args, **kwargs):
            payload = args[2]
            page = self.archive.page({**payload, "request_id": "read", "page_index": 0}, client_route_id=self.route)
            self.assertEqual("ready", page["status"])
            archived = json.loads(base64.b64decode(page["data_b64"]))
            self.assertEqual(payload["message_id"], archived["message_id"])
            self.assertEqual(reply["rich_output"], archived["rich_output"])
            self.assertEqual({key: value for key, value in payload.items() if key != "result_recovery"}, archived)
            self.assertEqual({"held": 1}, runtime.sender.journal.snapshot())
            self.assertTrue(delivery._ledger_path().exists())
            self.assertIn("result_recovery", payload)
        self.bridge._publish_to_registered_client.side_effect = published
        runtime.sender._register_batches()
        self.assertEqual({"pending": 1}, runtime.sender.journal.snapshot())

    def test_existing_archive_with_different_reply_cannot_get_mismatched_recovery_receipt(self):
        runtime = self.enable()
        bodies, reply = self.prepare()
        self.archive.put({**reply, "content": "previously published canonical result"})
        runtime.sender.enqueue_batch(bodies, publication=reply)
        runtime.sender._register_batches()
        self.bridge._publish_to_registered_client.assert_not_called()
        job = runtime.sender.journal.claim_due(1)[0]
        self.assertEqual("failure", job["phase"])
        self.assertEqual("artifact_blob_publication_conflict", job["failure"])
        self.assertTrue(self.source.exists())

    def test_batch_digest_rejects_reply_replacement_and_cross_session_injection(self):
        runtime = self.enable()
        bodies, reply = self.prepare()
        batch_id = runtime.sender.enqueue_batch(bodies, publication=reply)
        self.assertEqual(batch_id, runtime.sender.enqueue_batch(bodies, publication=reply))
        for changed in ({**reply, "content": "replacement"}, {**reply, "conversation_id": "other"}):
            with self.assertRaises(BlobError):
                runtime.sender.enqueue_batch(bodies, publication=changed)
        batch = runtime.sender.journal.batches.claim_due()[0]
        self.assertEqual(reply, batch["publication"])

    def test_failed_activation_after_card_queued_can_replay_without_rewriting_card(self):
        runtime = self.enable()
        bodies, reply = self.prepare()
        runtime.sender.enqueue_batch(bodies, publication=reply)
        with patch.object(runtime.sender.journal.batches, "finish", side_effect=OSError("checkpoint failed")):
            runtime.sender._register_batches()
        first = self.bridge._publish_to_registered_client.call_args.args[2]
        self.assertEqual({"held": 1}, runtime.sender.journal.snapshot())
        with patch("blob_artifact_batches.time.time", return_value=time.time() + 120):
            runtime.sender._register_batches()
        self.assertEqual(first, self.bridge._publish_to_registered_client.call_args.args[2])
        self.assertEqual({"pending": 1}, runtime.sender.journal.snapshot())
