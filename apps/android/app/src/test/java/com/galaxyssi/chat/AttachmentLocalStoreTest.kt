package com.galaxyssi.chat

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AttachmentLocalStoreTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun `stored bytes are original with no local encryption or framing`() {
        for (size in listOf(0, 1, 65535, 65536, 65537, 300123)) {
            val bytes = ByteArray(size) { (it * 31).toByte() }
            val file = temporary.newFile("attachment-$size")
            AttachmentLocalStore.storeBytes(bytes, file)
            assertArrayEquals(bytes, file.readBytes())
            assertEquals(size.toLong(), AttachmentLocalStore.metadata(file).plaintextLength)
        }
    }

    @Test fun `invalid source lengths preserve existing destination and clean staging`() {
        for (length in listOf(2L, 4L)) {
            val file = temporary.newFile("existing-$length").apply { writeBytes(byteArrayOf(9)) }
            assertTrue(runCatching { AttachmentLocalStore.storeStream(byteArrayOf(1, 2, 3).inputStream(), length, file) }.isFailure)
            assertArrayEquals(byteArrayOf(9), file.readBytes())
        }
        assertFalse(temporary.root.listFiles()!!.any { it.name.endsWith(".storing") })
    }

    @Test fun `digest callback sees exact bytes and failure never commits`() {
        val target = temporary.root.resolve("verified")
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        val bytes = ByteArray(300000) { it.toByte() }
        AttachmentLocalStore.storeStream(bytes.inputStream(), bytes.size.toLong(), target) { buffer, count -> digest.update(buffer, 0, count) }
        assertArrayEquals(java.security.MessageDigest.getInstance("SHA-256").digest(bytes), digest.digest())
        assertTrue(runCatching { AttachmentLocalStore.storeStream(bytes.inputStream(), bytes.size.toLong(), target) { _, _ -> error("reject") } }.isFailure)
        assertArrayEquals(bytes, target.readBytes())
    }

    @Test fun `sequence is lazy and ordered`() {
        val first = temporary.newFile("first").apply { writeBytes(byteArrayOf(1, 2)) }
        val second = temporary.root.resolve("second")
        AttachmentLocalStore.openSequence(listOf(first, second)).use {
            assertEquals(1, it.read()); assertEquals(2, it.read())
            second.writeBytes(byteArrayOf(3, 4))
            assertArrayEquals(byteArrayOf(3, 4), it.readBytes())
        }
    }

    @Test fun `copy preserves the original file and bytes`() {
        val source = temporary.newFile("source").apply { writeBytes(byteArrayOf(1, 2)) }
        val output = java.io.ByteArrayOutputStream()
        assertEquals(2L, AttachmentLocalStore.copyTo(source, output))
        assertArrayEquals(byteArrayOf(1, 2), output.toByteArray())
        assertArrayEquals(byteArrayOf(1, 2), source.readBytes())
    }
}
