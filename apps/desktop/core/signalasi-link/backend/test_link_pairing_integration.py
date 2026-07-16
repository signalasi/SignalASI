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
import pairing_state


class FakeInfo:
    def __init__(self, mid: int):
        self.mid = mid
        self.rc = 0


class FakeMqtt:
    def __init__(self):
        self.publishes = []
        self.subscriptions = []

    def publish(self, topic, payload, **kwargs):
        self.publishes.append((topic, json.loads(payload), kwargs))
        return FakeInfo(len(self.publishes))

    def subscribe(self, topic, **kwargs):
        self.subscriptions.append((topic, kwargs))


class FakeMessage:
    def __init__(self, topic: str, payload: dict):
        self.topic = topic
        self.payload = json.dumps(payload).encode("utf-8")


def client_claim(token: str, server_route: str, client_route: str, identity: bytes, name: str) -> dict:
    fingerprint = hashlib.sha256(identity).hexdigest()
    signal_name = f"signalasi:{fingerprint[:16]}"
    return {
        "type": "signalasi_pairing_claim",
        "protocol": link_protocol.PROTOCOL_NAME,
        "version": link_protocol.PROTOCOL_VERSION,
        "pairing_token": token,
        "server_route_id": server_route,
        "client_route_id": client_route,
        "client_name": name,
        "platform": "android",
        "from": signal_name,
        "signalasi_id": signal_name,
        "signal_name": signal_name,
        "signal_device_id": 1,
        "identity_fingerprint": fingerprint,
        "signal_bundle": {"identityKey": base64.b64encode(identity).decode("ascii")},
    }


class LinkPairingIntegrationTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.state_patch = patch.object(pairing_state, "STATE_PATH", Path(self.temp.name) / "registry.json")
        self.state_patch.start()
        pairing_state._tokens.clear()
        self.mqtt = FakeMqtt()
        self.bundle = {"identityKeySha256": "d" * 64}
        self.patches = [
            patch.object(mqtt_bridge, "replace_peer_signal_bundle", return_value={"ok": True}),
            patch.object(mqtt_bridge, "get_signal_bundle", return_value=self.bundle),
            patch.object(mqtt_bridge, "desktop_id", return_value="desktop_" + "d" * 16),
            patch.object(mqtt_bridge, "desktop_name", return_value="Test Desktop"),
            patch.object(mqtt_bridge, "mobile_connector_agents", return_value=[]),
            patch.object(mqtt_bridge.threading, "Timer", _ImmediateTimer),
        ]
        for item in self.patches:
            item.start()

    def tearDown(self):
        for item in reversed(self.patches):
            item.stop()
        self.state_patch.stop()
        self.temp.cleanup()

    def test_two_clients_pair_without_replacement_and_revoke_independently(self):
        server = pairing_state.server_route_id()
        first_route = link_protocol.new_route_id()
        second_route = link_protocol.new_route_id()
        for route, identity, name in (
            (first_route, b"first identity", "First phone"),
            (second_route, b"second identity", "Second phone"),
        ):
            pairing = pairing_state.new_pairing_session()
            claim = client_claim(pairing["token"], server, route, identity, name)
            wire = link_protocol.encrypt_pairing_claim(claim, pairing["token"], pairing["secret"], server)
            mqtt_bridge.on_message(
                self.mqtt, None, FakeMessage(link_protocol.LinkTopics(server).pairing, wire)
            )
        status = pairing_state.pairing_status()
        self.assertEqual(2, status["client_count"])
        self.assertNotEqual(status["clients"][0]["topics"]["up"], status["clients"][1]["topics"]["up"])
        self.assertFalse(any(item[1].get("type") == "pairing_revoked" for item in self.mqtt.publishes))
        pairing_state.revoke_client(first_route)
        self.assertIsNone(pairing_state.get_client(first_route))
        self.assertIsNotNone(pairing_state.get_client(second_route))

    def test_mqtt_reconnect_publishes_one_recovery_presence(self):
        with (
            patch.object(mqtt_bridge, "_subscribe_all_routes") as subscribe,
            patch.object(mqtt_bridge.agent_task_manager, "drain_recovered", return_value=[]),
            patch.object(mqtt_bridge, "flush_pending_task_events") as flush_events,
            patch.object(mqtt_bridge, "flush_outbound_messages") as flush_messages,
            patch.object(mqtt_bridge, "publish_connector_status", return_value={"ok": True}) as publish_status,
        ):
            mqtt_bridge.on_connect(self.mqtt, None, None, 0)

        subscribe.assert_called_once_with(self.mqtt)
        flush_events.assert_called_once_with(self.mqtt)
        flush_messages.assert_called_once_with(self.mqtt)
        publish_status.assert_called_once_with(self.mqtt, reason="mqtt_connected")

    def test_delivery_ack_preserves_phone_source_message_id(self):
        ack = mqtt_bridge.accepted_delivery_ack_payload(
            {"source_message_id": "42"},
            "signal-envelope-uuid",
            [{"stage": "desktop_received"}],
        )
        self.assertEqual("42", ack["source_message_id"])
        self.assertEqual("signal-envelope-uuid", ack["message_id"])


class _ImmediateTimer:
    def __init__(self, interval, function, args=(), kwargs=None):
        self.function = function
        self.args = args
        self.kwargs = kwargs or {}
        self.daemon = False

    def start(self):
        # Capability publication is independently covered; avoid Signal crypto in this pairing test.
        return None


if __name__ == "__main__":
    unittest.main()
