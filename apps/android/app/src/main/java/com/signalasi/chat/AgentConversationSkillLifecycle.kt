package com.signalasi.chat

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.util.Locale
import java.util.UUID

enum class AgentRecordedRunStatus { RUNNING, COMPLETED, FAILED, CANCELLED }

data class AgentRecordedRun(
    val runId: String,
    val conversationId: String,
    val taskThreadId: String,
    val originalRequest: String,
    val normalizedIntent: String = "",
    val extractedInputsJson: String = "{}",
    val agentPlanJson: String = "[]",
    val toolCalls: List<AgentToolCallRecord> = emptyList(),
    val sourcesJson: String = "[]",
    val transformationsJson: String = "[]",
    val finalOutputJson: String = "{}",
    val renderSpecJson: String = "{}",
    val artifacts: List<AgentArtifactReference> = emptyList(),
    val userFeedback: List<String> = emptyList(),
    val activeSkillId: String = "",
    val parentRunId: String = "",
    val revisionNumber: Int = 1,
    val status: AgentRecordedRunStatus = AgentRecordedRunStatus.RUNNING,
    val createdAtMillis: Long = System.currentTimeMillis(),
    val completedAtMillis: Long = 0L
)

data class AgentTaskThreadContext(
    val taskThreadId: String,
    val conversationId: String,
    val activeRunId: String,
    val activeArtifactId: String = "",
    val activeSkillId: String = "",
    val revisionNumber: Int = 1,
    val updatedAtMillis: Long = System.currentTimeMillis()
)

class AgentRunRecorder(context: Context) {
    private val database = AgentEncryptedDatabase(context.applicationContext, PREFERENCES_NAME)

    @Synchronized
    fun begin(
        conversationId: String,
        request: String,
        activeSkillId: String = "",
        forceNewThread: Boolean = false
    ): AgentRecordedRun {
        val currentContext = context(conversationId)
        val taskThreadId = if (!forceNewThread && currentContext != null) {
            currentContext.taskThreadId
        } else {
            UUID.randomUUID().toString()
        }
        val parent = runsForThread(taskThreadId).maxByOrNull { it.revisionNumber }
        val run = AgentRecordedRun(
            runId = UUID.randomUUID().toString(),
            conversationId = conversationId,
            taskThreadId = taskThreadId,
            originalRequest = request.trim().take(MAX_REQUEST_CHARS),
            activeSkillId = activeSkillId,
            parentRunId = parent?.runId.orEmpty(),
            revisionNumber = (parent?.revisionNumber ?: 0) + 1
        )
        saveRuns((allRuns() + run).takeLast(MAX_RUNS))
        saveContext(
            AgentTaskThreadContext(
                taskThreadId = taskThreadId,
                conversationId = conversationId,
                activeRunId = run.runId,
                activeArtifactId = currentContext?.activeArtifactId.orEmpty(),
                activeSkillId = activeSkillId,
                revisionNumber = run.revisionNumber
            )
        )
        return run
    }

    @Synchronized
    fun complete(
        runId: String,
        planJson: String,
        toolCalls: List<AgentToolCallRecord>,
        sourcesJson: String,
        finalOutputJson: String,
        renderSpecJson: String,
        artifacts: List<AgentArtifactReference>,
        success: Boolean = true
    ): AgentRecordedRun? = update(runId) { current ->
        current.copy(
            normalizedIntent = normalizeIntent(current.originalRequest),
            extractedInputsJson = inferInputs(current.originalRequest),
            agentPlanJson = safeJson(planJson, "[]", MAX_PLAN_CHARS),
            toolCalls = toolCalls.take(MAX_TOOL_CALLS),
            sourcesJson = sanitizeSecrets(safeJson(sourcesJson, "[]", MAX_RESULT_CHARS)),
            finalOutputJson = sanitizeSecrets(safeJson(finalOutputJson, "{}", MAX_RESULT_CHARS)),
            renderSpecJson = sanitizeSecrets(safeJson(renderSpecJson, "{}", MAX_RENDER_CHARS)),
            artifacts = artifacts.take(MAX_ARTIFACTS),
            status = if (success) AgentRecordedRunStatus.COMPLETED else AgentRecordedRunStatus.FAILED,
            completedAtMillis = System.currentTimeMillis()
        )
    }

    @Synchronized
    fun addFeedback(conversationId: String, feedback: String): AgentRecordedRun? {
        val active = activeRun(conversationId) ?: return null
        return update(active.runId) { run ->
            run.copy(userFeedback = (run.userFeedback + feedback.trim().take(MAX_FEEDBACK_CHARS)).takeLast(32))
        }
    }

    @Synchronized
    fun activeRun(conversationId: String): AgentRecordedRun? =
        context(conversationId)?.activeRunId?.let { id -> allRuns().firstOrNull { it.runId == id } }

    @Synchronized
    fun runsForThread(taskThreadId: String): List<AgentRecordedRun> =
        allRuns().filter { it.taskThreadId == taskThreadId }.sortedBy { it.revisionNumber }

    @Synchronized
    fun context(conversationId: String): AgentTaskThreadContext? = contexts()
        .firstOrNull { it.conversationId == conversationId }

    @Synchronized
    fun setActiveSkill(conversationId: String, skillId: String) {
        val current = context(conversationId) ?: return
        saveContext(current.copy(activeSkillId = skillId.trim(), updatedAtMillis = System.currentTimeMillis()))
    }

    @Synchronized
    fun clear() = database.clear()

    private fun update(runId: String, transform: (AgentRecordedRun) -> AgentRecordedRun): AgentRecordedRun? {
        val runs = allRuns().toMutableList()
        val index = runs.indexOfFirst { it.runId == runId }
        if (index < 0) return null
        val updated = transform(runs[index])
        runs[index] = updated
        saveRuns(runs)
        val current = context(updated.conversationId)
        if (current != null) {
            saveContext(current.copy(activeRunId = updated.runId, revisionNumber = updated.revisionNumber))
        }
        return updated
    }

    private fun allRuns(): List<AgentRecordedRun> = decodeRuns(database.readString(KEY_RUNS, "[]"))

    private fun contexts(): List<AgentTaskThreadContext> = decodeContexts(database.readString(KEY_CONTEXTS, "[]"))

    private fun saveRuns(runs: List<AgentRecordedRun>) {
        database.writeString(KEY_RUNS, JSONArray().apply { runs.forEach { put(it.toJson()) } }.toString())
    }

    private fun saveContext(context: AgentTaskThreadContext) {
        val remaining = contexts().filterNot { it.conversationId == context.conversationId } + context
        database.writeString(KEY_CONTEXTS, JSONArray().apply { remaining.takeLast(MAX_CONTEXTS).forEach { put(it.toJson()) } }.toString())
    }

    private fun decodeRuns(raw: String): List<AgentRecordedRun> = runCatching {
        val array = JSONArray(raw)
        buildList {
            for (index in 0 until array.length()) array.optJSONObject(index)?.toRun()?.let(::add)
        }
    }.getOrDefault(emptyList())

    private fun decodeContexts(raw: String): List<AgentTaskThreadContext> = runCatching {
        val array = JSONArray(raw)
        buildList {
            for (index in 0 until array.length()) array.optJSONObject(index)?.toTaskContext()?.let(::add)
        }
    }.getOrDefault(emptyList())

    private fun AgentRecordedRun.toJson() = JSONObject()
        .put("run_id", runId)
        .put("conversation_id", conversationId)
        .put("task_thread_id", taskThreadId)
        .put("original_request", originalRequest)
        .put("normalized_intent", normalizedIntent)
        .put("extracted_inputs", JSONObject(extractedInputsJson))
        .put("agent_plan", JSONArray(agentPlanJson))
        .put("tool_calls", JSONArray().apply { toolCalls.forEach { put(it.toJson()) } })
        .put("sources", JSONArray(sourcesJson))
        .put("transformations", JSONArray(transformationsJson))
        .put("final_output", JSONObject(finalOutputJson))
        .put("render_spec", JSONObject(renderSpecJson))
        .put("artifacts", JSONArray().apply { artifacts.forEach { put(it.toJson()) } })
        .put("user_feedback", JSONArray(userFeedback))
        .put("active_skill_id", activeSkillId)
        .put("parent_run_id", parentRunId)
        .put("revision_number", revisionNumber)
        .put("status", status.name)
        .put("created_at", createdAtMillis)
        .put("completed_at", completedAtMillis)

    private fun JSONObject.toRun(): AgentRecordedRun? = runCatching {
        AgentRecordedRun(
            runId = getString("run_id"),
            conversationId = getString("conversation_id"),
            taskThreadId = getString("task_thread_id"),
            originalRequest = optString("original_request"),
            normalizedIntent = optString("normalized_intent"),
            extractedInputsJson = optJSONObject("extracted_inputs")?.toString() ?: "{}",
            agentPlanJson = optJSONArray("agent_plan")?.toString() ?: "[]",
            toolCalls = optJSONArray("tool_calls")?.objects().orEmpty().mapNotNull { it.toToolCall() },
            sourcesJson = optJSONArray("sources")?.toString() ?: "[]",
            transformationsJson = optJSONArray("transformations")?.toString() ?: "[]",
            finalOutputJson = optJSONObject("final_output")?.toString() ?: "{}",
            renderSpecJson = optJSONObject("render_spec")?.toString() ?: "{}",
            artifacts = optJSONArray("artifacts")?.objects().orEmpty().mapNotNull { it.toArtifact() },
            userFeedback = optJSONArray("user_feedback")?.strings().orEmpty(),
            activeSkillId = optString("active_skill_id"),
            parentRunId = optString("parent_run_id"),
            revisionNumber = optInt("revision_number", 1).coerceAtLeast(1),
            status = runCatching { AgentRecordedRunStatus.valueOf(optString("status")) }.getOrDefault(AgentRecordedRunStatus.FAILED),
            createdAtMillis = optLong("created_at"),
            completedAtMillis = optLong("completed_at")
        )
    }.getOrNull()

    private fun AgentTaskThreadContext.toJson() = JSONObject()
        .put("task_thread_id", taskThreadId)
        .put("conversation_id", conversationId)
        .put("active_run_id", activeRunId)
        .put("active_artifact_id", activeArtifactId)
        .put("active_skill_id", activeSkillId)
        .put("revision_number", revisionNumber)
        .put("updated_at", updatedAtMillis)

    private fun JSONObject.toTaskContext(): AgentTaskThreadContext? = runCatching {
        AgentTaskThreadContext(
            taskThreadId = getString("task_thread_id"),
            conversationId = getString("conversation_id"),
            activeRunId = getString("active_run_id"),
            activeArtifactId = optString("active_artifact_id"),
            activeSkillId = optString("active_skill_id"),
            revisionNumber = optInt("revision_number", 1),
            updatedAtMillis = optLong("updated_at")
        )
    }.getOrNull()

    private fun AgentToolCallRecord.toJson() = JSONObject()
        .put("id", id).put("tool", toolName).put("status", status.name)
        .put("arguments", JSONObject(argumentsJson.ifBlank { "{}" }))
        .put("result", JSONObject(resultJson.ifBlank { "{}" }))
        .put("error", errorMessage).put("started_at", startedAtMillis).put("completed_at", completedAtMillis)

    private fun JSONObject.toToolCall(): AgentToolCallRecord? = runCatching {
        AgentToolCallRecord(
            id = getString("id"), toolName = getString("tool"),
            status = AgentToolCallStatus.valueOf(getString("status")),
            argumentsJson = optJSONObject("arguments")?.toString() ?: "{}",
            resultJson = optJSONObject("result")?.toString() ?: "{}",
            errorMessage = optString("error"), startedAtMillis = optLong("started_at"),
            completedAtMillis = optLong("completed_at")
        )
    }.getOrNull()

    private fun AgentArtifactReference.toJson() = JSONObject()
        .put("id", id).put("uri", uri).put("name", name).put("mime_type", mimeType)
        .put("metadata", metadataJson).put("created_at", createdAtMillis)

    private fun JSONObject.toArtifact(): AgentArtifactReference? = runCatching {
        AgentArtifactReference(
            id = getString("id"), uri = getString("uri"), name = optString("name"),
            mimeType = optString("mime_type"), metadataJson = optString("metadata"),
            createdAtMillis = optLong("created_at")
        )
    }.getOrNull()

    private fun normalizeIntent(request: String): String = request.trim().replace(Regex("\\s+"), " ").take(240)

    private fun inferInputs(request: String): String = JSONObject()
        .put("request", request.trim().take(MAX_REQUEST_CHARS))
        .toString()

    private fun safeJson(value: String, fallback: String, limit: Int): String = runCatching {
        val trimmed = value.trim().take(limit)
        if (fallback.startsWith("[")) JSONArray(trimmed).toString() else JSONObject(trimmed).toString()
    }.getOrDefault(fallback)

    companion object {
        private const val PREFERENCES_NAME = "signalasi_agent_runs"
        private const val KEY_RUNS = "runs"
        private const val KEY_CONTEXTS = "contexts"
        private const val MAX_RUNS = 256
        private const val MAX_CONTEXTS = 64
        private const val MAX_REQUEST_CHARS = 8_000
        private const val MAX_PLAN_CHARS = 32_000
        private const val MAX_RESULT_CHARS = 96_000
        private const val MAX_RENDER_CHARS = 24_000
        private const val MAX_FEEDBACK_CHARS = 4_000
        private const val MAX_TOOL_CALLS = 64
        private const val MAX_ARTIFACTS = 64
    }
}

data class AgentSkillMatch(
    val installation: AgentSkillInstallation,
    val confidence: Double,
    val parameters: Map<String, Any?>,
    val explicit: Boolean = false
)

class AgentSkillMatcher(private val runtime: AgentSkillRuntime) {
    fun match(request: String): AgentSkillMatch? {
        val normalized = normalize(request)
        if (normalized.isBlank()) return null
        val explicitRequest = request.trim().takeIf { it.startsWith("@") }
            ?.substringAfter('@')?.trim().orEmpty()
        return runtime.list(enabledOnly = true)
            .asSequence()
            .filter { it.autoInvoke || explicitRequest.isNotBlank() }
            .filterNot { skill -> skill.manifest.negativeExamples.any { example -> similarity(normalized, normalize(example)) >= 0.7 } }
            .map { installation ->
                val explicitTarget = listOf(installation.id, installation.manifest.title)
                    .firstOrNull { target ->
                        explicitRequest.equals(target, true) || explicitRequest.startsWith("$target ", true)
                    }.orEmpty()
                val explicit = explicitTarget.isNotBlank()
                val score = if (explicit) 1.0 else installation.manifest.triggerExamples
                    .maxOfOrNull { similarity(normalized, normalize(it)) } ?: 0.0
                AgentSkillMatch(
                    installation = installation,
                    confidence = score,
                    parameters = mapOf(
                        "request" to request.trim().let { original ->
                            if (!explicit) original else explicitRequest.drop(explicitTarget.length).trim()
                        }
                    ),
                    explicit = explicit
                )
            }
            .filter { it.explicit || it.confidence >= AUTO_MATCH_THRESHOLD }
            .maxByOrNull { it.confidence }
    }

    private fun normalize(value: String): String = value.lowercase(Locale.US)
        .replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()

    private fun similarity(left: String, right: String): Double {
        if (left == right) return 1.0
        val leftTokens = left.split(' ').filter(String::isNotBlank).toSet()
        val rightTokens = right.split(' ').filter(String::isNotBlank).toSet()
        if (leftTokens.isEmpty() || rightTokens.isEmpty()) return 0.0
        val tokenScore = leftTokens.intersect(rightTokens).size.toDouble() / leftTokens.union(rightTokens).size
        val containsScore = if (left.contains(right) || right.contains(left)) 0.85 else 0.0
        return maxOf(tokenScore, containsScore)
    }

    companion object {
        const val AUTO_MATCH_THRESHOLD = 0.78
    }
}

class AgentConversationSkillCompiler(
    private val runtime: AgentSkillRuntime,
    private val availableTools: () -> List<AgentNativeToolDescriptor>
) {
    fun compile(runs: List<AgentRecordedRun>, titleHint: String = ""): AgentSkillManifest {
        val successful = runs.filter { it.status == AgentRecordedRunStatus.COMPLETED }
        require(successful.isNotEmpty()) { "A completed Agent run is required before saving a Skill" }
        val latest = successful.last()
        val calls = successful.flatMap { it.toolCalls }
            .filter { it.status == AgentToolCallStatus.SUCCEEDED }
        require(calls.isNotEmpty()) { "The active task has no successful reusable tool calls" }
        val descriptors = availableTools().associateBy { it.id }
        val toolIds = calls.map { it.toolName }.filter(descriptors::containsKey).distinct()
        require(toolIds.isNotEmpty()) { "The active task used no installed declarative tools" }
        val id = "skill_${sha256(successful.first().normalizedIntent.ifBlank { successful.first().originalRequest }).take(16)}"
        val title = titleHint.trim().ifBlank { deriveTitle(successful.first().originalRequest) }
        val steps = calls.filter { it.toolName in toolIds }.mapIndexed { index, call ->
            AgentSkillStep(
                id = "step_${index + 1}",
                toolId = call.toolName,
                input = parameterizeJson(call.argumentsJson, successful.first().originalRequest),
                dependsOn = if (index == 0) emptyList() else listOf("step_$index")
            )
        }
        val permissions = toolIds.flatMap { descriptors[it]?.requiredPermissions.orEmpty() }
            .filter { it.required }.map { it.id }.toSet()
        val renderSpec = runCatching { jsonToMap(JSONObject(latest.renderSpecJson)) }.getOrDefault(emptyMap())
        val manifest = AgentSkillManifest(
            id = id,
            version = "1.0.0",
            title = title,
            instructions = buildInstructions(successful),
            nativeTools = toolIds.toSet(),
            permissions = permissions,
            parameters = AgentSkillParameterSchema.objectSchema(
                properties = mapOf("request" to AgentSkillParameterSchema.string(minLength = 1, maxLength = 8_000)),
                required = setOf("request")
            ),
            steps = steps,
            description = "Reusable method learned from a completed Agent task",
            author = "User Generated",
            source = "conversation",
            autoInvoke = true,
            triggerExamples = successful.map { it.originalRequest }.distinct().take(12),
            negativeExamples = emptyList(),
            renderSpec = renderSpec,
            tests = listOf(
                AgentSkillTestCase(
                    id = "regression_1",
                    input = mapOf("request" to successful.first().originalRequest),
                    expectedToolIds = toolIds.toSet()
                )
            )
        )
        runtime.validate(manifest).requireValid()
        return manifest
    }

    fun install(runs: List<AgentRecordedRun>, titleHint: String = ""): AgentSkillInstallation =
        runtime.install(compile(runs, titleHint))

    private fun deriveTitle(request: String): String = request.trim().replace(Regex("\\s+"), " ").take(32)
        .ifBlank { "Learned Skill" }

    private fun buildInstructions(runs: List<AgentRecordedRun>): String = buildString {
        append("Complete requests in this task family by following the saved declarative tool workflow. ")
        append("Treat the request parameter as variable input. Never copy credentials, cookies, tokens, or private data into the Skill. ")
        val feedback = runs.flatMap { it.userFeedback }.distinct()
        if (feedback.isNotEmpty()) append("User-approved refinements: ").append(feedback.joinToString("; ").take(8_000))
    }

    private fun parameterizeJson(raw: String, originalRequest: String): Map<String, Any?> {
        val source = runCatching { jsonToMap(JSONObject(raw.ifBlank { "{}" })) }.getOrDefault(emptyMap())
        return (replaceValue(source, originalRequest) as? Map<*, *>)?.entries
            ?.mapNotNull { (key, value) -> (key as? String)?.let { it to value } }
            ?.toMap().orEmpty()
    }

    private fun replaceValue(value: Any?, originalRequest: String): Any? = when (value) {
        is String -> if (value.trim() == originalRequest.trim()) "{{parameters.request}}" else sanitizeSecrets(value)
        is Map<*, *> -> value.entries.associate { it.key.toString() to replaceValue(it.value, originalRequest) }
        is List<*> -> value.map { replaceValue(it, originalRequest) }
        else -> value
    }
}

class AgentSkillVersionManager(private val runtime: AgentSkillRuntime) {
    fun upgrade(base: AgentSkillInstallation, improvedRuns: List<AgentRecordedRun>): AgentSkillInstallation {
        val latest = improvedRuns.lastOrNull() ?: error("An improved run is required")
        val feedback = improvedRuns.flatMap { it.userFeedback }.distinct().joinToString("; ")
        val next = base.manifest.copy(
            version = incrementMinor(base.version),
            instructions = listOf(base.manifest.instructions, feedback).filter(String::isNotBlank).joinToString("\n").take(32_000),
            renderSpec = runCatching { jsonToMap(JSONObject(latest.renderSpecJson)) }.getOrDefault(base.manifest.renderSpec),
            tests = (base.manifest.tests + AgentSkillTestCase(
                id = "regression_${base.manifest.tests.size + 1}",
                input = mapOf("request" to latest.originalRequest),
                expectedToolIds = base.manifest.nativeTools
            )).take(AgentSkillLimits.MAX_TESTS)
        )
        runtime.validate(next).requireValid()
        return runtime.install(next)
    }

    fun rollback(id: String, currentVersion: String): AgentSkillInstallation {
        val previous = runtime.list().filter { it.id == id && compareVersions(it.version, currentVersion) < 0 }
            .maxWithOrNull { left, right -> compareVersions(left.version, right.version) }
            ?: error("No previous Skill version is available")
        runtime.list().filter { it.id == id }.forEach { runtime.disable(it.id, it.version) }
        return runtime.enable(previous.id, previous.version)
    }

    private fun incrementMinor(version: String): String {
        val parts = version.split('.').mapNotNull(String::toIntOrNull).toMutableList()
        while (parts.size < 3) parts += 0
        return "${parts[0]}.${parts[1] + 1}.0"
    }

    private fun compareVersions(left: String, right: String): Int {
        val l = left.split('.').map { it.toIntOrNull() ?: 0 }
        val r = right.split('.').map { it.toIntOrNull() ?: 0 }
        return (0..2).firstNotNullOfOrNull { index ->
            (l.getOrElse(index) { 0 } - r.getOrElse(index) { 0 }).takeIf { it != 0 }
        } ?: 0
    }
}

data class AgentSkillExecutionResult(
    val success: Boolean,
    val skillId: String,
    val version: String,
    val message: String,
    val toolResults: List<AgentNativeToolResult> = emptyList()
)

class AgentSkillExecutionEngine(
    private val runtime: AgentSkillRuntime,
    private val mobileAgent: MobileNativeAgent
) {
    fun execute(match: AgentSkillMatch): AgentSkillExecutionResult {
        val expansion = runtime.expand(match.installation.id, match.installation.version, match.parameters)
        val catalog = mobileAgent.nativeToolCatalog().associateBy { it.id }
        val results = mutableListOf<AgentNativeToolResult>()
        expansion.orderedSteps.forEach { step ->
            val descriptor = catalog[step.toolId]
                ?: return fallback(match, "Missing tool: ${step.toolId}", results)
            if (descriptor.availability.status != AgentNativeToolAvailabilityStatus.AVAILABLE) {
                return fallback(match, "Tool unavailable: ${step.toolId}", results)
            }
            if (descriptor.risk.weight >= AgentNativeToolRisk.HIGH.weight || descriptor.requiredConsents.any { it.required }) {
                return fallback(match, "Tool requires interactive authorization: ${step.toolId}", results)
            }
            val result = mobileAgent.invokeNativeTool(
                toolId = step.toolId,
                input = step.input,
                grantedPermissions = descriptor.requiredPermissions.filter { it.required }.mapTo(linkedSetOf()) { it.id }
            )
            results += result
            if (!result.isSuccess) return fallback(match, result.message, results)
        }
        runtime.recordUse(match.installation.id, match.installation.version)
        val final = results.lastOrNull()?.let { AgentNativeJsonCodec.stringify(it.output) }.orEmpty()
        return AgentSkillExecutionResult(
            success = true,
            skillId = match.installation.id,
            version = match.installation.version,
            message = final.ifBlank { "Skill completed" },
            toolResults = results
        )
    }

    private fun fallback(
        match: AgentSkillMatch,
        reason: String,
        results: List<AgentNativeToolResult>
    ) = AgentSkillExecutionResult(false, match.installation.id, match.installation.version, reason, results)
}

object AgentSkillCommandParser {
    fun isSaveCommand(value: String): Boolean {
        val text = value.trim().lowercase(Locale.US)
        return text in setOf(
            "save as skill", "save this as a skill", "save this method", "remember this method",
            "\u4fdd\u5b58\u6210 skill", "\u4fdd\u5b58\u4e3a skill", "\u628a\u8fd9\u4e2a\u4fdd\u5b58\u4e3a skill",
            "\u4ee5\u540e\u6309\u8fd9\u4e2a\u65b9\u5f0f\u6267\u884c", "\u628a\u521a\u624d\u7684\u65b9\u6cd5\u4fdd\u5b58\u4e0b\u6765"
        )
    }

    fun isUpgradeCommand(value: String): Boolean {
        val text = value.trim().lowercase(Locale.US)
        return text in setOf(
            "upgrade skill", "upgrade this skill", "improve this skill",
            "\u5347\u7ea7 skill", "\u5347\u7ea7\u8fd9\u4e2a skill", "\u6539\u8fdb\u8fd9\u4e2a skill"
        )
    }
}

object AgentBuiltInSkills {
    fun installAvailable(runtime: AgentSkillRuntime): List<AgentSkillInstallation> = manifests()
        .mapNotNull { manifest ->
            if (!runtime.validate(manifest).isValid) null else runCatching { runtime.install(manifest) }.getOrNull()
        }

    fun manifests(): List<AgentSkillManifest> = listOf(
        statusSkill(
            id = "signalasi.builtin.battery-health",
            title = "Battery health",
            description = "Read the phone battery level, charging state, temperature, voltage, and health.",
            toolId = AgentHardwareNativeTools.BATTERY_STATUS,
            triggers = listOf("Check my phone battery", "Show battery health", "\u67e5\u770b\u624b\u673a\u7535\u91cf", "\u68c0\u67e5\u7535\u6c60\u5065\u5eb7")
        ),
        statusSkill(
            id = "signalasi.builtin.network-status",
            title = "Network status",
            description = "Inspect the phone's current app-visible network capabilities.",
            toolId = AgentHardwareNativeTools.NETWORK_STATUS,
            triggers = listOf("Check my phone network", "Show network status", "\u67e5\u770b\u624b\u673a\u7f51\u7edc", "\u68c0\u67e5\u7f51\u7edc\u72b6\u6001")
        ),
        statusSkill(
            id = "signalasi.builtin.storage-status",
            title = "Storage status",
            description = "Read total and available phone storage visible to SignalASI.",
            toolId = AgentHardwareNativeTools.STORAGE_STATUS,
            triggers = listOf("Check phone storage", "Show free storage", "\u67e5\u770b\u624b\u673a\u5b58\u50a8", "\u8fd8\u6709\u591a\u5c11\u5b58\u50a8\u7a7a\u95f4")
        ),
        statusSkill(
            id = "signalasi.builtin.device-power",
            title = "Power status",
            description = "Inspect screen, power-save, interactive, and idle state on this phone.",
            toolId = AgentHardwareNativeTools.POWER_STATUS,
            triggers = listOf("Check phone power state", "Show power saving status", "\u67e5\u770b\u624b\u673a\u7535\u6e90\u72b6\u6001", "\u68c0\u67e5\u7701\u7535\u72b6\u6001")
        )
    )

    private fun statusSkill(
        id: String,
        title: String,
        description: String,
        toolId: String,
        triggers: List<String>
    ) = AgentSkillManifest(
        id = id,
        version = "1.0.0",
        title = title,
        description = description,
        instructions = "Read the current phone-local status with the declared native tool and render the complete structured result.",
        nativeTools = setOf(toolId),
        parameters = AgentSkillParameterSchema.objectSchema(
            properties = mapOf("request" to AgentSkillParameterSchema.string(maxLength = 8_000)),
            required = emptySet()
        ),
        steps = listOf(AgentSkillStep("read", toolId)),
        source = "built_in",
        autoInvoke = false,
        triggerExamples = triggers,
        negativeExamples = listOf("Check desktop status", "Run a server diagnostic"),
        renderSpec = mapOf("type" to "key_value", "title" to title),
        tests = listOf(AgentSkillTestCase("registered_tool", expectedToolIds = setOf(toolId)))
    )
}

private fun sanitizeSecrets(value: String): String {
    var sanitized = value
    SECRET_PATTERNS.forEach { pattern -> sanitized = pattern.replace(sanitized, "$1[REDACTED]") }
    return sanitized
}

private fun AgentSkillValidationResult.requireValid() {
    if (!isValid) throw AgentSkillValidationException(this)
}

private val SECRET_PATTERNS = listOf(
    Regex("(?i)(authorization\\s*[:=]\\s*)([^\\s,;]+)"),
    Regex("(?i)((?:api[_-]?key|token|password|cookie)\\s*[:=]\\s*)([^\\s,;]+)")
)

private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
    .digest(value.toByteArray()).joinToString("") { "%02x".format(it) }

private fun jsonToMap(json: JSONObject): Map<String, Any?> = json.keys().asSequence().associateWith { key ->
    when (val value = json.opt(key)) {
        is JSONObject -> jsonToMap(value)
        is JSONArray -> (0 until value.length()).map { index ->
            when (val item = value.opt(index)) {
                is JSONObject -> jsonToMap(item)
                else -> item
            }
        }
        JSONObject.NULL -> null
        else -> value
    }
}

private fun JSONArray.objects(): List<JSONObject> = buildList {
    for (index in 0 until length()) optJSONObject(index)?.let(::add)
}

private fun JSONArray.strings(): List<String> = buildList {
    for (index in 0 until length()) optString(index).takeIf(String::isNotBlank)?.let(::add)
}
