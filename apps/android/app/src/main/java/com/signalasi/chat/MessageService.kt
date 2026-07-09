package com.signalasi.chat

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.os.Build
import android.os.IBinder
import android.util.Base64

class MessageService : Service(), SignalASIMqttClient.Listener {
    companion object {
        private const val CHANNEL_ID = "signalasi_message_service"
        private const val NOTIFICATION_ID = 1001
        private const val MESSAGE_NOTIFICATION_ID = 1002
    }

    override fun onCreate() {
        super.onCreate()
        ensureNotificationChannel()
        startForeground(NOTIFICATION_ID, serviceNotification())
        SignalASIMqttClient.addListener(this)
        SignalASIMqttClient.connect(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        handleDebugIncoming(intent)
        SignalASIMqttClient.connect(this)
        return START_STICKY
    }

    override fun onDestroy() {
        SignalASIMqttClient.removeListener(this)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onMessage(payload: String) {
        if (AppForegroundTracker.isForeground()) return
        val stored = ChatHistoryStore.appendIncoming(this, payload) ?: return
        if (stored.notify) {
            showIncomingNotification(stored)
            ChatHistoryStore.markNotified(this, stored.contactId, stored.messageId)
        }
    }

    private fun ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.service_channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.service_channel_description)
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    private fun serviceNotification(): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_tab_chat_filled)
            .setContentTitle("SignalASI")
            .setContentText(getString(R.string.service_notification_content))
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setShowWhen(false)
            .build()
    }

    private fun showIncomingNotification(message: StoredIncomingMessage) {
        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            1,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_tab_chat_filled)
            .setContentTitle(message.contactName)
            .setContentText(message.content.take(120))
            .setStyle(Notification.BigTextStyle().bigText(message.content))
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setShowWhen(true)
            .build()
        getSystemService(NotificationManager::class.java).notify(MESSAGE_NOTIFICATION_ID, notification)
    }

    private fun handleDebugIncoming(intent: Intent?) {
        if ((applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) == 0) return
        val encodedPayload = intent?.getStringExtra("signalasi_debug_service_payload_b64")?.trim().orEmpty()
        val payload = if (encodedPayload.isNotBlank()) {
            runCatching {
                String(Base64.decode(encodedPayload, Base64.DEFAULT), Charsets.UTF_8).trim()
            }.getOrDefault("")
        } else {
            intent?.getStringExtra("signalasi_debug_service_payload")?.trim().orEmpty()
        }
        if (payload.isBlank()) return
        ChatHistoryStore.appendIncoming(this, payload)?.let {
            showIncomingNotification(it)
            ChatHistoryStore.markNotified(this, it.contactId, it.messageId)
        }
    }
}
