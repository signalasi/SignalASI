package com.galaxyssi.chat.blob

import org.json.JSONObject
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class BlobArtifactPresentationTest {
    private fun message(value: JSONObject = manifest()) = JSONObject()
        .put("id", 42L).put("contactId", "desktop").put("remoteMessageId", value.getString("source_message_id"))
        .put("isMine", false).put("content", "original text").put("isRead", true).put("timestamp", 1234L)
        .put("attachments", JSONArray().put(attachment(value)))

    @Test fun `terminal message projection preserves text ordering read state and original identity`() {
        val value = manifest()
        val original = message(value)
        val result = requireNotNull(BlobArtifactPresentation.updateMessage(
            BlobArtifactIngressPolicy.event(value, "artifact_available"), original))
        for (field in listOf("contactId", "remoteMessageId", "isMine", "content", "isRead")) {
            assertEquals(original.get(field), result.get(field))
        }
        for (field in listOf("id", "timestamp")) {
            assertEquals(original.getLong(field), result.getLong(field))
        }
        assertEquals("complete", result.getJSONArray("attachments").getJSONObject(0).getString("transfer_state"))
        assertFalse(original.getJSONArray("attachments").getJSONObject(0).has("transfer_state"))
    }

    @Test fun `progress and agent events never enter durable peer projection`() {
        val value = manifest()
        val progress = BlobArtifactPresentation.progress(value, 0, value.getLong("size_bytes"))
        assertFalse(BlobArtifactPresentation.isTerminalPeerEvent(progress))
        assertNull(BlobArtifactPresentation.updateMessage(progress, message(value)))
        val event = BlobArtifactIngressPolicy.event(value, "artifact_available").put("peer_chat", false)
        assertNull(BlobArtifactPresentation.updateMessage(event, message(value)))
    }

    @Test fun `only the matching attachment is projected regardless of its ordinal`() {
        val value = manifest()
        val unrelated = JSONObject().put("name", "other file").put("transfer_id", "f".repeat(64))
        val original = message(value).put("attachments", JSONArray().put(unrelated).put(attachment(value)))
        val result = requireNotNull(BlobArtifactPresentation.updateMessage(
            BlobArtifactIngressPolicy.event(value, "artifact_available"), original))
        assertEquals(unrelated.toString(), result.getJSONArray("attachments").getJSONObject(0).toString())
        assertEquals("complete", result.getJSONArray("attachments").getJSONObject(1).getString("transfer_state"))
    }

    @Test fun `missing outbound and cross contact messages are never invented or rewritten`() {
        val event = BlobArtifactIngressPolicy.event(manifest(), "artifact_available")
        for (item in listOf(JSONObject(), message().put("isMine", true), message().put("contactId", "other"),
            message().put("remoteMessageId", "other"))) assertNull(BlobArtifactPresentation.updateMessage(event, item))
    }

    @Test fun `terminal projection rejects incomplete metadata rather than poisoning future completion`() {
        val event = BlobArtifactIngressPolicy.event(manifest(), "artifact_available")
        for ((key, invalid) in listOf("sha256" to "", "artifact_uri" to "file:///tmp/secret", "transfer_id" to "bad",
            "size_bytes" to 0L, "size_bytes" to (BlobProtocol.MAX_FILE_BYTES + 1), "source_message_id" to "")) {
            assertFalse(BlobArtifactPresentation.isTerminalPeerEvent(JSONObject(event.toString()).put(key, invalid)))
        }
    }

    @Test fun `terminal projection is idempotent and late failure cannot replace completion`() {
        val complete = BlobArtifactIngressPolicy.event(manifest(), "artifact_available")
        val done = requireNotNull(BlobArtifactPresentation.updateMessage(complete, message()))
        assertNull(BlobArtifactPresentation.updateMessage(complete, done))
        assertNull(BlobArtifactPresentation.updateMessage(
            BlobArtifactIngressPolicy.event(manifest(), "artifact_download_failed", "late"), done))
    }
    private fun manifest(): JSONObject {
        val relative = "core/protocol/fixtures/blob-artifact-v1.json"
        val root = generateSequence(File(requireNotNull(System.getProperty("user.dir")))) { it.parentFile }
            .first { File(it, relative).isFile }
        val value = JSONObject(File(root, relative).readText()).getJSONObject("manifest")
        value.remove("transfer_id")
        value.put("peer_chat", true).put("contact_id", "desktop")
            .put("artifact_uri", "galaxyssi-artifact://blob/" + "b".repeat(64))
        return BlobArtifactContract.makeManifest(value)
    }

    private fun attachment(value: JSONObject = manifest()) = JSONObject()
        .put("name", value.getString("name")).put("mime_type", "audio/ogg").put("duration_ms", 3456)
        .put("artifact_uri", value.getString("artifact_uri")).put("transfer_id", value.getString("transfer_id"))
        .put("sha256", value.getString("sha256")).put("size_bytes", value.getLong("size_bytes"))

    private fun apply(event: JSONObject, value: JSONObject = attachment(), contact: String = "desktop",
        source: String = manifest().getString("source_message_id"), mine: Boolean = false) =
        BlobArtifactPresentation.updateAttachment(event, contact, source, mine, value)

    @Test fun `progress is local metadata without credentials or synthetic message identity`() {
        val value = manifest()
        val event = BlobArtifactPresentation.progress(value, 0, value.getLong("size_bytes"))
        assertEquals(BlobArtifactPresentation.PROGRESS, event.getString("type"))
        assertTrue(event.getBoolean("peer_chat"))
        assertEquals(value.getString("source_message_id"), event.getString("source_message_id"))
        for (field in listOf("message_id", "private", "read_token", "key", "relative_path", "origin")) assertFalse(event.has(field))
    }

    @Test fun `last downloaded byte remains below complete until persistence event`() {
        val value = manifest()
        val size = value.getLong("size_bytes")
        val progress = BlobArtifactPresentation.progress(value, size, size)
        assertEquals(99, progress.getInt("progress"))
        val pending = requireNotNull(apply(progress))
        assertEquals("downloading", pending.getString("transfer_state"))
        val complete = requireNotNull(apply(BlobArtifactIngressPolicy.event(value, "artifact_available"), pending))
        assertEquals(100, complete.getInt("transfer_progress"))
        assertEquals("complete", complete.getString("transfer_state"))
    }

    @Test fun `bad byte counts cannot become a successful progress event`() {
        val value = manifest()
        val size = value.getLong("size_bytes")
        for ((done, total) in listOf(-1L to size, (size + 1) to size, 0L to 0L, 0L to (size + 1))) {
            assertThrows(IllegalArgumentException::class.java) { BlobArtifactPresentation.progress(value, done, total) }
        }
    }

    @Test fun `card match requires contact original message transfer uri digest and size`() {
        val event = BlobArtifactIngressPolicy.event(manifest(), "artifact_available")
        assertNull(apply(event, contact = "other"))
        assertNull(apply(event, source = "other"))
        assertNull(apply(event, mine = true))
        for ((key, replacement) in listOf("transfer_id" to "c".repeat(64), "artifact_uri" to "galaxyssi-artifact://blob/" + "d".repeat(64),
            "sha256" to "e".repeat(64), "size_bytes" to 1L)) {
            assertNull(apply(event, attachment().put(key, replacement)))
        }
    }

    @Test fun `attachment identity is preserved rather than replaced by an ordinal fallback`() {
        val event = BlobArtifactIngressPolicy.event(manifest(), "artifact_available")
        val item = attachment()
        val result = requireNotNull(apply(event, item))
        for (key in listOf("name", "mime_type", "duration_ms", "artifact_uri", "transfer_id", "sha256")) {
            assertEquals(item.get(key), result.get(key))
        }
        assertFalse(item.has("transfer_state"))
        assertNotEquals(manifest().getString("transfer_id"), "b".repeat(64))
    }

    @Test fun `late progress cannot revert a completed file`() {
        val value = manifest()
        val complete = requireNotNull(apply(BlobArtifactIngressPolicy.event(value, "artifact_available")))
        assertNull(apply(BlobArtifactPresentation.progress(value, 0, value.getLong("size_bytes")), complete))
        assertNull(apply(BlobArtifactIngressPolicy.event(value, "artifact_download_failed", "blob_expired"), complete))
    }

    @Test fun `same event replay produces no redundant view update`() {
        val event = BlobArtifactIngressPolicy.event(manifest(), "artifact_available")
        val complete = requireNotNull(apply(event))
        assertNull(apply(event, complete))
    }

    @Test fun `failure stays on the matching file and can be retried`() {
        val value = manifest()
        val failed = requireNotNull(apply(BlobArtifactIngressPolicy.event(value, "artifact_download_failed", "blob_expired")))
        assertEquals("failed", failed.getString("transfer_state"))
        val resumed = requireNotNull(apply(BlobArtifactPresentation.progress(value, 0, value.getLong("size_bytes")), failed))
        assertEquals("downloading", resumed.getString("transfer_state"))
    }

    @Test fun `agent result progress never mutates contact attachments`() {
        val event = BlobArtifactIngressPolicy.event(manifest(), "artifact_available").put("peer_chat", false)
        assertNull(apply(event))
    }

    @Test fun `peer completion event retains original scope and content metadata`() {
        val value = manifest()
        val event = BlobArtifactIngressPolicy.event(value, "artifact_available")
        for (field in listOf("source_message_id", "peer_chat", "name", "mime_type", "size_bytes", "sha256",
            "client_route_id", "conversation_id", "task_id", "turn_id", "execution_generation")) {
            if (value.get(field) is Number) assertEquals(value.getLong(field), event.getLong(field))
            else assertEquals(value.get(field), event.get(field))
        }
    }
}
