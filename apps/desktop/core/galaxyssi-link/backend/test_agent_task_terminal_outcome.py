"""Real persistence, process-death and generation boundaries for terminal recovery."""

import base64
from contextlib import closing
import json
from pathlib import Path
import sqlite3
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import Mock, patch

from agent_run_storage import RUN_KERNEL_DATABASE_NAME
from agent_task_manager import AgentTaskManager
from agent_task_recovery_query import IDENTITY_FIELDS, TASK_FIELDS, recovery_query
from agent_task_result_archive import TaskResultArchive
from agent_task_terminal_outcome import persist_terminal_outcome, recover_terminal_outcome, terminal_outcome


class TerminalOutcomeTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.root = Path(self.directory.name)
        self.archive = TaskResultArchive(self.root / "results.db")
        self.manager = AgentTaskManager(state_path=self.root / RUN_KERNEL_DATABASE_NAME)
        self.fields = dict(zip(IDENTITY_FIELDS, ("route", "conversation", "task", "turn", "contact", "42", "codex")))
        self.task = dict(zip(TASK_FIELDS, self.fields.values())) | dict(
            status="failed", execution_generation=1, status_seq=4,
            error="Provider returned HTTP 429", result="", updated_at=1700000000000)

    def page(self, generation=1, index=0, **changes):
        return self.archive.page({**self.fields, "execution_generation": generation,
                                  "request_id": "nonce", "page_index": index, **changes}, client_route_id="route")

    def stored(self, generation=1):
        first = self.page(generation)
        body = b"".join(base64.b64decode(self.page(generation, index)["data_b64"])
                        for index in range(first["page_count"]))
        return json.loads(body)

    def saved_task(self, status="failed", **changes):
        task = self.manager.create_external(
            task_id="task", agent_id="codex", contact_id="contact", source_message_id="42",
            prompt="private prompt", client_route_id="route", conversation_id="backend-conversation",
            client_conversation_id="conversation", client_turn_id="turn", on_event=lambda _: None)
        return self.manager.update(task.task_id, status, error="original failure", **changes)

    def recover(self, fields=None):
        return recover_terminal_outcome(fields or self.fields, client_route_id="route",
                                        manager=self.manager, result_archive=self.archive)

    def test_error_and_original_partial_result_are_preserved_without_prompt(self):
        payload = terminal_outcome({**self.task, "result": "partial answer", "prompt": "secret"})
        self.assertEqual("Provider returned HTTP 429", payload["content"])
        self.assertEqual("partial answer", payload["result"])
        self.assertFalse(payload["success"])
        self.assertNotIn("prompt", payload)
        self.assertEqual(1700000000, payload["time"])
        self.assertEqual(4, payload["status_sequence"])

    def test_status_only_cancellation_is_not_fabricated_as_error_text(self):
        task = {**self.task, "status": "cancelled", "error": ""}
        self.assertIsNotNone(persist_terminal_outcome(task, self.archive))
        payload = self.stored()
        self.assertEqual("", payload["content"])
        self.assertEqual("cancelled", payload["terminal_reason"])
        self.assertFalse(payload["success"])

    def test_nonterminal_and_completed_tasks_are_not_reconstructed(self):
        for status in ("accepted", "running", "paused", "completed", "waiting_input", "unknown"):
            with self.subTest(status=status):
                self.assertIsNone(terminal_outcome({**self.task, "status": status}))

    def test_identity_generation_and_sequence_validation(self):
        for field in TASK_FIELDS:
            self.assertIsNone(terminal_outcome({**self.task, field: ""}))
        for generation in (True, False, "2", 0, -1, 1.5, 2**53):
            self.assertIsNone(terminal_outcome({**self.task, "execution_generation": generation}))
        for sequence in (True, -1, "4"):
            self.assertIsNone(terminal_outcome({**self.task, "status_seq": sequence}))

    def test_retry_success_and_earlier_failure_have_distinct_bodies_and_receipts(self):
        failed = terminal_outcome(self.task)
        first = self.archive.put(failed)
        completed = {**self.fields, "execution_generation": 2, "type": "text",
                     "task_status": "completed", "content": "retry succeeded"}
        second = self.archive.put(completed)
        self.assertNotEqual(first, second)
        self.assertEqual("failed", self.stored(1)["task_status"])
        self.assertEqual(completed, self.stored(2))
        self.assertEqual("unavailable", self.page(3)["status"])
        self.assertEqual(first, self.archive.put({**failed, "content": "late rewrite"}))

    def test_old_receipt_cannot_delete_retry_result(self):
        first = self.archive.put(terminal_outcome(self.task))
        second = self.archive.put(terminal_outcome({**self.task, "execution_generation": 2, "error": "new failure"}))
        self.assertFalse(self.archive.acknowledge({**self.fields, **first, "execution_generation": 2}, client_route_id="route"))
        self.assertTrue(self.archive.acknowledge({**self.fields, **first}, client_route_id="route"))
        self.assertEqual("ready", self.page(2)["status"])
        self.assertTrue(self.archive.acknowledge({**self.fields, **second, "execution_generation": 2}, client_route_id="route"))
        self.assertEqual("unavailable", self.page(2)["status"])

    def test_invalid_generation_never_reads_or_acknowledges_a_body(self):
        receipt = self.archive.put(terminal_outcome(self.task))
        for generation in (True, "1", 0, -1, 1.5, 2**53):
            self.assertIsNone(self.page(generation))
            self.assertFalse(self.archive.acknowledge({**self.fields, **receipt, "execution_generation": generation},
                                                       client_route_id="route"))
        self.assertEqual("ready", self.page()["status"])

    def test_generation_is_authenticated_in_page_ciphertext(self):
        self.archive.put(terminal_outcome(self.task))
        self.archive.put(terminal_outcome({**self.task, "execution_generation": 2}))
        with closing(sqlite3.connect(self.archive.path)) as db:
            rows = db.execute("SELECT scope,body FROM pages").fetchall()
            self.assertEqual(2, len(rows))
            db.execute("UPDATE pages SET body=? WHERE scope=?", (rows[0][1], rows[1][0]))
            db.commit()
        with self.assertRaises(Exception):
            self.page(2)

    def test_large_unicode_error_survives_generation_two_reopen(self):
        message = "\u7f51\u7edc\u8fde\u63a5\u5931\u8d25\U0001f310\n" * 5000
        self.archive.put(terminal_outcome({**self.task, "execution_generation": 2, "error": message}))
        self.archive = TaskResultArchive(self.archive.path)
        self.assertGreater(self.page(2)["page_count"], 1)
        self.assertEqual(message, self.stored(2)["content"])

    def test_archive_failure_does_not_suppress_existing_event_delivery(self):
        broken = Mock()
        broken.put.side_effect = OSError("disk unavailable")
        self.assertIsNone(persist_terminal_outcome(self.task, broken))
        self.assertIsNotNone(persist_terminal_outcome(self.task, self.archive))

    def test_missing_callback_is_repaired_from_committed_task(self):
        self.saved_task()
        self.assertEqual("unavailable", self.page()["status"])
        self.assertIsNotNone(self.recover())
        self.assertEqual("original failure", self.stored()["content"])

    def test_backfill_rejects_all_wrong_identity_fields(self):
        self.saved_task()
        for field in IDENTITY_FIELDS:
            self.assertIsNone(self.recover({**self.fields, field: "wrong"}))
        self.assertEqual("unavailable", self.page()["status"])

    def test_backfill_rejects_wrong_generation(self):
        self.saved_task()
        self.assertIsNone(self.recover({**self.fields, "execution_generation": 2}))
        self.assertEqual("unavailable", self.page(2)["status"])

    def test_backfill_cannot_resurrect_acknowledged_payload(self):
        self.saved_task()
        receipt = self.recover()
        self.archive.acknowledge({**self.fields, **receipt}, client_route_id="route")
        self.assertEqual(receipt, self.recover())
        self.assertEqual("unavailable", self.page()["status"])

    def test_retry_between_metadata_and_body_read_is_rejected(self):
        manager = Mock(spec=["recovery_snapshot"])
        manager.recovery_snapshot.side_effect = [self.task, {**self.task, "execution_generation": 2}]
        self.assertIsNone(recover_terminal_outcome(self.fields, client_route_id="route",
                                                  manager=manager, result_archive=self.archive))
        self.assertEqual("unavailable", self.page()["status"])

    def test_observation_does_not_hydrate_large_result_or_live_task(self):
        self.saved_task(result="large output " * 10000)
        self.manager._tasks.clear()
        with patch.object(self.manager._store, "_hydrate_output", side_effect=AssertionError("full result read")):
            result = recovery_query({"request_id": "nonce", "client_route_id": "route", "items": [self.fields]},
                                    client_route_id="route", manager=self.manager)
        self.assertEqual("failed", result["items"][0]["status"])
        self.assertEqual(1, result["items"][0]["execution_generation"])
        self.assertEqual({}, self.manager._tasks)
        self.assertNotIn("result", result["items"][0])

    def test_uncommitted_live_mutation_cannot_replace_committed_error(self):
        task = self.saved_task()
        task.error = "uncommitted mutation"
        task.execution_generation = 2
        self.recover()
        self.assertEqual("original failure", self.stored()["content"])
        self.assertEqual(1, self.stored()["execution_generation"])

    def test_real_process_death_after_terminal_commit_before_archive_callback(self):
        code = """
import os, sys
from pathlib import Path
from agent_task_manager import AgentTaskManager
manager = AgentTaskManager(state_path=Path(sys.argv[1]))
task = manager.create_external(task_id='task', agent_id='codex', contact_id='contact',
    source_message_id='42', prompt='original private prompt', client_route_id='route',
    conversation_id='backend-conversation', client_conversation_id='conversation',
    client_turn_id='turn', on_event=lambda _: None)
manager.update(task.task_id, sys.argv[2], error='committed failure', on_event=lambda _: os._exit(76))
raise AssertionError('terminal callback did not run')
"""
        for status in ("failed", "timed_out", "cancelled"):
            with self.subTest(status=status):
                path = self.root / status / RUN_KERNEL_DATABASE_NAME
                process = subprocess.run([sys.executable, "-c", code, str(path), status],
                                         cwd=Path(__file__).parent, capture_output=True, text=True, timeout=30)
                self.assertEqual(76, process.returncode, process.stderr)
                manager = AgentTaskManager(state_path=path)
                archive = TaskResultArchive(path.parent / "results.db")
                with patch.object(manager, "create", side_effect=AssertionError("re-execution")), \
                        patch.object(manager, "resume", side_effect=AssertionError("re-execution")):
                    self.assertIsNotNone(recover_terminal_outcome(self.fields, client_route_id="route",
                                                                  manager=manager, result_archive=archive))
                page = archive.page({**self.fields, "request_id": "nonce", "page_index": 0}, client_route_id="route")
                payload = json.loads(base64.b64decode(page["data_b64"]))
                self.assertEqual(status, payload["task_status"])
                self.assertEqual("committed failure", payload["content"])

    def test_mqtt_terminal_event_archives_before_publish_failure(self):
        import agent_task_result_archive
        import mqtt_bridge

        def publish(*_):
            self.assertEqual("failed", self.stored()["task_status"])
            raise OSError("connection lost")

        with patch.object(agent_task_result_archive, "archive", self.archive), \
                patch.object(mqtt_bridge, "_publish_or_queue_task_result", side_effect=OSError("queue failure")), \
                patch.object(mqtt_bridge, "_try_publish_task_event", side_effect=publish), \
                patch.object(mqtt_bridge, "pending_task_events", {}):
            self.assertFalse(mqtt_bridge._publish_or_queue_task_event(None,
                {"_client_route_id": "route"}, self.task, []))

    def test_terminal_event_uses_durable_result_channel(self):
        import agent_task_result_archive
        import mqtt_bridge

        with patch.object(agent_task_result_archive, "archive", self.archive), \
                patch.object(mqtt_bridge, "_publish_or_queue_task_result", return_value=False) as result, \
                patch.object(mqtt_bridge, "_try_publish_task_event") as event:
            self.assertFalse(mqtt_bridge._publish_or_queue_task_event(None,
                {"_client_route_id": "route"}, {**self.task, "status": "cancelled"}, []))
            event.assert_not_called()
            self.assertEqual("cancelled", result.call_args.args[2]["task_status"])
            self.assertFalse(result.call_args.args[2]["success"])

    def test_transport_message_id_changes_for_a_new_execution(self):
        import agent_task_result_archive
        import mqtt_bridge

        payload = {**self.fields, "type": "text", "task_status": "completed", "content": "done"}
        with patch.object(agent_task_result_archive, "archive", self.archive), \
                patch.object(mqtt_bridge, "queue_task_result") as queue, \
                patch.object(mqtt_bridge, "_ensure_outbound_retry_thread"), \
                patch.object(mqtt_bridge, "outbound_status", return_value=None):
            for generation in (1, 2):
                mqtt_bridge._publish_or_queue_task_result(None, {"_client_route_id": "route"},
                                                          {**payload, "execution_generation": generation})
        identifiers = [call.args[3]["message_id"] for call in queue.call_args_list]
        self.assertEqual(2, len(set(identifiers)))


if __name__ == "__main__":
    unittest.main()
