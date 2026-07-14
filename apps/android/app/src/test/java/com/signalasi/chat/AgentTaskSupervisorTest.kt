package com.signalasi.chat

import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentTaskSupervisorTest {
    @Test
    fun readReasoningLaneBoundsConcurrentWork() = runBlocking {
        val store = InMemoryAgentWorkspaceStore()
        val supervisor = AgentTaskSupervisor(store, maxConcurrentReadReasoningTasks = 2)
        val running = AtomicInteger(0)
        val maximumRunning = AtomicInteger(0)
        val started = AtomicInteger(0)
        val release = CompletableDeferred<Unit>()

        val handles = (1..4).map { index ->
            supervisor.submit(workspace(index.toString())) {
                val active = running.incrementAndGet()
                updateMaximum(maximumRunning, active)
                started.incrementAndGet()
                try {
                    release.await()
                } finally {
                    running.decrementAndGet()
                }
            }
        }

        awaitCondition { started.get() == 2 }
        assertEquals(2, maximumRunning.get())
        assertEquals(2, started.get())

        release.complete(Unit)
        handles.map { it.job }.joinAll()

        assertEquals(4, started.get())
        assertTrue(store.list().all { it.status == AgentWorkspaceStatus.COMPLETED })
        supervisor.shutdown()
    }

    @Test
    fun sideEffectLaneRunsOneTaskAtATime() = runBlocking {
        val store = InMemoryAgentWorkspaceStore()
        val supervisor = AgentTaskSupervisor(store)
        val firstStarted = CompletableDeferred<Unit>()
        val releaseFirst = CompletableDeferred<Unit>()
        val secondStarted = CompletableDeferred<Unit>()

        val first = supervisor.submit(workspace("first"), AgentTaskLane.SIDE_EFFECT) {
            firstStarted.complete(Unit)
            releaseFirst.await()
        }
        withTimeout(TEST_TIMEOUT_MILLIS) { firstStarted.await() }
        val second = supervisor.submit(workspace("second"), AgentTaskLane.SIDE_EFFECT) {
            secondStarted.complete(Unit)
        }

        delay(100L)
        assertFalse(secondStarted.isCompleted)

        releaseFirst.complete(Unit)
        listOf(first.job, second.job).joinAll()

        assertTrue(secondStarted.isCompleted)
        assertEquals(AgentWorkspaceStatus.COMPLETED, store.find("workspace-first")?.status)
        assertEquals(AgentWorkspaceStatus.COMPLETED, store.find("workspace-second")?.status)
        supervisor.shutdown()
    }

    @Test
    fun failedTaskDoesNotCancelSibling() = runBlocking {
        val store = InMemoryAgentWorkspaceStore()
        val supervisor = AgentTaskSupervisor(store)
        val siblingCompleted = CompletableDeferred<Unit>()

        val failed = supervisor.submit(workspace("failed")) {
            throw IllegalStateException("reasoning failed")
        }
        val sibling = supervisor.submit(workspace("sibling")) {
            delay(50L)
            siblingCompleted.complete(Unit)
        }

        listOf(failed.job, sibling.job).joinAll()

        assertTrue(siblingCompleted.isCompleted)
        assertEquals(AgentWorkspaceStatus.FAILED, store.find("workspace-failed")?.status)
        assertEquals(AgentWorkspaceStatus.COMPLETED, store.find("workspace-sibling")?.status)
        assertTrue(supervisor.isActive)
        supervisor.shutdown()
    }

    @Test
    fun cancellationSourcePersistsCancellationEventAndCheckpoint() = runBlocking {
        var now = 1_000L
        val store = InMemoryAgentWorkspaceStore(clock = { now++ })
        val supervisor = AgentTaskSupervisor(store, clock = { now++ })
        val started = CompletableDeferred<Unit>()
        val handle = supervisor.submit(workspace("cancel")) {
            checkpoint(
                checkpointId = "before-side-effect",
                planSnapshot = "1. Read\n2. Confirm\n3. Execute",
                stateJson = "{\"step\":2}"
            )
            started.complete(Unit)
            awaitCancellation()
        }

        withTimeout(TEST_TIMEOUT_MILLIS) { started.await() }
        assertTrue(handle.cancel("user stopped task"))
        handle.join()

        val cancelled = requireNotNull(store.find("workspace-cancel"))
        assertEquals(AgentWorkspaceStatus.CANCELLED, cancelled.status)
        assertTrue(cancelled.cancellationRequested)
        assertEquals("before-side-effect", cancelled.checkpoints.single().id)
        assertEquals("{\"step\":2}", cancelled.checkpoints.single().stateJson)
        assertTrue(cancelled.eventJournal.any { it.kind == AgentTaskEventKinds.CHECKPOINT })
        assertTrue(cancelled.eventJournal.any {
            it.kind == AgentTaskEventKinds.CANCELLED && it.message == "user stopped task"
        })
        assertTrue(handle.cancellationSource.isCancellationRequested)
        assertTrue(supervisor.recoverableTasks().isEmpty())
        supervisor.shutdown()
    }

    @Test
    fun recoverableTasksResumeThroughHookFromDurableState() = runBlocking {
        val store = InMemoryAgentWorkspaceStore()
        store.upsert(workspace("paused", status = AgentWorkspaceStatus.PAUSED))
        store.upsert(workspace("running", status = AgentWorkspaceStatus.RUNNING))
        store.upsert(workspace("complete", status = AgentWorkspaceStatus.COMPLETED))
        val supervisor = AgentTaskSupervisor(store)
        val resumedIds = Collections.synchronizedList(mutableListOf<String>())

        assertEquals(
            setOf("workspace-paused", "workspace-running"),
            supervisor.recoverableTasks().map { it.workspaceId }.toSet()
        )

        val handles = supervisor.resumeRecoverable(
            hook = AgentTaskResumeHook { context, recovered ->
                resumedIds += recovered.workspaceId
                context.checkpoint(
                    checkpointId = "resumed-${recovered.taskId}",
                    stateJson = "{\"resumed\":true}"
                )
            }
        )
        handles.map { it.job }.joinAll()

        assertEquals(setOf("workspace-paused", "workspace-running"), resumedIds.toSet())
        listOf("workspace-paused", "workspace-running").forEach { workspaceId ->
            val resumed = requireNotNull(store.find(workspaceId))
            assertEquals(AgentWorkspaceStatus.COMPLETED, resumed.status)
            assertTrue(resumed.eventJournal.any { it.kind == AgentTaskEventKinds.RESUMED })
            assertTrue(resumed.checkpoints.single().stateJson.contains("resumed"))
        }
        assertEquals(AgentWorkspaceStatus.COMPLETED, store.find("workspace-complete")?.status)
        supervisor.shutdown()
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

    private fun workspace(
        suffix: String,
        status: AgentWorkspaceStatus = AgentWorkspaceStatus.CREATED
    ): AgentWorkspace = AgentWorkspace(
        workspaceId = "workspace-$suffix",
        sessionId = "session-$suffix",
        conversationId = "conversation-$suffix",
        taskId = "task-$suffix",
        status = status
    )

    private companion object {
        const val TEST_TIMEOUT_MILLIS = 5_000L
    }
}
