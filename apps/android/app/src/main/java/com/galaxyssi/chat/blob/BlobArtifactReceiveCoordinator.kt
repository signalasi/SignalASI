package com.galaxyssi.chat.blob

import org.json.JSONObject
import java.io.Closeable
import java.io.File
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.file.StandardOpenOption
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Owns recovery; control persistence and bulk transfer never run on the UI/MQTT thread. */
internal class BlobArtifactReceiveCoordinator(
    private val root: File,
    private val journal: BlobArtifactReceiveJournal,
    private val pipeline: BlobArtifactReceivePipeline,
    private val capacity: () -> Int = { 2 },
    private val diagnostic: (String) -> Unit = {}
) : Closeable {
    init { require(root.canonicalFile == journal.directory) { "artifact_blob_owner_path_mismatch" } }
    private val stopped = AtomicBoolean()
    private var closed = false
    private val scheduler = Executors.newSingleThreadScheduledExecutor { Thread(it, "blob-artifact-scheduler") }
    private val workers = Executors.newFixedThreadPool(4) { Thread(it, "blob-artifact-worker") }
    private data class Active(val cancel: BlobCancellation, val revision: Long)
    private val active = ConcurrentHashMap<String, Active>()
    private var channel: FileChannel? = null
    private var owner: FileLock? = null
    private var recovered = false
    private var scheduled: ScheduledFuture<*>? = null

    fun enqueue(body: JSONObject, persisted: (Result<String>) -> Unit) {
        if (stopped.get()) { persisted(Result.failure(IllegalStateException("blob_receiver_stopped"))); return }
        val snapshot = JSONObject(body.toString())
        try { scheduler.execute {
            if (stopped.get()) { persisted(Result.failure(IllegalStateException("blob_receiver_stopped"))); return@execute }
            val result = runCatching { journal.enqueueCommitted(snapshot) }
            result.getOrNull()?.let { committed ->
                active[committed.id]?.takeIf { committed.revision > it.revision }?.cancel?.cancel()
            }
            // This callback permits the control transport ACK only after the SQLite commit.
            try { persisted(result.map { it.id }) } finally { wake() }
        } } catch (_: RejectedExecutionException) {
            persisted(Result.failure(IllegalStateException("blob_receiver_stopped")))
        }
    }

    fun cancel(id: String) {
        if (stopped.get()) return
        scheduler.execute {
            journal.cancel(id)
            active[id]?.cancel?.cancel()
            tick()
        }
    }

    fun wake() {
        if (!stopped.get()) runCatching { scheduler.execute { scheduled?.cancel(false); tick() } }
    }

    /** Called before capability publication; opening a database alone does not establish ownership. */
    fun prepare(completed: (Result<Unit>) -> Unit) {
        if (stopped.get()) { completed(Result.failure(IllegalStateException("blob_receiver_stopped"))); return }
        try { scheduler.execute {
            val result = runCatching {
                check(!stopped.get()) { "blob_receiver_stopped" }
                check(acquireAndRecover()) { "artifact_blob_receiver_busy" }
            }
            try { completed(result) } finally { wake() }
        } } catch (_: RejectedExecutionException) {
            completed(Result.failure(IllegalStateException("blob_receiver_stopped")))
        }
    }

    private fun acquireAndRecover(): Boolean {
        if (owner == null) {
            check(root.mkdirs() || root.isDirectory)
            val opened = FileChannel.open(File(root, "owner.lock").toPath(), StandardOpenOption.CREATE, StandardOpenOption.WRITE)
            val lock = runCatching { opened.tryLock() }.getOrNull()
            if (lock == null) { opened.close(); return false }
            channel = opened; owner = lock
        }
        if (!recovered) { journal.recover(); recovered = true }
        return true
    }

    private fun tick() {
        if (stopped.get()) return
        scheduled?.cancel(false)
        try {
            if (!acquireAndRecover()) { schedule(2_000); return }
            val available = capacity().coerceIn(1, 4) - active.size
            journal.claimDue(System.currentTimeMillis(), available, active.keys.toSet()).forEach { work ->
                val cancel = BlobCancellation()
                val running = Active(cancel, BlobArtifactReceiveJob.revision(work.body))
                active[work.id] = running
                workers.execute {
                    try {
                        process(work, cancel)
                        active.remove(work.id, running)
                        wake()
                    } catch (_: Exception) {
                        // Keep the slot occupied until a failed checkpoint write can be retried.
                        retryCheckpoint(work, running)
                    }
                }
            }
        } catch (_: Exception) { report("artifact_blob_queue_unavailable") }
        val due = runCatching { journal.nextDue() }.getOrNull()
        schedule(if (due == null) 60_000 else (due - System.currentTimeMillis()).coerceIn(1_000, 60_000))
    }

    private fun process(work: BlobArtifactReceiveWork, cancel: BlobCancellation) {
        try {
            pipeline.process(work, cancel) {
                if (!journal.current(work)) throw BlobFailure("artifact_blob_claim_superseded", 409)
            }
            if (work.phase in setOf(BlobArtifactReceiveJournal.CLEANUP, BlobArtifactReceiveJournal.DISCARD,
                    BlobArtifactReceiveJournal.OBSERVE_FAILURE)) journal.finish(work) else journal.advance(work)
        } catch (error: Exception) {
            if (!journal.current(work)) return
            val code = (error as? BlobFailure)?.code ?: "artifact_blob_receive_failed"
            when {
                code == "artifact_blob_local_copy_missing" -> journal.redownload(work)
                work.phase in setOf(BlobArtifactReceiveJournal.DOWNLOAD, BlobArtifactReceiveJournal.PUBLISH,
                    BlobArtifactReceiveJournal.RECEIPT) && code in terminalCodes -> journal.fail(work, code)
                else -> journal.defer(work, System.currentTimeMillis() + BlobOutgoingContract.retryDelay(work.attempts), code)
            }
            report(code)
        }
    }

    private fun retryCheckpoint(work: BlobArtifactReceiveWork, running: Active) {
        if (stopped.get()) { active.remove(work.id, running); return }
        scheduler.schedule({
            try {
                journal.defer(work, System.currentTimeMillis() + 2_000, "artifact_blob_checkpoint_unavailable")
                active.remove(work.id, running)
                wake()
            } catch (_: Exception) { report("artifact_blob_checkpoint_unavailable"); retryCheckpoint(work, running) }
        }, 2_000, TimeUnit.MILLISECONDS)
    }

    private fun schedule(delay: Long) {
        if (!stopped.get()) scheduled = scheduler.schedule(::tick, delay, TimeUnit.MILLISECONDS)
    }
    private fun report(code: String) { runCatching { diagnostic(BlobArtifactReceiveJob.errorCode(code)) } }

    /** Close off the UI thread. Ownership is not released while workers can still mutate state. */
    @Synchronized override fun close() {
        if (closed) return
        stopped.set(true)
        scheduled?.cancel(false)
        active.values.forEach { it.cancel.cancel() }
        scheduler.shutdown()
        check(scheduler.awaitTermination(5, TimeUnit.SECONDS)) { "artifact_blob_scheduler_still_running" }
        workers.shutdown()
        check(workers.awaitTermination(70, TimeUnit.SECONDS)) { "artifact_blob_workers_still_running" }
        try { journal.close() } finally { owner?.release(); channel?.close(); closed = true }
    }

    companion object {
        private val terminalCodes = BlobFailureContract.terminalCodes + setOf("artifact_blob_binding_mismatch", "artifact_blob_uri_conflict",
            "artifact_blob_record_invalid", "artifact_blob_identity_mismatch", "relay_checkpoint_mismatch",
            "invalid_transfer_path", "artifact_blob_checkpoint_invalid")
    }
}
