package com.galaxyssi.chat.blob

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class BlobOutgoingJournalDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val id = "a".repeat(64)
    private val fingerprint = "f".repeat(64)
    private fun body() = JSONObject().put("desktop_id", "test-desktop-private").put("fingerprint", fingerprint)
        .put("origin", "https://blob.test").put("manifest", JSONObject().put("client_route_id", "route")
            .put("conversation_id", "中文测试会话").put("task_id", "task").put("turn_id", "turn")
            .put("attachment_id", "attachment").put("transfer_id", id).put("contact_id", "contact")
            .put("sha256", "b".repeat(64)).put("size_bytes", 123).put("client_message_id", 7))
    private fun receipt() = JSONObject(body().getJSONObject("manifest").toString())
        .put("status", "stored").put("source_message_id", "7")
    private fun fixture(block: (File) -> Unit) {
        val root = File(context.noBackupFilesDir, "blob-test-${UUID.randomUUID()}").apply { mkdirs() }
        try { block(File(root, "journal.sqlite3")) } finally { root.deleteRecursively() }
    }
    @Test fun encryptedCheckpointReopensWithoutPlaintextRows() = fixture { path ->
        BlobOutgoingJournal(path).use { it.register(id, body(), active = true) }
        val raw = path.readBytes().toString(Charsets.ISO_8859_1)
        assertFalse(raw.contains("test-desktop-private")); assertFalse(raw.contains(fingerprint))
        BlobOutgoingJournal(path).use {
            val work = it.claimDue(Long.MAX_VALUE, 1, emptySet()).single()
            assertEquals("中文测试会话", work.body.getJSONObject("manifest").getString("conversation_id"))
        }
    }
    @Test fun activationDuringClaimDoesNotWaitForOldBackoff() = fixture { path ->
        BlobOutgoingJournal(path).use {
            it.register(id, body(), active = false)
            val old = it.claimDue(10, 1, emptySet()).single()
            assertTrue(old.waitingForTask)
            it.activate(id)
            it.defer(old, 30_000, waiting = true)
            val work = it.claimDue(11, 1, emptySet()).single()
            assertFalse(work.waitingForTask)
        }
    }
    @Test fun storedReceiptSurvivesRestartAndFencesLateUploadCallback() = fixture { path ->
        BlobOutgoingJournal(path).use {
            it.register(id, body(), active = true)
            val upload = it.claimDue(10, 1, emptySet()).single()
            assertFalse(it.stored(id, receipt().put("turn_id", "other"), "test-desktop-private", fingerprint))
            assertTrue(it.stored(id, receipt(), "test-desktop-private", fingerprint))
            it.defer(upload, Long.MAX_VALUE, "late_worker")
        }
        BlobOutgoingJournal(path).use {
            it.recover()
            val clean = it.claimDue(11, 1, emptySet()).single()
            assertEquals(BlobOutgoingJournal.CLEANUP, clean.phase)
            it.finish(clean)
            assertTrue(it.claimDue(Long.MAX_VALUE, 1, emptySet()).isEmpty())
        }
    }
    @Test fun restartClaimsAreBoundedAndExcludeLiveTransfers() = fixture { path ->
        BlobOutgoingJournal(path).use { journal ->
            val ids = (0..11).map { it.toString(16).padStart(64, '0') }
            ids.forEach { journal.register(it, body(), active = true) }
            val first = journal.claimDue(10, 4, emptySet())
            assertEquals(4, first.size)
            journal.recover()
            val next = journal.claimDue(11, 4, first.map { it.id }.toSet())
            assertEquals(4, next.size)
            assertTrue(next.none { it.id in first.map { previous -> previous.id } })
        }
    }
    @Test fun cancelledUploadCannotBeRevivedByLateStoredReceipt() = fixture { path ->
        BlobOutgoingJournal(path).use {
            it.register(id, body(), active = true)
            val upload = it.claimDue(10, 1, emptySet()).single()
            it.cancel(id)
            assertFalse(it.stored(id, receipt(), "test-desktop-private", fingerprint))
            it.defer(upload, Long.MAX_VALUE)
            assertEquals(BlobOutgoingJournal.DISCARD, it.claimDue(11, 1, emptySet()).single().phase)
        }
    }
    @Test fun duplicateIdentityCannotReplaceOriginalManifest() = fixture { path ->
        BlobOutgoingJournal(path).use {
            it.register(id, body(), active = true)
            val changed = body().also { value -> value.getJSONObject("manifest").put("turn_id", "other") }
            assertTrue(runCatching { it.register(id, changed, active = true) }.exceptionOrNull() is BlobFailure)
            assertEquals("turn", it.body(id)!!.getJSONObject("manifest").getString("turn_id"))
        }
    }

    @Test fun failureReceiptSurvivesRestartAndFencesLateSuccess() = fixture { path ->
        val failed = BlobFailureContract.receipt(body().getJSONObject("manifest"), "blob_expired")
        BlobOutgoingJournal(path).use {
            it.register(id, body(), active = true)
            val upload = it.claimDue(10, 1, emptySet()).single()
            assertTrue(it.failedReceipt(id, failed, "test-desktop-private", fingerprint))
            assertFalse(it.stored(id, receipt(), "test-desktop-private", fingerprint))
            it.defer(upload, Long.MAX_VALUE, "late_worker")
        }
        BlobOutgoingJournal(path).use {
            it.recover()
            val failure = it.claimDue(11, 1, emptySet()).single()
            assertEquals(BlobOutgoingJournal.FAILURE, failure.phase)
            assertEquals("blob_expired", failure.body.getJSONObject("receipt").getString("error_code"))
            assertTrue(it.failureObserved(failure))
        }
        BlobOutgoingJournal(path).use {
            it.recover()
            assertTrue(it.failedReceipt(id, failed, "test-desktop-private", fingerprint))
            val cleanup = it.claimDue(12, 1, emptySet()).single()
            assertEquals(BlobOutgoingJournal.FAILED_CLEANUP, cleanup.phase)
            it.finish(cleanup)
            it.activate(id)
            assertTrue(it.claimDue(Long.MAX_VALUE, 1, emptySet()).isEmpty())
            assertFalse(it.stored(id, receipt(), "test-desktop-private", fingerprint))
        }
    }

    @Test fun untrustedOrMisboundFailureCannotChangeUploadState() = fixture { path ->
        BlobOutgoingJournal(path).use {
            it.register(id, body(), active = true)
            val failed = BlobFailureContract.receipt(body().getJSONObject("manifest"), "blob_expired")
            assertFalse(it.failedReceipt(id, failed, "other", fingerprint))
            assertFalse(it.failedReceipt(id, failed, "test-desktop-private", "b".repeat(64)))
            assertFalse(it.failedReceipt(id, JSONObject(failed.toString()).put("turn_id", "other"),
                "test-desktop-private", fingerprint))
            assertFalse(it.failedReceipt(id, JSONObject(failed.toString()).put("error_code", "unknown"),
                "test-desktop-private", fingerprint))
            assertEquals(BlobOutgoingJournal.UPLOAD, it.claimDue(10, 1, emptySet()).single().phase)
        }
    }

    @Test fun localFailureCannotOverwriteReceivedSuccessOrCancellation() = fixture { path ->
        BlobOutgoingJournal(path).use {
            it.register(id, body(), active = true)
            val work = it.claimDue(10, 1, emptySet()).single()
            assertTrue(it.stored(id, receipt(), "test-desktop-private", fingerprint))
            assertFalse(it.fail(work, "source_changed"))
            val failed = BlobFailureContract.receipt(body().getJSONObject("manifest"), "blob_expired")
            assertFalse(it.failedReceipt(id, failed, "test-desktop-private", fingerprint))
            it.cancel(id)
            assertFalse(it.failedReceipt(id, failed, "test-desktop-private", fingerprint))
        }
    }

    @Test fun localFailureIsReplayedUntilObservationIsPersisted() = fixture { path ->
        BlobOutgoingJournal(path).use {
            it.register(id, body(), active = true)
            assertTrue(it.fail(it.claimDue(10, 1, emptySet()).single(), "source_changed"))
            assertEquals(BlobOutgoingJournal.FAILURE, it.claimDue(11, 1, emptySet()).single().phase)
        }
        BlobOutgoingJournal(path).use {
            it.recover()
            val work = it.claimDue(12, 1, emptySet()).single()
            assertEquals(BlobOutgoingJournal.FAILURE, work.phase)
            it.finish(work)
            assertEquals(BlobOutgoingJournal.FAILURE, work.phase)
            assertTrue(it.failureObserved(work))
            assertFalse(it.failureObserved(work))
            assertEquals(BlobOutgoingJournal.FAILED_CLEANUP, it.claimDue(13, 1, emptySet()).single().phase)
        }
    }
}
