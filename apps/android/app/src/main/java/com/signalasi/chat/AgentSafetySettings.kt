package com.signalasi.chat

import android.content.Context
import org.json.JSONObject

data class AgentSafetySettings(
    val permissionMode: PermissionMode = PermissionMode.ASK_BEFORE_ACTION,
    val highRiskGuard: Boolean = true,
    val memoryCapture: Boolean = true,
    val screenObservationAllowed: Boolean = true,
    val localActionsAllowed: Boolean = true,
    val connectorCallsAllowed: Boolean = true,
    val deviceControlAllowed: Boolean = true,
    val executionPaused: Boolean = false
)

interface AgentSafetySettingsStore {
    fun load(): AgentSafetySettings
    fun save(settings: AgentSafetySettings)
}

class SharedPreferencesAgentSafetySettingsStore(context: Context) : AgentSafetySettingsStore {
    private val appContext = context.applicationContext
    private val prefs = AgentEncryptedPreferences(appContext, PREFS)

    override fun load(): AgentSafetySettings {
        migrateLegacySettings()
        return readStored()
    }

    override fun save(settings: AgentSafetySettings) {
        migrateLegacySettings()
        val before = readStored()
        if (before == settings) return
        writeStored(settings)
        GlobalCapabilityObservationExtractor.safetyPolicyMutation(before, settings)?.let { event ->
            GlobalConversationEventBus.publishCapabilityEvents(appContext, listOf(event))
        }
    }

    private fun readStored(): AgentSafetySettings {
        val json = runCatching { JSONObject(prefs.readString(KEY_SETTINGS, "{}")) }.getOrDefault(JSONObject())
        return AgentSafetySettings(
            permissionMode = enumOrDefault(
                json.optString("permission_mode"),
                PermissionMode.ASK_BEFORE_ACTION
            ),
            highRiskGuard = json.optBoolean("high_risk_guard", true),
            memoryCapture = json.optBoolean("memory_capture", true),
            screenObservationAllowed = json.optBoolean("screen_observation_allowed", true),
            localActionsAllowed = json.optBoolean("local_actions_allowed", true),
            connectorCallsAllowed = json.optBoolean("connector_calls_allowed", true),
            deviceControlAllowed = json.optBoolean("device_control_allowed", true),
            executionPaused = json.optBoolean("execution_paused", false)
        )
    }

    private fun writeStored(settings: AgentSafetySettings) {
        prefs.writeString(
            KEY_SETTINGS,
            JSONObject()
                .put("version", 2)
                .put("permission_mode", settings.permissionMode.name)
                .put("high_risk_guard", settings.highRiskGuard)
                .put("memory_capture", settings.memoryCapture)
                .put("screen_observation_allowed", settings.screenObservationAllowed)
                .put("local_actions_allowed", settings.localActionsAllowed)
                .put("connector_calls_allowed", settings.connectorCallsAllowed)
                .put("device_control_allowed", settings.deviceControlAllowed)
                .put("execution_paused", settings.executionPaused)
                .toString()
        )
    }

    private fun migrateLegacySettings() {
        if (prefs.contains(KEY_SETTINGS)) return
        val legacy = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (!legacy.contains(KEY_PERMISSION_MODE) &&
            !legacy.contains(KEY_HIGH_RISK_GUARD) &&
            !legacy.contains(KEY_MEMORY_CAPTURE)
        ) return
        val settings = AgentSafetySettings(
            permissionMode = enumOrDefault(
                legacy.getString(KEY_PERMISSION_MODE, null).orEmpty(),
                PermissionMode.ASK_BEFORE_ACTION
            ),
            highRiskGuard = legacy.getBoolean(KEY_HIGH_RISK_GUARD, true),
            memoryCapture = legacy.getBoolean(KEY_MEMORY_CAPTURE, true)
        )
        writeStored(settings)
        legacy.edit()
            .remove(KEY_PERMISSION_MODE)
            .remove(KEY_HIGH_RISK_GUARD)
            .remove(KEY_MEMORY_CAPTURE)
            .apply()
    }

    private inline fun <reified T : Enum<T>> enumOrDefault(value: String, default: T): T =
        runCatching { enumValueOf<T>(value) }.getOrElse { default }

    private companion object {
        const val PREFS = "signalasi_agent_safety"
        const val KEY_SETTINGS = "settings"
        const val KEY_PERMISSION_MODE = "permission_mode"
        const val KEY_HIGH_RISK_GUARD = "high_risk_guard"
        const val KEY_MEMORY_CAPTURE = "memory_capture"
    }
}
