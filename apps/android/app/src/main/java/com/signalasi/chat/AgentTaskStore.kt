package com.signalasi.chat

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class AgentTaskRecord(
    val taskId: String,
    val sessionId: String,
    val goal: String,
    val phase: AgentPhase,
    val routeKind: AgentRouteKind,
    val targetTitle: String,
    val risk: AgentRisk,
    val blocked: Boolean,
    val result: String = "",
    val verification: String = "",
    val createdAtMillis: Long = System.currentTimeMillis(),
    val updatedAtMillis: Long = System.currentTimeMillis()
)

interface AgentTaskStore {
    fun upsert(record: AgentTaskRecord)
    fun recent(limit: Int = 20): List<AgentTaskRecord>
    fun search(query: String, limit: Int = 10): List<AgentTaskRecord>
    fun clear()
}

class SharedPreferencesAgentTaskStore(context: Context) : AgentTaskStore {
    private val prefs = AgentEncryptedPreferences(context, PREFS)

    override fun upsert(record: AgentTaskRecord) {
        if (record.taskId.isBlank() || record.goal.isBlank()) return
        val items = loadItems()
            .filterNot { it.taskId == record.taskId }
            .plus(record.copy(updatedAtMillis = System.currentTimeMillis()))
            .sortedBy { it.updatedAtMillis }
            .takeLast(MAX_ITEMS)
        saveItems(items)
    }

    override fun recent(limit: Int): List<AgentTaskRecord> =
        loadItems().sortedByDescending { it.updatedAtMillis }.take(limit)

    override fun search(query: String, limit: Int): List<AgentTaskRecord> {
        val cleanQuery = query.trim()
        if (cleanQuery.isBlank()) return recent(limit)
        val tokens = cleanQuery.lowercase().split(Regex("\\s+")).filter { it.length >= 2 }
        return loadItems()
            .map { item -> item to score(item, cleanQuery, tokens) }
            .filter { it.second > 0 }
            .sortedWith(compareByDescending<Pair<AgentTaskRecord, Int>> { it.second }.thenByDescending { it.first.updatedAtMillis })
            .map { it.first }
            .take(limit)
    }

    override fun clear() {
        prefs.clear()
    }

    private fun score(item: AgentTaskRecord, query: String, tokens: List<String>): Int {
        val haystack = listOf(
            item.goal,
            item.targetTitle,
            item.result,
            item.verification,
            item.phase.name,
            item.routeKind.name,
            item.risk.name
        ).joinToString("\n").lowercase()
        var total = 0
        if (haystack.contains(query.lowercase())) total += 10
        tokens.forEach { token ->
            if (haystack.contains(token)) total += 2
            if (item.goal.lowercase().contains(token)) total += 3
            if (item.targetTitle.lowercase().contains(token)) total += 2
        }
        return total
    }

    private fun loadItems(): List<AgentTaskRecord> {
        val raw = prefs.readString(KEY_ITEMS, "[]")
        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (index in 0 until array.length()) {
                    decodeItem(array.optJSONObject(index) ?: continue)?.let { add(it) }
                }
            }
        }.getOrDefault(emptyList())
    }

    private fun saveItems(items: List<AgentTaskRecord>) {
        val array = JSONArray()
        items.forEach { array.put(encodeItem(it)) }
        prefs.writeString(KEY_ITEMS, array.toString())
    }

    private fun encodeItem(item: AgentTaskRecord): JSONObject = JSONObject()
        .put("task_id", item.taskId)
        .put("session_id", item.sessionId)
        .put("goal", item.goal)
        .put("phase", item.phase.name)
        .put("route_kind", item.routeKind.name)
        .put("target_title", item.targetTitle)
        .put("risk", item.risk.name)
        .put("blocked", item.blocked)
        .put("result", item.result)
        .put("verification", item.verification)
        .put("created_at_millis", item.createdAtMillis)
        .put("updated_at_millis", item.updatedAtMillis)

    private fun decodeItem(json: JSONObject): AgentTaskRecord? {
        val taskId = json.optString("task_id")
        val goal = json.optString("goal")
        if (taskId.isBlank() || goal.isBlank()) return null
        return AgentTaskRecord(
            taskId = taskId,
            sessionId = json.optString("session_id"),
            goal = goal,
            phase = enumOrDefault(json.optString("phase"), AgentPhase.OBSERVING),
            routeKind = enumOrDefault(json.optString("route_kind"), AgentRouteKind.UNKNOWN),
            targetTitle = json.optString("target_title"),
            risk = enumOrDefault(json.optString("risk"), AgentRisk.LOW),
            blocked = json.optBoolean("blocked"),
            result = json.optString("result"),
            verification = json.optString("verification"),
            createdAtMillis = json.optLong("created_at_millis", System.currentTimeMillis()),
            updatedAtMillis = json.optLong("updated_at_millis", System.currentTimeMillis())
        )
    }

    private inline fun <reified T : Enum<T>> enumOrDefault(value: String, default: T): T =
        runCatching { enumValueOf<T>(value) }.getOrElse { default }

    private companion object {
        const val PREFS = "signalasi_agent_tasks"
        const val KEY_ITEMS = "items"
        const val MAX_ITEMS = 200
    }
}
