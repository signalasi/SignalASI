"""Content-free measurements of individual recovery operations, not task execution."""

from contextlib import contextmanager
from dataclasses import dataclass
import json
import time
import uuid

from agent_latency import record_task

PHASES = frozenset({"lookup", "page", "restore", "publish"})
SCOPE = ("client_route_id", "conversation_id", "task_id", "turn_id", "contact_id",
         "source_message_id", "agent_id")


@dataclass
class RecoveryMeasurement:
    completed: bool = False


def _binding(fields, phase, request_id):
    if phase not in PHASES or not isinstance(fields, dict):
        return None
    values = [fields.get(key) for key in SCOPE]
    nonce = fields.get("request_id") if request_id is None else request_id
    generation = fields.get("execution_generation", 1)
    page = fields.get("page_index", -1)
    if (any(not isinstance(value, str) or not 1 <= len(value) <= 200 for value in values)
            or not isinstance(nonce, str) or not 1 <= len(nonce) <= 128
            or type(generation) is not int or not 1 <= generation <= 2**53 - 1
            or type(page) is not int or not -1 <= page <= 2**31 - 1):
        return None
    # The tracer hashes both IDs. Do not include payload text, hashes of content, or error strings.
    operation = json.dumps(["recovery", phase, *values, nonce, generation, page, uuid.uuid4().hex], separators=(",", ":"))
    return values[2], operation


def _record(binding, phase, boundary, at, outcome=""):
    if binding is None:
        return
    try:
        record_task(binding[0], f"desktop_recovery_{phase}_{boundary}",
                    operation_id=binding[1], outcome=outcome, at_ns=at, once=True)
    except Exception:
        pass


@contextmanager
def recovery_timing(fields, phase, *, request_id=None):
    start = time.monotonic_ns()
    try:
        binding = _binding(fields, phase, request_id)
    except Exception:
        binding = None
    result = RecoveryMeasurement()
    _record(binding, phase, "started", start)
    try:
        yield result
    except BaseException:
        result.completed = False
        raise
    finally:
        _record(binding, phase, "finished", time.monotonic_ns(),
                "completed" if result.completed else "failed")
