import unittest

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


if __name__ == "__main__":
    unittest.main()
