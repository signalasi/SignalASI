package com.galaxyssi.chat

import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException
import org.junit.Assert.*
import org.junit.Test

class AttachmentControlInboxTest {
    @Test fun completionWaitsForBackgroundPersistence() {
        val queue = mutableListOf<Runnable>()
        val inbox = AttachmentControlInbox(Executor(queue::add))
        val events = mutableListOf<String>()
        inbox.enqueue("receipt", { events += "persisted" }, { events += "completed" }, { throw it })
        assertTrue(events.isEmpty())
        queue.single().run()
        assertEquals(listOf("persisted", "completed"), events)
    }

    @Test fun failedPersistenceRetainsReceiptAndAllowsReplay() {
        val inbox = AttachmentControlInbox(Executor(Runnable::run))
        val events = mutableListOf<String>()
        inbox.enqueue("receipt", { error("disk unavailable") }, { events += "lost" }, { events += "retained" })
        inbox.enqueue("receipt", { events += "persisted" }, { events += "completed" }, { throw it })
        assertEquals(listOf("retained", "persisted", "completed"), events)
    }

    @Test fun replayCannotRunSameControlTwiceWhileQueued() {
        val queue = mutableListOf<Runnable>()
        val inbox = AttachmentControlInbox(Executor(queue::add))
        repeat(3) { inbox.enqueue("receipt", {}, {}, { throw it }) }
        assertEquals(1, queue.size)
        queue.single().run()
        inbox.enqueue("receipt", {}, {}, { throw it })
        assertEquals(2, queue.size)
    }

    @Test fun failedCompletionMarkerAllowsIdempotentHandlerToReplay() {
        val inbox = AttachmentControlInbox(Executor(Runnable::run))
        var handled = 0
        var completed = 0
        var failed = 0
        inbox.enqueue("receipt", { handled++ }, { error("inbox unavailable") }, { failed++ })
        inbox.enqueue("receipt", { handled++ }, { completed++ }, { throw it })
        assertEquals(2, handled); assertEquals(1, completed); assertEquals(1, failed)
    }

    @Test fun executorRejectionDoesNotPoisonReplayIdentity() {
        var rejected = true
        val inbox = AttachmentControlInbox(Executor { if (rejected) throw RejectedExecutionException() else it.run() })
        var failures = 0
        var completed = 0
        inbox.enqueue("receipt", {}, { completed++ }, { failures++ })
        rejected = false
        inbox.enqueue("receipt", {}, { completed++ }, { throw it })
        assertEquals(1, failures); assertEquals(1, completed)
    }
}
