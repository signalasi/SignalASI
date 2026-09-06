package com.galaxyssi.chat.blob

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import com.galaxyssi.chat.AgentStorageCipher
import org.json.JSONObject
import java.io.Closeable
import java.io.File
import java.util.UUID

internal data class BlobArtifactReceiveWork(val id: String, val body: JSONObject, val phase: Int,
    val claim: String, val attempts: Int, val error: String) {
    override fun toString() = "BlobArtifactReceiveWork(phase=$phase, attempts=$attempts)"
}
internal data class BlobArtifactOfferCommit(val id: String, val revision: Long)

/** Persist an authenticated offer before acknowledging transport delivery. No file bytes enter SQLite. */
internal class BlobArtifactReceiveJournal(path: File) : Closeable {
    val directory: File = path.canonicalFile.parentFile!!
    private val db: SQLiteDatabase
    init {
        check(path.parentFile!!.mkdirs() || path.parentFile!!.isDirectory)
        db = SQLiteDatabase.openOrCreateDatabase(path, null)
        try {
            db.execSQL("PRAGMA synchronous=FULL")
            db.execSQL("CREATE TABLE IF NOT EXISTS artifact_receives(id TEXT PRIMARY KEY,digest TEXT NOT NULL," +
                "identity TEXT NOT NULL,revision INTEGER NOT NULL,body TEXT NOT NULL,phase INTEGER NOT NULL DEFAULT 0,state INTEGER NOT NULL DEFAULT 0," +
                "due INTEGER NOT NULL DEFAULT 0,claim TEXT NOT NULL DEFAULT '',attempts INTEGER NOT NULL DEFAULT 0," +
                "error TEXT NOT NULL DEFAULT '')")
            db.execSQL("CREATE INDEX IF NOT EXISTS artifact_receives_due ON artifact_receives(state,due,id)")
        } catch (error: Exception) { db.close(); throw error }
    }

    private fun aad(id: String) = "blob-artifact-receive-v1:$id".toByteArray(Charsets.UTF_8)
    private fun encode(id: String, body: JSONObject) = AgentStorageCipher.encrypt(body.toString(), aad(id))
    private fun decode(id: String, encoded: String): JSONObject {
        val body = JSONObject(AgentStorageCipher.decrypt(encoded, aad(id))
            ?: BlobProtocol.fail("artifact_blob_checkpoint_invalid"))
        return BlobArtifactReceiveJob.validate(body).also {
            if (it.getJSONObject("offer").getJSONObject("manifest").getString("transfer_id") != id) {
                BlobProtocol.fail("artifact_blob_checkpoint_invalid")
            }
        }
    }

    fun enqueue(body: JSONObject): String = enqueueCommitted(body).id

    @Synchronized fun enqueueCommitted(body: JSONObject): BlobArtifactOfferCommit {
        val value = BlobArtifactReceiveJob.validate(body)
        val id = value.getJSONObject("offer").getJSONObject("manifest").getString("transfer_id")
        val digest = BlobProtocol.hash(BlobProtocol.canonical(value))
        val identity = BlobArtifactReceiveJob.identity(value)
        val revision = BlobArtifactReceiveJob.revision(value)
        db.beginTransaction()
        try {
            val previous = db.rawQuery("SELECT digest,identity,revision,state,phase FROM artifact_receives WHERE id=?", arrayOf(id)).use {
                if (it.moveToFirst()) Existing(it.getString(0), it.getString(1), it.getLong(2), it.getInt(3), it.getInt(4)) else null
            }
            if (previous != null) {
                if (previous.identity != identity) BlobProtocol.fail("artifact_blob_offer_conflict")
                if (revision == previous.revision && previous.digest != digest) BlobProtocol.fail("artifact_blob_offer_conflict")
                if (revision > previous.revision && previous.phase != DISCARD && previous.state != QUARANTINED) {
                    db.update("artifact_receives", ContentValues().apply {
                        put("body", encode(id, value)); put("digest", digest); put("revision", revision)
                        put("phase", DOWNLOAD); put("state", READY); put("due", 0); put("attempts", 0)
                        put("claim", ""); put("error", "")
                    }, "id=?", arrayOf(id))
                }
                // Recheck a completed local file before replaying a lost receipt.
                // Cancelled or terminal-failed jobs cannot be revived by a duplicate.
                if (revision == previous.revision) db.update("artifact_receives", ContentValues().apply {
                    put("phase", DOWNLOAD); put("state", READY); put("due", 0); put("attempts", 0)
                }, "id=? AND state=? AND phase=?", arrayOf(id, DONE.toString(), CLEANUP.toString()))
            } else {
                db.insertOrThrow("artifact_receives", null, ContentValues().apply {
                    put("id", id); put("digest", digest); put("identity", identity); put("revision", revision)
                    put("body", encode(id, value))
                })
            }
            db.setTransactionSuccessful()
            val acceptedRevision = if (previous == null || (revision > previous.revision &&
                    previous.phase != DISCARD && previous.state != QUARANTINED)) revision else previous.revision
            return BlobArtifactOfferCommit(id, acceptedRevision)
        } finally { db.endTransaction() }
    }

    /** Only the coordinator holding the process owner lock may recover abandoned claims. */
    @Synchronized fun recover() {
        db.execSQL("UPDATE artifact_receives SET state=0,claim='',due=0 WHERE state=1")
    }

    @Synchronized fun claimDue(now: Long, capacity: Int, occupied: Set<String> = emptySet()): List<BlobArtifactReceiveWork> {
        if (capacity !in 1..64) return emptyList()
        require(occupied.size <= 64)
        occupied.forEach { BlobProtocol.unhex(it, 32).fill(0) }
        val exclusion = if (occupied.isEmpty()) "" else " AND id NOT IN (${occupied.joinToString(",") { "?" }})"
        val args = listOf(READY.toString(), now.toString()) + occupied + capacity.toString()
        db.beginTransaction()
        try {
            val result = mutableListOf<BlobArtifactReceiveWork>()
            db.rawQuery("SELECT id,body,phase,attempts,error,revision,identity FROM artifact_receives WHERE state=? AND due<=?" +
                exclusion + " ORDER BY due,id LIMIT ?", args.toTypedArray()).use { cursor ->
                while (cursor.moveToNext()) {
                    val id = cursor.getString(0)
                    val body = try { decode(id, cursor.getString(1)) } catch (_: Exception) { null }
                    if (body == null || cursor.getInt(2) !in DOWNLOAD..DISCARD || cursor.getInt(3) < 0 ||
                        BlobArtifactReceiveJob.revision(body) != cursor.getLong(5) ||
                        BlobArtifactReceiveJob.identity(body) != cursor.getString(6)) {
                        db.update("artifact_receives", ContentValues().apply {
                            put("state", QUARANTINED); put("error", "artifact_blob_checkpoint_invalid")
                        }, "id=?", arrayOf(id))
                        continue
                    }
                    val claim = UUID.randomUUID().toString()
                    db.update("artifact_receives", ContentValues().apply { put("state", RUNNING); put("claim", claim) },
                        "id=?", arrayOf(id))
                    result += BlobArtifactReceiveWork(id, body, cursor.getInt(2), claim, cursor.getInt(3), cursor.getString(4))
                }
            }
            db.setTransactionSuccessful()
            return result
        } finally { db.endTransaction() }
    }

    @Synchronized fun current(work: BlobArtifactReceiveWork): Boolean = db.rawQuery(
        "SELECT 1 FROM artifact_receives WHERE id=? AND claim=? AND phase=? AND state=?", condition(work))
        .use { it.moveToFirst() }

    @Synchronized fun advance(work: BlobArtifactReceiveWork): Boolean {
        val next = when (work.phase) {
            DOWNLOAD -> PUBLISH
            PUBLISH -> RECEIPT
            RECEIPT -> CLEANUP
            else -> return false
        }
        return update(work, ContentValues().apply {
            put("phase", next); put("state", READY); put("claim", ""); put("due", 0)
            put("attempts", 0); put("error", "")
        })
    }

    @Synchronized fun defer(work: BlobArtifactReceiveWork, due: Long, error: String): Boolean = update(work,
        ContentValues().apply {
            put("state", READY); put("claim", ""); put("due", due.coerceAtLeast(0))
            put("attempts", (work.attempts.toLong() + 1).coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
            put("error", BlobArtifactReceiveJob.errorCode(error))
        })

    @Synchronized fun fail(work: BlobArtifactReceiveWork, code: String): Boolean {
        if (work.phase !in setOf(DOWNLOAD, PUBLISH, RECEIPT)) return false
        return update(work, ContentValues().apply {
            put("phase", OBSERVE_FAILURE); put("state", READY); put("claim", ""); put("due", 0)
            put("attempts", 0); put("error", BlobArtifactReceiveJob.errorCode(code))
        })
    }

    @Synchronized fun redownload(work: BlobArtifactReceiveWork): Boolean {
        if (work.phase !in setOf(PUBLISH, RECEIPT)) return false
        return update(work, ContentValues().apply {
            put("phase", DOWNLOAD); put("state", READY); put("claim", ""); put("due", 0)
            put("error", "artifact_blob_local_copy_missing")
        })
    }

    @Synchronized fun finish(work: BlobArtifactReceiveWork): Boolean {
        if (work.phase !in setOf(CLEANUP, DISCARD, OBSERVE_FAILURE)) return false
        return update(work, ContentValues().apply { put("state", DONE); put("claim", ""); put("due", 0) })
    }

    @Synchronized fun cancel(id: String) {
        BlobProtocol.unhex(id, 32).fill(0)
        db.update("artifact_receives", ContentValues().apply {
            put("phase", DISCARD); put("state", READY); put("claim", ""); put("due", 0)
        }, "id=? AND state<>? AND phase<>?", arrayOf(id, QUARANTINED.toString(), DISCARD.toString()))
    }

    @Synchronized fun nextDue(): Long? = db.rawQuery(
        "SELECT due FROM artifact_receives WHERE state=? ORDER BY due,id LIMIT 1", arrayOf(READY.toString()))
        .use { if (it.moveToFirst()) it.getLong(0) else null }

    private fun condition(work: BlobArtifactReceiveWork) =
        arrayOf(work.id, work.claim, work.phase.toString(), RUNNING.toString())
    private fun update(work: BlobArtifactReceiveWork, values: ContentValues): Boolean = db.update(
        "artifact_receives", values, "id=? AND claim=? AND phase=? AND state=?", condition(work)) == 1

    @Synchronized override fun close() = db.close()
    private data class Existing(val digest: String, val identity: String, val revision: Long, val state: Int, val phase: Int)
    companion object {
        const val DOWNLOAD = 0; const val PUBLISH = 1; const val RECEIPT = 2; const val CLEANUP = 3
        const val OBSERVE_FAILURE = 4; const val DISCARD = 5
        private const val READY = 0; private const val RUNNING = 1; private const val DONE = 2; private const val QUARANTINED = 3
    }
}

internal object BlobArtifactReceiveJob {
    fun revision(body: JSONObject): Long = BlobProtocol.integer(body.getJSONObject("offer"),
        "transport_revision", 1, 9_007_199_254_740_991L)
    fun identity(body: JSONObject): String = BlobProtocol.hash(BlobProtocol.canonical(
        JSONObject(body.toString()).put("offer", body.getJSONObject("offer").getJSONObject("manifest"))))
    fun validate(body: JSONObject): JSONObject {
        BlobProtocol.keys(body, setOf("offer", "route_id", "desktop_id", "origin", "peer_fingerprint", "local_fingerprint"))
        listOf("peer_fingerprint", "local_fingerprint").forEach {
            BlobProtocol.unhex(BlobProtocol.string(body, it), 32).fill(0)
        }
        val origin = BlobHttp.normalizeOrigin(BlobProtocol.string(body, "origin"))
        val offer = BlobArtifactContract.validateOffer(body.getJSONObject("offer"),
            BlobProtocol.string(body, "route_id"), BlobProtocol.string(body, "desktop_id"), origin)
        return JSONObject(body.toString()).put("offer", offer).put("origin", origin)
    }
    fun errorCode(value: String) = value.takeIf { it.matches(Regex("[a-z][a-z0-9_]{0,95}")) }
        ?: "artifact_blob_receive_failed"
}
