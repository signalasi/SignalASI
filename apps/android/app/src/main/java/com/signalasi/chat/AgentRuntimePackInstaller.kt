package com.signalasi.chat

import android.content.Context
import android.net.Uri
import android.os.StatFs
import android.provider.OpenableColumns
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import java.util.zip.ZipInputStream

data class AgentRuntimePackInstallResult(
    val packId: String,
    val version: String,
    val state: AgentRuntimePackState,
    val installedBytes: Long,
    val replacedExisting: Boolean,
    val reason: String = ""
)

enum class AgentRuntimePackInstallStage {
    PREPARING,
    COPYING,
    EXTRACTING,
    VERIFYING,
    ACTIVATING,
    COMPLETED
}

data class AgentRuntimePackInstallProgress(
    val stage: AgentRuntimePackInstallStage,
    val processedBytes: Long = 0L,
    val totalBytes: Long = -1L
)

class AgentRuntimePackInstaller(
    context: Context,
    private val signatureVerifier: AgentRuntimePackSignatureVerifier =
        AndroidAppSigningRuntimePackVerifier(context)
) {
    private val appContext = context.applicationContext
    private val manager = AgentOnDeviceRuntimeManager(appContext, signatureVerifier = signatureVerifier)
    private val packsRoot = manager.packsDirectory()

    fun install(
        uri: Uri,
        cancellationToken: AgentNativeToolCancellationToken = AgentNativeToolCancellationToken.NONE,
        onProgress: (AgentRuntimePackInstallProgress) -> Unit = {}
    ): AgentRuntimePackInstallResult {
        packsRoot.mkdirs()
        require(packsRoot.isDirectory) { "Runtime pack storage is unavailable" }
        val operationId = UUID.randomUUID().toString()
        val archive = File(packsRoot, ".import-$operationId.sarpack")
        val declaredArchiveBytes = contentLength(uri)
        check(declaredArchiveBytes <= MAX_ARCHIVE_BYTES || declaredArchiveBytes < 0L) {
            "Runtime pack archive exceeds the size limit"
        }
        ensureFreeSpace((declaredArchiveBytes.coerceAtLeast(0L) * 2L) + MIN_FREE_BYTES)
        try {
            onProgress(AgentRuntimePackInstallProgress(AgentRuntimePackInstallStage.PREPARING, totalBytes = declaredArchiveBytes))
            copyArchive(uri, archive, declaredArchiveBytes, cancellationToken, onProgress)
            return install(archive, cancellationToken, onProgress)
        } finally {
            archive.delete()
        }
    }

    internal fun install(
        archive: File,
        cancellationToken: AgentNativeToolCancellationToken = AgentNativeToolCancellationToken.NONE,
        onProgress: (AgentRuntimePackInstallProgress) -> Unit = {}
    ): AgentRuntimePackInstallResult {
        require(archive.isFile && archive.length() in 1..MAX_ARCHIVE_BYTES) {
            "Runtime pack archive is missing or exceeds the size limit"
        }
        packsRoot.mkdirs()
        require(packsRoot.isDirectory) { "Runtime pack storage is unavailable" }
        cancellationToken.checkpoint()
        ensureFreeSpace(archive.length() + MIN_FREE_BYTES)
        val operationId = UUID.randomUUID().toString()
        val staging = File(packsRoot, ".stage-$operationId")
        var destination: File? = null
        var backup: File? = null
        var activated = false
        try {
            check(staging.mkdirs()) { "Runtime pack staging directory could not be created" }
            val extractedBytes = extractArchive(archive, staging, cancellationToken, onProgress)
            cancellationToken.checkpoint()
            onProgress(AgentRuntimePackInstallProgress(AgentRuntimePackInstallStage.VERIFYING, archive.length(), archive.length()))
            val staged = manager.inspectPackDirectory(
                directory = staging,
                checkDependencies = false,
                forceIntegrityCheck = true
            )
            check(staged.state == AgentRuntimePackState.READY && staged.manifest != null) {
                staged.reason.ifBlank { "Runtime pack validation failed" }
            }
            val manifest = staged.manifest
            check(archive.length() <= manifest.archiveSizeBytes + ARCHIVE_SIZE_TOLERANCE_BYTES) {
                "Runtime pack archive exceeds its signed size"
            }
            check(extractedBytes <= manifest.installedSizeBytes + INSTALL_SIZE_TOLERANCE_BYTES) {
                "Runtime pack content exceeds its signed installed size"
            }
            val target = File(packsRoot, manifest.id)
            val previous = File(packsRoot, ".backup-${manifest.id}-$operationId")
            destination = target
            backup = previous
            val replacing = target.exists()
            cancellationToken.checkpoint()
            onProgress(AgentRuntimePackInstallProgress(AgentRuntimePackInstallStage.ACTIVATING, archive.length(), archive.length()))
            if (replacing) check(target.renameTo(previous)) { "Existing runtime pack could not be staged for update" }
            if (!staging.renameTo(target)) {
                if (replacing) previous.renameTo(target)
                error("Runtime pack could not be activated")
            }
            activated = true
            manager.clearIntegrityCache(manifest.id)
            val installed = manager.inspectPackDirectory(
                directory = target,
                expectedId = manifest.id,
                checkDependencies = true,
                forceIntegrityCheck = true
            )
            if (installed.state == AgentRuntimePackState.INVALID) {
                error(installed.reason.ifBlank { "Installed runtime pack failed validation" })
            }
            previous.deleteRecursively()
            onProgress(AgentRuntimePackInstallProgress(AgentRuntimePackInstallStage.COMPLETED, archive.length(), archive.length()))
            return AgentRuntimePackInstallResult(
                packId = manifest.id,
                version = manifest.version,
                state = installed.state,
                installedBytes = extractedBytes,
                replacedExisting = replacing,
                reason = installed.reason
            )
        } catch (error: Throwable) {
            val activeDestination = destination
            val previous = backup
            if (activated && activeDestination != null) activeDestination.deleteRecursively()
            if (previous != null && previous.exists() && activeDestination != null && !activeDestination.exists()) {
                previous.renameTo(activeDestination)
            }
            throw error
        } finally {
            staging.deleteRecursively()
        }
    }

    fun uninstall(packId: String): Boolean {
        require(packId in AgentOnDeviceRuntimeManager.REQUIRED_PACKS) { "Runtime pack id is not supported" }
        val dependent = manager.status().packs.firstOrNull { pack ->
            pack.id != packId && pack.manifest?.dependencies?.contains(packId) == true
        }
        check(dependent == null) { "${dependent?.id} depends on $packId" }
        val directory = File(packsRoot, packId)
        if (!directory.exists()) return false
        val quarantine = File(packsRoot, ".remove-$packId-${UUID.randomUUID()}")
        check(directory.renameTo(quarantine)) { "Runtime pack could not be removed" }
        manager.clearIntegrityCache(packId)
        return quarantine.deleteRecursively()
    }

    private fun copyArchive(
        uri: Uri,
        destination: File,
        declaredBytes: Long,
        cancellationToken: AgentNativeToolCancellationToken,
        onProgress: (AgentRuntimePackInstallProgress) -> Unit
    ) {
        val input = appContext.contentResolver.openInputStream(uri)
            ?: error("Runtime pack could not be opened")
        input.buffered().use { source ->
            FileOutputStream(destination).buffered().use { target ->
                val buffer = ByteArray(COPY_BUFFER_BYTES)
                var total = 0L
                while (true) {
                    cancellationToken.checkpoint()
                    val read = source.read(buffer)
                    if (read < 0) break
                    total += read
                    check(total <= MAX_ARCHIVE_BYTES) { "Runtime pack archive exceeds the size limit" }
                    target.write(buffer, 0, read)
                    onProgress(AgentRuntimePackInstallProgress(AgentRuntimePackInstallStage.COPYING, total, declaredBytes))
                }
            }
        }
    }

    private fun extractArchive(
        archive: File,
        destination: File,
        cancellationToken: AgentNativeToolCancellationToken,
        onProgress: (AgentRuntimePackInstallProgress) -> Unit
    ): Long {
        var entries = 0
        var totalBytes = 0L
        val canonicalRoot = destination.canonicalFile
        ZipInputStream(archive.inputStream().buffered()).use { zip ->
            while (true) {
                cancellationToken.checkpoint()
                val entry = zip.nextEntry ?: break
                entries += 1
                check(entries <= MAX_ARCHIVE_ENTRIES) { "Runtime pack contains too many files" }
                val name = entry.name
                check(name.isNotBlank() && !name.contains('\\') && !File(name).isAbsolute) {
                    "Runtime pack contains an unsafe path"
                }
                val target = File(canonicalRoot, name).canonicalFile
                check(target.path.startsWith(canonicalRoot.path + File.separator)) {
                    "Runtime pack contains an unsafe path"
                }
                if (entry.isDirectory) {
                    check(target.mkdirs() || target.isDirectory) { "Runtime pack directory could not be created" }
                } else {
                    check(target.parentFile?.let { it.isDirectory || it.mkdirs() } != false) {
                        "Runtime pack directory could not be created"
                    }
                    FileOutputStream(target).buffered().use { output ->
                        val buffer = ByteArray(COPY_BUFFER_BYTES)
                        while (true) {
                            cancellationToken.checkpoint()
                            val read = zip.read(buffer)
                            if (read < 0) break
                            totalBytes += read
                            check(totalBytes <= MAX_EXTRACTED_BYTES) { "Runtime pack content exceeds the size limit" }
                            output.write(buffer, 0, read)
                            onProgress(AgentRuntimePackInstallProgress(
                                AgentRuntimePackInstallStage.EXTRACTING,
                                totalBytes,
                                -1L
                            ))
                        }
                    }
                }
                zip.closeEntry()
            }
        }
        check(File(destination, MANIFEST_FILE).isFile) { "Runtime pack manifest is missing" }
        return totalBytes
    }

    private fun contentLength(uri: Uri): Long = runCatching {
        appContext.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
            if (!cursor.moveToFirst()) return@use -1L
            val index = cursor.getColumnIndex(OpenableColumns.SIZE)
            if (index < 0 || cursor.isNull(index)) -1L else cursor.getLong(index)
        } ?: -1L
    }.getOrDefault(-1L)

    private fun ensureFreeSpace(requiredBytes: Long) {
        val available = StatFs(packsRoot.absolutePath).availableBytes
        check(available >= requiredBytes.coerceAtLeast(MIN_FREE_BYTES)) {
            "Not enough storage for the runtime pack"
        }
    }

    private fun AgentNativeToolCancellationToken.checkpoint() {
        if (isCancellationRequested) throw AgentNativeToolCancelledException()
    }

    companion object {
        const val PACKAGE_MIME_TYPE = "application/vnd.signalasi.runtime-pack+zip"
        private const val MANIFEST_FILE = "manifest.json"
        private const val COPY_BUFFER_BYTES = 256 * 1024
        private const val MAX_ARCHIVE_ENTRIES = 2_048
        private const val MAX_ARCHIVE_BYTES = 6L * 1024L * 1024L * 1024L
        private const val MAX_EXTRACTED_BYTES = 12L * 1024L * 1024L * 1024L
        private const val MIN_FREE_BYTES = 256L * 1024L * 1024L
        private const val INSTALL_SIZE_TOLERANCE_BYTES = 16L * 1024L * 1024L
        private const val ARCHIVE_SIZE_TOLERANCE_BYTES = 4L * 1024L * 1024L
    }
}
