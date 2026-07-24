package com.signalasi.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentPersonalPolicyFirewallTest {
    @Test
    fun trustedLowRiskOutboundRequestIsAdmittedWithoutBroadGrant() {
        val fixture = fixture()
        val request = request()

        val decision = fixture.firewall.admit(request, listOf(verifiedAgent("codex")))

        assertEquals(AgentPolicyFirewallVerdict.ALLOW, decision.verdict)
        assertTrue(decision.replayClaimed)
        assertTrue("trusted_low_risk_outbound" in decision.reasonCodes)
    }

    @Test
    fun inboundRequestRequiresAnExactUserGrant() {
        val fixture = fixture()
        val incoming = request(
            direction = AgentExternalRequestDirection.INBOUND,
            requesterAgentId = "remote-hermes",
            targetAgentIds = setOf("phone-agent")
        )
        val registrations = listOf(
            verifiedAgent("remote-hermes"),
            phoneAgent("phone-agent")
        )

        val before = fixture.firewall.evaluate(incoming, registrations)
        fixture.grants.grant(permanentGrant(
            subjectId = "remote-hermes",
            action = "inbound",
            resource = SOURCE_TEAM,
            target = DESTINATION_TEAM
        ))
        val after = fixture.firewall.admit(incoming, registrations)

        assertEquals(AgentPolicyFirewallVerdict.REQUIRE_CONFIRMATION, before.verdict)
        assertTrue("inbound_request_requires_grant" in before.reasonCodes)
        assertEquals(AgentPolicyFirewallVerdict.ALLOW, after.verdict)
        assertTrue(after.matchedGrantIds.isNotEmpty())
    }

    @Test
    fun restrictedDataRequiresFreshSingleUseGrantEvenForPairedAgent() {
        val fixture = fixture()
        val first = request(
            requestId = "restricted-one",
            nonce = "restricted-nonce-0001",
            sensitivity = AgentDataSensitivity.RESTRICTED
        )
        fixture.grants.grant(singleUseGrant("codex"))

        val admitted = fixture.firewall.admit(first, listOf(verifiedAgent("codex")))
        val next = fixture.firewall.evaluate(
            first.copy(
                requestId = "restricted-two",
                nonce = "restricted-nonce-0002"
            ),
            listOf(verifiedAgent("codex"))
        )

        assertEquals(AgentPolicyFirewallVerdict.ALLOW, admitted.verdict)
        assertEquals(AgentPolicyFirewallVerdict.REQUIRE_CONFIRMATION, next.verdict)
        assertTrue("fresh_single_use_grant_required" in next.reasonCodes)
    }

    @Test
    fun restrictedDataCannotCrossIntoCloudEvenWithGrant() {
        val fixture = fixture()
        val request = request(sensitivity = AgentDataSensitivity.RESTRICTED)
        fixture.grants.grant(singleUseGrant("cloud-model"))

        val decision = fixture.firewall.evaluate(
            request.copy(targetAgentIds = setOf("cloud-model")),
            listOf(cloudAgent("cloud-model"))
        )

        assertEquals(AgentPolicyFirewallVerdict.DENY, decision.verdict)
        assertTrue("restricted_data_boundary" in decision.reasonCodes)
    }

    @Test
    fun internalMemoryConversationAndSystemPromptCannotBeDelegated() {
        val fixture = fixture()
        val decision = fixture.firewall.evaluate(
            request().copy(
                disclosure = AgentDelegationDisclosure(
                    contextKeys = setOf("objective", "_signalasi_internal_memory"),
                    includesConversationHistory = true,
                    includesInternalMemory = true,
                    includesSystemPrompt = true
                )
            ),
            listOf(verifiedAgent("codex"))
        )

        assertEquals(AgentPolicyFirewallVerdict.DENY, decision.verdict)
        assertTrue("conversation_history_forbidden" in decision.reasonCodes)
        assertTrue("internal_memory_forbidden" in decision.reasonCodes)
        assertTrue("system_prompt_forbidden" in decision.reasonCodes)
        assertTrue("context_boundary_violation" in decision.reasonCodes)
    }

    @Test
    fun replayedRequestOrNonceIsDenied() {
        val fixture = fixture()
        val request = request()
        val registrations = listOf(verifiedAgent("codex"))

        val first = fixture.firewall.admit(request, registrations)
        val sameRequest = fixture.firewall.admit(request, registrations)
        val reusedNonce = fixture.firewall.admit(
            request.copy(requestId = "second-request"),
            registrations
        )

        assertEquals(AgentPolicyFirewallVerdict.ALLOW, first.verdict)
        assertEquals(AgentPolicyFirewallVerdict.DENY, sameRequest.verdict)
        assertEquals(AgentPolicyFirewallVerdict.DENY, reusedNonce.verdict)
        assertTrue("replay_detected" in sameRequest.reasonCodes)
        assertTrue("replay_detected" in reusedNonce.reasonCodes)
    }

    @Test
    fun unverifiedIdentityInsecureTransportAndExpiredRequestFailClosed() {
        val fixture = fixture()
        val decision = fixture.firewall.evaluate(
            request().copy(
                secureTransport = false,
                identityProofVerified = false,
                createdAtMillis = NOW - 120_000L,
                expiresAtMillis = NOW - 60_000L
            ),
            listOf(verifiedAgent("codex"))
        )

        assertEquals(AgentPolicyFirewallVerdict.DENY, decision.verdict)
        assertTrue("secure_transport_required" in decision.reasonCodes)
        assertTrue("identity_proof_required" in decision.reasonCodes)
        assertTrue("request_expired" in decision.reasonCodes)
    }

    @Test
    fun collectiveCapabilityContractMustBeCoveredByDestinationTeam() {
        val fixture = fixture()
        val decision = fixture.firewall.evaluate(
            request(requiredCapabilities = setOf(AgentCapability.CODE, AgentCapability.LIVE_DATA)),
            listOf(verifiedAgent("codex", setOf(AgentCapability.CHAT, AgentCapability.CODE)))
        )

        assertEquals(AgentPolicyFirewallVerdict.DENY, decision.verdict)
        assertTrue("capability_contract_unmet" in decision.reasonCodes)
    }

    @Test
    fun unregisteredInboundDestinationFailsClosedWithoutCapabilityHints() {
        val fixture = fixture()
        val incoming = request(
            direction = AgentExternalRequestDirection.INBOUND,
            requesterAgentId = "remote-hermes",
            targetAgentIds = setOf("missing-phone-team"),
            requiredCapabilities = emptySet()
        )

        val decision = fixture.firewall.evaluate(
            incoming,
            listOf(verifiedAgent("remote-hermes"))
        )

        assertEquals(AgentPolicyFirewallVerdict.DENY, decision.verdict)
        assertTrue("target_not_registered" in decision.reasonCodes)
    }

    @Test
    fun deviceControlAlwaysRequiresFreshSingleUseGrant() {
        val fixture = fixture()
        val registration = verifiedAgent(
            "device-agent",
            setOf(AgentCapability.CHAT, AgentCapability.DEVICE_CONTROL)
        )
        val request = request(
            targetAgentIds = setOf("device-agent"),
            requiredCapabilities = setOf(AgentCapability.DEVICE_CONTROL)
        )

        val before = fixture.firewall.evaluate(request, listOf(registration))
        fixture.grants.grant(singleUseGrant("device-agent"))
        val after = fixture.firewall.admit(request, listOf(registration))

        assertEquals(AgentPolicyFirewallVerdict.REQUIRE_CONFIRMATION, before.verdict)
        assertEquals(AgentPolicyFirewallVerdict.ALLOW, after.verdict)
    }

    @Test
    fun everyParticipantInExternalTeamNeedsGrantWhenTrustBoundaryRequiresIt() {
        val fixture = fixture()
        val request = request(targetAgentIds = setOf("cloud-one", "cloud-two"))
        val registrations = listOf(cloudAgent("cloud-one"), cloudAgent("cloud-two"))
        fixture.grants.grant(permanentGrant(
            subjectId = "cloud-one",
            action = "outbound",
            resource = SOURCE_TEAM,
            target = DESTINATION_TEAM
        ))

        val partial = fixture.firewall.evaluate(request, registrations)
        fixture.grants.grant(permanentGrant(
            subjectId = "cloud-two",
            action = "outbound",
            resource = SOURCE_TEAM,
            target = DESTINATION_TEAM
        ))
        val complete = fixture.firewall.admit(request, registrations)

        assertEquals(AgentPolicyFirewallVerdict.REQUIRE_CONFIRMATION, partial.verdict)
        assertEquals(2, partial.requiredGrants.size)
        assertEquals(AgentPolicyFirewallVerdict.ALLOW, complete.verdict)
    }

    @Test
    fun auditStoresOnlyGoalHashAndDecisionMetadata() {
        val fixture = fixture()
        val secretGoal = "Summarize private project notes without storing their contents"

        fixture.firewall.evaluate(
            request(goal = secretGoal),
            listOf(verifiedAgent("codex"))
        )

        val event = fixture.audit.list().single()
        assertEquals(64, event.goalHash.length)
        assertFalse(event.goalHash.contains(secretGoal))
        assertEquals(AgentPolicyFirewallVerdict.ALLOW, event.verdict)
        assertEquals(setOf("codex"), event.targetAgentIds)
    }

    private fun fixture(): Fixture {
        val grants = InMemoryAgentPermissionGrantStore(clock = { NOW })
        val audit = InMemoryAgentPolicyFirewallAuditStore()
        return Fixture(
            grants = grants,
            audit = audit,
            firewall = AgentPersonalPolicyFirewall(
                grantStore = grants,
                replayStore = InMemoryAgentPolicyReplayStore(),
                auditStore = audit,
                clock = { NOW }
            )
        )
    }

    private fun request(
        requestId: String = "request-one",
        nonce: String = "nonce-0123456789abcdef",
        direction: AgentExternalRequestDirection = AgentExternalRequestDirection.OUTBOUND,
        requesterAgentId: String = "signalasi-mobile",
        targetAgentIds: Set<String> = setOf("codex"),
        goal: String = "Complete the delegated task",
        requiredCapabilities: Set<AgentCapability> = setOf(AgentCapability.CHAT),
        sensitivity: AgentDataSensitivity = AgentDataSensitivity.PERSONAL
    ) = AgentExternalPolicyRequest(
        requestId = requestId,
        nonce = nonce,
        direction = direction,
        sourceTeamId = SOURCE_TEAM,
        destinationTeamId = DESTINATION_TEAM,
        requesterAgentId = requesterAgentId,
        targetAgentIds = targetAgentIds,
        goal = goal,
        requiredCapabilities = requiredCapabilities,
        disclosure = AgentDelegationDisclosure(contextKeys = setOf("objective")),
        dataSensitivity = sensitivity,
        risk = AgentRisk.LOW,
        secureTransport = true,
        identityProofVerified = true,
        createdAtMillis = NOW,
        expiresAtMillis = NOW + 60_000L
    )

    private fun singleUseGrant(subjectId: String) = AgentPermissionGrant(
        grantId = "grant-$subjectId",
        subjectType = AgentPermissionSubjectType.AGENT,
        subjectId = subjectId,
        scope = AgentPersonalPolicyFirewall.DELEGATION_SCOPE,
        action = "outbound",
        resource = SOURCE_TEAM,
        target = DESTINATION_TEAM,
        issuer = AgentPermissionGrantIssuer.USER,
        evidence = "user-confirmed",
        lifetime = AgentPermissionGrantLifetime.SINGLE_USE
    )

    private fun permanentGrant(
        subjectId: String,
        action: String,
        resource: String,
        target: String
    ) = AgentPermissionGrant(
        grantId = "grant-$subjectId-$action",
        subjectType = AgentPermissionSubjectType.AGENT,
        subjectId = subjectId,
        scope = AgentPersonalPolicyFirewall.DELEGATION_SCOPE,
        action = action,
        resource = resource,
        target = target,
        issuer = AgentPermissionGrantIssuer.USER,
        evidence = "user-confirmed",
        lifetime = AgentPermissionGrantLifetime.PERMANENT
    )

    private fun verifiedAgent(
        id: String,
        capabilities: Set<AgentCapability> = setOf(AgentCapability.CHAT)
    ) = registration(
        id = id,
        location = AgentResourceLocation.TRUSTED_DESKTOP,
        trust = AgentResourceTrust.VERIFIED_PAIRED,
        capabilities = capabilities
    )

    private fun phoneAgent(id: String) = registration(
        id = id,
        location = AgentResourceLocation.PHONE,
        trust = AgentResourceTrust.PHONE_SYSTEM,
        capabilities = setOf(AgentCapability.CHAT)
    )

    private fun cloudAgent(id: String) = registration(
        id = id,
        location = AgentResourceLocation.CLOUD,
        trust = AgentResourceTrust.CLOUD_CONFIGURED,
        capabilities = setOf(AgentCapability.CHAT)
    )

    private fun registration(
        id: String,
        location: AgentResourceLocation,
        trust: AgentResourceTrust,
        capabilities: Set<AgentCapability>
    ) = AgentRegistration(
        agentId = id,
        installationId = "installation-$id",
        deviceId = "device-$id",
        providerId = id.substringBefore('-'),
        displayName = id,
        kind = AgentConnectorKind.AGENT,
        location = location,
        status = AgentEndpointStatus.ONLINE,
        capabilities = capabilities,
        protocol = AgentProtocolRange("1.1", "1.0", "1.1"),
        connectionKind = AgentConnectionKind.SIGNALASI_LINK,
        trust = trust
    )

    private data class Fixture(
        val grants: InMemoryAgentPermissionGrantStore,
        val audit: InMemoryAgentPolicyFirewallAuditStore,
        val firewall: AgentPersonalPolicyFirewall
    )

    companion object {
        private const val NOW = 1_000_000L
        private const val SOURCE_TEAM = "team-source"
        private const val DESTINATION_TEAM = "team-destination"
    }
}
