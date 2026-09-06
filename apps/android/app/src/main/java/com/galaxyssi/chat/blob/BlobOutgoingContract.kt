package com.galaxyssi.chat.blob

import org.json.JSONObject

internal object BlobOutgoingContract {
    const val TYPE = "input_attachment_blob_offer"
    private val bindingKeys = listOf("client_route_id", "conversation_id", "task_id", "turn_id",
        "attachment_id", "transfer_id", "contact_id")

    fun binding(manifest: JSONObject): Map<String, String> = bindingKeys.associateWith {
        BlobProtocol.string(manifest, it)
    }.also { BlobProtocol.bindingHash(it) }

    fun offerPayload(manifest: JSONObject, offer: JSONObject): JSONObject = JSONObject(manifest.toString())
        .put("type", TYPE).put("blob_offer", offer).also {
            it.remove("eager_chunks"); it.remove("resume"); it.remove("time")
            if (BlobProtocol.canonical(it).size > 32 * 1024) BlobProtocol.fail("input_blob_offer_too_large")
        }

    fun receiptMatches(manifest: JSONObject, receipt: JSONObject): Boolean = runCatching {
        receipt.optString("status") == "stored" &&
            (bindingKeys + "sha256").all { manifest.optString(it).isNotBlank() &&
                manifest.optString(it) == receipt.optString(it) } &&
            BlobProtocol.integer(manifest, "size_bytes", 0, BlobProtocol.MAX_FILE_BYTES) ==
                BlobProtocol.integer(receipt, "size_bytes", 0, BlobProtocol.MAX_FILE_BYTES) &&
            manifest.optString("attachment_request_id") == receipt.optString("attachment_request_id") &&
            (manifest.isNull("client_message_id") || manifest.optString("client_message_id").isBlank() ||
                manifest.optString("client_message_id") == receipt.optString("source_message_id"))
    }.getOrDefault(false)

    fun sameManifest(first: JSONObject, second: JSONObject): Boolean {
        fun normalized(value: JSONObject) = JSONObject(value.toString()).also {
            it.remove("time"); it.remove("resume"); it.remove("eager_chunks")
        }
        return BlobProtocol.canonical(normalized(first)).contentEquals(BlobProtocol.canonical(normalized(second)))
    }

    fun retryDelay(attempt: Int): Long = minOf(300_000L, 2_000L shl attempt.coerceIn(0, 8))
}
