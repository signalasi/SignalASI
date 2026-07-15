package com.signalasi.chat

import android.content.Context
import org.json.JSONArray

enum class AgentConfirmationTier {
    DIRECT,
    CONFIRM_ONCE,
    CONFIRM_ALWAYS
}

interface AgentConfirmationConsentStore {
    fun isRemembered(consentKey: String): Boolean
    fun remember(consentKey: String)
    fun clear()
}

class SharedPreferencesAgentConfirmationConsentStore(context: Context) : AgentConfirmationConsentStore {
    private val preferences = AgentEncryptedPreferences(context.applicationContext, PREFS)

    override fun isRemembered(consentKey: String): Boolean = consentKey in readKeys()

    override fun remember(consentKey: String) {
        if (consentKey.isBlank()) return
        val keys = readKeys() + consentKey
        preferences.writeString(KEY_CONSENTS, JSONArray(keys.sorted()).toString())
    }

    override fun clear() = preferences.clear()

    private fun readKeys(): Set<String> {
        val array = runCatching {
            JSONArray(preferences.readString(KEY_CONSENTS, "[]"))
        }.getOrDefault(JSONArray())
        return buildSet {
            for (index in 0 until array.length()) {
                array.optString(index).trim().takeIf(String::isNotBlank)?.let(::add)
            }
        }
    }

    private companion object {
        const val PREFS = "signalasi_agent_confirmation_consents"
        const val KEY_CONSENTS = "remembered_consents"
    }
}

object AgentConfirmationPolicy {
    fun tier(action: AgentAction): AgentConfirmationTier {
        val value = searchableValue(action)
        if (action.kind in ALWAYS_CONFIRM_KINDS || ALWAYS_CONFIRM_TERMS.any(value::contains)) {
            return AgentConfirmationTier.CONFIRM_ALWAYS
        }
        if (CONFIRM_ONCE_TERMS.any(value::contains) || action.kind == AgentActionKind.CONTROL_DEVICE) {
            return AgentConfirmationTier.CONFIRM_ONCE
        }
        if (action.kind == AgentActionKind.SET_ALARM || action.kind == AgentActionKind.OPEN_APP ||
            action.id in DIRECT_ACTION_IDS || DIRECT_TERMS.any(value::contains)
        ) return AgentConfirmationTier.DIRECT
        return when (action.risk) {
            AgentRisk.LOW -> AgentConfirmationTier.DIRECT
            AgentRisk.MEDIUM -> AgentConfirmationTier.CONFIRM_ONCE
            AgentRisk.HIGH,
            AgentRisk.BLOCKED -> AgentConfirmationTier.CONFIRM_ALWAYS
        }
    }

    fun consentKey(action: AgentAction): String {
        val value = searchableValue(action)
        return when {
            LOCATION_TERMS.any(value::contains) -> "location"
            MICROPHONE_TERMS.any(value::contains) -> "microphone"
            DOWNLOAD_TERMS.any(value::contains) -> "downloads"
            CONTACT_WRITE_TERMS.any(value::contains) -> "contacts_write"
            CALENDAR_WRITE_TERMS.any(value::contains) -> "calendar_write"
            action.kind == AgentActionKind.CONTROL_DEVICE -> "device_control:${action.target.lowercase().trim()}"
            else -> "action:${action.kind.name.lowercase()}:${action.id.lowercase().trim()}"
        }
    }

    private fun searchableValue(action: AgentAction): String = buildString {
        append(action.id).append(' ')
        append(action.kind.name).append(' ')
        append(action.target).append(' ')
        append(action.description).append(' ')
        action.parameters.forEach { (key, value) -> append(key).append(' ').append(value).append(' ') }
    }.lowercase()

    private val ALWAYS_CONFIRM_KINDS = setOf(
        AgentActionKind.REPLY_NOTIFICATION,
        AgentActionKind.DELETE_TEXT,
        AgentActionKind.LOCK_SCREEN
    )

    private val DIRECT_ACTION_IDS = setOf(
        "set-timer", "open-timer", "set-alarm", "open-camera", "open-flashlight",
        "battery-status", "device-status"
    )

    private val ALWAYS_CONFIRM_TERMS = listOf(
        "send sms", "sms.send", "reply sms", "send message", "reply message", "reply notification",
        "send email", "reply email", "phone call", "dial", "telephony.dial", "delete", "remove",
        "install", "uninstall", "payment", "purchase", "checkout", "transfer", "grant permission",
        "authorize", "security setting", "screen lock", "lock device", "device_policy.lock", "reboot",
        "door lock", "smart lock", "garage door", "alarm panel", "private key", "password",
        "\u53d1\u9001\u77ed\u4fe1", "\u56de\u590d\u77ed\u4fe1", "\u53d1\u6d88\u606f", "\u56de\u590d\u6d88\u606f",
        "\u6253\u7535\u8bdd", "\u62e8\u53f7", "\u5220\u9664", "\u5b89\u88c5", "\u5378\u8f7d", "\u652f\u4ed8",
        "\u8f6c\u8d26", "\u6388\u6743", "\u6743\u9650", "\u5b89\u5168\u8bbe\u7f6e", "\u9501\u5c4f", "\u91cd\u542f",
        "\u95e8\u9501", "\u8f66\u5e93\u95e8"
    )

    private val DIRECT_TERMS = listOf(
        "timer", "alarm clock", "set alarm", "camera capture", "take photo", "flashlight", "torch",
        "audio volume", "set volume", "audio mute", "open app", "launch app", "battery status",
        "device status", "read battery", "read device", "\u8ba1\u65f6\u5668", "\u95f9\u949f", "\u62cd\u7167",
        "\u624b\u7535\u7b52", "\u97f3\u91cf", "\u6253\u5f00app", "\u6253\u5f00 app", "\u7535\u91cf", "\u8bbe\u5907\u72b6\u6001"
    )

    private val LOCATION_TERMS = listOf("location", "gps", "\u5b9a\u4f4d", "\u4f4d\u7f6e")
    private val MICROPHONE_TERMS = listOf("microphone", "record audio", "\u9ea6\u514b\u98ce", "\u5f55\u97f3")
    private val DOWNLOAD_TERMS = listOf("download", "\u4e0b\u8f7d")
    private val CONTACT_WRITE_TERMS = listOf(
        "contacts.write", "contact upsert", "create contact", "update contact",
        "\u65b0\u5efa\u8054\u7cfb\u4eba", "\u4fee\u6539\u8054\u7cfb\u4eba", "\u66f4\u65b0\u8054\u7cfb\u4eba"
    )
    private val CALENDAR_WRITE_TERMS = listOf(
        "calendar.write", "calendar event upsert", "create calendar event", "update calendar event",
        "\u65b0\u5efa\u65e5\u7a0b", "\u4fee\u6539\u65e5\u7a0b", "\u66f4\u65b0\u65e5\u7a0b"
    )
    private val CONFIRM_ONCE_TERMS =
        LOCATION_TERMS + MICROPHONE_TERMS + DOWNLOAD_TERMS + CONTACT_WRITE_TERMS + CALENDAR_WRITE_TERMS
}
