package com.galaxyssi.chat.blob

import android.content.Context
import android.util.Log
import com.galaxyssi.chat.AgentOutboundAttachmentTransferStore
import com.galaxyssi.chat.AgentPreparedOutboundAttachment
import com.galaxyssi.chat.GalaxySSILinkDeliveryStore
import com.galaxyssi.chat.GalaxySSILinkProtocol
import com.galaxyssi.chat.GalaxySSIMqttClient
import com.galaxyssi.chat.PeerAttachmentTransferProgress
import org.json.JSONObject
import java.io.File
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.file.StandardOpenOption
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

internal object AndroidBlobTransfers {
    @Volatile private var coordinator: BlobOutgoingCoordinator? = null
    private fun get(context: Context): BlobOutgoingCoordinator = coordinator ?: synchronized(this) {
        coordinator ?: BlobOutgoingCoordinator(context.applicationContext).also { coordinator = it }
    }
    fun register(context: Context, attachment: AgentPreparedOutboundAttachment, immediate: Boolean = false): Boolean {
        if (owns(context, attachment.transferId)) {
            get(context).resume(attachment, immediate)
            return true
        }
        val config = BlobRelayConfigurations.get(context, attachment.scope.desktopId) ?: return false
        get(context).register(attachment, config, immediate)
        return true
    }
    fun owns(context: Context, id: String): Boolean = exists(context) && get(context).journal.contains(id)
    fun activate(context: Context, ids: Collection<String>) { if (exists(context)) get(context).activate(ids) }
    fun cancel(context: Context, ids: Collection<String>) { if (exists(context)) get(context).cancel(ids) }
    fun wake(context: Context) { if (exists(context)) get(context).wake() }
    private fun exists(context: Context) = File(context.noBackupFilesDir, "blob-outgoing-v1/jobs.sqlite3").exists()

    fun acceptReceipt(context: Context, payload: JSONObject, sourceId: String): Boolean {
        if (payload.optString("status") !in setOf("stored", "failed") || !exists(context)) return false
        val link = GalaxySSILinkProtocol.serverLink(context, sourceId)?.takeIf { it.paired } ?: return false
        return get(context).acceptReceipt(payload, sourceId, link.routes.remoteFingerprint)
    }
}

/** Application singleton, independent of the MQTT receipt executor and the UI thread. */
private class BlobOutgoingCoordinator(private val context: Context) {
    private val root = File(context.noBackupFilesDir, "blob-outgoing-v1").apply { mkdirs() }
    val journal = BlobOutgoingJournal(File(root, "jobs.sqlite3"))
    private val scheduler = Executors.newSingleThreadScheduledExecutor { Thread(it, "blob-outgoing-scheduler") }
    private val workers = Executors.newFixedThreadPool(4) { Thread(it, "blob-outgoing-worker") }
    private val active = ConcurrentHashMap<String, BlobCancellation>()
    private var channel: FileChannel? = null
    private var owner: FileLock? = null
    private var recovered = false
    private var scheduled: ScheduledFuture<*>? = null

    fun resume(attachment: AgentPreparedOutboundAttachment, immediate: Boolean) {
        val previous = journal.body(attachment.transferId) ?: BlobProtocol.fail("blob_outgoing_checkpoint_missing")
        if (previous.optString("desktop_id") != attachment.scope.desktopId ||
            !BlobOutgoingContract.sameManifest(previous.getJSONObject("manifest"), attachment.manifestPayload(false))) {
            BlobProtocol.fail("blob_outgoing_identity_conflict")
        }
        if (immediate) activate(listOf(attachment.transferId))
    }

    fun register(attachment: AgentPreparedOutboundAttachment, config: BlobRelayConfiguration, immediate: Boolean) {
        if (attachment.scope.clientRouteId != config.routeId) BlobProtocol.fail("blob_outgoing_route_mismatch")
        val manifest = attachment.manifestPayload(false).also { it.remove("time"); it.remove("resume"); it.remove("eager_chunks") }
        journal.register(attachment.transferId, JSONObject().put("manifest", manifest)
            .put("desktop_id", config.desktopId).put("fingerprint", config.fingerprint).put("origin", config.origin), immediate)
        if (immediate) wake()
    }
    fun activate(ids: Collection<String>) { ids.forEach(journal::activate); wake() }
    fun cancel(ids: Collection<String>) {
        ids.forEach { journal.cancel(it); active[it]?.cancel() }
        wake()
    }
    fun acceptReceipt(payload: JSONObject, sourceId: String, fingerprint: String): Boolean {
        val id = payload.optString("transfer_id")
        val accepted = if (payload.optString("status") == "failed") journal.failedReceipt(id, payload, sourceId, fingerprint)
            else journal.stored(id, payload, sourceId, fingerprint)
        if (!accepted) return false
        active[id]?.cancel()
        wake()
        return true
    }
    fun wake() { scheduler.execute { scheduled?.cancel(false); tick() } }

    private fun tick() {
        try {
            if (owner == null) {
                val opened = FileChannel.open(File(root, "owner.lock").toPath(), StandardOpenOption.CREATE, StandardOpenOption.WRITE)
                val lock = runCatching { opened.tryLock() }.getOrNull()
                if (lock == null) { opened.close(); schedule(2_000); return }
                channel = opened
                owner = lock
            }
            if (!recovered) { journal.recover(); recovered = true }
            journal.claimDue(System.currentTimeMillis(), 4 - active.size, active.keys.toSet()).forEach { work ->
                val cancel = BlobCancellation()
                active[work.id] = cancel
                workers.execute {
                    try { process(work, cancel) }
                    catch (_: Exception) {
                        scheduler.schedule({ retryCheckpoint(work, cancel) }, 1_000, TimeUnit.MILLISECONDS)
                        return@execute
                    }
                    active.remove(work.id, cancel); wake()
                }
            }
        } catch (error: Exception) {
            Log.w("BlobTransfers", "Queue retry: ${error.javaClass.simpleName}")
        }
        val next = runCatching { journal.nextDue() }.getOrNull()
        schedule(if (next == null) 60_000 else (next - System.currentTimeMillis()).coerceIn(1_000, 60_000))
    }
    private fun schedule(delay: Long) { scheduled = scheduler.schedule(::tick, delay, TimeUnit.MILLISECONDS) }

    private fun retryCheckpoint(work: BlobOutgoingWork, cancel: BlobCancellation) {
        try { journal.defer(work, System.currentTimeMillis() + 2_000, "blob_checkpoint_unavailable") }
        catch (_: Exception) {
            scheduler.schedule({ retryCheckpoint(work, cancel) }, 2_000, TimeUnit.MILLISECONDS)
            return
        }
        active.remove(work.id, cancel); wake()
    }

    private fun process(work: BlobOutgoingWork, cancel: BlobCancellation) {
        try {
            val manifest = work.body.getJSONObject("manifest")
            val binding = BlobOutgoingContract.binding(manifest)
            val directory = staging(work.id)
            if (work.phase == BlobOutgoingJournal.FAILURE) {
                if (!BlobFailureDelivery.persist(context, work.body)) {
                    throw BlobFailure("blob_failure_observation_pending", 503)
                }
                GalaxySSILinkDeliveryStore.discardBlockedByAttachmentTransfers(context, listOf(work.id))
                GalaxySSILinkDeliveryStore.discardAttachmentTransferMessages(context, work.id)
                journal.failureObserved(work)
                return
            }
            if (work.phase != BlobOutgoingJournal.UPLOAD) {
                cleanup(work, directory, binding, cancel)
                return
            }
            if (work.waitingForTask && manifest.optString("attachment_request_id").isBlank() &&
                !GalaxySSILinkDeliveryStore.hasAttachmentDependency(context, work.id)) {
                journal.defer(work, System.currentTimeMillis() + 30_000, waiting = true)
                return
            }
            val config = currentConfig(work)
            val attachment = AgentOutboundAttachmentTransferStore.find(context, work.id)
                ?: throw BlobFailure("blob_source_missing", 409)
            if (!BlobStaging.exists(directory) && directory.exists()) removeStaging(directory)
            directory.parentFile!!.mkdirs()
            val staged = if (BlobStaging.exists(directory)) BlobStaging.open(directory, binding) else {
                BlobStaging.prepare(directory, attachment.sizeBytes, attachment.sha256, binding,
                    source = attachment::openPlaintext, checkCancelled = cancel::check)
            }
            staged.use {
                BlobTransferClient(BlobHttp(config.origin), config.provisioningToken).upload(it, cancel,
                    progress = { done, total ->
                        cancel.check(); currentConfig(work)
                        val percent = (done * 100 / total.coerceAtLeast(1)).coerceAtMost(99).toInt()
                        GalaxySSIMqttClient.notifyBlobProgress(PeerAttachmentTransferProgress.event(attachment,
                            attachment.scope.desktopId, "outbound", percent, PeerAttachmentTransferProgress.STATE_UPLOADING, done))
                    }, onOffer = { offer ->
                        cancel.check(); currentConfig(work)
                        // A fresh Signal envelope permits retry after receiver persistence/decrypt failure.
                        GalaxySSILinkDeliveryStore.discardAttachmentTransferMessages(context, work.id)
                        if (!GalaxySSIMqttClient.publishBlobOffer(BlobOutgoingContract.offerPayload(manifest, offer),
                                attachment.scope.contactId)) throw BlobFailure("blob_offer_not_queued", 503)
                    })
            }
            // Relay completion is not permission to release the attachment-dependent task.
            journal.defer(work, System.currentTimeMillis() + 30_000)
        } catch (error: Exception) {
            val code = when (error) {
                is BlobFailure -> error.code
                is java.io.FileNotFoundException -> "blob_source_missing"
                else -> "blob_transfer_failed"
            }
            if (work.phase == BlobOutgoingJournal.UPLOAD && code in BlobFailureContract.terminalCodes) {
                journal.fail(work, code)
                return
            }
            Log.w("BlobTransfers", "Transfer deferred code=$code")
            journal.defer(work, System.currentTimeMillis() + BlobOutgoingContract.retryDelay(work.attempts), code)
        }
    }

    private fun currentConfig(work: BlobOutgoingWork): BlobRelayConfiguration {
        val config = BlobRelayConfigurations.get(context, work.body.getString("desktop_id"))
            ?: throw BlobFailure("blob_relay_unavailable", 503)
        if (config.fingerprint != work.body.getString("fingerprint") || config.origin != work.body.getString("origin") ||
            config.routeId != work.body.getJSONObject("manifest").getString("client_route_id")) {
            throw BlobFailure("blob_outgoing_identity_mismatch", 409)
        }
        return config
    }
    private fun cleanup(work: BlobOutgoingWork, directory: File, binding: Map<String, String>, cancel: BlobCancellation) {
        if (work.phase == BlobOutgoingJournal.CLEANUP) {
            val link = GalaxySSILinkProtocol.serverLink(context, work.body.getString("desktop_id"))
            if (link?.paired != true || link.routes.remoteFingerprint != work.body.getString("fingerprint") ||
                link.routes.clientRouteId != binding["client_route_id"]) {
                throw BlobFailure("blob_outgoing_identity_mismatch", 409)
            }
            val receipt = work.body.getJSONObject("receipt")
            val transfer = AgentOutboundAttachmentTransferStore.find(context, work.id)
            GalaxySSILinkDeliveryStore.releaseAttachmentDependencyResult(context, work.id)
            GalaxySSILinkDeliveryStore.discardAttachmentTransferMessages(context, work.id)
            if (transfer != null) GalaxySSIMqttClient.notifyBlobProgress(PeerAttachmentTransferProgress.event(transfer,
                transfer.scope.desktopId, "outbound", 100, PeerAttachmentTransferProgress.STATE_COMPLETE, transfer.sizeBytes))
            AgentOutboundAttachmentTransferStore.acknowledgeStored(context, receipt)
            GalaxySSIMqttClient.dispatchBlobDependencies()
        }
        if (BlobStaging.exists(directory)) BlobStaging.open(directory, binding).use { staged ->
            if (staged.remote.optBoolean("created") && !staged.remote.optBoolean("revoked")) {
                try { BlobTransferClient(BlobHttp(work.body.getString("origin"))).revoke(staged, cancel) }
                catch (error: BlobFailure) { if (error.status !in setOf(404, 410)) throw error }
            }
        }
        removeStaging(directory)
        journal.finish(work)
    }
    private fun staging(id: String): File {
        BlobProtocol.unhex(id, 32).fill(0)
        val parent = File(root, "staging").canonicalFile
        return File(parent, id).also { check(it.canonicalFile.parentFile == parent) }
    }
    private fun removeStaging(directory: File) {
        check(directory.canonicalFile.parentFile == File(root, "staging").canonicalFile)
        check(!directory.exists() || directory.deleteRecursively()) { "blob_cleanup_failed" }
    }
}
