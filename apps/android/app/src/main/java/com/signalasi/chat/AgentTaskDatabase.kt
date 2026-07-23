package com.signalasi.chat

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONArray
import org.json.JSONObject

internal class AgentTaskDatabase(
    context: Context,
    databaseName: String = DEFAULT_DATABASE_NAME
) : SQLiteOpenHelper(context.applicationContext, databaseName, null, DATABASE_VERSION) {
    init {
        setWriteAheadLoggingEnabled(true)
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE agent_tasks (
                task_id TEXT PRIMARY KEY NOT NULL,
                session_id TEXT NOT NULL,
                created_at_millis INTEGER NOT NULL,
                updated_at_millis INTEGER NOT NULL,
                encrypted_payload TEXT NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE INDEX agent_tasks_session_order
            ON agent_tasks(session_id, updated_at_millis DESC, task_id DESC)
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE INDEX agent_tasks_recent_order
            ON agent_tasks(updated_at_millis DESC, task_id DESC)
            """.trimIndent()
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    @Synchronized
    fun upsert(record: AgentTaskRecord) {
        check(insertRecord(writableDatabase, record) != -1L) { "Agent task record write failed" }
    }

    @Synchronized
    fun recent(limit: Int): List<AgentTaskRecord> =
        readableDatabase.query(
            TABLE_TASKS,
            PAYLOAD_COLUMNS,
            null,
            null,
            null,
            null,
            TASK_ORDER,
            limit.toString()
        ).use(::decodeRecords)

    @Synchronized
    fun forSession(sessionId: String, limit: Int): List<AgentTaskRecord> {
        if (sessionId.isBlank()) return emptyList()
        return readableDatabase.query(
            TABLE_TASKS,
            PAYLOAD_COLUMNS,
            "session_id = ?",
            arrayOf(sessionId),
            null,
            null,
            TASK_ORDER,
            limit.toString()
        ).use(::decodeRecords)
    }

    @Synchronized
    fun find(taskId: String): AgentTaskRecord? {
        if (taskId.isBlank()) return null
        return readableDatabase.query(
            TABLE_TASKS,
            PAYLOAD_COLUMNS,
            "task_id = ?",
            arrayOf(taskId),
            null,
            null,
            null,
            "1"
        ).use { cursor ->
            if (cursor.moveToFirst()) decodeRecord(cursor) else null
        }
    }

    @Synchronized
    fun search(query: String, limit: Int): List<AgentTaskRecord> {
        if (query.isBlank()) return recent(limit)
        val tokens = query.lowercase()
            .split(Regex("\\s+"))
            .filter { it.length >= 2 }
        return readableDatabase.query(
            TABLE_TASKS,
            PAYLOAD_COLUMNS,
            null,
            null,
            null,
            null,
            TASK_ORDER
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    val record = decodeRecord(cursor)
                    val score = score(record, query, tokens)
                    if (score > 0) add(record to score)
                }
            }
        }
            .sortedWith(
                compareByDescending<Pair<AgentTaskRecord, Int>> { it.second }
                    .thenByDescending { it.first.updatedAtMillis }
            )
            .take(limit)
            .map { it.first }
    }

    @Synchronized
    fun rebindSession(sourceSessionId: String, targetSessionId: String): Int {
        if (sourceSessionId.isBlank() || targetSessionId.isBlank() || sourceSessionId == targetSessionId) {
            return 0
        }
        val records = forSession(sourceSessionId, Int.MAX_VALUE)
        if (records.isEmpty()) return 0
        val db = writableDatabase
        db.beginTransaction()
        return try {
            records.forEach { record ->
                val rebound = record.copy(sessionId = targetSessionId)
                val values = ContentValues().apply {
                    put("session_id", targetSessionId)
                    put("encrypted_payload", encode(rebound))
                }
                check(db.update(TABLE_TASKS, values, "task_id = ?", arrayOf(record.taskId)) == 1) {
                    "Agent task session rebind failed"
                }
            }
            db.setTransactionSuccessful()
            records.size
        } finally {
            db.endTransaction()
        }
    }

    @Synchronized
    fun deleteByTaskOrSessionIds(values: Set<String>) {
        val cleanValues = values.map(String::trim).filter(String::isNotBlank).distinct()
        if (cleanValues.isEmpty()) return
        val db = writableDatabase
        db.beginTransaction()
        try {
            cleanValues.forEach { value ->
                db.delete(
                    TABLE_TASKS,
                    "task_id = ? OR session_id = ?",
                    arrayOf(value, value)
                )
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    @Synchronized
    fun clear() {
        writableDatabase.delete(TABLE_TASKS, null, null)
    }

    @Synchronized
    fun exportJson(): JSONArray {
        val output = JSONArray()
        readableDatabase.query(
            TABLE_TASKS,
            PAYLOAD_COLUMNS,
            null,
            null,
            null,
            null,
            "created_at_millis ASC, task_id ASC"
        ).use { cursor ->
            while (cursor.moveToNext()) output.put(encodeJson(decodeRecord(cursor)))
        }
        return output
    }

    @Synchronized
    fun replaceAllJson(input: JSONArray) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.delete(TABLE_TASKS, null, null)
            for (index in 0 until input.length()) {
                val record = input.optJSONObject(index)?.let(::decodeJson) ?: continue
                check(insertRecord(db, record) != -1L) { "Agent task backup restore failed" }
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    @Synchronized
    fun count(): Int =
        readableDatabase.rawQuery("SELECT COUNT(*) FROM $TABLE_TASKS", null).use { cursor ->
            if (cursor.moveToFirst()) cursor.getInt(0) else 0
        }

    @Synchronized
    internal fun encryptedPayloadForTest(taskId: String): String? =
        readableDatabase.query(
            TABLE_TASKS,
            arrayOf("encrypted_payload"),
            "task_id = ?",
            arrayOf(taskId),
            null,
            null,
            null,
            "1"
        ).use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }

    private fun decodeRecords(cursor: Cursor): List<AgentTaskRecord> = buildList {
        while (cursor.moveToNext()) add(decodeRecord(cursor))
    }

    private fun decodeRecord(cursor: Cursor): AgentTaskRecord {
        val taskId = cursor.getString(cursor.getColumnIndexOrThrow("task_id"))
        val encrypted = cursor.getString(cursor.getColumnIndexOrThrow("encrypted_payload"))
        val raw = AgentStorageCipher.decrypt(encrypted, associatedData(taskId))
            ?: error("Agent task record could not be decrypted")
        return decode(JSONObject(raw), taskId)
    }

    private fun insertRecord(db: SQLiteDatabase, record: AgentTaskRecord): Long {
        val values = ContentValues().apply {
            put("task_id", record.taskId)
            put("session_id", record.sessionId)
            put("created_at_millis", record.createdAtMillis)
            put("updated_at_millis", record.updatedAtMillis)
            put("encrypted_payload", encode(record))
        }
        return db.insertWithOnConflict(
            TABLE_TASKS,
            null,
            values,
            SQLiteDatabase.CONFLICT_REPLACE
        )
    }

    private fun encode(record: AgentTaskRecord): String {
        val raw = encodeJson(record)
            .toString()
        return AgentStorageCipher.encrypt(raw, associatedData(record.taskId))
    }

    private fun encodeJson(record: AgentTaskRecord): JSONObject =
        JSONObject()
            .put("task_id", record.taskId)
            .put("session_id", record.sessionId)
            .put("goal", record.goal)
            .put("phase", record.phase.name)
            .put("route_kind", record.routeKind.name)
            .put("target_title", record.targetTitle)
            .put("risk", record.risk.name)
            .put("blocked", record.blocked)
            .put("result", record.result)
            .put("verification", record.verification)
            .put("output_files", JSONArray(record.outputFiles))
            .put("execution_log", JSONArray(record.executionLog))
            .put("created_at_millis", record.createdAtMillis)
            .put("updated_at_millis", record.updatedAtMillis)

    private fun decode(json: JSONObject, fallbackTaskId: String): AgentTaskRecord =
        AgentTaskRecord(
            taskId = json.optString("task_id", fallbackTaskId),
            sessionId = json.optString("session_id"),
            goal = json.optString("goal"),
            phase = enumOrDefault(json.optString("phase"), AgentPhase.OBSERVING),
            routeKind = enumOrDefault(json.optString("route_kind"), AgentRouteKind.UNKNOWN),
            targetTitle = json.optString("target_title"),
            risk = enumOrDefault(json.optString("risk"), AgentRisk.LOW),
            blocked = json.optBoolean("blocked"),
            result = json.optString("result"),
            verification = json.optString("verification"),
            outputFiles = json.optJSONArray("output_files").strings(),
            executionLog = json.optJSONArray("execution_log").strings(),
            createdAtMillis = json.optLong("created_at_millis"),
            updatedAtMillis = json.optLong("updated_at_millis")
        )

    private fun decodeJson(json: JSONObject): AgentTaskRecord? {
        val taskId = json.optString("task_id").trim()
        val goal = json.optString("goal").trim()
        if (taskId.isBlank() || goal.isBlank()) return null
        return decode(json, taskId)
    }

    private fun score(record: AgentTaskRecord, query: String, tokens: List<String>): Int {
        val normalizedQuery = query.lowercase()
        val goal = record.goal.lowercase()
        val target = record.targetTitle.lowercase()
        val haystack = listOf(
            goal,
            target,
            record.result,
            record.verification,
            record.outputFiles.joinToString("\n"),
            record.executionLog.joinToString("\n"),
            record.phase.name,
            record.routeKind.name,
            record.risk.name
        ).joinToString("\n").lowercase()
        var total = if (haystack.contains(normalizedQuery)) 10 else 0
        tokens.forEach { token ->
            if (haystack.contains(token)) total += 2
            if (goal.contains(token)) total += 3
            if (target.contains(token)) total += 2
        }
        return total
    }

    private fun associatedData(taskId: String): ByteArray =
        "agent-task-record:$taskId".toByteArray(Charsets.UTF_8)

    private inline fun <reified T : Enum<T>> enumOrDefault(value: String, default: T): T =
        runCatching { enumValueOf<T>(value) }.getOrElse { default }

    private fun JSONArray?.strings(): List<String> {
        val source = this ?: return emptyList()
        return buildList {
            for (index in 0 until source.length()) {
                source.optString(index).takeIf(String::isNotBlank)?.let(::add)
            }
        }
    }

    companion object {
        internal const val DEFAULT_DATABASE_NAME = "signalasi_agent_tasks.db"
        private const val DATABASE_VERSION = 1
        private const val TABLE_TASKS = "agent_tasks"
        private const val TASK_ORDER = "updated_at_millis DESC, task_id DESC"
        private val PAYLOAD_COLUMNS = arrayOf("task_id", "encrypted_payload")
    }
}
