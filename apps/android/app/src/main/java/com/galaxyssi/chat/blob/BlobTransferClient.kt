package com.galaxyssi.chat.blob

import org.json.JSONObject
import java.io.File
import java.io.OutputStream
import javax.crypto.SecretKey

/** File completion here means authenticated bytes, not a peer's durable chat receipt. */
internal class BlobTransferClient(private val http: BlobHttp, private val provisioningToken: String? = null) {
    init { provisioningToken?.let { BlobProtocol.unhex(it, 32).fill(0) } }

    fun upload(staged: BlobStaging, cancel: BlobCancellation = BlobCancellation(),
        progress: (Long, Long) -> Unit = { _, _ -> }, onOffer: (JSONObject) -> Unit = {}): JSONObject = staged.exclusive {
        cancel.check()
        val remote = staged.remote
        if (remote.length() != 0 && (remote.optString("relay") != http.origin || remote.optString("role") != "sender")) {
            BlobProtocol.fail("relay_checkpoint_mismatch")
        }
        if (remote.optBoolean("revoked")) BlobProtocol.fail("transfer_revoked")
        if (remote.length() == 0) {
            remote.put("relay", http.origin).put("role", "sender").put("created", false)
                .put("read_token", BlobProtocol.hex(BlobProtocol.randomBytes(32)))
                .put("write_token", BlobProtocol.hex(BlobProtocol.randomBytes(32)))
            staged.save()
        }
        val path = "/v1/blobs/${staged.private.blobId}"
        val readToken = BlobProtocol.string(remote, "read_token")
        val writeToken = BlobProtocol.string(remote, "write_token")
        if (!remote.optBoolean("created")) {
            val token = provisioningToken ?: throw BlobFailure("relay_provisioning_required", 401)
            val result = http.json("PUT", path, token, JSONObject().put("manifest", staged.manifest)
                .put("read_token", readToken).put("write_token", writeToken), cancel)
            if (result.opt("root") != staged.private.manifestHash) BlobProtocol.fail("manifest_hash_mismatch")
            remote.put("created", true)
            staged.save()
        }
        val missing = missing(http.json("GET", "$path/missing", writeToken, cancel = cancel), staged)
        // Existing encrypted control queues must deduplicate this stable blob ID on retry.
        onOffer(offer(staged))
        var done = staged.private.size - missing.sumOf { staged.chunks[it].size.toLong() - BlobProtocol.TAG_BYTES }
        progress(done, staged.private.size)
        missing.forEach { index ->
            cancel.check()
            val bytes = staged.readChunk(index)
            try {
                val raw = http.request("PUT", "$path/chunks/$index", writeToken, bytes, binary = true, cancel = cancel)
                try {
                    val stored = try { JSONObject(raw.toString(Charsets.UTF_8)).opt("stored") == true }
                        catch (_: org.json.JSONException) { false }
                    if (!stored) throw BlobFailure("invalid_chunk_receipt", 502)
                } finally { raw.fill(0) }
                done += bytes.size - BlobProtocol.TAG_BYTES
                progress(done, staged.private.size)
            } finally { bytes.fill(0) }
        }
        val final = http.json("GET", "$path/missing", writeToken, cancel = cancel)
        if (missing(final, staged).isNotEmpty() || final.opt("complete") != true) BlobProtocol.fail("relay_upload_incomplete")
        remote.put("uploaded", true)
        staged.save()
        offer(staged)
    }

    private fun missing(status: JSONObject, staged: BlobStaging): List<Int> {
        if (status.opt("root") != staged.private.manifestHash ||
            BlobProtocol.integer(status, "chunk_count", 1, BlobProtocol.MAX_CHUNKS.toLong()).toInt() != staged.chunks.size) {
            BlobProtocol.fail("relay_checkpoint_mismatch")
        }
        return BlobProtocol.missing(BlobProtocol.string(status, "missing_bitmap"), staged.chunks.size)
    }

    private fun offer(staged: BlobStaging): JSONObject = JSONObject().put("version", BlobProtocol.VERSION)
        .put("relay", http.origin).put("private", staged.private.json())
        .put("read_token", BlobProtocol.string(staged.remote, "read_token"))

    fun download(offer: JSONObject, directory: File, binding: Map<String, String>, storageKey: SecretKey? = null,
        cancel: BlobCancellation = BlobCancellation(), progress: (Long, Long) -> Unit = { _, _ -> }): BlobStaging {
        cancel.check()
        BlobProtocol.keys(offer, setOf("version", "relay", "private", "read_token"))
        BlobProtocol.integer(offer, "version", 1, 1)
        if (BlobProtocol.string(offer, "relay") != http.origin) BlobProtocol.fail("invalid_blob_offer")
        val descriptor = offer.optJSONObject("private") ?: BlobProtocol.fail("invalid_private_descriptor")
        if (BlobProtocol.string(descriptor, "binding_sha256") != BlobProtocol.bindingHash(binding)) {
            BlobProtocol.fail("transfer_binding_mismatch")
        }
        val blobId = BlobProtocol.string(descriptor, "blob_id").also { BlobProtocol.unhex(it, 16).fill(0) }
        val token = BlobProtocol.string(offer, "read_token").also { BlobProtocol.unhex(it, 32).fill(0) }
        val path = "/v1/blobs/$blobId"
        val remote = JSONObject().put("relay", http.origin).put("role", "receiver").put("read_token", token)
        val staged = if (BlobStaging.exists(directory)) BlobStaging.open(directory, binding, storageKey) else {
            val manifest = http.json("GET", path, token, cancel = cancel)
            try { BlobStaging.receive(directory, descriptor, manifest, remote, binding, storageKey) }
            catch (_: java.nio.file.FileAlreadyExistsException) { throw BlobFailure("transfer_busy", 409) }
        }
        try {
            staged.exclusive {
                BlobPrivate.parse(descriptor, staged.manifest).use { expected ->
                    if (expected.json().toString() != staged.private.json().toString() ||
                        staged.remote.opt("relay") != http.origin || staged.remote.opt("role") != "receiver" ||
                        staged.remote.opt("read_token") != token) BlobProtocol.fail("relay_checkpoint_mismatch")
                }
                val missing = staged.chunks.indices.filterNot(staged::hasChunk)
                var done = staged.private.size - missing.sumOf { staged.chunks[it].size.toLong() - BlobProtocol.TAG_BYTES }
                progress(done, staged.private.size)
                missing.forEach { index ->
                    cancel.check()
                    val bytes = http.request("GET", "$path/chunks/$index", token,
                        maximum = BlobProtocol.CHUNK_BYTES + BlobProtocol.TAG_BYTES, cancel = cancel)
                    try {
                        staged.storeChunk(index, bytes)
                        done += bytes.size - BlobProtocol.TAG_BYTES
                        progress(done, staged.private.size)
                    } finally { bytes.fill(0) }
                }
                staged.copyPlaintext(discard, binding, cancel::check)
            }
            return staged
        } catch (error: Exception) { staged.close(); throw error }
    }

    fun revoke(staged: BlobStaging, cancel: BlobCancellation = BlobCancellation()) = staged.exclusive {
        if (staged.remote.opt("relay") != http.origin || staged.remote.opt("role") != "sender") {
            BlobProtocol.fail("relay_checkpoint_mismatch")
        }
        http.request("DELETE", "/v1/blobs/${staged.private.blobId}",
            BlobProtocol.string(staged.remote, "write_token"), cancel = cancel).fill(0)
        staged.remote.put("revoked", true)
        staged.save()
    }

    companion object {
        private val discard = object : OutputStream() {
            override fun write(value: Int) = Unit
            override fun write(bytes: ByteArray, offset: Int, length: Int) = Unit
        }
    }
}
