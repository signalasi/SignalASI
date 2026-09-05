package com.galaxyssi.chat

import android.content.Context
import java.util.Base64
import org.json.JSONObject

data class AgentRemoteExecutionVersion(val generation: Long, val sequence: Long) {
    init { require(generation in 1..9_007_199_254_740_991L && sequence >= -1L) }

    fun accepts(candidate: AgentRemoteExecutionVersion): Boolean =
        candidate.generation > generation || (candidate.generation == generation &&
            (candidate.sequence < 0L || sequence < 0L || candidate.sequence >= sequence))

    fun advance(candidate: AgentRemoteExecutionVersion): AgentRemoteExecutionVersion =
        if (candidate.generation > generation) candidate else copy(sequence = maxOf(sequence, candidate.sequence))
}

/** One interpretation for normal transport replies and recovered archive pages. */
internal object AgentRemoteOutcomeCodec {
    val FAILURES = setOf("failed", "timed_out", "cancelled")
    val TERMINAL = FAILURES + "completed"

    fun taskKey(taskId: String, generation: Long): String =
        if (generation == 1L) taskId else "$taskId#generation:$generation"

    fun version(payload: JSONObject): AgentRemoteExecutionVersion? {
        fun integer(name: String, default: Long): Long? = when (val value = payload.opt(name)) {
            null -> default
            is Int -> value.toLong()
            is Long -> value
            else -> null
        }
        val generation = integer("execution_generation", 1L) ?: return null
        val sequence = if (payload.has("status_sequence")) integer("status_sequence", -1L)
            else integer("status_seq", -1L)
        return runCatching { AgentRemoteExecutionVersion(generation, sequence ?: return null) }.getOrNull()
    }

    fun observation(payload: JSONObject): AgentConnectorResponse? {
        val version = version(payload) ?: return null
        val source = payload.optString("source_message_id").toLongOrNull()?.takeIf { it > 0L } ?: return null
        val fields = listOf("contact_id", "conversation_id", "turn_id", "task_id").map { payload.optString(it) }
        if (fields.any { it.isBlank() || it.length > 200 }) return null
        return AgentConnectorResponse(source, fields[0], "", fields[1], fields[2], fields[3],
            taskStatus = payload.optString("task_status"), executionGeneration = version.generation,
            statusSequence = version.sequence)
    }

    fun decode(payload: JSONObject, content: String, richOutputJson: String = ""): AgentConnectorResponse? {
        val identity = observation(payload) ?: return null
        val status = payload.optString("task_status").lowercase()
        if (status.isNotBlank() && status !in TERMINAL) return null
        return identity.copy(content = content, richOutputJson = richOutputJson, taskStatus = status,
            success = if (status.isBlank()) payload.optBoolean("success", true) else status == "completed")
    }

    fun content(context: Context, payload: JSONObject, supplied: String = ""): String {
        val status = payload.optString("task_status").lowercase()
        if (status in FAILURES) {
            val actual = payload.optString("error").ifBlank { supplied.ifBlank { payload.optString("content") } }
            if (actual.isNotBlank()) return actual
            return context.getString(when (status) {
                "cancelled" -> R.string.agent_task_status_cancelled
                "timed_out" -> R.string.agent_task_status_timed_out
                else -> R.string.agent_task_status_failed
            })
        }
        val encoded = payload.optString("exact_content_b64")
        val exact = if (payload.optString("exact_content_encoding") == "base64-utf8" && encoded.length in 1..256 * 1024) {
            runCatching {
                val bytes = Base64.getDecoder().decode(encoded)
                try { if (bytes.size <= 128 * 1024) String(bytes, Charsets.UTF_8) else null }
                finally { bytes.fill(0) }
            }.getOrNull()
        } else null
        return exact ?: supplied.ifBlank { payload.optString("content") }
    }
}
