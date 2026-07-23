package com.signalasi.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentTranscriptRenderPolicyTest {
    @Test
    fun changedEntryWithStableIdIsReplacedInPlace() {
        val previous = entry("process-1", "Accepted")
        val current = entry("process-1", "Running")

        val diff = AgentTranscriptRenderPolicy.diff(
            renderedIds = listOf(previous.id),
            renderedSignatures = mapOf(previous.id to AgentTranscriptRenderPolicy.signature(previous)),
            incoming = listOf(current)
        )

        assertFalse(diff.reset)
        assertEquals(listOf(0), diff.replacementIndices)
        assertEquals(1, diff.appendFromIndex)
    }

    @Test
    fun unchangedPrefixOnlyAppendsNewRows() {
        val first = entry("user-1", "Run this")
        val second = entry("process-1", "Running")

        val diff = AgentTranscriptRenderPolicy.diff(
            renderedIds = listOf(first.id),
            renderedSignatures = mapOf(first.id to AgentTranscriptRenderPolicy.signature(first)),
            incoming = listOf(first, second)
        )

        assertFalse(diff.reset)
        assertTrue(diff.replacementIndices.isEmpty())
        assertEquals(1, diff.appendFromIndex)
    }

    @Test
    fun removedOrReorderedRowsRequireReset() {
        val first = entry("user-1", "Run this")
        val second = entry("process-1", "Running")

        val diff = AgentTranscriptRenderPolicy.diff(
            renderedIds = listOf(first.id, second.id),
            renderedSignatures = mapOf(
                first.id to AgentTranscriptRenderPolicy.signature(first),
                second.id to AgentTranscriptRenderPolicy.signature(second)
            ),
            incoming = listOf(second)
        )

        assertTrue(diff.reset)
        assertTrue(diff.replacementIndices.isEmpty())
        assertEquals(0, diff.appendFromIndex)
    }

    @Test
    fun richOutputChangeAlsoRefreshesStableAssistantRow() {
        val previous = entry("assistant-1", "Done", richOutputJson = "{\"type\":\"text\"}")
        val current = entry("assistant-1", "Done", richOutputJson = "{\"type\":\"table\"}")

        val diff = AgentTranscriptRenderPolicy.diff(
            renderedIds = listOf(previous.id),
            renderedSignatures = mapOf(previous.id to AgentTranscriptRenderPolicy.signature(previous)),
            incoming = listOf(current)
        )

        assertEquals(listOf(0), diff.replacementIndices)
    }

    private fun entry(
        id: String,
        text: String,
        richOutputJson: String = ""
    ) = AgentTranscriptEntry(
        id = id,
        role = AgentTranscriptRole.PROCESS,
        text = text,
        timestampMillis = 1L,
        richOutputJson = richOutputJson
    )
}
