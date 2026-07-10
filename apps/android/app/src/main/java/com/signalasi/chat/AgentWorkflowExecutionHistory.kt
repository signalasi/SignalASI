package com.signalasi.chat

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

enum class AgentWorkflowExecutionSource {
    MANUAL,
    SCHEDULE,
    EVENT
}

enum class AgentWorkflowExecutionStatus {
    RUNNING,
    WAITING_CONFIRMATION,
    WAITING_RESPONSE,
    COMPLETED,
    SKIPPED,
    FAILED,
    CANCELLED,
    BLOCKED
}

data class AgentWorkflowExecutionRecord(
    val id: String = UUID.randomUUID().toString(),
    val workflowId: String,
    val workflowName: String,
    val source: AgentWorkflowExecutionSource,
    val status: AgentWorkflowExecutionStatus,
    val startedAtMillis: Long = System.currentTimeMillis(),
    val completedAtMillis: Long = 0L,
    val resultSummary: String = ""
)

class AgentWorkflowExecutionHistoryStore(context: Context) {
    private val preferences = AgentEncryptedPreferences(context, PREFS)

    @Synchronized
    fun upsert(record: AgentWorkflowExecutionRecord) {
        val normalized = normalizeForWrite(record)
        save(
            load()
                .filterNot { it.id == normalized.id }
                .plus(normalized)
        )
    }

    @Synchronized
    fun findById(id: String): AgentWorkflowExecutionRecord? {
        val cleanId = id.trim()
        if (cleanId.isBlank()) return null
        return load().firstOrNull { it.id == cleanId }
    }

    @Synchronized
    fun recent(limit: Int = DEFAULT_RECENT_LIMIT): List<AgentWorkflowExecutionRecord> =
        load()
            .sortedWith(NEWEST_FIRST)
            .take(limit.coerceIn(0, MAX_ITEMS))

    @Synchronized
    fun deleteForWorkflow(workflowId: String): Int {
        val cleanWorkflowId = workflowId.trim()
        if (cleanWorkflowId.isBlank()) return 0
        val items = load()
        val kept = items.filterNot { it.workflowId == cleanWorkflowId }
        if (kept.size != items.size) save(kept)
        return items.size - kept.size
    }

    @Synchronized
    fun clear() {
        preferences.clear()
    }

    private fun load(): List<AgentWorkflowExecutionRecord> {
        val raw = preferences.readString(KEY_ITEMS, "[]")
        if (raw.length > MAX_SERIALIZED_CHARS) return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (index in 0 until array.length()) {
                    decode(array.optJSONObject(index) ?: continue)?.let(::add)
                }
            }.bounded()
        }.getOrDefault(emptyList())
    }

    private fun save(items: List<AgentWorkflowExecutionRecord>) {
        val bounded = items.mapNotNull(::normalizeOrNull).bounded().toMutableList()
        while (true) {
            val serialized = encode(bounded).toString()
            if (serialized.length <= MAX_SERIALIZED_CHARS) {
                preferences.writeString(KEY_ITEMS, serialized)
                return
            }
            check(bounded.isNotEmpty()) { "Agent workflow execution history storage limit exceeded" }
            bounded.removeAt(0)
        }
    }

    private fun encode(items: List<AgentWorkflowExecutionRecord>): JSONArray = JSONArray().apply {
        items.forEach { record ->
            put(
                JSONObject()
                    .put("id", record.id)
                    .put("workflow_id", record.workflowId)
                    .put("workflow_name", record.workflowName)
                    .put("source", record.source.name)
                    .put("status", record.status.name)
                    .put("started_at", record.startedAtMillis)
                    .put("completed_at", record.completedAtMillis)
                    .put("result_summary", record.resultSummary)
            )
        }
    }

    private fun decode(json: JSONObject): AgentWorkflowExecutionRecord? {
        val source = enumOrNull<AgentWorkflowExecutionSource>(json.optString("source")) ?: return null
        val status = enumOrNull<AgentWorkflowExecutionStatus>(json.optString("status")) ?: return null
        return normalizeOrNull(
            AgentWorkflowExecutionRecord(
                id = json.optString("id"),
                workflowId = json.optString("workflow_id"),
                workflowName = json.optString("workflow_name"),
                source = source,
                status = status,
                startedAtMillis = json.optLong("started_at", -1L),
                completedAtMillis = json.optLong("completed_at"),
                resultSummary = json.optString("result_summary")
            )
        )
    }

    private fun normalizeForWrite(record: AgentWorkflowExecutionRecord): AgentWorkflowExecutionRecord {
        val normalized = normalizeOrNull(record)
        requireNotNull(normalized) {
            "Workflow execution fields are blank, invalid, or exceed storage limits"
        }
        return normalized
    }

    private fun normalizeOrNull(record: AgentWorkflowExecutionRecord): AgentWorkflowExecutionRecord? {
        val id = record.id.trim()
        val workflowId = record.workflowId.trim()
        val workflowName = record.workflowName.trim().replace(Regex("\\s+"), " ")
        val resultSummary = record.resultSummary.trim()
        if (id.isBlank() || id.length > MAX_IDENTIFIER_LENGTH) return null
        if (workflowId.isBlank() || workflowId.length > MAX_IDENTIFIER_LENGTH) return null
        if (workflowName.isBlank() || workflowName.length > MAX_WORKFLOW_NAME_LENGTH) return null
        if (resultSummary.length > MAX_RESULT_SUMMARY_LENGTH) return null
        if (record.startedAtMillis < 0L || record.completedAtMillis < 0L) return null
        if (record.completedAtMillis != 0L && record.completedAtMillis < record.startedAtMillis) return null
        return record.copy(
            id = id,
            workflowId = workflowId,
            workflowName = workflowName,
            resultSummary = resultSummary
        )
    }

    private fun List<AgentWorkflowExecutionRecord>.bounded(): List<AgentWorkflowExecutionRecord> {
        val byId = LinkedHashMap<String, AgentWorkflowExecutionRecord>()
        sortedWith(OLDEST_FIRST).forEach { record -> byId[record.id] = record }
        return byId.values.sortedWith(OLDEST_FIRST).takeLast(MAX_ITEMS)
    }

    private inline fun <reified T : Enum<T>> enumOrNull(value: String): T? =
        runCatching { enumValueOf<T>(value) }.getOrNull()

    private companion object {
        const val PREFS = "signalasi_agent_workflow_execution_history"
        const val KEY_ITEMS = "items"
        const val MAX_ITEMS = 200
        const val DEFAULT_RECENT_LIMIT = 20
        const val MAX_SERIALIZED_CHARS = 512 * 1024
        const val MAX_IDENTIFIER_LENGTH = 128
        const val MAX_WORKFLOW_NAME_LENGTH = 80
        const val MAX_RESULT_SUMMARY_LENGTH = 2_000

        val OLDEST_FIRST = compareBy<AgentWorkflowExecutionRecord> { it.startedAtMillis }
            .thenBy { it.completedAtMillis }
            .thenBy { it.id }
        val NEWEST_FIRST = OLDEST_FIRST.reversed()
    }
}
