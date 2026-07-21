package com.signalasi.chat

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

enum class GlobalMemoryTemporalState {
    HISTORICAL,
    CURRENT,
    PLANNED,
    DEPRECATED,
    PENDING,
    CONFLICTED
}

enum class GlobalMemoryCandidateKind {
    FACT,
    PREFERENCE,
    IDENTITY,
    DECISION,
    PROJECT_STATE,
    GOAL,
    RELATION,
    SKILL_OPPORTUNITY
}

enum class GlobalMemoryCandidateRisk {
    LOW,
    REVIEW_REQUIRED,
    PRIVATE_BLOCKED
}

enum class GlobalMemoryCandidateStatus {
    AUTO_MERGED,
    PENDING_REVIEW,
    CONFLICTED,
    APPROVED,
    REJECTED,
    SUPERSEDED
}

enum class GlobalMemoryEvolutionAction {
    CREATE,
    STRENGTHEN,
    SUPERSEDE,
    LINK,
    CONSOLIDATE,
    REVIEW_CONFLICT,
    BLOCK_PRIVATE
}

data class GlobalMemoryCandidate(
    val id: String,
    val sourceEventId: String,
    val conversationId: String,
    val kind: GlobalMemoryCandidateKind,
    val temporalState: GlobalMemoryTemporalState,
    val risk: GlobalMemoryCandidateRisk,
    val status: GlobalMemoryCandidateStatus,
    val action: GlobalMemoryEvolutionAction = GlobalMemoryEvolutionAction.CREATE,
    val targetItemIds: List<String> = emptyList(),
    val item: GlobalWorldItem,
    val reason: String,
    val createdAtMillis: Long = System.currentTimeMillis(),
    val reviewedAtMillis: Long = 0L
)

data class GlobalMemoryInbox(
    val candidates: List<GlobalMemoryCandidate> = emptyList(),
    val processedEventIds: List<String> = emptyList(),
    val updatedAtMillis: Long = 0L
) {
    fun pending(): List<GlobalMemoryCandidate> = candidates
        .filter { it.status in setOf(GlobalMemoryCandidateStatus.PENDING_REVIEW, GlobalMemoryCandidateStatus.CONFLICTED) }
        .sortedByDescending(GlobalMemoryCandidate::createdAtMillis)
}

data class GlobalMemoryEvolutionResult(
    val reduction: GlobalWorldReduction,
    val inbox: GlobalMemoryInbox,
    val candidates: List<GlobalMemoryCandidate>,
    val records: List<GlobalMemoryEvolutionRecord> = emptyList()
)

enum class GlobalMemoryEvolutionOutcome {
    APPLIED,
    WAITING_REVIEW,
    CONFLICTED,
    PRIVATE_BLOCKED,
    APPROVED,
    REJECTED
}

data class GlobalMemoryEvolutionRecord(
    val id: String,
    val sourceEventId: String,
    val conversationId: String,
    val candidateId: String,
    val kind: GlobalMemoryCandidateKind,
    val action: GlobalMemoryEvolutionAction,
    val outcome: GlobalMemoryEvolutionOutcome,
    val temporalState: GlobalMemoryTemporalState,
    val subject: String,
    val targetItemIds: List<String>,
    val resultingItemId: String,
    val evidenceCount: Int,
    val createdAtMillis: Long
)

object GlobalMemoryEvolutionPolicy {
    fun evolve(
        worldBefore: PersonalWorldModel,
        reduction: GlobalWorldReduction,
        inbox: GlobalMemoryInbox,
        event: GlobalConversationEvent,
        understanding: GlobalUnderstanding
    ): GlobalMemoryEvolutionResult {
        val retractedInbox = retractCandidates(inbox, event)
        if (event.id in retractedInbox.processedEventIds) {
            return GlobalMemoryEvolutionResult(reduction, retractedInbox, emptyList())
        }
        val candidates = classifyActions(
            worldBefore,
            extractCandidates(event, understanding, reduction),
            event
        )
        val gated = gateReduction(worldBefore, reduction, candidates, event)
        val evolved = applyTemporalEvolution(gated, candidates, event)
        val updatedInbox = retractedInbox.copy(
            candidates = mergeCandidates(retractedInbox.candidates, candidates),
            processedEventIds = (retractedInbox.processedEventIds + event.id)
                .filter(String::isNotBlank)
                .distinct()
                .takeLast(MAX_PROCESSED_EVENT_IDS),
            updatedAtMillis = maxOf(inbox.updatedAtMillis, event.timestampMillis)
        )
        return GlobalMemoryEvolutionResult(
            reduction = evolved,
            inbox = updatedInbox,
            candidates = candidates,
            records = candidates.map(::recordForCandidate)
        )
    }

    fun reviewRecord(
        candidate: GlobalMemoryCandidate,
        outcome: GlobalMemoryEvolutionOutcome,
        nowMillis: Long = System.currentTimeMillis()
    ): GlobalMemoryEvolutionRecord {
        require(outcome in setOf(GlobalMemoryEvolutionOutcome.APPROVED, GlobalMemoryEvolutionOutcome.REJECTED))
        return recordForCandidate(candidate, outcome, nowMillis)
    }

    fun auditRecords(
        worldBefore: PersonalWorldModel,
        worldAfter: PersonalWorldModel,
        nowMillis: Long
    ): List<GlobalMemoryEvolutionRecord> {
        val afterById = worldAfter.items.associateBy(GlobalWorldItem::id)
        return worldBefore.items.mapNotNull { previous ->
            val updated = afterById[previous.id] ?: return@mapNotNull null
            if (previous.status == updated.status && previous.temporalState == updated.temporalState) {
                return@mapNotNull null
            }
            if (updated.status != GlobalWorldItemStatus.SUPERSEDED &&
                updated.temporalState != GlobalMemoryTemporalState.DEPRECATED
            ) return@mapNotNull null
            val consolidatedInto = worldAfter.items.firstOrNull { candidate ->
                candidate.id != updated.id &&
                    candidate.status == GlobalWorldItemStatus.ACTIVE &&
                    candidate.layer == updated.layer &&
                    equivalentAssertion(candidate, updated)
            }
            val sourceEventId = updated.evidenceProvenance.maxByOrNull(GlobalEvidenceRef::timestampMillis)?.eventId
                ?: updated.evidenceEventIds.lastOrNull()
                ?: "memory-audit:${updated.id}"
            val action = if (consolidatedInto == null) {
                GlobalMemoryEvolutionAction.SUPERSEDE
            } else GlobalMemoryEvolutionAction.CONSOLIDATE
            GlobalMemoryEvolutionRecord(
                id = GlobalAgentText.stableKey(
                    "memory-audit-record",
                    updated.id,
                    action.name,
                    updated.temporalState.name,
                    consolidatedInto?.id.orEmpty(),
                    updated.lastSeenAtMillis.toString()
                ),
                sourceEventId = sourceEventId,
                conversationId = updated.conversationIds.firstOrNull().orEmpty(),
                candidateId = "",
                kind = candidateKind(updated),
                action = action,
                outcome = GlobalMemoryEvolutionOutcome.APPLIED,
                temporalState = updated.temporalState,
                subject = updated.topic.replace(Regex("\\s+"), " ").trim().take(160)
                    .ifBlank { updated.kind.name.lowercase(Locale.ROOT) },
                targetItemIds = listOf(updated.id),
                resultingItemId = consolidatedInto?.id.orEmpty(),
                evidenceCount = updated.evidenceCount.coerceAtLeast(0),
                createdAtMillis = nowMillis
            )
        }
    }

    fun approve(
        world: PersonalWorldModel,
        inbox: GlobalMemoryInbox,
        candidateId: String,
        nowMillis: Long = System.currentTimeMillis()
    ): Pair<PersonalWorldModel, GlobalMemoryInbox> {
        val candidate = inbox.candidates.firstOrNull {
            it.id == candidateId && it.status in setOf(
                GlobalMemoryCandidateStatus.PENDING_REVIEW,
                GlobalMemoryCandidateStatus.CONFLICTED
            )
        } ?: return world to inbox
        val approved = candidate.copy(
            status = GlobalMemoryCandidateStatus.APPROVED,
            temporalState = if (candidate.temporalState == GlobalMemoryTemporalState.CONFLICTED) {
                GlobalMemoryTemporalState.CURRENT
            } else candidate.temporalState,
            reviewedAtMillis = nowMillis
        )
        val incoming = approved.item.copy(
            status = GlobalWorldItemStatus.ACTIVE,
            temporalState = approved.temporalState,
            conflictGroupId = "",
            lastSeenAtMillis = maxOf(approved.item.lastSeenAtMillis, nowMillis)
        )
        val replaced = world.items.map { existing ->
            if (existing.id == incoming.id) return@map existing
            val sameSubject = existing.kind == incoming.kind &&
                GlobalAgentText.overlap(
                    GlobalAgentText.tokens(existing.topic),
                    GlobalAgentText.tokens(incoming.topic)
                ) >= 0.45
            if (sameSubject && existing.status in setOf(GlobalWorldItemStatus.ACTIVE, GlobalWorldItemStatus.CONFLICTED)) {
                existing.copy(status = GlobalWorldItemStatus.SUPERSEDED, conflictGroupId = "")
            } else existing
        }.filterNot { it.id == incoming.id } + incoming
        val updatedWorld = world.copy(
            items = replaced.sortedByDescending(GlobalWorldItem::lastSeenAtMillis).take(MAX_WORLD_ITEMS),
            updatedAtMillis = maxOf(world.updatedAtMillis, nowMillis)
        )
        val updatedInbox = inbox.copy(
            candidates = inbox.candidates.map { if (it.id == candidateId) approved else it },
            updatedAtMillis = maxOf(inbox.updatedAtMillis, nowMillis)
        )
        return updatedWorld to updatedInbox
    }

    fun reject(
        inbox: GlobalMemoryInbox,
        candidateId: String,
        nowMillis: Long = System.currentTimeMillis()
    ): GlobalMemoryInbox {
        var changed = false
        val candidates = inbox.candidates.map { candidate ->
            if (candidate.id == candidateId && candidate.status in setOf(
                    GlobalMemoryCandidateStatus.PENDING_REVIEW,
                    GlobalMemoryCandidateStatus.CONFLICTED
                )
            ) {
                changed = true
                candidate.copy(status = GlobalMemoryCandidateStatus.REJECTED, reviewedAtMillis = nowMillis)
            } else candidate
        }
        return if (changed) inbox.copy(candidates = candidates, updatedAtMillis = nowMillis) else inbox
    }

    private fun extractCandidates(
        event: GlobalConversationEvent,
        understanding: GlobalUnderstanding,
        reduction: GlobalWorldReduction
    ): List<GlobalMemoryCandidate> {
        val replacement = replacementSignal(event.content)
        return reduction.changedItems.asSequence()
            .filter { item -> item.evidenceEventIds.contains(event.id) || item.evidenceProvenance.any { it.eventId == event.id } }
            .distinctBy(GlobalWorldItem::stableKey)
            .map { item ->
                val kind = candidateKind(event, item, understanding)
                val private = event.sensitivity == GlobalConversationSensitivity.SESSION_PRIVATE ||
                    AgentLearningAnalyzer.containsSensitiveData(item.value)
                val conflict = item.status == GlobalWorldItemStatus.CONFLICTED
                val review = requiresReview(event, item, kind)
                val risk = when {
                    private -> GlobalMemoryCandidateRisk.PRIVATE_BLOCKED
                    review || (conflict && !replacement) -> GlobalMemoryCandidateRisk.REVIEW_REQUIRED
                    else -> GlobalMemoryCandidateRisk.LOW
                }
                val status = when {
                    private -> GlobalMemoryCandidateStatus.REJECTED
                    conflict && !replacement -> GlobalMemoryCandidateStatus.CONFLICTED
                    review -> GlobalMemoryCandidateStatus.PENDING_REVIEW
                    else -> GlobalMemoryCandidateStatus.AUTO_MERGED
                }
                val temporal = temporalState(event, item, replacement, status)
                val safeItem = if (private) item.copy(
                    topic = "Private memory candidate",
                    value = "",
                    contextVisibility = GlobalWorldContextVisibility.LOCAL_ONLY
                ) else item.copy(temporalState = temporal)
                GlobalMemoryCandidate(
                    id = GlobalAgentText.stableKey("memory-candidate", event.id, item.stableKey),
                    sourceEventId = event.id,
                    conversationId = event.conversationId,
                    kind = kind,
                    temporalState = temporal,
                    risk = risk,
                    status = status,
                    item = safeItem,
                    reason = when {
                        private -> "private_content_not_persisted"
                        conflict && !replacement -> "conflicting_evidence_requires_review"
                        review -> "identity_preference_or_safety_requires_review"
                        replacement -> "explicit_state_evolution"
                        else -> "low_risk_evidence_auto_merged"
                    },
                    createdAtMillis = event.timestampMillis
                )
            }
            .take(MAX_CANDIDATES_PER_EVENT)
            .toList()
    }

    private fun gateReduction(
        worldBefore: PersonalWorldModel,
        reduction: GlobalWorldReduction,
        candidates: List<GlobalMemoryCandidate>,
        event: GlobalConversationEvent
    ): GlobalWorldReduction {
        val gatedStableKeys = candidates.filter { candidate ->
            candidate.status in setOf(
                GlobalMemoryCandidateStatus.PENDING_REVIEW,
                GlobalMemoryCandidateStatus.CONFLICTED,
                GlobalMemoryCandidateStatus.REJECTED
            )
        }.mapTo(mutableSetOf()) { it.item.stableKey }
        if (gatedStableKeys.isEmpty()) return reduction
        val beforeByStableKey = worldBefore.items.groupBy(GlobalWorldItem::stableKey)
        val gatedConflictIds = reduction.conflicts.asSequence()
            .filter { (left, right) -> left.stableKey in gatedStableKeys || right.stableKey in gatedStableKeys }
            .flatMap { (left, right) -> sequenceOf(left.id, right.id) }
            .toSet()
        val beforeById = worldBefore.items.associateBy(GlobalWorldItem::id)
        val retained = reduction.world.items.filterNot {
            it.stableKey in gatedStableKeys || it.id in gatedConflictIds
        }.toMutableList()
        gatedStableKeys.forEach { key ->
            beforeByStableKey[key].orEmpty().forEach { previous ->
                if (retained.none { it.id == previous.id }) retained += previous
            }
        }
        gatedConflictIds.forEach { id ->
            beforeById[id]?.let { previous ->
                if (retained.none { it.id == previous.id }) retained += previous
            }
        }
        return reduction.copy(
            world = reduction.world.copy(
                items = retained.sortedByDescending(GlobalWorldItem::lastSeenAtMillis).take(MAX_WORLD_ITEMS),
                processedEventIds = (reduction.world.processedEventIds + event.id).distinct().takeLast(MAX_PROCESSED_EVENT_IDS)
            ),
            changedItems = reduction.changedItems.filterNot { it.stableKey in gatedStableKeys },
            conflicts = reduction.conflicts.filterNot { (left, right) ->
                left.stableKey in gatedStableKeys || right.stableKey in gatedStableKeys
            }
        )
    }

    private fun classifyActions(
        worldBefore: PersonalWorldModel,
        candidates: List<GlobalMemoryCandidate>,
        event: GlobalConversationEvent
    ): List<GlobalMemoryCandidate> = candidates.map { candidate ->
        val subjectMatches = worldBefore.items.asSequence()
            .filter { existing ->
                existing.status in setOf(GlobalWorldItemStatus.ACTIVE, GlobalWorldItemStatus.CONFLICTED) &&
                    existing.id != candidate.item.id &&
                    existing.kind == candidate.item.kind &&
                    sameSubject(existing, candidate.item)
            }
            .sortedByDescending(GlobalWorldItem::lastSeenAtMillis)
            .toList()
        val equivalent = subjectMatches.firstOrNull { equivalentAssertion(it, candidate.item) }
        val action = when {
            candidate.risk == GlobalMemoryCandidateRisk.PRIVATE_BLOCKED -> GlobalMemoryEvolutionAction.BLOCK_PRIVATE
            candidate.status == GlobalMemoryCandidateStatus.CONFLICTED -> GlobalMemoryEvolutionAction.REVIEW_CONFLICT
            replacementSignal(event.content) && subjectMatches.isNotEmpty() -> GlobalMemoryEvolutionAction.SUPERSEDE
            equivalent != null -> GlobalMemoryEvolutionAction.STRENGTHEN
            candidate.kind == GlobalMemoryCandidateKind.RELATION -> GlobalMemoryEvolutionAction.LINK
            candidate.kind == GlobalMemoryCandidateKind.SKILL_OPPORTUNITY -> GlobalMemoryEvolutionAction.CONSOLIDATE
            else -> GlobalMemoryEvolutionAction.CREATE
        }
        val targets = when (action) {
            GlobalMemoryEvolutionAction.SUPERSEDE -> subjectMatches.map(GlobalWorldItem::id).take(MAX_EVOLUTION_TARGETS)
            GlobalMemoryEvolutionAction.STRENGTHEN -> listOfNotNull(equivalent?.id)
            else -> emptyList()
        }
        candidate.copy(action = action, targetItemIds = targets)
    }

    private fun applyTemporalEvolution(
        reduction: GlobalWorldReduction,
        candidates: List<GlobalMemoryCandidate>,
        event: GlobalConversationEvent
    ): GlobalWorldReduction {
        val accepted = candidates.filter { it.status == GlobalMemoryCandidateStatus.AUTO_MERGED }
        if (accepted.isEmpty()) return reduction
        val evolvedItems = reduction.world.items.toMutableList()
        val changed = reduction.changedItems.toMutableList()
        accepted.forEach { candidate ->
            val incomingIndex = evolvedItems.indexOfFirst { it.id == candidate.item.id }
            if (incomingIndex >= 0) {
                evolvedItems[incomingIndex] = evolvedItems[incomingIndex].copy(
                    temporalState = candidate.temporalState,
                    status = if (candidate.temporalState == GlobalMemoryTemporalState.CONFLICTED) {
                        GlobalWorldItemStatus.CONFLICTED
                    } else evolvedItems[incomingIndex].status,
                    conflictGroupId = if (candidate.temporalState == GlobalMemoryTemporalState.CONFLICTED) {
                        evolvedItems[incomingIndex].conflictGroupId
                    } else ""
                )
            }
            when (candidate.action) {
                GlobalMemoryEvolutionAction.STRENGTHEN -> {
                    val targetId = candidate.targetItemIds.firstOrNull() ?: return@forEach
                    val targetIndex = evolvedItems.indexOfFirst { it.id == targetId }
                    val currentIncomingIndex = evolvedItems.indexOfFirst { it.id == candidate.item.id }
                    if (targetIndex < 0 || currentIncomingIndex < 0 || targetIndex == currentIncomingIndex) return@forEach
                    val merged = strengthen(evolvedItems[targetIndex], evolvedItems[currentIncomingIndex])
                    evolvedItems[targetIndex] = merged
                    evolvedItems.removeAt(currentIncomingIndex)
                    changed.removeAll { it.id == candidate.item.id || it.id == targetId }
                    changed += merged
                }
                GlobalMemoryEvolutionAction.SUPERSEDE -> {
                    candidate.targetItemIds.forEach { targetId ->
                        val targetIndex = evolvedItems.indexOfFirst { it.id == targetId }
                        if (targetIndex >= 0) {
                            val previous = evolvedItems[targetIndex]
                            if (previous.lastSeenAtMillis <= candidate.item.lastSeenAtMillis) {
                                val superseded = previous.copy(
                                    status = GlobalWorldItemStatus.SUPERSEDED,
                                    temporalState = GlobalMemoryTemporalState.DEPRECATED,
                                    conflictGroupId = ""
                                )
                                evolvedItems[targetIndex] = superseded
                                changed.removeAll { it.id == targetId }
                                changed += superseded
                            }
                        }
                    }
                    val currentIncomingIndex = evolvedItems.indexOfFirst { it.id == candidate.item.id }
                    if (currentIncomingIndex >= 0) {
                        evolvedItems[currentIncomingIndex] = evolvedItems[currentIncomingIndex].copy(
                            status = GlobalWorldItemStatus.ACTIVE,
                            temporalState = GlobalMemoryTemporalState.CURRENT,
                            conflictGroupId = ""
                        )
                    }
                }
                else -> Unit
            }
        }
        val supersededIds = evolvedItems.filter {
            it.status == GlobalWorldItemStatus.SUPERSEDED ||
                it.temporalState == GlobalMemoryTemporalState.DEPRECATED
        }.mapTo(mutableSetOf(), GlobalWorldItem::id)
        return reduction.copy(
            world = reduction.world.copy(
                items = evolvedItems.sortedWith(
                    compareBy<GlobalWorldItem> { it.status == GlobalWorldItemStatus.SUPERSEDED }
                        .thenByDescending(GlobalWorldItem::lastSeenAtMillis)
                ).take(MAX_WORLD_ITEMS),
                updatedAtMillis = maxOf(reduction.world.updatedAtMillis, event.timestampMillis)
            ),
            changedItems = changed.distinctBy(GlobalWorldItem::id),
            conflicts = reduction.conflicts.filterNot { (left, right) ->
                left.id in supersededIds || right.id in supersededIds
            }
        )
    }

    internal fun strengthen(existing: GlobalWorldItem, incoming: GlobalWorldItem): GlobalWorldItem {
        val evidence = (existing.evidenceProvenance + incoming.evidenceProvenance)
            .distinctBy(GlobalEvidenceRef::eventId)
            .takeLast(MAX_EVIDENCE_PER_ITEM)
        return existing.copy(
            confidence = (maxOf(existing.confidence, incoming.confidence) + STRENGTHEN_CONFIDENCE_BOOST)
                .coerceAtMost(0.99),
            evidenceCount = evidence.size.coerceAtLeast(maxOf(existing.evidenceCount, incoming.evidenceCount)),
            conversationIds = (existing.conversationIds + incoming.conversationIds).take(MAX_CONVERSATIONS_PER_ITEM).toSet(),
            evidenceEventIds = evidence.map(GlobalEvidenceRef::eventId),
            evidenceProvenance = evidence,
            temporalState = if (incoming.lastSeenAtMillis >= existing.lastSeenAtMillis) {
                incoming.temporalState
            } else existing.temporalState,
            lastSeenAtMillis = maxOf(existing.lastSeenAtMillis, incoming.lastSeenAtMillis),
            expiresAtMillis = if (existing.expiresAtMillis <= 0L || incoming.expiresAtMillis <= 0L) {
                0L
            } else maxOf(existing.expiresAtMillis, incoming.expiresAtMillis)
        )
    }

    internal fun sameSubject(left: GlobalWorldItem, right: GlobalWorldItem): Boolean {
        val leftTopicTokens = GlobalAgentText.tokens(left.topic)
        val rightTopicTokens = GlobalAgentText.tokens(right.topic)
        val topicOverlap = GlobalAgentText.overlap(leftTopicTokens, rightTopicTokens)
        val valueOverlap = GlobalAgentText.overlap(GlobalAgentText.tokens(left.value), GlobalAgentText.tokens(right.value))
        val specificTopic = minOf(leftTopicTokens.size, rightTopicTokens.size) >= MIN_SPECIFIC_TOPIC_TOKENS
        return valueOverlap >= 0.42 || (specificTopic && topicOverlap >= 0.58 && valueOverlap >= 0.12)
    }

    internal fun equivalentAssertion(left: GlobalWorldItem, right: GlobalWorldItem): Boolean {
        if (left.kind != right.kind || !sameSubject(left, right)) return false
        val valueOverlap = GlobalAgentText.overlap(
            GlobalAgentText.tokens(left.value),
            GlobalAgentText.tokens(right.value)
        )
        return valueOverlap >= EQUIVALENT_ASSERTION_OVERLAP &&
            assertionPolarity(left.value) == assertionPolarity(right.value)
    }

    private fun assertionPolarity(value: String): Int {
        val lower = value.lowercase(Locale.ROOT)
        return if (NEGATIVE_SIGNALS.any(lower::contains)) -1 else 1
    }

    private fun requiresReview(
        event: GlobalConversationEvent,
        item: GlobalWorldItem,
        kind: GlobalMemoryCandidateKind
    ): Boolean {
        if (event.type in setOf(
                GlobalConversationEventType.AUTHORIZATION_GRANTED,
                GlobalConversationEventType.AUTHORIZATION_REVOKED,
                GlobalConversationEventType.AUTHORIZATION_POLICY_CHANGED,
                GlobalConversationEventType.RESOURCE_REGISTERED,
                GlobalConversationEventType.RESOURCE_UPDATED,
                GlobalConversationEventType.RESOURCE_REMOVED,
                GlobalConversationEventType.RESOURCE_STATE_CHANGED
            )
        ) return false
        val memoryKind = event.metadata["memory_kind"].orEmpty()
        return kind == GlobalMemoryCandidateKind.SKILL_OPPORTUNITY ||
            item.layer == GlobalWorldLayer.USER || memoryKind in setOf(
            AgentMemoryKind.IDENTITY.name,
            AgentMemoryKind.PREFERENCE.name,
            AgentMemoryKind.SAFETY.name
        )
    }

    private fun candidateKind(
        event: GlobalConversationEvent,
        item: GlobalWorldItem,
        understanding: GlobalUnderstanding
    ): GlobalMemoryCandidateKind {
        val memoryKind = event.metadata["memory_kind"].orEmpty()
        return when {
            memoryKind == AgentMemoryKind.IDENTITY.name -> GlobalMemoryCandidateKind.IDENTITY
            memoryKind == AgentMemoryKind.WORKFLOW.name && item.evidenceCount >= MIN_SKILL_EVIDENCE ->
                GlobalMemoryCandidateKind.SKILL_OPPORTUNITY
            item.kind == GlobalWorldItemKind.PREFERENCE -> GlobalMemoryCandidateKind.PREFERENCE
            item.kind == GlobalWorldItemKind.DECISION -> GlobalMemoryCandidateKind.DECISION
            item.kind == GlobalWorldItemKind.GOAL -> GlobalMemoryCandidateKind.GOAL
            relationSignal(event.content) -> GlobalMemoryCandidateKind.RELATION
            item.kind in setOf(GlobalWorldItemKind.STATE, GlobalWorldItemKind.TASK) || understanding.project.isNotBlank() ->
                GlobalMemoryCandidateKind.PROJECT_STATE
            else -> GlobalMemoryCandidateKind.FACT
        }
    }

    private fun candidateKind(item: GlobalWorldItem): GlobalMemoryCandidateKind = when (item.kind) {
        GlobalWorldItemKind.PREFERENCE -> GlobalMemoryCandidateKind.PREFERENCE
        GlobalWorldItemKind.DECISION -> GlobalMemoryCandidateKind.DECISION
        GlobalWorldItemKind.GOAL -> GlobalMemoryCandidateKind.GOAL
        GlobalWorldItemKind.STATE, GlobalWorldItemKind.TASK -> GlobalMemoryCandidateKind.PROJECT_STATE
        else -> GlobalMemoryCandidateKind.FACT
    }

    private fun temporalState(
        event: GlobalConversationEvent,
        item: GlobalWorldItem,
        replacement: Boolean,
        status: GlobalMemoryCandidateStatus
    ): GlobalMemoryTemporalState {
        val explicitState = parseTemporalState(event.metadata["memory_temporal_state"])
        return when {
            explicitState != null -> explicitState
            status == GlobalMemoryCandidateStatus.CONFLICTED -> GlobalMemoryTemporalState.CONFLICTED
            status == GlobalMemoryCandidateStatus.PENDING_REVIEW -> GlobalMemoryTemporalState.PENDING
            item.status == GlobalWorldItemStatus.SUPERSEDED -> GlobalMemoryTemporalState.DEPRECATED
            item.status == GlobalWorldItemStatus.COMPLETED -> GlobalMemoryTemporalState.HISTORICAL
            replacement -> GlobalMemoryTemporalState.CURRENT
            item.kind in setOf(GlobalWorldItemKind.GOAL, GlobalWorldItemKind.TASK) -> GlobalMemoryTemporalState.PLANNED
            historicalSignal(event.content) -> GlobalMemoryTemporalState.HISTORICAL
            else -> GlobalMemoryTemporalState.CURRENT
        }
    }

    private fun parseTemporalState(value: String?): GlobalMemoryTemporalState? {
        val normalized = value.orEmpty().trim().uppercase(Locale.ROOT)
        return GlobalMemoryTemporalState.entries.firstOrNull { it.name == normalized }
    }

    private fun relationSignal(value: String): Boolean {
        val lower = value.lowercase(Locale.ROOT)
        return RELATION_SIGNALS.any(lower::contains)
    }

    internal fun replacementSignal(value: String): Boolean {
        val lower = value.lowercase(Locale.ROOT)
        return REPLACEMENT_SIGNALS.any(lower::contains)
    }

    internal fun removalSignal(value: String): Boolean {
        val lower = value.lowercase(Locale.ROOT)
        return REMOVAL_SIGNALS.any(lower::contains)
    }

    private fun historicalSignal(value: String): Boolean {
        val lower = value.lowercase(Locale.ROOT)
        return HISTORICAL_SIGNALS.any(lower::contains)
    }

    private fun retractCandidates(
        inbox: GlobalMemoryInbox,
        event: GlobalConversationEvent
    ): GlobalMemoryInbox {
        val retractedEventIds = event.effectiveRetractions()
        val removeConversation = event.excludesConversationFromGlobalModel()
        if (retractedEventIds.isEmpty() && !removeConversation) return inbox
        val retained = inbox.candidates.filterNot { candidate ->
            (removeConversation && candidate.conversationId == event.conversationId) ||
                candidate.sourceEventId in retractedEventIds ||
                candidate.item.evidenceProvenance.any { it.invalidatedBy(retractedEventIds) } ||
                candidate.item.evidenceEventIds.any(retractedEventIds::contains)
        }
        return if (retained.size == inbox.candidates.size) inbox else inbox.copy(
            candidates = retained,
            updatedAtMillis = maxOf(inbox.updatedAtMillis, event.timestampMillis)
        )
    }

    private fun mergeCandidates(
        current: List<GlobalMemoryCandidate>,
        incoming: List<GlobalMemoryCandidate>
    ): List<GlobalMemoryCandidate> {
        if (incoming.isEmpty()) return current
        val incomingIds = incoming.map(GlobalMemoryCandidate::id).toSet()
        return (current.filterNot { it.id in incomingIds } + incoming)
            .sortedWith(
                compareByDescending<GlobalMemoryCandidate> { it.status in setOf(
                    GlobalMemoryCandidateStatus.PENDING_REVIEW,
                    GlobalMemoryCandidateStatus.CONFLICTED
                ) }.thenByDescending(GlobalMemoryCandidate::createdAtMillis)
            )
            .take(MAX_INBOX_CANDIDATES)
    }

    private fun recordForCandidate(
        candidate: GlobalMemoryCandidate,
        explicitOutcome: GlobalMemoryEvolutionOutcome? = null,
        createdAtMillis: Long = candidate.createdAtMillis
    ): GlobalMemoryEvolutionRecord {
        val outcome = explicitOutcome ?: when (candidate.status) {
            GlobalMemoryCandidateStatus.AUTO_MERGED -> GlobalMemoryEvolutionOutcome.APPLIED
            GlobalMemoryCandidateStatus.PENDING_REVIEW -> GlobalMemoryEvolutionOutcome.WAITING_REVIEW
            GlobalMemoryCandidateStatus.CONFLICTED -> GlobalMemoryEvolutionOutcome.CONFLICTED
            GlobalMemoryCandidateStatus.APPROVED -> GlobalMemoryEvolutionOutcome.APPROVED
            GlobalMemoryCandidateStatus.REJECTED -> if (candidate.risk == GlobalMemoryCandidateRisk.PRIVATE_BLOCKED) {
                GlobalMemoryEvolutionOutcome.PRIVATE_BLOCKED
            } else GlobalMemoryEvolutionOutcome.REJECTED
            GlobalMemoryCandidateStatus.SUPERSEDED -> GlobalMemoryEvolutionOutcome.APPLIED
        }
        val resultingItemId = when {
            outcome in setOf(
                GlobalMemoryEvolutionOutcome.WAITING_REVIEW,
                GlobalMemoryEvolutionOutcome.CONFLICTED,
                GlobalMemoryEvolutionOutcome.PRIVATE_BLOCKED,
                GlobalMemoryEvolutionOutcome.REJECTED
            ) -> ""
            candidate.action == GlobalMemoryEvolutionAction.STRENGTHEN ->
                candidate.targetItemIds.firstOrNull().orEmpty()
            else -> candidate.item.id
        }
        val subject = if (candidate.risk == GlobalMemoryCandidateRisk.PRIVATE_BLOCKED) {
            "Private memory candidate"
        } else candidate.item.topic.replace(Regex("\\s+"), " ").trim().take(160)
            .ifBlank { candidate.kind.name.lowercase(Locale.ROOT) }
        return GlobalMemoryEvolutionRecord(
            id = GlobalAgentText.stableKey(
                "memory-evolution-record",
                candidate.id,
                outcome.name
            ),
            sourceEventId = candidate.sourceEventId,
            conversationId = candidate.conversationId,
            candidateId = candidate.id,
            kind = candidate.kind,
            action = candidate.action,
            outcome = outcome,
            temporalState = if (outcome == GlobalMemoryEvolutionOutcome.APPROVED &&
                candidate.temporalState == GlobalMemoryTemporalState.CONFLICTED
            ) GlobalMemoryTemporalState.CURRENT else candidate.temporalState,
            subject = subject,
            targetItemIds = candidate.targetItemIds.take(MAX_EVOLUTION_TARGETS),
            resultingItemId = resultingItemId,
            evidenceCount = candidate.item.evidenceCount.coerceAtLeast(0),
            createdAtMillis = createdAtMillis
        )
    }

    private val REPLACEMENT_SIGNALS = listOf(
        "no longer", "removed", "deleted", "deprecated", "renamed to", "changed to", "replaced by", "disabled",
        "correction", "corrected to", "actually", "instead", "i was wrong", "should be",
        "\u4e0d\u518d", "\u5df2\u79fb\u9664", "\u53bb\u6389", "\u5220\u6389", "\u5220\u9664", "\u5e9f\u5f03", "\u6539\u6210", "\u6539\u4e3a", "\u66ff\u6362\u4e3a", "\u5173\u95ed",
        "\u66f4\u6b63", "\u4fee\u6b63", "\u5e94\u8be5\u662f", "\u4e0d\u662f", "\u800c\u662f", "\u6211\u8bf4\u9519\u4e86"
    )
    private val REMOVAL_SIGNALS = listOf(
        "no longer", "removed", "deleted", "deprecated", "disabled",
        "\u4e0d\u518d", "\u5df2\u79fb\u9664", "\u53bb\u6389", "\u5220\u6389", "\u5220\u9664", "\u5e9f\u5f03", "\u5173\u95ed"
    )
    private val HISTORICAL_SIGNALS = listOf(
        "previously", "formerly", "used to", "in the past", "\u4e4b\u524d", "\u66fe\u7ecf", "\u8fc7\u53bb", "\u539f\u6765"
    )
    private val RELATION_SIGNALS = listOf(
        "owns", "uses", "supports", "contains", "includes", "state is", "status is", "depends on",
        "connected to", "paired with", "prefers", "renamed to", "changed to",
        "\u62e5\u6709", "\u4f7f\u7528", "\u652f\u6301", "\u5305\u542b", "\u72b6\u6001\u4e3a", "\u4f9d\u8d56",
        "\u8fde\u63a5", "\u914d\u5bf9", "\u504f\u597d", "\u559c\u6b22", "\u66f4\u540d\u4e3a", "\u6539\u6210"
    )
    private val NEGATIVE_SIGNALS = listOf(
        " not ", "no longer", "without", "disabled", "removed", "deleted", "failed",
        "\u4e0d", "\u672a", "\u65e0", "\u5173\u95ed", "\u79fb\u9664", "\u5220\u9664", "\u5931\u8d25"
    )
    private const val EQUIVALENT_ASSERTION_OVERLAP = 0.64
    private const val MIN_SPECIFIC_TOPIC_TOKENS = 2
    private const val STRENGTHEN_CONFIDENCE_BOOST = 0.035
    private const val MIN_SKILL_EVIDENCE = 3
    private const val MAX_EVOLUTION_TARGETS = 12
    private const val MAX_EVIDENCE_PER_ITEM = 20
    private const val MAX_CONVERSATIONS_PER_ITEM = 20
    private const val MAX_CANDIDATES_PER_EVENT = 32
    private const val MAX_INBOX_CANDIDATES = 1_000
    private const val MAX_WORLD_ITEMS = 1_500
    private const val MAX_PROCESSED_EVENT_IDS = 4_000
}

enum class GlobalMemoryAuditFindingKind {
    EXPIRED,
    DUPLICATE,
    LOW_CONFIDENCE_REUSED,
    UNRESOLVED_CONFLICT,
    SKILL_CANDIDATE,
    COMPLETED_GOAL
}

data class GlobalMemoryAuditFinding(
    val kind: GlobalMemoryAuditFindingKind,
    val stableKey: String,
    val summary: String,
    val evidenceCount: Int
)

data class GlobalMemoryTheme(
    val id: String,
    val title: String,
    val itemStableKeys: List<String>,
    val itemCount: Int,
    val evidenceCount: Int,
    val conversationCount: Int,
    val confidence: Double,
    val lastUpdatedAtMillis: Long
)

data class GlobalMemoryAuditReport(
    val findings: List<GlobalMemoryAuditFinding> = emptyList(),
    val themes: List<GlobalMemoryTheme> = emptyList(),
    val auditedItemCount: Int = 0,
    val createdAtMillis: Long = 0L
)

object GlobalMemoryCritic {
    fun audit(
        world: PersonalWorldModel,
        inbox: GlobalMemoryInbox,
        nowMillis: Long = System.currentTimeMillis()
    ): Pair<PersonalWorldModel, GlobalMemoryAuditReport> {
        val findings = mutableListOf<GlobalMemoryAuditFinding>()
        val active = world.items.map { item ->
            when {
                item.expiresAtMillis > 0L && item.expiresAtMillis <= nowMillis && item.status == GlobalWorldItemStatus.ACTIVE -> {
                    findings += GlobalMemoryAuditFinding(
                        GlobalMemoryAuditFindingKind.EXPIRED,
                        item.stableKey,
                        "Expired temporal evidence was retired",
                        item.evidenceCount
                    )
                    item.copy(
                        status = GlobalWorldItemStatus.SUPERSEDED,
                        temporalState = GlobalMemoryTemporalState.DEPRECATED
                    )
                }
                item.confidence < 0.50 && item.evidenceCount >= 3 -> {
                    findings += GlobalMemoryAuditFinding(
                        GlobalMemoryAuditFindingKind.LOW_CONFIDENCE_REUSED,
                        item.stableKey,
                        "Frequently reused evidence remains low confidence",
                        item.evidenceCount
                    )
                    item
                }
                else -> item
            }
        }.toMutableList()
        consolidateDuplicates(active, findings)
        active.filter { it.status == GlobalWorldItemStatus.CONFLICTED }
            .groupBy(GlobalWorldItem::conflictGroupId)
            .filterKeys(String::isNotBlank)
            .forEach { (group, items) ->
                findings += GlobalMemoryAuditFinding(
                    GlobalMemoryAuditFindingKind.UNRESOLVED_CONFLICT,
                    group,
                    "Conflicting memory evidence requires review",
                    items.sumOf(GlobalWorldItem::evidenceCount)
                )
            }
        active.filter { it.kind == GlobalWorldItemKind.DECISION && it.evidenceCount >= 3 }
            .forEach { item ->
                findings += GlobalMemoryAuditFinding(
                    GlobalMemoryAuditFindingKind.SKILL_CANDIDATE,
                    item.stableKey,
                    "Repeated workflow evidence may be promoted to a reviewed Skill",
                    item.evidenceCount
                )
            }
        active.filter { it.kind == GlobalWorldItemKind.GOAL && it.status == GlobalWorldItemStatus.COMPLETED }
            .forEach { item ->
                findings += GlobalMemoryAuditFinding(
                    GlobalMemoryAuditFindingKind.COMPLETED_GOAL,
                    item.stableKey,
                    "Completed goal can be archived",
                    item.evidenceCount
                )
            }
        val stalePending = inbox.pending().count { nowMillis - it.createdAtMillis > PENDING_REVIEW_WARNING_MILLIS }
        if (stalePending > 0) {
            findings += GlobalMemoryAuditFinding(
                GlobalMemoryAuditFindingKind.LOW_CONFIDENCE_REUSED,
                "memory-inbox",
                "$stalePending memory candidates are still awaiting review",
                stalePending
            )
        }
        val themes = consolidateThemes(active)
        val evolved = if (active == world.items) world else world.copy(
            items = active,
            updatedAtMillis = maxOf(world.updatedAtMillis, nowMillis)
        )
        return evolved to GlobalMemoryAuditReport(
            findings = findings.distinctBy { "${it.kind}:${it.stableKey}" }.take(MAX_FINDINGS),
            themes = themes,
            auditedItemCount = world.items.size,
            createdAtMillis = nowMillis
        )
    }

    private fun consolidateDuplicates(
        items: MutableList<GlobalWorldItem>,
        findings: MutableList<GlobalMemoryAuditFinding>
    ) {
        val ordered = items.indices.sortedByDescending { items[it].lastSeenAtMillis }
        ordered.forEachIndexed { position, primaryIndex ->
            val primary = items[primaryIndex]
            if (!primary.isCurrentMemory()) return@forEachIndexed
            ordered.drop(position + 1).forEach duplicateLoop@{ duplicateIndex ->
                val currentPrimary = items[primaryIndex]
                val duplicate = items[duplicateIndex]
                if (!duplicate.isCurrentMemory() || currentPrimary.layer != duplicate.layer) return@duplicateLoop
                if (!GlobalMemoryEvolutionPolicy.equivalentAssertion(currentPrimary, duplicate)) return@duplicateLoop
                val merged = GlobalMemoryEvolutionPolicy.strengthen(currentPrimary, duplicate)
                items[primaryIndex] = merged
                items[duplicateIndex] = duplicate.copy(
                    status = GlobalWorldItemStatus.SUPERSEDED,
                    temporalState = GlobalMemoryTemporalState.DEPRECATED,
                    conflictGroupId = ""
                )
                findings += GlobalMemoryAuditFinding(
                    GlobalMemoryAuditFindingKind.DUPLICATE,
                    merged.stableKey,
                    "Equivalent evidence was consolidated into the current memory",
                    merged.evidenceCount
                )
            }
        }
    }

    private fun consolidateThemes(items: List<GlobalWorldItem>): List<GlobalMemoryTheme> {
        val clusters = mutableListOf<MutableList<GlobalWorldItem>>()
        items.asSequence()
            .filter { it.isCurrentMemory() }
            .filter { it.layer != GlobalWorldLayer.REALTIME }
            .filter { it.contextVisibility == GlobalWorldContextVisibility.SHAREABLE }
            .sortedByDescending(GlobalWorldItem::lastSeenAtMillis)
            .forEach { item ->
                val tokens = GlobalAgentText.tokens(item.topic)
                val cluster = clusters.firstOrNull { existing ->
                    existing.any { member ->
                        GlobalAgentText.overlap(tokens, GlobalAgentText.tokens(member.topic)) >= THEME_TOPIC_OVERLAP
                    }
                }
                if (cluster == null) clusters += mutableListOf(item) else cluster += item
            }
        return clusters.mapNotNull { cluster ->
            val evidence = cluster.sumOf(GlobalWorldItem::evidenceCount)
            val conversations = cluster.flatMap { it.conversationIds }.distinct()
            if (cluster.size < MIN_THEME_ITEMS && conversations.size < MIN_THEME_CONVERSATIONS) return@mapNotNull null
            val title = cluster.maxByOrNull(GlobalWorldItem::lastSeenAtMillis)?.topic.orEmpty().take(160)
            if (title.isBlank()) return@mapNotNull null
            GlobalMemoryTheme(
                id = GlobalAgentText.stableKey("memory-theme", title),
                title = title,
                itemStableKeys = cluster.map(GlobalWorldItem::stableKey).distinct().take(MAX_THEME_ITEMS),
                itemCount = cluster.size,
                evidenceCount = evidence,
                conversationCount = conversations.size,
                confidence = cluster.map(GlobalWorldItem::confidence).average().coerceIn(0.0, 1.0),
                lastUpdatedAtMillis = cluster.maxOf(GlobalWorldItem::lastSeenAtMillis)
            )
        }.sortedWith(compareByDescending<GlobalMemoryTheme> { it.evidenceCount }
            .thenByDescending(GlobalMemoryTheme::lastUpdatedAtMillis))
            .take(MAX_THEMES)
    }

    private fun GlobalWorldItem.isCurrentMemory(): Boolean =
        status == GlobalWorldItemStatus.ACTIVE && temporalState in setOf(
            GlobalMemoryTemporalState.CURRENT,
            GlobalMemoryTemporalState.PLANNED
        )

    fun due(lastAuditMillis: Long, processedEvents: Int, nowMillis: Long = System.currentTimeMillis()): Boolean =
        processedEvents >= 20 || lastAuditMillis <= 0L || nowMillis - lastAuditMillis >= AUDIT_INTERVAL_MILLIS

    private const val AUDIT_INTERVAL_MILLIS = 24L * 60L * 60L * 1_000L
    private const val PENDING_REVIEW_WARNING_MILLIS = 30L * 24L * 60L * 60L * 1_000L
    private const val MAX_FINDINGS = 200
    private const val THEME_TOPIC_OVERLAP = 0.42
    private const val MIN_THEME_ITEMS = 3
    private const val MIN_THEME_CONVERSATIONS = 2
    private const val MAX_THEME_ITEMS = 24
    private const val MAX_THEMES = 80
}

class GlobalMemoryEvolutionStore(context: Context) {
    private val database = AgentEncryptedDatabase(context.applicationContext, GlobalAgentRepository.DATABASE_NAME)

    fun inbox(): GlobalMemoryInbox = synchronized(STORE_LOCK) {
        GlobalMemoryEvolutionCodec.decodeInbox(database.readString(KEY_INBOX, ""))
    }

    fun saveInbox(inbox: GlobalMemoryInbox) = synchronized(STORE_LOCK) {
        database.writeString(KEY_INBOX, GlobalMemoryEvolutionCodec.encodeInbox(inbox).toString())
    }

    fun auditReport(): GlobalMemoryAuditReport = synchronized(STORE_LOCK) {
        GlobalMemoryEvolutionCodec.decodeAudit(database.readString(KEY_AUDIT, ""))
    }

    fun saveAudit(report: GlobalMemoryAuditReport) = synchronized(STORE_LOCK) {
        database.writeString(KEY_AUDIT, GlobalMemoryEvolutionCodec.encodeAudit(report).toString())
    }

    fun evolutionRecords(): List<GlobalMemoryEvolutionRecord> = synchronized(STORE_LOCK) {
        GlobalMemoryEvolutionCodec.decodeRecords(database.readString(KEY_RECORDS, ""))
    }

    fun appendEvolutionRecords(records: List<GlobalMemoryEvolutionRecord>) = synchronized(STORE_LOCK) {
        if (records.isEmpty()) return@synchronized
        val incomingIds = records.mapTo(mutableSetOf(), GlobalMemoryEvolutionRecord::id)
        val merged = (evolutionRecords().filterNot { it.id in incomingIds } + records)
            .sortedBy(GlobalMemoryEvolutionRecord::createdAtMillis)
            .takeLast(MAX_EVOLUTION_RECORDS)
        database.writeString(KEY_RECORDS, GlobalMemoryEvolutionCodec.encodeRecords(merged).toString())
    }

    fun export(): JSONObject = synchronized(STORE_LOCK) {
        JSONObject()
            .put("inbox", GlobalMemoryEvolutionCodec.encodeInbox(inbox()))
            .put("audit", GlobalMemoryEvolutionCodec.encodeAudit(auditReport()))
            .put("records", GlobalMemoryEvolutionCodec.encodeRecords(evolutionRecords()))
    }

    fun restore(payload: JSONObject) = synchronized(STORE_LOCK) {
        payload.optJSONObject("inbox")?.let { saveInbox(GlobalMemoryEvolutionCodec.decodeInbox(it.toString())) }
        payload.optJSONObject("audit")?.let { saveAudit(GlobalMemoryEvolutionCodec.decodeAudit(it.toString())) }
        payload.optJSONArray("records")?.let { records ->
            database.writeString(
                KEY_RECORDS,
                GlobalMemoryEvolutionCodec.encodeRecords(
                    GlobalMemoryEvolutionCodec.decodeRecords(records.toString())
                ).toString()
            )
        }
    }

    private companion object {
        const val KEY_INBOX = "memory_evolution_inbox"
        const val KEY_AUDIT = "memory_evolution_audit"
        const val KEY_RECORDS = "memory_evolution_records"
        const val MAX_EVOLUTION_RECORDS = 2_000
        val STORE_LOCK = Any()
    }
}

internal object GlobalMemoryEvolutionCodec {
    fun encodeInbox(inbox: GlobalMemoryInbox): JSONObject = JSONObject()
        .put("candidates", JSONArray().apply { inbox.candidates.forEach { put(encodeCandidate(it)) } })
        .put("processed_event_ids", JSONArray(inbox.processedEventIds))
        .put("updated_at_millis", inbox.updatedAtMillis)

    fun decodeInbox(raw: String): GlobalMemoryInbox = runCatching {
        if (raw.isBlank()) return@runCatching GlobalMemoryInbox()
        val root = JSONObject(raw)
        val candidates = buildList {
            val array = root.optJSONArray("candidates") ?: JSONArray()
            for (index in 0 until array.length()) decodeCandidate(array.optJSONObject(index))?.let(::add)
        }
        GlobalMemoryInbox(
            candidates = candidates.take(1_000),
            processedEventIds = strings(root.optJSONArray("processed_event_ids")).takeLast(4_000),
            updatedAtMillis = root.optLong("updated_at_millis")
        )
    }.getOrDefault(GlobalMemoryInbox())

    fun encodeRecords(records: List<GlobalMemoryEvolutionRecord>): JSONArray = JSONArray().apply {
        records.forEach { record ->
            put(JSONObject()
                .put("id", record.id)
                .put("source_event_id", record.sourceEventId)
                .put("conversation_id", record.conversationId)
                .put("candidate_id", record.candidateId)
                .put("kind", record.kind.name)
                .put("action", record.action.name)
                .put("outcome", record.outcome.name)
                .put("temporal_state", record.temporalState.name)
                .put("subject", record.subject)
                .put("target_item_ids", JSONArray(record.targetItemIds))
                .put("resulting_item_id", record.resultingItemId)
                .put("evidence_count", record.evidenceCount)
                .put("created_at_millis", record.createdAtMillis))
        }
    }

    fun decodeRecords(raw: String): List<GlobalMemoryEvolutionRecord> = runCatching {
        if (raw.isBlank()) return@runCatching emptyList()
        val array = JSONArray(raw)
        buildList {
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                val id = item.optString("id")
                val sourceEventId = item.optString("source_event_id")
                if (id.isBlank() || sourceEventId.isBlank()) continue
                add(GlobalMemoryEvolutionRecord(
                    id = id.take(160),
                    sourceEventId = sourceEventId.take(240),
                    conversationId = item.optString("conversation_id").take(240),
                    candidateId = item.optString("candidate_id").take(160),
                    kind = enumValue(item.optString("kind"), GlobalMemoryCandidateKind.FACT),
                    action = enumValue(item.optString("action"), GlobalMemoryEvolutionAction.CREATE),
                    outcome = enumValue(item.optString("outcome"), GlobalMemoryEvolutionOutcome.APPLIED),
                    temporalState = enumValue(
                        item.optString("temporal_state"),
                        GlobalMemoryTemporalState.CURRENT
                    ),
                    subject = item.optString("subject").replace(Regex("\\s+"), " ").trim().take(160),
                    targetItemIds = strings(item.optJSONArray("target_item_ids")).take(12),
                    resultingItemId = item.optString("resulting_item_id").take(160),
                    evidenceCount = item.optInt("evidence_count").coerceAtLeast(0),
                    createdAtMillis = item.optLong("created_at_millis").coerceAtLeast(0L)
                ))
            }
        }.takeLast(2_000)
    }.getOrDefault(emptyList())

    fun encodeAudit(report: GlobalMemoryAuditReport): JSONObject = JSONObject()
        .put("findings", JSONArray().apply {
            report.findings.forEach { finding ->
                put(JSONObject()
                    .put("kind", finding.kind.name)
                    .put("stable_key", finding.stableKey)
                    .put("summary", finding.summary)
                    .put("evidence_count", finding.evidenceCount))
            }
        })
        .put("themes", JSONArray().apply {
            report.themes.forEach { theme ->
                put(JSONObject()
                    .put("id", theme.id)
                    .put("title", theme.title)
                    .put("item_stable_keys", JSONArray(theme.itemStableKeys))
                    .put("item_count", theme.itemCount)
                    .put("evidence_count", theme.evidenceCount)
                    .put("conversation_count", theme.conversationCount)
                    .put("confidence", theme.confidence)
                    .put("last_updated_at_millis", theme.lastUpdatedAtMillis))
            }
        })
        .put("audited_item_count", report.auditedItemCount)
        .put("created_at_millis", report.createdAtMillis)

    fun decodeAudit(raw: String): GlobalMemoryAuditReport = runCatching {
        if (raw.isBlank()) return@runCatching GlobalMemoryAuditReport()
        val root = JSONObject(raw)
        val findings = buildList {
            val array = root.optJSONArray("findings") ?: JSONArray()
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                val kind = enumValue(item.optString("kind"), GlobalMemoryAuditFindingKind.DUPLICATE)
                add(GlobalMemoryAuditFinding(
                    kind = kind,
                    stableKey = item.optString("stable_key").take(240),
                    summary = item.optString("summary").take(600),
                    evidenceCount = item.optInt("evidence_count").coerceAtLeast(0)
                ))
            }
        }
        val themes = buildList {
            val array = root.optJSONArray("themes") ?: JSONArray()
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                val id = item.optString("id")
                val title = item.optString("title")
                if (id.isBlank() || title.isBlank()) continue
                add(GlobalMemoryTheme(
                    id = id.take(160),
                    title = title.take(160),
                    itemStableKeys = strings(item.optJSONArray("item_stable_keys")).take(24),
                    itemCount = item.optInt("item_count").coerceAtLeast(0),
                    evidenceCount = item.optInt("evidence_count").coerceAtLeast(0),
                    conversationCount = item.optInt("conversation_count").coerceAtLeast(0),
                    confidence = item.optDouble("confidence", 0.5).coerceIn(0.0, 1.0),
                    lastUpdatedAtMillis = item.optLong("last_updated_at_millis").coerceAtLeast(0L)
                ))
            }
        }
        GlobalMemoryAuditReport(
            findings = findings.take(200),
            themes = themes.take(80),
            auditedItemCount = root.optInt("audited_item_count").coerceAtLeast(0),
            createdAtMillis = root.optLong("created_at_millis").coerceAtLeast(0L)
        )
    }.getOrDefault(GlobalMemoryAuditReport())

    private fun encodeCandidate(candidate: GlobalMemoryCandidate): JSONObject = JSONObject()
        .put("id", candidate.id)
        .put("source_event_id", candidate.sourceEventId)
        .put("conversation_id", candidate.conversationId)
        .put("kind", candidate.kind.name)
        .put("temporal_state", candidate.temporalState.name)
        .put("risk", candidate.risk.name)
        .put("status", candidate.status.name)
        .put("action", candidate.action.name)
        .put("target_item_ids", JSONArray(candidate.targetItemIds))
        .put("item", encodeItem(candidate.item))
        .put("reason", candidate.reason)
        .put("created_at_millis", candidate.createdAtMillis)
        .put("reviewed_at_millis", candidate.reviewedAtMillis)

    private fun decodeCandidate(json: JSONObject?): GlobalMemoryCandidate? {
        if (json == null) return null
        val id = json.optString("id")
        val item = decodeItem(json.optJSONObject("item")) ?: return null
        if (id.isBlank()) return null
        return GlobalMemoryCandidate(
            id = id,
            sourceEventId = json.optString("source_event_id"),
            conversationId = json.optString("conversation_id"),
            kind = enumValue(json.optString("kind"), GlobalMemoryCandidateKind.FACT),
            temporalState = enumValue(json.optString("temporal_state"), GlobalMemoryTemporalState.CURRENT),
            risk = enumValue(json.optString("risk"), GlobalMemoryCandidateRisk.REVIEW_REQUIRED),
            status = enumValue(json.optString("status"), GlobalMemoryCandidateStatus.PENDING_REVIEW),
            action = enumValue(json.optString("action"), GlobalMemoryEvolutionAction.CREATE),
            targetItemIds = strings(json.optJSONArray("target_item_ids")).take(12),
            item = item,
            reason = json.optString("reason").take(240),
            createdAtMillis = json.optLong("created_at_millis"),
            reviewedAtMillis = json.optLong("reviewed_at_millis")
        )
    }

    private fun encodeItem(item: GlobalWorldItem): JSONObject = JSONObject()
        .put("id", item.id)
        .put("stable_key", item.stableKey)
        .put("kind", item.kind.name)
        .put("layer", item.layer.name)
        .put("topic", item.topic)
        .put("value", item.value)
        .put("confidence", item.confidence)
        .put("context_visibility", item.contextVisibility.name)
        .put("evidence_count", item.evidenceCount)
        .put("conversation_ids", JSONArray(item.conversationIds.toList()))
        .put("evidence_event_ids", JSONArray(item.evidenceEventIds))
        .put("evidence_provenance", JSONArray().apply {
            item.evidenceProvenance.forEach { evidence ->
                put(JSONObject()
                    .put("event_id", evidence.eventId)
                    .put("causal_event_ids", JSONArray(evidence.causalEventIds.toList()))
                    .put("conversation_id", evidence.conversationId)
                    .put("timestamp_millis", evidence.timestampMillis))
            }
        })
        .put("status", item.status.name)
        .put("temporal_state", item.temporalState.name)
        .put("conflict_group_id", item.conflictGroupId)
        .put("first_seen_at_millis", item.firstSeenAtMillis)
        .put("last_seen_at_millis", item.lastSeenAtMillis)
        .put("expires_at_millis", item.expiresAtMillis)

    private fun decodeItem(json: JSONObject?): GlobalWorldItem? {
        if (json == null) return null
        val stableKey = json.optString("stable_key")
        if (stableKey.isBlank()) return null
        val evidence = buildList {
            val array = json.optJSONArray("evidence_provenance") ?: JSONArray()
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                val eventId = item.optString("event_id")
                if (eventId.isBlank()) continue
                add(GlobalEvidenceRef(
                    eventId = eventId,
                    causalEventIds = strings(item.optJSONArray("causal_event_ids")).toSet().ifEmpty { setOf(eventId) },
                    conversationId = item.optString("conversation_id"),
                    timestampMillis = item.optLong("timestamp_millis")
                ))
            }
        }
        return GlobalWorldItem(
            id = json.optString("id").ifBlank { stableKey },
            stableKey = stableKey,
            kind = enumValue(json.optString("kind"), GlobalWorldItemKind.FACT),
            layer = enumValue(json.optString("layer"), GlobalWorldLayer.TOPIC),
            topic = json.optString("topic").take(1_200),
            value = json.optString("value").take(1_200),
            confidence = json.optDouble("confidence", 0.5).coerceIn(0.0, 1.0),
            contextVisibility = enumValue(
                json.optString("context_visibility"),
                GlobalWorldContextVisibility.SHAREABLE
            ),
            evidenceCount = json.optInt("evidence_count", 1).coerceAtLeast(1),
            conversationIds = strings(json.optJSONArray("conversation_ids")).take(20).toSet(),
            evidenceEventIds = strings(json.optJSONArray("evidence_event_ids")).takeLast(20),
            evidenceProvenance = evidence.takeLast(20),
            status = enumValue(json.optString("status"), GlobalWorldItemStatus.ACTIVE),
            temporalState = enumValue(
                json.optString("temporal_state"),
                migrateTemporalState(
                    enumValue(json.optString("status"), GlobalWorldItemStatus.ACTIVE),
                    enumValue(json.optString("kind"), GlobalWorldItemKind.FACT)
                )
            ),
            conflictGroupId = json.optString("conflict_group_id"),
            firstSeenAtMillis = json.optLong("first_seen_at_millis"),
            lastSeenAtMillis = json.optLong("last_seen_at_millis"),
            expiresAtMillis = json.optLong("expires_at_millis")
        )
    }

    private fun strings(array: JSONArray?): List<String> {
        if (array == null) return emptyList()
        return buildList {
            for (index in 0 until array.length()) array.optString(index).takeIf(String::isNotBlank)?.let(::add)
        }
    }

    private inline fun <reified T : Enum<T>> enumValue(value: String, fallback: T): T =
        enumValues<T>().firstOrNull { it.name == value } ?: fallback

    private fun migrateTemporalState(
        status: GlobalWorldItemStatus,
        kind: GlobalWorldItemKind
    ): GlobalMemoryTemporalState = when {
        status == GlobalWorldItemStatus.CONFLICTED -> GlobalMemoryTemporalState.CONFLICTED
        status == GlobalWorldItemStatus.SUPERSEDED -> GlobalMemoryTemporalState.DEPRECATED
        status == GlobalWorldItemStatus.COMPLETED -> GlobalMemoryTemporalState.HISTORICAL
        kind in setOf(GlobalWorldItemKind.GOAL, GlobalWorldItemKind.TASK) -> GlobalMemoryTemporalState.PLANNED
        else -> GlobalMemoryTemporalState.CURRENT
    }
}
