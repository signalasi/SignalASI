package com.signalasi.chat

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class AgentMcpHttpRuntimeTest {
    private lateinit var server: MockWebServer

    @Before
    fun startServer() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun stopServer() {
        server.shutdown()
    }

    @Test
    fun streamableHttpInitializesDiscoversAndCallsToolWithSessionHeaders() = runBlocking {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val body = request.body.readUtf8()
                val message = JSONObject(body)
                return when (message.optString("method")) {
                    "initialize" -> success(message, JSONObject()
                        .put("protocolVersion", AgentMcpSessionConfig.PROTOCOL_VERSION_2025_11_25)
                        .put("serverInfo", JSONObject().put("name", "test-mcp").put("version", "1.0.0"))
                        .put("capabilities", JSONObject().put("tools", JSONObject())))
                        .addHeader("Mcp-Session-Id", "session-123")
                    "notifications/initialized" -> MockResponse().setResponseCode(202)
                    "tools/list" -> success(message, JSONObject().put("tools", JSONArray().put(JSONObject()
                        .put("name", "relay.switch")
                        .put("title", "Switch relay")
                        .put("description", "Switches a relay")
                        .put("inputSchema", JSONObject().put("type", "object")))))
                    "tools/call" -> success(message, JSONObject()
                        .put("content", JSONArray().put(JSONObject().put("type", "text").put("text", "Relay enabled")))
                        .put("structuredContent", JSONObject().put("enabled", true))
                        .put("isError", false))
                    else -> MockResponse().setResponseCode(400)
                }
            }
        }
        val transport = AgentMcpStreamableHttpTransport(
            server.url("/mcp").toString(),
            mapOf("Authorization" to "Bearer test-token")
        )
        val session = AgentMcpSession(transport)

        session.initialize(AgentMcpImplementationInfo("signalasi-test", "1.0.0"))
        val tools = session.listTools()
        val result = session.callTool("relay.switch", McpJsonObject.of("enabled" to true))
        session.close()

        assertEquals("relay.switch", tools.items.single().name)
        assertEquals("Relay enabled", result.content.single().text)
        assertEquals(true, (result.structuredContent?.get("enabled") as McpJsonBoolean).value)

        val requests = (0 until 4).map { server.takeRequest() }
        assertTrue(requests.all { it.getHeader("Authorization") == "Bearer test-token" })
        assertEquals("session-123", requests[1].getHeader("Mcp-Session-Id"))
        assertEquals(AgentMcpSessionConfig.PROTOCOL_VERSION_2025_11_25, requests[2].getHeader("MCP-Protocol-Version"))
    }

    @Test
    fun authenticationExchangeMapsReturnedTokenIntoEncryptedStoreContract() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(201).setBody(
            JSONObject().put("session", JSONObject().put("access_token", "mapped-token")).toString()
        ).addHeader("Content-Type", "application/json"))
        val store = InMemoryAgentMcpStore()
        val registry = AgentMcpRegistry(store) { 1_000L }
        val profile = AgentMcpAuthProfile(
            method = AgentMcpAuthMethod.DYNAMIC,
            steps = listOf(
                AgentMcpAuthStepSpec(
                    id = "login",
                    title = "Sign in",
                    fields = listOf(
                        AgentMcpAuthFieldSpec("username", "Username", AgentMcpAuthFieldType.TEXT),
                        AgentMcpAuthFieldSpec("password", "Password", AgentMcpAuthFieldType.PASSWORD)
                    ),
                    exchange = AgentMcpAuthExchangeSpec(
                        method = "POST",
                        pathTemplate = "/login",
                        bodyTemplate = "{\"username\":{{field.username}},\"password\":{{field.password}}}",
                        responseMappings = mapOf("access_token" to "$.session.access_token"),
                        acceptedStatusCodes = setOf(201)
                    )
                )
            ),
            accessTokenTtlMillis = 60_000L
        )
        val connection = registry.addRemote("Relay", server.url("/mcp").toString(), profile, id = "relay")
        registry.beginAuthentication(connection.id)

        val authenticated = AgentMcpAuthenticationCoordinator(registry).submitStep(
            connection.id,
            mapOf("username" to "operator", "password" to "secret")
        )

        assertEquals(AgentMcpAuthState.AUTHENTICATED, authenticated.authState)
        assertEquals("Bearer mapped-token", registry.requestHeaders(connection.id)["Authorization"])
        val request = server.takeRequest()
        assertEquals("/login", request.path)
        assertEquals("operator", JSONObject(request.body.readUtf8()).getString("username"))
        assertFalse(AgentMcpConnectionCodec.encode(registry.list()).contains("mapped-token"))
    }

    @Test
    fun parsesMultipleSseEventsAndIgnoresComments() {
        val transport = AgentMcpStreamableHttpTransport("https://example.com/mcp")

        val messages = transport.parseSse(
            ": keepalive\n" +
                "data: {\"jsonrpc\":\"2.0\",\"id\":1}\n\n" +
                "event: message\n" +
                "data: {\"jsonrpc\":\"2.0\",\n" +
                "data: \"method\":\"ping\"}\n\n"
        )

        assertEquals(2, messages.size)
        assertTrue(messages.first().contains("\"id\":1"))
        assertTrue(messages.last().contains("\"method\":\"ping\""))
    }

    private fun success(request: JSONObject, result: JSONObject): MockResponse = MockResponse()
        .setResponseCode(200)
        .addHeader("Content-Type", "application/json")
        .setBody(JSONObject().put("jsonrpc", "2.0").put("id", request.getLong("id")).put("result", result).toString())
}
