package com.galaxyssi.chat

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONObject

internal object AndroidAgentResultReceipts {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var coordinator: AgentResultReceiptCoordinator? = null

    fun connectionChanged(context: Context, connected: Boolean) = coordinator(context).connectionChanged(connected)
    fun request(context: Context) = coordinator(context).request(GalaxySSIMqttClient.isRequestReplyReady())

    fun receive(context: Context, payload: JSONObject, desktop: String) {
        val receipt = AgentResultReceipt.confirmed(payload, desktop) ?: return
        val app = context.applicationContext
        scope.launch {
            runCatching {
                if (paired(app, receipt) && AgentConnectorResponseStore.confirmReceipt(app, receipt)) request(app)
            }.onFailure { Log.w("GalaxySSIRecovery", "Result receipt confirmation deferred: ${it.javaClass.simpleName}") }
        }
    }

    private fun coordinator(context: Context): AgentResultReceiptCoordinator = coordinator ?: synchronized(this) {
        val app = context.applicationContext
        coordinator ?: AgentResultReceiptCoordinator(scope, drain = { drain(app) }, failed = {
            Log.w("GalaxySSIRecovery", "Result receipt queue deferred: ${it.javaClass.simpleName}")
        }).also { coordinator = it }
    }

    private fun drain(context: Context): Long? {
        val now = System.currentTimeMillis()
        for (work in AgentConnectorResponseStore.dueReceipts(context, now)) {
            if (!GalaxySSIMqttClient.isRequestReplyReady()) break
            if (!AgentConnectorResponseStore.claimReceipt(context, work, now)) continue
            runCatching {
                val receipt = work.receipt
                if (work.state == 1) {
                    // Confirmation is durable first; a crash during cleanup retries this branch.
                    AgentResultPageDatabase(context).use { it.checkpoint(receipt.desktop, receipt.payload()).clear(receipt.digest) }
                    AgentConnectorResponseStore.cleanedReceipt(context, receipt)
                } else if (paired(context, receipt)) {
                    val contact = receipt.fields[4]
                    GalaxySSIMqttClient.publishJsonForTransport(receipt.payload(),
                        GalaxySSIMqttClient.outgoingTopicFor(contact), contact)
                    // Neither publish success nor broker ACK retires the receipt intent.
                }
            }.onFailure { Log.w("GalaxySSIRecovery", "One result receipt deferred: ${it.javaClass.simpleName}") }
        }
        return AgentConnectorResponseStore.nextReceiptWake(context)
    }

    private fun paired(context: Context, receipt: AgentResultReceipt): Boolean {
        val link = GalaxySSILinkProtocol.serverLink(context, receipt.desktop) ?: return false
        val contact = AppStore.contactById(context, receipt.fields[4]) ?: return false
        return link.paired && link.routes.clientRouteId == receipt.fields[0] &&
            contact.optString("desktop_id") == receipt.desktop
    }
}
