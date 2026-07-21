package com.signalasi.chat

import java.util.UUID

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
    private val markInterrupted: (String, String) -> Unit = { _, _ -> }
) {
    suspend fun recover(): List<AgentRunRecoveryResult> = runStore.recoverableRuns().map { snapshot ->
        recover(snapshot)
    }

    private suspend fun recover(snapshot: AgentRunControlSnapshot): AgentRunRecoveryResult {
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
            AgentRunRecoveryDisposition.RECONNECT_DURABLE_REMOTE -> recoverRemote(snapshot, decision)
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
        snapshot: AgentRunControlSnapshot,
        decision: AgentRunRecoveryDecision
    ): AgentRunRecoveryResult {
        val adapter = runCatching { adapterResolver(snapshot.agentId) }.getOrNull()
        val remote = adapter?.let { resolved ->
            runCatching { resolved.recoverRuns() }.getOrDefault(emptyList())
                .firstOrNull { candidate ->
                    candidate.handle.runId == snapshot.runId ||
                        candidate.handle.taskId == snapshot.taskId ||
                        candidate.handle.remoteRunId == workspaceFor(snapshot)?.remoteRunId
                }
        }
        if (remote == null) {
            restoreWorkspace(
                snapshot,
                status = AgentWorkspaceStatus.WAITING_RESPONSE,
                eventKind = AgentTaskEventKinds.WAITING_RESPONSE,
                checkpoint = workspaceFor(snapshot)?.checkpoints?.lastOrNull()?.stateJson.orEmpty(),
                remoteHandle = null,
                remoteSequence = snapshot.lastSequence,
                reason = "remote_run_temporarily_unavailable"
            )
            appendWaitingForDevice(snapshot)
            return AgentRunRecoveryResult(
                snapshot.runId,
                AgentRunRecoveryOutcome.WAITING_FOR_REMOTE,
                snapshot.lastSequence,
                "remote_run_temporarily_unavailable"
            )
        }

        val priorWorkspace = workspaceFor(snapshot)
        if (snapshot.lastEvent.type == AgentRunControlEventType.RUN_RECOVERED &&
            priorWorkspace != null && priorWorkspace.lastRemoteEventSequence >= remote.lastEventSequence
        ) {
            return AgentRunRecoveryResult(
                snapshot.runId,
                AgentRunRecoveryOutcome.ALREADY_CURRENT,
                remote.lastEventSequence,
                "remote_cursor_already_current"
            )
        }
        restoreWorkspace(
            snapshot,
            status = AgentWorkspaceStatus.RUNNING,
            eventKind = "task.reconnected_remote",
            checkpoint = AgentNativeJsonCodec.stringify(remote.checkpoint),
            remoteHandle = remote.handle,
            remoteSequence = remote.lastEventSequence,
            reason = decision.reason
        )
        appendRecoveryEvent(snapshot, decision.reason, remote.lastEventSequence, "durable_remote")
        return AgentRunRecoveryResult(
            snapshot.runId,
            AgentRunRecoveryOutcome.RECONNECTED_REMOTE,
            remote.lastEventSequence,
            decision.reason
        )
    }

    private fun workspaceFor(snapshot: AgentRunControlSnapshot): AgentWorkspace? =
        workspaceStore.find(snapshot.runId)
            ?: workspaceStore.list().firstOrNull { it.taskId == snapshot.taskId }

    private fun restoreWorkspace(
        snapshot: AgentRunControlSnapshot,
        status: AgentWorkspaceStatus,
        eventKind: String,
        checkpoint: String,
        remoteHandle: AgentRunHandle?,
        remoteSequence: Long,
        reason: String
    ) {
        val workspaceId = workspaceFor(snapshot)?.workspaceId ?: return
        repeat(MAX_WRITE_ATTEMPTS) {
            val current = workspaceStore.find(workspaceId) ?: return
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
                // Rebuild from the newest durable revision.
            }
        }
        throw IllegalStateException("Run recovery could not update workspace $workspaceId")
    }

    private fun appendRecoveryEvent(
        snapshot: AgentRunControlSnapshot,
        reason: String,
        remoteSequence: Long,
        source: String
    ) {
        runStore.appendNext(snapshot.lastEvent.copy(
            eventId = UUID.randomUUID().toString(),
            type = AgentRunControlEventType.RUN_RECOVERED,
            sequence = 0L,
            payload = snapshot.lastEvent.payload + mapOf(
                "recovery_source" to source,
                "reason" to reason,
                "last_remote_event_sequence" to remoteSequence
            )
        ))
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
            type = type,
            sequence = 0L,
            payload = snapshot.lastEvent.payload + mapOf(
                "recovery_source" to "local_wait",
                "reason" to reason,
                "last_local_event_sequence" to snapshot.lastSequence
            )
        ))
    }

    private fun appendWaitingForDevice(snapshot: AgentRunControlSnapshot) {
        if (snapshot.lastEvent.type == AgentRunControlEventType.WAITING_FOR_DEVICE) return
        runStore.appendNext(snapshot.lastEvent.copy(
            eventId = UUID.randomUUID().toString(),
            type = AgentRunControlEventType.WAITING_FOR_DEVICE,
            sequence = 0L,
            payload = snapshot.lastEvent.payload + mapOf("reason" to "remote_run_temporarily_unavailable")
        ))
    }

    private fun appendTerminalFailure(snapshot: AgentRunControlSnapshot, reason: String) {
        runStore.appendNext(snapshot.lastEvent.copy(
            eventId = UUID.randomUUID().toString(),
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
            type = type,
            sequence = 0L,
            payload = snapshot.lastEvent.payload + mapOf("reason" to "recorded_run_is_terminal")
        ))
    }

    private companion object {
        const val MAX_WRITE_ATTEMPTS = 4
    }
}
