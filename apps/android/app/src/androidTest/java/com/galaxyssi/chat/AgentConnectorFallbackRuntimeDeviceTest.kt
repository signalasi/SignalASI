package com.galaxyssi.chat

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Real MobileNativeAgent lifecycle with an injected provider, never a live user request. */
@RunWith(AndroidJUnit4::class)
class AgentConnectorFallbackRuntimeDeviceTest {
    @Test fun immediateSuccessIsObservedAndCompleted() {
        val state = exercise(success = true, awaiting = false)
        assertEquals(AgentPhase.COMPLETED, state.phase)
        assertEquals("test-success", state.lastActionResult?.message)
        assertEquals(AgentActionStatus.COMPLETED, state.plan?.actions?.single()?.status)
    }

    @Test fun asynchronousDispatchIsPersistedOnTheSelectedAgent() {
        val state = exercise(success = true, awaiting = true)
        assertEquals(AgentPhase.WAITING_RESPONSE, state.phase)
        assertEquals("test-codex", state.plan?.actions?.single()?.parameters?.get("connector_id"))
        assertEquals("902", state.lastActionResult?.metadata?.get("source_message_id"))
        assertEquals("Codex test", state.plan?.selectedAgentOrModel)
    }

    @Test fun immediateFailureRetainsTheActualFallbackError() {
        val state = exercise(success = false, awaiting = false)
        assertEquals(AgentPhase.FAILED, state.phase)
        assertEquals("test-permanent-error", state.lastActionResult?.message)
    }

    @Test fun structuredCloudFailureReachesTheRealFallbackRuntime() {
        val state = exercise(success = true, awaiting = false, structuredCloudFailure = true)
        assertEquals(AgentPhase.COMPLETED, state.phase)
    }

    @Test fun cancellingRealRuntimeCancelsOnlyItsCloudDispatch() {
        assertEquals(AgentPhase.CANCELLED, exercise(true, true, cancelCloud = true).phase)
    }

    @Test fun recoveredCancellationStopsWithoutAnotherProviderCall() {
        val state = exercise(true, true, terminalStatus = "cancelled")
        assertEquals(AgentPhase.CANCELLED, state.phase)
        assertEquals("actual remote outcome", state.lastActionResult?.message)
    }

    @Test fun manuallyLockedTerminalFailureRetainsItsActualCause() {
        val state = exercise(true, true, terminalStatus = "failed")
        assertEquals(AgentPhase.FAILED, state.phase)
        assertEquals("actual remote outcome", state.lastActionResult?.message)
    }

    @Test fun canonicalFailureSurvivesLaterStatusRevision() {
        val state = exercise(true, true, terminalStatus = "timed_out", observedGeneration = 2, observedSequence = 100)
        assertEquals(AgentPhase.FAILED, state.phase)
        assertEquals("100", state.lastActionResult?.metadata?.get("remote_task_status_seq"))
        assertEquals("actual remote outcome", state.lastActionResult?.message)
    }

    @Test fun cancellationFromOlderExecutionCannotStopCurrentRetry() {
        val state = exercise(true, true, terminalStatus = "cancelled", observedGeneration = 3)
        assertEquals(AgentPhase.WAITING_RESPONSE, state.phase)
        assertEquals("3", state.lastActionResult?.metadata?.get("remote_execution_generation"))
    }

    @Test fun autoTerminalFailureContinuesThroughExistingFallbackLifecycle() {
        val state = exercise(true, true, terminalStatus = "failed", allowTerminalFallback = true)
        assertEquals(AgentPhase.WAITING_RESPONSE, state.phase)
        assertEquals("test-hermes", state.lastActionResult?.metadata?.get("contact_id"))
    }

    private fun exercise(success: Boolean, awaiting: Boolean, structuredCloudFailure: Boolean = false,
        cancelCloud: Boolean = false, terminalStatus: String = "", observedGeneration: Long = 1,
        observedSequence: Long = -1, allowTerminalFallback: Boolean = false): AgentUiState {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val screen = ScreenContext(foregroundApp = "GalaxySSI", pageTitle = "Agent")
        val session = InMemoryAgentSessionStore()
        val records = linkedMapOf<String, AgentTaskRecord>()
        val target = AgentCallableTarget("test-codex", "Codex test", AgentConnectorKind.AGENT,
            AgentConnectorStatus.AVAILABLE, listOf(AgentCapability.CHAT), "test-desktop", adapterType = "desktop-agent")
        var dispatches = 0
        val agent = MobileNativeAgent(
            context,
            perceptionProvider = object : ScreenPerceptionProvider {
                override fun capture() = screen
                override fun capture(foregroundApp: String, pageTitle: String) = screen
            },
            planner = object : AgentPlanner {
                override fun plan(request: AgentRequest): AgentPlan = error("Unexpected replan")
            },
            actionExecutor = object : AgentActionExecutor {
                override fun execute(action: AgentAction, screen: ScreenContext): AgentActionResult {
                    dispatches++
                    val expectedTarget = if (dispatches > 1 && allowTerminalFallback) "test-hermes" else "test-codex"
                    assertEquals(expectedTarget, action.parameters["connector_id"])
                    assertEquals("desktop-agent", action.parameters["connector_adapter_type"])
                    assertEquals(expectedTarget, session.load()?.currentPlan?.actions?.single()?.parameters?.get("connector_id"))
                    if (dispatches > 1 && allowTerminalFallback) {
                        assertEquals("actual remote outcome", session.load()?.lastActionResult?.message)
                        assertEquals(terminalStatus, session.load()?.lastActionResult?.metadata?.get("remote_task_status"))
                    }
                    if (structuredCloudFailure) {
                        assertTrue(AgentConnectorFallbackTrail.parse(action.parameters["routing_retried_resource_ids"].orEmpty())
                            .containsAll(listOf("test-cloud-a", "test-cloud-b")))
                        assertFalse(action.parameters["routing_deferred_retry_ids"].orEmpty().contains("test-cloud"))
                        assertEquals("permanent_billing", session.load()?.lastActionResult?.metadata?.get("provider_failure_class"))
                    }
                    return AgentActionResult(action.id, success, if (success) "test-success" else "test-permanent-error",
                        AgentConnectorFallbackAction.resultMetadata(action) + mapOf(
                            "awaiting_response" to awaiting.toString(), "non_retriable" to (!success).toString(),
                            "source_message_id" to "902", "contact_id" to expectedTarget, "resource_id" to expectedTarget
                        ))
                }
            },
            memoryStore = InMemoryAgentMemoryStore(),
            taskStore = object : AgentTaskStore {
                override fun upsert(record: AgentTaskRecord) { records[record.taskId] = record }
                override fun recent(limit: Int) = records.values.take(limit)
                override fun forSession(sessionId: String, limit: Int) = recent(limit).filter { it.sessionId == sessionId }
                override fun find(taskId: String) = records[taskId]
                override fun search(query: String, limit: Int) = emptyList<AgentTaskRecord>()
                override fun rebindSession(sourceSessionId: String, targetSessionId: String) = 0
                override fun delete(taskIds: Set<String>) { taskIds.forEach(records::remove) }
                override fun clear() { records.clear() }
            },
            connectorRegistry = object : AgentConnectorRegistry {
                override fun availableTargets() = if (allowTerminalFallback)
                    listOf(target, target.copy(id = "test-hermes", title = "Hermes test")) else listOf(target)
            },
            sessionStore = session,
            screenObservationOverride = false
        )
        val action = AgentAction("test-dispatch", AgentActionKind.CALL_CONNECTOR, "Hermes test", AgentRisk.LOW,
            AgentActionStatus.WAITING_RESPONSE, "Test reply", mapOf("connector_id" to "test-hermes", "prompt" to "Test reply"),
            requiresConfirmation = false)
        val plan = AgentPlan("Test reply", screen, emptyList(), listOf(action), confirmationRequired = false)
        agent.currentGoal = "Test reply"
        agent.currentPlan = plan
        agent.phase = AgentPhase.WAITING_RESPONSE
        if (cancelCloud) {
            val id = AgentCloudDispatchIdentity(901, "test-cloud", "test-conversation", "test-turn", "test-task", action.id)
            val lease = AgentCloudDispatchRegistry.register(id)
            val otherId = id.copy(conversationId = "other", turnId = "other")
            val other = AgentCloudDispatchRegistry.register(otherId)
            val job = kotlinx.coroutines.Job()
            val registration = lease.bind(job)
            try {
                agent.lastActionResult = AgentActionResult(action.id, true, "Waiting", mapOf(
                    "resource_location" to "cloud", "source_message_id" to "901", "contact_id" to "test-cloud",
                    "conversation_id" to "test-conversation", "turn_id" to "test-turn", "task_id" to "test-task"))
                val state = agent.cancelCurrentTask()
                assertTrue(lease.isCancelled)
                assertTrue(job.isCancelled)
                assertFalse(other.isCancelled)
                assertFalse(lease.claimCompletion())
                return state
            } finally {
                registration.dispose()
                other.cancel()
                AgentCloudDispatchRegistry.release(id, lease)
                AgentCloudDispatchRegistry.release(otherId, other)
            }
        }
        val state = if (structuredCloudFailure) {
            assertTrue(agent.startExecutionLoop("test-turn"))
            assertTrue(agent.advanceExecutionLoop(AgentExecutionLoopPhase.ACT, "Test dispatch", action.id))
            assertTrue(agent.advanceExecutionLoop(AgentExecutionLoopPhase.WAITING_RESPONSE, "Test wait", action.id))
            val tracker = AgentProviderAttemptTracker(AgentProviderAttemptReport(901, "test-conversation", "test-turn", "test-task", action.id))
            tracker.start("r1", "test-cloud-a", "provider-a", "model-a", 1)
            tracker.finish(10, AgentProviderFailure(AgentProviderFailureClass.TRANSIENT, true), 503)
            tracker.start("r2", "test-cloud-b", "provider-b", "model-b", 20)
            tracker.finish(10, AgentProviderFailure(AgentProviderFailureClass.PERMANENT_BILLING, false), 402)
            agent.lastActionResult = AgentActionResult(action.id, true, "Waiting", mapOf(
                "source_message_id" to "901", "contact_id" to "test-cloud-a", "conversation_id" to "test-conversation",
                "turn_id" to "test-turn", "task_id" to "test-task", "awaiting_response" to "true",
                "resource_id" to "test-cloud-a", "remaining_fallback_ids" to "test-cloud-a,test-cloud-b,test-codex",
                "cloud_health_recorded" to "true"))
            requireNotNull(agent.acceptConnectorResponse(901, "test-cloud-a", "\u8bf7\u6c42\u5931\u8d25", success = false,
                conversationId = "test-conversation", turnId = "test-turn", taskId = "test-task", providerAttempts = tracker.report))
        } else requireNotNull(agent.continueWithConnectorFallback(plan, AgentActionResult(
            action.id, false, "test-old-failure", mapOf("resource_id" to "test-hermes",
                "remaining_fallback_ids" to "test-codex", "timeout_stage" to "NOT_RUNNING",
                "failure_domain" to "test-desktop", "awaiting_response" to "false")
        )))
        assertEquals(1, dispatches)
        assertEquals(state.phase, session.load()?.phase)
        if (terminalStatus.isNotBlank()) {
            agent.lastActionResult = agent.lastActionResult!!.copy(metadata = agent.lastActionResult!!.metadata + mapOf(
                "resource_location" to "desktop", "conversation_id" to "test-conversation", "turn_id" to "test-turn",
                "remote_task_id" to "test-task", "remaining_fallback_ids" to "", "routing_deferred_retry_ids" to "",
                "manual_target_locked" to (terminalStatus != "cancelled" && !allowTerminalFallback).toString(),
                "remote_execution_generation" to observedGeneration.toString(),
                "remote_task_status_seq" to observedSequence.toString()))
            assertTrue(agent.startExecutionLoop("test-turn"))
            assertTrue(agent.advanceExecutionLoop(AgentExecutionLoopPhase.ACT, "Test dispatch", action.id))
            assertTrue(agent.advanceExecutionLoop(AgentExecutionLoopPhase.WAITING_RESPONSE, "Test wait", action.id))
            val final = requireNotNull(agent.acceptConnectorOutcome(AgentConnectorResponse(902, "test-codex",
                "actual remote outcome", "test-conversation", "test-turn", "test-task", success = false,
                taskStatus = terminalStatus, executionGeneration = 2, statusSequence = 2)))
            assertEquals(if (allowTerminalFallback) 2 else 1, dispatches)
            assertEquals(final.phase, session.load()?.phase)
            return final
        }
        return state
    }
}
