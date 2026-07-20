package com.signalasi.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GlobalAgentContinuityTest {
    @Test
    fun queueSpillsToDurableOverflowWithoutDroppingOldestEvents() {
        val initial = GlobalEventQueueState(ready = listOf(event("a"), event("b")))

        val mutation = GlobalEventQueuePolicy.enqueue(
            initial,
            listOf(event("c"), event("d"), event("e")),
            readyCapacity = 3,
            overflowCapacity = 3
        )

        assertEquals(listOf("a", "b", "c"), mutation.state.ready.map(GlobalConversationEvent::id))
        assertEquals(listOf("d", "e"), mutation.state.overflow.map(GlobalConversationEvent::id))
        assertEquals(3, mutation.acceptedCount)
        assertTrue(mutation.capacityRejected.isEmpty())
    }

    @Test
    fun queueDeduplicatesAcrossReadyOverflowAndDeadLetters() {
        val initial = GlobalEventQueueState(
            ready = listOf(event("a")),
            overflow = listOf(event("b"))
        )

        val mutation = GlobalEventQueuePolicy.enqueue(
            initial,
            listOf(event("a"), event("b"), event("dead"), event("c")),
            deadLetterEventIds = setOf("dead"),
            readyCapacity = 2,
            overflowCapacity = 2
        )

        assertEquals(1, mutation.acceptedCount)
        assertEquals(listOf("a", "c"), mutation.state.ready.map(GlobalConversationEvent::id))
        assertEquals(listOf("b"), mutation.state.overflow.map(GlobalConversationEvent::id))
    }

    @Test
    fun completedEventsPromoteOverflowInOriginalOrder() {
        val state = GlobalEventQueueState(
            ready = listOf(event("a"), event("b"), event("c")),
            overflow = listOf(event("d"), event("e"))
        )

        val next = GlobalEventQueuePolicy.removeAndPromote(state, setOf("a", "c"), readyCapacity = 3)

        assertEquals(listOf("b", "d", "e"), next.ready.map(GlobalConversationEvent::id))
        assertTrue(next.overflow.isEmpty())
    }

    @Test
    fun capacityPressureReturnsRejectedEventsForDeadLetterPreservation() {
        val mutation = GlobalEventQueuePolicy.enqueue(
            GlobalEventQueueState(ready = listOf(event("a")), overflow = listOf(event("b"))),
            listOf(event("c"), event("d")),
            readyCapacity = 1,
            overflowCapacity = 1
        )

        assertEquals(2, mutation.acceptedCount)
        assertEquals(listOf("c", "d"), mutation.capacityRejected.map(GlobalConversationEvent::id))
        assertEquals(listOf("a"), mutation.state.ready.map(GlobalConversationEvent::id))
        assertEquals(listOf("b"), mutation.state.overflow.map(GlobalConversationEvent::id))
    }

    @Test
    fun eventFailureUsesBackoffThenQuarantinesTheThirdAttempt() {
        val first = GlobalEventRetryPolicy.recordFailure("event", null, IllegalStateException("first failure"), 1_000L)
        val second = GlobalEventRetryPolicy.recordFailure("event", first, IllegalStateException("second failure"), 40_000L)
        val third = GlobalEventRetryPolicy.recordFailure("event", second, IllegalStateException("third failure"), 200_000L)

        assertEquals(1, first.attemptCount)
        assertEquals(31_000L, first.nextAttemptAtMillis)
        assertFalse(first.quarantined)
        assertEquals(2, second.attemptCount)
        assertEquals(160_000L, second.nextAttemptAtMillis)
        assertFalse(second.quarantined)
        assertEquals(3, third.attemptCount)
        assertEquals(0L, third.nextAttemptAtMillis)
        assertTrue(third.quarantined)
        assertEquals(first.firstFailedAtMillis, third.firstFailedAtMillis)
    }

    @Test
    fun delayedFailuresDoNotBlockOtherReadyEvents() {
        val failure = GlobalEventRetryPolicy.recordFailure(
            "delayed",
            null,
            IllegalArgumentException("temporary"),
            1_000L
        )

        assertFalse(GlobalEventRetryPolicy.eligible(failure, 20_000L))
        assertTrue(GlobalEventRetryPolicy.eligible(failure, 31_000L))
        assertTrue(GlobalEventRetryPolicy.eligible(null, 20_000L))
    }

    @Test
    fun failureReasonIsCompactAndStableWithoutAStackTrace() {
        val first = GlobalEventRetryPolicy.recordFailure(
            "event",
            null,
            IllegalStateException("bad\n  payload"),
            1_000L
        )
        val second = GlobalEventRetryPolicy.recordFailure(
            "event",
            null,
            IllegalStateException("bad payload"),
            2_000L
        )

        assertEquals("bad payload", first.reason)
        assertEquals(first.errorFingerprint, second.errorFingerprint)
        assertFalse(first.reason.contains("IllegalStateException"))
    }

    private fun event(id: String) = GlobalConversationEvent(
        id = id,
        type = GlobalConversationEventType.MESSAGE_CREATED,
        conversationId = "conversation",
        actor = GlobalConversationActor.USER,
        content = "content-$id"
    )
}
