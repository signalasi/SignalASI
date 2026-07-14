from __future__ import annotations

import tempfile
import time
import unittest
import uuid
from pathlib import Path
from unittest.mock import patch

import link_protocol
import mqtt_bridge
import pairing_state
import phone_tool_broker


class FakeInfo:
    def __init__(self, mid: int = 1, rc: int = 0) -> None:
        self.mid = mid
        self.rc = rc


class FakeMqtt:
    def is_connected(self) -> bool:
        return True


class FakeMessage:
    def __init__(self, topic: str, signal_name: str) -> None:
        self.topic = topic
        self.payload = (
            '{"scheme":"signal","from":"' + signal_name + '","ciphertext":"test"}'
        ).encode("utf-8")


class MqttPhoneToolRoutingTests(unittest.TestCase):
    def setUp(self) -> None:
        self.temp = tempfile.TemporaryDirectory()
        self.state_patch = patch.object(
            pairing_state,
            "STATE_PATH",
            Path(self.temp.name) / "registry.json",
        )
        self.state_patch.start()
        with mqtt_bridge.phone_tool_sessions_lock:
            mqtt_bridge.phone_tool_sessions.clear()

        self.mqtt = FakeMqtt()
        self.desktop_id = "desktop_test"
        self.manifest_hash = phone_tool_broker.compute_manifest_hash(
            {"revision": 1, "tools": ["signalasi.workspace.file.read.text"]}
        )
        self.published: list[tuple[dict, dict, str]] = []
        self.decrypted: dict = {}
        self.agent_starts: list[tuple] = []
        self.patches = [
            patch.object(mqtt_bridge, "desktop_id", return_value=self.desktop_id),
            patch.object(mqtt_bridge, "claim_message", return_value=True),
            patch.object(mqtt_bridge, "complete_message"),
            patch.object(mqtt_bridge, "_publish_phone_payload", return_value=True),
            patch.object(
                mqtt_bridge,
                "decrypt_signal_envelope",
                side_effect=lambda *_args, **_kwargs: self.decrypted,
            ),
            patch.object(
                mqtt_bridge,
                "_publish_to_registered_client",
                side_effect=self._capture_publish,
            ),
            patch.object(
                mqtt_bridge,
                "_start_remote_agent_task",
                side_effect=lambda *args: self.agent_starts.append(args),
            ),
        ]
        for item in self.patches:
            item.start()

        self.first = self._pair_client("first-route", "signalasi:first-phone")
        self.second = self._pair_client("second-route", "signalasi:second-phone")

    def tearDown(self) -> None:
        with mqtt_bridge.phone_tool_sessions_lock:
            mqtt_bridge.phone_tool_sessions.clear()
        for item in reversed(self.patches):
            item.stop()
        self.state_patch.stop()
        self.temp.cleanup()

    def _pair_client(self, fingerprint: str, signal_name: str) -> dict:
        return pairing_state.record_pairing_success(
            fingerprint=fingerprint,
            remote_name=signal_name,
            client_route_id=link_protocol.new_route_id(),
            display_name=fingerprint,
            platform="android",
        )

    def _capture_publish(self, _mqttc, paired_client, payload, channel="down", durable=True):
        self.published.append((paired_client, payload, channel))
        return FakeInfo(mid=len(self.published))

    def _deliver(self, paired_client: dict, payload: dict, conversation_id: str = "conversation-1") -> None:
        self.decrypted = link_protocol.make_envelope(
            payload,
            source_id=paired_client["signal_name"],
            target_id=self.desktop_id,
            conversation_id=conversation_id,
        )
        mqtt_bridge.on_message(
            self.mqtt,
            None,
            FakeMessage(paired_client["topics"]["up"], paired_client["signal_name"]),
        )

    def _start_session(self, paired_client: dict | None = None) -> None:
        now_ms = int(time.time() * 1000)
        self._deliver(
            paired_client or self.first,
            {
                "protocol": phone_tool_broker.PROTOCOL_NAME,
                "version": phone_tool_broker.PROTOCOL_VERSION,
                "type": mqtt_bridge.TOOL_SESSION_START_TYPE,
                "message_id": str(uuid.uuid4()),
                "session_id": "session-1",
                "task_id": "task-1",
                "turn_id": "turn-1",
                "manifest_hash": self.manifest_hash,
                "sequence": 1,
                "sent_at": now_ms,
                "expires_at": now_ms + 60_000,
                "conversation_id": "conversation-1",
                "payload": {"goal": "Inspect selected workspace"},
            },
        )

    def _request_call(self, call_id: str = "call-1", sequence: int = 1) -> dict:
        return mqtt_bridge.request_phone_tool_call(
            "session-1",
            call_id=call_id,
            sequence=sequence,
            tool_id="signalasi.workspace.file.read.text",
            arguments={"path": "src/Main.kt"},
        )

    def test_session_start_binds_request_to_paired_encrypted_control_route(self):
        self._start_session()

        request = self._request_call()

        self.assertIn("session-1", mqtt_bridge.phone_tool_sessions)
        self.assertEqual(phone_tool_broker.REQUEST_TYPE, request["type"])
        paired_client, transport_payload, channel = self.published[-1]
        self.assertEqual(self.first["client_route_id"], paired_client["client_route_id"])
        self.assertEqual("control", channel)
        self.assertEqual(mqtt_bridge.TOOL_CALL_REQUEST_TYPE, transport_payload["type"])
        self.assertEqual("session-1", transport_payload["session_id"])
        self.assertEqual("conversation-1", transport_payload["conversation_id"])
        self.assertNotIn("mqtt_topic", transport_payload)
        self.assertEqual([], self.agent_starts)

    def test_result_from_other_paired_client_cannot_complete_session_call(self):
        self._start_session()
        request = self._request_call()
        response = phone_tool_broker.make_response_envelope(
            request,
            status="succeeded",
            result={"text": "done"},
        )
        transport_result = {**response, "type": mqtt_bridge.TOOL_CALL_RESULT_TYPE}

        self._deliver(self.second, transport_result)
        self.assertEqual(1, mqtt_bridge.phone_tool_sessions["session-1"].broker.pending_count)

        self._deliver(self.first, transport_result, conversation_id="conversation-2")
        self.assertEqual(1, mqtt_bridge.phone_tool_sessions["session-1"].broker.pending_count)

        self._deliver(self.first, transport_result)
        accepted = mqtt_bridge.wait_for_phone_tool_result("session-1", "call-1")
        self.assertEqual("succeeded", accepted["payload"]["status"])
        self.assertEqual({"text": "done"}, accepted["payload"]["result"])

    def test_desktop_and_phone_cancellation_envelopes_are_terminal(self):
        self._start_session()
        self._request_call()

        cancelled = mqtt_bridge.cancel_phone_tool_call("session-1", "call-1", "task stopped")

        self.assertEqual(phone_tool_broker.CANCEL_TYPE, cancelled["type"])
        self.assertEqual(mqtt_bridge.TOOL_CALL_CANCEL_TYPE, self.published[-1][1]["type"])
        self.assertEqual("control", self.published[-1][2])

        request = self._request_call(call_id="call-2", sequence=3)
        phone_cancel = phone_tool_broker.make_cancel_envelope(
            request,
            sequence=4,
            reason="permission revoked",
        )
        self._deliver(
            self.first,
            {**phone_cancel, "type": mqtt_bridge.TOOL_CALL_CANCEL_TYPE},
        )

        result = mqtt_bridge.wait_for_phone_tool_result("session-1", "call-2")
        self.assertEqual("cancelled", result["payload"]["status"])
        self.assertEqual("phone_cancelled", result["payload"]["error"]["code"])

    def test_regular_text_message_still_uses_existing_agent_flow(self):
        self._deliver(
            self.first,
            {
                "type": "text",
                "content": "hello",
                "contact_id": "hermes",
                "client_message_id": str(uuid.uuid4()),
            },
        )

        self.assertEqual(1, len(self.agent_starts))
        self.assertEqual({}, mqtt_bridge.phone_tool_sessions)

    def test_wrong_link_target_cannot_create_tool_session(self):
        now_ms = int(time.time() * 1000)
        payload = {
            "type": mqtt_bridge.TOOL_SESSION_START_TYPE,
            "session_id": "session-1",
            "task_id": "task-1",
            "turn_id": "turn-1",
            "manifest_hash": self.manifest_hash,
            "sequence": 1,
            "sent_at": now_ms,
            "expires_at": now_ms + 60_000,
        }
        self.decrypted = link_protocol.make_envelope(
            payload,
            source_id=self.first["signal_name"],
            target_id="some-other-desktop",
        )

        mqtt_bridge.on_message(
            self.mqtt,
            None,
            FakeMessage(self.first["topics"]["up"], self.first["signal_name"]),
        )

        self.assertEqual({}, mqtt_bridge.phone_tool_sessions)


if __name__ == "__main__":
    unittest.main()
