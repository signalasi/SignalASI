import ast
import tokenize
from pathlib import Path
from types import SimpleNamespace
import unittest
from unittest.mock import Mock, patch

from agent_latency import AgentLatencyTracer, summarize
from agent_recovery_timing import recovery_timing
from agent_transport_timing import TransportTiming
from agent_timing_clock import now_ns
import mqtt_bridge as bridge


class Sink:
    def __init__(self):
        self.points = []

    def append(self, point):
        self.points.append(point)

    def snapshot(self):
        return list(self.points)

    def health(self):
        return {}


class AgentTimingClockTest(unittest.TestCase):
    def setUp(self):
        self.sink = Sink()
        self.tracer = AgentLatencyTracer(self.sink)
        self.now = 9_000_000_000_000
        counter = patch("agent_timing_clock.time.perf_counter_ns", side_effect=lambda: self.now)
        counter.start()
        self.addCleanup(counter.stop)
        coarse = patch("time.monotonic_ns", side_effect=AssertionError("coarse diagnostic clock"))
        coarse.start()
        self.addCleanup(coarse.stop)

    def metric(self, name):
        return summarize(self.sink.points)["metrics"][name]

    def emit(self, trace, stage, operation, outcome, at):
        self.tracer.record_opaque(trace, stage, operation=operation, outcome=outcome, at_ns=at)

    def test_default_tracer_resolves_sub_millisecond_spans(self):
        self.tracer.record("task", "desktop_agent_started")
        self.now += 125_000
        self.tracer.record("task", "desktop_first_output")
        self.assertEqual(.125, self.metric("desktop_first_output_ms")["p95_ms"])
        self.assertEqual(self.now, now_ns())

    def test_recovery_uses_same_high_resolution_clock(self):
        fields = {key: key for key in ("client_route_id", "conversation_id", "task_id", "turn_id",
                                      "contact_id", "source_message_id", "agent_id")}
        with patch("agent_recovery_timing.record_task", side_effect=self.tracer.record):
            with recovery_timing(fields, "page", request_id="request") as measurement:
                self.now += 250_000
                measurement.completed = True
        self.assertEqual(.25, self.metric("desktop_recovery_page_ms")["p95_ms"])

    def test_actual_mqtt_ack_callback_shares_transport_clock(self):
        timing = TransportTiming(self.emit)
        timing.queued("route", "message", "task")
        self.now += 100_000
        attempt = timing.begin("route", "message")
        client = SimpleNamespace()
        key = (id(client), bridge.mqtt_connection_generation, 787812)
        timing.bind(key, attempt)
        self.now += 350_000
        with patch.object(bridge, "transport_timing", timing), \
                patch.object(bridge, "_complete_fragment_publish", return_value=(True, None)):
            bridge.on_publish(client, None, key[2])
        self.assertEqual(.1, self.metric("desktop_transport_queue_ms")["p95_ms"])
        self.assertEqual(.35, self.metric("desktop_broker_ack_ms")["p95_ms"])

    def test_actual_inbound_callback_and_default_tracer_share_origin(self):
        with patch.object(bridge, "_handle_transport_probe_message", return_value=False), \
                patch.object(bridge, "_resolve_inbound_topic", return_value=("client", {"client_route_id": "route"})), \
                patch.object(bridge, "transport_probe_state", Mock()), \
                patch.object(bridge, "_queue_inbound_message") as queued:
            bridge.on_mqtt_message(SimpleNamespace(), None, SimpleNamespace(topic="opaque", payload=b"test"))
        received = queued.call_args.args[2].received_at_ns
        self.assertEqual(self.now, received)
        self.now += 750_000
        self.tracer.record("task", "desktop_request_received", at_ns=received)
        self.tracer.record("task", "desktop_decrypt_started")
        self.assertEqual(.75, self.metric("desktop_receive_queue_ms")["p95_ms"])

    def test_summary_describes_active_clock_not_old_journal_clocks(self):
        info = SimpleNamespace(implementation="counter", resolution=1e-7, monotonic=True, adjustable=False)
        with patch("agent_timing_clock.time.get_clock_info", return_value=info):
            clock = self.tracer.summary()["active_clock"]
        self.assertEqual(self.tracer.clock_id, clock["clock_id"])
        self.assertEqual("perf_counter_ns", clock["source"])
        self.assertEqual(100, clock["resolution_ns"])
        self.assertEqual("active_clock_only", clock["scope"])
        self.assertTrue(clock["monotonic"])
        self.assertFalse(clock["adjustable"])

    def test_injected_test_clock_is_not_mislabelled_as_production_counter(self):
        tracer = AgentLatencyTracer(self.sink, monotonic_ns=lambda: 42)
        self.assertEqual("injected", tracer.summary()["active_clock"]["source"])
        self.assertNotIn("resolution_ns", tracer.summary()["active_clock"])

    def test_new_process_clock_does_not_join_old_partial_span(self):
        old = AgentLatencyTracer(self.sink, monotonic_ns=lambda: 1_000_000)
        old.record("task", "desktop_agent_started")
        self.tracer.record("task", "desktop_first_output")
        metric = self.metric("desktop_first_output_ms")
        self.assertEqual((0, 1), (metric["count"], metric["incomplete"]))

    def test_trace_producers_do_not_reintroduce_coarse_clock_calls(self):
        root = Path(__file__).parent
        for name in ("agent_latency.py", "agent_recovery_timing.py", "agent_transport_timing.py", "mqtt_bridge.py"):
            with tokenize.open(root / name) as source:
                tree = ast.parse(source.read())
            aliases = {alias.asname or alias.name for node in ast.walk(tree) if isinstance(node, ast.Import)
                       for alias in node.names if alias.name == "time"}
            coarse = [node for node in ast.walk(tree) if isinstance(node, ast.Call)
                      and isinstance(node.func, ast.Attribute) and node.func.attr == "monotonic_ns"
                      and isinstance(node.func.value, ast.Name) and node.func.value.id in aliases]
            coarse += [node for node in ast.walk(tree) if isinstance(node, ast.ImportFrom)
                       and node.module == "time" and any(alias.name == "monotonic_ns" for alias in node.names)]
            self.assertEqual([], coarse, name)


if __name__ == "__main__":
    unittest.main()
