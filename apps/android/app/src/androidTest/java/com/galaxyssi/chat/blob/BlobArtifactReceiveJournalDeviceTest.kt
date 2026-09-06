package com.galaxyssi.chat.blob

import android.database.sqlite.SQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class BlobArtifactReceiveJournalDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private fun body(turn: String = "turn"): JSONObject {
        val manifest = BlobArtifactContract.makeManifest(JSONObject().put("client_route_id", "a".repeat(22))
            .put("desktop_id", "private-test-desktop").put("conversation_id", "中文恢复测试").put("task_id", "task")
            .put("turn_id", turn).put("contact_id", "contact").put("source_message_id", "message")
            .put("artifact_id", "b".repeat(64)).put("artifact_uri", "galaxyssi-artifact://task/$turn.bin")
            .put("name", "private-test-file.bin").put("relative_path", "file.bin").put("mime_type", "application/octet-stream")
            .put("size_bytes", 7).put("original_size_bytes", 7).put("sha256", "c".repeat(64))
            .put("original_sha256", "c".repeat(64)).put("execution_generation", 1).put("peer_chat", false))
        val private = JSONObject().put("version", 1).put("blob_id", "d".repeat(32)).put("key", "e".repeat(64))
            .put("nonce_prefix", "f".repeat(16)).put("size", 7).put("sha256", "c".repeat(64))
            .put("manifest_sha256", "1".repeat(64)).put("binding_sha256", BlobProtocol.bindingHash(BlobArtifactContract.binding(manifest)))
        return JSONObject().put("offer", JSONObject().put("type", BlobArtifactContract.OFFER_TYPE).put("version", 1).put("transport_revision", 1)
            .put("manifest", manifest).put("blob_offer", JSONObject().put("version", 1).put("relay", "https://blob.test")
                .put("private", private).put("read_token", "2".repeat(64))))
            .put("route_id", "a".repeat(22)).put("desktop_id", "private-test-desktop").put("origin", "https://blob.test")
            .put("peer_fingerprint", "3".repeat(64)).put("local_fingerprint", "4".repeat(64))
    }
    private fun fixture(block: (File) -> Unit) {
        val root = File(context.noBackupFilesDir, "blob-artifact-test-${UUID.randomUUID()}").apply { mkdirs() }
        try { block(File(root, "journal.sqlite3")) } finally { root.deleteRecursively() }
    }
    private fun next(journal: BlobArtifactReceiveJournal) = journal.claimDue(Long.MAX_VALUE, 1).single()
    private fun revised(revision: Long) = body().also { value ->
        value.getJSONObject("offer").put("transport_revision", revision).getJSONObject("blob_offer")
            .put("read_token", revision.toString(16).padStart(64, '0')).getJSONObject("private")
            .put("blob_id", revision.toString(16).padStart(32, '0')).put("key", "8".repeat(64))
    }

    @Test fun newerTransportFencesOldWorkerAndLateOffersCannotRollBack() = fixture { path ->
        BlobArtifactReceiveJournal(path).use {
            val id = it.enqueue(body()); val old = next(it)
            assertEquals(id, it.enqueue(revised(2)))
            assertFalse(it.current(old)); assertFalse(it.advance(old)); assertFalse(it.fail(old, "blob_expired"))
            val fresh = next(it)
            assertEquals(2L, BlobArtifactReceiveJob.revision(fresh.body))
            it.enqueue(body()); assertTrue(it.current(fresh))
            assertTrue(it.claimDue(Long.MAX_VALUE, 1).isEmpty())
        }
    }
    @Test fun sameRevisionCannotReplaceKeysAndNewRevisionCannotReplaceIdentity() = fixture { path ->
        BlobArtifactReceiveJournal(path).use {
            it.enqueue(revised(2))
            val conflict = revised(2).also { value ->
                value.getJSONObject("offer").getJSONObject("blob_offer").getJSONObject("private").put("key", "9".repeat(64))
            }
            assertThrows(BlobFailure::class.java) { it.enqueue(conflict) }
            assertThrows(BlobFailure::class.java) { it.enqueue(revised(3).put("peer_fingerprint", "9".repeat(64))) }
            assertEquals(2L, BlobArtifactReceiveJob.revision(next(it).body))
        }
    }
    @Test fun newestFirstArrivalAndRestartPreserveTransportRevision() = fixture { path ->
        BlobArtifactReceiveJournal(path).use { it.enqueue(revised(3)); it.enqueue(revised(2)); next(it) }
        BlobArtifactReceiveJournal(path).use {
            it.recover()
            assertEquals(3L, BlobArtifactReceiveJob.revision(next(it).body))
        }
    }
    @Test fun newTransportCanRecoverExpiredOfferButCannotReviveCancellation() = fixture { path ->
        BlobArtifactReceiveJournal(path).use {
            val id = it.enqueue(body())
            assertTrue(it.fail(next(it), "blob_expired")); assertTrue(it.finish(next(it)))
            it.enqueue(body()); assertNull(it.nextDue())
            it.enqueue(revised(2)); assertEquals(BlobArtifactReceiveJournal.DOWNLOAD, next(it).phase)
            it.cancel(id); assertTrue(it.finish(next(it)))
            assertEquals(2L, it.enqueueCommitted(revised(3)).revision)
            assertNull(it.nextDue())
        }
    }
    @Test fun corruptedRevisionCannotOutrankAuthenticatedCheckpoint() = fixture { path ->
        BlobArtifactReceiveJournal(path).use { it.enqueue(body()) }
        SQLiteDatabase.openDatabase(path.path, null, SQLiteDatabase.OPEN_READWRITE).use {
            it.execSQL("UPDATE artifact_receives SET revision=999")
        }
        BlobArtifactReceiveJournal(path).use {
            assertTrue(it.claimDue(Long.MAX_VALUE, 1).isEmpty()); assertNull(it.nextDue())
        }
    }

    @Test fun committedOfferReopensWithKeystoreEncryptedSecrets() = fixture { path ->
        BlobArtifactReceiveJournal(path).use { it.enqueue(body()) }
        val raw = path.readBytes().toString(Charsets.ISO_8859_1)
        for (secret in listOf("private-test-desktop", "private-test-file", "https://blob.test", "e".repeat(64), "2".repeat(64))) {
            assertFalse(raw.contains(secret))
        }
        BlobArtifactReceiveJournal(path).use {
            assertEquals("中文恢复测试", next(it).body.getJSONObject("offer").getJSONObject("manifest").getString("conversation_id"))
        }
    }
    @Test fun duplicateOfferDoesNotStealAnActiveClaim() = fixture { path ->
        BlobArtifactReceiveJournal(path).use {
            val id = it.enqueue(body()); val current = next(it)
            assertEquals(id, it.enqueue(body())); assertTrue(it.current(current))
            assertTrue(it.claimDue(Long.MAX_VALUE, 64).isEmpty())
        }
    }
    @Test fun changedBearerOrFingerprintCannotOverwriteCapturedIdentity() = fixture { path ->
        BlobArtifactReceiveJournal(path).use { journal ->
            journal.enqueue(body())
            for (field in listOf("peer_fingerprint", "local_fingerprint", "read_token")) {
                val changed = body()
                if (field == "read_token") changed.getJSONObject("offer").getJSONObject("blob_offer").put(field, "8".repeat(64))
                else changed.put(field, "8".repeat(64))
                assertThrows(BlobFailure::class.java) { journal.enqueue(changed) }
            }
            assertEquals("3".repeat(64), next(journal).body.getString("peer_fingerprint"))
        }
    }
    @Test fun abandonedClaimsRecoverAndFenceOldWorkerCallbacks() = fixture { path ->
        val old = BlobArtifactReceiveJournal(path).use { it.enqueue(body()); next(it) }
        BlobArtifactReceiveJournal(path).use {
            it.recover(); val fresh = next(it)
            assertFalse(it.current(old)); assertNotEquals(old.claim, fresh.claim)
            assertFalse(it.advance(old)); assertFalse(it.fail(old, "blob_expired")); assertFalse(it.defer(old, 9, "late"))
            assertTrue(it.current(fresh))
        }
    }
    @Test fun everyPublicationPhaseIsDurableAndCannotFinishEarly() = fixture { path ->
        BlobArtifactReceiveJournal(path).use { it.enqueue(body()) }
        for (phase in 0..3) BlobArtifactReceiveJournal(path).use {
            it.recover(); val work = next(it)
            assertEquals(phase, work.phase)
            if (phase < 3) { assertFalse(it.finish(work)); assertTrue(it.advance(work)); assertFalse(it.advance(work)) }
            else assertTrue(it.finish(work))
        }
        BlobArtifactReceiveJournal(path).use { assertNull(it.nextDue()) }
    }
    @Test fun lostReceiptReplayReverifiesCompletedFileInsteadOfBlindlyAcknowledging() = fixture { path ->
        BlobArtifactReceiveJournal(path).use {
            it.enqueue(body())
            repeat(3) { _ -> assertTrue(it.advance(next(it))) }
            assertTrue(it.finish(next(it)))
            it.enqueue(body())
            assertEquals(BlobArtifactReceiveJournal.DOWNLOAD, next(it).phase)
        }
    }
    @Test fun cancellationIsDurableAndLateCompletionOrDuplicateCannotReviveIt() = fixture { path ->
        BlobArtifactReceiveJournal(path).use {
            val id = it.enqueue(body()); val old = next(it)
            it.cancel(id); assertFalse(it.advance(old)); assertFalse(it.fail(old, "blob_expired"))
            it.enqueue(body()); val discard = next(it)
            assertEquals(BlobArtifactReceiveJournal.DISCARD, discard.phase); assertTrue(it.finish(discard))
        }
        BlobArtifactReceiveJournal(path).use {
            it.recover(); it.enqueue(body()); assertNull(it.nextDue())
        }
    }
    @Test fun terminalFailureMustBeObservedAndCannotSendSuccessOrAutoRevive() = fixture { path ->
        BlobArtifactReceiveJournal(path).use {
            it.enqueue(body()); val old = next(it)
            assertTrue(it.fail(old, "blob_expired")); assertFalse(it.advance(old))
        }
        BlobArtifactReceiveJournal(path).use {
            it.recover(); val failure = next(it)
            assertEquals(BlobArtifactReceiveJournal.OBSERVE_FAILURE, failure.phase)
            assertEquals("blob_expired", failure.error); assertFalse(it.advance(failure))
            assertTrue(it.finish(failure)); it.enqueue(body()); assertNull(it.nextDue())
        }
    }
    @Test fun claimLimitAndOccupiedExclusionDoNotLoseOtherConversations() = fixture { path ->
        BlobArtifactReceiveJournal(path).use { journal ->
            repeat(20) { journal.enqueue(body("turn-$it")) }
            val first = journal.claimDue(Long.MAX_VALUE, 4)
            journal.recover()
            val second = journal.claimDue(Long.MAX_VALUE, 4, first.map { it.id }.toSet())
            assertEquals(4, first.size); assertEquals(4, second.size)
            assertTrue(second.none { work -> first.any { it.id == work.id } })
            assertTrue(journal.claimDue(Long.MAX_VALUE, 0).isEmpty())
        }
    }
    @Test fun corruptEncryptedRowIsQuarantinedWithoutPoisoningOtherJobs() = fixture { path ->
        val id = BlobArtifactReceiveJournal(path).use { it.enqueue(body()); it.enqueue(body("other")) }
        SQLiteDatabase.openDatabase(path.path, null, SQLiteDatabase.OPEN_READWRITE).use {
            it.execSQL("UPDATE artifact_receives SET body='broken' WHERE id=?", arrayOf(id))
        }
        BlobArtifactReceiveJournal(path).use {
            val good = it.claimDue(Long.MAX_VALUE, 64)
            assertEquals(1, good.size); assertNotEquals(id, good.single().id)
            assertNull(it.nextDue())
        }
    }
    @Test fun missingLocalFileReturnsToDownloadWithoutTrustingEarlierReceiptPhase() = fixture { path ->
        BlobArtifactReceiveJournal(path).use {
            it.enqueue(body()); assertTrue(it.advance(next(it))); assertTrue(it.advance(next(it)))
            val receipt = next(it); assertTrue(it.redownload(receipt)); assertFalse(it.advance(receipt))
            assertEquals(BlobArtifactReceiveJournal.DOWNLOAD, next(it).phase)
        }
    }
    @Test fun corruptPhaseIsQuarantinedInsteadOfBeingRetriedForever() = fixture { path ->
        BlobArtifactReceiveJournal(path).use { it.enqueue(body()) }
        SQLiteDatabase.openDatabase(path.path, null, SQLiteDatabase.OPEN_READWRITE).use {
            it.execSQL("UPDATE artifact_receives SET phase=999")
        }
        BlobArtifactReceiveJournal(path).use {
            assertTrue(it.claimDue(Long.MAX_VALUE, 64).isEmpty())
            assertNull(it.nextDue())
        }
    }
    @Test fun transientRetryHasNoFixedAttemptExhaustionAndSanitizesDiagnostics() = fixture { path ->
        BlobArtifactReceiveJournal(path).use {
            it.enqueue(body())
            repeat(12) { attempt ->
                val work = next(it); assertEquals(attempt, work.attempts)
                assertTrue(it.defer(work, 50_000, "https://secret/key"))
            }
            assertTrue(it.claimDue(49_999, 1).isEmpty())
            assertEquals("artifact_blob_receive_failed", next(it).error)
        }
    }
}
