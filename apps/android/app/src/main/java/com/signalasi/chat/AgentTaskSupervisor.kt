package com.signalasi.chat

import java.io.Closeable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableJob
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore

enum class AgentTaskLane {
    READ_REASONING,
    SIDE_EFFECT
}

object AgentTaskEventKinds {
    const val QUEUED = "task.queued"
    const val RESUMED = "task.resumed"
    const val RUNNING = "task.running"
    const val COMPLETED = "task.completed"
    const val FAILED = "task.failed"
    const val CANCELLED = "task.cancelled"
    const val INTERRUPTED = "task.interrupted"
    const val CHECKPOINT = "task.checkpoint"
    const val WAITING_CONFIRMATION = "task.waiting_confirmation"
    const val WAITING_RESPONSE = "task.waiting_response"
    const val PAUSED = "task.paused"
    const val BLOCKED = "task.blocked"
}

fun interface AgentTaskResumeHook {
    suspend fun resume(context: AgentTaskContext, workspace: AgentWorkspace)
}

class AgentTaskCancellationSource internal constructor(
    private val cancellationJob: CompletableJob,
    private val requestCancellation: (String) -> Boolean
) {
    private val requested = AtomicBoolean(false)

    val isCancellationRequested: Boolean
        get() = requested.get()

    val isActive: Boolean
        get() = cancellationJob.isActive

    fun cancel(reason: String = "Task cancellation requested"): Boolean =
        requestCancellation(reason.ifBlank { "Task cancellation requested" })

    fun throwIfCancellationRequested() {
        if (requested.get()) throw CancellationException("Task cancellation requested")
    }

    internal fun cancelExecution(reason: String) {
        requested.set(true)
        cancellationJob.cancel(CancellationException(reason))
    }

    internal fun complete() {
        cancellationJob.complete()
    }
}

class AgentTaskHandle internal constructor(
    val workspaceId: String,
    val taskId: String,
    val lane: AgentTaskLane,
    val cancellationSource: AgentTaskCancellationSource,
    val job: Job
) {
    val isActive: Boolean
        get() = job.isActive

    suspend fun join() {
        job.join()
    }

    fun cancel(reason: String = "Task cancellation requested"): Boolean =
        cancellationSource.cancel(reason)
}

class AgentTaskContext internal constructor(
    val workspaceKey: AgentWorkspaceKey,
    val lane: AgentTaskLane,
    val cancellationSource: AgentTaskCancellationSource,
    private val supervisor: AgentTaskSupervisor
) {
    fun workspace(): AgentWorkspace = supervisor.requireWorkspace(workspaceKey.workspaceId)

    fun appendEvent(
        kind: String,
        message: String = "",
        payloadJson: String = ""
    ): AgentWorkspace = supervisor.appendEvent(
        workspaceId = workspaceKey.workspaceId,
        kind = kind,
        message = message,
        payloadJson = payloadJson
    )

    fun checkpoint(
        checkpointId: String,
        planSnapshot: String = "",
        stateJson: String = ""
    ): AgentWorkspace = supervisor.checkpoint(
        workspaceId = workspaceKey.workspaceId,
        checkpointId = checkpointId,
        planSnapshot = planSnapshot,
        stateJson = stateJson
    )

    fun transition(
        status: AgentWorkspaceStatus,
        eventKind: String = "task.status.${status.name.lowercase()}",
        message: String = "",
        payloadJson: String = ""
    ): AgentWorkspace = supervisor.transition(
        workspaceId = workspaceKey.workspaceId,
        status = status,
        eventKind = eventKind,
        message = message,
        payloadJson = payloadJson
    )

    suspend fun ensureActive() {
        cancellationSource.throwIfCancellationRequested()
        currentCoroutineContext().ensureActive()
    }

    fun waitForConfirmation(message: String = ""): Nothing = supervisor.deferTask(
        workspaceId = workspaceKey.workspaceId,
        status = AgentWorkspaceStatus.WAITING_CONFIRMATION,
        eventKind = AgentTaskEventKinds.WAITING_CONFIRMATION,
        message = message
    )

    fun waitForResponse(message: String = ""): Nothing = supervisor.deferTask(
        workspaceId = workspaceKey.workspaceId,
        status = AgentWorkspaceStatus.WAITING_RESPONSE,
        eventKind = AgentTaskEventKinds.WAITING_RESPONSE,
        message = message
    )

    fun pause(message: String = ""): Nothing = supervisor.deferTask(
        workspaceId = workspaceKey.workspaceId,
        status = AgentWorkspaceStatus.PAUSED,
        eventKind = AgentTaskEventKinds.PAUSED,
        message = message
    )

    fun blockTask(message: String = ""): Nothing = supervisor.deferTask(
        workspaceId = workspaceKey.workspaceId,
        status = AgentWorkspaceStatus.BLOCKED,
        eventKind = AgentTaskEventKinds.BLOCKED,
        message = message
    )
}

/**
 * Process-lifetime coordinator for agent work. It owns no Activity or other UI lifecycle object.
 */
class AgentTaskSupervisor(
    private val workspaceStore: AgentWorkspaceStore,
    maxConcurrentReadReasoningTasks: Int = DEFAULT_MAX_READ_REASONING_TASKS,
    dispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val clock: () -> Long = { System.currentTimeMillis() }
) : Closeable {
    private val supervisorJob = SupervisorJob()
    private val applicationScope = CoroutineScope(
        supervisorJob + dispatcher + CoroutineName("AgentTaskSupervisor")
    )
    private val readReasoningPermits = Semaphore(maxConcurrentReadReasoningTasks)
    private val sideEffectMutex = Mutex()
    private val storeMutationLock = Any()
    private val closed = AtomicBoolean(false)
    private val activeByWorkspace = ConcurrentHashMap<String, TaskControl>()
    private val activeByTask = ConcurrentHashMap<String, TaskControl>()

    init {
        require(maxConcurrentReadReasoningTasks > 0) {
            "maxConcurrentReadReasoningTasks must be positive"
        }
    }

    val isActive: Boolean
        get() = supervisorJob.isActive && !closed.get()

    fun activeTaskIds(): Set<String> = activeByTask.keys.toSet()

    fun cancellationSource(taskId: String): AgentTaskCancellationSource? =
        activeByTask[taskId.trim()]?.cancellationSource

    fun submit(
        workspace: AgentWorkspace,
        lane: AgentTaskLane = AgentTaskLane.READ_REASONING,
        block: suspend AgentTaskContext.() -> Unit
    ): AgentTaskHandle = startTask(
        workspace = workspace,
        lane = lane,
        resumed = false,
        block = block
    )

    fun launch(
        workspace: AgentWorkspace,
        lane: AgentTaskLane = AgentTaskLane.READ_REASONING,
        block: suspend AgentTaskContext.() -> Unit
    ): AgentTaskHandle = submit(workspace, lane, block)

    fun recoverableTasks(): List<AgentWorkspace> = workspaceStore.recoverable()

    fun resume(
        workspaceId: String,
        lane: AgentTaskLane = AgentTaskLane.READ_REASONING,
        hook: AgentTaskResumeHook
    ): AgentTaskHandle {
        val recovered = requireWorkspace(workspaceId)
        require(!recovered.status.isTerminal && !recovered.cancellationRequested) {
            "Workspace $workspaceId is not recoverable"
        }
        return startTask(recovered, lane, resumed = true) {
            hook.resume(this, recovered)
        }
    }

    fun resumeRecoverable(
        laneSelector: (AgentWorkspace) -> AgentTaskLane = { AgentTaskLane.READ_REASONING },
        hook: AgentTaskResumeHook
    ): List<AgentTaskHandle> = recoverableTasks()
        .filterNot { activeByWorkspace.containsKey(it.workspaceId) }
        .map { workspace ->
            startTask(
                workspace = workspace,
                lane = laneSelector(workspace),
                resumed = true
            ) {
                hook.resume(this, workspace)
            }
        }

    fun cancelTask(taskId: String, reason: String = "Task cancellation requested"): Boolean {
        val cleanTaskId = taskId.trim()
        if (cleanTaskId.isBlank()) return false
        val active = activeByTask[cleanTaskId]
        return if (active != null) {
            cancelWorkspace(active.workspaceId, reason)
        } else {
            val workspace = workspaceStore.list().firstOrNull { it.taskId == cleanTaskId } ?: return false
            cancelWorkspace(workspace.workspaceId, reason)
        }
    }

    fun cancelWorkspace(
        workspaceId: String,
        reason: String = "Task cancellation requested"
    ): Boolean {
        val cleanWorkspaceId = workspaceId.trim()
        if (cleanWorkspaceId.isBlank()) return false
        val cleanReason = reason.ifBlank { "Task cancellation requested" }
        var changed = false
        var shouldCancelExecution = false
        synchronized(storeMutationLock) {
            val current = workspaceStore.find(cleanWorkspaceId) ?: return@synchronized
            if (!current.status.isTerminal || current.status == AgentWorkspaceStatus.CANCELLED) {
                shouldCancelExecution = true
            }
            if (!current.status.isTerminal ||
                (current.status == AgentWorkspaceStatus.CANCELLED && !current.cancellationRequested)
            ) {
                mutateWorkspaceLocked(cleanWorkspaceId) { latest ->
                    if (latest.status.isTerminal && latest.cancellationRequested) {
                        latest
                    } else {
                        changed = true
                        transitionCandidate(
                            current = latest,
                            status = AgentWorkspaceStatus.CANCELLED,
                            eventKind = AgentTaskEventKinds.CANCELLED,
                            message = cleanReason,
                            cancellationRequested = true
                        )
                    }
                }
            }
        }
        if (shouldCancelExecution) {
            activeByWorkspace[cleanWorkspaceId]?.cancellationSource?.cancelExecution(cleanReason)
        }
        return changed
    }

    fun appendEvent(
        workspaceId: String,
        kind: String,
        message: String = "",
        payloadJson: String = ""
    ): AgentWorkspace = synchronized(storeMutationLock) {
        mutateWorkspaceLocked(workspaceId) { current ->
            appendEventCandidate(current, kind, message, payloadJson)
        }
    }

    fun checkpoint(
        workspaceId: String,
        checkpointId: String,
        planSnapshot: String = "",
        stateJson: String = ""
    ): AgentWorkspace = synchronized(storeMutationLock) {
        val cleanCheckpointId = checkpointId.trim()
        require(cleanCheckpointId.isNotBlank()) { "checkpointId must not be blank" }
        mutateWorkspaceLocked(workspaceId) { current ->
            val now = now()
            val withEvent = appendEventCandidate(
                current = current,
                kind = AgentTaskEventKinds.CHECKPOINT,
                message = cleanCheckpointId,
                payloadJson = ""
            )
            val checkpoint = AgentWorkspaceCheckpoint(
                id = cleanCheckpointId,
                eventSequence = withEvent.eventSequence,
                planSnapshot = planSnapshot.ifBlank { current.currentPlanSnapshot },
                stateJson = stateJson,
                createdAtMillis = now
            )
            val checkpoints = current.checkpoints
                .filterNot { it.id == cleanCheckpointId }
                .plus(checkpoint)
                .sortedWith(compareBy<AgentWorkspaceCheckpoint> { it.eventSequence }
                    .thenBy { it.createdAtMillis }
                    .thenBy { it.id })
                .takeLast(AgentWorkspaceLimits.MAX_CHECKPOINTS)
            withEvent.copy(
                currentPlanSnapshot = checkpoint.planSnapshot,
                checkpoints = checkpoints,
                updatedAtMillis = maxOf(withEvent.updatedAtMillis, now)
            )
        }
    }

    fun transition(
        workspaceId: String,
        status: AgentWorkspaceStatus,
        eventKind: String = "task.status.${status.name.lowercase()}",
        message: String = "",
        payloadJson: String = ""
    ): AgentWorkspace = synchronized(storeMutationLock) {
        mutateWorkspaceLocked(workspaceId) { current ->
            transitionCandidate(
                current = current,
                status = status,
                eventKind = eventKind,
                message = message,
                payloadJson = payloadJson,
                cancellationRequested = current.cancellationRequested || status == AgentWorkspaceStatus.CANCELLED
            )
        }
    }

    internal fun requireWorkspace(workspaceId: String): AgentWorkspace =
        requireNotNull(workspaceStore.find(workspaceId.trim())) {
            "Agent workspace $workspaceId does not exist"
        }

    internal fun deferTask(
        workspaceId: String,
        status: AgentWorkspaceStatus,
        eventKind: String,
        message: String
    ): Nothing {
        transition(workspaceId, status, eventKind, message)
        throw AgentTaskDeferredException(status)
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            supervisorJob.cancel(CancellationException("Agent task supervisor closed"))
        }
    }

    suspend fun shutdown() {
        val jobs = activeByWorkspace.values.mapNotNull { it.executionJob }
        close()
        jobs.joinAll()
    }

    private fun startTask(
        workspace: AgentWorkspace,
        lane: AgentTaskLane,
        resumed: Boolean,
        block: suspend AgentTaskContext.() -> Unit
    ): AgentTaskHandle {
        check(isActive) { "Agent task supervisor is closed" }
        val normalizedWorkspace = workspace.copy(
            workspaceId = workspace.workspaceId.trim(),
            sessionId = workspace.sessionId.trim(),
            conversationId = workspace.conversationId.trim(),
            taskId = workspace.taskId.trim()
        )
        require(normalizedWorkspace.workspaceId.isNotBlank()) { "workspaceId must not be blank" }
        require(normalizedWorkspace.sessionId.isNotBlank()) { "sessionId must not be blank" }
        require(normalizedWorkspace.conversationId.isNotBlank()) { "conversationId must not be blank" }
        require(normalizedWorkspace.taskId.isNotBlank()) { "taskId must not be blank" }

        val taskJob = Job(supervisorJob)
        lateinit var control: TaskControl
        val cancellationSource = AgentTaskCancellationSource(taskJob) { reason ->
            cancelWorkspace(normalizedWorkspace.workspaceId, reason)
        }
        control = TaskControl(
            workspaceId = normalizedWorkspace.workspaceId,
            taskId = normalizedWorkspace.taskId,
            lane = lane,
            cancellationSource = cancellationSource
        )
        try {
            reserve(control)
        } catch (failure: Throwable) {
            taskJob.complete()
            throw failure
        }

        val queued = try {
            queueWorkspace(normalizedWorkspace, resumed)
        } catch (failure: Throwable) {
            release(control)
            taskJob.complete()
            throw failure
        }
        val context = AgentTaskContext(
            workspaceKey = queued.key,
            lane = lane,
            cancellationSource = cancellationSource,
            supervisor = this
        )
        val execution = applicationScope.launch(
            taskJob + CoroutineName("AgentTask-${queued.taskId}")
        ) {
            runTask(control, context, block)
        }
        control.executionJob = execution
        execution.invokeOnCompletion { cause ->
            if (cause is CancellationException) finishInterrupted(control)
            release(control)
            cancellationSource.complete()
        }
        return AgentTaskHandle(
            workspaceId = queued.workspaceId,
            taskId = queued.taskId,
            lane = lane,
            cancellationSource = cancellationSource,
            job = execution
        )
    }

    private suspend fun runTask(
        control: TaskControl,
        context: AgentTaskContext,
        block: suspend AgentTaskContext.() -> Unit
    ) {
        try {
            runInLane(control.lane) {
                context.ensureActive()
                transition(
                    workspaceId = control.workspaceId,
                    status = AgentWorkspaceStatus.RUNNING,
                    eventKind = AgentTaskEventKinds.RUNNING
                )
                context.block()
            }
            finishCompleted(control)
        } catch (_: AgentTaskDeferredException) {
            // The context already persisted the waiting, paused, or blocked state.
        } catch (_: CancellationException) {
            finishInterrupted(control)
        } catch (failure: Throwable) {
            finishFailed(control, failure)
        }
    }

    private suspend fun <T> runInLane(lane: AgentTaskLane, block: suspend () -> T): T = when (lane) {
        AgentTaskLane.READ_REASONING -> {
            readReasoningPermits.acquire()
            try {
                block()
            } finally {
                readReasoningPermits.release()
            }
        }

        AgentTaskLane.SIDE_EFFECT -> {
            sideEffectMutex.lock()
            try {
                block()
            } finally {
                sideEffectMutex.unlock()
            }
        }
    }

    private fun queueWorkspace(workspace: AgentWorkspace, resumed: Boolean): AgentWorkspace =
        synchronized(storeMutationLock) {
            val existing = workspaceStore.find(workspace.workspaceId)
            if (existing == null) {
                require(!workspace.status.isTerminal && !workspace.cancellationRequested) {
                    "A new task workspace must be recoverable"
                }
                val queued = transitionCandidate(
                    current = workspace.copy(revision = 0L),
                    status = AgentWorkspaceStatus.QUEUED,
                    eventKind = if (resumed) AgentTaskEventKinds.RESUMED else AgentTaskEventKinds.QUEUED
                )
                workspaceStore.upsert(queued, expectedRevision = 0L)
            } else {
                require(existing.key == workspace.key) {
                    "Agent workspace identity fields cannot change"
                }
                require(!existing.status.isTerminal && !existing.cancellationRequested) {
                    "Workspace ${workspace.workspaceId} is not recoverable"
                }
                mutateWorkspaceLocked(workspace.workspaceId) { current ->
                    transitionCandidate(
                        current = current,
                        status = AgentWorkspaceStatus.QUEUED,
                        eventKind = if (resumed) AgentTaskEventKinds.RESUMED else AgentTaskEventKinds.QUEUED
                    )
                }
            }
        }

    private fun finishCompleted(control: TaskControl) {
        runCatching {
            synchronized(storeMutationLock) {
                mutateWorkspaceLocked(control.workspaceId) { current ->
                    when {
                        current.status.isTerminal -> current
                        current.cancellationRequested -> transitionCandidate(
                            current,
                            AgentWorkspaceStatus.CANCELLED,
                            AgentTaskEventKinds.CANCELLED,
                            "Task cancellation requested",
                            cancellationRequested = true
                        )

                        current.status in DEFERRED_STATUSES -> current
                        else -> transitionCandidate(
                            current,
                            AgentWorkspaceStatus.COMPLETED,
                            AgentTaskEventKinds.COMPLETED
                        )
                    }
                }
            }
        }
    }

    private fun finishFailed(control: TaskControl, failure: Throwable) {
        runCatching {
            synchronized(storeMutationLock) {
                mutateWorkspaceLocked(control.workspaceId) { current ->
                    when {
                        current.status.isTerminal -> current
                        current.cancellationRequested -> transitionCandidate(
                            current,
                            AgentWorkspaceStatus.CANCELLED,
                            AgentTaskEventKinds.CANCELLED,
                            "Task cancellation requested",
                            cancellationRequested = true
                        )

                        else -> transitionCandidate(
                            current,
                            AgentWorkspaceStatus.FAILED,
                            AgentTaskEventKinds.FAILED,
                            failure.message?.takeIf { it.isNotBlank() }
                                ?: failure::class.java.simpleName.ifBlank { "Task failed" }
                        )
                    }
                }
            }
        }
    }

    private fun finishInterrupted(control: TaskControl) {
        runCatching {
            synchronized(storeMutationLock) {
                mutateWorkspaceLocked(control.workspaceId) { current ->
                    when {
                        current.status.isTerminal -> current
                        current.cancellationRequested || control.cancellationSource.isCancellationRequested ->
                            transitionCandidate(
                                current,
                                AgentWorkspaceStatus.CANCELLED,
                                AgentTaskEventKinds.CANCELLED,
                                "Task cancellation requested",
                                cancellationRequested = true
                            )

                        current.status in DEFERRED_STATUSES -> current
                        else -> transitionCandidate(
                            current,
                            AgentWorkspaceStatus.PAUSED,
                            AgentTaskEventKinds.INTERRUPTED,
                            "Task execution was interrupted"
                        )
                    }
                }
            }
        }
    }

    private fun transitionCandidate(
        current: AgentWorkspace,
        status: AgentWorkspaceStatus,
        eventKind: String,
        message: String = "",
        payloadJson: String = "",
        cancellationRequested: Boolean = current.cancellationRequested
    ): AgentWorkspace {
        require(!current.status.isTerminal || current.status == status) {
            "Terminal workspace ${current.workspaceId} cannot transition from ${current.status} to $status"
        }
        return appendEventCandidate(current, eventKind, message, payloadJson).copy(
            status = status,
            cancellationRequested = cancellationRequested
        )
    }

    private fun appendEventCandidate(
        current: AgentWorkspace,
        kind: String,
        message: String,
        payloadJson: String
    ): AgentWorkspace {
        val cleanKind = kind.trim()
        require(cleanKind.isNotBlank()) { "event kind must not be blank" }
        require(current.eventSequence < Long.MAX_VALUE) { "Agent workspace event sequence exhausted" }
        val timestamp = now()
        val nextSequence = current.eventSequence + 1L
        val event = AgentWorkspaceEvent(
            sequence = nextSequence,
            kind = cleanKind,
            message = message,
            payloadJson = payloadJson,
            timestampMillis = timestamp
        )
        return current.copy(
            eventSequence = nextSequence,
            eventJournal = (current.eventJournal + event).takeLast(AgentWorkspaceLimits.MAX_EVENTS),
            updatedAtMillis = maxOf(current.updatedAtMillis, timestamp)
        )
    }

    private fun mutateWorkspaceLocked(
        workspaceId: String,
        mutation: (AgentWorkspace) -> AgentWorkspace
    ): AgentWorkspace {
        val cleanWorkspaceId = workspaceId.trim()
        require(cleanWorkspaceId.isNotBlank()) { "workspaceId must not be blank" }
        var lastConflict: AgentWorkspaceRevisionConflictException? = null
        repeat(MAX_STORE_WRITE_ATTEMPTS) {
            val current = requireNotNull(workspaceStore.find(cleanWorkspaceId)) {
                "Agent workspace $cleanWorkspaceId does not exist"
            }
            val candidate = mutation(current)
            if (candidate == current) return current
            try {
                return workspaceStore.upsert(
                    candidate.copy(revision = current.revision),
                    expectedRevision = current.revision
                )
            } catch (conflict: AgentWorkspaceRevisionConflictException) {
                lastConflict = conflict
            }
        }
        throw checkNotNull(lastConflict)
    }

    private fun reserve(control: TaskControl) {
        check(activeByWorkspace.putIfAbsent(control.workspaceId, control) == null) {
            "Workspace ${control.workspaceId} already has an active task"
        }
        if (activeByTask.putIfAbsent(control.taskId, control) != null) {
            activeByWorkspace.remove(control.workspaceId, control)
            error("Task ${control.taskId} is already active")
        }
    }

    private fun release(control: TaskControl) {
        activeByWorkspace.remove(control.workspaceId, control)
        activeByTask.remove(control.taskId, control)
    }

    private fun now(): Long = clock().coerceAtLeast(0L)

    private class TaskControl(
        val workspaceId: String,
        val taskId: String,
        val lane: AgentTaskLane,
        val cancellationSource: AgentTaskCancellationSource,
        @Volatile var executionJob: Job? = null
    )

    private class AgentTaskDeferredException(
        val status: AgentWorkspaceStatus
    ) : RuntimeException(null, null, false, false)

    companion object {
        const val DEFAULT_MAX_READ_REASONING_TASKS = 3
        private const val MAX_STORE_WRITE_ATTEMPTS = 5
        private val DEFERRED_STATUSES = setOf(
            AgentWorkspaceStatus.WAITING_CONFIRMATION,
            AgentWorkspaceStatus.WAITING_RESPONSE,
            AgentWorkspaceStatus.PAUSED,
            AgentWorkspaceStatus.BLOCKED
        )
    }
}
