package com.signalasi.chat

import android.app.Notification
import android.app.RemoteInput
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import java.util.Locale

class SignalASINotificationListenerService : NotificationListenerService() {
    private val reconnectHandler = Handler(Looper.getMainLooper())
    private val retryActiveNotificationSync = Runnable {
        if (activeService === this) refreshActiveNotifications(scheduleRetry = false)
    }

    override fun onCreate() {
        super.onCreate()
        activeService = this
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        activeService = this
        refreshActiveNotifications(scheduleRetry = true)
    }

    override fun onListenerDisconnected() {
        reconnectHandler.removeCallbacks(retryActiveNotificationSync)
        connected = false
        synchronized(lock) {
            latestItems = emptyList()
            latestNotifications = emptyMap()
        }
    }

    override fun onDestroy() {
        reconnectHandler.removeCallbacks(retryActiveNotificationSync)
        if (activeService === this) activeService = null
        connected = false
        synchronized(lock) {
            latestItems = emptyList()
            latestNotifications = emptyMap()
        }
        super.onDestroy()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        sbn ?: return
        connected = true
        val item = notificationItem(sbn)
        synchronized(lock) {
            val next = listOf(item)
                .plus(latestItems.filterNot { it.key == sbn.key })
                .take(MAX_ITEMS)
            latestItems = next
            latestNotifications = mapOf(sbn.key to sbn)
                .plus(latestNotifications.filterKeys { it != sbn.key })
                .entries
                .take(MAX_ITEMS)
                .associate { it.toPair() }
        }
        if (sbn.packageName != packageName) {
            AgentWorkflowTriggerEngine.onNotification(applicationContext, item)
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        sbn ?: return
        connected = true
        synchronized(lock) {
            latestItems = latestItems.filterNot { it.key == sbn.key }
            latestNotifications = latestNotifications.filterKeys { it != sbn.key }
        }
    }

    private fun refreshActiveNotifications(scheduleRetry: Boolean) {
        val snapshot = try {
            activeNotifications?.toList().orEmpty()
        } catch (error: Exception) {
            connected = false
            Log.w(TAG, "Notification listener snapshot is not ready", error)
            if (scheduleRetry && activeService === this) {
                reconnectHandler.removeCallbacks(retryActiveNotificationSync)
                reconnectHandler.postDelayed(retryActiveNotificationSync, ACTIVE_SYNC_RETRY_MILLIS)
            }
            return
        }
        connected = true
        updateActiveNotifications(snapshot)
    }

    private fun updateActiveNotifications(items: List<StatusBarNotification>) {
        synchronized(lock) {
            val recent = items
                .sortedByDescending { it.postTime }
                .take(MAX_ITEMS)
            latestItems = recent.map { notificationItem(it) }
            latestNotifications = recent.associateBy { it.key }
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
            canReply = replyAction(sbn.notification) != null,
            sensitiveFlags = notificationSensitiveFlags(combined)
        )
    }

    private fun reply(notificationKey: String, text: String): AgentNotificationReplyResult {
        val cleanText = text.trim().take(MAX_REPLY_CHARACTERS)
        if (notificationKey.isBlank() || cleanText.isBlank()) {
            return AgentNotificationReplyResult(
                false,
                "Notification reply target or text is missing",
                code = "invalid_notification_reply"
            )
        }
        val notification = synchronized(lock) { latestNotifications[notificationKey] }
            ?: return AgentNotificationReplyResult(
                false,
                "The notification is no longer available",
                code = "notification_stale",
                retryable = true
            )
        val item = notificationItem(notification)
        if (item.sensitiveFlags.isNotEmpty() || item.category == "system" || item.category == "call") {
            return AgentNotificationReplyResult(
                false,
                "Replies to sensitive or system notifications are blocked",
                code = "notification_reply_blocked",
                notificationPackage = item.packageName,
                notificationTitle = item.title
            )
        }
        val action = replyAction(notification.notification)
            ?: return AgentNotificationReplyResult(
                false,
                "This notification does not support direct reply",
                code = "notification_reply_unsupported",
                notificationPackage = item.packageName,
                notificationTitle = item.title
            )
        val remoteInputs = action.remoteInputs
            ?.filter { it.allowFreeFormInput }
            ?.toTypedArray()
            .orEmpty()
        if (remoteInputs.isEmpty()) {
            return AgentNotificationReplyResult(
                false,
                "This notification has no text reply field",
                code = "notification_reply_unsupported",
                notificationPackage = item.packageName,
                notificationTitle = item.title
            )
        }
        return runCatching {
            val resultIntent = Intent()
            val results = Bundle().apply {
                remoteInputs.forEach { input -> putCharSequence(input.resultKey, cleanText) }
            }
            RemoteInput.addResultsToIntent(remoteInputs, resultIntent, results)
            action.actionIntent.send(this, 0, resultIntent)
            AgentNotificationReplyResult(
                true,
                "Reply dispatched to ${item.title.ifBlank { item.packageName }}",
                code = "notification_reply_dispatched",
                notificationPackage = item.packageName,
                notificationTitle = item.title
            )
        }.getOrElse { error ->
            AgentNotificationReplyResult(
                false,
                error.message ?: "Notification reply failed",
                code = "notification_reply_failed",
                retryable = true,
                notificationPackage = item.packageName,
                notificationTitle = item.title
            )
        }
    }

    private fun replyAction(notification: Notification): Notification.Action? =
        notification.actions?.firstOrNull { action ->
            action.remoteInputs?.any { it.allowFreeFormInput } == true
        }

    companion object {
        private const val TAG = "SignalASINotification"
        private const val MAX_ITEMS = 12
        private const val MAX_REPLY_CHARACTERS = 2_000
        private const val ACTIVE_SYNC_RETRY_MILLIS = 500L
        private val lock = Any()

        @Volatile
        private var connected: Boolean = false

        @Volatile
        private var latestItems: List<AgentNotificationItem> = emptyList()

        @Volatile
        private var latestNotifications: Map<String, StatusBarNotification> = emptyMap()

        @Volatile
        private var activeService: SignalASINotificationListenerService? = null

        fun currentContext(limit: Int = 6): AgentNotificationContext = synchronized(lock) {
            AgentNotificationContext(
                hasAccess = connected,
                items = latestItems.take(limit.coerceIn(1, MAX_ITEMS)),
                sensitiveFlags = latestItems.flatMap { it.sensitiveFlags }.distinct().take(6),
                totalCount = latestItems.size
            )
        }

        fun reply(notificationKey: String, text: String): AgentNotificationReplyResult =
            activeService?.reply(notificationKey, text)
                ?: AgentNotificationReplyResult(false, "Notification access service is not connected")
    }
}

data class AgentNotificationReplyResult(
    val success: Boolean,
    val message: String,
    val code: String = "",
    val retryable: Boolean = false,
    val notificationPackage: String = "",
    val notificationTitle: String = ""
)

data class AgentNotificationContext(
    val hasAccess: Boolean = false,
    val items: List<AgentNotificationItem> = emptyList(),
    val sensitiveFlags: List<String> = emptyList(),
    val totalCount: Int = items.size
)

data class AgentNotificationItem(
    val key: String = "",
    val packageName: String = "",
    val title: String = "",
    val textPreview: String = "",
    val category: String = "app",
    val postedAtMillis: Long = 0L,
    val canReply: Boolean = false,
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
        "api key",
        "\u5bc6\u7801",
        "\u9a8c\u8bc1\u7801",
        "\u94f6\u884c\u5361",
        "\u79c1\u94a5",
        "\u652f\u4ed8"
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
