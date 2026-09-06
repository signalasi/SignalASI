package com.galaxyssi.chat.blob

import org.json.JSONObject
import java.net.URI
import java.util.Locale

/** Small authenticated output offers; file bytes never belong in this contract. */
internal object BlobArtifactContract {
    const val OFFER_TYPE = "artifact_blob_offer"
    const val RECEIPT_TYPE = "artifact_blob_receipt"
    const val MAX_CONTROL_BYTES = 32 * 1024
    private val scope = listOf("client_route_id", "conversation_id", "task_id", "turn_id", "contact_id",
        "source_message_id", "desktop_id")
    private val textLimits = scope.associateWith { 256 } + mapOf("artifact_id" to 64, "artifact_uri" to 2048,
        "name" to 255, "relative_path" to 2048, "mime_type" to 255, "sha256" to 64, "original_sha256" to 64)
    private val numberLimits = mapOf("size_bytes" to BlobProtocol.MAX_FILE_BYTES,
        "original_size_bytes" to 9_007_199_254_740_991L, "execution_generation" to 9_007_199_254_740_991L)
    private val fields = textLimits.keys + numberLimits.keys + "peer_chat"

    fun makeManifest(metadata: JSONObject): JSONObject {
        BlobProtocol.keys(metadata, fields)
        textLimits.forEach { (key, maximum) ->
            val text = BlobProtocol.string(metadata, key)
            if (text.codePointCount(0, text.length) !in 1..maximum || text.any { it.code < 32 }) {
                BlobProtocol.fail("invalid_artifact_blob_manifest")
            }
        }
        numberLimits.forEach { (key, maximum) -> BlobProtocol.integer(metadata, key, 1, maximum) }
        if (metadata.opt("peer_chat") !is Boolean ||
            !BlobProtocol.string(metadata, "client_route_id").matches(Regex("[A-Za-z0-9_-]{22}"))) {
            BlobProtocol.fail("invalid_artifact_blob_manifest")
        }
        listOf("artifact_id", "sha256", "original_sha256").forEach {
            BlobProtocol.unhex(BlobProtocol.string(metadata, it), 32).fill(0)
        }
        val uri = try { URI(BlobProtocol.string(metadata, "artifact_uri")) }
            catch (_: Exception) { BlobProtocol.fail("invalid_artifact_blob_uri") }
        if (uri.scheme?.lowercase(Locale.ROOT) != "galaxyssi-artifact" || uri.rawAuthority.isNullOrBlank() ||
            uri.rawQuery != null || uri.rawFragment != null) BlobProtocol.fail("invalid_artifact_blob_uri")
        if (metadata.getLong("original_size_bytes") < metadata.getLong("size_bytes")) {
            BlobProtocol.fail("invalid_artifact_blob_manifest")
        }
        val canonical = BlobProtocol.canonical(metadata)
        if (canonical.size > MAX_CONTROL_BYTES / 2) BlobProtocol.fail("artifact_blob_manifest_too_large")
        return JSONObject(metadata.toString()).put("transfer_id", BlobProtocol.hash(canonical))
    }

    fun validateManifest(manifest: JSONObject): JSONObject {
        BlobProtocol.keys(manifest, fields + "transfer_id")
        val transferId = BlobProtocol.string(manifest, "transfer_id").also { BlobProtocol.unhex(it, 32).fill(0) }
        val metadata = JSONObject(manifest.toString()).also { it.remove("transfer_id") }
        return makeManifest(metadata).also {
            if (it.getString("transfer_id") != transferId) BlobProtocol.fail("artifact_blob_manifest_mismatch")
        }
    }

    fun binding(manifest: JSONObject): Map<String, String> {
        val value = validateManifest(manifest)
        return (scope.associateWith { value.getString(it) } + mapOf("artifact_id" to value.getString("artifact_id"),
            "transfer_id" to value.getString("transfer_id"),
            "execution_generation" to value.getLong("execution_generation").toString()))
            .also { BlobProtocol.bindingHash(it) }
    }

    fun validateOffer(payload: JSONObject, route: String, desktop: String, origin: String): JSONObject {
        if (payload.optString("type") != OFFER_TYPE) BlobProtocol.fail("invalid_artifact_blob_offer")
        BlobProtocol.integer(payload, "version", 1, 1)
        val revision = BlobProtocol.integer(payload, "transport_revision", 1, 9_007_199_254_740_991L)
        if (BlobProtocol.canonical(payload).size > MAX_CONTROL_BYTES) BlobProtocol.fail("artifact_blob_offer_too_large")
        val manifest = validateManifest(payload.optJSONObject("manifest") ?: BlobProtocol.fail("invalid_artifact_blob_manifest"))
        if (manifest.getString("client_route_id") != route || manifest.getString("desktop_id") != desktop) {
            BlobProtocol.fail("artifact_blob_route_mismatch")
        }
        val offer = payload.optJSONObject("blob_offer") ?: BlobProtocol.fail("invalid_blob_offer")
        BlobProtocol.keys(offer, setOf("version", "relay", "private", "read_token"))
        BlobProtocol.integer(offer, "version", 1, 1)
        if (BlobProtocol.string(offer, "relay") != BlobHttp.normalizeOrigin(origin)) {
            BlobProtocol.fail("artifact_blob_relay_mismatch")
        }
        BlobProtocol.unhex(BlobProtocol.string(offer, "read_token"), 32).fill(0)
        val private = offer.optJSONObject("private") ?: BlobProtocol.fail("invalid_private_descriptor")
        BlobProtocol.keys(private, setOf("version", "blob_id", "key", "nonce_prefix", "size", "sha256",
            "binding_sha256", "manifest_sha256"))
        BlobProtocol.integer(private, "version", 1, 1)
        BlobProtocol.integer(private, "size", 0, BlobProtocol.MAX_FILE_BYTES)
        mapOf("blob_id" to 16, "key" to 32, "nonce_prefix" to 8, "sha256" to 32,
            "binding_sha256" to 32, "manifest_sha256" to 32).forEach { (key, bytes) ->
            BlobProtocol.unhex(BlobProtocol.string(private, key), bytes).fill(0)
        }
        if (private.getString("binding_sha256") != BlobProtocol.bindingHash(binding(manifest)) ||
            private.getLong("size") != manifest.getLong("size_bytes") ||
            private.getString("sha256") != manifest.getString("sha256")) {
            BlobProtocol.fail("artifact_blob_binding_mismatch")
        }
        return JSONObject().put("type", OFFER_TYPE).put("version", 1).put("manifest", manifest).put("transport_revision", revision)
            .put("blob_offer", JSONObject(offer.toString()))
    }

    fun storedReceipt(manifest: JSONObject): JSONObject {
        val value = validateManifest(manifest)
        return JSONObject().put("type", RECEIPT_TYPE).put("version", 1).put("status", "stored").also { receipt ->
            listOf("transfer_id", "client_route_id", "artifact_id", "sha256", "size_bytes").forEach {
                receipt.put(it, value.get(it))
            }
        }
    }
}
