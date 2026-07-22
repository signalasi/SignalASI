package com.signalasi.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class AgentPlannerSettingsConsumptionTest {
    @Test
    fun maximumActionsRejectsOversizedModelPlans() {
        val raw = planJson(
            actionJson("first", "READ_SCREEN"),
            actionJson("second", "BACK")
        )

        assertNull(AgentModelPlanParser.parse(request(), raw, AgentModelPlannerSettings(maxActions = 1)))
        assertNotNull(AgentModelPlanParser.parse(request(), raw, AgentModelPlannerSettings(maxActions = 2)))
    }

    @Test
    fun multiAgentCoordinationControlsDependencyGraphs() {
        val raw = planJson(
            actionJson("first", "READ_SCREEN"),
            actionJson("second", "BACK", dependsOn = listOf("first"))
        )

        assertNull(
            AgentModelPlanParser.parse(
                request(),
                raw,
                AgentModelPlannerSettings(multiAgentCoordination = false)
            )
        )
        assertNotNull(
            AgentModelPlanParser.parse(
                request(),
                raw,
                AgentModelPlannerSettings(multiAgentCoordination = true)
            )
        )
    }

    @Test
    fun maximumAgentHopsLimitsDependencyDepth() {
        val raw = planJson(
            actionJson("first", "READ_SCREEN"),
            actionJson("second", "BACK", dependsOn = listOf("first")),
            actionJson("third", "HOME", dependsOn = listOf("second"))
        )

        assertNull(AgentModelPlanParser.parse(request(), raw, AgentModelPlannerSettings(maxAgentHops = 1)))
        assertNotNull(AgentModelPlanParser.parse(request(), raw, AgentModelPlannerSettings(maxAgentHops = 3)))
    }

    @Test
    fun initialPlansRejectStandaloneDraftActions() {
        val raw = planJson(actionJson("draft", "DRAFT_PLAN", target = "local-agent-runtime"))

        assertNull(AgentModelPlanParser.parse(request(), raw, AgentModelPlannerSettings()))
    }

    @Test
    fun executablePlansRejectTrailingDraftActions() {
        val raw = planJson(
            actionJson("execute", "READ_SCREEN"),
            actionJson(
                ref = "draft",
                kind = "DRAFT_PLAN",
                dependsOn = listOf("execute"),
                target = "local-agent-runtime"
            )
        )

        assertNull(
            AgentModelPlanParser.parse(
                request(replanReason = "connector_response_received"),
                raw,
                AgentModelPlannerSettings()
            )
        )
    }

    @Test
    fun completedReplansAcceptOnlyTaskCompleteMarker() {
        val raw = planJson(actionJson("done", "DRAFT_PLAN", target = "task-complete"))

        val plan = AgentModelPlanParser.parse(
            request(replanReason = "connector_response_received"),
            raw,
            AgentModelPlannerSettings()
        )

        assertNotNull(plan)
        assertEquals("task-complete", plan?.actions?.single()?.target)
    }

    @Test
    fun maximumToolCallsStopsFurtherAutonomousActions() {
        val completed = action("completed", AgentActionStatus.COMPLETED)
        val pending = action("pending", AgentActionStatus.PENDING_CONFIRMATION)
        val plan = AgentPlanFactory.actions(request(), listOf(pending)).copy(
            actionHistory = listOf(completed)
        )

        val decision = AgentAutonomyGuard.review(
            plan = plan,
            action = pending,
            settings = AgentModelPlannerSettings(maxToolCalls = 1)
        )

        assertFalse(decision.allowed)
    }

    private fun request(replanReason: String = ""): AgentRequest {
        val screen = ScreenContext(foregroundApp = "SignalASI", pageTitle = "Agent")
        return AgentRequest(
            goal = "Test planning settings",
            screen = screen,
            targets = emptyList(),
            memories = emptyList(),
            runtimeContext = AgentRuntimeContextBuilder.build(
                sessionId = "planner-settings-test",
                goal = "Test planning settings",
                screen = screen,
                permissionMode = PermissionMode.AUTO_LOW_RISK,
                highRiskGuard = true,
                memoryCapture = false,
                callableTargets = emptyList(),
                memories = emptyList(),
                nativeTools = emptyList()
            ),
            replanReason = replanReason
        )
    }

    private fun action(id: String, status: AgentActionStatus) = AgentAction(
        id = id,
        kind = AgentActionKind.OPEN_APP,
        target = "com.signalasi.chat",
        risk = AgentRisk.LOW,
        status = status,
        description = id,
        parameters = mapOf("package" to "com.signalasi.chat")
    )

    private fun planJson(vararg actions: String): String =
        "{\"actions\":[${actions.joinToString(",")}] }"

    private fun actionJson(
        ref: String,
        kind: String,
        dependsOn: List<String> = emptyList(),
        target: String = ""
    ): String {
        val dependencies = dependsOn.joinToString(",") { "\"$it\"" }
        return "{\"ref\":\"$ref\",\"kind\":\"$kind\",\"target\":\"$target\",\"depends_on\":[$dependencies],\"parameters\":{}}"
    }
}
