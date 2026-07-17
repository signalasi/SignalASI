import tempfile
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


if __name__ == "__main__":
    unittest.main()
