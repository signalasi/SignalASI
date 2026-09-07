package com.galaxyssi.chat.blob

import okhttp3.OkHttpClient
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.security.KeyStore
import java.security.cert.CertificateFactory
import javax.crypto.spec.SecretKeySpec
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

class BlobArtifactRevisionHttpsTest {
    @Test fun `expired partial transfer rekeys into a new staging revision over real HTTPS`() {
        val fixtureRoot = System.getenv("GALAXYSSI_BLOB_TEST_ROOT").orEmpty()
        assumeTrue("Requires the isolated cross-runtime test runner", fixtureRoot.isNotEmpty())
        val root = File(fixtureRoot)
        val fixture = JSONObject(File(root, "fixture.json").readText())
        val origin = fixture.getJSONObject("offer").getString("relay")
        val source = File(root, "source.bin")
        val manifest = BlobArtifactContract.makeManifest(JSONObject().put("client_route_id", "a".repeat(22))
            .put("desktop_id", "desktop").put("conversation_id", "中文过期重建测试").put("task_id", "task")
            .put("turn_id", "turn").put("contact_id", "contact").put("source_message_id", "message")
            .put("artifact_id", "b".repeat(64)).put("artifact_uri", "galaxyssi-artifact://task/rekey.bin")
            .put("name", "重新下载.bin").put("relative_path", "rekey.bin").put("mime_type", "application/octet-stream")
            .put("size_bytes", source.length()).put("original_size_bytes", source.length())
            .put("sha256", fixture.getString("sha256")).put("original_sha256", fixture.getString("sha256"))
            .put("execution_generation", 1).put("peer_chat", false))
        val id = manifest.getString("transfer_id")
        val key = SecretKeySpec(ByteArray(32) { (it + 53).toByte() }, "AES")
        val binding = BlobArtifactContract.binding(manifest)
        val client = BlobTransferClient(BlobHttp(origin, trustedClient(File(root, "relay.crt"))), fixture.getString("token"))
        val storage = BlobArtifactStorage(File(root, "revision-output"), key)
        val stageRoot = File(root, "revision-downloads")
        val events = mutableListOf<String>()
        var cancellation = BlobCancellation()
        var interrupt = true
        val pipeline = BlobArtifactReceivePipeline(stageRoot, storage, checkIdentity = {},
            publish = { events += "publish"; true },
            sendReceipt = { _, receipt -> assertEquals("stored", receipt.getString("status")); events += "receipt"; true },
            observeFailure = { _, _ -> fail("Unexpected failure"); false }, client = { client }, storageKey = key,
            progress = { _, done, _ -> if (interrupt && done >= BlobProtocol.CHUNK_BYTES) cancellation.cancel() })
        fun work(offer: JSONObject, revision: Long, phase: Int): BlobArtifactReceiveWork {
            val body = JSONObject().put("offer", JSONObject().put("type", BlobArtifactContract.OFFER_TYPE).put("version", 1)
                .put("manifest", manifest).put("blob_offer", offer).put("transport_revision", revision))
                .put("route_id", "a".repeat(22)).put("desktop_id", "desktop").put("origin", origin)
                .put("peer_fingerprint", "c".repeat(64)).put("local_fingerprint", "d".repeat(64))
            return BlobArtifactReceiveWork(id, body, phase, "fixture", 0, "")
        }
        fun prepare(name: String) = BlobStaging.prepare(File(root, name), source.length(), fixture.getString("sha256"),
            binding, source::inputStream, key)
        prepare("revision-old-sender").use { old ->
            val first = client.upload(old)
            assertEquals("transfer_cancelled", assertThrows(BlobFailure::class.java) {
                pipeline.process(work(first, 1, 0), cancellation) {}
            }.code)
            assertTrue(File(stageRoot, "$id/1/00000000.blob").isFile)
            assertFalse(storage.verified(manifest)); assertTrue(events.isEmpty())
            client.revoke(old)
            prepare("revision-new-sender").use { fresh ->
                val second = client.upload(fresh)
                assertNotEquals(first.getJSONObject("private").getString("key"), second.getJSONObject("private").getString("key"))
                interrupt = false; cancellation = BlobCancellation()
                pipeline.process(work(second, 2, 0), cancellation) {}
                assertTrue(File(stageRoot, "$id/1/00000000.blob").isFile)
                assertTrue(File(stageRoot, "$id/2/00000000.blob").isFile)
                assertTrue(storage.verified(manifest))
                for (phase in 1..3) pipeline.process(work(second, 2, phase), cancellation) {}
                assertEquals(listOf("publish", "receipt"), events); assertFalse(File(stageRoot, id).exists())
                File(root, "kotlin-artifact-rekey-result.json").writeText(JSONObject()
                    .put("old_blob_id", old.private.blobId).put("new_blob_id", fresh.private.blobId)
                    .put("sha256", manifest.getString("sha256")).put("local_verified", true).toString())
            }
        }
    }

    private fun trustedClient(certificate: File): OkHttpClient {
        val ca = certificate.inputStream().use { CertificateFactory.getInstance("X.509").generateCertificate(it) }
        val store = KeyStore.getInstance(KeyStore.getDefaultType()).apply { load(null); setCertificateEntry("isolated-relay", ca) }
        val manager = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(store) }
            .trustManagers.filterIsInstance<X509TrustManager>().single()
        val tls = SSLContext.getInstance("TLS").apply { init(null, arrayOf(manager), null) }
        return OkHttpClient.Builder().sslSocketFactory(tls.socketFactory, manager).build()
    }
}
