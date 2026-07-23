package com.signalasi.chat

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class AgentTranscriptContextCursorInstrumentedTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val databaseNames = mutableListOf<String>()

    @After
    fun cleanUp() {
        databaseNames.forEach(context::deleteDatabase)
    }

    @Test
    fun readsContextEntriesAfterTheDurableSummaryCursor() {
        val database = database()
        (0 until 8).forEach { index ->
            assertTrue(database.insert(entry(index, timestampMillis = 1_000L)))
        }

        val recent = database.listConversationAfterEntry(
            conversationId = CONVERSATION_ID,
            entryId = "entry-4"
        )

        assertEquals(listOf("entry-5", "entry-6", "entry-7"), recent?.map(AgentTranscriptEntry::id))
        database.close()
    }

    @Test
    fun rejectsAMissingSummaryCursorInsteadOfUsingLegacyTimestampFallback() {
        val database = database()
        assertTrue(database.insert(entry(1, timestampMillis = 1_000L)))
        assertTrue(database.insert(entry(2, timestampMillis = 2_000L)))
        assertTrue(database.insert(entry(3, timestampMillis = 3_000L)))

        val recent = database.listConversationAfterEntry(
            conversationId = CONVERSATION_ID,
            entryId = "deleted-cursor"
        )

        assertEquals(null, recent)
        database.close()
    }

    private fun database(): AgentTranscriptEntryDatabase {
        val name = "signalasi_context_cursor_test_${UUID.randomUUID()}.db"
        databaseNames += name
        return AgentTranscriptEntryDatabase(context, name)
    }

    private fun entry(index: Int, timestampMillis: Long) = AgentTranscriptEntry(
        id = "entry-$index",
        role = if (index % 2 == 0) AgentTranscriptRole.USER else AgentTranscriptRole.ASSISTANT,
        text = "message $index",
        timestampMillis = timestampMillis,
        dedupeKey = "dedupe-$index",
        conversationId = CONVERSATION_ID,
        turnId = "turn-${index / 2}",
        taskId = "task-${index / 2}"
    )

    private companion object {
        const val CONVERSATION_ID = "conversation-main"
    }
}
