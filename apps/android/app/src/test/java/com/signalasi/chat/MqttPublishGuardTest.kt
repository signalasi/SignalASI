package com.signalasi.chat

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
        val generation = state.begin(2)

        assertEquals(
            MqttSubscriptionAttemptOutcome.PENDING,
            state.complete(generation, succeeded = true)
        )
        assertEquals(
            MqttSubscriptionAttemptOutcome.READY,
            state.complete(generation, succeeded = true)
        )
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
    }
}
