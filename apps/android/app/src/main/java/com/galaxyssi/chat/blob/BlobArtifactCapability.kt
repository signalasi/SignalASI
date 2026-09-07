package com.galaxyssi.chat.blob

import org.json.JSONObject

/** Receiver support is independent of input-upload opt-in and bound to both paired identities. */
internal data class BlobArtifactCapabilityPair(val desktop: String, val route: String,
    val remoteFingerprint: String, val localFingerprint: String) {
    fun binding() = JSONObject().put("desktop_id", desktop).put("client_route_id", route)
        .put("desktop_fingerprint", remoteFingerprint).put("local_fingerprint", localFingerprint)
    fun key() = BlobProtocol.hash(BlobProtocol.canonical(binding()))
}

internal object BlobArtifactCapability {
    const val TYPE = "artifact_blob_capability"
    const val MAX_REVISION = 9_007_199_254_740_991L

    /** Replays preserve the revision; state or identity changes must persist before sending. */
    fun transition(raw: String?, pair: BlobArtifactCapabilityPair, enabled: Boolean, now: Long): JSONObject {
        val previous = raw?.let { value ->
            val doc = JSONObject(value)
            BlobProtocol.integer(doc, "version", 1, 1)
            BlobProtocol.integer(doc, "revision", 1, MAX_REVISION)
            if (doc.opt("enabled") !is Boolean || doc.optJSONObject("binding") == null)
                BlobProtocol.fail("artifact_blob_capability_invalid")
            doc
        }
        if (previous != null && BlobProtocol.canonical(previous.getJSONObject("binding"))
                .contentEquals(BlobProtocol.canonical(pair.binding())) && previous.getBoolean("enabled") == enabled)
            return previous
        val oldRevision = previous?.getLong("revision") ?: 0L
        if (oldRevision == MAX_REVISION) BlobProtocol.fail("artifact_blob_capability_revision_exhausted")
        return JSONObject().put("version", 1).put("binding", pair.binding())
            .put("revision", maxOf(oldRevision + 1, now.coerceIn(1, MAX_REVISION))).put("enabled", enabled)
    }

    fun payload(state: JSONObject): JSONObject {
        val pair = state.getJSONObject("binding")
        return JSONObject().put("type", TYPE).put("version", 1).put("revision", state.getLong("revision"))
            .put("enabled", state.getBoolean("enabled")).put("desktop_id", pair.getString("desktop_id"))
            .put("client_route_id", pair.getString("client_route_id"))
            .put("desktop_fingerprint", pair.getString("desktop_fingerprint"))
    }
}

/** Worker-confined publisher. Queue acceptance is durable transport acceptance, not a broker ACK. */
internal class BlobArtifactCapabilityPublisher(
    private val read: (String) -> String?, private val write: (String, String) -> Unit,
    private val current: (BlobArtifactCapabilityPair) -> Boolean,
    private val publish: (BlobArtifactCapabilityPair, JSONObject) -> Boolean,
    private val clock: () -> Long = System::currentTimeMillis
) {
    private val accepted = mutableMapOf<String, Pair<String, Long>>()
    fun reconnect() = accepted.clear()

    fun update(pair: BlobArtifactCapabilityPair, enabled: Boolean): Boolean {
        if (!current(pair)) return true
        val storageKey = BlobProtocol.hash(pair.desktop.toByteArray(Charsets.UTF_8))
        val raw = read(storageKey)
        val state = BlobArtifactCapability.transition(raw, pair, enabled, clock())
        val encoded = state.toString()
        if (encoded != raw) write(storageKey, encoded)
        if (!current(pair)) return true
        val key = pair.key()
        val revision = state.getLong("revision")
        if (accepted[pair.desktop] == (key to revision)) return true
        if (!publish(pair, BlobArtifactCapability.payload(state))) return false
        accepted[pair.desktop] = key to revision
        return true
    }
}
