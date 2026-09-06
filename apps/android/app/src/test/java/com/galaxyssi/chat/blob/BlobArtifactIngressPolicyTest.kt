package com.galaxyssi.chat.blob

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class BlobArtifactIngressPolicyTest {
    private fun manifest(peer: Boolean = false): JSONObject {
        val relative = "core/protocol/fixtures/blob-artifact-v1.json"
        val root = generateSequence(File(requireNotNull(System.getProperty("user.dir")))) { it.parentFile }
            .first { File(it, relative).isFile }
        val metadata = JSONObject(File(root, relative).readText()).getJSONObject("manifest")
            .also { it.remove("transfer_id") }.put("peer_chat", peer)
        if (peer) metadata.put("contact_id", "desktop")
        return BlobArtifactContract.makeManifest(metadata)
    }
    private fun offer(manifest: JSONObject = manifest()): JSONObject {
        val private = JSONObject().put("version", 1).put("blob_id", "f".repeat(32)).put("key", "1".repeat(64))
            .put("nonce_prefix", "2".repeat(16)).put("size", manifest.getLong("size_bytes"))
            .put("sha256", manifest.getString("sha256")).put("manifest_sha256", "3".repeat(64))
            .put("binding_sha256", BlobProtocol.bindingHash(BlobArtifactContract.binding(manifest)))
        return JSONObject().put("type", BlobArtifactContract.OFFER_TYPE).put("version", 1)
            .put("manifest", manifest).put("transport_revision", 1)
            .put("blob_offer", JSONObject().put("version", 1).put("relay", "https://blob.test")
                .put("read_token", "e".repeat(64)).put("private", private))
    }
    private fun prepare(payload: JSONObject = offer(), conversation: String = manifest().getString("conversation_id")) =
        BlobArtifactIngressPolicy.prepare(payload, "desktop", conversation, "a".repeat(22),
            "4".repeat(64), "5".repeat(64), "https://blob.test")

    @Test fun `transport envelope metadata is removed from durable descriptor`() {
        val payload = offer().put("message_id", "transport-id").put("reply_to", "request")
        val result = prepare(payload)
        assertFalse(result.getJSONObject("offer").has("message_id"))
        assertEquals("a".repeat(22), result.getString("route_id"))
        assertEquals("4".repeat(64), result.getString("peer_fingerprint"))
        assertEquals("5".repeat(64), result.getString("local_fingerprint"))
    }

    @Test fun `envelope conversation cannot redirect a nested valid manifest`() {
        assertThrows(BlobFailure::class.java) { prepare(conversation = "different-conversation") }
    }

    @Test fun `direct peer artifact must belong to sending desktop contact`() {
        prepare(offer(manifest(true)))
        val metadata = manifest(true).also { it.remove("transfer_id") }.put("contact_id", "another-contact")
        assertThrows(BlobFailure::class.java) { prepare(offer(BlobArtifactContract.makeManifest(metadata))) }
    }

    @Test fun `pair replacement route rotation or local key replacement fences pending work`() {
        val body = prepare()
        BlobArtifactIngressPolicy.checkPair(body, "a".repeat(22), "4".repeat(64), "5".repeat(64))
        for (values in listOf(listOf("b".repeat(22), "4".repeat(64), "5".repeat(64)),
            listOf("a".repeat(22), "6".repeat(64), "5".repeat(64)),
            listOf("a".repeat(22), "4".repeat(64), "6".repeat(64)))) {
            assertThrows(BlobFailure::class.java) { BlobArtifactIngressPolicy.checkPair(body, values[0], values[1], values[2]) }
        }
    }

    @Test fun `local publication is stable and excludes transport secrets`() {
        val manifest = manifest()
        val event = BlobArtifactIngressPolicy.event(manifest, "artifact_available")
        assertEquals(event.toString(), BlobArtifactIngressPolicy.event(manifest, "artifact_available").toString())
        assertEquals(manifest.getString("artifact_uri"), event.getString("artifact_uri"))
        assertEquals(manifest.getString("turn_id"), event.getString("turn_id"))
        for (key in listOf("read_token", "blob_offer", "private", "origin")) assertFalse(event.has(key))
        assertNotEquals(event.getString("message_id"),
            BlobArtifactIngressPolicy.event(manifest, "artifact_download_failed", "blob_expired").getString("message_id"))
    }

    @Test fun `failure diagnostics cannot inject raw paths or tokens into UI events`() {
        val event = BlobArtifactIngressPolicy.event(manifest(), "artifact_download_failed", "secret token /private/file")
        assertEquals("artifact_blob_receive_failed", event.getString("error_code"))
        assertFalse(event.toString().contains("secret token"))
    }

    @Test fun `publication verifies manifest rather than trusting caller fields`() {
        val wrong = manifest().put("conversation_id", "different")
        assertThrows(BlobFailure::class.java) { BlobArtifactIngressPolicy.event(wrong, "artifact_available") }
    }
}
