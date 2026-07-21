package com.signalasi.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GlobalMemoryEvolutionTest {
    @Test
    fun explicitReplacementSupersedesCurrentState() {
        val previous = item(
            id = "old",
            kind = GlobalWorldItemKind.STATE,
            topic = "Screen understanding",
            value = "Screen understanding is enabled",
            eventId = "event-old"
        )
        val incoming = item(
            id = "new",
            kind = GlobalWorldItemKind.STATE,
            topic = "Screen understanding",
            value = "Screen understanding has been removed",
            eventId = "event-new"
        )
        val event = event("event-new", "Screen understanding has been removed")
        val result = GlobalMemoryEvolutionPolicy.evolve(
            worldBefore = PersonalWorldModel(items = listOf(previous)),
            reduction = GlobalWorldReduction(
                world = PersonalWorldModel(items = listOf(previous, incoming), processedEventIds = listOf(event.id)),
                changedItems = listOf(incoming),
                conflicts = emptyList()
            ),
            inbox = GlobalMemoryInbox(),
            event = event,
            understanding = understanding(event, "Screen understanding")
        )

        assertEquals(GlobalWorldItemStatus.SUPERSEDED, result.reduction.world.items.first { it.id == "old" }.status)
        assertEquals(GlobalWorldItemStatus.ACTIVE, result.reduction.world.items.first { it.id == "new" }.status)
        assertEquals(GlobalMemoryTemporalState.CURRENT, result.candidates.single().temporalState)
    }

    @Test
    fun preferenceWaitsInInboxUntilApproved() {
        val event = event(
            id = "preference-event",
            content = "Prefer concise responses",
            metadata = mapOf("memory_kind" to AgentMemoryKind.PREFERENCE.name)
        )
        val preference = item(
            id = "preference",
            kind = GlobalWorldItemKind.PREFERENCE,
            layer = GlobalWorldLayer.USER,
            topic = "Response style",
            value = "Prefer concise responses",
            eventId = event.id
        )
        val result = GlobalMemoryEvolutionPolicy.evolve(
            PersonalWorldModel(),
            reduction(event, preference),
            GlobalMemoryInbox(),
            event,
            understanding(event, "Response style")
        )

        assertTrue(result.reduction.world.items.isEmpty())
        val pending = result.inbox.pending().single()
        assertEquals(GlobalMemoryCandidateStatus.PENDING_REVIEW, pending.status)
        val (approvedWorld, approvedInbox) = GlobalMemoryEvolutionPolicy.approve(
            result.reduction.world,
            result.inbox,
            pending.id,
            nowMillis = 3_000L
        )
        assertEquals("Prefer concise responses", approvedWorld.items.single().value)
        assertEquals(GlobalMemoryCandidateStatus.APPROVED, approvedInbox.candidates.single().status)
    }

    @Test
    fun privateCandidateNeverPersistsRawContent() {
        val event = event(
            id = "private-event",
            content = "My private account secret must not persist",
            sensitivity = GlobalConversationSensitivity.SESSION_PRIVATE
        )
        val privateItem = item(
            id = "private",
            kind = GlobalWorldItemKind.FACT,
            topic = "Private account",
            value = event.content,
            eventId = event.id
        )
        val result = GlobalMemoryEvolutionPolicy.evolve(
            PersonalWorldModel(),
            reduction(event, privateItem),
            GlobalMemoryInbox(),
            event,
            understanding(event, "Private account")
        )

        assertTrue(result.reduction.world.items.isEmpty())
        assertEquals("", result.candidates.single().item.value)
        assertFalse(result.candidates.single().item.topic.contains("account", ignoreCase = true))
        assertEquals(GlobalMemoryCandidateRisk.PRIVATE_BLOCKED, result.candidates.single().risk)
    }

    @Test
    fun conflictingCandidateDoesNotMutateAcceptedWorld() {
        val previous = item(
            id = "accepted",
            kind = GlobalWorldItemKind.DECISION,
            topic = "Model packaging",
            value = "Bundle the runtime model",
            eventId = "event-old"
        )
        val incoming = item(
            id = "conflict",
            kind = GlobalWorldItemKind.DECISION,
            topic = "Model packaging",
            value = "Do not bundle the runtime model",
            eventId = "event-new",
            status = GlobalWorldItemStatus.CONFLICTED
        )
        val conflictedPrevious = previous.copy(status = GlobalWorldItemStatus.CONFLICTED, conflictGroupId = "group")
        val conflictedIncoming = incoming.copy(conflictGroupId = "group")
        val event = event("event-new", "Do not bundle the runtime model")
        val result = GlobalMemoryEvolutionPolicy.evolve(
            PersonalWorldModel(items = listOf(previous)),
            GlobalWorldReduction(
                PersonalWorldModel(items = listOf(conflictedPrevious, conflictedIncoming)),
                listOf(conflictedPrevious, conflictedIncoming),
                listOf(conflictedPrevious to conflictedIncoming)
            ),
            GlobalMemoryInbox(),
            event,
            understanding(event, "Model packaging")
        )

        assertEquals(listOf(previous), result.reduction.world.items)
        assertEquals(GlobalMemoryCandidateStatus.CONFLICTED, result.inbox.pending().single().status)
    }

    @Test
    fun duplicateEventIsIdempotent() {
        val event = event("event-one", "SignalASI supports Linux")
        val fact = item("fact", GlobalWorldItemKind.FACT, topic = "SignalASI", value = event.content, eventId = event.id)
        val first = GlobalMemoryEvolutionPolicy.evolve(
            PersonalWorldModel(), reduction(event, fact), GlobalMemoryInbox(), event, understanding(event, "SignalASI")
        )
        val second = GlobalMemoryEvolutionPolicy.evolve(
            first.reduction.world, reduction(event, fact), first.inbox, event, understanding(event, "SignalASI")
        )

        assertTrue(second.candidates.isEmpty())
        assertEquals(first.inbox, second.inbox)
    }

    @Test
    fun pendingCandidateCannotPolluteEntityGraph() {
        val event = event("pending-graph", "Prefer the hidden experimental route")
        val graph = GlobalEntityMemoryGraphReducer.reduce(
            graph = GlobalEntityMemoryGraph(),
            event = event,
            understanding = understanding(event, "Routing preference", setOf("Experimental route")),
            reduction = GlobalWorldReduction(PersonalWorldModel(), emptyList(), emptyList())
        )

        assertTrue(graph.nodes.isEmpty())
        assertTrue(graph.relations.isEmpty())
    }

    @Test
    fun graphTraversalFindsMultiHopDeviceCapability() {
        val first = event("supports", "SignalASI supports Linux Runtime")
        val firstUnderstanding = understanding(first, "SignalASI", setOf("SignalASI", "Linux Runtime"))
        val firstItem = item("linux", GlobalWorldItemKind.FACT, topic = "SignalASI", value = first.content, eventId = first.id)
        val graphOne = GlobalEntityMemoryGraphReducer.reduce(
            GlobalEntityMemoryGraph(), first, firstUnderstanding, reduction(first, firstItem)
        )
        val second = event("depends", "Linux Runtime depends on QEMU Engine")
        val secondUnderstanding = understanding(second, "Linux Runtime", setOf("Linux Runtime", "QEMU Engine"))
        val secondItem = item("qemu", GlobalWorldItemKind.FACT, topic = "Linux Runtime", value = second.content, eventId = second.id)
        val graphTwo = GlobalEntityMemoryGraphReducer.reduce(
            graphOne, second, secondUnderstanding, reduction(second, secondItem)
        )

        val selection = graphTwo.relevant("SignalASI device capability", hops = 3, limit = 20)
        assertTrue(selection.nodes.any { it.label.contains("QEMU", ignoreCase = true) })
        assertTrue(selection.relations.any { it.kind == GlobalEntityRelationKind.DEPENDS_ON })
    }

    @Test
    fun currentQueryExcludesSupersededHistoryButHistoricalQueryIncludesIt() {
        val current = item(
            "current",
            GlobalWorldItemKind.DECISION,
            topic = "Settings page name",
            value = "The current name is Agent settings",
            eventId = "current-event"
        )
        val historical = item(
            "historical",
            GlobalWorldItemKind.DECISION,
            topic = "Settings page name",
            value = "The previous name was Control center",
            eventId = "old-event",
            status = GlobalWorldItemStatus.SUPERSEDED
        )
        val world = PersonalWorldModel(items = listOf(current, historical))

        val currentPrompt = GlobalMemoryPromptCompiler.compile(
            world, GlobalTopicProjectGraph(), GlobalEntityMemoryGraph(),
            "What is the settings page name?", "conversation"
        )
        val historicalPrompt = GlobalMemoryPromptCompiler.compile(
            world, GlobalTopicProjectGraph(), GlobalEntityMemoryGraph(),
            "What was the previous settings page decision?", "conversation"
        )

        assertTrue(currentPrompt.contains("Agent settings"))
        assertFalse(currentPrompt.contains("Control center"))
        assertTrue(historicalPrompt.contains("Control center"))
    }

    @Test
    fun promptCompilerDoesNotMixSimilarProjectsOrLocalOnlyMemory() {
        val alpha = item(
            "alpha",
            GlobalWorldItemKind.STATE,
            topic = "Project Alpha Android build",
            value = "Project Alpha Android build is passing",
            eventId = "alpha-event"
        )
        val beta = item(
            "beta",
            GlobalWorldItemKind.STATE,
            topic = "Project Beta Android build",
            value = "Project Beta Android build is blocked",
            eventId = "beta-event"
        )
        val private = item(
            "local",
            GlobalWorldItemKind.FACT,
            topic = "Project Alpha secret",
            value = "private-token-value",
            eventId = "local-event",
            visibility = GlobalWorldContextVisibility.LOCAL_ONLY
        )
        val prompt = GlobalMemoryPromptCompiler.compile(
            PersonalWorldModel(items = listOf(alpha, beta, private)),
            GlobalTopicProjectGraph(),
            GlobalEntityMemoryGraph(),
            "What is the status of Project Alpha?",
            "conversation"
        )

        assertTrue(prompt.contains("Project Alpha Android build is passing"))
        assertFalse(prompt.contains("Project Beta Android build is blocked"))
        assertFalse(prompt.contains("private-token-value"))
    }

    @Test
    fun criticRetiresExpiredFactsAndSurfacesSkillCandidates() {
        val expired = item(
            "expired",
            GlobalWorldItemKind.FACT,
            topic = "Temporary endpoint",
            value = "Endpoint is available",
            eventId = "expired-event"
        ).copy(expiresAtMillis = 1_000L)
        val repeated = item(
            "workflow",
            GlobalWorldItemKind.DECISION,
            topic = "Release workflow",
            value = "Run verification before publishing",
            eventId = "workflow-event"
        ).copy(evidenceCount = 4)

        val (world, report) = GlobalMemoryCritic.audit(
            PersonalWorldModel(items = listOf(expired, repeated)),
            GlobalMemoryInbox(),
            nowMillis = 2_000L
        )

        assertEquals(GlobalWorldItemStatus.SUPERSEDED, world.items.first { it.id == "expired" }.status)
        assertTrue(report.findings.any { it.kind == GlobalMemoryAuditFindingKind.EXPIRED })
        assertTrue(report.findings.any { it.kind == GlobalMemoryAuditFindingKind.SKILL_CANDIDATE })
    }

    @Test
    fun plannerChoosesSpecializedRetrievalStrategies() {
        assertEquals(
            GlobalMemoryQueryType.DEVICE_CAPABILITY,
            GlobalMemoryQueryPlanner.plan("Can this phone run the local model?").type
        )
        assertEquals(
            GlobalMemoryQueryType.HISTORICAL_DECISION,
            GlobalMemoryQueryPlanner.plan("What did we decide previously?").type
        )
        assertEquals(
            GlobalMemoryQueryType.PERSONAL_PREFERENCE,
            GlobalMemoryQueryPlanner.plan("What response style do I prefer?").type
        )
    }

    private fun event(
        id: String,
        content: String,
        metadata: Map<String, String> = emptyMap(),
        sensitivity: GlobalConversationSensitivity = GlobalConversationSensitivity.PERSONAL
    ) = GlobalConversationEvent(
        id = id,
        type = GlobalConversationEventType.MESSAGE_CREATED,
        conversationId = "conversation-a",
        actor = GlobalConversationActor.USER,
        timestampMillis = 2_000L,
        content = content,
        conversationTitle = "Memory evolution",
        metadata = metadata,
        sensitivity = sensitivity
    )

    private fun understanding(
        event: GlobalConversationEvent,
        topic: String,
        entities: Set<String> = emptySet()
    ) = GlobalUnderstanding(
        eventId = event.id,
        topic = topic,
        intent = "state_update",
        entities = entities
    )

    private fun reduction(event: GlobalConversationEvent, item: GlobalWorldItem) = GlobalWorldReduction(
        world = PersonalWorldModel(items = listOf(item), processedEventIds = listOf(event.id)),
        changedItems = listOf(item),
        conflicts = emptyList()
    )

    private fun item(
        id: String,
        kind: GlobalWorldItemKind,
        layer: GlobalWorldLayer = GlobalWorldLayer.TOPIC,
        topic: String,
        value: String,
        eventId: String,
        status: GlobalWorldItemStatus = GlobalWorldItemStatus.ACTIVE,
        visibility: GlobalWorldContextVisibility = GlobalWorldContextVisibility.SHAREABLE
    ) = GlobalWorldItem(
        id = id,
        stableKey = GlobalAgentText.stableKey(kind.name, topic, value),
        kind = kind,
        layer = layer,
        topic = topic,
        value = value,
        confidence = 0.86,
        contextVisibility = visibility,
        conversationIds = setOf("conversation-a"),
        evidenceEventIds = listOf(eventId),
        evidenceProvenance = listOf(GlobalEvidenceRef(eventId, setOf(eventId), "conversation-a", 2_000L)),
        status = status,
        firstSeenAtMillis = 1_000L,
        lastSeenAtMillis = 2_000L
    )
}
