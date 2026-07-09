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
        synchronized(lock) {
            val next = listOf(notificationItem(sbn))
                .plus(latestItems.filterNot { it.key == sbn.key })
                .take(MAX_ITEMS)
            latestItems = next
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
    val postedAtMillis: Long = 0L,
    val sensitiveFlags: List<String> = emptyList()
)

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
