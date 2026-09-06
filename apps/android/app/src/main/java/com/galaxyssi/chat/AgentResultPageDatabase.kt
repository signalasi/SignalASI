package com.galaxyssi.chat

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.io.Closeable
import java.util.Base64
import org.json.JSONArray
import org.json.JSONObject

/** Only encrypted, page-sized values enter SQLite cursors; no plaintext files or cache. */
internal class AgentResultPageDatabase(context: Context, private val databaseName: String = DATABASE_NAME) : Closeable {
    private val helper = Database(context.applicationContext, databaseName)

    fun checkpoint(desktop: String, fields: JSONObject): AgentResultPageCheckpoint {
        val identity = AgentResultRecoveryClient.identity(fields)
        val generation = requireNotNull(AgentRemoteOutcomeCodec.version(fields)).generation
        require(desktop.isNotBlank() && identity.all { it.isNotBlank() && it.length <= 200 })
        val keyBytes = JSONArray(listOf(desktop, generation.toString()) + identity).toString().toByteArray(Charsets.UTF_8)
        val key = try { AgentResultRecoveryClient.sha256(keyBytes) } finally { keyBytes.fill(0) }
        return Checkpoint(key)
    }

    private inner class Checkpoint(private val key: String) : AgentResultPageCheckpoint {
        private fun aad(suffix: String) = "$databaseName|result-page|$key|$suffix".toByteArray(Charsets.UTF_8)
        private fun storedManifest(database: SQLiteDatabase): String? = database.rawQuery(
            "SELECT encrypted_manifest FROM manifests WHERE scope_key=?", arrayOf(key)
        ).use { if (it.moveToFirst()) it.getString(0) else null }

        private fun decodeManifest(encoded: String?): AgentResultPageManifest? = encoded?.let { value ->
            runCatching { AgentStorageCipher.decrypt(value, aad("manifest"))?.let { AgentResultPageManifest.from(JSONObject(it)) } }
                .getOrNull()
        }

        override fun manifest(): AgentResultPageManifest? = decodeManifest(storedManifest(helper.readableDatabase))

        override fun read(manifest: AgentResultPageManifest, page: Int): ByteArray? {
            require(manifest.valid() && page in 0 until manifest.pages)
            val database = helper.readableDatabase
            val stored = database.rawQuery("SELECT p.encrypted_page,m.encrypted_manifest FROM pages p " +
                "JOIN manifests m ON m.scope_key=p.scope_key WHERE p.scope_key=? AND p.page_index=?",
                arrayOf(key, page.toString())).use { if (it.moveToFirst()) it.getString(0) to it.getString(1) else null }
                ?: return null
            if (decodeManifest(stored.second) != manifest) return null
            val encrypted = stored.first
            val decoded = runCatching {
                val value = JSONObject(requireNotNull(AgentStorageCipher.decrypt(encrypted, aad("${manifest.digest}|$page"))))
                val encoded = value.getString("data_b64")
                require(encoded.length <= ((AgentResultRecoveryClient.PAGE_BYTES + 2) / 3) * 4)
                val digest = value.getString("sha256")
                val bytes = Base64.getDecoder().decode(encoded)
                if (bytes.size == manifest.pageBytes(page) && AgentResultRecoveryClient.sha256(bytes) == digest) bytes
                else { bytes.fill(0); null }
            }.getOrNull()
            if (decoded == null) helper.writableDatabase.delete("pages",
                "scope_key=? AND page_index=? AND encrypted_page=? AND EXISTS " +
                    "(SELECT 1 FROM manifests WHERE scope_key=? AND encrypted_manifest=?)",
                arrayOf(key, page.toString(), encrypted, key, stored.second))
            return decoded
        }

        override fun write(manifest: AgentResultPageManifest, page: Int, bytes: ByteArray): Boolean {
            require(manifest.valid() && page in 0 until manifest.pages && bytes.size == manifest.pageBytes(page))
            val value = JSONObject().put("data_b64", Base64.getEncoder().encodeToString(bytes))
                .put("sha256", AgentResultRecoveryClient.sha256(bytes))
            val encrypted = AgentStorageCipher.encrypt(value.toString(), aad("${manifest.digest}|$page"))
            val database = helper.writableDatabase
            database.beginTransaction()
            return try {
                val previous = storedManifest(database)
                val decoded = decodeManifest(previous)
                if (decoded != null && decoded != manifest) return false
                if (decoded == null) {
                    // Only the caller's authenticated and hash-verified page can
                    // replace an unreadable manifest; never mix two reply bodies.
                    database.delete("manifests", "scope_key=?", arrayOf(key))
                    database.insertOrThrow("manifests", null, ContentValues().apply {
                        put("scope_key", key)
                        put("encrypted_manifest", AgentStorageCipher.encrypt(manifest.json().toString(), aad("manifest")))
                    })
                }
                database.insertWithOnConflict("pages", null, ContentValues().apply {
                    put("scope_key", key); put("page_index", page); put("encrypted_page", encrypted)
                }, SQLiteDatabase.CONFLICT_REPLACE).also { check(it != -1L) { "Reply page was not persisted" } }
                database.setTransactionSuccessful()
                true
            } finally { database.endTransaction() }
        }

        override fun clear(digest: String) {
            val database = helper.writableDatabase
            database.beginTransaction()
            try {
                if (decodeManifest(storedManifest(database))?.digest == digest) {
                    database.delete("manifests", "scope_key=?", arrayOf(key))
                }
                database.setTransactionSuccessful()
            } finally { database.endTransaction() }
        }
    }

    override fun close() = helper.close()

    private class Database(context: Context, name: String) : SQLiteOpenHelper(context, name, null, 1) {
        init { setWriteAheadLoggingEnabled(true) }
        override fun onConfigure(db: SQLiteDatabase) {
            super.onConfigure(db)
            db.setForeignKeyConstraintsEnabled(true)
            db.rawQuery("PRAGMA synchronous=FULL", null).use { it.moveToFirst() }
        }
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL("CREATE TABLE manifests(scope_key TEXT PRIMARY KEY, encrypted_manifest TEXT NOT NULL)")
            db.execSQL("CREATE TABLE pages(scope_key TEXT NOT NULL REFERENCES manifests(scope_key) ON DELETE CASCADE, " +
                "page_index INTEGER NOT NULL, encrypted_page TEXT NOT NULL, PRIMARY KEY(scope_key,page_index))")
        }
        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
    }

    companion object { const val DATABASE_NAME = "agent_result_pages.db" }
}
