"""Content-free, clock-domain-safe timing for ordinary Agent requests."""

from __future__ import annotations

from collections import OrderedDict, defaultdict
from dataclasses import asdict, dataclass
import hashlib
import math
import os
from pathlib import Path
import re
import threading
import time
import uuid


SCHEMA = "galaxyssi.agent-latency.v1"
TRACE_LIMIT = 8_000
STAGE_PAIRS = {
    "desktop_recovery_lookup_ms": ("desktop_recovery_lookup_started", "desktop_recovery_lookup_finished"),
    "desktop_recovery_page_ms": ("desktop_recovery_page_started", "desktop_recovery_page_finished"),
    "desktop_recovery_restore_ms": ("desktop_recovery_restore_started", "desktop_recovery_restore_finished"),
    "desktop_recovery_publish_ms": ("desktop_recovery_publish_started", "desktop_recovery_publish_finished"),
    "phone_transport_queue_ms": ("phone_transport_queued", "phone_transport_dispatched"),
    "phone_broker_ack_ms": ("phone_wire_started", "phone_broker_acked"),
    "phone_peer_receipt_ms": ("phone_transport_queued", "phone_peer_received"),
    "desktop_transport_queue_ms": ("desktop_transport_queued", "desktop_transport_dispatched"),
    "desktop_broker_ack_ms": ("desktop_wire_started", "desktop_broker_acked"),
    "desktop_peer_receipt_ms": ("desktop_transport_queued", "desktop_peer_received"),
    "phone_context_route_ms": ("phone_send_started", "phone_publish_started"),
    "phone_send_prepare_ms": ("phone_send_started", "phone_request_queued"),
    "phone_send_first_visible_ms": ("phone_send_started", "phone_first_output_visible"),
    "phone_publish_prepare_ms": ("phone_publish_started", "phone_request_queued"),
    "phone_response_roundtrip_ms": ("phone_request_queued", "phone_response_received"),
    "phone_connector_first_visible_ms": ("phone_publish_started", "phone_first_output_visible"),
    "phone_connector_complete_visible_ms": ("phone_publish_started", "phone_final_output_visible"),
    "phone_render_ms": ("phone_response_received", "phone_first_output_visible"),
    "desktop_prepare_ms": ("desktop_request_received", "desktop_task_created"),
    "desktop_receive_queue_ms": ("desktop_request_received", "desktop_decrypt_started"),
    "desktop_decrypt_ms": ("desktop_decrypt_started", "desktop_request_decrypted"),
    "desktop_queue_ms": ("desktop_task_created", "desktop_agent_started"),
    "desktop_first_output_ms": ("desktop_agent_started", "desktop_first_output"),
    "desktop_execution_ms": ("desktop_agent_started", "desktop_task_completed"),
    "desktop_finalize_ms": ("desktop_first_output", "desktop_task_completed"),
    "desktop_response_enqueue_ms": ("desktop_task_completed", "desktop_response_enqueued"),
    "desktop_tool_ms": ("desktop_tool_started", "desktop_tool_completed"),
    "desktop_model_submit_ms": ("desktop_model_submit_started", "desktop_model_submitted"),
    "desktop_model_first_output_ms": ("desktop_model_submit_started", "desktop_first_output"),
}
STAGES = frozenset(stage for pair in STAGE_PAIRS.values() for stage in pair) | {"phone_final_received"}
OUTCOMES = frozenset({"", "completed", "failed", "cancelled", "timed_out"})


@dataclass(frozen=True)
class AgentTimingPoint:
    trace_id: str
    clock_id: str
    stage: str
    monotonic_ns: int
    wall_clock_ms: int
    operation_id: str = ""
    provider: str = ""
    outcome: str = ""

    @classmethod
    def decode(cls, raw: dict) -> AgentTimingPoint:
        point = cls(**{key: raw.get(key, "") for key in cls.__dataclass_fields__})
        if (not re.fullmatch(r"[a-f0-9]{64}", point.trace_id)
                or not re.fullmatch(r"[a-f0-9]{32}", point.clock_id)
                or point.stage not in STAGES or point.outcome not in OUTCOMES
                or (point.operation_id and not re.fullmatch(r"[a-f0-9]{64}", point.operation_id))
                or not re.fullmatch(r"[A-Za-z0-9._:-]{0,96}", point.provider)
                or type(point.monotonic_ns) is not int or point.monotonic_ns < 0
                or type(point.wall_clock_ms) is not int or point.wall_clock_ms < 0):
            raise ValueError("Invalid Agent timing point")
        return point


def opaque_id(value: str) -> str:
    return hashlib.sha256(value.encode("utf-8")).hexdigest()


def summarize(points: list[AgentTimingPoint]) -> dict:
    groups = defaultdict(list)
    for point in points:
        groups[(point.trace_id, point.clock_id, point.operation_id)].append(point)
    metrics = {}
    for name, (start_stage, end_stage) in STAGE_PAIRS.items():
        samples = []
        incomplete = unsuccessful = 0
        for group in groups.values():
            starts = [p.monotonic_ns for p in group if p.stage == start_stage]
            if not starts:
                continue
            start = min(starts)
            ends = [p for p in group if p.stage == end_stage and p.monotonic_ns >= start]
            if not ends:
                incomplete += 1
                continue
            end = min(ends, key=lambda p: p.monotonic_ns)
            if end.outcome in {"failed", "cancelled", "timed_out"}:
                unsuccessful += 1
                continue
            samples.append((end.monotonic_ns - start) / 1_000_000)
        samples.sort()
        def percentile(fraction):
            return round(samples[math.ceil(fraction * len(samples)) - 1], 3) if samples else None
        metrics[name] = {"count": len(samples), "incomplete": incomplete,
                         "unsuccessful": unsuccessful, "p50_ms": percentile(.50),
                         "p95_ms": percentile(.95), "p99_ms": percentile(.99)}
    return {"schema": SCHEMA, "content_included": False, "event_count": len(points),
            "trace_count": len({p.trace_id for p in points}), "metrics": metrics,
            "cross_device_clock_subtraction": False}


class AgentLatencyTracer:
    def __init__(self, sink, *, monotonic_ns=time.monotonic_ns,
                 wall_clock_ms=lambda: int(time.time() * 1000), clock_id=None):
        self.sink = sink
        self.monotonic_ns = monotonic_ns
        self.wall_clock_ms = wall_clock_ms
        self.clock_id = clock_id or uuid.uuid4().hex
        self._seen = OrderedDict()
        self._lock = threading.Lock()

    def record(self, task_id: str, stage: str, *, operation_id="", provider="", outcome="",
               at_ns=None, once=False):
        if not task_id or stage not in STAGES:
            return
        trace_id = opaque_id(task_id)
        operation = opaque_id(operation_id) if operation_id else ""
        self.record_opaque(trace_id, stage, operation=operation, provider=provider,
                           outcome=outcome, at_ns=at_ns, once=once)

    def record_opaque(self, trace_id, stage, *, operation="", provider="", outcome="",
                      at_ns=None, once=True):
        if stage not in STAGES:
            return
        if once:
            with self._lock:
                key = (trace_id, stage, operation)
                if key in self._seen:
                    return
                self._seen[key] = True
                if len(self._seen) > TRACE_LIMIT:
                    self._seen.popitem(last=False)
        safe_provider = str(provider or "")
        if not re.fullmatch(r"[A-Za-z0-9._:-]{0,96}", safe_provider):
            safe_provider = ""
        self.sink.append(AgentTimingPoint(
            trace_id, self.clock_id, stage,
            max(0, self.monotonic_ns() if at_ns is None else at_ns),
            max(0, self.wall_clock_ms()), operation,
            safe_provider, outcome if outcome in OUTCOMES else "",
        ))

    def summary(self):
        result = summarize(self.sink.snapshot())
        result.update(self.sink.health())
        return result


_instance = None
_lock = threading.Lock()


def tracer() -> AgentLatencyTracer:
    global _instance
    if _instance is None:
        with _lock:
            if _instance is None:
                from agent_latency_store import AgentTimingJournal
                root = Path(os.environ.get("GALAXYSSI_STATE_DIR") or
                            str(Path(os.environ.get("APPDATA") or Path.home()) / "GalaxySSI"))
                _instance = AgentLatencyTracer(AgentTimingJournal(root / "diagnostics/agent_latency_v1.jsonl"))
    return _instance


def record_task(task_id: str, stage: str, **kwargs) -> None:
    if os.environ.get("GALAXYSSI_AGENT_LATENCY_TRACING", "1").lower() in {"0", "false", "off"}:
        return
    try:
        tracer().record(task_id, stage, **kwargs)
    except Exception:
        # Diagnostics cannot fail the task or its transport.
        pass


def export_snapshot() -> dict:
    current = tracer()
    points = current.sink.snapshot()
    return {**summarize(points), **current.sink.health(),
            "events": [asdict(point) for point in points]}
