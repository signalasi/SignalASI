package com.signalasi.chat

data class GlobalProactiveInboxItem(
    val key: String,
    val messageIds: Set<String>,
    val title: String,
    val content: String,
    val topic: String,
    val target: GlobalProactiveTarget,
    val urgent: Boolean,
    val sourceConversationId: String,
    val destinationConversationId: String,
    val causalEventIds: Set<String>,
    val createdAtMillis: Long,
    val deliveredAtMillis: Long,
    val viewedAtMillis: Long,
    val feedbackKind: GlobalAgentFeedbackKind?
) {
    val isNew: Boolean
        get() = viewedAtMillis <= 0L && feedbackKind == null
}

object GlobalProactiveInboxPolicy {
    fun project(
        messages: List<GlobalProactiveMessage>,
        feedback: List<GlobalAgentFeedback>,
        limit: Int = 50
    ): List<GlobalProactiveInboxItem> {
        if (limit <= 0) return emptyList()
        val feedbackByMessage = feedback
            .groupBy(GlobalAgentFeedback::proactiveMessageId)
            .mapValues { (_, values) -> values.maxByOrNull(GlobalAgentFeedback::createdAtMillis)?.kind }
        return messages.asSequence()
            .filter { it.status == GlobalProactiveMessageStatus.DELIVERED }
            .groupBy(::inboxKey)
            .values
            .mapNotNull { group -> projectGroup(group, feedbackByMessage) }
            .sortedWith(
                compareByDescending<GlobalProactiveInboxItem> { it.isNew }
                    .thenByDescending { it.urgent }
                    .thenByDescending { it.deliveredAtMillis }
                    .thenByDescending { it.createdAtMillis }
            )
            .take(limit.coerceIn(1, 100))
            .toList()
    }

    fun newCount(items: List<GlobalProactiveInboxItem>): Int = items.count(GlobalProactiveInboxItem::isNew)

    fun markViewed(
        messages: List<GlobalProactiveMessage>,
        messageIds: Set<String>,
        nowMillis: Long
    ): List<GlobalProactiveMessage> {
        if (messageIds.isEmpty() || nowMillis <= 0L) return messages
        return messages.map { message ->
            if (
                message.id in messageIds &&
                message.status == GlobalProactiveMessageStatus.DELIVERED &&
                message.viewedAtMillis <= 0L
            ) {
                message.copy(viewedAtMillis = nowMillis)
            } else message
        }
    }

    private fun projectGroup(
        group: List<GlobalProactiveMessage>,
        feedbackByMessage: Map<String, GlobalAgentFeedbackKind?>
    ): GlobalProactiveInboxItem? {
        val ordered = group.sortedBy(GlobalProactiveMessage::createdAtMillis)
        val primary = ordered.lastOrNull() ?: return null
        val kinds = ordered.mapNotNull { feedbackByMessage[it.id] }.distinct()
        if (kinds.any { it == GlobalAgentFeedbackKind.NOT_RELEVANT || it == GlobalAgentFeedbackKind.TOO_FREQUENT }) {
            return null
        }
        val content = if (ordered.size == 1) {
            primary.content.trim()
        } else {
            ordered.joinToString("\n") { message ->
                val topic = message.topic.trim().takeIf(String::isNotBlank)
                if (topic == null) "\u2022 ${message.content.trim()}" else "\u2022 $topic: ${message.content.trim()}"
            }
        }
        return GlobalProactiveInboxItem(
            key = inboxKey(primary),
            messageIds = ordered.mapTo(linkedSetOf(), GlobalProactiveMessage::id),
            title = primary.title.trim(),
            content = content,
            topic = primary.topic.trim(),
            target = primary.target,
            urgent = ordered.any(GlobalProactiveMessage::urgent),
            sourceConversationId = primary.sourceConversationId,
            destinationConversationId = ordered
                .lastOrNull { it.deliveredConversationId.isNotBlank() }
                ?.deliveredConversationId
                .orEmpty(),
            causalEventIds = ordered.flatMapTo(linkedSetOf(), GlobalProactiveMessage::causalEventIds),
            createdAtMillis = ordered.minOf(GlobalProactiveMessage::createdAtMillis),
            deliveredAtMillis = ordered.maxOf(GlobalProactiveMessage::deliveredAtMillis),
            viewedAtMillis = ordered.map(GlobalProactiveMessage::viewedAtMillis).takeIf { values ->
                values.all { it > 0L }
            }?.maxOrNull() ?: 0L,
            feedbackKind = kinds.singleOrNull()
        )
    }

    private fun inboxKey(message: GlobalProactiveMessage): String = if (
        message.target == GlobalProactiveTarget.GLOBAL_DIGEST && message.deliveryGroupId.isNotBlank()
    ) {
        "global-agent-digest:${message.deliveryGroupId}"
    } else {
        "global-agent:${message.id}"
    }
}
