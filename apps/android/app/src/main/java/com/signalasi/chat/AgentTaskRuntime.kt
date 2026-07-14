package com.signalasi.chat

import android.content.Context

/** Process-lifetime owner for durable mobile Agent tasks. */
object AgentTaskRuntime {
    @Volatile private var supervisor: AgentTaskSupervisor? = null

    fun supervisor(context: Context): AgentTaskSupervisor {
        supervisor?.let { return it }
        return synchronized(this) {
            supervisor ?: AgentTaskSupervisor(
                workspaceStore = EncryptedAgentWorkspaceStore(context.applicationContext)
            ).also { supervisor = it }
        }
    }

    fun recoverable(context: Context): List<AgentWorkspace> = supervisor(context).recoverableTasks()
}

internal fun AgentPhase.toWorkspaceStatus(): AgentWorkspaceStatus = when (this) {
    AgentPhase.OBSERVING,
    AgentPhase.PLANNING,
    AgentPhase.EXECUTING,
    AgentPhase.VERIFYING -> AgentWorkspaceStatus.RUNNING
    AgentPhase.WAITING_CONFIRMATION -> AgentWorkspaceStatus.WAITING_CONFIRMATION
    AgentPhase.WAITING_RESPONSE -> AgentWorkspaceStatus.WAITING_RESPONSE
    AgentPhase.PAUSED -> AgentWorkspaceStatus.PAUSED
    AgentPhase.BLOCKED -> AgentWorkspaceStatus.BLOCKED
    AgentPhase.COMPLETED -> AgentWorkspaceStatus.COMPLETED
    AgentPhase.FAILED -> AgentWorkspaceStatus.FAILED
    AgentPhase.CANCELLED -> AgentWorkspaceStatus.CANCELLED
}
