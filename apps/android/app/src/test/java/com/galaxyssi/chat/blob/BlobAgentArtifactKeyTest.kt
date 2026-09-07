package com.galaxyssi.chat.blob

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class BlobAgentArtifactKeyTest {
    private val uri = "galaxyssi-artifact://blob/" + "a".repeat(64)
    private fun metadata() = mapOf("blob_client_route_id" to "route", "blob_desktop_id" to "desktop",
        "blob_conversation_id" to "conversation", "blob_task_id" to "task", "blob_turn_id" to "turn",
        "blob_execution_generation" to "1", "transfer_id" to "b".repeat(64), "sha256" to "c".repeat(64), "size_bytes" to "1234")
    private fun key() = requireNotNull(BlobAgentArtifactKey.from("conversation", "task", "turn", uri, metadata()))
    private fun event() = JSONObject().put("type", "artifact_available").put("blob_publication", true).put("peer_chat", false)
        .put("client_route_id", "route").put("desktop_id", "desktop").put("conversation_id", "conversation")
        .put("task_id", "task").put("turn_id", "turn").put("execution_generation", 1)
        .put("transfer_id", "b".repeat(64)).put("artifact_uri", uri).put("sha256", "c".repeat(64)).put("size_bytes", 1234)

    @Test fun `valid progress and both terminal outcomes have the same strict identity`() {
        for (type in listOf("artifact_blob_progress", "artifact_available", "artifact_download_failed")) {
            assertTrue(key().matches(event().put("type", type)))
        }
    }

    @Test fun `all route conversation task turn generation and attachment mismatches are rejected`() {
        for (field in listOf("client_route_id", "desktop_id", "conversation_id", "task_id", "turn_id",
            "transfer_id", "artifact_uri", "sha256")) assertFalse(field, key().matches(event().put(field, "other")))
        assertFalse(key().matches(event().put("execution_generation", 2)))
        assertFalse(key().matches(event().put("size_bytes", 1235)))
    }

    @Test fun `peer and non blob events never update agent cards`() {
        assertFalse(key().matches(event().put("peer_chat", true)))
        assertFalse(key().matches(event().put("peer_chat", "false")))
        assertFalse(key().matches(event().put("blob_publication", false)))
        assertFalse(key().matches(event().put("type", "text")))
    }

    @Test fun `missing entry scope cannot inherit metadata from another conversation`() {
        for (scope in listOf(Triple("", "task", "turn"), Triple("conversation", "", "turn"),
            Triple("conversation", "task", ""), Triple("other", "task", "turn"),
            Triple("conversation", "other", "turn"), Triple("conversation", "task", "other"))) {
            assertNull(BlobAgentArtifactKey.from(scope.first, scope.second, scope.third, uri, metadata()))
        }
    }

    @Test fun `partial metadata and invalid digests do not create a card identity`() {
        metadata().keys.forEach { field ->
            assertNull(field, BlobAgentArtifactKey.from("conversation", "task", "turn", uri, metadata() - field))
        }
        assertNull(BlobAgentArtifactKey.from("conversation", "task", "turn", "content://unscoped", metadata()))
        assertNull(BlobAgentArtifactKey.from("conversation", "task", "turn", uri, metadata() + ("transfer_id" to "bad")))
    }

    @Test fun `non integer wire sizes and generations are not rounded into valid identities`() {
        for ((field, value) in listOf("execution_generation" to 1.5, "execution_generation" to "1",
            "execution_generation" to true, "size_bytes" to 1234.5, "size_bytes" to "1234")) {
            assertFalse(key().matches(event().put(field, value)))
        }
    }

    @Test fun `oversized or negative metadata is rejected before any storage lookup`() {
        for ((field, value) in listOf("size_bytes" to "0", "size_bytes" to "-1",
            "size_bytes" to (BlobProtocol.MAX_FILE_BYTES + 1).toString(), "blob_execution_generation" to "0",
            "blob_execution_generation" to "9007199254740992")) {
            assertNull(BlobAgentArtifactKey.from("conversation", "task", "turn", uri, metadata() + (field to value)))
        }
    }
}
