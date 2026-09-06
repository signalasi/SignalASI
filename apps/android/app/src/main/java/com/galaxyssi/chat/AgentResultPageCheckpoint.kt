package com.galaxyssi.chat

import org.json.JSONObject

internal data class AgentResultPageManifest(val digest: String, val bytes: Long, val pages: Int) {
    fun valid(): Boolean = Regex("[a-f0-9]{64}").matches(digest) && bytes in 1L..(Int.MAX_VALUE - 8L) &&
        pages.toLong() == (bytes + AgentResultRecoveryClient.PAGE_BYTES - 1) / AgentResultRecoveryClient.PAGE_BYTES

    fun pageBytes(page: Int): Int = minOf(AgentResultRecoveryClient.PAGE_BYTES.toLong(),
        bytes - page.toLong() * AgentResultRecoveryClient.PAGE_BYTES).toInt()

    fun json(): JSONObject = JSONObject().put("sha256", digest).put("total_bytes", bytes).put("page_count", pages)

    companion object {
        fun from(value: JSONObject): AgentResultPageManifest? = AgentResultPageManifest(
            value.optString("sha256"), value.optLong("total_bytes", -1), value.optInt("page_count", -1)
        ).takeIf { it.valid() }
    }
}

/** Pages are scoped to a desktop, all seven execution identities and one generation. */
internal interface AgentResultPageCheckpoint {
    fun manifest(): AgentResultPageManifest?
    fun read(manifest: AgentResultPageManifest, page: Int): ByteArray?
    fun write(manifest: AgentResultPageManifest, page: Int, bytes: ByteArray): Boolean
    fun clear(digest: String)
}
