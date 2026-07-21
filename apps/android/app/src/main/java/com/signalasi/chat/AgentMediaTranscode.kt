package com.signalasi.chat

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.security.MessageDigest
import java.util.Locale
import org.json.JSONObject

enum class AgentMediaTargetFormat(
    val wireValue: String,
    val extension: String,
    val mimeType: String
) {
    MP4("mp4", "mp4", "video/mp4"),
    M4A("m4a", "m4a", "audio/mp4"),
    WAV("wav", "wav", "audio/wav"),
    FLAC("flac", "flac", "audio/flac"),
    GIF("gif", "gif", "image/gif"),
    PNG("png", "png", "image/png"),
    JPG("jpg", "jpg", "image/jpeg");

    companion object {
        fun fromWireValue(value: String): AgentMediaTargetFormat? = entries.firstOrNull {
            it.wireValue == value.lowercase(Locale.ROOT)
        }
    }
}

enum class AgentMediaTranscodePreset(val wireValue: String) {
    COMPACT("compact"),
    BALANCED("balanced"),
    HIGH_QUALITY("high_quality");

    companion object {
        fun fromWireValue(value: String): AgentMediaTranscodePreset? = entries.firstOrNull {
            it.wireValue == value.lowercase(Locale.ROOT)
        }
    }
}

data class AgentMediaTranscodeRequest(
    val contentUri: String = "",
    val sourcePath: String = "",
    val destinationPath: String = "",
    val targetFormat: AgentMediaTargetFormat,
    val preset: AgentMediaTranscodePreset = AgentMediaTranscodePreset.BALANCED,
    val startMillis: Long = 0L,
    val durationMillis: Long = 0L,
    val maxWidth: Int = 0,
    val maxHeight: Int = 0,
    val audioBitrateKbps: Int = 0,
    val timeoutMillis: Long = 5L * 60_000L,
    val workspaceId: String,
    val invocationId: String,
    val cancellationToken: AgentNativeToolCancellationToken = AgentNativeToolCancellationToken.NONE,
    val checkpoint: () -> Unit = {},
    val progressListener: (AgentRuntimeProgress) -> Unit = {}
)

data class AgentMediaTranscodeResult(
    val sourcePath: String,
    val destinationPath: String,
    val targetFormat: AgentMediaTargetFormat,
    val sizeBytes: Long,
    val sha256: String,
    val executionDurationMillis: Long,
    val artifacts: List<AgentNativeJsonObject>,
    val executionReceipt: AgentNativeJsonObject
)

interface AgentMediaTranscoder {
    val availability: AgentNativeToolAvailability
    val implementationId: String
    fun transcode(request: AgentMediaTranscodeRequest): AgentMediaTranscodeResult
}

internal fun AgentOnDeviceRuntimeStatus.ffmpegSetupReason(): String {
    if (!backendReady) return reason.ifBlank { "Start the Android-local Linux runtime" }
    val pack = packs.firstOrNull { it.id == AgentRuntimeLanguage.FFMPEG.requiredPack }
        ?: return "Install the signed FFmpeg runtime pack"
    return when (pack.state) {
        AgentRuntimePackState.READY -> pack.reason.ifBlank { "The FFmpeg runtime capability is not ready" }
        AgentRuntimePackState.NOT_INSTALLED -> "Install the signed FFmpeg runtime pack"
        AgentRuntimePackState.INVALID -> pack.reason.ifBlank { "Reinstall the signed FFmpeg runtime pack" }
        AgentRuntimePackState.INCOMPATIBLE -> pack.reason.ifBlank { "Install a compatible FFmpeg runtime pack" }
    }
}

object AgentUnavailableMediaTranscoder : AgentMediaTranscoder {
    override val availability = AgentNativeToolAvailability(
        AgentNativeToolAvailabilityStatus.UNAVAILABLE,
        "The on-device FFmpeg runtime is unavailable"
    )
    override val implementationId = "signalasi.media.ffmpeg.unavailable"

    override fun transcode(request: AgentMediaTranscodeRequest): AgentMediaTranscodeResult {
        throw AgentWebMediaException("ffmpeg_unavailable", availability.reason)
    }
}

internal data class AgentFfmpegTranscodePlan(
    val sourcePath: String,
    val destinationPath: String,
    val arguments: List<String>
)

internal object AgentFfmpegTranscodePlanner {
    fun create(request: AgentMediaTranscodeRequest, sourcePath: String, destinationPath: String): AgentFfmpegTranscodePlan {
        val source = AgentMediaWorkspacePaths.normalizeRelative(sourcePath, "source_path")
        val destination = AgentMediaWorkspacePaths.normalizeRelative(destinationPath, "destination_path")
        require(source != destination) { "Source and destination paths must be different" }
        require(destination.lowercase(Locale.ROOT).endsWith(".${request.targetFormat.extension}")) {
            "Destination extension must match ${request.targetFormat.wireValue}"
        }
        require(request.startMillis in 0L..MAX_MEDIA_TIME_MILLIS) { "Start time is outside the allowed range" }
        require(request.durationMillis in 0L..MAX_MEDIA_TIME_MILLIS) { "Duration is outside the allowed range" }
        require(request.maxWidth in 0..MAX_DIMENSION) { "Maximum width is outside the allowed range" }
        require(request.maxHeight in 0..MAX_DIMENSION) { "Maximum height is outside the allowed range" }
        require(request.audioBitrateKbps == 0 || request.audioBitrateKbps in MIN_AUDIO_BITRATE..MAX_AUDIO_BITRATE) {
            "Audio bitrate is outside the allowed range"
        }

        val arguments = mutableListOf("-hide_banner", "-loglevel", "error", "-y")
        if (request.startMillis > 0L) arguments += listOf("-ss", seconds(request.startMillis))
        arguments += listOf("-i", "./$source")
        if (request.durationMillis > 0L) arguments += listOf("-t", seconds(request.durationMillis))
        arguments += listOf("-map_metadata", "-1", "-sn", "-dn", "-threads", "2")

        when (request.targetFormat) {
            AgentMediaTargetFormat.MP4 -> addMp4(arguments, request)
            AgentMediaTargetFormat.M4A -> addM4a(arguments, request)
            AgentMediaTargetFormat.WAV -> arguments += listOf("-map", "0:a:0", "-vn", "-c:a", "pcm_s16le")
            AgentMediaTargetFormat.FLAC -> arguments += listOf(
                "-map", "0:a:0",
                "-vn",
                "-c:a", "flac",
                "-compression_level", when (request.preset) {
                    AgentMediaTranscodePreset.COMPACT -> "8"
                    AgentMediaTranscodePreset.BALANCED -> "5"
                    AgentMediaTranscodePreset.HIGH_QUALITY -> "8"
                }
            )
            AgentMediaTargetFormat.GIF -> addGif(arguments, request)
            AgentMediaTargetFormat.PNG -> addStillImage(arguments, request, "png", "2")
            AgentMediaTargetFormat.JPG -> addStillImage(
                arguments,
                request,
                "mjpeg",
                when (request.preset) {
                    AgentMediaTranscodePreset.COMPACT -> "7"
                    AgentMediaTranscodePreset.BALANCED -> "4"
                    AgentMediaTranscodePreset.HIGH_QUALITY -> "2"
                }
            )
        }
        arguments += "./$destination"
        return AgentFfmpegTranscodePlan(source, destination, arguments)
    }

    private fun addMp4(arguments: MutableList<String>, request: AgentMediaTranscodeRequest) {
        val (width, height) = effectiveVideoBounds(request)
        arguments += listOf(
            "-map", "0:v:0?",
            "-map", "0:a:0?",
            "-vf", videoScaleFilter(width, height, pixelFormat = "yuv420p"),
            "-c:v", "mpeg4",
            "-q:v", when (request.preset) {
                AgentMediaTranscodePreset.COMPACT -> "8"
                AgentMediaTranscodePreset.BALANCED -> "5"
                AgentMediaTranscodePreset.HIGH_QUALITY -> "2"
            },
            "-c:a", "aac",
            "-b:a", "${audioBitrate(request)}k",
            "-movflags", "+faststart"
        )
    }

    private fun addM4a(arguments: MutableList<String>, request: AgentMediaTranscodeRequest) {
        arguments += listOf(
            "-map", "0:a:0",
            "-vn",
            "-c:a", "aac",
            "-b:a", "${audioBitrate(request)}k",
            "-movflags", "+faststart"
        )
    }

    private fun addGif(arguments: MutableList<String>, request: AgentMediaTranscodeRequest) {
        val defaultBound = when (request.preset) {
            AgentMediaTranscodePreset.COMPACT -> 640
            AgentMediaTranscodePreset.BALANCED -> 960
            AgentMediaTranscodePreset.HIGH_QUALITY -> 1_280
        }
        val width = request.maxWidth.takeIf { it > 0 } ?: defaultBound
        val height = request.maxHeight.takeIf { it > 0 } ?: defaultBound
        val fps = when (request.preset) {
            AgentMediaTranscodePreset.COMPACT -> 8
            AgentMediaTranscodePreset.BALANCED -> 12
            AgentMediaTranscodePreset.HIGH_QUALITY -> 15
        }
        arguments += listOf(
            "-map", "0:v:0",
            "-an",
            "-vf", "fps=$fps,${videoScaleFilter(width, height)}",
            "-loop", "0"
        )
    }

    private fun addStillImage(
        arguments: MutableList<String>,
        request: AgentMediaTranscodeRequest,
        codec: String,
        quality: String
    ) {
        arguments += listOf("-map", "0:v:0", "-an", "-frames:v", "1", "-c:v", codec)
        imageScaleFilter(request.maxWidth, request.maxHeight)?.let { filter ->
            arguments += listOf("-vf", filter)
        }
        if (codec == "mjpeg") arguments += listOf("-q:v", quality)
    }

    private fun effectiveVideoBounds(request: AgentMediaTranscodeRequest): Pair<Int, Int> {
        if (request.maxWidth > 0 || request.maxHeight > 0) return request.maxWidth to request.maxHeight
        return when (request.preset) {
            AgentMediaTranscodePreset.COMPACT -> 1_280 to 720
            AgentMediaTranscodePreset.BALANCED -> 1_920 to 1_080
            AgentMediaTranscodePreset.HIGH_QUALITY -> 0 to 0
        }
    }

    private fun audioBitrate(request: AgentMediaTranscodeRequest): Int = request.audioBitrateKbps.takeIf { it > 0 }
        ?: when (request.preset) {
            AgentMediaTranscodePreset.COMPACT -> 96
            AgentMediaTranscodePreset.BALANCED -> 160
            AgentMediaTranscodePreset.HIGH_QUALITY -> 256
        }

    private fun videoScaleFilter(width: Int, height: Int, pixelFormat: String = ""): String {
        val scale = when {
            width > 0 && height > 0 ->
                "scale=w='min($width,iw)':h='min($height,ih)':force_original_aspect_ratio=decrease:force_divisible_by=2"
            width > 0 -> "scale=w='min($width,iw)':h=-2"
            height > 0 -> "scale=w=-2:h='min($height,ih)'"
            else -> "scale=w='trunc(iw/2)*2':h='trunc(ih/2)*2'"
        }
        return if (pixelFormat.isBlank()) scale else "$scale,format=$pixelFormat"
    }

    private fun imageScaleFilter(width: Int, height: Int): String? = when {
        width > 0 && height > 0 ->
            "scale=w='min($width,iw)':h='min($height,ih)':force_original_aspect_ratio=decrease"
        width > 0 -> "scale=w='min($width,iw)':h=-1"
        height > 0 -> "scale=w=-1:h='min($height,ih)'"
        else -> null
    }

    private fun seconds(milliseconds: Long): String {
        val whole = milliseconds / 1_000L
        val remainder = milliseconds % 1_000L
        if (remainder == 0L) return whole.toString()
        return "$whole.${remainder.toString().padStart(3, '0').trimEnd('0')}"
    }

    private const val MAX_MEDIA_TIME_MILLIS = 6L * 60L * 60L * 1_000L
    private const val MAX_DIMENSION = 8_192
    private const val MIN_AUDIO_BITRATE = 32
    private const val MAX_AUDIO_BITRATE = 512
}

internal object AgentMediaWorkspacePaths {
    fun normalizeRelative(value: String, field: String): String {
        val normalized = value.trim().replace('\\', '/')
        require(normalized.isNotBlank() && normalized.length <= MAX_PATH_CHARS && !File(normalized).isAbsolute) {
            "$field is invalid"
        }
        require(normalized.none { it.code < 32 || it == '\u007f' }) { "$field contains control characters" }
        val segments = normalized.split('/')
        require(segments.none { it.isBlank() || it == "." || it == ".." }) { "$field is unsafe" }
        require(segments.first().lowercase(Locale.ROOT) !in RESERVED_ROOT_NAMES) {
            "$field uses a reserved runtime path"
        }
        return normalized
    }

    private const val MAX_PATH_CHARS = 1_024
    private val RESERVED_ROOT_NAMES = setOf(
        ".signalasi-tools",
        ".tmp",
        "request.json",
        "status.json",
        "main.ffmpeg.json",
        "main.ffprobe.json"
    )
}

class AgentAndroidFfmpegMediaTranscoder(
    context: Context,
    private val runtimeManager: AgentOnDeviceRuntimeManager = AgentOnDeviceRuntimeManager(context)
) : AgentMediaTranscoder {
    private val appContext = context.applicationContext
    private val projectRoot = File(appContext.filesDir, "agent-native-workspaces")

    override val implementationId = "signalasi.android_runtime.ffmpeg"

    override val availability: AgentNativeToolAvailability
        get() = runCatching { runtimeManager.status() }.fold(
            onSuccess = { status ->
                if (status.languageReady(AgentRuntimeLanguage.FFMPEG)) {
                    AgentNativeToolAvailability.AVAILABLE
                } else {
                    AgentNativeToolAvailability(
                        AgentNativeToolAvailabilityStatus.REQUIRES_SETUP,
                        status.ffmpegSetupReason(),
                        System.currentTimeMillis()
                    )
                }
            },
            onFailure = { error ->
                AgentNativeToolAvailability(
                    AgentNativeToolAvailabilityStatus.REQUIRES_SETUP,
                    error.message ?: "The on-device FFmpeg runtime could not be inspected",
                    System.currentTimeMillis()
                )
            }
        )

    override fun transcode(request: AgentMediaTranscodeRequest): AgentMediaTranscodeResult {
        require(request.workspaceId.matches(WORKSPACE_ID_PATTERN)) { "Media workspace ID is invalid" }
        require(request.invocationId.isNotBlank()) { "Media invocation ID is required" }
        request.checkpoint()
        val currentAvailability = availability
        if (currentAvailability.status != AgentNativeToolAvailabilityStatus.AVAILABLE) {
            throw AgentWebMediaException("ffmpeg_requires_setup", currentAvailability.reason)
        }
        return AgentWorkspaceScope.withLock(request.workspaceId) {
            val workspace = openWorkspace(request.workspaceId)
            val sourcePath = if (request.contentUri.isNotBlank()) {
                importContentUri(workspace, request)
            } else {
                AgentMediaWorkspacePaths.normalizeRelative(request.sourcePath, "source_path")
            }
            val sourceFile = resolveWorkspaceFile(workspace, sourcePath)
            if (!Files.isRegularFile(sourceFile.toPath(), LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(sourceFile.toPath())) {
                throw AgentWebMediaException("media_source_not_found", "The selected media source is unavailable")
            }
            if (sourceFile.length() > MAX_SOURCE_BYTES) {
                throw AgentWebMediaException("media_source_too_large", "The selected media exceeds the 256 MB limit")
            }
            val destinationPath = request.destinationPath.takeIf(String::isNotBlank)
                ?.let { AgentMediaWorkspacePaths.normalizeRelative(it, "destination_path") }
                ?: defaultDestination(request)
            val destinationFile = resolveWorkspaceFile(workspace, destinationPath)
            if (sourceFile.canonicalFile == destinationFile.canonicalFile) {
                throw AgentWebMediaException("media_destination_conflict", "Source and destination must be different files")
            }
            if (destinationFile.exists() && Files.isSymbolicLink(destinationFile.toPath())) {
                throw AgentWebMediaException("unsafe_media_destination", "The media destination is unsafe")
            }
            val destinationParent = destinationFile.parentFile
                ?: throw AgentWebMediaException("invalid_media_destination", "The media destination is invalid")
            check(destinationParent.mkdirs() || destinationParent.isDirectory) {
                "Media output directory is unavailable"
            }
            val plan = runCatching {
                AgentFfmpegTranscodePlanner.create(request, sourcePath, destinationPath)
            }.getOrElse { error ->
                throw AgentWebMediaException("invalid_transcode_request", error.message ?: "Media conversion request is invalid")
            }
            request.progressListener(
                AgentRuntimeProgress(runtimeRequestId(request), 0L, "prepare", "Preparing local media conversion", 0)
            )
            val response = try {
                runtimeManager.execute(
                    AgentRuntimeExecutionRequest(
                        language = AgentRuntimeLanguage.FFMPEG,
                        source = operationManifest(request, plan),
                        arguments = plan.arguments,
                        timeoutMillis = request.timeoutMillis,
                        networkEnabled = false,
                        artifactPaths = listOf(plan.destinationPath),
                        workspaceId = request.workspaceId,
                        requestId = runtimeRequestId(request),
                        resourceLimits = AgentRuntimeResourceLimits(
                            wallClockMillis = request.timeoutMillis,
                            cpuMillis = (request.timeoutMillis * 9L / 10L).coerceAtLeast(100L),
                            memoryBytes = 768L * 1024L * 1024L,
                            diskBytes = 768L * 1024L * 1024L,
                            maxProcesses = 32,
                            maxOutputBytes = 512L * 1024L,
                            maxArtifactBytes = MAX_ARTIFACT_BYTES
                        ),
                        cancellationToken = request.cancellationToken,
                        progressListener = request.progressListener
                    )
                )
            } catch (error: AgentNativeToolCancelledException) {
                throw error
            } catch (error: AgentNativeToolTimeoutException) {
                throw error
            } catch (error: AgentWebMediaException) {
                throw error
            } catch (error: Throwable) {
                throw AgentWebMediaException(
                    "ffmpeg_runtime_failed",
                    "The on-device FFmpeg runtime could not complete the conversion: " +
                        (error.message ?: "runtime unavailable").take(MAX_FRIENDLY_DIAGNOSTIC_CHARS),
                    cause = error
                )
            }
            if (response.exitCode != 0) {
                throw AgentWebMediaException(
                    "ffmpeg_transcode_failed",
                    friendlyFailure(response.stderr),
                    details = linkedMapOf(
                        "exit_code" to response.exitCode,
                        "diagnostic" to response.stderr.take(MAX_DIAGNOSTIC_CHARS),
                        "execution_receipt" to response.executionReceipt?.toEvidenceMap()
                    )
                )
            }
            val outputFile = resolveWorkspaceFile(workspace, plan.destinationPath)
            if (!Files.isRegularFile(outputFile.toPath(), LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(outputFile.toPath())) {
                throw AgentWebMediaException("ffmpeg_output_missing", "FFmpeg completed without producing the requested media file")
            }
            if (outputFile.length() > MAX_ARTIFACT_BYTES) {
                throw AgentWebMediaException("ffmpeg_output_too_large", "Converted media exceeds the 256 MB output limit")
            }
            val artifact = response.artifacts.firstOrNull {
                it["relative_path"]?.toString() == plan.destinationPath
            }
            val artifacts = response.artifacts.map { LinkedHashMap(it) }
            AgentMediaTranscodeResult(
                sourcePath = plan.sourcePath,
                destinationPath = plan.destinationPath,
                targetFormat = request.targetFormat,
                sizeBytes = (artifact?.get("size_bytes") as? Number)?.toLong() ?: outputFile.length(),
                sha256 = artifact?.get("sha256")?.toString().orEmpty().ifBlank { sha256File(outputFile) },
                executionDurationMillis = response.durationMillis,
                artifacts = artifacts,
                executionReceipt = response.executionReceipt?.toEvidenceMap().orEmpty()
            )
        }
    }

    private fun openWorkspace(workspaceId: String): File {
        val canonicalRoot = projectRoot.canonicalFile
        check(canonicalRoot.mkdirs() || canonicalRoot.isDirectory) { "Agent project storage is unavailable" }
        val direct = File(canonicalRoot, workspaceId)
        if (direct.exists() && Files.isSymbolicLink(direct.toPath())) {
            throw AgentWebMediaException("unsafe_media_workspace", "The media workspace is unsafe")
        }
        val workspace = direct.canonicalFile
        require(workspace.path.startsWith(canonicalRoot.path + File.separator)) { "Media workspace path is unsafe" }
        check(workspace.mkdirs() || workspace.isDirectory) { "Media workspace is unavailable" }
        return workspace
    }

    private fun resolveWorkspaceFile(workspace: File, relativePath: String): File {
        val relative = AgentMediaWorkspacePaths.normalizeRelative(relativePath, "path")
        val candidate = File(workspace, relative).canonicalFile
        require(candidate.path.startsWith(workspace.canonicalPath + File.separator)) { "Media path escapes its workspace" }
        return candidate
    }

    private fun importContentUri(workspace: File, request: AgentMediaTranscodeRequest): String {
        val uri = Uri.parse(request.contentUri.trim())
        if (!uri.scheme.equals("content", ignoreCase = true) || uri.authority.isNullOrBlank()) {
            throw AgentWebMediaException("content_uri_required", "A user-authorized content:// URI is required")
        }
        val displayName = appContext.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
            .orEmpty()
        val extension = safeExtension(displayName).ifBlank {
            extensionForMimeType(appContext.contentResolver.getType(uri))
        }.ifBlank { "bin" }
        val relative = "inputs/media/${runtimeRequestId(request)}.$extension"
        val target = resolveWorkspaceFile(workspace, relative)
        val targetParent = target.parentFile
            ?: throw AgentWebMediaException("invalid_media_source", "The media input path is invalid")
        check(targetParent.mkdirs() || targetParent.isDirectory) {
            "Media input directory is unavailable"
        }
        val temporary = File(targetParent, ".${target.name}.part")
        var total = 0L
        try {
            appContext.contentResolver.openAssetFileDescriptor(uri, "r")?.use { descriptor ->
                if (descriptor.length > MAX_SOURCE_BYTES) {
                    throw AgentWebMediaException("media_source_too_large", "The selected media exceeds the 256 MB limit")
                }
            }
            val input = appContext.contentResolver.openInputStream(uri)
                ?: throw AgentWebMediaException("content_uri_unavailable", "The selected media cannot be opened")
            input.buffered().use { source ->
                temporary.outputStream().buffered().use { destination ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        request.checkpoint()
                        if (request.cancellationToken.isCancellationRequested) throw AgentNativeToolCancelledException()
                        val read = source.read(buffer)
                        if (read < 0) break
                        total += read
                        if (total > MAX_SOURCE_BYTES) {
                            throw AgentWebMediaException("media_source_too_large", "The selected media exceeds the 256 MB limit")
                        }
                        destination.write(buffer, 0, read)
                    }
                }
            }
            if (target.exists() && !target.delete()) error("Previous media input could not be replaced")
            check(temporary.renameTo(target)) { "Selected media could not be committed" }
        } finally {
            temporary.delete()
        }
        return relative
    }

    private fun defaultDestination(request: AgentMediaTranscodeRequest): String =
        "outputs/media-${sha256(request.invocationId.toByteArray()).take(16)}.${request.targetFormat.extension}"

    private fun runtimeRequestId(request: AgentMediaTranscodeRequest): String =
        "media-${sha256(request.invocationId.toByteArray()).take(32)}"

    private fun operationManifest(
        request: AgentMediaTranscodeRequest,
        plan: AgentFfmpegTranscodePlan
    ): String = JSONObject()
        .put("operation", "media_transcode")
        .put("source_path", plan.sourcePath)
        .put("destination_path", plan.destinationPath)
        .put("target_format", request.targetFormat.wireValue)
        .put("preset", request.preset.wireValue)
        .put("network_enabled", false)
        .toString()

    private fun friendlyFailure(stderr: String): String {
        val diagnostic = stderr.lineSequence().map(String::trim).filter(String::isNotBlank).lastOrNull().orEmpty()
        return if (diagnostic.isBlank()) {
            "FFmpeg could not convert the selected media"
        } else {
            "FFmpeg could not convert the selected media: ${diagnostic.take(MAX_FRIENDLY_DIAGNOSTIC_CHARS)}"
        }
    }

    private fun safeExtension(displayName: String): String = displayName.substringAfterLast('.', "")
        .lowercase(Locale.ROOT)
        .takeIf { it.matches(Regex("[a-z0-9]{1,10}")) }
        .orEmpty()

    private fun extensionForMimeType(mimeType: String?): String = when (mimeType?.lowercase(Locale.ROOT)) {
        "video/mp4" -> "mp4"
        "audio/mp4", "audio/x-m4a" -> "m4a"
        "audio/wav", "audio/x-wav" -> "wav"
        "audio/flac" -> "flac"
        "image/gif" -> "gif"
        "image/png" -> "png"
        "image/jpeg" -> "jpg"
        else -> ""
    }

    private fun sha256File(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(Locale.ROOT, it.toInt() and 0xff) }
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { "%02x".format(Locale.ROOT, it.toInt() and 0xff) }

    companion object {
        private const val MAX_SOURCE_BYTES = 256L * 1024L * 1024L
        private const val MAX_ARTIFACT_BYTES = 256L * 1024L * 1024L
        private const val MAX_DIAGNOSTIC_CHARS = 2_000
        private const val MAX_FRIENDLY_DIAGNOSTIC_CHARS = 320
        private val WORKSPACE_ID_PATTERN = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,63}")
    }
}
