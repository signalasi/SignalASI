"""Actual large files exercise preparation, imported references and source leases."""
from dataclasses import replace
import json
from pathlib import Path
import tracemalloc
import unittest
from unittest.mock import Mock, patch

import artifact_delivery as delivery
import blob_artifact_publication as publication
from blob_protocol import BlobError, MAX_FILE_BYTES
import task_workspace as workspace
import test_blob_artifact_peer as fixtures


class BlobArtifactLargePreparationTests(unittest.TestCase):
    setUp = fixtures.BlobArtifactPeerTests.setUp
    stop = fixtures.BlobArtifactPeerTests.stop
    enable = fixtures.BlobArtifactPeerTests.enable
    send = fixtures.BlobArtifactPeerTests.send
    queued_batch = fixtures.BlobArtifactPeerTests.queued_batch

    def grow(self, size, path=None):
        path = path or self.source
        with path.open("wb") as output:
            output.write(b"large artifact beginning")
            output.truncate(size)
        return path

    def prepare(self, maximum=MAX_FILE_BYTES, compress=False):
        return delivery.prepare_artifacts(self.artifact.task_id, [{"relative_path": self.artifact.relative_path}],
            compress_images=compress, maximum_bytes=maximum)

    def test_actual_152_mib_contact_send_keeps_bounded_heap_and_no_mqtt_chunks(self):
        size = 152 * 1024 * 1024 + 17
        self.grow(size)
        original_read = Path.read_bytes
        def bounded_read(path):
            if path.stat().st_size > 1024 * 1024:
                raise AssertionError("large file read into a single bytes object")
            return original_read(path)
        tracemalloc.start()
        try:
            with patch.object(Path, "read_bytes", bounded_read):
                result = self.send()
                self.assertTrue(result["ok"], result)
                self.runtime.sender._register_batches()
            _, peak = tracemalloc.get_traced_memory()
        finally:
            tracemalloc.stop()
        self.assertLess(peak, 24 * 1024 * 1024)
        wire = self.bridge._publish_to_registered_client.call_args.args[2]
        self.assertEqual(size, wire["attachments"][0]["size_bytes"])
        self.assertLess(len(json.dumps(wire)), 32 * 1024)
        self.bridge._publish_task_artifacts.assert_not_called()
        self.assertEqual({"pending": 1}, self.runtime.sender.journal.snapshot())
        self.assertEqual(size, self.store.get_message(result["message_id"])["attachments"][0]["size_bytes"])
        print(f"large_contact_preparation bytes={size} python_heap_peak={peak}")

    def test_blob_boundary_one_gib_is_streamed_not_materialized(self):
        self.grow(MAX_FILE_BYTES)
        tracemalloc.start()
        try:
            with patch.object(Path, "read_bytes", side_effect=AssertionError("whole-file allocation")):
                items = self.prepare()
            _, peak = tracemalloc.get_traced_memory()
        finally:
            tracemalloc.stop()
        self.assertEqual(1, len(items))
        self.assertEqual(MAX_FILE_BYTES, items[0].size_bytes)
        self.assertIsNone(items[0].transport_bytes)
        self.assertEqual(4096, items[0].chunk_count)
        self.assertLess(peak, 8 * 1024 * 1024)
        print(f"one_gib_preparation python_heap_peak={peak}")

    def test_above_protocol_limit_is_rejected_before_hashing(self):
        self.grow(MAX_FILE_BYTES + 1)
        with patch.object(delivery, "_file_sha256", side_effect=AssertionError("should reject before reading")):
            self.assertEqual([], self.prepare())

    def test_legacy_preparation_boundary_remains_explicit(self):
        self.grow(delivery.MAX_ARTIFACT_BYTES)
        self.assertEqual(1, len(self.prepare(delivery.MAX_ARTIFACT_BYTES)))
        self.grow(delivery.MAX_ARTIFACT_BYTES + 1)
        self.assertEqual([], self.prepare(delivery.MAX_ARTIFACT_BYTES))
        self.assertEqual(1, len(self.prepare()))

    def test_invalid_limit_does_not_enable_unbounded_preparation(self):
        for limit in (True, 0, -1, MAX_FILE_BYTES + 1, "1073741824"):
            with self.assertRaises(ValueError):
                self.prepare(limit)

    def test_unnegotiated_route_cannot_prepare_oversized_mqtt_artifacts(self):
        self.grow(delivery.MAX_ARTIFACT_BYTES + 1)
        with patch("blob_pair_configuration.can_receive_artifacts", return_value=False):
            with self.assertRaisesRegex(BlobError, "artifact_blob_transport_required"):
                publication.prepare_for_route(self.bridge, self.route, self.artifact.task_id,
                    [{"relative_path": self.artifact.relative_path}])

    def test_empty_output_does_not_inspect_transport_or_initialize_blob_state(self):
        bridge = Mock()
        self.assertEqual([], publication.prepare_for_route(bridge, self.route, "task", []))
        bridge.get_client.assert_not_called()

    def test_contact_failure_is_visible_and_keeps_imported_file(self):
        self.grow(delivery.MAX_ARTIFACT_BYTES + 1)
        with patch("blob_pair_configuration.can_receive_artifacts", return_value=False):
            result = self.send()
        self.assertFalse(result["ok"])
        self.assertEqual("artifact_blob_transport_required", result["code"])
        message = self.store.get_message(result["message_id"])
        self.assertEqual("failed", message["delivery_status"])
        self.assertEqual(1, len(message["attachments"]))
        imported = Path(self.store.attachment_record(result["message_id"], 0)["local_path"])
        self.assertEqual(self.source.stat().st_size, imported.stat().st_size)
        self.bridge._publish_task_artifacts.assert_not_called()
        self.bridge._publish_to_registered_client.assert_not_called()

    def test_large_prepared_file_never_becomes_legacy_chunk_payloads(self):
        oversized = replace(self.artifact, size_bytes=delivery.MAX_ARTIFACT_BYTES + 1)
        with self.assertRaisesRegex(BlobError, "artifact_blob_transport_required"):
            next(delivery.artifact_chunk_payloads(oversized))

    def test_lost_capability_after_preparation_cannot_fall_back_to_mqtt(self):
        oversized = replace(self.artifact, size_bytes=delivery.MAX_ARTIFACT_BYTES + 1)
        with patch.object(publication, "enqueue_result", return_value=False):
            with self.assertRaisesRegex(BlobError, "artifact_blob_transport_required"):
                publication.publish_result(self.bridge, None, {"_client_route_id": self.route}, [oversized], self.payload,
                    retain_on_desktop=False)
        self.bridge._publish_task_artifacts.assert_not_called()
        self.assertFalse(delivery._ledger_path().exists())

    def test_cross_turn_reference_above_64_mib_is_imported_and_hash_preserved(self):
        size = 65 * 1024 * 1024 + 1
        self.grow(size)
        content = f"[download](<{self.source}>)"
        self.assertEqual([self.source], workspace.referenced_task_artifact_paths(content))
        outputs = workspace.import_referenced_task_artifacts("next-turn", content)
        imported = delivery.prepare_artifacts("next-turn", outputs, maximum_bytes=MAX_FILE_BYTES)
        self.assertEqual(1, len(imported))
        self.assertEqual(size, imported[0].size_bytes)
        self.assertEqual(delivery._file_sha256(self.source), imported[0].sha256)

    def test_relative_cross_turn_reference_above_64_mib_is_imported(self):
        self.grow(65 * 1024 * 1024)
        outputs = workspace.import_referenced_task_artifacts("next-turn", "[download](outputs/report.txt)",
            source_task_ids=[self.artifact.task_id])
        self.assertEqual(1, len(outputs))
        self.assertEqual(65 * 1024 * 1024, outputs[0]["size"])

    def test_over_limit_references_never_trigger_copy(self):
        self.grow(MAX_FILE_BYTES + 1)
        with patch.object(workspace, "_copy_artifact_to_output", side_effect=AssertionError("oversize copy")):
            self.assertEqual([], workspace.import_referenced_task_artifacts("next-turn", f"[file](<{self.source}>)"))


if __name__ == "__main__":
    unittest.main()
