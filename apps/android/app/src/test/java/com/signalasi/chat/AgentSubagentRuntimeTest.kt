package com.signalasi.chat

import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class AgentSubagentRuntimeTest {
    @Test
    fun executesDagWithBoundedConcurrencyContextProvenanceAndDeterministicAggregation() =
        runBlocking {
            val events = Collections.synchronizedList(mutableListOf<AgentSubagentEvent>())
            val runtime = AgentSubagentRuntime(
                limits = AgentSubagentLimits(
                    maxChildren = 4,
                    maxDepth = 2,
                    maxConcurrency = 2,
                    maxContextChars = 6,
                    maxOutputChars = 20
                ),
                eventHook = AgentSubagentEventHook { events += it }
            )
            val active = AtomicInteger(0)
            val maximumActive = AtomicInteger(0)
            val rootsStarted = AtomicInteger(0)
            val releaseRoots = CompletableDeferred<Unit>()
            val mergeHandoff = CompletableDeferred<AgentSubagentContextHandoff>()
            val provenance = AgentSubagentProvenance(
                source = "planner",
                sourceId = "request-7",
                traceId = "trace-9"
            )
            val plan = AgentSubagentPlan(
                supervisorId = "supervisor-1",
                provenance = provenance,
                children = listOf(
                    AgentSubagentChild(
                        childId = "merge",
                        parentId = "alpha",
                        dependencies = setOf("beta", "alpha"),
                        context = "xy",
                        provenance = AgentSubagentProvenance(source = "planner", sourceId = "merge")
                    ),
                    AgentSubagentChild("beta", provenance = provenance),
                    AgentSubagentChild("alpha", provenance = provenance)
                )
            )

            val handle = runtime.start(plan) { context ->
                val current = active.incrementAndGet()
                updateMaximum(maximumActive, current)
                try {
                    when (context.childId) {
                        "alpha", "beta" -> {
                            rootsStarted.incrementAndGet()
                            releaseRoots.await()
                            AgentSubagentOutput(if (context.childId == "alpha") "AAAA" else "BBBB")
                        }

                        "merge" -> {
                            mergeHandoff.complete(context.handoff)
                            AgentSubagentOutput("merged")
                        }

                        else -> error("unexpected child")
                    }
                } finally {
                    active.decrementAndGet()
                }
            }

            awaitCondition { rootsStarted.get() == 2 }
            assertEquals(2, maximumActive.get())
            releaseRoots.complete(Unit)
            val result = withTimeout(TEST_TIMEOUT_MILLIS) { handle.await() }
            val handoff = mergeHandoff.await()

            assertEquals(AgentSubagentRunStatus.SUCCEEDED, result.status)
            assertEquals(listOf("alpha", "beta", "merge"), result.results.map { it.childId })
            assertEquals(2, result["merge"]?.depth)
            assertEquals(provenance, result.provenance)
            assertEquals(6, handoff.usedChars)
            assertEquals("xy", handoff.context)
            assertEquals(listOf("alpha", "beta"), handoff.dependencies.map { it.childId })
            assertEquals("AAAA", handoff.dependencies[0].output)
            assertEquals("", handoff.dependencies[1].output)
            assertTrue(handoff.truncated)
            assertEquals(events.indices.map { (it + 1).toLong() }, events.map { it.sequence })
            assertTrue(events.filter { it.childStatus?.isTerminal == true }.all { it.result != null })
            assertEquals(
                AgentSubagentEventKinds.SUPERVISOR_SUCCEEDED,
                events.last().kind
            )
            runtime.shutdown()
        }

    @Test
    fun continuePolicyKeepsIndependentBranchesAndSkipsFailedDependents() = runBlocking {
        val runtime = AgentSubagentRuntime(
            limits = AgentSubagentLimits(maxChildren = 3, maxConcurrency = 3)
        )
        val independentCompleted = CompletableDeferred<Unit>()
        val result = runtime.execute(
            AgentSubagentPlan(
                supervisorId = "continue-supervisor",
                failurePolicy = AgentSubagentFailurePolicy.CONTINUE,
                children = listOf(
                    AgentSubagentChild("failed"),
                    AgentSubagentChild("dependent", dependencies = setOf("failed")),
                    AgentSubagentChild("independent")
                )
            )
        ) { context ->
            when (context.childId) {
                "failed" -> error("branch failed")
                "independent" -> {
                    delay(25L)
                    independentCompleted.complete(Unit)
                    AgentSubagentOutput("still completed")
                }
                else -> error("dependent child must not execute")
            }
        }

        assertTrue(independentCompleted.isCompleted)
        assertEquals(AgentSubagentRunStatus.COMPLETED_WITH_FAILURES, result.status)
        assertEquals(AgentSubagentStatus.FAILED, result["failed"]?.status)
        assertEquals(AgentSubagentStatus.SKIPPED, result["dependent"]?.status)
        assertEquals(AgentSubagentStatus.SUCCEEDED, result["independent"]?.status)
        runtime.shutdown()
    }

    @Test
    fun failFastCancelsRunningSiblings() = runBlocking {
        val runtime = AgentSubagentRuntime(
            limits = AgentSubagentLimits(maxChildren = 2, maxConcurrency = 2)
        )
        val slowStarted = CompletableDeferred<Unit>()
        val result = runtime.execute(
            AgentSubagentPlan(
                supervisorId = "fail-fast-supervisor",
                failurePolicy = AgentSubagentFailurePolicy.FAIL_FAST,
                children = listOf(
                    AgentSubagentChild("failing"),
                    AgentSubagentChild("slow")
                )
            )
        ) { context ->
            when (context.childId) {
                "slow" -> {
                    slowStarted.complete(Unit)
                    awaitCancellation()
                }
                else -> {
                    slowStarted.await()
                    error("stop all children")
                }
            }
        }

        assertEquals(AgentSubagentRunStatus.FAILED, result.status)
        assertEquals(AgentSubagentStatus.FAILED, result["failing"]?.status)
        assertEquals(AgentSubagentStatus.CANCELLED, result["slow"]?.status)
        assertTrue(result["slow"]?.errorMessage.orEmpty().contains("failing"))
        runtime.shutdown()
    }

    @Test
    fun handleCancellationFansOutAndPersistsTerminalEvents() = runBlocking {
        val events = Collections.synchronizedList(mutableListOf<AgentSubagentEvent>())
        val runtime = AgentSubagentRuntime(
            limits = AgentSubagentLimits(maxChildren = 2, maxConcurrency = 2),
            eventHook = AgentSubagentEventHook { events += it }
        )
        val started = AtomicInteger(0)
        val handle = runtime.start(
            AgentSubagentPlan(
                supervisorId = "cancel-supervisor",
                children = listOf(AgentSubagentChild("one"), AgentSubagentChild("two"))
            )
        ) {
            started.incrementAndGet()
            awaitCancellation()
        }

        awaitCondition { started.get() == 2 }
        assertTrue(handle.cancel("user stopped supervisor"))
        assertFalse(handle.cancel("second cancellation"))
        val result = withTimeout(TEST_TIMEOUT_MILLIS) { handle.await() }

        assertEquals(AgentSubagentRunStatus.CANCELLED, result.status)
        assertTrue(result.results.all { it.status == AgentSubagentStatus.CANCELLED })
        assertTrue(result.results.all { it.errorMessage == "user stopped supervisor" })
        assertEquals(2, events.count { it.kind == AgentSubagentEventKinds.CHILD_CANCELLED })
        assertEquals(AgentSubagentEventKinds.SUPERVISOR_CANCELLED, events.last().kind)
        runtime.shutdown()
    }

    @Test
    fun rejectsChildDepthAndDependencyGraphsBeyondConfiguredBounds() {
        val runtime = AgentSubagentRuntime(
            limits = AgentSubagentLimits(maxChildren = 1, maxDepth = 1)
        )

        expectIllegalArgument("maxChildren") {
            runtime.start(
                AgentSubagentPlan(
                    "too-many",
                    listOf(AgentSubagentChild("one"), AgentSubagentChild("two"))
                )
            ) { AgentSubagentOutput() }
        }
        val depthRuntime = AgentSubagentRuntime(
            limits = AgentSubagentLimits(maxChildren = 2, maxDepth = 1)
        )
        expectIllegalArgument("maxDepth") {
            depthRuntime.start(
                AgentSubagentPlan(
                    "too-deep",
                    listOf(
                        AgentSubagentChild("parent"),
                        AgentSubagentChild("child", parentId = "parent")
                    )
                )
            ) { AgentSubagentOutput() }
        }

        val cycleRuntime = AgentSubagentRuntime(
            limits = AgentSubagentLimits(maxChildren = 2, maxDepth = 2)
        )
        expectIllegalArgument("cycle") {
            cycleRuntime.start(
                AgentSubagentPlan(
                    "cycle",
                    listOf(
                        AgentSubagentChild("one", dependencies = setOf("two")),
                        AgentSubagentChild("two", dependencies = setOf("one"))
                    )
                )
            ) { AgentSubagentOutput() }
        }

        assertTrue(runtime.activeSupervisorIds().isEmpty())
        assertTrue(depthRuntime.activeSupervisorIds().isEmpty())
        assertTrue(cycleRuntime.activeSupervisorIds().isEmpty())
        runtime.close()
        depthRuntime.close()
        cycleRuntime.close()
    }

    private suspend fun awaitCondition(condition: () -> Boolean) {
        withTimeout(TEST_TIMEOUT_MILLIS) {
            while (!condition()) delay(10L)
        }
    }

    private fun updateMaximum(maximum: AtomicInteger, candidate: Int) {
        while (true) {
            val current = maximum.get()
            if (candidate <= current || maximum.compareAndSet(current, candidate)) return
        }
    }

    private fun expectIllegalArgument(messagePart: String, block: () -> Unit) {
        try {
            block()
            fail("Expected IllegalArgumentException containing $messagePart")
        } catch (failure: IllegalArgumentException) {
            assertNotNull(failure.message)
            assertTrue(failure.message.orEmpty().contains(messagePart, ignoreCase = true))
        }
    }

    private companion object {
        const val TEST_TIMEOUT_MILLIS = 5_000L
    }
}
