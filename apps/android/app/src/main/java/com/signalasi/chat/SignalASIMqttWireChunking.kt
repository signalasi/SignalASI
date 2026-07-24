package com.signalasi.chat

import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.Base64
import java.util.LinkedHashMap

internal object SignalASIMqttWireChunking {
    const val SCHEME = "signal-chunk"
    const val DEFAULT_DIRECT_LIMIT_BYTES = 48 * 1024
    const val DEFAULT_CHUNK_DATA_BYTES = 32 * 1024
    const val MAX_REASSEMBLED_BYTES = 2 * 1024 * 1024
    const val MAX_CHUNK_COUNT = 64
    const val MAX_PACKET_BYTES = 60 * 1024

    fun isChunk(wire: JSONObject): Boolean = wire.optString("scheme") == SCHEME

    fun encode(
        wirePayload: String,
        directLimitBytes: Int = DEFAULT_DIRECT_LIMIT_BYTES,
        chunkDataBytes: Int = DEFAULT_CHUNK_DATA_BYTES
    ): List<String> {
        require(directLimitBytes > 0)
        require(chunkDataBytes > 0)
        val bytes = wirePayload.toByteArray(Charsets.UTF_8)
        if (bytes.size <= directLimitBytes) return listOf(wirePayload)
        require(bytes.size <= MAX_REASSEMBLED_BYTES) { "MQTT wire payload exceeds reassembly limit" }

        val count = (bytes.size + chunkDataBytes - 1) / chunkDataBytes
        require(count in 2..MAX_CHUNK_COUNT) { "MQTT wire payload requires too many chunks" }
        val digest = sha256(bytes)
        val endpointEnvelope = runCatching { JSONObject(wirePayload) }.getOrNull()
        return (0 until count).map { index ->
            val start = index * chunkDataBytes
            val end = minOf(start + chunkDataBytes, bytes.size)
            val chunk = bytes.copyOfRange(start, end)
            JSONObject()
                .put("protocol", SignalASILinkProtocol.NAME)
                .put("version", SignalASILinkProtocol.VERSION)
                .put("scheme", SCHEME)
                .put("transfer_id", digest)
                .put("chunk_index", index)
                .put("chunk_count", count)
                .put("total_bytes", bytes.size)
                .put("sha256", digest)
                .put("chunk_sha256", sha256(chunk))
                .put("from", endpointEnvelope?.optString("from").orEmpty())
                .put("to", endpointEnvelope?.optString("to").orEmpty())
                .put("data", Base64.getEncoder().encodeToString(chunk))
                .toString()
                .also {
                    require(it.toByteArray(Charsets.UTF_8).size <= MAX_PACKET_BYTES) {
                        "MQTT chunk packet exceeds packet limit"
                    }
                }
        }
    }

    fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { "%02x".format(it) }
}

internal class SignalASIMqttChunkAssembler(
    private val nowMillis: () -> Long = System::currentTimeMillis,
    private val ttlMillis: Long = 2 * 60_000L,
    private val maximumActiveTransfers: Int = 16
) {
    private data class PartialTransfer(
        val chunkCount: Int,
        val totalBytes: Int,
        val sha256: String,
        val from: String,
        val to: String,
        val chunks: MutableMap<Int, ByteArray> = linkedMapOf(),
        var updatedAtMillis: Long
    )

    private val transfers = LinkedHashMap<String, PartialTransfer>()

    @Synchronized
    fun accept(scope: String, wire: JSONObject): String? {
        require(SignalASIMqttWireChunking.isChunk(wire)) { "Not a SignalASI MQTT chunk" }
        pruneExpired()

        val transferId = wire.optString("transfer_id").lowercase()
        val fullHash = wire.optString("sha256").lowercase()
        val chunkHash = wire.optString("chunk_sha256").lowercase()
        val chunkIndex = wire.optInt("chunk_index", -1)
        val chunkCount = wire.optInt("chunk_count", -1)
        val totalBytes = wire.optInt("total_bytes", -1)
        val from = wire.optString("from")
        val to = wire.optString("to")
        require(transferId.length == 64 && transferId == fullHash) { "Invalid MQTT transfer identity" }
        require(chunkHash.length == 64) { "Invalid MQTT chunk hash" }
        require(chunkCount in 2..SignalASIMqttWireChunking.MAX_CHUNK_COUNT) { "Invalid MQTT chunk count" }
        require(chunkIndex in 0 until chunkCount) { "Invalid MQTT chunk index" }
        require(totalBytes in 1..SignalASIMqttWireChunking.MAX_REASSEMBLED_BYTES) {
            "Invalid MQTT transfer size"
        }
        val chunk = runCatching { Base64.getDecoder().decode(wire.getString("data")) }
            .getOrElse { throw IllegalArgumentException("Invalid MQTT chunk encoding", it) }
        require(chunk.isNotEmpty() && chunk.size <= SignalASIMqttWireChunking.DEFAULT_CHUNK_DATA_BYTES) {
            "Invalid MQTT chunk size"
        }
        require(SignalASIMqttWireChunking.sha256(chunk) == chunkHash) { "MQTT chunk integrity check failed" }

        val key = "$scope:$transferId"
        val partial = transfers[key] ?: run {
            if (transfers.size >= maximumActiveTransfers) {
                val oldest = transfers.entries.minByOrNull { it.value.updatedAtMillis }?.key
                if (oldest != null) transfers.remove(oldest)
            }
            PartialTransfer(
                chunkCount = chunkCount,
                totalBytes = totalBytes,
                sha256 = fullHash,
                from = from,
                to = to,
                updatedAtMillis = nowMillis()
            ).also { transfers[key] = it }
        }
        require(
            partial.chunkCount == chunkCount &&
                partial.totalBytes == totalBytes &&
                partial.sha256 == fullHash &&
                partial.from == from &&
                partial.to == to
        ) { "MQTT chunk metadata mismatch" }
        partial.chunks[chunkIndex]?.let { previous ->
            require(previous.contentEquals(chunk)) { "Conflicting MQTT chunk duplicate" }
        } ?: run {
            partial.chunks[chunkIndex] = chunk
        }
        partial.updatedAtMillis = nowMillis()
        if (partial.chunks.size < partial.chunkCount) return null

        val output = ByteArrayOutputStream(partial.totalBytes)
        repeat(partial.chunkCount) { index ->
            output.write(partial.chunks[index] ?: throw IllegalArgumentException("Missing MQTT chunk"))
        }
        val assembled = output.toByteArray()
        transfers.remove(key)
        require(assembled.size == partial.totalBytes) { "MQTT transfer length check failed" }
        require(SignalASIMqttWireChunking.sha256(assembled) == partial.sha256) {
            "MQTT transfer integrity check failed"
        }
        return assembled.toString(Charsets.UTF_8)
    }

    @Synchronized
    fun clear() {
        transfers.clear()
    }

    private fun pruneExpired() {
        val cutoff = nowMillis() - ttlMillis
        transfers.entries.removeAll { it.value.updatedAtMillis < cutoff }
    }
}
