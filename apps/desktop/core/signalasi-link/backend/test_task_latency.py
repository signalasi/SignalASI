import unittest
from types import SimpleNamespace
from unittest.mock import patch

from codex_app_server import CodexAppServer
from mqtt_bridge import (
    _TaskProgressEventGate,
    _agent_task_payload,
    _should_publish_task_status,
    _trace_metrics,
)


class TaskLatencyTests(unittest.TestCase):
    def test_trace_metrics_reports_stage_and_total_milliseconds(self):
        metrics = _trace_metrics([
            {"stage": "phone_publish_started", "at": 1_000},
            {"stage": "desktop_mqtt_received", "at": 1_240},
            {"stage": "desktop_task_created", "at": 1_310},
            {"stage": "codex_running", "at": 1_900},
        ])

        self.assertEqual(900, metrics["total_ms"])
        self.assertEqual(240, metrics["stages"][1]["from_previous_ms"])
        self.assertEqual(310, metrics["stages"][2]["from_start_ms"])

    def test_transport_statuses_are_merged_into_one_progress_row(self):
        self.assertFalse(_should_publish_task_status("accepted"))
        self.assertFalse(_should_publish_task_status("queued"))
        self.assertFalse(_should_publish_task_status("starting"))
        self.assertTrue(_should_publish_task_status("running"))
        self.assertFalse(_should_publish_task_status("completed"))
        self.assertTrue(_should_publish_task_status("failed"))

    def test_running_progress_keeps_a_bounded_heartbeat(self):
        gate = _TaskProgressEventGate(heartbeat_interval_ms=15_000)

        self.assertTrue(gate.should_publish({
            "status": "running", "status_seq": 1, "current_step": "Running Codex",
        }, now_ms=1_000))
        self.assertFalse(gate.should_publish({
            "status": "running", "status_seq": 2, "current_step": "Running Codex",
        }, now_ms=6_000))
        self.assertFalse(gate.should_publish({
            "status": "running", "status_seq": 3, "current_step": "Running Codex",
        }, now_ms=15_999))
        self.assertFalse(gate.should_publish({
            "status": "running", "status_seq": 2, "current_step": "Running Codex",
        }, now_ms=16_000))
        self.assertTrue(gate.should_publish({
            "status": "running", "status_seq": 4, "current_step": "Running Codex",
        }, now_ms=16_000))

    def test_running_step_change_publishes_without_waiting_for_heartbeat(self):
        gate = _TaskProgressEventGate(heartbeat_interval_ms=15_000)

        self.assertTrue(gate.should_publish({
            "status": "running", "status_seq": 1, "current_step": "",
        }, now_ms=1_000))
        self.assertTrue(gate.should_publish({
            "status": "running", "status_seq": 2, "current_step": "Reading files",
        }, now_ms=1_100))
        self.assertFalse(gate.should_publish({
            "status": "running", "status_seq": 2, "current_step": "Reading files",
        }, now_ms=20_000))

    def test_progress_event_state_change_publishes_even_when_title_is_unchanged(self):
        gate = _TaskProgressEventGate(heartbeat_interval_ms=15_000)
        started = {
            "event_id": "codex:image_view:image-1",
            "status": "running",
            "created_at": 1_000,
            "updated_at": 1_000,
        }
        completed = {
            **started,
            "status": "completed",
            "updated_at": 1_100,
        }

        self.assertTrue(gate.should_publish({
            "status": "running",
            "status_seq": 1,
            "current_step": "Image",
            "events": [started],
        }, now_ms=1_000))
        self.assertTrue(gate.should_publish({
            "status": "running",
            "status_seq": 2,
            "current_step": "Image",
            "events": [completed],
        }, now_ms=1_100))

    def test_task_payload_carries_only_latest_structured_progress_event(self):
        first = {"event_id": "one", "status": "completed"}
        latest = {
            "event_id": "two",
            "kind": "narration",
            "status": "completed",
            "detail": "Inspecting the worksheet.",
        }
        payload = _agent_task_payload(
            {
                "task_id": "task-1",
                "status": "running",
                "events": [first, latest],
            },
            [],
            resolved_desktop_id="desktop-1",
            resolved_desktop_name="Desktop",
            resolved_connector_agents=[],
        )

        self.assertEqual(latest, payload["progress_event"])
        self.assertNotIn("events", payload)

    def test_terminal_failure_is_not_throttled(self):
        gate = _TaskProgressEventGate(heartbeat_interval_ms=15_000)

        self.assertTrue(gate.should_publish({
            "status": "running", "status_seq": 1, "current_step": "Running Hermes",
        }, now_ms=1_000))
        self.assertTrue(gate.should_publish({
            "status": "failed", "status_seq": 2, "current_step": "Running Hermes",
        }, now_ms=1_100))
        self.assertFalse(gate.should_publish({
            "status": "completed", "status_seq": 3, "current_step": "",
        }, now_ms=1_200))

    def test_steered_completion_is_published_even_though_normal_completion_is_hidden(self):
        gate = _TaskProgressEventGate(heartbeat_interval_ms=15_000)

        self.assertTrue(gate.should_publish({
            "status": "running", "status_seq": 1, "current_step": "Adding instruction",
        }, now_ms=1_000))
        self.assertTrue(gate.should_publish({
            "status": "completed",
            "status_seq": 2,
            "current_step": "",
            "task_disposition": "steered",
        }, now_ms=1_100))

    def test_resumed_running_and_stale_events_are_ordered(self):
        gate = _TaskProgressEventGate(heartbeat_interval_ms=15_000)

        self.assertTrue(gate.should_publish({
            "status": "running", "status_seq": 1, "current_step": "Calling tool",
        }, now_ms=1_000))
        self.assertTrue(gate.should_publish({
            "status": "waiting_input", "status_seq": 2, "current_step": "Waiting for input",
        }, now_ms=1_100))
        self.assertTrue(gate.should_publish({
            "status": "running", "status_seq": 3, "current_step": "Calling tool",
        }, now_ms=1_200))
        self.assertFalse(gate.should_publish({
            "status": "completed", "status_seq": 4, "current_step": "",
        }, now_ms=1_300))
        self.assertFalse(gate.should_publish({
            "status": "running", "status_seq": 3, "current_step": "Calling tool",
        }, now_ms=20_000))

    def test_warm_initializes_without_creating_a_task(self):
        server = CodexAppServer("codex", {}, lambda _task_id, _event: None)
        server.process = SimpleNamespace(pid=42)
        with patch.object(server, "_ensure_started") as ensure_started:
            result = server.warm()

        ensure_started.assert_called_once_with()
        self.assertTrue(result["ready"])
        self.assertEqual(42, result["pid"])


if __name__ == "__main__":
    unittest.main()
