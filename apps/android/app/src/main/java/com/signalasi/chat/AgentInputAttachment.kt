package com.signalasi.chat

import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

data class AgentInputAttachment(
    val id: String,
    val uri: Uri,
    val displayName: String,
    val mimeType: String,
    val sizeBytes: Long
) {
    val isImage: Boolean get() = mimeType.startsWith("image/", ignoreCase = true)

    fun richBlock(): AgentRichBlock = AgentRichBlock(
        id = id,
        type = if (isImage) AgentRichBlockType.IMAGE else AgentRichBlockType.FILE,
        title = displayName,
        uri = uri.toString(),
        mimeType = mimeType,
        text = if (isImage) "" else humanSize(sizeBytes),
        fallbackText = displayName
    )

    fun descriptor(): JSONObject = JSONObject()
        .put("id", id)
        .put("name", displayName)
        .put("mime_type", mimeType)
        .put("size", sizeBytes)
        .put("uri", uri.toString())

    companion object {
        fun humanSize(bytes: Long): String = when {
            bytes < 0L -> ""
            bytes < 1024L -> "$bytes B"
            bytes < 1024L * 1024L -> "%.1f KB".format(bytes / 1024.0)
            else -> "%.1f MB".format(bytes / (1024.0 * 1024.0))
        }
    }
}

/** Keeps attachment URIs bound to a turn while the planner selects a target. */
object AgentTurnAttachmentRegistry {
    private const val MAX_TURNS = 64
    private val turns = ConcurrentHashMap<String, List<AgentInputAttachment>>()

    fun put(turnId: String, attachments: List<AgentInputAttachment>) {
        if (turnId.isBlank() || attachments.isEmpty()) return
        turns[turnId] = attachments.toList()
        if (turns.size > MAX_TURNS) turns.keys.firstOrNull()?.let(turns::remove)
    }

    fun get(turnId: String): List<AgentInputAttachment> = turns[turnId].orEmpty()

    fun remove(turnId: String) {
        turns.remove(turnId)
    }

    fun descriptors(turnId: String): JSONArray = JSONArray(get(turnId).map(AgentInputAttachment::descriptor))
}
