import base64
import os
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

from rich_output import build_rich_output


class RichOutputTests(unittest.TestCase):
    def test_extracts_explicit_blocks_and_keeps_fallback(self):
        content = """Summary

```signalasi-rich
{"blocks":[{"id":"t1","type":"table","columns":["A"],"rows":[["B"]]}]}
```"""
        fallback, document = build_rich_output(content)
        self.assertEqual(fallback, "Summary")
        self.assertEqual(document["version"], 1)
        self.assertEqual(document["blocks"][0]["type"], "table")

    def test_converts_artifacts_to_media_blocks(self):
        with tempfile.TemporaryDirectory() as temporary, patch.dict(
            os.environ, {"SIGNALASI_WORKSPACE_ROOT": temporary}
        ):
            output = Path(temporary) / "tasks" / "task-1" / "outputs" / "preview.png"
            output.parent.mkdir(parents=True)
            output.write_bytes(b"not-a-real-image")
            fallback, document = build_rich_output(
                "Created the requested output.",
                [{"name": "preview.png", "relative_path": "outputs/preview.png", "size": 12}],
                "task-1",
            )
        self.assertEqual(fallback, "Created the requested output.")
        self.assertEqual(document["blocks"][0]["type"], "image")
        self.assertTrue(document["blocks"][0]["uri"].startswith("signalasi-artifact://"))

    def test_preserves_self_contained_html_animation(self):
        fallback, document = build_rich_output(
            """```signalasi-rich
{"blocks":[{"id":"anim","type":"html","text":"<div class='dot'></div>","fallback_text":"Animated result"}]}
```"""
        )
        self.assertEqual(document["blocks"][0]["type"], "html")
        self.assertEqual(document["blocks"][0]["fallback_text"], "Animated result")

    def test_preserves_https_webpage_preview(self):
        fallback, document = build_rich_output(
            '''```signalasi-rich
{"blocks":[{"id":"page","type":"webpage","title":"Result","uri":"https://example.com"}]}
```'''
        )
        self.assertEqual(document["blocks"][0]["type"], "webpage")
        self.assertEqual(document["blocks"][0]["uri"], "https://example.com")

    def test_corrects_mislabelled_webpage_gif_to_image(self):
        fallback, document = build_rich_output(
            '''```signalasi-rich
{"blocks":[{"type":"webpage","uri":"https://cdn.example.com/character.gif"}]}
```'''
        )
        self.assertEqual(document["blocks"][0]["type"], "image")
        self.assertEqual(fallback, "https://cdn.example.com/character.gif")

    def test_preserves_structured_document_blocks_and_metadata(self):
        fallback, document = build_rich_output(
            '''```signalasi-rich
{"blocks":[
  {"type":"list","rows":[["checked","Build"],["unchecked","Verify"]],"metadata":{"style":"checklist"}},
  {"type":"chart","columns":["Run","ms"],"rows":[["1","120"]]},
  {"type":"notice","title":"Ready","text":"Result available","metadata":{"style":"success"}}
]}
```'''
        )
        self.assertEqual(["list", "chart", "notice"], [item["type"] for item in document["blocks"]])
        self.assertEqual("checklist", document["blocks"][0]["metadata"]["style"])
        self.assertEqual([["1", "120"]], document["blocks"][1]["rows"])

    def test_artifact_includes_human_readable_metadata(self):
        with tempfile.TemporaryDirectory() as temporary, patch.dict(
            os.environ, {"SIGNALASI_WORKSPACE_ROOT": temporary}
        ):
            output = Path(temporary) / "tasks" / "task-2" / "outputs" / "report.pdf"
            output.parent.mkdir(parents=True)
            output.write_bytes(b"x" * 2048)
            _, document = build_rich_output(
                "Created output.",
                [{"name": "report.pdf", "relative_path": "outputs/report.pdf", "size": 2048}],
                "task-2",
            )
        self.assertEqual("2.0 KB", document["blocks"][0]["metadata"]["size"])

    def test_small_image_artifact_is_embedded_for_encrypted_phone_delivery(self):
        png = base64.b64decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII="
        )
        with tempfile.TemporaryDirectory() as temporary, patch.dict(
            os.environ, {"SIGNALASI_WORKSPACE_ROOT": temporary}
        ):
            output = Path(temporary) / "tasks" / "task-inline" / "outputs" / "marked.png"
            output.parent.mkdir(parents=True)
            output.write_bytes(png)
            _, document = build_rich_output(
                "Created output.",
                [{"name": output.name, "relative_path": "outputs/marked.png", "size": len(png)}],
                "task-inline",
            )

        block = document["blocks"][0]
        self.assertEqual(base64.b64encode(png).decode("ascii"), block["data_b64"])
        self.assertEqual("encrypted-inline", block["metadata"]["transport"])

    def test_hydrates_relative_image_and_deduplicates_auto_discovery(self):
        png = base64.b64decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII="
        )
        content = """Done.

```signalasi-rich
{"blocks":[{"type":"file","title":"作业批改_全对.jpg","uri":"outputs/作业批改_全对.jpg"}]}
```"""
        with tempfile.TemporaryDirectory() as temporary, patch.dict(
            os.environ, {"SIGNALASI_WORKSPACE_ROOT": temporary}
        ):
            output = Path(temporary) / "tasks" / "current" / "outputs" / "作业批改_全对.jpg"
            output.parent.mkdir(parents=True)
            output.write_bytes(png)
            fallback, document = build_rich_output(
                content,
                [{
                    "name": output.name,
                    "relative_path": "outputs/作业批改_全对.jpg",
                    "size": len(png),
                }],
                "current",
            )

        self.assertEqual("Done.", fallback)
        self.assertEqual(1, len(document["blocks"]))
        block = document["blocks"][0]
        self.assertEqual("image", block["type"])
        self.assertEqual("image/jpeg", block["mime_type"])
        self.assertEqual(base64.b64encode(png).decode("ascii"), block["data_b64"])
        self.assertRegex(block["metadata"]["sha256"], r"^[0-9a-f]{64}$")

    def test_drops_unresolvable_relative_card_without_exposing_protocol(self):
        fallback, document = build_rich_output(
            """```signalasi-rich
{"blocks":[{"type":"file","title":"missing.jpg","uri":"outputs/missing.jpg"}]}
```""",
            task_id="missing-task",
        )
        self.assertEqual("The generated file is unavailable. Please try again.", fallback)
        self.assertIsNone(document)


if __name__ == "__main__":
    unittest.main()
