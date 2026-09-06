package com.galaxyssi.chat.blob

import android.content.Context
import android.util.Log
import com.galaxyssi.chat.AgentDesktopArtifactStore
import com.galaxyssi.chat.AgentTaskIdentityStore
import com.galaxyssi.chat.ChatHistoryStore
import com.galaxyssi.chat.GalaxySSILinkProtocol
import com.galaxyssi.chat.GalaxySSIMqttClient
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors

/** Lazy application singleton. Initialization and persistence never block MQTT or UI. */
internal object AndroidBlobArtifactReceives {
    private const val DIRECTORY = "blob-artifact-receives-v1"
    private val control = Executors.newSingleThreadExecutor { Thread(it, "blob-artifact-control") }
    private var coordinator: BlobArtifactReceiveCoordinator? = null

    fun receive(context: Context, desktopId: String, conversationId: String, payload: JSONObject,
        committed: (Result<Unit>) -> Unit) {
        val snapshot = payload.toString()
        if (snapshot.toByteArray(Charsets.UTF_8).size > BlobArtifactContract.MAX_CONTROL_BYTES) {
            committed(Result.failure(BlobFailure("artifact_blob_offer_too_large", 413)))
            return
        }
        val app = context.applicationContext
        control.execute {
            val prepared = runCatching {
                val link = GalaxySSILinkProtocol.serverLink(app, desktopId)?.takeIf { it.paired }
                    ?: throw BlobFailure("artifact_blob_identity_mismatch", 409)
                val config = BlobRelayConfigurations.get(app, desktopId)
                    ?: throw BlobFailure("artifact_blob_relay_unavailable", 503)
                BlobArtifactIngressPolicy.prepare(JSONObject(snapshot), desktopId, conversationId,
                    link.routes.clientRouteId, link.routes.remoteFingerprint, link.routes.localFingerprint, config.origin)
                    .also { checkIdentity(app, it) }
            }
            prepared.fold(onSuccess = { body ->
                try { get(app).enqueue(body) { committed(it.map { Unit }) } }
                catch (error: Exception) { committed(Result.failure(error)) }
            }, onFailure = { committed(Result.failure(it)) })
        }
    }

    fun wake(context: Context) {
        val app = context.applicationContext
        control.execute {
            if (File(app.noBackupFilesDir, "$DIRECTORY/jobs.sqlite3").isFile) {
                runCatching { get(app).wake() }.onFailure { report("artifact_blob_queue_unavailable") }
            }
        }
    }

    private fun get(context: Context): BlobArtifactReceiveCoordinator {
        coordinator?.let { return it }
        val root = File(context.noBackupFilesDir, DIRECTORY)
        val journal = BlobArtifactReceiveJournal(File(root, "jobs.sqlite3"))
        try {
            val pipeline = BlobArtifactReceivePipeline(File(root, "staging"), AgentDesktopArtifactStore.blobStorage(context),
                checkIdentity = { checkIdentity(context, it) },
                publish = { manifest -> publishEvent(context, manifest, "artifact_available") },
                sendReceipt = { body, receipt ->
                    val manifest = body.getJSONObject("offer").getJSONObject("manifest")
                    BlobArtifactIngressPolicy.scopeFields.forEach { receipt.put(it, manifest.get(it)) }
                    GalaxySSIMqttClient.publishDesktopControlPayload(body.getString("desktop_id"), receipt,
                        clientRouteId = body.getString("route_id"))
                },
                observeFailure = { body, code -> publishEvent(context,
                    body.getJSONObject("offer").getJSONObject("manifest"), "artifact_download_failed", code) },
                progress = { manifest, done, total ->
                    GalaxySSIMqttClient.notifyBlobProgress(BlobArtifactPresentation.progress(manifest, done, total))
                })
            return BlobArtifactReceiveCoordinator(root, journal, pipeline, diagnostic = ::report)
                .also { coordinator = it }
        } catch (error: Exception) { journal.close(); throw error }
    }

    private fun checkIdentity(context: Context, body: JSONObject) {
        val desktopId = body.getString("desktop_id")
        val link = GalaxySSILinkProtocol.serverLink(context, desktopId)?.takeIf { it.paired }
            ?: throw BlobFailure("artifact_blob_identity_mismatch", 409)
        BlobArtifactIngressPolicy.checkPair(body, link.routes.clientRouteId,
            link.routes.remoteFingerprint, link.routes.localFingerprint)
        val manifest = body.getJSONObject("offer").getJSONObject("manifest")
        if (!manifest.getBoolean("peer_chat") && !AgentTaskIdentityStore.matchesRegistered(context, manifest)) {
            throw BlobFailure("artifact_blob_identity_mismatch", 409)
        }
        val config = BlobRelayConfigurations.get(context, desktopId)
            ?: throw BlobFailure("artifact_blob_relay_unavailable", 503)
        if (config.origin != body.getString("origin")) throw BlobFailure("relay_checkpoint_mismatch", 409)
    }

    private fun publishEvent(context: Context, manifest: JSONObject, type: String, code: String = ""): Boolean {
        val event = BlobArtifactIngressPolicy.event(manifest, type, code)
        if (event.optBoolean("peer_chat")) ChatHistoryStore.applyBlobAttachmentEvent(context, event)
        return GalaxySSIMqttClient.persistBlobArtifactEvent(context, event)
    }

    private fun report(code: String) { Log.w("BlobArtifactReceiver", BlobArtifactReceiveJob.errorCode(code)) }
}

/** Pure ingress/publication rules; no untrusted payload may choose a different endpoint or conversation. */
internal object BlobArtifactIngressPolicy {
    val scopeFields = listOf("conversation_id", "task_id", "turn_id", "contact_id", "execution_generation")

    fun prepare(payload: JSONObject, desktopId: String, conversationId: String, route: String,
        peerFingerprint: String, localFingerprint: String, origin: String): JSONObject {
        val offer = BlobArtifactContract.validateOffer(payload, route, desktopId, origin)
        val manifest = offer.getJSONObject("manifest")
        if (manifest.getString("conversation_id") != conversationId ||
            (manifest.getBoolean("peer_chat") && manifest.getString("contact_id") != desktopId)) {
            throw BlobFailure("artifact_blob_identity_mismatch", 409)
        }
        return BlobArtifactReceiveJob.validate(JSONObject().put("offer", offer).put("desktop_id", desktopId)
            .put("route_id", route).put("peer_fingerprint", peerFingerprint).put("local_fingerprint", localFingerprint)
            .put("origin", origin))
    }

    fun checkPair(body: JSONObject, route: String, peerFingerprint: String, localFingerprint: String) {
        if (body.getString("route_id") != route || body.getString("peer_fingerprint") != peerFingerprint ||
            body.getString("local_fingerprint") != localFingerprint) throw BlobFailure("artifact_blob_identity_mismatch", 409)
    }

    fun event(manifest: JSONObject, type: String, code: String = ""): JSONObject {
        require(type in setOf("artifact_available", "artifact_download_failed"))
        val value = BlobArtifactContract.validateManifest(manifest)
        return JSONObject().put("type", type).put("blob_publication", true)
            .put("message_id", "blob-artifact:${value.getString("transfer_id")}:$type")
            .put("artifact_id", value.getString("artifact_id")).put("artifact_uri", value.getString("artifact_uri"))
            .put("desktop_id", value.getString("desktop_id")).put("client_route_id", value.getString("client_route_id"))
            .put("transfer_id", value.getString("transfer_id"))
            .put("peer_chat", value.getBoolean("peer_chat"))
            .put("source_message_id", value.getString("source_message_id"))
            .put("name", value.getString("name")).put("mime_type", value.getString("mime_type"))
            .put("size_bytes", value.getLong("size_bytes")).put("sha256", value.getString("sha256"))
            .also { event -> scopeFields.forEach { event.put(it, value.get(it)) } }
            .also { if (code.isNotEmpty()) it.put("error_code", BlobArtifactReceiveJob.errorCode(code)) }
    }
}
