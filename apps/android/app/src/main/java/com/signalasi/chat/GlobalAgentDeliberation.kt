package com.signalasi.chat

import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import java.util.UUID

enum class GlobalCognitionTaskStatus {
    QUEUED,
    RUNNING,
    WAITING_FOR_RESOURCE,
    COMPLETED,
    FAILED
}

data class GlobalCognitionTask(
    val id: String = UUID.randomUUID().toString(),
    val sourceEvent: GlobalConversationEvent,
    val baselineUnderstanding: GlobalUnderstanding,
    val status: GlobalCognitionTaskStatus = GlobalCognitionTaskStatus.QUEUED,
    val resourceId: String = "",
    val attemptedResourceIds: List<String> = emptyList(),
    val sourceMessageId: Long = 0L,
    val attemptCount: Int = 0,
    val nextAttemptAtMillis: Long = 0L,
    val leaseExpiresAtMillis: Long = 0L,
    val lastError: String = "",
    val result: GlobalModelUnderstanding = GlobalModelUnderstanding(),
    val createdAtMillis: Long = System.currentTimeMillis(),
    val updatedAtMillis: Long = System.currentTimeMillis()
)

data class GlobalModelUnderstanding(
    val topic: String = "",
    val intent: String = "",
    val entities: Set<String> = emptySet(),
    val goals: List<String> = emptyList(),
    val tasks: List<String> = emptyList(),
    val decisions: List<String> = emptyList(),
    val preferences: List<String> = emptyList(),
    val risks: List<String> = emptyList(),
    val opportunities: List<String> = emptyList(),
    val researchQuestions: List<String> = emptyList(),
    val actions: List<GlobalAutonomousAction> = emptyList(),
    val userInsight: String = "",
    val confidence: Double = 0.0
) {
    val meaningful: Boolean
        get() = topic.isNotBlank() || goals.isNotEmpty() || tasks.isNotEmpty() ||
            decisions.isNotEmpty() || preferences.isNotEmpty() || risks.isNotEmpty() ||
            opportunities.isNotEmpty() || researchQuestions.isNotEmpty() ||
            actions.isNotEmpty() || userInsight.isNotBlank()
}

enum class GlobalAutonomousActionKind {
    ANALYZE,
    DRAFT,
    READ_ONLY_CHECK,
    CREATE_TOPIC,
    START_RESEARCH,
    START_MONITOR
}

enum class GlobalAutonomousActionStatus {
    PENDING,
    RUNNING,
    WAITING_CONFIRMATION,
    COMPLETED,
    FAILED,
    SKIPPED
}

data class GlobalAutonomousAction(
    val id: String = UUID.randomUUID().toString(),
    val kind: GlobalAutonomousActionKind,
    val goal: String,
    val rationale: String = "",
    val expectedResult: String = "",
    val targetTopic: String = "",
    val priority: Double = 0.5,
    val externalEffect: Boolean = false,
    val reversible: Boolean = true,
    val confirmationGranted: Boolean = false,
    val status: GlobalAutonomousActionStatus = GlobalAutonomousActionStatus.PENDING,
    val resourceId: String = "",
    val attemptedResourceIds: List<String> = emptyList(),
    val sourceMessageId: Long = 0L,
    val attemptCount: Int = 0,
    val leaseExpiresAtMillis: Long = 0L,
    val result: String = "",
    val lastError: String = "",
    val startedAtMillis: Long = 0L,
    val completedAtMillis: Long = 0L
) {
    val requiresConfirmation: Boolean get() = (externalEffect || !reversible) && !confirmationGranted
}

enum class GlobalAutonomousRunStatus {
    QUEUED,
    RUNNING,
    WAITING_FOR_RESOURCE,
    WAITING_CONFIRMATION,
    COMPLETED,
    PARTIAL,
    FAILED,
    PAUSED
}

data class GlobalAutonomousRun(
    val id: String = UUID.randomUUID().toString(),
    val sourceCognitionTaskId: String,
    val sourceEventId: String,
    val sourceConversationId: String,
    val topic: String,
    val goal: String,
    val actions: List<GlobalAutonomousAction>,
    val status: GlobalAutonomousRunStatus = GlobalAutonomousRunStatus.QUEUED,
    val attemptCount: Int = 0,
    val nextAttemptAtMillis: Long = 0L,
    val leaseExpiresAtMillis: Long = 0L,
    val lastError: String = "",
    val createdAtMillis: Long = System.currentTimeMillis(),
    val updatedAtMillis: Long = System.currentTimeMillis()
) {
    fun activeAction(): GlobalAutonomousAction? = actions.firstOrNull {
        it.status in setOf(GlobalAutonomousActionStatus.PENDING, GlobalAutonomousActionStatus.RUNNING)
    }

    fun completedActions(): List<GlobalAutonomousAction> = actions.filter {
        it.status == GlobalAutonomousActionStatus.COMPLETED
    }
}

object GlobalCognitionTaskPolicy {
    fun shouldDeliberate(
        event: GlobalConversationEvent,
        understanding: GlobalUnderstanding,
        reduction: GlobalWorldReduction
    ): Boolean {
        if (event.sensitivity == GlobalConversationSensitivity.SESSION_PRIVATE) return false
        if (event.actor !in setOf(GlobalConversationActor.USER, GlobalConversationActor.TOOL)) return false
        if (event.type == GlobalConversationEventType.TOOL_RESULT &&
            (event.metadata.containsKey("research_task_id") || event.metadata.containsKey("autonomous_run_id"))
        ) return false
        if (event.content.trim().length < 8) return false
        return reduction.conflicts.isNotEmpty() ||
            understanding.crossConversationIds.isNotEmpty() ||
            understanding.durableFollowUpUseful ||
            understanding.externalResearchUseful ||
            understanding.riskCandidates.isNotEmpty() ||
            understanding.opportunityCandidates.isNotEmpty() ||
            understanding.taskCandidates.size >= 2 ||
            understanding.complexity >= 0.34
    }

    fun recoverIfStale(task: GlobalCognitionTask, nowMillis: Long): GlobalCognitionTask {
        if (task.status != GlobalCognitionTaskStatus.RUNNING ||
            task.leaseExpiresAtMillis <= 0L || task.leaseExpiresAtMillis > nowMillis
        ) return task
        return task.copy(
            status = GlobalCognitionTaskStatus.WAITING_FOR_RESOURCE,
            attemptedResourceIds = (task.attemptedResourceIds + task.resourceId)
                .filter(String::isNotBlank)
                .distinct(),
            sourceMessageId = 0L,
            nextAttemptAtMillis = nowMillis,
            leaseExpiresAtMillis = 0L,
            lastError = "The previous cognition lease expired before a result arrived",
            updatedAtMillis = nowMillis
        )
    }

    fun retryDelayMillis(attemptCount: Int): Long = when (attemptCount.coerceAtLeast(1)) {
        1 -> 15_000L
        2 -> 60_000L
        else -> 5L * 60L * 1_000L
    }

    const val LEASE_MILLIS = 4L * 60L * 1_000L
    const val MAX_ATTEMPTS = 3
}

object GlobalCognitionMerger {
    fun merge(task: GlobalCognitionTask, result: GlobalModelUnderstanding): GlobalUnderstanding {
        val baseline = task.baselineUnderstanding
        fun mergeValues(first: List<String>, second: List<String>, limit: Int): List<String> =
            (first + second).map { it.replace(Regex("\\s+"), " ").trim() }
                .filter(String::isNotBlank)
                .distinctBy(GlobalAgentText::normalize)
                .take(limit)
        return baseline.copy(
            topic = result.topic.ifBlank { baseline.topic }.take(120),
            intent = result.intent.ifBlank { baseline.intent }.take(80),
            entities = (baseline.entities + result.entities).take(32).toSet(),
            goalCandidates = mergeValues(baseline.goalCandidates, result.goals, 8),
            taskCandidates = mergeValues(baseline.taskCandidates, result.tasks, 10),
            decisionCandidates = mergeValues(baseline.decisionCandidates, result.decisions, 8),
            preferenceCandidates = mergeValues(baseline.preferenceCandidates, result.preferences, 6),
            riskCandidates = mergeValues(baseline.riskCandidates, result.risks, 8),
            opportunityCandidates = mergeValues(baseline.opportunityCandidates, result.opportunities, 8),
            complexity = maxOf(baseline.complexity, if (result.actions.size >= 3) 0.72 else 0.48),
            novelty = maxOf(baseline.novelty, result.confidence * 0.72),
            uncertainty = minOf(baseline.uncertainty, (1.0 - result.confidence).coerceIn(0.08, 0.72)),
            externalResearchUseful = baseline.externalResearchUseful || result.researchQuestions.isNotEmpty(),
            durableFollowUpUseful = baseline.durableFollowUpUseful ||
                result.actions.any { it.kind in setOf(GlobalAutonomousActionKind.CREATE_TOPIC, GlobalAutonomousActionKind.START_MONITOR) }
        )
    }
}

object GlobalModelUnderstandingParser {
    fun parse(raw: String): GlobalModelUnderstanding? {
        val json = extractJson(raw) ?: return null
        val actions = buildList {
            val array = json.optJSONArray("actions") ?: JSONArray()
            for (index in 0 until minOf(array.length(), MAX_ACTIONS)) {
                val item = array.optJSONObject(index) ?: continue
                val kind = actionKind(item.optString("kind")) ?: continue
                val goal = clean(item.optString("goal"), 1_000)
                if (goal.isBlank()) continue
                add(GlobalAutonomousAction(
                    kind = kind,
                    goal = goal,
                    rationale = clean(item.optString("rationale"), 600),
                    expectedResult = clean(item.optString("expected_result"), 600),
                    targetTopic = clean(item.optString("target_topic"), 160),
                    priority = item.optDouble("priority", 0.5).coerceIn(0.0, 1.0),
                    externalEffect = item.optBoolean("external_effect", false),
                    reversible = item.optBoolean("reversible", true)
                ))
            }
        }
        return GlobalModelUnderstanding(
            topic = clean(json.optString("topic"), 120),
            intent = clean(json.optString("intent"), 80),
            entities = strings(json.optJSONArray("entities"), 32, 120).toSet(),
            goals = strings(json.optJSONArray("goals"), 8, 1_000),
            tasks = strings(json.optJSONArray("tasks"), 10, 1_000),
            decisions = strings(json.optJSONArray("decisions"), 8, 1_000),
            preferences = strings(json.optJSONArray("preferences"), 6, 1_000),
            risks = strings(json.optJSONArray("risks"), 8, 1_000),
            opportunities = strings(json.optJSONArray("opportunities"), 8, 1_000),
            researchQuestions = strings(json.optJSONArray("research_questions"), 6, 1_000),
            actions = actions.distinctBy { GlobalAgentText.stableKey(it.kind.name, it.goal) },
            userInsight = clean(json.optString("user_insight"), 2_000),
            confidence = json.optDouble("confidence", 0.5).coerceIn(0.0, 1.0)
        ).takeIf(GlobalModelUnderstanding::meaningful)
    }

    private fun actionKind(value: String): GlobalAutonomousActionKind? {
        val normalized = value.trim().uppercase(Locale.ROOT).replace('-', '_').replace(' ', '_')
        return GlobalAutonomousActionKind.entries.firstOrNull { it.name == normalized }
    }

    private fun extractJson(raw: String): JSONObject? {
        val clean = raw.trim()
            .removePrefix("```json").removePrefix("```")
            .removeSuffix("```").trim()
        val start = clean.indexOf('{')
        val end = clean.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        return runCatching { JSONObject(clean.substring(start, end + 1)) }.getOrNull()
    }

    private fun strings(array: JSONArray?, limit: Int, maxCharacters: Int): List<String> {
        if (array == null) return emptyList()
        return buildList {
            for (index in 0 until minOf(array.length(), limit * 2)) {
                val value = clean(array.optString(index), maxCharacters)
                if (value.isNotBlank()) add(value)
                if (size >= limit) break
            }
        }.distinctBy(GlobalAgentText::normalize)
    }

    private fun clean(value: String, maxCharacters: Int): String =
        value.replace(Regex("\\s+"), " ").trim().take(maxCharacters)

    private const val MAX_ACTIONS = 6
}

object GlobalAutonomousRunPlanner {
    fun plan(task: GlobalCognitionTask): GlobalAutonomousRun? {
        val result = task.result
        val actions = result.actions
            .sortedByDescending(GlobalAutonomousAction::priority)
            .distinctBy { GlobalAgentText.stableKey(it.kind.name, it.goal) }
            .take(MAX_ACTIONS)
            .map { action ->
                if (action.requiresConfirmation) {
                    action.copy(status = GlobalAutonomousActionStatus.WAITING_CONFIRMATION)
                } else action
            }
        if (actions.isEmpty()) return null
        val status = if (actions.all { it.status == GlobalAutonomousActionStatus.WAITING_CONFIRMATION }) {
            GlobalAutonomousRunStatus.WAITING_CONFIRMATION
        } else GlobalAutonomousRunStatus.QUEUED
        return GlobalAutonomousRun(
            sourceCognitionTaskId = task.id,
            sourceEventId = task.sourceEvent.id,
            sourceConversationId = task.sourceEvent.conversationId,
            topic = result.topic.ifBlank { task.baselineUnderstanding.topic },
            goal = task.sourceEvent.content.take(2_000),
            actions = actions,
            status = status,
            createdAtMillis = task.updatedAtMillis,
            updatedAtMillis = task.updatedAtMillis
        )
    }

    private const val MAX_ACTIONS = 6
}

object GlobalAutonomousRunPolicy {
    fun recoverIfStale(run: GlobalAutonomousRun, nowMillis: Long): GlobalAutonomousRun {
        if (run.status != GlobalAutonomousRunStatus.RUNNING ||
            run.leaseExpiresAtMillis <= 0L || run.leaseExpiresAtMillis > nowMillis
        ) return run
        val actions = run.actions.map { action ->
            if (action.status == GlobalAutonomousActionStatus.RUNNING &&
                action.leaseExpiresAtMillis in 1..nowMillis
            ) {
                action.copy(
                    status = GlobalAutonomousActionStatus.PENDING,
                    attemptedResourceIds = (action.attemptedResourceIds + action.resourceId)
                        .filter(String::isNotBlank).distinct(),
                    sourceMessageId = 0L,
                    leaseExpiresAtMillis = 0L,
                    lastError = "The delegated action lease expired before a result arrived"
                )
            } else action
        }
        return run.copy(
            actions = actions,
            status = if (actions.any { it.status == GlobalAutonomousActionStatus.PENDING }) {
                GlobalAutonomousRunStatus.WAITING_FOR_RESOURCE
            } else GlobalAutonomousRunStatus.WAITING_CONFIRMATION,
            nextAttemptAtMillis = nowMillis,
            leaseExpiresAtMillis = 0L,
            updatedAtMillis = nowMillis
        )
    }

    fun terminalStatus(actions: List<GlobalAutonomousAction>): GlobalAutonomousRunStatus? {
        if (actions.any { it.status in setOf(GlobalAutonomousActionStatus.PENDING, GlobalAutonomousActionStatus.RUNNING) }) {
            return null
        }
        val completed = actions.count { it.status == GlobalAutonomousActionStatus.COMPLETED }
        val failed = actions.count { it.status == GlobalAutonomousActionStatus.FAILED }
        val waiting = actions.any { it.status == GlobalAutonomousActionStatus.WAITING_CONFIRMATION }
        return when {
            waiting -> GlobalAutonomousRunStatus.WAITING_CONFIRMATION
            completed > 0 && failed > 0 -> GlobalAutonomousRunStatus.PARTIAL
            completed > 0 -> GlobalAutonomousRunStatus.COMPLETED
            else -> GlobalAutonomousRunStatus.FAILED
        }
    }

    fun retryDelayMillis(attemptCount: Int): Long = GlobalCognitionTaskPolicy.retryDelayMillis(attemptCount)

    const val LEASE_MILLIS = 8L * 60L * 1_000L
    const val MAX_ACTION_ATTEMPTS = 3
}

class GlobalAgentDeliberationStore(context: android.content.Context) {
    private val database = AgentEncryptedDatabase(
        context.applicationContext,
        GlobalAgentRepository.DATABASE_NAME
    )

    fun cognitionTasks(): List<GlobalCognitionTask> = synchronized(STORE_LOCK) { loadCognitionTasks() }

    fun saveCognitionTasks(tasks: List<GlobalCognitionTask>) = synchronized(STORE_LOCK) {
        val array = JSONArray()
        tasks.sortedBy(GlobalCognitionTask::createdAtMillis).takeLast(MAX_COGNITION_TASKS)
            .forEach { array.put(encodeCognitionTask(it)) }
        database.writeString(KEY_COGNITION_TASKS, array.toString())
    }

    fun upsertCognitionTask(task: GlobalCognitionTask) = synchronized(STORE_LOCK) {
        saveCognitionTasks(loadCognitionTasks().filterNot { it.id == task.id } + task)
    }

    fun updateCognitionTask(
        taskId: String,
        transform: (GlobalCognitionTask) -> GlobalCognitionTask
    ): GlobalCognitionTask? = synchronized(STORE_LOCK) {
        val tasks = loadCognitionTasks().toMutableList()
        val index = tasks.indexOfFirst { it.id == taskId }
        if (index < 0) return@synchronized null
        val updated = transform(tasks[index])
        tasks[index] = updated
        saveCognitionTasks(tasks)
        updated
    }

    fun claimCognitionTask(nowMillis: Long = System.currentTimeMillis()): GlobalCognitionTask? = synchronized(STORE_LOCK) {
        val tasks = loadCognitionTasks().map { GlobalCognitionTaskPolicy.recoverIfStale(it, nowMillis) }.toMutableList()
        val index = tasks.indexOfFirst {
            it.status in setOf(GlobalCognitionTaskStatus.QUEUED, GlobalCognitionTaskStatus.WAITING_FOR_RESOURCE) &&
                it.nextAttemptAtMillis <= nowMillis
        }
        if (index < 0) {
            saveCognitionTasks(tasks)
            return@synchronized null
        }
        val claimed = tasks[index].copy(
            status = GlobalCognitionTaskStatus.RUNNING,
            attemptCount = tasks[index].attemptCount + 1,
            sourceMessageId = 0L,
            leaseExpiresAtMillis = nowMillis + GlobalCognitionTaskPolicy.LEASE_MILLIS,
            updatedAtMillis = nowMillis
        )
        tasks[index] = claimed
        saveCognitionTasks(tasks)
        claimed
    }

    fun autonomousRuns(): List<GlobalAutonomousRun> = synchronized(STORE_LOCK) { loadAutonomousRuns() }

    fun saveAutonomousRuns(runs: List<GlobalAutonomousRun>) = synchronized(STORE_LOCK) {
        val array = JSONArray()
        runs.sortedBy(GlobalAutonomousRun::createdAtMillis).takeLast(MAX_AUTONOMOUS_RUNS)
            .forEach { array.put(encodeAutonomousRun(it)) }
        database.writeString(KEY_AUTONOMOUS_RUNS, array.toString())
    }

    fun upsertAutonomousRun(run: GlobalAutonomousRun) = synchronized(STORE_LOCK) {
        saveAutonomousRuns(loadAutonomousRuns().filterNot { it.id == run.id } + run)
    }

    fun updateAutonomousRun(
        runId: String,
        transform: (GlobalAutonomousRun) -> GlobalAutonomousRun
    ): GlobalAutonomousRun? = synchronized(STORE_LOCK) {
        val runs = loadAutonomousRuns().toMutableList()
        val index = runs.indexOfFirst { it.id == runId }
        if (index < 0) return@synchronized null
        val updated = transform(runs[index])
        runs[index] = updated
        saveAutonomousRuns(runs)
        updated
    }

    fun claimAutonomousRun(nowMillis: Long = System.currentTimeMillis()): GlobalAutonomousRun? = synchronized(STORE_LOCK) {
        val runs = loadAutonomousRuns().map { GlobalAutonomousRunPolicy.recoverIfStale(it, nowMillis) }.toMutableList()
        val index = runs.indexOfFirst {
            it.status in setOf(
                GlobalAutonomousRunStatus.QUEUED,
                GlobalAutonomousRunStatus.WAITING_FOR_RESOURCE,
                GlobalAutonomousRunStatus.RUNNING
            ) && it.nextAttemptAtMillis <= nowMillis && it.actions.any { action ->
                action.status == GlobalAutonomousActionStatus.PENDING
            }
        }
        if (index < 0) {
            saveAutonomousRuns(runs)
            return@synchronized null
        }
        val claimed = runs[index].copy(
            status = GlobalAutonomousRunStatus.RUNNING,
            attemptCount = runs[index].attemptCount + 1,
            leaseExpiresAtMillis = nowMillis + GlobalAutonomousRunPolicy.LEASE_MILLIS,
            updatedAtMillis = nowMillis
        )
        runs[index] = claimed
        saveAutonomousRuns(runs)
        claimed
    }

    fun exportCognitionTasks(): JSONArray = synchronized(STORE_LOCK) {
        JSONArray().apply { loadCognitionTasks().forEach { put(encodeCognitionTask(it)) } }
    }

    fun exportAutonomousRuns(): JSONArray = synchronized(STORE_LOCK) {
        JSONArray().apply { loadAutonomousRuns().forEach { put(encodeAutonomousRun(it)) } }
    }

    fun restoreCognitionTasks(array: JSONArray) = synchronized(STORE_LOCK) {
        saveCognitionTasks(buildList {
            for (index in 0 until array.length()) decodeCognitionTask(array.optJSONObject(index))?.let(::add)
        })
    }

    fun restoreAutonomousRuns(array: JSONArray) = synchronized(STORE_LOCK) {
        saveAutonomousRuns(buildList {
            for (index in 0 until array.length()) decodeAutonomousRun(array.optJSONObject(index))?.let(::add)
        })
    }

    private fun loadCognitionTasks(): List<GlobalCognitionTask> = runCatching {
        val array = JSONArray(database.readString(KEY_COGNITION_TASKS, "[]"))
        buildList {
            for (index in 0 until array.length()) decodeCognitionTask(array.optJSONObject(index))?.let(::add)
        }
    }.getOrDefault(emptyList())

    private fun loadAutonomousRuns(): List<GlobalAutonomousRun> = runCatching {
        val array = JSONArray(database.readString(KEY_AUTONOMOUS_RUNS, "[]"))
        buildList {
            for (index in 0 until array.length()) decodeAutonomousRun(array.optJSONObject(index))?.let(::add)
        }
    }.getOrDefault(emptyList())

    private fun encodeCognitionTask(task: GlobalCognitionTask): JSONObject = JSONObject()
        .put("id", task.id)
        .put("source_event", encodeEvent(task.sourceEvent))
        .put("baseline", encodeUnderstanding(task.baselineUnderstanding))
        .put("status", task.status.name)
        .put("resource_id", task.resourceId)
        .put("attempted_resource_ids", JSONArray(task.attemptedResourceIds))
        .put("source_message_id", task.sourceMessageId)
        .put("attempt_count", task.attemptCount)
        .put("next_attempt_at_millis", task.nextAttemptAtMillis)
        .put("lease_expires_at_millis", task.leaseExpiresAtMillis)
        .put("last_error", task.lastError.take(600))
        .put("result", encodeModelUnderstanding(task.result))
        .put("created_at_millis", task.createdAtMillis)
        .put("updated_at_millis", task.updatedAtMillis)

    private fun decodeCognitionTask(json: JSONObject?): GlobalCognitionTask? {
        if (json == null) return null
        val sourceEvent = decodeEvent(json.optJSONObject("source_event")) ?: return null
        val baseline = decodeUnderstanding(json.optJSONObject("baseline")) ?: return null
        return GlobalCognitionTask(
            id = json.optString("id").ifBlank { UUID.randomUUID().toString() },
            sourceEvent = sourceEvent,
            baselineUnderstanding = baseline,
            status = enumValue(json.optString("status"), GlobalCognitionTaskStatus.QUEUED),
            resourceId = json.optString("resource_id"),
            attemptedResourceIds = strings(json.optJSONArray("attempted_resource_ids")),
            sourceMessageId = json.optLong("source_message_id"),
            attemptCount = json.optInt("attempt_count").coerceAtLeast(0),
            nextAttemptAtMillis = json.optLong("next_attempt_at_millis"),
            leaseExpiresAtMillis = json.optLong("lease_expires_at_millis"),
            lastError = json.optString("last_error").take(600),
            result = decodeModelUnderstanding(json.optJSONObject("result")),
            createdAtMillis = json.optLong("created_at_millis", sourceEvent.timestampMillis),
            updatedAtMillis = json.optLong("updated_at_millis", sourceEvent.timestampMillis)
        )
    }

    private fun encodeAutonomousRun(run: GlobalAutonomousRun): JSONObject = JSONObject()
        .put("id", run.id)
        .put("source_cognition_task_id", run.sourceCognitionTaskId)
        .put("source_event_id", run.sourceEventId)
        .put("source_conversation_id", run.sourceConversationId)
        .put("topic", run.topic)
        .put("goal", run.goal.take(2_000))
        .put("actions", JSONArray().apply { run.actions.forEach { put(encodeAction(it)) } })
        .put("status", run.status.name)
        .put("attempt_count", run.attemptCount)
        .put("next_attempt_at_millis", run.nextAttemptAtMillis)
        .put("lease_expires_at_millis", run.leaseExpiresAtMillis)
        .put("last_error", run.lastError.take(600))
        .put("created_at_millis", run.createdAtMillis)
        .put("updated_at_millis", run.updatedAtMillis)

    private fun decodeAutonomousRun(json: JSONObject?): GlobalAutonomousRun? {
        if (json == null) return null
        val id = json.optString("id")
        val sourceEventId = json.optString("source_event_id")
        if (id.isBlank() || sourceEventId.isBlank()) return null
        return GlobalAutonomousRun(
            id = id,
            sourceCognitionTaskId = json.optString("source_cognition_task_id"),
            sourceEventId = sourceEventId,
            sourceConversationId = json.optString("source_conversation_id"),
            topic = json.optString("topic").take(160),
            goal = json.optString("goal").take(2_000),
            actions = buildList {
                val array = json.optJSONArray("actions") ?: JSONArray()
                for (index in 0 until array.length()) decodeAction(array.optJSONObject(index))?.let(::add)
            }.take(8),
            status = enumValue(json.optString("status"), GlobalAutonomousRunStatus.QUEUED),
            attemptCount = json.optInt("attempt_count").coerceAtLeast(0),
            nextAttemptAtMillis = json.optLong("next_attempt_at_millis"),
            leaseExpiresAtMillis = json.optLong("lease_expires_at_millis"),
            lastError = json.optString("last_error").take(600),
            createdAtMillis = json.optLong("created_at_millis", System.currentTimeMillis()),
            updatedAtMillis = json.optLong("updated_at_millis", System.currentTimeMillis())
        )
    }

    private fun encodeModelUnderstanding(result: GlobalModelUnderstanding): JSONObject = JSONObject()
        .put("topic", result.topic)
        .put("intent", result.intent)
        .put("entities", JSONArray(result.entities.toList()))
        .put("goals", JSONArray(result.goals))
        .put("tasks", JSONArray(result.tasks))
        .put("decisions", JSONArray(result.decisions))
        .put("preferences", JSONArray(result.preferences))
        .put("risks", JSONArray(result.risks))
        .put("opportunities", JSONArray(result.opportunities))
        .put("research_questions", JSONArray(result.researchQuestions))
        .put("actions", JSONArray().apply { result.actions.forEach { put(encodeAction(it)) } })
        .put("user_insight", result.userInsight)
        .put("confidence", result.confidence)

    private fun decodeModelUnderstanding(json: JSONObject?): GlobalModelUnderstanding {
        if (json == null) return GlobalModelUnderstanding()
        return GlobalModelUnderstanding(
            topic = json.optString("topic").take(120),
            intent = json.optString("intent").take(80),
            entities = strings(json.optJSONArray("entities")).take(32).toSet(),
            goals = strings(json.optJSONArray("goals")).take(8),
            tasks = strings(json.optJSONArray("tasks")).take(10),
            decisions = strings(json.optJSONArray("decisions")).take(8),
            preferences = strings(json.optJSONArray("preferences")).take(6),
            risks = strings(json.optJSONArray("risks")).take(8),
            opportunities = strings(json.optJSONArray("opportunities")).take(8),
            researchQuestions = strings(json.optJSONArray("research_questions")).take(6),
            actions = buildList {
                val array = json.optJSONArray("actions") ?: JSONArray()
                for (index in 0 until array.length()) decodeAction(array.optJSONObject(index))?.let(::add)
            }.take(6),
            userInsight = json.optString("user_insight").take(2_000),
            confidence = json.optDouble("confidence", 0.0).coerceIn(0.0, 1.0)
        )
    }

    private fun encodeAction(action: GlobalAutonomousAction): JSONObject = JSONObject()
        .put("id", action.id)
        .put("kind", action.kind.name)
        .put("goal", action.goal)
        .put("rationale", action.rationale)
        .put("expected_result", action.expectedResult)
        .put("target_topic", action.targetTopic)
        .put("priority", action.priority)
        .put("external_effect", action.externalEffect)
        .put("reversible", action.reversible)
        .put("confirmation_granted", action.confirmationGranted)
        .put("status", action.status.name)
        .put("resource_id", action.resourceId)
        .put("attempted_resource_ids", JSONArray(action.attemptedResourceIds))
        .put("source_message_id", action.sourceMessageId)
        .put("attempt_count", action.attemptCount)
        .put("lease_expires_at_millis", action.leaseExpiresAtMillis)
        .put("result", action.result.take(12_000))
        .put("last_error", action.lastError.take(600))
        .put("started_at_millis", action.startedAtMillis)
        .put("completed_at_millis", action.completedAtMillis)

    private fun decodeAction(json: JSONObject?): GlobalAutonomousAction? {
        if (json == null) return null
        val goal = json.optString("goal").take(1_000)
        if (goal.isBlank()) return null
        return GlobalAutonomousAction(
            id = json.optString("id").ifBlank { UUID.randomUUID().toString() },
            kind = enumValue(json.optString("kind"), GlobalAutonomousActionKind.ANALYZE),
            goal = goal,
            rationale = json.optString("rationale").take(600),
            expectedResult = json.optString("expected_result").take(600),
            targetTopic = json.optString("target_topic").take(160),
            priority = json.optDouble("priority", 0.5).coerceIn(0.0, 1.0),
            externalEffect = json.optBoolean("external_effect", false),
            reversible = json.optBoolean("reversible", true),
            confirmationGranted = json.optBoolean("confirmation_granted", false),
            status = enumValue(json.optString("status"), GlobalAutonomousActionStatus.PENDING),
            resourceId = json.optString("resource_id"),
            attemptedResourceIds = strings(json.optJSONArray("attempted_resource_ids")),
            sourceMessageId = json.optLong("source_message_id"),
            attemptCount = json.optInt("attempt_count").coerceAtLeast(0),
            leaseExpiresAtMillis = json.optLong("lease_expires_at_millis"),
            result = json.optString("result").take(12_000),
            lastError = json.optString("last_error").take(600),
            startedAtMillis = json.optLong("started_at_millis"),
            completedAtMillis = json.optLong("completed_at_millis")
        )
    }

    private fun encodeEvent(event: GlobalConversationEvent): JSONObject = JSONObject()
        .put("id", event.id)
        .put("type", event.type.name)
        .put("conversation_id", event.conversationId)
        .put("message_id", event.messageId)
        .put("actor", event.actor.name)
        .put("timestamp_millis", event.timestampMillis)
        .put("content", event.content.take(12_000))
        .put("content_ref", event.contentRef)
        .put("conversation_title", event.conversationTitle)
        .put("topic_hints", JSONArray(event.topicHints.toList()))
        .put("sensitivity", event.sensitivity.name)
        .put("metadata", JSONObject(event.metadata))

    private fun decodeEvent(json: JSONObject?): GlobalConversationEvent? {
        if (json == null) return null
        val id = json.optString("id")
        val conversationId = json.optString("conversation_id")
        if (id.isBlank() || conversationId.isBlank()) return null
        return GlobalConversationEvent(
            id = id,
            type = enumValue(json.optString("type"), GlobalConversationEventType.MESSAGE_CREATED),
            conversationId = conversationId,
            messageId = json.optString("message_id"),
            actor = enumValue(json.optString("actor"), GlobalConversationActor.SYSTEM),
            timestampMillis = json.optLong("timestamp_millis", System.currentTimeMillis()),
            content = json.optString("content").take(12_000),
            contentRef = json.optString("content_ref"),
            conversationTitle = json.optString("conversation_title").take(160),
            topicHints = strings(json.optJSONArray("topic_hints")).toSet(),
            sensitivity = enumValue(json.optString("sensitivity"), GlobalConversationSensitivity.PERSONAL),
            metadata = stringMap(json.optJSONObject("metadata"))
        )
    }

    private fun encodeUnderstanding(value: GlobalUnderstanding): JSONObject = JSONObject()
        .put("event_id", value.eventId)
        .put("topic", value.topic)
        .put("intent", value.intent)
        .put("entities", JSONArray(value.entities.toList()))
        .put("goals", JSONArray(value.goalCandidates))
        .put("tasks", JSONArray(value.taskCandidates))
        .put("decisions", JSONArray(value.decisionCandidates))
        .put("preferences", JSONArray(value.preferenceCandidates))
        .put("risks", JSONArray(value.riskCandidates))
        .put("opportunities", JSONArray(value.opportunityCandidates))
        .put("cross_conversation_ids", JSONArray(value.crossConversationIds.toList()))
        .put("complexity", value.complexity)
        .put("urgency", value.urgency)
        .put("novelty", value.novelty)
        .put("uncertainty", value.uncertainty)
        .put("external_research_useful", value.externalResearchUseful)
        .put("durable_follow_up_useful", value.durableFollowUpUseful)

    private fun decodeUnderstanding(json: JSONObject?): GlobalUnderstanding? {
        if (json == null) return null
        val eventId = json.optString("event_id")
        if (eventId.isBlank()) return null
        return GlobalUnderstanding(
            eventId = eventId,
            topic = json.optString("topic").take(120),
            intent = json.optString("intent").take(80),
            entities = strings(json.optJSONArray("entities")).take(32).toSet(),
            goalCandidates = strings(json.optJSONArray("goals")).take(8),
            taskCandidates = strings(json.optJSONArray("tasks")).take(10),
            decisionCandidates = strings(json.optJSONArray("decisions")).take(8),
            preferenceCandidates = strings(json.optJSONArray("preferences")).take(6),
            riskCandidates = strings(json.optJSONArray("risks")).take(8),
            opportunityCandidates = strings(json.optJSONArray("opportunities")).take(8),
            crossConversationIds = strings(json.optJSONArray("cross_conversation_ids")).take(20).toSet(),
            complexity = json.optDouble("complexity").coerceIn(0.0, 1.0),
            urgency = json.optDouble("urgency").coerceIn(0.0, 1.0),
            novelty = json.optDouble("novelty", 0.5).coerceIn(0.0, 1.0),
            uncertainty = json.optDouble("uncertainty").coerceIn(0.0, 1.0),
            externalResearchUseful = json.optBoolean("external_research_useful"),
            durableFollowUpUseful = json.optBoolean("durable_follow_up_useful")
        )
    }

    private fun strings(array: JSONArray?): List<String> {
        if (array == null) return emptyList()
        return buildList {
            for (index in 0 until array.length()) array.optString(index).takeIf(String::isNotBlank)?.let(::add)
        }
    }

    private fun stringMap(json: JSONObject?): Map<String, String> {
        if (json == null) return emptyMap()
        return buildMap { json.keys().forEach { key -> put(key, json.optString(key)) } }
    }

    private inline fun <reified T : Enum<T>> enumValue(value: String, fallback: T): T =
        enumValues<T>().firstOrNull { it.name == value } ?: fallback

    private companion object {
        const val KEY_COGNITION_TASKS = "cognition_tasks"
        const val KEY_AUTONOMOUS_RUNS = "autonomous_runs"
        const val MAX_COGNITION_TASKS = 300
        const val MAX_AUTONOMOUS_RUNS = 200
        val STORE_LOCK = Any()
    }
}
