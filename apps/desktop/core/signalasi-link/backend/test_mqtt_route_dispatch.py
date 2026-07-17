from __future__ import annotations

import threading
import time
import unittest
from unittest.mock import patch

import mqtt_bridge


class FakeMessage:
    def __init__(self, topic: str, payload: bytes = b"{}") -> None:
        self.topic = topic
        self.payload = payload


class AlreadyPublishedInfo:
    mid = 41

    @staticmethod
    def is_published() -> bool:
        return True


class MqttRouteDispatchTests(unittest.TestCase):
    def setUp(self) -> None:
        mqtt_bridge._stop_inbound_route_workers()

    def tearDown(self) -> None:
        mqtt_bridge._stop_inbound_route_workers()
        with mqtt_bridge.pending_outbound_acks_lock:
            mqtt_bridge.pending_outbound_acks.clear()

    @staticmethod
    def _route(topic: str):
        route_id = "old-route" if topic == "old" else "current-route"
        return "server-route", route_id, "up"

    def test_stalled_old_route_does_not_block_current_phone(self) -> None:
        old_started = threading.Event()
        release_old = threading.Event()
        current_processed = threading.Event()

        def process(_mqttc, _userdata, message) -> None:
            if message.topic == "old":
                old_started.set()
                release_old.wait(2)
            else:
                current_processed.set()

        with (
            patch.object(mqtt_bridge, "parse_topic", side_effect=self._route),
            patch.object(mqtt_bridge, "server_route_id", return_value="server-route"),
            patch.object(mqtt_bridge, "_process_message", side_effect=process),
        ):
            mqtt_bridge.on_mqtt_message(object(), None, FakeMessage("old"))
            self.assertTrue(old_started.wait(1))
            mqtt_bridge.on_mqtt_message(object(), None, FakeMessage("current"))
            self.assertTrue(current_processed.wait(1))
            release_old.set()

    def test_signal_messages_remain_ordered_within_one_route(self) -> None:
        first_started = threading.Event()
        release_first = threading.Event()
        second_processed = threading.Event()
        processed: list[bytes] = []

        def process(_mqttc, _userdata, message) -> None:
            if message.payload == b"first":
                first_started.set()
                release_first.wait(2)
            processed.append(message.payload)
            if message.payload == b"second":
                second_processed.set()

        with (
            patch.object(mqtt_bridge, "parse_topic", return_value=("server-route", "one-route", "up")),
            patch.object(mqtt_bridge, "server_route_id", return_value="server-route"),
            patch.object(mqtt_bridge, "_process_message", side_effect=process),
        ):
            mqtt_bridge.on_mqtt_message(object(), None, FakeMessage("same", b"first"))
            self.assertTrue(first_started.wait(1))
            mqtt_bridge.on_mqtt_message(object(), None, FakeMessage("same", b"second"))
            time.sleep(0.05)
            self.assertFalse(second_processed.is_set())
            release_first.set()
            self.assertTrue(second_processed.wait(1))

        self.assertEqual([b"first", b"second"], processed)

    def test_publish_ack_that_wins_the_tracking_race_is_persisted(self) -> None:
        with patch.object(mqtt_bridge, "mark_outbound_published") as mark_published:
            mqtt_bridge.track_outbound_publish(AlreadyPublishedInfo(), "route", "message")

        mark_published.assert_called_once_with("route", "message")
        self.assertNotIn(AlreadyPublishedInfo.mid, mqtt_bridge.pending_outbound_acks)

    def test_returned_image_intent_is_detected_without_matching_plain_grading(self) -> None:
        self.assertTrue(mqtt_bridge._requests_returned_image("Annotate this and return the image"))
        self.assertTrue(mqtt_bridge._requests_returned_image("\u6279\u6539\u4f5c\u4e1a\u5e76\u53d1\u56de\u6765\u56fe\u7247"))
        self.assertFalse(mqtt_bridge._requests_returned_image("\u6279\u6539\u4f5c\u4e1a"))

    def test_returned_image_contract_targets_output_directory(self) -> None:
        contract = mqtt_bridge._returned_image_artifact_contract(mqtt_bridge.Path("outputs"))
        self.assertIn("finished annotated image", contract)
        self.assertIn("outputs", contract)


if __name__ == "__main__":
    unittest.main()
