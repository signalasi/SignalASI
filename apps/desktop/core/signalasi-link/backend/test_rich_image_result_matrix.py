import base64
import json
import os
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

import task_workspace
from rich_output import build_rich_output


class RichImageResultMatrixTests(unittest.TestCase):
    def test_one_hundred_result_delivery_scenarios(self):
        executed: list[str] = []
        with tempfile.TemporaryDirectory() as temporary, patch.dict(
            os.environ, {"SIGNALASI_WORKSPACE_ROOT": temporary}
        ):
            root = Path(temporary)
            self._run_hydration_cases(root, executed)
            self._run_explicit_dedupe_cases(root, executed)
            self._run_invalid_reference_cases(executed)
            self._run_current_conversation_cases(root, executed)
            self._run_remote_and_inline_cases(executed)

        self.assertEqual(100, len(executed))
        self.assertEqual(100, len(set(executed)))

    def _run_hydration_cases(self, root: Path, executed: list[str]) -> None:
        categories = ("outputs", "downloads", "screenshots")
        names = (
            "result.png",
            "marked homework.jpg",
            "批改结果.jpeg",
            "preview.webp",
            "animation.gif",
            "answer (final).png",
            "作业 批改.jpg",
            "result-v2.jpeg",
            "scan_001.webp",
            "图像结果.gif",
        )
        for index, (category, name) in enumerate(
            (category, name) for category in categories for name in names
        ):
            label = f"hydrate-{index + 1:02d}-{category}-{name}"
            with self.subTest(label):
                task_id = f"hydrate-{index + 1:02d}"
                relative = f"{category}/{name}"
                source = root / "tasks" / task_id / category / name
                source.parent.mkdir(parents=True, exist_ok=True)
                payload = f"image-{label}".encode()
                source.write_bytes(payload)
                content = self._rich_file(name, relative)
                _, document = build_rich_output(
                    content,
                    [{"name": name, "relative_path": relative, "size": len(payload)}],
                    task_id,
                )
                self.assertIsNotNone(document)
                block = document["blocks"][0]
                self.assertEqual(1, len(document["blocks"]))
                self.assertEqual("image", block["type"])
                self.assertEqual(base64.b64encode(payload).decode("ascii"), block["data_b64"])
                self.assertRegex(block["metadata"]["sha256"], r"^[0-9a-f]{64}$")
            executed.append(label)

    def _run_explicit_dedupe_cases(self, root: Path, executed: list[str]) -> None:
        extensions = ("png", "jpg", "jpeg", "webp", "gif")
        uri_styles = ("forward", "backslash", "artifact", "percent-encoded")
        for index, (extension, uri_style) in enumerate(
            (extension, uri_style) for extension in extensions for uri_style in uri_styles
        ):
            label = f"dedupe-{index + 1:02d}-{extension}-{uri_style}"
            with self.subTest(label):
                task_id = f"dedupe-{index + 1:02d}"
                name = f"marked result {index + 1}.{extension}"
                relative = f"outputs/{name}"
                source = root / "tasks" / task_id / "outputs" / name
                source.parent.mkdir(parents=True, exist_ok=True)
                payload = f"duplicate-{label}".encode()
                source.write_bytes(payload)
                if uri_style == "backslash":
                    uri = relative.replace("/", "\\")
                elif uri_style == "artifact":
                    uri = f"signalasi-artifact://previous-task/{relative}"
                elif uri_style == "percent-encoded":
                    uri = relative.replace(" ", "%20")
                else:
                    uri = relative
                content = self._rich_file(name, uri)
                _, document = build_rich_output(
                    content,
                    [{"name": name, "relative_path": relative, "size": len(payload)}],
                    task_id,
                )
                self.assertEqual(1, len(document["blocks"]))
                self.assertTrue(document["blocks"][0]["data_b64"])
            executed.append(label)

    def _run_invalid_reference_cases(self, executed: list[str]) -> None:
        references = (
            "outputs/missing.jpg",
            "outputs/../secret.jpg",
            "outputs/%2e%2e/secret.jpg",
            "../outputs/secret.jpg",
            "downloads/input/private.jpg",
            "scripts/generated.jpg",
            "temp/generated.jpg",
            "logs/generated.jpg",
            "C:/private/result.jpg",
            "file:///C:/private/result.jpg",
            "\\\\server\\share\\result.jpg",
            "/etc/private.jpg",
            "outputs/folder/../../secret.jpg",
            "downloads/./result.jpg",
            "screenshots/../result.jpg",
            "signalasi-artifact://task/scripts/result.jpg",
            "signalasi-artifact://task/downloads/input/private.jpg",
            "ftp://example.com/result.jpg",
            "javascript:alert(1)",
            "",
        )
        for index, reference in enumerate(references):
            label = f"invalid-{index + 1:02d}-{reference or 'blank'}"
            with self.subTest(label):
                fallback, document = build_rich_output(
                    self._rich_file("unavailable.jpg", reference),
                    task_id=f"invalid-{index + 1:02d}",
                )
                self.assertIsNone(document)
                self.assertNotIn("signalasi-rich", fallback)
                self.assertIn("unavailable", fallback.lower())
            executed.append(label)

    def _run_current_conversation_cases(self, root: Path, executed: list[str]) -> None:
        names = (
            "annotated.png",
            "批改图.jpg",
            "result 01.jpeg",
            "scan-final.webp",
            "动画.gif",
            "answer.png",
            "老师批注.jpg",
            "worksheet.jpeg",
            "数学作业.webp",
            "english-homework.gif",
            "photo result.png",
            "红笔批改.jpg",
            "reviewed.jpeg",
            "page-2.webp",
            "revision.gif",
            "final answer.png",
            "检查结果.jpg",
            "verified.jpeg",
            "output-copy.webp",
            "完成.gif",
        )
        for index, name in enumerate(names):
            label = f"conversation-{index + 1:02d}-{name}"
            with self.subTest(label):
                source_task_id = f"source-turn-{index + 1:02d}"
                current_task_id = f"current-turn-{index + 1:02d}"
                source_task = task_workspace.task_workspace(source_task_id, "codex")
                payload = f"conversation-image-{index}".encode()
                source = source_task / "outputs" / name
                source.write_bytes(payload)
                content = self._rich_file(name, f"outputs/{name}")
                artifacts = task_workspace.import_referenced_task_artifacts(
                    current_task_id,
                    content,
                    source_task_ids=[source_task_id],
                )
                _, document = build_rich_output(content, artifacts, current_task_id)
                self.assertEqual(1, len(document["blocks"]))
                self.assertEqual(base64.b64encode(payload).decode("ascii"), document["blocks"][0]["data_b64"])
                self.assertTrue((root / "tasks" / current_task_id / "outputs" / name).is_file())
            executed.append(label)

    def _run_remote_and_inline_cases(self, executed: list[str]) -> None:
        for index in range(5):
            label = f"remote-{index + 1:02d}"
            with self.subTest(label):
                uri = f"https://cdn.example.com/results/image-{index + 1}.png"
                _, document = build_rich_output(self._rich_file(f"image-{index + 1}.png", uri))
                self.assertEqual(uri, document["blocks"][0]["uri"])
                self.assertEqual("image", document["blocks"][0]["type"])
            executed.append(label)

        for index in range(5):
            label = f"inline-{index + 1:02d}"
            with self.subTest(label):
                encoded = base64.b64encode(f"inline-image-{index}".encode()).decode("ascii")
                block = {
                    "type": "image",
                    "title": f"inline-{index + 1}.png",
                    "data_b64": encoded,
                    "mime_type": "image/png",
                }
                content = "```signalasi-rich\n" + json.dumps(
                    {"blocks": [block, dict(block)]},
                    ensure_ascii=False,
                ) + "\n```"
                _, document = build_rich_output(content)
                self.assertEqual(1, len(document["blocks"]))
                self.assertEqual(encoded, document["blocks"][0]["data_b64"])
            executed.append(label)

    @staticmethod
    def _rich_file(name: str, uri: str) -> str:
        return "```signalasi-rich\n" + json.dumps(
            {"blocks": [{"type": "file", "title": name, "uri": uri}]},
            ensure_ascii=False,
        ) + "\n```"


if __name__ == "__main__":
    unittest.main()
