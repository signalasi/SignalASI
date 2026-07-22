from __future__ import annotations

import tempfile
import unittest
from pathlib import Path

from desktop_memory import DesktopMemoryStore
from desktop_skills import DesktopSkillRegistry


class DesktopMemoryTest(unittest.TestCase):
    def test_retrieves_cross_conversation_memory_and_supersedes_state(self):
        with tempfile.TemporaryDirectory() as directory:
            store = DesktopMemoryStore(Path(directory) / "memory.db", now=lambda: 100.0)
            first = store.remember(
                "SignalASI response style is concise",
                kind="decision",
                key="project:response-style",
                conversation_id="old-conversation",
            )
            second = store.remember(
                "SignalASI response style is concise and action oriented",
                kind="decision",
                key="project:response-style",
                conversation_id="new-conversation",
            )

            self.assertEqual(store.get(first["id"])["status"], "superseded")
            self.assertEqual(second["supersedes_id"], first["id"])
            matches = store.search("What is the SignalASI response style?")
            self.assertEqual(matches[0]["id"], second["id"])

    def test_evolution_ignores_secrets_and_records_reusable_task_episode(self):
        with tempfile.TemporaryDirectory() as directory:
            store = DesktopMemoryStore(Path(directory) / "memory.db")

            self.assertIsNone(store.remember("api_key=secret-value", kind="fact"))
            learned = store.evolve(
                "Build the desktop release checklist and remember that verification is required",
                "Created and verified the release checklist with four checks.",
                conversation_id="conversation-1",
                task_id="task-1",
            )

            self.assertGreaterEqual(len(learned), 1)
            self.assertGreaterEqual(store.stats()["active"], 1)
            self.assertIn("verification", store.compile_context("desktop release verification"))

    def test_evolution_keeps_explicit_chinese_memory_but_skips_volatile_status(self):
        with tempfile.TemporaryDirectory() as directory:
            store = DesktopMemoryStore(Path(directory) / "memory.db")

            explicit = store.evolve(
                "\u8bf7\u8bb0\u4f4f\uff1a\u9ed8\u8ba4\u4f7f\u7528\u7b80\u4f53\u4e2d\u6587",
                "\u5df2\u8bb0\u4f4f\uff0c\u540e\u7eed\u9ed8\u8ba4\u4f7f\u7528\u7b80\u4f53\u4e2d\u6587\u56de\u590d\u3002",
                task_id="task-explicit",
            )
            volatile = store.evolve(
                "Show current computer status and memory usage",
                "Windows 11 with 20 GB memory currently available.",
                task_id="task-volatile",
            )

            self.assertTrue(any(item["kind"] == "explicit" for item in explicit))
            self.assertEqual(volatile, [])


class DesktopSkillRegistryTest(unittest.TestCase):
    def test_builtin_skills_match_without_polluting_main_prompt(self):
        with tempfile.TemporaryDirectory() as directory:
            registry = DesktopSkillRegistry(Path(directory) / "skills.json")

            compiled, matched = registry.compile("Fix the Python project build and run tests")

            self.assertEqual(matched[0].id, "signalasi.code-work")
            self.assertIn("run proportionate verification", compiled)

    def test_custom_skill_persists_and_can_be_disabled(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "skills.json"
            registry = DesktopSkillRegistry(path)
            registry.upsert({
                "id": "team.release-review",
                "name": "Release review",
                "description": "Review a release candidate",
                "triggers": ["release candidate"],
                "instructions": "Check versioning, artifacts, and release notes.",
            })

            reloaded = DesktopSkillRegistry(path)
            self.assertEqual(reloaded.match("Review this release candidate")[0].id, "team.release-review")
            reloaded.set_enabled("team.release-review", False)
            self.assertFalse(any(item.id == "team.release-review" for item in reloaded.match("release candidate")))


if __name__ == "__main__":
    unittest.main()
