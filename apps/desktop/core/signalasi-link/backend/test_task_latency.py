import unittest
from types import SimpleNamespace
from unittest.mock import patch

from codex_app_server import CodexAppServer
from mqtt_bridge import _should_publish_task_status, _trace_metrics


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
