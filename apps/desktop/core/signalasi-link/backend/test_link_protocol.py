from __future__ import annotations

import json
import tempfile
import time
import unittest
import uuid
from pathlib import Path
from unittest.mock import patch

import link_delivery
import link_protocol
import pairing_state


class LinkProtocolTests(unittest.TestCase):
    def test_route_ids_are_128_bit_base64url(self):
        values = {link_protocol.new_route_id() for _ in range(100)}
        self.assertEqual(100, len(values))
        self.assertTrue(all(link_protocol.valid_route_id(value) for value in values))

    def test_topics_parse_and_reject_legacy(self):
        server_id = link_protocol.new_route_id()
        client_id = link_protocol.new_route_id()
        topics = link_protocol.LinkTopics(server_id, client_id)
        self.assertEqual((server_id, client_id, "up"), link_protocol.parse_topic(topics.up))
        self.assertEqual((server_id, client_id, "control"), link_protocol.parse_topic(topics.control))
        self.assertIsNone(link_protocol.parse_topic("signalasichat/android/send"))

    def test_application_envelope_validation(self):
        now = int(time.time() * 1000)
        envelope = link_protocol.make_envelope(
            {"type": "text", "content": "hello"}, source_id="source", target_id="target"
        )
        self.assertEqual(envelope, link_protocol.validate_envelope(envelope, now))
        envelope["message_id"] = "not-a-uuid"
        with self.assertRaises(ValueError):
            link_protocol.validate_envelope(envelope, now)

    def test_oversized_text_is_rejected_before_encryption(self):
        with self.assertRaises(ValueError):
            link_protocol.make_envelope(
                {"type": "text", "content": "x" * (link_protocol.MAX_TEXT_BYTES + 1)},
                source_id="source",
                target_id="target",
            )

    def test_pairing_claim_is_confidential_and_bound_to_route(self):
        token = "token-value"
        secret = link_protocol._b64url_encode(b"k" * 32)
        server_route = link_protocol.new_route_id()
        claim = {"type": "signalasi_pairing_claim", "client_name": "Private phone name"}
        wire = link_protocol.encrypt_pairing_claim(claim, token, secret, server_route)
        self.assertNotIn("Private phone name", json.dumps(wire))
        self.assertEqual(claim, link_protocol.decrypt_pairing_claim(wire, secret))
        wire["server_route_id"] = link_protocol.new_route_id()
        with self.assertRaises(Exception):
            link_protocol.decrypt_pairing_claim(wire, secret)


class PairingRegistryTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.state_path = Path(self.temp.name) / "registry.json"
        self.delivery_path = Path(self.temp.name) / "delivery.db"
        self.state_patch = patch.object(pairing_state, "STATE_PATH", self.state_path)
        self.delivery_patch = patch.object(link_delivery, "DB_PATH", self.delivery_path)
        self.state_patch.start()
        self.delivery_patch.start()
        pairing_state._tokens.clear()

    def tearDown(self):
        self.delivery_patch.stop()
        self.state_patch.stop()
        self.temp.cleanup()

    def test_multiple_clients_coexist_and_revoke_independently(self):
        first_id = link_protocol.new_route_id()
        second_id = link_protocol.new_route_id()
        pairing_state.record_pairing_success("a" * 64, "signalasi:first", client_route_id=first_id)
        pairing_state.record_pairing_success("b" * 64, "signalasi:second", client_route_id=second_id)
        self.assertEqual(2, pairing_state.pairing_status()["client_count"])
        pairing_state.revoke_client(first_id)
        self.assertIsNone(pairing_state.get_client(first_id))
        self.assertIsNotNone(pairing_state.get_client(second_id))
        self.assertTrue(pairing_state.is_paired())

    def test_identity_lookup_only_returns_active_replaced_routes(self):
        first_id = link_protocol.new_route_id()
        second_id = link_protocol.new_route_id()
        other_id = link_protocol.new_route_id()
        fingerprint = "a" * 64
        pairing_state.record_pairing_success(
            fingerprint,
            "signalasi:shared",
            client_route_id=first_id,
        )
        pairing_state.record_pairing_success(
            fingerprint,
            "signalasi:shared",
            client_route_id=second_id,
        )
        pairing_state.record_pairing_success(
            "b" * 64,
            "signalasi:other",
            client_route_id=other_id,
        )

        matches = pairing_state.clients_for_identity(
            fingerprint,
            "signalasi:shared",
            exclude_route_id=second_id,
        )
        self.assertEqual([first_id], [client["client_route_id"] for client in matches])
        pairing_state.revoke_client(first_id, "replaced_by_new_pairing")
        self.assertEqual(
            [],
            pairing_state.clients_for_identity(
                fingerprint,
                "signalasi:shared",
                exclude_route_id=second_id,
            ),
        )

    def test_pairing_token_is_one_time(self):
        token = pairing_state.new_pairing_token()
        self.assertTrue(pairing_state.validate_pairing_token(token, consume=True))
        self.assertFalse(pairing_state.validate_pairing_token(token, consume=True))

    def test_duplicate_message_is_claimed_once(self):
        route_id = link_protocol.new_route_id()
        message_id = str(uuid.uuid4())
        self.assertTrue(link_delivery.claim_message(route_id, message_id))
        self.assertFalse(link_delivery.claim_message(route_id, message_id))
        link_delivery.complete_message(route_id, message_id, "accepted", {"status": "accepted"})
        self.assertEqual("accepted", link_delivery.previous_acknowledgement(route_id, message_id)["status"])

    def test_outbound_message_survives_until_client_ack(self):
        route_id = link_protocol.new_route_id()
        message_id = str(uuid.uuid4())
        link_delivery.queue_outbound(route_id, message_id, "topic/down", '{"ciphertext":true}')
        self.assertEqual(1, len(link_delivery.pending_outbound()))
        self.assertEqual("queued", link_delivery.outbound_status(route_id, message_id))
        link_delivery.mark_outbound_published(route_id, message_id)
        self.assertEqual([], link_delivery.pending_outbound())
        self.assertEqual("published", link_delivery.outbound_status(route_id, message_id))
        link_delivery.acknowledge_outbound(route_id, message_id)
        self.assertEqual([], link_delivery.pending_outbound())
        self.assertIsNone(link_delivery.outbound_status(route_id, message_id))


if __name__ == "__main__":
    unittest.main()
