package com.galaxyssi.chat

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class LinkTransportReceiptDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private fun receipt(message: String = "message", phone: Boolean = false) =
        LinkTransportReceipt("private-peer", phone, "a".repeat(64), message)

    private fun withJournal(test: (LinkTransportReceiptJournal) -> Unit) {
        val name = "receipt-test-${UUID.randomUUID()}.db"
        try { LinkTransportReceiptJournal(context, name).use(test) }
        finally { context.deleteDatabase(name) }
    }

    @Test fun processRestartRecoversPreparedCiphertextBeforeBrokerAck() {
        val name = "receipt-restart-${UUID.randomUUID()}.db"
        val receipt = receipt()
        try {
            val previous = LinkTransportReceiptJournal(context, name).use {
                it.enqueue(receipt, 100)
                val work = it.claim(receipt.key, 100)!!
                assertTrue(it.prepared(work, "original-signal-ciphertext"))
                work.attempt
            }
            LinkTransportReceiptJournal(context, name).use {
                assertTrue(it.due(101).isEmpty())
                it.reconnect(101)
                assertEquals(listOf(receipt.key), it.due(101))
                val resumed = it.claim(receipt.key, 101)!!
                assertEquals("original-signal-ciphertext", resumed.wire)
                assertNotEquals(previous.token, resumed.attempt.token)
                assertFalse(it.acknowledge(previous))
                assertTrue(it.acknowledge(resumed.attempt))
                assertNull(it.nextDue())
            }
        } finally { context.deleteDatabase(name) }
    }

    @Test fun crashBeforeEncryptionRetainsReceiptIntent() {
        val name = "receipt-intent-${UUID.randomUUID()}.db"
        try {
            LinkTransportReceiptJournal(context, name).use { it.enqueue(receipt(), 10) }
            LinkTransportReceiptJournal(context, name).use {
                val work = it.claim(receipt().key, 10)!!
                assertEquals(receipt(), work.receipt)
                assertEquals("", work.wire)
            }
        } finally { context.deleteDatabase(name) }
    }

    @Test fun replayDoesNotReplaceInflightCiphertextOrExtendRetry() = withJournal {
        val receipt = receipt()
        it.enqueue(receipt, 100)
        val first = it.claim(receipt.key, 100)!!
        it.prepared(first, "ciphertext")
        val wake = it.nextDue()
        repeat(20) { _ -> it.enqueue(receipt, 101) }
        assertEquals(wake, it.nextDue())
        assertTrue(it.acknowledge(first.attempt))
        assertNull(it.nextDue())
    }

    @Test fun lateBrokerAckCannotRetireNewReplayOfSameMessage() = withJournal {
        val receipt = receipt()
        it.enqueue(receipt, 10)
        val old = it.claim(receipt.key, 10)!!
        assertTrue(it.acknowledge(old.attempt))
        it.enqueue(receipt, 20)
        val fresh = it.claim(receipt.key, 20)!!
        assertFalse(it.acknowledge(old.attempt))
        assertTrue(it.acknowledge(fresh.attempt))
    }

    @Test fun claimingWithoutAckKeepsRetryableWork() = withJournal {
        it.enqueue(receipt(), 100)
        it.claim(receipt().key, 100)
        assertTrue(it.due(101).isEmpty())
        assertEquals(listOf(receipt().key), it.due(100 + LinkTransportReceiptJournal.RETRY_MILLIS))
    }

    @Test fun receiptDrainIsPagedAndPhoneReceiptsRemainIndependent() = withJournal {
        repeat(100) { index -> it.enqueue(receipt("message-$index", index % 2 == 0), index.toLong()) }
        var count = 0
        while (true) {
            val page = it.due(200)
            if (page.isEmpty()) break
            assertTrue(page.size <= 4)
            page.forEach { key -> assertTrue(it.acknowledge(it.claim(key, 200)!!.attempt)); count++ }
        }
        assertEquals(100, count)
        assertNull(it.nextDue())
    }

    @Test fun rowsDoNotExposePeerMessageOrWireAndRejectTampering() = withJournal {
        val receipt = receipt()
        it.enqueue(receipt, 10)
        val work = it.claim(receipt.key, 10)!!
        it.prepared(work, "private-wire-content")
        val raw = it.readableDatabase.rawQuery("SELECT payload FROM receipts", null).use { cursor ->
            assertTrue(cursor.moveToFirst()); cursor.getString(0)
        }
        assertFalse(raw.contains("private-peer"))
        assertFalse(raw.contains("private-wire-content"))
        it.writableDatabase.execSQL("UPDATE receipts SET payload = 'tampered'")
        it.reconnect(11)
        assertThrows(IllegalStateException::class.java) { it.claim(receipt.key, 11) }
        assertTrue(it.due(12).isEmpty())
    }

    @Test fun oversizedPreparedReceiptIsRejectedWithoutDroppingIntent() = withJournal {
        it.enqueue(receipt(), 10)
        val work = it.claim(receipt().key, 10)!!
        assertThrows(IllegalArgumentException::class.java) { it.prepared(work, "x".repeat(65537)) }
        it.reconnect(11)
        assertEquals("", it.claim(receipt().key, 11)!!.wire)
    }
}
