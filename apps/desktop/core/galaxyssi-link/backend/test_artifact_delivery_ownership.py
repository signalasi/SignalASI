"""Recipient ownership, restart and cleanup boundaries for real workspace files."""
import json
import os
import tempfile
import unittest
from concurrent.futures import ThreadPoolExecutor
from unittest.mock import patch

import artifact_delivery as delivery
from task_workspace import task_workspace


class ArtifactOwnershipTests(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        environment = patch.dict(os.environ, {"GALAXYSSI_WORKSPACE_ROOT": temporary.name})
        environment.start()
        self.addCleanup(environment.stop)
        self.root = task_workspace("shared-task", "codex")
        self.source = self.root / "outputs" / "report.txt"
        self.source.write_bytes(b"shared source")
        self.artifact = delivery.prepare_artifacts("shared-task", [{"relative_path": "outputs/report.txt"}])[0]

    def register(self, route, retain=False, artifact=None, scope=""):
        artifact = artifact or self.artifact
        delivery.register_artifact_batch([artifact], client_route_id=route,
            retain_on_desktop=retain, delivery_scopes={artifact.artifact_id: scope} if scope else None)

    def receipt(self, route, artifact=None, scope="", **overrides):
        artifact = artifact or self.artifact
        payload = {"artifact_id": artifact.artifact_id, "sha256": artifact.sha256, "status": "stored"}
        payload.update(overrides)
        return delivery.acknowledge_artifact(payload, client_route_id=route, delivery_scope=scope)

    def test_same_phone_different_transfers_cannot_release_each_others_source(self):
        self.register("s20", scope="a" * 64)
        self.register("s20", scope="b" * 64)
        self.assertTrue(self.receipt("s20", scope="a" * 64))
        self.assertTrue(self.source.is_file())
        self.assertTrue(self.receipt("s20", scope="a" * 64))
        self.assertTrue(self.source.is_file())
        self.assertTrue(self.receipt("s20", scope="b" * 64))
        self.assertFalse(self.root.exists())

    def test_legacy_receipt_cannot_select_a_blob_lease_using_payload_fields(self):
        self.register("s20", scope="a" * 64)
        self.assertFalse(self.receipt("s20", transfer_id="a" * 64, delivery_scope="a" * 64))
        self.assertTrue(self.source.is_file())
        self.assertTrue(self.receipt("s20", scope="a" * 64))

    def test_legacy_and_blob_owners_are_both_required_for_workspace_cleanup(self):
        self.register("s20")
        self.register("s26", scope="a" * 64)
        self.assertTrue(self.receipt("s26", scope="a" * 64))
        self.assertTrue(self.source.is_file())
        self.assertTrue(self.receipt("s20"))
        self.assertFalse(self.root.exists())

    def test_legacy_recovery_never_resends_blob_lease_as_mqtt_chunks(self):
        self.register("s20", scope="a" * 64)
        self.register("s26")
        self.assertEqual(["s26"], [route for route, _ in delivery.pending_artifacts_for_redelivery()])
        self.assertIsNone(delivery.artifact_for_redelivery({"artifact_id": self.artifact.artifact_id,
            "artifact_uri": self.artifact.artifact_uri, "sha256": self.artifact.sha256}, client_route_id="s20"))

    def test_scope_survives_reload_and_duplicate_registration(self):
        self.register("s20", scope="a" * 64)
        self.register("s20", scope="b" * 64)
        self.receipt("s20", scope="a" * 64)
        self.register("s20", scope="a" * 64)
        self.assertEqual({"a" * 64, "b" * 64}, {item["delivery_scope"] for item in delivery._read_ledger().values()})
        self.receipt("s20", scope="b" * 64)
        self.assertFalse(self.root.exists())

    def test_invalid_or_missing_scope_does_not_overwrite_existing_ownership(self):
        self.register("s20")
        before = delivery._ledger_path().read_bytes()
        for scopes in ({}, {self.artifact.artifact_id: ""}, {self.artifact.artifact_id: "../escape"},
                       {self.artifact.artifact_id: "A" * 64}, {self.artifact.artifact_id: None}):
            with self.subTest(scopes=scopes), self.assertRaises(ValueError):
                delivery.register_artifact_batch([self.artifact], client_route_id="s20", retain_on_desktop=False,
                                                 delivery_scopes=scopes)
        self.assertEqual(before, delivery._ledger_path().read_bytes())

    def test_same_artifact_two_phones_keep_independent_pending_owners(self):
        self.register("s20")
        self.register("s26")
        self.assertEqual({"s20", "s26"}, {route for route, _ in delivery.pending_artifacts_for_redelivery()})
        self.assertTrue(self.receipt("s20"))
        self.assertTrue(self.source.is_file())
        self.assertEqual(["s26"], [route for route, _ in delivery.pending_artifacts_for_redelivery()])
        self.assertTrue(self.receipt("s26"))
        self.assertFalse(self.root.exists())

    def test_all_artifacts_across_routes_must_complete(self):
        second = self.root / "outputs" / "second.txt"
        second.write_bytes(b"second")
        artifact = delivery.prepare_artifacts("shared-task", [{"relative_path": "outputs/second.txt"}])[0]
        self.register("s20")
        self.register("s26", artifact=artifact)
        self.receipt("s20")
        self.assertTrue(second.is_file())
        self.receipt("s26", artifact)
        self.assertFalse(self.root.exists())

    def test_duplicate_registration_preserves_stored_state(self):
        self.register("s20")
        self.register("s26")
        self.receipt("s20")
        self.register("s20")
        self.receipt("s26")
        self.assertFalse(self.root.exists())
        self.assertTrue(self.receipt("s20"))

    def test_any_authorized_retention_survives_other_phone_receipt(self):
        self.register("s20", retain=True)
        self.register("s26")
        self.receipt("s20")
        self.receipt("s26")
        self.assertTrue(self.source.is_file())
        self.assertEqual([], delivery.pending_artifacts_for_redelivery())

    def test_duplicate_registration_cannot_remove_retention(self):
        self.register("s20", retain=True)
        self.register("s20", retain=False)
        self.receipt("s20")
        self.assertTrue(self.source.is_file())

    def test_wrong_route_hash_or_broker_status_never_releases_source(self):
        self.register("s20")
        self.assertFalse(self.receipt("s26"))
        self.assertFalse(self.receipt("s20", sha256="0" * 64))
        self.assertFalse(self.receipt("s20", status="published"))
        self.assertTrue(self.source.is_file())

    def test_legacy_disk_owner_is_migrated_not_overwritten(self):
        self.register("s20")
        entry = next(iter(delivery._read_ledger().values()))
        entry.pop("artifact_id")
        delivery._ledger_path().write_text(json.dumps({self.artifact.artifact_id: entry}), encoding="utf-8")
        self.register("s26")
        self.receipt("s26")
        self.assertTrue(self.source.is_file())
        self.receipt("s20")
        self.assertFalse(self.root.exists())

    def test_expiry_never_removes_pending_source_or_another_owner(self):
        with patch.object(delivery.time, "time", return_value=100):
            self.register("s20")
            self.register("s26")
            self.receipt("s20")
        with patch.object(delivery.time, "time", return_value=100 + 2 * delivery.LEDGER_TTL_SECONDS):
            pending = delivery.pending_artifacts_for_redelivery()
        self.assertEqual(["s26"], [route for route, _ in pending])
        self.assertTrue(self.source.is_file())
        self.assertEqual(2, len(delivery._read_ledger()))

    def test_expired_completed_receipts_are_pruned_without_deleting_retained_files(self):
        with patch.object(delivery.time, "time", return_value=100):
            self.register("s20", retain=True)
            self.receipt("s20")
        self.assertEqual([], delivery.pending_artifacts_for_redelivery())
        self.assertEqual({}, delivery._read_ledger())
        self.assertTrue(self.source.is_file())

    def test_recent_completion_of_old_transfer_keeps_idempotent_receipt(self):
        with patch.object(delivery.time, "time", return_value=100):
            self.register("s20")
        self.assertTrue(self.receipt("s20"))
        self.assertEqual([], delivery.pending_artifacts_for_redelivery())
        self.assertTrue(self.receipt("s20"))

    def test_cleanup_failure_preserves_receipt_for_idempotent_retry(self):
        self.register("s20")
        with patch.object(delivery, "cleanup_task_workspace", side_effect=OSError("busy")):
            with self.assertRaises(OSError):
                self.receipt("s20")
        entry = next(iter(delivery._read_ledger().values()))
        self.assertEqual("stored", entry["state"])
        self.assertFalse(entry.get("cleanup_done", False))
        self.assertTrue(self.receipt("s20"))
        self.assertFalse(self.root.exists())

    def test_receipt_persistence_failure_does_not_delete_source(self):
        self.register("s20")
        with patch.object(delivery, "_write_ledger", side_effect=OSError("disk full")):
            with self.assertRaises(OSError):
                self.receipt("s20")
        self.assertTrue(self.source.is_file())
        self.assertEqual("pending", next(iter(delivery._read_ledger().values()))["state"])

    def test_unsuccessful_cleanup_is_not_marked_complete(self):
        self.register("s20")
        with patch.object(delivery, "cleanup_task_workspace", return_value=False):
            with self.assertRaisesRegex(OSError, "cleanup incomplete"):
                self.receipt("s20")
        self.assertFalse(next(iter(delivery._read_ledger().values())).get("cleanup_done", False))
        self.assertTrue(self.receipt("s20"))

    def test_crash_after_delete_before_completion_checkpoint_is_recoverable(self):
        self.register("s20")
        original = delivery._write_ledger
        def write(ledger):
            if any(entry.get("cleanup_done") for entry in ledger.values()):
                raise OSError("crash after delete")
            original(ledger)
        with patch.object(delivery, "_write_ledger", side_effect=write):
            with self.assertRaises(OSError):
                self.receipt("s20")
        self.assertFalse(self.root.exists())
        self.assertTrue(self.receipt("s20"))

    def test_corrupt_ledger_is_not_silently_replaced_with_one_recipient(self):
        delivery._ledger_path().write_bytes(b"{truncated")
        with self.assertRaisesRegex(ValueError, "ledger unavailable"):
            self.register("s20")
        self.assertEqual(b"{truncated", delivery._ledger_path().read_bytes())
        self.assertTrue(self.source.is_file())

    def test_concurrent_receipts_cleanup_once(self):
        routes = [f"phone-{index}" for index in range(8)]
        for route in routes:
            self.register(route)
        with patch.object(delivery, "cleanup_task_workspace", wraps=delivery.cleanup_task_workspace) as cleanup:
            with ThreadPoolExecutor(max_workers=8) as pool:
                self.assertTrue(all(pool.map(self.receipt, routes)))
            self.assertEqual(1, cleanup.call_count)
        self.assertFalse(self.root.exists())


if __name__ == "__main__":
    unittest.main()
