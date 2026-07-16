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

    @Test
    fun promotesFirstMarkdownHttpsLinkToInlineWebPage() {
        val blocks = AgentRichContentCodec.fromText(
            "Open [animated result](https://example.com/animation) in the output area."
        )

        val page = blocks.single { it.type == AgentRichBlockType.WEBPAGE }
        assertTrue(page.title == "animated result")
        assertTrue(page.uri == "https://example.com/animation")
    }

    @Test
    fun doesNotEmbedInsecureMarkdownLinks() {
        val blocks = AgentRichContentCodec.fromText("Open [result](http://example.com).")

        assertTrue(blocks.none { it.type == AgentRichBlockType.WEBPAGE })
    }

    @Test
    fun doesNotExpandMultiLinkResultListsIntoAWebPage() {
        val blocks = AgentRichContentCodec.fromText(
            "Latest news:\n- [One](https://example.com/one)\n- [Two](https://example.com/two)"
        )

        assertTrue(blocks.none { it.type == AgentRichBlockType.WEBPAGE })
    }

    @Test
    fun correctsMislabelledWebPageGifToImage() {
        val encoded = """{"version":1,"blocks":[{"type":"webpage","uri":"https://cdn.example.com/character.gif"}]}"""

        val block = AgentRichContentCodec.decode(encoded).single()

        assertTrue(block.type == AgentRichBlockType.IMAGE)
        assertTrue(AgentRichContentCodec.fallbackText(encoded).endsWith("character.gif"))
    }
}
