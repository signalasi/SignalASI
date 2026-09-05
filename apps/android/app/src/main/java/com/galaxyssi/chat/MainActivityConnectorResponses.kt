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

internal object AgentSupervisedControlResponseRetryPolicy {
    fun delayMillis(attempt: Int): Long {
        val exponent = attempt.coerceIn(0, MAX_EXPONENT)
        return (INITIAL_DELAY_MILLIS shl exponent).coerceAtMost(MAX_DELAY_MILLIS)
    }

    fun nextAttempt(attempt: Int): Int = when {
        attempt < 0 -> 1
        attempt >= MAX_TRACKED_ATTEMPT -> MAX_TRACKED_ATTEMPT
        else -> attempt + 1
    }

    private const val INITIAL_DELAY_MILLIS = 500L
    private const val MAX_DELAY_MILLIS = 30_000L
    private const val MAX_EXPONENT = 6
    private const val MAX_TRACKED_ATTEMPT = 30
}

internal fun MainActivity.publishAgentConnectorResponse(envelope: JSONObject?, message: ChatMessage): Boolean {
    val payload = envelope ?: return false
    if (payload.optString("type").ifBlank { "text" } != "text") return false
    val sourceMessageId = payload.optString("source_message_id").toLongOrNull()
        ?: payload.optLong("source_message_id", 0L).takeIf { it > 0L }
        ?: return false
    val response = AgentRemoteOutcomeCodec.decode(payload,
        AgentRemoteOutcomeCodec.content(this, payload, message.content),
        CodexStyleResponsePolicy.filterAssistantRichOutput(AgentRichContentCodec.fromEnvelope(payload))) ?: return false
    if (AgentTerminalDeliveryStore.isTerminal(this, sourceMessageId)) {
        Log.i("GalaxySSIAgent", "Discarded late response for terminal source=$sourceMessageId")
        return true
    }
    val updateVoiceRun = VoiceFeatureFlags.isAgentVoiceRunBridgeEnabled(this) &&
        isVoiceAgentRunBridgeInitialized()
    val voiceTraceId = payload.optString("trace_id")
    val coordinatorSessionId = voiceCoordinatorSession(voiceTraceId).ifBlank {
        voiceCoordinatorIdsBySourceMessage[sourceMessageId].orEmpty()
    }
    if (voiceTraceId.isNotBlank() && response.success) {
        activeVoiceTraceId = voiceTraceId
        VoiceLatencyTelemetry.record(
            this,
            voiceTraceId,
            VoiceTraceEvents.AGENT_FIRST_PARTIAL_RESULT,
            mapOf("agent_provider" to payload.optString("agent_id", "remote_agent")),
            once = true
        )
    }
    if (coordinatorSessionId.isNotBlank()) {
        val status = response.taskStatus
        dispatchVoiceCoordinator(when (status) {
            "cancelled" -> VoiceInteractionEvent.Cancelled(coordinatorSessionId, "remote_agent_cancelled")
            "failed", "timed_out" -> VoiceInteractionEvent.Failed(coordinatorSessionId,
                VoiceFailure(code = "agent_$status", recoverable = true, stage = voiceInteractionCoordinator.snapshot().phase))
            else -> VoiceInteractionEvent.Completed(coordinatorSessionId)
        })
        voiceCoordinatorIdsBySourceMessage.remove(sourceMessageId)
    }
    com.galaxyssi.chat.metrics.AgentLatencyTelemetry.record(this, response.taskId, "phone_response_received")
    com.galaxyssi.chat.metrics.AgentLatencyTelemetry.record(
        this, response.taskId, "phone_final_received", outcome = payload.optString("task_status")
    )
    // A verified final response owns this remote task's terminal outcome. Ignore
    // status envelopes that arrive later and would regress a continuing loop.
    response.taskId.takeIf(String::isNotBlank)?.let {
        completedConnectorTaskIds.add(AgentRemoteOutcomeCodec.taskKey(it, response.executionGeneration))
    }
    agentRuntimeRecoveryExecutor.execute {
        if (isGlobalSuperAgentRuntimeInitialized() &&
            globalSuperAgentRuntime.consumeResearchResponse(response)
        ) {
            runOnUiThread {
                if (!isFinishing && !isDestroyed) refreshGlobalAgentCognition()
            }
        } else {
            AgentConnectorResponseBus.publish(this, response)
        }
        AndroidAgentResultRecovery.acknowledge(this, payload, response)
        // The durable reply must not wait for the optional voice projection's ledger scan.
        if (updateVoiceRun && response.success) {
            runCatching {
                voiceAgentRunBridge.consumeLegacyFinal(
                    sourceMessageId = response.sourceMessageId,
                    taskId = response.taskId,
                    content = response.content
                )
            }.onFailure {
                Log.w("GalaxySSIAgent", "Voice run projection update failed; connector reply remains durable")
            }
        }
    }
    return true
}

internal fun MainActivity.consumeBoundDirectConnectorResponse(response: AgentConnectorResponse): Boolean {
    if (AgentTerminalDeliveryStore.isTerminal(this, response.sourceMessageId)) return true
    val binding = pendingDirectConnectorRuns[response.sourceMessageId] ?: return false
    if (binding.contactId.isNotBlank() && response.contactId.isNotBlank() &&
        binding.contactId != response.contactId
    ) return false
    if (response.conversationId.isNotBlank() &&
        agentTranscriptStore.resolveMergedConversationId(response.conversationId) != binding.conversationId
    ) return false
    if (response.turnId.isNotBlank() && response.turnId != binding.turnId) return false
    if (!pendingDirectConnectorRuns.remove(response.sourceMessageId, binding)) return false

    directControlPlaneExecutor.consumeConnectorResponse(response)
    AgentPendingDeliveryStore.remove(this, response.sourceMessageId)
    deleteAgentTranscriptByDedupeKey(
        binding.conversationId,
        AgentDeliveryFailureRecorder.dedupeKey(response.sourceMessageId)
    )
    val taskId = response.taskId.ifBlank { binding.taskId.ifBlank { binding.turnId } }
    var removedLiveStream = false
    val stored = AgentConnectorStreamHandoff.persistThenRetire(
        persistFinal = {
            agentTranscriptStore.upsert(
                role = AgentTranscriptRole.ASSISTANT,
                text = response.content,
                dedupeKey = AgentFinalResponseIdentity.dedupeKey(
                    turnId = binding.turnId,
                    sourceMessageId = response.sourceMessageId,
                    taskId = taskId
                ),
                conversationId = binding.conversationId,
                turnId = binding.turnId,
                taskId = taskId,
                richOutputJson = response.richOutputJson
            )
        },
        retireLiveStream = {
            removedLiveStream = deferAgentConnectorStreamRetirement(
                response.sourceMessageId,
                binding.conversationId
            )
        }
    )
    pendingDirectConnectorActions.remove(binding.turnId)?.let { action ->
        recordDirectAgentRun(
            turnId = binding.turnId,
            action = action,
            result = AgentActionResult(
                actionId = action.id,
                success = response.success,
                message = response.content,
                metadata = mapOf(
                    "source_message_id" to response.sourceMessageId.toString(),
                    "contact_id" to response.contactId,
                    "conversation_id" to binding.conversationId,
                    "turn_id" to binding.turnId,
                    "task_id" to taskId,
                    "remote_task_status" to response.taskStatus,
                    "remote_execution_generation" to response.executionGeneration.toString()
                )
            )
        )
    }
    deleteAgentTranscriptByDedupeKey(binding.conversationId, "connector-task:$taskId")
    completedConnectorTaskIds.add(AgentRemoteOutcomeCodec.taskKey(taskId, response.executionGeneration))
    agentTranscriptStore.recordUsage(
        binding.conversationId,
        response.inputTokens,
        response.outputTokens,
        response.costMicros
    )
    if ((stored || removedLiveStream) &&
        binding.conversationId == agentTranscriptStore.activeConversation().id
    ) {
        runOnUiThread {
            if (!isFinishing && !isDestroyed) {
                refreshAgentTranscriptWindow(binding.conversationId)
                refreshAgentConversationHeader()
            }
        }
    }
    Log.i(
        "GalaxySSIAgent",
        "Consumed bound direct connector response source=${response.sourceMessageId} " +
            "turn=${binding.turnId.take(8)}"
    )
    return true
}

internal fun MainActivity.consumeAgentConnectorResponse(response: AgentConnectorResponse) {
    if (!AgentConnectorResponseStore.isCurrentExecution(this, response)) {
        AgentConnectorResponseStore.remove(this, response)
        return
    }
    if (AgentTerminalDeliveryStore.isTerminal(this, response.sourceMessageId)) {
        AgentConnectorResponseStore.remove(this, response)
        liveAgentConnectorStreams.remove(response.sourceMessageId)
        Log.i(
            "GalaxySSIAgent",
            "Discarded queued response for terminal source=${response.sourceMessageId}"
        )
        return
    }
    if (response.sourceMessageId in supersededConnectorSourceIds) {
        AgentConnectorResponseStore.remove(this, response)
        liveAgentConnectorStreams.remove(response.sourceMessageId)
        Log.i(
            "GalaxySSIAgent",
            "Discarded response for superseded source=${response.sourceMessageId}"
        )
        return
    }
    if (isGlobalSuperAgentRuntimeInitialized() &&
        globalSuperAgentRuntime.consumeResearchResponse(response)
    ) {
        AgentConnectorResponseStore.remove(this, response)
        liveAgentConnectorStreams.remove(response.sourceMessageId)
        runOnUiThread {
            if (!isFinishing && !isDestroyed) refreshGlobalAgentCognition()
        }
        return
    }
    val runtime = runtimeForConnectorResponse(
        response.sourceMessageId,
        response.contactId,
        response.conversationId,
        response.turnId,
        response.taskId
    )
    if (runtime == null) {
        if (AgentSupervisedProjectControlPayload.isControlPayload(response.content)) {
            deferSupervisedProjectControlResponse(response)
            return
        }
        val consumed = consumeOrphanedAgentConnectorResponse(response)
        if (!consumed && shouldDiscardUnroutableConnectorResponse(response)) {
            AgentConnectorResponseStore.remove(this, response)
            liveAgentConnectorStreams.remove(response.sourceMessageId)
            Log.i(
                "GalaxySSIAgent",
                "Discarded unroutable connector response source=${response.sourceMessageId}"
            )
        }
        return
    }
    agentConnectorResponsesInFlight.remove(
        "supervised-control:${AgentConnectorResponseCodec.identity(response)}"
    )
    val responseKey = AgentConnectorResponseCodec.identity(response)
    if (!agentConnectorResponsesInFlight.add(responseKey)) return
    resumeAgentConnectorResponse(response, runtime, responseKey)
}

internal fun MainActivity.deferSupervisedProjectControlResponse(
    response: AgentConnectorResponse,
    attempt: Int = 0
) {
    val responseKey = "supervised-control:${AgentConnectorResponseCodec.identity(response)}"
    if (!agentConnectorResponsesInFlight.add(responseKey)) return
    handler.postDelayed(
        {
            agentRuntimeRecoveryExecutor.execute {
                if (!AgentConnectorResponseStore.contains(applicationContext, response)) {
                    handler.post { agentConnectorResponsesInFlight.remove(responseKey) }
                    return@execute
                }
                val runtime = runtimeForConnectorResponse(
                    sourceMessageId = response.sourceMessageId,
                    contactId = response.contactId,
                    conversationId = response.conversationId,
                    turnId = response.turnId,
                    taskId = response.taskId,
                    restorePersisted = true
                )
                handler.post {
                    agentConnectorResponsesInFlight.remove(responseKey)
                    if (isFinishing || isDestroyed) return@post
                    if (!AgentConnectorResponseStore.contains(
                            this@deferSupervisedProjectControlResponse,
                            response
                        )
                    ) return@post
                    when {
                        runtime != null -> consumeAgentConnectorResponse(response)
                        else -> {
                            if (attempt == 0 || attempt % 10 == 0) {
                                Log.i(
                                    "GalaxySSIAgent",
                                    "Keeping supervised control response while its originating run is restored " +
                                        "source=${response.sourceMessageId} turn=${response.turnId.take(8)} attempt=$attempt"
                                )
                            }
                            deferSupervisedProjectControlResponse(
                                response,
                                AgentSupervisedControlResponseRetryPolicy.nextAttempt(attempt)
                            )
                        }
                    }
                }
            }
        },
        AgentSupervisedControlResponseRetryPolicy.delayMillis(attempt)
    )
}

internal fun MainActivity.rebindAgentConnectorContinuation(
    response: AgentConnectorResponse,
    runtime: MobileNativeAgent,
    state: AgentUiState,
    conversationId: String,
    turnId: String
) = rebindAgentConnectorContinuation(
    previousSourceMessageId = response.sourceMessageId,
    runtime = runtime,
    state = state,
    conversationId = conversationId,
    turnId = turnId
)

internal fun MainActivity.rebindAgentConnectorContinuation(
    previousSourceMessageId: Long,
    runtime: MobileNativeAgent,
    state: AgentUiState,
    conversationId: String,
    turnId: String
) {
    val nextResult = state.lastActionResult
    val nextSourceMessageId = nextResult?.metadata
        ?.get("source_message_id")
        ?.toLongOrNull()
        ?.takeIf { sourceId ->
            sourceId > 0L &&
                nextResult.metadata["awaiting_response"] == "true" &&
                state.phase == AgentPhase.WAITING_RESPONSE
        }
    if (nextSourceMessageId != previousSourceMessageId) {
        activeAgentTasks.remove(previousSourceMessageId, runtime)
    }
    if (nextSourceMessageId == null) return
    activeAgentTasks[nextSourceMessageId] = runtime
    provisionalAgentTasks.remove(runtime)
    AgentPendingDeliveryStore.put(
        this,
        AgentPendingDelivery(
            sourceMessageId = nextSourceMessageId,
            conversationId = conversationId,
            turnId = turnId,
            taskId = nextResult.metadata["remote_task_id"].orEmpty().ifBlank { turnId },
            contactId = nextResult.metadata["contact_id"].orEmpty()
        )
    )
    scheduleConnectorTimeouts(runtime, nextSourceMessageId, conversationId, turnId)
}

internal fun MainActivity.scheduleAgentConnectorStreamRefresh() {
    if (pendingAgentConnectorStreamUpdates.isEmpty()) return
    if (agentConnectorStreamRefreshScheduled.compareAndSet(false, true)) {
        handler.postDelayed(
            agentConnectorStreamRefreshRunnable,
            AGENT_CONNECTOR_STREAM_UI_INTERVAL_MS
        )
    }
}

internal fun MainActivity.deferAgentConnectorStreamRetirement(
    sourceMessageId: Long,
    conversationId: String
): Boolean {
    val live = liveAgentConnectorStreams[sourceMessageId] ?: return false
    if (live.conversationId != conversationId || conversationId != agentRenderedConversationId) {
        pendingAgentConnectorStreamRetirements.remove(sourceMessageId)
        return liveAgentConnectorStreams.remove(sourceMessageId, live)
    }
    pendingAgentConnectorStreamRetirements.add(sourceMessageId)
    return true
}

internal fun MainActivity.retirePersistedAgentConnectorStreams(
    conversationId: String,
    persistedEntries: Collection<AgentTranscriptEntry>
) {
    val represented = AgentConnectorStreamPresentationPolicy.representedSourceIds(
        pendingSourceIds = pendingAgentConnectorStreamRetirements,
        liveBySourceId = liveAgentConnectorStreams,
        persisted = persistedEntries,
        conversationId = conversationId
    )
    represented.forEach { sourceMessageId ->
        liveAgentConnectorStreams.remove(sourceMessageId)
        pendingAgentConnectorStreamRetirements.remove(sourceMessageId)
    }
}

internal fun MainActivity.applyAgentConnectorStreamUpdate(update: AgentConnectorStreamUpdate): Boolean {
    if (update.sourceMessageId in supersededConnectorSourceIds) return false
    if (!agentConnectorStreamAttempts.isCurrent(update)) return false
    if (AgentSupervisedProjectControlPayload.isControlPayloadFragment(update.content)) {
        supervisedProjectConnectorSourceIds.add(update.sourceMessageId)
        liveAgentConnectorStreams.remove(update.sourceMessageId)
        return false
    }
    val runtime = runtimeForConnectorResponse(
        update.sourceMessageId,
        update.contactId,
        update.conversationId,
        update.turnId,
        update.taskId,
        allowTransportOnly = true
    )
    val state = runtime?.snapshot()
    val pendingResult = state?.lastActionResult
    val pendingAction = state?.plan?.actions?.firstOrNull { action ->
        action.id == pendingResult?.actionId
    }
    if (!AgentSupervisedProjectPresentationPolicy.shouldExposeConnectorStream(
            phase = state?.phase ?: AgentPhase.OBSERVING,
            pendingAction = pendingAction,
            expectedSourceMessageId = pendingResult?.metadata
                ?.get("source_message_id")?.toLongOrNull() ?: 0L,
            incomingSourceMessageId = update.sourceMessageId,
            isSupervisedSource = update.sourceMessageId in supervisedProjectConnectorSourceIds
        )
    ) {
        liveAgentConnectorStreams.remove(update.sourceMessageId)
        return false
    }
    val turnId = update.turnId.ifBlank {
        runtime?.let(agentRuntimeTurnIds::get).orEmpty()
    }
    val conversationId = connectorConversationId(update.conversationId, runtime, turnId) ?: return false
    if (update.content.isBlank()) {
        liveAgentConnectorStreams.remove(update.sourceMessageId)
    } else {
        liveAgentConnectorStreams[update.sourceMessageId] = AgentTranscriptEntry(
            id = "agent-stream-${update.sourceMessageId}",
            role = AgentTranscriptRole.ASSISTANT,
            text = update.content,
            timestampMillis = update.receivedAtMillis,
            dedupeKey = AgentFinalResponseIdentity.dedupeKey(
                turnId = turnId,
                sourceMessageId = update.sourceMessageId,
                taskId = update.taskId
            ),
            conversationId = conversationId,
            turnId = turnId,
            taskId = update.taskId.ifBlank { turnId }
        )
    }
    return conversationId == agentTranscriptStore.activeConversation().id
}

internal fun MainActivity.resumeAgentConnectorResponse(
    response: AgentConnectorResponse,
    runtime: MobileNativeAgent,
    responseKey: String,
    attempt: Int = 0
) {
    if (AgentTerminalDeliveryStore.isTerminal(this, response.sourceMessageId)) {
        AgentConnectorResponseStore.remove(this, response)
        liveAgentConnectorStreams.remove(response.sourceMessageId)
        activeAgentTasks.remove(response.sourceMessageId)
        agentConnectorResponsesInFlight.remove(responseKey)
        return
    }
    val durableDelivery = AgentPendingDeliveryStore.find(
        this,
        response.sourceMessageId,
        response.contactId
    )
    val responseIdentity = AgentTaskIdentityPolicy.canonicalConnectorResponseIdentity(
        pendingDelivery = durableDelivery,
        conversationId = response.conversationId,
        taskId = response.taskId,
        turnId = response.turnId
    )
    val turnId = agentRuntimeTurnIds[runtime].orEmpty().ifBlank { responseIdentity.turnId }
    val conversationId = connectorConversationId(
        responseIdentity.conversationId,
        runtime,
        turnId
    )
    if (conversationId == null) {
        Log.w(
            "GalaxySSIAgent",
            "Discarding unroutable connector response source=${response.sourceMessageId} turn=${turnId.take(8)}"
        )
        AgentConnectorResponseStore.remove(this, response)
        liveAgentConnectorStreams.remove(response.sourceMessageId)
        activeAgentTasks.remove(response.sourceMessageId)
        agentConnectorResponsesInFlight.remove(responseKey)
        return
    }
    val supervisor = AgentTaskRuntime.supervisor(this)
    if (turnId.isBlank()) {
        consumeLegacyAgentConnectorResponse(response, runtime, responseKey, conversationId)
        return
    }
    val expectedSourceMessageId = AgentPendingDeliveryStore.recoverySuccessorForResponse(
        this,
        response.sourceMessageId,
        conversationId,
        turnId
    ) ?: response.sourceMessageId
    val reconciledWorkspace = supervisor.reconcileLateConnectorResponse(
        workspaceId = turnId,
        sourceMessageId = response.sourceMessageId,
        durableTurnId = durableDelivery
            ?.takeIf { delivery ->
                delivery.turnId == turnId &&
                    delivery.conversationId == conversationId
            }
            ?.turnId
            .orEmpty()
    )
    if (reconciledWorkspace?.status == AgentWorkspaceStatus.WAITING_RESPONSE) {
        Log.i(
            "GalaxySSIAgentLifecycle",
            "Reconciled authenticated connector response source=${response.sourceMessageId} " +
                "workspace=${turnId.take(8)}"
        )
    }
    if (turnId in supervisor.activeTaskIds()) {
        if (AgentSupervisedProjectControlPayload.isControlPayload(response.content)) {
            scheduleDurableSupervisedConnectorResponse(response, runtime, responseKey, attempt)
        } else if (attempt < MAX_LEGACY_ACTIVE_RUN_RESPONSE_RETRIES) {
            handler.postDelayed(
                { resumeAgentConnectorResponse(response, runtime, responseKey, attempt + 1) },
                LEGACY_ACTIVE_RUN_RESPONSE_RETRY_MILLIS
            )
        } else {
            agentConnectorResponsesInFlight.remove(responseKey)
        }
        return
    }
    val resumed = runCatching {
        supervisor.resume(
            workspaceId = turnId,
            lane = AgentTaskLane.READ_REASONING,
            priority = AgentTaskPriority.FOREGROUND,
            hook = AgentTaskResumeHook { context, _ ->
                com.galaxyssi.chat.metrics.AgentLatencyTelemetry.replyStage(
                    this@resumeAgentConnectorResponse, response.taskId, "phone_final_consume_started"
                )
                bindAgentExecutionLoop(runtime, turnId, context)
                context.progress("connector.response", "Connector response received")
                recordSupervisedModelOutput(
                    response = response,
                    runtime = runtime,
                    conversationId = conversationId,
                    turnId = turnId
                )
                var state = try {
                    runtime.acceptConnectorOutcome(
                        response = response,
                        conversationId = responseIdentity.conversationId,
                        turnId = responseIdentity.turnId,
                        taskId = responseIdentity.taskId,
                        expectedSourceMessageId = expectedSourceMessageId
                    ) ?: runtime.snapshot()
                } catch (failure: Throwable) {
                    agentConnectorResponsesInFlight.remove(responseKey)
                    throw failure
                }
                com.galaxyssi.chat.metrics.AgentLatencyTelemetry.bindReply(
                    conversationId, turnId, state.sessionId, response.taskId
                )
                com.galaxyssi.chat.metrics.AgentLatencyTelemetry.replyStage(
                    this@resumeAgentConnectorResponse, response.taskId, "phone_final_accepted"
                )
                state = finalizeAgentExecutionLoop(runtime, turnId, state)
                com.galaxyssi.chat.metrics.AgentLatencyTelemetry.replyStage(
                    this@resumeAgentConnectorResponse, response.taskId, "phone_finalized"
                )
                context.appendEvent(
                    kind = "agent.connector.response",
                    message = state.phase.name,
                    payloadJson = JSONObject()
                        .put("source_message_id", response.sourceMessageId)
                        .put("contact_id", response.contactId)
                        .put("success", response.success)
                        .toString()
                )
                persistAgentWorkspaceSnapshot(turnId, state, runtime)
                // A continuation response can arrive before this hook finishes. Keep later
                // responses for a live turn and clear the whole turn only after terminal state.
                AgentConnectorResponseStore.removeHandled(
                    this@resumeAgentConnectorResponse,
                    response,
                    terminal = state.phase.isTerminalAgentPhase()
                )
                AgentPendingDeliveryStore.completeResponse(
                    this@resumeAgentConnectorResponse,
                    durableDelivery
                )
                com.galaxyssi.chat.metrics.AgentLatencyTelemetry.replyStage(
                    this@resumeAgentConnectorResponse, response.taskId, "phone_final_checkpointed"
                )
                runOnUiThread {
                    com.galaxyssi.chat.metrics.AgentLatencyTelemetry.replyStage(
                        this@resumeAgentConnectorResponse, response.taskId, "phone_final_ui_started"
                    )
                    rebindAgentConnectorContinuation(
                        response,
                        runtime,
                        state,
                        conversationId,
                        turnId
                    )
                    finishAgentConnectorResponseUi(
                        response = response,
                        runtime = runtime,
                        state = state,
                        conversationId = conversationId,
                        turnId = turnId,
                        responseKey = responseKey
                    )
                }
                when (state.phase) {
                    AgentPhase.WAITING_CONFIRMATION -> context.waitForConfirmation(
                        state.pendingAction?.description.orEmpty()
                    )
                    AgentPhase.WAITING_RESPONSE -> context.waitForResponse(
                        state.lastActionResult?.message.orEmpty()
                    )
                    AgentPhase.PAUSED -> context.pause(state.lastActionResult?.message.orEmpty())
                    AgentPhase.BLOCKED -> context.blockTask(state.plan?.safetyReview?.reason.orEmpty())
                    AgentPhase.FAILED -> throw IllegalStateException(
                        state.lastActionResult?.message.orEmpty().ifBlank { "Agent task failed" }
                    )
                    AgentPhase.CANCELLED -> context.cancellationSource.cancel("Agent task cancelled")
                    else -> Unit
                }
            }
        )
    }
    if (resumed.isFailure) {
        if (AgentSupervisedProjectControlPayload.isControlPayload(response.content)) {
            if (attempt == 0 || attempt % 10 == 0) {
                Log.w(
                    "GalaxySSIAgentLifecycle",
                    "Keeping supervised response durable after resume failure " +
                        "source=${response.sourceMessageId} workspace=${turnId.take(8)} attempt=$attempt",
                    resumed.exceptionOrNull()
                )
            }
            scheduleDurableSupervisedConnectorResponse(response, runtime, responseKey, attempt)
        } else if (attempt < MAX_LEGACY_ACTIVE_RUN_RESPONSE_RETRIES) {
            handler.postDelayed(
                { resumeAgentConnectorResponse(response, runtime, responseKey, attempt + 1) },
                LEGACY_ACTIVE_RUN_RESPONSE_RETRY_MILLIS
            )
        } else {
            agentConnectorResponsesInFlight.remove(responseKey)
            consumeLegacyAgentConnectorResponse(response, runtime, responseKey, conversationId)
        }
    }
}

private fun MainActivity.scheduleDurableSupervisedConnectorResponse(
    response: AgentConnectorResponse,
    runtime: MobileNativeAgent,
    responseKey: String,
    attempt: Int
) {
    if (!AgentConnectorResponseStore.contains(this, response)) {
        agentConnectorResponsesInFlight.remove(responseKey)
        return
    }
    handler.postDelayed(
        {
            if (isFinishing || isDestroyed ||
                !AgentConnectorResponseStore.contains(this, response)
            ) {
                agentConnectorResponsesInFlight.remove(responseKey)
                return@postDelayed
            }
            resumeAgentConnectorResponse(
                response = response,
                runtime = runtime,
                responseKey = responseKey,
                attempt = AgentSupervisedControlResponseRetryPolicy.nextAttempt(attempt)
            )
        },
        AgentSupervisedControlResponseRetryPolicy.delayMillis(attempt)
    )
}

private const val MAX_LEGACY_ACTIVE_RUN_RESPONSE_RETRIES = 100
private const val LEGACY_ACTIVE_RUN_RESPONSE_RETRY_MILLIS = 100L

internal fun MainActivity.consumeLegacyAgentConnectorResponse(
    response: AgentConnectorResponse,
    runtime: MobileNativeAgent,
    responseKey: String,
    conversationId: String
) {
    thread(name = "galaxyssi-agent-response-${response.sourceMessageId}") {
        val turnId = agentRuntimeTurnIds[runtime].orEmpty().ifBlank { response.turnId }
        bindAgentExecutionLoop(runtime, turnId)
        recordSupervisedModelOutput(
            response = response,
            runtime = runtime,
            conversationId = conversationId,
            turnId = turnId
        )
        var state = runtime.acceptConnectorOutcome(
            response = response,
            conversationId = response.conversationId,
            turnId = response.turnId,
            taskId = response.taskId
        ) ?: runtime.snapshot()
        if (turnId.isNotBlank()) {
            state = finalizeAgentExecutionLoop(runtime, turnId, state)
        }
        if (turnId.isNotBlank()) persistAgentWorkspaceSnapshot(turnId, state, runtime)
        AgentConnectorResponseStore.remove(this, response)
        runOnUiThread {
            rebindAgentConnectorContinuation(
                response,
                runtime,
                state,
                conversationId,
                turnId
            )
            finishAgentConnectorResponseUi(
                response,
                runtime,
                state,
                conversationId,
                turnId,
                responseKey
            )
        }
    }
}

internal fun MainActivity.recordSupervisedModelOutput(
    response: AgentConnectorResponse,
    runtime: MobileNativeAgent,
    conversationId: String,
    turnId: String
) {
    clearSupersededAgentFailureEntries(conversationId)
    val snapshot = runtime.snapshot()
    val pendingActionId = snapshot.lastActionResult?.actionId.orEmpty()
    val pendingAction = snapshot.plan?.actions?.firstOrNull { action ->
        action.id == pendingActionId
    }
    val supervised = snapshot.plan?.isSupervisedProjectPlan() == true ||
        pendingAction?.isSupervisedProjectConnector() == true ||
        AgentSupervisedProjectControlPayload.isControlPayloadFragment(response.content)
    if (!supervised) return
    agentTranscriptStore.entriesForTurn(turnId)
        .asSequence()
        .filter { entry -> entry.dedupeKey.startsWith("agent-recovery:") }
        .forEach { entry ->
            deleteAgentTranscriptByDedupeKey(conversationId, entry.dedupeKey)
        }
    response.taskId.takeIf(String::isNotBlank)?.let { taskId ->
        deleteAgentTranscriptByDedupeKey(
            conversationId,
            agentFailureRecoveryDedupeKey(taskId)
        )
    }
    val visibleOutput = AgentSupervisedProjectControlPayload.visibleModelOutput(response.content)
    if (visibleOutput.isBlank()) return
    val taskId = response.taskId.ifBlank { snapshot.sessionId }.ifBlank { turnId }
    agentTranscriptStore.upsert(
        role = AgentTranscriptRole.PROCESS,
        text = getString(R.string.agent_loop_reason_format, visibleOutput),
        dedupeKey = "supervised-model-output:$taskId:REASONING_SUMMARY:${response.sourceMessageId}",
        timestampMillis = response.receivedAtMillis,
        conversationId = conversationId,
        turnId = turnId,
        taskId = taskId
    )
}

internal fun MainActivity.finishAgentConnectorResponseUi(
    response: AgentConnectorResponse,
    runtime: MobileNativeAgent,
    state: AgentUiState,
    conversationId: String,
    turnId: String,
    responseKey: String
) {
    AgentPendingDeliveryStore.remove(this, response.sourceMessageId)
    deleteAgentTranscriptByDedupeKey(
        conversationId,
        AgentDeliveryFailureRecorder.dedupeKey(response.sourceMessageId)
    )
    agentConnectorResponsesInFlight.remove(responseKey)
    cancelConnectorTimeouts(response.sourceMessageId)
    updateAgentExecutionTarget(
        conversationId = conversationId,
        contactId = response.executionContactId
    )
    agentTranscriptStore.recordUsage(
        conversationId, response.inputTokens, response.outputTokens, response.costMicros
    )
    if (turnId.isNotBlank()) finishStructuredAgentHandoff(turnId, response)
    if (turnId.isNotBlank() && state.phase.isTerminalAgentPhase()) {
        clearAgentTaskWatchdogTranscript(conversationId, turnId)
    }
    renderAgentState(
        state,
        conversationId,
        turnId,
        onTranscriptSynced = {
            deferAgentConnectorStreamRetirement(response.sourceMessageId, conversationId)
        }
    )
    if (state.phase == AgentPhase.WAITING_RESPONSE) {
        // Rebind happens immediately above. Consume a continuation that raced the previous
        // response instead of leaving it parked until another connector event or app restart.
        consumePendingAgentConnectorResponses()
    }
    if (state.phase == AgentPhase.COMPLETED || state.phase == AgentPhase.FAILED ||
        state.phase == AgentPhase.CANCELLED
    ) {
        provisionalAgentTasks.remove(runtime)
        agentRuntimeConversationIds.remove(runtime)
        agentRuntimeTurnIds.remove(runtime)
    }
    if (VoiceAssistantSettings.get(this).routingMode == VoiceAssistantSettings.ROUTING_MODE_NATIVE_AGENT &&
        voiceAssistantAwake
    ) {
        presentVoiceAgentState(state)
    }
}

internal fun MainActivity.clearAgentTaskWatchdogTranscript(conversationId: String, turnId: String) {
    deleteAgentTranscriptByDedupeKey(conversationId, "task-watchdog:$turnId")
    deleteAgentTranscriptByDedupeKey(conversationId, "task-watchdog-timeout:$turnId")
}


internal fun MainActivity.runtimeForConnectorResponse(
    sourceMessageId: Long,
    contactId: String,
    conversationId: String = "",
    turnId: String = "",
    taskId: String = "",
    allowTransportOnly: Boolean = false,
    restorePersisted: Boolean = Looper.myLooper() != Looper.getMainLooper()
): MobileNativeAgent? {
    if (AgentTerminalDeliveryStore.isTerminal(this, sourceMessageId)) return null
    val pendingDelivery = AgentPendingDeliveryStore.find(
        this,
        sourceMessageId,
        contactId
    )
    val responseIdentity = AgentTaskIdentityPolicy.canonicalConnectorResponseIdentity(
        pendingDelivery = pendingDelivery,
        conversationId = conversationId,
        taskId = taskId,
        turnId = turnId
    )
    fun MobileNativeAgent.accepts(): Boolean =
        if (allowTransportOnly) {
            canAcceptConnectorTransport(sourceMessageId, contactId)
        } else {
            canAcceptConnectorResponse(
                sourceMessageId,
                contactId,
                responseIdentity.conversationId,
                responseIdentity.turnId,
                responseIdentity.taskId
            )
        }
    fun MobileNativeAgent.acceptsRecoveryPredecessor(): Boolean {
        if (allowTransportOnly ||
            responseIdentity.conversationId.isBlank() ||
            responseIdentity.turnId.isBlank()
        ) return false
        val successor = AgentPendingDeliveryStore.recoverySuccessorForResponse(
            this@runtimeForConnectorResponse,
            sourceMessageId,
            responseIdentity.conversationId,
            responseIdentity.turnId
        ) ?: return false
        return canAcceptConnectorResponse(
            successor,
            contactId,
            responseIdentity.conversationId,
            responseIdentity.turnId,
            responseIdentity.taskId
        )
    }
    activeAgentTasks[sourceMessageId]
        ?.takeIf { it.accepts() || it.acceptsRecoveryPredecessor() }
        ?.let { return it }
    activeAgentTasks.values.asSequence()
        .distinct()
        .firstOrNull { it.acceptsRecoveryPredecessor() }
        ?.let { runtime ->
            activeAgentTasks[sourceMessageId] = runtime
            return runtime
        }
    provisionalAgentTasks.firstOrNull {
        it.accepts() || it.acceptsRecoveryPredecessor()
    }?.let { runtime ->
        activeAgentTasks[sourceMessageId] = runtime
        provisionalAgentTasks.remove(runtime)
        return runtime
    }
    mobileNativeAgent.takeIf {
        it.accepts() || it.acceptsRecoveryPredecessor()
    }?.let { return it }
    if (!restorePersisted || Looper.myLooper() == Looper.getMainLooper()) return null
    val cleanTurnId = responseIdentity.turnId
    if (cleanTurnId.isNotBlank()) {
        val restored = MobileNativeAgent(
            this,
            actionExecutor = directAgentActionExecutor,
            sessionStore = SharedPreferencesAgentSessionStore(this, "task:$cleanTurnId"),
            nativeToolEventSink = AgentNativeToolEventSink(::recordNativeToolLifecycleEvent)
        )
        if (restored.accepts() || restored.acceptsRecoveryPredecessor()) {
            activeAgentTasks[sourceMessageId] = restored
            agentRuntimeTurnIds[restored] = cleanTurnId
            connectorConversationId(responseIdentity.conversationId, restored, cleanTurnId)?.let {
                agentRuntimeConversationIds[restored] = it
            }
            return restored
        }
    }
    SharedPreferencesAgentSessionStore.taskStorageKeyForConnectorResponse(
        this,
        sourceMessageId,
        contactId
    )?.let { storageKey ->
        if (storageKey == "task:$cleanTurnId") return@let
        val storedTurnId = storageKey.removePrefix("task:")
        val restored = MobileNativeAgent(
            this,
            actionExecutor = directAgentActionExecutor,
            sessionStore = SharedPreferencesAgentSessionStore(this, storageKey),
            nativeToolEventSink = AgentNativeToolEventSink(::recordNativeToolLifecycleEvent)
        )
        if (restored.accepts() || restored.acceptsRecoveryPredecessor()) {
            activeAgentTasks[sourceMessageId] = restored
            agentRuntimeTurnIds[restored] = storedTurnId
            connectorConversationId(responseIdentity.conversationId, restored, storedTurnId)?.let {
                agentRuntimeConversationIds[restored] = it
            }
            Log.i(
                "GalaxySSIAgent",
                "Recovered connector response source=$sourceMessageId from saved task ${storedTurnId.take(8)}"
            )
            return restored
        }
    }
    return null
}

internal fun MainActivity.consumeOrphanedAgentConnectorResponse(response: AgentConnectorResponse): Boolean {
    if (AgentTerminalDeliveryStore.isTerminal(this, response.sourceMessageId)) {
        AgentConnectorResponseStore.remove(this, response)
        return true
    }
    val indexedTurnId = SharedPreferencesAgentSessionStore.taskStorageKeyForConnectorResponse(
        this,
        response.sourceMessageId,
        response.contactId
    )?.removePrefix("task:").orEmpty()
    val responseTurnId = response.turnId.trim()
    val responseTaskId = response.taskId.trim()
    val conversationId = sequenceOf(
        response.conversationId.trim().takeIf(String::isNotBlank),
        responseTurnId.takeIf(String::isNotBlank)?.let(agentTranscriptStore::conversationIdForTurn),
        responseTaskId.takeIf(String::isNotBlank)?.let(agentTranscriptStore::conversationIdForTask),
        indexedTurnId.takeIf(String::isNotBlank)?.let(agentTranscriptStore::conversationIdForTurn)
    ).filterNotNull()
        .mapNotNull(agentTranscriptStore::resolveMergedConversationId)
        .firstOrNull()
        ?: return false
    val entries = agentTranscriptStore.list(conversationId)
    val turnId = AgentLateConnectorResponsePolicy.exactTurnId(
        explicitTurnId = responseTurnId,
        taskTurnId = responseTaskId.takeIf(String::isNotBlank)
            ?.let(agentTranscriptStore::turnIdForTask)
            .orEmpty(),
        indexedTurnId = indexedTurnId,
        conversationEntries = entries
    )
    if (!AgentLateConnectorResponsePolicy.canAccept(
            sourceIsTerminal = false,
            exactTurnId = turnId,
            conversationEntries = entries
        )
    ) {
        AgentConnectorResponseStore.remove(this, response)
        return true
    }
    val exactTurnId = checkNotNull(turnId)
    val taskId = response.taskId.ifBlank { exactTurnId }
    val stored = AgentConnectorStreamHandoff.persistThenRetire(
        persistFinal = {
            agentTranscriptStore.upsert(
                role = AgentTranscriptRole.ASSISTANT,
                text = response.content,
                dedupeKey = AgentFinalResponseIdentity.dedupeKey(
                    turnId = exactTurnId,
                    sourceMessageId = response.sourceMessageId,
                    taskId = taskId
                ),
                conversationId = conversationId,
                turnId = exactTurnId,
                taskId = taskId,
                richOutputJson = response.richOutputJson
            )
        },
        retireLiveStream = {
            deferAgentConnectorStreamRetirement(response.sourceMessageId, conversationId)
        }
    )
    if (!stored) return false
    pendingDirectConnectorActions.remove(exactTurnId)?.let { action ->
        recordDirectAgentRun(
            turnId = exactTurnId,
            action = action,
            result = AgentActionResult(
                actionId = action.id,
                success = response.success,
                message = response.content,
                metadata = mapOf(
                    "source_message_id" to response.sourceMessageId.toString(),
                    "contact_id" to response.contactId,
                    "conversation_id" to conversationId,
                    "turn_id" to exactTurnId,
                    "task_id" to taskId,
                    "remote_task_status" to response.taskStatus,
                    "remote_execution_generation" to response.executionGeneration.toString()
                )
            )
        )
    }
    deleteAgentTranscriptByDedupeKey(conversationId, "connector-task:$taskId")
    AgentConnectorResponseStore.remove(this, response)
    AgentPendingDeliveryStore.remove(this, response.sourceMessageId)
    deleteAgentTranscriptByDedupeKey(
        conversationId,
        AgentDeliveryFailureRecorder.dedupeKey(response.sourceMessageId)
    )
    pendingDirectConnectorRuns.remove(response.sourceMessageId)
    completedConnectorTaskIds.add(AgentRemoteOutcomeCodec.taskKey(taskId, response.executionGeneration))
    agentTranscriptStore.recordUsage(
        conversationId, response.inputTokens, response.outputTokens, response.costMicros
    )
    if (conversationId == agentTranscriptStore.activeConversation().id) {
        runOnUiThread {
            if (!isFinishing && !isDestroyed) {
                refreshAgentTranscriptWindow(conversationId)
                refreshAgentConversationHeader()
            }
        }
    }
    Log.i(
        "GalaxySSIAgent",
        "Recovered orphan connector response source=${response.sourceMessageId} turn=${turnId.take(8)}"
    )
    return true
}

internal fun MainActivity.latestUnansweredAgentTurnId(conversationId: String): String? {
    val entries = agentTranscriptStore.list(conversationId)
    val answeredTurns = entries.asSequence()
        .filter { it.role == AgentTranscriptRole.ASSISTANT && it.turnId.isNotBlank() }
        .map(AgentTranscriptEntry::turnId)
        .toSet()
    return entries.asReversed().firstOrNull {
        it.role == AgentTranscriptRole.USER &&
            it.turnId.isNotBlank() &&
            it.turnId !in answeredTurns
    }?.turnId
}

internal fun MainActivity.shouldDiscardUnroutableConnectorResponse(response: AgentConnectorResponse): Boolean {
    val explicitConversation = response.conversationId.trim()
    if (explicitConversation.isNotBlank() &&
        agentTranscriptStore.resolveMergedConversationId(explicitConversation) == null
    ) {
        return true
    }
    val hasRouteReference = response.turnId.isNotBlank() || response.taskId.isNotBlank()
    val ageMillis = System.currentTimeMillis() - response.receivedAtMillis
    return hasRouteReference && ageMillis >= UNROUTABLE_CONNECTOR_GRACE_MILLIS
}

internal fun MainActivity.connectorConversationId(
    explicitConversationId: String,
    runtime: MobileNativeAgent?,
    turnId: String
): String? {
    val explicit = explicitConversationId.trim()
    if (explicit.isNotBlank()) return agentTranscriptStore.resolveMergedConversationId(explicit)
    val runtimeConversation = runtime?.let(agentRuntimeConversationIds::get).orEmpty()
    if (runtimeConversation.isNotBlank()) {
        agentTranscriptStore.resolveMergedConversationId(runtimeConversation)?.let { return it }
    }
    return agentTranscriptStore.conversationIdForTurn(turnId)
        ?.let(agentTranscriptStore::resolveMergedConversationId)
}

internal fun MainActivity.handleSelfEvolutionEvent(envelope: JSONObject?): Boolean {
    val type = envelope?.optString("type").orEmpty()
    if (type !in setOf("evolution_task_event", "evolution_task_snapshot")) return false
    val desktopId = envelope?.optString("desktop_id").orEmpty()
    if (desktopId.isBlank()) return true
    if (type == "evolution_task_snapshot") {
        val tasks = envelope?.optJSONArray("tasks").orEmptyJsonObjects()
        remoteSelfEvolutionStore.replace(desktopId, tasks)
    } else {
        envelope?.optJSONObject("task")?.let { remoteSelfEvolutionStore.save(desktopId, it) }
        val event = envelope?.optString("event").orEmpty()
        if (event in setOf("candidate_ready", "attempt_failed", "failed", "command_failed")) {
            val message = when (event) {
                "candidate_ready" -> getString(R.string.cc_evolution_remote_candidate_ready)
                "attempt_failed", "failed" -> getString(R.string.cc_evolution_remote_failed)
                else -> envelope?.optString("error")
                    ?.takeIf(String::isNotBlank)
                    ?: getString(R.string.cc_evolution_remote_command_failed)
            }
            Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        }
    }
    if (controlCenterDestination?.route == ControlCenterRoute.SELF_EVOLUTION) {
        renderControlCenterSelfEvolutionPage()
    }
    return true
}

internal fun MainActivity.handleVoiceAgentRunUpdate(update: VoiceAgentRunUpdate) {
    if (!VoiceFeatureFlags.isAgentVoiceRunBridgeEnabled(this)) return
    val snapshot = update.snapshot
    val coordinatorSessionId = voiceCoordinatorSession(snapshot.traceId).ifBlank {
        voiceCoordinatorIdsByTurn[snapshot.turnId].orEmpty()
    }.ifBlank {
        voiceCoordinatorIdsBySourceMessage[snapshot.sourceMessageId].orEmpty()
    }
    if (update.event is VoiceAgentEvent.RunCreated && coordinatorSessionId.isNotBlank()) {
        dispatchVoiceCoordinator(
            VoiceInteractionEvent.AgentRunCreated(coordinatorSessionId, snapshot.runId)
        )
    }
    if (update.firstAcceptance && snapshot.traceId.isNotBlank()) {
        VoiceLatencyTelemetry.record(
            this,
            snapshot.traceId,
            VoiceTraceEvents.AGENT_RUN_ACCEPTED,
            mapOf("agent_provider" to snapshot.agentId.ifBlank { "remote_agent" }),
            once = true
        )
    }
    val immediate = update.event is VoiceAgentEvent.RunCreated ||
        update.event is VoiceAgentEvent.Accepted ||
        update.event is VoiceAgentEvent.ApprovalRequired ||
        snapshot.state.isTerminal
    queueVoiceAgentRunCard(snapshot, immediate)
    announceVoiceAgentRunUpdate(update)
}

internal fun MainActivity.queueVoiceAgentRunCard(snapshot: VoiceAgentRunSnapshot, immediate: Boolean) {
    if (immediate) {
        pendingVoiceAgentRunCardUpdates.remove(snapshot.runId)
        syncVoiceAgentRunCard(snapshot)
        return
    }
    pendingVoiceAgentRunCardUpdates[snapshot.runId] = snapshot
    if (voiceAgentRunCardRefreshScheduled) return
    voiceAgentRunCardRefreshScheduled = true
    handler.postDelayed(voiceAgentRunCardRefresh, VOICE_AGENT_RUN_CARD_COALESCE_MS)
}

internal fun MainActivity.restoreVoiceAgentRunCards() {
    thread(name = "galaxyssi-voice-agent-run-restore") {
        val snapshots = runCatching {
            voiceAgentRunBridge.recentSnapshots(MAX_RESTORED_VOICE_AGENT_RUNS)
        }.getOrDefault(emptyList())
        if (isFinishing || isDestroyed) return@thread
        agentTranscriptContentExecutor.execute {
            if (!isFinishing && !isDestroyed) snapshots.forEach(::syncVoiceAgentRunCard)
        }
    }
}

internal fun MainActivity.syncVoiceAgentRunCard(snapshot: VoiceAgentRunSnapshot) {
    if (Looper.myLooper() == Looper.getMainLooper()) {
        agentTranscriptContentExecutor.execute {
            if (!isFinishing && !isDestroyed) syncVoiceAgentRunCard(snapshot)
        }
        return
    }
    val conversationId = agentTranscriptStore.resolveMergedConversationId(snapshot.conversationId)
        ?: snapshot.conversationId
    if (conversationId.isBlank() || snapshot.taskId.isBlank()) return
    val statusLabel = voiceAgentRunStatusLabel(snapshot)
    val agentLabel = snapshot.agentName.ifBlank {
        contactById(snapshot.contactId).name
    }.ifBlank { getString(R.string.agent_task_details_title) }
    val currentStep = snapshot.progressMessage
        .ifBlank { statusLabel }
        .take(MAX_VOICE_AGENT_RUN_STEP_CHARACTERS)
    rememberAgentExecutionPresentation(
        snapshot.taskId,
        AgentExecutionPresentationPolicy.remote(
            executorId = snapshot.agentId.ifBlank { snapshot.contactId },
            executorLabel = agentLabel,
            locationKind = "desktop",
            locationId = snapshot.deviceId,
            locationName = snapshot.deviceId,
            runtimeKind = "desktop_agent",
            status = snapshot.state.name.lowercase(Locale.ROOT),
            currentStep = currentStep,
            startedAtMillis = snapshot.acceptedAtMillis.takeIf { it > 0L }
                ?: snapshot.createdAtMillis,
            completedAtMillis = snapshot.completedAtMillis,
            advertisedCancellable = snapshot.cancellable
        ).copy(voiceRun = AgentVoiceRunReference(
            snapshot.runId, conversationId, snapshot.turnId, snapshot.taskId
        ))
    )
    agentTranscriptStore.upsert(
        role = AgentTranscriptRole.PROCESS,
        text = "$agentLabel \u00b7 $statusLabel",
        dedupeKey = "connector-task:${snapshot.taskId}",
        timestampMillis = snapshot.updatedAtMillis,
        conversationId = conversationId,
        turnId = snapshot.turnId,
        taskId = snapshot.taskId
    )
    snapshot.firstDiscovery.takeIf(String::isNotBlank)?.let { discovery ->
        agentTranscriptStore.upsert(
            role = AgentTranscriptRole.PROCESS,
            text = discovery,
            dedupeKey = "voice-agent-first-discovery:${snapshot.runId}",
            timestampMillis = snapshot.updatedAtMillis,
            conversationId = conversationId,
            turnId = snapshot.turnId,
            taskId = snapshot.taskId
        )
    }
    if (conversationId == agentTranscriptStore.activeConversation().id &&
        isAgentTranscriptAdapterInitialized()
    ) {
        refreshAgentTranscriptWindow(conversationId)
    }
}

internal fun MainActivity.voiceAgentRunStatusLabel(snapshot: VoiceAgentRunSnapshot): String = getString(
    when (snapshot.state) {
        VoiceAgentRunState.CREATED -> R.string.agent_task_status_created
        VoiceAgentRunState.ACCEPTED -> R.string.agent_task_status_accepted
        VoiceAgentRunState.QUEUED -> R.string.agent_task_status_queued
        VoiceAgentRunState.STARTING -> if (snapshot.stage == "recovering") {
            R.string.agent_task_status_recovering
        } else {
            R.string.agent_task_status_starting
        }
        VoiceAgentRunState.RUNNING -> R.string.agent_task_status_running
        VoiceAgentRunState.WAITING_INPUT -> R.string.agent_task_status_waiting_input
        VoiceAgentRunState.WAITING_APPROVAL -> R.string.agent_task_status_waiting_approval
        VoiceAgentRunState.CANCELLING -> R.string.agent_task_status_cancelling
        VoiceAgentRunState.COMPLETED -> R.string.agent_task_status_completed
        VoiceAgentRunState.FAILED -> R.string.agent_task_status_failed
        VoiceAgentRunState.CANCELLED -> R.string.agent_task_status_cancelled
        VoiceAgentRunState.TIMED_OUT -> R.string.agent_task_status_timed_out
    }
)

internal fun MainActivity.announceVoiceAgentRunUpdate(update: VoiceAgentRunUpdate) {
    val snapshot = update.snapshot
    if (snapshot.traceId.isBlank() || !voiceAssistantAwake ||
        activeMainTab != PAGE_VOICE || wakePage.visibility != View.VISIBLE
    ) return
    val spoken = when {
        update.firstAcceptance -> getString(
            R.string.voice_agent_run_accepted,
            snapshot.agentName.ifBlank { getString(R.string.agent_task_details_title) }
        )
        update.event is VoiceAgentEvent.ApprovalRequired ->
            getString(R.string.voice_agent_run_approval_required)
        else -> ""
    }
    if (spoken.isBlank()) return
    updateWakeVoiceUi(voiceAgentRunStatusLabel(snapshot), spoken)
    if (!VoiceAssistantSettings.get(this).speakReplies || voiceAssistantListening ||
        voiceAssistantRecordingCommand || agentVoiceListening
    ) return
    speakWithConfiguredTts(spoken, traceId = snapshot.traceId) {
        scheduleVoiceRestart(350L)
    }
}

internal fun MainActivity.cancelVoiceAgentRun(snapshot: VoiceAgentRunSnapshot) {
    if (!snapshot.cancellable) return
    val sent = GalaxySSIMqttClient.publishAgentTaskCancel(
        taskId = snapshot.taskId,
        contactId = snapshot.contactId,
        sourceMessageId = snapshot.sourceMessageId,
        conversationId = snapshot.conversationId,
        turnId = snapshot.turnId,
        topicOverride = AppStore.outgoingTopicForContact(this, snapshot.contactId)
    )
    if (sent) {
        voiceAgentRunBridge.markCancellationRequested(snapshot.runId)
        Toast.makeText(this, R.string.agent_task_status_cancelling, Toast.LENGTH_SHORT).show()
    } else {
        Toast.makeText(this, R.string.agent_loop_timeline_remote_cancel_failed, Toast.LENGTH_LONG).show()
    }
}

internal fun MainActivity.showVoiceAgentRunDetails(snapshot: VoiceAgentRunSnapshot) {
    val task = agentTaskCenter.find(snapshot.taskId)
    if (task != null) {
        showAgentTaskDetails(task)
        return
    }
    AlertDialog.Builder(this)
        .setTitle(R.string.agent_task_detail_title)
        .setMessage(buildString {
            appendLine(snapshot.goal.ifBlank { snapshot.taskId })
            appendLine()
            appendLine("${getString(R.string.agent_task_detail_status)}: ${voiceAgentRunStatusLabel(snapshot)}")
            appendLine("${getString(R.string.agent_task_detail_target)}: ${snapshot.agentName.ifBlank { snapshot.agentId }}")
            if (snapshot.deviceId.isNotBlank()) {
                appendLine("${getString(R.string.agent_task_detail_execution)}: ${snapshot.deviceId}")
            }
            if (snapshot.progressMessage.isNotBlank()) {
                appendLine()
                append(snapshot.progressMessage)
            }
        }.trim())
        .setPositiveButton(android.R.string.ok, null)
        .show()
}

internal fun MainActivity.publishAgentTaskPartialResult(
    envelope: JSONObject,
    sourceMessageId: Long,
    contactId: String,
    status: String
) {
    if (status != "running") return
    val partial = envelope.optJSONObject("partial_result") ?: return
    if (!partial.optBoolean("user_visible", true)) return
    val content = partial.optString("text").trim().take(64_000)
    if (content.isBlank()) return
    if (sourceMessageId in supervisedProjectConnectorSourceIds) return
    if (AgentSupervisedProjectControlPayload.isControlPayloadFragment(content)) {
        supervisedProjectConnectorSourceIds.add(sourceMessageId)
        liveAgentConnectorStreams.remove(sourceMessageId)
        return
    }
    val sequence = partial.optLong("sequence", 0L)
    com.galaxyssi.chat.metrics.AgentLatencyTelemetry.record(
        this, envelope.optString("task_id"), "phone_response_received"
    )
    AgentConnectorStreamBus.publish(
        this,
        AgentConnectorStreamUpdate(
            sourceMessageId = sourceMessageId,
            contactId = contactId,
            content = content,
            conversationId = envelope.optString("conversation_id"),
            turnId = envelope.optString("turn_id"),
            taskId = envelope.optString("task_id"),
            firstDelta = sequence <= 1L
        )
    )
}
