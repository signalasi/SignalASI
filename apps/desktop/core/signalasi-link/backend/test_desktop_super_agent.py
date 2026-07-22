from __future__ import annotations

import unittest

from desktop_super_agent import DesktopSuperAgent


class FakeTaskManager:
    def __init__(self) -> None:
        self.events: list[dict] = []
        self.updates: list[dict] = []

    def add_event(self, task_id, kind, title, **kwargs):
        self.events.append({"task_id": task_id, "kind": kind, "title": title, **kwargs})

    def update(self, task_id, status, **kwargs):
        self.updates.append({"task_id": task_id, "status": status, **kwargs})


class FakeRegistry:
    def __init__(self, results: dict[str, dict]) -> None:
        self.results = results
        self.calls: list[tuple[str, dict, dict]] = []

    def invoke(self, tool_id, arguments, context):
        self.calls.append((tool_id, arguments, context))
        return self.results[tool_id]


class FakeMemory:
    def __init__(self, context: str = "") -> None:
        self.context = context
        self.learned: list[tuple[str, str, str, str]] = []

    def compile_context(self, prompt):
        return self.context

    def evolve(self, prompt, reply, *, conversation_id="", task_id=""):
        self.learned.append((prompt, reply, conversation_id, task_id))
        return []


class FakeSkills:
    def compile(self, prompt):
        return "", []


class FakeMcp:
    def match(self, prompt):
        return None


class MatchingMcp:
    class Connection:
        id = "relay"
        name = "Relay MCP"

    def match(self, prompt):
        return self.Connection()

    def invoke_prompt(self, connection_id, prompt, process_callback=None):
        return {"result": "Relay is on.", "duration_ms": 17}


def succeeded(output: dict, message: str = "ok") -> dict:
    return {
        "status": "succeeded",
        "output": output,
        "message": message,
        "error": None,
        "verification": {"status": "passed"},
        "receipt": {"duration_ms": 2},
    }


class DesktopSuperAgentTest(unittest.TestCase):
    def test_launches_named_application_as_a_direct_desktop_action(self):
        manager = FakeTaskManager()
        registry = FakeRegistry({
            "signalasi.desktop.windows.app.launch": succeeded({"id": "notepad", "name": "Notepad", "launched": True}),
        })
        coordinator = DesktopSuperAgent(
            task_manager=manager,
            diagnostics=lambda quick=True: {"agents": []},
            deliver=lambda *args, **kwargs: self.fail("Application launch should not call an external Agent"),
            registry=registry,
            memory=FakeMemory(),
            skills=FakeSkills(),
            mcp=FakeMcp(),
        )

        outcome = coordinator.run(
            task_id="task-app",
            conversation_id="conversation-app",
            prompt="Open Notepad",
            compiled_prompt="compiled",
            attachments=[],
        )

        self.assertEqual(outcome.reply, "Launched Notepad.")
        self.assertEqual(registry.calls[0][1], {"name": "Notepad"})

    def test_starting_a_project_is_delegated_instead_of_treated_as_an_app(self):
        manager = FakeTaskManager()
        delivered: list[tuple[str, str]] = []

        def deliver(agent_id, prompt, **_kwargs):
            delivered.append((agent_id, prompt))
            return {"reply": "Project started."}

        coordinator = DesktopSuperAgent(
            task_manager=manager,
            diagnostics=lambda quick=True: {"agents": [{"id": "codex", "status": "ready"}]},
            deliver=deliver,
            registry=FakeRegistry({}),
            memory=FakeMemory(),
            skills=FakeSkills(),
            mcp=FakeMcp(),
        )

        outcome = coordinator.run(
            task_id="task-project",
            conversation_id="conversation-project",
            prompt="Start a project for release automation",
            compiled_prompt="compiled project request",
            attachments=[],
        )

        self.assertEqual(outcome.delegate_agent_id, "codex")
        self.assertEqual(delivered[0][0], "codex")

    def test_explicit_mcp_capability_executes_without_external_agent(self):
        manager = FakeTaskManager()
        deliveries = []
        coordinator = DesktopSuperAgent(
            task_manager=manager,
            diagnostics=lambda quick=True: {"agents": []},
            deliver=lambda *args, **kwargs: deliveries.append((args, kwargs)),
            registry=FakeRegistry({}),
            memory=FakeMemory(),
            skills=FakeSkills(),
            mcp=MatchingMcp(),
        )

        outcome = coordinator.run(
            task_id="task-mcp",
            conversation_id="conversation-mcp",
            prompt="Use Relay MCP to turn it on",
            compiled_prompt="compiled",
            attachments=[],
        )

        self.assertEqual(outcome.reply, "Relay is on.")
        self.assertEqual(outcome.delegate_agent_id, "mcp:relay")
        self.assertEqual(deliveries, [])
        self.assertTrue(any(event["kind"] == "mcp" for event in manager.events))

    def test_reads_system_status_without_external_agent(self):
        manager = FakeTaskManager()
        registry = FakeRegistry({
            "signalasi.desktop.windows.system.status": succeeded({
                "platform": "Windows",
                "release": "11",
                "architecture": "AMD64",
                "logical_cpu_count": 16,
                "memory_total_bytes": 32 * 1024 ** 3,
                "memory_available_bytes": 20 * 1024 ** 3,
            }),
        })
        deliveries: list[dict] = []
        coordinator = DesktopSuperAgent(
            task_manager=manager,
            diagnostics=lambda quick=True: {"agents": []},
            deliver=lambda *args, **kwargs: deliveries.append(kwargs),
            registry=registry,
            memory=FakeMemory(),
            skills=FakeSkills(),
            mcp=FakeMcp(),
        )

        outcome = coordinator.run(
            task_id="task-1",
            conversation_id="conversation-1",
            prompt="Show computer system status and memory usage",
            compiled_prompt="compiled",
            attachments=[],
        )

        self.assertIn("20.0 GB available", outcome.reply)
        self.assertEqual(deliveries, [])
        self.assertEqual(registry.calls[0][0], "signalasi.desktop.windows.system.status")
        self.assertTrue(any(event["kind"] == "tool" for event in manager.events))

    def test_inspects_attachment_then_delegates_with_observation(self):
        manager = FakeTaskManager()
        registry = FakeRegistry({
            "signalasi.desktop.workspace.file.read.text": succeeded({
                "path": "downloads/input/brief.md",
                "text": "Release notes",
                "size_bytes": 13,
                "sha256": "0" * 64,
            }),
        })
        delivered: list[tuple[str, str, dict]] = []

        def deliver(agent_id, prompt, **kwargs):
            delivered.append((agent_id, prompt, kwargs))
            return {"reply": "Summary complete."}

        coordinator = DesktopSuperAgent(
            task_manager=manager,
            diagnostics=lambda quick=True: {"agents": [{"id": "codex", "status": "ready"}]},
            deliver=deliver,
            registry=registry,
            memory=FakeMemory("- [decision] Use concise release summaries"),
            skills=FakeSkills(),
            mcp=FakeMcp(),
        )

        outcome = coordinator.run(
            task_id="task-2",
            conversation_id="conversation-2",
            prompt="Summarize the attached project brief",
            compiled_prompt="compiled request",
            attachments=["downloads/input/brief.md"],
        )

        self.assertEqual(outcome.reply, "Summary complete.")
        self.assertEqual(outcome.delegate_agent_id, "codex")
        self.assertEqual(delivered[0][0], "codex")
        self.assertIn("Release notes", delivered[0][1])
        self.assertIn("Use concise release summaries", delivered[0][1])
        self.assertTrue(any(update.get("delegate_agent_id") == "codex" for update in manager.updates))

    def test_healthy_agent_wins_over_preferred_but_degraded_agent(self):
        coordinator = DesktopSuperAgent(
            task_manager=FakeTaskManager(),
            diagnostics=lambda quick=True: {
                "agents": [
                    {"id": "hermes", "status": "degraded"},
                    {"id": "codex", "status": "ready"},
                ]
            },
            deliver=lambda *args, **kwargs: {"reply": "Current research result."},
            registry=FakeRegistry({}),
            memory=FakeMemory(),
            skills=FakeSkills(),
            mcp=FakeMcp(),
        )

        outcome = coordinator.run(
            task_id="task-research",
            conversation_id="conversation-research",
            prompt="Research the latest release news",
            compiled_prompt="compiled research request",
            attachments=[],
        )

        self.assertEqual(outcome.delegate_agent_id, "codex")


if __name__ == "__main__":
    unittest.main()
