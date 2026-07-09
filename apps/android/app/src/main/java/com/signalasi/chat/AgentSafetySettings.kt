package com.signalasi.chat

import android.content.Context

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
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    override fun load(): AgentSafetySettings = AgentSafetySettings(
        permissionMode = enumOrDefault(
            prefs.getString(KEY_PERMISSION_MODE, null).orEmpty(),
            PermissionMode.ASK_BEFORE_ACTION
        ),
        highRiskGuard = prefs.getBoolean(KEY_HIGH_RISK_GUARD, true),
        memoryCapture = prefs.getBoolean(KEY_MEMORY_CAPTURE, true)
    )

    override fun save(settings: AgentSafetySettings) {
        prefs.edit()
            .putString(KEY_PERMISSION_MODE, settings.permissionMode.name)
            .putBoolean(KEY_HIGH_RISK_GUARD, settings.highRiskGuard)
            .putBoolean(KEY_MEMORY_CAPTURE, settings.memoryCapture)
            .apply()
    }

    private inline fun <reified T : Enum<T>> enumOrDefault(value: String, default: T): T =
        runCatching { enumValueOf<T>(value) }.getOrElse { default }

    private companion object {
        const val PREFS = "signalasi_agent_safety"
        const val KEY_PERMISSION_MODE = "permission_mode"
        const val KEY_HIGH_RISK_GUARD = "high_risk_guard"
        const val KEY_MEMORY_CAPTURE = "memory_capture"
    }
}
