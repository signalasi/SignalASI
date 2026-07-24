import json
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

import agent_gateway
import agent_config
import agent_task_manager
import conversation_context
from desktop_agent_adapters import AgentAdapterRequest


class AgentContextCompactionTest(unittest.TestCase):
    def test_stateless_model_receives_phone_context_after_provider_switch(self):
        mobile_payload = {
            "version": 1,
            "conversation_id": "conversation-switch",
            "summary": "",
            "turns": [
                {
                    "entry_id": "u1",
                    "turn_id": "turn-phone-api",
                    "role": "user",
                    "content": "Find the Shanghai weather",
                },
                {
                    "entry_id": "a1",
                    "turn_id": "turn-phone-api",
                    "role": "assistant",
                    "content": "Shanghai is sunny today",
                },
            ],
        }
        request = AgentAdapterRequest(
            agent_id="cloud-model",
            prompt=(
                "[SIGNALASI_CONVERSATION_CONTEXT_V1]\n"
                f"{json.dumps(mobile_payload)}\n"
                "[/SIGNALASI_CONVERSATION_CONTEXT_V1]\n\n"
                "Current user request:\nCompare that with tomorrow"
            ),
            run_id="current-task",
            conversation_id="conversation-switch",
        )
        with tempfile.TemporaryDirectory() as directory:
            store = conversation_context.ConversationSummaryStore(
                Path(directory) / "summaries.json"
            )
            with patch.object(
                agent_task_manager.agent_task_manager,
                "conversation_messages",
                return_value=[],
            ), patch.object(
                agent_gateway,
                "cloud_model_config",
                return_value={
                    "context_window_tokens": 64_000,
                    "max_output_tokens": 4_096,
                    "context_model_summary": False,
                },
            ), patch.object(conversation_context, "_summary_store", store):
                messages = agent_gateway._stateless_model_messages(request, "cloud-model")

        dialogue = [item["content"] for item in messages if item["role"] != "system"]
        self.assertEqual(
            [
                "Find the Shanghai weather",
                "Shanghai is sunny today",
                "Compare that with tomorrow",
            ],
            dialogue,
        )

    def test_context_summary_setting_accepts_typed_and_serialized_booleans(self):
        self.assertTrue(agent_config._as_bool(True, False))
        self.assertTrue(agent_config._as_bool("true", False))
        self.assertFalse(agent_config._as_bool(False, True))
        self.assertFalse(agent_config._as_bool("false", True))
        self.assertFalse(agent_config._as_bool("", True))

    def test_stateless_cloud_model_uses_refined_summary_and_latest_request(self):
        history = []
        for index in range(24):
            history.append(
                {
                    "task_id": f"task-{index}",
                    "created_at": 1_000 + index,
                    "prompt": f"Earlier request {index} " + "context " * 80,
                    "result": f"Verified result {index} " + "result " * 80,
                    "status": "completed",
                    "agent_id": "cloud-model",
                }
            )
        request = AgentAdapterRequest(
            agent_id="cloud-model",
            prompt="Current request must stay verbatim",
            run_id="current-task",
            conversation_id="conversation-1",
        )
        with tempfile.TemporaryDirectory() as directory:
            store = conversation_context.ConversationSummaryStore(
                Path(directory) / "summaries.json"
            )
            with patch.object(
                agent_task_manager.agent_task_manager,
                "conversation_messages",
                return_value=history,
            ), patch.object(
                agent_gateway,
                "cloud_model_config",
                return_value={
                    "context_window_tokens": 4_096,
                    "max_output_tokens": 1_024,
                    "context_model_summary": True,
                },
            ), patch.object(
                conversation_context,
                "_summary_store",
                store,
            ), patch.object(
                agent_gateway,
                "_refine_stateless_context_summary",
                return_value="- Refined durable context",
            ) as refine:
                messages = agent_gateway._stateless_model_messages(request, "cloud-model")

        self.assertEqual("Current request must stay verbatim", messages[-1]["content"])
        self.assertIn("Refined durable context", messages[0]["content"])
        refine.assert_called_once()

    def test_cloud_model_sends_compiled_messages_unchanged(self):
        captured = {}

        def fake_post(_url, payload, timeout, headers=None):
            captured["payload"] = payload
            return {"choices": [{"message": {"content": "done"}}]}

        messages = [
            {"role": "system", "content": "policy"},
            {"role": "user", "content": "old request"},
            {"role": "assistant", "content": "old result"},
            {"role": "user", "content": "current request"},
        ]
        with patch.object(
            agent_gateway,
            "cloud_model_config",
            return_value={
                "url": "https://example.invalid/v1/chat/completions",
                "api_key": "secret",
                "model": "test-model",
            },
        ), patch.object(agent_gateway, "_post_json", side_effect=fake_post):
            result = agent_gateway.ask_cloud_model("current request", messages=messages)

        self.assertEqual("done", result)
        self.assertEqual(messages, captured["payload"]["messages"])

    def test_cloud_model_recompiles_with_smaller_token_windows_after_overflow(self):
        request = AgentAdapterRequest(
            agent_id="cloud-model",
            prompt="Keep the current request",
            run_id="task-current",
            conversation_id="conversation-overflow",
        )
        attempted_windows = []
        post_attempts = 0

        def fake_messages(_request, _agent_id, context_window_override=None):
            attempted_windows.append(context_window_override)
            return [{"role": "user", "content": f"window={context_window_override}"}]

        def fake_post(_url, _payload, timeout, headers=None):
            nonlocal post_attempts
            post_attempts += 1
            if post_attempts < 3:
                raise agent_gateway.ModelHttpError(400, '{"code":"context_length_exceeded"}')
            return {"choices": [{"message": {"content": "recovered"}}]}

        with patch.object(
            agent_gateway,
            "cloud_model_config",
            return_value={
                "url": "https://example.invalid/v1/chat/completions",
                "api_key": "secret",
                "model": "test-model",
                "context_window_tokens": 64_000,
            },
        ), patch.object(
            agent_gateway,
            "_stateless_model_messages",
            side_effect=fake_messages,
        ), patch.object(agent_gateway, "_post_json", side_effect=fake_post):
            result = agent_gateway._ask_cloud_model_for_request(
                request,
                agent_gateway.all_agent_specs()["cloud-model"],
            )

        self.assertEqual("recovered", result)
        self.assertEqual([64_000, 32_000, 16_000], attempted_windows)


if __name__ == "__main__":
    unittest.main()
