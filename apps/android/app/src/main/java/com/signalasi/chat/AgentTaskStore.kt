package com.signalasi.chat

import android.content.Context
import org.json.JSONArray

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
    val outputFiles: List<String> = emptyList(),
    val executionLog: List<String> = emptyList(),
    val createdAtMillis: Long = System.currentTimeMillis(),
    val updatedAtMillis: Long = System.currentTimeMillis()
)

interface AgentTaskStore {
    fun upsert(record: AgentTaskRecord)
    fun recent(limit: Int = 20): List<AgentTaskRecord>
    fun forSession(sessionId: String, limit: Int = Int.MAX_VALUE): List<AgentTaskRecord>
    fun find(taskId: String): AgentTaskRecord?
    fun search(query: String, limit: Int = 10): List<AgentTaskRecord>
    fun rebindSession(sourceSessionId: String, targetSessionId: String): Int
    fun delete(taskIds: Set<String>)
    fun clear()
}

class SQLiteAgentTaskStore(
    context: Context,
    databaseName: String = AgentTaskDatabase.DEFAULT_DATABASE_NAME
) : AgentTaskStore {
    private val database = if (databaseName == AgentTaskDatabase.DEFAULT_DATABASE_NAME) {
        defaultDatabase(context)
    } else {
        AgentTaskDatabase(context, databaseName)
    }

    override fun upsert(record: AgentTaskRecord) {
        if (record.taskId.isBlank() || record.goal.isBlank()) return
        database.upsert(record.copy(updatedAtMillis = System.currentTimeMillis()))
    }

    override fun recent(limit: Int): List<AgentTaskRecord> =
        database.recent(limit.coerceAtLeast(1))

    override fun forSession(sessionId: String, limit: Int): List<AgentTaskRecord> =
        database.forSession(sessionId.trim(), limit.coerceAtLeast(1))

    override fun find(taskId: String): AgentTaskRecord? =
        database.find(taskId.trim())

    override fun search(query: String, limit: Int): List<AgentTaskRecord> =
        database.search(query.trim(), limit.coerceAtLeast(1))

    override fun rebindSession(sourceSessionId: String, targetSessionId: String): Int =
        database.rebindSession(sourceSessionId.trim(), targetSessionId.trim())

    override fun delete(taskIds: Set<String>) {
        database.deleteByTaskOrSessionIds(taskIds)
    }

    override fun clear() {
        database.clear()
    }

    internal fun exportJson(): JSONArray = database.exportJson()

    internal fun replaceAllJson(input: JSONArray) {
        database.replaceAllJson(input)
    }

    private companion object {
        @Volatile
        var sharedDatabase: AgentTaskDatabase? = null

        fun defaultDatabase(context: Context): AgentTaskDatabase =
            sharedDatabase ?: synchronized(this) {
                sharedDatabase ?: AgentTaskDatabase(context).also { sharedDatabase = it }
            }

        fun closeDefaultDatabase() {
            synchronized(this) {
                sharedDatabase?.close()
                sharedDatabase = null
            }
        }
    }

    internal fun closeDefault() {
        closeDefaultDatabase()
    }
}
