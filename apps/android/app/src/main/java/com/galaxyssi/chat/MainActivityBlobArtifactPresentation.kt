package com.galaxyssi.chat

import android.view.View
import com.galaxyssi.chat.blob.BlobArtifactPresentation
import org.json.JSONArray
import org.json.JSONObject

/** The terminal event can be delivered before the incoming card reaches the UI. */
internal fun MainActivity.hydrateBlobAttachmentPresentation(message: ChatMessage) {
    if (message.isMine || message.isSystem || message.remoteMessageId.isBlank()) return
    val attachments = JSONArray()
    message.attachments.forEach { attachment ->
        val value = attachment.json()
        if (value.optString("artifact_uri").startsWith("galaxyssi-artifact://blob/")) {
            attachments.put(JSONObject().put("artifact_uri", value.optString("artifact_uri"))
                .put("transfer_id", value.optString("transfer_id")).put("sha256", value.optString("sha256"))
                .put("size_bytes", value.optLong("size_bytes")))
        }
    }
    if (attachments.length() == 0) return
    val identity = JSONObject().put("contactId", message.contact.id)
        .put("remoteMessageId", message.remoteMessageId).put("isMine", false).put("attachments", attachments)
    runCatching {
        historyExecutor.execute {
            val events = runCatching { ChatHistoryStore.matchingBlobAttachmentEvents(applicationContext, identity) }
                .getOrDefault(emptyList())
            if (events.isNotEmpty()) handler.post {
                if (!isDestroyed && !isFinishing) events.forEach { handleBlobArtifactPresentation(it) }
            }
        }
    }
}

/** Updates loaded cards only, without creating messages or writing history per chunk. */
internal fun MainActivity.handleBlobArtifactPresentation(event: JSONObject): Boolean {
    if (!BlobArtifactPresentation.isEvent(event)) return false
    if (!event.optBoolean("peer_chat")) return event.optString("type") == BlobArtifactPresentation.PROGRESS
    val contactId = event.optString("contact_id")
    val loaded = messages[contactId] ?: return true
    var changed = false
    loaded.forEach { message ->
        message.attachments = message.attachments.map { attachment ->
            BlobArtifactPresentation.updateAttachment(event, contactId, message.remoteMessageId,
                message.isMine, attachment.json())?.let {
                changed = true
                PeerChatAttachment.fromJson(it)
            } ?: attachment
        }
    }
    if (changed && chatPage.visibility == View.VISIBLE && selectedContact?.id == contactId) {
        messageAdapter?.syncMessages(currentMessages)
    }
    return true
}
