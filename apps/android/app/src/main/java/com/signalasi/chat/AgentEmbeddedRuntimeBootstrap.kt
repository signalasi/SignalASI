package com.signalasi.chat

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.UUID

internal data class AgentEmbeddedRuntimePack(
    val id: String,
    val version: String,
    val architecture: String,
    val assetPath: String,
    val archiveSha256: String,
    val archiveSizeBytes: Long,
    val installedSizeBytes: Long,
    val dependencies: List<String>
)

internal data class AgentEmbeddedRuntimeBundle(
    val architecture: String,
    val packs: List<AgentEmbeddedRuntimePack>,
    val formatVersion: Int = 1
)

internal data class AgentEmbeddedRuntimeBootstrapResult(
    val bundled: Boolean,
    val installed: List<String> = emptyList(),
    val retained: List<String> = emptyList()
)

internal object AgentEmbeddedRuntimeBundleCodec {
    fun decode(raw: String): AgentEmbeddedRuntimeBundle {
        require(raw.toByteArray(Charsets.UTF_8).size in 1..MAX_INDEX_BYTES) {
            "Embedded runtime index exceeds the size limit"
        }
        val root = JSONObject(raw)
        require(root.optInt("format_version") == 1) { "Embedded runtime index version is unsupported" }
        val architecture = root.getString("architecture")
        require(architecture == "arm64-v8a") { "Embedded runtime architecture is unsupported" }
        val values = root.getJSONArray("packs")
        val packs = buildList {
            for (index in 0 until values.length()) {
                val value = values.getJSONObject(index)
                add(AgentEmbeddedRuntimePack(
                    id = value.getString("pack_id"),
                    version = value.getString("version"),
                    architecture = value.getString("architecture"),
                    assetPath = value.getString("asset_path"),
                    archiveSha256 = value.getString("archive_sha256").lowercase(),
                    archiveSizeBytes = value.getLong("archive_size_bytes"),
                    installedSizeBytes = value.getLong("installed_size_bytes"),
                    dependencies = value.optJSONArray("dependencies").let { dependencies ->
                        buildList {
                            if (dependencies != null) {
                                for (dependencyIndex in 0 until dependencies.length()) {
                                    add(dependencies.getString(dependencyIndex))
                                }
                            }
                        }
                    }
                ))
            }
        }
        validate(packs)
        return AgentEmbeddedRuntimeBundle(architecture = architecture, packs = packs)
    }

    private fun validate(packs: List<AgentEmbeddedRuntimePack>) {
        require(packs.map(AgentEmbeddedRuntimePack::id) == DEFAULT_PACKS) {
            "Embedded runtime must contain linux-base followed by python-uv"
        }
        packs.forEach { pack ->
            require(pack.architecture == "arm64-v8a" && VERSION_PATTERN.matches(pack.version)) {
                "Embedded runtime pack is incompatible: ${pack.id}"
            }
            require(ASSET_PATH_PATTERN.matches(pack.assetPath) && SHA256_PATTERN.matches(pack.archiveSha256)) {
                "Embedded runtime pack metadata is invalid: ${pack.id}"
            }
            require(pack.archiveSizeBytes in 1..MAX_ARCHIVE_BYTES && pack.installedSizeBytes in 1..MAX_INSTALLED_BYTES) {
                "Embedded runtime pack size is invalid: ${pack.id}"
            }
            require(pack.dependencies.distinct().size == pack.dependencies.size &&
                pack.dependencies.all { it in DEFAULT_PACKS && it != pack.id }
            ) { "Embedded runtime pack dependencies are invalid: ${pack.id}" }
        }
        require(packs.first().dependencies.isEmpty()) { "linux-base cannot depend on another embedded pack" }
        require(packs.last().dependencies == listOf("linux-base")) { "python-uv must depend on linux-base" }
    }

    private const val MAX_INDEX_BYTES = 64 * 1024
    private const val MAX_ARCHIVE_BYTES = 6L * 1024L * 1024L * 1024L
    private const val MAX_INSTALLED_BYTES = 12L * 1024L * 1024L * 1024L
    private val DEFAULT_PACKS = listOf("linux-base", "python-uv")
    private val VERSION_PATTERN = Regex("[0-9]+\\.[0-9]+\\.[0-9]+(?:[-+][0-9A-Za-z._-]+)?")
    private val SHA256_PATTERN = Regex("[0-9a-f]{64}")
    private val ASSET_PATH_PATTERN = Regex("runtime/bootstrap/[A-Za-z0-9._+-]+\\.sarpack")
}

internal object AgentEmbeddedRuntimeBootstrap {
    @Synchronized
    fun ensureInstalled(context: Context): AgentEmbeddedRuntimeBootstrapResult {
        val appContext = context.applicationContext
        val bundle = loadBundle(appContext) ?: return AgentEmbeddedRuntimeBootstrapResult(bundled = false)
        val manager = AgentOnDeviceRuntimeManager(appContext)
        val installer = AgentRuntimePackInstaller(appContext)
        val installed = mutableListOf<String>()
        val retained = mutableListOf<String>()
        bundle.packs.forEach { pack ->
            val current = manager.inspectPackDirectory(
                directory = File(manager.packsDirectory(), pack.id),
                expectedId = pack.id,
                checkDependencies = true
            )
            if (current.state == AgentRuntimePackState.READY &&
                current.manifest != null && compareVersions(current.manifest.version, pack.version) >= 0
            ) {
                retained += pack.id
                return@forEach
            }
            val archive = copyVerifiedArchive(appContext, pack)
            try {
                val result = installer.install(archive)
                check(result.state == AgentRuntimePackState.READY) {
                    result.reason.ifBlank { "Embedded runtime pack did not become ready: ${pack.id}" }
                }
                installed += pack.id
            } finally {
                archive.delete()
            }
        }
        return AgentEmbeddedRuntimeBootstrapResult(
            bundled = true,
            installed = installed,
            retained = retained
        )
    }

    private fun loadBundle(context: Context): AgentEmbeddedRuntimeBundle? {
        val bundledFiles = context.assets.list("runtime/bootstrap").orEmpty()
        if ("index.json" !in bundledFiles) return null
        return context.assets.open(INDEX_ASSET).bufferedReader(Charsets.UTF_8).use { reader ->
            AgentEmbeddedRuntimeBundleCodec.decode(reader.readText())
        }
    }

    private fun copyVerifiedArchive(context: Context, pack: AgentEmbeddedRuntimePack): File {
        val directory = File(context.cacheDir, "embedded-runtime")
        check(directory.mkdirs() || directory.isDirectory) { "Embedded runtime cache is unavailable" }
        directory.listFiles().orEmpty()
            .filter { it.name.startsWith(".${pack.id}-") && it.name.endsWith(".sarpack") }
            .forEach(File::delete)
        val target = File(directory, ".${pack.id}-${UUID.randomUUID()}.sarpack")
        val digest = MessageDigest.getInstance("SHA-256")
        var total = 0L
        try {
            context.assets.open(pack.assetPath).buffered().use { input ->
                FileOutputStream(target).buffered().use { output ->
                    val buffer = ByteArray(COPY_BUFFER_BYTES)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        total += read
                        check(total <= pack.archiveSizeBytes) {
                            "Embedded runtime archive exceeds its declared size: ${pack.id}"
                        }
                        digest.update(buffer, 0, read)
                        output.write(buffer, 0, read)
                    }
                }
            }
            check(total == pack.archiveSizeBytes) { "Embedded runtime archive is truncated: ${pack.id}" }
            val actual = digest.digest().joinToString("") { "%02x".format(it) }
            check(actual == pack.archiveSha256) { "Embedded runtime archive failed APK integrity verification: ${pack.id}" }
            return target
        } catch (error: Throwable) {
            target.delete()
            throw error
        }
    }

    internal fun compareVersions(left: String, right: String): Int {
        val leftValue = left.substringBefore('+')
        val rightValue = right.substringBefore('+')
        val leftParts = leftValue.substringBefore('-').split('.').map { it.toIntOrNull() ?: 0 }
        val rightParts = rightValue.substringBefore('-').split('.').map { it.toIntOrNull() ?: 0 }
        for (index in 0 until maxOf(leftParts.size, rightParts.size)) {
            val comparison = (leftParts.getOrElse(index) { 0 }).compareTo(rightParts.getOrElse(index) { 0 })
            if (comparison != 0) return comparison
        }
        val leftPrerelease = leftValue.substringAfter('-', "").ifBlank { null }
        val rightPrerelease = rightValue.substringAfter('-', "").ifBlank { null }
        return when {
            leftPrerelease == null && rightPrerelease == null -> 0
            leftPrerelease == null -> 1
            rightPrerelease == null -> -1
            else -> leftPrerelease.compareTo(rightPrerelease)
        }
    }

    private const val INDEX_ASSET = "runtime/bootstrap/index.json"
    private const val COPY_BUFFER_BYTES = 256 * 1024
}
