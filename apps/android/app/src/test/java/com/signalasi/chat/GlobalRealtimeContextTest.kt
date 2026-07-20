package com.signalasi.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GlobalRealtimeContextTest {
    @Test
    fun `projection keeps live and recent state while excluding stale or deleted work`() {
        val now = 10L * HOUR
        val liveCognition = cognition("cognition-live", "conversation-a", "Runtime architecture", now - 1_000L)
        val deletedResearch = research("research-deleted", "conversation-deleted", "Deleted topic", now - 2_000L)
        val recentRun = run(
            id = "run-recent",
            conversationId = "conversation-a",
            goal = "Build the Android runtime",
            status = GlobalAutonomousRunStatus.COMPLETED,
            updatedAtMillis = now - HOUR
        )
        val staleRun = run(
            id = "run-stale",
            conversationId = "conversation-a",
            goal = "Old completed work",
            status = GlobalAutonomousRunStatus.COMPLETED,
            updatedAtMillis = now - 7L * HOUR
        )
        val invalidatedRun = run(
            id = "run-invalidated",
            conversationId = "conversation-a",
            goal = "Retracted work",
            status = GlobalAutonomousRunStatus.PAUSED,
            updatedAtMillis = now
        ).copy(lastError = "Source evidence was revised or deleted")
        val blockedGoal = goal("goal-blocked", "conversation-a", GlobalLongHorizonGoalStatus.BLOCKED, now)

        val projected = GlobalRealtimeContextPolicy.project(
            cognitionTasks = listOf(liveCognition),
            researchTasks = listOf(deletedResearch),
            autonomousRuns = listOf(recentRun, staleRun, invalidatedRun),
            longHorizonGoals = listOf(blockedGoal),
            excludedConversationIds = setOf("conversation-deleted"),
            nowMillis = now
        )

        assertEquals(
            setOf("cognition:cognition-live", "run:run-recent", "goal:goal-blocked"),
            projected.map(GlobalRealtimeContextItem::key).toSet()
        )
        assertTrue(projected.first { it.key == "goal:goal-blocked" }.needsAttention)
    }

    @Test
    fun `selection is conversation scoped and query relevant`() {
        val now = 20L * HOUR
        val items = listOf(
            item("same", "Android runtime", "SignalASI", setOf("conversation-a"), now),
            item("related", "Android package verification", "SignalASI", setOf("conversation-b"), now - 1_000L),
            item("unrelated", "Travel booking", "Holiday", setOf("conversation-c"), now - 2_000L)
        )

        val selected = GlobalRealtimeContextPolicy.select(
            items = items,
            query = "Check the Android runtime status",
            currentConversationId = "conversation-a",
            nowMillis = now
        )

        assertEquals(listOf("same", "related"), selected.map(GlobalRealtimeContextItem::key))
    }

    @Test
    fun `global status query can surface active work from other conversations`() {
        val now = 30L * HOUR
        val items = listOf(
            item("one", "Android build", "SignalASI", setOf("conversation-a"), now),
            item("two", "Research memory design", "Memory", setOf("conversation-b"), now - 1_000L)
        )

        val selected = GlobalRealtimeContextPolicy.select(
            items = items,
            query = "Show all tasks and current status",
            currentConversationId = "conversation-c",
            nowMillis = now
        )

        assertEquals(setOf("one", "two"), selected.map(GlobalRealtimeContextItem::key).toSet())
    }

    @Test
    fun `current work can be excluded from related realtime context`() {
        val now = 40L * HOUR
        val items = listOf(
            item("run:current", "Current run", "SignalASI", setOf("conversation-a"), now),
            item("run:other", "Related run", "SignalASI", setOf("conversation-a"), now - 1_000L)
        )

        val selected = GlobalRealtimeContextPolicy.select(
            items = items,
            query = "Continue SignalASI",
            currentConversationId = "conversation-a",
            excludedKeys = setOf("run:current"),
            nowMillis = now
        )

        assertEquals(listOf("run:other"), selected.map(GlobalRealtimeContextItem::key))
    }

    @Test
    fun `render exposes bounded host state without internal keys or secrets`() {
        val rendered = GlobalRealtimeContextPolicy.render(
            listOf(GlobalRealtimeContextItem(
                key = "run:internal-uuid",
                kind = GlobalRealtimeContextKind.AUTONOMOUS_RUN,
                status = "waiting_for_resource",
                title = "Build release with api_key=top-secret",
                topic = "SignalASI at https://internal.example/path",
                detail = "workspace=C:\\Users\\agent\\private; cache=/data/user/0/com.signalasi.chat; steps=1/3"
            )),
            maximumCharacters = 500
        )

        assertTrue(rendered.startsWith("Host-observed realtime state"))
        assertTrue(rendered.contains("[autonomous_run/waiting_for_resource]"))
        assertTrue(rendered.contains("api_key=<redacted>"))
        assertTrue(rendered.contains("<endpoint>"))
        assertTrue(rendered.contains("<path>"))
        assertFalse(rendered.contains("com.signalasi.chat"))
        assertFalse(rendered.contains("top-secret"))
        assertFalse(rendered.contains("internal-uuid"))
        assertTrue(rendered.length <= 500)
    }

    @Test
    fun `run projection reports progress but not resource routing identifiers`() {
        val now = 50L * HOUR
        val action = GlobalAutonomousAction(
            id = "action-1",
            kind = GlobalAutonomousActionKind.ANALYZE,
            goal = "Inspect the build",
            status = GlobalAutonomousActionStatus.RUNNING,
            resourceId = "codex-secret-route"
        )
        val source = run(
            id = "run-1",
            conversationId = "conversation-a",
            goal = "Verify SignalASI",
            status = GlobalAutonomousRunStatus.RUNNING,
            updatedAtMillis = now,
            actions = listOf(action)
        )

        val rendered = GlobalRealtimeContextPolicy.build(
            cognitionTasks = emptyList(),
            researchTasks = emptyList(),
            autonomousRuns = listOf(source),
            longHorizonGoals = emptyList(),
            query = "SignalASI status",
            currentConversationId = "conversation-a",
            nowMillis = now
        )

        assertTrue(rendered.contains("steps=0/1"))
        assertTrue(rendered.contains("running=Inspect the build"))
        assertFalse(rendered.contains("codex-secret-route"))
        assertFalse(rendered.contains("run-1"))
        assertFalse(rendered.contains("action-1"))
    }

    private fun cognition(id: String, conversationId: String, topic: String, updatedAtMillis: Long) =
        GlobalCognitionTask(
            id = id,
            sourceEvent = GlobalConversationEvent(
                id = "event-$id",
                type = GlobalConversationEventType.MESSAGE_CREATED,
                conversationId = conversationId,
                actor = GlobalConversationActor.USER,
                content = topic,
                conversationTitle = topic,
                timestampMillis = updatedAtMillis
            ),
            baselineUnderstanding = GlobalUnderstanding(
                eventId = "event-$id",
                topic = topic,
                intent = "status_tracking"
            ),
            updatedAtMillis = updatedAtMillis
        )

    private fun research(id: String, conversationId: String, question: String, updatedAtMillis: Long) =
        GlobalResearchTask(
            id = id,
            sourceEventId = "event-$id",
            sourceConversationId = conversationId,
            topic = question,
            question = question,
            depth = GlobalResearchDepth.DEEP_RESEARCH,
            preferredSources = listOf("official"),
            updatedAtMillis = updatedAtMillis
        )

    private fun run(
        id: String,
        conversationId: String,
        goal: String,
        status: GlobalAutonomousRunStatus,
        updatedAtMillis: Long,
        actions: List<GlobalAutonomousAction> = emptyList()
    ) = GlobalAutonomousRun(
        id = id,
        sourceCognitionTaskId = "cognition-$id",
        sourceEventId = "event-$id",
        sourceConversationId = conversationId,
        topic = "SignalASI",
        goal = goal,
        actions = actions,
        status = status,
        updatedAtMillis = updatedAtMillis
    )

    private fun goal(
        id: String,
        conversationId: String,
        status: GlobalLongHorizonGoalStatus,
        updatedAtMillis: Long
    ) = GlobalLongHorizonGoal(
        id = id,
        stableKey = "stable-$id",
        topic = "SignalASI",
        title = "Complete the super-agent runtime",
        status = status,
        sourceConversationIds = setOf(conversationId),
        blocker = "Waiting for verified evidence",
        updatedAtMillis = updatedAtMillis
    )

    private fun item(
        key: String,
        title: String,
        topic: String,
        conversationIds: Set<String>,
        updatedAtMillis: Long
    ) = GlobalRealtimeContextItem(
        key = key,
        kind = GlobalRealtimeContextKind.AUTONOMOUS_RUN,
        status = "running",
        title = title,
        topic = topic,
        conversationIds = conversationIds,
        updatedAtMillis = updatedAtMillis
    )

    private companion object {
        const val HOUR = 60L * 60L * 1_000L
    }
}
