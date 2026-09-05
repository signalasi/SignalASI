package com.galaxyssi.chat

import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.Base64
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject

/** Pulls one canonical reply in bounded pages; it never executes or resumes a tool. */
internal class AgentResultRecoveryClient {
    private data class Pending(val desktop: String, val identity: List<String>, val generation: Long, val page: Int,
        val result: CompletableDeferred<JSONObject> = CompletableDeferred())
    private val pending = ConcurrentHashMap<String, Pending>()

    suspend fun fetch(desktop: String, fields: JSONObject, timeoutMillis: Long = 8_000L,
        stillPending: () -> Boolean = { true }, publish: (JSONObject) -> Boolean): JSONObject? {
        val expected = identity(fields)
        val version = AgentRemoteOutcomeCodec.version(fields) ?: return null
        require(desktop.isNotBlank() && expected.all { it.isNotBlank() && it.length <= 200 })
        if (GalaxySSITransportPrivacyPolicy.isLocalOnly(fields)) return null
        val bytes = WipeableBuffer()
        try {
            var digest = ""
            var total = -1L
            var count = 1
            var page = 0
            while (page < count) {
                if (!stillPending()) return null
                val response = query(desktop, fields, page, digest, timeoutMillis, publish) ?: return null
                if (response.optString("status") != "ready") return null
                val observedDigest = response.optString("sha256")
                val observedTotal = response.optLong("total_bytes", -1L)
                val observedCount = response.optInt("page_count", -1)
                if (!HASH.matches(observedDigest) || observedTotal !in 1L..(Int.MAX_VALUE - 8L) ||
                    observedCount.toLong() != (observedTotal + PAGE_BYTES - 1) / PAGE_BYTES) return null
                if (page == 0) {
                    digest = observedDigest; total = observedTotal; count = observedCount
                } else if (digest != observedDigest || total != observedTotal || count != observedCount) return null
                val encoded = response.optString("data_b64")
                if (encoded.length > ((PAGE_BYTES + 2) / 3) * 4) return null
                val chunk = runCatching { Base64.getDecoder().decode(encoded) }.getOrNull() ?: return null
                try {
                    val expectedSize = minOf(PAGE_BYTES.toLong(), total - page.toLong() * PAGE_BYTES).toInt()
                    if (chunk.size != expectedSize || sha256(chunk) != response.optString("page_sha256")) return null
                    bytes.write(chunk)
                } finally { chunk.fill(0) }
                page++
            }
            if (!stillPending()) return null
            val complete = bytes.toByteArray()
            try {
                if (complete.size.toLong() != total || sha256(complete) != digest) return null
                val result = runCatching { JSONObject(String(complete, Charsets.UTF_8)) }.getOrNull() ?: return null
                if (identity(result) != expected || result.optString("type") != "text" ||
                    result.optString("task_status") !in AgentRemoteOutcomeCodec.TERMINAL ||
                    AgentRemoteOutcomeCodec.version(result)?.generation != version.generation ||
                    (fields.optString("expected_status").isNotBlank() &&
                        fields.optString("expected_status") != result.optString("task_status")) ||
                    (result.optString("content").isBlank() && result.optJSONObject("rich_output") == null &&
                        result.optString("task_status") !in AgentRemoteOutcomeCodec.FAILURES)) return null
                return result.put("result_recovery", JSONObject().put("sha256", digest))
            } finally { complete.fill(0) }
        } finally { bytes.wipe() }
    }

    private suspend fun query(desktop: String, fields: JSONObject, page: Int, digest: String,
        timeoutMillis: Long, publish: (JSONObject) -> Boolean): JSONObject? {
        val nonce = UUID.randomUUID().toString()
        val generation = requireNotNull(AgentRemoteOutcomeCodec.version(fields)).generation
        val request = Pending(desktop, identity(fields), generation, page)
        pending[nonce] = request
        try {
            val payload = JSONObject().apply { FIELDS.forEach { put(it, fields.optString(it)) } }
                .put("type", "agent_task_result_page_request")
                .put("request_id", nonce).put("page_index", page).put("sha256", digest)
                .put("desktop_id", desktop)
                .put("execution_generation", generation)
            if (!publish(payload)) return null
            return withTimeoutOrNull(timeoutMillis) { request.result.await() }
        } finally { pending.remove(nonce, request); request.result.cancel() }
    }

    fun receive(payload: JSONObject, authenticatedDesktop: String): Boolean {
        val request = pending[payload.optString("request_id")] ?: return false
        if (payload.optString("type") != "agent_task_result_page" || authenticatedDesktop != request.desktop ||
            AgentRemoteOutcomeCodec.version(payload)?.generation != request.generation ||
            identity(payload) != request.identity || payload.optInt("page_index", -1) != request.page) return false
        return request.result.complete(payload)
    }

    internal val pendingCount: Int get() = pending.size

    private class WipeableBuffer : ByteArrayOutputStream() {
        override fun write(bytes: ByteArray, offset: Int, length: Int) {
            val required = count.toLong() + length
            require(required <= Int.MAX_VALUE - 8L)
            if (required > buf.size) {
                val previous = buf
                buf = previous.copyOf(maxOf(required, minOf((Int.MAX_VALUE - 8L), previous.size.toLong() * 2)).toInt())
                previous.fill(0)
            }
            super.write(bytes, offset, length)
        }
        fun wipe() { buf.fill(0); reset() }
    }

    companion object {
        const val PAGE_BYTES = 16 * 1024
        val FIELDS = listOf("client_route_id", "conversation_id", "task_id", "turn_id", "contact_id",
            "source_message_id", "agent_id")
        private val HASH = Regex("[a-f0-9]{64}")
        fun identity(value: JSONObject): List<String> = FIELDS.map { value.optString(it) }
        fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it.toInt() and 255) }
    }
}
