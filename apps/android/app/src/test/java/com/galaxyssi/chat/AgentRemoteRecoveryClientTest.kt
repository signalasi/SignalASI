package com.galaxyssi.chat

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class AgentRemoteRecoveryClientTest {
    private fun item() = JSONObject().put("client_route_id", "route").put("conversation_id", "conversation")
        .put("task_id", "task").put("turn_id", "turn").put("contact_id", "contact")
        .put("source_message_id", "42").put("agent_id", "codex")
    private fun result(request: JSONObject) = JSONObject(request.toString())
        .put("type", "agent_task_recovery_result")

    @Test fun exactResponseCompletesAndReleasesRequest(): Unit = runBlocking {
        val client = AgentRemoteRecoveryClient()
        lateinit var sent: JSONObject
        val query = async(start = CoroutineStart.UNDISPATCHED) {
            client.query("desktop", "route", listOf(item())) { sent = it; true }
        }
        assertEquals(1, client.pendingCount)
        assertTrue(client.receive(result(sent), "desktop"))
        assertEquals("task", query.await().single().getString("task_id"))
        assertEquals(0, client.pendingCount)
        assertFalse(client.receive(result(sent), "desktop"))
    }

    @Test fun everyIdentityAndAuthenticatedDeviceMustMatch(): Unit = runBlocking {
        val client = AgentRemoteRecoveryClient()
        lateinit var sent: JSONObject
        val query = async(start = CoroutineStart.UNDISPATCHED) {
            client.query("desktop", "route", listOf(item())) { sent = it; true }
        }
        assertFalse(client.receive(result(sent), "other-desktop"))
        for (field in item().keys()) {
            val bad = result(sent)
            bad.getJSONArray("items").getJSONObject(0).put(field, "wrong")
            assertFalse(field, client.receive(bad, "desktop"))
        }
        assertFalse(client.receive(result(sent).put("client_route_id", "wrong"), "desktop"))
        assertFalse(client.receive(result(sent).put("request_id", "old"), "desktop"))
        assertFalse(query.isCompleted)
        assertTrue(client.receive(result(sent), "desktop"))
        query.await()
    }

    @Test fun duplicatesAndMissingItemsCannotCompleteBatch(): Unit = runBlocking {
        val client = AgentRemoteRecoveryClient()
        lateinit var sent: JSONObject
        val second = item().put("task_id", "task-2")
        val query = async(start = CoroutineStart.UNDISPATCHED) {
            client.query("desktop", "route", listOf(item(), second)) { sent = it; true }
        }
        assertFalse(client.receive(result(sent).put("items", JSONArray(listOf(item(), item()))), "desktop"))
        assertFalse(client.receive(result(sent).put("items", JSONArray(listOf(item()))), "desktop"))
        assertTrue(client.receive(result(sent).put("items", JSONArray(listOf(second, item()))), "desktop"))
        assertEquals(listOf("task", "task-2"), query.await().map { it.getString("task_id") })
    }

    @Test fun cancellationDiscardsLateResponseWithoutAffectingNextQuery(): Unit = runBlocking {
        val client = AgentRemoteRecoveryClient()
        lateinit var first: JSONObject
        val cancelled = async(start = CoroutineStart.UNDISPATCHED) {
            client.query("desktop", "route", listOf(item())) { first = it; true }
        }
        cancelled.cancel()
        cancelled.join()
        assertEquals(0, client.pendingCount)
        lateinit var second: JSONObject
        val active = async(start = CoroutineStart.UNDISPATCHED) {
            client.query("desktop", "route", listOf(item())) { second = it; true }
        }
        assertFalse(client.receive(result(first), "desktop"))
        assertFalse(active.isCompleted)
        assertTrue(client.receive(result(second), "desktop"))
        active.await()
    }

    @Test fun queryTimeoutIsAnUnavailableObservation(): Unit = runBlocking {
        val client = AgentRemoteRecoveryClient()
        val outcomes = mutableListOf<String>()
        assertTrue(client.query("desktop", "route", listOf(item()), timeoutMillis = 5,
            report = { outcomes.add(it) }) { true }.isEmpty())
        assertEquals(listOf("response_timeout"), outcomes)
        assertEquals(0, client.pendingCount)
    }

    @Test fun publishFailureReleasesRequest(): Unit = runBlocking {
        val client = AgentRemoteRecoveryClient()
        val outcomes = mutableListOf<String>()
        assertTrue(client.query("desktop", "route", listOf(item()), report = { outcomes.add(it) }) { false }.isEmpty())
        assertEquals(listOf("publish_rejected"), outcomes)
        assertEquals(0, client.pendingCount)
    }

    @Test fun remoteUnavailableIsNotReportedAsNetworkTimeout(): Unit = runBlocking {
        for (status in listOf("unavailable", "completed")) {
            val client = AgentRemoteRecoveryClient()
            val outcomes = mutableListOf<String>()
            val response = client.query("desktop", "route", listOf(item()), report = { outcomes.add(it) }) { sent ->
                val reply = result(sent)
                reply.getJSONArray("items").getJSONObject(0).put("status", status)
                client.receive(reply, "desktop")
                true
            }
            assertEquals(status, response.single().getString("status"))
            assertEquals(listOf(if (status == "unavailable") "remote_unavailable" else "authenticated_response"), outcomes)
        }
    }

    @Test fun batchCannotHideLocalOnlyConversationFromPrivacyPolicy(): Unit = runBlocking {
        val client = AgentRemoteRecoveryClient()
        for (conversation in listOf("global-cognition:one", "self-evolution:two", "memory-evolution:three")) {
            val result = client.query("desktop", "route", listOf(item(), item().put("conversation_id", conversation))) {
                error("Local-only identities must not leave the phone")
            }
            assertTrue(result.isEmpty())
        }
        assertEquals(0, client.pendingCount)
    }
}
