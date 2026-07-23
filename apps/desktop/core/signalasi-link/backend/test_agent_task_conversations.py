import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

import agent_task_manager


class AgentTaskConversationTests(unittest.TestCase):
    def test_delete_conversation_removes_only_matching_tasks(self):
        with tempfile.TemporaryDirectory() as temporary, patch.object(
            agent_task_manager, "TASKS_PATH", Path(temporary) / "tasks.json"
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

    def test_restart_replays_only_tasks_that_were_interrupted(self):
        with tempfile.TemporaryDirectory() as temporary, patch.object(
            agent_task_manager, "TASKS_PATH", Path(temporary) / "tasks.json"
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

            restored = agent_task_manager.AgentTaskManager()
            recovered = restored.drain_recovered()

            self.assertEqual(["running-task"], [item["task_id"] for item in recovered])
            self.assertEqual("failed", recovered[0]["status"])
            self.assertEqual([], restored.drain_recovered())

    def test_client_turn_id_survives_codex_internal_turn_updates(self):
        with tempfile.TemporaryDirectory() as temporary, patch.object(
            agent_task_manager, "TASKS_PATH", Path(temporary) / "tasks.json"
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
