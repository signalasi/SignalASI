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
}
