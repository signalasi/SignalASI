from __future__ import annotations

import base64
import hashlib
import json
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

import link_protocol
import mqtt_bridge
import pairing_access
import pairing_state
import desktop_control


class FakeInfo:
    def __init__(self, mid: int):
        self.mid = mid
        self.rc = 0


class FakeMqtt:
    def __init__(self):
        self.publishes = []
        self.subscriptions = []

    def publish(self, topic, payload, **kwargs):
        decoded = payload
        for paired_client in pairing_state.list_clients(include_revoked=True):
            try:
                decoded = json.loads(
                    link_protocol.open_wire_packet(
                        payload,
                        paired_client["link_secret"],
                    ).decode("utf-8")
                )
                break
            except Exception:
                continue
        self.publishes.append((topic, decoded, kwargs))
        return FakeInfo(len(self.publishes))

    def subscribe(self, topic, **kwargs):
        self.subscriptions.append((topic, kwargs))
        subscriptions = tuple(
            (item[0], mqtt_bridge._expected_subscriptions().get(item[0], ""))
            for item in topic
        )
        mqtt_bridge._activate_subscription_acknowledgements(
            subscriptions, tuple(True for _ in subscriptions)
        )

    def unsubscribe(self, topic):
        self.subscriptions = [item for item in self.subscriptions if item[0] != topic]
        return FakeInfo(len(self.publishes) + 1)


class FakeMessage:
    def __init__(self, topic: str, payload):
        self.topic = topic
        self.payload = (
            json.dumps(payload).encode("utf-8")
            if isinstance(payload, dict)
            else str(payload).encode("ascii")
        )


def client_claim(token: str, client_route: str, identity: bytes, name: str) -> dict:
    fingerprint = hashlib.sha256(identity).hexdigest()
    signal_name = f"galaxyssi:{fingerprint[:16]}"
    return {
        "type": "galaxyssi_pairing_claim",
        "protocol": link_protocol.PROTOCOL_NAME,
        "version": link_protocol.PROTOCOL_VERSION,
        "pairing_token": token,
        "client_route_id": client_route,
        "client_name": name,
        "platform": "android",
        "client_device_id": f"phone-{fingerprint[:12]}",
        "device_name": name,
        "device_manufacturer": "Samsung",
        "device_model": "SM-TEST",
        "platform_version": "17",
        "profile_name": "Me",
        "from": signal_name,
        "galaxyssi_id": signal_name,
        "signal_name": signal_name,
        "signal_device_id": 1,
        "identity_fingerprint": fingerprint,
        "signal_bundle": {"identityKey": base64.b64encode(identity).decode("ascii")},
    }


class LinkPairingIntegrationTests(unittest.TestCase):
    def setUp(self):
        mqtt_bridge._reset_subscription_state()
        _ImmediateTimer.created.clear()
        self.temp = tempfile.TemporaryDirectory()
        self.state_patch = patch.object(pairing_state, "STATE_PATH", Path(self.temp.name) / "registry.json")
        self.state_patch.start()
        pairing_state._tokens.clear()
        self.mqtt = FakeMqtt()
        self.bundle = {"identityKeySha256": "d" * 64}
        self.control = desktop_control.DesktopControlManager(
            Path(self.temp.name) / "desktop-control.json"
        )
        self.patches = [
            patch.object(mqtt_bridge, "replace_peer_signal_bundle", return_value={"ok": True}),
            patch.object(mqtt_bridge, "get_signal_bundle", return_value=self.bundle),
            patch.object(mqtt_bridge, "desktop_id", return_value="desktop_" + "d" * 16),
            patch.object(mqtt_bridge, "desktop_name", return_value="Test Desktop"),
            patch.object(mqtt_bridge, "mobile_connector_agents", return_value=[]),
            patch.object(mqtt_bridge, "publish_pairing_revoked", return_value={"ok": True}),
            patch.object(mqtt_bridge.threading, "Timer", _ImmediateTimer),
            patch.object(desktop_control, "desktop_control_manager", return_value=self.control),
        ]
        for item in self.patches:
            item.start()

    def tearDown(self):
        for item in reversed(self.patches):
            item.stop()
        self.state_patch.stop()
        self.temp.cleanup()

    def test_two_clients_pair_without_replacement_and_revoke_independently(self):
        first_route = link_protocol.new_route_id()
        second_route = link_protocol.new_route_id()
        for route, identity, name in (
            (first_route, b"first identity", "First phone"),
            (second_route, b"second identity", "Second phone"),
        ):
            pairing = pairing_state.new_pairing_session()
            claim = client_claim(pairing["token"], route, identity, name)
            wire = link_protocol.encrypt_pairing_claim(claim, pairing["secret"])
            mqtt_bridge.on_message(
                self.mqtt, None, FakeMessage(pairing["topic"], wire)
            )
        status = pairing_state.pairing_status()
        self.assertEqual(2, status["client_count"])
        first = pairing_state.get_client(first_route)
        second = pairing_state.get_client(second_route)
        self.assertNotEqual(
            mqtt_bridge._topics_for_client(first).send,
            mqtt_bridge._topics_for_client(second).send,
        )
        self.assertNotIn("link_secret", status["clients"][0])
        self.assertFalse(any(item[1].get("type") == "pairing_revoked" for item in self.mqtt.publishes))
        pairing_state.revoke_client(first_route)
        self.assertIsNone(pairing_state.get_client(first_route))
        self.assertIsNotNone(pairing_state.get_client(second_route))

    def test_duplicate_pairing_claim_replays_confirmation_without_replacing_the_route(self):
        route = link_protocol.new_route_id()
        pairing = pairing_state.new_pairing_session()
        claim = client_claim(pairing["token"], route, b"same retrying phone", "S26 Ultra")
        wire = link_protocol.encrypt_pairing_claim(claim, pairing["secret"])

        for _ in range(2):
            mqtt_bridge.on_message(
                self.mqtt,
                None,
                FakeMessage(pairing["topic"], wire),
            )

        confirmations = [
            payload
            for _, payload, _ in self.mqtt.publishes
            if payload.get("type") == "pairing_confirmed"
        ]
        self.assertEqual(1, pairing_state.pairing_status()["client_count"])
        self.assertEqual(2, len(confirmations))
        self.assertEqual(route, confirmations[-1]["client_route_id"])
        self.assertEqual(confirmations[0]["message_id"], confirmations[1]["message_id"])
        self.assertEqual([], _ImmediateTimer.created)

    def test_repairing_same_phone_rotates_only_its_route_and_keeps_alias(self):
        identity = b"same physical phone"
        first_route = link_protocol.new_route_id()
        first_pairing = pairing_state.new_pairing_session()
        first_claim = client_claim(first_pairing["token"], first_route, identity, "S26 Ultra")
        mqtt_bridge.on_message(
            self.mqtt,
            None,
            FakeMessage(
                first_pairing["topic"],
                link_protocol.encrypt_pairing_claim(first_claim, first_pairing["secret"]),
            ),
        )
        pairing_state.rename_client(first_route, "My S26U")

        second_route = link_protocol.new_route_id()
        second_pairing = pairing_state.new_pairing_session()
        second_claim = client_claim(second_pairing["token"], second_route, identity, "S26 Ultra")
        mqtt_bridge.on_message(
            self.mqtt,
            None,
            FakeMessage(
                second_pairing["topic"],
                link_protocol.encrypt_pairing_claim(second_claim, second_pairing["secret"]),
            ),
        )

        self.assertIsNone(pairing_state.get_client(first_route))
        replacement = pairing_state.get_client(second_route)
        self.assertEqual("My S26U", replacement["display_name"])
        self.assertTrue(replacement["user_renamed"])

    def test_pairing_token_is_the_authority_for_restricted_or_executor_access(self):
        restricted_route = link_protocol.new_route_id()
        restricted = pairing_state.new_pairing_session(
            pairing_access.grant_for_executor(False)
        )
        restricted_claim = client_claim(
            restricted["token"],
            restricted_route,
            b"restricted identity",
            "Restricted phone",
        )
        restricted_claim["pairing_access"] = pairing_access.grant_for_executor(True)
        mqtt_bridge.on_message(
            self.mqtt,
            None,
            FakeMessage(
                restricted["topic"],
                link_protocol.encrypt_pairing_claim(restricted_claim, restricted["secret"]),
            ),
        )
        stored_restricted = pairing_state.get_client(restricted_route)
        self.assertEqual(pairing_access.RESTRICTED, stored_restricted["access_profile"])
        self.assertFalse(pairing_access.has_full_executor(stored_restricted))

        executor_route = link_protocol.new_route_id()
        executor = pairing_state.new_pairing_session(
            pairing_access.grant_for_executor(True)
        )
        executor_claim = client_claim(
            executor["token"],
            executor_route,
            b"executor identity",
            "Executor phone",
        )
        mqtt_bridge.on_message(
            self.mqtt,
            None,
            FakeMessage(
                executor["topic"],
                link_protocol.encrypt_pairing_claim(executor_claim, executor["secret"]),
            ),
        )
        stored_executor = pairing_state.get_client(executor_route)
        self.assertEqual(pairing_access.DESKTOP_EXECUTOR, stored_executor["access_profile"])
        self.assertTrue(pairing_access.has_full_executor(stored_executor))

    def test_executor_pairing_activates_control_without_a_second_approval(self):
        self.control.update_settings(enabled=True)
        route = link_protocol.new_route_id()
        pairing = pairing_state.new_pairing_session(
            pairing_access.grant_for_executor(True, issued_at_millis=123_456)
        )
        offer = self.control.create_offer(pairing["token"])
        claim = client_claim(
            pairing["token"],
            route,
            b"one-time consent identity",
            "Trusted phone",
        )
        claim["desktop_control_authorization_token"] = offer["token"]

        mqtt_bridge.on_message(
            self.mqtt,
            None,
            FakeMessage(
                pairing["topic"],
                link_protocol.encrypt_pairing_claim(claim, pairing["secret"]),
            ),
        )

        status = self.control.status(route)
        self.assertEqual(1, status["active_count"])
        self.assertEqual(0, status["pending_count"])
        self.assertEqual("pairing_qr", status["authorizations"][0]["grant_source"])
        confirmation = next(
            item[1]
            for item in self.mqtt.publishes
            if item[1].get("type") == "pairing_confirmed"
        )
        self.assertEqual(
            "active",
            confirmation["desktop_control"]["authorization_status"],
        )

    def test_mqtt_post_connect_recovery_publishes_one_presence(self):
        with (
            patch.object(mqtt_bridge.agent_task_manager, "drain_recovered", return_value=[]),
            patch.object(mqtt_bridge, "flush_pending_task_events") as flush_events,
            patch.object(mqtt_bridge, "flush_outbound_messages") as flush_messages,
            patch.object(mqtt_bridge, "publish_connector_status", return_value={"ok": True}) as publish_status,
        ):
            mqtt_bridge._recover_after_mqtt_connect(self.mqtt)

        flush_events.assert_called_once_with(self.mqtt)
        flush_messages.assert_called_once_with(self.mqtt)
        publish_status.assert_called_once_with(self.mqtt, reason="mqtt_connected")

    def test_mqtt_reconnect_resumes_recoverable_task_for_its_paired_client(self):
        recovered = {
            "task_id": "task-1",
            "status": "recovering",
            "client_route_id": "client-1",
            "prompt": "continue",
        }
        with (
            patch.object(mqtt_bridge.agent_task_manager, "drain_recovered", return_value=[recovered]),
            patch.object(mqtt_bridge, "get_client", return_value={"client_route_id": "client-1"}),
            patch.object(mqtt_bridge, "_publish_or_queue_task_event") as publish_recovery,
            patch.object(mqtt_bridge, "_resume_recovered_remote_task") as resume,
            patch.object(mqtt_bridge.agent_task_manager, "retain_recovered") as retain,
            patch.object(mqtt_bridge, "flush_pending_task_events"),
            patch.object(mqtt_bridge, "flush_outbound_messages"),
            patch.object(mqtt_bridge, "publish_connector_status", return_value={"ok": True}),
        ):
            mqtt_bridge._recover_after_mqtt_connect(self.mqtt)

        publish_recovery.assert_called_once()
        recovery_wire, recovery_task, recovery_trace = publish_recovery.call_args.args[1:]
        self.assertEqual("client-1", recovery_wire["_client_route_id"])
        self.assertIs(recovered, recovery_task)
        self.assertEqual("desktop_task_recovery_started", recovery_trace[0]["stage"])
        resume.assert_called_once_with(self.mqtt, recovered)
        retain.assert_not_called()

    def test_mqtt_reconnect_retains_recovery_until_client_route_returns(self):
        recovered = {
            "task_id": "task-2",
            "status": "recovering",
            "client_route_id": "client-2",
            "prompt": "continue later",
        }
        with (
            patch.object(mqtt_bridge.agent_task_manager, "drain_recovered", return_value=[recovered]),
            patch.object(mqtt_bridge, "get_client", return_value=None),
            patch.object(mqtt_bridge, "_resume_recovered_remote_task") as resume,
            patch.object(mqtt_bridge.agent_task_manager, "retain_recovered") as retain,
            patch.object(mqtt_bridge, "flush_pending_task_events"),
            patch.object(mqtt_bridge, "flush_outbound_messages"),
            patch.object(mqtt_bridge, "publish_connector_status", return_value={"ok": True}),
        ):
            mqtt_bridge._recover_after_mqtt_connect(self.mqtt)

        resume.assert_not_called()
        retain.assert_called_once_with("task-2")

    def test_delivery_ack_separates_transport_and_client_message_ids(self):
        ack = mqtt_bridge.accepted_delivery_ack_payload(
            {"source_message_id": "42"},
            "signal-envelope-uuid",
            [{"stage": "desktop_received"}],
        )
        self.assertEqual("42", ack["source_message_id"])
        self.assertEqual("42", ack["client_source_message_id"])
        self.assertEqual("signal-envelope-uuid", ack["transport_message_id"])
        self.assertNotIn("message_id", ack)

    def test_delivery_ack_prefers_explicit_transport_message_id(self):
        self.assertEqual(
            "transport-uuid",
            mqtt_bridge.acknowledged_transport_message_id(
                {
                    "transport_message_id": "transport-uuid",
                    "source_message_id": "42",
                },
                {"reply_to": "fallback"},
            ),
        )

    def test_delivery_ack_does_not_fall_back_to_legacy_fields(self):
        self.assertEqual(
            "",
            mqtt_bridge.acknowledged_transport_message_id(
                {"source_message_id": "logical-message-id"},
                {"reply_to": "old-envelope-id"},
            ),
        )


class _ImmediateTimer:
    created = []

    def __init__(self, interval, function, args=(), kwargs=None):
        self.function = function
        self.args = args
        self.kwargs = kwargs or {}
        self.daemon = False
        self.created.append((interval, function, args, self.kwargs))

    def start(self):
        # Capability publication is independently covered; avoid Signal crypto in this pairing test.
        return None


if __name__ == "__main__":
    unittest.main()
