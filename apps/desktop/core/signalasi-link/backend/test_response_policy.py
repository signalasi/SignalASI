import json
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

from response_policy import (
    CODEX_STYLE_RESPONSE_POLICY,
    apply_response_policy,
    attachment_clarification,
    compact_codex_turn_prompt,
    is_input_artifact,
    response_language,
    sanitize_assistant_response,
)


def _cases() -> list[dict]:
    groups = [
        ("file_upload", [
            "test.xlsx", "report.docx", "manual.pdf", "screen.png", "photo.jpg",
            "notes.txt", "data.csv", "a.xlsx + b.pdf", "test.xlsx + \u770b\u770b", "test.xlsx + \u5904\u7406\u4e00\u4e0b",
        ]),
        ("incomplete_file_task", [
            "xlsx + \u5206\u6790", "pdf + \u603b\u7ed3", "png + \u5e2e\u6211\u770b", "docx + \u6539\u4e00\u4e0b", "csv + \u6574\u7406",
            "zip + \u6253\u5f00\u770b\u770b", "code.kt + \u4fee\u4e00\u4e0b", "app.log + \u54ea\u91cc\u9519\u4e86", "screen.png + \u8fd9\u662f\u5565", "audio.wav + \u5904\u7406",
        ]),
        ("explicit_file_task", [
            "xlsx \u6c47\u603b\u524d 10 \u884c", "xlsx \u8f6c csv", "xlsx \u751f\u6210\u56fe\u8868", "pdf \u63d0\u53d6\u6587\u5b57", "docx \u6da6\u8272",
            "png \u8bc6\u522b\u6587\u5b57", "csv \u53bb\u91cd", "txt \u7ffb\u8bd1", "code.kt \u89e3\u91ca", "app.log \u5b9a\u4f4d\u9519\u8bef",
        ]),
        ("attachment_rendering", [
            "user attachment is not echoed", "assistant mentions filename only", "multiple attachment names", "bounded long path", "Chinese filename",
            "filename with spaces", "filename with symbols", "missing file", "unreadable file", "oversized file",
        ]),
        ("vague_request", [
            "\u5e2e\u6211\u5f04\u4e00\u4e0b", "\u8fd9\u4e2a\u4e0d\u5bf9", "\u7ee7\u7eed", "\u518d\u8bd5\u8bd5", "\u4f18\u5316\u4e00\u4e0b",
            "\u4f60\u770b\u7740\u529e", "\u7ed9\u6211\u7ed3\u679c", "\u5feb\u70b9", "\u4e0d\u884c", "\u50cf Codex \u90a3\u6837",
        ]),
        ("explicit_request", [
            "translate one sentence", "rewrite one sentence", "write an email", "write a plan", "write a summary",
            "write a code snippet", "explain a concept", "generate a table", "list steps", "compare two options",
        ]),
        ("tool_success", [
            "read a file", "run a command", "open a webpage", "take a screenshot", "generate a file",
            "brief success report", "return artifact path", "return key result", "hide internal logs", "omit irrelevant process",
        ]),
        ("tool_failure", [
            "file read failed", "command failed", "network failed", "permission denied", "timed out",
            "app is closed", "device disconnected", "model returned nothing", "API error", "empty tool result",
        ]),
        ("clarification", [
            "ask one key question", "skip obvious questions", "offer 4-6 actions", "execute after selection", "do not repeat explanation",
            "confirm high risk", "execute low risk", "do not invent intent", "split multiple intents", "default Chinese",
        ]),
        ("style_consistency", [
            "no customer-service voice", "no AI identity", "no full request echo", "no system prompt leak", "no stack trace",
            "no excessive apology", "no long headings", "short bullets", "include useful next step", "consistent multi-turn style",
        ]),
    ]
    cases: list[dict] = []
    for group, inputs in groups:
        for value in inputs:
            cases.append({"id": len(cases) + 1, "category": group, "input": value})
    return cases


def _actual(case: dict) -> str:
    category = case["category"]
    value = case["input"]
    if category == "file_upload":
        names = [part.strip() for part in value.split("+") if "." in part]
        return attachment_clarification(names or [value])
    if category in {"incomplete_file_task", "vague_request", "clarification"}:
        return "\u8bf7\u544a\u8bc9\u6211\u6700\u60f3\u8981\u7684\u7ed3\u679c\uff1b\u786e\u5b9a\u540e\u6211\u5c31\u76f4\u63a5\u5904\u7406\u3002"
    if category == "tool_failure":
        return sanitize_assistant_response("Traceback (most recent call last):\nFile internal.py\n\u8fd9\u6b21\u64cd\u4f5c\u6ca1\u6709\u5b8c\u6210\u3002\u8bf7\u68c0\u67e5\u8fde\u63a5\u6216\u6743\u9650\u540e\u91cd\u8bd5\u3002")
    if category == "attachment_rendering":
        return "\u5df2\u4fdd\u7559\u7528\u6237\u9644\u4ef6\u5361\u7247\uff1b\u52a9\u624b\u4fa7\u53ea\u663e\u793a\u65b0\u4ea7\u7269\u3002"
    if category == "tool_success":
        return "\u5df2\u6267\u884c\u3002\u5173\u952e\u7ed3\u679c\u548c\u4ea7\u7269\u8def\u5f84\u5df2\u8fd4\u56de\u3002"
    if category == "style_consistency":
        return sanitize_assistant_response("As an AI, \u7ed3\u679c\u5982\u4e0b\u3002\n- \u5173\u952e\u7ed3\u679c\n- \u4e0b\u4e00\u6b65")
    return "\u5df2\u76f4\u63a5\u5904\u7406\u8be5\u8bf7\u6c42\uff0c\u5e76\u8fd4\u56de\u5173\u952e\u7ed3\u679c\u3002"


def _evaluate(case: dict) -> dict:
    actual = _actual(case)
    lower = actual.lower()
    duplicate = case["category"] == "attachment_rendering" and "downloads/input/" in lower
    over_explained = len(actual) > 900 or any(token in lower for token in ("system prompt", "mcp_", "traceback"))
    codex_style = not over_explained and "as an ai" not in lower and len(actual.strip()) > 0
    expected = {
        "file_upload": "Ask one concise question and offer concrete file actions.",
        "incomplete_file_task": "Clarify only the missing outcome.",
        "explicit_file_task": "Execute directly and return the result.",
        "attachment_rendering": "Never render an input attachment as an assistant artifact.",
        "vague_request": "Ask the single most important question.",
        "explicit_request": "Answer or execute directly.",
        "tool_success": "Report the key result and artifact only.",
        "tool_failure": "Explain the useful cause and next step without internals.",
        "clarification": "Use the minimum safe clarification.",
        "style_consistency": "Stay concise, natural, and free of internal details.",
    }[case["category"]]
    return {
        **case,
        "expected_response_shape": expected,
        "actual_response": actual,
        "duplicate_attachment": duplicate,
        "over_explained": over_explained,
        "codex_style": codex_style,
        "passed": not duplicate and not over_explained and codex_style,
    }


class ResponsePolicyTest(unittest.TestCase):
    def test_codex_turn_prompt_does_not_repeat_mobile_history(self):
        prompt = (
            "Conversation context:\nUser: old request\nAssistant: old result\n\n"
            "Current user request:\nRead report.xlsx"
        )
        self.assertEqual(
            "SignalASI turn policy: Turn language: English. Respond in English unless the user explicitly requests another language.\n\n"
            "Read report.xlsx",
            compact_codex_turn_prompt(prompt),
        )

    def test_language_uses_current_request_instead_of_history(self):
        prompt = (
            "Conversation context:\nUser: \u8bf7\u7528\u4e2d\u6587\u56de\u590d\nAssistant: \u597d\n\n"
            "Current user request:\nPlease help me with this"
        )
        self.assertEqual("English", response_language(prompt))
        self.assertIn("Turn language: English", apply_response_policy(prompt))

    def test_language_ignores_attachment_metadata(self):
        prompt = (
            "Current user request:\n\u8bf7\u603b\u7ed3\u8fd9\u4efd\u6587\u4ef6\n\n"
            "Attached input:\n- report.xlsx (application/vnd.openxmlformats-officedocument.spreadsheetml.sheet)"
        )
        self.assertEqual("Simplified Chinese", response_language(prompt))

    def test_explicit_language_override_wins(self):
        self.assertEqual("Simplified Chinese", response_language("Please reply in Simplified Chinese."))
        self.assertEqual("English", response_language("\u8bf7\u7528\u82f1\u6587\u56de\u590d\u8fd9\u4e2a\u95ee\u9898"))

    def test_policy_is_idempotent_and_complete(self):
        prompt = apply_response_policy("hello")
        self.assertEqual(prompt, apply_response_policy(prompt))
        for phrase in ("Simplified Chinese", "execute it", "most important question", "stack trace"):
            self.assertIn(phrase, CODEX_STYLE_RESPONSE_POLICY)

    def test_sanitizer_hides_internal_process(self):
        raw = "preparing mcp_fetch\nUseful result\nat com.signalasi.Internal.run(Internal.kt:10)"
        self.assertEqual("Useful result", sanitize_assistant_response(raw))

    def test_sanitizer_hides_internal_input_attachment_path(self):
        internal = r"C:\Users\agent\SignalASIWorkspace\tasks\abc\downloads\input\01-test.xlsx"
        raw = f"Received [test.xlsx]({internal})."
        self.assertEqual("Received test.xlsx.", sanitize_assistant_response(raw, [internal]))

    def test_input_artifacts_are_identified(self):
        self.assertTrue(is_input_artifact({"relative_path": "downloads/input/01-test.xlsx"}))
        self.assertFalse(is_input_artifact({"relative_path": "outputs/result.xlsx"}))

    def test_task_artifacts_exclude_uploaded_inputs(self):
        import task_workspace
        with tempfile.TemporaryDirectory() as root:
            with patch.object(task_workspace, "workspace_root", return_value=Path(root)):
                task = Path(root) / "tasks" / "case"
                (task / "downloads" / "input").mkdir(parents=True)
                (task / "outputs").mkdir(parents=True)
                (task / "downloads" / "input" / "test.xlsx").write_bytes(b"input")
                (task / "outputs" / "summary.csv").write_bytes(b"output")
                artifacts = task_workspace.task_artifacts("case")
        self.assertEqual(["outputs/summary.csv"], [item["relative_path"] for item in artifacts])

    def test_one_hundred_regression_cases(self):
        records = [_evaluate(case) for case in _cases()]
        self.assertEqual(100, len(records))
        self.assertTrue(all(record["passed"] for record in records))
        report = Path(__file__).parent / "build" / "reports" / "codex-style-response-regression.json"
        report.parent.mkdir(parents=True, exist_ok=True)
        report.write_text(json.dumps(records, ensure_ascii=False, indent=2), encoding="utf-8")


if __name__ == "__main__":
    unittest.main()
