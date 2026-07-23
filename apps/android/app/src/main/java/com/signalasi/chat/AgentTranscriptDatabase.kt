package com.signalasi.chat

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONObject
import java.security.MessageDigest

internal data class AgentTranscriptPage(
    val entries: List<AgentTranscriptEntry>,
    val nextBeforeSequence: Long?,
    val hasMore: Boolean,
    val newestSequence: Long?
)

internal data class AgentTranscriptDelta(
    val entries: List<AgentTranscriptEntry>,
    val newestSequence: Long?,
    val hasMore: Boolean
)

internal class AgentTranscriptWindow {
    var conversationId: String = ""
        private set
    var entries: List<AgentTranscriptEntry> = emptyList()
        private set
    var nextBeforeSequence: Long? = null
        private set
    var newestSequence: Long? = null
        private set
    var hasMore: Boolean = false
        private set

    fun reset(conversationId: String = "") {
        this.conversationId = conversationId
        entries = emptyList()
        nextBeforeSequence = null
        newestSequence = null
        hasMore = false
    }

    fun replace(conversationId: String, page: AgentTranscriptPage) {
        this.conversationId = conversationId
        entries = page.entries.distinctBy(AgentTranscriptEntry::id)
        nextBeforeSequence = page.nextBeforeSequence
        newestSequence = page.newestSequence
        hasMore = page.hasMore
    }

    fun appendNewer(conversationId: String, delta: AgentTranscriptDelta): Int {
        if (this.conversationId != conversationId) {
            reset(conversationId)
        }
        if (delta.entries.isEmpty()) {
            newestSequence = delta.newestSequence ?: newestSequence
            return 0
        }
        val incomingIds = delta.entries.mapTo(mutableSetOf(), AgentTranscriptEntry::id)
        val incomingDedupeKeys = delta.entries.asSequence()
            .map(AgentTranscriptEntry::dedupeKey)
            .filter(String::isNotBlank)
            .toSet()
        val retained = entries.filterNot { entry ->
            entry.id in incomingIds ||
                (entry.dedupeKey.isNotBlank() && entry.dedupeKey in incomingDedupeKeys)
        }
        entries = retained + delta.entries
        newestSequence = delta.newestSequence ?: newestSequence
        return delta.entries.size
    }

    fun prependOlder(conversationId: String, page: AgentTranscriptPage): Int {
        if (this.conversationId != conversationId || entries.isEmpty()) {
            replace(conversationId, page)
            return entries.size
        }
        val loadedIds = entries.mapTo(mutableSetOf(), AgentTranscriptEntry::id)
        val older = page.entries.filterNot { it.id in loadedIds }
        entries = older + entries
        nextBeforeSequence = page.nextBeforeSequence
        newestSequence = newestSequence ?: page.newestSequence
        hasMore = page.hasMore
        return older.size
    }

    fun remove(entryId: String): Boolean {
        val retained = entries.filterNot { it.id == entryId }
        if (retained.size == entries.size) return false
        entries = retained
        return true
    }
}

internal class AgentTranscriptEntryDatabase(
    context: Context,
    databaseName: String = DATABASE_NAME
) : SQLiteOpenHelper(context.applicationContext, databaseName, null, DATABASE_VERSION) {
    init {
        setWriteAheadLoggingEnabled(true)
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE transcript_entries (
                sequence INTEGER PRIMARY KEY AUTOINCREMENT,
                entry_id TEXT UNIQUE NOT NULL,
                conversation_id TEXT NOT NULL,
                turn_id TEXT NOT NULL,
                task_id TEXT NOT NULL,
                dedupe_hash TEXT NOT NULL,
                timestamp_millis INTEGER NOT NULL,
                encrypted_payload TEXT NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE INDEX transcript_entries_conversation_order
            ON transcript_entries(conversation_id, timestamp_millis, sequence)
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE INDEX transcript_entries_turn
            ON transcript_entries(turn_id, sequence)
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE INDEX transcript_entries_task
            ON transcript_entries(task_id, sequence)
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE INDEX transcript_entries_dedupe
            ON transcript_entries(conversation_id, dedupe_hash)
            """.trimIndent()
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    @Synchronized
    fun insert(entry: AgentTranscriptEntry): Boolean =
        insertEntry(writableDatabase, entry) != -1L

    @Synchronized
    fun replace(previousEntryId: String, entry: AgentTranscriptEntry): Boolean {
        val db = writableDatabase
        db.beginTransaction()
        return try {
            db.delete(TABLE_ENTRIES, "entry_id = ?", arrayOf(previousEntryId))
            val inserted = insertEntry(db, entry) != -1L
            if (inserted) db.setTransactionSuccessful()
            inserted
        } finally {
            db.endTransaction()
        }
    }

    @Synchronized
    fun replaceAll(entries: List<AgentTranscriptEntry>) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.delete(TABLE_ENTRIES, null, null)
            entries.forEach { entry ->
                check(insertEntry(db, entry) != -1L) { "Agent transcript entry write failed" }
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    @Synchronized
    fun listAll(): List<AgentTranscriptEntry> =
        readableDatabase.query(
            TABLE_ENTRIES,
            PAYLOAD_COLUMNS,
            null,
            null,
            null,
            null,
            "timestamp_millis ASC, sequence ASC"
        ).use(::decodeEntries)

    @Synchronized
    fun listConversation(conversationId: String): List<AgentTranscriptEntry> =
        readableDatabase.query(
            TABLE_ENTRIES,
            PAYLOAD_COLUMNS,
            "conversation_id = ?",
            arrayOf(conversationId),
            null,
            null,
            "timestamp_millis ASC, sequence ASC"
        ).use(::decodeEntries)

    @Synchronized
    fun listConversationAfterEntry(
        conversationId: String,
        entryId: String
    ): List<AgentTranscriptEntry>? {
        val cleanConversationId = conversationId.trim()
        val cleanEntryId = entryId.trim()
        if (cleanConversationId.isBlank()) return emptyList()
        if (cleanEntryId.isBlank()) return null
        val cursorSequence = readableDatabase.query(
            TABLE_ENTRIES,
            arrayOf("sequence"),
            "conversation_id = ? AND entry_id = ?",
            arrayOf(cleanConversationId, cleanEntryId),
            null,
            null,
            null,
            "1"
        ).use { cursor ->
            if (cursor.moveToFirst()) cursor.getLong(0) else null
        } ?: return null
        return readableDatabase.query(
            TABLE_ENTRIES,
            PAYLOAD_COLUMNS,
            "conversation_id = ? AND sequence > ?",
            arrayOf(cleanConversationId, cursorSequence.toString()),
            null,
            null,
            "sequence ASC"
        ).use(::decodeEntries)
    }

    @Synchronized
    fun listConversationPage(
        conversationId: String,
        beforeSequenceExclusive: Long? = null,
        pageSize: Int = DEFAULT_PAGE_SIZE
    ): AgentTranscriptPage {
        val safePageSize = pageSize.coerceIn(1, MAX_PAGE_SIZE)
        val selection = buildString {
            append("conversation_id = ?")
            if (beforeSequenceExclusive != null) append(" AND sequence < ?")
        }
        val arguments = buildList {
            add(conversationId)
            beforeSequenceExclusive?.let { add(it.toString()) }
        }.toTypedArray()
        val rows = readableDatabase.query(
            TABLE_ENTRIES,
            PAGE_COLUMNS,
            selection,
            arguments,
            null,
            null,
            "sequence DESC",
            (safePageSize + 1).toString()
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add(cursor.getLong(cursor.getColumnIndexOrThrow("sequence")) to decodeEntry(cursor))
                }
            }
        }
        val hasMore = rows.size > safePageSize
        val retained = rows.take(safePageSize)
        return AgentTranscriptPage(
            entries = retained.asReversed().map { it.second },
            nextBeforeSequence = retained.lastOrNull()?.first?.takeIf { hasMore },
            hasMore = hasMore,
            newestSequence = retained.firstOrNull()?.first
        )
    }

    @Synchronized
    fun listConversationAfter(
        conversationId: String,
        afterSequenceExclusive: Long,
        pageSize: Int = DEFAULT_PAGE_SIZE
    ): AgentTranscriptDelta {
        val safePageSize = pageSize.coerceIn(1, MAX_PAGE_SIZE)
        val rows = readableDatabase.query(
            TABLE_ENTRIES,
            PAGE_COLUMNS,
            "conversation_id = ? AND sequence > ?",
            arrayOf(conversationId, afterSequenceExclusive.toString()),
            null,
            null,
            "sequence ASC",
            (safePageSize + 1).toString()
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add(cursor.getLong(cursor.getColumnIndexOrThrow("sequence")) to decodeEntry(cursor))
                }
            }
        }
        val hasMore = rows.size > safePageSize
        val retained = rows.take(safePageSize)
        return AgentTranscriptDelta(
            entries = retained.map { it.second },
            newestSequence = retained.lastOrNull()?.first ?: afterSequenceExclusive,
            hasMore = hasMore
        )
    }

    @Synchronized
    fun listTurn(turnId: String): List<AgentTranscriptEntry> =
        readableDatabase.query(
            TABLE_ENTRIES,
            PAYLOAD_COLUMNS,
            "turn_id = ?",
            arrayOf(turnId),
            null,
            null,
            "sequence ASC"
        ).use(::decodeEntries)

    @Synchronized
    fun listTask(taskId: String): List<AgentTranscriptEntry> =
        readableDatabase.query(
            TABLE_ENTRIES,
            PAYLOAD_COLUMNS,
            "task_id = ?",
            arrayOf(taskId),
            null,
            null,
            "sequence ASC"
        ).use(::decodeEntries)

    @Synchronized
    fun findById(entryId: String): AgentTranscriptEntry? =
        querySingle("entry_id = ?", arrayOf(entryId))

    @Synchronized
    fun findByDedupeKey(conversationId: String, dedupeKey: String): AgentTranscriptEntry? {
        if (dedupeKey.isBlank()) return null
        return querySingle(
            "conversation_id = ? AND dedupe_hash = ?",
            arrayOf(conversationId, digest(dedupeKey))
        )
    }

    @Synchronized
    fun conversationIdForTurn(turnId: String): String? =
        scalarId("turn_id = ?", arrayOf(turnId), "conversation_id")

    @Synchronized
    fun conversationIdForTask(taskId: String): String? =
        scalarId("task_id = ?", arrayOf(taskId), "conversation_id")

    @Synchronized
    fun turnIdForTask(taskId: String): String? =
        scalarId("task_id = ? AND turn_id != ''", arrayOf(taskId), "turn_id")

    @Synchronized
    fun conversationIdsWithEntries(): Set<String> =
        readableDatabase.query(
            true,
            TABLE_ENTRIES,
            arrayOf("conversation_id"),
            null,
            null,
            null,
            null,
            null,
            null
        ).use { cursor ->
            buildSet {
                while (cursor.moveToNext()) add(cursor.getString(0))
            }
        }

    @Synchronized
    fun deleteById(entryId: String): Boolean =
        writableDatabase.delete(TABLE_ENTRIES, "entry_id = ?", arrayOf(entryId)) > 0

    @Synchronized
    fun deleteEntries(entryIds: Collection<String>): Int {
        if (entryIds.isEmpty()) return 0
        val db = writableDatabase
        db.beginTransaction()
        return try {
            var removed = 0
            entryIds.forEach { entryId ->
                removed += db.delete(TABLE_ENTRIES, "entry_id = ?", arrayOf(entryId))
            }
            db.setTransactionSuccessful()
            removed
        } finally {
            db.endTransaction()
        }
    }

    @Synchronized
    fun deleteConversation(conversationId: String): Int =
        writableDatabase.delete(TABLE_ENTRIES, "conversation_id = ?", arrayOf(conversationId))

    @Synchronized
    fun clear() {
        writableDatabase.delete(TABLE_ENTRIES, null, null)
    }

    private fun querySingle(selection: String, arguments: Array<String>): AgentTranscriptEntry? =
        readableDatabase.query(
            TABLE_ENTRIES,
            PAYLOAD_COLUMNS,
            selection,
            arguments,
            null,
            null,
            "sequence DESC",
            "1"
        ).use { cursor ->
            if (cursor.moveToFirst()) decodeEntry(cursor) else null
        }

    private fun scalarId(selection: String, arguments: Array<String>, column: String): String? =
        readableDatabase.query(
            TABLE_ENTRIES,
            arrayOf(column),
            selection,
            arguments,
            null,
            null,
            "sequence DESC",
            "1"
        ).use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0).takeIf(String::isNotBlank) else null
        }

    private fun insertEntry(db: SQLiteDatabase, entry: AgentTranscriptEntry): Long {
        val values = ContentValues().apply {
            put("entry_id", entry.id)
            put("conversation_id", entry.conversationId)
            put("turn_id", entry.turnId)
            put("task_id", entry.taskId)
            put("dedupe_hash", entry.dedupeKey.takeIf(String::isNotBlank)?.let(::digest).orEmpty())
            put("timestamp_millis", entry.timestampMillis)
            put("encrypted_payload", encode(entry))
        }
        return db.insertWithOnConflict(TABLE_ENTRIES, null, values, SQLiteDatabase.CONFLICT_ABORT)
    }

    private fun decodeEntries(cursor: Cursor): List<AgentTranscriptEntry> = buildList {
        while (cursor.moveToNext()) add(decodeEntry(cursor))
    }

    private fun decodeEntry(cursor: Cursor): AgentTranscriptEntry {
        val entryId = cursor.getString(cursor.getColumnIndexOrThrow("entry_id"))
        val encrypted = cursor.getString(cursor.getColumnIndexOrThrow("encrypted_payload"))
        val raw = AgentStorageCipher.decrypt(encrypted, associatedData(entryId))
            ?: error("Agent transcript entry could not be decrypted")
        val item = JSONObject(raw)
        return AgentTranscriptEntry(
            id = item.optString("id", entryId),
            role = runCatching { AgentTranscriptRole.valueOf(item.optString("role")) }
                .getOrDefault(AgentTranscriptRole.ASSISTANT),
            text = item.optString("text"),
            timestampMillis = item.optLong("timestamp_millis"),
            dedupeKey = item.optString("dedupe_key"),
            conversationId = item.optString("conversation_id"),
            turnId = item.optString("turn_id"),
            taskId = item.optString("task_id"),
            richOutputJson = AgentRichContentCodec.normalize(item.optString("rich_output")),
            sourceConversationId = item.optString("source_conversation_id"),
            sourceConversationTitle = item.optString("source_conversation_title"),
            sourceEntryId = item.optString("source_entry_id")
        )
    }

    private fun encode(entry: AgentTranscriptEntry): String {
        val raw = JSONObject()
            .put("id", entry.id)
            .put("role", entry.role.name)
            .put("text", entry.text)
            .put("timestamp_millis", entry.timestampMillis)
            .put("dedupe_key", entry.dedupeKey)
            .put("conversation_id", entry.conversationId)
            .put("turn_id", entry.turnId)
            .put("task_id", entry.taskId)
            .put("rich_output", entry.richOutputJson)
            .put("source_conversation_id", entry.sourceConversationId)
            .put("source_conversation_title", entry.sourceConversationTitle)
            .put("source_entry_id", entry.sourceEntryId)
            .toString()
        return AgentStorageCipher.encrypt(raw, associatedData(entry.id))
    }

    private fun associatedData(entryId: String): ByteArray =
        "agent-transcript-entry:$entryId".toByteArray(Charsets.UTF_8)

    private fun digest(value: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }

    companion object {
        private const val DATABASE_NAME = "signalasi_agent_transcript_entries.db"
        private const val DATABASE_VERSION = 1
        private const val TABLE_ENTRIES = "transcript_entries"
        private const val DEFAULT_PAGE_SIZE = 100
        private const val MAX_PAGE_SIZE = 500
        private val PAYLOAD_COLUMNS = arrayOf("entry_id", "encrypted_payload")
        private val PAGE_COLUMNS = arrayOf("sequence", "entry_id", "encrypted_payload")
    }
}
