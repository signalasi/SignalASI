package com.signalasi.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentOcrLayoutAnalyzerTest {
    @Test
    fun autoModeMergesMixedScriptsWithoutDuplicatingSpatialMatches() {
        val chinese = AgentOcrCandidate(
            AgentOcrScript.CHINESE,
            "SignalASI \u63a7\u5236\u4e2d\u5fc3",
            listOf(
                line("SignalASI", 10, 10, 150, 40, "zh", 0),
                line("\u63a7\u5236\u4e2d\u5fc3", 10, 55, 170, 90, "zh", 0)
            )
        )
        val latin = AgentOcrCandidate(
            AgentOcrScript.LATIN,
            "SignalASI Ready",
            listOf(
                line("SignalASI", 12, 11, 151, 41, "Latn", 0),
                line("Ready", 10, 105, 100, 135, "Latn", 1)
            )
        )

        val merged = AgentOcrLayoutAnalyzer.merge(listOf(chinese, latin), 1080, 1920)

        assertEquals(1, merged.lines.count { it.text == "SignalASI" })
        assertTrue(merged.text.contains("\u63a7\u5236\u4e2d\u5fc3"))
        assertTrue(merged.text.contains("Ready"))
        assertTrue(merged.languageTags.containsAll(listOf("zh", "Latn")))
        assertTrue("mixed_script" in merged.warnings)
    }

    @Test
    fun layoutProducesBoundedBlocksAndQualitySignals() {
        val candidate = AgentOcrCandidate(
            AgentOcrScript.LATIN,
            "Title\nFirst line\nSecond line",
            listOf(
                line("Title", 20, 10, 300, 55, "Latn", 0),
                line("First line", 20, 90, 320, 125, "Latn", 1),
                line("Second line", 20, 130, 340, 165, "Latn", 1)
            )
        )

        val merged = AgentOcrLayoutAnalyzer.merge(listOf(candidate), 1080, 1920)

        assertEquals(2, merged.blocks.size)
        assertEquals(2, merged.blocks.last().lineCount)
        assertEquals("single_column", merged.layoutMode)
        assertTrue(merged.qualityScore in 0.0..1.0)
        assertFalse("low_ocr_quality" in merged.warnings)
    }

    private fun line(
        text: String,
        left: Int,
        top: Int,
        right: Int,
        bottom: Int,
        language: String,
        block: Int
    ) = AgentOcrLine(text, left, top, right, bottom, language, block, 0)
}
