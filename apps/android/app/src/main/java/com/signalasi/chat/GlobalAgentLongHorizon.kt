package com.signalasi.chat

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import java.util.UUID

enum class GlobalRunReviewStatus {
    NONE,
    PENDING,
    RUNNING,
    WAITING_FOR_RESOURCE,
    COMPLETED,
    FAILED
}

enum class GlobalGoalProgressState {
    ACTIVE,
    COMPLETED,
    BLOCKED,
    PAUSED
}

data class GlobalRunReplanDecision(
    val goalState: GlobalGoalProgressState = GlobalGoalProgressState.ACTIVE,
    val summary: String = "",
    val cancelActionIds: Set<String> = emptySet(),
    val actions: List<GlobalAutonomousAction> = emptyList(),
    val nextCheckHours: Int = 24,
    val confidence: Double = 0.0
)

data class GlobalAutonomousRunReview(
    val status: GlobalRunReviewStatus = GlobalRunReviewStatus.NONE,
    val reason: String = "",
    val resourceId: String = "",
    val attemptedResourceIds: List<String> = emptyList(),
    val sourceMessageId: Long = 0L,
    val attemptCount: Int = 0,
    val nextAttemptAtMillis: Long = 0L,
    val leaseExpiresAtMillis: Long = 0L,
    val lastError: String = "",
    val decision: GlobalRunReplanDecision = GlobalRunReplanDecision(),
    val createdAtMillis: Long = 0L,
    val updatedAtMillis: Long = 0L
)

object GlobalRunReplanParser {
    fun parse(raw: String): GlobalRunReplanDecision? {
        val clean = raw.trim()
            .removePrefix("```json").removePrefix("```")
            .removeSuffix("```").trim()
        val start = clean.indexOf('{')
        val end = clean.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        val json = runCatching { JSONObject(clean.substring(start, end + 1)) }.getOrNull() ?: return null
        val state = enumValue(json.optString("goal_state"), GlobalGoalProgressState.ACTIVE)
        val actions = buildList {
            val array = json.optJSONArray("actions") ?: JSONArray()
            for (index in 0 until minOf(array.length(), MAX_ACTIONS)) {
                val item = array.optJSONObject(index) ?: continue
                val kind = enumValueOrNull<GlobalAutonomousActionKind>(item.optString("kind")) ?: continue
                val goal = cleanText(item.optString("goal"), 1_000)
                if (goal.isBlank()) continue
                add(GlobalAutonomousAction(
                    kind = kind,
                    goal = goal,
                    rationale = cleanText(item.optString("rationale"), 600),
                    expectedResult = cleanText(item.optString("expected_result"), 600),
                    targetTopic = cleanText(item.optString("target_topic"), 160),
                    priority = item.optDouble("priority", 0.5).coerceIn(0.0, 1.0),
                    externalEffect = item.optBoolean("external_effect", false),
                    reversible = item.optBoolean("reversible", true)
                ))
            }
        }.distinctBy { GlobalAgentText.stableKey(it.kind.name, it.goal) }
        val summary = cleanText(json.optString("summary"), 2_000)
        if (summary.isBlank() && actions.isEmpty() && state == GlobalGoalProgressState.ACTIVE) return null
        return GlobalRunReplanDecision(
            goalState = state,
            summary = summary,
            cancelActionIds = strings(json.optJSONArray("cancel_action_ids"), 12, 80).toSet(),
            actions = actions,
            nextCheckHours = json.optInt("next_check_hours", 24).coerceIn(1, 24 * 30),
            confidence = json.optDouble("confidence", 0.5).coerceIn(0.0, 1.0)
        )
    }

    private fun strings(array: JSONArray?, limit: Int, maxCharacters: Int): List<String> {
        if (array == null) return emptyList()
        return buildList {
            for (index in 0 until minOf(array.length(), limit * 2)) {
                val value = cleanText(array.optString(index), maxCharacters)
                if (value.isNotBlank()) add(value)
                if (size >= limit) break
            }
        }.distinct()
    }

    private fun cleanText(value: String, maxCharacters: Int): String =
        value.replace(Regex("\\s+"), " ").trim().take(maxCharacters)

    private inline fun <reified T : Enum<T>> enumValue(value: String, fallback: T): T =
        enumValueOrNull<T>(value) ?: fallback

    private inline fun <reified T : Enum<T>> enumValueOrNull(value: String): T? {
        val normalized = value.trim().uppercase(Locale.ROOT).replace('-', '_').replace(' ', '_')
        return enumValues<T>().firstOrNull { it.name == normalized }
    }

    private const val MAX_ACTIONS = 6
}

object GlobalAutonomousReplanPolicy {
    fun shouldReview(
        run: GlobalAutonomousRun,
        action: GlobalAutonomousAction,
        succeeded: Boolean,
        result: String,
        enabled: Boolean,
        maxReplans: Int
    ): Boolean {
        if (!enabled || run.replanCount >= maxReplans.coerceIn(1, MAX_REPLANS)) return false
        if (run.review.status in setOf(GlobalRunReviewStatus.PENDING, GlobalRunReviewStatus.RUNNING)) return false
        if (!succeeded) return true
        val pendingAfterCurrent = run.actions.any {
            it.id != action.id && it.status == GlobalAutonomousActionStatus.PENDING
        }
        val discoveryStep = action.kind in setOf(
            GlobalAutonomousActionKind.ANALYZE,
            GlobalAutonomousActionKind.READ_ONLY_CHECK
        )
        val normalized = result.lowercase(Locale.ROOT)
        val changedAssumptions = OUTCOME_REVIEW_SIGNALS.any(normalized::contains)
        return changedAssumptions || (discoveryStep && pendingAfterCurrent)
    }

    fun requestReview(
        run: GlobalAutonomousRun,
        reason: String,
        nowMillis: Long = System.currentTimeMillis()
    ): GlobalAutonomousRun = run.copy(
        status = GlobalAutonomousRunStatus.REPLANNING,
        review = GlobalAutonomousRunReview(
            status = GlobalRunReviewStatus.PENDING,
            reason = reason.take(600),
            nextAttemptAtMillis = nowMillis,
            createdAtMillis = nowMillis,
            updatedAtMillis = nowMillis
        ),
        nextAttemptAtMillis = nowMillis,
        leaseExpiresAtMillis = 0L,
        updatedAtMillis = nowMillis
    )

    fun applyDecision(
        run: GlobalAutonomousRun,
        decision: GlobalRunReplanDecision,
        nowMillis: Long = System.currentTimeMillis()
    ): GlobalAutonomousRun {
        val cancelled = run.actions.map { action ->
            if (action.id in decision.cancelActionIds &&
                action.status == GlobalAutonomousActionStatus.PENDING
            ) action.copy(
                status = GlobalAutonomousActionStatus.SKIPPED,
                lastError = "Superseded by plan revision",
                completedAtMillis = nowMillis
            ) else action
        }
        val existingKeys = cancelled.map { GlobalAgentText.stableKey(it.kind.name, it.goal) }.toSet()
        val additions = decision.actions
            .filterNot { GlobalAgentText.stableKey(it.kind.name, it.goal) in existingKeys }
            .map { action ->
                if (action.requiresConfirmation) {
                    action.copy(status = GlobalAutonomousActionStatus.WAITING_CONFIRMATION)
                } else action.copy(status = GlobalAutonomousActionStatus.PENDING)
            }
            .take((MAX_RUN_ACTIONS - cancelled.size).coerceAtLeast(0))
        var actions = cancelled + additions
        if (decision.goalState == GlobalGoalProgressState.COMPLETED) {
            actions = actions.map { action ->
                if (action.status in setOf(
                        GlobalAutonomousActionStatus.PENDING,
                        GlobalAutonomousActionStatus.WAITING_CONFIRMATION
                    )
                ) action.copy(
                    status = GlobalAutonomousActionStatus.SKIPPED,
                    lastError = "The goal was satisfied before this step was needed",
                    completedAtMillis = nowMillis
                ) else action
            }
        }
        val nextStatus = when (decision.goalState) {
            GlobalGoalProgressState.COMPLETED -> GlobalAutonomousRunStatus.COMPLETED
            GlobalGoalProgressState.BLOCKED,
            GlobalGoalProgressState.PAUSED -> GlobalAutonomousRunStatus.PAUSED
            GlobalGoalProgressState.ACTIVE -> GlobalAutonomousRunPolicy.terminalStatus(actions)
                ?: if (actions.any { it.status == GlobalAutonomousActionStatus.PENDING }) {
                    GlobalAutonomousRunStatus.QUEUED
                } else GlobalAutonomousRunStatus.WAITING_CONFIRMATION
        }
        return run.copy(
            actions = actions,
            status = nextStatus,
            revision = run.revision + 1,
            replanCount = run.replanCount + 1,
            outcomeSummary = decision.summary,
            review = run.review.copy(
                status = GlobalRunReviewStatus.COMPLETED,
                sourceMessageId = 0L,
                leaseExpiresAtMillis = 0L,
                lastError = "",
                decision = decision,
                updatedAtMillis = nowMillis
            ),
            nextAttemptAtMillis = if (nextStatus == GlobalAutonomousRunStatus.QUEUED) nowMillis else 0L,
            leaseExpiresAtMillis = 0L,
            lastError = if (decision.goalState == GlobalGoalProgressState.BLOCKED) decision.summary else "",
            updatedAtMillis = nowMillis
        )
    }

    fun recoverIfStale(run: GlobalAutonomousRun, nowMillis: Long): GlobalAutonomousRun {
        val review = run.review
        if (run.status != GlobalAutonomousRunStatus.REPLANNING ||
            review.status != GlobalRunReviewStatus.RUNNING ||
            review.leaseExpiresAtMillis <= 0L || review.leaseExpiresAtMillis > nowMillis
        ) return run
        return run.copy(
            status = GlobalAutonomousRunStatus.REPLANNING,
            review = review.copy(
                status = GlobalRunReviewStatus.WAITING_FOR_RESOURCE,
                attemptedResourceIds = (review.attemptedResourceIds + review.resourceId)
                    .filter(String::isNotBlank).distinct(),
                sourceMessageId = 0L,
                nextAttemptAtMillis = nowMillis,
                leaseExpiresAtMillis = 0L,
                lastError = "The plan review lease expired before a result arrived",
                updatedAtMillis = nowMillis
            ),
            nextAttemptAtMillis = nowMillis,
            leaseExpiresAtMillis = 0L,
            updatedAtMillis = nowMillis
        )
    }

    const val LEASE_MILLIS = 4L * 60L * 1_000L
    const val MAX_REVIEW_ATTEMPTS = 3
    private const val MAX_REPLANS = 5
    private const val MAX_RUN_ACTIONS = 12
    private val OUTCOME_REVIEW_SIGNALS = listOf(
        "blocked", "failed", "failure", "missing", "cannot", "unable", "uncertain",
        "requires", "not available", "conflict", "changed assumption", "incomplete"
    )
}

enum class GlobalLongHorizonGoalStatus {
    ACTIVE,
    IN_PROGRESS,
    WAITING_CONFIRMATION,
    BLOCKED,
    COMPLETED,
    PAUSED
}

data class GlobalLongHorizonGoal(
    val id: String = UUID.randomUUID().toString(),
    val stableKey: String,
    val topic: String,
    val title: String,
    val description: String = "",
    val status: GlobalLongHorizonGoalStatus = GlobalLongHorizonGoalStatus.ACTIVE,
    val priority: Double = 0.5,
    val confidence: Double = 0.5,
    val sourceConversationIds: Set<String> = emptySet(),
    val sourceEventIds: List<String> = emptyList(),
    val checkpointIntervalMillis: Long = DEFAULT_CHECKPOINT_MILLIS,
    val nextCheckAtMillis: Long = System.currentTimeMillis() + DEFAULT_CHECKPOINT_MILLIS,
    val lastCheckAtMillis: Long = 0L,
    val lastProgressAtMillis: Long = 0L,
    val checkpointCount: Int = 0,
    val activeCognitionTaskId: String = "",
    val activeRunId: String = "",
    val progressSummary: String = "",
    val blocker: String = "",
    val createdAtMillis: Long = System.currentTimeMillis(),
    val updatedAtMillis: Long = System.currentTimeMillis()
) {
    companion object {
        const val DEFAULT_CHECKPOINT_MILLIS = 24L * 60L * 60L * 1_000L
    }
}

object GlobalLongHorizonGoalPolicy {
    fun mergeCognition(
        task: GlobalCognitionTask,
        current: List<GlobalLongHorizonGoal>,
        nowMillis: Long = System.currentTimeMillis()
    ): List<GlobalLongHorizonGoal> {
        if (task.longHorizonGoalId.isNotBlank()) return current
        val durable = task.baselineUnderstanding.durableFollowUpUseful ||
            task.result.actions.any {
                it.kind in setOf(GlobalAutonomousActionKind.CREATE_TOPIC, GlobalAutonomousActionKind.START_MONITOR)
            }
        if (!durable) return current
        val topic = task.result.topic.ifBlank { task.baselineUnderstanding.topic }.take(160)
        val candidates = task.result.goals.ifEmpty { task.baselineUnderstanding.goalCandidates }
            .filter(String::isNotBlank)
            .distinctBy(GlobalAgentText::normalize)
            .take(MAX_GOALS_PER_COGNITION)
        if (candidates.isEmpty()) return current
        val mutable = current.toMutableList()
        candidates.forEach { title ->
            val clean = title.replace(Regex("\\s+"), " ").trim().take(1_000)
            val stableKey = GlobalAgentText.stableKey("long-horizon-goal", topic, clean)
            val index = mutable.indexOfFirst { goal ->
                goal.stableKey == stableKey || (
                    GlobalAgentText.normalize(goal.topic) == GlobalAgentText.normalize(topic) &&
                        GlobalAgentText.overlap(
                            GlobalAgentText.tokens(goal.title),
                            GlobalAgentText.tokens(clean)
                        ) >= 0.72
                    )
            }
            val priority = maxOf(
                task.baselineUnderstanding.urgency,
                task.result.actions.maxOfOrNull(GlobalAutonomousAction::priority) ?: 0.5
            ).coerceIn(0.1, 1.0)
            val interval = checkpointInterval(priority)
            if (index >= 0) {
                val existing = mutable[index]
                mutable[index] = existing.copy(
                    topic = topic.ifBlank { existing.topic },
                    title = clean,
                    description = task.sourceEvent.content.take(2_000),
                    status = if (existing.status == GlobalLongHorizonGoalStatus.COMPLETED) {
                        existing.status
                    } else GlobalLongHorizonGoalStatus.ACTIVE,
                    priority = maxOf(existing.priority, priority),
                    confidence = maxOf(existing.confidence, task.result.confidence),
                    sourceConversationIds = (existing.sourceConversationIds + task.sourceEvent.conversationId).take(20).toSet(),
                    sourceEventIds = (existing.sourceEventIds + task.sourceEvent.id).distinct().takeLast(30),
                    checkpointIntervalMillis = minOf(existing.checkpointIntervalMillis, interval),
                    nextCheckAtMillis = minOf(existing.nextCheckAtMillis, nowMillis + interval),
                    updatedAtMillis = nowMillis
                )
            } else {
                mutable += GlobalLongHorizonGoal(
                    stableKey = stableKey,
                    topic = topic,
                    title = clean,
                    description = task.sourceEvent.content.take(2_000),
                    priority = priority,
                    confidence = task.result.confidence.coerceIn(0.35, 1.0),
                    sourceConversationIds = setOf(task.sourceEvent.conversationId),
                    sourceEventIds = listOf(task.sourceEvent.id),
                    checkpointIntervalMillis = interval,
                    nextCheckAtMillis = nowMillis + interval,
                    createdAtMillis = nowMillis,
                    updatedAtMillis = nowMillis
                )
            }
        }
        return mutable.sortedBy(GlobalLongHorizonGoal::createdAtMillis).takeLast(MAX_GOALS)
    }

    fun checkpointInterval(priority: Double): Long = when {
        priority >= 0.90 -> 60L * 60L * 1_000L
        priority >= 0.72 -> 6L * 60L * 60L * 1_000L
        priority >= 0.50 -> 24L * 60L * 60L * 1_000L
        else -> 3L * 24L * 60L * 60L * 1_000L
    }

    fun mergeWorld(
        world: PersonalWorldModel,
        current: List<GlobalLongHorizonGoal>,
        nowMillis: Long = System.currentTimeMillis()
    ): List<GlobalLongHorizonGoal> {
        val activeTaskTopics = world.items.asSequence()
            .filter { it.kind == GlobalWorldItemKind.TASK && it.status == GlobalWorldItemStatus.ACTIVE }
            .map { GlobalAgentText.normalize(it.topic) }
            .filter(String::isNotBlank)
            .toSet()
        val candidates = world.items.asSequence()
            .filter { it.kind == GlobalWorldItemKind.GOAL && it.status == GlobalWorldItemStatus.ACTIVE }
            .filter { it.confidence >= 0.65 }
            .filter { item ->
                item.evidenceCount >= 2 || item.conversationIds.size >= 2 ||
                    GlobalAgentText.normalize(item.topic) in activeTaskTopics ||
                    DURABLE_SIGNALS.any { signal ->
                        "${item.topic} ${item.value}".lowercase(Locale.ROOT).contains(signal)
                    }
            }
            .sortedWith(compareByDescending<GlobalWorldItem> { it.evidenceCount }
                .thenByDescending { it.confidence }
                .thenByDescending { it.lastSeenAtMillis })
            .take(MAX_WORLD_GOALS)
            .toList()
        if (candidates.isEmpty()) return current
        val mutable = current.toMutableList()
        candidates.forEach { item ->
            val stableKey = GlobalAgentText.stableKey("long-horizon-goal", item.topic, item.value)
            val index = mutable.indexOfFirst { goal ->
                goal.stableKey == stableKey || (
                    GlobalAgentText.normalize(goal.topic) == GlobalAgentText.normalize(item.topic) &&
                        GlobalAgentText.overlap(
                            GlobalAgentText.tokens(goal.title),
                            GlobalAgentText.tokens(item.value)
                        ) >= 0.72
                    )
            }
            val priority = (
                item.confidence * 0.72 +
                    item.evidenceCount.coerceAtMost(4) * 0.05 +
                    if (item.conversationIds.size >= 2) 0.08 else 0.0
                ).coerceIn(0.1, 1.0)
            val interval = checkpointInterval(priority)
            if (index >= 0) {
                val existing = mutable[index]
                val merged = existing.copy(
                    priority = maxOf(existing.priority, priority),
                    confidence = maxOf(existing.confidence, item.confidence),
                    sourceConversationIds = (existing.sourceConversationIds + item.conversationIds).take(20).toSet(),
                    sourceEventIds = (existing.sourceEventIds + item.evidenceEventIds).distinct().takeLast(30),
                    checkpointIntervalMillis = minOf(existing.checkpointIntervalMillis, interval),
                    nextCheckAtMillis = if (existing.status in setOf(
                            GlobalLongHorizonGoalStatus.COMPLETED,
                            GlobalLongHorizonGoalStatus.PAUSED
                        )
                    ) existing.nextCheckAtMillis else minOf(
                        existing.nextCheckAtMillis.takeIf { it > 0L } ?: Long.MAX_VALUE,
                        nowMillis + interval
                    ),
                    updatedAtMillis = existing.updatedAtMillis
                )
                mutable[index] = if (merged == existing) merged else merged.copy(updatedAtMillis = nowMillis)
            } else {
                mutable += GlobalLongHorizonGoal(
                    stableKey = stableKey,
                    topic = item.topic,
                    title = item.value.take(1_000),
                    description = "Derived from ${item.evidenceCount} authorized world-model observations",
                    priority = priority,
                    confidence = item.confidence,
                    sourceConversationIds = item.conversationIds,
                    sourceEventIds = item.evidenceEventIds,
                    checkpointIntervalMillis = interval,
                    nextCheckAtMillis = nowMillis + minOf(interval, INITIAL_CHECKPOINT_DELAY_MILLIS),
                    createdAtMillis = nowMillis,
                    updatedAtMillis = nowMillis
                )
            }
        }
        return mutable.sortedBy(GlobalLongHorizonGoal::createdAtMillis).takeLast(MAX_GOALS)
    }

    fun nextDue(goals: List<GlobalLongHorizonGoal>, nowMillis: Long): List<GlobalLongHorizonGoal> = goals
        .filter {
            it.status in setOf(GlobalLongHorizonGoalStatus.ACTIVE, GlobalLongHorizonGoalStatus.BLOCKED) &&
                it.activeCognitionTaskId.isBlank() && it.activeRunId.isBlank() &&
                it.nextCheckAtMillis <= nowMillis
        }
        .sortedWith(compareByDescending<GlobalLongHorizonGoal> { it.priority }.thenBy { it.nextCheckAtMillis })

    fun applyGoalStatesToWorld(
        world: PersonalWorldModel,
        goals: List<GlobalLongHorizonGoal>,
        nowMillis: Long = System.currentTimeMillis()
    ): PersonalWorldModel {
        val completed = goals.filter {
            it.status == GlobalLongHorizonGoalStatus.COMPLETED && it.confidence >= 0.65
        }
        if (completed.isEmpty()) return world
        var changed = false
        val items = world.items.map { item ->
            if (item.kind != GlobalWorldItemKind.GOAL || item.status == GlobalWorldItemStatus.COMPLETED) {
                return@map item
            }
            val match = completed.any { goal ->
                GlobalAgentText.normalize(goal.topic) == GlobalAgentText.normalize(item.topic) &&
                    GlobalAgentText.overlap(
                        GlobalAgentText.tokens(goal.title),
                        GlobalAgentText.tokens(item.value)
                    ) >= 0.72
            }
            if (!match) item else {
                changed = true
                item.copy(status = GlobalWorldItemStatus.COMPLETED, lastSeenAtMillis = nowMillis)
            }
        }
        return if (changed) world.copy(items = items, updatedAtMillis = nowMillis) else world
    }

    private const val MAX_GOALS_PER_COGNITION = 4
    private const val MAX_WORLD_GOALS = 80
    private const val MAX_GOALS = 200
    private const val INITIAL_CHECKPOINT_DELAY_MILLIS = 5L * 60L * 1_000L
    private val DURABLE_SIGNALS = listOf(
        "long term", "ongoing", "project", "roadmap", "monitor", "track",
        "\u957f\u671f", "\u6301\u7eed", "\u9879\u76ee", "\u8def\u7ebf\u56fe", "\u76d1\u63a7", "\u8ddf\u8e2a"
    )
}

class GlobalLongHorizonGoalStore(context: Context) {
    private val database = AgentEncryptedDatabase(
        context.applicationContext,
        GlobalAgentRepository.DATABASE_NAME
    )

    fun goals(): List<GlobalLongHorizonGoal> = synchronized(STORE_LOCK) { load() }

    fun save(goals: List<GlobalLongHorizonGoal>) = synchronized(STORE_LOCK) {
        val array = JSONArray()
        goals.sortedBy(GlobalLongHorizonGoal::createdAtMillis).takeLast(MAX_GOALS)
            .forEach { array.put(encode(it)) }
        database.writeString(KEY_GOALS, array.toString())
    }

    fun upsert(goal: GlobalLongHorizonGoal) = synchronized(STORE_LOCK) {
        save(load().filterNot { it.id == goal.id } + goal)
    }

    fun update(
        goalId: String,
        transform: (GlobalLongHorizonGoal) -> GlobalLongHorizonGoal
    ): GlobalLongHorizonGoal? = synchronized(STORE_LOCK) {
        val goals = load().toMutableList()
        val index = goals.indexOfFirst { it.id == goalId }
        if (index < 0) return@synchronized null
        val updated = transform(goals[index])
        goals[index] = updated
        save(goals)
        updated
    }

    fun export(): JSONArray = synchronized(STORE_LOCK) {
        JSONArray().apply { load().forEach { put(encode(it)) } }
    }

    fun restore(array: JSONArray) = synchronized(STORE_LOCK) {
        save(buildList {
            for (index in 0 until array.length()) decode(array.optJSONObject(index))?.let(::add)
        })
    }

    private fun load(): List<GlobalLongHorizonGoal> = runCatching {
        val array = JSONArray(database.readString(KEY_GOALS, "[]"))
        buildList {
            for (index in 0 until array.length()) decode(array.optJSONObject(index))?.let(::add)
        }
    }.getOrDefault(emptyList())

    private fun encode(goal: GlobalLongHorizonGoal): JSONObject = JSONObject()
        .put("id", goal.id)
        .put("stable_key", goal.stableKey)
        .put("topic", goal.topic)
        .put("title", goal.title)
        .put("description", goal.description)
        .put("status", goal.status.name)
        .put("priority", goal.priority)
        .put("confidence", goal.confidence)
        .put("source_conversation_ids", JSONArray(goal.sourceConversationIds.toList()))
        .put("source_event_ids", JSONArray(goal.sourceEventIds))
        .put("checkpoint_interval_millis", goal.checkpointIntervalMillis)
        .put("next_check_at_millis", goal.nextCheckAtMillis)
        .put("last_check_at_millis", goal.lastCheckAtMillis)
        .put("last_progress_at_millis", goal.lastProgressAtMillis)
        .put("checkpoint_count", goal.checkpointCount)
        .put("active_cognition_task_id", goal.activeCognitionTaskId)
        .put("active_run_id", goal.activeRunId)
        .put("progress_summary", goal.progressSummary)
        .put("blocker", goal.blocker)
        .put("created_at_millis", goal.createdAtMillis)
        .put("updated_at_millis", goal.updatedAtMillis)

    private fun decode(json: JSONObject?): GlobalLongHorizonGoal? {
        if (json == null) return null
        val title = json.optString("title").take(1_000)
        if (title.isBlank()) return null
        return GlobalLongHorizonGoal(
            id = json.optString("id").ifBlank { UUID.randomUUID().toString() },
            stableKey = json.optString("stable_key").ifBlank {
                GlobalAgentText.stableKey("long-horizon-goal", json.optString("topic"), title)
            },
            topic = json.optString("topic").take(160),
            title = title,
            description = json.optString("description").take(2_000),
            status = enumValue(json.optString("status"), GlobalLongHorizonGoalStatus.ACTIVE),
            priority = json.optDouble("priority", 0.5).coerceIn(0.0, 1.0),
            confidence = json.optDouble("confidence", 0.5).coerceIn(0.0, 1.0),
            sourceConversationIds = strings(json.optJSONArray("source_conversation_ids")).take(20).toSet(),
            sourceEventIds = strings(json.optJSONArray("source_event_ids")).takeLast(30),
            checkpointIntervalMillis = json.optLong(
                "checkpoint_interval_millis",
                GlobalLongHorizonGoal.DEFAULT_CHECKPOINT_MILLIS
            ).coerceIn(MIN_CHECKPOINT_MILLIS, MAX_CHECKPOINT_MILLIS),
            nextCheckAtMillis = json.optLong("next_check_at_millis"),
            lastCheckAtMillis = json.optLong("last_check_at_millis"),
            lastProgressAtMillis = json.optLong("last_progress_at_millis"),
            checkpointCount = json.optInt("checkpoint_count").coerceAtLeast(0),
            activeCognitionTaskId = json.optString("active_cognition_task_id"),
            activeRunId = json.optString("active_run_id"),
            progressSummary = json.optString("progress_summary").take(2_000),
            blocker = json.optString("blocker").take(600),
            createdAtMillis = json.optLong("created_at_millis", System.currentTimeMillis()),
            updatedAtMillis = json.optLong("updated_at_millis", System.currentTimeMillis())
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

    private companion object {
        const val KEY_GOALS = "long_horizon_goals"
        const val MAX_GOALS = 200
        const val MIN_CHECKPOINT_MILLIS = 15L * 60L * 1_000L
        const val MAX_CHECKPOINT_MILLIS = 30L * 24L * 60L * 60L * 1_000L
        val STORE_LOCK = Any()
    }
}

data class GlobalLongHorizonCycleResult(
    val reconciledGoalCount: Int,
    val queuedCheckpointCount: Int,
    val nextWakeAtMillis: Long
)

class GlobalLongHorizonCoordinator(context: Context) {
    private val appContext = context.applicationContext
    private val repository = GlobalAgentRepository(appContext)
    private val deliberationStore = GlobalAgentDeliberationStore(appContext)
    private val goalStore = GlobalLongHorizonGoalStore(appContext)

    fun processDue(
        nowMillis: Long = System.currentTimeMillis(),
        maxGoals: Int = 4
    ): GlobalLongHorizonCycleResult {
        val settings = repository.settings()
        if (!settings.enabled || !settings.longHorizonPlanningEnabled) {
            return GlobalLongHorizonCycleResult(0, 0, 0L)
        }
        val before = goalStore.goals()
        val synchronized = GlobalLongHorizonGoalPolicy.mergeWorld(repository.loadWorld(), before, nowMillis)
        if (synchronized != before) goalStore.save(synchronized)
        val reconciled = reconcile(synchronized, nowMillis)
        if (reconciled != synchronized) goalStore.save(reconciled)
        val world = repository.loadWorld()
        val updatedWorld = GlobalLongHorizonGoalPolicy.applyGoalStatesToWorld(world, reconciled, nowMillis)
        if (updatedWorld != world) repository.saveWorld(updatedWorld)
        var goals = goalStore.goals()
        var queued = 0
        GlobalLongHorizonGoalPolicy.nextDue(goals, nowMillis)
            .take(maxGoals.coerceIn(1, 12))
            .forEach { goal ->
                val task = checkpointTask(goal, nowMillis)
                val duplicate = deliberationStore.cognitionTasks().any {
                    it.longHorizonGoalId == goal.id && it.status in setOf(
                        GlobalCognitionTaskStatus.QUEUED,
                        GlobalCognitionTaskStatus.RUNNING,
                        GlobalCognitionTaskStatus.WAITING_FOR_RESOURCE
                    )
                }
                if (duplicate) return@forEach
                deliberationStore.upsertCognitionTask(task)
                goals = goals.map {
                    if (it.id == goal.id) it.copy(
                        status = GlobalLongHorizonGoalStatus.IN_PROGRESS,
                        activeCognitionTaskId = task.id,
                        lastCheckAtMillis = nowMillis,
                        nextCheckAtMillis = nowMillis + goal.checkpointIntervalMillis,
                        updatedAtMillis = nowMillis
                    ) else it
                }
                queued += 1
            }
        if (queued > 0) goalStore.save(goals)
        return GlobalLongHorizonCycleResult(
            reconciledGoalCount = (synchronized.size - before.size).coerceAtLeast(0) +
                reconciled.zip(synchronized).count { (left, right) -> left != right },
            queuedCheckpointCount = queued,
            nextWakeAtMillis = nextWakeAt(goals, nowMillis)
        )
    }

    fun goals(): List<GlobalLongHorizonGoal> = goalStore.goals()

    fun pause(goalId: String): Boolean = goalStore.update(goalId) { goal ->
        goal.copy(
            status = GlobalLongHorizonGoalStatus.PAUSED,
            activeCognitionTaskId = "",
            activeRunId = "",
            nextCheckAtMillis = 0L,
            updatedAtMillis = System.currentTimeMillis()
        )
    } != null

    fun resume(goalId: String): Boolean = goalStore.update(goalId) { goal ->
        val now = System.currentTimeMillis()
        goal.copy(
            status = GlobalLongHorizonGoalStatus.ACTIVE,
            nextCheckAtMillis = now,
            blocker = "",
            updatedAtMillis = now
        )
    } != null

    private fun reconcile(
        goals: List<GlobalLongHorizonGoal>,
        nowMillis: Long
    ): List<GlobalLongHorizonGoal> {
        val cognitionById = deliberationStore.cognitionTasks().associateBy(GlobalCognitionTask::id)
        val runsById = deliberationStore.autonomousRuns().associateBy(GlobalAutonomousRun::id)
        return goals.map { goal ->
            if (goal.status in setOf(GlobalLongHorizonGoalStatus.COMPLETED, GlobalLongHorizonGoalStatus.PAUSED)) {
                return@map goal
            }
            val cognition = cognitionById[goal.activeCognitionTaskId]
            if (goal.activeCognitionTaskId.isNotBlank()) {
                when (cognition?.status) {
                    GlobalCognitionTaskStatus.QUEUED,
                    GlobalCognitionTaskStatus.RUNNING,
                    GlobalCognitionTaskStatus.WAITING_FOR_RESOURCE -> return@map goal.copy(
                        status = GlobalLongHorizonGoalStatus.IN_PROGRESS,
                        updatedAtMillis = maxOf(goal.updatedAtMillis, cognition.updatedAtMillis)
                    )
                    GlobalCognitionTaskStatus.FAILED, null -> return@map goal.copy(
                        status = GlobalLongHorizonGoalStatus.BLOCKED,
                        activeCognitionTaskId = "",
                        blocker = cognition?.lastError.orEmpty().ifBlank { "The checkpoint cognition task was lost" },
                        nextCheckAtMillis = nowMillis + RETRY_CHECKPOINT_MILLIS,
                        updatedAtMillis = nowMillis
                    )
                    GlobalCognitionTaskStatus.COMPLETED -> Unit
                }
            }
            val run = runsById[goal.activeRunId]
            if (goal.activeRunId.isBlank()) return@map goal
            when (run?.status) {
                GlobalAutonomousRunStatus.QUEUED,
                GlobalAutonomousRunStatus.RUNNING,
                GlobalAutonomousRunStatus.REPLANNING,
                GlobalAutonomousRunStatus.WAITING_FOR_RESOURCE -> goal.copy(
                    status = GlobalLongHorizonGoalStatus.IN_PROGRESS,
                    updatedAtMillis = maxOf(goal.updatedAtMillis, run.updatedAtMillis)
                )
                GlobalAutonomousRunStatus.WAITING_CONFIRMATION -> goal.copy(
                    status = GlobalLongHorizonGoalStatus.WAITING_CONFIRMATION,
                    updatedAtMillis = maxOf(goal.updatedAtMillis, run.updatedAtMillis)
                )
                GlobalAutonomousRunStatus.COMPLETED -> {
                    val completedGoal = run.review.decision.goalState == GlobalGoalProgressState.COMPLETED
                    goal.copy(
                        status = if (completedGoal) {
                            GlobalLongHorizonGoalStatus.COMPLETED
                        } else GlobalLongHorizonGoalStatus.ACTIVE,
                        activeRunId = "",
                        lastProgressAtMillis = run.updatedAtMillis,
                        progressSummary = run.outcomeSummary.ifBlank {
                            run.completedActions().lastOrNull()?.result.orEmpty().take(2_000)
                        },
                        nextCheckAtMillis = if (completedGoal) 0L else nowMillis + goal.checkpointIntervalMillis,
                        blocker = "",
                        updatedAtMillis = nowMillis
                    )
                }
                GlobalAutonomousRunStatus.PARTIAL,
                GlobalAutonomousRunStatus.FAILED,
                GlobalAutonomousRunStatus.PAUSED,
                null -> goal.copy(
                    status = GlobalLongHorizonGoalStatus.BLOCKED,
                    activeRunId = "",
                    lastProgressAtMillis = run?.updatedAtMillis ?: goal.lastProgressAtMillis,
                    progressSummary = run?.outcomeSummary.orEmpty().ifBlank { goal.progressSummary },
                    blocker = run?.lastError.orEmpty().ifBlank { "The autonomous run could not complete" },
                    nextCheckAtMillis = nowMillis + RETRY_CHECKPOINT_MILLIS,
                    updatedAtMillis = nowMillis
                )
            }
        }
    }

    private fun checkpointTask(goal: GlobalLongHorizonGoal, nowMillis: Long): GlobalCognitionTask {
        val content = buildString {
            append("Review progress toward this long-horizon goal: ").append(goal.title)
            if (goal.description.isNotBlank()) append("\nOriginal context: ").append(goal.description.take(1_500))
            if (goal.progressSummary.isNotBlank()) append("\nLast progress: ").append(goal.progressSummary.take(1_500))
            if (goal.blocker.isNotBlank()) append("\nCurrent blocker: ").append(goal.blocker.take(600))
        }
        val conversationId = goal.sourceConversationIds.firstOrNull().orEmpty().ifBlank { "global-goal:${goal.id}" }
        val event = GlobalConversationEvent(
            id = "long-horizon-checkpoint:${goal.id}:${goal.checkpointCount + 1}",
            type = GlobalConversationEventType.TASK_UPDATED,
            conversationId = conversationId,
            actor = GlobalConversationActor.TOOL,
            timestampMillis = nowMillis,
            content = content.take(12_000),
            contentRef = "encrypted://global-agent/goal/${goal.id}",
            conversationTitle = goal.topic,
            topicHints = setOf(goal.topic).filter(String::isNotBlank).toSet(),
            metadata = mapOf(
                "long_horizon_goal_id" to goal.id,
                "checkpoint_count" to (goal.checkpointCount + 1).toString(),
                "origin" to "global_long_horizon_scheduler"
            )
        )
        val baseline = GlobalUnderstanding(
            eventId = event.id,
            topic = goal.topic,
            intent = "long_horizon_checkpoint",
            goalCandidates = listOf(goal.title),
            taskCandidates = listOf("Review progress and revise the next safe actions"),
            complexity = 0.72,
            urgency = goal.priority,
            novelty = 0.35,
            uncertainty = 0.42,
            externalResearchUseful = true,
            durableFollowUpUseful = true
        )
        return GlobalCognitionTask(
            sourceEvent = event,
            baselineUnderstanding = baseline,
            longHorizonGoalId = goal.id,
            createdAtMillis = nowMillis,
            updatedAtMillis = nowMillis
        )
    }

    private fun nextWakeAt(goals: List<GlobalLongHorizonGoal>, nowMillis: Long): Long = goals.asSequence()
        .filter { it.status in setOf(GlobalLongHorizonGoalStatus.ACTIVE, GlobalLongHorizonGoalStatus.BLOCKED) }
        .map(GlobalLongHorizonGoal::nextCheckAtMillis)
        .filter { it > 0L }
        .minOrNull()
        ?.coerceAtLeast(nowMillis + 60_000L)
        ?: 0L

    private companion object {
        const val RETRY_CHECKPOINT_MILLIS = 60L * 60L * 1_000L
    }
}

object GlobalAgentWakeScheduler {
    const val ACTION_WAKE = "com.signalasi.chat.action.WAKE_GLOBAL_AGENT"
    private const val REQUEST_CODE = 0x5347
    private const val WINDOW_MILLIS = 5L * 60L * 1_000L

    fun schedule(context: Context, triggerAtMillis: Long) {
        val manager = context.applicationContext.getSystemService(AlarmManager::class.java)
        val intent = pendingIntent(context)
        manager.cancel(intent)
        if (triggerAtMillis <= 0L) return
        manager.setWindow(
            AlarmManager.RTC_WAKEUP,
            triggerAtMillis.coerceAtLeast(System.currentTimeMillis() + 60_000L),
            WINDOW_MILLIS,
            intent
        )
    }

    fun restore(context: Context) {
        GlobalSuperAgentRuntime.get(context).scheduleNextWake()
    }

    private fun pendingIntent(context: Context): PendingIntent = PendingIntent.getBroadcast(
        context.applicationContext,
        REQUEST_CODE,
        Intent(context.applicationContext, GlobalAgentWakeReceiver::class.java).setAction(ACTION_WAKE),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )
}

class GlobalAgentWakeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != GlobalAgentWakeScheduler.ACTION_WAKE) return
        GlobalConversationEventBus.requestProcessing(context)
    }
}
