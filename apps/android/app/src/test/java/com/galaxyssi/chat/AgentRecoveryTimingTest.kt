package com.galaxyssi.chat

import com.galaxyssi.chat.metrics.*
import java.util.Base64
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class AgentRecoveryTimingTest {
    private class Sink : AgentTimingSink {
        val points = mutableListOf<AgentTimingPoint>()
        override fun append(point: AgentTimingPoint) { points += point }
        override fun snapshot() = points.toList()
    }
    private val sink = Sink()
    private val tracer = AgentLatencyTracer(sink)
    private var now = 0L
    private val timing = AgentRecoveryTiming(
        { trace, stage, operation, outcome, at -> tracer.recordOpaque(trace, stage, operation, outcome, at) },
        { now }
    )
    private fun metric(phase: String) = AgentLatencyContract.summarize(sink.points)
        .getValue("phone_recovery_${phase}_ms")
    private fun fields() = JSONObject().put("client_route_id", "route").put("conversation_id", "conversation")
        .put("task_id", "private-task").put("turn_id", "turn").put("contact_id", "contact")
        .put("source_message_id", "42").put("agent_id", "codex")
    private fun page(request: JSONObject, corrupt: Boolean = false): JSONObject {
        val bytes = fields().put("type", "text").put("task_status", "completed")
            .put("content", "private answer").toString().toByteArray()
        return JSONObject(request.toString()).put("type", "agent_task_result_page").put("status", "ready")
            .put("sha256", AgentResultRecoveryClient.sha256(bytes)).put("total_bytes", bytes.size)
            .put("page_count", 1).put("page_sha256", if (corrupt) "0".repeat(64) else AgentResultRecoveryClient.sha256(bytes))
            .put("data_b64", Base64.getEncoder().encodeToString(bytes))
    }

    @Test fun exactDurationAndDoubleCloseAreSafe() {
        val span = timing.begin("private-task", "query")
        now = 6_500_000
        span.outcome = "completed"
        span.close(); span.close()
        assertEquals(2, sink.points.size)
        assertEquals(6.5, metric("query").p95Ms!!, 0.0)
        assertFalse(sink.points.toString().contains("private-task"))
        assertEquals(64, sink.points.first().operationId.length)
    }

    @Test fun attemptsCannotMergeAndIncompleteIsNotSuccess() {
        timing.begin("same-task", "page").close()
        timing.begin("same-task", "page").use { it.outcome = "completed" }
        timing.begin("same-task", "page")
        assertEquals(3, sink.points.map { it.operationId }.distinct().size)
        assertEquals(1, metric("page").count)
        assertEquals(1, metric("page").unsuccessful)
        assertEquals(1, metric("page").incomplete)
    }

    @Test fun brokenDiagnosticsCannotMaskBusinessException() {
        val broken = AgentRecoveryTiming({ _, _, _, _, _ -> error("diagnostic failure") })
        val failure = IllegalStateException("original failure")
        try {
            broken.begin("task", "body").use { throw failure }
            fail("Expected original failure")
        } catch (actual: IllegalStateException) { assertSame(failure, actual) }
        timing.begin("", "page").close()
        timing.begin("task", "unknown").close()
        assertTrue(sink.points.isEmpty())
    }

    @Test fun rejectedPublishAndTimeoutAreNotSuccessfulQueries(): Unit = runBlocking {
        val client = AgentRemoteRecoveryClient()
        assertTrue(client.query("desktop", "route", listOf(fields()), timing = timing) { false }.isEmpty())
        assertTrue(client.query("desktop", "route", listOf(fields()), timeoutMillis = 5, timing = timing) { true }.isEmpty())
        assertEquals(0, client.pendingCount)
        assertEquals(0, metric("query").count)
        assertEquals(2, metric("query").unsuccessful)
        assertEquals(listOf("failed", "timed_out"), sink.points.filter { it.stage.endsWith("finished") }.map { it.outcome })
    }

    @Test fun cancelledQueryKeepsCancellationAndReleasesRequest(): Unit = runBlocking {
        val client = AgentRemoteRecoveryClient()
        val running = async(start = CoroutineStart.UNDISPATCHED) {
            client.query("desktop", "route", listOf(fields()), timing = timing) { true }
        }
        running.cancel(); running.join()
        assertTrue(running.isCancelled)
        assertEquals(0, client.pendingCount)
        assertEquals("cancelled", sink.points.last().outcome)
    }

    @Test fun batchCountsOneRoundtripAndUnavailableIsSeparate(): Unit = runBlocking {
        val client = AgentRemoteRecoveryClient()
        for (status in listOf("unavailable", "failed")) {
            val items = listOf(fields(), fields().put("task_id", "another"))
            val response = client.query("desktop", "route", items, timing = timing) { sent ->
                val result = JSONObject(sent.toString()).put("type", "agent_task_recovery_result")
                for (index in 0..1) result.getJSONArray("items").getJSONObject(index).put("status", status)
                client.receive(result, "desktop")
            }
            assertEquals(2, response.size)
        }
        assertEquals(4, sink.points.size)
        assertEquals(1, metric("query").count)
        assertEquals(1, metric("query").unsuccessful)
    }

    @Test fun localOnlyAndInvalidRequestsProduceNoTiming(): Unit = runBlocking {
        val client = AgentRemoteRecoveryClient()
        assertTrue(client.query("desktop", "route", listOf(fields().put("conversation_id", "self-evolution:test")),
            timing = timing) { error("must not publish") }.isEmpty())
        try {
            client.query("desktop", "wrong-route", listOf(fields()), timing = timing) { error("must not publish") }
            fail("Expected validation failure")
        } catch (_: IllegalArgumentException) { }
        assertTrue(sink.points.isEmpty())
    }

    @Test fun authenticatedPageAndValidatedBodyAreDifferentMeasurements(): Unit = runBlocking {
        val client = AgentResultRecoveryClient()
        for (corrupt in listOf(true, false)) {
            val result = client.fetch("desktop", fields(), timing = timing) { request ->
                client.receive(page(request, corrupt), "desktop")
            }
            if (corrupt) assertNull(result) else assertEquals("private answer", result!!.getString("content"))
        }
        assertEquals(2, metric("page").count)
        assertEquals(1, metric("body").count)
        assertEquals(1, metric("body").unsuccessful)
        assertFalse(sink.points.toString().contains("private answer"))
    }

    @Test fun pageTimeoutAndCancelledBodyAreRecorded(): Unit = runBlocking {
        val client = AgentResultRecoveryClient()
        assertNull(client.fetch("desktop", fields(), timeoutMillis = 5, timing = timing) { true })
        val pending = async(start = CoroutineStart.UNDISPATCHED) {
            client.fetch("desktop", fields(), timing = timing) { true }
        }
        pending.cancel(); pending.join()
        assertEquals(0, client.pendingCount)
        assertEquals(2, metric("page").unsuccessful)
        assertEquals(2, metric("body").unsuccessful)
        assertTrue(sink.points.any { it.outcome == "timed_out" })
        assertEquals("cancelled", sink.points.last().outcome)
    }

    @Test fun failedCheckpointIsNotDurableSuccess(): Unit = runBlocking {
        val client = AgentResultRecoveryClient()
        val checkpoint = object : AgentResultPageCheckpoint {
            override fun manifest(): AgentResultPageManifest? = null
            override fun read(manifest: AgentResultPageManifest, page: Int): ByteArray? = null
            override fun write(manifest: AgentResultPageManifest, page: Int, bytes: ByteArray) = false
            override fun clear(digest: String) = Unit
        }
        assertNull(client.fetch("desktop", fields(), checkpoint = checkpoint, timing = timing) {
            client.receive(page(it), "desktop")
        })
        assertEquals(1, metric("checkpoint").unsuccessful)
        assertEquals(0, metric("checkpoint").count)
        assertEquals(1, metric("body").unsuccessful)
    }

    @Test fun cachedRecoveryDoesNotInventNetworkOrWriteSamples(): Unit = runBlocking {
        var savedManifest: AgentResultPageManifest? = null
        var savedBytes: ByteArray? = null
        val checkpoint = object : AgentResultPageCheckpoint {
            override fun manifest() = savedManifest
            override fun read(manifest: AgentResultPageManifest, page: Int) = savedBytes?.copyOf()
            override fun write(manifest: AgentResultPageManifest, page: Int, bytes: ByteArray): Boolean {
                savedManifest = manifest
                savedBytes = bytes.copyOf()
                return true
            }
            override fun clear(digest: String) { savedBytes?.fill(0); savedBytes = null; savedManifest = null }
        }
        val first = AgentResultRecoveryClient()
        assertNotNull(first.fetch("desktop", fields(), checkpoint = checkpoint, timing = timing) {
            first.receive(page(it), "desktop")
        })
        assertNotNull(AgentResultRecoveryClient().fetch("desktop", fields(), checkpoint = checkpoint, timing = timing) {
            error("Complete cached result must not be downloaded again")
        })
        assertEquals(2, metric("body").count)
        assertEquals(1, metric("page").count)
        assertEquals(1, metric("checkpoint").count)
        checkpoint.clear("")
    }

    @Test fun pagePublishExceptionRemainsOriginalAndRequestsAreReleased(): Unit = runBlocking {
        val client = AgentResultRecoveryClient()
        val failure = IllegalStateException("network unavailable")
        try {
            client.fetch("desktop", fields(), timing = timing) { throw failure }
            fail("Expected publish failure")
        } catch (actual: IllegalStateException) { assertSame(failure, actual) }
        assertEquals(0, client.pendingCount)
        assertEquals(1, metric("page").unsuccessful)
        assertEquals(1, metric("body").unsuccessful)
        assertFalse(sink.points.toString().contains("network unavailable"))
    }
}
