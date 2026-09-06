package com.galaxyssi.chat.blob

import com.galaxyssi.chat.AttachmentAtRestCipher
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.RandomAccessFile
import javax.crypto.spec.SecretKeySpec

class BlobStagingTest {
    @get:Rule val temporary = TemporaryFolder()
    private val storageKey = SecretKeySpec(ByteArray(32) { (it + 1).toByte() }, "AES")
    private val binding = mapOf("client_route_id" to "route-a", "conversation_id" to "conversation-a", "turn_id" to "turn-a")

    @Test fun `preparation reuses one plaintext buffer and clears it on success and interruption`() {
        val data = ByteArray(3 * BlobProtocol.CHUNK_BYTES + 73) { (it % 251).toByte() }
        listOf(false, true).forEach { interrupt ->
            var captured: ByteArray? = null
            var reads = 0
            val source = object : java.io.ByteArrayInputStream(data) {
                override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                    if (captured == null) captured = buffer else assertSame(captured, buffer)
                    assertEquals(BlobProtocol.CHUNK_BYTES, buffer.size)
                    reads++
                    return super.read(buffer, offset, length)
                }
            }
            val directory = File(temporary.root, "reuse-$interrupt")
            val prepare = {
                BlobStaging.prepare(directory, data.size.toLong(), BlobProtocol.hash(data), binding,
                    { source }, storageKey, checkCancelled = {
                        if (interrupt && reads == 2) throw BlobFailure("transfer_cancelled", 499)
                    })
            }
            if (interrupt) {
                assertEquals("transfer_cancelled", assertThrows(BlobFailure::class.java) { prepare() }.code)
                assertFalse(BlobStaging.exists(directory))
            } else prepare().use { staged ->
                assertEquals(4, staged.chunks.size)
                assertEquals(89, staged.chunks.last().size)
                val output = ByteArrayOutputStream()
                staged.copyPlaintext(output, binding)
                assertArrayEquals(data, output.toByteArray())
            }
            assertNotNull(captured)
            assertTrue(captured!!.all { it == 0.toByte() })
        }
    }

    @Test fun `empty exact and partial files round trip without plaintext staging`() {
        listOf(0, 1_048_576, 1_048_597).forEach { size ->
            val bytes = ByteArray(size) { (it % 131).toByte() }
            val path = File(temporary.root, "sender-$size")
            BlobStaging.prepare(path, size.toLong(), BlobProtocol.hash(bytes), binding, { bytes.inputStream() }, storageKey).use { staged ->
                assertEquals(maxOf(1, (size + 1_048_575) / 1_048_576), staged.chunks.size)
                val output = ByteArrayOutputStream()
                assertEquals(size.toLong(), staged.copyPlaintext(output, binding))
                assertArrayEquals(bytes, output.toByteArray())
                val state = File(path, BlobStaging.STATE_FILE)
                assertTrue(AttachmentAtRestCipher.isEncrypted(state))
                assertFalse(state.readBytes().toString(Charsets.ISO_8859_1).contains(BlobProtocol.hex(staged.private.key)))
                assertTrue(path.listFiles()!!.all { it.name.endsWith(".blob") || it.name == BlobStaging.STATE_FILE })
            }
            BlobStaging.open(path, binding, storageKey).use { reopened ->
                assertTrue(reopened.chunks.indices.all(reopened::hasChunk))
                assertEquals(BlobProtocol.hash(bytes), reopened.private.plaintextHash)
            }
        }
    }

    @Test fun `source changed truncated or enlarged never commits a usable checkpoint`() {
        val expected = byteArrayOf(1, 2, 3)
        listOf(byteArrayOf(1, 2), byteArrayOf(1, 2, 3, 4), byteArrayOf(1, 2, 4)).forEachIndexed { index, bytes ->
            val directory = File(temporary.root, "changed-$index")
            assertThrows(BlobFailure::class.java) {
                BlobStaging.prepare(directory, 3, BlobProtocol.hash(expected), binding, { bytes.inputStream() }, storageKey)
            }
            assertFalse(BlobStaging.exists(directory))
            assertThrows(java.nio.file.FileAlreadyExistsException::class.java) {
                BlobStaging.prepare(directory, 3, BlobProtocol.hash(expected), binding, { expected.inputStream() }, storageKey)
            }
        }
    }

    @Test fun `short reads and zero reads still form exact fixed-size chunks`() {
        val bytes = ByteArray(1_048_591) { (it % 71).toByte() }
        var offset = 0
        var zero = true
        val source = object : InputStream() {
            override fun read(): Int = if (offset == bytes.size) -1 else bytes[offset++].toInt() and 255
            override fun read(buffer: ByteArray, start: Int, length: Int): Int {
                if (zero) { zero = false; return 0 }
                val count = minOf(37, length, bytes.size - offset)
                if (count == 0) return -1
                bytes.copyInto(buffer, start, offset, offset + count)
                offset += count
                return count
            }
        }
        BlobStaging.prepare(File(temporary.root, "short"), bytes.size.toLong(), BlobProtocol.hash(bytes), binding,
            { source }, storageKey).use { staged -> assertEquals(listOf(1_048_592, 31), staged.chunks.map { it.size }) }
    }

    @Test fun `checkpoint reopens remote capabilities and stale owner reloads under lock`() {
        val directory = File(temporary.root, "owner")
        val data = byteArrayOf(1)
        BlobStaging.prepare(directory, 1, BlobProtocol.hash(data), binding, { data.inputStream() }, storageKey).use { first ->
            BlobStaging.open(directory, binding, storageKey).use { second ->
                first.exclusive {
                    first.remote.put("read_token", "a".repeat(64))
                    first.save()
                    assertEquals("transfer_busy", assertThrows(BlobFailure::class.java) { second.exclusive {} }.code)
                }
                second.exclusive { assertEquals("a".repeat(64), second.remote.getString("read_token")) }
                assertThrows(IllegalStateException::class.java) { second.save() }
            }
        }
        assertEquals("transfer_binding_mismatch", assertThrows(BlobFailure::class.java) {
            BlobStaging.open(directory, binding + ("turn_id" to "wrong-turn"), storageKey)
        }.code)
    }

    @Test fun `corrupt or oversized encrypted checkpoint is not reset`() {
        val directory = File(temporary.root, "corrupt")
        BlobStaging.prepare(directory, 0, BlobProtocol.hash(byteArrayOf()), binding, { byteArrayOf().inputStream() }, storageKey).close()
        val file = File(directory, BlobStaging.STATE_FILE)
        val original = file.readBytes()
        file.writeBytes(original.copyOf().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() })
        assertThrows(Exception::class.java) { BlobStaging.open(directory, binding, storageKey) }
        assertTrue(file.exists())
        RandomAccessFile(file, "rw").use { it.setLength(300_000) }
        assertEquals("invalid_transfer_checkpoint", assertThrows(BlobFailure::class.java) {
            BlobStaging.open(directory, binding, storageKey)
        }.code)
    }

    @Test fun `receiver repairs missing corrupt ciphertext and rejects cross-file chunks`() {
        val fixture = BlobProtocolTest.fixture()
        val bindingJson = fixture.getJSONObject("binding")
        val vectorBinding = bindingJson.keys().asSequence().associateWith(bindingJson::getString)
        BlobStaging.receive(File(temporary.root, "receiver"), fixture.getJSONObject("private"),
            fixture.getJSONObject("manifest"), JSONObject(), vectorBinding, storageKey).use { staged ->
            val data = BlobProtocol.unhex(fixture.getString("ciphertext_hex"), 34)
            staged.exclusive {
                assertFalse(staged.hasChunk(0))
                assertThrows(BlobFailure::class.java) { staged.storeChunk(0, data.copyOf().also { it[0]++ }) }
                staged.storeChunk(0, data)
                assertTrue(staged.hasChunk(0))
                val path = File(staged.directory, "00000000.blob")
                path.writeBytes(byteArrayOf(1))
                assertFalse(staged.hasChunk(0))
                staged.storeChunk(0, data)
                val output = ByteArrayOutputStream()
                staged.copyPlaintext(output, vectorBinding)
                assertEquals(fixture.getString("plaintext_hex"), BlobProtocol.hex(output.toByteArray()))
            }
        }
    }

    @Test fun `malformed private JSON never escapes through parser exception text`() {
        val directory = temporary.newFolder("invalid-json")
        val secret = "test-only-private-capability"
        AttachmentAtRestCipher.encryptBytes("{\"private\":\"$secret".toByteArray(),
            File(directory, BlobStaging.STATE_FILE), storageKey)
        val error = assertThrows(BlobFailure::class.java) { BlobStaging.open(directory, binding, storageKey) }
        assertEquals("invalid_transfer_checkpoint", error.code)
        assertFalse(error.toString().contains(secret))
        assertNull(error.cause)
    }
}
