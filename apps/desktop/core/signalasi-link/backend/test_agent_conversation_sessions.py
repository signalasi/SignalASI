import json
import os
import sys
import tempfile
import threading
import time
import unittest
from pathlib import Path
from unittest.mock import patch

import agent_conversation_sessions
import agent_gateway
import agent_task_manager


class AgentConversationSessionsTest(unittest.TestCase):
    def test_session_binding_persists_cursor_and_deletes_by_conversation(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "sessions.json"
            store = agent_conversation_sessions.AgentConversationSessions(path)
            first = store.ensure("claude", "conversation-a")
            store.ensure("openclaw", "conversation-a")
            store.ensure("claude", "conversation-b")
            store.mark_synced(
                "claude",
                "conversation-a",
                through_created_at_millis=1234,
                through_task_id="task-a",
                synced_turn_ids=("turn-a",),
                synced_entry_ids=("entry-a",),
                summary_digest="summary-a",
            )

            restored = agent_conversation_sessions.AgentConversationSessions(path)
            binding = restored.get("claude", "conversation-a")
            self.assertEqual(first.session_id, binding.session_id)
            self.assertEqual((1234, "task-a"), binding.cursor)
            self.assertEqual(("turn-a",), binding.synced_turn_ids)
            self.assertEqual(("entry-a",), binding.synced_entry_ids)
            self.assertEqual("summary-a", binding.summary_digest)
            self.assertEqual(2, restored.delete_conversation("conversation-a"))
            self.assertFalse(restored.get("claude", "conversation-a").session_id)
            self.assertTrue(restored.get("claude", "conversation-b").session_id)

    def test_same_conversation_lock_serializes_but_other_conversations_run(self):
        with tempfile.TemporaryDirectory() as directory:
            store = agent_conversation_sessions.AgentConversationSessions(
                Path(directory) / "sessions.json"
            )
            first_lock = store.conversation_lock("local-llm", "conversation-a")
            same_lock = store.conversation_lock("local-llm", "conversation-a")
            other_lock = store.conversation_lock("local-llm", "conversation-b")
            self.assertIs(first_lock, same_lock)
            self.assertIsNot(first_lock, other_lock)

            entered = threading.Event()
            released = threading.Event()

            def wait_for_same_lock():
                with same_lock:
                    entered.set()
                released.set()

            with first_lock:
                thread = threading.Thread(target=wait_for_same_lock)
                thread.start()
                time.sleep(0.03)
                self.assertFalse(entered.is_set())
                self.assertTrue(other_lock.acquire(blocking=False))
                other_lock.release()
            thread.join(timeout=1)
            self.assertTrue(released.is_set())


class NativeCliConversationTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.root = Path(self.temporary.name)
        self.store = agent_conversation_sessions.AgentConversationSessions(
            self.root / "sessions.json"
        )
        self.session_patch = patch.object(
            agent_conversation_sessions,
            "_sessions",
            self.store,
        )
        self.session_patch.start()
        self.environment_patch = patch.dict(
            os.environ,
            {"SIGNALASI_WORKSPACE_ROOT": str(self.root / "workspaces")},
        )
        self.environment_patch.start()

    def tearDown(self):
        self.environment_patch.stop()
        self.session_patch.stop()
        self.temporary.cleanup()

    def _recording_script(self) -> tuple[Path, Path]:
        log_path = self.root / "calls.jsonl"
        script = self.root / "record_cli.py"
        script.write_text(
            "import json, pathlib, sys\n"
            "log = pathlib.Path(sys.argv[1])\n"
            "with log.open('a', encoding='utf-8') as handle:\n"
            "    handle.write(json.dumps({'args': sys.argv[2:], 'stdin': sys.stdin.read()}) + '\\n')\n"
            "print('native reply')\n"
            "if '--hermes-test' in sys.argv:\n"
            "    print('session_id: hermes-session-1', file=sys.stderr)\n",
            encoding="utf-8",
        )
        return script, log_path

    def test_hermes_captures_session_then_resumes_same_conversation(self):
        script, log_path = self._recording_script()
        command = [
            sys.executable,
            str(script),
            str(log_path),
            "--hermes-test",
            "{prompt}",
        ]
        with patch.object(agent_gateway, "_command_for", return_value=command), patch.object(
            agent_task_manager.agent_task_manager,
            "conversation_messages",
            return_value=[],
        ):
            first = agent_gateway.ask_cli_agent(
                agent_gateway.BASE_AGENTS["hermes"],
                "first request",
                conversation_id="conversation-hermes",
            )
            second = agent_gateway.ask_cli_agent(
                agent_gateway.BASE_AGENTS["hermes"],
                "follow-up request",
                conversation_id="conversation-hermes",
            )

        calls = [json.loads(line) for line in log_path.read_text(encoding="utf-8").splitlines()]
        self.assertEqual("native reply", first)
        self.assertEqual("native reply", second)
        self.assertNotIn("--resume", calls[0]["args"])
        self.assertEqual(
            ["--resume", "hermes-session-1"],
            calls[1]["args"][-2:],
        )

    def test_claude_uses_new_session_id_then_resume(self):
        script, log_path = self._recording_script()
        command = [sys.executable, str(script), str(log_path)]
        with patch.object(agent_gateway, "_command_for", return_value=command), patch.object(
            agent_task_manager.agent_task_manager,
            "conversation_messages",
            return_value=[],
        ):
            agent_gateway.ask_cli_agent(
                agent_gateway.BASE_AGENTS["claude"],
                "first request",
                conversation_id="conversation-claude",
            )
            agent_gateway.ask_cli_agent(
                agent_gateway.BASE_AGENTS["claude"],
                "follow-up request",
                conversation_id="conversation-claude",
            )

        calls = [json.loads(line) for line in log_path.read_text(encoding="utf-8").splitlines()]
        session_id = calls[0]["args"][calls[0]["args"].index("--session-id") + 1]
        self.assertEqual(["--resume", session_id], calls[1]["args"][-2:])
        self.assertIn("Current user request:\nfirst request", calls[0]["stdin"])
        self.assertIn("follow-up request", calls[1]["stdin"])

    def test_openclaw_reuses_explicit_session_id(self):
        script, log_path = self._recording_script()
        command = [sys.executable, str(script), str(log_path), "{prompt}"]
        with patch.object(agent_gateway, "_command_for", return_value=command), patch.object(
            agent_task_manager.agent_task_manager,
            "conversation_messages",
            return_value=[],
        ):
            agent_gateway.ask_cli_agent(
                agent_gateway.BASE_AGENTS["openclaw"],
                "first request",
                conversation_id="conversation-openclaw",
            )
            agent_gateway.ask_cli_agent(
                agent_gateway.BASE_AGENTS["openclaw"],
                "follow-up request",
                conversation_id="conversation-openclaw",
            )

        calls = [json.loads(line) for line in log_path.read_text(encoding="utf-8").splitlines()]
        first_session = calls[0]["args"][calls[0]["args"].index("--session-id") + 1]
        second_session = calls[1]["args"][calls[1]["args"].index("--session-id") + 1]
        self.assertEqual(first_session, second_session)

    def test_native_session_receives_only_missing_cross_provider_turns(self):
        history = [
            {
                "task_id": "cloud-task",
                "created_at": 200,
                "prompt": "Check the external evidence",
                "result": "The external evidence was verified",
                "status": "completed",
                "agent_id": "cloud-model",
            }
        ]
        with patch.object(
            agent_task_manager.agent_task_manager,
            "conversation_messages",
            return_value=history,
        ):
            prompt = agent_gateway._native_incremental_cli_prompt(
                agent_gateway.BASE_AGENTS["hermes"],
                "Continue with that result",
                "current-task",
                "conversation-switch",
                after_cursor=(100, "previous-hermes-task"),
            )

        self.assertIn("Check the external evidence", prompt)
        self.assertIn("The external evidence was verified", prompt)
        self.assertIn("Current user request:\nContinue with that result", prompt)

    def test_native_session_skips_synced_phone_turns_and_hands_off_new_provider_turn(self):
        mobile_payload = {
            "version": 1,
            "conversation_id": "conversation-switch",
            "summary": "",
            "turns": [
                {
                    "entry_id": "entry-u1",
                    "turn_id": "turn-native",
                    "role": "user",
                    "content": "Original native request",
                },
                {
                    "entry_id": "entry-a1",
                    "turn_id": "turn-native",
                    "role": "assistant",
                    "content": "Original native answer",
                },
                {
                    "entry_id": "entry-u2",
                    "turn_id": "turn-cloud",
                    "role": "user",
                    "content": "Cloud follow-up",
                },
                {
                    "entry_id": "entry-a2",
                    "turn_id": "turn-cloud",
                    "role": "assistant",
                    "content": "Cloud follow-up answer",
                },
            ],
        }
        raw_prompt = (
            "[SIGNALASI_CONVERSATION_CONTEXT_V1]\n"
            f"{json.dumps(mobile_payload)}\n"
            "[/SIGNALASI_CONVERSATION_CONTEXT_V1]\n\n"
            "Current user request:\nContinue in the native Agent"
        )
        with patch.object(
            agent_task_manager.agent_task_manager,
            "conversation_messages",
            return_value=[],
        ):
            prompt = agent_gateway._native_incremental_cli_prompt(
                agent_gateway.BASE_AGENTS["hermes"],
                raw_prompt,
                "current-task",
                "conversation-switch",
                after_cursor=(0, ""),
                synced_turn_ids=("turn-native",),
            )

        self.assertNotIn("Original native request", prompt)
        self.assertNotIn("Original native answer", prompt)
        self.assertIn("Cloud follow-up", prompt)
        self.assertIn("Cloud follow-up answer", prompt)
        self.assertIn("Current user request:\nContinue in the native Agent", prompt)


if __name__ == "__main__":
    unittest.main()
