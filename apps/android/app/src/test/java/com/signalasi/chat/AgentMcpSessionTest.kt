package com.signalasi.chat

import java.util.Collections
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class AgentMcpSessionTest {
    @Test
    fun initializesNegotiatesCapabilitiesAndClosesTransport() = runBlocking {
        val transport = FakeTransport { request ->
            when (request.string("method")) {
                "initialize" -> initializeResponse(request, "tools", "resources", "prompts")
                else -> null
            }
        }
        val session = AgentMcpSession(transport)

        val initialized = session.initialize(
            AgentMcpImplementationInfo(
                name = "signalasi-android",
                version = "1.0.0",
                title = "SignalASI Android"
            ),
            McpJsonObject.of("roots" to McpJsonObject.of("listChanged" to true))
        )

        assertEquals(AgentMcpSessionState.ACTIVE, session.state)
        assertEquals(AgentMcpSessionConfig.PROTOCOL_VERSION_2025_11_25, initialized.protocolVersion)
        assertEquals("desktop-mcp", initialized.serverInfo.name)
        assertTrue(initialized.capabilities.tools)
        assertTrue(initialized.capabilities.resources)
        assertTrue(initialized.capabilities.prompts)
        assertEquals(initialized.protocolVersion, transport.negotiatedProtocolVersion)

        val sent = transport.sentMessages()
        val initialize = sent.first()
        assertEquals("2.0", initialize.string("jsonrpc"))
        assertEquals(1L, initialize.id())
        assertEquals("initialize", initialize.string("method"))
        val params = initialize.objectValue("params")!!
        assertEquals(AgentMcpSessionConfig.PROTOCOL_VERSION_2025_11_25, params.string("protocolVersion"))
        assertEquals("signalasi-android", params.objectValue("clientInfo")?.string("name"))
        assertEquals("notifications/initialized", sent.last().string("method"))
        assertFalse(sent.last().entries.containsKey("id"))

        session.close()
        assertTrue(transport.closed)
        assertEquals(AgentMcpSessionState.CLOSED, session.state)
    }

    @Test
    fun parsesToolSchemasStructuredResultsAndCorrelatesIds() = runBlocking {
        val transport = FakeTransport { request ->
            when (request.string("method")) {
                "initialize" -> initializeResponse(request, "tools")
                "tools/list" -> successResponse(
                    request,
                    McpJsonObject.of(
                        "tools" to listOf(
                            McpJsonObject.of(
                                "name" to "weather.current",
                                "title" to "Current weather",
                                "description" to "Reads current weather",
                                "inputSchema" to McpJsonObject.of(
                                    "type" to "object",
                                    "properties" to McpJsonObject.of(
                                        "city" to McpJsonObject.of("type" to "string")
                                    ),
                                    "required" to listOf("city"),
                                    "additionalProperties" to false
                                ),
                                "outputSchema" to McpJsonObject.of("type" to "object"),
                                "annotations" to McpJsonObject.of("readOnlyHint" to true)
                            )
                        ),
                        "nextCursor" to "page-2"
                    )
                )
                "tools/call" -> successResponse(
                    request,
                    McpJsonObject.of(
                        "content" to listOf(
                            McpJsonObject.of("type" to "text", "text" to "18 C")
                        ),
                        "structuredContent" to McpJsonObject.of(
                            "temperature" to 18,
                            "unit" to "C"
                        ),
                        "isError" to false
                    )
                )
                else -> null
            }
        }
        val session = initializedSession(transport, "tools")

        val tools = session.listTools("page-1")
        val result = session.callTool(
            "weather.current",
            McpJsonObject.of("city" to "Shanghai")
        )

        assertEquals(2L, tools.requestId)
        assertEquals("page-2", tools.nextCursor)
        assertEquals("weather.current", tools.items.single().name)
        assertEquals("object", tools.items.single().inputSchema.string("type"))
        assertEquals("string", tools.items.single().inputSchema
            .objectValue("properties")?.objectValue("city")?.string("type"))
        assertEquals(3L, result.requestId)
        assertEquals("18 C", result.content.single().text)
        assertEquals(18L, (result.structuredContent?.get("temperature") as McpJsonNumber).longOrNull())
        assertFalse(result.isError)

        val requests = transport.sentMessages().filter { it.entries.containsKey("id") }
        assertEquals(listOf(1L, 2L, 3L), requests.map { it.id() })
        val listParams = requests.first { it.string("method") == "tools/list" }.objectValue("params")
        assertEquals("page-1", listParams?.string("cursor"))
        val callParams = requests.first { it.string("method") == "tools/call" }.objectValue("params")!!
        assertEquals("Shanghai", callParams.objectValue("arguments")?.string("city"))

        session.close()
    }

    @Test
    fun listsAndReadsResourcesAndListsAndGetsPrompts() = runBlocking {
        val transport = FakeTransport { request ->
            when (request.string("method")) {
                "initialize" -> initializeResponse(request, "resources", "prompts")
                "resources/list" -> successResponse(
                    request,
                    McpJsonObject.of(
                        "resources" to listOf(
                            McpJsonObject.of(
                                "uri" to "file:///README.md",
                                "name" to "README.md",
                                "title" to "Project readme",
                                "mimeType" to "text/markdown",
                                "size" to 42,
                                "annotations" to McpJsonObject.of("priority" to 0.8)
                            )
                        )
                    )
                )
                "resources/read" -> successResponse(
                    request,
                    McpJsonObject.of(
                        "contents" to listOf(
                            McpJsonObject.of(
                                "uri" to "file:///README.md",
                                "mimeType" to "text/markdown",
                                "text" to "# SignalASI"
                            )
                        )
                    )
                )
                "prompts/list" -> successResponse(
                    request,
                    McpJsonObject.of(
                        "prompts" to listOf(
                            McpJsonObject.of(
                                "name" to "review",
                                "title" to "Review",
                                "arguments" to listOf(
                                    McpJsonObject.of(
                                        "name" to "focus",
                                        "description" to "Review focus",
                                        "required" to true
                                    )
                                )
                            )
                        ),
                        "nextCursor" to "prompt-page-2"
                    )
                )
                "prompts/get" -> successResponse(
                    request,
                    McpJsonObject.of(
                        "description" to "Focused review",
                        "messages" to listOf(
                            McpJsonObject.of(
                                "role" to "user",
                                "content" to McpJsonObject.of(
                                    "type" to "text",
                                    "text" to "Review cancellation behavior"
                                )
                            ),
                            McpJsonObject.of(
                                "role" to "assistant",
                                "content" to McpJsonObject.of(
                                    "type" to "resource",
                                    "resource" to McpJsonObject.of(
                                        "uri" to "file:///README.md",
                                        "mimeType" to "text/markdown",
                                        "text" to "# SignalASI"
                                    )
                                )
                            )
                        )
                    )
                )
                else -> null
            }
        }
        val session = initializedSession(transport, "resources", "prompts")

        val resources = session.listResources()
        val resource = session.readResource("file:///README.md")
        val prompts = session.listPrompts("prompt-page-1")
        val prompt = session.getPrompt("review", mapOf("focus" to "cancellation"))

        assertEquals("README.md", resources.items.single().name)
        assertEquals(42L, resources.items.single().size)
        assertEquals("# SignalASI", resource.contents.single().text)
        assertNull(resource.contents.single().blob)
        assertEquals("prompt-page-2", prompts.nextCursor)
        assertTrue(prompts.items.single().arguments.single().required)
        assertEquals("Focused review", prompt.description)
        assertEquals("user", prompt.messages.first().role)
        assertEquals("Review cancellation behavior", prompt.messages.first().content.text)
        assertEquals("file:///README.md", prompt.messages.last().content.resource?.uri)
        assertEquals("# SignalASI", prompt.messages.last().content.resource?.text)

        val getParams = transport.sentMessages()
            .first { it.string("method") == "prompts/get" }
            .objectValue("params")!!
        assertEquals("cancellation", getParams.objectValue("arguments")?.string("focus"))

        session.close()
    }

    @Test
    fun returnsStructuredRemoteErrorsAndRejectsUnnegotiatedCapabilities() = runBlocking {
        val transport = FakeTransport { request ->
            when (request.string("method")) {
                "initialize" -> initializeResponse(request, "tools")
                "tools/call" -> errorResponse(
                    request,
                    code = -32602,
                    message = "Invalid tool arguments",
                    data = McpJsonObject.of("field" to "city")
                )
                else -> null
            }
        }
        val session = initializedSession(transport, "tools")

        val remote = expectMcpFailure {
            session.callTool("weather.current", McpJsonObject.of("city" to 9))
        }
        assertEquals(AgentMcpErrorKind.REMOTE, remote.error.kind)
        assertEquals(-32602L, remote.error.rpcCode)
        assertEquals(2L, remote.error.requestId)
        assertEquals("tools/call", remote.error.method)
        assertEquals("city", (remote.error.data as McpJsonObject).string("field"))

        val unsupported = expectMcpFailure { session.listResources() }
        assertEquals(AgentMcpErrorKind.CAPABILITY_NOT_NEGOTIATED, unsupported.error.kind)
        assertEquals("resources/list", unsupported.error.method)

        session.close()
    }

    @Test
    fun timeoutAndCallerCancellationSendCancellationNotifications() = runBlocking {
        val transport = FakeTransport { request ->
            when (request.string("method")) {
                "initialize" -> initializeResponse(request, "tools")
                else -> null
            }
        }
        val issues = Collections.synchronizedList(mutableListOf<AgentMcpError>())
        val session = AgentMcpSession(
            transport,
            AgentMcpSessionConfig(defaultRequestTimeoutMillis = 40),
            object : AgentMcpSessionListener {
                override fun onProtocolIssue(error: AgentMcpError) {
                    issues += error
                }
            }
        )
        session.initialize(AgentMcpImplementationInfo("signalasi-test", "1.0"))

        val timeout = expectMcpFailure { session.callTool("slow.tool") }
        assertEquals(AgentMcpErrorKind.TIMEOUT, timeout.error.kind)
        val timeoutCancellation = transport.awaitMessage("notifications/cancelled")
        assertEquals(2L, timeoutCancellation.objectValue("params")?.number("requestId"))
        assertTrue(timeoutCancellation.objectValue("params")?.string("reason")!!.contains("timed out"))

        val job = launch {
            try {
                session.callTool("cancel.tool", timeoutMillis = 5_000)
                fail("Expected caller cancellation")
            } catch (_: CancellationException) {
                // Expected coroutine cancellation path.
            }
        }
        val call = transport.awaitRequest("tools/call", minimumId = 3)
        job.cancel()
        job.join()
        val callerCancellation = transport.awaitCancellation(call.id())
        assertEquals("Caller cancelled request", callerCancellation.objectValue("params")?.string("reason"))
        assertEquals(AgentMcpSessionState.ACTIVE, session.state)
        assertTrue(issues.none { it.kind == AgentMcpErrorKind.INVALID_JSON_RPC })

        session.close()
    }

    @Test
    fun boundedValidationFailsSessionAndPendingRequest() = runBlocking {
        val transport = FakeTransport { request ->
            when (request.string("method")) {
                "initialize" -> initializeResponse(request, "tools")
                "tools/call" -> successResponse(
                    request,
                    McpJsonObject.of(
                        "content" to listOf(
                            McpJsonObject.of("type" to "text", "text" to "x".repeat(2_000))
                        )
                    )
                )
                else -> null
            }
        }
        val session = AgentMcpSession(
            transport,
            AgentMcpSessionConfig(
                jsonLimits = McpJsonLimits(maxMessageBytes = 1_024, maxStringLength = 4_096)
            )
        )
        session.initialize(AgentMcpImplementationInfo("signalasi-test", "1.0"))

        val failure = expectMcpFailure { session.callTool("oversized.tool") }

        assertEquals(AgentMcpErrorKind.LIMIT_EXCEEDED, failure.error.kind)
        assertEquals(AgentMcpSessionState.FAILED, session.state)
        assertTrue(transport.closed)
        assertTrue(session.pendingRequestIds().isEmpty())

        session.close()
        assertEquals(AgentMcpSessionState.CLOSED, session.state)
    }

    private suspend fun initializedSession(
        transport: FakeTransport,
        vararg capabilities: String
    ): AgentMcpSession {
        val session = AgentMcpSession(transport)
        session.initialize(AgentMcpImplementationInfo("signalasi-test", "1.0"))
        capabilities.forEach { capability ->
            assertTrue("Missing test capability $capability", session.initialization?.capabilities?.has(capability) == true)
        }
        return session
    }

    private suspend fun expectMcpFailure(block: suspend () -> Unit): AgentMcpException {
        try {
            block()
            fail("Expected AgentMcpException")
        } catch (failure: AgentMcpException) {
            return failure
        }
        throw AssertionError("Unreachable")
    }

    private fun initializeResponse(
        request: McpJsonObject,
        vararg capabilities: String
    ): McpJsonObject = successResponse(
        request,
        McpJsonObject.of(
            "protocolVersion" to AgentMcpSessionConfig.PROTOCOL_VERSION_2025_11_25,
            "capabilities" to McpJsonObject(
                capabilities.associateWith { McpJsonObject.EMPTY }
            ),
            "serverInfo" to McpJsonObject.of(
                "name" to "desktop-mcp",
                "title" to "Trusted Desktop MCP",
                "version" to "2.1.0"
            ),
            "instructions" to "Use trusted desktop tools"
        )
    )

    private fun successResponse(request: McpJsonObject, result: McpJsonValue): McpJsonObject =
        McpJsonObject.of(
            "jsonrpc" to "2.0",
            "id" to assertNotNullValue(request["id"]),
            "result" to result
        )

    private fun errorResponse(
        request: McpJsonObject,
        code: Long,
        message: String,
        data: McpJsonValue? = null
    ): McpJsonObject = McpJsonObject.of(
        "jsonrpc" to "2.0",
        "id" to assertNotNullValue(request["id"]),
        "error" to McpJsonObject(
            linkedMapOf<String, McpJsonValue>().apply {
                put("code", McpJsonNumber(code.toString()))
                put("message", McpJsonString(message))
                data?.let { put("data", it) }
            }
        )
    )

    private fun assertNotNullValue(value: McpJsonValue?): McpJsonValue {
        assertNotNull(value)
        return value!!
    }

    private fun McpJsonObject.id(): Long = (this["id"] as McpJsonNumber).longOrNull()!!

    private fun McpJsonObject.number(name: String): Long? = (this[name] as? McpJsonNumber)?.longOrNull()

    private class FakeTransport(
        private val responder: suspend (McpJsonObject) -> McpJsonObject?
    ) : AgentMcpTransport {
        private val inbound = Channel<String>(Channel.UNLIMITED)
        private val sent = Collections.synchronizedList(mutableListOf<McpJsonObject>())

        @Volatile
        var opened = false
            private set

        @Volatile
        var closed = false
            private set

        @Volatile
        var negotiatedProtocolVersion: String? = null
            private set

        override suspend fun open() {
            check(!opened) { "Transport opened twice" }
            opened = true
        }

        override suspend fun send(message: String) {
            check(opened && !closed) { "Transport is not open" }
            val parsed = McpJson.parseObject(message)
            sent += parsed
            responder(parsed)?.let { inbound.send(McpJson.stringify(it)) }
        }

        override suspend fun receive(): String? = inbound.receiveCatching().getOrNull()

        override suspend fun onProtocolVersionNegotiated(protocolVersion: String) {
            negotiatedProtocolVersion = protocolVersion
        }

        override suspend fun close() {
            closed = true
            inbound.close()
        }

        fun sentMessages(): List<McpJsonObject> = synchronized(sent) { sent.toList() }

        suspend fun awaitMessage(method: String): McpJsonObject = withTimeout(1_000) {
            while (true) {
                sentMessages().firstOrNull { it.string("method") == method }?.let { return@withTimeout it }
                delay(5)
            }
            error("Unreachable")
        }

        suspend fun awaitRequest(method: String, minimumId: Long): McpJsonObject = withTimeout(1_000) {
            while (true) {
                sentMessages().firstOrNull {
                    it.string("method") == method &&
                        ((it["id"] as? McpJsonNumber)?.longOrNull() ?: -1L) >= minimumId
                }?.let { return@withTimeout it }
                delay(5)
            }
            error("Unreachable")
        }

        suspend fun awaitCancellation(requestId: Long): McpJsonObject = withTimeout(1_000) {
            while (true) {
                sentMessages().firstOrNull {
                    it.string("method") == "notifications/cancelled" &&
                        (it.objectValue("params")?.get("requestId") as? McpJsonNumber)?.longOrNull() == requestId
                }?.let { return@withTimeout it }
                delay(5)
            }
            error("Unreachable")
        }
    }
}
