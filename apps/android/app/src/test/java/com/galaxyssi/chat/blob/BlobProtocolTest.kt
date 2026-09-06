package com.galaxyssi.chat.blob

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import javax.crypto.Cipher
import javax.crypto.AEADBadTagException

class BlobProtocolTest {
    @Test fun `caller buffers preserve shared ciphertext and never write the unused tail`() {
        val fixture = fixture()
        BlobPrivate.parse(fixture.getJSONObject("private"), fixture.getJSONObject("manifest")).use { private ->
            val plain = BlobProtocol.unhex(fixture.getString("plaintext_hex"), private.size.toInt())
            val input = plain.copyOf(1024).also { it.fill(99, plain.size) }
            val output = ByteArray(1024) { 42 }
            val count = BlobProtocol.cryptInto(private, 0, input, plain.size, output, true)
            assertEquals(plain.size + BlobProtocol.TAG_BYTES, count)
            assertEquals(fixture.getString("ciphertext_hex"), BlobProtocol.hex(output.copyOf(count)))
            assertTrue(output.drop(count).all { it == 42.toByte() })
            assertEquals(BlobProtocol.hash(output.copyOf(count)), BlobProtocol.hash(output, count))
            val decrypted = ByteArray(1024) { 43 }
            assertEquals(plain.size, BlobProtocol.cryptInto(private, 0, output, count, decrypted, false))
            assertArrayEquals(plain, decrypted.copyOf(plain.size))
            assertTrue(decrypted.drop(plain.size).all { it == 43.toByte() })
            output[0] = (output[0].toInt() xor 1).toByte()
            assertThrows(AEADBadTagException::class.java) {
                BlobProtocol.cryptInto(private, 0, output, count, decrypted, false)
            }
            assertTrue(decrypted.all { it == 0.toByte() })
            assertThrows(BlobFailure::class.java) { BlobProtocol.cryptInto(private, 0, input, -1, output, true) }
            assertThrows(BlobFailure::class.java) { BlobProtocol.cryptInto(private, 0, input, input.size + 1, output, true) }
            assertThrows(IllegalArgumentException::class.java) {
                BlobProtocol.cryptInto(private, 0, input, plain.size, ByteArray(count - 1), true)
            }
        }
    }

    @Test fun `cipher reuse across full and short chunks matches independent encryption`() {
        val size = BlobProtocol.CHUNK_BYTES.toLong() + 73
        BlobPrivate("a".repeat(32), ByteArray(32) { it.toByte() }, ByteArray(8) { it.toByte() },
            size, "b".repeat(64), "c".repeat(64), "").use { private ->
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            val buffer = ByteArray(BlobProtocol.CHUNK_BYTES) { (it % 251).toByte() }
            val output = ByteArray(buffer.size + BlobProtocol.TAG_BYTES)
            listOf(buffer.size, 73).forEachIndexed { index, length ->
                val count = BlobProtocol.cryptInto(private, index, buffer, length, output, true, cipher)
                assertArrayEquals(BlobProtocol.crypt(private, index, buffer.copyOf(length), true), output.copyOf(count))
            }
        }
    }

    @Test fun `shared Python vector matches Android binding AAD ciphertext and plaintext`() {
        val fixture = fixture()
        val bindingJson = fixture.getJSONObject("binding")
        val binding = bindingJson.keys().asSequence().associateWith(bindingJson::getString)
        val manifest = fixture.getJSONObject("manifest")
        BlobPrivate.parse(fixture.getJSONObject("private"), manifest).use { private ->
            val plain = BlobProtocol.unhex(fixture.getString("plaintext_hex"), private.size.toInt())
            val encrypted = BlobProtocol.unhex(fixture.getString("ciphertext_hex"), plain.size + 16)
            assertEquals(private.bindingHash, BlobProtocol.bindingHash(binding))
            assertEquals(private.manifestHash, BlobProtocol.hash(BlobProtocol.canonical(manifest)))
            assertEquals(fixture.getString("aad_hex"), BlobProtocol.hex(BlobProtocol.aad(private, 0, plain.size)))
            assertArrayEquals(encrypted, BlobProtocol.crypt(private, 0, plain, true))
            assertArrayEquals(plain, BlobProtocol.crypt(private, 0, encrypted, false))
        }
    }

    @Test fun `canonical JSON sorts keys and escapes unicode as Python does`() {
        val json = JSONObject().put("z", "/\u007f\ud83d\ude00").put("a", "\n\t\b\u000c\r\"\\")
        assertEquals("{\"a\":\"\\n\\t\\b\\f\\r\\\"\\\\\",\"z\":\"/\\u007f\\ud83d\\ude00\"}",
            BlobProtocol.canonical(json).toString(Charsets.US_ASCII))
        assertThrows(BlobFailure::class.java) { BlobProtocol.canonical(JSONObject().put("x", 1.5)) }
    }

    @Test fun `binding counts unicode code points but also enforces encoded limit`() {
        val value = "\ud83d\ude00".repeat(256)
        assertEquals(64, BlobProtocol.bindingHash(mapOf("text" to value)).length)
        assertThrows(BlobFailure::class.java) { BlobProtocol.bindingHash(mapOf("text" to value + "a")) }
        assertEquals("transfer_binding_too_large", assertThrows(BlobFailure::class.java) {
            BlobProtocol.bindingHash((0..15).associate { "key_$it" to value })
        }.code)
        assertThrows(BlobFailure::class.java) { BlobProtocol.bindingHash(mapOf("../bad" to "value")) }
    }

    @Test fun `manifest rejects extra fields noninteger sizes and incorrect chunk layout`() {
        val chunk = BlobChunk("a".repeat(64), 16)
        assertEquals(listOf(chunk), BlobProtocol.parseManifest(BlobProtocol.manifest(listOf(chunk))))
        listOf<Any>(true, 16.0, "16", -1, 1_048_593).forEach { bad ->
            val json = BlobProtocol.manifest(listOf(chunk))
            json.getJSONArray("chunks").getJSONObject(0).put("size", bad)
            assertThrows(BlobFailure::class.java) { BlobProtocol.parseManifest(json) }
        }
        assertThrows(BlobFailure::class.java) {
            BlobProtocol.parseManifest(BlobProtocol.manifest(listOf(chunk)).put("name", "not-public"))
        }
        assertThrows(BlobFailure::class.java) { BlobProtocol.parseManifest(BlobProtocol.manifest(listOf(chunk, chunk))) }
        assertThrows(BlobFailure::class.java) {
            BlobProtocol.parseManifest(BlobProtocol.manifest(listOf(BlobChunk(chunk.sha256, 1_048_592), chunk)))
        }
        assertThrows(BlobFailure::class.java) { BlobProtocol.parseManifest(JSONObject().put("version", 1).put("chunks", JSONArray())) }
    }

    @Test fun `bitmap is little bit order with strict count and spare bits`() {
        assertEquals(listOf(0, 2, 8), BlobProtocol.missing("0501", 9))
        assertEquals(emptyList<Int>(), BlobProtocol.missing("0000", 9))
        assertThrows(BlobFailure::class.java) { BlobProtocol.missing("0002", 9) }
        assertThrows(BlobFailure::class.java) { BlobProtocol.missing("ff", 9) }
        assertThrows(BlobFailure::class.java) { BlobProtocol.missing("FF", 8) }
    }

    @Test fun `private metadata rejects wrong root file size and fields`() {
        val fixture = fixture()
        val manifest = fixture.getJSONObject("manifest")
        listOf("size" to 19, "version" to 2, "key" to "a".repeat(63), "manifest_sha256" to "a".repeat(64)).forEach { (key, value) ->
            val json = JSONObject(fixture.getJSONObject("private").toString()).put(key, value)
            assertThrows(BlobFailure::class.java) { BlobPrivate.parse(json, manifest) }
        }
    }

    @Test fun `changed binding fails AEAD and closed keys cannot be reused`() {
        val fixture = fixture()
        val descriptor = fixture.getJSONObject("private").put("binding_sha256", "b".repeat(64))
        val private = BlobPrivate.parse(descriptor, fixture.getJSONObject("manifest"))
        val encrypted = BlobProtocol.unhex(fixture.getString("ciphertext_hex"), 34)
        assertThrows(AEADBadTagException::class.java) { BlobProtocol.crypt(private, 0, encrypted, false) }
        assertThrows(BlobFailure::class.java) { BlobProtocol.crypt(private, 1, encrypted, false) }
        assertThrows(BlobFailure::class.java) { BlobProtocol.crypt(private, 0, encrypted.copyOf(33), false) }
        private.close()
        assertTrue(private.key.all { it == 0.toByte() })
        assertThrows(BlobFailure::class.java) { private.json() }
        assertThrows(BlobFailure::class.java) { BlobProtocol.crypt(private, 0, encrypted, false) }
    }

    companion object {
        fun fixture(): JSONObject {
            val root = generateSequence(File(requireNotNull(System.getProperty("user.dir")))) { it.parentFile }
                .first { File(it, "core/protocol/fixtures/blob-aead-v1.json").isFile }
            return JSONObject(File(root, "core/protocol/fixtures/blob-aead-v1.json").readText())
        }
    }
}
