package com.galaxyssi.chat

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import android.os.IBinder
import android.util.Base64
import android.util.Log
import com.galaxyssi.chat.voice.VoiceFeatureFlags
import com.galaxyssi.chat.voice.agent.VoiceAgentRunBridge
import org.json.JSONObject
import java.util.concurrent.Executors
import kotlin.concurrent.thread

class MessageService : Service(), GalaxySSIMqttClient.Listener {
    companion object {
        private const val CHANNEL_ID = "galaxyssi_message_service"
        private const val MESSAGE_CHANNEL_ID = "galaxyssi_incoming_messages_v2"
        private const val NOTIFICATION_ID = 1001
        private const val AGENT_SCHEDULE_NOTIFICATION_ID = 1003
        const val ACTION_REFRESH_LANGUAGE = "com.galaxyssi.chat.action.REFRESH_NOTIFICATION_LANGUAGE"
        const val ACTION_PROCESS_GLOBAL_AGENT = "com.galaxyssi.chat.action.PROCESS_GLOBAL_AGENT"

        internal fun cancelIncomingMessageNotification(context: Context, contactId: String) {
            if (contactId.isBlank()) return
            context.getSystemService(NotificationManager::class.java)
                .cancel(incomingMessageNotificationId(contactId))
        }

        internal fun incomingMessageNotificationId(contactId: String): Int =
            "message:$contactId".hashCode()
    }

    private val proactiveTaskExecutor = Executors.newFixedThreadPool(4) { runnable ->
        Thread(runnable, "galaxyssi-proactive-task").apply { isDaemon = true }
    }
    private var networkRecoveryCallback: ConnectivityManager.NetworkCallback? = null

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppLanguage.wrap(newBase))
    }

    override fun onCreate() {
        super.onCreate()
        ensureNotificationChannel()
        startForeground(NOTIFICATION_ID, serviceNotification())
        GalaxySSIMqttClient.addListener(this)
        GalaxySSIMqttClient.connect(this)
        registerNetworkRecoveryCallback()
        thread(name = "galaxyssi-runtime-bootstrap") {
            runCatching { AgentEmbeddedRuntimeBootstrap.ensureInstalled(this@MessageService) }
            runCatching {
                AgentLongTaskRecoveryScheduler.enqueueRecoverable(
                    this@MessageService,
                    "message_service_started"
                )
            }.onFailure { error ->
                Log.w("GalaxySSILongTask", "Could not schedule durable task recovery", error)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        AppLanguage.applyToResources(this)
        if (intent?.action == ACTION_REFRESH_LANGUAGE) {
            ensureNotificationChannel()
            getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, serviceNotification())
        }
        handleDebugIncoming(intent)
        when (intent?.action) {
            ACTION_PROCESS_GLOBAL_AGENT -> Unit
            AgentWorkflowScheduler.ACTION_RUN_SCHEDULE -> executeScheduledWorkflow(
                intent.getStringExtra(AgentWorkflowScheduler.EXTRA_SCHEDULE_ID).orEmpty()
            )
            AgentWorkflowTriggerEngine.ACTION_RUN_TRIGGER -> executeTriggeredWorkflow(
                intent.getStringExtra(AgentWorkflowTriggerEngine.EXTRA_TRIGGER_ID).orEmpty()
            )
            AgentProactiveTaskScheduler.ACTION_RUN -> {
                val runId = intent.getStringExtra(
                    AgentProactiveTaskScheduler.EXTRA_RUN_ID
                ).orEmpty()
                proactiveTaskExecutor.execute {
                    AgentProactiveTaskExecutor.execute(this@MessageService, runId)
                }
            }
        }
        GalaxySSIMqttClient.connect(this)
        return START_STICKY
    }

    override fun onConnectionChanged(isConnected: Boolean) {
        Unit
    }

    override fun onSecureChannelChanged(isReady: Boolean) {
        Unit
    }

    override fun onDeliveryFailed(sourceMessageId: Long, contactId: String, reason: String) {
        if (sourceMessageId <= 0L) return
        if (contactId.isNotBlank()) {
            ChatHistoryStore.markOutgoingDelivery(
                this,
                contactId,
                sourceMessageId,
                "delivery_failed",
                reason,
                getString(R.string.delivery_status_failed)
            )
        }
        AgentDeliveryFailureRecorder.record(
            this,
            sourceMessageId,
            contactId,
            getString(R.string.agent_message_not_delivered)
        )
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        unregisterNetworkRecoveryCallback()
        GalaxySSIMqttClient.removeListener(this)
        proactiveTaskExecutor.shutdownNow()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onMessage(payload: String) {
        var handled = true
        try {
            val envelope = runCatching { JSONObject(payload) }.getOrNull()
            if (PeerChatPresentation.isInternalTransportEvent(envelope)) return
            if (envelope?.optString("type") == "phone_contact_request_received") {
                showFriendRequestNotification(envelope.optString("name"))
                return
            }
            if (envelope?.optString("type") == "phone_contact_session_ready") return
            if (envelope?.optString("type") == "phone_contact_request_approved") {
                showPhoneContactStatusNotification(envelope.optString("name"), approved = true)
                return
            }
            if (envelope?.optString("type") == "phone_contact_request_rejected") {
                showPhoneContactStatusNotification(envelope.optString("name"), approved = false)
                return
            }
            if (AppForegroundTracker.isForeground()) {
                if (AgentRuntimeNotificationPolicy.suppressMessageNotification(envelope)) return
                val preview = ChatHistoryStore.inspectIncoming(this, payload) ?: return
                if (preview.notify && !AppForegroundTracker.isConversationVisible(preview.contactId)) {
                    showIncomingNotification(preview)
                }
                return
            }
            if (
                envelope?.optString("type") == "agent_task_event" &&
                VoiceFeatureFlags.isAgentVoiceRunBridgeEnabled(this)
            ) {
                VoiceAgentRunBridge.get(this).consumeRemoteEnvelope(envelope)
            }
            if (envelope != null && ChatHistoryStore.applyAgentTaskEvent(this, envelope)) return
            if (envelope?.optString("type").orEmpty().ifBlank { "text" } == "text") {
                val sourceMessageId = envelope?.optString("source_message_id")?.toLongOrNull()
                    ?: envelope?.optLong("source_message_id", 0L)?.takeIf { it > 0L }
                if (sourceMessageId != null) {
                    val preview = ChatHistoryStore.inspectIncoming(this, payload) ?: return
                    val response = envelope?.takeUnless { it.optBoolean("peer_chat") }?.let {
                        AgentRemoteOutcomeCodec.decode(it,
                            AgentRemoteOutcomeCodec.content(this, it, preview.content),
                            CodexStyleResponsePolicy.filterAssistantRichOutput(AgentRichContentCodec.fromEnvelope(it)))
                    }
                    if (response != null) {
                        if (AgentTerminalDeliveryStore.isTerminal(this, response.sourceMessageId) ||
                            !AgentConnectorResponseStore.isCurrentExecution(this, response)) return
                        val consumed = AndroidAgentResultRecovery.publishResult(this, envelope, response)
                        // Commit the exact versioned reply before optional voice projection or notification work.
                        if (VoiceFeatureFlags.isAgentVoiceRunBridgeEnabled(this)) {
                            VoiceAgentRunBridge.get(this).consumeLegacyFinal(
                                sourceMessageId = response.sourceMessageId,
                                taskId = response.taskId,
                                content = response.content
                            )
                        }
                        val controlPayload = AgentSupervisedProjectControlPayload
                            .isControlPayloadFragment(response.content)
                        if (consumed || controlPayload) return
                    }
                }
            }
            val stored = ChatHistoryStore.appendIncoming(this, payload) ?: return
            if (stored.notify) {
                showIncomingNotification(stored)
                ChatHistoryStore.markNotified(this, stored.contactId, stored.messageId)
            }
        } catch (error: Throwable) {
            handled = false
            Log.e("GalaxySSILink", "Deferred inbound message after background handling failure", error)
        } finally {
            if (handled) GalaxySSIMqttClient.completeIncomingDelivery(this, payload)
        }
    }

    private fun registerNetworkRecoveryCallback() {
        val manager = getSystemService(ConnectivityManager::class.java)
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                GalaxySSIMqttClient.connectAfterNetworkAvailable(this@MessageService)
            }

            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                if (capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) {
                    GalaxySSIMqttClient.connectAfterNetworkAvailable(this@MessageService)
                }
            }
        }
        if (runCatching { manager.registerDefaultNetworkCallback(callback) }.isSuccess) {
            networkRecoveryCallback = callback
        }
    }

    private fun unregisterNetworkRecoveryCallback() {
        val callback = networkRecoveryCallback ?: return
        networkRecoveryCallback = null
        runCatching {
            getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(callback)
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
        manager.createNotificationChannel(
            NotificationChannel(
                MESSAGE_CHANNEL_ID,
                getString(R.string.message_channel_name),
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = getString(R.string.message_channel_description)
                setShowBadge(true)
                enableVibration(true)
            }
        )
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
            .setContentTitle("GalaxySSI")
            .setContentText(getString(R.string.service_notification_content))
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setShowWhen(false)
            .build()
    }

    private fun showIncomingNotification(message: StoredIncomingMessage) {
        val firstAttachment = message.attachments.optJSONObject(0)
        val attachmentName = firstAttachment?.optString("name").orEmpty()
        val attachmentMime = firstAttachment?.optString("mime_type").orEmpty()
        val preview = message.content.ifBlank {
            when {
                attachmentMime.startsWith("audio/") -> getString(R.string.message_notification_voice)
                attachmentMime.startsWith("image/") -> getString(R.string.message_notification_photo)
                else -> attachmentName.ifBlank { getString(R.string.rich_output_type_file) }
            }
        }
        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("galaxyssi_open_contact_id", message.contactId)
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            message.contactId.hashCode(),
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = Notification.Builder(this, MESSAGE_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_tab_chat_filled)
            .setContentTitle(message.contactName)
            .setContentText(preview.take(120))
            .setStyle(Notification.BigTextStyle().bigText(preview))
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setShowWhen(true)
            .setCategory(Notification.CATEGORY_MESSAGE)
            .build()
        getSystemService(NotificationManager::class.java).notify(
            incomingMessageNotificationId(message.contactId),
            notification
        )
    }

    private fun showFriendRequestNotification(name: String) {
        val pendingIntent = PendingIntent.getActivity(
            this,
            "phone-contact-request".hashCode(),
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val displayName = name.ifBlank { getString(R.string.fallback_contact_name) }
        val notification = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_tab_chat_filled)
            .setContentTitle(getString(R.string.new_friends))
            .setContentText(getString(R.string.phone_contact_request_received, displayName))
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setShowWhen(true)
            .build()
        getSystemService(NotificationManager::class.java).notify(
            "phone-contact-request:$displayName".hashCode(),
            notification
        )
    }

    private fun showPhoneContactStatusNotification(name: String, approved: Boolean) {
        val displayName = name.ifBlank { getString(R.string.fallback_contact_name) }
        val pendingIntent = PendingIntent.getActivity(
            this,
            "phone-contact-status:$displayName:$approved".hashCode(),
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val content = getString(
            if (approved) {
                R.string.phone_contact_request_approved
            } else {
                R.string.phone_contact_request_rejected
            },
            displayName
        )
        val notification = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_tab_chat_filled)
            .setContentTitle(getString(R.string.new_friends))
            .setContentText(content)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setShowWhen(true)
            .build()
        getSystemService(NotificationManager::class.java).notify(
            "phone-contact-status:$displayName:$approved".hashCode(),
            notification
        )
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

    private fun handleDebugIncoming(intent: Intent?) {
        if ((applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) == 0) return
        val encodedPayload = intent?.getStringExtra("galaxyssi_debug_service_payload_b64")?.trim().orEmpty()
        val payload = if (encodedPayload.isNotBlank()) {
            runCatching {
                String(Base64.decode(encodedPayload, Base64.DEFAULT), Charsets.UTF_8).trim()
            }.getOrDefault("")
        } else {
            intent?.getStringExtra("galaxyssi_debug_service_payload")?.trim().orEmpty()
        }
        if (payload.isBlank()) return
        onMessage(payload)
    }

}
