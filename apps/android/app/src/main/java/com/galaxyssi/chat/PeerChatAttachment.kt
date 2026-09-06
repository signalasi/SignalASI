package com.galaxyssi.chat

import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject

data class PeerChatAttachment(
    val name: String,
    val mimeType: String,
    val sizeBytes: Long,
    val uri: String = "",
    val artifactUri: String = "",
    val transferId: String = "",
    val sha256: String = "",
    val durationMillis: Long = 0L,
    val transferProgress: Int = -1,
    val transferState: String = ""
) {
    fun resolvedUri(context: Context): Uri? {
        val source = if (artifactUri.isNotBlank()) {
            AgentDesktopArtifactStore.resolveBlock(
                context,
                AgentRichBlock(
                    id = artifactUri,
                    type = if (mimeType.startsWith("image/")) {
                        AgentRichBlockType.IMAGE
                    } else {
                        AgentRichBlockType.FILE
                    },
                    title = name,
                    uri = artifactUri,
                    mimeType = mimeType,
                    metadata = mapOf("artifact_source_uri" to artifactUri)
                )
            ).uri
        } else {
            uri
        }
        if (source.startsWith("galaxyssi-artifact://")) return null
        val parsed = source.takeIf(String::isNotBlank)?.let(Uri::parse)
        if (mimeType.startsWith("audio/", ignoreCase = true)) {
            return PeerMessageAttachmentStore.resolveAudio(context, name, parsed)
        }
        return parsed
    }

    fun json(): JSONObject = JSONObject()
        .put("name", name)
        .put("mime_type", mimeType)
        .put("size_bytes", sizeBytes)
        .put("uri", uri)
        .put("artifact_uri", artifactUri)
        .put("transfer_id", transferId)
        .put("sha256", sha256)
        .put("duration_ms", durationMillis)
        .put("transfer_progress", transferProgress)
        .put("transfer_state", transferState)

    companion object {
        fun fromJson(value: JSONObject): PeerChatAttachment = PeerChatAttachment(
            name = value.optString("name").ifBlank { "attachment" },
            mimeType = value.optString("mime_type").ifBlank { "application/octet-stream" },
            sizeBytes = value.optLong("size_bytes", value.optLong("size", 0L)),
            uri = value.optString("uri"),
            artifactUri = value.optString("artifact_uri"),
            transferId = value.optString("transfer_id"),
            sha256 = value.optString("sha256"),
            durationMillis = value.optLong("duration_ms", 0L),
            transferProgress = value.optInt("transfer_progress", -1).coerceIn(-1, 100),
            transferState = value.optString("transfer_state")
        )

        fun decode(values: JSONArray?): List<PeerChatAttachment> = buildList {
            if (values == null) return@buildList
            for (index in 0 until values.length()) {
                values.optJSONObject(index)?.let { add(fromJson(it)) }
            }
        }

        fun encode(values: List<PeerChatAttachment>): JSONArray = JSONArray().apply {
            values.forEach { put(it.json()) }
        }
    }
}

internal object PeerChatPresentation {
    private val internalTransportTypes = setOf(
        "artifact_blob_progress",
        PeerAttachmentTransferProgress.TYPE,
        "input_attachment_manifest",
        "input_attachment_chunk",
        "input_attachment_receipt",
        AgentAttachmentRecoveryRequest.REQUEST_TYPE,
        AgentAttachmentRecoveryRequest.RESULT_TYPE
    )

    fun isInternalTransportEvent(json: JSONObject?): Boolean =
        json?.optString("type") in internalTransportTypes

    fun incomingContent(payload: String, json: JSONObject?): String {
        if (isInternalTransportEvent(json)) return ""
        if (json?.optString("type") == "peer_message") {
            return json.optString("content")
        }
        return json?.optString("content", payload)?.takeIf(String::isNotBlank) ?: payload
    }

    fun storedContent(content: String): String {
        val envelope = runCatching { JSONObject(content) }.getOrNull() ?: return content
        if (isInternalTransportEvent(envelope)) return ""
        if (envelope.optString("type") != "peer_message") return content
        return envelope.optString("content")
    }
}
