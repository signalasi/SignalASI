package com.galaxyssi.chat

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import com.galaxyssi.chat.blob.BlobArtifactPresentation
import org.json.JSONObject
import java.security.MessageDigest

/** Terminal projections share the chat transaction; progress never enters this table. */
internal class BlobChatAttachmentEvents(private val cipher: AgentRowStorageCipher) {
    fun store(db: SQLiteDatabase, event: JSONObject): Boolean {
        if (!BlobArtifactPresentation.isTerminalPeerEvent(event)) return false
        val contact = event.getString("contact_id")
        val remote = hash(event.getString("source_message_id"))
        val transfer = event.getString("transfer_id")
        val previous = read(db, contact, remote, transfer)
        if (previous?.optString("type") == "artifact_available" || previous?.toString() == event.toString()) return false
        val values = ContentValues().apply {
            put("contact_id", contact); put("remote_hash", remote); put("transfer_id", transfer)
            put("payload", cipher.encrypt(event.toString(), aad(contact, remote, transfer)))
        }
        db.insertWithOnConflict(TABLE, null, values, SQLiteDatabase.CONFLICT_REPLACE).also { check(it != -1L) }
        return true
    }

    fun project(db: SQLiteDatabase, message: JSONObject): JSONObject {
        if (message.optBoolean("isMine")) return message
        val contact = message.optString("contactId")
        val remote = message.optString("remoteMessageId")
        if (contact.isBlank() || remote.isBlank()) return message
        val attachments = message.optJSONArray("attachments") ?: return message
        if (attachments.length() == 0) return message
        var result = message
        var remoteHash: String? = null
        for (index in 0 until attachments.length()) {
            val attachment = attachments.optJSONObject(index) ?: continue
            if (!attachment.optString("artifact_uri").startsWith("galaxyssi-artifact://blob/")) continue
            val transfer = attachment.optString("transfer_id")
            if (!transfer.matches(Regex("[0-9a-f]{64}"))) continue
            if (remoteHash == null) remoteHash = hash(remote)
            val event = read(db, contact, remoteHash, transfer) ?: continue
            result = BlobArtifactPresentation.updateMessage(event, result) ?: result
        }
        return result
    }

    private fun read(db: SQLiteDatabase, contact: String, remote: String, transfer: String): JSONObject? =
        db.rawQuery("SELECT payload FROM $TABLE WHERE contact_id=? AND remote_hash=? AND transfer_id=?",
            arrayOf(contact, remote, transfer)).use { cursor ->
            if (!cursor.moveToFirst()) return@use null
            // An invalid auxiliary projection must not prevent loading/saving the
            // actual message. Keep the row for diagnosis; a verified event can repair it.
            runCatching { cipher.decrypt(cursor.getString(0), aad(contact, remote, transfer))?.let(::JSONObject) }
                .getOrNull()?.takeIf { event ->
                    BlobArtifactPresentation.isTerminalPeerEvent(event) && event.optString("contact_id") == contact &&
                        hash(event.optString("source_message_id")) == remote && event.optString("transfer_id") == transfer
                }
        }

    private fun aad(contact: String, remote: String, transfer: String) =
        "blob-chat-terminal:$contact:$remote:$transfer".toByteArray(Charsets.UTF_8)

    companion object {
        const val TABLE = "blob_chat_attachment_events"
        fun createSchema(db: SQLiteDatabase) = db.execSQL("CREATE TABLE IF NOT EXISTS $TABLE(" +
            "contact_id TEXT NOT NULL,remote_hash TEXT NOT NULL,transfer_id TEXT NOT NULL,payload TEXT NOT NULL," +
            "PRIMARY KEY(contact_id,remote_hash,transfer_id))")
        private fun hash(value: String): String = MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }
}
