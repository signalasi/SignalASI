import unittest
from types import SimpleNamespace
from unittest.mock import patch

from agent_latency import AgentLatencyTracer, summarize
from agent_transport_timing import TransportTiming
from test_agent_latency import MemorySink


class TimingFixture:
    def setUp(self):
        self.now = 0
        self.sink = MemorySink()
        self.tracer = AgentLatencyTracer(self.sink, monotonic_ns=lambda: self.now)
        self.timing = TransportTiming(self.emit, now_ns=lambda: self.now)

    def emit(self, trace, stage, operation, outcome, at):
        self.tracer.record_opaque(trace, stage, operation=operation, outcome=outcome, at_ns=at)

    def at(self, ms):
        self.now = ms * 1_000_000

    def metric(self, name):
        return summarize(self.sink.points)["metrics"][f"desktop_{name}_ms"]

class TransportTimingTest(TimingFixture, unittest.TestCase):
    def test_queue_broker_and_peer_are_different_boundaries(self):
        self.timing.queued("phone", "message", "task")
        self.at(10)
        attempt = self.timing.begin("phone", "message")
        self.at(20)
        self.timing.broker(attempt)
        self.assertEqual(0, self.metric("peer_receipt")["count"])
        self.at(40)
        self.timing.received("phone", "message")
        self.assertEqual(10, self.metric("transport_queue")["p50_ms"])
        self.assertEqual(10, self.metric("broker_ack")["p50_ms"])
        self.assertEqual(40, self.metric("peer_receipt")["p50_ms"])

    def test_ack_before_registration_uses_callback_time(self):
        self.timing.queued("p", "m", "t")
        attempt = self.timing.begin("p", "m")
        self.at(5)
        self.timing.acknowledged((1, 2, 3))
        self.at(100)
        self.timing.bind((1, 2, 3), attempt)
        self.assertEqual(5, self.metric("broker_ack")["p50_ms"])

    def test_retry_is_distinct_and_disconnect_invalidates_old_handle(self):
        self.timing.queued("p", "m", "t")
        old = self.timing.begin("p", "m")
        self.timing.bind((1, 0, 4), old)
        self.at(12)
        self.timing.disconnected()
        self.at(20)
        retry = self.timing.begin("p", "m")
        self.timing.bind((1, 1, 4), retry)
        self.timing.acknowledged((1, 0, 4))
        self.timing.broker(old)
        self.assertEqual(0, self.metric("broker_ack")["count"])
        self.at(23)
        self.timing.acknowledged((1, 1, 4))
        self.assertEqual((1, 1, 3), tuple(self.metric("broker_ack")[k] for k in ("count", "unsuccessful", "p50_ms")))

    def test_old_mid_ack_cannot_complete_later_attempt(self):
        self.timing.acknowledged((1, 1, 3))
        self.at(20)
        self.timing.queued("p", "m", "t")
        self.timing.bind((1, 1, 3), self.timing.begin("p", "m"))
        self.assertEqual(0, self.metric("broker_ack")["count"])
        self.at(25)
        self.timing.acknowledged((1, 1, 3))
        self.assertEqual(5, self.metric("broker_ack")["p50_ms"])

    def test_route_isolation_duplicate_receipt_and_peer_before_broker(self):
        self.timing.queued("p", "m", "t")
        attempt = self.timing.begin("p", "m")
        self.timing.received("wrong-phone", "m")
        self.assertEqual(0, self.metric("peer_receipt")["count"])
        self.at(3)
        self.timing.received("p", "m")
        self.timing.received("p", "m")
        self.at(5)
        self.timing.broker(attempt)
        self.assertEqual(1, self.metric("peer_receipt")["count"])
        self.assertEqual(5, self.metric("broker_ack")["p50_ms"])

    def test_capacity_ttl_and_restart_never_fabricate_samples(self):
        timing = TransportTiming(self.emit, now_ns=lambda: self.now, limit=2, ttl_ns=10_000_000)
        for i in range(3):
            timing.queued("p", str(i), "t")
            timing.acknowledged((1, 1, i))
        self.assertIsNone(timing.begin("p", "0"))
        self.assertLessEqual(len(timing.early), 2)
        self.at(11)
        self.assertIsNone(timing.begin("p", "2"))
        self.assertIsNone(TransportTiming(self.emit).begin("p", "1"))

    def test_failed_ack_and_duplicate_callback_are_not_successes(self):
        self.timing.queued("p", "m", "t")
        attempt = self.timing.begin("p", "m")
        self.timing.bind((1, 1, 1), attempt)
        self.at(5)
        self.timing.acknowledged((1, 1, 1), "failed")
        self.timing.acknowledged((1, 1, 1))
        self.timing.broker(attempt)
        self.assertEqual((0, 1), tuple(self.metric("broker_ack")[k] for k in ("count", "unsuccessful")))

    def test_sink_failure_is_nonfatal(self):
        def broken(*args):
            raise OSError("full disk")
        timing = TransportTiming(broken)
        timing.queued("p", "m", "t")
        timing.broker(timing.begin("p", "m"))
        timing.received("p", "m")


class MqttTransportTimingTest(TimingFixture, unittest.TestCase):
    def test_real_publish_hook_uses_early_callback_not_publish_return_time(self):
        import mqtt_bridge as bridge
        self.timing.queued("p", "m", "t")
        def publish(*args, **kwargs):
            self.at(5)
            bridge.on_publish(client, None, 23456)
            self.at(90)
            return SimpleNamespace(mid=23456, rc=0)
        client = SimpleNamespace(publish=publish)
        with patch.object(bridge, "transport_timing", self.timing), \
             patch.object(bridge, "encode_wire_payload", return_value=["packet"]), \
             patch.object(bridge, "seal_wire_packet", side_effect=lambda value, _: value), \
             patch.object(bridge.time, "monotonic_ns", side_effect=lambda: self.now):
            bridge._publish_mqtt_wire_payload(client, "topic", "wire", "secret", timing_scope=("p", "m"))
        self.assertEqual(5, self.metric("broker_ack")["p50_ms"])

    def test_real_fragment_hook_waits_for_all_fragments(self):
        import mqtt_bridge as bridge
        self.timing.queued("p", "m", "t")
        mids = []
        def publish(*args, **kwargs):
            mid = 24500 + len(mids)
            mids.append(mid)
            return SimpleNamespace(mid=mid, rc=0)
        client = SimpleNamespace(publish=publish)
        with patch.object(bridge, "transport_timing", self.timing), \
             patch.object(bridge, "encode_wire_payload", return_value=["a", "b", "c"]), \
             patch.object(bridge, "seal_wire_packet", side_effect=lambda value, _: value), \
             patch.object(bridge.time, "monotonic_ns", side_effect=lambda: self.now):
            info = bridge._publish_mqtt_wire_payload(client, "topic", "unique-fragment-wire", "secret", timing_scope=("p", "m"))
            self.at(2); bridge.on_publish(client, None, mids[1])
            self.at(4); bridge.on_publish(client, None, mids[0])
            self.assertEqual(0, self.metric("broker_ack")["count"])
            self.at(7); bridge.on_publish(client, None, mids[2])
            self.assertTrue(info.is_published())
        self.assertEqual(7, self.metric("broker_ack")["p50_ms"])
