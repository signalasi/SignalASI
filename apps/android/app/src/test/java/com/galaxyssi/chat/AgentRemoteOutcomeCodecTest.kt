package com.galaxyssi.chat

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class AgentRemoteOutcomeCodecTest {
    private fun payload() = JSONObject().put("source_message_id", "42").put("contact_id", "codex")
        .put("conversation_id", "conversation").put("turn_id", "turn").put("task_id", "task")

    @Test fun allTerminalStatusesOverrideMisleadingSuccessFlag() {
        for (status in AgentRemoteOutcomeCodec.TERMINAL) {
            val response = AgentRemoteOutcomeCodec.decode(payload().put("task_status", status)
                .put("success", status != "completed"), "actual result")!!
            assertEquals(status == "completed", response.success)
            assertEquals(status != "completed", response.remoteFailure)
        }
    }

    @Test fun legacyCloudReplyRetainsExplicitFailure() {
        val response = AgentRemoteOutcomeCodec.decode(payload().put("success", false), "HTTP 429")!!
        assertFalse(response.success)
        assertFalse(response.remoteFailure)
        assertEquals(1L, response.executionGeneration)
        assertEquals(-1L, response.statusSequence)
    }

    @Test fun runningAndUnknownStatusesCannotMasqueradeAsFinalReplies() {
        for (status in listOf("running", "accepted", "waiting_input", "invented")) {
            assertNull(AgentRemoteOutcomeCodec.decode(payload().put("task_status", status), "text"))
        }
    }

    @Test fun allRequiredResponseScopeFieldsMustBePresent() {
        for (field in listOf("source_message_id", "contact_id", "conversation_id", "turn_id", "task_id")) {
            assertNull(AgentRemoteOutcomeCodec.decode(payload().put(field, ""), "text"))
        }
    }

    @Test fun invalidGenerationsAndSequencesAreRejected() {
        for (value in listOf(0, -1, true, "2", 2.0, Long.MAX_VALUE, JSONObject.NULL)) {
            assertNull("generation=$value", AgentRemoteOutcomeCodec.version(payload().put("execution_generation", value)))
        }
        for (value in listOf(-2, true, "3", 3.0, JSONObject.NULL)) {
            assertNull("sequence=$value", AgentRemoteOutcomeCodec.version(payload().put("status_sequence", value)))
        }
    }

    @Test fun newGenerationCanRestartItsSequenceButOldGenerationCannotReturn() {
        val old = AgentRemoteExecutionVersion(1, 500)
        val fresh = AgentRemoteExecutionVersion(2, 1)
        assertTrue(old.accepts(fresh))
        assertEquals(fresh, old.advance(fresh))
        assertFalse(fresh.accepts(old))
        assertFalse(fresh.accepts(AgentRemoteExecutionVersion(1, 10000)))
    }

    @Test fun duplicateObservationDoesNotAdvanceOrRegressRevision() {
        val current = AgentRemoteExecutionVersion(2, 20)
        assertTrue(current.accepts(current))
        assertFalse(current.accepts(AgentRemoteExecutionVersion(2, 19)))
        assertTrue(current.accepts(AgentRemoteExecutionVersion(2, 21)))
        assertEquals(current, current.advance(AgentRemoteExecutionVersion(2, -1)))
    }

    @Test fun legacyMissingSequenceCannotDowngradeKnownRevision() {
        val current = AgentRemoteExecutionVersion(1, 100)
        assertTrue(current.accepts(AgentRemoteExecutionVersion(1, -1)))
        assertEquals(100L, current.advance(AgentRemoteExecutionVersion(1, -1)).sequence)
        assertEquals(AgentRemoteExecutionVersion(4, 9), AgentRemoteOutcomeCodec.version(
            payload().put("execution_generation", 4).put("status_seq", 9)))
    }

    @Test fun terminalStateAndGenerationSurviveResponseCodecRoundTrip() {
        for (status in AgentRemoteOutcomeCodec.TERMINAL) {
            val response = AgentRemoteOutcomeCodec.decode(payload().put("task_status", status)
                .put("execution_generation", 3).put("status_sequence", 28), "actual cause")!!
            assertEquals(response, AgentConnectorResponseCodec.decode(AgentConnectorResponseCodec.encode(response)))
        }
    }

    @Test fun retryHasIndependentInboxAndCompletionIdentity() {
        val old = AgentRemoteOutcomeCodec.decode(payload(), "old")!!
        val retry = old.copy(executionGeneration = 2)
        assertNotEquals(AgentConnectorResponseCodec.identity(old), AgentConnectorResponseCodec.identity(retry))
        assertEquals(AgentConnectorResponseCodec.scopeIdentity(old), AgentConnectorResponseCodec.scopeIdentity(retry))
        assertFalse(AgentConnectorResponseCodec.matches(old, retry))
        assertEquals("task", AgentRemoteOutcomeCodec.taskKey("task", 1))
        assertNotEquals(AgentRemoteOutcomeCodec.taskKey("task", 1), AgentRemoteOutcomeCodec.taskKey("task", 2))
    }

    @Test fun observedTerminalResultDoesNotPrematurelyCloseWorkspaceBeforeReplyPersistence() {
        for (status in AgentRemoteOutcomeCodec.FAILURES) {
            val observed = AgentRemoteRecoveryObservation("c", "d", status, "t", "r", 4,
                executionGeneration = 2, awaitingTerminalReply = true)
            assertEquals(status, observed.status)
            assertEquals(AgentWorkspaceStatus.WAITING_RESPONSE, observed.workspaceStatus)
            assertNotEquals(AgentWorkspaceStatus.WAITING_RESPONSE, observed.copy(awaitingTerminalReply = false).workspaceStatus)
        }
    }
}
