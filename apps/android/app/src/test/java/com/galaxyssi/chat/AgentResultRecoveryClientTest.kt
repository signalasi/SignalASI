package com.galaxyssi.chat

import java.util.Base64
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class AgentResultRecoveryClientTest {
    @Test fun terminalFailuresAndStatusOnlyCancellationCanBeRecovered(): Unit = runBlocking {
        for (status in AgentRemoteOutcomeCodec.FAILURES) {
            val client = AgentResultRecoveryClient()
            val answer = body(if (status == "cancelled") "" else "actual failure").put("task_status", status)
                .put("execution_generation", 2).put("terminal_reason", status)
            val result = client.fetch("desktop", fields().put("execution_generation", 2).put("expected_status", status)) {
                assertEquals(2L, it.getLong("execution_generation"))
                client.receive(page(it, answer), "desktop")
            }
            assertEquals(status, result!!.getString("task_status"))
        }
    }

    @Test fun differentExecutionPageCannotSatisfyCurrentRequest(): Unit = runBlocking {
        val client = AgentResultRecoveryClient()
        lateinit var sent: JSONObject
        val request = async(start = CoroutineStart.UNDISPATCHED) {
            client.fetch("desktop", fields().put("execution_generation", 2)) { sent = it; true }
        }
        val answer = body().put("execution_generation", 2)
        assertFalse(client.receive(page(sent, answer).put("execution_generation", 1), "desktop"))
        assertTrue(client.receive(page(sent, answer), "desktop"))
        assertNotNull(request.await())
    }

    @Test fun innerGenerationAndObservedStatusMustMatchArchivePayload(): Unit = runBlocking {
        for (field in listOf("execution_generation", "task_status")) {
            val client = AgentResultRecoveryClient()
            val answer = body().put("execution_generation", 2)
                .put(field, if (field == "task_status") "failed" else 1)
            val result = client.fetch("desktop", fields().put("execution_generation", 2).put("expected_status", "completed")) {
                client.receive(page(it, answer), "desktop")
            }
            assertNull(result)
        }
    }

    private fun fields() = JSONObject().put("client_route_id", "route").put("conversation_id", "conversation")
        .put("task_id", "task").put("turn_id", "turn").put("contact_id", "contact")
        .put("source_message_id", "42").put("agent_id", "codex")
    private fun body(text: String = "\u6062\u590d\u7b54\u6848") = fields().put("type", "text")
        .put("task_status", "completed").put("content", text)
    private fun page(request: JSONObject, body: JSONObject): JSONObject {
        val bytes = body.toString().toByteArray(Charsets.UTF_8)
        val offset = request.getInt("page_index") * AgentResultRecoveryClient.PAGE_BYTES
        val chunk = bytes.copyOfRange(offset, minOf(bytes.size, offset + AgentResultRecoveryClient.PAGE_BYTES))
        return JSONObject(request.toString()).put("type", "agent_task_result_page").put("status", "ready")
            .put("sha256", AgentResultRecoveryClient.sha256(bytes)).put("total_bytes", bytes.size)
            .put("page_count", (bytes.size + AgentResultRecoveryClient.PAGE_BYTES - 1) / AgentResultRecoveryClient.PAGE_BYTES)
            .put("page_sha256", AgentResultRecoveryClient.sha256(chunk))
            .put("data_b64", Base64.getEncoder().encodeToString(chunk))
    }

    @Test fun unicodeAndStructuredResultSurviveMultiplePages(): Unit = runBlocking {
        val client = AgentResultRecoveryClient()
        val answer = body("\u4f60\u597d\uD83D\uDE80".repeat(10000))
            .put("rich_output", JSONObject().put("blocks", org.json.JSONArray()))
        var requests = 0
        val result = client.fetch("desktop", fields()) { request ->
            requests++
            assertTrue(client.receive(page(request, answer), "desktop")); true
        }
        assertTrue(requests > 1)
        assertEquals(answer.getString("content"), result!!.getString("content"))
        assertEquals(answer.getJSONObject("rich_output").toString(), result.getJSONObject("rich_output").toString())
        assertNotNull(result.optJSONObject("result_recovery"))
        assertEquals(0, client.pendingCount)
    }

    @Test fun everyIdentityNonceDeviceAndPageMustMatch(): Unit = runBlocking {
        val client = AgentResultRecoveryClient()
        lateinit var sent: JSONObject
        val request = async(start = CoroutineStart.UNDISPATCHED) {
            client.fetch("desktop", fields()) { sent = it; true }
        }
        for (field in AgentResultRecoveryClient.FIELDS + listOf("request_id", "type")) {
            assertFalse(field, client.receive(page(sent, body()).put(field, "wrong"), "desktop"))
        }
        assertFalse(client.receive(page(sent, body()).put("page_index", 1), "desktop"))
        assertFalse(client.receive(page(sent, body()), "other-device"))
        assertFalse(request.isCompleted)
        assertTrue(client.receive(page(sent, body()), "desktop"))
        assertNotNull(request.await())
    }

    @Test fun tamperingBoundsAndInconsistentManifestNeverReachInbox(): Unit = runBlocking {
        for ((field, value) in listOf("sha256" to "0".repeat(64), "page_sha256" to "0".repeat(64),
            "total_bytes" to 0, "total_bytes" to Long.MAX_VALUE, "page_count" to 2,
            "data_b64" to "!!!", "data_b64" to "a".repeat(30000))) {
            val client = AgentResultRecoveryClient()
            val result = client.fetch("desktop", fields()) {
                client.receive(page(it, body()).put(field, value), "desktop"); true
            }
            assertNull(field, result)
            assertEquals(0, client.pendingCount)
        }
    }

    @Test fun pageManifestCannotChangeHalfwayThroughReply(): Unit = runBlocking {
        val client = AgentResultRecoveryClient()
        val result = client.fetch("desktop", fields()) {
            val answer = body((if (it.getInt("page_index") == 0) "a" else "b").repeat(20000))
            client.receive(page(it, answer), "desktop"); true
        }
        assertNull(result)
    }

    @Test fun innerPayloadCannotTargetAnotherConversation(): Unit = runBlocking {
        for (field in AgentResultRecoveryClient.FIELDS + listOf("type", "task_status")) {
            val client = AgentResultRecoveryClient()
            val result = client.fetch("desktop", fields()) {
                client.receive(page(it, body().put(field, "wrong")), "desktop"); true
            }
            assertNull(field, result)
        }
    }

    @Test fun cancellationAndLatePageDoNotCompleteNewRequest(): Unit = runBlocking {
        val client = AgentResultRecoveryClient()
        lateinit var old: JSONObject
        val cancelled = async(start = CoroutineStart.UNDISPATCHED) {
            client.fetch("desktop", fields()) { old = it; true }
        }
        cancelled.cancel(); cancelled.join()
        lateinit var current: JSONObject
        val next = async(start = CoroutineStart.UNDISPATCHED) {
            client.fetch("desktop", fields()) { current = it; true }
        }
        assertFalse(client.receive(page(old, body()), "desktop"))
        assertFalse(next.isCompleted)
        assertTrue(client.receive(page(current, body()), "desktop"))
        assertNotNull(next.await())
        assertEquals(0, client.pendingCount)
    }

    @Test fun localCancellationOrAlreadyDeliveredResultStopsBeforeNextPage(): Unit = runBlocking {
        val client = AgentResultRecoveryClient()
        var eligible = true
        var count = 0
        val result = client.fetch("desktop", fields(), stillPending = { eligible }) {
            count++; client.receive(page(it, body("a".repeat(20000))), "desktop"); eligible = false; true
        }
        assertNull(result)
        assertEquals(1, count)
    }

    @Test fun timeoutPublishFailureAndUnavailableDoNotSynthesizeAnswers(): Unit = runBlocking {
        val client = AgentResultRecoveryClient()
        assertNull(client.fetch("desktop", fields(), timeoutMillis = 5) { true })
        assertNull(client.fetch("desktop", fields()) { false })
        assertNull(client.fetch("desktop", fields()) {
            client.receive(JSONObject(it.toString()).put("type", "agent_task_result_page").put("status", "unavailable"), "desktop")
            true
        })
        assertEquals(0, client.pendingCount)
    }

    @Test fun privacyOnlyConversationsNeverPublish(): Unit = runBlocking {
        val client = AgentResultRecoveryClient()
        for (conversation in listOf("global-cognition:x", "self-evolution:x", "memory-evolution:x")) {
            assertNull(client.fetch("desktop", fields().put("conversation_id", conversation)) {
                error("Private global context must stay local")
            })
        }
    }

    @Test fun alreadyCompletedRequestCannotReceiveSamePageAgain(): Unit = runBlocking {
        val client = AgentResultRecoveryClient()
        lateinit var received: JSONObject
        assertNotNull(client.fetch("desktop", fields()) {
            received = page(it, body()); client.receive(received, "desktop"); true
        })
        assertFalse(client.receive(received, "desktop"))
    }
}
