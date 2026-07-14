package com.signalasi.chat

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.ReentrantLock

/**
 * Process-wide authority for phone side effects.
 *
 * Reasoning and read-only observations may run concurrently. Actions that can
 * change Android, another app, local data, or a remote device are serialized so
 * independent Agent tasks cannot race the same phone surface.
 */
object PhoneExecutionAuthority {
    private const val TASK_ID_PARAMETER = "_signalasi_task_id"
    private val sideEffectLock = ReentrantLock(true)
    private val activeSideEffectTask = AtomicReference<String?>(null)
    private val cancelledTasks = ConcurrentHashMap.newKeySet<String>()

    fun guarded(delegate: AgentActionExecutor): AgentActionExecutor = object : AgentActionExecutor {
        override fun execute(action: AgentAction, screen: ScreenContext): AgentActionResult {
            val taskId = action.parameters[TASK_ID_PARAMETER].orEmpty().ifBlank { action.id }
            if (isCancelled(taskId)) return cancelledResult(action, taskId)
            if (action.kind.isConcurrentPhoneRead()) {
                return delegate.execute(action, screen).withAuthorityMetadata(
                    taskId = taskId,
                    serialized = false
                )
            }

            try {
                sideEffectLock.lockInterruptibly()
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return cancelledResult(action, taskId)
            }
            activeSideEffectTask.set(taskId)
            return try {
                if (isCancelled(taskId)) cancelledResult(action, taskId)
                else delegate.execute(action, screen).withAuthorityMetadata(taskId = taskId, serialized = true)
            } finally {
                activeSideEffectTask.compareAndSet(taskId, null)
                sideEffectLock.unlock()
            }
        }
    }

    fun requestCancellation(taskId: String) {
        if (taskId.isNotBlank()) cancelledTasks += taskId
    }

    fun clearCancellation(taskId: String) {
        if (taskId.isNotBlank()) cancelledTasks -= taskId
    }

    fun isCancelled(taskId: String): Boolean =
        taskId.isNotBlank() && (taskId in cancelledTasks || Thread.currentThread().isInterrupted)

    fun snapshot(): PhoneExecutionAuthoritySnapshot = PhoneExecutionAuthoritySnapshot(
        activeSideEffectTaskId = activeSideEffectTask.get().orEmpty(),
        queuedSideEffectTasks = sideEffectLock.queueLength,
        cancelledTaskCount = cancelledTasks.size
    )

    private fun cancelledResult(action: AgentAction, taskId: String): AgentActionResult = AgentActionResult(
        actionId = action.id,
        success = false,
        message = "Phone tool execution was cancelled",
        metadata = mapOf(
            "execution_location" to "phone",
            "execution_authority" to "signalasi-phone",
            "task_id" to taskId,
            "cancelled" to "true"
        )
    )

    private fun AgentActionResult.withAuthorityMetadata(
        taskId: String,
        serialized: Boolean
    ): AgentActionResult = copy(
        metadata = metadata + mapOf(
            "execution_location" to "phone",
            "execution_authority" to "signalasi-phone",
            "task_id" to taskId,
            "serialized_side_effect" to serialized.toString()
        )
    )

    private fun AgentActionKind.isConcurrentPhoneRead(): Boolean = when (this) {
        AgentActionKind.READ_SCREEN,
        AgentActionKind.DRAFT_PLAN -> true
        else -> false
    }
}

data class PhoneExecutionAuthoritySnapshot(
    val activeSideEffectTaskId: String,
    val queuedSideEffectTasks: Int,
    val cancelledTaskCount: Int
)
