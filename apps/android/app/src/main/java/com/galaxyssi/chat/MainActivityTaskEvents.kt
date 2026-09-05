package com.galaxyssi.chat

import android.app.Activity
import android.app.AlertDialog
import android.app.DownloadManager
import android.app.Dialog
import android.app.NotificationManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.database.Cursor
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.drawable.AnimatedImageDrawable
import android.graphics.drawable.GradientDrawable
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaRecorder
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.OpenableColumns
import android.provider.MediaStore
import android.provider.Settings
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.text.Editable
import android.text.InputType
import android.text.SpannableString
import android.text.Spanned
import android.text.TextPaint
import android.text.TextUtils
import android.text.TextWatcher
import android.text.style.CharacterStyle
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.text.style.UpdateAppearance
import android.util.Base64
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.*
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.zxing.BarcodeFormat
import com.google.zxing.integration.android.IntentIntegrator
import com.google.zxing.qrcode.QRCodeWriter
import com.rementia.openwakeword.lib.WakeWordEngine
import com.rementia.openwakeword.lib.model.DetectionMode
import com.rementia.openwakeword.lib.model.WakeWordModel
import com.galaxyssi.chat.GalaxySSIMqttClient.Listener
import com.galaxyssi.chat.ui.AgentComposerUiPolicy
import com.galaxyssi.chat.ui.AppleHoldToTalkController
import com.galaxyssi.chat.ui.VoiceWaveformView
import com.galaxyssi.chat.voice.TranscriptHypothesis
import com.galaxyssi.chat.voice.VoiceFailure
import com.galaxyssi.chat.voice.VoiceFeatureFlags
import com.galaxyssi.chat.voice.VoiceInteractionCommand
import com.galaxyssi.chat.voice.VoiceInteractionCoordinator
import com.galaxyssi.chat.voice.VoiceInteractionCoordinatorRegistry
import com.galaxyssi.chat.voice.VoiceInteractionEvent
import com.galaxyssi.chat.voice.VoiceInteractionPhase
import com.galaxyssi.chat.voice.VoiceRouteDecision
import com.galaxyssi.chat.voice.VoiceRouteKind
import com.galaxyssi.chat.voice.VoiceSessionConfig
import com.galaxyssi.chat.voice.VoiceTtsRequest
import com.galaxyssi.chat.voice.VoiceTtsRequestRegistry
import com.galaxyssi.chat.voice.audio.AdaptiveEndpointConfig
import com.galaxyssi.chat.voice.audio.AndroidPcmRecorder
import com.galaxyssi.chat.voice.audio.DirectPcmFramePacket
import com.galaxyssi.chat.voice.audio.EndpointReason
import com.galaxyssi.chat.voice.audio.PcmCaptureConfig
import com.galaxyssi.chat.voice.audio.PcmSnapshot
import com.galaxyssi.chat.voice.audio.PcmStopReason
import com.galaxyssi.chat.voice.audio.PcmWaveFileAdapter
import com.galaxyssi.chat.voice.audio.VadDecision
import com.galaxyssi.chat.voice.audio.VoiceAudioHub
import com.galaxyssi.chat.voice.audio.VoiceAudioHubListener
import com.galaxyssi.chat.voice.asr.AsrNetworkType
import com.galaxyssi.chat.voice.asr.AsrProviderSelector
import com.galaxyssi.chat.voice.asr.AsrSessionConfig
import com.galaxyssi.chat.voice.asr.VoiceRecognitionPreference
import com.galaxyssi.chat.voice.asr.online.CachingRealtimeAsrCredentialSource
import com.galaxyssi.chat.voice.asr.online.HttpRealtimeAsrCredentialSource
import com.galaxyssi.chat.voice.asr.online.OnlineAsrCompletion
import com.galaxyssi.chat.voice.asr.online.OnlineRealtimeAsrTurn
import com.galaxyssi.chat.voice.asr.online.RealtimeAsrPreconnector
import com.galaxyssi.chat.voice.asr.online.RealtimeAsrProvider
import com.galaxyssi.chat.voice.asr.online.RealtimeAsrTurnAction
import com.galaxyssi.chat.voice.asr.remote.RemoteWhisperNodeClient
import com.galaxyssi.chat.voice.asr.remote.RemoteWhisperNodeRegistry
import com.galaxyssi.chat.voice.asr.remote.RemoteWhisperRoutingPolicy
import com.galaxyssi.chat.voice.asr.remote.GalaxySSILinkRemoteWhisperTransport
import com.galaxyssi.chat.voice.asr.local.AbortReason
import com.galaxyssi.chat.voice.asr.local.AsrConfig as HighAccuracyAsrConfig
import com.galaxyssi.chat.voice.asr.local.AsrEvent as HighAccuracyAsrEvent
import com.galaxyssi.chat.voice.asr.local.AsrPerformanceMode
import com.galaxyssi.chat.voice.asr.local.DefaultWhisperDecodeScheduler
import com.galaxyssi.chat.voice.asr.local.HighAccuracyAsrResult
import com.galaxyssi.chat.voice.asr.local.AsrTranscriptCompletenessPolicy
import com.galaxyssi.chat.voice.asr.local.HighAccuracyLocalAsrController
import com.galaxyssi.chat.voice.asr.local.HighAccuracyLocalAsrTurn
import com.galaxyssi.chat.voice.asr.local.LiveWhisperTranscriptionSession
import com.galaxyssi.chat.voice.asr.local.LiveWhisperTranscriptUpdate
import com.galaxyssi.chat.voice.asr.local.NativeWhisperCode
import com.galaxyssi.chat.voice.asr.local.LargeTurboQnnModelAction
import com.galaxyssi.chat.voice.asr.local.LargeTurboQnnModelManager
import com.galaxyssi.chat.voice.asr.local.LargeTurboQnnModelStatus
import com.galaxyssi.chat.voice.asr.local.QnnAsrEligibility
import com.galaxyssi.chat.voice.asr.local.QnnModelDownloadNetworkPolicy
import com.galaxyssi.chat.voice.asr.local.QnnWhisperPackageManager
import com.galaxyssi.chat.voice.asr.local.QnnWhisperPackageStatus
import com.galaxyssi.chat.voice.asr.local.WhisperDecodeScheduler
import com.galaxyssi.chat.voice.asr.local.largeTurboQnnModelAction
import com.galaxyssi.chat.voice.benchmark.WhisperBenchmarkManager
import com.galaxyssi.chat.voice.benchmark.WhisperBenchmarkDeferredException
import com.galaxyssi.chat.voice.benchmark.WhisperBenchmarkProgress
import com.galaxyssi.chat.voice.benchmark.WhisperBenchmarkRecord
import com.galaxyssi.chat.voice.benchmark.WhisperBenchmarkStage
import com.galaxyssi.chat.voice.benchmark.WhisperProviderChoice
import com.galaxyssi.chat.voice.benchmark.WhisperUserVoiceMode
import com.galaxyssi.chat.voice.correction.AndroidVoiceExecutionRecordStore
import com.galaxyssi.chat.voice.correction.CorrectionDecision
import com.galaxyssi.chat.voice.correction.DefaultVoiceCommandRiskClassifier
import com.galaxyssi.chat.voice.correction.TranscriptDiff
import com.galaxyssi.chat.voice.correction.VoiceCommandRisk
import com.galaxyssi.chat.voice.correction.VoiceCorrectionContextRecord
import com.galaxyssi.chat.voice.correction.VoiceCorrectionJournal
import com.galaxyssi.chat.voice.correction.VoiceExecutionLedger
import com.galaxyssi.chat.voice.correction.VoiceEntityType
import com.galaxyssi.chat.voice.correction.VoiceSecondPassCoordinator
import com.galaxyssi.chat.voice.correction.VoiceSecondPassRequest
import com.galaxyssi.chat.voice.correction.VoiceSecondPassResult
import com.galaxyssi.chat.voice.correction.VoiceSecondPassTriggerPolicy
import com.galaxyssi.chat.voice.audio.VoiceAudioSession
import com.galaxyssi.chat.voice.audio.VoiceAudioSessionConfig
import com.galaxyssi.chat.voice.agent.VoiceAgentEvent
import com.galaxyssi.chat.voice.agent.VoiceAgentRunBridge
import com.galaxyssi.chat.voice.agent.VoiceAgentRunListener
import com.galaxyssi.chat.voice.agent.VoiceAgentRunSnapshot
import com.galaxyssi.chat.voice.agent.VoiceAgentRunState
import com.galaxyssi.chat.voice.agent.VoiceAgentRunUpdate
import com.galaxyssi.chat.voice.metrics.VoiceLatencyTelemetry
import com.galaxyssi.chat.voice.metrics.VoiceLatencyTraceContext
import com.galaxyssi.chat.voice.metrics.VoiceTraceEvents
import com.galaxyssi.chat.voice.model.WhisperExecutionMode
import com.galaxyssi.chat.voice.model.WhisperCertificationLevel
import com.galaxyssi.chat.voice.model.WhisperMemoryAdmissionPolicy
import com.galaxyssi.chat.voice.model.WhisperModelFamily
import com.galaxyssi.chat.voice.model.WhisperModelFallbackPolicy
import com.galaxyssi.chat.voice.reliability.AndroidVoiceReliabilityController
import com.galaxyssi.chat.voice.reliability.VoicePipelineFeature
import com.galaxyssi.chat.voice.reliability.VoicePerformanceHealth
import com.galaxyssi.chat.voice.reliability.VoiceResourceMode
import com.galaxyssi.chat.voice.reliability.VoiceWorkloadProfile
import com.galaxyssi.chat.voice.modelstream.ModelStreamCancelReason
import com.galaxyssi.chat.voice.modelstream.CommittedSpeechChunk
import com.galaxyssi.chat.voice.modelstream.DefaultSentenceCommitter
import com.galaxyssi.chat.voice.modelstream.ModelStreamEvent
import com.galaxyssi.chat.voice.modelstream.ModelStreamUiMerger
import com.galaxyssi.chat.voice.modelstream.ModelStreamUiUpdate
import com.galaxyssi.chat.voice.modelstream.ModelUsage
import com.galaxyssi.chat.voice.modelstream.SentenceCommitter
import com.galaxyssi.chat.voice.tts.BargeInActions
import com.galaxyssi.chat.voice.tts.BargeInController
import com.galaxyssi.chat.voice.tts.BargeInTaskKind
import com.galaxyssi.chat.voice.tts.ProgressiveTtsUtteranceRegistry
import com.galaxyssi.chat.voice.tts.ProgressiveTtsUtteranceRequest
import com.galaxyssi.chat.voice.tts.TtsCancelReason
import com.galaxyssi.chat.voice.tts.TtsChunkPlayback
import com.galaxyssi.chat.voice.tts.TtsChunkPlaybackCallbacks
import com.galaxyssi.chat.voice.tts.TtsChunkPlayer
import com.galaxyssi.chat.voice.tts.TtsChunkScheduler
import com.galaxyssi.chat.voice.tts.TtsChunkSchedulerCallbacks
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.Executors
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import okhttp3.OkHttpClient
import kotlin.concurrent.thread
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sin

internal fun MainActivity.handleAgentTaskEvent(envelope: JSONObject?): Boolean {
    if (envelope?.optString("type") != "agent_task_event") return false
    val executionVersion = AgentRemoteOutcomeCodec.version(envelope) ?: return true
    AgentRemoteOutcomeCodec.observation(envelope)?.let {
        if (!AgentConnectorResponseStore.isCurrentExecution(this, it)) return true
    }
    if (VoiceFeatureFlags.isAgentVoiceRunBridgeEnabled(this) && isVoiceAgentRunBridgeInitialized()) {
        voiceAgentRunBridge.consumeRemoteEnvelope(envelope)
    }
    val perfStartedAt = SystemClock.elapsedRealtime()
    var perfCheckpointAt = perfStartedAt
    fun traceTaskEvent(stage: String) {
        if ((applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) == 0) return
        val now = SystemClock.elapsedRealtime()
        val stepMillis = now - perfCheckpointAt
        if (stepMillis >= AGENT_TRANSCRIPT_PERF_LOG_THRESHOLD_MS) {
            Log.d(
                "GalaxySSIPerf",
                "task_event stage=$stage status=${envelope.optString("task_status")} " +
                    "task=${envelope.optString("task_id").take(8)} step_ms=$stepMillis " +
                    "total_ms=${now - perfStartedAt}"
            )
        }
        perfCheckpointAt = now
    }
    val sourceMessageId = envelope.optString("source_message_id").toLongOrNull()
        ?: envelope.optLong("source_message_id", 0L).takeIf { it > 0L }
        ?: return true
    val contactId = envelope.optString("contact_id").takeIf { it.isNotBlank() }
        ?: selectedContact?.id
        ?: return true
    markDesktopDomainAvailable(contactId)
    if (sourceMessageId in supersededConnectorSourceIds) return true
    val status = AgentRemoteTaskStatusPolicy.normalize(envelope.optString("task_status"))
    val voiceTraceId = envelope.optString("trace_id")
    val envelopeTurnId = envelope.optString("turn_id")
    val coordinatorSessionId = voiceCoordinatorSession(voiceTraceId).ifBlank {
        voiceCoordinatorIdsByTurn[envelopeTurnId].orEmpty()
    }.ifBlank {
        voiceCoordinatorIdsBySourceMessage[sourceMessageId].orEmpty()
    }
    if (voiceTraceId.isNotBlank()) {
        activeVoiceTraceId = voiceTraceId
        val provider = envelope.optString("agent_id", "remote_agent")
        when (status) {
            "accepted" -> {
                VoiceLatencyTelemetry.record(
                    this,
                    voiceTraceId,
                    VoiceTraceEvents.AGENT_RUN_ACCEPTED,
                    mapOf("agent_provider" to provider),
                    once = true
                )
            }
            "completed", "failed", "cancelled", "timed_out" -> {
                VoiceLatencyTelemetry.record(
                    this,
                    voiceTraceId,
                    VoiceTraceEvents.AGENT_COMPLETED,
                    mapOf(
                        "agent_provider" to provider,
                        "task_status" to status,
                        "success" to (status == "completed").toString()
                    ),
                    once = true
                )
            }
        }
    }
    if (coordinatorSessionId.isNotBlank()) {
        when (status) {
            "accepted" -> dispatchVoiceCoordinator(
                VoiceInteractionEvent.AgentAccepted(
                    coordinatorSessionId,
                    envelope.optString("task_id")
                )
            )
            "completed" -> dispatchVoiceCoordinator(
                VoiceInteractionEvent.Completed(coordinatorSessionId)
            )
            "cancelled" -> dispatchVoiceCoordinator(
                VoiceInteractionEvent.Cancelled(coordinatorSessionId, "remote_agent_cancelled")
            )
            "failed", "timed_out" -> dispatchVoiceCoordinator(
                VoiceInteractionEvent.Failed(
                    coordinatorSessionId,
                    VoiceFailure(
                        code = "agent_$status",
                        recoverable = true,
                        stage = voiceInteractionCoordinator.snapshot().phase
                    )
                )
            )
        }
    }
    val taskDisposition = envelope.optString("task_disposition")
    val isSteeredCompletion = status == "completed" && taskDisposition == "steered"
    val taskId = envelope.optString("task_id")
    val completionKey = AgentRemoteOutcomeCodec.taskKey(taskId, executionVersion.generation)
    val envelopeConversationId = envelope.optString("conversation_id")
    if (status in setOf("completed", "failed", "cancelled", "timed_out")) {
        AgentGlobalRunSlotStore(this).releaseBySourceMessageId(sourceMessageId)
        if (envelopeTurnId.isNotBlank()) {
            voiceTraceIdsByTurn.remove(envelopeTurnId)
            voiceCoordinatorIdsByTurn.remove(envelopeTurnId)
        }
        voiceCoordinatorIdsBySourceMessage.remove(sourceMessageId)
    }
    if (completionKey in completedConnectorTaskIds &&
        AgentRemoteTaskStatusPolicy.settlesWithoutResponse(status)
    ) {
        return true
    }
    if (AgentTaskEventRoutingPolicy.isManaged(envelope, sourceMessageId, contactId)) {
        updateAgentRegistryTaskHeartbeat(contactId, status)
        publishAgentTaskPartialResult(envelope, sourceMessageId, contactId, status)
        if (status == "waiting_approval") {
            syncRemoteAgentApproval(
                envelope = envelope,
                conversationId = envelopeConversationId,
                turnId = envelopeTurnId,
                taskId = taskId,
                targetName = contactById(contactId).name
            )
        }
        return true
    }
    val taskRuntime = runtimeForConnectorResponse(
        sourceMessageId,
        contactId,
        envelopeConversationId,
        envelopeTurnId,
        taskId
    )
    val directBindingMatches = AgentSupervisedProjectPresentationPolicy.matchesDirectConnectorTaskEvent(
        binding = pendingDirectConnectorRuns[sourceMessageId],
        contactId = contactId,
        conversationId = envelopeConversationId,
        turnId = envelopeTurnId,
        taskId = taskId
    )
    updateAgentRegistryTaskHeartbeat(contactId, status)
    if (completionKey in completedConnectorTaskIds && status !in setOf("completed", "failed", "cancelled", "timed_out")) {
        return true
    }
    val statusSeq = executionVersion.sequence
    val existingMessage = messages[contactId]?.firstOrNull { it.id == sourceMessageId }
    if (taskRuntime == null && existingMessage != null && !directBindingMatches) {
        val expectedConversationId = AgentTaskIdentityPolicy.conversationId(contactId, "")
        val expectedTurnId = AgentTaskIdentityPolicy.turnId(sourceMessageId, "")
        val expectedTaskId = AgentTaskIdentityPolicy.taskId(
            ownerId = GalaxySSICrypto.localGalaxySSIId(),
            contactId = contactId,
            sourceMessageId = sourceMessageId,
            conversationId = expectedConversationId,
            turnId = expectedTurnId
        )
        if (envelopeConversationId != expectedConversationId ||
            envelopeTurnId != expectedTurnId ||
            taskId != expectedTaskId
        ) {
            Log.w("GalaxySSILink", "Rejected task event outside its originating chat turn")
            return true
        }
    }
    if (existingMessage != null && statusSeq > 0L && statusSeq < existingMessage.taskStatusSeq) return true
    publishAgentTaskPartialResult(envelope, sourceMessageId, contactId, status)
    val baseStatusLabel = when (status) {
        "accepted" -> getString(R.string.agent_task_status_accepted)
        "queued" -> getString(R.string.agent_task_status_queued)
        "starting" -> getString(R.string.agent_task_status_starting)
        "recovering" -> getString(R.string.agent_task_status_recovering)
        "running" -> getString(R.string.agent_task_status_running)
        "waiting_input" -> getString(R.string.agent_task_status_waiting_input)
        "waiting_approval" -> getString(R.string.agent_task_status_waiting_approval)
        "completed" -> getString(R.string.agent_task_status_completed)
        "failed" -> getString(R.string.agent_task_status_failed)
        "cancelled" -> getString(R.string.agent_task_status_cancelled)
        "timed_out" -> getString(R.string.agent_task_status_timed_out)
        else -> status
    }
    val elapsedSeconds = envelope.optLong("elapsed_ms", 0L) / 1000L
    val currentStep = envelope.optString("current_step").trim()
    val statusLabel = if (currentStep.isNotBlank() && status in setOf("starting", "running", "waiting_input", "waiting_approval")) {
        currentStep
    } else if (status == "running" && elapsedSeconds > 0L) {
        getString(R.string.agent_task_status_running_elapsed, elapsedSeconds)
    } else baseStatusLabel
    existingMessage?.let { message ->
        message.taskId = envelope.optString("task_id")
        message.taskStatus = status
        message.taskStatusSeq = maxOf(message.taskStatusSeq, statusSeq)
    }
    if (existingMessage != null && !isSteeredCompletion) {
        val eventTrace = incomingDeliveryTrace(envelope).apply {
            add(newTraceEvent("phone_task_event_received", status))
        }
        mergeDeliveryTrace(sourceMessageId, contactId, eventTrace, statusLabel)
    } else {
        ChatHistoryStore.applyAgentTaskEvent(this, envelope)
        reloadChatHistoryIfChanged(force = true)
    }
    traceTaskEvent("chat_history")
    val taskStatusState = taskRuntime?.recordConnectorTaskStatus(
        sourceMessageId = sourceMessageId,
        contactId = contactId,
        taskId = taskId,
        taskStatus = status,
        statusSeq = statusSeq,
        conversationId = envelopeConversationId,
        turnId = envelopeTurnId,
        executionGeneration = executionVersion.generation
    )
    val nativeState = if (isSteeredCompletion) {
        taskRuntime?.acceptConnectorSteered(
            sourceMessageId = sourceMessageId,
            contactId = contactId,
            mergedIntoTaskId = envelope.optString("merged_into_task_id"),
            conversationId = envelopeConversationId,
            turnId = envelopeTurnId,
            taskId = taskId
        ) ?: taskStatusState
    } else {
        taskStatusState
    }
    val pendingNativeAction = nativeState?.lastActionResult?.actionId?.let { pendingActionId ->
        nativeState.plan?.actions?.firstOrNull { action -> action.id == pendingActionId }
    }
    val supervisedPlan = nativeState?.plan?.isSupervisedProjectPlan() == true ||
        taskRuntime?.snapshot()?.plan?.isSupervisedProjectPlan() == true
    val supervisedControlTask = sourceMessageId in supervisedProjectConnectorSourceIds ||
        pendingNativeAction?.isSupervisedProjectConnector() == true ||
        supervisedPlan
    traceTaskEvent("runtime_status")
    if (isSteeredCompletion) {
        activeAgentTasks.remove(sourceMessageId)
        if (taskId.isNotBlank()) completedConnectorTaskIds.add(completionKey)
    }
    val targetName = contactById(contactId).name
    val executionView = envelope.optJSONObject("execution_view")
    val executorId = executionView
        ?.optString("executor_id")
        .orEmpty()
        .ifBlank { envelope.optString("agent_id") }
    val executorLabel = contactById(executorId).name
        .substringBefore(" \u00b7 ")
        .trim()
        .ifBlank { targetName.substringBefore(" \u00b7 ").trim() }
        .ifBlank { targetName }
    val taskEventObservedAt = System.currentTimeMillis()
    val taskEventUpdatedAt = envelope.optLong("updated_at")
        .takeIf { it > 0L }
        ?: taskEventObservedAt
    val declaredCompletedAt = executionView
        ?.optLong("completed_at")
        ?.takeIf { it > 0L }
        ?: envelope.optLong("completed_at").takeIf { it > 0L }
        ?: 0L
    rememberAgentExecutionPresentation(
        taskId,
        AgentExecutionPresentationPolicy.remote(
            executorId = executorId,
            executorLabel = executorLabel,
            locationKind = executionView
                ?.optString("location_kind")
                .orEmpty()
                .ifBlank { "desktop" },
            locationId = executionView
                ?.optString("location_id")
                .orEmpty(),
            locationName = executionView
                ?.optString("location_name")
                .orEmpty()
                .ifBlank { envelope.optString("desktop_name") },
            runtimeKind = executionView
                ?.optString("runtime_kind")
                .orEmpty(),
            runtimeId = executionView
                ?.optString("runtime_id")
                .orEmpty(),
            runtimeName = executionView
                ?.optString("runtime_name")
                .orEmpty(),
            contract = executionView
                ?.optString("contract")
                .orEmpty(),
            status = status,
            currentStep = currentStep.ifBlank { statusLabel },
            startedAtMillis = executionView
                ?.optLong("started_at")
                ?.takeIf { it > 0L }
                ?: envelope.optLong("started_at")
                    .takeIf { it > 0L }
                ?: envelope.optLong("created_at", System.currentTimeMillis()),
            completedAtMillis = AgentRemoteTaskStatusPolicy.completionTimestamp(
                status = status,
                declaredCompletedAtMillis = declaredCompletedAt,
                updatedAtMillis = taskEventUpdatedAt,
                observedAtMillis = taskEventObservedAt
            ),
            advertisedCancellable = executionView
                ?.optBoolean(
                    "cancellable",
                    status !in setOf("completed", "failed", "cancelled", "timed_out")
                )
                ?: (status !in setOf("completed", "failed", "cancelled", "timed_out"))
        )
    )
    val turnId = envelopeTurnId.takeIf { it.isNotBlank() }
        ?: taskRuntime?.let(agentRuntimeTurnIds::get).orEmpty()
    if (isSteeredCompletion) {
        val conversationId = connectorConversationId(
            envelopeConversationId,
            taskRuntime,
            turnId
        )
        if (nativeState != null && conversationId != null) {
            runOnUiThread {
                renderAgentState(
                    nativeState,
                    conversationId,
                    turnId,
                    syncTranscript = false
                )
            }
        }
        return true
    }
    if (turnId.isNotBlank() && status in setOf(
            "accepted", "queued", "starting", "recovering", "running", "waiting_input", "waiting_approval",
            "completed", "failed", "cancelled", "timed_out", "not_found"
        )
    ) {
        runCatching {
            AgentTaskRuntime.supervisor(this).progress(
                workspaceId = turnId,
                stage = "connector.$status",
                message = statusLabel
            )
        }
    }
    val conversationId = connectorConversationId(
        envelopeConversationId,
        taskRuntime,
        turnId
    ) ?: return true
    syncRemoteAgentApproval(
        envelope = envelope,
        conversationId = conversationId,
        turnId = turnId,
        taskId = taskId,
        targetName = targetName
    )
    traceTaskEvent("approval")
    envelope.optJSONObject("progress_event")?.let { progress ->
        val eventId = progress.optString("event_id").trim()
        val progressText = connectorProgressText(progress)
        if (eventId.isNotBlank() && progressText.isNotBlank()) {
            voiceCoordinatorSession(voiceTraceId).ifBlank {
                voiceCoordinatorIdsByTurn[envelope.optString("turn_id")].orEmpty()
            }.takeIf(String::isNotBlank)?.let { sessionId ->
                dispatchVoiceCoordinator(
                    VoiceInteractionEvent.AgentProgress(sessionId, envelope.optString("task_id"))
                )
            }
            VoiceLatencyTelemetry.record(
                this,
                voiceTraceId,
                VoiceTraceEvents.AGENT_FIRST_PROGRESS,
                mapOf("agent_provider" to envelope.optString("agent_id", "remote_agent")),
                once = true
            )
            val narration = progress.optString("kind") == "narration"
            agentTranscriptStore.upsert(
                AgentTranscriptRole.PROCESS,
                progressText,
                dedupeKey = buildString {
                    append("connector-event:")
                    append(taskId)
                    append(':')
                    append(if (narration) "REASONING_SUMMARY" else "TOOL_EVENT")
                    append(':')
                    append(eventId)
                },
                timestampMillis = progress.optLong(
                    "updated_at",
                    progress.optLong("created_at", envelope.optLong("updated_at", System.currentTimeMillis()))
                ),
                conversationId = conversationId,
                turnId = turnId,
                taskId = taskId
            )
        }
    }
    traceTaskEvent("progress_entry")
    // A task can gain a Codex turn id after it starts. Keep one stable key so
    // accepted, running steps, and completion update one process row in place.
    val connectorProcessKey = "connector-task:$taskId"
    if (turnId.isNotBlank()) {
        deleteAgentTranscriptByDedupeKey(conversationId, "connector-turn:$turnId")
    }
    agentTranscriptStore.upsert(
        AgentTranscriptRole.PROCESS,
        "$targetName · $statusLabel",
        dedupeKey = connectorProcessKey,
        timestampMillis = envelope.optLong("updated_at", System.currentTimeMillis()),
        conversationId = conversationId,
        turnId = turnId,
        taskId = taskId
    )
    val terminalFailure = status in setOf("failed", "timed_out", "not_found")
    val showFailureRecovery = AgentSupervisedProjectPresentationPolicy.shouldShowFailureRecovery(
        pendingAction = pendingNativeAction,
        isSupervisedSource = supervisedControlTask,
        isSupervisedPlan = supervisedPlan
    )
    if (terminalFailure && taskRuntime == null && showFailureRecovery) {
        syncAgentFailureRecoveryCard(
            envelope = envelope,
            conversationId = conversationId,
            turnId = turnId,
            taskId = taskId,
            agentId = executorId.ifBlank { envelope.optString("agent_id") },
            targetName = targetName,
            statusLabel = statusLabel
        )
    } else if (status == "completed") {
        deleteAgentTranscriptByDedupeKey(
            conversationId,
            agentFailureRecoveryDedupeKey(taskId)
        )
    }
    syncRemoteTaskEvents(
        envelope = envelope,
        conversationId = conversationId,
        turnId = turnId,
        taskId = taskId,
        targetName = targetName
    )
    traceTaskEvent("remote_events")
    if (taskId.isNotBlank()) {
        persistRemoteAgentTaskAsync(
            envelope = JSONObject(envelope.toString()),
            conversationId = conversationId,
            turnId = turnId,
            taskId = taskId,
            targetName = targetName,
            status = status,
            statusLabel = statusLabel
        )
    }
    traceTaskEvent("task_store")
    if (conversationId == agentTranscriptStore.activeConversation().id) {
        refreshAgentTranscriptWindow(conversationId)
    }
    traceTaskEvent("transcript_refresh")
    if (nativeState != null &&
        !AgentRemoteTaskStatusPolicy.settlesWithoutResponse(status)
    ) {
        runOnUiThread {
            renderAgentState(
                nativeState,
                conversationId,
                turnId,
                syncTranscript = false
            )
        }
    }
    if (taskRuntime != null &&
        AgentRemoteTaskStatusPolicy.settlesWithoutResponse(status)
    ) {
        if (taskId.isNotBlank()) completedConnectorTaskIds.add(completionKey)
        settleAgentConnectorTerminalEvent(
            runtime = taskRuntime,
            sourceMessageId = sourceMessageId,
            contactId = contactId,
            conversationId = conversationId,
            turnId = turnId,
            taskId = taskId,
            status = status,
            statusSeq = statusSeq,
            executionGeneration = executionVersion.generation,
            message = envelope.optString("error").ifBlank { statusLabel },
            envelope = JSONObject(envelope.toString()),
            showFailureRecovery = showFailureRecovery
        )
    }
    traceTaskEvent("render_state")
    return true
}

internal object AgentTaskEventRoutingPolicy {
    fun isManaged(envelope: JSONObject, sourceMessageId: Long, contactId: String): Boolean =
        AgentManagedConnectorResponseRegistry.contains(
            AgentConnectorStreamUpdate(
                sourceMessageId = sourceMessageId,
                contactId = contactId,
                content = "",
                conversationId = envelope.optString("conversation_id"),
                turnId = envelope.optString("turn_id"),
                taskId = envelope.optString("task_id")
            )
        )
}

internal fun MainActivity.settleAgentConnectorTerminalEvent(
    runtime: MobileNativeAgent,
    sourceMessageId: Long,
    contactId: String,
    conversationId: String,
    turnId: String,
    taskId: String,
    status: String,
    statusSeq: Long,
    executionGeneration: Long,
    message: String,
    envelope: JSONObject,
    showFailureRecovery: Boolean
) {
    val responseKey = "terminal:$sourceMessageId:$contactId:$taskId:$executionGeneration"
    if (!agentConnectorResponsesInFlight.add(responseKey)) return
    cancelConnectorTimeouts(sourceMessageId)
    thread(name = "galaxyssi-agent-terminal-${status.take(24)}") {
        bindAgentExecutionLoop(runtime, turnId)
        var state = runtime.acceptConnectorTerminalStatus(
            sourceMessageId = sourceMessageId,
            contactId = contactId,
            taskId = taskId,
            taskStatus = status,
            statusSeq = statusSeq,
            message = message,
            conversationId = conversationId,
            turnId = turnId,
            executionGeneration = executionGeneration
        )
        if (state == null) {
            agentConnectorResponsesInFlight.remove(responseKey)
            return@thread
        }
        state = finalizeAgentExecutionLoop(runtime, turnId, state)
        persistAgentWorkspaceSnapshot(turnId, state, runtime)
        val settledState = state
        val terminalResponse = AgentConnectorResponse(
            sourceMessageId = sourceMessageId,
            contactId = contactId,
            content = message,
            conversationId = conversationId,
            turnId = turnId,
            taskId = taskId,
            success = false,
            taskStatus = status.takeIf { it in AgentRemoteOutcomeCodec.FAILURES }.orEmpty(),
            executionGeneration = executionGeneration,
            statusSequence = statusSeq
        )
        finishStructuredAgentHandoff(turnId, terminalResponse)
        val replacementSourceId = state.lastActionResult?.metadata
            ?.get("source_message_id")
            ?.toLongOrNull()
            ?.takeIf { replacement -> replacement > 0L && replacement != sourceMessageId }
        if (AgentSupervisedProjectPresentationPolicy.shouldShowFailureRecovery(
                pendingAction = settledState.pendingAction,
                isSupervisedSource = false,
                isSupervisedPlan = settledState.plan?.isSupervisedProjectPlan() == true,
                terminalAccepted = true,
                settledPhase = settledState.phase
            ) && showFailureRecovery
        ) {
            syncAgentFailureRecoveryCard(
                envelope = envelope,
                conversationId = conversationId,
                turnId = turnId,
                taskId = taskId,
                agentId = contactId,
                targetName = contactById(contactId).name,
                statusLabel = message
            )
        } else {
            deleteAgentTranscriptByDedupeKey(
                conversationId,
                agentFailureRecoveryDedupeKey(taskId)
            )
        }
        runOnUiThread {
            activeAgentTasks.remove(sourceMessageId)
            if (settledState.phase == AgentPhase.WAITING_RESPONSE && replacementSourceId != null) {
                supersededConnectorSourceIds.add(sourceMessageId)
                activeAgentTasks[replacementSourceId] = runtime
                scheduleConnectorTimeouts(
                    runtime = runtime,
                    sourceMessageId = replacementSourceId,
                    conversationId = conversationId,
                    turnId = turnId
                )
            }
            finishAgentConnectorResponseUi(
                response = terminalResponse,
                runtime = runtime,
                state = settledState,
                conversationId = conversationId,
                turnId = turnId,
                responseKey = responseKey
            )
        }
    }
}

internal fun MainActivity.syncAgentFailureRecoveryCard(
    envelope: JSONObject,
    conversationId: String,
    turnId: String,
    taskId: String,
    agentId: String,
    targetName: String,
    statusLabel: String
) {
    if (taskId.isBlank() || conversationId.isBlank()) return
    val failure = envelope.optString("error").trim().ifBlank { statusLabel }
    val executionView = envelope.optJSONObject("execution_view")
    val noReply = agentNoReplyDisplay(
        taskStatus = envelope.optString("task_status"),
        error = failure,
        currentStep = envelope.optString("current_step"),
        agentId = agentId,
        targetName = targetName.ifBlank { contactById(agentId).name }.ifBlank { agentId },
        routeKind = when (
            executionView?.optString("location_kind").orEmpty().lowercase(Locale.ROOT)
        ) {
            "desktop", "windows", "macos", "linux" -> AgentRouteKind.DESKTOP_AGENT
            "cloud" -> AgentRouteKind.CLOUD_MODEL
            else -> AgentRouteKind.UNKNOWN
        }
    )
    val visibleFailure = AgentFailureDetailPolicy.visibleMessage(failure, noReply.message)
    val originalGoal = agentTurnGoals[turnId].orEmpty().ifBlank {
        agentTranscriptStore.entriesForTurn(turnId)
            .lastOrNull { it.role == AgentTranscriptRole.USER }
            ?.text
            .orEmpty()
    }
    val advertised = envelope.optJSONArray("recovery_actions")
    val advertisedByAction = buildMap<String, JSONObject> {
        if (advertised != null) {
            for (index in 0 until advertised.length()) {
                advertised.optJSONObject(index)?.let { item ->
                    item.optString("action").takeIf(String::isNotBlank)?.let { put(it, item) }
                }
            }
        }
    }
    val recommended = advertisedByAction.values
        .firstOrNull { it.optBoolean("enabled", true) && it.optBoolean("recommended") }
        ?.optString("action")
        ?.let(AgentFailureRecoveryAction::fromWireValue)
        ?: AgentFailureRecoveryPolicy.recommended(
            envelope.optString("task_status"),
            failure
        )
    val actions = AgentFailureRecoveryAction.entries.mapNotNull { action ->
        val advertisedAction = advertisedByAction[action.wireValue]
        if (advertisedAction?.optBoolean("enabled", true) == false) return@mapNotNull null
        val payload = AgentFailureRecoveryPayload(
            action = action,
            taskId = taskId,
            conversationId = conversationId,
            turnId = turnId,
            agentId = agentId,
            originalGoal = originalGoal,
            failure = failure
        )
        AgentRichAction(
            id = "recovery-${action.wireValue}",
            label = getString(
                when (action) {
                    AgentFailureRecoveryAction.RETRY -> R.string.agent_recovery_retry
                    AgentFailureRecoveryAction.SWITCH_AGENT -> R.string.agent_recovery_switch_agent
                    AgentFailureRecoveryAction.DEGRADE -> R.string.agent_recovery_degrade
                    AgentFailureRecoveryAction.DIAGNOSTICS -> R.string.agent_recovery_diagnostics
                }
            ),
            verb = "recover_agent_task",
            value = payload.encode(),
            style = if (action == recommended) "primary" else "default"
        )
    }
    if (actions.isEmpty()) return
    val richOutput = AgentRichContentCodec.encode(
        listOf(
            AgentRichBlock(
                id = "recovery-$taskId",
                type = AgentRichBlockType.ACTIONS,
                title = noReply.title,
                text = visibleFailure,
                fallbackText = visibleFailure,
                actions = actions,
                metadata = mapOf(
                    "task_id" to taskId,
                    "no_reply_reason" to noReply.reason.name.lowercase(Locale.ROOT),
                    "recommended_action" to recommended.wireValue
                )
            )
        )
    )
    agentTranscriptStore.upsert(
        role = AgentTranscriptRole.ASSISTANT,
        text = visibleFailure,
        dedupeKey = agentFailureRecoveryDedupeKey(taskId),
        timestampMillis = envelope.optLong("updated_at", System.currentTimeMillis()),
        conversationId = conversationId,
        turnId = turnId,
        taskId = taskId,
        richOutputJson = richOutput
    )
}

internal fun MainActivity.agentFailureRecoveryDedupeKey(taskId: String): String =
    "agent-recovery:$taskId"

internal fun MainActivity.agentNoReplyDisplay(
    taskStatus: String,
    error: String,
    currentStep: String,
    agentId: String,
    targetName: String,
    routeKind: AgentRouteKind,
    routeStatus: AgentConnectorStatus? = null
): AgentNoReplyDisplay {
    val cleanAgentId = agentId.trim()
    val registry = AppStoreAgentConnectorRegistry(this)
    val targets = registry.availableTargets()
    val registrations = registry.registrations()
    fun identityMatches(candidate: String): Boolean {
        val cleanCandidate = candidate.trim()
        return cleanAgentId.isNotBlank() && (
            cleanCandidate == cleanAgentId ||
                cleanCandidate.endsWith(":$cleanAgentId") ||
                cleanAgentId.endsWith(":$cleanCandidate")
            )
    }
    val target = targets.firstOrNull { identityMatches(it.id) }
        ?: targets.firstOrNull {
            targetName.isNotBlank() && it.title.equals(targetName, ignoreCase = true)
        }
    val registration = registrations.firstOrNull { identityMatches(it.agentId) }
        ?: registrations.firstOrNull {
            targetName.isNotBlank() && it.displayName.equals(targetName, ignoreCase = true)
        }
    val resolvedRouteKind = routeKind.takeUnless { it == AgentRouteKind.UNKNOWN }
        ?: when {
            registration?.location == AgentResourceLocation.TRUSTED_DESKTOP ->
                AgentRouteKind.DESKTOP_AGENT
            target?.kind == AgentConnectorKind.AGENT -> AgentRouteKind.DESKTOP_AGENT
            target?.kind == AgentConnectorKind.MODEL &&
                AgentCapability.LOCAL_INFERENCE in target.capabilities ->
                AgentRouteKind.LOCAL_MODEL
            target?.kind == AgentConnectorKind.MODEL -> AgentRouteKind.CLOUD_MODEL
            target?.kind == AgentConnectorKind.DEVICE -> AgentRouteKind.DEVICE_CONNECTOR
            target?.kind == AgentConnectorKind.KNOWLEDGE -> AgentRouteKind.KNOWLEDGE
            else -> AgentRouteKind.UNKNOWN
        }
    val networkRequired = resolvedRouteKind in setOf(
        AgentRouteKind.DESKTOP_AGENT,
        AgentRouteKind.CLOUD_MODEL,
        AgentRouteKind.DEVICE_CONNECTOR
    ) || registration?.connectionKind in setOf(
        AgentConnectionKind.GALAXYSSI_LINK,
        AgentConnectionKind.HTTP,
        AgentConnectionKind.WEBSOCKET,
        AgentConnectionKind.MCP
    )
    val endpointStatus = when {
        registration != null && !registration.hasCapacity -> AgentEndpointStatus.BUSY
        else -> registration?.status
    }
    val reason = AgentNoReplyReasonPolicy.classify(
        AgentNoReplySignal(
            taskStatus = taskStatus,
            error = error,
            currentStep = currentStep,
            routeKind = resolvedRouteKind,
            routeStatus = routeStatus ?: target?.status ?: AgentConnectorStatus.AVAILABLE,
            endpointStatus = endpointStatus,
            networkRequired = networkRequired,
            networkAvailable = validatedInternetAvailable()
        )
    )
    val targetLabel = targetName.trim()
        .ifBlank { registration?.displayName.orEmpty() }
        .ifBlank { target?.title.orEmpty() }
        .ifBlank { getString(R.string.tab_agent) }
    val title = getString(when (reason) {
        AgentNoReplyReason.NETWORK_UNAVAILABLE -> R.string.agent_no_reply_network_title
        AgentNoReplyReason.DESKTOP_OFFLINE -> R.string.agent_no_reply_desktop_title
        AgentNoReplyReason.DESKTOP_AGENT_START_FAILED -> R.string.agent_no_reply_desktop_start_title
        AgentNoReplyReason.AGENT_BUSY -> R.string.agent_no_reply_busy_title
        AgentNoReplyReason.PERMISSION_WAITING -> R.string.agent_no_reply_permission_title
        AgentNoReplyReason.AUTHENTICATION_REQUIRED -> R.string.agent_no_reply_auth_title
        AgentNoReplyReason.CONFIGURATION_REQUIRED -> R.string.agent_no_reply_configuration_title
        AgentNoReplyReason.TOOL_UNAVAILABLE -> R.string.agent_no_reply_tool_title
        AgentNoReplyReason.AGENT_UNAVAILABLE -> R.string.agent_no_reply_unavailable_title
        AgentNoReplyReason.TIMED_OUT -> R.string.agent_no_reply_timeout_title
        AgentNoReplyReason.INVALID_REQUEST -> R.string.agent_no_reply_invalid_title
        AgentNoReplyReason.UNKNOWN -> R.string.agent_no_reply_unknown_title
    })
    val message = getString(
        when (reason) {
            AgentNoReplyReason.NETWORK_UNAVAILABLE -> R.string.agent_no_reply_network_message
            AgentNoReplyReason.DESKTOP_OFFLINE -> R.string.agent_no_reply_desktop_message
            AgentNoReplyReason.DESKTOP_AGENT_START_FAILED -> R.string.agent_no_reply_desktop_start_message
            AgentNoReplyReason.AGENT_BUSY -> R.string.agent_no_reply_busy_message
            AgentNoReplyReason.PERMISSION_WAITING -> R.string.agent_no_reply_permission_message
            AgentNoReplyReason.AUTHENTICATION_REQUIRED -> R.string.agent_no_reply_auth_message
            AgentNoReplyReason.CONFIGURATION_REQUIRED -> R.string.agent_no_reply_configuration_message
            AgentNoReplyReason.TOOL_UNAVAILABLE -> R.string.agent_no_reply_tool_message
            AgentNoReplyReason.AGENT_UNAVAILABLE -> R.string.agent_no_reply_unavailable_message
            AgentNoReplyReason.TIMED_OUT -> R.string.agent_no_reply_timeout_message
            AgentNoReplyReason.INVALID_REQUEST -> R.string.agent_no_reply_invalid_message
            AgentNoReplyReason.UNKNOWN -> R.string.agent_no_reply_unknown_message
        },
        targetLabel
    )
    return AgentNoReplyDisplay(reason, title, message)
}

internal fun MainActivity.persistRemoteAgentTaskAsync(
    envelope: JSONObject,
    conversationId: String,
    turnId: String,
    taskId: String,
    targetName: String,
    status: String,
    statusLabel: String
) {
    agentTaskPersistenceExecutor.execute {
        val taskStore = SQLiteAgentTaskStore(applicationContext)
        val existingTask = taskStore.find(taskId)
        val executionView = envelope.optJSONObject("execution_view")
        val execution = AgentExecutionPresentationPolicy.remote(
            executorId = executionView?.optString("executor_id").orEmpty()
                .ifBlank { envelope.optString("agent_id") },
            executorLabel = targetName,
            locationKind = executionView?.optString("location_kind").orEmpty()
                .ifBlank { "desktop" },
            locationId = executionView?.optString("location_id").orEmpty(),
            locationName = executionView?.optString("location_name").orEmpty()
                .ifBlank { envelope.optString("desktop_name") },
            runtimeKind = executionView?.optString("runtime_kind").orEmpty(),
            runtimeId = executionView?.optString("runtime_id").orEmpty(),
            runtimeName = executionView?.optString("runtime_name").orEmpty(),
            contract = executionView?.optString("contract").orEmpty(),
            status = status,
            currentStep = envelope.optString("current_step"),
            startedAtMillis = executionView?.optLong("started_at")
                ?.takeIf { it > 0L }
                ?: envelope.optLong("started_at", envelope.optLong("created_at")),
            completedAtMillis = executionView?.optLong("completed_at")
                ?: envelope.optLong("completed_at"),
            advertisedCancellable = executionView?.optBoolean("cancellable", true) ?: true
        )
        val outputFiles = buildList {
            val files = envelope.optJSONArray("output_files") ?: org.json.JSONArray()
            for (index in 0 until files.length()) {
                val item = files.optJSONObject(index) ?: continue
                item.optString("relative_path").takeIf { it.isNotBlank() }?.let(::add)
            }
        }
        val sourceGoal = existingTask?.goal.orEmpty().ifBlank {
            agentTurnGoals[turnId].orEmpty()
        }
        val eventTime = listTime(envelope.optLong("updated_at", System.currentTimeMillis()))
        val eventLine = "$eventTime · $targetName · $statusLabel"
        val executionLog = (existingTask?.executionLog.orEmpty() + eventLine).distinct()
        taskStore.upsert(
            AgentTaskRecord(
                taskId = taskId,
                sessionId = conversationId,
                goal = existingTask?.goal ?: sourceGoal.ifBlank { targetName },
                phase = when (status) {
                    "completed" -> AgentPhase.COMPLETED
                    "failed", "timed_out", "not_found" -> AgentPhase.FAILED
                    "cancelled" -> AgentPhase.CANCELLED
                    "waiting_input", "waiting_approval" -> AgentPhase.PAUSED
                    else -> AgentPhase.EXECUTING
                },
                routeKind = existingTask?.routeKind ?: AgentRouteKind.DESKTOP_AGENT,
                targetTitle = targetName,
                risk = existingTask?.risk ?: AgentRisk.LOW,
                blocked = status == "waiting_approval",
                executionLocationKind = execution.locationKind,
                executionRuntimeKind = execution.runtimeKind,
                executionLocationId = execution.locationId,
                executionLocationName = execution.locationLabelHint,
                executionRuntimeId = execution.runtimeId.ifBlank { execution.executorId },
                executionLocationTrusted = execution.locationTrusted,
                result = envelope.optString("error").ifBlank { existingTask?.result.orEmpty() },
                verification = existingTask?.verification.orEmpty(),
                outputFiles = if (outputFiles.isNotEmpty()) {
                    outputFiles
                } else {
                    existingTask?.outputFiles.orEmpty()
                },
                executionLog = executionLog,
                createdAtMillis = existingTask?.createdAtMillis
                    ?: envelope.optLong("created_at", System.currentTimeMillis()),
                updatedAtMillis = envelope.optLong("updated_at", System.currentTimeMillis())
            )
        )
    }
}

internal fun MainActivity.syncRemoteAgentApproval(
    envelope: JSONObject,
    conversationId: String,
    turnId: String,
    taskId: String,
    targetName: String
) {
    val request = AgentRemoteApprovalRequest.fromTaskEvent(envelope)
    if (request == null) {
        if (taskId in remoteAgentApprovalTaskIds &&
            envelope.optString("task_status") != "waiting_approval"
        ) {
            removeRemoteAgentApprovals(taskId)
        }
        return
    }
    GalaxySSIMqttClient.publishAgentTaskApproval(
        request.decision(AgentPermissionChoice.ALLOW_ALWAYS)
    )
    removeRemoteAgentApprovals(taskId)
}

internal fun MainActivity.remoteAgentApprovalTitle(request: AgentRemoteApprovalRequest): String =
    getString(
        when (request.kind.lowercase(Locale.ROOT)) {
            "command" -> R.string.agent_remote_approval_command
            "file_change" -> R.string.agent_remote_approval_files
            "permissions" -> R.string.agent_remote_approval_permissions
            else -> R.string.agent_remote_approval_title
        }
    )

internal fun MainActivity.agentPermissionChoices(
    tier: AgentConfirmationTier
): List<AgentPermissionChoice> = if (tier == AgentConfirmationTier.CONFIRM_ALWAYS) {
    listOf(
        AgentPermissionChoice.ALLOW_ONCE,
        AgentPermissionChoice.DENY_ALWAYS
    )
} else {
    listOf(
        AgentPermissionChoice.ALLOW_ONCE,
        AgentPermissionChoice.ALLOW_SESSION,
        AgentPermissionChoice.ALLOW_ALWAYS,
        AgentPermissionChoice.DENY_ALWAYS
    )
}

internal fun MainActivity.agentPermissionChoiceLabel(choice: AgentPermissionChoice): String = getString(
    when (choice) {
        AgentPermissionChoice.ALLOW_ONCE -> R.string.agent_permission_allow_once
        AgentPermissionChoice.ALLOW_SESSION -> R.string.agent_permission_allow_session
        AgentPermissionChoice.ALLOW_ALWAYS -> R.string.agent_permission_allow_always
        AgentPermissionChoice.DENY_ALWAYS -> R.string.agent_permission_deny_always
    }
)

internal fun MainActivity.remoteAgentApprovalDetail(
    request: AgentRemoteApprovalRequest,
    targetName: String
): String = buildList {
    add(getString(R.string.agent_remote_approval_requested_by, targetName))
    request.detail.takeIf(String::isNotBlank)?.let(::add)
    val parameters = runCatching { JSONObject(request.parametersJson) }.getOrNull()
    parameters?.optString("cwd")?.takeIf(String::isNotBlank)?.let {
        add(getString(R.string.agent_remote_approval_working_directory, it))
    }
    parameters?.optString("grant_root")?.takeIf(String::isNotBlank)?.let {
        add(getString(R.string.agent_remote_approval_scope, it))
    }
    parameters?.optJSONArray("files")?.let { files ->
        val names = (0 until files.length())
            .mapNotNull { index -> files.optString(index).takeIf(String::isNotBlank) }
        if (names.isNotEmpty()) {
            add(getString(R.string.agent_remote_approval_files_list, names.joinToString(", ")))
        }
    }
    parameters?.optJSONObject("permissions")
        ?.takeIf { it.length() > 0 }
        ?.let {
            add(
                getString(
                    R.string.agent_remote_approval_permissions_list,
                    it.toString().take(2_000)
                )
            )
        }
    request.reason
        .takeIf { it.isNotBlank() && !request.detail.contains(it, ignoreCase = true) }
        ?.let { add(getString(R.string.agent_remote_approval_reason, it)) }
    add(getString(R.string.agent_remote_approval_fingerprint, request.compactActionHash))
}.joinToString("\n")

internal fun MainActivity.handleAgentTaskApprovalResult(envelope: JSONObject?): Boolean {
    if (envelope?.optString("type") != "agent_task_approval_result") return false
    val taskId = envelope.optString("task_id").trim()
    val approvalId = envelope.optString("approval_id").trim()
    val actionHash = envelope.optString("action_hash").trim().lowercase(Locale.ROOT)
    val matchingEntries = remoteAgentApprovalEntries(taskId, approvalId, actionHash)
    remoteAgentApprovalsInFlight.remove("$taskId:$approvalId:$actionHash")
    if (matchingEntries.isEmpty()) return true
    if (envelope.optBoolean("resolved", false)) {
        matchingEntries.forEach { entry ->
            agentTranscriptStore.deleteEntry(entry.id)
            if (agentTranscriptWindow.conversationId == entry.conversationId) {
                agentTranscriptWindow.remove(entry.id)
            }
        }
        matchingEntries.map(AgentTranscriptEntry::conversationId).distinct().forEach { conversationId ->
            if (conversationId == agentTranscriptStore.activeConversation().id) {
                refreshAgentTranscriptWindow(conversationId)
            }
        }
        Toast.makeText(
            this,
            if (envelope.optBoolean("approved", false)) {
                R.string.agent_remote_approval_allowed
            } else {
                R.string.agent_remote_approval_denied
            },
            Toast.LENGTH_SHORT
        ).show()
        remoteAgentApprovalTaskIds.remove(taskId)
    } else {
        Toast.makeText(
            this,
            if (envelope.optString("error").contains("expired", ignoreCase = true)) {
                R.string.agent_remote_approval_expired
            } else {
                R.string.agent_remote_approval_failed
            },
            Toast.LENGTH_LONG
        ).show()
    }
    return true
}

internal fun MainActivity.remoteAgentApprovalEntries(
    taskId: String,
    approvalId: String,
    actionHash: String
): List<AgentTranscriptEntry> = agentTranscriptStore.taskEntries(taskId).filter { entry ->
    AgentRichContentCodec.decode(entry.richOutputJson)
        .flatMap(AgentRichBlock::actions)
        .mapNotNull { action -> AgentRemoteApprovalDecision.decode(action.value) }
        .any { decision ->
            decision.taskId == taskId &&
                decision.approvalId == approvalId &&
                decision.actionHash == actionHash
        }
}

internal fun MainActivity.removeRemoteAgentApprovals(taskId: String) {
    if (!remoteAgentApprovalTaskIds.remove(taskId)) return
    val entries = agentTranscriptStore.taskEntries(taskId).filter { entry ->
        isRemoteAgentApprovalEntry(entry)
    }
    remoteAgentApprovalsInFlight
        .filter { key -> key.startsWith("$taskId:") }
        .forEach(remoteAgentApprovalsInFlight::remove)
    if (entries.isEmpty()) return
    entries.forEach { entry ->
        agentTranscriptStore.deleteEntry(entry.id)
        if (agentTranscriptWindow.conversationId == entry.conversationId) {
            agentTranscriptWindow.remove(entry.id)
        }
    }
}

internal fun MainActivity.syncRemoteTaskEvents(
    envelope: JSONObject,
    conversationId: String,
    turnId: String,
    taskId: String,
    targetName: String
) {
    val events = envelope.optJSONArray("events") ?: return
    for (index in 0 until events.length()) {
        val event = events.optJSONObject(index) ?: continue
        val eventId = event.optString("event_id").trim()
        val kind = event.optString("kind").trim().lowercase(Locale.ROOT)
        val title = event.optString("title").trim()
        val detail = event.optString("detail").trim()
        if (eventId.isBlank() || (title.isBlank() && detail.isBlank())) continue
        val rendered = if (kind == "mcp") {
            connectorProgressText(event)
        } else {
            remoteTaskEventText(kind, title, detail, targetName)
        }
        if (rendered.isBlank()) continue
        val contentKind = if (kind in setOf("narration", "reasoning", "plan")) {
            "REASONING_SUMMARY"
        } else {
            kind.ifBlank { "TOOL" }.uppercase(Locale.ROOT)
        }
        val eventKey = "$taskId:$contentKind:$eventId"
        if (remoteTaskEventFingerprints.put(eventKey, rendered) == rendered) continue
        agentTranscriptStore.upsert(
            role = AgentTranscriptRole.PROCESS,
            text = rendered,
            dedupeKey = "connector-event:$taskId:$contentKind:$eventId",
            timestampMillis = event.optLong(
                "updated_at",
                event.optLong("created_at", System.currentTimeMillis())
            ),
            conversationId = conversationId,
            turnId = turnId,
            taskId = taskId
        )
    }
    if (remoteTaskEventFingerprints.size > 10_000) {
        remoteTaskEventFingerprints.keys.take(2_000).forEach(remoteTaskEventFingerprints::remove)
    }
}

internal fun MainActivity.remoteTaskEventText(
    kind: String,
    title: String,
    detail: String,
    targetName: String
): String {
    if (kind == "narration") {
        return detail.ifBlank { title }.take(MAX_CONNECTOR_PROGRESS_TEXT_CHARACTERS)
    }
    val base = when (kind) {
        "reasoning" -> getString(R.string.agent_trace_remote_reasoning)
        "plan" -> getString(R.string.agent_trace_remote_plan)
        "command" -> getString(R.string.agent_trace_remote_command)
        "file" -> getString(R.string.agent_trace_remote_file)
        "network" -> getString(R.string.agent_trace_remote_network)
        "mcp" -> getString(R.string.agent_trace_remote_mcp)
        "model", "agent" -> getString(
            R.string.agent_trace_remote_provider,
            targetName.ifBlank { title }
        )
        else -> getString(R.string.agent_trace_remote_tool)
    }
    return if (detail.isBlank()) base else "$base · $detail"
}

internal fun MainActivity.connectorProgressText(progress: JSONObject): String {
    val kind = progress.optString("kind")
    val code = progress.optString("code").ifBlank {
        progress.optJSONObject("metadata")?.optString("code").orEmpty()
    }
    val detail = progress.optString("detail").trim()
    val title = progress.optString("title").trim()
    if (kind == "narration" || code in setOf("commentary", "reasoning_summary", "plan")) {
        return detail.ifBlank { title }.take(MAX_CONNECTOR_PROGRESS_TEXT_CHARACTERS)
    }
    val status = progress.optString("status").ifBlank { "completed" }
    val metadata = progress.optJSONObject("metadata")
    if (kind == "mcp" && metadata?.optString("kind") == "mcp_tool_call") {
        val connectionName = metadata.optString("connection_name")
            .ifBlank { metadata.optString("connection_id") }
        val toolName = metadata.optString("tool_name").ifBlank { title }
        val toolLabel = listOf(connectionName, toolName)
            .filter(String::isNotBlank)
            .joinToString(" · ")
        val risk = when (metadata.optString("risk")) {
            "low" -> getString(R.string.agent_risk_low)
            "high" -> getString(R.string.agent_risk_high)
            else -> getString(R.string.agent_risk_medium)
        }
        val permissionArray = metadata.optJSONArray("permissions")
        val permissions = buildList {
            if (permissionArray != null) {
                for (index in 0 until permissionArray.length()) {
                    permissionArray.optString(index)
                        .takeIf(String::isNotBlank)
                        ?.let(::add)
                }
            }
        }.joinToString(" · ").ifBlank { "—" }
        val parameters = metadata.optJSONObject("parameter_preview")
            ?.toString()
            ?.take(MAX_CONNECTOR_PROGRESS_DETAIL_CHARACTERS)
            .orEmpty()
            .ifBlank { "{}" }
        return getString(
            R.string.agent_trace_mcp_tool_details,
            toolLabel.ifBlank { getString(R.string.agent_trace_connector_operation_mcp) },
            metadata.optString("source").ifBlank { "—" },
            risk,
            permissions,
            parameters
        ).take(MAX_CONNECTOR_PROGRESS_TEXT_CHARACTERS)
    }
    val count = metadata?.optInt("count", 1)?.coerceAtLeast(1) ?: 1
    if (code == "image_view") {
        return resources.getQuantityString(
            if (status == "running") {
                R.plurals.agent_trace_connector_images_viewing
            } else {
                R.plurals.agent_trace_connector_images_viewed
            },
            count,
            count
        )
    }
    val operation = getString(when (code) {
        "web_search" -> R.string.agent_trace_connector_operation_web_search
        "command" -> R.string.agent_trace_connector_operation_command
        "file_change" -> R.string.agent_trace_connector_operation_file_change
        "mcp_tool" -> R.string.agent_trace_connector_operation_mcp
        "dynamic_tool" -> R.string.agent_trace_connector_operation_tool
        "image_generation" -> R.string.agent_trace_connector_operation_image_generation
        "agent_collaboration" -> R.string.agent_trace_connector_operation_collaboration
        "context_compaction" -> R.string.agent_trace_connector_operation_context_compaction
        else -> return title.take(MAX_CONNECTOR_PROGRESS_TEXT_CHARACTERS)
    })
    val suffix = detail
        .lineSequence()
        .firstOrNull()
        .orEmpty()
        .trim()
        .take(MAX_CONNECTOR_PROGRESS_DETAIL_CHARACTERS)
        .takeIf(String::isNotBlank)
        ?.let { " · $it" }
        .orEmpty()
    return getString(
        when (status) {
            "running" -> R.string.agent_trace_connector_progress_running
            "failed" -> R.string.agent_trace_connector_progress_failed
            else -> R.string.agent_trace_connector_progress_completed
        },
        operation,
        suffix
    )
}

internal fun MainActivity.markDesktopDomainAvailable(contactId: String) {
    val desktopId = AppStore.desktopIdForContact(this, contactId)
    if (desktopId.isNotBlank()) markDesktopDomainAvailableById(desktopId)
}

internal fun MainActivity.markDesktopDomainAvailableById(desktopId: String) {
    AgentResourceHealthStore(this).markAvailable("domain:$desktopId")
}

internal fun MainActivity.syncAgentRegistrySnapshot(force: Boolean = false) = synchronized(agentRegistrySyncLock) {
    if (!isEncryptedAgentRegistryInitialized() || !isMobileNativeAgentInitialized()) return
    val now = System.currentTimeMillis()
    if (!force && now - lastAgentRegistrySyncAtMillis < AGENT_REGISTRY_SYNC_INTERVAL_MILLIS) return
    val existing = encryptedAgentRegistry.list(now).associateBy(AgentRegistration::agentId)
    mobileNativeAgent.agentRegistrySnapshot().forEach { candidate ->
        val previous = existing[candidate.agentId]
        if (previous == null || agentRegistrationMetadataChanged(previous, candidate)) {
            encryptedAgentRegistry.upsert(candidate)
        } else if (candidate.lastHeartbeatMillis > previous.lastHeartbeatMillis ||
            candidate.status != previous.status || candidate.activeRuns != previous.activeRuns
        ) {
            encryptedAgentRegistry.heartbeat(
                agentId = candidate.agentId,
                status = candidate.status,
                activeRuns = candidate.activeRuns,
                capabilitiesHash = candidate.capabilitiesHash,
                timestampMillis = candidate.lastHeartbeatMillis.takeIf { it > 0L } ?: now
            )
        }
    }
    lastAgentRegistrySyncAtMillis = now
}

internal fun MainActivity.requestAgentRegistrySnapshotSync(force: Boolean = false) {
    val now = System.currentTimeMillis()
    if (!force && now - lastAgentRegistrySyncAtMillis < AGENT_REGISTRY_SYNC_INTERVAL_MILLIS) return
    agentRegistrySyncRequested.set(true)
    if (!agentRegistrySyncInProgress.compareAndSet(false, true)) return
    thread(name = "galaxyssi-agent-registry-sync") {
        try {
            do {
                agentRegistrySyncRequested.set(false)
                runCatching { syncAgentRegistrySnapshot(force = true) }
                    .onFailure { Log.w("GalaxySSIStartup", "Agent registry sync failed", it) }
            } while (agentRegistrySyncRequested.get())
        } finally {
            agentRegistrySyncInProgress.set(false)
            if (agentRegistrySyncRequested.get()) {
                requestAgentRegistrySnapshotSync(force = true)
            }
        }
    }
}

internal fun MainActivity.agentRegistrationMetadataChanged(
    previous: AgentRegistration,
    candidate: AgentRegistration
): Boolean = previous.providerId != candidate.providerId ||
    previous.displayName != candidate.displayName ||
    previous.kind != candidate.kind ||
    previous.location != candidate.location ||
    previous.capabilities != candidate.capabilities ||
    previous.toolIds != candidate.toolIds ||
    previous.permissionScopes != candidate.permissionScopes ||
    previous.protocol != candidate.protocol ||
    previous.connectionKind != candidate.connectionKind ||
    previous.cost != candidate.cost ||
    previous.latency != candidate.latency ||
    previous.trust != candidate.trust ||
    previous.maxParallelRuns != candidate.maxParallelRuns ||
    previous.capabilitiesHash != candidate.capabilitiesHash ||
    previous.failureDomain != candidate.failureDomain ||
    previous.runtimeFailureDomain != candidate.runtimeFailureDomain ||
    previous.adapterType != candidate.adapterType ||
    previous.independentlyUpgradeable != candidate.independentlyUpgradeable

internal fun MainActivity.updateAgentRegistryTaskHeartbeat(contactId: String, taskStatus: String) {
    if (contactId.isBlank() || !isEncryptedAgentRegistryInitialized()) return
    runCatching {
        agentRegistryHeartbeatExecutor.execute {
            val registrations = encryptedAgentRegistry.list()
            val registration = registrations.firstOrNull { it.agentId == contactId }
                ?: registrations.firstOrNull { it.deviceId == contactId }
                ?: registrations.firstOrNull {
                    it.agentId.endsWith(":$contactId") || contactId.endsWith(":${it.agentId}")
                }
                ?: return@execute
            val endpointStatus = when (taskStatus) {
                "accepted", "queued", "starting", "recovering", "running",
                "waiting_input", "waiting_approval" -> AgentEndpointStatus.BUSY
                "timed_out" -> AgentEndpointStatus.DEGRADED
                "completed", "failed", "cancelled", "not_found" -> AgentEndpointStatus.IDLE
                else -> registration.status
            }
            val activeRuns = if (endpointStatus == AgentEndpointStatus.BUSY) {
                registration.activeRuns.coerceAtLeast(1)
            } else {
                0
            }
            encryptedAgentRegistry.heartbeat(
                agentId = registration.agentId,
                status = endpointStatus,
                activeRuns = activeRuns,
                timestampMillis = System.currentTimeMillis()
            )
        }
    }.onFailure {
        if (!isFinishing && !isDestroyed) {
            Log.w("GalaxySSIAgent", "Agent registry heartbeat was not scheduled", it)
        }
    }
}

internal fun MainActivity.handleAgentTaskLivenessSignal(signal: AgentTaskLivenessSignal) {
    if (!isAgentTranscriptStoreInitialized()) return
    val workspace = signal.workspace
    val conversationId = agentTranscriptStore.resolveMergedConversationId(workspace.conversationId)
        ?: workspace.conversationId
    if (conversationId.isBlank()) return
    val dedupeKey = "task-watchdog:${workspace.taskId}"
    if (AgentTaskTerminalReplyPolicy.hasTerminalReply(
            agentTranscriptStore.entriesForTask(workspace.taskId),
            workspace.taskId
        )
    ) {
        clearAgentTaskWatchdogTranscript(conversationId, workspace.taskId)
        requestAgentTranscriptWindowRefresh(conversationId)
        return
    }
    when (signal.kind) {
        AgentTaskLivenessSignalKind.STALLED -> {
            // A warning threshold is only a supervisor signal. Do not present a guessed
            // diagnosis until the model has assessed the latest durable evidence.
            consumePendingAgentConnectorResponsesAsync()
            if (workspace.status == AgentWorkspaceStatus.WAITING_RESPONSE &&
                workspace.agentId.isNotBlank() && workspace.agentId != "galaxyssi-mobile"
            ) {
                requestRecoverableAgentRunReconciliation("stall")
            }
        }
        AgentTaskLivenessSignalKind.RECOVERED -> {
            deleteAgentTranscriptByDedupeKey(conversationId, dedupeKey)
            deleteAgentTranscriptByDedupeKey(
                conversationId,
                "task-liveness-assessment:${workspace.taskId}"
            )
            clearSupersededAgentFailureEntries(conversationId)
        }
        AgentTaskLivenessSignalKind.ASSESSMENT_REQUIRED -> {
            deleteAgentTranscriptByDedupeKey(conversationId, dedupeKey)
            agentTranscriptStore.upsert(
                role = AgentTranscriptRole.PROCESS,
                text = getString(R.string.agent_task_liveness_assessment),
                dedupeKey = "task-liveness-assessment:${workspace.taskId}",
                timestampMillis = signal.observedAtMillis,
                conversationId = conversationId,
                turnId = workspace.taskId,
                taskId = workspace.taskId
            )
            AgentLongTaskRecoveryScheduler.enqueue(
                this,
                workspace.workspaceId,
                signal.reason
            )
            requestRecoverableAgentRunReconciliation("liveness_assessment")
        }
    }
    requestAgentTranscriptWindowRefresh(conversationId)
}

internal fun MainActivity.requestRecoverableAgentRunReconciliation(
    reason: String,
    refreshRegistry: Boolean = false
) {
    val now = SystemClock.elapsedRealtime()
    if (reason == "stall") {
        val previous = agentTaskRecoveryLastStartedAt.get()
        if (previous > 0L && now - previous < AGENT_STALL_RECOVERY_MIN_INTERVAL_MS) return
        if (!agentTaskRecoveryLastStartedAt.compareAndSet(previous, now)) return
    }
    AndroidAgentRecoveryWake.request(this)
    if (!agentTaskRecoveryInProgress.compareAndSet(false, true)) return
    thread(name = "galaxyssi-agent-run-recovery") {
        val startedAt = SystemClock.elapsedRealtime()
        try {
            if (refreshRegistry) syncAgentRegistrySnapshot(force = true)
            runCatching { reconcileRecoverableAgentRuns() }
                .onFailure { Log.w("GalaxySSIAgent", "Agent run recovery failed ($reason)", it) }
        } finally {
            agentTaskRecoveryInProgress.set(false)
            Log.i(
                "GalaxySSIStartup",
                "agent_run_recovery reason=$reason total=${SystemClock.elapsedRealtime() - startedAt}ms"
            )
        }
    }
}

private const val AGENT_STALL_RECOVERY_MIN_INTERVAL_MS = 30_000L

internal fun MainActivity.reconcileRecoverableAgentRuns() {
    if (!isAgentRunEventStoreInitialized() || !isAgentRunRecorderInitialized()) return
    val liveRuntimeWorkspaceIds = buildSet {
        val runtimes = buildSet {
            addAll(provisionalAgentTasks)
            addAll(activeAgentTasks.values)
            if (isMobileNativeAgentInitialized()) add(mobileNativeAgent)
        }
        runtimes.forEach { runtime ->
            val phase = runtime.snapshot().phase
            if (phase !in setOf(AgentPhase.COMPLETED, AgentPhase.FAILED, AgentPhase.CANCELLED)) {
                agentRuntimeTurnIds[runtime]?.takeIf(String::isNotBlank)?.let(::add)
            }
        }
    }
    val activeRunIds = (AgentTaskRuntime.supervisor(this).activeWorkspaces().map { it.workspaceId } +
        liveRuntimeWorkspaceIds)
        .distinct()
        .mapNotNull { workspaceId -> EncryptedAgentWorkspaceStore(this).find(workspaceId) }
        .flatMap { workspace ->
            listOfNotNull(
                workspace.parentRunId.takeIf(String::isNotBlank),
                agentRunIdsByTurn[workspace.taskId]?.takeIf(String::isNotBlank)
            )
        }
        .toSet()
    val registrations = encryptedAgentRegistry.list()
    var observations: List<AgentRecoverableRun>? = null
    val recoverableSource: suspend () -> List<AgentRecoverableRun> = {
        observations ?: AndroidAgentRemoteRecovery.recover(this, agentHandoffStore.active()
            .filterNot { it.request.runId in activeRunIds }).also { observations = it }
    }
    val provider = ActionExecutorAgentProvider(
        registrationSource = { AppStoreAgentConnectorRegistry(this).registrations() },
        delegate = AndroidAgentActionExecutor(this),
        recoverableSource = recoverableSource,
        runStartReceipts = EncryptedAgentRunStartReceiptStore(this),
        healthLedger = EncryptedAgentProviderHealthLedger(this),
        managedResponses = EncryptedAgentManagedResponseLedger(this),
        globalRunSlots = AgentGlobalRunSlotStore(this)
    )
    val directory = AgentAdapterDirectory().apply { register(provider) }
    val results = runBlocking {
        AgentRunRecoveryCoordinator(
            runStore = agentRunEventStore,
            workspaceStore = EncryptedAgentWorkspaceStore(this@reconcileRecoverableAgentRuns),
            recordedRun = agentRunRecorder::run,
            registration = { agentId, _ ->
                // The Run's device id is its phone owner, not the remote executor.
                registrations.singleOrNull { it.agentId == agentId }
            },
            adapterResolver = directory::resolveAdapter,
            markInterrupted = { runId, reason -> agentRunRecorder.markInterrupted(runId, reason) },
            markRemoteTerminal = agentRunRecorder::reconcileRemoteTerminal
        ).recover(excludedRunIds = activeRunIds)
    }
    results.filter { it.outcome in setOf(
        AgentRunRecoveryOutcome.RESTORED_LOCAL_WAIT,
        AgentRunRecoveryOutcome.RECONNECTED_REMOTE,
        AgentRunRecoveryOutcome.WAITING_FOR_REMOTE,
        AgentRunRecoveryOutcome.ALREADY_CURRENT
    ) }.forEach { result ->
        agentRunEventStore.latestEvent(result.runId)?.messageId
            ?.takeIf(String::isNotBlank)
            ?.let { messageId -> agentRunIdsByTurn[messageId] = result.runId }
    }
    reconcileStructuredAgentHandoffs()
}

internal fun MainActivity.reconcileStructuredAgentHandoffs() {
    if (!isAgentHandoffStoreInitialized()) return
    agentHandoffStore.active().forEach { handoff ->
        val run = agentRunRecorder.run(handoff.request.runId) ?: return@forEach
        val terminalState = when (run.status) {
            AgentRecordedRunStatus.COMPLETED -> AgentHandoffState.RETURNED
            AgentRecordedRunStatus.CANCELLED -> AgentHandoffState.CANCELLED
            AgentRecordedRunStatus.FAILED -> AgentHandoffState.FAILED
            AgentRecordedRunStatus.RUNNING -> null
        } ?: return@forEach
        agentHandoffStore.finish(
            runId = handoff.request.runId,
            sourceMessageId = handoff.sourceMessageId,
            state = terminalState,
            resultSummary = "Recovered terminal run: ${run.status.name.lowercase(Locale.ROOT)}"
        )
    }
}
