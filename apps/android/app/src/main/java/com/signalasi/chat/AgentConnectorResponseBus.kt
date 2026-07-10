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
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val raw = prefs.getString(KEY_RESPONSES, "[]") ?: "[]"
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
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
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
                    .put("received_at", response.receivedAtMillis)
            )
        }
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_RESPONSES, array.toString())
            .apply()
    }

    private const val MAX_RESPONSE_AGE_MILLIS = 24L * 60L * 60L * 1000L
}
