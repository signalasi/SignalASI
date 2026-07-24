import json
import os
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

import agent_config


class AgentConfigStorageTest(unittest.TestCase):
    def test_missing_current_config_uses_defaults_without_creating_state(self):
        with tempfile.TemporaryDirectory() as directory:
            state_dir = Path(directory) / "state"
            with patch.dict(
                os.environ,
                {"SIGNALASI_STATE_DIR": str(state_dir), "SIGNALASI_CONFIG_PATH": ""},
                clear=False,
            ):
                loaded = agent_config.load_config()

            self.assertEqual(agent_config.DEFAULT_CONFIG["commands"]["claude"], loaded["commands"]["claude"])
            self.assertFalse((state_dir / "agents.json").exists())

    def test_save_config_writes_only_to_runtime_state(self):
        with tempfile.TemporaryDirectory() as directory:
            state_dir = Path(directory) / "state"
            with patch.dict(
                os.environ,
                {"SIGNALASI_STATE_DIR": str(state_dir), "SIGNALASI_CONFIG_PATH": ""},
                clear=False,
            ):
                agent_config.save_config({"custom_agent": {"name": "Test Agent"}})

            saved = json.loads((state_dir / "agents.json").read_text(encoding="utf-8"))
            self.assertEqual("Test Agent", saved["custom_agent"]["name"])

    def test_cloud_api_and_context_settings_round_trip_without_exposing_secret(self):
        with tempfile.TemporaryDirectory() as directory:
            state_dir = Path(directory) / "state"
            with patch.dict(
                os.environ,
                {"SIGNALASI_STATE_DIR": str(state_dir), "SIGNALASI_CONFIG_PATH": ""},
                clear=False,
            ):
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
