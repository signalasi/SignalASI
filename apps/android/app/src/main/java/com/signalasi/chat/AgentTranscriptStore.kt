package com.signalasi.chat

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

enum class AgentTranscriptRole { USER, ASSISTANT, PROCESS }

data class AgentTranscriptEntry(
    val id: String,
    val role: AgentTranscriptRole,
    val text: String,
    val timestampMillis: Long,
    val dedupeKey: String = ""
)

class AgentTranscriptStore(context: Context) {
    private val preferences = AgentEncryptedPreferences(context.applicationContext, PREFS)

    @Synchronized
    fun list(): List<AgentTranscriptEntry> = decode(preferences.readString(KEY_ITEMS, "[]"))

    @Synchronized
    fun append(
        role: AgentTranscriptRole,
        text: String,
        dedupeKey: String = "",
        timestampMillis: Long = System.currentTimeMillis()
    ): Boolean {
        val cleanText = text.trim().take(MAX_TEXT_CHARACTERS)
        if (cleanText.isBlank()) return false
        val cleanKey = dedupeKey.trim().take(MAX_DEDUPE_KEY_CHARACTERS)
        val current = list().toMutableList()
        if (cleanKey.isNotBlank() && current.any { it.dedupeKey == cleanKey }) return false
        current += AgentTranscriptEntry(
            id = UUID.randomUUID().toString(),
            role = role,
            text = cleanText,
            timestampMillis = timestampMillis,
            dedupeKey = cleanKey
        )
        save(current.takeLast(MAX_ITEMS))
        return true
    }

    fun clear() = preferences.clear()

    private fun save(items: List<AgentTranscriptEntry>) {
        val array = JSONArray()
        items.forEach { entry ->
            array.put(
                JSONObject()
                    .put("id", entry.id)
                    .put("role", entry.role.name)
                    .put("text", entry.text)
                    .put("timestamp", entry.timestampMillis)
                    .put("dedupe_key", entry.dedupeKey)
            )
        }
        preferences.writeString(KEY_ITEMS, array.toString())
    }

    private fun decode(raw: String): List<AgentTranscriptEntry> {
        val array = runCatching { JSONArray(raw) }.getOrDefault(JSONArray())
        return buildList {
            val start = (array.length() - MAX_ITEMS).coerceAtLeast(0)
            for (index in start until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                val text = item.optString("text").trim().take(MAX_TEXT_CHARACTERS)
                if (text.isBlank()) continue
                add(
                    AgentTranscriptEntry(
                        id = item.optString("id").ifBlank { UUID.randomUUID().toString() },
                        role = runCatching { AgentTranscriptRole.valueOf(item.optString("role")) }
                            .getOrDefault(AgentTranscriptRole.ASSISTANT),
                        text = text,
                        timestampMillis = item.optLong("timestamp", System.currentTimeMillis()),
                        dedupeKey = item.optString("dedupe_key").take(MAX_DEDUPE_KEY_CHARACTERS)
                    )
                )
            }
        }
    }

    companion object {
        const val PREFS = "signalasi_agent_transcript"
        const val KEY_ITEMS = "items"
        private const val MAX_ITEMS = 300
        private const val MAX_TEXT_CHARACTERS = 16_000
        private const val MAX_DEDUPE_KEY_CHARACTERS = 240
    }
}
