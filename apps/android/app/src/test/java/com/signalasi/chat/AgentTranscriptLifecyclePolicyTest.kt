package com.signalasi.chat

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentTranscriptLifecyclePolicyTest {
    @Test
    fun removesOnlyLegacyPlannerProcessRows() {
        assertTrue(
            AgentTranscriptLifecyclePolicy.isObsoletePlannerProcessEntry(
                AgentTranscriptRole.PROCESS,
                "pending:plan:ask-codex:1"
            )
        )
        assertFalse(
            AgentTranscriptLifecyclePolicy.isObsoletePlannerProcessEntry(
                AgentTranscriptRole.USER,
                "pending:plan:user-text:1"
            )
        )
        assertFalse(
            AgentTranscriptLifecyclePolicy.isObsoletePlannerProcessEntry(
                AgentTranscriptRole.PROCESS,
                "connector-task:task-id"
            )
        )
    }
}
