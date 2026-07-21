from __future__ import annotations

import re
import unittest
from unittest.mock import patch

import desktop_native_tools
import mqtt_bridge


class FakeRegistry:
    def __init__(self) -> None:
        self.calls: list[tuple[str, dict, dict]] = []

    def invoke(self, tool_id: str, arguments: dict, context: dict) -> dict:
        self.calls.append((tool_id, arguments, context))
        return {
            "status": "succeeded",
            "output": {"path": arguments.get("path", "")},
            "message": "done",
            "metadata": {},
            "error": None,
            "verification": {"status": "passed", "message": "verified", "evidence": {}},
            "receipt": {"invocation_id": context["invocation_id"]},
            "provenance": {"tool_id": tool_id, "location": "desktop"},
            "artifacts": [],
        }


class MqttDesktopToolRoutingTests(unittest.TestCase):
    def setUp(self) -> None:
        self.registry = FakeRegistry()
        self.published: list[dict] = []
        self.patches = [
            patch.object(desktop_native_tools, "desktop_native_tool_registry", return_value=self.registry),
            patch.object(mqtt_bridge, "desktop_id", return_value="desktop-test"),
            patch.object(mqtt_bridge, "desktop_name", return_value="Test Desktop"),
            patch.object(
                mqtt_bridge,
                "_publish_phone_payload",
                side_effect=lambda _mqttc, _wire, payload: self.published.append(payload) or True,
            ),
        ]
        for item in self.patches:
            item.start()

    def tearDown(self) -> None:
        for item in reversed(self.patches):
            item.stop()

    @staticmethod
    def request(workspace_id: str = "workspace-one") -> dict:
        return {
            "type": mqtt_bridge.DESKTOP_TOOL_CALL_REQUEST_TYPE,
            "message_id": "message-1",
            "call_id": "call-1",
            "invocation_id": "invocation-1",
            "task_id": "task-1",
            "conversation_id": "conversation-1",
            "workspace_id": workspace_id,
            "tool_id": desktop_native_tools.FILE_READ_TEXT,
            "tool_version": "1.0.0",
            "arguments": {"workspace_id": workspace_id, "path": "notes.txt"},
        }

    @staticmethod
    def envelope(conversation_id: str = "conversation-1") -> dict:
        return {
            "source_id": "signalasi:phone-a",
            "target_id": "desktop-test",
            "conversation_id": conversation_id,
            "message_id": "envelope-1",
        }

    def execute(self, signal_name: str, workspace_id: str = "workspace-one") -> tuple[dict, dict]:
        paired = {"signal_name": signal_name, "client_route_id": f"route-{signal_name[-1]}"}
        response = mqtt_bridge._execute_desktop_tool_request(
            object(),
            {"_client_route_id": paired["client_route_id"]},
            self.envelope(),
            self.request(workspace_id),
            paired,
        )
        return response, self.registry.calls[-1][1]

    def test_remote_workspace_is_namespaced_by_paired_phone_identity(self) -> None:
        first_response, first_arguments = self.execute("signalasi:phone-a")
        second_response, second_arguments = self.execute("signalasi:phone-b")

        self.assertRegex(first_arguments["workspace_id"], re.compile(r"link-[0-9a-f]{64}\Z"))
        self.assertNotEqual(first_arguments["workspace_id"], second_arguments["workspace_id"])
        self.assertNotEqual("workspace-one", first_arguments["workspace_id"])
        self.assertEqual("desktop_tool_call_result", first_response["type"])
        self.assertEqual("desktop-test", second_response["desktop_id"])
        self.assertEqual(2, len(self.published))

    def test_workspace_identity_mismatch_is_rejected_before_execution(self) -> None:
        payload = self.request("workspace-one")
        payload["arguments"]["workspace_id"] = "workspace-two"

        with self.assertRaises(mqtt_bridge.PhoneToolSessionRoutingError):
            mqtt_bridge._execute_desktop_tool_request(
                object(),
                {"_client_route_id": "route-a"},
                self.envelope(),
                payload,
                {"signal_name": "signalasi:phone-a", "client_route_id": "route-a"},
            )

        self.assertEqual([], self.registry.calls)
        self.assertEqual([], self.published)

    def test_phone_confirmation_is_verified_then_rebound_to_isolated_workspace(self) -> None:
        payload = self.request()
        original_digest = desktop_native_tools.canonical_input_sha256(payload["arguments"])
        payload["confirmation"] = {
            "decision": "approved",
            "tool_id": desktop_native_tools.FILE_READ_TEXT,
            "tool_version": "1.0.0",
            "arguments_sha256": original_digest,
            "expires_at": 9_999_999_999_999,
        }

        mqtt_bridge._execute_desktop_tool_request(
            object(),
            {"_client_route_id": "route-a"},
            self.envelope(),
            payload,
            {"signal_name": "signalasi:phone-a", "client_route_id": "route-a"},
        )

        _, isolated_arguments, context = self.registry.calls[-1]
        self.assertNotEqual(original_digest, desktop_native_tools.canonical_input_sha256(isolated_arguments))
        self.assertEqual(
            desktop_native_tools.canonical_input_sha256(isolated_arguments),
            context["confirmation"]["arguments_sha256"],
        )

    def test_tampered_phone_confirmation_is_rejected_before_execution(self) -> None:
        payload = self.request()
        payload["confirmation"] = {
            "decision": "approved",
            "tool_id": desktop_native_tools.FILE_READ_TEXT,
            "tool_version": "1.0.0",
            "arguments_sha256": "0" * 64,
            "expires_at": 9_999_999_999_999,
        }

        with self.assertRaises(mqtt_bridge.PhoneToolSessionRoutingError):
            mqtt_bridge._execute_desktop_tool_request(
                object(),
                {"_client_route_id": "route-a"},
                self.envelope(),
                payload,
                {"signal_name": "signalasi:phone-a", "client_route_id": "route-a"},
            )

        self.assertEqual([], self.registry.calls)

    def test_conversation_must_match_encrypted_link_envelope(self) -> None:
        with self.assertRaises(mqtt_bridge.PhoneToolSessionRoutingError):
            mqtt_bridge._execute_desktop_tool_request(
                object(),
                {"_client_route_id": "route-a"},
                self.envelope("other-conversation"),
                self.request(),
                {"signal_name": "signalasi:phone-a", "client_route_id": "route-a"},
            )

        self.assertEqual([], self.registry.calls)


if __name__ == "__main__":
    unittest.main()
