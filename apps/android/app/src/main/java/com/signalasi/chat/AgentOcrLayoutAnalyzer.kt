package com.signalasi.chat

import kotlin.math.max
import kotlin.math.min
import java.util.Locale

internal data class AgentOcrCandidate(
    val script: AgentOcrScript,
    val fallbackText: String,
    val lines: List<AgentOcrLine>
)

internal data class AgentOcrMergedLayout(
    val text: String,
    val lines: List<AgentOcrLine>,
    val blocks: List<AgentOcrBlock>,
    val languageTags: List<String>,
    val layoutMode: String,
    val qualityScore: Double,
    val warnings: List<String>
)

internal object AgentOcrLayoutAnalyzer {
    fun merge(candidates: List<AgentOcrCandidate>, width: Int, height: Int): AgentOcrMergedLayout {
        val usable = candidates.map { candidate ->
            candidate.copy(
                fallbackText = candidate.fallbackText.trim(),
                lines = candidate.lines
                    .map { it.copy(text = it.text.trim()) }
                    .filter { it.text.isNotBlank() && validBounds(it) }
            )
        }.filter { it.fallbackText.isNotBlank() || it.lines.isNotEmpty() }
        if (usable.isEmpty()) return AgentOcrMergedLayout(
            text = "",
            lines = emptyList(),
            blocks = emptyList(),
            languageTags = emptyList(),
            layoutMode = "empty",
            qualityScore = 0.0,
            warnings = listOf("no_readable_text")
        )

        val ranked = usable.sortedByDescending(::candidateScore)
        val merged = mutableListOf<AgentOcrLine>()
        ranked.forEach { candidate ->
            candidate.lines.forEach { sourceLine ->
                val line = sourceLine.copy(
                    languageTag = sourceLine.languageTag.ifBlank { candidate.script.languageTag }
                )
                val duplicateIndex = merged.indexOfFirst { existing -> duplicate(existing, line) }
                if (duplicateIndex < 0) {
                    merged += line
                } else if (lineScore(line, candidate.script) > lineScore(merged[duplicateIndex], scriptFor(merged[duplicateIndex]))) {
                    merged[duplicateIndex] = line
                }
            }
        }

        val ordered = merged.sortedWith(
            compareBy<AgentOcrLine> { it.top }
                .thenBy { it.left }
                .thenBy { it.blockIndex }
                .thenBy { it.lineIndex }
        )
        val structured = structureBlocks(ordered)
        val primary = ranked.first()
        val text = structured.second.joinToString("\n") { it.text }
            .ifBlank { primary.fallbackText }
        val tags = structured.second.map { it.languageTag }
            .filter(String::isNotBlank)
            .distinct()
            .ifEmpty { listOf(primary.script.languageTag) }
        val layout = layoutMode(structured.first, structured.second, width)
        val quality = qualityScore(text, structured.second)
        val warnings = buildList {
            if (min(width, height) in 1..LOW_RESOLUTION_EDGE) add("low_resolution")
            if (quality < LOW_QUALITY_THRESHOLD) add("low_ocr_quality")
            if (tags.size > 1) add("mixed_script")
            if (structured.second.isEmpty()) add("text_without_layout")
        }
        return AgentOcrMergedLayout(
            text = text,
            lines = structured.second,
            blocks = structured.first,
            languageTags = tags,
            layoutMode = layout,
            qualityScore = quality,
            warnings = warnings
        )
    }

    private fun structureBlocks(lines: List<AgentOcrLine>): Pair<List<AgentOcrBlock>, List<AgentOcrLine>> {
        if (lines.isEmpty()) return emptyList<AgentOcrBlock>() to emptyList()
        val sourceGroups = lines.groupBy { it.languageTag to it.blockIndex }
            .values
            .map { group -> group.sortedWith(compareBy<AgentOcrLine> { it.top }.thenBy { it.left }) }
            .sortedWith(compareBy<List<AgentOcrLine>> { group -> group.minOf { it.top } }
                .thenBy { group -> group.minOf { it.left } })
        val blocks = mutableListOf<AgentOcrBlock>()
        val outputLines = mutableListOf<AgentOcrLine>()
        sourceGroups.forEachIndexed { blockIndex, group ->
            val normalized = group.mapIndexed { lineIndex, line ->
                line.copy(blockIndex = blockIndex, lineIndex = lineIndex)
            }
            outputLines += normalized
            blocks += AgentOcrBlock(
                text = normalized.joinToString("\n") { it.text },
                left = normalized.minOf { it.left },
                top = normalized.minOf { it.top },
                right = normalized.maxOf { it.right },
                bottom = normalized.maxOf { it.bottom },
                lineCount = normalized.size
            )
        }
        return blocks to outputLines
    }

    private fun layoutMode(blocks: List<AgentOcrBlock>, lines: List<AgentOcrLine>, width: Int): String {
        if (lines.isEmpty()) return "unknown"
        if (lines.size <= 2) return "sparse"
        if (width <= 0 || blocks.size < 2) return "single_column"
        val narrow = blocks.filter { it.right - it.left < width * 0.72 }
        if (narrow.size < 4) return "single_column"
        val centers = narrow.map { (it.left + it.right) / 2 }.sorted()
        val gap = centers.zipWithNext().maxByOrNull { it.second - it.first } ?: return "single_column"
        val divider = (gap.first + gap.second) / 2
        val left = narrow.filter { (it.left + it.right) / 2 < divider }
        val right = narrow.filter { (it.left + it.right) / 2 >= divider }
        if (left.size < 2 || right.size < 2 || gap.second - gap.first < width * COLUMN_GAP_RATIO) {
            return "single_column"
        }
        val overlapTop = max(left.minOf { it.top }, right.minOf { it.top })
        val overlapBottom = min(left.maxOf { it.bottom }, right.maxOf { it.bottom })
        return if (overlapBottom > overlapTop) "multi_column" else "single_column"
    }

    private fun duplicate(left: AgentOcrLine, right: AgentOcrLine): Boolean {
        val leftText = normalized(left.text)
        val rightText = normalized(right.text)
        if (leftText.isBlank() || rightText.isBlank()) return false
        val textMatches = leftText == rightText ||
            (min(leftText.length, rightText.length) >= 4 &&
                (leftText.contains(rightText) || rightText.contains(leftText)))
        if (left.right <= left.left || left.bottom <= left.top || right.right <= right.left || right.bottom <= right.top) {
            return textMatches
        }
        val intersectionWidth = (min(left.right, right.right) - max(left.left, right.left)).coerceAtLeast(0)
        val intersectionHeight = (min(left.bottom, right.bottom) - max(left.top, right.top)).coerceAtLeast(0)
        val intersection = intersectionWidth.toLong() * intersectionHeight
        val smallerArea = min(
            (left.right - left.left).toLong() * (left.bottom - left.top),
            (right.right - right.left).toLong() * (right.bottom - right.top)
        ).coerceAtLeast(1L)
        return intersection.toDouble() / smallerArea >= SPATIAL_DUPLICATE_RATIO || textMatches &&
            centerDistance(left, right) <= max(left.bottom - left.top, right.bottom - right.top) * 2
    }

    private fun centerDistance(left: AgentOcrLine, right: AgentOcrLine): Int {
        val leftX = (left.left + left.right) / 2
        val leftY = (left.top + left.bottom) / 2
        val rightX = (right.left + right.right) / 2
        val rightY = (right.top + right.bottom) / 2
        return kotlin.math.abs(leftX - rightX) + kotlin.math.abs(leftY - rightY)
    }

    private fun candidateScore(candidate: AgentOcrCandidate): Double =
        candidate.lines.sumOf { lineScore(it, candidate.script) } + candidate.fallbackText.count(Char::isLetterOrDigit)

    private fun lineScore(line: AgentOcrLine, script: AgentOcrScript): Double {
        val meaningful = line.text.count(Char::isLetterOrDigit)
        val replacementPenalty = line.text.count { it == '\uFFFD' } * 8
        val scriptBonus = line.text.count { characterMatchesScript(it, script) } * 0.4
        return meaningful * 2.0 + scriptBonus - replacementPenalty
    }

    private fun scriptFor(line: AgentOcrLine): AgentOcrScript = AgentOcrScript.entries.firstOrNull {
        it.languageTag == line.languageTag
    } ?: AgentOcrScript.AUTO

    private fun characterMatchesScript(value: Char, script: AgentOcrScript): Boolean = when (script) {
        AgentOcrScript.AUTO -> value.isLetterOrDigit()
        AgentOcrScript.LATIN -> value.code in 0x0041..0x024F
        AgentOcrScript.CHINESE -> value.code in 0x3400..0x9FFF
        AgentOcrScript.JAPANESE -> value.code in 0x3040..0x30FF || value.code in 0x3400..0x9FFF
        AgentOcrScript.KOREAN -> value.code in 0x1100..0x11FF || value.code in 0xAC00..0xD7AF
        AgentOcrScript.DEVANAGARI -> value.code in 0x0900..0x097F
    }

    private fun qualityScore(text: String, lines: List<AgentOcrLine>): Double {
        val visible = text.count { !it.isWhitespace() }.coerceAtLeast(1)
        val meaningful = text.count(Char::isLetterOrDigit)
        val replacement = text.count { it == '\uFFFD' }
        val characterQuality = ((meaningful - replacement * 4).coerceAtLeast(0).toDouble() / visible)
        val structureQuality = (lines.size.coerceAtMost(8) / 8.0) * 0.18
        return (characterQuality * 0.82 + structureQuality).coerceIn(0.0, 1.0)
    }

    private fun normalized(value: String): String = value.lowercase(Locale.ROOT)
        .filter { it.isLetterOrDigit() }

    private fun validBounds(line: AgentOcrLine): Boolean =
        line.left >= 0 && line.top >= 0 && line.right >= line.left && line.bottom >= line.top

    private const val LOW_RESOLUTION_EDGE = 640
    private const val LOW_QUALITY_THRESHOLD = 0.45
    private const val SPATIAL_DUPLICATE_RATIO = 0.58
    private const val COLUMN_GAP_RATIO = 0.14
}
