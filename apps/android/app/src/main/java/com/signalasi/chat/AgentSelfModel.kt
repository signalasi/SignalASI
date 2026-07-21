package com.signalasi.chat

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.util.Locale
import kotlin.math.roundToInt

data class AgentSelfCapabilityBelief(
    val key: String,
    val taskFamily: String,
    val resourceKey: String,
    val requiredCapabilities: Set<AgentCapability> = emptySet(),
    val successfulRuns: Int = 0,
    val failedRuns: Int = 0,
    val cancelledRuns: Int = 0,
    val correctionCount: Int = 0,
    val consecutiveFailures: Int = 0,
    val averageLatencyMillis: Long = 0L,
    val lastOutcome: AgentRecordedRunStatus = AgentRecordedRunStatus.RUNNING,
    val lastFailureCategory: String = "",
    val firstObservedAtMillis: Long = System.currentTimeMillis(),
    val lastObservedAtMillis: Long = System.currentTimeMillis()
) {
    val terminalRuns: Int get() = successfulRuns + failedRuns + cancelledRuns
    val evaluatedRuns: Int get() = successfulRuns + failedRuns
    val successRate: Double get() = if (evaluatedRuns == 0) 0.5 else successfulRuns.toDouble() / evaluatedRuns
    val confidence: Double get() = (evaluatedRuns / 8.0).coerceIn(0.0, 1.0)
}

data class AgentSelfModel(
    val identityId: String = AgentSelfModelReducer.IDENTITY_ID,
    val totalRuns: Int = 0,
    val successfulRuns: Int = 0,
    val failedRuns: Int = 0,
    val cancelledRuns: Int = 0,
    val beliefs: List<AgentSelfCapabilityBelief> = emptyList(),
    val processedRunIds: List<String> = emptyList(),
    val processedFeedbackKeys: List<String> = emptyList(),
    val updatedAtMillis: Long = 0L
) {
    fun strengths(limit: Int = 5): List<AgentSelfCapabilityBelief> = beliefs
        .filter { it.evaluatedRuns >= 3 && it.successRate >= 0.75 }
        .sortedWith(compareByDescending<AgentSelfCapabilityBelief> { it.confidence }
            .thenByDescending { it.successRate }
            .thenByDescending { it.lastObservedAtMillis })
        .take(limit.coerceIn(1, 20))

    fun limitations(limit: Int = 5): List<AgentSelfCapabilityBelief> = beliefs
        .filter { it.failedRuns >= 2 && (it.successRate < 0.50 || it.consecutiveFailures >= 2) }
        .sortedWith(compareByDescending<AgentSelfCapabilityBelief> { it.consecutiveFailures }
            .thenBy { it.successRate }
            .thenByDescending { it.lastObservedAtMillis })
        .take(limit.coerceIn(1, 20))
}

data class AgentSelfCalibration(
    val scoreAdjustment: Int = 0,
    val confidence: Double = 0.0,
    val evidenceRuns: Int = 0,
    val reason: String = ""
)

data class AgentSelfModelMutation(
    val before: AgentSelfModel,
    val after: AgentSelfModel,
    val belief: AgentSelfCapabilityBelief? = null,
    val changed: Boolean = before != after
)

object AgentSelfModelReducer {
    const val IDENTITY_ID = "signalasi-mobile"

    fun observeRun(current: AgentSelfModel, run: AgentRecordedRun): AgentSelfModelMutation {
        if (run.status == AgentRecordedRunStatus.RUNNING || run.runId.isBlank() || run.runId in current.processedRunIds) {
            return AgentSelfModelMutation(current, current)
        }
        val capabilities = AgentTaskRequirementAnalyzer.analyze(run.originalRequest).capabilities
        val family = safeTaskFamily(run.originalRequest, capabilities)
        val resourceKey = resourceKey(run.executionResourceId.ifBlank { inferResource(run) })
        val key = stableKey(family, resourceKey, capabilities.sortedBy(Enum<*>::name).joinToString(","))
        val now = run.completedAtMillis.takeIf { it > 0L } ?: System.currentTimeMillis()
        val latency = (now - run.createdAtMillis).coerceAtLeast(0L)
        val existing = current.beliefs.firstOrNull { it.key == key }
        val previousSamples = existing?.terminalRuns ?: 0
        val averageLatency = when {
            latency <= 0L -> existing?.averageLatencyMillis ?: 0L
            previousSamples <= 0 -> latency
            else -> (((existing?.averageLatencyMillis ?: 0L) * previousSamples) + latency) / (previousSamples + 1)
        }
        val belief = (existing ?: AgentSelfCapabilityBelief(
            key = key,
            taskFamily = family,
            resourceKey = resourceKey,
            requiredCapabilities = capabilities,
            firstObservedAtMillis = run.createdAtMillis.takeIf { it > 0L } ?: now
        )).copy(
            successfulRuns = (existing?.successfulRuns ?: 0) + if (run.status == AgentRecordedRunStatus.COMPLETED) 1 else 0,
            failedRuns = (existing?.failedRuns ?: 0) + if (run.status == AgentRecordedRunStatus.FAILED) 1 else 0,
            cancelledRuns = (existing?.cancelledRuns ?: 0) + if (run.status == AgentRecordedRunStatus.CANCELLED) 1 else 0,
            consecutiveFailures = when (run.status) {
                AgentRecordedRunStatus.COMPLETED -> 0
                AgentRecordedRunStatus.FAILED -> (existing?.consecutiveFailures ?: 0) + 1
                AgentRecordedRunStatus.CANCELLED -> existing?.consecutiveFailures ?: 0
                AgentRecordedRunStatus.RUNNING -> existing?.consecutiveFailures ?: 0
            },
            averageLatencyMillis = averageLatency,
            lastOutcome = run.status,
            lastFailureCategory = if (run.status == AgentRecordedRunStatus.FAILED) failureCategory(run) else "",
            lastObservedAtMillis = now
        )
        val beliefs = (current.beliefs.filterNot { it.key == key } + belief)
            .sortedByDescending(AgentSelfCapabilityBelief::lastObservedAtMillis)
            .take(MAX_BELIEFS)
        val after = current.copy(
            totalRuns = current.totalRuns + 1,
            successfulRuns = current.successfulRuns + if (run.status == AgentRecordedRunStatus.COMPLETED) 1 else 0,
            failedRuns = current.failedRuns + if (run.status == AgentRecordedRunStatus.FAILED) 1 else 0,
            cancelledRuns = current.cancelledRuns + if (run.status == AgentRecordedRunStatus.CANCELLED) 1 else 0,
            beliefs = beliefs,
            processedRunIds = (current.processedRunIds + run.runId).takeLast(MAX_PROCESSED_EVIDENCE),
            updatedAtMillis = now
        )
        return AgentSelfModelMutation(current, after, belief)
    }

    fun observeFeedback(
        current: AgentSelfModel,
        run: AgentRecordedRun,
        feedback: String,
        timestampMillis: Long = System.currentTimeMillis()
    ): AgentSelfModelMutation {
        val clean = feedback.trim()
        if (run.runId.isBlank() || clean.isBlank() || AgentLearningAnalyzer.containsSensitiveData(clean)) {
            return AgentSelfModelMutation(current, current)
        }
        val feedbackKey = stableKey(run.runId, clean)
        if (feedbackKey in current.processedFeedbackKeys) return AgentSelfModelMutation(current, current)
        val capabilities = AgentTaskRequirementAnalyzer.analyze(run.originalRequest).capabilities
        val family = safeTaskFamily(run.originalRequest, capabilities)
        val resource = resourceKey(run.executionResourceId.ifBlank { inferResource(run) })
        val beliefKey = stableKey(family, resource, capabilities.sortedBy(Enum<*>::name).joinToString(","))
        val existing = current.beliefs.firstOrNull { it.key == beliefKey }
            ?: AgentSelfCapabilityBelief(
                key = beliefKey,
                taskFamily = family,
                resourceKey = resource,
                requiredCapabilities = capabilities,
                firstObservedAtMillis = timestampMillis,
                lastObservedAtMillis = timestampMillis
            )
        val belief = existing.copy(
            correctionCount = existing.correctionCount + 1,
            lastObservedAtMillis = timestampMillis
        )
        val after = current.copy(
            beliefs = (current.beliefs.filterNot { it.key == beliefKey } + belief)
                .sortedByDescending(AgentSelfCapabilityBelief::lastObservedAtMillis)
                .take(MAX_BELIEFS),
            processedFeedbackKeys = (current.processedFeedbackKeys + feedbackKey).takeLast(MAX_PROCESSED_EVIDENCE),
            updatedAtMillis = timestampMillis
        )
        return AgentSelfModelMutation(current, after, belief)
    }

    fun calibration(
        model: AgentSelfModel,
        goal: String,
        resourceId: String,
        requirements: AgentTaskRequirements
    ): AgentSelfCalibration {
        val resource = resourceKey(resourceId)
        val family = safeTaskFamily(goal, requirements.capabilities)
        val matches = model.beliefs.filter { belief ->
            belief.resourceKey == resource && belief.evaluatedRuns > 0 &&
                (AgentLearningAnalyzer.sameTaskFamily(belief.taskFamily, family) ||
                    capabilityOverlap(belief.requiredCapabilities, requirements.capabilities) >= 0.74)
        }
        if (matches.isEmpty()) return AgentSelfCalibration()
        val evidence = matches.sumOf(AgentSelfCapabilityBelief::evaluatedRuns)
        if (evidence < MIN_CALIBRATION_RUNS) return AgentSelfCalibration(evidenceRuns = evidence)
        val successes = matches.sumOf(AgentSelfCapabilityBelief::successfulRuns)
        val failures = matches.sumOf(AgentSelfCapabilityBelief::failedRuns)
        val corrections = matches.sumOf(AgentSelfCapabilityBelief::correctionCount)
        val consecutiveFailures = matches.maxOf(AgentSelfCapabilityBelief::consecutiveFailures)
        val confidence = (evidence / 10.0).coerceIn(0.0, 1.0)
        val reliability = successes.toDouble() / (successes + failures).coerceAtLeast(1)
        var adjustment = ((reliability - 0.5) * 220.0 * confidence).roundToInt()
        adjustment -= (corrections * 12).coerceAtMost(48)
        if (consecutiveFailures >= 2) adjustment -= (70 + (consecutiveFailures - 2) * 25).coerceAtMost(145)
        adjustment = adjustment.coerceIn(MIN_ROUTE_ADJUSTMENT, MAX_ROUTE_ADJUSTMENT)
        return AgentSelfCalibration(
            scoreAdjustment = adjustment,
            confidence = confidence,
            evidenceRuns = evidence,
            reason = "self_model:${successes}s/${failures}f/${corrections}c"
        )
    }

    fun resourceKey(value: String): String {
        val id = value.trim().lowercase(Locale.ROOT)
        return when {
            id.isBlank() || id == IDENTITY_ID || id == "phone" || id == "android" -> IDENTITY_ID
            id == "claude" || id == "claude-code" || id.contains(":claude-code") -> "claude-code"
            id == "codex" || id.contains(":codex") -> "codex"
            id == "hermes" || id.contains(":hermes") -> "hermes"
            id == "openclaw" || id.contains(":openclaw") -> "openclaw"
            id == "local-llm" || id.contains(":local-llm") -> "local-llm"
            id == "cloud-models" || id.startsWith("cloud-model:") -> "cloud-models"
            id.startsWith("skill:") -> "skill:${stableKey(id).take(20)}"
            else -> "resource:${stableKey(id).take(24)}"
        }
    }

    private fun safeTaskFamily(request: String, capabilities: Set<AgentCapability>): String =
        if (AgentLearningAnalyzer.containsSensitiveData(request)) {
            "capabilities:${capabilities.sortedBy(Enum<*>::name).joinToString(",") { it.name.lowercase(Locale.ROOT) }}"
        } else {
            AgentLearningAnalyzer.taskFamily(request).ifBlank {
                "capabilities:${capabilities.sortedBy(Enum<*>::name).joinToString(",") { it.name.lowercase(Locale.ROOT) }}"
            }
        }

    private fun inferResource(run: AgentRecordedRun): String = when {
        run.activeSkillId.isNotBlank() -> "skill:${run.activeSkillId}"
        run.toolCalls.any { it.toolName.startsWith("android.") || it.toolName.startsWith("signalasi.runtime.") } -> IDENTITY_ID
        else -> IDENTITY_ID
    }

    private fun failureCategory(run: AgentRecordedRun): String {
        val signal = buildString {
            run.toolCalls.filter { it.status == AgentToolCallStatus.FAILED }.forEach { call ->
                append(call.toolName).append(' ').append(call.errorMessage).append(' ')
            }
            append(run.finalOutputJson)
        }.lowercase(Locale.ROOT)
        return when {
            "timeout" in signal || "timed out" in signal -> "timeout"
            "permission" in signal || "denied" in signal -> "permission"
            "network" in signal || "offline" in signal || "unreachable" in signal -> "connectivity"
            "cancel" in signal -> "cancelled"
            run.toolCalls.any { it.status == AgentToolCallStatus.FAILED } -> "tool_failure"
            else -> "run_failure"
        }
    }

    private fun capabilityOverlap(left: Set<AgentCapability>, right: Set<AgentCapability>): Double {
        if (left.isEmpty() && right.isEmpty()) return 1.0
        if (left.isEmpty() || right.isEmpty()) return 0.0
        return left.intersect(right).size.toDouble() / left.union(right).size
    }

    internal fun stableKey(vararg values: String): String = MessageDigest.getInstance("SHA-256")
        .digest(values.joinToString("\u0000").toByteArray(Charsets.UTF_8))
        .joinToString("") { byte -> "%02x".format(byte) }

    private const val MIN_CALIBRATION_RUNS = 2
    private const val MIN_ROUTE_ADJUSTMENT = -180
    private const val MAX_ROUTE_ADJUSTMENT = 120
    private const val MAX_BELIEFS = 512
    private const val MAX_PROCESSED_EVIDENCE = 4_000
}

class AgentSelfModelStore(context: Context) {
    private val appContext = context.applicationContext
    private val database = AgentEncryptedDatabase(appContext, DATABASE_NAME)

    @Synchronized
    fun snapshot(): AgentSelfModel = AgentSelfModelCodec.decode(database.readString(KEY_MODEL, ""))

    @Synchronized
    fun observeRun(run: AgentRecordedRun): AgentSelfModelMutation {
        val mutation = AgentSelfModelReducer.observeRun(snapshot(), run)
        if (mutation.changed) {
            database.writeString(KEY_MODEL, AgentSelfModelCodec.encode(mutation.after).toString())
            mutation.belief?.let { belief ->
                GlobalCapabilityObservationExtractor.selfModelTransition(mutation.before, mutation.after, belief, run)
                    ?.let { event -> GlobalConversationEventBus.publishCapabilityEvents(appContext, listOf(event)) }
            }
        }
        return mutation
    }

    @Synchronized
    fun observeFeedback(run: AgentRecordedRun, feedback: String): AgentSelfModelMutation {
        val mutation = AgentSelfModelReducer.observeFeedback(snapshot(), run, feedback)
        if (mutation.changed) database.writeString(KEY_MODEL, AgentSelfModelCodec.encode(mutation.after).toString())
        return mutation
    }

    fun calibration(goal: String, resourceId: String, requirements: AgentTaskRequirements): AgentSelfCalibration =
        AgentSelfModelReducer.calibration(snapshot(), goal, resourceId, requirements)

    @Synchronized
    fun exportJson(): JSONObject = AgentSelfModelCodec.encode(snapshot())

    @Synchronized
    fun restoreJson(value: JSONObject) {
        database.writeString(KEY_MODEL, AgentSelfModelCodec.encode(AgentSelfModelCodec.decode(value.toString())).toString())
    }

    @Synchronized
    fun clear() = database.clear()

    companion object {
        const val DATABASE_NAME = "signalasi_agent_self_model"
        private const val KEY_MODEL = "model"
    }
}

internal object AgentSelfModelCodec {
    fun encode(model: AgentSelfModel): JSONObject = JSONObject()
        .put("identity_id", model.identityId)
        .put("total_runs", model.totalRuns)
        .put("successful_runs", model.successfulRuns)
        .put("failed_runs", model.failedRuns)
        .put("cancelled_runs", model.cancelledRuns)
        .put("beliefs", JSONArray().apply { model.beliefs.forEach { put(encodeBelief(it)) } })
        .put("processed_run_ids", JSONArray(model.processedRunIds))
        .put("processed_feedback_keys", JSONArray(model.processedFeedbackKeys))
        .put("updated_at_millis", model.updatedAtMillis)

    fun decode(raw: String): AgentSelfModel = runCatching {
        if (raw.isBlank()) return@runCatching AgentSelfModel()
        val root = JSONObject(raw)
        AgentSelfModel(
            identityId = root.optString("identity_id").ifBlank { AgentSelfModelReducer.IDENTITY_ID },
            totalRuns = root.optInt("total_runs").coerceAtLeast(0),
            successfulRuns = root.optInt("successful_runs").coerceAtLeast(0),
            failedRuns = root.optInt("failed_runs").coerceAtLeast(0),
            cancelledRuns = root.optInt("cancelled_runs").coerceAtLeast(0),
            beliefs = root.optJSONArray("beliefs").objects().mapNotNull(::decodeBelief).take(512),
            processedRunIds = root.optJSONArray("processed_run_ids").strings().takeLast(4_000),
            processedFeedbackKeys = root.optJSONArray("processed_feedback_keys").strings().takeLast(4_000),
            updatedAtMillis = root.optLong("updated_at_millis").coerceAtLeast(0L)
        )
    }.getOrDefault(AgentSelfModel())

    private fun encodeBelief(value: AgentSelfCapabilityBelief): JSONObject = JSONObject()
        .put("key", value.key)
        .put("task_family", value.taskFamily)
        .put("resource_key", value.resourceKey)
        .put("required_capabilities", JSONArray(value.requiredCapabilities.map(Enum<*>::name)))
        .put("successful_runs", value.successfulRuns)
        .put("failed_runs", value.failedRuns)
        .put("cancelled_runs", value.cancelledRuns)
        .put("correction_count", value.correctionCount)
        .put("consecutive_failures", value.consecutiveFailures)
        .put("average_latency_millis", value.averageLatencyMillis)
        .put("last_outcome", value.lastOutcome.name)
        .put("last_failure_category", value.lastFailureCategory)
        .put("first_observed_at_millis", value.firstObservedAtMillis)
        .put("last_observed_at_millis", value.lastObservedAtMillis)

    private fun decodeBelief(value: JSONObject): AgentSelfCapabilityBelief? = runCatching {
        val key = value.getString("key")
        if (key.isBlank()) return@runCatching null
        AgentSelfCapabilityBelief(
            key = key,
            taskFamily = value.optString("task_family").take(320),
            resourceKey = value.optString("resource_key").take(80),
            requiredCapabilities = value.optJSONArray("required_capabilities").strings()
                .mapNotNull { name -> enumValues<AgentCapability>().firstOrNull { it.name == name } }
                .toSet(),
            successfulRuns = value.optInt("successful_runs").coerceAtLeast(0),
            failedRuns = value.optInt("failed_runs").coerceAtLeast(0),
            cancelledRuns = value.optInt("cancelled_runs").coerceAtLeast(0),
            correctionCount = value.optInt("correction_count").coerceAtLeast(0),
            consecutiveFailures = value.optInt("consecutive_failures").coerceAtLeast(0),
            averageLatencyMillis = value.optLong("average_latency_millis").coerceAtLeast(0L),
            lastOutcome = enumValues<AgentRecordedRunStatus>()
                .firstOrNull { it.name == value.optString("last_outcome") }
                ?: AgentRecordedRunStatus.RUNNING,
            lastFailureCategory = value.optString("last_failure_category").take(80),
            firstObservedAtMillis = value.optLong("first_observed_at_millis").coerceAtLeast(0L),
            lastObservedAtMillis = value.optLong("last_observed_at_millis").coerceAtLeast(0L)
        )
    }.getOrNull()
}

private fun JSONArray?.objects(): List<JSONObject> {
    if (this == null) return emptyList()
    return buildList {
        for (index in 0 until length()) optJSONObject(index)?.let(::add)
    }
}

private fun JSONArray?.strings(): List<String> {
    if (this == null) return emptyList()
    return buildList {
        for (index in 0 until length()) optString(index).takeIf(String::isNotBlank)?.let(::add)
    }
}
