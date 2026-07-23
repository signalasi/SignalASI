import tempfile
import time
import unittest
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import patch

import mqtt_bridge


class _ImmediateThread:
    def __init__(self, target=None, args=(), kwargs=None, **_options):
        self.target = target
        self.args = args
        self.kwargs = kwargs or {}

    def start(self):
        if self.target is not None:
            self.target(*self.args, **self.kwargs)


class _RecoveredTaskManager:
    def __init__(self):
        now = int(time.time() * 1000)
        self.task = SimpleNamespace(
            task_id="task-recovered",
            agent_id="codex",
            contact_id="codex",
            source_message_id="message-1",
            prompt="continue",
            conversation_id="conversation-1",
            client_route_id="client-1",
            client_turn_id="client-turn-1",
            thread_id="thread-original",
            turn_id="turn-original",
            created_at=now - 55_000,
            started_at=now - 45_000,
            status="recovering",
            result="",
        )
        self.updates = []

    def resume_external(self, task_id, _on_event):
        if task_id != self.task.task_id:
            return None
        return self.task

    def update(self, task_id, status, on_event=None, **values):
        self.updates.append((task_id, status, values))
        self.task.status = status
        for name, value in values.items():
            if value is not None:
                setattr(self.task, name, value)
        return self.task


class _RecoveredCodexServer:
    def __init__(self):
        self.process = SimpleNamespace(pid=123)
        self.recoveries = []
        self.started = False

    def warm(self):
        return {"ready": True, "pid": self.process.pid}

    def recover_task(self, **values):
        self.recoveries.append(values)
        return SimpleNamespace(finished=False)

    def start_task(self, *_args, **_kwargs):
        self.started = True
        raise AssertionError("Recovery must not start a duplicate Codex turn")


class MqttCodexRecoveryTests(unittest.TestCase):
    def test_recovered_codex_task_reconnects_to_original_turn(self):
        manager = _RecoveredTaskManager()
        server = _RecoveredCodexServer()
        with tempfile.TemporaryDirectory() as temporary, patch.object(
            mqtt_bridge,
            "agent_task_manager",
            manager,
        ), patch.object(
            mqtt_bridge,
            "_codex_server",
            return_value=server,
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
                    "content": "continue",
                    "contact_id": "codex",
                    "agent_id": "codex",
                    "client_message_id": "message-1",
                    "task_id": "task-recovered",
                    "conversation_id": "conversation-1",
                    "attachments": [],
                    "_recovered_task": True,
                },
                trace=[],
                content="continue",
                msg_type="text",
            )

        self.assertFalse(server.started)
        self.assertEqual(1, len(server.recoveries))
        recovery = server.recoveries[0]
        self.assertEqual("task-recovered", recovery["task_id"])
        self.assertEqual("thread-original", recovery["thread_id"])
        self.assertEqual("turn-original", recovery["turn_id"])
        self.assertGreaterEqual(recovery["elapsed_seconds"], 44)
        with mqtt_bridge.codex_task_callbacks_lock:
            mqtt_bridge.codex_task_callbacks.pop("task-recovered", None)


if __name__ == "__main__":
    unittest.main()
