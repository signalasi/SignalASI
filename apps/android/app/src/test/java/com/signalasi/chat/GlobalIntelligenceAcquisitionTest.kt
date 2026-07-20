package com.signalasi.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneOffset

class GlobalIntelligenceAcquisitionTest {
    @Test
    fun researchPlanCarriesExecutableQueriesAndEvidenceRequirements() {
        val plan = GlobalResearchPlanBuilder.create(task(GlobalResearchDepth.DEEP_RESEARCH), NOW)

        assertEquals(GlobalResearchDepth.DEEP_RESEARCH, plan.depth)
        assertTrue(plan.units.all { it.queryCandidates.size == 3 })
        assertTrue(plan.units.all { it.minimumIndependentSources >= 1 })
        assertTrue(plan.units.all { it.freshnessWindowMillis > 0L })
        val primary = plan.units.first { it.purpose == GlobalResearchUnitPurpose.PRIMARY_EVIDENCE }
        assertTrue(GlobalEvidenceSourceKind.OFFICIAL in primary.requiredSourceKinds)
        assertTrue(primary.queryCandidates.any { it.contains("official documentation") })
    }

    @Test
    fun publisherSubdomainsCountAsOneIndependentAuthority() {
        val plan = completedPlan(
            GlobalResearchDepth.DEEP_RESEARCH,
            listOf(
                evidence(
                    "The platform documents a current page-size compatibility requirement.",
                    "https://developer.android.com/guide/practices/page-sizes?utm_source=test",
                    "2026-07-01"
                ),
                evidence(
                    "The platform documents a current page-size compatibility requirement.",
                    "https://source.android.com/docs/core/architecture/page-sizes",
                    "2026-07-02"
                )
            )
        )

        val ledger = GlobalEvidenceEvaluator.build(plan, NOW)

        assertEquals(1, ledger.independentSourceCount)
        assertEquals(setOf("android.com"), ledger.sources.map(GlobalEvidenceSource::authority).toSet())
        assertTrue(GlobalEvidenceQualityIssue.INSUFFICIENT_SOURCE_DIVERSITY in ledger.qualityIssues)
        assertFalse(ledger.verified)
    }

    @Test
    fun trackingParametersCannotCreateDuplicateEvidence() {
        val first = GlobalEvidenceEvaluator.canonicalUri("https://example.com/report?id=7&utm_source=a#summary")
        val second = GlobalEvidenceEvaluator.canonicalUri("https://EXAMPLE.com/report?id=7&utm_medium=b")

        assertEquals("https://example.com/report?id=7", first)
        assertEquals(first, second)
    }

    @Test
    fun continuousMonitoringRequiresDatedFreshEvidence() {
        val plan = completedPlan(
            GlobalResearchDepth.CONTINUOUS_MONITOR,
            listOf(
                evidence(
                    "The current release changes the supported runtime compatibility contract.",
                    "https://developer.android.com/about/versions/16/release-notes",
                    "2026-07-10"
                ),
                evidence(
                    "The current release changes the supported runtime compatibility contract.",
                    "https://reuters.com/technology/runtime-release-analysis",
                    "2024-01-01"
                )
            )
        )

        val ledger = GlobalEvidenceEvaluator.build(plan, NOW)

        assertEquals(1, ledger.freshSourceCount)
        assertEquals(1, ledger.staleSourceCount)
        assertTrue(ledger.sources.any { it.publishedAtMillis > 0L })
        assertTrue(ledger.verified)
    }

    @Test
    fun staleMonitoringEvidenceFailsTheFreshnessGate() {
        val plan = completedPlan(
            GlobalResearchDepth.CONTINUOUS_MONITOR,
            listOf(
                evidence(
                    "The documented release changes the supported runtime compatibility contract.",
                    "https://developer.android.com/about/versions/16/release-notes",
                    "2024-01-01"
                ),
                evidence(
                    "The documented release changes the supported runtime compatibility contract.",
                    "https://reuters.com/technology/runtime-release-analysis",
                    "2024-02-01"
                )
            )
        )

        val ledger = GlobalEvidenceEvaluator.build(plan, NOW)

        assertEquals(0, ledger.freshSourceCount)
        assertEquals(2, ledger.staleSourceCount)
        assertTrue(GlobalEvidenceQualityIssue.FRESH_EVIDENCE_MISSING in ledger.qualityIssues)
        assertFalse(ledger.verified)
    }

    @Test
    fun repeatedCitationOfOneUrlIsNotIndependentCorroboration() {
        val sharedUrl = "https://developer.android.com/guide/practices/page-sizes"
        val plan = completedPlan(
            GlobalResearchDepth.DEEP_RESEARCH,
            listOf(
                evidence("The compatibility requirement applies to packaged native libraries.", sharedUrl, "2026-06-01"),
                evidence("The compatibility requirement applies to packaged native libraries.", sharedUrl, "2026-06-01")
            )
        )

        val ledger = GlobalEvidenceEvaluator.build(plan, NOW)

        assertEquals(1, ledger.independentSourceCount)
        assertEquals(0, ledger.corroboratedClaimCount)
        assertTrue(GlobalEvidenceQualityIssue.CLAIMS_NOT_CORROBORATED in ledger.qualityIssues)
        assertFalse(ledger.verified)
    }

    @Test
    fun qualityGateCreatesTargetedFollowUpEvidenceUnits() {
        val initial = GlobalResearchPlanBuilder.create(task(GlobalResearchDepth.DEEP_RESEARCH), NOW)
        val completed = initial.copy(units = initial.units.mapIndexed { index, unit ->
            if (index == 0) unit.copy(
                status = GlobalResearchUnitStatus.COMPLETED,
                result = evidence(
                    "A community report claims the runtime behavior changed for packaged libraries.",
                    "https://reddit.com/r/androiddev/comments/example",
                    "2026-07-01"
                )
            ) else unit.copy(status = GlobalResearchUnitStatus.FAILED)
        })
        val researchTask = task(GlobalResearchDepth.DEEP_RESEARCH)
        val ledger = GlobalEvidenceEvaluator.build(completed, NOW)

        val expanded = GlobalResearchPlanBuilder.closeCollection(researchTask, completed, ledger, NOW + 1L)

        assertEquals(GlobalResearchPlanPhase.COLLECTING, expanded.phase)
        assertEquals(1, expanded.qualityExpansionCount)
        assertTrue(expanded.pendingUnits().isNotEmpty())
        assertTrue(expanded.pendingUnits().any { it.purpose == GlobalResearchUnitPurpose.PRIMARY_EVIDENCE })
        assertTrue(expanded.pendingUnits().any { it.purpose == GlobalResearchUnitPurpose.CORROBORATION })
        assertTrue(expanded.pendingUnits().all { it.queryCandidates.isNotEmpty() })
    }

    @Test
    fun qualityExpansionHasABoundedFallbackToSynthesis() {
        val researchTask = task(GlobalResearchDepth.DEEP_RESEARCH)
        val initial = GlobalResearchPlanBuilder.create(researchTask, NOW)
        val completed = initial.copy(
            qualityExpansionCount = 2,
            units = initial.units.mapIndexed { index, unit ->
                if (index == 0) unit.copy(
                    status = GlobalResearchUnitStatus.COMPLETED,
                    result = evidence(
                        "A single report describes a possible runtime compatibility change.",
                        "https://example.com/runtime-report",
                        "2026-07-01"
                    )
                ) else unit.copy(status = GlobalResearchUnitStatus.FAILED)
            }
        )
        val ledger = GlobalEvidenceEvaluator.build(completed, NOW)

        val closed = GlobalResearchPlanBuilder.closeCollection(researchTask, completed, ledger, NOW + 1L)

        assertEquals(GlobalResearchPlanPhase.SYNTHESIS_PENDING, closed.phase)
        assertEquals(completed.units.size, closed.units.size)
        assertFalse(ledger.verified)
    }

    @Test
    fun contradictoryClaimsRemainExplicitlyContested() {
        val plan = completedPlan(
            GlobalResearchDepth.DEEP_RESEARCH,
            listOf(
                evidence(
                    "The runtime supports sixteen kilobyte pages on every current device model.",
                    "https://developer.android.com/guide/practices/page-sizes",
                    "2026-07-01"
                ),
                evidence(
                    "The runtime does not support sixteen kilobyte pages on every current device model.",
                    "https://github.com/android/ndk/issues/2000",
                    "2026-07-02"
                )
            )
        )

        val ledger = GlobalEvidenceEvaluator.build(plan, NOW)

        assertTrue(ledger.contestedClaimCount >= 2)
        assertTrue(GlobalEvidenceQualityIssue.UNRESOLVED_CONTRADICTIONS in ledger.qualityIssues)
        assertFalse(ledger.verified)
    }

    @Test
    fun unrelatedPublishersRemainIndependent() {
        assertNotEquals(
            GlobalEvidenceEvaluator.sourceAuthority("https://developer.android.com/guide"),
            GlobalEvidenceEvaluator.sourceAuthority("https://github.com/android/ndk")
        )
    }

    private fun completedPlan(depth: GlobalResearchDepth, results: List<String>): GlobalResearchPlan {
        val initial = GlobalResearchPlanBuilder.create(task(depth), NOW)
        return initial.copy(units = initial.units.mapIndexed { index, unit ->
            if (index < results.size) unit.copy(
                status = GlobalResearchUnitStatus.COMPLETED,
                result = results[index],
                evidenceUris = GlobalEvidenceEvaluator.extractUrls(results[index]),
                completedAtMillis = NOW
            ) else unit.copy(status = GlobalResearchUnitStatus.FAILED)
        })
    }

    private fun task(depth: GlobalResearchDepth) = GlobalResearchTask(
        id = "research-${depth.name.lowercase()}",
        sourceEventId = "event-1",
        sourceConversationId = "conversation-1",
        topic = "Runtime compatibility",
        question = "Assess current Android runtime compatibility requirements",
        depth = depth,
        preferredSources = listOf("official", "primary", "repository"),
        createdAtMillis = NOW,
        updatedAtMillis = NOW
    )

    private fun evidence(claim: String, url: String, date: String): String =
        "CLAIM: $claim | SOURCE: $url | DATE: $date"

    private companion object {
        val NOW: Long = LocalDate.of(2026, 7, 20).atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli()
    }
}
