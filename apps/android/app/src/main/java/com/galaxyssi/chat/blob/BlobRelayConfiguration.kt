package com.galaxyssi.chat.blob

import android.content.Context
import com.galaxyssi.chat.AgentEncryptedPreferences
import com.galaxyssi.chat.GalaxySSILinkProtocol
import org.json.JSONObject

internal data class BlobRelayConfiguration(
    val desktopId: String, val routeId: String, val fingerprint: String,
    val revision: Long, val origin: String, val provisioningToken: String
) {
    override fun toString(): String = "BlobRelayConfiguration(revision=$revision, enabled=${origin.isNotEmpty()})"
    companion object {
        const val TYPE = "blob_relay_config"
        fun parse(payload: JSONObject, desktopId: String, routeId: String, fingerprint: String): BlobRelayConfiguration {
            BlobProtocol.integer(payload, "version", 1, 1)
            if (payload.optString("desktop_id") != desktopId || payload.optString("client_route_id") != routeId ||
                payload.optString("desktop_fingerprint") != fingerprint) BlobProtocol.fail("blob_config_identity_mismatch")
            val revision = BlobProtocol.integer(payload, "revision", 1, Long.MAX_VALUE)
            val enabled = payload.opt("enabled") as? Boolean ?: BlobProtocol.fail("invalid_blob_configuration")
            val origin = if (enabled) BlobHttp.normalizeOrigin(BlobProtocol.string(payload, "origin")) else ""
            val token = if (enabled) BlobProtocol.string(payload, "provisioning_token").also {
                BlobProtocol.unhex(it, 32).fill(0)
            } else ""
            return BlobRelayConfiguration(desktopId, routeId, fingerprint, revision, origin, token)
        }
    }
}

/** Small per-pair configuration only; transfer jobs use an indexed encrypted journal. */
internal object BlobRelayConfigurations {
    private fun prefs(context: Context) = AgentEncryptedPreferences(context, "blob-relay-config-v1")
    private fun key(id: String) = BlobProtocol.hash(id.toByteArray(Charsets.UTF_8))

    @Synchronized
    fun ingest(context: Context, payload: JSONObject, authenticatedDesktopId: String) {
        check(payload.toString().length <= 8192) { "invalid_blob_configuration" }
        val link = GalaxySSILinkProtocol.serverLink(context, authenticatedDesktopId)
            ?.takeIf { it.paired } ?: BlobProtocol.fail("blob_config_identity_mismatch")
        val value = BlobRelayConfiguration.parse(payload, authenticatedDesktopId, link.routes.clientRouteId,
            link.routes.remoteFingerprint)
        val storage = prefs(context)
        val old = storage.readString(key(authenticatedDesktopId), "").takeIf(String::isNotEmpty)?.let {
            runCatching { BlobRelayConfiguration.parse(JSONObject(it), authenticatedDesktopId,
                link.routes.clientRouteId, link.routes.remoteFingerprint) }.getOrNull()
        }
        if (old != null && value.revision < old.revision) return
        if (old != null && value.revision == old.revision) {
            if (value != old) BlobProtocol.fail("blob_config_revision_conflict")
            return
        }
        val clean = JSONObject().put("version", 1).put("desktop_id", value.desktopId)
            .put("client_route_id", value.routeId).put("desktop_fingerprint", value.fingerprint)
            .put("revision", value.revision).put("enabled", value.origin.isNotEmpty())
            .put("origin", value.origin).put("provisioning_token", value.provisioningToken)
        storage.writeString(key(authenticatedDesktopId), clean.toString())
    }

    fun get(context: Context, desktopId: String): BlobRelayConfiguration? = runCatching {
        val link = GalaxySSILinkProtocol.serverLink(context, desktopId)?.takeIf { it.paired } ?: return null
        val raw = prefs(context).readString(key(desktopId), "").takeIf(String::isNotEmpty) ?: return null
        BlobRelayConfiguration.parse(JSONObject(raw), desktopId, link.routes.clientRouteId,
            link.routes.remoteFingerprint).takeIf { it.origin.isNotEmpty() }
    }.getOrNull()
}
