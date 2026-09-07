package com.galaxyssi.chat

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONObject
import java.util.UUID

/** Only timing and opaque row keys are plaintext; endpoints, receipts, and wire data are encrypted. */
internal class LinkTransportReceiptJournal(context: Context, private val name: String = DATABASE_NAME) :
    SQLiteOpenHelper(context.applicationContext, name, null, 1) {
    private val cipher = AgentRowStorageCipher(context.applicationContext, name)
    init { setWriteAheadLoggingEnabled(true) }
    override fun onConfigure(db: SQLiteDatabase) { db.execSQL("PRAGMA synchronous=FULL") }
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE receipts (id TEXT PRIMARY KEY, next_at INTEGER NOT NULL, attempt TEXT NOT NULL, payload TEXT NOT NULL)")
        db.execSQL("CREATE INDEX receipts_due ON receipts(next_at, id)")
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    @Synchronized fun enqueue(receipt: LinkTransportReceipt, now: Long = System.currentTimeMillis()) {
        // A replay does not replace an in-flight attempt or its already-prepared Signal ciphertext.
        val inserted = writableDatabase.insertWithOnConflict("receipts", null, ContentValues().apply {
            put("id", receipt.key); put("next_at", now); put("attempt", "")
            put("payload", seal(receipt.key, JSONObject().put("receipt", receipt.json()).put("wire", "")))
        }, SQLiteDatabase.CONFLICT_IGNORE)
        check(inserted != -1L || readableDatabase.query("receipts", arrayOf("id"), "id = ?",
            arrayOf(receipt.key), null, null, null, "1").use { it.moveToFirst() }) {
            "Transport receipt intent could not be persisted"
        }
    }

    @Synchronized fun due(now: Long, limit: Int = 4): List<String> {
        require(limit in 1..32)
        return readableDatabase.query("receipts", arrayOf("id"), "next_at <= ?", arrayOf(now.toString()),
            null, null, "next_at, id", limit.toString()).use { cursor ->
            buildList { while (cursor.moveToNext()) add(cursor.getString(0)) }
        }
    }

    @Synchronized fun claim(key: String, now: Long): LinkTransportReceiptWork? {
        val db = writableDatabase
        val encoded = db.query("receipts", arrayOf("payload"), "id = ? AND next_at <= ?", arrayOf(key, now.toString()),
            null, null, null, "1").use { if (it.moveToFirst()) it.getString(0) else null } ?: return null
        val attempt = LinkTransportReceiptAttempt(key, UUID.randomUUID().toString())
        check(db.update("receipts", ContentValues().apply {
            put("attempt", attempt.token); put("next_at", now + RETRY_MILLIS)
        }, "id = ? AND next_at <= ?", arrayOf(key, now.toString())) == 1)
        val value = cipher.decrypt(encoded, key.toByteArray())?.let(::JSONObject)
            ?: error("Transport receipt row failed authentication")
        val receipt = LinkTransportReceipt.from(value.getJSONObject("receipt"))
        check(receipt.key == key)
        return LinkTransportReceiptWork(receipt, value.optString("wire"), attempt)
    }

    @Synchronized fun prepared(work: LinkTransportReceiptWork, wire: String): Boolean {
        require(wire.isNotBlank() && wire.toByteArray(Charsets.UTF_8).size <= 64 * 1024)
        return writableDatabase.update("receipts", ContentValues().apply {
            put("payload", seal(work.attempt.key, JSONObject().put("receipt", work.receipt.json()).put("wire", wire)))
        }, "id = ? AND attempt = ?", arrayOf(work.attempt.key, work.attempt.token)) == 1
    }

    @Synchronized fun acknowledge(attempt: LinkTransportReceiptAttempt): Boolean =
        writableDatabase.delete("receipts", "id = ? AND attempt = ?", arrayOf(attempt.key, attempt.token)) == 1

    @Synchronized fun reconnect(now: Long = System.currentTimeMillis()) {
        writableDatabase.execSQL("UPDATE receipts SET next_at = MIN(next_at, ?), attempt = ''", arrayOf(now))
    }

    @Synchronized fun nextDue(): Long? = readableDatabase.rawQuery("SELECT MIN(next_at) FROM receipts", null).use {
        if (it.moveToFirst() && !it.isNull(0)) it.getLong(0) else null
    }

    private fun seal(key: String, value: JSONObject) = cipher.encrypt(value.toString(), key.toByteArray())
    companion object {
        const val DATABASE_NAME = "galaxyssi_transport_receipts.db"
        const val RETRY_MILLIS = 12_000L
    }
}
