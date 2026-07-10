package com.signalasi.chat

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

object AgentBackupData {
    private const val MEMORY_PREFS = "signalasi_agent_memory"
    private const val KNOWLEDGE_PREFS = "signalasi_agent_knowledge"
    private const val TASK_PREFS = "signalasi_agent_tasks"
    private const val WORKFLOW_PREFS = "signalasi_agent_workflows"
    private const val SCHEDULE_PREFS = "signalasi_agent_workflow_schedules"
    private const val TRIGGER_PREFS = "signalasi_agent_workflow_triggers"
    private const val WORKFLOW_EXECUTION_HISTORY_PREFS = "signalasi_agent_workflow_execution_history"
    private const val ITEMS_KEY = "items"

    fun export(context: Context): JSONObject {
        val safety = SharedPreferencesAgentSafetySettingsStore(context).load()
        val homeAssistant = HomeAssistantSettingsStore.load(context)
        return JSONObject()
            .put("version", 1)
            .put("memory", readArray(context, MEMORY_PREFS, MAX_MEMORY_ITEMS, MAX_MEMORY_ITEM_CHARACTERS))
            .put("knowledge", readArray(context, KNOWLEDGE_PREFS, MAX_KNOWLEDGE_ITEMS, MAX_KNOWLEDGE_ITEM_CHARACTERS))
            .put("tasks", readArray(context, TASK_PREFS, MAX_TASK_ITEMS, MAX_TASK_ITEM_CHARACTERS))
            .put("workflows", readArray(context, WORKFLOW_PREFS, MAX_WORKFLOW_ITEMS, MAX_WORKFLOW_ITEM_CHARACTERS))
            .put("workflow_schedules", readArray(context, SCHEDULE_PREFS, MAX_SCHEDULE_ITEMS, MAX_SCHEDULE_ITEM_CHARACTERS))
            .put("workflow_triggers", readArray(context, TRIGGER_PREFS, MAX_TRIGGER_ITEMS, MAX_TRIGGER_ITEM_CHARACTERS))
            .put(
                "workflow_execution_history",
                readArray(
                    context,
                    WORKFLOW_EXECUTION_HISTORY_PREFS,
                    MAX_WORKFLOW_EXECUTION_HISTORY_ITEMS,
                    MAX_WORKFLOW_EXECUTION_HISTORY_ITEM_CHARACTERS
                )
            )
            .put(
                "safety",
                JSONObject()
                    .put("permission_mode", safety.permissionMode.name)
                    .put("high_risk_guard", safety.highRiskGuard)
                    .put("memory_capture", safety.memoryCapture)
            )
            .put(
                "home_assistant",
                JSONObject()
                    .put("enabled", homeAssistant.enabled)
                    .put("base_url", homeAssistant.baseUrl)
                    .put("access_token", homeAssistant.accessToken)
                    .put("default_entity_id", homeAssistant.defaultEntityId)
            )
    }

    fun restore(context: Context, payload: JSONObject) {
        payload.optJSONArray("memory")?.let { input ->
            val sanitized = sanitizeArray(input, MAX_MEMORY_ITEMS, MAX_MEMORY_ITEM_CHARACTERS)
            AgentEncryptedPreferences(context, MEMORY_PREFS).writeString(ITEMS_KEY, sanitized.toString())
        }
        payload.optJSONArray("knowledge")?.let { input ->
            val sanitized = sanitizeArray(input, MAX_KNOWLEDGE_ITEMS, MAX_KNOWLEDGE_ITEM_CHARACTERS)
            AgentEncryptedPreferences(context, KNOWLEDGE_PREFS).writeString(ITEMS_KEY, sanitized.toString())
        }
        payload.optJSONArray("tasks")?.let { input ->
            val sanitized = sanitizeArray(input, MAX_TASK_ITEMS, MAX_TASK_ITEM_CHARACTERS)
            AgentEncryptedPreferences(context, TASK_PREFS).writeString(ITEMS_KEY, sanitized.toString())
        }
        payload.optJSONArray("workflows")?.let { input ->
            val sanitized = sanitizeArray(input, MAX_WORKFLOW_ITEMS, MAX_WORKFLOW_ITEM_CHARACTERS)
            AgentEncryptedPreferences(context, WORKFLOW_PREFS).writeString(ITEMS_KEY, sanitized.toString())
        }
        payload.optJSONArray("workflow_schedules")?.let { input ->
            val sanitized = sanitizeArray(input, MAX_SCHEDULE_ITEMS, MAX_SCHEDULE_ITEM_CHARACTERS)
            AgentEncryptedPreferences(context, SCHEDULE_PREFS).writeString(ITEMS_KEY, sanitized.toString())
        }
        payload.optJSONArray("workflow_triggers")?.let { input ->
            val sanitized = sanitizeArray(input, MAX_TRIGGER_ITEMS, MAX_TRIGGER_ITEM_CHARACTERS)
            AgentEncryptedPreferences(context, TRIGGER_PREFS).writeString(ITEMS_KEY, sanitized.toString())
        }
        payload.optJSONArray("workflow_execution_history")?.let { input ->
            val sanitized = sanitizeArray(
                input,
                MAX_WORKFLOW_EXECUTION_HISTORY_ITEMS,
                MAX_WORKFLOW_EXECUTION_HISTORY_ITEM_CHARACTERS
            )
            AgentEncryptedPreferences(context, WORKFLOW_EXECUTION_HISTORY_PREFS)
                .writeString(ITEMS_KEY, sanitized.toString())
        }
        payload.optJSONObject("safety")?.let { json ->
            SharedPreferencesAgentSafetySettingsStore(context).save(
                AgentSafetySettings(
                    permissionMode = enumOrDefault(
                        json.optString("permission_mode"),
                        PermissionMode.ASK_BEFORE_ACTION
                    ),
                    highRiskGuard = json.optBoolean("high_risk_guard", true),
                    memoryCapture = json.optBoolean("memory_capture", true)
                )
            )
        }
        payload.optJSONObject("home_assistant")?.let { json ->
            HomeAssistantSettingsStore.save(
                context,
                HomeAssistantSettings(
                    enabled = json.optBoolean("enabled"),
                    baseUrl = json.optString("base_url").take(MAX_URL_CHARACTERS),
                    accessToken = json.optString("access_token").take(MAX_SECRET_CHARACTERS),
                    defaultEntityId = json.optString("default_entity_id").take(MAX_ENTITY_ID_CHARACTERS)
                )
            )
        }
    }

    private fun readArray(context: Context, preferencesName: String, maxItems: Int, maxItemCharacters: Int): JSONArray {
        val raw = AgentEncryptedPreferences(context, preferencesName).readString(ITEMS_KEY, "[]")
        val array = runCatching { JSONArray(raw) }.getOrDefault(JSONArray())
        return sanitizeArray(array, maxItems, maxItemCharacters)
    }

    private fun sanitizeArray(input: JSONArray, maxItems: Int, maxItemCharacters: Int): JSONArray {
        val output = JSONArray()
        val start = (input.length() - maxItems).coerceAtLeast(0)
        for (index in start until input.length()) {
            val item = input.optJSONObject(index) ?: continue
            if (item.toString().length <= maxItemCharacters) output.put(item)
        }
        return output
    }

    private inline fun <reified T : Enum<T>> enumOrDefault(value: String, default: T): T =
        runCatching { enumValueOf<T>(value) }.getOrElse { default }

    private const val MAX_MEMORY_ITEMS = 200
    private const val MAX_MEMORY_ITEM_CHARACTERS = 24_000
    private const val MAX_KNOWLEDGE_ITEMS = 500
    private const val MAX_KNOWLEDGE_ITEM_CHARACTERS = 20_000
    private const val MAX_TASK_ITEMS = 200
    private const val MAX_TASK_ITEM_CHARACTERS = 12_000
    private const val MAX_WORKFLOW_ITEMS = 100
    private const val MAX_WORKFLOW_ITEM_CHARACTERS = 4_000
    private const val MAX_SCHEDULE_ITEMS = 100
    private const val MAX_SCHEDULE_ITEM_CHARACTERS = 4_000
    private const val MAX_TRIGGER_ITEMS = 100
    private const val MAX_TRIGGER_ITEM_CHARACTERS = 20_000
    private const val MAX_WORKFLOW_EXECUTION_HISTORY_ITEMS = 200
    private const val MAX_WORKFLOW_EXECUTION_HISTORY_ITEM_CHARACTERS = 4_000
    private const val MAX_URL_CHARACTERS = 2_000
    private const val MAX_SECRET_CHARACTERS = 8_000
    private const val MAX_ENTITY_ID_CHARACTERS = 240
}
