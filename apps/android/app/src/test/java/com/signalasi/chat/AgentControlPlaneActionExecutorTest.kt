package com.signalasi.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class AgentControlPlaneActionExecutorTest {
    @Test
    fun productionConnectorActionRunsThroughAdapterOnlyOnce() {
        val executions = AtomicInteger()
        val delegate = object : AgentActionExecutor {
            override fun execute(action: AgentAction, screen: ScreenContext): AgentActionResult {
                executions.incrementAndGet()
                return AgentActionResult(
                    action.id,
                    true,
                    "Waiting",
                    mapOf("awaiting_response" to "true", "source_message_id" to "42")
                )
            }
        }
        val provider = ActionExecutorAgentProvider({ listOf(registration()) }, delegate)
        val executor = AgentControlPlaneActionExecutor(provider)
        val action = connectorAction()

        val first = executor.execute(action, ScreenContext(foregroundApp = "SignalASI", pageTitle = "Agent"))
        val replay = executor.execute(action, ScreenContext(foregroundApp = "SignalASI", pageTitle = "Agent"))

        assertTrue(first.success)
        assertTrue(replay.success)
        assertEquals(1, executions.get())
        assertEquals(first.metadata["control_plane_run_id"], replay.metadata["control_plane_run_id"])
        assertEquals("codex", first.metadata["control_plane_agent_id"])
    }

    @Test
    fun ignoreDeliveryNeverTouchesConnectorTransport() {
        val executions = AtomicInteger()
        val delegate = object : AgentActionExecutor {
            override fun execute(action: AgentAction, screen: ScreenContext): AgentActionResult {
                executions.incrementAndGet()
                return AgentActionResult(action.id, true, "unexpected")
            }
        }
        val executor = AgentControlPlaneActionExecutor(
            ActionExecutorAgentProvider({ listOf(registration()) }, delegate)
        )

        val result = executor.execute(
            connectorAction().copy(
                parameters = connectorAction().parameters + ("delivery_mode" to "ignore")
            ),
            ScreenContext(foregroundApp = "SignalASI", pageTitle = "Agent")
        )

        assertTrue(result.success)
        assertEquals("ignore", result.metadata["delivery_mode"])
        assertEquals(0, executions.get())
    }

    @Test
    fun incompatibleAgentProtocolFailsWithoutBypassingAdapter() {
        val executions = AtomicInteger()
        val delegate = object : AgentActionExecutor {
            override fun execute(action: AgentAction, screen: ScreenContext): AgentActionResult {
                executions.incrementAndGet()
                return AgentActionResult(action.id, true, "unexpected")
            }
        }
        val incompatible = registration().copy(
            protocol = AgentProtocolRange("2.0", "2.0", "2.1", setOf("message.respond"))
        )
        val executor = AgentControlPlaneActionExecutor(
            ActionExecutorAgentProvider({ listOf(incompatible) }, delegate)
        )

        val result = executor.execute(
            connectorAction(),
            ScreenContext(foregroundApp = "SignalASI", pageTitle = "Agent")
        )

        assertFalse(result.success)
        assertTrue(result.message.contains("compatible", ignoreCase = true))
        assertEquals(0, executions.get())
    }

    private fun connectorAction() = AgentAction(
        id = "route-codex",
        kind = AgentActionKind.CALL_CONNECTOR,
        target = "Codex",
        risk = AgentRisk.LOW,
        status = AgentActionStatus.PROPOSED,
        description = "Ask Codex",
        parameters = mapOf(
            "connector_id" to "codex",
            "prompt" to "Inspect the project",
            "_signalasi_conversation_id" to "conversation",
            "_signalasi_turn_id" to "turn"
        ),
        requiresConfirmation = false
    )

    private fun registration() = AgentRegistration(
        agentId = "codex",
        installationId = "desktop-installation",
        deviceId = "desktop-device",
        providerId = "desktop-provider",
        displayName = "Codex",
        kind = AgentConnectorKind.AGENT,
        location = AgentResourceLocation.TRUSTED_DESKTOP,
        status = AgentEndpointStatus.ONLINE,
        capabilities = setOf(AgentCapability.CODE, AgentCapability.TASK_EXECUTION),
        protocol = AgentProtocolRange(
            preferred = "1.0",
            minimum = "1.0",
            maximum = "1.0",
            features = setOf("run.cancel", "run.recover", "run.events", "message.respond", "message.observe")
        ),
        connectionKind = AgentConnectionKind.SIGNALASI_LINK,
        trust = AgentResourceTrust.VERIFIED_PAIRED
    )
}
