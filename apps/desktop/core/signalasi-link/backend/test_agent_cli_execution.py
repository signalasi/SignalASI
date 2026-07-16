import sys
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

import agent_gateway


class AgentCliExecutionTest(unittest.TestCase):
    def setUp(self):
        self.runtime_directory = tempfile.TemporaryDirectory()
        self.runtime_patch = patch.object(
            agent_gateway,
            "_agent_runtime_path",
            return_value=Path(self.runtime_directory.name) / "agent-runtime.json",
        )
        self.runtime_patch.start()
        self.execution_log_patch = patch.object(
            agent_gateway,
            "_execution_log_path",
            return_value=Path(self.runtime_directory.name) / "agent-execution.jsonl",
        )
        self.execution_log_patch.start()
        with agent_gateway._agent_runtime_lock:
            agent_gateway._agent_runtime_loaded = True
            agent_gateway._agent_runtime.clear()

    def tearDown(self):
        with agent_gateway._agent_runtime_lock:
            agent_gateway._agent_runtime.clear()
            agent_gateway._agent_runtime_loaded = False
        self.runtime_patch.stop()
        self.execution_log_patch.stop()
        self.runtime_directory.cleanup()

    def test_stdin_cli_command_runs_from_isolated_task_workspace(self):
        with tempfile.TemporaryDirectory() as directory:
            script = Path(directory) / "fake_cli.py"
            script.write_text(
                "import sys\n"
                "assert sys.argv[1:] == ['-']\n"
                "print('CLI_OK:' + sys.stdin.read().strip())\n",
                encoding="utf-8",
            )
            command = [sys.executable, str(script), "-"]
            with patch.object(agent_gateway, "_command_for", return_value=command):
                reply = agent_gateway.ask_cli_agent(
                    agent_gateway.BASE_AGENTS["claude"],
                    "test prompt",
                )

        self.assertEqual("CLI_OK:test prompt", reply)

    def test_full_agent_call_applies_policy_and_returns_cli_reply(self):
        with tempfile.TemporaryDirectory() as directory:
            script = Path(directory) / "fake_cli.py"
            script.write_text(
                "import sys\n"
                "print('CLAUDE_SMOKE_OK:' + sys.stdin.read().strip()[:32])\n",
                encoding="utf-8",
            )
            command = [sys.executable, str(script), "-"]
            with patch.object(agent_gateway, "_command_for", return_value=command):
                reply = agent_gateway.ask_agent_sync("claude", "test prompt")

        self.assertTrue(reply.startswith("CLAUDE_SMOKE_OK:"))

    def test_saved_quoted_windows_command_preserves_executable_and_script_paths(self):
        with tempfile.TemporaryDirectory() as directory:
            script = Path(directory) / "fake cli.py"
            script.write_text(
                "import sys\n"
                "print('QUOTED_OK:' + sys.stdin.read().strip())\n",
                encoding="utf-8",
            )
            saved = f'"{sys.executable.replace(chr(92), "/")}" "{str(script).replace(chr(92), "/")}" -'
            with patch.object(agent_gateway, "command_for", return_value=saved):
                reply = agent_gateway.ask_cli_agent(
                    agent_gateway.BASE_AGENTS["claude"],
                    "quoted prompt",
                )

        self.assertEqual("QUOTED_OK:quoted prompt", reply)


if __name__ == "__main__":
    unittest.main()
