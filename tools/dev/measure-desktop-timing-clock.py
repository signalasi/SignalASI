"""Compare diagnostic clock quantization; does not start a backend or access task state."""

import argparse
import json
import math
from pathlib import Path
import sys
import time
import uuid

sys.path.insert(0, str(Path(__file__).resolve().parents[2] / "apps/desktop/core/galaxyssi-link/backend"))
from agent_timing_clock import now_ns, details


def distribution(values):
    ordered = sorted(value / 1_000_000 for value in values)
    return {
        "count": len(values),
        "zero_samples": values.count(0),
        "negative_samples": sum(value < 0 for value in values),
        "p50_ms": ordered[math.ceil(len(values) * .5) - 1],
        "p95_ms": ordered[math.ceil(len(values) * .95) - 1],
        "p99_ms": ordered[math.ceil(len(values) * .99) - 1],
    }


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--samples", type=int, default=100)
    args = parser.parse_args()
    if not 1 <= args.samples <= 10_000:
        parser.error("samples must be between 1 and 10000 for this diagnostic experiment")
    legacy, current = [], []
    for _ in range(args.samples):
        old_start = time.monotonic_ns()
        start = now_ns()
        time.sleep(.001)
        current.append(now_ns() - start)
        legacy.append(time.monotonic_ns() - old_start)
    info = time.get_clock_info("monotonic")
    print(json.dumps({
        "scope": "paired clock readings around a requested 1 ms sleep; not Agent latency or speedup",
        "legacy_clock": {"implementation": info.implementation, "resolution_ns": info.resolution * 1e9},
        "current_clock": details(uuid.uuid4().hex),
        "legacy": distribution(legacy),
        "current": distribution(current),
        "limitations": "Sleep scheduling varies. These percentiles describe only this clock experiment.",
    }, indent=2))
    return 1 if any(value <= 0 for value in current) else 0


if __name__ == "__main__":
    raise SystemExit(main())
