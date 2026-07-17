package com.signalasi.chat

import org.w3c.dom.Element
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.Locale
import java.util.zip.ZipInputStream
import javax.xml.parsers.DocumentBuilderFactory

internal object AgentOfficeDocumentExtractor {
    fun extractXlsx(bytes: ByteArray): String {
        val entries = readEntries(bytes) { name ->
            name == "xl/sharedStrings.xml" ||
                name.startsWith("xl/worksheets/sheet") && name.endsWith(".xml")
        }
        val sharedStrings = entries["xl/sharedStrings.xml"]?.let(::parseSharedStrings).orEmpty()
        val sheets = entries.entries
            .filter { it.key.startsWith("xl/worksheets/sheet") }
            .sortedBy { naturalIndex(it.key, "sheet") }
        require(sheets.isNotEmpty()) { "XLSX contains no readable worksheets" }
        return buildString {
            sheets.forEachIndexed { sheetIndex, (_, xml) ->
                append("[Sheet ").append(sheetIndex + 1).append("]\n")
                parseXml(xml).getElementsByTagNameNS("*", "row").asElements().forEach { row ->
                    val values = row.getElementsByTagNameNS("*", "c").asElements().mapNotNull { cell ->
                        val reference = cell.getAttribute("r").ifBlank { "cell" }
                        val type = cell.getAttribute("t")
                        val raw = firstText(cell, "v")
                        val inline = descendantText(cell, "t")
                        val formula = firstText(cell, "f")
                        val value = when {
                            type == "s" -> raw.toIntOrNull()?.let(sharedStrings::getOrNull).orEmpty()
                            type == "inlineStr" || type == "str" -> inline.ifBlank { raw }
                            raw.isNotBlank() -> raw
                            formula.isNotBlank() -> "=$formula"
                            else -> inline
                        }.normalizeOfficeText()
                        value.takeIf(String::isNotBlank)?.let { "$reference=$it" }
                    }
                    if (values.isNotEmpty()) append(values.joinToString(" | ")).append('\n')
                    require(length <= MAX_OUTPUT_CHARS) { "XLSX text exceeds the extraction limit" }
                }
                append('\n')
            }
        }.trim()
    }

    fun extractPptx(bytes: ByteArray): String {
        val slides = readEntries(bytes) { name ->
            name.startsWith("ppt/slides/slide") && name.endsWith(".xml")
        }.entries.sortedBy { naturalIndex(it.key, "slide") }
        require(slides.isNotEmpty()) { "PPTX contains no readable slides" }
        return buildString {
            slides.forEachIndexed { index, (_, xml) ->
                append("[Slide ").append(index + 1).append("]\n")
                val paragraphs = parseXml(xml).getElementsByTagNameNS("*", "p").asElements()
                    .map { paragraph -> descendantText(paragraph, "t").normalizeOfficeText() }
                    .filter(String::isNotBlank)
                if (paragraphs.isEmpty()) {
                    parseXml(xml).documentElement?.let { root ->
                        descendantText(root, "t").normalizeOfficeText().takeIf(String::isNotBlank)?.let(::append)
                    }
                    append('\n')
                } else {
                    paragraphs.forEach { append(it).append('\n') }
                }
                append('\n')
                require(length <= MAX_OUTPUT_CHARS) { "PPTX text exceeds the extraction limit" }
            }
        }.trim()
    }

    private fun parseSharedStrings(xml: ByteArray): List<String> = parseXml(xml)
        .getElementsByTagNameNS("*", "si")
        .asElements()
        .map { descendantText(it, "t").normalizeOfficeText() }

    private fun readEntries(bytes: ByteArray, include: (String) -> Boolean): Map<String, ByteArray> {
        val result = linkedMapOf<String, ByteArray>()
        var selectedBytes = 0
        var expandedBytes = 0
        var entryCount = 0
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                entryCount += 1
                require(entryCount <= MAX_ZIP_ENTRIES) { "Office archive contains too many entries" }
                val name = entry.name.replace('\\', '/')
                require(!name.startsWith('/') && name.split('/').none { it == ".." }) {
                    "Office archive contains an unsafe entry"
                }
                if (!entry.isDirectory) {
                    val selected = include(name)
                    val output = if (selected) ByteArrayOutputStream() else null
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        val read = zip.read(buffer)
                        if (read <= 0) break
                        expandedBytes += read
                        require(expandedBytes <= MAX_EXPANDED_ARCHIVE_BYTES) {
                            "Office archive expands beyond the safety limit"
                        }
                        if (selected) {
                            selectedBytes += read
                            require(selectedBytes <= MAX_SELECTED_XML_BYTES) {
                                "Office XML exceeds the extraction limit"
                            }
                            output?.write(buffer, 0, read)
                        }
                    }
                    if (selected) result[name] = checkNotNull(output).toByteArray()
                }
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }
        return result
    }

    private fun parseXml(bytes: ByteArray) = DocumentBuilderFactory.newInstance().apply {
        require(!String(bytes, Charsets.UTF_8).contains("<!DOCTYPE", ignoreCase = true)) {
            "Office XML document types are not allowed"
        }
        isNamespaceAware = true
        isExpandEntityReferences = false
        runCatching { isXIncludeAware = false }
        secureFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        secureFeature("http://xml.org/sax/features/external-general-entities", false)
        secureFeature("http://xml.org/sax/features/external-parameter-entities", false)
        runCatching { setAttribute("http://javax.xml.XMLConstants/property/accessExternalDTD", "") }
        runCatching { setAttribute("http://javax.xml.XMLConstants/property/accessExternalSchema", "") }
    }.newDocumentBuilder().parse(ByteArrayInputStream(bytes))

    private fun DocumentBuilderFactory.secureFeature(name: String, value: Boolean) {
        runCatching { setFeature(name, value) }
    }

    private fun firstText(element: Element, localName: String): String =
        element.getElementsByTagNameNS("*", localName).item(0)?.textContent.orEmpty()

    private fun descendantText(element: Element, localName: String): String =
        element.getElementsByTagNameNS("*", localName).asElements()
            .joinToString("") { it.textContent.orEmpty() }

    private fun org.w3c.dom.NodeList.asElements(): List<Element> = buildList {
        for (index in 0 until length) (item(index) as? Element)?.let(::add)
    }

    private fun naturalIndex(name: String, marker: String): Int = Regex("$marker(\\d+)", RegexOption.IGNORE_CASE)
        .find(name)
        ?.groupValues
        ?.getOrNull(1)
        ?.toIntOrNull()
        ?: Int.MAX_VALUE

    private fun String.normalizeOfficeText(): String = replace('\u0000', ' ')
        .replace(Regex("[ \\t\\r\\n]+"), " ")
        .trim()

    private const val MAX_ZIP_ENTRIES = 2_000
    private const val MAX_SELECTED_XML_BYTES = 24 * 1024 * 1024
    private const val MAX_EXPANDED_ARCHIVE_BYTES = 96 * 1024 * 1024
    private const val MAX_OUTPUT_CHARS = 500_000
}
