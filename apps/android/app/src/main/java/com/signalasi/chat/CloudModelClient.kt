package com.signalasi.chat

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

object CloudModelClient {
    private const val SYSTEM_PROMPT =
        "You are a helpful AI contact inside SignalASI. Reply naturally in the user's language."

    fun send(context: Context, contact: JSONObject, prompt: String): String {
        return send(context, contact, listOf(ChatMessage(0L, prompt, true, Contact("me", context.getString(R.string.chat_me), ""))))
    }

    fun send(context: Context, contact: JSONObject, turns: List<ChatMessage>): String {
        return send(context, contact, turns, SYSTEM_PROMPT)
    }

    fun sendStructured(context: Context, contact: JSONObject, systemPrompt: String, prompt: String): String {
        val turn = ChatMessage(0L, prompt, true, Contact("me", context.getString(R.string.chat_me), ""))
        return send(context, contact, listOf(turn), systemPrompt.take(4_000))
    }

    private fun send(
        context: Context,
        contact: JSONObject,
        turns: List<ChatMessage>,
        systemPrompt: String
    ): String {
        validateContact(context, contact)
        val groundedTurns = CloudWebGrounding.enrich(turns)
        val style = contact.optString("cloud_api_style", "openai")
        return when (style) {
            "anthropic" -> sendAnthropic(context, contact, groundedTurns, systemPrompt)
            "gemini" -> sendGemini(context, contact, groundedTurns, systemPrompt)
            else -> sendOpenAiCompatible(context, contact, groundedTurns, systemPrompt)
        }
    }

    private fun sendOpenAiCompatible(
        context: Context,
        contact: JSONObject,
        turns: List<ChatMessage>,
        systemPrompt: String
    ): String {
        val body = JSONObject()
            .put("model", contact.getString("cloud_model"))
            .put("messages", openAiMessages(context, turns, systemPrompt))
            .put("stream", false)
            .apply { if (systemPrompt != SYSTEM_PROMPT) put("temperature", 0.1) }
        val text = postJson(
            contact.getString("cloud_endpoint"),
            openAiHeaders(contact),
            body
        )
        val json = JSONObject(text)
        val choice = json.optJSONArray("choices")
            ?.optJSONObject(0)
        val message = choice?.optJSONObject("message")
        return stringifyContent(message?.opt("content"))
            .ifBlank { choice?.optString("text").orEmpty() }
            .ifBlank { json.optString("output_text") }
            .ifBlank { text.take(1200) }
    }

    private fun sendAnthropic(
        context: Context,
        contact: JSONObject,
        turns: List<ChatMessage>,
        systemPrompt: String
    ): String {
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
        return textBlocks(json.optJSONArray("content")).ifBlank { text.take(1200) }
    }

    private fun sendGemini(
        context: Context,
        contact: JSONObject,
        turns: List<ChatMessage>,
        systemPrompt: String
    ): String {
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
        return textBlocks(parts).ifBlank { text.take(1200) }
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
                    .put("content", turn.content.take(4000))
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
            pending.append(turn.content.take(4000))
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
                .put("parts", JSONArray().put(JSONObject().put("text", turn.content.take(4000))))
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

private fun <T> Sequence<T>.takeLastCompat(count: Int): Sequence<T> =
    toList().takeLast(count).asSequence()
