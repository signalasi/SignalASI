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
            val extracted = extractText(metadata.name, mimeType, bytes)
            val normalized = normalizeText(extracted)
            if (normalized.isBlank()) {
                return failure(metadata.name, source, mimeType, "No readable text was found in this document")
            }
            val sensitiveFlags = sensitiveContentFlags(normalized)
            if (sensitiveFlags.isNotEmpty()) {
                return AgentKnowledgeImportResult(
                    success = false,
                    title = metadata.name,
                    source = source,
                    mimeType = mimeType,
                    byteCount = bytes.size,
                    characterCount = normalized.length,
                    chunkCount = 0,
                    truncated = false,
                    message = "Import blocked because the document appears to contain secrets",
                    sensitiveFlags = sensitiveFlags
                )
            }
            val truncated = normalized.length > MAX_EXTRACTED_CHARACTERS
            val indexedText = normalized.take(MAX_EXTRACTED_CHARACTERS)
            val chunks = chunkText(indexedText)
            val extension = metadata.name.substringAfterLast('.', "").lowercase(Locale.US)
            val importedAt = System.currentTimeMillis()
            val items = chunks.mapIndexed { index, chunk ->
                AgentKnowledgeItem(
                    id = UUID.nameUUIDFromBytes("$source#$index".toByteArray(Charsets.UTF_8)).toString(),
                    kind = AgentKnowledgeKind.DOCUMENT,
                    title = if (chunks.size == 1) metadata.name else "${metadata.name} [${index + 1}/${chunks.size}]",
                    content = chunk,
                    source = source,
                    tags = listOf("import", "file", mimeType, extension).filter { it.isNotBlank() },
                    updatedAtMillis = importedAt + index
                )
            }
            store.replaceSource(source, items)
            AgentKnowledgeImportResult(
                success = true,
                title = metadata.name,
                source = source,
                mimeType = mimeType,
                byteCount = bytes.size,
                characterCount = indexedText.length,
                chunkCount = items.size,
                truncated = truncated,
                message = buildString {
                    append("Imported ").append(metadata.name)
                    append(" as ").append(items.size).append(" knowledge chunks")
                    if (truncated) append("; content truncated to ").append(MAX_EXTRACTED_CHARACTERS).append(" characters")
                }
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

    private fun extractText(name: String, mimeType: String, bytes: ByteArray): String {
        val extension = name.substringAfterLast('.', "").lowercase(Locale.US)
        return when {
            extension == "pdf" || mimeType == "application/pdf" -> extractPdf(bytes)
            extension == "docx" || mimeType == DOCX_MIME_TYPE -> extractDocx(bytes)
            extension == "html" || extension == "htm" || mimeType == "text/html" -> extractHtml(bytes)
            extension in TEXT_EXTENSIONS || mimeType.startsWith("text/") || mimeType == "application/json" ->
                bytes.toString(Charsets.UTF_8)
            extension == "doc" || mimeType == "application/msword" ->
                throw IllegalArgumentException("Legacy .doc files are not supported; save the document as .docx")
            else -> throw IllegalArgumentException("Unsupported knowledge document type")
        }
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

    private companion object {
        const val MAX_SOURCE_BYTES = 20 * 1024 * 1024
        const val MAX_EXTRACTED_CHARACTERS = 240_000
        const val MAX_DOCX_XML_BYTES = 8 * 1024 * 1024
        const val CHUNK_CHARACTERS = 6_000
        const val CHUNK_OVERLAP_CHARACTERS = 400
        const val DOCX_MIME_TYPE = "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
        val TEXT_EXTENSIONS = setOf("txt", "md", "markdown", "csv", "json", "log", "xml", "yaml", "yml")
        val PRIVATE_KEY_PATTERN = Regex("-----BEGIN (?:RSA |EC |OPENSSH )?PRIVATE KEY-----", RegexOption.IGNORE_CASE)
        val SECRET_ASSIGNMENT_PATTERN = Regex(
            "(?i)\\b(?:password|api[_ -]?key|access[_ -]?token|secret[_ -]?key)\\s*[:=]\\s*[^\\s]{8,}"
        )
        val TOKEN_PATTERN = Regex("\\b(?:sk|rk|pk)_[A-Za-z0-9_-]{20,}\\b")
    }
}
