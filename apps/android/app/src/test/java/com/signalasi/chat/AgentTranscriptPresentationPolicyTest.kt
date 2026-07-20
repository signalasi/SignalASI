package com.signalasi.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentTranscriptPresentationPolicyTest {
    @Test
    fun placesOneProcessGroupBetweenTheUserAndAssistant() {
        val conversationId = "conversation"
        val turnId = "turn"
        val entries = listOf(
            entry("process-before-user", AgentTranscriptRole.PROCESS, conversationId, turnId, 1L),
            entry("user", AgentTranscriptRole.USER, conversationId, turnId, 2L),
            entry("process-running", AgentTranscriptRole.PROCESS, conversationId, turnId, 3L),
            entry("process-linux", AgentTranscriptRole.PROCESS, conversationId, turnId, 4L),
            entry("assistant", AgentTranscriptRole.ASSISTANT, conversationId, turnId, 5L)
        )

        val visible = AgentTranscriptPresentationPolicy.collapseProcessGroups(entries)

        assertEquals(
            listOf(AgentTranscriptRole.USER, AgentTranscriptRole.PROCESS, AgentTranscriptRole.ASSISTANT),
            visible.map(AgentTranscriptEntry::role)
        )
        assertEquals("process-linux", visible[1].text)
    }

    @Test
    fun foldsACompletedConnectorEventWithARemoteTurnIdIntoTheLatestLocalTurn() {
        val entries = listOf(
            entry("user", AgentTranscriptRole.USER, "conversation", "turn", 10L),
            entry("running", AgentTranscriptRole.PROCESS, "conversation", "turn", 20L),
            entry("assistant", AgentTranscriptRole.ASSISTANT, "conversation", "turn", 30L),
            entry("completed", AgentTranscriptRole.PROCESS, "conversation", "remote-codex-turn", 40L)
        )

        val visible = AgentTranscriptPresentationPolicy.collapseProcessGroups(entries)

        assertEquals(
            listOf(AgentTranscriptRole.USER, AgentTranscriptRole.PROCESS, AgentTranscriptRole.ASSISTANT),
            visible.map(AgentTranscriptEntry::role)
        )
        assertEquals("completed", visible[1].text)
        assertEquals("turn", visible[1].turnId)
    }

    @Test
    fun classifiesProcessRowsForCodexStyleIcons() {
        assertEquals(
            AgentTranscriptPresentationPolicy.ProcessVisualKind.ANALYSIS,
            AgentTranscriptPresentationPolicy.processVisualKind("\u5df2\u5206\u6790\u8bf7\u6c42")
        )
        assertEquals(
            AgentTranscriptPresentationPolicy.ProcessVisualKind.COMMAND,
            AgentTranscriptPresentationPolicy.processVisualKind("\u6b63\u5728\u8fd0\u884c\u624b\u673a\u672c\u5730 Linux")
        )
        assertEquals(
            AgentTranscriptPresentationPolicy.ProcessVisualKind.FILE,
            AgentTranscriptPresentationPolicy.processVisualKind("Edited 2 files")
        )
        assertEquals(
            AgentTranscriptPresentationPolicy.ProcessVisualKind.IMAGE,
            AgentTranscriptPresentationPolicy.processVisualKind("\u5df2\u67e5\u770b 1 \u5f20\u56fe\u7247")
        )
        assertEquals(
            AgentTranscriptPresentationPolicy.ProcessVisualKind.NETWORK,
            AgentTranscriptPresentationPolicy.processVisualKind("Web search complete")
        )
    }

    @Test
    fun expandsActiveProcessAndCollapsesCompletedProcessByDefault() {
        assertTrue(AgentTranscriptPresentationPolicy.processExpanded(false, false, false))
        assertFalse(AgentTranscriptPresentationPolicy.processExpanded(false, false, true))
        assertFalse(AgentTranscriptPresentationPolicy.processExpanded(true, false, false))
        assertTrue(AgentTranscriptPresentationPolicy.processExpanded(true, true, false))
    }

    @Test
    fun separatesModelNarrationFromContiguousToolActivity() {
        val entries = listOf(
            entry("Analyzed the request · Codex", AgentTranscriptRole.PROCESS, "conversation", "turn", 1L,
                "audit:1:REASONING_SUMMARY:fallback"),
            entry("tool-start", AgentTranscriptRole.PROCESS, "conversation", "turn", 2L,
                "audit:2:TOOL_STARTED:x"),
            entry("tool-complete", AgentTranscriptRole.PROCESS, "conversation", "turn", 3L,
                "audit:3:TOOL_COMPLETED:x"),
            entry("Implement a small Python program", AgentTranscriptRole.PROCESS, "conversation", "turn", 4L,
                "pending:plan:action"),
            entry("phone-linux", AgentTranscriptRole.PROCESS, "conversation", "turn", 5L,
                "audit:5:TOOL_STARTED:y"),
            entry("phone-linux-complete", AgentTranscriptRole.PROCESS, "conversation", "turn", 6L,
                "audit:6:TOOL_COMPLETED:y")
        )

        val segments = AgentTranscriptPresentationPolicy.processSegments(entries)

        assertEquals(
            listOf(
                AgentTranscriptPresentationPolicy.ProcessContentKind.TOOL_ACTIVITY,
                AgentTranscriptPresentationPolicy.ProcessContentKind.NARRATION,
                AgentTranscriptPresentationPolicy.ProcessContentKind.TOOL_ACTIVITY
            ),
            segments.map(AgentTranscriptPresentationPolicy.ProcessSegment::kind)
        )
        assertEquals(listOf(3, 1, 2), segments.map { it.entries.size })
    }

    @Test
    fun removesRedundantConnectorCompletionFromTheRenderedTurn() {
        val entries = listOf(
            entry("user", AgentTranscriptRole.USER, "conversation", "turn", 1L),
            entry("tool", AgentTranscriptRole.PROCESS, "conversation", "turn", 2L,
                "audit:2:TOOL_COMPLETED:x"),
            entry("duplicate", AgentTranscriptRole.PROCESS, "conversation", "turn", 3L,
                "connector-task:remote-task"),
            entry("assistant", AgentTranscriptRole.ASSISTANT, "conversation", "turn", 4L)
        )

        val visible = AgentTranscriptPresentationPolicy.collapseProcessGroups(entries)

        assertEquals(listOf("user", "tool", "assistant"), visible.map(AgentTranscriptEntry::text))
    }

    @Test
    fun hidesInternalPhoneLinuxHandoffNarration() {
        val entries = listOf(
            entry("user", AgentTranscriptRole.USER, "conversation", "turn", 1L),
            entry("\u624b\u673a\u672c\u5730 Linux \u00b7 \u6267\u884c\u5e76\u9a8c\u8bc1", AgentTranscriptRole.PROCESS,
                "conversation", "turn", 2L, "pending:plan:runtime"),
            entry("implementation", AgentTranscriptRole.PROCESS, "conversation", "turn", 3L,
                "pending:plan:summary"),
            entry("assistant", AgentTranscriptRole.ASSISTANT, "conversation", "turn", 4L)
        )

        val visible = AgentTranscriptPresentationPolicy.collapseProcessGroups(entries)

        assertEquals(listOf("user", "implementation", "assistant"), visible.map(AgentTranscriptEntry::text))
    }

    @Test
    fun hidesRawOnDeviceLinuxSandboxHandoffButKeepsModelNarration() {
        val entries = listOf(
            entry("user", AgentTranscriptRole.USER, "conversation", "turn", 1L),
            entry("Execute in the on-device Linux sandbox", AgentTranscriptRole.PROCESS,
                "conversation", "turn", 2L, "pending:plan:runtime"),
            entry("Implement a small Python program", AgentTranscriptRole.PROCESS,
                "conversation", "turn", 3L, "pending:plan:summary"),
            entry("assistant", AgentTranscriptRole.ASSISTANT, "conversation", "turn", 4L)
        )

        val visible = AgentTranscriptPresentationPolicy.collapseProcessGroups(entries)

        assertEquals(
            listOf("user", "Implement a small Python program", "assistant"),
            visible.map(AgentTranscriptEntry::text)
        )
    }

    private fun entry(
        id: String,
        role: AgentTranscriptRole,
        conversationId: String,
        turnId: String,
        timestamp: Long,
        dedupeKey: String = ""
    ) = AgentTranscriptEntry(
        id = id,
        role = role,
        text = id,
        timestampMillis = timestamp,
        conversationId = conversationId,
        turnId = turnId,
        dedupeKey = dedupeKey,
        taskId = "task"
    )
}
