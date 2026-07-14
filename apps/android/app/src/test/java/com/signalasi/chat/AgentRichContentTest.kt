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
}
