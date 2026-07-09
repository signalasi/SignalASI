package com.signalasi.chat

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

data class AgentKnowledgeItem(
    val id: String = UUID.randomUUID().toString(),
    val kind: AgentKnowledgeKind,
    val title: String,
    val content: String,
    val source: String = "",
    val tags: List<String> = emptyList(),
    val updatedAtMillis: Long = System.currentTimeMillis()
)

data class AgentKnowledgeStats(
    val itemCount: Int = 0,
    val sourceCount: Int = 0,
    val lastUpdatedAtMillis: Long = 0L
)

enum class AgentKnowledgeKind {
    NOTE,
    DOCUMENT,
    SCREEN,
    CHAT,
    TASK
}

interface AgentKnowledgeStore {
    fun upsert(item: AgentKnowledgeItem)
    fun search(query: String, limit: Int = 5): List<AgentKnowledgeItem>
    fun stats(): AgentKnowledgeStats
}

class SharedPreferencesAgentKnowledgeStore(context: Context) : AgentKnowledgeStore {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    override fun upsert(item: AgentKnowledgeItem) {
        val cleanTitle = item.title.trim()
        val cleanContent = item.content.trim()
        if (cleanTitle.isBlank() || cleanContent.isBlank()) return
        val next = item.copy(title = cleanTitle, content = cleanContent)
        val items = loadItems()
            .filterNot { it.id == next.id || (it.kind == next.kind && it.title.equals(next.title, ignoreCase = true)) }
            .plus(next)
            .sortedBy { it.updatedAtMillis }
            .takeLast(MAX_ITEMS)
        saveItems(items)
    }

    override fun search(query: String, limit: Int): List<AgentKnowledgeItem> {
        val cleanQuery = query.trim()
        val items = loadItems()
        if (cleanQuery.isBlank()) return items.takeLast(limit).asReversed()
        val tokens = cleanQuery.lowercase().split(Regex("\\s+")).filter { it.length >= 2 }
        return items.asSequence()
            .map { item -> item to score(item, cleanQuery, tokens) }
            .filter { it.second > 0 }
            .sortedByDescending { it.second }
            .take(limit)
            .map { it.first }
            .toList()
    }

    override fun stats(): AgentKnowledgeStats {
        val items = loadItems()
        return AgentKnowledgeStats(
            itemCount = items.size,
            sourceCount = items.map { it.source }.filter { it.isNotBlank() }.distinct().size,
            lastUpdatedAtMillis = items.maxOfOrNull { it.updatedAtMillis } ?: 0L
        )
    }

    private fun score(item: AgentKnowledgeItem, query: String, tokens: List<String>): Int {
        val haystack = "${item.title}\n${item.content}\n${item.tags.joinToString(" ")}".lowercase()
        var total = 0
        if (haystack.contains(query.lowercase())) total += 10
        tokens.forEach { token ->
            if (haystack.contains(token)) total += 2
            if (item.title.lowercase().contains(token)) total += 3
        }
        return total
    }

    private fun loadItems(): List<AgentKnowledgeItem> {
        val raw = prefs.getString(KEY_ITEMS, "[]") ?: "[]"
        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (index in 0 until array.length()) {
                    decodeItem(array.optJSONObject(index) ?: continue)?.let { add(it) }
                }
            }
        }.getOrDefault(emptyList())
    }

    private fun saveItems(items: List<AgentKnowledgeItem>) {
        val array = JSONArray()
        items.forEach { array.put(encodeItem(it)) }
        prefs.edit().putString(KEY_ITEMS, array.toString()).apply()
    }

    private fun encodeItem(item: AgentKnowledgeItem): JSONObject = JSONObject()
        .put("id", item.id)
        .put("kind", item.kind.name)
        .put("title", item.title)
        .put("content", item.content)
        .put("source", item.source)
        .put("tags", JSONArray().also { array -> item.tags.forEach { array.put(it) } })
        .put("updated_at_millis", item.updatedAtMillis)

    private fun decodeItem(json: JSONObject): AgentKnowledgeItem? {
        val title = json.optString("title")
        val content = json.optString("content")
        if (title.isBlank() || content.isBlank()) return null
        return AgentKnowledgeItem(
            id = json.optString("id").ifBlank { UUID.randomUUID().toString() },
            kind = enumOrDefault(json.optString("kind"), AgentKnowledgeKind.NOTE),
            title = title,
            content = content,
            source = json.optString("source"),
            tags = decodeStringList(json.optJSONArray("tags")),
            updatedAtMillis = json.optLong("updated_at_millis", System.currentTimeMillis())
        )
    }

    private fun decodeStringList(array: JSONArray?): List<String> {
        if (array == null) return emptyList()
        return buildList {
            for (index in 0 until array.length()) {
                array.optString(index).takeIf { it.isNotBlank() }?.let { add(it) }
            }
        }
    }

    private inline fun <reified T : Enum<T>> enumOrDefault(value: String, default: T): T =
        runCatching { enumValueOf<T>(value) }.getOrElse { default }

    private companion object {
        const val PREFS = "signalasi_agent_knowledge"
        const val KEY_ITEMS = "items"
        const val MAX_ITEMS = 500
    }
}
