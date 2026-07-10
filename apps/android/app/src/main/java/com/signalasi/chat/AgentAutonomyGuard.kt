package com.signalasi.chat

data class AgentAutonomyDecision(
    val allowed: Boolean,
    val reason: String = "",
    val completedToolCalls: Int = 0,
    val repeatedCalls: Int = 0
)

object AgentAutonomyGuard {
    fun completedToolCalls(plan: AgentPlan): Int = (plan.actionHistory + plan.actions).count { candidate ->
        candidate.kind.isBudgetedToolCall() && candidate.status in TERMINAL_TOOL_STATUSES
    }

    fun review(
        plan: AgentPlan,
        action: AgentAction,
        settings: AgentModelPlannerSettings
    ): AgentAutonomyDecision {
        val history = plan.actionHistory + plan.actions
        val completedCalls = completedToolCalls(plan)
        if (completedCalls >= settings.maxToolCalls) {
            return AgentAutonomyDecision(
                allowed = false,
                reason = "Autonomous tool-call budget reached",
                completedToolCalls = completedCalls
            )
        }
        val signature = action.autonomySignature()
        val repeatedCalls = history.count { candidate ->
            candidate.kind.isLoopSensitiveToolCall() &&
                candidate.status in TERMINAL_TOOL_STATUSES &&
                candidate.autonomySignature() == signature
        }
        if (action.kind.isLoopSensitiveToolCall() && repeatedCalls >= MAX_REPEATED_TOOL_CALLS) {
            return AgentAutonomyDecision(
                allowed = false,
                reason = "Repeated autonomous tool-call loop blocked",
                completedToolCalls = completedCalls,
                repeatedCalls = repeatedCalls
            )
        }
        return AgentAutonomyDecision(
            allowed = true,
            completedToolCalls = completedCalls,
            repeatedCalls = repeatedCalls
        )
    }

    private fun AgentAction.autonomySignature(): String = listOf(
        kind.name,
        parameters["connector_id"].orEmpty(),
        parameters["package"].orEmpty(),
        parameters["url"].orEmpty(),
        parameters["prompt"].orEmpty().trim().hashCode().toString(),
        target
    ).joinToString("|")

    private fun AgentActionKind.isBudgetedToolCall(): Boolean = this !in setOf(
        AgentActionKind.READ_SCREEN,
        AgentActionKind.DRAFT_PLAN
    )

    private fun AgentActionKind.isLoopSensitiveToolCall(): Boolean = this in setOf(
        AgentActionKind.CALL_CONNECTOR,
        AgentActionKind.CONTROL_DEVICE,
        AgentActionKind.OPEN_URL,
        AgentActionKind.OPEN_APP
    )

    private val TERMINAL_TOOL_STATUSES = setOf(
        AgentActionStatus.COMPLETED,
        AgentActionStatus.FAILED,
        AgentActionStatus.BLOCKED,
        AgentActionStatus.ROLLED_BACK
    )
    private const val MAX_REPEATED_TOOL_CALLS = 2
}
