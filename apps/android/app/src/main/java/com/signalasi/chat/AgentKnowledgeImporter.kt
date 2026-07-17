package com.signalasi.chat

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.text.Html
import android.util.Xml
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import org.xmlpull.v1.XmlPullParser
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.StringReader
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URI
import java.net.URL
import java.nio.charset.Charset
import java.util.Locale
import java.util.UUID
import java.util.zip.ZipInputStream

data class AgentKnowledgeImportResult(
    val success: Boolean,
    val title: String,
    val source: String,
    val mimeType: String,
    val byteCount: Int,
    val characterCount: Int,
    val chunkCount: Int,
    val truncated: Boolean,
    val message: String,
    val sensitiveFlags: List<String> = emptyList()
)

class AgentKnowledgeImporter(
    context: Context,
    private val store: AgentKnowledgeStore = SharedPreferencesAgentKnowledgeStore(context)
) {
    private val appContext = context.applicationContext
    private val resolver = appContext.contentResolver

    fun importDocument(uri: Uri): AgentKnowledgeImportResult {
        val source = uri.toString()
        val metadata = documentMetadata(uri)
        val mimeType = resolver.getType(uri).orEmpty().lowercase(Locale.US)
        if (metadata.size > MAX_SOURCE_BYTES) {
            return failure(metadata.name, source, mimeType, "Document exceeds the 20 MB import limit")
        }
        return runCatching {
            val bytes = readDocumentBytes(uri)
            val extracted = extractText(uri, metadata.name, mimeType, bytes)
            indexExtractedContent(
                title = metadata.name,
                source = source,
                mimeType = mimeType,
                byteCount = bytes.size,
                extracted = extracted,
                sourceTags = listOf("file", metadata.name.substringAfterLast('.', "").lowercase(Locale.US))
            )
        }.getOrElse { error ->
            failure(
                title = metadata.name,
                source = source,
                mimeType = mimeType,
                message = error.message?.take(240) ?: "Knowledge import failed"
            )
        }
    }

    fun importWebPage(value: String): AgentKnowledgeImportResult {
        val normalizedUrl = normalizeWebUrl(value)
            ?: return failure("Web page", value, "text/html", "Only HTTP and HTTPS web pages can be imported")
        return runCatching {
            val response = downloadWebPage(normalizedUrl)
            val title = extractHtmlTitle(response.body).ifBlank {
                URI(normalizedUrl).host?.ifBlank { "Web page" } ?: "Web page"
            }.take(180)
            val extracted = extractHtml(response.body.toByteArray(Charsets.UTF_8))
            indexExtractedContent(
                title = title,
                source = normalizedUrl,
                mimeType = response.contentType,
                byteCount = response.byteCount,
                extracted = extracted,
                sourceTags = listOf("web", URI(normalizedUrl).host.orEmpty())
            )
        }.getOrElse { error ->
            failure(
                title = "Web page",
                source = normalizedUrl,
                mimeType = "text/html",
                message = error.message?.take(240) ?: "Web page import failed"
            )
        }
    }

    private fun indexExtractedContent(
        title: String,
        source: String,
        mimeType: String,
        byteCount: Int,
        extracted: String,
        sourceTags: List<String>
    ): AgentKnowledgeImportResult {
        val normalized = normalizeText(extracted)
        if (normalized.isBlank()) {
            return failure(title, source, mimeType, "No readable text was found in this source")
        }
        val sensitiveFlags = sensitiveContentFlags(normalized)
        if (sensitiveFlags.isNotEmpty()) {
            return AgentKnowledgeImportResult(
                success = false,
                title = title,
                source = source,
                mimeType = mimeType,
                byteCount = byteCount,
                characterCount = normalized.length,
                chunkCount = 0,
                truncated = false,
                message = "Import blocked because the source appears to contain secrets",
                sensitiveFlags = sensitiveFlags
            )
        }
        val truncated = normalized.length > MAX_EXTRACTED_CHARACTERS
        val indexedText = normalized.take(MAX_EXTRACTED_CHARACTERS)
        val chunks = chunkText(indexedText)
        val importedAt = System.currentTimeMillis()
        val documentSummary = indexedText
            .replace(Regex("\\s+"), " ")
            .trim()
            .take(480)
        val semanticTags = AgentKnowledgeTextAnalyzer.tokens("$title ${indexedText.take(2_000)}")
            .filter { it.length in 2..40 }
            .take(12)
        val items = chunks.mapIndexed { index, chunk ->
            AgentKnowledgeItem(
                id = UUID.nameUUIDFromBytes("$source#$index".toByteArray(Charsets.UTF_8)).toString(),
                kind = AgentKnowledgeKind.DOCUMENT,
                title = if (chunks.size == 1) title else "$title [${index + 1}/${chunks.size}]",
                content = chunk,
                source = source,
                tags = listOf("import", mimeType)
                    .plus(sourceTags)
                    .plus(semanticTags)
                    .filter { it.isNotBlank() }
                    .distinct(),
                summary = documentSummary,
                chunkIndex = index,
                chunkCount = chunks.size,
                updatedAtMillis = importedAt + index
            )
        }
        store.replaceSource(source, items)
        return AgentKnowledgeImportResult(
            success = true,
            title = title,
            source = source,
            mimeType = mimeType,
            byteCount = byteCount,
            characterCount = indexedText.length,
            chunkCount = items.size,
            truncated = truncated,
            message = buildString {
                append("Imported ").append(title)
                append(" as ").append(items.size).append(" knowledge chunks")
                if (truncated) append("; content truncated to ").append(MAX_EXTRACTED_CHARACTERS).append(" characters")
            }
        )
    }

    private fun normalizeWebUrl(value: String): String? {
        val raw = value.trim()
        if (raw.isBlank()) return null
        val candidate = if (raw.startsWith("http://", ignoreCase = true) || raw.startsWith("https://", ignoreCase = true)) {
            raw
        } else {
            "https://$raw"
        }
        return runCatching {
            val uri = URI(candidate)
            if ((uri.scheme == "http" || uri.scheme == "https") && !uri.host.isNullOrBlank()) uri.toString() else null
        }.getOrNull()
    }

    private fun downloadWebPage(initialUrl: String): WebPageResponse {
        var currentUrl = initialUrl
        repeat(MAX_REDIRECTS + 1) { redirectCount ->
            validatePublicWebUrl(currentUrl)
            val connection = (URL(currentUrl).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 8_000
                readTimeout = 12_000
                instanceFollowRedirects = false
                setRequestProperty("Accept", "text/html,application/xhtml+xml,text/plain;q=0.9")
                setRequestProperty("User-Agent", "SignalASI-Knowledge/0.1")
            }
            try {
                val code = connection.responseCode
                if (code in 300..399) {
                    if (redirectCount >= MAX_REDIRECTS) throw IllegalArgumentException("Too many web page redirects")
                    val location = connection.getHeaderField("Location")
                        ?: throw IllegalArgumentException("Web page redirect is missing a location")
                    currentUrl = URL(URL(currentUrl), location).toString()
                    return@repeat
                }
                if (code !in 200..299) throw IllegalArgumentException("Web page returned HTTP $code")
                val contentTypeHeader = connection.contentType.orEmpty()
                val mimeType = contentTypeHeader.substringBefore(';').trim().lowercase(Locale.US).ifBlank { "text/html" }
                if (mimeType !in WEB_MIME_TYPES) {
                    throw IllegalArgumentException("Unsupported web content type: ${mimeType.ifBlank { "unknown" }}")
                }
                val bytes = ByteArrayOutputStream().also { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    connection.inputStream.use { input ->
                        while (true) {
                            val read = input.read(buffer)
                            if (read <= 0) break
                            if (output.size() + read > MAX_WEB_BYTES) {
                                throw IllegalArgumentException("Web page exceeds the 5 MB import limit")
                            }
                            output.write(buffer, 0, read)
                        }
                    }
                }.toByteArray()
                val charsetName = Regex("charset=([^;\\s]+)", RegexOption.IGNORE_CASE)
                    .find(contentTypeHeader)
                    ?.groupValues
                    ?.getOrNull(1)
                    ?.trim('"', '\'')
                val charset = charsetName?.let { runCatching { Charset.forName(it) }.getOrNull() } ?: Charsets.UTF_8
                return WebPageResponse(
                    body = bytes.toString(charset),
                    byteCount = bytes.size,
                    contentType = mimeType.ifBlank { "text/html" }
                )
            } finally {
                connection.disconnect()
            }
        }
        throw IllegalArgumentException("Web page redirect failed")
    }

    private fun validatePublicWebUrl(value: String) {
        val uri = URI(value)
        if (uri.scheme !in setOf("http", "https") || uri.host.isNullOrBlank() || uri.userInfo != null) {
            throw IllegalArgumentException("Only HTTP and HTTPS web pages can be imported")
        }
        val addresses = InetAddress.getAllByName(uri.host)
        if (addresses.isEmpty() || addresses.any { address ->
                address.isAnyLocalAddress ||
                    address.isLoopbackAddress ||
                    address.isLinkLocalAddress ||
                    address.isSiteLocalAddress ||
                    address.isMulticastAddress
            }
        ) {
            throw IllegalArgumentException("Private or local network web pages cannot be imported")
        }
    }

    private fun extractHtmlTitle(html: String): String {
        val rawTitle = Regex("<title[^>]*>(.*?)</title>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
            .find(html)
            ?.groupValues
            ?.getOrNull(1)
            .orEmpty()
        return Html.fromHtml(rawTitle, Html.FROM_HTML_MODE_LEGACY).toString().replace(Regex("\\s+"), " ").trim()
    }

    private fun documentMetadata(uri: Uri): ImportDocumentMetadata {
        var name = uri.lastPathSegment?.substringAfterLast('/')?.ifBlank { "document" } ?: "document"
        var size = 0L
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    .takeIf { it >= 0 }
                    ?.let { name = cursor.getString(it)?.ifBlank { name } ?: name }
                cursor.getColumnIndex(OpenableColumns.SIZE)
                    .takeIf { it >= 0 }
                    ?.let { size = cursor.getLong(it) }
            }
        }
        return ImportDocumentMetadata(name = name.take(180), size = size)
    }

    private fun readDocumentBytes(uri: Uri): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        resolver.openInputStream(uri)?.use { input ->
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                if (output.size() + read > MAX_SOURCE_BYTES) {
                    throw IllegalArgumentException("Document exceeds the 20 MB import limit")
                }
                output.write(buffer, 0, read)
            }
        } ?: throw IllegalArgumentException("Document could not be opened")
        return output.toByteArray()
    }

    private fun extractText(uri: Uri, name: String, mimeType: String, bytes: ByteArray): String {
        val extension = name.substringAfterLast('.', "").lowercase(Locale.US)
        return when {
            extension == "pdf" || mimeType == "application/pdf" -> extractPdf(bytes)
            extension == "docx" || mimeType == DOCX_MIME_TYPE -> extractDocx(bytes)
            extension == "xlsx" || mimeType == XLSX_MIME_TYPE -> AgentOfficeDocumentExtractor.extractXlsx(bytes)
            extension == "pptx" || mimeType == PPTX_MIME_TYPE -> AgentOfficeDocumentExtractor.extractPptx(bytes)
            extension in IMAGE_EXTENSIONS || mimeType.startsWith("image/") -> extractImageText(uri, bytes)
            extension == "html" || extension == "htm" || mimeType == "text/html" -> extractHtml(bytes)
            extension in TEXT_EXTENSIONS || name.lowercase(Locale.US) in EXTENSIONLESS_TEXT_FILES ||
                mimeType.startsWith("text/") || mimeType in TEXT_APPLICATION_MIME_TYPES ->
                bytes.toString(Charsets.UTF_8)
            extension == "doc" || mimeType == "application/msword" ->
                throw IllegalArgumentException("Legacy .doc files are not supported; save the document as .docx")
            extension in setOf("xls", "ppt") ->
                throw IllegalArgumentException("Legacy Office files are not supported; save as XLSX or PPTX")
            else -> throw IllegalArgumentException("Unsupported knowledge document type")
        }
    }

    private fun extractImageText(uri: Uri, bytes: ByteArray): String {
        require(bytes.size <= AgentWebMediaNativeTools.MAX_OCR_SOURCE_BYTES) {
            "Image exceeds the 12 MB OCR limit"
        }
        return AgentAndroidMlKitContentOcr(appContext).recognizeBytes(
            bytes,
            AgentOcrRequest(
                contentUri = uri.toString(),
                sourceKind = AgentOcrSourceKind.SELECTED,
                maxSourceBytes = AgentWebMediaNativeTools.MAX_OCR_SOURCE_BYTES,
                timeoutMillis = AgentWebMediaNativeTools.MAX_TOOL_TIMEOUT_MILLIS,
                scriptHint = AgentOcrScript.AUTO
            )
        ).text
    }

    private fun extractPdf(bytes: ByteArray): String {
        PDFBoxResourceLoader.init(appContext)
        return PDDocument.load(bytes).use { document ->
            PDFTextStripper().getText(document)
        }
    }

    private fun extractDocx(bytes: ByteArray): String {
        val xmlBytes = ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                if (entry.name == "word/document.xml") {
                    val output = ByteArrayOutputStream()
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        val read = zip.read(buffer)
                        if (read <= 0) break
                        if (output.size() + read > MAX_DOCX_XML_BYTES) {
                            throw IllegalArgumentException("DOCX text content exceeds the extraction limit")
                        }
                        output.write(buffer, 0, read)
                    }
                    return@use output.toByteArray()
                }
                zip.closeEntry()
                entry = zip.nextEntry
            }
            null
        } ?: throw IllegalArgumentException("DOCX document.xml is missing")
        val parser = Xml.newPullParser().apply {
            setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true)
            setInput(StringReader(xmlBytes.toString(Charsets.UTF_8)))
        }
        return buildString {
            var event = parser.eventType
            while (event != XmlPullParser.END_DOCUMENT) {
                when {
                    event == XmlPullParser.START_TAG && parser.name == "t" -> append(parser.nextText())
                    event == XmlPullParser.END_TAG && (parser.name == "p" || parser.name == "tr") -> append('\n')
                    event == XmlPullParser.END_TAG && parser.name == "tab" -> append('\t')
                }
                event = parser.next()
            }
        }
    }

    private fun extractHtml(bytes: ByteArray): String {
        val html = bytes.toString(Charsets.UTF_8)
        return Html.fromHtml(html, Html.FROM_HTML_MODE_LEGACY).toString()
    }

    private fun normalizeText(value: String): String = value
        .replace("\u0000", "")
        .replace(Regex("[ \\t]+"), " ")
        .replace(Regex("\\n{3,}"), "\n\n")
        .trim()

    private fun chunkText(value: String): List<String> {
        if (value.length <= CHUNK_CHARACTERS) return listOf(value)
        val chunks = mutableListOf<String>()
        var start = 0
        while (start < value.length) {
            var end = minOf(start + CHUNK_CHARACTERS, value.length)
            if (end < value.length) {
                val breakAt = value.lastIndexOfAny(charArrayOf('\n', '.', '!', '?', ' '), end - 1)
                if (breakAt > start + CHUNK_CHARACTERS / 2) end = breakAt + 1
            }
            value.substring(start, end).trim().takeIf { it.isNotBlank() }?.let { chunks += it }
            if (end >= value.length) break
            start = (end - CHUNK_OVERLAP_CHARACTERS).coerceAtLeast(start + 1)
        }
        return chunks
    }

    private fun sensitiveContentFlags(value: String): List<String> = buildList {
        if (PRIVATE_KEY_PATTERN.containsMatchIn(value)) add("private_key")
        if (SECRET_ASSIGNMENT_PATTERN.containsMatchIn(value)) add("credential_assignment")
        if (TOKEN_PATTERN.containsMatchIn(value)) add("access_token")
    }.distinct()

    private fun failure(title: String, source: String, mimeType: String, message: String) =
        AgentKnowledgeImportResult(
            success = false,
            title = title,
            source = source,
            mimeType = mimeType,
            byteCount = 0,
            characterCount = 0,
            chunkCount = 0,
            truncated = false,
            message = message
        )

    private data class ImportDocumentMetadata(val name: String, val size: Long)

    private data class WebPageResponse(
        val body: String,
        val byteCount: Int,
        val contentType: String
    )

    private companion object {
        const val MAX_SOURCE_BYTES = 20 * 1024 * 1024
        const val MAX_EXTRACTED_CHARACTERS = 240_000
        const val MAX_DOCX_XML_BYTES = 8 * 1024 * 1024
        const val MAX_WEB_BYTES = 5 * 1024 * 1024
        const val MAX_REDIRECTS = 4
        const val CHUNK_CHARACTERS = 6_000
        const val CHUNK_OVERLAP_CHARACTERS = 400
        const val DOCX_MIME_TYPE = "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
        const val XLSX_MIME_TYPE = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
        const val PPTX_MIME_TYPE = "application/vnd.openxmlformats-officedocument.presentationml.presentation"
        val IMAGE_EXTENSIONS = setOf("png", "jpg", "jpeg", "webp", "bmp", "gif", "heic", "heif")
        val TEXT_EXTENSIONS = setOf(
            "txt", "md", "markdown", "csv", "tsv", "json", "jsonl", "log", "xml", "yaml", "yml", "toml",
            "ini", "conf", "properties", "env", "gradle", "kt", "kts", "java", "py", "pyi", "js", "mjs",
            "cjs", "jsx", "ts", "tsx", "go", "rs", "c", "h", "cc", "cpp", "cxx", "hpp", "cs", "swift",
            "rb", "php", "sh", "bash", "zsh", "fish", "ps1", "bat", "cmd", "sql", "graphql", "proto",
            "dart", "lua", "r", "scala", "clj", "ex", "exs", "erl", "hrl", "fs", "fsx", "vb", "asm", "s"
        )
        val EXTENSIONLESS_TEXT_FILES = setOf(
            "dockerfile", "makefile", "rakefile", "gemfile", "podfile", "license", "readme", "changelog"
        )
        val TEXT_APPLICATION_MIME_TYPES = setOf(
            "application/json", "application/ld+json", "application/xml", "application/javascript",
            "application/x-javascript", "application/sql", "application/x-httpd-php", "application/x-sh"
        )
        val WEB_MIME_TYPES = setOf("text/html", "application/xhtml+xml", "text/plain")
        val PRIVATE_KEY_PATTERN = Regex("-----BEGIN (?:RSA |EC |OPENSSH )?PRIVATE KEY-----", RegexOption.IGNORE_CASE)
        val SECRET_ASSIGNMENT_PATTERN = Regex(
            "(?i)\\b(?:password|api[_ -]?key|access[_ -]?token|secret[_ -]?key)\\s*[:=]\\s*[^\\s]{8,}"
        )
        val TOKEN_PATTERN = Regex("\\b(?:sk|rk|pk)_[A-Za-z0-9_-]{20,}\\b")
    }
}
