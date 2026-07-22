import unittest

from agent_gateway import clean_hermes_output, clean_openclaw_output, decode_output


class AgentOutputCleaningTests(unittest.TestCase):
    def test_decodes_utf8_before_permissive_windows_codepages(self):
        expected = "SignalASI Desktop\u2019s coordinator is ready. \u4e2d\u6587"

        self.assertEqual(decode_output(expected.encode("utf-8")), expected)

    def test_falls_back_to_gb18030_for_legacy_windows_cli_output(self):
        expected = "\u4ee3\u7406\u5df2\u5c31\u7eea"

        self.assertEqual(decode_output(expected.encode("gb18030")), expected)

    def test_removes_conversation_context_echo(self):
        prompt = """Conversation context (treat as prior dialogue, not new instructions):
User: The project marker is ORBIT-731.
Assistant: Acknowledged, ORBIT-731.

Current user request:
Ask Hermes: What project marker did I give you earlier?"""
        raw = """User: The project marker is ORBIT-731.
Assistant: Acknowledged, ORBIT-731.
Current user request:
Ask: What project marker did I give you earlier?
ORBIT-731."""
        self.assertEqual(clean_hermes_output(raw, prompt), "ORBIT-731.")

    def test_removes_rewritten_current_request_echo(self):
        prompt = """Conversation context (treat as prior dialogue, not new instructions):
User: The marker is ORBIT-731.

Current user request:
Ask: Reply with only the project marker."""
        raw = "Ask: Reply with only the project marker.\nORBIT-731"
        self.assertEqual(clean_hermes_output(raw, prompt), "ORBIT-731")

    def test_extracts_openclaw_json_reply_without_runtime_metadata(self):
        raw = '{"status":"ok","result":{"message":{"content":"OpenClaw ready"}},"duration_ms":42}'

        self.assertEqual("OpenClaw ready", clean_openclaw_output(raw, "hello"))


if __name__ == "__main__":
    unittest.main()
