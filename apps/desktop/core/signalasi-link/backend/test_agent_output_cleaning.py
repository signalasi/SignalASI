import unittest

from agent_gateway import clean_hermes_output, clean_openclaw_output


class AgentOutputCleaningTests(unittest.TestCase):
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
