from __future__ import annotations

import unittest
from unittest.mock import patch

import mqtt_bridge


class ConnectorPresenceTest(unittest.TestCase):
    def test_mobile_agent_status_uses_epoch_milliseconds(self) -> None:
        diagnostics = {
            "agents": [
                {
                    "id": "codex",
                    "mobile_contact_id": "codex",
                    "name": "Codex",
                    "kind": "codex",
                    "status": "ready",
                }
            ]
        }
        with (
            patch.object(mqtt_bridge, "connector_diagnostics", return_value=diagnostics),
            patch.object(mqtt_bridge, "desktop_id", return_value="desktop-test"),
            patch.object(mqtt_bridge, "desktop_name", return_value="Test PC"),
            patch.object(mqtt_bridge, "get_signal_bundle", return_value={"identityKeySha256": "abc"}),
            patch.object(mqtt_bridge, "server_route_id", return_value="b" * 22),
        ):
            agents = mqtt_bridge.mobile_connector_agents("a" * 22)

        self.assertEqual(1, len(agents))
        self.assertGreater(agents[0]["updated_at"], 1_000_000_000_000)


if __name__ == "__main__":
    unittest.main()
