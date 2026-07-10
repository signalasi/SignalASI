package com.signalasi.chat

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

data class HomeAssistantSettings(
    val enabled: Boolean = false,
    val baseUrl: String = "",
    val accessToken: String = "",
    val defaultEntityId: String = ""
) {
    val configured: Boolean
        get() = enabled && baseUrl.isNotBlank() && accessToken.isNotBlank()
}

object HomeAssistantSettingsStore {
    private const val PREFS = "signalasi_home_assistant"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_BASE_URL = "base_url"
    private const val KEY_ACCESS_TOKEN = "access_token"
    private const val KEY_DEFAULT_ENTITY_ID = "default_entity_id"

    fun load(context: Context): HomeAssistantSettings {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return HomeAssistantSettings(
            enabled = prefs.getBoolean(KEY_ENABLED, false),
            baseUrl = prefs.getString(KEY_BASE_URL, "") ?: "",
            accessToken = prefs.getString(KEY_ACCESS_TOKEN, "") ?: "",
            defaultEntityId = prefs.getString(KEY_DEFAULT_ENTITY_ID, "") ?: ""
        )
    }

    fun save(context: Context, settings: HomeAssistantSettings) {
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_ENABLED, settings.enabled)
            .putString(KEY_BASE_URL, settings.baseUrl.trim().trimEnd('/'))
            .putString(KEY_ACCESS_TOKEN, settings.accessToken.trim())
            .putString(KEY_DEFAULT_ENTITY_ID, settings.defaultEntityId.trim())
            .apply()
    }

    fun setEnabled(context: Context, enabled: Boolean) {
        save(context, load(context).copy(enabled = enabled))
    }

    fun setBaseUrl(context: Context, value: String) {
        save(context, load(context).copy(baseUrl = value))
    }

    fun setAccessToken(context: Context, value: String) {
        save(context, load(context).copy(accessToken = value))
    }

    fun setDefaultEntityId(context: Context, value: String) {
        save(context, load(context).copy(defaultEntityId = value))
    }

    fun clear(context: Context) {
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .clear()
            .apply()
    }
}

data class HomeAssistantCommandResult(
    val handled: Boolean,
    val success: Boolean,
    val message: String
)

data class HomeAssistantEntityState(
    val entityId: String,
    val friendlyName: String,
    val state: String,
    val domain: String,
    val protected: Boolean = false
)

data class HomeAssistantEntityResult(
    val handled: Boolean,
    val success: Boolean,
    val message: String,
    val entities: List<HomeAssistantEntityState> = emptyList()
)

object HomeAssistantDeviceClient {
    private val entityRegex = Regex("""\b[a-z_]+\.[A-Za-z0-9_]+\b""")

    fun control(context: Context, prompt: String): HomeAssistantCommandResult {
        val settings = HomeAssistantSettingsStore.load(context)
        if (!settings.configured) {
            return HomeAssistantCommandResult(false, false, "Home Assistant local API is not configured")
        }
        val command = parseCommand(prompt, settings)
            ?: return HomeAssistantCommandResult(true, false, "Home Assistant entity is missing")
        return runServiceCall(settings, command)
    }

    fun connectionStatus(context: Context): HomeAssistantCommandResult {
        val settings = HomeAssistantSettingsStore.load(context)
        if (!settings.configured) {
            return HomeAssistantCommandResult(false, false, "Home Assistant local API is not configured")
        }
        return runBlockingRequest {
            getJson("${settings.baseUrl}/api/", settings.accessToken)
            HomeAssistantCommandResult(true, true, "Home Assistant is connected")
        }
    }

    fun listEntities(context: Context, query: String = "", limit: Int = 40): HomeAssistantEntityResult {
        val settings = HomeAssistantSettingsStore.load(context)
        if (!settings.configured) {
            return HomeAssistantEntityResult(false, false, "Home Assistant local API is not configured")
        }
        val cleanQuery = query.trim().lowercase(Locale.US)
        return runBlockingEntityRequest {
            val payload = JSONArray(getJson("${settings.baseUrl}/api/states", settings.accessToken))
            val entities = buildList {
                for (index in 0 until payload.length()) {
                    val item = payload.optJSONObject(index) ?: continue
                    val entityId = item.optString("entity_id").trim()
                    if (entityId.isBlank()) continue
                    val domain = entityId.substringBefore('.', "unknown")
                    val attributes = item.optJSONObject("attributes") ?: JSONObject()
                    val friendlyName = attributes.optString("friendly_name").ifBlank { entityId }
                    val state = item.optString("state", "unknown")
                    val protected = domain in PROTECTED_DOMAINS
                    val searchText = "$entityId $friendlyName $domain ${if (protected) "" else state}".lowercase(Locale.US)
                    if (cleanQuery.isNotBlank() && !searchText.contains(cleanQuery)) continue
                    add(
                        HomeAssistantEntityState(
                            entityId = entityId,
                            friendlyName = friendlyName.take(100),
                            state = if (protected) "protected" else state.take(100),
                            domain = domain,
                            protected = protected
                        )
                    )
                }
            }.sortedWith(compareBy<HomeAssistantEntityState> { it.domain }.thenBy { it.friendlyName })
                .take(limit.coerceIn(1, 100))
            HomeAssistantEntityResult(
                handled = true,
                success = true,
                message = "Loaded ${entities.size} Home Assistant entities",
                entities = entities
            )
        }
    }

    fun readEntity(context: Context, entityId: String): HomeAssistantEntityResult {
        val cleanEntityId = entityId.trim()
        val settings = HomeAssistantSettingsStore.load(context)
        if (!settings.configured) {
            return HomeAssistantEntityResult(false, false, "Home Assistant local API is not configured")
        }
        if (!entityRegex.matches(cleanEntityId)) {
            return HomeAssistantEntityResult(true, false, "Invalid Home Assistant entity id")
        }
        return runBlockingEntityRequest {
            val item = JSONObject(
                getJson("${settings.baseUrl}/api/states/$cleanEntityId", settings.accessToken)
            )
            val domain = cleanEntityId.substringBefore('.', "unknown")
            val protected = domain in PROTECTED_DOMAINS
            val attributes = item.optJSONObject("attributes") ?: JSONObject()
            val entity = HomeAssistantEntityState(
                entityId = cleanEntityId,
                friendlyName = attributes.optString("friendly_name").ifBlank { cleanEntityId }.take(100),
                state = if (protected) "protected" else item.optString("state", "unknown").take(100),
                domain = domain,
                protected = protected
            )
            HomeAssistantEntityResult(
                handled = true,
                success = true,
                message = if (protected) "Protected Home Assistant entity state hidden" else "Read Home Assistant entity",
                entities = listOf(entity)
            )
        }
    }

    private fun parseCommand(prompt: String, settings: HomeAssistantSettings): HomeAssistantCommand? {
        val lower = prompt.lowercase(Locale.US)
        val entityId = entityRegex.find(prompt)?.value ?: settings.defaultEntityId
        if (entityId.isBlank()) return null
        val service = when {
            lower.contains("turn off") || lower.contains("power off") || lower.endsWith(" off") -> "turn_off"
            lower.contains("turn on") || lower.contains("power on") || lower.endsWith(" on") -> "turn_on"
            lower.contains("toggle") || lower.contains("switch") -> "toggle"
            lower.contains("unlock") -> "unlock"
            lower.contains("lock") -> "lock"
            lower.contains("open") -> "open_cover"
            lower.contains("close") -> "close_cover"
            lower.contains("activate") || lower.contains("scene") -> "turn_on"
            else -> "toggle"
        }
        val entityDomain = entityId.substringBefore('.', "homeassistant")
        val serviceDomain = when (service) {
            "turn_on", "turn_off", "toggle" -> "homeassistant"
            "open_cover", "close_cover" -> "cover"
            "lock", "unlock" -> "lock"
            else -> entityDomain
        }
        return HomeAssistantCommand(serviceDomain, service, entityId)
    }

    private fun runServiceCall(
        settings: HomeAssistantSettings,
        command: HomeAssistantCommand
    ): HomeAssistantCommandResult {
        val latch = CountDownLatch(1)
        val result = AtomicReference<HomeAssistantCommandResult>()
        Thread {
            result.set(
                runCatching {
                    postJson(
                        url = "${settings.baseUrl}/api/services/${command.domain}/${command.service}",
                        token = settings.accessToken,
                        body = JSONObject().put("entity_id", command.entityId)
                    )
                    HomeAssistantCommandResult(
                        handled = true,
                        success = true,
                        message = "Home Assistant ${command.service} sent to ${command.entityId}"
                    )
                }.getOrElse {
                    HomeAssistantCommandResult(
                        handled = true,
                        success = false,
                        message = it.message?.take(180) ?: "Home Assistant request failed"
                    )
                }
            )
            latch.countDown()
        }.start()
        val completed = latch.await(12, TimeUnit.SECONDS)
        return if (completed) {
            result.get() ?: HomeAssistantCommandResult(true, false, "Home Assistant request failed")
        } else {
            HomeAssistantCommandResult(true, false, "Home Assistant request timed out")
        }
    }

    private fun runBlockingRequest(block: () -> HomeAssistantCommandResult): HomeAssistantCommandResult {
        val latch = CountDownLatch(1)
        val result = AtomicReference<HomeAssistantCommandResult>()
        Thread {
            result.set(
                runCatching(block).getOrElse { error ->
                    HomeAssistantCommandResult(
                        handled = true,
                        success = false,
                        message = error.message?.take(180) ?: "Home Assistant request failed"
                    )
                }
            )
            latch.countDown()
        }.start()
        return if (latch.await(12, TimeUnit.SECONDS)) {
            result.get() ?: HomeAssistantCommandResult(true, false, "Home Assistant request failed")
        } else {
            HomeAssistantCommandResult(true, false, "Home Assistant request timed out")
        }
    }

    private fun runBlockingEntityRequest(block: () -> HomeAssistantEntityResult): HomeAssistantEntityResult {
        val latch = CountDownLatch(1)
        val result = AtomicReference<HomeAssistantEntityResult>()
        Thread {
            result.set(
                runCatching(block).getOrElse { error ->
                    HomeAssistantEntityResult(
                        handled = true,
                        success = false,
                        message = error.message?.take(180) ?: "Home Assistant request failed"
                    )
                }
            )
            latch.countDown()
        }.start()
        return if (latch.await(12, TimeUnit.SECONDS)) {
            result.get() ?: HomeAssistantEntityResult(true, false, "Home Assistant request failed")
        } else {
            HomeAssistantEntityResult(true, false, "Home Assistant request timed out")
        }
    }

    private fun postJson(url: String, token: String, body: JSONObject): String {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 6_000
            readTimeout = 10_000
            doOutput = true
            setRequestProperty("Authorization", "Bearer $token")
            setRequestProperty("Content-Type", "application/json")
        }
        try {
            OutputStreamWriter(connection.outputStream, Charsets.UTF_8).use { writer ->
                writer.write(body.toString())
            }
            val stream = if (connection.responseCode in 200..299) connection.inputStream else connection.errorStream
            val response = stream?.let { BufferedReader(it.reader(Charsets.UTF_8)).use { reader -> reader.readText() } }.orEmpty()
            if (connection.responseCode !in 200..299) {
                throw IllegalStateException("HTTP ${connection.responseCode}: ${response.take(300)}")
            }
            return response
        } finally {
            connection.disconnect()
        }
    }

    private fun getJson(url: String, token: String): String {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 6_000
            readTimeout = 10_000
            setRequestProperty("Authorization", "Bearer $token")
            setRequestProperty("Accept", "application/json")
        }
        try {
            val stream = if (connection.responseCode in 200..299) connection.inputStream else connection.errorStream
            val response = stream?.let { BufferedReader(it.reader(Charsets.UTF_8)).use { reader -> reader.readText() } }.orEmpty()
            if (connection.responseCode !in 200..299) {
                throw IllegalStateException("HTTP ${connection.responseCode}: ${response.take(300)}")
            }
            return response
        } finally {
            connection.disconnect()
        }
    }

    private val PROTECTED_DOMAINS = setOf(
        "alarm_control_panel",
        "camera",
        "device_tracker",
        "geo_location",
        "lock",
        "person"
    )
}

private data class HomeAssistantCommand(
    val domain: String,
    val service: String,
    val entityId: String
)
