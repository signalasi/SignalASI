package com.signalasi.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GlobalProactiveDeliveryTest {
    @Test
    fun foregroundDeliverySignalCanBeSubscribedAndRemoved() {
        var calls = 0
        val listener = GlobalProactiveDeliveryListener { calls += 1 }

        GlobalProactiveDeliveryBus.addListener(listener)
        try {
            GlobalProactiveDeliveryBus.signalReady()
            assertEquals(1, calls)
        } finally {
            GlobalProactiveDeliveryBus.removeListener(listener)
        }
        GlobalProactiveDeliveryBus.signalReady()
        assertEquals(1, calls)
    }

    @Test
    fun currentConversationDeliveryUsesItsEligibleSource() {
        val source = conversation("source", "Current work")

        val route = GlobalProactiveConversationRouter.resolve(
            message = message(target = GlobalProactiveTarget.CURRENT_CONVERSATION),
            conversations = listOf(source),
            autoCreateConversationsEnabled = true
        )

        assertEquals(GlobalProactiveRouteKind.SOURCE, route?.kind)
        assertEquals(source.id, route?.conversationId)
        assertFalse(route?.bindTopic ?: true)
    }

    @Test
    fun renamedAgentWorkspaceIsReusedThroughStableTopicOwnership() {
        val topicKey = GlobalProactiveConversationRouter.topicKey(TOPIC)
        val renamed = conversation(
            id = "topic-workspace",
            title = "A user-selected title",
            createdByAgent = true,
            globalTopicKey = topicKey
        )

        val route = GlobalProactiveConversationRouter.resolve(
            message = message(target = GlobalProactiveTarget.NEW_CONVERSATION),
            conversations = listOf(renamed),
            autoCreateConversationsEnabled = true
        )

        assertEquals(GlobalProactiveRouteKind.BOUND_TOPIC, route?.kind)
        assertEquals(renamed.id, route?.conversationId)
        assertFalse(route?.createConversation ?: true)
    }

    @Test
    fun strongestGraphWorkspaceWinsBeforeMostRecentlyUpdatedWorkspace() {
        val strongest = conversation("strongest", "Primary project", updatedAt = 10L)
        val newest = conversation("newest", "Secondary project", updatedAt = 100L)

        val route = GlobalProactiveConversationRouter.resolve(
            message = message(target = GlobalProactiveTarget.NEW_CONVERSATION),
            conversations = listOf(newest, strongest),
            relatedConversationIds = listOf(strongest.id, newest.id),
            autoCreateConversationsEnabled = true
        )

        assertEquals(GlobalProactiveRouteKind.RELATED_TOPIC, route?.kind)
        assertEquals(strongest.id, route?.conversationId)
        assertTrue(route?.bindTopic == true)
    }

    @Test
    fun privateOrPausedSourceCannotLeakIntoAnotherWorkspace() {
        val privateSource = conversation("source", "Private", privateMode = true)
        val publicTopic = conversation("topic", TOPIC, createdByAgent = true)

        val privateRoute = GlobalProactiveConversationRouter.resolve(
            message = message(target = GlobalProactiveTarget.NEW_CONVERSATION),
            conversations = listOf(privateSource, publicTopic),
            autoCreateConversationsEnabled = true
        )
        val pausedRoute = GlobalProactiveConversationRouter.resolve(
            message = message(target = GlobalProactiveTarget.NEW_CONVERSATION),
            conversations = listOf(privateSource.copy(privateMode = false, trackingPaused = true), publicTopic),
            autoCreateConversationsEnabled = true
        )

        assertNull(privateRoute)
        assertNull(pausedRoute)
    }

    @Test
    fun deletedExternalSourceCannotRecreateItsTopicWorkspace() {
        val route = GlobalProactiveConversationRouter.resolve(
            message = message(target = GlobalProactiveTarget.NEW_CONVERSATION),
            conversations = emptyList(),
            autoCreateConversationsEnabled = true,
            excludedConversationIds = setOf("source")
        )

        assertNull(route)
    }

    @Test
    fun unmatchedDurableTopicCreatesOneOwnedChildWorkspace() {
        val route = GlobalProactiveConversationRouter.resolve(
            message = message(target = GlobalProactiveTarget.NEW_CONVERSATION),
            conversations = listOf(conversation("source", "Current work")),
            autoCreateConversationsEnabled = true
        )

        assertEquals(GlobalProactiveRouteKind.CREATE_TOPIC, route?.kind)
        assertTrue(route?.createConversation == true)
        assertEquals("source", route?.parentConversationId)
        assertEquals(GlobalProactiveConversationRouter.topicKey(TOPIC), route?.topicKey)
    }

    @Test
    fun agentCreatedTopicProducesOneSourceWorkspaceNoticeContract() {
        val destination = conversation(
            id = "topic-workspace",
            title = "Runtime reliability",
            createdByAgent = true,
            parentConversationId = "source"
        )

        val notice = GlobalProactiveTopicNoticePolicy.create(
            message(target = GlobalProactiveTarget.NEW_CONVERSATION),
            destination
        )

        assertEquals("source", notice?.parentConversationId)
        assertEquals("topic-workspace", notice?.destinationConversationId)
        assertEquals("global-agent-topic-created:topic-workspace", notice?.dedupeKey)
        assertEquals("Open topic", notice?.actionLabel)
        assertTrue(notice?.text.orEmpty().contains("Runtime reliability"))
    }

    @Test
    fun sourceNoticeCannotBeCreatedForUserOwnedOrUnrelatedWorkspace() {
        val userOwned = conversation("user-topic", "User topic")
        val unrelated = conversation(
            id = "agent-topic",
            title = "Agent topic",
            createdByAgent = true,
            parentConversationId = "different-source"
        )

        assertNull(GlobalProactiveTopicNoticePolicy.create(
            message(target = GlobalProactiveTarget.NEW_CONVERSATION),
            userOwned
        ))
        assertNull(GlobalProactiveTopicNoticePolicy.create(
            message(target = GlobalProactiveTarget.NEW_CONVERSATION),
            unrelated
        ))
    }

    @Test
    fun disabledAutoCreationFallsBackToEligibleSource() {
        val source = conversation("source", "Current work")

        val route = GlobalProactiveConversationRouter.resolve(
            message = message(target = GlobalProactiveTarget.NEW_CONVERSATION),
            conversations = listOf(source),
            autoCreateConversationsEnabled = false
        )

        assertEquals(GlobalProactiveRouteKind.SOURCE_FALLBACK, route?.kind)
        assertEquals(source.id, route?.conversationId)
    }

    @Test
    fun externalConversationFallsBackToMostRecentWorkspaceWhenCreationIsDisabled() {
        val older = conversation("older", "Older workspace", updatedAt = 10L)
        val recent = conversation("recent", "Recent workspace", updatedAt = 20L)

        val route = GlobalProactiveConversationRouter.resolve(
            message = message(target = GlobalProactiveTarget.CURRENT_CONVERSATION)
                .copy(sourceConversationId = "contact:external"),
            conversations = listOf(older, recent),
            autoCreateConversationsEnabled = false
        )

        assertEquals(GlobalProactiveRouteKind.SOURCE_FALLBACK, route?.kind)
        assertEquals(recent.id, route?.conversationId)
    }

    @Test
    fun deliveryLeaseOnlyRecoversAfterExpiry() {
        val now = 10_000L
        val fresh = message().copy(
            status = GlobalProactiveMessageStatus.DELIVERING,
            deliveryLeaseExpiresAtMillis = now + 1_000L
        )
        val stale = fresh.copy(deliveryLeaseExpiresAtMillis = now)

        assertFalse(GlobalProactiveDeliveryPolicy.isRecoverable(fresh, now))
        assertTrue(GlobalProactiveDeliveryPolicy.isRecoverable(stale, now))
    }

    @Test
    fun budgetAndTopicCooldownAreRecheckedAtDeliveryTime() {
        val now = 2L * DAY_MILLIS
        val settings = GlobalAgentSettings(dailyMessageBudget = 2, topicCooldownMillis = 6L * HOUR_MILLIS)
        val profile = GlobalAgentAdaptiveProfile()
        val budgetFull = GlobalInterventionHistory(
            notificationTimestamps = listOf(now - 100L, now - 200L)
        )
        val topicCoolingDown = GlobalInterventionHistory(
            notificationTimestamps = listOf(now - DAY_MILLIS),
            lastTopicNotificationMillis = mapOf(GlobalAgentText.normalize(TOPIC) to now - HOUR_MILLIS)
        )

        assertFalse(GlobalProactiveDeliveryPolicy.canDeliver(message(), settings, profile, budgetFull, now))
        assertFalse(GlobalProactiveDeliveryPolicy.canDeliver(message(), settings, profile, topicCoolingDown, now))
        assertTrue(GlobalProactiveDeliveryPolicy.canDeliver(message().copy(urgent = true), settings, profile, budgetFull, now))
        assertTrue(GlobalProactiveDeliveryPolicy.canDeliver(
            message().copy(deliveryBudgetCounted = true),
            settings,
            profile,
            budgetFull,
            now
        ))
    }

    @Test
    fun digestSelectionDeliversOneBoundedBatchAndLeavesOverflowForLater() {
        val now = 100L * HOUR_MILLIS
        val messages = (1..7).map { index ->
            message(id = "message-$index", target = GlobalProactiveTarget.GLOBAL_DIGEST)
                .copy(createdAtMillis = now - HOUR_MILLIS + index)
        }

        val selected = GlobalProactiveDeliveryPolicy.digestBatch(
            messages = messages,
            settings = GlobalAgentSettings(dailyMessageBudget = 4),
            profile = GlobalAgentAdaptiveProfile(),
            history = GlobalInterventionHistory(),
            nowMillis = now,
            minimumItems = 3,
            maximumItems = 4,
            maximumWaitMillis = 12L * HOUR_MILLIS
        )

        assertEquals(listOf("message-1", "message-2", "message-3", "message-4"), selected.map { it.id })
        assertNotEquals(messages.map { it.id }.toSet(), selected.map { it.id }.toSet())
    }

    private fun message(
        id: String = "message",
        target: GlobalProactiveTarget = GlobalProactiveTarget.CURRENT_CONVERSATION
    ): GlobalProactiveMessage = GlobalProactiveMessage(
        id = id,
        sourceEventId = "event",
        sourceConversationId = "source",
        target = target,
        title = "Signal insight",
        content = "A material result is ready.",
        topic = TOPIC,
        urgent = false,
        createdAtMillis = 1L
    )

    private fun conversation(
        id: String,
        title: String,
        updatedAt: Long = 1L,
        privateMode: Boolean = false,
        trackingPaused: Boolean = false,
        createdByAgent: Boolean = false,
        globalTopicKey: String = "",
        parentConversationId: String = ""
    ): AgentConversation = AgentConversation(
        id = id,
        title = title,
        createdAt = 1L,
        updatedAt = updatedAt,
        privateMode = privateMode,
        trackingPaused = trackingPaused,
        createdByAgent = createdByAgent,
        globalTopicKey = globalTopicKey,
        parentConversationId = parentConversationId
    )

    companion object {
        private const val TOPIC = "SignalASI global autonomy"
        private const val HOUR_MILLIS = 60L * 60L * 1_000L
        private const val DAY_MILLIS = 24L * HOUR_MILLIS
    }
}
