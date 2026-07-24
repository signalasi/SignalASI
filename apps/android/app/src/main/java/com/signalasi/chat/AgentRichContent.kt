package com.signalasi.chat

import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.util.UUID

enum class AgentRichBlockType {
    TEXT,
    HEADING,
    QUOTE,
    LIST,
    DIVIDER,
    CODE,
    JSON,
    KEY_VALUE,
    TABLE,
    IMAGE,
    GALLERY,
    VIDEO,
    AUDIO,
    FILE,
    LINK,
    CITATION,
    STATUS,
    PROGRESS,
    METRIC,
    TOOL,
    DIFF,
    CHART,
    TIMELINE,
    NOTICE,
    HTML,
    WEBPAGE,
    ACTIONS,
    APPROVAL,
    FORM,
    UNKNOWN
}

data class AgentRichAction(
    val id: String,
    val label: String,
    val verb: String,
    val value: String = "",
    val style: String = "default"
)

data class AgentRichField(
    val id: String,
    val label: String,
    val inputType: String = "text",
    val value: String = "",
    val required: Boolean = false,
    val options: List<String> = emptyList()
)

data class AgentRichBlock(
    val id: String,
    val type: AgentRichBlockType,
    val title: String = "",
    val text: String = "",
    val uri: String = "",
    val dataB64: String = "",
    val mimeType: String = "",
    val language: String = "",
    val columns: List<String> = emptyList(),
    val rows: List<List<String>> = emptyList(),
    val value: Int = 0,
    val maximum: Int = 100,
    val fallbackText: String = "",
    val actions: List<AgentRichAction> = emptyList(),
    val fields: List<AgentRichField> = emptyList(),
    val metadata: Map<String, String> = emptyMap()
)

object AgentRichContentCodec {
    const val VERSION = 1
    private const val MAX_BLOCKS = 100
    private const val MAX_BLOCK_TEXT = 32_000
    private const val MAX_SERIALIZED_SIZE = 640 * 1024
    private const val MAX_INLINE_DATA_CHARS = 420 * 1024
    private const val MAX_TABLE_COLUMNS = 24
    private const val MAX_TABLE_ROWS = 500

    fun normalize(raw: String): String {
        val blocks = decode(raw)
        return if (blocks.isEmpty()) "" else encode(blocks)
    }

    fun fallbackText(raw: String): String = decode(raw).asSequence()
        .map { block -> block.text.ifBlank { block.title.ifBlank { block.fallbackText.ifBlank { block.uri } } } }
        .firstOrNull(String::isNotBlank)
        .orEmpty()

    fun decode(raw: String): List<AgentRichBlock> {
        if (raw.isBlank() || raw.length > MAX_SERIALIZED_SIZE) return emptyList()
        val root = runCatching { JSONObject(raw) }.getOrNull() ?: return emptyList()
        if (root.optInt("version", VERSION) > VERSION) return emptyList()
        val array = root.optJSONArray("blocks") ?: return emptyList()
        return buildList {
            for (index in 0 until minOf(array.length(), MAX_BLOCKS)) {
                val item = array.optJSONObject(index) ?: continue
                val typeName = item.optString("type").trim().uppercase()
                val declaredType = runCatching { AgentRichBlockType.valueOf(typeName) }
                    .getOrDefault(AgentRichBlockType.UNKNOWN)
                val uri = item.optString("uri").trim().take(4_096)
                val mimeType = item.optString("mime_type").trim().take(160)
                val language = item.optString("language").trim().take(80)
                val type = AgentRichFormatRegistry.normalizedType(declaredType, uri, mimeType, language)
                val columns = jsonStrings(item.optJSONArray("columns"), MAX_TABLE_COLUMNS)
                val rows = buildList {
                    val sourceRows = item.optJSONArray("rows") ?: JSONArray()
                    for (rowIndex in 0 until minOf(sourceRows.length(), MAX_TABLE_ROWS)) {
                        add(jsonStrings(sourceRows.optJSONArray(rowIndex), MAX_TABLE_COLUMNS))
                    }
                }
                val block = AgentRichBlock(
                    id = item.optString("id").trim().take(120).ifBlank { UUID.randomUUID().toString() },
                    type = type,
                    title = item.optString("title").trim().take(500),
                    text = normalizeBlockText(item.optString("text").take(MAX_BLOCK_TEXT), type),
                    uri = uri,
                    dataB64 = item.optString("data_b64").trim().take(MAX_INLINE_DATA_CHARS),
                    mimeType = mimeType,
                    language = language,
                    columns = columns,
                    rows = rows,
                    value = item.optInt("value", 0),
                    maximum = item.optInt("maximum", 100).coerceAtLeast(1),
                    fallbackText = item.optString("fallback_text").trim().take(MAX_BLOCK_TEXT),
                    actions = decodeActions(item.optJSONArray("actions")),
                    fields = decodeFields(item.optJSONArray("fields")),
                    metadata = decodeMetadata(item.optJSONObject("metadata"))
                )
                if (block.hasRenderableContent()) add(block)
            }
        }.deduplicateArtifacts()
    }

    fun encode(blocks: List<AgentRichBlock>): String {
        val array = JSONArray()
        blocks.take(MAX_BLOCKS).filter { it.hasRenderableContent() }.forEach { block ->
            array.put(JSONObject()
                .put("id", block.id.take(120))
                .put("type", block.type.name.lowercase())
                .put("title", block.title.take(500))
                .put("text", block.text.take(MAX_BLOCK_TEXT))
                .put("uri", block.uri.take(4_096))
                .put("data_b64", block.dataB64.take(MAX_INLINE_DATA_CHARS))
                .put("mime_type", block.mimeType.take(160))
                .put("language", block.language.take(80))
                .put("columns", JSONArray(block.columns.take(MAX_TABLE_COLUMNS)))
                .put("rows", JSONArray(block.rows.take(MAX_TABLE_ROWS).map { JSONArray(it.take(MAX_TABLE_COLUMNS)) }))
                .put("value", block.value)
                .put("maximum", block.maximum.coerceAtLeast(1))
                .put("fallback_text", block.fallbackText.take(MAX_BLOCK_TEXT))
                .put("actions", JSONArray(block.actions.take(12).map { action ->
                    JSONObject()
                        .put("id", action.id.take(120))
                        .put("label", action.label.take(120))
                        .put("verb", action.verb.take(80))
                        .put("value", action.value.take(8_000))
                        .put("style", action.style.take(40))
                }))
                .put("fields", JSONArray(block.fields.take(24).map { field ->
                    JSONObject()
                        .put("id", field.id.take(120))
                        .put("label", field.label.take(200))
                        .put("input_type", field.inputType.take(40))
                        .put("value", field.value.take(4_000))
                        .put("required", field.required)
                        .put("options", JSONArray(field.options.take(50)))
                }))
                .put("metadata", JSONObject(block.metadata.entries.take(MAX_METADATA_ITEMS).associate {
                    it.key.take(80) to it.value.take(MAX_METADATA_VALUE)
                })))
        }
        while (array.length() > 0) {
            val document = JSONObject().put("version", VERSION).put("blocks", array).toString()
            if (document.length <= MAX_SERIALIZED_SIZE) return document
            array.remove(array.length() - 1)
        }
        return ""
    }

    fun fromText(text: String): List<AgentRichBlock> {
        val clean = text.trim()
        if (clean.isBlank()) return emptyList()
        prettyJson(clean)?.let { formatted ->
            return listOf(AgentRichBlock(newId(), AgentRichBlockType.JSON, text = formatted, language = "json"))
        }
        val blocks = mutableListOf<AgentRichBlock>()
        val lines = clean.lines()
        var index = 0
        var paragraph = mutableListOf<String>()

        fun flushParagraph() {
            val value = paragraph.joinToString("\n").trim()
            if (value.isNotBlank()) blocks += AgentRichBlock(newId(), AgentRichBlockType.TEXT, text = value)
            paragraph = mutableListOf()
        }

        while (index < lines.size && blocks.size < MAX_BLOCKS) {
            val line = lines[index]
            if (line.trimStart().startsWith("```")) {
                flushParagraph()
                val language = line.trim().removePrefix("```").trim()
                val code = mutableListOf<String>()
                index++
                while (index < lines.size && !lines[index].trimStart().startsWith("```")) {
                    code += lines[index++]
                }
                val codeText = code.joinToString("\n")
                if (language.equals("signalasi-rich", ignoreCase = true)) {
                    val document = runCatching { JSONObject(codeText) }.getOrNull()
                    val richBlocks = document?.let { decode(
                        if (it.has("blocks")) it.put("version", it.optInt("version", VERSION)).toString()
                        else JSONObject().put("version", VERSION).put("blocks", JSONArray().put(it)).toString()
                    ) }.orEmpty()
                    if (richBlocks.isNotEmpty()) blocks += richBlocks
                    else blocks += AgentRichBlock(newId(), AgentRichBlockType.CODE, text = codeText, language = language)
                } else {
                    blocks += AgentRichBlock(newId(), AgentRichBlockType.CODE, text = codeText, language = language)
                }
            } else if (parseListItem(line) != null) {
                flushParagraph()
                val rows = mutableListOf<List<String>>()
                var ordered = false
                var checklist = false
                while (index < lines.size) {
                    val item = parseListItem(lines[index]) ?: break
                    rows += listOf(item.marker, item.text)
                    ordered = ordered || item.ordered
                    checklist = checklist || item.checklist
                    index++
                }
                blocks += AgentRichBlock(
                    newId(),
                    AgentRichBlockType.LIST,
                    rows = rows,
                    metadata = mapOf("style" to when {
                        checklist -> "checklist"
                        ordered -> "ordered"
                        else -> "bullet"
                    })
                )
                continue
            } else if (isTableHeader(lines, index)) {
                flushParagraph()
                val columns = tableCells(lines[index])
                index += 2
                val rows = mutableListOf<List<String>>()
                while (index < lines.size && lines[index].contains('|') && rows.size < MAX_TABLE_ROWS) {
                    rows += tableCells(lines[index++]).take(columns.size.coerceAtLeast(1))
                }
                blocks += AgentRichBlock(newId(), AgentRichBlockType.TABLE, columns = columns, rows = rows)
                continue
            } else if (line.matches(Regex("^#{1,6}\\s+.+"))) {
                flushParagraph()
                blocks += AgentRichBlock(
                    newId(), AgentRichBlockType.HEADING,
                    text = line.replaceFirst(Regex("^#{1,6}\\s+"), ""),
                    metadata = mapOf("level" to line.takeWhile { it == '#' }.length.toString())
                )
            } else if (line.trimStart().startsWith("> ")) {
                flushParagraph()
                val quote = mutableListOf<String>()
                while (index < lines.size && lines[index].trimStart().startsWith(">")) {
                    quote += lines[index].trimStart().removePrefix(">").removePrefix(" ")
                    index++
                }
                blocks += AgentRichBlock(newId(), AgentRichBlockType.QUOTE, text = quote.joinToString("\n"))
                continue
            } else if (line.trim().matches(Regex("^([-*_])\\1{2,}$"))) {
                flushParagraph()
                blocks += AgentRichBlock(newId(), AgentRichBlockType.DIVIDER)
            } else if (line.isBlank()) {
                flushParagraph()
            } else {
                paragraph += line
            }
            index++
        }
        flushParagraph()
        markdownWebPage(clean)?.let { blocks += it }
        return blocks.take(MAX_BLOCKS)
    }

    fun fromEnvelope(envelope: JSONObject?): String {
        val value = envelope?.optJSONObject("rich_output") ?: return ""
        return normalize(value.toString())
    }

    private fun AgentRichBlock.hasRenderableContent(): Boolean =
        text.isNotBlank() || title.isNotBlank() || uri.isNotBlank() || dataB64.isNotBlank() || columns.isNotEmpty() ||
            rows.isNotEmpty() || actions.isNotEmpty() || fields.isNotEmpty() ||
            type in setOf(AgentRichBlockType.PROGRESS, AgentRichBlockType.STATUS, AgentRichBlockType.DIVIDER)

    private fun List<AgentRichBlock>.deduplicateArtifacts(): List<AgentRichBlock> {
        val result = mutableListOf<AgentRichBlock>()
        val indexes = mutableMapOf<String, Int>()
        forEach { block ->
            val identity = block.artifactIdentity()
            if (identity.isBlank()) {
                result += block
                return@forEach
            }
            val previousIndex = indexes[identity]
            if (previousIndex == null) {
                indexes[identity] = result.size
                result += block
            } else if (block.artifactQuality() > result[previousIndex].artifactQuality()) {
                result[previousIndex] = block
            }
        }
        return result
    }

    private fun AgentRichBlock.artifactIdentity(): String {
        if (type !in setOf(
                AgentRichBlockType.IMAGE,
                AgentRichBlockType.VIDEO,
                AgentRichBlockType.AUDIO,
                AgentRichBlockType.FILE
            )
        ) return ""
        val artifactName = fallbackText
            .replace('\\', '/')
            .trim()
            .lowercase()
            .ifBlank { title.trim().lowercase() }
        metadata["sha256"]
            ?.trim()
            ?.lowercase()
            ?.takeIf { it.matches(Regex("[0-9a-f]{64}")) }
            ?.let { return "sha256:$it:$artifactName" }
        if (dataB64.isNotBlank()) return "data:${dataB64.hashCode()}:$artifactName"
        if (uri.isNotBlank()) return "uri:${uri.replace('\\', '/').trim().lowercase()}"
        if (fallbackText.isNotBlank() || title.isNotBlank()) {
            return "name:${fallbackText.replace('\\', '/').trim().lowercase()}:${title.trim().lowercase()}"
        }
        return ""
    }

    private fun AgentRichBlock.artifactQuality(): Int =
        (if (dataB64.isNotBlank()) 8 else 0) +
            (if (mimeType.isNotBlank()) 4 else 0) +
            (if (metadata["sha256"].isNullOrBlank()) 0 else 2) +
            (if (uri.startsWith("signalasi-artifact://")) 1 else 0)

    private fun normalizeBlockText(value: String, type: AgentRichBlockType): String =
        if (type in setOf(AgentRichBlockType.CODE, AgentRichBlockType.DIFF, AgentRichBlockType.JSON, AgentRichBlockType.HTML)) {
            value.trim('\r', '\n')
        } else {
            value.trim()
        }

    private fun decodeMetadata(value: JSONObject?): Map<String, String> {
        if (value == null) return emptyMap()
        return buildMap {
            val keys = value.keys()
            while (keys.hasNext() && size < MAX_METADATA_ITEMS) {
                val key = keys.next().take(80)
                put(key, value.optString(key).take(MAX_METADATA_VALUE))
            }
        }
    }

    private fun decodeActions(array: JSONArray?): List<AgentRichAction> {
        if (array == null) return emptyList()
        return buildList {
            for (index in 0 until minOf(array.length(), 12)) {
                val item = array.optJSONObject(index) ?: continue
                val label = item.optString("label").trim().take(120)
                val verb = item.optString("verb").trim().lowercase().take(80)
                if (label.isBlank() || verb.isBlank()) continue
                add(AgentRichAction(
                    id = item.optString("id").trim().take(120).ifBlank { "action-$index" },
                    label = label,
                    verb = verb,
                    value = item.optString("value").take(8_000),
                    style = item.optString("style", "default").take(40)
                ))
            }
        }
    }

    private fun decodeFields(array: JSONArray?): List<AgentRichField> {
        if (array == null) return emptyList()
        return buildList {
            for (index in 0 until minOf(array.length(), 24)) {
                val item = array.optJSONObject(index) ?: continue
                val id = item.optString("id").trim().take(120)
                val label = item.optString("label").trim().take(200)
                if (id.isBlank() || label.isBlank()) continue
                add(AgentRichField(
                    id = id,
                    label = label,
                    inputType = item.optString("input_type", "text").trim().lowercase().take(40),
                    value = item.optString("value").take(4_000),
                    required = item.optBoolean("required"),
                    options = jsonStrings(item.optJSONArray("options"), 50)
                ))
            }
        }
    }

    private fun jsonStrings(array: JSONArray?, limit: Int): List<String> {
        if (array == null) return emptyList()
        return buildList {
            for (index in 0 until minOf(array.length(), limit)) {
                add(array.optString(index).take(2_000))
            }
        }
    }

    private fun isTableHeader(lines: List<String>, index: Int): Boolean {
        if (index + 1 >= lines.size || !lines[index].contains('|')) return false
        val separator = lines[index + 1].trim().trim('|')
        if (separator.isBlank()) return false
        return separator.split('|').all { it.trim().matches(Regex(":?-{3,}:?")) }
    }

    private fun tableCells(line: String): List<String> =
        line.trim().trim('|').split('|').map { it.trim().take(2_000) }.take(MAX_TABLE_COLUMNS)

    private data class ParsedListItem(
        val marker: String,
        val text: String,
        val ordered: Boolean,
        val checklist: Boolean
    )

    private fun parseListItem(line: String): ParsedListItem? {
        val match = Regex("^\\s*(?:(\\d+)[.)]|([-+*]))\\s+(.+)$").matchEntire(line) ?: return null
        val rawText = match.groupValues[3].trim()
        val check = Regex("^\\[([ xX])]\\s*(.*)$").matchEntire(rawText)
        return ParsedListItem(
            marker = when {
                check != null && check.groupValues[1].isBlank() -> "unchecked"
                check != null -> "checked"
                match.groupValues[1].isNotBlank() -> match.groupValues[1]
                else -> "bullet"
            },
            text = check?.groupValues?.get(2)?.trim().orEmpty().ifBlank { rawText },
            ordered = match.groupValues[1].isNotBlank(),
            checklist = check != null
        )
    }

    private fun prettyJson(value: String): String? {
        if (!(value.startsWith('{') && value.endsWith('}')) && !(value.startsWith('[') && value.endsWith(']'))) {
            return null
        }
        return runCatching {
            when (val parsed = JSONTokener(value).nextValue()) {
                is JSONObject -> parsed.toString(2)
                is JSONArray -> parsed.toString(2)
                else -> null
            }
        }.getOrNull()
    }

    private fun markdownWebPage(text: String): AgentRichBlock? {
        val matches = Regex("""\[([^]]+)]\((https://[^)\s]+)\)""", RegexOption.IGNORE_CASE)
            .findAll(text)
            .take(2)
            .toList()
        if (matches.size != 1) return null
        val match = matches.single()
        val title = match.groupValues[1].trim().take(500)
        val uri = match.groupValues[2].trim().take(4_096)
        return AgentRichBlock(
            id = newId(),
            type = AgentRichBlockType.WEBPAGE,
            title = title,
            uri = uri,
            fallbackText = uri
        )
    }

    private fun newId(): String = UUID.randomUUID().toString()

    private const val MAX_METADATA_ITEMS = 32
    private const val MAX_METADATA_VALUE = 2_000
}
