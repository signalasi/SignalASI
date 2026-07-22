package com.signalasi.chat

enum class AgentTaskLivenessState {
    HEALTHY,
    STALLED,
    TIMED_OUT
}

data class AgentTaskLivenessDecision(
    val state: AgentTaskLivenessState,
    val reason: String = "",
    val idleMillis: Long = 0L,
    val lifetimeMillis: Long = 0L
)

enum class AgentTaskLivenessSignalKind {
    STALLED,
    RECOVERED,
    TIMED_OUT
}

data class AgentTaskLivenessSignal(
    val kind: AgentTaskLivenessSignalKind,
    val workspace: AgentWorkspace,
    val reason: String,
    val observedAtMillis: Long
)

fun interface AgentTaskLivenessListener {
    fun onSignal(signal: AgentTaskLivenessSignal)
}

internal object AgentTaskTerminalReplyPolicy {
    private val terminalDedupePrefixes = listOf(
        "result:",
        "direct-system:",
        "fast-local:",
        "skill-command:",
        "skill-result:"
    )

    fun hasTerminalReply(entries: List<AgentTranscriptEntry>, turnId: String): Boolean {
        val cleanTurnId = turnId.trim()
        if (cleanTurnId.isBlank()) return false
        return entries.any { entry ->
            entry.turnId == cleanTurnId &&
                entry.role == AgentTranscriptRole.ASSISTANT &&
                terminalDedupePrefixes.any(entry.dedupeKey::startsWith)
        }
    }
}

data class AgentTaskLivenessPolicy(
    val queuedWarningMillis: Long = 15_000L,
    val queuedTimeoutMillis: Long = 90_000L,
    val runningWarningMillis: Long = 45_000L,
    val runningTimeoutMillis: Long = 10 * 60_000L,
    val waitingResponseWarningMillis: Long = 30_000L,
    val waitingResponseTimeoutMillis: Long = 6 * 60_000L,
    val absoluteTimeoutMillis: Long = 2 * 60 * 60_000L,
    val watchdogIntervalMillis: Long = 5_000L,
    val heartbeatWriteThrottleMillis: Long = 2_000L
) {
    init {
        require(queuedWarningMillis > 0L && queuedTimeoutMillis > queuedWarningMillis)
        require(runningWarningMillis > 0L && runningTimeoutMillis > runningWarningMillis)
        require(waitingResponseWarningMillis > 0L && waitingResponseTimeoutMillis > waitingResponseWarningMillis)
        require(absoluteTimeoutMillis > 0L)
        require(watchdogIntervalMillis > 0L)
        require(heartbeatWriteThrottleMillis >= 0L)
    }

    fun evaluate(
        workspace: AgentWorkspace,
        nowMillis: Long,
        volatileActivityAtMillis: Long = 0L
    ): AgentTaskLivenessDecision {
        if (workspace.status.isTerminal || workspace.cancellationRequested ||
            workspace.status in USER_CONTROLLED_STATUSES
        ) {
            return AgentTaskLivenessDecision(AgentTaskLivenessState.HEALTHY)
        }
        val now = nowMillis.coerceAtLeast(0L)
        val lastActivity = maxOf(
            meaningfulActivityAt(workspace),
            volatileActivityAtMillis.coerceAtLeast(0L)
        )
        val startedAt = workspace.createdAtMillis.takeIf { it > 0L }
            ?: lastActivity.takeIf { it > 0L }
            ?: now
        val idleMillis = (now - lastActivity.coerceAtMost(now)).coerceAtLeast(0L)
        val lifetimeMillis = (now - startedAt.coerceAtMost(now)).coerceAtLeast(0L)
        if (lifetimeMillis >= absoluteTimeoutMillis) {
            return AgentTaskLivenessDecision(
                AgentTaskLivenessState.TIMED_OUT,
                reason = "absolute_deadline_exceeded",
                idleMillis = idleMillis,
                lifetimeMillis = lifetimeMillis
            )
        }
        val thresholds = thresholds(workspace.status)
            ?: return AgentTaskLivenessDecision(AgentTaskLivenessState.HEALTHY)
        return when {
            idleMillis >= thresholds.timeoutMillis -> AgentTaskLivenessDecision(
                AgentTaskLivenessState.TIMED_OUT,
                reason = "${workspace.status.name.lowercase()}_progress_timeout",
                idleMillis = idleMillis,
                lifetimeMillis = lifetimeMillis
            )
            idleMillis >= thresholds.warningMillis -> AgentTaskLivenessDecision(
                AgentTaskLivenessState.STALLED,
                reason = "${workspace.status.name.lowercase()}_progress_stalled",
                idleMillis = idleMillis,
                lifetimeMillis = lifetimeMillis
            )
            else -> AgentTaskLivenessDecision(
                AgentTaskLivenessState.HEALTHY,
                idleMillis = idleMillis,
                lifetimeMillis = lifetimeMillis
            )
        }
    }

    fun hasUnresolvedStall(workspace: AgentWorkspace): Boolean {
        val stalledSequence = workspace.eventJournal
            .asReversed()
            .firstOrNull { it.kind == AgentTaskEventKinds.STALLED }
            ?.sequence
            ?: return false
        return workspace.eventJournal.none { event ->
            event.sequence > stalledSequence && event.kind !in SUPERVISOR_OBSERVATION_EVENTS
        }
    }

    fun meaningfulActivityAt(workspace: AgentWorkspace): Long {
        val eventAt = workspace.eventJournal
            .asSequence()
            .filterNot { it.kind in SUPERVISOR_OBSERVATION_EVENTS }
            .maxOfOrNull(AgentWorkspaceEvent::timestampMillis)
            ?: 0L
        return maxOf(
            workspace.createdAtMillis,
            eventAt,
            workspace.updatedAtMillis.takeIf { workspace.eventJournal.isEmpty() } ?: 0L
        )
    }

    private fun thresholds(status: AgentWorkspaceStatus): Thresholds? = when (status) {
        AgentWorkspaceStatus.CREATED,
        AgentWorkspaceStatus.QUEUED -> Thresholds(queuedWarningMillis, queuedTimeoutMillis)
        AgentWorkspaceStatus.RUNNING -> Thresholds(runningWarningMillis, runningTimeoutMillis)
        AgentWorkspaceStatus.WAITING_RESPONSE -> Thresholds(
            waitingResponseWarningMillis,
            waitingResponseTimeoutMillis
        )
        AgentWorkspaceStatus.WAITING_CONFIRMATION,
        AgentWorkspaceStatus.PAUSED,
        AgentWorkspaceStatus.BLOCKED,
        AgentWorkspaceStatus.COMPLETED,
        AgentWorkspaceStatus.FAILED,
        AgentWorkspaceStatus.CANCELLED -> null
    }

    private data class Thresholds(
        val warningMillis: Long,
        val timeoutMillis: Long
    )

    private companion object {
        val USER_CONTROLLED_STATUSES = setOf(
            AgentWorkspaceStatus.WAITING_CONFIRMATION,
            AgentWorkspaceStatus.PAUSED,
            AgentWorkspaceStatus.BLOCKED
        )
        val SUPERVISOR_OBSERVATION_EVENTS = setOf(
            AgentTaskEventKinds.STALLED,
            AgentTaskEventKinds.TIMED_OUT
        )
    }
}
