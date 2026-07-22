package com.signalasi.chat

data class AgentPlanLifecycleNormalization(
    val plan: AgentPlan,
    val removedActions: List<AgentAction>
) {
    val changed: Boolean
        get() = removedActions.isNotEmpty()

    fun recoverResult(previous: AgentActionResult?): AgentActionResult? {
        if (!changed) return previous
        val removedIds = removedActions.mapTo(hashSetOf(), AgentAction::id)
        if (previous != null && previous.actionId !in removedIds && previous.message.isNotBlank()) return previous
        val action = plan.actions.asReversed().firstOrNull {
            it.result.isNotBlank() && it.status in RESULT_STATUSES
        } ?: return previous
        return AgentActionResult(
            actionId = action.id,
            success = action.status == AgentActionStatus.COMPLETED,
            message = action.result
        )
    }

    companion object {
        private val RESULT_STATUSES = setOf(
            AgentActionStatus.COMPLETED,
            AgentActionStatus.FAILED,
            AgentActionStatus.BLOCKED
        )
    }
}

data class AgentSessionLifecycleNormalization(
    val session: AgentSessionSnapshot,
    val removedActions: List<AgentAction>
) {
    val changed: Boolean
        get() = removedActions.isNotEmpty()
}

object AgentPlanLifecyclePolicy {
    fun normalize(plan: AgentPlan): AgentPlanLifecycleNormalization {
        val trailingDrafts = plan.actions.asReversed()
            .takeWhile { action ->
                action.kind == AgentActionKind.DRAFT_PLAN &&
                    !action.target.equals(TASK_COMPLETE_TARGET, ignoreCase = true)
            }
            .asReversed()
        if (trailingDrafts.isEmpty()) {
            return AgentPlanLifecycleNormalization(plan, emptyList())
        }
        if (trailingDrafts.size == plan.actions.size) {
            return recoverCompletedHistory(plan, trailingDrafts)
        }
        val retainedActions = plan.actions.dropLast(trailingDrafts.size)
        if (retainedActions.none { it.kind != AgentActionKind.DRAFT_PLAN }) {
            return AgentPlanLifecycleNormalization(plan, emptyList())
        }
        val removedIds = trailingDrafts.mapTo(hashSetOf(), AgentAction::id)
        val normalized = plan.copy(
            actions = retainedActions,
            verificationResults = plan.verificationResults.filterNot { it.actionId in removedIds },
            checkpoints = plan.checkpoints.filterNot { it.actionId in removedIds }
        ).let { candidate ->
            candidate.copy(validation = AgentPlanValidator.validate(candidate))
        }
        return AgentPlanLifecycleNormalization(normalized, trailingDrafts)
    }

    private fun recoverCompletedHistory(
        plan: AgentPlan,
        drafts: List<AgentAction>
    ): AgentPlanLifecycleNormalization {
        val recoveredIndex = plan.actionHistory.indexOfLast { action ->
            action.kind != AgentActionKind.DRAFT_PLAN &&
                action.status == AgentActionStatus.COMPLETED &&
                action.result.isNotBlank()
        }
        if (recoveredIndex < 0) return AgentPlanLifecycleNormalization(plan, emptyList())
        val recoveredAction = plan.actionHistory[recoveredIndex]
        val retainedHistory = plan.actionHistory.toMutableList().also { it.removeAt(recoveredIndex) }
        val removedIds = drafts.mapTo(hashSetOf(), AgentAction::id)
        val normalized = plan.copy(
            actions = listOf(recoveredAction),
            actionHistory = retainedHistory,
            selectedAgentOrModel = recoveredAction.target,
            expectedResult = recoveredAction.result,
            verificationResults = plan.verificationResults.filterNot { it.actionId in removedIds },
            checkpoints = plan.checkpoints.filterNot { it.actionId in removedIds }
        ).let { candidate ->
            candidate.copy(validation = AgentPlanValidator.validate(candidate))
        }
        return AgentPlanLifecycleNormalization(normalized, drafts)
    }

    fun normalize(session: AgentSessionSnapshot): AgentSessionLifecycleNormalization {
        val plan = session.currentPlan ?: return AgentSessionLifecycleNormalization(session, emptyList())
        val planNormalization = normalize(plan)
        if (!planNormalization.changed) return AgentSessionLifecycleNormalization(session, emptyList())
        val result = planNormalization.recoverResult(session.lastActionResult)
        val normalizedSession = session.copy(
            phase = resolvedPhase(planNormalization.plan, session.phase),
            currentPlan = planNormalization.plan,
            lastActionResult = result
        )
        return AgentSessionLifecycleNormalization(normalizedSession, planNormalization.removedActions)
    }

    fun recoverCompletedConnector(
        session: AgentSessionSnapshot,
        persistedTask: AgentTaskRecord?,
        missingResult: String
    ): AgentSessionSnapshot {
        val plan = session.currentPlan ?: return session
        val receivedConnectorResponse = session.auditTrail.any {
            it.event == AgentAuditEvent.CONNECTOR_RESPONSE_RECEIVED
        }
        val staleRuntimeDrafts = plan.actions.isNotEmpty() && plan.actions.all {
            it.kind == AgentActionKind.DRAFT_PLAN &&
                it.target.equals(LOCAL_AGENT_RUNTIME_TARGET, ignoreCase = true)
        }
        if (!receivedConnectorResponse || !staleRuntimeDrafts) return session

        val durableResult = persistedTask
            ?.takeIf { task ->
                task.result.isNotBlank() &&
                    !task.targetTitle.equals(LOCAL_AGENT_RUNTIME_TARGET, ignoreCase = true) &&
                    task.routeKind in CONNECTOR_ROUTE_KINDS
            }
            ?.result
            .orEmpty()
        val previousResult = session.lastActionResult
            ?.takeIf { result ->
                result.message.isNotBlank() &&
                    plan.actions.none { it.id == result.actionId }
            }
            ?.message
            .orEmpty()
        val resultText = durableResult.ifBlank { previousResult }.ifBlank { missingResult.trim() }
        if (resultText.isBlank()) return session
        val recoveredAction = plan.actionHistory.asReversed().firstOrNull {
            it.kind == AgentActionKind.CALL_CONNECTOR
        }?.copy(
            status = AgentActionStatus.COMPLETED,
            result = resultText,
            evidence = "restored_connector_terminal_result"
        ) ?: AgentAction(
            id = "restored-connector-result",
            kind = AgentActionKind.CALL_CONNECTOR,
            target = persistedTask?.targetTitle.orEmpty().ifBlank { plan.route.targetTitle.ifBlank { "remote-agent" } },
            risk = persistedTask?.risk ?: AgentRisk.LOW,
            status = AgentActionStatus.COMPLETED,
            description = "Restore completed remote Agent result",
            requiresConfirmation = false,
            result = resultText,
            evidence = "restored_connector_terminal_result"
        )
        val normalizedPlan = plan.copy(
            actions = listOf(recoveredAction),
            selectedAgentOrModel = recoveredAction.target,
            expectedResult = resultText,
            actionHistory = plan.actionHistory.filterNot { it.id == recoveredAction.id },
            confirmationRequired = false
        ).let { candidate ->
            candidate.copy(validation = AgentPlanValidator.validate(candidate))
        }
        return session.copy(
            phase = AgentPhase.COMPLETED,
            currentPlan = normalizedPlan,
            lastActionResult = AgentActionResult(
                actionId = recoveredAction.id,
                success = true,
                message = resultText
            )
        )
    }

    private fun resolvedPhase(plan: AgentPlan, fallback: AgentPhase): AgentPhase = when {
        plan.actions.any { it.status == AgentActionStatus.WAITING_RESPONSE } -> AgentPhase.WAITING_RESPONSE
        plan.actions.any { it.status == AgentActionStatus.RUNNING } -> AgentPhase.PAUSED
        plan.actions.any {
            it.status == AgentActionStatus.PENDING_CONFIRMATION || it.status == AgentActionStatus.PROPOSED
        } -> AgentPhase.WAITING_CONFIRMATION
        plan.actions.any { it.status == AgentActionStatus.FAILED } -> AgentPhase.FAILED
        plan.actions.any { it.status == AgentActionStatus.BLOCKED } -> AgentPhase.BLOCKED
        plan.actions.isNotEmpty() && plan.actions.all { it.status in TERMINAL_STATUSES } -> AgentPhase.COMPLETED
        else -> fallback
    }

    private val TERMINAL_STATUSES = setOf(
        AgentActionStatus.COMPLETED,
        AgentActionStatus.FAILED,
        AgentActionStatus.BLOCKED,
        AgentActionStatus.ROLLED_BACK
    )
    private val CONNECTOR_ROUTE_KINDS = setOf(
        AgentRouteKind.DESKTOP_AGENT,
        AgentRouteKind.CLOUD_MODEL,
        AgentRouteKind.LOCAL_MODEL
    )
    private const val TASK_COMPLETE_TARGET = "task-complete"
    private const val LOCAL_AGENT_RUNTIME_TARGET = "local-agent-runtime"
}
