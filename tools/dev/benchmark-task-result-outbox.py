"""Exercise real encrypted outbox APIs at scale without touching live Desktop state."""

from pathlib import Path
import json
import os
import statistics
import sys
import tempfile
import time


def main() -> None:
    root = Path(__file__).resolve().parents[2]
    with tempfile.TemporaryDirectory(prefix="galaxyssi-outbox-scale-") as home:
        os.environ.update(HOME=home, USERPROFILE=home, APPDATA=home,
                          GALAXYSSI_STATE_DIR=str(Path(home) / "GalaxySSI"))
        sys.dont_write_bytecode = True
        sys.path.insert(0, str(root / "apps/desktop/core/galaxyssi-link/backend"))
        import link_delivery

        link_delivery.DB_PATH = Path(home) / "delivery.db"
        start = time.perf_counter()
        for index in range(10000):
            task = f"outbox-scale-{index:05}"
            payload = {"client_route_id": "test-phone", "conversation_id": task,
                       "task_id": task, "turn_id": "turn", "contact_id": "contact",
                       "source_message_id": str(index), "agent_id": "test-agent",
                       "content": "test result " * 64, "execution_generation": 1}
            link_delivery.queue_task_result(task, "test-phone", {"_client_route_id": "test-phone"}, payload)
        write_ms = (time.perf_counter() - start) * 1000
        seen, timings = set(), []
        for _ in range(313):
            start = time.perf_counter()
            page = link_delivery.pending_task_results()
            timings.append((time.perf_counter() - start) * 1000)
            assert len(page) <= 32
            seen.update(record["task_id"] for record in page)
        assert len(seen) == 10000, len(seen)
        db = link_delivery._connect()
        try:
            count = db.execute("SELECT count(*) FROM task_result_queue WHERE state='pending'").fetchone()[0]
            plan = db.execute("EXPLAIN QUERY PLAN SELECT * FROM task_result_queue WHERE state='pending' "
                              "ORDER BY last_attempt_at,created_at,scope LIMIT 32").fetchall()
        finally:
            db.close()
        assert count == 10000
        assert any("task_result_queue_pending" in row[3] for row in plan), plan
        print(json.dumps({"records": count, "distinct_read": len(seen), "page_limit": 32,
            "write_ms": round(write_ms, 3), "first_page_ms": round(timings[0], 3),
            "p50_page_ms": round(statistics.median(timings), 3),
            "p95_page_ms": round(sorted(timings)[int(len(timings) * .95)], 3),
            "p99_page_ms": round(sorted(timings)[int(len(timings) * .99)], 3),
            "database_bytes": link_delivery.DB_PATH.stat().st_size, "query_plan": plan}, indent=2))


if __name__ == "__main__":
    main()
