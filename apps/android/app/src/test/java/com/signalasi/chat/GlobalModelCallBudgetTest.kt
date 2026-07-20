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

    private fun acquire(
        state: GlobalModelCallBudgetState,
        ownerKey: String,
        kind: GlobalModelCallKind = GlobalModelCallKind.COGNITION,
        dailyLimit: Int = 48,
        concurrencyLimit: Int = 3,
        nowMillis: Long = NOW
    ): GlobalModelCallBudgetDecision = GlobalModelCallBudgetPolicy.acquire(
        state = state,
        leaseId = GlobalModelCallBudgetStore.leaseId(kind, ownerKey),
        kind = kind,
        ownerKey = ownerKey,
        leaseMillis = LEASE_MILLIS,
        dailyLimit = dailyLimit,
        concurrencyLimit = concurrencyLimit,
        nowMillis = nowMillis
    )

    private companion object {
        const val NOW = 1_000_000L
        const val LEASE_MILLIS = 60_000L
    }
}
