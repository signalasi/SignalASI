package com.signalasi.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentFailoverPolicyTest {
    @Test
    fun desktopTimeoutPrefersCloudThenAnotherDesktopThenSameDesktop() {
        val primary = resource("codex", AgentResourceLocation.TRUSTED_DESKTOP, "desktop-a")
        val cloud = resource("cloud-models", AgentResourceLocation.CLOUD, "cloud-openai")
        val otherDesktop = resource("codex-b", AgentResourceLocation.TRUSTED_DESKTOP, "desktop-b")
        val sameDesktop = resource("hermes-a", AgentResourceLocation.TRUSTED_DESKTOP, "desktop-a")

        assertEquals(0, AgentFailoverPolicy.fallbackTier(primary, cloud))
        assertEquals(1, AgentFailoverPolicy.fallbackTier(primary, otherDesktop))
        assertEquals(2, AgentFailoverPolicy.fallbackTier(primary, sameDesktop))
    }

    @Test
    fun timeoutStagesDistinguishTransportQueueAndReadOnlyExecution() {
        assertTrue(AgentFailoverPolicy.shouldFailOver(AgentConnectorTimeoutStage.NOT_ACCEPTED, "", false))
        assertFalse(AgentFailoverPolicy.shouldFailOver(AgentConnectorTimeoutStage.NOT_ACCEPTED, "accepted", false))
        assertTrue(AgentFailoverPolicy.shouldFailOver(AgentConnectorTimeoutStage.NOT_RUNNING, "queued", false))
        assertFalse(AgentFailoverPolicy.shouldFailOver(AgentConnectorTimeoutStage.NOT_RUNNING, "running", false))
        assertTrue(AgentFailoverPolicy.shouldFailOver(AgentConnectorTimeoutStage.READ_ONLY_STALE, "running", true))
        assertFalse(AgentFailoverPolicy.shouldFailOver(AgentConnectorTimeoutStage.READ_ONLY_STALE, "running", false))
    }

    @Test
    fun acceptedOnlyResourceIsAllowedToStartSlowly() {
        assertTrue(
            AgentFailoverPolicy.shouldKeepOnlyResourceAlive(
                AgentConnectorTimeoutStage.NOT_RUNNING,
                "accepted",
                hasFallback = false
            )
        )
        assertTrue(
            AgentFailoverPolicy.shouldKeepOnlyResourceAlive(
                AgentConnectorTimeoutStage.NOT_RUNNING,
                "queued",
                hasFallback = false
            )
        )
        assertFalse(
            AgentFailoverPolicy.shouldKeepOnlyResourceAlive(
                AgentConnectorTimeoutStage.NOT_RUNNING,
                "accepted",
                hasFallback = true
            )
        )
        assertFalse(
            AgentFailoverPolicy.shouldKeepOnlyResourceAlive(
                AgentConnectorTimeoutStage.NOT_ACCEPTED,
                "",
                hasFallback = false
            )
        )
    }

    @Test
    fun desktopProbeBackoffCapsAtOneHour() {
        assertEquals(60_000L, AgentFailoverPolicy.domainCooldownMs(1))
        assertEquals(5 * 60_000L, AgentFailoverPolicy.domainCooldownMs(2))
        assertEquals(15 * 60_000L, AgentFailoverPolicy.domainCooldownMs(3))
        assertEquals(60 * 60_000L, AgentFailoverPolicy.domainCooldownMs(8))
    }

    private fun resource(
        id: String,
        location: AgentResourceLocation,
        failureDomain: String
    ) = AgentResourceDescriptor(
        id = id,
        title = id,
        type = if (location == AgentResourceLocation.CLOUD) AgentResourceType.CLOUD_MODEL else AgentResourceType.REMOTE_AGENT,
        location = location,
        status = AgentConnectorStatus.AVAILABLE,
        capabilities = setOf(AgentCapability.CHAT),
        cost = AgentResourceCost.LOW,
        latency = AgentResourceLatency.NORMAL,
        quality = AgentResourceQuality.STRONG,
        supportsTools = true,
        targetId = id,
        failureDomain = failureDomain
    )
}
