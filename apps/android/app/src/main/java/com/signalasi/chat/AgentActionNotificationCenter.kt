package com.signalasi.chat

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Build
import android.provider.AlarmClock
import kotlin.math.absoluteValue

class NotifyingAgentActionExecutor(
    context: Context,
    private val delegate: AgentActionExecutor
) : AgentActionExecutor {
    private val notifications = AgentActionNotificationCenter(context.applicationContext)

    override fun execute(action: AgentAction, screen: ScreenContext): AgentActionResult {
        if (!action.shouldPublishPhoneNotification()) return delegate.execute(action, screen)
        notifications.showRunning(action)
        val result = delegate.execute(action, screen)
        notifications.showResult(action, result)
        return result
    }

    private fun AgentAction.shouldPublishPhoneNotification(): Boolean = kind !in setOf(
        AgentActionKind.DRAFT_PLAN,
        AgentActionKind.CALL_CONNECTOR,
        AgentActionKind.CREATE_NOTIFICATION
    )
}

private class AgentActionNotificationCenter(private val context: Context) {
    private val manager = context.getSystemService(NotificationManager::class.java)

    fun showRunning(action: AgentAction) {
        ensureChannel()
        manager.notify(
            notificationId(action),
            builder(action)
                .setContentTitle(operationTitle(action))
                .setContentText(context.getString(R.string.agent_operation_status_running))
                .setProgress(0, 0, true)
                .setOngoing(true)
                .setAutoCancel(false)
                .setCategory(Notification.CATEGORY_PROGRESS)
                .build()
        )
    }

    fun showResult(action: AgentAction, result: AgentActionResult) {
        ensureChannel()
        val detail = if (result.success) {
            context.getString(R.string.agent_operation_status_success)
        } else {
            result.message.trim().ifBlank { context.getString(R.string.agent_operation_status_failure) }
        }
        manager.notify(
            notificationId(action),
            builder(action, result.success)
                .setContentTitle(operationTitle(action))
                .setContentText(detail.take(160))
                .setStyle(Notification.BigTextStyle().bigText(detail))
                .setProgress(0, 0, false)
                .setOngoing(false)
                .setAutoCancel(true)
                .setCategory(if (result.success) Notification.CATEGORY_STATUS else Notification.CATEGORY_ERROR)
                .build()
        )
    }

    private fun builder(action: AgentAction, successful: Boolean? = null): Notification.Builder {
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(context, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(context)
        }
        val publicNotification = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(context, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(context)
        }
            .setSmallIcon(R.drawable.ic_tab_agent_filled)
            .setContentTitle(context.getString(R.string.app_name))
            .setContentText(context.getString(R.string.agent_operation_private_text))
            .build()
        return builder
            .setSmallIcon(R.drawable.ic_tab_agent_filled)
            .setColor(
                Color.parseColor(
                    when (successful) {
                        false -> "#B56A6A"
                        else -> "#12BFA3"
                    }
                )
            )
            .setSubText(context.getString(R.string.agent_operation_subtext))
            .setContentIntent(contentIntent(action))
            .setPublicVersion(publicNotification)
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .setOnlyAlertOnce(true)
            .setShowWhen(true)
    }

    private fun contentIntent(action: AgentAction): PendingIntent {
        val intent = if (action.kind == AgentActionKind.SET_ALARM &&
            action.parameters["timer_seconds"]?.toIntOrNull() != null
        ) {
            Intent(AlarmClock.ACTION_SHOW_TIMERS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        } else {
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
        }
        return PendingIntent.getActivity(
            context,
            notificationId(action),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun operationTitle(action: AgentAction): String {
        val timerSeconds = action.parameters["timer_seconds"]?.toIntOrNull()
        val hour = action.parameters["hour"]?.toIntOrNull()
        val minute = action.parameters["minute"]?.toIntOrNull()
        val value = buildString {
            append(action.id).append(' ')
            append(action.target).append(' ')
            append(action.description).append(' ')
            action.parameters.forEach { (key, item) -> append(key).append(' ').append(item).append(' ') }
        }.lowercase()
        return when {
            timerSeconds != null && timerSeconds % 60 == 0 -> context.getString(
                R.string.agent_operation_timer_minutes,
                timerSeconds / 60
            )
            timerSeconds != null -> context.getString(R.string.agent_operation_timer_seconds, timerSeconds)
            action.kind == AgentActionKind.SET_ALARM && hour != null && minute != null -> context.getString(
                R.string.agent_operation_alarm_time,
                "%02d:%02d".format(hour, minute)
            )
            action.kind == AgentActionKind.SET_ALARM -> context.getString(R.string.agent_operation_alarm)
            "camera" in value || "photo" in value -> context.getString(R.string.agent_operation_camera)
            "flashlight" in value || "torch" in value -> context.getString(R.string.agent_operation_flashlight)
            "volume" in value || "audio mute" in value -> context.getString(R.string.agent_operation_volume)
            "battery" in value -> context.getString(R.string.agent_operation_battery)
            "device status" in value -> context.getString(R.string.agent_operation_device_status)
            action.kind == AgentActionKind.OPEN_APP -> context.getString(
                R.string.agent_operation_open_app,
                action.target.ifBlank { action.description }
            )
            else -> action.description.ifBlank { action.target.ifBlank { action.kind.name } }
        }
    }

    private fun notificationId(action: AgentAction): Int {
        val taskId = action.parameters["_signalasi_task_id"].orEmpty().ifBlank { action.id }
        return NOTIFICATION_ID_BASE + (taskId.hashCode() % NOTIFICATION_ID_RANGE).absoluteValue
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.agent_operation_channel_name),
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = context.getString(R.string.agent_operation_channel_description)
                setShowBadge(false)
            }
        )
    }

    private companion object {
        const val CHANNEL_ID = "signalasi_phone_operations"
        const val NOTIFICATION_ID_BASE = 52_000
        const val NOTIFICATION_ID_RANGE = 10_000
    }
}
