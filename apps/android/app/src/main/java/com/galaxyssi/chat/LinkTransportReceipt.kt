package com.galaxyssi.chat

import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

/** The binding fences receipts from revoked/replaced relationships, including key changes. */
internal data class LinkTransportReceipt(val peer: String, val phone: Boolean, val binding: String, val message: String) {
    init {
        require(peer.isNotBlank() && peer.length <= 512)
        require(binding.matches(Regex("[a-f0-9]{64}")))
        require(message.isNotBlank() && message.length <= 512)
    }
    val key: String get() = digest(JSONArray(listOf(peer, phone, binding, message)).toString())
    fun json(): JSONObject = JSONObject().put("peer", peer).put("phone", phone)
        .put("binding", binding).put("message", message)

    companion object {
        fun binding(peer: String, phone: Boolean, route: String, local: String, remote: String, secret: String): String =
            digest(JSONArray(listOf(peer, phone, route, local, remote, secret)).toString())
        fun from(json: JSONObject) = LinkTransportReceipt(json.getString("peer"), json.getBoolean("phone"),
            json.getString("binding"), json.getString("message"))
        private fun digest(value: String): String = MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }
}

internal data class LinkTransportReceiptAttempt(val key: String, val token: String)
internal data class LinkTransportReceiptWork(val receipt: LinkTransportReceipt, val wire: String, val attempt: LinkTransportReceiptAttempt)
