"""Authenticated phone receipts must commit before the MQTT replay/ACK boundary."""
from contextlib import ExitStack
from pathlib import Path
import unittest
from unittest.mock import patch

from blob_artifact_contract import stored_receipt
import blob_artifact_ingress as ingress
from blob_artifact_journal import BlobArtifactJournal
import link_protocol
import mqtt_bridge
from test_blob_artifact_journal import artifact_job
from tests import test_mqtt_link_diagnostics as fixture


class ArtifactReceiptIngressTests(unittest.TestCase):
    setUp = fixture.MqttLinkDiagnosticsTests.setUp
    tearDown = fixture.MqttLinkDiagnosticsTests.tearDown

    def prepare(self):
        self.journal = BlobArtifactJournal(Path(self.temp.name) / "blob-output" / "artifact-jobs.sqlite3")
        self.body = artifact_job(client_route_id=self.client_route_id, desktop_id=self.desktop_id)
        self.body.update(source_id=self.signal_name, peer_fingerprint=self.phone_fingerprint,
                         local_fingerprint=self.desktop_fingerprint)
        self.journal.enqueue(self.body, now=0)
        return self.envelope()

    def envelope(self):
        return link_protocol.make_envelope(stored_receipt(self.body["manifest"]),
            source_id=self.signal_name, target_id=self.desktop_id,
            conversation_id=self.body["manifest"]["conversation_id"])

    def dispatch(self, envelope, events, fail_disk=False):
        original = BlobArtifactJournal.accept_receipt
        def commit(journal, *args, **kwargs):
            if fail_disk:
                raise OSError("receipt test disk failure")
            result = original(journal, *args, **kwargs)
            if result:
                events.append("committed")
            return result
        with ExitStack() as stack:
            stack.enter_context(patch.object(mqtt_bridge, "DATA_DIR", Path(self.temp.name)))
            stack.enter_context(patch.object(BlobArtifactJournal, "accept_receipt", commit))
            for name, value in (("message_for_ciphertext", None), ("decrypt_signal_envelope", envelope),
                                ("claim_message", True), ("touch_client", None)):
                stack.enter_context(patch.object(mqtt_bridge, name, return_value=value))
            for name, event in (("bind_ciphertext", "bound"), ("complete_message", "accepted"),
                                ("_publish_phone_payload", "ack")):
                stack.enter_context(patch.object(mqtt_bridge, name,
                    side_effect=lambda *_, event=event: events.append(event)))
            agent = stack.enter_context(patch.object(mqtt_bridge, "_start_remote_agent_task"))
            mqtt_bridge.on_message(object(), None,
                fixture.FakeMessage(self.topics.receive, self.wire, self.link_secret))
            agent.assert_not_called()

    def test_actual_ingress_commits_cleanup_before_transport_ack(self):
        events = []
        self.dispatch(self.prepare(), events)
        self.assertEqual(["committed", "bound", "accepted", "ack"], events)
        self.assertEqual("cleanup", self.journal.claim_due(1)[0]["phase"])

    def test_receipt_commit_failure_is_retryable_not_claimed_as_delivered(self):
        events = []
        self.dispatch(self.prepare(), events, fail_disk=True)
        self.assertEqual([], events)
        self.assertEqual("upload", self.journal.claim_due(1)[0]["phase"])

    def test_foreign_conversation_receipt_is_consumed_but_cannot_release_source(self):
        envelope = self.prepare()
        envelope["conversation_id"] = "different-conversation"
        events = []
        self.dispatch(envelope, events)
        self.assertEqual(["bound", "accepted", "ack"], events)
        self.assertEqual("upload", self.journal.claim_due(1)[0]["phase"])

    def test_changed_pair_identity_cannot_ack_old_job(self):
        envelope = self.prepare()
        self.client["identity_fingerprint"] = "f" * 64
        events = []
        self.dispatch(envelope, events)
        self.assertEqual(["bound", "accepted", "ack"], events)
        self.assertEqual("upload", self.journal.claim_due(1)[0]["phase"])

    def test_wrong_hash_cannot_release_source(self):
        envelope = self.prepare()
        envelope["payload"]["sha256"] = "0" * 64
        events = []
        self.dispatch(envelope, events)
        self.assertEqual(["bound", "accepted", "ack"], events)
        self.assertEqual("upload", self.journal.claim_due(1)[0]["phase"])

    def test_duplicate_receipts_preserve_current_cleanup_claim(self):
        self.dispatch(self.prepare(), [])
        current = self.journal.claim_due(1)[0]
        self.dispatch(self.envelope(), [])
        self.assertTrue(self.journal.current(current))
        self.assertEqual("cleanup", current["phase"])

    def test_unknown_receipt_does_not_create_store(self):
        self.body = artifact_job(client_route_id=self.client_route_id)
        events = []
        self.dispatch(self.envelope(), events)
        self.assertEqual(["bound", "accepted", "ack"], events)
        self.assertFalse((Path(self.temp.name) / "blob-output").exists())

    def test_plain_chat_performs_no_store_or_pair_lookup(self):
        with patch.object(mqtt_bridge, "get_client") as peer:
            result = ingress.persist_receipt_before_ack(mqtt_bridge,
                {"payload": {"type": "text", "content": "hello"}}, self.client_route_id)
            self.assertIsNone(result)
            peer.assert_not_called()


if __name__ == "__main__":
    unittest.main()
