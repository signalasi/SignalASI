package com.signalasi.chat

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentControlPlaneTest {
    @Test
    fun protocolNegotiationSelectsHighestCompatibleVersionAndCommonFeatures() {
        val local = AgentProtocolRange(
            preferred = "1.3",
            minimum = "1.0",
            maximum = "1.4",
            features = setOf("run.cancel", "run.recover", "message.observe")
        )
        val remote = AgentProtocolRange(
            preferred = "1.2",
            minimum = "1.1",
            maximum = "1.2",
            features = setOf("run.cancel", "message.observe", "provider.list")
        )

        val agreement = AgentProtocolNegotiator.negotiate(local, remote)!!

        assertEquals("1.2", agreement.version)
        assertEquals(setOf("run.cancel", "message.observe"), agreement.features)
    }

    @Test
    fun incompatibleProtocolRangesAreRejected() {
        assertNull(
            AgentProtocolNegotiator.negotiate(
                AgentProtocolRange("1.0", "1.0", "1.1"),
                AgentProtocolRange("2.0", "2.0", "2.2")
            )
        )
    }

    @Test
    fun runStateReducerPreservesTerminalStateUntilExplicitRecovery() {
        val completed = AgentRunEventStore.reduce(
            AgentRunControlState.RUNNING,
            AgentRunControlEventType.RUN_COMPLETED
        )
        val ignoredLateProgress = AgentRunEventStore.reduce(completed, AgentRunControlEventType.TOOL_PROGRESS)
        val recovered = AgentRunEventStore.reduce(completed, AgentRunControlEventType.RUN_RECOVERED)

        assertEquals(AgentRunControlState.COMPLETED, ignoredLateProgress)
        assertEquals(AgentRunControlState.RUNNING, recovered)
    }

    @Test
    fun currentConnectorRegistryProjectsToUnifiedRegistrations() {
        val registrations = StaticAgentConnectorRegistry().registrations()
        val codex = registrations.first { it.agentId == "codex" }

        assertEquals(AgentConnectionKind.SIGNALASI_LINK, codex.connectionKind)
        assertEquals(AgentResourceTrust.VERIFIED_PAIRED, codex.trust)
        assertTrue(AgentCapability.CODE in codex.capabilities)
        assertTrue("message.observe" in codex.protocol.features)
        assertEquals(64, codex.capabilitiesHash.length)
    }

    @Test
    fun deliveryModesKeepResponseAndObservationSemanticsSeparate() {
        assertEquals(3, AgentDeliveryMode.entries.size)
        assertTrue(AgentDeliveryMode.RESPOND != AgentDeliveryMode.OBSERVE)
        assertTrue(AgentDeliveryMode.IGNORE != AgentDeliveryMode.RESPOND)
    }

    @Test
    fun structuredHandoffPreservesReturnPathAndCheckpoint() {
        val handoff = AgentHandoffRequest(
            conversationId = "conversation",
            taskId = "task",
            runId = "run",
            fromAgentId = "codex",
            toAgentId = "test-agent",
            returnToAgentId = "codex",
            reason = "Implementation is ready for verification",
            deliveryMode = AgentDeliveryMode.RESPOND,
            artifactIds = listOf("patch"),
            checkpoint = mapOf("sequence" to 12)
        )

        assertEquals("codex", handoff.returnToAgentId)
        assertEquals(listOf("patch"), handoff.artifactIds)
        assertEquals(12, handoff.checkpoint["sequence"])
    }

    @Test
    fun structuredHandoffIdentityIsStableAcrossProcessRecovery() {
        val first = AgentHandoffLifecycle.stableId("run", "compile", "codex", "tester")
        val replay = AgentHandoffLifecycle.stableId("run", "compile", "codex", "tester")
        val differentStep = AgentHandoffLifecycle.stableId("run", "verify", "codex", "tester")

        assertEquals(first, replay)
        assertTrue(first != differentStep)
    }

    @Test
    fun terminalHandoffCannotBeReopenedByLateEvents() {
        val returned = AgentHandoffLifecycle.transition(
            AgentHandoffState.ACTIVE,
            AgentHandoffState.RETURNED
        )
        val lateActive = AgentHandoffLifecycle.transition(returned, AgentHandoffState.ACTIVE)

        assertEquals(AgentHandoffState.RETURNED, lateActive)
        assertEquals(
            AgentHandoffState.FAILED,
            AgentHandoffLifecycle.transition(AgentHandoffState.ACTIVE, AgentHandoffState.FAILED)
        )
    }

    @Test
    fun observedContextExpiresAtItsConfiguredBoundary() {
        val item = AgentObservedContext(
            targetId = "codex",
            text = "Review this context later",
            createdAtMillis = 1_000L,
            expiresAtMillis = 2_000L
        )

        assertTrue(!item.isExpired(1_999L))
        assertTrue(item.isExpired(2_000L))
    }

    @Test
    fun transportAdapterNegotiatesFeaturesAndNeverDeliversIgnoreMessages() = runBlocking {
        val registration = testRegistration()
        val transport = FakeAgentTransport(
            registration = registration,
            remoteProtocol = registration.protocol.copy(features = setOf("run.cancel", "run.recover"))
        )
        val adapter = TransportBackedAgentAdapter(registration, transport)

        val agreement = adapter.connect()
        adapter.sendMessage("run", AgentControlMessage("message", "user", "ignored", deliveryMode = AgentDeliveryMode.IGNORE))

        assertEquals(setOf("run.cancel", "run.recover"), agreement.features)
        assertEquals(0, transport.sentMessages)
        val observationFailure = runCatching {
            adapter.startRun(
                AgentRunRequest(
                    conversationId = "conversation",
                    messageId = "message",
                    taskId = "task",
                    goal = "observe",
                    deliveryMode = AgentDeliveryMode.OBSERVE
                )
            )
        }.exceptionOrNull()
        assertTrue(observationFailure is IllegalArgumentException)
    }

    @Test
    fun ignoredAndRetriedRunsNeverDuplicateRemoteExecution() = runBlocking {
        val registration = testRegistration()
        val transport = FakeAgentTransport(registration, registration.protocol)
        val adapter = TransportBackedAgentAdapter(registration, transport)
        val ignored = AgentRunRequest(
            conversationId = "conversation",
            messageId = "message-ignore",
            taskId = "task-ignore",
            goal = "do not deliver",
            deliveryMode = AgentDeliveryMode.IGNORE,
            idempotencyKey = "ignore-key"
        )
        val executable = ignored.copy(
            messageId = "message-run",
            taskId = "task-run",
            runId = "run-1",
            goal = "run once",
            deliveryMode = AgentDeliveryMode.RESPOND,
            idempotencyKey = "run-key"
        )

        val ignoredHandle = adapter.startRun(ignored)
        val first = adapter.startRun(executable)
        val replay = adapter.startRun(executable.copy(runId = "run-2"))

        assertEquals("", ignoredHandle.remoteRunId)
        assertEquals(1, transport.startedRuns)
        assertEquals(first, replay)
    }

    @Test
    fun teamCoordinatorStartsOneResponderAndObservationMembers() = runBlocking {
        val primaryRegistration = testRegistration()
        val observerRegistration = testRegistration().copy(
            agentId = "tester",
            installationId = "tester-installation",
            displayName = "Tester",
            capabilities = setOf(AgentCapability.RESEARCH)
        )
        val primaryTransport = FakeAgentTransport(primaryRegistration, primaryRegistration.protocol)
        val observerTransport = FakeAgentTransport(observerRegistration, observerRegistration.protocol)
        val directory = AgentAdapterDirectory().apply {
            register(TransportBackedAgentAdapter(primaryRegistration, primaryTransport))
            register(TransportBackedAgentAdapter(observerRegistration, observerTransport))
        }
        val result = AgentTeamCoordinator(directory).start(
            AgentTeamDefinition(
                primaryAgentId = "codex",
                members = listOf(
                    AgentTeamMember("codex", AgentDeliveryMode.RESPOND, setOf(AgentCapability.CODE)),
                    AgentTeamMember("tester", AgentDeliveryMode.OBSERVE, setOf(AgentCapability.RESEARCH)),
                    AgentTeamMember("ignored", AgentDeliveryMode.IGNORE)
                ),
                visibilityMode = AgentTeamVisibilityMode.VISIBLE
            ),
            AgentRunRequest("conversation", "message", "task", goal = "Implement and review")
        )

        assertEquals(setOf("codex", "tester"), result.memberRuns.keys)
        assertEquals(1, primaryTransport.startedRuns)
        assertEquals(1, observerTransport.startedRuns)
        assertEquals(AgentTeamVisibilityMode.VISIBLE, result.visibilityMode)
    }

    @Test
    fun recoveryPolicyReconnectsOnlyDurableDesktopRuns() {
        val event = AgentRunControlEvent(
            conversationId = "conversation",
            messageId = "message",
            taskId = "task",
            runId = "run",
            agentId = "codex",
            deviceId = "desktop",
            type = AgentRunControlEventType.WAITING_FOR_DEVICE,
            sequence = 4L
        )
        val snapshot = AgentRunControlSnapshot(
            runId = "run",
            taskId = "task",
            state = AgentRunControlState.WAITING_FOR_DEVICE,
            agentId = "codex",
            deviceId = "desktop",
            lastSequence = 4L,
            lastEvent = event
        )
        val recorded = AgentRecordedRun(
            runId = "run",
            conversationId = "conversation",
            taskThreadId = "task",
            originalRequest = "Continue the task"
        )

        assertEquals(
            AgentRunRecoveryDisposition.RECONNECT_DURABLE_REMOTE,
            AgentRunRecoveryPolicy.decide(snapshot, recorded, testRegistration()).disposition
        )
        assertEquals(
            AgentRunRecoveryDisposition.FAIL_NON_REPLAYABLE,
            AgentRunRecoveryPolicy.decide(
                snapshot,
                recorded,
                testRegistration().copy(location = AgentResourceLocation.CLOUD, connectionKind = AgentConnectionKind.HTTP)
            ).disposition
        )
    }

    private fun testRegistration(): AgentRegistration = AgentRegistration(
        agentId = "codex",
        installationId = "installation",
        deviceId = "desktop",
        providerId = "desktop-provider",
        displayName = "Codex",
        kind = AgentConnectorKind.AGENT,
        location = AgentResourceLocation.TRUSTED_DESKTOP,
        status = AgentEndpointStatus.ONLINE,
        capabilities = setOf(AgentCapability.CODE),
        protocol = AgentProtocolRange(
            preferred = "1.1",
            minimum = "1.0",
            maximum = "1.1",
            features = setOf("run.cancel", "run.recover", "message.observe")
        ),
        connectionKind = AgentConnectionKind.SIGNALASI_LINK,
        trust = AgentResourceTrust.VERIFIED_PAIRED
    )
}

private class FakeAgentTransport(
    private val registration: AgentRegistration,
    private val remoteProtocol: AgentProtocolRange
) : AgentAdapterTransport {
    var sentMessages: Int = 0
        private set
    var startedRuns: Int = 0
        private set

    override suspend fun open(): AgentProtocolRange = remoteProtocol
    override suspend fun close() = Unit
    override suspend fun status(): AgentRegistration = registration
    override suspend fun startRun(request: AgentRunRequest): AgentRunHandle {
        startedRuns += 1
        return AgentRunHandle(
            runId = request.runId,
            taskId = request.taskId,
            agentId = registration.agentId
        )
    }

    override suspend fun sendMessage(runId: String, message: AgentControlMessage) {
        sentMessages += 1
    }

    override suspend fun cancelRun(runId: String) = Unit
    override fun observeEvents(runId: String): Flow<AgentRunControlEvent> = emptyFlow()
    override suspend fun recoverRuns(): List<AgentRecoverableRun> = emptyList()
}
