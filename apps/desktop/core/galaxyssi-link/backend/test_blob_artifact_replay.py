"""Explicit replay after storage acknowledgement never rebuilds legacy file links."""
from types import SimpleNamespace
import unittest
from unittest.mock import Mock, patch

from api_response import api_ok, api_error
import artifact_delivery as delivery
import blob_artifact_replay as replay
from blob_protocol import BlobError
import blob_artifact_source as source
import test_blob_artifact_publication as fixture


class BlobArtifactReplayTests(unittest.TestCase):
    stop = fixture.BlobArtifactPublicationTests.stop
    enable = fixture.BlobArtifactPublicationTests.enable
    prepare = fixture.BlobArtifactPublicationTests.prepare

    def setUp(self):
        fixture.BlobArtifactPublicationTests.setUp(self)
        self.bridge.api_ok, self.bridge.api_error = api_ok, api_error
        self.bridge._client_task_turn_id = lambda task: task.get("client_turn_id") or task["turn_id"]
        self.bridge._publish_or_queue_task_result.return_value = True
        self.task = {**self.payload, "status": "completed", "result": "original unscoped model response",
                     "client_conversation_id": self.payload["conversation_id"], "client_turn_id": self.payload["turn_id"],
                     "output_files": [{"relative_path": self.artifact.relative_path}]}

    def batch(self, *, activate=True):
        runtime = self.enable()
        bodies, payload = self.prepare()
        batch_id = runtime.sender.enqueue_batch(bodies, publication=payload)
        if activate:
            runtime.sender._register_batches()
            self.assertEqual({"pending": 1}, runtime.sender.journal.snapshot())
        return runtime, bodies, payload, batch_id

    def test_absent_journal_falls_back_without_creating_private_state(self):
        self.assertIsNone(replay.republish(self.bridge, self.task, self.route))
        self.assertFalse((self.bridge.DATA_DIR / "blob-output").exists())
        self.bridge._publish_or_queue_task_result.assert_not_called()

    def test_replay_after_source_handoff_uses_exact_card_without_recreating_workspace(self):
        _, bodies, payload, _ = self.batch()
        self.assertTrue(source.commit_receipt(bodies[0]))
        self.assertFalse(self.root.exists())
        with patch.object(delivery, "prepare_artifacts", side_effect=AssertionError("must not read source")):
            result = replay.republish(self.bridge, self.task, self.route)
        self.assertEqual("agent_task_result_republished", result["code"])
        call = self.bridge._publish_or_queue_task_result.call_args
        self.assertTrue(call.kwargs["replay"])
        self.assertEqual(payload["rich_output"], call.args[2]["rich_output"])
        self.assertEqual(payload["content"], call.args[2]["content"])
        self.assertTrue(call.args[2]["recovery_replay"])
        self.assertFalse(self.root.exists())

    def test_phone_archive_acknowledgement_does_not_make_replay_rebuild_old_uris(self):
        _, _, payload, _ = self.batch()
        wire_payload = self.bridge._publish_to_registered_client.call_args.args[2]
        self.assertTrue(self.archive.acknowledge({**wire_payload, "sha256": wire_payload["result_recovery"]["sha256"]},
                                               client_route_id=self.route))
        self.assertEqual("agent_task_result_republished", replay.republish(self.bridge, self.task, self.route)["code"])
        self.assertEqual(payload["rich_output"], self.bridge._publish_or_queue_task_result.call_args.args[2]["rich_output"])

    def test_offline_replay_reports_queued_not_sent(self):
        self.batch()
        self.bridge._publish_or_queue_task_result.return_value = False
        result = replay.republish(self.bridge, self.task, self.route)
        self.assertEqual("agent_task_result_queued", result["code"])
        self.assertTrue(result["queued"])

    def test_pending_registration_is_not_bypassed_by_manual_replay(self):
        runtime, _, _, _ = self.batch(activate=False)
        with patch("blob_artifact_bridge.start") as start:
            result = replay.republish(self.bridge, self.task, self.route)
        start.assert_called_once_with(self.bridge)
        self.assertEqual("agent_task_result_queued", result["code"])
        self.assertEqual({"held": 1}, runtime.sender.journal.snapshot())
        self.bridge._publish_or_queue_task_result.assert_not_called()

    def test_terminal_batch_failure_explains_error_without_replaying_or_reregistering(self):
        runtime, _, _, _ = self.batch(activate=False)
        claim = runtime.sender.journal.batches.claim_due()[0]
        runtime.sender.journal.batches.fail(claim, "source_changed")
        result = replay.republish(self.bridge, self.task, self.route)
        self.assertEqual("artifact_delivery_failed", result["code"])
        self.assertEqual("source_changed", result["error_code"])
        self.bridge._publish_or_queue_task_result.assert_not_called()

    def test_peer_replacement_cannot_receive_old_scope_or_trigger_legacy_fallback(self):
        self.batch()
        self.peer["identity_fingerprint"] = "e" * 64
        result = replay.republish(self.bridge, self.task, self.route)
        self.assertEqual("artifact_blob_identity_changed", result["code"])
        self.bridge._publish_or_queue_task_result.assert_not_called()

    def test_lookup_requires_every_scope_component_and_generation(self):
        runtime, _, payload, _ = self.batch()
        self.assertIsNotNone(runtime.sender.journal.batches.publication_for(payload))
        for key, value in (("turn_id", "other"), ("client_route_id", "b" * 22), ("conversation_id", "other"),
                           ("source_message_id", "other"), ("contact_id", "other"), ("desktop_id", "other"),
                           ("execution_generation", 2)):
            with self.subTest(key=key):
                self.assertIsNone(runtime.sender.journal.batches.publication_for({**payload, key: value}))
        with self.assertRaises(BlobError):
            runtime.sender.journal.batches.publication_for({**payload, "execution_generation": True})

    def test_corrupt_batch_is_not_silently_treated_as_absent(self):
        runtime, _, _, batch_id = self.batch()
        with runtime.sender.journal._db() as db:
            db.execute("UPDATE artifact_batches SET body='broken' WHERE id=?", (batch_id,))
        result = replay.republish(self.bridge, self.task, self.route)
        self.assertEqual("artifact_blob_batch_invalid", result["code"])
        self.bridge._publish_or_queue_task_result.assert_not_called()

    def test_new_generation_does_not_replace_previous_generation_card(self):
        runtime, _, original, _ = self.batch()
        bodies, revised = self.prepare(payload={**self.payload, "execution_generation": 2})
        runtime.sender.enqueue_batch(bodies, publication=revised)
        runtime.sender._register_batches()
        for payload in (original, revised):
            found = runtime.sender.journal.batches.publication_for(payload)
            self.assertEqual(payload, found["publication"])
            self.assertEqual("done", found["state"])
        self.assertNotEqual(original["rich_output"], revised["rich_output"])

    def extra_batch(self, runtime):
        other = self.source.parent / "other.txt"
        other.write_bytes(b"a different generated artifact")
        artifact = delivery.prepare_artifacts(self.artifact.task_id, [{"relative_path": "outputs/other.txt"}])[0]
        bodies, payload = self.prepare([artifact], {**self.payload, "content": "another reply"})
        runtime.sender.enqueue_batch(bodies, publication=payload)
        return runtime.sender.journal.batches.claim_due()[0]

    def test_later_failed_publication_does_not_shadow_already_committed_reply(self):
        runtime, _, original, _ = self.batch()
        later = self.extra_batch(runtime)
        runtime.sender.journal.batches.fail(later, "artifact_blob_publication_conflict")
        found = runtime.sender.journal.batches.publication_for(original)
        self.assertEqual(original, found["publication"])
        self.assertEqual("done", found["state"])
        self.assertEqual("agent_task_result_republished", replay.republish(self.bridge, self.task, self.route)["code"])

    def test_conflicting_committed_records_are_not_resolved_by_arbitrary_row_order(self):
        runtime, _, original, _ = self.batch()
        runtime.sender.journal.batches.finish(self.extra_batch(runtime))
        with self.assertRaisesRegex(BlobError, "publication_ambiguous"):
            runtime.sender.journal.batches.publication_for(original)
        result = replay.republish(self.bridge, self.task, self.route)
        self.assertEqual("artifact_blob_publication_ambiguous", result["code"])
        self.bridge._publish_or_queue_task_result.assert_not_called()

    def test_multiple_failed_intents_before_committed_row_do_not_hide_it(self):
        runtime, _, original, _ = self.batch(activate=False)
        first = runtime.sender.journal.batches.claim_due()[0]
        second = self.extra_batch(runtime)
        third_path = self.source.parent / "third.txt"
        third_path.write_bytes(b"third candidate")
        artifact = delivery.prepare_artifacts(self.artifact.task_id, [{"relative_path": "outputs/third.txt"}])[0]
        bodies, payload = self.prepare([artifact], {**self.payload, "content": "third reply"})
        runtime.sender.enqueue_batch(bodies, publication=payload)
        third = runtime.sender.journal.batches.claim_due()[0]
        ordered = sorted((first, second, third), key=lambda batch: batch["id"])
        for batch in ordered[:-1]:
            runtime.sender.journal.batches.fail(batch, "source_changed")
        runtime.sender.journal.batches.finish(ordered[-1])
        found = runtime.sender.journal.batches.publication_for(original)
        self.assertEqual(ordered[-1]["publication"], found["publication"])

    def test_sqlite_failure_is_reported_without_fallback(self):
        self.batch()
        with patch("blob_artifact_journal.BlobArtifactJournal", side_effect=OSError("disk unavailable")):
            result = replay.republish(self.bridge, self.task, self.route)
        self.assertEqual("artifact_blob_checkpoint_unavailable", result["code"])
        self.bridge._publish_or_queue_task_result.assert_not_called()

    def test_actual_republish_entry_uses_batch_instead_of_legacy_artifact_builder(self):
        import mqtt_bridge
        _, _, payload, _ = self.batch()
        task = SimpleNamespace(task_id=self.task["task_id"], status="completed", result="model text",
                               client_route_id=self.route, public=lambda: self.task)
        with patch.object(mqtt_bridge, "DATA_DIR", self.bridge.DATA_DIR), \
                patch.object(mqtt_bridge, "agent_task_manager", SimpleNamespace(get=lambda _: task)), \
                patch.object(mqtt_bridge, "get_client", self.bridge.get_client), \
                patch.object(mqtt_bridge, "desktop_id", self.bridge.desktop_id), \
                patch.object(mqtt_bridge, "_publish_or_queue_task_result", return_value=True) as publish, \
                patch.object(mqtt_bridge, "_build_republished_task_result", side_effect=AssertionError("legacy rerender")), \
                patch.object(delivery, "prepare_artifacts", side_effect=AssertionError("source reload")):
            result = mqtt_bridge.republish_agent_task_result(self.task["task_id"])
        self.assertEqual("agent_task_result_republished", result["code"])
        self.assertEqual(payload["rich_output"], publish.call_args.args[2]["rich_output"])
