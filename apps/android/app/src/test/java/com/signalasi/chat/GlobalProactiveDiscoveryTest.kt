package com.signalasi.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GlobalProactiveDiscoveryTest {
    @Test
    fun `accumulated cross topic conflict becomes one discovery candidate`() {
        val world = PersonalWorldModel(items = listOf(
            worldItem(
                stableKey = "decision-a",
                value = "Bundle every model in the APK",
                conversationId = "conversation-a",
                eventId = "event-a",
                status = GlobalWorldItemStatus.CONFLICTED,
                conflictGroupId = "packaging-conflict",
                kind = GlobalWorldItemKind.DECISION
            ),
            worldItem(
                stableKey = "decision-b",
                value = "Download large models after installation",
                conversationId = "conversation-b",
                eventId = "event-b",
                status = GlobalWorldItemStatus.CONFLICTED,
                conflictGroupId = "packaging-conflict",
                kind = GlobalWorldItemKind.DECISION
            )
        ))

        val candidates = GlobalProactiveDiscoveryPolicy.scan(world, emptyList(), emptySet(), 10_000L)

        assertEquals(1, candidates.size)
        assertEquals(GlobalDiscoveryKind.CROSS_TOPIC_CONFLICT, candidates.single().kind)
        assertEquals(setOf("conversation-a", "conversation-b"), candidates.single().sourceConversationIds)
        assertEquals(setOf("event-a", "event-b"), candidates.single().causalEventIds)
    }

    @Test
    fun `local only and deleted conversation evidence never enters discovery`() {
        val localOnly = worldItem(
            stableKey = "private-risk",
            value = "Private risk",
            conversationId = "private",
            eventId = "private-event",
            kind = GlobalWorldItemKind.RISK,
            visibility = GlobalWorldContextVisibility.LOCAL_ONLY
        )
        val deleted = worldItem(
            stableKey = "deleted-risk",
            value = "Deleted risk",
            conversationId = "deleted",
            eventId = "deleted-event",
            kind = GlobalWorldItemKind.RISK
        )

        val candidates = GlobalProactiveDiscoveryPolicy.scan(
            PersonalWorldModel(items = listOf(localOnly, deleted)),
            emptyList(),
            setOf("deleted"),
            10_000L
        )

        assertTrue(candidates.isEmpty())
    }

    @Test
    fun `repeated risk and cross conversation opportunity cross discovery threshold`() {
        val risk = worldItem(
            stableKey = "risk",
            value = "A platform change may break native libraries",
            conversationId = "conversation-a",
            eventId = "risk-a",
            kind = GlobalWorldItemKind.RISK,
            confidence = 0.76,
            evidenceCount = 2
        ).copy(evidenceEventIds = listOf("risk-a", "risk-b"))
        val opportunity = worldItem(
            stableKey = "opportunity",
            value = "Reuse one runtime across several topic workspaces",
            conversationId = "conversation-a",
            eventId = "opportunity-a",
            kind = GlobalWorldItemKind.OPPORTUNITY,
            confidence = 0.80,
            evidenceCount = 2
        ).copy(
            conversationIds = setOf("conversation-a", "conversation-b"),
            evidenceEventIds = listOf("opportunity-a", "opportunity-b")
        )

        val kinds = GlobalProactiveDiscoveryPolicy.scan(
            PersonalWorldModel(items = listOf(risk, opportunity)),
            emptyList(),
            emptySet(),
            10_000L
        ).map(GlobalDiscoveryCandidate::kind).toSet()

        assertEquals(setOf(GlobalDiscoveryKind.MATERIAL_RISK, GlobalDiscoveryKind.HIGH_VALUE_OPPORTUNITY), kinds)
    }

    @Test
    fun `ordinary evidence across topic workspaces creates a proactive synthesis`() {
        val goal = worldItem(
            stableKey = "goal",
            value = "Keep the Android Agent runtime available offline",
            conversationId = "conversation-a",
            eventId = "goal-event",
            kind = GlobalWorldItemKind.GOAL
        )
        val decision = worldItem(
            stableKey = "decision",
            value = "Bundle the base Linux image in the APK",
            conversationId = "conversation-b",
            eventId = "decision-event",
            kind = GlobalWorldItemKind.DECISION
        )
        val fact = worldItem(
            stableKey = "fact",
            value = "Large runtime payloads increase installation size",
            conversationId = "conversation-b",
            eventId = "fact-event",
            kind = GlobalWorldItemKind.FACT
        )
        val graph = GlobalTopicProjectGraph(nodes = listOf(GlobalTopicNode(
            stableKey = "project-runtime",
            name = "Android Agent runtime",
            kind = GlobalTopicNodeKind.PROJECT,
            conversationIds = setOf("conversation-a", "conversation-b"),
            worldItemIds = setOf(goal.id, decision.id, fact.id),
            evidenceEventIds = listOf("goal-event", "decision-event", "fact-event"),
            confidence = 0.86
        )))

        val candidate = GlobalProactiveDiscoveryPolicy.scan(
            world = PersonalWorldModel(items = listOf(goal, decision, fact)),
            goals = emptyList(),
            excludedConversationIds = emptySet(),
            nowMillis = 10_000L,
            topicGraph = graph
        ).single()

        assertEquals(GlobalDiscoveryKind.CROSS_TOPIC_SYNTHESIS, candidate.kind)
        assertEquals(setOf("conversation-a", "conversation-b"), candidate.sourceConversationIds)
        assertTrue("installation size" in candidate.summary)
    }

    @Test
    fun `blocked durable goal becomes a review task without a new chat event`() {
        val source = worldItem(
            stableKey = "goal-world",
            value = "Ship the on-device runtime",
            conversationId = "runtime-topic",
            eventId = "goal-event",
            kind = GlobalWorldItemKind.GOAL,
            confidence = 0.90
        )
        val goal = GlobalLongHorizonGoal(
            id = "goal-id",
            stableKey = "goal-stable",
            topic = "On-device runtime",
            title = "Ship the on-device runtime",
            status = GlobalLongHorizonGoalStatus.BLOCKED,
            sourceConversationIds = setOf("runtime-topic"),
            sourceEventIds = listOf("goal-event"),
            blocker = "The runtime package is unavailable",
            createdAtMillis = 1_000L,
            updatedAtMillis = 2_000L
        )

        val candidate = GlobalProactiveDiscoveryPolicy.scan(
            PersonalWorldModel(items = listOf(source)),
            listOf(goal),
            emptySet(),
            100_000L
        ).single()
        val task = GlobalProactiveDiscoveryPolicy.task(candidate, 100_000L)

        assertEquals(GlobalDiscoveryKind.STALLED_GOAL, candidate.kind)
        assertEquals("goal-id", task.longHorizonGoalId)
        assertEquals("proactive_world_review", task.baselineUnderstanding.intent)
    }

    @Test
    fun `unchanged completed finding is not queued twice`() {
        val candidate = candidate("fingerprint-a")
        val task = GlobalProactiveDiscoveryPolicy.task(candidate, 1_000L).copy(
            status = GlobalCognitionTaskStatus.COMPLETED,
            updatedAtMillis = 2_000L
        )
        val state = GlobalProactiveDiscoveryState(records = listOf(
            GlobalDiscoveryRecord(
                candidate.stableKey,
                candidate.fingerprint,
                task.id,
                1_000L
            )
        ))

        val selected = GlobalProactiveDiscoveryPolicy.selectForDeliberation(
            listOf(candidate),
            state,
            listOf(task),
            GlobalAgentSettings(),
            100_000_000L
        )

        assertTrue(selected.isEmpty())
    }

    @Test
    fun `materially changed evidence can be reconsidered after cooldown`() {
        val previous = candidate("fingerprint-a")
        val changed = candidate("fingerprint-b")
        val state = GlobalProactiveDiscoveryState(records = listOf(
            GlobalDiscoveryRecord(
                previous.stableKey,
                previous.fingerprint,
                GlobalProactiveDiscoveryPolicy.cognitionTaskId(previous),
                1_000L
            )
        ))

        val selected = GlobalProactiveDiscoveryPolicy.selectForDeliberation(
            listOf(changed),
            state,
            emptyList(),
            GlobalAgentSettings(discoveryIntervalMillis = 60L * 60L * 1_000L),
            2L * 60L * 60L * 1_000L
        )

        assertEquals(listOf(changed), selected)
    }

    @Test
    fun `daily discovery budget bounds autonomous deliberation`() {
        val now = 100_000_000L
        val state = GlobalProactiveDiscoveryState(
            recentEmissionTimestamps = listOf(now - 1_000L, now - 2_000L, now - 3_000L)
        )

        val selected = GlobalProactiveDiscoveryPolicy.selectForDeliberation(
            listOf(candidate("new")),
            state,
            emptyList(),
            GlobalAgentSettings(dailyDiscoveryTaskBudget = 3),
            now
        )

        assertTrue(selected.isEmpty())
    }

    @Test
    fun `expired scan lease is recoverable while active lease is exclusive`() {
        val state = GlobalProactiveDiscoveryState(
            nextScanAtMillis = 10L,
            scanLeaseExpiresAtMillis = 100L
        )

        assertFalse(GlobalProactiveDiscoveryPolicy.canClaim(state, 99L))
        assertTrue(GlobalProactiveDiscoveryPolicy.canClaim(state, 100L))
    }

    @Test
    fun `discovery state survives backup codec round trip`() {
        val state = GlobalProactiveDiscoveryState(
            nextScanAtMillis = 20L,
            scanLeaseExpiresAtMillis = 30L,
            lastStartedAtMillis = 10L,
            lastCompletedAtMillis = 15L,
            scanSequence = 4L,
            recentEmissionTimestamps = listOf(12L),
            records = listOf(GlobalDiscoveryRecord("key", "fingerprint", "task", 12L)),
            lastError = "temporary"
        )

        val restored = GlobalProactiveDiscoveryCodec.decode(
            GlobalProactiveDiscoveryCodec.encode(state).toString()
        )

        assertEquals(state, restored)
    }

    @Test
    fun `deleting any contributing conversation invalidates discovery evidence`() {
        val candidate = candidate("fingerprint").copy(
            sourceConversationIds = setOf("conversation-a", "conversation-b"),
            causalEventIds = setOf("event-a", "event-b")
        )
        val task = GlobalProactiveDiscoveryPolicy.task(candidate, 1_000L)

        val evidence = GlobalAgentEvidenceLifecyclePolicy.evidenceIdsForConversation(
            conversationId = "conversation-b",
            cognitionTasks = listOf(task),
            researchTasks = emptyList(),
            autonomousRuns = emptyList(),
            proactiveMessages = emptyList(),
            longHorizonGoals = emptyList()
        )

        assertEquals(setOf("event-a", "event-b"), evidence)
    }

    @Test
    fun `empty periodic inference remains silent while a material finding may surface`() {
        val source = GlobalProactiveDiscoveryPolicy.task(candidate("fingerprint"), 1_000L)

        assertFalse(GlobalProactiveDiscoveryPolicy.shouldSurfaceResult(source.copy(
            result = GlobalModelUnderstanding(topic = "SignalASI")
        )))
        assertTrue(GlobalProactiveDiscoveryPolicy.shouldSurfaceResult(source.copy(
            result = GlobalModelUnderstanding(
                topic = "SignalASI",
                risks = listOf("A newly validated risk")
            )
        )))
    }

    private fun worldItem(
        stableKey: String,
        value: String,
        conversationId: String,
        eventId: String,
        kind: GlobalWorldItemKind,
        status: GlobalWorldItemStatus = GlobalWorldItemStatus.ACTIVE,
        conflictGroupId: String = "",
        visibility: GlobalWorldContextVisibility = GlobalWorldContextVisibility.SHAREABLE,
        confidence: Double = 0.90,
        evidenceCount: Int = 1
    ) = GlobalWorldItem(
        stableKey = stableKey,
        kind = kind,
        layer = GlobalWorldLayer.TOPIC,
        topic = "SignalASI",
        value = value,
        confidence = confidence,
        contextVisibility = visibility,
        evidenceCount = evidenceCount,
        conversationIds = setOf(conversationId),
        evidenceEventIds = listOf(eventId),
        status = status,
        conflictGroupId = conflictGroupId
    )

    private fun candidate(fingerprint: String) = GlobalDiscoveryCandidate(
        stableKey = "risk:key",
        fingerprint = fingerprint,
        kind = GlobalDiscoveryKind.MATERIAL_RISK,
        topic = "SignalASI",
        summary = "A material risk needs review",
        sourceConversationIds = setOf("conversation-a"),
        causalEventIds = setOf("event-a"),
        score = 0.85,
        urgency = 0.80,
        externalResearchUseful = true
    )
}
