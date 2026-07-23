package com.signalasi.chat

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

object AgentBackupData {
    private const val MEMORY_DATABASE = "signalasi_agent_memory_v2"
    private const val KNOWLEDGE_PREFS = "signalasi_agent_knowledge"
    private const val TASK_PREFS = "signalasi_agent_tasks"
    private const val WORKFLOW_PREFS = "signalasi_agent_workflows"
    private const val SCHEDULE_PREFS = "signalasi_agent_workflow_schedules"
    private const val TRIGGER_PREFS = "signalasi_agent_workflow_triggers"
    private const val WORKFLOW_EXECUTION_HISTORY_PREFS = "signalasi_agent_workflow_execution_history"
    private const val TRANSCRIPT_PREFS = AgentTranscriptStore.PREFS
    private const val ITEMS_KEY = "items"

    fun export(context: Context, includeSessionHistory: Boolean = true): JSONObject {
        val safety = SharedPreferencesAgentSafetySettingsStore(context).load()
        val modelPlanner = AgentModelPlannerSettingsStore(context).load()
        val voiceAssistant = VoiceAssistantSettings.get(context)
        val homeAssistant = HomeAssistantSettingsStore.load(context)
        val customDevices = CustomDeviceConnectorStore(context).exportJson()
        return JSONObject()
            .put("version", 27)
            .put("memory", readDatabaseArray(context, MEMORY_DATABASE, MAX_MEMORY_ITEMS, MAX_MEMORY_ITEM_CHARACTERS))
            .put("knowledge", readArray(context, KNOWLEDGE_PREFS, MAX_KNOWLEDGE_ITEMS, MAX_KNOWLEDGE_ITEM_CHARACTERS))
            .put("tasks", if (includeSessionHistory) readArray(context, TASK_PREFS, MAX_TASK_ITEMS, MAX_TASK_ITEM_CHARACTERS) else JSONArray())
            .put("transcript", if (includeSessionHistory) readAgentTranscriptArray(context) else JSONArray())
            .put("agent_conversations", if (includeSessionHistory) readAgentConversationArray(context) else JSONArray())
            .put(
                "active_agent_conversation",
                if (includeSessionHistory) {
                    AgentEncryptedDatabase(context, TRANSCRIPT_PREFS)
                        .readString(AgentTranscriptStore.KEY_ACTIVE_CONVERSATION, "")
                } else ""
            )
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
                    .put("screen_observation_allowed", safety.screenObservationAllowed)
                    .put("local_actions_allowed", safety.localActionsAllowed)
                    .put("connector_calls_allowed", safety.connectorCallsAllowed)
                    .put("device_control_allowed", safety.deviceControlAllowed)
                    .put("execution_paused", safety.executionPaused)
            )
            .put("custom_device_connectors", customDevices)
            .put("global_super_agent", GlobalAgentRepository(context).exportSnapshot())
            .put("agent_self_model", AgentSelfModelStore(context).exportJson())
            .put(
                "model_planner",
                JSONObject()
                    .put("enabled", modelPlanner.enabled)
                    .put("share_screen_text", modelPlanner.shareScreenText)
                    .put("max_actions", modelPlanner.maxActions)
                    .put("cloud_contact_id", modelPlanner.cloudContactId)
                    .put("dynamic_replanning", modelPlanner.dynamicReplanning)
                    .put("max_replans", modelPlanner.maxReplans)
                    .put("multi_agent_coordination", modelPlanner.multiAgentCoordination)
                    .put("share_agent_outputs_with_planner", modelPlanner.shareAgentOutputsWithPlanner)
                    .put("max_agent_hops", modelPlanner.maxAgentHops)
                    .put("max_tool_calls", modelPlanner.maxToolCalls)
            )
            .put(
                "voice_assistant",
                JSONObject()
                    .put("enabled", voiceAssistant.enabled)
                    .put("wake_words", JSONArray(voiceAssistant.wakeWords))
                    .put("wake_provider", voiceAssistant.wakeProvider)
                    .put("wake_model", voiceAssistant.wakeModel)
                    .put("wake_threshold", voiceAssistant.wakeThreshold.toDouble())
                    .put("asr_provider", voiceAssistant.asrProvider)
                    .put("asr_model", voiceAssistant.asrModel)
                    .put("asr_language", voiceAssistant.asrLanguage)
                    .put("tts_provider", voiceAssistant.ttsProvider)
                    .put("microsoft_voice", voiceAssistant.microsoftVoice)
                    .put("welcome_text", voiceAssistant.welcomeText)
                    .put("target_contact_id", voiceAssistant.targetContactId)
                    .put("speak_replies", voiceAssistant.speakReplies)
                    .put("routing_mode", voiceAssistant.routingMode)
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
            AgentEncryptedDatabase(
                context,
                MEMORY_DATABASE,
                legacyPreferencesName = "signalasi_agent_memory_v2_no_legacy"
            ).writeString(ITEMS_KEY, sanitized.toString())
        }
        payload.optJSONArray("knowledge")?.let { input ->
            val sanitized = sanitizeArray(input, MAX_KNOWLEDGE_ITEMS, MAX_KNOWLEDGE_ITEM_CHARACTERS)
            AgentEncryptedPreferences(context, KNOWLEDGE_PREFS).writeString(ITEMS_KEY, sanitized.toString())
        }
        payload.optJSONArray("tasks")?.let { input ->
            val sanitized = sanitizeArray(input, MAX_TASK_ITEMS, MAX_TASK_ITEM_CHARACTERS)
            AgentEncryptedPreferences(context, TASK_PREFS).writeString(ITEMS_KEY, sanitized.toString())
        }
        payload.optJSONArray("transcript")?.let { input ->
            AgentTranscriptStore(context).restoreEntriesJson(copyObjectArray(input))
        }
        payload.optJSONArray("agent_conversations")?.let { input ->
            AgentEncryptedDatabase(context, TRANSCRIPT_PREFS)
                .writeString(AgentTranscriptStore.KEY_CONVERSATIONS, copyObjectArray(input).toString())
        }
        payload.optString("active_agent_conversation").takeIf { it.isNotBlank() }?.let { activeId ->
            AgentEncryptedDatabase(context, TRANSCRIPT_PREFS)
                .writeString(AgentTranscriptStore.KEY_ACTIVE_CONVERSATION, activeId.take(120))
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
                    memoryCapture = json.optBoolean("memory_capture", true),
                    screenObservationAllowed = json.optBoolean("screen_observation_allowed", true),
                    localActionsAllowed = json.optBoolean("local_actions_allowed", true),
                    connectorCallsAllowed = json.optBoolean("connector_calls_allowed", true),
                    deviceControlAllowed = json.optBoolean("device_control_allowed", true),
                    executionPaused = json.optBoolean("execution_paused", false)
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
        payload.optJSONArray("custom_device_connectors")?.let { array ->
            CustomDeviceConnectorStore(context).restoreJson(array)
        }
        payload.optJSONObject("global_super_agent")?.let { snapshot ->
            GlobalAgentRepository(context).restoreSnapshot(snapshot)
        }
        payload.optJSONObject("agent_self_model")?.let { snapshot ->
            AgentSelfModelStore(context).restoreJson(snapshot)
        }
        payload.optJSONObject("model_planner")?.let { json ->
            AgentModelPlannerSettingsStore(context).save(
                AgentModelPlannerSettings(
                    enabled = json.optBoolean("enabled", false),
                    shareScreenText = json.optBoolean("share_screen_text", false),
                    maxActions = json.optInt("max_actions", 8).coerceIn(1, 12),
                    cloudContactId = json.optString("cloud_contact_id").take(120),
                    dynamicReplanning = json.optBoolean("dynamic_replanning", true),
                    maxReplans = json.optInt("max_replans", 3).coerceIn(1, 5),
                    multiAgentCoordination = json.optBoolean("multi_agent_coordination", true),
                    shareAgentOutputsWithPlanner = json.optBoolean("share_agent_outputs_with_planner", false),
                    maxAgentHops = json.optInt("max_agent_hops", 4).coerceIn(1, 8),
                    maxToolCalls = json.optInt("max_tool_calls", 16).coerceIn(4, 32)
                )
            )
        }
        payload.optJSONObject("voice_assistant")?.let { json ->
            VoiceAssistantSettings.setEnabled(context, json.optBoolean("enabled", true))
            VoiceAssistantSettings.setWakeWords(
                context,
                decodeStringList(json.optJSONArray("wake_words")).joinToString(",")
            )
            VoiceAssistantSettings.setWakeProvider(context, json.optString("wake_provider"))
            VoiceAssistantSettings.setWakeModel(context, json.optString("wake_model"))
            VoiceAssistantSettings.setWakeThreshold(context, json.optDouble("wake_threshold", 0.5).toFloat())
            VoiceAssistantSettings.setAsrProvider(context, json.optString("asr_provider"))
            VoiceAssistantSettings.setAsrModel(context, json.optString("asr_model", "tiny"))
            VoiceAssistantSettings.setAsrLanguage(context, json.optString("asr_language"))
            VoiceAssistantSettings.setTtsProvider(context, json.optString("tts_provider"))
            VoiceAssistantSettings.setMicrosoftVoice(context, json.optString("microsoft_voice"))
            VoiceAssistantSettings.setWelcomeText(context, json.optString("welcome_text"))
            VoiceAssistantSettings.setTargetContact(context, json.optString("target_contact_id"))
            VoiceAssistantSettings.setSpeakReplies(context, json.optBoolean("speak_replies", true))
            VoiceAssistantSettings.setRoutingMode(context, json.optString("routing_mode"))
        }
        GlobalConversationEventBus.requestProcessing(context)
    }

    private fun decodeStringList(array: JSONArray?): List<String> {
        if (array == null) return emptyList()
        return buildList {
            for (index in 0 until array.length()) {
                array.optString(index).takeIf { it.isNotBlank() }?.let(::add)
            }
        }
    }

    private fun readArray(context: Context, preferencesName: String, maxItems: Int, maxItemCharacters: Int): JSONArray {
        val raw = AgentEncryptedPreferences(context, preferencesName).readString(ITEMS_KEY, "[]")
        val array = runCatching { JSONArray(raw) }.getOrDefault(JSONArray())
        return sanitizeArray(array, maxItems, maxItemCharacters)
    }

    private fun readDatabaseArray(
        context: Context,
        databaseName: String,
        maxItems: Int,
        maxItemCharacters: Int
    ): JSONArray {
        val raw = AgentEncryptedDatabase(
            context,
            databaseName,
            legacyPreferencesName = "${databaseName}_no_legacy"
        ).readString(ITEMS_KEY, "[]")
        return sanitizeArray(runCatching { JSONArray(raw) }.getOrDefault(JSONArray()), maxItems, maxItemCharacters)
    }

    private fun readAgentConversationArray(context: Context): JSONArray {
        val raw = AgentEncryptedDatabase(context, TRANSCRIPT_PREFS)
            .readString(AgentTranscriptStore.KEY_CONVERSATIONS, "[]")
        return copyObjectArray(runCatching { JSONArray(raw) }.getOrDefault(JSONArray()))
    }

    private fun readAgentTranscriptArray(context: Context): JSONArray =
        AgentTranscriptStore(context).exportEntriesJson()

    private fun copyObjectArray(input: JSONArray): JSONArray {
        val output = JSONArray()
        for (index in 0 until input.length()) {
            input.optJSONObject(index)?.let(output::put)
        }
        return output
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
