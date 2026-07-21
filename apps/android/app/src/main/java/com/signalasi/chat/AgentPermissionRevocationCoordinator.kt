package com.signalasi.chat

import java.util.UUID

data class AgentPermissionRevocationReport(
    val revocation: AgentPermissionRevocation,
    val pausedWorkspaceIds: Set<String>,
    val pausedRunIds: Set<String>,
    val failedWorkspaceIds: Set<String>
)

/**
 * Propagates host-owned permission revocation to durable task and Run state.
 * Active execution is stopped through the supplied process-lifetime supervisor hook.
 */
class AgentPermissionRevocationCoordinator(
    private val grantStore: AgentPermissionGrantStore,
    private val workspaceStore: AgentWorkspaceStore,
    private val runEventStore: AgentRunControlStore,
    private val pauseActiveWorkspace: (String, String) -> Boolean = { _, _ -> false }
) {
    fun revokeGrant(grantId: String, reason: String): AgentPermissionRevocationReport =
        propagate(grantStore.revokeGrant(grantId, reason))

    fun revokeScope(scope: String, reason: String): AgentPermissionRevocationReport =
        propagate(grantStore.revokeScope(scope, reason))

    fun propagate(revocation: AgentPermissionRevocation): AgentPermissionRevocationReport {
        if (revocation.revokedGrantIds.isEmpty()) {
            return AgentPermissionRevocationReport(revocation, emptySet(), emptySet(), emptySet())
        }
        val affected = workspaceStore.recoverable().filter { workspace ->
            workspace.permissionGrantIds.any(revocation.revokedGrantIds::contains) ||
                workspace.permissionScopes.any(revocation.scopes::contains)
        }
        val paused = linkedSetOf<String>()
        val failed = linkedSetOf<String>()
        affected.forEach { workspace ->
            val result = runCatching {
                pauseActiveWorkspace(workspace.workspaceId, revocation.reason)
                persistRevocation(workspace.workspaceId, revocation)
            }
            if (result.isSuccess) paused += workspace.workspaceId else failed += workspace.workspaceId
        }

        val affectedTaskIds = affected.mapTo(linkedSetOf(), AgentWorkspace::taskId)
        val affectedWorkspaceIds = affected.mapTo(linkedSetOf(), AgentWorkspace::workspaceId)
        val pausedRuns = linkedSetOf<String>()
        runEventStore.recoverableRuns()
            .filter { it.taskId in affectedTaskIds || it.runId in affectedWorkspaceIds }
            .forEach { snapshot ->
                val appended = runEventStore.appendNext(
                    snapshot.lastEvent.copy(
                        eventId = UUID.randomUUID().toString(),
                        type = AgentRunControlEventType.PERMISSION_REVOKED,
                        sequence = 0L,
                        timestampMillis = revocation.revokedAtMillis,
                        payload = snapshot.lastEvent.payload + mapOf(
                            "revoked_grant_ids" to revocation.revokedGrantIds.sorted(),
                            "revoked_scopes" to revocation.scopes.sorted(),
                            "revocation_reason" to revocation.reason,
                            "revoked_at_millis" to revocation.revokedAtMillis
                        )
                    )
                )
                if (appended != null) pausedRuns += snapshot.runId
            }
        return AgentPermissionRevocationReport(revocation, paused, pausedRuns, failed)
    }

    private fun persistRevocation(
        workspaceId: String,
        revocation: AgentPermissionRevocation
    ) {
        repeat(MAX_WRITE_ATTEMPTS) {
            val current = workspaceStore.find(workspaceId) ?: return
            if (current.status.isTerminal || current.cancellationRequested) return
            val alreadyRecorded = current.eventJournal.lastOrNull()?.let { event ->
                event.kind == AgentTaskEventKinds.PERMISSION_REVOKED &&
                    event.timestampMillis == revocation.revokedAtMillis
            } == true
            try {
                val withEvent = if (alreadyRecorded) current else requireNotNull(
                    workspaceStore.appendEvent(
                        workspaceId = workspaceId,
                        kind = AgentTaskEventKinds.PERMISSION_REVOKED,
                        message = revocation.reason,
                        payloadJson = AgentNativeJsonCodec.stringify(mapOf(
                            "grant_ids" to revocation.revokedGrantIds.sorted(),
                            "scopes" to revocation.scopes.sorted(),
                            "revoked_at_millis" to revocation.revokedAtMillis
                        )),
                        expectedRevision = current.revision,
                        timestampMillis = revocation.revokedAtMillis
                    )
                )
                val checkpoint = requireNotNull(workspaceStore.checkpoint(
                    workspaceId = workspaceId,
                    checkpointId = "permission-revoked-${revocation.revokedAtMillis}",
                    planSnapshot = withEvent.currentPlanSnapshot,
                    stateJson = AgentNativeJsonCodec.stringify(mapOf(
                        "status" to AgentWorkspaceStatus.PAUSED.name,
                        "revoked_grant_ids" to revocation.revokedGrantIds.sorted(),
                        "revoked_scopes" to revocation.scopes.sorted(),
                        "reason" to revocation.reason
                    )),
                    expectedRevision = withEvent.revision,
                    createdAtMillis = revocation.revokedAtMillis
                ))
                workspaceStore.upsert(
                    checkpoint.copy(
                        status = AgentWorkspaceStatus.PAUSED,
                        toolCalls = checkpoint.toolCalls.map { call ->
                            if (call.status == AgentToolCallStatus.PENDING ||
                                call.status == AgentToolCallStatus.RUNNING
                            ) call.copy(
                                status = AgentToolCallStatus.CANCELLED,
                                errorMessage = revocation.reason,
                                completedAtMillis = revocation.revokedAtMillis
                            ) else call
                        },
                        errorMessage = revocation.reason,
                        revision = checkpoint.revision
                    ),
                    expectedRevision = checkpoint.revision
                )
                return
            } catch (_: AgentWorkspaceRevisionConflictException) {
                // Retry against the latest durable revision.
            }
        }
        throw IllegalStateException("Permission revocation could not update workspace $workspaceId")
    }

    private companion object {
        const val MAX_WRITE_ATTEMPTS = 4
    }
}
