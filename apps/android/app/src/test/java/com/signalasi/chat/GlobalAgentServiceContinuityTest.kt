package com.signalasi.chat

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Test

class GlobalAgentServiceContinuityTest {
    @Test
    fun `recovery signals are coalesced while work is pending and during cooldown`() {
        val gate = GlobalAgentRecoverySignalGate(cooldownMillis = 2_000L)

        assertTrue(gate.tryAcquire(10_000L))
        assertFalse(gate.tryAcquire(10_100L))
        gate.release()
        assertFalse(gate.tryAcquire(11_999L))
        assertTrue(gate.tryAcquire(12_000L))
    }

    @Test
    fun `service recovery preserves an earlier durable work wake`() {
        assertEquals(
            1_030_000L,
            GlobalAgentServiceContinuityPolicy.recoveryWakeAt(
                nowMillis = 1_000_000L,
                scheduledWorkWakeAtMillis = 1_030_000L
            )
        )
    }

    @Test
    fun `service recovery bounds a distant or missing wake`() {
        assertEquals(
            1_060_000L,
            GlobalAgentServiceContinuityPolicy.recoveryWakeAt(
                nowMillis = 1_000_000L,
                scheduledWorkWakeAtMillis = 1_600_000L
            )
        )
        assertEquals(
            1_060_000L,
            GlobalAgentServiceContinuityPolicy.recoveryWakeAt(
                nowMillis = 1_000_000L,
                scheduledWorkWakeAtMillis = 0L
            )
        )
    }
}
