package com.signalasi.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GlobalConversationMergeLifecycleTest {
    @Test
    fun rebindsWorldGraphAndActiveWorkWithoutCreatingNewEvidence() {
        val merge = mergeEvent()
        val evidence = GlobalEvidenceRef("evidence", conversationId = "child")
        val world = PersonalWorldModel(
            items = listOf(GlobalWorldItem(
                stableKey = "item",
                kind = GlobalWorldItemKind.GOAL,
                layer = GlobalWorldLayer.TOPIC,
                topic = "Runtime",
                value = "Finish the runtime",
                confidence = 0.9,
                conversationIds = setOf("child", "other"),
                evidenceProvenance = listOf(evidence)
            )),
            links = listOf(
                GlobalConversationLink(
                    leftConversationId = "child",
                    rightConversationId = "parent",
                    topic = "Runtime",
                    strength = 0.8,
                    evidenceProvenance = listOf(evidence)
                ),
                GlobalConversationLink(
                    leftConversationId = "child",
                    rightConversationId = "other",
                    topic = "Runtime",
                    strength = 0.7,
                    evidenceProvenance = listOf(evidence)
                )
            )
        )
        val graph = GlobalTopicProjectGraph(nodes = listOf(GlobalTopicNode(
            stableKey = "runtime",
            name = "Runtime",
            conversationIds = setOf("child"),
            evidenceProvenance = listOf(evidence)
        )))

        val reboundWorld = GlobalConversationMergeLifecycle.rebindWorld(world, merge)
        val reboundGraph = GlobalConversationMergeLifecycle.rebindTopicGraph(graph, merge)

        assertEquals(setOf("parent", "other"), reboundWorld.items.single().conversationIds)
        assertEquals("parent", reboundWorld.items.single().evidenceProvenance.single().conversationId)
        assertTrue(reboundWorld.links.none {
            it.leftConversationId == it.rightConversationId ||
                it.leftConversationId == "child" || it.rightConversationId == "child"
        })
        assertEquals(setOf("parent"), reboundGraph.nodes.single().conversationIds)
        assertTrue(merge.id in reboundWorld.processedEventIds)

        val research = GlobalResearchTask(
            sourceEventId = "event",
            sourceConversationId = "child",
            topic = "Runtime",
            question = "Check it",
            depth = GlobalResearchDepth.QUICK_FACT,
            preferredSources = emptyList()
        )
        val run = GlobalAutonomousRun(
            sourceCognitionTaskId = "cognition",
            sourceEventId = "event",
            sourceConversationId = "child",
            topic = "Runtime",
            goal = "Finish",
            actions = emptyList()
        )
        val proactive = GlobalProactiveMessage(
            sourceEventId = "event",
            sourceConversationId = "child",
            target = GlobalProactiveTarget.CURRENT_CONVERSATION,
            title = "Update",
            content = "Ready",
            topic = "Runtime",
            urgent = false,
            deliveryConversationId = "child",
            deliveredConversationId = "child"
        )
        val goal = GlobalLongHorizonGoal(
            stableKey = "goal",
            topic = "Runtime",
            title = "Finish",
            sourceConversationIds = setOf("child", "other")
        )

        assertEquals("parent", GlobalConversationMergeLifecycle.rebindResearchTasks(listOf(research), merge).single().sourceConversationId)
        assertEquals("parent", GlobalConversationMergeLifecycle.rebindAutonomousRuns(listOf(run), merge).single().sourceConversationId)
        val reboundMessage = GlobalConversationMergeLifecycle.rebindProactiveMessages(listOf(proactive), merge).single()
        assertEquals("parent", reboundMessage.sourceConversationId)
        assertEquals("parent", reboundMessage.deliveryConversationId)
        assertEquals("parent", reboundMessage.deliveredConversationId)
        assertEquals(setOf("parent", "other"), GlobalConversationMergeLifecycle.rebindLongHorizonGoals(listOf(goal), merge).single().sourceConversationIds)
    }

    @Test
    fun journalMovesExistingContextInsteadOfDuplicatingIt() {
        val original = GlobalConversationEvent(
            id = "message",
            type = GlobalConversationEventType.MESSAGE_CREATED,
            conversationId = "child",
            actor = GlobalConversationActor.USER,
            content = "Continue the runtime"
        )

        val journal = GlobalConversationContextJournalPolicy.apply(
            existing = listOf(original),
            incoming = listOf(mergeEvent())
        )

        val visible = GlobalConversationContextJournalPolicy.select(
            events = journal,
            conversationId = "parent",
            beforeOrAtMillis = Long.MAX_VALUE
        )
        assertEquals(1, visible.size)
        assertEquals("message", visible.single().id)
        assertEquals("parent", visible.single().conversationId)
        assertFalse(GlobalConversationContextJournalPolicy.render(journal).contains("conversation_merged"))

        val delayedSource = original.copy(id = "delayed", timestampMillis = 500L)
        val afterReplay = GlobalConversationContextJournalPolicy.apply(journal, listOf(delayedSource))
        assertTrue(GlobalConversationContextJournalPolicy.select(
            events = afterReplay,
            conversationId = "child",
            beforeOrAtMillis = Long.MAX_VALUE
        ).isEmpty())
    }

    private fun mergeEvent() = GlobalConversationEvent(
        id = "merge",
        type = GlobalConversationEventType.CONVERSATION_MERGED,
        conversationId = "parent",
        actor = GlobalConversationActor.SYSTEM,
        timestampMillis = 1_000L,
        metadata = mapOf(
            GlobalConversationMergeLifecycle.SOURCE_CONVERSATION_ID to "child",
            GlobalConversationMergeLifecycle.TARGET_CONVERSATION_ID to "parent"
        )
    )
}
