package com.signalasi.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

class AgentRuntimeGuestProtocolTest {
    private val key = ByteArray(32) { index -> (index + 1).toByte() }

    @Test
    fun signedEnvelopeRoundTripsAndRejectsTampering() {
        val unsigned = AgentRuntimeGuestEnvelope(
            requestId = "request-1",
            type = AgentRuntimeGuestMessageType.EXECUTE,
            sequence = 2L,
            timestampMillis = 10_000L,
            payload = linkedMapOf(
                "source" to "print('ok')",
                "limits" to linkedMapOf("memory" to 128, "cpu" to 100)
            )
        )
        val signed = AgentRuntimeGuestProtocol.sign(unsigned, key)
        val decoded = AgentRuntimeGuestProtocol.decode(AgentRuntimeGuestProtocol.encode(signed))

        assertTrue(AgentRuntimeGuestProtocol.verify(decoded, key, nowMillis = 10_000L))
        assertFalse(
            AgentRuntimeGuestProtocol.verify(
                decoded.copy(payload = decoded.payload + ("source" to "changed")),
                key,
                nowMillis = 10_000L
            )
        )
    }

    @Test
    fun canonicalPayloadIsStableAcrossMapInsertionOrder() {
        val first = linkedMapOf<String, Any?>("b" to 2, "a" to mapOf("z" to true, "x" to false))
        val second = linkedMapOf<String, Any?>("a" to mapOf("x" to false, "z" to true), "b" to 2)

        assertEquals(
            AgentRuntimeGuestProtocol.canonicalJson(first),
            AgentRuntimeGuestProtocol.canonicalJson(second)
        )
    }

    @Test
    fun streamChannelUsesBoundedAuthenticatedLengthPrefixedFrames() {
        val output = ByteArrayOutputStream()
        StreamAgentRuntimeGuestChannel(ByteArrayInputStream(byteArrayOf()), output).use { channel ->
            channel.send(
                AgentRuntimeGuestEnvelope(
                    requestId = "request-2",
                    type = AgentRuntimeGuestMessageType.HEARTBEAT,
                    sequence = 1L,
                    payload = mapOf("status" to "ready")
                ),
                key
            )
        }

        val received = StreamAgentRuntimeGuestChannel(
            ByteArrayInputStream(output.toByteArray()),
            ByteArrayOutputStream()
        ).use { channel -> channel.receive(1_000L, key) }

        assertEquals(AgentRuntimeGuestMessageType.HEARTBEAT, received.type)
        assertEquals("ready", received.payload["status"])
    }

    @Test
    fun runtimeLimitsRejectUnsafeCombinations() {
        val failure = runCatching {
            AgentRuntimeResourceLimits(wallClockMillis = 1_000L, cpuMillis = 2_000L).validated()
        }.exceptionOrNull()

        assertTrue(failure is IllegalArgumentException)
    }

    @Test
    fun authenticationRejectsInvalidIdentifiersAndSequence() {
        val timestamp = System.currentTimeMillis()
        val blankRequest = AgentRuntimeGuestProtocol.sign(
            AgentRuntimeGuestEnvelope(
                requestId = "",
                type = AgentRuntimeGuestMessageType.PROGRESS,
                sequence = 1L,
                timestampMillis = timestamp
            ),
            key
        )
        val invalidSequence = AgentRuntimeGuestProtocol.sign(
            AgentRuntimeGuestEnvelope(
                requestId = "request-3",
                type = AgentRuntimeGuestMessageType.PROGRESS,
                sequence = 0L,
                timestampMillis = timestamp
            ),
            key
        )

        assertFalse(AgentRuntimeGuestProtocol.verify(blankRequest, key, timestamp))
        assertFalse(AgentRuntimeGuestProtocol.verify(invalidSequence, key, timestamp))
    }

    @Test
    fun guestBridgeRejectsReplayedExecutionFrames() {
        val requestId = "request-replay"
        val responses = ArrayDeque(
            listOf(
                AgentRuntimeGuestEnvelope(
                    requestId = requestId,
                    type = AgentRuntimeGuestMessageType.HELLO_ACK,
                    sequence = 1L,
                    payload = mapOf("guest_api_version" to 1, "guest_version" to "test")
                ),
                AgentRuntimeGuestEnvelope(
                    requestId = requestId,
                    type = AgentRuntimeGuestMessageType.PROGRESS,
                    sequence = 2L,
                    payload = mapOf("stage" to "running")
                ),
                AgentRuntimeGuestEnvelope(
                    requestId = requestId,
                    type = AgentRuntimeGuestMessageType.RESULT,
                    sequence = 2L,
                    payload = mapOf("exit_code" to 0, "stdout" to "done", "stderr" to "")
                )
            )
        )
        val bridge = AgentRuntimeGuestBridge(
            channelFactory = AgentRuntimeGuestChannelFactory {
                object : AgentRuntimeGuestChannel {
                    override fun send(envelope: AgentRuntimeGuestEnvelope, sessionKey: ByteArray) = Unit
                    override fun receive(timeoutMillis: Long, sessionKey: ByteArray) = responses.removeFirst()
                    override fun close() = Unit
                }
            },
            sessionKeyProvider = { key }
        )

        val failure = runCatching {
            bridge.execute(
                AgentRuntimeExecutionRequest(
                    language = AgentRuntimeLanguage.PYTHON,
                    source = "print('done')",
                    arguments = emptyList(),
                    timeoutMillis = 1_000L,
                    networkEnabled = false,
                    artifactPaths = emptyList(),
                    workspaceId = "workspace",
                    requestId = requestId
                )
            )
        }.exceptionOrNull()

        assertTrue(failure is IllegalStateException)
        assertTrue(failure?.message.orEmpty().contains("replayed or out-of-order"))
    }
}
