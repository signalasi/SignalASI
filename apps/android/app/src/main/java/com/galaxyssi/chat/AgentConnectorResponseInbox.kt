package com.galaxyssi.chat

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.io.Closeable
import org.json.JSONArray
import org.json.JSONObject

internal data class AgentConnectorInboxPage(
    val responses: List<AgentConnectorResponse>,
    val nextSequence: Long,
    val unreadableCount: Int = 0
)

/** Pending bodies have no count/age eviction. Acknowledgements retain only opaque identities. */
internal class AgentConnectorResponseInbox(
    private val context: Context,
    private val databaseName: String = DATABASE_NAME,
    private val legacyPreferences: String = LEGACY_PREFERENCES
) : Closeable {
    private val helper = InboxDatabase(context, databaseName)
    private var migrationChecked = false

    @Synchronized
    fun append(response: AgentConnectorResponse): Boolean {
        migrate()
        require(response.sourceMessageId > 0) { "Invalid connector response source identity" }
        require(response.content.isNotBlank() || response.richOutputJson.isNotBlank()) { "Empty connector response" }
        val database = helper.writableDatabase
        database.beginTransaction()
        return try {
            val inserted = insert(database, response)
            database.setTransactionSuccessful()
            inserted
        } finally { database.endTransaction() }
    }

    @Synchronized
    fun observeExecution(response: AgentConnectorResponse, finalReply: Boolean = false): Boolean {
        migrate()
        val database = helper.writableDatabase
        database.beginTransaction()
        return try {
            val accepted = observe(database, response, finalReply)
            database.setTransactionSuccessful()
            accepted
        } finally { database.endTransaction() }
    }

    @Synchronized
    fun isCurrentExecution(response: AgentConnectorResponse): Boolean {
        migrate()
        return (currentVersion(helper.readableDatabase, response)?.generation ?: 1L) <= response.executionGeneration
    }

    @Synchronized
    fun page(afterSequence: Long = 0, throughSequence: Long = Long.MAX_VALUE, limit: Int = PAGE_SIZE): AgentConnectorInboxPage {
        migrate()
        require(afterSequence >= 0 && throughSequence >= 0)
        val database = helper.readableDatabase
        val rows = database.rawQuery(
            "SELECT sequence, identity_key, length(encrypted_value) FROM inbox WHERE handled=0 AND sequence>? AND sequence<=? " +
                "ORDER BY sequence LIMIT ?",
            arrayOf(afterSequence.toString(), throughSequence.toString(), limit.coerceIn(1, 64).toString())
        ).use { cursor -> buildList {
            var encodedChars = 0L
            while (cursor.moveToNext()) {
                val size = cursor.getLong(2)
                if (isNotEmpty() && encodedChars + size > PAGE_ENCRYPTED_CHARS) break
                add(cursor.getLong(0) to cursor.getString(1))
                encodedChars += size
            }
        } }
        var unreadable = 0
        val responses = rows.mapNotNull { (_, key) ->
            runCatching {
                decodeBody(database, key)
            }.getOrElse { unreadable++; null }
        }
        return AgentConnectorInboxPage(responses, rows.lastOrNull()?.first ?: afterSequence, unreadable)
    }

    @Synchronized
    fun highWatermark(): Long {
        migrate()
        return helper.readableDatabase.rawQuery("SELECT COALESCE(MAX(sequence),0) FROM inbox WHERE handled=0", null)
            .use { it.moveToFirst(); it.getLong(0) }
    }

    @Synchronized
    fun contains(response: AgentConnectorResponse): Boolean {
        migrate()
        return isCurrentExecution(response) && exists("identity_key=? AND handled=0", arrayOf(AgentConnectorResponseCodec.identity(response)))
    }

    @Synchronized
    fun wasRecorded(response: AgentConnectorResponse): Boolean {
        migrate()
        return exists("identity_key=?", arrayOf(AgentConnectorResponseCodec.identity(response)))
    }

    @Synchronized
    fun find(response: AgentConnectorResponse): AgentConnectorResponse? {
        migrate()
        return if (isCurrentExecution(response)) decodeBody(helper.readableDatabase, AgentConnectorResponseCodec.identity(response)) else null
    }

    @Synchronized
    fun containsTurn(conversationId: String, turnId: String): Boolean {
        if (conversationId.isBlank() || turnId.isBlank()) return false
        migrate()
        return exists("turn_key=? AND handled=0", arrayOf(AgentConnectorResponseCodec.turnKey(conversationId, turnId)))
    }

    @Synchronized
    fun acknowledge(response: AgentConnectorResponse): Boolean {
        migrate()
        return acknowledgeWhere("identity_key=?", arrayOf(AgentConnectorResponseCodec.identity(response))) > 0
    }

    @Synchronized
    fun acknowledgeTurn(conversationId: String, turnId: String) {
        if (conversationId.isBlank() || turnId.isBlank()) return
        migrate()
        acknowledgeWhere("turn_key=?", arrayOf(AgentConnectorResponseCodec.turnKey(conversationId, turnId)))
    }

    @Synchronized
    fun acknowledgeThrough(response: AgentConnectorResponse) {
        migrate()
        val key = AgentConnectorResponseCodec.identity(response)
        acknowledgeWhere("turn_key=? AND sequence<=(SELECT sequence FROM inbox WHERE identity_key=?)",
            arrayOf(AgentConnectorResponseCodec.turnKey(response.conversationId, response.turnId), key))
    }

    @Synchronized
    fun clear() {
        val database = helper.writableDatabase
        database.beginTransaction()
        try {
            database.delete("inbox", null, null)
            database.delete("remote_executions", null, null)
            database.execSQL("INSERT OR IGNORE INTO inbox_metadata(name) VALUES ('legacy_migrated')")
            database.setTransactionSuccessful()
        } finally { database.endTransaction() }
        context.getSharedPreferences(legacyPreferences, Context.MODE_PRIVATE).edit().remove("responses").commit()
        AgentEncryptedPreferenceCache.clearNamespace(legacyPreferences)
        migrationChecked = true
    }

    @Synchronized
    override fun close() = helper.close()

    private fun exists(selection: String, args: Array<String>): Boolean = helper.readableDatabase.rawQuery(
        "SELECT 1 FROM inbox WHERE $selection LIMIT 1", args
    ).use { it.moveToFirst() }

    private fun acknowledgeWhere(selection: String, args: Array<String>): Int = helper.writableDatabase.update(
        "inbox", ContentValues().apply { put("handled", 1); putNull("encrypted_value") },
        "handled=0 AND ($selection)", args
    )

    private fun insert(database: SQLiteDatabase, response: AgentConnectorResponse): Boolean {
        if (!observe(database, response, finalReply = true)) return false
        val key = AgentConnectorResponseCodec.identity(response)
        if (database.rawQuery("SELECT 1 FROM inbox WHERE identity_key=? LIMIT 1", arrayOf(key)).use { it.moveToFirst() }) {
            return false
        }
        val values = ContentValues().apply {
            put("identity_key", key)
            put("turn_key", AgentConnectorResponseCodec.turnKey(response.conversationId, response.turnId))
            put("scope_key", AgentConnectorResponseCodec.scopeIdentity(response))
            put("execution_generation", response.executionGeneration)
            put("encrypted_value", AgentStorageCipher.encrypt(AgentConnectorResponseCodec.encode(response).toString(), aad(key)))
        }
        val inserted = database.insertWithOnConflict("inbox", null, values, SQLiteDatabase.CONFLICT_IGNORE) != -1L
        check(inserted || database.rawQuery("SELECT 1 FROM inbox WHERE identity_key=?", arrayOf(key)).use { it.moveToFirst() }) {
            "Connector response was not persisted"
        }
        return inserted
    }

    private fun currentVersion(database: SQLiteDatabase, response: AgentConnectorResponse): AgentRemoteExecutionVersion? =
        database.rawQuery("SELECT generation,status_sequence FROM remote_executions WHERE scope_key=?",
            arrayOf(AgentConnectorResponseCodec.scopeIdentity(response))).use { cursor ->
            if (cursor.moveToFirst()) AgentRemoteExecutionVersion(cursor.getLong(0), cursor.getLong(1)) else null
        }

    private fun observe(database: SQLiteDatabase, response: AgentConnectorResponse, finalReply: Boolean = false): Boolean {
        val candidate = response.executionVersion
        val current = currentVersion(database, response)
        if (current != null && (candidate.generation < current.generation || (!finalReply && !current.accepts(candidate)))) return false
        val next = current?.advance(candidate) ?: candidate
        if (next == current) return true
        val key = AgentConnectorResponseCodec.scopeIdentity(response)
        database.insertWithOnConflict("remote_executions", null, ContentValues().apply {
            put("scope_key", key); put("generation", next.generation); put("status_sequence", next.sequence)
        }, SQLiteDatabase.CONFLICT_REPLACE).also { check(it != -1L) { "Execution observation was not persisted" } }
        database.update("inbox", ContentValues().apply { put("handled", 1); putNull("encrypted_value") },
            "(scope_key=? OR identity_key=?) AND execution_generation<? AND handled=0",
            arrayOf(key, key, next.generation.toString()))
        return true
    }

    private fun migrate() {
        if (migrationChecked) return
        val database = helper.writableDatabase
        val preferences = context.getSharedPreferences(legacyPreferences, Context.MODE_PRIVATE)
        database.beginTransaction()
        try {
            val migrated = database.rawQuery("SELECT 1 FROM inbox_metadata WHERE name='legacy_migrated'", null)
                .use { it.moveToFirst() }
            if (!migrated) {
                val raw = preferences.getString("responses", null)
                if (raw != null) {
                    val plaintext = checkNotNull(AgentStorageCipher.decrypt(raw, "$legacyPreferences:responses".toByteArray(Charsets.UTF_8))) {
                        "Legacy connector response inbox is unreadable; migration was not committed"
                    }
                    val values = JSONArray(plaintext)
                    for (index in 0 until values.length()) {
                        insert(database, AgentConnectorResponseCodec.decode(values.getJSONObject(index)))
                    }
                }
                database.execSQL("INSERT INTO inbox_metadata(name) VALUES ('legacy_migrated')")
            }
            database.setTransactionSuccessful()
        } finally { database.endTransaction() }
        // The marker and rows commit together. An old preferences file cannot resurrect acknowledgements.
        if (preferences.contains("responses")) preferences.edit().remove("responses").commit()
        AgentEncryptedPreferenceCache.clearNamespace(legacyPreferences)
        migrationChecked = true
    }

    private fun readBody(database: SQLiteDatabase, key: String): String? {
        val length = database.rawQuery(
            "SELECT length(encrypted_value) FROM inbox WHERE identity_key=? AND handled=0", arrayOf(key)
        ).use { cursor -> if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getInt(0) else return null }
        val body = StringBuilder(length)
        var offset = 1
        while (offset <= length) {
            val chunk = database.rawQuery(
                "SELECT substr(encrypted_value,?,?) FROM inbox WHERE identity_key=? AND handled=0",
                arrayOf(offset.toString(), CURSOR_CHUNK.toString(), key)
            ).use { cursor -> if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getString(0) else return null }
            check(chunk.isNotEmpty()) { "Incomplete encrypted connector response" }
            body.append(chunk)
            offset += chunk.length
        }
        return body.toString()
    }

    private fun decodeBody(database: SQLiteDatabase, key: String): AgentConnectorResponse? {
        val encoded = readBody(database, key) ?: return null
        val plaintext = checkNotNull(AgentStorageCipher.decrypt(encoded, aad(key))) {
            "Connector response decryption failed"
        }
        return AgentConnectorResponseCodec.decode(JSONObject(plaintext)).also {
            check(AgentConnectorResponseCodec.identity(it) == key) { "Connector response identity mismatch" }
        }
    }

    private fun aad(key: String): ByteArray = "connector-inbox:$databaseName:$key".toByteArray(Charsets.UTF_8)

    private class InboxDatabase(context: Context, name: String) : SQLiteOpenHelper(context, name, null, 2) {
        init { setWriteAheadLoggingEnabled(true) }

        override fun onConfigure(db: SQLiteDatabase) {
            super.onConfigure(db)
            db.rawQuery("PRAGMA synchronous=FULL", null).use { it.moveToFirst() }
        }

        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL("CREATE TABLE inbox (sequence INTEGER PRIMARY KEY AUTOINCREMENT," +
                "identity_key TEXT NOT NULL UNIQUE,turn_key TEXT NOT NULL," +
                "handled INTEGER NOT NULL DEFAULT 0,encrypted_value TEXT," +
                "scope_key TEXT NOT NULL DEFAULT '',execution_generation INTEGER NOT NULL DEFAULT 1)")
            db.execSQL("CREATE INDEX inbox_pending ON inbox(handled,sequence)")
            db.execSQL("CREATE INDEX inbox_turn ON inbox(turn_key,handled)")
            db.execSQL("CREATE TABLE inbox_metadata (name TEXT PRIMARY KEY NOT NULL)")
            createExecutionTable(db)
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            if (oldVersion < 2) {
                db.execSQL("ALTER TABLE inbox ADD COLUMN scope_key TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE inbox ADD COLUMN execution_generation INTEGER NOT NULL DEFAULT 1")
                createExecutionTable(db)
            }
        }

        private fun createExecutionTable(db: SQLiteDatabase) {
            db.execSQL("CREATE INDEX inbox_execution ON inbox(scope_key,execution_generation,handled)")
            db.execSQL("CREATE TABLE remote_executions (scope_key TEXT PRIMARY KEY NOT NULL," +
                "generation INTEGER NOT NULL,status_sequence INTEGER NOT NULL)")
        }
    }

    companion object {
        const val PAGE_SIZE = 32
        const val DATABASE_NAME = "galaxyssi_connector_inbox.db"
        const val LEGACY_PREFERENCES = "galaxyssi_agent_connector_responses"
        private const val CURSOR_CHUNK = 256 * 1024
        private const val PAGE_ENCRYPTED_CHARS = 2 * 1024 * 1024
    }
}
