"""Pairing must not advertise readiness before the phone uplink is subscribed."""
import json
import unittest
from unittest.mock import patch

import link_protocol
import mqtt_bridge
import pairing_state
import test_link_pairing_integration as fixtures

client_claim = fixtures.client_claim


class DelayedMqtt(fixtures.FakeMqtt):
    def subscribe(self, topics, **kwargs):
        self.subscriptions.append((topics, kwargs))
        return 0, len(self.subscriptions)


class PairingFirstMessageTests(unittest.TestCase):
    setUp = fixtures.LinkPairingIntegrationTests.setUp
    tearDown = fixtures.LinkPairingIntegrationTests.tearDown

    def pair_delayed(self):
        self.mqtt = DelayedMqtt()
        pairing = pairing_state.new_pairing_session()
        route = link_protocol.new_route_id()
        claim = client_claim(pairing["token"], route, b"new phone", "Phone")
        mqtt_bridge.handle_pairing_claim(self.mqtt, claim)
        return pairing, route, claim

    def ack_route(self, route, accepted=True):
        client = pairing_state.get_client(route)
        subscriptions = tuple(
            (topic, route) for topic in mqtt_bridge._topics_for_client(client).receive_window
        )
        mqtt_bridge._activate_subscription_acknowledgements(
            subscriptions, tuple(accepted for _ in subscriptions),
        )

    def test_confirmation_waits_for_suback_then_publishes_immediately(self):
        _, route, _ = self.pair_delayed()
        self.assertEqual([], self.mqtt.publishes)
        self.ack_route(route)
        self.assertEqual(1, len(self.mqtt.publishes))
        self.assertEqual("pairing_confirmed", self.mqtt.publishes[0][1]["type"])
        required = mqtt_bridge._topics_for_client(pairing_state.get_client(route)).receive_window
        self.assertTrue(all(mqtt_bridge.mqtt_subscription_active.get(t) == route for t in required))

    def test_rejected_subscription_cannot_advertise_ready(self):
        _, route, _ = self.pair_delayed()
        self.ack_route(route, accepted=False)
        self.assertEqual([], self.mqtt.publishes)
        self.ack_route(route)
        self.assertEqual(1, len(self.mqtt.publishes))

    def test_duplicate_claim_before_suback_coalesces_confirmation(self):
        _, route, claim = self.pair_delayed()
        mqtt_bridge.handle_pairing_claim(self.mqtt, claim)
        self.assertEqual(1, len(mqtt_bridge.mqtt_pairing_confirmations))
        self.ack_route(route)
        self.assertEqual(1, len(self.mqtt.publishes))

    def test_revocation_before_suback_does_not_confirm_old_route(self):
        _, route, _ = self.pair_delayed()
        subscriptions = tuple(
            (t, route) for t in mqtt_bridge._topics_for_client(pairing_state.get_client(route)).receive_window
        )
        pairing_state.revoke_client(route)
        mqtt_bridge._activate_subscription_acknowledgements(subscriptions, (True,) * len(subscriptions))
        self.assertEqual([], self.mqtt.publishes)

    def test_one_phone_subscription_does_not_unlock_another(self):
        _, route, _ = self.pair_delayed()
        other_pairing = pairing_state.new_pairing_session()
        other_route = link_protocol.new_route_id()
        mqtt_bridge.handle_pairing_claim(self.mqtt, client_claim(
            other_pairing["token"], other_route, b"second phone", "Other",
        ))
        self.ack_route(other_route)
        self.assertEqual(1, len(self.mqtt.publishes))
        self.assertEqual(other_route, self.mqtt.publishes[0][1]["client_route_id"])
        self.ack_route(route)
        self.assertEqual(2, len(self.mqtt.publishes))

    def test_used_qr_sends_authenticated_rejection_without_changing_first_phone(self):
        pairing, route, _ = self.pair_delayed()
        other = client_claim(pairing["token"], link_protocol.new_route_id(), b"other identity", "Other")
        mqtt_bridge.handle_pairing_claim(self.mqtt, other)
        self.assertEqual(1, len(pairing_state.list_clients()))
        self.assertIsNotNone(pairing_state.get_client(route))
        secret = link_protocol.derive_link_secret(
            pairing["secret"], self.bundle["identityKeySha256"], other["identity_fingerprint"],
        )
        payload = json.loads(link_protocol.open_wire_packet(self.mqtt.publishes[-1][1], secret))
        self.assertEqual("pairing_rejected", payload["type"])
        self.assertEqual("token_bound", payload["reason"])
        self.assertEqual(other["client_route_id"], payload["client_route_id"])

    def test_reconnection_drops_stale_pending_confirmation_and_claim_can_resume(self):
        _, route, claim = self.pair_delayed()
        mqtt_bridge._reset_subscription_state()
        self.assertFalse(mqtt_bridge.mqtt_pairing_confirmations)
        mqtt_bridge.handle_pairing_claim(self.mqtt, claim)
        self.ack_route(route)
        self.assertEqual(1, len(self.mqtt.publishes))

    def test_real_suback_callback_unlocks_only_after_all_route_topics_are_accepted(self):
        _, route, _ = self.pair_delayed()
        pending = next(
            mid for mid, topics in mqtt_bridge.mqtt_subscription_pending.items()
            if any(owner == route for _, owner in topics)
        )
        mqtt_bridge.on_subscribe(self.mqtt, None, pending, [1, 128, 1])
        self.assertEqual([], self.mqtt.publishes)
        mqtt_bridge.reconcile_mqtt_subscriptions(self.mqtt, force=True)
        retry = next(
            mid for mid, topics in mqtt_bridge.mqtt_subscription_pending.items()
            if any(owner == route for _, owner in topics)
        )
        mqtt_bridge.on_subscribe(self.mqtt, None, retry, [1])
        self.assertEqual(1, len(self.mqtt.publishes))

    def test_lost_confirmation_publish_is_recovered_by_replayed_claim(self):
        _, route, claim = self.pair_delayed()
        with patch.object(self.mqtt, "publish", side_effect=OSError("disconnected")):
            self.ack_route(route)
        self.assertEqual([], self.mqtt.publishes)
        mqtt_bridge.handle_pairing_claim(self.mqtt, claim)
        self.assertEqual(1, len(self.mqtt.publishes))
        self.assertEqual(route, self.mqtt.publishes[0][1]["client_route_id"])


if __name__ == "__main__":
    unittest.main()
