"""Opt-in public MQTT transport probe. No production identities or Signal sessions."""
import json
import os
import secrets
import threading
import time
import unittest
from unittest.mock import patch

import paho.mqtt.client as mqtt
import device_identity
import link_protocol
import mqtt_bridge
import pairing_state
import test_link_pairing_integration as fixtures


@unittest.skipUnless(os.environ.get("GALAXYSSI_LIVE_PAIRING_TEST") == "1", "live broker opt-in")
class LivePairingBrokerTest(unittest.TestCase):
    setUp = fixtures.LinkPairingIntegrationTests.setUp
    tearDown = fixtures.LinkPairingIntegrationTests.tearDown

    def test_lost_confirmation_then_immediate_first_packet_round_trip(self):
        pairing = pairing_state.new_pairing_session()
        route = link_protocol.new_route_id()
        claim = fixtures.client_claim(pairing["token"], route, secrets.token_bytes(32), "Test phone")
        secret = link_protocol.derive_link_secret(
            pairing["secret"], self.bundle["identityKeySha256"], claim["identity_fingerprint"],
        )
        topics = link_protocol.LinkTopics(
            secret, self.bundle["identityKeySha256"], claim["identity_fingerprint"],
        )
        marker = secrets.token_hex(16)
        desktop_ready, phone_ready = threading.Event(), threading.Event()
        dropped, received, completed = threading.Event(), threading.Event(), threading.Event()
        errors, times = [], {}
        confirmations = []
        desktop = mqtt.Client(mqtt.CallbackAPIVersion.VERSION2, client_id=secrets.token_hex(16))
        phone = mqtt.Client(mqtt.CallbackAPIVersion.VERSION2, client_id=secrets.token_hex(16))

        def desktop_message(client, userdata, message):
            try:
                payload = json.loads(link_protocol.open_wire_packet(message.payload, secret))
                if payload == {"probe": marker}:
                    times["received"] = time.monotonic()
                    received.set()
                    client.publish(topics.send, link_protocol.seal_wire_packet(
                        json.dumps({"receipt": marker}), secret,
                    ), qos=1)
            except Exception as error:
                errors.append(str(error))
                completed.set()

        def phone_message(client, userdata, message):
            try:
                payload = json.loads(link_protocol.open_wire_packet(message.payload, secret))
                if payload.get("type") == "pairing_confirmed":
                    confirmations.append(payload)
                    if len(confirmations) == 1:
                        dropped.set()
                        return
                    times["first_send"] = time.monotonic()
                    client.publish(topics.receive, link_protocol.seal_wire_packet(
                        json.dumps({"probe": marker}), secret,
                    ), qos=1)
                elif payload == {"receipt": marker}:
                    times["completed"] = time.monotonic()
                    completed.set()
            except Exception as error:
                errors.append(str(error))
                completed.set()

        def phone_connect(client, userdata, flags, reason, properties):
            client.subscribe(topics.send, qos=1)

        desktop.on_connect = lambda *args: desktop_ready.set()
        desktop.on_subscribe = mqtt_bridge.on_subscribe
        desktop.on_message = desktop_message
        phone.on_connect = phone_connect
        phone.on_subscribe = lambda *args: phone_ready.set()
        phone.on_message = phone_message
        clients = (desktop, phone)
        try:
            for client in clients:
                client.tls_set()  # System trust store and hostname verification stay enabled.
                client.connect("broker.emqx.io", 8883, keepalive=30)
                client.loop_start()
            self.assertTrue(desktop_ready.wait(30), "Desktop broker connection timed out")
            self.assertTrue(phone_ready.wait(30), "Phone downlink SUBACK timed out")
            with patch.object(device_identity, "desktop_device_profile", return_value={
                "display_name": "Isolated test desktop", "device_name": "test",
            }):
                mqtt_bridge.handle_pairing_claim(desktop, claim)
                self.assertTrue(dropped.wait(30), "Initial confirmation was not delivered")
                mqtt_bridge.handle_pairing_claim(desktop, claim)
                self.assertTrue(completed.wait(30), "First packet receipt was not delivered")
            self.assertFalse(errors, errors)
            self.assertTrue(received.is_set())
            self.assertEqual(2, len(confirmations))
            self.assertEqual(confirmations[0]["message_id"], confirmations[1]["message_id"])
            print(json.dumps({
                "broker": "broker.emqx.io:8883", "tls_verified": True,
                "confirmation_replay": True, "first_packet_received": True,
                "first_packet_round_trip_ms": round(
                    (times["completed"] - times["first_send"]) * 1000, 1,
                ),
                "scope": "opaque transport only; not Android UI or inner Signal session",
            }))
        finally:
            for client in clients:
                client.disconnect()
                client.loop_stop()
