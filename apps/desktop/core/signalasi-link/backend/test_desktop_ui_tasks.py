from __future__ import annotations

import time
from types import SimpleNamespace

import agent_task_manager as task_module
import main


class LoopbackRequest:
    client = SimpleNamespace(host="127.0.0.1")


def wait_for_terminal(manager, task_id: str, timeout: float = 3.0):
    deadline = time.time() + timeout
    while time.time() < deadline:
        task = manager.get(task_id)
        if task and task.status in task_module.TERMINAL_STATES:
            return task
        time.sleep(0.02)
    raise AssertionError("Desktop task did not reach a terminal state")


def test_desktop_task_runs_async_and_reuses_conversation_context(tmp_path, monkeypatch):
    monkeypatch.setattr(task_module, "TASKS_DB_PATH", tmp_path / "tasks.sqlite3")
    manager = task_module.AgentTaskManager()
    monkeypatch.setattr(main, "agent_task_manager", manager)
    monkeypatch.setenv("SIGNALASI_WORKSPACE_ROOT", str(tmp_path / "workspace"))
    monkeypatch.setattr(
        main,
        "connector_diagnostics",
        lambda quick=False: {
            "agents": [
                {"id": "codex", "status": "ready"},
                {"id": "hermes", "status": "needs_setup"},
            ]
        },
    )
    prompts: list[str] = []

    def fake_delivery(agent_id, prompt, **kwargs):
        prompts.append(prompt)
        return {"reply": f"reply-{len(prompts)}", "agent_id": agent_id}

    monkeypatch.setattr(main, "deliver_agent_sync", fake_delivery)
    source = tmp_path / "brief.txt"
    source.write_text("release brief", encoding="utf-8")

    first = main.api_start_desktop_task(
        main.DesktopTaskStartReq(
            prompt="Inspect the attached release brief",
            conversation_id="conversation-1",
            attachments=[str(source)],
        ),
        LoopbackRequest(),
    )
    first_task = wait_for_terminal(manager, first["task_id"])
    assert first_task.result == "reply-1"
    assert first_task.attachments == ["downloads/input/brief.txt"]
    assert "downloads/input/brief.txt" in prompts[0]

    second = main.api_start_desktop_task(
        main.DesktopTaskStartReq(
            prompt="Continue with the release notes",
            conversation_id="conversation-1",
        ),
        LoopbackRequest(),
    )
    second_task = wait_for_terminal(manager, second["task_id"])
    assert second_task.result == "reply-2"
    assert "Inspect the attached release brief" in prompts[1]
    assert "reply-1" in prompts[1]

    listed = main.api_list_desktop_tasks(LoopbackRequest(), limit=10)["tasks"]
    assert [item["task_id"] for item in listed[:2]] == [second["task_id"], first["task_id"]]
    assert all(item["source_message_id"].startswith("desktop:") for item in listed)


def test_desktop_auto_uses_super_agent_and_explicit_agents_remain_direct(monkeypatch):
    monkeypatch.setattr(
        main,
        "connector_diagnostics",
        lambda quick=False: {
            "agents": [
                {"id": "codex", "status": "ready"},
                {"id": "hermes", "status": "ready"},
            ]
        },
    )
    assert main._desktop_agent_for("Research today's latest news") == "desktop"
    assert main._desktop_agent_for("Fix the project build") == "desktop"
    assert main._desktop_agent_for("Research today's latest news", "hermes") == "hermes"
    assert main._desktop_agent_for("Fix the project build", "codex") == "codex"


def test_failed_attachment_task_retries_in_the_same_conversation(tmp_path, monkeypatch):
    monkeypatch.setattr(task_module, "TASKS_DB_PATH", tmp_path / "tasks.sqlite3")
    manager = task_module.AgentTaskManager()
    monkeypatch.setattr(main, "agent_task_manager", manager)
    monkeypatch.setenv("SIGNALASI_WORKSPACE_ROOT", str(tmp_path / "workspace"))
    monkeypatch.setattr(
        main,
        "connector_diagnostics",
        lambda quick=False: {"agents": [{"id": "codex", "status": "ready"}]},
    )
    prompts: list[str] = []

    def flaky_delivery(_agent_id, prompt, **_kwargs):
        prompts.append(prompt)
        if len(prompts) == 1:
            raise RuntimeError("temporary failure")
        return {"reply": "retry completed"}

    monkeypatch.setattr(main, "deliver_agent_sync", flaky_delivery)
    source = tmp_path / "report.csv"
    source.write_text("name,value\nSignalASI,1\n", encoding="utf-8")

    first = main.api_start_desktop_task(
        main.DesktopTaskStartReq(
            prompt="Summarize the attached report",
            agent_id="codex",
            conversation_id="conversation-retry",
            attachments=[str(source)],
        ),
        LoopbackRequest(),
    )
    failed = wait_for_terminal(manager, first["task_id"])
    assert failed.status == "failed"

    retried = main.api_retry_desktop_task(first["task_id"], LoopbackRequest())
    completed = wait_for_terminal(manager, retried["task_id"])
    assert completed.status == "completed"
    assert completed.result == "retry completed"
    assert completed.conversation_id == "conversation-retry"
    assert completed.retry_of == first["task_id"]
    assert completed.attempt == 2
    assert completed.attachments == ["downloads/input/report.csv"]
    assert prompts[1].count("Current user request:\nSummarize the attached report") == 1
