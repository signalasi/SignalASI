import tempfile
import time
import unittest
from pathlib import Path
from unittest.mock import patch

import codex_app_server


class CodexConversationThreadTests(unittest.TestCase):
    def test_same_conversation_reuses_thread(self):
        with tempfile.TemporaryDirectory() as temporary, patch.object(
            codex_app_server,
            "CONVERSATION_THREADS_PATH",
            Path(temporary) / "threads.json",
        ):
            server = codex_app_server.CodexAppServer("codex", {}, lambda _task, _event: None)
            server._ensure_started = lambda: None
            calls = []

            def request(method, params, timeout):
                calls.append((method, params, timeout))
                if method == "thread/start":
                    return {"thread": {"id": "thread-1"}}
                return {"turn": {"id": f"turn-{len(calls)}"}}

            server._request = request
            first = server.start_task("task-1", "first", temporary, conversation_id="conversation-1")
            first.finished = True
            second = server.start_task("task-2", "second", temporary, conversation_id="conversation-1")

            self.assertEqual(first.thread_id, "thread-1")
            self.assertEqual(second.thread_id, "thread-1")
            self.assertEqual([method for method, _, _ in calls].count("thread/start"), 1)
            self.assertEqual([method for method, _, _ in calls].count("turn/start"), 2)
            turn_inputs = [params["input"][0]["text"] for method, params, _ in calls if method == "turn/start"]
            self.assertIn("first", turn_inputs[0])
            self.assertIn("Do not synthesize replacement media or data.", turn_inputs[0])
            self.assertTrue(server.delete_conversation("conversation-1"))
            self.assertNotIn(server._conversation_key("conversation-1"), server._conversation_threads)

    def test_overlapping_tasks_use_independent_threads(self):
        with tempfile.TemporaryDirectory() as temporary, patch.object(
            codex_app_server,
            "CONVERSATION_THREADS_PATH",
            Path(temporary) / "threads.json",
        ), patch.object(codex_app_server.threading, "Thread") as thread:
            server = codex_app_server.CodexAppServer("codex", {}, lambda _task, _event: None)
            server._ensure_started = lambda: None
            thread_count = 0

            def request(method, params, timeout):
                nonlocal thread_count
                if method == "thread/start":
                    thread_count += 1
                    return {"thread": {"id": f"thread-{thread_count}"}}
                return {"turn": {"id": f"turn-{thread_count}"}}

            server._request = request
            first = server.start_task("task-1", "first", temporary, conversation_id="conversation-1")
            second = server.start_task("task-2", "second", temporary, conversation_id="conversation-1")

            self.assertEqual("thread-1", first.thread_id)
            self.assertEqual("thread-2", second.thread_id)
            self.assertEqual("thread-2", server._conversation_threads[server._conversation_key("conversation-1")])
            self.assertEqual(2, thread_count)
            self.assertEqual(2, thread.call_count)

    def test_missing_persisted_thread_is_recreated(self):
        with tempfile.TemporaryDirectory() as temporary, patch.object(
            codex_app_server,
            "CONVERSATION_THREADS_PATH",
            Path(temporary) / "threads.json",
        ):
            server = codex_app_server.CodexAppServer("codex", {}, lambda _task, _event: None)
            server._ensure_started = lambda: None
            conversation_key = server._conversation_key("conversation-1")
            server._conversation_threads[conversation_key] = "stale-thread"
            calls = []

            def request(method, params, timeout):
                calls.append((method, params, timeout))
                if method == "turn/start" and params["threadId"] == "stale-thread":
                    raise RuntimeError("thread not found: stale-thread")
                if method == "thread/start":
                    return {"thread": {"id": "fresh-thread"}}
                return {"turn": {"id": "fresh-turn"}}

            server._request = request
            run = server.start_task("task-1", "hello", temporary, conversation_id="conversation-1")

            self.assertEqual(run.thread_id, "fresh-thread")
            self.assertEqual(run.turn_id, "fresh-turn")
            self.assertEqual(server._conversation_threads[conversation_key], "fresh-thread")
            self.assertEqual([method for method, _, _ in calls], ["turn/start", "thread/start", "turn/start"])

    def test_local_images_are_sent_as_native_app_server_input(self):
        with tempfile.TemporaryDirectory() as temporary, patch.object(
            codex_app_server,
            "CONVERSATION_THREADS_PATH",
            Path(temporary) / "threads.json",
        ), patch.object(codex_app_server.threading, "Thread"):
            image = Path(temporary) / "homework.jpg"
            image.write_bytes(b"image")
            server = codex_app_server.CodexAppServer("codex", {}, lambda _task, _event: None)
            server._ensure_started = lambda: None
            calls = []

            def request(method, params, timeout):
                calls.append((method, params, timeout))
                if method == "thread/start":
                    return {"thread": {"id": "thread-image"}}
                return {"turn": {"id": "turn-image"}}

            server._request = request
            server.start_task("task-image", "grade this", temporary, image_paths=[str(image)])

            turn = next(params for method, params, _ in calls if method == "turn/start")
            self.assertEqual("text", turn["input"][0]["type"])
            self.assertEqual("localImage", turn["input"][1]["type"])
            self.assertEqual(str(image.resolve()), turn["input"][1]["path"])
            self.assertEqual("original", turn["input"][1]["detail"])

    def test_recover_completed_turn_without_starting_a_duplicate_turn(self):
        with tempfile.TemporaryDirectory() as temporary, patch.object(
            codex_app_server,
            "CONVERSATION_THREADS_PATH",
            Path(temporary) / "threads.json",
        ):
            events = []
            server = codex_app_server.CodexAppServer(
                "codex",
                {},
                lambda task_id, event: events.append((task_id, event)),
            )
            server._ensure_started = lambda: None
            calls = []

            def request(method, params, timeout):
                calls.append((method, params, timeout))
                if method != "thread/resume":
                    raise AssertionError(f"Unexpected recovery method: {method}")
                return {
                    "thread": {
                        "id": "thread-recovered",
                        "status": {"type": "idle"},
                        "turns": [{
                            "id": "turn-recovered",
                            "status": "completed",
                            "items": [{
                                "id": "answer",
                                "type": "agentMessage",
                                "text": "Recovered final answer",
                            }],
                        }],
                    }
                }

            server._request = request
            run = server.recover_task(
                task_id="task-recovered",
                thread_id="thread-recovered",
                turn_id="turn-recovered",
                original_prompt="continue the work",
            )

            self.assertTrue(run.finished)
            self.assertEqual("Recovered final answer", run.final_text)
            self.assertEqual(["thread/resume"], [method for method, _, _ in calls])
            self.assertEqual("completed", events[-1][1]["status"])
            self.assertEqual("Recovered final answer", events[-1][1]["result"])

    def test_recover_in_progress_turn_reconnects_without_replaying_prompt(self):
        with tempfile.TemporaryDirectory() as temporary, patch.object(
            codex_app_server,
            "CONVERSATION_THREADS_PATH",
            Path(temporary) / "threads.json",
        ), patch.object(codex_app_server.threading, "Thread") as thread:
            events = []
            server = codex_app_server.CodexAppServer(
                "codex",
                {},
                lambda task_id, event: events.append((task_id, event)),
            )
            server._ensure_started = lambda: None

            def request(method, params, timeout):
                self.assertEqual("thread/resume", method)
                return {
                    "thread": {
                        "id": "thread-running",
                        "status": {"type": "active", "activeFlags": []},
                        "turns": [{
                            "id": "turn-running",
                            "status": "inProgress",
                            "items": [],
                        }],
                    }
                }

            server._request = request
            run = server.recover_task(
                task_id="task-running",
                thread_id="thread-running",
                turn_id="turn-running",
                original_prompt="\u7ee7\u7eed\u539f\u4efb\u52a1",
                elapsed_seconds=42,
            )

            self.assertFalse(run.finished)
            self.assertEqual("turn-running", run.turn_id)
            self.assertEqual("task-running", server._turn_tasks["turn-running"])
            self.assertGreaterEqual(time.monotonic() - run.started_monotonic, 41)
            self.assertEqual("running", events[-1][1]["status"])
            self.assertEqual("Reconnected to Codex turn", events[-1][1]["current_step"])
            thread.assert_called_once()

    def test_missing_original_turn_fails_without_replaying_prompt(self):
        with tempfile.TemporaryDirectory() as temporary, patch.object(
            codex_app_server,
            "CONVERSATION_THREADS_PATH",
            Path(temporary) / "threads.json",
        ):
            server = codex_app_server.CodexAppServer("codex", {}, lambda _task, _event: None)
            server._ensure_started = lambda: None
            calls = []

            def request(method, params, timeout):
                calls.append(method)
                return {
                    "thread": {
                        "id": "thread-missing",
                        "status": {"type": "idle"},
                        "turns": [{
                            "id": "different-turn",
                            "status": "completed",
                            "items": [],
                        }],
                    }
                }

            server._request = request
            with self.assertRaisesRegex(RuntimeError, "original Codex turn"):
                server.recover_task(
                    task_id="task-missing",
                    thread_id="thread-missing",
                    turn_id="turn-missing",
                    original_prompt="do not replay this",
                )

            self.assertEqual(["thread/resume"], calls)


if __name__ == "__main__":
    unittest.main()
