package com.signalasi.chat

import android.content.Context
import android.os.Build
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.IDN
import java.net.InetAddress
import java.net.URI
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.TimeUnit

data class AgentRuntimePackCatalogEntry(
    val packId: String,
    val version: String,
    val architecture: String,
    val downloadUrl: String,
    val archiveSha256: String,
    val archiveSizeBytes: Long,
    val installedSizeBytes: Long,
    val dependencies: List<String>,
    val license: String,
    val minimumHostVersionCode: Long,
    val guestApiVersion: Int,
    val releaseNotes: String = ""
) {
    internal fun canonicalValue(): String = listOf(
        packId,
        version,
        architecture,
        downloadUrl,
        archiveSha256.lowercase(Locale.ROOT),
        archiveSizeBytes.toString(),
        installedSizeBytes.toString(),
        dependencies.sorted().joinToString(","),
        license,
        minimumHostVersionCode.toString(),
        guestApiVersion.toString(),
        releaseNotes
    ).joinToString("") { value -> "${value.toByteArray(Charsets.UTF_8).size}:$value" }
}

data class AgentRuntimePackCatalog(
    val catalogVersion: String,
    val generatedAtMillis: Long,
    val expiresAtMillis: Long,
    val entries: List<AgentRuntimePackCatalogEntry>,
    val signatureKeyId: String,
    val signature: String,
    val formatVersion: Int = 1
) {
    fun signingPayload(): ByteArray = buildString {
        append(formatVersion).append('\n')
        append(catalogVersion).append('\n')
        append(generatedAtMillis).append('\n')
        append(expiresAtMillis).append('\n')
        entries.sortedWith(compareBy<AgentRuntimePackCatalogEntry> { it.packId }
            .thenBy { it.architecture }
            .thenBy { it.version })
            .forEach { append(it.canonicalValue()).append('\n') }
        append(signatureKeyId.lowercase(Locale.ROOT))
    }.toByteArray(Charsets.UTF_8)
}

fun interface AgentRuntimeCatalogSignatureVerifier {
    fun verify(catalog: AgentRuntimePackCatalog): Boolean
}

class AndroidTrustedRuntimeCatalogVerifier(context: Context) : AgentRuntimeCatalogSignatureVerifier {
    private val verifier = AndroidRuntimePayloadVerifier(context)

    override fun verify(catalog: AgentRuntimePackCatalog): Boolean = verifier.verify(
        catalog.signatureKeyId,
        catalog.signature,
        catalog.signingPayload()
    )
}

object AgentRuntimePackCatalogCodec {
    const val MAX_CATALOG_BYTES = 1_048_576

    fun decode(raw: String): AgentRuntimePackCatalog {
        require(raw.toByteArray(Charsets.UTF_8).size in 1..MAX_CATALOG_BYTES) {
            "Runtime catalog exceeds the size limit"
        }
        val root = JSONObject(raw)
        val entriesJson = root.optJSONArray("entries") ?: JSONArray()
        val entries = buildList {
            for (index in 0 until entriesJson.length()) {
                val value = entriesJson.optJSONObject(index) ?: error("Runtime catalog entry is invalid")
                add(AgentRuntimePackCatalogEntry(
                    packId = value.getString("pack_id"),
                    version = value.getString("version"),
                    architecture = value.getString("architecture"),
                    downloadUrl = value.getString("download_url"),
                    archiveSha256 = value.getString("archive_sha256"),
                    archiveSizeBytes = value.getLong("archive_size_bytes"),
                    installedSizeBytes = value.getLong("installed_size_bytes"),
                    dependencies = value.optJSONArray("dependencies").strings(),
                    license = value.getString("license"),
                    minimumHostVersionCode = value.optLong("minimum_host_version_code", 1L),
                    guestApiVersion = value.optInt("guest_api_version", 1),
                    releaseNotes = value.optString("release_notes")
                ))
            }
        }
        return AgentRuntimePackCatalog(
            formatVersion = root.optInt("format_version", 1),
            catalogVersion = root.getString("catalog_version"),
            generatedAtMillis = root.getLong("generated_at_millis"),
            expiresAtMillis = root.getLong("expires_at_millis"),
            entries = entries,
            signatureKeyId = root.getString("signature_key_id"),
            signature = root.getString("signature")
        )
    }

    fun encode(catalog: AgentRuntimePackCatalog): String = JSONObject()
        .put("format_version", catalog.formatVersion)
        .put("catalog_version", catalog.catalogVersion)
        .put("generated_at_millis", catalog.generatedAtMillis)
        .put("expires_at_millis", catalog.expiresAtMillis)
        .put("entries", JSONArray().apply {
            catalog.entries.forEach { entry ->
                put(JSONObject()
                    .put("pack_id", entry.packId)
                    .put("version", entry.version)
                    .put("architecture", entry.architecture)
                    .put("download_url", entry.downloadUrl)
                    .put("archive_sha256", entry.archiveSha256)
                    .put("archive_size_bytes", entry.archiveSizeBytes)
                    .put("installed_size_bytes", entry.installedSizeBytes)
                    .put("dependencies", JSONArray(entry.dependencies))
                    .put("license", entry.license)
                    .put("minimum_host_version_code", entry.minimumHostVersionCode)
                    .put("guest_api_version", entry.guestApiVersion)
                    .put("release_notes", entry.releaseNotes))
            }
        })
        .put("signature_key_id", catalog.signatureKeyId)
        .put("signature", catalog.signature)
        .toString()

    private fun JSONArray?.strings(): List<String> = buildList {
        val source = this@strings ?: return@buildList
        for (index in 0 until source.length()) source.optString(index).takeIf(String::isNotBlank)?.let(::add)
    }
}

object AgentRuntimePackCatalogPolicy {
    fun validate(
        catalog: AgentRuntimePackCatalog,
        verifier: AgentRuntimeCatalogSignatureVerifier,
        nowMillis: Long = System.currentTimeMillis()
    ): AgentRuntimePackCatalog {
        require(catalog.formatVersion == FORMAT_VERSION) { "Runtime catalog format is incompatible" }
        require(VERSION_PATTERN.matches(catalog.catalogVersion)) { "Runtime catalog version is invalid" }
        require(catalog.generatedAtMillis > 0L && catalog.generatedAtMillis <= nowMillis + CLOCK_SKEW_MILLIS) {
            "Runtime catalog generation time is invalid"
        }
        require(catalog.expiresAtMillis > catalog.generatedAtMillis && catalog.expiresAtMillis >= nowMillis) {
            "Runtime catalog is expired"
        }
        require(catalog.entries.size in 1..MAX_ENTRIES) { "Runtime catalog entry count is invalid" }
        require(SHA256_PATTERN.matches(catalog.signatureKeyId)) { "Runtime catalog signing key id is invalid" }
        require(catalog.signature.isNotBlank()) { "Runtime catalog signature is missing" }
        val identities = mutableSetOf<String>()
        catalog.entries.forEach { entry ->
            require(entry.packId in AgentOnDeviceRuntimeManager.REQUIRED_PACKS) { "Runtime catalog pack id is unsupported" }
            require(VERSION_PATTERN.matches(entry.version)) { "Runtime catalog pack version is invalid" }
            require(ARCHITECTURE_PATTERN.matches(entry.architecture)) { "Runtime catalog architecture is invalid" }
            require(SHA256_PATTERN.matches(entry.archiveSha256)) { "Runtime catalog archive digest is invalid" }
            require(entry.archiveSizeBytes in 1..MAX_ARCHIVE_BYTES) { "Runtime catalog archive size is invalid" }
            require(entry.installedSizeBytes in 1..MAX_INSTALLED_BYTES) { "Runtime catalog installed size is invalid" }
            require(entry.dependencies.distinct().size == entry.dependencies.size &&
                entry.dependencies.all { it in AgentOnDeviceRuntimeManager.REQUIRED_PACKS && it != entry.packId }
            ) { "Runtime catalog dependencies are invalid" }
            require(entry.license.isNotBlank() && entry.license.length <= 256) { "Runtime catalog license is invalid" }
            require(entry.minimumHostVersionCode > 0L && entry.guestApiVersion > 0) {
                "Runtime catalog compatibility metadata is invalid"
            }
            require(entry.releaseNotes.length <= MAX_RELEASE_NOTES_CHARS &&
                entry.canonicalValue().none { it == '\n' || it == '\r' }
            ) { "Runtime catalog text is invalid" }
            validateHttpsUrl(entry.downloadUrl)
            require(identities.add("${entry.packId}|${entry.architecture}")) {
                "Runtime catalog contains duplicate pack entries"
            }
        }
        val entriesByArchitecture = catalog.entries.groupBy(AgentRuntimePackCatalogEntry::architecture)
        entriesByArchitecture.forEach { (architecture, entries) ->
            val byId = entries.associateBy(AgentRuntimePackCatalogEntry::packId)
            entries.forEach { entry ->
                require(entry.dependencies.all(byId::containsKey)) {
                    "Runtime catalog dependency is missing for $architecture"
                }
            }
            val visiting = mutableSetOf<String>()
            val visited = mutableSetOf<String>()
            fun visit(packId: String) {
                if (packId in visited) return
                require(visiting.add(packId)) { "Runtime catalog contains a dependency cycle" }
                byId.getValue(packId).dependencies.forEach(::visit)
                visiting.remove(packId)
                visited.add(packId)
            }
            byId.keys.forEach(::visit)
        }
        require(verifier.verify(catalog)) { "Runtime catalog signature is not trusted" }
        return catalog
    }

    fun compatibleEntries(
        catalog: AgentRuntimePackCatalog,
        supportedAbis: List<String> = Build.SUPPORTED_ABIS.toList(),
        hostVersionCode: Long
    ): List<AgentRuntimePackCatalogEntry> = catalog.entries.filter { entry ->
        entry.architecture in supportedAbis &&
            entry.minimumHostVersionCode <= hostVersionCode &&
            entry.guestApiVersion == AgentRuntimeGuestProtocol.VERSION
    }

    fun validateReplacement(
        previous: AgentRuntimePackCatalog?,
        candidate: AgentRuntimePackCatalog
    ) {
        if (previous == null) return
        require(candidate.generatedAtMillis >= previous.generatedAtMillis) {
            "Runtime catalog rollback was rejected"
        }
        if (candidate.generatedAtMillis == previous.generatedAtMillis) {
            require(candidate.signingPayload().contentEquals(previous.signingPayload())) {
                "Runtime catalog generation was reused with different content"
            }
        }
    }

    internal fun validateHttpsUrl(value: String): URI {
        require(value.length in 1..MAX_URL_CHARS) { "Runtime pack URL is invalid" }
        val uri = runCatching { URI(value) }.getOrElse { error("Runtime pack URL is invalid") }
        require(uri.scheme.equals("https", ignoreCase = true) && !uri.host.isNullOrBlank() &&
            uri.userInfo == null && uri.fragment == null && uri.port in -1..65535 && uri.port != 0
        ) { "Runtime pack URL must be public HTTPS" }
        val host = IDN.toASCII(uri.host, IDN.USE_STD3_ASCII_RULES).lowercase(Locale.ROOT)
        require(host != "localhost" && !host.endsWith(".localhost") && !host.endsWith(".local")) {
            "Runtime pack URL must not target a local host"
        }
        return uri
    }

    private const val FORMAT_VERSION = 1
    private const val MAX_ENTRIES = 128
    private const val MAX_URL_CHARS = 4_096
    private const val MAX_RELEASE_NOTES_CHARS = 8_192
    private const val MAX_ARCHIVE_BYTES = 6L * 1024L * 1024L * 1024L
    private const val MAX_INSTALLED_BYTES = 12L * 1024L * 1024L * 1024L
    private const val CLOCK_SKEW_MILLIS = 24L * 60L * 60L * 1_000L
    private val VERSION_PATTERN = Regex("[0-9]+\\.[0-9]+\\.[0-9]+(?:[-+][A-Za-z0-9._-]+)?")
    private val ARCHITECTURE_PATTERN = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,63}")
    private val SHA256_PATTERN = Regex("[a-fA-F0-9]{64}")
}

data class AgentRuntimePackDownloadProgress(
    val downloadedBytes: Long,
    val totalBytes: Long,
    val resumed: Boolean
)

data class AgentRuntimePackDownloadResult(
    val archive: File,
    val downloadedBytes: Long,
    val resumed: Boolean,
    val sha256: String
)

internal class AgentRuntimePackDownloader(
    private val root: File,
    private val baseClient: OkHttpClient = OkHttpClient.Builder()
        .followRedirects(false)
        .followSslRedirects(false)
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .callTimeout(60, TimeUnit.MINUTES)
        .build(),
    private val resolver: AgentHostResolver = AgentSystemHostResolver,
    private val allowInsecureLoopbackForTests: Boolean = false
) {
    constructor(context: Context) : this(File(context.applicationContext.filesDir, "agent-runtime/downloads"))

    fun download(
        entry: AgentRuntimePackCatalogEntry,
        cancellationToken: AgentNativeToolCancellationToken = AgentNativeToolCancellationToken.NONE,
        onProgress: (AgentRuntimePackDownloadProgress) -> Unit = {}
    ): AgentRuntimePackDownloadResult {
        require(entry.archiveSizeBytes > 0L && entry.archiveSha256.matches(SHA256_PATTERN)) {
            "Runtime pack download metadata is invalid"
        }
        check(root.mkdirs() || root.isDirectory) { "Runtime pack download storage is unavailable" }
        val baseName = "${entry.packId}-${entry.version}-${entry.architecture}-${entry.archiveSha256.take(12)}"
        val completed = File(root, "$baseName.sarpack")
        val partial = File(root, "$baseName.partial")
        val metadata = File(root, "$baseName.partial.json")
        if (completed.isFile && completed.length() == entry.archiveSizeBytes &&
            sha256(completed).equals(entry.archiveSha256, ignoreCase = true)
        ) {
            return AgentRuntimePackDownloadResult(completed, completed.length(), resumed = false, entry.archiveSha256)
        }
        completed.delete()
        val saved = readResumeMetadata(metadata)
        if (saved == null || saved.url != entry.downloadUrl ||
            !saved.sha256.equals(entry.archiveSha256, ignoreCase = true) || saved.totalBytes != entry.archiveSizeBytes
        ) {
            partial.delete()
            metadata.delete()
        }
        var offset = partial.length().coerceAtMost(entry.archiveSizeBytes)
        if (offset != partial.length()) partial.delete()
        ensureSpace((entry.archiveSizeBytes - offset).coerceAtLeast(0L))
        var resumed = offset > 0L
        var restartAllowed = true
        var resumeEtag = saved?.etag.orEmpty()
        while (true) {
            cancellationToken.checkpoint()
            var retryFreshRequest = false
            val response = openResponse(entry.downloadUrl, offset, resumeEtag, cancellationToken)
            response.use { active ->
                if (active.code == 416) {
                    if (offset == entry.archiveSizeBytes && sha256(partial).equals(entry.archiveSha256, ignoreCase = true)) {
                        activateDownload(partial, completed, metadata)
                        return AgentRuntimePackDownloadResult(completed, completed.length(), resumed, entry.archiveSha256)
                    }
                    if (!restartAllowed) error("Runtime pack server rejected a fresh download")
                    partial.delete()
                    metadata.delete()
                    offset = 0L
                    resumed = false
                    resumeEtag = ""
                    restartAllowed = false
                    retryFreshRequest = true
                    return@use
                }
                if (active.code !in setOf(200, 206)) error("Runtime pack download returned HTTP ${active.code}")
                if (active.code == 206) {
                    val range = parseContentRange(active.header("Content-Range"))
                    if (range == null || range.first != offset || range.total != entry.archiveSizeBytes) {
                        if (!restartAllowed) error("Runtime pack server returned an invalid resume range")
                        partial.delete()
                        metadata.delete()
                        offset = 0L
                        resumed = false
                        resumeEtag = ""
                        restartAllowed = false
                        retryFreshRequest = true
                        return@use
                    }
                } else if (offset > 0L) {
                    partial.delete()
                    offset = 0L
                    resumed = false
                }
                val body = active.body ?: error("Runtime pack download body is empty")
                val etag = active.header("ETag").orEmpty().take(MAX_ETAG_CHARS)
                if (offset > 0L && resumeEtag.isNotBlank() && etag.isNotBlank() && etag != resumeEtag) {
                    if (!restartAllowed) error("Runtime pack changed while resuming")
                    partial.delete()
                    metadata.delete()
                    offset = 0L
                    resumed = false
                    resumeEtag = ""
                    restartAllowed = false
                    retryFreshRequest = true
                    return@use
                }
                resumeEtag = etag
                writeResumeMetadata(metadata, ResumeMetadata(entry.downloadUrl, entry.archiveSha256, entry.archiveSizeBytes, etag))
                val append = active.code == 206 && offset > 0L
                val cancellationRegistration = cancellationToken.invokeOnCancellation(active::close)
                try {
                    FileOutputStream(partial, append).buffered().use { output ->
                        body.byteStream().buffered().use { input ->
                            val buffer = ByteArray(BUFFER_BYTES)
                            var downloaded = if (append) offset else 0L
                            while (true) {
                                cancellationToken.checkpoint()
                                val read = try {
                                    input.read(buffer)
                                } catch (error: Exception) {
                                    if (cancellationToken.isCancellationRequested) throw AgentNativeToolCancelledException()
                                    throw error
                                }
                                if (read < 0) break
                                downloaded += read
                                check(downloaded <= entry.archiveSizeBytes) {
                                    "Runtime pack download exceeds its signed size"
                                }
                                output.write(buffer, 0, read)
                                onProgress(AgentRuntimePackDownloadProgress(downloaded, entry.archiveSizeBytes, resumed))
                            }
                        }
                    }
                } finally {
                    cancellationRegistration.dispose()
                }
            }
            if (retryFreshRequest) continue
            offset = partial.length()
            if (offset == entry.archiveSizeBytes) break
            check(offset in 1 until entry.archiveSizeBytes) { "Runtime pack download ended without data" }
            resumed = true
        }
        check(partial.length() == entry.archiveSizeBytes) { "Runtime pack download size does not match the signed catalog" }
        val digest = sha256(partial)
        if (!digest.equals(entry.archiveSha256, ignoreCase = true)) {
            partial.delete()
            metadata.delete()
            error("Runtime pack download integrity check failed")
        }
        activateDownload(partial, completed, metadata)
        return AgentRuntimePackDownloadResult(completed, completed.length(), resumed, digest)
    }

    private fun openResponse(
        initialUrl: String,
        offset: Long,
        etag: String,
        cancellationToken: AgentNativeToolCancellationToken
    ): Response {
        var current = initialUrl
        repeat(MAX_REDIRECTS + 1) { redirectCount ->
            val uri = validateEndpoint(current)
            val addresses = resolver.resolve(uri.host).distinctBy { it.hostAddress }
            require(addresses.isNotEmpty()) { "Runtime pack host did not resolve" }
            if (!allowInsecureLoopbackForTests) {
                require(addresses.all(AgentPublicAddressPolicy::isPublic)) {
                    "Runtime pack host resolved to a private or special-use address"
                }
            }
            val expectedHost = uri.host
            val client = baseClient.newBuilder().dns(object : Dns {
                override fun lookup(hostname: String): List<InetAddress> {
                    if (!hostname.equals(expectedHost, ignoreCase = true)) {
                        throw java.net.UnknownHostException("Unexpected runtime pack host")
                    }
                    return addresses
                }
            }).build()
            val request = Request.Builder().url(current)
                .header("Accept", AgentRuntimePackInstaller.PACKAGE_MIME_TYPE)
                .header("Accept-Encoding", "identity")
                .apply {
                    if (offset > 0L) {
                        header("Range", "bytes=$offset-")
                        if (etag.isNotBlank()) header("If-Range", etag)
                    }
                }
                .build()
            val call = client.newCall(request)
            val registration = cancellationToken.invokeOnCancellation(call::cancel)
            val response = try {
                call.execute()
            } catch (error: Exception) {
                if (cancellationToken.isCancellationRequested) throw AgentNativeToolCancelledException()
                throw error
            } finally {
                registration.dispose()
            }
            if (response.code in REDIRECT_CODES) {
                if (redirectCount >= MAX_REDIRECTS) {
                    response.close()
                    error("Runtime pack download has too many redirects")
                }
                val location = response.header("Location")
                response.close()
                require(!location.isNullOrBlank()) { "Runtime pack redirect has no location" }
                current = URI(current).resolve(location).toString()
                return@repeat
            }
            return response
        }
        error("Runtime pack download redirect failed")
    }

    private fun validateEndpoint(value: String): URI {
        if (allowInsecureLoopbackForTests) {
            val uri = URI(value)
            require(uri.scheme in setOf("http", "https") && !uri.host.isNullOrBlank())
            return uri
        }
        return AgentRuntimePackCatalogPolicy.validateHttpsUrl(value)
    }

    private fun activateDownload(partial: File, completed: File, metadata: File) {
        completed.delete()
        check(partial.renameTo(completed)) { "Runtime pack download could not be finalized" }
        metadata.delete()
    }

    private fun parseContentRange(value: String?): ContentRange? {
        val match = CONTENT_RANGE_PATTERN.matchEntire(value.orEmpty()) ?: return null
        val first = match.groupValues[1].toLongOrNull() ?: return null
        val last = match.groupValues[2].toLongOrNull() ?: return null
        val total = match.groupValues[3].toLongOrNull() ?: return null
        if (first < 0L || last < first || total <= last) return null
        return ContentRange(first, last, total)
    }

    private fun ensureSpace(totalBytes: Long) {
        val available = root.parentFile?.usableSpace ?: root.usableSpace
        check(available >= totalBytes + MIN_FREE_BYTES) { "Not enough storage to download the runtime pack" }
    }

    private fun readResumeMetadata(file: File): ResumeMetadata? = runCatching {
        val value = JSONObject(file.readText(Charsets.UTF_8))
        ResumeMetadata(
            url = value.getString("url"),
            sha256 = value.getString("sha256"),
            totalBytes = value.getLong("total_bytes"),
            etag = value.optString("etag")
        )
    }.getOrNull()

    private fun writeResumeMetadata(file: File, value: ResumeMetadata) {
        val temporary = File(file.parentFile, "${file.name}.tmp")
        temporary.writeText(JSONObject()
            .put("url", value.url)
            .put("sha256", value.sha256)
            .put("total_bytes", value.totalBytes)
            .put("etag", value.etag)
            .toString(), Charsets.UTF_8)
        file.delete()
        check(temporary.renameTo(file)) { "Runtime pack resume metadata could not be saved" }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(BUFFER_BYTES)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun AgentNativeToolCancellationToken.checkpoint() {
        if (isCancellationRequested) throw AgentNativeToolCancelledException()
    }

    private data class ResumeMetadata(val url: String, val sha256: String, val totalBytes: Long, val etag: String)
    private data class ContentRange(val first: Long, val last: Long, val total: Long)

    companion object {
        private const val BUFFER_BYTES = 256 * 1024
        private const val MAX_REDIRECTS = 5
        private const val MAX_ETAG_CHARS = 512
        private const val MIN_FREE_BYTES = 256L * 1024L * 1024L
        private val REDIRECT_CODES = setOf(301, 302, 303, 307, 308)
        private val SHA256_PATTERN = Regex("[a-fA-F0-9]{64}")
        private val CONTENT_RANGE_PATTERN = Regex("bytes ([0-9]+)-([0-9]+)/([0-9]+)")
    }
}

class AgentRuntimePackCatalogStore(context: Context) {
    private val database = AgentEncryptedDatabase(
        context.applicationContext,
        DATABASE,
        legacyPreferencesName = UNUSED_LEGACY_PREFERENCES
    )

    @Synchronized
    fun save(catalog: AgentRuntimePackCatalog) =
        database.writeString(KEY_CATALOG, AgentRuntimePackCatalogCodec.encode(catalog))

    @Synchronized
    fun load(): AgentRuntimePackCatalog? = database.readString(KEY_CATALOG, "")
        .takeIf(String::isNotBlank)
        ?.let { runCatching { AgentRuntimePackCatalogCodec.decode(it) }.getOrNull() }

    @Synchronized
    fun clear() = database.clear()

    companion object {
        private const val DATABASE = "signalasi_runtime_catalog_v1"
        private const val UNUSED_LEGACY_PREFERENCES = "signalasi_runtime_catalog_v1_no_legacy"
        private const val KEY_CATALOG = "verified_catalog"
    }
}

class AgentRuntimePackCatalogManager(
    context: Context,
    private val verifier: AgentRuntimeCatalogSignatureVerifier = AndroidTrustedRuntimeCatalogVerifier(context),
    private val store: AgentRuntimePackCatalogStore = AgentRuntimePackCatalogStore(context),
    private val web: AgentBoundedWebService = AgentBoundedWebService(
        transport = AgentPinnedOkHttpWebTransport(),
        policy = AgentWebPolicy(
            maxFetchBytes = AgentRuntimePackCatalogCodec.MAX_CATALOG_BYTES.toLong(),
            maxDownloadBytes = AgentRuntimePackCatalogCodec.MAX_CATALOG_BYTES.toLong(),
            maxTimeoutMillis = 20_000L
        )
    )
) {
    private val appContext = context.applicationContext

    fun refresh(
        url: String = DEFAULT_CATALOG_URL,
        cancellationToken: AgentNativeToolCancellationToken = AgentNativeToolCancellationToken.NONE
    ): AgentRuntimePackCatalog {
        val resource = web.fetch(
            url,
            AgentRuntimePackCatalogCodec.MAX_CATALOG_BYTES.toLong(),
            web.policy.maxTimeoutMillis,
            cancellationToken
        )
        val catalog = AgentRuntimePackCatalogCodec.decode(resource.body.toString(Charsets.UTF_8))
        AgentRuntimePackCatalogPolicy.validate(catalog, verifier)
        val previous = store.load()?.let { cached ->
            runCatching {
                AgentRuntimePackCatalogPolicy.validate(cached, verifier, cached.generatedAtMillis)
            }.getOrNull()
        }
        AgentRuntimePackCatalogPolicy.validateReplacement(previous, catalog)
        store.save(catalog)
        return catalog
    }

    fun cachedCompatible(): List<AgentRuntimePackCatalogEntry> {
        val verified = cachedVerified() ?: return emptyList()
        return AgentRuntimePackCatalogPolicy.compatibleEntries(
            verified,
            hostVersionCode = installedVersionCode()
        )
    }

    fun cachedVerified(): AgentRuntimePackCatalog? {
        val catalog = store.load() ?: return null
        return runCatching { AgentRuntimePackCatalogPolicy.validate(catalog, verifier) }.getOrNull()
    }

    fun installationPlan(entry: AgentRuntimePackCatalogEntry): List<AgentRuntimePackCatalogEntry> {
        val available = cachedCompatible().filter { it.architecture == entry.architecture }
        val byId = available.associateBy(AgentRuntimePackCatalogEntry::packId)
        val current = byId[entry.packId]
            ?.takeIf { it.version == entry.version && it.archiveSha256 == entry.archiveSha256 }
            ?: error("Runtime pack is not present in the current verified catalog")
        val ordered = mutableListOf<AgentRuntimePackCatalogEntry>()
        val visiting = mutableSetOf<String>()
        val visited = mutableSetOf<String>()
        fun visit(item: AgentRuntimePackCatalogEntry) {
            if (item.packId in visited) return
            check(visiting.add(item.packId)) { "Runtime pack dependency cycle detected" }
            item.dependencies.forEach { dependency ->
                visit(byId[dependency] ?: error("Runtime pack dependency is unavailable: $dependency"))
            }
            visiting.remove(item.packId)
            visited.add(item.packId)
            ordered += item
        }
        visit(current)
        return ordered
    }

    fun downloadAndInstall(
        entry: AgentRuntimePackCatalogEntry,
        cancellationToken: AgentNativeToolCancellationToken = AgentNativeToolCancellationToken.NONE,
        onDownloadProgress: (AgentRuntimePackDownloadProgress) -> Unit = {},
        onInstallProgress: (AgentRuntimePackInstallProgress) -> Unit = {}
    ): AgentRuntimePackInstallResult {
        val available = cachedCompatible().firstOrNull {
            it.packId == entry.packId && it.version == entry.version && it.archiveSha256 == entry.archiveSha256
        } ?: error("Runtime pack is not present in the current verified catalog")
        val downloaded = AgentRuntimePackDownloader(appContext).download(
            available,
            cancellationToken,
            onDownloadProgress
        )
        val installed = AgentRuntimePackInstaller(appContext)
            .install(downloaded.archive, cancellationToken, onInstallProgress)
        downloaded.archive.delete()
        return installed
    }

    @Suppress("DEPRECATION")
    private fun installedVersionCode(): Long = appContext.packageManager
        .getPackageInfo(appContext.packageName, 0)
        .let { info -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) info.longVersionCode else info.versionCode.toLong() }

    companion object {
        const val DEFAULT_CATALOG_URL =
            "https://github.com/signalasi/SignalASI/releases/latest/download/android-runtime-catalog-v1.json"
    }
}
