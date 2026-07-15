"""Run representative Codex-style response checks against the official App Server."""
from __future__ import annotations

import argparse
import json
import sys
import tempfile
import threading
import time
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
BACKEND = ROOT / "core" / "signalasi-link" / "backend"
sys.path.insert(0, str(BACKEND))

from agent_gateway import BASE_AGENTS, _agent_env, _find_codex_desktop_cli
from codex_app_server import CodexAppServer
from response_policy import sanitize_assistant_response


CASES = [
    {
        "id": "concise_calculation",
        "prompt": "\u53ea\u7ed9\u51fa 37 + 58 \u7684\u7ed3\u679c\u3002",
        "expect_question": False,
    },
    {
        "id": "vague_request",
        "prompt": "\u5e2e\u6211\u5904\u7406\u4e00\u4e0b\u3002",
        "expect_question": True,
    },
    {
        "id": "missing_file",
        "prompt": "Read missing-report.csv and tell me the key result.",
        "expect_question": False,
    },
    {
        "id": "attachment_without_goal",
        "prompt": "Input attachment: sample.txt\nThe user attached this file without an instruction.",
        "expect_question": True,
    },
    {
        "id": "explicit_file_summary",
        "prompt": "Read sample.txt and summarize it in two short bullets.",
        "expect_question": False,
    },
    {
        "id": "current_information",
        "prompt": "\u67e5\u8be2\u4eca\u5929\u4e0a\u6d77\u7684\u5929\u6c14\uff0c\u7ed9\u51fa\u6e29\u5ea6\u548c\u7b80\u77ed\u51fa\u884c\u5efa\u8bae\u3002",
        "expect_question": False,
    },
]


def evaluate(case: dict, response: str, elapsed_ms: int, statuses: list[str]) -> dict:
    clean = sanitize_assistant_response(response)
    lower = clean.lower()
    forbidden = [
        token for token in ("as an ai", "system prompt", "traceback", "mcp_", "preparing tool")
        if token in lower
    ]
    has_question = "?" in clean or "\uff1f" in clean
    passed = bool(clean) and not forbidden and len(clean) <= 4_000
    if case.get("expect_question"):
        passed = passed and has_question
    return {
        "id": case["id"],
        "input": case["prompt"],
        "actual_response": clean,
        "elapsed_ms": elapsed_ms,
        "statuses": statuses,
        "duplicate_attachment": "downloads/input/" in lower,
        "over_explained": len(clean) > 4_000 or bool(forbidden),
        "codex_style": passed,
        "passed": passed and "downloads/input/" not in lower,
    }


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--start", type=int, default=0)
    parser.add_argument("--limit", type=int, default=len(CASES))
    parser.add_argument("--timeout", type=int, default=180)
    args = parser.parse_args()
    executable = _find_codex_desktop_cli() or "codex"
    events: dict[str, list[dict]] = {}
    completed: dict[str, threading.Event] = {}

    def on_event(task_id: str, event: dict) -> None:
        events.setdefault(task_id, []).append(dict(event))
        if str(event.get("status") or "") in {"completed", "failed", "cancelled"}:
            completed.setdefault(task_id, threading.Event()).set()

    server = CodexAppServer(executable, _agent_env(BASE_AGENTS["codex"]), on_event)
    records = []
    try:
        warm = server.warm()
        with tempfile.TemporaryDirectory(
            prefix="signalasi-live-regression-", ignore_cleanup_errors=True
        ) as temporary:
            selected_cases = CASES[max(0, args.start):][: max(1, args.limit)]
            for index, case in enumerate(selected_cases, start=max(0, args.start)):
                workspace = Path(temporary) / case["id"]
                workspace.mkdir(parents=True, exist_ok=True)
                if case["id"] in {"attachment_without_goal", "explicit_file_summary"}:
                    (workspace / "sample.txt").write_text(
                        "SignalASI connects trusted mobile clients to desktop agents.\n"
                        "The current focus is low latency and concise, actionable responses.\n",
                        encoding="utf-8",
                    )
                task_id = f"live-{index + 1}-{int(time.time() * 1000)}"
                completed[task_id] = threading.Event()
                started = time.perf_counter()
                try:
                    server.start_task(
                        task_id,
                        case["prompt"],
                        str(workspace),
                        conversation_id=f"live-regression-{case['id']}-{time.time_ns()}",
                    )
                    if not completed[task_id].wait(args.timeout):
                        raise TimeoutError("Codex live regression timed out")
                    terminal = events.get(task_id, [])[-1]
                    response = str(terminal.get("result") or terminal.get("error") or "")
                except Exception as exc:
                    response = f"Live regression failed: {exc}"
                elapsed_ms = round((time.perf_counter() - started) * 1000)
                record = evaluate(
                    case,
                    response,
                    elapsed_ms,
                    [str(event.get("status") or "") for event in events.get(task_id, [])],
                )
                records.append(record)
                print(json.dumps(record, ensure_ascii=False), flush=True)
    finally:
        server.close()

    report = ROOT / "build" / "reports" / "codex-style-live-regression.json"
    report.parent.mkdir(parents=True, exist_ok=True)
    report.write_text(json.dumps({"records": records}, ensure_ascii=False, indent=2), encoding="utf-8")
    passed = sum(1 for record in records if record["passed"])
    print(json.dumps({"passed": passed, "total": len(records), "report": str(report)}, ensure_ascii=False))
    return 0 if passed == len(records) else 1


if __name__ == "__main__":
    raise SystemExit(main())
