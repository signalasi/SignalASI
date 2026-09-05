package com.galaxyssi.chat

/** A fresh transport observation, not a locally persisted handoff checkpoint. */
data class AgentRemoteRecoveryObservation(
    val conversationId: String,
    val deviceId: String,
    val status: String,
    val remoteTaskId: String,
    val remoteRunId: String,
    val statusSequence: Long,
    val executionGeneration: Long = 1L,
    val awaitingTerminalReply: Boolean = false
) {
    val workspaceStatus: AgentWorkspaceStatus?
        get() = when (status) {
            "accepted", "queued", "starting", "running", "recovering" -> AgentWorkspaceStatus.RUNNING
            "waiting_input", "waiting_approval" -> AgentWorkspaceStatus.WAITING_CONFIRMATION
            "pausing", "paused", "takeover", "interrupted" -> AgentWorkspaceStatus.PAUSED
            "completed" -> AgentWorkspaceStatus.WAITING_RESPONSE
            "failed", "timed_out" -> if (awaitingTerminalReply) AgentWorkspaceStatus.WAITING_RESPONSE else AgentWorkspaceStatus.FAILED
            "cancelled" -> if (awaitingTerminalReply) AgentWorkspaceStatus.WAITING_RESPONSE else AgentWorkspaceStatus.CANCELLED
            else -> null
        }
}
