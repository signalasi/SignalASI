package com.signalasi.chat

import java.nio.charset.StandardCharsets
import java.util.UUID

enum class AgentConversationMergeFailure {
    NONE,
    SOURCE_NOT_FOUND,
    TARGET_NOT_FOUND,
    NOT_AGENT_CREATED,
    ALREADY_MERGED,
    SAME_CONVERSATION,
    PRIVACY_MISMATCH
}

data class AgentConversationMergeResult(
    val merged: Boolean,
    val sourceConversation: AgentConversation? = null,
    val targetConversation: AgentConversation? = null,
    val copiedEntryCount: Int = 0,
    val skippedEntryCount: Int = 0,
    val failure: AgentConversationMergeFailure = AgentConversationMergeFailure.NONE
)

data class AgentConversationMergeMutation(
    val result: AgentConversationMergeResult,
    val conversations: List<AgentConversation>,
    val entries: List<AgentTranscriptEntry>
)

object AgentConversationMergePolicy {
    fun mergeIntoParent(
        conversations: List<AgentConversation>,
        entries: List<AgentTranscriptEntry>,
        sourceConversationId: String,
        nowMillis: Long = System.currentTimeMillis()
    ): AgentConversationMergeMutation {
        val source = conversations.firstOrNull { it.id == sourceConversationId }
            ?: return failure(
                AgentConversationMergeFailure.SOURCE_NOT_FOUND,
                conversations,
                entries
            )
        if (!source.createdByAgent) {
            return failure(AgentConversationMergeFailure.NOT_AGENT_CREATED, conversations, entries, source)
        }
        if (source.mergedIntoConversationId.isNotBlank()) {
            return failure(AgentConversationMergeFailure.ALREADY_MERGED, conversations, entries, source)
        }
        val target = conversations.firstOrNull { it.id == source.parentConversationId }
            ?: return failure(AgentConversationMergeFailure.TARGET_NOT_FOUND, conversations, entries, source)
        if (source.id == target.id) {
            return failure(AgentConversationMergeFailure.SAME_CONVERSATION, conversations, entries, source, target)
        }
        if (source.privateMode != target.privateMode) {
            return failure(AgentConversationMergeFailure.PRIVACY_MISMATCH, conversations, entries, source, target)
        }

        val targetProvenance = entries.asSequence()
            .filter { it.conversationId == target.id && it.sourceEntryId.isNotBlank() }
            .map { "${it.sourceConversationId}:${it.sourceEntryId}" }
            .toMutableSet()
        val targetGlobalDedupeKeys = entries.asSequence()
            .filter { it.conversationId == target.id }
            .map(AgentTranscriptEntry::dedupeKey)
            .filter { it.startsWith("global-agent:") || it.startsWith("global-agent-digest:") }
            .toMutableSet()
        var copied = 0
        var skipped = 0
        val copiedEntries = entries.asSequence()
            .filter { it.conversationId == source.id }
            .filter { it.role != AgentTranscriptRole.PROCESS }
            .sortedWith(compareBy<AgentTranscriptEntry>(AgentTranscriptEntry::timestampMillis)
                .thenBy(AgentTranscriptEntry::id))
            .mapNotNull { entry ->
                val originConversationId = entry.sourceConversationId.ifBlank { source.id }
                val originConversationTitle = entry.sourceConversationTitle.ifBlank { source.title }
                val originEntryId = entry.sourceEntryId.ifBlank { entry.id }
                val provenanceKey = "$originConversationId:$originEntryId"
                val duplicateGlobalDelivery = entry.dedupeKey.isNotBlank() &&
                    entry.dedupeKey in targetGlobalDedupeKeys
                if (!targetProvenance.add(provenanceKey) || duplicateGlobalDelivery) {
                    skipped += 1
                    return@mapNotNull null
                }
                if (entry.dedupeKey.startsWith("global-agent")) targetGlobalDedupeKeys += entry.dedupeKey
                copied += 1
                entry.copy(
                    id = stableMergedEntryId(target.id, originConversationId, originEntryId),
                    dedupeKey = mergedDedupeKey(entry, originConversationId, originEntryId),
                    conversationId = target.id,
                    sourceConversationId = originConversationId,
                    sourceConversationTitle = originConversationTitle,
                    sourceEntryId = originEntryId
                )
            }
            .toList()

        val mergedSummary = mergeSummary(target, source)
        val updatedConversations = conversations.map { conversation ->
            when (conversation.id) {
                source.id -> conversation.copy(
                    status = AgentConversationStatus.ARCHIVED,
                    trackingPaused = true,
                    mergedIntoConversationId = target.id,
                    mergedAtMillis = nowMillis,
                    updatedAt = nowMillis
                )
                target.id -> conversation.copy(
                    status = AgentConversationStatus.ACTIVE,
                    summary = mergedSummary,
                    inputTokens = saturatingAdd(target.inputTokens, source.inputTokens),
                    outputTokens = saturatingAdd(target.outputTokens, source.outputTokens),
                    costMicros = saturatingAdd(target.costMicros, source.costMicros),
                    updatedAt = maxOf(target.updatedAt, source.updatedAt, nowMillis)
                )
                else -> conversation
            }
        }
        val updatedTarget = updatedConversations.first { it.id == target.id }
        val updatedSource = updatedConversations.first { it.id == source.id }
        return AgentConversationMergeMutation(
            result = AgentConversationMergeResult(
                merged = true,
                sourceConversation = updatedSource,
                targetConversation = updatedTarget,
                copiedEntryCount = copied,
                skippedEntryCount = skipped
            ),
            conversations = updatedConversations,
            entries = entries + copiedEntries
        )
    }

    private fun failure(
        failure: AgentConversationMergeFailure,
        conversations: List<AgentConversation>,
        entries: List<AgentTranscriptEntry>,
        source: AgentConversation? = null,
        target: AgentConversation? = null
    ) = AgentConversationMergeMutation(
        result = AgentConversationMergeResult(
            merged = false,
            sourceConversation = source,
            targetConversation = target,
            failure = failure
        ),
        conversations = conversations,
        entries = entries
    )

    private fun stableMergedEntryId(targetId: String, sourceId: String, entryId: String): String =
        UUID.nameUUIDFromBytes(
            "signalasi-conversation-merge:$targetId:$sourceId:$entryId"
                .toByteArray(StandardCharsets.UTF_8)
        ).toString()

    private fun mergedDedupeKey(
        entry: AgentTranscriptEntry,
        sourceConversationId: String,
        sourceEntryId: String
    ): String = if (
        entry.dedupeKey.startsWith("global-agent:") ||
        entry.dedupeKey.startsWith("global-agent-digest:")
    ) {
        entry.dedupeKey
    } else {
        "merged:$sourceConversationId:$sourceEntryId"
    }.take(240)

    private fun mergeSummary(target: AgentConversation, source: AgentConversation): String {
        if (source.summary.isBlank()) return target.summary
        val addition = "Merged topic ${source.title}:\n${source.summary}"
        return listOf(target.summary, addition)
            .filter(String::isNotBlank)
            .joinToString("\n\n")
            .take(12_000)
    }

    private fun saturatingAdd(left: Long, right: Long): Long {
        val safeLeft = left.coerceAtLeast(0L)
        val safeRight = right.coerceAtLeast(0L)
        return if (Long.MAX_VALUE - safeLeft < safeRight) Long.MAX_VALUE else safeLeft + safeRight
    }
}
