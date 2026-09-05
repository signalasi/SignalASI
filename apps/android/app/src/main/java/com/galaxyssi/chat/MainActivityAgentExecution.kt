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

internal fun MainActivity.agentClarificationQuestion(question: AgentClarificationQuestion): String =
    getString(
        when (question) {
            AgentClarificationQuestion.CODE_OUTCOME -> R.string.agent_clarify_code_outcome
            AgentClarificationQuestion.CONTROL_ACTION -> R.string.agent_clarify_control_action
            AgentClarificationQuestion.RESEARCH_TOPIC -> R.string.agent_clarify_research_topic
            AgentClarificationQuestion.FILE_ACTION -> R.string.agent_clarify_file_action
            AgentClarificationQuestion.MEMORY_CONTENT -> R.string.agent_clarify_memory_content
            AgentClarificationQuestion.AUTOMATION_DETAILS -> R.string.agent_clarify_automation_details
            AgentClarificationQuestion.NONE,
            AgentClarificationQuestion.TASK_GOAL -> R.string.agent_clarify_task_goal
        }
    )

internal fun MainActivity.updateAgentSubmitButtonAppearance(hasInput: Boolean) {
    val composerState = AgentComposerUiPolicy.resolve(
        hasInput = hasInput,
        textModeActive = agentComposerTextMode,
        actionTrayRequested = agentActionTrayExpanded
    )
    agentActionTrayExpanded = composerState.showActionTray
    agentPrimaryActionSlot.visibility = if (composerState.showPrimaryActionSlot) View.VISIBLE else View.GONE
    agentAttachButton.visibility = if (composerState.showMoreButton) View.VISIBLE else View.GONE
    agentSubmitButton.visibility = if (composerState.showSendButton) View.VISIBLE else View.GONE
    agentActionTray.visibility = if (composerState.showActionTray) View.VISIBLE else View.GONE
    agentAttachButton.renderComposerMoreButton(composerState.showActionTray)
    agentAttachButton.contentDescription = getString(
        if (composerState.showActionTray) R.string.agent_attachment_close_menu
        else R.string.agent_attachment_open_menu
    )
    agentSubmitButton.setBackgroundResource(android.R.color.transparent)
    agentSubmitButton.imageTintList = android.content.res.ColorStateList.valueOf(
        getColorCompat(R.color.composer_send_icon)
    )
}

internal fun MainActivity.setAgentActionTrayExpanded(expanded: Boolean) {
    if (expanded) {
        exitAgentComposerTextMode(hideKeyboard = true)
    }
    agentActionTrayExpanded = expanded
    updateAgentSubmitButtonAppearance(
        agentGoalInput.text?.toString()?.isNotBlank() == true || agentInputAttachments.isNotEmpty()
    )
}

internal fun MainActivity.enterAgentComposerTextMode() {
    if (agentComposerTextMode) return
    agentComposerTextMode = true
    agentComposerKeyboardObserved = false
    agentComposerRow.clearFocus()
    agentGoalInput.requestFocus()
    agentGoalInput.setSelection(agentGoalInput.text?.length ?: 0)
    updateAgentSubmitButtonAppearance(
        agentGoalInput.text?.toString()?.isNotBlank() == true || agentInputAttachments.isNotEmpty()
    )
    agentGoalInput.post {
        getSystemService(InputMethodManager::class.java)
            .showSoftInput(agentGoalInput, InputMethodManager.SHOW_IMPLICIT)
    }
}

internal fun MainActivity.exitAgentComposerTextMode(hideKeyboard: Boolean) {
    agentComposerTextMode = false
    agentComposerKeyboardObserved = false
    if (hideKeyboard) {
        getSystemService(InputMethodManager::class.java)
            .hideSoftInputFromWindow(agentGoalInput.windowToken, 0)
    }
    agentGoalInput.clearFocus()
    agentComposerRow.isFocusableInTouchMode = true
    agentComposerRow.requestFocus()
    updateAgentSubmitButtonAppearance(
        agentGoalInput.text?.toString()?.isNotBlank() == true || agentInputAttachments.isNotEmpty()
    )
}

internal fun MainActivity.installAgentComposerKeyboardObserver() {
    val root = findViewById<View>(android.R.id.content)
    root.viewTreeObserver.addOnGlobalLayoutListener {
        if (!agentComposerTextMode) return@addOnGlobalLayoutListener
        val visibleFrame = Rect()
        root.getWindowVisibleDisplayFrame(visibleFrame)
        val rootHeight = root.rootView.height.coerceAtLeast(1)
        val keyboardVisible = rootHeight - visibleFrame.bottom > rootHeight * 0.15f
        if (keyboardVisible) {
            agentComposerKeyboardObserved = true
        } else if (agentComposerKeyboardObserved) {
            agentComposerKeyboardClosedAt = SystemClock.elapsedRealtime()
            exitAgentComposerTextMode(hideKeyboard = false)
        }
    }
}

internal fun MainActivity.bindAgentExecutionLoop(
    runtime: MobileNativeAgent,
    turnId: String,
    taskContext: AgentTaskContext? = null
) {
    val cleanTurnId = turnId.trim()
    val supervisor = cleanTurnId.takeIf(String::isNotBlank)
        ?.let { AgentTaskRuntime.supervisor(this) }
    runtime.bindExecutionLoopEventSink(AgentExecutionLoopEventSink { event ->
        if (cleanTurnId.isBlank()) return@AgentExecutionLoopEventSink
        if (taskContext != null) {
            taskContext.persistExecutionLoop(event)
        } else {
            if (!event.phase.isTerminal) {
                supervisor?.cancellationSource(cleanTurnId)?.throwIfCancellationRequested()
            }
            runCatching {
                supervisor?.persistExecutionLoop(cleanTurnId, event)
            }.onFailure { error ->
                Log.w(
                    "GalaxySSIAgentLoop",
                    "loop_checkpoint_failed turn=${cleanTurnId.take(8)}",
                    error
                )
            }
        }
        recordAgentExecutionLoopEvent(runtime, cleanTurnId, event)
    })
}

internal fun MainActivity.recordAgentExecutionLoopEvent(
    runtime: MobileNativeAgent,
    turnId: String,
    event: AgentExecutionLoopEvent
) {
    val persistedRunId = runCatching {
        EncryptedAgentWorkspaceStore(this).find(turnId)?.parentRunId.orEmpty()
    }.getOrDefault("")
    val runId = agentRunIdsByTurn[turnId].orEmpty().ifBlank { persistedRunId }
    val run = agentRunRecorder.run(runId) ?: return
    val projection = AgentExecutionLoopTimelinePolicy.project(event)
    val revisionRecorded = agentRunEventStore.events(run.runId).any { recorded ->
        AgentExecutionLoopTimelinePolicy.isSameRevision(
            recorded,
            event.snapshot.revision
        )
    }
    if (!revisionRecorded) {
        appendRunControlEvent(
            run = run,
            messageId = turnId,
            taskId = turnId,
            agentId = "galaxyssi-mobile",
            type = projection.controlEventType,
            payload = projection.payload,
            stepId = projection.stepId,
            toolCallId = projection.toolCallId,
            timestampMillis = event.snapshot.updatedAtMillis
        )
    }
    val label = projection.label ?: return
    if (runtime.snapshot().plan?.isSupervisedProjectPlan() == true && label in setOf(
            AgentExecutionLoopTimelineLabel.ACT,
            AgentExecutionLoopTimelineLabel.OBSERVE,
            AgentExecutionLoopTimelineLabel.VERIFY,
            AgentExecutionLoopTimelineLabel.FINALIZE,
            AgentExecutionLoopTimelineLabel.LEARN
        )
    ) {
        return
    }
    val state = runtime.snapshot()
    agentTranscriptStore.append(
        AgentTranscriptRole.PROCESS,
        agentExecutionLoopTimelineText(label),
        dedupeKey = AgentExecutionLoopTimelinePolicy.transcriptDedupeKey(turnId, event),
        timestampMillis = event.snapshot.updatedAtMillis,
        conversationId = run.conversationId,
        turnId = turnId,
        taskId = state.sessionId
    )
    runOnUiThread {
        if (run.conversationId == agentTranscriptStore.activeConversation().id) {
            refreshAgentTranscriptWindow(run.conversationId)
        }
    }
}

internal fun MainActivity.finalizeAgentExecutionLoop(
    runtime: MobileNativeAgent,
    turnId: String,
    state: AgentUiState
): AgentUiState {
    if (runtime.executionLoopSnapshot()?.phase == AgentExecutionLoopPhase.COMPLETED) {
        return state
    }
    if (state.phase != AgentPhase.COMPLETED) {
        recordAgentRunFromState(turnId, state)
        return state
    }
    val loopPhase = runtime.executionLoopSnapshot()?.phase
    if (loopPhase !in setOf(
            AgentExecutionLoopPhase.FINALIZE,
            AgentExecutionLoopPhase.LEARN
        ) &&
        !runtime.beginExecutionFinalization()
    ) {
        return runtime.snapshot().also { recordAgentRunFromState(turnId, it) }
    }
    if (runtime.executionLoopSnapshot()?.phase != AgentExecutionLoopPhase.LEARN &&
        !runtime.beginExecutionLearning()
    ) {
        return runtime.snapshot().also { recordAgentRunFromState(turnId, it) }
    }
    return runCatching {
        recordAgentRunFromState(turnId, state)
        runtime.completeExecutionLoop()
        runtime.snapshot()
    }.getOrElse { failure ->
        runtime.failExecutionLoop(
            failure.message.orEmpty().ifBlank { "Task finalization failed" }
        )
        runtime.snapshot()
    }
}

internal fun MainActivity.executeConcurrentAgentGoal(
    goal: String,
    conversationContext: AgentConversationContext,
    conversationId: String,
    turnId: String,
    deterministicAction: AgentAction? = null,
    executionMode: AgentTaskExecutionMode? = null
) {
    val submissionStartedAt = SystemClock.elapsedRealtime()
    val supervisedProject = AgentPhoneAgentLoopRoutingPolicy.shouldUseSupervisedLoop(
        goal = goal,
        conversationContext = conversationContext,
        selectedAction = deterministicAction
    )
    val selectedReasoningProvider = deterministicAction?.takeIf { action ->
        supervisedProject &&
        action.kind == AgentActionKind.CALL_CONNECTOR &&
            action.parameters["connector_id"] != UNAVAILABLE_REASONING_CONNECTOR_ID
    }
    if (supervisedProject) {
        agentTranscriptStore.entriesForTurn(turnId)
            .filter { entry -> entry.dedupeKey.startsWith("agent-recovery:") }
            .forEach { entry ->
                deleteAgentTranscriptByDedupeKey(conversationId, entry.dedupeKey)
            }
        agentTranscriptStore.upsert(
            role = AgentTranscriptRole.PROCESS,
            text = getString(R.string.agent_loop_context_phone_project),
            dedupeKey = "agent-loop-context:$turnId",
            conversationId = conversationId,
            turnId = turnId,
            taskId = turnId
        )
        runOnUiThread {
            if (conversationId == agentTranscriptStore.activeConversation().id) {
                refreshAgentTranscriptWindow(conversationId)
            }
        }
    }
    val workspace = AgentWorkspace(
        workspaceId = turnId,
        sessionId = turnId,
        conversationId = conversationId,
        taskId = turnId,
        goal = goal,
        parentRunId = agentRunIdsByTurn[turnId].orEmpty(),
        agentId = deterministicAction?.parameters?.get("connector_id").orEmpty()
            .ifBlank { "galaxyssi-mobile" },
        deviceId = AppStore.profile(this).optString("device_id")
            .ifBlank { AppStore.profile(this).optString("galaxyssi_id") },
        status = AgentWorkspaceStatus.CREATED
    )
    AgentTaskRuntime.supervisor(this).submit(
        workspace,
        AgentTaskLane.READ_REASONING,
        AgentTaskPriority.FOREGROUND
    ) {
        Log.i(
            "GalaxySSILatency",
            "agent_runtime stage=task_started turn=${turnId.take(8)} " +
                "elapsed_ms=${SystemClock.elapsedRealtime() - submissionStartedAt}"
        )
        progress("planning", "Planning task")
        Log.i(
            "GalaxySSILatency",
            "agent_runtime stage=planning_recorded turn=${turnId.take(8)} " +
                "elapsed_ms=${SystemClock.elapsedRealtime() - submissionStartedAt}"
        )
        lateinit var runtime: MobileNativeAgent
        val sharedNativeToolRegistry by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
            AgentPhoneNativeToolCatalog.defaultRegistry(
                context = this@executeConcurrentAgentGoal,
                screenProvider = { runtime.currentScreen },
                actionExecutor = directAgentActionExecutor
            )
        }
        runtime = MobileNativeAgent(
            this@executeConcurrentAgentGoal,
            planner = when {
                selectedReasoningProvider != null ->
                    AgentPhoneReasoningProviderPlanner(selectedReasoningProvider)
                deterministicAction != null -> object : AgentPlanner {
                    override fun plan(request: AgentRequest): AgentPlan =
                        AgentPlanFactory.actions(request, listOf(deterministicAction)).copy(
                            plannerProfile = "deterministic-native-route",
                            routeRationale = "An exact phone-native route was selected before model planning."
                        )
                }
                else -> GuardedModelAgentPlanner(
                    context = this@executeConcurrentAgentGoal,
                    modelToolLoopEventSink = AgentModelToolLoopEventSink { event ->
                        recordModelToolLoopEvent(conversationId, turnId, event)
                    },
                    modelToolLoopCancellationToken =
                        cancellationSource.asNativeToolCancellationToken(),
                    nativeToolRegistryProvider = { sharedNativeToolRegistry }
                )
            },
            actionExecutor = directAgentActionExecutor,
            sessionStore = SharedPreferencesAgentSessionStore(this@executeConcurrentAgentGoal, "task:$turnId"),
            nativeToolEventSink = AgentNativeToolEventSink(::recordNativeToolLifecycleEvent),
            screenObservationOverride = deterministicAction?.let { selectedAction ->
                AgentScreenObservationPolicy.requiresObservation(goal, selectedAction)
            },
            nativeToolRegistryProvider = { sharedNativeToolRegistry }
        )
        bindAgentExecutionLoop(runtime, turnId, this)
        provisionalAgentTasks.add(runtime)
        agentRuntimeConversationIds[runtime] = conversationId
        agentRuntimeTurnIds[runtime] = turnId
        val voiceTraceId = voiceTraceIdsByTurn[turnId].orEmpty()
        val outcome = runCatching {
            VoiceLatencyTraceContext.withTrace(voiceTraceId) {
                var state = runtime.submitGoal(
                    goal,
                    conversationContext,
                    turnId,
                    executionMode,
                    requestedMembers = AgentTurnMentionRegistry.take(turnId)
                )
                while (state.pendingAction != null &&
                    state.phase != AgentPhase.WAITING_RESPONSE
                ) {
                    state = runtime.approveNextAction(highRiskConfirmed = true)
                }
                check(
                    state.phase != AgentPhase.OBSERVING ||
                        state.plan != null ||
                        state.lastActionResult != null
                ) {
                    "Agent submission completed without a plan or result"
                }
                state
            }
        }
        var state = outcome.getOrElse { failure ->
            Log.e(
                "GalaxySSIAgent",
                "agent_submission_failed turn=${turnId.take(8)}",
                failure
            )
            runtime.failSubmission(
                failure.message.orEmpty().ifBlank { "Agent submission failed" }
            )
        }
        state = finalizeAgentExecutionLoop(runtime, turnId, state)
        val coordinatorSessionId = voiceCoordinatorIdsByTurn[turnId]
            .orEmpty()
            .ifBlank { voiceCoordinatorSession(voiceTraceId) }
        if (coordinatorSessionId.isNotBlank()) {
            val selectedTarget = state.plan?.selectedAgentOrModel.orEmpty()
            if (voiceInteractionCoordinator.snapshot().phase == VoiceInteractionPhase.ROUTING) {
                dispatchVoiceCoordinator(
                    VoiceInteractionEvent.RouteSelected(
                        coordinatorSessionId,
                        VoiceRouteDecision(
                            kind = if (state.phase == AgentPhase.WAITING_RESPONSE ||
                                selectedTarget.isNotBlank() && selectedTarget != "galaxyssi-mobile"
                            ) {
                                VoiceRouteKind.REMOTE_AGENT
                            } else {
                                VoiceRouteKind.LOCAL_ACTION
                            },
                            targetId = selectedTarget
                        )
                    )
                )
            }
            when (state.phase) {
                AgentPhase.COMPLETED -> {
                    val currentPhase = voiceInteractionCoordinator.snapshot().phase
                    dispatchVoiceCoordinator(
                        if (currentPhase == VoiceInteractionPhase.EXECUTING_LOCAL_ACTION) {
                            VoiceInteractionEvent.LocalActionCompleted(coordinatorSessionId)
                        } else {
                            VoiceInteractionEvent.Completed(coordinatorSessionId)
                        }
                    )
                }
                AgentPhase.BLOCKED,
                AgentPhase.FAILED -> dispatchVoiceCoordinator(
                    VoiceInteractionEvent.Failed(
                        coordinatorSessionId,
                        VoiceFailure(
                            code = "mobile_agent_${state.phase.name.lowercase(Locale.ROOT)}",
                            recoverable = state.phase != AgentPhase.BLOCKED,
                            stage = voiceInteractionCoordinator.snapshot().phase
                        )
                    )
                )
                AgentPhase.CANCELLED -> dispatchVoiceCoordinator(
                    VoiceInteractionEvent.Cancelled(coordinatorSessionId, "mobile_agent_cancelled")
                )
                else -> Unit
            }
            if (state.phase in setOf(
                    AgentPhase.COMPLETED,
                    AgentPhase.BLOCKED,
                    AgentPhase.FAILED,
                    AgentPhase.CANCELLED
                )
            ) {
                voiceCoordinatorIdsByTurn.remove(turnId)
            }
        }
        progress("agent.${state.phase.name.lowercase(Locale.ROOT)}", state.phase.name)
        persistAgentWorkspaceSnapshot(turnId, state, runtime)
        appendEvent(
            kind = "agent.state",
            message = state.phase.name,
            payloadJson = JSONObject()
                .put("session_id", state.sessionId)
                .put("phase", state.phase.name)
                .put("goal", goal.take(2_000))
                .toString()
        )
        runOnUiThread {
            if (conversationId == agentTranscriptStore.activeConversation().id) {
                mobileNativeAgent = runtime
            }
            state.lastActionResult?.metadata?.get("source_message_id")?.toLongOrNull()?.let { sourceId ->
                val sourceAction = state.plan?.actions?.firstOrNull { action ->
                    action.id == state.lastActionResult?.actionId
                }
                if (sourceAction?.isSupervisedProjectConnector() == true) {
                    supervisedProjectConnectorSourceIds.add(sourceId)
                    pendingAgentConnectorStreamUpdates.remove(sourceId)
                    liveAgentConnectorStreams.remove(sourceId)
                }
                activeAgentTasks[sourceId] = runtime
                provisionalAgentTasks.remove(runtime)
                AgentPendingDeliveryStore.put(
                    this@executeConcurrentAgentGoal,
                    AgentPendingDelivery(
                        sourceMessageId = sourceId,
                        conversationId = conversationId,
                        turnId = turnId,
                        taskId = state.lastActionResult?.metadata?.get("remote_task_id").orEmpty()
                            .ifBlank { turnId },
                        contactId = state.lastActionResult?.metadata?.get("contact_id").orEmpty()
                    )
                )
                scheduleConnectorTimeouts(runtime, sourceId, conversationId, turnId)
            }
            renderAgentState(state, conversationId, turnId)
            requestMissingAgentNativePermissions(state)
            consumePendingAgentConnectorResponses()
            outcome.exceptionOrNull()?.let { error ->
                provisionalAgentTasks.remove(runtime)
                Toast.makeText(this@executeConcurrentAgentGoal, error.message ?: "Agent operation failed", Toast.LENGTH_LONG).show()
            }
        }
        when (state.phase) {
            AgentPhase.WAITING_CONFIRMATION -> waitForConfirmation(state.pendingAction?.description.orEmpty())
            AgentPhase.WAITING_RESPONSE -> waitForResponse(state.lastActionResult?.message.orEmpty())
            AgentPhase.PAUSED -> pause(state.lastActionResult?.message.orEmpty())
            AgentPhase.BLOCKED -> blockTask(state.plan?.safetyReview?.reason.orEmpty())
            AgentPhase.FAILED -> error(state.lastActionResult?.message.orEmpty().ifBlank { "Agent task failed" })
            AgentPhase.CANCELLED -> cancellationSource.cancel("Agent task cancelled")
            else -> Unit
        }
    }
}

internal fun MainActivity.scheduleConnectorTimeouts(
    runtime: MobileNativeAgent,
    sourceMessageId: Long,
    conversationId: String,
    turnId: String
) {
    val metadata = runtime.pendingConnectorMetadata(sourceMessageId)
    if (metadata["resource_location"] != "desktop") return
    val deadlines = AgentConnectorTimingPolicy.deadlines(metadata["has_attachments"] == "true")
    val now = System.currentTimeMillis()
    val transportAcceptedAt = AgentConnectorTimingPolicy.deadlineStartMillis(
        AgentConnectorTimeoutStage.NOT_RUNNING,
        metadata
    )
    if (transportAcceptedAt <= 0L) {
        scheduleConnectorTimeout(
            runtime, sourceMessageId, conversationId, turnId,
            AgentRemoteTaskStatusPolicy.remainingDeadlineMillis(
                deadlines.acceptedMs,
                AgentConnectorTimingPolicy.deadlineStartMillis(
                    AgentConnectorTimeoutStage.NOT_ACCEPTED,
                    metadata
                ),
                now
            ),
            AgentConnectorTimeoutStage.NOT_ACCEPTED
        )
        return
    }
    scheduleConnectorTimeout(
        runtime, sourceMessageId, conversationId, turnId,
        AgentRemoteTaskStatusPolicy.remainingDeadlineMillis(
            deadlines.runningMs,
            transportAcceptedAt,
            now
        ),
        AgentConnectorTimeoutStage.NOT_RUNNING
    )
    if (metadata["routing_requires_live_data"] == "true") {
        scheduleConnectorTimeout(
            runtime, sourceMessageId, conversationId, turnId,
            AgentRemoteTaskStatusPolicy.remainingDeadlineMillis(
                deadlines.liveStaleMs,
                AgentConnectorTimingPolicy.deadlineStartMillis(
                    AgentConnectorTimeoutStage.READ_ONLY_STALE,
                    metadata
                ),
                now
            ),
            AgentConnectorTimeoutStage.READ_ONLY_STALE
        )
    }
}

internal fun MainActivity.scheduleConnectorTimeout(
    runtime: MobileNativeAgent,
    sourceMessageId: Long,
    conversationId: String,
    turnId: String,
    delayMs: Long,
    stage: AgentConnectorTimeoutStage
) {
    val callbackKey = "$sourceMessageId:${stage.name}"
    lateinit var callback: Runnable
    callback = Runnable {
        agentConnectorTimeoutCallbacks.remove(callbackKey, callback)
        if (isFinishing || isDestroyed) return@Runnable
        if (stage == AgentConnectorTimeoutStage.NOT_ACCEPTED &&
            GalaxySSILinkDeliveryStore.hasPendingClientSourceMessageId(this, sourceMessageId)
        ) {
            scheduleConnectorTimeout(
                runtime = runtime,
                sourceMessageId = sourceMessageId,
                conversationId = conversationId,
                turnId = turnId,
                delayMs = CONNECTOR_TRANSPORT_RECHECK_MILLIS,
                stage = stage
            )
            return@Runnable
        }
        thread(name = "galaxyssi-connector-timeout-${stage.name.lowercase(Locale.US)}") {
            val before = runtime.pendingConnectorMetadata(sourceMessageId)
            bindAgentExecutionLoop(runtime, turnId)
            var state = runtime.handleConnectorTimeout(sourceMessageId, stage) ?: return@thread
            state = finalizeAgentExecutionLoop(runtime, turnId, state)
            persistAgentWorkspaceSnapshot(turnId, state, runtime)
            val remoteTaskId = before["remote_task_id"].orEmpty()
            val contactId = before["contact_id"].orEmpty()
            finishStructuredAgentHandoff(
                turnId,
                AgentConnectorResponse(
                    sourceMessageId = sourceMessageId,
                    contactId = contactId,
                    content = "Connector timeout: ${stage.name.lowercase(Locale.ROOT)}",
                    conversationId = conversationId,
                    turnId = turnId,
                    taskId = remoteTaskId,
                    success = false
                )
            )
            if (remoteTaskId.isNotBlank() && contactId.isNotBlank()) {
                GalaxySSIMqttClient.publishAgentTaskCancel(
                    taskId = remoteTaskId,
                    contactId = contactId,
                    sourceMessageId = sourceMessageId,
                    conversationId = conversationId,
                    turnId = turnId,
                    topicOverride = AppStore.outgoingTopicForContact(this, contactId)
                )
            }
            val replacementSourceId = state.lastActionResult?.metadata
                ?.get("source_message_id")?.toLongOrNull()
                ?.takeIf { it != sourceMessageId }
            runOnUiThread {
                if (replacementSourceId != null) {
                    supersededConnectorSourceIds.add(sourceMessageId)
                    activeAgentTasks.remove(sourceMessageId)
                    activeAgentTasks[replacementSourceId] = runtime
                    if (sourceMessageId in supervisedProjectConnectorSourceIds) {
                        supervisedProjectConnectorSourceIds.add(replacementSourceId)
                    }
                    scheduleConnectorTimeouts(runtime, replacementSourceId, conversationId, turnId)
                }
                renderAgentState(state, conversationId, turnId)
            }
        }
    }
    agentConnectorTimeoutCallbacks.put(callbackKey, callback)
        ?.let(handler::removeCallbacks)
    handler.postDelayed(callback, delayMs)
}

private const val CONNECTOR_TRANSPORT_RECHECK_MILLIS = 5_000L

internal fun MainActivity.cancelConnectorTimeouts(sourceMessageId: Long) {
    val prefix = "$sourceMessageId:"
    agentConnectorTimeoutCallbacks.entries
        .filter { (key, _) -> key.startsWith(prefix) }
        .forEach { (key, callback) ->
            if (agentConnectorTimeoutCallbacks.remove(key, callback)) {
                handler.removeCallbacks(callback)
            }
        }
}

internal fun MainActivity.deterministicSystemActionFor(
    goal: String,
    conversationContext: AgentConversationContext
): AgentAction? = mobileNativeAgent.resolveDeterministicAction(goal, conversationContext)

internal fun MainActivity.executeDirectSystemAction(
    action: AgentAction,
    conversationId: String,
    turnId: String,
    conversationContext: AgentConversationContext? = null,
    goal: String = "",
    executionMode: AgentTaskExecutionMode = AgentTaskExecutionMode.AUTO_COMPLETE
) {
    val contextualAction = conversationContext?.let { context ->
        action.withDirectConversationContext(
            conversationContext = context,
            turnId = turnId,
            goal = goal,
            executionMode = executionMode
        )
    } ?: action
    val missingPermissions = if (contextualAction.kind == AgentActionKind.CALL_NATIVE_TOOL) {
        val toolId = contextualAction.parameters["tool_id"].orEmpty()
        mobileNativeAgent.snapshot().runtimeContext.nativeTools
            .firstOrNull { it.id == toolId }
            ?.requiredPermissions
            .orEmpty()
            .filter { it.required && checkSelfPermission(it.id) != PackageManager.PERMISSION_GRANTED }
            .map { it.id }
            .distinct()
    } else {
        emptyList()
    }
    if (missingPermissions.isNotEmpty()) {
        pendingDirectSystemAction = PendingDirectSystemAction(contextualAction, conversationId, turnId)
        requestPermissions(missingPermissions.toTypedArray(), REQUEST_AGENT_NATIVE_PERMISSIONS)
        return
    }
    val screen = mobileNativeAgent.snapshot().currentScreen
    thread(name = "galaxyssi-agent-system-action") {
        val dispatchStartedAt = SystemClock.elapsedRealtime()
        val outcome = runCatching {
            if (contextualAction.kind == AgentActionKind.CALL_NATIVE_TOOL) {
                val notifications = AgentActionNotificationCenter(this@executeDirectSystemAction)
                notifications.showRunning(contextualAction)
                mobileNativeAgent.executeDirectAction(contextualAction, conversationId, turnId).also { result ->
                    notifications.showResult(contextualAction, result)
                }
            } else {
                directAgentActionExecutor.execute(contextualAction, screen)
            }
        }
        Log.i(
            "GalaxySSILatency",
            "agent_route stage=direct_action_dispatched turn=${turnId.take(8)} " +
                "elapsed_ms=${SystemClock.elapsedRealtime() - dispatchStartedAt}"
        )
        runOnUiThread {
            val result = outcome.getOrElse { error ->
                AgentActionResult(contextualAction.id, false, error.message ?: "Agent operation failed")
            }
            appendDirectSystemResult(contextualAction, conversationId, turnId, result)
            if (result.metadata["awaiting_response"] != "true") {
                recordDirectAgentRun(turnId, contextualAction, result)
            }
        }
    }
}

internal fun MainActivity.handleAgentSkillCommand(goal: String, conversationId: String, turnId: String): Boolean {
    if (!AgentSkillCommandParser.isSaveCommand(goal) && !AgentSkillCommandParser.isUpgradeCommand(goal)) return false
    val context = agentRunRecorder.context(conversationId)
    val runs = context?.let { agentRunRecorder.runsForThread(it.taskThreadId) }.orEmpty()
        .filter { it.status == AgentRecordedRunStatus.COMPLETED }
        .takeLast(1)
    val outcome = runCatching {
        if (AgentSkillCommandParser.isUpgradeCommand(goal)) {
            val skillId = context?.activeSkillId.orEmpty()
            val current = agentSkillRuntime.list(enabledOnly = true)
                .filter { it.id == skillId }
                .maxByOrNull { skillVersionParts(it.version) }
                ?: error("The active task was not produced by an installed Skill")
            AgentSkillVersionManager(agentSkillRuntime).upgrade(current, runs)
        } else {
            AgentConversationSkillCompiler(agentSkillRuntime, mobileNativeAgent::nativeToolCatalog).install(runs)
        }
    }
    val message = outcome.fold(
        onSuccess = { installation ->
            agentRunRecorder.setActiveSkill(conversationId, installation.id)
            getString(
                R.string.agent_skill_saved_message,
                installation.manifest.title,
                installation.version,
                installation.manifest.nativeTools.size
            )
        },
        onFailure = { error ->
            val detail = generateSequence(error) { it.cause }
                .mapNotNull { cause -> cause.message?.takeIf(String::isNotBlank) }
                .firstOrNull()
                ?: error.javaClass.simpleName
            getString(R.string.agent_skill_save_failed, detail)
        }
    )
    agentTranscriptStore.append(
        AgentTranscriptRole.ASSISTANT,
        message,
        dedupeKey = "skill-command:$turnId",
        conversationId = conversationId,
        turnId = turnId,
        taskId = turnId
    )
    refreshAgentTranscriptWindow(conversationId)
    return true
}

internal fun MainActivity.executeMatchedSkill(
    match: AgentSkillMatch,
    conversationId: String,
    turnId: String,
    goal: String,
    conversationContext: AgentConversationContext
): Boolean {
    if (AGENT_ORCHESTRATION_TOOL_ID in match.installation.manifest.nativeTools) {
        agentSkillRuntime.recordUse(match.installation.id, match.installation.version)
        val savedRequest = match.installation.manifest.triggerExamples.firstOrNull().orEmpty()
        val transformedGoal = AgentSkillRequestTransformer.transform(savedRequest, goal)
        val learnedGoal = if (transformedGoal != goal.trim()) transformedGoal else buildString {
            append("Apply the saved Skill named ")
            append(match.installation.manifest.title)
            append(". Follow these learned instructions: ")
            append(match.installation.manifest.instructions)
            append("\nCurrent user request: ")
            append(goal)
        }
        val isolatedSkillContext = AgentConversationContext(
            conversationId = "skill:${match.installation.id}:$turnId",
            summary = "",
            turns = emptyList(),
            privateMode = conversationContext.privateMode
        )
        executeConcurrentAgentGoal(learnedGoal, isolatedSkillContext, conversationId, turnId)
        return true
    }
    thread(name = "galaxyssi-agent-skill") {
        val result = runCatching {
            AgentSkillExecutionEngine(agentSkillRuntime, mobileNativeAgent).execute(match, conversationId, turnId)
        }.getOrElse { error ->
            AgentSkillExecutionResult(false, match.installation.id, match.installation.version, error.message ?: "Skill failed")
        }
        runOnUiThread {
            if (result.success) {
                val text = getString(
                    R.string.agent_skill_result_message,
                    match.installation.manifest.title,
                    match.installation.version,
                    result.message
                )
                agentTranscriptStore.append(
                    AgentTranscriptRole.ASSISTANT,
                    text,
                    dedupeKey = "skill-result:$turnId",
                    conversationId = conversationId,
                    turnId = turnId,
                    taskId = turnId
                )
                refreshAgentTranscriptWindow(conversationId)
                recordSkillAgentRun(turnId, result)
            } else {
                agentTranscriptStore.append(
                    AgentTranscriptRole.PROCESS,
                    getString(R.string.agent_skill_fallback_message),
                    dedupeKey = "skill-fallback:$turnId",
                    conversationId = conversationId,
                    turnId = turnId,
                    taskId = turnId
                )
                executeConcurrentAgentGoal(goal, conversationContext, conversationId, turnId)
            }
        }
    }
    return true
}

internal fun MainActivity.recordDirectAgentRun(turnId: String, action: AgentAction, result: AgentActionResult) {
    val runId = agentRunIdsByTurn.remove(turnId) ?: return
    agentRunRecorder.run(runId) ?: return
    val toolName = action.parameters["tool_id"].orEmpty().ifBlank { "android.${action.kind.name.lowercase()}" }
    val nativeResultJson = result.metadata["native_tool_output"].orEmpty()
    val invocationId = result.metadata["invocation_id"].orEmpty().ifBlank { action.id }
    val cancelled = result.metadata["remote_task_status"] == "cancelled"
    val call = AgentToolCallRecord(
        id = invocationId,
        toolName = toolName,
        status = if (cancelled) AgentToolCallStatus.CANCELLED else if (result.success) AgentToolCallStatus.SUCCEEDED else AgentToolCallStatus.FAILED,
        argumentsJson = action.parameters["input_json"].orEmpty().ifBlank { JSONObject(action.parameters).toString() },
        resultJson = nativeResultJson.ifBlank { JSONObject(result.metadata).put("message", result.message).toString() },
        errorMessage = if (result.success) "" else result.message,
        startedAtMillis = result.metadata["started_at_millis"]?.toLongOrNull() ?: System.currentTimeMillis(),
        completedAtMillis = result.metadata["completed_at_millis"]?.toLongOrNull() ?: System.currentTimeMillis()
    )
    agentRunRecorder.complete(
        runId = runId,
        planJson = JSONArray().put(JSONObject().put("action", action.id).put("kind", action.kind.name)).toString(),
        toolCalls = listOf(call),
        sourcesJson = "[]",
        finalOutputJson = JSONObject().put("text", result.message).toString(),
        renderSpecJson = "{}",
        artifacts = runtimeArtifactsFromResult(nativeResultJson),
        success = result.success,
        finalStatus = if (cancelled) AgentRecordedRunStatus.CANCELLED else null,
        executionResourceId = "galaxyssi-mobile"
    )?.let(::observeCompletedAgentRun)
}

internal fun MainActivity.recordSkillAgentRun(turnId: String, result: AgentSkillExecutionResult) {
    val runId = agentRunIdsByTurn.remove(turnId) ?: return
    agentRunRecorder.run(runId) ?: return
    val toolIds = agentSkillRuntime.get(result.skillId, result.version)?.manifest?.steps.orEmpty().map { it.toolId }
    val calls = result.toolResults.mapIndexed { index, toolResult ->
        AgentToolCallRecord(
            id = toolResult.receipt.invocationId.ifBlank { "skill-${index + 1}" },
            toolName = toolIds.getOrElse(index) { "unknown" },
            status = if (toolResult.isSuccess) AgentToolCallStatus.SUCCEEDED else AgentToolCallStatus.FAILED,
            resultJson = AgentNativeJsonCodec.stringify(toolResult.output),
            errorMessage = if (toolResult.isSuccess) "" else toolResult.message,
            startedAtMillis = toolResult.receipt.startedAtEpochMillis,
            completedAtMillis = toolResult.receipt.finishedAtEpochMillis
        )
    }
    agentRunRecorder.complete(
        runId, "[]", calls, "[]",
        JSONObject().put("text", result.message).toString(),
        "{}",
        result.toolResults.flatMap { runtimeArtifactsFromResult(AgentNativeJsonCodec.stringify(it.output)) }
            .distinctBy { it.id },
        result.success,
        executionResourceId = "skill:${result.skillId}"
    )?.let(::observeCompletedAgentRun)
}

internal fun MainActivity.persistAgentWorkspaceSnapshot(
    turnId: String,
    state: AgentUiState,
    runtime: MobileNativeAgent = mobileNativeAgent,
    interruptedRecoveryReason: String = ""
) {
    runCatching {
        val actions = (state.plan?.actionHistory.orEmpty() + state.plan?.actions.orEmpty())
            .distinctBy(AgentAction::id)
        val result = state.lastActionResult
        val toolCalls = actions
            .filter { it.kind == AgentActionKind.CALL_NATIVE_TOOL || it.kind == AgentActionKind.CALL_CONNECTOR }
            .map { action ->
                val isLast = result?.actionId == action.id
                AgentToolCallRecord(
                    id = if (isLast) result?.metadata?.get("invocation_id").orEmpty()
                        .ifBlank { action.id } else action.id,
                    toolName = action.parameters["tool_id"].orEmpty()
                        .ifBlank { action.parameters["connector_id"].orEmpty() }
                        .ifBlank { action.kind.name.lowercase(Locale.ROOT) },
                    status = when (action.status) {
                        AgentActionStatus.PROPOSED,
                        AgentActionStatus.PENDING_CONFIRMATION -> AgentToolCallStatus.PENDING
                        AgentActionStatus.RUNNING,
                        AgentActionStatus.WAITING_RESPONSE -> AgentToolCallStatus.RUNNING
                        AgentActionStatus.COMPLETED -> AgentToolCallStatus.SUCCEEDED
                        AgentActionStatus.FAILED,
                        AgentActionStatus.BLOCKED,
                        AgentActionStatus.ROLLED_BACK -> AgentToolCallStatus.FAILED
                    },
                    argumentsJson = action.parameters["input_json"].orEmpty()
                        .ifBlank { JSONObject(action.parameters).toString() },
                    resultJson = if (isLast) {
                        result?.metadata?.get("native_tool_output").orEmpty()
                            .ifBlank { JSONObject(result?.metadata.orEmpty()).put("message", result?.message.orEmpty()).toString() }
                    } else {
                        JSONObject().put("message", action.result).toString()
                    },
                    errorMessage = if (action.status in setOf(
                            AgentActionStatus.FAILED,
                            AgentActionStatus.BLOCKED,
                            AgentActionStatus.ROLLED_BACK
                        )
                    ) action.result else "",
                    startedAtMillis = if (isLast) {
                        result?.metadata?.get("started_at_millis")?.toLongOrNull() ?: 0L
                    } else 0L,
                    completedAtMillis = if (isLast) {
                        result?.metadata?.get("completed_at_millis")?.toLongOrNull() ?: 0L
                    } else 0L
                )
            }
        val pendingScope = state.pendingAction?.let(AgentConfirmationPolicy::consentKey).orEmpty()
        val grantIds = if (pendingScope.isBlank()) emptyList() else {
            EncryptedAgentPermissionGrantStore(this).list(includeInactive = false)
                .filter { it.scope == pendingScope }
                .map(AgentPermissionGrant::grantId)
        }
        val routeTarget = state.plan?.route?.targetId.orEmpty()
            .ifBlank { state.plan?.selectedAgentOrModel.orEmpty() }
            .ifBlank { "galaxyssi-mobile" }
        val sourceMessageId = result?.metadata?.get("source_message_id").orEmpty()
        val planJson = JSONArray().apply {
            actions.forEach { action ->
                put(JSONObject()
                    .put("id", action.id)
                    .put("kind", action.kind.name)
                    .put("target", action.target)
                    .put("status", action.status.name))
            }
        }.toString()
        val resultJson = JSONObject()
            .put("phase", state.phase.name)
            .put("message", result?.message.orEmpty())
            .put("metadata", JSONObject(result?.metadata.orEmpty()))
            .put(
                "execution_loop",
                runtime.executionLoopSnapshot()
                    ?.let(AgentExecutionLoopJsonCodec::encode)
                    ?.let(::JSONObject)
            )
            .toString()
        val profile = AppStore.profile(this)
        val supervisor = AgentTaskRuntime.supervisor(this)
        val snapshot = AgentWorkspaceExecutionSnapshot(
                status = state.phase.toWorkspaceStatus(),
                planSnapshot = planJson,
                resultJson = resultJson,
                errorMessage = if (state.phase in setOf(AgentPhase.FAILED, AgentPhase.BLOCKED)) {
                    result?.message.orEmpty()
                } else "",
                toolCalls = toolCalls,
                artifacts = runtimeArtifactsFromResult(result?.metadata?.get("native_tool_output").orEmpty()),
                permissionGrantIds = grantIds,
                permissionScopes = listOfNotNull(pendingScope.takeIf(String::isNotBlank)),
                handoffIds = listOfNotNull(
                    sourceMessageId.takeIf(String::isNotBlank)?.let { "$routeTarget:$it" }
                ),
                agentId = routeTarget,
                deviceId = profile.optString("device_id").ifBlank { profile.optString("galaxyssi_id") },
                remoteRunId = result?.metadata?.get("remote_task_id").orEmpty()
                    .ifBlank { sourceMessageId },
                lastRemoteEventSequence = result?.metadata?.get("last_event_sequence")?.toLongOrNull() ?: 0L
            )
        if (interruptedRecoveryReason.isNotBlank()) {
            supervisor.recordRecoveredExecutionSnapshot(
                workspaceId = turnId,
                snapshot = snapshot,
                reason = interruptedRecoveryReason
            )
        } else {
            supervisor.recordExecutionSnapshot(turnId, snapshot)
        }
        supervisor.checkpoint(
            workspaceId = turnId,
            checkpointId = "state-${state.sessionId.take(48)}",
            planSnapshot = planJson,
            stateJson = JSONObject()
                .put("phase", state.phase.name)
                .put("pending_action_id", state.pendingAction?.id.orEmpty())
                .put("permission_scope", pendingScope)
                .put("agent_id", routeTarget)
                .put("remote_run_id", result?.metadata?.get("remote_task_id").orEmpty())
                .put(
                    "execution_loop",
                    runtime.executionLoopSnapshot()
                        ?.let(AgentExecutionLoopJsonCodec::encode)
                        ?.let(::JSONObject)
                )
                .toString()
        )
    }.onFailure { error ->
        Log.w("GalaxySSIAgent", "workspace_snapshot_failed turn=${turnId.take(8)}", error)
    }
}

internal fun MainActivity.recordAgentRunFromState(turnId: String, state: AgentUiState) {
    if (state.phase !in setOf(AgentPhase.COMPLETED, AgentPhase.FAILED, AgentPhase.CANCELLED, AgentPhase.BLOCKED)) return
    val persistedRunId = runCatching {
        EncryptedAgentWorkspaceStore(this).find(turnId)?.parentRunId.orEmpty()
    }.getOrDefault("")
    val runId = agentRunIdsByTurn.remove(turnId).orEmpty().ifBlank { persistedRunId }
    val run = agentRunRecorder.run(runId)
        ?.takeIf { it.status == AgentRecordedRunStatus.RUNNING }
        ?: return
    val result = state.lastActionResult
    val nativeActions = (state.plan?.actionHistory.orEmpty() + state.plan?.actions.orEmpty())
        .distinctBy { it.id }
        .filter { it.kind == AgentActionKind.CALL_NATIVE_TOOL }
    val calls = nativeActions.map { action ->
        val isLast = result?.actionId == action.id
        val succeeded = action.status == AgentActionStatus.COMPLETED || (isLast && result?.success == true)
        AgentToolCallRecord(
            id = if (isLast) result?.metadata?.get("invocation_id").orEmpty().ifBlank { action.id } else action.id,
            toolName = action.parameters["tool_id"].orEmpty(),
            status = if (succeeded) AgentToolCallStatus.SUCCEEDED else AgentToolCallStatus.FAILED,
            argumentsJson = action.parameters["input_json"].orEmpty().ifBlank { "{}" },
            resultJson = if (isLast) result?.metadata?.get("native_tool_output").orEmpty().ifBlank {
                JSONObject().put("message", action.result).toString()
            } else JSONObject().put("message", action.result).toString(),
            errorMessage = if (succeeded) "" else action.result,
            startedAtMillis = if (isLast) result?.metadata?.get("started_at_millis")?.toLongOrNull() ?: 0L else 0L,
            completedAtMillis = if (isLast) result?.metadata?.get("completed_at_millis")?.toLongOrNull() ?: 0L else 0L
        )
    }
    val routeTarget = state.plan?.route?.targetId.orEmpty()
        .ifBlank { state.plan?.selectedAgentOrModel.orEmpty() }
    val handoffAlreadyRecorded = agentRunEventStore.events(run.runId)
        .any { it.type == AgentRunControlEventType.HANDOFF }
    if (routeTarget.isNotBlank() && routeTarget != "galaxyssi-mobile" && !handoffAlreadyRecorded) {
        appendRunControlEvent(
            run = run,
            messageId = turnId,
            taskId = turnId,
            agentId = routeTarget,
            type = AgentRunControlEventType.HANDOFF,
            payload = mapOf(
                "from_agent_id" to "galaxyssi-mobile",
                "to_agent_id" to routeTarget,
                "reason" to state.plan?.routeRationale.orEmpty(),
                "return_to_agent_id" to "galaxyssi-mobile"
            )
        )
    }
    val planJson = JSONArray().apply {
        state.plan?.actions.orEmpty().forEach { item ->
            put(JSONObject().put("id", item.id).put("kind", item.kind.name).put("target", item.target))
        }
    }.toString()
    agentRunRecorder.complete(
        runId = runId,
        planJson = planJson,
        toolCalls = calls,
        sourcesJson = "[]",
        finalOutputJson = JSONObject().put("text", result?.message.orEmpty()).toString(),
        renderSpecJson = "{}",
        artifacts = runtimeArtifactsFromResult(result?.metadata?.get("native_tool_output").orEmpty()),
        success = state.phase == AgentPhase.COMPLETED,
        finalStatus = when (state.phase) {
            AgentPhase.COMPLETED -> AgentRecordedRunStatus.COMPLETED
            AgentPhase.CANCELLED -> AgentRecordedRunStatus.CANCELLED
            else -> AgentRecordedRunStatus.FAILED
        },
        executionResourceId = routeTarget.ifBlank { "galaxyssi-mobile" }
    )?.let(::observeCompletedAgentRun)
}

internal fun MainActivity.observeCompletedAgentRun(run: AgentRecordedRun) {
    val existing = agentRunEventStore.events(run.runId).lastOrNull()
    ensureRecordedRunTimeline(
        run = run,
        messageId = existing?.messageId.orEmpty().ifBlank { run.runId },
        taskId = existing?.taskId.orEmpty().ifBlank { run.taskThreadId },
        agentId = existing?.agentId.orEmpty().ifBlank { "galaxyssi-mobile" }
    )
    appendRunControlEvent(
        run = run,
        messageId = existing?.messageId.orEmpty().ifBlank { run.runId },
        taskId = existing?.taskId.orEmpty().ifBlank { run.taskThreadId },
        agentId = existing?.agentId.orEmpty().ifBlank { "galaxyssi-mobile" },
        type = when (run.status) {
            AgentRecordedRunStatus.COMPLETED -> AgentRunControlEventType.RUN_COMPLETED
            AgentRecordedRunStatus.CANCELLED -> AgentRunControlEventType.RUN_CANCELLED
            AgentRecordedRunStatus.RUNNING, AgentRecordedRunStatus.FAILED -> AgentRunControlEventType.RUN_FAILED
        },
        payload = mapOf(
            "timeline_contract" to AgentRunTimelineContract.VERSION,
            "timeline_kind" to if (run.status == AgentRecordedRunStatus.COMPLETED) "result" else "failure",
            "run_status" to run.status.name.lowercase(Locale.ROOT),
            "tool_call_count" to run.toolCalls.size,
            "artifact_count" to run.artifacts.size,
            "execution_resource_id" to run.executionResourceId
        )
    )
    val privateMode = agentTranscriptStore.context(run.conversationId).privateMode
    agentLearningEngine.observeCompletedRun(
        run = run,
        recentRuns = agentRunRecorder.recentRuns(),
        privateMode = privateMode,
        memoryCaptureEnabled = mobileNativeAgent.safetySettings().memoryCapture
    )
}

internal fun MainActivity.ensureRecordedRunTimeline(
    run: AgentRecordedRun,
    messageId: String,
    taskId: String,
    agentId: String
) {
    var events = agentRunEventStore.events(run.runId)
    if (!AgentRunTimelineContract.coverage(events).hasPlan) {
        val planStepCount = runCatching { JSONArray(run.agentPlanJson).length() }.getOrDefault(0)
        appendRunControlEvent(
            run = run,
            messageId = messageId,
            taskId = taskId,
            agentId = agentId,
            type = AgentRunControlEventType.PLANNING,
            payload = mapOf(
                "timeline_contract" to AgentRunTimelineContract.VERSION,
                "timeline_kind" to "plan",
                "plan_step_count" to planStepCount,
                "synthetic_from_recorded_run" to true
            ),
            stepId = "plan",
            timestampMillis = run.createdAtMillis
        )
        events = agentRunEventStore.events(run.runId)
    }
    run.toolCalls.forEach { call ->
        val hasStart = events.any {
            it.toolCallId == call.id && it.type == AgentRunControlEventType.TOOL_STARTED
        }
        if (!hasStart) {
            appendRunControlEvent(
                run = run,
                messageId = messageId,
                taskId = taskId,
                agentId = agentId,
                type = AgentRunControlEventType.TOOL_STARTED,
                payload = mapOf(
                    "timeline_contract" to AgentRunTimelineContract.VERSION,
                    "timeline_kind" to "tool",
                    "tool_id" to call.toolName,
                    "status" to "running",
                    "synthetic_from_recorded_run" to true
                ),
                stepId = call.id,
                toolCallId = call.id,
                timestampMillis = call.startedAtMillis.takeIf { it > 0L } ?: run.createdAtMillis
            )
            events = agentRunEventStore.events(run.runId)
        }
        val hasCompletion = events.any {
            it.toolCallId == call.id && it.type == AgentRunControlEventType.TOOL_COMPLETED
        }
        if (!hasCompletion) {
            appendRunControlEvent(
                run = run,
                messageId = messageId,
                taskId = taskId,
                agentId = agentId,
                type = AgentRunControlEventType.TOOL_COMPLETED,
                payload = mapOf(
                    "timeline_contract" to AgentRunTimelineContract.VERSION,
                    "timeline_kind" to "tool",
                    "tool_id" to call.toolName,
                    "status" to call.status.name.lowercase(Locale.ROOT),
                    "synthetic_from_recorded_run" to true
                ),
                stepId = call.id,
                toolCallId = call.id,
                timestampMillis = call.completedAtMillis.takeIf { it > 0L }
                    ?: run.completedAtMillis.takeIf { it > 0L }
                    ?: System.currentTimeMillis()
            )
            events = agentRunEventStore.events(run.runId)
        }
    }
}

internal fun MainActivity.recordNativeToolLifecycleEvent(event: AgentNativeToolLifecycleEvent) {
    agentTaskPersistenceExecutor.execute {
        recordNativeToolLifecycleEventPersisted(event)
    }
}

internal fun MainActivity.recordModelToolLoopEvent(
    conversationId: String,
    turnId: String,
    event: AgentModelToolLoopEvent
) {
    agentTaskPersistenceExecutor.execute {
        recordModelToolLoopEventPersisted(conversationId, turnId, event)
    }
}

private fun MainActivity.recordModelToolLoopEventPersisted(
    conversationId: String,
    turnId: String,
    event: AgentModelToolLoopEvent
) {
    if (conversationId.isBlank() || turnId.isBlank()) return
    val projection = AgentModelToolLoopTimelinePolicy.project(event)
    val runId = agentRunIdsByTurn[turnId].orEmpty().ifBlank {
        runCatching { EncryptedAgentWorkspaceStore(this).find(turnId)?.parentRunId.orEmpty() }
            .getOrDefault("")
    }
    agentRunRecorder.run(runId)?.let { run ->
        appendRunControlEvent(
            run = run,
            messageId = turnId,
            taskId = turnId,
            agentId = "galaxyssi-mobile-model",
            type = projection.controlEventType,
            payload = projection.payload,
            stepId = projection.stepId,
            toolCallId = projection.toolCallId,
            timestampMillis = event.occurredAtEpochMillis
        )
    }
    val textType = projection.text ?: return
    val toolTitle = modelToolTimelineTitle(projection.toolId)
    val text = when (textType) {
        AgentModelToolTimelineText.MODEL_REASONING -> getString(
            R.string.agent_model_loop_reasoning_format,
            event.round
        )
        AgentModelToolTimelineText.MODEL_SELECTED_TOOLS -> getString(
            R.string.agent_model_loop_selected_tools_format,
            projection.count
        )
        AgentModelToolTimelineText.MODEL_PREPARED_STEP ->
            getString(R.string.agent_model_loop_prepared_step)
        AgentModelToolTimelineText.TOOL_RUNNING -> getString(
            R.string.agent_loop_action_format,
            toolTitle
        )
        AgentModelToolTimelineText.TOOL_PROGRESS -> if (projection.detail.isBlank()) {
            getString(R.string.agent_loop_action_format, toolTitle)
        } else {
            getString(
                R.string.agent_model_loop_tool_progress_format,
                toolTitle,
                projection.detail
            )
        }
        AgentModelToolTimelineText.TOOL_SUCCEEDED -> getString(
            R.string.agent_loop_observation_format,
            toolTitle
        )
        AgentModelToolTimelineText.TOOL_FAILED -> getString(
            R.string.agent_loop_observation_failed_format,
            toolTitle,
            projection.detail.ifBlank { getString(R.string.agent_model_loop_unknown_failure) }
        )
        AgentModelToolTimelineText.TOOL_RETRYING -> getString(
            R.string.agent_model_loop_retrying_format,
            toolTitle
        )
        AgentModelToolTimelineText.TOOL_WAITING -> getString(
            R.string.agent_model_loop_waiting_format,
            toolTitle
        )
        AgentModelToolTimelineText.MODEL_LOOP_STOPPED -> getString(
            R.string.agent_model_loop_stopped_format,
            projection.detail.ifBlank { getString(R.string.agent_model_loop_unknown_failure) }
        )
    }
    val changed = agentTranscriptStore.upsert(
        role = AgentTranscriptRole.PROCESS,
        text = text,
        dedupeKey = "agent-model-loop:$turnId:${projection.dedupeSuffix}",
        timestampMillis = event.occurredAtEpochMillis,
        conversationId = conversationId,
        turnId = turnId,
        taskId = turnId
    )
    if (changed) {
        runOnUiThread {
            if (conversationId == agentTranscriptStore.activeConversation().id) {
                refreshAgentTranscriptWindow(conversationId)
            }
        }
    }
}

private fun MainActivity.modelToolTimelineTitle(toolId: String): String {
    if (toolId.isBlank()) return getString(R.string.agent_model_loop_tool_fallback)
    return mobileNativeAgent.nativeToolCatalog()
        .firstOrNull { descriptor -> descriptor.id == toolId }
        ?.title
        .orEmpty()
        .ifBlank {
            toolId.substringAfterLast('.')
                .replace('_', ' ')
                .replaceFirstChar { character ->
                    if (character.isLowerCase()) {
                        character.titlecase(Locale.getDefault())
                    } else {
                        character.toString()
                    }
                }
        }
}

private fun MainActivity.recordNativeToolLifecycleEventPersisted(event: AgentNativeToolLifecycleEvent) {
    recordNativeToolTranscript(event)
    if (event.stage == AgentNativeToolLifecycleStage.STARTED &&
        event.conversationId.isNotBlank() &&
        event.turnId.isNotBlank()
    ) {
        runOnUiThread {
            val removedWatchdog = deleteAgentTranscriptByDedupeKey(
                event.conversationId,
                "task-watchdog:${event.turnId}"
            ) or deleteAgentTranscriptByDedupeKey(
                event.conversationId,
                "task-watchdog-timeout:${event.turnId}"
            )
            val removedRecovery = deleteAgentTranscriptByDedupeKey(
                event.conversationId,
                agentFailureRecoveryDedupeKey(event.turnId)
            )
            if ((removedWatchdog || removedRecovery) &&
                event.conversationId == agentTranscriptStore.activeConversation().id
            ) {
                refreshAgentTranscriptWindow(event.conversationId)
            }
        }
    }
    val runId = agentRunIdsByTurn[event.turnId] ?: return
    val run = agentRunRecorder.run(runId) ?: return
    if (event.turnId.isNotBlank()) {
        runCatching {
            AgentTaskRuntime.supervisor(this).progress(
                workspaceId = event.turnId,
                stage = "tool.${event.stage.name.lowercase(Locale.ROOT)}",
                message = event.message.ifBlank { event.toolId }
            )
        }
    }
    val type = when (event.stage) {
        AgentNativeToolLifecycleStage.STARTED -> AgentRunControlEventType.TOOL_STARTED
        AgentNativeToolLifecycleStage.PROGRESS -> AgentRunControlEventType.TOOL_PROGRESS
        AgentNativeToolLifecycleStage.FINISHED -> AgentRunControlEventType.TOOL_COMPLETED
    }
    appendRunControlEvent(
        run = run,
        messageId = event.turnId.ifBlank { event.invocationId },
        taskId = event.turnId.ifBlank { run.taskThreadId },
        agentId = "galaxyssi-mobile",
        type = type,
        payload = buildMap {
            put("timeline_contract", AgentRunTimelineContract.VERSION)
            put("timeline_kind", "tool")
            put("tool_id", event.toolId)
            event.status?.let { put("status", it.wireValue) }
            if (event.progressStage.isNotBlank()) put("progress_stage", event.progressStage)
            if (event.message.isNotBlank()) put("message", event.message)
            event.percent?.let { put("percent", it) }
            if (event.sequence > 0L) put("progress_sequence", event.sequence)
            put("timestamp_millis", event.timestampMillis)
        },
        stepId = event.stepId,
        toolCallId = event.invocationId
    )
}

internal fun MainActivity.recordNativeToolTranscript(event: AgentNativeToolLifecycleEvent) {
    if (event.conversationId.isBlank() || event.turnId.isBlank()) return
    val descriptorTitle = mobileNativeAgent.nativeToolCatalog()
        .firstOrNull { descriptor -> descriptor.id == event.toolId }
        ?.title
        .orEmpty()
    val toolTitle = descriptorTitle.ifBlank {
        event.toolId.substringAfterLast('.')
            .replace('_', ' ')
            .replaceFirstChar { character ->
                if (character.isLowerCase()) character.titlecase(Locale.getDefault()) else character.toString()
            }
    }
    val progress = event.percent?.coerceIn(0, 100)
    val detail = event.progressStage.trim()
        .takeIf(String::isNotBlank)
        ?: event.message.trim().takeIf(String::isNotBlank)
    val text = when (event.stage) {
        AgentNativeToolLifecycleStage.STARTED -> getString(
            R.string.agent_loop_action_format,
            toolTitle
        )
        AgentNativeToolLifecycleStage.PROGRESS -> when {
            progress != null -> getString(
                R.string.agent_loop_action_progress_format,
                toolTitle,
                progress
            )
            detail != null -> getString(
                R.string.agent_loop_action_detail_format,
                toolTitle,
                detail.take(160)
            )
            else -> getString(R.string.agent_loop_action_format, toolTitle)
        }
        AgentNativeToolLifecycleStage.FINISHED -> {
            val failed = event.status != null && event.status != AgentNativeToolResultStatus.SUCCEEDED
            if (failed) {
                getString(
                    R.string.agent_loop_observation_failed_format,
                    toolTitle,
                    event.message.trim().ifBlank { event.status?.wireValue.orEmpty() }.take(240)
                )
            } else {
                getString(
                    R.string.agent_loop_observation_format,
                    toolTitle
                )
            }
        }
    }
    val changed = agentTranscriptStore.upsert(
        role = AgentTranscriptRole.PROCESS,
        text = text,
        dedupeKey = "agent-loop-tool:${event.turnId}:${event.invocationId}",
        timestampMillis = event.timestampMillis,
        conversationId = event.conversationId,
        turnId = event.turnId,
        taskId = event.turnId
    )
    if (changed) {
        runOnUiThread {
            if (event.conversationId == agentTranscriptStore.activeConversation().id) {
                refreshAgentTranscriptWindow(event.conversationId)
            }
        }
    }
}

internal fun MainActivity.runtimeArtifactsFromResult(resultJson: String): List<AgentArtifactReference> = runCatching {
    if (resultJson.isBlank()) return@runCatching emptyList()
    val root = JSONObject(resultJson)
    val receiptId = root.optJSONObject("execution_receipt")?.optString("request_id").orEmpty()
    val artifacts = root.optJSONArray("artifacts") ?: return@runCatching emptyList()
    buildList {
        for (index in 0 until artifacts.length()) {
            val item = artifacts.optJSONObject(index) ?: continue
            val hostPath = item.optString("host_path")
            val relativePath = item.optString("relative_path")
            val sha256 = item.optString("sha256")
            if (hostPath.isBlank() || relativePath.isBlank() || sha256.isBlank()) continue
            val metadata = JSONObject()
                .put("runtime_request_id", receiptId)
                .put("relative_path", relativePath)
                .put("size_bytes", item.optLong("size_bytes"))
                .put("sha256", sha256)
                .toString()
            add(
                AgentArtifactReference(
                    id = "runtime-${receiptId.ifBlank { sha256 }.take(48)}-$index",
                    uri = File(hostPath).toURI().toString(),
                    name = File(relativePath).name,
                    metadataJson = metadata,
                    createdAtMillis = System.currentTimeMillis()
                )
            )
        }
    }
}.getOrDefault(emptyList())

internal fun MainActivity.appendRunControlEvent(
    run: AgentRecordedRun,
    messageId: String,
    taskId: String,
    agentId: String,
    type: AgentRunControlEventType,
    payload: AgentNativeJsonObject = emptyMap(),
    stepId: String = "",
    toolCallId: String = "",
    timestampMillis: Long = System.currentTimeMillis()
) {
    val profile = AppStore.profile(this)
    agentRunEventStore.appendNext(
        AgentRunControlEvent(
            conversationId = run.conversationId,
            messageId = messageId,
            taskId = taskId,
            runId = run.runId,
            stepId = stepId,
            toolCallId = toolCallId,
            agentId = agentId,
            deviceId = profile.optString("device_id").ifBlank { profile.optString("galaxyssi_id") },
            type = type,
            sequence = 0L,
            timestampMillis = timestampMillis,
            payload = payload
        )
    )
}

internal fun MainActivity.appendRunControlEvents(
    run: AgentRecordedRun,
    messageId: String,
    taskId: String,
    agentId: String,
    events: List<Pair<AgentRunControlEventType, AgentNativeJsonObject>>,
    timestampMillis: Long = System.currentTimeMillis()
) {
    if (events.isEmpty()) return
    val profile = AppStore.profile(this)
    val deviceId = profile.optString("device_id").ifBlank { profile.optString("galaxyssi_id") }
    agentRunEventStore.appendNextAll(
        events.mapIndexed { index, (type, payload) ->
            AgentRunControlEvent(
                conversationId = run.conversationId,
                messageId = messageId,
                taskId = taskId,
                runId = run.runId,
                agentId = agentId,
                deviceId = deviceId,
                type = type,
                sequence = 0L,
                timestampMillis = timestampMillis + index,
                payload = payload
            )
        }
    )
}

internal fun MainActivity.appendDirectSystemResult(
    action: AgentAction,
    conversationId: String,
    turnId: String,
    result: AgentActionResult
) {
    if (action.kind == AgentActionKind.CALL_CONNECTOR) {
        updateAgentExecutionTarget(
            conversationId = conversationId,
            connectorId = action.parameters["connector_id"].orEmpty(),
            contactId = result.metadata["contact_id"].orEmpty(),
            runtimeTarget = result.metadata["target"].orEmpty(),
            fallbackTarget = action.target
        )
    }
    if (result.metadata["awaiting_response"] == "true") {
        pendingDirectConnectorActions[turnId] = action
        result.metadata["source_message_id"]
            ?.toLongOrNull()
            ?.takeIf { it > 0L }
            ?.let { sourceMessageId ->
                val binding = PendingDirectConnectorRun(
                    action = action,
                    conversationId = conversationId,
                    turnId = turnId,
                    taskId = result.metadata["remote_task_id"].orEmpty()
                        .ifBlank { result.metadata["task_id"].orEmpty() }
                        .ifBlank { turnId },
                    contactId = result.metadata["contact_id"].orEmpty()
                )
                pendingDirectConnectorRuns[sourceMessageId] = binding
                AgentPendingDeliveryStore.put(
                    this,
                    AgentPendingDelivery(
                        sourceMessageId = sourceMessageId,
                        conversationId = binding.conversationId,
                        turnId = binding.turnId,
                        taskId = binding.taskId,
                        contactId = binding.contactId
                    )
                )
            }
        Log.i(
            "GalaxySSIAgent",
            "Direct connector awaiting response source=${result.metadata["source_message_id"].orEmpty()} " +
                "conversation=${conversationId.take(8)} turn=${turnId.take(8)}"
        )
        consumePendingAgentConnectorResponsesAsync()
        return
    }
    agentTranscriptStore.append(
        AgentTranscriptRole.ASSISTANT,
        result.message,
        dedupeKey = "direct-system:$turnId:${action.id}",
        conversationId = conversationId,
        turnId = turnId,
        taskId = turnId
    )
    refreshAgentTranscriptWindow(conversationId)
}

internal fun MainActivity.requestMissingAgentNativePermissions(state: AgentUiState): Boolean {
    val runtimePermissions = setOf(
        android.Manifest.permission.READ_PHONE_STATE,
        android.Manifest.permission.READ_SMS,
        android.Manifest.permission.SEND_SMS,
        android.Manifest.permission.READ_CONTACTS,
        android.Manifest.permission.WRITE_CONTACTS,
        android.Manifest.permission.READ_CALENDAR,
        android.Manifest.permission.WRITE_CALENDAR,
        android.Manifest.permission.ACCESS_FINE_LOCATION,
        android.Manifest.permission.ACCESS_COARSE_LOCATION,
        android.Manifest.permission.BLUETOOTH_CONNECT,
        android.Manifest.permission.BLUETOOTH_SCAN,
        android.Manifest.permission.CAMERA,
        android.Manifest.permission.RECORD_AUDIO
    )
    val missing = state.plan?.requiredPermissions.orEmpty()
        .asSequence()
        .filterNot { it.granted }
        .map { it.id }
        .filter { it in runtimePermissions }
        .filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        .distinct()
        .toList()
    if (missing.isNotEmpty()) {
        requestPermissions(missing.toTypedArray(), REQUEST_AGENT_NATIVE_PERMISSIONS)
        return true
    }
    return false
}

internal fun MainActivity.refreshAgentConversationHeader(
    conversation: AgentConversation = agentTranscriptStore.activeConversation()
) {
    agentSessionTitle.text = getString(
        R.string.agent_header_session_title,
        agentConversationDisplayTitle(conversation)
    )
    val conversationId = conversation.id
    runCatching {
        navigationContentExecutor.execute {
            val subtitle = resolveAgentConversationModelSubtitle(conversation)
            handler.post {
                if (!isFinishing && !isDestroyed && agentRenderedConversationId == conversationId) {
                    agentSubtitleText.text = subtitle
                }
            }
        }
    }
}

private fun MainActivity.resolveAgentConversationModelSubtitle(
    conversation: AgentConversation
): String {
    val targets = AppStoreAgentConnectorRegistry(this).availableTargets()
    val selection = AgentModelSelectionSettings.selection(this, conversation.id)
    val preferredTarget = AgentModelSelectionPolicy.selectedTarget(selection, targets)
    val manualSelectionAvailable = selection.mode == AgentModelSelectionMode.MANUAL &&
        selection.targetId.isNotBlank()
    val modelName = if (manualSelectionAvailable) {
        when (preferredTarget?.id) {
            "local-llm" -> selection.modelId
                .takeIf(String::isNotBlank)
                ?.let { LocalModelManager.profile(this, it).displayName }
                ?: LocalModelRuntimeSettings.displayProfile(this).displayName
            else -> if (preferredTarget?.kind == AgentConnectorKind.MODEL) {
                modelDisplayLabel(selection.modelId).ifBlank { selection.displayName }
            } else buildList {
                add(selection.displayName.ifBlank { preferredTarget?.let(::agentModelTargetDisplayName).orEmpty() })
                if (preferredTarget?.kind == AgentConnectorKind.AGENT && selection.modelId.isNotBlank()) {
                    add(selection.modelId)
                }
                if (preferredTarget?.kind == AgentConnectorKind.AGENT &&
                    selection.reasoningEffort != AgentModelReasoningEffort.AUTO
                ) {
                    add(getString(selection.reasoningEffort.labelResource()))
                }
            }.filter(String::isNotBlank).joinToString(" · ")
        }
    } else {
        agentConversationSourceLabel(conversation)
            .takeUnless { source ->
                source.isBlank() ||
                    source.equals("Automatic", ignoreCase = true) ||
                    source.equals("GalaxySSI", ignoreCase = true) ||
                    source.equals("Agent Knowledge", ignoreCase = true)
            }
            ?: AgentConnectorRouteSelector.select(targets, decision = null)
                ?.target
                ?.let(::agentModelTargetDisplayName)
                .orEmpty()
    }.ifBlank { getString(R.string.agent_model_selection_automatic) }
    return if (manualSelectionAvailable) {
        getString(R.string.agent_header_model_manual, modelName)
    } else {
        getString(R.string.agent_header_model_auto_with_name, modelName)
    }
}

internal fun MainActivity.agentConversationDisplayTitle(conversation: AgentConversation): String =
    (if (conversation.title == "New session") getString(R.string.agent_session_new) else conversation.title).let { title ->
        val sourceTitle = if (conversation.createdByAgent) {
            getString(R.string.agent_session_created_by_agent, title)
        } else title
        if (conversation.mergedIntoConversationId.isNotBlank()) {
            "$sourceTitle \u00b7 ${getString(R.string.agent_session_merged)}"
        } else if (conversation.trackingPaused) {
            "$sourceTitle \u00b7 ${getString(R.string.agent_session_tracking_paused)}"
        } else sourceTitle
    }

internal fun MainActivity.refreshGlobalAgentCognition() {
    if (!isGlobalSuperAgentRuntimeInitialized() || !isAgentTranscriptStoreInitialized()) return
    if (foregroundAgentTurnInProgress()) {
        if (globalAgentRefreshRequested.compareAndSet(false, true)) {
            handler.postDelayed({
                globalAgentRefreshRequested.set(false)
                if (!isFinishing && !isDestroyed) refreshGlobalAgentCognition()
            }, GLOBAL_AGENT_FOREGROUND_RETRY_MILLIS)
        }
        return
    }
    if (!globalAgentRefreshInProgress.compareAndSet(false, true)) {
        globalAgentRefreshRequested.set(true)
        return
    }
    thread(name = "galaxyssi-global-agent-cognition") {
        runCatching { globalSuperAgentRuntime.processPending() }
        runCatching { globalSuperAgentRuntime.processLongHorizonCycle() }
        runCatching { globalSuperAgentRuntime.processProactiveDiscoveryCycle() }
        runCatching { globalSuperAgentRuntime.executeCognitionCycle() }
        runCatching { globalSuperAgentRuntime.executeAutonomousCycle() }
        runCatching { globalSuperAgentRuntime.executeResearchCycle() }
        runCatching { globalSuperAgentRuntime.processPending() }
        runCatching { globalSuperAgentRuntime.processLongHorizonCycle() }
        runCatching { globalSuperAgentRuntime.processProactiveDiscoveryCycle() }
        runCatching { globalSuperAgentRuntime.scheduleNextWake() }
        val delivered = runCatching {
            globalSuperAgentRuntime.deliverPending(agentTranscriptStore)
        }.getOrDefault(emptyList())
        if (delivered.isNotEmpty()) {
            runCatching {
                globalSuperAgentRuntime.markNotified(delivered.map(GlobalProactiveMessage::id).toSet())
            }
        }
        runOnUiThread {
            try {
                if (!isFinishing && !isDestroyed) {
                    if (openLatestGlobalInsightWhenDelivered) {
                        (requestedGlobalInsightConversationId.takeIf(String::isNotBlank)
                            ?: delivered.lastOrNull { it.deliveredConversationId.isNotBlank() }
                                ?.deliveredConversationId)
                            ?.let { targetId ->
                                if (agentTranscriptStore.switchConversation(targetId)) {
                                    resetAgentTranscriptRendering(targetId)
                                }
                            }
                        openLatestGlobalInsightWhenDelivered = false
                        requestedGlobalInsightConversationId = ""
                    }
                    if (activeMainTab == PAGE_AGENT) {
                        refreshAgentConversationHeader()
                        refreshAgentTranscriptWindow()
                        refreshGlobalInsightIndicator()
                    }
                }
            } finally {
                globalAgentRefreshInProgress.set(false)
                if (globalAgentRefreshRequested.getAndSet(false) && !isFinishing && !isDestroyed) {
                    handler.post(::refreshGlobalAgentCognition)
                }
            }
        }
    }
}

internal fun MainActivity.updateAgentExecutionTarget(
    conversationId: String,
    connectorId: String = "",
    contactId: String = "",
    runtimeTarget: String = "",
    fallbackTarget: String = ""
) {
    if (Looper.myLooper() == Looper.getMainLooper()) {
        runCatching {
            navigationContentExecutor.execute {
                updateAgentExecutionTarget(
                    conversationId = conversationId,
                    connectorId = connectorId,
                    contactId = contactId,
                    runtimeTarget = runtimeTarget,
                    fallbackTarget = fallbackTarget
                )
            }
        }.onFailure { error ->
            if (!isFinishing && !isDestroyed) {
                Log.w("GalaxySSIAgent", "Agent execution target update was not scheduled", error)
            }
        }
        return
    }
    val targets = AppStoreAgentConnectorRegistry(this).availableTargets()
    val target = AgentExecutionTargetStatusPolicy.resolveTarget(
        connectorId = connectorId,
        contactId = contactId,
        targets = targets
    )
    val genericLabels = setOf(
        "agent or model",
        "cloud models",
        "local llm",
        "local model",
        "galaxyssi",
        "mobile executor"
    )
    val label = target?.let(::agentModelTargetDisplayName)
        .orEmpty()
        .ifBlank {
            runtimeTarget.trim().takeUnless { it.lowercase(Locale.US) in genericLabels }.orEmpty()
        }
        .ifBlank {
            fallbackTarget.trim().takeUnless { it.lowercase(Locale.US) in genericLabels }.orEmpty()
        }
    if (label.isBlank()) return
    agentTranscriptStore.setSelectedModelOrAgent(conversationId, label)
    if (conversationId == agentTranscriptStore.activeConversation().id) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            refreshAgentConversationHeader()
        } else {
            runOnUiThread {
                if (!isFinishing && !isDestroyed &&
                    conversationId == agentTranscriptStore.activeConversation().id
                ) {
                    refreshAgentConversationHeader()
                }
            }
        }
    }
}

internal fun MainActivity.foregroundAgentTurnInProgress(): Boolean {
    if (AgentForegroundWorkCoordinator.hasForegroundWork ||
        pendingAgentReplyIndicators.isNotEmpty() ||
        provisionalAgentTasks.isNotEmpty() ||
        activeAgentTasks.isNotEmpty()
    ) {
        return true
    }
    return AgentTaskRuntime.supervisor(this).activeWorkspaces().any { workspace ->
        workspace.status in setOf(
            AgentWorkspaceStatus.CREATED,
            AgentWorkspaceStatus.QUEUED,
            AgentWorkspaceStatus.RUNNING,
            AgentWorkspaceStatus.WAITING_RESPONSE
        )
    }
}

internal fun MainActivity.agentConversationSourceLabel(conversation: AgentConversation): String {
    val selected = conversation.selectedModelOrAgent
    if (!selected.equals("Multiple Executors", ignoreCase = true)) {
        return agentTraceTargetLabel(selected)
    }
    val entries = agentTranscriptWindow.entries
        .takeIf { agentTranscriptWindow.conversationId == conversation.id }
        .orEmpty()
    val latestTurnId = entries.lastOrNull { it.role == AgentTranscriptRole.USER }?.turnId.orEmpty()
    val latestProcess = entries.asSequence()
        .filter { entry ->
            entry.role == AgentTranscriptRole.PROCESS &&
                (latestTurnId.isBlank() || entry.turnId == latestTurnId)
        }
        .joinToString("\n", transform = AgentTranscriptEntry::text)
        .lowercase(Locale.US)
    return if ("codex" in latestProcess) {
        connectorAgentDisplayName("codex", "Codex")
    } else {
        selected
    }
}
