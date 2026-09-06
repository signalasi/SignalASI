package com.galaxyssi.chat.blob

import org.json.JSONObject

internal object BlobFailureContract {
    // Keep terminal outcomes aligned with Desktop blob_failures.py. HTTP status alone is not enough:
    // chunk_not_ready is a retryable 404, whereas blob_not_found requires a new transfer.
    val terminalCodes = setOf(
        "blob_expired", "blob_not_found", "blob_source_missing", "source_changed",
        "chunk_authentication_failed", "ciphertext_hash_mismatch", "plaintext_hash_mismatch",
        "manifest_hash_mismatch", "file_size_mismatch", "transfer_binding_mismatch",
        "local_chunk_missing_or_corrupt", "blob_outgoing_identity_mismatch"
    )

    fun observation(code: String): String {
        require(code in terminalCodes)
        return "Attachment transfer failed: $code. No verified attachment was delivered. " +
            "Inspect the attachment source and request a fresh transfer when appropriate. " +
            "Do not assume the file or image is available, and do not report a provider outage from this error."
    }

    fun receipt(manifest: JSONObject, code: String): JSONObject {
        require(code in terminalCodes)
        return JSONObject().also { result ->
            (BlobOutgoingContract.binding(manifest).keys + listOf("sha256", "size_bytes",
                "attachment_request_id", "name", "mime_type")).forEach { key ->
                if (manifest.has(key)) result.put(key, manifest.get(key))
            }
        }.put("type", "input_attachment_receipt").put("status", "failed").put("error_code", code)
            .put("source_message_id", manifest.optString("client_message_id"))
    }

    fun matches(manifest: JSONObject, receipt: JSONObject): Boolean =
        receipt.optString("error_code") in terminalCodes &&
            BlobOutgoingContract.receiptMatches(manifest, receipt, status = "failed")
}
