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
    fun replacementDoesNotSupersedeUnrelatedFactsWithAGenericTopic() {
        val tts = item(
            "tts-old",
            GlobalWorldItemKind.STATE,
            topic = "SignalASI",
            value = "SignalASI supports TTS",
            eventId = "tts-event"
        )
        val ocr = item(
            "ocr-current",
            GlobalWorldItemKind.STATE,
            topic = "SignalASI",
            value = "SignalASI supports OCR",
            eventId = "ocr-event"
        )
        val removedTts = item(
            "tts-removed",
            GlobalWorldItemKind.STATE,
            topic = "SignalASI",
            value = "SignalASI removed TTS",
            eventId = "tts-removed-event"
        )
        val event = event("tts-removed-event", removedTts.value)
        val result = GlobalMemoryEvolutionPolicy.evolve(
            PersonalWorldModel(items = listOf(tts, ocr)),
            GlobalWorldReduction(
                PersonalWorldModel(items = listOf(tts, ocr, removedTts), processedEventIds = listOf(event.id)),
                listOf(removedTts),
                emptyList()
            ),
            GlobalMemoryInbox(),
            event,
            understanding(event, "SignalASI")
        )

        assertEquals(GlobalWorldItemStatus.SUPERSEDED, result.reduction.world.items.first { it.id == tts.id }.status)
        assertEquals(GlobalWorldItemStatus.ACTIVE, result.reduction.world.items.first { it.id == ocr.id }.status)
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
        assertEquals(GlobalMemoryTemporalState.CURRENT, approvedWorld.items.single().temporalState)
        assertEquals(GlobalMemoryCandidateStatus.APPROVED, approvedInbox.candidates.single().status)
        assertEquals(GlobalMemoryTemporalState.CURRENT, approvedInbox.candidates.single().temporalState)
        val compiled = GlobalMemoryPromptCompiler.compile(
            approvedWorld,
            GlobalTopicProjectGraph(),
            GlobalEntityMemoryGraph(),
            "What response style do I prefer?",
            "conversation-a"
        )
        assertTrue(compiled.contains("Prefer concise responses"))
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
    fun approvingAChangedPreferenceDeprecatesThePreviousPreference() {
        val previous = item(
            "previous-preference",
            GlobalWorldItemKind.PREFERENCE,
            layer = GlobalWorldLayer.USER,
            topic = "Response style",
            value = "Prefer detailed responses",
            eventId = "previous-preference-event"
        )
        val source = event(
            "new-preference-event",
            "Prefer concise responses",
            metadata = mapOf("memory_kind" to AgentMemoryKind.PREFERENCE.name)
        )
        val incoming = item(
            "new-preference",
            GlobalWorldItemKind.PREFERENCE,
            layer = GlobalWorldLayer.USER,
            topic = "Response style",
            value = source.content,
            eventId = source.id
        )
        val evolved = GlobalMemoryEvolutionPolicy.evolve(
            PersonalWorldModel(items = listOf(previous)),
            GlobalWorldReduction(
                world = PersonalWorldModel(items = listOf(previous, incoming), processedEventIds = listOf(source.id)),
                changedItems = listOf(incoming),
                conflicts = emptyList()
            ),
            GlobalMemoryInbox(),
            source,
            understanding(source, incoming.topic)
        )
        val (approvedWorld, _) = GlobalMemoryEvolutionPolicy.approve(
            evolved.reduction.world,
            evolved.inbox,
            evolved.inbox.pending().single().id,
            nowMillis = 3_000L
        )
        val old = approvedWorld.items.first { it.id == previous.id }
        val current = approvedWorld.items.first { it.id == incoming.id }

        assertEquals(GlobalWorldItemStatus.SUPERSEDED, old.status)
        assertEquals(GlobalMemoryTemporalState.DEPRECATED, old.temporalState)
        assertEquals(GlobalWorldItemStatus.ACTIVE, current.status)
        assertEquals(GlobalMemoryTemporalState.CURRENT, current.temporalState)
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
    fun semanticallyEquivalentEvidenceStrengthensAcceptedMemory() {
        val previous = item(
            "linux-old",
            GlobalWorldItemKind.FACT,
            topic = "Linux runtime support",
            value = "SignalASI supports the Linux runtime",
            eventId = "event-old"
        ).copy(lastSeenAtMillis = 1_000L)
        val incoming = item(
            "linux-new",
            GlobalWorldItemKind.FACT,
            topic = "Linux runtime support",
            value = "SignalASI supports Linux runtime",
            eventId = "event-new"
        )
        val event = event("event-new", incoming.value)
        val result = GlobalMemoryEvolutionPolicy.evolve(
            PersonalWorldModel(items = listOf(previous)),
            GlobalWorldReduction(
                PersonalWorldModel(items = listOf(previous, incoming), processedEventIds = listOf(event.id)),
                listOf(incoming),
                emptyList()
            ),
            GlobalMemoryInbox(),
            event,
            understanding(event, incoming.topic)
        )

        assertEquals(GlobalMemoryEvolutionAction.STRENGTHEN, result.candidates.single().action)
        assertEquals(1, result.reduction.world.items.count { it.status == GlobalWorldItemStatus.ACTIVE })
        assertEquals(2, result.reduction.world.items.single().evidenceCount)
        assertTrue(result.reduction.world.items.single().confidence > previous.confidence)
    }

    @Test
    fun plannedMemoryRemainsDistinctFromCurrentStateInCompiledContext() {
        val event = event("planned-event", "Plan to add offline OCR")
        val goal = item(
            "planned-goal",
            GlobalWorldItemKind.GOAL,
            topic = "Offline OCR",
            value = event.content,
            eventId = event.id
        )
        val result = GlobalMemoryEvolutionPolicy.evolve(
            PersonalWorldModel(),
            reduction(event, goal),
            GlobalMemoryInbox(),
            event,
            understanding(event, goal.topic)
        )

        assertEquals(GlobalMemoryTemporalState.PLANNED, result.reduction.world.items.single().temporalState)
        val context = GlobalMemoryPromptCompiler.compile(
            result.reduction.world,
            GlobalTopicProjectGraph(),
            GlobalEntityMemoryGraph(),
            "What is the next goal for offline OCR?",
            "conversation-a"
        )
        assertTrue(context.contains("[planned/"))
        assertFalse(context.contains("[current/topic/goal]"))
    }

    @Test
    fun causalDeletionRemovesSourceCandidateFromInbox() {
        val source = event(
            "candidate-source",
            "Prefer concise answers",
            metadata = mapOf("memory_kind" to AgentMemoryKind.PREFERENCE.name)
        )
        val preference = item(
            "candidate-item",
            GlobalWorldItemKind.PREFERENCE,
            layer = GlobalWorldLayer.USER,
            topic = "Response style",
            value = source.content,
            eventId = source.id
        )
        val first = GlobalMemoryEvolutionPolicy.evolve(
            PersonalWorldModel(),
            reduction(source, preference),
            GlobalMemoryInbox(),
            source,
            understanding(source, preference.topic)
        )
        val deletion = event(
            "candidate-delete",
            "",
            metadata = mapOf("deleted_event_id" to source.id)
        ).copy(type = GlobalConversationEventType.MESSAGE_DELETED)
        val deleted = GlobalMemoryEvolutionPolicy.evolve(
            first.reduction.world,
            GlobalWorldReduction(first.reduction.world, emptyList(), emptyList()),
            first.inbox,
            deletion,
            understanding(deletion, preference.topic)
        )

        assertTrue(deleted.inbox.candidates.none { it.sourceEventId == source.id })
    }

    @Test
    fun evolutionActionsTemporalStateAndThemesSurvivePersistenceRoundTrip() {
        val persistedItem = item(
            "persisted-item",
            GlobalWorldItemKind.GOAL,
            topic = "Offline OCR",
            value = "Add offline OCR",
            eventId = "persisted-event"
        ).copy(temporalState = GlobalMemoryTemporalState.PLANNED)
        val candidate = GlobalMemoryCandidate(
            id = "persisted-candidate",
            sourceEventId = "persisted-event",
            conversationId = "conversation-a",
            kind = GlobalMemoryCandidateKind.PROJECT_STATE,
            temporalState = GlobalMemoryTemporalState.PLANNED,
            risk = GlobalMemoryCandidateRisk.LOW,
            status = GlobalMemoryCandidateStatus.AUTO_MERGED,
            action = GlobalMemoryEvolutionAction.STRENGTHEN,
            targetItemIds = listOf("existing-item"),
            item = persistedItem,
            reason = "test",
            createdAtMillis = 4_000L
        )
        val inbox = GlobalMemoryInbox(listOf(candidate), listOf("persisted-event"), 4_000L)
        val restoredInbox = GlobalMemoryEvolutionCodec.decodeInbox(
            GlobalMemoryEvolutionCodec.encodeInbox(inbox).toString()
        )
        assertEquals(GlobalMemoryEvolutionAction.STRENGTHEN, restoredInbox.candidates.single().action)
        assertEquals(listOf("existing-item"), restoredInbox.candidates.single().targetItemIds)
        assertEquals(GlobalMemoryTemporalState.PLANNED, restoredInbox.candidates.single().item.temporalState)

        val report = GlobalMemoryAuditReport(
            themes = listOf(GlobalMemoryTheme(
                id = "theme-id",
                title = "Offline OCR",
                itemStableKeys = listOf(persistedItem.stableKey),
                itemCount = 3,
                evidenceCount = 5,
                conversationCount = 2,
                confidence = 0.88,
                lastUpdatedAtMillis = 5_000L
            )),
            auditedItemCount = 3,
            createdAtMillis = 5_000L
        )
        val restoredReport = GlobalMemoryEvolutionCodec.decodeAudit(
            GlobalMemoryEvolutionCodec.encodeAudit(report).toString()
        )
        assertEquals(report.themes, restoredReport.themes)
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
    fun graphBuildsTypedComponentStateConnectionAndPreferenceRelations() {
        val statements = listOf(
            "SignalASI contains Linux Runtime" to GlobalEntityRelationKind.HAS_COMPONENT,
            "Phone connected to SignalASI" to GlobalEntityRelationKind.CONNECTED_TO,
            "User prefers concise responses" to GlobalEntityRelationKind.PREFERS,
            "Linux Runtime status is ready" to GlobalEntityRelationKind.HAS_STATE
        )
        var graph = GlobalEntityMemoryGraph()
        statements.forEachIndexed { index, (statement, _) ->
            val event = event("typed-relation-$index", statement)
            val fact = item(
                "typed-item-$index",
                GlobalWorldItemKind.FACT,
                topic = statement.substringBefore(' '),
                value = statement,
                eventId = event.id
            )
            graph = GlobalEntityMemoryGraphReducer.reduce(
                graph,
                event,
                understanding(event, fact.topic),
                reduction(event, fact)
            )
        }

        val kinds = graph.relations.map(GlobalEntityRelation::kind).toSet()
        statements.forEach { (_, expected) -> assertTrue(expected in kinds) }
    }

    @Test
    fun removedFeatureBecomesDeprecatedInEntityGraph() {
        val enabled = event("screen-enabled", "Screen understanding status is enabled")
        val enabledItem = item(
            "screen-enabled-item",
            GlobalWorldItemKind.STATE,
            topic = "Screen understanding",
            value = enabled.content,
            eventId = enabled.id
        )
        val first = GlobalEntityMemoryGraphReducer.reduce(
            GlobalEntityMemoryGraph(),
            enabled,
            understanding(enabled, enabledItem.topic, setOf(enabledItem.topic)),
            reduction(enabled, enabledItem)
        )
        val removed = event("screen-removed", "Screen understanding has been removed")
        val removedItem = item(
            "screen-removed-item",
            GlobalWorldItemKind.STATE,
            topic = "Screen understanding",
            value = removed.content,
            eventId = removed.id
        )
        val evolved = GlobalEntityMemoryGraphReducer.reduce(
            first,
            removed,
            understanding(removed, removedItem.topic, setOf(removedItem.topic)),
            reduction(removed, removedItem)
        )

        assertEquals(
            GlobalMemoryTemporalState.DEPRECATED,
            evolved.nodes.first { it.label.equals("Screen understanding", ignoreCase = true) }.temporalState
        )
        assertFalse(evolved.relations.any {
            it.kind == GlobalEntityRelationKind.OWNS &&
                it.temporalState == GlobalMemoryTemporalState.CURRENT
        })
    }

    @Test
    fun removedEntityRetiresInboundAndOutboundRelationsFromCurrentGraph() {
        val supportEvent = event("support-event", "SignalASI supports Screen understanding")
        val supportItem = item(
            "support-item",
            GlobalWorldItemKind.FACT,
            topic = "SignalASI screen features",
            value = supportEvent.content,
            eventId = supportEvent.id
        )
        val initial = GlobalEntityMemoryGraphReducer.reduce(
            GlobalEntityMemoryGraph(),
            supportEvent,
            understanding(supportEvent, supportItem.topic, setOf("SignalASI", "Screen understanding")),
            reduction(supportEvent, supportItem)
        )
        assertTrue(initial.relations.any {
            it.kind == GlobalEntityRelationKind.SUPPORTS &&
                it.temporalState == GlobalMemoryTemporalState.CURRENT
        })

        val removalEvent = event("remove-event", "Screen understanding has been removed")
        val removalItem = item(
            "remove-item",
            GlobalWorldItemKind.STATE,
            topic = "Screen understanding",
            value = removalEvent.content,
            eventId = removalEvent.id
        )
        val evolved = GlobalEntityMemoryGraphReducer.reduce(
            initial,
            removalEvent,
            understanding(removalEvent, removalItem.topic, setOf("Screen understanding")),
            reduction(removalEvent, removalItem)
        )
        val support = evolved.relations.first { it.kind == GlobalEntityRelationKind.SUPPORTS }

        assertEquals(GlobalMemoryTemporalState.DEPRECATED, support.temporalState)
        assertTrue(support.validUntilMillis > 0L)
        assertFalse(
            evolved.relevant("Does SignalASI support Screen understanding?").relations.any {
                it.kind == GlobalEntityRelationKind.SUPPORTS
            }
        )
        assertTrue(
            evolved.relevant(
                "Did SignalASI previously support Screen understanding?",
                includeHistorical = true,
                historicalOnly = true
            ).relations.any { it.kind == GlobalEntityRelationKind.SUPPORTS }
        )
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

        val before = PersonalWorldModel(items = listOf(expired, repeated))
        val (world, report) = GlobalMemoryCritic.audit(
            before,
            GlobalMemoryInbox(),
            nowMillis = 2_000L
        )

        assertEquals(GlobalWorldItemStatus.SUPERSEDED, world.items.first { it.id == "expired" }.status)
        assertTrue(report.findings.any { it.kind == GlobalMemoryAuditFindingKind.EXPIRED })
        assertTrue(report.findings.any { it.kind == GlobalMemoryAuditFindingKind.SKILL_CANDIDATE })
        val records = GlobalMemoryEvolutionPolicy.auditRecords(before, world, report.createdAtMillis)
        assertEquals(GlobalMemoryEvolutionAction.SUPERSEDE, records.single().action)
        assertEquals(GlobalMemoryTemporalState.DEPRECATED, records.single().temporalState)
    }

    @Test
    fun criticConsolidatesDuplicatesAndBuildsLongTermThemes() {
        val duplicateOne = item(
            "duplicate-one",
            GlobalWorldItemKind.FACT,
            topic = "SignalASI runtime",
            value = "SignalASI supports Linux runtime",
            eventId = "duplicate-event-one"
        ).copy(lastSeenAtMillis = 4_000L)
        val duplicateTwo = item(
            "duplicate-two",
            GlobalWorldItemKind.FACT,
            topic = "SignalASI runtime",
            value = "SignalASI supports the Linux runtime",
            eventId = "duplicate-event-two"
        ).copy(conversationIds = setOf("conversation-b"), lastSeenAtMillis = 3_000L)
        val decision = item(
            "runtime-decision",
            GlobalWorldItemKind.DECISION,
            topic = "SignalASI runtime",
            value = "The runtime is installed on demand",
            eventId = "decision-event"
        ).copy(conversationIds = setOf("conversation-c"), lastSeenAtMillis = 2_000L)
        val goal = item(
            "runtime-goal",
            GlobalWorldItemKind.GOAL,
            topic = "SignalASI runtime",
            value = "Add verified runtime packages",
            eventId = "goal-event"
        ).copy(conversationIds = setOf("conversation-d"), lastSeenAtMillis = 1_000L)

        val before = PersonalWorldModel(items = listOf(duplicateOne, duplicateTwo, decision, goal))
        val (world, report) = GlobalMemoryCritic.audit(
            before,
            GlobalMemoryInbox(),
            nowMillis = 5_000L
        )

        assertEquals(1, world.items.count { it.kind == GlobalWorldItemKind.FACT && it.status == GlobalWorldItemStatus.ACTIVE })
        assertTrue(report.findings.any { it.kind == GlobalMemoryAuditFindingKind.DUPLICATE })
        assertTrue(report.themes.any { it.title == "SignalASI runtime" && it.itemCount >= 3 })
        val records = GlobalMemoryEvolutionPolicy.auditRecords(before, world, report.createdAtMillis)
        assertEquals(GlobalMemoryEvolutionAction.CONSOLIDATE, records.single().action)
        assertTrue(records.single().resultingItemId.isNotBlank())
    }

    @Test
    fun explicitRelationshipIsClassifiedForGraphProjection() {
        val event = event("relation-event", "SignalASI supports Linux Runtime")
        val fact = item(
            "relation-item",
            GlobalWorldItemKind.FACT,
            topic = "SignalASI",
            value = event.content,
            eventId = event.id
        )
        val result = GlobalMemoryEvolutionPolicy.evolve(
            PersonalWorldModel(),
            reduction(event, fact),
            GlobalMemoryInbox(),
            event,
            understanding(event, "SignalASI", setOf("SignalASI", "Linux Runtime"))
        )

        assertEquals(GlobalMemoryCandidateKind.RELATION, result.candidates.single().kind)
        assertEquals(GlobalMemoryEvolutionAction.LINK, result.candidates.single().action)
    }

    @Test
    fun repeatedWorkflowBecomesReviewedSkillOpportunity() {
        val event = event(
            "workflow-event",
            "Run verification before publishing",
            metadata = mapOf("memory_kind" to AgentMemoryKind.WORKFLOW.name)
        )
        val workflow = item(
            "workflow-memory",
            GlobalWorldItemKind.DECISION,
            topic = "Release workflow",
            value = event.content,
            eventId = event.id
        ).copy(evidenceCount = 3)
        val result = GlobalMemoryEvolutionPolicy.evolve(
            PersonalWorldModel(),
            reduction(event, workflow),
            GlobalMemoryInbox(),
            event,
            understanding(event, workflow.topic)
        )

        assertTrue(result.reduction.world.items.isEmpty())
        assertEquals(GlobalMemoryCandidateKind.SKILL_OPPORTUNITY, result.inbox.pending().single().kind)
        assertEquals(GlobalMemoryEvolutionAction.CONSOLIDATE, result.inbox.pending().single().action)
    }

    @Test
    fun explicitCorrectionSupersedesOldStateAndProducesEvolutionRecord() {
        val previous = item(
            "settings-old",
            GlobalWorldItemKind.DECISION,
            topic = "Settings page name",
            value = "The settings page is named Settings",
            eventId = "settings-old-event"
        )
        val incoming = item(
            "settings-new",
            GlobalWorldItemKind.DECISION,
            topic = "Settings page name",
            value = "The settings page should be Control Center",
            eventId = "settings-correction"
        )
        val event = event(
            "settings-correction",
            "I was wrong; the settings page name should be Control Center"
        )
        val result = GlobalMemoryEvolutionPolicy.evolve(
            PersonalWorldModel(items = listOf(previous)),
            GlobalWorldReduction(
                PersonalWorldModel(items = listOf(previous, incoming), processedEventIds = listOf(event.id)),
                listOf(incoming),
                emptyList()
            ),
            GlobalMemoryInbox(),
            event,
            understanding(event, incoming.topic)
        )

        assertEquals(GlobalWorldItemStatus.SUPERSEDED, result.reduction.world.items.first { it.id == previous.id }.status)
        assertEquals(GlobalWorldItemStatus.ACTIVE, result.reduction.world.items.first { it.id == incoming.id }.status)
        val record = result.records.single()
        assertEquals(GlobalMemoryEvolutionAction.SUPERSEDE, record.action)
        assertEquals(GlobalMemoryEvolutionOutcome.APPLIED, record.outcome)
        assertEquals(listOf(previous.id), record.targetItemIds)
        assertEquals(incoming.id, record.resultingItemId)
    }

    @Test
    fun privateEvolutionRecordNeverContainsPrivateSubjectOrValue() {
        val secret = "account-secret-123"
        val event = event(
            "private-record",
            secret,
            sensitivity = GlobalConversationSensitivity.SESSION_PRIVATE
        )
        val privateItem = item(
            "private-record-item",
            GlobalWorldItemKind.FACT,
            topic = "Private $secret",
            value = secret,
            eventId = event.id
        )
        val result = GlobalMemoryEvolutionPolicy.evolve(
            PersonalWorldModel(),
            reduction(event, privateItem),
            GlobalMemoryInbox(),
            event,
            understanding(event, privateItem.topic)
        )

        val record = result.records.single()
        assertEquals(GlobalMemoryEvolutionOutcome.PRIVATE_BLOCKED, record.outcome)
        assertFalse(record.subject.contains(secret))
        assertTrue(record.resultingItemId.isBlank())
    }

    @Test
    fun reviewOutcomeAndEvolutionRecordsSurvivePersistenceRoundTrip() {
        val event = event(
            "review-record",
            "Prefer concise responses",
            metadata = mapOf("memory_kind" to AgentMemoryKind.PREFERENCE.name)
        )
        val preference = item(
            "review-record-item",
            GlobalWorldItemKind.PREFERENCE,
            layer = GlobalWorldLayer.USER,
            topic = "Response style",
            value = event.content,
            eventId = event.id
        )
        val evolved = GlobalMemoryEvolutionPolicy.evolve(
            PersonalWorldModel(),
            reduction(event, preference),
            GlobalMemoryInbox(),
            event,
            understanding(event, preference.topic)
        )
        val candidate = evolved.inbox.pending().single()
        val approved = GlobalMemoryEvolutionPolicy.reviewRecord(
            candidate,
            GlobalMemoryEvolutionOutcome.APPROVED,
            nowMillis = 8_000L
        )

        val restored = GlobalMemoryEvolutionCodec.decodeRecords(
            GlobalMemoryEvolutionCodec.encodeRecords(evolved.records + approved).toString()
        )

        assertEquals(2, restored.size)
        assertEquals(GlobalMemoryEvolutionOutcome.WAITING_REVIEW, restored.first().outcome)
        assertEquals(GlobalMemoryEvolutionOutcome.APPROVED, restored.last().outcome)
        assertEquals(GlobalMemoryTemporalState.CURRENT, restored.last().temporalState)
        assertEquals(8_000L, restored.last().createdAtMillis)
    }

    @Test
    fun criticClassifiesStaleInboxItemsSeparatelyFromLowConfidenceMemory() {
        val source = event(
            "stale-candidate-event",
            "Prefer concise replies",
            metadata = mapOf("memory_kind" to AgentMemoryKind.PREFERENCE.name)
        )
        val preference = item(
            "stale-preference",
            GlobalWorldItemKind.PREFERENCE,
            layer = GlobalWorldLayer.USER,
            topic = "Response style",
            value = source.content,
            eventId = source.id
        )
        val evolved = GlobalMemoryEvolutionPolicy.evolve(
            PersonalWorldModel(),
            reduction(source, preference),
            GlobalMemoryInbox(),
            source,
            understanding(source, preference.topic)
        )
        val monthLater = 32L * 24L * 60L * 60L * 1_000L

        val (_, report) = GlobalMemoryCritic.audit(
            PersonalWorldModel(),
            evolved.inbox,
            nowMillis = monthLater
        )

        assertTrue(report.findings.any { it.kind == GlobalMemoryAuditFindingKind.STALE_CANDIDATE })
        assertFalse(report.findings.any { it.kind == GlobalMemoryAuditFindingKind.LOW_CONFIDENCE_REUSED })
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
        assertEquals(
            GlobalMemoryQueryType.PERSONAL_IDENTITY,
            GlobalMemoryQueryPlanner.plan("Who am I?").type
        )
        assertEquals(
            GlobalMemoryQueryType.SECURITY_STATE,
            GlobalMemoryQueryPlanner.plan("What is my current privacy and authorization state?").type
        )
        assertEquals(
            GlobalMemoryQueryType.RELATIONSHIP,
            GlobalMemoryQueryPlanner.plan("How is the gateway connected to the phone?").type
        )
        assertEquals(
            GlobalMemoryQueryType.LONG_TERM_GOAL,
            GlobalMemoryQueryPlanner.plan("What is the next long-term milestone?").type
        )
        assertEquals(
            GlobalMemoryQueryType.TOOL_EVIDENCE,
            GlobalMemoryQueryPlanner.plan("What output did the command produce?").type
        )
    }

    @Test
    fun identityQueryRetrievesApprovedShareableUserMemoryOnly() {
        val identity = item(
            "identity",
            GlobalWorldItemKind.FACT,
            layer = GlobalWorldLayer.USER,
            topic = "Profile",
            value = "The user display name is Ada",
            eventId = "identity-event"
        )
        val privateIdentity = item(
            "private-identity",
            GlobalWorldItemKind.FACT,
            layer = GlobalWorldLayer.USER,
            topic = "Private profile",
            value = "private-identity-value",
            eventId = "private-identity-event",
            visibility = GlobalWorldContextVisibility.LOCAL_ONLY
        )

        val prompt = GlobalMemoryPromptCompiler.compile(
            PersonalWorldModel(items = listOf(identity, privateIdentity)),
            GlobalTopicProjectGraph(),
            GlobalEntityMemoryGraph(),
            "Who am I?",
            "conversation-a"
        )

        assertTrue(prompt.contains("The user display name is Ada"))
        assertFalse(prompt.contains("private-identity-value"))
    }

    @Test
    fun plannerCombinesDomainAndTemporalIntentWithoutLosingEither() {
        val plan = GlobalMemoryQueryPlanner.plan(
            "What changed between the previous and current Project SignalASI device status?"
        )

        assertEquals(GlobalMemoryQueryType.HISTORICAL_DECISION, plan.type)
        assertTrue(GlobalMemoryQueryType.PROJECT_STATE in plan.types)
        assertTrue(GlobalMemoryQueryType.DEVICE_CAPABILITY in plan.types)
        assertEquals(GlobalMemoryTemporalQueryScope.CURRENT_AND_HISTORY, plan.temporalScope)
        assertTrue(GlobalWorldItemKind.DECISION in plan.preferredKinds)
        assertTrue(GlobalWorldItemKind.STATE in plan.preferredKinds)
    }

    @Test
    fun plannerInfersTypedGraphRelationHints() {
        val plan = GlobalMemoryQueryPlanner.plan(
            "What model does the phone support and which runtime does it depend on?"
        )

        assertTrue(GlobalMemoryQueryType.RELATIONSHIP in plan.types)
        assertTrue(GlobalEntityRelationKind.SUPPORTS in plan.preferredRelationKinds)
        assertTrue(GlobalEntityRelationKind.DEPENDS_ON in plan.preferredRelationKinds)
    }

    @Test
    fun memoryAuditProvidesAStableDailyWakeDeadline() {
        val day = 24L * 60L * 60L * 1_000L

        assertEquals(1_000L, GlobalMemoryCritic.nextAuditAt(0L, 1_000L))
        assertEquals(day + 500L, GlobalMemoryCritic.nextAuditAt(500L, 1_000L))
        assertEquals(day + 501L, GlobalMemoryCritic.nextAuditAt(500L, day + 501L))
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
