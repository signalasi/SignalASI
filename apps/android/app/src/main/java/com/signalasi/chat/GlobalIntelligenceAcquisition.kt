package com.signalasi.chat

import java.net.URI
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.Locale
import java.util.UUID

enum class GlobalResearchPlanPhase {
    UNPLANNED,
    COLLECTING,
    SYNTHESIS_PENDING,
    SYNTHESIZING,
    COMPLETED
}

enum class GlobalResearchUnitStatus { PENDING, RUNNING, COMPLETED, FAILED }

enum class GlobalEvidenceSourceKind {
    OFFICIAL,
    GOVERNMENT,
    PAPER,
    CODE_REPOSITORY,
    NEWS,
    COMMUNITY,
    UNKNOWN
}

enum class GlobalEvidenceFreshness { FRESH, STALE, UNKNOWN }

enum class GlobalEvidenceQualityIssue {
    NO_USABLE_CLAIMS,
    INSUFFICIENT_SOURCE_DIVERSITY,
    PRIMARY_SOURCE_MISSING,
    FRESH_EVIDENCE_MISSING,
    CLAIMS_NOT_CORROBORATED,
    UNRESOLVED_CONTRADICTIONS,
    LOW_CONFIDENCE
}

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
    val queryCandidates: List<String> = emptyList(),
    val minimumIndependentSources: Int = 1,
    val requiredSourceKinds: Set<GlobalEvidenceSourceKind> = emptySet(),
    val freshnessWindowMillis: Long = 0L,
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
    val depth: GlobalResearchDepth = GlobalResearchDepth.QUICK_FACT,
    val phase: GlobalResearchPlanPhase = GlobalResearchPlanPhase.UNPLANNED,
    val units: List<GlobalResearchUnit> = emptyList(),
    val qualityExpansionCount: Int = 0,
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

data class GlobalEvidenceSource(
    val uri: String,
    val kind: GlobalEvidenceSourceKind,
    val qualityScore: Double,
    val authority: String = "",
    val contributingUnitIds: Set<String> = emptySet(),
    val publishedAtMillis: Long = 0L,
    val freshness: GlobalEvidenceFreshness = GlobalEvidenceFreshness.UNKNOWN,
    val retrievedAtMillis: Long = System.currentTimeMillis()
)

data class GlobalEvidenceClaim(
    val id: String = UUID.randomUUID().toString(),
    val statement: String,
    val sourceUris: Set<String> = emptySet(),
    val contributingUnitIds: Set<String> = emptySet(),
    val corroborationCount: Int = 1,
    val independentSourceCount: Int = 0,
    val primarySourceCount: Int = 0,
    val confidence: Double = 0.0,
    val contested: Boolean = false
)

data class GlobalEvidenceLedger(
    val sources: List<GlobalEvidenceSource> = emptyList(),
    val claims: List<GlobalEvidenceClaim> = emptyList(),
    val independentSourceCount: Int = 0,
    val primarySourceCount: Int = 0,
    val freshSourceCount: Int = 0,
    val staleSourceCount: Int = 0,
    val undatedSourceCount: Int = 0,
    val corroboratedClaimCount: Int = 0,
    val contestedClaimCount: Int = 0,
    val qualityIssues: Set<GlobalEvidenceQualityIssue> = emptySet(),
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
            depth = task.depth,
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

    fun closeCollection(
        task: GlobalResearchTask,
        plan: GlobalResearchPlan,
        ledger: GlobalEvidenceLedger,
        nowMillis: Long = System.currentTimeMillis()
    ): GlobalResearchPlan {
        if (plan.phase in setOf(GlobalResearchPlanPhase.SYNTHESIZING, GlobalResearchPlanPhase.COMPLETED)) return plan
        if (plan.runningUnits().isNotEmpty() || plan.pendingUnits().isNotEmpty()) return plan
        if (plan.completedUnits().isEmpty()) return plan
        if (ledger.verified || plan.qualityExpansionCount >= MAX_QUALITY_EXPANSIONS) {
            return plan.copy(
                phase = GlobalResearchPlanPhase.SYNTHESIS_PENDING,
                updatedAtMillis = nowMillis
            )
        }
        val nextExpansion = plan.qualityExpansionCount + 1
        val additions = qualityUnits(task, ledger.qualityIssues, nextExpansion).take(MAX_QUALITY_UNITS_PER_EXPANSION)
        return if (additions.isEmpty()) {
            plan.copy(
                phase = GlobalResearchPlanPhase.SYNTHESIS_PENDING,
                qualityExpansionCount = nextExpansion,
                updatedAtMillis = nowMillis
            )
        } else {
            plan.copy(
                phase = GlobalResearchPlanPhase.COLLECTING,
                units = plan.units + additions,
                qualityExpansionCount = nextExpansion,
                updatedAtMillis = nowMillis
            )
        }
    }

    private fun unit(
        task: GlobalResearchTask,
        purpose: GlobalResearchUnitPurpose,
        question: String,
        sourceFocus: String,
        idSuffix: String = "base"
    ) = GlobalResearchUnit(
        id = "unit-${GlobalAgentText.stableKey(task.id, purpose.name, idSuffix)}",
        purpose = purpose,
        question = question,
        sourceFocus = sourceFocus,
        queryCandidates = queryCandidates(question, purpose, task.preferredSources),
        minimumIndependentSources = minimumIndependentSources(purpose),
        requiredSourceKinds = requiredSourceKinds(purpose),
        freshnessWindowMillis = freshnessWindowMillis(task.depth, purpose)
    )

    private fun qualityUnits(
        task: GlobalResearchTask,
        issues: Set<GlobalEvidenceQualityIssue>,
        expansion: Int
    ): List<GlobalResearchUnit> {
        val units = mutableListOf<GlobalResearchUnit>()
        if (GlobalEvidenceQualityIssue.UNRESOLVED_CONTRADICTIONS in issues) {
            units += unit(
                task,
                GlobalResearchUnitPurpose.CORROBORATION,
                "Resolve the strongest contradictory claims using independent primary evidence for: ${task.question}",
                "Independent primary sources that directly support or refute each contested claim",
                "quality-$expansion-contradiction"
            )
        }
        if (GlobalEvidenceQualityIssue.FRESH_EVIDENCE_MISSING in issues) {
            units += unit(
                task,
                GlobalResearchUnitPurpose.CHANGE_MONITOR,
                "Find dated, current evidence and material changes for: ${task.question}",
                "Dated official releases, advisories, repositories, or first-party announcements",
                "quality-$expansion-freshness"
            )
        }
        if (GlobalEvidenceQualityIssue.PRIMARY_SOURCE_MISSING in issues) {
            units += unit(
                task,
                GlobalResearchUnitPurpose.PRIMARY_EVIDENCE,
                "Verify the key conclusion against a first-party or original source for: ${task.question}",
                "Official documentation, original data, standards, papers, or first-party repositories",
                "quality-$expansion-primary"
            )
        }
        if (issues.any { it in DIVERSITY_ISSUES }) {
            units += unit(
                task,
                GlobalResearchUnitPurpose.CORROBORATION,
                "Independently corroborate the material claims from a different publisher for: ${task.question}",
                "A source organization not already represented, preferably primary evidence",
                "quality-$expansion-diversity"
            )
        }
        return units.distinctBy(GlobalResearchUnit::id)
    }

    private fun queryCandidates(
        question: String,
        purpose: GlobalResearchUnitPurpose,
        preferredSources: List<String>
    ): List<String> {
        val concise = question.replace(Regex("\\s+"), " ").trim().take(700)
        val preferred = preferredSources.filter(String::isNotBlank).distinct().joinToString(" ")
        val qualifiers = when (purpose) {
            GlobalResearchUnitPurpose.CURRENT_FACTS -> listOf("official current", "latest update date", "primary source")
            GlobalResearchUnitPurpose.PRIMARY_EVIDENCE -> listOf("official documentation", "original data", "first party")
            GlobalResearchUnitPurpose.ALTERNATIVES -> listOf("alternatives comparison", "benchmark tradeoffs", "official documentation")
            GlobalResearchUnitPurpose.RISKS -> listOf("known issues advisory", "failure report", "contradictory evidence")
            GlobalResearchUnitPurpose.USER_IMPACT -> listOf("implementation impact", "decision criteria", "measured benchmark")
            GlobalResearchUnitPurpose.CHANGE_MONITOR -> listOf("latest release notes", "dated announcement", "recent changes")
            GlobalResearchUnitPurpose.CORROBORATION -> listOf("independent verification", "primary evidence", "counter evidence")
            GlobalResearchUnitPurpose.PROACTIVE_INFERENCE -> listOf("emerging opportunity", "lower cost alternative", "next constraint")
        }
        return qualifiers.map { qualifier -> listOf(concise, qualifier, preferred).filter(String::isNotBlank).joinToString(" ") }
            .distinct()
            .take(3)
    }

    private fun minimumIndependentSources(purpose: GlobalResearchUnitPurpose): Int = when (purpose) {
        GlobalResearchUnitPurpose.ALTERNATIVES,
        GlobalResearchUnitPurpose.RISKS,
        GlobalResearchUnitPurpose.CORROBORATION,
        GlobalResearchUnitPurpose.PROACTIVE_INFERENCE -> 2
        else -> 1
    }

    private fun requiredSourceKinds(purpose: GlobalResearchUnitPurpose): Set<GlobalEvidenceSourceKind> = when (purpose) {
        GlobalResearchUnitPurpose.CURRENT_FACTS,
        GlobalResearchUnitPurpose.PRIMARY_EVIDENCE,
        GlobalResearchUnitPurpose.CHANGE_MONITOR -> PRIMARY_SOURCE_KINDS
        GlobalResearchUnitPurpose.ALTERNATIVES,
        GlobalResearchUnitPurpose.CORROBORATION -> PRIMARY_SOURCE_KINDS + GlobalEvidenceSourceKind.CODE_REPOSITORY
        else -> emptySet()
    }

    private fun freshnessWindowMillis(
        depth: GlobalResearchDepth,
        purpose: GlobalResearchUnitPurpose
    ): Long = when {
        purpose == GlobalResearchUnitPurpose.CHANGE_MONITOR -> 120L * DAY_MILLIS
        purpose == GlobalResearchUnitPurpose.CURRENT_FACTS -> 365L * DAY_MILLIS
        depth == GlobalResearchDepth.CONTINUOUS_MONITOR -> 180L * DAY_MILLIS
        purpose in setOf(GlobalResearchUnitPurpose.ALTERNATIVES, GlobalResearchUnitPurpose.USER_IMPACT) -> 365L * DAY_MILLIS
        else -> 730L * DAY_MILLIS
    }

    private const val MAX_UNIT_ATTEMPTS = 3
    private const val MAX_QUALITY_EXPANSIONS = 2
    private const val MAX_QUALITY_UNITS_PER_EXPANSION = 2
    private const val DAY_MILLIS = 24L * 60L * 60L * 1_000L
    private val PRIMARY_SOURCE_KINDS = setOf(
        GlobalEvidenceSourceKind.GOVERNMENT,
        GlobalEvidenceSourceKind.OFFICIAL,
        GlobalEvidenceSourceKind.PAPER
    )
    private val DIVERSITY_ISSUES = setOf(
        GlobalEvidenceQualityIssue.NO_USABLE_CLAIMS,
        GlobalEvidenceQualityIssue.INSUFFICIENT_SOURCE_DIVERSITY,
        GlobalEvidenceQualityIssue.CLAIMS_NOT_CORROBORATED,
        GlobalEvidenceQualityIssue.LOW_CONFIDENCE
    )
}

object GlobalEvidenceEvaluator {
    private val urlPattern = Regex("https://[^\\s<>()]+", RegexOption.IGNORE_CASE)
    private val sentenceBoundary = Regex("(?<=[.!?])\\s+|\\n+")
    private val isoDatePattern = Regex("(?<!\\d)(20\\d{2})[-/.](0?[1-9]|1[0-2])[-/.](0?[1-9]|[12]\\d|3[01])(?!\\d)")

    fun build(plan: GlobalResearchPlan, nowMillis: Long = System.currentTimeMillis()): GlobalEvidenceLedger {
        val completed = plan.completedUnits()
        val unitsById = plan.units.associateBy(GlobalResearchUnit::id)
        val observations = completed.flatMap { unit ->
            (unit.evidenceUris + extractUrls(unit.result))
                .mapNotNull(::canonicalUri)
                .distinct()
                .take(MAX_SOURCES_PER_UNIT)
                .map { uri -> SourceObservation(uri, unit.id, publishedAtMillis(unit.result, uri, nowMillis)) }
        }
        val sourcesByUri = observations.groupBy(SourceObservation::uri).mapValuesTo(linkedMapOf()) { (uri, entries) ->
            val kind = sourceKind(uri)
            val unitIds = entries.map(SourceObservation::unitId).toSet()
            val strictestFreshnessWindow = unitIds.mapNotNull { unitsById[it]?.freshnessWindowMillis?.takeIf { age -> age > 0L } }
                .minOrNull() ?: 0L
            val publishedAtMillis = entries.maxOfOrNull(SourceObservation::publishedAtMillis) ?: 0L
            val freshness = freshness(publishedAtMillis, strictestFreshnessWindow, nowMillis)
            GlobalEvidenceSource(
                uri = uri,
                kind = kind,
                qualityScore = quality(kind, uri, freshness, strictestFreshnessWindow > 0L),
                authority = sourceAuthority(uri),
                contributingUnitIds = unitIds,
                publishedAtMillis = publishedAtMillis,
                freshness = freshness,
                retrievedAtMillis = nowMillis
            )
        }
        val mutableClaims = mutableListOf<GlobalEvidenceClaim>()
        completed.forEach { unit ->
            val unitUris = (unit.evidenceUris + extractUrls(unit.result))
                .mapNotNull(::canonicalUri).distinct().take(MAX_SOURCES_PER_UNIT).toSet()
            extractClaims(unit.result, unitUris).forEach { parsed ->
                val existingIndex = mutableClaims.indexOfFirst { existing ->
                    polarity(existing.statement) == polarity(parsed.statement) && GlobalAgentText.overlap(
                        GlobalAgentText.tokens(existing.statement),
                        GlobalAgentText.tokens(parsed.statement)
                    ) >= CLAIM_MERGE_OVERLAP
                }
                if (existingIndex >= 0) {
                    val existing = mutableClaims[existingIndex]
                    val contributingUnits = existing.contributingUnitIds + unit.id
                    mutableClaims[existingIndex] = existing.copy(
                        sourceUris = existing.sourceUris + parsed.sourceUris,
                        contributingUnitIds = contributingUnits,
                        corroborationCount = contributingUnits.size
                    )
                } else {
                    mutableClaims += GlobalEvidenceClaim(
                        statement = parsed.statement,
                        sourceUris = parsed.sourceUris,
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
            val claimSources = claim.sourceUris.mapNotNull(sourcesByUri::get)
            val independentSourceCount = claimSources.map(GlobalEvidenceSource::authority)
                .filter(String::isNotBlank).distinct().size
            val primarySourceCount = claimSources.count { it.kind in PRIMARY_SOURCE_KINDS }
            val sourceQuality = claimSources.map(GlobalEvidenceSource::qualityScore).averageOrZero()
            val sourceFactor = (independentSourceCount.coerceAtMost(3) * 0.09)
            val corroborationFactor = ((claim.contributingUnitIds.size - 1).coerceAtMost(3) * 0.08)
            val freshnessBonus = if (claimSources.any { it.freshness == GlobalEvidenceFreshness.FRESH }) 0.04 else 0.0
            val stalePenalty = if (claimSources.isNotEmpty() && claimSources.all {
                    it.freshness == GlobalEvidenceFreshness.STALE
                }) 0.12 else 0.0
            val noSourcePenalty = if (claim.sourceUris.isEmpty()) 0.24 else 0.0
            val contestedPenalty = if (claim.id in contestedIds) 0.22 else 0.0
            claim.copy(
                confidence = (0.24 + sourceQuality * 0.48 + sourceFactor + corroborationFactor -
                    noSourcePenalty - contestedPenalty - stalePenalty + freshnessBonus).coerceIn(0.05, 0.98),
                corroborationCount = claim.contributingUnitIds.size,
                independentSourceCount = independentSourceCount,
                primarySourceCount = primarySourceCount,
                contested = claim.id in contestedIds
            )
        }.sortedByDescending(GlobalEvidenceClaim::confidence).take(MAX_CLAIMS)
        val sources = sourcesByUri.values.sortedByDescending(GlobalEvidenceSource::qualityScore).take(MAX_SOURCES)
        val independentSources = sources.map(GlobalEvidenceSource::authority).filter(String::isNotBlank).distinct().size
        val primarySources = sources.count { it.kind in PRIMARY_SOURCE_KINDS }
        val freshSources = sources.count { it.freshness == GlobalEvidenceFreshness.FRESH }
        val staleSources = sources.count { it.freshness == GlobalEvidenceFreshness.STALE }
        val undatedSources = sources.count { it.publishedAtMillis <= 0L }
        val corroborated = scoredClaims.count {
            it.corroborationCount >= 2 && it.independentSourceCount >= 2 && !it.contested
        }
        val overall = scoredClaims.take(8).map(GlobalEvidenceClaim::confidence).averageOrZero()
        val issues = qualityIssues(
            plan = plan,
            claims = scoredClaims,
            independentSources = independentSources,
            primarySources = primarySources,
            freshSources = freshSources,
            staleSources = staleSources,
            corroboratedClaims = corroborated,
            contestedClaims = scoredClaims.count(GlobalEvidenceClaim::contested),
            overallConfidence = overall
        )
        return GlobalEvidenceLedger(
            sources = sources,
            claims = scoredClaims,
            independentSourceCount = independentSources,
            primarySourceCount = primarySources,
            freshSourceCount = freshSources,
            staleSourceCount = staleSources,
            undatedSourceCount = undatedSources,
            corroboratedClaimCount = corroborated,
            contestedClaimCount = scoredClaims.count(GlobalEvidenceClaim::contested),
            qualityIssues = issues,
            overallConfidence = overall,
            verified = issues.isEmpty(),
            updatedAtMillis = nowMillis
        )
    }

    fun extractUrls(value: String): List<String> = urlPattern.findAll(value)
        .map { it.value.trimEnd('.', ',', ')', ']', '}') }
        .mapNotNull(::canonicalUri)
        .distinct()
        .take(MAX_SOURCES)
        .toList()

    fun canonicalUri(value: String): String? = runCatching {
        val parsed = URI(value.trim())
        if (!parsed.scheme.equals("https", true) || parsed.host.isNullOrBlank()) return null
        val filteredQuery = parsed.rawQuery?.split('&')?.filterNot { parameter ->
            val key = parameter.substringBefore('=').lowercase(Locale.ROOT)
            key.startsWith("utm_") || key in TRACKING_QUERY_KEYS
        }?.joinToString("&")?.ifBlank { null }
        URI(
            "https",
            parsed.userInfo,
            parsed.host.lowercase(Locale.ROOT),
            parsed.port,
            parsed.path.ifBlank { "/" },
            filteredQuery,
            null
        ).toASCIIString().trimEnd('/')
    }.getOrNull()

    fun sourceKind(uri: String): GlobalEvidenceSourceKind {
        val domain = sourceDomain(uri)
        val authority = sourceAuthority(uri)
        return when {
            domain.endsWith(".gov") || domain.contains(".gov.") -> GlobalEvidenceSourceKind.GOVERNMENT
            domain == "github.com" || domain == "gitlab.com" -> GlobalEvidenceSourceKind.CODE_REPOSITORY
            authority in OFFICIAL_AUTHORITIES ->
                GlobalEvidenceSourceKind.OFFICIAL
            domain == "doi.org" || domain == "arxiv.org" || domain.endsWith(".edu") ->
                GlobalEvidenceSourceKind.PAPER
            authority in NEWS_AUTHORITIES -> GlobalEvidenceSourceKind.NEWS
            authority in COMMUNITY_AUTHORITIES -> GlobalEvidenceSourceKind.COMMUNITY
            else -> GlobalEvidenceSourceKind.UNKNOWN
        }
    }

    fun sourceAuthority(uri: String): String {
        val domain = sourceDomain(uri)
        if (domain.isBlank() || IPV4_PATTERN.matches(domain)) return domain
        val labels = domain.split('.').filter(String::isNotBlank)
        if (labels.size <= 2) return domain
        val suffix = labels.takeLast(2).joinToString(".")
        return if (suffix in TWO_LEVEL_PUBLIC_SUFFIXES && labels.size >= 3) {
            labels.takeLast(3).joinToString(".")
        } else suffix
    }

    private fun extractClaims(value: String, fallbackUris: Set<String>): List<ParsedClaim> {
        val claims = mutableListOf<ParsedClaim>()
        value.lineSequence().forEach { line ->
            val lineUris = extractUrls(line).toSet()
            val cleanedLine = cleanClaimText(line)
            val statements = cleanedLine.split(sentenceBoundary).map(::normalizeClaim).filter(::isUsableClaim)
            if (statements.isEmpty() && lineUris.isNotEmpty() && claims.isNotEmpty()) {
                val previous = claims.last()
                claims[claims.lastIndex] = previous.copy(sourceUris = previous.sourceUris + lineUris)
            } else {
                statements.forEach { statement -> claims += ParsedClaim(statement, lineUris) }
            }
        }
        if (claims.isEmpty()) {
            value.replace(urlPattern, " ").split(sentenceBoundary)
                .map(::normalizeClaim).filter(::isUsableClaim)
                .forEach { claims += ParsedClaim(it, emptySet()) }
        }
        return claims.map { claim ->
            if (claim.sourceUris.isEmpty() && fallbackUris.size == 1) claim.copy(sourceUris = fallbackUris) else claim
        }.distinctBy { GlobalAgentText.normalize(it.statement) }.take(MAX_CLAIMS_PER_UNIT)
    }

    private fun cleanClaimText(value: String): String = value
        .replace(urlPattern, " ")
        .replace(STRUCTURED_FIELD_PATTERN, " ")
        .replace(isoDatePattern, " ")
        .replace('|', ' ')

    private fun normalizeClaim(value: String): String = value
        .replace(Regex("^[#>*\\-\\d. )]+"), "")
        .replace(Regex("\\s+"), " ")
        .trim()

    private fun isUsableClaim(value: String): Boolean =
        value.length in MIN_CLAIM_CHARACTERS..MAX_CLAIM_CHARACTERS &&
            !value.endsWith(":") && value.count(Char::isLetterOrDigit) >= MIN_CLAIM_CONTENT_CHARACTERS

    private fun publishedAtMillis(value: String, uri: String, nowMillis: Long): Long {
        val lines = value.lines()
        val sourcePath = runCatching { URI(uri).path }.getOrDefault("")
        val lineIndex = lines.indexOfFirst { line ->
            line.contains(uri, ignoreCase = true) ||
                (sourcePath.length > 4 && line.contains(sourcePath, ignoreCase = true))
        }
        val nearby = if (lineIndex >= 0) {
            lines.subList(lineIndex, (lineIndex + 2).coerceAtMost(lines.size)).joinToString(" ")
        } else ""
        return (dateCandidates(nearby, nowMillis) + dateCandidates(uri, nowMillis)).maxOrNull() ?: 0L
    }

    private fun dateCandidates(value: String, nowMillis: Long): List<Long> = isoDatePattern.findAll(value).mapNotNull { match ->
        runCatching {
            LocalDate.of(
                match.groupValues[1].toInt(),
                match.groupValues[2].toInt(),
                match.groupValues[3].toInt()
            ).atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli()
        }.getOrNull()?.takeIf { it <= nowMillis + FUTURE_DATE_TOLERANCE_MILLIS }
    }.toList()

    private fun freshness(publishedAtMillis: Long, windowMillis: Long, nowMillis: Long): GlobalEvidenceFreshness = when {
        publishedAtMillis <= 0L || windowMillis <= 0L -> GlobalEvidenceFreshness.UNKNOWN
        publishedAtMillis >= nowMillis - windowMillis -> GlobalEvidenceFreshness.FRESH
        else -> GlobalEvidenceFreshness.STALE
    }

    private fun qualityIssues(
        plan: GlobalResearchPlan,
        claims: List<GlobalEvidenceClaim>,
        independentSources: Int,
        primarySources: Int,
        freshSources: Int,
        staleSources: Int,
        corroboratedClaims: Int,
        contestedClaims: Int,
        overallConfidence: Double
    ): Set<GlobalEvidenceQualityIssue> = linkedSetOf<GlobalEvidenceQualityIssue>().apply {
        val requiredIndependentSources = when (plan.depth) {
            GlobalResearchDepth.QUICK_FACT -> 1
            GlobalResearchDepth.DEEP_RESEARCH,
            GlobalResearchDepth.CONTINUOUS_MONITOR,
            GlobalResearchDepth.PROACTIVE_INFERENCE -> 2
        }
        if (claims.isEmpty()) add(GlobalEvidenceQualityIssue.NO_USABLE_CLAIMS)
        if (independentSources < requiredIndependentSources) {
            add(GlobalEvidenceQualityIssue.INSUFFICIENT_SOURCE_DIVERSITY)
        }
        val primaryRequired = plan.depth != GlobalResearchDepth.PROACTIVE_INFERENCE ||
            plan.units.any { it.requiredSourceKinds.any(PRIMARY_SOURCE_KINDS::contains) }
        if (primaryRequired && primarySources == 0) add(GlobalEvidenceQualityIssue.PRIMARY_SOURCE_MISSING)
        val freshnessRequired = plan.depth == GlobalResearchDepth.CONTINUOUS_MONITOR ||
            plan.units.any { it.purpose == GlobalResearchUnitPurpose.CHANGE_MONITOR }
        if (freshnessRequired && freshSources == 0) add(GlobalEvidenceQualityIssue.FRESH_EVIDENCE_MISSING)
        if (plan.depth == GlobalResearchDepth.QUICK_FACT && staleSources > 0 && freshSources == 0) {
            add(GlobalEvidenceQualityIssue.FRESH_EVIDENCE_MISSING)
        }
        if (plan.depth != GlobalResearchDepth.QUICK_FACT && corroboratedClaims == 0) {
            add(GlobalEvidenceQualityIssue.CLAIMS_NOT_CORROBORATED)
        }
        if (contestedClaims > 0) add(GlobalEvidenceQualityIssue.UNRESOLVED_CONTRADICTIONS)
        val confidenceThreshold = when (plan.depth) {
            GlobalResearchDepth.QUICK_FACT -> 0.50
            GlobalResearchDepth.DEEP_RESEARCH -> 0.58
            GlobalResearchDepth.CONTINUOUS_MONITOR -> 0.60
            GlobalResearchDepth.PROACTIVE_INFERENCE -> 0.56
        }
        if (overallConfidence < confidenceThreshold) add(GlobalEvidenceQualityIssue.LOW_CONFIDENCE)
    }

    private fun quality(
        kind: GlobalEvidenceSourceKind,
        uri: String,
        freshness: GlobalEvidenceFreshness,
        freshnessRelevant: Boolean
    ): Double {
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
        val freshnessAdjustment = if (!freshnessRelevant) 0.0 else when (freshness) {
            GlobalEvidenceFreshness.FRESH -> 0.02
            GlobalEvidenceFreshness.STALE -> -0.18
            GlobalEvidenceFreshness.UNKNOWN -> -0.05
        }
        return (base + httpsBonus + freshnessAdjustment).coerceIn(0.20, 0.98)
    }

    private fun sourceDomain(uri: String): String = runCatching {
        URI(uri).host.orEmpty().lowercase(Locale.ROOT).removePrefix("www.")
    }.getOrDefault("")

    private fun polarity(value: String): Int {
        val lower = value.lowercase(Locale.ROOT)
        return if (NEGATION_PATTERN.containsMatchIn(lower) || NEGATION_SIGNALS.any(lower::contains)) -1 else 1
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
    private const val FUTURE_DATE_TOLERANCE_MILLIS = 2L * 24L * 60L * 60L * 1_000L
    private data class SourceObservation(val uri: String, val unitId: String, val publishedAtMillis: Long)
    private data class ParsedClaim(val statement: String, val sourceUris: Set<String>)
    private val PRIMARY_SOURCE_KINDS = setOf(
        GlobalEvidenceSourceKind.GOVERNMENT,
        GlobalEvidenceSourceKind.OFFICIAL,
        GlobalEvidenceSourceKind.PAPER
    )
    private val NEGATION_PATTERN = Regex("\\b(no|not|never|without|cannot|failed|unsupported)\\b")
    private val NEGATION_SIGNALS = listOf(
        "\u4e0d\u652f\u6301", "\u4e0d\u80fd", "\u65e0\u6cd5", "\u672a\u901a\u8fc7", "\u5931\u8d25"
    )
    private val STRUCTURED_FIELD_PATTERN = Regex(
        "(?i)\\b(claim|finding|source|url|date|published|updated|confidence)\\s*[:=]\\s*"
    )
    private val TRACKING_QUERY_KEYS = setOf("ref", "source", "fbclid", "gclid", "mc_cid", "mc_eid")
    private val IPV4_PATTERN = Regex("(?:\\d{1,3}\\.){3}\\d{1,3}")
    private val TWO_LEVEL_PUBLIC_SUFFIXES = setOf(
        "co.uk", "org.uk", "ac.uk", "com.cn", "net.cn", "org.cn", "com.au", "co.jp", "co.kr", "com.br"
    )
    private val OFFICIAL_AUTHORITIES = setOf(
        "openai.com", "android.com", "microsoft.com", "apple.com", "anthropic.com", "google.com",
        "github.com", "kotlinlang.org", "oracle.com", "python.org", "rust-lang.org", "nodejs.org",
        "mozilla.org", "w3.org"
    )
    private val NEWS_AUTHORITIES = setOf("reuters.com", "apnews.com", "bbc.com", "bloomberg.com")
    private val COMMUNITY_AUTHORITIES = setOf("stackoverflow.com", "reddit.com", "medium.com")
}
