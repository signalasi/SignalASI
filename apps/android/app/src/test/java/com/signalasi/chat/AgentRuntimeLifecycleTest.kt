package com.signalasi.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class AgentRuntimeLifecycleTest {
    @Test
    fun `startup failures back off exponentially and readiness resets failures`() {
        var now = 10_000L
        val machine = AgentRuntimeLifecycleStateMachine(AgentRuntimeLifecycleClock { now })

        assertEquals(AgentRuntimeLifecyclePhase.STARTING, machine.beginStart("qemu", force = false).phase)
        val firstFailure = machine.failed("qemu", "first failure")
        assertEquals(AgentRuntimeLifecyclePhase.BACKING_OFF, firstFailure.phase)
        assertEquals(1, firstFailure.consecutiveFailures)
        assertEquals(now + 1_000L, firstFailure.nextAttemptAtMillis)

        assertEquals(
            AgentRuntimeLifecyclePhase.BACKING_OFF,
            machine.beginStart("qemu", force = false).phase
        )
        now += 1_000L
        assertEquals(AgentRuntimeLifecyclePhase.STARTING, machine.beginStart("qemu", force = false).phase)
        val secondFailure = machine.failed("qemu", "second failure")
        assertEquals(now + 2_000L, secondFailure.nextAttemptAtMillis)
        assertEquals(2, secondFailure.consecutiveFailures)

        val forced = machine.beginStart("qemu", force = true)
        assertEquals(AgentRuntimeLifecyclePhase.STARTING, forced.phase)
        val ready = machine.ready("qemu")
        assertEquals(AgentRuntimeLifecyclePhase.READY, ready.phase)
        assertEquals(0, ready.consecutiveFailures)
        assertEquals(0L, ready.nextAttemptAtMillis)
        assertEquals(now, ready.lastReadyAtMillis)
    }

    @Test
    fun `blocked state is explicit and user stop clears retry history`() {
        var now = 20_000L
        val machine = AgentRuntimeLifecycleStateMachine(AgentRuntimeLifecycleClock { now })
        machine.beginStart("qemu", force = false)
        machine.failed("qemu", "failed")
        now += 100L

        val blocked = machine.blocked("linux-base is not installed")
        assertEquals(AgentRuntimeLifecyclePhase.BLOCKED, blocked.phase)
        assertEquals(1, blocked.consecutiveFailures)
        assertEquals(0L, blocked.nextAttemptAtMillis)

        val stopped = machine.stopped("stopped by user", resetFailures = true)
        assertEquals(AgentRuntimeLifecyclePhase.STOPPED, stopped.phase)
        assertEquals(0, stopped.consecutiveFailures)
    }

    @Test
    fun `launch specification never exposes the guest authentication key`() {
        val key = ByteArray(32) { 7 }
        val spec = AgentRuntimeEngineLaunchSpec(
            engineFile = File("engine"),
            baseImageFile = File("base"),
            socketFile = File("socket"),
            packsDirectory = File("packs"),
            workspacesDirectory = File("workspaces"),
            architecture = "arm64-v8a",
            sessionKey = key
        )

        assertTrue(spec.toString().contains("[redacted]"))
        assertFalse(spec.toString().contains("7, 7"))
        spec.clearSecrets()
        assertTrue(key.all { it == 0.toByte() })
    }
}
