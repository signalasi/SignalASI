package com.signalasi.chat

import android.content.Context
import android.net.LocalSocket
import android.net.LocalSocketAddress
import org.json.JSONArray
import org.json.JSONObject
import java.io.Closeable
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.net.SocketTimeoutException
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

enum class AgentRuntimeGuestMessageType(val wireValue: String) {
    HELLO("hello"),
    HELLO_ACK("hello_ack"),
    EXECUTE("execute"),
    PROGRESS("progress"),
    CANCEL("cancel"),
    CANCELLED("cancelled"),
    RESULT("result"),
    ERROR("error"),
    HEARTBEAT("heartbeat"),
    HEARTBEAT_ACK("heartbeat_ack")
}

data class AgentRuntimeResourceLimits(
    val wallClockMillis: Long = 60_000L,
    val cpuMillis: Long = 45_000L,
    val memoryBytes: Long = 512L * 1024L * 1024L,
    val diskBytes: Long = 512L * 1024L * 1024L,
    val maxProcesses: Int = 64,
    val maxOutputBytes: Long = 512L * 1024L,
    val maxArtifactBytes: Long = 256L * 1024L * 1024L
) {
    fun validated(): AgentRuntimeResourceLimits {
        require(wallClockMillis in 100L..30L * 60_000L) { "Runtime wall-clock limit is invalid" }
        require(cpuMillis in 100L..wallClockMillis) { "Runtime CPU limit is invalid" }
        require(memoryBytes in 32L * 1024L * 1024L..4L * 1024L * 1024L * 1024L) {
            "Runtime memory limit is invalid"
        }
        require(diskBytes in 8L * 1024L * 1024L..8L * 1024L * 1024L * 1024L) {
            "Runtime disk limit is invalid"
        }
        require(maxProcesses in 1..512) { "Runtime process limit is invalid" }
        require(maxOutputBytes in 1_024L..4L * 1024L * 1024L) { "Runtime output limit is invalid" }
        require(maxArtifactBytes in 1_024L..2L * 1024L * 1024L * 1024L) {
            "Runtime artifact limit is invalid"
        }
        return this
    }
}

data class AgentRuntimeProgress(
    val requestId: String,
    val sequence: Long,
    val stage: String,
    val message: String,
    val percent: Int? = null,
    val timestampMillis: Long = System.currentTimeMillis()
)

data class AgentRuntimeBridgeHealth(
    val ready: Boolean,
    val guestApiVersion: Int = 0,
    val guestVersion: String = "",
    val capabilities: Set<String> = emptySet(),
    val reason: String = ""
)

data class AgentRuntimeGuestEnvelope(
    val messageId: String = UUID.randomUUID().toString(),
    val requestId: String,
    val type: AgentRuntimeGuestMessageType,
    val sequence: Long,
    val timestampMillis: Long = System.currentTimeMillis(),
    val payload: AgentNativeJsonObject = emptyMap(),
    val protocolVersion: Int = AgentRuntimeGuestProtocol.VERSION,
    val mac: String = ""
)

object AgentRuntimeGuestProtocol {
    const val VERSION = 1
    const val MAX_FRAME_BYTES = 1024 * 1024
    const val MAX_CLOCK_SKEW_MILLIS = 5 * 60_000L

    fun sign(envelope: AgentRuntimeGuestEnvelope, sessionKey: ByteArray): AgentRuntimeGuestEnvelope {
        require(sessionKey.size >= 32) { "Runtime session key is too short" }
        return envelope.copy(mac = hmac(unsignedPayload(envelope), sessionKey))
    }

    fun verify(
        envelope: AgentRuntimeGuestEnvelope,
        sessionKey: ByteArray,
        nowMillis: Long = System.currentTimeMillis()
    ): Boolean {
        if (envelope.protocolVersion != VERSION || envelope.mac.isBlank() ||
            envelope.messageId.isBlank() || envelope.requestId.isBlank() || envelope.sequence < 1L
        ) return false
        if (kotlin.math.abs(nowMillis - envelope.timestampMillis) > MAX_CLOCK_SKEW_MILLIS) return false
        val expected = hmac(unsignedPayload(envelope.copy(mac = "")), sessionKey)
        return MessageDigest.isEqual(
            expected.toByteArray(Charsets.US_ASCII),
            envelope.mac.toByteArray(Charsets.US_ASCII)
        )
    }

    fun encode(envelope: AgentRuntimeGuestEnvelope): ByteArray = JSONObject()
        .put("protocol_version", envelope.protocolVersion)
        .put("message_id", envelope.messageId)
        .put("request_id", envelope.requestId)
        .put("type", envelope.type.wireValue)
        .put("sequence", envelope.sequence)
        .put("timestamp_millis", envelope.timestampMillis)
        .put("payload", JSONObject(canonicalJson(envelope.payload)))
        .put("mac", envelope.mac)
        .toString()
        .toByteArray(Charsets.UTF_8)
        .also { require(it.size <= MAX_FRAME_BYTES) { "Runtime protocol frame is too large" } }

    fun decode(bytes: ByteArray): AgentRuntimeGuestEnvelope {
        require(bytes.size in 1..MAX_FRAME_BYTES) { "Runtime protocol frame size is invalid" }
        val json = JSONObject(String(bytes, Charsets.UTF_8))
        val typeValue = json.getString("type")
        val type = AgentRuntimeGuestMessageType.entries.firstOrNull { it.wireValue == typeValue }
            ?: error("Runtime protocol message type is invalid")
        return AgentRuntimeGuestEnvelope(
            protocolVersion = json.getInt("protocol_version"),
            messageId = json.getString("message_id"),
            requestId = json.getString("request_id"),
            type = type,
            sequence = json.getLong("sequence"),
            timestampMillis = json.getLong("timestamp_millis"),
            payload = json.optJSONObject("payload").toNativeMap(),
            mac = json.getString("mac")
        )
    }

    private fun unsignedPayload(envelope: AgentRuntimeGuestEnvelope): ByteArray = buildString {
        append(envelope.protocolVersion).append('\n')
        append(envelope.messageId).append('\n')
        append(envelope.requestId).append('\n')
        append(envelope.type.wireValue).append('\n')
        append(envelope.sequence).append('\n')
        append(envelope.timestampMillis).append('\n')
        append(canonicalJson(envelope.payload))
    }.toByteArray(Charsets.UTF_8)

    private fun hmac(payload: ByteArray, sessionKey: ByteArray): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(sessionKey, "HmacSHA256"))
        return Base64.getEncoder().encodeToString(mac.doFinal(payload))
    }

    internal fun canonicalJson(value: Any?): String = when (value) {
        null -> "null"
        is String -> JSONObject.quote(value)
        is Boolean, is Byte, is Short, is Int, is Long -> value.toString()
        is Float -> if (value.isFinite()) value.toString() else error("Non-finite JSON number")
        is Double -> if (value.isFinite()) value.toString() else error("Non-finite JSON number")
        is Number -> value.toString()
        is Map<*, *> -> value.entries
            .map { entry -> (entry.key as? String ?: error("Runtime payload key must be a string")) to entry.value }
            .sortedBy { it.first }
            .joinToString(prefix = "{", postfix = "}") { (key, item) ->
                "${JSONObject.quote(key)}:${canonicalJson(item)}"
            }
        is Iterable<*> -> value.joinToString(prefix = "[", postfix = "]") { canonicalJson(it) }
        is Array<*> -> value.joinToString(prefix = "[", postfix = "]") { canonicalJson(it) }
        else -> error("Unsupported runtime payload value: ${value::class.java.simpleName}")
    }

    private fun JSONObject?.toNativeMap(): AgentNativeJsonObject {
        val source = this ?: return emptyMap()
        return buildMap {
            source.keys().asSequence().forEach { key -> put(key, source.opt(key).toNativeValue()) }
        }
    }

    private fun Any?.toNativeValue(): Any? = when (this) {
        null, JSONObject.NULL -> null
        is JSONObject -> toNativeMap()
        is JSONArray -> buildList { for (index in 0 until length()) add(opt(index).toNativeValue()) }
        is Boolean, is String, is Number -> this
        else -> toString()
    }
}

interface AgentRuntimeGuestChannel : Closeable {
    fun send(envelope: AgentRuntimeGuestEnvelope, sessionKey: ByteArray)
    fun receive(timeoutMillis: Long, sessionKey: ByteArray): AgentRuntimeGuestEnvelope
}

class StreamAgentRuntimeGuestChannel(
    input: InputStream,
    output: OutputStream,
    private val timeoutSetter: (Int) -> Unit = {}
) : AgentRuntimeGuestChannel {
    private val dataInput = DataInputStream(input.buffered())
    private val dataOutput = DataOutputStream(output.buffered())
    private val writeLock = Any()

    override fun send(envelope: AgentRuntimeGuestEnvelope, sessionKey: ByteArray) {
        val bytes = AgentRuntimeGuestProtocol.encode(AgentRuntimeGuestProtocol.sign(envelope, sessionKey))
        synchronized(writeLock) {
            dataOutput.writeInt(bytes.size)
            dataOutput.write(bytes)
            dataOutput.flush()
        }
    }

    override fun receive(timeoutMillis: Long, sessionKey: ByteArray): AgentRuntimeGuestEnvelope {
        timeoutSetter(timeoutMillis.coerceIn(1L, Int.MAX_VALUE.toLong()).toInt())
        val size = dataInput.readInt()
        require(size in 1..AgentRuntimeGuestProtocol.MAX_FRAME_BYTES) { "Runtime protocol frame size is invalid" }
        val bytes = ByteArray(size)
        dataInput.readFully(bytes)
        val envelope = AgentRuntimeGuestProtocol.decode(bytes)
        require(AgentRuntimeGuestProtocol.verify(envelope, sessionKey)) { "Runtime protocol authentication failed" }
        return envelope
    }

    override fun close() {
        runCatching { dataInput.close() }
        runCatching { dataOutput.close() }
    }
}

fun interface AgentRuntimeGuestChannelFactory {
    fun open(): AgentRuntimeGuestChannel
}

class LocalSocketAgentRuntimeGuestChannelFactory(private val socketPath: String) : AgentRuntimeGuestChannelFactory {
    override fun open(): AgentRuntimeGuestChannel {
        val socket = LocalSocket()
        socket.connect(LocalSocketAddress(socketPath, LocalSocketAddress.Namespace.FILESYSTEM))
        return object : AgentRuntimeGuestChannel {
            private val delegate = StreamAgentRuntimeGuestChannel(
                socket.inputStream,
                socket.outputStream,
                timeoutSetter = socket::setSoTimeout
            )

            override fun send(envelope: AgentRuntimeGuestEnvelope, sessionKey: ByteArray) =
                delegate.send(envelope, sessionKey)

            override fun receive(timeoutMillis: Long, sessionKey: ByteArray): AgentRuntimeGuestEnvelope =
                delegate.receive(timeoutMillis, sessionKey)

            override fun close() {
                runCatching { delegate.close() }
                runCatching { socket.close() }
            }
        }
    }
}

class AgentRuntimeGuestBridge(
    private val channelFactory: AgentRuntimeGuestChannelFactory,
    private val sessionKeyProvider: () -> ByteArray
) : AgentOnDeviceRuntimeBridge {
    @Volatile private var cachedHealth: AgentRuntimeBridgeHealth? = null
    @Volatile private var cachedHealthAtMillis: Long = 0L

    override fun health(): AgentRuntimeBridgeHealth {
        val now = System.currentTimeMillis()
        cachedHealth?.takeIf { now - cachedHealthAtMillis < HEALTH_CACHE_MILLIS }?.let { return it }
        val health = runCatching {
            channelFactory.open().use { channel -> handshake(channel, "health-${UUID.randomUUID()}").health }
        }.getOrElse { error ->
            AgentRuntimeBridgeHealth(false, reason = error.message ?: "Guest runtime is unavailable")
        }
        cachedHealth = health
        cachedHealthAtMillis = now
        return health
    }

    override fun execute(request: AgentRuntimeExecutionRequest): AgentRuntimeExecutionResponse {
        val key = sessionKeyProvider()
        val startedAt = System.currentTimeMillis()
        channelFactory.open().use { channel ->
            val handshake = handshake(channel, request.requestId)
            check(handshake.health.ready) {
                handshake.health.reason.ifBlank { "Guest runtime handshake failed" }
            }
            var lastReceivedSequence = handshake.responseSequence
            val sequence = AtomicLong(2L)
            if (request.cancellationToken.isCancellationRequested) throw AgentNativeToolCancelledException()
            channel.send(executeEnvelope(request, sequence.getAndIncrement()), key)
            val registration = request.cancellationToken.invokeOnCancellation {
                runCatching {
                    channel.send(
                        AgentRuntimeGuestEnvelope(
                            requestId = request.requestId,
                            type = AgentRuntimeGuestMessageType.CANCEL,
                            sequence = sequence.getAndIncrement()
                        ),
                        key
                    )
                }
            }
            try {
                val deadline = startedAt + request.timeoutMillis
                while (true) {
                    if (request.cancellationToken.isCancellationRequested) throw AgentNativeToolCancelledException()
                    val remaining = deadline - System.currentTimeMillis()
                    if (remaining <= 0L) throw AgentNativeToolTimeoutException()
                    val envelope = try {
                        channel.receive(remaining, key)
                    } catch (_: SocketTimeoutException) {
                        throw AgentNativeToolTimeoutException()
                    }
                    if (envelope.requestId != request.requestId) continue
                    check(envelope.sequence > lastReceivedSequence) {
                        "Guest runtime returned a replayed or out-of-order frame"
                    }
                    lastReceivedSequence = envelope.sequence
                    when (envelope.type) {
                        AgentRuntimeGuestMessageType.PROGRESS -> request.progressListener(
                            AgentRuntimeProgress(
                                requestId = request.requestId,
                                sequence = envelope.sequence,
                                stage = envelope.payload["stage"]?.toString().orEmpty(),
                                message = envelope.payload["message"]?.toString().orEmpty(),
                                percent = (envelope.payload["percent"] as? Number)?.toInt()?.coerceIn(0, 100),
                                timestampMillis = envelope.timestampMillis
                            )
                        )
                        AgentRuntimeGuestMessageType.RESULT -> return responseFrom(envelope, startedAt)
                        AgentRuntimeGuestMessageType.CANCELLED -> throw AgentNativeToolCancelledException()
                        AgentRuntimeGuestMessageType.ERROR -> error(
                            envelope.payload["message"]?.toString().orEmpty().ifBlank { "Guest runtime failed" }
                        )
                        else -> Unit
                    }
                }
            } finally {
                registration.dispose()
            }
        }
    }

    private fun handshake(channel: AgentRuntimeGuestChannel, requestId: String): HandshakeResult {
        val key = sessionKeyProvider()
        channel.send(
            AgentRuntimeGuestEnvelope(
                requestId = requestId,
                type = AgentRuntimeGuestMessageType.HELLO,
                sequence = 1L,
                payload = mapOf(
                    "host_api_version" to AgentRuntimeGuestProtocol.VERSION,
                    "nonce" to UUID.randomUUID().toString()
                )
            ),
            key
        )
        val response = channel.receive(HANDSHAKE_TIMEOUT_MILLIS, key)
        check(response.requestId == requestId && response.type == AgentRuntimeGuestMessageType.HELLO_ACK) {
            "Guest runtime returned an invalid handshake"
        }
        val guestApi = (response.payload["guest_api_version"] as? Number)?.toInt() ?: 0
        val capabilities = (response.payload["capabilities"] as? Iterable<*>)
            ?.mapNotNull { it?.toString()?.takeIf(String::isNotBlank) }
            ?.toSet()
            .orEmpty()
        return HandshakeResult(
            health = AgentRuntimeBridgeHealth(
                ready = guestApi == AgentRuntimeGuestProtocol.VERSION,
                guestApiVersion = guestApi,
                guestVersion = response.payload["guest_version"]?.toString().orEmpty(),
                capabilities = capabilities,
                reason = if (guestApi == AgentRuntimeGuestProtocol.VERSION) "" else "Guest API version is incompatible"
            ),
            responseSequence = response.sequence
        )
    }

    private fun executeEnvelope(request: AgentRuntimeExecutionRequest, sequence: Long): AgentRuntimeGuestEnvelope {
        val limits = request.resourceLimits.validated()
        return AgentRuntimeGuestEnvelope(
            requestId = request.requestId,
            type = AgentRuntimeGuestMessageType.EXECUTE,
            sequence = sequence,
            payload = mapOf(
                "language" to request.language.wireValue,
                "source" to request.source,
                "arguments" to request.arguments,
                "workspace_id" to request.workspaceId,
                "host_workspace_path" to request.hostWorkspacePath,
                "artifact_paths" to request.artifactPaths,
                "network" to mapOf(
                    "enabled" to request.networkEnabled,
                    "allowed_domains" to request.allowedNetworkDomains
                ),
                "limits" to mapOf(
                    "wall_clock_ms" to limits.wallClockMillis,
                    "cpu_ms" to limits.cpuMillis,
                    "memory_bytes" to limits.memoryBytes,
                    "disk_bytes" to limits.diskBytes,
                    "max_processes" to limits.maxProcesses,
                    "max_output_bytes" to limits.maxOutputBytes,
                    "max_artifact_bytes" to limits.maxArtifactBytes
                )
            )
        )
    }

    private fun responseFrom(envelope: AgentRuntimeGuestEnvelope, startedAt: Long): AgentRuntimeExecutionResponse =
        AgentRuntimeExecutionResponse(
            exitCode = (envelope.payload["exit_code"] as? Number)?.toInt() ?: -1,
            stdout = envelope.payload["stdout"]?.toString().orEmpty(),
            stderr = envelope.payload["stderr"]?.toString().orEmpty(),
            durationMillis = (envelope.payload["duration_ms"] as? Number)?.toLong()
                ?: (System.currentTimeMillis() - startedAt),
            artifacts = (envelope.payload["artifacts"] as? Iterable<*>)
                ?.mapNotNull { it as? Map<*, *> }
                ?.map { item -> item.entries.associate { it.key.toString() to it.value } }
                .orEmpty(),
            requestId = envelope.requestId
        )

    companion object {
        private const val HANDSHAKE_TIMEOUT_MILLIS = 2_000L
        private const val HEALTH_CACHE_MILLIS = 5_000L
    }

    private data class HandshakeResult(
        val health: AgentRuntimeBridgeHealth,
        val responseSequence: Long
    )
}

class AgentRuntimeSessionKeyStore(context: Context) {
    private val database = AgentEncryptedDatabase(
        context.applicationContext,
        DATABASE,
        legacyPreferencesName = UNUSED_LEGACY_PREFERENCES
    )

    @Synchronized
    fun getOrCreate(): ByteArray {
        val existing = database.readString(KEY_SESSION, "")
            .takeIf(String::isNotBlank)
            ?.let { runCatching { Base64.getDecoder().decode(it) }.getOrNull() }
            ?.takeIf { it.size >= KEY_BYTES }
        if (existing != null) return existing
        return ByteArray(KEY_BYTES).also(SecureRandom()::nextBytes).also { key ->
            database.writeString(KEY_SESSION, Base64.getEncoder().encodeToString(key))
        }
    }

    companion object {
        private const val DATABASE = "signalasi_runtime_session_v1"
        private const val UNUSED_LEGACY_PREFERENCES = "signalasi_runtime_session_v1_no_legacy"
        private const val KEY_SESSION = "guest_hmac_key"
        private const val KEY_BYTES = 32
    }
}

object AgentOnDeviceRuntimeSupervisor {
    @Volatile private var registeredBridge: AgentRuntimeGuestBridge? = null

    @Synchronized
    fun discover(context: Context): AgentOnDeviceRuntimeBridge? {
        registeredBridge?.let { bridge ->
            if (bridge.health().ready) return bridge
            AgentOnDeviceRuntimeBridgeRegistry.unregister(bridge)
            registeredBridge = null
        }
        val socket = File(context.applicationContext.filesDir, "agent-runtime/guest.sock")
        if (!socket.exists()) return null
        val keyStore = AgentRuntimeSessionKeyStore(context)
        val candidate = AgentRuntimeGuestBridge(
            channelFactory = LocalSocketAgentRuntimeGuestChannelFactory(socket.absolutePath),
            sessionKeyProvider = keyStore::getOrCreate
        )
        if (!candidate.health().ready) return null
        AgentOnDeviceRuntimeBridgeRegistry.register(candidate)
        registeredBridge = candidate
        return candidate
    }

    @Synchronized
    fun reset() {
        registeredBridge?.let(AgentOnDeviceRuntimeBridgeRegistry::unregister)
        registeredBridge = null
    }
}
