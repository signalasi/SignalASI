from unittest.mock import patch

from fastapi import FastAPI
from fastapi.testclient import TestClient

import blob_configuration_api as api
import blob_pair_configuration as settings
from test_blob_pair_configuration import PairConfigurationFixture


class ConfigurationApiTest(PairConfigurationFixture):
    def setUp(self):
        super().setUp()
        app = FastAPI()
        app.include_router(api.router)
        self.client = TestClient(app)
        self.addCleanup(self.client.close)
        self.bridge_patch = patch.object(api, "_bridge", return_value=self.bridge)
        self.bridge_patch.start()
        self.addCleanup(self.bridge_patch.stop)
        self.url = "/api/blob/settings/" + self.routes[0]

    def payload(self):
        value = self.client.get(self.url).json()
        return {"identity_fingerprint": value["identity_fingerprint"],
                "identity_binding": value["identity_binding"], "expected_revision": value["revision"],
                "enabled": True, "origin": "https://relay.test", "provisioning_token": "c" * 64}

    def test_api_saves_without_echoing_secret_and_waits_for_capability(self):
        result = self.client.put(self.url, json=self.payload())
        self.assertEqual(200, result.status_code)
        self.assertFalse(result.json()["configuration_queued"])
        self.assertNotIn("c" * 64, result.text)
        self.assertNotIn("provisioning_token", self.client.get(self.url).text)
        self.bridge._publish_to_registered_client.assert_not_called()

    def test_queue_failure_keeps_saved_revision_for_next_authenticated_request(self):
        settings.private_settings(self.bridge, self.routes[0], requested=True)
        self.bridge._publish_to_registered_client.side_effect = OSError("private-provider-secret")
        result = self.client.put(self.url, json=self.payload())
        self.assertEqual(200, result.status_code)
        self.assertFalse(result.json()["configuration_queued"])
        self.assertNotIn("private-provider-secret", result.text)
        self.assertTrue(self.client.get(self.url).json()["enabled"])

    def test_validation_and_storage_errors_never_echo_credentials(self):
        payload = self.payload()
        payload["enabled"] = "private-provider-secret"
        result = self.client.put(self.url, json=payload)
        self.assertEqual(422, result.status_code)
        self.assertNotIn("private-provider-secret", result.text)
        self.assertNotIn("c" * 64, result.text)
        with patch.object(settings, "write_secure_json", side_effect=OSError("private-provider-secret")):
            result = self.client.put(self.url, json=self.payload())
        self.assertEqual(503, result.status_code)
        self.assertNotIn("private-provider-secret", result.text)

    def test_api_rejects_stale_editor(self):
        payload = self.payload()
        self.assertEqual(200, self.client.put(self.url, json=payload).status_code)
        result = self.client.put(self.url, json={**payload, "enabled": False})
        self.assertEqual(409, result.status_code)
        self.assertTrue(self.client.get(self.url).json()["enabled"])

    def test_loopback_check_is_enforced_before_settings_access(self):
        from fastapi import HTTPException, Request
        self.bridge_patch.stop()
        with patch.object(settings, "public_settings") as read, self.assertRaises(HTTPException) as caught:
            api.get_settings(self.routes[0], Request({"type": "http", "client": ("198.51.100.4", 1234), "headers": []}))
        self.assertEqual(403, caught.exception.status_code)
        read.assert_not_called()
