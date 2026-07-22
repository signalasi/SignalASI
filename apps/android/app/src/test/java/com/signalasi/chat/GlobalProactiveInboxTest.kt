package com.signalasi.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GlobalProactiveInboxTest {
    @Test
    fun deliveredFindingsBecomeUserVisibleInboxItems() {
        val items = GlobalProactiveInboxPolicy.project(
            messages = listOf(message("current"), message("topic", GlobalProactiveTarget.NEW_CONVERSATION)),
            feedback = emptyList()
        )

        assertEquals(2, items.size)
        assertEquals(2, GlobalProactiveInboxPolicy.newCount(items))
        assertTrue(items.all(GlobalProactiveInboxItem::isNew))
        assertEquals(setOf("destination"), items.map(GlobalProactiveInboxItem::destinationConversationId).toSet())
    }

    @Test
    fun oneDigestDeliveryIsProjectedAsOneFinding() {
        val messages = listOf(
            message("digest-a", GlobalProactiveTarget.GLOBAL_DIGEST).copy(deliveryGroupId = "daily"),
            message("digest-b", GlobalProactiveTarget.GLOBAL_DIGEST).copy(
                deliveryGroupId = "daily",
                topic = "Release risk",
                content = "A second material change is ready."
            )
        )

        val items = GlobalProactiveInboxPolicy.project(messages, emptyList())

        assertEquals(1, items.size)
        assertEquals("global-agent-digest:daily", items.single().key)
        assertEquals(setOf("digest-a", "digest-b"), items.single().messageIds)
        assertTrue(items.single().content.contains("Release risk"))
    }

    @Test
    fun pendingAndDismissedMessagesDoNotAppearAsNewFindings() {
        val pending = message("pending").copy(status = GlobalProactiveMessageStatus.PENDING)
        val dismissed = message("dismissed").copy(status = GlobalProactiveMessageStatus.DISMISSED)

        val items = GlobalProactiveInboxPolicy.project(listOf(pending, dismissed), emptyList())

        assertTrue(items.isEmpty())
    }

    @Test
    fun helpfulFeedbackKeepsFindingButClearsItsNewState() {
        val message = message("helpful")
        val feedback = feedback(message.id, GlobalAgentFeedbackKind.HELPFUL)

        val item = GlobalProactiveInboxPolicy.project(listOf(message), listOf(feedback)).single()

        assertFalse(item.isNew)
        assertEquals(GlobalAgentFeedbackKind.HELPFUL, item.feedbackKind)
    }

    @Test
    fun negativeFeedbackRemovesFindingFromInbox() {
        val irrelevant = message("irrelevant")
        val frequent = message("frequent")

        val items = GlobalProactiveInboxPolicy.project(
            messages = listOf(irrelevant, frequent),
            feedback = listOf(
                feedback(irrelevant.id, GlobalAgentFeedbackKind.NOT_RELEVANT),
                feedback(frequent.id, GlobalAgentFeedbackKind.TOO_FREQUENT)
            )
        )

        assertTrue(items.isEmpty())
    }

    @Test
    fun viewingOnlyChangesSelectedDeliveredMessages() {
        val delivered = message("delivered")
        val untouched = message("untouched")
        val pending = message("pending").copy(status = GlobalProactiveMessageStatus.PENDING)

        val updated = GlobalProactiveInboxPolicy.markViewed(
            messages = listOf(delivered, untouched, pending),
            messageIds = setOf("delivered", "pending"),
            nowMillis = 9_000L
        ).associateBy(GlobalProactiveMessage::id)

        assertEquals(9_000L, updated.getValue("delivered").viewedAtMillis)
        assertEquals(0L, updated.getValue("untouched").viewedAtMillis)
        assertEquals(0L, updated.getValue("pending").viewedAtMillis)
    }

    @Test
    fun legacyProductTitleIsUpdatedWithoutChangingProtocolNames() {
        val legacy = message("legacy").copy(title = "Signal 建议")

        val item = GlobalProactiveInboxPolicy.project(listOf(legacy), emptyList()).single()

        assertEquals("SignalASI 建议", item.title)
        assertEquals("Signal Protocol", GlobalAgentText.productTitle("Signal Protocol"))
    }

    private fun message(
        id: String,
        target: GlobalProactiveTarget = GlobalProactiveTarget.CURRENT_CONVERSATION
    ): GlobalProactiveMessage = GlobalProactiveMessage(
        id = id,
        sourceEventId = "event-$id",
        sourceConversationId = "source",
        target = target,
        title = "SignalASI insight",
        content = "A material result is ready.",
        topic = "SignalASI autonomy",
        urgent = false,
        status = GlobalProactiveMessageStatus.DELIVERED,
        createdAtMillis = 1_000L,
        deliveredAtMillis = 2_000L,
        deliveredConversationId = "destination",
        deliveryGroupId = id
    )

    private fun feedback(
        messageId: String,
        kind: GlobalAgentFeedbackKind
    ): GlobalAgentFeedback = GlobalAgentFeedback(
        proactiveMessageId = messageId,
        deliveryGroupId = messageId,
        conversationId = "destination",
        topic = "SignalASI autonomy",
        target = GlobalProactiveTarget.CURRENT_CONVERSATION,
        kind = kind,
        createdAtMillis = 3_000L
    )
}
