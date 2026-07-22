package com.signalasi.chat

import android.content.Context
import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.os.Looper
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
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.ReentrantLock
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
        val expected = runCatching { hmac(unsignedPayload(envelope.copy(mac = "")), sessionKey) }
            .getOrElse { return false }
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
        is String -> canonicalJsonString(value)
        is Boolean, is Byte, is Short, is Int, is Long -> value.toString()
        is Number -> error("Runtime payload numbers must be signed 64-bit integers")
        is Map<*, *> -> value.entries
            .map { entry -> (entry.key as? String ?: error("Runtime payload key must be a string")) to entry.value }
            .sortedBy { it.first }
            .joinToString(separator = ",", prefix = "{", postfix = "}") { (key, item) ->
                "${canonicalJsonString(key)}:${canonicalJson(item)}"
            }
        is Iterable<*> -> value.joinToString(separator = ",", prefix = "[", postfix = "]") { canonicalJson(it) }
        is Array<*> -> value.joinToString(separator = ",", prefix = "[", postfix = "]") { canonicalJson(it) }
        else -> error("Unsupported runtime payload value: ${value::class.java.simpleName}")
    }

    private fun canonicalJsonString(value: String): String = buildString(value.length + 2) {
        append('"')
        var index = 0
        while (index < value.length) {
            val character = value[index]
            when (character) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\b' -> append("\\b")
                '\u000c' -> append("\\f")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> when {
                    character.code < 0x20 -> append("\\u").append(character.code.toString(16).padStart(4, '0'))
                    character.isHighSurrogate() -> {
                        check(index + 1 < value.length && value[index + 1].isLowSurrogate()) {
                            "Runtime payload contains an invalid Unicode surrogate"
                        }
                        append(character).append(value[++index])
                    }
                    character.isLowSurrogate() -> error("Runtime payload contains an invalid Unicode surrogate")
                    else -> append(character)
                }
            }
            index++
        }
        append('"')
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
) : AgentOnDeviceRuntimeBridge, Closeable {
    @Volatile private var cachedHealth: AgentRuntimeBridgeHealth? = null
    @Volatile private var cachedHealthAtMillis: Long = 0L
    @Volatile private var activeConnection: Connection? = null
    private val connectionLock = Any()

    override fun health(): AgentRuntimeBridgeHealth {
        val now = System.currentTimeMillis()
        cachedHealth?.takeIf {
            activeConnection?.isActive == true && now - cachedHealthAtMillis < HEALTH_CACHE_MILLIS
        }?.let { return it }
        val health = runCatching {
            val connection = connection()
            val requestId = "health-${UUID.randomUUID()}"
            val pending = connection.register(requestId)
            try {
                connection.send(requestId, AgentRuntimeGuestMessageType.HEARTBEAT)
                val response = pending.receive(HANDSHAKE_TIMEOUT_MILLIS)
                check(response.type == AgentRuntimeGuestMessageType.HEARTBEAT_ACK && response.sequence == 1L) {
                    "Guest runtime returned an invalid heartbeat"
                }
                check(response.payload["ready"] as? Boolean == true) {
                    response.payload["reason"]?.toString().orEmpty()
                        .ifBlank { "Guest runtime reported an unhealthy state" }
                }
                connection.health
            } finally {
                connection.unregister(requestId)
            }
        }.getOrElse { error ->
            activeConnection?.shutdown(error)
            AgentRuntimeBridgeHealth(false, reason = error.message ?: "Guest runtime is unavailable")
        }
        cachedHealth = health
        cachedHealthAtMillis = now
        return health
    }

    override fun execute(request: AgentRuntimeExecutionRequest): AgentRuntimeExecutionResponse {
        val startedAt = System.currentTimeMillis()
        val connection = connection()
        check(connection.health.ready) {
            connection.health.reason.ifBlank { "Guest runtime handshake failed" }
        }
        if (request.secretEnvironment.isNotEmpty()) {
            check(SECRET_ENVIRONMENT_CAPABILITY in connection.health.capabilities) {
                "Guest runtime does not support secure environment injection; update the Linux base runtime pack"
            }
        }
        val pending = connection.register(request.requestId)
        var lastReceivedSequence = 0L
        if (request.cancellationToken.isCancellationRequested) {
            connection.unregister(request.requestId)
            throw AgentNativeToolCancelledException()
        }
        connection.send(request.requestId, AgentRuntimeGuestMessageType.EXECUTE, executePayload(request))
        val registration = request.cancellationToken.invokeOnCancellation {
            runCatching { connection.send(request.requestId, AgentRuntimeGuestMessageType.CANCEL) }
        }
        try {
            val deadline = startedAt + request.timeoutMillis
            while (true) {
                if (request.cancellationToken.isCancellationRequested) throw AgentNativeToolCancelledException()
                val remaining = deadline - System.currentTimeMillis()
                if (remaining <= 0L) {
                    runCatching { connection.send(request.requestId, AgentRuntimeGuestMessageType.CANCEL) }
                    throw AgentNativeToolTimeoutException()
                }
                val envelope = try {
                    pending.receive(remaining)
                } catch (_: SocketTimeoutException) {
                    runCatching { connection.send(request.requestId, AgentRuntimeGuestMessageType.CANCEL) }
                    throw AgentNativeToolTimeoutException()
                }
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
            connection.unregister(request.requestId)
        }
    }

    override fun cancel(requestId: String): Boolean {
        val connection = activeConnection?.takeIf(Connection::isActive) ?: return false
        if (!connection.hasPending(requestId)) return false
        return runCatching {
            connection.send(requestId, AgentRuntimeGuestMessageType.CANCEL)
            true
        }.getOrDefault(false)
    }

    override fun close() {
        synchronized(connectionLock) {
            activeConnection?.shutdown(IllegalStateException("Guest runtime connection closed"))
            activeConnection = null
            cachedHealth = null
            cachedHealthAtMillis = 0L
        }
    }

    private fun connection(): Connection = synchronized(connectionLock) {
        activeConnection?.takeIf(Connection::isActive)?.let { return@synchronized it }
        val channel = channelFactory.open()
        val key = sessionKeyProvider()
        try {
            val requestId = "connect-${UUID.randomUUID()}"
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
            val response = receiveHandshake(channel, key, requestId)
            check(
                response.type == AgentRuntimeGuestMessageType.HELLO_ACK && response.sequence == 1L
            ) {
                "Guest runtime returned an invalid handshake"
            }
            val guestApi = (response.payload["guest_api_version"] as? Number)?.toInt() ?: 0
            val guestReady = response.payload["ready"] as? Boolean ?: false
            val capabilities = (response.payload["capabilities"] as? Iterable<*>)
                ?.mapNotNull { it?.toString()?.takeIf(String::isNotBlank) }
                ?.toSet()
                .orEmpty()
            val missingCapabilities = REQUIRED_GUEST_CAPABILITIES - capabilities
            val health = AgentRuntimeBridgeHealth(
                ready = guestApi == AgentRuntimeGuestProtocol.VERSION && guestReady && missingCapabilities.isEmpty(),
                guestApiVersion = guestApi,
                guestVersion = response.payload["guest_version"]?.toString().orEmpty(),
                capabilities = capabilities,
                reason = when {
                    guestApi != AgentRuntimeGuestProtocol.VERSION -> "Guest API version is incompatible"
                    !guestReady -> response.payload["reason"]?.toString().orEmpty()
                        .ifBlank { "Guest runtime prerequisites are unavailable" }
                    missingCapabilities.isNotEmpty() ->
                        "Guest runtime capabilities are incomplete: ${missingCapabilities.sorted().joinToString()}"
                    else -> ""
                }
            )
            check(health.ready) { health.reason }
            Connection(channel, key, health).also { connection ->
                activeConnection = connection
                cachedHealth = health
                cachedHealthAtMillis = System.currentTimeMillis()
                connection.startReader()
            }
        } catch (error: Throwable) {
            key.fill(0)
            runCatching(channel::close)
            throw error
        }
    }

    private fun receiveHandshake(
        channel: AgentRuntimeGuestChannel,
        key: ByteArray,
        requestId: String
    ): AgentRuntimeGuestEnvelope {
        val deadline = System.currentTimeMillis() + HANDSHAKE_TIMEOUT_MILLIS
        var staleFrames = 0
        while (true) {
            val remaining = deadline - System.currentTimeMillis()
            if (remaining <= 0L) throw SocketTimeoutException("Guest runtime handshake timed out")
            val response = channel.receive(remaining, key)
            if (response.requestId == requestId) return response
            staleFrames++
            check(staleFrames <= MAX_STALE_HANDSHAKE_FRAMES) {
                "Guest runtime returned too many stale handshake frames"
            }
        }
    }

    private fun executePayload(request: AgentRuntimeExecutionRequest): AgentNativeJsonObject {
        val limits = request.resourceLimits.validated()
        return linkedMapOf<String, Any?>(
            "language" to request.language.wireValue,
            "arguments" to request.arguments,
            "workspace_id" to request.workspaceId,
            "workspace_path" to request.guestWorkspacePath,
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
        ).apply {
            if (request.secretEnvironment.isNotEmpty()) {
                put("secret_environment", request.secretEnvironment)
            }
        }
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
        private const val HANDSHAKE_TIMEOUT_MILLIS = 15_000L
        private const val HEALTH_CACHE_MILLIS = 5_000L
        private const val READER_POLL_MILLIS = 30_000L
        private const val MAX_PENDING_REQUESTS = 64
        private const val MAX_PENDING_FRAMES = 256
        private const val MAX_STALE_HANDSHAKE_FRAMES = 32
        private const val SECRET_ENVIRONMENT_CAPABILITY = "runtime.secret_environment"
        private val REQUIRED_GUEST_CAPABILITIES = setOf(
            "runtime.execute",
            "runtime.cancel",
            "runtime.progress",
            "runtime.concurrent"
        )
    }

    private inner class Connection(
        private val channel: AgentRuntimeGuestChannel,
        private val sessionKey: ByteArray,
        val health: AgentRuntimeBridgeHealth
    ) {
        private val active = AtomicBoolean(true)
        private val pending = ConcurrentHashMap<String, PendingResponse>()
        private val outboundSequences = ConcurrentHashMap<String, AtomicLong>()
        private val registrationSlots = Semaphore(MAX_PENDING_REQUESTS, true)
        val isActive: Boolean get() = active.get()

        fun register(requestId: String): PendingResponse {
            check(registrationSlots.tryAcquire()) { "Runtime request concurrency limit reached" }
            val response = PendingResponse()
            if (pending.putIfAbsent(requestId, response) != null) {
                registrationSlots.release()
                error("Runtime request is already active")
            }
            return response
        }

        fun unregister(requestId: String) {
            if (pending.remove(requestId) != null) registrationSlots.release()
            outboundSequences.remove(requestId)
        }

        fun hasPending(requestId: String): Boolean = pending.containsKey(requestId)

        fun send(
            requestId: String,
            type: AgentRuntimeGuestMessageType,
            payload: AgentNativeJsonObject = emptyMap()
        ) {
            check(isActive) { "Guest runtime connection is unavailable" }
            val sequence = outboundSequences.computeIfAbsent(requestId) { AtomicLong(0L) }.incrementAndGet()
            runCatching {
                channel.send(
                    AgentRuntimeGuestEnvelope(
                        requestId = requestId,
                        type = type,
                        sequence = sequence,
                        payload = payload
                    ),
                    sessionKey
                )
            }.getOrElse { error ->
                shutdown(error)
                throw error
            }
        }

        fun startReader() {
            Thread({
                while (isActive) {
                    val envelope = try {
                        channel.receive(READER_POLL_MILLIS, sessionKey)
                    } catch (_: SocketTimeoutException) {
                        continue
                    } catch (error: Throwable) {
                        shutdown(error)
                        break
                    }
                    val accepted = pending[envelope.requestId]?.offer(envelope) ?: true
                    if (!accepted) {
                        shutdown(IllegalStateException("Guest runtime response queue overflowed"))
                        break
                    }
                }
            }, "signalasi-guest-reader").apply {
                isDaemon = true
                start()
            }
        }

        fun shutdown(error: Throwable) {
            if (!active.compareAndSet(true, false)) return
            pending.values.forEach { it.fail(error) }
            pending.clear()
            outboundSequences.clear()
            runCatching(channel::close)
            sessionKey.fill(0)
            synchronized(connectionLock) {
                if (activeConnection === this) activeConnection = null
                cachedHealth = null
                cachedHealthAtMillis = 0L
            }
        }
    }

    private class PendingResponse {
        private val frames = ArrayBlockingQueue<PendingFrame>(MAX_PENDING_FRAMES)

        fun offer(envelope: AgentRuntimeGuestEnvelope): Boolean = frames.offer(PendingFrame(envelope = envelope))

        fun fail(error: Throwable) {
            frames.clear()
            frames.offer(PendingFrame(error = error))
        }

        fun receive(timeoutMillis: Long): AgentRuntimeGuestEnvelope {
            val frame = frames.poll(timeoutMillis.coerceAtLeast(1L), TimeUnit.MILLISECONDS)
                ?: throw SocketTimeoutException("Guest runtime response timed out")
            frame.error?.let { throw IllegalStateException(it.message ?: "Guest runtime connection failed", it) }
            return requireNotNull(frame.envelope)
        }
    }

    private data class PendingFrame(
        val envelope: AgentRuntimeGuestEnvelope? = null,
        val error: Throwable? = null
    )

}

class AgentRuntimeSessionKeyStore(context: Context) {
    private val appContext = context.applicationContext
    @Volatile private var cachedKey: ByteArray? = null

    @Synchronized
    fun getOrCreate(): ByteArray {
        cachedKey?.let { return it.copyOf() }
        val database = AgentEncryptedDatabase(
            appContext,
            DATABASE,
            legacyPreferencesName = UNUSED_LEGACY_PREFERENCES
        )
        val key = try {
            database.readString(KEY_SESSION, "")
                .takeIf(String::isNotBlank)
                ?.let { runCatching { Base64.getDecoder().decode(it) }.getOrNull() }
                ?.takeIf { it.size >= KEY_BYTES }
                ?: ByteArray(KEY_BYTES).also(SecureRandom()::nextBytes).also { generated ->
                    database.writeString(KEY_SESSION, Base64.getEncoder().encodeToString(generated))
                }
        } finally {
            database.close()
        }
        cachedKey = key.copyOf()
        return key
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
    @Volatile private var lastHealth: AgentRuntimeBridgeHealth? = null
    @Volatile private var lastProbeAtMillis: Long = 0L
    @Volatile private var lastHealthyAtMillis: Long = 0L
    private val discoveryLock = ReentrantLock()
    private val discoveryScheduled = AtomicBoolean(false)
    private val discoveryExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "signalasi-runtime-discovery").apply { isDaemon = true }
    }

    fun discover(context: Context): AgentOnDeviceRuntimeBridge? {
        val appContext = context.applicationContext
        val now = android.os.SystemClock.elapsedRealtime()
        cachedReadyBridge(now)?.let { return it }
        if (lastHealth?.ready == false && now - lastProbeAtMillis <= FAILED_HEALTH_CACHE_MILLIS) {
            return null
        }
        if (Looper.myLooper() == Looper.getMainLooper()) {
            scheduleDiscovery(appContext)
            return null
        }
        return discoverBlocking(appContext)
    }

    fun cachedHealth(bridge: AgentOnDeviceRuntimeBridge): AgentRuntimeBridgeHealth? {
        if (registeredBridge !== bridge) return null
        val now = android.os.SystemClock.elapsedRealtime()
        return lastHealth?.takeIf { now - lastProbeAtMillis <= HEALTH_CACHE_MILLIS }
    }

    private fun scheduleDiscovery(context: Context) {
        if (!discoveryScheduled.compareAndSet(false, true)) return
        discoveryExecutor.execute {
            try {
                discoverBlocking(context.applicationContext)
            } finally {
                discoveryScheduled.set(false)
            }
        }
    }

    private fun discoverBlocking(context: Context): AgentOnDeviceRuntimeBridge? {
        if (!discoveryLock.tryLock()) {
            return cachedReadyBridge(android.os.SystemClock.elapsedRealtime())
        }
        try {
            val now = android.os.SystemClock.elapsedRealtime()
            cachedReadyBridge(now)?.let { return it }
            if (lastHealth?.ready == false && now - lastProbeAtMillis <= FAILED_HEALTH_CACHE_MILLIS) {
                return null
            }
            registeredBridge?.let { bridge ->
                val health = runCatching { bridge.health() }
                    .getOrElse { AgentRuntimeBridgeHealth(false, reason = it.message ?: "Guest runtime is unavailable") }
                recordHealth(health)
                if (health.ready) return bridge
                bridge.close()
                AgentOnDeviceRuntimeBridgeRegistry.unregister(bridge)
                registeredBridge = null
                lastHealthyAtMillis = 0L
            }
            val socket = File(context.filesDir, "agent-runtime/guest.sock")
            if (!socket.exists()) {
                recordHealth(AgentRuntimeBridgeHealth(false, reason = "Guest runtime socket is unavailable"))
                return null
            }
            val keyStore = AgentRuntimeSessionKeyStore(context)
            val candidate = AgentRuntimeGuestBridge(
                channelFactory = LocalSocketAgentRuntimeGuestChannelFactory(socket.absolutePath),
                sessionKeyProvider = keyStore::getOrCreate
            )
            val health = candidate.health()
            recordHealth(health)
            if (!health.ready) {
                candidate.close()
                return null
            }
            AgentOnDeviceRuntimeBridgeRegistry.register(candidate)
            registeredBridge = candidate
            return candidate
        } finally {
            discoveryLock.unlock()
        }
    }

    fun reset() {
        discoveryLock.lock()
        try {
            registeredBridge?.let { bridge ->
                bridge.close()
                AgentOnDeviceRuntimeBridgeRegistry.unregister(bridge)
            }
            registeredBridge = null
            lastHealth = null
            lastProbeAtMillis = 0L
            lastHealthyAtMillis = 0L
        } finally {
            discoveryLock.unlock()
        }
    }

    private fun cachedReadyBridge(now: Long): AgentRuntimeGuestBridge? = registeredBridge?.takeIf {
        lastHealth?.ready == true && now - lastHealthyAtMillis <= HEALTH_CACHE_MILLIS
    }

    private fun recordHealth(health: AgentRuntimeBridgeHealth) {
        val now = android.os.SystemClock.elapsedRealtime()
        lastHealth = health
        lastProbeAtMillis = now
        if (health.ready) lastHealthyAtMillis = now
    }

    private const val HEALTH_CACHE_MILLIS = 15_000L
    private const val FAILED_HEALTH_CACHE_MILLIS = 1_000L
}
