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
}
