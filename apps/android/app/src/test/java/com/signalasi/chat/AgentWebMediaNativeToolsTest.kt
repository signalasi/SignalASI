package com.signalasi.chat

import java.net.InetAddress
import java.security.MessageDigest
import java.util.ArrayDeque
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentWebMediaNativeToolsTest {
    @Test
    fun exposesBoundedToolsAndExplicitFfmpegUnavailability() {
        val services = services(FakeTransport())
        val definitions = AgentWebMediaNativeTools.definitions(services)

        assertEquals(AgentWebMediaNativeTools.toolIds, definitions.map { it.descriptor.id }.toSet())
        definitions.filterNot { it.descriptor.id == AgentWebMediaNativeTools.MEDIA_FFMPEG_TRANSCODE }.forEach {
            assertTrue(it.descriptor.timeoutMillis <= AgentWebMediaNativeTools.MAX_TOOL_TIMEOUT_MILLIS)
        }
        val ffmpeg = definitions.single { it.descriptor.id == AgentWebMediaNativeTools.MEDIA_FFMPEG_TRANSCODE }
        assertEquals(AgentNativeToolRisk.BLOCKED, ffmpeg.descriptor.risk)
        assertEquals(AgentNativeToolAvailabilityStatus.UNAVAILABLE, ffmpeg.descriptor.availability.status)

        val result = AgentWebMediaNativeTools.createRegistry(services).invoke(
            AgentWebMediaNativeTools.MEDIA_FFMPEG_TRANSCODE,
            mapOf("content_uri" to "content://selected/video", "target_format" to "mp4")
        )

        assertEquals(AgentNativeToolResultStatus.UNAVAILABLE, result.status)
        assertEquals("tool_unavailable", result.error?.code)
        assertTrue(result.error?.message.orEmpty().contains("FFmpeg"))
        setOf(
            AgentWebMediaNativeTools.WEATHER_FORECAST,
            AgentWebMediaNativeTools.WEB_SEARCH,
            AgentWebMediaNativeTools.WEB_OPEN,
            AgentWebMediaNativeTools.BROWSER_RENDER,
            AgentWebMediaNativeTools.HTTP_REQUEST
        ).forEach { id ->
            assertTrue(definitions.single { it.descriptor.id == id }.descriptor.requiredConsents.isEmpty())
        }
    }

    @Test
    fun weatherForecastRunsEntirelyThroughPhoneWebTransport() {
        val geocoding = """{"results":[{"name":"Shanghai","admin1":"Shanghai","country":"China","latitude":31.22,"longitude":121.46}]}""".toByteArray()
        val forecast = """{"current":{"temperature_2m":33,"apparent_temperature":39,"relative_humidity_2m":62,"weather_code":3,"wind_speed_10m":9},"daily":{"time":["2026-07-16"],"temperature_2m_max":[38],"temperature_2m_min":[29],"precipitation_probability_max":[20],"weather_code":[3]}}""".toByteArray()
        val transport = FakeTransport(
            AgentWebTransportResponse(200, mapOf("Content-Type" to listOf("application/json")), geocoding),
            AgentWebTransportResponse(200, mapOf("Content-Type" to listOf("application/json")), forecast)
        )
        val registry = registry(
            transport,
            FakeResolver(
                "geocoding-api.open-meteo.com" to listOf(publicAddress(93, 184, 216, 34)),
                "api.open-meteo.com" to listOf(publicAddress(142, 250, 72, 14))
            )
        )

        val result = registry.invoke(
            AgentWebMediaNativeTools.WEATHER_FORECAST,
            mapOf("location" to "Shanghai", "language" to "en", "day_offset" to 0, "timeout_ms" to 10_000),
            AgentNativeToolInvocationContext(grantedPermissions = setOf(AgentWebMediaNativeTools.INTERNET_PERMISSION))
        )

        assertTrue(result.toJson(), result.isSuccess)
        assertEquals("Shanghai, China", result.output["location"])
        assertEquals(29.0, result.output["temperature_min_c"])
        assertTrue(result.message.contains("29\u201338 \u00b0C"))
        assertEquals("android_phone", result.metadata["execution_location"])
        assertEquals(2, transport.requests.size)
    }

    @Test
    fun searchUsesFastProviderAndReturnsSchemaValidResults() {
        val html = """
            <html><body><li class="b_algo"><div class="b_algoheader"><a href="https://signalasi.example/result"><h2>SignalASI result</h2></a></div></li></body></html>
        """.trimIndent().toByteArray()
        val transport = FakeTransport(
            AgentWebTransportResponse(
                200,
                mapOf("Content-Type" to listOf("text/html; charset=utf-8")),
                html
            )
        )
        val registry = registry(
            transport,
            FakeResolver("cn.bing.com" to listOf(publicAddress(13, 107, 21, 200)))
        )

        val result = registry.invoke(
            AgentWebMediaNativeTools.WEB_SEARCH,
            mapOf("query" to "SignalASI", "max_results" to 2, "timeout_ms" to 10_000),
            webContext()
        )

        assertTrue(result.toJson(), result.isSuccess)
        assertEquals(1, result.output["result_count"])
        val first = (result.output["results"] as List<*>).first() as Map<*, *>
        assertEquals("SignalASI result", first["title"])
        assertEquals("https://signalasi.example/result", first["url"])
        assertEquals("bing", result.metadata["provider"])
    }

    @Test
    fun openHttpAndContentResultsSatisfyTheirDeclaredSchemas() {
        listOf(AgentWebMediaNativeTools.WEB_OPEN, AgentWebMediaNativeTools.BROWSER_RENDER).forEach { toolId ->
            val registry = registry(
                FakeTransport(
                    AgentWebTransportResponse(
                        200,
                        mapOf("Content-Type" to listOf("text/html; charset=utf-8")),
                        "<h1>SignalASI</h1>".toByteArray()
                    )
                ),
                FakeResolver("public.example.test" to listOf(publicAddress(93, 184, 216, 34)))
            )
            val result = registry.invoke(
                toolId,
                mapOf("url" to "https://public.example.test/page"),
                webContext()
            )
            assertTrue(result.toJson(), result.isSuccess)
            assertEquals("SignalASI", result.output["text"])
        }

        val headRegistry = registry(
            FakeTransport(AgentWebTransportResponse(200, mapOf("Content-Type" to listOf("text/plain")))),
            FakeResolver("public.example.test" to listOf(publicAddress(93, 184, 216, 34)))
        )
        val head = headRegistry.invoke(
            AgentWebMediaNativeTools.HTTP_REQUEST,
            mapOf("url" to "https://public.example.test/status", "method" to "HEAD"),
            webContext()
        )
        assertTrue(head.toJson(), head.isSuccess)

        val content = AgentWebMediaNativeTools.createRegistry(services(FakeTransport())).invoke(
            AgentWebMediaNativeTools.CONTENT_EXTRACT,
            mapOf("content" to "<p>Readable text</p>")
        )
        assertTrue(content.toJson(), content.isSuccess)
        assertEquals("Readable text", content.output["text"])
    }

    @Test
    fun fetchFollowsRevalidatedRedirectAndReturnsTimestampedSourceProvenance() {
        val clock = MutableClock(1_000L)
        val transport = FakeTransport(
            AgentWebTransportResponse(
                302,
                mapOf("Location" to listOf("https://cdn.example.test/article"))
            ),
            AgentWebTransportResponse(
                200,
                mapOf(
                    "Content-Type" to listOf("text/plain; charset=utf-8"),
                    "Content-Length" to listOf("5"),
                    "ETag" to listOf("v1")
                ),
                "hello".toByteArray()
            ),
            onExecute = { clock.now += 5 }
        )
        val resolver = FakeResolver(
            "origin.example.test" to listOf(publicAddress(93, 184, 216, 34)),
            "cdn.example.test" to listOf(publicAddress(142, 250, 72, 14))
        )
        val registry = registry(transport, resolver, clock)

        val result = registry.invoke(
            AgentWebMediaNativeTools.WEB_FETCH,
            mapOf("url" to "https://origin.example.test/start", "max_bytes" to 100),
            webContext()
        )

        assertTrue(result.toJson(), result.isSuccess)
        assertEquals("hello", result.output["text"])
        assertEquals(1_000L, result.output["requested_at_epoch_ms"])
        assertEquals(1_010L, result.output["retrieved_at_epoch_ms"])
        assertEquals("v1", (result.output["response_headers"] as Map<*, *>)["etag"])
        val source = result.output["source"] as Map<*, *>
        assertEquals("https://origin.example.test/start", source["requested_url"])
        assertEquals("https://cdn.example.test/article", source["final_url"])
        assertEquals(1, (source["redirect_chain"] as List<*>).size)
        assertEquals(2, (source["dns_resolution"] as List<*>).size)
        assertEquals(2, transport.requests.size)
        assertEquals("93.184.216.34", transport.requests[0].resolvedAddresses.single().hostAddress)
        assertEquals("142.250.72.14", transport.requests[1].resolvedAddresses.single().hostAddress)
    }

    @Test
    fun blocksLocalhostAndEveryPrivateDnsAnswerBeforeTransport() {
        val transport = FakeTransport()
        val resolver = FakeResolver(
            "private.example.test" to listOf(publicAddress(93, 184, 216, 34), privateAddress(10, 0, 0, 8))
        )
        val registry = registry(transport, resolver)

        val localhost = registry.invoke(
            AgentWebMediaNativeTools.WEB_HEAD,
            mapOf("url" to "https://localhost/status"),
            webContext()
        )
        val privateDns = registry.invoke(
            AgentWebMediaNativeTools.WEB_HEAD,
            mapOf("url" to "https://private.example.test/status"),
            webContext()
        )

        assertEquals("local_host_blocked", localhost.error?.code)
        assertEquals("private_network_blocked", privateDns.error?.code)
        assertTrue(transport.requests.isEmpty())
    }

    @Test
    fun blocksRedirectToPrivateNetworkBeforeSecondRequest() {
        val transport = FakeTransport(
            AgentWebTransportResponse(
                307,
                mapOf("Location" to listOf("https://internal.example.test/private"))
            )
        )
        val resolver = FakeResolver(
            "public.example.test" to listOf(publicAddress(93, 184, 216, 34)),
            "internal.example.test" to listOf(privateAddress(192, 168, 1, 20))
        )
        val registry = registry(transport, resolver)

        val result = registry.invoke(
            AgentWebMediaNativeTools.WEB_FETCH,
            mapOf("url" to "https://public.example.test/start"),
            webContext()
        )

        assertEquals(AgentNativeToolResultStatus.FAILED, result.status)
        assertEquals("private_network_blocked", result.error?.code)
        assertEquals(1, transport.requests.size)
    }

    @Test
    fun enforcesContentTypeDeclaredSizeActualSizeAndWallClockTimeout() {
        val resolver = FakeResolver("public.example.test" to listOf(publicAddress(93, 184, 216, 34)))

        val unsupported = registry(
            FakeTransport(
                AgentWebTransportResponse(
                    200,
                    mapOf("Content-Type" to listOf("application/x-executable")),
                    byteArrayOf(1)
                )
            ),
            resolver
        ).invoke(
            AgentWebMediaNativeTools.WEB_FETCH,
            mapOf("url" to "https://public.example.test/a"),
            webContext()
        )
        val declaredTooLarge = registry(
            FakeTransport(
                AgentWebTransportResponse(
                    200,
                    mapOf("Content-Type" to listOf("text/plain"), "Content-Length" to listOf("101")),
                    byteArrayOf(1)
                )
            ),
            resolver
        ).invoke(
            AgentWebMediaNativeTools.WEB_FETCH,
            mapOf("url" to "https://public.example.test/b", "max_bytes" to 100),
            webContext()
        )
        val actualTooLarge = registry(
            FakeTransport(
                AgentWebTransportResponse(
                    200,
                    mapOf("Content-Type" to listOf("text/plain")),
                    ByteArray(101)
                )
            ),
            resolver
        ).invoke(
            AgentWebMediaNativeTools.WEB_FETCH,
            mapOf("url" to "https://public.example.test/c", "max_bytes" to 100),
            webContext()
        )
        val clock = MutableClock(500L)
        val timedOut = registry(
            FakeTransport(
                AgentWebTransportResponse(200, mapOf("Content-Type" to listOf("text/plain"))),
                onExecute = { clock.now += 10 }
            ),
            resolver,
            clock
        ).invoke(
            AgentWebMediaNativeTools.WEB_FETCH,
            mapOf("url" to "https://public.example.test/d", "timeout_ms" to 5),
            webContext()
        )

        assertEquals("unsupported_content_type", unsupported.error?.code)
        assertEquals("response_too_large", declaredTooLarge.error?.code)
        assertEquals("response_too_large", actualTooLarge.error?.code)
        assertEquals(AgentNativeToolResultStatus.TIMED_OUT, timedOut.status)
    }

    @Test
    fun downloadWritesOnlyToSelectedContentUriAndReportsDigest() {
        val bytes = byteArrayOf(1, 2, 3, 4)
        val transport = FakeTransport(
            AgentWebTransportResponse(
                200,
                mapOf("Content-Type" to listOf("image/png"), "Content-Length" to listOf(bytes.size.toString())),
                bytes
            )
        )
        val writer = FakeWriter()
        val registry = registry(
            transport,
            FakeResolver("assets.example.test" to listOf(publicAddress(93, 184, 216, 34))),
            writer = writer
        )

        val result = registry.invoke(
            AgentWebMediaNativeTools.WEB_DOWNLOAD,
            mapOf(
                "url" to "https://assets.example.test/image.png",
                "destination_content_uri" to "content://documents/selected-image"
            ),
            AgentNativeToolInvocationContext(
                idempotencyKey = "download-1",
                grantedPermissions = setOf(AgentWebMediaNativeTools.INTERNET_PERMISSION),
                grantedConsents = setOf(
                    AgentWebMediaNativeTools.PUBLIC_WEB_CONSENT,
                    AgentWebMediaNativeTools.WEB_DOWNLOAD_CONSENT,
                    AgentWebMediaNativeTools.CONTENT_URI_WRITE_CONSENT
                )
            )
        )

        assertTrue(result.toJson(), result.isSuccess)
        assertEquals("content://documents/selected-image", writer.uri)
        assertEquals("image/png", writer.contentType)
        assertArrayEquals(bytes, writer.bytes)
        assertEquals(bytes.size.toLong(), result.output["size_bytes"])
        assertEquals(hexSha256(bytes), result.output["sha256"])
        assertEquals(false, result.metadata["auto_execute"])
    }

    @Test
    fun ocrUsesSelectedOrCapturedUriAndReflectsImplementationAvailability() {
        val ocr = FakeOcr(
            result = AgentOcrResult(
                "Signal ASI",
                listOf(AgentOcrLine("Signal ASI", 1, 2, 20, 12)),
                width = 100,
                height = 40,
                languageTags = listOf("en", "en")
            )
        )
        val registry = registry(FakeTransport(), ocr = ocr)

        val result = registry.invoke(
            AgentWebMediaNativeTools.OCR_RECOGNIZE_CONTENT,
            mapOf("content_uri" to "content://capture/frame-1", "source_kind" to "captured"),
            contentReadContext()
        )

        assertTrue(result.toJson(), result.isSuccess)
        assertEquals("content://capture/frame-1", ocr.request?.contentUri)
        assertEquals(AgentOcrSourceKind.CAPTURED, ocr.request?.sourceKind)
        assertEquals("Signal ASI", result.output["text"])
        assertEquals(listOf("en"), result.output["language_tags"])
        assertEquals("captured", ((result.output["source"] as Map<*, *>)["source_kind"]))

        ocr.currentAvailability = AgentNativeToolAvailability(
            AgentNativeToolAvailabilityStatus.REQUIRES_SETUP,
            "OCR model is not installed"
        )
        val unavailable = registry.invoke(
            AgentWebMediaNativeTools.OCR_RECOGNIZE_CONTENT,
            mapOf("content_uri" to "content://selected/image-2", "source_kind" to "selected"),
            contentReadContext()
        )
        assertEquals(AgentNativeToolResultStatus.UNAVAILABLE, unavailable.status)
        assertTrue(unavailable.error?.retryable == true)
    }

    @Test
    fun mediaMetadataAndPlaybackAreBoundedContentUriHandoffs() {
        val inspector = FakeInspector()
        val playback = FakePlayback()
        val registry = registry(FakeTransport(), inspector = inspector, playback = playback)

        val metadata = registry.invoke(
            AgentWebMediaNativeTools.MEDIA_METADATA,
            mapOf("content_uri" to "content://media/item-7"),
            contentReadContext()
        )
        val handoff = registry.invoke(
            AgentWebMediaNativeTools.MEDIA_PLAYBACK_HANDOFF,
            mapOf("content_uri" to "content://media/item-7", "content_type" to "video/mp4"),
            AgentNativeToolInvocationContext(
                grantedConsents = setOf(
                    AgentWebMediaNativeTools.CONTENT_URI_READ_CONSENT,
                    AgentWebMediaNativeTools.MEDIA_PLAYBACK_CONSENT
                )
            )
        )

        assertTrue(metadata.toJson(), metadata.isSuccess)
        assertEquals(4_000L, metadata.output["duration_ms"])
        assertEquals(true, metadata.output["has_video"])
        assertEquals("content://media/item-7", inspector.uri)
        assertTrue(handoff.toJson(), handoff.isSuccess)
        assertEquals(true, handoff.output["launched"])
        assertEquals(false, handoff.output["completed"])
        assertEquals("content://media/item-7", playback.uri)
        assertEquals("video/mp4", playback.contentType)
        assertNotNull(handoff.output["handed_off_at_epoch_ms"])
    }

    private fun registry(
        transport: AgentWebTransport,
        resolver: AgentHostResolver = FakeResolver(),
        clock: MutableClock = MutableClock(1_000L),
        writer: AgentContentUriWriter = FakeWriter(),
        ocr: AgentContentOcr = FakeOcr(),
        inspector: AgentMediaInspector = FakeInspector(),
        playback: AgentMediaPlayback = FakePlayback()
    ): AgentNativeToolRegistry {
        val web = AgentBoundedWebService(
            transport,
            resolver,
            clock,
            AgentWebPolicy(
                maxFetchBytes = AgentWebMediaNativeTools.MAX_FETCH_BYTES,
                maxDownloadBytes = AgentWebMediaNativeTools.MAX_DOWNLOAD_BYTES
            )
        )
        return AgentWebMediaNativeTools.createRegistry(
            AgentWebMediaServices(web, writer, ocr, inspector, playback),
            clock
        )
    }

    private fun services(transport: AgentWebTransport) = AgentWebMediaServices(
        AgentBoundedWebService(transport, FakeResolver()),
        FakeWriter(),
        FakeOcr(),
        FakeInspector(),
        FakePlayback()
    )

    private fun webContext() = AgentNativeToolInvocationContext(
        grantedPermissions = setOf(AgentWebMediaNativeTools.INTERNET_PERMISSION),
        grantedConsents = setOf(AgentWebMediaNativeTools.PUBLIC_WEB_CONSENT)
    )

    private fun contentReadContext() = AgentNativeToolInvocationContext(
        grantedConsents = setOf(AgentWebMediaNativeTools.CONTENT_URI_READ_CONSENT)
    )

    private class MutableClock(var now: Long) : AgentNativeClock {
        override fun nowEpochMillis(): Long = now
    }

    private class FakeTransport(
        vararg responses: AgentWebTransportResponse,
        private val onExecute: () -> Unit = {}
    ) : AgentWebTransport {
        private val responses = ArrayDeque(responses.toList())
        val requests = mutableListOf<AgentWebTransportRequest>()

        override fun execute(request: AgentWebTransportRequest): AgentWebTransportResponse {
            requests += request
            onExecute()
            return responses.pollFirst() ?: error("No fake response configured")
        }
    }

    private class FakeResolver(vararg entries: Pair<String, List<InetAddress>>) : AgentHostResolver {
        private val answers = mapOf(*entries)
        override fun resolve(host: String): List<InetAddress> = answers[host].orEmpty()
    }

    private class FakeWriter : AgentContentUriWriter {
        override val availability = AgentNativeToolAvailability.AVAILABLE
        override val implementationId = "fake.writer"
        var uri: String? = null
        var contentType: String? = null
        var bytes: ByteArray? = null

        override fun write(contentUri: String, contentType: String, bytes: ByteArray): AgentContentWriteResult {
            this.uri = contentUri
            this.contentType = contentType
            this.bytes = bytes.copyOf()
            return AgentContentWriteResult(contentUri, bytes.size.toLong())
        }
    }

    private class FakeOcr(
        var currentAvailability: AgentNativeToolAvailability = AgentNativeToolAvailability.AVAILABLE,
        private val result: AgentOcrResult = AgentOcrResult("", emptyList(), 0, 0)
    ) : AgentContentOcr {
        override val availability: AgentNativeToolAvailability get() = currentAvailability
        override val implementationId = "fake.ocr"
        var request: AgentOcrRequest? = null

        override fun recognize(request: AgentOcrRequest): AgentOcrResult {
            this.request = request
            return result
        }
    }

    private class FakeInspector : AgentMediaInspector {
        override val availability = AgentNativeToolAvailability.AVAILABLE
        override val implementationId = "fake.media_inspector"
        var uri: String? = null

        override fun inspect(contentUri: String): AgentMediaMetadata {
            uri = contentUri
            return AgentMediaMetadata(
                contentUri,
                "video/mp4",
                "clip.mp4",
                1_024,
                4_000,
                1_920,
                1_080,
                0,
                hasAudio = true,
                hasVideo = true
            )
        }
    }

    private class FakePlayback : AgentMediaPlayback {
        override val availability = AgentNativeToolAvailability.AVAILABLE
        override val implementationId = "fake.playback"
        var uri: String? = null
        var contentType: String? = null

        override fun handoff(contentUri: String, contentType: String): AgentMediaPlaybackResult {
            uri = contentUri
            this.contentType = contentType
            return AgentMediaPlaybackResult(true, "android.intent.action.VIEW", "fake.player")
        }
    }

    private fun publicAddress(a: Int, b: Int, c: Int, d: Int): InetAddress = address(a, b, c, d)
    private fun privateAddress(a: Int, b: Int, c: Int, d: Int): InetAddress = address(a, b, c, d)

    private fun address(a: Int, b: Int, c: Int, d: Int): InetAddress = InetAddress.getByAddress(
        byteArrayOf(a.toByte(), b.toByte(), c.toByte(), d.toByte())
    )

    private fun hexSha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }
}
