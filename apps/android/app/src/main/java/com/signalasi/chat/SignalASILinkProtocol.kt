package com.signalasi.chat

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.security.SecureRandom
import java.util.Base64
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

object SignalASILinkProtocol {
    const val NAME = "signalasi-link"
    const val VERSION = 1
    const val TOPIC_ROOT = "signalasichat/v1"
    private const val PREFS = "signalasi_link_v1"
    private const val KEY_SERVERS = "servers"
    private const val MAX_QR_AGE_MS = 10 * 60 * 1000L
    private const val MAX_CLOCK_SKEW_MS = 5 * 60 * 1000L
    private const val DEFAULT_MESSAGE_TTL_MS = 7 * 24 * 60 * 60 * 1000L
    const val MAX_ENVELOPE_BYTES = 512 * 1024
    private const val MAX_TEXT_BYTES = 128 * 1024
    private val routePattern = Regex("^[A-Za-z0-9_-]{22}$")
    private val random = SecureRandom()

    data class Routes(val serverRouteId: String, val clientRouteId: String) {
        init {
            require(validRouteId(serverRouteId)) { "Invalid server route ID" }
            require(validRouteId(clientRouteId)) { "Invalid client route ID" }
        }

        val pairing: String get() = "$TOPIC_ROOT/$serverRouteId/pair"
        val up: String get() = "$TOPIC_ROOT/$serverRouteId/$clientRouteId/up"
        val down: String get() = "$TOPIC_ROOT/$serverRouteId/$clientRouteId/down"
        val control: String get() = "$TOPIC_ROOT/$serverRouteId/$clientRouteId/control"
    }

    data class ServerLink(
        val desktopId: String,
        val desktopName: String,
        val desktopFingerprint: String,
        val signalName: String,
        val routes: Routes,
        val paired: Boolean
    ) {
        fun toJson(): JSONObject = JSONObject()
            .put("desktop_id", desktopId)
            .put("desktop_name", desktopName)
            .put("desktop_fingerprint", desktopFingerprint)
            .put("signal_name", signalName)
            .put("server_route_id", routes.serverRouteId)
            .put("client_route_id", routes.clientRouteId)
            .put("paired", paired)
            .put("updated_at", System.currentTimeMillis())
    }

    fun newRouteId(): String {
        val bytes = ByteArray(16)
        random.nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    fun validRouteId(value: String): Boolean = routePattern.matches(value)

    fun validatePairingQr(qr: JSONObject, nowMs: Long = System.currentTimeMillis()): Boolean {
        if (qr.optString("type") != "signalasi_verify") return false
        if (qr.optString("protocol") != NAME || qr.optInt("version") != VERSION) return false
        if (qr.optString("role") != "server") return false
        val serverRouteId = qr.optString("server_route_id")
        if (!validRouteId(serverRouteId)) return false
        if (qr.optString("pairing_topic") != "$TOPIC_ROOT/$serverRouteId/pair") return false
        if (qr.optString("pairing_token").length < 32) return false
        if (runCatching { Base64.getUrlDecoder().decode(qr.optString("pairing_secret")) }.getOrNull()?.size != 32) return false
        if (qr.optString("desktop_id").isBlank() || qr.optString("identity_key_sha256").length != 64) return false
        val createdAt = qr.optLong("created_at")
        val createdAtMs = if (createdAt < 10_000_000_000L) TimeUnit.SECONDS.toMillis(createdAt) else createdAt
        return createdAtMs > 0 && kotlin.math.abs(nowMs - createdAtMs) <= MAX_QR_AGE_MS
    }

    @Synchronized
    fun ensureServerLink(
        context: Context,
        qr: JSONObject,
        rotateClientRoute: Boolean = false
    ): ServerLink {
        require(validatePairingQr(qr)) { "Invalid SignalASI Link v1 pairing QR" }
        val desktopId = qr.getString("desktop_id")
        val existing = serverLink(context, desktopId)
        if (!rotateClientRoute &&
            existing != null &&
            existing.routes.serverRouteId == qr.getString("server_route_id")
        ) return existing
        val link = ServerLink(
            desktopId = desktopId,
            desktopName = qr.optString("desktop_name", "SignalASI Desktop"),
            desktopFingerprint = qr.getString("identity_key_sha256"),
            signalName = desktopId,
            routes = Routes(qr.getString("server_route_id"), newRouteId()),
            paired = false
        )
        existing?.let { SignalASILinkDeliveryStore.discardRoutes(context, it.routes) }
        save(context, link)
        return link
    }

    @Synchronized
    fun markPaired(context: Context, desktopId: String): ServerLink? {
        val current = serverLink(context, desktopId) ?: return null
        val updated = current.copy(paired = true)
        save(context, updated)
        return updated
    }

    fun serverLink(context: Context, desktopId: String): ServerLink? =
        allServerLinks(context).firstOrNull { it.desktopId == desktopId }

    fun allServerLinks(context: Context): List<ServerLink> {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_SERVERS, "[]") ?: "[]"
        val array = runCatching { JSONArray(raw) }.getOrDefault(JSONArray())
        return buildList {
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                runCatching {
                    add(
                        ServerLink(
                            desktopId = item.getString("desktop_id"),
                            desktopName = item.optString("desktop_name", "SignalASI Desktop"),
                            desktopFingerprint = item.getString("desktop_fingerprint"),
                            signalName = item.optString("signal_name", item.getString("desktop_id")),
                            routes = Routes(item.getString("server_route_id"), item.getString("client_route_id")),
                            paired = item.optBoolean("paired", false)
                        )
                    )
                }
            }
        }
    }

    @Synchronized
    fun removeServer(context: Context, desktopId: String) {
        val links = allServerLinks(context)
        links.firstOrNull { it.desktopId == desktopId }?.let {
            SignalASILinkDeliveryStore.discardRoutes(context, it.routes)
        }
        val remaining = links.filterNot { it.desktopId == desktopId }
        write(context, remaining)
    }

    @Synchronized
    fun clear(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().commit()
    }

    fun makeEnvelope(payload: JSONObject, sourceId: String, targetId: String): JSONObject {
        require(payload.optString("content").toByteArray(Charsets.UTF_8).size <= MAX_TEXT_BYTES) { "Text exceeds Link limit" }
        val now = System.currentTimeMillis()
        val envelope = JSONObject()
            .put("protocol", NAME)
            .put("version", VERSION)
            .put("message_id", payload.optString("message_id").ifBlank { UUID.randomUUID().toString() })
            .put("conversation_id", payload.optString("conversation_id"))
            .put("source_id", sourceId)
            .put("target_id", targetId)
            .put("reply_to", payload.optString("reply_to"))
            .put("sent_at", now)
            .put("expires_at", now + DEFAULT_MESSAGE_TTL_MS)
            .put("payload", payload)
        require(envelope.toString().toByteArray(Charsets.UTF_8).size <= MAX_ENVELOPE_BYTES) { "Envelope exceeds Link limit" }
        return envelope
    }

    fun encryptPairingClaim(claim: JSONObject, qr: JSONObject): JSONObject {
        require(validatePairingQr(qr)) { "Invalid SignalASI Link v1 pairing QR" }
        val token = qr.getString("pairing_token")
        val serverRouteId = qr.getString("server_route_id")
        val key = Base64.getUrlDecoder().decode(qr.getString("pairing_secret"))
        val nonce = ByteArray(12).also { random.nextBytes(it) }
        val aad = "$NAME|$VERSION|$token|$serverRouteId".toByteArray(Charsets.UTF_8)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
        cipher.updateAAD(aad)
        val ciphertext = cipher.doFinal(claim.toString().toByteArray(Charsets.UTF_8))
        return JSONObject()
            .put("type", "signalasi_pairing_ciphertext")
            .put("protocol", NAME)
            .put("version", VERSION)
            .put("pairing_token", token)
            .put("server_route_id", serverRouteId)
            .put("nonce", Base64.getUrlEncoder().withoutPadding().encodeToString(nonce))
            .put("ciphertext", Base64.getUrlEncoder().withoutPadding().encodeToString(ciphertext))
    }

    fun unwrapEnvelope(envelope: JSONObject): JSONObject? {
        if (envelope.optString("protocol") != NAME || envelope.optInt("version") != VERSION) return null
        if (envelope.toString().toByteArray(Charsets.UTF_8).size > MAX_ENVELOPE_BYTES) return null
        if (runCatching { UUID.fromString(envelope.optString("message_id")) }.isFailure) return null
        if (envelope.optString("source_id").isBlank() || envelope.optString("target_id").isBlank()) return null
        val now = System.currentTimeMillis()
        val sentAt = envelope.optLong("sent_at")
        val expiresAt = envelope.optLong("expires_at")
        if (sentAt <= 0 || sentAt - now > MAX_CLOCK_SKEW_MS || expiresAt <= sentAt || now > expiresAt) return null
        return envelope.optJSONObject("payload")?.apply {
            put("message_id", envelope.optString("message_id"))
            if (optString("reply_to").isBlank()) put("reply_to", envelope.optString("reply_to"))
            if (optString("conversation_id").isBlank()) put("conversation_id", envelope.optString("conversation_id"))
        }
    }

    private fun save(context: Context, link: ServerLink) {
        val values = allServerLinks(context).filterNot { it.desktopId == link.desktopId } + link
        write(context, values)
    }

    private fun write(context: Context, links: List<ServerLink>) {
        val array = JSONArray()
        links.forEach { array.put(it.toJson()) }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_SERVERS, array.toString()).commit()
    }
}
