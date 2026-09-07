package com.galaxyssi.chat.blob

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class BlobArtifactCapabilityTest {
    private val pair = BlobArtifactCapabilityPair("desktop", "route", "a".repeat(64), "b".repeat(64))
    private val stored = mutableMapOf<String, String>()
    private val sent = mutableListOf<JSONObject>()
    private var current = true
    private var accepts = true
    private var canWrite = true
    private var now = 100L
    private fun publisher() = BlobArtifactCapabilityPublisher(stored::get, { key, value ->
        check(canWrite); stored[key] = value
    }, { current }, { _, payload ->
        assertTrue("Must persist before publication", stored.isNotEmpty())
        sent.add(payload); accepts
    }, { now })

    @Test fun `receiver transitions remain monotonic when the wall clock goes backwards`() {
        val sender = publisher()
        sender.update(pair, true); now = 1; sender.update(pair, false); sender.update(pair, true)
        assertEquals(listOf(100L, 101L, 102L), sent.map { it.getLong("revision") })
        assertEquals(listOf(true, false, true), sent.map { it.getBoolean("enabled") })
    }
    @Test fun `repeat state suppresses duplicate traffic until subscriptions reconnect`() {
        val sender = publisher()
        repeat(100) { sender.update(pair, true) }
        assertEquals(1, sent.size)
        sender.reconnect(); sender.update(pair, true)
        assertEquals(2, sent.size)
        assertEquals(sent[0].toString(), sent[1].toString())
    }
    @Test fun `process restart replays the exact committed declaration`() {
        publisher().update(pair, true)
        now = 9999; publisher().update(pair, true)
        assertEquals(sent[0].toString(), sent[1].toString())
    }
    @Test fun `failed enqueue retries instead of remembering unaccepted declaration`() {
        val sender = publisher(); accepts = false
        assertFalse(sender.update(pair, true))
        accepts = true; assertTrue(sender.update(pair, true)); sender.update(pair, true)
        assertEquals(2, sent.size)
        assertEquals(sent[0].toString(), sent[1].toString())
    }
    @Test fun `disk failure does not declare a receiver ready`() {
        canWrite = false
        assertThrows(IllegalStateException::class.java) { publisher().update(pair, true) }
        assertTrue(sent.isEmpty())
    }
    @Test fun `pair revoked before state write causes no side effects`() {
        current = false; publisher().update(pair, true)
        assertTrue(stored.isEmpty()); assertTrue(sent.isEmpty())
    }
    @Test fun `pair revoked during durable write is never published`() {
        val sender = BlobArtifactCapabilityPublisher(stored::get, { key, value ->
            stored[key] = value; current = false
        }, { current }, { _, value -> sent.add(value); true }, { now })
        sender.update(pair, true)
        assertEquals(1, stored.size); assertTrue(sent.isEmpty())
    }
    @Test fun `repaired identity cannot reuse accepted declaration`() {
        val sender = publisher()
        sender.update(pair, true)
        listOf(pair.copy(route = "new-route"), pair.copy(remoteFingerprint = "c".repeat(64)),
            pair.copy(localFingerprint = "d".repeat(64))).forEach { sender.update(it, true) }
        assertEquals(listOf(100L, 101L, 102L, 103L), sent.map { it.getLong("revision") })
    }
    @Test fun `different paired desktops have independent state`() {
        val sender = publisher()
        sender.update(pair, true); sender.update(pair.copy(desktop = "second"), false)
        assertEquals(2, stored.size); assertEquals(2, sent.size)
        assertEquals(listOf(true, false), sent.map { it.getBoolean("enabled") })
    }
    @Test fun `wire payload matches Desktop schema and excludes local storage fields`() {
        publisher().update(pair, true)
        assertEquals(setOf("type", "version", "revision", "enabled", "desktop_id", "client_route_id", "desktop_fingerprint"),
            sent.single().keys().asSequence().toSet())
        assertEquals("artifact_blob_capability", sent.single().getString("type"))
        assertFalse(sent.single().toString().contains(pair.localFingerprint))
    }
    @Test fun `corrupt committed revision is not silently reset`() {
        val state = BlobArtifactCapability.transition(null, pair, true, now)
        listOf("100", 1.5, 0, -1).forEach { revision ->
            assertThrows(BlobFailure::class.java) {
                BlobArtifactCapability.transition(state.put("revision", revision).toString(), pair, true, now)
            }
        }
    }
    @Test fun `revision exhaustion cannot wrap around and overwrite newer remote state`() {
        val state = BlobArtifactCapability.transition(null, pair, true, BlobArtifactCapability.MAX_REVISION)
        assertThrows(BlobFailure::class.java) {
            BlobArtifactCapability.transition(state.toString(), pair, false, 1)
        }
        assertEquals(BlobArtifactCapability.MAX_REVISION,
            BlobArtifactCapability.transition(state.toString(), pair, true, 1).getLong("revision"))
    }
    @Test fun `bad state shape does not advertise ready`() {
        val state = BlobArtifactCapability.transition(null, pair, true, now)
        assertThrows(BlobFailure::class.java) {
            BlobArtifactCapability.transition(state.put("enabled", "true").toString(), pair, true, now)
        }
    }
}
