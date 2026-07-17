package com.signalasi.chat

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest

class AgentRuntimePackCatalogTest {
    private lateinit var server: MockWebServer
    private lateinit var downloadRoot: File

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        downloadRoot = Files.createTempDirectory("signalasi-runtime-download").toFile()
    }

    @After
    fun tearDown() {
        server.shutdown()
        downloadRoot.deleteRecursively()
    }

    @Test
    fun `catalog signing payload is deterministic across entry ordering`() {
        val now = 1_750_000_000_000L
        val first = entry("linux-base", "arm64-v8a")
        val second = entry("python-uv", "arm64-v8a", dependencies = listOf("linux-base"))
        val forward = catalog(now, listOf(first, second))
        val reversed = catalog(now, listOf(second, first))

        assertArrayEquals(forward.signingPayload(), reversed.signingPayload())
        assertFalse(first.canonicalValue().contains('|'))
    }

    @Test
    fun `catalog codec round trips all signed metadata`() {
        val now = 1_750_000_000_000L
        val expected = catalog(
            now,
            listOf(entry("linux-base", "arm64-v8a").copy(releaseNotes = "Security update"))
        )

        val actual = AgentRuntimePackCatalogCodec.decode(AgentRuntimePackCatalogCodec.encode(expected))

        assertEquals(expected, actual)
    }

    @Test
    fun `catalog policy rejects duplicate insecure expired and untrusted catalogs`() {
        val now = 1_750_000_000_000L
        val trusted = AgentRuntimeCatalogSignatureVerifier { true }
        val valid = catalog(now, listOf(entry("linux-base", "arm64-v8a")))
        assertEquals(valid, AgentRuntimePackCatalogPolicy.validate(valid, trusted, now))

        assertThrows(IllegalArgumentException::class.java) {
            AgentRuntimePackCatalogPolicy.validate(
                valid.copy(entries = listOf(valid.entries.single(), valid.entries.single().copy(version = "1.0.1"))),
                trusted,
                now
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            AgentRuntimePackCatalogPolicy.validate(
                valid.copy(entries = listOf(valid.entries.single().copy(downloadUrl = "http://example.com/runtime.sarpack"))),
                trusted,
                now
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            AgentRuntimePackCatalogPolicy.validate(
                valid.copy(expiresAtMillis = now - 1L),
                trusted,
                now
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            AgentRuntimePackCatalogPolicy.validate(valid, AgentRuntimeCatalogSignatureVerifier { false }, now)
        }
        assertThrows(IllegalArgumentException::class.java) {
            AgentRuntimePackCatalogPolicy.validate(
                valid.copy(entries = listOf(entry("python-uv", "arm64-v8a", listOf("linux-base")))),
                trusted,
                now
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            AgentRuntimePackCatalogPolicy.validate(
                valid.copy(entries = listOf(
                    entry("linux-base", "arm64-v8a", listOf("python-uv")),
                    entry("python-uv", "arm64-v8a", listOf("linux-base"))
                )),
                trusted,
                now
            )
        }
    }

    @Test
    fun `catalog replacement rejects rollback and generation reuse`() {
        val now = 1_750_000_000_000L
        val previous = catalog(now, listOf(entry("linux-base", "arm64-v8a")))

        assertThrows(IllegalArgumentException::class.java) {
            AgentRuntimePackCatalogPolicy.validateReplacement(
                previous,
                previous.copy(generatedAtMillis = previous.generatedAtMillis - 1L)
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            AgentRuntimePackCatalogPolicy.validateReplacement(
                previous,
                previous.copy(entries = listOf(previous.entries.single().copy(version = "1.0.1")))
            )
        }
        AgentRuntimePackCatalogPolicy.validateReplacement(previous, previous)
        AgentRuntimePackCatalogPolicy.validateReplacement(
            previous,
            previous.copy(generatedAtMillis = previous.generatedAtMillis + 1L)
        )
    }

    @Test
    fun `compatible entries require ABI host and guest protocol compatibility`() {
        val now = 1_750_000_000_000L
        val compatible = entry("linux-base", "arm64-v8a")
        val wrongAbi = entry("python-uv", "x86_64")
        val futureHost = entry("node-js", "arm64-v8a").copy(minimumHostVersionCode = 99L)
        val wrongGuest = entry("go", "arm64-v8a").copy(guestApiVersion = AgentRuntimeGuestProtocol.VERSION + 1)

        val result = AgentRuntimePackCatalogPolicy.compatibleEntries(
            catalog(now, listOf(compatible, wrongAbi, futureHost, wrongGuest)),
            supportedAbis = listOf("arm64-v8a"),
            hostVersionCode = 1L
        )

        assertEquals(listOf(compatible), result)
    }

    @Test
    fun `download resumes a cancelled archive and verifies its signed digest`() {
        val payload = ByteArray(768 * 1024) { index -> (index % 251).toByte() }
        val expectedDigest = sha256(payload)
        val url = server.url("/linux-base.sarpack").toString()
        val downloadEntry = entry("linux-base", "arm64-v8a").copy(
            downloadUrl = url,
            archiveSha256 = expectedDigest,
            archiveSizeBytes = payload.size.toLong()
        )
        val downloader = AgentRuntimePackDownloader(
            root = downloadRoot,
            allowInsecureLoopbackForTests = true
        )
        val cancellation = AgentNativeToolCancellationSource()
        server.enqueue(MockResponse()
            .setResponseCode(200)
            .setHeader("ETag", "\"runtime-v1\"")
            .setBody(Buffer().write(payload)))

        assertThrows(AgentNativeToolCancelledException::class.java) {
            downloader.download(downloadEntry, cancellation.token) { progress ->
                if (progress.downloadedBytes >= 128 * 1024L) cancellation.cancel()
            }
        }
        val firstRequest = server.takeRequest()
        assertNull(firstRequest.getHeader("Range"))
        val partial = downloadRoot.listFiles().orEmpty().single { it.name.endsWith(".partial") }
        val resumeOffset = partial.length()
        assertTrue(resumeOffset in 1 until payload.size.toLong())

        server.enqueue(MockResponse()
            .setResponseCode(206)
            .setHeader("ETag", "\"runtime-v1\"")
            .setHeader("Content-Range", "bytes $resumeOffset-${payload.lastIndex}/${payload.size}")
            .setBody(Buffer().write(payload, resumeOffset.toInt(), payload.size - resumeOffset.toInt())))

        val result = downloader.download(downloadEntry)
        val secondRequest = server.takeRequest()

        assertEquals("bytes=$resumeOffset-", secondRequest.getHeader("Range"))
        assertEquals("\"runtime-v1\"", secondRequest.getHeader("If-Range"))
        assertTrue(result.resumed)
        assertEquals(expectedDigest, result.sha256)
        assertArrayEquals(payload, result.archive.readBytes())
        assertTrue(result.archive.isFile)
        assertTrue(downloadRoot.listFiles().orEmpty().none { it.name.endsWith(".partial") })
    }

    private fun catalog(now: Long, entries: List<AgentRuntimePackCatalogEntry>) = AgentRuntimePackCatalog(
        catalogVersion = "1.0.0",
        generatedAtMillis = now - 1_000L,
        expiresAtMillis = now + 60_000L,
        entries = entries,
        signatureKeyId = "a".repeat(64),
        signature = "signed"
    )

    private fun entry(
        packId: String,
        architecture: String,
        dependencies: List<String> = emptyList()
    ) = AgentRuntimePackCatalogEntry(
        packId = packId,
        version = "1.0.0",
        architecture = architecture,
        downloadUrl = "https://downloads.example.com/$packId.sarpack",
        archiveSha256 = "b".repeat(64),
        archiveSizeBytes = 1_024L,
        installedSizeBytes = 2_048L,
        dependencies = dependencies,
        license = "Apache-2.0",
        minimumHostVersionCode = 1L,
        guestApiVersion = AgentRuntimeGuestProtocol.VERSION
    )

    private fun sha256(value: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(value)
        .joinToString("") { "%02x".format(it) }
}
