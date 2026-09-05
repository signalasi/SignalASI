package com.galaxyssi.chat

import java.security.MessageDigest
import org.json.JSONArray
import org.json.JSONObject

internal object AgentConnectorResponseCodec {
    fun scopeIdentity(response: AgentConnectorResponse): String = hash(
        response.sourceMessageId.toString(), response.contactId,
        response.conversationId, response.turnId, response.taskId
    )

    fun identity(response: AgentConnectorResponse): String = scopeIdentity(response).let { scope ->
        if (response.executionGeneration == 1L) scope else hash(scope, response.executionGeneration.toString())
    }

    fun turnKey(conversationId: String, turnId: String): String = hash(conversationId, turnId)

    fun matches(candidate: AgentConnectorResponse, expected: AgentConnectorResponse): Boolean =
        candidate.sourceMessageId == expected.sourceMessageId && candidate.contactId == expected.contactId &&
            candidate.conversationId == expected.conversationId && candidate.turnId == expected.turnId &&
            candidate.taskId == expected.taskId && candidate.executionGeneration == expected.executionGeneration

    fun encode(response: AgentConnectorResponse): JSONObject = JSONObject()
        .put("source_message_id", response.sourceMessageId)
        .put("contact_id", response.contactId)
        .put("resolved_contact_id", response.resolvedContactId)
        .put("content", response.content)
        .put("conversation_id", response.conversationId)
        .put("turn_id", response.turnId)
        .put("task_id", response.taskId)
        .put("success", response.success)
        .put("task_status", response.taskStatus)
        .put("execution_generation", response.executionGeneration)
        .put("status_sequence", response.statusSequence)
        .put("input_tokens", response.inputTokens)
        .put("output_tokens", response.outputTokens)
        .put("cost_micros", response.costMicros)
        .put("rich_output", response.richOutputJson)
        .put("received_at", response.receivedAtMillis)
        .put("provider_attempts", response.providerAttempts?.let(AgentProviderAttemptCodec::encode))

    fun decode(value: JSONObject): AgentConnectorResponse {
        val source = value.getLong("source_message_id")
        require(source > 0) { "Invalid connector response source identity" }
        val rich = value.optString("rich_output")
        val content = value.optString("content").ifBlank { AgentRichContentCodec.fallbackText(rich) }
        require(content.isNotBlank() || rich.isNotBlank()) { "Empty connector response" }
        val version = requireNotNull(AgentRemoteOutcomeCodec.version(value)) { "Invalid execution version" }
        val status = value.optString("task_status")
        require(status.isBlank() || status in AgentRemoteOutcomeCodec.TERMINAL) { "Invalid terminal status" }
        return AgentConnectorResponse(
            sourceMessageId = source, contactId = value.optString("contact_id"), content = content,
            conversationId = value.optString("conversation_id"), turnId = value.optString("turn_id"),
            taskId = value.optString("task_id"), success = if (status.isBlank()) value.optBoolean("success", true) else status == "completed",
            inputTokens = value.optLong("input_tokens"), outputTokens = value.optLong("output_tokens"),
            costMicros = value.optLong("cost_micros"), richOutputJson = rich,
            receivedAtMillis = value.optLong("received_at", System.currentTimeMillis()),
            resolvedContactId = value.optString("resolved_contact_id"),
            providerAttempts = value.optJSONObject("provider_attempts")?.let(AgentProviderAttemptCodec::decode),
            taskStatus = status, executionGeneration = version.generation, statusSequence = version.sequence
        )
    }

    private fun hash(vararg parts: String): String {
        val encoded = JSONArray(parts.toList()).toString().toByteArray(Charsets.UTF_8)
        return try {
            MessageDigest.getInstance("SHA-256").digest(encoded).joinToString("") { byte ->
                HEX[(byte.toInt() and 255) ushr 4].toString() + HEX[byte.toInt() and 15]
            }
        } finally { encoded.fill(0) }
    }

    private const val HEX = "0123456789abcdef"
}
