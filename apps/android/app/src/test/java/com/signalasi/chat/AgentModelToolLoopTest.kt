package com.signalasi.chat

import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentModelToolLoopTest {
    @Test
    fun completesIterativeToolCallWithBoundIdsManifestAndEvents() = runBlocking {
        val capturedContexts = mutableListOf<AgentNativeToolInvocationContext>()
        val registry = registry(
            idempotency = AgentNativeToolIdempotency.IDEMPOTENT,
            executor = AgentNativeToolExecutor { invocation ->
                capturedContexts += invocation.context
                AgentNativeToolExecutionResult.success(
                    output = mapOf("echo" to invocation.input["value"]),
                    message = "Echoed"
                )
            }
        )
        val adapter = ScriptedAdapter(
            AgentModelResponse(
                toolCalls = listOf(call("call-1", arguments = mapOf("value" to "hello"))),
                usage = AgentModelUsage(inputTokens = 4, outputTokens = 2)
            ),
            AgentModelResponse("The phone echoed hello.", usage = AgentModelUsage(3, 5))
        )
        val streamedEvents = mutableListOf<AgentModelToolLoopEvent>()
        val loop = loop(adapter, registry)

        val outcome = loop.run(
            request(eventSink = AgentModelToolLoopEventSink { event -> streamedEvents += event })
        )

        assertEquals(AgentModelToolLoopStatus.COMPLETED, outcome.status)
        assertEquals("The phone echoed hello.", outcome.assistantText)
        assertEquals(2, outcome.usage.rounds)
        assertEquals(1, outcome.usage.toolCallAttempts)
        assertEquals(14L, outcome.usage.totalTokens)
        assertEquals(64, outcome.toolManifestSha256.length)
        assertEquals(outcome.events, streamedEvents)
        assertEquals(outcome.events.indices.map { it.toLong() + 1 }, outcome.events.map { it.sequence })
        assertTrue(outcome.events.all { it.sessionId == "session-1" && it.turnId == "turn-1" })
        assertTrue(outcome.events.all { it.toolManifestSha256 == outcome.toolManifestSha256 })
        assertEquals(outcome.toolManifestSha256, adapter.singleManifestHash())

        val context = capturedContexts.single()
        assertEquals("session-1", context.sessionId)
        assertEquals("conversation-1", context.conversationId)
        assertEquals("turn-1", context.turnId)
        assertEquals("task-1", context.attributes["task_id"])
        assertEquals("workspace-1", context.attributes["workspace_id"])
        assertEquals("call-1", context.attributes["tool_call_id"])
        assertEquals(outcome.toolManifestSha256, context.attributes["tool_manifest_sha256"])
        assertNotNull(context.idempotencyKey)
        assertEquals(
            "hello",
            outcome.messages.single { it.role == AgentModelMessageRole.TOOL }
                .toolResult?.output?.get("echo")
        )
        assertEquals(
            TOOL_ID,
            outcome.messages.single { it.role == AgentModelMessageRole.TOOL }
                .toolResult?.nativeResult?.get("provenance")
                .let { it as Map<*, *> }["tool_id"]
        )
    }

    @Test
    fun pausesForBoundConsentAndResumesWithSingleUseApproval() = runBlocking {
        val executions = AtomicInteger()
        var invocationContext: AgentNativeToolInvocationContext? = null
        val clock = MutableClock(1_000)
        val registry = registry(
            clock = clock,
            consents = listOf(AgentNativeConsentRequirement("contacts.lookup", "Look up contacts")),
            executor = AgentNativeToolExecutor { invocation ->
                executions.incrementAndGet()
                invocationContext = invocation.context
                AgentNativeToolExecutionResult.success(mapOf("echo" to "Alice"))
            }
        )
        val adapter = ScriptedAdapter(
            AgentModelResponse(toolCalls = listOf(call("consent-call"))),
            AgentModelResponse("Alice was found on the phone.")
        )
        val loop = loop(adapter, registry, clock)

        val paused = loop.run(request())

        assertEquals(AgentModelToolLoopStatus.WAITING_FOR_APPROVAL, paused.status)
        assertFalse(paused.isTerminal)
        assertEquals(0, executions.get())
        val handle = requireNotNull(paused.approval)
        assertEquals("session-1", handle.sessionId)
        assertEquals("turn-1", handle.turnId)
        assertEquals("consent-call", handle.toolCallId)
        assertEquals(setOf("contacts.lookup"), handle.requiredConsentIds)
        assertEquals(paused.toolManifestSha256, handle.toolManifestSha256)

        val completed = loop.resume(handle, AgentModelToolApprovalDecision.APPROVED)

        assertEquals(AgentModelToolLoopStatus.COMPLETED, completed.status)
        assertEquals(1, executions.get())
        assertTrue("contacts.lookup" in requireNotNull(invocationContext).grantedConsents)
        assertEquals(handle.confirmationId, invocationContext?.attributes?.get("confirmation_id"))
        assertTrue(completed.events.any { it.type == AgentModelToolLoopEventType.APPROVAL_DECIDED })
        assertTrue(completed.events.any { it.type == AgentModelToolLoopEventType.LOOP_RESUMED })
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { loop.resume(handle, AgentModelToolApprovalDecision.APPROVED) }
        }
        Unit
    }

    @Test
    fun stopsBeforeExecutionWhenTokenBudgetIsExceeded() = runBlocking {
        val executions = AtomicInteger()
        val registry = registry(executor = AgentNativeToolExecutor {
            executions.incrementAndGet()
            AgentNativeToolExecutionResult.success()
        })
        val adapter = ScriptedAdapter(
            AgentModelResponse(
                assistantText = "I will call the tool.",
                toolCalls = listOf(call("over-budget")),
                usage = AgentModelUsage(inputTokens = 6, outputTokens = 5)
            )
        )
        val loop = loop(adapter, registry)

        val outcome = loop.run(
            request(budget = AgentModelToolLoopBudget(maxTokens = 10))
        )

        assertEquals(AgentModelToolLoopStatus.BUDGET_EXCEEDED, outcome.status)
        assertEquals("max_tokens", outcome.error?.code)
        assertEquals(0, executions.get())
        assertEquals(11L, outcome.usage.totalTokens)
        assertEquals(AgentModelToolLoopEventType.BUDGET_EXCEEDED, outcome.events.last().type)
    }

    @Test
    fun propagatesCancellationIntoActiveNativeTool() = runBlocking {
        val cancellation = AgentNativeToolCancellationSource()
        val executions = AtomicInteger()
        val registry = registry(executor = AgentNativeToolExecutor { invocation ->
            executions.incrementAndGet()
            cancellation.cancel()
            invocation.checkpoint()
            AgentNativeToolExecutionResult.success()
        })
        val adapter = ScriptedAdapter(
            AgentModelResponse(toolCalls = listOf(call("cancel-call"))),
            AgentModelResponse("This response must not be requested.")
        )
        val loop = loop(adapter, registry)

        val outcome = loop.run(request(cancellationToken = cancellation.token))

        assertEquals(AgentModelToolLoopStatus.CANCELLED, outcome.status)
        assertEquals(1, executions.get())
        assertEquals(1, adapter.requests.size)
        assertEquals(
            AgentNativeToolResultStatus.CANCELLED.wireValue,
            outcome.messages.last().toolResult?.status
        )
        assertEquals(AgentModelToolLoopEventType.LOOP_CANCELLED, outcome.events.last().type)
    }

    @Test
    fun returnsInvalidCallToModelWithoutInvokingUnknownTool() = runBlocking {
        val registry = AgentNativeToolRegistry()
        val adapter = ScriptedAdapter(
            AgentModelResponse(
                toolCalls = listOf(
                    AgentModelToolCall("invalid-call", "phone.unknown.tool", emptyMap())
                )
            ),
            AgentModelResponse("That phone tool is unavailable.")
        )
        val loop = loop(adapter, registry)

        val outcome = loop.run(request())

        assertEquals(AgentModelToolLoopStatus.COMPLETED, outcome.status)
        assertEquals("unknown_tool", outcome.messages.single {
            it.role == AgentModelMessageRole.TOOL
        }.toolResult?.error?.code)
        assertEquals("unknown_tool", adapter.requests[1].messages.last().toolResult?.error?.code)
        assertTrue(outcome.events.any { it.type == AgentModelToolLoopEventType.TOOL_CALL_REJECTED })
    }

    @Test
    fun detectsRepeatedCallSignatureBeforeSecondExecution() = runBlocking {
        val executions = AtomicInteger()
        val registry = registry(executor = AgentNativeToolExecutor {
            executions.incrementAndGet()
            AgentNativeToolExecutionResult.success(mapOf("echo" to "same"))
        })
        val adapter = ScriptedAdapter(
            AgentModelResponse(
                toolCalls = listOf(call("repeat-1", arguments = mapOf("value" to "same")))
            ),
            AgentModelResponse(
                toolCalls = listOf(call("repeat-2", arguments = mapOf("value" to "same")))
            )
        )
        val loop = loop(adapter, registry)

        val outcome = loop.run(request())

        assertEquals(AgentModelToolLoopStatus.LOOP_DETECTED, outcome.status)
        assertEquals("repeated_tool_call", outcome.error?.code)
        assertEquals(1, executions.get())
        assertEquals(AgentModelToolLoopEventType.LOOP_DETECTED, outcome.events.last().type)
    }

    @Test
    fun retriesOnlyRetryableIdempotentToolFailures() = runBlocking {
        val executions = AtomicInteger()
        val registry = registry(
            idempotency = AgentNativeToolIdempotency.IDEMPOTENT,
            executor = AgentNativeToolExecutor {
                if (executions.incrementAndGet() == 1) {
                    AgentNativeToolExecutionResult.failure("temporary", "Try again", retryable = true)
                } else {
                    AgentNativeToolExecutionResult.success(mapOf("echo" to "recovered"))
                }
            }
        )
        val adapter = ScriptedAdapter(
            AgentModelResponse(toolCalls = listOf(call("retry-call"))),
            AgentModelResponse("Recovered after a safe retry.")
        )
        val loop = loop(adapter, registry)

        val outcome = loop.run(request())

        assertEquals(AgentModelToolLoopStatus.COMPLETED, outcome.status)
        assertEquals(2, executions.get())
        assertEquals(1, outcome.usage.retries)
        assertEquals(2, outcome.usage.toolCallAttempts)
        assertEquals(1, outcome.messages.single {
            it.role == AgentModelMessageRole.TOOL
        }.toolResult?.retryCount)
        assertTrue(outcome.events.any { it.type == AgentModelToolLoopEventType.TOOL_RETRY_SCHEDULED })
    }

    @Test
    fun reportsNonIdempotentFailureWithoutRetrying() = runBlocking {
        val executions = AtomicInteger()
        val registry = registry(
            idempotency = AgentNativeToolIdempotency.NON_IDEMPOTENT,
            executor = AgentNativeToolExecutor {
                executions.incrementAndGet()
                AgentNativeToolExecutionResult.failure("send_failed", "Not sent", retryable = true)
            }
        )
        val adapter = ScriptedAdapter(
            AgentModelResponse(toolCalls = listOf(call("send-call"))),
            AgentModelResponse("The action failed and was not repeated.")
        )
        val loop = loop(adapter, registry)

        val outcome = loop.run(request())

        assertEquals(AgentModelToolLoopStatus.COMPLETED, outcome.status)
        assertEquals(1, executions.get())
        assertEquals(0, outcome.usage.retries)
        val result = outcome.messages.single { it.role == AgentModelMessageRole.TOOL }.toolResult
        assertEquals(AgentNativeToolResultStatus.FAILED.wireValue, result?.status)
        assertEquals("send_failed", result?.error?.code)
    }

    @Test
    fun bindsWorkspaceToolCallsToTheCurrentModelLoopWorkspace() = runBlocking {
        var receivedWorkspace = ""
        val toolId = AgentPhoneNativeToolCatalog.WORKSPACE_READ_TEXT
        val registry = AgentNativeToolRegistry(MutableClock(100)).register(
            AgentNativeToolDefinition(
                descriptor = AgentNativeToolDescriptor(
                    id = toolId,
                    version = "1.0.0",
                    title = "Read workspace text",
                    description = "Test workspace scoping.",
                    location = AgentNativeToolLocation.PHONE,
                    inputSchema = AgentNativeJsonSchema.objectSchema(
                        properties = mapOf(
                            "workspace_id" to AgentNativeJsonSchema.string(),
                            "path" to AgentNativeJsonSchema.string()
                        ),
                        required = setOf("workspace_id", "path"),
                        additionalProperties = false
                    ),
                    outputSchema = AgentNativeJsonSchema.any(),
                    risk = AgentNativeToolRisk.LOW
                ),
                executor = AgentNativeToolExecutor { invocation ->
                    receivedWorkspace = invocation.input["workspace_id"]?.toString().orEmpty()
                    AgentNativeToolExecutionResult.success(mapOf("text" to "ok"))
                },
                executorId = "test.workspace_scope"
            )
        )
        val adapter = ScriptedAdapter(
            AgentModelResponse(
                toolCalls = listOf(
                    AgentModelToolCall(
                        callId = "workspace-call",
                        toolId = toolId,
                        arguments = mapOf("workspace_id" to "foreign-workspace", "path" to "notes.txt"),
                        toolVersion = "1.0.0"
                    )
                )
            ),
            AgentModelResponse("Read the project file.")
        )

        val outcome = loop(adapter, registry).run(request())

        assertEquals(AgentModelToolLoopStatus.COMPLETED, outcome.status)
        val toolResult = outcome.messages.single { it.role == AgentModelMessageRole.TOOL }.toolResult
        assertEquals(toolResult?.error?.toString(), AgentNativeToolResultStatus.SUCCEEDED.wireValue, toolResult?.status)
        assertEquals("workspace-1", receivedWorkspace)
        assertEquals("ok", toolResult?.output?.get("text"))
    }

    private fun loop(
        adapter: AgentModelAdapter,
        registry: AgentNativeToolRegistry,
        clock: AgentNativeClock = MutableClock(100)
    ) = AgentModelToolLoop(
        modelAdapter = adapter,
        toolRegistry = registry,
        clock = clock,
        idFactory = CountingIdFactory()
    )

    private fun request(
        budget: AgentModelToolLoopBudget = AgentModelToolLoopBudget(),
        cancellationToken: AgentNativeToolCancellationToken = AgentNativeToolCancellationToken.NONE,
        eventSink: AgentModelToolLoopEventSink = AgentModelToolLoopEventSink.NONE
    ) = AgentModelToolLoopRequest.forUserMessage(
        sessionId = "session-1",
        conversationId = "conversation-1",
        turnId = "turn-1",
        taskId = "task-1",
        workspaceId = "workspace-1",
        userMessage = "Use the phone tool.",
        budget = budget,
        cancellationToken = cancellationToken,
        eventSink = eventSink
    )

    private fun call(
        id: String,
        arguments: AgentNativeJsonObject = mapOf("value" to "Alice")
    ) = AgentModelToolCall(
        callId = id,
        toolId = TOOL_ID,
        arguments = arguments,
        toolVersion = "1.0.0"
    )

    private fun registry(
        clock: AgentNativeClock = MutableClock(100),
        idempotency: AgentNativeToolIdempotency = AgentNativeToolIdempotency.NON_IDEMPOTENT,
        consents: List<AgentNativeConsentRequirement> = emptyList(),
        executor: AgentNativeToolExecutor
    ): AgentNativeToolRegistry = AgentNativeToolRegistry(clock).register(
        AgentNativeToolDefinition(
            descriptor = AgentNativeToolDescriptor(
                id = TOOL_ID,
                version = "1.0.0",
                title = "Echo value",
                description = "Returns a value for model tool loop tests.",
                location = AgentNativeToolLocation.PHONE,
                inputSchema = AgentNativeJsonSchema.objectSchema(
                    properties = mapOf("value" to AgentNativeJsonSchema.string()),
                    required = setOf("value"),
                    additionalProperties = false
                ),
                outputSchema = AgentNativeJsonSchema.objectSchema(),
                risk = AgentNativeToolRisk.LOW,
                requiredConsents = consents,
                idempotency = idempotency
            ),
            executor = executor,
            executorId = "test.model_tool_loop"
        )
    )

    private class ScriptedAdapter(vararg responses: AgentModelResponse) : AgentModelAdapter {
        private val remaining = ArrayDeque(responses.toList())
        val requests = mutableListOf<AgentModelRequest>()

        override suspend fun complete(request: AgentModelRequest): AgentModelResponse {
            requests += request
            return remaining.removeFirst()
        }

        fun singleManifestHash(): String {
            val hashes = requests.map { it.toolManifestSha256 }.distinct()
            assertEquals(1, hashes.size)
            assertTrue(requests.all { it.toolManifestJson == requests.first().toolManifestJson })
            return hashes.single()
        }
    }

    private class CountingIdFactory : AgentModelToolLoopIdFactory {
        private var next = 0
        override fun newId(purpose: String): String = "$purpose-${++next}"
    }

    private class MutableClock(var now: Long) : AgentNativeClock {
        override fun nowEpochMillis(): Long = now
    }

    companion object {
        private const val TOOL_ID = "phone.test.echo"
    }
}
