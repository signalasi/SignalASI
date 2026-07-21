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
    val credentialsConfigured: Boolean
        get() = baseUrl.isNotBlank() && accessToken.isNotBlank()

    val configured: Boolean
        get() = enabled && credentialsConfigured
}

object HomeAssistantSettingsStore {
    private const val PREFS = "signalasi_home_assistant"
    private const val KEY_SETTINGS = "settings"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_BASE_URL = "base_url"
    private const val KEY_ACCESS_TOKEN = "access_token"
    private const val KEY_DEFAULT_ENTITY_ID = "default_entity_id"

    fun load(context: Context): HomeAssistantSettings {
        migrateLegacySettings(context)
        return readStored(context)
    }

    fun save(context: Context, settings: HomeAssistantSettings) {
        val appContext = context.applicationContext
        migrateLegacySettings(appContext)
        val before = readStored(appContext)
        val after = settings.copy(
            baseUrl = settings.baseUrl.trim().trimEnd('/'),
            accessToken = settings.accessToken.trim(),
            defaultEntityId = settings.defaultEntityId.trim()
        )
        if (before == after) return
        writeStored(appContext, after)
        GlobalConversationEventBus.publishCapabilityEvents(
            appContext,
            GlobalCapabilityObservationExtractor.homeAssistantMutations(before, after)
        )
    }

    private fun readStored(context: Context): HomeAssistantSettings {
        val raw = AgentEncryptedPreferences(context, PREFS).readString(KEY_SETTINGS, "{}")
        val json = runCatching { JSONObject(raw) }.getOrDefault(JSONObject())
        return HomeAssistantSettings(
            enabled = json.optBoolean("enabled"),
            baseUrl = json.optString("base_url"),
            accessToken = json.optString("access_token"),
            defaultEntityId = json.optString("default_entity_id")
        )
    }

    private fun writeStored(context: Context, settings: HomeAssistantSettings) {
        AgentEncryptedPreferences(context, PREFS).writeString(
            KEY_SETTINGS,
            JSONObject()
                .put("version", 1)
                .put("enabled", settings.enabled)
                .put("base_url", settings.baseUrl)
                .put("access_token", settings.accessToken)
                .put("default_entity_id", settings.defaultEntityId)
                .toString()
        )
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
        AgentEncryptedPreferences(context, PREFS).clear()
    }

    private fun migrateLegacySettings(context: Context) {
        val encrypted = AgentEncryptedPreferences(context, PREFS)
        if (encrypted.contains(KEY_SETTINGS)) return
        val legacy = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (!legacy.contains(KEY_ENABLED) &&
            !legacy.contains(KEY_BASE_URL) &&
            !legacy.contains(KEY_ACCESS_TOKEN) &&
            !legacy.contains(KEY_DEFAULT_ENTITY_ID)
        ) return
        writeStored(
            context,
            HomeAssistantSettings(
                enabled = legacy.getBoolean(KEY_ENABLED, false),
                baseUrl = legacy.getString(KEY_BASE_URL, "") ?: "",
                accessToken = legacy.getString(KEY_ACCESS_TOKEN, "") ?: "",
                defaultEntityId = legacy.getString(KEY_DEFAULT_ENTITY_ID, "") ?: ""
            )
        )
        legacy.edit()
            .remove(KEY_ENABLED)
            .remove(KEY_BASE_URL)
            .remove(KEY_ACCESS_TOKEN)
            .remove(KEY_DEFAULT_ENTITY_ID)
            .apply()
    }
}

data class HomeAssistantCommandResult(
    val handled: Boolean,
    val success: Boolean,
    val message: String,
    val code: String = if (success) "ok" else "home_assistant_request_failed",
    val retryable: Boolean = false
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
    val entities: List<HomeAssistantEntityState> = emptyList(),
    val totalMatched: Int = entities.size,
    val truncated: Boolean = false,
    val observedAtEpochMillis: Long = System.currentTimeMillis(),
    val code: String = if (success) "ok" else "home_assistant_request_failed",
    val retryable: Boolean = false
)

data class HomeAssistantServiceCallResult(
    val handled: Boolean,
    val success: Boolean,
    val message: String,
    val serviceDomain: String,
    val service: String,
    val entityId: String,
    val requestAccepted: Boolean = false,
    val verificationSupported: Boolean = false,
    val controllerStateObserved: Boolean = false,
    val controllerStateVerified: Boolean = false,
    val previousState: String = "",
    val currentState: String = "",
    val stateProtected: Boolean = false,
    val changedStateCount: Int = 0,
    val physicalOutcomeVerified: Boolean = false,
    val code: String = if (success) "ok" else "home_assistant_request_failed",
    val retryable: Boolean = false
)

data class HomeAssistantServiceCallRequest(
    val serviceDomain: String,
    val service: String,
    val entityId: String,
    val serviceData: Map<String, Any?> = emptyMap()
)

object HomeAssistantDeviceClient {
    private val entityRegex = Regex("""\b[a-z_]+\.[A-Za-z0-9_]+\b""")

    fun control(context: Context, prompt: String): HomeAssistantCommandResult {
        val settings = HomeAssistantSettingsStore.load(context)
        if (!settings.configured) {
            return HomeAssistantCommandResult(
                false,
                false,
                "Home Assistant local API is not configured",
                code = "home_assistant_not_configured"
            )
        }
        val command = parseCommand(prompt, settings)
            ?: return HomeAssistantCommandResult(true, false, "Home Assistant entity is missing")
        return runServiceCall(settings, command)
    }

    fun serviceCallForPrompt(context: Context, prompt: String): HomeAssistantServiceCallRequest? =
        parseCommand(prompt, HomeAssistantSettingsStore.load(context))

    internal fun serviceCallForPrompt(prompt: String, defaultEntityId: String): HomeAssistantServiceCallRequest? =
        parseCommand(prompt, HomeAssistantSettings(defaultEntityId = defaultEntityId))

    fun connectionStatus(context: Context): HomeAssistantCommandResult {
        val settings = HomeAssistantSettingsStore.load(context)
        if (!settings.configured) {
            return HomeAssistantCommandResult(
                false,
                false,
                "Home Assistant local API is not configured",
                code = "home_assistant_not_configured"
            )
        }
        return runBlockingRequest {
            getJson("${settings.baseUrl}/api/", settings.accessToken)
            HomeAssistantCommandResult(true, true, "Home Assistant is connected")
        }
    }

    fun listEntities(
        context: Context,
        query: String = "",
        limit: Int = 40,
        domains: Set<String> = emptySet()
    ): HomeAssistantEntityResult {
        val settings = HomeAssistantSettingsStore.load(context)
        if (!settings.configured) {
            return HomeAssistantEntityResult(
                false,
                false,
                "Home Assistant local API is not configured",
                code = "home_assistant_not_configured"
            )
        }
        val cleanQuery = query.trim().lowercase(Locale.US)
        return runBlockingEntityRequest {
            val payload = JSONArray(getJson("${settings.baseUrl}/api/states", settings.accessToken))
            val matched = buildList {
                for (index in 0 until payload.length()) {
                    val item = payload.optJSONObject(index) ?: continue
                    val entityId = item.optString("entity_id").trim()
                    if (entityId.isBlank()) continue
                    val domain = entityId.substringBefore('.', "unknown")
                    if (domains.isNotEmpty() && domain !in domains) continue
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
            val boundedLimit = limit.coerceIn(1, 100)
            val entities = matched.take(boundedLimit)
            HomeAssistantEntityResult(
                handled = true,
                success = true,
                message = "Loaded ${entities.size} Home Assistant entities",
                entities = entities,
                totalMatched = matched.size,
                truncated = matched.size > boundedLimit
            )
        }
    }

    fun listScenes(context: Context, limit: Int = 40): HomeAssistantEntityResult =
        listEntities(context, limit = limit, domains = setOf("scene"))

    fun listAutomations(context: Context, limit: Int = 40): HomeAssistantEntityResult =
        listEntities(context, limit = limit, domains = setOf("automation"))

    fun listScripts(context: Context, limit: Int = 40): HomeAssistantEntityResult =
        listEntities(context, limit = limit, domains = setOf("script"))

    fun entityIdForPrompt(prompt: String): String = entityRegex.find(prompt)?.value.orEmpty()

    fun entityIdForPrompt(context: Context, prompt: String): String =
        entityIdForPrompt(prompt).ifBlank { HomeAssistantSettingsStore.load(context).defaultEntityId }

    fun riskForPrompt(prompt: String): AgentRisk = riskForEntity(prompt, entityIdForPrompt(prompt))

    fun riskForPrompt(context: Context, prompt: String): AgentRisk =
        riskForEntity(prompt, entityIdForPrompt(context, prompt))

    private fun riskForEntity(prompt: String, entityId: String): AgentRisk {
        val lower = prompt.lowercase(Locale.US)
        val domain = entityId.substringBefore('.', "")
        return when {
            domain in HIGH_RISK_CONTROL_DOMAINS -> AgentRisk.HIGH
            domain == "cover" && HIGH_RISK_CONTROL_TERMS.any { lower.contains(it) } -> AgentRisk.HIGH
            domain in MEDIUM_RISK_CONTROL_DOMAINS -> AgentRisk.MEDIUM
            HIGH_RISK_CONTROL_TERMS.any { lower.contains(it) } -> AgentRisk.HIGH
            MEDIUM_RISK_CONTROL_TERMS.any { lower.contains(it) } -> AgentRisk.MEDIUM
            else -> AgentRisk.LOW
        }
    }

    fun readEntity(context: Context, entityId: String): HomeAssistantEntityResult {
        val cleanEntityId = entityId.trim()
        val settings = HomeAssistantSettingsStore.load(context)
        if (!settings.configured) {
            return HomeAssistantEntityResult(
                false,
                false,
                "Home Assistant local API is not configured",
                code = "home_assistant_not_configured"
            )
        }
        if (!entityRegex.matches(cleanEntityId)) {
            return HomeAssistantEntityResult(
                true,
                false,
                "Invalid Home Assistant entity id",
                code = "invalid_home_assistant_entity"
            )
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

    fun callService(
        context: Context,
        serviceDomain: String,
        service: String,
        entityId: String,
        serviceData: Map<String, Any?> = emptyMap()
    ): HomeAssistantServiceCallResult {
        val settings = HomeAssistantSettingsStore.load(context)
        if (!settings.configured) {
            return HomeAssistantServiceCallResult(
                handled = false,
                success = false,
                message = "Home Assistant local API is not configured",
                serviceDomain = serviceDomain,
                service = service,
                entityId = entityId,
                code = "home_assistant_not_configured"
            )
        }
        val cleanDomain = serviceDomain.trim().lowercase(Locale.US)
        val cleanService = service.trim().lowercase(Locale.US)
        val cleanEntityId = entityId.trim().lowercase(Locale.US)
        val invalidReason = validateServiceCall(cleanDomain, cleanService, cleanEntityId, serviceData)
        if (invalidReason != null) {
            return HomeAssistantServiceCallResult(
                handled = true,
                success = false,
                message = invalidReason,
                serviceDomain = cleanDomain,
                service = cleanService,
                entityId = cleanEntityId,
                code = "invalid_home_assistant_service_call"
            )
        }
        return runBlockingServiceRequest {
            executeServiceCall(settings, cleanDomain, cleanService, cleanEntityId, serviceData)
        }.let { result ->
            if (result.serviceDomain.isNotBlank()) result else result.copy(
                serviceDomain = cleanDomain,
                service = cleanService,
                entityId = cleanEntityId
            )
        }
    }

    private fun parseCommand(
        prompt: String,
        settings: HomeAssistantSettings
    ): HomeAssistantServiceCallRequest? {
        val lower = prompt.lowercase(Locale.US)
        val entityId = entityRegex.find(prompt)?.value ?: settings.defaultEntityId
        if (entityId.isBlank()) return null
        val entityDomain = entityId.substringBefore('.', "homeassistant")
        val requestedDomain = when {
            lower.contains("run automation") || lower.contains("trigger automation") ||
                lower.contains("\u81ea\u52a8\u5316") -> "automation"
            lower.contains("run script") || lower.contains("execute script") ||
                lower.contains("\u811a\u672c") -> "script"
            lower.contains("activate scene") || lower.contains("run scene") ||
                lower.contains("\u573a\u666f") -> "scene"
            else -> null
        }
        if (requestedDomain != null && entityDomain != requestedDomain) return null
        val service = when {
            entityId.startsWith("automation.") || lower.contains("run automation") ||
                lower.contains("trigger automation") || lower.contains("\u81ea\u52a8\u5316") ->
                "trigger"
            entityId.startsWith("script.") || lower.contains("run script") ||
                lower.contains("execute script") || lower.contains("\u811a\u672c") ->
                "turn_on"
            entityId.startsWith("scene.") || lower.contains("activate scene") ||
                lower.contains("run scene") || lower.contains("\u573a\u666f") ->
                "turn_on"
            lower.contains("unlock") || lower.contains("\u89e3\u9501") -> "unlock"
            lower.contains("lock") || lower.contains("\u4e0a\u9501") || lower.contains("\u9501\u95e8") -> "lock"
            entityDomain == "cover" && (lower.contains("open") || lower.contains("\u6253\u5f00") ||
                lower.contains("\u5f00\u542f")) -> "open_cover"
            entityDomain == "cover" && (lower.contains("close") || lower.contains("\u5173\u95ed") ||
                lower.contains("\u5173\u4e0a")) -> "close_cover"
            entityDomain == "valve" && (lower.contains("open") || lower.contains("\u6253\u5f00")) -> "open_valve"
            entityDomain == "valve" && (lower.contains("close") || lower.contains("\u5173\u95ed")) -> "close_valve"
            lower.contains("turn off") || lower.contains("power off") || lower.endsWith(" off") ||
                lower.contains("\u5173\u95ed") || lower.contains("\u5173\u6389") || lower.contains("\u5173\u706f") -> "turn_off"
            lower.contains("turn on") || lower.contains("power on") || lower.endsWith(" on") ||
                lower.contains("\u6253\u5f00") || lower.contains("\u5f00\u542f") || lower.contains("\u5f00\u706f") -> "turn_on"
            lower.contains("toggle") || lower.contains("switch") || lower.contains("\u5207\u6362") -> "toggle"
            lower.contains("activate") || lower.contains("scene") -> "turn_on"
            else -> "toggle"
        }
        val serviceDomain = when (service) {
            "trigger" -> "automation"
            "turn_on" -> when (entityDomain) {
                "scene", "script" -> entityDomain
                else -> "homeassistant"
            }
            "turn_off", "toggle" -> "homeassistant"
            "open_cover", "close_cover" -> "cover"
            "open_valve", "close_valve" -> "valve"
            "lock", "unlock" -> "lock"
            else -> entityDomain
        }
        return HomeAssistantServiceCallRequest(
            serviceDomain = serviceDomain,
            service = service,
            entityId = entityId,
            serviceData = if (service == "trigger") {
                mapOf("skip_condition" to lower.contains("skip condition"))
            } else {
                emptyMap()
            }
        )
    }

    private fun runServiceCall(
        settings: HomeAssistantSettings,
        command: HomeAssistantServiceCallRequest
    ): HomeAssistantCommandResult {
        val result = runBlockingServiceRequest {
            executeServiceCall(
                settings = settings,
                serviceDomain = command.serviceDomain,
                service = command.service,
                entityId = command.entityId,
                serviceData = command.serviceData
            )
        }
        return HomeAssistantCommandResult(
            handled = result.handled,
            success = result.success,
            message = result.message,
            code = result.code,
            retryable = result.retryable
        )
    }

    private fun executeServiceCall(
        settings: HomeAssistantSettings,
        serviceDomain: String,
        service: String,
        entityId: String,
        serviceData: Map<String, Any?>
    ): HomeAssistantServiceCallResult {
        val entityDomain = entityId.substringBefore('.', "unknown")
        val stateProtected = entityDomain in PROTECTED_DOMAINS
        val previousRawState = readRawEntityState(settings, entityId)
        val expectedState = expectedState(entityDomain, service, previousRawState, serviceData)
        val body = JSONObject().put("entity_id", entityId)
        serviceData.forEach { (key, value) -> body.put(key, value) }
        val response = postJson(
            url = "${settings.baseUrl}/api/services/$serviceDomain/$service",
            token = settings.accessToken,
            body = body
        )
        val changedStateCount = runCatching { JSONArray(response).length() }.getOrDefault(0)
        var currentRawState = runCatching { readRawEntityState(settings, entityId) }.getOrDefault("")
        if (expectedState != null && !statesMatch(expectedState, currentRawState)) {
            for (attempt in 0 until MAX_STATE_VERIFICATION_RETRIES) {
                Thread.sleep(STATE_VERIFICATION_RETRY_DELAY_MILLIS)
                currentRawState = runCatching { readRawEntityState(settings, entityId) }.getOrDefault("")
                if (statesMatch(expectedState, currentRawState)) break
            }
        }
        val controllerStateObserved = currentRawState.isNotBlank()
        val controllerStateVerified = expectedState != null && statesMatch(expectedState, currentRawState)
        return HomeAssistantServiceCallResult(
            handled = true,
            success = true,
            message = when {
                controllerStateVerified -> "Home Assistant accepted the service call and controller state matched"
                expectedState != null -> "Home Assistant accepted the service call, but controller state did not match yet"
                else -> "Home Assistant accepted the service call"
            },
            serviceDomain = serviceDomain,
            service = service,
            entityId = entityId,
            requestAccepted = true,
            verificationSupported = expectedState != null,
            controllerStateObserved = controllerStateObserved,
            controllerStateVerified = controllerStateVerified,
            previousState = redactState(previousRawState, stateProtected),
            currentState = redactState(currentRawState, stateProtected),
            stateProtected = stateProtected,
            changedStateCount = changedStateCount,
            physicalOutcomeVerified = false
        )
    }

    private fun readRawEntityState(settings: HomeAssistantSettings, entityId: String): String =
        JSONObject(getJson("${settings.baseUrl}/api/states/$entityId", settings.accessToken))
            .optString("state", "unknown")
            .take(MAX_STATE_CHARACTERS)

    private fun expectedState(
        entityDomain: String,
        service: String,
        previousState: String,
        serviceData: Map<String, Any?>
    ): String? = when (service) {
        "turn_on" -> if (entityDomain in BINARY_TURN_STATE_DOMAINS) "on" else null
        "turn_off" -> if (entityDomain in BINARY_TURN_STATE_DOMAINS || entityDomain == "climate") "off" else null
        "lock" -> "locked"
        "unlock" -> "unlocked"
        "open_cover", "open_valve" -> "open"
        "close_cover", "close_valve" -> "closed"
        "toggle" -> when (previousState.lowercase(Locale.US)) {
            "on" -> "off"
            "off" -> "on"
            "locked" -> "unlocked"
            "unlocked" -> "locked"
            "open" -> "closed"
            "closed" -> "open"
            else -> null
        }
        "set_hvac_mode" -> serviceData["hvac_mode"]?.toString()
        "select_option" -> serviceData["option"]?.toString()
        "set_value" -> serviceData["value"]?.toString()
        else -> null
    }?.trim()?.takeIf(String::isNotBlank)?.take(MAX_STATE_CHARACTERS)

    private fun statesMatch(expected: String, actual: String): Boolean {
        if (actual.isBlank()) return false
        val expectedNumber = expected.toDoubleOrNull()
        val actualNumber = actual.toDoubleOrNull()
        return if (expectedNumber != null && actualNumber != null) {
            kotlin.math.abs(expectedNumber - actualNumber) < 0.0001
        } else {
            expected.equals(actual, ignoreCase = true)
        }
    }

    private fun redactState(state: String, protected: Boolean): String = when {
        state.isBlank() -> ""
        protected -> "protected"
        else -> state.take(MAX_STATE_CHARACTERS)
    }

    private fun validateServiceCall(
        serviceDomain: String,
        service: String,
        entityId: String,
        serviceData: Map<String, Any?>
    ): String? {
        if (!NAME_REGEX.matches(serviceDomain)) return "Invalid Home Assistant service domain"
        if (!NAME_REGEX.matches(service)) return "Invalid Home Assistant service"
        if (!entityRegex.matches(entityId)) return "Invalid Home Assistant entity id"
        val entityDomain = entityId.substringBefore('.')
        if (serviceDomain == "homeassistant") {
            if (service !in GENERIC_ENTITY_SERVICES) return "Unsupported Home Assistant core service"
        } else if (serviceDomain != entityDomain) {
            return "Home Assistant service domain must match the target entity"
        }
        if (serviceDomain == "homeassistant" && service in BLOCKED_ADMIN_SERVICES) {
            return "Administrative Home Assistant services are not exposed"
        }
        if (serviceData.size > MAX_SERVICE_DATA_ENTRIES) return "Home Assistant service data is too large"
        if (serviceData.keys.any { !NAME_REGEX.matches(it) || it in SECRET_PARAMETER_NAMES }) {
            return "Home Assistant service data contains an unsupported parameter"
        }
        if (!isBoundedServiceData(serviceData) || JSONObject(serviceData).toString().length > MAX_SERVICE_DATA_CHARACTERS) {
            return "Home Assistant service data is not a bounded JSON object"
        }
        return null
    }

    private fun isBoundedServiceData(value: Any?, depth: Int = 0): Boolean {
        if (depth > MAX_SERVICE_DATA_DEPTH) return false
        return when (value) {
            null, is Boolean, is Number -> true
            is String -> value.length <= MAX_SERVICE_VALUE_CHARACTERS
            is Map<*, *> -> value.size <= MAX_SERVICE_DATA_ENTRIES && value.all { (key, item) ->
                key is String && NAME_REGEX.matches(key) && key !in SECRET_PARAMETER_NAMES &&
                    isBoundedServiceData(item, depth + 1)
            }
            is Iterable<*> -> value.count() <= MAX_SERVICE_ARRAY_ITEMS &&
                value.all { isBoundedServiceData(it, depth + 1) }
            is Array<*> -> value.size <= MAX_SERVICE_ARRAY_ITEMS &&
                value.all { isBoundedServiceData(it, depth + 1) }
            else -> false
        }
    }

    private fun runBlockingServiceRequest(block: () -> HomeAssistantServiceCallResult): HomeAssistantServiceCallResult {
        val latch = CountDownLatch(1)
        val result = AtomicReference<HomeAssistantServiceCallResult>()
        Thread {
            result.set(
                runCatching(block).getOrElse { error ->
                    HomeAssistantServiceCallResult(
                        handled = true,
                        success = false,
                        message = error.message?.take(180) ?: "Home Assistant request failed",
                        serviceDomain = "",
                        service = "",
                        entityId = "",
                        code = requestErrorCode(error),
                        retryable = requestErrorRetryable(error)
                    )
                }
            )
            latch.countDown()
        }.start()
        return if (latch.await(SERVICE_CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            result.get() ?: HomeAssistantServiceCallResult(
                true, false, "Home Assistant request failed", "", "", "",
                code = "home_assistant_request_failed", retryable = true
            )
        } else {
            HomeAssistantServiceCallResult(
                true, false, "Home Assistant request timed out", "", "", "",
                code = "home_assistant_timeout", retryable = true
            )
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
                        message = error.message?.take(180) ?: "Home Assistant request failed",
                        code = requestErrorCode(error),
                        retryable = requestErrorRetryable(error)
                    )
                }
            )
            latch.countDown()
        }.start()
        return if (latch.await(12, TimeUnit.SECONDS)) {
            result.get() ?: HomeAssistantCommandResult(
                true,
                false,
                "Home Assistant request failed",
                retryable = true
            )
        } else {
            HomeAssistantCommandResult(
                true,
                false,
                "Home Assistant request timed out",
                code = "home_assistant_timeout",
                retryable = true
            )
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
                        message = error.message?.take(180) ?: "Home Assistant request failed",
                        code = requestErrorCode(error),
                        retryable = requestErrorRetryable(error)
                    )
                }
            )
            latch.countDown()
        }.start()
        return if (latch.await(12, TimeUnit.SECONDS)) {
            result.get() ?: HomeAssistantEntityResult(
                true,
                false,
                "Home Assistant request failed",
                code = "home_assistant_request_failed",
                retryable = true
            )
        } else {
            HomeAssistantEntityResult(
                true,
                false,
                "Home Assistant request timed out",
                code = "home_assistant_timeout",
                retryable = true
            )
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
                throw IllegalStateException("Home Assistant HTTP ${connection.responseCode}")
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
                throw IllegalStateException("Home Assistant HTTP ${connection.responseCode}")
            }
            return response
        } finally {
            connection.disconnect()
        }
    }

    private fun requestErrorCode(error: Throwable): String = when {
        error.message.orEmpty().contains("HTTP 401") || error.message.orEmpty().contains("HTTP 403") ->
            "home_assistant_auth_failed"
        error.message.orEmpty().contains("HTTP 404") -> "home_assistant_not_found"
        else -> "home_assistant_request_failed"
    }

    private fun requestErrorRetryable(error: Throwable): Boolean =
        requestErrorCode(error) == "home_assistant_request_failed"

    private val NAME_REGEX = Regex("[a-z_][a-z0-9_]*")

    private val GENERIC_ENTITY_SERVICES = setOf(
        "turn_on",
        "turn_off",
        "toggle",
        "update_entity"
    )

    private val BINARY_TURN_STATE_DOMAINS = setOf(
        "automation",
        "fan",
        "input_boolean",
        "light",
        "media_player",
        "siren",
        "switch"
    )

    private val BLOCKED_ADMIN_SERVICES = setOf(
        "check_config",
        "clear",
        "delete",
        "purge",
        "reload",
        "remove",
        "restart",
        "stop"
    )

    private val SECRET_PARAMETER_NAMES = setOf(
        "access_token",
        "api_key",
        "code",
        "password",
        "pin",
        "secret",
        "token"
    )

    private const val MAX_SERVICE_DATA_ENTRIES = 16
    private const val MAX_SERVICE_DATA_DEPTH = 2
    private const val MAX_SERVICE_ARRAY_ITEMS = 32
    private const val MAX_SERVICE_DATA_CHARACTERS = 8_192
    private const val MAX_SERVICE_VALUE_CHARACTERS = 1_024
    private const val MAX_STATE_CHARACTERS = 100
    private const val MAX_STATE_VERIFICATION_RETRIES = 3
    private const val STATE_VERIFICATION_RETRY_DELAY_MILLIS = 250L
    private const val SERVICE_CALL_TIMEOUT_SECONDS = 14L

    private val PROTECTED_DOMAINS = setOf(
        "alarm_control_panel",
        "camera",
        "device_tracker",
        "geo_location",
        "lock",
        "person"
    )

    private val HIGH_RISK_CONTROL_DOMAINS = setOf(
        "alarm_control_panel",
        "automation",
        "camera",
        "lock",
        "siren",
        "script",
        "valve"
    )

    private val MEDIUM_RISK_CONTROL_DOMAINS = setOf(
        "climate",
        "cover",
        "fan",
        "scene",
        "switch",
        "vacuum"
    )

    private val HIGH_RISK_CONTROL_TERMS = listOf(
        "alarm",
        "camera",
        "door",
        "gate",
        "garage",
        "lock",
        "security",
        "siren",
        "valve"
    )

    private val MEDIUM_RISK_CONTROL_TERMS = listOf(
        "automation",
        "blind",
        "climate",
        "cover",
        "curtain",
        "scene",
        "script",
        "switch",
        "thermostat"
    )
}
