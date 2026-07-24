package com.signalasi.chat

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

data class AgentObservedContext(
    val id: String = UUID.randomUUID().toString(),
    val targetId: String,
    val text: String,
    val conversationId: String = "",
    val taskId: String = "",
    val createdAtMillis: Long = System.currentTimeMillis(),
    val expiresAtMillis: Long = createdAtMillis + DEFAULT_TTL_MILLIS
) {
    fun isExpired(nowMillis: Long = System.currentTimeMillis()): Boolean =
        expiresAtMillis > 0L && nowMillis >= expiresAtMillis

    companion object {
        const val DEFAULT_TTL_MILLIS = 24L * 60L * 60L * 1_000L
    }
}

class AgentObservationContextStore(context: Context) {
    private val database = AgentEncryptedDatabase(context.applicationContext, DATABASE)

    @Synchronized
    fun observe(
        targetId: String,
        text: String,
        conversationId: String = "",
        taskId: String = ""
    ): AgentObservedContext? {
        val cleanTarget = targetId.trim().take(MAX_TARGET_CHARS)
        val cleanText = text.trim().take(MAX_ENTRY_CHARS)
        if (cleanTarget.isBlank() || cleanText.isBlank()) return null
        val now = System.currentTimeMillis()
        val entry = AgentObservedContext(
            targetId = cleanTarget,
            text = cleanText,
            conversationId = conversationId.trim().take(MAX_ID_CHARS),
            taskId = taskId.trim().take(MAX_ID_CHARS),
            createdAtMillis = now,
            expiresAtMillis = now + AgentObservedContext.DEFAULT_TTL_MILLIS
        )
        val current = load(now).filterNot { existing ->
            existing.targetId == cleanTarget && existing.text == cleanText && existing.conversationId == entry.conversationId
        }
        val otherTargets = current.filterNot { it.targetId == cleanTarget }
        val targetEntries = (current.filter { it.targetId == cleanTarget } + entry).takeLast(MAX_ENTRIES_PER_TARGET)
        save((otherTargets + targetEntries).sortedBy { it.createdAtMillis }.takeLast(MAX_TOTAL_ENTRIES))
        return entry
    }

    @Synchronized
    fun peek(targetId: String, conversationId: String = ""): List<AgentObservedContext> {
        val now = System.currentTimeMillis()
        val current = load(now)
        return current.filter { entry ->
            entry.targetId == targetId.trim() &&
                (conversationId.isBlank() || entry.conversationId.isBlank() || entry.conversationId == conversationId)
        }.takeLast(MAX_ENTRIES_PER_TARGET)
    }

    @Synchronized
    fun acknowledge(entryIds: Set<String>): Int {
        if (entryIds.isEmpty()) return 0
        val current = load(System.currentTimeMillis())
        val remaining = current.filterNot { it.id in entryIds }
        if (remaining.size != current.size) save(remaining)
        return current.size - remaining.size
    }

    @Synchronized
    fun clearTarget(targetId: String): Int {
        val current = load(System.currentTimeMillis())
        val remaining = current.filterNot { it.targetId == targetId.trim() }
        if (remaining.size != current.size) save(remaining)
        return current.size - remaining.size
    }

    @Synchronized
    fun clear() = database.clear()

    private fun load(nowMillis: Long): List<AgentObservedContext> = runCatching {
        val array = JSONArray(database.readString(KEY_ITEMS, "[]"))
        buildList {
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                val entry = AgentObservedContext(
                    id = item.optString("id").ifBlank { UUID.randomUUID().toString() },
                    targetId = item.optString("target_id").take(MAX_TARGET_CHARS),
                    text = item.optString("text").take(MAX_ENTRY_CHARS),
                    conversationId = item.optString("conversation_id").take(MAX_ID_CHARS),
                    taskId = item.optString("task_id").take(MAX_ID_CHARS),
                    createdAtMillis = item.optLong("created_at_millis"),
                    expiresAtMillis = item.optLong("expires_at_millis")
                )
                if (entry.targetId.isNotBlank() && entry.text.isNotBlank() && !entry.isExpired(nowMillis)) add(entry)
            }
        }
    }.getOrDefault(emptyList())

    private fun save(items: List<AgentObservedContext>) {
        val array = JSONArray()
        items.forEach { item ->
            array.put(JSONObject()
                .put("id", item.id)
                .put("target_id", item.targetId)
                .put("text", item.text)
                .put("conversation_id", item.conversationId)
                .put("task_id", item.taskId)
                .put("created_at_millis", item.createdAtMillis)
                .put("expires_at_millis", item.expiresAtMillis))
        }
        database.writeString(KEY_ITEMS, array.toString())
    }

    companion object {
        private const val DATABASE = "signalasi_agent_observation_context_v1"
        private const val KEY_ITEMS = "items"
        private const val MAX_TOTAL_ENTRIES = 128
        private const val MAX_ENTRIES_PER_TARGET = 16
        private const val MAX_TARGET_CHARS = 160
        private const val MAX_ID_CHARS = 160
        private const val MAX_ENTRY_CHARS = 8_000
    }
}
