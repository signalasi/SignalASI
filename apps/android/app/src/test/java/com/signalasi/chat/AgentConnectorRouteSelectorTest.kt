package com.signalasi.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AgentConnectorRouteSelectorTest {
    @Test
    fun selectsCodexWhenPhoneToolIsTheTopScoredResource() {
        val codex = target("codex", AgentConnectorKind.AGENT)
        val phoneWeb = resource(
            targetId = AgentWebMediaNativeTools.WEB_SEARCH,
            type = AgentResourceType.LOCAL_TOOL,
            location = AgentResourceLocation.PHONE
        )
        val codexResource = resource(
            targetId = codex.id,
            type = AgentResourceType.REMOTE_AGENT,
            location = AgentResourceLocation.TRUSTED_DESKTOP
        )
        val decision = decision(
            primary = candidate(phoneWeb, 900),
            fallbacks = listOf(candidate(codexResource, 800)),
            catalog = listOf(phoneWeb, codexResource)
        )

        val selected = AgentConnectorRouteSelector.select(listOf(codex), decision)

        assertEquals(codex.id, selected?.target?.id)
        assertEquals(codex.id, selected?.decision?.primary?.resource?.targetId)
        assertEquals(emptyList<String>(), selected?.decision?.orderedTargetIds?.drop(1))
    }

    @Test
    fun connectorFallbacksNeverContainPhoneTools() {
        val codex = target("codex", AgentConnectorKind.AGENT)
        val cloud = target("cloud-model:deepseek", AgentConnectorKind.MODEL)
        val codexResource = resource(codex.id, AgentResourceType.REMOTE_AGENT, AgentResourceLocation.TRUSTED_DESKTOP)
        val phoneWeb = resource(AgentWebMediaNativeTools.WEB_SEARCH, AgentResourceType.LOCAL_TOOL, AgentResourceLocation.PHONE)
        val cloudResource = resource(cloud.id, AgentResourceType.CLOUD_MODEL, AgentResourceLocation.CLOUD)
        val decision = decision(
            primary = candidate(codexResource, 900),
            fallbacks = listOf(candidate(phoneWeb, 850), candidate(cloudResource, 700)),
            catalog = listOf(codexResource, phoneWeb, cloudResource)
        )

        val selected = AgentConnectorRouteSelector.select(listOf(codex, cloud), decision)

        assertEquals(codex.id, selected?.target?.id)
        assertEquals(listOf(cloud.id), selected?.decision?.fallbacks?.map { it.resource.targetId })
    }

    @Test
    fun explicitConnectorRemainsPrimaryAndUsesOnlyConnectorFallbacks() {
        val codex = target("codex", AgentConnectorKind.AGENT)
        val cloud = target("cloud-model:deepseek", AgentConnectorKind.MODEL)
        val phoneWeb = resource(AgentWebMediaNativeTools.WEB_SEARCH, AgentResourceType.LOCAL_TOOL, AgentResourceLocation.PHONE)
        val codexResource = resource(codex.id, AgentResourceType.REMOTE_AGENT, AgentResourceLocation.TRUSTED_DESKTOP)
        val cloudResource = resource(cloud.id, AgentResourceType.CLOUD_MODEL, AgentResourceLocation.CLOUD)
        val decision = decision(
            primary = candidate(phoneWeb, 950),
            fallbacks = listOf(candidate(cloudResource, 900), candidate(codexResource, 800)),
            catalog = listOf(phoneWeb, cloudResource, codexResource)
        )

        val selected = AgentConnectorRouteSelector.select(
            targets = listOf(codex, cloud),
            decision = decision,
            preferredTargetId = codex.id
        )

        assertEquals(codex.id, selected?.decision?.primary?.resource?.targetId)
        assertEquals(listOf(cloud.id), selected?.decision?.fallbacks?.map { it.resource.targetId })
    }

    @Test
    fun recoversPairedReasoningResourceWhileStatusHeartbeatIsInFlight() {
        val recovering = target("codex", AgentConnectorKind.AGENT).copy(
            status = AgentConnectorStatus.DISCONNECTED
        )

        val selected = AgentConnectorRouteSelector.select(listOf(recovering), null)

        assertEquals("codex", selected?.target?.id)
    }

    @Test
    fun returnsNullWhenReasoningResourceStillNeedsSetup() {
        val unavailable = target("codex", AgentConnectorKind.AGENT).copy(
            status = AgentConnectorStatus.NEEDS_SETUP
        )

        assertNull(AgentConnectorRouteSelector.select(listOf(unavailable), null))
    }

    private fun target(id: String, kind: AgentConnectorKind) = AgentCallableTarget(
        id = id,
        title = id,
        kind = kind,
        status = AgentConnectorStatus.AVAILABLE,
        capabilities = listOf(AgentCapability.CHAT, AgentCapability.REASONING, AgentCapability.RESEARCH)
    )

    private fun resource(
        targetId: String,
        type: AgentResourceType,
        location: AgentResourceLocation
    ) = AgentResourceDescriptor(
        id = "resource:$targetId",
        title = targetId,
        type = type,
        location = location,
        status = AgentConnectorStatus.AVAILABLE,
        capabilities = setOf(AgentCapability.RESEARCH),
        cost = AgentResourceCost.FREE,
        latency = AgentResourceLatency.FAST,
        quality = AgentResourceQuality.STRONG,
        supportsTools = type == AgentResourceType.LOCAL_TOOL,
        targetId = targetId
    )

    private fun candidate(resource: AgentResourceDescriptor, score: Int) = AgentResourceCandidate(
        resource = resource,
        score = score,
        reasons = emptyList()
    )

    private fun decision(
        primary: AgentResourceCandidate,
        fallbacks: List<AgentResourceCandidate>,
        catalog: List<AgentResourceDescriptor>
    ) = AgentRoutingDecision(
        requirements = AgentTaskRequirements(
            capabilities = setOf(AgentCapability.RESEARCH),
            mode = AgentRoutingMode.BALANCED,
            liveDataRequired = true,
            localOnly = false,
            complexReasoning = false,
            estimatedInputTokens = 200
        ),
        primary = primary,
        fallbacks = fallbacks,
        catalog = catalog
    )
}
