package com.signalasi.chat

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.Flow
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.Locale
import java.util.concurrent.TimeoutException
import kotlin.math.max

enum class AgentAdapterFamily(val wireName: String) {
    HERMES("hermes"),
    CODEX("codex"),
    CLAUDE_CODE("claude-code"),
    OPENCLAW("openclaw"),
    CLOUD_MODEL("cloud-model"),
    LOCAL_MODEL("local-model"),
    WINDOWS_HOST("windows-host"),
    ANDROID_DEVICE("android-device"),
    HOME_ASSISTANT("home-assistant"),
    CUSTOM("custom")
}

fun AgentRegistration.adapterFamily(): AgentAdapterFamily {
    val identity = listOf(adapterType, agentId, providerId, displayName)
        .joinToString(" ")
        .lowercase(Locale.ROOT)
    return when {
        "codex" in identity -> AgentAdapterFamily.CODEX
        "claude-code" in identity || "claude_code" in identity || "claude code" in identity ->
            AgentAdapterFamily.CLAUDE_CODE
        "openclaw" in identity -> AgentAdapterFamily.OPENCLAW
        "hermes" in identity -> AgentAdapterFamily.HERMES
        "windows-host" in identity || "windows_tools" in identity -> AgentAdapterFamily.WINDOWS_HOST
        "android-device" in identity || "android_tools" in identity -> AgentAdapterFamily.ANDROID_DEVICE
        "home-assistant" in identity || "home assistant" in identity -> AgentAdapterFamily.HOME_ASSISTANT
        "local-model" in identity || "local_model" in identity || "local-llm" in identity ||
            (kind == AgentConnectorKind.MODEL && location != AgentResourceLocation.CLOUD) ->
            AgentAdapterFamily.LOCAL_MODEL
        "cloud-model" in identity || "cloud_model" in identity ||
            (kind == AgentConnectorKind.MODEL && location == AgentResourceLocation.CLOUD) ->
            AgentAdapterFamily.CLOUD_MODEL
        else -> AgentAdapterFamily.CUSTOM
    }
}

fun AgentRegistration.runtimeHealthScope(): String = runtimeFailureDomain.ifBlank {
    val installation = installationId.ifBlank { deviceId }.ifBlank { providerId }.ifBlank { "unknown" }
    "$installation:${adapterFamily().wireName}"
}

enum class AgentProviderFailureKind {
    TIMEOUT,
    TRANSPORT_CRASH,
    AUTHORIZATION,
    PROTOCOL,
    UNAVAILABLE,
    EXECUTION
}

enum class AgentProviderCircuitState { CLOSED, OPEN, HALF_OPEN }

data class AgentProviderHealthSnapshot(
    val scopeId: String,
    val successes: Int = 0,
    val failures: Int = 0,
    val consecutiveFailures: Int = 0,
    val averageLatencyMillis: Long = 0L,
    val lastSuccessAtMillis: Long = 0L,
    val lastFailureAtMillis: Long = 0L,
    val circuitOpenUntilMillis: Long = 0L,
    val probeLeaseUntilMillis: Long = 0L,
    val lastFailureKind: AgentProviderFailureKind? = null,
    val lastOperation: String = ""
) {
    fun circuitState(nowMillis: Long): AgentProviderCircuitState = when {
        circuitOpenUntilMillis > nowMillis -> AgentProviderCircuitState.OPEN
        circuitOpenUntilMillis > 0L && consecutiveFailures > 0 -> AgentProviderCircuitState.HALF_OPEN
        else -> AgentProviderCircuitState.CLOSED
    }

    companion object {
        const val CIRCUIT_THRESHOLD = 3
    }
}

data class AgentProviderAttemptDecision(
    val allowed: Boolean,
    val scopeId: String,
    val state: AgentProviderCircuitState,
    val retryAtMillis: Long = 0L,
    val probe: Boolean = false
)

interface AgentProviderHealthLedger {
    fun acquire(registration: AgentRegistration, operation: String, nowMillis: Long): AgentProviderAttemptDecision
    fun recordSuccess(registration: AgentRegistration, operation: String, latencyMillis: Long, nowMillis: Long)
    fun recordFailure(
        registration: AgentRegistration,
        operation: String,
        kind: AgentProviderFailureKind,
        latencyMillis: Long,
        nowMillis: Long
    )
    fun snapshot(registration: AgentRegistration): AgentProviderHealthSnapshot
    fun snapshots(): List<AgentProviderHealthSnapshot>
    fun clear()
}

internal interface AgentProviderHealthPersistence {
    fun load(): Map<String, AgentProviderHealthSnapshot>
    fun save(values: Collection<AgentProviderHealthSnapshot>)
    fun clear()
}

private class InMemoryAgentProviderHealthPersistence : AgentProviderHealthPersistence {
    private var values = emptyMap<String, AgentProviderHealthSnapshot>()

    override fun load(): Map<String, AgentProviderHealthSnapshot> = values

    override fun save(values: Collection<AgentProviderHealthSnapshot>) {
        this.values = values.associateBy(AgentProviderHealthSnapshot::scopeId)
    }

    override fun clear() {
        values = emptyMap()
    }
}

private class EncryptedAgentProviderHealthPersistence(context: Context) : AgentProviderHealthPersistence {
    private val database = AgentEncryptedDatabase(context, DATABASE)

    override fun load(): Map<String, AgentProviderHealthSnapshot> {
        val array = runCatching { JSONArray(database.readString(KEY_STATES, "[]")) }.getOrDefault(JSONArray())
        return buildMap {
            for (index in 0 until array.length()) {
                array.optJSONObject(index)?.toProviderHealthSnapshot()?.let { put(it.scopeId, it) }
            }
        }
    }

    override fun save(values: Collection<AgentProviderHealthSnapshot>) {
        val array = JSONArray()
        values.sortedByDescending { max(it.lastFailureAtMillis, it.lastSuccessAtMillis) }
            .take(MAX_SCOPES)
            .forEach { array.put(it.toJson()) }
        database.writeString(KEY_STATES, array.toString())
    }

    override fun clear() = database.clear()

    companion object {
        const val DATABASE = "signalasi_agent_provider_health"
        private const val KEY_STATES = "states"
        private const val MAX_SCOPES = 256
    }
}

open class PersistentAgentProviderHealthLedger internal constructor(
    private val persistence: AgentProviderHealthPersistence
) : AgentProviderHealthLedger {
    override fun acquire(
        registration: AgentRegistration,
        operation: String,
        nowMillis: Long
    ): AgentProviderAttemptDecision = synchronized(PROCESS_LOCK) {
        val values = persistence.load().toMutableMap()
        val scopeId = registration.runtimeHealthScope()
        val current = values[scopeId] ?: AgentProviderHealthSnapshot(scopeId)
        val state = current.circuitState(nowMillis)
        when {
            state == AgentProviderCircuitState.OPEN -> AgentProviderAttemptDecision(
                allowed = false,
                scopeId = scopeId,
                state = state,
                retryAtMillis = current.circuitOpenUntilMillis
            )
            state == AgentProviderCircuitState.HALF_OPEN && current.probeLeaseUntilMillis > nowMillis ->
                AgentProviderAttemptDecision(
                    allowed = false,
                    scopeId = scopeId,
                    state = state,
                    retryAtMillis = current.probeLeaseUntilMillis
                )
            state == AgentProviderCircuitState.HALF_OPEN -> {
                val leased = current.copy(
                    probeLeaseUntilMillis = nowMillis + HALF_OPEN_PROBE_LEASE_MILLIS,
                    lastOperation = operation
                )
                values[scopeId] = leased
                persistence.save(values.values)
                AgentProviderAttemptDecision(true, scopeId, state, probe = true)
            }
            else -> AgentProviderAttemptDecision(true, scopeId, state)
        }
    }

    override fun recordSuccess(
        registration: AgentRegistration,
        operation: String,
        latencyMillis: Long,
        nowMillis: Long
    ) = synchronized(PROCESS_LOCK) {
        val values = persistence.load().toMutableMap()
        val scopeId = registration.runtimeHealthScope()
        val current = values[scopeId] ?: AgentProviderHealthSnapshot(scopeId)
        val clearsFailure = current.consecutiveFailures == 0 ||
            current.lastOperation == operation ||
            (operation in HEALTH_CHECK_OPERATIONS &&
                current.lastFailureKind?.let(CONNECTIVITY_FAILURES::contains) == true)
        values[scopeId] = current.copy(
            successes = current.successes + 1,
            consecutiveFailures = if (clearsFailure) 0 else current.consecutiveFailures,
            averageLatencyMillis = rollingAverage(current, latencyMillis),
            lastSuccessAtMillis = nowMillis,
            circuitOpenUntilMillis = if (clearsFailure) 0L else current.circuitOpenUntilMillis,
            probeLeaseUntilMillis = if (clearsFailure) 0L else current.probeLeaseUntilMillis,
            lastFailureKind = if (clearsFailure) null else current.lastFailureKind,
            lastOperation = if (clearsFailure) operation else current.lastOperation
        )
        persistence.save(values.values)
    }

    override fun recordFailure(
        registration: AgentRegistration,
        operation: String,
        kind: AgentProviderFailureKind,
        latencyMillis: Long,
        nowMillis: Long
    ) = synchronized(PROCESS_LOCK) {
        val values = persistence.load().toMutableMap()
        val scopeId = registration.runtimeHealthScope()
        val current = values[scopeId] ?: AgentProviderHealthSnapshot(scopeId)
        val consecutive = current.consecutiveFailures + 1
        val threshold = threshold(kind)
        val openUntil = if (consecutive >= threshold) {
            nowMillis + cooldownMillis(kind, consecutive - threshold)
        } else {
            0L
        }
        values[scopeId] = current.copy(
            failures = current.failures + 1,
            consecutiveFailures = consecutive,
            averageLatencyMillis = rollingAverage(current, latencyMillis),
            lastFailureAtMillis = nowMillis,
            circuitOpenUntilMillis = openUntil,
            probeLeaseUntilMillis = 0L,
            lastFailureKind = kind,
            lastOperation = operation
        )
        persistence.save(values.values)
    }

    override fun snapshot(registration: AgentRegistration): AgentProviderHealthSnapshot = synchronized(PROCESS_LOCK) {
        val scopeId = registration.runtimeHealthScope()
        persistence.load()[scopeId] ?: AgentProviderHealthSnapshot(scopeId)
    }

    override fun snapshots(): List<AgentProviderHealthSnapshot> = synchronized(PROCESS_LOCK) {
        persistence.load().values.sortedBy(AgentProviderHealthSnapshot::scopeId)
    }

    override fun clear() = synchronized(PROCESS_LOCK) { persistence.clear() }

    private fun rollingAverage(current: AgentProviderHealthSnapshot, latencyMillis: Long): Long {
        val sample = latencyMillis.coerceAtLeast(0L)
        val count = current.successes + current.failures
        return if (count <= 0) sample else ((current.averageLatencyMillis * count) + sample) / (count + 1)
    }

    private fun threshold(kind: AgentProviderFailureKind): Int = when (kind) {
        AgentProviderFailureKind.TRANSPORT_CRASH,
        AgentProviderFailureKind.AUTHORIZATION,
        AgentProviderFailureKind.PROTOCOL -> 1
        AgentProviderFailureKind.TIMEOUT -> 2
        AgentProviderFailureKind.UNAVAILABLE,
        AgentProviderFailureKind.EXECUTION -> AgentProviderHealthSnapshot.CIRCUIT_THRESHOLD
    }

    private fun cooldownMillis(kind: AgentProviderFailureKind, excessFailures: Int): Long {
        val base = when (kind) {
            AgentProviderFailureKind.AUTHORIZATION -> 15 * 60_000L
            AgentProviderFailureKind.PROTOCOL -> 5 * 60_000L
            AgentProviderFailureKind.TRANSPORT_CRASH -> 2 * 60_000L
            AgentProviderFailureKind.TIMEOUT -> 60_000L
            AgentProviderFailureKind.UNAVAILABLE -> 45_000L
            AgentProviderFailureKind.EXECUTION -> 30_000L
        }
        return (base * (1L shl excessFailures.coerceIn(0, 4))).coerceAtMost(MAX_COOLDOWN_MILLIS)
    }

    companion object {
        private val PROCESS_LOCK = Any()
        private const val HALF_OPEN_PROBE_LEASE_MILLIS = 30_000L
        private const val MAX_COOLDOWN_MILLIS = 30 * 60_000L
        private val HEALTH_CHECK_OPERATIONS = setOf("connect", "status")
        private val CONNECTIVITY_FAILURES = setOf(
            AgentProviderFailureKind.TRANSPORT_CRASH,
            AgentProviderFailureKind.AUTHORIZATION,
            AgentProviderFailureKind.PROTOCOL,
            AgentProviderFailureKind.UNAVAILABLE
        )
    }
}

class InMemoryAgentProviderHealthLedger : PersistentAgentProviderHealthLedger(
    InMemoryAgentProviderHealthPersistence()
)

class EncryptedAgentProviderHealthLedger(context: Context) : PersistentAgentProviderHealthLedger(
    EncryptedAgentProviderHealthPersistence(context.applicationContext)
)

class AgentProviderCircuitOpenException(
    val scopeId: String,
    val retryAtMillis: Long
) : IllegalStateException("Agent runtime is temporarily unavailable")

interface ClassifiedAgentAdapter : AgentAdapter {
    val family: AgentAdapterFamily
    val healthScopeId: String
}

abstract class HealthIsolatedAgentAdapter(
    private val delegate: AgentAdapter,
    final override val family: AgentAdapterFamily,
    private val healthLedger: AgentProviderHealthLedger,
    private val clock: () -> Long = System::currentTimeMillis
) : ClassifiedAgentAdapter {
    override val registration: AgentRegistration get() = delegate.registration
    override val healthScopeId: String get() = registration.runtimeHealthScope()

    override suspend fun connect(): AgentProtocolAgreement = guarded("connect") { delegate.connect() }

    override suspend fun disconnect() = delegate.disconnect()

    override suspend fun status(): AgentRegistration = guarded("status") { delegate.status() }

    override suspend fun startRun(request: AgentRunRequest): AgentRunHandle =
        guarded("start_run", recordSuccess = false) { delegate.startRun(request) }

    override suspend fun sendMessage(runId: String, message: AgentControlMessage) =
        guarded("send_message") { delegate.sendMessage(runId, message) }

    override suspend fun cancelRun(runId: String) = guarded("cancel_run") { delegate.cancelRun(runId) }

    override fun observeEvents(runId: String): Flow<AgentRunControlEvent> = delegate.observeEvents(runId)

    override suspend fun recoverRuns(): List<AgentRecoverableRun> = guarded("recover_runs") {
        delegate.recoverRuns()
    }

    private suspend fun <T> guarded(
        operation: String,
        recordSuccess: Boolean = true,
        block: suspend () -> T
    ): T {
        val startedAt = clock()
        val decision = healthLedger.acquire(registration, operation, startedAt)
        if (!decision.allowed) throw AgentProviderCircuitOpenException(decision.scopeId, decision.retryAtMillis)
        return try {
            block().also {
                val finishedAt = clock()
                if (recordSuccess) {
                    healthLedger.recordSuccess(registration, operation, finishedAt - startedAt, finishedAt)
                }
            }
        } catch (timeout: TimeoutCancellationException) {
            val finishedAt = clock()
            healthLedger.recordFailure(
                registration,
                operation,
                AgentProviderFailureKind.TIMEOUT,
                finishedAt - startedAt,
                finishedAt
            )
            throw timeout
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            val finishedAt = clock()
            healthLedger.recordFailure(
                registration,
                operation,
                AgentProviderFailureClassifier.from(error),
                finishedAt - startedAt,
                finishedAt
            )
            throw error
        }
    }
}

class HermesAgentAdapter internal constructor(delegate: AgentAdapter, ledger: AgentProviderHealthLedger) :
    HealthIsolatedAgentAdapter(delegate, AgentAdapterFamily.HERMES, ledger)

class CodexAgentAdapter internal constructor(delegate: AgentAdapter, ledger: AgentProviderHealthLedger) :
    HealthIsolatedAgentAdapter(delegate, AgentAdapterFamily.CODEX, ledger)

class ClaudeCodeAgentAdapter internal constructor(delegate: AgentAdapter, ledger: AgentProviderHealthLedger) :
    HealthIsolatedAgentAdapter(delegate, AgentAdapterFamily.CLAUDE_CODE, ledger)

class OpenClawAgentAdapter internal constructor(delegate: AgentAdapter, ledger: AgentProviderHealthLedger) :
    HealthIsolatedAgentAdapter(delegate, AgentAdapterFamily.OPENCLAW, ledger)

class CloudModelAgentAdapter internal constructor(delegate: AgentAdapter, ledger: AgentProviderHealthLedger) :
    HealthIsolatedAgentAdapter(delegate, AgentAdapterFamily.CLOUD_MODEL, ledger)

class LocalModelAgentAdapter internal constructor(delegate: AgentAdapter, ledger: AgentProviderHealthLedger) :
    HealthIsolatedAgentAdapter(delegate, AgentAdapterFamily.LOCAL_MODEL, ledger)

class WindowsHostAgentAdapter internal constructor(delegate: AgentAdapter, ledger: AgentProviderHealthLedger) :
    HealthIsolatedAgentAdapter(delegate, AgentAdapterFamily.WINDOWS_HOST, ledger)

class AndroidDeviceAgentAdapter internal constructor(delegate: AgentAdapter, ledger: AgentProviderHealthLedger) :
    HealthIsolatedAgentAdapter(delegate, AgentAdapterFamily.ANDROID_DEVICE, ledger)

class HomeAssistantAgentAdapter internal constructor(delegate: AgentAdapter, ledger: AgentProviderHealthLedger) :
    HealthIsolatedAgentAdapter(delegate, AgentAdapterFamily.HOME_ASSISTANT, ledger)

class CustomAgentAdapter internal constructor(delegate: AgentAdapter, ledger: AgentProviderHealthLedger) :
    HealthIsolatedAgentAdapter(delegate, AgentAdapterFamily.CUSTOM, ledger)

object AgentProductionAdapterFactory {
    fun create(
        registration: AgentRegistration,
        transport: AgentAdapterTransport,
        localProtocol: AgentProtocolRange,
        runStartReceipts: AgentRunStartReceiptStore,
        healthLedger: AgentProviderHealthLedger
    ): ClassifiedAgentAdapter {
        val delegate = TransportBackedAgentAdapter(registration, transport, localProtocol, runStartReceipts)
        return when (registration.adapterFamily()) {
            AgentAdapterFamily.HERMES -> HermesAgentAdapter(delegate, healthLedger)
            AgentAdapterFamily.CODEX -> CodexAgentAdapter(delegate, healthLedger)
            AgentAdapterFamily.CLAUDE_CODE -> ClaudeCodeAgentAdapter(delegate, healthLedger)
            AgentAdapterFamily.OPENCLAW -> OpenClawAgentAdapter(delegate, healthLedger)
            AgentAdapterFamily.CLOUD_MODEL -> CloudModelAgentAdapter(delegate, healthLedger)
            AgentAdapterFamily.LOCAL_MODEL -> LocalModelAgentAdapter(delegate, healthLedger)
            AgentAdapterFamily.WINDOWS_HOST -> WindowsHostAgentAdapter(delegate, healthLedger)
            AgentAdapterFamily.ANDROID_DEVICE -> AndroidDeviceAgentAdapter(delegate, healthLedger)
            AgentAdapterFamily.HOME_ASSISTANT -> HomeAssistantAgentAdapter(delegate, healthLedger)
            AgentAdapterFamily.CUSTOM -> CustomAgentAdapter(delegate, healthLedger)
        }
    }
}

internal object AgentProviderFailureClassifier {
    fun from(error: Throwable): AgentProviderFailureKind {
        if (error is TimeoutException) return AgentProviderFailureKind.TIMEOUT
        val message = generateSequence(error) { it.cause }
            .joinToString(" ") { it.message.orEmpty() }
        return classify(message, error is IOException || "crash" in error.javaClass.simpleName.lowercase(Locale.ROOT))
    }

    fun from(result: AgentActionResult): AgentProviderFailureKind {
        val metadata = result.metadata.entries.joinToString(" ") { "${it.key}=${it.value}" }
        return classify("${result.message} $metadata", false)
    }

    private fun classify(raw: String, transportException: Boolean): AgentProviderFailureKind {
        val message = raw.lowercase(Locale.ROOT)
        return when {
            "timeout" in message || "timed out" in message -> AgentProviderFailureKind.TIMEOUT
            "unauthorized" in message || "forbidden" in message || "permission" in message ||
                "credential" in message || "api key" in message -> AgentProviderFailureKind.AUTHORIZATION
            "protocol" in message || "version" in message && "compatible" in message ->
                AgentProviderFailureKind.PROTOCOL
            transportException || "connection reset" in message || "broken pipe" in message ||
                "process exited" in message || "process crashed" in message ->
                AgentProviderFailureKind.TRANSPORT_CRASH
            "unavailable" in message || "offline" in message || "not connected" in message ||
                "needs_setup" in message || "not configured" in message -> AgentProviderFailureKind.UNAVAILABLE
            else -> AgentProviderFailureKind.EXECUTION
        }
    }
}

private fun AgentProviderHealthSnapshot.toJson(): JSONObject = JSONObject()
    .put("scope_id", scopeId)
    .put("successes", successes)
    .put("failures", failures)
    .put("consecutive_failures", consecutiveFailures)
    .put("average_latency_millis", averageLatencyMillis)
    .put("last_success_at_millis", lastSuccessAtMillis)
    .put("last_failure_at_millis", lastFailureAtMillis)
    .put("circuit_open_until_millis", circuitOpenUntilMillis)
    .put("probe_lease_until_millis", probeLeaseUntilMillis)
    .put("last_failure_kind", lastFailureKind?.name.orEmpty())
    .put("last_operation", lastOperation)

private fun JSONObject.toProviderHealthSnapshot(): AgentProviderHealthSnapshot? = runCatching {
    val scopeId = getString("scope_id").trim()
    require(scopeId.isNotBlank())
    AgentProviderHealthSnapshot(
        scopeId = scopeId,
        successes = optInt("successes").coerceAtLeast(0),
        failures = optInt("failures").coerceAtLeast(0),
        consecutiveFailures = optInt("consecutive_failures").coerceAtLeast(0),
        averageLatencyMillis = optLong("average_latency_millis").coerceAtLeast(0L),
        lastSuccessAtMillis = optLong("last_success_at_millis").coerceAtLeast(0L),
        lastFailureAtMillis = optLong("last_failure_at_millis").coerceAtLeast(0L),
        circuitOpenUntilMillis = optLong("circuit_open_until_millis").coerceAtLeast(0L),
        probeLeaseUntilMillis = optLong("probe_lease_until_millis").coerceAtLeast(0L),
        lastFailureKind = optString("last_failure_kind").takeIf(String::isNotBlank)
            ?.let { runCatching { AgentProviderFailureKind.valueOf(it) }.getOrNull() },
        lastOperation = optString("last_operation")
    )
}.getOrNull()
