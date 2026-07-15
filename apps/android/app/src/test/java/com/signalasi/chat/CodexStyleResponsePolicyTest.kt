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
}
