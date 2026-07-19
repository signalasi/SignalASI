package com.signalasi.chat

import java.net.URI
import java.util.Locale
import java.util.UUID
import kotlin.math.max

enum class GlobalResearchPlanPhase {
    UNPLANNED,
    COLLECTING,
    SYNTHESIS_PENDING,
    SYNTHESIZING,
    COMPLETED
}

enum class GlobalResearchUnitStatus { PENDING, RUNNING, COMPLETED, FAILED }

enum class GlobalResearchUnitPurpose {
    CURRENT_FACTS,
    PRIMARY_EVIDENCE,
    ALTERNATIVES,
    RISKS,
    USER_IMPACT,
    CHANGE_MONITOR,
    CORROBORATION,
    PROACTIVE_INFERENCE
}

data class GlobalResearchUnit(
    val id: String = UUID.randomUUID().toString(),
    val purpose: GlobalResearchUnitPurpose,
    val question: String,
    val sourceFocus: String,
    val status: GlobalResearchUnitStatus = GlobalResearchUnitStatus.PENDING,
    val resourceId: String = "",
    val attemptedResourceIds: List<String> = emptyList(),
    val sourceMessageId: Long = 0L,
    val attemptCount: Int = 0,
    val leaseExpiresAtMillis: Long = 0L,
    val result: String = "",
    val evidenceUris: List<String> = emptyList(),
    val lastError: String = "",
    val startedAtMillis: Long = 0L,
    val completedAtMillis: Long = 0L
)

data class GlobalResearchPlan(
    val id: String = "",
    val phase: GlobalResearchPlanPhase = GlobalResearchPlanPhase.UNPLANNED,
    val units: List<GlobalResearchUnit> = emptyList(),
    val synthesisResourceId: String = "",
    val synthesisSourceMessageId: Long = 0L,
    val synthesisLeaseExpiresAtMillis: Long = 0L,
    val synthesisAttemptCount: Int = 0,
    val createdAtMillis: Long = 0L,
    val updatedAtMillis: Long = 0L
) {
    fun completedUnits(): List<GlobalResearchUnit> = units.filter { it.status == GlobalResearchUnitStatus.COMPLETED }
    fun runningUnits(): List<GlobalResearchUnit> = units.filter { it.status == GlobalResearchUnitStatus.RUNNING }
    fun pendingUnits(): List<GlobalResearchUnit> = units.filter { it.status == GlobalResearchUnitStatus.PENDING }
    fun readyForSynthesis(): Boolean = units.isNotEmpty() && runningUnits().isEmpty() && pendingUnits().isEmpty() &&
        completedUnits().isNotEmpty()
}

enum class GlobalEvidenceSourceKind {
    OFFICIAL,
    GOVERNMENT,
    PAPER,
    CODE_REPOSITORY,
    NEWS,
    COMMUNITY,
    UNKNOWN
}

data class GlobalEvidenceSource(
    val uri: String,
    val kind: GlobalEvidenceSourceKind,
    val qualityScore: Double,
    val contributingUnitIds: Set<String> = emptySet(),
    val retrievedAtMillis: Long = System.currentTimeMillis()
)

data class GlobalEvidenceClaim(
    val id: String = UUID.randomUUID().toString(),
    val statement: String,
    val sourceUris: Set<String> = emptySet(),
    val contributingUnitIds: Set<String> = emptySet(),
    val corroborationCount: Int = 1,
    val confidence: Double = 0.0,
    val contested: Boolean = false
)

data class GlobalEvidenceLedger(
    val sources: List<GlobalEvidenceSource> = emptyList(),
    val claims: List<GlobalEvidenceClaim> = emptyList(),
    val independentSourceCount: Int = 0,
    val corroboratedClaimCount: Int = 0,
    val contestedClaimCount: Int = 0,
    val overallConfidence: Double = 0.0,
    val verified: Boolean = false,
    val updatedAtMillis: Long = 0L
)

object GlobalResearchPlanBuilder {
    fun create(task: GlobalResearchTask, nowMillis: Long = System.currentTimeMillis()): GlobalResearchPlan {
        val question = task.question.replace(Regex("\\s+"), " ").trim().take(1_200)
        val units = when (task.depth) {
            GlobalResearchDepth.QUICK_FACT -> listOf(
                unit(task, GlobalResearchUnitPurpose.CURRENT_FACTS, question,
                    "Primary or official sources with a current date")
            )
            GlobalResearchDepth.DEEP_RESEARCH -> listOf(
                unit(task, GlobalResearchUnitPurpose.PRIMARY_EVIDENCE,
                    "Establish the current primary-source facts for: $question",
                    "Official documentation, first-party publications, standards, or original data"),
                unit(task, GlobalResearchUnitPurpose.ALTERNATIVES,
                    "Compare credible alternatives and tradeoffs for: $question",
                    "Independent technical sources and primary documentation for each alternative"),
                unit(task, GlobalResearchUnitPurpose.RISKS,
                    "Find failure modes, contradictory evidence, and unresolved risks for: $question",
                    "Issue trackers, advisories, original studies, and reproducible evidence"),
                unit(task, GlobalResearchUnitPurpose.USER_IMPACT,
                    "Assess practical consequences and decision criteria for the user's goals: $question",
                    "Current implementation evidence, benchmarks, and directly applicable sources")
            )
            GlobalResearchDepth.CONTINUOUS_MONITOR -> listOf(
                unit(task, GlobalResearchUnitPurpose.CHANGE_MONITOR,
                    "Find material official changes since the previous check for: $question",
                    "Official release notes, advisories, repositories, and dated announcements"),
                unit(task, GlobalResearchUnitPurpose.CORROBORATION,
                    "Independently verify whether reported changes materially affect: $question",
                    "Independent primary evidence, original repositories, and dated technical analysis")
            )
            GlobalResearchDepth.PROACTIVE_INFERENCE -> listOf(
                unit(task, GlobalResearchUnitPurpose.RISKS,
                    "Identify likely hidden risks or next-stage constraints for: $question",
                    "Primary technical evidence and known failure reports"),
                unit(task, GlobalResearchUnitPurpose.PROACTIVE_INFERENCE,
                    "Identify overlooked opportunities or lower-cost paths for: $question",
                    "Current products, repositories, research, and comparable implementations"),
                unit(task, GlobalResearchUnitPurpose.CORROBORATION,
                    "Challenge the strongest inferred conclusion about: $question",
                    "Independent counter-evidence and primary sources")
            )
        }
        return GlobalResearchPlan(
            id = "plan-${GlobalAgentText.stableKey(task.id, task.question)}",
            phase = GlobalResearchPlanPhase.COLLECTING,
            units = units,
            createdAtMillis = nowMillis,
            updatedAtMillis = nowMillis
        )
    }

    fun recoverStale(plan: GlobalResearchPlan, nowMillis: Long): GlobalResearchPlan {
        val recoveredUnits = plan.units.map { unit ->
            if (unit.status == GlobalResearchUnitStatus.RUNNING &&
                unit.leaseExpiresAtMillis > 0L && unit.leaseExpiresAtMillis <= nowMillis
            ) {
                unit.copy(
                    status = if (unit.attemptCount >= MAX_UNIT_ATTEMPTS) {
                        GlobalResearchUnitStatus.FAILED
                    } else GlobalResearchUnitStatus.PENDING,
                    attemptedResourceIds = (unit.attemptedResourceIds + unit.resourceId)
                        .filter(String::isNotBlank).distinct(),
                    sourceMessageId = 0L,
                    leaseExpiresAtMillis = 0L,
                    lastError = "The evidence worker lease expired before a result arrived"
                )
            } else unit
        }
        val synthesisExpired = plan.phase == GlobalResearchPlanPhase.SYNTHESIZING &&
            plan.synthesisLeaseExpiresAtMillis > 0L && plan.synthesisLeaseExpiresAtMillis <= nowMillis
        return plan.copy(
            phase = when {
                synthesisExpired -> GlobalResearchPlanPhase.SYNTHESIS_PENDING
                recoveredUnits.any { it.status == GlobalResearchUnitStatus.PENDING } -> GlobalResearchPlanPhase.COLLECTING
                recoveredUnits.none { it.status == GlobalResearchUnitStatus.RUNNING } &&
                    recoveredUnits.any { it.status == GlobalResearchUnitStatus.COMPLETED } ->
                    GlobalResearchPlanPhase.SYNTHESIS_PENDING
                else -> plan.phase
            },
            units = recoveredUnits,
            synthesisSourceMessageId = if (synthesisExpired) 0L else plan.synthesisSourceMessageId,
            synthesisLeaseExpiresAtMillis = if (synthesisExpired) 0L else plan.synthesisLeaseExpiresAtMillis,
            updatedAtMillis = if (recoveredUnits != plan.units || synthesisExpired) nowMillis else plan.updatedAtMillis
        )
    }

    fun parallelism(depth: GlobalResearchDepth, resourceCount: Int): Int {
        if (resourceCount <= 0) return 0
        return when (depth) {
            GlobalResearchDepth.QUICK_FACT -> 1
            GlobalResearchDepth.CONTINUOUS_MONITOR -> 2
            GlobalResearchDepth.PROACTIVE_INFERENCE -> 2
            GlobalResearchDepth.DEEP_RESEARCH -> 3
        }
    }

    private fun unit(
        task: GlobalResearchTask,
        purpose: GlobalResearchUnitPurpose,
        question: String,
        sourceFocus: String
    ) = GlobalResearchUnit(
        id = "unit-${GlobalAgentText.stableKey(task.id, purpose.name)}",
        purpose = purpose,
        question = question,
        sourceFocus = sourceFocus
    )

    private const val MAX_UNIT_ATTEMPTS = 3
}

object GlobalEvidenceEvaluator {
    private val urlPattern = Regex("https://[^\\s<>()]+", RegexOption.IGNORE_CASE)
    private val sentenceBoundary = Regex("(?<=[.!?])\\s+|\\n+")

    fun build(plan: GlobalResearchPlan, nowMillis: Long = System.currentTimeMillis()): GlobalEvidenceLedger {
        val completed = plan.completedUnits()
        val sourcesByUri = linkedMapOf<String, GlobalEvidenceSource>()
        completed.forEach { unit ->
            val urls = (unit.evidenceUris + extractUrls(unit.result)).distinct().take(MAX_SOURCES_PER_UNIT)
            urls.forEach { uri ->
                val existing = sourcesByUri[uri]
                val kind = sourceKind(uri)
                val quality = quality(kind, uri)
                sourcesByUri[uri] = if (existing == null) {
                    GlobalEvidenceSource(uri, kind, quality, setOf(unit.id), nowMillis)
                } else existing.copy(
                    qualityScore = max(existing.qualityScore, quality),
                    contributingUnitIds = existing.contributingUnitIds + unit.id,
                    retrievedAtMillis = nowMillis
                )
            }
        }
        val mutableClaims = mutableListOf<GlobalEvidenceClaim>()
        completed.forEach { unit ->
            val unitUris = (unit.evidenceUris + extractUrls(unit.result)).distinct().take(MAX_SOURCES_PER_UNIT).toSet()
            extractClaims(unit.result).forEach { statement ->
                val existingIndex = mutableClaims.indexOfFirst { existing ->
                    GlobalAgentText.overlap(
                        GlobalAgentText.tokens(existing.statement),
                        GlobalAgentText.tokens(statement)
                    ) >= CLAIM_MERGE_OVERLAP
                }
                if (existingIndex >= 0) {
                    val existing = mutableClaims[existingIndex]
                    mutableClaims[existingIndex] = existing.copy(
                        sourceUris = existing.sourceUris + unitUris,
                        contributingUnitIds = existing.contributingUnitIds + unit.id,
                        corroborationCount = existing.corroborationCount + 1
                    )
                } else {
                    mutableClaims += GlobalEvidenceClaim(
                        statement = statement,
                        sourceUris = unitUris,
                        contributingUnitIds = setOf(unit.id)
                    )
                }
            }
        }
        val contestedIds = mutableSetOf<String>()
        mutableClaims.forEachIndexed { leftIndex, left ->
            mutableClaims.drop(leftIndex + 1).forEach { right ->
                val overlap = GlobalAgentText.overlap(
                    GlobalAgentText.tokens(left.statement),
                    GlobalAgentText.tokens(right.statement)
                )
                if (overlap >= CONTEST_OVERLAP && polarity(left.statement) != polarity(right.statement)) {
                    contestedIds += left.id
                    contestedIds += right.id
                }
            }
        }
        val scoredClaims = mutableClaims.map { claim ->
            val sourceQuality = claim.sourceUris.mapNotNull { sourcesByUri[it]?.qualityScore }.averageOrZero()
            val sourceFactor = (claim.sourceUris.size.coerceAtMost(3) * 0.08)
            val corroborationFactor = ((claim.corroborationCount - 1).coerceAtMost(3) * 0.10)
            val noSourcePenalty = if (claim.sourceUris.isEmpty()) 0.24 else 0.0
            val contestedPenalty = if (claim.id in contestedIds) 0.22 else 0.0
            claim.copy(
                confidence = (0.24 + sourceQuality * 0.48 + sourceFactor + corroborationFactor -
                    noSourcePenalty - contestedPenalty).coerceIn(0.05, 0.98),
                contested = claim.id in contestedIds
            )
        }.sortedByDescending(GlobalEvidenceClaim::confidence).take(MAX_CLAIMS)
        val independentSources = sourcesByUri.values.map { sourceDomain(it.uri) }.filter(String::isNotBlank).distinct().size
        val corroborated = scoredClaims.count { it.corroborationCount >= 2 && !it.contested }
        val overall = scoredClaims.take(8).map(GlobalEvidenceClaim::confidence).averageOrZero()
        val multiUnitResearch = plan.units.size > 1
        val sufficientSourceDiversity = if (multiUnitResearch) {
            independentSources >= 2 && (corroborated > 0 || completed.size >= 2)
        } else {
            independentSources >= 1 || sourcesByUri.values.any { it.kind in PRIMARY_SOURCE_KINDS }
        }
        val verified = scoredClaims.isNotEmpty() && overall >= 0.56 && sufficientSourceDiversity
        return GlobalEvidenceLedger(
            sources = sourcesByUri.values.sortedByDescending(GlobalEvidenceSource::qualityScore).take(MAX_SOURCES),
            claims = scoredClaims,
            independentSourceCount = independentSources,
            corroboratedClaimCount = corroborated,
            contestedClaimCount = scoredClaims.count(GlobalEvidenceClaim::contested),
            overallConfidence = overall,
            verified = verified,
            updatedAtMillis = nowMillis
        )
    }

    fun extractUrls(value: String): List<String> = urlPattern.findAll(value)
        .map { it.value.trimEnd('.', ',', ')', ']', '}') }
        .filter { runCatching { URI(it).scheme.equals("https", true) }.getOrDefault(false) }
        .distinct()
        .take(MAX_SOURCES)
        .toList()

    fun sourceKind(uri: String): GlobalEvidenceSourceKind {
        val domain = sourceDomain(uri)
        return when {
            domain.endsWith(".gov") || domain.contains(".gov.") -> GlobalEvidenceSourceKind.GOVERNMENT
            OFFICIAL_DOMAINS.any { domain == it || domain.endsWith(".$it") } ||
                domain.startsWith("docs.") || domain.startsWith("developer.") ->
                GlobalEvidenceSourceKind.OFFICIAL
            domain == "doi.org" || domain == "arxiv.org" || domain.endsWith(".edu") ->
                GlobalEvidenceSourceKind.PAPER
            domain == "github.com" || domain == "gitlab.com" -> GlobalEvidenceSourceKind.CODE_REPOSITORY
            domain in NEWS_DOMAINS -> GlobalEvidenceSourceKind.NEWS
            domain in COMMUNITY_DOMAINS -> GlobalEvidenceSourceKind.COMMUNITY
            else -> GlobalEvidenceSourceKind.UNKNOWN
        }
    }

    private fun extractClaims(value: String): List<String> = value
        .replace(urlPattern, " ")
        .split(sentenceBoundary)
        .map { it.replace(Regex("^[#>*\\-\\d. )]+"), "").replace(Regex("\\s+"), " ").trim() }
        .filter { it.length in MIN_CLAIM_CHARACTERS..MAX_CLAIM_CHARACTERS }
        .filterNot { it.endsWith(":") || it.count(Char::isLetterOrDigit) < MIN_CLAIM_CONTENT_CHARACTERS }
        .distinctBy(GlobalAgentText::normalize)
        .take(MAX_CLAIMS_PER_UNIT)

    private fun quality(kind: GlobalEvidenceSourceKind, uri: String): Double {
        val base = when (kind) {
            GlobalEvidenceSourceKind.GOVERNMENT -> 0.96
            GlobalEvidenceSourceKind.OFFICIAL -> 0.93
            GlobalEvidenceSourceKind.PAPER -> 0.90
            GlobalEvidenceSourceKind.CODE_REPOSITORY -> 0.84
            GlobalEvidenceSourceKind.NEWS -> 0.68
            GlobalEvidenceSourceKind.COMMUNITY -> 0.48
            GlobalEvidenceSourceKind.UNKNOWN -> 0.56
        }
        val httpsBonus = if (uri.startsWith("https://", true)) 0.02 else 0.0
        return (base + httpsBonus).coerceAtMost(0.98)
    }

    private fun sourceDomain(uri: String): String = runCatching {
        URI(uri).host.orEmpty().lowercase(Locale.ROOT).removePrefix("www.")
    }.getOrDefault("")

    private fun polarity(value: String): Int {
        val lower = value.lowercase(Locale.ROOT)
        return if (NEGATION_SIGNALS.any(lower::contains)) -1 else 1
    }

    private fun Iterable<Double>.averageOrZero(): Double {
        val values = toList()
        return if (values.isEmpty()) 0.0 else values.average()
    }

    private const val CLAIM_MERGE_OVERLAP = 0.72
    private const val CONTEST_OVERLAP = 0.34
    private const val MIN_CLAIM_CHARACTERS = 28
    private const val MAX_CLAIM_CHARACTERS = 700
    private const val MIN_CLAIM_CONTENT_CHARACTERS = 18
    private const val MAX_CLAIMS_PER_UNIT = 10
    private const val MAX_CLAIMS = 32
    private const val MAX_SOURCES_PER_UNIT = 16
    private const val MAX_SOURCES = 40
    private val PRIMARY_SOURCE_KINDS = setOf(
        GlobalEvidenceSourceKind.GOVERNMENT,
        GlobalEvidenceSourceKind.OFFICIAL,
        GlobalEvidenceSourceKind.PAPER
    )
    private val NEGATION_SIGNALS = listOf(
        " not ", " no ", "never", "without", "cannot", "failed", "unsupported",
        "\u4e0d\u652f\u6301", "\u4e0d\u80fd", "\u65e0\u6cd5", "\u672a\u901a\u8fc7", "\u5931\u8d25"
    )
    private val OFFICIAL_DOMAINS = setOf(
        "openai.com", "developers.openai.com", "developer.android.com", "android.com", "microsoft.com",
        "apple.com", "anthropic.com", "cloud.google.com", "docs.github.com", "kotlinlang.org", "oracle.com"
    )
    private val NEWS_DOMAINS = setOf("reuters.com", "apnews.com", "bbc.com", "bloomberg.com")
    private val COMMUNITY_DOMAINS = setOf("stackoverflow.com", "reddit.com", "medium.com")
}
