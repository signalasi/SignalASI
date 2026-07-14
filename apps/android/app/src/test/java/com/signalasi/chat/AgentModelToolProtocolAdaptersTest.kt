package com.signalasi.chat

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class AgentModelToolProtocolAdaptersTest {
    @Test
    fun openAiCompatibleEncodesCatalogAndDecodesParallelCalls() {
        val adapter = OpenAiCompatibleAgentModelToolProtocolAdapter()
        val catalog = catalog()

        val tools = adapter.encodeToolCatalog(catalog)
        assertEquals(2, tools.length())
        assertEquals("function", tools.getJSONObject(0).getString("type"))
        val echoFunction = tools.getJSONObject(0).getJSONObject("function")
        assertEquals(ECHO_TOOL_ID, echoFunction.getString("name"))
        assertEquals("object", echoFunction.getJSONObject("parameters").getString("type"))

        val response = adapter.decodeResponse(
            """
            {
              "id": "chatcmpl-1",
              "model": "test-model",
              "choices": [{
                "message": {
                  "role": "assistant",
                  "content": [
                    {"type": "text", "text": "Checking both tools."},
                    {"type": "text", "text": "Please wait."}
                  ],
                  "tool_calls": [
                    {
                      "id": "call-openai-1",
                      "type": "function",
                      "function": {"name": "$ECHO_TOOL_ID", "arguments": "{\"value\":\"hello\"}"}
                    },
                    {
                      "id": "call-openai-2",
                      "type": "function",
                      "function": {"name": "$SUM_TOOL_ID", "arguments": "{\"left\":2,\"right\":3}"}
                    }
                  ]
                },
                "finish_reason": "tool_calls"
              }],
              "usage": {"prompt_tokens": 13, "completion_tokens": 7, "total_tokens": 20}
            }
            """.trimIndent(),
            catalog
        )

        assertEquals("Checking both tools.\nPlease wait.", response.assistantText)
        assertEquals(listOf("call-openai-1", "call-openai-2"), response.toolCalls.map { it.callId })
        assertEquals(listOf(ECHO_TOOL_ID, SUM_TOOL_ID), response.toolCalls.map { it.toolId })
        assertEquals("hello", response.toolCalls[0].arguments["value"])
        assertEquals(3, response.toolCalls[1].arguments["right"])
        assertEquals("1.0.0", response.toolCalls[0].toolVersion)
        assertEquals(13L, response.usage.inputTokens)
        assertEquals(7L, response.usage.outputTokens)
        assertEquals("tool_calls", response.providerMetadata["finish_reason"])
        assertEquals("chatcmpl-1", response.providerMetadata["response_id"])

        val messages = adapter.encodeConversation(
            listOf(
                AgentModelMessage.system("Use native tools."),
                AgentModelMessage.user("Echo and add."),
                AgentModelMessage(
                    AgentModelMessageRole.ASSISTANT,
                    text = response.assistantText,
                    toolCalls = response.toolCalls
                ),
                toolResult("call-openai-1", ECHO_TOOL_ID, mapOf("echo" to "hello"))
            )
        ).getJSONArray("messages")
        val assistant = messages.getJSONObject(2)
        assertEquals("call-openai-2", assistant.getJSONArray("tool_calls").getJSONObject(1).getString("id"))
        assertEquals("call-openai-1", messages.getJSONObject(3).getString("tool_call_id"))
    }

    @Test
    fun anthropicEncodesBlocksAndDecodesUsageAndStopReason() {
        val adapter = AnthropicAgentModelToolProtocolAdapter()
        val catalog = catalog()

        val tools = adapter.encodeToolCatalog(catalog)
        assertEquals(ECHO_TOOL_ID, tools.getJSONObject(0).getString("name"))
        assertEquals("object", tools.getJSONObject(0).getJSONObject("input_schema").getString("type"))

        val response = adapter.decodeResponse(
            """
            {
              "id": "msg-1",
              "model": "claude-test",
              "content": [
                {"type": "text", "text": "I will check."},
                {"type": "tool_use", "id": "call-anthropic-1", "name": "$ECHO_TOOL_ID", "input": {"value": "hi"}},
                {"type": "tool_use", "id": "call-anthropic-2", "name": "$SUM_TOOL_ID", "input": {"left": 4, "right": 5}},
                {"type": "text", "text": "Both are independent."}
              ],
              "stop_reason": "tool_use",
              "usage": {
                "input_tokens": 10,
                "cache_creation_input_tokens": 2,
                "cache_read_input_tokens": 3,
                "output_tokens": 8
              }
            }
            """.trimIndent(),
            catalog
        )

        assertEquals("I will check.\nBoth are independent.", response.assistantText)
        assertEquals(listOf("call-anthropic-1", "call-anthropic-2"), response.toolCalls.map { it.callId })
        assertEquals(15L, response.usage.inputTokens)
        assertEquals(8L, response.usage.outputTokens)
        assertEquals("tool_use", response.providerMetadata["finish_reason"])
        assertEquals("tool_use", response.providerMetadata["stop_reason"])

        val conversation = adapter.encodeConversation(
            listOf(
                AgentModelMessage.system("Use native tools."),
                AgentModelMessage.user("Run both."),
                AgentModelMessage(AgentModelMessageRole.ASSISTANT, toolCalls = response.toolCalls),
                toolResult("call-anthropic-1", ECHO_TOOL_ID, mapOf("echo" to "hi")),
                toolResult(
                    "call-anthropic-2",
                    SUM_TOOL_ID,
                    status = "failed",
                    output = mapOf("reason" to "offline")
                )
            )
        )
        assertEquals("Use native tools.", conversation.getString("system"))
        val messages = conversation.getJSONArray("messages")
        val assistantBlocks = messages.getJSONObject(1).getJSONArray("content")
        assertEquals("call-anthropic-2", assistantBlocks.getJSONObject(1).getString("id"))
        val resultBlocks = messages.getJSONObject(2).getJSONArray("content")
        assertEquals(2, resultBlocks.length())
        assertEquals("call-anthropic-1", resultBlocks.getJSONObject(0).getString("tool_use_id"))
        assertFalse(resultBlocks.getJSONObject(0).getBoolean("is_error"))
        assertTrue(resultBlocks.getJSONObject(1).getBoolean("is_error"))
    }

    @Test
    fun geminiEncodesFunctionsAndPreservesParallelCallIds() {
        val adapter = GeminiAgentModelToolProtocolAdapter()
        val catalog = catalog()

        val tools = adapter.encodeToolCatalog(catalog)
        val declarations = tools.getJSONObject(0).getJSONArray("functionDeclarations")
        assertEquals(2, declarations.length())
        assertEquals(ECHO_TOOL_ID, declarations.getJSONObject(0).getString("name"))
        assertEquals("object", declarations.getJSONObject(0).getJSONObject("response").getString("type"))

        val responseJson = """
            {
              "responseId": "gemini-response-1",
              "modelVersion": "gemini-test",
              "candidates": [{
                "content": {
                  "role": "model",
                  "parts": [
                    {"text": "Calling two functions."},
                    {"functionCall": {"id": "call-gemini-1", "name": "$ECHO_TOOL_ID", "args": {"value": "hola"}}},
                    {"functionCall": {"id": "call-gemini-2", "name": "$SUM_TOOL_ID", "args": {"left": 8, "right": 9}}},
                    {"text": "Results will follow."}
                  ]
                },
                "finishReason": "STOP"
              }],
              "usageMetadata": {
                "promptTokenCount": 21,
                "candidatesTokenCount": 9,
                "totalTokenCount": 30
              }
            }
        """.trimIndent()
        val response = adapter.decodeResponse(responseJson, catalog)

        assertEquals("Calling two functions.\nResults will follow.", response.assistantText)
        assertEquals(listOf("call-gemini-1", "call-gemini-2"), response.toolCalls.map { it.callId })
        assertEquals(21L, response.usage.inputTokens)
        assertEquals(9L, response.usage.outputTokens)
        assertEquals("STOP", response.providerMetadata["finish_reason"])
        assertEquals("gemini-response-1", response.providerMetadata["response_id"])

        val conversation = adapter.encodeConversation(
            listOf(
                AgentModelMessage.system("Use native tools."),
                AgentModelMessage.user("Run both."),
                AgentModelMessage(AgentModelMessageRole.ASSISTANT, toolCalls = response.toolCalls),
                toolResult("call-gemini-1", ECHO_TOOL_ID, mapOf("echo" to "hola")),
                toolResult("call-gemini-2", SUM_TOOL_ID, mapOf("sum" to 17))
            )
        )
        assertEquals(
            "Use native tools.",
            conversation.getJSONObject("system_instruction")
                .getJSONArray("parts")
                .getJSONObject(0)
                .getString("text")
        )
        val contents = conversation.getJSONArray("contents")
        val calls = contents.getJSONObject(1).getJSONArray("parts")
        assertEquals("call-gemini-1", calls.getJSONObject(0).getJSONObject("functionCall").getString("id"))
        val results = contents.getJSONObject(2).getJSONArray("parts")
        assertEquals(
            "call-gemini-2",
            results.getJSONObject(1).getJSONObject("functionResponse").getString("id")
        )
    }

    @Test
    fun rejectsMalformedUnknownAndOversizedCalls() {
        val catalog = catalog()
        expectProtocolError("malformed_tool_call") {
            OpenAiCompatibleAgentModelToolProtocolAdapter().decodeResponse(
                """
                {"choices":[{"message":{"tool_calls":[{
                  "id":"bad-1","type":"function",
                  "function":{"name":"$ECHO_TOOL_ID","arguments":"not-json"}
                }]},"finish_reason":"tool_calls"}]}
                """.trimIndent(),
                catalog
            )
        }

        expectProtocolError("unknown_tool") {
            AnthropicAgentModelToolProtocolAdapter().decodeResponse(
                """
                {"content":[{
                  "type":"tool_use","id":"unknown-1","name":"phone.test.unknown","input":{}
                }],"stop_reason":"tool_use"}
                """.trimIndent(),
                catalog
            )
        }

        val limits = AgentModelToolProtocolLimits(maxArgumentsCharacters = 32)
        expectProtocolError("oversized_tool_call") {
            GeminiAgentModelToolProtocolAdapter(limits).decodeResponse(
                """
                {"candidates":[{"content":{"parts":[{"functionCall":{
                  "id":"large-1","name":"$ECHO_TOOL_ID","args":{"value":"${"x".repeat(80)}"}
                }}]},"finishReason":"STOP"}]}
                """.trimIndent(),
                catalog
            )
        }
    }

    @Test
    fun emitsBoundedToolResultsForEveryProvider() {
        val limits = AgentModelToolProtocolLimits(maxToolResultCharacters = 220)
        val result = toolResult(
            callId = "bounded-call",
            toolId = ECHO_TOOL_ID,
            output = mapOf("payload" to "x".repeat(2_000)),
            message = "m".repeat(1_000)
        )
        val assistant = AgentModelMessage(
            AgentModelMessageRole.ASSISTANT,
            toolCalls = listOf(AgentModelToolCall("bounded-call", ECHO_TOOL_ID, mapOf("value" to "x")))
        )

        val openAiContent = OpenAiCompatibleAgentModelToolProtocolAdapter(limits)
            .encodeConversation(listOf(assistant, result))
            .getJSONArray("messages")
            .getJSONObject(1)
            .getString("content")
        assertBoundedSummary(openAiContent, limits.maxToolResultCharacters)

        val anthropicContent = AnthropicAgentModelToolProtocolAdapter(limits)
            .encodeConversation(listOf(assistant, result))
            .getJSONArray("messages")
            .getJSONObject(1)
            .getJSONArray("content")
            .getJSONObject(0)
            .getString("content")
        assertBoundedSummary(anthropicContent, limits.maxToolResultCharacters)

        val geminiResponse = GeminiAgentModelToolProtocolAdapter(limits)
            .encodeConversation(listOf(assistant, result))
            .getJSONArray("contents")
            .getJSONObject(1)
            .getJSONArray("parts")
            .getJSONObject(0)
            .getJSONObject("functionResponse")
        val geminiSummary = geminiResponse.getJSONObject("response")
        assertTrue(geminiSummary.toString().length <= limits.maxToolResultCharacters)
        assertTrue(geminiSummary.getBoolean("truncated"))
        assertEquals("bounded-call", geminiResponse.getString("id"))
    }

    @Test
    fun geminiCreatesStableIdsOnlyWhenProviderOmitsOptionalId() {
        val adapter = GeminiAgentModelToolProtocolAdapter()
        val responseJson = """
            {
              "responseId":"without-call-id",
              "candidates":[{"content":{"parts":[{"functionCall":{
                "name":"$ECHO_TOOL_ID","args":{"value":"hello"}
              }}]},"finishReason":"STOP"}]
            }
        """.trimIndent()

        val first = adapter.decodeResponse(responseJson, catalog()).toolCalls.single().callId
        val second = adapter.decodeResponse(responseJson, catalog()).toolCalls.single().callId

        assertEquals(first, second)
        assertTrue(first.startsWith("gemini_call_"))
        assertNotEquals("", first)
    }

    private fun assertBoundedSummary(content: String, limit: Int) {
        assertTrue(content.length <= limit)
        val json = JSONObject(content)
        assertTrue(json.getBoolean("truncated"))
        assertEquals("bounded-call", json.getString("tool_call_id"))
    }

    private fun expectProtocolError(code: String, block: () -> Unit) {
        try {
            block()
            fail("Expected AgentModelToolProtocolException with code $code")
        } catch (error: AgentModelToolProtocolException) {
            assertEquals(code, error.code)
        }
    }

    private fun toolResult(
        callId: String,
        toolId: String,
        output: AgentNativeJsonObject,
        status: String = "succeeded",
        message: String = ""
    ) = AgentModelMessage(
        AgentModelMessageRole.TOOL,
        toolResult = AgentModelToolResultContent(
            callId = callId,
            toolId = toolId,
            status = status,
            output = output,
            message = message
        )
    )

    private fun catalog(): List<AgentNativeToolDescriptor> = listOf(
        descriptor(
            id = ECHO_TOOL_ID,
            inputSchema = AgentNativeJsonSchema.objectSchema(
                properties = mapOf("value" to AgentNativeJsonSchema.string()),
                required = setOf("value"),
                additionalProperties = false
            )
        ),
        descriptor(
            id = SUM_TOOL_ID,
            inputSchema = AgentNativeJsonSchema.objectSchema(
                properties = mapOf(
                    "left" to AgentNativeJsonSchema.integer(),
                    "right" to AgentNativeJsonSchema.integer()
                ),
                required = setOf("left", "right"),
                additionalProperties = false
            )
        )
    )

    private fun descriptor(
        id: String,
        inputSchema: AgentNativeJsonSchema
    ) = AgentNativeToolDescriptor(
        id = id,
        version = "1.0.0",
        title = id,
        description = "Test adapter tool $id.",
        location = AgentNativeToolLocation.PHONE,
        inputSchema = inputSchema,
        outputSchema = AgentNativeJsonSchema.objectSchema(),
        risk = AgentNativeToolRisk.LOW
    )

    companion object {
        private const val ECHO_TOOL_ID = "phone.test.echo"
        private const val SUM_TOOL_ID = "phone.test.sum"
    }
}
