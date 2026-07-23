import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

import link_delivery
import mqtt_bridge


class DisconnectedMqtt:
    @staticmethod
    def is_connected() -> bool:
        return False


class MqttTaskTurnRoutingTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.database_patch = patch.object(
            link_delivery,
            "DB_PATH",
            Path(self.temporary.name) / "delivery.db",
        )
        self.database_patch.start()

    def tearDown(self):
        self.database_patch.stop()
        self.temporary.cleanup()

    def test_phone_turn_id_is_preserved_when_agent_has_internal_turn(self):
        task = {
            "task_id": "task-1",
            "status": "running",
            "client_turn_id": "phone-turn-1",
            "turn_id": "codex-turn-1",
        }

        self.assertEqual("phone-turn-1", mqtt_bridge._client_task_turn_id(task))
        payload = mqtt_bridge._agent_task_payload(task, [])
        self.assertEqual("phone-turn-1", payload["turn_id"])
        self.assertEqual("codex-turn-1", payload["agent_turn_id"])

    def test_legacy_task_without_client_turn_id_remains_routable(self):
        task = {"task_id": "task-1", "status": "completed", "turn_id": "legacy-turn-1"}

        self.assertEqual("legacy-turn-1", mqtt_bridge._client_task_turn_id(task))
        self.assertEqual("legacy-turn-1", mqtt_bridge._agent_task_payload(task, [])["turn_id"])

    def test_cancelled_codex_task_never_publishes_partial_or_failure_text(self):
        self.assertEqual(
            "",
            mqtt_bridge._codex_terminal_result(
                "cancel this task",
                "cancelled",
                "partial answer that arrived during interruption",
            ),
        )

    def test_failed_codex_task_keeps_natural_language_fallback(self):
        self.assertEqual(
            "Codex could not complete this task. Please send it again.",
            mqtt_bridge._codex_terminal_result("run this task", "failed", None),
        )
        self.assertEqual(
            "Codex \u672a\u80fd\u5b8c\u6210\u8fd9\u6b21\u4efb\u52a1\uff0c\u8bf7\u91cd\u65b0\u53d1\u9001\u4e00\u6b21\u3002",
            mqtt_bridge._codex_terminal_result("\u8bf7\u6267\u884c\u4efb\u52a1", "timed_out", ""),
        )

    def test_completed_result_is_queued_offline_and_flushed_after_reconnect(self):
        wire_payload = {"scheme": "signal", "_client_route_id": "phone-1"}
        payload = {"task_id": "task-1", "content": "done"}

        published = mqtt_bridge._publish_or_queue_task_result(
            DisconnectedMqtt(), wire_payload, payload
        )

        self.assertFalse(published)
        pending = link_delivery.pending_task_results()
        self.assertEqual(1, len(pending))
        self.assertEqual("task-1", pending[0]["task_id"])
        self.assertEqual("done", pending[0]["payload"]["content"])
        self.assertRegex(
            pending[0]["payload"]["message_id"],
            r"^[0-9a-f]{8}-[0-9a-f]{4}-5[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$",
        )
        connected_mqtt = object()
        with patch.object(mqtt_bridge, "_publish_phone_payload", return_value=True) as publish:
            mqtt_bridge.flush_pending_task_results(connected_mqtt)
        publish.assert_called_once_with(connected_mqtt, wire_payload, pending[0]["payload"])
        self.assertEqual([], link_delivery.pending_task_results())

    def test_existing_transport_outbox_owns_result_without_duplicate_publish(self):
        wire_payload = {"scheme": "signal", "_client_route_id": "client-1"}
        payload = {
            "type": "chat",
            "task_id": "task-2",
            "message_id": "e70793b8-6ee2-532d-995b-4f55fc73c253",
            "content": "done",
        }
        link_delivery.queue_task_result(
            "task-2",
            "client-1",
            wire_payload,
            payload,
        )
        link_delivery.queue_outbound(
            "client-1",
            payload["message_id"],
            "signalasichat/v1/server/client/down",
            '{"encrypted":true}',
        )

        with patch.object(mqtt_bridge, "_publish_phone_payload") as publish:
            mqtt_bridge.flush_pending_task_results(object())

        publish.assert_not_called()
        self.assertEqual([], link_delivery.pending_task_results())
        self.assertEqual(1, len(link_delivery.pending_outbound()))


if __name__ == "__main__":
    unittest.main()
