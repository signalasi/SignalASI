package com.signalasi.chat

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArraySet

data class AgentConnectorResponse(
    val sourceMessageId: Long,
    val contactId: String,
    val content: String,
    val conversationId: String = "",
    val turnId: String = "",
    val taskId: String = "",
    val success: Boolean = true,
    val inputTokens: Long = 0L,
    val outputTokens: Long = 0L,
    val costMicros: Long = 0L,
    val richOutputJson: String = "",
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

    fun publish(context: Context, response: AgentConnectorResponse): Boolean {
        if (response.sourceMessageId <= 0L) return false
        val richOutput = AgentRichContentCodec.normalize(response.richOutputJson)
        val normalized = response.copy(
            content = response.content.ifBlank { AgentRichContentCodec.fallbackText(richOutput) },
            richOutputJson = richOutput
        )
        if (normalized.content.isBlank() && normalized.richOutputJson.isBlank()) return false
        if (AgentManagedConnectorResponseRegistry.consume(normalized)) return true
        AgentConnectorResponseStore.append(context, normalized)
        listeners.forEach { listener -> listener.onConnectorResponse(normalized) }
        return false
    }
}

/**
 * One-shot response interception for host-managed Agent runs. Internal team
 * replies must return to their supervisor instead of appearing as independent
 * assistant messages in the user transcript.
 */
internal object AgentManagedConnectorResponseRegistry {
    private data class Interceptor(
        val ownerId: String,
        val consume: (AgentConnectorResponse) -> Boolean
    )

    private val interceptors = ConcurrentHashMap<String, Interceptor>()

    fun register(
        sourceMessageId: Long,
        contactId: String,
        ownerId: String,
        consume: (AgentConnectorResponse) -> Boolean
    ) {
        require(sourceMessageId > 0L) { "Managed response source id must be positive" }
        require(ownerId.isNotBlank()) { "Managed response owner id must not be blank" }
        interceptors[key(sourceMessageId, contactId)] = Interceptor(ownerId, consume)
    }

    fun consume(response: AgentConnectorResponse): Boolean {
        val exactKey = key(response.sourceMessageId, response.contactId)
        val wildcardKey = key(response.sourceMessageId, "")
        val interceptor = interceptors.remove(exactKey)
            ?: interceptors.remove(wildcardKey)
            ?: return false
        return runCatching { interceptor.consume(response) }.getOrDefault(false)
    }

    fun unregisterOwner(ownerId: String) {
        if (ownerId.isBlank()) return
        interceptors.entries.removeIf { it.value.ownerId == ownerId }
    }

    fun clear() = interceptors.clear()

    private fun key(sourceMessageId: Long, contactId: String): String =
        "$sourceMessageId:${contactId.trim()}"
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
                    val richOutput = AgentRichContentCodec.normalize(item.optString("rich_output"))
                    if (sourceMessageId <= 0L || (content.isBlank() && richOutput.isBlank()) || receivedAt < cutoff) continue
                    add(
                        AgentConnectorResponse(
                            sourceMessageId = sourceMessageId,
                            contactId = item.optString("contact_id"),
                            content = content.ifBlank { AgentRichContentCodec.fallbackText(richOutput) },
                            conversationId = item.optString("conversation_id"),
                            turnId = item.optString("turn_id"),
                            taskId = item.optString("task_id"),
                            success = item.optBoolean("success", true),
                            inputTokens = item.optLong("input_tokens", 0L),
                            outputTokens = item.optLong("output_tokens", 0L),
                            costMicros = item.optLong("cost_micros", 0L),
                            richOutputJson = richOutput,
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
                    .put("conversation_id", response.conversationId)
                    .put("turn_id", response.turnId)
                    .put("task_id", response.taskId)
                    .put("success", response.success)
                    .put("input_tokens", response.inputTokens)
                    .put("output_tokens", response.outputTokens)
                    .put("cost_micros", response.costMicros)
                    .put("rich_output", AgentRichContentCodec.normalize(response.richOutputJson))
                    .put("received_at", response.receivedAtMillis)
            )
        }
        AgentEncryptedPreferences(context, PREFS).writeString(KEY_RESPONSES, array.toString())
    }

    private const val MAX_RESPONSE_AGE_MILLIS = 24L * 60L * 60L * 1000L
}
