"""Smoke-test the shared remote Agent task lifecycle."""
from __future__ import annotations

import os
import subprocess
import sys
import tempfile
import threading
import time
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
BACKEND = ROOT / "core" / "signalasi-link" / "backend"
sys.path.insert(0, str(BACKEND))

with tempfile.TemporaryDirectory(prefix="signalasi-task-smoke-") as temporary_home:
    os.environ["HOME"] = temporary_home
    os.environ["USERPROFILE"] = temporary_home

    import agent_task_manager as lifecycle

    lifecycle.TASKS_PATH = Path(temporary_home) / "agent_tasks.json"
    manager = lifecycle.AgentTaskManager()

    events: list[dict] = []
    done = threading.Event()

    def capture(event: dict) -> None:
        events.append(dict(event))
        if event["status"] in lifecycle.TERMINAL_STATES:
            done.set()

    completed = manager.create(
        agent_id="codex",
        contact_id="codex-workstation",
        source_message_id="101",
        prompt="smoke",
        runner=lambda _task: "Task complete",
        on_event=capture,
    )
    if not done.wait(5):
        raise SystemExit("completed task did not finish")
    statuses = [event["status"] for event in events]
    if statuses[:3] != ["accepted", "queued", "running"] or statuses[-1] != "completed":
        raise SystemExit(f"unexpected lifecycle: {statuses}")
    if manager.get(completed.task_id).result != "Task complete":
        raise SystemExit("completed result was not persisted")
    terminal_replay: list[dict] = []
    manager.cancel(completed.task_id, lambda event: terminal_replay.append(dict(event)))
    if not terminal_replay or terminal_replay[-1]["status"] != "completed":
        raise SystemExit("cancelling a settled task did not replay its authoritative terminal state")
    sequences = [event["status_seq"] for event in events]
    if sequences != sorted(sequences) or len(sequences) != len(set(sequences)):
        raise SystemExit(f"task status sequence is not monotonic: {sequences}")

    covered_agents = {"codex"}
    for agent_id in ("hermes", "claude", "local-llm", "custom-agent"):
        terminal = threading.Event()
        task = manager.create(
            agent_id=agent_id,
            contact_id=f"{agent_id}-workstation",
            source_message_id=f"agent-{agent_id}",
            prompt="shared lifecycle smoke",
            runner=lambda _task, value=agent_id: f"{value} complete",
            on_event=lambda event, signal=terminal: signal.set() if event["status"] in lifecycle.TERMINAL_STATES else None,
        )
        if not terminal.wait(5) or manager.get(task.task_id).status != "completed":
            raise SystemExit(f"shared lifecycle failed for {agent_id}")
        covered_agents.add(agent_id)
    if covered_agents != {"codex", "hermes", "claude", "local-llm", "custom-agent"}:
        raise SystemExit(f"Agent coverage is incomplete: {covered_agents}")

    cancel_events: list[dict] = []
    process_ready = threading.Event()
    process_holder: list[subprocess.Popen] = []

    def cancellable(task) -> str:
        process = subprocess.Popen([sys.executable, "-c", "import time; time.sleep(30)"])
        process_holder.append(process)
        manager.register_process(task.task_id, process)
        process_ready.set()
        process.wait()
        return "should not be delivered"

    cancelled = manager.create(
        agent_id="custom-agent",
        contact_id="custom-agent-workstation",
        source_message_id="102",
        prompt="cancel smoke",
        runner=cancellable,
        on_event=lambda event: cancel_events.append(dict(event)),
    )
    if not process_ready.wait(5):
        raise SystemExit("cancellable process did not start")
    manager.cancel(cancelled.task_id, lambda event: cancel_events.append(dict(event)))
    deadline = time.time() + 5
    while time.time() < deadline and manager.get(cancelled.task_id).status != "cancelled":
        time.sleep(0.05)
    if manager.get(cancelled.task_id).status != "cancelled":
        raise SystemExit("task cancellation did not become terminal")
    if cancel_events[-1]["status"] != "cancelled":
        raise SystemExit(f"unexpected cancellation lifecycle: {cancel_events}")
    if process_holder[0].poll() is None:
        raise SystemExit("cancelled CLI process is still running")

    recovery_gate = threading.Event()
    recovery_started = threading.Event()

    def interrupted(_task) -> str:
        recovery_started.set()
        recovery_gate.wait(5)
        return "original process eventually stopped"

    interrupted_task = manager.create(
        agent_id="hermes",
        contact_id="hermes-recovery",
        source_message_id="103",
        prompt="restart recovery smoke",
        runner=interrupted,
        on_event=lambda _event: None,
    )
    if not recovery_started.wait(5):
        raise SystemExit("recovery task did not start")
    recovered_manager = lifecycle.AgentTaskManager()
    recovered = recovered_manager.get(interrupted_task.task_id)
    if recovered is None or recovered.status != "recovering" or recovered.attempt != 2:
        raise SystemExit("Desktop restart did not queue the running task for bounded recovery")
    recovered_rows = recovered_manager.drain_recovered()
    if interrupted_task.task_id not in {row["task_id"] for row in recovered_rows}:
        raise SystemExit("recovered task was not queued for status replay")
    if next(row for row in recovered_rows if row["task_id"] == interrupted_task.task_id).get("prompt") != "restart recovery smoke":
        raise SystemExit("recovered task lost its original prompt")
    recovery_gate.set()
    recovery_deadline = time.time() + 5
    while time.time() < recovery_deadline and manager.get(interrupted_task.task_id).status not in lifecycle.TERMINAL_STATES:
        time.sleep(0.05)

    external_events: list[dict] = []
    external = manager.create_external(
        agent_id="codex", contact_id="codex-app-server", source_message_id="104",
        prompt="app server smoke", on_event=lambda event: external_events.append(dict(event)),
    )
    manager.update(
        external.task_id, "running", on_event=lambda event: external_events.append(dict(event)),
        thread_id="thread-smoke", turn_id="turn-smoke", current_step="Running command",
    )
    manager.update(
        external.task_id, "completed", on_event=lambda event: external_events.append(dict(event)),
        result="App Server complete", current_step="",
    )
    external_row = manager.get(external.task_id)
    if not external_row or external_row.thread_id != "thread-smoke" or external_row.result != "App Server complete":
        raise SystemExit("external App Server lifecycle was not persisted")

    rows = manager.list()
    task_ids = {row["task_id"] for row in rows}
    if completed.task_id not in task_ids or cancelled.task_id not in task_ids:
        raise SystemExit("task registry list is incomplete")
    if not lifecycle.TASKS_PATH.exists():
        raise SystemExit("task registry was not persisted")

print("Remote Agent lifecycle smoke passed")
