package com.signalasi.chat

import org.json.JSONObject

object CloudModelCredentialPolicy {
    private val placeholderCredentials = setOf(
        "key",
        "api-key",
        "your-api-key",
        "your_api_key",
        "replace-me",
        "replace_me"
    )

    private val debugCredentials = setOf(
        "smoke-key",
        "backup-smoke-key",
        "sk-signalasi-smoke-key"
    )

    fun isStoredCredential(value: String): Boolean {
        val credential = value.trim()
        if (credential.isBlank() || '*' in credential) return false
        return credential.lowercase() !in placeholderCredentials
    }

    fun isDebugFixtureCredential(value: String): Boolean {
        val credential = value.trim().lowercase()
        return credential in debugCredentials ||
            credential.contains("signalasi-smoke") ||
            credential.startsWith("backup-smoke-")
    }

    fun isAutoRoutableCredential(value: String): Boolean =
        isStoredCredential(value) && !isDebugFixtureCredential(value)

    fun isAutoRoutable(contact: JSONObject): Boolean {
        val endpoint = contact.optString("cloud_endpoint").trim()
        val model = contact.optString("cloud_model").trim()
        return contact.optString("setup_status").ifBlank { "ready" }
            .equals("ready", ignoreCase = true) &&
            contact.optString("cloud_provider").isNotBlank() &&
            model.isNotBlank() &&
            !model.equals("model-id", ignoreCase = true) &&
            endpoint.startsWith("https://", ignoreCase = true) &&
            !endpoint.contains("example.com", ignoreCase = true) &&
            isAutoRoutableCredential(contact.optString("cloud_api_key"))
    }
}
