package com.galaxyssi.chat.blob

import org.junit.Assert.*
import org.junit.Test

class BlobVerifiedInputStreamTest {
    @Test fun `EOF checks length and hash for byte and buffer reads including empty input`() {
        listOf(byteArrayOf(), byteArrayOf(1, 2, 3)).forEach { bytes ->
            BlobVerifiedInputStream(bytes.inputStream(), bytes.size.toLong(), BlobProtocol.hash(bytes)).use { stream ->
                bytes.forEach { assertEquals(it.toInt(), stream.read()) }
                assertEquals(-1, stream.read())
                assertEquals(-1, stream.read(ByteArray(4)))
            }
            BlobVerifiedInputStream(bytes.inputStream(), bytes.size.toLong(), BlobProtocol.hash(bytes)).use { stream ->
                assertArrayEquals(bytes, stream.readBytes())
            }
        }
    }

    @Test fun `wrong hash truncation and extra bytes fail and close the stream`() {
        val bytes = byteArrayOf(1, 2, 3)
        listOf(2L to BlobProtocol.hash(bytes), 4L to BlobProtocol.hash(bytes), 3L to "a".repeat(64)).forEach { (size, hash) ->
            val stream = BlobVerifiedInputStream(bytes.inputStream(), size, hash)
            assertEquals("plaintext_hash_mismatch", assertThrows(BlobFailure::class.java) { stream.readBytes() }.code)
            assertThrows(java.io.IOException::class.java) { stream.read() }
        }
    }

    @Test fun `cancellation erases the active plaintext chunk and caller buffer`() {
        val bytes = byteArrayOf(1, 2, 3)
        var cancel = false
        val chunked = BlobChunkInputStream(1, 3) { bytes }
        val stream = BlobVerifiedInputStream(chunked, 3, BlobProtocol.hash(bytes)) {
            if (cancel) throw BlobFailure("transfer_cancelled", 499)
        }
        assertEquals(1, stream.read())
        cancel = true
        val output = ByteArray(2) { 7 }
        assertThrows(BlobFailure::class.java) { stream.read(output) }
        assertArrayEquals(ByteArray(3), bytes)
        assertArrayEquals(ByteArray(2), output)
    }

    @Test fun `close failure cannot hide the read failure and close is idempotent`() {
        val original = java.io.IOException("read failed")
        var closes = 0
        val source = object : java.io.InputStream() {
            override fun read(): Int = throw original
            override fun close() { closes++; throw IllegalArgumentException("close failed") }
        }
        val stream = BlobVerifiedInputStream(source, 1, "a".repeat(64))
        assertSame(original, assertThrows(java.io.IOException::class.java) { stream.read() })
        assertEquals(1, original.suppressed.size)
        stream.close()
        assertEquals(1, closes)
    }
}
