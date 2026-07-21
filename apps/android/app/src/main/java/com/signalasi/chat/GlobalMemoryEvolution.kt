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

data class GlobalMemoryCandidate(
    val id: String,
    val sourceEventId: String,
    val conversationId: String,
    val kind: GlobalMemoryCandidateKind,
    val temporalState: GlobalMemoryTemporalState,
    val risk: GlobalMemoryCandidateRisk,
    val status: GlobalMemoryCandidateStatus,
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
    val candidates: List<GlobalMemoryCandidate>
)

object GlobalMemoryEvolutionPolicy {
    fun evolve(
        worldBefore: PersonalWorldModel,
        reduction: GlobalWorldReduction,
        inbox: GlobalMemoryInbox,
        event: GlobalConversationEvent,
        understanding: GlobalUnderstanding
    ): GlobalMemoryEvolutionResult {
        if (event.id in inbox.processedEventIds) {
            return GlobalMemoryEvolutionResult(reduction, inbox, emptyList())
        }
        val candidates = extractCandidates(event, understanding, reduction)
        val gated = gateReduction(worldBefore, reduction, candidates, event)
        val evolved = applyTemporalEvolution(worldBefore, gated, candidates, event)
        val updatedInbox = inbox.copy(
            candidates = mergeCandidates(inbox.candidates, candidates),
            processedEventIds = (inbox.processedEventIds + event.id)
                .filter(String::isNotBlank)
                .distinct()
                .takeLast(MAX_PROCESSED_EVENT_IDS),
            updatedAtMillis = maxOf(inbox.updatedAtMillis, event.timestampMillis)
        )
        return GlobalMemoryEvolutionResult(evolved, updatedInbox, candidates)
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
                val private = event.sensitivity == GlobalConversationSensitivity.SESSION_PRIVATE ||
                    AgentLearningAnalyzer.containsSensitiveData(item.value)
                val conflict = item.status == GlobalWorldItemStatus.CONFLICTED
                val review = requiresReview(event, item)
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
                ) else item
                GlobalMemoryCandidate(
                    id = GlobalAgentText.stableKey("memory-candidate", event.id, item.stableKey),
                    sourceEventId = event.id,
                    conversationId = event.conversationId,
                    kind = candidateKind(event, item, understanding),
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

    private fun applyTemporalEvolution(
        worldBefore: PersonalWorldModel,
        reduction: GlobalWorldReduction,
        candidates: List<GlobalMemoryCandidate>,
        event: GlobalConversationEvent
    ): GlobalWorldReduction {
        if (!replacementSignal(event.content)) return reduction
        val incoming = candidates.filter { it.status == GlobalMemoryCandidateStatus.AUTO_MERGED }
            .map(GlobalMemoryCandidate::item)
        if (incoming.isEmpty()) return reduction
        val incomingIds = incoming.map(GlobalWorldItem::id).toSet()
        val evolvedItems = reduction.world.items.map { item ->
            if (item.id in incomingIds) return@map item.copy(
                status = GlobalWorldItemStatus.ACTIVE,
                conflictGroupId = ""
            )
            val replacement = incoming.firstOrNull { candidate ->
                item.status in setOf(GlobalWorldItemStatus.ACTIVE, GlobalWorldItemStatus.CONFLICTED) &&
                    item.lastSeenAtMillis <= candidate.lastSeenAtMillis &&
                    item.kind == candidate.kind &&
                    sameSubject(item, candidate)
            }
            if (replacement != null) {
                item.copy(status = GlobalWorldItemStatus.SUPERSEDED, conflictGroupId = "")
            } else item
        }
        val superseded = evolvedItems.filter { evolved ->
            val before = worldBefore.items.firstOrNull { it.id == evolved.id }
            before?.status != evolved.status && evolved.status == GlobalWorldItemStatus.SUPERSEDED
        }
        return reduction.copy(
            world = reduction.world.copy(items = evolvedItems),
            changedItems = (reduction.changedItems.filterNot { changed ->
                superseded.any { it.id == changed.id }
            } + superseded + incoming.map { item ->
                evolvedItems.firstOrNull { it.id == item.id } ?: item
            }).distinctBy(GlobalWorldItem::id),
            conflicts = reduction.conflicts.filterNot { (left, right) ->
                evolvedItems.any { it.id == left.id && it.status == GlobalWorldItemStatus.SUPERSEDED } ||
                    evolvedItems.any { it.id == right.id && it.status == GlobalWorldItemStatus.SUPERSEDED }
            }
        )
    }

    private fun sameSubject(left: GlobalWorldItem, right: GlobalWorldItem): Boolean {
        val topicOverlap = GlobalAgentText.overlap(GlobalAgentText.tokens(left.topic), GlobalAgentText.tokens(right.topic))
        val valueOverlap = GlobalAgentText.overlap(GlobalAgentText.tokens(left.value), GlobalAgentText.tokens(right.value))
        return topicOverlap >= 0.34 || valueOverlap >= 0.42
    }

    private fun requiresReview(event: GlobalConversationEvent, item: GlobalWorldItem): Boolean {
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
        return item.layer == GlobalWorldLayer.USER || memoryKind in setOf(
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
            item.kind == GlobalWorldItemKind.PREFERENCE -> GlobalMemoryCandidateKind.PREFERENCE
            item.kind == GlobalWorldItemKind.DECISION -> GlobalMemoryCandidateKind.DECISION
            item.kind == GlobalWorldItemKind.GOAL -> GlobalMemoryCandidateKind.GOAL
            item.kind in setOf(GlobalWorldItemKind.STATE, GlobalWorldItemKind.TASK) || understanding.project.isNotBlank() ->
                GlobalMemoryCandidateKind.PROJECT_STATE
            else -> GlobalMemoryCandidateKind.FACT
        }
    }

    private fun temporalState(
        event: GlobalConversationEvent,
        item: GlobalWorldItem,
        replacement: Boolean,
        status: GlobalMemoryCandidateStatus
    ): GlobalMemoryTemporalState = when {
        status == GlobalMemoryCandidateStatus.CONFLICTED -> GlobalMemoryTemporalState.CONFLICTED
        status == GlobalMemoryCandidateStatus.PENDING_REVIEW -> GlobalMemoryTemporalState.PENDING
        item.status == GlobalWorldItemStatus.SUPERSEDED -> GlobalMemoryTemporalState.DEPRECATED
        item.status == GlobalWorldItemStatus.COMPLETED -> GlobalMemoryTemporalState.HISTORICAL
        replacement -> GlobalMemoryTemporalState.CURRENT
        item.kind in setOf(GlobalWorldItemKind.GOAL, GlobalWorldItemKind.TASK) -> GlobalMemoryTemporalState.PLANNED
        historicalSignal(event.content) -> GlobalMemoryTemporalState.HISTORICAL
        else -> GlobalMemoryTemporalState.CURRENT
    }

    internal fun replacementSignal(value: String): Boolean {
        val lower = value.lowercase(Locale.ROOT)
        return REPLACEMENT_SIGNALS.any(lower::contains)
    }

    private fun historicalSignal(value: String): Boolean {
        val lower = value.lowercase(Locale.ROOT)
        return HISTORICAL_SIGNALS.any(lower::contains)
    }

    private fun mergeCandidates(
        current: List<GlobalMemoryCandidate>,
        incoming: List<GlobalMemoryCandidate>
    ): List<GlobalMemoryCandidate> {
        if (incoming.isEmpty()) return current
        val incomingIds = incoming.map(GlobalMemoryCandidate::id).toSet()
        return (current.filterNot { it.id in incomingIds } + incoming)
            .sortedByDescending(GlobalMemoryCandidate::createdAtMillis)
            .take(MAX_INBOX_CANDIDATES)
    }

    private val REPLACEMENT_SIGNALS = listOf(
        "no longer", "removed", "deleted", "deprecated", "renamed to", "changed to", "replaced by", "disabled",
        "\u4e0d\u518d", "\u5df2\u79fb\u9664", "\u53bb\u6389", "\u5220\u6389", "\u5220\u9664", "\u5e9f\u5f03", "\u6539\u6210", "\u66ff\u6362\u4e3a", "\u5173\u95ed"
    )
    private val HISTORICAL_SIGNALS = listOf(
        "previously", "formerly", "used to", "in the past", "\u4e4b\u524d", "\u66fe\u7ecf", "\u8fc7\u53bb", "\u539f\u6765"
    )
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

data class GlobalMemoryAuditReport(
    val findings: List<GlobalMemoryAuditFinding> = emptyList(),
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
                    item.copy(status = GlobalWorldItemStatus.SUPERSEDED)
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
        val evolved = if (active == world.items) world else world.copy(
            items = active,
            updatedAtMillis = maxOf(world.updatedAtMillis, nowMillis)
        )
        return evolved to GlobalMemoryAuditReport(
            findings = findings.distinctBy { "${it.kind}:${it.stableKey}" }.take(MAX_FINDINGS),
            auditedItemCount = world.items.size,
            createdAtMillis = nowMillis
        )
    }

    fun due(lastAuditMillis: Long, processedEvents: Int, nowMillis: Long = System.currentTimeMillis()): Boolean =
        processedEvents >= 20 || lastAuditMillis <= 0L || nowMillis - lastAuditMillis >= AUDIT_INTERVAL_MILLIS

    private const val AUDIT_INTERVAL_MILLIS = 24L * 60L * 60L * 1_000L
    private const val PENDING_REVIEW_WARNING_MILLIS = 30L * 24L * 60L * 60L * 1_000L
    private const val MAX_FINDINGS = 200
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

    fun export(): JSONObject = synchronized(STORE_LOCK) {
        JSONObject()
            .put("inbox", GlobalMemoryEvolutionCodec.encodeInbox(inbox()))
            .put("audit", GlobalMemoryEvolutionCodec.encodeAudit(auditReport()))
    }

    fun restore(payload: JSONObject) = synchronized(STORE_LOCK) {
        payload.optJSONObject("inbox")?.let { saveInbox(GlobalMemoryEvolutionCodec.decodeInbox(it.toString())) }
        payload.optJSONObject("audit")?.let { saveAudit(GlobalMemoryEvolutionCodec.decodeAudit(it.toString())) }
    }

    private companion object {
        const val KEY_INBOX = "memory_evolution_inbox"
        const val KEY_AUDIT = "memory_evolution_audit"
        val STORE_LOCK = Any()
    }
}

private object GlobalMemoryEvolutionCodec {
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
        GlobalMemoryAuditReport(
            findings = findings.take(200),
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
}
