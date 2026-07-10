package com.signalasi.chat

enum class AgentRecoveryDecision {
    NOT_NEEDED,
    RETRY_SUCCEEDED,
    RETRY_FAILED,
    MANUAL_REQUIRED
}

data class AgentRecoveryAttempt(
    val result: AgentActionResult?,
    val observation: AgentObservationOutcome
)

data class AgentRecoveryOutcome(
    val result: AgentActionResult?,
    val observation: AgentObservationOutcome,
    val decision: AgentRecoveryDecision,
    val attemptCount: Int
)

class AgentActionRecoveryController {
    fun recover(
        action: AgentAction,
        failedResult: AgentActionResult?,
        failedObservation: AgentObservationOutcome,
        retry: () -> AgentRecoveryAttempt
    ): AgentRecoveryOutcome {
        if (failedResult?.success != false) {
            return AgentRecoveryOutcome(
                result = failedResult,
                observation = failedObservation,
                decision = AgentRecoveryDecision.NOT_NEEDED,
                attemptCount = 0
            )
        }
        if (!action.supportsAutomaticRecovery() ||
            failedObservation.decision != AgentObservationDecision.TIMED_OUT
        ) {
            return AgentRecoveryOutcome(
                result = failedResult,
                observation = failedObservation,
                decision = AgentRecoveryDecision.MANUAL_REQUIRED,
                attemptCount = 0
            )
        }

        val attempt = retry()
        return AgentRecoveryOutcome(
            result = attempt.result,
            observation = attempt.observation,
            decision = if (attempt.result?.success == true) {
                AgentRecoveryDecision.RETRY_SUCCEEDED
            } else {
                AgentRecoveryDecision.RETRY_FAILED
            },
            attemptCount = 1
        )
    }

    private fun AgentAction.supportsAutomaticRecovery(): Boolean =
        risk == AgentRisk.LOW && kind in SAFE_RETRY_ACTIONS

    private companion object {
        val SAFE_RETRY_ACTIONS = setOf(
            AgentActionKind.OPEN_APP,
            AgentActionKind.HOME,
            AgentActionKind.RECENTS
        )
    }
}
