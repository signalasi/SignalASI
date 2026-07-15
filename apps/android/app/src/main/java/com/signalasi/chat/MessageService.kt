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
import org.json.JSONObject

class MessageService : Service(), SignalASIMqttClient.Listener {
    companion object {
        private const val CHANNEL_ID = "signalasi_message_service"
        private const val NOTIFICATION_ID = 1001
        private const val MESSAGE_NOTIFICATION_ID = 1002
        private const val AGENT_SCHEDULE_NOTIFICATION_ID = 1003
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
        when (intent?.action) {
            AgentWorkflowScheduler.ACTION_RUN_SCHEDULE -> executeScheduledWorkflow(
                intent.getStringExtra(AgentWorkflowScheduler.EXTRA_SCHEDULE_ID).orEmpty()
            )
            AgentWorkflowTriggerEngine.ACTION_RUN_TRIGGER -> executeTriggeredWorkflow(
                intent.getStringExtra(AgentWorkflowTriggerEngine.EXTRA_TRIGGER_ID).orEmpty()
            )
        }
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
        val envelope = runCatching { JSONObject(payload) }.getOrNull()
        if (envelope != null && ChatHistoryStore.applyAgentTaskEvent(this, envelope)) return
        val stored = ChatHistoryStore.appendIncoming(this, payload) ?: return
        if (envelope?.optString("type").orEmpty().ifBlank { "text" } == "text") {
            val sourceMessageId = envelope?.optString("source_message_id")?.toLongOrNull()
                ?: envelope?.optLong("source_message_id", 0L)?.takeIf { it > 0L }
            if (sourceMessageId != null) {
                AgentConnectorResponseBus.publish(
                    this,
                    AgentConnectorResponse(
                        sourceMessageId = sourceMessageId,
                        contactId = envelope?.optString("contact_id").orEmpty().ifBlank { stored.contactId },
                        content = stored.content,
                        richOutputJson = AgentRichContentCodec.fromEnvelope(envelope)
                    )
                )
            }
        }
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
            putExtra("signalasi_open_agent", true)
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

    private fun executeScheduledWorkflow(scheduleId: String) {
        if (scheduleId.isBlank()) return
        val schedule = AgentWorkflowScheduleStore(this).findById(scheduleId) ?: return
        executeWorkflow(
            workflowId = schedule.workflowId,
            workflowName = schedule.workflowName,
            source = AgentWorkflowExecutionSource.SCHEDULE
        ) {
            AgentWorkflowScheduler.cancel(this, schedule)
        }
    }

    private fun executeTriggeredWorkflow(triggerId: String) {
        if (triggerId.isBlank()) return
        val triggerStore = AgentWorkflowTriggerStore(this)
        val trigger = triggerStore.findById(triggerId) ?: return
        executeWorkflow(
            workflowId = trigger.workflowId,
            workflowName = trigger.workflowName,
            source = AgentWorkflowExecutionSource.EVENT
        ) {
            triggerStore.deleteForWorkflow(trigger.workflowId)
        }
    }

    private fun executeWorkflow(
        workflowId: String,
        workflowName: String,
        source: AgentWorkflowExecutionSource,
        onWorkflowMissing: () -> Unit
    ) {
        val workflowStore = SharedPreferencesAgentWorkflowStore(this)
        val workflow = workflowStore.findById(workflowId) ?: run {
            onWorkflowMissing()
            showScheduledAgentNotification(workflowName, getString(R.string.agent_schedule_workflow_missing))
            return
        }
        val agent = MobileNativeAgent(this)
        val startedAtMillis = System.currentTimeMillis()
        if (agent.snapshot().runningTaskCount > 0) {
            val detail = getString(R.string.agent_schedule_busy)
            AgentWorkflowExecutionHistoryStore(this).upsert(
                AgentWorkflowExecutionRecord(
                    workflowId = workflow.id,
                    workflowName = workflow.name,
                    source = source,
                    status = AgentWorkflowExecutionStatus.SKIPPED,
                    startedAtMillis = startedAtMillis,
                    completedAtMillis = System.currentTimeMillis(),
                    resultSummary = detail
                )
            )
            showScheduledAgentNotification(workflow.name, detail)
            return
        }
        workflowStore.markRun(workflow.id)
        val executionStore = AgentWorkflowExecutionHistoryStore(this)
        val execution = AgentWorkflowExecutionRecord(
            workflowId = workflow.id,
            workflowName = workflow.name,
            source = source,
            status = AgentWorkflowExecutionStatus.RUNNING,
            startedAtMillis = startedAtMillis
        )
        executionStore.upsert(execution)
        agent.attachWorkflowExecution(execution.id)
        val state = agent.submitGoal(workflow.goal)
        val detail = when {
            state.phase == AgentPhase.WAITING_CONFIRMATION -> getString(
                R.string.agent_schedule_approval_required,
                state.pendingAction?.description.orEmpty().ifBlank { workflow.goal }
            )
            state.phase == AgentPhase.BLOCKED -> state.plan?.safetyReview?.reason
                ?.ifBlank { getString(R.string.agent_status_blocked) }
                ?: getString(R.string.agent_status_blocked)
            state.phase == AgentPhase.WAITING_RESPONSE -> getString(R.string.agent_status_waiting_response)
            state.lastActionResult != null -> state.lastActionResult.message
            else -> getString(R.string.agent_schedule_started)
        }
        val status = workflowExecutionStatus(state.phase)
        executionStore.upsert(
            execution.copy(
                status = status,
                completedAtMillis = if (status.isTerminal()) System.currentTimeMillis() else 0L,
                resultSummary = detail.take(2_000)
            )
        )
        showScheduledAgentNotification(workflow.name, detail)
    }

    private fun workflowExecutionStatus(phase: AgentPhase): AgentWorkflowExecutionStatus = when (phase) {
        AgentPhase.WAITING_CONFIRMATION -> AgentWorkflowExecutionStatus.WAITING_CONFIRMATION
        AgentPhase.WAITING_RESPONSE -> AgentWorkflowExecutionStatus.WAITING_RESPONSE
        AgentPhase.COMPLETED -> AgentWorkflowExecutionStatus.COMPLETED
        AgentPhase.FAILED -> AgentWorkflowExecutionStatus.FAILED
        AgentPhase.CANCELLED -> AgentWorkflowExecutionStatus.CANCELLED
        AgentPhase.BLOCKED -> AgentWorkflowExecutionStatus.BLOCKED
        else -> AgentWorkflowExecutionStatus.RUNNING
    }

    private fun AgentWorkflowExecutionStatus.isTerminal(): Boolean = when (this) {
        AgentWorkflowExecutionStatus.COMPLETED,
        AgentWorkflowExecutionStatus.SKIPPED,
        AgentWorkflowExecutionStatus.FAILED,
        AgentWorkflowExecutionStatus.CANCELLED,
        AgentWorkflowExecutionStatus.BLOCKED -> true
        else -> false
    }

    private fun showScheduledAgentNotification(workflowName: String, detail: String) {
        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            AGENT_SCHEDULE_NOTIFICATION_ID,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_tab_agent_filled)
            .setContentTitle(getString(R.string.agent_schedule_notification_title, workflowName))
            .setContentText(detail.take(160))
            .setStyle(Notification.BigTextStyle().bigText(detail))
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setShowWhen(true)
            .build()
        getSystemService(NotificationManager::class.java).notify(AGENT_SCHEDULE_NOTIFICATION_ID, notification)
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
