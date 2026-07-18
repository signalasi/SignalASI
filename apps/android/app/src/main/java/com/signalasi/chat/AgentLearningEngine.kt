package com.signalasi.chat

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.util.Locale
import java.util.UUID

enum class AgentLearningProposalKind { SKILL, SKILL_UPGRADE, BEHAVIOR_RULE }

enum class AgentLearningProposalStatus { PENDING, APPROVED, REJECTED }

data class AgentLearningProposal(
    val id: String,
    val kind: AgentLearningProposalKind,
    val title: String,
    val taskFamily: String,
    val summary: String,
    val evidenceRunIds: List<String>,
    val manifestJson: String = "",
    val status: AgentLearningProposalStatus = AgentLearningProposalStatus.PENDING,
    val createdAtMillis: Long = System.currentTimeMillis(),
    val reviewedAtMillis: Long = 0L
)

data class AgentLearningOutcome(
    val memories: List<AgentMemoryWriteResult> = emptyList(),
    val proposals: List<AgentLearningProposal> = emptyList()
)

object AgentLearningAnalyzer {
    private val url = Regex("(?i)\\bhttps?://[^\\s]+")
    private val windowsPath = Regex("(?i)(?:[a-z]:\\\\|\\\\\\\\)[^\\r\\n]+")
    private val unixPath = Regex("(?<![A-Za-z0-9])/(?:[^\\s/]+/)*[^\\s/]+")
    private val uuid = Regex("(?i)\\b[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}\\b")
    private val email = Regex("(?i)\\b[A-Z0-9._%+-]+@[A-Z0-9.-]+\\.[A-Z]{2,}\\b")
    private val number = Regex("(?<![\\p{L}\\p{N}])[-+]?\\d+(?:[.,:]\\d+)*(?![\\p{L}\\p{N}])")
    private val quoted = Regex("([\"'`]).{1,160}?\\1")
    private val secret = Regex(
        "(?i)(?:api[_-]?key|authorization|bearer|cookie|password|passwd|secret|token|otp|verification[_ -]?code)\\s*[:=]?\\s*\\S+"
    )
    private val englishPreference = Regex(
        "(?i)^(?:please\\s+)?(?:remember(?:\\s+that)?|i\\s+prefer|my\\s+preference\\s+is|always|use\\s+.+?\\s+by\\s+default)\\b[\\s,:-]*(.+)$"
    )
    private val cjkPreference = Regex(
        "^(?:\\u8bf7)?(?:\\u8bb0\\u4f4f|\\u6211\\u559c\\u6b22|\\u6211\\u504f\\u597d|\\u4ee5\\u540e|\\u9ed8\\u8ba4)[\\s:\\uFF1A,\\uFF0C-]*(.+)$"
    )
    private val englishCorrection = Regex(
        "(?i)^(?:no\\b|wrong\\b|that(?:'s| is) (?:wrong|not right)|not (?:that|this) way|instead\\b|change (?:it|that) to\\b|use .+ instead\\b|try again\\b).+"
    )
    private val cjkCorrection = Regex(
        "^(?:\\u4e0d\\u5bf9|\\u9519\\u4e86|\\u4e0d\\u662f\\u8fd9\\u6837|\\u6539\\u6210|\\u5e94\\u8be5|\\u4e0d\\u8981.+\\u8981|\\u91cd\\u65b0).+"
    )

    fun taskFamily(request: String): String = request
        .trim()
        .lowercase(Locale.ROOT)
        .let { url.replace(it, " <url> ") }
        .let { windowsPath.replace(it, " <path> ") }
        .let { unixPath.replace(it, " <path> ") }
        .let { uuid.replace(it, " <id> ") }
        .let { email.replace(it, " <email> ") }
        .let { quoted.replace(it, " <value> ") }
        .let { number.replace(it, " <number> ") }
        .replace(Regex("\\s+"), " ")
        .trim()
        .take(MAX_FAMILY_CHARS)

    fun sameTaskFamily(left: String, right: String): Boolean {
        val normalizedLeft = taskFamily(left)
        val normalizedRight = taskFamily(right)
        if (normalizedLeft == normalizedRight) return true
        if (normalizedLeft.isBlank() || normalizedRight.isBlank()) return false
        val leftTokens = similarityTokens(normalizedLeft)
        val rightTokens = similarityTokens(normalizedRight)
        if (leftTokens.isEmpty() || rightTokens.isEmpty()) return false
        val intersection = leftTokens.intersect(rightTokens).size.toDouble()
        val dice = 2.0 * intersection / (leftTokens.size + rightTokens.size)
        val smallerCoverage = intersection / minOf(leftTokens.size, rightTokens.size)
        return dice >= FAMILY_DICE_THRESHOLD ||
            (intersection >= MIN_SHARED_TOKENS && smallerCoverage >= FAMILY_COVERAGE_THRESHOLD)
    }

    fun explicitPreference(request: String): String? {
        val clean = request.trim().take(MAX_MEMORY_CHARS)
        if (clean.isBlank() || containsSensitiveData(clean)) return null
        val value = englishPreference.find(clean)?.groupValues?.getOrNull(1)
            ?: cjkPreference.find(clean)?.groupValues?.getOrNull(1)
            ?: return null
        return value.trim().takeIf { it.length in MIN_MEMORY_CHARS..MAX_MEMORY_CHARS }
    }

    fun correctionFeedback(request: String): String? {
        val clean = request.trim().take(MAX_MEMORY_CHARS)
        if (clean.isBlank() || containsSensitiveData(clean)) return null
        return clean.takeIf { englishCorrection.matches(it) || cjkCorrection.matches(it) }
    }

    internal fun repeatedFailureFamily(
        run: AgentRecordedRun,
        recentRuns: List<AgentRecordedRun>,
        minimumFailures: Int = 2
    ): String? {
        if (run.status != AgentRecordedRunStatus.FAILED ||
            minimumFailures < 1 ||
            containsSensitiveData(run.originalRequest)
        ) return null
        val family = taskFamily(run.originalRequest)
        if (family.isBlank()) return null
        val failures = recentRuns.count { candidate ->
            candidate.status == AgentRecordedRunStatus.FAILED &&
                sameTaskFamily(candidate.originalRequest, family)
        }
        return family.takeIf { failures >= minimumFailures }
    }

    fun containsSensitiveData(value: String): Boolean = secret.containsMatchIn(value) ||
        value.contains("-----BEGIN ", ignoreCase = true)

    fun safeTitle(request: String): String = taskFamily(request)
        .replace(Regex("<[^>]+>"), "item")
        .replace(Regex("\\s+"), " ")
        .trim()
        .take(48)
        .ifBlank { "Learned workflow" }

    fun stableKey(value: String): String = sha256(taskFamily(value)).take(24)

    internal fun hasUnboundTaskValue(value: Any?): Boolean = when (value) {
        is String -> value.isNotBlank() && value != "{{parameters.request}}"
        is Map<*, *> -> value.values.any(::hasUnboundTaskValue)
        is Iterable<*> -> value.any(::hasUnboundTaskValue)
        is Array<*> -> value.any(::hasUnboundTaskValue)
        else -> false
    }

    internal fun hasTrustedExecutionEvidence(call: AgentToolCallRecord): Boolean {
        if (call.status != AgentToolCallStatus.SUCCEEDED) return false
        if (call.toolName != AgentOnDeviceRuntimeTools.EXECUTE) return true
        val receipt = runCatching { JSONObject(call.resultJson).optJSONObject("execution_receipt") }
            .getOrNull() ?: return false
        val createdAt = receipt.optLong("created_at_millis")
        val completedAt = receipt.optLong("completed_at_millis")
        return receipt.optString("request_id").isNotBlank() &&
            receipt.optString("status") == AgentRuntimeReceiptStatus.COMPLETED.name.lowercase(Locale.ROOT) &&
            receipt.optInt("exit_code", Int.MIN_VALUE) == 0 &&
            SHA256_PATTERN.matches(receipt.optString("source_sha256")) &&
            SHA256_PATTERN.matches(receipt.optString("stdout_sha256")) &&
            SHA256_PATTERN.matches(receipt.optString("stderr_sha256")) &&
            createdAt > 0L && completedAt >= createdAt
    }

    internal fun runExecutionEvidenceTrusted(run: AgentRecordedRun): Boolean =
        run.toolCalls.filter { it.toolName == AgentOnDeviceRuntimeTools.EXECUTE }
            .all(::hasTrustedExecutionEvidence)

    private fun similarityTokens(value: String): Set<String> {
        val words = value.split(Regex("[^\\p{L}\\p{N}<>]+"))
            .filter { it.length >= 2 }
        val cjk = value.filter { it.code in 0x3400..0x9FFF }
        return (words + cjk.windowed(2)).toSet()
    }

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray())
        .joinToString("") { "%02x".format(it) }

    private const val FAMILY_DICE_THRESHOLD = 0.62
    private const val FAMILY_COVERAGE_THRESHOLD = 0.60
    private const val MIN_SHARED_TOKENS = 2
    private const val MAX_FAMILY_CHARS = 320
    private const val MAX_MEMORY_CHARS = 1_000
    private const val MIN_MEMORY_CHARS = 2
    private val SHA256_PATTERN = Regex("[0-9a-f]{64}")
}

class AgentLearningEngine(
    context: Context,
    private val memoryStore: AgentMemoryStore,
    private val skillRuntime: AgentSkillRuntime,
    private val skillCompiler: AgentConversationSkillCompiler
) {
    private val database = AgentEncryptedDatabase(context.applicationContext, STORAGE_NAME)

    @Synchronized
    fun observeCompletedRun(
        run: AgentRecordedRun,
        recentRuns: List<AgentRecordedRun>,
        privateMode: Boolean,
        memoryCaptureEnabled: Boolean
    ): AgentLearningOutcome {
        if (privateMode || run.status !in setOf(AgentRecordedRunStatus.COMPLETED, AgentRecordedRunStatus.FAILED)) {
            return AgentLearningOutcome()
        }
        if (run.status == AgentRecordedRunStatus.FAILED) {
            return AgentLearningOutcome(
                memories = if (memoryCaptureEnabled) learnRepeatedFailure(run, recentRuns) else emptyList()
            )
        }
        val memoryResults = buildList {
            if (memoryCaptureEnabled) {
                AgentLearningAnalyzer.explicitPreference(run.originalRequest)?.let { preference ->
                    add(memoryStore.remember(
                        AgentMemoryItem(
                            kind = AgentMemoryKind.PREFERENCE,
                            value = preference,
                            source = "automatic_learning",
                            key = "preference:${AgentLearningAnalyzer.stableKey(preference)}",
                            scope = AgentMemoryScope.GLOBAL,
                            confidence = 0.82,
                            evidenceCount = 1,
                            autoLearned = true,
                            lastConfirmedAtMillis = System.currentTimeMillis()
                        )
                    ))
                }
                val family = AgentLearningAnalyzer.taskFamily(run.originalRequest)
                val matchingSuccesses = recentRuns.count { candidate ->
                    candidate.status == AgentRecordedRunStatus.COMPLETED &&
                        candidate.toolCalls.any(AgentLearningAnalyzer::hasTrustedExecutionEvidence) &&
                        AgentLearningAnalyzer.runExecutionEvidenceTrusted(candidate) &&
                        AgentLearningAnalyzer.sameTaskFamily(candidate.originalRequest, family)
                }
                if (family.isNotBlank() && matchingSuccesses >= MIN_WORKFLOW_MEMORY_RUNS &&
                    !AgentLearningAnalyzer.containsSensitiveData(run.originalRequest)
                ) {
                    add(memoryStore.remember(
                        AgentMemoryItem(
                            kind = AgentMemoryKind.WORKFLOW,
                            value = "Successful workflow family: $family",
                            source = "automatic_learning",
                            key = "workflow:${AgentLearningAnalyzer.stableKey(family)}",
                            scope = AgentMemoryScope.GLOBAL,
                            confidence = 0.74,
                            evidenceCount = matchingSuccesses,
                            autoLearned = true,
                            expiresAtMillis = System.currentTimeMillis() + WORKFLOW_MEMORY_TTL_MILLIS
                        )
                    ))
                }
            }
        }
        val proposal = proposeSkill(run, recentRuns)
        return AgentLearningOutcome(memoryResults, listOfNotNull(proposal))
    }

    @Synchronized
    fun observeFeedback(run: AgentRecordedRun, recentRuns: List<AgentRecordedRun>): AgentLearningProposal? {
        if (run.activeSkillId.isBlank() || run.userFeedback.isEmpty() ||
            run.userFeedback.any(AgentLearningAnalyzer::containsSensitiveData)
        ) return null
        val base = skillRuntime.list()
            .filter { it.id == run.activeSkillId }
            .maxWithOrNull(compareBy<AgentSkillInstallation> { versionPart(it.version, 0) }
                .thenBy { versionPart(it.version, 1) }
                .thenBy { versionPart(it.version, 2) })
            ?: return null
        if (loadProposals().any { proposal ->
                proposal.kind == AgentLearningProposalKind.SKILL_UPGRADE &&
                    run.runId in proposal.evidenceRunIds &&
                    proposal.status == AgentLearningProposalStatus.PENDING
            }) return null
        val improvedRuns = recentRuns.filter { candidate ->
            candidate.activeSkillId == run.activeSkillId &&
                candidate.userFeedback.isNotEmpty() &&
                AgentLearningAnalyzer.runExecutionEvidenceTrusted(candidate)
        }.takeLast(MAX_EVIDENCE_RUNS).ifEmpty { listOf(run) }
        val manifest = runCatching { AgentSkillVersionManager(skillRuntime).buildUpgrade(base, improvedRuns) }
            .getOrNull() ?: return null
        val proposal = AgentLearningProposal(
            id = UUID.randomUUID().toString(),
            kind = AgentLearningProposalKind.SKILL_UPGRADE,
            title = manifest.title,
            taskFamily = AgentLearningAnalyzer.taskFamily(run.originalRequest),
            summary = "User correction is ready as a reviewed Skill upgrade",
            evidenceRunIds = improvedRuns.map { it.runId },
            manifestJson = AgentSkillManifestCodec.encode(manifest)
        )
        saveProposals((loadProposals() + proposal).takeLast(MAX_PROPOSALS))
        return proposal
    }

    @Synchronized
    fun proposals(status: AgentLearningProposalStatus? = null): List<AgentLearningProposal> =
        loadProposals().filter { status == null || it.status == status }
            .sortedByDescending { it.createdAtMillis }

    @Synchronized
    fun approve(proposalId: String): AgentSkillInstallation? {
        val proposals = loadProposals().toMutableList()
        val index = proposals.indexOfFirst { it.id == proposalId && it.status == AgentLearningProposalStatus.PENDING }
        if (index < 0) return null
        val manifest = AgentSkillManifestCodec.decode(proposals[index].manifestJson) ?: return null
        val installed = skillRuntime.install(manifest, enabled = true)
        proposals[index] = proposals[index].copy(
            status = AgentLearningProposalStatus.APPROVED,
            reviewedAtMillis = System.currentTimeMillis()
        )
        saveProposals(proposals)
        return installed
    }

    @Synchronized
    fun reject(proposalId: String): Boolean = review(proposalId, AgentLearningProposalStatus.REJECTED)

    @Synchronized
    fun clear() = database.clear()

    private fun proposeSkill(run: AgentRecordedRun, recentRuns: List<AgentRecordedRun>): AgentLearningProposal? {
        if (run.activeSkillId.isNotBlank() || AgentLearningAnalyzer.containsSensitiveData(run.originalRequest)) return null
        val familyRuns = recentRuns
            .filter { candidate ->
                candidate.status == AgentRecordedRunStatus.COMPLETED &&
                    candidate.activeSkillId.isBlank() &&
                    AgentLearningAnalyzer.runExecutionEvidenceTrusted(candidate) &&
                    AgentLearningAnalyzer.sameTaskFamily(candidate.originalRequest, run.originalRequest)
            }
            .distinctBy { it.runId }
            .sortedBy { it.completedAtMillis }
            .takeLast(MAX_EVIDENCE_RUNS)
        if (familyRuns.size < MIN_SUCCESSFUL_RUNS || familyRuns.map { it.originalRequest }.distinct().size < 2) return null
        val family = AgentLearningAnalyzer.taskFamily(run.originalRequest)
        if (loadProposals().any {
                it.taskFamily == family && it.status != AgentLearningProposalStatus.REJECTED
            }) return null
        val compiled = runCatching { skillCompiler.compile(familyRuns, AgentLearningAnalyzer.safeTitle(run.originalRequest)) }
            .getOrNull() ?: return null
        val reusableManifest = if (compiled.steps.any { step -> AgentLearningAnalyzer.hasUnboundTaskValue(step.input) }) {
            compiled.copy(
                nativeTools = setOf(AGENT_ORCHESTRATION_TOOL_ID),
                permissions = emptySet(),
                steps = listOf(
                    AgentSkillStep(
                        id = "step_1",
                        toolId = AGENT_ORCHESTRATION_TOOL_ID,
                        input = mapOf("request" to "{{parameters.request}}")
                    )
                )
            )
        } else {
            compiled
        }
        val reviewedManifest = reusableManifest.copy(
            source = "automatic_learning_proposal",
            author = "SignalASI Learning",
            autoInvoke = false,
            instructions = reusableManifest.instructions.take(MAX_INSTRUCTIONS_CHARS),
            renderSpec = emptyMap()
        )
        val proposal = AgentLearningProposal(
            id = UUID.randomUUID().toString(),
            kind = AgentLearningProposalKind.SKILL,
            title = reviewedManifest.title,
            taskFamily = family,
            summary = "Repeated successful workflow ready for review",
            evidenceRunIds = familyRuns.map { it.runId },
            manifestJson = AgentSkillManifestCodec.encode(reviewedManifest)
        )
        saveProposals((loadProposals() + proposal).takeLast(MAX_PROPOSALS))
        return proposal
    }

    private fun learnRepeatedFailure(
        run: AgentRecordedRun,
        recentRuns: List<AgentRecordedRun>
    ): List<AgentMemoryWriteResult> {
        val family = AgentLearningAnalyzer.repeatedFailureFamily(
            run,
            recentRuns,
            MIN_REPEATED_FAILURES
        ) ?: return emptyList()
        return listOf(
            memoryStore.remember(
                AgentMemoryItem(
                    kind = AgentMemoryKind.WORKFLOW,
                    value = "Do not repeat the unchanged failed workflow for: $family. Replan or change the execution resource before retrying.",
                    source = "automatic_failure_learning",
                    key = "failure:${AgentLearningAnalyzer.stableKey(family)}",
                    scope = AgentMemoryScope.GLOBAL,
                    confidence = 0.68,
                    evidenceCount = 1,
                    autoLearned = true,
                    expiresAtMillis = System.currentTimeMillis() + FAILURE_MEMORY_TTL_MILLIS
                )
            )
        )
    }

    private fun review(id: String, status: AgentLearningProposalStatus): Boolean {
        val proposals = loadProposals().toMutableList()
        val index = proposals.indexOfFirst { it.id == id && it.status == AgentLearningProposalStatus.PENDING }
        if (index < 0) return false
        proposals[index] = proposals[index].copy(status = status, reviewedAtMillis = System.currentTimeMillis())
        saveProposals(proposals)
        return true
    }

    private fun loadProposals(): List<AgentLearningProposal> = runCatching {
        val array = JSONArray(database.readString(KEY_PROPOSALS, "[]"))
        buildList {
            for (index in 0 until array.length()) {
                val json = array.optJSONObject(index) ?: continue
                val evidence = json.optJSONArray("evidence_run_ids") ?: JSONArray()
                add(AgentLearningProposal(
                    id = json.optString("id").ifBlank { UUID.randomUUID().toString() },
                    kind = enumValue(json.optString("kind"), AgentLearningProposalKind.SKILL),
                    title = json.optString("title"),
                    taskFamily = json.optString("task_family"),
                    summary = json.optString("summary"),
                    evidenceRunIds = buildList {
                        for (evidenceIndex in 0 until evidence.length()) {
                            evidence.optString(evidenceIndex).takeIf(String::isNotBlank)?.let(::add)
                        }
                    },
                    manifestJson = json.optString("manifest_json"),
                    status = enumValue(json.optString("status"), AgentLearningProposalStatus.PENDING),
                    createdAtMillis = json.optLong("created_at_millis", System.currentTimeMillis()),
                    reviewedAtMillis = json.optLong("reviewed_at_millis", 0L)
                ))
            }
        }
    }.getOrDefault(emptyList())

    private fun saveProposals(proposals: List<AgentLearningProposal>) {
        val array = JSONArray()
        proposals.forEach { proposal ->
            array.put(JSONObject()
                .put("id", proposal.id)
                .put("kind", proposal.kind.name)
                .put("title", proposal.title)
                .put("task_family", proposal.taskFamily)
                .put("summary", proposal.summary)
                .put("evidence_run_ids", JSONArray(proposal.evidenceRunIds))
                .put("manifest_json", proposal.manifestJson)
                .put("status", proposal.status.name)
                .put("created_at_millis", proposal.createdAtMillis)
                .put("reviewed_at_millis", proposal.reviewedAtMillis))
        }
        database.writeString(KEY_PROPOSALS, array.toString())
    }

    private inline fun <reified T : Enum<T>> enumValue(value: String, fallback: T): T =
        enumValues<T>().firstOrNull { it.name == value } ?: fallback

    private fun versionPart(version: String, index: Int): Int =
        version.split('.').getOrNull(index)?.toIntOrNull() ?: 0

    companion object {
        private const val STORAGE_NAME = "signalasi_agent_learning"
        private const val KEY_PROPOSALS = "proposals"
        private const val MIN_SUCCESSFUL_RUNS = 3
        private const val MIN_WORKFLOW_MEMORY_RUNS = 2
        private const val MIN_REPEATED_FAILURES = 2
        private const val MAX_EVIDENCE_RUNS = 12
        private const val MAX_PROPOSALS = 128
        private const val MAX_INSTRUCTIONS_CHARS = 24_000
        private const val WORKFLOW_MEMORY_TTL_MILLIS = 180L * 24L * 60L * 60L * 1_000L
        private const val FAILURE_MEMORY_TTL_MILLIS = 90L * 24L * 60L * 60L * 1_000L
    }
}
