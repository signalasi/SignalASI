package com.signalasi.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GlobalMemoryLoCoMoRegressionTest {
    @Test
    fun `cross session preference recall uses durable user memory`() {
        val preference = item(
            id = "preference",
            kind = GlobalWorldItemKind.PREFERENCE,
            layer = GlobalWorldLayer.USER,
            topic = "Response style",
            value = "The user prefers concise engineering responses",
            conversationId = "session-one"
        )

        val context = compile(
            PersonalWorldModel(items = listOf(preference)),
            "What response style do I prefer?",
            "session-twenty"
        )

        assertTrue(context.contains(preference.value))
        assertTrue(context.contains("personal_preference"))
    }

    @Test
    fun `current and historical queries select different temporal facts`() {
        val oldName = item(
            id = "old-name",
            kind = GlobalWorldItemKind.DECISION,
            topic = "Settings page name",
            value = "The settings page was named Settings",
            conversationId = "session-one",
            status = GlobalWorldItemStatus.SUPERSEDED,
            temporalState = GlobalMemoryTemporalState.DEPRECATED
        )
        val currentName = item(
            id = "current-name",
            kind = GlobalWorldItemKind.DECISION,
            topic = "Settings page name",
            value = "The settings page is named Control Center",
            conversationId = "session-two"
        )
        val world = PersonalWorldModel(items = listOf(oldName, currentName))

        val current = compile(world, "What is the current settings page name?", "session-three")
        val history = compile(world, "What was the previous settings page name?", "session-three")
        val comparison = compile(world, "What changed between the previous and current settings page name?", "session-three")

        assertTrue(current.contains(currentName.value))
        assertFalse(current.contains(oldName.value))
        assertTrue(history.contains(oldName.value))
        assertFalse(history.contains(currentName.value))
        assertTrue(comparison.contains(oldName.value))
        assertTrue(comparison.contains(currentName.value))
    }

    @Test
    fun `explicit correction evolves the old fact instead of appending two current facts`() {
        val previous = item(
            id = "screen-old",
            kind = GlobalWorldItemKind.STATE,
            topic = "SignalASI screen understanding",
            value = "SignalASI screen understanding is enabled",
            conversationId = "session-one"
        )
        val event = event(
            id = "screen-correction",
            content = "I was wrong; SignalASI screen understanding has been removed",
            conversationId = "session-two"
        )
        val incoming = item(
            id = "screen-new",
            kind = GlobalWorldItemKind.STATE,
            topic = previous.topic,
            value = event.content,
            conversationId = event.conversationId,
            eventId = event.id
        )
        val result = GlobalMemoryEvolutionPolicy.evolve(
            PersonalWorldModel(items = listOf(previous)),
            GlobalWorldReduction(
                world = PersonalWorldModel(items = listOf(previous, incoming)),
                changedItems = listOf(incoming),
                conflicts = emptyList()
            ),
            GlobalMemoryInbox(),
            event,
            understanding(event, previous.topic)
        )

        assertEquals(GlobalWorldItemStatus.SUPERSEDED, result.reduction.world.items.first { it.id == previous.id }.status)
        assertEquals(GlobalMemoryTemporalState.DEPRECATED, result.reduction.world.items.first { it.id == previous.id }.temporalState)
        assertEquals(1, result.reduction.world.items.count { it.status == GlobalWorldItemStatus.ACTIVE })
    }

    @Test
    fun `similar project names do not contaminate one another`() {
        val alpha = item(
            "alpha", GlobalWorldItemKind.STATE, GlobalWorldLayer.TOPIC,
            "Project Alpha release", "Project Alpha release is ready", "session-alpha"
        )
        val beta = item(
            "beta", GlobalWorldItemKind.STATE, GlobalWorldLayer.TOPIC,
            "Project Beta release", "Project Beta release is blocked", "session-beta"
        )

        val context = compile(
            PersonalWorldModel(items = listOf(alpha, beta)),
            "What is the status of Project Alpha?",
            "session-new"
        )

        assertTrue(context.contains(alpha.value))
        assertFalse(context.contains(beta.value))
    }

    @Test
    fun `private and local only evidence never enters compiled context`() {
        val shareable = item(
            "public", GlobalWorldItemKind.FACT, GlobalWorldLayer.TOPIC,
            "Runtime package", "The runtime package is verified", "session-one"
        )
        val local = item(
            "private", GlobalWorldItemKind.FACT, GlobalWorldLayer.TOPIC,
            "Runtime secret", "private-token-value", "session-one",
            visibility = GlobalWorldContextVisibility.LOCAL_ONLY
        )

        val context = compile(
            PersonalWorldModel(items = listOf(shareable, local)),
            "What do we know about the runtime?",
            "session-two"
        )

        assertTrue(context.contains(shareable.value))
        assertFalse(context.contains(local.value))
    }

    @Test
    fun `tool result queries prioritize reusable execution evidence`() {
        val toolResult = item(
            "tool-result", GlobalWorldItemKind.FACT, GlobalWorldLayer.REALTIME,
            "Build command output", "The Android build completed successfully", "session-build"
        )
        val plan = GlobalMemoryQueryPlanner.plan("What output did the build command produce?")
        val context = compile(
            PersonalWorldModel(items = listOf(toolResult)),
            "What output did the build command produce?",
            "session-review"
        )

        assertTrue(GlobalMemoryQueryType.TOOL_EVIDENCE in plan.types)
        assertTrue(GlobalMemoryQueryType.PROJECT_STATE in plan.types)
        assertTrue(context.contains(toolResult.value))
    }

    @Test
    fun `long term goal remains available across distant sessions`() {
        val goal = item(
            "goal", GlobalWorldItemKind.GOAL, GlobalWorldLayer.USER,
            "SignalASI roadmap", "Build a reliable on-device personal Agent", "session-one",
            temporalState = GlobalMemoryTemporalState.PLANNED
        )

        val context = compile(
            PersonalWorldModel(items = listOf(goal)),
            "What is the next long-term roadmap milestone?",
            "session-thirty-two"
        )

        assertTrue(context.contains(goal.value))
        assertTrue(context.contains("planned"))
    }

    @Test
    fun `typed graph follows multi hop device capability relations`() {
        val user = node("user", "User", GlobalEntityNodeKind.USER)
        val phone = node("phone", "SignalASI phone", GlobalEntityNodeKind.DEVICE)
        val chip = node("chip", "Snapdragon chip", GlobalEntityNodeKind.DEVICE)
        val model = node("model", "Gemma model", GlobalEntityNodeKind.MODEL)
        val graph = GlobalEntityMemoryGraph(
            nodes = listOf(user, phone, chip, model),
            relations = listOf(
                relation("owns", user, phone, GlobalEntityRelationKind.OWNS),
                relation("chip", phone, chip, GlobalEntityRelationKind.HAS_COMPONENT),
                relation("supports", chip, model, GlobalEntityRelationKind.SUPPORTS)
            )
        )

        val context = GlobalMemoryPromptCompiler.compile(
            PersonalWorldModel(),
            GlobalTopicProjectGraph(),
            graph,
            "What model does the SignalASI phone support?",
            "session-device"
        )

        assertTrue(context.contains("SignalASI phone"))
        assertTrue(context.contains("Snapdragon chip"))
        assertTrue(context.contains("Gemma model"))
        assertTrue(context.contains("supports"))
    }

    @Test
    fun `unresolved contradictions are labeled instead of presented as settled truth`() {
        val first = item(
            "conflict-one", GlobalWorldItemKind.STATE, GlobalWorldLayer.TOPIC,
            "Gateway state", "The gateway is online", "session-one",
            status = GlobalWorldItemStatus.CONFLICTED,
            temporalState = GlobalMemoryTemporalState.CONFLICTED
        ).copy(conflictGroupId = "gateway-conflict")
        val second = item(
            "conflict-two", GlobalWorldItemKind.STATE, GlobalWorldLayer.TOPIC,
            "Gateway state", "The gateway is offline", "session-two",
            status = GlobalWorldItemStatus.CONFLICTED,
            temporalState = GlobalMemoryTemporalState.CONFLICTED
        ).copy(conflictGroupId = "gateway-conflict")

        val context = compile(
            PersonalWorldModel(items = listOf(first, second)),
            "What is the current gateway state?",
            "session-three"
        )

        assertTrue(context.contains("Conflict notice"))
        assertTrue(context.contains(first.value))
        assertTrue(context.contains(second.value))
    }

    @Test
    fun `causal deletion removes graph evidence and candidate memory`() {
        val source = event("source", "SignalASI supports OCR", "session-one")
        val sourceItem = item(
            "source-item", GlobalWorldItemKind.FACT, GlobalWorldLayer.TOPIC,
            "SignalASI OCR", source.content, source.conversationId, eventId = source.id
        )
        val reduction = GlobalWorldReduction(
            PersonalWorldModel(items = listOf(sourceItem)),
            listOf(sourceItem),
            emptyList()
        )
        val evolved = GlobalMemoryEvolutionPolicy.evolve(
            PersonalWorldModel(), reduction, GlobalMemoryInbox(), source,
            understanding(source, sourceItem.topic, setOf("SignalASI", "OCR"))
        )
        val graph = GlobalEntityMemoryGraphReducer.reduce(
            GlobalEntityMemoryGraph(), source,
            understanding(source, sourceItem.topic, setOf("SignalASI", "OCR")),
            reduction
        )
        val deletion = event("delete", "Delete that memory", "session-one").copy(
            type = GlobalConversationEventType.MEMORY_DELETED,
            retractedEventIds = setOf(source.id)
        )
        val deletedEvolution = GlobalMemoryEvolutionPolicy.evolve(
            evolved.reduction.world,
            GlobalWorldReduction(evolved.reduction.world, emptyList(), emptyList()),
            evolved.inbox,
            deletion,
            understanding(deletion, "Memory deletion")
        )
        val deletedGraph = GlobalEntityMemoryGraphReducer.reduce(
            graph,
            deletion,
            understanding(deletion, "Memory deletion"),
            GlobalWorldReduction(evolved.reduction.world, emptyList(), emptyList())
        )

        assertTrue(deletedEvolution.inbox.candidates.none { it.sourceEventId == source.id })
        assertTrue(deletedGraph.nodes.none { node -> node.evidence.any { it.eventId == source.id } })
        assertTrue(deletedGraph.relations.none { relation -> relation.evidence.any { it.eventId == source.id } })
    }

    private fun compile(world: PersonalWorldModel, query: String, conversationId: String): String =
        GlobalMemoryPromptCompiler.compile(
            world,
            GlobalTopicProjectGraph(),
            GlobalEntityMemoryGraph(),
            query,
            conversationId
        )

    private fun item(
        id: String,
        kind: GlobalWorldItemKind,
        layer: GlobalWorldLayer = GlobalWorldLayer.TOPIC,
        topic: String,
        value: String,
        conversationId: String,
        status: GlobalWorldItemStatus = GlobalWorldItemStatus.ACTIVE,
        temporalState: GlobalMemoryTemporalState = GlobalMemoryTemporalState.CURRENT,
        visibility: GlobalWorldContextVisibility = GlobalWorldContextVisibility.SHAREABLE,
        eventId: String = "$id-event"
    ) = GlobalWorldItem(
        id = id,
        stableKey = GlobalAgentText.stableKey(kind.name, topic, value),
        kind = kind,
        layer = layer,
        topic = topic,
        value = value,
        confidence = 0.88,
        contextVisibility = visibility,
        conversationIds = setOf(conversationId),
        evidenceEventIds = listOf(eventId),
        evidenceProvenance = listOf(GlobalEvidenceRef(eventId, setOf(eventId), conversationId, 1_000L)),
        status = status,
        temporalState = temporalState,
        firstSeenAtMillis = 1_000L,
        lastSeenAtMillis = 2_000L
    )

    private fun event(id: String, content: String, conversationId: String) = GlobalConversationEvent(
        id = id,
        type = GlobalConversationEventType.MESSAGE_CREATED,
        conversationId = conversationId,
        actor = GlobalConversationActor.USER,
        timestampMillis = 2_000L,
        content = content,
        conversationTitle = "Memory regression"
    )

    private fun understanding(
        event: GlobalConversationEvent,
        topic: String,
        entities: Set<String> = emptySet()
    ) = GlobalUnderstanding(
        eventId = event.id,
        topic = topic,
        intent = "memory_update",
        entities = entities
    )

    private fun node(id: String, label: String, kind: GlobalEntityNodeKind) = GlobalEntityNode(
        id = id,
        stableKey = id,
        label = label,
        kind = kind,
        aliases = setOf(label),
        confidence = 0.9,
        lastSeenAtMillis = 2_000L
    )

    private fun relation(
        id: String,
        from: GlobalEntityNode,
        to: GlobalEntityNode,
        kind: GlobalEntityRelationKind
    ) = GlobalEntityRelation(
        id = id,
        fromNodeId = from.id,
        toNodeId = to.id,
        kind = kind,
        confidence = 0.9,
        lastSeenAtMillis = 2_000L
    )
}
