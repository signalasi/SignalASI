package com.galaxyssi.chat

import org.eclipse.paho.client.mqttv3.MqttException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MqttPublishGuardTest {
    @Test
    fun `successful publish preserves delivery token`() {
        val result = MqttPublishGuard.attempt { 42 }

        assertTrue(result.isSuccess)
        assertEquals(42, result.getOrNull())
    }

    @Test
    fun `publish backpressure is captured instead of escaping`() {
        val result = MqttPublishGuard.attempt<Int> {
            throw MqttException(MqttException.REASON_CODE_MAX_INFLIGHT.toInt())
        }

        assertTrue(result.isFailure)
        assertEquals(
            MqttException.REASON_CODE_MAX_INFLIGHT.toInt(),
            (result.exceptionOrNull() as MqttException).reasonCode
        )
    }

    @Test
    fun `offline encrypted messages remain accepted for reconnect delivery`() {
        val result = MqttOutboxDispatchPolicy.result(connected = false, published = false)

        assertEquals(MqttPublishResult.QUEUED, result)
        assertTrue(result.accepted)
    }

    @Test
    fun `publish backpressure keeps the durable message queued`() {
        val result = MqttOutboxDispatchPolicy.result(connected = true, published = false)

        assertEquals(MqttPublishResult.QUEUED, result)
        assertTrue(result.accepted)
    }

    @Test
    fun `successful immediate publish is distinguished from queued delivery`() {
        val result = MqttOutboxDispatchPolicy.result(connected = true, published = true)

        assertEquals(MqttPublishResult.PUBLISHED, result)
        assertTrue(result.accepted)
        assertFalse(MqttPublishResult.FAILED.accepted)
    }

    @Test
    fun `connection retry backs off and caps at the longest delay`() {
        val policy = MqttConnectionRetryPolicy(longArrayOf(2L, 5L, 10L))

        assertEquals(2L, policy.nextDelayMillis())
        assertEquals(5L, policy.nextDelayMillis())
        assertEquals(10L, policy.nextDelayMillis())
        assertEquals(10L, policy.nextDelayMillis())
    }

    @Test
    fun `successful connection resets retry backoff`() {
        val policy = MqttConnectionRetryPolicy(longArrayOf(2L, 5L))

        assertEquals(2L, policy.nextDelayMillis())
        assertEquals(5L, policy.nextDelayMillis())
        policy.reset()

        assertEquals(2L, policy.nextDelayMillis())
    }

    @Test
    fun `relationship subscriptions become ready only when every link succeeds`() {
        val state = MqttSubscriptionRecoveryState()
        assertFalse(state.isReady())
        val generation = state.begin(2)

        assertEquals(
            MqttSubscriptionAttemptOutcome.PENDING,
            state.complete(generation, succeeded = true)
        )
        assertFalse(state.isReady())
        assertEquals(
            MqttSubscriptionAttemptOutcome.READY,
            state.complete(generation, succeeded = true)
        )
        assertTrue(state.isReady())
    }

    @Test
    fun `any relationship subscription failure requests a retry`() {
        val state = MqttSubscriptionRecoveryState()
        val generation = state.begin(2)

        assertEquals(
            MqttSubscriptionAttemptOutcome.PENDING,
            state.complete(generation, succeeded = false)
        )
        assertEquals(
            MqttSubscriptionAttemptOutcome.RETRY,
            state.complete(generation, succeeded = true)
        )
        assertFalse(state.isReady())
    }

    @Test
    fun `late subscription callbacks from an invalidated connection are ignored`() {
        val state = MqttSubscriptionRecoveryState()
        val generation = state.begin(1)
        state.invalidate()

        assertEquals(
            MqttSubscriptionAttemptOutcome.STALE,
            state.complete(generation, succeeded = true)
        )
        assertFalse(state.isReady())
    }

    @Test
    fun `new subscription generation and disconnect retire previous readiness`() {
        val state = MqttSubscriptionRecoveryState()
        val old = state.begin(1)
        state.complete(old, true)
        assertTrue(state.isReady())
        val fresh = state.begin(1)
        assertFalse(state.isReady())
        assertEquals(MqttSubscriptionAttemptOutcome.STALE, state.complete(old, true))
        assertFalse(state.isReady())
        state.complete(fresh, true)
        assertTrue(state.isReady())
        state.invalidate()
        assertFalse(state.isReady())
    }

    @Test
    fun `broker ack watchdog uses the oldest outstanding publish`() {
        val watchdog = MqttBrokerAckWatchdog(timeoutMillis = 12_000L)

        watchdog.onPublished(messageId = 7, nowElapsedMillis = 1_000L)
        watchdog.onPublished(messageId = 8, nowElapsedMillis = 4_000L)

        assertEquals(2, watchdog.pendingCount())
        assertEquals(7_000L, watchdog.nextCheckDelayMillis(nowElapsedMillis = 6_000L))
        assertEquals(5_000L, watchdog.oldestPendingAgeMillis(nowElapsedMillis = 6_000L))
    }

    @Test
    fun `attachment broker ack timeout does not extend ordinary messages`() {
        val watchdog = MqttBrokerAckWatchdog(timeoutMillis = 12_000L)
        watchdog.onPublished(
            messageId = 7,
            nowElapsedMillis = 1_000L,
            timeoutMillis = MqttBrokerAckTimeoutPolicy.DEFAULT_TIMEOUT_MILLIS
        )
        watchdog.onPublished(
            messageId = 8,
            nowElapsedMillis = 1_000L,
            timeoutMillis = MqttBrokerAckTimeoutPolicy.ATTACHMENT_TIMEOUT_MILLIS
        )

        assertEquals(0L, watchdog.nextCheckDelayMillis(nowElapsedMillis = 13_000L))
        assertEquals(12_000L, watchdog.oldestTimedOutPendingAgeMillis(13_000L))
        watchdog.onAcknowledged(7)
        assertEquals(18_000L, watchdog.nextCheckDelayMillis(nowElapsedMillis = 13_000L))
        assertEquals(null, watchdog.oldestTimedOutPendingAgeMillis(13_000L))
    }

    @Test
    fun `only attachment chunks receive the longer broker ack timeout`() {
        assertEquals(
            MqttBrokerAckTimeoutPolicy.DEFAULT_TIMEOUT_MILLIS,
            MqttBrokerAckTimeoutPolicy.forPayloadType("peer_message")
        )
        assertEquals(
            MqttBrokerAckTimeoutPolicy.DEFAULT_TIMEOUT_MILLIS,
            MqttBrokerAckTimeoutPolicy.forPayloadType("input_attachment_manifest")
        )
        assertEquals(
            MqttBrokerAckTimeoutPolicy.ATTACHMENT_TIMEOUT_MILLIS,
            MqttBrokerAckTimeoutPolicy.forPayloadType("input_attachment_chunk")
        )
    }

    @Test
    fun `broker ack watchdog advances after an acknowledgement`() {
        val watchdog = MqttBrokerAckWatchdog(timeoutMillis = 12_000L)
        watchdog.onPublished(messageId = 7, nowElapsedMillis = 1_000L)
        watchdog.onPublished(messageId = 8, nowElapsedMillis = 4_000L)

        watchdog.onAcknowledged(messageId = 7)

        assertEquals(1, watchdog.pendingCount())
        assertEquals(10_000L, watchdog.nextCheckDelayMillis(nowElapsedMillis = 6_000L))
        watchdog.onAcknowledged(messageId = 8)
        assertEquals(null, watchdog.nextCheckDelayMillis(nowElapsedMillis = 6_000L))
    }

    @Test
    fun `broker ack watchdog clears stale transport state`() {
        val watchdog = MqttBrokerAckWatchdog(timeoutMillis = 12_000L)
        watchdog.onPublished(messageId = 7, nowElapsedMillis = 1_000L)

        assertEquals(12_000L, watchdog.oldestPendingAgeMillis(nowElapsedMillis = 13_000L))
        watchdog.clear()

        assertEquals(0, watchdog.pendingCount())
        assertEquals(null, watchdog.oldestPendingAgeMillis(nowElapsedMillis = 13_000L))
    }

    @Test
    fun `broker delivery reconciles an acknowledgement that arrives before registration`() {
        val registration = MqttBrokerDeliveryRegistration()

        assertFalse(registration.onAcknowledged(42))
        assertTrue(registration.onPublished(42))
        assertTrue(registration.onAcknowledged(42))
        assertFalse(registration.onAcknowledged(42))
    }

    @Test
    fun `broker delivery processes a normal acknowledgement exactly once`() {
        val registration = MqttBrokerDeliveryRegistration()

        assertFalse(registration.onPublished(7))
        assertTrue(registration.onAcknowledged(7))
        assertFalse(registration.onAcknowledged(7))
    }
}
