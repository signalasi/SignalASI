import json
import os
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

import agent_config


class AgentConfigStorageTest(unittest.TestCase):
    def test_legacy_source_config_migrates_to_private_state_directory(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            state_dir = root / "state"
            legacy = root / "legacy.json"
            legacy.write_text(
                json.dumps({"commands": {"claude": "custom-claude -"}}),
                encoding="utf-8",
            )
            with patch.dict(os.environ, {"SIGNALASI_STATE_DIR": str(state_dir)}, clear=False), \
                    patch.object(agent_config, "LEGACY_CONFIG_PATH", legacy):
                loaded = agent_config.load_config()

            self.assertEqual("custom-claude -", loaded["commands"]["claude"])
            self.assertTrue((state_dir / "agents.json").is_file())

    def test_save_config_writes_only_to_runtime_state(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            state_dir = root / "state"
            legacy = root / "legacy.json"
            with patch.dict(os.environ, {"SIGNALASI_STATE_DIR": str(state_dir)}, clear=False), \
                    patch.object(agent_config, "LEGACY_CONFIG_PATH", legacy):
                agent_config.save_config({"custom_agent": {"name": "Test Agent"}})

            saved = json.loads((state_dir / "agents.json").read_text(encoding="utf-8"))
            self.assertEqual("Test Agent", saved["custom_agent"]["name"])
            self.assertFalse(legacy.exists())

    def test_cloud_api_and_context_settings_round_trip_without_exposing_secret(self):
        with tempfile.TemporaryDirectory() as directory:
            state_dir = Path(directory) / "state"
            with patch.dict(os.environ, {"SIGNALASI_STATE_DIR": str(state_dir)}, clear=False):
                masked = agent_config.save_config({
                    "cloud_model": {
                        "provider": "deepseek",
                        "name": "DeepSeek",
                        "url": "https://api.deepseek.com/chat/completions",
                        "model": "deepseek-test",
                        "api_key": "secret-key",
                        "context_window_tokens": "128000",
                        "max_output_tokens": "8192",
                        "context_model_summary": "true",
                    }
                })
                persisted = agent_config.load_config(mask_secrets=False)["cloud_model"]

            self.assertEqual(agent_config.MASK, masked["cloud_model"]["api_key"])
            self.assertEqual("secret-key", persisted["api_key"])
            self.assertEqual("deepseek", persisted["provider"])
            self.assertEqual("128000", persisted["context_window_tokens"])
            self.assertEqual("8192", persisted["max_output_tokens"])
            self.assertEqual("true", persisted["context_model_summary"])


if __name__ == "__main__":
    unittest.main()
