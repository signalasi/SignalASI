package com.signalasi.chat

import java.net.URI
import java.util.Locale

enum class AgentRichFormatFamily {
    TEXT,
    CODE,
    STRUCTURED_DATA,
    TABLE,
    IMAGE,
    VIDEO,
    AUDIO,
    DOCUMENT,
    ARCHIVE,
    WEB,
    INTERACTIVE,
    UNKNOWN
}

data class AgentRichFormatDescriptor(
    val family: AgentRichFormatFamily,
    val label: String,
    val extension: String = "",
    val canPreviewInline: Boolean = false,
    val canOpenExternally: Boolean = true
)

/**
 * Central capability registry for rich output. The renderer consumes format families instead of
 * scattering extension checks across individual views, so new formats have a stable fallback.
 */
object AgentRichFormatRegistry {
    fun describe(block: AgentRichBlock): AgentRichFormatDescriptor = when (block.type) {
        AgentRichBlockType.TEXT, AgentRichBlockType.HEADING, AgentRichBlockType.QUOTE,
        AgentRichBlockType.LIST, AgentRichBlockType.NOTICE -> descriptor(AgentRichFormatFamily.TEXT, "Text", block)
        AgentRichBlockType.CODE, AgentRichBlockType.DIFF -> descriptor(AgentRichFormatFamily.CODE, "Code", block)
        AgentRichBlockType.JSON, AgentRichBlockType.KEY_VALUE, AgentRichBlockType.METRIC,
        AgentRichBlockType.PROGRESS, AgentRichBlockType.STATUS, AgentRichBlockType.TOOL,
        AgentRichBlockType.TIMELINE -> descriptor(AgentRichFormatFamily.STRUCTURED_DATA, "Data", block)
        AgentRichBlockType.TABLE, AgentRichBlockType.CHART -> descriptor(AgentRichFormatFamily.TABLE, "Table", block)
        AgentRichBlockType.IMAGE, AgentRichBlockType.GALLERY -> descriptor(AgentRichFormatFamily.IMAGE, "Image", block, true)
        AgentRichBlockType.VIDEO -> descriptor(AgentRichFormatFamily.VIDEO, "Video", block, true)
        AgentRichBlockType.AUDIO -> descriptor(AgentRichFormatFamily.AUDIO, "Audio", block, true)
        AgentRichBlockType.LINK, AgentRichBlockType.CITATION, AgentRichBlockType.WEBPAGE ->
            descriptor(AgentRichFormatFamily.WEB, "Link", block, block.type == AgentRichBlockType.WEBPAGE)
        AgentRichBlockType.HTML, AgentRichBlockType.ACTIONS, AgentRichBlockType.APPROVAL,
        AgentRichBlockType.FORM -> descriptor(AgentRichFormatFamily.INTERACTIVE, "Interactive", block, true)
        AgentRichBlockType.FILE, AgentRichBlockType.UNKNOWN -> describe(block.mimeType, block.uri, block.language)
        AgentRichBlockType.DIVIDER -> descriptor(
            AgentRichFormatFamily.TEXT, "Divider", block, canPreviewInline = true, canOpenExternally = false
        )
    }

    fun describe(mimeType: String, uri: String, language: String = ""): AgentRichFormatDescriptor {
        val mime = mimeType.substringBefore(';').trim().lowercase(Locale.ROOT)
        val extension = extensionOf(uri)
        val family = when {
            mime.startsWith("image/") || extension in IMAGE_EXTENSIONS -> AgentRichFormatFamily.IMAGE
            mime.startsWith("video/") || extension in VIDEO_EXTENSIONS -> AgentRichFormatFamily.VIDEO
            mime.startsWith("audio/") || extension in AUDIO_EXTENSIONS -> AgentRichFormatFamily.AUDIO
            mime in STRUCTURED_MIME_TYPES || extension in STRUCTURED_EXTENSIONS -> AgentRichFormatFamily.STRUCTURED_DATA
            mime.startsWith("text/") || language.isNotBlank() || extension in CODE_EXTENSIONS -> AgentRichFormatFamily.CODE
            mime in ARCHIVE_MIME_TYPES || extension in ARCHIVE_EXTENSIONS -> AgentRichFormatFamily.ARCHIVE
            mime in DOCUMENT_MIME_TYPES || extension in DOCUMENT_EXTENSIONS -> AgentRichFormatFamily.DOCUMENT
            mime == "text/html" || extension in WEB_EXTENSIONS -> AgentRichFormatFamily.WEB
            else -> AgentRichFormatFamily.UNKNOWN
        }
        val label = when (family) {
            AgentRichFormatFamily.IMAGE -> "Image"
            AgentRichFormatFamily.VIDEO -> "Video"
            AgentRichFormatFamily.AUDIO -> "Audio"
            AgentRichFormatFamily.CODE -> language.ifBlank { extension.removePrefix(".").uppercase(Locale.ROOT) }.ifBlank { "Text" }
            AgentRichFormatFamily.STRUCTURED_DATA -> extension.removePrefix(".").uppercase(Locale.ROOT).ifBlank { "Data" }
            AgentRichFormatFamily.DOCUMENT -> extension.removePrefix(".").uppercase(Locale.ROOT).ifBlank { "Document" }
            AgentRichFormatFamily.ARCHIVE -> extension.removePrefix(".").uppercase(Locale.ROOT).ifBlank { "Archive" }
            AgentRichFormatFamily.WEB -> "Web"
            AgentRichFormatFamily.TABLE -> "Table"
            AgentRichFormatFamily.INTERACTIVE -> "Interactive"
            AgentRichFormatFamily.TEXT -> "Text"
            AgentRichFormatFamily.UNKNOWN -> extension.removePrefix(".").uppercase(Locale.ROOT).ifBlank { "File" }
        }
        return AgentRichFormatDescriptor(
            family = family,
            label = label,
            extension = extension,
            canPreviewInline = family in INLINE_FAMILIES,
            canOpenExternally = uri.isNotBlank()
        )
    }

    fun normalizedType(
        declaredType: AgentRichBlockType,
        uri: String,
        mimeType: String,
        language: String = ""
    ): AgentRichBlockType {
        if (declaredType !in setOf(
                AgentRichBlockType.FILE,
                AgentRichBlockType.UNKNOWN,
                AgentRichBlockType.WEBPAGE
            )
        ) return declaredType
        return when (describe(mimeType, uri, language).family) {
            AgentRichFormatFamily.IMAGE -> AgentRichBlockType.IMAGE
            AgentRichFormatFamily.VIDEO -> AgentRichBlockType.VIDEO
            AgentRichFormatFamily.AUDIO -> AgentRichBlockType.AUDIO
            AgentRichFormatFamily.WEB -> if (declaredType == AgentRichBlockType.WEBPAGE) {
                AgentRichBlockType.WEBPAGE
            } else declaredType
            else -> declaredType
        }
    }

    fun fileName(block: AgentRichBlock): String = block.title.ifBlank {
        runCatching {
            URI(block.uri).path.orEmpty().substringAfterLast('/').ifBlank { block.uri.substringAfterLast('/') }
        }.getOrDefault(block.uri.substringAfterLast('/')).substringBefore('?')
    }.ifBlank { describe(block).label }

    private fun descriptor(
        family: AgentRichFormatFamily,
        label: String,
        block: AgentRichBlock,
        canPreviewInline: Boolean = true,
        canOpenExternally: Boolean = block.uri.isNotBlank()
    ) = AgentRichFormatDescriptor(
        family = family,
        label = label,
        extension = extensionOf(block.uri),
        canPreviewInline = canPreviewInline,
        canOpenExternally = canOpenExternally
    )

    private fun extensionOf(value: String): String {
        val path = runCatching { URI(value).path.orEmpty() }.getOrDefault(value.substringBefore('?'))
        val name = path.substringAfterLast('/')
        val suffix = name.substringAfterLast('.', missingDelimiterValue = "")
        return if (suffix.isBlank() || suffix.length > 12) "" else ".${suffix.lowercase(Locale.ROOT)}"
    }

    private val INLINE_FAMILIES = setOf(
        AgentRichFormatFamily.TEXT,
        AgentRichFormatFamily.CODE,
        AgentRichFormatFamily.STRUCTURED_DATA,
        AgentRichFormatFamily.TABLE,
        AgentRichFormatFamily.IMAGE,
        AgentRichFormatFamily.VIDEO,
        AgentRichFormatFamily.AUDIO,
        AgentRichFormatFamily.WEB,
        AgentRichFormatFamily.INTERACTIVE
    )
    private val IMAGE_EXTENSIONS = setOf(".png", ".jpg", ".jpeg", ".gif", ".webp", ".avif", ".bmp", ".heic", ".heif")
    private val VIDEO_EXTENSIONS = setOf(".mp4", ".m4v", ".webm", ".mkv", ".mov", ".avi", ".3gp")
    private val AUDIO_EXTENSIONS = setOf(".mp3", ".m4a", ".aac", ".wav", ".ogg", ".opus", ".flac", ".amr")
    private val STRUCTURED_EXTENSIONS = setOf(".json", ".jsonl", ".yaml", ".yml", ".xml", ".toml")
    private val CODE_EXTENSIONS = setOf(
        ".txt", ".md", ".markdown", ".kt", ".kts", ".java", ".js", ".jsx", ".ts", ".tsx",
        ".py", ".go", ".rs", ".swift", ".c", ".h", ".cpp", ".hpp", ".cs", ".rb", ".php",
        ".sh", ".ps1", ".bat", ".sql", ".css", ".scss", ".html", ".htm", ".svg", ".log", ".diff", ".patch"
    )
    private val DOCUMENT_EXTENSIONS = setOf(
        ".pdf", ".doc", ".docx", ".xls", ".xlsx", ".csv", ".tsv", ".ppt", ".pptx",
        ".odt", ".ods", ".odp", ".rtf", ".epub"
    )
    private val ARCHIVE_EXTENSIONS = setOf(".zip", ".7z", ".rar", ".tar", ".gz", ".bz2", ".xz", ".apk")
    private val WEB_EXTENSIONS = setOf(".html", ".htm")
    private val STRUCTURED_MIME_TYPES = setOf("application/json", "application/ld+json", "application/xml", "text/xml", "application/yaml", "text/yaml")
    private val ARCHIVE_MIME_TYPES = setOf("application/zip", "application/x-7z-compressed", "application/vnd.rar", "application/gzip", "application/x-tar")
    private val DOCUMENT_MIME_TYPES = setOf(
        "application/pdf",
        "application/msword",
        "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
        "application/vnd.ms-excel",
        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
        "application/vnd.ms-powerpoint",
        "application/vnd.openxmlformats-officedocument.presentationml.presentation",
        "text/csv"
    )
}
