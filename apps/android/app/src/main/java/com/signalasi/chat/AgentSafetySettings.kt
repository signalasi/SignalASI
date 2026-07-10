package com.signalasi.chat

import android.content.Context
import org.json.JSONObject

data class AgentSafetySettings(
    val permissionMode: PermissionMode = PermissionMode.ASK_BEFORE_ACTION,
    val highRiskGuard: Boolean = true,
    val memoryCapture: Boolean = true
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
        val json = runCatching { JSONObject(prefs.readString(KEY_SETTINGS, "{}")) }.getOrDefault(JSONObject())
        return AgentSafetySettings(
            permissionMode = enumOrDefault(
                json.optString("permission_mode"),
                PermissionMode.ASK_BEFORE_ACTION
            ),
            highRiskGuard = json.optBoolean("high_risk_guard", true),
            memoryCapture = json.optBoolean("memory_capture", true)
        )
    }

    override fun save(settings: AgentSafetySettings) {
        prefs.writeString(
            KEY_SETTINGS,
            JSONObject()
                .put("version", 1)
                .put("permission_mode", settings.permissionMode.name)
                .put("high_risk_guard", settings.highRiskGuard)
                .put("memory_capture", settings.memoryCapture)
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
        save(settings)
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
