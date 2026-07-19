package com.signalasi.chat

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.os.Build
import android.os.IBinder
import android.util.Base64
import org.json.JSONObject
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

class MessageService : Service(), SignalASIMqttClient.Listener {
    companion object {
        private const val CHANNEL_ID = "signalasi_message_service"
        private const val NOTIFICATION_ID = 1001
        private const val MESSAGE_NOTIFICATION_ID = 1002
        private const val AGENT_SCHEDULE_NOTIFICATION_ID = 1003
        private const val GLOBAL_AGENT_NOTIFICATION_ID = 1004
        private const val GLOBAL_AGENT_INTERVAL_SECONDS = 45L
        const val ACTION_REFRESH_LANGUAGE = "com.signalasi.chat.action.REFRESH_NOTIFICATION_LANGUAGE"
        const val ACTION_PROCESS_GLOBAL_AGENT = "com.signalasi.chat.action.PROCESS_GLOBAL_AGENT"
    }

    private val globalAgentExecutor = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "signalasi-global-agent-service").apply { isDaemon = true }
    }
    private val globalResearchExecutor = Executors.newFixedThreadPool(2) { runnable ->
        Thread(runnable, "signalasi-global-research").apply { isDaemon = true }
    }

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppLanguage.wrap(newBase))
    }

    override fun onCreate() {
        super.onCreate()
        ensureNotificationChannel()
        startForeground(NOTIFICATION_ID, serviceNotification())
        SignalASIMqttClient.addListener(this)
        SignalASIMqttClient.connect(this)
        thread(name = "signalasi-runtime-service-autostart") {
            runCatching { AgentEmbeddedRuntimeBootstrap.ensureInstalled(this@MessageService) }
            runCatching { AgentOnDeviceRuntimeLifecycle.ensureRunning(this@MessageService) }
        }
        globalAgentExecutor.scheduleWithFixedDelay(
            ::processGlobalAgentEvents,
            0L,
            GLOBAL_AGENT_INTERVAL_SECONDS,
            TimeUnit.SECONDS
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        AppLanguage.applyToResources(this)
        if (intent?.action == ACTION_REFRESH_LANGUAGE) {
            ensureNotificationChannel()
            getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, serviceNotification())
        }
        handleDebugIncoming(intent)
        when (intent?.action) {
            ACTION_PROCESS_GLOBAL_AGENT -> globalAgentExecutor.execute(::processGlobalAgentEvents)
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
        globalAgentExecutor.shutdownNow()
        globalResearchExecutor.shutdownNow()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onMessage(payload: String) {
        if (AppForegroundTracker.isForeground()) return
        val envelope = runCatching { JSONObject(payload) }.getOrNull()
        if (envelope != null && ChatHistoryStore.applyAgentTaskEvent(this, envelope)) return
        if (envelope?.optString("type").orEmpty().ifBlank { "text" } == "text") {
            val sourceMessageId = envelope?.optString("source_message_id")?.toLongOrNull()
                ?: envelope?.optLong("source_message_id", 0L)?.takeIf { it > 0L }
            if (sourceMessageId != null) {
                val preview = ChatHistoryStore.inspectIncoming(this, payload) ?: return
                val response = AgentConnectorResponse(
                    sourceMessageId = sourceMessageId,
                    contactId = envelope?.optString("contact_id").orEmpty().ifBlank { preview.contactId },
                    content = preview.content,
                    conversationId = envelope?.optString("conversation_id").orEmpty(),
                    turnId = envelope?.optString("turn_id").orEmpty(),
                    taskId = envelope?.optString("task_id").orEmpty(),
                    richOutputJson = AgentRichContentCodec.fromEnvelope(envelope)
                )
                val globalRuntime = GlobalSuperAgentRuntime.get(this)
                if (globalRuntime.consumeResearchResponse(response)) {
                    globalAgentExecutor.execute(::processGlobalAgentEvents)
                    return
                }
                AgentConnectorResponseBus.publish(this, response)
            }
        }
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
            .setSmallIcon(R.drawable.ic_tab_chat_filled)
            .setContentTitle(getString(R.string.agent_schedule_notification_title, workflowName))
            .setContentText(detail.take(160))
            .setStyle(Notification.BigTextStyle().bigText(detail))
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setShowWhen(true)
            .build()
        getSystemService(NotificationManager::class.java).notify(AGENT_SCHEDULE_NOTIFICATION_ID, notification)
    }

    private fun processGlobalAgentEvents() {
        val runtime = GlobalSuperAgentRuntime.get(this)
        runCatching { runtime.processPending() }
        runCatching { runtime.processLongHorizonCycle() }
        repeat(2) {
            globalResearchExecutor.execute {
                val cognition = runCatching { runtime.executeCognitionCycle() }.getOrNull()
                val autonomous = runCatching { runtime.executeAutonomousCycle() }.getOrNull()
                val research = runCatching { runtime.executeResearchCycle() }.getOrNull()
                if (cognition?.status == GlobalCognitionTaskStatus.COMPLETED ||
                    autonomous?.status in setOf(
                        GlobalAutonomousRunStatus.COMPLETED,
                        GlobalAutonomousRunStatus.PARTIAL,
                        GlobalAutonomousRunStatus.REPLANNING
                    ) ||
                    research?.status in setOf(
                        GlobalResearchTaskStatus.COMPLETED,
                        GlobalResearchTaskStatus.SCHEDULED
                    )
                ) {
                    runCatching { runtime.processPending() }
                    runCatching { runtime.processLongHorizonCycle() }
                }
                notifyPendingGlobalMessage(runtime)
                runCatching { runtime.scheduleNextWake() }
            }
        }
        notifyPendingGlobalMessage(runtime)
        runCatching { runtime.scheduleNextWake() }
    }

    private fun notifyPendingGlobalMessage(runtime: GlobalSuperAgentRuntime) {
        if (AppForegroundTracker.isForeground()) return
        val candidate = runtime.nextNotificationCandidate() ?: return
        showGlobalAgentNotification(candidate)
        runtime.markNotified(candidate.messageIds)
    }

    private fun showGlobalAgentNotification(message: GlobalAgentNotificationCandidate) {
        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("signalasi_open_agent", true)
            putExtra("signalasi_agent_conversation_id", message.sourceConversationId)
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            GLOBAL_AGENT_NOTIFICATION_ID,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_tab_chat_filled)
            .setContentTitle(message.title)
            .setContentText(message.content.take(160))
            .setStyle(Notification.BigTextStyle().bigText(message.content))
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setShowWhen(true)
            .build()
        getSystemService(NotificationManager::class.java).notify(GLOBAL_AGENT_NOTIFICATION_ID, notification)
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
