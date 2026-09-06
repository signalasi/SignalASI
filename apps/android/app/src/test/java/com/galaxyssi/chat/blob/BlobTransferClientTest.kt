package com.galaxyssi.chat.blob

import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import okio.Buffer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.InetAddress
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.crypto.spec.SecretKeySpec

class BlobTransferClientTest {
    @get:Rule val temporary = TemporaryFolder()
    private val server = MockWebServer()
    private val relay = Relay()
    private val key = SecretKeySpec(ByteArray(32) { (it + 1).toByte() }, "AES")
    private val binding = mapOf("client_route_id" to "route-a", "conversation_id" to "conversation-a", "turn_id" to "turn-a")
    private val data = ByteArray(1_048_601) { (it % 251).toByte() }
    private lateinit var http: BlobHttp
    private lateinit var client: BlobTransferClient

    @Before fun start() {
        server.dispatcher = relay
        server.start(InetAddress.getByAddress("localhost", byteArrayOf(127, 0, 0, 1)), 0)
        // MockWebServer may reverse-resolve to localhost on Linux; the HTTP test opt-in is literal-only.
        http = BlobHttp(server.url("/").newBuilder().host("127.0.0.1").build().toString(), allowLoopbackHttp = true)
        client = BlobTransferClient(http, "f".repeat(64))
    }
    @After fun stop() { server.shutdown() }
    private fun staged(name: String = "sender") = BlobStaging.prepare(File(temporary.root, name), data.size.toLong(),
        BlobProtocol.hash(data), binding, { data.inputStream() }, key)

    @Test fun `fixture hostname never broadens the literal loopback exception`() {
        val namedOrigin = server.url("/").newBuilder().host("localhost").build().toString()
        assertEquals("relay_requires_https", assertThrows(BlobFailure::class.java) {
            BlobHttp(namedOrigin, allowLoopbackHttp = true)
        }.code)
        assertTrue(http.origin.startsWith("http://127.0.0.1:"))
    }

    @Test fun `round trip sends early private offer and binary blocks with verified completion`() {
        staged().use { sender ->
            var early = false
            val progress = mutableListOf<Long>()
            val offer = client.upload(sender, progress = { done, _ -> progress.add(done) }, onOffer = {
                early = true
                assertTrue(relay.blocks.isEmpty())
                assertFalse(it.has("write_token"))
            })
            assertTrue(early)
            assertEquals(listOf(0L, 1_048_576L, data.size.toLong()), progress)
            assertEquals(listOf(1_048_592, 41), relay.blocks.toSortedMap().values.map { it.size })
            client.download(offer, File(temporary.root, "receiver"), binding, key).use { receiver ->
                val output = ByteArrayOutputStream()
                receiver.copyPlaintext(output, binding)
                assertArrayEquals(data, output.toByteArray())
            }
            assertTrue(relay.binaryHeaders.all { it == "application/octet-stream" })
            assertTrue(relay.encodings.all { it == "identity" })
        }
    }

    @Test fun `lost chunk ACK resumes missing only with the same persisted capabilities`() {
        val path = File(temporary.root, "sender")
        relay.dropAckOnce = true
        staged().use { sender ->
            assertEquals("relay_connection_failed", assertThrows(BlobFailure::class.java) { client.upload(sender) }.code)
            assertEquals(listOf(0), relay.puts)
        }
        BlobStaging.open(path, binding, key).use { reopened -> client.upload(reopened) }
        assertEquals(listOf(0, 1), relay.puts)
        assertEquals(1, relay.creates)
    }

    @Test fun `lost creation ACK retries identical manifest and capability identity`() {
        relay.dropCreationOnce = true
        val directory = File(temporary.root, "sender")
        staged().use { sender -> assertThrows(BlobFailure::class.java) { client.upload(sender) } }
        val oldRead = relay.readToken
        val oldWrite = relay.writeToken
        BlobStaging.open(directory, binding, key).use { client.upload(it) }
        assertEquals(oldRead, relay.readToken)
        assertEquals(oldWrite, relay.writeToken)
        assertEquals(2, relay.creates)
        assertEquals(listOf(0, 1), relay.puts)
    }

    @Test fun `cancelled download keeps first chunk and reopens for missing-only download`() {
        staged().use { sender ->
            val offer = client.upload(sender)
            val directory = File(temporary.root, "receiver")
            val cancel = BlobCancellation()
            assertEquals("transfer_cancelled", assertThrows(BlobFailure::class.java) {
                client.download(offer, directory, binding, key, cancel) { done, _ -> if (done >= 1_048_576) cancel.cancel() }
            }.code)
            assertEquals(listOf(0), relay.gets)
            client.download(offer, directory, binding, key).close()
            assertEquals(listOf(0, 1), relay.gets)
            File(directory, "00000000.blob").writeBytes(byteArrayOf(1))
            client.download(offer, directory, binding, key).close()
            assertEquals(listOf(0, 1, 0), relay.gets)
        }
    }

    @Test fun `wrong conversation rejected before network and wrong key fails authentication`() {
        staged().use { sender ->
            val offer = client.upload(sender)
            val previous = server.requestCount
            assertEquals("transfer_binding_mismatch", assertThrows(BlobFailure::class.java) {
                client.download(offer, File(temporary.root, "wrong-route"), binding + ("turn_id" to "different"), key)
            }.code)
            assertEquals(previous, server.requestCount)
            offer.getJSONObject("private").put("key", "a".repeat(64))
            assertEquals("chunk_authentication_failed", assertThrows(BlobFailure::class.java) {
                client.download(offer, File(temporary.root, "wrong-key"), binding, key)
            }.code)
        }
    }

    @Test fun `revocation prevents subsequent upload instead of recreating a transfer`() {
        staged().use { sender ->
            client.upload(sender)
            client.revoke(sender)
            val count = server.requestCount
            assertEquals("transfer_revoked", assertThrows(BlobFailure::class.java) { client.upload(sender) }.code)
            assertEquals(count, server.requestCount)
        }
    }

    @Test fun `relay rejects redirects compressed responses oversized bodies and arbitrary errors`() {
        val responses = listOf(
            MockResponse().setResponseCode(302).addHeader("Location", "https://example.com") to "relay_redirect_rejected",
            MockResponse().addHeader("Content-Encoding", "gzip").setBody("abc") to "relay_response_encoding_rejected",
            MockResponse().setChunkedBody("x".repeat(150_000), 1024) to "relay_response_too_large",
            MockResponse().setResponseCode(500).setBody("<html>private-key</html>") to "relay_http_500"
        )
        responses.forEach { (response, expected) ->
            server.dispatcher = object : Dispatcher() { override fun dispatch(request: RecordedRequest) = response }
            assertEquals(expected, assertThrows(BlobFailure::class.java) {
                http.json("GET", "/v1/blobs/${"a".repeat(32)}", "b".repeat(64))
            }.code)
        }
    }

    @Test fun `four blocked bulk requests do not block control and cancel promptly`() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
                if (request.path!!.contains("chunks")) MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE)
                else MockResponse().setBody("{\"ok\":true}")
        }
        val workers = Executors.newFixedThreadPool(4)
        val cancellations = List(4) { BlobCancellation() }
        val futures = cancellations.map { cancellation -> workers.submit<String> {
            try {
                http.request("GET", "/v1/blobs/${"a".repeat(32)}/chunks/0", "b".repeat(64),
                    maximum = 1_048_592, cancel = cancellation)
                "unexpected-success"
            } catch (error: BlobFailure) { error.code }
        } }
        try {
            repeat(4) { assertNotNull(server.takeRequest(5, TimeUnit.SECONDS)) }
            assertTrue(http.json("GET", "/v1/blobs/${"a".repeat(32)}/missing", "b".repeat(64)).getBoolean("ok"))
            cancellations.forEach(BlobCancellation::cancel)
            futures.forEach { assertEquals("transfer_cancelled", it.get(3, TimeUnit.SECONDS)) }
        } finally {
            cancellations.forEach(BlobCancellation::cancel)
            workers.shutdownNow()
            workers.awaitTermination(5, TimeUnit.SECONDS)
        }
    }

    @Test fun `production transport disallows insecure or credential-bearing origins`() {
        listOf("http://example.com", "http://127.0.0.1", "https://user:secret@example.com", "https://example.com/path",
            "https://example.com/?token=secret", "https://example.com/#fragment", "file:///tmp/data").forEach { origin ->
            assertThrows(BlobFailure::class.java) { BlobHttp(origin) }
        }
        assertThrows(BlobFailure::class.java) { BlobHttp("http://localhost", allowLoopbackHttp = true) }
    }

    @Test fun `malformed chunk receipt is redacted and durable chunk still resumes`() {
        relay.badReceiptOnce = true
        staged().use { sender ->
            val error = assertThrows(BlobFailure::class.java) { client.upload(sender) }
            assertEquals("invalid_chunk_receipt", error.code)
            assertFalse(error.toString().contains("private-relay-response"))
            assertNull(error.cause)
            client.upload(sender)
            assertEquals(listOf(0, 1), relay.puts)
        }
    }

    private class Relay : Dispatcher() {
        var manifest: JSONObject? = null
        var readToken = ""
        var writeToken = ""
        var creates = 0
        var dropAckOnce = false
        var dropCreationOnce = false
        var badReceiptOnce = false
        val blocks = mutableMapOf<Int, ByteArray>()
        val puts = CopyOnWriteArrayList<Int>()
        val gets = CopyOnWriteArrayList<Int>()
        val binaryHeaders = CopyOnWriteArrayList<String?>()
        val encodings = CopyOnWriteArrayList<String?>()

        @Synchronized override fun dispatch(request: RecordedRequest): MockResponse {
            encodings.add(request.getHeader("Accept-Encoding"))
            val path = request.path!!
            val token = request.getHeader("Authorization")?.removePrefix("Bearer ")
            val chunk = path.substringAfterLast("/chunks/", "").toIntOrNull()
            if (request.method == "PUT" && chunk == null) {
                if (token != "f".repeat(64)) return error(401, "authentication_required")
                val body = JSONObject(request.body.readUtf8())
                val next = body.getJSONObject("manifest")
                BlobProtocol.parseManifest(next)
                if (manifest != null && (root() != BlobProtocol.hash(BlobProtocol.canonical(next)) ||
                    readToken != body.getString("read_token") || writeToken != body.getString("write_token"))) {
                    return error(409, "blob_creation_conflict")
                }
                manifest = next
                readToken = body.getString("read_token")
                writeToken = body.getString("write_token")
                creates++
                val response = json(JSONObject().put("root", root()))
                if (dropCreationOnce) { dropCreationOnce = false; response.setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST) }
                return response
            }
            val expected = if (path.endsWith("/missing") || request.method in listOf("PUT", "DELETE")) writeToken else readToken
            if (token != expected) return error(401, "authentication_required")
            if (request.method == "DELETE") return json(JSONObject().put("deleted", true))
            if (path.endsWith("/missing")) {
                val count = manifest!!.getJSONArray("chunks").length()
                val bits = ByteArray((count + 7) / 8)
                repeat(count) { index -> if (index !in blocks) bits[index / 8] = (bits[index / 8].toInt() or (1 shl (index % 8))).toByte() }
                return json(JSONObject().put("root", root()).put("chunk_count", count)
                    .put("missing_bitmap", BlobProtocol.hex(bits)).put("complete", blocks.size == count))
            }
            if (chunk == null) return json(manifest!!)
            if (request.method == "PUT") {
                val bytes = request.body.readByteArray()
                val descriptor = manifest!!.getJSONArray("chunks").getJSONObject(chunk)
                if (bytes.size != descriptor.getInt("size") || BlobProtocol.hash(bytes) != descriptor.getString("sha256")) {
                    return error(409, "ciphertext_hash_mismatch")
                }
                blocks[chunk] = bytes
                puts.add(chunk)
                binaryHeaders.add(request.getHeader("Content-Type"))
                if (badReceiptOnce) { badReceiptOnce = false; return MockResponse().setBody("private-relay-response") }
                val response = json(JSONObject().put("stored", true))
                if (dropAckOnce) { dropAckOnce = false; response.setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST) }
                return response
            }
            gets.add(chunk)
            return blocks[chunk]?.let { MockResponse().setBody(Buffer().write(it)) } ?: error(409, "chunk_not_ready")
        }

        private fun root() = BlobProtocol.hash(BlobProtocol.canonical(manifest!!))
        private fun json(body: JSONObject) = MockResponse().setBody(body.toString()).addHeader("Content-Type", "application/json")
        private fun error(status: Int, code: String) = json(JSONObject().put("error", code)).setResponseCode(status)
    }
}
