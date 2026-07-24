package com.signalasi.chat

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentCrossTeamDelegationTest {
    @Test
    fun launchRequestUsesAnIsolatedConversationAndMinimalTypedContext() {
        val fixture = fixture()
        val destination = destinationTeam()
        val input = input(
            constraints = listOf("Use public evidence only"),
            expectedOutput = "A concise verified answer",
            evidence = listOf(
                AgentDelegationEvidence(
                    evidenceId = "evidence-one",
                    summary = "The API changed in the latest release.",
                    sourceAgentId = "hermes-source",
                    contentHash = "abc123"
                )
            )
        )

        val prepared = fixture.coordinator.prepare(input, destination, registrations())
        val admission = fixture.coordinator.admit(
            prepared.envelope.delegationId,
            destination,
            registrations()
        )
        val request = requireNotNull(admission.launchSpec).request

        assertEquals(AgentCrossTeamDelegationState.AUTHORIZED, admission.record.state)
        assertEquals("delegation:${input.delegationId}", request.conversationId)
        assertEquals(input.sourceRunId, request.parentRunId)
        assertEquals(setOf(AgentCapability.CHAT), request.requiredCapabilities)
        assertTrue(request.goal.contains("Use public evidence only"))
        assertTrue(request.goal.contains("The API changed in the latest release."))
        assertFalse(request.context.containsKey("conversation_history"))
        assertFalse(request.context.containsKey("internal_memory"))
        assertFalse(request.context.containsKey("system_prompt"))
        assertFalse(request.context.containsKey("checkpoint"))
        assertEquals(true, request.context["cross_team_delegation"])
    }

    @Test
    fun artifactManifestWaitsForBoundGrantAndNeverContainsAUri() {
        val fixture = fixture()
        val destination = destinationTeam()
        val prepared = fixture.coordinator.prepare(
            input(artifacts = listOf(
                AgentDelegationArtifactManifest(
                    artifactId = "artifact-one",
                    name = "report.pdf",
                    mimeType = "application/pdf",
                    contentHash = "deadbeef",
                    sizeBytes = 1_024L
                )
            )),
            destination,
            registrations()
        )

        assertEquals(AgentCrossTeamDelegationState.WAITING_CONFIRMATION, prepared.state)
        fixture.grants.grant(permanentGrant("codex-destination"))
        val admitted = fixture.coordinator.admit(
            prepared.envelope.delegationId,
            destination,
            registrations()
        )
        val encoded = AgentCrossTeamDelegationCodec.encodeEnvelope(admitted.record.envelope)

        assertEquals(AgentCrossTeamDelegationState.AUTHORIZED, admitted.record.state)
        assertNotNull(admitted.launchSpec)
        assertTrue(encoded.contains("artifact-one"))
        assertFalse(encoded.contains("content://"))
        assertFalse(encoded.contains("file://"))
        assertFalse(encoded.contains("\"uri\""))
    }

    @Test
    fun destinationTeamExecutesAndReturnsOnlyItsPrimaryFinalOutput() = runBlocking {
        val fixture = fixture()
        val destination = destinationTeam(includeObserver = true)
        val registrations = registrations(includeObserver = true)
        val prepared = fixture.coordinator.prepare(input(), destination, registrations)
        val admission = fixture.coordinator.admit(prepared.envelope.delegationId, destination, registrations)
        val launch = requireNotNull(admission.launchSpec)
        val runtimeStore = InMemoryAgentTeamExecutionStore()
        val runtime = AgentTeamExecutionRuntime(runtimeStore)

        val handle = runtime.start(launch.definition, launch.request) { context ->
            AgentSubagentOutput(
                if (context.member.agentId == destination.primaryAgentId) {
                    "Final delegated result"
                } else {
                    "Private observer evidence"
                }
            )
        }
        fixture.coordinator.markDispatched(prepared.envelope.delegationId, handle.supervisorRunId)
        val result = handle.await()
        val finished = fixture.coordinator.finish(prepared.envelope.delegationId, result.snapshot)
        runtime.close()

        assertEquals(AgentCrossTeamDelegationState.RETURNED, finished.state)
        assertEquals("Final delegated result", finished.resultSummary)
        assertFalse(finished.resultSummary.contains("Private observer evidence"))
    }

    @Test
    fun authorizedDelegationCanResumeAfterProcessBoundaryWithoutReclaimingNonce() {
        val fixture = fixture()
        val destination = destinationTeam()
        val prepared = fixture.coordinator.prepare(input(), destination, registrations())
        val firstAdmission = fixture.coordinator.admit(
            prepared.envelope.delegationId,
            destination,
            registrations()
        )

        val resumed = fixture.coordinator.admit(
            prepared.envelope.delegationId,
            destination,
            registrations()
        )

        assertEquals(AgentCrossTeamDelegationState.AUTHORIZED, firstAdmission.record.state)
        assertEquals(AgentCrossTeamDelegationState.AUTHORIZED, resumed.record.state)
        assertNotNull(resumed.launchSpec)
        assertEquals(
            firstAdmission.launchSpec!!.request.runId,
            resumed.launchSpec!!.request.runId
        )
    }

    @Test
    fun destinationIdentityAndMembershipAreImmutableAfterReview() {
        val fixture = fixture()
        val destination = destinationTeam()
        val prepared = fixture.coordinator.prepare(input(), destination, registrations())
        val changed = destination.copy(
            members = destination.members + AgentTeamMember(
                agentId = "unreviewed-agent",
                deliveryMode = AgentDeliveryMode.OBSERVE,
                requiredCapabilities = setOf(AgentCapability.CHAT)
            )
        )

        val failure = runCatching {
            fixture.coordinator.admit(prepared.envelope.delegationId, changed, registrations())
        }.exceptionOrNull()

        assertNotNull(failure)
        assertTrue(failure!!.message.orEmpty().contains("members changed"))
    }

    @Test
    fun excessiveDelegationDepthIsDeniedBeforeAnyRunIsCreated() {
        val fixture = fixture()
        val record = fixture.coordinator.prepare(
            input(delegationDepth = 4),
            destinationTeam(),
            registrations()
        )

        assertEquals(AgentCrossTeamDelegationState.DENIED, record.state)
        assertTrue("delegation_depth_exceeded" in record.policyReasonCodes)
    }

    @Test
    fun codecRoundTripPreservesTheImmutableEnvelopeAndReceipt() {
        val fixture = fixture()
        val prepared = fixture.coordinator.prepare(
            input(
                constraints = listOf("No network writes"),
                evidence = listOf(AgentDelegationEvidence(
                    "evidence-one",
                    "A bounded summary",
                    "source-agent"
                ))
            ),
            destinationTeam(),
            registrations()
        )
        val encodedEnvelope = AgentCrossTeamDelegationCodec.encodeEnvelope(prepared.envelope)
        val encodedRecords = AgentCrossTeamDelegationCodec.encodeRecords(listOf(prepared))

        assertEquals(
            prepared.envelope,
            AgentCrossTeamDelegationCodec.decodeEnvelope(encodedEnvelope)
        )
        assertEquals(
            listOf(prepared),
            AgentCrossTeamDelegationCodec.decodeRecords(encodedRecords)
        )
    }

    @Test
    fun delegationIdCannotBeReusedForDifferentContent() {
        val fixture = fixture()
        val destination = destinationTeam()
        val first = input()
        fixture.coordinator.prepare(first, destination, registrations())

        val failure = runCatching {
            fixture.coordinator.prepare(
                first.copy(goal = "A different immutable goal"),
                destination,
                registrations()
            )
        }.exceptionOrNull()

        assertNotNull(failure)
        assertTrue(failure!!.message.orEmpty().contains("immutable envelope"))
    }

    @Test
    fun deniedDelegationDoesNotProduceALaunchSpec() {
        val fixture = fixture()
        val destination = destinationTeam()
        val denied = fixture.coordinator.prepare(
            input(secureTransport = false),
            destination,
            registrations()
        )

        val admission = fixture.coordinator.admit(
            denied.envelope.delegationId,
            destination,
            registrations()
        )

        assertEquals(AgentCrossTeamDelegationState.DENIED, admission.record.state)
        assertNull(admission.launchSpec)
    }

    @Test
    fun rawSourceSecretsCannotEnterTheTypedEnvelopeByAccident() {
        val fixture = fixture()
        val sourceSecret = "source-team-internal-memory-secret"
        val prepared = fixture.coordinator.prepare(
            input(goal = "Summarize the approved public evidence"),
            destinationTeam(),
            registrations()
        )
        val encoded = AgentCrossTeamDelegationCodec.encodeEnvelope(prepared.envelope)

        assertFalse(encoded.contains(sourceSecret))
        assertFalse(encoded.contains("conversation_history"))
        assertFalse(encoded.contains("internal_memory"))
        assertFalse(encoded.contains("system_prompt"))
        assertFalse(encoded.contains("checkpoint"))
    }

    private fun fixture(): Fixture {
        val grants = InMemoryAgentPermissionGrantStore(clock = { NOW })
        val firewall = AgentPersonalPolicyFirewall(
            grantStore = grants,
            replayStore = InMemoryAgentPolicyReplayStore(),
            auditStore = InMemoryAgentPolicyFirewallAuditStore(),
            clock = { NOW }
        )
        return Fixture(
            grants = grants,
            coordinator = AgentCrossTeamDelegationCoordinator(
                firewall = firewall,
                store = InMemoryAgentCrossTeamDelegationStore(),
                clock = { NOW }
            )
        )
    }

    private fun input(
        goal: String = "Complete the delegated analysis",
        constraints: List<String> = emptyList(),
        expectedOutput: String = "",
        evidence: List<AgentDelegationEvidence> = emptyList(),
        artifacts: List<AgentDelegationArtifactManifest> = emptyList(),
        delegationDepth: Int = 1,
        secureTransport: Boolean = true
    ) = AgentCrossTeamDelegationInput(
        delegationId = "delegation-one",
        nonce = "delegation-nonce-0001",
        sourceTeamId = SOURCE_TEAM,
        sourceRunId = "source-run",
        requesterAgentId = "signalasi-mobile",
        goal = goal,
        constraints = constraints,
        expectedOutput = expectedOutput,
        requiredCapabilities = setOf(AgentCapability.CHAT),
        evidence = evidence,
        artifacts = artifacts,
        delegationDepth = delegationDepth,
        secureTransport = secureTransport,
        identityProofVerified = true,
        createdAtMillis = NOW,
        expiresAtMillis = NOW + 60_000L
    )

    private fun destinationTeam(includeObserver: Boolean = false): AgentTeamDefinition {
        val members = buildList {
            add(AgentTeamMember(
                agentId = "codex-destination",
                deliveryMode = AgentDeliveryMode.RESPOND,
                requiredCapabilities = setOf(AgentCapability.CHAT),
                role = "lead synthesizer"
            ))
            if (includeObserver) {
                add(AgentTeamMember(
                    agentId = "hermes-observer",
                    deliveryMode = AgentDeliveryMode.OBSERVE,
                    requiredCapabilities = emptySet(),
                    role = "research specialist"
                ))
            }
        }
        return AgentTeamDefinition(
            teamId = DESTINATION_TEAM,
            primaryAgentId = "codex-destination",
            members = members,
            collectiveCapabilities = setOf(AgentCapability.CHAT)
        )
    }

    private fun registrations(includeObserver: Boolean = false): List<AgentRegistration> = buildList {
        add(registration("codex-destination"))
        if (includeObserver) add(registration("hermes-observer"))
    }

    private fun registration(id: String) = AgentRegistration(
        agentId = id,
        installationId = "installation-$id",
        deviceId = "device-$id",
        providerId = id.substringBefore('-'),
        displayName = id,
        kind = AgentConnectorKind.AGENT,
        location = AgentResourceLocation.TRUSTED_DESKTOP,
        status = AgentEndpointStatus.ONLINE,
        capabilities = setOf(AgentCapability.CHAT),
        protocol = AgentProtocolRange("1.1", "1.0", "1.1"),
        connectionKind = AgentConnectionKind.SIGNALASI_LINK,
        trust = AgentResourceTrust.VERIFIED_PAIRED
    )

    private fun permanentGrant(subjectId: String) = AgentPermissionGrant(
        grantId = "grant-$subjectId",
        subjectType = AgentPermissionSubjectType.AGENT,
        subjectId = subjectId,
        scope = AgentPersonalPolicyFirewall.DELEGATION_SCOPE,
        action = "outbound",
        resource = SOURCE_TEAM,
        target = DESTINATION_TEAM,
        issuer = AgentPermissionGrantIssuer.USER,
        evidence = "user-confirmed",
        lifetime = AgentPermissionGrantLifetime.PERMANENT
    )

    private data class Fixture(
        val grants: InMemoryAgentPermissionGrantStore,
        val coordinator: AgentCrossTeamDelegationCoordinator
    )

    companion object {
        private const val NOW = 2_000_000L
        private const val SOURCE_TEAM = "team-source"
        private const val DESTINATION_TEAM = "team-destination"
    }
}
