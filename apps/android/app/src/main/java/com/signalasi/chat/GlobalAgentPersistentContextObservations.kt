package com.signalasi.chat

import java.security.MessageDigest
import java.util.Locale

/** Produces retractable global observations for explicit memory and knowledge mutations. */
object GlobalPersistentContextObservationExtractor {
    fun memoryMutations(
        before: List<AgentMemoryItem>,
        after: List<AgentMemoryItem>,
        timestampMillis: Long = System.currentTimeMillis()
    ): List<GlobalConversationEvent> {
        val previous = before.associateBy(AgentMemoryItem::id)
        val current = after.associateBy(AgentMemoryItem::id)
        return (previous.keys + current.keys).sorted().mapNotNull { itemId ->
            val oldItem = previous[itemId]
            val newItem = current[itemId]
            when {
                oldItem == newItem -> null
                newItem == null && oldItem != null -> memoryDeleted(oldItem, timestampMillis)
                newItem != null -> memoryUpserted(oldItem, newItem, timestampMillis)
                else -> null
            }
        }
    }

    fun knowledgeMutations(
        before: List<AgentKnowledgeItem>,
        after: List<AgentKnowledgeItem>,
        timestampMillis: Long = System.currentTimeMillis()
    ): List<GlobalConversationEvent> {
        val previous = knowledgeSources(before)
        val current = knowledgeSources(after)
        return (previous.keys + current.keys).sorted().mapNotNull { sourceKey ->
            val oldSource = previous[sourceKey]
            val newSource = current[sourceKey]
            when {
                oldSource == newSource -> null
                newSource == null && oldSource != null -> knowledgeDeleted(oldSource, timestampMillis)
                newSource != null -> knowledgeUpserted(oldSource, newSource, timestampMillis)
                else -> null
            }
        }
    }

    private fun memoryUpserted(
        previous: AgentMemoryItem?,
        item: AgentMemoryItem,
        timestampMillis: Long
    ): GlobalConversationEvent {
        val eventType = when {
            item.status == AgentMemoryStatus.CONFLICTED -> GlobalConversationEventType.MEMORY_CONFLICTED
            previous == null -> GlobalConversationEventType.MEMORY_CREATED
            else -> GlobalConversationEventType.MEMORY_UPDATED
        }
        val rootId = memoryRootId(item.id)
        val projection = if (item.status == AgentMemoryStatus.SUPERSEDED) "retract_only" else "upsert"
        val conversationId = when {
            item.scope == AgentMemoryScope.CONVERSATION && item.scopeId.isNotBlank() -> item.scopeId
            else -> MEMORY_CONVERSATION_ID
        }
        return GlobalConversationEvent(
            id = memoryEventId(item),
            type = eventType,
            conversationId = conversationId,
            messageId = item.id,
            actor = GlobalConversationActor.SYSTEM,
            timestampMillis = timestampMillis,
            content = if (projection == "upsert") item.value.replace(Regex("\\s+"), " ").trim().take(MAX_CONTENT) else "",
            contentRef = "encrypted://agent-memory/${item.id}",
            conversationTitle = "Personal memory",
            topicHints = setOf(memoryTopic(item)),
            metadata = mapOf(
                "origin" to "agent_memory",
                "memory_id" to item.id,
                "memory_root_id" to rootId,
                "memory_kind" to item.kind.name,
                "memory_scope" to item.scope.name,
                "memory_scope_id" to item.scopeId.take(160),
                "memory_status" to item.status.name,
                "memory_topic" to memoryTopic(item),
                "memory_source" to item.source.take(120),
                "version" to item.version.toString(),
                "important" to item.important.toString(),
                "auto_learned" to item.autoLearned.toString(),
                "confidence" to item.confidence.coerceIn(0.0, 1.0).toString(),
                "evidence_count" to item.evidenceCount.coerceAtLeast(1).toString(),
                "expires_at_millis" to item.expiresAtMillis.coerceAtLeast(0L).toString(),
                "conflict_group_id" to item.conflictGroupId.take(160),
                "context_visibility" to memoryContextVisibility(item).name,
                "projection" to projection
            ),
            causalEventIds = setOf(rootId),
            retractedEventIds = previous?.let { setOf(memoryEventId(it)) }.orEmpty()
        )
    }

    private fun memoryDeleted(item: AgentMemoryItem, timestampMillis: Long): GlobalConversationEvent {
        val rootId = memoryRootId(item.id)
        return GlobalConversationEvent(
            id = "memory-deleted:${item.id}:$timestampMillis",
            type = GlobalConversationEventType.MEMORY_DELETED,
            conversationId = if (item.scope == AgentMemoryScope.CONVERSATION && item.scopeId.isNotBlank()) {
                item.scopeId
            } else MEMORY_CONVERSATION_ID,
            messageId = item.id,
            actor = GlobalConversationActor.SYSTEM,
            timestampMillis = timestampMillis,
            contentRef = "encrypted://agent-memory/${item.id}",
            conversationTitle = "Personal memory",
            metadata = mapOf(
                "origin" to "agent_memory",
                "memory_id" to item.id,
                "memory_root_id" to rootId,
                "projection" to "retract_only"
            ),
            retractedEventIds = setOf(rootId, memoryEventId(item))
        )
    }

    private fun knowledgeUpserted(
        previous: KnowledgeSourceSnapshot?,
        source: KnowledgeSourceSnapshot,
        timestampMillis: Long
    ): GlobalConversationEvent {
        val type = when {
            previous == null -> GlobalConversationEventType.KNOWLEDGE_IMPORTED
            previous.contentFingerprint == source.contentFingerprint &&
                previous.accessFingerprint != source.accessFingerprint ->
                GlobalConversationEventType.KNOWLEDGE_ACCESS_CHANGED
            else -> GlobalConversationEventType.KNOWLEDGE_UPDATED
        }
        return GlobalConversationEvent(
            id = source.eventId,
            type = type,
            conversationId = KNOWLEDGE_CONVERSATION_ID,
            messageId = source.sourceKey,
            actor = GlobalConversationActor.SYSTEM,
            timestampMillis = timestampMillis,
            content = source.observationContent,
            contentRef = "encrypted://agent-knowledge/source/${source.sourceKey}",
            conversationTitle = "Personal knowledge",
            topicHints = setOf(source.title),
            metadata = mapOf(
                "origin" to "agent_knowledge",
                "knowledge_source_key" to source.sourceKey,
                "knowledge_title" to source.title.take(180),
                "knowledge_kind" to source.kind,
                "source_kind" to source.sourceKind,
                "item_count" to source.itemCount.toString(),
                "cloud_access" to source.cloudAccess.name,
                "agent_access" to source.agentAccess.name,
                "allowed_agent_count" to source.allowedAgentCount.toString(),
                "context_visibility" to source.contextVisibility.name,
                "projection" to "upsert"
            ),
            causalEventIds = setOf(source.rootId),
            retractedEventIds = previous?.let { setOf(it.eventId) }.orEmpty()
        )
    }

    private fun knowledgeDeleted(
        source: KnowledgeSourceSnapshot,
        timestampMillis: Long
    ): GlobalConversationEvent = GlobalConversationEvent(
        id = "knowledge-deleted:${source.sourceKey}:$timestampMillis",
        type = GlobalConversationEventType.KNOWLEDGE_DELETED,
        conversationId = KNOWLEDGE_CONVERSATION_ID,
        messageId = source.sourceKey,
        actor = GlobalConversationActor.SYSTEM,
        timestampMillis = timestampMillis,
        contentRef = "encrypted://agent-knowledge/source/${source.sourceKey}",
        conversationTitle = "Personal knowledge",
        metadata = mapOf(
            "origin" to "agent_knowledge",
            "knowledge_source_key" to source.sourceKey,
            "projection" to "retract_only"
        ),
        retractedEventIds = setOf(source.rootId, source.eventId)
    )

    private fun knowledgeSources(items: List<AgentKnowledgeItem>): Map<String, KnowledgeSourceSnapshot> = items
        .groupBy(::knowledgeSourceKey)
        .mapValues { (sourceKey, sourceItems) -> knowledgeSourceSnapshot(sourceKey, sourceItems) }

    private fun knowledgeSourceSnapshot(
        sourceKey: String,
        items: List<AgentKnowledgeItem>
    ): KnowledgeSourceSnapshot {
        val ordered = items.sortedWith(compareBy<AgentKnowledgeItem> { it.chunkIndex }.thenBy { it.id })
        val first = ordered.first()
        val title = first.title
            .replace(Regex("\\s+\\[\\d+/\\d+]$"), "")
            .replace(Regex("\\s+"), " ")
            .trim()
            .take(180)
            .ifBlank { "Knowledge source" }
        val summary = ordered.asSequence()
            .map(AgentKnowledgeItem::summary)
            .map { it.replace(Regex("\\s+"), " ").trim() }
            .firstOrNull(String::isNotBlank)
            .orEmpty()
            .take(MAX_KNOWLEDGE_SUMMARY)
        val tags = ordered.flatMap(AgentKnowledgeItem::tags)
            .map { it.replace(Regex("\\s+"), " ").trim() }
            .filter(String::isNotBlank)
            .distinct()
            .sorted()
            .take(MAX_TAGS)
        val cloudAccess = when {
            ordered.any { it.cloudAccess == AgentKnowledgeCloudAccess.DENY } -> AgentKnowledgeCloudAccess.DENY
            ordered.any { it.cloudAccess == AgentKnowledgeCloudAccess.SUMMARY_ONLY } ->
                AgentKnowledgeCloudAccess.SUMMARY_ONLY
            else -> AgentKnowledgeCloudAccess.FULL
        }
        val agentAccess = when {
            ordered.any { it.agentAccess == AgentKnowledgeAgentAccess.LOCAL_ONLY } -> AgentKnowledgeAgentAccess.LOCAL_ONLY
            ordered.any { it.agentAccess == AgentKnowledgeAgentAccess.SELECTED_AGENTS } ->
                AgentKnowledgeAgentAccess.SELECTED_AGENTS
            else -> AgentKnowledgeAgentAccess.ANY_PAIRED_AGENT
        }
        val allowedAgentIds = ordered.flatMap(AgentKnowledgeItem::allowedAgentIds).distinct().sorted()
        val contentDigest = sha256(ordered.joinToString("|") { item ->
            sha256(
                listOf(
                    item.id,
                    item.kind.name,
                    item.title,
                    item.content,
                    item.summary,
                    item.tags.joinToString("|"),
                    item.chunkIndex.toString(),
                    item.chunkCount.toString()
                ).joinToString("\u0000")
            )
        })
        val contentFingerprint = GlobalAgentText.stableKey(title, summary, tags.joinToString("|"), contentDigest)
        val accessFingerprint = GlobalAgentText.stableKey(
            cloudAccess.name,
            agentAccess.name,
            allowedAgentIds.joinToString("|")
        )
        val eventFingerprint = GlobalAgentText.stableKey(contentFingerprint, accessFingerprint)
        val contextVisibility = if (
            cloudAccess != AgentKnowledgeCloudAccess.DENY &&
            agentAccess == AgentKnowledgeAgentAccess.ANY_PAIRED_AGENT
        ) GlobalWorldContextVisibility.SHAREABLE else GlobalWorldContextVisibility.LOCAL_ONLY
        return KnowledgeSourceSnapshot(
            sourceKey = sourceKey,
            rootId = "knowledge-root:$sourceKey",
            eventId = "knowledge:$sourceKey:$eventFingerprint",
            title = title,
            summary = summary,
            kind = ordered.map { it.kind.name }.distinct().sorted().joinToString(","),
            sourceKind = sourceKind(first.source),
            itemCount = ordered.size,
            cloudAccess = cloudAccess,
            agentAccess = agentAccess,
            allowedAgentCount = allowedAgentIds.size,
            contextVisibility = contextVisibility,
            contentFingerprint = contentFingerprint,
            accessFingerprint = accessFingerprint,
            tags = tags
        )
    }

    private fun knowledgeSourceKey(item: AgentKnowledgeItem): String = if (item.source.isBlank()) {
        "item-${GlobalAgentText.stableKey(item.id)}"
    } else {
        "source-${GlobalAgentText.stableKey(item.source)}"
    }

    private fun sourceKind(source: String): String = when {
        source.startsWith("https://", ignoreCase = true) -> "https"
        source.startsWith("http://", ignoreCase = true) -> "http"
        source.startsWith("content://", ignoreCase = true) -> "content"
        source.startsWith("file://", ignoreCase = true) -> "file"
        source.startsWith("screen:", ignoreCase = true) -> "screen"
        source.isBlank() -> "internal"
        else -> "local"
    }

    private fun memoryTopic(item: AgentMemoryItem): String = item.key
        .replace(Regex("\\s+"), " ")
        .trim()
        .take(160)
        .ifBlank { item.kind.name.lowercase(Locale.ROOT) }

    private fun memoryContextVisibility(item: AgentMemoryItem): GlobalWorldContextVisibility = when (item.kind) {
        AgentMemoryKind.CONTACT, AgentMemoryKind.SAFETY -> GlobalWorldContextVisibility.LOCAL_ONLY
        else -> GlobalWorldContextVisibility.SHAREABLE
    }

    private fun memoryRootId(itemId: String): String = "memory-root:$itemId"

    private fun memoryEventId(item: AgentMemoryItem): String {
        val fingerprint = GlobalAgentText.stableKey(
            item.kind.name,
            item.value,
            item.key,
            item.version.toString(),
            item.status.name,
            item.conflictGroupId,
            item.scope.name,
            item.scopeId,
            item.important.toString(),
            item.confidence.toString(),
            item.evidenceCount.toString(),
            item.autoLearned.toString(),
            item.expiresAtMillis.toString()
        )
        return "memory:${item.id}:$fingerprint"
    }

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { byte -> "%02x".format(byte) }

    private data class KnowledgeSourceSnapshot(
        val sourceKey: String,
        val rootId: String,
        val eventId: String,
        val title: String,
        val summary: String,
        val kind: String,
        val sourceKind: String,
        val itemCount: Int,
        val cloudAccess: AgentKnowledgeCloudAccess,
        val agentAccess: AgentKnowledgeAgentAccess,
        val allowedAgentCount: Int,
        val contextVisibility: GlobalWorldContextVisibility,
        val contentFingerprint: String,
        val accessFingerprint: String,
        val tags: List<String>
    ) {
        val observationContent: String
            get() = buildString {
                append(title)
                if (summary.isNotBlank()) append(": ").append(summary)
                if (tags.isNotEmpty()) append(" [").append(tags.joinToString(", ")).append(']')
            }.take(MAX_CONTENT)
    }

    private const val MEMORY_CONVERSATION_ID = "global-memory"
    private const val KNOWLEDGE_CONVERSATION_ID = "knowledge-library"
    private const val MAX_CONTENT = 1_200
    private const val MAX_KNOWLEDGE_SUMMARY = 640
    private const val MAX_TAGS = 12
}
