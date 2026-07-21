package com.signalasi.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GlobalEventPublisherContractTest {
    @Test
    fun everyGlobalEventTypeHasExactlyOneCanonicalPublisher() {
        val audit = GlobalEventPublisherContract.audit()

        assertTrue(audit.toString(), audit.complete)
        assertEquals(
            GlobalConversationEventType.values().toSet(),
            GlobalEventPublisherContract.descriptors.flatMap { it.eventTypes }.toSet()
        )
    }

    @Test
    fun requiredSemanticInputsAreRepresentedByTheContract() {
        val semanticClasses = GlobalEventPublisherContract.descriptors
            .mapTo(mutableSetOf(), GlobalEventPublisherDescriptor::semanticClass)

        assertTrue(GlobalSemanticEventClass.MESSAGE in semanticClasses)
        assertTrue(GlobalSemanticEventClass.FILE in semanticClasses)
        assertTrue(GlobalSemanticEventClass.DECISION in semanticClasses)
        assertTrue(GlobalSemanticEventClass.TASK in semanticClasses)
        assertTrue(GlobalSemanticEventClass.TOOL in semanticClasses)
        assertTrue(GlobalSemanticEventClass.FEEDBACK in semanticClasses)
    }

    @Test
    fun hostAddsCanonicalPublisherMetadataAndRejectsSpoofing() {
        val normalized = GlobalConversationEventPolicy.normalize(event().copy(metadata = mapOf(
            GlobalEventPublisherContract.METADATA_PUBLISHER_ID to "untrusted.publisher",
            GlobalEventPublisherContract.METADATA_SEMANTIC_CLASS to "untrusted",
            "origin" to "transcript"
        )))!!

        assertEquals("conversation.message", normalized.metadata[GlobalEventPublisherContract.METADATA_PUBLISHER_ID])
        assertEquals("message", normalized.metadata[GlobalEventPublisherContract.METADATA_SEMANTIC_CLASS])
        assertEquals(GlobalEventPublisherContract.SCHEMA_VERSION, normalized.metadata[GlobalEventPublisherContract.METADATA_SCHEMA_VERSION])
        assertEquals("transcript", normalized.metadata["origin"])
    }

    @Test
    fun deniedContentNeverEntersTheGlobalStreamButRevocationEventsDo() {
        assertNull(GlobalConversationEventPolicy.normalize(event().copy(metadata = mapOf(
            "authorization_state" to "denied"
        ))))
        assertNull(GlobalConversationEventPolicy.normalize(event().copy(metadata = mapOf(
            "tracking_paused" to "true"
        ))))

        val revocation = GlobalConversationEventPolicy.normalize(event(
            GlobalConversationEventType.AUTHORIZATION_REVOKED
        ).copy(
            actor = GlobalConversationActor.SYSTEM,
            metadata = mapOf("authorization_state" to "revoked")
        ))
        assertTrue(revocation != null)
        assertFalse(revocation!!.metadata.containsKey("api_key"))
    }

    private fun event(
        type: GlobalConversationEventType = GlobalConversationEventType.MESSAGE_CREATED
    ) = GlobalConversationEvent(
        id = "event-id",
        type = type,
        conversationId = "conversation-id",
        actor = GlobalConversationActor.USER,
        content = "content"
    )
}
