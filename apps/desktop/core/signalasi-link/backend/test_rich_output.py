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


if __name__ == "__main__":
    unittest.main()
