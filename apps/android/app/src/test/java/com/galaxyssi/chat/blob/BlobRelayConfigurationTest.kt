package com.galaxyssi.chat.blob

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class BlobRelayConfigurationTest {
    private val fingerprint = "f".repeat(64)
    private val token = "a".repeat(64)
    private fun payload() = JSONObject().put("version", 1).put("revision", 10L).put("desktop_id", "desktop")
        .put("client_route_id", "route").put("desktop_fingerprint", fingerprint).put("enabled", true)
        .put("origin", "https://Blob.Test:443/").put("provisioning_token", token)
    private fun parse(value: JSONObject) = BlobRelayConfiguration.parse(value, "desktop", "route", fingerprint)
    @Test fun `normalizes TLS origin and redacts diagnostic representation`() {
        val config = parse(payload())
        assertEquals("https://blob.test", config.origin)
        assertEquals(token, config.provisioningToken)
        assertFalse(config.toString().contains(token))
    }
    @Test fun `rejects wrong paired source route or fingerprint`() {
        listOf("desktop_id", "client_route_id", "desktop_fingerprint").forEach {
            assertThrows(BlobFailure::class.java) { parse(payload().put(it, "different")) }
        }
    }
    @Test fun `disabled configuration clears origin and credential`() {
        val config = parse(payload().put("enabled", false))
        assertEquals("", config.origin); assertEquals("", config.provisioningToken)
    }
    @Test fun `rejects unsafe origins and untyped flags or revisions`() {
        listOf("http://blob.test", "https://name:secret@blob.test", "https://blob.test/private", "https://blob.test?q=secret").forEach {
            assertThrows(BlobFailure::class.java) { parse(payload().put("origin", it)) }
        }
        assertThrows(BlobFailure::class.java) { parse(payload().put("enabled", "true")) }
        assertThrows(BlobFailure::class.java) { parse(payload().put("revision", "10")) }
        assertThrows(BlobFailure::class.java) { parse(payload().put("provisioning_token", "invalid")) }
    }
}
