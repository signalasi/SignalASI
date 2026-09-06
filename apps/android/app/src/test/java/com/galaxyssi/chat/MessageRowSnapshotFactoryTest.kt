package com.galaxyssi.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test

class MessageRowSnapshotFactoryTest {
    private val contact = Contact("phone:test", "Test phone", "")

    @Test
    fun `delivery metadata changes do not invalidate a visible message row`() {
        val message = message(id = 1L, content = "hello")
        val before = MessageRowSnapshotFactory.from(listOf(message)).single()

        message.deliveryStatus = "delivered"
        message.deliveryTrace += DeliveryTraceEvent("broker_ack")
        message.taskStatus = "completed"

        assertEquals(before, MessageRowSnapshotFactory.from(listOf(message)).single())
    }

    @Test
    fun `attachment progress changes invalidate only its message row`() {
        val first = message(
            id = 1L,
            content = "",
            attachments = listOf(PeerChatAttachment("one.jpg", "image/jpeg", 128L))
        )
        val second = message(id = 2L, content = "unchanged")
        val before = MessageRowSnapshotFactory.from(listOf(first, second))

        first.attachments = first.attachments.map {
            it.copy(transferProgress = 50, transferState = "uploading")
        }
        val after = MessageRowSnapshotFactory.from(listOf(first, second))

        assertNotEquals(before[0], after[0])
        assertEquals(before[1], after[1])
    }

    @Test
    fun `adding a message preserves existing row snapshots`() {
        val first = message(id = 1L, content = "image")
        val before = MessageRowSnapshotFactory.from(listOf(first))
        val after = MessageRowSnapshotFactory.from(
            listOf(first, message(id = 2L, content = "new message"))
        )

        assertEquals(before.single(), after.first())
    }

    @Test
    fun `adapter list snapshot is isolated from structural source mutations`() {
        val source = mutableListOf(message(id = 1L, content = "first"))
        val snapshot = MessageListSnapshot.copy(source)

        source.add(message(id = 2L, content = "second"))

        assertEquals(listOf(1L), snapshot.map(ChatMessage::id))
        assertEquals(listOf(1L, 2L), source.map(ChatMessage::id))
    }

    @Test fun `only percentage change uses lightweight attachment payload`() {
        val attachment = PeerChatAttachment("photo.jpg", "image/jpeg", 1024L,
            transferProgress = 10, transferState = "downloading")
        val before = MessageRowSnapshotFactory.from(listOf(message(1L, "", listOf(attachment)))).single()
        val after = before.copy(attachments = listOf(attachment.copy(transferProgress = 50)))
        assertTrue(MessageRowSnapshotFactory.progressOnly(before, after))
        assertFalse(MessageRowSnapshotFactory.progressOnly(before, before))
    }

    @Test fun `state content uri and membership changes require a full bind`() {
        val attachment = PeerChatAttachment("photo.jpg", "image/jpeg", 1024L,
            transferProgress = 10, transferState = "downloading")
        val before = MessageRowSnapshotFactory.from(listOf(message(1L, "", listOf(attachment)))).single()
        for (after in listOf(before.copy(content = "caption"),
            before.copy(attachments = listOf(attachment.copy(transferProgress = 100, transferState = "complete"))),
            before.copy(attachments = listOf(attachment.copy(transferState = "failed"))),
            before.copy(attachments = listOf(attachment.copy(uri = "content://new", transferProgress = 50))),
            before.copy(attachments = emptyList()))) {
            assertFalse(MessageRowSnapshotFactory.progressOnly(before, after))
        }
    }

    @Test fun `multi attachment progress preserves all other attachment identities`() {
        val one = PeerChatAttachment("one.jpg", "image/jpeg", 1024L, transferProgress = 10, transferState = "downloading")
        val two = PeerChatAttachment("two.pdf", "application/pdf", 2048L, transferProgress = 20, transferState = "downloading")
        val before = MessageRowSnapshotFactory.from(listOf(message(1L, "", listOf(one, two)))).single()
        val after = before.copy(attachments = listOf(one, two.copy(transferProgress = 40)))
        assertTrue(MessageRowSnapshotFactory.progressOnly(before, after))
        assertFalse(MessageRowSnapshotFactory.progressOnly(before, after.copy(attachments = after.attachments.reversed())))
    }

    private fun message(
        id: Long,
        content: String,
        attachments: List<PeerChatAttachment> = emptyList()
    ) = ChatMessage(
        id = id,
        content = content,
        isMine = true,
        contact = contact,
        timestamp = 1_000L + id,
        attachments = attachments
    )
}
