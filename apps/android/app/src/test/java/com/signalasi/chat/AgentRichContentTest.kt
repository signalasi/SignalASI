package com.signalasi.chat

import org.junit.Assert.assertEquals
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

    @Test
    fun parsesListsChecklistsQuotesAndDividersAsDocumentBlocks() {
        val blocks = AgentRichContentCodec.fromText(
            """
            ## Release checklist

            - [x] Build
            - [ ] Verify

            > Keep the rollout reversible.
            > Record the result.

            ---

            1. Stage
            2. Ship
            """.trimIndent()
        )

        assertEquals("2", blocks.first { it.type == AgentRichBlockType.HEADING }.metadata["level"])
        assertEquals("checklist", blocks.first { it.type == AgentRichBlockType.LIST }.metadata["style"])
        assertTrue(blocks.first { it.type == AgentRichBlockType.QUOTE }.text.contains("Record the result"))
        assertTrue(blocks.any { it.type == AgentRichBlockType.DIVIDER })
        assertTrue(blocks.any { it.type == AgentRichBlockType.LIST && it.metadata["style"] == "ordered" })
    }

    @Test
    fun recognizesAndFormatsStandaloneJson() {
        val block = AgentRichContentCodec.fromText("{\"ready\":true,\"count\":2}").single()

        assertEquals(AgentRichBlockType.JSON, block.type)
        assertEquals("json", block.language)
        assertTrue(block.text.contains("\n"))
    }

    @Test
    fun preservesExtensibleMetadataAcrossCodecRoundTrip() {
        val encoded = AgentRichContentCodec.encode(listOf(AgentRichBlock(
            id = "notice",
            type = AgentRichBlockType.NOTICE,
            title = "Ready",
            text = "The result is available.",
            metadata = mapOf("style" to "success", "source" to "phone")
        )))

        val block = AgentRichContentCodec.decode(encoded).single()
        assertEquals("success", block.metadata["style"])
        assertEquals("phone", block.metadata["source"])
    }

    @Test
    fun preservesEncryptedInlineImageAcrossCodecRoundTrip() {
        val encoded = AgentRichContentCodec.encode(listOf(AgentRichBlock(
            id = "marked-homework",
            type = AgentRichBlockType.IMAGE,
            title = "marked.jpg",
            dataB64 = "aW1hZ2U=",
            mimeType = "image/jpeg",
            metadata = mapOf("transport" to "encrypted-inline")
        )))

        val block = AgentRichContentCodec.decode(encoded).single()
        assertEquals("aW1hZ2U=", block.dataB64)
        assertEquals("encrypted-inline", block.metadata["transport"])
    }

    @Test
    fun duplicateArtifactCardsKeepOnlyThePreviewableImage() {
        val digest = "a".repeat(64)
        val encoded = """
            {
              "version": 1,
              "blocks": [
                {
                  "id": "relative-only",
                  "type": "file",
                  "title": "marked.jpg",
                  "uri": "outputs/marked.jpg",
                  "metadata": {"sha256": "$digest"}
                },
                {
                  "id": "previewable",
                  "type": "image",
                  "title": "marked.jpg",
                  "uri": "signalasi-artifact://task/outputs/marked.jpg",
                  "data_b64": "aW1hZ2U=",
                  "mime_type": "image/jpeg",
                  "metadata": {"sha256": "$digest"}
                }
              ]
            }
        """.trimIndent()

        val block = AgentRichContentCodec.decode(encoded).single()

        assertEquals("previewable", block.id)
        assertEquals(AgentRichBlockType.IMAGE, block.type)
        assertEquals("aW1hZ2U=", block.dataB64)
    }

    @Test
    fun phoneRuntimeResultIncludesOnePreviewableSavableArtifactCard() {
        val rich = AgentRuntimeArtifactUi.richOutput(
            output = mapOf(
                "artifacts" to listOf(mapOf(
                    "relative_path" to "sample.py",
                    "host_path" to "C:/private/agent-native-workspaces/session/sample.py",
                    "size_bytes" to 42L,
                    "sha256" to "a".repeat(64),
                    "artifact_kind" to "file"
                ))
            ),
            responseText = "Written and verified.\n\nRun output:\n\n```text\n42\n```",
            preferredFileName = "sample.py",
            zh = false
        )

        val blocks = AgentRichContentCodec.decode(rich)
        val file = blocks.single { it.type == AgentRichBlockType.FILE }
        assertEquals("sample.py", file.title)
        assertEquals(listOf("preview_runtime_artifact", "save_runtime_artifact"), file.actions.map { it.verb })
        assertTrue(blocks.any { it.type == AgentRichBlockType.CODE && it.text == "42" })
    }

    @Test
    fun malformedOrFutureDocumentsFailClosed() {
        assertTrue(AgentRichContentCodec.decode("not-json").isEmpty())
        assertTrue(AgentRichContentCodec.decode("{\"version\":99,\"blocks\":[]}").isEmpty())
    }
}
