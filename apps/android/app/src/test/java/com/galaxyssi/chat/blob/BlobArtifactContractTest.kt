package com.galaxyssi.chat.blob

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class BlobArtifactContractTest {
    private fun fixture(): JSONObject {
        val relative = "core/protocol/fixtures/blob-artifact-v1.json"
        val root = generateSequence(File(requireNotNull(System.getProperty("user.dir")))) { it.parentFile }
            .first { File(it, relative).isFile }
        return JSONObject(File(root, relative).readText())
    }
    private fun manifest() = fixture().getJSONObject("manifest")
    private fun offer(): JSONObject {
        val manifest = manifest()
        val private = JSONObject().put("version", 1).put("blob_id", "f".repeat(32)).put("key", "1".repeat(64))
            .put("nonce_prefix", "2".repeat(16)).put("size", manifest.getLong("size_bytes"))
            .put("sha256", manifest.getString("sha256")).put("manifest_sha256", "3".repeat(64))
            .put("binding_sha256", BlobProtocol.bindingHash(BlobArtifactContract.binding(manifest)))
        return JSONObject().put("type", BlobArtifactContract.OFFER_TYPE).put("version", 1).put("manifest", manifest).put("transport_revision", 1)
            .put("blob_offer", JSONObject().put("version", 1).put("relay", "https://blob.test")
                .put("read_token", "e".repeat(64)).put("private", private))
    }

    @Test fun `shared Python manifest and receipt preserve Chinese and generation identity`() {
        val fixture = fixture()
        val manifest = BlobArtifactContract.validateManifest(fixture.getJSONObject("manifest"))
        assertEquals(fixture.getString("binding_sha256"), BlobProtocol.bindingHash(BlobArtifactContract.binding(manifest)))
        assertArrayEquals(BlobProtocol.canonical(fixture.getJSONObject("receipt")),
            BlobProtocol.canonical(BlobArtifactContract.storedReceipt(manifest)))
    }

    @Test fun `every mutable metadata field invalidates the original transfer id`() {
        val original = manifest()
        original.keys().asSequence().filter { it != "transfer_id" }.forEach { key ->
            val changed = JSONObject(original.toString())
            when (val value = original.get(key)) {
                is Boolean -> changed.put(key, !value)
                is Number -> changed.put(key, value.toLong() + 1)
                else -> changed.put(key, "different")
            }
            assertThrows(key, BlobFailure::class.java) { BlobArtifactContract.validateManifest(changed) }
        }
    }

    @Test fun `offer route desktop origin hash and size must agree`() {
        assertEquals(BlobArtifactContract.OFFER_TYPE,
            BlobArtifactContract.validateOffer(offer(), "a".repeat(22), "desktop", "https://blob.test").getString("type"))
        for ((route, desktop, origin) in listOf(Triple("b".repeat(22), "desktop", "https://blob.test"),
            Triple("a".repeat(22), "other", "https://blob.test"), Triple("a".repeat(22), "desktop", "https://other.test"))) {
            assertThrows(BlobFailure::class.java) { BlobArtifactContract.validateOffer(offer(), route, desktop, origin) }
        }
        for (field in listOf("size", "sha256", "binding_sha256")) {
            val changed = offer()
            changed.getJSONObject("blob_offer").getJSONObject("private").put(field, if (field == "size") 1 else "9".repeat(64))
            assertThrows(BlobFailure::class.java) {
                BlobArtifactContract.validateOffer(changed, "a".repeat(22), "desktop", "https://blob.test")
            }
        }
    }

    @Test fun `large output keeps bounded control without legacy chunk bytes`() {
        val metadata = manifest().also { it.remove("transfer_id") }
            .put("size_bytes", 152L * 1024 * 1024).put("original_size_bytes", 152L * 1024 * 1024)
        val result = BlobArtifactContract.makeManifest(metadata)
        assertEquals(152L * 1024 * 1024, result.getLong("size_bytes"))
        assertTrue(BlobProtocol.canonical(result).size < 4096)
        assertFalse(result.has("data_b64"))
    }

    @Test fun `strict integer versions and file sizes reject coercion`() {
        for (bad in listOf<Any>(true, 1.0, "1", JSONObject.NULL)) {
            assertThrows(BlobFailure::class.java) {
                BlobArtifactContract.validateOffer(offer().put("version", bad), "a".repeat(22), "desktop", "https://blob.test")
            }
            assertThrows(BlobFailure::class.java) {
                BlobArtifactContract.makeManifest(manifest().also { it.remove("transfer_id") }.put("size_bytes", bad))
            }
        }
    }

    @Test fun `control metadata is bounded and extra model instructions are not retained`() {
        val raw = offer().put("untrusted_model_instructions", "Do not retain")
        val clean = BlobArtifactContract.validateOffer(raw, "a".repeat(22), "desktop", "https://blob.test")
        assertFalse(clean.has("untrusted_model_instructions"))
        assertThrows(BlobFailure::class.java) {
            BlobArtifactContract.validateOffer(raw.put("padding", "x".repeat(32768)), "a".repeat(22), "desktop", "https://blob.test")
        }
    }

    @Test fun `canonical encoder matches Python booleans and null`() {
        assertEquals("{\"a\":true,\"b\":false,\"c\":null}",
            BlobProtocol.canonical(JSONObject().put("c", JSONObject.NULL).put("b", false).put("a", true)).toString(Charsets.US_ASCII))
    }

    @Test fun `transport revision is explicit integral and independent of artifact identity`() {
        for (revision in listOf(1L, 2L, 9_007_199_254_740_991L)) {
            val clean = BlobArtifactContract.validateOffer(offer().put("transport_revision", revision),
                "a".repeat(22), "desktop", "https://blob.test")
            assertEquals(revision, clean.getLong("transport_revision"))
            assertEquals(manifest().getString("transfer_id"), clean.getJSONObject("manifest").getString("transfer_id"))
        }
        for (invalid in listOf<Any>(JSONObject.NULL, true, 1.0, "1", 0, -1, 9_007_199_254_740_992L)) {
            assertThrows(BlobFailure::class.java) {
                BlobArtifactContract.validateOffer(offer().put("transport_revision", invalid),
                    "a".repeat(22), "desktop", "https://blob.test")
            }
        }
    }
}
