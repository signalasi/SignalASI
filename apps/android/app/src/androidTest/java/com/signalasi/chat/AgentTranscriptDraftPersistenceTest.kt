package com.signalasi.chat

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AgentTranscriptDraftPersistenceTest {
    @Test
    fun emptyDraftSurvivesStoreRecreationWithoutEnteringHistory() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val firstStore = AgentTranscriptStore(context)
        firstStore.clear()

        val previous = firstStore.createConversation()
        firstStore.append(AgentTranscriptRole.USER, "Previous message", conversationId = previous.id)
        val draft = firstStore.createConversation()

        assertTrue(firstStore.list(draft.id).isEmpty())
        assertFalse(firstStore.conversations(includeArchived = true).any { it.id == draft.id })

        val recreatedStore = AgentTranscriptStore(context)
        assertEquals(draft.id, recreatedStore.activeConversation().id)
        assertTrue(recreatedStore.list().isEmpty())
        assertFalse(recreatedStore.conversations(includeArchived = true).any { it.id == draft.id })

        recreatedStore.append(AgentTranscriptRole.USER, "First new message", conversationId = draft.id)
        assertEquals(draft.id, AgentTranscriptStore(context).activeConversation().id)
        assertTrue(AgentTranscriptStore(context).conversations(includeArchived = true).any { it.id == draft.id })
    }

    @Test
    fun agentTopicOwnershipAndDeliveryDedupeSurviveStoreRecreation() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val store = AgentTranscriptStore(context)
        store.clear()
        val topicKey = GlobalProactiveConversationRouter.topicKey("Durable project")
        val conversation = store.createAgentConversation(
            title = "Durable project",
            parentConversationId = "source",
            globalTopicKey = topicKey
        )

        assertTrue(store.append(
            role = AgentTranscriptRole.ASSISTANT,
            text = "First proactive result",
            dedupeKey = "global-agent:message-1",
            conversationId = conversation.id
        ))
        assertFalse(store.append(
            role = AgentTranscriptRole.ASSISTANT,
            text = "Duplicate proactive result",
            dedupeKey = "global-agent:message-1",
            conversationId = conversation.id
        ))
        assertTrue(store.renameConversation(conversation.id, "Renamed project"))

        val recreated = AgentTranscriptStore(context)
        val resolved = recreated.agentConversationForTopic(
            title = "Durable project",
            globalTopicKey = topicKey
        )
        assertEquals(conversation.id, resolved?.id)
        assertEquals(1, recreated.list(conversation.id).count { it.dedupeKey == "global-agent:message-1" })
    }
}
