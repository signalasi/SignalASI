import tempfile
import unittest
from pathlib import Path

from conversation_context import (
    ContextBudget,
    ContextMessage,
    ConversationSummaryStore,
    compacted_history_cursor,
    compile_context,
    estimate_tokens,
    is_context_overflow,
    reference_block,
    retry_context_windows,
    task_history_messages,
)


class ConversationContextTest(unittest.TestCase):
    def test_short_context_is_not_compacted(self):
        messages = [
            ContextMessage("user", "hello", "u1", "turn-1"),
            ContextMessage("assistant", "hello back", "a1", "turn-1"),
        ]

        result = compile_context(messages)

        self.assertFalse(result.compacted)
        self.assertEqual(tuple(messages), result.messages)

    def test_more_than_fourteen_short_turns_are_kept_when_they_fit(self):
        messages = []
        for index in range(24):
            messages.extend(
                (
                    ContextMessage("user", f"question {index}", f"u{index}", f"turn-{index}"),
                    ContextMessage("assistant", f"answer {index}", f"a{index}", f"turn-{index}"),
                )
            )

        result = compile_context(messages)

        self.assertFalse(result.compacted)
        self.assertEqual(tuple(messages), result.messages)

    def test_token_budget_retains_more_short_turns_than_long_turns(self):
        def messages(body):
            result = []
            for index in range(40):
                result.append(ContextMessage("user", f"question {index} {body}", f"u{index}", f"turn-{index}"))
                result.append(ContextMessage("assistant", f"answer {index} {body}", f"a{index}", f"turn-{index}"))
            return result

        budget = ContextBudget(
            context_window_tokens=4_096,
            reserved_output_tokens=1_024,
            trigger_ratio=0.50,
            target_ratio=0.30,
            minimum_recent_groups=2,
            maximum_summary_tokens=400,
        )
        short = compile_context(messages("short " * 8), budget=budget)
        long = compile_context(messages("long " * 100), budget=budget)

        self.assertTrue(short.compacted)
        self.assertTrue(long.compacted)
        self.assertGreater(len(short.messages), len(long.messages))
        self.assertEqual("a39", short.messages[-1].message_id)
        self.assertEqual("a39", long.messages[-1].message_id)

    def test_summary_is_reference_only_and_redacts_secrets(self):
        messages = []
        for index in range(12):
            messages.extend(
                (
                    ContextMessage(
                        "user",
                        f"Update C:\\work\\project-{index}\\plan.md api_key=secret-{index} " + "details " * 80,
                        f"u{index}",
                        f"turn-{index}",
                    ),
                    ContextMessage(
                        "assistant",
                        f"Verified project {index}. " + "result " * 90,
                        f"a{index}",
                        f"turn-{index}",
                    ),
                )
            )

        result = compile_context(
            messages,
            budget=ContextBudget(
                context_window_tokens=8_192,
                reserved_output_tokens=2_048,
                trigger_ratio=0.50,
                target_ratio=0.30,
                minimum_recent_groups=2,
                maximum_summary_tokens=900,
            ),
        )

        self.assertTrue(result.compacted)
        self.assertIn("Earlier user goals", result.summary)
        self.assertIn("turn-0", result.compacted_group_ids)
        self.assertIn("u0", result.compacted_message_ids)
        self.assertIn("C:\\work\\project-", result.summary)
        self.assertNotIn("secret-0", result.summary)
        self.assertIn("latest user message is the active task", reference_block(result.summary))
        self.assertLess(result.compacted_estimated_tokens, result.original_estimated_tokens)

    def test_task_history_discards_nested_context_and_keeps_current_request(self):
        history = [
            {
                "task_id": "task-1",
                "prompt": (
                    "Conversation context from an older request.\n"
                    "\nCurrent user request:\nSummarize report.csv"
                ),
                "result": "The report has 20 rows.",
                "status": "completed",
                "created_at": 1_000,
            },
            {
                "task_id": "task-current",
                "prompt": "Current duplicate",
                "result": "",
                "status": "running",
                "created_at": 2_000,
            },
        ]

        result = task_history_messages(
            history,
            "Current user request:\nCreate a chart",
            current_task_id="task-current",
        )

        self.assertEqual(
            ["Summarize report.csv", "The report has 20 rows.", "Create a chart"],
            [item.content for item in result],
        )
        self.assertEqual("current:user", result[-1].message_id)

    def test_summary_store_round_trip_and_delete(self):
        with tempfile.TemporaryDirectory() as temporary:
            store = ConversationSummaryStore(Path(temporary) / "summaries.json")
            store.put(
                "conversation-1",
                "api_key=secret-value\n- Durable fact",
                through_created_at=1_000,
                through_task_id="task-1",
            )

            self.assertNotIn("secret-value", store.get("conversation-1"))
            self.assertEqual((1_000, "task-1"), store.state("conversation-1").cursor)
            self.assertTrue(store.delete("conversation-1"))
            self.assertEqual("", store.get("conversation-1"))

    def test_compacted_cursor_skips_already_summarized_history(self):
        history = [
            {"task_id": "task-1", "created_at": 1_000, "prompt": "old", "result": "done", "status": "completed"},
            {"task_id": "task-2", "created_at": 2_000, "prompt": "new", "result": "ready", "status": "completed"},
        ]

        cursor = compacted_history_cursor(history, {"task-1"})
        messages = task_history_messages(
            history,
            "current",
            after_cursor=cursor,
        )

        self.assertEqual((1_000, "task-1"), cursor)
        self.assertEqual(["new", "ready", "current"], [item.content for item in messages])

    def test_compaction_uses_a_contiguous_prefix_for_durable_cursors(self):
        messages = []
        for index, size in enumerate((80, 1_200, 80, 80, 80)):
            messages.extend(
                (
                    ContextMessage(
                        "user",
                        f"request {index} " + "context " * size,
                        f"u{index}",
                        f"task-{index}",
                    ),
                    ContextMessage(
                        "assistant",
                        f"result {index} " + "output " * size,
                        f"a{index}",
                        f"task-{index}",
                    ),
                )
            )

        result = compile_context(
            messages,
            budget=ContextBudget(
                context_window_tokens=4_096,
                reserved_output_tokens=1_024,
                trigger_ratio=0.50,
                target_ratio=0.30,
                minimum_recent_groups=2,
                maximum_summary_tokens=400,
            ),
        )

        compacted = [
            index for index in range(5)
            if f"task-{index}" in result.compacted_group_ids
        ]
        self.assertTrue(result.compacted)
        self.assertEqual(compacted, list(range(len(compacted))))

    def test_cjk_estimate_is_not_four_characters_per_token(self):
        self.assertGreater(estimate_tokens("\u4eca\u5929\u4e0a\u6d77\u5929\u6c14\u600e\u4e48\u6837"), estimate_tokens("abcdefghij"))

    def test_context_overflow_retry_is_token_based_and_error_specific(self):
        self.assertTrue(is_context_overflow(400, '{"code":"context_length_exceeded"}'))
        self.assertTrue(is_context_overflow(413, "Request too large"))
        self.assertFalse(is_context_overflow(401, "Too many tokens in the supplied credential"))
        self.assertFalse(is_context_overflow(400, "Unknown model"))
        self.assertEqual((64_000, 32_000, 16_000, 8_000), retry_context_windows(64_000))
        self.assertEqual((8_192, 4_096), retry_context_windows(8_192))


if __name__ == "__main__":
    unittest.main()
