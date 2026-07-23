import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

import agent_gateway
import agent_task_manager
import conversation_context
from desktop_agent_adapters import AgentAdapterRequest


class AgentContextCompactionTest(unittest.TestCase):
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


if __name__ == "__main__":
    unittest.main()
