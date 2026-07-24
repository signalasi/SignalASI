import tempfile
import unittest
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import patch

import link_delivery
import mqtt_bridge


class DisconnectedMqtt:
    @staticmethod
    def is_connected() -> bool:
        return False


class ConnectedMqtt:
    @staticmethod
    def is_connected() -> bool:
        return True


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
        with mqtt_bridge.pending_task_events_lock:
            mqtt_bridge.pending_task_events.clear()
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
        payload = mqtt_bridge._agent_task_payload(
            task,
            [],
            resolved_desktop_id="desktop-test",
            resolved_desktop_name="Test Desktop",
            resolved_connector_agents=[],
        )
        self.assertEqual("phone-turn-1", payload["turn_id"])
        self.assertEqual("codex-turn-1", payload["agent_turn_id"])

    def test_task_without_client_turn_id_does_not_expose_internal_agent_turn(self):
        task = {"task_id": "task-1", "status": "completed", "turn_id": "codex-turn-1"}

        payload = mqtt_bridge._agent_task_payload(
            task,
            [],
            resolved_desktop_id="desktop-test",
            resolved_desktop_name="Test Desktop",
            resolved_connector_agents=[],
        )

        self.assertEqual("", mqtt_bridge._client_task_turn_id(task))
        self.assertEqual("", payload["turn_id"])
        self.assertEqual("codex-turn-1", payload["agent_turn_id"])

    def test_offline_task_event_queues_without_starting_signal_sidecar(self):
        task = {
            "task_id": "task-offline",
            "status": "running",
            "status_seq": 4,
            "updated_at": 100,
            "client_turn_id": "phone-turn-1",
        }

        with patch.object(
            mqtt_bridge,
            "desktop_id",
            side_effect=AssertionError("identity must not resolve while offline"),
        ):
            published = mqtt_bridge._publish_or_queue_task_event(
                DisconnectedMqtt(),
                {"scheme": "signal", "_client_route_id": "phone-1"},
                task,
                [],
            )

        self.assertFalse(published)
        self.assertEqual(task, mqtt_bridge.pending_task_events["task-offline"].task)

    def test_queued_task_event_resolves_identity_only_when_flushed(self):
        task = {
            "task_id": "task-reconnect",
            "status": "running",
            "status_seq": 5,
            "updated_at": 200,
            "client_turn_id": "phone-turn-2",
        }
        mqtt_bridge._publish_or_queue_task_event(
            DisconnectedMqtt(),
            {"scheme": "signal", "_client_route_id": "phone-1"},
            task,
            [],
        )

        with (
            patch.object(mqtt_bridge, "desktop_id", return_value="desktop-test"),
            patch.object(mqtt_bridge, "desktop_name", return_value="Test Desktop"),
            patch.object(mqtt_bridge, "mobile_connector_agents", return_value=[]),
            patch.object(mqtt_bridge, "_publish_phone_payload", return_value=True) as publish,
        ):
            mqtt_bridge.flush_pending_task_events(ConnectedMqtt())

        payload = publish.call_args.args[2]
        self.assertEqual("desktop-test", payload["desktop_id"])
        self.assertEqual("phone-turn-2", payload["turn_id"])
        self.assertNotIn("task-reconnect", mqtt_bridge.pending_task_events)

    def test_online_task_event_is_queued_when_signal_sidecar_is_temporarily_unavailable(self):
        task = {
            "task_id": "task-sidecar-recovery",
            "status": "running",
            "status_seq": 6,
            "updated_at": 220,
        }

        with patch.object(
            mqtt_bridge,
            "desktop_id",
            side_effect=FileNotFoundError("sidecar temporarily unavailable"),
        ):
            published = mqtt_bridge._publish_or_queue_task_event(
                ConnectedMqtt(),
                {"scheme": "signal", "_client_route_id": "phone-1"},
                task,
                [],
            )

        self.assertFalse(published)
        self.assertEqual(
            "running",
            mqtt_bridge.pending_task_events["task-sidecar-recovery"].task["status"],
        )

    def test_older_offline_event_cannot_replace_newer_terminal_state(self):
        terminal = {
            "task_id": "task-order",
            "status": "completed",
            "status_seq": 9,
            "updated_at": 300,
        }
        stale = {
            "task_id": "task-order",
            "status": "running",
            "status_seq": 8,
            "updated_at": 250,
        }

        mqtt_bridge._publish_or_queue_task_event(DisconnectedMqtt(), {}, terminal, [])
        mqtt_bridge._publish_or_queue_task_event(DisconnectedMqtt(), {}, stale, [])

        queued = mqtt_bridge.pending_task_events["task-order"]
        self.assertEqual("completed", queued.task["status"])
        self.assertEqual(9, queued.task["status_seq"])

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

    def test_task_control_requires_exact_paired_route_and_message(self):
        task = SimpleNamespace(
            client_route_id="client-a",
            contact_id="codex",
            source_message_id="42",
        )

        self.assertTrue(
            mqtt_bridge._task_control_matches(
                task,
                client_route_id="client-a",
                contact_id="codex",
                source_message_id="42",
            )
        )
        self.assertFalse(
            mqtt_bridge._task_control_matches(
                task,
                client_route_id="client-b",
                contact_id="codex",
                source_message_id="42",
            )
        )

    def test_task_control_rejects_missing_current_route_identity(self):
        task = SimpleNamespace(
            client_route_id="",
            contact_id="codex",
            source_message_id="42",
        )

        self.assertFalse(
            mqtt_bridge._task_control_matches(
                task,
                client_route_id="client-a",
                contact_id="codex",
                source_message_id="42",
            )
        )
        self.assertFalse(
            mqtt_bridge._task_control_matches(
                SimpleNamespace(
                    client_route_id="client-a",
                    contact_id="codex",
                    source_message_id="42",
                ),
                client_route_id="client-a",
                contact_id="codex",
                source_message_id="",
            )
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
