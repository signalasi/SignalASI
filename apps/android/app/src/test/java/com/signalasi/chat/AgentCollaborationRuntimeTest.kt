package com.signalasi.chat

import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentCollaborationRuntimeTest {
    @Test
    fun backgroundTeamRunsObserversInParallelAndPublishesOnlyPrimaryOutput() = runBlocking {
        val store = InMemoryAgentTeamExecutionStore()
        val runtime = AgentTeamExecutionRuntime(
            store,
            AgentSubagentLimits(maxChildren = 8, maxConcurrency = 3)
        )
        val activeObservers = AtomicInteger()
        val maximumActiveObservers = AtomicInteger()
        val executed = CopyOnWriteArrayList<String>()
        val definition = teamDefinition()
        val request = request()

        val result = runtime.start(definition, request) { context ->
            executed += context.member.agentId
            if (context.member.deliveryMode == AgentDeliveryMode.OBSERVE) {
                val active = activeObservers.incrementAndGet()
                maximumActiveObservers.updateAndGet { maxOf(it, active) }
                delay(60L)
                activeObservers.decrementAndGet()
                AgentSubagentOutput("${context.member.agentId}-evidence")
            } else {
                assertEquals(2, context.handoff.dependencies.size)
                assertTrue(context.handoff.dependencies.all { it.status == AgentSubagentStatus.SUCCEEDED })
                AgentSubagentOutput(
                    "final:${context.handoff.dependencies.joinToString("|") { it.output }}"
                )
            }
        }.await()
        runtime.close()

        assertEquals(AgentTeamExecutionState.SUCCEEDED, result.snapshot.state)
        assertEquals("final:researcher-evidence|tester-evidence", result.finalOutput)
        assertEquals(2, maximumActiveObservers.get())
        assertEquals(setOf("researcher", "tester", "primary"), executed.toSet())
        assertFalse("ignored" in executed)
        assertTrue(AgentTeamProgressPolicy.project(result.snapshot, expanded = false).members.isEmpty())
        assertEquals(4, AgentTeamProgressPolicy.project(result.snapshot, expanded = true).members.size)
    }

    @Test
    fun observerFailureIsIsolatedAndPrimaryStillProducesTheSingleReply() = runBlocking {
        val store = InMemoryAgentTeamExecutionStore()
        val runtime = AgentTeamExecutionRuntime(store)

        val result = runtime.start(teamDefinition(), request()) { context ->
            when (context.member.agentId) {
                "researcher" -> error("Research service unavailable")
                "tester" -> AgentSubagentOutput("verified evidence")
                "primary" -> {
                    assertEquals(AgentSubagentStatus.FAILED, context.handoff.dependencies.first {
                        it.childId == "researcher"
                    }.status)
                    assertEquals("Research service unavailable", context.handoff.dependencies.first {
                        it.childId == "researcher"
                    }.errorMessage)
                    AgentSubagentOutput("final answer from remaining evidence")
                }
                else -> error("Ignored member must not execute")
            }
        }.await()
        runtime.close()

        assertEquals(AgentTeamExecutionState.COMPLETED_WITH_FAILURES, result.snapshot.state)
        assertEquals("final answer from remaining evidence", result.finalOutput)
        assertEquals(
            AgentSubagentStatus.SUCCEEDED,
            result.snapshot.members.first { it.agentId == "primary" }.status
        )
    }

    @Test
    fun memberScopedKnowledgeOverridesTheSupervisorDefault() = runBlocking {
        val runtime = AgentTeamExecutionRuntime(InMemoryAgentTeamExecutionStore())
        val definition = AgentTeamDefinition(
            teamId = "scoped-team",
            primaryAgentId = "primary",
            members = listOf(
                AgentTeamMember(
                    "primary",
                    AgentDeliveryMode.RESPOND,
                    context = mapOf("_signalasi_agent_knowledge_context" to "lead-only")
                ),
                AgentTeamMember(
                    "researcher",
                    AgentDeliveryMode.OBSERVE,
                    context = mapOf("_signalasi_agent_knowledge_context" to "research-only")
                )
            )
        )
        val observed = linkedMapOf<String, String>()

        runtime.start(
            definition,
            request().copy(context = mapOf("_signalasi_agent_knowledge_context" to "shared-default"))
        ) { context ->
            observed[context.member.agentId] = context.request.context[
                "_signalasi_agent_knowledge_context"
            ].toString()
            AgentSubagentOutput(if (context.member.agentId == "primary") "final" else "evidence")
        }.await()
        runtime.close()

        assertEquals("lead-only", observed["primary"])
        assertEquals("research-only", observed["researcher"])
    }

    @Test
    fun teamRejectsMultipleRespondersAndUnknownDependencies() {
        val store = InMemoryAgentTeamExecutionStore()
        val runtime = AgentTeamExecutionRuntime(store)
        val request = request()
        val multipleResponders = teamDefinition().copy(
            members = teamDefinition().members.map {
                if (it.agentId == "tester") it.copy(deliveryMode = AgentDeliveryMode.RESPOND) else it
            }
        )
        val unknownDependency = teamDefinition().copy(
            members = teamDefinition().members.map {
                if (it.agentId == "tester") it.copy(dependsOnAgentIds = setOf("missing")) else it
            }
        )
        val cyclicDependency = teamDefinition().copy(
            members = teamDefinition().members.map {
                if (it.agentId == "researcher") it.copy(dependsOnAgentIds = setOf("primary")) else it
            }
        )

        assertTrue(runCatching {
            runtime.start(multipleResponders, request) { AgentSubagentOutput() }
        }.exceptionOrNull() is IllegalArgumentException)
        assertTrue(runCatching {
            runtime.start(unknownDependency, request.copy(runId = "another-run")) { AgentSubagentOutput() }
        }.exceptionOrNull() is IllegalArgumentException)
        assertTrue(runCatching {
            runtime.start(cyclicDependency, request.copy(runId = "cyclic-run")) { AgentSubagentOutput() }
        }.exceptionOrNull() is IllegalArgumentException)
        assertTrue(store.snapshots().isEmpty())
        runtime.close()
    }

    @Test
    fun nonterminalDurableTeamIsMarkedInterruptedWithoutSilentReplay() = runBlocking {
        val store = InMemoryAgentTeamExecutionStore()
        val definition = teamDefinition()
        val request = request()
        store.create(definition, request)
        store.append(AgentSubagentEvent(
            sequence = 1L,
            supervisorId = request.runId,
            kind = AgentSubagentEventKinds.SUPERVISOR_STARTED,
            timestampMillis = 1_000L
        ))
        store.append(AgentSubagentEvent(
            sequence = 2L,
            supervisorId = request.runId,
            childId = "researcher",
            kind = AgentSubagentEventKinds.CHILD_RUNNING,
            childStatus = AgentSubagentStatus.RUNNING,
            timestampMillis = 1_100L
        ))

        val interrupted = store.markNonTerminalInterrupted(2_000L)
        store.create(definition, request)

        assertEquals(1, interrupted.size)
        assertEquals(AgentTeamExecutionState.INTERRUPTED, interrupted.single().state)
        assertEquals(2, store.records().single().events.size)
        assertEquals(2_000L, store.snapshot(request.runId)?.interruptedAtMillis)
    }

    @Test
    fun oneTeamCanBeMarkedInterruptedWithoutMutatingAnotherActiveTeam() = runBlocking {
        val store = InMemoryAgentTeamExecutionStore()
        val first = request().copy(runId = "first-run")
        val second = request().copy(runId = "second-run")
        store.create(teamDefinition(), first)
        store.create(teamDefinition().copy(teamId = "second-team"), second)
        listOf(first, second).forEach { request ->
            store.append(AgentSubagentEvent(
                sequence = 1L,
                supervisorId = request.runId,
                kind = AgentSubagentEventKinds.SUPERVISOR_STARTED,
                timestampMillis = 1_000L
            ))
        }

        val interrupted = store.markInterrupted(first.runId, 2_000L)

        assertEquals(AgentTeamExecutionState.INTERRUPTED, interrupted?.state)
        assertEquals(AgentTeamExecutionState.RUNNING, store.snapshot(second.runId)?.state)
        assertEquals(0L, store.snapshot(second.runId)?.interruptedAtMillis)
        assertNull(store.markInterrupted("missing-run", 2_000L))
    }

    @Test
    fun lateManagedResponsesCompleteInterruptedTeamExactlyOnce() = runBlocking {
        val store = InMemoryAgentTeamExecutionStore()
        val definition = AgentTeamDefinition(
            teamId = "late-team",
            primaryAgentId = "primary",
            members = listOf(
                AgentTeamMember("primary", AgentDeliveryMode.RESPOND, role = "writer"),
                AgentTeamMember("observer", AgentDeliveryMode.OBSERVE, role = "reviewer")
            )
        )
        val request = request()
        store.create(definition, request)
        store.append(AgentSubagentEvent(
            sequence = 1L,
            supervisorId = request.runId,
            kind = AgentSubagentEventKinds.SUPERVISOR_STARTED,
            timestampMillis = 1_000L
        ))
        store.append(AgentSubagentEvent(
            sequence = 2L,
            supervisorId = request.runId,
            childId = "observer",
            kind = AgentSubagentEventKinds.CHILD_RUNNING,
            childStatus = AgentSubagentStatus.RUNNING,
            timestampMillis = 1_100L
        ))
        store.append(AgentSubagentEvent(
            sequence = 3L,
            supervisorId = request.runId,
            childId = "primary",
            kind = AgentSubagentEventKinds.CHILD_RUNNING,
            childStatus = AgentSubagentStatus.RUNNING,
            timestampMillis = 1_200L
        ))
        store.markNonTerminalInterrupted(1_300L)

        val observer = managedResponse(
            ownerRunId = "observer-run",
            agentId = "observer",
            sourceMessageId = 71L,
            content = "verified evidence"
        )
        val primary = managedResponse(
            ownerRunId = "primary-run",
            agentId = "primary",
            sourceMessageId = 72L,
            content = "final reviewed answer"
        )

        assertTrue(store.applyLateResponse(observer))
        assertEquals(AgentTeamExecutionState.INTERRUPTED, store.snapshot(request.runId)?.state)
        assertTrue(store.applyLateResponse(primary))
        val completed = requireNotNull(store.snapshot(request.runId))
        val eventCount = store.records().single().events.size
        assertEquals(AgentTeamExecutionState.SUCCEEDED, completed.state)
        assertEquals("final reviewed answer", completed.finalOutput)
        assertEquals(AgentSubagentStatus.SUCCEEDED, completed.members.first {
            it.agentId == "observer"
        }.status)

        assertTrue(store.applyLateResponse(primary))
        assertEquals(eventCount, store.records().single().events.size)
        assertEquals(AgentTeamExecutionState.SUCCEEDED, store.snapshot(request.runId)?.state)
    }

    @Test
    fun managedResponseLedgerCorrelatesAndReleasesLateReply() {
        val ledger = InMemoryAgentManagedResponseLedger()
        val record = AgentManagedResponseRecord(
            ownerRunId = "child-run",
            supervisorRunId = "supervisor-run",
            agentId = "primary",
            deliveryMode = AgentDeliveryMode.RESPOND,
            sourceMessageId = 91L,
            contactId = "primary",
            createdAtMillis = 1_000L
        )
        ledger.register(record)

        assertNull(ledger.complete(AgentConnectorResponse(92L, "primary", "wrong source")))
        val completed = ledger.complete(AgentConnectorResponse(
            sourceMessageId = 91L,
            contactId = "primary",
            content = "late answer",
            receivedAtMillis = 2_000L
        ))

        assertEquals("child-run", completed?.ownerRunId)
        assertEquals("late answer", ledger.completedUnapplied().single().response?.content)
        ledger.markApplied("child-run")
        assertTrue(ledger.completedUnapplied().isEmpty())
        assertEquals(
            AgentManagedResponseState.APPLIED,
            ledger.complete(AgentConnectorResponse(91L, "primary", "duplicate"))?.state
        )
        ledger.removeOwner("child-run")
        assertTrue(ledger.completedUnapplied().isEmpty())
    }

    @Test
    fun adapterWorkerUsesStableChildRunsAndStructuredDependencyContext() = runBlocking {
        val primary = EventAgentAdapter("primary", setOf(AgentCapability.CODE))
        val observer = EventAgentAdapter("observer", setOf(AgentCapability.RESEARCH))
        val directory = AgentAdapterDirectory().apply {
            register(primary)
            register(observer)
        }
        val store = InMemoryAgentTeamExecutionStore()
        val runtime = AgentTeamExecutionRuntime(store)
        val definition = AgentTeamDefinition(
            teamId = "adapter-team",
            primaryAgentId = "primary",
            members = listOf(
                AgentTeamMember("primary", AgentDeliveryMode.RESPOND, setOf(AgentCapability.CODE)),
                AgentTeamMember("observer", AgentDeliveryMode.OBSERVE, setOf(AgentCapability.RESEARCH))
            )
        )

        val result = runtime.start(
            definition,
            request().copy(requiredCapabilities = setOf(AgentCapability.CODE)),
            AgentAdapterTeamMemberWorker(directory, timeoutMillis = 5_000L)
        ).await()
        runtime.close()

        assertEquals("primary-result", result.finalOutput)
        assertEquals(setOf(AgentCapability.RESEARCH), observer.requests.single().requiredCapabilities)
        assertEquals(setOf(AgentCapability.CODE), primary.requests.single().requiredCapabilities)
        assertNotEquals(primary.requests.single().runId, observer.requests.single().runId)
        val dependencies = primary.requests.single().context["team_dependencies"] as List<*>
        assertEquals(1, dependencies.size)
    }

    @Test
    fun productionActionBridgeExecutesObserversInternallyBeforePrimaryResponse() = runBlocking {
        AgentManagedConnectorResponseRegistry.clear()
        val actions = CopyOnWriteArrayList<AgentAction>()
        val managedResponses = InMemoryAgentManagedResponseLedger()
        val registrations = listOf(
            registration("primary", AgentCapability.CODE),
            registration("observer", AgentCapability.RESEARCH)
        )
        val provider = ActionExecutorAgentProvider(
            registrationSource = { registrations },
            managedResponses = managedResponses,
            delegate = object : AgentActionExecutor {
                override fun execute(action: AgentAction, screen: ScreenContext): AgentActionResult {
                    actions += action
                    val connectorId = action.parameters["connector_id"].orEmpty()
                    val sourceMessageId = if (connectorId == "observer") 81L else 82L
                    thread(name = "managed-response-$connectorId") {
                        val response = AgentConnectorResponse(
                            sourceMessageId = sourceMessageId,
                            contactId = connectorId,
                            content = if (connectorId == "observer") "verified evidence" else "reviewed final answer"
                        )
                        repeat(100) {
                            if (AgentManagedConnectorResponseRegistry.consume(response)) return@thread
                            Thread.sleep(5L)
                        }
                    }
                    return AgentActionResult(
                        action.id,
                        true,
                        "Waiting",
                        mapOf(
                            "awaiting_response" to "true",
                            "source_message_id" to sourceMessageId.toString(),
                            "contact_id" to connectorId
                        )
                    )
                }
            }
        )
        val directory = AgentAdapterDirectory().apply { register(provider) }
        val runtime = AgentTeamExecutionRuntime(InMemoryAgentTeamExecutionStore())
        val worker = ActionExecutorAgentTeamMemberWorker(
            provider = provider,
            directory = directory,
            screenProvider = { ScreenContext(foregroundApp = "SignalASI", pageTitle = "Agent") },
            timeoutMillis = 5_000L
        )
        val definition = AgentTeamDefinition(
            teamId = "production-team",
            primaryAgentId = "primary",
            members = listOf(
                AgentTeamMember("primary", AgentDeliveryMode.RESPOND, setOf(AgentCapability.CODE)),
                AgentTeamMember("observer", AgentDeliveryMode.OBSERVE, setOf(AgentCapability.RESEARCH))
            )
        )

        val result = runtime.start(definition, request(), worker).await()
        runtime.close()

        assertEquals("reviewed final answer", result.finalOutput)
        assertEquals(2, actions.size)
        val observerAction = actions.first { it.parameters["connector_id"] == "observer" }
        val primaryAction = actions.first { it.parameters["connector_id"] == "primary" }
        assertEquals("respond", observerAction.parameters["delivery_mode"])
        assertTrue(primaryAction.parameters["prompt"].orEmpty().contains("verified evidence"))
        assertFalse(AgentManagedConnectorResponseRegistry.consume(
            AgentConnectorResponse(82L, "primary", "duplicate")
        ))
        val duplicate = managedResponses.complete(AgentConnectorResponse(82L, "primary", "duplicate"))
        assertNotNull(duplicate)
        assertEquals(AgentManagedResponseState.APPLIED, duplicate?.state)
        AgentManagedConnectorResponseRegistry.clear()
    }

    private fun managedResponse(
        ownerRunId: String,
        agentId: String,
        sourceMessageId: Long,
        content: String
    ) = AgentManagedResponseRecord(
        ownerRunId = ownerRunId,
        supervisorRunId = "supervisor-run",
        agentId = agentId,
        deliveryMode = if (agentId == "primary") AgentDeliveryMode.RESPOND else AgentDeliveryMode.OBSERVE,
        sourceMessageId = sourceMessageId,
        contactId = agentId,
        state = AgentManagedResponseState.COMPLETED,
        response = AgentConnectorResponse(
            sourceMessageId = sourceMessageId,
            contactId = agentId,
            content = content,
            receivedAtMillis = 2_000L + sourceMessageId
        ),
        createdAtMillis = 1_000L,
        completedAtMillis = 2_000L + sourceMessageId
    )

    private fun teamDefinition() = AgentTeamDefinition(
        teamId = "team",
        primaryAgentId = "primary",
        members = listOf(
            AgentTeamMember("primary", AgentDeliveryMode.RESPOND, role = "architect"),
            AgentTeamMember("researcher", AgentDeliveryMode.OBSERVE, role = "research"),
            AgentTeamMember("tester", AgentDeliveryMode.OBSERVE, role = "verification"),
            AgentTeamMember("ignored", AgentDeliveryMode.IGNORE, role = "unused")
        ),
        visibilityMode = AgentTeamVisibilityMode.BACKGROUND
    )

    private fun request() = AgentRunRequest(
        conversationId = "conversation",
        messageId = "message",
        taskId = "task",
        runId = "supervisor-run",
        goal = "Produce one reviewed answer",
        idempotencyKey = "team-task"
    )

    private fun registration(agentId: String, capability: AgentCapability) = AgentRegistration(
        agentId = agentId,
        installationId = "$agentId-installation",
        deviceId = "desktop-device",
        providerId = "signalasi-connectors",
        displayName = agentId,
        kind = AgentConnectorKind.AGENT,
        location = AgentResourceLocation.TRUSTED_DESKTOP,
        status = AgentEndpointStatus.ONLINE,
        capabilities = setOf(capability),
        protocol = AgentProtocolRange(
            preferred = "1.0",
            minimum = "1.0",
            maximum = "1.0",
            features = setOf("run.cancel", "run.recover", "run.events", "message.respond", "message.observe")
        ),
        connectionKind = AgentConnectionKind.SIGNALASI_LINK,
        trust = AgentResourceTrust.VERIFIED_PAIRED,
        adapterType = agentId
    )
}

private class EventAgentAdapter(
    agentId: String,
    capabilities: Set<AgentCapability>
) : AgentAdapter {
    override val registration = AgentRegistration(
        agentId = agentId,
        installationId = "$agentId-installation",
        deviceId = "device",
        providerId = "test",
        displayName = agentId,
        kind = AgentConnectorKind.AGENT,
        location = AgentResourceLocation.PHONE,
        status = AgentEndpointStatus.ONLINE,
        capabilities = capabilities,
        protocol = AgentProtocolRange("1.0", "1.0", "1.0", setOf("run.events")),
        connectionKind = AgentConnectionKind.IN_PROCESS,
        trust = AgentResourceTrust.PHONE_SYSTEM
    )
    val requests = CopyOnWriteArrayList<AgentRunRequest>()
    private val events = mutableMapOf<String, MutableSharedFlow<AgentRunControlEvent>>()

    override suspend fun connect() = AgentProtocolAgreement("1.0", setOf("run.events"))
    override suspend fun disconnect() = Unit
    override suspend fun status() = registration
    override suspend fun startRun(request: AgentRunRequest): AgentRunHandle {
        requests += request
        events.getOrPut(request.runId) { MutableSharedFlow(extraBufferCapacity = 4) }.emit(
            AgentRunControlEvent(
                conversationId = request.conversationId,
                messageId = request.messageId,
                taskId = request.taskId,
                runId = request.runId,
                agentId = registration.agentId,
                deviceId = registration.deviceId,
                type = AgentRunControlEventType.RUN_COMPLETED,
                sequence = 1L,
                payload = mapOf("result" to "${registration.agentId}-result")
            )
        )
        return AgentRunHandle(request.runId, request.taskId, registration.agentId)
    }
    override suspend fun sendMessage(runId: String, message: AgentControlMessage) = Unit
    override suspend fun cancelRun(runId: String) = Unit
    override fun observeEvents(runId: String): Flow<AgentRunControlEvent> =
        events.getOrPut(runId) { MutableSharedFlow(extraBufferCapacity = 4) }
    override suspend fun recoverRuns(): List<AgentRecoverableRun> = emptyList()
}
