"""Output receiver negotiation is independent, identity-bound and replay-safe."""
from concurrent.futures import ThreadPoolExecutor
from types import SimpleNamespace
from unittest.mock import patch
import unittest

import blob_pair_configuration as settings
from blob_protocol import BlobError
from secure_state import SecureStateError
from test_blob_pair_configuration import PairConfigurationFixture


class ArtifactCapabilityTests(PairConfigurationFixture):
    def report(self, *, route=None, source=None, **changes):
        route = route or self.routes[0]
        peer = self.peers[route]
        payload = {"type": settings.ARTIFACT_CAPABILITY_TYPE, "version": 1, "revision": 1,
                   "enabled": True, "client_route_id": route, "desktop_id": "desktop",
                   "desktop_fingerprint": peer["local_identity_fingerprint"], **changes}
        return settings.record_artifact_capability(self.bridge, route,
                                                   source or peer["signal_name"], payload)

    def test_input_opt_in_does_not_enable_output(self):
        self.save()
        settings.private_settings(self.bridge, self.routes[0], requested=True)
        self.assertTrue(settings.can_publish(self.bridge, self.routes[0]))
        self.assertFalse(settings.can_receive_artifacts(self.bridge, self.routes[0]))

    def test_output_support_does_not_change_relay_credentials_or_input_opt_in(self):
        before = settings.private_settings(self.bridge, self.routes[0])
        self.assertTrue(self.report())
        self.assertTrue(settings.can_receive_artifacts(self.bridge, self.routes[0]))
        self.assertEqual(before, settings.private_settings(self.bridge, self.routes[0]))
        self.assertFalse(settings.can_publish(self.bridge, self.routes[0]))
        self.assertFalse(settings.can_receive_artifacts(self.bridge, self.routes[1]))

    def test_relay_editor_and_input_opt_in_preserve_receiver_declaration(self):
        self.report(revision=4)
        self.save()
        settings.private_settings(self.bridge, self.routes[0], requested=True)
        self.assertTrue(settings.can_receive_artifacts(self.bridge, self.routes[0]))
        self.report(revision=3, enabled=False)
        self.assertTrue(settings.can_receive_artifacts(self.bridge, self.routes[0]))

    def test_persistence_survives_restart_without_plaintext_identity(self):
        self.report()
        restarted = SimpleNamespace(**vars(self.bridge))
        self.assertTrue(settings.can_receive_artifacts(restarted, self.routes[0]))
        raw = settings._path(self.bridge, self.routes[0]).read_text()
        self.assertNotIn("artifact_receiver", raw)
        self.assertNotIn(self.peers[self.routes[0]]["signal_name"], raw)

    def test_old_enable_does_not_undo_disable(self):
        self.report(revision=2, enabled=False)
        self.report(revision=1, enabled=True)
        self.assertFalse(settings.can_receive_artifacts(self.bridge, self.routes[0]))
        self.report(revision=3)
        self.assertTrue(settings.can_receive_artifacts(self.bridge, self.routes[0]))

    def test_same_revision_is_idempotent_but_conflicting_report_is_rejected(self):
        self.report()
        with patch.object(settings, "write_secure_json") as write:
            self.assertTrue(self.report())
            write.assert_not_called()
        with self.assertRaisesRegex(BlobError, "revision_conflict"):
            self.report(enabled=False)
        self.assertTrue(settings.can_receive_artifacts(self.bridge, self.routes[0]))

    def test_pair_replacement_resets_receiver_revision_and_support(self):
        self.report(revision=999)
        self.peers[self.routes[0]]["identity_fingerprint"] = "9" * 64
        self.assertFalse(settings.can_receive_artifacts(self.bridge, self.routes[0]))
        self.report(revision=1, enabled=False)
        self.assertFalse(settings.can_receive_artifacts(self.bridge, self.routes[0]))

    def test_revocation_forgets_output_capability(self):
        self.report()
        settings.forget_settings(self.root, self.routes[0])
        self.assertFalse(settings.can_receive_artifacts(self.bridge, self.routes[0]))

    def test_wrong_sender_route_or_desktop_does_not_mutate_state(self):
        for change in ({"source": "different phone"}, {"client_route_id": self.routes[1]},
                       {"desktop_id": "another desktop"}, {"desktop_fingerprint": "e" * 64}):
            with self.subTest(change=change), self.assertRaisesRegex(BlobError, "identity_mismatch"):
                self.report(**change)
        self.assertFalse(settings._path(self.bridge, self.routes[0]).exists())

    def test_strict_versions_revisions_and_booleans(self):
        for change in ({"version": True}, {"version": 2}, {"version": "1"}, {"enabled": 1},
                       {"enabled": "true"}, {"revision": 0}, {"revision": -1},
                       {"revision": True}, {"revision": 1.0}, {"revision": 2**53}):
            with self.subTest(change=change), self.assertRaisesRegex(BlobError, "invalid_artifact"):
                self.report(**change)
        self.assertFalse(settings._path(self.bridge, self.routes[0]).exists())

    def test_concurrent_out_of_order_reports_keep_highest_revision(self):
        with ThreadPoolExecutor(max_workers=8) as pool:
            results = pool.map(lambda revision: self.report(revision=revision, enabled=revision != 16),
                               [8, 16, 3, 11, 5, 2, 14, 1])
            self.assertTrue(all(results))
        self.assertFalse(settings.can_receive_artifacts(self.bridge, self.routes[0]))

    def test_disk_failure_cannot_advertise_persisted_support(self):
        with patch.object(settings, "write_secure_json", side_effect=OSError("disk full")):
            with self.assertRaises(OSError):
                self.report()
        self.assertFalse(settings.can_receive_artifacts(self.bridge, self.routes[0]))

    def test_corrupt_secure_state_is_not_treated_as_enabled_or_overwritten(self):
        self.report()
        path = settings._path(self.bridge, self.routes[0])
        path.write_bytes(b"corrupt")
        with self.assertRaises(SecureStateError):
            settings.can_receive_artifacts(self.bridge, self.routes[0])
        with self.assertRaises(SecureStateError):
            self.report(revision=2)
        self.assertEqual(b"corrupt", path.read_bytes())

    def test_plain_chat_has_no_configuration_io(self):
        with patch.object(settings, "_identity") as identity, patch.object(settings, "_read") as read:
            self.assertFalse(settings.record_artifact_capability(self.bridge, self.routes[0], "phone",
                                                                 {"type": "text", "content": "hello"}))
        identity.assert_not_called()
        read.assert_not_called()


if __name__ == "__main__":
    unittest.main()
