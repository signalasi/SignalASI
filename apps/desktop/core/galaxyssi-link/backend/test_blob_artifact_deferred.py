"""Pre-publication failures remain visible and retryable without freezing final replies."""
import base64
import json
import sqlite3
import threading
import unittest
from unittest.mock import patch

import artifact_delivery as delivery
import blob_artifact_deferred as deferred
import blob_artifact_publication as publication
from blob_protocol import BlobError, MAX_FILE_BYTES
import test_blob_artifact_replay as fixtures


class BlobArtifactDeferredTests(unittest.TestCase):
    setUp = fixtures.BlobArtifactReplayTests.setUp
    stop = fixtures.BlobArtifactReplayTests.stop
    enable = fixtures.BlobArtifactReplayTests.enable
    prepare = fixtures.BlobArtifactReplayTests.prepare

    def fail(self, code="artifact_blob_transport_required", **kwargs):
        return deferred.defer(self.bridge, self.payload, self.task["output_files"], BlobError(code), **kwargs)

    def records(self):
        return list((self.bridge.DATA_DIR / "blob-output" / "deferred").glob("*.secure.json"))

    def test_failure_is_durable_scoped_localized_and_does_not_archive_final(self):
        notice = self.fail(language="zh-CN")
        self.assertIn("\u9644\u4ef6\u672a\u53d1\u9001", notice["content"])
        self.assertEqual("failed", notice["artifact_delivery"]["status"])
        self.assertEqual(self.route, notice["client_route_id"])
        self.assertEqual(self.payload["turn_id"], notice["turn_id"])
        self.assertNotIn("rich_output", notice)
        self.assertNotIn("result_recovery", notice)
        self.assertNotIn(self.payload["content"], self.records()[0].read_text())
        self.assertNotIn(str(self.source), json.dumps(notice))
        call = self.bridge._publish_to_registered_client.call_args
        self.assertTrue(call.kwargs["durable"])
        self.assertEqual("control", call.args[3])
        self.assertFalse(self.archive.path.exists())
        self.assertTrue(self.source.exists())
        self.bridge._publish_task_artifacts.assert_not_called()
        self.bridge._publish_or_queue_task_result.assert_not_called()

    def test_repeated_failure_preserves_first_intent_and_notice_id(self):
        first = self.fail()
        second = deferred.defer(self.bridge, {**self.payload, "content": "changed rerender"}, [],
                                 BlobError("artifact_blob_transport_required"))
        self.assertEqual(first["message_id"], second["message_id"])
        self.assertEqual(1, len(self.records()))
        runtime = self.enable()
        result = deferred.resume(self.bridge, self.task, self.route)
        self.assertTrue(result["ok"], result)
        batch = runtime.sender.journal.batches.claim_due()[0]
        self.assertNotIn("changed rerender", batch["publication"]["content"])
        self.assertEqual(1, len(batch["bodies"]))

    def test_restart_retry_queues_original_card_and_archive_accepts_success(self):
        self.fail()
        runtime = self.enable()
        # Recovery uses disk, not an in-memory closure or a reexecuted provider.
        result = deferred.resume(self.bridge, dict(self.task), self.route)
        self.assertTrue(result["ok"], result)
        self.assertEqual([], self.records())
        self.assertEqual({"held": 1}, runtime.sender.journal.snapshot())
        runtime.sender._register_batches()
        wire = self.bridge._publish_to_registered_client.call_args.args[2]
        self.assertIn("galaxyssi-artifact://blob/", wire["content"])
        self.assertNotIn("artifact_delivery", wire)
        page = self.archive.page({**wire, "request_id": "read", "page_index": 0}, client_route_id=self.route)
        self.assertEqual("ready", page["status"])
        archived = json.loads(base64.b64decode(page["data_b64"]))
        self.assertEqual(wire["rich_output"], archived["rich_output"])
        self.assertTrue(self.source.exists())
        self.bridge._publish_task_artifacts.assert_not_called()

    def test_configuration_still_missing_keeps_record_and_actual_large_source(self):
        with self.source.open("wb") as stream:
            stream.truncate(delivery.MAX_ARTIFACT_BYTES + 1)
        self.fail()
        result = deferred.resume(self.bridge, self.task, self.route)
        self.assertFalse(result["ok"])
        self.assertEqual("artifact_blob_transport_required", result["code"])
        self.assertEqual(1, len(self.records()))
        self.assertEqual(delivery.MAX_ARTIFACT_BYTES + 1, self.source.stat().st_size)
        self.bridge._publish_task_artifacts.assert_not_called()

    def test_queue_disk_failure_keeps_intent_without_legacy_fallback(self):
        self.fail()
        self.enable()
        with patch("blob_artifact_bridge.enqueue", side_effect=sqlite3.OperationalError("database full")):
            result = deferred.resume(self.bridge, self.task, self.route)
        self.assertEqual("artifact_blob_checkpoint_unavailable", result["code"])
        self.assertEqual(1, len(self.records()))
        self.assertTrue(self.source.exists())
        self.bridge._publish_task_artifacts.assert_not_called()

    def test_notice_queue_failure_does_not_lose_recovery_checkpoint(self):
        self.bridge._publish_to_registered_client.side_effect = OSError("queue unavailable")
        with self.assertRaises(OSError):
            self.fail()
        self.assertEqual(1, len(self.records()))
        self.assertTrue(self.source.exists())
        self.assertFalse(self.archive.path.exists())

    def test_identity_change_blocks_resume_and_repeat_notice(self):
        self.fail()
        self.bridge._publish_to_registered_client.reset_mock()
        self.peer["identity_fingerprint"] = "e" * 64
        self.assertEqual("artifact_blob_identity_changed", deferred.resume(self.bridge, self.task, self.route)["code"])
        with self.assertRaisesRegex(BlobError, "artifact_blob_identity_changed"):
            self.fail()
        self.bridge._publish_to_registered_client.assert_not_called()
        self.assertEqual(1, len(self.records()))

    def test_corrupted_record_blocks_silent_legacy_fallback(self):
        self.fail()
        self.records()[0].write_text("corrupt")
        self.assertEqual("artifact_blob_deferred_invalid", deferred.resume(self.bridge, self.task, self.route)["code"])
        self.bridge._publish_task_artifacts.assert_not_called()

    def test_other_turn_generation_conversation_never_reads_this_record(self):
        self.fail()
        for changes in ({"client_turn_id": "other"}, {"execution_generation": 2},
                        {"client_conversation_id": "other"}, {"source_message_id": "other"}):
            with self.subTest(changes=changes):
                self.assertIsNone(deferred.resume(self.bridge, {**self.task, **changes}, self.route))
        self.assertEqual(1, len(self.records()))

    def test_no_deferred_store_means_no_io_or_required_scope_for_old_chat(self):
        self.assertIsNone(deferred.resume(self.bridge, {}, self.route))
        self.assertFalse((self.bridge.DATA_DIR / "blob-output").exists())

    def test_publication_failure_is_caught_without_archiving_unusable_links(self):
        with patch.object(publication, "publish_result", side_effect=BlobError("artifact_blob_transport_required")):
            self.assertFalse(deferred.publish_or_defer(self.bridge, None, {"_client_route_id": self.route},
                [self.artifact], self.payload, self.task["output_files"]))
        self.assertEqual(1, len(self.records()))
        self.assertFalse(self.archive.path.exists())

    def test_preparation_failure_never_calls_publish_result(self):
        with patch.object(publication, "publish_result", side_effect=AssertionError("no prepared artifact")):
            self.assertFalse(deferred.publish_or_defer(self.bridge, None, {"_client_route_id": self.route},
                [], self.payload, self.task["output_files"], preparation_error=BlobError("artifact_source_empty")))
        self.assertEqual("artifact_source_empty", self.bridge._publish_to_registered_client.call_args.args[2][
            "artifact_delivery"]["error_code"])

    def test_wrong_wire_route_cannot_emit_failure_to_another_phone(self):
        with self.assertRaisesRegex(BlobError, "scope_mismatch"):
            deferred.publish_or_defer(self.bridge, None, {"_client_route_id": "other"}, [],
                self.payload, self.task["output_files"], preparation_error=BlobError("artifact_source_empty"))
        self.bridge._publish_to_registered_client.assert_not_called()
        self.assertEqual([], self.records())

    def test_strict_preparation_reports_empty_missing_and_compression_failure(self):
        self.source.write_bytes(b"")
        with self.assertRaisesRegex(BlobError, "artifact_source_empty"):
            publication.prepare_for_route(self.bridge, self.route, self.artifact.task_id, self.task["output_files"])
        self.source.unlink()
        with self.assertRaisesRegex(BlobError, "artifact_source_unavailable"):
            publication.prepare_for_route(self.bridge, self.route, self.artifact.task_id, self.task["output_files"])
        self.source.write_bytes(b"source")
        with patch.object(delivery, "_prepare_artifact", return_value=None):
            with self.assertRaisesRegex(BlobError, "artifact_preparation_failed"):
                publication.prepare_for_route(self.bridge, self.route, self.artifact.task_id, self.task["output_files"])

    def test_strict_blob_limit_fails_before_hash_without_allocating_large_heap(self):
        self.enable()
        with self.source.open("wb") as stream:
            stream.truncate(MAX_FILE_BYTES + 1)
        with patch.object(delivery, "_file_sha256", side_effect=AssertionError("over limit")):
            with self.assertRaisesRegex(BlobError, "artifact_blob_size_exceeded"):
                publication.prepare_for_route(self.bridge, self.route, self.artifact.task_id, self.task["output_files"])

    def test_resume_does_not_hold_phone_send_lock_during_source_preparation(self):
        self.fail()
        self.enable()
        original = publication.prepare_result
        def prepare(*args, **kwargs):
            acquired = []
            def other_message():
                with self.bridge.phone_publish_lock:
                    acquired.append(True)
            thread = threading.Thread(target=other_message, daemon=True)
            thread.start()
            thread.join(1)
            self.assertEqual([True], acquired, "file preparation blocked unrelated messages")
            return original(*args, **kwargs)
        with patch.object(publication, "prepare_result", side_effect=prepare):
            self.assertTrue(deferred.resume(self.bridge, self.task, self.route)["ok"])

    def test_identity_changed_during_preparation_is_rejected_before_publication(self):
        self.fail()
        runtime = self.enable()
        original = publication.prepare_result
        def prepare(*args, **kwargs):
            self.peer["identity_fingerprint"] = "f" * 64
            return original(*args, **kwargs)
        with patch.object(publication, "prepare_result", side_effect=prepare):
            result = deferred.resume(self.bridge, self.task, self.route)
        self.assertFalse(result["ok"])
        self.assertEqual("artifact_blob_identity_changed", result["code"])
        self.assertEqual({}, runtime.sender.journal.snapshot())
        self.assertEqual(1, len(self.records()))

    def test_changed_file_cannot_replace_the_original_output_on_retry(self):
        self.fail()
        runtime = self.enable()
        original = self.source.read_bytes()
        self.source.write_bytes(b"a different report")
        result = deferred.resume(self.bridge, self.task, self.route)
        self.assertEqual("artifact_source_changed", result["code"])
        self.assertEqual({}, runtime.sender.journal.snapshot())
        self.assertEqual(1, len(self.records()))
        self.source.write_bytes(original)
        self.assertTrue(deferred.resume(self.bridge, self.task, self.route)["ok"])

    def test_publication_identity_failure_does_not_rebind_intent_to_replacement(self):
        with patch.object(publication, "publish_result", side_effect=BlobError("artifact_blob_identity_changed")):
            with self.assertRaisesRegex(BlobError, "artifact_blob_identity_changed"):
                deferred.publish_or_defer(self.bridge, None, {"_client_route_id": self.route},
                    [self.artifact], self.payload, self.task["output_files"])
        self.assertEqual([], self.records())
        self.bridge._publish_to_registered_client.assert_not_called()


if __name__ == "__main__":
    unittest.main()
