package com.signalasi.chat

import android.content.Context
import java.net.URI
import java.net.URLEncoder
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

class AgentMcpHttpException(
    val statusCode: Int,
    message: String,
    val authenticationFailure: Boolean = statusCode == 401 || statusCode == 403
) : IllegalStateException(message)

class AgentMcpStreamableHttpTransport(
    endpoint: String,
    private val requestHeaders: Map<String, String> = emptyMap(),
    private val client: OkHttpClient = defaultClient()
) : AgentMcpTransport {
    private val endpoint = AgentMcpEndpointPolicy.normalize(endpoint)
    private val incoming = Channel<String>(Channel.UNLIMITED)

    @Volatile private var opened = false
    @Volatile private var closed = false
    @Volatile private var protocolVersion = ""
    @Volatile private var sessionId = ""

    override suspend fun open() {
        check(!closed) { "MCP transport is closed" }
        opened = true
    }

    override suspend fun send(message: String) = withContext(Dispatchers.IO) {
        check(opened && !closed) { "MCP transport is not open" }
        val request = Request.Builder()
            .url(endpoint)
            .post(message.toRequestBody(JSON_MEDIA_TYPE))
            .header("Accept", "application/json, text/event-stream")
            .header("Content-Type", "application/json")
            .header("User-Agent", "SignalASI-Android-MCP/1")
            .apply {
                requestHeaders.forEach { (name, value) ->
                    if (isSafeHeader(name, value)) header(name, value)
                }
                protocolVersion.takeIf(String::isNotBlank)?.let { header("MCP-Protocol-Version", it) }
                sessionId.takeIf(String::isNotBlank)?.let { header("Mcp-Session-Id", it) }
            }
            .build()
        client.newCall(request).execute().use { response ->
            response.header("Mcp-Session-Id")?.trim()?.takeIf(String::isNotBlank)?.let { sessionId = it }
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw AgentMcpHttpException(
                    response.code,
                    "MCP server returned HTTP ${response.code}${body.take(240).trim().takeIf(String::isNotBlank)?.let { ": $it" }.orEmpty()}"
                )
            }
            if (body.isBlank()) return@use
            val contentType = response.header("Content-Type").orEmpty().lowercase(Locale.ROOT)
            val messages = if (contentType.contains("text/event-stream")) parseSse(body) else listOf(body.trim())
            messages.filter(String::isNotBlank).forEach { incoming.send(it) }
        }
    }

    override suspend fun receive(): String? = incoming.receiveCatching().getOrNull()

    override suspend fun onProtocolVersionNegotiated(protocolVersion: String) {
        this.protocolVersion = protocolVersion
    }

    override suspend fun close() {
        if (closed) return
        closed = true
        incoming.close()
    }

    internal fun parseSse(document: String): List<String> {
        val messages = mutableListOf<String>()
        val data = mutableListOf<String>()
        fun flush() {
            if (data.isNotEmpty()) messages += data.joinToString("\n")
            data.clear()
        }
        document.lineSequence().forEach { raw ->
            val line = raw.trimEnd('\r')
            when {
                line.isEmpty() -> flush()
                line.startsWith(":") -> Unit
                line.startsWith("data:") -> data += line.removePrefix("data:").removePrefix(" ")
            }
        }
        flush()
        return messages
    }

    companion object {
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        private val HEADER_NAME = Regex("[A-Za-z0-9!#$%&'*+.^_`|~-]{1,128}")

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(45, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .callTimeout(60, TimeUnit.SECONDS)
            .followRedirects(false)
            .followSslRedirects(false)
            .build()

        private fun isSafeHeader(name: String, value: String): Boolean =
            HEADER_NAME.matches(name) && value.length <= 8_192 && '\r' !in value && '\n' !in value &&
                !name.equals("Host", true) && !name.equals("Content-Length", true)
    }
}

class AgentMcpClientManager(
    context: Context,
    private val registry: AgentMcpRegistry = AgentMcpRegistry(EncryptedAgentMcpStore(context)),
    private val packageRepository: AgentMcpPackageRepository = AgentMcpPackageRepository(context),
    private val client: OkHttpClient = AgentMcpStreamableHttpTransport.defaultClient()
) {
    private val sessions = ConcurrentHashMap<String, AgentMcpSession>()
    private val authCoordinator = AgentMcpAuthenticationCoordinator(registry, client)

    suspend fun listTools(connectionId: String): List<AgentMcpTool> {
        val connection = prepareConnection(requireConnection(connectionId))
        if (connection.transport == AgentMcpTransportKind.DECLARATIVE_HTTP) {
            return packageRepository.get(connection.id)?.tools.orEmpty().map { tool ->
                AgentMcpTool(
                    name = tool.name,
                    title = tool.title,
                    description = tool.description,
                    inputSchema = nativeToMcpObject(tool.inputSchema),
                    outputSchema = null,
                    annotations = McpJsonObject.of("readOnlyHint" to !tool.mutating),
                    raw = McpJsonObject.of("name" to tool.name)
                )
            }.also { registry.markConnected(connectionId, it.map(AgentMcpTool::name)) }
        }
        return withRemoteSession(connection) { session ->
            session.listTools().items.also { registry.markConnected(connectionId, it.map(AgentMcpTool::name)) }
        }
    }

    suspend fun callTool(
        connectionId: String,
        toolName: String,
        arguments: AgentNativeJsonObject
    ): AgentNativeToolExecutionResult {
        val connection = prepareConnection(requireConnection(connectionId))
        return try {
            if (connection.transport == AgentMcpTransportKind.DECLARATIVE_HTTP) {
                callDeclarative(connection, toolName, arguments)
            } else {
                withRemoteSession(connection) { session ->
                    val result = session.callTool(toolName, nativeToMcpObject(arguments))
                    if (result.isError) {
                        AgentNativeToolExecutionResult.failure(
                            "mcp_tool_error",
                            result.content.mapNotNull(AgentMcpContent::text).joinToString("\n").ifBlank { "MCP tool returned an error" },
                            retryable = false
                        )
                    } else {
                        AgentNativeToolExecutionResult.success(
                            output = linkedMapOf(
                                "connection_id" to connectionId,
                                "tool_name" to toolName,
                                "content" to result.content.map(::contentValue),
                                "structured_content" to result.structuredContent?.let(::mcpToNative)
                            ),
                            message = result.content.mapNotNull(AgentMcpContent::text).joinToString("\n").ifBlank { "MCP tool completed" },
                            metadata = mapOf("transport" to "streamable_http", "server" to connection.displayName)
                        )
                    }
                }
            }
        } catch (error: Throwable) {
            handleFailure(connectionId, error)
            AgentNativeToolExecutionResult.failure(
                code = if (isAuthenticationFailure(error)) "mcp_authentication_required" else "mcp_call_failed",
                message = error.message ?: "MCP tool call failed",
                retryable = !isAuthenticationFailure(error),
                details = mapOf("connection_id" to connectionId, "tool_name" to toolName)
            )
        }
    }

    fun close(connectionId: String) {
        sessions.remove(connectionId)?.let { session -> runBlocking { session.close() } }
    }

    fun closeAll() {
        val current = sessions.values.toList()
        sessions.clear()
        current.forEach { session -> runBlocking { session.close() } }
    }

    private suspend fun <T> withRemoteSession(
        connection: AgentMcpConnection,
        block: suspend (AgentMcpSession) -> T
    ): T {
        require(connection.isCallable(System.currentTimeMillis())) { "MCP connection requires authentication or setup" }
        registry.markConnecting(connection.id)
        val session = session(connection)
        return try {
            block(session)
        } catch (error: Throwable) {
            sessions.remove(connection.id)
            runCatching { session.close() }
            handleFailure(connection.id, error)
            throw error
        }
    }

    private suspend fun session(connection: AgentMcpConnection): AgentMcpSession {
        sessions[connection.id]?.takeIf { it.state == AgentMcpSessionState.ACTIVE }?.let { return it }
        val transport = AgentMcpStreamableHttpTransport(connection.endpoint, registry.requestHeaders(connection.id), client)
        val session = AgentMcpSession(transport)
        session.initialize(
            AgentMcpImplementationInfo(
                name = "signalasi-android",
                version = "1.0.0",
                title = "SignalASI Android"
            )
        )
        sessions[connection.id] = session
        return session
    }

    private suspend fun callDeclarative(
        connection: AgentMcpConnection,
        toolName: String,
        arguments: AgentNativeJsonObject
    ): AgentNativeToolExecutionResult = withContext(Dispatchers.IO) {
        val manifest = packageRepository.get(connection.id)
            ?: return@withContext AgentNativeToolExecutionResult.failure("mcp_package_missing", "Local MCP package metadata is missing")
        val tool = manifest.tools.firstOrNull { it.name == toolName }
            ?: return@withContext AgentNativeToolExecutionResult.failure("mcp_tool_missing", "MCP tool is not installed: $toolName")
        val base = URI(connection.endpoint)
        val path = renderPathTemplate(tool.pathTemplate, arguments)
        val target = base.resolve(path)
        require(target.scheme == base.scheme && target.host == base.host && effectivePort(target) == effectivePort(base)) {
            "Declarative MCP request cannot leave its configured server"
        }
        val secrets = registry.secrets(connection.id)
        val headers = registry.requestHeaders(connection.id) + tool.headerTemplates.mapValues { (_, template) ->
            renderHeaderTemplate(template, arguments, secrets)
        }
        val body = renderBodyTemplate(tool.bodyTemplate, arguments)
        val requestBody = if (tool.method in setOf("POST", "PUT", "PATCH", "DELETE")) {
            body.toRequestBody("application/json; charset=utf-8".toMediaType())
        } else null
        val request = Request.Builder().url(target.toASCIIString()).method(tool.method, requestBody).apply {
            header("Accept", "application/json, text/plain")
            headers.forEach { (name, value) -> if ('\r' !in value && '\n' !in value) header(name, value) }
        }.build()
        client.newCall(request).execute().use { response ->
            val responseText = response.body?.source()?.let { source ->
                source.request(MAX_RESPONSE_BYTES + 1L)
                val bytes = source.buffer.clone().readByteArray((source.buffer.size).coerceAtMost(MAX_RESPONSE_BYTES + 1L))
                require(bytes.size <= MAX_RESPONSE_BYTES) { "Declarative MCP response is too large" }
                bytes.toString(Charsets.UTF_8)
            }.orEmpty()
            if (!response.isSuccessful) {
                if (response.code == 401 || response.code == 403) {
                    throw AgentMcpHttpException(response.code, "MCP authentication expired")
                }
                throw AgentMcpHttpException(response.code, "MCP endpoint returned HTTP ${response.code}")
            }
            val decoded = parseResponse(responseText)
            val selected = selectJsonPath(decoded, tool.resultJsonPath)
            registry.markConnected(connection.id, manifest.tools.map(AgentMcpDeclarativeTool::name))
            AgentNativeToolExecutionResult.success(
                output = mapOf(
                    "connection_id" to connection.id,
                    "tool_name" to toolName,
                    "result" to selected,
                    "http_status" to response.code
                ),
                message = when (selected) {
                    is String -> selected.take(4_000)
                    else -> "MCP tool completed"
                },
                metadata = mapOf("transport" to "declarative_http", "server" to connection.displayName)
            )
        }
    }

    private fun requireConnection(id: String): AgentMcpConnection = registry.get(id)
        ?.takeIf { it.enabled }
        ?: throw IllegalArgumentException("MCP connection is not available: $id")

    private suspend fun prepareConnection(connection: AgentMcpConnection): AgentMcpConnection {
        if (connection.effectiveAuthState(System.currentTimeMillis()) == AgentMcpAuthState.REFRESHING &&
            connection.authProfile.refreshExchange != null
        ) {
            return authCoordinator.refreshIfNeeded(connection.id)
        }
        return connection
    }

    private fun handleFailure(id: String, error: Throwable) {
        registry.markFailure(id, error.message ?: error.javaClass.simpleName, isAuthenticationFailure(error))
    }

    private fun isAuthenticationFailure(error: Throwable): Boolean =
        (error as? AgentMcpHttpException)?.authenticationFailure == true ||
            error.cause?.let(::isAuthenticationFailure) == true

    private fun renderPathTemplate(template: String, arguments: AgentNativeJsonObject): String =
        ARGUMENT_PATTERN.replace(template) { match ->
            val value = nestedValue(arguments, match.groupValues[1])?.toString().orEmpty()
            URLEncoder.encode(value, Charsets.UTF_8.name()).replace("+", "%20")
        }

    private fun renderHeaderTemplate(
        template: String,
        arguments: AgentNativeJsonObject,
        secrets: Map<String, String>
    ): String {
        val withArguments = ARGUMENT_PATTERN.replace(template) { nestedValue(arguments, it.groupValues[1])?.toString().orEmpty() }
        return AUTH_PATTERN.replace(withArguments) { secrets[it.groupValues[1]].orEmpty() }.take(MAX_HEADER_VALUE_CHARS)
    }

    private fun renderBodyTemplate(template: String, arguments: AgentNativeJsonObject): String {
        if (template.isBlank() || template.trim() == "{{args}}") return AgentNativeJsonCodec.stringify(arguments)
        return ARGUMENT_PATTERN.replace(template) { match ->
            AgentNativeJsonCodec.stringify(nestedValue(arguments, match.groupValues[1]))
        }
    }

    private fun nestedValue(root: Map<String, Any?>, path: String): Any? {
        var value: Any? = root
        path.split('.').forEach { segment -> value = (value as? Map<*, *>)?.get(segment) }
        return value
    }

    private fun parseResponse(text: String): Any? {
        val trimmed = text.trim()
        if (trimmed.isBlank()) return emptyMap<String, Any?>()
        return runCatching {
            when {
                trimmed.startsWith('{') -> JSONObject(trimmed).toNativeMap()
                trimmed.startsWith('[') -> JSONArray(trimmed).toNativeList()
                else -> trimmed
            }
        }.getOrDefault(trimmed)
    }

    private fun selectJsonPath(value: Any?, path: String): Any? {
        if (path.isBlank()) return value
        var selected = value
        path.trim().removePrefix("$.").split('.').filter(String::isNotBlank).forEach { segment ->
            selected = when (val current = selected) {
                is Map<*, *> -> current[segment]
                is List<*> -> segment.toIntOrNull()?.let(current::getOrNull)
                else -> null
            }
        }
        return selected
    }

    private fun JSONObject.toNativeMap(): Map<String, Any?> = keys().asSequence().associateWith { key ->
        toNativeValue(opt(key))
    }

    private fun JSONArray.toNativeList(): List<Any?> = (0 until length()).map { toNativeValue(opt(it)) }

    private fun toNativeValue(value: Any?): Any? = when (value) {
        null, JSONObject.NULL -> null
        is JSONObject -> value.toNativeMap()
        is JSONArray -> value.toNativeList()
        is String, is Number, is Boolean -> value
        else -> value.toString()
    }

    private fun nativeToMcpObject(value: AgentNativeJsonObject): McpJsonObject = McpJson.valueOf(value) as McpJsonObject

    private fun mcpToNative(value: McpJsonValue): Any? = when (value) {
        is McpJsonObject -> value.entries.mapValues { mcpToNative(it.value) }
        is McpJsonArray -> value.values.map(::mcpToNative)
        is McpJsonString -> value.value
        is McpJsonNumber -> value.longOrNull() ?: value.doubleOrNull() ?: value.raw
        is McpJsonBoolean -> value.value
        McpJsonNull -> null
    }

    private fun contentValue(content: AgentMcpContent): AgentNativeJsonObject = linkedMapOf(
        "type" to content.type,
        "text" to content.text,
        "data" to content.data,
        "mime_type" to content.mimeType,
        "uri" to content.uri,
        "name" to content.name
    ).filterValues { it != null }

    private fun effectivePort(uri: URI): Int = when {
        uri.port >= 0 -> uri.port
        uri.scheme.equals("https", true) -> 443
        else -> 80
    }

    companion object {
        private const val MAX_RESPONSE_BYTES = 1024L * 1024L
        private const val MAX_HEADER_VALUE_CHARS = 8_192
        private val ARGUMENT_PATTERN = Regex("\\{\\{args\\.([A-Za-z0-9_.-]+)\\}\\}")
        private val AUTH_PATTERN = Regex("\\{\\{auth\\.([A-Za-z0-9_.-]+)\\}\\}")
    }
}

class AgentMcpAuthenticationCoordinator(
    private val registry: AgentMcpRegistry,
    private val client: OkHttpClient = AgentMcpStreamableHttpTransport.defaultClient()
) {

    suspend fun submitStep(connectionId: String, values: Map<String, String>): AgentMcpConnection {
        val connection = registry.get(connectionId)
            ?: throw IllegalArgumentException("MCP connection not found: $connectionId")
        val step = connection.currentAuthStep ?: throw IllegalStateException("No authentication step is pending")
        val exchange = step.exchange ?: return registry.submitAuthenticationStep(connectionId, values)
        val mapped = executeExchange(connection, exchange, values)
        return registry.submitAuthenticationStep(connectionId, values + mapped)
    }

    suspend fun refreshIfNeeded(connectionId: String): AgentMcpConnection {
        val connection = registry.get(connectionId)
            ?: throw IllegalArgumentException("MCP connection not found: $connectionId")
        if (connection.effectiveAuthState(System.currentTimeMillis()) != AgentMcpAuthState.REFRESHING) {
            return connection
        }
        val exchange = connection.authProfile.refreshExchange ?: return connection
        val mapped = executeExchange(connection, exchange, emptyMap())
        return registry.markAuthenticationRefreshed(connectionId, mapped)
    }

    private suspend fun executeExchange(
        connection: AgentMcpConnection,
        exchange: AgentMcpAuthExchangeSpec,
        fields: Map<String, String>
    ): Map<String, String> = withContext(Dispatchers.IO) {
        val base = URI(connection.endpoint)
        val secrets = registry.secrets(connection.id)
        val target = base.resolve(render(exchange.pathTemplate, fields, secrets, encodeForPath = true))
        require(target.scheme == base.scheme && target.host == base.host && effectivePort(target) == effectivePort(base)) {
            "MCP authentication cannot leave its configured server"
        }
        val headers = registry.requestHeaders(connection.id) + exchange.headerTemplates.mapValues { (_, template) ->
            render(template, fields, secrets, encodeForPath = false)
        }
        val bodyText = renderBody(exchange.bodyTemplate, fields, secrets)
        val body = if (exchange.method.uppercase(Locale.ROOT) in setOf("POST", "PUT", "PATCH")) {
            bodyText.toRequestBody("application/json; charset=utf-8".toMediaType())
        } else null
        val request = Request.Builder().url(target.toASCIIString()).method(exchange.method.uppercase(Locale.ROOT), body).apply {
            header("Accept", "application/json")
            headers.forEach { (name, value) -> if ('\r' !in value && '\n' !in value) header(name, value) }
        }.build()
        try {
            client.newCall(request).execute().use { response ->
                val responseText = response.body?.string().orEmpty().take(MAX_AUTH_RESPONSE_CHARS)
                if (response.code !in exchange.acceptedStatusCodes) {
                    throw AgentMcpHttpException(response.code, "MCP sign-in returned HTTP ${response.code}")
                }
                val decoded: Any? = when {
                    responseText.trim().startsWith('{') -> JSONObject(responseText).toNativeMap()
                    responseText.trim().startsWith('[') -> JSONArray(responseText).toNativeList()
                    else -> responseText
                }
                exchange.responseMappings.mapValues { (key, path) ->
                    select(decoded, path)?.toString()?.takeIf(String::isNotBlank)
                        ?: throw IllegalStateException("MCP sign-in response did not provide $key")
                }
            }
        } catch (error: Throwable) {
            registry.markFailure(connection.id, error.message ?: "MCP sign-in failed", authenticationFailure = true)
            throw error
        }
    }

    private fun render(
        template: String,
        fields: Map<String, String>,
        secrets: Map<String, String>,
        encodeForPath: Boolean
    ): String {
        val fieldRendered = FIELD_PATTERN.replace(template) { match ->
            encode(fields[match.groupValues[1]].orEmpty(), encodeForPath)
        }
        return AUTH_PATTERN.replace(fieldRendered) { match ->
            encode(secrets[match.groupValues[1]].orEmpty(), encodeForPath)
        }
    }

    private fun renderBody(
        template: String,
        fields: Map<String, String>,
        secrets: Map<String, String>
    ): String {
        if (template.isBlank()) return JSONObject(fields).toString()
        val fieldRendered = FIELD_PATTERN.replace(template) { match ->
            JSONObject.quote(fields[match.groupValues[1]].orEmpty())
        }
        return AUTH_PATTERN.replace(fieldRendered) { match ->
            JSONObject.quote(secrets[match.groupValues[1]].orEmpty())
        }
    }

    private fun encode(value: String, enabled: Boolean): String = if (!enabled) value else
        URLEncoder.encode(value, Charsets.UTF_8.name()).replace("+", "%20")

    private fun select(root: Any?, rawPath: String): Any? {
        var value = root
        rawPath.trim().removePrefix("$.").split('.').filter(String::isNotBlank).forEach { segment ->
            value = when (val current = value) {
                is Map<*, *> -> current[segment]
                is List<*> -> segment.toIntOrNull()?.let(current::getOrNull)
                else -> null
            }
        }
        return value
    }

    private fun JSONObject.toNativeMap(): Map<String, Any?> = keys().asSequence().associateWith { key ->
        toNativeValue(opt(key))
    }

    private fun JSONArray.toNativeList(): List<Any?> = (0 until length()).map { toNativeValue(opt(it)) }

    private fun toNativeValue(value: Any?): Any? = when (value) {
        null, JSONObject.NULL -> null
        is JSONObject -> value.toNativeMap()
        is JSONArray -> value.toNativeList()
        is String, is Number, is Boolean -> value
        else -> value.toString()
    }

    private fun effectivePort(uri: URI): Int = when {
        uri.port >= 0 -> uri.port
        uri.scheme.equals("https", true) -> 443
        else -> 80
    }

    companion object {
        private const val MAX_AUTH_RESPONSE_CHARS = 256 * 1024
        private val FIELD_PATTERN = Regex("\\{\\{field\\.([A-Za-z0-9_.-]+)\\}\\}")
        private val AUTH_PATTERN = Regex("\\{\\{auth\\.([A-Za-z0-9_.-]+)\\}\\}")
    }
}

object AgentMcpNativeTools {
    const val LIST_CONNECTIONS = "signalasi.mcp.connections.list"
    const val LIST_TOOLS = "signalasi.mcp.tools.list"
    const val CALL_TOOL = "signalasi.mcp.tool.call"

    val toolIds: Set<String> = setOf(LIST_CONNECTIONS, LIST_TOOLS, CALL_TOOL)

    fun definitions(context: Context): List<AgentNativeToolDefinition> {
        val appContext = context.applicationContext
        val registry = AgentMcpRegistry(EncryptedAgentMcpStore(appContext))
        val manager = AgentMcpClientManager(appContext, registry)
        return listOf(
            AgentNativeToolDefinition(
                descriptor = descriptor(
                    LIST_CONNECTIONS,
                    "List MCP connections",
                    "Lists installed MCP connections and their authentication and availability state.",
                    AgentNativeJsonSchema.objectSchema(additionalProperties = false),
                    AgentNativeToolRisk.LOW
                ),
                executor = AgentNativeToolExecutor {
                    AgentNativeToolExecutionResult.success(
                        output = mapOf("connections" to registry.list().map { connection ->
                            mapOf(
                                "id" to connection.id,
                                "name" to connection.displayName,
                                "state" to connection.state.wireValue,
                                "auth_state" to connection.effectiveAuthState(System.currentTimeMillis()).wireValue,
                                "enabled" to connection.enabled,
                                "tools" to connection.toolIds
                            )
                        }),
                        message = "MCP connections listed"
                    )
                },
                executorId = "signalasi.mcp.host",
                availabilityProvider = AgentNativeToolAvailabilityProvider { mcpAvailability(registry) }
            ),
            AgentNativeToolDefinition(
                descriptor = descriptor(
                    LIST_TOOLS,
                    "List MCP tools",
                    "Discovers tools exposed by one installed and authenticated MCP connection.",
                    AgentNativeJsonSchema.objectSchema(
                        properties = mapOf("connection_id" to AgentNativeJsonSchema.string(maxLength = 128)),
                        required = setOf("connection_id"),
                        additionalProperties = false
                    ),
                    AgentNativeToolRisk.LOW
                ),
                executor = AgentNativeToolExecutor { invocation ->
                    val id = invocation.input["connection_id"]?.toString().orEmpty()
                    runBlocking(Dispatchers.IO) {
                        runCatching { manager.listTools(id) }.fold(
                            onSuccess = { tools -> AgentNativeToolExecutionResult.success(
                                output = mapOf("connection_id" to id, "tools" to tools.map { tool ->
                                    mapOf("name" to tool.name, "title" to tool.title, "description" to tool.description)
                                }),
                                message = "MCP tools discovered"
                            ) },
                            onFailure = { AgentNativeToolExecutionResult.failure("mcp_discovery_failed", it.message ?: "MCP discovery failed", retryable = true) }
                        )
                    }
                },
                executorId = "signalasi.mcp.host",
                availabilityProvider = AgentNativeToolAvailabilityProvider { mcpAvailability(registry) }
            ),
            AgentNativeToolDefinition(
                descriptor = descriptor(
                    CALL_TOOL,
                    "Call MCP tool",
                    "Calls a named tool on an installed and authenticated MCP connection.",
                    AgentNativeJsonSchema.objectSchema(
                        properties = mapOf(
                            "connection_id" to AgentNativeJsonSchema.string(maxLength = 128),
                            "tool_name" to AgentNativeJsonSchema.string(maxLength = 192),
                            "arguments" to AgentNativeJsonSchema.objectSchema()
                        ),
                        required = setOf("connection_id", "tool_name", "arguments"),
                        additionalProperties = false
                    ),
                    AgentNativeToolRisk.MEDIUM,
                    timeoutMillis = 60_000L
                ),
                executor = AgentNativeToolExecutor { invocation ->
                    val id = invocation.input["connection_id"]?.toString().orEmpty()
                    val name = invocation.input["tool_name"]?.toString().orEmpty()
                    val arguments = (invocation.input["arguments"] as? Map<*, *>)?.entries?.mapNotNull { (key, value) ->
                        (key as? String)?.let { it to value }
                    }?.toMap().orEmpty()
                    runBlocking(Dispatchers.IO) { manager.callTool(id, name, arguments) }
                },
                executorId = "signalasi.mcp.host",
                provenanceMetadata = mapOf("protocol" to "mcp", "host" to "android"),
                availabilityProvider = AgentNativeToolAvailabilityProvider { mcpAvailability(registry) }
            )
        )
    }

    private fun descriptor(
        id: String,
        title: String,
        description: String,
        input: AgentNativeJsonSchema,
        risk: AgentNativeToolRisk,
        timeoutMillis: Long = 30_000L
    ) = AgentNativeToolDescriptor(
        id = id,
        version = "1.0.0",
        title = title,
        description = description,
        location = AgentNativeToolLocation.APPLICATION,
        inputSchema = input,
        outputSchema = AgentNativeJsonSchema.any(),
        risk = risk,
        capabilities = setOf("mcp", "tool_use"),
        timeoutMillis = timeoutMillis,
        idempotency = AgentNativeToolIdempotency.NON_IDEMPOTENT,
        availability = AgentNativeToolAvailability(AgentNativeToolAvailabilityStatus.REQUIRES_SETUP, "No MCP connection is ready")
    )

    private fun mcpAvailability(registry: AgentMcpRegistry): AgentNativeToolAvailability =
        if (registry.readyConnections().isNotEmpty()) {
            AgentNativeToolAvailability.AVAILABLE
        } else {
            AgentNativeToolAvailability(AgentNativeToolAvailabilityStatus.REQUIRES_SETUP, "No authenticated MCP connection is ready")
        }
}
