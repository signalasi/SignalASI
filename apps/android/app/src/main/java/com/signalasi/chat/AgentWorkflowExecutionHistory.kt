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

class AgentWorkflowExecutionHistoryStore(
    context: Context,
    databaseName: String = AgentWorkflowExecutionHistoryDatabase.DEFAULT_DATABASE_NAME
) {
    private val database = if (
        databaseName == AgentWorkflowExecutionHistoryDatabase.DEFAULT_DATABASE_NAME
    ) {
        defaultDatabase(context)
    } else {
        AgentWorkflowExecutionHistoryDatabase(context, databaseName)
    }

    @Synchronized
    fun upsert(record: AgentWorkflowExecutionRecord) {
        database.upsert(normalizeForWrite(record))
    }

    @Synchronized
    fun findById(id: String): AgentWorkflowExecutionRecord? {
        val cleanId = id.trim()
        if (cleanId.isBlank()) return null
        return database.findById(cleanId)
    }

    @Synchronized
    fun recent(limit: Int = DEFAULT_RECENT_LIMIT): List<AgentWorkflowExecutionRecord> =
        database.recent(limit.coerceAtLeast(0))

    @Synchronized
    fun deleteForWorkflow(workflowId: String): Int {
        val cleanWorkflowId = workflowId.trim()
        if (cleanWorkflowId.isBlank()) return 0
        return database.deleteForWorkflow(cleanWorkflowId)
    }

    @Synchronized
    fun clear() {
        database.clear()
    }

    internal fun exportJson(): JSONArray {
        val output = JSONArray()
        database.listAll().forEach { output.put(encode(it)) }
        return output
    }

    internal fun replaceAllJson(input: JSONArray) {
        val records = buildList {
            for (index in 0 until input.length()) {
                decode(input.optJSONObject(index) ?: continue)?.let(::add)
            }
        }
        database.replaceAll(records)
    }

    private fun encode(record: AgentWorkflowExecutionRecord): JSONObject =
        JSONObject()
            .put("id", record.id)
            .put("workflow_id", record.workflowId)
            .put("workflow_name", record.workflowName)
            .put("source", record.source.name)
            .put("status", record.status.name)
            .put("started_at", record.startedAtMillis)
            .put("completed_at", record.completedAtMillis)
            .put("result_summary", record.resultSummary)

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

    private inline fun <reified T : Enum<T>> enumOrNull(value: String): T? =
        runCatching { enumValueOf<T>(value) }.getOrNull()

    companion object {
        const val DEFAULT_RECENT_LIMIT = 20
        const val MAX_IDENTIFIER_LENGTH = 128
        const val MAX_WORKFLOW_NAME_LENGTH = 80
        const val MAX_RESULT_SUMMARY_LENGTH = 2_000

        @Volatile
        private var sharedDatabase: AgentWorkflowExecutionHistoryDatabase? = null

        private fun defaultDatabase(context: Context): AgentWorkflowExecutionHistoryDatabase =
            sharedDatabase ?: synchronized(this) {
                sharedDatabase ?: AgentWorkflowExecutionHistoryDatabase(context).also {
                    sharedDatabase = it
                }
            }

        internal fun clearAndCloseDefault(context: Context) {
            synchronized(this) {
                val database = sharedDatabase ?: AgentWorkflowExecutionHistoryDatabase(context)
                database.clear()
                database.close()
                sharedDatabase = null
            }
        }
    }
}
