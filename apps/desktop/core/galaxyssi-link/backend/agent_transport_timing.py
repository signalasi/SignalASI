"""Local, bounded ACK measurements. Never part of reliable delivery state."""
from collections import OrderedDict
from dataclasses import dataclass
import os
import threading
import uuid

from agent_latency import opaque_id
from agent_timing_clock import now_ns


@dataclass(frozen=True)
class Attempt:
    operation: str
    trace: str
    born: int


class TransportTiming:
    def __init__(self, emit, *, now_ns=now_ns, limit=1024, ttl_ns=3_600_000_000_000):
        self.emit, self.now_ns = emit, now_ns
        self.limit, self.ttl_ns = max(1, limit), ttl_ns
        self.messages, self.attempts = OrderedDict(), OrderedDict()
        self.bindings, self.early = OrderedDict(), OrderedDict()
        self.lock = threading.RLock()

    @staticmethod
    def key(endpoint, message):
        return opaque_id(f"{len(endpoint)}:{endpoint}{message}")

    def _emit(self, trace, stage, operation, outcome="", at=None):
        try:
            self.emit(trace, stage, operation, outcome, self.now_ns() if at is None else at)
        except Exception:
            pass

    def _bound(self, mapping):
        while len(mapping) > self.limit:
            mapping.popitem(last=False)

    def queued(self, endpoint, message, task):
        if not endpoint or not message or not task:
            return
        with self.lock:
            key = self.key(endpoint, message)
            if key in self.messages:
                return
            now = self.now_ns()
            self.messages[key] = [opaque_id(task), now, False]
            self._bound(self.messages)
            self._emit(opaque_id(task), "desktop_transport_queued", key, at=now)

    def begin(self, endpoint, message):
        with self.lock:
            key, now = self.key(endpoint, message), self.now_ns()
            metadata = self.messages.get(key)
            if metadata is None:
                return None
            trace, born, dispatched = metadata
            if now - born > self.ttl_ns:
                self.messages.pop(key, None)
                return None
            if not dispatched:
                metadata[2] = True
                self._emit(trace, "desktop_transport_dispatched", key, at=now)
            attempt = Attempt(opaque_id(uuid.uuid4().hex), trace, now)
            self.attempts[attempt.operation] = attempt
            self._bound(self.attempts)
            self._emit(trace, "desktop_wire_started", attempt.operation, at=now)
            return attempt

    def broker(self, attempt, outcome="completed", at=None):
        with self.lock:
            if attempt is None or self.attempts.pop(attempt.operation, None) != attempt:
                return
            self._emit(attempt.trace, "desktop_broker_acked", attempt.operation, outcome, at)

    def bind(self, key, attempt):
        if attempt is None:
            return
        with self.lock:
            early = self.early.pop(key, None)
            if early is not None and early[0] >= attempt.born:
                self.broker(attempt, early[1], early[0])
            else:
                self.bindings[key] = attempt
                self._bound(self.bindings)

    def acknowledged(self, key, outcome="completed", at=None):
        with self.lock:
            at = self.now_ns() if at is None else at
            attempt = self.bindings.pop(key, None)
            if attempt is not None:
                self.broker(attempt, outcome, at)
            else:
                self.early[key] = (at, outcome)
                self._bound(self.early)

    def received(self, endpoint, message):
        with self.lock:
            key = self.key(endpoint, message)
            metadata = self.messages.pop(key, None)
            if metadata is not None and self.now_ns() - metadata[1] <= self.ttl_ns:
                self._emit(metadata[0], "desktop_peer_received", key, "completed")

    def disconnected(self):
        with self.lock:
            for attempt in list(self.attempts.values()):
                self.broker(attempt, "cancelled")
            self.bindings.clear()
            self.early.clear()


def _emit(trace, stage, operation, outcome, at):
    if os.environ.get("GALAXYSSI_AGENT_LATENCY_TRACING", "1").lower() in {"0", "false", "off"}:
        return
    from agent_latency import tracer
    tracer().record_opaque(trace, stage, operation=operation, outcome=outcome, at_ns=at)


transport_timing = TransportTiming(_emit)
