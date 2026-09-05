package com.galaxyssi.chat

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.PowerManager
import android.os.StatFs
import android.os.SystemClock
import android.provider.AlarmClock
import android.provider.CalendarContract
import android.provider.ContactsContract
import android.util.Log
import com.galaxyssi.chat.voice.VoiceFeatureFlags
import com.galaxyssi.chat.voice.agent.VoiceAgentRunBridge
import com.galaxyssi.chat.voice.agent.VoiceAgentRunRequest
import com.galaxyssi.chat.voice.metrics.VoiceLatencyTraceContext
import com.galaxyssi.chat.voice.modelstream.ModelStreamEvent
import com.galaxyssi.chat.voice.modelstream.ModelStreamUiMerger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.util.Locale
import java.util.Date
import java.text.SimpleDateFormat
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit

internal const val INTERNAL_CONVERSATION_ID = "_galaxyssi_conversation_id"
internal const val INTERNAL_CONVERSATION_CONTEXT = "_galaxyssi_conversation_context"
internal const val INTERNAL_CONVERSATION_HAS_ATTACHMENTS = "_galaxyssi_conversation_has_attachments"
internal const val INTERNAL_TURN_ID = "_galaxyssi_turn_id"
internal const val INTERNAL_MEMORY_CONTEXT = "_galaxyssi_memory_context"
internal const val INTERNAL_CLOUD_KNOWLEDGE_CONTEXT = "_galaxyssi_cloud_knowledge_context"
internal const val INTERNAL_AGENT_KNOWLEDGE_CONTEXT = "_galaxyssi_agent_knowledge_context"
internal const val INTERNAL_SCREEN_CONTEXT = "_galaxyssi_screen_context"
internal const val INTERNAL_LONG_TERM_WRITE_ALLOWED = "_galaxyssi_long_term_write_allowed"
internal const val INTERNAL_TASK_EXECUTION_MODE = "_galaxyssi_task_execution_mode"
internal const val RUNTIME_CONTEXT_CACHE_TTL_MILLIS = 2_000L
internal const val MAX_AUDIT_ITEMS = 20
internal const val MAX_CONNECTOR_RESPONSE_CHARACTERS = 24_000
internal const val MAX_NATIVE_TOOL_EVIDENCE_CHARACTERS = 128 * 1_024
internal const val MAX_TASK_RESULT_CHARACTERS = 4_000
internal const val MAX_TASK_EXECUTION_LOG_ITEMS = 200
internal const val MAX_SPECIALIZED_ADAPTER_REPLANS = 8
internal const val MAX_PHONE_DEVELOPMENT_REPAIRS = 2
internal val ACTIVE_EXECUTION_PHASES = setOf(
    AgentPhase.PLANNING,
    AgentPhase.WAITING_CONFIRMATION,
    AgentPhase.EXECUTING,
    AgentPhase.VERIFYING,
    AgentPhase.WAITING_RESPONSE
)

internal inline fun <T> traceMobileAgentInitialization(label: String, block: () -> T): T {
    val startedAt = SystemClock.elapsedRealtime()
    return try {
        block()
    } finally {
        Log.i(
            "GalaxySSIMobileAgentInit",
            "$label=${SystemClock.elapsedRealtime() - startedAt}ms"
        )
    }
}

internal fun AgentAction.withDirectConversationContext(
    conversationContext: AgentConversationContext,
    turnId: String,
    goal: String,
    executionMode: AgentTaskExecutionMode
): AgentAction {
    return copy(
        parameters = parameters + mapOf(
        INTERNAL_CONVERSATION_ID to conversationContext.conversationId,
        INTERNAL_CONVERSATION_CONTEXT to conversationContext.asAgentTransportBlock(goal),
        INTERNAL_CONVERSATION_HAS_ATTACHMENTS to conversationContext.hasAttachments.toString(),
        INTERNAL_TURN_ID to turnId,
        INTERNAL_LONG_TERM_WRITE_ALLOWED to (!conversationContext.privateMode).toString(),
        INTERNAL_TASK_EXECUTION_MODE to executionModeWireValue(executionMode),
        "_galaxyssi_task_id" to turnId,
        "original_goal" to goal
    )
    )
}

internal fun AgentAction.executionModeWireValue(defaultMode: AgentTaskExecutionMode): String =
    parameters[INTERNAL_TASK_EXECUTION_MODE]
        ?.takeIf(String::isNotBlank)
        ?: defaultMode.wireValue

internal val SENSITIVE_MEMORY_TERMS = listOf(
    "password",
    "passcode",
    "verification code",
    "otp",
    "2fa",
    "bank card",
    "credit card",
    "cvv",
    "private key",
    "secret key",
    "access token",
    "api key",
    "seed phrase",
    "\u5bc6\u7801",
    "\u9a8c\u8bc1\u7801",
    "\u94f6\u884c\u5361",
    "\u79c1\u94a5",
    "\u652f\u4ed8"
)

internal fun renderPhoneWebSearchResult(output: AgentNativeJsonObject, zh: Boolean): String {
    val results = (output["results"] as? Iterable<*>)
        ?.mapNotNull { it as? Map<*, *> }
        ?.mapNotNull { row ->
            val title = row["title"]?.toString()?.trim().orEmpty()
            val url = row["url"]?.toString()?.trim().orEmpty()
            if (title.isBlank() || !url.startsWith("https://", ignoreCase = true)) null else title to url
        }
        .orEmpty()
    if (results.isEmpty()) {
        return if (zh) {
            "\u6ca1\u6709\u627e\u5230\u53ef\u8bfb\u7684\u7f51\u9875\u7ed3\u679c\u3002\u8bf7\u6362\u4e2a\u5173\u952e\u8bcd\u540e\u91cd\u8bd5\u3002"
        } else {
            "No readable web results were found. Try a more specific query."
        }
    }
    val heading = if (zh) "\u6700\u65b0\u641c\u7d22\u7ed3\u679c\uff1a" else "Latest web results:"
    val lines = results.take(6).map { (rawTitle, rawUrl) ->
        val title = rawTitle.replace("[", "\\[").replace("]", "\\]").take(240)
        val url = rawUrl.replace(")", "%29").take(2_048)
        "- [$title]($url)"
    }
    return heading + "\n" + lines.joinToString("\n")
}

internal fun renderPhoneWebIntelligenceResult(
    toolId: String,
    output: AgentNativeJsonObject,
    zh: Boolean
): String {
    if (toolId in setOf(
            AgentWebIntelligenceNativeTools.SEARCH,
            AgentWebIntelligenceNativeTools.FIND_SIMILAR
        )
    ) {
        return renderPhoneWebSearchResult(output, zh)
    }
    val documents = (output["documents"] as? Iterable<*>)
        ?.mapNotNull { it as? Map<*, *> }
        .orEmpty()
    val resultLinks = (output["results"] as? Iterable<*>)
        ?.mapNotNull { it as? Map<*, *> }
        .orEmpty()
    val links = (documents + resultLinks).mapNotNull { item ->
        val title = item["title"]?.toString()?.trim().orEmpty()
        val url = item["url"]?.toString()?.trim().orEmpty()
        if (title.isBlank() || !url.startsWith("https://", true)) null else title to url
    }.distinctBy { it.second }.take(8)
    return when (toolId) {
        AgentWebIntelligenceNativeTools.RESEARCH,
        AgentWebIntelligenceNativeTools.AGENT -> {
            if (links.isEmpty()) {
                if (zh) "\u6ca1\u6709\u6536\u96c6\u5230\u53ef\u9a8c\u8bc1\u7684\u516c\u5f00\u6765\u6e90\u3002"
                else "No verifiable public sources were collected."
            } else {
                val heading = if (zh) {
                    "\u5df2\u6536\u96c6 ${links.size} \u4e2a\u53ef\u5f15\u7528\u6765\u6e90\uff1a"
                } else {
                    "Collected ${links.size} citable sources:"
                }
                heading + "\n" + links.joinToString("\n") { (title, url) ->
                    "- [${title.replace("[", "\\[").replace("]", "\\]").take(240)}](${url.replace(")", "%29")})"
                }
            }
        }
        AgentWebIntelligenceNativeTools.FETCH,
        AgentWebIntelligenceNativeTools.EXTRACT,
        AgentWebIntelligenceNativeTools.CRAWL -> {
            val first = documents.firstOrNull()
            val content = first?.get("content")?.toString()?.trim().orEmpty().take(3_000)
            val heading = if (zh) {
                "\u5df2\u8bfb\u53d6 ${documents.size} \u4e2a\u7f51\u9875\u3002"
            } else {
                "Read ${documents.size} web page${if (documents.size == 1) "" else "s"}."
            }
            listOf(heading, content).filter(String::isNotBlank).joinToString("\n\n")
        }
        AgentWebIntelligenceNativeTools.DIFF -> {
            val delta = output["diff"] as? Map<*, *> ?: emptyMap<Any?, Any?>()
            val changed = delta["changed"] as? Boolean ?: false
            val summary = delta["summary"]?.toString()?.trim().orEmpty()
            if (zh) {
                if (changed) "\u9875\u9762\u5df2\u53d8\u66f4\u3002" + summary.takeIf(String::isNotBlank)?.let { "\n\n$it" }.orEmpty()
                else "\u9875\u9762\u5185\u5bb9\u6ca1\u6709\u53d8\u5316\u3002"
            } else {
                if (changed) "The page changed." + summary.takeIf(String::isNotBlank)?.let { "\n\n$it" }.orEmpty()
                else "The page has not changed."
            }
        }
        AgentWebIntelligenceNativeTools.WATCH -> {
            val action = (output["metadata"] as? Map<*, *>)?.get("action")?.toString().orEmpty()
            if (zh) "\u7f51\u9875\u76d1\u63a7\u64cd\u4f5c\u5df2\u5b8c\u6210\uff1a$action"
            else "Web watch operation completed: $action"
        }
        AgentWebIntelligenceNativeTools.CACHE -> {
            val cache = output["cache"] as? Map<*, *> ?: emptyMap<Any?, Any?>()
            val count = cache["entry_count"]?.toString().orEmpty().ifBlank { "0" }
            if (zh) "\u672c\u673a\u52a0\u5bc6\u7f13\u5b58\u4e2d\u6709 $count \u4e2a\u7f51\u9875\u3002"
            else "The encrypted local cache contains $count web pages."
        }
        else -> if (zh) "\u7f51\u7edc\u60c5\u62a5\u4efb\u52a1\u5df2\u5b8c\u6210\u3002" else "Web intelligence task completed."
    }
}

internal fun renderPackageUnavailable(packageName: String, zh: Boolean): String {
    val displayName = packageName.ifBlank { if (zh) "\u8be5\u5e94\u7528" else "that app" }
    return if (zh) {
        "\u6211\u5728\u8fd9\u53f0\u624b\u673a\u4e0a\u627e\u4e0d\u5230\u6216\u65e0\u6cd5\u8bbf\u95ee $displayName\u3002\n\n" +
            "\u8bf7\u68c0\u67e5\u5305\u540d\uff0c\u6216\u786e\u8ba4\u5e94\u7528\u5df2\u7ecf\u5b89\u88c5\u3002"
    } else {
        "I couldn't find or access $displayName on this phone.\n\n" +
            "Check the package name or confirm the app is installed."
    }
}

enum class AgentNativeToolLifecycleStage { STARTED, PROGRESS, FINISHED }

data class AgentNativeToolLifecycleEvent(
    val stage: AgentNativeToolLifecycleStage,
    val toolId: String,
    val invocationId: String,
    val stepId: String,
    val conversationId: String,
    val turnId: String,
    val status: AgentNativeToolResultStatus? = null,
    val progressStage: String = "",
    val message: String = "",
    val percent: Int? = null,
    val sequence: Long = 0L,
    val timestampMillis: Long = System.currentTimeMillis()
)

fun interface AgentNativeToolEventSink {
    fun onEvent(event: AgentNativeToolLifecycleEvent)

    companion object {
        val NONE = AgentNativeToolEventSink { }
    }
}

/**
 * Phone-native Agent runtime scaffold.
 *
 * The first implementation keeps every capability behind a small interface so
 * screen perception, local actions, memory, and remote agents can be upgraded
 * independently without turning MainActivity into the agent runtime.
 */
class MobileNativeAgent(
    context: Context,
    internal val perceptionProvider: ScreenPerceptionProvider = traceMobileAgentInitialization("perception_provider") {
        AndroidScreenPerceptionProvider(context)
    },
    planner: AgentPlanner? = null,
    internal val safetySettingsStore: AgentSafetySettingsStore = traceMobileAgentInitialization("safety_store") {
        SharedPreferencesAgentSafetySettingsStore(context)
    },
    internal val confirmationConsentStore: AgentConfirmationConsentStore =
        traceMobileAgentInitialization("confirmation_store") {
            SharedPreferencesAgentConfirmationConsentStore(context)
        },
    internal val safetyPolicy: AgentSafetyPolicy = UnrestrictedAgentSafetyPolicy(),
    internal val actionExecutor: AgentActionExecutor = traceMobileAgentInitialization("action_executor") {
        PhoneExecutionAuthority.guarded(
            NotifyingAgentActionExecutor(
                context,
                AgentControlPlaneActionExecutor(context, AndroidAgentActionExecutor(context))
            )
        )
    },
    internal val observationController: AgentContinuousObservationController = AgentContinuousObservationController(),
    internal val recoveryController: AgentActionRecoveryController = AgentActionRecoveryController(),
    internal val memoryStore: AgentMemoryStore = traceMobileAgentInitialization("memory_store") {
        EncryptedAgentMemoryStore(context)
    },
    internal val knowledgeStore: AgentKnowledgeStore = traceMobileAgentInitialization("knowledge_store") {
        SharedPreferencesAgentKnowledgeStore(context)
    },
    internal val taskStore: AgentTaskStore = traceMobileAgentInitialization("task_store") {
        SQLiteAgentTaskStore(context)
    },
    internal val workflowStore: AgentWorkflowStore = traceMobileAgentInitialization("workflow_store") {
        SharedPreferencesAgentWorkflowStore(context)
    },
    internal val workflowScheduleStore: AgentWorkflowScheduleStore = traceMobileAgentInitialization("workflow_schedule_store") {
        AgentWorkflowScheduleStore(context)
    },
    internal val workflowTriggerStore: AgentWorkflowTriggerStore = traceMobileAgentInitialization("workflow_trigger_store") {
        AgentWorkflowTriggerStore(context)
    },
    internal val workflowExecutionHistoryStore: AgentWorkflowExecutionHistoryStore = traceMobileAgentInitialization("workflow_history_store") {
        AgentWorkflowExecutionHistoryStore(context)
    },
    internal val connectorRegistry: AgentConnectorRegistry = traceMobileAgentInitialization("connector_registry") {
        AppStoreAgentConnectorRegistry(context)
    },
    internal val reputationLedger: AgentReputationLedger = traceMobileAgentInitialization("reputation_ledger") {
        AgentReputationLedger.encrypted(context)
    },
    internal val sessionStore: AgentSessionStore = traceMobileAgentInitialization("session_store") {
        SharedPreferencesAgentSessionStore(context)
    },
    internal val nativeToolEventSink: AgentNativeToolEventSink = AgentNativeToolEventSink.NONE,
    internal val screenObservationOverride: Boolean? = null,
    executionLoopEventSink: AgentExecutionLoopEventSink = AgentExecutionLoopEventSink.NONE,
    nativeToolRegistryProvider: (() -> AgentNativeToolRegistry)? = null
) {
    internal val appContext = context.applicationContext
    internal val preferenceModeStore = traceMobileAgentInitialization("preference_store") {
        AgentPreferenceModeStore(appContext)
    }
    internal val modelPlannerSettingsStore: AgentModelPlannerSettingsStore by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        AgentModelPlannerSettingsStore(appContext)
    }
    internal var activePreferenceMode: AgentPreferenceMode = traceMobileAgentInitialization("preference_load") {
        preferenceModeStore.load()
    }
    internal var sessionId: String = UUID.randomUUID().toString()
    internal var activeConversationContext: AgentConversationContext = AgentConversationContext("", "", emptyList(), false)
    internal var activeConversationTurnId: String = ""
    internal var activeRequestedMembers: List<AgentRequestedMember> = emptyList()
    internal var activeTaskExecutionMode: AgentTaskExecutionMode =
        traceMobileAgentInitialization("safety_load") {
            safetySettingsStore.load().taskExecutionMode
        }
    @Volatile internal var phase: AgentPhase = AgentPhase.OBSERVING
    internal var currentGoal: String = ""
    internal var currentScreen: ScreenContext = ScreenContext(foregroundApp = "", pageTitle = "")
    internal var currentPlan: AgentPlan? = null
    internal var lastActionResult: AgentActionResult? = null
    internal var activeWorkflowExecutionId: String? = null
    internal var executionLoop = AgentExecutionLoop.create()
    internal var executionLoopEventSink: AgentExecutionLoopEventSink = executionLoopEventSink
    internal val activeNativeToolCancellationSources = linkedSetOf<AgentNativeToolCancellationSource>()
    @Volatile internal var activeNativeToolCancellationReason: String = ""
    internal val auditTrail = mutableListOf<AgentAuditEntry>()
    @Volatile internal var cachedRuntimeContext: AgentRuntimeContext? = null
    @Volatile internal var cachedRuntimeContextAtElapsedMillis: Long = 0L
    @Volatile internal var activeRunRuntimeContext: AgentRuntimeContext? = null
    internal val taskPersistenceGate = AgentTaskPersistenceGate()
    internal val nativeToolRegistry: AgentNativeToolRegistry by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        nativeToolRegistryProvider?.invoke()
            ?: AgentPhoneNativeToolCatalog.defaultRegistry(
                context = appContext,
                screenProvider = { currentScreen },
                actionExecutor = actionExecutor
            )
    }
    internal val planner: AgentPlanner by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        planner ?: traceMobileAgentInitialization("planner") {
            GuardedModelAgentPlanner(
                context = appContext,
                nativeToolRegistryProvider = { nativeToolRegistry }
            )
        }
    }

    init {
        AgentAdaptiveConcurrencyRuntime.initialize(appContext)
        traceMobileAgentInitialization("session_restore") {
            restoreSession(sessionStore.load())
        }
    }











































    @Synchronized
    fun acceptConnectorResponse(
        sourceMessageId: Long,
        contactId: String,
        content: String,
        success: Boolean = true,
        richOutputJson: String = "",
        conversationId: String = "",
        turnId: String = "",
        taskId: String = "",
        inputTokens: Long = 0L,
        outputTokens: Long = 0L,
        costMicros: Long = 0L,
        networkBytes: Long = 0L,
        expectedSourceMessageId: Long = sourceMessageId,
        providerAttempts: AgentProviderAttemptReport? = null
    ): AgentUiState? = acceptConnectorResponseInternal(
        sourceMessageId,
        contactId,
        content,
        success,
        richOutputJson,
        conversationId,
        turnId,
        taskId,
        inputTokens,
        outputTokens,
        costMicros,
        networkBytes,
        expectedSourceMessageId,
        providerAttempts
    )?.let(::reconcileExecutionLoop)


    @Synchronized
    fun acceptConnectorSteered(
        sourceMessageId: Long,
        contactId: String,
        mergedIntoTaskId: String,
        conversationId: String = "",
        turnId: String = "",
        taskId: String = ""
    ): AgentUiState? {
        if (sourceMessageId <= 0L || mergedIntoTaskId.isBlank()) return null
        val pendingResult = lastActionResult ?: return null
        if (phase != AgentPhase.WAITING_RESPONSE) return null
        if (pendingResult.metadata["source_message_id"]?.toLongOrNull() != sourceMessageId) return null
        val expectedContactId = pendingResult.metadata["contact_id"].orEmpty()
        if (expectedContactId.isNotBlank() && contactId.isNotBlank() && expectedContactId != contactId) return null
        if (!AgentTaskIdentityPolicy.matchesDesktopResponse(
                pendingResult.metadata,
                conversationId,
                taskId,
                turnId
            )
        ) return null
        val plan = currentPlan ?: return null
        val actionId = pendingResult.actionId
        if (!advanceExecutionLoop(
                nextPhase = AgentExecutionLoopPhase.OBSERVE,
                reason = "Remote task update merged into the active turn",
                actionId = actionId
            )
        ) {
            return snapshot()
        }
        val completedResult = AgentActionResult(
            actionId = actionId,
            success = true,
            message = "",
            metadata = pendingResult.metadata - setOf(
                "timeout_stage",
                "timeout_elapsed_ms"
            ) + mapOf(
                "awaiting_response" to "false",
                "response_received_at" to System.currentTimeMillis().toString(),
                "connector_disposition" to "steered",
                "merged_into_task_id" to mergedIntoTaskId
            )
        )
        val continuedPlan = AgentPlanLifecyclePolicy.normalize(
            plan.markAction(actionId, AgentActionStatus.COMPLETED, completedResult)
        ).plan
        currentPlan = continuedPlan
        lastActionResult = completedResult
        val hasPendingActions = continuedPlan.actions.any {
            it.status == AgentActionStatus.PENDING_CONFIRMATION || it.status == AgentActionStatus.PROPOSED
        }
        phase = when {
            safetySettingsStore.load().executionPaused -> AgentPhase.PAUSED
            continuedPlan.safetyReview.blocked -> AgentPhase.BLOCKED
            hasPendingActions && continuedPlan.safetyReview.requiresConfirmation -> AgentPhase.WAITING_CONFIRMATION
            hasPendingActions -> AgentPhase.EXECUTING
            else -> AgentPhase.COMPLETED
        }
        recordAudit(
            AgentAuditEvent.CONNECTOR_RESPONSE_RECEIVED,
            "source_message_id=$sourceMessageId; contact=$contactId; disposition=steered"
        )
        saveTaskRecord()
        return if (phase == AgentPhase.EXECUTING) executeFirstPendingAction() else snapshot()
    }





    @Synchronized
    fun recordConnectorTaskStatus(
        sourceMessageId: Long,
        contactId: String,
        taskId: String,
        taskStatus: String,
        statusSeq: Long,
        conversationId: String = "",
        turnId: String = "",
        executionGeneration: Long = 1L
    ): AgentUiState? {
        if (!canAcceptConnectorResponse(
                sourceMessageId,
                contactId,
                conversationId,
                turnId,
                taskId
            ) || taskId.isBlank()
        ) return null
        val pendingResult = lastActionResult ?: return null
        val previousGeneration = pendingResult.metadata["remote_execution_generation"]?.toLongOrNull() ?: 1L
        if (executionGeneration < previousGeneration) return snapshot()
        val previousSeq = if (executionGeneration > previousGeneration) -1L
            else pendingResult.metadata["remote_task_status_seq"]?.toLongOrNull() ?: -1L
        if (statusSeq >= 0L && statusSeq < previousSeq) return snapshot()
        val now = System.currentTimeMillis()
        if (AgentRemoteTaskStatusPolicy.keepsResourceHealthy(taskStatus)) {
            pendingResult.metadata["failure_domain"].orEmpty().takeIf(String::isNotBlank)?.let { domain ->
                AgentResourceHealthStore(appContext).markAvailable("domain:$domain")
            }
        }
        lastActionResult = pendingResult.copy(
            metadata = pendingResult.metadata + mapOf(
                "remote_task_id" to taskId,
                "remote_task_status" to AgentRemoteTaskStatusPolicy.normalize(taskStatus),
                "remote_execution_generation" to executionGeneration.toString(),
                "remote_task_status_seq" to maxOf(previousSeq, statusSeq).toString(),
                "remote_task_status_updated_at" to now.toString()
            )
        )
        saveTaskRecord()
        return snapshot()
    }

    @Synchronized
    fun acceptConnectorTerminalStatus(
        sourceMessageId: Long,
        contactId: String,
        taskId: String,
        taskStatus: String,
        statusSeq: Long,
        message: String,
        conversationId: String = "",
        turnId: String = "",
        expectedSourceMessageId: Long = sourceMessageId,
        executionGeneration: Long = 1L,
        canonicalReply: Boolean = false
    ): AgentUiState? {
        val normalizedStatus = AgentRemoteTaskStatusPolicy.normalize(taskStatus)
        if (!AgentRemoteTaskStatusPolicy.settlesWithoutResponse(normalizedStatus)) return null
        if (!canAcceptConnectorResponse(
                sourceMessageId = expectedSourceMessageId,
                contactId = contactId,
                conversationId = conversationId,
                turnId = turnId,
                taskId = taskId
            ) || taskId.isBlank()
        ) return null
        val pending = lastActionResult ?: return null
        val previousGeneration = pending.metadata["remote_execution_generation"]?.toLongOrNull() ?: 1L
        if (executionGeneration < previousGeneration) return snapshot()
        val previousSeq = if (executionGeneration > previousGeneration) -1L
            else pending.metadata["remote_task_status_seq"]?.toLongOrNull() ?: -1L
        if (!canonicalReply && statusSeq >= 0L && statusSeq < previousSeq) return snapshot()
        val plan = currentPlan ?: return null
        val now = System.currentTimeMillis()
        val elapsed = (
            now - (pending.metadata["resource_started_at"]?.toLongOrNull() ?: now)
            ).coerceAtLeast(0L)
        val terminalMessage = message.trim().ifBlank {
            when (normalizedStatus) {
                "cancelled" -> "The remote task was cancelled."
                "timed_out" -> "The remote task timed out."
                "not_found" -> "The remote task is no longer available."
                else -> "The remote task failed."
            }
        }
        val timeoutStage = AgentRemoteTaskStatusPolicy.timeoutStage(normalizedStatus)
        val terminalMetadata = pending.metadata + buildMap {
            put("awaiting_response", "false")
            put("remote_task_id", taskId)
            put("remote_task_status", normalizedStatus)
            put("remote_execution_generation", executionGeneration.toString())
            put("remote_task_status_seq", maxOf(previousSeq, statusSeq).toString())
            put("remote_task_status_updated_at", now.toString())
            put("remote_task_terminal_at", now.toString())
            if (timeoutStage.isNotBlank()) {
                put("timeout_stage", timeoutStage)
                put("timeout_elapsed_ms", elapsed.toString())
            }
        }
        val failed = pending.copy(
            success = false,
            message = terminalMessage,
            metadata = terminalMetadata
        )
        if (normalizedStatus == "cancelled") {
            lastActionResult = failed
            currentPlan = plan.markAction(failed.actionId, AgentActionStatus.FAILED, failed)
            phase = AgentPhase.CANCELLED
            recordAudit(
                AgentAuditEvent.TASK_CANCELLED,
                "remote_task_id=$taskId; source_message_id=$sourceMessageId"
            )
            saveTaskRecord(result = terminalMessage)
            return reconcileExecutionLoop(snapshot())
        }
        val resourceId = pending.metadata["resource_id"].orEmpty().ifBlank { contactId }
        val failureDomain = pending.metadata["failure_domain"].orEmpty()
        val health = AgentResourceHealthStore(appContext)
        if (resourceId.isNotBlank()) health.record("target:$resourceId", false, elapsed)
        if (failureDomain.isNotBlank()) {
            // Receiving this terminal status proves that the remote transport is alive.
            health.markAvailable("domain:$failureDomain")
        }
        val withinFailureBudget = recordExecutionFailure(
            failureClass = "connector:$resourceId",
            reason = terminalMessage,
            actionId = failed.actionId
        )
        if (!withinFailureBudget) {
            val budgetMessage = lastActionResult?.message.orEmpty().ifBlank { terminalMessage }
            val budgetFailure = failed.copy(message = budgetMessage)
            lastActionResult = budgetFailure
            currentPlan = plan.markAction(
                budgetFailure.actionId,
                AgentActionStatus.FAILED,
                budgetFailure
            )
            phase = AgentPhase.FAILED
            saveTaskRecord(result = budgetMessage)
            return reconcileExecutionLoop(snapshot())
        }
        continueWithConnectorFallback(plan, failed)?.let { return it }
        lastActionResult = failed
        currentPlan = plan.markAction(failed.actionId, AgentActionStatus.FAILED, failed)
        phase = AgentPhase.FAILED
        recordAudit(
            AgentAuditEvent.INVOCATION_AUDIT,
            "remote_terminal:$normalizedStatus:task=$taskId:source=$sourceMessageId"
        )
        saveTaskRecord(result = terminalMessage)
        return reconcileExecutionLoop(snapshot())
    }


    @Synchronized
    fun handleConnectorTimeout(
        sourceMessageId: Long,
        stage: AgentConnectorTimeoutStage
    ): AgentUiState? {
        if (sourceMessageId <= 0L || phase != AgentPhase.WAITING_RESPONSE) return null
        val pending = AgentProviderAttemptJournal.recover(appContext, lastActionResult ?: return null)
        if (pending.metadata["source_message_id"]?.toLongOrNull() != sourceMessageId) return null
        val status = pending.metadata["remote_task_status"].orEmpty()
        val liveReadOnly = pending.metadata["routing_requires_live_data"] == "true"
        val fallbackIds = pending.metadata["remaining_fallback_ids"].orEmpty()
            .split(',')
            .map(String::trim)
            .filter(String::isNotBlank)
            .distinct()
        val failureDomain = pending.metadata["failure_domain"].orEmpty()
        val timeoutMetadata = pending.metadata + ("timeout_stage" to stage.name)
        val viableFallbackIds = fallbackIds.filter { fallbackId ->
            AgentConnectorFailureScope.permitsFallback(timeoutMetadata, connectorFailureDomain(fallbackId))
        }
        if (AgentFailoverPolicy.shouldKeepOnlyResourceAlive(stage, status, viableFallbackIds.isNotEmpty())) {
            return null
        }
        val timedOut = AgentFailoverPolicy.shouldFailOver(stage, status, liveReadOnly)
        if (!timedOut) return null
        val targetId = pending.metadata["resource_id"].orEmpty()
        if (stage == AgentConnectorTimeoutStage.READ_ONLY_STALE) {
            val hasDifferentDomainFallback = viableFallbackIds.isNotEmpty()
            if (!hasDifferentDomainFallback) return null
        }
        val elapsed = (System.currentTimeMillis() - (pending.metadata["resource_started_at"]?.toLongOrNull()
            ?: System.currentTimeMillis())).coerceAtLeast(0L)
        val health = AgentResourceHealthStore(appContext)
        if (targetId.isNotBlank()) health.record("target:$targetId", false, elapsed)
        if (failureDomain.isNotBlank() && AgentConnectorFailureScope.sharedTransportFailed(timeoutMetadata)) {
            health.recordFailureDomainTimeout("domain:$failureDomain", elapsed)
        }
        val failed = pending.copy(
            success = false,
            message = "${pending.metadata["target"].orEmpty().ifBlank { "Selected resource" }} timed out",
            metadata = pending.metadata + mapOf(
                "awaiting_response" to "false",
                "timeout_stage" to stage.name,
                "timeout_elapsed_ms" to elapsed.toString()
            )
        )
        recordAudit(
            AgentAuditEvent.INVOCATION_AUDIT,
            "connector_timeout:$stage:resource=$targetId:domain=$failureDomain:elapsed_ms=$elapsed"
        )
        val plan = currentPlan ?: return null
        AgentCloudDispatchRegistry.cancel(pending)
        continueWithConnectorFallback(plan, failed)?.let { return it }
        lastActionResult = failed
        currentPlan = plan.markAction(failed.actionId, AgentActionStatus.FAILED, failed)
        phase = AgentPhase.FAILED
        saveTaskRecord(result = failed.message)
        return reconcileExecutionLoop(snapshot())
    }

    @Synchronized
    fun handleConnectorDeliveryFailure(sourceMessageId: Long, message: String): AgentUiState? {
        if (sourceMessageId <= 0L || phase != AgentPhase.WAITING_RESPONSE) return null
        val pending = lastActionResult ?: return null
        if (pending.metadata["source_message_id"]?.toLongOrNull() != sourceMessageId) return null
        val failed = pending.copy(
            success = false,
            message = message,
            metadata = pending.metadata + mapOf(
                "awaiting_response" to "false",
                "delivery_failed" to "true"
            )
        )
        val plan = currentPlan ?: return null
        recordAudit(
            AgentAuditEvent.INVOCATION_AUDIT,
            "connector_delivery_failed:source=$sourceMessageId"
        )
        return recoverAfterConnectorDeliveryFailure(plan, failed)
    }

    @Synchronized
    fun forceTaskTimeout(message: String): AgentUiState {
        if (phase in setOf(
                AgentPhase.COMPLETED,
                AgentPhase.FAILED,
                AgentPhase.CANCELLED,
                AgentPhase.BLOCKED
            )
        ) return snapshot()
        val now = System.currentTimeMillis()
        val reason = message.trim().ifBlank { "The task timed out." }
        val pending = lastActionResult
        AgentCloudDispatchRegistry.cancel(pending)
        val failed = AgentActionResult(
            actionId = pending?.actionId.orEmpty().ifBlank { "agent-task-timeout" },
            success = false,
            message = reason,
            metadata = pending?.metadata.orEmpty() + mapOf(
                "awaiting_response" to "false",
                "timeout_stage" to "TASK_WATCHDOG",
                "remote_task_status" to "timed_out",
                "remote_task_terminal_at" to now.toString()
            )
        )
        lastActionResult = failed
        currentPlan = currentPlan?.markAction(
            failed.actionId,
            AgentActionStatus.FAILED,
            failed
        )
        phase = AgentPhase.FAILED
        recordAudit(AgentAuditEvent.INVOCATION_AUDIT, "task_watchdog_timeout")
        saveTaskRecord(result = reason)
        return reconcileExecutionLoop(snapshot())
    }


































































































































































}
