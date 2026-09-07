package com.galaxyssi.chat.blob

import android.content.Context
import android.util.Log
import com.galaxyssi.chat.AgentEncryptedPreferences
import com.galaxyssi.chat.GalaxySSILinkProtocol
import com.galaxyssi.chat.GalaxySSIMqttClient
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/** Event-driven per-pair declarations; bulk receive work never runs on this control executor. */
internal object AndroidBlobArtifactCapability {
    private val control = Executors.newSingleThreadScheduledExecutor { Thread(it, "blob-receiver-capability") }
    private var publisher: BlobArtifactCapabilityPublisher? = null
    private var preparing = false
    private var again = false
    private var retry: ScheduledFuture<*>? = null

    fun refresh(context: Context, reconnect: Boolean = false) {
        val app = context.applicationContext
        control.execute {
            runCatching {
                if (reconnect) publisher?.reconnect()
                retry?.cancel(false)
                if (preparing) again = true else begin(app)
            }.onFailure { retry(app) }
        }
    }

    private fun pairs(context: Context) = GalaxySSILinkProtocol.allServerLinks(context)
        .filter { it.paired }.map {
            BlobArtifactCapabilityPair(it.desktopId, it.routes.clientRouteId,
                it.routes.remoteFingerprint, it.routes.localFingerprint)
        }

    private fun matches(context: Context, pair: BlobArtifactCapabilityPair): Boolean {
        val link = GalaxySSILinkProtocol.serverLink(context, pair.desktop)?.takeIf { it.paired } ?: return false
        return link.routes.clientRouteId == pair.route && link.routes.localFingerprint == pair.localFingerprint &&
            link.routes.remoteFingerprint == pair.remoteFingerprint
    }

    private fun configured(context: Context, pair: BlobArtifactCapabilityPair): Boolean {
        val config = BlobRelayConfigurations.get(context, pair.desktop) ?: return false
        return config.routeId == pair.route && config.fingerprint == pair.remoteFingerprint && matches(context, pair)
    }

    private fun publisher(context: Context): BlobArtifactCapabilityPublisher {
        publisher?.let { return it }
        val storage = AgentEncryptedPreferences(context, "blob-artifact-capability-v1")
        return BlobArtifactCapabilityPublisher(read = { key ->
            storage.readString(key, "").takeIf(String::isNotEmpty).also {
                if (it == null && storage.encodedValueLength(key) > 0)
                    BlobProtocol.fail("artifact_blob_capability_storage_unavailable")
            }
        }, write = storage::writeString, current = { matches(context, it) }, publish = { pair, value ->
            GalaxySSIMqttClient.publishDesktopControlPayload(pair.desktop, value, clientRouteId = pair.route)
        }).also { publisher = it }
    }

    private fun begin(context: Context) {
        if (!GalaxySSIMqttClient.isConnected()) return
        val links = pairs(context)
        if (links.isEmpty()) return
        preparing = true
        if (links.none { configured(context, it) }) {
            finish(context, links, ready = false, preparationFailed = false)
            return
        }
        AndroidBlobArtifactReceives.prepare(context) { result -> control.execute {
            finish(context, links, ready = result.isSuccess, preparationFailed = result.isFailure)
        } }
    }

    private fun finish(context: Context, links: List<BlobArtifactCapabilityPair>, ready: Boolean, preparationFailed: Boolean) {
        var needsRetry = preparationFailed
        try {
            val sender = publisher(context)
            // Recheck the current pair/config after asynchronous recovery, before publishing.
            links.forEach { pair ->
                runCatching { sender.update(pair, ready && configured(context, pair)) }
                    .fold(onSuccess = { if (!it) needsRetry = true }, onFailure = { needsRetry = true })
            }
        } catch (_: Exception) { needsRetry = true }
        finally { preparing = false }
        if (again) { again = false; refresh(context) }
        else if (needsRetry) retry(context)
    }

    private fun retry(context: Context) {
        Log.w("BlobArtifactCapability", "Receiver capability synchronization deferred")
        retry?.cancel(false)
        // Disconnected clients resume from the subscription-ready event; no periodic heartbeat.
        if (GalaxySSIMqttClient.isConnected()) retry = control.schedule({ refresh(context) }, 30, TimeUnit.SECONDS)
    }
}
