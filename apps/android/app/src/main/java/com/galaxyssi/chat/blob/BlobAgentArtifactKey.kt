package com.galaxyssi.chat.blob

import org.json.JSONObject

/** Immutable presentation identity; no Relay credentials or file paths enter UI updates. */
internal data class BlobAgentArtifactKey(
    val route: String, val desktop: String, val conversation: String, val task: String, val turn: String,
    val generation: Long, val transfer: String, val uri: String, val sha256: String, val size: Long
) {
    fun matches(event: JSONObject): Boolean = BlobArtifactPresentation.isEvent(event) &&
        event.opt("peer_chat") == false && event.optString("client_route_id") == route &&
        event.optString("desktop_id") == desktop && event.optString("conversation_id") == conversation &&
        event.optString("task_id") == task && event.optString("turn_id") == turn &&
        integerMatches(event.opt("execution_generation"), generation) && event.optString("transfer_id") == transfer &&
        event.optString("artifact_uri") == uri && event.optString("sha256") == sha256 &&
        integerMatches(event.opt("size_bytes"), size)

    private fun integerMatches(value: Any?, expected: Long): Boolean = when (value) {
        is Int -> value.toLong() == expected
        is Long -> value == expected
        else -> false
    }

    companion object {
        private val digest = Regex("[0-9a-f]{64}")
        fun from(conversation: String, task: String, turn: String, uri: String,
            metadata: Map<String, String>): BlobAgentArtifactKey? {
            if (listOf(conversation, task, turn).any(String::isBlank)) return null
            val route = metadata["blob_client_route_id"].orEmpty()
            val desktop = metadata["blob_desktop_id"].orEmpty()
            val transfer = metadata["transfer_id"].orEmpty()
            val hash = metadata["sha256"].orEmpty()
            val generation = metadata["blob_execution_generation"]?.toLongOrNull() ?: return null
            val size = metadata["size_bytes"]?.toLongOrNull() ?: return null
            if (route.isBlank() || desktop.isBlank() || generation !in 1..9_007_199_254_740_991L || size !in 1..BlobProtocol.MAX_FILE_BYTES ||
                !digest.matches(transfer) || !digest.matches(hash) ||
                !uri.matches(Regex("galaxyssi-artifact://blob/[0-9a-f]{64}")) ||
                metadata["blob_conversation_id"] != conversation || metadata["blob_task_id"] != task ||
                metadata["blob_turn_id"] != turn) return null
            return BlobAgentArtifactKey(route, desktop, conversation, task, turn, generation, transfer, uri, hash, size)
        }
    }
}
