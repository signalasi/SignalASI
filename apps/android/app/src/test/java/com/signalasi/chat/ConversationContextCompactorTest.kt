package com.signalasi.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationContextCompactorTest {
    @Test
    fun shortConversationStaysVerbatim() {
        val messages = listOf(
            item("u1", ConversationContextRole.USER, "hello", "turn-1"),
            item("a1", ConversationContextRole.ASSISTANT, "hello back", "turn-1")
        )

        val result = ConversationContextCompactor.compile(messages)

        assertFalse(result.compacted)
        assertEquals(messages, result.messages)
        assertTrue(result.summary.isBlank())
    }

    @Test
    fun moreThanFourteenShortTurnsStayWhenTheyFitTheTokenBudget() {
        val messages = buildList {
            repeat(24) { index ->
                add(item("u$index", ConversationContextRole.USER, "question $index", "turn-$index"))
                add(item("a$index", ConversationContextRole.ASSISTANT, "answer $index", "turn-$index"))
            }
        }

        val result = ConversationContextCompactor.compile(messages)

        assertFalse(result.compacted)
        assertEquals(messages, result.messages)
    }

    @Test
    fun olderTurnsBecomeReferenceSummaryAndLatestTurnsStayVerbatim() {
        val messages = buildList {
            repeat(12) { index ->
                add(
                    item(
                        "u$index",
                        ConversationContextRole.USER,
                        "Please update project $index at C:\\work\\project-$index\\plan.md " +
                            "api_key=secret-$index " + "details ".repeat(80),
                        "turn-$index"
                    )
                )
                add(
                    item(
                        "a$index",
                        ConversationContextRole.ASSISTANT,
                        "Verified project $index and saved the result. " + "result ".repeat(90),
                        "turn-$index"
                    )
                )
            }
        }

        val result = ConversationContextCompactor.compile(
            messages,
            budget = ConversationContextBudget(
                contextWindowTokens = 8_192,
                reservedOutputTokens = 2_048,
                triggerRatio = 0.50,
                targetRatio = 0.30,
                minimumRecentGroups = 2,
                maximumSummaryTokens = 900
            )
        )

        assertTrue(result.compacted)
        assertTrue(result.compactedGroupCount >= 8)
        assertTrue(result.messages.any { it.id == "u11" })
        assertTrue(result.messages.any { it.id == "a11" })
        assertTrue(result.messages.none { it.id == "u0" })
        assertTrue("u0" in result.compactedMessageIds)
        assertTrue("turn-0" in result.compactedGroupIds)
        assertTrue(result.summary.contains("Earlier user goals"))
        assertTrue(result.summary.contains("C:\\work\\project-"))
        assertFalse(result.summary.contains("secret-0"))
        assertTrue(result.compactedEstimatedTokens < result.originalEstimatedTokens)
        assertTrue(
            ConversationContextCompactor.referenceBlock(result.summary)
                .contains("latest user message is the active task")
        )
    }

    @Test
    fun tokenBudgetKeepsMoreShortTurnsThanLongTurns() {
        fun messages(body: String) = buildList {
            repeat(40) { index ->
                add(item("u$index", ConversationContextRole.USER, "question $index $body", "turn-$index"))
                add(item("a$index", ConversationContextRole.ASSISTANT, "answer $index $body", "turn-$index"))
            }
        }
        val budget = ConversationContextBudget(
            contextWindowTokens = 4_096,
            reservedOutputTokens = 1_024,
            triggerRatio = 0.50,
            targetRatio = 0.30,
            minimumRecentGroups = 2,
            maximumSummaryTokens = 400
        )
        val shortResult = ConversationContextCompactor.compile(messages("short ".repeat(8)), budget = budget)
        val longResult = ConversationContextCompactor.compile(messages("long ".repeat(100)), budget = budget)

        assertTrue(shortResult.compacted)
        assertTrue(longResult.compacted)
        assertTrue(shortResult.messages.size > longResult.messages.size)
        assertEquals("a39", shortResult.messages.last().id)
        assertEquals("a39", longResult.messages.last().id)
    }

    @Test
    fun previousSummaryIsMergedWithoutRecursiveSummaryLabels() {
        val messages = buildList {
            repeat(7) { index ->
                add(item("u$index", ConversationContextRole.USER, "goal $index " + "context ".repeat(80), "turn-$index"))
                add(item("a$index", ConversationContextRole.ASSISTANT, "result $index " + "verified ".repeat(80), "turn-$index"))
            }
        }
        val previous = """
            Prior durable facts:
            - Keep the release channel stable.
            Previous summary:
            - Do not duplicate this heading.
        """.trimIndent()

        val result = ConversationContextCompactor.compile(
            messages,
            previousSummary = previous,
            budget = ConversationContextBudget(
                contextWindowTokens = 4_096,
                reservedOutputTokens = 1_024,
                triggerRatio = 0.25,
                targetRatio = 0.20,
                minimumRecentGroups = 2,
                maximumSummaryTokens = 400
            )
        )

        assertTrue(result.summary.contains("Keep the release channel stable."))
        assertFalse(result.summary.contains("Previous summary:"))
        assertEquals(1, Regex("Keep the release channel stable\\.").findAll(result.summary).count())
    }

    @Test
    fun cjkTokenEstimateIsNotTreatedAsFourCharactersPerToken() {
        val chinese = ConversationContextCompactor.estimateTokens("\u4eca\u5929\u4e0a\u6d77\u5929\u6c14\u600e\u4e48\u6837")
        val ascii = ConversationContextCompactor.estimateTokens("abcdefghij")

        assertTrue(chinese > ascii)
    }

    @Test
    fun boundedPromptKeepsLatestRequestTail() {
        val value = "system ".repeat(2_000) + "\nCurrent user request:\nKEEP_THIS_LATEST_REQUEST"

        val result = ConversationContextCompactor.fitTextToTokenBudget(value, maximumTokens = 300)

        assertTrue(ConversationContextCompactor.estimateTokens(result) <= 300)
        assertTrue(result.endsWith("KEEP_THIS_LATEST_REQUEST"))
    }

    private fun item(
        id: String,
        role: ConversationContextRole,
        content: String,
        group: String
    ) = ConversationContextItem(id, role, content, group)
}
