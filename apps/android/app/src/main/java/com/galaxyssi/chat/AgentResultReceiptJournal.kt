package com.galaxyssi.chat

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import org.json.JSONObject

internal data class AgentResultReceiptWork(val receipt: AgentResultReceipt, val state: Int, val attempts: Int, val due: Long)

/** Uses the inbox's connection and transaction, so the reply and receipt commit together. */
internal class AgentResultReceiptJournal(private val namespace: String) {
    private fun aad(id: String) = "result-receipt:$namespace:$id".toByteArray(Charsets.UTF_8)

    fun insert(db: SQLiteDatabase, receipt: AgentResultReceipt, responseKey: String) {
        val present = db.rawQuery("SELECT 1 FROM inbox WHERE identity_key=?", arrayOf(responseKey)).use { it.moveToFirst() }
        check(present) { "A result receipt requires a durable inbox row" }
        val encrypted = AgentStorageCipher.encrypt(receipt.payload().toString(), aad(receipt.id))
        val inserted = db.insertWithOnConflict("result_receipts", null, ContentValues().apply {
            put("receipt_id", receipt.id); put("response_key", responseKey)
            put("encrypted_value", encrypted)
        }, SQLiteDatabase.CONFLICT_IGNORE)
        check(inserted != -1L || db.rawQuery("SELECT 1 FROM result_receipts WHERE receipt_id=?", arrayOf(receipt.id)).use {
            it.moveToFirst()
        }) { "Result receipt was not persisted" }
        db.update("result_receipts", ContentValues().apply {
            put("encrypted_value", encrypted); put("state", 0); put("attempts", 0); put("next_attempt_at", 0)
        }, "receipt_id=? AND state=2", arrayOf(receipt.id))
    }

    fun due(db: SQLiteDatabase, now: Long, limit: Int = 32): List<AgentResultReceiptWork> {
        val bound = limit.coerceIn(1, 32)
        // Each state is an ordered index seek. Merge at most 64 rows, not the entire backlog.
        val rows = db.rawQuery(DUE_SQL, arrayOf(now.toString(), bound.toString(), now.toString(), bound.toString()))
            .use { cursor -> buildList {
            while (cursor.moveToNext()) add(arrayOf(cursor.getString(0), cursor.getString(1), cursor.getInt(2).toString(),
                cursor.getInt(3).toString(), cursor.getLong(4).toString()))
        } }.sortedWith(compareBy<Array<String>> { it[4].toLong() }.thenBy { it[0] }).take(bound)
        return rows.mapNotNull { row ->
            val receipt = runCatching {
                val payload = JSONObject(requireNotNull(AgentStorageCipher.decrypt(row[1], aad(row[0]))))
                requireNotNull(AgentResultReceipt.from(payload, payload.getString("desktop_id"))).also {
                    check(it.id == row[0])
                }
            }.getOrNull()
            if (receipt == null) {
                // A corrupt row cannot starve unrelated receipts or erase a newer replacement.
                db.update("result_receipts", ContentValues().apply { put("state", 2) },
                    "receipt_id=? AND encrypted_value=?", arrayOf(row[0], row[1]))
                null
            } else AgentResultReceiptWork(receipt, row[2].toInt(), row[3].toInt(), row[4].toLong())
        }
    }

    fun claim(db: SQLiteDatabase, work: AgentResultReceiptWork, now: Long): Boolean = db.update("result_receipts",
        ContentValues().apply {
            put("attempts", (work.attempts + 1).coerceAtMost(30))
            put("next_attempt_at", now + retryDelay(work.attempts))
        }, "receipt_id=? AND state=? AND attempts=? AND next_attempt_at=?",
        arrayOf(work.receipt.id, work.state.toString(), work.attempts.toString(), work.due.toString())) == 1

    fun confirm(db: SQLiteDatabase, receipt: AgentResultReceipt): Boolean = db.update("result_receipts",
        ContentValues().apply { put("state", 1); put("next_attempt_at", 0) },
        "receipt_id=? AND state=0", arrayOf(receipt.id)) == 1

    fun cleaned(db: SQLiteDatabase, receipt: AgentResultReceipt): Boolean = db.update("result_receipts",
        ContentValues().apply { put("state", 3); putNull("encrypted_value") },
        "receipt_id=? AND state=1", arrayOf(receipt.id)) == 1

    fun nextWake(db: SQLiteDatabase): Long? = db.rawQuery(NEXT_SQL, null).use { cursor ->
        var next: Long? = null
        while (cursor.moveToNext()) next = minOf(next ?: Long.MAX_VALUE, cursor.getLong(0))
        next
    }

    companion object {
        private const val COLUMNS = "receipt_id,encrypted_value,state,attempts,next_attempt_at"
        const val DUE_SQL = "SELECT * FROM (SELECT $COLUMNS FROM result_receipts WHERE state=0 AND next_attempt_at<=? " +
            "ORDER BY next_attempt_at,receipt_id LIMIT ?) UNION ALL " +
            "SELECT * FROM (SELECT $COLUMNS FROM result_receipts WHERE state=1 AND next_attempt_at<=? " +
            "ORDER BY next_attempt_at,receipt_id LIMIT ?)"
        const val NEXT_SQL = "SELECT * FROM (SELECT next_attempt_at FROM result_receipts WHERE state=0 " +
            "ORDER BY next_attempt_at,receipt_id LIMIT 1) UNION ALL " +
            "SELECT * FROM (SELECT next_attempt_at FROM result_receipts WHERE state=1 ORDER BY next_attempt_at,receipt_id LIMIT 1)"
        fun retryDelay(attempt: Int): Long = minOf(300_000L, 5_000L shl attempt.coerceIn(0, 6))
        fun create(db: SQLiteDatabase) {
            db.execSQL("CREATE TABLE result_receipts(receipt_id TEXT PRIMARY KEY,response_key TEXT NOT NULL," +
                "encrypted_value TEXT,state INTEGER NOT NULL DEFAULT 0,attempts INTEGER NOT NULL DEFAULT 0," +
                "next_attempt_at INTEGER NOT NULL DEFAULT 0)")
            db.execSQL("CREATE INDEX result_receipts_due ON result_receipts(state,next_attempt_at,receipt_id)")
        }
    }
}
