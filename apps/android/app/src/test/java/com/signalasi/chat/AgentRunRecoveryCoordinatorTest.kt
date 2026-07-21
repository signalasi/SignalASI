package com.signalasi.chat

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentRunRecoveryCoordinatorTest {
    @Test
    fun processRecreationReconnectsRemoteCursorCheckpointAndToolState() = runBlocking {
        val workspaceStore = InMemoryAgentWorkspaceStore(clock = { 2_000L })
        workspaceStore.upsert(AgentWorkspace(
            workspaceId = "turn-1",
            sessionId = "session-1",
            conversationId = "conversation-1",
            taskId = "turn-1",
            goal = "Continue a durable Codex task",
            agentId = "codex",
            deviceId = "desktop-1",
            remoteRunId = "remote-1",
            status = AgentWorkspaceStatus.WAITING_RESPONSE,
            permissionScopes = listOf("filesystem.project.write"),
            toolCalls = listOf(AgentToolCallRecord(
                id = "shell-1",
                toolName = "shell",
                status = AgentToolCallStatus.RUNNING,
                argumentsJson = "{\"cmd\":\"gradle test\"}",
                startedAtMillis = 1_000L
            )),
            checkpoints = listOf(AgentWorkspaceCheckpoint(
                id = "before-death",
                stateJson = "{\"cursor\":7,\"permission_wait\":true}",
                createdAtMillis = 1_500L
            )),
            lastRemoteEventSequence = 7L
        ))
        val eventStore = RecoveryRunControlStore(
            event = runEvent(AgentRunControlEventType.WAITING_FOR_DEVICE, sequence = 8L)
        )
        val registration = durableRegistration()
        val remoteHandle = AgentRunHandle(
            runId = "run-1",
            taskId = "turn-1",
            agentId = "codex",
            remoteRunId = "remote-1"
        )
        val adapter = RecoveryAgentAdapter(
            registration,
            listOf(AgentRecoverableRun(
                handle = remoteHandle,
                lastEventSequence = 22L,
                checkpoint = mapOf(
                    "cursor" to 22,
                    "permission_wait" to true,
                    "active_tool_call_id" to "shell-1"
                )
            ))
        )
        val results = AgentRunRecoveryCoordinator(
            runStore = eventStore,
            workspaceStore = workspaceStore,
            recordedRun = { runningRecordedRun() },
            registration = { _, _ -> registration },
            adapterResolver = { adapter }
        ).recover()

        val restoredStore = InMemoryAgentWorkspaceStore(
            AgentWorkspaceJsonCodec.decodeList(workspaceStore.serializedSnapshot()),
            clock = { 3_000L }
        )
        val restored = restoredStore.find("turn-1")!!
        assertEquals(AgentRunRecoveryOutcome.RECONNECTED_REMOTE, results.single().outcome)
        assertEquals(AgentWorkspaceStatus.RUNNING, restored.status)
        assertEquals(22L, restored.lastRemoteEventSequence)
        assertEquals("remote-1", restored.remoteRunId)
        assertEquals(AgentToolCallStatus.RUNNING, restored.toolCalls.single().status)
        assertTrue(restored.checkpoints.last().stateJson.contains("active_tool_call_id"))
        assertEquals(AgentRunControlEventType.RUN_RECOVERED, eventStore.appended.single().type)
    }

    @Test
    fun unavailableRemoteIsKeptRecoverableInsteadOfBeingReplayedOrFailed() = runBlocking {
        val workspaceStore = InMemoryAgentWorkspaceStore(clock = { 2_000L })
        workspaceStore.upsert(AgentWorkspace(
            workspaceId = "turn-1",
            sessionId = "session-1",
            conversationId = "conversation-1",
            taskId = "turn-1",
            goal = "Wait for the trusted desktop",
            agentId = "codex",
            status = AgentWorkspaceStatus.RUNNING
        ))
        val eventStore = RecoveryRunControlStore(runEvent(AgentRunControlEventType.TOOL_PROGRESS, 5L))
        val registration = durableRegistration()

        val result = AgentRunRecoveryCoordinator(
            eventStore,
            workspaceStore,
            recordedRun = { runningRecordedRun() },
            registration = { _, _ -> registration },
            adapterResolver = { null }
        ).recover().single()

        assertEquals(AgentRunRecoveryOutcome.WAITING_FOR_REMOTE, result.outcome)
        assertEquals(AgentWorkspaceStatus.WAITING_RESPONSE, workspaceStore.find("turn-1")?.status)
        assertEquals(AgentRunControlEventType.WAITING_FOR_DEVICE, eventStore.appended.single().type)
    }

    @Test
    fun localPermissionWaitRemainsWaitingAcrossRepeatedStartupRecovery() = runBlocking {
        val workspaceStore = InMemoryAgentWorkspaceStore(clock = { 2_000L })
        workspaceStore.upsert(AgentWorkspace(
            workspaceId = "turn-1",
            sessionId = "session-1",
            conversationId = "conversation-1",
            taskId = "turn-1",
            goal = "Wait for user confirmation",
            status = AgentWorkspaceStatus.WAITING_CONFIRMATION,
            permissionScopes = listOf("contacts.write")
        ))
        val eventStore = RecoveryRunControlStore(runEvent(AgentRunControlEventType.WAITING_FOR_USER, 4L))
        val phoneRegistration = durableRegistration().copy(
            location = AgentResourceLocation.PHONE,
            connectionKind = AgentConnectionKind.IN_PROCESS
        )
        val coordinator = AgentRunRecoveryCoordinator(
            eventStore,
            workspaceStore,
            recordedRun = { runningRecordedRun() },
            registration = { _, _ -> phoneRegistration },
            adapterResolver = { error("A local wait must not reconnect or replay an Agent") }
        )

        val first = coordinator.recover().single()
        val second = coordinator.recover().single()

        assertEquals(AgentRunRecoveryOutcome.RESTORED_LOCAL_WAIT, first.outcome)
        assertEquals(AgentRunRecoveryOutcome.RESTORED_LOCAL_WAIT, second.outcome)
        assertEquals(AgentRunControlEventType.WAITING_FOR_USER, eventStore.appended.single().type)
        assertEquals(AgentWorkspaceStatus.WAITING_CONFIRMATION, workspaceStore.find("turn-1")?.status)
    }

    private fun runningRecordedRun() = AgentRecordedRun(
        runId = "run-1",
        conversationId = "conversation-1",
        taskThreadId = "turn-1",
        originalRequest = "Continue the task"
    )

    private fun durableRegistration() = AgentRegistration(
        agentId = "codex",
        installationId = "installation-1",
        deviceId = "desktop-1",
        providerId = "desktop-provider",
        displayName = "Codex",
        kind = AgentConnectorKind.AGENT,
        location = AgentResourceLocation.TRUSTED_DESKTOP,
        status = AgentEndpointStatus.BUSY,
        capabilities = setOf(AgentCapability.CODE),
        protocol = AgentProtocolRange(
            preferred = "1.0",
            minimum = "1.0",
            maximum = "1.0",
            features = setOf("run.recover")
        ),
        connectionKind = AgentConnectionKind.SIGNALASI_LINK,
        trust = AgentResourceTrust.VERIFIED_PAIRED
    )

    private fun runEvent(type: AgentRunControlEventType, sequence: Long) = AgentRunControlEvent(
        conversationId = "conversation-1",
        messageId = "message-1",
        taskId = "turn-1",
        runId = "run-1",
        agentId = "codex",
        deviceId = "desktop-1",
        type = type,
        sequence = sequence
    )
}

private class RecoveryRunControlStore(
    private val event: AgentRunControlEvent
) : AgentRunControlStore {
    val appended = mutableListOf<AgentRunControlEvent>()

    override fun appendNext(event: AgentRunControlEvent): AgentRunControlEvent {
        val next = event.copy(sequence = this.event.sequence + appended.size + 1L)
        appended += next
        return next
    }

    override fun recoverableRuns(): List<AgentRunControlSnapshot> = listOf(
        (appended.lastOrNull() ?: event).let { latest ->
            AgentRunControlSnapshot(
                runId = latest.runId,
                taskId = latest.taskId,
                state = AgentRunEventStore.reduce(AgentRunControlState.RUNNING, latest.type),
                agentId = latest.agentId,
                deviceId = latest.deviceId,
                lastSequence = latest.sequence,
                lastEvent = latest
            )
        }
    )
}

private class RecoveryAgentAdapter(
    override val registration: AgentRegistration,
    private val recoverable: List<AgentRecoverableRun>
) : AgentAdapter {
    override suspend fun connect(): AgentProtocolAgreement = AgentProtocolAgreement("1.0", setOf("run.recover"))
    override suspend fun disconnect() = Unit
    override suspend fun status(): AgentRegistration = registration
    override suspend fun startRun(request: AgentRunRequest): AgentRunHandle = error("Recovery must not replay Run start")
    override suspend fun sendMessage(runId: String, message: AgentControlMessage) = Unit
    override suspend fun cancelRun(runId: String) = Unit
    override fun observeEvents(runId: String): Flow<AgentRunControlEvent> = emptyFlow()
    override suspend fun recoverRuns(): List<AgentRecoverableRun> = recoverable
}
