package com.signalasi.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CodexStyleResponsePolicyTest {
    @Test
    fun policyCoversLanguageActionClarificationAndFailures() {
        val policy = CodexStyleResponsePolicy.PROMPT
        assertTrue(policy.contains("Simplified Chinese"))
        assertTrue(policy.contains("execute it"))
        assertTrue(policy.contains("ask only the most important question"))
        assertTrue(policy.contains("Never return a raw exception or stack trace"))
        assertTrue(policy.contains("never reproduce the input files as assistant artifacts"))
    }

    @Test
    fun assistantRichOutputDropsInputArtifactsButKeepsGeneratedFiles() {
        val raw = AgentRichContentCodec.encode(listOf(
            AgentRichBlock(
                id = "input",
                type = AgentRichBlockType.FILE,
                title = "test.xlsx",
                uri = "signalasi-artifact://task/downloads/input/01-test.xlsx"
            ),
            AgentRichBlock(
                id = "output",
                type = AgentRichBlockType.FILE,
                title = "summary.csv",
                uri = "signalasi-artifact://task/outputs/summary.csv"
            )
        ))

        val blocks = AgentRichContentCodec.decode(CodexStyleResponsePolicy.filterAssistantRichOutput(raw))

        assertEquals(1, blocks.size)
        assertEquals("summary.csv", blocks.single().title)
    }

    @Test
    fun assistantTextRemovesToolChatterAndStackFrames() {
        val raw = """
            preparing mcp_fetch
            Useful result
            at com.signalasi.Internal.run(Internal.kt:10)
        """.trimIndent()

        val clean = CodexStyleResponsePolicy.sanitizeAssistantText(raw)

        assertEquals("Useful result", clean)
        assertFalse(clean.contains("mcp_fetch"))
    }

    @Test
    fun phoneRuntimeOutputKeepsTheRunResultAndDropsDuplicateVerification() {
        val raw = listOf(
            "\u5df2\u5199\u597d\u5e76\u5728\u624b\u673a\u672c\u673a Linux \u4e2d\u9a8c\u8bc1\u901a\u8fc7\u3002",
            "",
            "\u8fd0\u884c\u7ed3\u679c\uff1a",
            "",
            "```text",
            "5050",
            "```",
            "",
            "\u9a8c\u8bc1\u7ed3\u679c\uff1a",
            "",
            "```text",
            "\u901a\u8fc7\uff08\u9000\u51fa\u7801 0\uff09",
            "```"
        ).joinToString("\n")

        val clean = CodexStyleResponsePolicy.sanitizeAssistantText(raw)

        assertTrue(clean.contains("5050"))
        assertFalse(clean.contains("\u5df2\u5199\u597d"))
        assertFalse(clean.contains("\u9a8c\u8bc1\u7ed3\u679c"))
        assertFalse(clean.contains("\u9000\u51fa\u7801"))
    }

    @Test
    fun phoneRuntimeRichOutputDropsOnlyTheRedundantSummaryBlocks() {
        val raw = AgentRichContentCodec.encode(listOf(
            AgentRichBlock("heading", AgentRichBlockType.TEXT, text = "\u5df2\u5199\u597d\u5e76\u5728\u624b\u673a\u672c\u673a Linux \u4e2d\u9a8c\u8bc1\u901a\u8fc7\u3002"),
            AgentRichBlock("run-heading", AgentRichBlockType.TEXT, text = "\u8fd0\u884c\u7ed3\u679c\uff1a"),
            AgentRichBlock("run", AgentRichBlockType.CODE, text = "5050", language = "text"),
            AgentRichBlock("verify-heading", AgentRichBlockType.TEXT, text = "\u9a8c\u8bc1\u7ed3\u679c\uff1a"),
            AgentRichBlock("verify", AgentRichBlockType.CODE, text = "\u901a\u8fc7\uff08\u9000\u51fa\u7801 0\uff09", language = "text")
        ))

        val blocks = AgentRichContentCodec.decode(CodexStyleResponsePolicy.filterAssistantRichOutput(raw))

        assertEquals(listOf("run-heading", "run"), blocks.map(AgentRichBlock::id))
    }
}
