package com.signalasi.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentRichFormatRegistryTest {
    @Test
    fun classifiesCommonOutputFamiliesFromMimeOrExtension() {
        val examples = listOf(
            Triple("image/png", "https://example.com/a.bin", AgentRichFormatFamily.IMAGE),
            Triple("", "content://files/movie.mp4", AgentRichFormatFamily.VIDEO),
            Triple("", "file:///tmp/voice.opus", AgentRichFormatFamily.AUDIO),
            Triple("", "file:///tmp/report.xlsx", AgentRichFormatFamily.DOCUMENT),
            Triple("", "file:///tmp/source.zip", AgentRichFormatFamily.ARCHIVE),
            Triple("application/json", "content://files/result", AgentRichFormatFamily.STRUCTURED_DATA),
            Triple("text/plain", "content://files/readme", AgentRichFormatFamily.CODE)
        )

        examples.forEach { (mime, uri, expected) ->
            assertEquals(expected, AgentRichFormatRegistry.describe(mime, uri).family)
        }
    }

    @Test
    fun unknownFormatsKeepAStableExternalFallback() {
        val descriptor = AgentRichFormatRegistry.describe(
            mimeType = "application/x-future-signalasi",
            uri = "content://files/result.sasi-next"
        )

        assertEquals(AgentRichFormatFamily.UNKNOWN, descriptor.family)
        assertFalse(descriptor.canPreviewInline)
        assertTrue(descriptor.canOpenExternally)
        assertEquals(".sasi-next", descriptor.extension)
    }

    @Test
    fun mediaFileBlocksArePromotedToNativeRenderers() {
        assertEquals(
            AgentRichBlockType.IMAGE,
            AgentRichFormatRegistry.normalizedType(
                AgentRichBlockType.FILE,
                "https://example.com/photo.webp?size=small",
                "image/webp"
            )
        )
        assertEquals(
            AgentRichBlockType.AUDIO,
            AgentRichFormatRegistry.normalizedType(AgentRichBlockType.UNKNOWN, "file:///voice.m4a", "")
        )
    }

    @Test
    fun preservesSafeFileNamesWithQueries() {
        val name = AgentRichFormatRegistry.fileName(AgentRichBlock(
            id = "file",
            type = AgentRichBlockType.FILE,
            uri = "https://example.com/downloads/report%20final.pdf?token=redacted"
        ))

        assertEquals("report final.pdf", name)
    }
}
