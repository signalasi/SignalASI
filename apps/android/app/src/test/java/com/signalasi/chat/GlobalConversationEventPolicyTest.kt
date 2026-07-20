package com.signalasi.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GlobalConversationEventPolicyTest {
    @Test
    fun everyEventTypeHasAValidBoundedEnvelope() {
        val normalized = GlobalConversationEventType.values().map { type ->
            GlobalConversationEventPolicy.normalize(event(type = type))
        }

        assertTrue(normalized.all { it != null })
        assertEquals(GlobalConversationEventType.values().toSet(), normalized.mapNotNull { it?.type }.toSet())
    }

    @Test
    fun sessionPrivateEnvelopeRetainsOnlyLifecycleIdentity() {
        val normalized = GlobalConversationEventPolicy.normalize(event(
            type = GlobalConversationEventType.CONVERSATION_UPDATED
        ).copy(
            sensitivity = GlobalConversationSensitivity.SESSION_PRIVATE,
            content = "private content",
            contentRef = "file:///private/item.txt?token=secret",
            conversationTitle = "Private project",
            topicHints = setOf("Private project"),
            metadata = mapOf(
                "global_visibility" to "excluded",
                "private_mode" to "true",
                "resource_name" to "private-item.txt",
                "api_key" to "secret"
            )
        ))!!

        assertEquals("", normalized.content)
        assertEquals("", normalized.contentRef)
        assertEquals("", normalized.conversationTitle)
        assertTrue(normalized.topicHints.isEmpty())
        assertEquals(mapOf("global_visibility" to "excluded", "private_mode" to "true"), normalized.metadata)
    }

    @Test
    fun sessionPrivateContentEventsAreRejectedBeforeGlobalPersistence() {
        val privateTypes = setOf(
            GlobalConversationEventType.MESSAGE_CREATED,
            GlobalConversationEventType.ATTACHMENT_ADDED,
            GlobalConversationEventType.TOOL_RESULT,
            GlobalConversationEventType.ARTIFACT_CREATED
        )

        privateTypes.forEach { type ->
            assertNull(GlobalConversationEventPolicy.normalize(event(type).copy(
                sensitivity = GlobalConversationSensitivity.SESSION_PRIVATE
            )))
        }
    }

    @Test
    fun credentialMetadataIsRemovedWithoutHidingAuthorizationState() {
        val normalized = GlobalConversationEventPolicy.normalize(event().copy(metadata = mapOf(
            "authorization_scope" to "microphone",
            "authorization_state" to "granted",
            "api_key" to "secret-key",
            "provider_access_token" to "secret-token",
            "password" to "secret-password",
            "origin" to "capability"
        )))!!

        assertEquals("microphone", normalized.metadata["authorization_scope"])
        assertEquals("granted", normalized.metadata["authorization_state"])
        assertEquals("capability", normalized.metadata["origin"])
        assertFalse("api_key" in normalized.metadata)
        assertFalse("provider_access_token" in normalized.metadata)
        assertFalse("password" in normalized.metadata)
    }

    @Test
    fun eventPayloadAndEvidenceSetsAreBoundedDeterministically() {
        val normalized = GlobalConversationEventPolicy.normalize(event().copy(
            id = "i".repeat(700),
            conversationId = "c".repeat(700),
            content = "x".repeat(13_000),
            conversationTitle = "t".repeat(300),
            topicHints = (1..30).map { "topic-$it" }.toSet(),
            metadata = (1..70).associate { "key-$it" to "v".repeat(1_200) },
            causalEventIds = (1..150).map { "cause-$it" }.toSet(),
            retractedEventIds = (1..150).map { "retracted-$it" }.toSet()
        ))!!

        assertEquals(512, normalized.id.length)
        assertEquals(512, normalized.conversationId.length)
        assertEquals(12_000, normalized.content.length)
        assertEquals(160, normalized.conversationTitle.length)
        assertEquals(16, normalized.topicHints.size)
        assertEquals(48, normalized.metadata.size)
        assertTrue(normalized.metadata.values.all { it.length <= 1_024 })
        assertEquals(128, normalized.causalEventIds.size)
        assertEquals(128, normalized.retractedEventIds.size)
    }

    @Test
    fun nonEncryptedContentReferenceDropsQueryCredentials() {
        val normalized = GlobalConversationEventPolicy.normalize(event().copy(
            contentRef = "https://example.test/report.pdf?token=secret#page=2"
        ))!!

        assertEquals("https://example.test/report.pdf", normalized.contentRef)
    }

    @Test
    fun invalidIdentityIsRejectedBeforePersistence() {
        assertNull(GlobalConversationEventPolicy.normalize(event().copy(id = "")))
        assertNull(GlobalConversationEventPolicy.normalize(event().copy(conversationId = "")))
    }

    private fun event(
        type: GlobalConversationEventType = GlobalConversationEventType.MESSAGE_CREATED
    ) = GlobalConversationEvent(
        id = "event-id",
        type = type,
        conversationId = "conversation-id",
        messageId = "message-id",
        actor = GlobalConversationActor.USER,
        content = "content",
        contentRef = "encrypted://event/content",
        conversationTitle = "Topic",
        topicHints = setOf("Topic")
    )
}
