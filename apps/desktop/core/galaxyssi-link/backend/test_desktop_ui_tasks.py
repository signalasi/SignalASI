from __future__ import annotations

import threading
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
    monkeypatch.setenv("GALAXYSSI_WORKSPACE_ROOT", str(tmp_path / "workspace"))
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
    deliveries: list[dict] = []

    def fake_delivery(agent_id, prompt, **kwargs):
        assert agent_id == "codex"
        prompts.append(prompt)
        deliveries.append(kwargs)
        return {"reply": f"reply-{len(prompts)}", "agent_id": agent_id}

    monkeypatch.setattr(main, "deliver_agent_sync", fake_delivery)
    source = tmp_path / "brief.txt"
    source.write_text("release brief", encoding="utf-8")

    first = main.api_start_desktop_task(
        main.DesktopTaskStartReq(
            prompt="Inspect the attached release brief",
            agent_id="codex",
            conversation_id="conversation-1",
            attachments=[str(source)],
        ),
        LoopbackRequest(),
    )
    first_task = wait_for_terminal(manager, first["task_id"])
    assert first_task.result == "reply-1"
    assert first_task.attachments == ["downloads/input/brief.txt"]
    assert "downloads/input/brief.txt" in prompts[0]
    assert deliveries[0]["execution_prompt"] == "Inspect the attached release brief"
    assert deliveries[0]["execution_policy"]["task_kind"] == "artifact"
    assert deliveries[0]["execution_policy"]["requires_artifact"] is False

    second = main.api_start_desktop_task(
        main.DesktopTaskStartReq(
            prompt="Continue with the release notes",
            agent_id="codex",
            conversation_id="conversation-1",
        ),
        LoopbackRequest(),
    )
    second_task = wait_for_terminal(manager, second["task_id"])
    assert second_task.result == "reply-2"
    assert "Inspect the attached release brief" in prompts[1]
    assert "reply-1" in prompts[1]
    assert deliveries[1]["execution_prompt"] == "Continue with the release notes"
    assert deliveries[1]["execution_policy"]["task_kind"] == "chat"

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


def test_desktop_task_forwards_plan_only_policy_without_requesting_artifacts(
    tmp_path,
    monkeypatch,
):
    monkeypatch.setattr(task_module, "TASKS_DB_PATH", tmp_path / "tasks.sqlite3")
    manager = task_module.AgentTaskManager()
    monkeypatch.setattr(main, "agent_task_manager", manager)
    monkeypatch.setenv("GALAXYSSI_WORKSPACE_ROOT", str(tmp_path / "workspace"))
    monkeypatch.setattr(
        main,
        "connector_diagnostics",
        lambda quick=False: {"agents": [{"id": "codex", "status": "ready"}]},
    )
    deliveries: list[dict] = []

    def fake_delivery(agent_id, provider_prompt, **kwargs):
        deliveries.append({
            "agent_id": agent_id,
            "provider_prompt": provider_prompt,
            **kwargs,
        })
        return {"reply": "Plan ready", "agent_id": agent_id}

    monkeypatch.setattr(main, "deliver_agent_sync", fake_delivery)
    started = main.api_start_desktop_task(
        main.DesktopTaskStartReq(
            prompt="Build an Android app and return the APK",
            agent_id="codex",
            execution_mode="plan_only",
            conversation_id="plan-conversation",
        ),
        LoopbackRequest(),
    )
    completed = wait_for_terminal(manager, started["task_id"])

    assert completed.result == "Plan ready"
    assert deliveries[0]["execution_policy"]["execution_mode"] == "plan_only"
    assert deliveries[0]["execution_policy"]["requires_artifact"] is False
    assert "read-only plan" in deliveries[0]["provider_prompt"].lower()


def test_prompt_can_override_desktop_plan_default_with_auto_complete(
    tmp_path,
    monkeypatch,
):
    monkeypatch.setattr(task_module, "TASKS_DB_PATH", tmp_path / "tasks.sqlite3")
    manager = task_module.AgentTaskManager()
    monkeypatch.setattr(main, "agent_task_manager", manager)
    monkeypatch.setenv("GALAXYSSI_WORKSPACE_ROOT", str(tmp_path / "workspace"))
    monkeypatch.setattr(
        main,
        "connector_diagnostics",
        lambda quick=False: {"agents": [{"id": "codex", "status": "ready"}]},
    )
    policies: list[dict] = []

    def fake_delivery(_agent_id, _prompt, **kwargs):
        policies.append(kwargs["execution_policy"])
        return {"reply": "Completed"}

    monkeypatch.setattr(main, "deliver_agent_sync", fake_delivery)
    started = main.api_start_desktop_task(
        main.DesktopTaskStartReq(
            prompt="Build the app and execute until complete",
            agent_id="codex",
            execution_mode="plan_only",
        ),
        LoopbackRequest(),
    )
    wait_for_terminal(manager, started["task_id"])

    assert policies[0]["execution_mode"] == "auto_complete"
    assert policies[0]["requires_artifact"] is True


def test_desktop_asks_once_then_uses_the_same_conversation_context(tmp_path, monkeypatch):
    monkeypatch.setattr(task_module, "TASKS_DB_PATH", tmp_path / "tasks.sqlite3")
    manager = task_module.AgentTaskManager()
    monkeypatch.setattr(main, "agent_task_manager", manager)
    monkeypatch.setenv("GALAXYSSI_WORKSPACE_ROOT", str(tmp_path / "workspace"))
    monkeypatch.setattr(
        main,
        "connector_diagnostics",
        lambda quick=False: {"agents": [{"id": "codex", "status": "ready"}]},
    )
    deliveries: list[str] = []

    def fake_delivery(_agent_id, prompt, **_kwargs):
        deliveries.append(prompt)
        return {"reply": "continued", "agent_id": "codex"}

    monkeypatch.setattr(main, "deliver_agent_sync", fake_delivery)
    first = main.api_start_desktop_task(
        main.DesktopTaskStartReq(
            prompt="Control my computer",
            agent_id="codex",
            conversation_id="clarification-conversation",
            response_language="en-US",
        ),
        LoopbackRequest(),
    )
    clarified = wait_for_terminal(manager, first["task_id"])

    assert clarified.status == "completed"
    assert clarified.result == "What should I do on the device?"
    assert deliveries == []
    assert clarified.events[-1]["kind"] == "clarification"

    second = main.api_start_desktop_task(
        main.DesktopTaskStartReq(
            prompt="Continue",
            agent_id="codex",
            conversation_id="clarification-conversation",
        ),
        LoopbackRequest(),
    )
    continued = wait_for_terminal(manager, second["task_id"])

    assert continued.result == "continued"
    assert len(deliveries) == 1


def test_failed_attachment_task_retries_in_the_same_conversation(tmp_path, monkeypatch):
    monkeypatch.setattr(task_module, "TASKS_DB_PATH", tmp_path / "tasks.sqlite3")
    manager = task_module.AgentTaskManager()
    monkeypatch.setattr(main, "agent_task_manager", manager)
    monkeypatch.setenv("GALAXYSSI_WORKSPACE_ROOT", str(tmp_path / "workspace"))
    monkeypatch.setattr(
        main,
        "connector_diagnostics",
        lambda quick=False: {"agents": [{"id": "codex", "status": "ready"}]},
    )
    prompts: list[str] = []
    policies: list[dict] = []

    def flaky_delivery(_agent_id, prompt, **kwargs):
        prompts.append(prompt)
        policies.append(kwargs["execution_policy"])
        if len(prompts) == 1:
            raise RuntimeError("temporary failure")
        return {"reply": "retry completed"}

    monkeypatch.setattr(main, "deliver_agent_sync", flaky_delivery)
    source = tmp_path / "report.csv"
    source.write_text("name,value\nGalaxySSI,1\n", encoding="utf-8")

    first = main.api_start_desktop_task(
        main.DesktopTaskStartReq(
            prompt="Summarize the attached report",
            agent_id="codex",
            conversation_id="conversation-retry",
            attachments=[str(source)],
            task_budget={
                "profile": "custom",
                "max_elapsed_seconds": 900,
                "max_input_tokens": 123_000,
                "allow_paid_providers": False,
            },
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
    assert policies[0]["task_budget"] == policies[1]["task_budget"]
    assert policies[0]["task_budget"]["profile"] == "custom"
    assert policies[0]["task_budget"]["max_elapsed_seconds"] == 900.0
    assert policies[0]["task_budget"]["max_input_tokens"] == 123_000
    assert policies[0]["task_budget"]["allow_paid_providers"] is False


def test_failed_task_can_switch_agent_or_return_diagnostics(tmp_path, monkeypatch):
    monkeypatch.setattr(task_module, "TASKS_DB_PATH", tmp_path / "tasks.sqlite3")
    manager = task_module.AgentTaskManager()
    monkeypatch.setattr(main, "agent_task_manager", manager)
    monkeypatch.setenv("GALAXYSSI_WORKSPACE_ROOT", str(tmp_path / "workspace"))
    monkeypatch.setattr(
        main,
        "connector_diagnostics",
        lambda quick=False: {
            "agents": [
                {"id": "codex", "status": "unavailable"},
                {"id": "hermes", "status": "ready"},
            ]
        },
    )
    calls: list[tuple[str, dict]] = []

    def delivery(agent_id, _prompt, **kwargs):
        calls.append((agent_id, kwargs["execution_policy"]))
        if len(calls) == 1:
            raise RuntimeError("Codex is unavailable")
        return {"reply": "Recovered with Hermes"}

    monkeypatch.setattr(main, "deliver_agent_sync", delivery)
    first = main.api_start_desktop_task(
        main.DesktopTaskStartReq(
            prompt="Inspect the repository",
            agent_id="codex",
            conversation_id="recovery-conversation",
        ),
        LoopbackRequest(),
    )
    failed = wait_for_terminal(manager, first["task_id"])
    assert failed.status == "failed"
    assert len(failed.public()["recovery_actions"]) == 4

    diagnostic = main.api_recover_desktop_task(
        failed.task_id,
        main.DesktopTaskRecoveryReq(action="diagnostics"),
        LoopbackRequest(),
    )
    assert diagnostic["diagnostic"]["recommended_action"] == "switch_agent"
    assert len(calls) == 1

    recovery = main.api_recover_desktop_task(
        failed.task_id,
        main.DesktopTaskRecoveryReq(action="switch_agent"),
        LoopbackRequest(),
    )
    completed = wait_for_terminal(manager, recovery["task"]["task_id"])
    assert completed.status == "completed"
    assert completed.agent_id == "hermes"
    assert completed.result == "Recovered with Hermes"
    assert completed.retry_of == failed.task_id


def test_safe_fallback_restarts_in_plan_only_mode(tmp_path, monkeypatch):
    monkeypatch.setattr(task_module, "TASKS_DB_PATH", tmp_path / "tasks.sqlite3")
    manager = task_module.AgentTaskManager()
    monkeypatch.setattr(main, "agent_task_manager", manager)
    monkeypatch.setenv("GALAXYSSI_WORKSPACE_ROOT", str(tmp_path / "workspace"))
    monkeypatch.setattr(
        main,
        "connector_diagnostics",
        lambda quick=False: {"agents": [{"id": "codex", "status": "ready"}]},
    )
    policies: list[dict] = []

    def delivery(_agent_id, _prompt, **kwargs):
        policies.append(kwargs["execution_policy"])
        if len(policies) == 1:
            raise RuntimeError("Verification failed")
        return {"reply": "Read-only fallback plan"}

    monkeypatch.setattr(main, "deliver_agent_sync", delivery)
    first = main.api_start_desktop_task(
        main.DesktopTaskStartReq(
            prompt="Change the project",
            agent_id="codex",
            conversation_id="degrade-conversation",
        ),
        LoopbackRequest(),
    )
    failed = wait_for_terminal(manager, first["task_id"])

    recovery = main.api_recover_desktop_task(
        failed.task_id,
        main.DesktopTaskRecoveryReq(action="degrade"),
        LoopbackRequest(),
    )
    completed = wait_for_terminal(manager, recovery["task"]["task_id"])
    assert completed.result == "Read-only fallback plan"
    assert policies[-1]["execution_mode"] == "plan_only"
    assert policies[-1]["requires_artifact"] is False


def test_desktop_task_can_pause_take_over_and_continue_in_place(tmp_path, monkeypatch):
    from desktop_run_control import desktop_run_control

    monkeypatch.setattr(task_module, "TASKS_DB_PATH", tmp_path / "tasks.sqlite3")
    manager = task_module.AgentTaskManager()
    monkeypatch.setattr(main, "agent_task_manager", manager)
    first_runner_started = threading.Event()
    release_first_runner = threading.Event()

    def first_runner(_task):
        first_runner_started.set()
        assert release_first_runner.wait(3.0)
        return "stale result"

    task = manager.create(
        agent_id="codex",
        contact_id="codex",
        source_message_id="desktop:test",
        prompt="Continue this task after takeover",
        conversation_id="conversation-control",
        runner=first_runner,
        on_event=lambda _event: None,
    )
    assert first_runner_started.wait(1.0)

    desktop_run_control().configure(
        runner_factory=lambda _task, bundle: (
            lambda _current: f"resumed from {bundle['persisted']['capture_id']}"
        ),
        interrupt_handler=lambda _task: {"cancelled_runtime_runs": ["run-1"]},
        checkpoint_provider=lambda _task: {
            "persisted": {"capture_id": "capture-after-takeover"},
            "grounding": "Fresh Desktop state",
        },
        task_manager_provider=lambda: manager,
    )
    try:
        paused = main.api_pause_desktop_task(
            task.task_id,
            main.DesktopTaskControlReq(reason="Pause for manual work"),
            LoopbackRequest(),
        )
        assert paused["task"]["status"] == "paused"
        assert paused["control"]["interruption"]["cancelled_runtime_runs"] == ["run-1"]

        takeover = main.api_takeover_desktop_task(
            task.task_id,
            main.DesktopTaskControlReq(lease_seconds=60),
            LoopbackRequest(),
        )
        assert takeover["task"]["status"] == "takeover"
        assert takeover["task"]["execution_view"]["takeover_active"] is True

        continued = main.api_continue_desktop_task(
            task.task_id,
            main.DesktopTaskControlReq(),
            LoopbackRequest(),
        )
        assert continued["control"]["task_id"] == task.task_id
        completed = wait_for_terminal(manager, task.task_id)
        assert completed.result == "resumed from capture-after-takeover"
        assert completed.resume_count == 1
        assert completed.execution_generation == 2

        release_first_runner.set()
        time.sleep(0.05)
        assert manager.get(task.task_id).result == "resumed from capture-after-takeover"
    finally:
        release_first_runner.set()
        main._configure_desktop_run_control()
