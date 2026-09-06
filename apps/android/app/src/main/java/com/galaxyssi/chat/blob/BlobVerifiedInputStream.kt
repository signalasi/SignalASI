package com.galaxyssi.chat.blob

import java.io.InputStream
import java.io.IOException
import java.security.MessageDigest

/** EOF is successful only after the whole file hash and length have matched. */
internal class BlobVerifiedInputStream(
    private val source: InputStream,
    private val expectedSize: Long,
    private val expectedHash: String,
    private val checkCancelled: () -> Unit = {}
) : InputStream() {
    private val digest = MessageDigest.getInstance("SHA-256")
    private var total = 0L
    private var verified = false
    private var closed = false

    override fun read(): Int {
        val one = ByteArray(1)
        return try { if (read(one, 0, 1) < 0) -1 else one[0].toInt() and 255 }
            finally { one.fill(0) }
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (offset < 0 || length < 0 || offset > buffer.size - length) throw IndexOutOfBoundsException()
        if (closed) throw IOException("Blob stream is closed")
        if (length == 0) return 0
        try {
            checkCancelled()
            if (verified) return -1
            val count = source.read(buffer, offset, length)
            if (count < 0) {
                if (total != expectedSize || BlobProtocol.hex(digest.digest()) != expectedHash) {
                    throw BlobFailure("plaintext_hash_mismatch", 409)
                }
                verified = true
            } else {
                total += count
                if (total > expectedSize) throw BlobFailure("plaintext_hash_mismatch", 409)
                digest.update(buffer, offset, count)
            }
            return count
        } catch (error: Exception) {
            buffer.fill(0, offset, offset + length)
            try { close() } catch (closing: Exception) { error.addSuppressed(closing) }
            throw error
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        digest.reset()
        source.close()
    }
}
