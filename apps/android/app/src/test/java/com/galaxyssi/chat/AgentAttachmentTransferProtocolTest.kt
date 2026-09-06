package com.galaxyssi.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test

class AgentAttachmentTransferProtocolTest {
    @Test
    fun transferIdentityIsStableAndBoundToTaskScope() {
        val scope = scope()
        val digest = AgentAttachmentTransferProtocol.sha256("content".toByteArray())

        val first = AgentAttachmentTransferProtocol.transferId(scope, "attachment", digest)
        val repeated = AgentAttachmentTransferProtocol.transferId(scope, "attachment", digest)
        val anotherTurn = AgentAttachmentTransferProtocol.transferId(
            scope.copy(turnId = "turn-two"),
            "attachment",
            digest
        )

        assertEquals(64, first.length)
        assertEquals(first, repeated)
        assertNotEquals(first, anotherTurn)
    }

    @Test
    fun missingChunkRangesRoundTripWithoutPerChunkOverhead() {
        val missing = listOf(0, 1, 2, 5, 7, 8, 9)

        val encoded = AgentAttachmentTransferProtocol.missingRanges(missing)
        val decoded = AgentAttachmentTransferProtocol.expandMissingRanges(encoded, 10)

        assertEquals("[[0,2],[5,5],[7,9]]", encoded.toString())
        assertEquals(missing, decoded)
    }

    @Test
    fun freshRecoveryRequestGetsIndependentTransferButDuplicateRemainsIdempotent() {
        val scope = scope()
        val digest = AgentAttachmentTransferProtocol.sha256("content".toByteArray())
        val initial = AgentAttachmentTransferProtocol.transferId(scope, "attachment", digest)
        val first = AgentAttachmentTransferProtocol.transferId(scope.copy(attachmentRequestId = "a".repeat(32)), "attachment", digest)
        val duplicate = AgentAttachmentTransferProtocol.transferId(scope.copy(attachmentRequestId = "a".repeat(32)), "attachment", digest)
        val next = AgentAttachmentTransferProtocol.transferId(scope.copy(attachmentRequestId = "b".repeat(32)), "attachment", digest)
        assertNotEquals(initial, first)
        assertEquals(first, duplicate)
        assertNotEquals(first, next)
    }

    @Test
    fun missingChunkRangesRejectOutOfBoundsRequests() {
        val encoded = AgentAttachmentTransferProtocol.missingRanges(listOf(0, 3))

        assertThrows(IllegalArgumentException::class.java) {
            AgentAttachmentTransferProtocol.expandMissingRanges(encoded, 3)
        }
    }

    @Test
    fun maximumAttachmentFitsExactlyWithinBoundedChunks() {
        assertEquals(
            AgentOutboundAttachmentTransferStore.MAX_ATTACHMENT_BYTES,
            AgentOutboundAttachmentTransferStore.CHUNK_BYTES.toLong() *
                AgentOutboundAttachmentTransferStore.MAX_CHUNKS
        )
    }

    @Test
    fun onlyCurrentChunkSizeIsAccepted() {
        assertTrue(AgentOutboundAttachmentTransferStore.isSupportedChunkSize(256 * 1024))
        assertFalse(AgentOutboundAttachmentTransferStore.isSupportedChunkSize(16 * 1024))
        assertFalse(AgentOutboundAttachmentTransferStore.isSupportedChunkSize(64 * 1024))
    }

    private fun scope() = AgentAttachmentTransferScope(
        contactId = "codex",
        desktopId = "desktop",
        clientRouteId = GalaxySSILinkProtocol.newRouteId(),
        conversationId = "conversation",
        taskId = "task",
        turnId = "turn-one",
        clientMessageId = 42L
    )
}
