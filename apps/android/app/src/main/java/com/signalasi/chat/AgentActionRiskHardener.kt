package com.signalasi.chat

import android.content.Context

object AgentActionRiskHardener {
    fun enforce(context: Context, plan: AgentPlan): AgentPlan {
        val customDevices = CustomDeviceConnectorStore(context.applicationContext)
        val actions = plan.actions.map { action ->
            val hardenedRisk = when (action.kind) {
                AgentActionKind.CONTROL_DEVICE -> deviceRisk(context, customDevices, action)
                AgentActionKind.CALL_CONNECTOR -> connectorRisk(action)
                AgentActionKind.TAP,
                AgentActionKind.LONG_PRESS -> visualGroundingRisk(
                    action.parameters["element_origin"].orEmpty(),
                    action.parameters["element_confidence"].orEmpty()
                )
                AgentActionKind.TYPE_TEXT,
                AgentActionKind.DELETE_TEXT,
                AgentActionKind.PASTE_TEXT -> visualGroundingRisk(
                    action.parameters["field_origin"].orEmpty(),
                    action.parameters["field_confidence"].orEmpty()
                )
                else -> action.risk
            }
            action.copy(risk = higherRisk(action.risk, hardenedRisk))
        }
        val hardened = plan.copy(actions = actions)
        return hardened.copy(validation = AgentPlanValidator.validate(hardened))
    }

    private fun deviceRisk(
        context: Context,
        customDevices: CustomDeviceConnectorStore,
        action: AgentAction
    ): AgentRisk {
        val connectorId = action.parameters["connector_id"].orEmpty()
        return when {
            connectorId.startsWith("custom-device:") ->
                customDevices.find(connectorId.removePrefix("custom-device:"))?.risk ?: AgentRisk.HIGH
            connectorId == "home-assistant" ->
                HomeAssistantDeviceClient.riskForPrompt(context, action.parameters["prompt"].orEmpty())
            else -> AgentRisk.HIGH
        }
    }

    private fun connectorRisk(action: AgentAction): AgentRisk {
        val value = listOf(
            action.description,
            action.target,
            action.parameters["prompt"].orEmpty()
        ).joinToString(" ").lowercase()
        return if (HIGH_RISK_CONNECTOR_TERMS.any(value::contains)) AgentRisk.HIGH else action.risk
    }

    private fun visualGroundingRisk(origin: String, confidenceValue: String): AgentRisk {
        if (origin != AgentElementOrigin.VISUAL_OCR.name) return AgentRisk.LOW
        val confidence = confidenceValue.toFloatOrNull() ?: 0f
        return if (confidence < MIN_CONFIDENT_VISUAL_ACTION) AgentRisk.HIGH else AgentRisk.MEDIUM
    }

    private fun higherRisk(first: AgentRisk, second: AgentRisk): AgentRisk =
        if (first.weight >= second.weight) first else second

    private val HIGH_RISK_CONNECTOR_TERMS = listOf(
        "delete", "erase", "remove account", "install", "uninstall", "deploy", "publish",
        "send message", "send email", "payment", "purchase", "buy", "order", "transfer",
        "credential", "password", "private key", "api key", "unlock", "open door",
        "\u5220\u9664", "\u6e05\u9664", "\u5b89\u88c5", "\u5378\u8f7d", "\u90e8\u7f72",
        "\u53d1\u5e03", "\u53d1\u9001", "\u652f\u4ed8", "\u8d2d\u4e70", "\u8f6c\u8d26",
        "\u5bc6\u7801", "\u79c1\u94a5", "\u89e3\u9501", "\u5f00\u95e8"
    )
    private const val MIN_CONFIDENT_VISUAL_ACTION = 0.70f
}
