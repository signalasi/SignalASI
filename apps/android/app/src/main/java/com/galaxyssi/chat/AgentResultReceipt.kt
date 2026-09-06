package com.galaxyssi.chat

import org.json.JSONArray
import org.json.JSONObject

/** Immutable routing metadata only; never retains the reply body. */
internal data class AgentResultReceipt private constructor(
    val desktop: String,
    val fields: List<String>,
    val generation: Long,
    val digest: String
) {
    val id: String get() {
        val bytes = JSONArray(listOf(desktop, generation.toString(), digest) + fields).toString().toByteArray(Charsets.UTF_8)
        return try { AgentResultRecoveryClient.sha256(bytes) } finally { bytes.fill(0) }
    }

    fun payload(type: String = "agent_task_result_received"): JSONObject = JSONObject().apply {
        AgentResultRecoveryClient.FIELDS.zip(fields).forEach { (key, value) -> put(key, value) }
        put("type", type); put("desktop_id", desktop); put("execution_generation", generation)
        put("sha256", digest); put("receipt_id", id)
    }

    fun matches(response: AgentConnectorResponse): Boolean =
        fields[1] == response.conversationId && fields[2] == response.taskId && fields[3] == response.turnId &&
            fields[4] == response.contactId && fields[5] == response.sourceMessageId.toString() &&
            generation == response.executionGeneration

    companion object {
        private val hash = Regex("[a-f0-9]{64}")

        fun from(payload: JSONObject, desktop: String): AgentResultReceipt? {
            val fields = AgentResultRecoveryClient.identity(payload)
            val generation = AgentRemoteOutcomeCodec.version(payload)?.generation ?: return null
            val digest = payload.optString("sha256").ifBlank {
                payload.optJSONObject("result_recovery")?.optString("sha256").orEmpty()
            }
            if (desktop.isBlank() || desktop.length > 200 || fields.any { it.isBlank() || it.length > 200 } ||
                (fields[5].toLongOrNull() ?: 0) <= 0 || !hash.matches(digest) ||
                GalaxySSITransportPrivacyPolicy.isLocalOnly(payload)) return null
            return AgentResultReceipt(desktop, fields, generation, digest)
        }

        fun confirmed(payload: JSONObject, desktop: String): AgentResultReceipt? {
            if (payload.optString("type") != "agent_task_result_receipt_confirmed") return null
            return from(payload, desktop)?.takeIf { it.id == payload.optString("receipt_id") }
        }
    }
}
