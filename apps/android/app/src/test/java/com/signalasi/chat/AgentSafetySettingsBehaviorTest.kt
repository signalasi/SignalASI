package com.signalasi.chat

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentSafetySettingsBehaviorTest {
    @Test
    fun permissionModesEnforceFourDistinctExecutionBoundaries() {
        val read = action(AgentActionKind.READ_SCREEN)
        val draft = action(AgentActionKind.DRAFT_PLAN)
        val direct = action(AgentActionKind.OPEN_APP)

        assertFalse(review(settings(PermissionMode.OBSERVE_ONLY), read).blocked)
        assertTrue(review(settings(PermissionMode.OBSERVE_ONLY), draft).blocked)

        assertFalse(review(settings(PermissionMode.SUGGEST_ONLY), read, draft).blocked)
        assertTrue(review(settings(PermissionMode.SUGGEST_ONLY), direct).blocked)

        val askReview = review(settings(PermissionMode.ASK_BEFORE_ACTION), direct)
        assertFalse(askReview.blocked)
        assertTrue(askReview.requiresConfirmation)

        val connectorReview = review(
            settings(PermissionMode.ASK_BEFORE_ACTION),
            action(AgentActionKind.CALL_CONNECTOR)
        )
        assertFalse(connectorReview.blocked)
        assertFalse(connectorReview.requiresConfirmation)

        val automaticReview = review(settings(PermissionMode.AUTO_LOW_RISK), direct)
        assertFalse(automaticReview.blocked)
        assertFalse(automaticReview.requiresConfirmation)
    }

    @Test
    fun executionAndCapabilitySwitchesAreConsumedBySafetyReview() {
        assertBlocked(
            AgentSafetySettings(permissionMode = PermissionMode.AUTO_LOW_RISK, executionPaused = true),
            action(AgentActionKind.OPEN_APP),
            "execution_paused"
        )
        assertBlocked(
            AgentSafetySettings(permissionMode = PermissionMode.AUTO_LOW_RISK, screenObservationAllowed = false),
            action(AgentActionKind.TAP),
            "screen_observation"
        )
        assertBlocked(
            AgentSafetySettings(permissionMode = PermissionMode.AUTO_LOW_RISK, localActionsAllowed = false),
            action(AgentActionKind.OPEN_APP),
            "local_actions"
        )
        assertBlocked(
            AgentSafetySettings(permissionMode = PermissionMode.AUTO_LOW_RISK, memoryCapture = false),
            action(AgentActionKind.SAVE_SCREEN_KNOWLEDGE),
            "memory_capture"
        )
        assertBlocked(
            AgentSafetySettings(permissionMode = PermissionMode.AUTO_LOW_RISK, connectorCallsAllowed = false),
            action(AgentActionKind.CALL_CONNECTOR),
            "connector_calls"
        )
        assertBlocked(
            AgentSafetySettings(permissionMode = PermissionMode.AUTO_LOW_RISK, deviceControlAllowed = false),
            action(AgentActionKind.CONTROL_DEVICE),
            "device_control"
        )
    }

    @Test
    fun phoneDevelopmentRuntimeContinuesWithoutASecondConfirmation() {
        val runtimeAction = action(AgentActionKind.CALL_NATIVE_TOOL).copy(
            parameters = mapOf(
                "tool_id" to AgentOnDeviceRuntimeTools.EXECUTE,
                PHONE_DEVELOPMENT_MANIFEST_PARAMETER to "true"
            )
        )

        val result = review(settings(PermissionMode.ASK_BEFORE_ACTION), runtimeAction)

        assertFalse(result.blocked)
        assertFalse(result.requiresConfirmation)
    }

    @Test
    fun highRiskGuardBlocksExplicitlyBlockedActions() {
        val blockedAction = action(AgentActionKind.LOCK_SCREEN, AgentRisk.BLOCKED)
        assertTrue(
            review(
                AgentSafetySettings(
                    permissionMode = PermissionMode.AUTO_LOW_RISK,
                    highRiskGuard = true
                ),
                blockedAction
            ).blocked
        )
        assertFalse(
            review(
                AgentSafetySettings(
                    permissionMode = PermissionMode.AUTO_LOW_RISK,
                    highRiskGuard = false
                ),
                blockedAction
            ).blocked
        )
    }

    private fun settings(mode: PermissionMode) = AgentSafetySettings(permissionMode = mode)

    private fun review(settings: AgentSafetySettings, vararg actions: AgentAction): AgentSafetyReview {
        val store = MutableSafetyStore(settings)
        return DefaultAgentSafetyPolicy(store).review(
            AgentPlan(
                goal = "test",
                screen = ScreenContext(foregroundApp = "SignalASI", pageTitle = "Agent"),
                steps = emptyList(),
                actions = actions.toList()
            )
        )
    }

    private fun assertBlocked(settings: AgentSafetySettings, action: AgentAction, denied: String) {
        val result = review(settings, action)
        assertTrue(result.blocked)
        assertTrue(denied in result.deniedPermissions)
    }

    private fun action(
        kind: AgentActionKind,
        risk: AgentRisk = AgentRisk.LOW
    ) = AgentAction(
        id = kind.name.lowercase(),
        kind = kind,
        target = "test",
        risk = risk,
        status = AgentActionStatus.PENDING_CONFIRMATION,
        description = "test action"
    )

    private class MutableSafetyStore(
        private var settings: AgentSafetySettings
    ) : AgentSafetySettingsStore {
        override fun load(): AgentSafetySettings = settings
        override fun save(settings: AgentSafetySettings) {
            this.settings = settings
        }
    }
}
