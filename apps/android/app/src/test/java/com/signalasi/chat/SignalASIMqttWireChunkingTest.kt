package com.signalasi.chat

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

class SignalASIMqttWireChunkingTest {
    private fun wirePayload(size: Int = 180_000): String =
        JSONObject()
            .put("scheme", "signal")
            .put("from", "signalasi:phone")
            .put("to", "desktop_test")
            .put("body", "x".repeat(size))
            .toString()

    @Test
    fun smallEncryptedWirePayloadRemainsDirect() {
        val wire = wirePayload(100)
        assertEquals(listOf(wire), SignalASIMqttWireChunking.encode(wire))
    }

    @Test
    fun largePayloadRoundTripsOutOfOrderWithDuplicates() {
        val wire = wirePayload()
        val packets = SignalASIMqttWireChunking.encode(wire)
        assertTrue(packets.size > 2)
        assertTrue(
            packets.all {
                it.toByteArray(Charsets.UTF_8).size <= SignalASIMqttWireChunking.MAX_PACKET_BYTES
            }
        )
        val assembler = SignalASIMqttChunkAssembler()
        val decoded = packets.map(::JSONObject)
        assertNull(assembler.accept("route", decoded.last()))
        assertNull(assembler.accept("route", decoded.last()))
        var result: String? = null
        decoded.dropLast(1).forEach { result = assembler.accept("route", it) }
        assertEquals(wire, result)
    }

    @Test(expected = IllegalArgumentException::class)
    fun modifiedChunkIsRejectedBeforeReassembly() {
        val packet = JSONObject(SignalASIMqttWireChunking.encode(wirePayload()).first())
        val chunk = Base64.getDecoder().decode(packet.getString("data"))
        chunk[0] = (chunk[0].toInt() xor 1).toByte()
        packet.put("data", Base64.getEncoder().encodeToString(chunk))
        SignalASIMqttChunkAssembler().accept("route", packet)
    }

    @Test(expected = IllegalArgumentException::class)
    fun modifiedTransferIsRejectedByWholePayloadHash() {
        val packets = SignalASIMqttWireChunking.encode(wirePayload()).map(::JSONObject)
        val last = packets.last()
        val chunk = Base64.getDecoder().decode(last.getString("data"))
        chunk[chunk.lastIndex] = (chunk.last().toInt() xor 1).toByte()
        last.put("data", Base64.getEncoder().encodeToString(chunk))
        last.put("chunk_sha256", SignalASIMqttWireChunking.sha256(chunk))
        val assembler = SignalASIMqttChunkAssembler()
        packets.forEach { assembler.accept("route", it) }
    }
}
