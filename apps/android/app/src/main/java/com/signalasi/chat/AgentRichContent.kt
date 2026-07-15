package com.signalasi.chat

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

enum class AgentRichBlockType {
    TEXT,
    HEADING,
    QUOTE,
    CODE,
    TABLE,
    IMAGE,
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
    val mimeType: String = "",
    val language: String = "",
    val columns: List<String> = emptyList(),
    val rows: List<List<String>> = emptyList(),
    val value: Int = 0,
    val maximum: Int = 100,
    val fallbackText: String = "",
    val actions: List<AgentRichAction> = emptyList(),
    val fields: List<AgentRichField> = emptyList()
)

object AgentRichContentCodec {
    const val VERSION = 1
    private const val MAX_BLOCKS = 100
    private const val MAX_BLOCK_TEXT = 32_000
    private const val MAX_SERIALIZED_SIZE = 256 * 1024
    private const val MAX_TABLE_COLUMNS = 24
    private const val MAX_TABLE_ROWS = 500

    fun normalize(raw: String): String {
        val blocks = decode(raw)
        return if (blocks.isEmpty()) "" else encode(blocks)
    }

    fun decode(raw: String): List<AgentRichBlock> {
        if (raw.isBlank() || raw.length > MAX_SERIALIZED_SIZE) return emptyList()
        val root = runCatching { JSONObject(raw) }.getOrNull() ?: return emptyList()
        if (root.optInt("version", VERSION) > VERSION) return emptyList()
        val array = root.optJSONArray("blocks") ?: return emptyList()
        return buildList {
            for (index in 0 until minOf(array.length(), MAX_BLOCKS)) {
                val item = array.optJSONObject(index) ?: continue
                val typeName = item.optString("type").trim().uppercase()
                val type = runCatching { AgentRichBlockType.valueOf(typeName) }
                    .getOrDefault(AgentRichBlockType.UNKNOWN)
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
                    text = item.optString("text").trim().take(MAX_BLOCK_TEXT),
                    uri = item.optString("uri").trim().take(4_096),
                    mimeType = item.optString("mime_type").trim().take(160),
                    language = item.optString("language").trim().take(80),
                    columns = columns,
                    rows = rows,
                    value = item.optInt("value", 0),
                    maximum = item.optInt("maximum", 100).coerceAtLeast(1),
                    fallbackText = item.optString("fallback_text").trim().take(MAX_BLOCK_TEXT),
                    actions = decodeActions(item.optJSONArray("actions")),
                    fields = decodeFields(item.optJSONArray("fields"))
                )
                if (block.hasRenderableContent()) add(block)
            }
        }
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
                    text = line.replaceFirst(Regex("^#{1,6}\\s+"), "")
                )
            } else if (line.trimStart().startsWith("> ")) {
                flushParagraph()
                blocks += AgentRichBlock(newId(), AgentRichBlockType.QUOTE, text = line.trimStart().removePrefix("> "))
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
        text.isNotBlank() || title.isNotBlank() || uri.isNotBlank() || columns.isNotEmpty() ||
            rows.isNotEmpty() || actions.isNotEmpty() || fields.isNotEmpty() ||
            type in setOf(AgentRichBlockType.PROGRESS, AgentRichBlockType.STATUS)

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

    private fun markdownWebPage(text: String): AgentRichBlock? {
        val match = Regex("""\[([^]]+)]\((https://[^)\s]+)\)""", RegexOption.IGNORE_CASE).find(text)
            ?: return null
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
}
