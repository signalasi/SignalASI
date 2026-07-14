package com.signalasi.chat

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

sealed interface McpJsonValue

data class McpJsonObject(val entries: Map<String, McpJsonValue>) : McpJsonValue {
    operator fun get(name: String): McpJsonValue? = entries[name]
    fun string(name: String): String? = (entries[name] as? McpJsonString)?.value
    fun boolean(name: String): Boolean? = (entries[name] as? McpJsonBoolean)?.value
    fun objectValue(name: String): McpJsonObject? = entries[name] as? McpJsonObject
    fun array(name: String): McpJsonArray? = entries[name] as? McpJsonArray

    companion object {
        val EMPTY = McpJsonObject(emptyMap())

        fun of(vararg entries: Pair<String, Any?>): McpJsonObject = McpJsonObject(
            linkedMapOf<String, McpJsonValue>().apply {
                entries.forEach { (name, value) -> put(name, McpJson.valueOf(value)) }
            }
        )
    }
}

data class McpJsonArray(val values: List<McpJsonValue>) : McpJsonValue
data class McpJsonString(val value: String) : McpJsonValue
data class McpJsonNumber(val raw: String) : McpJsonValue {
    fun longOrNull(): Long? = raw.toLongOrNull()
    fun doubleOrNull(): Double? = raw.toDoubleOrNull()?.takeIf { it.isFinite() }
}
data class McpJsonBoolean(val value: Boolean) : McpJsonValue
data object McpJsonNull : McpJsonValue

data class McpJsonLimits(
    val maxMessageBytes: Int = 1024 * 1024,
    val maxDepth: Int = 64,
    val maxNodes: Int = 50_000,
    val maxContainerEntries: Int = 4_096,
    val maxStringLength: Int = 256 * 1024
) {
    init {
        require(maxMessageBytes > 0)
        require(maxDepth in 1..256)
        require(maxNodes > 0)
        require(maxContainerEntries > 0)
        require(maxStringLength > 0)
    }
}

class McpJsonException(message: String) : IllegalArgumentException(message)

object McpJson {
    private val numberPattern = Regex("-?(?:0|[1-9][0-9]*)(?:\\.[0-9]+)?(?:[eE][+-]?[0-9]+)?")

    fun parseObject(json: String, limits: McpJsonLimits = McpJsonLimits()): McpJsonObject {
        val bytes = json.toByteArray(Charsets.UTF_8).size
        if (bytes > limits.maxMessageBytes) {
            throw McpJsonException("JSON message is $bytes bytes; limit is ${limits.maxMessageBytes}")
        }
        return Parser(json, limits).parseObject()
    }

    fun stringify(value: McpJsonValue, limits: McpJsonLimits = McpJsonLimits()): String {
        validate(value, limits)
        val output = StringBuilder()
        appendValue(output, value)
        val encoded = output.toString()
        val bytes = encoded.toByteArray(Charsets.UTF_8).size
        if (bytes > limits.maxMessageBytes) {
            throw McpJsonException("JSON message is $bytes bytes; limit is ${limits.maxMessageBytes}")
        }
        return encoded
    }

    fun valueOf(value: Any?): McpJsonValue = when (value) {
        null -> McpJsonNull
        is McpJsonValue -> value
        is String -> McpJsonString(value)
        is Boolean -> McpJsonBoolean(value)
        is Byte, is Short, is Int, is Long -> McpJsonNumber(value.toString())
        is Float -> {
            require(value.isFinite()) { "JSON numbers must be finite" }
            McpJsonNumber(value.toString())
        }
        is Double -> {
            require(value.isFinite()) { "JSON numbers must be finite" }
            McpJsonNumber(value.toString())
        }
        is Number -> McpJsonNumber(value.toString())
        is Map<*, *> -> McpJsonObject(
            linkedMapOf<String, McpJsonValue>().apply {
                value.forEach { (key, item) ->
                    require(key is String) { "JSON object keys must be strings" }
                    put(key, valueOf(item))
                }
            }
        )
        is Iterable<*> -> McpJsonArray(value.map(::valueOf))
        is Array<*> -> McpJsonArray(value.map(::valueOf))
        is IntArray -> McpJsonArray(value.map { McpJsonNumber(it.toString()) })
        is LongArray -> McpJsonArray(value.map { McpJsonNumber(it.toString()) })
        is BooleanArray -> McpJsonArray(value.map(::McpJsonBoolean))
        else -> throw IllegalArgumentException("Unsupported JSON value type: ${value.javaClass.name}")
    }

    private fun validate(root: McpJsonValue, limits: McpJsonLimits) {
        val stack = ArrayDeque<Pair<McpJsonValue, Int>>()
        stack.addLast(root to 1)
        var nodes = 0
        while (stack.isNotEmpty()) {
            val (value, depth) = stack.removeLast()
            nodes += 1
            if (nodes > limits.maxNodes) throw McpJsonException("JSON node limit exceeded")
            if (depth > limits.maxDepth) throw McpJsonException("JSON depth limit exceeded")
            when (value) {
                is McpJsonObject -> {
                    if (value.entries.size > limits.maxContainerEntries) {
                        throw McpJsonException("JSON object entry limit exceeded")
                    }
                    value.entries.forEach { (name, child) ->
                        validateString(name, limits)
                        stack.addLast(child to depth + 1)
                    }
                }
                is McpJsonArray -> {
                    if (value.values.size > limits.maxContainerEntries) {
                        throw McpJsonException("JSON array entry limit exceeded")
                    }
                    value.values.forEach { stack.addLast(it to depth + 1) }
                }
                is McpJsonString -> validateString(value.value, limits)
                is McpJsonNumber -> if (!numberPattern.matches(value.raw)) {
                    throw McpJsonException("Invalid JSON number: ${value.raw}")
                }
                is McpJsonBoolean, McpJsonNull -> Unit
            }
        }
    }

    private fun validateString(value: String, limits: McpJsonLimits) {
        if (value.length > limits.maxStringLength) throw McpJsonException("JSON string limit exceeded")
        var index = 0
        while (index < value.length) {
            val current = value[index]
            when {
                Character.isHighSurrogate(current) -> {
                    if (index + 1 >= value.length || !Character.isLowSurrogate(value[index + 1])) {
                        throw McpJsonException("JSON string contains an unpaired surrogate")
                    }
                    index += 2
                }
                Character.isLowSurrogate(current) -> throw McpJsonException("JSON string contains an unpaired surrogate")
                else -> index += 1
            }
        }
    }

    private fun appendValue(output: StringBuilder, value: McpJsonValue) {
        when (value) {
            is McpJsonObject -> {
                output.append('{')
                value.entries.entries.forEachIndexed { index, entry ->
                    if (index > 0) output.append(',')
                    appendString(output, entry.key)
                    output.append(':')
                    appendValue(output, entry.value)
                }
                output.append('}')
            }
            is McpJsonArray -> {
                output.append('[')
                value.values.forEachIndexed { index, child ->
                    if (index > 0) output.append(',')
                    appendValue(output, child)
                }
                output.append(']')
            }
            is McpJsonString -> appendString(output, value.value)
            is McpJsonNumber -> output.append(value.raw)
            is McpJsonBoolean -> output.append(if (value.value) "true" else "false")
            McpJsonNull -> output.append("null")
        }
    }

    private fun appendString(output: StringBuilder, value: String) {
        output.append('"')
        value.forEach { character ->
            when (character) {
                '"' -> output.append("\\\"")
                '\\' -> output.append("\\\\")
                '\b' -> output.append("\\b")
                '\u000C' -> output.append("\\f")
                '\n' -> output.append("\\n")
                '\r' -> output.append("\\r")
                '\t' -> output.append("\\t")
                else -> if (character.code < 0x20) {
                    output.append("\\u")
                    repeat(4 - character.code.toString(16).length) { output.append('0') }
                    output.append(character.code.toString(16))
                } else {
                    output.append(character)
                }
            }
        }
        output.append('"')
    }

    private class Parser(
        private val source: String,
        private val limits: McpJsonLimits
    ) {
        private var position = 0
        private var nodes = 0

        fun parseObject(): McpJsonObject {
            skipWhitespace()
            val result = parseValue(1) as? McpJsonObject ?: fail("JSON-RPC message must be an object")
            skipWhitespace()
            if (position != source.length) fail("Unexpected trailing JSON content")
            return result
        }

        private fun parseValue(depth: Int): McpJsonValue {
            if (depth > limits.maxDepth) fail("JSON depth limit exceeded")
            nodes += 1
            if (nodes > limits.maxNodes) fail("JSON node limit exceeded")
            skipWhitespace()
            if (position >= source.length) fail("Unexpected end of JSON input")
            return when (source[position]) {
                '{' -> parseObjectValue(depth)
                '[' -> parseArray(depth)
                '"' -> McpJsonString(parseString())
                't' -> parseLiteral("true", McpJsonBoolean(true))
                'f' -> parseLiteral("false", McpJsonBoolean(false))
                'n' -> parseLiteral("null", McpJsonNull)
                '-', in '0'..'9' -> parseNumber()
                else -> fail("Unexpected JSON token")
            }
        }

        private fun parseObjectValue(depth: Int): McpJsonObject {
            position += 1
            skipWhitespace()
            if (consume('}')) return McpJsonObject.EMPTY
            val entries = linkedMapOf<String, McpJsonValue>()
            while (true) {
                if (entries.size >= limits.maxContainerEntries) fail("JSON object entry limit exceeded")
                skipWhitespace()
                if (position >= source.length || source[position] != '"') fail("Expected a JSON object key")
                val name = parseString()
                if (entries.containsKey(name)) fail("Duplicate JSON object key: $name")
                skipWhitespace()
                expect(':')
                entries[name] = parseValue(depth + 1)
                skipWhitespace()
                if (consume('}')) return McpJsonObject(entries)
                expect(',')
            }
        }

        private fun parseArray(depth: Int): McpJsonArray {
            position += 1
            skipWhitespace()
            if (consume(']')) return McpJsonArray(emptyList())
            val values = mutableListOf<McpJsonValue>()
            while (true) {
                if (values.size >= limits.maxContainerEntries) fail("JSON array entry limit exceeded")
                values += parseValue(depth + 1)
                skipWhitespace()
                if (consume(']')) return McpJsonArray(values)
                expect(',')
            }
        }

        private fun parseString(): String {
            expect('"')
            val result = StringBuilder()
            while (position < source.length) {
                val character = source[position++]
                when (character) {
                    '"' -> return result.toString()
                    '\\' -> {
                        if (position >= source.length) fail("Unterminated JSON escape")
                        when (val escaped = source[position++]) {
                            '"', '\\', '/' -> result.append(escaped)
                            'b' -> result.append('\b')
                            'f' -> result.append('\u000C')
                            'n' -> result.append('\n')
                            'r' -> result.append('\r')
                            't' -> result.append('\t')
                            'u' -> appendUnicodeEscape(result)
                            else -> fail("Invalid JSON escape: \\$escaped")
                        }
                    }
                    else -> {
                        if (character.code < 0x20) fail("Unescaped control character in JSON string")
                        when {
                            Character.isHighSurrogate(character) -> {
                                if (position >= source.length || !Character.isLowSurrogate(source[position])) {
                                    fail("Unpaired surrogate in JSON string")
                                }
                                result.append(character).append(source[position++])
                            }
                            Character.isLowSurrogate(character) -> fail("Unpaired surrogate in JSON string")
                            else -> result.append(character)
                        }
                    }
                }
                if (result.length > limits.maxStringLength) fail("JSON string limit exceeded")
            }
            fail("Unterminated JSON string")
        }

        private fun appendUnicodeEscape(output: StringBuilder) {
            val first = readHexCodeUnit()
            when {
                Character.isHighSurrogate(first) -> {
                    if (position + 1 >= source.length || source[position] != '\\' || source[position + 1] != 'u') {
                        fail("High surrogate must be followed by a low surrogate")
                    }
                    position += 2
                    val second = readHexCodeUnit()
                    if (!Character.isLowSurrogate(second)) fail("High surrogate must be followed by a low surrogate")
                    output.append(first).append(second)
                }
                Character.isLowSurrogate(first) -> fail("Unpaired low surrogate in JSON string")
                else -> output.append(first)
            }
        }

        private fun readHexCodeUnit(): Char {
            if (position + 4 > source.length) fail("Incomplete JSON unicode escape")
            var value = 0
            repeat(4) {
                val digit = source[position++].digitToIntOrNull(16) ?: fail("Invalid JSON unicode escape")
                value = value * 16 + digit
            }
            return value.toChar()
        }

        private fun parseNumber(): McpJsonNumber {
            val start = position
            if (consume('-') && position >= source.length) fail("Incomplete JSON number")
            if (consume('0')) {
                if (position < source.length && source[position].isDigit()) fail("Leading zero in JSON number")
            } else {
                if (position >= source.length || source[position] !in '1'..'9') fail("Invalid JSON number")
                while (position < source.length && source[position].isDigit()) position += 1
            }
            if (consume('.')) {
                if (position >= source.length || !source[position].isDigit()) fail("Invalid JSON fraction")
                while (position < source.length && source[position].isDigit()) position += 1
            }
            if (position < source.length && source[position] in listOf('e', 'E')) {
                position += 1
                if (position < source.length && source[position] in listOf('+', '-')) position += 1
                if (position >= source.length || !source[position].isDigit()) fail("Invalid JSON exponent")
                while (position < source.length && source[position].isDigit()) position += 1
            }
            return McpJsonNumber(source.substring(start, position))
        }

        private fun <T : McpJsonValue> parseLiteral(literal: String, value: T): T {
            if (!source.regionMatches(position, literal, 0, literal.length)) fail("Invalid JSON literal")
            position += literal.length
            return value
        }

        private fun skipWhitespace() {
            while (position < source.length && source[position] in listOf(' ', '\t', '\n', '\r')) position += 1
        }

        private fun consume(expected: Char): Boolean {
            if (position >= source.length || source[position] != expected) return false
            position += 1
            return true
        }

        private fun expect(expected: Char) {
            if (!consume(expected)) fail("Expected '$expected'")
        }

        private fun fail(message: String): Nothing = throw McpJsonException("$message at offset $position")
    }
}

interface AgentMcpTransport {
    suspend fun open()
    suspend fun send(message: String)
    suspend fun receive(): String?
    suspend fun onProtocolVersionNegotiated(protocolVersion: String) = Unit
    suspend fun close()
}

enum class AgentMcpSessionState {
    NEW,
    CONNECTING,
    INITIALIZING,
    ACTIVE,
    CLOSING,
    CLOSED,
    FAILED
}

enum class AgentMcpErrorKind {
    INVALID_STATE,
    TRANSPORT,
    MALFORMED_JSON,
    INVALID_JSON_RPC,
    LIMIT_EXCEEDED,
    VERSION_NEGOTIATION,
    CAPABILITY_NOT_NEGOTIATED,
    REMOTE,
    TIMEOUT,
    CANCELLED,
    SESSION_CLOSED,
    INVALID_RESULT,
    RESOURCE_LIMIT
}

data class AgentMcpError(
    val kind: AgentMcpErrorKind,
    val message: String,
    val requestId: Long? = null,
    val method: String? = null,
    val rpcCode: Long? = null,
    val data: McpJsonValue? = null
)

class AgentMcpException(
    val error: AgentMcpError,
    cause: Throwable? = null
) : Exception(error.message, cause)

data class AgentMcpSessionConfig(
    val requestedProtocolVersion: String = PROTOCOL_VERSION_2025_11_25,
    val supportedProtocolVersions: Set<String> = linkedSetOf(
        PROTOCOL_VERSION_2025_11_25,
        PROTOCOL_VERSION_2025_06_18,
        PROTOCOL_VERSION_2025_03_26,
        PROTOCOL_VERSION_2024_11_05
    ),
    val initializeTimeoutMillis: Long = 10_000,
    val defaultRequestTimeoutMillis: Long = 30_000,
    val maxPendingRequests: Int = 128,
    val jsonLimits: McpJsonLimits = McpJsonLimits(),
    val dispatcher: CoroutineDispatcher = Dispatchers.IO
) {
    init {
        require(requestedProtocolVersion.isNotBlank())
        require(requestedProtocolVersion in supportedProtocolVersions)
        require(initializeTimeoutMillis > 0)
        require(defaultRequestTimeoutMillis > 0)
        require(maxPendingRequests > 0)
    }

    companion object {
        const val PROTOCOL_VERSION_2025_11_25 = "2025-11-25"
        const val PROTOCOL_VERSION_2025_06_18 = "2025-06-18"
        const val PROTOCOL_VERSION_2025_03_26 = "2025-03-26"
        const val PROTOCOL_VERSION_2024_11_05 = "2024-11-05"
    }
}

data class AgentMcpImplementationInfo(
    val name: String,
    val version: String,
    val title: String? = null,
    val description: String? = null,
    val websiteUrl: String? = null
) {
    init {
        require(name.isNotBlank())
        require(version.isNotBlank())
    }

    internal fun toJson(): McpJsonObject = McpJsonObject(
        linkedMapOf<String, McpJsonValue>().apply {
            put("name", McpJsonString(name))
            put("version", McpJsonString(version))
            title?.let { put("title", McpJsonString(it)) }
            description?.let { put("description", McpJsonString(it)) }
            websiteUrl?.let { put("websiteUrl", McpJsonString(it)) }
        }
    )
}

data class AgentMcpServerCapabilities(val raw: McpJsonObject) {
    fun has(name: String): Boolean = raw.entries[name] is McpJsonObject
    val tools: Boolean get() = has("tools")
    val resources: Boolean get() = has("resources")
    val prompts: Boolean get() = has("prompts")
}

data class AgentMcpInitializeResult(
    val requestId: Long,
    val protocolVersion: String,
    val serverInfo: AgentMcpImplementationInfo,
    val capabilities: AgentMcpServerCapabilities,
    val instructions: String?,
    val raw: McpJsonObject
)

data class AgentMcpRpcResult(
    val requestId: Long,
    val value: McpJsonValue
)

data class AgentMcpPage<T>(
    val requestId: Long,
    val items: List<T>,
    val nextCursor: String?,
    val raw: McpJsonObject
)

data class AgentMcpTool(
    val name: String,
    val title: String?,
    val description: String?,
    val inputSchema: McpJsonObject,
    val outputSchema: McpJsonObject?,
    val annotations: McpJsonObject?,
    val raw: McpJsonObject
)

data class AgentMcpContent(
    val type: String,
    val text: String?,
    val data: String?,
    val mimeType: String?,
    val uri: String?,
    val name: String?,
    val resource: AgentMcpResourceContent?,
    val raw: McpJsonObject
)

data class AgentMcpToolCallResult(
    val requestId: Long,
    val content: List<AgentMcpContent>,
    val structuredContent: McpJsonObject?,
    val isError: Boolean,
    val raw: McpJsonObject
)

data class AgentMcpResource(
    val uri: String,
    val name: String,
    val title: String?,
    val description: String?,
    val mimeType: String?,
    val size: Long?,
    val annotations: McpJsonObject?,
    val raw: McpJsonObject
)

data class AgentMcpResourceContent(
    val uri: String,
    val mimeType: String?,
    val text: String?,
    val blob: String?,
    val annotations: McpJsonObject?,
    val raw: McpJsonObject
)

data class AgentMcpResourceReadResult(
    val requestId: Long,
    val contents: List<AgentMcpResourceContent>,
    val raw: McpJsonObject
)

data class AgentMcpPromptArgument(
    val name: String,
    val title: String?,
    val description: String?,
    val required: Boolean,
    val raw: McpJsonObject
)

data class AgentMcpPrompt(
    val name: String,
    val title: String?,
    val description: String?,
    val arguments: List<AgentMcpPromptArgument>,
    val raw: McpJsonObject
)

data class AgentMcpPromptMessage(
    val role: String,
    val content: AgentMcpContent,
    val raw: McpJsonObject
)

data class AgentMcpPromptGetResult(
    val requestId: Long,
    val description: String?,
    val messages: List<AgentMcpPromptMessage>,
    val raw: McpJsonObject
)

data class AgentMcpNotification(
    val method: String,
    val params: McpJsonObject?,
    val raw: McpJsonObject
)

interface AgentMcpSessionListener {
    fun onNotification(notification: AgentMcpNotification) = Unit
    fun onProtocolIssue(error: AgentMcpError) = Unit

    companion object {
        val NONE: AgentMcpSessionListener = object : AgentMcpSessionListener {}
    }
}

class AgentMcpSession(
    private val transport: AgentMcpTransport,
    private val config: AgentMcpSessionConfig = AgentMcpSessionConfig(),
    private val listener: AgentMcpSessionListener = AgentMcpSessionListener.NONE
) {
    private data class PendingRequest(
        val id: Long,
        val method: String,
        val response: CompletableDeferred<McpJsonValue>
    )

    private data class IncomingId(val key: String, val numericId: Long?)

    private val stateLock = Any()
    private val sendMutex = Mutex()
    private val nextId = AtomicLong(1)
    private val sessionJob = SupervisorJob()
    private val scope = CoroutineScope(sessionJob + config.dispatcher)
    private val pending = ConcurrentHashMap<String, PendingRequest>()
    private val ignoredResponseIds = ConcurrentHashMap.newKeySet<String>()
    private var readerJob: Job? = null

    @Volatile
    var state: AgentMcpSessionState = AgentMcpSessionState.NEW
        private set

    @Volatile
    var initialization: AgentMcpInitializeResult? = null
        private set

    suspend fun initialize(
        clientInfo: AgentMcpImplementationInfo,
        clientCapabilities: McpJsonObject = McpJsonObject.EMPTY,
        timeoutMillis: Long = config.initializeTimeoutMillis
    ): AgentMcpInitializeResult {
        synchronized(stateLock) {
            if (state != AgentMcpSessionState.NEW) throw invalidState("initialize", AgentMcpSessionState.NEW)
            state = AgentMcpSessionState.CONNECTING
        }

        try {
            transport.open()
            synchronized(stateLock) {
                if (state != AgentMcpSessionState.CONNECTING) throw closedError("Session closed during transport open")
                state = AgentMcpSessionState.INITIALIZING
            }
            readerJob = scope.launch(start = CoroutineStart.UNDISPATCHED) { receiveLoop() }

            val params = McpJsonObject.of(
                "protocolVersion" to config.requestedProtocolVersion,
                "capabilities" to clientCapabilities,
                "clientInfo" to clientInfo.toJson()
            )
            val response = requestInternal(
                method = "initialize",
                params = params,
                timeoutMillis = timeoutMillis,
                allowInitializing = true,
                cancelOnAbort = false
            )
            val parsed = parseInitializeResult(response)
            if (parsed.protocolVersion !in config.supportedProtocolVersions) {
                throw AgentMcpException(
                    AgentMcpError(
                        kind = AgentMcpErrorKind.VERSION_NEGOTIATION,
                        message = "Server selected unsupported MCP version ${parsed.protocolVersion}",
                        requestId = response.requestId,
                        method = "initialize"
                    )
                )
            }
            transport.onProtocolVersionNegotiated(parsed.protocolVersion)
            sendNotification("notifications/initialized")
            synchronized(stateLock) {
                if (state != AgentMcpSessionState.INITIALIZING) throw closedError("Session closed during initialization")
                initialization = parsed
                state = AgentMcpSessionState.ACTIVE
            }
            return parsed
        } catch (cancelled: CancellationException) {
            withContext(NonCancellable) { closeAfterInitializationFailure(null) }
            throw cancelled
        } catch (failure: Throwable) {
            val structured = structureFailure(failure, "initialize")
            withContext(NonCancellable) { closeAfterInitializationFailure(structured.error) }
            throw structured
        }
    }

    suspend fun request(
        method: String,
        params: McpJsonObject? = null,
        timeoutMillis: Long = config.defaultRequestTimeoutMillis
    ): AgentMcpRpcResult {
        require(method.isNotBlank()) { "method must not be blank" }
        return requestInternal(method, params, timeoutMillis, allowInitializing = false, cancelOnAbort = true)
    }

    suspend fun listTools(
        cursor: String? = null,
        timeoutMillis: Long = config.defaultRequestTimeoutMillis
    ): AgentMcpPage<AgentMcpTool> {
        requireCapability("tools", "tools/list")
        val response = request("tools/list", cursorParams(cursor), timeoutMillis)
        val result = resultObject(response, "tools/list")
        val tools = requiredArray(result, "tools", "tools/list result").values.mapIndexed { index, value ->
            parseTool(requiredObject(value, "tools[$index]"))
        }
        return AgentMcpPage(response.requestId, tools, result.string("nextCursor"), result)
    }

    suspend fun callTool(
        name: String,
        arguments: McpJsonObject = McpJsonObject.EMPTY,
        timeoutMillis: Long = config.defaultRequestTimeoutMillis
    ): AgentMcpToolCallResult {
        require(name.isNotBlank()) { "Tool name must not be blank" }
        requireCapability("tools", "tools/call")
        val response = request(
            "tools/call",
            McpJsonObject.of("name" to name, "arguments" to arguments),
            timeoutMillis
        )
        val result = resultObject(response, "tools/call")
        val content = requiredArray(result, "content", "tools/call result").values.mapIndexed { index, value ->
            parseContent(requiredObject(value, "content[$index]"), "content[$index]")
        }
        val structuredContent = when (val value = result["structuredContent"]) {
            null -> null
            is McpJsonObject -> value
            else -> invalidResult("tools/call structuredContent must be an object", response.requestId, "tools/call")
        }
        val isError = optionalBoolean(result, "isError", false, "tools/call result")
        return AgentMcpToolCallResult(response.requestId, content, structuredContent, isError, result)
    }

    suspend fun listResources(
        cursor: String? = null,
        timeoutMillis: Long = config.defaultRequestTimeoutMillis
    ): AgentMcpPage<AgentMcpResource> {
        requireCapability("resources", "resources/list")
        val response = request("resources/list", cursorParams(cursor), timeoutMillis)
        val result = resultObject(response, "resources/list")
        val resources = requiredArray(result, "resources", "resources/list result").values.mapIndexed { index, value ->
            parseResource(requiredObject(value, "resources[$index]"))
        }
        return AgentMcpPage(response.requestId, resources, result.string("nextCursor"), result)
    }

    suspend fun readResource(
        uri: String,
        timeoutMillis: Long = config.defaultRequestTimeoutMillis
    ): AgentMcpResourceReadResult {
        require(uri.isNotBlank()) { "Resource URI must not be blank" }
        requireCapability("resources", "resources/read")
        val response = request("resources/read", McpJsonObject.of("uri" to uri), timeoutMillis)
        val result = resultObject(response, "resources/read")
        val contents = requiredArray(result, "contents", "resources/read result").values.mapIndexed { index, value ->
            parseResourceContent(requiredObject(value, "contents[$index]"), "contents[$index]")
        }
        return AgentMcpResourceReadResult(response.requestId, contents, result)
    }

    suspend fun listPrompts(
        cursor: String? = null,
        timeoutMillis: Long = config.defaultRequestTimeoutMillis
    ): AgentMcpPage<AgentMcpPrompt> {
        requireCapability("prompts", "prompts/list")
        val response = request("prompts/list", cursorParams(cursor), timeoutMillis)
        val result = resultObject(response, "prompts/list")
        val prompts = requiredArray(result, "prompts", "prompts/list result").values.mapIndexed { index, value ->
            parsePrompt(requiredObject(value, "prompts[$index]"))
        }
        return AgentMcpPage(response.requestId, prompts, result.string("nextCursor"), result)
    }

    suspend fun getPrompt(
        name: String,
        arguments: Map<String, String> = emptyMap(),
        timeoutMillis: Long = config.defaultRequestTimeoutMillis
    ): AgentMcpPromptGetResult {
        require(name.isNotBlank()) { "Prompt name must not be blank" }
        requireCapability("prompts", "prompts/get")
        val argumentJson = McpJsonObject(arguments.mapValues { McpJsonString(it.value) })
        val response = request(
            "prompts/get",
            McpJsonObject.of("name" to name, "arguments" to argumentJson),
            timeoutMillis
        )
        val result = resultObject(response, "prompts/get")
        val messages = requiredArray(result, "messages", "prompts/get result").values.mapIndexed { index, value ->
            val raw = requiredObject(value, "messages[$index]")
            val role = requiredString(raw, "role", "messages[$index]")
            if (role != "user" && role != "assistant") {
                invalidResult("messages[$index].role must be user or assistant", response.requestId, "prompts/get")
            }
            AgentMcpPromptMessage(
                role = role,
                content = parseContent(requiredObject(raw["content"], "messages[$index].content"), "messages[$index].content"),
                raw = raw
            )
        }
        return AgentMcpPromptGetResult(response.requestId, result.string("description"), messages, result)
    }

    suspend fun cancelRequest(requestId: Long, reason: String = "Cancelled by client"): Boolean {
        val key = numericIdKey(requestId)
        val request = pending[key] ?: return false
        if (request.method == "initialize" || !pending.remove(key, request)) return false
        rememberIgnored(key)
        request.response.completeExceptionally(
            AgentMcpException(
                AgentMcpError(
                    kind = AgentMcpErrorKind.CANCELLED,
                    message = reason,
                    requestId = requestId,
                    method = request.method
                )
            )
        )
        sendCancellation(requestId, reason)
        return true
    }

    fun pendingRequestIds(): Set<Long> = pending.values.mapTo(linkedSetOf()) { it.id }

    suspend fun close() {
        synchronized(stateLock) {
            if (state == AgentMcpSessionState.CLOSED) return
            state = AgentMcpSessionState.CLOSING
        }
        val closed = closedError("MCP session closed")
        completePending(closed)
        var closeFailure: Throwable? = null
        try {
            transport.close()
        } catch (failure: Throwable) {
            closeFailure = failure
        }
        val reader = readerJob
        if (reader != null && currentCoroutineContext()[Job] != reader) reader.cancelAndJoin()
        sessionJob.cancel()
        synchronized(stateLock) { state = AgentMcpSessionState.CLOSED }
        closeFailure?.let {
            throw AgentMcpException(
                AgentMcpError(AgentMcpErrorKind.TRANSPORT, "Failed to close MCP transport: ${it.message}"),
                it
            )
        }
    }

    private suspend fun requestInternal(
        method: String,
        params: McpJsonObject?,
        timeoutMillis: Long,
        allowInitializing: Boolean,
        cancelOnAbort: Boolean
    ): AgentMcpRpcResult {
        require(timeoutMillis > 0) { "timeoutMillis must be positive" }
        val allowed = state == AgentMcpSessionState.ACTIVE ||
            (allowInitializing && state == AgentMcpSessionState.INITIALIZING && method == "initialize")
        if (!allowed) throw invalidState(method, AgentMcpSessionState.ACTIVE)
        if (pending.size >= config.maxPendingRequests) {
            throw AgentMcpException(
                AgentMcpError(
                    AgentMcpErrorKind.RESOURCE_LIMIT,
                    "Maximum pending MCP request count (${config.maxPendingRequests}) reached",
                    method = method
                )
            )
        }

        val id = allocateRequestId()
        val key = numericIdKey(id)
        val request = PendingRequest(id, method, CompletableDeferred())
        check(pending.putIfAbsent(key, request) == null) { "MCP request ID collision" }
        val message = McpJsonObject(
            linkedMapOf<String, McpJsonValue>().apply {
                put("jsonrpc", McpJsonString("2.0"))
                put("id", McpJsonNumber(id.toString()))
                put("method", McpJsonString(method))
                params?.let { put("params", it) }
            }
        )

        try {
            sendJson(message)
            return try {
                AgentMcpRpcResult(id, withTimeout(timeoutMillis) { request.response.await() })
            } catch (timeout: TimeoutCancellationException) {
                if (pending.remove(key, request)) {
                    rememberIgnored(key)
                    if (cancelOnAbort) sendCancellationSafely(id, "Request timed out after ${timeoutMillis}ms")
                }
                throw AgentMcpException(
                    AgentMcpError(
                        kind = AgentMcpErrorKind.TIMEOUT,
                        message = "MCP request $method timed out after ${timeoutMillis}ms",
                        requestId = id,
                        method = method
                    ),
                    timeout
                )
            } catch (cancelled: CancellationException) {
                if (pending.remove(key, request)) {
                    rememberIgnored(key)
                    if (cancelOnAbort) {
                        withContext(NonCancellable) { sendCancellationSafely(id, "Caller cancelled request") }
                    }
                }
                throw cancelled
            }
        } catch (failure: Throwable) {
            pending.remove(key, request)
            if (failure is AgentMcpException || failure is CancellationException) throw failure
            throw structureFailure(failure, method, id)
        } finally {
            pending.remove(key, request)
        }
    }

    private suspend fun receiveLoop() {
        try {
            while (true) {
                val message = transport.receive()
                if (message == null) {
                    if (state in setOf(AgentMcpSessionState.CLOSING, AgentMcpSessionState.CLOSED)) return
                    throw AgentMcpException(
                        AgentMcpError(AgentMcpErrorKind.TRANSPORT, "MCP transport closed unexpectedly")
                    )
                }
                dispatchIncoming(parseIncoming(message))
            }
        } catch (cancelled: CancellationException) {
            if (state !in setOf(AgentMcpSessionState.CLOSING, AgentMcpSessionState.CLOSED)) {
                failFromReader(
                    AgentMcpError(AgentMcpErrorKind.TRANSPORT, "MCP receive loop was cancelled")
                )
            }
        } catch (failure: Throwable) {
            val structured = structureFailure(failure, "receive")
            failFromReader(structured.error)
        }
    }

    private fun parseIncoming(message: String): McpJsonObject {
        return try {
            McpJson.parseObject(message, config.jsonLimits)
        } catch (failure: McpJsonException) {
            val kind = if (failure.message?.contains("limit", ignoreCase = true) == true ||
                failure.message?.contains("bytes", ignoreCase = true) == true
            ) AgentMcpErrorKind.LIMIT_EXCEEDED else AgentMcpErrorKind.MALFORMED_JSON
            throw AgentMcpException(AgentMcpError(kind, failure.message ?: "Invalid JSON"), failure)
        }
    }

    private suspend fun dispatchIncoming(message: McpJsonObject) {
        if (message.string("jsonrpc") != "2.0") invalidRpc("jsonrpc must equal 2.0")
        val methodValue = message["method"]
        if (methodValue != null) {
            val method = (methodValue as? McpJsonString)?.value?.takeIf { it.isNotBlank() }
                ?: invalidRpc("JSON-RPC method must be a non-empty string")
            if (message.entries.containsKey("result") || message.entries.containsKey("error")) {
                invalidRpc("JSON-RPC request or notification cannot contain result or error")
            }
            val params = when (val value = message["params"]) {
                null -> null
                is McpJsonObject -> value
                else -> invalidRpc("JSON-RPC params must be an object")
            }
            if (!message.entries.containsKey("id")) {
                notifyListener(AgentMcpNotification(method, params, message))
                return
            }
            val id = parseIncomingId(message["id"])
            respondToServerRequest(id, method)
            return
        }

        if (!message.entries.containsKey("id")) invalidRpc("JSON-RPC response is missing id")
        val id = parseIncomingId(message["id"])
        val hasResult = message.entries.containsKey("result")
        val hasError = message.entries.containsKey("error")
        if (hasResult == hasError) invalidRpc("JSON-RPC response must contain exactly one of result or error")
        val request = pending.remove(id.key)
        if (request == null) {
            if (!ignoredResponseIds.remove(id.key)) {
                listener.onProtocolIssue(
                    AgentMcpError(
                        kind = AgentMcpErrorKind.INVALID_JSON_RPC,
                        message = "Response references an unknown request ID",
                        requestId = id.numericId
                    )
                )
            }
            return
        }
        if (hasResult) {
            request.response.complete(message["result"] ?: McpJsonNull)
            return
        }

        val error = message["error"] as? McpJsonObject ?: invalidRpc("JSON-RPC error must be an object")
        val code = (error["code"] as? McpJsonNumber)?.longOrNull()
            ?: invalidRpc("JSON-RPC error code must be an integer")
        val description = error.string("message")?.takeIf { it.isNotBlank() }
            ?: invalidRpc("JSON-RPC error message must be a non-empty string")
        request.response.completeExceptionally(
            AgentMcpException(
                AgentMcpError(
                    kind = AgentMcpErrorKind.REMOTE,
                    message = description,
                    requestId = request.id,
                    method = request.method,
                    rpcCode = code,
                    data = error["data"]
                )
            )
        )
    }

    private suspend fun respondToServerRequest(id: IncomingId, method: String) {
        val response = if (method == "ping") {
            McpJsonObject.of(
                "jsonrpc" to "2.0",
                "id" to incomingIdValue(id),
                "result" to McpJsonObject.EMPTY
            )
        } else {
            McpJsonObject.of(
                "jsonrpc" to "2.0",
                "id" to incomingIdValue(id),
                "error" to McpJsonObject.of(
                    "code" to -32601,
                    "message" to "Method not found: $method"
                )
            )
        }
        sendJson(response)
    }

    private fun incomingIdValue(id: IncomingId): McpJsonValue = when {
        id.key.startsWith("n:") -> McpJsonNumber(id.key.removePrefix("n:"))
        else -> McpJsonString(id.key.removePrefix("s:"))
    }

    private suspend fun sendJson(message: McpJsonObject) {
        val encoded = try {
            McpJson.stringify(message, config.jsonLimits)
        } catch (failure: McpJsonException) {
            throw AgentMcpException(
                AgentMcpError(AgentMcpErrorKind.LIMIT_EXCEEDED, failure.message ?: "Outbound JSON exceeds limits"),
                failure
            )
        }
        try {
            sendMutex.withLock { transport.send(encoded) }
        } catch (failure: AgentMcpException) {
            throw failure
        } catch (failure: Throwable) {
            throw AgentMcpException(
                AgentMcpError(AgentMcpErrorKind.TRANSPORT, "Failed to send MCP message: ${failure.message}"),
                failure
            )
        }
    }

    private suspend fun sendNotification(method: String, params: McpJsonObject? = null) {
        sendJson(
            McpJsonObject(
                linkedMapOf<String, McpJsonValue>().apply {
                    put("jsonrpc", McpJsonString("2.0"))
                    put("method", McpJsonString(method))
                    params?.let { put("params", it) }
                }
            )
        )
    }

    private suspend fun sendCancellation(requestId: Long, reason: String) {
        sendNotification(
            "notifications/cancelled",
            McpJsonObject.of("requestId" to requestId, "reason" to reason)
        )
    }

    private suspend fun sendCancellationSafely(requestId: Long, reason: String) {
        try {
            sendCancellation(requestId, reason)
        } catch (failure: Throwable) {
            listener.onProtocolIssue(
                AgentMcpError(
                    kind = AgentMcpErrorKind.TRANSPORT,
                    message = "Could not send cancellation for request $requestId: ${failure.message}",
                    requestId = requestId
                )
            )
        }
    }

    private fun parseInitializeResult(response: AgentMcpRpcResult): AgentMcpInitializeResult {
        val result = resultObject(response, "initialize")
        val protocolVersion = requiredString(result, "protocolVersion", "initialize result")
        val serverInfoJson = requiredObject(result["serverInfo"], "initialize result.serverInfo")
        val serverInfo = AgentMcpImplementationInfo(
            name = requiredString(serverInfoJson, "name", "serverInfo"),
            version = requiredString(serverInfoJson, "version", "serverInfo"),
            title = serverInfoJson.string("title"),
            description = serverInfoJson.string("description"),
            websiteUrl = serverInfoJson.string("websiteUrl")
        )
        val capabilitiesJson = requiredObject(result["capabilities"], "initialize result.capabilities")
        capabilitiesJson.entries.forEach { (name, value) ->
            if (value !is McpJsonObject) invalidResult("Server capability $name must be an object", response.requestId, "initialize")
        }
        return AgentMcpInitializeResult(
            requestId = response.requestId,
            protocolVersion = protocolVersion,
            serverInfo = serverInfo,
            capabilities = AgentMcpServerCapabilities(capabilitiesJson),
            instructions = result.string("instructions"),
            raw = result
        )
    }

    private fun parseTool(raw: McpJsonObject): AgentMcpTool = AgentMcpTool(
        name = requiredString(raw, "name", "tool"),
        title = raw.string("title"),
        description = raw.string("description"),
        inputSchema = requiredObject(raw["inputSchema"], "tool.inputSchema"),
        outputSchema = optionalObject(raw, "outputSchema", "tool"),
        annotations = optionalObject(raw, "annotations", "tool"),
        raw = raw
    )

    private fun parseContent(raw: McpJsonObject, context: String): AgentMcpContent {
        val type = requiredString(raw, "type", context)
        val text = raw.string("text")
        val data = raw.string("data")
        val mimeType = raw.string("mimeType")
        var resource: AgentMcpResourceContent? = null
        when (type) {
            "text" -> if (text == null) invalidResult("$context.text is required for text content")
            "image", "audio" -> if (data == null || mimeType == null) {
                invalidResult("$context requires data and mimeType for $type content")
            }
            "resource" -> resource = parseResourceContent(
                requiredObject(raw["resource"], "$context.resource"),
                "$context.resource"
            )
            "resource_link" -> {
                requiredString(raw, "uri", context)
                requiredString(raw, "name", context)
            }
        }
        return AgentMcpContent(
            type = type,
            text = text,
            data = data,
            mimeType = mimeType,
            uri = raw.string("uri"),
            name = raw.string("name"),
            resource = resource,
            raw = raw
        )
    }

    private fun parseResource(raw: McpJsonObject): AgentMcpResource {
        val size = when (val value = raw["size"]) {
            null -> null
            is McpJsonNumber -> value.longOrNull()?.takeIf { it >= 0 }
                ?: invalidResult("resource.size must be a non-negative integer")
            else -> invalidResult("resource.size must be a non-negative integer")
        }
        return AgentMcpResource(
            uri = requiredString(raw, "uri", "resource"),
            name = requiredString(raw, "name", "resource"),
            title = raw.string("title"),
            description = raw.string("description"),
            mimeType = raw.string("mimeType"),
            size = size,
            annotations = optionalObject(raw, "annotations", "resource"),
            raw = raw
        )
    }

    private fun parseResourceContent(raw: McpJsonObject, context: String): AgentMcpResourceContent {
        val text = raw.string("text")
        val blob = raw.string("blob")
        if ((text == null) == (blob == null)) invalidResult("$context must contain exactly one of text or blob")
        return AgentMcpResourceContent(
            uri = requiredString(raw, "uri", context),
            mimeType = raw.string("mimeType"),
            text = text,
            blob = blob,
            annotations = optionalObject(raw, "annotations", context),
            raw = raw
        )
    }

    private fun parsePrompt(raw: McpJsonObject): AgentMcpPrompt {
        val arguments = when (val value = raw["arguments"]) {
            null -> emptyList()
            is McpJsonArray -> value.values.mapIndexed { index, argument ->
                val item = requiredObject(argument, "prompt.arguments[$index]")
                AgentMcpPromptArgument(
                    name = requiredString(item, "name", "prompt.arguments[$index]"),
                    title = item.string("title"),
                    description = item.string("description"),
                    required = optionalBoolean(item, "required", false, "prompt.arguments[$index]"),
                    raw = item
                )
            }
            else -> invalidResult("prompt.arguments must be an array")
        }
        return AgentMcpPrompt(
            name = requiredString(raw, "name", "prompt"),
            title = raw.string("title"),
            description = raw.string("description"),
            arguments = arguments,
            raw = raw
        )
    }

    private fun resultObject(response: AgentMcpRpcResult, method: String): McpJsonObject =
        response.value as? McpJsonObject
            ?: invalidResult("$method result must be an object", response.requestId, method)

    private fun requiredObject(value: McpJsonValue?, context: String): McpJsonObject =
        value as? McpJsonObject ?: invalidResult("$context must be an object")

    private fun optionalObject(raw: McpJsonObject, name: String, context: String): McpJsonObject? =
        when (val value = raw[name]) {
            null -> null
            is McpJsonObject -> value
            else -> invalidResult("$context.$name must be an object")
        }

    private fun requiredArray(raw: McpJsonObject, name: String, context: String): McpJsonArray =
        raw[name] as? McpJsonArray ?: invalidResult("$context.$name must be an array")

    private fun requiredString(raw: McpJsonObject, name: String, context: String): String =
        raw.string(name)?.takeIf { it.isNotBlank() } ?: invalidResult("$context.$name must be a non-empty string")

    private fun optionalBoolean(raw: McpJsonObject, name: String, default: Boolean, context: String): Boolean =
        when (val value = raw[name]) {
            null -> default
            is McpJsonBoolean -> value.value
            else -> invalidResult("$context.$name must be a boolean")
        }

    private fun cursorParams(cursor: String?): McpJsonObject? {
        if (cursor == null) return null
        require(cursor.isNotBlank()) { "Cursor must not be blank" }
        return McpJsonObject.of("cursor" to cursor)
    }

    private fun requireCapability(capability: String, method: String) {
        val current = initialization ?: throw invalidState(method, AgentMcpSessionState.ACTIVE)
        if (!current.capabilities.has(capability)) {
            throw AgentMcpException(
                AgentMcpError(
                    kind = AgentMcpErrorKind.CAPABILITY_NOT_NEGOTIATED,
                    message = "Server did not negotiate the $capability capability",
                    method = method
                )
            )
        }
    }

    private fun parseIncomingId(value: McpJsonValue?): IncomingId = when (value) {
        is McpJsonNumber -> {
            val number = value.longOrNull() ?: invalidRpc("JSON-RPC numeric id must be an integer")
            IncomingId(numericIdKey(number), number)
        }
        is McpJsonString -> {
            if (value.value.isEmpty()) invalidRpc("JSON-RPC string id must not be empty")
            IncomingId("s:${value.value}", null)
        }
        else -> invalidRpc("JSON-RPC id must be a string or integer")
    }

    private fun allocateRequestId(): Long {
        val id = nextId.getAndIncrement()
        if (id <= 0) {
            throw AgentMcpException(
                AgentMcpError(AgentMcpErrorKind.RESOURCE_LIMIT, "MCP request ID space exhausted")
            )
        }
        return id
    }

    private fun rememberIgnored(key: String) {
        if (ignoredResponseIds.size >= config.maxPendingRequests * 2) ignoredResponseIds.clear()
        ignoredResponseIds += key
    }

    private fun notifyListener(notification: AgentMcpNotification) {
        try {
            listener.onNotification(notification)
        } catch (failure: Throwable) {
            listener.onProtocolIssue(
                AgentMcpError(
                    AgentMcpErrorKind.INVALID_RESULT,
                    "MCP notification listener failed: ${failure.message}",
                    method = notification.method
                )
            )
        }
    }

    private suspend fun failFromReader(error: AgentMcpError) {
        synchronized(stateLock) {
            if (state in setOf(AgentMcpSessionState.CLOSING, AgentMcpSessionState.CLOSED, AgentMcpSessionState.FAILED)) return
            state = AgentMcpSessionState.FAILED
        }
        listener.onProtocolIssue(error)
        try {
            transport.close()
        } catch (_: Throwable) {
            // The original receive or validation failure is the actionable error.
        }
        completePending(AgentMcpException(error))
        sessionJob.cancel()
    }

    private suspend fun closeAfterInitializationFailure(error: AgentMcpError?) {
        synchronized(stateLock) {
            if (state != AgentMcpSessionState.CLOSED) {
                state = if (error == null) AgentMcpSessionState.CLOSED else AgentMcpSessionState.FAILED
            }
        }
        error?.let { completePending(AgentMcpException(it)) }
        try {
            transport.close()
        } catch (_: Throwable) {
            // Preserve the initialization failure.
        }
        val reader = readerJob
        if (reader != null && currentCoroutineContext()[Job] != reader) reader.cancelAndJoin()
        sessionJob.cancel()
    }

    private fun completePending(failure: AgentMcpException) {
        pending.values.forEach { request ->
            if (pending.remove(numericIdKey(request.id), request)) request.response.completeExceptionally(failure)
        }
    }

    private fun structureFailure(failure: Throwable, method: String, requestId: Long? = null): AgentMcpException {
        if (failure is AgentMcpException) return failure
        return AgentMcpException(
            AgentMcpError(
                kind = AgentMcpErrorKind.TRANSPORT,
                message = "MCP $method failed: ${failure.message ?: failure.javaClass.simpleName}",
                requestId = requestId,
                method = method
            ),
            failure
        )
    }

    private fun invalidState(method: String, expected: AgentMcpSessionState): AgentMcpException =
        AgentMcpException(
            AgentMcpError(
                kind = AgentMcpErrorKind.INVALID_STATE,
                message = "$method requires MCP session state $expected; current state is $state",
                method = method
            )
        )

    private fun closedError(message: String): AgentMcpException = AgentMcpException(
        AgentMcpError(AgentMcpErrorKind.SESSION_CLOSED, message)
    )

    private fun invalidRpc(message: String): Nothing = throw AgentMcpException(
        AgentMcpError(AgentMcpErrorKind.INVALID_JSON_RPC, message)
    )

    private fun invalidResult(
        message: String,
        requestId: Long? = null,
        method: String? = null
    ): Nothing = throw AgentMcpException(
        AgentMcpError(AgentMcpErrorKind.INVALID_RESULT, message, requestId, method)
    )

    private fun numericIdKey(id: Long): String = "n:$id"
}
