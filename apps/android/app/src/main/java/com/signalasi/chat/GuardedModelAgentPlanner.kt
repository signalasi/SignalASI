package com.signalasi.chat

import android.content.Context
import org.json.JSONObject

class GuardedModelAgentPlanner(
    context: Context,
    private val fallback: AgentPlanner = RuleBasedAgentPlanner(context),
    private val settingsStore: AgentModelPlannerSettingsStore = AgentModelPlannerSettingsStore(context),
    private val safetySettingsStore: AgentSafetySettingsStore = SharedPreferencesAgentSafetySettingsStore(context)
) : AgentPlanner {
    private val appContext = context.applicationContext

    override fun plan(request: AgentRequest): AgentPlan {
        val settings = settingsStore.load()
        val fallbackPlan = fallback.plan(request)
        if (!settings.enabled || !safetySettingsStore.load().connectorCallsAllowed) {
            return fallbackPlan.copy(plannerProfile = "rule-based-local")
        }
        if (request.screen.hasSensitivePlannerContext() || request.goal.hasSensitivePlannerGoal()) {
            return fallbackPlan.copy(
                plannerProfile = "rule-based-sensitive-fallback",
                routeRationale = "Model planning skipped because the current screen context is sensitive."
            )
        }
        val contact = resolveCloudPlannerContact(settings.cloudContactId)
            ?: return fallbackPlan.copy(plannerProfile = "rule-based-model-unavailable")
        val raw = runCatching {
            CloudModelClient.sendStructured(
                appContext,
                contact,
                MODEL_PLANNER_SYSTEM_PROMPT,
                AgentModelPlanningPrompt.build(request, settings)
            )
        }.getOrElse {
            return fallbackPlan.copy(
                plannerProfile = "rule-based-model-error",
                routeRationale = "Model planning failed; the deterministic local planner was used."
            )
        }
        return AgentModelPlanParser.parse(request, raw, settings.maxActions)
            ?.let(::enforceRegisteredDeviceRisk)
            ?.copy(
                plannerProfile = "guarded-model:${contact.optString("cloud_model").take(80)}",
                routeRationale = "A configured model proposed this plan; all actions were resolved and validated locally."
            )
            ?: fallbackPlan.copy(
                plannerProfile = "rule-based-invalid-model-plan",
                routeRationale = "Model output failed local ActionPlan validation; deterministic fallback used."
            )
    }

    private fun enforceRegisteredDeviceRisk(plan: AgentPlan): AgentPlan {
        val store = CustomDeviceConnectorStore(appContext)
        val actions = plan.actions.map { action ->
            if (action.kind != AgentActionKind.CONTROL_DEVICE) return@map action
            val connectorId = action.parameters["connector_id"].orEmpty()
            val risk = when {
                connectorId.startsWith("custom-device:") ->
                    store.find(connectorId.removePrefix("custom-device:"))?.risk ?: AgentRisk.HIGH
                connectorId == "home-assistant" ->
                    HomeAssistantDeviceClient.riskForPrompt(appContext, action.parameters["prompt"].orEmpty())
                else -> AgentRisk.HIGH
            }
            action.copy(risk = risk)
        }
        val hardened = plan.copy(actions = actions)
        return hardened.copy(validation = AgentPlanValidator.validate(hardened))
    }

    private fun resolveCloudPlannerContact(preferredId: String): JSONObject? {
        val contacts = AppStore.contacts(appContext)
        val candidates = mutableListOf<Pair<String, JSONObject>>()
        for (index in 0 until contacts.length()) {
            val contact = contacts.optJSONObject(index) ?: continue
            if (contact.optBoolean("deleted", false)) continue
            if (contact.optString("delivery_mode") != "cloud_api") continue
            if (contact.optString("setup_status").ifBlank { "ready" } != "ready") continue
            val id = contact.optString("id").ifBlank { contact.optString("signalasi_id") }
            val selected = AppStore.selectedCloudModelContact(appContext, id) ?: contact
            if (selected.optString("cloud_model").isBlank()) continue
            if (selected.optString("cloud_endpoint").isBlank()) continue
            if (selected.optString("cloud_api_key").isBlank()) continue
            candidates += id to selected
        }
        return candidates.firstOrNull { it.first == preferredId }?.second ?: candidates.firstOrNull()?.second
    }

    private companion object {
        const val MODEL_PLANNER_SYSTEM_PROMPT =
            "You are a constrained Android task planner. Return exactly one JSON object matching the supplied schema. " +
                "Do not use markdown, prose, hidden steps, arbitrary coordinates, unlisted apps, or unlisted connectors."
    }
}

private object AgentModelPlanningPrompt {
    fun build(request: AgentRequest, settings: AgentModelPlannerSettings): String = buildString {
        append("Create an executable ActionPlan for the user goal. The phone validates every field locally.\n\n")
        append("JSON schema:\n")
        append("{\"summary\":\"...\",\"expected_result\":\"...\",\"rollback_strategy\":\"...\",")
        append("\"actions\":[{\"kind\":\"ACTION_KIND\",\"target\":\"...\",\"description\":\"...\",")
        append("\"parameters\":{\"key\":\"value\"}}]}\n\n")
        append("Allowed kinds: ").append(AgentModelPlanParser.allowedKinds.joinToString(", ") { it.name }).append(".\n")
        append("TAP/LONG_PRESS require element_query. TYPE_TEXT requires field_query and text. ")
        append("DELETE_TEXT/PASTE_TEXT require field_query. SWIPE requires direction up/down/left/right. ")
        append("OPEN_APP requires an exact package from inventory. OPEN_URL requires an http/https URL. ")
        append("CALL_CONNECTOR/CONTROL_DEVICE require an exact connector_id from inventory. ")
        append("Never create more than ").append(settings.maxActions.coerceIn(1, 12)).append(" actions.\n\n")
        append("User goal: ").append(request.goal.take(2_000)).append("\n")
        if (request.replanReason.isNotBlank()) {
            append("Replan reason: ").append(request.replanReason.take(500)).append("\n")
            append("Continue from the current state. Do not repeat completed actions unless the screen proves they were undone.\n")
        }
        if (request.executionHistory.isNotEmpty()) {
            append("Execution history:\n")
            request.executionHistory.takeLast(30).forEach { action ->
                append("- ").append(action.kind.name)
                    .append(" | ").append(action.status.name)
                    .append(" | ").append(action.description.take(180))
                    .append("\n")
            }
        }
        append("Current app: ").append(request.screen.foregroundApp.take(160)).append("\n")
        append("Current page: ").append(request.screen.pageTitle.take(160)).append("\n")
        append("Screen counts: text=").append(request.screen.visibleTextCount)
            .append(", actions=").append(request.screen.clickableNodeCount)
            .append(", fields=").append(request.screen.inputFieldCount).append("\n")
        if (settings.shareScreenText) appendScreenInventory(request.screen)
        append("Installed apps:\n")
        request.screen.installedApps.take(80).forEach {
            append("- ").append(it.label.take(100)).append(" | ").append(it.packageName.take(160)).append("\n")
        }
        append("Callable connectors:\n")
        request.targets.filter { it.status == AgentConnectorStatus.AVAILABLE }.take(40).forEach {
            append("- ").append(it.id).append(" | ").append(it.title.take(100))
                .append(" | ").append(it.kind.name).append("\n")
        }
    }.take(MAX_PROMPT_CHARACTERS)

    private fun StringBuilder.appendScreenInventory(screen: ScreenContext) {
        append("Visible text:\n")
        screen.visibleTexts.take(40).forEach { append("- ").append(it.take(240)).append("\n") }
        append("Clickable elements:\n")
        screen.clickableElements.take(40).forEach {
            append("- ").append(it.label.ifBlank { it.viewId }.take(160)).append("\n")
        }
        append("Input fields:\n")
        screen.inputFields.take(20).forEach {
            append("- ").append(it.label.ifBlank { it.viewId }.take(160)).append("\n")
        }
    }

    private const val MAX_PROMPT_CHARACTERS = 24_000
}

private fun ScreenContext.hasSensitivePlannerContext(): Boolean =
    sensitiveFlagCount > 0 || sensitiveFlags.isNotEmpty() ||
        clipboard.sensitiveFlags.isNotEmpty() || notifications.sensitiveFlags.isNotEmpty()

private fun String.hasSensitivePlannerGoal(): Boolean {
    val value = lowercase()
    return listOf(
        "password", "passcode", "verification code", "otp", "2fa", "api key", "secret key",
        "private key", "seed phrase", "bank card", "credit card", "cvv",
        "\u5bc6\u7801", "\u9a8c\u8bc1\u7801", "\u79c1\u94a5", "\u94f6\u884c\u5361", "\u652f\u4ed8"
    ).any(value::contains)
}
