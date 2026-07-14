package com.signalasi.chat

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import java.io.ByteArrayOutputStream
import java.net.IDN
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.URI
import java.net.UnknownHostException
import java.nio.charset.Charset
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.TimeoutException
import java.util.concurrent.TimeUnit
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request

enum class AgentWebMethod { HEAD, GET }

data class AgentWebTransportRequest(
    val method: AgentWebMethod,
    val uri: URI,
    val headers: Map<String, String>,
    val resolvedAddresses: List<InetAddress>,
    val timeoutMillis: Long,
    val maxBodyBytes: Long,
    val cancellationToken: AgentNativeToolCancellationToken = AgentNativeToolCancellationToken.NONE,
    val checkpoint: () -> Unit = {}
)

data class AgentWebTransportResponse(
    val statusCode: Int,
    val headers: Map<String, List<String>> = emptyMap(),
    val body: ByteArray = ByteArray(0)
)

interface AgentWebTransport {
    val availability: AgentNativeToolAvailability
        get() = AgentNativeToolAvailability.AVAILABLE

    fun execute(request: AgentWebTransportRequest): AgentWebTransportResponse
}

fun interface AgentHostResolver {
    fun resolve(host: String): List<InetAddress>
}

object AgentSystemHostResolver : AgentHostResolver {
    override fun resolve(host: String): List<InetAddress> = InetAddress.getAllByName(host).toList()
}

/** OkHttp transport whose DNS result is pinned to the addresses approved by the web policy. */
class AgentPinnedOkHttpWebTransport(
    private val baseClient: OkHttpClient = OkHttpClient()
) : AgentWebTransport {
    override fun execute(request: AgentWebTransportRequest): AgentWebTransportResponse {
        request.checkpoint()
        val expectedHost = request.uri.host
        val pinnedDns = object : Dns {
            override fun lookup(hostname: String): List<InetAddress> {
                if (!hostname.equals(expectedHost, ignoreCase = true)) {
                    throw UnknownHostException("Unexpected host requested by transport: $hostname")
                }
                return request.resolvedAddresses
            }
        }
        val timeout = request.timeoutMillis.coerceAtLeast(1L)
        val client = baseClient.newBuilder()
            .dns(pinnedDns)
            .followRedirects(false)
            .followSslRedirects(false)
            .connectTimeout(timeout, TimeUnit.MILLISECONDS)
            .readTimeout(timeout, TimeUnit.MILLISECONDS)
            .callTimeout(timeout, TimeUnit.MILLISECONDS)
            .build()
        val builder = Request.Builder().url(request.uri.toString())
        request.headers.forEach(builder::header)
        when (request.method) {
            AgentWebMethod.HEAD -> builder.head()
            AgentWebMethod.GET -> builder.get()
        }
        val call = client.newCall(builder.build())
        val cancellation = request.cancellationToken.invokeOnCancellation(call::cancel)
        try {
            call.execute().use { response ->
                val body = if (request.method == AgentWebMethod.HEAD || response.code !in 200..299) {
                    ByteArray(0)
                } else {
                    response.body?.byteStream()?.use { input ->
                        readBounded(input.readBytesChunked(request), request.maxBodyBytes)
                    } ?: ByteArray(0)
                }
                return AgentWebTransportResponse(
                    statusCode = response.code,
                    headers = response.headers.toMultimap(),
                    body = body
                )
            }
        } finally {
            cancellation.dispose()
        }
    }

    private fun java.io.InputStream.readBytesChunked(request: AgentWebTransportRequest): Sequence<ByteArray> = sequence {
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            request.checkpoint()
            val read = read(buffer)
            if (read < 0) break
            if (read == 0) continue
            yield(buffer.copyOf(read))
        }
    }

    private fun readBounded(chunks: Sequence<ByteArray>, maxBytes: Long): ByteArray {
        val output = ByteArrayOutputStream()
        var total = 0L
        chunks.forEach { chunk ->
            total += chunk.size
            if (total > maxBytes) {
                throw AgentWebMediaException(
                    "response_too_large",
                    "HTTPS response exceeded the $maxBytes byte limit"
                )
            }
            output.write(chunk)
        }
        return output.toByteArray()
    }
}

data class AgentWebPolicy(
    val maxRedirects: Int = 4,
    val maxFetchBytes: Long = 1_048_576L,
    val maxDownloadBytes: Long = 12L * 1_048_576L,
    val maxTimeoutMillis: Long = 15_000L,
    val maxDnsAddresses: Int = 16,
    val maxUrlCharacters: Int = 4_096
) {
    init {
        require(maxRedirects in 0..10)
        require(maxFetchBytes > 0)
        require(maxDownloadBytes > 0)
        require(maxTimeoutMillis > 0)
        require(maxDnsAddresses > 0)
        require(maxUrlCharacters > 0)
    }
}

data class AgentWebRedirect(val statusCode: Int, val fromUrl: String, val toUrl: String)

data class AgentWebDnsResolution(val host: String, val addresses: List<String>)

data class AgentWebResource(
    val method: AgentWebMethod,
    val requestedUrl: String,
    val finalUrl: String,
    val statusCode: Int,
    val contentType: String,
    val contentLengthBytes: Long,
    val body: ByteArray,
    val redirects: List<AgentWebRedirect>,
    val dnsResolutions: List<AgentWebDnsResolution>,
    val requestedAtEpochMillis: Long,
    val retrievedAtEpochMillis: Long,
    val selectedHeaders: Map<String, String>
)

class AgentWebMediaException(
    val code: String,
    override val message: String,
    val retryable: Boolean = false,
    val details: AgentNativeJsonObject = emptyMap(),
    cause: Throwable? = null
) : RuntimeException(message, cause)

class AgentBoundedWebService(
    private val transport: AgentWebTransport,
    private val resolver: AgentHostResolver = AgentSystemHostResolver,
    private val clock: AgentNativeClock = AgentNativeClock.SYSTEM,
    val policy: AgentWebPolicy = AgentWebPolicy()
) {
    val availability: AgentNativeToolAvailability get() = transport.availability

    fun head(
        url: String,
        timeoutMillis: Long = policy.maxTimeoutMillis,
        cancellationToken: AgentNativeToolCancellationToken = AgentNativeToolCancellationToken.NONE,
        checkpoint: () -> Unit = {}
    ): AgentWebResource = request(
        method = AgentWebMethod.HEAD,
        url = url,
        maxBodyBytes = 0,
        timeoutMillis = timeoutMillis,
        cancellationToken = cancellationToken,
        checkpoint = checkpoint
    )

    fun fetch(
        url: String,
        maxBytes: Long = policy.maxFetchBytes,
        timeoutMillis: Long = policy.maxTimeoutMillis,
        cancellationToken: AgentNativeToolCancellationToken = AgentNativeToolCancellationToken.NONE,
        checkpoint: () -> Unit = {}
    ): AgentWebResource {
        if (maxBytes !in 1..policy.maxFetchBytes) {
            throw AgentWebMediaException("invalid_limit", "Fetch limit must be between 1 and ${policy.maxFetchBytes} bytes")
        }
        return request(
            AgentWebMethod.GET,
            url,
            maxBytes,
            timeoutMillis,
            cancellationToken,
            checkpoint,
            ::isFetchContentType
        )
    }

    fun download(
        url: String,
        maxBytes: Long = policy.maxDownloadBytes,
        timeoutMillis: Long = policy.maxTimeoutMillis,
        cancellationToken: AgentNativeToolCancellationToken = AgentNativeToolCancellationToken.NONE,
        checkpoint: () -> Unit = {}
    ): AgentWebResource {
        if (maxBytes !in 1..policy.maxDownloadBytes) {
            throw AgentWebMediaException(
                "invalid_limit",
                "Download limit must be between 1 and ${policy.maxDownloadBytes} bytes"
            )
        }
        return request(
            AgentWebMethod.GET,
            url,
            maxBytes,
            timeoutMillis,
            cancellationToken,
            checkpoint,
            ::isDownloadContentType
        )
    }

    private fun request(
        method: AgentWebMethod,
        url: String,
        maxBodyBytes: Long,
        timeoutMillis: Long,
        cancellationToken: AgentNativeToolCancellationToken,
        checkpoint: () -> Unit,
        contentTypeAllowed: (String) -> Boolean = { true }
    ): AgentWebResource {
        if (timeoutMillis !in 1..policy.maxTimeoutMillis) {
            throw AgentWebMediaException(
                "invalid_timeout",
                "Timeout must be between 1 and ${policy.maxTimeoutMillis} milliseconds"
            )
        }
        val startedAt = clock.nowEpochMillis()
        val deadline = safeDeadline(startedAt, timeoutMillis)
        val initial = validateHttpsUri(url)
        var current = initial
        val redirects = mutableListOf<AgentWebRedirect>()
        val resolutions = mutableListOf<AgentWebDnsResolution>()
        val visited = linkedSetOf<String>()
        visited += current.toString()

        while (true) {
            checkpoint()
            if (cancellationToken.isCancellationRequested) throw AgentNativeToolCancelledException()
            val remaining = deadline - clock.nowEpochMillis()
            if (remaining <= 0) throw AgentNativeToolTimeoutException()
            val addresses = resolvePublic(current.host)
            resolutions += AgentWebDnsResolution(current.host, addresses.map(::canonicalAddress))
            val response = try {
                transport.execute(
                    AgentWebTransportRequest(
                        method = method,
                        uri = current,
                        headers = requestHeaders(method),
                        resolvedAddresses = addresses,
                        timeoutMillis = remaining,
                        maxBodyBytes = maxBodyBytes,
                        cancellationToken = cancellationToken,
                        checkpoint = checkpoint
                    )
                )
            } catch (error: AgentWebMediaException) {
                throw error
            } catch (error: AgentNativeToolCancelledException) {
                throw error
            } catch (error: AgentNativeToolTimeoutException) {
                throw error
            } catch (error: Exception) {
                if (cancellationToken.isCancellationRequested) throw AgentNativeToolCancelledException()
                checkpoint()
                if (clock.nowEpochMillis() >= deadline) throw AgentNativeToolTimeoutException()
                throw AgentWebMediaException(
                    code = "transport_failed",
                    message = error.message ?: "HTTPS transport failed",
                    retryable = true,
                    details = mapOf("url" to current.toString()),
                    cause = error
                )
            }
            checkpoint()
            if (clock.nowEpochMillis() >= deadline) throw AgentNativeToolTimeoutException()

            if (response.statusCode in REDIRECT_STATUS_CODES) {
                if (redirects.size >= policy.maxRedirects) {
                    throw AgentWebMediaException("too_many_redirects", "HTTPS response exceeded the redirect limit")
                }
                val location = header(response.headers, "Location")
                    ?: throw AgentWebMediaException("redirect_missing_location", "HTTPS redirect did not include Location")
                if (location.length > policy.maxUrlCharacters) {
                    throw AgentWebMediaException("redirect_url_too_long", "HTTPS redirect URL is too long")
                }
                val next = try {
                    validateHttpsUri(current.resolve(location).toString())
                } catch (error: IllegalArgumentException) {
                    throw AgentWebMediaException("invalid_redirect", error.message ?: "HTTPS redirect is invalid")
                }
                if (!visited.add(next.toString())) {
                    throw AgentWebMediaException("redirect_loop", "HTTPS redirect loop detected")
                }
                redirects += AgentWebRedirect(response.statusCode, current.toString(), next.toString())
                current = next
                continue
            }

            if (response.statusCode !in 200..299) {
                throw AgentWebMediaException(
                    "http_status",
                    "HTTPS resource returned HTTP ${response.statusCode}",
                    retryable = response.statusCode == 408 || response.statusCode == 429 || response.statusCode >= 500,
                    details = mapOf("status_code" to response.statusCode, "url" to current.toString())
                )
            }
            val rawContentType = header(response.headers, "Content-Type").orEmpty()
            if (rawContentType.length > MAX_HEADER_VALUE_CHARS) {
                throw AgentWebMediaException("header_too_large", "Content-Type header is too large")
            }
            val contentType = rawContentType.substringBefore(';').trim().lowercase(Locale.ROOT)
            if (method == AgentWebMethod.GET && (contentType.isBlank() || !contentTypeAllowed(contentType))) {
                throw AgentWebMediaException(
                    "unsupported_content_type",
                    "HTTPS content type is not allowed: ${contentType.ifBlank { "missing" }}",
                    details = mapOf("content_type" to contentType)
                )
            }
            val declaredLength = contentLength(response.headers)
            if (method == AgentWebMethod.GET && declaredLength != null && declaredLength > maxBodyBytes) {
                throw AgentWebMediaException(
                    "response_too_large",
                    "HTTPS Content-Length exceeds the $maxBodyBytes byte limit",
                    details = mapOf("content_length_bytes" to declaredLength, "max_bytes" to maxBodyBytes)
                )
            }
            if (method == AgentWebMethod.GET && response.body.size.toLong() > maxBodyBytes) {
                throw AgentWebMediaException(
                    "response_too_large",
                    "HTTPS response exceeded the $maxBodyBytes byte limit"
                )
            }
            return AgentWebResource(
                method = method,
                requestedUrl = initial.toString(),
                finalUrl = current.toString(),
                statusCode = response.statusCode,
                contentType = contentType,
                contentLengthBytes = declaredLength ?: if (method == AgentWebMethod.GET) response.body.size.toLong() else -1L,
                body = if (method == AgentWebMethod.GET) response.body else ByteArray(0),
                redirects = redirects.toList(),
                dnsResolutions = resolutions.toList(),
                requestedAtEpochMillis = startedAt,
                retrievedAtEpochMillis = clock.nowEpochMillis(),
                selectedHeaders = SELECTED_RESPONSE_HEADERS.mapNotNull { name ->
                    header(response.headers, name)?.take(MAX_HEADER_VALUE_CHARS)?.let { name.lowercase(Locale.ROOT) to it }
                }.toMap()
            )
        }
    }

    private fun validateHttpsUri(value: String): URI {
        val trimmed = value.trim()
        if (trimmed.length !in 1..policy.maxUrlCharacters) {
            throw AgentWebMediaException("invalid_url", "HTTPS URL is blank or too long")
        }
        val parsed = try {
            URI(trimmed)
        } catch (error: Exception) {
            throw AgentWebMediaException("invalid_url", "HTTPS URL is invalid", cause = error)
        }
        if (!parsed.scheme.equals("https", ignoreCase = true) || parsed.host.isNullOrBlank() || parsed.userInfo != null) {
            throw AgentWebMediaException("https_required", "Only public HTTPS URLs without user information are allowed")
        }
        if (parsed.port !in -1..65535 || parsed.port == 0) {
            throw AgentWebMediaException("invalid_port", "HTTPS URL port is invalid")
        }
        val asciiHost = try {
            if (parsed.host.contains(':')) {
                parsed.host.lowercase(Locale.ROOT)
            } else {
                IDN.toASCII(parsed.host, IDN.USE_STD3_ASCII_RULES).lowercase(Locale.ROOT)
            }
        } catch (error: Exception) {
            throw AgentWebMediaException("invalid_host", "HTTPS host is invalid", cause = error)
        }
        if (asciiHost == "localhost" || asciiHost.endsWith(".localhost") || asciiHost.endsWith(".local")) {
            throw AgentWebMediaException("local_host_blocked", "Localhost and local-network hostnames are blocked")
        }
        return URI(
            "https",
            null,
            asciiHost,
            parsed.port,
            parsed.rawPath.ifBlank { "/" },
            parsed.rawQuery,
            null
        )
    }

    private fun resolvePublic(host: String): List<InetAddress> {
        val addresses = try {
            resolver.resolve(host)
        } catch (error: Exception) {
            throw AgentWebMediaException(
                "dns_failed",
                error.message ?: "DNS resolution failed",
                retryable = true,
                details = mapOf("host" to host),
                cause = error
            )
        }.distinctBy(::canonicalAddress)
        if (addresses.isEmpty()) {
            throw AgentWebMediaException("dns_empty", "DNS returned no addresses", retryable = true)
        }
        if (addresses.size > policy.maxDnsAddresses) {
            throw AgentWebMediaException("dns_too_many_addresses", "DNS returned too many addresses")
        }
        val blocked = addresses.filterNot(AgentPublicAddressPolicy::isPublic)
        if (blocked.isNotEmpty()) {
            throw AgentWebMediaException(
                "private_network_blocked",
                "Private, local, special-use, and multicast network addresses are blocked",
                details = mapOf("host" to host, "blocked_addresses" to blocked.map(::canonicalAddress))
            )
        }
        return addresses
    }

    private fun requestHeaders(method: AgentWebMethod): Map<String, String> = mapOf(
        "Accept" to if (method == AgentWebMethod.HEAD) "*/*" else "text/*, application/json, application/xml;q=0.9, */*;q=0.5",
        "Accept-Encoding" to "identity",
        "User-Agent" to "SignalASI-Android-NativeTools/1.0"
    )

    private fun contentLength(headers: Map<String, List<String>>): Long? {
        val value = header(headers, "Content-Length") ?: return null
        return value.trim().toLongOrNull()?.takeIf { it >= 0 }
            ?: throw AgentWebMediaException("invalid_content_length", "HTTPS Content-Length is invalid")
    }

    private fun isFetchContentType(contentType: String): Boolean =
        contentType.startsWith("text/") || contentType in FETCH_APPLICATION_TYPES

    private fun isDownloadContentType(contentType: String): Boolean =
        DOWNLOAD_TOP_LEVEL_TYPES.any(contentType::startsWith) || contentType in DOWNLOAD_APPLICATION_TYPES

    private fun safeDeadline(startedAt: Long, timeoutMillis: Long): Long =
        if (Long.MAX_VALUE - startedAt < timeoutMillis) Long.MAX_VALUE else startedAt + timeoutMillis

    companion object {
        private const val MAX_HEADER_VALUE_CHARS = 2_048
        private val REDIRECT_STATUS_CODES = setOf(301, 302, 303, 307, 308)
        private val SELECTED_RESPONSE_HEADERS = listOf(
            "Content-Type",
            "ETag",
            "Last-Modified",
            "Cache-Control",
            "Content-Disposition"
        )
        private val FETCH_APPLICATION_TYPES = setOf(
            "application/json",
            "application/ld+json",
            "application/xml",
            "application/xhtml+xml",
            "application/rss+xml",
            "application/atom+xml"
        )
        private val DOWNLOAD_TOP_LEVEL_TYPES = listOf("text/", "image/", "audio/", "video/")
        private val DOWNLOAD_APPLICATION_TYPES = FETCH_APPLICATION_TYPES + setOf(
            "application/pdf",
            "application/zip",
            "application/gzip",
            "application/octet-stream",
            "application/msword",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
            "application/vnd.openxmlformats-officedocument.presentationml.presentation"
        )

        private fun header(headers: Map<String, List<String>>, name: String): String? = headers.entries
            .firstOrNull { it.key.equals(name, ignoreCase = true) }
            ?.value
            ?.firstOrNull()

        private fun canonicalAddress(address: InetAddress): String = address.hostAddress.orEmpty().substringBefore('%')
    }
}

object AgentPublicAddressPolicy {
    fun isPublic(address: InetAddress): Boolean {
        if (
            address.isAnyLocalAddress || address.isLoopbackAddress || address.isLinkLocalAddress ||
            address.isSiteLocalAddress || address.isMulticastAddress
        ) return false
        val bytes = address.address
        return when (address) {
            is Inet4Address -> isPublicV4(bytes)
            is Inet6Address -> isPublicV6(bytes)
            else -> false
        }
    }

    private fun isPublicV4(bytes: ByteArray): Boolean {
        val a = bytes[0].toInt() and 0xff
        val b = bytes[1].toInt() and 0xff
        val c = bytes[2].toInt() and 0xff
        return when {
            a == 0 || a == 10 || a == 127 -> false
            a == 100 && b in 64..127 -> false
            a == 169 && b == 254 -> false
            a == 172 && b in 16..31 -> false
            a == 192 && b == 168 -> false
            a == 192 && b == 0 && c == 0 -> false
            a == 192 && b == 0 && c == 2 -> false
            a == 192 && b == 88 && c == 99 -> false
            a == 198 && b in 18..19 -> false
            a == 198 && b == 51 && c == 100 -> false
            a == 203 && b == 0 && c == 113 -> false
            a >= 224 -> false
            else -> true
        }
    }

    private fun isPublicV6(bytes: ByteArray): Boolean {
        val first = bytes[0].toInt() and 0xff
        val second = bytes[1].toInt() and 0xff
        if (first and 0xfe == 0xfc) return false
        if (first == 0xfe && second and 0xc0 == 0x80) return false
        if (first == 0xff) return false
        if (
            first == 0x20 && second == 0x01 &&
            (bytes[2].toInt() and 0xff) == 0x0d && (bytes[3].toInt() and 0xff) == 0xb8
        ) return false
        return true
    }
}

data class AgentContentWriteResult(val contentUri: String, val bytesWritten: Long)

interface AgentContentUriWriter {
    val availability: AgentNativeToolAvailability
    val implementationId: String
    fun write(contentUri: String, contentType: String, bytes: ByteArray): AgentContentWriteResult
}

class AgentAndroidContentUriWriter(
    private val resolver: ContentResolver
) : AgentContentUriWriter {
    constructor(context: Context) : this(context.applicationContext.contentResolver)

    override val availability = AgentNativeToolAvailability.AVAILABLE
    override val implementationId = "android.content_resolver"

    override fun write(contentUri: String, contentType: String, bytes: ByteArray): AgentContentWriteResult {
        val uri = parseAndroidContentUri(contentUri)
        val output = resolver.openOutputStream(uri, "w")
            ?: throw AgentWebMediaException("content_uri_unavailable", "Selected destination content URI cannot be opened")
        output.use { it.write(bytes) }
        return AgentContentWriteResult(uri.toString(), bytes.size.toLong())
    }
}

enum class AgentOcrSourceKind(val wireValue: String) {
    SELECTED("selected"),
    CAPTURED("captured")
}

data class AgentOcrRequest(
    val contentUri: String,
    val sourceKind: AgentOcrSourceKind,
    val maxSourceBytes: Long,
    val timeoutMillis: Long
)

data class AgentOcrLine(
    val text: String,
    val left: Int = 0,
    val top: Int = 0,
    val right: Int = 0,
    val bottom: Int = 0
)

data class AgentOcrResult(
    val text: String,
    val lines: List<AgentOcrLine>,
    val width: Int,
    val height: Int,
    val languageTags: List<String> = emptyList()
)

interface AgentContentOcr {
    val availability: AgentNativeToolAvailability
    val implementationId: String
    fun recognize(request: AgentOcrRequest): AgentOcrResult
}

class AgentAndroidMlKitContentOcr(
    private val resolver: ContentResolver
) : AgentContentOcr {
    constructor(context: Context) : this(context.applicationContext.contentResolver)

    override val availability = AgentNativeToolAvailability.AVAILABLE
    override val implementationId = "mlkit.chinese_text_recognition"

    override fun recognize(request: AgentOcrRequest): AgentOcrResult {
        val uri = parseAndroidContentUri(request.contentUri)
        val bytes = readContentUriBounded(resolver, uri, request.maxSourceBytes)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            throw AgentWebMediaException("unsupported_ocr_content", "Selected content is not a decodable image")
        }
        if (bounds.outWidth.toLong() * bounds.outHeight.toLong() > MAX_OCR_PIXELS) {
            throw AgentWebMediaException("ocr_image_too_large", "OCR image exceeds the decoded pixel limit")
        }
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            ?: throw AgentWebMediaException("unsupported_ocr_content", "Selected content is not a decodable image")
        val recognizer = TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
        try {
            val text = Tasks.await(
                recognizer.process(InputImage.fromBitmap(bitmap, 0)),
                request.timeoutMillis,
                TimeUnit.MILLISECONDS
            )
            val lines = text.textBlocks.flatMap { it.lines }.map { line ->
                val box = line.boundingBox
                AgentOcrLine(
                    text = line.text,
                    left = box?.left ?: 0,
                    top = box?.top ?: 0,
                    right = box?.right ?: 0,
                    bottom = box?.bottom ?: 0
                )
            }
            return AgentOcrResult(text.text, lines, bitmap.width, bitmap.height)
        } catch (_: TimeoutException) {
            throw AgentNativeToolTimeoutException()
        } finally {
            bitmap.recycle()
            recognizer.close()
        }
    }

    companion object {
        private const val MAX_OCR_PIXELS = 20_000_000L
    }
}

data class AgentMediaMetadata(
    val contentUri: String,
    val contentType: String,
    val displayName: String,
    val sizeBytes: Long,
    val durationMillis: Long,
    val width: Int,
    val height: Int,
    val rotationDegrees: Int,
    val hasAudio: Boolean,
    val hasVideo: Boolean
)

interface AgentMediaInspector {
    val availability: AgentNativeToolAvailability
    val implementationId: String
    fun inspect(contentUri: String): AgentMediaMetadata
}

class AgentAndroidMediaInspector(
    private val context: Context
) : AgentMediaInspector {
    private val resolver = context.applicationContext.contentResolver
    override val availability = AgentNativeToolAvailability.AVAILABLE
    override val implementationId = "android.media_metadata_retriever"

    override fun inspect(contentUri: String): AgentMediaMetadata {
        val uri = parseAndroidContentUri(contentUri)
        val contentType = resolver.getType(uri).orEmpty().lowercase(Locale.ROOT)
        var displayName = ""
        var sizeBytes = -1L
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val nameColumn = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val sizeColumn = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (nameColumn >= 0) displayName = cursor.getString(nameColumn).orEmpty()
                if (sizeColumn >= 0 && !cursor.isNull(sizeColumn)) {
                    sizeBytes = cursor.getLong(sizeColumn)
                }
            }
        }
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(context, uri)
            val duration = retriever.longMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
            val width = retriever.intMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
            val height = retriever.intMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
            val rotation = retriever.intMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)
            val hasAudio = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO) == "yes"
            val hasVideo = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO) == "yes" || width > 0
            return AgentMediaMetadata(
                uri.toString(),
                contentType,
                displayName,
                sizeBytes,
                duration,
                width,
                height,
                rotation,
                hasAudio,
                hasVideo
            )
        } catch (error: Exception) {
            if (!contentType.startsWith("image/")) throw error
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
            if (options.outWidth <= 0 || options.outHeight <= 0) throw error
            return AgentMediaMetadata(
                uri.toString(), contentType, displayName, sizeBytes, 0, options.outWidth, options.outHeight, 0, false, false
            )
        } finally {
            retriever.release()
        }
    }

    private fun MediaMetadataRetriever.longMetadata(key: Int): Long = extractMetadata(key)?.toLongOrNull() ?: 0L
    private fun MediaMetadataRetriever.intMetadata(key: Int): Int = extractMetadata(key)?.toIntOrNull() ?: 0
}

data class AgentMediaPlaybackResult(
    val launched: Boolean,
    val action: String,
    val handlerPackage: String = ""
)

interface AgentMediaPlayback {
    val availability: AgentNativeToolAvailability
    val implementationId: String
    fun handoff(contentUri: String, contentType: String): AgentMediaPlaybackResult
}

class AgentAndroidMediaPlayback(
    private val context: Context
) : AgentMediaPlayback {
    override val availability = AgentNativeToolAvailability.AVAILABLE
    override val implementationId = "android.intent.action_view"

    override fun handoff(contentUri: String, contentType: String): AgentMediaPlaybackResult {
        val uri = parseAndroidContentUri(contentUri)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, contentType.ifBlank { "*/*" })
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val handler = intent.resolveActivity(context.packageManager)
            ?: throw AgentWebMediaException("playback_unavailable", "No installed app can play the selected media")
        context.startActivity(intent)
        return AgentMediaPlaybackResult(true, Intent.ACTION_VIEW, handler.packageName)
    }
}

data class AgentWebMediaServices(
    val web: AgentBoundedWebService,
    val contentWriter: AgentContentUriWriter,
    val ocr: AgentContentOcr,
    val mediaInspector: AgentMediaInspector,
    val mediaPlayback: AgentMediaPlayback
)

object AgentWebMediaNativeTools {
    const val WEB_HEAD = "signalasi.web.head"
    const val WEB_FETCH = "signalasi.web.fetch"
    const val WEB_DOWNLOAD = "signalasi.web.download"
    const val OCR_RECOGNIZE_CONTENT = "signalasi.ocr.content.recognize"
    const val MEDIA_METADATA = "signalasi.media.metadata"
    const val MEDIA_PLAYBACK_HANDOFF = "signalasi.media.playback.handoff"
    const val MEDIA_FFMPEG_TRANSCODE = "signalasi.media.ffmpeg.transcode"

    const val INTERNET_PERMISSION = "android.permission.INTERNET"
    const val PUBLIC_WEB_CONSENT = "signalasi.consent.public_web"
    const val WEB_DOWNLOAD_CONSENT = "signalasi.consent.web_download"
    const val CONTENT_URI_READ_CONSENT = "signalasi.consent.content_uri_read"
    const val CONTENT_URI_WRITE_CONSENT = "signalasi.consent.content_uri_write"
    const val MEDIA_PLAYBACK_CONSENT = "signalasi.consent.media_playback"

    const val MAX_FETCH_BYTES = 1_048_576L
    const val MAX_DOWNLOAD_BYTES = 12L * 1_048_576L
    const val MAX_CONTENT_URI_CHARS = 4_096
    const val MAX_OCR_SOURCE_BYTES = 12L * 1_048_576L
    const val MAX_OCR_TEXT_CHARS = 240_000
    const val MAX_OCR_LINES = 500
    const val MAX_TOOL_TIMEOUT_MILLIS = 15_000L

    private const val VERSION = "1.0.0"
    private const val WEB_EXECUTOR_ID = "signalasi.bounded_https"
    private const val CONTENT_EXECUTOR_ID = "signalasi.android_content_uri"
    private const val MEDIA_EXECUTOR_ID = "signalasi.android_media_handoff"
    private val FFMPEG_UNAVAILABLE = AgentNativeToolAvailability(
        AgentNativeToolAvailabilityStatus.UNAVAILABLE,
        "FFmpeg transcoding is not installed or supported in the phone runtime"
    )

    val toolIds: Set<String> = linkedSetOf(
        WEB_HEAD,
        WEB_FETCH,
        WEB_DOWNLOAD,
        OCR_RECOGNIZE_CONTENT,
        MEDIA_METADATA,
        MEDIA_PLAYBACK_HANDOFF,
        MEDIA_FFMPEG_TRANSCODE
    )

    fun androidServices(
        context: Context,
        transport: AgentWebTransport = AgentPinnedOkHttpWebTransport(),
        resolver: AgentHostResolver = AgentSystemHostResolver,
        clock: AgentNativeClock = AgentNativeClock.SYSTEM
    ): AgentWebMediaServices = AgentWebMediaServices(
        web = AgentBoundedWebService(
            transport,
            resolver,
            clock,
            AgentWebPolicy(maxFetchBytes = MAX_FETCH_BYTES, maxDownloadBytes = MAX_DOWNLOAD_BYTES)
        ),
        contentWriter = AgentAndroidContentUriWriter(context),
        ocr = AgentAndroidMlKitContentOcr(context),
        mediaInspector = AgentAndroidMediaInspector(context.applicationContext),
        mediaPlayback = AgentAndroidMediaPlayback(context.applicationContext)
    )

    fun createRegistry(
        services: AgentWebMediaServices,
        clock: AgentNativeClock = AgentNativeClock.SYSTEM
    ): AgentNativeToolRegistry = AgentNativeToolRegistry(clock).registerAll(definitions(services))

    fun definitions(services: AgentWebMediaServices): List<AgentNativeToolDefinition> = listOf(
        webHeadDefinition(services.web),
        webFetchDefinition(services.web),
        webDownloadDefinition(services.web, services.contentWriter),
        ocrDefinition(services.ocr),
        mediaMetadataDefinition(services.mediaInspector),
        mediaPlaybackDefinition(services.mediaPlayback),
        ffmpegUnavailableDefinition()
    )

    private fun webHeadDefinition(web: AgentBoundedWebService) = AgentNativeToolDefinition(
        descriptor = webDescriptor(
            id = WEB_HEAD,
            title = "Inspect public HTTPS resource",
            description = "Performs a redirect-bounded HEAD request to a DNS-validated public HTTPS resource.",
            outputSchema = webHeadOutputSchema(),
            availability = web.availability
        ),
        executor = AgentNativeToolExecutor { invocation ->
            executeBounded {
                val resource = web.head(
                    invocation.input.string("url"),
                    invocation.input.timeout(invocation),
                    invocation.cancellationToken,
                    invocation::checkpoint
                )
                AgentNativeToolExecutionResult.success(
                    output = resource.commonValue(),
                    message = "Public HTTPS resource inspected",
                    metadata = mapOf("network_policy" to "public_https_pinned_dns_v1")
                )
            }
        },
        executorId = WEB_EXECUTOR_ID,
        provenanceMetadata = webProvenance(),
        availabilityProvider = AgentNativeToolAvailabilityProvider { web.availability }
    )

    private fun webFetchDefinition(web: AgentBoundedWebService) = AgentNativeToolDefinition(
        descriptor = webDescriptor(
            id = WEB_FETCH,
            title = "Fetch public HTTPS text",
            description = "Fetches bounded textual content from a DNS-validated public HTTPS resource.",
            inputSchema = webGetInputSchema(MAX_FETCH_BYTES),
            outputSchema = webFetchOutputSchema(),
            availability = web.availability
        ),
        executor = AgentNativeToolExecutor { invocation ->
            executeBounded {
                val resource = web.fetch(
                    invocation.input.string("url"),
                    invocation.input.long("max_bytes", MAX_FETCH_BYTES),
                    invocation.input.timeout(invocation),
                    invocation.cancellationToken,
                    invocation::checkpoint
                )
                val charset = charset(resource.contentType, resource.selectedHeaders)
                val text = resource.body.toString(charset)
                AgentNativeToolExecutionResult.success(
                    output = resource.commonValue() + mapOf(
                        "text" to text,
                        "charset" to charset.name(),
                        "size_bytes" to resource.body.size.toLong(),
                        "sha256" to sha256(resource.body)
                    ),
                    message = "Public HTTPS text fetched",
                    metadata = mapOf("network_policy" to "public_https_pinned_dns_v1")
                )
            }
        },
        executorId = WEB_EXECUTOR_ID,
        provenanceMetadata = webProvenance(),
        availabilityProvider = AgentNativeToolAvailabilityProvider { web.availability }
    )

    private fun webDownloadDefinition(web: AgentBoundedWebService, writer: AgentContentUriWriter) =
        AgentNativeToolDefinition(
            descriptor = webDescriptor(
                id = WEB_DOWNLOAD,
                title = "Download public HTTPS resource",
                description = "Downloads bounded non-executable bytes to a user-authorized Android content URI.",
                inputSchema = objectSchema(
                    properties = webInputProperties(MAX_DOWNLOAD_BYTES) + (
                        "destination_content_uri" to contentUriSchema()
                    ),
                    required = setOf("url", "destination_content_uri")
                ),
                outputSchema = webDownloadOutputSchema(),
                risk = AgentNativeToolRisk.MEDIUM,
                consents = listOf(PUBLIC_WEB_CONSENT, WEB_DOWNLOAD_CONSENT, CONTENT_URI_WRITE_CONSENT),
                idempotency = AgentNativeToolIdempotency.IDEMPOTENCY_KEY_REQUIRED,
                availability = combineAvailability(web.availability, writer.availability)
            ),
            executor = AgentNativeToolExecutor { invocation ->
                executeBounded {
                    val destination = invocation.input.string("destination_content_uri")
                    validateContentUri(destination)
                    val resource = web.download(
                        invocation.input.string("url"),
                        invocation.input.long("max_bytes", MAX_DOWNLOAD_BYTES),
                        invocation.input.timeout(invocation),
                        invocation.cancellationToken,
                        invocation::checkpoint
                    )
                    invocation.checkpoint()
                    val written = writer.write(destination, resource.contentType, resource.body)
                    AgentNativeToolExecutionResult.success(
                        output = resource.commonValue() + mapOf(
                            "destination_content_uri" to written.contentUri,
                            "size_bytes" to written.bytesWritten,
                            "sha256" to sha256(resource.body)
                        ),
                        message = "Public HTTPS resource downloaded to selected content URI",
                        metadata = mapOf(
                            "network_policy" to "public_https_pinned_dns_v1",
                            "writer_implementation" to writer.implementationId,
                            "auto_execute" to false
                        )
                    )
                }
            },
            executorId = CONTENT_EXECUTOR_ID,
            provenanceMetadata = webProvenance() + mapOf(
                "destination_scope" to "user_authorized_content_uri",
                "writer_implementation" to writer.implementationId,
                "auto_execute" to "false"
            ),
            availabilityProvider = AgentNativeToolAvailabilityProvider {
                combineAvailability(web.availability, writer.availability)
            }
        )

    private fun ocrDefinition(ocr: AgentContentOcr) = AgentNativeToolDefinition(
        descriptor = AgentNativeToolDescriptor(
            id = OCR_RECOGNIZE_CONTENT,
            version = VERSION,
            title = "Recognize selected or captured text",
            description = "Runs an available bounded OCR implementation on a selected or captured Android content URI.",
            location = AgentNativeToolLocation.APPLICATION,
            inputSchema = objectSchema(
                properties = mapOf(
                    "content_uri" to contentUriSchema(),
                    "source_kind" to AgentNativeJsonSchema.string(enumValues = AgentOcrSourceKind.entries.map { it.wireValue }),
                    "max_source_bytes" to AgentNativeJsonSchema.integer(1, MAX_OCR_SOURCE_BYTES),
                    "timeout_ms" to timeoutSchema()
                ),
                required = setOf("content_uri", "source_kind")
            ),
            outputSchema = ocrOutputSchema(),
            risk = AgentNativeToolRisk.LOW,
            capabilities = setOf("ocr.content_uri", "ocr.bounded", "content_uri.user_authorized"),
            requiredConsents = consents(CONTENT_URI_READ_CONSENT),
            timeoutMillis = MAX_TOOL_TIMEOUT_MILLIS,
            idempotency = AgentNativeToolIdempotency.IDEMPOTENT,
            availability = ocr.availability
        ),
        executor = AgentNativeToolExecutor { invocation ->
            executeBounded {
                val contentUri = invocation.input.string("content_uri")
                validateContentUri(contentUri)
                val sourceKind = AgentOcrSourceKind.entries.first {
                    it.wireValue == invocation.input.string("source_kind")
                }
                val result = ocr.recognize(
                    AgentOcrRequest(
                        contentUri,
                        sourceKind,
                        invocation.input.long("max_source_bytes", MAX_OCR_SOURCE_BYTES),
                        invocation.input.timeout(invocation)
                    )
                ).bounded()
                invocation.checkpoint()
                AgentNativeToolExecutionResult.success(
                    output = mapOf(
                        "text" to result.text,
                        "lines" to result.lines.map { line ->
                            mapOf(
                                "text" to line.text,
                                "left" to line.left,
                                "top" to line.top,
                                "right" to line.right,
                                "bottom" to line.bottom
                            )
                        },
                        "width" to result.width,
                        "height" to result.height,
                        "language_tags" to result.languageTags,
                        "observed_at_epoch_ms" to System.currentTimeMillis(),
                        "source" to mapOf("content_uri" to contentUri, "source_kind" to sourceKind.wireValue)
                    ),
                    message = "OCR completed for selected content",
                    metadata = mapOf("ocr_implementation" to ocr.implementationId)
                )
            }
        },
        executorId = CONTENT_EXECUTOR_ID,
        provenanceMetadata = mapOf(
            "implementation" to ocr.implementationId,
            "source_scope" to "selected_or_captured_content_uri",
            "result_policy" to "bounded-v1"
        ),
        availabilityProvider = AgentNativeToolAvailabilityProvider { ocr.availability }
    )

    private fun mediaMetadataDefinition(inspector: AgentMediaInspector) = AgentNativeToolDefinition(
        descriptor = mediaDescriptor(
            id = MEDIA_METADATA,
            title = "Inspect selected media",
            description = "Reads bounded metadata from a user-authorized Android media content URI.",
            outputSchema = mediaMetadataOutputSchema(),
            consentIds = listOf(CONTENT_URI_READ_CONSENT),
            availability = inspector.availability
        ),
        executor = AgentNativeToolExecutor { invocation ->
            executeBounded {
                val uri = invocation.input.string("content_uri")
                validateContentUri(uri)
                val metadata = inspector.inspect(uri).bounded(uri)
                AgentNativeToolExecutionResult.success(
                    output = metadata.value(System.currentTimeMillis()),
                    message = "Selected media metadata inspected",
                    metadata = mapOf("media_implementation" to inspector.implementationId)
                )
            }
        },
        executorId = MEDIA_EXECUTOR_ID,
        provenanceMetadata = mapOf(
            "implementation" to inspector.implementationId,
            "source_scope" to "user_authorized_content_uri"
        ),
        availabilityProvider = AgentNativeToolAvailabilityProvider { inspector.availability }
    )

    private fun mediaPlaybackDefinition(playback: AgentMediaPlayback) = AgentNativeToolDefinition(
        descriptor = mediaDescriptor(
            id = MEDIA_PLAYBACK_HANDOFF,
            title = "Hand media to Android playback",
            description = "Opens selected media in an Android playback handler without claiming playback completion.",
            inputSchema = objectSchema(
                properties = mapOf(
                    "content_uri" to contentUriSchema(),
                    "content_type" to AgentNativeJsonSchema.string(maxLength = 255)
                ),
                required = setOf("content_uri")
            ),
            outputSchema = mediaPlaybackOutputSchema(),
            risk = AgentNativeToolRisk.MEDIUM,
            consentIds = listOf(CONTENT_URI_READ_CONSENT, MEDIA_PLAYBACK_CONSENT),
            idempotency = AgentNativeToolIdempotency.NON_IDEMPOTENT,
            availability = playback.availability
        ),
        executor = AgentNativeToolExecutor { invocation ->
            executeBounded {
                val contentUri = invocation.input.string("content_uri")
                validateContentUri(contentUri)
                val result = playback.handoff(contentUri, invocation.input.string("content_type", ""))
                AgentNativeToolExecutionResult.success(
                    output = mapOf(
                        "launched" to result.launched,
                        "action" to result.action,
                        "handler_package" to result.handlerPackage,
                        "completed" to false,
                        "handed_off_at_epoch_ms" to System.currentTimeMillis(),
                        "source" to mapOf("content_uri" to contentUri)
                    ),
                    message = "Media playback handed off to Android",
                    metadata = mapOf("playback_implementation" to playback.implementationId)
                )
            }
        },
        executorId = MEDIA_EXECUTOR_ID,
        provenanceMetadata = mapOf(
            "implementation" to playback.implementationId,
            "completion_semantics" to "handoff_only"
        ),
        availabilityProvider = AgentNativeToolAvailabilityProvider { playback.availability }
    )

    private fun ffmpegUnavailableDefinition() = AgentNativeToolDefinition(
        descriptor = AgentNativeToolDescriptor(
            id = MEDIA_FFMPEG_TRANSCODE,
            version = VERSION,
            title = "Transcode media with FFmpeg",
            description = "Represents general FFmpeg conversion, which is explicitly unavailable in the phone runtime.",
            location = AgentNativeToolLocation.APPLICATION,
            inputSchema = objectSchema(
                properties = mapOf(
                    "content_uri" to contentUriSchema(),
                    "target_format" to AgentNativeJsonSchema.string(minLength = 1, maxLength = 64)
                ),
                required = setOf("content_uri", "target_format")
            ),
            outputSchema = objectSchema(),
            risk = AgentNativeToolRisk.BLOCKED,
            capabilities = setOf("media.transcode.ffmpeg"),
            timeoutMillis = 1_000,
            availability = FFMPEG_UNAVAILABLE
        ),
        executor = AgentNativeToolExecutor {
            AgentNativeToolExecutionResult.failure("ffmpeg_unavailable", FFMPEG_UNAVAILABLE.reason)
        },
        executorId = "signalasi.unavailable_phone_runtime",
        provenanceMetadata = mapOf(
            "implementation" to "none",
            "platform" to "android_phone",
            "status" to "unavailable"
        ),
        availabilityProvider = AgentNativeToolAvailabilityProvider { FFMPEG_UNAVAILABLE }
    )

    private fun webDescriptor(
        id: String,
        title: String,
        description: String,
        inputSchema: AgentNativeJsonSchema = webHeadInputSchema(),
        outputSchema: AgentNativeJsonSchema,
        risk: AgentNativeToolRisk = AgentNativeToolRisk.LOW,
        consents: List<String> = listOf(PUBLIC_WEB_CONSENT),
        idempotency: AgentNativeToolIdempotency = AgentNativeToolIdempotency.IDEMPOTENT,
        availability: AgentNativeToolAvailability
    ) = AgentNativeToolDescriptor(
        id = id,
        version = VERSION,
        title = title,
        description = description,
        location = AgentNativeToolLocation.APPLICATION,
        inputSchema = inputSchema,
        outputSchema = outputSchema,
        risk = risk,
        capabilities = setOf("network.public_https", "network.dns_pinned", "network.redirect_bounded"),
        requiredPermissions = listOf(
            AgentNativePermissionRequirement(
                INTERNET_PERMISSION,
                "Internet access",
                "Uses the app-declared Android Internet permission for public HTTPS only."
            )
        ),
        requiredConsents = consents(consents),
        timeoutMillis = MAX_TOOL_TIMEOUT_MILLIS,
        idempotency = idempotency,
        availability = availability
    )

    private fun mediaDescriptor(
        id: String,
        title: String,
        description: String,
        inputSchema: AgentNativeJsonSchema = objectSchema(
            properties = mapOf("content_uri" to contentUriSchema()),
            required = setOf("content_uri")
        ),
        outputSchema: AgentNativeJsonSchema,
        risk: AgentNativeToolRisk = AgentNativeToolRisk.LOW,
        consentIds: List<String>,
        idempotency: AgentNativeToolIdempotency = AgentNativeToolIdempotency.IDEMPOTENT,
        availability: AgentNativeToolAvailability
    ) = AgentNativeToolDescriptor(
        id,
        VERSION,
        title,
        description,
        AgentNativeToolLocation.ANDROID_SYSTEM,
        inputSchema,
        outputSchema,
        risk,
        capabilities = setOf("media.content_uri", "content_uri.user_authorized"),
        requiredConsents = consents(consentIds),
        timeoutMillis = MAX_TOOL_TIMEOUT_MILLIS,
        idempotency = idempotency,
        availability = availability
    )

    private fun webHeadInputSchema() = objectSchema(
        properties = mapOf("url" to urlSchema(), "timeout_ms" to timeoutSchema()),
        required = setOf("url")
    )

    private fun webGetInputSchema(maxBytes: Long) = objectSchema(
        properties = webInputProperties(maxBytes),
        required = setOf("url")
    )

    private fun webInputProperties(maxBytes: Long) = mapOf(
        "url" to urlSchema(),
        "max_bytes" to AgentNativeJsonSchema.integer(1, maxBytes),
        "timeout_ms" to timeoutSchema()
    )

    private fun webHeadOutputSchema() = objectSchema(webCommonOutputProperties(), WEB_COMMON_REQUIRED)

    private fun webFetchOutputSchema() = objectSchema(
        webCommonOutputProperties() + mapOf(
            "text" to AgentNativeJsonSchema.string(maxLength = MAX_FETCH_BYTES.toInt()),
            "charset" to AgentNativeJsonSchema.string(maxLength = 64),
            "size_bytes" to AgentNativeJsonSchema.integer(0, MAX_FETCH_BYTES),
            "sha256" to sha256Schema()
        ),
        WEB_COMMON_REQUIRED + setOf("text", "charset", "size_bytes", "sha256")
    )

    private fun webDownloadOutputSchema() = objectSchema(
        webCommonOutputProperties() + mapOf(
            "destination_content_uri" to contentUriSchema(),
            "size_bytes" to AgentNativeJsonSchema.integer(0, MAX_DOWNLOAD_BYTES),
            "sha256" to sha256Schema()
        ),
        WEB_COMMON_REQUIRED + setOf("destination_content_uri", "size_bytes", "sha256")
    )

    private fun webCommonOutputProperties() = mapOf(
        "method" to AgentNativeJsonSchema.string(enumValues = listOf("head", "get")),
        "status_code" to AgentNativeJsonSchema.integer(100, 599),
        "content_type" to AgentNativeJsonSchema.string(maxLength = 255),
        "content_length_bytes" to AgentNativeJsonSchema.integer(-1),
        "requested_at_epoch_ms" to AgentNativeJsonSchema.integer(0),
        "retrieved_at_epoch_ms" to AgentNativeJsonSchema.integer(0),
        "response_headers" to objectSchema(
            properties = mapOf(
                "content-type" to AgentNativeJsonSchema.string(maxLength = 2_048),
                "etag" to AgentNativeJsonSchema.string(maxLength = 2_048),
                "last-modified" to AgentNativeJsonSchema.string(maxLength = 2_048),
                "cache-control" to AgentNativeJsonSchema.string(maxLength = 2_048),
                "content-disposition" to AgentNativeJsonSchema.string(maxLength = 2_048)
            )
        ),
        "source" to webSourceSchema()
    )

    private fun webSourceSchema() = objectSchema(
        properties = mapOf(
            "requested_url" to urlSchema(),
            "final_url" to urlSchema(),
            "redirect_chain" to AgentNativeJsonSchema.array(
                objectSchema(
                    properties = mapOf(
                        "status_code" to AgentNativeJsonSchema.integer(300, 399),
                        "from_url" to urlSchema(),
                        "to_url" to urlSchema()
                    ),
                    required = setOf("status_code", "from_url", "to_url")
                ),
                maxItems = 4
            ),
            "dns_resolution" to AgentNativeJsonSchema.array(
                objectSchema(
                    properties = mapOf(
                        "host" to AgentNativeJsonSchema.string(maxLength = 253),
                        "addresses" to AgentNativeJsonSchema.array(
                            AgentNativeJsonSchema.string(maxLength = 64),
                            minItems = 1,
                            maxItems = 16
                        )
                    ),
                    required = setOf("host", "addresses")
                ),
                minItems = 1,
                maxItems = 5
            )
        ),
        required = setOf("requested_url", "final_url", "redirect_chain", "dns_resolution")
    )

    private fun ocrOutputSchema() = objectSchema(
        properties = mapOf(
            "text" to AgentNativeJsonSchema.string(maxLength = MAX_OCR_TEXT_CHARS),
            "lines" to AgentNativeJsonSchema.array(
                objectSchema(
                    properties = mapOf(
                        "text" to AgentNativeJsonSchema.string(maxLength = 4_096),
                        "left" to AgentNativeJsonSchema.integer(minimum = 0),
                        "top" to AgentNativeJsonSchema.integer(minimum = 0),
                        "right" to AgentNativeJsonSchema.integer(minimum = 0),
                        "bottom" to AgentNativeJsonSchema.integer(minimum = 0)
                    ),
                    required = setOf("text", "left", "top", "right", "bottom")
                ),
                maxItems = MAX_OCR_LINES
            ),
            "width" to AgentNativeJsonSchema.integer(minimum = 0),
            "height" to AgentNativeJsonSchema.integer(minimum = 0),
            "language_tags" to AgentNativeJsonSchema.array(AgentNativeJsonSchema.string(maxLength = 64), maxItems = 64),
            "observed_at_epoch_ms" to AgentNativeJsonSchema.integer(minimum = 0),
            "source" to contentSourceSchema(includeKind = true)
        ),
        required = setOf("text", "lines", "width", "height", "language_tags", "observed_at_epoch_ms", "source")
    )

    private fun mediaMetadataOutputSchema() = objectSchema(
        properties = mapOf(
            "content_uri" to contentUriSchema(),
            "content_type" to AgentNativeJsonSchema.string(maxLength = 255),
            "display_name" to AgentNativeJsonSchema.string(maxLength = 1_024),
            "size_bytes" to AgentNativeJsonSchema.integer(minimum = -1),
            "duration_ms" to AgentNativeJsonSchema.integer(minimum = 0),
            "width" to AgentNativeJsonSchema.integer(minimum = 0),
            "height" to AgentNativeJsonSchema.integer(minimum = 0),
            "rotation_degrees" to AgentNativeJsonSchema.integer(0, 359),
            "has_audio" to AgentNativeJsonSchema.boolean(),
            "has_video" to AgentNativeJsonSchema.boolean(),
            "observed_at_epoch_ms" to AgentNativeJsonSchema.integer(minimum = 0),
            "source" to contentSourceSchema()
        ),
        required = setOf(
            "content_uri", "content_type", "display_name", "size_bytes", "duration_ms", "width", "height",
            "rotation_degrees", "has_audio", "has_video", "observed_at_epoch_ms", "source"
        )
    )

    private fun mediaPlaybackOutputSchema() = objectSchema(
        properties = mapOf(
            "launched" to AgentNativeJsonSchema.boolean(),
            "action" to AgentNativeJsonSchema.string(maxLength = 255),
            "handler_package" to AgentNativeJsonSchema.string(maxLength = 255),
            "completed" to AgentNativeJsonSchema.boolean(),
            "handed_off_at_epoch_ms" to AgentNativeJsonSchema.integer(minimum = 0),
            "source" to contentSourceSchema()
        ),
        required = setOf("launched", "action", "handler_package", "completed", "handed_off_at_epoch_ms", "source")
    )

    private fun contentSourceSchema(includeKind: Boolean = false): AgentNativeJsonSchema {
        val properties = linkedMapOf("content_uri" to contentUriSchema())
        if (includeKind) {
            properties["source_kind"] = AgentNativeJsonSchema.string(
                enumValues = AgentOcrSourceKind.entries.map { it.wireValue }
            )
        }
        return objectSchema(properties, properties.keys)
    }

    private fun objectSchema(
        properties: Map<String, AgentNativeJsonSchema> = emptyMap(),
        required: Set<String> = emptySet()
    ) = AgentNativeJsonSchema.objectSchema(properties, required, additionalProperties = false)

    private fun urlSchema() = AgentNativeJsonSchema.string(
        minLength = 9,
        maxLength = 4_096,
        pattern = "(?i)^https://"
    )

    private fun contentUriSchema() = AgentNativeJsonSchema.string(
        minLength = 11,
        maxLength = MAX_CONTENT_URI_CHARS,
        pattern = "^content://"
    )

    private fun timeoutSchema() = AgentNativeJsonSchema.integer(1, MAX_TOOL_TIMEOUT_MILLIS)

    private fun sha256Schema() = AgentNativeJsonSchema.string(
        minLength = 64,
        maxLength = 64,
        pattern = "^[0-9a-f]{64}$"
    )

    private fun consents(vararg ids: String) = consents(ids.toList())

    private fun consents(ids: List<String>) = ids.map { id ->
        AgentNativeConsentRequirement(
            id,
            title = when (id) {
                PUBLIC_WEB_CONSENT -> "Access public HTTPS resource"
                WEB_DOWNLOAD_CONSENT -> "Download public HTTPS resource"
                CONTENT_URI_READ_CONSENT -> "Read selected Android content"
                CONTENT_URI_WRITE_CONSENT -> "Write selected Android content"
                MEDIA_PLAYBACK_CONSENT -> "Open selected media for playback"
                else -> id
            },
            description = "Authorizes this invocation only; it does not grant ambient file or network access."
        )
    }

    private fun executeBounded(block: () -> AgentNativeToolExecutionResult): AgentNativeToolExecutionResult = try {
        block()
    } catch (error: AgentWebMediaException) {
        AgentNativeToolExecutionResult.failure(error.code, error.message, error.retryable, error.details)
    } catch (error: AgentNativeToolCancelledException) {
        throw error
    } catch (error: AgentNativeToolTimeoutException) {
        throw error
    } catch (error: Exception) {
        AgentNativeToolExecutionResult.failure(
            "native_adapter_failed",
            error.message ?: "Android native adapter failed"
        )
    }

    private fun combineAvailability(vararg values: AgentNativeToolAvailability): AgentNativeToolAvailability {
        val unavailable = values.firstOrNull { it.status == AgentNativeToolAvailabilityStatus.UNAVAILABLE }
        if (unavailable != null) return unavailable
        val setup = values.firstOrNull { it.status == AgentNativeToolAvailabilityStatus.REQUIRES_SETUP }
        return setup ?: AgentNativeToolAvailability.AVAILABLE
    }

    private fun AgentNativeJsonObject.timeout(invocation: AgentNativeToolInvocation): Long =
        long("timeout_ms", MAX_TOOL_TIMEOUT_MILLIS).coerceAtMost(invocation.remainingTimeMillis.coerceAtLeast(1L))

    private fun AgentNativeJsonObject.string(name: String, default: String? = null): String =
        (this[name] as? String) ?: default ?: throw AgentWebMediaException("invalid_input", "$name must be a string")

    private fun AgentNativeJsonObject.long(name: String, default: Long): Long = (this[name] as? Number)?.toLong() ?: default

    private fun AgentWebResource.commonValue(): AgentNativeJsonObject = linkedMapOf(
        "method" to method.name.lowercase(Locale.ROOT),
        "status_code" to statusCode,
        "content_type" to contentType,
        "content_length_bytes" to contentLengthBytes,
        "requested_at_epoch_ms" to requestedAtEpochMillis,
        "retrieved_at_epoch_ms" to retrievedAtEpochMillis,
        "response_headers" to selectedHeaders,
        "source" to linkedMapOf(
            "requested_url" to requestedUrl,
            "final_url" to finalUrl,
            "redirect_chain" to redirects.map { redirect ->
                mapOf(
                    "status_code" to redirect.statusCode,
                    "from_url" to redirect.fromUrl,
                    "to_url" to redirect.toUrl
                )
            },
            "dns_resolution" to dnsResolutions.map { resolution ->
                mapOf("host" to resolution.host, "addresses" to resolution.addresses)
            }
        )
    )

    private fun AgentOcrResult.bounded(): AgentOcrResult {
        if (text.length > MAX_OCR_TEXT_CHARS) {
            throw AgentWebMediaException("ocr_result_too_large", "OCR text exceeded the character limit")
        }
        if (lines.size > MAX_OCR_LINES || lines.any { it.text.length > 4_096 }) {
            throw AgentWebMediaException("ocr_result_too_large", "OCR lines exceeded the result limit")
        }
        if (
            width < 0 || height < 0 ||
            lines.any { it.left < 0 || it.top < 0 || it.right < it.left || it.bottom < it.top } ||
            languageTags.any { it.length > 64 }
        ) {
            throw AgentWebMediaException("invalid_ocr_result", "OCR implementation returned invalid bounded output")
        }
        return copy(languageTags = languageTags.distinct().take(64))
    }

    private fun AgentMediaMetadata.bounded(expectedContentUri: String): AgentMediaMetadata {
        if (
            contentUri != expectedContentUri || contentType.length > 255 || displayName.length > 1_024 ||
            sizeBytes < -1 || durationMillis < 0 || width < 0 || height < 0
        ) {
            throw AgentWebMediaException("invalid_media_metadata", "Media implementation returned out-of-bounds metadata")
        }
        return this
    }

    private fun AgentMediaMetadata.value(observedAt: Long): AgentNativeJsonObject = mapOf(
        "content_uri" to contentUri,
        "content_type" to contentType,
        "display_name" to displayName,
        "size_bytes" to sizeBytes,
        "duration_ms" to durationMillis.coerceAtLeast(0),
        "width" to width.coerceAtLeast(0),
        "height" to height.coerceAtLeast(0),
        "rotation_degrees" to ((rotationDegrees % 360) + 360) % 360,
        "has_audio" to hasAudio,
        "has_video" to hasVideo,
        "observed_at_epoch_ms" to observedAt,
        "source" to mapOf("content_uri" to contentUri)
    )

    private fun charset(contentType: String, headers: Map<String, String>): Charset {
        val raw = headers["content-type"].orEmpty()
        val name = Regex("charset=([^;\\s]+)", RegexOption.IGNORE_CASE)
            .find(raw)?.groupValues?.getOrNull(1)?.trim('"', '\'')
        return name?.let { runCatching { Charset.forName(it) }.getOrNull() }
            ?: if (contentType.startsWith("text/")) Charsets.UTF_8 else Charsets.UTF_8
    }

    private fun webProvenance() = mapOf(
        "transport" to AgentWebTransport::class.java.name,
        "scheme_policy" to "https_only",
        "address_policy" to "public_only_all_dns_answers",
        "redirect_policy" to "manual_revalidate_each_hop",
        "result_policy" to "bounded-v1"
    )

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { "%02x".format(Locale.ROOT, it.toInt() and 0xff) }

    private val WEB_COMMON_REQUIRED = setOf(
        "method",
        "status_code",
        "content_type",
        "content_length_bytes",
        "requested_at_epoch_ms",
        "retrieved_at_epoch_ms",
        "response_headers",
        "source"
    )
}

private fun validateContentUri(value: String): String {
    val normalized = value.trim()
    val uri = try {
        URI(normalized)
    } catch (error: Exception) {
        throw AgentWebMediaException("content_uri_required", "A user-authorized content:// URI is required", cause = error)
    }
    if (!uri.scheme.equals(ContentResolver.SCHEME_CONTENT, ignoreCase = true) || uri.rawAuthority.isNullOrBlank()) {
        throw AgentWebMediaException("content_uri_required", "A user-authorized content:// URI is required")
    }
    return normalized
}

private fun parseAndroidContentUri(value: String): Uri = Uri.parse(validateContentUri(value))

private fun readContentUriBounded(resolver: ContentResolver, uri: Uri, maxBytes: Long): ByteArray {
    resolver.openAssetFileDescriptor(uri, "r")?.use { descriptor ->
        if (descriptor.length > maxBytes) {
            throw AgentWebMediaException("content_too_large", "Selected content exceeds the $maxBytes byte limit")
        }
    }
    val input = resolver.openInputStream(uri)
        ?: throw AgentWebMediaException("content_uri_unavailable", "Selected content URI cannot be opened")
    return input.use {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var total = 0L
        while (true) {
            val read = it.read(buffer)
            if (read < 0) break
            if (read == 0) continue
            total += read
            if (total > maxBytes) {
                throw AgentWebMediaException("content_too_large", "Selected content exceeds the $maxBytes byte limit")
            }
            output.write(buffer, 0, read)
        }
        output.toByteArray()
    }
}
