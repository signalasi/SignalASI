package com.galaxyssi.chat

import android.view.View
import com.galaxyssi.chat.blob.BlobArtifactPresentation
import org.json.JSONObject

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
