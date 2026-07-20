package com.signalasi.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GlobalModelCallBudgetTest {
    @Test
    fun `acquisition records one dispatch and one active lease`() {
        val decision = acquire(GlobalModelCallBudgetState(), "call-1")

        assertTrue(decision.granted)
        assertEquals(1, decision.state.dispatches.size)
        assertEquals(1, decision.state.activeLeases.size)
        assertEquals("call-1", decision.state.activeLeases.single().ownerKey)
    }

    @Test
    fun `duplicate active acquisition is idempotent`() {
        val first = acquire(GlobalModelCallBudgetState(), "call-1")
        val second = acquire(first.state, "call-1")

        assertTrue(second.granted)
        assertEquals(first.leaseId, second.leaseId)
        assertEquals(1, second.state.dispatches.size)
        assertEquals(1, second.state.activeLeases.size)
    }

    @Test
    fun `concurrency limit returns the earliest lease expiry`() {
        val first = acquire(GlobalModelCallBudgetState(), "call-1", concurrencyLimit = 1)
        val second = acquire(first.state, "call-2", concurrencyLimit = 1)

        assertFalse(second.granted)
        assertEquals(GlobalModelCallBudgetDenial.CONCURRENCY_LIMIT, second.denial)
        assertEquals(NOW + LEASE_MILLIS, second.nextEligibleAtMillis)
        assertEquals(1, second.state.dispatches.size)
    }

    @Test
    fun `release frees concurrency without refunding daily usage`() {
        val first = acquire(GlobalModelCallBudgetState(), "call-1", concurrencyLimit = 1)
        val released = GlobalModelCallBudgetPolicy.release(first.state, first.leaseId, NOW + 100L)
        val second = acquire(released, "call-2", concurrencyLimit = 1, nowMillis = NOW + 100L)

        assertTrue(second.granted)
        assertEquals(2, second.state.dispatches.size)
        assertEquals(1, second.state.activeLeases.size)
    }

    @Test
    fun `released dispatch cannot be issued twice with the same deterministic lease id`() {
        val first = acquire(GlobalModelCallBudgetState(), "call-1")
        val released = GlobalModelCallBudgetPolicy.release(first.state, first.leaseId, NOW + 100L)
        val duplicate = acquire(released, "call-1", nowMillis = NOW + 100L)

        assertFalse(duplicate.granted)
        assertEquals(GlobalModelCallBudgetDenial.DUPLICATE_DISPATCH, duplicate.denial)
        assertEquals(1, duplicate.state.dispatches.size)
    }

    @Test
    fun `daily limit is rolling and exposes exact next eligibility`() {
        val first = acquire(GlobalModelCallBudgetState(), "call-1", dailyLimit = 1)
        val released = GlobalModelCallBudgetPolicy.release(first.state, first.leaseId, NOW + 100L)
        val denied = acquire(released, "call-2", dailyLimit = 1, nowMillis = NOW + 100L)

        assertFalse(denied.granted)
        assertEquals(GlobalModelCallBudgetDenial.DAILY_LIMIT, denied.denial)
        assertEquals(NOW + GlobalModelCallBudgetPolicy.WINDOW_MILLIS, denied.nextEligibleAtMillis)

        val afterWindow = acquire(
            denied.state,
            "call-2",
            dailyLimit = 1,
            nowMillis = NOW + GlobalModelCallBudgetPolicy.WINDOW_MILLIS + 1L
        )
        assertTrue(afterWindow.granted)
        assertEquals(1, afterWindow.state.dispatches.size)
    }

    @Test
    fun `expired leases recover concurrency after process restart`() {
        val first = acquire(GlobalModelCallBudgetState(), "call-1", concurrencyLimit = 1)
        val second = acquire(
            first.state,
            "call-2",
            concurrencyLimit = 1,
            nowMillis = NOW + LEASE_MILLIS + 1L
        )

        assertTrue(second.granted)
        assertEquals(1, second.state.activeLeases.size)
        assertEquals("call-2", second.state.activeLeases.single().ownerKey)
        assertEquals(2, second.state.dispatches.size)
    }

    @Test
    fun `cancel refunds a dispatch that was never published`() {
        val first = acquire(GlobalModelCallBudgetState(), "call-1", dailyLimit = 1)
        val cancelled = GlobalModelCallBudgetPolicy.cancel(first.state, first.leaseId, NOW + 100L)
        val second = acquire(cancelled, "call-2", dailyLimit = 1, nowMillis = NOW + 100L)

        assertTrue(second.granted)
        assertEquals(1, second.state.dispatches.size)
    }

    @Test
    fun `all model call kinds share one global budget`() {
        val cognition = acquire(
            GlobalModelCallBudgetState(),
            "cognition",
            kind = GlobalModelCallKind.COGNITION,
            dailyLimit = 2
        )
        val research = acquire(
            GlobalModelCallBudgetPolicy.release(cognition.state, cognition.leaseId, NOW + 1L),
            "research",
            kind = GlobalModelCallKind.RESEARCH_EVIDENCE,
            dailyLimit = 2,
            nowMillis = NOW + 1L
        )
        val review = acquire(
            GlobalModelCallBudgetPolicy.release(research.state, research.leaseId, NOW + 2L),
            "review",
            kind = GlobalModelCallKind.PLAN_REVIEW,
            dailyLimit = 2,
            nowMillis = NOW + 2L
        )

        assertFalse(review.granted)
        assertEquals(GlobalModelCallBudgetDenial.DAILY_LIMIT, review.denial)
    }

    @Test
    fun `availability inspection never consumes a dispatch`() {
        val first = acquire(GlobalModelCallBudgetState(), "call-1", concurrencyLimit = 1)
        val busy = GlobalModelCallBudgetPolicy.availability(
            first.state,
            dailyLimit = 48,
            concurrencyLimit = 1,
            nowMillis = NOW + 100L
        )

        assertFalse(busy.granted)
        assertEquals(GlobalModelCallBudgetDenial.CONCURRENCY_LIMIT, busy.denial)
        assertEquals(1, busy.state.dispatches.size)
        assertEquals(1, busy.state.activeLeases.size)
    }

    @Test
    fun `completion replaces reservation with provider usage and reported cost`() {
        val acquired = acquire(
            GlobalModelCallBudgetState(),
            "call-1",
            resourceId = "cloud-model:primary",
            estimatedInputTokens = 120L
        )
        val completed = GlobalModelCallBudgetPolicy.complete(
            state = acquired.state,
            leaseId = acquired.leaseId,
            inputTokens = 180L,
            outputTokens = 45L,
            reportedCostMicros = 2_500L,
            responseText = "done",
            nowMillis = NOW + 500L
        )

        val dispatch = completed.dispatches.single()
        assertEquals(180L, dispatch.inputTokens)
        assertEquals(45L, dispatch.outputTokens)
        assertEquals(225L, dispatch.totalTokens)
        assertEquals(2_500L, dispatch.reportedCostMicros)
        assertFalse(dispatch.usageEstimated)
        assertEquals(NOW + 500L, dispatch.completedAtMillis)
        assertTrue(completed.activeLeases.isEmpty())
    }

    @Test
    fun `completion estimates missing provider usage without inventing cost`() {
        val acquired = acquire(
            GlobalModelCallBudgetState(),
            "call-1",
            estimatedInputTokens = 80L
        )
        val completed = GlobalModelCallBudgetPolicy.complete(
            acquired.state,
            acquired.leaseId,
            inputTokens = 0L,
            outputTokens = 0L,
            reportedCostMicros = 0L,
            responseText = "A useful answer",
            nowMillis = NOW + 500L
        )

        val dispatch = completed.dispatches.single()
        assertEquals(80L, dispatch.inputTokens)
        assertTrue(dispatch.outputTokens > 0L)
        assertEquals(0L, dispatch.reportedCostMicros)
        assertTrue(dispatch.usageEstimated)
    }

    @Test
    fun `token budget is shared and exposes rolling eligibility`() {
        val first = acquire(
            GlobalModelCallBudgetState(),
            "call-1",
            estimatedInputTokens = 9_000L,
            dailyTokenLimit = 10_000L
        )
        val completed = GlobalModelCallBudgetPolicy.complete(
            first.state,
            first.leaseId,
            inputTokens = 9_000L,
            outputTokens = 900L,
            reportedCostMicros = 0L,
            responseText = "done",
            nowMillis = NOW + 100L
        )
        val denied = acquire(
            completed,
            "call-2",
            estimatedInputTokens = 500L,
            dailyTokenLimit = 10_000L,
            nowMillis = NOW + 200L
        )

        assertFalse(denied.granted)
        assertEquals(GlobalModelCallBudgetDenial.TOKEN_LIMIT, denied.denial)
        assertEquals(NOW + GlobalModelCallBudgetPolicy.WINDOW_MILLIS, denied.nextEligibleAtMillis)
    }

    @Test
    fun `first oversized request is admitted then blocks later calls`() {
        val oversized = acquire(
            GlobalModelCallBudgetState(),
            "call-1",
            estimatedInputTokens = 20_000L,
            dailyTokenLimit = 10_000L
        )
        assertTrue(oversized.granted)

        val released = GlobalModelCallBudgetPolicy.release(oversized.state, oversized.leaseId, NOW + 1L)
        val next = acquire(
            released,
            "call-2",
            estimatedInputTokens = 1L,
            dailyTokenLimit = 10_000L,
            nowMillis = NOW + 2L
        )
        assertFalse(next.granted)
        assertEquals(GlobalModelCallBudgetDenial.TOKEN_LIMIT, next.denial)
    }

    @Test
    fun `reported cost cap ignores unknown price and blocks reported spend`() {
        val first = acquire(
            GlobalModelCallBudgetState(),
            "call-1",
            dailyReportedCostLimitMicros = 10_000L
        )
        val completed = GlobalModelCallBudgetPolicy.complete(
            first.state,
            first.leaseId,
            inputTokens = 10L,
            outputTokens = 5L,
            reportedCostMicros = 10_000L,
            responseText = "done",
            nowMillis = NOW + 100L
        )
        val denied = acquire(
            completed,
            "call-2",
            dailyReportedCostLimitMicros = 10_000L,
            nowMillis = NOW + 200L
        )

        assertFalse(denied.granted)
        assertEquals(GlobalModelCallBudgetDenial.REPORTED_COST_LIMIT, denied.denial)
        assertEquals(NOW + GlobalModelCallBudgetPolicy.WINDOW_MILLIS, denied.nextEligibleAtMillis)
    }

    @Test
    fun `resource usage aggregates only matching dispatches`() {
        val first = acquire(GlobalModelCallBudgetState(), "call-1", resourceId = "model-a")
        val firstDone = GlobalModelCallBudgetPolicy.complete(
            first.state, first.leaseId, 100L, 20L, 1_000L, "a", NOW + 1L
        )
        val second = acquire(firstDone, "call-2", resourceId = "model-a", nowMillis = NOW + 2L)
        val secondDone = GlobalModelCallBudgetPolicy.complete(
            second.state, second.leaseId, 200L, 40L, 3_000L, "b", NOW + 3L
        )
        val third = acquire(secondDone, "call-3", resourceId = "model-b", nowMillis = NOW + 4L)

        val usage = GlobalModelCallBudgetPolicy.resourceUsage(third.state.dispatches, "model-a")
        assertEquals(2, usage.dispatches)
        assertEquals(150L, usage.averageInputTokens)
        assertEquals(30L, usage.averageOutputTokens)
        assertEquals(180L, usage.averageTotalTokens)
        assertEquals(2_000L, usage.averageReportedCostMicros)
    }

    @Test
    fun `usage totals saturate instead of overflowing`() {
        val dispatches = listOf(
            GlobalModelCallDispatch("a", GlobalModelCallKind.COGNITION, NOW, inputTokens = Long.MAX_VALUE),
            GlobalModelCallDispatch("b", GlobalModelCallKind.COGNITION, NOW + 1L, outputTokens = Long.MAX_VALUE)
        )

        assertEquals(Long.MAX_VALUE, GlobalModelCallBudgetPolicy.totalTokens(dispatches))
    }

    private fun acquire(
        state: GlobalModelCallBudgetState,
        ownerKey: String,
        kind: GlobalModelCallKind = GlobalModelCallKind.COGNITION,
        dailyLimit: Int = 48,
        concurrencyLimit: Int = 3,
        nowMillis: Long = NOW,
        resourceId: String = "",
        estimatedInputTokens: Long = 0L,
        dailyTokenLimit: Long = GlobalModelCallBudgetPolicy.MAX_DAILY_TOKEN_LIMIT,
        dailyReportedCostLimitMicros: Long = 0L
    ): GlobalModelCallBudgetDecision = GlobalModelCallBudgetPolicy.acquire(
        state = state,
        leaseId = GlobalModelCallBudgetStore.leaseId(kind, ownerKey),
        kind = kind,
        ownerKey = ownerKey,
        leaseMillis = LEASE_MILLIS,
        dailyLimit = dailyLimit,
        concurrencyLimit = concurrencyLimit,
        nowMillis = nowMillis,
        resourceId = resourceId,
        estimatedInputTokens = estimatedInputTokens,
        dailyTokenLimit = dailyTokenLimit,
        dailyReportedCostLimitMicros = dailyReportedCostLimitMicros
    )

    private companion object {
        const val NOW = 1_000_000L
        const val LEASE_MILLIS = 60_000L
    }
}
