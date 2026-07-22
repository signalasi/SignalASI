package com.signalasi.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentPlanFactoryTest {
    @Test
    fun duplicateCallsToTheSameConnectorCollapseIntoOneAction() {
        val first = connectorAction("codex-1", "desktop:codex")
        val duplicate = connectorAction("codex-2", "desktop:codex")
        val dependent = AgentAction(
            id = "finish",
            kind = AgentActionKind.CREATE_NOTIFICATION,
            target = "phone",
            risk = AgentRisk.LOW,
            status = AgentActionStatus.PENDING_CONFIRMATION,
            description = "Notify when complete",
            parameters = mapOf("depends_on" to duplicate.id)
        )

        val plan = AgentPlanFactory.actions(request(), listOf(first, duplicate, dependent))

        assertEquals(listOf("codex-1", "finish"), plan.actions.map { it.id })
        assertEquals(listOf("codex-1"), plan.actions.last().dependencyIds())
    }

    @Test
    fun callsToDifferentConnectorsRemainIndependent() {
        val plan = AgentPlanFactory.actions(
            request(),
            listOf(connectorAction("codex", "desktop:codex"), connectorAction("hermes", "desktop:hermes"))
        )

        assertEquals(listOf("codex", "hermes"), plan.actions.map { it.id })
    }

    @Test
    fun emptyPlanFallsBackToAvailableReasoningConnector() {
        val plan = AgentPlanFactory.actions(request(), emptyList())

        assertEquals(1, plan.actions.size)
        assertEquals(AgentActionKind.CALL_CONNECTOR, plan.actions.single().kind)
        assertEquals("desktop:codex", plan.actions.single().parameters["connector_id"])
        assertFalse(plan.actions.any { it.target == "local-agent-runtime" })
        assertTrue(plan.validation.valid)
    }

    @Test
    fun emptyPlanWithoutProviderFailsExplicitlyInsteadOfUsingLocalRuntime() {
        val plan = AgentPlanFactory.actions(request(targets = emptyList()), emptyList())

        assertEquals(1, plan.actions.size)
        assertEquals(AgentActionKind.CALL_CONNECTOR, plan.actions.single().kind)
        assertEquals("reasoning-provider-unavailable", plan.actions.single().parameters["connector_id"])
        assertFalse(plan.actions.any { it.target == "local-agent-runtime" })
        assertTrue(plan.validation.valid)
    }

    private fun connectorAction(id: String, connectorId: String) = AgentAction(
        id = id,
        kind = AgentActionKind.CALL_CONNECTOR,
        target = connectorId,
        risk = AgentRisk.LOW,
        status = AgentActionStatus.PENDING_CONFIRMATION,
        description = "Run the task",
        parameters = mapOf("connector_id" to connectorId, "prompt" to "Convert the file")
    )

    private fun request(targets: List<AgentCallableTarget>? = null): AgentRequest {
        val screen = ScreenContext(foregroundApp = "SignalASI", pageTitle = "Agent")
        val target = AgentCallableTarget(
            id = "desktop:codex",
            title = "Codex",
            kind = AgentConnectorKind.AGENT,
            status = AgentConnectorStatus.AVAILABLE,
            capabilities = emptyList()
        )
        val callableTargets = targets ?: listOf(target.copy(capabilities = listOf(AgentCapability.CHAT)))
        return AgentRequest(
            goal = "Convert the file",
            screen = screen,
            targets = callableTargets,
            memories = emptyList(),
            runtimeContext = AgentRuntimeContextBuilder.build(
                sessionId = "test",
                goal = "Convert the file",
                screen = screen,
                permissionMode = PermissionMode.AUTO_LOW_RISK,
                highRiskGuard = true,
                memoryCapture = false,
                callableTargets = callableTargets,
                memories = emptyList(),
                nativeTools = emptyList()
            )
        )
    }
}
