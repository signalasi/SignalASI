package com.signalasi.chat

import java.io.Closeable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CompletableJob
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.withContext

enum class AgentSubagentFailurePolicy {
    CONTINUE,
    FAIL_FAST
}

enum class AgentSubagentDependencyPolicy {
    REQUIRE_SUCCESS,
    ALLOW_TERMINAL
}

enum class AgentSubagentStatus {
    QUEUED,
    RUNNING,
    SUCCEEDED,
    FAILED,
    CANCELLED,
    SKIPPED;

    val isTerminal: Boolean
        get() = this == SUCCEEDED || this == FAILED || this == CANCELLED || this == SKIPPED
}

enum class AgentSubagentRunStatus {
    SUCCEEDED,
    COMPLETED_WITH_FAILURES,
    FAILED,
    CANCELLED
}

data class AgentSubagentLimits(
    val maxChildren: Int = DEFAULT_MAX_CHILDREN,
    val maxDepth: Int = DEFAULT_MAX_DEPTH,
    val maxConcurrency: Int = DEFAULT_MAX_CONCURRENCY,
    val maxContextChars: Int = DEFAULT_MAX_CONTEXT_CHARS,
    val maxOutputChars: Int = DEFAULT_MAX_OUTPUT_CHARS
) {
    init {
        require(maxChildren > 0) { "maxChildren must be positive" }
        require(maxDepth > 0) { "maxDepth must be positive" }
        require(maxConcurrency > 0) { "maxConcurrency must be positive" }
        require(maxContextChars >= 0) { "maxContextChars must not be negative" }
        require(maxOutputChars >= 0) { "maxOutputChars must not be negative" }
    }

    companion object {
        const val DEFAULT_MAX_CHILDREN = 12
        const val DEFAULT_MAX_DEPTH = 3
        const val DEFAULT_MAX_CONCURRENCY = 3
        const val DEFAULT_MAX_CONTEXT_CHARS = 8_000
        const val DEFAULT_MAX_OUTPUT_CHARS = 16_000
    }
}

data class AgentSubagentProvenance(
    val source: String = "unspecified",
    val sourceId: String = "",
    val traceId: String = "",
    val metadata: Map<String, String> = emptyMap()
)

data class AgentSubagentChild(
    val childId: String,
    val parentId: String? = null,
    val dependencies: Set<String> = emptySet(),
    val dependencyPolicy: AgentSubagentDependencyPolicy = AgentSubagentDependencyPolicy.REQUIRE_SUCCESS,
    val context: String = "",
    val provenance: AgentSubagentProvenance = AgentSubagentProvenance()
)

data class AgentSubagentPlan(
    val supervisorId: String,
    val children: List<AgentSubagentChild>,
    val failurePolicy: AgentSubagentFailurePolicy = AgentSubagentFailurePolicy.CONTINUE,
    val provenance: AgentSubagentProvenance = AgentSubagentProvenance()
)

data class AgentSubagentDependencyHandoff(
    val childId: String,
    val status: AgentSubagentStatus,
    val output: String,
    val outputTruncated: Boolean,
    val errorMessage: String = "",
    val provenance: AgentSubagentProvenance
)

data class AgentSubagentContextHandoff(
    val context: String,
    val dependencies: List<AgentSubagentDependencyHandoff>,
    val usedChars: Int,
    val maxChars: Int,
    val truncated: Boolean
)

data class AgentSubagentExecutionContext(
    val supervisorId: String,
    val childId: String,
    val parentId: String,
    val depth: Int,
    val handoff: AgentSubagentContextHandoff,
    val provenance: AgentSubagentProvenance
) {
    suspend fun ensureActive() {
        currentCoroutineContext().ensureActive()
    }

    fun dependency(childId: String): AgentSubagentDependencyHandoff? =
        handoff.dependencies.firstOrNull { it.childId == childId.trim() }
}

data class AgentSubagentOutput(
    val content: String = ""
)

fun interface AgentSubagentWorker {
    suspend fun execute(context: AgentSubagentExecutionContext): AgentSubagentOutput
}

data class AgentSubagentChildResult(
    val supervisorId: String,
    val childId: String,
    val parentId: String,
    val depth: Int,
    val status: AgentSubagentStatus,
    val output: String = "",
    val outputTruncated: Boolean = false,
    val errorMessage: String = "",
    val provenance: AgentSubagentProvenance = AgentSubagentProvenance(),
    val startedAtMillis: Long = 0L,
    val completedAtMillis: Long = 0L
)

data class AgentSubagentRunResult(
    val supervisorId: String,
    val status: AgentSubagentRunStatus,
    val results: List<AgentSubagentChildResult>,
    val provenance: AgentSubagentProvenance,
    val startedAtMillis: Long,
    val completedAtMillis: Long
) {
    operator fun get(childId: String): AgentSubagentChildResult? =
        results.firstOrNull { it.childId == childId.trim() }
}

object AgentSubagentEventKinds {
    const val SUPERVISOR_STARTED = "subagent.supervisor.started"
    const val SUPERVISOR_SUCCEEDED = "subagent.supervisor.succeeded"
    const val SUPERVISOR_COMPLETED_WITH_FAILURES = "subagent.supervisor.completed_with_failures"
    const val SUPERVISOR_FAILED = "subagent.supervisor.failed"
    const val SUPERVISOR_CANCELLED = "subagent.supervisor.cancelled"
    const val CHILD_QUEUED = "subagent.child.queued"
    const val CHILD_RUNNING = "subagent.child.running"
    const val CHILD_SUCCEEDED = "subagent.child.succeeded"
    const val CHILD_FAILED = "subagent.child.failed"
    const val CHILD_CANCELLED = "subagent.child.cancelled"
    const val CHILD_SKIPPED = "subagent.child.skipped"
}

data class AgentSubagentEvent(
    val sequence: Long,
    val supervisorId: String,
    val childId: String = "",
    val kind: String,
    val childStatus: AgentSubagentStatus? = null,
    val runStatus: AgentSubagentRunStatus? = null,
    val message: String = "",
    val provenance: AgentSubagentProvenance = AgentSubagentProvenance(),
    val result: AgentSubagentChildResult? = null,
    val timestampMillis: Long = 0L
)

/** The runtime awaits this hook, so returning means the event has been durably accepted. */
fun interface AgentSubagentEventHook {
    suspend fun append(event: AgentSubagentEvent)

    companion object {
        val NONE = AgentSubagentEventHook { }
    }
}

class AgentSubagentRunHandle internal constructor(
    val supervisorId: String,
    private val completion: CompletableDeferred<AgentSubagentRunResult>,
    private val requestCancellation: (String) -> Boolean
) {
    val isActive: Boolean
        get() = !completion.isCompleted

    suspend fun await(): AgentSubagentRunResult = completion.await()

    fun cancel(reason: String = "Subagent supervisor cancellation requested"): Boolean =
        requestCancellation(reason.ifBlank { "Subagent supervisor cancellation requested" })
}

/**
 * Process-lifetime, UI-independent orchestration for a bounded static subagent DAG.
 * Child failures are captured as results; raw threads and Activity lifetimes are never owned here.
 */
class AgentSubagentRuntime(
    private val limits: AgentSubagentLimits = AgentSubagentLimits(),
    dispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val eventHook: AgentSubagentEventHook = AgentSubagentEventHook.NONE,
    private val clock: () -> Long = { System.currentTimeMillis() }
) : Closeable {
    private val runtimeJob = SupervisorJob()
    private val runtimeScope = CoroutineScope(
        runtimeJob + dispatcher + CoroutineName("AgentSubagentRuntime")
    )
    private val executionPermits = Semaphore(limits.maxConcurrency)
    private val eventHookMutex = Mutex()
    private val closed = AtomicBoolean(false)
    private val activeRuns = ConcurrentHashMap<String, RunControl>()

    val isActive: Boolean
        get() = runtimeJob.isActive && !closed.get()

    fun activeSupervisorIds(): Set<String> = activeRuns.keys.toSet()

    fun start(
        plan: AgentSubagentPlan,
        worker: AgentSubagentWorker
    ): AgentSubagentRunHandle {
        check(isActive) { "Agent subagent runtime is closed" }
        val normalized = normalizeAndValidate(plan)
        val runJob = SupervisorJob(runtimeJob)
        val childrenJob = SupervisorJob(runJob)
        val completion = CompletableDeferred<AgentSubagentRunResult>()
        val control = RunControl(
            supervisorId = normalized.supervisorId,
            runJob = runJob,
            childrenJob = childrenJob,
            completion = completion
        )
        check(activeRuns.putIfAbsent(normalized.supervisorId, control) == null) {
            "Supervisor ${normalized.supervisorId} already has an active run"
        }

        val orchestration = runtimeScope.launch(
            runJob + CoroutineName("AgentSubagentSupervisor-${normalized.supervisorId}")
        ) {
            orchestrate(control, normalized, worker)
        }
        control.orchestrationJob = orchestration
        return AgentSubagentRunHandle(
            supervisorId = normalized.supervisorId,
            completion = completion,
            requestCancellation = control::requestCancellation
        )
    }

    suspend fun execute(
        plan: AgentSubagentPlan,
        worker: AgentSubagentWorker
    ): AgentSubagentRunResult = start(plan, worker).await()

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            activeRuns.values.forEach {
                it.requestCancellation("Agent subagent runtime closed")
            }
            runtimeJob.cancel(CancellationException("Agent subagent runtime closed"))
        }
    }

    suspend fun shutdown() {
        val completions = activeRuns.values.map { it.completion }
        close()
        completions.forEach { completion ->
            runCatching { completion.await() }
        }
    }

    private suspend fun orchestrate(
        control: RunControl,
        plan: NormalizedPlan,
        worker: AgentSubagentWorker
    ) {
        val startedAt = now()
        val slots = LinkedHashMap<String, CompletableDeferred<AgentSubagentChildResult>>()
        plan.children.forEach { child ->
            slots[child.childId] = CompletableDeferred()
        }
        val childJobs = mutableListOf<Job>()
        try {
            emit(
                control = control,
                plan = plan,
                kind = AgentSubagentEventKinds.SUPERVISOR_STARTED,
                provenance = plan.provenance
            )
            plan.children.forEach { child ->
                emit(
                    control = control,
                    plan = plan,
                    child = child,
                    kind = AgentSubagentEventKinds.CHILD_QUEUED,
                    childStatus = AgentSubagentStatus.QUEUED,
                    provenance = child.provenance
                )
            }
            plan.children.forEach { child ->
                val job = runtimeScope.launch(
                    control.childrenJob + CoroutineName("AgentSubagent-${child.childId}")
                ) {
                    runChild(control, plan, child, slots, worker)
                }
                childJobs += job
            }
            childJobs.joinAll()
            withContext(NonCancellable) {
                completeMissingChildren(control, plan, slots)
            }
            control.hookFailure.get()?.let { throw it }

            val result = aggregate(control, plan, slots, startedAt)
            emitRunFinished(control, plan, result)
            activeRuns.remove(plan.supervisorId, control)
            control.completion.complete(result)
        } catch (cancelled: CancellationException) {
            withContext(NonCancellable) {
                control.requestCancellation(
                    cancelled.message ?: "Subagent supervisor was cancelled"
                )
                control.childrenJob.cancel(cancelled)
                childJobs.joinAll()
                completeMissingChildren(control, plan, slots)
                val result = aggregate(control, plan, slots, startedAt, forceCancelled = true)
                if (control.hookFailure.get() == null) {
                    runCatching { emitRunFinished(control, plan, result) }
                }
                activeRuns.remove(plan.supervisorId, control)
                control.completion.complete(result)
            }
        } catch (failure: Throwable) {
            withContext(NonCancellable) {
                control.childrenJob.cancel(cancellationException("Subagent supervisor failed", failure))
                childJobs.joinAll()
                completeMissingChildren(control, plan, slots)
                activeRuns.remove(plan.supervisorId, control)
                control.completion.completeExceptionally(failure)
            }
        } finally {
            activeRuns.remove(plan.supervisorId, control)
            control.childrenJob.complete()
            control.runJob.complete()
        }
    }

    private suspend fun runChild(
        control: RunControl,
        plan: NormalizedPlan,
        child: NormalizedChild,
        slots: Map<String, CompletableDeferred<AgentSubagentChildResult>>,
        worker: AgentSubagentWorker
    ) {
        val slot = checkNotNull(slots[child.childId])
        val startedAt = now()
        try {
            val dependencies = child.dependencies.map { dependencyId ->
                checkNotNull(slots[dependencyId]).await()
            }
            currentCoroutineContext().ensureActive()
            val unsuccessful = dependencies.filter { it.status != AgentSubagentStatus.SUCCEEDED }
            if (unsuccessful.isNotEmpty() && child.dependencyPolicy == AgentSubagentDependencyPolicy.REQUIRE_SUCCESS) {
                val failedIds = unsuccessful.map { it.childId }.sorted().joinToString(",")
                val skipped = terminalResult(
                    plan = plan,
                    child = child,
                    status = AgentSubagentStatus.SKIPPED,
                    errorMessage = "Dependencies did not succeed: $failedIds",
                    startedAt = startedAt
                )
                publishChildResult(
                    control,
                    plan,
                    child,
                    skipped,
                    AgentSubagentEventKinds.CHILD_SKIPPED,
                    slot
                )
                return
            }

            executionPermits.acquire()
            try {
                currentCoroutineContext().ensureActive()
                emit(
                    control = control,
                    plan = plan,
                    child = child,
                    kind = AgentSubagentEventKinds.CHILD_RUNNING,
                    childStatus = AgentSubagentStatus.RUNNING,
                    provenance = child.provenance
                )
                val handoff = buildHandoff(child, dependencies)
                val output = worker.execute(
                    AgentSubagentExecutionContext(
                        supervisorId = plan.supervisorId,
                        childId = child.childId,
                        parentId = child.parentId,
                        depth = child.depth,
                        handoff = handoff,
                        provenance = child.provenance
                    )
                )
                currentCoroutineContext().ensureActive()
                val boundedOutput = output.content.take(limits.maxOutputChars)
                val succeeded = terminalResult(
                    plan = plan,
                    child = child,
                    status = AgentSubagentStatus.SUCCEEDED,
                    output = boundedOutput,
                    outputTruncated = boundedOutput.length < output.content.length,
                    startedAt = startedAt
                )
                publishChildResult(
                    control,
                    plan,
                    child,
                    succeeded,
                    AgentSubagentEventKinds.CHILD_SUCCEEDED,
                    slot
                )
            } finally {
                executionPermits.release()
            }
        } catch (cancelled: CancellationException) {
            withContext(NonCancellable) {
                if (!slot.isCompleted) {
                    val result = terminalResult(
                        plan = plan,
                        child = child,
                        status = AgentSubagentStatus.CANCELLED,
                        errorMessage = cancellationMessage(control, cancelled),
                        startedAt = startedAt
                    )
                    publishChildResult(
                        control,
                        plan,
                        child,
                        result,
                        AgentSubagentEventKinds.CHILD_CANCELLED,
                        slot
                    )
                }
            }
        } catch (failure: Throwable) {
            if (failure is EventHookFailure || control.hookFailure.get() != null) {
                if (!slot.isCompleted) {
                    slot.complete(
                        terminalResult(
                            plan = plan,
                            child = child,
                            status = AgentSubagentStatus.CANCELLED,
                            errorMessage = "Durable event hook failed",
                            startedAt = startedAt
                        )
                    )
                }
                return
            }
            withContext(NonCancellable) {
                val result = terminalResult(
                    plan = plan,
                    child = child,
                    status = AgentSubagentStatus.FAILED,
                    errorMessage = failure.message?.takeIf { it.isNotBlank() }
                        ?: failure::class.java.simpleName.ifBlank { "Subagent failed" },
                    startedAt = startedAt
                )
                publishChildResult(
                    control,
                    plan,
                    child,
                    result,
                    AgentSubagentEventKinds.CHILD_FAILED,
                    slot
                )
                if (plan.failurePolicy == AgentSubagentFailurePolicy.FAIL_FAST) {
                    control.failFast(child.childId, result.errorMessage)
                }
            }
        }
    }

    private suspend fun publishChildResult(
        control: RunControl,
        plan: NormalizedPlan,
        child: NormalizedChild,
        result: AgentSubagentChildResult,
        eventKind: String,
        slot: CompletableDeferred<AgentSubagentChildResult>
    ) {
        try {
            emit(
                control = control,
                plan = plan,
                child = child,
                kind = eventKind,
                childStatus = result.status,
                message = result.errorMessage,
                provenance = child.provenance,
                result = result
            )
        } finally {
            slot.complete(result)
        }
    }

    private suspend fun completeMissingChildren(
        control: RunControl,
        plan: NormalizedPlan,
        slots: Map<String, CompletableDeferred<AgentSubagentChildResult>>
    ) {
        plan.children.forEach { child ->
            val slot = checkNotNull(slots[child.childId])
            if (!slot.isCompleted) {
                val result = terminalResult(
                    plan = plan,
                    child = child,
                    status = AgentSubagentStatus.CANCELLED,
                    errorMessage = cancellationMessage(control, null),
                    startedAt = now()
                )
                if (control.hookFailure.get() == null) {
                    publishChildResult(
                        control,
                        plan,
                        child,
                        result,
                        AgentSubagentEventKinds.CHILD_CANCELLED,
                        slot
                    )
                } else {
                    slot.complete(result)
                }
            }
        }
    }

    private fun buildHandoff(
        child: NormalizedChild,
        dependencyResults: List<AgentSubagentChildResult>
    ): AgentSubagentContextHandoff {
        var remaining = limits.maxContextChars
        var truncated = false

        val context = child.context.take(remaining)
        remaining -= context.length
        truncated = truncated || context.length < child.context.length

        val dependencies = dependencyResults
            .sortedBy { it.childId }
            .map { result ->
                val output = result.output.take(remaining)
                remaining -= output.length
                val outputTruncated = result.outputTruncated || output.length < result.output.length
                truncated = truncated || outputTruncated
                AgentSubagentDependencyHandoff(
                    childId = result.childId,
                    status = result.status,
                    output = output,
                    outputTruncated = outputTruncated,
                    errorMessage = result.errorMessage.take(MAX_ERROR_CHARS),
                    provenance = result.provenance
                )
            }
        return AgentSubagentContextHandoff(
            context = context,
            dependencies = dependencies,
            usedChars = limits.maxContextChars - remaining,
            maxChars = limits.maxContextChars,
            truncated = truncated
        )
    }

    private fun terminalResult(
        plan: NormalizedPlan,
        child: NormalizedChild,
        status: AgentSubagentStatus,
        output: String = "",
        outputTruncated: Boolean = false,
        errorMessage: String = "",
        startedAt: Long
    ): AgentSubagentChildResult = AgentSubagentChildResult(
        supervisorId = plan.supervisorId,
        childId = child.childId,
        parentId = child.parentId,
        depth = child.depth,
        status = status,
        output = output,
        outputTruncated = outputTruncated,
        errorMessage = errorMessage.take(MAX_ERROR_CHARS),
        provenance = child.provenance,
        startedAtMillis = startedAt,
        completedAtMillis = now()
    )

    private suspend fun aggregate(
        control: RunControl,
        plan: NormalizedPlan,
        slots: Map<String, CompletableDeferred<AgentSubagentChildResult>>,
        startedAt: Long,
        forceCancelled: Boolean = false
    ): AgentSubagentRunResult {
        val results = slots.values
            .map { it.await() }
            .sortedBy { it.childId }
        val status = when {
            forceCancelled || control.cancellationReason.get() != null ->
                AgentSubagentRunStatus.CANCELLED
            plan.failurePolicy == AgentSubagentFailurePolicy.FAIL_FAST &&
                results.any { it.status == AgentSubagentStatus.FAILED } -> AgentSubagentRunStatus.FAILED
            results.any { it.status == AgentSubagentStatus.CANCELLED } -> AgentSubagentRunStatus.CANCELLED
            results.any {
                it.status == AgentSubagentStatus.FAILED || it.status == AgentSubagentStatus.SKIPPED
            } -> AgentSubagentRunStatus.COMPLETED_WITH_FAILURES
            else -> AgentSubagentRunStatus.SUCCEEDED
        }
        return AgentSubagentRunResult(
            supervisorId = plan.supervisorId,
            status = status,
            results = results,
            provenance = plan.provenance,
            startedAtMillis = startedAt,
            completedAtMillis = now()
        )
    }

    private suspend fun emitRunFinished(
        control: RunControl,
        plan: NormalizedPlan,
        result: AgentSubagentRunResult
    ) {
        val kind = when (result.status) {
            AgentSubagentRunStatus.SUCCEEDED -> AgentSubagentEventKinds.SUPERVISOR_SUCCEEDED
            AgentSubagentRunStatus.COMPLETED_WITH_FAILURES ->
                AgentSubagentEventKinds.SUPERVISOR_COMPLETED_WITH_FAILURES
            AgentSubagentRunStatus.FAILED -> AgentSubagentEventKinds.SUPERVISOR_FAILED
            AgentSubagentRunStatus.CANCELLED -> AgentSubagentEventKinds.SUPERVISOR_CANCELLED
        }
        emit(
            control = control,
            plan = plan,
            kind = kind,
            runStatus = result.status,
            provenance = plan.provenance
        )
    }

    private suspend fun emit(
        control: RunControl,
        plan: NormalizedPlan,
        child: NormalizedChild? = null,
        kind: String,
        childStatus: AgentSubagentStatus? = null,
        runStatus: AgentSubagentRunStatus? = null,
        message: String = "",
        provenance: AgentSubagentProvenance,
        result: AgentSubagentChildResult? = null
    ) {
        control.hookFailure.get()?.let { throw EventHookFailure(it) }
        eventHookMutex.lock()
        try {
            control.hookFailure.get()?.let { throw EventHookFailure(it) }
            val sequence = control.eventSequence + 1L
            val event = AgentSubagentEvent(
                sequence = sequence,
                supervisorId = plan.supervisorId,
                childId = child?.childId.orEmpty(),
                kind = kind,
                childStatus = childStatus,
                runStatus = runStatus,
                message = message.take(MAX_ERROR_CHARS),
                provenance = provenance,
                result = result,
                timestampMillis = now()
            )
            try {
                eventHook.append(event)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                control.hookFailure.compareAndSet(null, failure)
                control.childrenJob.cancel(
                    cancellationException("Durable subagent event hook failed", failure)
                )
                throw EventHookFailure(failure)
            }
            control.eventSequence = sequence
        } finally {
            eventHookMutex.unlock()
        }
    }

    private fun cancellationMessage(
        control: RunControl,
        cancellation: CancellationException?
    ): String = control.cancellationReason.get()
        ?: control.failFastChildId.get()?.let { failedId ->
            "Fail-fast cancellation after child $failedId failed"
        }
        ?: cancellation?.message
        ?: "Subagent child was cancelled"

    private fun normalizeAndValidate(plan: AgentSubagentPlan): NormalizedPlan {
        val supervisorId = normalizeId(plan.supervisorId, "supervisorId")
        require(plan.children.size <= limits.maxChildren) {
            "Supervisor $supervisorId exceeds maxChildren=${limits.maxChildren}"
        }

        val children = plan.children.map { child ->
            val childId = normalizeId(child.childId, "childId")
            require(childId != supervisorId) { "Child ID must differ from supervisor ID" }
            val parentId = child.parentId
                ?.let { normalizeId(it, "parentId") }
                ?: supervisorId
            val dependencies = child.dependencies
                .map { normalizeId(it, "dependencyId") }
                .toSortedSet()
            require(childId !in dependencies) { "Child $childId cannot depend on itself" }
            NormalizedChild(
                childId = childId,
                parentId = parentId,
                dependencies = dependencies.toList(),
                dependencyPolicy = child.dependencyPolicy,
                context = child.context,
                provenance = normalizeProvenance(child.provenance)
            )
        }.sortedBy { it.childId }

        val byId = children.associateBy { it.childId }
        require(byId.size == children.size) { "Child IDs must be unique" }
        children.forEach { child ->
            require(child.parentId == supervisorId || child.parentId in byId) {
                "Parent ${child.parentId} for child ${child.childId} does not exist"
            }
            require(child.parentId != child.childId) { "Child ${child.childId} cannot parent itself" }
            child.dependencies.forEach { dependencyId ->
                require(dependencyId in byId) {
                    "Dependency $dependencyId for child ${child.childId} does not exist"
                }
            }
        }

        val depths = calculateDepths(supervisorId, byId)
        validateDependencyDag(byId)
        val withDepth = children.map { child ->
            child.copy(depth = checkNotNull(depths[child.childId]))
        }
        return NormalizedPlan(
            supervisorId = supervisorId,
            children = withDepth,
            failurePolicy = plan.failurePolicy,
            provenance = normalizeProvenance(plan.provenance)
        )
    }

    private fun calculateDepths(
        supervisorId: String,
        children: Map<String, NormalizedChild>
    ): Map<String, Int> {
        val depths = mutableMapOf<String, Int>()
        val visiting = mutableSetOf<String>()

        fun depth(childId: String): Int {
            depths[childId]?.let { return it }
            require(visiting.add(childId)) { "Subagent parent hierarchy contains a cycle at $childId" }
            val child = checkNotNull(children[childId])
            val value = if (child.parentId == supervisorId) {
                1
            } else {
                depth(child.parentId) + 1
            }
            visiting.remove(childId)
            require(value <= limits.maxDepth) {
                "Child $childId exceeds maxDepth=${limits.maxDepth}"
            }
            depths[childId] = value
            return value
        }

        children.keys.sorted().forEach(::depth)
        return depths
    }

    private fun validateDependencyDag(children: Map<String, NormalizedChild>) {
        val state = mutableMapOf<String, Int>()

        fun visit(childId: String) {
            when (state[childId]) {
                1 -> throw IllegalArgumentException(
                    "Subagent dependency graph contains a cycle at $childId"
                )
                2 -> return
            }
            state[childId] = 1
            checkNotNull(children[childId]).dependencies.forEach(::visit)
            state[childId] = 2
        }

        children.keys.sorted().forEach(::visit)
    }

    private fun normalizeId(value: String, fieldName: String): String {
        val normalized = value.trim()
        require(normalized.isNotBlank()) { "$fieldName must not be blank" }
        require(normalized.length <= MAX_ID_CHARS) {
            "$fieldName exceeds $MAX_ID_CHARS characters"
        }
        return normalized
    }

    private fun normalizeProvenance(
        provenance: AgentSubagentProvenance
    ): AgentSubagentProvenance = provenance.copy(
        source = provenance.source.trim().ifBlank { "unspecified" },
        sourceId = provenance.sourceId.trim(),
        traceId = provenance.traceId.trim(),
        metadata = provenance.metadata.toSortedMap()
    )

    private fun now(): Long = clock().coerceAtLeast(0L)

    private fun cancellationException(message: String, cause: Throwable): CancellationException =
        CancellationException(message).also { it.initCause(cause) }

    private data class NormalizedPlan(
        val supervisorId: String,
        val children: List<NormalizedChild>,
        val failurePolicy: AgentSubagentFailurePolicy,
        val provenance: AgentSubagentProvenance
    )

    private data class NormalizedChild(
        val childId: String,
        val parentId: String,
        val dependencies: List<String>,
        val dependencyPolicy: AgentSubagentDependencyPolicy,
        val context: String,
        val provenance: AgentSubagentProvenance,
        val depth: Int = 0
    )

    private class RunControl(
        val supervisorId: String,
        val runJob: CompletableJob,
        val childrenJob: CompletableJob,
        val completion: CompletableDeferred<AgentSubagentRunResult>,
        val cancellationReason: AtomicReference<String?> = AtomicReference(null),
        val failFastChildId: AtomicReference<String?> = AtomicReference(null),
        val hookFailure: AtomicReference<Throwable?> = AtomicReference(null),
        @Volatile var orchestrationJob: Job? = null,
        @Volatile var eventSequence: Long = 0L
    ) {
        fun requestCancellation(reason: String): Boolean {
            if (completion.isCompleted) return false
            val cleanReason = reason.ifBlank { "Subagent supervisor cancellation requested" }
            if (!cancellationReason.compareAndSet(null, cleanReason)) return false
            childrenJob.cancel(CancellationException(cleanReason))
            return true
        }

        fun failFast(childId: String, errorMessage: String) {
            if (failFastChildId.compareAndSet(null, childId)) {
                childrenJob.cancel(
                    CancellationException(
                        "Child $childId failed: ${errorMessage.ifBlank { "unknown failure" }}"
                    )
                )
            }
        }
    }

    private class EventHookFailure(cause: Throwable) :
        IllegalStateException("Durable subagent event hook failed", cause)

    private companion object {
        const val MAX_ID_CHARS = 160
        const val MAX_ERROR_CHARS = 1_024
    }
}
