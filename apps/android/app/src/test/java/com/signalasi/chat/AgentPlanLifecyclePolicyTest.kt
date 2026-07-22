package com.signalasi.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentPlanLifecyclePolicyTest {
    @Test
    fun restoredConnectorResultRemovesTrailingDraftAndCompletesSession() {
        val connector = action(
            id = "connector-codex",
            kind = AgentActionKind.CALL_CONNECTOR,
            target = "Codex",
            status = AgentActionStatus.COMPLETED,
            result = "The worksheet has been corrected."
        )
        val draft = action(
            id = "draft-plan",
            kind = AgentActionKind.DRAFT_PLAN,
            target = "local-agent-runtime",
            status = AgentActionStatus.COMPLETED
        )
        val session = session(
            phase = AgentPhase.PLANNING,
            plan = plan(connector, draft),
            result = AgentActionResult(draft.id, true, "")
        )

        val normalized = AgentPlanLifecyclePolicy.normalize(session)

        assertTrue(normalized.changed)
        assertEquals(listOf(connector), normalized.session.currentPlan?.actions)
        assertEquals(AgentPhase.COMPLETED, normalized.session.phase)
        assertEquals(connector.id, normalized.session.lastActionResult?.actionId)
        assertEquals(connector.result, normalized.session.lastActionResult?.message)
    }

    @Test
    fun pendingTrailingDraftIsRemovedBeforeItCanRun() {
        val connector = action(
            id = "connector-codex",
            kind = AgentActionKind.CALL_CONNECTOR,
            target = "Codex",
            status = AgentActionStatus.COMPLETED,
            result = "Done"
        )
        val draft = action(
            id = "draft-plan",
            kind = AgentActionKind.DRAFT_PLAN,
            target = "local-agent-runtime",
            status = AgentActionStatus.PENDING_CONFIRMATION
        )

        val normalized = AgentPlanLifecyclePolicy.normalize(plan(connector, draft))

        assertTrue(normalized.changed)
        assertEquals(listOf(connector), normalized.plan.actions)
    }

    @Test
    fun standaloneDraftAndTaskCompleteMarkersRemainValid() {
        val standalone = plan(
            action("draft-plan", AgentActionKind.DRAFT_PLAN, "local-agent-runtime", AgentActionStatus.COMPLETED)
        )
        val taskComplete = plan(
            action("connector", AgentActionKind.CALL_CONNECTOR, "Codex", AgentActionStatus.COMPLETED),
            action("done", AgentActionKind.DRAFT_PLAN, "task-complete", AgentActionStatus.COMPLETED)
        )

        assertFalse(AgentPlanLifecyclePolicy.normalize(standalone).changed)
        assertFalse(AgentPlanLifecyclePolicy.normalize(taskComplete).changed)
    }

    @Test
    fun standaloneRestoredDraftRecoversCompletedConnectorFromHistory() {
        val connector = action(
            id = "connector-codex",
            kind = AgentActionKind.CALL_CONNECTOR,
            target = "Codex",
            status = AgentActionStatus.COMPLETED,
            result = "Recovered Codex reply"
        )
        val draft = action(
            id = "replanned-draft",
            kind = AgentActionKind.DRAFT_PLAN,
            target = "local-agent-runtime",
            status = AgentActionStatus.COMPLETED
        )
        val sourcePlan = plan(draft).copy(actionHistory = listOf(connector))
        val sourceSession = session(
            phase = AgentPhase.PLANNING,
            plan = sourcePlan,
            result = AgentActionResult(draft.id, true, "")
        )

        val normalized = AgentPlanLifecyclePolicy.normalize(sourceSession)

        assertTrue(normalized.changed)
        assertEquals(listOf(connector), normalized.session.currentPlan?.actions)
        assertTrue(normalized.session.currentPlan?.actionHistory.orEmpty().isEmpty())
        assertEquals(AgentPhase.COMPLETED, normalized.session.phase)
        assertEquals("Recovered Codex reply", normalized.session.lastActionResult?.message)
    }

    @Test
    fun receivedConnectorResponseNeverRestoresIntoLocalRuntimeDraft() {
        val draft = action(
            id = "replanned-draft",
            kind = AgentActionKind.DRAFT_PLAN,
            target = "local-agent-runtime",
            status = AgentActionStatus.COMPLETED
        )
        val sourcePlan = plan(draft).copy(
            route = AgentRoute(
                kind = AgentRouteKind.DESKTOP_AGENT,
                targetTitle = "Codex"
            )
        )
        val sourceSession = session(
            phase = AgentPhase.PLANNING,
            plan = sourcePlan,
            result = AgentActionResult(draft.id, true, "Created a local task plan")
        ).copy(
            auditTrail = listOf(
                AgentAuditEntry(
                    event = AgentAuditEvent.CONNECTOR_RESPONSE_RECEIVED,
                    detail = "source_message_id=1",
                    timestampMillis = 2L
                )
            )
        )
        val durableTask = AgentTaskRecord(
            taskId = sourcePlan.planId,
            sessionId = "session",
            goal = sourcePlan.goal,
            phase = AgentPhase.COMPLETED,
            routeKind = AgentRouteKind.DESKTOP_AGENT,
            targetTitle = "Codex",
            risk = AgentRisk.LOW,
            blocked = false,
            result = "Durable Codex result"
        )

        val recovered = AgentPlanLifecyclePolicy.recoverCompletedConnector(
            sourceSession,
            durableTask,
            "No final result"
        )

        assertEquals(AgentPhase.COMPLETED, recovered.phase)
        assertEquals(AgentActionKind.CALL_CONNECTOR, recovered.currentPlan?.actions?.single()?.kind)
        assertEquals("Codex", recovered.currentPlan?.actions?.single()?.target)
        assertEquals("Durable Codex result", recovered.lastActionResult?.message)
        assertTrue(recovered.currentPlan?.actions.orEmpty().none { it.target == "local-agent-runtime" })
    }

    @Test
    fun localRuntimeDraftWithoutConnectorReceiptIsNotRewritten() {
        val draft = action(
            id = "draft",
            kind = AgentActionKind.DRAFT_PLAN,
            target = "local-agent-runtime",
            status = AgentActionStatus.PENDING_CONFIRMATION
        )
        val source = session(AgentPhase.PLANNING, plan(draft), null)

        val recovered = AgentPlanLifecyclePolicy.recoverCompletedConnector(source, null, "No final result")

        assertEquals(source, recovered)
    }

    private fun plan(vararg actions: AgentAction): AgentPlan = AgentPlan(
        goal = "Correct the worksheet",
        screen = ScreenContext(foregroundApp = "SignalASI", pageTitle = "Agent"),
        steps = emptyList(),
        actions = actions.toList()
    )

    private fun session(
        phase: AgentPhase,
        plan: AgentPlan,
        result: AgentActionResult?
    ): AgentSessionSnapshot = AgentSessionSnapshot(
        sessionId = "session",
        phase = phase,
        currentGoal = plan.goal,
        currentScreen = plan.screen,
        currentPlan = plan,
        auditTrail = emptyList(),
        lastActionResult = result,
        updatedAtMillis = 1L
    )

    private fun action(
        id: String,
        kind: AgentActionKind,
        target: String,
        status: AgentActionStatus,
        result: String = ""
    ): AgentAction = AgentAction(
        id = id,
        kind = kind,
        target = target,
        risk = AgentRisk.LOW,
        status = status,
        description = id,
        result = result
    )
}
