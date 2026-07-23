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

    def test_imports_artifact_referenced_from_an_earlier_task(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary) / "SignalASIWorkspace"
            with patch.dict(os.environ, {"SIGNALASI_WORKSPACE_ROOT": str(root)}):
                earlier = task_workspace.task_workspace("earlier", "codex")
                source = earlier / "outputs" / "marked.jpg"
                source.write_bytes(b"image")
                response = f"![Marked image](<{source.as_posix()}>)"
                artifacts = task_workspace.import_referenced_task_artifacts("current", response)
            self.assertEqual(["outputs/marked.jpg"], [item["relative_path"] for item in artifacts])
            self.assertEqual(b"image", (root / "tasks" / "current" / "outputs" / "marked.jpg").read_bytes())

    def test_rejects_referenced_file_outside_task_workspace(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary) / "SignalASIWorkspace"
            outside = Path(temporary) / "private.jpg"
            outside.write_bytes(b"private")
            with patch.dict(os.environ, {"SIGNALASI_WORKSPACE_ROOT": str(root)}):
                artifacts = task_workspace.import_referenced_task_artifacts(
                    "current",
                    f"![Private](<{outside.as_posix()}>)",
                )
            self.assertEqual([], artifacts)


if __name__ == "__main__":
    unittest.main()
