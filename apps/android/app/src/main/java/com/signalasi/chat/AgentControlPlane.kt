package com.signalasi.chat

import android.content.Context
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

enum class AgentDeliveryMode { RESPOND, OBSERVE, IGNORE }

enum class AgentEndpointStatus {
    ONLINE,
    OFFLINE,
    IDLE,
    BUSY,
    DEGRADED,
    UPDATING,
    PERMISSION_REQUIRED,
    UNREACHABLE
}

enum class AgentConnectionKind {
    IN_PROCESS,
    BINDER,
    SIGNALASI_LINK,
    CLI_JSON,
    STDIO,
    HTTP,
    WEBSOCKET,
    MCP
}

data class AgentProtocolRange(
    val preferred: String,
    val minimum: String,
    val maximum: String,
    val features: Set<String> = emptySet()
)

data class AgentProtocolAgreement(
    val version: String,
    val features: Set<String>
)

data class AgentRegistration(
    val agentId: String,
    val installationId: String,
    val deviceId: String,
    val providerId: String,
    val displayName: String,
    val kind: AgentConnectorKind,
    val location: AgentResourceLocation,
    val status: AgentEndpointStatus,
    val capabilities: Set<AgentCapability>,
    val toolIds: Set<String> = emptySet(),
    val permissionScopes: Set<String> = emptySet(),
    val protocol: AgentProtocolRange,
    val connectionKind: AgentConnectionKind,
    val cost: AgentResourceCost = AgentResourceCost.FREE,
    val latency: AgentResourceLatency = AgentResourceLatency.NORMAL,
    val trust: AgentResourceTrust = AgentResourceTrust.UNKNOWN,
    val activeRuns: Int = 0,
    val maxParallelRuns: Int = 1,
    val capabilitiesHash: String = "",
    val failureDomain: String = "",
    val lastHeartbeatMillis: Long = 0L,
    val updatedAtMillis: Long = System.currentTimeMillis()
) {
    val hasCapacity: Boolean get() = activeRuns < maxParallelRuns.coerceAtLeast(1)
}

data class AgentRunRequest(
    val conversationId: String,
    val messageId: String,
    val taskId: String,
    val runId: String = UUID.randomUUID().toString(),
    val parentRunId: String = "",
    val goal: String,
    val deliveryMode: AgentDeliveryMode = AgentDeliveryMode.RESPOND,
    val requiredCapabilities: Set<AgentCapability> = emptySet(),
    val context: AgentNativeJsonObject = emptyMap(),
    val idempotencyKey: String = runId,
    val createdAtMillis: Long = System.currentTimeMillis()
)

data class AgentRunHandle(
    val runId: String,
    val taskId: String,
    val agentId: String,
    val remoteRunId: String = runId,
    val acceptedAtMillis: Long = System.currentTimeMillis()
)

data class AgentControlMessage(
    val messageId: String,
    val role: String,
    val text: String,
    val attachments: List<AgentArtifactReference> = emptyList(),
    val deliveryMode: AgentDeliveryMode = AgentDeliveryMode.RESPOND
)

data class AgentRecoverableRun(
    val handle: AgentRunHandle,
    val lastEventSequence: Long,
    val checkpoint: AgentNativeJsonObject = emptyMap()
)

data class AgentHandoffRequest(
    val handoffId: String = UUID.randomUUID().toString(),
    val conversationId: String,
    val taskId: String,
    val runId: String,
    val parentRunId: String = runId,
    val fromAgentId: String,
    val toAgentId: String,
    val returnToAgentId: String = fromAgentId,
    val reason: String,
    val deliveryMode: AgentDeliveryMode = AgentDeliveryMode.RESPOND,
    val requiredCapabilities: Set<AgentCapability> = emptySet(),
    val artifactIds: List<String> = emptyList(),
    val checkpoint: AgentNativeJsonObject = emptyMap(),
    val context: AgentNativeJsonObject = emptyMap(),
    val createdAtMillis: Long = System.currentTimeMillis()
)

enum class AgentHandoffState {
    REQUESTED,
    ACTIVE,
    RETURNED,
    FAILED,
    CANCELLED
}

data class AgentHandoffRecord(
    val request: AgentHandoffRequest,
    val state: AgentHandoffState,
    val sourceMessageId: Long = 0L,
    val resultSummary: String = "",
    val updatedAtMillis: Long = System.currentTimeMillis()
)

data class AgentHandoffMutation(
    val record: AgentHandoffRecord,
    val created: Boolean
)

object AgentHandoffLifecycle {
    fun stableId(runId: String, stepId: String, fromAgentId: String, toAgentId: String): String {
        val source = listOf(runId, stepId, fromAgentId, toAgentId).joinToString("\u001f")
        return UUID.nameUUIDFromBytes(source.toByteArray(Charsets.UTF_8)).toString()
    }

    fun transition(current: AgentHandoffState, requested: AgentHandoffState): AgentHandoffState {
        if (current in TERMINAL_STATES) return current
        return when (requested) {
            AgentHandoffState.REQUESTED -> current
            AgentHandoffState.ACTIVE -> AgentHandoffState.ACTIVE
            AgentHandoffState.RETURNED -> AgentHandoffState.RETURNED
            AgentHandoffState.FAILED -> AgentHandoffState.FAILED
            AgentHandoffState.CANCELLED -> AgentHandoffState.CANCELLED
        }
    }

    private val TERMINAL_STATES = setOf(
        AgentHandoffState.RETURNED,
        AgentHandoffState.FAILED,
        AgentHandoffState.CANCELLED
    )
}

/**
 * Encrypted host-owned ledger for cross-Agent transfers. A deterministic
 * handoff id makes re-rendering and process recovery safe without dispatching
 * or recording the same transfer twice.
 */
class EncryptedAgentHandoffStore(context: Context) {
    private val database = AgentEncryptedDatabase(
        context.applicationContext,
        DATABASE,
        legacyPreferencesName = UNUSED_LEGACY_PREFERENCES
    )

    @Synchronized
    fun beginActive(request: AgentHandoffRequest, sourceMessageId: Long = 0L): AgentHandoffMutation {
        require(request.handoffId.isNotBlank()) { "Handoff id must not be blank" }
        require(request.runId.isNotBlank() && request.taskId.isNotBlank()) {
            "Handoff run and task ids must not be blank"
        }
        require(request.fromAgentId.isNotBlank() && request.toAgentId.isNotBlank()) {
            "Handoff endpoints must not be blank"
        }
        val records = list().toMutableList()
        records.firstOrNull { it.request.handoffId == request.handoffId }?.let { existing ->
            return AgentHandoffMutation(existing, created = false)
        }
        val now = System.currentTimeMillis()
        val record = AgentHandoffRecord(
            request = request,
            state = AgentHandoffState.ACTIVE,
            sourceMessageId = sourceMessageId.coerceAtLeast(0L),
            updatedAtMillis = now
        )
        save((records + record).takeLast(MAX_RECORDS))
        return AgentHandoffMutation(record, created = true)
    }

    @Synchronized
    fun finish(
        runId: String,
        sourceMessageId: Long,
        state: AgentHandoffState,
        resultSummary: String = ""
    ): AgentHandoffRecord? {
        require(state in TERMINAL_STATES) { "A handoff can only finish in a terminal state" }
        val records = list().toMutableList()
        val index = records.indexOfLast { record ->
            record.request.runId == runId &&
                record.state !in TERMINAL_STATES &&
                (sourceMessageId <= 0L || record.sourceMessageId == sourceMessageId)
        }
        if (index < 0) return null
        val existing = records[index]
        val updated = existing.copy(
            state = AgentHandoffLifecycle.transition(existing.state, state),
            resultSummary = resultSummary.take(MAX_RESULT_CHARACTERS),
            updatedAtMillis = System.currentTimeMillis()
        )
        records[index] = updated
        save(records)
        return updated
    }

    @Synchronized
    fun list(): List<AgentHandoffRecord> = decode(database.readString(KEY_RECORDS, "[]"))

    @Synchronized
    fun forRun(runId: String): List<AgentHandoffRecord> = list().filter { it.request.runId == runId }

    @Synchronized
    fun active(): List<AgentHandoffRecord> = list().filter { it.state !in TERMINAL_STATES }

    @Synchronized
    fun clear() = database.clear()

    private fun save(records: List<AgentHandoffRecord>) {
        database.writeString(KEY_RECORDS, JSONArray().apply {
            records.forEach { put(it.toJson()) }
        }.toString())
    }

    private fun decode(raw: String): List<AgentHandoffRecord> = runCatching {
        val array = JSONArray(raw)
        buildList {
            for (index in 0 until array.length()) {
                array.optJSONObject(index)?.toHandoffRecord()?.let(::add)
            }
        }
    }.getOrDefault(emptyList())

    companion object {
        private const val DATABASE = "signalasi_agent_handoffs_v1"
        private const val UNUSED_LEGACY_PREFERENCES = "signalasi_agent_handoffs_v1_no_legacy"
        private const val KEY_RECORDS = "records"
        private const val MAX_RECORDS = 1_000
        private const val MAX_RESULT_CHARACTERS = 2_000
        private val TERMINAL_STATES = setOf(
            AgentHandoffState.RETURNED,
            AgentHandoffState.FAILED,
            AgentHandoffState.CANCELLED
        )
    }
}

enum class AgentTeamVisibilityMode { BACKGROUND, VISIBLE }

data class AgentTeamMember(
    val agentId: String,
    val deliveryMode: AgentDeliveryMode,
    val requiredCapabilities: Set<AgentCapability> = emptySet()
)

data class AgentTeamDefinition(
    val teamId: String = UUID.randomUUID().toString(),
    val primaryAgentId: String,
    val members: List<AgentTeamMember>,
    val visibilityMode: AgentTeamVisibilityMode = AgentTeamVisibilityMode.BACKGROUND
)

data class AgentTeamRun(
    val teamId: String,
    val taskId: String,
    val primaryRun: AgentRunHandle,
    val memberRuns: Map<String, AgentRunHandle>,
    val unavailableMembers: Map<String, String>,
    val visibilityMode: AgentTeamVisibilityMode
)

enum class AgentRunControlEventType {
    RUN_CREATED,
    RUN_QUEUED,
    RUN_STARTED,
    PLANNING,
    THINKING,
    AGENT_CONNECTED,
    STEP_STARTED,
    TOOL_PERMISSION_REQUIRED,
    TOOL_STARTED,
    TOOL_PROGRESS,
    TOOL_COMPLETED,
    WAITING_FOR_USER,
    WAITING_FOR_DEVICE,
    PAUSED,
    RETRYING,
    HANDOFF,
    STEP_COMPLETED,
    RUN_COMPLETED,
    RUN_FAILED,
    RUN_CANCELLED,
    RUN_RECOVERED
}

data class AgentRunControlEvent(
    val eventId: String = UUID.randomUUID().toString(),
    val conversationId: String,
    val messageId: String,
    val taskId: String,
    val runId: String,
    val stepId: String = "",
    val toolCallId: String = "",
    val agentId: String,
    val deviceId: String,
    val type: AgentRunControlEventType,
    val sequence: Long,
    val timestampMillis: Long = System.currentTimeMillis(),
    val payload: AgentNativeJsonObject = emptyMap()
)

enum class AgentRunControlState {
    CREATED,
    QUEUED,
    RUNNING,
    WAITING_FOR_USER,
    WAITING_FOR_DEVICE,
    PAUSED,
    COMPLETED,
    FAILED,
    CANCELLED
}

data class AgentRunControlSnapshot(
    val runId: String,
    val taskId: String,
    val state: AgentRunControlState,
    val agentId: String,
    val deviceId: String,
    val lastSequence: Long,
    val lastEvent: AgentRunControlEvent
)

interface AgentAdapter {
    val registration: AgentRegistration
    suspend fun connect(): AgentProtocolAgreement
    suspend fun disconnect()
    suspend fun status(): AgentRegistration
    suspend fun startRun(request: AgentRunRequest): AgentRunHandle
    suspend fun sendMessage(runId: String, message: AgentControlMessage)
    suspend fun cancelRun(runId: String)
    fun observeEvents(runId: String): Flow<AgentRunControlEvent>
    suspend fun recoverRuns(): List<AgentRecoverableRun>
}

interface AgentProvider {
    val providerId: String
    suspend fun connect(): AgentProtocolAgreement
    suspend fun disconnect()
    suspend fun registrations(): List<AgentRegistration>
    suspend fun adapter(agentId: String): AgentAdapter?
    suspend fun recoverRuns(): List<AgentRecoverableRun>
}

/**
 * Transport boundary used by concrete Codex, Claude Code, OpenClaw, cloud, and
 * device connectors. The transport owns bytes and authentication; the adapter
 * owns stable identity, protocol negotiation, and capability-safe behavior.
 */
interface AgentAdapterTransport {
    suspend fun open(): AgentProtocolRange
    suspend fun close()
    suspend fun status(): AgentRegistration
    suspend fun startRun(request: AgentRunRequest): AgentRunHandle
    suspend fun sendMessage(runId: String, message: AgentControlMessage)
    suspend fun cancelRun(runId: String)
    fun observeEvents(runId: String): Flow<AgentRunControlEvent>
    suspend fun recoverRuns(): List<AgentRecoverableRun>
}

class TransportBackedAgentAdapter(
    initialRegistration: AgentRegistration,
    private val transport: AgentAdapterTransport,
    private val localProtocol: AgentProtocolRange = initialRegistration.protocol
) : AgentAdapter {
    private val connectionMutex = Mutex()
    private val runStartMutex = Mutex()
    private val runHandlesByIdempotencyKey = linkedMapOf<String, AgentRunHandle>()
    @Volatile private var currentRegistration = initialRegistration
    @Volatile private var agreement: AgentProtocolAgreement? = null

    override val registration: AgentRegistration
        get() = currentRegistration

    override suspend fun connect(): AgentProtocolAgreement = connectionMutex.withLock {
        agreement?.let { return@withLock it }
        val remoteProtocol = transport.open()
        val negotiated = AgentProtocolNegotiator.negotiate(localProtocol, remoteProtocol)
        if (negotiated == null) {
            runCatching { transport.close() }
            throw IllegalStateException("No compatible SignalASI Agent protocol version")
        }
        agreement = negotiated
        negotiated
    }

    override suspend fun disconnect() = connectionMutex.withLock {
        runCatching { transport.close() }
        agreement = null
    }

    override suspend fun status(): AgentRegistration {
        ensureConnected()
        val remote = transport.status()
        require(remote.agentId == currentRegistration.agentId) { "Agent identity changed during a connection" }
        require(remote.installationId == currentRegistration.installationId) {
            "Agent installation identity changed during a connection"
        }
        currentRegistration = remote
        return remote
    }

    override suspend fun startRun(request: AgentRunRequest): AgentRunHandle = runStartMutex.withLock {
        require(request.idempotencyKey.isNotBlank()) { "Run idempotency key must not be blank" }
        runHandlesByIdempotencyKey[request.idempotencyKey]?.let { return@withLock it }
        val handle = if (request.deliveryMode == AgentDeliveryMode.IGNORE) {
            AgentRunHandle(
                runId = request.runId,
                taskId = request.taskId,
                agentId = currentRegistration.agentId,
                remoteRunId = ""
            )
        } else {
            val negotiated = ensureConnected()
            if (request.deliveryMode == AgentDeliveryMode.OBSERVE) {
                requireFeature(negotiated, "message.observe")
            }
            transport.startRun(request)
        }
        runHandlesByIdempotencyKey[request.idempotencyKey] = handle
        while (runHandlesByIdempotencyKey.size > MAX_IDEMPOTENCY_HANDLES) {
            runHandlesByIdempotencyKey.remove(runHandlesByIdempotencyKey.keys.first())
        }
        handle
    }

    override suspend fun sendMessage(runId: String, message: AgentControlMessage) {
        val negotiated = ensureConnected()
        if (message.deliveryMode == AgentDeliveryMode.OBSERVE) {
            requireFeature(negotiated, "message.observe")
        }
        if (message.deliveryMode != AgentDeliveryMode.IGNORE) transport.sendMessage(runId, message)
    }

    override suspend fun cancelRun(runId: String) {
        requireFeature(ensureConnected(), "run.cancel")
        transport.cancelRun(runId)
    }

    override fun observeEvents(runId: String): Flow<AgentRunControlEvent> = transport.observeEvents(runId)

    override suspend fun recoverRuns(): List<AgentRecoverableRun> {
        val negotiated = ensureConnected()
        return if ("run.recover" in negotiated.features) transport.recoverRuns() else emptyList()
    }

    private suspend fun ensureConnected(): AgentProtocolAgreement = agreement ?: connect()

    private fun requireFeature(negotiated: AgentProtocolAgreement, feature: String) {
        require(feature in negotiated.features) { "Agent does not support $feature" }
    }

    companion object {
        private const val MAX_IDEMPOTENCY_HANDLES = 1_024
    }
}

interface AgentProviderTransport {
    suspend fun open(): AgentProtocolRange
    suspend fun close()
    suspend fun registrations(): List<AgentRegistration>
    suspend fun adapterTransport(agentId: String): Pair<AgentRegistration, AgentAdapterTransport>?
    suspend fun recoverRuns(): List<AgentRecoverableRun>
}

class TransportBackedAgentProvider(
    override val providerId: String,
    private val transport: AgentProviderTransport,
    private val localProtocol: AgentProtocolRange
) : AgentProvider {
    private val connectionMutex = Mutex()
    @Volatile private var agreement: AgentProtocolAgreement? = null

    override suspend fun connect(): AgentProtocolAgreement = connectionMutex.withLock {
        agreement?.let { return@withLock it }
        val negotiated = AgentProtocolNegotiator.negotiate(localProtocol, transport.open())
        if (negotiated == null) {
            runCatching { transport.close() }
            throw IllegalStateException("No compatible SignalASI Provider protocol version")
        }
        agreement = negotiated
        negotiated
    }

    override suspend fun disconnect() = connectionMutex.withLock {
        runCatching { transport.close() }
        agreement = null
    }

    override suspend fun registrations(): List<AgentRegistration> {
        ensureConnected()
        return transport.registrations().filter { it.providerId == providerId }
    }

    override suspend fun adapter(agentId: String): AgentAdapter? {
        ensureConnected()
        val (registration, adapterTransport) = transport.adapterTransport(agentId) ?: return null
        require(registration.providerId == providerId) { "Agent belongs to a different provider" }
        return TransportBackedAgentAdapter(registration, adapterTransport, localProtocol)
    }

    override suspend fun recoverRuns(): List<AgentRecoverableRun> {
        val negotiated = ensureConnected()
        return if ("run.recover" in negotiated.features) transport.recoverRuns() else emptyList()
    }

    private suspend fun ensureConnected(): AgentProtocolAgreement = agreement ?: connect()
}

class AgentAdapterDirectory {
    private val adapters = ConcurrentHashMap<String, AgentAdapter>()
    private val providers = ConcurrentHashMap<String, AgentProvider>()

    fun register(adapter: AgentAdapter): AgentAdapter? {
        require(adapter.registration.agentId.isNotBlank()) { "Agent id must not be blank" }
        return adapters.put(adapter.registration.agentId, adapter)
    }

    fun register(provider: AgentProvider): AgentProvider? {
        require(provider.providerId.isNotBlank()) { "Provider id must not be blank" }
        return providers.put(provider.providerId, provider)
    }

    fun adapter(agentId: String): AgentAdapter? = adapters[agentId]

    fun provider(providerId: String): AgentProvider? = providers[providerId]

    suspend fun resolveAdapter(agentId: String): AgentAdapter? {
        adapters[agentId]?.let { return it }
        providers.values.forEach { provider ->
            provider.adapter(agentId)?.let { resolved ->
                adapters.putIfAbsent(agentId, resolved)
                return adapters[agentId]
            }
        }
        return null
    }

    suspend fun registrations(): List<AgentRegistration> {
        val remote = providers.values.flatMap { provider -> provider.registrations() }
        return (localRegistrations() + remote).distinctBy { it.agentId }.sortedBy { it.displayName }
    }

    fun localRegistrations(): List<AgentRegistration> = adapters.values
        .map(AgentAdapter::registration)
        .sortedBy(AgentRegistration::displayName)

    fun unregisterAgent(agentId: String): AgentAdapter? = adapters.remove(agentId)

    fun unregisterProvider(providerId: String): AgentProvider? = providers.remove(providerId)

    fun clear() {
        adapters.clear()
        providers.clear()
    }
}

class AgentTeamCoordinator(private val directory: AgentAdapterDirectory) {
    suspend fun start(definition: AgentTeamDefinition, request: AgentRunRequest): AgentTeamRun {
        val members = definition.members.distinctBy { it.agentId }
        require(definition.teamId.isNotBlank()) { "Team id must not be blank" }
        require(members.any { it.agentId == definition.primaryAgentId && it.deliveryMode == AgentDeliveryMode.RESPOND }) {
            "The primary Agent must be a responding team member"
        }
        val primaryMember = members.first { it.agentId == definition.primaryAgentId }
        val primaryAdapter = requireNotNull(directory.resolveAdapter(primaryMember.agentId)) {
            "Primary Agent is unavailable: ${primaryMember.agentId}"
        }
        require(primaryAdapter.registration.capabilities.containsAll(primaryMember.requiredCapabilities)) {
            "Primary Agent lacks required capabilities"
        }
        val primaryRun = primaryAdapter.startRun(
            request.copy(
                deliveryMode = AgentDeliveryMode.RESPOND,
                requiredCapabilities = request.requiredCapabilities + primaryMember.requiredCapabilities
            )
        )
        val runs = linkedMapOf(primaryMember.agentId to primaryRun)
        val unavailable = linkedMapOf<String, String>()
        members.filterNot { it.agentId == primaryMember.agentId || it.deliveryMode == AgentDeliveryMode.IGNORE }
            .forEach { member ->
                val adapter = directory.resolveAdapter(member.agentId)
                when {
                    adapter == null -> unavailable[member.agentId] = "agent_unavailable"
                    !adapter.registration.capabilities.containsAll(member.requiredCapabilities) ->
                        unavailable[member.agentId] = "capability_mismatch"
                    else -> runCatching {
                        adapter.startRun(
                            request.copy(
                                runId = UUID.randomUUID().toString(),
                                parentRunId = primaryRun.runId,
                                deliveryMode = member.deliveryMode,
                                requiredCapabilities = request.requiredCapabilities + member.requiredCapabilities,
                                idempotencyKey = "${request.idempotencyKey}:${member.agentId}"
                            )
                        )
                    }.onSuccess { runs[member.agentId] = it }
                        .onFailure { unavailable[member.agentId] = it.message.orEmpty().ifBlank { "start_failed" } }
                }
            }
        return AgentTeamRun(
            teamId = definition.teamId,
            taskId = request.taskId,
            primaryRun = primaryRun,
            memberRuns = runs,
            unavailableMembers = unavailable,
            visibilityMode = definition.visibilityMode
        )
    }
}

object AgentProtocolNegotiator {
    fun negotiate(local: AgentProtocolRange, remote: AgentProtocolRange): AgentProtocolAgreement? {
        val localMinimum = protocolParts(local.minimum) ?: return null
        val localMaximum = protocolParts(local.maximum) ?: return null
        val remoteMinimum = protocolParts(remote.minimum) ?: return null
        val remoteMaximum = protocolParts(remote.maximum) ?: return null
        val minimum = if (compareProtocol(localMinimum, remoteMinimum) >= 0) localMinimum else remoteMinimum
        val maximum = if (compareProtocol(localMaximum, remoteMaximum) <= 0) localMaximum else remoteMaximum
        if (compareProtocol(minimum, maximum) > 0) return null
        val preferred = listOfNotNull(protocolParts(local.preferred), protocolParts(remote.preferred))
            .filter { compareProtocol(it, minimum) >= 0 && compareProtocol(it, maximum) <= 0 }
            .maxWithOrNull { left, right -> compareProtocol(left, right) } ?: maximum
        return AgentProtocolAgreement(
            version = "${preferred.first}.${preferred.second}",
            features = local.features.intersect(remote.features)
        )
    }

    private fun protocolParts(value: String): Pair<Int, Int>? {
        val parts = value.trim().removePrefix("v").split('.')
        val major = parts.getOrNull(0)?.toIntOrNull() ?: return null
        val minor = parts.getOrNull(1)?.toIntOrNull() ?: 0
        return major to minor
    }

    private fun compareProtocol(left: Pair<Int, Int>, right: Pair<Int, Int>): Int =
        if (left.first != right.first) left.first.compareTo(right.first) else left.second.compareTo(right.second)
}

enum class AgentRunRecoveryDisposition {
    RESTORE_LOCAL_WAIT,
    RECONNECT_DURABLE_REMOTE,
    FAIL_NON_REPLAYABLE,
    IGNORE_TERMINAL
}

data class AgentRunRecoveryDecision(
    val disposition: AgentRunRecoveryDisposition,
    val reason: String
)

object AgentRunRecoveryPolicy {
    fun decide(
        snapshot: AgentRunControlSnapshot,
        recordedRun: AgentRecordedRun?,
        registration: AgentRegistration?
    ): AgentRunRecoveryDecision {
        if (recordedRun?.status != AgentRecordedRunStatus.RUNNING) {
            return AgentRunRecoveryDecision(AgentRunRecoveryDisposition.IGNORE_TERMINAL, "recorded_run_is_terminal")
        }
        if (snapshot.state == AgentRunControlState.WAITING_FOR_USER ||
            snapshot.state == AgentRunControlState.PAUSED
        ) {
            return AgentRunRecoveryDecision(AgentRunRecoveryDisposition.RESTORE_LOCAL_WAIT, "user_resumable_checkpoint")
        }
        val durableRemote = registration?.location == AgentResourceLocation.TRUSTED_DESKTOP &&
            registration.connectionKind in setOf(
                AgentConnectionKind.SIGNALASI_LINK,
                AgentConnectionKind.WEBSOCKET,
                AgentConnectionKind.CLI_JSON,
                AgentConnectionKind.STDIO
            )
        if (durableRemote) {
            return AgentRunRecoveryDecision(
                AgentRunRecoveryDisposition.RECONNECT_DURABLE_REMOTE,
                "durable_remote_run_can_reconnect"
            )
        }
        return AgentRunRecoveryDecision(
            AgentRunRecoveryDisposition.FAIL_NON_REPLAYABLE,
            "interrupted_run_cannot_be_replayed_safely"
        )
    }
}

class EncryptedAgentRegistry(context: Context) {
    private val appContext = context.applicationContext
    private val database = AgentEncryptedDatabase(
        appContext,
        DATABASE,
        legacyPreferencesName = UNUSED_LEGACY_PREFERENCES
    )

    @Synchronized
    fun upsert(registration: AgentRegistration): AgentRegistration {
        require(registration.agentId.isNotBlank()) { "Agent id must not be blank" }
        require(registration.installationId.isNotBlank()) { "Installation id must not be blank" }
        require(registration.deviceId.isNotBlank()) { "Device id must not be blank" }
        val existingRecords = list()
        val records = existingRecords.toMutableList()
        val index = records.indexOfFirst { it.agentId == registration.agentId }
        val existing = records.getOrNull(index)
        val stable = registration.copy(
            installationId = existing?.installationId ?: registration.installationId,
            deviceId = existing?.deviceId ?: registration.deviceId,
            updatedAtMillis = System.currentTimeMillis()
        )
        if (index >= 0) records[index] = stable else records += stable
        val after = records.takeLast(MAX_AGENTS)
        save(after)
        GlobalConversationEventBus.publishCapabilityEvents(
            appContext,
            GlobalCapabilityObservationExtractor.agentMutations(existingRecords, after)
        )
        return stable
    }

    @Synchronized
    fun heartbeat(
        agentId: String,
        status: AgentEndpointStatus,
        activeRuns: Int,
        capabilitiesHash: String = "",
        timestampMillis: Long = System.currentTimeMillis()
    ): AgentRegistration? {
        val existingRecords = list()
        val records = existingRecords.toMutableList()
        val index = records.indexOfFirst { it.agentId == agentId }
        if (index < 0) return null
        records[index] = records[index].copy(
            status = status,
            activeRuns = activeRuns.coerceAtLeast(0),
            capabilitiesHash = capabilitiesHash.ifBlank { records[index].capabilitiesHash },
            lastHeartbeatMillis = timestampMillis,
            updatedAtMillis = timestampMillis
        )
        save(records)
        GlobalConversationEventBus.publishCapabilityEvents(
            appContext,
            GlobalCapabilityObservationExtractor.agentMutations(existingRecords, records, timestampMillis)
        )
        return records[index]
    }

    @Synchronized
    fun list(nowMillis: Long = System.currentTimeMillis()): List<AgentRegistration> = decode(
        database.readString(KEY_REGISTRY, "[]")
    ).map { registration ->
        if (registration.location != AgentResourceLocation.PHONE &&
            registration.lastHeartbeatMillis > 0L &&
            nowMillis - registration.lastHeartbeatMillis > HEARTBEAT_TTL_MILLIS &&
            registration.status !in TERMINAL_OFFLINE_STATES
        ) registration.copy(status = AgentEndpointStatus.UNREACHABLE) else registration
    }

    @Synchronized
    fun routable(
        capabilities: Set<AgentCapability>,
        nowMillis: Long = System.currentTimeMillis()
    ): List<AgentRegistration> = list(nowMillis).filter { registration ->
        registration.status in ROUTABLE_STATES &&
            registration.hasCapacity &&
            registration.capabilities.containsAll(capabilities)
    }

    @Synchronized
    fun remove(agentId: String): Boolean {
        val existingRecords = list()
        val records = existingRecords.toMutableList()
        val removed = records.removeAll { it.agentId == agentId }
        if (removed) {
            save(records)
            GlobalConversationEventBus.publishCapabilityEvents(
                appContext,
                GlobalCapabilityObservationExtractor.agentMutations(existingRecords, records)
            )
        }
        return removed
    }

    @Synchronized
    fun clear() = database.clear()

    private fun save(records: List<AgentRegistration>) {
        val array = JSONArray()
        records.forEach { array.put(it.toJson()) }
        database.writeString(KEY_REGISTRY, array.toString())
    }

    private fun decode(raw: String): List<AgentRegistration> = runCatching {
        val array = JSONArray(raw)
        buildList {
            for (index in 0 until array.length()) array.optJSONObject(index)?.toRegistration()?.let(::add)
        }
    }.getOrDefault(emptyList())

    companion object {
        private const val DATABASE = "signalasi_agent_registry_v1"
        private const val UNUSED_LEGACY_PREFERENCES = "signalasi_agent_registry_v1_no_legacy"
        private const val KEY_REGISTRY = "registrations"
        private const val MAX_AGENTS = 512
        private const val HEARTBEAT_TTL_MILLIS = 10 * 60_000L
        private val ROUTABLE_STATES = setOf(AgentEndpointStatus.ONLINE, AgentEndpointStatus.IDLE, AgentEndpointStatus.BUSY)
        private val TERMINAL_OFFLINE_STATES = setOf(AgentEndpointStatus.OFFLINE, AgentEndpointStatus.UNREACHABLE)
    }
}

class AgentRunEventStore(context: Context) {
    private val database = AgentEncryptedDatabase(
        context.applicationContext,
        DATABASE,
        legacyPreferencesName = UNUSED_LEGACY_PREFERENCES
    )

    @Synchronized
    fun append(event: AgentRunControlEvent): Boolean {
        require(event.runId.isNotBlank() && event.taskId.isNotBlank()) { "Run and task ids must not be blank" }
        val events = events(event.runId).toMutableList()
        if (events.any { it.eventId == event.eventId }) return false
        val lastSequence = events.maxOfOrNull { it.sequence } ?: 0L
        require(event.sequence > lastSequence) { "Run event sequence must increase" }
        events += event
        database.writeString(runKey(event.runId), JSONArray().apply {
            events.takeLast(MAX_EVENTS_PER_RUN).forEach { put(it.toJson()) }
        }.toString())
        val runs = runIds().filterNot { it == event.runId } + event.runId
        val retained = runs.takeLast(MAX_RUNS)
        (runs - retained.toSet()).forEach { staleRunId -> database.remove(runKey(staleRunId)) }
        database.writeString(KEY_RUN_IDS, JSONArray(retained).toString())
        return true
    }

    @Synchronized
    fun appendNext(event: AgentRunControlEvent): AgentRunControlEvent? {
        val currentEvents = events(event.runId)
        val currentState = currentEvents.fold(AgentRunControlState.CREATED) { state, item ->
            reduce(state, item.type)
        }
        if (currentState in TERMINAL_STATES && event.type != AgentRunControlEventType.RUN_RECOVERED) return null
        val sequenced = event.copy(sequence = (currentEvents.lastOrNull()?.sequence ?: 0L) + 1L)
        return sequenced.takeIf(::append)
    }

    @Synchronized
    fun events(runId: String): List<AgentRunControlEvent> = decodeEvents(
        database.readString(runKey(runId), "[]")
    ).sortedBy { it.sequence }

    @Synchronized
    fun snapshot(runId: String): AgentRunControlSnapshot? {
        val events = events(runId)
        val last = events.lastOrNull() ?: return null
        return AgentRunControlSnapshot(
            runId = runId,
            taskId = last.taskId,
            state = events.fold(AgentRunControlState.CREATED) { state, event -> reduce(state, event.type) },
            agentId = last.agentId,
            deviceId = last.deviceId,
            lastSequence = last.sequence,
            lastEvent = last
        )
    }

    @Synchronized
    fun recoverableRuns(): List<AgentRunControlSnapshot> = runIds().mapNotNull(::snapshot).filter {
        it.state !in setOf(AgentRunControlState.COMPLETED, AgentRunControlState.FAILED, AgentRunControlState.CANCELLED)
    }

    @Synchronized
    fun clear() = database.clear()

    private fun runIds(): List<String> {
        val array = runCatching { JSONArray(database.readString(KEY_RUN_IDS, "[]")) }.getOrDefault(JSONArray())
        return buildList {
            for (index in 0 until array.length()) array.optString(index).takeIf(String::isNotBlank)?.let(::add)
        }
    }

    private fun decodeEvents(raw: String): List<AgentRunControlEvent> = runCatching {
        val array = JSONArray(raw)
        buildList {
            for (index in 0 until array.length()) array.optJSONObject(index)?.toRunEvent()?.let(::add)
        }
    }.getOrDefault(emptyList())

    private fun runKey(runId: String) = "run:$runId"

    companion object {
        private const val DATABASE = "signalasi_run_control_v1"
        private const val UNUSED_LEGACY_PREFERENCES = "signalasi_run_control_v1_no_legacy"
        private const val KEY_RUN_IDS = "run_ids"
        private const val MAX_RUNS = 500
        private const val MAX_EVENTS_PER_RUN = 2_000
        private val TERMINAL_STATES = setOf(
            AgentRunControlState.COMPLETED,
            AgentRunControlState.FAILED,
            AgentRunControlState.CANCELLED
        )

        fun reduce(current: AgentRunControlState, event: AgentRunControlEventType): AgentRunControlState = when (event) {
            AgentRunControlEventType.RUN_CREATED -> AgentRunControlState.CREATED
            AgentRunControlEventType.RUN_QUEUED -> AgentRunControlState.QUEUED
            AgentRunControlEventType.RUN_STARTED,
            AgentRunControlEventType.PLANNING,
            AgentRunControlEventType.THINKING,
            AgentRunControlEventType.AGENT_CONNECTED,
            AgentRunControlEventType.STEP_STARTED,
            AgentRunControlEventType.TOOL_STARTED,
            AgentRunControlEventType.TOOL_PROGRESS,
            AgentRunControlEventType.TOOL_COMPLETED,
            AgentRunControlEventType.RETRYING,
            AgentRunControlEventType.HANDOFF,
            AgentRunControlEventType.STEP_COMPLETED,
            AgentRunControlEventType.RUN_RECOVERED -> AgentRunControlState.RUNNING
            AgentRunControlEventType.TOOL_PERMISSION_REQUIRED,
            AgentRunControlEventType.WAITING_FOR_USER -> AgentRunControlState.WAITING_FOR_USER
            AgentRunControlEventType.WAITING_FOR_DEVICE -> AgentRunControlState.WAITING_FOR_DEVICE
            AgentRunControlEventType.PAUSED -> AgentRunControlState.PAUSED
            AgentRunControlEventType.RUN_COMPLETED -> AgentRunControlState.COMPLETED
            AgentRunControlEventType.RUN_FAILED -> AgentRunControlState.FAILED
            AgentRunControlEventType.RUN_CANCELLED -> AgentRunControlState.CANCELLED
        }.let { next ->
            if (current in TERMINAL_STATES &&
                event != AgentRunControlEventType.RUN_RECOVERED
            ) current else next
        }
    }
}

private fun AgentRegistration.toJson(): JSONObject = JSONObject()
    .put("agent_id", agentId)
    .put("installation_id", installationId)
    .put("device_id", deviceId)
    .put("provider_id", providerId)
    .put("display_name", displayName)
    .put("kind", kind.name)
    .put("location", location.name)
    .put("status", status.name)
    .put("capabilities", JSONArray(capabilities.map { it.name }))
    .put("tool_ids", JSONArray(toolIds.toList()))
    .put("permission_scopes", JSONArray(permissionScopes.toList()))
    .put("protocol", JSONObject()
        .put("preferred", protocol.preferred)
        .put("minimum", protocol.minimum)
        .put("maximum", protocol.maximum)
        .put("features", JSONArray(protocol.features.toList())))
    .put("connection_kind", connectionKind.name)
    .put("cost", cost.name)
    .put("latency", latency.name)
    .put("trust", trust.name)
    .put("active_runs", activeRuns)
    .put("max_parallel_runs", maxParallelRuns)
    .put("capabilities_hash", capabilitiesHash)
    .put("failure_domain", failureDomain)
    .put("last_heartbeat_millis", lastHeartbeatMillis)
    .put("updated_at_millis", updatedAtMillis)

private fun JSONObject.toRegistration(): AgentRegistration? = runCatching {
    val protocolJson = optJSONObject("protocol") ?: JSONObject()
    AgentRegistration(
        agentId = getString("agent_id"),
        installationId = getString("installation_id"),
        deviceId = getString("device_id"),
        providerId = optString("provider_id"),
        displayName = getString("display_name"),
        kind = enumValue(optString("kind"), AgentConnectorKind.AGENT),
        location = enumValue(optString("location"), AgentResourceLocation.CLOUD),
        status = enumValue(optString("status"), AgentEndpointStatus.OFFLINE),
        capabilities = optJSONArray("capabilities").enumSet(enumValues<AgentCapability>()),
        toolIds = optJSONArray("tool_ids").stringSet(),
        permissionScopes = optJSONArray("permission_scopes").stringSet(),
        protocol = AgentProtocolRange(
            preferred = protocolJson.optString("preferred", "1.0"),
            minimum = protocolJson.optString("minimum", "1.0"),
            maximum = protocolJson.optString("maximum", "1.0"),
            features = protocolJson.optJSONArray("features").stringSet()
        ),
        connectionKind = enumValue(optString("connection_kind"), AgentConnectionKind.HTTP),
        cost = enumValue(optString("cost"), AgentResourceCost.FREE),
        latency = enumValue(optString("latency"), AgentResourceLatency.NORMAL),
        trust = enumValue(optString("trust"), AgentResourceTrust.UNKNOWN),
        activeRuns = optInt("active_runs").coerceAtLeast(0),
        maxParallelRuns = optInt("max_parallel_runs", 1).coerceAtLeast(1),
        capabilitiesHash = optString("capabilities_hash"),
        failureDomain = optString("failure_domain"),
        lastHeartbeatMillis = optLong("last_heartbeat_millis"),
        updatedAtMillis = optLong("updated_at_millis", System.currentTimeMillis())
    )
}.getOrNull()

private fun AgentRunControlEvent.toJson(): JSONObject = JSONObject()
    .put("event_id", eventId)
    .put("conversation_id", conversationId)
    .put("message_id", messageId)
    .put("task_id", taskId)
    .put("run_id", runId)
    .put("step_id", stepId)
    .put("tool_call_id", toolCallId)
    .put("agent_id", agentId)
    .put("device_id", deviceId)
    .put("type", type.name)
    .put("sequence", sequence)
    .put("timestamp_millis", timestampMillis)
    .put("payload", JSONObject(payload))

private fun AgentHandoffRecord.toJson(): JSONObject = JSONObject()
    .put("handoff_id", request.handoffId)
    .put("conversation_id", request.conversationId)
    .put("task_id", request.taskId)
    .put("run_id", request.runId)
    .put("parent_run_id", request.parentRunId)
    .put("from_agent_id", request.fromAgentId)
    .put("to_agent_id", request.toAgentId)
    .put("return_to_agent_id", request.returnToAgentId)
    .put("reason", request.reason)
    .put("delivery_mode", request.deliveryMode.name)
    .put("required_capabilities", JSONArray(request.requiredCapabilities.map { it.name }))
    .put("artifact_ids", JSONArray(request.artifactIds))
    .put("checkpoint", JSONObject(request.checkpoint))
    .put("context", JSONObject(request.context))
    .put("created_at_millis", request.createdAtMillis)
    .put("state", state.name)
    .put("source_message_id", sourceMessageId)
    .put("result_summary", resultSummary)
    .put("updated_at_millis", updatedAtMillis)

private fun JSONObject.toHandoffRecord(): AgentHandoffRecord? = runCatching {
    AgentHandoffRecord(
        request = AgentHandoffRequest(
            handoffId = getString("handoff_id"),
            conversationId = optString("conversation_id"),
            taskId = getString("task_id"),
            runId = getString("run_id"),
            parentRunId = optString("parent_run_id").ifBlank { getString("run_id") },
            fromAgentId = getString("from_agent_id"),
            toAgentId = getString("to_agent_id"),
            returnToAgentId = optString("return_to_agent_id").ifBlank { getString("from_agent_id") },
            reason = optString("reason"),
            deliveryMode = enumValue(optString("delivery_mode"), AgentDeliveryMode.RESPOND),
            requiredCapabilities = optJSONArray("required_capabilities")
                .enumSet(enumValues<AgentCapability>()),
            artifactIds = optJSONArray("artifact_ids").stringSet().toList(),
            checkpoint = optJSONObject("checkpoint")?.toNativeMap().orEmpty(),
            context = optJSONObject("context")?.toNativeMap().orEmpty(),
            createdAtMillis = optLong("created_at_millis", System.currentTimeMillis())
        ),
        state = enumValue(optString("state"), AgentHandoffState.FAILED),
        sourceMessageId = optLong("source_message_id").coerceAtLeast(0L),
        resultSummary = optString("result_summary"),
        updatedAtMillis = optLong("updated_at_millis", System.currentTimeMillis())
    )
}.getOrNull()

private fun JSONObject.toRunEvent(): AgentRunControlEvent? = runCatching {
    AgentRunControlEvent(
        eventId = getString("event_id"),
        conversationId = optString("conversation_id"),
        messageId = optString("message_id"),
        taskId = getString("task_id"),
        runId = getString("run_id"),
        stepId = optString("step_id"),
        toolCallId = optString("tool_call_id"),
        agentId = getString("agent_id"),
        deviceId = getString("device_id"),
        type = enumValue(optString("type"), AgentRunControlEventType.RUN_FAILED),
        sequence = getLong("sequence"),
        timestampMillis = optLong("timestamp_millis", System.currentTimeMillis()),
        payload = optJSONObject("payload")?.toNativeMap().orEmpty()
    )
}.getOrNull()

private fun JSONObject.toNativeMap(): Map<String, Any?> = keys().asSequence().associateWith { key ->
    when (val value = opt(key)) {
        is JSONObject -> value.toNativeMap()
        is JSONArray -> buildList {
            for (index in 0 until value.length()) add(value.opt(index))
        }
        JSONObject.NULL -> null
        else -> value
    }
}

private inline fun <reified T : Enum<T>> enumValue(value: String, fallback: T): T =
    enumValues<T>().firstOrNull { it.name == value } ?: fallback

private fun JSONArray?.stringSet(): Set<String> = buildSet {
    val source = this@stringSet ?: return@buildSet
    for (index in 0 until source.length()) source.optString(index).takeIf(String::isNotBlank)?.let(::add)
}

private fun <T : Enum<T>> JSONArray?.enumSet(values: Array<T>): Set<T> = buildSet {
    val names = this@enumSet.stringSet()
    values.filterTo(this) { it.name in names }
}
