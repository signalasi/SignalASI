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
    val longHorizonGoalId: String = "",
    val createdAtMillis: Long = System.currentTimeMillis(),
    val updatedAtMillis: Long = System.currentTimeMillis()
)

data class GlobalModelUnderstanding(
    val topic: String = "",
    val project: String = "",
    val relatedTopics: List<String> = emptyList(),
    val intent: String = "",
    val entities: Set<String> = emptySet(),
    val goals: List<String> = emptyList(),
    val tasks: List<String> = emptyList(),
    val decisions: List<String> = emptyList(),
    val preferences: List<String> = emptyList(),
    val risks: List<String> = emptyList(),
    val opportunities: List<String> = emptyList(),
    val researchQuestions: List<String> = emptyList(),
    val goalDependencies: List<GlobalGoalDependencyProposal> = emptyList(),
    val actions: List<GlobalAutonomousAction> = emptyList(),
    val userInsight: String = "",
    val goalState: GlobalGoalProgressState = GlobalGoalProgressState.ACTIVE,
    val progressSummary: String = "",
    val nextCheckHours: Int = 24,
    val confidence: Double = 0.0
) {
    val meaningful: Boolean
        get() = topic.isNotBlank() || project.isNotBlank() || relatedTopics.isNotEmpty() ||
            goals.isNotEmpty() || tasks.isNotEmpty() ||
            decisions.isNotEmpty() || preferences.isNotEmpty() || risks.isNotEmpty() ||
            opportunities.isNotEmpty() || researchQuestions.isNotEmpty() ||
            goalDependencies.isNotEmpty() ||
            actions.isNotEmpty() || userInsight.isNotBlank() || progressSummary.isNotBlank() ||
            goalState != GlobalGoalProgressState.ACTIVE
}

data class GlobalGoalDependencyProposal(
    val goal: String,
    val dependsOn: String
)

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

enum class GlobalActionEvidenceKind {
    DELEGATED_RESULT,
    LOCAL_RECEIPT,
    RESEARCH_LEDGER,
    ARTIFACT,
    USER_CONFIRMATION
}

enum class GlobalActionVerificationStatus {
    PENDING,
    SUPPORTED,
    VERIFIED,
    INSUFFICIENT,
    CONTESTED
}

data class GlobalActionEvidence(
    val id: String = UUID.randomUUID().toString(),
    val kind: GlobalActionEvidenceKind,
    val summary: String,
    val sourceRef: String = "",
    val confidence: Double = 0.5,
    val verified: Boolean = false,
    val createdAtMillis: Long = System.currentTimeMillis()
)

data class GlobalActionVerificationContract(
    val criteria: List<String> = emptyList(),
    val acceptedEvidenceKinds: Set<GlobalActionEvidenceKind> = emptySet(),
    val minimumEvidenceCount: Int = 1,
    val minimumConfidence: Double = 0.5,
    val requireVerifiedEvidence: Boolean = false
)

data class GlobalAutonomousAction(
    val id: String = UUID.randomUUID().toString(),
    val planKey: String = "",
    val dependencyKeys: Set<String> = emptySet(),
    val dependsOnActionIds: Set<String> = emptySet(),
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
    val verificationContract: GlobalActionVerificationContract = GlobalActionVerificationContract(),
    val evidence: List<GlobalActionEvidence> = emptyList(),
    val verificationStatus: GlobalActionVerificationStatus = GlobalActionVerificationStatus.PENDING,
    val lastError: String = "",
    val startedAtMillis: Long = 0L,
    val completedAtMillis: Long = 0L
) {
    val requiresConfirmation: Boolean get() = (externalEffect || !reversible) && !confirmationGranted
}

object GlobalAutonomousActionGraphPolicy {
    fun prepare(actions: List<GlobalAutonomousAction>): List<GlobalAutonomousAction> {
        if (actions.isEmpty()) return emptyList()
        val keyed = actions.mapIndexed { index, action ->
            action.copy(
                planKey = action.planKey.ifBlank {
                    "step-${index + 1}-${GlobalAgentText.stableKey(action.kind.name, action.goal).take(8)}"
                }.take(80),
                verificationContract = if (action.verificationContract.criteria.isEmpty()) {
                    GlobalActionVerificationPolicy.defaultContract(action)
                } else action.verificationContract
            )
        }.distinctBy(GlobalAutonomousAction::planKey)
        val byKey = keyed.associateBy(GlobalAutonomousAction::planKey)
        val resolved = keyed.map { action ->
            val unknown = action.dependencyKeys.filterNot(byKey::containsKey)
            if (unknown.isNotEmpty()) action.copy(
                status = GlobalAutonomousActionStatus.SKIPPED,
                lastError = "Unknown prerequisite step: ${unknown.joinToString(", ").take(300)}"
            ) else action.copy(
                dependsOnActionIds = action.dependencyKeys.mapNotNull { byKey[it]?.id }.toSet()
            )
        }
        return if (isAcyclic(resolved)) resolved else resolved.map { action ->
            if (action.dependsOnActionIds.isNotEmpty()) action.copy(
                status = GlobalAutonomousActionStatus.SKIPPED,
                lastError = "The proposed step dependencies contain a cycle"
            ) else action
        }
    }

    fun resolveAgainst(
        existing: List<GlobalAutonomousAction>,
        proposed: List<GlobalAutonomousAction>
    ): List<GlobalAutonomousAction> {
        val keyed = (existing + proposed).mapIndexed { index, action ->
            if (action.planKey.isNotBlank()) action else action.copy(
                planKey = "step-${index + 1}-${GlobalAgentText.stableKey(action.kind.name, action.goal).take(8)}"
            )
        }
        val byKey = keyed.associateBy(GlobalAutonomousAction::planKey)
        val resolved = proposed.map { action ->
            val source = keyed.first { it.id == action.id }
            val unknown = source.dependencyKeys.filterNot(byKey::containsKey)
            if (unknown.isNotEmpty()) source.copy(
                status = GlobalAutonomousActionStatus.SKIPPED,
                lastError = "Unknown prerequisite step: ${unknown.joinToString(", ").take(300)}"
            ) else source.copy(
                dependsOnActionIds = source.dependencyKeys.mapNotNull { byKey[it]?.id }.toSet(),
                verificationContract = if (source.verificationContract.criteria.isEmpty()) {
                    GlobalActionVerificationPolicy.defaultContract(source)
                } else source.verificationContract
            )
        }
        return if (isAcyclic(existing + resolved)) resolved else resolved.map { action ->
            if (action.dependsOnActionIds.isNotEmpty()) action.copy(
                status = GlobalAutonomousActionStatus.SKIPPED,
                lastError = "The proposed step dependencies contain a cycle"
            ) else action
        }
    }

    fun reconcile(actions: List<GlobalAutonomousAction>): List<GlobalAutonomousAction> {
        val byId = actions.associateBy(GlobalAutonomousAction::id)
        return actions.map { action ->
            if (action.status !in setOf(
                    GlobalAutonomousActionStatus.PENDING,
                    GlobalAutonomousActionStatus.WAITING_CONFIRMATION
                )
            ) return@map action
            val failedDependencies = action.dependsOnActionIds.mapNotNull(byId::get).filter {
                it.status in setOf(GlobalAutonomousActionStatus.FAILED, GlobalAutonomousActionStatus.SKIPPED)
            }
            val missingDependencies = action.dependsOnActionIds.filterNot(byId::containsKey)
            if (failedDependencies.isEmpty() && missingDependencies.isEmpty()) action else action.copy(
                status = GlobalAutonomousActionStatus.SKIPPED,
                lastError = "A prerequisite step did not complete",
                completedAtMillis = maxOf(action.completedAtMillis, System.currentTimeMillis())
            )
        }
    }

    fun readyActions(actions: List<GlobalAutonomousAction>): List<GlobalAutonomousAction> {
        val byId = actions.associateBy(GlobalAutonomousAction::id)
        return actions.filter { action ->
            action.status == GlobalAutonomousActionStatus.PENDING && action.dependsOnActionIds.all {
                byId[it]?.status == GlobalAutonomousActionStatus.COMPLETED
            }
        }.sortedByDescending(GlobalAutonomousAction::priority)
    }

    fun reserveNext(
        actions: List<GlobalAutonomousAction>,
        nowMillis: Long,
        leaseExpiresAtMillis: Long
    ): GlobalActionReservation? {
        val action = readyActions(actions).firstOrNull() ?: return null
        return GlobalActionReservation(
            actionId = action.id,
            actions = actions.map {
                if (it.id == action.id) it.copy(
                    status = GlobalAutonomousActionStatus.RUNNING,
                    attemptCount = it.attemptCount + 1,
                    leaseExpiresAtMillis = leaseExpiresAtMillis,
                    lastError = "",
                    startedAtMillis = nowMillis
                ) else it
            }
        )
    }

    private fun isAcyclic(actions: List<GlobalAutonomousAction>): Boolean {
        val byId = actions.associateBy(GlobalAutonomousAction::id)
        val visiting = mutableSetOf<String>()
        val visited = mutableSetOf<String>()
        fun visit(id: String): Boolean {
            if (id in visited) return true
            if (!visiting.add(id)) return false
            val valid = byId[id]?.dependsOnActionIds.orEmpty().all(::visit)
            visiting.remove(id)
            if (valid) visited += id
            return valid
        }
        return actions.all { visit(it.id) }
    }
}

data class GlobalActionReservation(
    val actionId: String,
    val actions: List<GlobalAutonomousAction>
)

object GlobalActionVerificationPolicy {
    fun defaultContract(action: GlobalAutonomousAction): GlobalActionVerificationContract {
        val criteria = listOf(action.expectedResult.ifBlank { action.goal }.take(600))
        return when (action.kind) {
            GlobalAutonomousActionKind.ANALYZE,
            GlobalAutonomousActionKind.DRAFT -> GlobalActionVerificationContract(
                criteria = criteria,
                acceptedEvidenceKinds = setOf(
                    GlobalActionEvidenceKind.DELEGATED_RESULT,
                    GlobalActionEvidenceKind.ARTIFACT
                ),
                minimumConfidence = 0.50
            )
            GlobalAutonomousActionKind.READ_ONLY_CHECK -> GlobalActionVerificationContract(
                criteria = criteria,
                acceptedEvidenceKinds = setOf(
                    GlobalActionEvidenceKind.DELEGATED_RESULT,
                    GlobalActionEvidenceKind.LOCAL_RECEIPT,
                    GlobalActionEvidenceKind.RESEARCH_LEDGER
                ),
                minimumConfidence = 0.58
            )
            GlobalAutonomousActionKind.CREATE_TOPIC,
            GlobalAutonomousActionKind.START_MONITOR -> GlobalActionVerificationContract(
                criteria = criteria,
                acceptedEvidenceKinds = setOf(GlobalActionEvidenceKind.LOCAL_RECEIPT),
                minimumConfidence = 0.90,
                requireVerifiedEvidence = true
            )
            GlobalAutonomousActionKind.START_RESEARCH -> GlobalActionVerificationContract(
                criteria = criteria,
                acceptedEvidenceKinds = setOf(GlobalActionEvidenceKind.RESEARCH_LEDGER),
                minimumConfidence = 0.56,
                requireVerifiedEvidence = true
            )
        }
    }

    fun evaluate(
        contract: GlobalActionVerificationContract,
        evidence: List<GlobalActionEvidence>
    ): GlobalActionVerificationStatus {
        if (contract.criteria.isEmpty()) return GlobalActionVerificationStatus.INSUFFICIENT
        val accepted = evidence.filter { item ->
            (contract.acceptedEvidenceKinds.isEmpty() || item.kind in contract.acceptedEvidenceKinds) &&
                item.confidence >= contract.minimumConfidence
        }
        if (accepted.size < contract.minimumEvidenceCount.coerceAtLeast(1)) {
            return GlobalActionVerificationStatus.INSUFFICIENT
        }
        if (contract.requireVerifiedEvidence && accepted.none(GlobalActionEvidence::verified)) {
            return GlobalActionVerificationStatus.INSUFFICIENT
        }
        return if (accepted.any(GlobalActionEvidence::verified)) {
            GlobalActionVerificationStatus.VERIFIED
        } else GlobalActionVerificationStatus.SUPPORTED
    }
}

enum class GlobalAutonomousRunStatus {
    QUEUED,
    RUNNING,
    REPLANNING,
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
    val causalEventIds: Set<String> = emptySet(),
    val status: GlobalAutonomousRunStatus = GlobalAutonomousRunStatus.QUEUED,
    val revision: Int = 1,
    val replanCount: Int = 0,
    val outcomeSummary: String = "",
    val review: GlobalAutonomousRunReview = GlobalAutonomousRunReview(),
    val attemptCount: Int = 0,
    val nextAttemptAtMillis: Long = 0L,
    val leaseExpiresAtMillis: Long = 0L,
    val lastError: String = "",
    val createdAtMillis: Long = System.currentTimeMillis(),
    val updatedAtMillis: Long = System.currentTimeMillis()
) {
    fun activeAction(): GlobalAutonomousAction? = actions.firstOrNull {
        it.status == GlobalAutonomousActionStatus.RUNNING
    }

    fun completedActions(): List<GlobalAutonomousAction> = actions.filter {
        it.status == GlobalAutonomousActionStatus.COMPLETED
    }
}

data class GlobalAutonomousWorkClaim(
    val run: GlobalAutonomousRun,
    val actionId: String = "",
    val planReview: Boolean = false
)

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
            project = result.project.ifBlank { baseline.project }.take(160),
            relatedTopics = (baseline.relatedTopics + result.relatedTopics)
                .map { it.replace(Regex("\\s+"), " ").trim().take(160) }
                .filter(String::isNotBlank)
                .distinctBy(GlobalAgentText::normalize)
                .take(16)
                .toSet(),
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
                    planKey = clean(item.optString("key"), 80),
                    dependencyKeys = strings(item.optJSONArray("depends_on"), 8, 80).toSet(),
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
        val goalDependencies = buildList {
            val array = json.optJSONArray("goal_dependencies") ?: JSONArray()
            for (index in 0 until minOf(array.length(), MAX_GOAL_DEPENDENCIES)) {
                val item = array.optJSONObject(index) ?: continue
                val goal = clean(item.optString("goal"), 1_000)
                val dependsOn = clean(item.optString("depends_on"), 1_000)
                if (goal.isNotBlank() && dependsOn.isNotBlank() &&
                    GlobalAgentText.normalize(goal) != GlobalAgentText.normalize(dependsOn)
                ) add(GlobalGoalDependencyProposal(goal, dependsOn))
            }
        }.distinctBy { GlobalAgentText.stableKey(it.goal, it.dependsOn) }
        return GlobalModelUnderstanding(
            topic = clean(json.optString("topic"), 120),
            project = clean(json.optString("project"), 160),
            relatedTopics = strings(json.optJSONArray("related_topics"), 16, 160),
            intent = clean(json.optString("intent"), 80),
            entities = strings(json.optJSONArray("entities"), 32, 120).toSet(),
            goals = strings(json.optJSONArray("goals"), 8, 1_000),
            tasks = strings(json.optJSONArray("tasks"), 10, 1_000),
            decisions = strings(json.optJSONArray("decisions"), 8, 1_000),
            preferences = strings(json.optJSONArray("preferences"), 6, 1_000),
            risks = strings(json.optJSONArray("risks"), 8, 1_000),
            opportunities = strings(json.optJSONArray("opportunities"), 8, 1_000),
            researchQuestions = strings(json.optJSONArray("research_questions"), 6, 1_000),
            goalDependencies = goalDependencies,
            actions = GlobalAutonomousActionGraphPolicy.prepare(
                actions.distinctBy { GlobalAgentText.stableKey(it.kind.name, it.goal) }
            ),
            userInsight = clean(json.optString("user_insight"), 2_000),
            goalState = enumValue(json.optString("goal_state"), GlobalGoalProgressState.ACTIVE),
            progressSummary = clean(json.optString("progress_summary"), 2_000),
            nextCheckHours = json.optInt("next_check_hours", 24).coerceIn(1, 24 * 30),
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

    private inline fun <reified T : Enum<T>> enumValue(value: String, fallback: T): T {
        val normalized = value.trim().uppercase(Locale.ROOT).replace('-', '_').replace(' ', '_')
        return enumValues<T>().firstOrNull { it.name == normalized } ?: fallback
    }

    private const val MAX_ACTIONS = 6
    private const val MAX_GOAL_DEPENDENCIES = 12
}

object GlobalAutonomousRunPlanner {
    fun plan(task: GlobalCognitionTask): GlobalAutonomousRun? {
        val result = task.result
        if (task.longHorizonGoalId.isNotBlank() && result.goalState in setOf(
                GlobalGoalProgressState.COMPLETED,
                GlobalGoalProgressState.PAUSED
            )
        ) return null
        val actions = GlobalAutonomousActionGraphPolicy.prepare(result.actions)
            .sortedByDescending(GlobalAutonomousAction::priority)
            .distinctBy { GlobalAgentText.stableKey(it.kind.name, it.goal) }
            .take(MAX_ACTIONS)
            .map { action ->
                if (action.requiresConfirmation) {
                    action.copy(status = GlobalAutonomousActionStatus.WAITING_CONFIRMATION)
                } else action
            }
        if (actions.isEmpty()) return null
        val status = GlobalAutonomousRunPolicy.terminalStatus(actions) ?: if (
            actions.all { it.status == GlobalAutonomousActionStatus.WAITING_CONFIRMATION }
        ) GlobalAutonomousRunStatus.WAITING_CONFIRMATION else GlobalAutonomousRunStatus.QUEUED
        return GlobalAutonomousRun(
            sourceCognitionTaskId = task.id,
            sourceEventId = task.sourceEvent.id,
            causalEventIds = task.sourceEvent.evidenceRoots(),
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
        val recoveredReview = GlobalAutonomousReplanPolicy.recoverIfStale(run, nowMillis)
        if (recoveredReview != run) return recoveredReview
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
        if (actions == run.actions) return run
        val activeLease = actions.asSequence()
            .filter { it.status == GlobalAutonomousActionStatus.RUNNING }
            .map(GlobalAutonomousAction::leaseExpiresAtMillis)
            .filter { it > 0L }
            .maxOrNull() ?: 0L
        val terminal = terminalStatus(actions)
        return run.copy(
            actions = actions,
            status = terminal ?: if (actions.any { it.status == GlobalAutonomousActionStatus.PENDING }) {
                GlobalAutonomousRunStatus.WAITING_FOR_RESOURCE
            } else GlobalAutonomousRunStatus.RUNNING,
            nextAttemptAtMillis = nowMillis,
            leaseExpiresAtMillis = activeLease,
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

    fun completionSupported(actions: List<GlobalAutonomousAction>): Boolean {
        val completed = actions.filter { it.status == GlobalAutonomousActionStatus.COMPLETED }
        return completed.isNotEmpty() && completed.all {
            it.verificationStatus in setOf(
                GlobalActionVerificationStatus.SUPPORTED,
                GlobalActionVerificationStatus.VERIFIED
            )
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

    fun claimAutonomousWork(nowMillis: Long = System.currentTimeMillis()): GlobalAutonomousWorkClaim? = synchronized(STORE_LOCK) {
        val runs = loadAutonomousRuns().map { GlobalAutonomousRunPolicy.recoverIfStale(it, nowMillis) }.toMutableList()
        val index = runs.indexOfFirst { run ->
            val reviewReady = run.status == GlobalAutonomousRunStatus.REPLANNING &&
                run.review.status in setOf(
                    GlobalRunReviewStatus.PENDING,
                    GlobalRunReviewStatus.WAITING_FOR_RESOURCE
                ) && run.review.nextAttemptAtMillis <= nowMillis
            val actionReady = run.status in setOf(
                GlobalAutonomousRunStatus.QUEUED,
                GlobalAutonomousRunStatus.WAITING_FOR_RESOURCE,
                GlobalAutonomousRunStatus.RUNNING
            ) && run.nextAttemptAtMillis <= nowMillis &&
                GlobalAutonomousActionGraphPolicy.readyActions(run.actions).isNotEmpty()
            reviewReady || actionReady
        }
        if (index < 0) {
            saveAutonomousRuns(runs)
            return@synchronized null
        }
        val source = runs[index]
        val lease = nowMillis + GlobalAutonomousRunPolicy.LEASE_MILLIS
        val reviewReady = source.status == GlobalAutonomousRunStatus.REPLANNING &&
            source.review.status in setOf(
                GlobalRunReviewStatus.PENDING,
                GlobalRunReviewStatus.WAITING_FOR_RESOURCE
            ) && source.review.nextAttemptAtMillis <= nowMillis
        val reservation = if (reviewReady) null else {
            GlobalAutonomousActionGraphPolicy.reserveNext(source.actions, nowMillis, lease)
        }
        val claimed = if (reviewReady) {
            source.copy(
                status = GlobalAutonomousRunStatus.REPLANNING,
                review = source.review.copy(
                    status = GlobalRunReviewStatus.RUNNING,
                    attemptCount = source.review.attemptCount + 1,
                    leaseExpiresAtMillis = lease,
                    lastError = "",
                    updatedAtMillis = nowMillis
                ),
                attemptCount = source.attemptCount + 1,
                leaseExpiresAtMillis = lease,
                updatedAtMillis = nowMillis
            )
        } else {
            source.copy(
                status = GlobalAutonomousRunStatus.RUNNING,
                actions = reservation?.actions ?: source.actions,
                attemptCount = source.attemptCount + 1,
                leaseExpiresAtMillis = maxOf(source.leaseExpiresAtMillis, lease),
                updatedAtMillis = nowMillis
            )
        }
        runs[index] = claimed
        saveAutonomousRuns(runs)
        GlobalAutonomousWorkClaim(
            run = claimed,
            actionId = reservation?.actionId.orEmpty(),
            planReview = reviewReady
        )
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
        .put("long_horizon_goal_id", task.longHorizonGoalId)
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
            longHorizonGoalId = json.optString("long_horizon_goal_id"),
            createdAtMillis = json.optLong("created_at_millis", sourceEvent.timestampMillis),
            updatedAtMillis = json.optLong("updated_at_millis", sourceEvent.timestampMillis)
        )
    }

    private fun encodeAutonomousRun(run: GlobalAutonomousRun): JSONObject = JSONObject()
        .put("id", run.id)
        .put("source_cognition_task_id", run.sourceCognitionTaskId)
        .put("source_event_id", run.sourceEventId)
        .put("causal_event_ids", JSONArray(run.causalEventIds.toList()))
        .put("source_conversation_id", run.sourceConversationId)
        .put("topic", run.topic)
        .put("goal", run.goal.take(2_000))
        .put("actions", JSONArray().apply { run.actions.forEach { put(encodeAction(it)) } })
        .put("status", run.status.name)
        .put("revision", run.revision)
        .put("replan_count", run.replanCount)
        .put("outcome_summary", run.outcomeSummary)
        .put("review", encodeReview(run.review))
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
            causalEventIds = strings(json.optJSONArray("causal_event_ids")).toSet()
                .ifEmpty { setOf(sourceEventId) },
            sourceConversationId = json.optString("source_conversation_id"),
            topic = json.optString("topic").take(160),
            goal = json.optString("goal").take(2_000),
            actions = buildList {
                val array = json.optJSONArray("actions") ?: JSONArray()
                for (index in 0 until array.length()) decodeAction(array.optJSONObject(index))?.let(::add)
            }.take(8),
            status = enumValue(json.optString("status"), GlobalAutonomousRunStatus.QUEUED),
            revision = json.optInt("revision", 1).coerceAtLeast(1),
            replanCount = json.optInt("replan_count").coerceAtLeast(0),
            outcomeSummary = json.optString("outcome_summary").take(2_000),
            review = decodeReview(json.optJSONObject("review")),
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
        .put("project", result.project)
        .put("related_topics", JSONArray(result.relatedTopics))
        .put("intent", result.intent)
        .put("entities", JSONArray(result.entities.toList()))
        .put("goals", JSONArray(result.goals))
        .put("tasks", JSONArray(result.tasks))
        .put("decisions", JSONArray(result.decisions))
        .put("preferences", JSONArray(result.preferences))
        .put("risks", JSONArray(result.risks))
        .put("opportunities", JSONArray(result.opportunities))
        .put("research_questions", JSONArray(result.researchQuestions))
        .put("goal_dependencies", JSONArray().apply {
            result.goalDependencies.forEach { dependency ->
                put(JSONObject()
                    .put("goal", dependency.goal)
                    .put("depends_on", dependency.dependsOn))
            }
        })
        .put("actions", JSONArray().apply { result.actions.forEach { put(encodeAction(it)) } })
        .put("user_insight", result.userInsight)
        .put("goal_state", result.goalState.name)
        .put("progress_summary", result.progressSummary)
        .put("next_check_hours", result.nextCheckHours)
        .put("confidence", result.confidence)

    private fun decodeModelUnderstanding(json: JSONObject?): GlobalModelUnderstanding {
        if (json == null) return GlobalModelUnderstanding()
        return GlobalModelUnderstanding(
            topic = json.optString("topic").take(120),
            project = json.optString("project").take(160),
            relatedTopics = strings(json.optJSONArray("related_topics")).take(16),
            intent = json.optString("intent").take(80),
            entities = strings(json.optJSONArray("entities")).take(32).toSet(),
            goals = strings(json.optJSONArray("goals")).take(8),
            tasks = strings(json.optJSONArray("tasks")).take(10),
            decisions = strings(json.optJSONArray("decisions")).take(8),
            preferences = strings(json.optJSONArray("preferences")).take(6),
            risks = strings(json.optJSONArray("risks")).take(8),
            opportunities = strings(json.optJSONArray("opportunities")).take(8),
            researchQuestions = strings(json.optJSONArray("research_questions")).take(6),
            goalDependencies = buildList {
                val array = json.optJSONArray("goal_dependencies") ?: JSONArray()
                for (index in 0 until array.length()) {
                    val item = array.optJSONObject(index) ?: continue
                    val goal = item.optString("goal").take(1_000)
                    val dependsOn = item.optString("depends_on").take(1_000)
                    if (goal.isNotBlank() && dependsOn.isNotBlank()) {
                        add(GlobalGoalDependencyProposal(goal, dependsOn))
                    }
                }
            }.take(12),
            actions = buildList {
                val array = json.optJSONArray("actions") ?: JSONArray()
                for (index in 0 until array.length()) decodeAction(array.optJSONObject(index))?.let(::add)
            }.take(6),
            userInsight = json.optString("user_insight").take(2_000),
            goalState = enumValue(json.optString("goal_state"), GlobalGoalProgressState.ACTIVE),
            progressSummary = json.optString("progress_summary").take(2_000),
            nextCheckHours = json.optInt("next_check_hours", 24).coerceIn(1, 24 * 30),
            confidence = json.optDouble("confidence", 0.0).coerceIn(0.0, 1.0)
        )
    }

    private fun encodeReview(review: GlobalAutonomousRunReview): JSONObject = JSONObject()
        .put("status", review.status.name)
        .put("reason", review.reason)
        .put("resource_id", review.resourceId)
        .put("attempted_resource_ids", JSONArray(review.attemptedResourceIds))
        .put("source_message_id", review.sourceMessageId)
        .put("attempt_count", review.attemptCount)
        .put("next_attempt_at_millis", review.nextAttemptAtMillis)
        .put("lease_expires_at_millis", review.leaseExpiresAtMillis)
        .put("last_error", review.lastError)
        .put("decision", encodeReplanDecision(review.decision))
        .put("created_at_millis", review.createdAtMillis)
        .put("updated_at_millis", review.updatedAtMillis)

    private fun decodeReview(json: JSONObject?): GlobalAutonomousRunReview {
        if (json == null) return GlobalAutonomousRunReview()
        return GlobalAutonomousRunReview(
            status = enumValue(json.optString("status"), GlobalRunReviewStatus.NONE),
            reason = json.optString("reason").take(600),
            resourceId = json.optString("resource_id"),
            attemptedResourceIds = strings(json.optJSONArray("attempted_resource_ids")),
            sourceMessageId = json.optLong("source_message_id"),
            attemptCount = json.optInt("attempt_count").coerceAtLeast(0),
            nextAttemptAtMillis = json.optLong("next_attempt_at_millis"),
            leaseExpiresAtMillis = json.optLong("lease_expires_at_millis"),
            lastError = json.optString("last_error").take(600),
            decision = decodeReplanDecision(json.optJSONObject("decision")),
            createdAtMillis = json.optLong("created_at_millis"),
            updatedAtMillis = json.optLong("updated_at_millis")
        )
    }

    private fun encodeReplanDecision(decision: GlobalRunReplanDecision): JSONObject = JSONObject()
        .put("goal_state", decision.goalState.name)
        .put("summary", decision.summary)
        .put("cancel_action_ids", JSONArray(decision.cancelActionIds.toList()))
        .put("actions", JSONArray().apply { decision.actions.forEach { put(encodeAction(it)) } })
        .put("next_check_hours", decision.nextCheckHours)
        .put("confidence", decision.confidence)

    private fun decodeReplanDecision(json: JSONObject?): GlobalRunReplanDecision {
        if (json == null) return GlobalRunReplanDecision()
        return GlobalRunReplanDecision(
            goalState = enumValue(json.optString("goal_state"), GlobalGoalProgressState.ACTIVE),
            summary = json.optString("summary").take(2_000),
            cancelActionIds = strings(json.optJSONArray("cancel_action_ids")).take(12).toSet(),
            actions = buildList {
                val array = json.optJSONArray("actions") ?: JSONArray()
                for (index in 0 until array.length()) decodeAction(array.optJSONObject(index))?.let(::add)
            }.take(6),
            nextCheckHours = json.optInt("next_check_hours", 24).coerceIn(1, 24 * 30),
            confidence = json.optDouble("confidence", 0.0).coerceIn(0.0, 1.0)
        )
    }

    private fun encodeAction(action: GlobalAutonomousAction): JSONObject = JSONObject()
        .put("id", action.id)
        .put("plan_key", action.planKey)
        .put("dependency_keys", JSONArray(action.dependencyKeys.toList()))
        .put("depends_on_action_ids", JSONArray(action.dependsOnActionIds.toList()))
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
        .put("verification_contract", encodeVerificationContract(action.verificationContract))
        .put("evidence", JSONArray().apply { action.evidence.forEach { put(encodeActionEvidence(it)) } })
        .put("verification_status", action.verificationStatus.name)
        .put("last_error", action.lastError.take(600))
        .put("started_at_millis", action.startedAtMillis)
        .put("completed_at_millis", action.completedAtMillis)

    private fun decodeAction(json: JSONObject?): GlobalAutonomousAction? {
        if (json == null) return null
        val goal = json.optString("goal").take(1_000)
        if (goal.isBlank()) return null
        return GlobalAutonomousAction(
            id = json.optString("id").ifBlank { UUID.randomUUID().toString() },
            planKey = json.optString("plan_key").take(80),
            dependencyKeys = strings(json.optJSONArray("dependency_keys")).take(8).toSet(),
            dependsOnActionIds = strings(json.optJSONArray("depends_on_action_ids")).take(8).toSet(),
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
            verificationContract = decodeVerificationContract(json.optJSONObject("verification_contract")),
            evidence = buildList {
                val array = json.optJSONArray("evidence") ?: JSONArray()
                for (index in 0 until array.length()) decodeActionEvidence(array.optJSONObject(index))?.let(::add)
            }.take(24),
            verificationStatus = enumValue(
                json.optString("verification_status"),
                GlobalActionVerificationStatus.PENDING
            ),
            lastError = json.optString("last_error").take(600),
            startedAtMillis = json.optLong("started_at_millis"),
            completedAtMillis = json.optLong("completed_at_millis")
        )
    }

    private fun encodeVerificationContract(contract: GlobalActionVerificationContract): JSONObject = JSONObject()
        .put("criteria", JSONArray(contract.criteria))
        .put("accepted_evidence_kinds", JSONArray(contract.acceptedEvidenceKinds.map(Enum<*>::name)))
        .put("minimum_evidence_count", contract.minimumEvidenceCount)
        .put("minimum_confidence", contract.minimumConfidence)
        .put("require_verified_evidence", contract.requireVerifiedEvidence)

    private fun decodeVerificationContract(json: JSONObject?): GlobalActionVerificationContract {
        if (json == null) return GlobalActionVerificationContract()
        return GlobalActionVerificationContract(
            criteria = strings(json.optJSONArray("criteria")).take(8),
            acceptedEvidenceKinds = strings(json.optJSONArray("accepted_evidence_kinds"))
                .mapNotNull { value -> enumValues<GlobalActionEvidenceKind>().firstOrNull { it.name == value } }
                .toSet(),
            minimumEvidenceCount = json.optInt("minimum_evidence_count", 1).coerceIn(1, 8),
            minimumConfidence = json.optDouble("minimum_confidence", 0.5).coerceIn(0.0, 1.0),
            requireVerifiedEvidence = json.optBoolean("require_verified_evidence")
        )
    }

    private fun encodeActionEvidence(evidence: GlobalActionEvidence): JSONObject = JSONObject()
        .put("id", evidence.id)
        .put("kind", evidence.kind.name)
        .put("summary", evidence.summary)
        .put("source_ref", evidence.sourceRef)
        .put("confidence", evidence.confidence)
        .put("verified", evidence.verified)
        .put("created_at_millis", evidence.createdAtMillis)

    private fun decodeActionEvidence(json: JSONObject?): GlobalActionEvidence? {
        if (json == null) return null
        val summary = json.optString("summary").take(2_000)
        if (summary.isBlank()) return null
        return GlobalActionEvidence(
            id = json.optString("id").ifBlank { UUID.randomUUID().toString() },
            kind = enumValue(json.optString("kind"), GlobalActionEvidenceKind.DELEGATED_RESULT),
            summary = summary,
            sourceRef = json.optString("source_ref").take(1_000),
            confidence = json.optDouble("confidence", 0.5).coerceIn(0.0, 1.0),
            verified = json.optBoolean("verified"),
            createdAtMillis = json.optLong("created_at_millis")
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
        .put("causal_event_ids", JSONArray(event.causalEventIds.toList()))
        .put("retracted_event_ids", JSONArray(event.retractedEventIds.toList()))

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
            metadata = stringMap(json.optJSONObject("metadata")),
            causalEventIds = strings(json.optJSONArray("causal_event_ids")).toSet(),
            retractedEventIds = strings(json.optJSONArray("retracted_event_ids")).toSet()
        )
    }

    private fun encodeUnderstanding(value: GlobalUnderstanding): JSONObject = JSONObject()
        .put("event_id", value.eventId)
        .put("topic", value.topic)
        .put("project", value.project)
        .put("related_topics", JSONArray(value.relatedTopics.toList()))
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
            project = json.optString("project").take(160),
            relatedTopics = strings(json.optJSONArray("related_topics")).take(16).toSet(),
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
