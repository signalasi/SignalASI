package com.galaxyssi.chat

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONObject

/** Receipt intent is durable before UI dispatch; broker acknowledgement retires exactly one attempt. */
internal object AndroidTransportReceipts {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Any()
    @Volatile private var runtime: Runtime? = null

    private class Runtime(val app: Context) {
        val journal = LinkTransportReceiptJournal(app)
        val coordinator = AgentResultReceiptCoordinator(scope, drain = {
            for (key in journal.due(System.currentTimeMillis())) {
                if (!GalaxySSIMqttClient.canPublishTransportReceipt()) break
                runCatching { send(key) }.onFailure {
                    Log.w("GalaxySSIRecovery", "One transport receipt deferred: ${it.javaClass.simpleName}")
                }
            }
            journal.nextDue()?.let { due ->
                if (!GalaxySSIMqttClient.canPublishTransportReceipt()) maxOf(due, System.currentTimeMillis() + 1_000L)
                else due
            }
        }, failed = { Log.w("GalaxySSIRecovery", "Transport receipt retry deferred: ${it.javaClass.simpleName}") })

        private fun send(key: String) {
            val work = journal.claim(key, System.currentTimeMillis()) ?: return
            val routes = currentRoutes(app, work.receipt.peer, work.receipt.phone)
            if (routes == null || binding(work.receipt.peer, work.receipt.phone, routes) != work.receipt.binding) {
                journal.acknowledge(work.attempt)
                return
            }
            var wire = work.wire
            if (wire.isBlank()) {
                val payload = JSONObject().put("type", "delivery_ack")
                    .put("transport_message_id", work.receipt.message).put("source_message_id", work.receipt.message)
                    .put("delivery_status", "accepted").put("sender", "system").put("time", System.currentTimeMillis())
                if (work.receipt.phone) payload.put("peer_chat", true)
                val envelope = GalaxySSILinkProtocol.makeEnvelope(payload, GalaxySSICrypto.localGalaxySSIId(), work.receipt.peer)
                wire = (if (work.receipt.phone) GalaxySSICrypto.encryptPayloadForContact(work.receipt.peer, envelope)
                    else GalaxySSICrypto.encryptPayloadForDesktop(work.receipt.peer, envelope))?.toString() ?: return
                if (!journal.prepared(work, wire)) return
            }
            // Resolve the current topic at send time, not from an obsolete rotation window.
            GalaxySSIMqttClient.publishTransportReceipt(routes.control, wire, work.attempt)
        }
    }

    private fun runtime(context: Context): Runtime = runtime ?: synchronized(lock) {
        runtime ?: Runtime(context.applicationContext).also { runtime = it }
    }

    fun enqueue(context: Context, peer: String, phone: Boolean, message: String) {
        if (message.isBlank()) return
        val routes = currentRoutes(context, peer, phone) ?: return
        val active = runtime(context)
        active.journal.enqueue(LinkTransportReceipt(peer, phone, binding(peer, phone, routes), message))
        active.coordinator.request(GalaxySSIMqttClient.isRequestReplyReady())
    }

    fun connectionChanged(context: Context, ready: Boolean) {
        val active = runtime(context)
        if (!ready) active.coordinator.connectionChanged(false)
        else scope.launch {
            runCatching { active.journal.reconnect() }.onFailure {
                Log.w("GalaxySSIRecovery", "Transport receipt reconnect deferred: ${it.javaClass.simpleName}")
            }
            active.coordinator.request(GalaxySSIMqttClient.isRequestReplyReady())
        }
    }

    fun acknowledge(context: Context, attempt: LinkTransportReceiptAttempt) {
        val active = runtime(context)
        scope.launch {
            runCatching { active.journal.acknowledge(attempt) }
                .onFailure { Log.w("GalaxySSIRecovery", "Transport receipt completion deferred: ${it.javaClass.simpleName}") }
            active.coordinator.request(GalaxySSIMqttClient.isRequestReplyReady())
        }
    }

    private fun currentRoutes(context: Context, peer: String, phone: Boolean): GalaxySSILinkProtocol.Routes? =
        if (phone) AppStore.phoneRoutesForIdentity(context, peer)?.takeIf { AppStore.canCommunicateWith(context, peer) }
        else GalaxySSILinkProtocol.serverLink(context, peer)?.takeIf { it.paired }?.routes

    private fun binding(peer: String, phone: Boolean, routes: GalaxySSILinkProtocol.Routes) =
        LinkTransportReceipt.binding(peer, phone, routes.clientRouteId, routes.localFingerprint, routes.remoteFingerprint, routes.linkSecret)
}
