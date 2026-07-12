import unittest

from agent_gateway import clean_hermes_output


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


if __name__ == "__main__":
    unittest.main()
