package com.signalasi.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentConversationMergePolicyTest {
    @Test
    fun mergesDialogueOnceAndLeavesInternalProcessEntriesBehind() {
        val parent = conversation("parent", "Main topic")
        val child = conversation(
            id = "child",
            title = "Agent research",
            createdByAgent = true,
            parentConversationId = parent.id,
            inputTokens = 20,
            outputTokens = 30
        )
        val entries = listOf(
            entry("user", AgentTranscriptRole.USER, child.id, "Investigate the runtime"),
            entry("process", AgentTranscriptRole.PROCESS, child.id, "Ran a tool"),
            entry(
                "assistant",
                AgentTranscriptRole.ASSISTANT,
                child.id,
                "The runtime is ready",
                richOutputJson = "[{\"id\":\"result\",\"type\":\"markdown\",\"text\":\"ready\"}]"
            )
        )

        val mutation = AgentConversationMergePolicy.mergeIntoParent(
            listOf(parent, child),
            entries,
            child.id,
            nowMillis = 1_000L
        )

        assertTrue(mutation.result.merged)
        assertEquals(2, mutation.result.copiedEntryCount)
        assertEquals(0, mutation.result.skippedEntryCount)
        val copied = mutation.entries.filter { it.conversationId == parent.id }
        assertEquals(listOf(AgentTranscriptRole.USER, AgentTranscriptRole.ASSISTANT), copied.map { it.role })
        assertTrue(copied.all { it.sourceConversationId == child.id })
        assertTrue(copied.all { it.sourceConversationTitle == child.title })
        assertEquals(entries.last().richOutputJson, copied.last().richOutputJson)
        val mergedChild = mutation.conversations.first { it.id == child.id }
        assertEquals(AgentConversationStatus.ARCHIVED, mergedChild.status)
        assertTrue(mergedChild.trackingPaused)
        assertEquals(parent.id, mergedChild.mergedIntoConversationId)
        val mergedParent = mutation.conversations.first { it.id == parent.id }
        assertEquals(20L, mergedParent.inputTokens)
        assertEquals(30L, mergedParent.outputTokens)

        val repeated = AgentConversationMergePolicy.mergeIntoParent(
            mutation.conversations,
            mutation.entries,
            child.id,
            nowMillis = 2_000L
        )
        assertFalse(repeated.result.merged)
        assertEquals(AgentConversationMergeFailure.ALREADY_MERGED, repeated.result.failure)
    }

    @Test
    fun refusesToMergeAcrossPrivacyBoundaries() {
        val parent = conversation("parent", "Main topic")
        val child = conversation(
            id = "child",
            title = "Private research",
            createdByAgent = true,
            parentConversationId = parent.id,
            privateMode = true
        )

        val mutation = AgentConversationMergePolicy.mergeIntoParent(
            listOf(parent, child),
            emptyList(),
            child.id
        )

        assertFalse(mutation.result.merged)
        assertEquals(AgentConversationMergeFailure.PRIVACY_MISMATCH, mutation.result.failure)
    }

    @Test
    fun restoresAnArchivedParentWhenTheAgentTopicIsMerged() {
        val parent = conversation("parent", "Main topic").copy(status = AgentConversationStatus.ARCHIVED)
        val child = conversation(
            id = "child",
            title = "Agent research",
            createdByAgent = true,
            parentConversationId = parent.id
        )

        val mutation = AgentConversationMergePolicy.mergeIntoParent(
            listOf(parent, child),
            emptyList(),
            child.id
        )

        assertTrue(mutation.result.merged)
        assertEquals(AgentConversationStatus.ACTIVE, mutation.result.targetConversation?.status)
    }

    @Test
    fun rebindsConversationScopedMemoryWithoutChangingGlobalMemory() {
        val memory = InMemoryAgentMemoryStore()
        memory.remember(AgentMemoryItem(
            kind = AgentMemoryKind.TASK,
            value = "Child task",
            scope = AgentMemoryScope.CONVERSATION,
            scopeId = "child"
        ))
        memory.remember(AgentMemoryItem(
            kind = AgentMemoryKind.PREFERENCE,
            value = "Global preference",
            scope = AgentMemoryScope.GLOBAL
        ))

        assertEquals(1, memory.rebindConversationScope("child", "parent"))

        val items = memory.snapshot().activeItems
        assertEquals("parent", items.first { it.value == "Child task" }.scopeId)
        assertEquals("", items.first { it.value == "Global preference" }.scopeId)
    }

    @Test
    fun doesNotDuplicateAnInsightAlreadyDeliveredToTheParent() {
        val parent = conversation("parent", "Main topic")
        val child = conversation(
            id = "child",
            title = "Agent research",
            createdByAgent = true,
            parentConversationId = parent.id
        )
        val parentInsight = entry("parent-insight", AgentTranscriptRole.ASSISTANT, parent.id, "Shared result")
            .copy(dedupeKey = "global-agent:insight")
        val childInsight = entry("child-insight", AgentTranscriptRole.ASSISTANT, child.id, "Shared result")
            .copy(dedupeKey = "global-agent:insight")

        val mutation = AgentConversationMergePolicy.mergeIntoParent(
            listOf(parent, child),
            listOf(parentInsight, childInsight),
            child.id
        )

        assertTrue(mutation.result.merged)
        assertEquals(0, mutation.result.copiedEntryCount)
        assertEquals(1, mutation.result.skippedEntryCount)
        assertEquals(1, mutation.entries.count {
            it.conversationId == parent.id && it.dedupeKey == "global-agent:insight"
        })
    }

    private fun conversation(
        id: String,
        title: String,
        createdByAgent: Boolean = false,
        parentConversationId: String = "",
        privateMode: Boolean = false,
        inputTokens: Long = 0L,
        outputTokens: Long = 0L
    ) = AgentConversation(
        id = id,
        title = title,
        createdAt = 1L,
        updatedAt = 1L,
        createdByAgent = createdByAgent,
        parentConversationId = parentConversationId,
        privateMode = privateMode,
        inputTokens = inputTokens,
        outputTokens = outputTokens
    )

    private fun entry(
        id: String,
        role: AgentTranscriptRole,
        conversationId: String,
        text: String,
        richOutputJson: String = ""
    ) = AgentTranscriptEntry(
        id = id,
        role = role,
        text = text,
        timestampMillis = id.length.toLong(),
        conversationId = conversationId,
        turnId = "turn",
        taskId = "task",
        richOutputJson = richOutputJson
    )
}
