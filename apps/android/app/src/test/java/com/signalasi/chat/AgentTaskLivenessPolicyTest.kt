package com.signalasi.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentTaskLivenessPolicyTest {
    private val policy = AgentTaskLivenessPolicy(
        queuedWarningMillis = 10L,
        queuedTimeoutMillis = 20L,
        runningWarningMillis = 100L,
        runningTimeoutMillis = 200L,
        waitingResponseWarningMillis = 300L,
        waitingResponseTimeoutMillis = 400L,
        absoluteTimeoutMillis = 1_000L,
        watchdogIntervalMillis = 60_000L,
        heartbeatWriteThrottleMillis = 0L
    )

    @Test
    fun runningTaskWarnsBeforeHardTimeout() {
        val workspace = workspace(
            status = AgentWorkspaceStatus.RUNNING,
            events = listOf(event(1L, AgentTaskEventKinds.RUNNING, 1_000L))
        )

        assertEquals(
            AgentTaskLivenessState.HEALTHY,
            policy.evaluate(workspace, 1_099L).state
        )
        assertEquals(
            AgentTaskLivenessState.STALLED,
            policy.evaluate(workspace, 1_100L).state
        )
        assertEquals(
            AgentTaskLivenessState.TIMED_OUT,
            policy.evaluate(workspace, 1_200L).state
        )
    }

    @Test
    fun progressAfterStallClearsUnresolvedWarning() {
        val stalled = workspace(
            status = AgentWorkspaceStatus.RUNNING,
            events = listOf(
                event(1L, AgentTaskEventKinds.RUNNING, 1_000L),
                event(2L, AgentTaskEventKinds.STALLED, 1_100L)
            )
        )
        val recovered = stalled.copy(
            eventSequence = 3L,
            eventJournal = stalled.eventJournal + event(3L, AgentTaskEventKinds.PROGRESS, 1_110L)
        )

        assertTrue(policy.hasUnresolvedStall(stalled))
        assertFalse(policy.hasUnresolvedStall(recovered))
        assertEquals(
            AgentTaskLivenessState.HEALTHY,
            policy.evaluate(recovered, 1_150L).state
        )
    }

    @Test
    fun userControlledWaitsDoNotTimeOut() {
        listOf(
            AgentWorkspaceStatus.WAITING_CONFIRMATION,
            AgentWorkspaceStatus.PAUSED,
            AgentWorkspaceStatus.BLOCKED
        ).forEach { status ->
            assertEquals(
                AgentTaskLivenessState.HEALTHY,
                policy.evaluate(workspace(status), 10_000L).state
            )
        }
    }

    @Test
    fun absoluteDeadlineStopsOtherwiseActiveTask() {
        val workspace = workspace(
            status = AgentWorkspaceStatus.RUNNING,
            events = listOf(event(1L, AgentTaskEventKinds.PROGRESS, 1_950L))
        )

        val decision = policy.evaluate(workspace, 2_000L)

        assertEquals(AgentTaskLivenessState.TIMED_OUT, decision.state)
        assertEquals("absolute_deadline_exceeded", decision.reason)
    }

    private fun workspace(
        status: AgentWorkspaceStatus,
        events: List<AgentWorkspaceEvent> = emptyList()
    ) = AgentWorkspace(
        workspaceId = "workspace",
        sessionId = "session",
        conversationId = "conversation",
        taskId = "task",
        status = status,
        eventSequence = events.maxOfOrNull(AgentWorkspaceEvent::sequence) ?: 0L,
        eventJournal = events,
        createdAtMillis = 1_000L,
        updatedAtMillis = events.maxOfOrNull(AgentWorkspaceEvent::timestampMillis) ?: 1_000L
    )

    private fun event(sequence: Long, kind: String, timestamp: Long) = AgentWorkspaceEvent(
        sequence = sequence,
        kind = kind,
        timestampMillis = timestamp
    )
}
