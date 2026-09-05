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
            "conversation_id": "client:phone-a:conversation-1",
            "client_conversation_id": "conversation-1",
            "client_turn_id": "phone-turn-1",
            "turn_id": "codex-turn-1",
        }

        self.assertEqual("phone-turn-1", mqtt_bridge._client_task_turn_id(task))
        payload = mqtt_bridge._agent_task_payload(
            task,
            [],
            resolved_desktop_id="desktop-test",
            resolved_desktop_name="Test Desktop",
        )
        self.assertEqual("phone-turn-1", payload["turn_id"])
        self.assertEqual("codex-turn-1", payload["agent_turn_id"])
        self.assertEqual("conversation-1", payload["conversation_id"])

    def test_same_phone_conversation_id_is_isolated_between_pairings(self):
        phone_a = mqtt_bridge._scoped_agent_conversation_id("phone-a", "conversation-1")
        phone_b = mqtt_bridge._scoped_agent_conversation_id("phone-b", "conversation-1")

        self.assertEqual("client:phone-a:conversation-1", phone_a)
        self.assertEqual("client:phone-b:conversation-1", phone_b)
        self.assertNotEqual(phone_a, phone_b)

    def test_agent_instances_get_independent_native_conversations(self):
        base = mqtt_bridge._scoped_agent_conversation_id("phone-a", "conversation-1")

        reviewer = mqtt_bridge._scoped_agent_instance_conversation(base, "codex-reviewer")
        implementer = mqtt_bridge._scoped_agent_instance_conversation(base, "codex-implementer")

        self.assertEqual(
            "client:phone-a:conversation-1:agent-instance:codex-reviewer",
            reviewer,
        )
        self.assertNotEqual(reviewer, implementer)
        self.assertEqual(base, mqtt_bridge._scoped_agent_instance_conversation(base, ""))

    def test_agent_instance_id_rejects_unsafe_scope_values(self):
        self.assertEqual(
            "codex-reviewer:2",
            mqtt_bridge._agent_instance_id(
                {"agent_instance_id": "codex-reviewer:2"}
            ),
        )
        with self.assertRaisesRegex(ValueError, "invalid agent_instance_id"):
            mqtt_bridge._agent_instance_id(
                {"agent_instance_id": "../shared conversation"}
            )

    def test_team_message_forces_running_codex_turn_to_steer(self):
        original = SimpleNamespace(disposition="independent")
        decision = mqtt_bridge._team_follow_up_decision(
            {"agent_team_message": True},
            "codex",
            SimpleNamespace(task_id="active-task"),
            original,
        )

        self.assertEqual("steer", decision.disposition.value)
        self.assertIs(
            original,
            mqtt_bridge._team_follow_up_decision(
                {"agent_team_message": True},
                "claude",
                SimpleNamespace(task_id="active-task"),
                original,
            ),
        )

    def test_remote_task_identity_requires_all_four_matching_levels(self):
        payload = {
            "client_route_id": "phone-a",
            "conversation_id": "conversation-1",
            "task_id": "task-1",
            "turn_id": "turn-1",
        }

        self.assertEqual(
            payload,
            mqtt_bridge._remote_task_identity(payload, "phone-a"),
        )
        self.assertIsNone(
            mqtt_bridge._remote_task_identity(
                {**payload, "client_route_id": "phone-b"},
                "phone-a",
            )
        )
        self.assertIsNone(
            mqtt_bridge._remote_task_identity(
                {**payload, "turn_id": ""},
                "phone-a",
            )
        )

    def test_repaired_route_reuses_conversation_for_same_signal_identity(self):
        fingerprint = "a" * 64

        with patch.object(
            mqtt_bridge,
            "get_client",
            side_effect=lambda route, include_revoked=False: {
                "client_route_id": route,
                "identity_fingerprint": fingerprint,
            },
        ):
            before = mqtt_bridge._scoped_agent_conversation_id("old-route", "conversation-1")
            after = mqtt_bridge._scoped_agent_conversation_id("new-route", "conversation-1")

        self.assertEqual(before, after)
        self.assertEqual(f"client:identity:{fingerprint}:conversation-1", before)

    def test_task_without_client_turn_id_does_not_expose_internal_agent_turn(self):
        task = {"task_id": "task-1", "status": "completed", "turn_id": "codex-turn-1"}

        payload = mqtt_bridge._agent_task_payload(
            task,
            [],
            resolved_desktop_id="desktop-test",
            resolved_desktop_name="Test Desktop",
        )

        self.assertEqual("", mqtt_bridge._client_task_turn_id(task))
        self.assertEqual("", payload["turn_id"])
        self.assertEqual("codex-turn-1", payload["agent_turn_id"])

    def test_terminal_task_event_carries_signed_reputation_evidence(self):
        receipt = {
            "receipt_id": "receipt-1",
            "agent_id": "desktop_test:codex",
            "capabilities": ["CODE"],
            "signature": "signed",
        }
        snapshot = {
            "agent_id": "desktop_test:codex",
            "score": 74,
            "confidence": 18,
        }
        ledger = SimpleNamespace(
            receipt_for_task=lambda _task_id: None,
            record_task=lambda _task: receipt,
            snapshot=lambda _agent_id, _capabilities: snapshot,
        )
        task = {
            "task_id": "task-1",
            "status": "completed",
            "agent_id": "codex",
            "contact_id": "desktop_test:codex",
            "completed_at": 2_000,
        }

        with patch(
            "agent_reputation_ledger.agent_reputation_ledger",
            return_value=ledger,
        ):
            payload = mqtt_bridge._agent_task_payload(
                task,
                [],
                resolved_desktop_id="desktop_test",
                resolved_desktop_name="Test Desktop",
            )

        self.assertEqual(receipt, payload["execution_receipt"])
        self.assertEqual(snapshot, payload["reputation_snapshot"])

    def test_offline_task_event_queues_without_starting_signal_sidecar(self):
        task = {
            "task_id": "task-offline",
            "status": "running",
            "status_seq": 4,
            "updated_at": 100,
            "client_route_id": "phone-1",
            "client_conversation_id": "conversation-1",
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
        self.assertTrue(mqtt_bridge.pending_task_events["task-offline"].replay_progress)

    def test_reconnected_task_event_replays_readable_progress_only(self):
        task = {
            "task_id": "task-reconnect-progress",
            "status": "running",
            "status_seq": 5,
            "updated_at": 200,
            "client_route_id": "phone-1",
            "client_conversation_id": "conversation-1",
            "client_turn_id": "phone-turn-2",
            "events": [
                {
                    "event_id": "command-1",
                    "kind": "command",
                    "status": "completed",
                    "detail": "python internal.py",
                },
                {
                    "event_id": "narration-1",
                    "kind": "narration",
                    "status": "completed",
                    "detail": "I found the workbook and am checking its formulas.",
                },
            ],
        }
        mqtt_bridge._publish_or_queue_task_event(
            DisconnectedMqtt(),
            {"scheme": "signal", "_client_route_id": "phone-1"},
            task,
            [],
        )
        published_payloads = []

        with (
            patch.object(mqtt_bridge, "desktop_id", return_value="desktop-1"),
            patch.object(mqtt_bridge, "desktop_name", return_value="Desktop"),
            patch.object(mqtt_bridge, "mobile_connector_agents", return_value=[]),
            patch.object(
                mqtt_bridge,
                "_publish_phone_payload",
                side_effect=lambda _client, _wire, payload, **_kwargs: published_payloads.append(payload) or True,
            ),
        ):
            mqtt_bridge.flush_pending_task_events(ConnectedMqtt())

        self.assertNotIn("task-reconnect-progress", mqtt_bridge.pending_task_events)
        self.assertEqual(
            ["narration-1"],
            [event["event_id"] for event in published_payloads[0]["events"]],
        )

    def test_live_readable_progress_uses_best_effort_phone_delivery(self):
        task = {
            "task_id": "task-live-progress",
            "status": "running",
            "status_seq": 2,
            "updated_at": 200,
            "client_route_id": "phone-1",
            "client_conversation_id": "conversation-1",
            "client_turn_id": "phone-turn-1",
            "events": [{
                "event_id": "narration-1",
                "kind": "narration",
                "status": "completed",
                "title": "Checking the worksheet",
                "detail": "I am checking each answer before annotating the image.",
            }],
        }

        with (
            patch.object(mqtt_bridge, "desktop_id", return_value="desktop-1"),
            patch.object(mqtt_bridge, "desktop_name", return_value="Desktop"),
            patch.object(mqtt_bridge, "_publish_phone_payload", return_value=True) as publish,
        ):
            published = mqtt_bridge._publish_or_queue_task_event(
                ConnectedMqtt(),
                {"scheme": "signal", "_client_route_id": "phone-1"},
                task,
                [],
            )

        self.assertTrue(published)
        self.assertFalse(publish.call_args.kwargs["durable"])

    def test_status_only_heartbeat_does_not_enter_reliable_backlog(self):
        task = {
            "task_id": "task-heartbeat",
            "status": "running",
            "status_seq": 2,
            "updated_at": 200,
            "client_route_id": "phone-1",
            "client_conversation_id": "conversation-1",
            "client_turn_id": "phone-turn-1",
            "events": [],
        }

        with (
            patch.object(mqtt_bridge, "desktop_id", return_value="desktop-1"),
            patch.object(mqtt_bridge, "desktop_name", return_value="Desktop"),
            patch.object(mqtt_bridge, "_publish_phone_payload", return_value=True) as publish,
        ):
            published = mqtt_bridge._publish_or_queue_task_event(
                ConnectedMqtt(),
                {"scheme": "signal", "_client_route_id": "phone-1"},
                task,
                [],
            )

        self.assertTrue(published)
        self.assertFalse(publish.call_args.kwargs["durable"])

    def test_queued_task_event_resolves_identity_only_when_flushed(self):
        task = {
            "task_id": "task-reconnect",
            "status": "running",
            "status_seq": 5,
            "updated_at": 200,
            "client_route_id": "phone-1",
            "client_conversation_id": "conversation-1",
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
            "client_route_id": "phone-1",
            "client_conversation_id": "conversation-1",
            "client_turn_id": "phone-turn-1",
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
            "client_route_id": "phone-1",
            "client_conversation_id": "conversation-1",
            "client_turn_id": "phone-turn-1",
        }
        stale = {
            "task_id": "task-order",
            "status": "running",
            "status_seq": 8,
            "updated_at": 250,
            "client_route_id": "phone-1",
            "client_conversation_id": "conversation-1",
            "client_turn_id": "phone-turn-1",
        }

        route = {"_client_route_id": "phone-1"}
        mqtt_bridge._publish_or_queue_task_event(DisconnectedMqtt(), route, terminal, [])
        mqtt_bridge._publish_or_queue_task_event(DisconnectedMqtt(), route, stale, [])

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

    def test_failed_codex_task_explains_model_capacity(self):
        self.assertEqual(
            "Codex \u6240\u9009\u6a21\u578b\u5f53\u524d\u5bb9\u91cf\u5df2\u6ee1\uff0c\u8bf7\u7a0d\u540e\u91cd\u8bd5\u6216\u9009\u62e9\u5176\u4ed6 Codex \u6a21\u578b\u3002",
            mqtt_bridge._codex_terminal_result(
                "\u67e5\u8be2\u62cd\u6444\u5730\u70b9",
                "failed",
                "",
                "Selected model is at capacity. Please try a different model.",
            ),
        )

    def test_task_control_requires_exact_paired_route_and_message(self):
        task = SimpleNamespace(
            task_id="task-a",
            client_route_id="client-a",
            client_conversation_id="conversation-a",
            client_turn_id="turn-a",
            contact_id="codex",
            source_message_id="42",
        )

        self.assertTrue(
            mqtt_bridge._task_control_matches(
                task,
                client_route_id="client-a",
                conversation_id="conversation-a",
                task_id="task-a",
                turn_id="turn-a",
                contact_id="codex",
                source_message_id="42",
            )
        )
        self.assertFalse(
            mqtt_bridge._task_control_matches(
                task,
                client_route_id="client-b",
                conversation_id="conversation-a",
                task_id="task-a",
                turn_id="turn-a",
                contact_id="codex",
                source_message_id="42",
            )
        )
        self.assertFalse(
            mqtt_bridge._task_control_matches(
                task,
                client_route_id="client-a",
                conversation_id="conversation-b",
                task_id="task-a",
                turn_id="turn-a",
                contact_id="codex",
                source_message_id="42",
            )
        )
        self.assertFalse(
            mqtt_bridge._task_control_matches(
                task,
                client_route_id="client-a",
                conversation_id="conversation-a",
                task_id="task-a",
                turn_id="turn-b",
                contact_id="codex",
                source_message_id="42",
            )
        )

    def test_task_control_rejects_missing_current_route_identity(self):
        task = SimpleNamespace(
            task_id="task-a",
            client_route_id="",
            client_conversation_id="conversation-a",
            client_turn_id="turn-a",
            contact_id="codex",
            source_message_id="42",
        )

        self.assertFalse(
            mqtt_bridge._task_control_matches(
                task,
                client_route_id="client-a",
                conversation_id="conversation-a",
                task_id="task-a",
                turn_id="turn-a",
                contact_id="codex",
                source_message_id="42",
            )
        )
        self.assertFalse(
            mqtt_bridge._task_control_matches(
                SimpleNamespace(
                    task_id="task-a",
                    client_route_id="client-a",
                    client_conversation_id="conversation-a",
                    client_turn_id="turn-a",
                    contact_id="codex",
                    source_message_id="42",
                ),
                client_route_id="client-a",
                conversation_id="conversation-a",
                task_id="task-a",
                turn_id="turn-a",
                contact_id="codex",
                source_message_id="",
            )
        )

    def test_completed_result_is_queued_offline_and_flushed_after_reconnect(self):
        wire_payload = {"scheme": "signal", "_client_route_id": "phone-1"}
        payload = {
            "task_id": "task-1",
            "client_route_id": "phone-1",
            "conversation_id": "conversation-1",
            "turn_id": "turn-1",
            "contact_id": "contact-1", "source_message_id": "source-1", "agent_id": "codex",
            "content": "done",
        }

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
            "client_route_id": "client-1", "conversation_id": "conversation-1", "turn_id": "turn-1",
            "contact_id": "contact-1", "source_message_id": "source-1", "agent_id": "codex",
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
            "o" * 43,
            '{"encrypted":true}',
        )

        with patch.object(mqtt_bridge, "_publish_phone_payload") as publish:
            mqtt_bridge.flush_pending_task_results(object())

        publish.assert_not_called()
        self.assertEqual([], link_delivery.pending_task_results())
        self.assertEqual(1, len(link_delivery.pending_outbound()))


if __name__ == "__main__":
    unittest.main()
