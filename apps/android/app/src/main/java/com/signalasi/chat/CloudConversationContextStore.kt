package com.signalasi.chat

import android.content.Context
import org.json.JSONObject

data class CloudConversationContextState(
    val summary: String = "",
    val throughMessageId: Long = 0L
)

/**
 * Persists the compacted prefix of direct mobile cloud-model chats.
 *
 * The cursor prevents an already summarized prefix from being fed back into
 * the next compaction cycle. Model switches use independent summaries.
 */
object CloudConversationContextStore {
    private const val DATABASE = "cloud_conversation_context_v1"
    private const val KEY_STATES = "states"
    private const val MAX_SUMMARY_CHARACTERS = 32_000
    @Volatile
    private var storage: AgentEncryptedDatabase? = null

    @Synchronized
    fun get(context: Context, contactId: String, modelId: String): CloudConversationContextState {
        val key = stateKey(contactId, modelId)
        if (key.isBlank()) return CloudConversationContextState()
        val item = readRoot(context).optJSONObject(key) ?: return CloudConversationContextState()
        return CloudConversationContextState(
            summary = item.optString("summary").take(MAX_SUMMARY_CHARACTERS),
            throughMessageId = item.optLong("through_message_id").coerceAtLeast(0L)
        )
    }

    @Synchronized
    fun put(
        context: Context,
        contactId: String,
        modelId: String,
        state: CloudConversationContextState
    ) {
        val key = stateKey(contactId, modelId)
        val summary = state.summary.trim().take(MAX_SUMMARY_CHARACTERS)
        if (key.isBlank() || summary.isBlank()) return
        val root = readRoot(context)
        root.put(
            key,
            JSONObject()
                .put("summary", summary)
                .put("through_message_id", state.throughMessageId.coerceAtLeast(0L))
        )
        database(context).writeString(KEY_STATES, root.toString())
    }

    @Synchronized
    fun removeContact(context: Context, contactId: String) {
        val prefix = "${contactId.trim()}|"
        if (prefix == "|") return
        val root = readRoot(context)
        val keys = root.keys().asSequence().filter { it.startsWith(prefix) }.toList()
        if (keys.isEmpty()) return
        keys.forEach(root::remove)
        database(context).writeString(KEY_STATES, root.toString())
    }

    @Synchronized
    fun clear(context: Context) {
        database(context).clear()
        storage?.close()
        storage = null
    }

    private fun stateKey(contactId: String, modelId: String): String {
        val contact = contactId.trim()
        val model = modelId.trim()
        return if (contact.isBlank() || model.isBlank()) "" else "$contact|$model"
    }

    private fun readRoot(context: Context): JSONObject = runCatching {
        JSONObject(database(context).readString(KEY_STATES, "{}"))
    }.getOrDefault(JSONObject())

    private fun database(context: Context): AgentEncryptedDatabase =
        storage ?: synchronized(this) {
            storage ?: AgentEncryptedDatabase(context.applicationContext, DATABASE).also {
                storage = it
            }
        }
}
