package com.signalasi.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GlobalAgentKnowledgeGraphTest {
    @Test
    fun `conversation event creates an evidence backed topic node`() {
        val event = event("event-a", "conversation-a", "Android runtime")
        val understanding = understanding(event, "Android runtime")

        val graph = GlobalTopicProjectGraphReducer.reduce(
            GlobalTopicProjectGraph(),
            event,
            understanding,
            reduction(event, understanding)
        )

        assertEquals(1, graph.nodes.size)
        assertEquals(setOf("conversation-a"), graph.nodes.single().conversationIds)
        assertEquals(listOf("event-a"), graph.nodes.single().evidenceEventIds)
    }

    @Test
    fun `durable cross session topic is promoted to a project`() {
        val event = event("event-a", "conversation-a", "SignalASI runtime")
        val understanding = understanding(event, "SignalASI runtime").copy(
            durableFollowUpUseful = true,
            crossConversationIds = setOf("conversation-b"),
            goalCandidates = listOf("Ship the runtime")
        )

        val graph = GlobalTopicProjectGraphReducer.reduce(
            GlobalTopicProjectGraph(),
            event,
            understanding,
            reduction(event, understanding)
        )

        assertEquals(GlobalTopicNodeKind.PROJECT, graph.nodes.single().kind)
        assertEquals(setOf("conversation-a", "conversation-b"), graph.nodes.single().conversationIds)
    }

    @Test
    fun `model project creates a contains relationship`() {
        val event = event("event-a", "conversation-a", "Android runtime")
        val understanding = understanding(event, "Android runtime").copy(
            project = "SignalASI",
            relatedTopics = setOf("Run engine")
        )

        val graph = GlobalTopicProjectGraphReducer.reduce(
            GlobalTopicProjectGraph(),
            event,
            understanding,
            reduction(event, understanding)
        )

        val project = graph.nodes.single { it.name == "SignalASI" }
        val topic = graph.nodes.single { it.name == "Android runtime" }
        assertEquals(GlobalTopicNodeKind.PROJECT, project.kind)
        assertTrue(graph.relations.any {
            it.kind == GlobalTopicRelationKind.CONTAINS &&
                it.fromNodeId == project.id && it.toNodeId == topic.id
        })
        assertTrue(graph.nodes.any { it.name == "Run engine" })
    }

    @Test
    fun `duplicate event is idempotent across topic nodes and relations`() {
        val event = event("event-a", "conversation-a", "Android runtime").copy(timestampMillis = 2_000L)
        val understanding = understanding(event, "Android runtime").copy(relatedTopics = setOf("Run engine"))
        val first = GlobalTopicProjectGraphReducer.reduce(
            GlobalTopicProjectGraph(),
            event,
            understanding,
            reduction(event, understanding)
        )

        val duplicate = GlobalTopicProjectGraphReducer.reduce(
            first,
            event,
            understanding,
            reduction(event, understanding)
        )

        assertEquals(first, duplicate)
        assertEquals(listOf(event.id), duplicate.processedEventIds)
    }

    @Test
    fun `older retried event adds evidence without regressing graph time`() {
        val newer = event("newer", "conversation-new", "Android runtime").copy(timestampMillis = 2_000L)
        val newerUnderstanding = understanding(newer, "Android runtime").copy(relatedTopics = setOf("Run engine"))
        val current = GlobalTopicProjectGraphReducer.reduce(
            GlobalTopicProjectGraph(),
            newer,
            newerUnderstanding,
            reduction(newer, newerUnderstanding)
        )
        val older = event("older", "conversation-old", "Android runtime").copy(timestampMillis = 1_000L)
        val olderUnderstanding = understanding(older, "Android runtime").copy(relatedTopics = setOf("Run engine"))

        val retried = GlobalTopicProjectGraphReducer.reduce(
            current,
            older,
            olderUnderstanding,
            reduction(older, olderUnderstanding)
        )
        val topic = retried.nodes.single { it.name == "Android runtime" }

        assertEquals(1_000L, topic.firstSeenAtMillis)
        assertEquals(2_000L, topic.lastSeenAtMillis)
        assertEquals(2_000L, retried.updatedAtMillis)
        assertEquals(setOf("newer", "older"), topic.evidenceEventIds.toSet())
        assertEquals(setOf("conversation-new", "conversation-old"), topic.conversationIds)
        assertTrue(retried.relations.all { it.lastSeenAtMillis == 2_000L })
    }

    @Test
    fun `older conversation deletion cannot erase newer conversation evidence`() {
        val newer = event("newer", "conversation-a", "Android runtime").copy(timestampMillis = 2_000L)
        val newerUnderstanding = understanding(newer, "Android runtime")
        val current = GlobalTopicProjectGraphReducer.reduce(
            GlobalTopicProjectGraph(),
            newer,
            newerUnderstanding,
            reduction(newer, newerUnderstanding)
        )
        val olderDeletion = GlobalConversationEvent(
            id = "older-deletion",
            type = GlobalConversationEventType.CONVERSATION_DELETED,
            conversationId = "conversation-a",
            actor = GlobalConversationActor.SYSTEM,
            timestampMillis = 1_000L
        )

        val retained = GlobalTopicProjectGraphReducer.reduce(
            current,
            olderDeletion,
            understanding(olderDeletion, "Android runtime"),
            GlobalWorldReduction(PersonalWorldModel(), emptyList(), emptyList())
        )

        assertEquals(1, retained.nodes.size)
        assertTrue("conversation-a" in retained.nodes.single().conversationIds)
        assertEquals(2_000L, retained.nodes.single().lastSeenAtMillis)
        assertEquals(2_000L, retained.updatedAtMillis)
    }

    @Test
    fun `deleting the only conversation removes its topic and relations`() {
        val first = event("event-a", "conversation-a", "Android runtime")
        val understanding = understanding(first, "Android runtime").copy(relatedTopics = setOf("Run engine"))
        val graph = GlobalTopicProjectGraphReducer.reduce(
            GlobalTopicProjectGraph(),
            first,
            understanding,
            reduction(first, understanding)
        )
        val deletion = first.copy(
            id = "delete-a",
            type = GlobalConversationEventType.CONVERSATION_DELETED,
            content = ""
        )

        val deleted = GlobalTopicProjectGraphReducer.reduce(
            graph,
            deletion,
            understanding.copy(eventId = deletion.id),
            GlobalWorldReduction(PersonalWorldModel(), emptyList(), emptyList())
        )

        assertTrue(deleted.nodes.none { "conversation-a" in it.conversationIds })
        assertTrue(deleted.relations.all { relation ->
            deleted.nodes.any { it.id == relation.fromNodeId } && deleted.nodes.any { it.id == relation.toNodeId }
        })
    }

    @Test
    fun `deleting message evidence retracts its topic graph branch`() {
        val source = event("event-a", "conversation-a", "Android runtime")
        val sourceUnderstanding = understanding(source, "Android runtime").copy(
            relatedTopics = setOf("Run engine")
        )
        val graph = GlobalTopicProjectGraphReducer.reduce(
            GlobalTopicProjectGraph(),
            source,
            sourceUnderstanding,
            reduction(source, sourceUnderstanding)
        )
        val deletion = GlobalConversationEvent(
            id = "delete-event-a",
            type = GlobalConversationEventType.MESSAGE_DELETED,
            conversationId = source.conversationId,
            actor = GlobalConversationActor.SYSTEM,
            retractedEventIds = setOf(source.id)
        )

        val retracted = GlobalTopicProjectGraphReducer.reduce(
            graph,
            deletion,
            understanding(deletion, "Android runtime"),
            GlobalWorldReduction(PersonalWorldModel(), emptyList(), emptyList())
        )

        assertTrue(retracted.nodes.isEmpty())
        assertTrue(retracted.relations.isEmpty())
        assertTrue(source.id in retracted.retractedEventIds)
    }

    @Test
    fun `late derived event cannot recreate a retracted topic`() {
        val source = event("event-a", "conversation-a", "Android runtime")
        val deletion = GlobalConversationEvent(
            id = "delete-event-a",
            type = GlobalConversationEventType.MESSAGE_DELETED,
            conversationId = source.conversationId,
            actor = GlobalConversationActor.SYSTEM,
            retractedEventIds = setOf(source.id)
        )
        val retracted = GlobalTopicProjectGraphReducer.reduce(
            GlobalTopicProjectGraph(),
            deletion,
            understanding(deletion, "Android runtime"),
            GlobalWorldReduction(PersonalWorldModel(), emptyList(), emptyList())
        )
        val late = source.copy(
            id = "late-cognition",
            type = GlobalConversationEventType.COGNITION_RESULT,
            actor = GlobalConversationActor.GLOBAL_AGENT,
            causalEventIds = setOf(source.id)
        )

        val ignored = GlobalTopicProjectGraphReducer.reduce(
            retracted,
            late,
            understanding(late, "Android runtime"),
            reduction(late, understanding(late, "Android runtime"))
        )

        assertTrue(ignored.nodes.isEmpty())
        assertTrue(ignored.relations.isEmpty())
    }

    @Test
    fun `action dependencies expose only ready branches`() {
        val inspect = action("inspect", emptySet())
        val draft = action("draft", setOf("inspect"))
        val prepared = GlobalAutonomousActionGraphPolicy.prepare(listOf(inspect, draft))

        assertEquals(listOf("inspect"), GlobalAutonomousActionGraphPolicy.readyActions(prepared).map { it.planKey })
        val completed = prepared.map {
            if (it.planKey == "inspect") it.copy(
                status = GlobalAutonomousActionStatus.COMPLETED,
                verificationStatus = GlobalActionVerificationStatus.SUPPORTED
            ) else it
        }
        assertEquals(listOf("draft"), GlobalAutonomousActionGraphPolicy.readyActions(completed).map { it.planKey })
    }

    @Test
    fun `independent action branches are simultaneously ready`() {
        val prepared = GlobalAutonomousActionGraphPolicy.prepare(listOf(
            action("research", emptySet()),
            action("inspect", emptySet()),
            action("draft", setOf("research", "inspect"))
        ))

        assertEquals(setOf("research", "inspect"),
            GlobalAutonomousActionGraphPolicy.readyActions(prepared).map { it.planKey }.toSet())
    }

    @Test
    fun `atomic reservations never claim the same independent branch twice`() {
        val prepared = GlobalAutonomousActionGraphPolicy.prepare(listOf(
            action("research", emptySet()),
            action("inspect", emptySet()),
            action("draft", setOf("research", "inspect"))
        ))

        val first = GlobalAutonomousActionGraphPolicy.reserveNext(prepared, 1_000L, 9_000L)
        val second = GlobalAutonomousActionGraphPolicy.reserveNext(first!!.actions, 1_001L, 9_001L)

        assertTrue(first.actionId != second!!.actionId)
        assertEquals(2, second.actions.count { it.status == GlobalAutonomousActionStatus.RUNNING })
        assertTrue(GlobalAutonomousActionGraphPolicy.readyActions(second.actions).isEmpty())
        assertTrue(second.actions.filter { it.status == GlobalAutonomousActionStatus.RUNNING }
            .all { it.attemptCount == 1 })
    }

    @Test
    fun `failed prerequisite skips only its dependent branch`() {
        val prepared = GlobalAutonomousActionGraphPolicy.prepare(listOf(
            action("inspect", emptySet()),
            action("draft", setOf("inspect")),
            action("independent", emptySet())
        )).map {
            if (it.planKey == "inspect") it.copy(status = GlobalAutonomousActionStatus.FAILED) else it
        }

        val reconciled = GlobalAutonomousActionGraphPolicy.reconcile(prepared)

        assertEquals(GlobalAutonomousActionStatus.SKIPPED, reconciled.single { it.planKey == "draft" }.status)
        assertEquals(GlobalAutonomousActionStatus.PENDING, reconciled.single { it.planKey == "independent" }.status)
    }

    @Test
    fun `cyclic action plan is not executable`() {
        val prepared = GlobalAutonomousActionGraphPolicy.prepare(listOf(
            action("first", setOf("second")),
            action("second", setOf("first"))
        ))

        assertTrue(GlobalAutonomousActionGraphPolicy.readyActions(prepared).isEmpty())
        assertTrue(prepared.all { it.status == GlobalAutonomousActionStatus.SKIPPED })
    }

    @Test
    fun `delegated analysis output satisfies a supported evidence contract`() {
        val action = GlobalAutonomousAction(
            kind = GlobalAutonomousActionKind.ANALYZE,
            goal = "Analyze the architecture"
        )
        val contract = GlobalActionVerificationPolicy.defaultContract(action)
        val evidence = GlobalActionEvidence(
            kind = GlobalActionEvidenceKind.DELEGATED_RESULT,
            summary = "The architecture has one unsafe dependency.",
            confidence = 0.72
        )

        assertEquals(
            GlobalActionVerificationStatus.SUPPORTED,
            GlobalActionVerificationPolicy.evaluate(contract, listOf(evidence))
        )
    }

    @Test
    fun `research action requires a verified evidence ledger`() {
        val action = GlobalAutonomousAction(
            kind = GlobalAutonomousActionKind.START_RESEARCH,
            goal = "Verify the platform constraint"
        )
        val contract = GlobalActionVerificationPolicy.defaultContract(action)
        val unverified = GlobalActionEvidence(
            kind = GlobalActionEvidenceKind.RESEARCH_LEDGER,
            summary = "One source mentioned the constraint.",
            confidence = 0.80,
            verified = false
        )
        val verified = unverified.copy(id = "verified", verified = true)

        assertEquals(
            GlobalActionVerificationStatus.INSUFFICIENT,
            GlobalActionVerificationPolicy.evaluate(contract, listOf(unverified))
        )
        assertEquals(
            GlobalActionVerificationStatus.VERIFIED,
            GlobalActionVerificationPolicy.evaluate(contract, listOf(verified))
        )
    }

    @Test
    fun `run completion requires accepted action evidence`() {
        val pending = GlobalAutonomousAction(
            kind = GlobalAutonomousActionKind.ANALYZE,
            goal = "Analyze",
            status = GlobalAutonomousActionStatus.COMPLETED
        )
        val supported = pending.copy(verificationStatus = GlobalActionVerificationStatus.SUPPORTED)

        assertFalse(GlobalAutonomousRunPolicy.completionSupported(listOf(pending)))
        assertTrue(GlobalAutonomousRunPolicy.completionSupported(listOf(supported)))
    }

    @Test
    fun `goal dependency waits then resumes when prerequisite completes`() {
        val prerequisite = goal("Build runtime")
        val dependent = goal("Ship mobile Agent")
        val linked = GlobalLongHorizonGoalGraphPolicy.applyDependencies(
            listOf(prerequisite, dependent),
            listOf(GlobalGoalDependencyProposal("Ship mobile Agent", "Build runtime")),
            1_000L
        )

        assertEquals(
            GlobalLongHorizonGoalStatus.WAITING_DEPENDENCY,
            linked.single { it.id == dependent.id }.status
        )
        val released = GlobalLongHorizonGoalGraphPolicy.reconcile(linked.map {
            if (it.id == prerequisite.id) it.copy(status = GlobalLongHorizonGoalStatus.COMPLETED) else it
        }, 2_000L)
        assertEquals(GlobalLongHorizonGoalStatus.ACTIVE, released.single { it.id == dependent.id }.status)
        assertEquals(2_000L, released.single { it.id == dependent.id }.nextCheckAtMillis)
    }

    @Test
    fun `cyclic goal dependency proposal is ignored`() {
        val first = goal("First")
        val second = goal("Second").copy(dependencyGoalIds = setOf(first.id))

        val updated = GlobalLongHorizonGoalGraphPolicy.applyDependencies(
            listOf(first, second),
            listOf(GlobalGoalDependencyProposal("First", "Second")),
            1_000L
        )

        assertTrue(updated.single { it.id == first.id }.dependencyGoalIds.isEmpty())
    }

    @Test
    fun `structured cognition parser preserves project graph and plan dependencies`() {
        val parsed = GlobalModelUnderstandingParser.parse(
            """{
              "topic":"Android runtime",
              "project":"SignalASI",
              "related_topics":["Run engine"],
              "goal_dependencies":[{"goal":"Ship","depends_on":"Verify"}],
              "actions":[
                {"key":"verify","depends_on":[],"kind":"READ_ONLY_CHECK","goal":"Verify constraints"},
                {"key":"ship","depends_on":["verify"],"kind":"DRAFT","goal":"Draft the release"}
              ],
              "confidence":0.9
            }"""
        )

        assertNotNull(parsed)
        assertEquals("SignalASI", parsed?.project)
        assertEquals(listOf("Run engine"), parsed?.relatedTopics)
        assertEquals(1, parsed?.goalDependencies?.size)
        val ship = parsed?.actions?.single { it.planKey == "ship" }
        assertEquals(1, ship?.dependsOnActionIds?.size)
    }

    private fun action(key: String, dependencies: Set<String>) = GlobalAutonomousAction(
        planKey = key,
        dependencyKeys = dependencies,
        kind = GlobalAutonomousActionKind.ANALYZE,
        goal = key,
        priority = if (key == "inspect") 0.9 else 0.5
    )

    private fun goal(title: String) = GlobalLongHorizonGoal(
        stableKey = GlobalAgentText.stableKey(title),
        topic = "SignalASI",
        title = title,
        nextCheckAtMillis = 1_000L
    )

    private fun event(id: String, conversationId: String, title: String) = GlobalConversationEvent(
        id = id,
        type = GlobalConversationEventType.MESSAGE_CREATED,
        conversationId = conversationId,
        actor = GlobalConversationActor.USER,
        content = "Continue $title",
        conversationTitle = title
    )

    private fun understanding(event: GlobalConversationEvent, topic: String) = GlobalUnderstanding(
        eventId = event.id,
        topic = topic,
        intent = "planning",
        entities = setOf("SignalASI"),
        complexity = 0.7
    )

    private fun reduction(
        event: GlobalConversationEvent,
        understanding: GlobalUnderstanding
    ): GlobalWorldReduction = GlobalWorldModelReducer.reduce(
        PersonalWorldModel(),
        event,
        understanding
    )
}
