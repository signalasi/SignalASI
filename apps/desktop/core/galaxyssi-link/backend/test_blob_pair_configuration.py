import json
import os
from pathlib import Path
from tempfile import TemporaryDirectory
from types import SimpleNamespace
import unittest
from unittest.mock import Mock, patch

from blob_configuration import publish_configuration
import blob_pair_configuration as settings
from blob_protocol import BlobError
from secure_state import SecureStateError


class PairConfigurationFixture(unittest.TestCase):
    def setUp(self):
        self.temp = TemporaryDirectory(prefix="blob-pair-settings-")
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.routes = ["a" * 22, "b" * 22]
        self.peers = {route: {"signal_name": f"phone-{index}",
            "identity_fingerprint": str(index + 1) * 64, "local_identity_fingerprint": "f" * 64}
            for index, route in enumerate(self.routes)}
        self.bridge = SimpleNamespace(DATA_DIR=self.root, get_client=self.peers.get,
            desktop_id=lambda: "desktop", client=object(), _publish_to_registered_client=Mock())
        self.env = patch.dict(os.environ, {"GALAXYSSI_BLOB_RELAY_URL": "",
                                          "GALAXYSSI_BLOB_PROVISION_TOKEN": ""})
        self.env.start()
        self.addCleanup(self.env.stop)

    def save(self, route=None, **changes):
        route = route or self.routes[0]
        previous = settings.public_settings(self.bridge, route)
        args = {"identity_fingerprint": previous["identity_fingerprint"],
                "identity_binding": previous["identity_binding"],
                "expected_revision": previous["revision"], "enabled": True,
                "origin": "https://relay.test", "provisioning_token": "c" * 64, **changes}
        return settings.update_settings(self.bridge, route, **args)


class PairConfigurationTest(PairConfigurationFixture):
    def test_pair_override_is_encrypted_and_not_shared(self):
        value = self.save()
        self.assertTrue(value["enabled"])
        self.assertTrue(value["credential_present"])
        self.assertEqual("device", value["source"])
        self.assertNotIn("c" * 64, json.dumps(value))
        self.assertFalse(settings.public_settings(self.bridge, self.routes[1])["enabled"])
        for path in (self.root / "blob-pair-settings").glob("*.json"):
            self.assertNotIn("relay.test", path.read_text())
            self.assertNotIn("c" * 64, path.read_text())

    def test_restart_reads_saved_value_without_environment(self):
        first = self.save()
        clone = SimpleNamespace(**vars(self.bridge))
        self.assertEqual(first, settings.public_settings(clone, self.routes[0]))
        self.assertEqual("c" * 64, settings.private_settings(clone, self.routes[0])["provisioning_token"])

    def test_disabled_override_does_not_reenable_from_global_environment(self):
        self.save(enabled=False)
        with patch.dict(os.environ, {"GALAXYSSI_BLOB_RELAY_URL": "https://global.test",
                                     "GALAXYSSI_BLOB_PROVISION_TOKEN": "d" * 64}):
            self.assertFalse(settings.public_settings(self.bridge, self.routes[0])["enabled"])
            self.assertTrue(settings.public_settings(self.bridge, self.routes[1])["enabled"])

    def test_credential_retention_requires_same_origin(self):
        first = self.save()
        self.assertEqual(first, self.save(provisioning_token=None))
        with self.assertRaisesRegex(BlobError, "blob_new_origin_requires_credential"):
            self.save(origin="https://different.test", provisioning_token=None)
        self.assertEqual(first, settings.public_settings(self.bridge, self.routes[0]))

    def test_rotation_and_disable_are_monotonic(self):
        first = self.save()
        with patch.object(settings.time, "time", return_value=1):
            rotated = self.save(provisioning_token="d" * 64)
            disabled = self.save(enabled=False)
        self.assertEqual(first["revision"] + 1, rotated["revision"])
        self.assertEqual(rotated["revision"] + 1, disabled["revision"])
        self.assertFalse(disabled["credential_present"])
        self.assertEqual("", settings.private_settings(self.bridge, self.routes[0])["provisioning_token"])

    def test_stale_editor_and_changed_identity_do_not_overwrite(self):
        first = self.save()
        for changes in ({"expected_revision": first["revision"] - 1}, {"identity_fingerprint": "0" * 64},
                        {"identity_binding": "0" * 64}):
            with self.subTest(changes=changes), self.assertRaises(BlobError):
                self.save(**changes)
        self.assertEqual(first, settings.public_settings(self.bridge, self.routes[0]))

    def test_new_pair_identity_does_not_inherit_credentials_or_opt_in(self):
        self.save()
        settings.private_settings(self.bridge, self.routes[0], requested=True)
        self.peers[self.routes[0]]["identity_fingerprint"] = "9" * 64
        value = settings.public_settings(self.bridge, self.routes[0])
        self.assertFalse(value["enabled"])
        self.assertFalse(value["client_opted_in"])

    def test_settings_are_only_published_after_authenticated_capability_request(self):
        self.save()
        self.assertFalse(publish_configuration(self.bridge, self.bridge.client, self.routes[0], requested=False))
        self.bridge._publish_to_registered_client.assert_not_called()
        self.assertTrue(publish_configuration(self.bridge, self.bridge.client, self.routes[0]))
        self.assertTrue(publish_configuration(self.bridge, self.bridge.client, self.routes[0], requested=False))
        args, kwargs = self.bridge._publish_to_registered_client.call_args
        self.assertEqual(self.routes[0], args[2]["client_route_id"])
        self.assertEqual("c" * 64, args[2]["provisioning_token"])
        self.assertEqual("control", args[3])
        self.assertTrue(kwargs["durable"])
        self.assertFalse(settings.public_settings(self.bridge, self.routes[1])["client_opted_in"])

    def test_unpaired_and_malformed_routes_never_persist_credentials(self):
        for route in ("z" * 22, "../../private", "", "a/b"):
            with self.subTest(route=route), self.assertRaises(BlobError):
                self.save(route=route or " ")
        self.assertFalse((self.root / "blob-pair-settings").exists())

    def test_origin_and_credential_validation_never_replace_good_settings(self):
        first = self.save()
        for change in ({"origin": "http://relay.test"}, {"origin": "https://relay.test/path"},
                       {"origin": "https://user:pass@relay.test"}, {"provisioning_token": "secret"}):
            with self.subTest(change=change), self.assertRaises(BlobError):
                self.save(**change)
        self.assertEqual(first, settings.public_settings(self.bridge, self.routes[0]))

    def test_corruption_and_write_failure_do_not_reset_saved_settings(self):
        first = self.save()
        with patch.object(settings, "write_secure_json", side_effect=OSError("private token")), self.assertRaises(OSError):
            self.save(enabled=False)
        self.assertEqual(first, settings.public_settings(self.bridge, self.routes[0]))
        path = settings._path(self.bridge, self.routes[0])
        path.write_bytes(b"corrupt")
        with self.assertRaises(SecureStateError):
            settings.public_settings(self.bridge, self.routes[0])
        self.assertEqual(b"corrupt", path.read_bytes())

    def test_receiver_origin_uses_route_and_source_not_global_or_offer(self):
        self.save()
        self.assertEqual("https://relay.test", settings.origin_for_peer(self.bridge, self.routes[0], "phone-0"))
        self.assertEqual("", settings.origin_for_peer(self.bridge, self.routes[1], "phone-1"))
        with self.assertRaises(BlobError):
            settings.origin_for_peer(self.bridge, self.routes[0], "phone-1")

    def test_production_receiver_adapter_enforces_different_origins_per_pair(self):
        import blob_input_bridge
        from blob_crypto import binding_hash
        from blob_input_contract import input_binding
        from test_blob_input_journal import input_manifest

        self.save()
        self.save(route=self.routes[1], origin="https://second.test")
        def offer(route, origin):
            manifest = input_manifest(b"small input", route=route)
            return {**manifest, "type": "input_attachment_blob_offer", "blob_offer": {
                "version": 1, "relay": origin, "read_token": "c" * 64,
                "private": {"version": 1, "blob_id": "d" * 32, "key": "e" * 64,
                    "nonce_prefix": "f" * 16, "size": manifest["size_bytes"],
                    "sha256": manifest["sha256"], "manifest_sha256": "1" * 64,
                    "binding_sha256": binding_hash(input_binding(manifest))}}}
        with patch.object(blob_input_bridge, "_receiver", None):
            receiver = blob_input_bridge._get_receiver(self.bridge)
            receiver.enqueue(offer(self.routes[0], "https://relay.test"), self.routes[0], "phone-0")
            with self.assertRaisesRegex(BlobError, "input_blob_relay_mismatch"):
                receiver.enqueue(offer(self.routes[1], "https://relay.test"), self.routes[1], "phone-1")
            receiver.enqueue(offer(self.routes[1], "https://second.test"), self.routes[1], "phone-1")
            self.assertEqual({"pending": 2}, receiver.journal.snapshot())

    def test_pair_change_during_publication_does_not_send_settings(self):
        self.save()
        original = settings.private_settings
        def changed(*args, **kwargs):
            value = original(*args, **kwargs)
            self.peers[self.routes[0]] = {**self.peers[self.routes[0]], "identity_fingerprint": "8" * 64}
            return value
        with patch.object(settings, "private_settings", side_effect=changed):
            self.assertFalse(publish_configuration(self.bridge, self.bridge.client, self.routes[0]))
        self.bridge._publish_to_registered_client.assert_not_called()

    def test_revocation_and_forgetting_remove_only_the_affected_pair_settings(self):
        import pairing_state
        from link_protocol import new_link_secret
        with patch.object(pairing_state, "STATE_PATH", self.root / "registry.json"):
            for route in self.routes:
                peer = self.peers[route]
                pairing_state.record_pairing_success(peer["identity_fingerprint"], peer["signal_name"],
                    client_route_id=route, link_secret=new_link_secret(), local_identity_fingerprint="f" * 64)
                self.save(route=route)
            first = settings._path(self.bridge, self.routes[0])
            second = settings._path(self.bridge, self.routes[1])
            pairing_state.revoke_client(self.routes[0])
            self.assertFalse(first.exists())
            self.assertTrue(second.exists())
            pairing_state.clear_pairing_state(self.routes[1])
            self.assertFalse(second.exists())
            self.assertFalse(pairing_state.forget_client(self.routes[1]))

    def test_clear_all_pairings_removes_both_settings_but_not_global_defaults(self):
        import pairing_state
        from link_protocol import new_link_secret
        with patch.object(pairing_state, "STATE_PATH", self.root / "registry.json"):
            for route in self.routes:
                peer = self.peers[route]
                pairing_state.record_pairing_success(peer["identity_fingerprint"], peer["signal_name"],
                    client_route_id=route, link_secret=new_link_secret(), local_identity_fingerprint="f" * 64)
                self.save(route=route)
            default = self.root / "blob-relay-configuration.secure.json"
            before = default.read_bytes()
            pairing_state.clear_pairing_state()
            self.assertTrue(all(not settings._path(self.bridge, route).exists() for route in self.routes))
            self.assertEqual(before, default.read_bytes())
            self.assertEqual([], pairing_state.list_clients())
