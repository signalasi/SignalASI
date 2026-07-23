import tempfile
import unittest
from pathlib import Path

from agent_task_manager import AgentTaskManager
from agent_task_store import AgentTaskStore


def _record(index: int, conversation_id: str = "conversation-main") -> dict:
    task_id = f"task-{index:04d}"
    return {
        "task_id": task_id,
        "agent_id": "cloud-model",
        "contact_id": "cloud-contact",
        "source_message_id": f"desktop:{index}",
        "prompt": f"request {index}",
        "conversation_id": conversation_id,
        "client_route_id": "",
        "status": "completed",
        "created_at": 10_000 + index,
        "started_at": 10_000 + index,
        "updated_at": 20_000 + index,
        "completed_at": 20_000 + index,
        "result": f"result {index}",
        "error": "",
        "exit_code": 0,
        "status_seq": 4,
        "thread_id": "",
        "turn_id": "",
        "client_turn_id": "",
        "delegate_agent_id": "",
        "current_step": "",
        "events": [],
        "output_files": [],
        "attachments": [],
        "retry_of": "",
        "attempt": 1,
    }


class AgentTaskStoreTests(unittest.TestCase):
    def test_complete_conversation_history_survives_beyond_legacy_limit(self):
        with tempfile.TemporaryDirectory() as temp_dir:
            path = Path(temp_dir) / "tasks.sqlite3"
            store = AgentTaskStore(path)
            for index in range(1_205):
                store.upsert(_record(index))

            manager = AgentTaskManager(state_path=path)
            history = manager.conversation_messages(
                "conversation-main",
                source_prefix=None,
            )

            self.assertEqual(len(history), 1_205)
            self.assertEqual(history[0]["task_id"], "task-0000")
            self.assertEqual(history[-1]["task_id"], "task-1204")
            self.assertEqual(manager.get("task-0000").result, "result 0")
            self.assertEqual(AgentTaskStore(path).count(), 1_205)

    def test_conversation_cursor_queries_only_uncompacted_history(self):
        with tempfile.TemporaryDirectory() as temp_dir:
            store = AgentTaskStore(Path(temp_dir) / "tasks.sqlite3")
            for index in range(30):
                store.upsert(_record(index))

            records = store.conversation(
                "conversation-main",
                source_prefix=None,
                after_cursor=(10_019, "task-0019"),
            )

            self.assertEqual(
                [item["task_id"] for item in records],
                [f"task-{index:04d}" for index in range(20, 30)],
            )

    def test_full_task_event_history_is_persisted_while_public_view_is_bounded(self):
        with tempfile.TemporaryDirectory() as temp_dir:
            path = Path(temp_dir) / "tasks.sqlite3"
            record = _record(1)
            record["events"] = [
                {
                    "event_id": f"event-{index}",
                    "created_at": 30_000 + index,
                    "kind": "tool",
                    "title": f"Tool step {index}",
                    "status": "completed",
                    "detail": f"detail {index}",
                    "metadata": {},
                }
                for index in range(175)
            ]
            AgentTaskStore(path).upsert(record)

            restored = AgentTaskManager(state_path=path).get(record["task_id"])

            self.assertEqual(len(restored.events), 175)
            self.assertEqual(len(restored.public()["events"]), 100)
            self.assertEqual(restored.events[0]["event_id"], "event-0")

    def test_deleting_one_conversation_does_not_remove_other_history(self):
        with tempfile.TemporaryDirectory() as temp_dir:
            path = Path(temp_dir) / "tasks.sqlite3"
            store = AgentTaskStore(path)
            store.upsert(_record(1, "conversation-delete"))
            store.upsert(_record(2, "conversation-keep"))

            manager = AgentTaskManager(state_path=path)
            deleted = manager.delete_conversation("conversation-delete")

            self.assertEqual(deleted, ["task-0001"])
            self.assertIsNone(manager.get("task-0001"))
            self.assertIsNotNone(manager.get("task-0002"))

    def test_legacy_json_history_is_not_imported(self):
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            (root / "agent_tasks.json").write_text(
                '[{"task_id":"legacy-task","prompt":"legacy"}]',
                encoding="utf-8",
            )

            manager = AgentTaskManager(state_path=root / "agent_tasks.sqlite3")

            self.assertEqual(manager.list(), [])
            self.assertIsNone(manager.get("legacy-task"))


if __name__ == "__main__":
    unittest.main()
