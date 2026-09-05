package com.galaxyssi.chat

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

internal object AndroidAgentRemoteRecovery {
    private val client = AgentRemoteRecoveryClient()

    fun receive(context: Context, payload: JSONObject, desktopId: String) {
        val link = GalaxySSILinkProtocol.serverLink(context, desktopId) ?: return
        if (link.paired && link.routes.clientRouteId == payload.optString("client_route_id")) {
            client.receive(payload, desktopId)
        }
    }

    suspend fun recover(context: Context, handoffs: List<AgentHandoffRecord>): List<AgentRecoverableRun> =
        withContext(Dispatchers.IO) {
            val queries = handoffs.mapNotNull { handoff ->
                if (GalaxySSITransportPrivacyPolicy.isLocalOnly(JSONObject(handoff.request.context)
                        .put("conversation_id", handoff.request.conversationId))) return@mapNotNull null
                resolveQuery(context, handoff.request.toAgentId, handoff.sourceMessageId,
                    handoff.request.conversationId, handoff.request.context["turn_id"]?.toString().orEmpty()
                        .ifBlank { handoff.request.taskId })?.copy(handoff = handoff)
            }
            observe(context, queries).mapNotNull { (query, observation) ->
                val handoff = query.handoff ?: return@mapNotNull null
                AgentRecoverableRun(
                    handle = AgentRunHandle(handoff.request.runId, handoff.request.taskId,
                        handoff.request.toAgentId, observation.remoteRunId),
                    // Status revisions are not event cursors. Never skip unread remote events.
                    lastEventSequence = 0L, observation = observation)
            }
        }

    suspend fun recoverPendingReplies(context: Context, pending: List<AgentPendingDelivery>) =
        withContext(Dispatchers.IO) {
            val queries = pending.mapNotNull { delivery ->
                resolveQuery(context, delivery.contactId, delivery.sourceMessageId,
                    delivery.conversationId, delivery.turnId)?.takeIf {
                    AndroidAgentResultRecovery.eligible(context, it.desktopId, it.payload)
                }
            }
            observe(context, queries)
            Unit
        }

    private fun resolveQuery(context: Context, contactId: String, source: Long,
        conversationId: String, turnId: String): Query? {
        if (GalaxySSITransportPrivacyPolicy.isLocalOnly(JSONObject().put("conversation_id", conversationId))) return null
        val contact = AppStore.contactById(context, contactId) ?: return null
        val desktopId = contact.optString("desktop_id").takeIf { it.isNotBlank() } ?: return null
        val identity = AgentTaskIdentityStore.find(context, contactId, source) ?: return null
        if (identity.conversationId != conversationId || identity.turnId != turnId) return null
        val link = GalaxySSILinkProtocol.serverLink(context, desktopId) ?: return null
        if (!link.paired || link.routes.clientRouteId != identity.clientRouteId) return null
        val agentId = contact.optString("agent_id").ifBlank { AppStore.agentIdForContact(context, contactId) }
        if (agentId.isBlank()) return null
        return Query(null, desktopId, identity.clientRouteId, JSONObject()
            .put("client_route_id", identity.clientRouteId).put("conversation_id", identity.conversationId)
            .put("task_id", identity.taskId).put("turn_id", identity.turnId).put("contact_id", contactId)
            .put("source_message_id", source.toString()).put("agent_id", agentId))
    }

    private suspend fun observe(context: Context, queries: List<Query>): List<Pair<Query, AgentRemoteRecoveryObservation>> =
            buildList {
                queries.distinctBy { listOf(it.desktopId, it.payload.toString()) }
                    .groupBy { it.desktopId to it.routeId }.values.forEach { group ->
                    group.chunked(32).forEach { batch ->
                        val first = batch.first()
                        val observations = try {
                            client.query(first.desktopId, first.routeId, batch.map { it.payload }) { payload ->
                                GalaxySSIMqttClient.publishJsonForTransport(payload,
                                    GalaxySSIMqttClient.outgoingTopicFor(first.payload.getString("contact_id")),
                                    first.payload.getString("contact_id"))
                            }
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (error: Exception) {
                            Log.w("GalaxySSIRecovery", "Remote observation batch deferred: ${error.javaClass.simpleName}")
                            emptyList()
                        }
                        observations.forEachIndexed { index, result ->
                            val query = batch[index]
                            val version = AgentRemoteOutcomeCodec.version(result) ?: return@forEachIndexed
                            if (result.optString("remote_run_id").isBlank() || version.sequence < 0L ||
                                result.optString("status") == "unavailable") return@forEachIndexed
                            val fields = JSONObject(query.payload.toString()).put("execution_generation", version.generation)
                                .put("status_sequence", version.sequence).put("task_status", result.optString("status"))
                                .put("expected_status", result.optString("status"))
                            val identity = AgentRemoteOutcomeCodec.observation(fields) ?: return@forEachIndexed
                            val terminal = result.optString("status") in AgentRemoteOutcomeCodec.TERMINAL
                            val observation = AgentRemoteRecoveryObservation(query.payload.getString("conversation_id"),
                                query.desktopId, result.optString("status"), result.optString("task_id"),
                                result.optString("remote_run_id"), result.optLong("status_sequence", -1L),
                                executionGeneration = version.generation, awaitingTerminalReply = terminal)
                            if (observation.workspaceStatus == null || observation.remoteRunId.isBlank() ||
                                observation.statusSequence < 0L) return@forEachIndexed
                            if (!AgentConnectorResponseStore.observeExecution(context, identity)) return@forEachIndexed
                            if (terminal) {
                                AndroidAgentResultRecovery.request(context, query.desktopId, fields)
                            }
                            add(query to observation)
                        }
                    }
                }
            }

    private data class Query(val handoff: AgentHandoffRecord?, val desktopId: String,
        val routeId: String, val payload: JSONObject)
}
