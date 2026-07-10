package com.signalasi.chat

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import java.util.Locale

class SignalASINotificationListenerService : NotificationListenerService() {
    override fun onListenerConnected() {
        connected = true
        updateActiveNotifications(activeNotifications?.toList().orEmpty())
    }

    override fun onListenerDisconnected() {
        connected = false
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        sbn ?: return
        val item = notificationItem(sbn)
        synchronized(lock) {
            val next = listOf(item)
                .plus(latestItems.filterNot { it.key == sbn.key })
                .take(MAX_ITEMS)
            latestItems = next
        }
        if (sbn.packageName != packageName) {
            AgentWorkflowTriggerEngine.onNotification(applicationContext, item)
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        sbn ?: return
        synchronized(lock) {
            latestItems = latestItems.filterNot { it.key == sbn.key }
        }
    }

    private fun updateActiveNotifications(items: List<StatusBarNotification>) {
        synchronized(lock) {
            latestItems = items
                .sortedByDescending { it.postTime }
                .take(MAX_ITEMS)
                .map { notificationItem(it) }
        }
    }

    private fun notificationItem(sbn: StatusBarNotification): AgentNotificationItem {
        val extras = sbn.notification.extras
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()
        val bigText = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString().orEmpty()
        val content = bigText.ifBlank { text }.replace(Regex("\\s+"), " ").trim()
        val combined = listOf(title, content, sbn.packageName).joinToString(" ")
        return AgentNotificationItem(
            key = sbn.key,
            packageName = sbn.packageName,
            title = title.take(80),
            textPreview = content.take(120),
            category = notificationCategory(sbn.packageName, combined),
            postedAtMillis = sbn.postTime,
            sensitiveFlags = notificationSensitiveFlags(combined)
        )
    }

    companion object {
        private const val MAX_ITEMS = 12
        private val lock = Any()

        @Volatile
        private var connected: Boolean = false

        @Volatile
        private var latestItems: List<AgentNotificationItem> = emptyList()

        fun currentContext(): AgentNotificationContext = synchronized(lock) {
            AgentNotificationContext(
                hasAccess = connected,
                items = latestItems.take(6),
                sensitiveFlags = latestItems.flatMap { it.sensitiveFlags }.distinct().take(6)
            )
        }
    }
}

data class AgentNotificationContext(
    val hasAccess: Boolean = false,
    val items: List<AgentNotificationItem> = emptyList(),
    val sensitiveFlags: List<String> = emptyList()
)

data class AgentNotificationItem(
    val key: String = "",
    val packageName: String = "",
    val title: String = "",
    val textPreview: String = "",
    val category: String = "app",
    val postedAtMillis: Long = 0L,
    val sensitiveFlags: List<String> = emptyList()
)

private fun notificationCategory(packageName: String, value: String): String {
    val lower = "$packageName $value".lowercase(Locale.US)
    return when {
        listOf("sms", "mms", "message", "messages").any { lower.contains(it) } -> "sms"
        listOf("phone", "dialer", "missed call", "incoming call", "voicemail").any { lower.contains(it) } -> "call"
        listOf("wechat", "whatsapp", "telegram", "signal", "messenger", "chat").any { lower.contains(it) } -> "chat"
        listOf("android system", "system ui", "permission", "settings").any { lower.contains(it) } -> "system"
        else -> "app"
    }
}

private fun notificationSensitiveFlags(value: String): List<String> {
    val lower = value.lowercase(Locale.US)
    val flags = mutableListOf<String>()
    listOf(
        "password",
        "passcode",
        "verification",
        "otp",
        "2fa",
        "bank",
        "payment",
        "private key",
        "access token",
        "api key"
    ).forEach { term ->
        if (lower.contains(term)) flags += term
    }
    if (Regex("\\b\\d{4,8}\\b").containsMatchIn(value) &&
        listOf("code", "otp", "verification", "sms").any { lower.contains(it) }
    ) {
        flags += "verification_code"
    }
    return flags.distinct().take(6)
}
