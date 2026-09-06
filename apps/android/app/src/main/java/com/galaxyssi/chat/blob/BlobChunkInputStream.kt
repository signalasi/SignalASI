package com.galaxyssi.chat.blob

import java.io.IOException
import java.io.InputStream

/** Loads only one existing at-rest chunk at a time and erases it before moving on. */
internal class BlobChunkInputStream(
    private val count: Int,
    private val expectedSize: Long,
    private val load: (Int) -> ByteArray
) : InputStream() {
    private var chunk = ByteArray(0)
    private var offset = 0
    private var next = 0
    private var loaded = 0L
    private var closed = false

    init { require(count > 0 && expectedSize > 0) }

    private fun availableChunk(): Boolean {
        if (closed) throw IOException("Attachment stream is closed")
        if (offset < chunk.size) return true
        chunk.fill(0)
        chunk = ByteArray(0)
        offset = 0
        if (next == count) {
            if (loaded != expectedSize) { close(); throw BlobFailure("source_changed", 409) }
            return false
        }
        val bytes = try { load(next) } catch (error: Exception) { close(); throw error }
        next++
        if (bytes.isEmpty() || bytes.size > BlobProtocol.CHUNK_BYTES || loaded + bytes.size > expectedSize) {
            bytes.fill(0)
            close()
            throw BlobFailure("source_changed", 409)
        }
        chunk = bytes
        loaded += bytes.size
        return true
    }

    override fun read(): Int {
        if (!availableChunk()) return -1
        return chunk[offset++].toInt() and 255
    }

    override fun read(buffer: ByteArray, start: Int, length: Int): Int {
        if (start < 0 || length < 0 || start > buffer.size - length) throw IndexOutOfBoundsException()
        if (length == 0) return 0
        if (!availableChunk()) return -1
        val size = minOf(length, chunk.size - offset)
        chunk.copyInto(buffer, start, offset, offset + size)
        offset += size
        return size
    }

    override fun close() {
        closed = true
        chunk.fill(0)
        chunk = ByteArray(0)
    }
}
