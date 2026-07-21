package com.signalasi.chat

import android.content.Context
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.runBlocking
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Routes production connector actions through the unified Adapter/Provider
 * contract while preserving the existing host-owned action executor as the
 * byte and transport boundary.
 */
class AgentControlPlaneActionExecutor private constructor(
    private val provider: ActionExecutorAgentProvider,
    private val directory: AgentAdapterDirectory
) : AgentActionExecutor {
    constructor(context: Context, delegate: AgentActionExecutor) : this(
        provider = ActionExecutorAgentProvider(
            registrationSource = { AppStoreAgentConnectorRegistry(context).registrations() },
            delegate = delegate,
            recoverableSource = {
                EncryptedAgentHandoffStore(context).active().map { handoff ->
                    AgentRecoverableRun(
                        handle = AgentRunHandle(
                            runId = handoff.request.runId,
                            taskId = handoff.request.taskId,
                            agentId = handoff.request.toAgentId,
                            remoteRunId = handoff.sourceMessageId.takeIf { it > 0L }?.toString()
                                ?: handoff.request.runId,
                            acceptedAtMillis = handoff.request.createdAtMillis
                        ),
                        lastEventSequence = handoff.request.checkpoint["last_event_sequence"]
                            ?.toString()?.toLongOrNull() ?: 0L,
                        checkpoint = handoff.request.checkpoint
                    )
                }
            }
        )
    )

    internal constructor(provider: ActionExecutorAgentProvider) : this(
        provider = provider,
        directory = AgentAdapterDirectory().apply { register(provider) }
    )

    override fun execute(action: AgentAction, screen: ScreenContext): AgentActionResult {
        if (action.kind != AgentActionKind.CALL_CONNECTOR) return provider.executeDelegate(action, screen)
        val requestedAgentId = action.parameters["connector_id"].orEmpty().ifBlank { action.target }
        val agentId = provider.resolveAgentId(requestedAgentId)
            ?: return provider.executeDelegate(action, screen)
        val conversationId = action.parameters[CONVERSATION_ID_KEY].orEmpty()
        val turnId = action.parameters[TURN_ID_KEY].orEmpty()
        val runId = stableRunId(conversationId, turnId, action.id, agentId)
        val request = AgentRunRequest(
            conversationId = conversationId,
            messageId = turnId.ifBlank { action.id },
            taskId = turnId.ifBlank { runId },
            runId = runId,
            goal = action.parameters["original_goal"].orEmpty()
                .ifBlank { action.parameters["prompt"].orEmpty() }
                .ifBlank { action.description },
            deliveryMode = deliveryMode(action.parameters["delivery_mode"].orEmpty()),
            requiredCapabilities = provider.registration(agentId)?.capabilities.orEmpty(),
            context = mapOf(
                "action_id" to action.id,
                "action_target" to action.target,
                "risk" to action.risk.name.lowercase(Locale.ROOT)
            ),
            idempotencyKey = action.parameters["idempotency_key"].orEmpty().ifBlank { runId }
        )
        provider.prepare(agentId, request, action, screen)
        return try {
            val adapter = runBlocking { directory.resolveAdapter(agentId) }
                ?: return provider.executeDelegate(action, screen)
            val handle = runBlocking { adapter.startRun(request) }
            val dispatchResult = provider.result(agentId, handle.runId)
            dispatchResult
                ?.copy(metadata = dispatchResult.metadata + mapOf(
                    "control_plane_run_id" to handle.runId,
                    "control_plane_agent_id" to handle.agentId,
                    "control_plane_remote_run_id" to handle.remoteRunId
                ))
                ?: if (request.deliveryMode == AgentDeliveryMode.IGNORE) {
                    AgentActionResult(
                        actionId = action.id,
                        success = true,
                        message = "",
                        metadata = mapOf(
                            "delivery_mode" to "ignore",
                            "control_plane_run_id" to handle.runId,
                            "control_plane_agent_id" to handle.agentId
                        )
                    )
                } else {
                    AgentActionResult(action.id, false, "Agent Adapter returned no dispatch receipt")
                }
        } catch (error: Throwable) {
            AgentActionResult(
                actionId = action.id,
                success = false,
                message = error.message ?: "Agent Adapter dispatch failed",
                metadata = mapOf(
                    "control_plane_run_id" to runId,
                    "control_plane_agent_id" to agentId
                )
            )
        } finally {
            provider.discardPrepared(agentId, runId)
        }
    }

    private fun stableRunId(conversationId: String, turnId: String, actionId: String, agentId: String): String {
        val source = listOf(conversationId, turnId, actionId, agentId).joinToString("\u001f")
        return UUID.nameUUIDFromBytes(source.toByteArray(Charsets.UTF_8)).toString()
    }

    private fun deliveryMode(value: String): AgentDeliveryMode = when (value.trim().lowercase(Locale.ROOT)) {
        "observe", "inject", "context" -> AgentDeliveryMode.OBSERVE
        "ignore", "none", "skip" -> AgentDeliveryMode.IGNORE
        else -> AgentDeliveryMode.RESPOND
    }

    companion object {
        private const val CONVERSATION_ID_KEY = "_signalasi_conversation_id"
        private const val TURN_ID_KEY = "_signalasi_turn_id"
    }
}

internal class ActionExecutorAgentProvider(
    private val registrationSource: () -> List<AgentRegistration>,
    private val delegate: AgentActionExecutor,
    private val recoverableSource: () -> List<AgentRecoverableRun> = { emptyList() },
    override val providerId: String = "signalasi-connectors",
    private val protocol: AgentProtocolRange = AgentProtocolRange(
        preferred = "1.0",
        minimum = "1.0",
        maximum = "1.0",
        features = setOf("run.cancel", "run.recover", "run.events", "message.respond", "message.observe")
    )
) : AgentProvider {
    private val transports = ConcurrentHashMap<String, ActionExecutorAgentTransport>()
    private val adapters = ConcurrentHashMap<String, AgentAdapter>()

    override suspend fun connect(): AgentProtocolAgreement = AgentProtocolAgreement(
        version = protocol.preferred,
        features = protocol.features
    )

    override suspend fun disconnect() {
        adapters.values.forEach { runCatching { runBlocking { it.disconnect() } } }
        adapters.clear()
        transports.clear()
    }

    override suspend fun registrations(): List<AgentRegistration> = registrationSource()

    override suspend fun adapter(agentId: String): AgentAdapter? {
        adapters[agentId]?.let { return it }
        val registration = registration(agentId) ?: return null
        val transport = transports.computeIfAbsent(agentId) {
            ActionExecutorAgentTransport(registrationSource, delegate, recoverableSource, agentId)
        }
        val adapter = TransportBackedAgentAdapter(registration, transport, protocol)
        return adapters.putIfAbsent(agentId, adapter) ?: adapter
    }

    override suspend fun recoverRuns(): List<AgentRecoverableRun> = recoverableSource()

    fun registration(agentId: String): AgentRegistration? = registrationSource().firstOrNull {
        it.agentId == agentId
    }

    fun resolveAgentId(requested: String): String? {
        val clean = requested.trim()
        if (clean.isBlank()) return null
        val registrations = registrationSource()
        return registrations.firstOrNull { it.agentId == clean }?.agentId
            ?: registrations.firstOrNull { it.agentId.endsWith(":$clean") || clean.endsWith(":${it.agentId}") }?.agentId
            ?: registrations.firstOrNull { it.displayName.equals(clean, ignoreCase = true) }?.agentId
    }

    fun prepare(
        agentId: String,
        request: AgentRunRequest,
        action: AgentAction,
        screen: ScreenContext
    ) {
        val registration = registration(agentId) ?: return
        transports.computeIfAbsent(agentId) {
            ActionExecutorAgentTransport(registrationSource, delegate, recoverableSource, agentId)
        }.prepare(request.runId, action, screen, registration)
    }

    fun result(agentId: String, runId: String): AgentActionResult? = transports[agentId]?.result(runId)

    fun discardPrepared(agentId: String, runId: String) = transports[agentId]?.discardPrepared(runId)

    fun executeDelegate(action: AgentAction, screen: ScreenContext): AgentActionResult = delegate.execute(action, screen)
}

private class ActionExecutorAgentTransport(
    private val registrationSource: () -> List<AgentRegistration>,
    private val delegate: AgentActionExecutor,
    private val recoverableSource: () -> List<AgentRecoverableRun>,
    private val agentId: String
) : AgentAdapterTransport {
    private data class PreparedAction(
        val action: AgentAction,
        val screen: ScreenContext,
        val registration: AgentRegistration
    )

    private val prepared = ConcurrentHashMap<String, PreparedAction>()
    private val results = ConcurrentHashMap<String, AgentActionResult>()
    private val events = ConcurrentHashMap<String, MutableSharedFlow<AgentRunControlEvent>>()

    fun prepare(runId: String, action: AgentAction, screen: ScreenContext, registration: AgentRegistration) {
        prepared[runId] = PreparedAction(action, screen, registration)
    }

    fun result(runId: String): AgentActionResult? = results[runId]

    fun discardPrepared(runId: String) {
        prepared.remove(runId)
        trim(results)
        trim(events)
    }

    override suspend fun open(): AgentProtocolRange = currentRegistration().protocol

    override suspend fun close() = Unit

    override suspend fun status(): AgentRegistration = currentRegistration()

    override suspend fun startRun(request: AgentRunRequest): AgentRunHandle {
        val item = prepared.remove(request.runId)
            ?: throw IllegalStateException("No prepared connector action for Run ${request.runId}")
        emit(request, item.registration, AgentRunControlEventType.AGENT_CONNECTED, 1L)
        val result = delegate.execute(item.action, item.screen)
        results[request.runId] = result
        emit(
            request,
            item.registration,
            if (result.metadata["awaiting_response"] == "true") {
                AgentRunControlEventType.WAITING_FOR_DEVICE
            } else if (result.success) {
                AgentRunControlEventType.STEP_COMPLETED
            } else {
                AgentRunControlEventType.RUN_FAILED
            },
            2L,
            mapOf(
                "action_id" to item.action.id,
                "success" to result.success,
                "source_message_id" to result.metadata["source_message_id"].orEmpty()
            )
        )
        return AgentRunHandle(
            runId = request.runId,
            taskId = request.taskId,
            agentId = agentId,
            remoteRunId = result.metadata["remote_task_id"].orEmpty()
                .ifBlank { result.metadata["source_message_id"].orEmpty() }
                .ifBlank { request.runId }
        )
    }

    override suspend fun sendMessage(runId: String, message: AgentControlMessage) {
        if (message.deliveryMode == AgentDeliveryMode.IGNORE) return
        throw UnsupportedOperationException("Follow-up messages require a prepared connector action")
    }

    override suspend fun cancelRun(runId: String) {
        prepared.remove(runId)
        val current = results[runId]
        results[runId] = current?.copy(
            success = false,
            message = "Agent Run cancelled",
            metadata = current.metadata + ("cancelled" to "true")
        ) ?: AgentActionResult(runId, false, "Agent Run cancelled", mapOf("cancelled" to "true"))
    }

    override fun observeEvents(runId: String): Flow<AgentRunControlEvent> =
        events.computeIfAbsent(runId) { MutableSharedFlow(extraBufferCapacity = 64) }

    override suspend fun recoverRuns(): List<AgentRecoverableRun> = recoverableSource()
        .filter { it.handle.agentId == agentId }

    private fun currentRegistration(): AgentRegistration = registrationSource().firstOrNull {
        it.agentId == agentId
    } ?: throw IllegalStateException("Agent registration is no longer available: $agentId")

    private fun emit(
        request: AgentRunRequest,
        registration: AgentRegistration,
        type: AgentRunControlEventType,
        sequence: Long,
        payload: AgentNativeJsonObject = emptyMap()
    ) {
        events.computeIfAbsent(request.runId) { MutableSharedFlow(extraBufferCapacity = 64) }.tryEmit(
            AgentRunControlEvent(
                conversationId = request.conversationId,
                messageId = request.messageId,
                taskId = request.taskId,
                runId = request.runId,
                agentId = registration.agentId,
                deviceId = registration.deviceId,
                type = type,
                sequence = sequence,
                payload = payload
            )
        )
    }

    private fun <T> trim(map: ConcurrentHashMap<String, T>) {
        if (map.size <= MAX_TRACKED_RUNS) return
        map.keys.take(map.size - MAX_TRACKED_RUNS).forEach(map::remove)
    }

    companion object {
        private const val MAX_TRACKED_RUNS = 1_024
    }
}
