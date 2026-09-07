package com.galaxyssi.chat.blob

import org.json.JSONArray
import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

internal class BlobFailure(val code: String, val status: Int = 400) : Exception(code)

internal data class BlobChunk(val sha256: String, val size: Int)

internal object BlobProtocol {
    const val VERSION = 1
    const val CHUNK_BYTES = 1024 * 1024
    const val TAG_BYTES = 16
    const val MAX_FILE_BYTES = 1024L * 1024L * 1024L
    const val MAX_CHUNKS = 1024
    const val MAX_MANIFEST_BYTES = 128 * 1024
    private val random = SecureRandom()
    private val hexPattern = Regex("[a-f0-9]+")
    private val domain = "GalaxySSI-Blob-AEAD-v1\u0000".toByteArray(Charsets.US_ASCII)

    fun fail(code: String): Nothing = throw BlobFailure(code)

    fun hex(bytes: ByteArray): String = buildString(bytes.size * 2) {
        bytes.forEach { byte ->
            val value = byte.toInt() and 255
            append("0123456789abcdef"[value ushr 4])
            append("0123456789abcdef"[value and 15])
        }
    }

    fun unhex(value: String, bytes: Int): ByteArray {
        if (value.length != bytes * 2 || !hexPattern.matches(value)) fail("invalid_identifier")
        return ByteArray(bytes) { index -> value.substring(index * 2, index * 2 + 2).toInt(16).toByte() }
    }

    fun hash(bytes: ByteArray, length: Int = bytes.size): String = MessageDigest.getInstance("SHA-256").run {
        require(length in 0..bytes.size)
        update(bytes, 0, length)
        hex(digest())
    }
    fun randomBytes(size: Int): ByteArray = ByteArray(size).also(random::nextBytes)

    fun string(value: JSONObject, key: String): String = value.opt(key) as? String ?: fail("invalid_$key")

    fun integer(value: JSONObject, key: String, minimum: Long, maximum: Long): Long {
        val raw = value.opt(key)
        if (raw !is Int && raw !is Long) fail("invalid_$key")
        val number = (raw as Number).toLong()
        if (number !in minimum..maximum) fail("invalid_$key")
        return number
    }

    fun keys(value: JSONObject, required: Set<String>) {
        if (value.keys().asSequence().toSet() != required) fail("invalid_descriptor_fields")
    }

    fun parseManifest(value: JSONObject): List<BlobChunk> {
        keys(value, setOf("version", "chunks"))
        integer(value, "version", VERSION.toLong(), VERSION.toLong())
        val chunks = value.optJSONArray("chunks") ?: fail("invalid_chunk_count")
        if (chunks.length() !in 1..MAX_CHUNKS) fail("invalid_chunk_count")
        return (0 until chunks.length()).map { index ->
            val chunk = chunks.optJSONObject(index) ?: fail("invalid_chunk_descriptor")
            keys(chunk, setOf("sha256", "size"))
            val digest = string(chunk, "sha256").also { unhex(it, 32).fill(0) }
            val size = integer(chunk, "size", TAG_BYTES.toLong(), (CHUNK_BYTES + TAG_BYTES).toLong()).toInt()
            if ((index < chunks.length() - 1 && size != CHUNK_BYTES + TAG_BYTES) ||
                (chunks.length() > 1 && index == chunks.length() - 1 && size == TAG_BYTES)) {
                fail("invalid_chunk_layout")
            }
            BlobChunk(digest, size)
        }
    }

    fun manifest(chunks: List<BlobChunk>): JSONObject = JSONObject().put("version", VERSION)
        .put("chunks", JSONArray(chunks.map { JSONObject().put("sha256", it.sha256).put("size", it.size) }))

    fun bindingHash(binding: Map<String, String>): String {
        if (binding.size !in 1..16 || binding.any { (key, value) ->
                !key.matches(Regex("[a-z][a-z0-9_]{0,63}")) ||
                    value.codePointCount(0, value.length) !in 1..256
            }) fail("invalid_transfer_binding")
        val encoded = canonical(JSONObject(binding))
        if (encoded.size > 16384) fail("transfer_binding_too_large")
        return hash(encoded)
    }

    // Android JSON does not guarantee sorted keys or Python-compatible ASCII
    // escaping. This small canonical encoder is part of the shared wire contract.
    fun canonical(value: Any): ByteArray = encode(value).toByteArray(Charsets.US_ASCII)

    private fun encode(value: Any): String = when (value) {
        is JSONObject -> value.keys().asSequence().sorted().joinToString(",", "{", "}") {
            "${quote(it)}:${encode(value.get(it))}"
        }
        is JSONArray -> (0 until value.length()).joinToString(",", "[", "]") { encode(value.get(it)) }
        is String -> quote(value)
        is Boolean -> value.toString()
        is Int, is Long -> value.toString()
        JSONObject.NULL -> "null"
        else -> fail("invalid_canonical_json")
    }

    private fun quote(value: String): String = buildString {
        append('"')
        value.forEach { char ->
            when (char) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\b' -> append("\\b")
                '\u000c' -> append("\\f")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (char.code < 32 || char.code >= 127) {
                    append("\\u").append(char.code.toString(16).padStart(4, '0'))
                } else append(char)
            }
        }
        append('"')
    }

    fun missing(value: String, count: Int): List<Int> {
        if (count !in 1..MAX_CHUNKS) fail("invalid_chunk_count")
        val bits = unhex(value, (count + 7) / 8)
        if (count % 8 != 0 && ((bits.last().toInt() and 255) ushr (count % 8)) != 0) {
            fail("invalid_missing_bitmap")
        }
        return (0 until count).filter { index -> (bits[index / 8].toInt() and (1 shl (index % 8))) != 0 }
    }

    fun aad(private: BlobPrivate, index: Int, plaintextSize: Int): ByteArray = ByteBuffer
        .allocate(domain.size + 16 + 32 + 8 + 4 + 32 + 4 + 4).order(ByteOrder.BIG_ENDIAN)
        .put(domain).put(unhex(private.blobId, 16)).put(unhex(private.bindingHash, 32))
        .putLong(private.size).putInt(CHUNK_BYTES).put(unhex(private.plaintextHash, 32))
        .putInt(index).putInt(plaintextSize).array()

    fun crypt(private: BlobPrivate, index: Int, bytes: ByteArray, encrypt: Boolean): ByteArray {
        val output = ByteArray(if (encrypt) bytes.size + TAG_BYTES else maxOf(0, bytes.size - TAG_BYTES))
        try {
            cryptInto(private, index, bytes, bytes.size, output, encrypt)
            return output
        } catch (error: Exception) { output.fill(0); throw error }
    }

    /** Caller-owned buffers keep worker memory independent of attachment length. */
    fun cryptInto(private: BlobPrivate, index: Int, bytes: ByteArray, length: Int, output: ByteArray,
        encrypt: Boolean, cipher: Cipher = Cipher.getInstance("AES/GCM/NoPadding")): Int {
        private.checkOpen()
        val count = maxOf(1L, (private.size + CHUNK_BYTES - 1) / CHUNK_BYTES).toInt()
        if (index !in 0 until count) fail("invalid_chunk_index")
        if (length !in 0..bytes.size) fail("invalid_chunk_size")
        val plaintextSize = if (encrypt) length else length - TAG_BYTES
        val expected = minOf(CHUNK_BYTES.toLong(), private.size - index.toLong() * CHUNK_BYTES).toInt()
        if (plaintextSize != expected) fail("invalid_chunk_size")
        val outputSize = if (encrypt) plaintextSize + TAG_BYTES else plaintextSize
        require(output.size >= outputSize && output !== bytes)
        val nonce = ByteBuffer.allocate(12).put(private.noncePrefix).putInt(index).array()
        val aad = aad(private, index, plaintextSize)
        return try {
            cipher.run {
                init(if (encrypt) Cipher.ENCRYPT_MODE else Cipher.DECRYPT_MODE,
                    SecretKeySpec(private.key, "AES"), GCMParameterSpec(128, nonce))
                updateAAD(aad)
                doFinal(bytes, 0, length, output, 0).also { check(it == outputSize) }
            }
        } catch (error: Exception) {
            output.fill(0)
            throw error
        } finally {
            nonce.fill(0)
            aad.fill(0)
        }
    }
}

internal class BlobPrivate(
    val blobId: String,
    val key: ByteArray,
    val noncePrefix: ByteArray,
    val size: Long,
    val plaintextHash: String,
    val bindingHash: String,
    var manifestHash: String
) : AutoCloseable {
    private var closed = false
    init {
        BlobProtocol.unhex(blobId, 16).fill(0)
        BlobProtocol.unhex(plaintextHash, 32).fill(0)
        BlobProtocol.unhex(bindingHash, 32).fill(0)
        require(key.size == 32 && noncePrefix.size == 8 && size in 0..BlobProtocol.MAX_FILE_BYTES)
    }

    fun checkOpen() { if (closed) BlobProtocol.fail("transfer_closed") }

    fun json(): JSONObject = JSONObject().also { checkOpen() }.put("version", BlobProtocol.VERSION)
        .put("blob_id", blobId).put("key", BlobProtocol.hex(key))
        .put("nonce_prefix", BlobProtocol.hex(noncePrefix)).put("size", size)
        .put("sha256", plaintextHash).put("binding_sha256", bindingHash)
        .put("manifest_sha256", manifestHash)

    override fun close() {
        closed = true
        key.fill(0)
        noncePrefix.fill(0)
    }

    companion object {
        fun parse(value: JSONObject, manifest: JSONObject): BlobPrivate {
            BlobProtocol.keys(value, setOf("version", "blob_id", "key", "nonce_prefix", "size",
                "sha256", "binding_sha256", "manifest_sha256"))
            BlobProtocol.integer(value, "version", 1, 1)
            val chunks = BlobProtocol.parseManifest(manifest)
            val size = BlobProtocol.integer(value, "size", 0, BlobProtocol.MAX_FILE_BYTES)
            if (chunks.sumOf { (it.size - BlobProtocol.TAG_BYTES).toLong() } != size) {
                BlobProtocol.fail("file_size_mismatch")
            }
            val root = BlobProtocol.string(value, "manifest_sha256")
            if (BlobProtocol.hash(BlobProtocol.canonical(manifest)) != root) BlobProtocol.fail("manifest_hash_mismatch")
            val key = BlobProtocol.unhex(BlobProtocol.string(value, "key"), 32)
            var nonce: ByteArray? = null
            try {
                nonce = BlobProtocol.unhex(BlobProtocol.string(value, "nonce_prefix"), 8)
                return BlobPrivate(BlobProtocol.string(value, "blob_id"), key, nonce, size,
                    BlobProtocol.string(value, "sha256"), BlobProtocol.string(value, "binding_sha256"), root)
            } catch (error: Exception) {
                key.fill(0)
                nonce?.fill(0)
                throw error
            }
        }
    }
}
