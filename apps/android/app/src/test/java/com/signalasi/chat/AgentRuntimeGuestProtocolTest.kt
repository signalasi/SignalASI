package com.signalasi.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.SocketTimeoutException
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

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
    fun canonicalPayloadPreservesUnicodeAndRejectsFloatingPointNumbers() {
        assertEquals(
            "{\"emoji\":\"\uD83D\uDE00\",\"slash\":\"a/b\",\"text\":\"\u4e2d\u6587\\n\\\"x\\\"\"}",
            AgentRuntimeGuestProtocol.canonicalJson(
                mapOf("text" to "\u4e2d\u6587\n\"x\"", "slash" to "a/b", "emoji" to "\uD83D\uDE00")
            )
        )
        assertTrue(runCatching { AgentRuntimeGuestProtocol.canonicalJson(mapOf("value" to 1.5)) }.isFailure)
    }

    @Test
    fun hostSigningMatchesTheLinuxGuestContractVector() {
        val payload = mapOf(
            "arguments" to listOf("alpha"),
            "language" to "python",
            "limits" to mapOf("wall_clock_ms" to 1_000),
            "workspace_path" to "/workspace/a/b"
        )
        assertEquals(
            "{\"arguments\":[\"alpha\"],\"language\":\"python\",\"limits\":{\"wall_clock_ms\":1000},\"workspace_path\":\"/workspace/a/b\"}",
            AgentRuntimeGuestProtocol.canonicalJson(payload)
        )
        val signed = AgentRuntimeGuestProtocol.sign(
            AgentRuntimeGuestEnvelope(
                messageId = "message-1",
                requestId = "request-1",
                type = AgentRuntimeGuestMessageType.EXECUTE,
                sequence = 2L,
                timestampMillis = 1_700_000_000_000L,
                payload = payload
            ),
            ByteArray(32) { it.toByte() }
        )

        assertEquals("bQOnaC5JTWzoMgk+2beIwM/kTTghus6R73k6P1hopg8=", signed.mac)
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
        val responses = LinkedBlockingQueue<AgentRuntimeGuestEnvelope>()
        val channel = object : AgentRuntimeGuestChannel {
            override fun send(envelope: AgentRuntimeGuestEnvelope, sessionKey: ByteArray) {
                when (envelope.type) {
                    AgentRuntimeGuestMessageType.HELLO -> responses.put(
                        AgentRuntimeGuestEnvelope(
                            requestId = envelope.requestId,
                            type = AgentRuntimeGuestMessageType.HELLO_ACK,
                            sequence = 1L,
                            payload = readyGuestPayload()
                        )
                    )
                    AgentRuntimeGuestMessageType.EXECUTE -> {
                        responses.put(
                            AgentRuntimeGuestEnvelope(
                                requestId = envelope.requestId,
                                type = AgentRuntimeGuestMessageType.PROGRESS,
                                sequence = 1L,
                                payload = mapOf("stage" to "running")
                            )
                        )
                        responses.put(
                            AgentRuntimeGuestEnvelope(
                                requestId = envelope.requestId,
                                type = AgentRuntimeGuestMessageType.RESULT,
                                sequence = 1L,
                                payload = mapOf("exit_code" to 0, "stdout" to "done", "stderr" to "")
                            )
                        )
                    }
                    else -> Unit
                }
            }

            override fun receive(timeoutMillis: Long, sessionKey: ByteArray): AgentRuntimeGuestEnvelope =
                responses.poll(timeoutMillis, TimeUnit.MILLISECONDS)
                    ?: throw SocketTimeoutException("No fake Guest response")

            override fun close() = Unit
        }
        val bridge = AgentRuntimeGuestBridge(
            channelFactory = AgentRuntimeGuestChannelFactory { channel },
            sessionKeyProvider = { key.copyOf() }
        )

        val failure = try {
            runCatching {
                bridge.execute(
                    executionRequest(requestId)
                )
            }.exceptionOrNull()
        } finally {
            bridge.close()
        }

        assertTrue(failure is IllegalStateException)
        assertTrue(failure?.message.orEmpty().contains("replayed or out-of-order"))
    }

    @Test
    fun guestBridgeMultiplexesConcurrentExecutionsOverOneConnection() {
        val responses = LinkedBlockingQueue<AgentRuntimeGuestEnvelope>()
        val opens = AtomicInteger(0)
        val executionIds = mutableListOf<String>()
        val channel = object : AgentRuntimeGuestChannel {
            override fun send(envelope: AgentRuntimeGuestEnvelope, sessionKey: ByteArray) {
                synchronized(executionIds) {
                    when (envelope.type) {
                        AgentRuntimeGuestMessageType.HELLO -> responses.put(
                            AgentRuntimeGuestEnvelope(
                                requestId = envelope.requestId,
                                type = AgentRuntimeGuestMessageType.HELLO_ACK,
                                sequence = 1L,
                                payload = readyGuestPayload()
                            )
                        )
                        AgentRuntimeGuestMessageType.EXECUTE -> {
                            executionIds += envelope.requestId
                            if (executionIds.size == 2) {
                                executionIds.asReversed().forEach { completedId ->
                                    responses.put(
                                        AgentRuntimeGuestEnvelope(
                                            requestId = completedId,
                                            type = AgentRuntimeGuestMessageType.RESULT,
                                            sequence = 1L,
                                            payload = mapOf(
                                                "exit_code" to 0,
                                                "stdout" to completedId,
                                                "stderr" to ""
                                            )
                                        )
                                    )
                                }
                            }
                        }
                        else -> Unit
                    }
                }
            }

            override fun receive(timeoutMillis: Long, sessionKey: ByteArray): AgentRuntimeGuestEnvelope =
                responses.poll(timeoutMillis, TimeUnit.MILLISECONDS)
                    ?: throw SocketTimeoutException("No fake Guest response")

            override fun close() = Unit
        }
        val bridge = AgentRuntimeGuestBridge(
            channelFactory = AgentRuntimeGuestChannelFactory {
                opens.incrementAndGet()
                channel
            },
            sessionKeyProvider = { key.copyOf() }
        )
        val executor = Executors.newFixedThreadPool(2)

        try {
            val first = executor.submit<AgentRuntimeExecutionResponse> {
                bridge.execute(executionRequest("request-first"))
            }
            val second = executor.submit<AgentRuntimeExecutionResponse> {
                bridge.execute(executionRequest("request-second"))
            }

            assertEquals("request-first", first.get(2, TimeUnit.SECONDS).stdout)
            assertEquals("request-second", second.get(2, TimeUnit.SECONDS).stdout)
            assertEquals(1, opens.get())
        } finally {
            executor.shutdownNow()
            bridge.close()
        }
    }

    @Test
    fun guestBridgeRejectsAnUnhealthyHeartbeat() {
        val responses = LinkedBlockingQueue<AgentRuntimeGuestEnvelope>()
        val channel = object : AgentRuntimeGuestChannel {
            override fun send(envelope: AgentRuntimeGuestEnvelope, sessionKey: ByteArray) {
                val response = when (envelope.type) {
                    AgentRuntimeGuestMessageType.HELLO -> AgentRuntimeGuestEnvelope(
                        requestId = envelope.requestId,
                        type = AgentRuntimeGuestMessageType.HELLO_ACK,
                        sequence = 1L,
                        payload = readyGuestPayload()
                    )
                    AgentRuntimeGuestMessageType.HEARTBEAT -> AgentRuntimeGuestEnvelope(
                        requestId = envelope.requestId,
                        type = AgentRuntimeGuestMessageType.HEARTBEAT_ACK,
                        sequence = 1L,
                        payload = mapOf("ready" to false, "reason" to "Sandbox launcher unavailable")
                    )
                    else -> null
                }
                response?.let(responses::put)
            }

            override fun receive(timeoutMillis: Long, sessionKey: ByteArray): AgentRuntimeGuestEnvelope =
                responses.poll(timeoutMillis, TimeUnit.MILLISECONDS)
                    ?: throw SocketTimeoutException("No fake Guest response")

            override fun close() = Unit
        }
        val bridge = AgentRuntimeGuestBridge(
            channelFactory = AgentRuntimeGuestChannelFactory { channel },
            sessionKeyProvider = { key.copyOf() }
        )

        val health = try {
            bridge.health()
        } finally {
            bridge.close()
        }

        assertFalse(health.ready)
        assertTrue(health.reason.contains("Sandbox launcher unavailable"))
    }

    private fun executionRequest(requestId: String) = AgentRuntimeExecutionRequest(
        language = AgentRuntimeLanguage.PYTHON,
        source = "print('done')",
        arguments = emptyList(),
        timeoutMillis = 1_000L,
        networkEnabled = false,
        artifactPaths = emptyList(),
        workspaceId = "workspace",
        requestId = requestId,
        guestWorkspacePath = "/workspace/test/$requestId"
    )

    private fun readyGuestPayload(): Map<String, Any?> = mapOf(
        "guest_api_version" to 1,
        "guest_version" to "test",
        "ready" to true,
        "capabilities" to listOf(
            "runtime.execute",
            "runtime.cancel",
            "runtime.progress",
            "runtime.concurrent"
        )
    )
}
