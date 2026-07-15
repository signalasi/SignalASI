package com.signalasi.chat

import org.junit.Assert.assertEquals
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

    private fun connectorAction(id: String, connectorId: String) = AgentAction(
        id = id,
        kind = AgentActionKind.CALL_CONNECTOR,
        target = connectorId,
        risk = AgentRisk.LOW,
        status = AgentActionStatus.PENDING_CONFIRMATION,
        description = "Run the task",
        parameters = mapOf("connector_id" to connectorId, "prompt" to "Convert the file")
    )

    private fun request(): AgentRequest {
        val screen = ScreenContext(foregroundApp = "SignalASI", pageTitle = "Agent")
        val target = AgentCallableTarget(
            id = "desktop:codex",
            title = "Codex",
            kind = AgentConnectorKind.AGENT,
            status = AgentConnectorStatus.AVAILABLE,
            capabilities = emptyList()
        )
        return AgentRequest(
            goal = "Convert the file",
            screen = screen,
            targets = listOf(target),
            memories = emptyList(),
            runtimeContext = AgentRuntimeContextBuilder.build(
                sessionId = "test",
                goal = "Convert the file",
                screen = screen,
                permissionMode = PermissionMode.AUTO_LOW_RISK,
                highRiskGuard = true,
                memoryCapture = false,
                callableTargets = listOf(target),
                memories = emptyList(),
                nativeTools = emptyList()
            )
        )
    }
}
