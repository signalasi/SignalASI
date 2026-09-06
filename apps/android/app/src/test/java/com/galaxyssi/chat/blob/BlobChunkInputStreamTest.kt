package com.galaxyssi.chat.blob

import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class BlobChunkInputStreamTest {
    @Test fun `loads lazily wipes previous chunk and erases last chunk on early close`() {
        val loaded = mutableListOf<ByteArray>()
        val stream = BlobChunkInputStream(3, 7) { index ->
            ByteArray(if (index == 2) 1 else 3) { (index + 1).toByte() }.also(loaded::add)
        }
        assertTrue(loaded.isEmpty())
        val first = ByteArray(3)
        assertEquals(3, stream.read(first))
        assertArrayEquals(byteArrayOf(1, 1, 1), first)
        assertEquals(1, loaded.size)
        assertEquals(2, stream.read())
        assertTrue(loaded.first().all { it == 0.toByte() })
        assertEquals(2, loaded.size)
        stream.close()
        assertTrue(loaded.all { bytes -> bytes.all { it == 0.toByte() } })
        assertEquals(2, loaded.size)
        assertThrows(IOException::class.java) { stream.read() }
    }

    @Test fun `declared total empty chunks and oversized chunks fail without retained plaintext`() {
        BlobChunkInputStream(1, 3) { byteArrayOf(1, 2) }.use { stream ->
            assertEquals(1, stream.read())
            assertEquals(2, stream.read())
            assertThrows(BlobFailure::class.java) { stream.read() }
        }
        listOf(ByteArray(0), ByteArray(5), ByteArray(1_048_577)).forEach { bytes ->
            bytes.fill(7)
            BlobChunkInputStream(1, 4) { bytes }.use { stream ->
                assertThrows(BlobFailure::class.java) { stream.read() }
                assertTrue(bytes.all { it == 0.toByte() })
            }
        }
    }

    @Test fun `sequential stream preserves boundaries and consumes no extra chunks`() {
        val loaded = mutableListOf<ByteArray>()
        BlobChunkInputStream(2, 5) { index ->
            (if (index == 0) byteArrayOf(0, -1) else byteArrayOf(3, 4, 5)).also(loaded::add)
        }.use { stream ->
            assertEquals(0, stream.read(ByteArray(0)))
            assertTrue(loaded.isEmpty())
            assertArrayEquals(byteArrayOf(0, -1, 3, 4, 5), stream.readBytes())
            assertEquals(-1, stream.read())
            assertTrue(loaded.all { bytes -> bytes.all { it == 0.toByte() } })
            assertThrows(IndexOutOfBoundsException::class.java) { stream.read(ByteArray(2), 1, Int.MAX_VALUE) }
        }
    }

    @Test fun `loader failure closes the stream and cannot silently skip a chunk`() {
        var calls = 0
        BlobChunkInputStream(2, 2) { calls++; throw IOException("test-read-failure") }.use { stream ->
            assertThrows(IOException::class.java) { stream.read() }
            assertThrows(IOException::class.java) { stream.read() }
            assertEquals(1, calls)
        }
    }
}
