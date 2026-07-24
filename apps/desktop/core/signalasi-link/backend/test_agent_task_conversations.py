import tempfile
import threading
import time
import unittest
from pathlib import Path
from unittest.mock import patch

import agent_task_manager


class AgentTaskConversationTests(unittest.TestCase):
    def test_terminal_tasks_clear_stale_running_step(self):
        with tempfile.TemporaryDirectory() as temporary, patch.object(
            agent_task_manager, "TASKS_DB_PATH", Path(temporary) / "tasks.sqlite3"
        ):
            manager = agent_task_manager.AgentTaskManager()
            terminal = threading.Event()

            def run(task):
                manager.update(task.task_id, "running", current_step="Running codex")
                return "done"

            task = manager.create(
                "codex",
                "codex-contact",
                "desktop:1",
                "reply exactly",
                run,
                lambda event: terminal.set() if event["status"] in agent_task_manager.TERMINAL_STATES else None,
            )

            self.assertTrue(terminal.wait(3))
            settled = manager.get(task.task_id)
            self.assertEqual("completed", settled.status)
            self.assertEqual("", settled.current_step)

            restored = agent_task_manager.AgentTaskManager().get(task.task_id)
            self.assertEqual("", restored.current_step)

    def test_external_terminal_update_clears_current_step(self):
        with tempfile.TemporaryDirectory() as temporary, patch.object(
            agent_task_manager, "TASKS_DB_PATH", Path(temporary) / "tasks.sqlite3"
        ):
            manager = agent_task_manager.AgentTaskManager()
            manager.create_external(
                "codex",
                "codex-contact",
                "desktop:2",
                "external task",
                lambda _: None,
                task_id="external-task",
            )
            manager.update("external-task", "running", current_step="Codex is working")
            manager.update("external-task", "completed", result="done")

            settled = manager.get("external-task")
            self.assertEqual("completed", settled.status)
            self.assertEqual("", settled.current_step)

    def test_stable_progress_event_updates_in_place_and_remains_latest(self):
        with tempfile.TemporaryDirectory() as temporary, patch.object(
            agent_task_manager, "TASKS_DB_PATH", Path(temporary) / "tasks.sqlite3"
        ):
            events = []
            manager = agent_task_manager.AgentTaskManager()
            manager.create_external(
                "codex",
                "codex-contact",
                "desktop:progress",
                "inspect image",
                events.append,
                task_id="progress-task",
            )

            manager.add_event(
                "progress-task",
                "tool",
                "Viewing image",
                event_id="codex:image_view:image-1",
                status="running",
                metadata={"code": "image_view", "count": 1},
                on_event=events.append,
            )
            manager.add_event(
                "progress-task",
                "narration",
                "Checking answers",
                event_id="codex:commentary:message-1",
                detail="Checking answers against the worksheet.",
                on_event=events.append,
            )
            updated = manager.add_event(
                "progress-task",
                "tool",
                "Viewed image",
                event_id="codex:image_view:image-1",
                status="completed",
                metadata={"code": "image_view", "count": 1},
                on_event=events.append,
            )

            self.assertEqual("running", updated.status)
            self.assertEqual(2, len(updated.events))
            self.assertEqual("codex:image_view:image-1", updated.events[-1]["event_id"])
            self.assertEqual("completed", updated.events[-1]["status"])
            self.assertGreaterEqual(updated.events[-1]["updated_at"], updated.events[-1]["created_at"])
            restored = agent_task_manager.AgentTaskManager().get("progress-task")
            self.assertEqual("completed", restored.events[-1]["status"])

    def test_external_running_task_emits_heartbeats_until_terminal(self):
        with tempfile.TemporaryDirectory() as temporary, patch.object(
            agent_task_manager, "TASKS_DB_PATH", Path(temporary) / "tasks.sqlite3"
        ):
            events = []
            second_heartbeat = threading.Event()

            def capture(event):
                events.append(dict(event))
                if event["status"] == "running" and event["status_seq"] >= 3:
                    second_heartbeat.set()

            manager = agent_task_manager.AgentTaskManager(heartbeat_interval_seconds=0.02)
            manager.create_external(
                "codex", "codex-contact", "desktop:heartbeat", "external task",
                capture, task_id="external-heartbeat",
            )
            manager.update("external-heartbeat", "running", on_event=capture, current_step="Running command")

            self.assertTrue(second_heartbeat.wait(1))
            running_events = [event for event in events if event["status"] == "running"]
            self.assertGreaterEqual(len(running_events), 3)
            self.assertEqual("Running command", running_events[-1]["current_step"])

            completed = manager.update(
                "external-heartbeat", "completed", on_event=capture, result="done"
            )
            terminal_seq = completed.status_seq
            time.sleep(0.08)

            self.assertEqual("completed", manager.get("external-heartbeat").status)
            self.assertEqual(terminal_seq, manager.get("external-heartbeat").status_seq)

    def test_external_heartbeat_pauses_for_input_and_resumes_without_duplicate_threads(self):
        with tempfile.TemporaryDirectory() as temporary, patch.object(
            agent_task_manager, "TASKS_DB_PATH", Path(temporary) / "tasks.sqlite3"
        ):
            events = []
            manager = agent_task_manager.AgentTaskManager(heartbeat_interval_seconds=0.02)
            manager.create_external(
                "codex", "codex-contact", "desktop:waiting", "external task",
                events.append, task_id="external-waiting",
            )
            manager.update("external-waiting", "running", on_event=events.append)
            time.sleep(0.05)

            waiting = manager.update(
                "external-waiting", "waiting_input", on_event=events.append,
                current_step="Waiting for input",
            )
            waiting_seq = waiting.status_seq
            time.sleep(0.06)
            self.assertEqual(waiting_seq, manager.get("external-waiting").status_seq)

            resumed = manager.update(
                "external-waiting", "running", on_event=events.append,
                current_step="Continuing",
            )
            resumed_seq = resumed.status_seq
            deadline = time.time() + 1
            while manager.get("external-waiting").status_seq <= resumed_seq and time.time() < deadline:
                time.sleep(0.01)

            self.assertGreater(manager.get("external-waiting").status_seq, resumed_seq)
            manager.update("external-waiting", "cancelled", on_event=events.append)

    def test_hung_runner_times_out_once_and_discards_late_result(self):
        with tempfile.TemporaryDirectory() as temporary, patch.object(
            agent_task_manager, "TASKS_DB_PATH", Path(temporary) / "tasks.sqlite3"
        ):
            release = threading.Event()
            terminal = threading.Event()
            events = []
            results = []

            class RegisteredProcess:
                pid = 123

                @staticmethod
                def poll():
                    return None

            registered_process = RegisteredProcess()
            manager = agent_task_manager.AgentTaskManager(
                heartbeat_interval_seconds=0.01,
                task_timeout_seconds=0.05,
            )

            def run(running_task):
                manager.register_process(running_task.task_id, registered_process)
                release.wait(1)
                return "late result"

            def capture(event):
                events.append(dict(event))
                if event["status"] in agent_task_manager.TERMINAL_STATES:
                    terminal.set()

            with patch.object(manager, "_terminate") as terminate:
                task = manager.create(
                    "hermes",
                    "hermes-contact",
                    "desktop:timeout",
                    "run a task",
                    run,
                    capture,
                    on_result=lambda event: results.append(dict(event)),
                )

                self.assertTrue(terminal.wait(1))
                timed_out = manager.get(task.task_id)
                self.assertEqual("timed_out", timed_out.status)
                self.assertIn("execution time limit", timed_out.result)
                self.assertEqual("", timed_out.current_step)
                terminal_seq = timed_out.status_seq
                terminate.assert_called_once_with(registered_process)

                release.set()
                time.sleep(0.08)

                settled = manager.get(task.task_id)
                self.assertEqual("timed_out", settled.status)
                self.assertEqual(terminal_seq, settled.status_seq)
                self.assertNotEqual("late result", settled.result)
                self.assertEqual(1, len(results))
                self.assertEqual("timed_out", results[0]["status"])
                terminal_events = [
                    event for event in events
                    if event["status"] in agent_task_manager.TERMINAL_STATES
                ]
                self.assertEqual(1, len(terminal_events))

                restored = agent_task_manager.AgentTaskManager(
                    task_timeout_seconds=0.05
                ).get(task.task_id)
                self.assertEqual("timed_out", restored.status)

    def test_task_completion_before_deadline_does_not_emit_timeout(self):
        with tempfile.TemporaryDirectory() as temporary, patch.object(
            agent_task_manager, "TASKS_DB_PATH", Path(temporary) / "tasks.sqlite3"
        ):
            terminal = threading.Event()
            events = []
            manager = agent_task_manager.AgentTaskManager(
                heartbeat_interval_seconds=0.01,
                task_timeout_seconds=0.2,
            )

            def capture(event):
                events.append(dict(event))
                if event["status"] in agent_task_manager.TERMINAL_STATES:
                    terminal.set()

            task = manager.create(
                "hermes",
                "hermes-contact",
                "desktop:complete",
                "run a task",
                lambda _task: "done",
                capture,
            )

            self.assertTrue(terminal.wait(1))
            time.sleep(0.25)
            settled = manager.get(task.task_id)
            self.assertEqual("completed", settled.status)
            self.assertEqual("done", settled.result)
            self.assertNotIn("timed_out", [event["status"] for event in events])

    def test_delete_conversation_removes_only_matching_tasks(self):
        with tempfile.TemporaryDirectory() as temporary, patch.object(
            agent_task_manager, "TASKS_DB_PATH", Path(temporary) / "tasks.sqlite3"
        ):
            manager = agent_task_manager.AgentTaskManager()
            first = manager.create_external(
                "codex", "codex-contact", "1", "first", lambda _: None,
                task_id="task-1", conversation_id="conversation-1", client_route_id="client-1",
                client_turn_id="phone-turn-1",
            )
            manager.create_external(
                "hermes", "hermes-contact", "2", "second", lambda _: None,
                task_id="task-2", conversation_id="conversation-2",
            )
            self.assertEqual(first.public()["conversation_id"], "conversation-1")
            self.assertEqual(first.public()["client_route_id"], "client-1")
            self.assertEqual(first.public()["client_turn_id"], "phone-turn-1")
            restored = agent_task_manager.AgentTaskManager().get("task-1")
            self.assertIsNotNone(restored)
            self.assertEqual(restored.client_route_id, "client-1")
            self.assertEqual(restored.client_turn_id, "phone-turn-1")
            self.assertEqual(manager.delete_conversation("conversation-1"), ["task-1"])
            self.assertIsNone(manager.get("task-1"))
            self.assertIsNotNone(manager.get("task-2"))

    def test_restart_recovers_only_tasks_that_were_interrupted(self):
        with tempfile.TemporaryDirectory() as temporary, patch.object(
            agent_task_manager, "TASKS_DB_PATH", Path(temporary) / "tasks.sqlite3"
        ):
            manager = agent_task_manager.AgentTaskManager()
            manager.create_external(
                "codex", "codex-contact", "1", "finished", lambda _: None,
                task_id="finished-task", client_route_id="client-1",
            )
            manager.update("finished-task", "completed", result="done")
            manager.create_external(
                "codex", "codex-contact", "2", "running", lambda _: None,
                task_id="running-task", client_route_id="client-1",
            )
            manager.update("running-task", "running")
            manager.update(
                "running-task",
                "running",
                thread_id="thread-running",
                turn_id="turn-running",
            )

            restored = agent_task_manager.AgentTaskManager()
            recovered = restored.drain_recovered()

            self.assertEqual(["running-task"], [item["task_id"] for item in recovered])
            self.assertEqual("recovering", recovered[0]["status"])
            self.assertEqual("running", recovered[0]["prompt"])
            self.assertEqual(2, recovered[0]["attempt"])
            self.assertEqual([], restored.drain_recovered())

            events = []
            resumed = restored.resume_external("running-task", events.append)
            self.assertIsNotNone(resumed)
            self.assertEqual("accepted", resumed.status)
            self.assertEqual("thread-running", resumed.thread_id)
            self.assertEqual("turn-running", resumed.turn_id)
            restored.update("running-task", "completed", result="recovered")
            self.assertEqual("recovered", restored.get("running-task").result)

    def test_second_interruption_stops_automatic_recovery(self):
        with tempfile.TemporaryDirectory() as temporary, patch.object(
            agent_task_manager, "TASKS_DB_PATH", Path(temporary) / "tasks.sqlite3"
        ):
            manager = agent_task_manager.AgentTaskManager()
            manager.create_external(
                "codex", "codex-contact", "3", "repeat", lambda _: None,
                task_id="repeat-task", client_route_id="client-1",
            )
            manager.update("repeat-task", "running")
            first_restart = agent_task_manager.AgentTaskManager()
            first_restart.drain_recovered()
            first_restart.resume_external("repeat-task", lambda _: None)
            first_restart.update("repeat-task", "running")

            second_restart = agent_task_manager.AgentTaskManager()
            recovered = second_restart.drain_recovered()

            self.assertEqual("failed", recovered[0]["status"])
            self.assertIn("repeated Desktop restarts", recovered[0]["error"])
            self.assertIsNone(second_restart.resume_external("repeat-task", lambda _: None))

    def test_client_turn_id_survives_codex_internal_turn_updates(self):
        with tempfile.TemporaryDirectory() as temporary, patch.object(
            agent_task_manager, "TASKS_DB_PATH", Path(temporary) / "tasks.sqlite3"
        ):
            manager = agent_task_manager.AgentTaskManager()
            manager.create_external(
                "codex", "codex-contact", "1", "prompt", lambda _: None,
                task_id="task-1", client_turn_id="phone-turn-1",
            )

            manager.update("task-1", "running", turn_id="codex-turn-1")
            task = manager.get("task-1").public()

            self.assertEqual(task["client_turn_id"], "phone-turn-1")
            self.assertEqual(task["turn_id"], "codex-turn-1")


if __name__ == "__main__":
    unittest.main()
