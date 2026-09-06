from pathlib import Path
from tempfile import TemporaryDirectory
from types import SimpleNamespace
import unittest
from unittest.mock import Mock, patch

import blob_configuration
import mqtt_bridge
from blob_protocol import BlobError
from secure_state import SecureStateError


class BlobConfigurationTest(unittest.TestCase):
    def setUp(self):
        self.temp = TemporaryDirectory(prefix="blob-config-test-")
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.path = self.root / "config.secure.json"
        self.environment = {"GALAXYSSI_BLOB_RELAY_URL": "https://blob.test/",
                            "GALAXYSSI_BLOB_PROVISION_TOKEN": "a" * 64}

    def bridge(self, peer=None):
        if peer is not None:
            peer = {"signal_name": "phone", "identity_fingerprint": "e" * 64, **peer}
        return SimpleNamespace(DATA_DIR=self.root, desktop_id=lambda: "desktop",
            get_client=Mock(return_value=peer), _publish_to_registered_client=Mock())

    def test_encrypted_at_rest_and_revision_stable_when_unchanged(self):
        first = blob_configuration.configuration(self.path, environ=self.environment)
        raw = self.path.read_text()
        self.assertNotIn("a" * 64, raw)
        self.assertNotIn("https://blob.test", raw)
        with patch.object(blob_configuration, "write_secure_json") as write:
            second = blob_configuration.configuration(self.path, environ=self.environment)
        write.assert_not_called()
        self.assertEqual(first, second)
        self.assertEqual("https://blob.test", first["origin"])

    def test_rotation_and_disable_increment_revision_despite_clock_rollback(self):
        with patch.object(blob_configuration.time, "time", return_value=1000):
            first = blob_configuration.configuration(self.path, environ=self.environment)
        with patch.object(blob_configuration.time, "time", return_value=999):
            rotated = blob_configuration.configuration(self.path, environ={**self.environment,
                "GALAXYSSI_BLOB_PROVISION_TOKEN": "b" * 64})
            disabled = blob_configuration.configuration(self.path, environ={})
        self.assertEqual(first["revision"] + 1, rotated["revision"])
        self.assertEqual(rotated["revision"] + 1, disabled["revision"])
        self.assertFalse(disabled["enabled"])
        self.assertEqual("", disabled["origin"])
        self.assertEqual("", disabled["provisioning_token"])

    def test_partial_configuration_is_disabled(self):
        value = blob_configuration.configuration(self.path, environ={"GALAXYSSI_BLOB_RELAY_URL": "https://blob.test"})
        self.assertFalse(value["enabled"])
        self.assertEqual("", value["origin"])

    def test_unsafe_origin_and_invalid_credential_never_persist(self):
        for replacement in ({"GALAXYSSI_BLOB_RELAY_URL": "http://blob.test"},
                            {"GALAXYSSI_BLOB_RELAY_URL": "https://blob.test/path"},
                            {"GALAXYSSI_BLOB_PROVISION_TOKEN": "bad"}):
            with self.subTest(replacement=replacement), self.assertRaises(BlobError):
                blob_configuration.configuration(self.path, environ={**self.environment, **replacement})
        self.assertFalse(self.path.exists())

    def test_corrupt_checkpoint_is_not_silently_regenerated(self):
        self.path.write_bytes(b"invalid")
        with self.assertRaises(SecureStateError):
            blob_configuration.configuration(self.path, environ=self.environment)
        self.assertEqual(b"invalid", self.path.read_bytes())

    def test_unknown_client_never_reads_or_publishes_credentials(self):
        bridge = self.bridge()
        with patch.object(blob_configuration, "configuration") as read:
            self.assertFalse(blob_configuration.publish_configuration(bridge, object(), "unknown"))
        read.assert_not_called()
        bridge._publish_to_registered_client.assert_not_called()

    def test_paired_client_gets_durable_identity_bound_control_only(self):
        peer = {"local_identity_fingerprint": "f" * 64}
        bridge = self.bridge(peer)
        mqttc = object()
        with patch.dict("os.environ", self.environment):
            self.assertTrue(blob_configuration.publish_configuration(bridge, mqttc, "r" * 22))
        args, kwargs = bridge._publish_to_registered_client.call_args
        self.assertIs(mqttc, args[0]); self.assertEqual(peer["local_identity_fingerprint"], args[1]["local_identity_fingerprint"])
        payload = args[2]
        self.assertEqual("blob_relay_config", payload["type"])
        self.assertEqual("r" * 22, payload["client_route_id"])
        self.assertEqual("desktop", payload["desktop_id"])
        self.assertEqual("f" * 64, payload["desktop_fingerprint"])
        self.assertEqual("control", args[3]); self.assertTrue(kwargs["durable"])
        self.assertNotIn("content", payload)

    def test_persist_failure_never_publishes_uncommitted_revision(self):
        bridge = self.bridge({"local_identity_fingerprint": "f" * 64})
        with patch.dict("os.environ", self.environment), \
                patch.object(blob_configuration, "write_secure_json", side_effect=OSError("disk")), \
                self.assertRaises(OSError):
            blob_configuration.publish_configuration(bridge, object(), "r" * 22)
        bridge._publish_to_registered_client.assert_not_called()

    def test_status_request_without_opt_in_does_not_send_private_configuration(self):
        self.schedule_check(enabled=False, expected=["status"])

    def test_opt_in_sends_configuration_before_status(self):
        self.schedule_check(enabled=True, expected=["configuration", "status"])

    def test_configuration_failure_does_not_block_status_or_log_secret(self):
        with self.assertLogs(mqtt_bridge.log.name, level="WARNING") as logs:
            self.schedule_check(enabled=True, expected=["configuration", "status"], fail=True)
        self.assertNotIn("private-test-token", " ".join(logs.output))

    def schedule_check(self, *, enabled, expected, fail=False):
        events = []
        slots = Mock()
        slots.acquire.return_value = True
        def configuration(*args):
            events.append("configuration")
            if fail:
                raise OSError("private-test-token")
        def status(*args, **kwargs):
            events.append("status")
            return {"ok": True}
        with patch.object(mqtt_bridge, "CONNECTOR_STATUS_SYNC_SLOTS", slots), \
                patch.object(mqtt_bridge.threading, "Thread", side_effect=lambda target, **_: SimpleNamespace(start=target)), \
                patch.object(blob_configuration, "publish_configuration", side_effect=configuration), \
                patch.object(mqtt_bridge, "publish_connector_status", side_effect=status):
            self.assertTrue(mqtt_bridge._schedule_requested_connector_state(object(), "route",
                include_capability_manifest=False, include_blob_configuration=enabled))
        self.assertEqual(expected, events)
        slots.release.assert_called_once()
