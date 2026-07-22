package com.signalasi.chat

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class GlobalConversationContextJournalInstrumentedTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Before
    fun resetBefore() {
        GlobalAgentRepository(context).clear()
    }

    @After
    fun resetAfter() {
        GlobalAgentRepository(context).clear()
    }

    @Test
    fun contextSurvivesQueueProcessingAndEncryptedSnapshotRestore() {
        val repository = GlobalAgentRepository(context)
        repository.saveSettings(GlobalAgentSettings(enabled = true))
        val first = event("message-1", "Implement the Android runtime", 1_000L)
        val anchor = event("message-2", "Continue with that plan", 2_000L)
        assertTrue(repository.enqueueAll(listOf(first, anchor)) == 2)

        repository.removeEvents(setOf(first.id, anchor.id))
        assertTrue(repository.pendingEvents().isEmpty())
        val beforeBackup = repository.recentConversationContext(anchor)
        assertTrue(beforeBackup.contains("Implement the Android runtime"))
        assertFalse(beforeBackup.contains("Continue with that plan"))

        val snapshot = repository.exportSnapshot()
        assertTrue(snapshot.optInt("version") >= CONTEXT_JOURNAL_SNAPSHOT_VERSION)
        assertEquals(2, snapshot.getJSONArray("context_journal").length())
        repository.clear()
        repository.restoreSnapshot(snapshot)

        assertEquals(beforeBackup, repository.recentConversationContext(anchor))
    }

    @Test
    fun historyExclusionPurgesPersistedContextBeforeBackgroundProcessing() {
        val repository = GlobalAgentRepository(context)
        repository.saveSettings(GlobalAgentSettings(enabled = true))
        val original = event("message-1", "Remove this history", 1_000L)
        repository.enqueue(original)
        repository.enqueue(GlobalConversationEvent(
            id = "history-cleared",
            type = GlobalConversationEventType.CONVERSATION_UPDATED,
            conversationId = original.conversationId,
            actor = GlobalConversationActor.SYSTEM,
            timestampMillis = 2_000L,
            metadata = mapOf("global_visibility" to "excluded")
        ))

        val contextBlock = repository.recentConversationContext(
            conversationId = original.conversationId,
            beforeOrAtMillis = 3_000L
        )
        assertTrue(contextBlock.isBlank())
    }

    @Test
    fun restoringLegacySnapshotDoesNotRetainCurrentConversationContext() {
        val repository = GlobalAgentRepository(context)
        repository.saveSettings(GlobalAgentSettings(enabled = true))
        val original = event("message-1", "Do not retain this after restore", 1_000L)
        repository.enqueue(original)
        assertTrue(repository.recentConversationContext(
            conversationId = original.conversationId,
            beforeOrAtMillis = 2_000L
        ).isNotBlank())

        repository.restoreSnapshot(org.json.JSONObject().put("version", 13))

        assertTrue(repository.recentConversationContext(
            conversationId = original.conversationId,
            beforeOrAtMillis = 2_000L
        ).isBlank())
    }

    private fun event(id: String, content: String, timestampMillis: Long) = GlobalConversationEvent(
        id = id,
        type = GlobalConversationEventType.MESSAGE_CREATED,
        conversationId = "conversation-a",
        actor = GlobalConversationActor.USER,
        timestampMillis = timestampMillis,
        content = content,
        conversationTitle = "SignalASI"
    )

    companion object {
        private const val CONTEXT_JOURNAL_SNAPSHOT_VERSION = 14
    }
}
