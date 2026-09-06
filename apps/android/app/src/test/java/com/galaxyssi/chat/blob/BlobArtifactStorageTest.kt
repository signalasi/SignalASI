package com.galaxyssi.chat.blob

import com.galaxyssi.chat.AttachmentLocalStore
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.crypto.spec.SecretKeySpec

class BlobArtifactStorageTest {
    @get:Rule val temporary = TemporaryFolder()
    private val key = SecretKeySpec(ByteArray(32) { (it + 21).toByte() }, "AES")
    private val bytes = ByteArray(2 * BlobProtocol.CHUNK_BYTES + 57) { (it % 251).toByte() }
    private val root get() = File(temporary.root, "artifacts")

    private fun manifest(generation: Int = 1, uri: String = "galaxyssi-artifact://task/file.bin"): JSONObject {
        val metadata = JSONObject().put("client_route_id", "a".repeat(22)).put("conversation_id", "conversation")
            .put("task_id", "task").put("turn_id", "turn").put("contact_id", "contact")
            .put("source_message_id", "message").put("desktop_id", "desktop")
            .put("artifact_id", "b".repeat(64)).put("artifact_uri", uri).put("name", "returned file.bin")
            .put("relative_path", "file.bin").put("mime_type", "application/octet-stream")
            .put("sha256", BlobProtocol.hash(bytes)).put("original_sha256", BlobProtocol.hash(bytes))
            .put("size_bytes", bytes.size).put("original_size_bytes", bytes.size)
            .put("execution_generation", generation).put("peer_chat", false)
        return BlobArtifactContract.makeManifest(metadata)
    }

    private fun staging(manifest: JSONObject, name: String = "staging") = BlobStaging.prepare(
        File(temporary.root, name), bytes.size.toLong(), BlobProtocol.hash(bytes),
        BlobArtifactContract.binding(manifest), { bytes.inputStream() }, key)

    @Test fun `damaged authenticated metadata reports terminal corruption rather than endless network retries`() {
        val manifest = manifest()
        staging(manifest).use { storage -> BlobArtifactStorage(root, key).ingest(storage, manifest) }
        val record = File(root, "blob-metadata").listFiles()!!.single()
        val encoded = record.readBytes()
        encoded[encoded.lastIndex] = (encoded.last().toInt() xor 1).toByte()
        for (damaged in listOf(encoded, byteArrayOf(1, 2), encoded.copyOfRange(0, 30))) {
            record.writeBytes(damaged)
            assertEquals("artifact_blob_record_invalid", assertThrows(BlobFailure::class.java) {
                BlobArtifactStorage(root, key).verified(manifest)
            }.code)
        }
    }

    @Test fun `verified attachment stays byte exact while recovery metadata stays encrypted`() {
        val manifest = manifest()
        staging(manifest).use { staged ->
            val store = BlobArtifactStorage(root, key)
            val record = store.ingest(staged, manifest)
            assertEquals(manifest.getString("transfer_id"), record.getString("transfer_id"))
            val file = File(root, record.getString("relative_file"))
            assertArrayEquals(bytes, AttachmentLocalStore.readBytes(file))
            assertEquals(bytes.size.toLong(), file.length())
            assertArrayEquals(bytes, file.readBytes())
            val records = File(root, "blob-metadata").listFiles()!!
            assertEquals(1, records.size)
            assertTrue(BlobCheckpointCipher.isEncrypted(records.single()))
            assertFalse(records.single().readBytes().toString(Charsets.ISO_8859_1).contains("returned file"))
            val reopened = BlobArtifactStorage(root, key).readRecord(manifest.getString("artifact_uri"))!!
            assertEquals(record.toString(), reopened.toString())
            assertEquals(record.toString(), store.ingest(staged, manifest).toString())
            assertEquals(1, File(root, "blob-files").listFiles()!!.size)
        }
    }

    @Test fun `same URI in another generation cannot overwrite an existing artifact`() {
        val first = manifest()
        val second = manifest(2)
        val store = BlobArtifactStorage(root, key)
        staging(first).use { store.ingest(it, first) }
        staging(second, "second").use { staged ->
            assertEquals("artifact_blob_uri_conflict", assertThrows(BlobFailure::class.java) {
                store.ingest(staged, second)
            }.code)
        }
        assertEquals(first.getString("transfer_id"), store.readRecord(first.getString("artifact_uri"))!!.getString("transfer_id"))
    }

    @Test fun `saving to Downloads keeps version and metadata encrypted across duplicate offers`() {
        val manifest = manifest()
        staging(manifest).use { staged ->
            val store = BlobArtifactStorage(root, key)
            store.ingest(staged, manifest)
            val uri = manifest.getString("artifact_uri")
            assertThrows(BlobFailure::class.java) { store.markSaved(uri, "f".repeat(64), "content://downloads/1", 123) }
            store.markSaved(uri, manifest.getString("transfer_id"), "content://downloads/1", 123)
            val record = BlobArtifactStorage(root, key).readRecord(uri)!!
            assertTrue(record.getBoolean("saved_to_downloads"))
            assertEquals("content://downloads/1", record.getString("saved_uri"))
            assertEquals(record.toString(), store.ingest(staged, manifest).toString())
            assertTrue(File(root, "blob-metadata").listFiles()!!.all(BlobCheckpointCipher::isEncrypted))
        }
    }

    @Test fun `wrong binding or corrupted chunk never creates a committed record`() {
        val manifest = manifest()
        staging(manifest).use { staged ->
            val store = BlobArtifactStorage(root, key)
            assertThrows(BlobFailure::class.java) { store.ingest(staged, manifest(2)) }
            File(staged.directory, "00000001.blob").writeBytes(byteArrayOf(1, 2))
            assertEquals("local_chunk_missing_or_corrupt", assertThrows(BlobFailure::class.java) {
                store.ingest(staged, manifest)
            }.code)
            assertNull(store.readRecord(manifest.getString("artifact_uri")))
            assertTrue(File(root, "blob-files").listFiles()!!.isEmpty())
        }
    }

    @Test fun `cancellation after streaming but before publication leaves no readable record`() {
        val manifest = manifest()
        staging(manifest).use { staged ->
            val store = BlobArtifactStorage(root, key)
            assertEquals("transfer_cancelled", assertThrows(BlobFailure::class.java) {
                store.ingest(staged, manifest) {
                    if (File(root, "blob-files").listFiles().orEmpty().any { it.name.startsWith(".incoming-") && !it.name.endsWith(".encrypting") }) {
                        throw BlobFailure("transfer_cancelled", 499)
                    }
                }
            }.code)
            assertNull(store.readRecord(manifest.getString("artifact_uri")))
            assertTrue(File(root, "blob-files").listFiles()!!.isEmpty())
            assertNotNull(store.ingest(staged, manifest))
        }
    }

    @Test fun `duplicate repairs corrupt local attachment before acknowledging persistence`() {
        val manifest = manifest()
        staging(manifest).use { staged ->
            val store = BlobArtifactStorage(root, key)
            val record = store.ingest(staged, manifest)
            val file = File(root, record.getString("relative_file"))
            val corrupted = file.readBytes().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() }
            file.writeBytes(corrupted)
            store.ingest(staged, manifest)
            assertArrayEquals(bytes, AttachmentLocalStore.readBytes(file))
        }
    }

    @Test fun `a busy artifact does not block a different URI or metadata readers`() {
        val manifest = manifest()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val executor = Executors.newSingleThreadExecutor()
        staging(manifest).use { staged ->
            val store = BlobArtifactStorage(root, key)
            val future = executor.submit<JSONObject> {
                var first = true
                store.ingest(staged, manifest) {
                    if (first) { first = false; entered.countDown(); check(release.await(10, TimeUnit.SECONDS)) }
                }
            }
            try {
                assertTrue(entered.await(5, TimeUnit.SECONDS))
                assertNull(store.readRecord(manifest.getString("artifact_uri")))
                assertEquals("transfer_busy", assertThrows(BlobFailure::class.java) { store.ingest(staged, manifest) }.code)
                val other = manifest(uri = "galaxyssi-artifact://other/file.bin")
                staging(other, "other").use { assertNotNull(store.ingest(it, other)) }
                release.countDown()
                assertNotNull(future.get(10, TimeUnit.SECONDS))
            } finally { release.countDown(); executor.shutdownNow(); executor.awaitTermination(10, TimeUnit.SECONDS) }
        }
    }

    @Test fun `swapped encrypted records are rejected rather than crossing artifact scopes`() {
        val manifest = manifest()
        val other = manifest(uri = "galaxyssi-artifact://other/file.bin")
        val store = BlobArtifactStorage(root, key)
        staging(manifest).use { store.ingest(it, manifest) }
        staging(other, "other").use { store.ingest(it, other) }
        fun record(value: JSONObject) = File(root, "blob-metadata/" +
            BlobProtocol.hash(value.getString("artifact_uri").toByteArray()) + ".sasie")
        record(manifest).copyTo(record(other), overwrite = true)
        assertEquals("artifact_blob_record_invalid", assertThrows(BlobFailure::class.java) {
            store.readRecord(other.getString("artifact_uri"))
        }.code)
    }
}
