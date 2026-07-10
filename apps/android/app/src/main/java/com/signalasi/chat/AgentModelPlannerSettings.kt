package com.signalasi.chat

import android.content.Context
import org.json.JSONObject

data class AgentModelPlannerSettings(
    val enabled: Boolean = false,
    val shareScreenText: Boolean = false,
    val maxActions: Int = 8,
    val cloudContactId: String = "",
    val dynamicReplanning: Boolean = true,
    val maxReplans: Int = 3,
    val multiAgentCoordination: Boolean = true,
    val shareAgentOutputsWithPlanner: Boolean = false,
    val maxAgentHops: Int = 4,
    val maxToolCalls: Int = 16
)

class AgentModelPlannerSettingsStore(context: Context) {
    private val preferences = AgentEncryptedPreferences(context.applicationContext, PREFS)

    fun load(): AgentModelPlannerSettings {
        val json = runCatching { JSONObject(preferences.readString(KEY_SETTINGS, "{}")) }
            .getOrDefault(JSONObject())
        return AgentModelPlannerSettings(
            enabled = json.optBoolean("enabled", false),
            shareScreenText = json.optBoolean("share_screen_text", false),
            maxActions = json.optInt("max_actions", DEFAULT_MAX_ACTIONS).coerceIn(1, MAX_ACTIONS),
            cloudContactId = json.optString("cloud_contact_id").trim().take(120),
            dynamicReplanning = json.optBoolean("dynamic_replanning", true),
            maxReplans = json.optInt("max_replans", DEFAULT_MAX_REPLANS).coerceIn(1, MAX_REPLANS),
            multiAgentCoordination = json.optBoolean("multi_agent_coordination", true),
            shareAgentOutputsWithPlanner = json.optBoolean("share_agent_outputs_with_planner", false),
            maxAgentHops = json.optInt("max_agent_hops", DEFAULT_MAX_AGENT_HOPS).coerceIn(1, MAX_AGENT_HOPS),
            maxToolCalls = json.optInt("max_tool_calls", DEFAULT_MAX_TOOL_CALLS).coerceIn(MIN_TOOL_CALLS, MAX_TOOL_CALLS)
        )
    }

    fun save(settings: AgentModelPlannerSettings) {
        preferences.writeString(
            KEY_SETTINGS,
            JSONObject()
                .put("version", 4)
                .put("enabled", settings.enabled)
                .put("share_screen_text", settings.shareScreenText)
                .put("max_actions", settings.maxActions.coerceIn(1, MAX_ACTIONS))
                .put("cloud_contact_id", settings.cloudContactId.trim().take(120))
                .put("dynamic_replanning", settings.dynamicReplanning)
                .put("max_replans", settings.maxReplans.coerceIn(1, MAX_REPLANS))
                .put("multi_agent_coordination", settings.multiAgentCoordination)
                .put("share_agent_outputs_with_planner", settings.shareAgentOutputsWithPlanner)
                .put("max_agent_hops", settings.maxAgentHops.coerceIn(1, MAX_AGENT_HOPS))
                .put("max_tool_calls", settings.maxToolCalls.coerceIn(MIN_TOOL_CALLS, MAX_TOOL_CALLS))
                .toString()
        )
    }

    fun clear() = preferences.clear()

    private companion object {
        const val PREFS = "signalasi_agent_model_planner"
        const val KEY_SETTINGS = "settings"
        const val DEFAULT_MAX_ACTIONS = 8
        const val MAX_ACTIONS = 12
        const val DEFAULT_MAX_REPLANS = 3
        const val MAX_REPLANS = 5
        const val DEFAULT_MAX_AGENT_HOPS = 4
        const val MAX_AGENT_HOPS = 8
        const val DEFAULT_MAX_TOOL_CALLS = 16
        const val MIN_TOOL_CALLS = 4
        const val MAX_TOOL_CALLS = 32
    }
}
