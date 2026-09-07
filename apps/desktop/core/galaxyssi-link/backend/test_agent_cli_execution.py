import os
import sys
import tempfile
import unittest
import uuid
from pathlib import Path
from unittest.mock import patch

import agent_gateway
from external_cli_process_pool import ExternalCliProcessPool
from untrusted_evidence import POLICY_MARKER


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

        self.assertTrue(reply.startswith(f"CLI_OK:{POLICY_MARKER}"))
        self.assertIn("GalaxySSI current task:\ntest prompt", reply.replace("\r\n", "\n"))
        self.assertEqual(1, reply.count(POLICY_MARKER))

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

    def test_full_agent_call_prefers_acp_without_invoking_legacy_cli(self):
        class FakeAcpRuntime:
            def supports(self, _agent_id):
                return True

            def execute(self, _agent_id, _prompt, **_kwargs):
                return "Done."

            def agent_health(self, agent_id):
                return {"agent_id": agent_id, "status": "running"}

        with (
            patch("acp_runtime.acp_runtime", return_value=FakeAcpRuntime()),
            patch.object(
                agent_gateway,
                "_ask_agent_sync_inner",
                side_effect=AssertionError("legacy CLI must not run"),
            ),
        ):
            reply = agent_gateway.ask_agent_sync(
                "hermes",
                "Reply with exactly Done.",
                task_id=str(uuid.uuid4()),
            )

        self.assertEqual("Done.", reply)

    def test_selected_claude_model_bypasses_acp_and_reaches_cli_invocation(self):
        class FakeAcpRuntime:
            def supports(self, _agent_id):
                return True

            def execute(self, *_args, **_kwargs):
                raise AssertionError("ACP cannot apply an explicit CLI model")

        with (
            patch("acp_runtime.acp_runtime", return_value=FakeAcpRuntime()),
            patch.object(agent_gateway, "_ask_agent_sync_inner", return_value="Done.") as invoke,
        ):
            result = agent_gateway.deliver_agent_sync(
                "claude",
                "Reply with exactly Done.",
                task_id=str(uuid.uuid4()),
                agent_model_id="best",
            )

        self.assertEqual("Done.", result["reply"])
        self.assertEqual("best", invoke.call_args.kwargs["agent_model_id"])
        self.assertEqual("", invoke.call_args.kwargs["agent_reasoning_effort"])

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

        self.assertTrue(reply.startswith(f"QUOTED_OK:{POLICY_MARKER}"))
        self.assertIn("GalaxySSI current task:\nquoted prompt", reply.replace("\r\n", "\n"))
        self.assertEqual(1, reply.count(POLICY_MARKER))

    def test_restricted_pairing_disables_hermes_yolo_and_marks_workspace_only(self):
        full = agent_gateway._agent_env(agent_gateway.BASE_AGENTS["hermes"])
        restricted = agent_gateway._agent_env(
            agent_gateway.BASE_AGENTS["hermes"],
            restricted_workspace=True,
        )

        self.assertEqual("1", full["HERMES_YOLO_MODE"])
        self.assertNotIn("HERMES_YOLO_MODE", restricted)
        self.assertEqual("restricted", restricted["GALAXYSSI_DESKTOP_ACCESS_PROFILE"])
        self.assertEqual("workspace_only", restricted["GALAXYSSI_AGENT_TOOL_MODE"])

    def test_plan_only_commands_enforce_each_provider_read_only_mode(self):
        codex = agent_gateway._plan_only_command(
            agent_gateway.BASE_AGENTS["codex"],
            list(agent_gateway.BASE_AGENTS["codex"].command or ()),
        )
        claude = agent_gateway._plan_only_command(
            agent_gateway.BASE_AGENTS["claude"],
            list(agent_gateway.BASE_AGENTS["claude"].command or ()),
        )
        hermes = agent_gateway._plan_only_command(
            agent_gateway.BASE_AGENTS["hermes"],
            list(agent_gateway.BASE_AGENTS["hermes"].command or ()),
        )
        openclaw = agent_gateway._plan_only_command(
            agent_gateway.BASE_AGENTS["openclaw"],
            list(agent_gateway.BASE_AGENTS["openclaw"].command or ()),
        )

        self.assertEqual("read-only", codex[codex.index("--sandbox") + 1])
        self.assertNotIn("--ask-for-approval", codex)
        self.assertIn('approval_policy="never"', codex)
        self.assertEqual("plan", claude[claude.index("--permission-mode") + 1])
        self.assertEqual("none", hermes[hermes.index("--toolsets") + 1])
        self.assertEqual("1", hermes[hermes.index("--max-turns") + 1])
        self.assertEqual(
            ["openclaw", "model", "run", "--prompt", "{prompt}"],
            openclaw,
        )

    def test_custom_cli_plan_only_fails_closed(self):
        with self.assertRaisesRegex(RuntimeError, "read-only planning mode"):
            agent_gateway._plan_only_command(
                agent_gateway.BASE_AGENTS["custom-agent"],
                ["custom-agent", "{prompt}"],
            )

    def test_selected_codex_and_claude_models_replace_or_add_model_argument(self):
        codex = agent_gateway._apply_selected_agent_model(
            agent_gateway.BASE_AGENTS["codex"],
            list(agent_gateway.BASE_AGENTS["codex"].command or ()),
            "gpt-5.6-terra",
        )
        claude = agent_gateway._apply_selected_agent_model(
            agent_gateway.BASE_AGENTS["claude"],
            list(agent_gateway.BASE_AGENTS["claude"].command or ()),
            "sonnet[1m]",
        )

        self.assertEqual("gpt-5.6-terra", codex[codex.index("--model") + 1])
        self.assertEqual("sonnet[1m]", claude[claude.index("--model") + 1])

    def test_persistent_jsonl_agent_reuses_keepalive_process(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            startup_log = root / "starts.log"
            script = root / "persistent_cli.py"
            script.write_text(
                "import json, os, sys\n"
                "with open(os.environ['STARTUP_LOG'], 'a', encoding='utf-8') as handle:\n"
                "    handle.write(str(os.getpid()) + '\\n')\n"
                "for line in sys.stdin:\n"
                "    request = json.loads(line)\n"
                "    request_id = request.get('id')\n"
                "    if request.get('method') == 'agent/shutdown':\n"
                "        print(json.dumps({'id': request_id, 'result': {'stopped': True}}), flush=True)\n"
                "        break\n"
                "    prompt = str((request.get('params') or {}).get('prompt') or '')\n"
                "    print(json.dumps({'id': request_id, 'result': {'reply': 'KEEPALIVE:' + prompt}}), flush=True)\n",
                encoding="utf-8",
            )
            command = [sys.executable, str(script), "--serve-jsonl"]
            pool = ExternalCliProcessPool(start_janitor=False)
            old_startup_log = os.environ.get("STARTUP_LOG")
            os.environ["STARTUP_LOG"] = str(startup_log)
            try:
                with patch.object(agent_gateway, "_command_for", return_value=command), patch.object(
                    agent_gateway,
                    "cli_agent_runtime_config",
                    return_value={
                        "enabled": True,
                        "mode": "galaxyssi-jsonl-v1",
                        "pool_size": 1,
                        "prewarm": False,
                    },
                ), patch.object(
                    agent_gateway,
                    "external_cli_process_pool",
                    return_value=pool,
                ), patch.object(
                    agent_gateway,
                    "_state_root",
                    return_value=root,
                ):
                    first = agent_gateway.ask_cli_agent(
                        agent_gateway.BASE_AGENTS["claude"],
                        "one",
                        task_id="task-one",
                    )
                    second = agent_gateway.ask_cli_agent(
                        agent_gateway.BASE_AGENTS["claude"],
                        "two",
                        task_id="task-two",
                    )
            finally:
                pool.shutdown()
                if old_startup_log is None:
                    os.environ.pop("STARTUP_LOG", None)
                else:
                    os.environ["STARTUP_LOG"] = old_startup_log
            starts = startup_log.read_text(encoding="utf-8").splitlines()

        self.assertTrue(first.startswith(f"KEEPALIVE:{POLICY_MARKER}"))
        self.assertIn("GalaxySSI current task:\none", first.replace("\r\n", "\n"))
        self.assertEqual(1, first.count(POLICY_MARKER))
        self.assertTrue(second.startswith(f"KEEPALIVE:{POLICY_MARKER}"))
        self.assertIn("GalaxySSI current task:\ntwo", second.replace("\r\n", "\n"))
        self.assertEqual(1, second.count(POLICY_MARKER))
        self.assertEqual(1, len(starts))

    def test_restricted_access_rejects_persistent_transport_without_starting_process(self):
        with patch.object(
            agent_gateway,
            "cli_agent_runtime_config",
            return_value={
                "enabled": True,
                "mode": "galaxyssi-jsonl-v1",
                "pool_size": 1,
                "prewarm": False,
            },
        ), patch.object(agent_gateway, "external_cli_process_pool") as pool:
            reply = agent_gateway._run_cli_agent_process(
                agent_gateway.BASE_AGENTS["claude"],
                ["fake-agent", "--serve-jsonl"],
                "private request",
                original_text="private request",
                task_id="restricted-task",
                conversation_id="",
                response_language="en",
                restricted_workspace=True,
                retried_stale_session=False,
            )

        self.assertIn("requires desktop executor authorization", reply)
        pool.assert_not_called()

    def test_cli_agent_cannot_replace_host_task_metadata(self):
        with tempfile.TemporaryDirectory() as workspace_root, patch.dict(
            os.environ,
            {
                "GALAXYSSI_WORKSPACE_ROOT": workspace_root,
                "GALAXYSSI_STATE_DIR": str(Path(workspace_root) / "state"),
            },
        ), patch.object(
            agent_gateway,
            "cli_agent_runtime_config",
            return_value={
                "enabled": False,
                "mode": "legacy",
                "pool_size": 1,
                "prewarm": False,
            },
        ):
            reply = agent_gateway._run_cli_agent_process(
                agent_gateway.BASE_AGENTS["claude"],
                [
                    sys.executable,
                    "-c",
                    (
                        "from pathlib import Path;"
                        "Path('.galaxyssi-task.json').write_text("
                        "'{\\\"task_id\\\":\\\"attacker\\\"}', encoding='utf-8');"
                        "print('unsafe success')"
                    ),
                ],
                "write host metadata",
                original_text="write host metadata",
                task_id="protected-cli-task",
                conversation_id="conversation-1",
                response_language="en",
                restricted_workspace=False,
            )
            metadata = (
                Path(workspace_root)
                / "tasks"
                / "protected-cli-task"
                / ".galaxyssi-task.json"
            ).read_text(encoding="utf-8")

        self.assertIn("blocked and rolled back", reply)
        self.assertIn('"task_id": "protected-cli-task"', metadata)
        self.assertNotIn("attacker", metadata)


if __name__ == "__main__":
    unittest.main()
