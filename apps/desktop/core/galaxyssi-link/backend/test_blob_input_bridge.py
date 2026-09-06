from contextlib import ExitStack
from pathlib import Path
from types import SimpleNamespace
import unittest
from unittest.mock import Mock, patch

import blob_input_bridge
import link_protocol
import mqtt_bridge
from blob_crypto import binding_hash
from blob_input_contract import OFFER_TYPE, input_binding
from blob_input_receiver import BlobInputReceiver
from tests import test_mqtt_link_diagnostics as diagnostics_fixture
from test_blob_input_journal import input_manifest


class BlobMqttIngressTest(unittest.TestCase):
    setUp = diagnostics_fixture.MqttLinkDiagnosticsTests.setUp
    tearDown = diagnostics_fixture.MqttLinkDiagnosticsTests.tearDown

    def envelope(self):
        manifest = input_manifest(b"small input", route=self.client_route_id)
        offer = {"version": 1, "relay": "https://blob.test", "read_token": "c" * 64,
                 "private": {"version": 1, "blob_id": "d" * 32, "key": "e" * 64,
                             "nonce_prefix": "f" * 16, "size": manifest["size_bytes"],
                             "sha256": manifest["sha256"], "manifest_sha256": "1" * 64,
                             "binding_sha256": binding_hash(input_binding(manifest))}}
        return link_protocol.make_envelope({**manifest, "type": OFFER_TYPE, "blob_offer": offer},
            source_id=self.signal_name, target_id=self.desktop_id, conversation_id=manifest["conversation_id"])

    def patches(self, envelope, receiver, events):
        stack = ExitStack()
        for name, value in (("message_for_ciphertext", None), ("decrypt_signal_envelope", envelope),
                            ("claim_message", True), ("touch_client", None)):
            stack.enter_context(patch.object(mqtt_bridge, name, return_value=value))
        stack.enter_context(patch.object(blob_input_bridge, "_get_receiver", return_value=receiver))
        stack.enter_context(patch.object(mqtt_bridge, "bind_ciphertext", side_effect=lambda *_: events.append("bind")))
        stack.enter_context(patch.object(mqtt_bridge, "complete_message", side_effect=lambda *_: events.append("accepted")))
        stack.enter_context(patch.object(mqtt_bridge, "_publish_phone_payload", side_effect=lambda *_: events.append("ack")))
        return stack

    def test_actual_mqtt_ingress_persists_before_acceptance_and_never_dispatches_agent(self):
        events = []
        envelope = self.envelope()
        receiver = BlobInputReceiver(Path(self.temp.name) / "blob", configured_origin=lambda: "https://blob.test",
                                     peer_identity=lambda *_: self.phone_fingerprint, publish_receipt=lambda *_: True)
        enqueue = receiver.enqueue
        def persist(*args):
            result = enqueue(*args)
            self.assertEqual({"pending": 1}, receiver.journal.snapshot())
            events.append("persisted")
            return result
        with self.patches(envelope, receiver, events), patch.object(receiver, "enqueue", side_effect=persist), \
                patch.object(receiver, "start") as start, patch.object(mqtt_bridge, "_start_remote_agent_task") as agent:
            mqtt_bridge.on_message(object(), None, diagnostics_fixture.FakeMessage(self.topics.receive, self.wire, self.link_secret))
        self.assertEqual(["persisted", "bind", "accepted", "ack"], events)
        start.assert_called_once()
        agent.assert_not_called()

    def test_disk_failure_never_binds_replay_or_claims_accepted(self):
        receiver = SimpleNamespace(enqueue=Mock(side_effect=OSError("isolated disk error")), start=Mock())
        events = []
        with self.patches(self.envelope(), receiver, events):
            mqtt_bridge.on_message(object(), None, diagnostics_fixture.FakeMessage(self.topics.receive, self.wire, self.link_secret))
        self.assertEqual([], events)
        receiver.start.assert_not_called()

    def test_wrong_conversation_is_rejected_before_persistence(self):
        envelope = self.envelope()
        envelope["conversation_id"] = "different conversation"
        receiver = Mock()
        events = []
        with self.patches(envelope, receiver, events):
            mqtt_bridge.on_message(object(), None, diagnostics_fixture.FakeMessage(self.topics.receive, self.wire, self.link_secret))
        self.assertEqual([], events)
        receiver.enqueue.assert_not_called()

    def test_plain_chat_has_no_blob_store_or_worker_side_effect(self):
        envelope = link_protocol.make_envelope({"type": "text", "content": "你好"},
            source_id=self.signal_name, target_id=self.desktop_id, conversation_id="chat")
        with patch.object(blob_input_bridge, "_get_receiver") as create:
            self.assertFalse(blob_input_bridge.persist_before_ack(mqtt_bridge, envelope, self.client_route_id))
        create.assert_not_called()

    def test_recovery_starts_only_if_configured_or_persisted_jobs_exist(self):
        bridge = SimpleNamespace(DATA_DIR=Path(self.temp.name))
        with patch.dict("os.environ", {"GALAXYSSI_BLOB_RELAY_URL": ""}), \
                patch.object(blob_input_bridge, "_get_receiver") as create:
            blob_input_bridge.start(bridge)
            create.assert_not_called()
            (bridge.DATA_DIR / "blob-input").mkdir()
            (bridge.DATA_DIR / "blob-input" / "input-jobs.sqlite3").touch()
            blob_input_bridge.start(bridge)
            create.return_value.start.assert_called_once()
