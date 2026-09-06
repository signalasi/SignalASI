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
