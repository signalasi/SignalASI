package com.signalasi.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentNetworkSearchTest {
    @Test
    fun naturalLanguageSearchRanksCapableNamedAgentsWithoutCollapsingIdentity() {
        val index = AgentNetworkIndex(
            listOf(
                registration(
                    agentId = "codex.office",
                    displayName = "Codex - Office PC",
                    capabilities = setOf(AgentCapability.CHAT, AgentCapability.CODE, AgentCapability.TASK_EXECUTION),
                    latency = AgentResourceLatency.FAST
                ),
                registration(
                    agentId = "claude-code.home",
                    displayName = "Claude Code - Home PC",
                    capabilities = setOf(AgentCapability.CHAT, AgentCapability.CODE, AgentCapability.REASONING),
                    latency = AgentResourceLatency.NORMAL
                ),
                registration(
                    agentId = "hermes.research",
                    displayName = "Hermes - Research PC",
                    capabilities = setOf(AgentCapability.CHAT, AgentCapability.RESEARCH, AgentCapability.LIVE_DATA)
                )
            )
        )

        val page = index.search(
            AgentNetworkSearchQuery(text = "Find a fast Agent to debug a Python project")
        )

        assertEquals(2, page.totalMatches)
        assertEquals("codex.office", page.hits.first().registration.agentId)
        assertEquals("Codex - Office PC", page.hits.first().registration.displayName)
        assertTrue(AgentCapability.CODE in page.hits.first().matchedCapabilities)
        assertFalse(page.hits.any { it.registration.agentId == "hermes.research" })
    }

    @Test
    fun explicitSecurityCapacityAndCostFiltersAreEnforced() {
        val index = AgentNetworkIndex(
            listOf(
                registration(
                    agentId = "phone.local",
                    displayName = "Phone Agent",
                    location = AgentResourceLocation.PHONE,
                    trust = AgentResourceTrust.PHONE_SYSTEM,
                    cost = AgentResourceCost.FREE
                ),
                registration(
                    agentId = "desktop.busy",
                    displayName = "Codex - Busy PC",
                    activeRuns = 2,
                    maxParallelRuns = 2,
                    cost = AgentResourceCost.LOW
                ),
                registration(
                    agentId = "cloud.unknown",
                    displayName = "Unknown Cloud Agent",
                    location = AgentResourceLocation.CLOUD,
                    trust = AgentResourceTrust.UNKNOWN,
                    cost = AgentResourceCost.HIGH
                )
            )
        )

        val page = index.search(
            AgentNetworkSearchQuery(
                trustedOnly = true,
                maximumCost = AgentResourceCost.LOW
            )
        )

        assertEquals(listOf("phone.local"), page.hits.map { it.registration.agentId })
    }

    @Test
    fun cursorPaginationIsStableAndInvalidatesAfterDirectoryMutation() {
        val index = AgentNetworkIndex(
            (0 until 75).map { number ->
                registration(
                    agentId = "agent-$number",
                    displayName = "Agent ${number.toString().padStart(3, '0')}"
                )
            }
        )
        val query = AgentNetworkSearchQuery(pageSize = 25)
        val first = index.search(query)
        val second = index.search(query.copy(cursor = first.nextCursor))

        assertEquals(25, first.hits.size)
        assertEquals(25, second.hits.size)
        assertTrue(first.hits.map { it.registration.agentId }.intersect(
            second.hits.map { it.registration.agentId }.toSet()
        ).isEmpty())
        assertFalse(second.cursorReset)

        index.upsert(registration("agent-new", "Agent New"))
        val reset = index.search(query.copy(cursor = second.nextCursor))

        assertTrue(reset.cursorReset)
        assertEquals(index.revision(), reset.revision)
        assertEquals(25, reset.hits.size)
    }

    @Test
    fun indexSupportsTenThousandAgentsWithoutARegistryCap() {
        val registrations = (0 until 10_000).map { number ->
            registration(
                agentId = "network-agent-$number",
                displayName = "Network Agent $number",
                providerId = "provider-${number % 50}",
                deviceId = "device-${number % 200}",
                capabilities = if (number == 9_999) {
                    setOf(AgentCapability.CHAT, AgentCapability.CODE, AgentCapability.REASONING)
                } else {
                    setOf(AgentCapability.CHAT)
                }
            )
        }
        val index = AgentNetworkIndex(registrations)

        val broadPage = index.search(AgentNetworkSearchQuery(pageSize = 100))
        val page = index.search(
            AgentNetworkSearchQuery(
                text = "network-agent-9999",
                pageSize = 10
            )
        )

        assertEquals(10_000, index.size())
        assertEquals(10_000, broadPage.totalMatches)
        assertEquals(100, broadPage.hits.size)
        assertEquals("network-agent-9999", page.hits.first().registration.agentId)
        assertTrue(page.totalMatches >= 1)
    }

    @Test
    fun staleRemoteHeartbeatBecomesUnreachableButPhoneAgentRemainsRoutable() {
        val now = 1_000_000L
        val staleHeartbeat = now - AGENT_NETWORK_HEARTBEAT_TTL_MILLIS - 1L
        val index = AgentNetworkIndex(
            listOf(
                registration(
                    agentId = "desktop.stale",
                    displayName = "Codex - Stale PC",
                    lastHeartbeatMillis = staleHeartbeat
                ),
                registration(
                    agentId = "phone.agent",
                    displayName = "Phone Agent",
                    location = AgentResourceLocation.PHONE,
                    trust = AgentResourceTrust.PHONE_SYSTEM,
                    lastHeartbeatMillis = staleHeartbeat
                )
            )
        )

        val routable = index.search(AgentNetworkSearchQuery(), now)
        val all = index.search(AgentNetworkSearchQuery(routableOnly = false), now)

        assertEquals(listOf("phone.agent"), routable.hits.map { it.registration.agentId })
        assertEquals(
            AgentEndpointStatus.UNREACHABLE,
            all.hits.first { it.registration.agentId == "desktop.stale" }.registration.status
        )
    }

    private fun registration(
        agentId: String,
        displayName: String,
        providerId: String = "desktop-provider",
        deviceId: String = "desktop-device",
        location: AgentResourceLocation = AgentResourceLocation.TRUSTED_DESKTOP,
        status: AgentEndpointStatus = AgentEndpointStatus.ONLINE,
        capabilities: Set<AgentCapability> = setOf(AgentCapability.CHAT),
        cost: AgentResourceCost = AgentResourceCost.FREE,
        latency: AgentResourceLatency = AgentResourceLatency.NORMAL,
        trust: AgentResourceTrust = AgentResourceTrust.VERIFIED_PAIRED,
        activeRuns: Int = 0,
        maxParallelRuns: Int = 4,
        lastHeartbeatMillis: Long = 0L
    ): AgentRegistration = AgentRegistration(
        agentId = agentId,
        installationId = "installation-$agentId",
        deviceId = deviceId,
        providerId = providerId,
        displayName = displayName,
        kind = AgentConnectorKind.AGENT,
        location = location,
        status = status,
        capabilities = capabilities,
        protocol = AgentProtocolRange(
            preferred = "1.1",
            minimum = "1.0",
            maximum = "1.1",
            features = setOf("run.cancel", "run.recover")
        ),
        connectionKind = AgentConnectionKind.SIGNALASI_LINK,
        cost = cost,
        latency = latency,
        trust = trust,
        activeRuns = activeRuns,
        maxParallelRuns = maxParallelRuns,
        lastHeartbeatMillis = lastHeartbeatMillis
    )
}
