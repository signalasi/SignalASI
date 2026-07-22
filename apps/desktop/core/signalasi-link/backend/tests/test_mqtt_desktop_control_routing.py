from __future__ import annotations

import tempfile
import time
import unittest
import uuid
from pathlib import Path
from unittest.mock import patch

import desktop_control
import mqtt_bridge


class ImmediateThread:
    def __init__(self, *, target, **_kwargs) -> None:
        self.target = target

    def start(self) -> None:
        self.target()


class FakeInput:
    def __init__(self) -> None:
        self.calls: list[tuple] = []

    def is_locked(self) -> bool:
        return False

    def click(self, x: int, y: int, button: str) -> None:
        self.calls.append(("click", x, y, button))

    def type_text(self, value: str) -> None:
        self.calls.append(("type", len(value)))

    def hotkey(self, keys: list[str]) -> None:
        self.calls.append(("hotkey", tuple(keys)))

    def scroll(self, delta: int) -> None:
        self.calls.append(("scroll", delta))


class MqttDesktopControlRoutingTests(unittest.TestCase):
    def setUp(self) -> None:
        self.temporary = tempfile.TemporaryDirectory()
        self.input = FakeInput()
        self.manager = desktop_control.DesktopControlManager(
            Path(self.temporary.name) / "desktop-control.json",
            screenshot_provider=lambda: {
                "image_mime": "image/jpeg",
                "image_base64": "/9j/2Q==",
                "width": 480,
                "height": 270,
                "original_width": 1920,
                "original_height": 1080,
                "bytes": 4,
                "captured_at": int(time.time() * 1000),
            },
            input_controller=self.input,
        )
        self.client = {
            "client_route_id": "client-route-a",
            "identity_fingerprint": "a" * 64,
            "signal_name": "signalasi:phone-a",
            "display_name": "Phone A",
            "platform": "android",
        }
        self.published: list[dict] = []
        self.patches = [
            patch.object(desktop_control, "desktop_control_manager", return_value=self.manager),
            patch.object(mqtt_bridge, "desktop_id", return_value="desktop-test"),
            patch.object(mqtt_bridge, "desktop_name", return_value="Test Desktop"),
            patch.object(mqtt_bridge.threading, "Thread", ImmediateThread),
            patch.object(
                mqtt_bridge,
                "_publish_phone_payload",
                side_effect=lambda _mqtt, _wire, payload: self.published.append(dict(payload)) or True,
            ),
        ]
        for item in self.patches:
            item.start()

    def tearDown(self) -> None:
        for item in reversed(self.patches):
            item.stop()
        self.temporary.cleanup()

    def authorize(self) -> dict:
        self.manager.update_settings(enabled=True)
        offer = self.manager.create_offer("pair-token")
        pending = self.manager.accept_pairing_offer(offer["token"], "pair-token", self.client)
        return self.manager.approve(pending["authorization_id"])

    @staticmethod
    def envelope(target: str = "desktop-test") -> dict:
        return {
            "source_id": "signalasi:phone-a",
            "target_id": target,
            "message_id": str(uuid.uuid4()),
        }

    @staticmethod
    def request(authorization_id: str) -> dict:
        now = int(time.time() * 1000)
        return {
            "type": mqtt_bridge.DESKTOP_EXECUTOR_REQUEST_TYPE,
            "task_id": str(uuid.uuid4()),
            "action_id": str(uuid.uuid4()),
            "authorization_id": authorization_id,
            "tool_id": desktop_control.CLICK_XY,
            "input": {"x": 100, "y": 200, "button": "left"},
            "sent_at": now,
            "expires_at": now + 30_000,
        }

    def test_authorized_control_request_executes_and_returns_receipt(self) -> None:
        authorization = self.authorize()
        handled = mqtt_bridge._route_desktop_control_payload(
            object(),
            self.client,
            self.envelope(),
            self.request(authorization["authorization_id"]),
            "control",
        )

        self.assertTrue(handled)
        self.assertEqual([("click", 100, 200, "left")], self.input.calls)
        self.assertEqual(
            [mqtt_bridge.DESKTOP_EXECUTOR_EVENT_TYPE, mqtt_bridge.DESKTOP_ACTION_RECEIPT_TYPE],
            [item["type"] for item in self.published],
        )
        self.assertEqual("succeeded", self.published[-1]["status"])

    def test_unapproved_phone_receives_failure_without_execution(self) -> None:
        self.manager.update_settings(enabled=True)
        mqtt_bridge._route_desktop_control_payload(
            object(),
            self.client,
            self.envelope(),
            self.request(str(uuid.uuid4())),
            "control",
        )

        self.assertEqual([], self.input.calls)
        self.assertEqual(1, len(self.published))
        self.assertEqual("authorization_not_found", self.published[0]["error"]["code"])

    def test_non_control_channel_and_wrong_target_are_not_executed(self) -> None:
        authorization = self.authorize()
        request = self.request(authorization["authorization_id"])
        self.assertTrue(mqtt_bridge._route_desktop_control_payload(
            object(), self.client, self.envelope(), request, "up"
        ))
        self.assertTrue(mqtt_bridge._route_desktop_control_payload(
            object(), self.client, self.envelope("another-desktop"), request, "control"
        ))
        self.assertEqual([], self.input.calls)
        self.assertEqual([], self.published)


if __name__ == "__main__":
    unittest.main()
