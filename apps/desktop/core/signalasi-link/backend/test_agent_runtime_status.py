import unittest
import tempfile
from pathlib import Path
from unittest.mock import patch

import agent_gateway


class AgentRuntimeStatusTest(unittest.TestCase):
    def setUp(self):
        self.runtime_dir = tempfile.TemporaryDirectory()
        self.path_patch = patch.object(
            agent_gateway,
            "_agent_runtime_path",
            return_value=Path(self.runtime_dir.name) / "agent-runtime.json",
        )
        self.path_patch.start()
        with agent_gateway._agent_runtime_lock:
            agent_gateway._agent_runtime_loaded = True
            agent_gateway._agent_runtime.clear()

    def tearDown(self):
        with agent_gateway._agent_runtime_lock:
            agent_gateway._agent_runtime.clear()
            agent_gateway._agent_runtime_loaded = False
        self.path_patch.stop()
        self.runtime_dir.cleanup()

    @patch.object(agent_gateway, "_command_available", return_value=(True, "codex-cli test"))
    def test_runtime_transitions_override_installed_command_status(self, _available):
        spec = agent_gateway.BASE_AGENTS["codex"]
        self.assertEqual("ready", agent_gateway.agent_status(spec)["status"])

        agent_gateway._agent_execution_started("codex")
        busy = agent_gateway.agent_status(spec)
        self.assertEqual("busy", busy["status"])
        self.assertEqual(1, busy["active_tasks"])

        agent_gateway._agent_execution_finished("codex", False, "runtime failure")
        unavailable = agent_gateway.agent_status(spec)
        self.assertEqual("unavailable", unavailable["status"])
        self.assertEqual(0, unavailable["active_tasks"])

        with agent_gateway._agent_runtime_lock:
            agent_gateway._agent_runtime["codex"]["unavailable_until"] = 0
        self.assertEqual("degraded", agent_gateway.agent_status(spec)["status"])

        agent_gateway._agent_execution_started("codex")
        agent_gateway._agent_execution_finished("codex", True, "ready")
        self.assertEqual("ready", agent_gateway.agent_status(spec)["status"])

    @patch.object(agent_gateway, "_command_for", return_value=["C:/tools/codex.exe"])
    @patch.object(agent_gateway.Path, "is_file", return_value=True)
    def test_quick_status_does_not_launch_or_probe_agent(self, _is_file, _command):
        with patch.object(agent_gateway, "_command_available") as deep_probe:
            status = agent_gateway.agent_status(agent_gateway.BASE_AGENTS["codex"], quick=True)
        self.assertEqual("ready", status["status"])
        deep_probe.assert_not_called()

    @patch.object(agent_gateway, "_local_model_available", return_value=(False, "Local model API is offline"))
    def test_quick_local_model_status_requires_a_reachable_runtime(self, _available):
        status = agent_gateway.agent_status(agent_gateway.BASE_AGENTS["local-llm"], quick=True)
        self.assertEqual("needs_setup", status["status"])
        self.assertIn("offline", status["detail"])

    @patch.object(agent_gateway, "_command_available", return_value=(True, "ollama version test"))
    @patch.object(agent_gateway, "_ollama_api_available", return_value=(False, "API offline"))
    def test_installed_ollama_without_api_is_not_routable(self, _api, _command):
        ready, detail = agent_gateway._ollama_available()
        self.assertFalse(ready)
        self.assertIn("not reachable", detail)

    def test_known_connector_error_replies_are_failures(self):
        self.assertTrue(agent_gateway._agent_reply_failed("[Local LLM] not connected"))
        self.assertTrue(agent_gateway._agent_reply_failed("[Claude Code] \u672a\u68c0\u6d4b\u5230\u547d\u4ee4"))
        self.assertFalse(agent_gateway._agent_reply_failed("A concise normal reply."))

    def test_config_change_clears_inactive_quarantine_but_preserves_running_tasks(self):
        agent_gateway._agent_execution_started("codex")
        agent_gateway._agent_execution_started("claude")
        agent_gateway._agent_execution_finished("claude", False, "old command failed")

        agent_gateway.reset_inactive_agent_runtime()

        self.assertIn("codex", agent_gateway._agent_runtime)
        self.assertNotIn("claude", agent_gateway._agent_runtime)


if __name__ == "__main__":
    unittest.main()
