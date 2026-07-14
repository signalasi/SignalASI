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
        AgentActionKind.CALL_NATIVE_TOOL,
        AgentActionKind.CALL_CONNECTOR,
        AgentActionKind.CONTROL_DEVICE
    )

    fun parse(request: AgentRequest, raw: String, settings: AgentModelPlannerSettings): AgentPlan? {
        val json = extractJson(raw) ?: return null
        val input = json.optJSONArray("actions") ?: return null
        if (input.length() !in 1..settings.maxActions.coerceIn(1, MAX_ACTIONS)) return null
        val refs = mutableMapOf<String, Pair<Int, String>>()
        for (index in 0 until input.length()) {
            val item = input.optJSONObject(index) ?: return null
            val ref = normalizedRef(item.optString("ref").ifBlank { "step-${index + 1}" }) ?: return null
            if (refs.containsKey(ref)) return null
            refs[ref] = index to "model-${index + 1}-$ref"
        }
        val actions = mutableListOf<AgentAction>()
        for (index in 0 until input.length()) {
            val item = input.optJSONObject(index) ?: return null
            val ref = normalizedRef(item.optString("ref").ifBlank { "step-${index + 1}" }) ?: return null
            val action = parseAction(
                request = request,
                json = item,
                index = index,
                actionId = refs.getValue(ref).second,
                refs = refs,
                allowCoordination = settings.multiAgentCoordination
            ) ?: return null
            actions += action
        }
        val plan = AgentPlanFactory.actions(request, actions).copy(
            expectedResult = json.optString("expected_result").trim().take(500)
                .ifBlank { actions.last().description },
            rollbackStrategy = json.optString("rollback_strategy").trim().take(500)
                .ifBlank { "Stop execution and restore the last safe checkpoint." }
        )
        return plan.takeIf {
            AgentPlanValidator.validate(it).valid && it.toolGraphDepth() <= settings.maxAgentHops
        }
    }

    private fun parseAction(
        request: AgentRequest,
        json: JSONObject,
        index: Int,
        actionId: String,
        refs: Map<String, Pair<Int, String>>,
        allowCoordination: Boolean
    ): AgentAction? {
        val kind = runCatching {
            AgentActionKind.valueOf(json.optString("kind").trim().uppercase(Locale.US))
        }.getOrNull()?.takeIf { it in allowedKinds } ?: return null
        val dependencyRefs = json.optJSONArray("depends_on").stringValues()
        val outputRefs = json.optJSONArray("use_outputs_from").stringValues()
        if (!allowCoordination && (dependencyRefs.isNotEmpty() || outputRefs.isNotEmpty())) return null
        if (outputRefs.isNotEmpty() && kind != AgentActionKind.CALL_CONNECTOR) return null
        if (outputRefs.any { it !in dependencyRefs }) return null
        val dependencyIds = resolvePriorRefs(dependencyRefs, refs, index) ?: return null
        val outputSourceIds = resolvePriorRefs(outputRefs, refs, index) ?: return null
        val nodeRef = refs.entries.firstOrNull { it.value.second == actionId }?.key ?: return null
        val resolved = resolveParameters(kind, json.optJSONObject("parameters") ?: JSONObject(), request)
            ?: return null
        val parameters = resolved + mapOf(
            "node_ref" to nodeRef,
            "depends_on" to dependencyIds.joinToString(","),
            "use_outputs_from" to outputSourceIds.joinToString(",")
        )
        val target = resolveTarget(kind, json.optString("target"), parameters, request)
        val description = json.optString("description").trim().take(300)
            .ifBlank { defaultDescription(kind, target) }
        return AgentAction(
            id = actionId,
            kind = kind,
            target = target,
            risk = localRisk(kind, parameters, target),
            status = AgentActionStatus.PENDING_CONFIRMATION,
            description = description,
            parameters = parameters,
            requiresConfirmation = true
        )
    }

    private fun resolvePriorRefs(
        values: List<String>,
        refs: Map<String, Pair<Int, String>>,
        currentIndex: Int
    ): List<String>? = values.map { value ->
        val ref = normalizedRef(value) ?: return null
        val resolved = refs[ref] ?: return null
        if (resolved.first >= currentIndex) return null
        resolved.second
    }.distinct()

    private fun org.json.JSONArray?.stringValues(): List<String> {
        if (this == null) return emptyList()
        return buildList {
            for (index in 0 until length()) {
                optString(index).trim().takeIf { it.isNotBlank() }?.let(::add)
            }
        }
    }

    private fun normalizedRef(value: String): String? = value.trim()
        .lowercase(Locale.US)
        .takeIf { it.matches(Regex("[a-z0-9][a-z0-9_-]{0,47}")) }

    private fun resolveParameters(
        kind: AgentActionKind,
        input: JSONObject,
        request: AgentRequest
    ): Map<String, String>? = when (kind) {
        AgentActionKind.TAP,
        AgentActionKind.LONG_PRESS -> resolveElement(
            input.optString("element_query"),
            request.screen.clickableElements
        )?.let {
            mapOf(
                "bounds" to it.bounds,
                "matched_label" to it.safeLabel(),
                "element_origin" to it.origin.name,
                "element_role" to it.visualRole.name,
                "element_confidence" to it.confidence.toString()
            )
        }

        AgentActionKind.TYPE_TEXT -> {
            val text = input.optString("text").take(MAX_TEXT_INPUT_CHARACTERS)
            val field = resolveInputField(input.optString("field_query"), request.screen)
            if (text.isBlank() || field == null || field.isSensitiveInput()) null else mapOf(
                "text" to text,
                "field_bounds" to field.bounds,
                "matched_label" to field.safeLabel(),
                "field_origin" to field.origin.name,
                "field_confidence" to field.confidence.toString()
            )
        }

        AgentActionKind.DELETE_TEXT,
        AgentActionKind.PASTE_TEXT -> {
            val field = resolveInputField(input.optString("field_query"), request.screen)
            if (field == null || field.isSensitiveInput()) null else mapOf(
                "field_bounds" to field.bounds,
                "matched_label" to field.safeLabel(),
                "field_origin" to field.origin.name,
                "field_confidence" to field.confidence.toString()
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

        AgentActionKind.CALL_NATIVE_TOOL -> resolveNativeTool(input, request)

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

    private fun resolveNativeTool(input: JSONObject, request: AgentRequest): Map<String, String>? {
        val toolId = input.optString("tool_id").trim()
        val descriptor = request.runtimeContext.nativeTools.firstOrNull {
            it.id == toolId && it.availability.status == AgentNativeToolAvailabilityStatus.AVAILABLE
        } ?: return null
        val arguments = input.optJSONObject("arguments") ?: JSONObject()
        val inputJson = arguments.toString()
        if (inputJson.length > MAX_NATIVE_TOOL_ARGUMENT_CHARACTERS) return null
        return mapOf(
            "tool_id" to descriptor.id,
            "tool_version" to descriptor.version,
            "native_tool_risk" to descriptor.risk.wireValue,
            "input_json" to inputJson
        )
    }

    private fun resolveInputField(query: String, screen: ScreenContext): ScreenElement? =
        if (query.isBlank()) screen.focusedInputField else resolveElement(query, screen.inputFields)

    private fun resolveElement(query: String, elements: List<ScreenElement>): ScreenElement? {
        return AgentScreenElementMatcher.resolve(query, elements)
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
        AgentActionKind.CALL_NATIVE_TOOL -> request.runtimeContext.nativeTools
            .firstOrNull { it.id == parameters["tool_id"] }?.title.orEmpty()
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

        AgentActionKind.CALL_NATIVE_TOOL -> when (parameters["native_tool_risk"]) {
            AgentNativeToolRisk.HIGH.wireValue -> AgentRisk.HIGH
            AgentNativeToolRisk.MEDIUM.wireValue -> AgentRisk.MEDIUM
            AgentNativeToolRisk.BLOCKED.wireValue -> AgentRisk.BLOCKED
            else -> AgentRisk.LOW
        }

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
    private const val MAX_NATIVE_TOOL_ARGUMENT_CHARACTERS = 64 * 1_024
}
