package com.galaxyssi.chat

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.yield

/** Read-only reply recovery is independent of foreground task/maintenance scheduling. */
internal object AndroidAgentRecoveryWake {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var coordinator: AgentRecoveryWakeCoordinator? = null

    fun connectionChanged(context: Context, connected: Boolean) {
        AndroidTransportReceipts.connectionChanged(context, connected)
        AndroidAgentResultReceipts.connectionChanged(context, connected)
        coordinator(context).connectionChanged(connected)
    }

    fun request(context: Context) {
        AndroidAgentResultReceipts.request(context)
        coordinator(context).request(GalaxySSIMqttClient.isRequestReplyReady())
    }

    private fun coordinator(context: Context): AgentRecoveryWakeCoordinator = coordinator ?: synchronized(this) {
        coordinator ?: create(context.applicationContext).also { coordinator = it }
    }

    private fun create(context: Context) = AgentRecoveryWakeCoordinator(scope, recover = {
        var beforeSource: Long? = null
        while (GalaxySSIMqttClient.isRequestReplyReady()) {
            val page = AgentPendingDeliveryStore.page(context, beforeSource)
            val next = page.nextBeforeSource ?: break
            AndroidAgentRemoteRecovery.recoverPendingReplies(context, page.deliveries)
            beforeSource = next
            yield()
        }
    }, failed = { error ->
        Log.w("GalaxySSIRecovery", "Reply recovery wake deferred: ${error.javaClass.simpleName}")
    })
}
