package com.signalasi.chat

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

enum class AgentModelToolProvider(val wireValue: String) {
    OPENAI_COMPATIBLE("openai_compatible"),
    ANTHROPIC("anthropic"),
    GEMINI("gemini")
}

data class AgentModelToolProtocolLimits(
    val maxToolCalls: Int = 32,
    val maxCallIdCharacters: Int = 256,
    val maxToolNameCharacters: Int = 128,
    val maxArgumentsCharacters: Int = 65_536,
    val maxToolResultCharacters: Int = 8_192,
    val maxResponseCharacters: Int = 1_048_576,
    val maxJsonDepth: Int = 64
) {
    init {
        require(maxToolCalls > 0) { "Maximum tool calls must be positive" }
        require(maxCallIdCharacters > 0) { "Maximum call id length must be positive" }
        require(maxToolNameCharacters > 0) { "Maximum tool name length must be positive" }
        require(maxArgumentsCharacters > 1) { "Maximum arguments length must fit a JSON object" }
        require(maxToolResultCharacters >= 128) { "Maximum tool result length must be at least 128" }
        require(maxResponseCharacters > 1) { "Maximum response length must fit a JSON object" }
        require(maxJsonDepth > 0) { "Maximum JSON depth must be positive" }
    }

    companion object {
        val DEFAULT = AgentModelToolProtocolLimits()
    }
}

class AgentModelToolProtocolException(
    val code: String,
    message: String,
    cause: Throwable? = null
) : IllegalArgumentException(message, cause)

interface AgentModelToolProtocolAdapter {
    val provider: AgentModelToolProvider

    fun encodeToolCatalog(catalog: List<AgentNativeToolDescriptor>): JSONArray

    /** Returns the provider request fields that carry system instructions and message history. */
    fun encodeConversation(messages: List<AgentModelMessage>): JSONObject

    fun decodeResponse(
        responseJson: String,
        catalog: List<AgentNativeToolDescriptor>
    ): AgentModelResponse

    fun encodeTools(catalog: List<AgentNativeToolDescriptor>): JSONArray = encodeToolCatalog(catalog)

    fun encodeMessages(messages: List<AgentModelMessage>): JSONObject = encodeConversation(messages)

    fun parseResponse(
        responseJson: String,
        catalog: List<AgentNativeToolDescriptor>
    ): AgentModelResponse = decodeResponse(responseJson, catalog)
}

object AgentModelToolProtocolAdapters {
    fun forProvider(
        provider: AgentModelToolProvider,
        limits: AgentModelToolProtocolLimits = AgentModelToolProtocolLimits.DEFAULT
    ): AgentModelToolProtocolAdapter = when (provider) {
        AgentModelToolProvider.OPENAI_COMPATIBLE ->
            OpenAiCompatibleAgentModelToolProtocolAdapter(limits)
        AgentModelToolProvider.ANTHROPIC -> AnthropicAgentModelToolProtocolAdapter(limits)
        AgentModelToolProvider.GEMINI -> GeminiAgentModelToolProtocolAdapter(limits)
    }

    fun openAiCompatible(
        limits: AgentModelToolProtocolLimits = AgentModelToolProtocolLimits.DEFAULT
    ) = OpenAiCompatibleAgentModelToolProtocolAdapter(limits)

    fun anthropic(
        limits: AgentModelToolProtocolLimits = AgentModelToolProtocolLimits.DEFAULT
    ) = AnthropicAgentModelToolProtocolAdapter(limits)

    fun gemini(
        limits: AgentModelToolProtocolLimits = AgentModelToolProtocolLimits.DEFAULT
    ) = GeminiAgentModelToolProtocolAdapter(limits)
}

class OpenAiCompatibleAgentModelToolProtocolAdapter(
    limits: AgentModelToolProtocolLimits = AgentModelToolProtocolLimits.DEFAULT
) : StrictAgentModelToolProtocolAdapter(AgentModelToolProvider.OPENAI_COMPATIBLE, limits) {
    override fun encodeToolCatalog(catalog: List<AgentNativeToolDescriptor>): JSONArray {
        checkedCatalog(catalog)
        return JSONArray().apply {
            catalog.forEach { descriptor ->
                put(
                    JSONObject()
                        .put("type", "function")
                        .put(
                            "function",
                            JSONObject()
                                .put("name", descriptor.id)
                                .put("description", descriptor.description)
                                .put("parameters", descriptor.inputSchema.document.toJsonObject())
                        )
                )
            }
        }
    }

    override fun encodeConversation(messages: List<AgentModelMessage>): JSONObject {
        val encoded = JSONArray()
        messages.forEachIndexed { index, message ->
            val path = "messages[$index]"
            when (message.role) {
                AgentModelMessageRole.SYSTEM -> encoded.put(textMessage("system", message.text))
                AgentModelMessageRole.USER -> encoded.put(textMessage("user", message.text))
                AgentModelMessageRole.ASSISTANT -> {
                    val item = JSONObject().put("role", "assistant")
                    item.put("content", if (message.text.isBlank()) JSONObject.NULL else message.text)
                    if (message.toolCalls.isNotEmpty()) {
                        checkCallCount(message.toolCalls.size, path)
                        item.put("tool_calls", JSONArray().apply {
                            message.toolCalls.forEachIndexed { callIndex, call ->
                                checkedOutboundCall(call, "$path.tool_calls[$callIndex]")
                                put(
                                    JSONObject()
                                        .put("id", call.callId)
                                        .put("type", "function")
                                        .put(
                                            "function",
                                            JSONObject()
                                                .put("name", call.toolId)
                                                .put("arguments", AgentNativeJsonCodec.stringify(call.arguments))
                                        )
                                )
                            }
                        })
                    }
                    encoded.put(item)
                }
                AgentModelMessageRole.TOOL -> {
                    val result = requireNotNull(message.toolResult)
                    val bounded = boundedResult(result, path)
                    encoded.put(
                        JSONObject()
                            .put("role", "tool")
                            .put("tool_call_id", result.callId)
                            .put("content", bounded.jsonText)
                    )
                }
            }
        }
        return JSONObject().put("messages", encoded)
    }

    override fun decodeResponse(
        responseJson: String,
        catalog: List<AgentNativeToolDescriptor>
    ): AgentModelResponse {
        val root = parseRoot(responseJson)
        val choices = root.requiredArray("choices", "response")
        if (choices.length() == 0) protocolFailure("malformed_response", "OpenAI response has no choices")
        val choice = choices.requiredObject(0, "response.choices")
        val message = choice.optionalObject("message", "response.choices[0]")
        val text = if (message == null) {
            choice.optionalString("text", "response.choices[0]").orEmpty()
        } else {
            parseOpenAiContent(message.optionalValue("content"), "response.choices[0].message.content")
                .ifBlank { message.optionalString("refusal", "response.choices[0].message").orEmpty() }
        }
        val calls = message?.optionalArray("tool_calls", "response.choices[0].message")?.let { array ->
            parseCalls(array, catalog) { item, path ->
                val type = item.optionalString("type", path)
                if (type != null && type != "function") {
                    protocolFailure("malformed_tool_call", "$path.type must be function")
                }
                val function = item.requiredObject("function", path)
                ParsedCall(
                    id = item.requiredString("id", path),
                    name = function.requiredString("name", "$path.function"),
                    arguments = parseOpenAiArguments(function, "$path.function")
                )
            }
        }.orEmpty()
        val usage = root.optionalObject("usage", "response")
        val finishReason = choice.optionalString("finish_reason", "response.choices[0]")
            ?: choice.optionalString("finishReason", "response.choices[0]")
        return modelResponse(
            text = text,
            calls = calls,
            usage = AgentModelUsage(
                inputTokens = usage?.tokenCount("prompt_tokens", "input_tokens", path = "response.usage") ?: 0,
                outputTokens = usage?.tokenCount("completion_tokens", "output_tokens", path = "response.usage") ?: 0
            ),
            metadata = metadata(finishReason).apply {
                root.optionalString("id", "response")?.let { put("response_id", it) }
                root.optionalString("model", "response")?.let { put("model", it) }
            }
        )
    }

    private fun parseOpenAiArguments(function: JSONObject, path: String): AgentNativeJsonObject {
        val value = function.requiredValue("arguments", path)
        return when (value) {
            is String -> parseArgumentString(value, "$path.arguments")
            is JSONObject -> checkedArguments(value.toNativeObject("$path.arguments", limits), "$path.arguments")
            else -> protocolFailure(
                "malformed_tool_call",
                "$path.arguments must be a JSON object or a JSON-encoded object"
            )
        }
    }

    private fun parseOpenAiContent(value: Any?, path: String): String = when (value) {
        null, JSONObject.NULL -> ""
        is String -> value
        is JSONArray -> buildList {
            for (index in 0 until value.length()) {
                when (val block = value.get(index)) {
                    is String -> if (block.isNotBlank()) add(block)
                    is JSONObject -> {
                        val type = block.optionalString("type", "$path[$index]")
                        if (type == null || type == "text" || type == "output_text") {
                            block.optionalString("text", "$path[$index]")
                                ?.takeIf(String::isNotBlank)
                                ?.let(::add)
                        }
                    }
                }
            }
        }.joinToString("\n").trim()
        else -> protocolFailure("malformed_response", "$path must be a string, array, or null")
    }

    private fun textMessage(role: String, text: String) = JSONObject()
        .put("role", role)
        .put("content", text)
}

typealias OpenAICompatibleAgentModelToolProtocolAdapter =
    OpenAiCompatibleAgentModelToolProtocolAdapter

class AnthropicAgentModelToolProtocolAdapter(
    limits: AgentModelToolProtocolLimits = AgentModelToolProtocolLimits.DEFAULT
) : StrictAgentModelToolProtocolAdapter(AgentModelToolProvider.ANTHROPIC, limits) {
    override fun encodeToolCatalog(catalog: List<AgentNativeToolDescriptor>): JSONArray {
        checkedCatalog(catalog)
        return JSONArray().apply {
            catalog.forEach { descriptor ->
                put(
                    JSONObject()
                        .put("name", descriptor.id)
                        .put("description", descriptor.description)
                        .put("input_schema", descriptor.inputSchema.document.toJsonObject())
                )
            }
        }
    }

    override fun encodeConversation(messages: List<AgentModelMessage>): JSONObject {
        val result = JSONObject()
        messages.asSequence()
            .filter { it.role == AgentModelMessageRole.SYSTEM }
            .map(AgentModelMessage::text)
            .filter(String::isNotBlank)
            .joinToString("\n\n")
            .takeIf(String::isNotBlank)
            ?.let { result.put("system", it) }

        val encoded = JSONArray()
        var index = 0
        while (index < messages.size) {
            val message = messages[index]
            when (message.role) {
                AgentModelMessageRole.SYSTEM -> Unit
                AgentModelMessageRole.USER -> encoded.put(
                    JSONObject()
                        .put("role", "user")
                        .put("content", JSONArray().put(textBlock(message.text)))
                )
                AgentModelMessageRole.ASSISTANT -> {
                    val blocks = JSONArray()
                    if (message.text.isNotBlank()) blocks.put(textBlock(message.text))
                    checkCallCount(message.toolCalls.size, "messages[$index]")
                    message.toolCalls.forEachIndexed { callIndex, call ->
                        checkedOutboundCall(call, "messages[$index].tool_calls[$callIndex]")
                        blocks.put(
                            JSONObject()
                                .put("type", "tool_use")
                                .put("id", call.callId)
                                .put("name", call.toolId)
                                .put("input", call.arguments.toJsonObject())
                        )
                    }
                    encoded.put(JSONObject().put("role", "assistant").put("content", blocks))
                }
                AgentModelMessageRole.TOOL -> {
                    val blocks = JSONArray()
                    while (index < messages.size && messages[index].role == AgentModelMessageRole.TOOL) {
                        val toolResult = requireNotNull(messages[index].toolResult)
                        val bounded = boundedResult(toolResult, "messages[$index]")
                        blocks.put(
                            JSONObject()
                                .put("type", "tool_result")
                                .put("tool_use_id", toolResult.callId)
                                .put("content", bounded.jsonText)
                                .put("is_error", toolResult.status !in SUCCESS_STATUSES)
                        )
                        index += 1
                    }
                    encoded.put(JSONObject().put("role", "user").put("content", blocks))
                    continue
                }
            }
            index += 1
        }
        return result.put("messages", encoded)
    }

    override fun decodeResponse(
        responseJson: String,
        catalog: List<AgentNativeToolDescriptor>
    ): AgentModelResponse {
        val root = parseRoot(responseJson)
        val content = root.requiredArray("content", "response")
        val text = mutableListOf<String>()
        val callBlocks = JSONArray()
        for (index in 0 until content.length()) {
            val block = content.requiredObject(index, "response.content")
            when (block.requiredString("type", "response.content[$index]")) {
                "text" -> block.requiredString("text", "response.content[$index]")
                    .takeIf(String::isNotBlank)
                    ?.let(text::add)
                "tool_use" -> callBlocks.put(block)
            }
        }
        val calls = parseCalls(callBlocks, catalog) { item, path ->
            ParsedCall(
                id = item.requiredString("id", path),
                name = item.requiredString("name", path),
                arguments = checkedArguments(
                    item.requiredObject("input", path).toNativeObject("$path.input", limits),
                    "$path.input"
                )
            )
        }
        val usage = root.optionalObject("usage", "response")
        val inputTokens = usage?.let {
            saturatingSum(
                it.tokenCount("input_tokens", path = "response.usage"),
                it.tokenCount("cache_creation_input_tokens", path = "response.usage"),
                it.tokenCount("cache_read_input_tokens", path = "response.usage")
            )
        } ?: 0
        val stopReason = root.optionalString("stop_reason", "response")
        return modelResponse(
            text = text.joinToString("\n").trim(),
            calls = calls,
            usage = AgentModelUsage(
                inputTokens = inputTokens,
                outputTokens = usage?.tokenCount("output_tokens", path = "response.usage") ?: 0
            ),
            metadata = metadata(stopReason).apply {
                stopReason?.let { put("stop_reason", it) }
                root.optionalString("id", "response")?.let { put("response_id", it) }
                root.optionalString("model", "response")?.let { put("model", it) }
            }
        )
    }

    private fun textBlock(text: String) = JSONObject().put("type", "text").put("text", text)

    companion object {
        private val SUCCESS_STATUSES = setOf("success", "succeeded")
    }
}

class GeminiAgentModelToolProtocolAdapter(
    limits: AgentModelToolProtocolLimits = AgentModelToolProtocolLimits.DEFAULT
) : StrictAgentModelToolProtocolAdapter(AgentModelToolProvider.GEMINI, limits) {
    override fun encodeToolCatalog(catalog: List<AgentNativeToolDescriptor>): JSONArray {
        checkedCatalog(catalog)
        val declarations = JSONArray()
        catalog.forEach { descriptor ->
            declarations.put(
                JSONObject()
                    .put("name", descriptor.id)
                    .put("description", descriptor.description)
                    .put("parameters", descriptor.inputSchema.document.toJsonObject())
                    .put("response", descriptor.outputSchema.document.toJsonObject())
            )
        }
        return JSONArray().put(JSONObject().put("functionDeclarations", declarations))
    }

    override fun encodeConversation(messages: List<AgentModelMessage>): JSONObject {
        val result = JSONObject()
        val systemText = messages.asSequence()
            .filter { it.role == AgentModelMessageRole.SYSTEM }
            .map(AgentModelMessage::text)
            .filter(String::isNotBlank)
            .joinToString("\n\n")
        if (systemText.isNotBlank()) {
            result.put(
                "system_instruction",
                JSONObject().put("parts", JSONArray().put(JSONObject().put("text", systemText)))
            )
        }

        val contents = JSONArray()
        var index = 0
        while (index < messages.size) {
            val message = messages[index]
            when (message.role) {
                AgentModelMessageRole.SYSTEM -> Unit
                AgentModelMessageRole.USER -> contents.put(content("user", JSONArray().put(textPart(message.text))))
                AgentModelMessageRole.ASSISTANT -> {
                    val parts = JSONArray()
                    if (message.text.isNotBlank()) parts.put(textPart(message.text))
                    checkCallCount(message.toolCalls.size, "messages[$index]")
                    message.toolCalls.forEachIndexed { callIndex, call ->
                        checkedOutboundCall(call, "messages[$index].tool_calls[$callIndex]")
                        parts.put(
                            JSONObject().put(
                                "functionCall",
                                JSONObject()
                                    .put("id", call.callId)
                                    .put("name", call.toolId)
                                    .put("args", call.arguments.toJsonObject())
                            )
                        )
                    }
                    contents.put(content("model", parts))
                }
                AgentModelMessageRole.TOOL -> {
                    val parts = JSONArray()
                    while (index < messages.size && messages[index].role == AgentModelMessageRole.TOOL) {
                        val toolResult = requireNotNull(messages[index].toolResult)
                        val bounded = boundedResult(toolResult, "messages[$index]")
                        parts.put(
                            JSONObject().put(
                                "functionResponse",
                                JSONObject()
                                    .put("id", toolResult.callId)
                                    .put("name", toolResult.toolId)
                                    .put("response", bounded.value.toJsonObject())
                            )
                        )
                        index += 1
                    }
                    contents.put(content("user", parts))
                    continue
                }
            }
            index += 1
        }
        return result.put("contents", contents)
    }

    override fun decodeResponse(
        responseJson: String,
        catalog: List<AgentNativeToolDescriptor>
    ): AgentModelResponse {
        val root = parseRoot(responseJson)
        val candidates = root.requiredArray("candidates", "response")
        if (candidates.length() == 0) protocolFailure("malformed_response", "Gemini response has no candidates")
        val candidate = candidates.requiredObject(0, "response.candidates")
        val parts = candidate.requiredObject("content", "response.candidates[0]")
            .requiredArray("parts", "response.candidates[0].content")
        val text = mutableListOf<String>()
        val rawCalls = mutableListOf<Pair<JSONObject, Int>>()
        for (index in 0 until parts.length()) {
            val part = parts.requiredObject(index, "response.candidates[0].content.parts")
            part.optionalString("text", "response.candidates[0].content.parts[$index]")
                ?.takeIf(String::isNotBlank)
                ?.let(text::add)
            part.optionalObject("functionCall", "response.candidates[0].content.parts[$index]")
                ?.let { rawCalls += it to index }
        }
        checkCallCount(rawCalls.size, "response.candidates[0].content.parts")
        val catalogById = checkedCatalog(catalog)
        val callIds = LinkedHashSet<String>()
        val responseSeed = root.optionalString("responseId", "response")
            ?: AgentNativeJsonCodec.sha256(responseJson)
        val calls = rawCalls.mapIndexed { callIndex, (function, partIndex) ->
            val path = "response.candidates[0].content.parts[$partIndex].functionCall"
            try {
                val providerId = function.optionalString("id", path)
                val callId = providerId ?: syntheticGeminiCallId(responseSeed, callIndex, partIndex)
                val name = function.requiredString("name", path)
                val arguments = function.optionalValue("args")?.let { value ->
                    if (value !is JSONObject) {
                        protocolFailure("malformed_tool_call", "$path.args must be a JSON object")
                    }
                    checkedArguments(value.toNativeObject("$path.args", limits), "$path.args")
                }.orEmpty()
                checkedParsedCall(ParsedCall(callId, name, arguments), path, catalogById, callIds)
            } catch (error: AgentModelToolProtocolException) {
                throw error.asMalformedToolCall(path)
            }
        }
        val usage = root.optionalObject("usageMetadata", "response")
        val finishReason = candidate.optionalString("finishReason", "response.candidates[0]")
        return modelResponse(
            text = text.joinToString("\n").trim(),
            calls = calls,
            usage = AgentModelUsage(
                inputTokens = usage?.tokenCount("promptTokenCount", path = "response.usageMetadata") ?: 0,
                outputTokens = usage?.tokenCount("candidatesTokenCount", path = "response.usageMetadata")
                    ?: geminiOutputFromTotal(usage)
            ),
            metadata = metadata(finishReason).apply {
                root.optionalString("responseId", "response")?.let { put("response_id", it) }
                root.optionalString("modelVersion", "response")?.let { put("model_version", it) }
            }
        )
    }

    private fun syntheticGeminiCallId(responseSeed: String, callIndex: Int, partIndex: Int): String {
        val digest = AgentNativeJsonCodec.sha256("$responseSeed:$callIndex:$partIndex")
        return "gemini_call_${digest.take(24)}"
    }

    private fun geminiOutputFromTotal(usage: JSONObject?): Long {
        if (usage == null) return 0
        val total = usage.tokenCount("totalTokenCount", path = "response.usageMetadata")
        val input = usage.tokenCount("promptTokenCount", path = "response.usageMetadata")
        return (total - input).coerceAtLeast(0)
    }

    private fun textPart(text: String) = JSONObject().put("text", text)

    private fun content(role: String, parts: JSONArray) = JSONObject()
        .put("role", role)
        .put("parts", parts)
}

abstract class StrictAgentModelToolProtocolAdapter(
    final override val provider: AgentModelToolProvider,
    protected val limits: AgentModelToolProtocolLimits
) : AgentModelToolProtocolAdapter {
    protected data class ParsedCall(
        val id: String,
        val name: String,
        val arguments: AgentNativeJsonObject
    )

    protected data class BoundedResult(
        val jsonText: String,
        val value: AgentNativeJsonObject
    )

    protected fun checkedCatalog(
        catalog: List<AgentNativeToolDescriptor>
    ): Map<String, AgentNativeToolDescriptor> {
        val result = LinkedHashMap<String, AgentNativeToolDescriptor>()
        catalog.forEachIndexed { index, descriptor ->
            checkedToolName(descriptor.id, "catalog[$index].id")
            if (result.put(descriptor.id, descriptor) != null) {
                protocolFailure("duplicate_tool", "Catalog contains duplicate tool ${descriptor.id}")
            }
        }
        return result
    }

    protected fun parseRoot(responseJson: String): JSONObject {
        if (responseJson.isBlank()) protocolFailure("malformed_response", "Provider response is blank")
        if (responseJson.length > limits.maxResponseCharacters) {
            protocolFailure(
                "oversized_response",
                "Provider response exceeds ${limits.maxResponseCharacters} characters"
            )
        }
        return try {
            JSONObject(responseJson)
        } catch (error: JSONException) {
            throw AgentModelToolProtocolException(
                "malformed_response",
                "Provider response is not a JSON object",
                error
            )
        }
    }

    protected fun parseArgumentString(value: String, path: String): AgentNativeJsonObject {
        if (value.length > limits.maxArgumentsCharacters) {
            protocolFailure(
                "oversized_tool_call",
                "$path exceeds ${limits.maxArgumentsCharacters} characters"
            )
        }
        val parsed = try {
            JSONObject(value)
        } catch (error: JSONException) {
            throw AgentModelToolProtocolException(
                "malformed_tool_call",
                "$path is not a JSON object",
                error
            )
        }
        return checkedArguments(parsed.toNativeObject(path, limits), path)
    }

    protected fun checkedArguments(
        arguments: AgentNativeJsonObject,
        path: String
    ): AgentNativeJsonObject {
        if (!AgentNativeJsonCodec.isCompatible(arguments)) {
            protocolFailure("malformed_tool_call", "$path contains a non-JSON value")
        }
        val size = AgentNativeJsonCodec.stringify(arguments).length
        if (size > limits.maxArgumentsCharacters) {
            protocolFailure(
                "oversized_tool_call",
                "$path exceeds ${limits.maxArgumentsCharacters} characters"
            )
        }
        return arguments
    }

    protected fun checkedOutboundCall(call: AgentModelToolCall, path: String) {
        checkedCallId(call.callId, "$path.id")
        checkedToolName(call.toolId, "$path.name")
        checkedArguments(call.arguments, "$path.arguments")
    }

    protected fun checkCallCount(count: Int, path: String) {
        if (count > limits.maxToolCalls) {
            protocolFailure(
                "oversized_tool_call",
                "$path contains $count tool calls; maximum is ${limits.maxToolCalls}"
            )
        }
    }

    protected fun parseCalls(
        calls: JSONArray,
        catalog: List<AgentNativeToolDescriptor>,
        parser: (JSONObject, String) -> ParsedCall
    ): List<AgentModelToolCall> {
        checkCallCount(calls.length(), "response")
        val catalogById = checkedCatalog(catalog)
        val callIds = LinkedHashSet<String>()
        return buildList {
            for (index in 0 until calls.length()) {
                val path = "response.tool_calls[$index]"
                try {
                    add(
                        checkedParsedCall(
                            parser(calls.requiredObject(index, "response.tool_calls"), path),
                            path,
                            catalogById,
                            callIds
                        )
                    )
                } catch (error: AgentModelToolProtocolException) {
                    throw error.asMalformedToolCall(path)
                }
            }
        }
    }

    protected fun checkedParsedCall(
        call: ParsedCall,
        path: String,
        catalogById: Map<String, AgentNativeToolDescriptor>,
        callIds: MutableSet<String>
    ): AgentModelToolCall {
        checkedCallId(call.id, "$path.id")
        checkedToolName(call.name, "$path.name")
        if (!callIds.add(call.id)) {
            protocolFailure("malformed_tool_call", "Duplicate tool call id ${call.id}")
        }
        val descriptor = catalogById[call.name]
            ?: protocolFailure("unknown_tool", "Provider requested unknown tool ${call.name}")
        return AgentModelToolCall(
            callId = call.id,
            toolId = descriptor.id,
            arguments = checkedArguments(call.arguments, "$path.arguments"),
            toolVersion = descriptor.version
        )
    }

    protected fun boundedResult(result: AgentModelToolResultContent, path: String): BoundedResult {
        checkedCallId(result.callId, "$path.tool_call_id")
        checkedToolName(result.toolId, "$path.tool_id")
        if (result.status.isBlank()) {
            protocolFailure("malformed_tool_result", "$path.status must not be blank")
        }
        val full = result.toJsonValue()
        if (!AgentNativeJsonCodec.isCompatible(full)) {
            protocolFailure("malformed_tool_result", "$path contains a non-JSON value")
        }
        val fullText = AgentNativeJsonCodec.stringify(full)
        if (fullText.length <= limits.maxToolResultCharacters) return BoundedResult(fullText, full)

        val summary = linkedMapOf<String, Any?>(
            "tool_call_id" to result.callId,
            "tool_id" to result.toolId,
            "status" to result.status,
            "truncated" to true,
            "original_characters" to fullText.length
        )
        var summaryText = AgentNativeJsonCodec.stringify(summary)
        if (summaryText.length > limits.maxToolResultCharacters) {
            protocolFailure(
                "oversized_tool_result",
                "$path identity exceeds ${limits.maxToolResultCharacters} characters"
            )
        }
        if (result.message.isNotBlank()) {
            var low = 0
            var high = result.message.length
            while (low < high) {
                val middle = (low + high + 1) / 2
                summary["message"] = result.message.take(middle)
                if (AgentNativeJsonCodec.stringify(summary).length <= limits.maxToolResultCharacters) {
                    low = middle
                } else {
                    high = middle - 1
                }
            }
            if (low > 0) summary["message"] = result.message.take(low) else summary.remove("message")
            summaryText = AgentNativeJsonCodec.stringify(summary)
        }
        return BoundedResult(summaryText, summary)
    }

    protected fun metadata(finishReason: String?): LinkedHashMap<String, Any?> = linkedMapOf<String, Any?>(
        "provider" to provider.wireValue
    ).apply {
        finishReason?.let { put("finish_reason", it) }
    }

    protected fun modelResponse(
        text: String,
        calls: List<AgentModelToolCall>,
        usage: AgentModelUsage,
        metadata: AgentNativeJsonObject
    ): AgentModelResponse {
        if (text.isBlank() && calls.isEmpty()) {
            protocolFailure("empty_response", "Provider response contains neither text nor tool calls")
        }
        return AgentModelResponse(text.trim(), calls, usage, metadata)
    }

    private fun checkedCallId(callId: String, path: String) {
        if (callId.isBlank()) protocolFailure("malformed_tool_call", "$path must not be blank")
        if (callId.length > limits.maxCallIdCharacters) {
            protocolFailure(
                "oversized_tool_call",
                "$path exceeds ${limits.maxCallIdCharacters} characters"
            )
        }
    }

    private fun checkedToolName(name: String, path: String) {
        if (name.isBlank()) protocolFailure("malformed_tool_call", "$path must not be blank")
        if (name.length > limits.maxToolNameCharacters) {
            protocolFailure(
                "oversized_tool_call",
                "$path exceeds ${limits.maxToolNameCharacters} characters"
            )
        }
    }
}

private fun protocolFailure(code: String, message: String): Nothing =
    throw AgentModelToolProtocolException(code, message)

private fun AgentModelToolProtocolException.asMalformedToolCall(path: String): AgentModelToolProtocolException =
    if (code == "malformed_response") {
        AgentModelToolProtocolException("malformed_tool_call", "Malformed tool call at $path: $message", this)
    } else {
        this
    }

private fun JSONObject.optionalValue(key: String): Any? =
    if (!has(key) || isNull(key)) null else get(key)

private fun JSONObject.requiredValue(key: String, path: String): Any =
    optionalValue(key) ?: protocolFailure("malformed_response", "$path.$key is required")

private fun JSONObject.requiredString(key: String, path: String): String {
    val value = requiredValue(key, path)
    if (value !is String || value.isBlank()) {
        protocolFailure("malformed_response", "$path.$key must be a non-blank string")
    }
    return value
}

private fun JSONObject.optionalString(key: String, path: String): String? {
    val value = optionalValue(key) ?: return null
    if (value !is String) protocolFailure("malformed_response", "$path.$key must be a string")
    return value
}

private fun JSONObject.requiredObject(key: String, path: String): JSONObject {
    val value = requiredValue(key, path)
    if (value !is JSONObject) protocolFailure("malformed_response", "$path.$key must be an object")
    return value
}

private fun JSONObject.optionalObject(key: String, path: String): JSONObject? {
    val value = optionalValue(key) ?: return null
    if (value !is JSONObject) protocolFailure("malformed_response", "$path.$key must be an object")
    return value
}

private fun JSONObject.requiredArray(key: String, path: String): JSONArray {
    val value = requiredValue(key, path)
    if (value !is JSONArray) protocolFailure("malformed_response", "$path.$key must be an array")
    return value
}

private fun JSONObject.optionalArray(key: String, path: String): JSONArray? {
    val value = optionalValue(key) ?: return null
    if (value !is JSONArray) protocolFailure("malformed_response", "$path.$key must be an array")
    return value
}

private fun JSONArray.requiredObject(index: Int, path: String): JSONObject {
    val value = get(index)
    if (value !is JSONObject) protocolFailure("malformed_response", "$path[$index] must be an object")
    return value
}

private fun JSONObject.tokenCount(
    primaryKey: String,
    fallbackKey: String? = null,
    path: String
): Long {
    val key = when {
        has(primaryKey) && !isNull(primaryKey) -> primaryKey
        fallbackKey != null && has(fallbackKey) && !isNull(fallbackKey) -> fallbackKey
        else -> return 0
    }
    val value = get(key)
    val count = when (value) {
        is Byte -> value.toLong()
        is Short -> value.toLong()
        is Int -> value.toLong()
        is Long -> value
        is Number -> {
            val number = value.toDouble()
            if (!number.isFinite() || number % 1.0 != 0.0 || number > Long.MAX_VALUE.toDouble()) {
                protocolFailure("malformed_response", "$path.$key must be an integer")
            }
            number.toLong()
        }
        else -> protocolFailure("malformed_response", "$path.$key must be an integer")
    }
    if (count < 0) protocolFailure("malformed_response", "$path.$key must be non-negative")
    return count
}

private fun JSONObject.toNativeObject(
    path: String,
    limits: AgentModelToolProtocolLimits,
    depth: Int = 0
): AgentNativeJsonObject {
    if (depth > limits.maxJsonDepth) {
        protocolFailure("oversized_tool_call", "$path exceeds JSON depth ${limits.maxJsonDepth}")
    }
    val result = linkedMapOf<String, Any?>()
    val keys = keys()
    while (keys.hasNext()) {
        val key = keys.next()
        result[key] = get(key).toNativeValue("$path.$key", limits, depth + 1)
    }
    return result
}

private fun JSONArray.toNativeList(
    path: String,
    limits: AgentModelToolProtocolLimits,
    depth: Int
): List<Any?> {
    if (depth > limits.maxJsonDepth) {
        protocolFailure("oversized_tool_call", "$path exceeds JSON depth ${limits.maxJsonDepth}")
    }
    return buildList {
        for (index in 0 until length()) {
            add(get(index).toNativeValue("$path[$index]", limits, depth + 1))
        }
    }
}

private fun Any?.toNativeValue(
    path: String,
    limits: AgentModelToolProtocolLimits,
    depth: Int
): Any? = when (this) {
    null, JSONObject.NULL -> null
    is JSONObject -> toNativeObject(path, limits, depth)
    is JSONArray -> toNativeList(path, limits, depth)
    is String, is Boolean, is Byte, is Short, is Int, is Long -> this
    is Number -> {
        if (!toDouble().isFinite()) protocolFailure("malformed_tool_call", "$path is not finite")
        this
    }
    else -> protocolFailure("malformed_tool_call", "$path contains an unsupported JSON value")
}

private fun AgentNativeJsonObject.toJsonObject(): JSONObject = JSONObject().apply {
    this@toJsonObject.forEach { (key, value) -> put(key, value.toJsonValue()) }
}

private fun Any?.toJsonValue(): Any = when (this) {
    null -> JSONObject.NULL
    is Map<*, *> -> JSONObject().apply {
        this@toJsonValue.forEach { (key, value) ->
            if (key !is String) protocolFailure("malformed_json", "JSON object keys must be strings")
            put(key, value.toJsonValue())
        }
    }
    is Iterable<*> -> JSONArray().apply { this@toJsonValue.forEach { put(it.toJsonValue()) } }
    is Array<*> -> JSONArray().apply { this@toJsonValue.forEach { put(it.toJsonValue()) } }
    is String, is Boolean, is Number -> this
    else -> protocolFailure("malformed_json", "Unsupported JSON value ${this::class.java.name}")
}

private fun saturatingSum(vararg values: Long): Long {
    var total = 0L
    values.forEach { value ->
        total = if (Long.MAX_VALUE - total < value) Long.MAX_VALUE else total + value
    }
    return total
}
