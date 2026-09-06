package com.galaxyssi.chat.blob

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import com.galaxyssi.chat.AgentStorageCipher
import org.json.JSONObject
import java.io.Closeable
import java.io.File
import java.util.UUID

internal data class BlobOutgoingWork(val id: String, val body: JSONObject, val phase: Int,
    val attempts: Int, val claim: String, val waitingForTask: Boolean)

internal class BlobOutgoingJournal(path: File) : Closeable {
    private val db: SQLiteDatabase
    init {
        check(path.parentFile!!.mkdirs() || path.parentFile!!.isDirectory)
        db = SQLiteDatabase.openOrCreateDatabase(path, null)
        try {
            db.enableWriteAheadLogging()
            db.execSQL("PRAGMA synchronous=FULL")
            db.execSQL("CREATE TABLE IF NOT EXISTS jobs(id TEXT PRIMARY KEY,digest TEXT NOT NULL,body TEXT NOT NULL," +
                "phase INTEGER NOT NULL DEFAULT 0,state INTEGER NOT NULL DEFAULT 0,due INTEGER NOT NULL DEFAULT 0," +
                "attempts INTEGER NOT NULL DEFAULT 0,claim TEXT NOT NULL DEFAULT '',error TEXT NOT NULL DEFAULT ''," +
                "ready INTEGER NOT NULL DEFAULT 0)")
            db.execSQL("CREATE INDEX IF NOT EXISTS jobs_due ON jobs(state,due,id)")
        } catch (error: Exception) {
            db.close()
            throw error
        }
    }
    private fun aad(id: String) = "blob-outgoing-v1:$id".toByteArray(Charsets.UTF_8)
    private fun encode(id: String, body: JSONObject) = AgentStorageCipher.encrypt(body.toString(), aad(id))
    private fun decode(id: String, value: String) = JSONObject(AgentStorageCipher.decrypt(value, aad(id))
        ?: BlobProtocol.fail("invalid_blob_outgoing_checkpoint"))

    @Synchronized fun register(id: String, body: JSONObject, active: Boolean) {
        BlobProtocol.unhex(id, 32).fill(0)
        val hash = BlobProtocol.hash(BlobProtocol.canonical(body))
        db.beginTransaction()
        try {
            val existing = db.rawQuery("SELECT digest FROM jobs WHERE id=?", arrayOf(id)).use {
                if (it.moveToFirst()) it.getString(0) else null
            }
            if (existing != null) {
                if (existing != hash) BlobProtocol.fail("blob_outgoing_identity_conflict")
            } else {
                check(db.insertOrThrow("jobs", null, ContentValues().apply {
                    put("id", id); put("digest", hash); put("body", encode(id, body))
                    put("state", if (active) READY else WAITING)
                    put("ready", if (active) 1 else 0)
                }) != -1L)
            }
            if (active) activate(id)
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    @Synchronized fun activate(id: String) {
        db.update("jobs", ContentValues().apply { put("ready", 1) }, "id=?", arrayOf(id))
        db.update("jobs", ContentValues().apply { put("state", READY); put("due", 0) },
            "id=? AND (state=? OR (state=? AND phase=?))", arrayOf(id, WAITING.toString(), DONE.toString(), CLEANUP.toString()))
    }
    @Synchronized fun contains(id: String): Boolean = db.rawQuery("SELECT 1 FROM jobs WHERE id=?", arrayOf(id))
        .use { it.moveToFirst() }
    @Synchronized fun body(id: String): JSONObject? = db.rawQuery("SELECT body FROM jobs WHERE id=?", arrayOf(id))
        .use { if (it.moveToFirst()) decode(id, it.getString(0)) else null }
    @Synchronized fun recover() {
        // The coordinator must own the process/file lock before resetting abandoned claims.
        db.execSQL("UPDATE jobs SET state=CASE WHEN phase=0 AND ready=0 THEN 0 ELSE 1 END,claim='',due=0 WHERE state=2")
    }

    @Synchronized fun claimDue(now: Long, capacity: Int, occupied: Set<String>): List<BlobOutgoingWork> {
        if (capacity <= 0) return emptyList()
        val bound = (capacity + occupied.size).coerceIn(1, 16)
        db.beginTransaction()
        try {
            val candidates = (listOf(WAITING, READY).flatMap { state ->
                db.rawQuery("SELECT id,body,phase,attempts,due,ready FROM jobs WHERE state=? AND due<=? " +
                    "ORDER BY due,id LIMIT ?", arrayOf(state.toString(), now.toString(), bound.toString())).use { cursor ->
                    buildList { while (cursor.moveToNext()) add(arrayOf(cursor.getString(0), cursor.getString(1),
                        cursor.getInt(2).toString(), cursor.getInt(3).toString(), cursor.getLong(4).toString(),
                        cursor.getInt(5).toString())) }
                }
            }).filterNot { it[0] in occupied }.sortedWith(compareBy<Array<String>> { it[4].toLong() }.thenBy { it[0] })
                .take(capacity.coerceAtMost(4))
            val claimed = candidates.mapNotNull { row ->
                val body = runCatching { decode(row[0], row[1]) }.getOrNull()
                if (body == null) {
                    db.update("jobs", ContentValues().apply { put("due", now + 300_000); put("error", "invalid_checkpoint") },
                        "id=?", arrayOf(row[0]))
                    return@mapNotNull null
                }
                val token = UUID.randomUUID().toString()
                db.update("jobs", ContentValues().apply { put("state", RUNNING); put("claim", token) },
                    "id=?", arrayOf(row[0]))
                BlobOutgoingWork(row[0], body, row[2].toInt(), row[3].toInt(), token, row[5].toInt() == 0)
            }
            db.setTransactionSuccessful()
            return claimed
        } finally { db.endTransaction() }
    }

    @Synchronized fun defer(work: BlobOutgoingWork, due: Long, error: String = "", waiting: Boolean = false) {
        val activated = waiting && db.rawQuery("SELECT ready FROM jobs WHERE id=?", arrayOf(work.id))
            .use { it.moveToFirst() && it.getInt(0) == 1 }
        db.update("jobs", ContentValues().apply {
            put("state", if (waiting && !activated) WAITING else READY); put("claim", ""); put("due", if (activated) 0L else due)
            put("attempts", if (error.isBlank()) 0 else (work.attempts.toLong() + 1).coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
            put("error", error)
        }, "id=? AND claim=?", arrayOf(work.id, work.claim))
    }

    @Synchronized fun stored(id: String, payload: JSONObject, sourceId: String, fingerprint: String): Boolean =
        acceptReceipt(id, payload, sourceId, fingerprint, CLEANUP)

    @Synchronized fun failedReceipt(id: String, payload: JSONObject, sourceId: String, fingerprint: String): Boolean =
        acceptReceipt(id, payload, sourceId, fingerprint, FAILURE)

    private fun acceptReceipt(id: String, payload: JSONObject, sourceId: String, fingerprint: String, target: Int): Boolean {
        db.beginTransaction()
        try {
            val body = body(id) ?: return false
            val phase = db.rawQuery("SELECT phase FROM jobs WHERE id=?", arrayOf(id)).use {
                if (it.moveToFirst()) it.getInt(0) else DISCARD
            }
            if (phase != UPLOAD && phase != target && !(target == FAILURE && phase == FAILED_CLEANUP)) return false
            if (body.optString("desktop_id") != sourceId || body.optString("fingerprint") != fingerprint ||
                !(if (target == FAILURE) BlobFailureContract.matches(body.getJSONObject("manifest"), payload)
                  else BlobOutgoingContract.receiptMatches(body.getJSONObject("manifest"), payload))) return false
            body.put("receipt", payload)
            db.update("jobs", ContentValues().apply { put("body", encode(id, body)); put("phase", target)
                put("state", READY); put("claim", ""); put("due", 0) },
                "id=? AND phase=0 AND state<>?", arrayOf(id, DONE.toString()))
            db.setTransactionSuccessful()
            return true
        } finally { db.endTransaction() }
    }

    @Synchronized fun fail(work: BlobOutgoingWork, code: String): Boolean {
        val body = JSONObject(work.body.toString()).put("receipt",
            BlobFailureContract.receipt(work.body.getJSONObject("manifest"), code))
        return db.update("jobs", ContentValues().apply {
            put("body", encode(work.id, body)); put("phase", FAILURE); put("state", READY)
            put("claim", ""); put("due", 0); put("error", code)
        }, "id=? AND claim=? AND phase=?", arrayOf(work.id, work.claim, UPLOAD.toString())) == 1
    }

    @Synchronized fun failureObserved(work: BlobOutgoingWork): Boolean = db.update("jobs", ContentValues().apply {
        put("phase", FAILED_CLEANUP); put("state", READY); put("claim", ""); put("due", 0)
    }, "id=? AND claim=? AND phase=?", arrayOf(work.id, work.claim, FAILURE.toString())) == 1
    @Synchronized fun finish(work: BlobOutgoingWork) {
        db.update("jobs", ContentValues().apply { put("state", DONE); put("claim", ""); put("error", "") },
            "id=? AND claim=? AND phase IN (?,?,?)", arrayOf(work.id, work.claim,
                CLEANUP.toString(), DISCARD.toString(), FAILED_CLEANUP.toString()))
    }
    @Synchronized fun cancel(id: String) {
        db.update("jobs", ContentValues().apply { put("phase", DISCARD); put("state", READY); put("claim", ""); put("due", 0) },
            "id=? AND (state<>? OR phase=?)", arrayOf(id, DONE.toString(), FAILED_CLEANUP.toString()))
    }
    @Synchronized fun nextDue(): Long? = listOf(WAITING, READY).mapNotNull { state ->
        db.rawQuery("SELECT due FROM jobs WHERE state=? ORDER BY due,id LIMIT 1", arrayOf(state.toString()))
            .use { if (it.moveToFirst()) it.getLong(0) else null }
    }.minOrNull()
    @Synchronized override fun close() = db.close()
    companion object {
        const val UPLOAD = 0; const val CLEANUP = 1; const val DISCARD = 2
        const val FAILURE = 3; const val FAILED_CLEANUP = 4
        private const val WAITING = 0; private const val READY = 1; private const val RUNNING = 2; private const val DONE = 3
    }
}
