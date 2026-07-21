package com.signalasi.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentTeamPlanBridgeTest {
    @Test
    fun branchedAgentGraphCompilesIntoOneSupervisedTeamAction() {
        val research = agentAction("research", "researcher").withAgentKnowledge("research-only")
        val review = agentAction("review", "reviewer")
        val synthesis = agentAction(
            id = "synthesis",
            connectorId = "lead",
            dependsOn = listOf("research", "review"),
            outputSources = listOf("research", "review")
        ).withAgentKnowledge("lead-only")

        val compiled = AgentTeamPlanCompiler.compile(
            plan = plan(research, review, synthesis),
            targets = targets(),
            enabled = true
        )

        assertEquals(1, compiled.actions.size)
        val action = compiled.actions.single()
        assertTrue(action.id.startsWith("agent-team-"))
        assertTrue(action.parameters[AGENT_TEAM_SOURCE_PARAMETER]!!.toLong() > 0L)
        val spec = AgentTeamDispatchSpecCodec.decode(action.parameters[AGENT_TEAM_SPEC_PARAMETER].orEmpty())
        assertNotNull(spec)
        assertEquals("lead", spec!!.definition.primaryAgentId)
        assertEquals(3, spec.definition.members.size)
        assertEquals(
            AgentDeliveryMode.RESPOND,
            spec.definition.members.single { it.agentId == "lead" }.deliveryMode
        )
        assertEquals(
            setOf("researcher", "reviewer"),
            spec.definition.members.single { it.agentId == "lead" }.dependsOnAgentIds
        )
        assertTrue(spec.definition.members.filter { it.agentId != "lead" }.all {
            it.deliveryMode == AgentDeliveryMode.OBSERVE
        })
        assertEquals(
            "research-only",
            spec.definition.members.single { it.agentId == "researcher" }
                .context["_signalasi_agent_knowledge_context"]
        )
        assertEquals(
            "lead-only",
            spec.definition.members.single { it.agentId == "lead" }
                .context["_signalasi_agent_knowledge_context"]
        )
        assertTrue(compiled.validation.valid)
    }

    @Test
    fun downstreamOutputDependencyIsRemappedToTheTeamResult() {
        val research = agentAction("research", "researcher")
        val synthesis = agentAction(
            id = "synthesis",
            connectorId = "lead",
            dependsOn = listOf("research"),
            outputSources = listOf("research")
        )
        val downstream = connectorAction(
            id = "publish",
            connectorId = "cloud",
            dependsOn = listOf("synthesis"),
            outputSources = listOf("synthesis")
        )

        val compiled = AgentTeamPlanCompiler.compile(
            plan = plan(research, synthesis, downstream),
            targets = targets() + target("cloud", AgentConnectorKind.MODEL),
            enabled = true
        )

        assertEquals(2, compiled.actions.size)
        val teamId = compiled.actions.first().id
        assertEquals(listOf(teamId), compiled.actions.last().dependencyIds())
        assertEquals(listOf(teamId), compiled.actions.last().outputSourceIds())
        assertTrue(compiled.validation.valid)
    }

    @Test
    fun independentRespondersRemainOrdinaryActions() {
        val original = plan(
            agentAction("first", "researcher"),
            agentAction("second", "lead")
        )

        val compiled = AgentTeamPlanCompiler.compile(original, targets(), enabled = true)

        assertEquals(original.actions, compiled.actions)
    }

    @Test
    fun repeatedAgentIdentityCannotBecomeAHostTeam() {
        val original = plan(
            agentAction("first", "researcher"),
            agentAction("second", "researcher", dependsOn = listOf("first"))
        )

        val compiled = AgentTeamPlanCompiler.compile(original, targets(), enabled = true)

        assertEquals(original.actions, compiled.actions)
    }

    @Test
    fun externalPrerequisitePreventsUnsafePartialCompilation() {
        val prerequisite = AgentAction(
            id = "phone-step",
            kind = AgentActionKind.DRAFT_PLAN,
            target = "phone",
            risk = AgentRisk.LOW,
            status = AgentActionStatus.PENDING_CONFIRMATION,
            description = "Prepare phone evidence"
        )
        val research = agentAction("research", "researcher", dependsOn = listOf("phone-step"))
        val synthesis = agentAction("synthesis", "lead", dependsOn = listOf("research"))
        val original = plan(prerequisite, research, synthesis)

        val compiled = AgentTeamPlanCompiler.compile(original, targets(), enabled = true)

        assertEquals(original.actions, compiled.actions)
    }

    @Test
    fun malformedOrMultiResponderSpecsAreRejected() {
        val valid = AgentTeamDispatchSpec(
            AgentTeamDefinition(
                teamId = "team",
                primaryAgentId = "lead",
                members = listOf(
                    AgentTeamMember("researcher", AgentDeliveryMode.OBSERVE),
                    AgentTeamMember("lead", AgentDeliveryMode.RESPOND)
                )
            ),
            supervisorRunId = "run"
        )
        val encoded = AgentTeamDispatchSpecCodec.encode(valid)
            .replace("\"delivery_mode\":\"OBSERVE\"", "\"delivery_mode\":\"RESPOND\"")

        assertNull(AgentTeamDispatchSpecCodec.decode(encoded))
        assertNull(AgentTeamDispatchSpecCodec.decode("{\"version\":1}"))
    }

    @Test
    fun retryCreatesANewPersistableTeamAttempt() {
        val compiled = AgentTeamPlanCompiler.compile(
            plan = plan(
                agentAction("research", "researcher"),
                agentAction("synthesis", "lead", dependsOn = listOf("research"))
            ),
            targets = targets(),
            enabled = true
        )
        val original = compiled.actions.single()

        val retry = original.rekeyAgentTeamForRetry()
        val originalSpec = requireNotNull(
            AgentTeamDispatchSpecCodec.decode(original.parameters[AGENT_TEAM_SPEC_PARAMETER].orEmpty())
        )
        val retrySpec = requireNotNull(
            AgentTeamDispatchSpecCodec.decode(retry.parameters[AGENT_TEAM_SPEC_PARAMETER].orEmpty())
        )

        assertTrue(originalSpec.definition.teamId != retrySpec.definition.teamId)
        assertTrue(originalSpec.supervisorRunId != retrySpec.supervisorRunId)
        assertTrue(originalSpec.sourceMessageId != retrySpec.sourceMessageId)
        assertEquals(retrySpec.supervisorRunId, retry.parameters[AGENT_TEAM_RUN_PARAMETER])
        assertEquals(retrySpec.sourceMessageId.toString(), retry.parameters[AGENT_TEAM_SOURCE_PARAMETER])
    }

    private fun plan(vararg actions: AgentAction): AgentPlan {
        val draft = AgentPlan(
            goal = "Research and synthesize a verified answer",
            screen = ScreenContext(foregroundApp = "SignalASI", pageTitle = "Agent"),
            steps = emptyList(),
            actions = actions.toList(),
            route = AgentRoute(kind = AgentRouteKind.DESKTOP_AGENT)
        )
        return draft.copy(validation = AgentPlanValidator.validate(draft))
    }

    private fun agentAction(
        id: String,
        connectorId: String,
        dependsOn: List<String> = emptyList(),
        outputSources: List<String> = emptyList()
    ): AgentAction = connectorAction(id, connectorId, dependsOn, outputSources)

    private fun connectorAction(
        id: String,
        connectorId: String,
        dependsOn: List<String> = emptyList(),
        outputSources: List<String> = emptyList()
    ) = AgentAction(
        id = id,
        kind = AgentActionKind.CALL_CONNECTOR,
        target = connectorId,
        risk = AgentRisk.MEDIUM,
        status = AgentActionStatus.PENDING_CONFIRMATION,
        description = "Run $id",
        parameters = mapOf(
            "connector_id" to connectorId,
            "prompt" to "Complete $id",
            "node_ref" to id,
            "depends_on" to dependsOn.joinToString(","),
            "use_outputs_from" to outputSources.joinToString(","),
            "_signalasi_conversation_id" to "conversation",
            "_signalasi_turn_id" to "turn"
        )
    )

    private fun targets(): List<AgentCallableTarget> = listOf(
        target("researcher", AgentConnectorKind.AGENT, AgentCapability.RESEARCH),
        target("reviewer", AgentConnectorKind.AGENT, AgentCapability.RESEARCH),
        target("lead", AgentConnectorKind.AGENT, AgentCapability.REASONING)
    )

    private fun target(
        id: String,
        kind: AgentConnectorKind,
        capability: AgentCapability = AgentCapability.CHAT
    ) = AgentCallableTarget(
        id = id,
        title = id,
        kind = kind,
        status = AgentConnectorStatus.AVAILABLE,
        capabilities = listOf(capability)
    )

    private fun AgentAction.withAgentKnowledge(value: String): AgentAction = copy(
        parameters = parameters + ("_signalasi_agent_knowledge_context" to value)
    )
}
