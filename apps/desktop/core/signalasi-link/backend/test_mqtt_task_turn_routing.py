import unittest
from unittest.mock import patch

import mqtt_bridge


class DisconnectedMqtt:
    @staticmethod
    def is_connected() -> bool:
        return False


class MqttTaskTurnRoutingTests(unittest.TestCase):
    def tearDown(self):
        with mqtt_bridge.pending_task_results_lock:
            mqtt_bridge.pending_task_results.clear()

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

    def test_completed_result_is_queued_offline_and_flushed_after_reconnect(self):
        wire_payload = {"scheme": "signal", "_client_route_id": "phone-1"}
        payload = {"task_id": "task-1", "content": "done"}

        published = mqtt_bridge._publish_or_queue_task_result(
            DisconnectedMqtt(), wire_payload, payload
        )

        self.assertFalse(published)
        self.assertIn("task-1", mqtt_bridge.pending_task_results)
        connected_mqtt = object()
        with patch.object(mqtt_bridge, "_publish_phone_payload", return_value=True) as publish:
            mqtt_bridge.flush_pending_task_results(connected_mqtt)
        publish.assert_called_once_with(connected_mqtt, wire_payload, payload)
        self.assertNotIn("task-1", mqtt_bridge.pending_task_results)


if __name__ == "__main__":
    unittest.main()
