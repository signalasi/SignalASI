package com.galaxyssi.chat.blob

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import javax.crypto.spec.SecretKeySpec

class BlobArtifactReceivePipelineTest {
    @get:Rule val temporary = TemporaryFolder()
    private val key = SecretKeySpec(ByteArray(32) { (it + 17).toByte() }, "AES")
    private val bytes = "接收端断点恢复测试".repeat(100).toByteArray()
    private val events = mutableListOf<String>()
    private val storage get() = BlobArtifactStorage(File(temporary.root, "artifacts"), key)
    private var trusted = true
    private var published = true
    private var receiptQueued = true
    private var observed = true

    private fun manifest(): JSONObject = BlobArtifactContract.makeManifest(JSONObject()
        .put("client_route_id", "a".repeat(22)).put("desktop_id", "desktop").put("conversation_id", "中文测试会话")
        .put("task_id", "task").put("turn_id", "turn").put("contact_id", "contact").put("source_message_id", "source")
        .put("artifact_id", "b".repeat(64)).put("artifact_uri", "galaxyssi-artifact://task/file.bin")
        .put("name", "测试文件.bin").put("relative_path", "file.bin").put("mime_type", "application/octet-stream")
        .put("size_bytes", bytes.size).put("original_size_bytes", bytes.size)
        .put("sha256", BlobProtocol.hash(bytes)).put("original_sha256", BlobProtocol.hash(bytes))
        .put("execution_generation", 1).put("peer_chat", false))

    private fun body(): JSONObject {
        val manifest = manifest()
        val private = JSONObject().put("version", 1).put("blob_id", "c".repeat(32)).put("key", "d".repeat(64))
            .put("nonce_prefix", "e".repeat(16)).put("size", bytes.size).put("sha256", BlobProtocol.hash(bytes))
            .put("manifest_sha256", "f".repeat(64)).put("binding_sha256", BlobProtocol.bindingHash(BlobArtifactContract.binding(manifest)))
        val offer = JSONObject().put("type", BlobArtifactContract.OFFER_TYPE).put("version", 1).put("manifest", manifest).put("transport_revision", 1)
            .put("blob_offer", JSONObject().put("version", 1).put("relay", "https://blob.test")
                .put("private", private).put("read_token", "1".repeat(64)))
        return JSONObject().put("offer", offer).put("route_id", "a".repeat(22)).put("desktop_id", "desktop")
            .put("origin", "https://blob.test").put("peer_fingerprint", "2".repeat(64)).put("local_fingerprint", "3".repeat(64))
    }
    private fun work(phase: Int) = BlobArtifactReceiveWork(manifest().getString("transfer_id"), body(), phase, "claim", 0, "blob_expired")
    private fun store() {
        val manifest = manifest()
        BlobStaging.prepare(File(temporary.root, "source-stage"), bytes.size.toLong(), BlobProtocol.hash(bytes),
            BlobArtifactContract.binding(manifest), { bytes.inputStream() }, key).use { storage.ingest(it, manifest) }
    }
    private fun pipeline() = BlobArtifactReceivePipeline(File(temporary.root, "downloads"), storage,
        checkIdentity = { if (!trusted) throw BlobFailure("artifact_blob_identity_mismatch", 409) },
        publish = { events += "publish"; published },
        sendReceipt = { context, receipt ->
            assertFalse(context.getJSONObject("offer").has("blob_offer"))
            assertFalse(context.toString().contains("read_token"))
            assertEquals("stored", receipt.getString("status"))
            assertArrayEquals(BlobProtocol.canonical(BlobArtifactContract.storedReceipt(manifest())), BlobProtocol.canonical(receipt))
            events += "receipt"; receiptQueued
        }, observeFailure = { context, code ->
            assertFalse(context.getJSONObject("offer").has("blob_offer"))
            assertFalse(context.toString().contains("read_token"))
            assertEquals("blob_expired", code); events += "failure"; observed
        },
        client = { throw AssertionError("A verified local file must not be fetched again") }, storageKey = key)

    @Test fun `completed local copy recovers without network then publishes before receipt`() {
        store()
        val pipeline = pipeline()
        for (phase in 0..3) pipeline.process(work(phase), BlobCancellation()) {}
        assertEquals(listOf("publish", "receipt"), events)
        assertTrue(storage.verified(manifest()))
    }
    @Test fun `missing local copy cannot publish or authorize desktop cleanup`() {
        for (phase in listOf(BlobArtifactReceiveJournal.PUBLISH, BlobArtifactReceiveJournal.RECEIPT)) {
            assertEquals("artifact_blob_local_copy_missing", assertThrows(BlobFailure::class.java) {
                pipeline().process(work(phase), BlobCancellation()) {}
            }.code)
        }
        assertTrue(events.isEmpty())
    }
    @Test fun `corrupt local copy cannot produce stored receipt`() {
        store()
        File(temporary.root, "artifacts/blob-files/${manifest().getString("transfer_id")}.bin").writeBytes(byteArrayOf(1))
        assertEquals("artifact_blob_local_copy_missing", assertThrows(BlobFailure::class.java) {
            pipeline().process(work(BlobArtifactReceiveJournal.RECEIPT), BlobCancellation()) {}
        }.code)
        assertTrue(events.isEmpty())
    }
    @Test fun `revoked identity and stale claim block external side effects`() {
        store(); trusted = false
        assertEquals("artifact_blob_identity_mismatch", assertThrows(BlobFailure::class.java) {
            pipeline().process(work(BlobArtifactReceiveJournal.RECEIPT), BlobCancellation()) {}
        }.code)
        trusted = true
        assertEquals("stale_claim", assertThrows(BlobFailure::class.java) {
            pipeline().process(work(BlobArtifactReceiveJournal.PUBLISH), BlobCancellation()) { throw BlobFailure("stale_claim") }
        }.code)
        assertTrue(events.isEmpty())
    }
    @Test fun `publication failure leaves receipt unsent`() {
        store(); published = false
        assertEquals("artifact_blob_publication_pending", assertThrows(BlobFailure::class.java) {
            pipeline().process(work(BlobArtifactReceiveJournal.PUBLISH), BlobCancellation()) {}
        }.code)
        assertEquals(listOf("publish"), events)
    }
    @Test fun `receipt queue failure remains retryable with intact local file`() {
        store(); receiptQueued = false
        assertEquals("artifact_blob_receipt_pending", assertThrows(BlobFailure::class.java) {
            pipeline().process(work(BlobArtifactReceiveJournal.RECEIPT), BlobCancellation()) {}
        }.code)
        assertTrue(storage.verified(manifest()))
    }
    @Test fun `cancel stops before any publication`() {
        store()
        assertEquals("transfer_cancelled", assertThrows(BlobFailure::class.java) {
            pipeline().process(work(BlobArtifactReceiveJournal.RECEIPT), BlobCancellation().apply { cancel() }) {}
        }.code)
        assertTrue(events.isEmpty())
    }
    @Test fun `failure observation is local even after pair revocation and keeps diagnostic staging`() {
        trusted = false; observed = false
        val stage = File(temporary.root, "downloads/${manifest().getString("transfer_id")}").apply { mkdirs() }
        File(stage, "diagnostic").writeText("checkpoint")
        assertEquals("artifact_blob_observation_pending", assertThrows(BlobFailure::class.java) {
            pipeline().process(work(BlobArtifactReceiveJournal.OBSERVE_FAILURE), BlobCancellation()) {}
        }.code)
        assertTrue(stage.isDirectory)
        assertEquals(listOf("failure"), events)
    }
    @Test fun `discard cleans only its staging and preserves stored artifact and another transfer`() {
        store(); trusted = false
        val stage = File(temporary.root, "downloads/${manifest().getString("transfer_id")}").apply { mkdirs() }
        File(stage, "chunk").writeText("ciphertext")
        val other = File(temporary.root, "downloads/${"9".repeat(64)}").apply { mkdirs() }
        pipeline().process(work(BlobArtifactReceiveJournal.DISCARD), BlobCancellation()) {}
        assertFalse(stage.exists()); assertTrue(other.exists()); assertTrue(storage.verified(manifest()))
        assertTrue(events.isEmpty())
    }
    @Test fun `job binding rejects changed identity fields before any work`() {
        for (field in listOf("route_id", "desktop_id", "origin", "peer_fingerprint", "local_fingerprint")) {
            val changed = body().put(field, "different")
            assertThrows(Exception::class.java) { BlobArtifactReceiveJob.validate(changed) }
        }
        assertFalse(work(0).toString().contains("read_token"))
        assertEquals("artifact_blob_receive_failed", BlobArtifactReceiveJob.errorCode("secret https://blob.test"))
    }
}
