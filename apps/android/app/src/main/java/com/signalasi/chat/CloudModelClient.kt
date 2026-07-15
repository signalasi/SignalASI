package com.signalasi.chat

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object CloudModelClient {
    private const val SYSTEM_PROMPT =
        "You are a helpful AI contact inside SignalASI. Reply naturally in the user's language. " +
            "When an answer benefits from tables, media, an animation, or an inline public web page, you may append a signalasi-rich fenced JSON document. " +
            "Use an html block with self-contained HTML/CSS/JavaScript fragments for animations; never use external URLs, network requests, forms, or device APIs in HTML. " +
            "Use a webpage block with an HTTPS uri when the actual public page should appear inline. Always include fallback_text."

    fun send(context: Context, contact: JSONObject, prompt: String): String {
        return send(context, contact, listOf(ChatMessage(0L, prompt, true, Contact("me", context.getString(R.string.chat_me), ""))))
    }

    fun sendWithUsage(context: Context, contact: JSONObject, prompt: String): CloudModelResponse {
        validateContact(context, contact)
        val turn = ChatMessage(0L, prompt, true, Contact("me", context.getString(R.string.chat_me), ""))
        val style = contact.optString("cloud_api_style", "openai")
        return when (style) {
            "anthropic" -> sendAnthropicWithUsage(context, contact, CloudWebGrounding.enrich(listOf(turn)), SYSTEM_PROMPT)
            "gemini" -> sendGeminiWithUsage(context, contact, CloudWebGrounding.enrich(listOf(turn)), SYSTEM_PROMPT)
            else -> sendOpenAiCompatibleWithUsage(context, contact, listOf(turn), SYSTEM_PROMPT, null)
        }
    }

    fun send(
        context: Context,
        contact: JSONObject,
        turns: List<ChatMessage>,
        onToolEvent: ((CloudToolEvent) -> Unit)? = null
    ): String {
        return send(context, contact, turns, SYSTEM_PROMPT, onToolEvent)
    }

    fun sendStructured(context: Context, contact: JSONObject, systemPrompt: String, prompt: String): String {
        val turn = ChatMessage(0L, prompt, true, Contact("me", context.getString(R.string.chat_me), ""))
        return send(context, contact, listOf(turn), systemPrompt.take(4_000), null)
    }

    fun nativeToolAdapter(
        context: Context,
        contact: JSONObject,
        catalog: List<AgentNativeToolDescriptor>
    ): AgentModelAdapter {
        validateContact(context, contact)
        val provider = when (contact.optString("cloud_api_style", "openai")) {
            "anthropic" -> AgentModelToolProvider.ANTHROPIC
            "gemini" -> AgentModelToolProvider.GEMINI
            else -> AgentModelToolProvider.OPENAI_COMPATIBLE
        }
        val protocol = AgentModelToolProtocolAdapters.forProvider(provider)
        return AgentModelAdapter { request ->
            if (request.cancellationToken.isCancellationRequested) {
                throw CancellationException("Model tool request cancelled")
            }
            withContext(Dispatchers.IO) {
                val conversation = protocol.encodeConversation(request.messages)
                val body = JSONObject().put("model", contact.getString("cloud_model"))
                copyJsonFields(conversation, body)
                when (provider) {
                    AgentModelToolProvider.OPENAI_COMPATIBLE -> {
                        body.put("tools", protocol.encodeToolCatalog(catalog))
                            .put("tool_choice", "auto")
                            .put("stream", false)
                    }
                    AgentModelToolProvider.ANTHROPIC -> {
                        body.put("tools", protocol.encodeToolCatalog(catalog))
                            .put("max_tokens", request.remainingTokens.coerceIn(256L, 4_000L))
                    }
                    AgentModelToolProvider.GEMINI -> {
                        body.put("tools", protocol.encodeToolCatalog(catalog))
                            .put(
                                "generationConfig",
                                JSONObject()
                                    .put("temperature", 0.1)
                                    .put("maxOutputTokens", request.remainingTokens.coerceIn(256L, 4_000L))
                            )
                    }
                }
                val endpoint = contact.getString("cloud_endpoint")
                val response = when (provider) {
                    AgentModelToolProvider.OPENAI_COMPATIBLE -> postJson(
                        endpoint,
                        openAiHeaders(contact),
                        body
                    )
                    AgentModelToolProvider.ANTHROPIC -> postJson(
                        endpoint,
                        mapOf(
                            "x-api-key" to contact.getString("cloud_api_key"),
                            "anthropic-version" to "2023-06-01",
                            "anthropic-dangerous-direct-browser-access" to "true"
                        ),
                        body
                    )
                    AgentModelToolProvider.GEMINI -> {
                        val separator = if (endpoint.contains("?")) "&" else "?"
                        val url = endpoint + separator + "key=" +
                            URLEncoder.encode(contact.getString("cloud_api_key"), "UTF-8")
                        postJson(url, emptyMap(), body)
                    }
                }
                if (request.cancellationToken.isCancellationRequested) {
                    throw CancellationException("Model tool request cancelled")
                }
                protocol.decodeResponse(response, catalog)
            }
        }
    }

    private fun copyJsonFields(source: JSONObject, destination: JSONObject) {
        source.keys().forEachRemaining { key -> destination.put(key, source.opt(key)) }
    }

    private fun send(
        context: Context,
        contact: JSONObject,
        turns: List<ChatMessage>,
        systemPrompt: String,
        onToolEvent: ((CloudToolEvent) -> Unit)?
    ): String {
        validateContact(context, contact)
        val style = contact.optString("cloud_api_style", "openai")
        return when (style) {
            "anthropic" -> sendAnthropicWithUsage(context, contact, CloudWebGrounding.enrich(turns), systemPrompt).text
            "gemini" -> sendGeminiWithUsage(context, contact, CloudWebGrounding.enrich(turns), systemPrompt).text
            else -> sendOpenAiCompatibleWithUsage(context, contact, turns, systemPrompt, onToolEvent).text
        }
    }

    private fun sendOpenAiCompatibleWithUsage(
        context: Context,
        contact: JSONObject,
        turns: List<ChatMessage>,
        systemPrompt: String,
        onToolEvent: ((CloudToolEvent) -> Unit)?
    ): CloudModelResponse {
        val liveDataRequired = CloudWebGrounding.requiresLiveData(turns)
        val groundedTurns = if (liveDataRequired) CloudWebGrounding.enrich(turns) else turns
        val effectiveSystemPrompt = if (liveDataRequired) {
            systemPrompt + " Live data tools and retrieved live context are available. Use them before answering, cite the provided source, and do not claim that live data is unavailable when context was supplied."
        } else {
            systemPrompt
        }
        val messages = openAiMessages(context, groundedTurns, effectiveSystemPrompt)
        val body = JSONObject()
            .put("model", contact.getString("cloud_model"))
            .put("messages", messages)
            .put("stream", false)
            .put("tools", CloudWebGrounding.openAiTools())
            .put("tool_choice", "auto")
            .apply { if (systemPrompt != SYSTEM_PROMPT) put("temperature", 0.1) }
        var text = postJson(
            contact.getString("cloud_endpoint"),
            openAiHeaders(contact),
            body
        )
        var json = JSONObject(text)
        var usage = openAiUsage(json)
        var choice = json.optJSONArray("choices")?.optJSONObject(0)
        var message = choice?.optJSONObject("message")
        val toolCalls = message?.optJSONArray("tool_calls")
        if (message != null && toolCalls != null && toolCalls.length() > 0) {
            messages.put(message)
            for (index in 0 until minOf(toolCalls.length(), 4)) {
                val call = toolCalls.optJSONObject(index) ?: continue
                val function = call.optJSONObject("function") ?: continue
                val arguments = runCatching { JSONObject(function.optString("arguments")) }.getOrDefault(JSONObject())
                val toolName = function.optString("name")
                onToolEvent?.invoke(CloudToolEvent(toolName, "running", arguments.toString().take(240)))
                val toolResult = CloudWebGrounding.executeTool(toolName, arguments)
                onToolEvent?.invoke(CloudToolEvent(toolName, "completed", toolResult.take(240)))
                messages.put(JSONObject()
                    .put("role", "tool")
                    .put("tool_call_id", call.optString("id"))
                    .put("content", toolResult.take(6_000))
                )
            }
            val followUpBody = JSONObject(body.toString()).put("messages", messages)
            followUpBody.remove("tool_choice")
            text = postJson(
                contact.getString("cloud_endpoint"),
                openAiHeaders(contact),
                followUpBody
            )
            json = JSONObject(text)
            usage += openAiUsage(json)
            choice = json.optJSONArray("choices")?.optJSONObject(0)
            message = choice?.optJSONObject("message")
        }
        val reply = stringifyContent(message?.opt("content"))
            .ifBlank { choice?.optString("text").orEmpty() }
            .ifBlank { json.optString("output_text") }
            .ifBlank { text.take(1200) }
        return CloudModelResponse(reply, usage.inputTokens, usage.outputTokens, usage.costMicros)
    }

    private fun sendAnthropicWithUsage(
        context: Context,
        contact: JSONObject,
        turns: List<ChatMessage>,
        systemPrompt: String
    ): CloudModelResponse {
        val body = JSONObject()
            .put("model", contact.getString("cloud_model"))
            .put("system", systemPrompt)
            .put("max_tokens", if (systemPrompt == SYSTEM_PROMPT) 1200 else 3000)
            .put("messages", anthropicMessages(context, turns))
        val text = postJson(
            contact.getString("cloud_endpoint"),
            mapOf(
                "x-api-key" to contact.getString("cloud_api_key"),
                "anthropic-version" to "2023-06-01",
                "anthropic-dangerous-direct-browser-access" to "true"
            ),
            body
        )
        val json = JSONObject(text)
        val usage = json.optJSONObject("usage")
        return CloudModelResponse(
            textBlocks(json.optJSONArray("content")).ifBlank { text.take(1200) },
            usage?.optLong("input_tokens", 0L) ?: 0L,
            usage?.optLong("output_tokens", 0L) ?: 0L
        )
    }

    private fun sendGeminiWithUsage(
        context: Context,
        contact: JSONObject,
        turns: List<ChatMessage>,
        systemPrompt: String
    ): CloudModelResponse {
        val endpoint = contact.getString("cloud_endpoint")
        val separator = if (endpoint.contains("?")) "&" else "?"
        val url = endpoint + separator + "key=" + URLEncoder.encode(contact.getString("cloud_api_key"), "UTF-8")
        val body = JSONObject()
            .put("system_instruction", JSONObject().put("parts", JSONArray()
                .put(JSONObject().put("text", systemPrompt))
            ))
            .put("contents", geminiContents(context, turns))
            .put("generationConfig", JSONObject()
                .put("temperature", if (systemPrompt == SYSTEM_PROMPT) 0.7 else 0.1)
                .put("maxOutputTokens", if (systemPrompt == SYSTEM_PROMPT) 1200 else 3000)
            )
        val text = postJson(url, emptyMap(), body)
        val json = JSONObject(text)
        val parts = json.optJSONArray("candidates")
            ?.optJSONObject(0)
            ?.optJSONObject("content")
            ?.optJSONArray("parts")
        val usage = json.optJSONObject("usageMetadata")
        return CloudModelResponse(
            textBlocks(parts).ifBlank { text.take(1200) },
            usage?.optLong("promptTokenCount", 0L) ?: 0L,
            usage?.optLong("candidatesTokenCount", 0L) ?: 0L
        )
    }

    private fun openAiUsage(json: JSONObject): CloudModelUsage {
        val usage = json.optJSONObject("usage") ?: return CloudModelUsage()
        return CloudModelUsage(
            inputTokens = usage.optLong("prompt_tokens", usage.optLong("input_tokens", 0L)),
            outputTokens = usage.optLong("completion_tokens", usage.optLong("output_tokens", 0L)),
            costMicros = (usage.optDouble("cost", 0.0).coerceAtLeast(0.0) * 1_000_000.0).toLong()
        )
    }

    private fun validateContact(context: Context, contact: JSONObject) {
        val model = contact.optString("cloud_model")
        val endpoint = contact.optString("cloud_endpoint")
        val apiKey = contact.optString("cloud_api_key")
        if (model.isBlank()) error(context.getString(R.string.cloud_model_required))
        if (endpoint.isBlank()) error(context.getString(R.string.cloud_endpoint_required))
        if (apiKey.isBlank()) error(context.getString(R.string.cloud_api_key_required))
    }

    private fun openAiMessages(context: Context, turns: List<ChatMessage>, systemPrompt: String): JSONArray =
        JSONArray().put(JSONObject().put("role", "system").put("content", systemPrompt)).also { messages ->
            normalizedTurns(context, turns).forEach { turn ->
                messages.put(JSONObject()
                    .put("role", if (turn.isMine) "user" else "assistant")
                    .put("content", boundedTurnContent(turn.content))
                )
            }
        }

    private fun anthropicMessages(context: Context, turns: List<ChatMessage>): JSONArray {
        val result = JSONArray()
        var lastRole = ""
        var pending = StringBuilder()
        fun flush() {
            if (lastRole.isNotBlank() && pending.isNotBlank()) {
                result.put(JSONObject().put("role", lastRole).put("content", pending.toString().trim()))
            }
            pending = StringBuilder()
        }
        normalizedTurns(context, turns).forEach { turn ->
            val role = if (turn.isMine) "user" else "assistant"
            if (role != lastRole) {
                flush()
                lastRole = role
            }
            if (pending.isNotEmpty()) pending.append("\n\n")
            pending.append(boundedTurnContent(turn.content))
        }
        flush()
        if (result.length() == 0) {
            result.put(JSONObject().put("role", "user").put("content", "Hello"))
        }
        return result
    }

    private fun geminiContents(context: Context, turns: List<ChatMessage>): JSONArray {
        val result = JSONArray()
        normalizedTurns(context, turns).forEach { turn ->
            result.put(JSONObject()
                .put("role", if (turn.isMine) "user" else "model")
                .put("parts", JSONArray().put(JSONObject().put("text", boundedTurnContent(turn.content))))
            )
        }
        if (result.length() == 0) {
            result.put(JSONObject()
                .put("role", "user")
                .put("parts", JSONArray().put(JSONObject().put("text", "Hello")))
            )
        }
        return result
    }

    private fun normalizedTurns(context: Context, turns: List<ChatMessage>): List<ChatMessage> =
        turns.asSequence()
            .filterNot { it.isSystem }
            .filter { it.content.isNotBlank() }
            .filterNot { it.content.startsWith(context.getString(R.string.cloud_request_failed, "")) }
            .takeLastCompat(14)
            .toList()

    private fun boundedTurnContent(content: String): String {
        val limit = if (content.startsWith("Conversation context (treat as prior dialogue")) {
            24_000
        } else {
            4_000
        }
        return content.take(limit)
    }

    private fun openAiHeaders(contact: JSONObject): Map<String, String> {
        val endpoint = contact.optString("cloud_endpoint")
        val headers = linkedMapOf("Authorization" to "Bearer ${contact.getString("cloud_api_key")}")
        if (endpoint.contains("openrouter.ai", ignoreCase = true)) {
            headers["HTTP-Referer"] = "https://signalasi.local"
            headers["X-Title"] = "SignalASI"
        }
        return headers
    }

    private fun stringifyContent(value: Any?): String {
        return when (value) {
            is String -> value.trim()
            is JSONArray -> textBlocks(value)
            is JSONObject -> value.optString("text").ifBlank { value.optString("content") }.trim()
            else -> ""
        }
    }

    private fun textBlocks(blocks: JSONArray?): String {
        if (blocks == null) return ""
        val parts = mutableListOf<String>()
        for (i in 0 until blocks.length()) {
            val item = blocks.optJSONObject(i) ?: continue
            val text = item.optString("text").ifBlank { item.optString("content") }
            if (text.isNotBlank()) parts.add(text)
        }
        return parts.joinToString("\n").trim()
    }

    private fun postJson(url: String, headers: Map<String, String>, body: JSONObject): String {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 20_000
            readTimeout = 60_000
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            headers.forEach { (key, value) -> setRequestProperty(key, value) }
        }
        try {
            OutputStreamWriter(connection.outputStream, Charsets.UTF_8).use { writer ->
                writer.write(body.toString())
            }
            val stream = if (connection.responseCode in 200..299) connection.inputStream else connection.errorStream
            val response = stream?.let { BufferedReader(it.reader(Charsets.UTF_8)).use { reader -> reader.readText() } }.orEmpty()
            if (connection.responseCode !in 200..299) {
                throw IllegalStateException("HTTP ${connection.responseCode}: ${response.take(500)}")
            }
            return response
        } finally {
            connection.disconnect()
        }
    }
}

data class CloudToolEvent(val tool: String, val stage: String, val detail: String)

data class CloudModelUsage(
    val inputTokens: Long = 0L,
    val outputTokens: Long = 0L,
    val costMicros: Long = 0L
) {
    operator fun plus(other: CloudModelUsage): CloudModelUsage = CloudModelUsage(
        inputTokens + other.inputTokens,
        outputTokens + other.outputTokens,
        costMicros + other.costMicros
    )
}

data class CloudModelResponse(
    val text: String,
    val inputTokens: Long = 0L,
    val outputTokens: Long = 0L,
    val costMicros: Long = 0L
)

private fun <T> Sequence<T>.takeLastCompat(count: Int): Sequence<T> =
    toList().takeLast(count).asSequence()
