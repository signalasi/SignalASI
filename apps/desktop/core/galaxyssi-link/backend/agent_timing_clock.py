"""One high-resolution monotonic clock for Agent diagnostics, never deadlines."""

import time


def now_ns() -> int:
    return time.perf_counter_ns()


def details(clock_id: str) -> dict:
    info = time.get_clock_info("perf_counter")
    return {
        "clock_id": clock_id,
        "source": "perf_counter_ns",
        "implementation": info.implementation,
        "resolution_ns": info.resolution * 1_000_000_000,
        "monotonic": info.monotonic,
        "adjustable": info.adjustable,
        "scope": "active_clock_only",
    }
