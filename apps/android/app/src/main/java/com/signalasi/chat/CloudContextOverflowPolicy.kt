package com.signalasi.chat

internal class CloudHttpException(
    val statusCode: Int,
    val responseBody: String
) : IllegalStateException("HTTP $statusCode: ${responseBody.take(500)}")

internal object CloudContextOverflowPolicy {
    private const val MINIMUM_RETRY_WINDOW_TOKENS = 4_096
    private const val MAXIMUM_ATTEMPTS = 4

    private val overflowMarkers = listOf(
        "context_length_exceeded",
        "maximum context length",
        "context window",
        "too many tokens",
        "token limit",
        "prompt is too long",
        "prompt too long",
        "input is too long",
        "input too long",
        "input token count",
        "exceeds the maximum number of tokens",
        "exceeds maximum token",
        "reduce the length of the messages",
        "reduce your prompt"
    )

    fun isContextOverflow(error: CloudHttpException): Boolean {
        if (error.statusCode !in setOf(400, 413, 422)) return false
        val detail = error.responseBody.lowercase()
        if (overflowMarkers.any(detail::contains)) return true
        return error.statusCode == 413 &&
            ("request too large" in detail || "payload too large" in detail)
    }

    fun retryWindows(configuredWindowTokens: Int): List<Int> {
        val configured = configuredWindowTokens.coerceAtLeast(MINIMUM_RETRY_WINDOW_TOKENS)
        return buildList {
            var candidate = configured
            repeat(MAXIMUM_ATTEMPTS) {
                if (candidate !in this) add(candidate)
                candidate = (candidate / 2).coerceAtLeast(MINIMUM_RETRY_WINDOW_TOKENS)
            }
        }
    }
}
