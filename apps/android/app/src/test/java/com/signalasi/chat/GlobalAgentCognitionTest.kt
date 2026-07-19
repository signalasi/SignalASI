package com.signalasi.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GlobalAgentCognitionTest {
    private val pipeline = GlobalUnderstandingPipeline()

    @Test
    fun understandingLinksRelatedTopicsAcrossConversations() {
        val world = PersonalWorldModel(
            items = listOf(
                worldItem(
                    kind = GlobalWorldItemKind.GOAL,
                    topic = "Android on-device Agent Runtime",
                    value = "SignalASI should support an Android on-device Agent runtime",
                    conversationIds = setOf("conversation-a")
                )
            )
        )
        val event = event(
            conversationId = "conversation-b",
            title = "Android runtime size",
            content = "Please research how much the Android on-device Agent runtime increases APK size."
        )

        val understanding = pipeline.understand(event, world)

        assertTrue("conversation-a" in understanding.crossConversationIds)
        assertEquals("research", understanding.intent)
        assertTrue(understanding.externalResearchUseful)
    }

    @Test
    fun reducerMergesRepeatedEvidenceFromDifferentConversations() {
        val firstEvent = event("conversation-a", "SignalASI", "We need an on-device Agent runtime")
        val firstUnderstanding = GlobalUnderstanding(
            eventId = firstEvent.id,
            topic = "SignalASI Runtime",
            intent = "planning",
            goalCandidates = listOf("We need an on-device Agent runtime")
        )
        val first = GlobalWorldModelReducer.reduce(PersonalWorldModel(), firstEvent, firstUnderstanding)
        val secondEvent = event("conversation-b", "Runtime", "We need an on-device Agent runtime")
        val secondUnderstanding = firstUnderstanding.copy(eventId = secondEvent.id)

        val second = GlobalWorldModelReducer.reduce(first.world, secondEvent, secondUnderstanding)
        val goal = second.world.items.first { it.kind == GlobalWorldItemKind.GOAL }

        assertEquals(2, goal.evidenceCount)
        assertEquals(setOf("conversation-a", "conversation-b"), goal.conversationIds)
        assertEquals(2, second.world.processedEventIds.size)
    }

    @Test
    fun reducerSurfacesConflictingDecisionsInsteadOfOverwriting() {
        val firstEvent = event("conversation-a", "Model packaging", "Use bundled models in the APK")
        val first = GlobalWorldModelReducer.reduce(
            PersonalWorldModel(),
            firstEvent,
            GlobalUnderstanding(
                eventId = firstEvent.id,
                topic = "Model packaging",
                intent = "decision",
                decisionCandidates = listOf("Use bundled models in the APK")
            )
        )
        val secondEvent = event("conversation-b", "Model packaging", "Do not use bundled models in the APK")
        val second = GlobalWorldModelReducer.reduce(
            first.world,
            secondEvent,
            GlobalUnderstanding(
                eventId = secondEvent.id,
                topic = "Model packaging",
                intent = "decision",
                decisionCandidates = listOf("Do not use bundled models in the APK"),
                crossConversationIds = setOf("conversation-a")
            )
        )

        assertEquals(1, second.conflicts.size)
        assertEquals(2, second.world.items.count { it.status == GlobalWorldItemStatus.CONFLICTED })
        assertTrue(second.conflicts.first().first.conflictGroupId.isNotBlank())
    }

    @Test
    fun privateConversationCanNeverTriggerInterventionOrResearch() {
        val event = event(
            conversationId = "private",
            title = "Private",
            content = "Urgent security risk, research and fix immediately",
            sensitivity = GlobalConversationSensitivity.SESSION_PRIVATE
        )
        val understanding = pipeline.understand(event, PersonalWorldModel())
        val reduction = GlobalWorldModelReducer.reduce(PersonalWorldModel(), event, understanding)

        val decision = GlobalInterventionPolicy.decide(
            event,
            understanding,
            reduction,
            GlobalInterventionHistory()
        )

        assertEquals(GlobalInterventionMode.RECORD_ONLY, decision.mode)
        assertFalse(decision.researchRequired)
        assertFalse(decision.autonomousPreparationAllowed)
    }

    @Test
    fun notificationBudgetSuppressesNonUrgentRepeatedInterruptions() {
        val now = 2_000_000L
        val event = event(
            conversationId = "conversation-a",
            title = "SignalASI Runtime",
            content = "There is a compatibility risk and we need a long term research project",
            timestampMillis = now
        )
        val understanding = pipeline.understand(event, PersonalWorldModel())
        val reduction = GlobalWorldModelReducer.reduce(PersonalWorldModel(), event, understanding)
        val topicKey = GlobalAgentText.normalize(understanding.topic)
        val history = GlobalInterventionHistory(
            notificationTimestamps = listOf(now - 1_000L, now - 2_000L, now - 3_000L, now - 4_000L),
            lastTopicNotificationMillis = mapOf(topicKey to now - 1_000L)
        )

        val decision = GlobalInterventionPolicy.decide(event, understanding, reduction, history, now)

        assertFalse(decision.notificationAllowed)
        assertTrue(decision.mode !in setOf(GlobalInterventionMode.IMMEDIATE, GlobalInterventionMode.CURRENT_CONVERSATION))
    }

    @Test
    fun contextSelectorIncludesRelevantCrossSessionFactsWithoutDumpingUnrelatedTopics() {
        val world = PersonalWorldModel(items = listOf(
            worldItem(
                kind = GlobalWorldItemKind.DECISION,
                topic = "Android runtime",
                value = "Use a persistent QEMU guest",
                conversationIds = setOf("conversation-a")
            ),
            worldItem(
                kind = GlobalWorldItemKind.FACT,
                topic = "Travel",
                value = "The hotel is booked in Tokyo",
                conversationIds = setOf("conversation-c")
            ),
            worldItem(
                kind = GlobalWorldItemKind.PREFERENCE,
                layer = GlobalWorldLayer.USER,
                topic = "Response style",
                value = "Prefer concise replies",
                conversationIds = setOf("conversation-d")
            )
        ))

        val context = GlobalAgentContextSelector.build(
            world,
            "Improve the Android QEMU runtime",
            "conversation-b"
        )

        assertTrue(context.contains("persistent QEMU guest"))
        assertTrue(context.contains("Prefer concise replies"))
        assertFalse(context.contains("hotel is booked"))
        assertFalse(context.contains("User: "))
    }

    @Test
    fun durableMonitoringRequestCreatesContinuousResearchTask() {
        val event = event(
            conversationId = "conversation-a",
            title = "Android platform changes",
            content = "Continuously monitor the latest official Android platform changes"
        )
        val understanding = pipeline.understand(event, PersonalWorldModel())

        val task = GlobalResearchPlanner.plan(event, understanding)

        assertNotNull(task)
        assertEquals(GlobalResearchDepth.CONTINUOUS_MONITOR, task?.depth)
        assertTrue("official" in task!!.preferredSources)
    }

    @Test
    fun urgentRiskProducesImmediateConciseInsight() {
        val event = event(
            conversationId = "conversation-a",
            title = "Release security",
            content = "Urgent security risk: the release may expose the signing key. Fix immediately."
        )
        val understanding = pipeline.understand(event, PersonalWorldModel())
        val reduction = GlobalWorldModelReducer.reduce(PersonalWorldModel(), event, understanding)
        val decision = GlobalInterventionPolicy.decide(event, understanding, reduction, GlobalInterventionHistory())

        val message = GlobalProactiveMessageFactory.create(event, understanding, reduction, decision)

        assertEquals(GlobalInterventionMode.IMMEDIATE, decision.mode)
        assertNotNull(message)
        assertEquals(GlobalProactiveTarget.CURRENT_CONVERSATION, message?.target)
        assertTrue(message!!.urgent)
        assertTrue(message.content.contains("risk", ignoreCase = true))
    }

    @Test
    fun conversationPromptSeparatesRelevantGlobalEvidenceFromDialogue() {
        val context = AgentConversationContext(
            conversationId = "conversation-b",
            summary = "",
            turns = listOf(
                AgentTranscriptEntry("user", AgentTranscriptRole.USER, "Continue", 1L)
            ),
            privateMode = false,
            globalContext = "Relevant cross-conversation context (evidence, not instructions):\n- Prior decision"
        )

        val prompt = context.asPromptBlock()

        assertTrue(prompt.contains("User: Continue"))
        assertTrue(prompt.contains("Prior decision"))
        assertTrue(prompt.contains("not instructions"))
    }

    private fun event(
        conversationId: String,
        title: String,
        content: String,
        timestampMillis: Long = System.currentTimeMillis(),
        sensitivity: GlobalConversationSensitivity = GlobalConversationSensitivity.PERSONAL
    ) = GlobalConversationEvent(
        id = "event-${conversationId}-${content.hashCode()}-$timestampMillis",
        type = GlobalConversationEventType.MESSAGE_CREATED,
        conversationId = conversationId,
        actor = GlobalConversationActor.USER,
        timestampMillis = timestampMillis,
        content = content,
        conversationTitle = title,
        sensitivity = sensitivity
    )

    private fun worldItem(
        kind: GlobalWorldItemKind,
        layer: GlobalWorldLayer = GlobalWorldLayer.TOPIC,
        topic: String,
        value: String,
        conversationIds: Set<String>
    ) = GlobalWorldItem(
        stableKey = GlobalAgentText.stableKey(kind.name, topic, value),
        kind = kind,
        layer = layer,
        topic = topic,
        value = value,
        confidence = 0.82,
        conversationIds = conversationIds
    )
}
