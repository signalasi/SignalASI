package com.galaxyssi.chat

import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

enum class AgentRunRecoveryOutcome {
    RESTORED_LOCAL_WAIT,
    RECONNECTED_REMOTE,
    WAITING_FOR_REMOTE,
    FAILED_NON_REPLAYABLE,
    IGNORED_TERMINAL,
    ALREADY_CURRENT
}

data class AgentRunRecoveryResult(
    val runId: String,
    val outcome: AgentRunRecoveryOutcome,
    val lastRemoteEventSequence: Long = 0L,
    val reason: String
)

class AgentRunRecoveryCoordinator(
    private val runStore: AgentRunControlStore,
    private val workspaceStore: AgentWorkspaceStore,
    private val recordedRun: (String) -> AgentRecordedRun?,
    private val registration: (String, String) -> AgentRegistration?,
    private val adapterResolver: suspend (String) -> AgentAdapter?,
    private val markInterrupted: (String, String) -> Unit = { _, _ -> },
    private val markRemoteTerminal: (String, AgentRecordedRunStatus, String) -> Unit = { _, _, _ -> }
) {
    suspend fun recover(excludedRunIds: Set<String> = emptySet()): List<AgentRunRecoveryResult> =
        runStore.recoverableRuns()
            .filterNot { it.runId in excludedRunIds || it.lastEvent.payload["recovery_mode"] == "observation_only" }
            .map { snapshot -> recover(snapshot) }

    private suspend fun recover(snapshot: AgentRunControlSnapshot): AgentRunRecoveryResult {
        currentCoroutineContext().ensureActive()
        val run = recordedRun(snapshot.runId)
        val decision = AgentRunRecoveryPolicy.decide(
            snapshot,
            run,
            registration(snapshot.agentId, snapshot.deviceId)
        )
        return when (decision.disposition) {
            AgentRunRecoveryDisposition.IGNORE_TERMINAL -> AgentRunRecoveryResult(
                snapshot.runId,
                AgentRunRecoveryOutcome.IGNORED_TERMINAL,
                snapshot.lastSequence,
                decision.reason
            ).also {
                val terminalStatus = when (run?.status) {
                    AgentRecordedRunStatus.COMPLETED -> AgentWorkspaceStatus.COMPLETED
                    AgentRecordedRunStatus.CANCELLED -> AgentWorkspaceStatus.CANCELLED
                    AgentRecordedRunStatus.FAILED -> AgentWorkspaceStatus.FAILED
                    AgentRecordedRunStatus.RUNNING, null -> null
                }
                terminalStatus?.let { status ->
                    restoreWorkspace(
                        snapshot,
                        status = status,
                        eventKind = "task.reconciled_terminal",
                        checkpoint = "",
                        remoteHandle = null,
                        remoteSequence = snapshot.lastSequence,
                        reason = decision.reason
                    )
                    appendRecordedTerminal(snapshot, run?.status)
                }
            }
            AgentRunRecoveryDisposition.RESTORE_LOCAL_WAIT -> {
                restoreWorkspace(
                    snapshot,
                    status = workspaceFor(snapshot)?.status?.takeIf {
                        it == AgentWorkspaceStatus.WAITING_CONFIRMATION || it == AgentWorkspaceStatus.PAUSED
                    } ?: AgentWorkspaceStatus.PAUSED,
                    eventKind = "task.recovered_local_wait",
                    checkpoint = workspaceFor(snapshot)?.checkpoints?.lastOrNull()?.stateJson.orEmpty(),
                    remoteHandle = null,
                    remoteSequence = snapshot.lastSequence,
                    reason = decision.reason
                )
                appendLocalWaitRecoveryEvent(snapshot, decision.reason)
                AgentRunRecoveryResult(
                    snapshot.runId,
                    AgentRunRecoveryOutcome.RESTORED_LOCAL_WAIT,
                    snapshot.lastSequence,
                    decision.reason
                )
            }
            AgentRunRecoveryDisposition.RECONNECT_DURABLE_REMOTE -> recoverRemote(snapshot)
            AgentRunRecoveryDisposition.FAIL_NON_REPLAYABLE -> {
                markInterrupted(snapshot.runId, decision.reason)
                restoreWorkspace(
                    snapshot,
                    status = AgentWorkspaceStatus.FAILED,
                    eventKind = AgentTaskEventKinds.FAILED,
                    checkpoint = "",
                    remoteHandle = null,
                    remoteSequence = snapshot.lastSequence,
                    reason = decision.reason
                )
                appendTerminalFailure(snapshot, decision.reason)
                AgentRunRecoveryResult(
                    snapshot.runId,
                    AgentRunRecoveryOutcome.FAILED_NON_REPLAYABLE,
                    snapshot.lastSequence,
                    decision.reason
                )
            }
        }
    }

    private suspend fun recoverRemote(
        snapshot: AgentRunControlSnapshot
    ): AgentRunRecoveryResult {
        val priorWorkspace = workspaceFor(snapshot)
        val adapter = recoverOrNull { adapterResolver(snapshot.agentId) }
        val remote = adapter?.let { resolved ->
            recoverOrNull { resolved.recoverRuns() }.orEmpty()
                .singleOrNull { candidate ->
                    candidate.handle.runId == snapshot.runId &&
                        candidate.handle.taskId == snapshot.taskId &&
                        candidate.handle.agentId == snapshot.agentId &&
                        candidate.observation?.conversationId == snapshot.lastEvent.conversationId &&
                        candidate.observation.deviceId == resolved.registration.deviceId &&
                        candidate.observation.workspaceStatus != null
                }
        }
        currentCoroutineContext().ensureActive()
        if (remote == null) {
            val remoteSequence = priorWorkspace?.lastRemoteEventSequence ?: 0L
            if (snapshot.lastEvent.type == AgentRunControlEventType.WAITING_FOR_DEVICE) {
                return AgentRunRecoveryResult(snapshot.runId, AgentRunRecoveryOutcome.WAITING_FOR_REMOTE,
                    remoteSequence, "remote_run_temporarily_unavailable")
            }
            if (!appendWaitingForDevice(snapshot)) return staleResult(snapshot)
            restoreWorkspace(
                snapshot,
                status = AgentWorkspaceStatus.WAITING_RESPONSE,
                eventKind = AgentTaskEventKinds.RECOVERY_WAITING_RESPONSE,
                checkpoint = priorWorkspace?.checkpoints?.lastOrNull()?.stateJson.orEmpty(),
                remoteHandle = null,
                remoteSequence = remoteSequence,
                reason = "remote_run_temporarily_unavailable",
                expectedRevision = priorWorkspace?.revision
            )
            return AgentRunRecoveryResult(
                snapshot.runId,
                AgentRunRecoveryOutcome.WAITING_FOR_REMOTE,
                remoteSequence,
                "remote_run_temporarily_unavailable"
            )
        }

        val observation = requireNotNull(remote.observation)
        val remoteStatus = requireNotNull(observation.workspaceStatus)
        val reason = "remote_status_${observation.status}"
        if (snapshot.lastEvent.payload["remote_status"] == observation.status &&
            snapshot.lastEvent.payload["remote_status_sequence"]?.toString() == observation.statusSequence.toString() &&
            snapshot.lastEvent.payload["remote_execution_generation"]?.toString() == observation.executionGeneration.toString() &&
            priorWorkspace?.status == remoteStatus && priorWorkspace.remoteRunId == remote.handle.remoteRunId
        ) {
            return AgentRunRecoveryResult(
                snapshot.runId,
                AgentRunRecoveryOutcome.ALREADY_CURRENT,
                remote.lastEventSequence,
                "remote_cursor_already_current"
            )
        }
        val type = when (remoteStatus) {
            AgentWorkspaceStatus.CANCELLED -> AgentRunControlEventType.RUN_CANCELLED
            AgentWorkspaceStatus.FAILED -> AgentRunControlEventType.RUN_FAILED
            AgentWorkspaceStatus.PAUSED -> AgentRunControlEventType.PAUSED
            AgentWorkspaceStatus.WAITING_CONFIRMATION -> AgentRunControlEventType.WAITING_FOR_USER
            AgentWorkspaceStatus.WAITING_RESPONSE -> AgentRunControlEventType.WAITING_FOR_DEVICE
            else -> AgentRunControlEventType.RUN_RECOVERED
        }
        val committed = runStore.appendRecoveryIfCurrent(snapshot.lastEvent.copy(eventId = UUID.randomUUID().toString(),
            idempotencyKey = UUID.randomUUID().toString(), timestampMillis = System.currentTimeMillis(), type = type,
            sequence = 0L, payload = snapshot.lastEvent.payload + mapOf(
                "recovery_source" to "verified_remote", "reason" to reason,
                "remote_status" to observation.status, "remote_status_sequence" to observation.statusSequence,
                "remote_execution_generation" to observation.executionGeneration,
                "remote_task_id" to observation.remoteTaskId, "remote_run_id" to observation.remoteRunId
            )), snapshot.lastSequence)
        if (committed == null) return staleResult(snapshot)
        when (remoteStatus) {
            AgentWorkspaceStatus.CANCELLED -> markRemoteTerminal(snapshot.runId, AgentRecordedRunStatus.CANCELLED, reason)
            AgentWorkspaceStatus.FAILED -> markRemoteTerminal(snapshot.runId, AgentRecordedRunStatus.FAILED, reason)
            else -> Unit
        }
        restoreWorkspace(
            snapshot,
            status = remoteStatus,
            eventKind = "task.reconnected_remote",
            checkpoint = remote.checkpoint.takeIf { it.isNotEmpty() }?.let(AgentNativeJsonCodec::stringify).orEmpty(),
            remoteHandle = remote.handle,
            remoteSequence = remote.lastEventSequence,
            reason = reason,
            expectedRevision = priorWorkspace?.revision
        )
        return AgentRunRecoveryResult(
            snapshot.runId,
            AgentRunRecoveryOutcome.RECONNECTED_REMOTE,
            remote.lastEventSequence,
            reason
        )
    }

    private fun workspaceFor(snapshot: AgentRunControlSnapshot): AgentWorkspace? =
        listOfNotNull(workspaceStore.find(snapshot.runId), workspaceStore.find(snapshot.taskId))
            .distinctBy { it.workspaceId }.singleOrNull { workspace ->
                workspace.taskId == snapshot.taskId &&
                    workspace.conversationId == snapshot.lastEvent.conversationId &&
                    (workspace.workspaceId == snapshot.runId || workspace.parentRunId == snapshot.runId) &&
                    (workspace.agentId.isBlank() || workspace.agentId == snapshot.agentId) &&
                    (workspace.deviceId.isBlank() || workspace.deviceId == snapshot.deviceId)
            }

    private suspend fun <T> recoverOrNull(block: suspend () -> T): T? = try {
        block()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        null
    }

    private fun staleResult(snapshot: AgentRunControlSnapshot) = AgentRunRecoveryResult(
        snapshot.runId, AgentRunRecoveryOutcome.ALREADY_CURRENT, snapshot.lastSequence, "local_run_advanced_during_query")

    private fun restoreWorkspace(
        snapshot: AgentRunControlSnapshot,
        status: AgentWorkspaceStatus,
        eventKind: String,
        checkpoint: String,
        remoteHandle: AgentRunHandle?,
        remoteSequence: Long,
        reason: String,
        expectedRevision: Long? = null
    ) {
        val workspaceId = workspaceFor(snapshot)?.workspaceId ?: return
        repeat(MAX_WRITE_ATTEMPTS) {
            val current = workspaceStore.find(workspaceId) ?: return
            if (expectedRevision != null && current.revision != expectedRevision) return
            if (current.status in setOf(AgentWorkspaceStatus.COMPLETED, AgentWorkspaceStatus.FAILED,
                    AgentWorkspaceStatus.CANCELLED) || current.cancellationRequested) return
            val alreadyCurrent = current.status == status &&
                current.eventJournal.lastOrNull()?.kind == eventKind &&
                current.lastRemoteEventSequence >= remoteSequence &&
                (remoteHandle == null || current.remoteRunId == remoteHandle.remoteRunId) &&
                (checkpoint.isBlank() || current.checkpoints.lastOrNull()?.stateJson == checkpoint)
            if (alreadyCurrent) return
            try {
                val evented = requireNotNull(workspaceStore.appendEvent(
                    workspaceId = workspaceId,
                    kind = eventKind,
                    message = reason,
                    payloadJson = AgentNativeJsonCodec.stringify(mapOf(
                        "run_id" to snapshot.runId,
                        "remote_run_id" to remoteHandle?.remoteRunId.orEmpty(),
                        "last_remote_event_sequence" to remoteSequence
                    )),
                    expectedRevision = current.revision
                ))
                val checkpointed = if (checkpoint.isNotBlank()) requireNotNull(workspaceStore.checkpoint(
                    workspaceId = workspaceId,
                    checkpointId = "recovery-${snapshot.runId.take(48)}-$remoteSequence",
                    planSnapshot = evented.currentPlanSnapshot,
                    stateJson = checkpoint,
                    expectedRevision = evented.revision
                )) else evented
                workspaceStore.upsert(
                    checkpointed.copy(
                        status = status,
                        agentId = remoteHandle?.agentId.orEmpty().ifBlank { checkpointed.agentId },
                        remoteRunId = remoteHandle?.remoteRunId.orEmpty().ifBlank { checkpointed.remoteRunId },
                        lastRemoteEventSequence = maxOf(
                            checkpointed.lastRemoteEventSequence,
                            remoteSequence
                        ),
                        errorMessage = if (status == AgentWorkspaceStatus.FAILED) reason else checkpointed.errorMessage,
                        revision = checkpointed.revision
                    ),
                    expectedRevision = checkpointed.revision
                )
                return
            } catch (_: AgentWorkspaceRevisionConflictException) {
                if (expectedRevision != null) return
                // Rebuild from the newest durable revision.
            }
        }
        throw IllegalStateException("Run recovery could not update workspace $workspaceId")
    }

    private fun appendLocalWaitRecoveryEvent(
        snapshot: AgentRunControlSnapshot,
        reason: String
    ) {
        val type = if (snapshot.state == AgentRunControlState.PAUSED) {
            AgentRunControlEventType.PAUSED
        } else {
            AgentRunControlEventType.WAITING_FOR_USER
        }
        if (snapshot.lastEvent.type == type &&
            snapshot.lastEvent.payload["recovery_source"] == "local_wait"
        ) return
        runStore.appendNext(snapshot.lastEvent.copy(
            eventId = UUID.randomUUID().toString(),
            idempotencyKey = UUID.randomUUID().toString(),
            timestampMillis = System.currentTimeMillis(),
            type = type,
            sequence = 0L,
            payload = snapshot.lastEvent.payload + mapOf(
                "recovery_source" to "local_wait",
                "reason" to reason,
                "last_local_event_sequence" to snapshot.lastSequence
            )
        ))
    }

    private fun appendWaitingForDevice(snapshot: AgentRunControlSnapshot): Boolean {
        if (snapshot.lastEvent.type == AgentRunControlEventType.WAITING_FOR_DEVICE) return true
        return runStore.appendRecoveryIfCurrent(snapshot.lastEvent.copy(
            eventId = UUID.randomUUID().toString(),
            idempotencyKey = UUID.randomUUID().toString(),
            timestampMillis = System.currentTimeMillis(),
            type = AgentRunControlEventType.WAITING_FOR_DEVICE,
            sequence = 0L,
            payload = snapshot.lastEvent.payload + mapOf("reason" to "remote_run_temporarily_unavailable")
        ), snapshot.lastSequence) != null
    }

    private fun appendTerminalFailure(snapshot: AgentRunControlSnapshot, reason: String) {
        runStore.appendNext(snapshot.lastEvent.copy(
            eventId = UUID.randomUUID().toString(),
            idempotencyKey = UUID.randomUUID().toString(),
            timestampMillis = System.currentTimeMillis(),
            type = AgentRunControlEventType.RUN_FAILED,
            sequence = 0L,
            payload = snapshot.lastEvent.payload + mapOf("reason" to reason, "replay_safe" to false)
        ))
    }

    private fun appendRecordedTerminal(
        snapshot: AgentRunControlSnapshot,
        status: AgentRecordedRunStatus?
    ) {
        val type = when (status) {
            AgentRecordedRunStatus.COMPLETED -> AgentRunControlEventType.RUN_COMPLETED
            AgentRecordedRunStatus.CANCELLED -> AgentRunControlEventType.RUN_CANCELLED
            AgentRecordedRunStatus.FAILED -> AgentRunControlEventType.RUN_FAILED
            AgentRecordedRunStatus.RUNNING, null -> return
        }
        runStore.appendNext(snapshot.lastEvent.copy(
            eventId = UUID.randomUUID().toString(),
            idempotencyKey = UUID.randomUUID().toString(),
            timestampMillis = System.currentTimeMillis(),
            type = type,
            sequence = 0L,
            payload = snapshot.lastEvent.payload + mapOf("reason" to "recorded_run_is_terminal")
        ))
    }

    private companion object {
        const val MAX_WRITE_ATTEMPTS = 4
    }
}
