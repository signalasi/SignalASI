package com.galaxyssi.chat

import android.content.Context
import android.util.Log
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.json.JSONObject

internal object AndroidAgentResultRecovery {
    private val client = AgentResultRecoveryClient()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val active = ConcurrentHashMap.newKeySet<List<String>>()
    private val transfers = Semaphore(2)

    fun receive(context: Context, payload: JSONObject, desktopId: String) {
        if (paired(context, desktopId, payload)) client.receive(payload, desktopId)
    }

    fun request(context: Context, desktopId: String, fields: JSONObject) {
        val app = context.applicationContext
        val generation = AgentRemoteOutcomeCodec.version(fields)?.generation ?: return
        val key = listOf(desktopId, generation.toString()) + AgentResultRecoveryClient.identity(fields)
        if (!active.add(key)) return
        scope.launch {
            try {
                transfers.withPermit {
                    val payload = client.fetch(desktopId, fields,
                        stillPending = { eligible(app, desktopId, fields) },
                        publish = { publish(app, desktopId, it) }) ?: return@withPermit
                    if (!eligible(app, desktopId, fields)) return@withPermit
                    val response = AgentRemoteOutcomeCodec.decode(payload, AgentRemoteOutcomeCodec.content(app, payload),
                        CodexStyleResponsePolicy.filterAssistantRichOutput(AgentRichContentCodec.fromEnvelope(payload)))
                        ?: return@withPermit
                    // The bus commits to the encrypted inbox before notifying the UI.
                    AgentConnectorResponseBus.publish(app, response)
                    acknowledge(app, payload, response)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                Log.w("GalaxySSIRecovery", "Final reply recovery deferred: ${error.javaClass.simpleName}")
            } finally { active.remove(key) }
        }
    }

    fun acknowledge(context: Context, payload: JSONObject, response: AgentConnectorResponse) {
        val receipt = payload.optJSONObject("result_recovery") ?: return
        if (!AgentConnectorResponseStore.wasRecorded(context, response)) return
        val digest = receipt.optString("sha256")
        if (!Regex("[a-f0-9]{64}").matches(digest)) return
        val contact = AppStore.contactById(context, payload.optString("contact_id")) ?: return
        val desktop = contact.optString("desktop_id")
        if (desktop.isBlank() || !paired(context, desktop, payload)) return
        val ack = JSONObject().put("type", "agent_task_result_received").put("sha256", digest)
            .put("execution_generation", response.executionGeneration)
        AgentResultRecoveryClient.FIELDS.forEach { ack.put(it, payload.optString(it)) }
        runCatching { publish(context, desktop, ack) }
            .onFailure { Log.w("GalaxySSIRecovery", "Durable result receipt deferred") }
    }

    internal fun eligible(context: Context, desktop: String, fields: JSONObject): Boolean {
        if (!paired(context, desktop, fields) || GalaxySSITransportPrivacyPolicy.isLocalOnly(fields)) return false
        val source = fields.optString("source_message_id").toLongOrNull() ?: return false
        if (AgentTerminalDeliveryStore.isTerminal(context, source)) return false
        val pending = AgentPendingDeliveryStore.find(context, source, fields.optString("contact_id")) ?: return false
        if (!AgentTaskIdentityStore.matchesRegistered(context, fields)) return false
        val observation = AgentRemoteOutcomeCodec.observation(fields) ?: return false
        // Discovery has no generation yet. Only a verified observation pins a body transfer.
        if (fields.has("execution_generation") &&
            !AgentConnectorResponseStore.isCurrentExecution(context, observation)) return false
        if (pending.sourceMessageId != source || pending.contactId != fields.optString("contact_id") ||
            pending.conversationId != fields.optString("conversation_id") || pending.turnId != fields.optString("turn_id") ||
            (pending.taskId != fields.optString("task_id") && pending.taskId != pending.turnId) ||
            pending.recoverySuccessorSourceMessageId > 0) return false
        return !AgentPendingDeliveryStore.isSuperseded(context, source, pending.conversationId, pending.turnId) &&
            !AgentConnectorResponseStore.containsTurn(context, pending.conversationId, pending.turnId)
    }

    private fun paired(context: Context, desktop: String, payload: JSONObject): Boolean {
        val link = GalaxySSILinkProtocol.serverLink(context, desktop) ?: return false
        return link.paired && link.routes.clientRouteId == payload.optString("client_route_id")
    }

    private fun publish(context: Context, desktop: String, payload: JSONObject): Boolean {
        if (!paired(context, desktop, payload)) return false
        val contact = payload.optString("contact_id")
        return GalaxySSIMqttClient.publishJsonForTransport(payload,
            GalaxySSIMqttClient.outgoingTopicFor(contact), contact)
    }

}
