from concurrent.futures import ThreadPoolExecutor
import json
from pathlib import Path
import sqlite3
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch

import link_delivery
from task_result_outbox import PAGE_SIZE, TaskResultOutbox


def outcome(**changes):
    return {"client_route_id": "phone", "conversation_id": "conversation", "task_id": "task",
            "turn_id": "turn", "contact_id": "contact", "source_message_id": "source",
            "agent_id": "codex", "execution_generation": 1, "message_id": "message",
            "type": "text", "task_status": "completed", "content": "canonical reply", **changes}


class TaskResultOutboxTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.path = Path(self.temporary.name) / "delivery.db"
        self.patch = patch.object(link_delivery, "DB_PATH", self.path)
        self.patch.start()
        self.addCleanup(self.patch.stop)

    def enqueue(self, **changes):
        payload = outcome(**changes)
        return link_delivery.queue_task_result(payload["task_id"], payload["client_route_id"],
            {"_client_route_id": payload["client_route_id"]}, payload)

    def sql(self, statement, params=()):
        db = link_delivery._connect()
        try:
            with db:
                return db.execute(statement, params).fetchall()
        finally:
            db.close()

    def test_stale_receipt_cannot_remove_new_generation(self):
        old = self.enqueue()
        new = self.enqueue(execution_generation=2, content="retry reply")
        self.assertFalse(link_delivery.remove_task_result(old))
        self.assertEqual([new], link_delivery.pending_task_results())
        self.assertTrue(link_delivery.remove_task_result(new))
        self.assertFalse(link_delivery.remove_task_result(new))

    def test_generation_fence_survives_retirement_and_reopen(self):
        newest = self.enqueue(execution_generation=3)
        self.assertTrue(link_delivery.remove_task_result(newest))
        self.assertIsNone(self.enqueue(execution_generation=1))
        self.assertIsNone(self.enqueue(execution_generation=2))
        self.assertIsNone(self.enqueue(execution_generation=3))
        self.assertEqual([], link_delivery.pending_task_results())
        self.assertIsNotNone(self.enqueue(execution_generation=4))

    def test_explicit_replay_has_new_revision_but_cannot_revive_older_generation(self):
        old = self.enqueue()
        self.assertTrue(link_delivery.remove_task_result(old))
        replay = link_delivery.queue_task_result("task", "phone", {"_client_route_id": "phone"}, outcome(), replay=True)
        self.assertNotEqual(old["revision"], replay["revision"])
        self.assertNotEqual(old["payload"]["message_id"], replay["payload"]["message_id"])
        self.assertFalse(link_delivery.remove_task_result(old))
        self.assertTrue(link_delivery.task_result_is_current(replay))
        self.enqueue(execution_generation=2)
        self.assertIsNone(link_delivery.queue_task_result("task", "phone", {"_client_route_id": "phone"}, outcome(), replay=True))

    def test_explicit_replay_keeps_pending_body_and_bypasses_failed_wire(self):
        import agent_task_result_archive
        import mqtt_bridge
        from types import SimpleNamespace
        old = self.enqueue()
        with patch.object(agent_task_result_archive.archive, "put", return_value=None), \
                patch.object(mqtt_bridge, "_ensure_outbound_retry_thread"), \
                patch.object(mqtt_bridge, "outbound_status", side_effect=lambda _, message: "failed" if message == "message" else None), \
                patch.object(mqtt_bridge, "_publish_phone_payload", return_value=True) as publish:
            self.assertTrue(mqtt_bridge._publish_or_queue_task_result(SimpleNamespace(is_connected=lambda: True),
                {"_client_route_id": "phone"}, outcome(content="rerender"), replay=True))
        self.assertEqual("canonical reply", publish.call_args.args[2]["content"])
        self.assertNotEqual("message", publish.call_args.args[2]["message_id"])
        self.assertFalse(link_delivery.remove_task_result(old))

    def test_duplicate_keeps_original_body_and_receipt(self):
        original = self.enqueue()
        later = self.enqueue(content="rerendered", message_id="wrong-new-id")
        self.assertEqual(original, later)
        self.assertEqual("canonical reply", link_delivery.pending_task_results()[0]["payload"]["content"])

    def test_each_identity_dimension_has_an_independent_head(self):
        first = self.enqueue(execution_generation=8)
        for field in ("client_route_id", "conversation_id", "task_id", "turn_id", "contact_id", "source_message_id", "agent_id"):
            with self.subTest(field=field):
                other = self.enqueue(**{field: "other"})
                self.assertNotEqual(first["scope"], other["scope"])
                self.assertTrue(link_delivery.remove_task_result(other))
        self.assertTrue(link_delivery.task_result_is_current(first))

    def test_rejects_invalid_generation_and_incomplete_scope(self):
        for generation in (0, -1, True, 1.5, "2", 2**53):
            with self.subTest(generation=generation), self.assertRaises(ValueError):
                self.enqueue(execution_generation=generation)
        for field in ("contact_id", "agent_id", "source_message_id", "turn_id"):
            with self.subTest(field=field), self.assertRaises(ValueError):
                self.enqueue(**{field: ""})

    def test_rejects_mismatched_envelope(self):
        with self.assertRaises(ValueError):
            link_delivery.queue_task_result("task", "phone", {"_client_route_id": "other"}, outcome())

    def test_task_id_or_partial_receipt_cannot_delete(self):
        record = self.enqueue()
        for receipt in ("task", {}, {"scope": record["scope"], "revision": record["revision"]},
                        {**record, "revision": "wrong"}, {**record, "scope": "wrong"}):
            self.assertFalse(link_delivery.remove_task_result(receipt))
        self.assertTrue(link_delivery.task_result_is_current(record))

    def test_pending_results_are_retained_across_age_and_broker_epoch(self):
        record = self.enqueue()
        self.sql("UPDATE task_result_queue SET created_at=1")
        link_delivery.queue_outbound("phone", "cipher", "topic", "wire")
        self.assertTrue(link_delivery.ensure_transport_epoch("new-broker"))
        self.assertEqual([], link_delivery.pending_outbound())
        self.assertEqual(record["revision"], link_delivery.pending_task_results()[0]["revision"])
        self.assertFalse(link_delivery.ensure_transport_epoch("new-broker"))

    def test_pending_page_is_bounded_and_deferred_routes_rotate(self):
        for i in range(75):
            self.enqueue(task_id=f"task-{i:03}")
        first = link_delivery.pending_task_results(limit=10000)
        second = link_delivery.pending_task_results()
        third = link_delivery.pending_task_results()
        self.assertEqual(PAGE_SIZE, len(first))
        self.assertEqual(PAGE_SIZE, len(second))
        self.assertFalse({r["scope"] for r in first} & {r["scope"] for r in second})
        self.assertEqual(75, len({r["scope"] for r in first + second + third}))
        self.assertEqual([], link_delivery.pending_task_results(limit=0))

    def test_corrupt_row_does_not_block_other_results_or_get_deleted(self):
        damaged = self.enqueue()
        healthy = self.enqueue(task_id="healthy")
        self.sql("UPDATE task_result_queue SET payload='damaged-ciphertext' WHERE scope=?", (damaged["scope"],))
        self.assertEqual([healthy], link_delivery.pending_task_results())
        self.assertEqual([("corrupt", "damaged-ciphertext")], self.sql(
            "SELECT state,payload FROM task_result_queue WHERE scope=?", (damaged["scope"],)))
        self.assertIsNotNone(self.enqueue(execution_generation=2))

    def test_swapped_encrypted_bodies_are_quarantined(self):
        a, b = self.enqueue(), self.enqueue(task_id="other")
        body = self.sql("SELECT payload FROM task_result_queue WHERE scope=?", (b["scope"],))[0][0]
        self.sql("UPDATE task_result_queue SET payload=? WHERE scope=?", (body, a["scope"]))
        self.assertEqual([b], link_delivery.pending_task_results())

    def test_body_decryption_does_not_hold_database_writer(self):
        self.enqueue()
        decode = TaskResultOutbox._decode
        def observe(store, row):
            with ThreadPoolExecutor(max_workers=1) as executor:
                self.assertIsNotNone(executor.submit(self.enqueue, task_id="concurrent-writer").result(timeout=3))
            return decode(store, row)
        with patch.object(TaskResultOutbox, "_decode", observe):
            self.assertEqual(1, len(link_delivery.pending_task_results()))

    def test_old_corrupt_snapshot_cannot_quarantine_new_generation(self):
        self.enqueue()
        def observe(*_):
            self.enqueue(execution_generation=2)
            raise ValueError("old snapshot failed validation")
        with patch.object(TaskResultOutbox, "_decode", observe):
            self.assertEqual([], link_delivery.pending_task_results())
        self.assertEqual(2, link_delivery.pending_task_results()[0]["execution_generation"])

    def test_body_and_identity_not_plaintext_at_rest(self):
        self.enqueue(content="private-result-sentinel", source_message_id="private-source-sentinel")
        for file in self.path.parent.glob("delivery.db*"):
            raw = file.read_bytes()
            self.assertNotIn(b"private-result-sentinel", raw)
            self.assertNotIn(b"private-source-sentinel", raw)

    def legacy(self, payload=None, ciphertext=None):
        body = payload or outcome()
        self.sql("""INSERT INTO task_result_outbox
            (task_id,client_route_id,wire_payload,payload,created_at,updated_at)
            VALUES(?,?,?,?,1,1)""", (body["task_id"], link_delivery._route("phone"),
            link_delivery._protect(json.dumps({"_client_route_id": "phone"}), "task-wire-payload"),
            ciphertext or link_delivery._protect(json.dumps(body), "task-payload")))

    def test_migrates_legacy_encrypted_row_without_resetting_other_state(self):
        link_delivery.claim_message("phone", "accepted-message")
        self.legacy()
        self.assertEqual("canonical reply", link_delivery.pending_task_results()[0]["payload"]["content"])
        self.assertEqual([], self.sql("SELECT * FROM task_result_outbox"))
        self.assertFalse(link_delivery.claim_message("phone", "accepted-message"))

    def test_legacy_canonical_row_wins_before_same_generation_enqueue(self):
        self.legacy()
        self.assertEqual("canonical reply", self.enqueue(content="new rendering")["payload"]["content"])

    def test_invalid_legacy_row_retained_without_blocking_healthy(self):
        self.legacy(ciphertext="broken")
        good = self.enqueue(task_id="healthy")
        self.assertEqual([good], link_delivery.pending_task_results())
        self.assertEqual([("broken", 1)], self.sql("SELECT payload,migration_error FROM task_result_outbox"))

    def test_migration_batch_is_bounded_and_eventually_complete(self):
        for i in range(70):
            self.legacy(outcome(task_id=f"legacy-{i:03}"))
        self.assertEqual(32, len(link_delivery.pending_task_results()))
        self.assertEqual([(38,)], self.sql("SELECT count(*) FROM task_result_outbox"))
        link_delivery.pending_task_results()
        link_delivery.pending_task_results()
        self.assertEqual([(0,)], self.sql("SELECT count(*) FROM task_result_outbox"))
        self.assertEqual([(70,)], self.sql("SELECT count(*) FROM task_result_queue"))

    def test_enqueue_failure_rolls_back_generation_and_body(self):
        old = self.enqueue()
        self.sql("""CREATE TRIGGER fail_queue_update BEFORE UPDATE ON task_result_queue
            BEGIN SELECT RAISE(ABORT,'simulated disk failure'); END""")
        with self.assertRaises(sqlite3.IntegrityError):
            self.enqueue(execution_generation=2)
        self.sql("DROP TRIGGER fail_queue_update")
        self.assertEqual([old], link_delivery.pending_task_results())

    def test_handoff_failure_rolls_back_and_keeps_body(self):
        record = self.enqueue()
        self.sql("""CREATE TRIGGER fail_handoff BEFORE UPDATE ON task_result_queue
            WHEN NEW.state='handed_off' BEGIN SELECT RAISE(ABORT,'simulated disk failure'); END""")
        with self.assertRaises(sqlite3.IntegrityError):
            link_delivery.remove_task_result(record)
        self.assertTrue(link_delivery.task_result_is_current(record))

    def test_migration_failure_rolls_back_legacy_deletion(self):
        self.legacy()
        self.sql("""CREATE TRIGGER fail_legacy_delete BEFORE DELETE ON task_result_outbox
            BEGIN SELECT RAISE(ABORT,'simulated disk failure'); END""")
        with self.assertRaises(sqlite3.IntegrityError):
            link_delivery.pending_task_results()
        self.assertEqual([(1,)], self.sql("SELECT count(*) FROM task_result_outbox"))
        self.assertEqual([(0,)], self.sql("SELECT count(*) FROM task_result_queue"))

    def test_concurrent_generations_converge_to_highest_and_old_receipts_fail(self):
        self.enqueue()
        with ThreadPoolExecutor(max_workers=8) as executor:
            records = list(executor.map(lambda i: self.enqueue(execution_generation=i), range(2, 26)))
        latest = link_delivery.pending_task_results()[0]
        self.assertEqual(25, latest["execution_generation"])
        for record in records:
            if record and record["execution_generation"] < 25:
                self.assertFalse(link_delivery.remove_task_result(record))

    def test_concurrent_first_open_keeps_every_distinct_result(self):
        with ThreadPoolExecutor(max_workers=8) as executor:
            records = list(executor.map(lambda i: self.enqueue(task_id=f"task-{i}"), range(24)))
        self.assertEqual({r["scope"] for r in records},
                         {r["scope"] for r in link_delivery.pending_task_results()})

    def test_schema_upgrade_keeps_encrypted_key_and_delivery_version(self):
        self.enqueue()
        key_files = {p.name: p.read_bytes() for p in self.path.parent.glob(".galaxyssi-state-key*")}
        self.assertTrue(key_files)
        for _ in range(4):
            link_delivery.pending_task_results()
        self.assertEqual(key_files, {p.name: p.read_bytes() for p in self.path.parent.glob(".galaxyssi-state-key*")})
        self.assertEqual([("1",)], self.sql("SELECT value FROM delivery_metadata WHERE key='secure_storage_version'"))

    def child(self, script, *args):
        result = subprocess.run([sys.executable, "-c", "import link_delivery,sys,json,os; "
            "from pathlib import Path; link_delivery.DB_PATH=Path(sys.argv[1]); " + script,
            str(self.path), *args], cwd=Path(__file__).parent, capture_output=True, text=True, timeout=30)
        self.assertEqual(76, result.returncode, result.stderr)

    def test_process_exit_after_enqueue_and_handoff_preserves_fence(self):
        self.child("p=json.loads(sys.argv[2]); link_delivery.queue_task_result('task','phone',"
            "{'_client_route_id':'phone'},p); os._exit(76)", json.dumps(outcome(execution_generation=2)))
        record = link_delivery.pending_task_results()[0]
        self.assertEqual(2, record["execution_generation"])
        self.child("assert link_delivery.remove_task_result(json.loads(sys.argv[2])); os._exit(76)", json.dumps(record))
        self.assertIsNone(self.enqueue())
        self.assertEqual([], link_delivery.pending_task_results())

    def test_old_process_snapshot_cannot_retire_new_retry(self):
        old = self.enqueue()
        new = self.enqueue(execution_generation=2)
        self.child("assert not link_delivery.remove_task_result(json.loads(sys.argv[2])); os._exit(76)", json.dumps(old))
        self.assertEqual([new], link_delivery.pending_task_results())


    def test_publish_finishes_after_new_retry_is_queued(self):
        import mqtt_bridge
        old = self.enqueue()
        def publish(*_):
            self.enqueue(execution_generation=2, content="new result")
            return True
        with patch.object(mqtt_bridge, "_publish_phone_payload", side_effect=publish):
            self.assertTrue(mqtt_bridge._publish_pending_task_result(object(), old))
        self.assertEqual(2, link_delivery.pending_task_results()[0]["execution_generation"])

    def test_failed_ciphertext_is_not_handoff_and_does_not_block_other_route(self):
        import mqtt_bridge
        self.enqueue()
        self.enqueue(client_route_id="other", message_id="other-message")
        with patch.object(mqtt_bridge, "outbound_status", side_effect=lambda route, _: "failed" if route == "phone" else None), \
                patch.object(mqtt_bridge, "_publish_phone_payload", return_value=True) as publish:
            mqtt_bridge.flush_pending_task_results(object())
        self.assertEqual(1, publish.call_count)
        self.assertEqual("phone", link_delivery.pending_task_results()[0]["client_route_id"])

    def test_stale_snapshot_is_not_published(self):
        import mqtt_bridge
        old = self.enqueue()
        self.enqueue(execution_generation=2)
        with patch.object(mqtt_bridge, "_publish_phone_payload") as publish:
            self.assertFalse(mqtt_bridge._publish_pending_task_result(object(), old))
            publish.assert_not_called()

    def test_transport_identity_changes_for_every_execution_scope_dimension(self):
        import agent_task_result_archive
        import mqtt_bridge
        payloads = [outcome()]
        payloads += [outcome(**{field: "other"}) for field in
                     ("client_route_id", "conversation_id", "task_id", "turn_id", "contact_id", "source_message_id", "agent_id")]
        payloads.append(outcome(execution_generation=2))
        with patch.object(agent_task_result_archive.archive, "put", return_value=None), \
                patch.object(mqtt_bridge, "queue_task_result", return_value=None) as queue:
            for payload in payloads:
                mqtt_bridge._publish_or_queue_task_result(None, {"_client_route_id": payload["client_route_id"]}, payload)
        self.assertEqual(9, len({call.args[3]["message_id"] for call in queue.call_args_list}))

    def test_transport_failure_after_preparation_retires_only_observed_revision(self):
        import mqtt_bridge
        old = self.enqueue()
        def publish(*_):
            self.enqueue(execution_generation=2)
            link_delivery.queue_outbound("phone", "message", "topic", "encrypted")
            raise OSError("broker disconnected after ciphertext commit")
        with patch.object(mqtt_bridge, "_publish_phone_payload", side_effect=publish):
            self.assertFalse(mqtt_bridge._publish_pending_task_result(object(), old))
        self.assertEqual(2, link_delivery.pending_task_results()[0]["execution_generation"])

    def test_duplicate_bridge_call_publishes_stored_canonical_body(self):
        import agent_task_result_archive
        import mqtt_bridge
        from types import SimpleNamespace
        wire = {"_client_route_id": "phone"}
        with patch.object(agent_task_result_archive.archive, "put", return_value=None), \
                patch.object(mqtt_bridge, "_ensure_outbound_retry_thread"), \
                patch.object(mqtt_bridge, "_publish_phone_payload", return_value=True) as publish:
            mqtt_bridge._publish_or_queue_task_result(None, wire, outcome(content="original"))
            mqtt_bridge._publish_or_queue_task_result(SimpleNamespace(is_connected=lambda: True),
                                                      wire, outcome(content="later rerender"))
        self.assertEqual("original", publish.call_args.args[2]["content"])


if __name__ == "__main__":
    unittest.main()
