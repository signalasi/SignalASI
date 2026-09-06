package com.galaxyssi.chat.blob

import okhttp3.Call
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.io.InterruptedIOException
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.SSLException

internal class BlobCancellation {
    private val cancelled = AtomicBoolean()
    private val active = AtomicReference<Call?>()

    fun cancel() { cancelled.set(true); active.get()?.cancel() }
    fun check() { if (cancelled.get()) throw BlobFailure("transfer_cancelled", 499) }
    fun attach(call: Call) {
        check()
        check(active.compareAndSet(null, call)) { "One cancellation scope per transfer worker" }
        if (cancelled.get()) call.cancel()
    }
    fun detach(call: Call) { active.compareAndSet(call, null) }
}

/** Blocking transport for attachment workers, never for MQTT callbacks or UI code. */
internal class BlobHttp(
    baseUrl: String,
    client: OkHttpClient = sharedClient,
    allowLoopbackHttp: Boolean = false
) {
    val origin: String
    private val client = client.newBuilder().followRedirects(false).followSslRedirects(false)
        .retryOnConnectionFailure(false).callTimeout(60, TimeUnit.SECONDS)
        .connectTimeout(15, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS).build()

    init {
        val url = if (baseUrl.length <= 2048) baseUrl.toHttpUrlOrNull() else null
        if (url == null || url.username.isNotEmpty() || url.password.isNotEmpty() ||
            url.query != null || url.fragment != null || url.encodedPath != "/") {
            BlobProtocol.fail("invalid_relay_origin")
        }
        if (url.scheme != "https" && !(allowLoopbackHttp && url.host in setOf("127.0.0.1", "::1"))) {
            BlobProtocol.fail("relay_requires_https")
        }
        origin = url.toString().removeSuffix("/")
    }

    fun json(method: String, path: String, token: String, body: JSONObject? = null,
        cancel: BlobCancellation = BlobCancellation()): JSONObject {
        val bytes = body?.let(BlobProtocol::canonical)
        val raw = try { request(method, path, token, bytes, cancel = cancel) } finally { bytes?.fill(0) }
        return try { JSONObject(raw.toString(Charsets.UTF_8)) }
            catch (_: org.json.JSONException) { throw BlobFailure("invalid_relay_response", 502) }
            finally { raw.fill(0) }
    }

    fun request(method: String, path: String, token: String, data: ByteArray? = null,
        binary: Boolean = false, maximum: Int = BlobProtocol.MAX_MANIFEST_BYTES,
        cancel: BlobCancellation = BlobCancellation()): ByteArray {
        BlobProtocol.unhex(token, 32).fill(0)
        require(maximum in 1..BlobProtocol.CHUNK_BYTES + BlobProtocol.TAG_BYTES)
        // Internal callers supply only typed blob IDs and integer chunk indices.
        require(path.matches(Regex("/v1/blobs/[a-f0-9]{32}(/missing|/chunks/[0-9]{1,4})?")))
        cancel.check()
        val bulk = binary || maximum > BlobProtocol.MAX_MANIFEST_BYTES
        var acquired = false
        try {
            if (bulk) {
                while (!bulkSlots.tryAcquire(100, TimeUnit.MILLISECONDS)) cancel.check()
                acquired = true
                cancel.check()
            }
            val contentType = if (binary) "application/octet-stream" else "application/json"
            val body = if (data != null || method in setOf("PUT", "POST")) {
                (data ?: ByteArray(0)).toRequestBody(contentType.toMediaType())
            } else null
            val request = Request.Builder().url(origin + path).method(method, body)
                .header("Authorization", "Bearer $token").header("Accept-Encoding", "identity").build()
            val call = client.newCall(request)
            cancel.attach(call)
            try {
                call.execute().use { response ->
                    if (response.code in 300..399) throw BlobFailure("relay_redirect_rejected", 502)
                    if (!response.header("Content-Encoding", "identity").equals("identity", ignoreCase = true)) {
                        throw BlobFailure("relay_response_encoding_rejected", 502)
                    }
                    val limit = if (response.isSuccessful) maximum else 4096
                    val source = response.body ?: throw BlobFailure("invalid_relay_response", 502)
                    if (source.contentLength() > limit) throw BlobFailure("relay_response_too_large", 502)
                    val raw = try { source.byteStream().use { BlobStaging.readBounded(it, limit) } }
                        catch (_: BlobFailure) { throw BlobFailure("relay_response_too_large", 502) }
                    if (!response.isSuccessful) {
                        val code = try { JSONObject(raw.toString(Charsets.UTF_8)).opt("error") as? String }
                            catch (_: org.json.JSONException) { null }
                            finally { raw.fill(0) }
                        throw BlobFailure(code?.takeIf { it in relayErrors } ?: "relay_http_${response.code}", response.code)
                    }
                    cancel.check()
                    return raw
                }
            } finally { cancel.detach(call) }
        } catch (error: IOException) {
            cancel.check()
            val code = when (error) {
                is SSLException -> "relay_tls_verification_failed"
                is InterruptedIOException -> "relay_timeout"
                else -> "relay_connection_failed"
            }
            throw BlobFailure(code, if (error is InterruptedIOException) 504 else 503)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            throw BlobFailure("transfer_cancelled", 499)
        } finally { if (acquired) bulkSlots.release() }
    }

    companion object {
        private val sharedClient = OkHttpClient()
        private val bulkSlots = Semaphore(4, true)
        private val relayErrors = setOf("authentication_required", "blob_not_found", "blob_expired",
            "blob_creation_conflict", "relay_session_capacity", "relay_storage_capacity", "ciphertext_hash_mismatch",
            "chunk_not_ready", "chunk_not_found", "corrupt_chunk_requires_repair", "invalid_json",
            "invalid_creation_request", "invalid_identifier", "invalid_manifest", "unsupported_blob_version",
            "invalid_chunk_count", "invalid_chunk_descriptor", "invalid_chunk_size", "invalid_chunk_layout",
            "file_too_large", "capabilities_must_differ", "invalid_chunk_index", "body_too_large",
            "body_timeout", "content_encoding_not_supported")
    }
}
