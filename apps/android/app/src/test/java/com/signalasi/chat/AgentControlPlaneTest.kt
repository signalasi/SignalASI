package com.signalasi.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentControlPlaneTest {
    @Test
    fun protocolNegotiationSelectsHighestCompatibleVersionAndCommonFeatures() {
        val local = AgentProtocolRange(
            preferred = "1.3",
            minimum = "1.0",
            maximum = "1.4",
            features = setOf("run.cancel", "run.recover", "message.observe")
        )
        val remote = AgentProtocolRange(
            preferred = "1.2",
            minimum = "1.1",
            maximum = "1.2",
            features = setOf("run.cancel", "message.observe", "provider.list")
        )

        val agreement = AgentProtocolNegotiator.negotiate(local, remote)!!

        assertEquals("1.2", agreement.version)
        assertEquals(setOf("run.cancel", "message.observe"), agreement.features)
    }

    @Test
    fun incompatibleProtocolRangesAreRejected() {
        assertNull(
            AgentProtocolNegotiator.negotiate(
                AgentProtocolRange("1.0", "1.0", "1.1"),
                AgentProtocolRange("2.0", "2.0", "2.2")
            )
        )
    }

    @Test
    fun runStateReducerPreservesTerminalStateUntilExplicitRecovery() {
        val completed = AgentRunEventStore.reduce(
            AgentRunControlState.RUNNING,
            AgentRunControlEventType.RUN_COMPLETED
        )
        val ignoredLateProgress = AgentRunEventStore.reduce(completed, AgentRunControlEventType.TOOL_PROGRESS)
        val recovered = AgentRunEventStore.reduce(completed, AgentRunControlEventType.RUN_RECOVERED)

        assertEquals(AgentRunControlState.COMPLETED, ignoredLateProgress)
        assertEquals(AgentRunControlState.RUNNING, recovered)
    }

    @Test
    fun currentConnectorRegistryProjectsToUnifiedRegistrations() {
        val registrations = StaticAgentConnectorRegistry().registrations()
        val codex = registrations.first { it.agentId == "codex" }

        assertEquals(AgentConnectionKind.SIGNALASI_LINK, codex.connectionKind)
        assertEquals(AgentResourceTrust.VERIFIED_PAIRED, codex.trust)
        assertTrue(AgentCapability.CODE in codex.capabilities)
        assertTrue("message.observe" in codex.protocol.features)
    }

    @Test
    fun deliveryModesKeepResponseAndObservationSemanticsSeparate() {
        assertEquals(3, AgentDeliveryMode.entries.size)
        assertTrue(AgentDeliveryMode.RESPOND != AgentDeliveryMode.OBSERVE)
        assertTrue(AgentDeliveryMode.IGNORE != AgentDeliveryMode.RESPOND)
    }

    @Test
    fun structuredHandoffPreservesReturnPathAndCheckpoint() {
        val handoff = AgentHandoffRequest(
            conversationId = "conversation",
            taskId = "task",
            runId = "run",
            fromAgentId = "codex",
            toAgentId = "test-agent",
            returnToAgentId = "codex",
            reason = "Implementation is ready for verification",
            deliveryMode = AgentDeliveryMode.RESPOND,
            artifactIds = listOf("patch"),
            checkpoint = mapOf("sequence" to 12)
        )

        assertEquals("codex", handoff.returnToAgentId)
        assertEquals(listOf("patch"), handoff.artifactIds)
        assertEquals(12, handoff.checkpoint["sequence"])
    }
}
