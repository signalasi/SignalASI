package com.signalasi.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GlobalPersonalAsiAcceptanceTest {
    @Test
    fun `durable cross conversation work becomes an owned topic with a source receipt`() {
        val firstEvent = event(
            id = "event-a",
            conversationId = "conversation-a",
            title = "Initial architecture",
            content = "SignalASI needs a reliable on-device Agent runtime."
        )
        val firstReduction = GlobalWorldModelReducer.reduce(
            PersonalWorldModel(),
            firstEvent,
            GlobalUnderstanding(
                eventId = firstEvent.id,
                topic = "On-device runtime",
                intent = "planning",
                goalCandidates = listOf("Build a reliable on-device Agent runtime")
            )
        )
        val secondEvent = event(
            id = "event-b",
            conversationId = "conversation-b",
            title = "Runtime compatibility",
            content = "Research the long-term compatibility risk and prepare a durable follow-up plan."
        )
        val understanding = GlobalUnderstanding(
            eventId = secondEvent.id,
            topic = "On-device runtime",
            intent = "research",
            goalCandidates = listOf("Build a reliable on-device Agent runtime"),
            riskCandidates = listOf("A platform compatibility change may block the runtime"),
            crossConversationIds = setOf("conversation-a"),
            complexity = 0.80,
            urgency = 0.20,
            novelty = 0.70,
            uncertainty = 0.0,
            externalResearchUseful = true,
            durableFollowUpUseful = true
        )
        val secondReduction = GlobalWorldModelReducer.reduce(
            firstReduction.world,
            secondEvent,
            understanding
        )
        val decision = GlobalInterventionPolicy.decide(
            secondEvent,
            understanding,
            secondReduction,
            GlobalInterventionHistory()
        )
        val message = GlobalProactiveMessageFactory.create(
            secondEvent,
            understanding,
            secondReduction,
            decision
        )

        assertEquals(GlobalInterventionMode.NEW_CONVERSATION, decision.mode)
        assertNotNull(message)
        assertEquals(GlobalProactiveTarget.NEW_CONVERSATION, message?.target)
        assertTrue("conversation-a" in understanding.crossConversationIds)
        assertEquals(2, secondReduction.world.items.first {
            it.kind == GlobalWorldItemKind.GOAL
        }.conversationIds.size)

        val route = GlobalProactiveConversationRouter.resolve(
            message = requireNotNull(message),
            conversations = listOf(conversation("conversation-b", "Runtime compatibility")),
            autoCreateConversationsEnabled = true
        )
        assertEquals(GlobalProactiveRouteKind.CREATE_TOPIC, route?.kind)
        assertEquals("conversation-b", route?.parentConversationId)

        val destination = conversation(
            id = "agent-topic",
            title = route?.title.orEmpty(),
            createdByAgent = true,
            parentConversationId = route?.parentConversationId.orEmpty(),
            globalTopicKey = route?.topicKey.orEmpty()
        )
        val notice = GlobalProactiveTopicNoticePolicy.create(message, destination)

        assertNotNull(notice)
        assertEquals("conversation-b", notice?.parentConversationId)
        assertEquals("agent-topic", notice?.destinationConversationId)
        assertEquals("Open topic", notice?.actionLabel)
    }

    private fun event(
        id: String,
        conversationId: String,
        title: String,
        content: String
    ) = GlobalConversationEvent(
        id = id,
        type = GlobalConversationEventType.MESSAGE_CREATED,
        conversationId = conversationId,
        actor = GlobalConversationActor.USER,
        timestampMillis = 1_000L,
        content = content,
        conversationTitle = title
    )

    private fun conversation(
        id: String,
        title: String,
        createdByAgent: Boolean = false,
        parentConversationId: String = "",
        globalTopicKey: String = ""
    ) = AgentConversation(
        id = id,
        title = title,
        createdAt = 1_000L,
        updatedAt = 1_000L,
        createdByAgent = createdByAgent,
        parentConversationId = parentConversationId,
        globalTopicKey = globalTopicKey
    )
}
