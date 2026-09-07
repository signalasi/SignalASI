import os
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

from ui_smoke_fixtures import seed_peer_image
from peer_chat_store import PeerChatStore


class UiSmokeFixtureTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)
        self.data = self.root / "user-data" / "runtime"
        self.image = self.root / "fixture.png"
        self.image.write_bytes(b"synthetic image bytes")
        self.env = patch.dict(os.environ, {
            "GALAXYSSI_UI_SMOKE": "1", "GALAXYSSI_UI_SMOKE_DIR": str(self.root),
            "GALAXYSSI_STATE_DIR": str(self.root),
            "GALAXYSSI_DISABLE_EXTERNAL_SERVICES": "1",
        })
        self.env.start()

    def tearDown(self):
        self.env.stop()
        self.temp.cleanup()

    def test_fixture_survives_store_recreation_and_repeated_seed(self):
        fixture = seed_peer_image(self.data, self.image)
        self.assertEqual(fixture, seed_peer_image(self.data, self.image))
        store = PeerChatStore(self.data / "peer_chat.db")
        messages = store.list_messages(fixture["route_id"])
        self.assertEqual(1, len(messages))
        self.assertTrue(messages[0]["attachments"][0]["available"])
        attachment = store.attachment_record(fixture["message_id"], 0)
        self.assertEqual(self.image.read_bytes(), Path(attachment["local_path"]).read_bytes())

    def test_refuses_non_smoke_environment(self):
        with patch.dict(os.environ, {"GALAXYSSI_UI_SMOKE": "0"}):
            with self.assertRaisesRegex(ValueError, "isolated UI smoke"):
                seed_peer_image(self.data, self.image)
        self.assertFalse(self.data.exists())

    def test_refuses_production_state_path(self):
        unrelated = self.root / "production"
        with self.assertRaisesRegex(ValueError, "outside"):
            seed_peer_image(unrelated, self.image)
        self.assertFalse(unrelated.exists())

    def test_refuses_external_services(self):
        with patch.dict(os.environ, {"GALAXYSSI_DISABLE_EXTERNAL_SERVICES": "0"}):
            with self.assertRaises(ValueError):
                seed_peer_image(self.data, self.image)
        self.assertFalse(self.data.exists())

    def test_profile_and_screenshot_directories_can_differ(self):
        profile = self.root / "separate-state"
        with patch.dict(os.environ, {"GALAXYSSI_STATE_DIR": str(profile)}):
            data = profile / "user-data" / "runtime"
            result = seed_peer_image(data, self.image)
            self.assertIsNotNone(PeerChatStore(data / "peer_chat.db").get_message(result["message_id"]))
            self.assertFalse(self.data.exists())
