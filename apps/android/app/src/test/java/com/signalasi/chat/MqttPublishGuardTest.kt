package com.signalasi.chat

import org.eclipse.paho.client.mqttv3.MqttException
import org.junit.Assert.assertEquals
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
}
