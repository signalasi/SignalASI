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

    override fun load(): AgentSafetySettings = readStored()

    override fun save(settings: AgentSafetySettings) {
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

    private inline fun <reified T : Enum<T>> enumOrDefault(value: String, default: T): T =
        runCatching { enumValueOf<T>(value) }.getOrElse { default }

    private companion object {
        const val PREFS = "signalasi_agent_safety"
        const val KEY_SETTINGS = "settings"
    }
}
