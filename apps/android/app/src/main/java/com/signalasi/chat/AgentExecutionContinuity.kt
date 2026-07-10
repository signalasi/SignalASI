package com.signalasi.chat

import java.util.UUID

enum class AgentCheckpointStatus {
    ACTIVE,
    RESTORED,
    INVALIDATED
}

data class AgentExecutionCheckpoint(
    val id: String,
    val actionId: String,
    val planRevision: Int,
    val foregroundApp: String,
    val activityName: String,
    val pageTitle: String,
    val screenDigest: String,
    val rollbackAction: AgentAction? = null,
    val status: AgentCheckpointStatus = AgentCheckpointStatus.ACTIVE,
    val createdAtMillis: Long = System.currentTimeMillis()
)

object AgentExecutionContinuity {
    fun checkpointBefore(action: AgentAction, screen: ScreenContext, planRevision: Int): AgentExecutionCheckpoint =
        AgentExecutionCheckpoint(
            id = UUID.randomUUID().toString(),
            actionId = action.id,
            planRevision = planRevision,
            foregroundApp = screen.foregroundApp,
            activityName = screen.activityName,
            pageTitle = screen.pageTitle,
            screenDigest = screenDigest(screen),
            rollbackAction = rollbackActionFor(action)
        )

    fun screenDigest(screen: ScreenContext): String = listOf(
        screen.foregroundApp,
        screen.activityName,
        screen.pageTitle,
        screen.visibleTexts.take(40).joinToString("\u001f"),
        screen.clickableNodeCount.toString(),
        screen.inputFieldCount.toString()
    ).joinToString("\u001e").hashCode().toString()

    private fun rollbackActionFor(action: AgentAction): AgentAction? = when (action.kind) {
        AgentActionKind.OPEN_APP,
        AgentActionKind.OPEN_URL,
        AgentActionKind.RECENTS -> AgentAction(
            id = "rollback-${action.id}",
            kind = AgentActionKind.BACK,
            target = action.target,
            risk = AgentRisk.LOW,
            status = AgentActionStatus.PENDING_CONFIRMATION,
            description = "Return to the screen before ${action.description}",
            requiresConfirmation = true
        )

        AgentActionKind.SWIPE -> reverseSwipe(action)
        else -> null
    }

    private fun reverseSwipe(action: AgentAction): AgentAction? {
        val fromX = action.parameters["from_x"] ?: return null
        val fromY = action.parameters["from_y"] ?: return null
        val toX = action.parameters["to_x"] ?: return null
        val toY = action.parameters["to_y"] ?: return null
        return AgentAction(
            id = "rollback-${action.id}",
            kind = AgentActionKind.SWIPE,
            target = action.target,
            risk = AgentRisk.LOW,
            status = AgentActionStatus.PENDING_CONFIRMATION,
            description = "Reverse the previous swipe",
            parameters = mapOf(
                "from_x" to toX,
                "from_y" to toY,
                "to_x" to fromX,
                "to_y" to fromY
            ),
            requiresConfirmation = true
        )
    }
}

fun AgentPlan.addCheckpoint(checkpoint: AgentExecutionCheckpoint): AgentPlan = copy(
    checkpoints = (checkpoints + checkpoint).takeLast(20)
)

fun AgentPlan.markCheckpoint(checkpointId: String, status: AgentCheckpointStatus): AgentPlan = copy(
    checkpoints = checkpoints.map { checkpoint ->
        if (checkpoint.id == checkpointId) checkpoint.copy(status = status) else checkpoint
    }
)

fun AgentPlan.recoverInterruptedExecution(): AgentPlan = copy(
    actions = actions.map { action ->
        if (action.status == AgentActionStatus.RUNNING) {
            action.copy(
                status = AgentActionStatus.PENDING_CONFIRMATION,
                result = "Execution was interrupted before verification",
                evidence = "interrupted"
            )
        } else {
            action
        }
    }
)

fun AgentPlan.historyForReplan(): List<AgentAction> =
    (actionHistory + actions.filter { action ->
        action.status in setOf(
            AgentActionStatus.COMPLETED,
            AgentActionStatus.FAILED,
            AgentActionStatus.BLOCKED,
            AgentActionStatus.ROLLED_BACK
        )
    }).takeLast(40)
