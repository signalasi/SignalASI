package com.galaxyssi.chat.blob

import okhttp3.OkHttpClient
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.io.OutputStream
import java.security.KeyStore
import java.security.MessageDigest
import java.security.cert.CertificateFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager
import javax.crypto.spec.SecretKeySpec

/** Invoked by tools/dev/test-android-blob-interoperability.py with an isolated HTTPS relay. */
class BlobCrossRuntimeTest {
    @Test fun `artifact output resumes then publishes and receipts before staging cleanup`() {
        val fixtureRoot = System.getenv("GALAXYSSI_BLOB_TEST_ROOT").orEmpty()
        assumeTrue("Requires the isolated cross-runtime test runner", fixtureRoot.isNotEmpty())
        val root = File(fixtureRoot)
        val fixture = JSONObject(File(root, "fixture.json").readText())
        val origin = fixture.getJSONObject("offer").getString("relay")
        val source = File(root, "source.bin")
        val key = SecretKeySpec(ByteArray(32) { (it + 43).toByte() }, "AES")
        val manifest = BlobArtifactContract.makeManifest(JSONObject().put("client_route_id", "a".repeat(22))
            .put("desktop_id", "desktop").put("conversation_id", "中文产物回传测试").put("task_id", "task")
            .put("turn_id", "turn").put("contact_id", "contact").put("source_message_id", "message")
            .put("artifact_id", "b".repeat(64)).put("artifact_uri", "galaxyssi-artifact://task/output.bin")
            .put("name", "回传文件.bin").put("relative_path", "output.bin").put("mime_type", "application/octet-stream")
            .put("size_bytes", source.length()).put("original_size_bytes", source.length())
            .put("sha256", fixture.getString("sha256")).put("original_sha256", fixture.getString("sha256"))
            .put("execution_generation", 1).put("peer_chat", false))
        val client = BlobTransferClient(BlobHttp(origin, trustedClient(File(root, "relay.crt"))), fixture.getString("token"))
        val binding = BlobArtifactContract.binding(manifest)
        val id = manifest.getString("transfer_id")
        val storage = BlobArtifactStorage(File(root, "artifact-output"), key)
        val stagingRoot = File(root, "artifact-downloads")
        val events = mutableListOf<String>()
        BlobStaging.prepare(File(root, "artifact-sender"), source.length(), fixture.getString("sha256"),
            binding, source::inputStream, key).use { sender ->
            val blobOffer = client.upload(sender)
            val body = JSONObject().put("offer", JSONObject().put("type", BlobArtifactContract.OFFER_TYPE)
                .put("version", 1).put("manifest", manifest).put("blob_offer", blobOffer).put("transport_revision", 1))
                .put("route_id", "a".repeat(22)).put("desktop_id", "desktop").put("origin", origin)
                .put("peer_fingerprint", "c".repeat(64)).put("local_fingerprint", "d".repeat(64))
            var cancellation = BlobCancellation()
            var interrupt = true
            val pipeline = BlobArtifactReceivePipeline(stagingRoot, storage,
                checkIdentity = { assertEquals("desktop", it.getString("desktop_id")) },
                publish = { assertTrue(storage.verified(it)); events += "publish"; true },
                sendReceipt = { _, receipt ->
                    assertEquals(listOf("publish"), events)
                    assertEquals(id, receipt.getString("transfer_id"))
                    assertEquals("stored", receipt.getString("status"))
                    events += "receipt"; true
                }, observeFailure = { _, _ -> fail("Unexpected failure observation"); false },
                client = { client }, storageKey = key,
                progress = { _, done, _ -> if (interrupt && done >= BlobProtocol.CHUNK_BYTES) cancellation.cancel() })
            fun work(phase: Int) = BlobArtifactReceiveWork(id, body, phase, "fixture", 0, "")
            assertEquals("transfer_cancelled", assertThrows(BlobFailure::class.java) {
                pipeline.process(work(BlobArtifactReceiveJournal.DOWNLOAD), cancellation) {}
            }.code)
            assertTrue(events.isEmpty()); assertFalse(storage.verified(manifest))
            assertTrue(File(stagingRoot, "$id/1/00000000.blob").isFile)
            interrupt = false; cancellation = BlobCancellation()
            for (phase in 0..3) pipeline.process(work(phase), cancellation) {}
            assertEquals(listOf("publish", "receipt"), events)
            assertFalse(File(stagingRoot, id).exists())
            assertTrue(storage.verified(manifest))
            client.revoke(sender)
            // A duplicate offer after sender cleanup verifies the local file without contacting the Relay.
            pipeline.process(work(BlobArtifactReceiveJournal.DOWNLOAD), cancellation) {}
            File(root, "kotlin-artifact-result.json").writeText(JSONObject()
                .put("blob_id", sender.private.blobId).put("sha256", manifest.getString("sha256"))
                .put("phases", org.json.JSONArray(events)).put("local_verified_after_revoke", true).toString())
        }
    }

    @Test fun `Kotlin and Python exchange authenticated resumable ciphertext over real HTTPS`() {
        val fixtureRoot = System.getenv("GALAXYSSI_BLOB_TEST_ROOT").orEmpty()
        assumeTrue("Requires the isolated cross-runtime test runner", fixtureRoot.isNotEmpty())
        val root = File(fixtureRoot)
        val fixture = JSONObject(File(root, "fixture.json").readText())
        val offer = fixture.getJSONObject("offer")
        val origin = offer.getString("relay")
        val bindingJson = fixture.getJSONObject("binding")
        val binding = bindingJson.keys().asSequence().associateWith(bindingJson::getString)
        val path = "/v1/blobs/${offer.getJSONObject("private").getString("blob_id")}"
        assertEquals("relay_tls_verification_failed", assertThrows(BlobFailure::class.java) {
            BlobHttp(origin).json("GET", path, offer.getString("read_token"))
        }.code)

        val trusted = trustedClient(File(root, "relay.crt"))
        val client = BlobTransferClient(BlobHttp(origin, trusted), fixture.getString("token"))
        val storageKey = SecretKeySpec(ByteArray(32) { (it * 3 + 1).toByte() }, "AES")
        client.download(offer, File(root, "kotlin-receiver"), binding, storageKey).use { staged ->
            val digest = MessageDigest.getInstance("SHA-256")
            val output = object : OutputStream() {
                override fun write(value: Int) { digest.update(value.toByte()) }
                override fun write(bytes: ByteArray, offset: Int, length: Int) {
                    assertTrue(length <= BlobProtocol.CHUNK_BYTES)
                    digest.update(bytes, offset, length)
                }
            }
            staged.copyPlaintext(output, binding)
            assertEquals(fixture.getString("sha256"), BlobProtocol.hex(digest.digest()))
        }

        val source = File(root, "source.bin")
        val directory = File(root, "kotlin-sender")
        BlobStaging.prepare(directory, source.length(), fixture.getString("sha256"), binding,
            source::inputStream, storageKey).use { staged ->
            val cancel = BlobCancellation()
            assertEquals("transfer_cancelled", assertThrows(BlobFailure::class.java) {
                client.upload(staged, cancel, progress = { done, _ -> if (done >= BlobProtocol.CHUNK_BYTES) cancel.cancel() })
            }.code)
        }
        BlobStaging.open(directory, binding, storageKey).use { reopened ->
            val completed = client.upload(reopened)
            // This fixture contains test-only random capabilities, never user data.
            File(root, "kotlin-offer.json").writeText(completed.toString())
        }
    }

    private fun trustedClient(certificate: File): OkHttpClient {
        val ca = certificate.inputStream().use { CertificateFactory.getInstance("X.509").generateCertificate(it) }
        val store = KeyStore.getInstance(KeyStore.getDefaultType()).apply { load(null); setCertificateEntry("test-relay", ca) }
        val manager = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(store) }
            .trustManagers.filterIsInstance<X509TrustManager>().single()
        val context = SSLContext.getInstance("TLS").apply { init(null, arrayOf(manager), null) }
        return OkHttpClient.Builder().sslSocketFactory(context.socketFactory, manager).build()
    }
}
