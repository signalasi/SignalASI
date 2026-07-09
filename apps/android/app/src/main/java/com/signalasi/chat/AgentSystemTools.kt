package com.signalasi.chat

import android.provider.Settings

object AgentSystemToolPlanner {
    fun availableTools(): List<AgentSystemTool> = SYSTEM_TOOLS

    fun actionFor(request: AgentRequest): AgentAction? {
        val goal = request.goal.trim()
        val lower = goal.lowercase()
        return when {
            lower.contains("open wifi settings") || lower == "wifi settings" -> intentAction(
                id = "open-wifi-settings",
                target = "Wi-Fi Settings",
                description = "Open Wi-Fi settings",
                intentAction = Settings.ACTION_WIFI_SETTINGS
            )
            lower.contains("open bluetooth settings") || lower == "bluetooth settings" -> intentAction(
                id = "open-bluetooth-settings",
                target = "Bluetooth Settings",
                description = "Open Bluetooth settings",
                intentAction = Settings.ACTION_BLUETOOTH_SETTINGS
            )
            lower.contains("open accessibility settings") || lower.contains("screen agent permission") -> intentAction(
                id = "open-accessibility-settings",
                target = "Accessibility Settings",
                description = "Open accessibility settings",
                intentAction = Settings.ACTION_ACCESSIBILITY_SETTINGS
            )
            lower.contains("open notification settings") || lower == "notification settings" -> intentAction(
                id = "open-notification-settings",
                target = "Notification Settings",
                description = "Open notification settings",
                intentAction = "android.settings.NOTIFICATION_SETTINGS"
            )
            lower.contains("open settings") || lower == "settings" -> intentAction(
                id = "open-settings",
                target = "Android Settings",
                description = "Open Android Settings",
                intentAction = Settings.ACTION_SETTINGS
            )
            lower == "home" || lower.contains("go home") || lower.contains("home screen") -> AgentAction(
                id = "go-home",
                kind = AgentActionKind.HOME,
                target = "Home Screen",
                risk = AgentRisk.LOW,
                status = AgentActionStatus.PENDING_CONFIRMATION,
                description = "Go to the home screen"
            )
            lower.contains("recent apps") || lower == "recents" || lower.contains("show recents") -> AgentAction(
                id = "show-recents",
                kind = AgentActionKind.RECENTS,
                target = "Recent Apps",
                risk = AgentRisk.LOW,
                status = AgentActionStatus.PENDING_CONFIRMATION,
                description = "Show recent apps"
            )
            lower.contains("copy screen text") || lower.contains("copy current screen") -> AgentAction(
                id = "copy-screen-text",
                kind = AgentActionKind.COPY_SCREEN_TEXT,
                target = request.screen.foregroundApp,
                risk = AgentRisk.LOW,
                status = AgentActionStatus.PENDING_CONFIRMATION,
                description = "Copy current screen text"
            )
            lower.startsWith("open url ") || lower.startsWith("open website ") -> {
                val url = if (lower.startsWith("open url ")) {
                    goal.substring("open url ".length).trim()
                } else {
                    goal.substring("open website ".length).trim()
                }
                AgentAction(
                    id = "open-url",
                    kind = AgentActionKind.OPEN_URL,
                    target = normalizeUrl(url),
                    risk = AgentRisk.MEDIUM,
                    status = AgentActionStatus.PENDING_CONFIRMATION,
                    description = "Open URL",
                    parameters = mapOf("url" to normalizeUrl(url))
                )
            }
            lower.contains("set alarm") || lower.contains("open alarms") || lower.contains("open alarm") -> alarmAction(goal, lower)
            lower.contains("long press first") || lower.contains("press and hold first") -> {
                val firstElement = request.screen.clickableElements.firstOrNull()
                AgentAction(
                    id = "long-press-first-action",
                    kind = AgentActionKind.LONG_PRESS,
                    target = firstElement?.label?.ifBlank { request.screen.foregroundApp } ?: request.screen.foregroundApp,
                    risk = AgentRisk.MEDIUM,
                    status = AgentActionStatus.PENDING_CONFIRMATION,
                    description = "Long press the first clickable element",
                    parameters = mapOf("bounds" to firstElement?.bounds.orEmpty())
                )
            }
            else -> null
        }
    }

    private fun intentAction(
        id: String,
        target: String,
        description: String,
        intentAction: String
    ): AgentAction = AgentAction(
        id = id,
        kind = AgentActionKind.OPEN_APP,
        target = target,
        risk = AgentRisk.LOW,
        status = AgentActionStatus.PENDING_CONFIRMATION,
        description = description,
        parameters = mapOf("intent_action" to intentAction)
    )

    private fun alarmAction(goal: String, lower: String): AgentAction {
        val match = Regex("(\\d{1,2}):(\\d{2})").find(lower)
        val hour = match?.groupValues?.getOrNull(1)?.toIntOrNull()
        val minute = match?.groupValues?.getOrNull(2)?.toIntOrNull()
        val parameters = buildMap {
            put("label", goal)
            if (hour != null && minute != null && hour in 0..23 && minute in 0..59) {
                put("hour", hour.toString())
                put("minute", minute.toString())
            }
        }
        return AgentAction(
            id = "set-alarm",
            kind = AgentActionKind.SET_ALARM,
            target = if ("open" in lower && hour == null) "Alarm App" else "Android Alarm",
            risk = AgentRisk.MEDIUM,
            status = AgentActionStatus.PENDING_CONFIRMATION,
            description = if (hour == null || minute == null) "Open alarm app" else "Set alarm for %02d:%02d".format(hour, minute),
            parameters = parameters
        )
    }

    private fun normalizeUrl(value: String): String {
        if (value.isBlank()) return ""
        return if (value.startsWith("http://") || value.startsWith("https://")) value else "https://$value"
    }

    private val SYSTEM_TOOLS = listOf(
        AgentSystemTool(
            id = "screen-copy",
            title = "Copy Screen Text",
            kind = AgentActionKind.COPY_SCREEN_TEXT,
            risk = AgentRisk.LOW,
            capabilities = listOf(AgentCapability.SCREEN_READING, AgentCapability.CLIPBOARD),
            examples = listOf("copy screen text", "copy current screen")
        ),
        AgentSystemTool(
            id = "system-settings",
            title = "System Settings",
            kind = AgentActionKind.OPEN_APP,
            risk = AgentRisk.LOW,
            capabilities = listOf(AgentCapability.SYSTEM_SETTINGS, AgentCapability.APP_NAVIGATION),
            examples = listOf("open settings", "open wifi settings", "open bluetooth settings")
        ),
        AgentSystemTool(
            id = "navigation",
            title = "System Navigation",
            kind = AgentActionKind.HOME,
            risk = AgentRisk.LOW,
            capabilities = listOf(AgentCapability.APP_NAVIGATION, AgentCapability.DEVICE_CONTROL),
            examples = listOf("go home", "show recents", "go back")
        ),
        AgentSystemTool(
            id = "open-url",
            title = "Open URL",
            kind = AgentActionKind.OPEN_URL,
            risk = AgentRisk.MEDIUM,
            capabilities = listOf(AgentCapability.APP_NAVIGATION, AgentCapability.TASK_EXECUTION),
            examples = listOf("open url https://example.com")
        ),
        AgentSystemTool(
            id = "alarm",
            title = "Alarm Handoff",
            kind = AgentActionKind.SET_ALARM,
            risk = AgentRisk.MEDIUM,
            capabilities = listOf(AgentCapability.SYSTEM_SETTINGS, AgentCapability.TASK_EXECUTION),
            examples = listOf("set alarm 07:30", "open alarms")
        ),
        AgentSystemTool(
            id = "gesture",
            title = "Screen Gesture",
            kind = AgentActionKind.TAP,
            risk = AgentRisk.MEDIUM,
            capabilities = listOf(AgentCapability.DEVICE_CONTROL, AgentCapability.TASK_EXECUTION),
            examples = listOf("tap first", "swipe up", "long press first")
        )
    )
}
