package com.galaxyssi.chat.blob

import android.content.Context
import com.galaxyssi.chat.AgentAttachmentRecoveryRequest
import com.galaxyssi.chat.AgentConnectorResponse
import com.galaxyssi.chat.AgentConnectorResponseBus
import com.galaxyssi.chat.AgentConnectorResponseStore
import com.galaxyssi.chat.AgentPendingDeliveryStore
import com.galaxyssi.chat.AgentTerminalDeliveryStore
import com.galaxyssi.chat.AppStore
import com.galaxyssi.chat.GalaxySSILinkProtocol
import com.galaxyssi.chat.GalaxySSIMqttClient
import com.galaxyssi.chat.R
import org.json.JSONObject

/** Reuses the encrypted Agent inbox; never fan out through the UI/service delivery-failure race. */
internal object BlobFailureDelivery {
    fun persist(context: Context, body: JSONObject): Boolean {
        val manifest = body.getJSONObject("manifest")
        val receipt = body.getJSONObject("receipt")
        require(BlobFailureContract.matches(manifest, receipt))
        val code = receipt.getString("error_code")
        val source = manifest.optString("client_message_id").toLongOrNull()?.takeIf { it > 0 } ?: return false
        val contact = manifest.getString("contact_id")
        val conversation = manifest.getString("conversation_id")
        val turn = manifest.getString("turn_id")
        val task = manifest.getString("task_id")
        val requestId = manifest.optString("attachment_request_id")
        if (requestId.isNotBlank()) {
            val desktop = body.getString("desktop_id")
            val link = GalaxySSILinkProtocol.serverLink(context, desktop) ?: return false
            if (!link.paired || link.routes.remoteFingerprint != body.getString("fingerprint") ||
                link.routes.clientRouteId != manifest.getString("client_route_id") ||
                AppStore.desktopIdForContact(context, contact) != desktop) return false
            val result = AgentAttachmentRecoveryRequest(requestId, link.routes.clientRouteId,
                conversation, task, turn, contact, source, listOf(manifest.getString("attachment_id")))
                .result(status = "failed", error = code).put("error_code", code)
            return GalaxySSIMqttClient.publishBlobOffer(result, contact)
        }
        val response = AgentConnectorResponse(source, contact, message(context, code),
            conversationId = conversation, turnId = turn, taskId = task, success = false,
            deliveryFailureCode = code)
        if (AgentPendingDeliveryStore.isSuperseded(context, source, conversation, turn)) return true
        AgentTerminalDeliveryStore.find(context, source)?.let {
            return it.contactId == contact && it.conversationId == conversation && it.turnId == turn && it.taskId == task
        }
        val pending = AgentPendingDeliveryStore.find(context, source, contact)
            ?: return AgentConnectorResponseStore.wasRecorded(context, response)
        if (pending.conversationId != conversation || pending.turnId != turn || pending.taskId != task) return false
        return AgentConnectorResponseBus.publish(context, response) || AgentConnectorResponseStore.wasRecorded(context, response)
    }

    private fun message(context: Context, code: String): String = context.getString(when (code) {
        "blob_expired", "blob_not_found" -> R.string.agent_attachment_transfer_expired
        "blob_source_missing" -> R.string.agent_attachment_source_missing
        "blob_outgoing_identity_mismatch", "transfer_binding_mismatch" -> R.string.agent_attachment_identity_changed
        else -> R.string.agent_attachment_integrity_failed
    })
}
