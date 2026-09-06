package com.galaxyssi.chat

import com.galaxyssi.chat.metrics.*
import org.junit.Assert.*
import org.junit.Test

class AgentTransportTimingTest {
    private class Fixture(limit: Int = 1024, ttl: Long = 3_600_000_000_000L) {
        var now = 0L
        val points = mutableListOf<AgentTimingPoint>()
        val tracer = AgentLatencyTracer(object : AgentTimingSink {
            override fun append(point: AgentTimingPoint) { points.add(point) }
            override fun snapshot() = points.toList()
        }, { now }, { 1L }, "a".repeat(32))
        val timing = AgentTransportTiming({ trace, stage, operation, outcome, at ->
            tracer.recordOpaque(trace, stage, operation, outcome, at)
        }, { now }, limit, ttl)
        fun at(ms: Long) { now = ms * 1_000_000 }
        fun metric(id: String) = AgentLatencyContract.summarize(points).getValue("phone_${id}_ms")
    }

    @Test fun queueBrokerAndPeerAreIndependentBoundaries() {
        val f = Fixture()
        f.timing.queued("desktop", "message", "task")
        f.at(10); val attempt = f.timing.begin("desktop", "message")
        f.at(30); f.timing.broker(attempt)
        assertEquals(0, f.metric("peer_receipt").count)
        f.at(50); f.timing.received("desktop", "message")
        assertEquals(10.0, f.metric("transport_queue").p50Ms)
        assertEquals(20.0, f.metric("broker_ack").p50Ms)
        assertEquals(50.0, f.metric("peer_receipt").p50Ms)
    }

    @Test fun oldAttemptCannotCompleteRetryAfterDisconnect() {
        val f = Fixture()
        f.timing.queued("d", "m", "t")
        val first = f.timing.begin("d", "m")
        f.at(12); f.timing.disconnected()
        f.at(20); val retry = f.timing.begin("d", "m")
        f.at(21); f.timing.broker(first)
        assertEquals(0, f.metric("broker_ack").count)
        f.at(24); f.timing.broker(retry); f.timing.broker(retry)
        assertEquals(1, f.metric("broker_ack").unsuccessful)
        assertEquals(1, f.metric("broker_ack").count)
        assertEquals(4.0, f.metric("broker_ack").p50Ms)
        assertEquals(1, f.metric("transport_queue").count)
    }

    @Test fun wrongPeerAndDuplicatesNeverCreateReceiptSamples() {
        val f = Fixture()
        f.timing.queued("a", "m", "t")
        f.timing.queued("a", "m", "other-task")
        f.at(2); f.timing.received("b", "m")
        assertEquals(0, f.metric("peer_receipt").count)
        f.at(3); f.timing.received("a", "m"); f.timing.received("a", "m")
        assertEquals(1, f.metric("peer_receipt").count)
        assertEquals(setOf(AgentLatencyContract.opaqueId("t")), f.points.map { it.traceId }.toSet())
    }

    @Test fun peerReceiptCanArriveBeforeBrokerCallback() {
        val f = Fixture()
        f.timing.queued("d", "m", "t")
        val attempt = f.timing.begin("d", "m")
        f.at(4); f.timing.received("d", "m")
        f.at(5); f.timing.broker(attempt)
        assertEquals(4.0, f.metric("peer_receipt").p50Ms)
        assertEquals(5.0, f.metric("broker_ack").p50Ms)
    }

    @Test fun evictionAndRestartProduceMissingEvidenceNotFabricatedTimes() {
        val f = Fixture(limit = 2)
        repeat(3) { f.timing.queued("d", "m$it", "t$it") }
        assertNull(f.timing.begin("d", "m0"))
        f.timing.received("d", "m0")
        assertEquals(0, f.metric("peer_receipt").count)
        assertNull(Fixture().timing.begin("d", "m2"))
    }

    @Test fun expiredMetadataAndUnknownMessagesAreNotMeasured() {
        val f = Fixture(ttl = 10_000_000)
        f.timing.queued("d", "m", "t")
        f.at(11)
        assertNull(f.timing.begin("d", "m"))
        f.timing.received("d", "m")
        assertEquals(0, f.metric("peer_receipt").count)
        assertNull(f.timing.begin("d", "unknown"))
    }

    @Test fun failureIsNotIncludedInSuccessfulPercentiles() {
        val f = Fixture()
        f.timing.queued("d", "m", "t")
        val failed = f.timing.begin("d", "m")
        f.at(8); f.timing.broker(failed, "failed")
        val retry = f.timing.begin("d", "m")
        f.at(10); f.timing.broker(retry)
        assertEquals(2.0, f.metric("broker_ack").p95Ms)
        assertEquals(1, f.metric("broker_ack").unsuccessful)
    }

    @Test fun registryKeysAreUnambiguousAndAllPointsAreContentFree() {
        val f = Fixture()
        f.timing.queued("a", "bc", "private task")
        f.timing.queued("ab", "c", "other task")
        f.at(1); f.timing.received("a", "bc"); f.timing.received("ab", "c")
        assertEquals(2, f.metric("peer_receipt").count)
        assertTrue(f.points.all(AgentLatencyContract::valid))
        assertFalse(f.points.toString().contains("private task"))
    }

    @Test fun diagnosticSinkFailureDoesNotInterruptPublishLifecycle() {
        val timing = AgentTransportTiming({ _, _, _, _, _ -> error("full disk") })
        timing.queued("d", "m", "t")
        val attempt = timing.begin("d", "m")
        assertNotNull(attempt)
        timing.broker(attempt); timing.received("d", "m"); timing.disconnected()
    }
}
