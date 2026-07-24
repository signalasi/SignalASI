import tempfile
import time
import unittest
from pathlib import Path
from unittest.mock import patch

import codex_app_server


class CodexConversationThreadTests(unittest.TestCase):
    def test_app_server_exposes_visible_tool_events_without_reasoning_content(self):
        events = []
        server = codex_app_server.CodexAppServer(
            "codex",
            {},
            lambda task_id, event: events.append((task_id, event)),
        )
        server._runs["task-1"] = codex_app_server.CodexRun(
            task_id="task-1",
            thread_id="thread-1",
            turn_id="turn-1",
        )
        server._turn_tasks["turn-1"] = "task-1"

        server._handle_event({
            "method": "item/started",
            "params": {
                "turnId": "turn-1",
                "item": {
                    "id": "command-1",
                    "type": "commandExecution",
                    "command": ["python", "verify.py"],
                },
            },
        })
        server._handle_event({
            "method": "item/started",
            "params": {
                "turnId": "turn-1",
                "item": {
                    "id": "reasoning-1",
                    "type": "reasoning",
                    "text": "private internal reasoning must not leave the server",
                },
            },
        })

        command = events[0][1]
        reasoning = events[1][1]
        self.assertEqual("command", command["event_kind"])
        self.assertEqual("python verify.py", command["event_detail"])
        self.assertEqual("reasoning", reasoning["event_kind"])
        self.assertEqual("", reasoning["event_detail"])
        self.assertNotIn("private internal reasoning", str(reasoning))

    @staticmethod
    def _event_server():
        events = []
        server = codex_app_server.CodexAppServer(
            "codex",
            {},
            lambda task_id, event: events.append((task_id, event)),
        )
        run = codex_app_server.CodexRun(
            task_id="task-visible",
            thread_id="thread-visible",
            turn_id="turn-visible",
        )
        server._runs[run.task_id] = run
        server._turn_tasks[run.turn_id] = run.task_id
        return server, run, events

    def test_visible_codex_progress_preserves_commentary_reasoning_and_image_events(self):
        server, run, events = self._event_server()

        server._handle_event({
            "method": "item/completed",
            "params": {
                "threadId": run.thread_id,
                "turnId": run.turn_id,
                "item": {
                    "id": "commentary-1",
                    "type": "agentMessage",
                    "phase": "commentary",
                    "text": "I will inspect each answer and flag uncertain regions.",
                },
            },
        })
        server._handle_event({
            "method": "item/completed",
            "params": {
                "threadId": run.thread_id,
                "turnId": run.turn_id,
                "item": {
                    "id": "reasoning-1",
                    "type": "reasoning",
                    "summary": ["Comparing the written answers with the worksheet prompts."],
                    "content": ["private reasoning must not be forwarded"],
                },
            },
        })
        for method in ("item/started", "item/completed"):
            server._handle_event({
                "method": method,
                "params": {
                    "threadId": run.thread_id,
                    "turnId": run.turn_id,
                    "item": {"id": "image-1", "type": "imageView", "path": "homework.jpg"},
                },
            })
        server._handle_event({
            "method": "item/completed",
            "params": {
                "threadId": run.thread_id,
                "turnId": run.turn_id,
                "item": {
                    "id": "answer-1",
                    "type": "agentMessage",
                    "phase": "final_answer",
                    "text": "The worksheet is mostly correct.",
                },
            },
        })
        server._handle_event({
            "method": "turn/completed",
            "params": {
                "threadId": run.thread_id,
                "turnId": run.turn_id,
                "turn": {"id": run.turn_id, "status": "completed"},
            },
        })

        progress = [event["progress_event"] for _, event in events if "progress_event" in event]
        self.assertEqual(
            ["commentary", "reasoning_summary", "image_view", "image_view"],
            [event["code"] for event in progress],
        )
        self.assertEqual(["running", "completed"], [event["status"] for event in progress[-2:]])
        self.assertNotIn("private reasoning", " ".join(event["detail"] for event in progress))
        self.assertEqual("completed", events[-1][1]["status"])
        self.assertEqual("The worksheet is mostly correct.", events[-1][1]["result"])

    def test_reasoning_summary_delta_is_used_without_forwarding_reasoning_text_delta(self):
        server, run, events = self._event_server()

        server._handle_event({
            "method": "item/reasoning/summaryTextDelta",
            "params": {
                "threadId": run.thread_id,
                "turnId": run.turn_id,
                "itemId": "reasoning-2",
                "summaryIndex": 0,
                "delta": "Checking the visible worksheet fields.",
            },
        })
        server._handle_event({
            "method": "item/reasoning/textDelta",
            "params": {
                "threadId": run.thread_id,
                "turnId": run.turn_id,
                "itemId": "reasoning-2",
                "contentIndex": 0,
                "delta": "hidden chain of thought",
            },
        })
        server._handle_event({
            "method": "item/completed",
            "params": {
                "threadId": run.thread_id,
                "turnId": run.turn_id,
                "item": {"id": "reasoning-2", "type": "reasoning", "summary": [], "content": []},
            },
        })

        progress = [event["progress_event"] for _, event in events if "progress_event" in event]
        self.assertEqual(1, len(progress))
        self.assertEqual("Checking the visible worksheet fields.", progress[0]["detail"])
        self.assertNotIn("hidden chain", progress[0]["detail"])

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

    def test_overlapping_tasks_are_steered_without_creating_a_branch(self):
        with tempfile.TemporaryDirectory() as temporary, patch.object(
            codex_app_server,
            "CONVERSATION_THREADS_PATH",
            Path(temporary) / "threads.json",
        ), patch.object(codex_app_server.threading, "Thread") as thread:
            server = codex_app_server.CodexAppServer("codex", {}, lambda _task, _event: None)
            server._ensure_started = lambda: None
            thread_count = 0
            calls = []

            def request(method, params, timeout):
                nonlocal thread_count
                calls.append((method, params, timeout))
                if method == "thread/start":
                    thread_count += 1
                    return {"thread": {"id": f"thread-{thread_count}"}}
                return {"turn": {"id": f"turn-{thread_count}"}}

            server._request = request
            first = server.start_task("task-1", "first", temporary, conversation_id="conversation-1")
            with self.assertRaises(codex_app_server.CodexConversationBusyError) as raised:
                server.start_task("task-2", "second", temporary, conversation_id="conversation-1")
            steered = server.steer_task(raised.exception.active_task_id, "be exact")

            self.assertIs(first, steered)
            self.assertEqual("thread-1", first.thread_id)
            self.assertEqual("thread-1", server._conversation_threads[server._conversation_key("conversation-1")])
            self.assertEqual(1, thread_count)
            self.assertEqual(1, thread.call_count)
            steer = next(params for method, params, _ in calls if method == "turn/steer")
            self.assertEqual("thread-1", steer["threadId"])
            self.assertEqual(first.turn_id, steer["expectedTurnId"])
            self.assertIn("be exact", steer["input"][0]["text"])
            self.assertNotIn(codex_app_server.CODEX_TASK_POLICY, steer["input"][0]["text"])

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
