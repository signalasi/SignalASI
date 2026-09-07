package com.galaxyssi.chat

import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import com.galaxyssi.chat.metrics.AgentRecoveryTiming
import kotlinx.coroutines.CancellationException

internal class AgentRemoteRecoveryClient {
    private data class Pending(
        val desktopId: String,
        val routeId: String,
        val identities: List<List<String>>,
        val result: CompletableDeferred<List<JSONObject>> = CompletableDeferred()
    )

    private val pending = ConcurrentHashMap<String, Pending>()

    suspend fun query(
        desktopId: String,
        routeId: String,
        items: List<JSONObject>,
        timeoutMillis: Long = 8_000L,
        report: (String) -> Unit = {},
        timing: AgentRecoveryTiming? = null,
        publish: (JSONObject) -> Boolean
    ): List<JSONObject> {
        require(desktopId.isNotBlank() && routeId.isNotBlank())
        require(items.size in 1..32)
        if (items.any { GalaxySSITransportPrivacyPolicy.isLocalOnly(it) }) return emptyList()
        val identities = items.map(::identity)
        require(identities.all { values -> values.all { it.isNotBlank() && it.length <= 200 } })
        require(identities.all { it.first() == routeId } && identities.distinct().size == items.size)
        val requestId = UUID.randomUUID().toString()
        val request = Pending(desktopId, routeId, identities)
        pending[requestId] = request
        // A batch is one round trip, not one duplicate sample for each item.
        val span = timing?.begin(items.first().optString("task_id"), "query")
        try {
            val payload = JSONObject().put("type", "agent_task_recovery_request")
                .put("request_id", requestId).put("client_route_id", routeId)
                .put("desktop_id", desktopId).put("items", JSONArray(items))
            if (!publish(payload)) {
                report("publish_rejected")
                return emptyList()
            }
            val response = withTimeoutOrNull(timeoutMillis) { request.result.await() }
            span?.outcome = if (response == null) "timed_out" else if (response.any {
                it.optString("status") == "unavailable"
            }) "failed" else "completed"
            report(if (response == null) "response_timeout" else if (response.any {
                    it.optString("status") == "unavailable"
                }) "remote_unavailable" else "authenticated_response")
            return response ?: emptyList()
        } catch (cancelled: CancellationException) {
            span?.outcome = "cancelled"
            throw cancelled
        } finally {
            pending.remove(requestId, request)
            request.result.cancel()
            span?.close()
        }
    }

    fun receive(payload: JSONObject, authenticatedDesktopId: String): Boolean {
        val request = pending[payload.optString("request_id")] ?: return false
        if (authenticatedDesktopId != request.desktopId ||
            payload.optString("client_route_id") != request.routeId) return false
        val array = payload.optJSONArray("items") ?: return false
        if (array.length() != request.identities.size) return false
        val items = (0 until array.length()).map { array.optJSONObject(it) ?: return false }
        val identities = items.map(::identity)
        if (identities.distinct().size != items.size || identities.toSet() != request.identities.toSet()) return false
        return request.result.complete(request.identities.map { key -> items[identities.indexOf(key)] })
    }

    internal val pendingCount: Int get() = pending.size

    companion object {
        private val FIELDS = listOf("client_route_id", "conversation_id", "task_id", "turn_id",
            "contact_id", "source_message_id", "agent_id")
        private fun identity(json: JSONObject): List<String> = FIELDS.map { json.optString(it) }
    }
}
