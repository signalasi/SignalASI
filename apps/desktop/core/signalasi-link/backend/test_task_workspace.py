import os
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

import task_workspace


class TaskWorkspaceTests(unittest.TestCase):
    def test_creates_isolated_task_layout(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary) / "SignalASIWorkspace"
            with patch.dict(os.environ, {"SIGNALASI_WORKSPACE_ROOT": str(root)}):
                directory = task_workspace.task_workspace("task-123", "codex")
            self.assertEqual(directory, (root / "tasks" / "task-123").resolve())
            for name in task_workspace.TASK_SUBDIRECTORIES:
                self.assertTrue((directory / name).is_dir())
            self.assertTrue((directory / ".signalasi-task.json").is_file())

    def test_task_id_cannot_escape_workspace(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary) / "SignalASIWorkspace"
            with patch.dict(os.environ, {"SIGNALASI_WORKSPACE_ROOT": str(root)}):
                directory = task_workspace.task_workspace("../../source/project", "codex")
            self.assertTrue(directory.is_relative_to((root / "tasks").resolve()))
            self.assertNotIn("..", directory.name)

    def test_source_tree_configuration_falls_back_to_user_workspace(self):
        unsafe = task_workspace.BACKEND_DIR / "generated-tasks"
        with patch.dict(os.environ, {"SIGNALASI_WORKSPACE_ROOT": str(unsafe)}):
            root = task_workspace.workspace_root()
        self.assertEqual(root, task_workspace.DEFAULT_WORKSPACE_ROOT.resolve())
        self.assertFalse(root.is_relative_to(task_workspace.BACKEND_DIR))

    def test_cleanup_removes_only_temporary_directories(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary) / "SignalASIWorkspace"
            with patch.dict(os.environ, {"SIGNALASI_WORKSPACE_ROOT": str(root)}):
                directory = task_workspace.task_workspace("task-clean", "codex")
                (directory / "temp" / "scratch.txt").write_text("temp", encoding="utf-8")
                (directory / "logs" / "run.txt").write_text("log", encoding="utf-8")
                (directory / "outputs" / "result.txt").write_text("keep", encoding="utf-8")
                artifacts = task_workspace.task_artifacts("task-clean")
                cleaned = task_workspace.cleanup_task_temporary_files({"task-clean"})
            self.assertEqual(artifacts[0]["relative_path"], "outputs/result.txt")
            self.assertEqual(artifacts[0]["size"], 4)
            self.assertEqual(cleaned, ["task-clean"])
            self.assertFalse((directory / "temp").exists())
            self.assertFalse((directory / "logs").exists())
            self.assertEqual((directory / "outputs" / "result.txt").read_text(encoding="utf-8"), "keep")


if __name__ == "__main__":
    unittest.main()
