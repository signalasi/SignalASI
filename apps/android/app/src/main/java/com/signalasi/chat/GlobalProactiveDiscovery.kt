package com.signalasi.chat

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

enum class GlobalDiscoveryKind {
    CROSS_TOPIC_CONFLICT,
    CROSS_TOPIC_SYNTHESIS,
    MATERIAL_RISK,
    HIGH_VALUE_OPPORTUNITY,
    STALLED_GOAL
}

data class GlobalDiscoveryCandidate(
    val stableKey: String,
    val fingerprint: String,
    val kind: GlobalDiscoveryKind,
    val topic: String,
    val summary: String,
    val sourceConversationIds: Set<String>,
    val causalEventIds: Set<String>,
    val score: Double,
    val urgency: Double,
    val externalResearchUseful: Boolean,
    val longHorizonGoalId: String = ""
)

data class GlobalDiscoveryRecord(
    val stableKey: String,
    val fingerprint: String,
    val cognitionTaskId: String,
    val emittedAtMillis: Long
)

data class GlobalProactiveDiscoveryState(
    val nextScanAtMillis: Long = 0L,
    val scanLeaseExpiresAtMillis: Long = 0L,
    val lastStartedAtMillis: Long = 0L,
    val lastCompletedAtMillis: Long = 0L,
    val scanSequence: Long = 0L,
    val recentEmissionTimestamps: List<Long> = emptyList(),
    val records: List<GlobalDiscoveryRecord> = emptyList(),
    val lastError: String = ""
)

data class GlobalDiscoveryScanClaim(
    val sequence: Long,
    val leaseExpiresAtMillis: Long
)

data class GlobalProactiveDiscoveryCycleResult(
    val scanned: Boolean,
    val candidateCount: Int,
    val queuedTaskCount: Int,
    val nextWakeAtMillis: Long,
    val error: String = ""
)

object GlobalProactiveDiscoveryPolicy {
    fun scan(
        world: PersonalWorldModel,
        goals: List<GlobalLongHorizonGoal>,
        excludedConversationIds: Set<String>,
        nowMillis: Long,
        topicGraph: GlobalTopicProjectGraph = GlobalTopicProjectGraph()
    ): List<GlobalDiscoveryCandidate> {
        val eligibleItems = world.items.filter { item ->
            item.contextVisibility == GlobalWorldContextVisibility.SHAREABLE &&
                item.status in setOf(GlobalWorldItemStatus.ACTIVE, GlobalWorldItemStatus.CONFLICTED) &&
                (item.expiresAtMillis <= 0L || item.expiresAtMillis > nowMillis) &&
                item.evidenceEventIds.isNotEmpty() &&
                item.conversationIds.isNotEmpty() &&
                item.conversationIds.none(excludedConversationIds::contains)
        }
        return buildList {
            addAll(conflictCandidates(eligibleItems))
            eligibleItems.asSequence()
                .filter { it.kind == GlobalWorldItemKind.RISK && it.status == GlobalWorldItemStatus.ACTIVE }
                .filter { it.confidence >= 0.62 }
                .filter { it.confidence >= 0.82 || it.evidenceCount >= 2 || it.conversationIds.size >= 2 }
                .forEach { add(worldItemCandidate(it, GlobalDiscoveryKind.MATERIAL_RISK)) }
            eligibleItems.asSequence()
                .filter { it.kind == GlobalWorldItemKind.OPPORTUNITY && it.status == GlobalWorldItemStatus.ACTIVE }
                .filter { it.confidence >= 0.72 }
                .filter { it.evidenceCount >= 2 || it.conversationIds.size >= 2 }
                .forEach { add(worldItemCandidate(it, GlobalDiscoveryKind.HIGH_VALUE_OPPORTUNITY)) }
            addAll(synthesisCandidates(topicGraph, eligibleItems, excludedConversationIds))
            addAll(stalledGoalCandidates(goals, eligibleItems, excludedConversationIds, nowMillis))
        }.distinctBy(GlobalDiscoveryCandidate::stableKey)
            .sortedWith(
                compareByDescending<GlobalDiscoveryCandidate> { it.score }
                    .thenByDescending(GlobalDiscoveryCandidate::urgency)
                    .thenBy(GlobalDiscoveryCandidate::stableKey)
            )
            .take(MAX_SCAN_CANDIDATES)
    }

    fun selectForDeliberation(
        candidates: List<GlobalDiscoveryCandidate>,
        state: GlobalProactiveDiscoveryState,
        existingTasks: List<GlobalCognitionTask>,
        settings: GlobalAgentSettings,
        nowMillis: Long,
        maxTasks: Int = MAX_TASKS_PER_SCAN
    ): List<GlobalDiscoveryCandidate> {
        val emittedToday = state.recentEmissionTimestamps.count { nowMillis - it in 0..DAY_MILLIS }
        val remainingBudget = (settings.dailyDiscoveryTaskBudget - emittedToday).coerceAtLeast(0)
        if (remainingBudget == 0) return emptyList()
        val records = state.records.associateBy(GlobalDiscoveryRecord::stableKey)
        val tasksById = existingTasks.associateBy(GlobalCognitionTask::id)
        val changedCooldown = minOf(intervalMillis(settings.discoveryIntervalMillis), CHANGED_FINDING_COOLDOWN_MILLIS)
        return candidates.asSequence()
            .filter { it.score >= MIN_DISCOVERY_SCORE }
            .filter { candidate ->
                val record = records[candidate.stableKey] ?: return@filter true
                if (record.fingerprint != candidate.fingerprint) {
                    return@filter nowMillis - record.emittedAtMillis >= changedCooldown
                }
                val task = tasksById[record.cognitionTaskId]
                task?.status == GlobalCognitionTaskStatus.FAILED &&
                    nowMillis - task.updatedAtMillis >= FAILED_TASK_RETRY_MILLIS
            }
            .take(minOf(maxTasks.coerceIn(1, MAX_TASKS_PER_SCAN), remainingBudget))
            .toList()
    }

    fun task(candidate: GlobalDiscoveryCandidate, nowMillis: Long): GlobalCognitionTask {
        val taskId = cognitionTaskId(candidate)
        val primaryConversationId = candidate.sourceConversationIds.sorted().first()
        val kindLabel = candidate.kind.name.lowercase(Locale.ROOT).replace('_', ' ')
        val event = GlobalConversationEvent(
            id = "global-discovery-event:${GlobalAgentText.stableKey(candidate.stableKey, candidate.fingerprint)}",
            type = GlobalConversationEventType.TASK_UPDATED,
            conversationId = primaryConversationId,
            actor = GlobalConversationActor.TOOL,
            timestampMillis = nowMillis,
            content = buildString {
                append("Periodic Personal ASI world review found a ").append(kindLabel).append(" candidate.\n")
                append(candidate.summary)
                append("\nEvaluate whether this is still material, identify cross-topic implications, and propose only safe, evidence-backed next actions.")
            }.take(12_000),
            contentRef = "encrypted://global-agent/discovery/${candidate.stableKey}",
            conversationTitle = candidate.topic,
            topicHints = setOf(candidate.topic).filter(String::isNotBlank).toSet(),
            metadata = mapOf(
                "origin" to ORIGIN,
                "discovery_key" to candidate.stableKey,
                "discovery_fingerprint" to candidate.fingerprint,
                "discovery_kind" to candidate.kind.name,
                "source_conversation_ids" to candidate.sourceConversationIds.sorted().joinToString(",")
            ),
            causalEventIds = candidate.causalEventIds
        )
        val riskCandidates = when (candidate.kind) {
            GlobalDiscoveryKind.CROSS_TOPIC_CONFLICT,
            GlobalDiscoveryKind.MATERIAL_RISK,
            GlobalDiscoveryKind.STALLED_GOAL -> listOf(candidate.summary)
            GlobalDiscoveryKind.CROSS_TOPIC_SYNTHESIS,
            GlobalDiscoveryKind.HIGH_VALUE_OPPORTUNITY -> emptyList()
        }
        val opportunityCandidates = if (candidate.kind == GlobalDiscoveryKind.HIGH_VALUE_OPPORTUNITY) {
            listOf(candidate.summary)
        } else emptyList()
        return GlobalCognitionTask(
            id = taskId,
            sourceEvent = event,
            baselineUnderstanding = GlobalUnderstanding(
                eventId = event.id,
                topic = candidate.topic,
                relatedTopics = setOf(candidate.topic),
                intent = "proactive_world_review",
                riskCandidates = riskCandidates,
                opportunityCandidates = opportunityCandidates,
                crossConversationIds = candidate.sourceConversationIds - primaryConversationId,
                complexity = maxOf(0.62, candidate.score),
                urgency = candidate.urgency,
                novelty = 0.72,
                uncertainty = 0.35,
                externalResearchUseful = candidate.externalResearchUseful,
                durableFollowUpUseful = true
            ),
            longHorizonGoalId = candidate.longHorizonGoalId,
            createdAtMillis = nowMillis,
            updatedAtMillis = nowMillis
        )
    }

    fun cognitionTaskId(candidate: GlobalDiscoveryCandidate): String =
        "global-discovery:${GlobalAgentText.stableKey(candidate.stableKey, candidate.fingerprint)}"

    fun shouldSurfaceResult(task: GlobalCognitionTask): Boolean =
        task.sourceEvent.metadata["origin"] != ORIGIN ||
            task.result.userInsight.isNotBlank() ||
            task.result.risks.isNotEmpty() ||
            task.result.opportunities.isNotEmpty()

    fun intervalMillis(configured: Long): Long = configured.coerceIn(
        MIN_SCAN_INTERVAL_MILLIS,
        MAX_SCAN_INTERVAL_MILLIS
    )

    fun canClaim(state: GlobalProactiveDiscoveryState, nowMillis: Long, force: Boolean = false): Boolean {
        if (state.scanLeaseExpiresAtMillis > nowMillis) return false
        return force || state.nextScanAtMillis <= nowMillis
    }

    fun nextWakeAt(state: GlobalProactiveDiscoveryState, nowMillis: Long): Long = when {
        state.scanLeaseExpiresAtMillis > nowMillis -> state.scanLeaseExpiresAtMillis
        state.nextScanAtMillis > 0L -> state.nextScanAtMillis.coerceAtLeast(nowMillis + MIN_WAKE_DELAY_MILLIS)
        else -> nowMillis + MIN_WAKE_DELAY_MILLIS
    }

    private fun conflictCandidates(items: List<GlobalWorldItem>): List<GlobalDiscoveryCandidate> = items
        .filter { it.status == GlobalWorldItemStatus.CONFLICTED && it.conflictGroupId.isNotBlank() }
        .groupBy(GlobalWorldItem::conflictGroupId)
        .values
        .filter { it.size >= 2 }
        .map { group ->
            val sorted = group.sortedBy(GlobalWorldItem::stableKey)
            val topic = sorted.maxByOrNull(GlobalWorldItem::confidence)?.topic.orEmpty()
            val values = sorted.map(GlobalWorldItem::value).distinctBy(GlobalAgentText::normalize).take(4)
            val causalIds = sorted.flatMap(GlobalWorldItem::evidenceEventIds).filter(String::isNotBlank).toSet()
            val conversations = sorted.flatMap(GlobalWorldItem::conversationIds).filter(String::isNotBlank).toSet()
            GlobalDiscoveryCandidate(
                stableKey = "conflict:${sorted.first().conflictGroupId}",
                fingerprint = fingerprint(
                    GlobalDiscoveryKind.CROSS_TOPIC_CONFLICT,
                    topic,
                    values,
                    causalIds
                ),
                kind = GlobalDiscoveryKind.CROSS_TOPIC_CONFLICT,
                topic = topic,
                summary = buildString {
                    append("Reconcile this unresolved cross-conversation contradiction:")
                    values.forEach { append("\n- ").append(it.take(800)) }
                },
                sourceConversationIds = conversations,
                causalEventIds = causalIds,
                score = 0.90,
                urgency = sorted.maxOf(GlobalWorldItem::confidence).coerceIn(0.6, 1.0),
                externalResearchUseful = false
            )
        }

    private fun worldItemCandidate(
        item: GlobalWorldItem,
        kind: GlobalDiscoveryKind
    ): GlobalDiscoveryCandidate {
        val evidence = item.evidenceEventIds.filter(String::isNotBlank).toSet()
        val score = when (kind) {
            GlobalDiscoveryKind.MATERIAL_RISK -> (
                0.50 + item.confidence * 0.32 + minOf(item.evidenceCount, 4) * 0.035
                ).coerceIn(0.0, 0.94)
            GlobalDiscoveryKind.HIGH_VALUE_OPPORTUNITY -> (
                0.42 + item.confidence * 0.30 + minOf(item.evidenceCount, 4) * 0.04 +
                    if (item.conversationIds.size >= 2) 0.08 else 0.0
                ).coerceIn(0.0, 0.90)
            else -> item.confidence
        }
        val label = if (kind == GlobalDiscoveryKind.MATERIAL_RISK) "Material risk" else "High-value opportunity"
        return GlobalDiscoveryCandidate(
            stableKey = "${kind.name.lowercase(Locale.ROOT)}:${item.stableKey}",
            fingerprint = fingerprint(kind, item.topic, listOf(item.value), evidence),
            kind = kind,
            topic = item.topic,
            summary = "$label supported by ${item.evidenceCount} authorized observation(s): ${item.value.take(1_500)}",
            sourceConversationIds = item.conversationIds,
            causalEventIds = evidence,
            score = score,
            urgency = if (kind == GlobalDiscoveryKind.MATERIAL_RISK) item.confidence else score * 0.72,
            externalResearchUseful = true
        )
    }

    private fun synthesisCandidates(
        graph: GlobalTopicProjectGraph,
        eligibleItems: List<GlobalWorldItem>,
        excludedConversationIds: Set<String>
    ): List<GlobalDiscoveryCandidate> = graph.activeNodes().asSequence()
        .filter { node ->
            node.confidence >= 0.65 && node.conversationIds.size >= 2 &&
                node.conversationIds.none(excludedConversationIds::contains)
        }
        .mapNotNull { node ->
            val nodeTokens = GlobalAgentText.tokens(node.name)
            val items = eligibleItems.filter { item ->
                item.id in node.worldItemIds || (
                    item.conversationIds.intersect(node.conversationIds).isNotEmpty() &&
                        GlobalAgentText.overlap(nodeTokens, GlobalAgentText.tokens(item.topic)) >= 0.45
                    )
            }.filter { it.kind in setOf(
                GlobalWorldItemKind.GOAL,
                GlobalWorldItemKind.TASK,
                GlobalWorldItemKind.DECISION,
                GlobalWorldItemKind.FACT,
                GlobalWorldItemKind.STATE
            ) }.distinctBy(GlobalWorldItem::stableKey)
            val conversations = items.flatMap(GlobalWorldItem::conversationIds).toSet()
            if (items.size < MIN_SYNTHESIS_ITEMS || conversations.size < 2) return@mapNotNull null
            val evidence = items.flatMap(GlobalWorldItem::evidenceEventIds)
                .filter(String::isNotBlank).toSet()
            val authorizedTopic = items.maxByOrNull(GlobalWorldItem::confidence)?.topic.orEmpty()
            if (authorizedTopic.isBlank()) return@mapNotNull null
            val values = items.sortedWith(
                compareByDescending<GlobalWorldItem> { it.confidence }
                    .thenByDescending(GlobalWorldItem::lastSeenAtMillis)
            ).map(GlobalWorldItem::value).distinctBy(GlobalAgentText::normalize).take(6)
            GlobalDiscoveryCandidate(
                stableKey = "synthesis:${node.stableKey}",
                fingerprint = fingerprint(
                    GlobalDiscoveryKind.CROSS_TOPIC_SYNTHESIS,
                    authorizedTopic,
                    values,
                    evidence
                ),
                kind = GlobalDiscoveryKind.CROSS_TOPIC_SYNTHESIS,
                topic = authorizedTopic,
                summary = buildString {
                    append("Synthesize these related observations to find an unexpressed need, contradiction, risk, or opportunity:")
                    values.forEach { append("\n- ").append(it.take(800)) }
                },
                sourceConversationIds = conversations,
                causalEventIds = evidence,
                score = (
                    0.50 + node.confidence * 0.20 + minOf(items.size, 6) * 0.025 +
                        minOf(conversations.size, 4) * 0.025
                    ).coerceIn(0.0, 0.84),
                urgency = 0.52,
                externalResearchUseful = false
            )
        }
        .toList()

    private fun stalledGoalCandidates(
        goals: List<GlobalLongHorizonGoal>,
        eligibleItems: List<GlobalWorldItem>,
        excludedConversationIds: Set<String>,
        nowMillis: Long
    ): List<GlobalDiscoveryCandidate> = goals.asSequence()
        .filter { it.activeCognitionTaskId.isBlank() && it.activeRunId.isBlank() }
        .filter { it.status in setOf(
            GlobalLongHorizonGoalStatus.ACTIVE,
            GlobalLongHorizonGoalStatus.IN_PROGRESS,
            GlobalLongHorizonGoalStatus.WAITING_DEPENDENCY,
            GlobalLongHorizonGoalStatus.BLOCKED
        ) }
        .mapNotNull { goal ->
            val support = eligibleItems.filter { item ->
                item.evidenceEventIds.any(goal.sourceEventIds.toSet()::contains) || (
                    GlobalAgentText.normalize(item.topic) == GlobalAgentText.normalize(goal.topic) &&
                        GlobalAgentText.overlap(
                            GlobalAgentText.tokens(item.value),
                            GlobalAgentText.tokens(goal.title)
                        ) >= 0.58
                    )
            }
            if (support.isEmpty()) return@mapNotNull null
            val conversations = (goal.sourceConversationIds + support.flatMap(GlobalWorldItem::conversationIds))
                .filter(String::isNotBlank).toSet()
            if (conversations.isEmpty() || conversations.any(excludedConversationIds::contains)) return@mapNotNull null
            val referenceAt = maxOf(goal.lastProgressAtMillis, goal.lastCheckAtMillis, goal.createdAtMillis)
            val stallThreshold = maxOf(MIN_STALLED_GOAL_AGE_MILLIS, goal.checkpointIntervalMillis * 2L)
            val stalled = goal.status == GlobalLongHorizonGoalStatus.BLOCKED ||
                nowMillis - referenceAt >= stallThreshold
            if (!stalled) return@mapNotNull null
            val causalIds = support.flatMap(GlobalWorldItem::evidenceEventIds)
                .filter(String::isNotBlank).toSet()
            val detail = goal.blocker.ifBlank { goal.progressSummary }.ifBlank {
                "No verified progress has been recorded within the expected checkpoint window."
            }
            GlobalDiscoveryCandidate(
                stableKey = "stalled-goal:${goal.stableKey}",
                fingerprint = fingerprint(
                    GlobalDiscoveryKind.STALLED_GOAL,
                    goal.topic,
                    listOf(goal.title, goal.status.name, detail),
                    causalIds
                ),
                kind = GlobalDiscoveryKind.STALLED_GOAL,
                topic = goal.topic,
                summary = "Long-horizon goal needs a revised path: ${goal.title.take(1_000)}\nCurrent evidence: ${detail.take(1_000)}",
                sourceConversationIds = conversations,
                causalEventIds = causalIds,
                score = if (goal.status == GlobalLongHorizonGoalStatus.BLOCKED) 0.84 else 0.74,
                urgency = maxOf(goal.priority, if (goal.status == GlobalLongHorizonGoalStatus.BLOCKED) 0.72 else 0.52),
                externalResearchUseful = true,
                longHorizonGoalId = goal.id
            )
        }
        .toList()

    private fun fingerprint(
        kind: GlobalDiscoveryKind,
        topic: String,
        values: List<String>,
        causalEventIds: Set<String>
    ): String = GlobalAgentText.stableKey(
        kind.name,
        GlobalAgentText.normalize(topic),
        values.joinToString("|") { it.replace(Regex("\\s+"), " ").trim() },
        causalEventIds.sorted().joinToString("|")
    )

    const val ORIGIN = "global_world_discovery"
    const val SCAN_LEASE_MILLIS = 10L * 60L * 1_000L
    const val RETRY_DELAY_MILLIS = 15L * 60L * 1_000L
    private const val MIN_DISCOVERY_SCORE = 0.68
    private const val MAX_SCAN_CANDIDATES = 40
    private const val MIN_SYNTHESIS_ITEMS = 3
    private const val MAX_TASKS_PER_SCAN = 2
    private const val DAY_MILLIS = 24L * 60L * 60L * 1_000L
    private const val CHANGED_FINDING_COOLDOWN_MILLIS = 6L * 60L * 60L * 1_000L
    private const val FAILED_TASK_RETRY_MILLIS = 24L * 60L * 60L * 1_000L
    private const val MIN_STALLED_GOAL_AGE_MILLIS = 24L * 60L * 60L * 1_000L
    private const val MIN_SCAN_INTERVAL_MILLIS = 60L * 60L * 1_000L
    private const val MAX_SCAN_INTERVAL_MILLIS = 7L * 24L * 60L * 60L * 1_000L
    private const val MIN_WAKE_DELAY_MILLIS = 60_000L
}

object GlobalProactiveDiscoveryCodec {
    fun encode(state: GlobalProactiveDiscoveryState): JSONObject = JSONObject()
        .put("next_scan_at_millis", state.nextScanAtMillis)
        .put("scan_lease_expires_at_millis", state.scanLeaseExpiresAtMillis)
        .put("last_started_at_millis", state.lastStartedAtMillis)
        .put("last_completed_at_millis", state.lastCompletedAtMillis)
        .put("scan_sequence", state.scanSequence)
        .put("recent_emission_timestamps", JSONArray(state.recentEmissionTimestamps.takeLast(MAX_EMISSIONS)))
        .put("records", JSONArray().apply {
            state.records.takeLast(MAX_RECORDS).forEach { record ->
                put(JSONObject()
                    .put("stable_key", record.stableKey)
                    .put("fingerprint", record.fingerprint)
                    .put("cognition_task_id", record.cognitionTaskId)
                    .put("emitted_at_millis", record.emittedAtMillis))
            }
        })
        .put("last_error", state.lastError.take(600))

    fun decode(raw: String): GlobalProactiveDiscoveryState = runCatching {
        if (raw.isBlank()) return@runCatching GlobalProactiveDiscoveryState()
        decode(JSONObject(raw))
    }.getOrDefault(GlobalProactiveDiscoveryState())

    fun decode(json: JSONObject): GlobalProactiveDiscoveryState {
        val timestamps = json.optJSONArray("recent_emission_timestamps")
        val records = json.optJSONArray("records")
        return GlobalProactiveDiscoveryState(
            nextScanAtMillis = json.optLong("next_scan_at_millis").coerceAtLeast(0L),
            scanLeaseExpiresAtMillis = json.optLong("scan_lease_expires_at_millis").coerceAtLeast(0L),
            lastStartedAtMillis = json.optLong("last_started_at_millis").coerceAtLeast(0L),
            lastCompletedAtMillis = json.optLong("last_completed_at_millis").coerceAtLeast(0L),
            scanSequence = json.optLong("scan_sequence").coerceAtLeast(0L),
            recentEmissionTimestamps = buildList {
                if (timestamps != null) for (index in 0 until timestamps.length()) {
                    timestamps.optLong(index).takeIf { it > 0L }?.let(::add)
                }
            }.takeLast(MAX_EMISSIONS),
            records = buildList {
                if (records != null) for (index in 0 until records.length()) {
                    val item = records.optJSONObject(index) ?: continue
                    val stableKey = item.optString("stable_key")
                    val fingerprint = item.optString("fingerprint")
                    val taskId = item.optString("cognition_task_id")
                    if (stableKey.isBlank() || fingerprint.isBlank() || taskId.isBlank()) continue
                    add(GlobalDiscoveryRecord(
                        stableKey = stableKey.take(240),
                        fingerprint = fingerprint.take(160),
                        cognitionTaskId = taskId.take(240),
                        emittedAtMillis = item.optLong("emitted_at_millis").coerceAtLeast(0L)
                    ))
                }
            }.takeLast(MAX_RECORDS),
            lastError = json.optString("last_error").take(600)
        )
    }

    private const val MAX_RECORDS = 400
    private const val MAX_EMISSIONS = 200
}

class GlobalProactiveDiscoveryStore(context: Context) {
    private val database = AgentEncryptedDatabase(
        context.applicationContext,
        GlobalAgentRepository.DATABASE_NAME
    )

    fun state(): GlobalProactiveDiscoveryState = synchronized(STORE_LOCK) {
        GlobalProactiveDiscoveryCodec.decode(database.readString(KEY_STATE, ""))
    }

    fun save(state: GlobalProactiveDiscoveryState) = synchronized(STORE_LOCK) {
        database.writeString(KEY_STATE, GlobalProactiveDiscoveryCodec.encode(state).toString())
    }

    fun makeDue(nowMillis: Long = System.currentTimeMillis()) = synchronized(STORE_LOCK) {
        val current = state()
        save(current.copy(nextScanAtMillis = minOf(
            current.nextScanAtMillis.takeIf { it > 0L } ?: Long.MAX_VALUE,
            nowMillis
        )))
    }

    fun claim(nowMillis: Long, force: Boolean = false): GlobalDiscoveryScanClaim? = synchronized(STORE_LOCK) {
        val current = state()
        if (!GlobalProactiveDiscoveryPolicy.canClaim(current, nowMillis, force)) return@synchronized null
        val claim = GlobalDiscoveryScanClaim(
            sequence = current.scanSequence + 1L,
            leaseExpiresAtMillis = nowMillis + GlobalProactiveDiscoveryPolicy.SCAN_LEASE_MILLIS
        )
        save(current.copy(
            scanLeaseExpiresAtMillis = claim.leaseExpiresAtMillis,
            lastStartedAtMillis = nowMillis,
            scanSequence = claim.sequence,
            lastError = ""
        ))
        claim
    }

    fun complete(
        claim: GlobalDiscoveryScanClaim,
        emitted: List<GlobalDiscoveryCandidate>,
        nowMillis: Long,
        intervalMillis: Long
    ): GlobalProactiveDiscoveryState = synchronized(STORE_LOCK) {
        val current = state()
        if (current.scanSequence != claim.sequence) return@synchronized current
        val records = current.records.associateBy(GlobalDiscoveryRecord::stableKey).toMutableMap()
        emitted.forEach { candidate ->
            records[candidate.stableKey] = GlobalDiscoveryRecord(
                stableKey = candidate.stableKey,
                fingerprint = candidate.fingerprint,
                cognitionTaskId = GlobalProactiveDiscoveryPolicy.cognitionTaskId(candidate),
                emittedAtMillis = nowMillis
            )
        }
        current.copy(
            nextScanAtMillis = nowMillis + GlobalProactiveDiscoveryPolicy.intervalMillis(intervalMillis),
            scanLeaseExpiresAtMillis = 0L,
            lastCompletedAtMillis = nowMillis,
            recentEmissionTimestamps = (current.recentEmissionTimestamps + emitted.map { nowMillis })
                .filter { nowMillis - it <= RETAIN_EMISSIONS_MILLIS }
                .takeLast(MAX_EMISSIONS),
            records = records.values.sortedBy(GlobalDiscoveryRecord::emittedAtMillis).takeLast(MAX_RECORDS),
            lastError = ""
        ).also(::save)
    }

    fun fail(
        claim: GlobalDiscoveryScanClaim,
        nowMillis: Long,
        error: String
    ): GlobalProactiveDiscoveryState = synchronized(STORE_LOCK) {
        val current = state()
        if (current.scanSequence != claim.sequence) return@synchronized current
        current.copy(
            nextScanAtMillis = nowMillis + GlobalProactiveDiscoveryPolicy.RETRY_DELAY_MILLIS,
            scanLeaseExpiresAtMillis = 0L,
            lastError = error.take(600)
        ).also(::save)
    }

    fun export(): JSONObject = synchronized(STORE_LOCK) { GlobalProactiveDiscoveryCodec.encode(state()) }

    fun restore(json: JSONObject) = save(GlobalProactiveDiscoveryCodec.decode(json))

    private companion object {
        const val KEY_STATE = "proactive_discovery_state"
        const val MAX_RECORDS = 400
        const val MAX_EMISSIONS = 200
        const val RETAIN_EMISSIONS_MILLIS = 7L * 24L * 60L * 60L * 1_000L
        val STORE_LOCK = Any()
    }
}

class GlobalProactiveDiscoveryCoordinator(context: Context) {
    private val appContext = context.applicationContext
    private val repository = GlobalAgentRepository(appContext)
    private val deliberationStore = GlobalAgentDeliberationStore(appContext)
    private val goalStore = GlobalLongHorizonGoalStore(appContext)
    private val discoveryStore = GlobalProactiveDiscoveryStore(appContext)

    fun processDue(
        nowMillis: Long = System.currentTimeMillis(),
        force: Boolean = false,
        maxTasks: Int = 2
    ): GlobalProactiveDiscoveryCycleResult {
        val settings = repository.settings()
        if (!settings.enabled || !settings.proactiveDiscoveryEnabled || !settings.modelUnderstandingEnabled) {
            return GlobalProactiveDiscoveryCycleResult(false, 0, 0, 0L)
        }
        val claim = discoveryStore.claim(nowMillis, force)
            ?: return GlobalProactiveDiscoveryCycleResult(
                scanned = false,
                candidateCount = 0,
                queuedTaskCount = 0,
                nextWakeAtMillis = nextWakeAt(nowMillis)
            )
        return runCatching {
            val candidates = GlobalProactiveDiscoveryPolicy.scan(
                world = repository.loadWorld(),
                goals = goalStore.goals(),
                excludedConversationIds = repository.excludedConversationIds(),
                nowMillis = nowMillis,
                topicGraph = repository.topicGraph()
            )
            val selected = GlobalProactiveDiscoveryPolicy.selectForDeliberation(
                candidates = candidates,
                state = discoveryStore.state(),
                existingTasks = deliberationStore.cognitionTasks(),
                settings = settings,
                nowMillis = nowMillis,
                maxTasks = maxTasks
            )
            selected.forEach { candidate ->
                deliberationStore.upsertCognitionTask(GlobalProactiveDiscoveryPolicy.task(candidate, nowMillis))
            }
            val completed = discoveryStore.complete(
                claim,
                selected,
                nowMillis,
                settings.discoveryIntervalMillis
            )
            GlobalProactiveDiscoveryCycleResult(
                scanned = true,
                candidateCount = candidates.size,
                queuedTaskCount = selected.size,
                nextWakeAtMillis = GlobalProactiveDiscoveryPolicy.nextWakeAt(completed, nowMillis)
            )
        }.getOrElse { error ->
            val failed = discoveryStore.fail(claim, nowMillis, error.message.orEmpty())
            GlobalProactiveDiscoveryCycleResult(
                scanned = true,
                candidateCount = 0,
                queuedTaskCount = 0,
                nextWakeAtMillis = GlobalProactiveDiscoveryPolicy.nextWakeAt(failed, nowMillis),
                error = error.message.orEmpty().take(600)
            )
        }
    }

    fun state(): GlobalProactiveDiscoveryState = discoveryStore.state()

    fun requestImmediateScan(nowMillis: Long = System.currentTimeMillis()) = discoveryStore.makeDue(nowMillis)

    fun nextWakeAt(nowMillis: Long = System.currentTimeMillis()): Long {
        val settings = repository.settings()
        if (!settings.enabled || !settings.proactiveDiscoveryEnabled || !settings.modelUnderstandingEnabled) return 0L
        return GlobalProactiveDiscoveryPolicy.nextWakeAt(discoveryStore.state(), nowMillis)
    }
}
