package com.signalasi.chat

import org.junit.Assert.assertTrue
import org.junit.Test

class AgentRichContentTest {
    @Test
    fun parsesCodeAndMarkdownTableFallback() {
        val blocks = AgentRichContentCodec.fromText(
            """
            # Result

            | Name | State |
            | --- | --- |
            | Build | Passed |

            ```kotlin
            val ready = true
            ```
            """.trimIndent()
        )

        assertTrue(blocks.any { it.type == AgentRichBlockType.HEADING })
        assertTrue(blocks.any { it.type == AgentRichBlockType.TABLE && it.rows.size == 1 })
        assertTrue(blocks.any { it.type == AgentRichBlockType.CODE && it.language == "kotlin" })
    }

    @Test
    fun preservesSelfContainedHtmlAnimationBlocks() {
        val encoded = AgentRichContentCodec.encode(listOf(
            AgentRichBlock(
                id = "animation",
                type = AgentRichBlockType.HTML,
                title = "Animated result",
                text = "<div class='dot'></div><style>.dot{animation:pulse 1s infinite}</style>",
                fallbackText = "Animated explanation"
            )
        ))

        val block = AgentRichContentCodec.decode(encoded).single()
        assertTrue(block.type == AgentRichBlockType.HTML)
        assertTrue(block.text.contains("animation:pulse"))
        assertTrue(block.fallbackText == "Animated explanation")
    }
}
