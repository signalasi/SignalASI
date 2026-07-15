package com.signalasi.chat

enum class AgentInlineStyle { NORMAL, BOLD, CODE, LINK }

data class AgentInlineSegment(
    val text: String,
    val style: AgentInlineStyle = AgentInlineStyle.NORMAL,
    val url: String = ""
)

/** Small, deterministic inline parser for model text rendered inside rich blocks. */
object AgentInlineMarkdown {
    private val tokens = listOf(
        AgentInlineStyle.BOLD to Regex("\\*\\*([^*\\n]+)\\*\\*"),
        AgentInlineStyle.LINK to Regex("\\[([^]\\n]+)]\\((https?://[^)\\s]+)\\)"),
        AgentInlineStyle.CODE to Regex("`([^`\\n]+)`")
    )

    fun parse(value: String): List<AgentInlineSegment> {
        if (value.isEmpty()) return emptyList()
        val result = mutableListOf<AgentInlineSegment>()
        var cursor = 0
        while (cursor < value.length) {
            val next = tokens.mapNotNull { (style, pattern) ->
                pattern.find(value, cursor)?.let { Triple(style, pattern, it) }
            }.minByOrNull { it.third.range.first }
            if (next == null) {
                result += AgentInlineSegment(value.substring(cursor))
                break
            }
            val (style, _, match) = next
            if (match.range.first > cursor) {
                result += AgentInlineSegment(value.substring(cursor, match.range.first))
            }
            result += when (style) {
                AgentInlineStyle.LINK -> AgentInlineSegment(
                    text = match.groupValues[1],
                    style = style,
                    url = match.groupValues[2]
                )
                else -> AgentInlineSegment(match.groupValues[1], style)
            }
            cursor = match.range.last + 1
        }
        return result.filter { it.text.isNotEmpty() }
    }
}
