package com.signalasi.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentWorkspaceStoreTest {
    @Test
    fun mutationsAdvanceRevisionAndRejectStaleWriters() {
        var now = 1_000L
        val store = InMemoryAgentWorkspaceStore(clock = { now })
        val created = store.upsert(workspace(status = AgentWorkspaceStatus.RUNNING))

        assertEquals(1L, created.revision)
        assertEquals(now, created.createdAtMillis)
        assertEquals(now, created.updatedAtMillis)

        now = 1_100L
        val withEvent = requireNotNull(store.appendEvent(
            workspaceId = created.workspaceId,
            kind = "tool_started",
            payloadJson = "{\"tool\":\"shell\"}",
            expectedRevision = created.revision
        ))
        assertEquals(2L, withEvent.revision)
        assertEquals(1L, withEvent.eventSequence)
        assertEquals(1L, withEvent.eventJournal.single().sequence)

        assertThrows(AgentWorkspaceRevisionConflictException::class.java) {
            store.appendEvent(
                workspaceId = created.workspaceId,
                kind = "stale",
                expectedRevision = created.revision
            )
        }

        now = 1_200L
        val checkpointed = requireNotNull(store.checkpoint(
            workspaceId = created.workspaceId,
            checkpointId = "checkpoint-1",
            planSnapshot = "1. Inspect\n2. Execute",
            stateJson = "{\"cursor\":2}",
            expectedRevision = withEvent.revision
        ))
        assertEquals(3L, checkpointed.revision)
        assertEquals(1L, checkpointed.eventSequence)
        assertEquals(1L, checkpointed.checkpoints.single().eventSequence)
        assertEquals("1. Inspect\n2. Execute", checkpointed.currentPlanSnapshot)
        assertEquals(listOf(checkpointed), store.recoverable())

        now = 1_300L
        val cancelled = requireNotNull(store.requestCancel(
            created.workspaceId,
            expectedRevision = checkpointed.revision
        ))
        assertTrue(cancelled.cancellationRequested)
        assertEquals(4L, cancelled.revision)
        assertTrue(store.recoverable().isEmpty())
    }

    @Test
    fun eventJournalIsBoundedWhileSequenceRemainsMonotonic() {
        val store = InMemoryAgentWorkspaceStore(clock = { 10L })
        var current = store.upsert(workspace())
        val appendedCount = AgentWorkspaceLimits.MAX_EVENTS + 7

        repeat(appendedCount) { index ->
            current = requireNotNull(store.appendEvent(
                workspaceId = current.workspaceId,
                kind = "progress",
                message = "event-$index",
                expectedRevision = current.revision,
                timestampMillis = 20L + index
            ))
        }

        assertEquals(appendedCount.toLong(), current.eventSequence)
        assertEquals(AgentWorkspaceLimits.MAX_EVENTS, current.eventJournal.size)
        assertEquals(8L, current.eventJournal.first().sequence)
        assertEquals(appendedCount.toLong(), current.eventJournal.last().sequence)
    }

    @Test
    fun deterministicCodecRoundTripsEveryWorkspaceRecordType() {
        val workspace = workspace(
            status = AgentWorkspaceStatus.WAITING_CONFIRMATION,
            currentPlanSnapshot = "Plan \"A\"\nthen B",
            eventSequence = 1L,
            eventJournal = listOf(
                AgentWorkspaceEvent(1L, "tool_result", "done", "{\"ok\":true}", 200L)
            ),
            toolCalls = listOf(
                AgentToolCallRecord(
                    id = "call-1",
                    toolName = "shell",
                    status = AgentToolCallStatus.SUCCEEDED,
                    argumentsJson = "{\"cmd\":\"pwd\"}",
                    resultJson = "{\"exit\":0}",
                    startedAtMillis = 150L,
                    completedAtMillis = 180L
                )
            ),
            checkpoints = listOf(
                AgentWorkspaceCheckpoint("cp-1", 1L, "Plan A", "{\"step\":1}", 210L)
            ),
            artifacts = listOf(
                AgentArtifactReference(
                    id = "artifact-1",
                    uri = "content://signalasi/result.txt",
                    name = "result.txt",
                    mimeType = "text/plain",
                    metadataJson = "{\"bytes\":12}",
                    createdAtMillis = 220L
                )
            ),
            createdAtMillis = 100L,
            updatedAtMillis = 220L,
            revision = 9L
        )

        val encoded = AgentWorkspaceJsonCodec.encode(workspace)
        val decoded = AgentWorkspaceJsonCodec.decode(encoded)

        assertEquals(workspace, decoded)
        assertEquals(encoded, AgentWorkspaceJsonCodec.encode(requireNotNull(decoded)))
        assertTrue(encoded.startsWith("{\"version\":1,\"workspace_id\":"))
        assertNull(AgentWorkspaceJsonCodec.decode("{not-json}"))
    }

    @Test
    fun listFindDeleteClearAndRecoverableUseWorkspaceIdentity() {
        var now = 100L
        val store = InMemoryAgentWorkspaceStore(clock = { now })
        val active = store.upsert(workspace(workspaceId = "active", status = AgentWorkspaceStatus.PAUSED))
        now = 200L
        val complete = store.upsert(workspace(workspaceId = "complete", status = AgentWorkspaceStatus.COMPLETED))

        assertEquals(listOf(complete, active), store.list())
        assertEquals(active, store.find(active.key))
        assertNull(store.find(active.key.copy(taskId = "different")))
        assertEquals(listOf(active), store.recoverable())
        assertTrue(store.delete(complete.workspaceId))
        assertFalse(store.delete(complete.workspaceId))
        assertNotNull(store.find(active.workspaceId))

        store.clear()
        assertTrue(store.list().isEmpty())
        assertEquals(AgentWorkspaceJsonCodec.emptyDocument(), store.serializedSnapshot())
    }

    private fun workspace(
        workspaceId: String = "workspace-1",
        status: AgentWorkspaceStatus = AgentWorkspaceStatus.CREATED,
        currentPlanSnapshot: String = "",
        eventSequence: Long = 0L,
        eventJournal: List<AgentWorkspaceEvent> = emptyList(),
        toolCalls: List<AgentToolCallRecord> = emptyList(),
        checkpoints: List<AgentWorkspaceCheckpoint> = emptyList(),
        artifacts: List<AgentArtifactReference> = emptyList(),
        createdAtMillis: Long = 0L,
        updatedAtMillis: Long = 0L,
        revision: Long = 0L
    ): AgentWorkspace = AgentWorkspace(
        workspaceId = workspaceId,
        sessionId = "session-1",
        conversationId = "conversation-1",
        taskId = "task-1",
        status = status,
        currentPlanSnapshot = currentPlanSnapshot,
        eventSequence = eventSequence,
        eventJournal = eventJournal,
        toolCalls = toolCalls,
        checkpoints = checkpoints,
        artifacts = artifacts,
        createdAtMillis = createdAtMillis,
        updatedAtMillis = updatedAtMillis,
        revision = revision
    )
}
