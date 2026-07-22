import tempfile
import unittest
import uuid
from pathlib import Path

from desktop_control import (
    CLICK_XY,
    SCREENSHOT,
    TYPE_TEXT,
    DesktopControlError,
    DesktopControlManager,
    WindowsInputController,
)


class FakeClock:
    def __init__(self, value: float = 1_800_000_000.0):
        self.value = value

    def __call__(self) -> float:
        return self.value


class FakeInput:
    def __init__(self):
        self.calls = []
        self.locked = False

    def is_locked(self):
        return self.locked

    def click(self, x, y, button, *, source_width=None, source_height=None):
        self.calls.append(("click", x, y, button))
        self.coordinate_space = (source_width, source_height)

    def type_text(self, text):
        self.calls.append(("type", text))

    def hotkey(self, keys):
        self.calls.append(("hotkey", list(keys)))

    def scroll(self, delta):
        self.calls.append(("scroll", delta))


class DesktopControlTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.clock = FakeClock()
        self.input = FakeInput()
        self.screenshot_calls = 0

        def screenshot():
            self.screenshot_calls += 1
            return {
                "image_mime": "image/jpeg",
                "image_base64": "/9j/2Q==",
                "width": 960,
                "height": 540,
                "original_width": 1920,
                "original_height": 1080,
                "bytes": 4,
                "captured_at": int(self.clock() * 1000),
            }

        self.manager = DesktopControlManager(
            Path(self.temporary.name) / "control.json",
            now=self.clock,
            screenshot_provider=screenshot,
            input_controller=self.input,
        )
        self.client = {
            "client_route_id": "client-route-1",
            "identity_fingerprint": "a" * 64,
            "signal_name": "signalasi:" + "a" * 16,
            "display_name": "Test Phone",
            "platform": "android",
        }

    def tearDown(self):
        self.temporary.cleanup()

    def authorize(self):
        self.manager.update_settings(enabled=True)
        offer = self.manager.create_offer("pair-token")
        self.assertIsNotNone(offer)
        pending = self.manager.accept_pairing_offer(offer["token"], "pair-token", self.client)
        self.assertEqual("pending", pending["status"])
        return self.manager.approve(pending["authorization_id"])

    def request(self, authorization, tool=SCREENSHOT, input_value=None, action_id=None):
        now = int(self.clock() * 1000)
        return {
            "type": "desktop_executor_request",
            "task_id": "task-1",
            "action_id": action_id or str(uuid.uuid4()),
            "authorization_id": authorization["authorization_id"],
            "tool_id": tool,
            "input": input_value or {},
            "sent_at": now,
            "expires_at": now + 30_000,
        }

    def test_offer_requires_enabled_executor_and_is_single_use(self):
        self.assertIsNone(self.manager.create_offer("pair-token"))
        authorization = self.authorize()
        self.assertEqual("active", authorization["status"])
        consumed_offer = self.manager.create_offer("second-pair-token")
        self.assertIsNotNone(consumed_offer)
        pending = self.manager.accept_pairing_offer(
            consumed_offer["token"],
            "second-pair-token",
            {**self.client, "client_route_id": "client-route-2", "identity_fingerprint": "b" * 64},
        )
        self.assertEqual("pending", pending["status"])
        with self.assertRaises(DesktopControlError) as raised:
            self.manager.accept_pairing_offer(
                consumed_offer["token"],
                "second-pair-token",
                {**self.client, "client_route_id": "client-route-2", "identity_fingerprint": "b" * 64},
            )
        self.assertEqual("authorization_offer_invalid", raised.exception.code)

    def test_offer_is_rejected_if_executor_is_disabled_before_pairing_completes(self):
        self.manager.update_settings(enabled=True)
        offer = self.manager.create_offer("pair-token")
        self.manager.update_settings(enabled=False)
        with self.assertRaises(DesktopControlError) as raised:
            self.manager.accept_pairing_offer(offer["token"], "pair-token", self.client)
        self.assertEqual("desktop_executor_disabled", raised.exception.code)

    def test_authorized_screenshot_emits_running_and_replays_without_recapture(self):
        authorization = self.authorize()
        action_id = str(uuid.uuid4())
        events = []
        request = self.request(authorization, action_id=action_id)
        result = self.manager.execute_request(request, self.client, on_running=events.append)
        self.assertEqual("succeeded", result["status"])
        self.assertEqual(SCREENSHOT, result["tool_id"])
        self.assertEqual(1, len(events))
        self.assertEqual(1, self.screenshot_calls)
        replay = self.manager.execute_request(request, self.client)
        self.assertTrue(replay["replayed"])
        self.assertEqual(1, self.screenshot_calls)
        state_text = (Path(self.temporary.name) / "control.json").read_text(encoding="utf-8")
        self.assertNotIn("/9j/2Q==", state_text)

    def test_duplicate_action_with_different_input_is_rejected(self):
        authorization = self.authorize()
        action_id = str(uuid.uuid4())
        first = self.request(
            authorization,
            CLICK_XY,
            {"x": 100, "y": 200, "button": "left"},
            action_id,
        )
        self.assertEqual("succeeded", self.manager.execute_request(first, self.client)["status"])
        conflicting = self.request(
            authorization,
            CLICK_XY,
            {"x": 101, "y": 200, "button": "left"},
            action_id,
        )
        with self.assertRaises(DesktopControlError) as raised:
            self.manager.execute_request(conflicting, self.client)
        self.assertEqual("duplicate_action_conflict", raised.exception.code)

    def test_click_and_text_are_executed_but_text_is_redacted_from_audit(self):
        authorization = self.authorize()
        click = self.manager.execute_request(
            self.request(authorization, CLICK_XY, {"x": 100, "y": 200, "button": "left"}),
            self.client,
        )
        self.assertEqual("succeeded", click["status"])
        secret = "private text must not enter the audit log"
        typed = self.manager.execute_request(
            self.request(authorization, TYPE_TEXT, {"text": secret}),
            self.client,
        )
        self.assertEqual("succeeded", typed["status"])
        state_text = (Path(self.temporary.name) / "control.json").read_text(encoding="utf-8")
        self.assertNotIn(secret, state_text)
        self.assertIn("typed 41 chars", state_text)

    def test_click_coordinate_space_is_forwarded_and_scaled_for_windows_dpi(self):
        authorization = self.authorize()
        click = self.manager.execute_request(
            self.request(
                authorization,
                CLICK_XY,
                {
                    "x": 3000,
                    "y": 1800,
                    "button": "left",
                    "coordinate_width": 3840,
                    "coordinate_height": 2160,
                },
            ),
            self.client,
        )
        self.assertEqual("succeeded", click["status"])
        self.assertEqual((3840, 2160), self.input.coordinate_space)
        self.assertEqual(
            (1200, 719),
            WindowsInputController.scale_point(
                3000,
                1800,
                source_width=3840,
                source_height=2160,
                target_width=1536,
                target_height=864,
            ),
        )

    def test_identity_mismatch_expiry_disable_and_revoke_are_rejected(self):
        authorization = self.authorize()
        request = self.request(authorization)
        mismatched = {**self.client, "identity_fingerprint": "b" * 64}
        with self.assertRaises(DesktopControlError) as mismatch:
            self.manager.execute_request(request, mismatched)
        self.assertEqual("authorization_identity_mismatch", mismatch.exception.code)

        expired = self.request(authorization)
        self.clock.value += 31
        with self.assertRaises(DesktopControlError) as expiry:
            self.manager.execute_request(expired, self.client)
        self.assertEqual("message_expired", expiry.exception.code)

        self.manager.update_settings(enabled=False)
        with self.assertRaises(DesktopControlError) as disabled:
            self.manager.execute_request(self.request(authorization), self.client)
        self.assertEqual("desktop_executor_disabled", disabled.exception.code)

        self.manager.update_settings(enabled=True)
        self.manager.revoke_by_client(authorization["authorization_id"], self.client)
        with self.assertRaises(DesktopControlError) as revoked:
            self.manager.execute_request(self.request(authorization), self.client)
        self.assertEqual("authorization_not_found", revoked.exception.code)


if __name__ == "__main__":
    unittest.main()
