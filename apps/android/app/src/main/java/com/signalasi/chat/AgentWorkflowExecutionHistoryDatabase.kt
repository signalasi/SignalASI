package com.signalasi.chat

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONObject
import java.security.MessageDigest

internal class AgentWorkflowExecutionHistoryDatabase(
    context: Context,
    databaseName: String = DEFAULT_DATABASE_NAME
) : SQLiteOpenHelper(context.applicationContext, databaseName, null, DATABASE_VERSION) {
    init {
        setWriteAheadLoggingEnabled(true)
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE workflow_execution_history (
                sequence INTEGER PRIMARY KEY AUTOINCREMENT,
                record_hash TEXT UNIQUE NOT NULL,
                workflow_hash TEXT NOT NULL,
                started_at_millis INTEGER NOT NULL,
                completed_at_millis INTEGER NOT NULL,
                encrypted_payload TEXT NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE INDEX workflow_execution_history_recent
            ON workflow_execution_history(started_at_millis DESC, completed_at_millis DESC, sequence DESC)
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE INDEX workflow_execution_history_workflow
            ON workflow_execution_history(workflow_hash, started_at_millis DESC, sequence DESC)
            """.trimIndent()
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    @Synchronized
    fun upsert(record: AgentWorkflowExecutionRecord) {
        check(insertRecord(writableDatabase, record) != -1L) {
            "Agent workflow execution history write failed"
        }
    }

    @Synchronized
    fun findById(id: String): AgentWorkflowExecutionRecord? {
        if (id.isBlank()) return null
        return readableDatabase.query(
            TABLE_HISTORY,
            PAYLOAD_COLUMNS,
            "record_hash = ?",
            arrayOf(digest(id)),
            null,
            null,
            null,
            "1"
        ).use { cursor ->
            if (cursor.moveToFirst()) decodeRecord(cursor) else null
        }
    }

    @Synchronized
    fun recent(limit: Int): List<AgentWorkflowExecutionRecord> {
        if (limit <= 0) return emptyList()
        return readableDatabase.query(
            TABLE_HISTORY,
            PAYLOAD_COLUMNS,
            null,
            null,
            null,
            null,
            NEWEST_FIRST,
            limit.toString()
        ).use(::decodeRecords)
    }

    @Synchronized
    fun listAll(): List<AgentWorkflowExecutionRecord> =
        readableDatabase.query(
            TABLE_HISTORY,
            PAYLOAD_COLUMNS,
            null,
            null,
            null,
            null,
            OLDEST_FIRST
        ).use(::decodeRecords)

    @Synchronized
    fun replaceAll(records: List<AgentWorkflowExecutionRecord>) {
        val database = writableDatabase
        database.beginTransaction()
        try {
            database.delete(TABLE_HISTORY, null, null)
            records.forEach { record ->
                check(insertRecord(database, record) != -1L) {
                    "Agent workflow execution history restore failed"
                }
            }
            database.setTransactionSuccessful()
        } finally {
            database.endTransaction()
        }
    }

    @Synchronized
    fun deleteForWorkflow(workflowId: String): Int {
        if (workflowId.isBlank()) return 0
        return writableDatabase.delete(
            TABLE_HISTORY,
            "workflow_hash = ?",
            arrayOf(digest(workflowId))
        )
    }

    @Synchronized
    fun clear() {
        writableDatabase.delete(TABLE_HISTORY, null, null)
    }

    @Synchronized
    fun count(): Int =
        readableDatabase.rawQuery("SELECT COUNT(*) FROM $TABLE_HISTORY", null).use { cursor ->
            if (cursor.moveToFirst()) cursor.getInt(0) else 0
        }

    @Synchronized
    internal fun encryptedPayloadForTest(recordId: String): String? =
        readableDatabase.query(
            TABLE_HISTORY,
            arrayOf("encrypted_payload"),
            "record_hash = ?",
            arrayOf(digest(recordId)),
            null,
            null,
            null,
            "1"
        ).use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }

    private fun insertRecord(
        database: SQLiteDatabase,
        record: AgentWorkflowExecutionRecord
    ): Long {
        val recordHash = digest(record.id)
        val values = ContentValues().apply {
            put("record_hash", recordHash)
            put("workflow_hash", digest(record.workflowId))
            put("started_at_millis", record.startedAtMillis)
            put("completed_at_millis", record.completedAtMillis)
            put("encrypted_payload", encode(record, recordHash))
        }
        return database.insertWithOnConflict(
            TABLE_HISTORY,
            null,
            values,
            SQLiteDatabase.CONFLICT_REPLACE
        )
    }

    private fun decodeRecords(cursor: Cursor): List<AgentWorkflowExecutionRecord> = buildList {
        while (cursor.moveToNext()) add(decodeRecord(cursor))
    }

    private fun decodeRecord(cursor: Cursor): AgentWorkflowExecutionRecord {
        val recordHash = cursor.getString(cursor.getColumnIndexOrThrow("record_hash"))
        val encrypted = cursor.getString(cursor.getColumnIndexOrThrow("encrypted_payload"))
        val raw = AgentStorageCipher.decrypt(encrypted, associatedData(recordHash))
            ?: error("Agent workflow execution history could not be decrypted")
        val json = JSONObject(raw)
        return AgentWorkflowExecutionRecord(
            id = json.getString("id"),
            workflowId = json.getString("workflow_id"),
            workflowName = json.getString("workflow_name"),
            source = AgentWorkflowExecutionSource.valueOf(json.getString("source")),
            status = AgentWorkflowExecutionStatus.valueOf(json.getString("status")),
            startedAtMillis = json.getLong("started_at_millis"),
            completedAtMillis = json.getLong("completed_at_millis"),
            resultSummary = json.getString("result_summary")
        )
    }

    private fun encode(record: AgentWorkflowExecutionRecord, recordHash: String): String {
        val raw = JSONObject()
            .put("id", record.id)
            .put("workflow_id", record.workflowId)
            .put("workflow_name", record.workflowName)
            .put("source", record.source.name)
            .put("status", record.status.name)
            .put("started_at_millis", record.startedAtMillis)
            .put("completed_at_millis", record.completedAtMillis)
            .put("result_summary", record.resultSummary)
            .toString()
        return AgentStorageCipher.encrypt(raw, associatedData(recordHash))
    }

    private fun associatedData(recordHash: String): ByteArray =
        "agent-workflow-execution:$recordHash".toByteArray(Charsets.UTF_8)

    private fun digest(value: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }

    companion object {
        internal const val DEFAULT_DATABASE_NAME = "signalasi_agent_workflow_execution_history.db"
        private const val DATABASE_VERSION = 1
        private const val TABLE_HISTORY = "workflow_execution_history"
        private const val NEWEST_FIRST =
            "started_at_millis DESC, completed_at_millis DESC, sequence DESC"
        private const val OLDEST_FIRST =
            "started_at_millis ASC, completed_at_millis ASC, sequence ASC"
        private val PAYLOAD_COLUMNS = arrayOf("record_hash", "encrypted_payload")
    }
}
