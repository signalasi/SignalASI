package com.signalasi.chat

import android.content.Intent
import android.net.Uri
import android.provider.CalendarContract
import android.provider.MediaStore
import android.provider.Settings

object AgentSystemToolPlanner {
    fun availableTools(): List<AgentSystemTool> = SYSTEM_TOOLS

    fun actionFor(request: AgentRequest): AgentAction? {
        val goal = request.goal.trim()
        val lower = goal.lowercase()
        commonToolAction(lower)?.let { return it }
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
            lower.contains("open battery settings") || lower == "battery settings" -> intentAction(
                id = "open-battery-settings",
                target = "Battery Settings",
                description = "Open battery settings",
                intentAction = Settings.ACTION_BATTERY_SAVER_SETTINGS
            )
            lower.contains("open display settings") || lower == "display settings" -> intentAction(
                id = "open-display-settings",
                target = "Display Settings",
                description = "Open display settings",
                intentAction = Settings.ACTION_DISPLAY_SETTINGS
            )
            lower.contains("open location settings") || lower == "location settings" -> intentAction(
                id = "open-location-settings",
                target = "Location Settings",
                description = "Open location settings",
                intentAction = Settings.ACTION_LOCATION_SOURCE_SETTINGS
            )
            lower.contains("open app settings") || lower == "app settings" -> intentAction(
                id = "open-app-settings",
                target = "App Settings",
                description = "Open app settings",
                intentAction = Settings.ACTION_APPLICATION_SETTINGS
            )
            lower.contains("open keyboard settings") || lower.contains("open input settings") -> intentAction(
                id = "open-input-settings",
                target = "Keyboard Settings",
                description = "Open keyboard settings",
                intentAction = Settings.ACTION_INPUT_METHOD_SETTINGS
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
            lower.contains("clear text") || lower.contains("delete text") -> textEditAction(
                id = "clear-focused-text",
                kind = AgentActionKind.DELETE_TEXT,
                target = request.screen.inputFields.firstOrNull()?.label?.ifBlank { request.screen.foregroundApp } ?: request.screen.foregroundApp,
                description = "Clear text from the active input field",
                fieldBounds = request.screen.inputFields.firstOrNull()?.bounds.orEmpty()
            )
            lower.contains("paste clipboard") || lower == "paste" -> textEditAction(
                id = "paste-clipboard",
                kind = AgentActionKind.PASTE_TEXT,
                target = request.screen.inputFields.firstOrNull()?.label?.ifBlank { request.screen.foregroundApp } ?: request.screen.foregroundApp,
                description = "Paste clipboard text into the active input field",
                fieldBounds = request.screen.inputFields.firstOrNull()?.bounds.orEmpty()
            )
            lower.startsWith("share text ") -> intentAction(
                id = "share-text",
                target = "Android Share Sheet",
                description = "Share text",
                intentAction = Intent.ACTION_SEND,
                type = "text/plain",
                risk = AgentRisk.MEDIUM,
                extras = mapOf("extra_text" to goal.substring("share text ".length).trim())
            )
            lower.contains("open calendar") -> intentAction(
                id = "open-calendar",
                target = "Calendar",
                description = "Open calendar",
                intentAction = Intent.ACTION_VIEW,
                uri = CalendarContract.CONTENT_URI.toString(),
                risk = AgentRisk.LOW
            )
            lower.startsWith("create calendar event ") || lower.startsWith("add calendar event ") -> {
                val title = goal
                    .removePrefixIgnoreCase("create calendar event ")
                    .removePrefixIgnoreCase("add calendar event ")
                    .trim()
                intentAction(
                    id = "create-calendar-event",
                    target = "Calendar",
                    description = "Create calendar event",
                    intentAction = Intent.ACTION_INSERT,
                    uri = CalendarContract.Events.CONTENT_URI.toString(),
                    risk = AgentRisk.MEDIUM,
                    extras = mapOf("calendar_title" to title.ifBlank { "SignalASI task" })
                )
            }
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
            lower.startsWith("search web ") || lower.startsWith("google ") -> {
                val query = if (lower.startsWith("search web ")) {
                    goal.substring("search web ".length).trim()
                } else {
                    goal.substring("google ".length).trim()
                }
                val url = "https://www.google.com/search?q=${Uri.encode(query)}"
                AgentAction(
                    id = "search-web",
                    kind = AgentActionKind.OPEN_URL,
                    target = "Web Search",
                    risk = AgentRisk.MEDIUM,
                    status = AgentActionStatus.PENDING_CONFIRMATION,
                    description = "Search the web",
                    parameters = mapOf("url" to url)
                )
            }
            lower.startsWith("open map ") || lower.startsWith("map ") || lower.startsWith("navigate to ") -> {
                val query = when {
                    lower.startsWith("open map ") -> goal.substring("open map ".length).trim()
                    lower.startsWith("map ") -> goal.substring("map ".length).trim()
                    else -> goal.substring("navigate to ".length).trim()
                }
                intentAction(
                    id = "open-map",
                    target = "Maps",
                    description = "Open map location",
                    intentAction = Intent.ACTION_VIEW,
                    uri = "geo:0,0?q=${Uri.encode(query)}",
                    risk = AgentRisk.MEDIUM
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

    private fun commonToolAction(lower: String): AgentAction? = when {
        lower.contains("open wechat") -> packageAction(
            id = "open-wechat",
            target = "WeChat",
            description = "Open WeChat",
            packageName = "com.tencent.mm",
            risk = AgentRisk.MEDIUM
        )
        lower.contains("open camera") || lower.contains("take photo") -> intentAction(
            id = "open-camera",
            target = "Camera",
            description = "Open camera",
            intentAction = MediaStore.ACTION_IMAGE_CAPTURE,
            risk = AgentRisk.LOW
        )
        lower.contains("install app") || lower.contains("uninstall app") || lower.contains("delete app") -> blockedSensitiveAction(
            id = "blocked-app-installation",
            target = "Package Manager",
            description = "App installation or removal requires explicit owner control"
        )
        lower.contains("unlock phone") || lower.contains("disable lock") || lower.contains("change screen lock") -> blockedSensitiveAction(
            id = "blocked-lock-control",
            target = "Screen Lock",
            description = "Lock screen and unlock changes are protected"
        )
        lower.contains("answer call") || lower.contains("listen call") || lower.contains("record call") -> blockedSensitiveAction(
            id = "blocked-call-control",
            target = "Phone Call",
            description = "Phone call handling requires explicit owner control"
        )
        lower.contains("send wechat") || lower.contains("reply wechat") || lower.contains("send message to") -> blockedSensitiveAction(
            id = "blocked-third-party-send",
            target = "Third-party messaging",
            description = "Sending to third parties is protected"
        )
        lower.contains("open phone") || lower.contains("open dialer") || lower.contains("make phone call") -> intentAction(
            id = "open-phone",
            target = "Phone",
            description = "Open phone dialer",
            intentAction = Intent.ACTION_DIAL,
            uri = "tel:",
            risk = AgentRisk.HIGH
        )
        lower.contains("open messages") || lower.contains("open sms") || lower.contains("send sms") -> intentAction(
            id = "open-messages",
            target = "Messages",
            description = "Open messages",
            intentAction = Intent.ACTION_VIEW,
            uri = "sms:",
            risk = if (lower.contains("send sms")) AgentRisk.HIGH else AgentRisk.MEDIUM
        )
        lower.contains("open browser") -> intentAction(
            id = "open-browser",
            target = "Browser",
            description = "Open browser",
            intentAction = Intent.ACTION_VIEW,
            uri = "https://www.google.com",
            risk = AgentRisk.LOW
        )
        lower.contains("open contacts") -> intentAction(
            id = "open-contacts",
            target = "Contacts",
            description = "Open contacts",
            intentAction = Intent.ACTION_VIEW,
            uri = "content://contacts/people/",
            risk = AgentRisk.LOW
        )
        lower.contains("open files") || lower.contains("open file manager") -> intentAction(
            id = "open-files",
            target = "Files",
            description = "Open file picker",
            intentAction = Intent.ACTION_OPEN_DOCUMENT,
            type = "*/*",
            category = Intent.CATEGORY_OPENABLE,
            risk = AgentRisk.LOW
        )
        else -> null
    }

    private fun intentAction(
        id: String,
        target: String,
        description: String,
        intentAction: String,
        uri: String = "",
        type: String = "",
        category: String = "",
        risk: AgentRisk = AgentRisk.LOW,
        extras: Map<String, String> = emptyMap()
    ): AgentAction = AgentAction(
        id = id,
        kind = AgentActionKind.OPEN_APP,
        target = target,
        risk = risk,
        status = AgentActionStatus.PENDING_CONFIRMATION,
        description = description,
        parameters = buildMap {
            put("intent_action", intentAction)
            if (uri.isNotBlank()) put("uri", uri)
            if (type.isNotBlank()) put("type", type)
            if (category.isNotBlank()) put("category", category)
            extras.forEach { (key, value) ->
                if (value.isNotBlank()) put(key, value)
            }
        }
    )

    private fun textEditAction(
        id: String,
        kind: AgentActionKind,
        target: String,
        description: String,
        fieldBounds: String
    ): AgentAction = AgentAction(
        id = id,
        kind = kind,
        target = target,
        risk = AgentRisk.MEDIUM,
        status = AgentActionStatus.PENDING_CONFIRMATION,
        description = description,
        parameters = mapOf("field_bounds" to fieldBounds)
    )

    private fun packageAction(
        id: String,
        target: String,
        description: String,
        packageName: String,
        risk: AgentRisk = AgentRisk.LOW
    ): AgentAction = AgentAction(
        id = id,
        kind = AgentActionKind.OPEN_APP,
        target = target,
        risk = risk,
        status = AgentActionStatus.PENDING_CONFIRMATION,
        description = description,
        parameters = mapOf("package" to packageName)
    )

    private fun blockedSensitiveAction(
        id: String,
        target: String,
        description: String
    ): AgentAction = AgentAction(
        id = id,
        kind = AgentActionKind.DRAFT_PLAN,
        target = target,
        risk = AgentRisk.BLOCKED,
        status = AgentActionStatus.PENDING_CONFIRMATION,
        description = description,
        parameters = mapOf("blocked_reason" to description)
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

    private fun String.removePrefixIgnoreCase(prefix: String): String =
        if (startsWith(prefix, ignoreCase = true)) drop(prefix.length) else this

    private val SYSTEM_TOOLS = listOf(
        AgentSystemTool(
            id = "screen-copy",
            title = "Copy Screen Text",
            kind = AgentActionKind.COPY_SCREEN_TEXT,
            risk = AgentRisk.LOW,
            capabilities = listOf(AgentCapability.SCREEN_READING, AgentCapability.CLIPBOARD),
            examples = listOf("copy screen text", "copy current screen", "paste clipboard", "clear text")
        ),
        AgentSystemTool(
            id = "share-text",
            title = "Share Text",
            kind = AgentActionKind.OPEN_APP,
            risk = AgentRisk.MEDIUM,
            capabilities = listOf(AgentCapability.APP_NAVIGATION, AgentCapability.TASK_EXECUTION),
            examples = listOf("share text hello", "create calendar event Project review")
        ),
        AgentSystemTool(
            id = "system-settings",
            title = "System Settings",
            kind = AgentActionKind.OPEN_APP,
            risk = AgentRisk.LOW,
            capabilities = listOf(AgentCapability.SYSTEM_SETTINGS, AgentCapability.APP_NAVIGATION),
            examples = listOf("open settings", "open wifi settings", "open battery settings", "open display settings")
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
            examples = listOf("open url https://example.com", "search web SignalASI", "open map Shenzhen")
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
        ),
        AgentSystemTool(
            id = "phone-tools",
            title = "Phone Tools",
            kind = AgentActionKind.OPEN_APP,
            risk = AgentRisk.MEDIUM,
            capabilities = listOf(AgentCapability.APP_NAVIGATION, AgentCapability.TASK_EXECUTION),
            examples = listOf("open phone", "open messages", "open camera", "open wechat")
        )
    )
}
