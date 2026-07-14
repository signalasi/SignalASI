package com.signalasi.chat

import java.security.MessageDigest
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.CancellationException

enum class AgentModelMessageRole {
    SYSTEM,
    USER,
    ASSISTANT,
    TOOL
}

data class AgentModelToolCall(
    val callId: String,
    val toolId: String,
    val arguments: AgentNativeJsonObject = emptyMap(),
    val toolVersion: String? = null,
    val idempotencyKey: String? = null,
    val depth: Int = 1
)

data class AgentModelToolResultContent(
    val callId: String,
    val toolId: String,
    val status: String,
    val output: AgentNativeJsonObject = emptyMap(),
    val message: String = "",
    val error: AgentNativeToolError? = null,
    val invocationId: String? = null,
    val retryCount: Int = 0,
    val receipt: AgentNativeJsonObject? = null,
    val nativeResult: AgentNativeJsonObject? = null
) {
    fun toJsonValue(): AgentNativeJsonObject = linkedMapOf(
        "tool_call_id" to callId,
        "tool_id" to toolId,
        "status" to status,
        "output" to output,
        "message" to message,
        "error" to error?.let {
            linkedMapOf(
                "code" to it.code,
                "message" to it.message,
                "retryable" to it.retryable,
                "details" to it.details
            )
        },
        "invocation_id" to invocationId,
        "retry_count" to retryCount,
        "receipt" to receipt,
        "native_result" to nativeResult
    )
}

data class AgentModelMessage(
    val role: AgentModelMessageRole,
    val text: String = "",
    val toolCalls: List<AgentModelToolCall> = emptyList(),
    val toolResult: AgentModelToolResultContent? = null
) {
    init {
        require(role == AgentModelMessageRole.ASSISTANT || toolCalls.isEmpty()) {
            "Only assistant messages may contain tool calls"
        }
        require(role == AgentModelMessageRole.TOOL || toolResult == null) {
            "Only tool messages may contain a tool result"
        }
        require(role != AgentModelMessageRole.TOOL || toolResult != null) {
            "Tool messages require a tool result"
        }
    }

    companion object {
        fun user(text: String) = AgentModelMessage(AgentModelMessageRole.USER, text = text)
        fun system(text: String) = AgentModelMessage(AgentModelMessageRole.SYSTEM, text = text)
    }
}

data class AgentModelUsage(
    val inputTokens: Long = 0,
    val outputTokens: Long = 0
) {
    init {
        require(inputTokens >= 0) { "Input token usage must be non-negative" }
        require(outputTokens >= 0) { "Output token usage must be non-negative" }
    }

    val totalTokens: Long get() = safeTokenSum(inputTokens, outputTokens)
}

data class AgentModelResponse(
    val assistantText: String = "",
    val toolCalls: List<AgentModelToolCall> = emptyList(),
    val usage: AgentModelUsage = AgentModelUsage(),
    val providerMetadata: AgentNativeJsonObject = emptyMap()
) {
    init {
        require(assistantText.isNotBlank() || toolCalls.isNotEmpty()) {
            "A model response must contain assistant text or at least one tool call"
        }
        require(AgentNativeJsonCodec.isCompatible(providerMetadata)) {
            "Provider metadata must be JSON-compatible"
        }
    }
}

data class AgentModelRequest(
    val sessionId: String,
    val conversationId: String,
    val turnId: String,
    val taskId: String,
    val workspaceId: String,
    val round: Int,
    val messages: List<AgentModelMessage>,
    val toolManifestJson: String,
    val toolManifestSha256: String,
    val remainingToolCalls: Int,
    val remainingTokens: Long,
    val remainingTimeMillis: Long,
    val maxDepth: Int,
    val cancellationToken: AgentNativeToolCancellationToken
)

fun interface AgentModelAdapter {
    suspend fun complete(request: AgentModelRequest): AgentModelResponse
}

data class AgentModelToolLoopBudget(
    val maxRounds: Int = 8,
    val maxToolCalls: Int = 32,
    val maxDepth: Int = 4,
    val maxTokens: Long = 32_000,
    val maxDurationMillis: Long = 120_000,
    val maxRetriesPerCall: Int = 1,
    val maxRepeatedCallSignatures: Int = 1,
    val approvalTtlMillis: Long = 60_000
) {
    init {
        require(maxRounds > 0) { "Maximum rounds must be positive" }
        require(maxToolCalls > 0) { "Maximum tool calls must be positive" }
        require(maxDepth > 0) { "Maximum depth must be positive" }
        require(maxTokens > 0) { "Maximum tokens must be positive" }
        require(maxDurationMillis > 0) { "Maximum duration must be positive" }
        require(maxRetriesPerCall >= 0) { "Maximum retries must be non-negative" }
        require(maxRepeatedCallSignatures > 0) { "Repeated-call limit must be positive" }
        require(approvalTtlMillis > 0) { "Approval TTL must be positive" }
    }
}

fun interface AgentModelToolLoopEventSink {
    fun onEvent(event: AgentModelToolLoopEvent)

    companion object {
        val NONE = AgentModelToolLoopEventSink { }
    }
}

data class AgentModelToolLoopRequest(
    val sessionId: String,
    val conversationId: String,
    val turnId: String,
    val taskId: String,
    val workspaceId: String,
    val messages: List<AgentModelMessage>,
    val budget: AgentModelToolLoopBudget = AgentModelToolLoopBudget(),
    val callerId: String = "signalasi.mobile_model_tool_loop",
    val grantedPermissions: Set<String> = emptySet(),
    val grantedConsents: Set<String> = emptySet(),
    val cancellationToken: AgentNativeToolCancellationToken = AgentNativeToolCancellationToken.NONE,
    val eventSink: AgentModelToolLoopEventSink = AgentModelToolLoopEventSink.NONE
) {
    init {
        validateBoundId("Session", sessionId)
        validateBoundId("Conversation", conversationId)
        validateBoundId("Turn", turnId)
        validateBoundId("Task", taskId)
        validateBoundId("Workspace", workspaceId)
        require(messages.isNotEmpty()) { "At least one initial model message is required" }
        require(callerId.isNotBlank()) { "Caller id must not be blank" }
        require(grantedPermissions.none(String::isBlank)) { "Granted permissions must not be blank" }
        require(grantedConsents.none(String::isBlank)) { "Granted consents must not be blank" }
    }

    companion object {
        fun forUserMessage(
            sessionId: String,
            conversationId: String,
            turnId: String,
            taskId: String,
            workspaceId: String,
            userMessage: String,
            budget: AgentModelToolLoopBudget = AgentModelToolLoopBudget(),
            grantedPermissions: Set<String> = emptySet(),
            grantedConsents: Set<String> = emptySet(),
            cancellationToken: AgentNativeToolCancellationToken = AgentNativeToolCancellationToken.NONE,
            eventSink: AgentModelToolLoopEventSink = AgentModelToolLoopEventSink.NONE
        ) = AgentModelToolLoopRequest(
            sessionId = sessionId,
            conversationId = conversationId,
            turnId = turnId,
            taskId = taskId,
            workspaceId = workspaceId,
            messages = listOf(AgentModelMessage.user(userMessage)),
            budget = budget,
            grantedPermissions = grantedPermissions,
            grantedConsents = grantedConsents,
            cancellationToken = cancellationToken,
            eventSink = eventSink
        )
    }
}

enum class AgentModelToolLoopEventType {
    LOOP_STARTED,
    MODEL_REQUESTED,
    MODEL_RESPONDED,
    TOOL_CALL_PROPOSED,
    TOOL_CALL_REJECTED,
    APPROVAL_REQUIRED,
    APPROVAL_DECIDED,
    LOOP_RESUMED,
    TOOL_STARTED,
    TOOL_FINISHED,
    TOOL_RETRY_SCHEDULED,
    BUDGET_EXCEEDED,
    LOOP_DETECTED,
    LOOP_CANCELLED,
    LOOP_FAILED,
    LOOP_COMPLETED
}

data class AgentModelToolLoopEvent(
    val sequence: Long,
    val type: AgentModelToolLoopEventType,
    val occurredAtEpochMillis: Long,
    val sessionId: String,
    val turnId: String,
    val taskId: String,
    val toolManifestSha256: String,
    val round: Int,
    val toolCallId: String? = null,
    val invocationId: String? = null,
    val details: AgentNativeJsonObject = emptyMap()
)

enum class AgentModelToolApprovalDecision(val wireValue: String) {
    APPROVED("approved"),
    REJECTED("rejected"),
    EXPIRED("expired")
}

class AgentModelToolApprovalHandle internal constructor(
    val confirmationId: String,
    val sessionId: String,
    val turnId: String,
    val taskId: String,
    val toolCallId: String,
    val toolId: String,
    val toolVersion: String,
    val argumentsSha256: String,
    val toolManifestSha256: String,
    val requiredConsentIds: Set<String>,
    val targetSummary: String,
    val expiresAtEpochMillis: Long,
    internal val nonce: String
)

enum class AgentModelToolLoopStatus {
    COMPLETED,
    WAITING_FOR_APPROVAL,
    BUDGET_EXCEEDED,
    LOOP_DETECTED,
    CANCELLED,
    MODEL_FAILED
}

data class AgentModelToolLoopError(
    val code: String,
    val message: String,
    val details: AgentNativeJsonObject = emptyMap()
)

data class AgentModelToolLoopUsage(
    val rounds: Int,
    val toolCallAttempts: Int,
    val retries: Int,
    val inputTokens: Long,
    val outputTokens: Long,
    val durationMillis: Long
) {
    val totalTokens: Long get() = safeTokenSum(inputTokens, outputTokens)
}

data class AgentModelToolLoopOutcome(
    val status: AgentModelToolLoopStatus,
    val assistantText: String,
    val messages: List<AgentModelMessage>,
    val events: List<AgentModelToolLoopEvent>,
    val usage: AgentModelToolLoopUsage,
    val toolManifestJson: String,
    val toolManifestSha256: String,
    val approval: AgentModelToolApprovalHandle? = null,
    val error: AgentModelToolLoopError? = null
) {
    val isTerminal: Boolean get() = status != AgentModelToolLoopStatus.WAITING_FOR_APPROVAL
}

fun interface AgentModelToolLoopIdFactory {
    fun newId(purpose: String): String

    companion object {
        val UUIDS = AgentModelToolLoopIdFactory { UUID.randomUUID().toString() }
    }
}

class AgentModelToolLoop(
    private val modelAdapter: AgentModelAdapter,
    private val toolRegistry: AgentNativeToolRegistry,
    private val clock: AgentNativeClock = AgentNativeClock.SYSTEM,
    private val idFactory: AgentModelToolLoopIdFactory = AgentModelToolLoopIdFactory.UUIDS
) {
    private val pendingApprovals = LinkedHashMap<String, PendingApproval>()

    suspend fun run(request: AgentModelToolLoopRequest): AgentModelToolLoopOutcome {
        val startedAt = clock.nowEpochMillis()
        val manifestJson = toolRegistry.catalogJson()
        val state = LoopState(
            request = request,
            messages = request.messages.toMutableList(),
            events = mutableListOf(),
            manifestJson = manifestJson,
            manifestSha256 = rawSha256(manifestJson),
            startedAtEpochMillis = startedAt,
            deadlineEpochMillis = safeAdd(startedAt, request.budget.maxDurationMillis)
        )
        emit(state, AgentModelToolLoopEventType.LOOP_STARTED)
        return advance(state, emptyList())
    }

    suspend fun resume(
        handle: AgentModelToolApprovalHandle,
        decision: AgentModelToolApprovalDecision
    ): AgentModelToolLoopOutcome {
        val pending = synchronized(pendingApprovals) {
            val candidate = pendingApprovals[handle.confirmationId]
                ?: throw IllegalArgumentException("Approval handle is unknown, expired, or already used")
            require(candidate.handle.nonce == handle.nonce) { "Approval handle does not match pending state" }
            pendingApprovals.remove(handle.confirmationId)
            candidate
        }
        val state = pending.state
        terminalGuard(state)?.let { return it }

        val effectiveDecision = if (
            decision == AgentModelToolApprovalDecision.APPROVED &&
            clock.nowEpochMillis() >= handle.expiresAtEpochMillis
        ) {
            AgentModelToolApprovalDecision.EXPIRED
        } else {
            decision
        }
        emit(
            state,
            AgentModelToolLoopEventType.APPROVAL_DECIDED,
            call = pending.call,
            details = mapOf(
                "confirmation_id" to handle.confirmationId,
                "decision" to effectiveDecision.wireValue,
                "arguments_sha256" to handle.argumentsSha256
            )
        )
        emit(state, AgentModelToolLoopEventType.LOOP_RESUMED, call = pending.call)

        val resumed = if (effectiveDecision == AgentModelToolApprovalDecision.APPROVED) {
            executeCall(
                state = state,
                call = pending.call,
                descriptor = pending.descriptor,
                approvedConsentIds = handle.requiredConsentIds,
                confirmationId = handle.confirmationId
            )
        } else {
            val code = if (effectiveDecision == AgentModelToolApprovalDecision.EXPIRED) {
                "approval_expired"
            } else {
                "approval_rejected"
            }
            appendSyntheticToolResult(
                state,
                pending.call,
                code = code,
                message = if (effectiveDecision == AgentModelToolApprovalDecision.EXPIRED) {
                    "The phone approval expired before the tool call could run"
                } else {
                    "The user rejected the phone tool call"
                }
            )
            ProcessResult.Continue
        }
        if (resumed is ProcessResult.Terminal) return resumed.outcome
        return advance(state, pending.remainingCalls)
    }

    private suspend fun advance(
        state: LoopState,
        initialCalls: List<AgentModelToolCall>
    ): AgentModelToolLoopOutcome {
        var calls = initialCalls
        while (true) {
            terminalGuard(state)?.let { return it }
            if (calls.isNotEmpty()) {
                when (val processed = processCalls(state, calls)) {
                    ProcessResult.Continue -> Unit
                    is ProcessResult.Terminal -> return processed.outcome
                }
                calls = emptyList()
            }

            terminalGuard(state)?.let { return it }
            if (state.rounds >= state.request.budget.maxRounds) {
                return budgetExceeded(state, "max_rounds", "The model round budget was exhausted")
            }

            state.rounds += 1
            emit(state, AgentModelToolLoopEventType.MODEL_REQUESTED)
            val modelRequest = AgentModelRequest(
                sessionId = state.request.sessionId,
                conversationId = state.request.conversationId,
                turnId = state.request.turnId,
                taskId = state.request.taskId,
                workspaceId = state.request.workspaceId,
                round = state.rounds,
                messages = state.messages.toList(),
                toolManifestJson = state.manifestJson,
                toolManifestSha256 = state.manifestSha256,
                remainingToolCalls = (state.request.budget.maxToolCalls - state.toolCallAttempts).coerceAtLeast(0),
                remainingTokens = (state.request.budget.maxTokens - state.totalTokens()).coerceAtLeast(0),
                remainingTimeMillis = remainingTime(state),
                maxDepth = state.request.budget.maxDepth,
                cancellationToken = state.request.cancellationToken
            )
            val response = try {
                modelAdapter.complete(modelRequest)
            } catch (cancelled: CancellationException) {
                if (!state.request.cancellationToken.isCancellationRequested) throw cancelled
                return cancelled(state)
            } catch (error: Throwable) {
                return modelFailed(state, error)
            }
            if (state.request.cancellationToken.isCancellationRequested) return cancelled(state)

            state.inputTokens = safeTokenSum(state.inputTokens, response.usage.inputTokens)
            state.outputTokens = safeTokenSum(state.outputTokens, response.usage.outputTokens)
            state.lastAssistantText = response.assistantText
            state.messages += AgentModelMessage(
                role = AgentModelMessageRole.ASSISTANT,
                text = response.assistantText,
                toolCalls = response.toolCalls
            )
            emit(
                state,
                AgentModelToolLoopEventType.MODEL_RESPONDED,
                details = mapOf(
                    "tool_call_count" to response.toolCalls.size,
                    "input_tokens" to response.usage.inputTokens,
                    "output_tokens" to response.usage.outputTokens
                )
            )

            terminalGuard(state)?.let { return it }
            if (state.totalTokens() > state.request.budget.maxTokens) {
                return budgetExceeded(state, "max_tokens", "The model token budget was exhausted")
            }
            if (response.toolCalls.isEmpty()) return completed(state, response.assistantText)

            val roundFingerprint = responseFingerprint(response)
            if (!state.seenResponseFingerprints.add(roundFingerprint)) {
                return loopDetected(
                    state,
                    code = "repeated_model_response",
                    message = "The model repeated an identical tool-calling response"
                )
            }
            calls = response.toolCalls
        }
    }

    private fun processCalls(
        state: LoopState,
        calls: List<AgentModelToolCall>
    ): ProcessResult {
        calls.forEachIndexed { index, call ->
            terminalGuard(state)?.let { return ProcessResult.Terminal(it) }
            when (val result = processCall(state, call, calls.drop(index + 1))) {
                ProcessResult.Continue -> Unit
                is ProcessResult.Terminal -> return result
            }
        }
        return ProcessResult.Continue
    }

    private fun processCall(
        state: LoopState,
        call: AgentModelToolCall,
        remainingCalls: List<AgentModelToolCall>
    ): ProcessResult {
        emit(state, AgentModelToolLoopEventType.TOOL_CALL_PROPOSED, call = call)
        if (!consumeToolCallAttempt(state)) {
            return ProcessResult.Terminal(
                budgetExceeded(state, "max_tool_calls", "The phone tool-call budget was exhausted")
            )
        }

        val basicError = basicCallError(call)
        if (basicError != null) {
            appendSyntheticToolResult(state, call, basicError.first, basicError.second)
            return ProcessResult.Continue
        }

        val argumentsSha256 = AgentNativeJsonCodec.sha256(call.arguments)
        val resolvedVersion = toolRegistry.lookup(call.toolId)?.descriptor?.version
            ?: call.toolVersion.orEmpty()
        val callIdentity = "${call.toolId}|$resolvedVersion|$argumentsSha256|${call.depth}"
        val previousIdentity = state.callIds.putIfAbsent(call.callId, callIdentity)
        if (previousIdentity != null) {
            return ProcessResult.Terminal(
                loopDetected(
                    state,
                    code = if (previousIdentity == callIdentity) "repeated_tool_call_id" else "tool_call_id_reused",
                    message = "The model reused a tool call id",
                    call = call
                )
            )
        }

        val signatureCount = (state.callSignatures[callIdentity] ?: 0) + 1
        state.callSignatures[callIdentity] = signatureCount
        if (signatureCount > state.request.budget.maxRepeatedCallSignatures) {
            return ProcessResult.Terminal(
                loopDetected(
                    state,
                    code = "repeated_tool_call",
                    message = "The model repeated the same tool call beyond the configured limit",
                    call = call
                )
            )
        }

        val inputValidation = toolRegistry.validateInput(call.toolId, call.arguments)
        if (!inputValidation.isValid) {
            appendSyntheticToolResult(
                state,
                call,
                code = inputValidation.issues.firstOrNull()?.code ?: "invalid_input",
                message = "The phone rejected the proposed tool input",
                details = mapOf(
                    "issues" to inputValidation.issues.map {
                        mapOf("path" to it.path, "code" to it.code, "message" to it.message)
                    }
                )
            )
            return ProcessResult.Continue
        }

        val descriptor = toolRegistry.lookup(call.toolId)?.descriptor
            ?: error("Registry validation succeeded for an unknown tool")
        if (call.toolVersion != null && call.toolVersion != descriptor.version) {
            appendSyntheticToolResult(
                state,
                call,
                code = "tool_version_mismatch",
                message = "The proposed tool version does not match the phone manifest",
                details = mapOf("expected" to descriptor.version, "received" to call.toolVersion)
            )
            return ProcessResult.Continue
        }
        if (call.depth > state.request.budget.maxDepth) {
            appendSyntheticToolResult(
                state,
                call,
                code = "max_depth_exceeded",
                message = "The proposed tool call exceeds the configured graph depth",
                details = mapOf("depth" to call.depth, "max_depth" to state.request.budget.maxDepth)
            )
            return ProcessResult.Continue
        }

        val missingConsents = descriptor.requiredConsents
            .filter { it.required && it.id !in state.request.grantedConsents }
            .map { it.id }
            .toSet()
        if (missingConsents.isNotEmpty()) {
            val now = clock.nowEpochMillis()
            val handle = AgentModelToolApprovalHandle(
                confirmationId = checkedId("confirmation"),
                sessionId = state.request.sessionId,
                turnId = state.request.turnId,
                taskId = state.request.taskId,
                toolCallId = call.callId,
                toolId = descriptor.id,
                toolVersion = descriptor.version,
                argumentsSha256 = argumentsSha256,
                toolManifestSha256 = state.manifestSha256,
                requiredConsentIds = missingConsents,
                targetSummary = descriptor.title,
                expiresAtEpochMillis = minOf(
                    state.deadlineEpochMillis,
                    safeAdd(now, state.request.budget.approvalTtlMillis)
                ),
                nonce = checkedId("approval_nonce")
            )
            synchronized(pendingApprovals) {
                pendingApprovals[handle.confirmationId] = PendingApproval(
                    state = state,
                    call = call,
                    remainingCalls = remainingCalls,
                    descriptor = descriptor,
                    handle = handle
                )
            }
            emit(
                state,
                AgentModelToolLoopEventType.APPROVAL_REQUIRED,
                call = call,
                details = mapOf(
                    "confirmation_id" to handle.confirmationId,
                    "consent_ids" to missingConsents.sorted(),
                    "arguments_sha256" to argumentsSha256,
                    "expires_at_epoch_ms" to handle.expiresAtEpochMillis
                )
            )
            return ProcessResult.Terminal(outcome(state, AgentModelToolLoopStatus.WAITING_FOR_APPROVAL, handle))
        }

        return executeCall(state, call, descriptor)
    }

    private fun executeCall(
        state: LoopState,
        call: AgentModelToolCall,
        descriptor: AgentNativeToolDescriptor,
        approvedConsentIds: Set<String> = emptySet(),
        confirmationId: String? = null
    ): ProcessResult {
        val idempotencyKey = when (descriptor.idempotency) {
            AgentNativeToolIdempotency.NON_IDEMPOTENT -> call.idempotencyKey
            AgentNativeToolIdempotency.IDEMPOTENT,
            AgentNativeToolIdempotency.IDEMPOTENCY_KEY_REQUIRED -> call.idempotencyKey?.takeIf(String::isNotBlank)
                ?: derivedIdempotencyKey(state, call)
        }
        var attempt = 0
        while (true) {
            terminalGuard(state)?.let { return ProcessResult.Terminal(it) }
            attempt += 1
            val invocationId = checkedId("invocation")
            emit(
                state,
                AgentModelToolLoopEventType.TOOL_STARTED,
                call = call,
                invocationId = invocationId,
                details = mapOf("attempt" to attempt, "tool_version" to descriptor.version)
            )
            val result = toolRegistry.invoke(
                id = call.toolId,
                input = call.arguments,
                context = AgentNativeToolInvocationContext(
                    invocationId = invocationId,
                    sessionId = state.request.sessionId,
                    conversationId = state.request.conversationId,
                    turnId = state.request.turnId,
                    callerId = state.request.callerId,
                    requestedAtEpochMillis = clock.nowEpochMillis(),
                    deadlineEpochMillis = state.deadlineEpochMillis,
                    idempotencyKey = idempotencyKey,
                    grantedPermissions = state.request.grantedPermissions,
                    grantedConsents = state.request.grantedConsents + approvedConsentIds,
                    attributes = buildMap {
                        put("task_id", state.request.taskId)
                        put("workspace_id", state.request.workspaceId)
                        put("tool_call_id", call.callId)
                        put("tool_manifest_sha256", state.manifestSha256)
                        put("model_round", state.rounds.toString())
                        put("tool_depth", call.depth.toString())
                        put("retry_attempt", (attempt - 1).toString())
                        confirmationId?.let { put("confirmation_id", it) }
                    }
                ),
                hooks = AgentNativeToolInvocationHooks(
                    cancellationToken = state.request.cancellationToken
                )
            )
            emit(
                state,
                AgentModelToolLoopEventType.TOOL_FINISHED,
                call = call,
                invocationId = invocationId,
                details = mapOf(
                    "status" to result.status.wireValue,
                    "error_code" to result.error?.code,
                    "retryable" to (result.error?.retryable == true),
                    "attempt" to attempt
                )
            )

            if (result.status == AgentNativeToolResultStatus.CANCELLED ||
                state.request.cancellationToken.isCancellationRequested
            ) {
                appendToolResult(state, call, result, attempt - 1)
                return ProcessResult.Terminal(cancelled(state))
            }

            val mayRetry = !result.isSuccess &&
                result.error?.retryable == true &&
                descriptor.idempotency != AgentNativeToolIdempotency.NON_IDEMPOTENT &&
                attempt <= state.request.budget.maxRetriesPerCall
            if (!mayRetry) {
                appendToolResult(state, call, result, attempt - 1)
                return ProcessResult.Continue
            }
            if (!consumeToolCallAttempt(state)) {
                appendToolResult(state, call, result, attempt - 1)
                return ProcessResult.Terminal(
                    budgetExceeded(state, "max_tool_calls", "A safe tool retry would exceed the call budget")
                )
            }

            state.retries += 1
            emit(
                state,
                AgentModelToolLoopEventType.TOOL_RETRY_SCHEDULED,
                call = call,
                invocationId = invocationId,
                details = mapOf(
                    "next_attempt" to (attempt + 1),
                    "error_code" to result.error?.code,
                    "idempotency" to descriptor.idempotency.wireValue
                )
            )
        }
    }

    private fun appendToolResult(
        state: LoopState,
        call: AgentModelToolCall,
        result: AgentNativeToolResult,
        retryCount: Int
    ) {
        state.messages += AgentModelMessage(
            role = AgentModelMessageRole.TOOL,
            toolResult = AgentModelToolResultContent(
                callId = call.callId,
                toolId = call.toolId,
                status = result.status.wireValue,
                output = result.output,
                message = result.message,
                error = result.error,
                invocationId = result.receipt.invocationId,
                retryCount = retryCount,
                receipt = receiptValue(result.receipt),
                nativeResult = result.toJsonValue()
            )
        )
    }

    private fun receiptValue(receipt: AgentNativeToolReceipt): AgentNativeJsonObject = linkedMapOf(
        "invocation_id" to receipt.invocationId,
        "idempotency_key" to receipt.idempotencyKey,
        "started_at_epoch_ms" to receipt.startedAtEpochMillis,
        "finished_at_epoch_ms" to receipt.finishedAtEpochMillis,
        "duration_ms" to receipt.durationMillis,
        "status" to receipt.status.wireValue,
        "input_sha256" to receipt.inputSha256,
        "output_sha256" to receipt.outputSha256,
        "replayed" to receipt.replayed,
        "original_invocation_id" to receipt.originalInvocationId
    )

    private fun appendSyntheticToolResult(
        state: LoopState,
        call: AgentModelToolCall,
        code: String,
        message: String,
        details: AgentNativeJsonObject = emptyMap()
    ) {
        val error = AgentNativeToolError(code, message, retryable = false, details = details)
        state.messages += AgentModelMessage(
            role = AgentModelMessageRole.TOOL,
            toolResult = AgentModelToolResultContent(
                callId = call.callId,
                toolId = call.toolId,
                status = AgentNativeToolResultStatus.REJECTED.wireValue,
                message = message,
                error = error
            )
        )
        emit(
            state,
            AgentModelToolLoopEventType.TOOL_CALL_REJECTED,
            call = call,
            details = mapOf("code" to code, "message" to message) + details
        )
    }

    private fun basicCallError(call: AgentModelToolCall): Pair<String, String>? = when {
        call.callId.isBlank() -> "invalid_tool_call_id" to "Tool call id must not be blank"
        call.callId.length > MAX_ID_LENGTH -> "invalid_tool_call_id" to "Tool call id is too long"
        call.toolId.isBlank() -> "invalid_tool_id" to "Tool id must not be blank"
        call.depth <= 0 -> "invalid_tool_depth" to "Tool call depth must be positive"
        !AgentNativeJsonCodec.isCompatible(call.arguments) ->
            "invalid_json_arguments" to "Tool arguments must be JSON-compatible"
        else -> null
    }

    private fun terminalGuard(state: LoopState): AgentModelToolLoopOutcome? = when {
        state.request.cancellationToken.isCancellationRequested -> cancelled(state)
        clock.nowEpochMillis() >= state.deadlineEpochMillis ->
            budgetExceeded(state, "max_duration", "The model tool loop exceeded its time budget")
        else -> null
    }

    private fun completed(state: LoopState, assistantText: String): AgentModelToolLoopOutcome {
        emit(
            state,
            AgentModelToolLoopEventType.LOOP_COMPLETED,
            details = mapOf("assistant_text_present" to assistantText.isNotBlank())
        )
        return outcome(state, AgentModelToolLoopStatus.COMPLETED)
    }

    private fun cancelled(state: LoopState): AgentModelToolLoopOutcome {
        if (state.events.lastOrNull()?.type != AgentModelToolLoopEventType.LOOP_CANCELLED) {
            emit(state, AgentModelToolLoopEventType.LOOP_CANCELLED)
        }
        return outcome(
            state,
            AgentModelToolLoopStatus.CANCELLED,
            error = AgentModelToolLoopError("cancelled", "The phone-owned model tool loop was cancelled")
        )
    }

    private fun budgetExceeded(
        state: LoopState,
        code: String,
        message: String
    ): AgentModelToolLoopOutcome {
        emit(state, AgentModelToolLoopEventType.BUDGET_EXCEEDED, details = mapOf("code" to code))
        return outcome(
            state,
            AgentModelToolLoopStatus.BUDGET_EXCEEDED,
            error = AgentModelToolLoopError(code, message)
        )
    }

    private fun loopDetected(
        state: LoopState,
        code: String,
        message: String,
        call: AgentModelToolCall? = null
    ): AgentModelToolLoopOutcome {
        emit(
            state,
            AgentModelToolLoopEventType.LOOP_DETECTED,
            call = call,
            details = mapOf("code" to code)
        )
        return outcome(
            state,
            AgentModelToolLoopStatus.LOOP_DETECTED,
            error = AgentModelToolLoopError(code, message)
        )
    }

    private fun modelFailed(state: LoopState, error: Throwable): AgentModelToolLoopOutcome {
        val safeMessage = error.message?.take(500).orEmpty().ifBlank { error::class.java.simpleName }
        emit(
            state,
            AgentModelToolLoopEventType.LOOP_FAILED,
            details = mapOf("code" to "model_failed")
        )
        return outcome(
            state,
            AgentModelToolLoopStatus.MODEL_FAILED,
            error = AgentModelToolLoopError("model_failed", safeMessage)
        )
    }

    private fun outcome(
        state: LoopState,
        status: AgentModelToolLoopStatus,
        approval: AgentModelToolApprovalHandle? = null,
        error: AgentModelToolLoopError? = null
    ) = AgentModelToolLoopOutcome(
        status = status,
        assistantText = state.lastAssistantText,
        messages = state.messages.toList(),
        events = state.events.toList(),
        usage = AgentModelToolLoopUsage(
            rounds = state.rounds,
            toolCallAttempts = state.toolCallAttempts,
            retries = state.retries,
            inputTokens = state.inputTokens,
            outputTokens = state.outputTokens,
            durationMillis = (clock.nowEpochMillis() - state.startedAtEpochMillis).coerceAtLeast(0)
        ),
        toolManifestJson = state.manifestJson,
        toolManifestSha256 = state.manifestSha256,
        approval = approval,
        error = error
    )

    private fun emit(
        state: LoopState,
        type: AgentModelToolLoopEventType,
        call: AgentModelToolCall? = null,
        invocationId: String? = null,
        details: AgentNativeJsonObject = emptyMap()
    ) {
        state.eventSequence += 1
        val event = AgentModelToolLoopEvent(
            sequence = state.eventSequence,
            type = type,
            occurredAtEpochMillis = clock.nowEpochMillis(),
            sessionId = state.request.sessionId,
            turnId = state.request.turnId,
            taskId = state.request.taskId,
            toolManifestSha256 = state.manifestSha256,
            round = state.rounds,
            toolCallId = call?.callId,
            invocationId = invocationId,
            details = details
        )
        state.events += event
        runCatching { state.request.eventSink.onEvent(event) }
    }

    private fun consumeToolCallAttempt(state: LoopState): Boolean {
        if (state.toolCallAttempts >= state.request.budget.maxToolCalls) return false
        state.toolCallAttempts += 1
        return true
    }

    private fun remainingTime(state: LoopState): Long =
        (state.deadlineEpochMillis - clock.nowEpochMillis()).coerceAtLeast(0)

    private fun derivedIdempotencyKey(state: LoopState, call: AgentModelToolCall): String = rawSha256(
        listOf(
            state.request.sessionId,
            state.request.turnId,
            call.callId,
            call.toolId,
            AgentNativeJsonCodec.sha256(call.arguments)
        ).joinToString("|")
    )

    private fun responseFingerprint(response: AgentModelResponse): String = AgentNativeJsonCodec.sha256(
        mapOf(
            "assistant_text" to response.assistantText,
            "tool_calls" to response.toolCalls.map {
                mapOf(
                    "call_id" to it.callId,
                    "tool_id" to it.toolId,
                    "tool_version" to it.toolVersion,
                    "arguments" to it.arguments,
                    "depth" to it.depth
                )
            }
        )
    )

    private fun checkedId(purpose: String): String {
        val value = idFactory.newId(purpose)
        validateBoundId(purpose.replace('_', ' '), value)
        return value
    }

    private data class PendingApproval(
        val state: LoopState,
        val call: AgentModelToolCall,
        val remainingCalls: List<AgentModelToolCall>,
        val descriptor: AgentNativeToolDescriptor,
        val handle: AgentModelToolApprovalHandle
    )

    private data class LoopState(
        val request: AgentModelToolLoopRequest,
        val messages: MutableList<AgentModelMessage>,
        val events: MutableList<AgentModelToolLoopEvent>,
        val manifestJson: String,
        val manifestSha256: String,
        val startedAtEpochMillis: Long,
        val deadlineEpochMillis: Long,
        val callIds: MutableMap<String, String> = linkedMapOf(),
        val callSignatures: MutableMap<String, Int> = linkedMapOf(),
        val seenResponseFingerprints: MutableSet<String> = linkedSetOf(),
        var rounds: Int = 0,
        var toolCallAttempts: Int = 0,
        var retries: Int = 0,
        var inputTokens: Long = 0,
        var outputTokens: Long = 0,
        var lastAssistantText: String = "",
        var eventSequence: Long = 0
    ) {
        fun totalTokens(): Long = safeTokenSum(inputTokens, outputTokens)
    }

    private sealed interface ProcessResult {
        data object Continue : ProcessResult
        data class Terminal(val outcome: AgentModelToolLoopOutcome) : ProcessResult
    }

    companion object {
        private const val MAX_ID_LENGTH = 160
    }
}

private fun validateBoundId(label: String, value: String) {
    require(value.isNotBlank()) { "$label id must not be blank" }
    require(value.length <= 160) { "$label id must not exceed 160 characters" }
}

private fun safeTokenSum(left: Long, right: Long): Long =
    if (right > 0 && left > Long.MAX_VALUE - right) Long.MAX_VALUE else left + right

private fun safeAdd(left: Long, right: Long): Long =
    if (right > 0 && left > Long.MAX_VALUE - right) Long.MAX_VALUE else left + right

private fun rawSha256(value: String): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
    return digest.joinToString("") { "%02x".format(Locale.ROOT, it.toInt() and 0xff) }
}
