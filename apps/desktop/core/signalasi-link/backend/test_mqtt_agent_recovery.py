import tempfile
import time
import unittest
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import patch

import mqtt_bridge


class _RecoveredTask(SimpleNamespace):
    def public(self):
        return {
            "task_id": self.task_id,
            "agent_id": self.agent_id,
            "contact_id": self.contact_id,
            "source_message_id": self.source_message_id,
            "conversation_id": self.conversation_id,
            "client_route_id": self.client_route_id,
            "client_turn_id": self.client_turn_id,
            "thread_id": "",
            "turn_id": "",
            "status": self.status,
            "created_at": self.created_at,
            "started_at": self.started_at,
            "updated_at": self.updated_at,
            "completed_at": self.completed_at,
            "elapsed_ms": max(0, self.updated_at - self.started_at),
            "status_seq": self.status_seq,
            "current_step": self.current_step,
            "result": self.result,
            "error": self.error,
            "output_files": [],
            "events": [],
        }


class _RecoveredTaskManager:
    def __init__(self):
        now = int(time.time() * 1000)
        self.task = _RecoveredTask(
            task_id="task-recovered",
            agent_id="hermes",
            contact_id="hermes",
            source_message_id="message-1",
            conversation_id="conversation-1",
            client_route_id="client-1",
            client_turn_id="phone-turn-1",
            status="recovering",
            created_at=now - 20_000,
            started_at=now - 18_000,
            updated_at=now,
            completed_at=0,
            status_seq=4,
            current_step="",
            result="",
            error="",
        )
        self.runner_replayed = False

    def resume_external(self, task_id, _on_event):
        if task_id != self.task.task_id:
            return None
        self.task.status = "accepted"
        return self.task

    def resume(self, *_args, **_kwargs):
        self.runner_replayed = True
        raise AssertionError("Recovery must not execute the Agent request again")

    def update(self, task_id, status, on_event=None, **values):
        if task_id != self.task.task_id:
            return None
        self.task.status = status
        self.task.status_seq += 1
        self.task.updated_at = int(time.time() * 1000)
        if status in {"completed", "failed", "cancelled", "timed_out"}:
            self.task.completed_at = self.task.updated_at
        for name, value in values.items():
            if value is not None:
                setattr(self.task, name, value)
        return self.task


class _AdapterProvider:
    def __init__(self, state, reply="", error=""):
        self.result = SimpleNamespace(state=state, reply=reply, error=error)
        self.calls = []

    def status(self, agent_id, run_id):
        self.calls.append((agent_id, run_id))
        return self.result


class MqttAgentRecoveryTests(unittest.TestCase):
    def _recover(self, provider):
        manager = _RecoveredTaskManager()
        replies = []
        with tempfile.TemporaryDirectory() as temporary, patch.object(
            mqtt_bridge,
            "agent_task_manager",
            manager,
        ), patch(
            "agent_gateway.desktop_agent_provider",
            return_value=provider,
        ), patch.object(
            mqtt_bridge,
            "_publish_or_queue_task_result",
            side_effect=lambda _mqtt, _wire, payload: replies.append(payload) or True,
        ), patch.object(
            mqtt_bridge,
            "desktop_id",
            return_value="desktop-1",
        ), patch.object(
            mqtt_bridge,
            "desktop_name",
            return_value="Desktop",
        ), patch.object(
            mqtt_bridge,
            "mobile_connector_agents",
            return_value=[],
        ), patch(
            "task_workspace.task_workspace",
            return_value=Path(temporary),
        ):
            mqtt_bridge._start_remote_agent_task(
                mqttc=SimpleNamespace(),
                wire_payload={"scheme": "signal", "_client_route_id": "client-1"},
                payload={
                    "type": "text",
                    "content": "finish the original task",
                    "contact_id": "hermes",
                    "agent_id": "hermes",
                    "client_message_id": "message-1",
                    "task_id": "task-recovered",
                    "conversation_id": "conversation-1",
                    "attachments": [],
                    "_recovered_task": True,
                },
                trace=[],
                content="finish the original task",
                msg_type="text",
            )
        return manager, replies

    def test_completed_adapter_receipt_is_delivered_without_reexecution(self):
        provider = _AdapterProvider("completed", reply="Recovered final answer")
        manager, replies = self._recover(provider)

        self.assertFalse(manager.runner_replayed)
        self.assertEqual([("hermes", "task-recovered")], provider.calls)
        self.assertEqual("completed", manager.task.status)
        self.assertEqual("Recovered final answer", replies[0]["content"])

    def test_interrupted_adapter_receipt_fails_clearly_without_reexecution(self):
        provider = _AdapterProvider("interrupted", error="Desktop stopped")
        manager, replies = self._recover(provider)

        self.assertFalse(manager.runner_replayed)
        self.assertEqual("failed", manager.task.status)
        self.assertIn("was not repeated", replies[0]["content"])
        self.assertNotIn("Desktop stopped", replies[0]["content"])


if __name__ == "__main__":
    unittest.main()
