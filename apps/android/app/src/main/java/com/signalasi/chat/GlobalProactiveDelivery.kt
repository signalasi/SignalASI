package com.signalasi.chat

import java.util.concurrent.CopyOnWriteArraySet

fun interface GlobalProactiveDeliveryListener {
    fun onProactiveDeliveryReady()
}

object GlobalProactiveDeliveryBus {
    private val listeners = CopyOnWriteArraySet<GlobalProactiveDeliveryListener>()

    fun addListener(listener: GlobalProactiveDeliveryListener) {
        listeners += listener
    }

    fun removeListener(listener: GlobalProactiveDeliveryListener) {
        listeners -= listener
    }

    fun signalReady() {
        listeners.forEach(GlobalProactiveDeliveryListener::onProactiveDeliveryReady)
    }
}

enum class GlobalProactiveRouteKind {
    CLAIMED,
    SOURCE,
    BOUND_TOPIC,
    RELATED_TOPIC,
    TITLE_MATCH,
    CREATE_TOPIC,
    SOURCE_FALLBACK
}

data class GlobalProactiveDeliveryRoute(
    val kind: GlobalProactiveRouteKind,
    val conversationId: String = "",
    val createConversation: Boolean = false,
    val title: String = "",
    val parentConversationId: String = "",
    val topicKey: String = "",
    val bindTopic: Boolean = false
)

data class GlobalProactiveTopicNotice(
    val parentConversationId: String,
    val destinationConversationId: String,
    val dedupeKey: String,
    val taskId: String,
    val text: String,
    val actionLabel: String
)

object GlobalProactiveTopicNoticePolicy {
    fun create(
        message: GlobalProactiveMessage,
        destination: AgentConversation
    ): GlobalProactiveTopicNotice? {
        if (message.target != GlobalProactiveTarget.NEW_CONVERSATION ||
            !destination.createdByAgent ||
            destination.parentConversationId.isBlank() ||
            destination.parentConversationId != message.sourceConversationId ||
            destination.status != AgentConversationStatus.ACTIVE
        ) return null
        val title = destination.title.trim().ifBlank { message.topic.trim() }.take(160)
        if (title.isBlank()) return null
        val chinese = GlobalAgentText.containsCjk("$title ${message.content}")
        val text = if (chinese) {
            "\u201c$title\u201d\u5df2\u521b\u5efa\u4e3a\u72ec\u7acb\u4e13\u9898\uff0c\u540e\u7eed\u7814\u7a76\u548c\u7ed3\u679c\u4f1a\u7ee7\u7eed\u6574\u7406\u5230\u90a3\u91cc\u3002"
        } else {
            "$title now has its own topic workspace. Follow-up research and results will continue there."
        }
        return GlobalProactiveTopicNotice(
            parentConversationId = destination.parentConversationId,
            destinationConversationId = destination.id,
            dedupeKey = "global-agent-topic-created:${destination.id}",
            taskId = "global-agent-topic:${destination.id}",
            text = text,
            actionLabel = if (chinese) "\u6253\u5f00\u4e13\u9898" else "Open topic"
        )
    }
}

object GlobalProactiveConversationRouter {
    fun topicKey(topic: String): String = GlobalAgentText.stableKey("global-topic", topic)

    fun resolve(
        message: GlobalProactiveMessage,
        conversations: List<AgentConversation>,
        relatedConversationIds: List<String> = emptyList(),
        autoCreateConversationsEnabled: Boolean,
        excludedConversationIds: Set<String> = emptySet()
    ): GlobalProactiveDeliveryRoute? {
        if (message.sourceConversationId in excludedConversationIds) return null
        val eligible = conversations.filter(::isEligible)
        val source = conversations.firstOrNull { it.id == message.sourceConversationId }
        if (source != null && !isEligible(source)) return null

        message.deliveryConversationId
            .takeIf(String::isNotBlank)
            ?.let { claimedId ->
                eligible.firstOrNull { it.id == claimedId }?.let { claimed ->
                    return GlobalProactiveDeliveryRoute(
                        kind = GlobalProactiveRouteKind.CLAIMED,
                        conversationId = claimed.id,
                        topicKey = topicKey(message.topic)
                    )
                }
            }

        val stableTopicKey = topicKey(message.topic)
        val topicMatch = selectTopicConversation(
            message = message,
            conversations = eligible,
            relatedConversationIds = relatedConversationIds,
            stableTopicKey = stableTopicKey
        )
        val fallback = source ?: eligible.maxByOrNull(AgentConversation::updatedAt)
        return when (message.target) {
            GlobalProactiveTarget.CURRENT_CONVERSATION -> source?.let {
                GlobalProactiveDeliveryRoute(
                    kind = GlobalProactiveRouteKind.SOURCE,
                    conversationId = it.id,
                    topicKey = stableTopicKey
                )
            } ?: topicMatch
                ?: createRoute(message, stableTopicKey, autoCreateConversationsEnabled)
                ?: fallback?.let {
                    GlobalProactiveDeliveryRoute(
                        kind = GlobalProactiveRouteKind.SOURCE_FALLBACK,
                        conversationId = it.id,
                        topicKey = stableTopicKey
                    )
                }

            GlobalProactiveTarget.NEW_CONVERSATION -> topicMatch
                ?: createRoute(message, stableTopicKey, autoCreateConversationsEnabled)
                ?: fallback?.let {
                    GlobalProactiveDeliveryRoute(
                        kind = GlobalProactiveRouteKind.SOURCE_FALLBACK,
                        conversationId = it.id,
                        topicKey = stableTopicKey
                    )
                }

            GlobalProactiveTarget.GLOBAL_DIGEST -> null
        }
    }

    fun isEligible(conversation: AgentConversation): Boolean =
        conversation.status == AgentConversationStatus.ACTIVE &&
            !conversation.privateMode &&
            !conversation.trackingPaused

    private fun selectTopicConversation(
        message: GlobalProactiveMessage,
        conversations: List<AgentConversation>,
        relatedConversationIds: List<String>,
        stableTopicKey: String
    ): GlobalProactiveDeliveryRoute? {
        conversations
            .filter { it.globalTopicKey == stableTopicKey }
            .maxByOrNull(AgentConversation::updatedAt)
            ?.let { conversation ->
                return GlobalProactiveDeliveryRoute(
                    kind = GlobalProactiveRouteKind.BOUND_TOPIC,
                    conversationId = conversation.id,
                    topicKey = stableTopicKey
                )
            }
        conversations
            .filter { it.id in relatedConversationIds && it.id != message.sourceConversationId }
            .sortedWith(
                compareBy<AgentConversation> { relatedConversationIds.indexOf(it.id) }
                    .thenByDescending(AgentConversation::updatedAt)
            )
            .firstOrNull()
            ?.let { conversation ->
                return GlobalProactiveDeliveryRoute(
                    kind = GlobalProactiveRouteKind.RELATED_TOPIC,
                    conversationId = conversation.id,
                    topicKey = stableTopicKey,
                    bindTopic = message.target == GlobalProactiveTarget.NEW_CONVERSATION
                )
            }
        val normalizedTopic = GlobalAgentText.normalize(message.topic)
        val topicTokens = GlobalAgentText.tokens(message.topic)
        conversations.asSequence()
            .filterNot { it.id == message.sourceConversationId }
            .map { conversation ->
                val normalizedTitle = GlobalAgentText.normalize(conversation.title)
                val exact = normalizedTopic.isNotBlank() && normalizedTitle == normalizedTopic
                val overlap = GlobalAgentText.overlap(topicTokens, GlobalAgentText.tokens(conversation.title))
                conversation to when {
                    exact -> 2.0
                    overlap >= MIN_TITLE_OVERLAP -> overlap
                    else -> 0.0
                }
            }
            .filter { it.second > 0.0 }
            .sortedWith(
                compareByDescending<Pair<AgentConversation, Double>> { it.second }
                    .thenByDescending { it.first.updatedAt }
            )
            .firstOrNull()
            ?.first
            ?.let { conversation ->
                return GlobalProactiveDeliveryRoute(
                    kind = GlobalProactiveRouteKind.TITLE_MATCH,
                    conversationId = conversation.id,
                    topicKey = stableTopicKey,
                    bindTopic = message.target == GlobalProactiveTarget.NEW_CONVERSATION
                )
            }
        return null
    }

    private fun createRoute(
        message: GlobalProactiveMessage,
        stableTopicKey: String,
        enabled: Boolean
    ): GlobalProactiveDeliveryRoute? {
        if (!enabled || message.topic.isBlank()) return null
        return GlobalProactiveDeliveryRoute(
            kind = GlobalProactiveRouteKind.CREATE_TOPIC,
            createConversation = true,
            title = message.topic,
            parentConversationId = message.sourceConversationId,
            topicKey = stableTopicKey,
            bindTopic = true
        )
    }

    private const val MIN_TITLE_OVERLAP = 0.62
}

object GlobalProactiveDeliveryPolicy {
    fun isRecoverable(message: GlobalProactiveMessage, nowMillis: Long): Boolean = when (message.status) {
        GlobalProactiveMessageStatus.PENDING,
        GlobalProactiveMessageStatus.NOTIFIED -> true
        GlobalProactiveMessageStatus.DELIVERING ->
            message.deliveryLeaseExpiresAtMillis <= 0L || message.deliveryLeaseExpiresAtMillis <= nowMillis
        GlobalProactiveMessageStatus.DELIVERED,
        GlobalProactiveMessageStatus.DISMISSED -> false
    }

    fun canDeliver(
        message: GlobalProactiveMessage,
        settings: GlobalAgentSettings,
        profile: GlobalAgentAdaptiveProfile,
        history: GlobalInterventionHistory,
        nowMillis: Long
    ): Boolean {
        if (message.urgent || message.deliveryBudgetCounted) return true
        if (!dailyBudgetAvailable(settings, profile, history, nowMillis)) return false
        val topicKey = GlobalAgentText.normalize(message.topic)
        val lastTopicDelivery = history.lastTopicNotificationMillis[topicKey] ?: return true
        val cooldown = GlobalAgentLearningPolicy.topicCooldownMillis(settings, profile, message.topic)
        return nowMillis - lastTopicDelivery !in 0 until cooldown
    }

    fun dailyBudgetAvailable(
        settings: GlobalAgentSettings,
        profile: GlobalAgentAdaptiveProfile,
        history: GlobalInterventionHistory,
        nowMillis: Long
    ): Boolean {
        val budget = GlobalAgentLearningPolicy.dailyMessageBudget(settings, profile)
        if (budget <= 0) return false
        val used = history.notificationTimestamps.count { nowMillis - it in 0..DAY_MILLIS }
        return used < budget
    }

    fun nextEligibleAtMillis(
        message: GlobalProactiveMessage,
        settings: GlobalAgentSettings,
        profile: GlobalAgentAdaptiveProfile,
        history: GlobalInterventionHistory,
        nowMillis: Long
    ): Long {
        if (message.urgent || message.deliveryBudgetCounted) return nowMillis
        val budget = GlobalAgentLearningPolicy.dailyMessageBudget(settings, profile)
        if (budget <= 0) return 0L
        val recent = history.notificationTimestamps.filter { nowMillis - it in 0..DAY_MILLIS }.sorted()
        val budgetReadyAt = if (recent.size >= budget) {
            recent[recent.size - budget] + DAY_MILLIS + 1L
        } else nowMillis
        val topicKey = GlobalAgentText.normalize(message.topic)
        val topicReadyAt = (history.lastTopicNotificationMillis[topicKey] ?: 0L).let { last ->
            if (last <= 0L) nowMillis else last +
                GlobalAgentLearningPolicy.topicCooldownMillis(settings, profile, message.topic) + 1L
        }
        return maxOf(nowMillis, budgetReadyAt, topicReadyAt)
    }

    fun digestBatch(
        messages: List<GlobalProactiveMessage>,
        settings: GlobalAgentSettings,
        profile: GlobalAgentAdaptiveProfile,
        history: GlobalInterventionHistory,
        nowMillis: Long,
        minimumItems: Int,
        maximumItems: Int,
        maximumWaitMillis: Long
    ): List<GlobalProactiveMessage> {
        if (!dailyBudgetAvailable(settings, profile, history, nowMillis)) return emptyList()
        val eligible = messages.asSequence()
            .filter { it.target == GlobalProactiveTarget.GLOBAL_DIGEST }
            .filter { isRecoverable(it, nowMillis) }
            .filter { canDeliver(it, settings, profile, history, nowMillis) }
            .sortedBy(GlobalProactiveMessage::createdAtMillis)
            .toList()
        val ready = eligible.size >= minimumItems ||
            eligible.firstOrNull()?.let { nowMillis - it.createdAtMillis >= maximumWaitMillis } == true
        return if (ready) eligible.take(maximumItems.coerceAtLeast(1)) else emptyList()
    }

    private const val DAY_MILLIS = 24L * 60L * 60L * 1_000L
}
