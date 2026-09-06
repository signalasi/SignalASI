package com.galaxyssi.chat.blob

import org.json.JSONObject
import org.json.JSONArray

/** Local presentation only; transport credentials never enter a chat event. */
internal object BlobArtifactPresentation {
    const val PROGRESS = "artifact_blob_progress"

    fun progress(manifest: JSONObject, done: Long, total: Long): JSONObject {
        val value = BlobArtifactContract.validateManifest(manifest)
        require(total == value.getLong("size_bytes") && done in 0..total)
        return BlobArtifactIngressPolicy.event(value, "artifact_available")
            .put("type", PROGRESS).also { it.remove("message_id") }
            .put("received_bytes", done)
            // The last downloaded chunk is not yet a verified, persisted local file.
            .put("progress", ((done * 100L) / total).toInt().coerceIn(0, 99))
    }

    fun isEvent(event: JSONObject): Boolean = event.optBoolean("blob_publication") &&
        event.optString("type") in setOf(PROGRESS, "artifact_available", "artifact_download_failed")

    fun isTerminalPeerEvent(event: JSONObject): Boolean = isEvent(event) &&
        event.optString("type") != PROGRESS && event.optBoolean("peer_chat") &&
        event.optString("contact_id").isNotBlank() &&
        event.optString("desktop_id") == event.optString("contact_id") &&
        event.optString("source_message_id").length in 1..200 &&
        event.optString("transfer_id").matches(Regex("[0-9a-f]{64}")) &&
        event.optString("artifact_uri").matches(Regex("galaxyssi-artifact://blob/[0-9a-f]{64}")) &&
        event.optString("sha256").matches(Regex("[0-9a-f]{64}")) &&
        event.optLong("size_bytes", -1) in 1..BlobProtocol.MAX_FILE_BYTES

    fun updateMessage(event: JSONObject, message: JSONObject): JSONObject? {
        if (!isTerminalPeerEvent(event)) return null
        val original = message.optJSONArray("attachments") ?: return null
        var changed = false
        val attachments = JSONArray()
        for (index in 0 until original.length()) {
            val item = original.optJSONObject(index)
            val updated = item?.let { updateAttachment(event, message.optString("contactId"),
                message.optString("remoteMessageId"), message.optBoolean("isMine"), it) }
            if (updated != null) changed = true
            attachments.put(updated ?: original.get(index))
        }
        return if (changed) JSONObject(message.toString()).put("attachments", attachments) else null
    }

    fun updateAttachment(event: JSONObject, contactId: String, remoteMessageId: String,
        mine: Boolean, attachment: JSONObject): JSONObject? {
        if (!isEvent(event) || !event.optBoolean("peer_chat") || mine || contactId.isBlank() ||
            event.optString("desktop_id") != contactId || event.optString("contact_id") != contactId ||
            remoteMessageId.isBlank() || event.optString("source_message_id") != remoteMessageId) return null
        val transfer = event.optString("transfer_id")
        val uri = event.optString("artifact_uri")
        if (!transfer.matches(Regex("[0-9a-f]{64}")) || !uri.matches(Regex("galaxyssi-artifact://blob/[0-9a-f]{64}")) ||
            attachment.optString("artifact_uri") != uri || attachment.optString("transfer_id") != transfer ||
            attachment.optString("sha256") != event.optString("sha256") ||
            attachment.optLong("size_bytes") != event.optLong("size_bytes")) return null
        val type = event.optString("type")
        if (attachment.optString("transfer_state") == "complete") return null
        val state = when (type) {
            "artifact_available" -> "complete"
            "artifact_download_failed" -> "failed"
            else -> "downloading"
        }
        val progress = when (state) {
            "complete" -> 100
            "failed" -> attachment.optInt("transfer_progress", 0).coerceIn(0, 99)
            else -> event.optInt("progress").coerceIn(0, 99)
        }
        if (attachment.optString("transfer_state") == state && attachment.optInt("transfer_progress", -1) == progress) return null
        return JSONObject(attachment.toString()).put("transfer_state", state).put("transfer_progress", progress)
    }
}
