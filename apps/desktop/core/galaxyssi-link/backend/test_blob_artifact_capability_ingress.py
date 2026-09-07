"""Exercise real MQTT dispatch and encrypted capability persistence ordering."""
from contextlib import ExitStack
from pathlib import Path
import unittest
from unittest.mock import patch

import blob_pair_configuration as settings
import link_protocol
import mqtt_bridge
from tests import test_mqtt_link_diagnostics as fixture


class ArtifactCapabilityIngressTests(unittest.TestCase):
    setUp = fixture.MqttLinkDiagnosticsTests.setUp
    tearDown = fixture.MqttLinkDiagnosticsTests.tearDown

    def envelope(self, **changes):
        payload = {"type": settings.ARTIFACT_CAPABILITY_TYPE, "version": 1, "revision": 1,
                   "enabled": True, "client_route_id": self.client_route_id,
                   "desktop_id": self.desktop_id, "desktop_fingerprint": self.desktop_fingerprint,
                   **changes}
        return link_protocol.make_envelope(payload, source_id=self.signal_name,
            target_id=self.desktop_id, conversation_id="blob-capabilities")

    def dispatch(self, envelope, events, fail_write=False):
        original = settings.write_secure_json
        def persist(*args, **kwargs):
            if fail_write:
                raise OSError("capability test disk failure")
            result = original(*args, **kwargs)
            events.append("persisted")
            return result
        with ExitStack() as stack:
            stack.enter_context(patch.object(mqtt_bridge, "DATA_DIR", Path(self.temp.name)))
            stack.enter_context(patch.object(settings, "write_secure_json", side_effect=persist))
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

    def test_capability_is_persisted_before_replay_binding_and_transport_ack(self):
        events = []
        self.dispatch(self.envelope(), events)
        self.assertEqual(["persisted", "bound", "accepted", "ack"], events)
        with patch.object(mqtt_bridge, "DATA_DIR", Path(self.temp.name)):
            self.assertTrue(settings.can_receive_artifacts(mqtt_bridge, self.client_route_id))

    def test_failed_disk_write_does_not_ack_or_poison_replay_tracking(self):
        events = []
        self.dispatch(self.envelope(), events, fail_write=True)
        self.assertEqual([], events)

    def test_wrong_desktop_does_not_persist_or_ack(self):
        events = []
        self.dispatch(self.envelope(desktop_id="wrong desktop"), events)
        self.assertEqual([], events)

    def test_delayed_report_is_acknowledged_without_changing_newer_disable(self):
        self.dispatch(self.envelope(revision=2, enabled=False), [])
        events = []
        self.dispatch(self.envelope(revision=1), events)
        self.assertEqual(["bound", "accepted", "ack"], events)
        with patch.object(mqtt_bridge, "DATA_DIR", Path(self.temp.name)):
            self.assertFalse(settings.can_receive_artifacts(mqtt_bridge, self.client_route_id))


if __name__ == "__main__":
    unittest.main()
