package com.galaxyssi.chat.blob

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class BlobOutgoingContractTest {
    private fun manifest() = JSONObject().put("client_route_id", "route").put("conversation_id", "中文会话")
        .put("task_id", "task").put("turn_id", "turn").put("attachment_id", "attachment")
        .put("transfer_id", "a".repeat(64)).put("contact_id", "contact").put("sha256", "b".repeat(64))
        .put("size_bytes", 123L).put("client_message_id", 7L)
    private fun receipt() = JSONObject(manifest().toString()).put("source_message_id", "7").put("status", "stored")
    @Test fun `binding contains only the seven actual scope identities`() {
        val value = BlobOutgoingContract.binding(manifest())
        assertEquals(7, value.size)
        assertEquals("中文会话", value["conversation_id"])
        assertFalse(value.containsKey("sha256"))
        assertFalse(value.containsKey("client_message_id"))
    }
    @Test fun `stored receipt must match every scope and file field`() {
        assertTrue(BlobOutgoingContract.receiptMatches(manifest(), receipt()))
        for (key in BlobOutgoingContract.binding(manifest()).keys + listOf("sha256", "source_message_id")) {
            assertFalse(key, BlobOutgoingContract.receiptMatches(manifest(), receipt().put(key, "wrong")))
        }
        assertFalse(BlobOutgoingContract.receiptMatches(manifest(), receipt().put("size_bytes", 122)))
        assertFalse(BlobOutgoingContract.receiptMatches(manifest(), receipt().put("status", "uploaded")))
    }
    @Test fun `recovery request receipt cannot satisfy a different request`() {
        val manifest = manifest().put("attachment_request_id", "r1")
        assertFalse(BlobOutgoingContract.receiptMatches(manifest, receipt().put("attachment_request_id", "r2")))
        assertTrue(BlobOutgoingContract.receiptMatches(manifest, receipt().put("attachment_request_id", "r1")))
    }
    @Test fun `offer contains no file bytes or legacy chunk instructions`() {
        val source = manifest().put("time", 1).put("eager_chunks", true).put("resume", true)
        val output = BlobOutgoingContract.offerPayload(source, JSONObject().put("version", 1))
        assertEquals(BlobOutgoingContract.TYPE, output.getString("type"))
        assertFalse(output.has("time")); assertFalse(output.has("eager_chunks")); assertFalse(output.has("resume"))
        assertFalse(output.has("data_b64"))
        assertTrue(source.has("resume"))
    }
    @Test fun `retry delay is bounded but never a task attempt cutoff`() {
        assertEquals(2_000L, BlobOutgoingContract.retryDelay(0))
        assertEquals(300_000L, BlobOutgoingContract.retryDelay(Int.MAX_VALUE))
    }
    @Test fun `receipt file length must be a real integer`() {
        for (invalid in listOf<Any>("123", 123.0, true, JSONObject.NULL)) {
            assertFalse(BlobOutgoingContract.receiptMatches(manifest(), receipt().put("size_bytes", invalid)))
        }
    }
    @Test fun `resume preserves immutable request identity but ignores transport hints`() {
        val original = manifest().put("attachment_request_id", "request-1")
        assertTrue(BlobOutgoingContract.sameManifest(original,
            JSONObject(original.toString()).put("time", 123).put("resume", true).put("eager_chunks", true)))
        for (field in listOf("attachment_request_id", "turn_id", "source_message_id", "sha256")) {
            assertFalse(field, BlobOutgoingContract.sameManifest(original, JSONObject(original.toString()).put(field, "other")))
        }
    }
}
