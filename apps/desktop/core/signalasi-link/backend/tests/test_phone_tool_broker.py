from __future__ import annotations

import json
import unittest

import phone_tool_broker as broker_module


class FakeClock:
    def __init__(self, now_ms: int = 1_784_000_000_000) -> None:
        self.now_ms = now_ms

    def __call__(self) -> int:
        return self.now_ms


class PhoneToolBrokerTests(unittest.TestCase):
    def setUp(self) -> None:
        self.clock = FakeClock()
        self.sent: list[dict] = []
        self.events: list[dict] = []
        self.manifest_hash = broker_module.compute_manifest_hash(
            {"revision": 7, "tools": ["signalasi.workspace.file.read.text"]}
        )
        self.broker = broker_module.PhoneToolBroker(
            self.sent.append,
            on_event=self.events.append,
            clock_ms=self.clock,
            default_timeout_ms=1_000,
            max_result_bytes=256,
        )

    def start_call(self, **overrides):
        values = {
            "session_id": "session-1",
            "task_id": "task-1",
            "turn_id": "turn-1",
            "call_id": "call-1",
            "parent_call_id": "parent-call",
            "manifest_hash": self.manifest_hash,
            "sequence": 1,
            "tool_id": "signalasi.workspace.file.read.text",
            "arguments": {"path": "src/Main.kt"},
            "approval_handle": "approval:one-shot:1",
        }
        values.update(overrides)
        return self.broker.start_call(**values)

    def test_request_envelope_carries_full_correlation_without_transport_or_credentials(self):
        request = self.start_call()

        self.assertEqual(broker_module.PROTOCOL_NAME, request["protocol"])
        self.assertEqual("session-1", request["session_id"])
        self.assertEqual("task-1", request["task_id"])
        self.assertEqual("turn-1", request["turn_id"])
        self.assertEqual("call-1", request["tool_call_id"])
        self.assertEqual("parent-call", request["parent_tool_call_id"])
        self.assertEqual(self.manifest_hash, request["manifest_hash"])
        self.assertEqual(1, request["sequence"])
        self.assertEqual("approval:one-shot:1", request["approval_handle"])
        self.assertEqual(self.clock.now_ms + 1_000, request["expires_at"])
        self.assertEqual([request], self.sent)
        self.assertEqual(1, self.broker.pending_count)
        self.assertNotIn("ciphertext", request)
        self.assertNotIn("credentials", json.dumps(request).lower())

        snapshot = self.broker.pending_calls["call-1"]
        self.assertNotIn("arguments", snapshot)
        self.assertNotIn("tool_id", snapshot)

    def test_response_is_correlated_redacted_and_delivered_to_waiter(self):
        request = self.start_call()
        response = broker_module.make_response_envelope(
            request,
            status="succeeded",
            result={
                "text": "done",
                "password": "must-not-survive",
                "authorization": "Bearer must-not-survive",
            },
            now_ms=self.clock.now_ms + 10,
        )

        accepted = self.broker.receive_response(response)
        result = self.broker.wait_for_result("call-1")

        self.assertEqual(accepted, result)
        self.assertEqual("[REDACTED]", result["payload"]["result"]["password"])
        self.assertEqual("[REDACTED]", result["payload"]["result"]["authorization"])
        self.assertEqual(0, self.broker.pending_count)
        self.assertEqual(
            ["phone_tool_broker.call_requested", "phone_tool_broker.call_completed"],
            [event["type"] for event in self.events],
        )
        self.assertNotIn("must-not-survive", json.dumps(self.events))

    def test_oversized_result_is_replaced_with_a_bounded_terminal_summary(self):
        request = self.start_call()
        response = broker_module.make_response_envelope(
            request,
            status="succeeded",
            result={"content": "x" * 4_000},
            now_ms=self.clock.now_ms + 10,
        )

        result = self.broker.receive_response(response)

        payload_bytes = len(
            json.dumps(result["payload"], separators=(",", ":")).encode("utf-8")
        )
        self.assertLessEqual(payload_bytes, 256)
        self.assertTrue(result["payload"]["result"]["truncated"])
        self.assertNotIn("x" * 100, json.dumps(result))

    def test_duplicate_message_and_call_replay_are_rejected(self):
        request = self.start_call()
        response = broker_module.make_response_envelope(
            request, status="succeeded", result={"ok": True}, now_ms=self.clock.now_ms + 10
        )
        self.broker.receive_response(response)

        with self.assertRaises(broker_module.ReplayRejectedError):
            self.broker.receive_response(response)
        with self.assertRaises(broker_module.ReplayRejectedError):
            self.start_call()

    def test_wrong_manifest_or_identity_cannot_complete_pending_call(self):
        request = self.start_call()
        response = broker_module.make_response_envelope(
            request, status="succeeded", result={}, now_ms=self.clock.now_ms + 10
        )
        response["manifest_hash"] = "f" * 64

        with self.assertRaises(broker_module.ReplayRejectedError):
            self.broker.receive_response(response)
        self.assertEqual(1, self.broker.pending_count)

    def test_expiry_removes_pending_call_and_sends_cancel(self):
        self.start_call(timeout_ms=50)
        self.clock.now_ms += 50

        expired = self.broker.expire_pending()

        self.assertEqual(["call-1"], expired)
        self.assertEqual(0, self.broker.pending_count)
        self.assertEqual(broker_module.CANCEL_TYPE, self.sent[-1]["type"])
        self.assertEqual(2, self.sent[-1]["sequence"])
        with self.assertRaises(broker_module.PhoneToolTimeoutError):
            self.broker.wait_for_result("call-1")
        self.assertEqual("phone_tool_broker.call_timed_out", self.events[-1]["type"])

    def test_late_response_cannot_override_the_original_call_deadline(self):
        request = self.start_call(timeout_ms=50)
        response = broker_module.make_response_envelope(
            request,
            status="succeeded",
            result={"too_late": True},
            now_ms=self.clock.now_ms + 40,
            expires_at=self.clock.now_ms + 1_000,
        )
        self.clock.now_ms += 50

        with self.assertRaises(broker_module.PhoneToolTimeoutError):
            self.broker.receive_response(response)

        self.assertEqual(0, self.broker.pending_count)
        self.assertEqual(broker_module.CANCEL_TYPE, self.sent[-1]["type"])

    def test_explicit_cancel_is_terminal_and_idempotent(self):
        self.start_call()

        cancel = self.broker.cancel_call("call-1", "user stopped task")

        self.assertEqual(broker_module.CANCEL_TYPE, cancel["type"])
        self.assertEqual("user stopped task", cancel["payload"]["reason"])
        self.assertIsNone(self.broker.cancel_call("call-1"))
        with self.assertRaises(broker_module.PhoneToolCancelledError):
            self.broker.wait_for_result("call-1")

    def test_android_credentials_are_rejected_before_pending_or_send(self):
        with self.assertRaises(broker_module.EnvelopeValidationError):
            self.start_call(arguments={"android_permission_token": "grant-object"})

        self.assertEqual([], self.sent)
        self.assertEqual(0, self.broker.pending_count)

    def test_stale_outbound_sequence_is_rejected(self):
        self.start_call()

        with self.assertRaises(broker_module.ReplayRejectedError):
            self.start_call(call_id="call-2", sequence=1)
        self.assertNotIn("call-2", self.broker.pending_calls)

    def test_event_callback_failure_does_not_break_broker(self):
        def broken_callback(_event):
            raise RuntimeError("observer failed")

        self.broker.add_event_callback(broken_callback)
        request = self.start_call()
        response = broker_module.make_response_envelope(
            request, status="succeeded", result={"ok": True}, now_ms=self.clock.now_ms + 10
        )

        self.assertEqual("succeeded", self.broker.receive_response(response)["payload"]["status"])


class EnvelopeValidationTests(unittest.TestCase):
    def test_expired_response_is_rejected(self):
        now = 1_784_000_000_000
        manifest_hash = broker_module.compute_manifest_hash({"tools": []})
        request = broker_module.make_request_envelope(
            session_id="session",
            task_id="task",
            turn_id="turn",
            call_id="call",
            manifest_hash=manifest_hash,
            sequence=1,
            tool_id="signalasi.test",
            arguments={},
            now_ms=now,
            timeout_ms=100,
        )
        response = broker_module.make_response_envelope(
            request,
            status="failed",
            error={"code": "tool_unavailable"},
            now_ms=now + 10,
        )

        with self.assertRaises(broker_module.EnvelopeValidationError):
            broker_module.validate_phone_tool_envelope(response, now_ms=now + 100)


if __name__ == "__main__":
    unittest.main()
