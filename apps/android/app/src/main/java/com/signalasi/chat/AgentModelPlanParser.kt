package com.signalasi.chat

import org.json.JSONObject
import java.net.URI
import java.util.Locale

object AgentModelPlanParser {
    val allowedKinds = setOf(
        AgentActionKind.READ_SCREEN,
        AgentActionKind.SAVE_SCREEN_KNOWLEDGE,
        AgentActionKind.DRAFT_PLAN,
        AgentActionKind.TAP,
        AgentActionKind.TYPE_TEXT,
        AgentActionKind.SWIPE,
        AgentActionKind.LONG_PRESS,
        AgentActionKind.BACK,
        AgentActionKind.HOME,
        AgentActionKind.RECENTS,
        AgentActionKind.LOCK_SCREEN,
        AgentActionKind.OPEN_APP,
        AgentActionKind.OPEN_URL,
        AgentActionKind.SET_ALARM,
        AgentActionKind.CREATE_NOTIFICATION,
        AgentActionKind.COPY_SCREEN_TEXT,
        AgentActionKind.DELETE_TEXT,
        AgentActionKind.PASTE_TEXT,
        AgentActionKind.IMPORT_WEB_KNOWLEDGE,
        AgentActionKind.CALL_CONNECTOR,
        AgentActionKind.CONTROL_DEVICE
    )

    fun parse(request: AgentRequest, raw: String, maxActions: Int): AgentPlan? {
        val json = extractJson(raw) ?: return null
        val input = json.optJSONArray("actions") ?: return null
        if (input.length() !in 1..maxActions.coerceIn(1, MAX_ACTIONS)) return null
        val actions = mutableListOf<AgentAction>()
        for (index in 0 until input.length()) {
            val action = parseAction(request, input.optJSONObject(index), index) ?: return null
            actions += action
        }
        val plan = AgentPlanFactory.actions(request, actions).copy(
            expectedResult = json.optString("expected_result").trim().take(500)
                .ifBlank { actions.last().description },
            rollbackStrategy = json.optString("rollback_strategy").trim().take(500)
                .ifBlank { "Stop execution and restore the last safe checkpoint." }
        )
        return plan.takeIf { AgentPlanValidator.validate(it).valid }
    }

    private fun parseAction(request: AgentRequest, json: JSONObject?, index: Int): AgentAction? {
        json ?: return null
        val kind = runCatching {
            AgentActionKind.valueOf(json.optString("kind").trim().uppercase(Locale.US))
        }.getOrNull()?.takeIf { it in allowedKinds } ?: return null
        val parameters = resolveParameters(kind, json.optJSONObject("parameters") ?: JSONObject(), request)
            ?: return null
        val target = resolveTarget(kind, json.optString("target"), parameters, request)
        val description = json.optString("description").trim().take(300)
            .ifBlank { defaultDescription(kind, target) }
        return AgentAction(
            id = "model-${index + 1}-${kind.name.lowercase(Locale.US)}",
            kind = kind,
            target = target,
            risk = localRisk(kind, parameters, target),
            status = AgentActionStatus.PENDING_CONFIRMATION,
            description = description,
            parameters = parameters,
            requiresConfirmation = true
        )
    }

    private fun resolveParameters(
        kind: AgentActionKind,
        input: JSONObject,
        request: AgentRequest
    ): Map<String, String>? = when (kind) {
        AgentActionKind.TAP,
        AgentActionKind.LONG_PRESS -> resolveElement(
            input.optString("element_query"),
            request.screen.clickableElements
        )?.let { mapOf("bounds" to it.bounds, "matched_label" to it.safeLabel()) }

        AgentActionKind.TYPE_TEXT -> {
            val text = input.optString("text").take(MAX_TEXT_INPUT_CHARACTERS)
            val field = resolveInputField(input.optString("field_query"), request.screen)
            if (text.isBlank() || field == null || field.isSensitiveInput()) null else mapOf(
                "text" to text,
                "field_bounds" to field.bounds,
                "matched_label" to field.safeLabel()
            )
        }

        AgentActionKind.DELETE_TEXT,
        AgentActionKind.PASTE_TEXT -> {
            val field = resolveInputField(input.optString("field_query"), request.screen)
            if (field == null || field.isSensitiveInput()) null else mapOf(
                "field_bounds" to field.bounds,
                "matched_label" to field.safeLabel()
            )
        }

        AgentActionKind.SWIPE -> swipeParameters(input.optString("direction"))

        AgentActionKind.OPEN_APP -> {
            val packageName = input.optString("package").trim()
            request.screen.installedApps.firstOrNull { it.packageName == packageName }
                ?.let { mapOf("package" to it.packageName) }
        }

        AgentActionKind.OPEN_URL,
        AgentActionKind.IMPORT_WEB_KNOWLEDGE -> safeHttpUrl(input.optString("url"))
            ?.let { mapOf("url" to it) }

        AgentActionKind.SET_ALARM -> {
            val hour = input.optInt("hour", -1)
            val minute = input.optInt("minute", -1)
            if (hour !in 0..23 || minute !in 0..59) null else mapOf(
                "hour" to hour.toString(),
                "minute" to minute.toString(),
                "message" to input.optString("message").take(200)
            )
        }

        AgentActionKind.CREATE_NOTIFICATION -> input.optString("text").take(1_000)
            .takeIf { it.isNotBlank() }
            ?.let {
                mapOf(
                    "title" to input.optString("title").take(160).ifBlank { "SignalASI Agent" },
                    "text" to it
                )
            }

        AgentActionKind.CALL_CONNECTOR,
        AgentActionKind.CONTROL_DEVICE -> resolveConnector(kind, input, request)

        AgentActionKind.READ_SCREEN,
        AgentActionKind.SAVE_SCREEN_KNOWLEDGE,
        AgentActionKind.DRAFT_PLAN,
        AgentActionKind.BACK,
        AgentActionKind.HOME,
        AgentActionKind.RECENTS,
        AgentActionKind.LOCK_SCREEN,
        AgentActionKind.COPY_SCREEN_TEXT -> emptyMap()

        AgentActionKind.REPLY_NOTIFICATION -> null
    }

    private fun resolveConnector(
        kind: AgentActionKind,
        input: JSONObject,
        request: AgentRequest
    ): Map<String, String>? {
        val connectorId = input.optString("connector_id").trim()
        val target = request.targets.firstOrNull {
            it.id == connectorId &&
                it.status == AgentConnectorStatus.AVAILABLE &&
                (kind != AgentActionKind.CONTROL_DEVICE || it.kind == AgentConnectorKind.DEVICE)
        } ?: return null
        return mapOf(
            "connector_id" to target.id,
            "prompt" to input.optString("prompt").take(MAX_CONNECTOR_PROMPT_CHARACTERS)
                .ifBlank { request.goal.take(MAX_CONNECTOR_PROMPT_CHARACTERS) },
            "custom_device_id" to target.id.removePrefix("custom-device:")
                .takeIf { target.id.startsWith("custom-device:") }.orEmpty()
        )
    }

    private fun resolveInputField(query: String, screen: ScreenContext): ScreenElement? =
        if (query.isBlank()) screen.focusedInputField else resolveElement(query, screen.inputFields)

    private fun resolveElement(query: String, elements: List<ScreenElement>): ScreenElement? {
        val clean = query.normalizedQuery()
        if (clean.isBlank()) return null
        return elements.firstOrNull { element ->
            element.label.normalizedQuery().contains(clean) ||
                element.viewId.normalizedQuery().contains(clean) ||
                element.className.normalizedQuery().contains(clean)
        }
    }

    private fun swipeParameters(direction: String): Map<String, String>? = when (direction.lowercase(Locale.US)) {
        "up" -> coordinates(540, 1700, 540, 700)
        "down" -> coordinates(540, 700, 540, 1700)
        "left" -> coordinates(900, 1100, 180, 1100)
        "right" -> coordinates(180, 1100, 900, 1100)
        else -> null
    }

    private fun coordinates(fromX: Int, fromY: Int, toX: Int, toY: Int): Map<String, String> = mapOf(
        "from_x" to fromX.toString(),
        "from_y" to fromY.toString(),
        "to_x" to toX.toString(),
        "to_y" to toY.toString()
    )

    private fun safeHttpUrl(value: String): String? = runCatching {
        val clean = value.trim()
        val uri = URI(clean)
        clean.takeIf { uri.scheme in setOf("http", "https") && !uri.host.isNullOrBlank() }
    }.getOrNull()

    private fun resolveTarget(
        kind: AgentActionKind,
        proposed: String,
        parameters: Map<String, String>,
        request: AgentRequest
    ): String = when (kind) {
        AgentActionKind.OPEN_APP -> request.screen.installedApps
            .firstOrNull { it.packageName == parameters["package"] }?.label.orEmpty()
        AgentActionKind.CALL_CONNECTOR,
        AgentActionKind.CONTROL_DEVICE -> request.targets
            .firstOrNull { it.id == parameters["connector_id"] }?.title.orEmpty()
        else -> proposed.trim().take(200).ifBlank { request.screen.foregroundApp }
    }

    private fun localRisk(
        kind: AgentActionKind,
        parameters: Map<String, String>,
        target: String
    ): AgentRisk = when (kind) {
        AgentActionKind.READ_SCREEN,
        AgentActionKind.DRAFT_PLAN,
        AgentActionKind.SWIPE,
        AgentActionKind.BACK,
        AgentActionKind.HOME,
        AgentActionKind.RECENTS,
        AgentActionKind.OPEN_APP,
        AgentActionKind.COPY_SCREEN_TEXT -> AgentRisk.LOW

        AgentActionKind.SAVE_SCREEN_KNOWLEDGE,
        AgentActionKind.TAP,
        AgentActionKind.TYPE_TEXT,
        AgentActionKind.LONG_PRESS,
        AgentActionKind.OPEN_URL,
        AgentActionKind.SET_ALARM,
        AgentActionKind.CREATE_NOTIFICATION,
        AgentActionKind.DELETE_TEXT,
        AgentActionKind.PASTE_TEXT,
        AgentActionKind.IMPORT_WEB_KNOWLEDGE,
        AgentActionKind.CALL_CONNECTOR -> AgentRisk.MEDIUM

        AgentActionKind.LOCK_SCREEN,
        AgentActionKind.CONTROL_DEVICE -> if (isHighRiskTarget(target, parameters)) AgentRisk.HIGH else AgentRisk.MEDIUM
        AgentActionKind.REPLY_NOTIFICATION -> AgentRisk.HIGH
    }

    private fun isHighRiskTarget(target: String, parameters: Map<String, String>): Boolean =
        HIGH_RISK_TERMS.any { term ->
            target.contains(term, ignoreCase = true) ||
                parameters.values.any { it.contains(term, ignoreCase = true) }
        }

    private fun defaultDescription(kind: AgentActionKind, target: String): String =
        kind.name.lowercase(Locale.US).replace('_', ' ') +
            target.takeIf { it.isNotBlank() }?.let { " on $it" }.orEmpty()

    private fun extractJson(raw: String): JSONObject? {
        val trimmed = raw.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
        val start = trimmed.indexOf('{')
        val end = trimmed.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        return runCatching { JSONObject(trimmed.substring(start, end + 1)) }.getOrNull()
    }

    private fun ScreenElement.safeLabel(): String = label.ifBlank { viewId.ifBlank { className } }.take(160)

    private fun ScreenElement.isSensitiveInput(): Boolean {
        val value = "$label $viewId $className"
        return SENSITIVE_FIELD_TERMS.any { value.contains(it, ignoreCase = true) }
    }

    private fun String.normalizedQuery(): String =
        lowercase(Locale.US).replace(Regex("[^\\p{L}\\p{N}]+"), "")

    private val SENSITIVE_FIELD_TERMS = listOf("password", "passcode", "pin", "otp", "verification", "cvv")
    private val HIGH_RISK_TERMS = listOf("lock", "door", "garage", "alarm", "camera", "security", "siren", "valve")
    private const val MAX_ACTIONS = 12
    private const val MAX_TEXT_INPUT_CHARACTERS = 2_000
    private const val MAX_CONNECTOR_PROMPT_CHARACTERS = 4_000
}
