package com.signalasi.chat

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentDynamicTeamCompilerTest {
    @Test
    fun complexGoalCompilesComplementaryNamedAgentsIntoOneVerifiedDag() {
        val result = AgentDynamicTeamCompiler().compile(
            request = AgentDynamicTeamRequest(
                goal = "Research the latest API and implement a Python program with high quality",
                teamId = "dynamic-team"
            ),
            registrations = listOf(
                registration(
                    "hermes.office",
                    "Hermes - Office PC",
                    setOf(
                        AgentCapability.CHAT,
                        AgentCapability.REASONING,
                        AgentCapability.RESEARCH,
                        AgentCapability.LIVE_DATA,
                        AgentCapability.TOOL_USE
                    ),
                    failureDomain = "desktop-office"
                ),
                registration(
                    "codex.dev",
                    "Codex - Development PC",
                    setOf(
                        AgentCapability.CHAT,
                        AgentCapability.REASONING,
                        AgentCapability.CODE,
                        AgentCapability.TASK_EXECUTION,
                        AgentCapability.TOOL_USE
                    ),
                    failureDomain = "desktop-dev",
                    latency = AgentResourceLatency.FAST
                ),
                registration(
                    "claude-code.review",
                    "Claude Code - Review PC",
                    setOf(
                        AgentCapability.CHAT,
                        AgentCapability.REASONING,
                        AgentCapability.CODE,
                        AgentCapability.TASK_EXECUTION
                    ),
                    failureDomain = "desktop-review"
                ),
                registration(
                    "auditor.independent",
                    "Independent Auditor",
                    setOf(AgentCapability.CHAT, AgentCapability.REASONING, AgentCapability.RESEARCH),
                    failureDomain = "cloud-audit"
                )
            )
        )

        assertEquals(AgentDynamicTeamOutcome.TEAM, result.outcome)
        assertEquals("codex.dev", result.primaryAgentId)
        assertEquals(
            setOf("codex.dev", "hermes.office", "claude-code.review", "auditor.independent"),
            result.assignments.mapTo(linkedSetOf()) { it.registration.agentId }
        )
        assertEquals(1, result.definition!!.members.count {
            it.deliveryMode == AgentDeliveryMode.RESPOND
        })
        assertEquals(
            setOf(AgentCapability.LIVE_DATA, AgentCapability.CODE),
            result.definition.collectiveCapabilities
        )
        val verifier = result.definition.members.single { it.role == "independent verifier" }
        assertEquals(
            setOf("hermes.office", "claude-code.review"),
            verifier.dependsOnAgentIds
        )
        val lead = result.definition.members.single { it.agentId == result.primaryAgentId }
        assertEquals(
            result.definition.members.filter { it.deliveryMode == AgentDeliveryMode.OBSERVE }
                .mapTo(linkedSetOf()) { it.agentId },
            lead.dependsOnAgentIds
        )
    }

    @Test
    fun simpleConversationKeepsOneAgentInsteadOfCreatingNeedlessTeam() {
        val result = AgentDynamicTeamCompiler().compile(
            AgentDynamicTeamRequest("Hello"),
            listOf(registration(
                "hermes",
                "Hermes",
                setOf(AgentCapability.CHAT, AgentCapability.REASONING)
            ))
        )

        assertEquals(AgentDynamicTeamOutcome.SINGLE_AGENT, result.outcome)
        assertEquals("hermes", result.primaryAgentId)
        assertEquals(1, result.assignments.size)
        assertNull(result.definition)
    }

    @Test
    fun privateRestrictedGoalExcludesCloudAndUnknownAgents() {
        val result = AgentDynamicTeamCompiler().compile(
            AgentDynamicTeamRequest("Handle my private key locally only"),
            listOf(
                registration(
                    "phone.agent",
                    "Phone Agent",
                    setOf(AgentCapability.CHAT, AgentCapability.REASONING),
                    location = AgentResourceLocation.PHONE,
                    trust = AgentResourceTrust.PHONE_SYSTEM,
                    failureDomain = "phone"
                ),
                registration(
                    "cloud.agent",
                    "Cloud Agent",
                    setOf(AgentCapability.CHAT, AgentCapability.REASONING),
                    location = AgentResourceLocation.CLOUD,
                    trust = AgentResourceTrust.CLOUD_CONFIGURED,
                    failureDomain = "cloud"
                )
            )
        )

        assertEquals("phone.agent", result.primaryAgentId)
        assertTrue(result.assignments.none { it.registration.location == AgentResourceLocation.CLOUD })
    }

    @Test
    fun requiredVerifierMustUseAnIndependentFailureDomain() {
        val sameDomain = listOf(
            registration(
                "codex",
                "Codex",
                setOf(AgentCapability.CHAT, AgentCapability.CODE, AgentCapability.REASONING),
                failureDomain = "desktop-one"
            ),
            registration(
                "claude-code",
                "Claude Code",
                setOf(AgentCapability.CHAT, AgentCapability.CODE, AgentCapability.REASONING),
                failureDomain = "desktop-one"
            )
        )
        val request = AgentDynamicTeamRequest(
            goal = "Implement and verify a Python program",
            policy = AgentDynamicTeamPolicy(
                verificationMode = AgentTeamVerificationMode.REQUIRED
            )
        )

        val blocked = AgentDynamicTeamCompiler().compile(request, sameDomain)
        val verified = AgentDynamicTeamCompiler().compile(
            request.copy(teamId = "verified-team"),
            sameDomain + registration(
                "auditor",
                "Auditor",
                setOf(AgentCapability.CHAT, AgentCapability.REASONING),
                failureDomain = "desktop-two"
            )
        )

        assertEquals(AgentDynamicTeamOutcome.BLOCKED, blocked.outcome)
        assertTrue(AgentDynamicTeamRole.VERIFIER in blocked.unfilledRoles)
        assertEquals(AgentDynamicTeamOutcome.TEAM, verified.outcome)
        assertEquals(
            "desktop-two",
            verified.assignments.single { it.role == AgentDynamicTeamRole.VERIFIER }.failureDomain
        )
    }

    @Test
    fun pinnedAgentIdentityIsHonoredWithoutRenamingOrMergingIt() {
        val result = AgentDynamicTeamCompiler().compile(
            AgentDynamicTeamRequest(
                goal = "Discuss the architecture",
                policy = AgentDynamicTeamPolicy(pinnedAgentIds = setOf("hermes.home"))
            ),
            listOf(
                registration(
                    "codex.office",
                    "Codex - Office PC",
                    setOf(AgentCapability.CHAT, AgentCapability.REASONING),
                    latency = AgentResourceLatency.INSTANT
                ),
                registration(
                    "hermes.home",
                    "Hermes - Home PC",
                    setOf(AgentCapability.CHAT, AgentCapability.REASONING),
                    latency = AgentResourceLatency.NORMAL
                )
            )
        )

        assertEquals("hermes.home", result.primaryAgentId)
        assertEquals("Hermes - Home PC", result.assignments.first().registration.displayName)
    }

    @Test
    fun pinnedAgentOutsideTheFirstSearchPageStillBecomesLead() {
        val registrations = (1..150).map { index ->
            registration(
                "agent-$index",
                "Architecture Expert $index",
                setOf(AgentCapability.CHAT, AgentCapability.REASONING),
                latency = AgentResourceLatency.INSTANT
            )
        } + registration(
            "hermes.requested",
            "Hermes - Requested PC",
            setOf(AgentCapability.CHAT, AgentCapability.REASONING),
            latency = AgentResourceLatency.SLOW
        )

        val result = AgentDynamicTeamCompiler().compile(
            AgentDynamicTeamRequest(
                goal = "Discuss the architecture",
                policy = AgentDynamicTeamPolicy(pinnedAgentIds = setOf("hermes.requested"))
            ),
            registrations
        )

        assertEquals("hermes.requested", result.primaryAgentId)
    }

    @Test
    fun aliasesForTheSameRuntimeCannotOccupyTwoTeamSeats() {
        val result = AgentDynamicTeamCompiler().compile(
            AgentDynamicTeamRequest(
                goal = "Implement and verify a Python program",
                policy = AgentDynamicTeamPolicy(forceTeam = true)
            ),
            listOf(
                registration(
                    "codex.alias-one",
                    "Codex",
                    setOf(AgentCapability.CHAT, AgentCapability.REASONING, AgentCapability.CODE),
                    failureDomain = "desktop-one"
                ).copy(runtimeFailureDomain = "desktop-one:codex"),
                registration(
                    "codex.alias-two",
                    "Codex Alias",
                    setOf(AgentCapability.CHAT, AgentCapability.REASONING, AgentCapability.CODE),
                    failureDomain = "desktop-one"
                ).copy(runtimeFailureDomain = "desktop-one:codex")
            )
        )

        assertEquals(AgentDynamicTeamOutcome.BLOCKED, result.outcome)
        assertEquals(1, result.assignments.size)
    }

    @Test
    fun collectiveCapabilityContractGivesEachMemberOnlyItsOwnRequirements() = runBlocking {
        val store = InMemoryAgentTeamExecutionStore()
        val runtime = AgentTeamExecutionRuntime(store)
        val observed = mutableMapOf<String, Set<AgentCapability>>()
        val definition = AgentTeamDefinition(
            teamId = "collective-team",
            primaryAgentId = "researcher",
            members = listOf(
                AgentTeamMember(
                    agentId = "researcher",
                    deliveryMode = AgentDeliveryMode.RESPOND,
                    requiredCapabilities = setOf(AgentCapability.LIVE_DATA),
                    role = "lead synthesizer"
                ),
                AgentTeamMember(
                    agentId = "implementer",
                    deliveryMode = AgentDeliveryMode.OBSERVE,
                    requiredCapabilities = setOf(AgentCapability.CODE),
                    role = "implementation specialist"
                )
            ),
            collectiveCapabilities = setOf(AgentCapability.LIVE_DATA, AgentCapability.CODE)
        )
        val request = AgentRunRequest(
            conversationId = "conversation",
            messageId = "message",
            taskId = "task",
            runId = "collective-run",
            goal = "Research and implement",
            requiredCapabilities = setOf(AgentCapability.LIVE_DATA, AgentCapability.CODE)
        )

        runtime.start(definition, request) { context ->
            observed[context.member.agentId] = context.request.requiredCapabilities
            AgentSubagentOutput(if (context.member.agentId == "researcher") "final" else "code")
        }.await()
        runtime.close()

        assertEquals(setOf(AgentCapability.LIVE_DATA), observed["researcher"])
        assertEquals(setOf(AgentCapability.CODE), observed["implementer"])
    }

    @Test
    fun teamDispatchCodecPreservesCollectiveCapabilityContract() {
        val spec = AgentTeamDispatchSpec(
            definition = AgentTeamDefinition(
                teamId = "codec-team",
                primaryAgentId = "lead",
                members = listOf(
                    AgentTeamMember(
                        "lead",
                        AgentDeliveryMode.RESPOND,
                        setOf(AgentCapability.LIVE_DATA)
                    ),
                    AgentTeamMember(
                        "coder",
                        AgentDeliveryMode.OBSERVE,
                        setOf(AgentCapability.CODE)
                    )
                ),
                collectiveCapabilities = setOf(AgentCapability.LIVE_DATA, AgentCapability.CODE)
            ),
            supervisorRunId = "codec-run"
        )

        val decoded = AgentTeamDispatchSpecCodec.decode(AgentTeamDispatchSpecCodec.encode(spec))

        assertEquals(spec.definition.collectiveCapabilities, decoded!!.definition.collectiveCapabilities)
    }

    private fun registration(
        agentId: String,
        displayName: String,
        capabilities: Set<AgentCapability>,
        location: AgentResourceLocation = AgentResourceLocation.TRUSTED_DESKTOP,
        trust: AgentResourceTrust = AgentResourceTrust.VERIFIED_PAIRED,
        failureDomain: String = "desktop-default",
        latency: AgentResourceLatency = AgentResourceLatency.NORMAL,
        cost: AgentResourceCost = AgentResourceCost.FREE,
        activeRuns: Int = 0,
        maxParallelRuns: Int = 4
    ): AgentRegistration = AgentRegistration(
        agentId = agentId,
        installationId = "installation-$agentId",
        deviceId = "device-$agentId",
        providerId = "signalasi-network",
        displayName = displayName,
        kind = AgentConnectorKind.AGENT,
        location = location,
        status = AgentEndpointStatus.ONLINE,
        capabilities = capabilities,
        protocol = AgentProtocolRange(
            preferred = "1.1",
            minimum = "1.0",
            maximum = "1.1",
            features = setOf("run.events", "run.recover")
        ),
        connectionKind = AgentConnectionKind.SIGNALASI_LINK,
        cost = cost,
        latency = latency,
        trust = trust,
        activeRuns = activeRuns,
        maxParallelRuns = maxParallelRuns,
        failureDomain = failureDomain
    )
}
