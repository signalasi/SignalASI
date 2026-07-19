package com.signalasi.chat

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.security.Signature
import java.security.cert.CertificateFactory
import java.util.Locale
import java.util.UUID

enum class AgentOnDeviceRuntimeBackend(val wireValue: String) {
    QEMU_TCG("qemu_tcg"),
    ANDROID_VIRTUALIZATION_FRAMEWORK("android_virtualization_framework"),
    NONE("none")
}

enum class AgentRuntimePackState(val wireValue: String) {
    READY("ready"),
    NOT_INSTALLED("not_installed"),
    INVALID("invalid"),
    INCOMPATIBLE("incompatible")
}

enum class AgentRuntimeLanguage(
    val wireValue: String,
    val requiredPack: String,
    val requiredCapability: String
) {
    SHELL("shell", "linux-base", "shell.execute"),
    PYTHON("python", "python-uv", "python.execute"),
    UV("uv", "python-uv", "uv.sync"),
    JAVASCRIPT("javascript", "node-js", "javascript.execute"),
    TYPESCRIPT("typescript", "node-js", "typescript.execute"),
    GO("go", "go", "go.execute"),
    RUST("rust", "rust", "rust.execute"),
    C("c", "cpp", "c.execute"),
    CPP("cpp", "cpp", "cpp.execute"),
    JAVA("java", "java", "java.execute"),
    FFMPEG("ffmpeg", "ffmpeg", "ffmpeg.execute"),
    FFPROBE("ffprobe", "ffmpeg", "ffprobe.inspect")
}

data class AgentRuntimePackManifest(
    val id: String,
    val version: String,
    val architecture: String,
    val imageFile: String,
    val imageSha256: String,
    val capabilities: List<String>,
    val dependencies: List<String>,
    val installedSizeBytes: Long,
    val license: String,
    val signatureKeyId: String,
    val signature: String,
    val formatVersion: Int = 1,
    val archiveSizeBytes: Long = 0L,
    val minimumHostVersionCode: Long = 1L,
    val guestApiVersion: Int = 1
) {
    fun signingPayload(): ByteArray = listOf(
        formatVersion.toString(),
        id,
        version,
        architecture,
        imageFile,
        imageSha256.lowercase(Locale.ROOT),
        capabilities.sorted().joinToString(","),
        dependencies.sorted().joinToString(","),
        installedSizeBytes.toString(),
        archiveSizeBytes.toString(),
        minimumHostVersionCode.toString(),
        guestApiVersion.toString(),
        license,
        signatureKeyId.lowercase(Locale.ROOT)
    ).joinToString("") { value -> "${value.toByteArray(Charsets.UTF_8).size}:$value" }
        .toByteArray(Charsets.UTF_8)
}

fun interface AgentRuntimePackSignatureVerifier {
    fun verify(manifest: AgentRuntimePackManifest): Boolean
}

class AndroidTrustedRuntimePackVerifier(context: Context) : AgentRuntimePackSignatureVerifier {
    private val verifier = AndroidRuntimePayloadVerifier(context)

    override fun verify(manifest: AgentRuntimePackManifest): Boolean = verifier.verify(
        manifest.signatureKeyId,
        manifest.signature,
        manifest.signingPayload()
    )
}

class AndroidRuntimePayloadVerifier(context: Context) {
    private val appContext = context.applicationContext

    fun verify(signatureKeyId: String, signature: String, payload: ByteArray): Boolean {
        val signatureBytes = runCatching { Base64.decode(signature, Base64.DEFAULT) }.getOrNull()
            ?: return false
        return trustedCertificates().any { certificateBytes ->
            runCatching {
                val certificate = CertificateFactory.getInstance("X.509")
                    .generateCertificate(certificateBytes.inputStream())
                val keyId = MessageDigest.getInstance("SHA-256")
                    .digest(certificate.encoded)
                    .joinToString("") { "%02x".format(it) }
                if (!keyId.equals(signatureKeyId, ignoreCase = true)) return@runCatching false
                val algorithm = when (certificate.publicKey.algorithm.uppercase(Locale.ROOT)) {
                    "RSA" -> "SHA256withRSA"
                    "EC", "ECDSA" -> "SHA256withECDSA"
                    "ED25519", "EDDSA" -> "Ed25519"
                    else -> return@runCatching false
                }
                Signature.getInstance(algorithm).run {
                    initVerify(certificate.publicKey)
                    update(payload)
                    verify(signatureBytes)
                }
            }.getOrDefault(false)
        }
    }

    private fun trustedCertificates(): List<ByteArray> {
        val embedded = decodeTrustAnchors(runCatching {
            appContext.resources.openRawResource(R.raw.signalasi_runtime_trust_anchors)
                .bufferedReader(Charsets.UTF_8)
                .use { it.readText() }
        }.getOrNull())
        val bundled = decodeTrustAnchors(runCatching {
            appContext.assets.open(BUNDLED_TRUST_ANCHORS)
                .bufferedReader(Charsets.UTF_8)
                .use { it.readText() }
        }.getOrNull())
        val debugSigningCertificates = if (
            appContext.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
        ) signingCertificates() else emptyList()
        return (embedded + bundled + debugSigningCertificates).distinctBy { certificate ->
            MessageDigest.getInstance("SHA-256").digest(certificate).joinToString("") { "%02x".format(it) }
        }.take(MAX_TRUST_ANCHORS)
    }

    private fun decodeTrustAnchors(raw: String?): List<ByteArray> = runCatching {
        if (raw == null || raw.toByteArray(Charsets.UTF_8).size !in 1..MAX_TRUST_ANCHOR_DOCUMENT_BYTES) {
            return@runCatching emptyList()
        }
        val root = JSONObject(raw)
        require(root.optInt("format_version") == 1)
        val values = root.optJSONArray("certificates") ?: JSONArray()
        buildList {
            for (index in 0 until minOf(values.length(), MAX_TRUST_ANCHORS)) {
                val encoded = values.optString(index)
                if (encoded.isBlank()) continue
                val decoded = Base64.decode(encoded, Base64.DEFAULT)
                require(decoded.size in 1..MAX_CERTIFICATE_BYTES)
                add(decoded)
            }
        }
    }.getOrDefault(emptyList())

    @Suppress("DEPRECATION")
    private fun signingCertificates(): List<ByteArray> {
        val info = appContext.packageManager.getPackageInfo(
            appContext.packageName,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                PackageManager.GET_SIGNING_CERTIFICATES
            } else {
                PackageManager.GET_SIGNATURES
            }
        )
        val signatures = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.signingInfo?.apkContentsSigners.orEmpty()
        } else {
            info.signatures.orEmpty()
        }
        return signatures.map { it.toByteArray() }
    }

    companion object {
        private const val BUNDLED_TRUST_ANCHORS = "runtime/bootstrap/trust-anchors.json"
        private const val MAX_TRUST_ANCHORS = 8
        private const val MAX_CERTIFICATE_BYTES = 32 * 1024
        private const val MAX_TRUST_ANCHOR_DOCUMENT_BYTES = 256 * 1024
    }
}

data class AgentRuntimePackStatus(
    val id: String,
    val state: AgentRuntimePackState,
    val reason: String = "",
    val manifest: AgentRuntimePackManifest? = null
)

data class AgentOnDeviceRuntimeStatus(
    val backend: AgentOnDeviceRuntimeBackend,
    val backendReady: Boolean,
    val reason: String,
    val architecture: String,
    val enginePath: String,
    val avfAdvertised: Boolean,
    val packs: List<AgentRuntimePackStatus>,
    val lifecyclePhase: AgentRuntimeLifecyclePhase = AgentRuntimeLifecyclePhase.STOPPED,
    val lifecycleReason: String = "",
    val lifecycleFailures: Int = 0,
    val lifecycleNextAttemptAtMillis: Long = 0L
) {
    fun languageReady(language: AgentRuntimeLanguage): Boolean = backendReady && packs.any { pack ->
        pack.id == language.requiredPack &&
            pack.state == AgentRuntimePackState.READY &&
            language.requiredCapability in pack.manifest?.capabilities.orEmpty()
    }
}

internal data class AgentRuntimeBootstrapFiles(
    val engineFile: File,
    val baseImageFile: File,
    val socketFile: File,
    val packsDirectory: File,
    val workspacesDirectory: File,
    val architecture: String,
    val packAttachments: List<AgentRuntimePackAttachment>
)

data class AgentRuntimeExecutionRequest(
    val language: AgentRuntimeLanguage,
    val source: String,
    val arguments: List<String>,
    val timeoutMillis: Long,
    val networkEnabled: Boolean,
    val artifactPaths: List<String>,
    val workspaceId: String,
    val requestId: String = UUID.randomUUID().toString(),
    val allowedNetworkDomains: List<String> = emptyList(),
    val resourceLimits: AgentRuntimeResourceLimits = AgentRuntimeResourceLimits(
        wallClockMillis = timeoutMillis,
        cpuMillis = (timeoutMillis * 3L / 4L).coerceAtLeast(100L)
    ),
    val cancellationToken: AgentNativeToolCancellationToken = AgentNativeToolCancellationToken.NONE,
    val progressListener: (AgentRuntimeProgress) -> Unit = {},
    val guestWorkspacePath: String = ""
)

data class AgentRuntimeExecutionResponse(
    val exitCode: Int,
    val stdout: String,
    val stderr: String,
    val durationMillis: Long,
    val artifacts: List<Map<String, Any?>> = emptyList(),
    val requestId: String = "",
    val executionReceipt: AgentRuntimeExecutionReceipt? = null
)

fun interface AgentOnDeviceRuntimeBridge {
    fun execute(request: AgentRuntimeExecutionRequest): AgentRuntimeExecutionResponse
    fun health(): AgentRuntimeBridgeHealth = AgentRuntimeBridgeHealth(ready = true)
    fun cancel(requestId: String): Boolean = false
}

object AgentOnDeviceRuntimeBridgeRegistry {
    @Volatile
    private var active: AgentOnDeviceRuntimeBridge? = null

    fun register(bridge: AgentOnDeviceRuntimeBridge) {
        active = bridge
    }

    fun unregister(bridge: AgentOnDeviceRuntimeBridge) {
        if (active === bridge) active = null
    }

    fun current(): AgentOnDeviceRuntimeBridge? = active
}

class AgentOnDeviceRuntimeManager(
    context: Context,
    private val bridge: AgentOnDeviceRuntimeBridge? = null,
    private val signatureVerifier: AgentRuntimePackSignatureVerifier = AndroidTrustedRuntimePackVerifier(context)
) {
    private val appContext = context.applicationContext
    private val runtimeRoot = File(appContext.filesDir, RUNTIME_DIRECTORY)
    private val packsRoot = File(runtimeRoot, PACKS_DIRECTORY)
    private val integrityCache = appContext.getSharedPreferences(INTEGRITY_CACHE, Context.MODE_PRIVATE)
    private val workspaceManager = AgentRuntimeWorkspaceManager(appContext)
    private val receiptStore = AgentRuntimeExecutionReceiptStore(appContext)

    fun packStatuses(): List<AgentRuntimePackStatus> = REQUIRED_PACKS.map(::packStatus)

    fun architecture(): String = Build.SUPPORTED_ABIS.firstOrNull().orEmpty()

    fun status(): AgentOnDeviceRuntimeStatus {
        val engine = qemuEngineFile()
        val avf = appContext.packageManager.hasSystemFeature(AVF_FEATURE)
        val base = packStatus("linux-base")
        val engineReady = engine.isFile && engine.canExecute()
        val baseReady = base.state == AgentRuntimePackState.READY
        val backend = when {
            engineReady && baseReady -> AgentOnDeviceRuntimeBackend.QEMU_TCG
            else -> AgentOnDeviceRuntimeBackend.NONE
        }
        val activeBridge = bridge ?: AgentOnDeviceRuntimeBridgeRegistry.current()
            ?: AgentOnDeviceRuntimeSupervisor.discover(appContext)
        val bridgeHealth = activeBridge?.let { candidate ->
            runCatching { candidate.health() }.getOrElse { error ->
                AgentRuntimeBridgeHealth(false, reason = error.message ?: "Guest bridge health check failed")
            }
        }
        val lifecycle = if (bridgeHealth?.ready == true) {
            AgentRuntimeLifecycleSnapshot(
                phase = AgentRuntimeLifecyclePhase.READY,
                reason = "Guest runtime health handshake completed"
            )
        } else {
            AgentOnDeviceRuntimeLifecycle.inspectAfterBridgeProbe(appContext, bridgeHealth)
        }
        val reason = when {
            backend == AgentOnDeviceRuntimeBackend.QEMU_TCG && activeBridge == null -> lifecycle.reason.ifBlank {
                "Runtime engine and base pack are present, but the guest bridge is not connected"
            }
            backend == AgentOnDeviceRuntimeBackend.QEMU_TCG && bridgeHealth?.ready != true ->
                bridgeHealth?.reason.orEmpty().ifBlank { "The guest bridge is not healthy" }
            backend == AgentOnDeviceRuntimeBackend.QEMU_TCG -> "On-device Linux runtime is ready"
            !engineReady -> "Install the SignalASI QEMU engine"
            !baseReady -> "Install the linux-base runtime pack"
            else -> "On-device Linux runtime requires setup"
        }
        return AgentOnDeviceRuntimeStatus(
            backend = backend,
            backendReady = backend != AgentOnDeviceRuntimeBackend.NONE && bridgeHealth?.ready == true,
            reason = reason,
            architecture = architecture(),
            enginePath = engine.absolutePath,
            avfAdvertised = avf,
            packs = packStatuses(),
            lifecyclePhase = lifecycle.phase,
            lifecycleReason = lifecycle.reason,
            lifecycleFailures = lifecycle.consecutiveFailures,
            lifecycleNextAttemptAtMillis = lifecycle.nextAttemptAtMillis
        )
    }

    fun execute(request: AgentRuntimeExecutionRequest): AgentRuntimeExecutionResponse {
        require(request.source.toByteArray().size <= MAX_SOURCE_BYTES) { "Runtime source exceeds the limit" }
        require(request.arguments.size <= MAX_ARGUMENTS) { "Runtime argument count exceeds the limit" }
        require(request.arguments.all { it.toByteArray().size <= MAX_ARGUMENT_BYTES }) {
            "A runtime argument exceeds the limit"
        }
        require(request.timeoutMillis in MIN_TIMEOUT_MILLIS..MAX_TIMEOUT_MILLIS) {
            "Runtime timeout is outside the allowed range"
        }
        require(request.artifactPaths.size <= MAX_ARTIFACTS) { "Too many runtime artifact paths" }
        require(request.requestId.matches(REQUEST_ID_PATTERN)) { "Runtime request id is invalid" }
        require(request.workspaceId.isNotBlank()) { "Runtime workspace id is required" }
        require(request.allowedNetworkDomains.size <= MAX_NETWORK_DOMAINS) { "Too many runtime network domains" }
        if (request.networkEnabled) require(request.allowedNetworkDomains.isNotEmpty()) {
            "Runtime network access requires an explicit domain allowlist"
        }
        if (!request.networkEnabled) require(request.allowedNetworkDomains.isEmpty()) {
            "Runtime network domains require network access"
        }
        request.resourceLimits.validated()
        if (request.cancellationToken.isCancellationRequested) throw AgentNativeToolCancelledException()
        val current = status()
        check(current.languageReady(request.language)) {
            "${request.language.wireValue} requires the ${request.language.requiredPack} pack"
        }
        val activeBridge = bridge ?: AgentOnDeviceRuntimeBridgeRegistry.current()
            ?: AgentOnDeviceRuntimeSupervisor.discover(appContext)
            ?: error("The on-device guest bridge is not connected")
        val prepared = workspaceManager.prepare(request)
        val normalizedRequest = request.copy(guestWorkspacePath = prepared.guestPath)
        val packVersions = current.packs.mapNotNull { pack ->
            pack.manifest?.version?.takeIf(String::isNotBlank)?.let { version -> pack.id to version }
        }.toMap()
        receiptStore.begin(normalizedRequest, packVersions)
        return try {
            val rawResponse = activeBridge.execute(normalizedRequest)
            val artifacts = workspaceManager.collectArtifacts(prepared, normalizedRequest)
            val response = rawResponse.copy(
                artifacts = artifacts,
                requestId = normalizedRequest.requestId
            ).bounded()
            val receipt = receiptStore.complete(normalizedRequest.requestId, response, artifacts)
            workspaceManager.markFinished(
                prepared,
                if (response.exitCode == 0) AgentRuntimeReceiptStatus.COMPLETED else AgentRuntimeReceiptStatus.FAILED
            )
            response.copy(executionReceipt = receipt)
        } catch (error: Throwable) {
            receiptStore.fail(normalizedRequest.requestId, error)
            workspaceManager.markFinished(
                prepared,
                when (error) {
                    is AgentNativeToolCancelledException -> AgentRuntimeReceiptStatus.CANCELLED
                    is AgentNativeToolTimeoutException -> AgentRuntimeReceiptStatus.TIMED_OUT
                    else -> AgentRuntimeReceiptStatus.FAILED
                }
            )
            throw error
        }
    }

    fun receipt(requestId: String): AgentRuntimeExecutionReceipt? = receiptStore.find(requestId)

    private fun qemuEngineFile(): File = File(appContext.applicationInfo.nativeLibraryDir, QEMU_ENGINE_FILE)

    internal fun packsDirectory(): File = packsRoot

    internal fun runtimeSocketFile(): File = File(appContext.filesDir, "$RUNTIME_DIRECTORY/guest.sock")

    internal fun runtimeBootstrapFiles(): AgentRuntimeBootstrapFiles {
        val engine = qemuEngineFile()
        check(engine.isFile) { "Install the SignalASI QEMU engine" }
        val base = inspectPackDirectory(
            directory = File(packsRoot, "linux-base"),
            expectedId = "linux-base",
            checkDependencies = true
        )
        check(base.state == AgentRuntimePackState.READY && base.manifest != null) {
            base.reason.ifBlank { "Install the linux-base runtime pack" }
        }
        val image = safeChild(File(packsRoot, "linux-base"), base.manifest.imageFile)
        check(image?.isFile == true) { "The linux-base runtime image is unavailable" }
        val runtimeRoot = File(appContext.filesDir, RUNTIME_DIRECTORY)
        val workspaces = File(runtimeRoot, "workspaces")
        check(runtimeRoot.mkdirs() || runtimeRoot.isDirectory) { "Runtime storage is unavailable" }
        check(workspaces.mkdirs() || workspaces.isDirectory) { "Runtime workspace storage is unavailable" }
        val packAttachments = REQUIRED_PACKS.asSequence()
            .filterNot { it == "linux-base" }
            .map(::packStatus)
            .filter { it.state == AgentRuntimePackState.READY && it.manifest != null }
            .map { status ->
                val manifest = requireNotNull(status.manifest)
                val image = requireNotNull(safeChild(File(packsRoot, manifest.id), manifest.imageFile))
                AgentRuntimePackAttachment(
                    packId = manifest.id,
                    version = manifest.version,
                    capabilities = manifest.capabilities.toSet(),
                    imageFile = image
                )
            }
            .toList()
        return AgentRuntimeBootstrapFiles(
            engineFile = engine,
            baseImageFile = image,
            socketFile = runtimeSocketFile(),
            packsDirectory = packsRoot,
            workspacesDirectory = workspaces,
            architecture = Build.SUPPORTED_ABIS.firstOrNull().orEmpty(),
            packAttachments = packAttachments
        )
    }

    internal fun inspectPackDirectory(
        directory: File,
        expectedId: String? = null,
        checkDependencies: Boolean = true,
        forceIntegrityCheck: Boolean = false
    ): AgentRuntimePackStatus {
        val manifestFile = File(directory, MANIFEST_FILE)
        val fallbackId = expectedId ?: directory.name
        if (!manifestFile.isFile) return AgentRuntimePackStatus(fallbackId, AgentRuntimePackState.NOT_INSTALLED)
        val manifest = runCatching { decodeManifest(manifestFile.readText()) }.getOrNull()
            ?: return AgentRuntimePackStatus(fallbackId, AgentRuntimePackState.INVALID, "Invalid runtime pack manifest")
        if (expectedId != null && manifest.id != expectedId) {
            return AgentRuntimePackStatus(expectedId, AgentRuntimePackState.INVALID, "Runtime pack id does not match its directory", manifest)
        }
        if (manifest.id !in REQUIRED_PACKS || !PACK_ID_PATTERN.matches(manifest.id)) {
            return AgentRuntimePackStatus(manifest.id, AgentRuntimePackState.INCOMPATIBLE, "Runtime pack id is not supported", manifest)
        }
        if (manifest.architecture !in supportedArchitectures()) {
            return AgentRuntimePackStatus(manifest.id, AgentRuntimePackState.INCOMPATIBLE, "Runtime pack architecture is incompatible", manifest)
        }
        if (!VERSION_PATTERN.matches(manifest.version) || !SHA256_PATTERN.matches(manifest.imageSha256)) {
            return AgentRuntimePackStatus(manifest.id, AgentRuntimePackState.INVALID, "Runtime pack metadata is invalid", manifest)
        }
        if (manifest.formatVersion != SUPPORTED_PACK_FORMAT_VERSION ||
            manifest.minimumHostVersionCode > installedHostVersionCode() ||
            manifest.guestApiVersion != SUPPORTED_GUEST_API_VERSION
        ) {
            return AgentRuntimePackStatus(manifest.id, AgentRuntimePackState.INCOMPATIBLE, "Runtime pack protocol is incompatible", manifest)
        }
        if (manifest.archiveSizeBytes !in 1..MAX_ARCHIVE_BYTES ||
            manifest.installedSizeBytes !in 1..MAX_INSTALLED_BYTES ||
            manifest.license.isBlank() || manifest.license.length > MAX_LICENSE_CHARS ||
            manifest.capabilities.distinct().size != manifest.capabilities.size ||
            manifest.capabilities.any { !CAPABILITY_PATTERN.matches(it) } ||
            manifest.dependencies.distinct().size != manifest.dependencies.size ||
            manifest.dependencies.any { it !in REQUIRED_PACKS || it == manifest.id }
        ) {
            return AgentRuntimePackStatus(manifest.id, AgentRuntimePackState.INVALID, "Runtime pack metadata is incomplete", manifest)
        }
        val missingCapabilities = REQUIRED_PACK_CAPABILITIES[manifest.id].orEmpty() - manifest.capabilities.toSet()
        if (missingCapabilities.isNotEmpty()) {
            return AgentRuntimePackStatus(
                manifest.id,
                AgentRuntimePackState.INVALID,
                "Runtime pack capabilities are incomplete: ${missingCapabilities.sorted().joinToString()}",
                manifest
            )
        }
        if (manifest.signature.isBlank() || !SHA256_PATTERN.matches(manifest.signatureKeyId)) {
            return AgentRuntimePackStatus(manifest.id, AgentRuntimePackState.INVALID, "Runtime pack signature is missing", manifest)
        }
        if (!signatureVerifier.verify(manifest)) {
            return AgentRuntimePackStatus(manifest.id, AgentRuntimePackState.INVALID, "Runtime pack signature is not trusted", manifest)
        }
        val image = safeChild(directory, manifest.imageFile)
            ?: return AgentRuntimePackStatus(manifest.id, AgentRuntimePackState.INVALID, "Runtime pack image path is unsafe", manifest)
        if (!image.isFile) return AgentRuntimePackStatus(manifest.id, AgentRuntimePackState.INVALID, "Runtime pack image is missing", manifest)
        if (manifest.installedSizeBytes <= 0L || image.length() > manifest.installedSizeBytes + INSTALL_SIZE_TOLERANCE_BYTES) {
            return AgentRuntimePackStatus(manifest.id, AgentRuntimePackState.INVALID, "Runtime pack installed size is invalid", manifest)
        }
        val actualHash = sha256(image, manifest.imageSha256, forceIntegrityCheck)
        if (!actualHash.equals(manifest.imageSha256, ignoreCase = true)) {
            return AgentRuntimePackStatus(manifest.id, AgentRuntimePackState.INVALID, "Runtime pack image integrity check failed", manifest)
        }
        if (checkDependencies) {
            val missingDependency = manifest.dependencies.firstOrNull { dependency ->
                dependency != manifest.id && packStatusWithoutDependencies(dependency).state != AgentRuntimePackState.READY
            }
            if (missingDependency != null) {
                return AgentRuntimePackStatus(manifest.id, AgentRuntimePackState.INCOMPATIBLE, "Missing dependency: $missingDependency", manifest)
            }
        }
        return AgentRuntimePackStatus(manifest.id, AgentRuntimePackState.READY, manifest = manifest)
    }

    internal fun clearIntegrityCache(packId: String) {
        val prefix = "$packId|"
        integrityCache.edit().also { editor ->
            integrityCache.all.keys.filter { it.startsWith(prefix) }.forEach(editor::remove)
        }.apply()
    }

    private fun packStatus(id: String): AgentRuntimePackStatus = inspectPackDirectory(
        directory = File(packsRoot, id),
        expectedId = id
    )

    private fun packStatusWithoutDependencies(id: String): AgentRuntimePackStatus {
        val directory = File(packsRoot, id)
        return inspectPackDirectory(directory, expectedId = id, checkDependencies = false)
    }

    internal fun decodeManifest(raw: String): AgentRuntimePackManifest {
        require(raw.toByteArray().size <= MAX_MANIFEST_BYTES) { "Runtime manifest is too large" }
        val json = JSONObject(raw)
        return AgentRuntimePackManifest(
            id = json.getString("id").take(MAX_ID_CHARS),
            version = json.getString("version"),
            architecture = json.getString("architecture").lowercase(Locale.ROOT),
            imageFile = json.getString("image_file"),
            imageSha256 = json.getString("image_sha256").lowercase(Locale.ROOT),
            capabilities = json.optJSONArray("capabilities").strings(MAX_CAPABILITIES),
            dependencies = json.optJSONArray("dependencies").strings(MAX_DEPENDENCIES),
            installedSizeBytes = json.optLong("installed_size_bytes", 0L).coerceAtLeast(0L),
            license = json.optString("license"),
            signatureKeyId = json.optString("signature_key_id"),
            signature = json.optString("signature"),
            formatVersion = json.optInt("format_version", 0),
            archiveSizeBytes = json.optLong("archive_size_bytes", 0L).coerceAtLeast(0L),
            minimumHostVersionCode = json.optLong("minimum_host_version_code", 1L).coerceAtLeast(1L),
            guestApiVersion = json.optInt("guest_api_version", 0)
        )
    }

    @Suppress("DEPRECATION")
    private fun installedHostVersionCode(): Long = appContext.packageManager
        .getPackageInfo(appContext.packageName, 0)
        .let { info -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) info.longVersionCode else info.versionCode.toLong() }

    private fun safeChild(root: File, relative: String): File? {
        if (relative.isBlank() || File(relative).isAbsolute) return null
        val canonicalRoot = root.canonicalFile
        val candidate = File(canonicalRoot, relative).canonicalFile
        return candidate.takeIf { it.path.startsWith(canonicalRoot.path + File.separator) }
    }

    internal fun supportedArchitectures(): Set<String> = Build.SUPPORTED_ABIS.flatMapTo(linkedSetOf()) { abi ->
        when (abi.lowercase(Locale.ROOT)) {
            "arm64-v8a" -> listOf("arm64-v8a", "aarch64")
            "x86_64" -> listOf("x86_64", "amd64")
            else -> listOf(abi.lowercase(Locale.ROOT))
        }
    }

    private fun sha256(file: File, expectedHash: String, force: Boolean = false): String {
        val cacheKey = "${file.parentFile?.name.orEmpty()}|${file.canonicalPath}"
        val cacheStamp = "${file.length()}:${file.lastModified()}:${expectedHash.lowercase(Locale.ROOT)}"
        if (!force) {
            integrityCache.getString(cacheKey, null)?.let { cached ->
                val separator = cached.indexOf('|')
                if (separator > 0 && cached.substring(0, separator) == cacheStamp) {
                    return cached.substring(separator + 1)
                }
            }
        }
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }.also { actual ->
            integrityCache.edit().putString(cacheKey, "$cacheStamp|$actual").apply()
        }
    }

    private fun AgentRuntimeExecutionResponse.bounded(): AgentRuntimeExecutionResponse = copy(
        stdout = stdout.take(MAX_OUTPUT_CHARS),
        stderr = stderr.take(MAX_OUTPUT_CHARS),
        artifacts = artifacts.take(MAX_ARTIFACTS)
    )

    private fun JSONArray?.strings(limit: Int): List<String> = buildList {
        val source = this@strings ?: return@buildList
        for (index in 0 until minOf(source.length(), limit)) {
            source.optString(index).takeIf(String::isNotBlank)?.let(::add)
        }
    }

    companion object {
        val REQUIRED_PACKS = listOf("linux-base", "python-uv", "node-js", "go", "rust", "cpp", "java", "ffmpeg")
        val REQUIRED_PACK_CAPABILITIES: Map<String, Set<String>> = AgentRuntimeLanguage.entries
            .groupBy(AgentRuntimeLanguage::requiredPack)
            .mapValues { (_, languages) ->
                languages.mapTo(linkedSetOf(), AgentRuntimeLanguage::requiredCapability)
            }
        private const val RUNTIME_DIRECTORY = "agent-runtime"
        private const val PACKS_DIRECTORY = "packs"
        private const val MANIFEST_FILE = "manifest.json"
        private const val QEMU_ENGINE_FILE = "libsignalasi_qemu.so"
        private const val AVF_FEATURE = "android.software.virtualization_framework"
        private const val INTEGRITY_CACHE = "signalasi_runtime_integrity_cache_v1"
        private const val MAX_MANIFEST_BYTES = 64 * 1024
        private const val MAX_SOURCE_BYTES = 256 * 1024
        private const val MAX_ARGUMENTS = 256
        private const val MAX_ARGUMENT_BYTES = 8 * 1024
        private const val MAX_ARTIFACTS = 32
        private const val MAX_NETWORK_DOMAINS = 64
        private const val MAX_OUTPUT_CHARS = 512 * 1024
        private const val MAX_ID_CHARS = 80
        private const val MAX_CAPABILITIES = 128
        private const val MAX_DEPENDENCIES = 32
        private const val MAX_LICENSE_CHARS = 256
        private const val MAX_ARCHIVE_BYTES = 6L * 1024L * 1024L * 1024L
        private const val MAX_INSTALLED_BYTES = 12L * 1024L * 1024L * 1024L
        private const val INSTALL_SIZE_TOLERANCE_BYTES = 16L * 1024L * 1024L
        private const val SUPPORTED_PACK_FORMAT_VERSION = 1
        private const val SUPPORTED_GUEST_API_VERSION = 1
        private const val MIN_TIMEOUT_MILLIS = 100L
        private const val MAX_TIMEOUT_MILLIS = 30 * 60_000L
        private val VERSION_PATTERN = Regex("[0-9]+\\.[0-9]+\\.[0-9]+(?:[-+][0-9A-Za-z.-]+)?")
        private val SHA256_PATTERN = Regex("[0-9a-f]{64}")
        private val PACK_ID_PATTERN = Regex("[a-z0-9][a-z0-9._-]{0,79}")
        private val REQUEST_ID_PATTERN = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")
        private val CAPABILITY_PATTERN = Regex("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}")
    }
}

object AgentOnDeviceRuntimeTools {
    const val STATUS = "signalasi.runtime.status"
    const val LIST_PACKS = "signalasi.runtime.packs.list"
    const val EXECUTE = "signalasi.runtime.execute"

    val toolIds = setOf(STATUS, LIST_PACKS, EXECUTE)

    fun definitions(context: Context): List<AgentNativeToolDefinition> {
        val manager = AgentOnDeviceRuntimeManager(context.applicationContext)
        return listOf(
            AgentNativeToolDefinition(
                descriptor = descriptor(
                    id = STATUS,
                    title = "Inspect on-device runtime",
                    description = "Reports Android-local Linux backend, language, toolchain, and media-pack readiness.",
                    input = AgentNativeJsonSchema.objectSchema(additionalProperties = false),
                    risk = AgentNativeToolRisk.LOW,
                    availability = AgentNativeToolAvailability.AVAILABLE
                ),
                executor = AgentNativeToolExecutor {
                    val status = manager.status()
                    AgentNativeToolExecutionResult.success(runtimeStatusOutput(status), "On-device runtime inspected")
                },
                executorId = "signalasi.android_runtime_broker"
            ),
            AgentNativeToolDefinition(
                descriptor = descriptor(
                    id = LIST_PACKS,
                    title = "List on-device runtime packs",
                    description = "Lists Android-local Linux, language, FFmpeg, and toolchain pack state.",
                    input = AgentNativeJsonSchema.objectSchema(additionalProperties = false),
                    risk = AgentNativeToolRisk.LOW,
                    availability = AgentNativeToolAvailability.AVAILABLE
                ),
                executor = AgentNativeToolExecutor {
                    val status = manager.status()
                    AgentNativeToolExecutionResult.success(
                        mapOf("packs" to status.packs.map(::packOutput)),
                        "On-device runtime packs listed"
                    )
                },
                executorId = "signalasi.android_runtime_broker"
            ),
            AgentNativeToolDefinition(
                descriptor = descriptor(
                    id = EXECUTE,
                    title = "Execute in the on-device Linux sandbox",
                    description = "Runs bounded shell, language, build, or FFmpeg work in the Android-local Linux runtime.",
                    input = executionInputSchema(),
                    risk = AgentNativeToolRisk.MEDIUM,
                    timeoutMillis = 30 * 60_000L,
                    availability = executionAvailability(manager)
                ),
                executor = AgentNativeToolExecutor { invocation ->
                    val language = AgentRuntimeLanguage.entries.firstOrNull {
                        it.wireValue == invocation.input["language"]?.toString()
                    } ?: return@AgentNativeToolExecutor AgentNativeToolExecutionResult.failure(
                        "invalid_runtime_language", "Runtime language is invalid"
                    )
                    val request = AgentRuntimeExecutionRequest(
                        language = language,
                        source = invocation.input["source"]?.toString().orEmpty(),
                        arguments = invocation.input.stringList("arguments"),
                        timeoutMillis = (invocation.input["timeout_ms"] as? Number)?.toLong() ?: 60_000L,
                        networkEnabled = invocation.input["network_enabled"] as? Boolean ?: false,
                        allowedNetworkDomains = invocation.input.stringList("allowed_network_domains"),
                        artifactPaths = invocation.input.stringList("artifact_paths"),
                        workspaceId = invocation.context.turnId
                            .ifBlank { invocation.context.conversationId }
                            .ifBlank { invocation.context.invocationId },
                        requestId = invocation.context.invocationId,
                        cancellationToken = invocation.cancellationToken,
                        progressListener = { progress ->
                            invocation.reportProgress(
                                stage = progress.stage,
                                message = progress.message,
                                percent = progress.percent,
                                sequence = progress.sequence,
                                timestampEpochMillis = progress.timestampMillis
                            )
                        }
                    )
                    runCatching { manager.execute(request) }.fold(
                        onSuccess = ::runtimeExecutionResult,
                        onFailure = { error ->
                            val evidence = manager.receipt(request.requestId)?.toEvidenceMap()
                            AgentNativeToolExecutionResult(
                                output = evidence?.let { mapOf("execution_receipt" to it) }.orEmpty(),
                                error = AgentNativeToolError(
                                    code = "on_device_runtime_failed",
                                    message = error.message ?: "On-device runtime failed",
                                    retryable = false,
                                    details = evidence.orEmpty()
                                )
                            )
                        }
                    )
                },
                executorId = "signalasi.android_runtime_broker",
                provenanceMetadata = mapOf(
                    "platform" to "android",
                    "sandbox" to "linux_guest",
                    "network_default" to "disabled"
                ),
                availabilityProvider = AgentNativeToolAvailabilityProvider { executionAvailability(manager) }
            )
        )
    }

    internal fun runtimeExecutionResult(response: AgentRuntimeExecutionResponse): AgentNativeToolExecutionResult {
        val output = runtimeExecutionOutput(response)
        if (response.exitCode == 0) {
            return AgentNativeToolExecutionResult.success(output, "On-device runtime completed")
        }
        return AgentNativeToolExecutionResult(
            output = output,
            message = "On-device runtime exited with ${response.exitCode}",
            error = AgentNativeToolError(
                code = "on_device_runtime_nonzero_exit",
                message = "On-device runtime exited with ${response.exitCode}",
                retryable = false,
                details = response.executionReceipt?.toEvidenceMap().orEmpty()
            )
        )
    }

    private fun runtimeExecutionOutput(response: AgentRuntimeExecutionResponse): AgentNativeJsonObject = buildMap {
        put("exit_code", response.exitCode)
        put("stdout", response.stdout)
        put("stderr", response.stderr)
        put("duration_ms", response.durationMillis)
        put("artifacts", response.artifacts)
        response.executionReceipt?.let { put("execution_receipt", it.toEvidenceMap()) }
    }

    private fun descriptor(
        id: String,
        title: String,
        description: String,
        input: AgentNativeJsonSchema,
        risk: AgentNativeToolRisk,
        timeoutMillis: Long = 30_000L,
        availability: AgentNativeToolAvailability
    ) = AgentNativeToolDescriptor(
        id = id,
        version = "1.0.0",
        title = title,
        description = description,
        location = AgentNativeToolLocation.APPLICATION,
        inputSchema = input,
        outputSchema = AgentNativeJsonSchema.any(),
        risk = risk,
        capabilities = setOf("runtime.android_local", "runtime.linux", "runtime.sandboxed"),
        timeoutMillis = timeoutMillis,
        idempotency = AgentNativeToolIdempotency.NON_IDEMPOTENT,
        availability = availability
    )

    private fun executionInputSchema() = AgentNativeJsonSchema.objectSchema(
        properties = mapOf(
            "language" to AgentNativeJsonSchema.string(enumValues = AgentRuntimeLanguage.entries.map { it.wireValue }),
            "source" to AgentNativeJsonSchema.string(maxLength = 256 * 1024),
            "arguments" to AgentNativeJsonSchema.array(AgentNativeJsonSchema.string(maxLength = 8 * 1024), maxItems = 256),
            "timeout_ms" to AgentNativeJsonSchema.integer(100, 30 * 60_000L),
            "network_enabled" to AgentNativeJsonSchema.boolean(),
            "allowed_network_domains" to AgentNativeJsonSchema.array(
                AgentNativeJsonSchema.string(maxLength = 253),
                maxItems = 64
            ),
            "artifact_paths" to AgentNativeJsonSchema.array(AgentNativeJsonSchema.string(maxLength = 1_024), maxItems = 32)
        ),
        required = setOf("language", "source"),
        additionalProperties = false
    )

    private fun executionAvailability(manager: AgentOnDeviceRuntimeManager): AgentNativeToolAvailability {
        val status = manager.status()
        return if (status.backendReady) AgentNativeToolAvailability.AVAILABLE else AgentNativeToolAvailability(
            AgentNativeToolAvailabilityStatus.REQUIRES_SETUP,
            status.reason,
            System.currentTimeMillis()
        )
    }

    private fun runtimeStatusOutput(status: AgentOnDeviceRuntimeStatus): Map<String, Any?> = mapOf(
        "backend" to status.backend.wireValue,
        "backend_ready" to status.backendReady,
        "reason" to status.reason,
        "lifecycle" to mapOf(
            "phase" to status.lifecyclePhase.wireValue,
            "reason" to status.lifecycleReason,
            "consecutive_failures" to status.lifecycleFailures,
            "next_attempt_at_millis" to status.lifecycleNextAttemptAtMillis
        ),
        "architecture" to status.architecture,
        "avf_advertised" to status.avfAdvertised,
        "packs" to status.packs.map(::packOutput),
        "languages" to AgentRuntimeLanguage.entries.map { language ->
            mapOf(
                "id" to language.wireValue,
                "required_pack" to language.requiredPack,
                "required_capability" to language.requiredCapability,
                "ready" to status.languageReady(language)
            )
        }
    )

    private fun packOutput(pack: AgentRuntimePackStatus): Map<String, Any?> = mapOf(
        "id" to pack.id,
        "state" to pack.state.wireValue,
        "reason" to pack.reason,
        "version" to pack.manifest?.version.orEmpty(),
        "architecture" to pack.manifest?.architecture.orEmpty(),
        "capabilities" to pack.manifest?.capabilities.orEmpty(),
        "installed_size_bytes" to (pack.manifest?.installedSizeBytes ?: 0L),
        "license" to pack.manifest?.license.orEmpty()
    )

    private fun Map<String, Any?>.stringList(key: String): List<String> =
        (this[key] as? Iterable<*>)?.mapNotNull { it?.toString() }.orEmpty()
}
