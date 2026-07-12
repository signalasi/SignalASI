package com.signalasi.chat

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.CopyOnWriteArraySet

data class AgentConnectorResponse(
    val sourceMessageId: Long,
    val contactId: String,
    val content: String,
    val success: Boolean = true,
    val inputTokens: Long = 0L,
    val outputTokens: Long = 0L,
    val costMicros: Long = 0L,
    val receivedAtMillis: Long = System.currentTimeMillis()
)

fun interface AgentConnectorResponseListener {
    fun onConnectorResponse(response: AgentConnectorResponse)
}

object AgentConnectorResponseBus {
    private val listeners = CopyOnWriteArraySet<AgentConnectorResponseListener>()

    fun addListener(listener: AgentConnectorResponseListener) {
        listeners += listener
    }

    fun removeListener(listener: AgentConnectorResponseListener) {
        listeners -= listener
    }

    fun publish(context: Context, response: AgentConnectorResponse) {
        if (response.sourceMessageId <= 0L || response.content.isBlank()) return
        AgentConnectorResponseStore.append(context, response)
        listeners.forEach { listener -> listener.onConnectorResponse(response) }
    }
}

object AgentConnectorResponseStore {
    private const val PREFS = "signalasi_agent_connector_responses"
    private const val KEY_RESPONSES = "responses"
    private const val MAX_RESPONSES = 30

    @Synchronized
    fun append(context: Context, response: AgentConnectorResponse) {
        val next = pending(context)
            .filterNot { it.sourceMessageId == response.sourceMessageId && it.contactId == response.contactId }
            .plus(response)
            .sortedBy { it.receivedAtMillis }
            .takeLast(MAX_RESPONSES)
        save(context, next)
    }

    @Synchronized
    fun pending(context: Context): List<AgentConnectorResponse> {
        val raw = AgentEncryptedPreferences(context, PREFS).readString(KEY_RESPONSES, "[]")
        return runCatching {
            val array = JSONArray(raw)
            val cutoff = System.currentTimeMillis() - MAX_RESPONSE_AGE_MILLIS
            buildList {
                for (index in 0 until array.length()) {
                    val item = array.optJSONObject(index) ?: continue
                    val sourceMessageId = item.optLong("source_message_id")
                    val content = item.optString("content")
                    val receivedAt = item.optLong("received_at", System.currentTimeMillis())
                    if (sourceMessageId <= 0L || content.isBlank() || receivedAt < cutoff) continue
                    add(
                        AgentConnectorResponse(
                            sourceMessageId = sourceMessageId,
                            contactId = item.optString("contact_id"),
                            content = content,
                            success = item.optBoolean("success", true),
                            inputTokens = item.optLong("input_tokens", 0L),
                            outputTokens = item.optLong("output_tokens", 0L),
                            costMicros = item.optLong("cost_micros", 0L),
                            receivedAtMillis = receivedAt
                        )
                    )
                }
            }
        }.getOrDefault(emptyList())
    }

    @Synchronized
    fun remove(context: Context, response: AgentConnectorResponse) {
        save(
            context,
            pending(context).filterNot {
                it.sourceMessageId == response.sourceMessageId && it.contactId == response.contactId
            }
        )
    }

    @Synchronized
    fun clear(context: Context) {
        AgentEncryptedPreferences(context, PREFS).clear()
    }

    private fun save(context: Context, responses: List<AgentConnectorResponse>) {
        val array = JSONArray()
        responses.forEach { response ->
            array.put(
                JSONObject()
                    .put("source_message_id", response.sourceMessageId)
                    .put("contact_id", response.contactId)
                    .put("content", response.content.take(24_000))
                    .put("success", response.success)
                    .put("input_tokens", response.inputTokens)
                    .put("output_tokens", response.outputTokens)
                    .put("cost_micros", response.costMicros)
                    .put("received_at", response.receivedAtMillis)
            )
        }
        AgentEncryptedPreferences(context, PREFS).writeString(KEY_RESPONSES, array.toString())
    }

    private const val MAX_RESPONSE_AGE_MILLIS = 24L * 60L * 60L * 1000L
}
