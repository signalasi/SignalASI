import tempfile
import time
import unittest
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import patch

import mqtt_bridge
import agent_conversation_sessions


class _ImmediateThread:
    def __init__(self, target=None, args=(), kwargs=None, **_options):
        self.target = target
        self.args = args
        self.kwargs = kwargs or {}

    def start(self):
        if self.target is not None:
            self.target(*self.args, **self.kwargs)


class _Task:
    def __init__(
        self,
        task_id: str,
        prompt: str,
        conversation_id: str,
        client_conversation_id: str = "conversation-1",
    ):
        now = int(time.time() * 1000)
        self.task_id = task_id
        self.agent_id = "codex"
        self.contact_id = "codex"
        self.source_message_id = "message-follow-up"
        self.prompt = prompt
        self.conversation_id = conversation_id
        self.client_conversation_id = client_conversation_id
        self.client_route_id = "client-1"
        self.client_turn_id = "phone-turn-follow-up"
        self.thread_id = ""
        self.turn_id = ""
        self.status = "accepted"
        self.status_seq = 0
        self.created_at = now
        self.started_at = 0
        self.updated_at = now
        self.completed_at = 0
        self.current_step = ""
        self.result = ""
        self.error = ""

    def public(self):
        return {
            "task_id": self.task_id,
            "agent_id": self.agent_id,
            "contact_id": self.contact_id,
            "source_message_id": self.source_message_id,
            "conversation_id": self.conversation_id,
            "client_conversation_id": self.client_conversation_id,
            "client_route_id": self.client_route_id,
            "client_turn_id": self.client_turn_id,
            "thread_id": self.thread_id,
            "turn_id": self.turn_id,
            "status": self.status,
            "status_seq": self.status_seq,
            "created_at": self.created_at,
            "started_at": self.started_at,
            "updated_at": self.updated_at,
            "completed_at": self.completed_at,
            "elapsed_ms": max(0, (self.completed_at or self.updated_at) - self.created_at),
            "current_step": self.current_step,
            "result": self.result,
            "error": self.error,
            "output_files": [],
            "process_id": 0,
        }


class _SteeringTaskManager:
    def __init__(self):
        self.prior = _Task(
            "task-original",
            "grade homework",
            mqtt_bridge._scoped_agent_conversation_id("client-1", "conversation-1"),
        )
        self.prior.source_message_id = "message-original"
        self.prior.status = "running"
        self.current = None
        self.updates = []

    def create_external(self, **values):
        self.current = _Task(
            values["task_id"] or "task-follow-up",
            values["prompt"],
            values["conversation_id"],
            values["client_conversation_id"],
        )
        return self.current

    def active_for_conversation(self, conversation_id, **_options):
        return self.prior if conversation_id == self.prior.conversation_id else None

    def update(self, task_id, status, on_event=None, **values):
        self.updates.append((task_id, status, values, on_event))
        task = self.current if self.current and self.current.task_id == task_id else self.prior
        task.status = status
        task.status_seq += 1
        task.updated_at = int(time.time() * 1000)
        if status not in {"accepted", "queued"} and not task.started_at:
            task.started_at = task.updated_at
        if status in {"completed", "failed", "cancelled", "timed_out"}:
            task.completed_at = task.updated_at
            task.current_step = ""
        for name, value in values.items():
            if value is not None:
                setattr(task, name, value)
        if on_event is not None:
            on_event(task.public())
        return task


class _SteeringCodexServer:
    def __init__(self):
        self.process = SimpleNamespace(pid=123)
        self.steers = []
        self.started = False

    def warm(self):
        return {"ready": True, "pid": self.process.pid}

    def steer_task(self, task_id, prompt, image_paths=None):
        self.steers.append((task_id, prompt, list(image_paths or [])))
        return SimpleNamespace(
            task_id=task_id,
            thread_id="thread-original",
            turn_id="turn-original",
        )

    def start_task(self, *_args, **_kwargs):
        self.started = True
        raise AssertionError("A follow-up in the same conversation must steer the active turn")


class MqttCodexSteeringTests(unittest.TestCase):
    def test_same_conversation_follow_up_steers_without_publishing_assistant_result(self):
        manager = _SteeringTaskManager()
        server = _SteeringCodexServer()
        published_events = []
        published_results = []
        with tempfile.TemporaryDirectory() as temporary, patch.object(
            mqtt_bridge,
            "agent_task_manager",
            manager,
        ), patch.object(
            agent_conversation_sessions,
            "_sessions",
            agent_conversation_sessions.AgentConversationSessions(
                Path(temporary) / "sessions.json"
            ),
        ), patch.object(
            mqtt_bridge,
            "_codex_server",
            return_value=server,
        ), patch.object(
            mqtt_bridge,
            "_enqueue_task_event",
            side_effect=lambda _mqttc, _wire, event, _trace: published_events.append(dict(event)),
        ), patch.object(
            mqtt_bridge,
            "_publish_or_queue_task_result",
            side_effect=lambda *_args: published_results.append(_args),
        ), patch.object(
            mqtt_bridge.threading,
            "Thread",
            _ImmediateThread,
        ), patch(
            "agent_gateway._find_codex_desktop_cli",
            return_value="codex",
        ), patch(
            "task_workspace.task_workspace",
            return_value=Path(temporary),
        ):
            mqtt_bridge._start_remote_agent_task(
                mqttc=SimpleNamespace(),
                wire_payload={"scheme": "signal", "_client_route_id": "client-1"},
                payload={
                    "type": "text",
                    "content": "\u8981\u4fdd\u8bc1\u6b63\u786e",
                    "contact_id": "codex",
                    "agent_id": "codex",
                    "client_message_id": "message-follow-up",
                    "task_id": "task-follow-up",
                    "conversation_id": "conversation-1",
                    "turn_id": "phone-turn-follow-up",
                    "attachments": [],
                },
                trace=[],
                content="\u8981\u4fdd\u8bc1\u6b63\u786e",
                msg_type="text",
            )

        self.assertFalse(server.started)
        self.assertEqual(1, len(server.steers))
        self.assertEqual("task-original", server.steers[0][0])
        self.assertIn("\u8981\u4fdd\u8bc1\u6b63\u786e", server.steers[0][1])
        terminal = [event for event in published_events if event["status"] == "completed"]
        self.assertEqual(1, len(terminal))
        self.assertEqual("steered", terminal[0]["task_disposition"])
        self.assertEqual("task-original", terminal[0]["merged_into_task_id"])
        self.assertEqual([], published_results)
        with mqtt_bridge.codex_task_callbacks_lock:
            mqtt_bridge.codex_task_callbacks.pop("task-follow-up", None)


if __name__ == "__main__":
    unittest.main()
