package com.signalasi.chat

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.Locale
import java.util.zip.ZipFile

internal data class AgentRuntimeArtifactActionPayload(
    val hostPath: String,
    val displayName: String,
    val mimeType: String,
    val sha256: String,
    val sizeBytes: Long,
    val kind: String
) {
    fun encode(): String = JSONObject()
        .put("host_path", hostPath)
        .put("display_name", displayName)
        .put("mime_type", mimeType)
        .put("sha256", sha256)
        .put("size_bytes", sizeBytes)
        .put("kind", kind)
        .toString()

    companion object {
        fun decode(raw: String): AgentRuntimeArtifactActionPayload? = runCatching {
            val value = JSONObject(raw)
            AgentRuntimeArtifactActionPayload(
                hostPath = value.getString("host_path"),
                displayName = value.getString("display_name"),
                mimeType = value.optString("mime_type", "application/octet-stream"),
                sha256 = value.getString("sha256").lowercase(Locale.ROOT),
                sizeBytes = value.getLong("size_bytes"),
                kind = value.optString("kind", "file")
            )
        }.getOrNull()
    }
}

internal object AgentRuntimeArtifactUi {
    fun richOutput(
        output: AgentNativeJsonObject,
        responseText: String,
        preferredFileName: String,
        zh: Boolean
    ): String {
        val artifact = (output["artifacts"] as? Iterable<*>)
            ?.mapNotNull { it as? Map<*, *> }
            ?.firstOrNull { item ->
                item["relative_path"]?.toString() == preferredFileName ||
                    item["artifact_kind"]?.toString() == "project_archive"
            }
            ?: return ""
        val hostPath = artifact["host_path"]?.toString().orEmpty()
        val relativePath = artifact["relative_path"]?.toString().orEmpty()
        val sha256 = artifact["sha256"]?.toString().orEmpty()
        val sizeBytes = (artifact["size_bytes"] as? Number)?.toLong() ?: 0L
        if (hostPath.isBlank() || relativePath.isBlank() || sha256.isBlank() || sizeBytes < 0L) return ""
        val kind = artifact["artifact_kind"]?.toString().orEmpty().ifBlank { "file" }
        val mimeType = mimeType(relativePath)
        val payload = AgentRuntimeArtifactActionPayload(
            hostPath = hostPath,
            displayName = File(relativePath).name,
            mimeType = mimeType,
            sha256 = sha256,
            sizeBytes = sizeBytes,
            kind = kind
        )
        val fileCount = (artifact["file_count"] as? Number)?.toInt() ?: 0
        val detail = when {
            kind == "project_archive" && zh -> "${fileCount.coerceAtLeast(1)} \u4e2a\u6587\u4ef6 · ${humanSize(sizeBytes)}"
            kind == "project_archive" -> "${fileCount.coerceAtLeast(1)} files · ${humanSize(sizeBytes)}"
            else -> "${formatLabel(relativePath)} · ${humanSize(sizeBytes)}"
        }
        val artifactBlock = AgentRichBlock(
            id = "runtime-artifact:${sha256.take(24)}",
            type = AgentRichBlockType.FILE,
            title = payload.displayName,
            uri = File(hostPath).toURI().toString(),
            mimeType = mimeType,
            language = language(relativePath),
            fallbackText = detail,
            actions = buildList {
                if (isPreviewable(relativePath)) {
                    add(AgentRichAction("preview", if (zh) "\u67e5\u770b" else "View", "preview_runtime_artifact", payload.encode()))
                }
                add(AgentRichAction("save", if (zh) "\u4fdd\u5b58" else "Save", "save_runtime_artifact", payload.encode(), "primary"))
            },
            metadata = mapOf(
                "runtime_artifact" to "true",
                "artifact_kind" to kind,
                "size" to humanSize(sizeBytes),
                "detail" to detail,
                "file_count" to fileCount.toString()
            )
        )
        val blocks = AgentRichContentCodec.fromText(responseText).toMutableList()
        blocks.add(minOf(1, blocks.size), artifactBlock)
        return AgentRichContentCodec.encode(blocks)
    }

    fun resolve(context: Context, payload: AgentRuntimeArtifactActionPayload): Result<File> = runCatching {
        require(payload.displayName.isNotBlank() && payload.sha256.matches(Regex("[a-f0-9]{64}"))) {
            "Runtime artifact metadata is invalid"
        }
        val candidate = File(payload.hostPath).canonicalFile
        val roots = listOf(
            File(context.filesDir, "agent-native-workspaces").canonicalFile,
            File(context.filesDir, "agent-runtime/exports").canonicalFile
        )
        require(roots.any { root -> candidate.path.startsWith(root.path + File.separator) }) {
            "Runtime artifact is outside the managed workspace"
        }
        require(candidate.isFile && candidate.length() == payload.sizeBytes) { "Runtime artifact is unavailable" }
        require(sha256(candidate) == payload.sha256) { "Runtime artifact integrity check failed" }
        candidate
    }

    fun preview(file: File, maximumBytes: Int = MAX_PREVIEW_BYTES): Result<String> = runCatching {
        if (file.extension.equals("zip", ignoreCase = true)) {
            ZipFile(file).use { zip ->
                val entries = zip.entries().asSequence().filterNot { it.isDirectory }.take(MAX_ZIP_PREVIEW_ENTRIES).toList()
                buildString {
                    entries.forEach { entry ->
                        append(entry.name).append("  ").append(humanSize(entry.size.coerceAtLeast(0L))).append('\n')
                    }
                    if (zip.size() > entries.size) append("… +").append(zip.size() - entries.size).append(" files")
                }.trim()
            }
        } else {
            require(file.length() <= maximumBytes) { "Runtime source is too large to preview" }
            file.readText(Charsets.UTF_8)
        }
    }

    fun isCodeFile(file: File): Boolean = file.extension.lowercase(Locale.ROOT) in CODE_EXTENSIONS

    private fun isPreviewable(path: String): Boolean =
        path.substringAfterLast('.', "").lowercase(Locale.ROOT) in PREVIEW_EXTENSIONS

    private fun mimeType(path: String): String = when (path.substringAfterLast('.', "").lowercase(Locale.ROOT)) {
        "py" -> "text/x-python"
        "js" -> "text/javascript"
        "ts" -> "text/typescript"
        "json" -> "application/json"
        "md" -> "text/markdown"
        "html", "htm" -> "text/html"
        "css" -> "text/css"
        "zip" -> "application/zip"
        else -> "text/plain"
    }

    private fun language(path: String): String = path.substringAfterLast('.', "").lowercase(Locale.ROOT)

    private fun formatLabel(path: String): String = when (path.substringAfterLast('.', "").lowercase(Locale.ROOT)) {
        "py" -> "Python"
        "js" -> "JavaScript"
        "ts" -> "TypeScript"
        "json" -> "JSON"
        "md" -> "Markdown"
        else -> "Source"
    }

    private fun humanSize(bytes: Long): String = when {
        bytes < 1_024L -> "$bytes B"
        bytes < 1_024L * 1_024L -> String.format(Locale.ROOT, "%.1f KB", bytes / 1_024.0)
        else -> String.format(Locale.ROOT, "%.1f MB", bytes / (1_024.0 * 1_024.0))
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(64 * 1_024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private const val MAX_PREVIEW_BYTES = 1024 * 1024
    private const val MAX_ZIP_PREVIEW_ENTRIES = 500
    private val CODE_EXTENSIONS = setOf("py", "js", "ts", "java", "kt", "kts", "c", "h", "cpp", "hpp", "rs", "go", "sh")
    private val PREVIEW_EXTENSIONS = CODE_EXTENSIONS + setOf("txt", "md", "json", "yaml", "yml", "xml", "html", "htm", "css", "toml", "ini", "zip")
}

internal class AgentRuntimeArtifactExporter(private val context: Context) {
    fun saveToDownloads(source: File, payload: AgentRuntimeArtifactActionPayload): Result<String> = runCatching {
        require(Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) { "System Downloads requires Android 10 or newer" }
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, payload.displayName)
            put(MediaStore.Downloads.MIME_TYPE, payload.mimeType)
            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/SignalASI")
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: error("Download destination could not be created")
        try {
            resolver.openOutputStream(uri, "w")?.use { output -> source.inputStream().buffered().use { it.copyTo(output) } }
                ?: error("Download destination could not be opened")
            resolver.update(uri, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null)
        } catch (error: Throwable) {
            resolver.delete(uri, null, null)
            throw error
        }
        "${Environment.DIRECTORY_DOWNLOADS}/SignalASI/${payload.displayName}"
    }

    fun copyToUri(source: File, destination: Uri): Result<Unit> = runCatching {
        context.contentResolver.openOutputStream(destination, "w")?.use { output ->
            source.inputStream().buffered().use { it.copyTo(output) }
        } ?: error("Export destination could not be opened")
    }
}
