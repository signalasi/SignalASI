package com.signalasi.chat

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

internal data class ChatHistoryPage(
    val messages: List<JSONObject>,
    val nextBeforeSequence: Long?,
    val hasMore: Boolean
)

internal class ChatHistoryDatabase(
    context: Context,
    databaseName: String = DATABASE_NAME
) : SQLiteOpenHelper(context.applicationContext, databaseName, null, DATABASE_VERSION) {
    init {
        setWriteAheadLoggingEnabled(true)
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE chat_messages (
                sequence INTEGER PRIMARY KEY AUTOINCREMENT,
                message_id INTEGER UNIQUE NOT NULL,
                contact_id TEXT NOT NULL,
                timestamp_millis INTEGER NOT NULL,
                is_mine INTEGER NOT NULL,
                task_id_hash TEXT NOT NULL,
                remote_message_id_hash TEXT NOT NULL,
                payload_hash TEXT NOT NULL,
                encrypted_payload TEXT NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE INDEX chat_messages_contact_order
            ON chat_messages(contact_id, timestamp_millis, sequence)
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE INDEX chat_messages_remote_id
            ON chat_messages(contact_id, remote_message_id_hash)
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE INDEX chat_messages_task
            ON chat_messages(contact_id, task_id_hash)
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE chat_metadata (
                metadata_key TEXT PRIMARY KEY NOT NULL,
                metadata_value INTEGER NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE deleted_chat_messages (
                message_id INTEGER PRIMARY KEY NOT NULL
            )
            """.trimIndent()
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    @Synchronized
    fun reserveMessageId(): Long {
        val db = writableDatabase
        db.beginTransaction()
        return try {
            val storedNext = metadataValue(db, KEY_NEXT_MESSAGE_ID)
            val nextId = storedNext.takeIf { it > 0L } ?: queryMaximumMessageId(db) + 1L
            writeMetadata(db, KEY_NEXT_MESSAGE_ID, nextId + 1L)
            db.setTransactionSuccessful()
            nextId
        } finally {
            db.endTransaction()
        }
    }

    @Synchronized
    fun upsert(message: JSONObject): Boolean {
        val db = writableDatabase
        db.beginTransaction()
        return try {
            val changed = insertOrMerge(db, message)
            if (changed) incrementVersion(db)
            db.setTransactionSuccessful()
            changed
        } finally {
            db.endTransaction()
        }
    }

    @Synchronized
    fun mergeSnapshot(root: JSONObject): Boolean {
        val db = writableDatabase
        db.beginTransaction()
        return try {
            var changed = false
            val keys = root.keys()
            while (keys.hasNext()) {
                val contactId = keys.next()
                val messages = root.optJSONArray(contactId) ?: continue
                for (index in 0 until messages.length()) {
                    val message = messages.optJSONObject(index) ?: continue
                    if (message.optString("contactId").isBlank()) message.put("contactId", contactId)
                    changed = insertOrMerge(db, message) || changed
                }
            }
            if (changed) incrementVersion(db)
            db.setTransactionSuccessful()
            changed
        } finally {
            db.endTransaction()
        }
    }

    @Synchronized
    fun replaceAll(root: JSONObject) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.delete(TABLE_MESSAGES, null, null)
            db.delete(TABLE_TOMBSTONES, null, null)
            writeMetadata(db, KEY_NEXT_MESSAGE_ID, 1L)
            val keys = root.keys()
            while (keys.hasNext()) {
                val contactId = keys.next()
                val messages = root.optJSONArray(contactId) ?: continue
                for (index in 0 until messages.length()) {
                    val message = messages.optJSONObject(index) ?: continue
                    if (message.optString("contactId").isBlank()) message.put("contactId", contactId)
                    insertOrMerge(db, message)
                }
            }
            incrementVersion(db)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    @Synchronized
    fun readAll(): JSONObject {
        val root = JSONObject()
        readableDatabase.query(
            TABLE_MESSAGES,
            PAYLOAD_COLUMNS,
            null,
            null,
            null,
            null,
            "timestamp_millis ASC, sequence ASC"
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val message = decodeMessage(cursor)
                val contactId = message.optString("contactId")
                if (contactId.isBlank()) continue
                val array = root.optJSONArray(contactId) ?: JSONArray().also { root.put(contactId, it) }
                array.put(message)
            }
        }
        return root
    }

    @Synchronized
    fun readContact(contactId: String): JSONArray {
        val messages = JSONArray()
        readableDatabase.query(
            TABLE_MESSAGES,
            PAYLOAD_COLUMNS,
            "contact_id = ?",
            arrayOf(contactId),
            null,
            null,
            "timestamp_millis ASC, sequence ASC"
        ).use { cursor ->
            while (cursor.moveToNext()) messages.put(decodeMessage(cursor))
        }
        return messages
    }

    @Synchronized
    fun page(
        contactId: String,
        beforeSequenceExclusive: Long? = null,
        pageSize: Int = DEFAULT_PAGE_SIZE
    ): ChatHistoryPage {
        val safePageSize = pageSize.coerceIn(1, MAX_PAGE_SIZE)
        val selection = buildString {
            append("contact_id = ?")
            if (beforeSequenceExclusive != null) append(" AND sequence < ?")
        }
        val arguments = buildList {
            add(contactId)
            beforeSequenceExclusive?.let { add(it.toString()) }
        }.toTypedArray()
        val rows = readableDatabase.query(
            TABLE_MESSAGES,
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
                    add(cursor.getLong(cursor.getColumnIndexOrThrow("sequence")) to decodeMessage(cursor))
                }
            }
        }
        val hasMore = rows.size > safePageSize
        val retained = rows.take(safePageSize)
        return ChatHistoryPage(
            messages = retained.asReversed().map { it.second },
            nextBeforeSequence = retained.lastOrNull()?.first?.takeIf { hasMore },
            hasMore = hasMore
        )
    }

    @Synchronized
    fun findMessage(messageId: Long): JSONObject? =
        querySingle("message_id = ?", arrayOf(messageId.toString()))

    @Synchronized
    fun hasIncomingDuplicate(
        contactId: String,
        remoteMessageId: String,
        taskId: String,
        content: String
    ): Boolean {
        if (remoteMessageId.isNotBlank()) {
            readableDatabase.query(
                TABLE_MESSAGES,
                arrayOf("message_id"),
                "contact_id = ? AND remote_message_id_hash = ?",
                arrayOf(contactId, digest(remoteMessageId)),
                null,
                null,
                null,
                "1"
            ).use { if (it.moveToFirst()) return true }
        }
        if (taskId.isBlank()) return false
        readableDatabase.query(
            TABLE_MESSAGES,
            PAYLOAD_COLUMNS,
            "contact_id = ? AND task_id_hash = ? AND is_mine = 0",
            arrayOf(contactId, digest(taskId)),
            null,
            null,
            "sequence DESC"
        ).use { cursor ->
            while (cursor.moveToNext()) {
                if (decodeMessage(cursor).optString("content") == content) return true
            }
        }
        return false
    }

    @Synchronized
    fun deleteMessage(messageId: Long): Boolean {
        val db = writableDatabase
        db.beginTransaction()
        return try {
            val deleted = db.delete(TABLE_MESSAGES, "message_id = ?", arrayOf(messageId.toString())) > 0
            if (deleted) {
                insertTombstone(db, messageId)
                incrementVersion(db)
            }
            db.setTransactionSuccessful()
            deleted
        } finally {
            db.endTransaction()
        }
    }

    @Synchronized
    fun deleteContact(contactId: String): Int {
        val db = writableDatabase
        db.beginTransaction()
        return try {
            val messageIds = db.query(
                TABLE_MESSAGES,
                arrayOf("message_id"),
                "contact_id = ?",
                arrayOf(contactId),
                null,
                null,
                null
            ).use { cursor ->
                buildList {
                    while (cursor.moveToNext()) add(cursor.getLong(0))
                }
            }
            val deleted = db.delete(TABLE_MESSAGES, "contact_id = ?", arrayOf(contactId))
            if (deleted > 0) {
                messageIds.forEach { insertTombstone(db, it) }
                incrementVersion(db)
            }
            db.setTransactionSuccessful()
            deleted
        } finally {
            db.endTransaction()
        }
    }

    @Synchronized
    fun clear() {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.delete(TABLE_MESSAGES, null, null)
            db.delete(TABLE_TOMBSTONES, null, null)
            writeMetadata(db, KEY_NEXT_MESSAGE_ID, 1L)
            incrementVersion(db)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    @Synchronized
    fun updatedVersion(): Long = metadataValue(readableDatabase, KEY_UPDATED_VERSION)

    internal fun encryptedPayloadForTest(messageId: Long): String? =
        readableDatabase.query(
            TABLE_MESSAGES,
            arrayOf("encrypted_payload"),
            "message_id = ?",
            arrayOf(messageId.toString()),
            null,
            null,
            null,
            "1"
        ).use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }

    private fun insertOrMerge(db: SQLiteDatabase, incoming: JSONObject): Boolean {
        val messageId = incoming.optLong("id", 0L)
        val contactId = incoming.optString("contactId")
        if (messageId <= 0L || contactId.isBlank()) return false
        if (isTombstoned(db, messageId)) return false
        val current = querySingle(db, "message_id = ?", arrayOf(messageId.toString()))
        val message = mergeMessage(current, incoming)
        val raw = message.toString()
        val payloadHash = digest(raw)
        if (current != null && existingPayloadHash(db, messageId) == payloadHash) {
            raiseNextMessageId(db, messageId + 1L)
            return false
        }
        val encrypted = AgentStorageCipher.encrypt(raw, associatedData(messageId))
        val values = ContentValues().apply {
            put("contact_id", message.optString("contactId"))
            put("timestamp_millis", message.optLong("timestamp", System.currentTimeMillis()))
            put("is_mine", if (message.optBoolean("isMine")) 1 else 0)
            put("task_id_hash", message.optString("taskId").takeIf(String::isNotBlank)?.let(::digest).orEmpty())
            put(
                "remote_message_id_hash",
                message.optString("remoteMessageId").takeIf(String::isNotBlank)?.let(::digest).orEmpty()
            )
            put("payload_hash", payloadHash)
            put("encrypted_payload", encrypted)
        }
        if (current == null) {
            values.put("message_id", messageId)
            check(db.insert(TABLE_MESSAGES, null, values) != -1L) { "Chat history write failed" }
        } else {
            check(
                db.update(TABLE_MESSAGES, values, "message_id = ?", arrayOf(messageId.toString())) == 1
            ) { "Chat history update failed" }
        }
        raiseNextMessageId(db, messageId + 1L)
        return true
    }

    private fun mergeMessage(current: JSONObject?, incoming: JSONObject): JSONObject {
        if (current == null) return JSONObject(incoming.toString())
        val merged = JSONObject(incoming.toString())
        if (merged.optString("remoteMessageId").isBlank() && current.optString("remoteMessageId").isNotBlank()) {
            merged.put("remoteMessageId", current.optString("remoteMessageId"))
        }
        if (merged.optString("taskId").isBlank() && current.optString("taskId").isNotBlank()) {
            merged.put("taskId", current.optString("taskId"))
        }
        val currentTaskSequence = current.optLong("taskStatusSeq", 0L)
        val incomingTaskSequence = merged.optLong("taskStatusSeq", 0L)
        if (currentTaskSequence > incomingTaskSequence) {
            merged.put("taskId", current.optString("taskId"))
            merged.put("taskStatus", current.optString("taskStatus"))
            merged.put("taskStatusSeq", currentTaskSequence)
            merged.put("deliveryStatus", current.optString("deliveryStatus"))
        } else if (merged.optString("deliveryStatus").isBlank() && current.optString("deliveryStatus").isNotBlank()) {
            merged.put("deliveryStatus", current.optString("deliveryStatus"))
        }
        merged.put(
            "deliveryTrace",
            mergeDeliveryTrace(current.optJSONArray("deliveryTrace"), merged.optJSONArray("deliveryTrace"))
        )
        return merged
    }

    private fun mergeDeliveryTrace(current: JSONArray?, incoming: JSONArray?): JSONArray {
        val merged = JSONArray()
        val stages = mutableSetOf<String>()
        listOf(current, incoming).forEach { source ->
            if (source == null) return@forEach
            for (index in 0 until source.length()) {
                val event = source.optJSONObject(index) ?: continue
                val stage = event.optString("stage")
                if (stage.isBlank() || stages.add(stage)) merged.put(JSONObject(event.toString()))
            }
        }
        return merged
    }

    private fun existingPayloadHash(db: SQLiteDatabase, messageId: Long): String? =
        db.query(
            TABLE_MESSAGES,
            arrayOf("payload_hash"),
            "message_id = ?",
            arrayOf(messageId.toString()),
            null,
            null,
            null,
            "1"
        ).use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }

    private fun querySingle(selection: String, arguments: Array<String>): JSONObject? =
        querySingle(readableDatabase, selection, arguments)

    private fun querySingle(
        db: SQLiteDatabase,
        selection: String,
        arguments: Array<String>
    ): JSONObject? =
        db.query(
            TABLE_MESSAGES,
            PAYLOAD_COLUMNS,
            selection,
            arguments,
            null,
            null,
            "sequence DESC",
            "1"
        ).use { cursor -> if (cursor.moveToFirst()) decodeMessage(cursor) else null }

    private fun decodeMessage(cursor: Cursor): JSONObject {
        val messageId = cursor.getLong(cursor.getColumnIndexOrThrow("message_id"))
        val encrypted = cursor.getString(cursor.getColumnIndexOrThrow("encrypted_payload"))
        val raw = AgentStorageCipher.decrypt(encrypted, associatedData(messageId))
            ?: error("Chat history message could not be decrypted")
        return JSONObject(raw)
    }

    private fun queryMaximumMessageId(db: SQLiteDatabase): Long =
        db.rawQuery("SELECT COALESCE(MAX(message_id), 0) FROM $TABLE_MESSAGES", null)
            .use { cursor -> if (cursor.moveToFirst()) cursor.getLong(0) else 0L }

    private fun raiseNextMessageId(db: SQLiteDatabase, candidate: Long) {
        if (candidate > metadataValue(db, KEY_NEXT_MESSAGE_ID)) {
            writeMetadata(db, KEY_NEXT_MESSAGE_ID, candidate)
        }
    }

    private fun incrementVersion(db: SQLiteDatabase): Long {
        val next = metadataValue(db, KEY_UPDATED_VERSION) + 1L
        writeMetadata(db, KEY_UPDATED_VERSION, next)
        return next
    }

    private fun metadataValue(db: SQLiteDatabase, key: String): Long =
        db.query(
            TABLE_METADATA,
            arrayOf("metadata_value"),
            "metadata_key = ?",
            arrayOf(key),
            null,
            null,
            null,
            "1"
        ).use { cursor -> if (cursor.moveToFirst()) cursor.getLong(0) else 0L }

    private fun writeMetadata(db: SQLiteDatabase, key: String, value: Long) {
        val values = ContentValues().apply {
            put("metadata_key", key)
            put("metadata_value", value)
        }
        check(
            db.insertWithOnConflict(TABLE_METADATA, null, values, SQLiteDatabase.CONFLICT_REPLACE) != -1L
        ) { "Chat history metadata write failed" }
    }

    private fun insertTombstone(db: SQLiteDatabase, messageId: Long) {
        val values = ContentValues().apply { put("message_id", messageId) }
        check(
            db.insertWithOnConflict(TABLE_TOMBSTONES, null, values, SQLiteDatabase.CONFLICT_IGNORE) != -1L
        ) { "Chat history deletion marker write failed" }
    }

    private fun isTombstoned(db: SQLiteDatabase, messageId: Long): Boolean =
        db.query(
            TABLE_TOMBSTONES,
            arrayOf("message_id"),
            "message_id = ?",
            arrayOf(messageId.toString()),
            null,
            null,
            null,
            "1"
        ).use(Cursor::moveToFirst)

    private fun associatedData(messageId: Long): ByteArray =
        "chat-history-message:$messageId".toByteArray(Charsets.UTF_8)

    private fun digest(value: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }

    companion object {
        const val DATABASE_NAME = "signalasi_chat_history.db"
        private const val DATABASE_VERSION = 1
        private const val TABLE_MESSAGES = "chat_messages"
        private const val TABLE_METADATA = "chat_metadata"
        private const val TABLE_TOMBSTONES = "deleted_chat_messages"
        private const val KEY_NEXT_MESSAGE_ID = "next_message_id"
        private const val KEY_UPDATED_VERSION = "updated_version"
        private const val DEFAULT_PAGE_SIZE = 100
        private const val MAX_PAGE_SIZE = 500
        private val PAYLOAD_COLUMNS = arrayOf("message_id", "encrypted_payload")
        private val PAGE_COLUMNS = arrayOf("sequence", "message_id", "encrypted_payload")
    }
}
