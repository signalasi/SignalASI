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
import com.galaxyssi.chat.voice.audio.PeerVoiceMessageAudio
import com.galaxyssi.chat.voice.audio.PeerVoiceOpusEncoder
import com.galaxyssi.chat.voice.audio.PeerVoiceOpusRecorder
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

internal fun MainActivity.startPeerVoiceMessageRecording(): Boolean {
    if (isVoiceCaptureActive()) return false
    val traceId = VoiceLatencyTelemetry.startSession(
        this,
        mapOf(
            "recording_source" to "peer_voice_message",
            "sample_rate_hz" to PeerVoiceMessageAudio.SAMPLE_RATE_HZ.toString(),
            "channel_count" to PeerVoiceMessageAudio.CHANNEL_COUNT.toString()
        )
    )
    val coordinatorSessionId = beginVoiceCoordinatorSession("chat_message", traceId)
    VoiceLatencyTelemetry.record(
        this,
        traceId,
        VoiceTraceEvents.MICROPHONE_OPEN_STARTED,
        mapOf("recording_source" to "peer_voice_message"),
        once = true
    )
    return runCatching {
        check(PeerVoiceOpusEncoder.isAvailable()) { "Opus encoder is unavailable" }
        val activeRecorder = PeerVoiceOpusRecorder(this).apply { start() }
        peerVoiceRecorder = activeRecorder
        recordingFile = null
        recordingStartedAt = System.currentTimeMillis()
        recordingPurpose = "chat_message"
        activeVoiceTraceId = traceId
        recordingVoiceTraceId = traceId
        recordingVoiceCoordinatorSessionId = coordinatorSessionId
        if (coordinatorSessionId.isNotBlank()) {
            dispatchVoiceCoordinator(VoiceInteractionEvent.CapturePrepared(coordinatorSessionId))
            dispatchVoiceCoordinator(
                VoiceInteractionEvent.SpeechStarted(
                    coordinatorSessionId,
                    SystemClock.elapsedRealtimeNanos()
                )
            )
        }
        VoiceLatencyTelemetry.record(
            this,
            traceId,
            VoiceTraceEvents.MICROPHONE_OPENED,
            mapOf(
                "recording_source" to "peer_voice_message",
                "codec" to "opus",
                "sample_rate_hz" to PeerVoiceMessageAudio.SAMPLE_RATE_HZ.toString(),
                "channel_count" to PeerVoiceMessageAudio.CHANNEL_COUNT.toString()
            ),
            once = true
        )
        VoiceLatencyTelemetry.record(
            this,
            traceId,
            VoiceTraceEvents.SPEECH_STARTED,
            mapOf("recording_source" to "peer_voice_message"),
            once = true
        )
        Log.i(
            "GalaxySSIVoice",
            "Peer voice recording started codec=opus sampleRate=${PeerVoiceMessageAudio.SAMPLE_RATE_HZ} " +
                "channels=${PeerVoiceMessageAudio.CHANNEL_COUNT} bitrate=${PeerVoiceMessageAudio.OPUS_BIT_RATE_BPS}"
        )
        true
    }.getOrElse { error ->
        peerVoiceRecorder?.cancel()
        peerVoiceRecorder = null
        recordingFile = null
        recordingPurpose = ""
        recordingVoiceTraceId = ""
        recordingVoiceCoordinatorSessionId = ""
        failVoiceCoordinator(traceId, error.javaClass.simpleName)
        VoiceLatencyTelemetry.record(
            this,
            traceId,
            VoiceTraceEvents.SESSION_FAILED,
            mapOf("error_code" to error.javaClass.simpleName),
            once = true
        )
        Log.w("GalaxySSIVoice", "Peer Opus recorder unavailable", error)
        false
    }
}

internal fun MainActivity.stopRecording(send: Boolean) {
    if (recordingPurpose == "agent_input") {
        stopAgentInputRecording(send)
        return
    }
    peerVoiceRecorder?.let { activeRecorder ->
        stopPeerVoiceMessageRecording(activeRecorder, send)
        return
    }
    if (pcmVoiceSession != null) {
        stopPcmRecording(send, if (send) "release_send" else "user_cancelled")
        return
    }
    val activeRecorder = recorder ?: return
    val traceId = recordingVoiceTraceId
    val coordinatorSessionId = recordingVoiceCoordinatorSessionId.ifBlank {
        voiceCoordinatorSession(traceId)
    }
    recorder = null
    recordingVoiceTraceId = ""
    recordingVoiceCoordinatorSessionId = ""
    val stoppedCleanly = runCatching {
        activeRecorder.stop()
        true
    }.getOrDefault(false)
    runCatching { activeRecorder.reset() }
    runCatching { activeRecorder.release() }
    val file = recordingFile
    recordingFile = null
    recordingPurpose = ""
    VoiceLatencyTelemetry.record(
        this,
        traceId,
        VoiceTraceEvents.SPEECH_ENDED,
        mapOf("endpoint_reason" to if (send) "release_send" else "cancel"),
        once = true
    )
    if (coordinatorSessionId.isNotBlank()) {
        dispatchVoiceCoordinator(
            VoiceInteractionEvent.SpeechEnded(
                coordinatorSessionId,
                SystemClock.elapsedRealtimeNanos()
            )
        )
    }
    if (!send || !stoppedCleanly || file == null || !file.exists() || file.length() <= 0L) {
        file?.delete()
        if (coordinatorSessionId.isNotBlank()) {
            if (send) {
                failVoiceCoordinator(traceId, "recorder_stop_failed")
            } else {
                dispatchVoiceCoordinator(VoiceInteractionEvent.Cancelled(coordinatorSessionId, "user_cancelled"))
            }
        }
        VoiceLatencyTelemetry.record(
            this,
            traceId,
            if (send) VoiceTraceEvents.SESSION_FAILED else VoiceTraceEvents.SESSION_CANCELLED,
            mapOf("error_code" to if (send) "RECORDER_STOP_FAILED" else "USER_CANCELLED"),
            once = true
        )
        return
    }
    if (coordinatorSessionId.isNotBlank()) {
        dispatchVoiceCoordinator(VoiceInteractionEvent.FinalizationStarted(coordinatorSessionId))
    }
    val seconds = ((System.currentTimeMillis() - recordingStartedAt) / 1000).coerceAtLeast(1)
    val contact = selectedContact ?: CONTACT_HERMES
    sendVoiceRecordingThroughPipeline(
        sourceFile = file,
        contact = contact,
        seconds = seconds,
        label = "${getString(R.string.message_voice_prefix)} ${seconds}s",
        source = "chat_hold_to_talk",
        traceId = traceId
    )
}

private fun MainActivity.stopPeerVoiceMessageRecording(
    activeRecorder: PeerVoiceOpusRecorder,
    send: Boolean
) {
    val traceId = recordingVoiceTraceId
    val coordinatorSessionId = recordingVoiceCoordinatorSessionId.ifBlank {
        voiceCoordinatorSession(traceId)
    }
    val startedAt = recordingStartedAt
    val contact = selectedContact ?: CONTACT_HERMES
    peerVoiceRecorder = null
    recordingFile = null
    recordingPurpose = ""
    recordingVoiceTraceId = ""
    recordingVoiceCoordinatorSessionId = ""
    VoiceLatencyTelemetry.record(
        this,
        traceId,
        VoiceTraceEvents.SPEECH_ENDED,
        mapOf("endpoint_reason" to if (send) "release_send" else "cancel"),
        once = true
    )
    if (coordinatorSessionId.isNotBlank()) {
        dispatchVoiceCoordinator(
            VoiceInteractionEvent.SpeechEnded(
                coordinatorSessionId,
                SystemClock.elapsedRealtimeNanos()
            )
        )
    }
    if (!send) {
        activeRecorder.cancel()
        if (coordinatorSessionId.isNotBlank()) {
            dispatchVoiceCoordinator(VoiceInteractionEvent.Cancelled(coordinatorSessionId, "user_cancelled"))
        }
        VoiceLatencyTelemetry.record(
            this,
            traceId,
            VoiceTraceEvents.SESSION_CANCELLED,
            mapOf("error_code" to "USER_CANCELLED"),
            once = true
        )
        return
    }
    if (coordinatorSessionId.isNotBlank()) {
        dispatchVoiceCoordinator(VoiceInteractionEvent.FinalizationStarted(coordinatorSessionId))
    }
    val messageId = newMessageId()
    outboundMessageExecutor.execute {
        runCatching {
            val result = activeRecorder.stopAndEncode()
            val persistentFile = try {
                PeerMessageAttachmentStore.persistOutgoingVoiceBytes(
                    filesDir = filesDir,
                    encoded = result.encodedOggOpus,
                    messageId = messageId,
                    extension = "opus"
                ).getOrThrow()
            } finally {
                result.encodedOggOpus.wipeSensitive()
            }
            val durationMillis = result.durationMillis.coerceAtLeast(1_000L)
            VoiceLatencyTelemetry.record(
                this,
                traceId,
                VoiceTraceEvents.PCM_CAPTURE_STOPPED,
                mapOf(
                    "codec" to "opus",
                    "duration_ms" to durationMillis.toString(),
                    "elapsed_ms" to (System.currentTimeMillis() - startedAt).coerceAtLeast(0L).toString(),
                    "noise_suppressor" to result.noiseSuppressorEnabled.toString(),
                    "echo_canceler" to result.echoCancelerEnabled.toString(),
                    "target_lufs" to PeerVoiceMessageAudio.TARGET_LUFS.toString(),
                    "peak_dbfs" to PeerVoiceMessageAudio.PEAK_DBFS.toString()
                ),
                once = true
            )
            runOnUiThread {
                sendPeerVoiceRecording(
                    messageId = messageId,
                    contact = contact,
                    file = persistentFile,
                    durationMillis = durationMillis,
                    mediaExtension = "opus"
                )
            }
        }.onFailure { error ->
            activeRecorder.cancel()
            failVoiceCoordinator(traceId, error.javaClass.simpleName)
            VoiceLatencyTelemetry.record(
                this,
                traceId,
                VoiceTraceEvents.SESSION_FAILED,
                mapOf("error_code" to error.javaClass.simpleName),
                once = true
            )
            Log.e("GalaxySSIVoice", "Unable to finalize peer Opus voice message", error)
            runOnUiThread {
                Toast.makeText(
                    this,
                    getString(R.string.toast_send_failed, error.message ?: ""),
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }
}

internal fun MainActivity.stopAgentInputRecording(send: Boolean) {
    if (pcmVoiceSession != null) {
        stopPcmRecording(send, if (send) "release_send" else "user_cancelled")
        return
    }
    val activeRecorder = recorder ?: run {
        if (!send) clearAgentVoiceDraftSnapshot()
        return
    }
    val purpose = recordingPurpose
    if (!send) clearAgentVoiceDraftSnapshot()
    val traceId = recordingVoiceTraceId
    val coordinatorSessionId = recordingVoiceCoordinatorSessionId.ifBlank {
        voiceCoordinatorSession(traceId)
    }
    val pendingAgentVoice = if (send) {
        showAgentVoiceTranscriptionPending(traceId)
    } else {
        null
    }
    recorder = null
    recordingVoiceTraceId = ""
    recordingVoiceCoordinatorSessionId = ""
    val stoppedCleanly = runCatching {
        activeRecorder.stop()
        true
    }.getOrDefault(false)
    runCatching { activeRecorder.reset() }
    runCatching { activeRecorder.release() }
    val file = recordingFile
    recordingFile = null
    recordingPurpose = ""
    VoiceLatencyTelemetry.record(
        this,
        traceId,
        VoiceTraceEvents.SPEECH_ENDED,
        mapOf("endpoint_reason" to if (send) "release_send" else "cancel"),
        once = true
    )
    if (coordinatorSessionId.isNotBlank()) {
        dispatchVoiceCoordinator(
            VoiceInteractionEvent.SpeechEnded(
                coordinatorSessionId,
                SystemClock.elapsedRealtimeNanos()
            )
        )
    }
    Log.i("GalaxySSIVoice", "Agent input recording stopped purpose=$purpose send=$send clean=$stoppedCleanly bytes=${file?.length() ?: 0L}")
    if (!send || !stoppedCleanly || file == null || !file.exists() || file.length() <= 0L) {
        file?.delete()
        dismissAgentVoiceTranscriptionPending(pendingAgentVoice)
        if (coordinatorSessionId.isNotBlank()) {
            if (send) {
                failVoiceCoordinator(traceId, "recorder_stop_failed")
            } else {
                dispatchVoiceCoordinator(VoiceInteractionEvent.Cancelled(coordinatorSessionId, "user_cancelled"))
            }
        }
        VoiceLatencyTelemetry.record(
            this,
            traceId,
            if (send) VoiceTraceEvents.SESSION_FAILED else VoiceTraceEvents.SESSION_CANCELLED,
            mapOf("error_code" to if (send) "RECORDER_STOP_FAILED" else "USER_CANCELLED"),
            once = true
        )
        return
    }
    if (coordinatorSessionId.isNotBlank()) {
        dispatchVoiceCoordinator(VoiceInteractionEvent.FinalizationStarted(coordinatorSessionId))
    }
    val pending = checkNotNull(pendingAgentVoice)
    requestAgentInputTranscription(
        file,
        traceId,
        pendingVoice = pending
    )
}

internal fun MainActivity.requestAgentInputTranscription(
    sourceFile: File,
    traceId: String,
    pcmSamples: ShortArray? = null,
    sampleRateHz: Int = 16_000,
    pendingVoice: PendingAgentVoiceTranscription
): Boolean {
    transcribeLocally(
        sourceFile,
        traceId = traceId,
        pcmSamples = pcmSamples,
        sampleRateHz = sampleRateHz,
        purpose = "agent_input",
        onSuccess = { transcript ->
            val draftSnapshot = pendingVoice.draftSnapshot
            if (draftSnapshot != null) {
                appendAgentVoiceTranscriptToDraft(draftSnapshot, transcript)
            } else {
                submitAgentGoal(
                    voiceTraceId = traceId,
                    pendingVoiceDedupeKey = pendingVoice.dedupeKey,
                    pendingVoiceConversationId = pendingVoice.conversationId,
                    goalOverride = transcript,
                    attachmentsOverride = pendingVoice.attachments
                )
            }
        },
        onFailure = {
            dismissAgentVoiceTranscriptionPending(pendingVoice)
        }
    )
    return true
}

internal fun MainActivity.captureAgentVoiceDraftSnapshot() {
    agentVoiceDraftSnapshot = AgentVoiceTranscriptPolicy.draftSnapshot(
        conversationId = agentTranscriptStore.activeConversation().id,
        text = agentGoalInput.text?.toString().orEmpty()
    )
}

internal fun MainActivity.consumeAgentVoiceDraftSnapshot(): AgentVoiceDraftSnapshot? =
    agentVoiceDraftSnapshot.also { agentVoiceDraftSnapshot = null }

internal fun MainActivity.clearAgentVoiceDraftSnapshot() {
    agentVoiceDraftSnapshot = null
}

internal fun MainActivity.appendAgentVoiceTranscriptToDraft(
    snapshot: AgentVoiceDraftSnapshot,
    transcript: String
): Boolean {
    if (snapshot.conversationId != agentTranscriptStore.activeConversation().id) return false
    val currentDraft = agentGoalInput.text?.toString().orEmpty().ifBlank { snapshot.text }
    val merged = AgentVoiceTranscriptPolicy.mergeDraftWithTranscript(currentDraft, transcript)
    if (merged.isBlank()) return false
    agentGoalInput.setText(merged)
    agentGoalInput.setSelection(merged.length)
    updateAgentSubmitButtonAppearance(true)
    return true
}

internal fun MainActivity.sendVoiceRecordingThroughPipeline(
    sourceFile: File,
    contact: Contact,
    seconds: Long,
    label: String,
    source: String,
    traceId: String = "",
    pcmSamples: ShortArray? = null,
    sampleRateHz: Int = 16_000
): Boolean {
    if (!sourceFile.exists()) return false
    val msgId = newMessageId()
    val extension = sourceFile.extension.lowercase().takeIf { it in setOf("wav", "m4a", "opus") } ?: "wav"
    val peerChat = AppStore.isDirectPeerContact(this, contact.id)
    if (peerChat) {
        val persistentFile = PeerMessageAttachmentStore.persistOutgoingVoice(
            filesDir = filesDir,
            cacheDir = cacheDir,
            source = sourceFile,
            messageId = msgId,
            extension = extension
        ).getOrElse { error ->
            Log.e("GalaxySSIVoice", "Unable to persist peer voice message", error)
            Toast.makeText(this, getString(R.string.toast_send_failed, error.message ?: ""), Toast.LENGTH_SHORT).show()
            return false
        }
        sendPeerVoiceRecording(msgId, contact, persistentFile, seconds.coerceAtLeast(1L) * 1_000L, extension)
        return true
    }
    val voiceFile = File(cacheDir, "voices/msg_${msgId}.$extension").apply {
        parentFile?.mkdirs()
    }
    val moved = sourceFile.renameTo(voiceFile)
    val finalFile = if (moved) voiceFile else sourceFile
    val msg = ChatMessage(msgId, label, true, CONTACT_ME)
    addMessage(msg)
    Log.i("GalaxySSIVoice", "Voice pipeline send source=$source target=${contact.id} seconds=$seconds bytes=${finalFile.length()} messageId=$msgId")
    publishInlineVoiceFile(msg.id, contact, finalFile, traceId, pcmSamples, sampleRateHz)
    return true
}

internal fun MainActivity.sendPeerVoiceRecording(
    messageId: Long,
    contact: Contact,
    file: File,
    durationMillis: Long,
    mediaExtension: String
) {
    val normalizedExtension = mediaExtension.lowercase().takeIf { it in setOf("wav", "m4a", "opus") } ?: "wav"
    val mimeType = when (normalizedExtension) {
        "opus" -> "audio/ogg"
        "m4a" -> "audio/mp4"
        else -> "audio/wav"
    }
    val normalizedDurationMillis = durationMillis.coerceAtLeast(1_000L)
    val input = AgentInputAttachment(
        id = "voice-$messageId",
        uri = LocalAttachmentUris.forFile(this, file, "voice-$messageId.$normalizedExtension"),
        displayName = "voice-$messageId.$normalizedExtension",
        mimeType = mimeType,
        sizeBytes = AttachmentLocalStore.metadata(file).plaintextLength
    )
    val message = ChatMessage(
        id = messageId,
        content = "",
        isMine = true,
        contact = CONTACT_ME,
        deliveryStatus = getString(R.string.delivery_status_sending),
        deliveryTrace = mutableListOf(newTraceEvent("created", "peer_voice")),
        attachments = listOf(
            PeerChatAttachment(
                name = input.displayName,
                mimeType = mimeType,
                sizeBytes = input.sizeBytes,
                uri = input.uri.toString(),
                durationMillis = normalizedDurationMillis
            )
        )
    )
    addMessage(message)
    outboundMessageExecutor.execute {
        val result = GalaxySSIMqttClient.publishPeerMessageResult(
            content = "",
            contactId = contact.id,
            topicOverride = AppStore.outgoingTopicForContact(this, contact.id),
            clientMessageId = messageId,
            deliveryTrace = deliveryTraceJson(message.deliveryTrace),
            attachments = listOf(input),
            messageKind = "voice",
            durationMillis = normalizedDurationMillis
        )
        runOnUiThread {
            if (result == MqttPublishResult.FAILED) {
                markPeerAttachmentTransferFailed(messageId, contact.id)
            }
            updateMessageStatus(
                messageId,
                contact.id,
                getString(
                    when (result) {
                        MqttPublishResult.PUBLISHED -> R.string.delivery_status_sent
                        MqttPublishResult.QUEUED -> R.string.delivery_status_queued
                        MqttPublishResult.FAILED -> R.string.delivery_status_failed
                    }
                )
            )
        }
    }
}

internal fun MainActivity.requestVoiceAgentTranscription(
    sourceFile: File,
    contact: Contact,
    traceId: String,
    pcmSamples: ShortArray? = null,
    sampleRateHz: Int = 16_000
): Boolean {
    if (!sourceFile.exists()) return false
    transcribeLocally(
        sourceFile,
        traceId = traceId,
        pcmSamples = pcmSamples,
        sampleRateHz = sampleRateHz,
        purpose = "voice_agent",
        onSuccess = { transcript -> submitVoiceAgentGoal(transcript, traceId) }
    )
    return true
}

internal fun MainActivity.publishInlineVoiceFile(
    messageId: Long,
    contact: Contact,
    file: File,
    traceId: String,
    pcmSamples: ShortArray? = null,
    sampleRateHz: Int = 16_000
) {
    updateMessageStatus(messageId, contact.id, getString(R.string.voice_status_transcribing))
    transcribeLocally(file, traceId = traceId, pcmSamples = pcmSamples, sampleRateHz = sampleRateHz, purpose = "chat", onSuccess = { transcript ->
        updateMessageStatus(messageId, contact.id, getString(R.string.voice_status_transcribed))
        sendOutgoingText(contact, transcript, traceId)
    }, onFailure = {
        updateMessageStatus(messageId, contact.id, getString(R.string.delivery_status_failed))
    })
}

internal fun MainActivity.handleVoiceFastTranscript(
    purpose: String,
    traceId: String,
    transcript: String,
    pcmSnapshot: ShortArray,
    sampleRateHz: Int,
    language: String,
    decodedModelProfileId: String,
    confidence: Float?,
    providerId: String = "whisper.cpp",
    onSuccess: (String) -> Unit,
    onFailure: () -> Unit
) {
    if (PeerVoiceTranscriptionPolicy.returnsTextWithoutCommandExecution(purpose)) {
        pcmSnapshot.wipeSensitive()
        onSuccess(transcript.trim())
        return
    }
    val sessionId = voiceCoordinatorSession(traceId)
        .ifBlank { traceId }
        .ifBlank { UUID.randomUUID().toString() }
    val risk = DefaultVoiceCommandRiskClassifier.classify(transcript)
    val settings = VoiceAssistantSettings.get(this)
    val selectedProfile = WhisperModelManager.model(settings.asrModel)
    val durationMs = pcmSnapshot.size.toLong() * 1_000L / sampleRateHz.coerceAtLeast(1)
    val trigger = VoiceSecondPassTriggerPolicy.evaluate(
        fast = TranscriptHypothesis(
            text = transcript,
            revision = 1,
            provider = providerId,
            modelProfileId = decodedModelProfileId,
            confidence = confidence,
            transcriptId = sessionId,
            isFinal = true
        ),
        utteranceDurationMs = durationMs,
        userRequestedAccuracy = settings.asrRuntimeMode == WhisperUserVoiceMode.ACCURATE
    )
    val policyDecision = if (
        VoiceFeatureFlags.isWhisperPolicyEngineEnabled(this) &&
        VoiceFeatureFlags.isWhisperSecondPassEnabled(this)
    ) {
        WhisperBenchmarkManager.decide(
            context = this,
            userMode = settings.asrRuntimeMode,
            selectedProfileId = selectedProfile.id,
            foreground = true,
            decodeQueueDepth = whisperDecodeScheduler?.queueSnapshot()?.queuedPartials ?: 0,
            utteranceDurationMs = durationMs,
            highRiskTask = risk >= VoiceCommandRisk.HIGH,
            accuracySensitiveTask = trigger.requested
        )
    } else null
    val fastProfileId = decodedModelProfileId.ifBlank {
        policyDecision?.fastProfileId ?: selectedProfile.id
    }
    val fast = TranscriptHypothesis(
        text = transcript,
        revision = 1,
        provider = providerId,
        modelProfileId = fastProfileId,
        confidence = confidence,
        transcriptId = sessionId,
        isFinal = true
    )
    voiceExecutionLedger.begin(
        sessionId = sessionId,
        idempotencyKey = "$sessionId:primary-dispatch",
        fast = fast,
        risk = risk
    )

    val requiresVoiceConfirmation = risk >= VoiceCommandRisk.HIGH
    val secondPassStarted = scheduleVoiceSecondPass(
        purpose = purpose,
        traceId = traceId,
        sessionId = sessionId,
        fast = fast,
        risk = risk,
        pcmSnapshot = pcmSnapshot,
        sampleRateHz = sampleRateHz,
        language = language,
        accurateProfileId = policyDecision?.accurateProfileId,
        runSecondPass = policyDecision?.runSecondPass == true,
        onSuccess = onSuccess,
        onFailure = onFailure,
        waitForConfirmation = requiresVoiceConfirmation
    )
    pcmSnapshot.wipeSensitive()

    if (requiresVoiceConfirmation) {
        if (!secondPassStarted) {
            showVoiceRiskConfirmation(
                purpose = purpose,
                traceId = traceId,
                sessionId = sessionId,
                fast = fast,
                accurate = null,
                diff = null,
                risk = risk,
                onSuccess = onSuccess,
                onFailure = onFailure
            )
        }
        return
    }
    routeVoiceTranscriptOnce(traceId, sessionId, fast, onSuccess)
}

internal fun MainActivity.scheduleVoiceSecondPass(
    purpose: String,
    traceId: String,
    sessionId: String,
    fast: TranscriptHypothesis,
    risk: VoiceCommandRisk,
    pcmSnapshot: ShortArray,
    sampleRateHz: Int,
    language: String,
    accurateProfileId: String?,
    runSecondPass: Boolean,
    onSuccess: (String) -> Unit,
    onFailure: () -> Unit,
    waitForConfirmation: Boolean
): Boolean {
    val settings = VoiceAssistantSettings.get(this)
    val remoteExplicitlySelected = settings.recognitionPreference == VoiceRecognitionPreference.REMOTE_NODE
    if ((!runSecondPass && !remoteExplicitlySelected) || pcmSnapshot.isEmpty()) return false
    val localProfile = accurateProfileId
        ?.takeIf(String::isNotBlank)
        ?.let { runCatching { WhisperModelManager.model(it) }.getOrNull() }
    val localAvailable = localProfile?.let { WhisperModelManager.isAvailable(this, it) } == true
    val remoteFeatureEnabled = VoiceFeatureFlags.isRemoteWhisperNodeEnabled(this)
    val remoteNode = if (remoteFeatureEnabled && settings.remoteWhisperAllowed) {
        RemoteWhisperNodeRegistry.best(this)
    } else {
        null
    }
    val useRemote = RemoteWhisperRoutingPolicy.shouldUseRemote(
        preference = settings.recognitionPreference,
        localAlwaysPreferred = settings.onlineAsrPrivacy.localAlwaysPreferred,
        explicitConsent = settings.remoteWhisperAllowed,
        featureEnabled = remoteFeatureEnabled,
        nodeAvailable = remoteNode != null,
        localAccurateAvailable = localAvailable,
        secondPassRequested = runSecondPass
    )
    if (remoteExplicitlySelected && remoteNode == null) return false
    if (!useRemote && (!runSecondPass || localProfile == null || !localAvailable)) return false
    val selectedProfileId = if (useRemote) remoteNode!!.activeProfile.id else localProfile!!.id
    val selectedProfileSha = if (useRemote) remoteNode!!.activeProfile.sha256 else localProfile!!.sha256
    val request = VoiceSecondPassRequest(
        sessionId = sessionId,
        pcm16 = pcmSnapshot,
        sampleRateHz = sampleRateHz,
        language = language,
        fast = fast,
        accurateProfileId = selectedProfileId,
        accurateModelSha256 = selectedProfileSha,
        mode = WhisperExecutionMode.SECOND_PASS
    )
    VoiceLatencyTelemetry.record(
        this,
        traceId,
        VoiceTraceEvents.SECOND_PASS_STARTED,
        mapOf(
            "model_profile_id" to selectedProfileId,
            "model_sha256" to selectedProfileSha,
            "execution_mode" to WhisperExecutionMode.SECOND_PASS.name.lowercase(Locale.ROOT),
            "execution_device" to if (useRemote) "desktop" else "phone"
        )
    )
    return voiceSecondPassCoordinator.schedule(
        scope = voiceAssistantScope,
        request = request,
        executionLedger = voiceExecutionLedger,
        decoder = { frozen ->
            if (useRemote) {
                remoteWhisperNodeClient.transcribe(
                    node = remoteNode!!,
                    clientId = GalaxySSICrypto.localGalaxySSIId(),
                    voiceSessionId = frozen.sessionId,
                    transcriptId = frozen.fast.transcriptId.ifBlank { frozen.sessionId },
                    pcm16 = frozen.pcm16,
                    sampleRateHz = frozen.sampleRateHz,
                    language = frozen.language,
                    fastRevision = fast.revision
                )
            } else {
                val outcome = LocalWhisperAsr.decodePcmWindow(
                    context = this@scheduleVoiceSecondPass,
                    pcm16 = frozen.pcm16,
                    sampleRateHz = frozen.sampleRateHz,
                    language = frozen.language,
                    mode = frozen.mode,
                    traceId = frozen.sessionId,
                    source = "audio_record_second_pass_pcm16",
                    modelProfileId = frozen.accurateProfileId
                )
                TranscriptHypothesis(
                    text = outcome.result.text,
                    revision = fast.revision + 1,
                    provider = "whisper.cpp",
                    modelProfileId = outcome.profileId,
                    confidence = whisperConfidence(outcome.result.segments.map { it.averageLogProb })
                )
            }
        },
        onResult = { result ->
            VoiceLatencyTelemetry.record(
                this,
                traceId,
                VoiceTraceEvents.SECOND_PASS_COMPLETED,
                mapOf(
                    "model_profile_id" to result.metadata.accurateProfileId,
                    "success" to "true",
                    "changed" to result.diff.changed.toString()
                )
            )
            runOnUiThread {
                handleVoiceSecondPassResult(
                    purpose = purpose,
                    traceId = traceId,
                    sessionId = sessionId,
                    risk = risk,
                    result = result,
                    onSuccess = onSuccess,
                    onFailure = onFailure,
                    waitForConfirmation = waitForConfirmation
                )
            }
        },
        onFailure = { error ->
            VoiceLatencyTelemetry.record(
                this,
                traceId,
                VoiceTraceEvents.SECOND_PASS_COMPLETED,
                mapOf(
                    "model_profile_id" to selectedProfileId,
                    "success" to "false",
                    "error_code" to error.javaClass.simpleName
                )
            )
            Log.w("GalaxySSIVoice", "Second pass failed safely session=$sessionId", error)
            if (waitForConfirmation) {
                runOnUiThread {
                    showVoiceRiskConfirmation(
                        purpose = purpose,
                        traceId = traceId,
                        sessionId = sessionId,
                        fast = fast,
                        accurate = null,
                        diff = null,
                        risk = risk,
                        onSuccess = onSuccess,
                        onFailure = onFailure
                    )
                }
            }
        }
    )
}

internal fun MainActivity.handleVoiceSecondPassResult(
    purpose: String,
    traceId: String,
    sessionId: String,
    risk: VoiceCommandRisk,
    result: VoiceSecondPassResult,
    onSuccess: (String) -> Unit,
    onFailure: () -> Unit,
    waitForConfirmation: Boolean
) {
    val coordinatorSessionId = voiceCoordinatorSession(traceId)
    if (coordinatorSessionId.isNotBlank()) {
        dispatchVoiceCoordinator(
            VoiceInteractionEvent.TranscriptCorrected(
                coordinatorSessionId,
                result.metadata.fast,
                result.accurate
            )
        )
    }
    persistVoiceCorrection(traceId, sessionId, risk, result)
    if (waitForConfirmation) {
        showVoiceRiskConfirmation(
            purpose = purpose,
            traceId = traceId,
            sessionId = sessionId,
            fast = result.metadata.fast,
            accurate = result.accurate,
            diff = result.diff,
            risk = risk,
            onSuccess = onSuccess,
            onFailure = onFailure
        )
        return
    }
    if (result.decision is CorrectionDecision.WarnUser) {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.voice_correction_warning_title))
            .setMessage(
                getString(
                    R.string.voice_correction_warning_message,
                    result.diff.fastText,
                    result.diff.accurateText
                )
            )
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }
}

internal fun MainActivity.whisperConfidence(averageLogProbabilities: List<Float>): Float? =
    averageLogProbabilities
        .filterNot(Float::isNaN)
        .takeIf { values -> values.isNotEmpty() }
        ?.map { value -> kotlin.math.exp(value.toDouble()) }
        ?.average()
        ?.toFloat()
        ?.coerceIn(0f, 1f)

internal fun MainActivity.persistVoiceCorrection(
    traceId: String,
    sessionId: String,
    risk: VoiceCommandRisk,
    result: VoiceSecondPassResult
) {
    if (!result.diff.changed) return
    val turnContext = voiceTurnContextsByTraceId[traceId]
    val executionRecord = voiceExecutionLedger.snapshot(sessionId)
    voiceCorrectionJournal.append(
        VoiceCorrectionContextRecord(
            sessionId = sessionId,
            conversationId = turnContext?.conversationId.orEmpty(),
            turnId = turnContext?.turnId.orEmpty(),
            fastText = result.diff.fastText,
            accurateText = result.diff.accurateText,
            diffSummary = result.diff.compactSummary(),
            risk = risk,
            revision = result.accurate.revision,
            modelProfileId = result.metadata.accurateProfileId,
            modelSha256 = result.metadata.accurateModelSha256,
            executionMode = result.metadata.mode.name,
            userEdited = executionRecord?.userEdited == true,
            completedAtMillis = result.completedAtMillis
        )
    )
}

internal fun MainActivity.showVoiceRiskConfirmation(
    purpose: String,
    traceId: String,
    sessionId: String,
    fast: TranscriptHypothesis,
    accurate: TranscriptHypothesis?,
    diff: TranscriptDiff?,
    risk: VoiceCommandRisk,
    onSuccess: (String) -> Unit,
    onFailure: () -> Unit
) {
    if (isFinishing || isDestroyed || voiceExecutionLedger.snapshot(sessionId)?.primaryDispatchClaimed == true) {
        return
    }
    val chosen = accurate?.takeIf { it.text.isNotBlank() } ?: fast
    val message = if (accurate == null) {
        getString(
            R.string.voice_correction_confirmation_single_message,
            fast.text,
            voiceCommandRiskLabel(risk)
        )
    } else {
        getString(
            R.string.voice_correction_confirmation_comparison_message,
            fast.text,
            accurate.text,
            diff?.let(::formatVoiceTranscriptDiff).orEmpty(),
            voiceCommandRiskLabel(risk)
        )
    }
    val cancelPending = {
        cancelVoiceBeforeExecution(purpose, traceId, onFailure)
        Unit
    }
    voiceRiskConfirmationCancellation?.invoke()
    voiceRiskConfirmationCancellation = null
    voiceRiskConfirmationDialog?.dismiss()
    voiceRiskConfirmationDialog = null
    voiceRiskConfirmationCancellation = cancelPending
    val builder = AlertDialog.Builder(this)
        .setTitle(getString(R.string.voice_correction_confirmation_title))
        .setMessage(message)
        .setPositiveButton(getString(R.string.voice_correction_confirmation_execute)) { _, _ ->
            voiceRiskConfirmationCancellation = null
            routeVoiceTranscriptOnce(traceId, sessionId, chosen, onSuccess)
        }
        .setNegativeButton(android.R.string.cancel) { _, _ ->
            voiceRiskConfirmationCancellation = null
            cancelPending()
        }
    if (purpose == "agent_input") {
        builder.setNeutralButton(getString(R.string.voice_correction_edit)) { _, _ ->
            voiceRiskConfirmationCancellation = null
            voiceExecutionLedger.markUserEdited(sessionId)
            voiceCorrectionJournal.markUserEdited(sessionId)
            agentGoalInput.setText(
                AgentVoiceTranscriptPolicy.mergeDraftWithTranscript(
                    agentGoalInput.text?.toString().orEmpty(),
                    chosen.text
                )
            )
            agentGoalInput.setSelection(agentGoalInput.text?.length ?: 0)
            cancelVoiceBeforeExecution(purpose, traceId, onFailure = {})
        }
    }
    builder.setOnCancelListener {
        voiceRiskConfirmationCancellation = null
        cancelPending()
    }
    voiceRiskConfirmationDialog = builder.create().also { dialog ->
        dialog.setOnDismissListener {
            if (voiceRiskConfirmationDialog === dialog) {
                voiceRiskConfirmationDialog = null
                voiceRiskConfirmationCancellation = null
            }
        }
        dialog.show()
    }
}

internal fun MainActivity.voiceCommandRiskLabel(risk: VoiceCommandRisk): String = getString(
    when (risk) {
        VoiceCommandRisk.CONVERSATION -> R.string.voice_risk_conversation
        VoiceCommandRisk.LOW -> R.string.voice_risk_low
        VoiceCommandRisk.MEDIUM -> R.string.voice_risk_medium
        VoiceCommandRisk.HIGH -> R.string.voice_risk_high
        VoiceCommandRisk.CRITICAL -> R.string.voice_risk_critical
    }
)

internal fun MainActivity.formatVoiceTranscriptDiff(diff: TranscriptDiff): String {
    if (diff.entityDifferences.isEmpty()) return getString(R.string.voice_correction_wording_changed)
    return diff.entityDifferences.joinToString("\n") { difference ->
        val label = getString(
            when (difference.type) {
                VoiceEntityType.RECIPIENT -> R.string.voice_entity_recipient
                VoiceEntityType.PHONE_NUMBER -> R.string.voice_entity_phone
                VoiceEntityType.AMOUNT -> R.string.voice_entity_amount
                VoiceEntityType.DATE_TIME -> R.string.voice_entity_date_time
                VoiceEntityType.FILE_PATH -> R.string.voice_entity_file_path
                VoiceEntityType.APPLICATION -> R.string.voice_entity_application
                VoiceEntityType.DEVICE -> R.string.voice_entity_device
                VoiceEntityType.NEGATION -> R.string.voice_entity_negation
                VoiceEntityType.ACTION -> R.string.voice_entity_action
            }
        )
        "$label: ${difference.fastValues.joinToString("|").ifBlank { "-" }} -> " +
            difference.accurateValues.joinToString("|").ifBlank { "-" }
    }
}

internal fun MainActivity.routeVoiceTranscriptOnce(
    traceId: String,
    sessionId: String,
    transcript: TranscriptHypothesis,
    onSuccess: (String) -> Unit
): Boolean {
    if (transcript.text.isBlank()) return false
    if (!acceptVoiceCoordinatorFinal(traceId, transcript)) return false
    if (!voiceExecutionLedger.claimPrimaryDispatch(sessionId)) {
        Log.i("GalaxySSIVoice", "Duplicate primary voice dispatch ignored session=$sessionId")
        return false
    }
    onSuccess(transcript.text.trim())
    return true
}

internal fun MainActivity.cancelVoiceBeforeExecution(purpose: String, traceId: String, onFailure: () -> Unit) {
    val coordinatorSessionId = voiceCoordinatorSession(traceId)
    if (coordinatorSessionId.isNotBlank()) {
        dispatchVoiceCoordinator(VoiceInteractionEvent.Cancelled(coordinatorSessionId, "voice_confirmation_cancelled"))
    }
    onFailure()
    if (purpose == "voice_agent" && voiceAssistantAwake) scheduleVoiceRestart(500L)
}

internal fun MainActivity.localWhisperAdmission(profile: WhisperModel) = voiceReliabilityController.admit(
    workload = VoiceWorkloadProfile(
        feature = VoicePipelineFeature.LOCAL_WHISPER_REALTIME,
        profileId = profile.id,
        estimatedIncrementalMemoryBytes = WhisperMemoryAdmissionPolicy.estimatedIncrementalBytes(
            profile = profile,
            alreadyLoaded = WhisperModelManager.isLoaded(profile)
        ),
        certifiedPeakPssBytes = WhisperBenchmarkManager.current(this, profile)?.certification?.peakPssBytes ?: 0L,
        localInference = true,
        highMemoryLocalModel = profile.family in setOf(
            WhisperModelFamily.MEDIUM,
            WhisperModelFamily.LARGE_V3,
            WhisperModelFamily.LARGE_V3_TURBO
        )
    ),
    requestedEnabled = true,
    deviceCertified = true,
    foreground = true
)

internal fun MainActivity.selectWhisperMemoryFallback(
    failedProfile: WhisperModel,
    excludedProfileIds: Set<String> = emptySet()
): WhisperModel? = WhisperModelFallbackPolicy.select(
    requested = failedProfile,
    installedProfiles = WhisperModelManager.models.filter { candidate ->
        candidate.id !in excludedProfileIds && WhisperModelManager.isAvailable(this, candidate)
    },
    canRun = { candidate ->
        !VoiceFeatureFlags.isReliabilityGovernorEnabled(this) || localWhisperAdmission(candidate).allowed
    }
)

internal fun MainActivity.selectWhisperQnnRescue(): WhisperModel? = WhisperModelFallbackPolicy.selectRealtimeRescue(
    installedProfiles = WhisperModelManager.models.filter { candidate ->
        WhisperModelManager.isAvailable(this, candidate)
    },
    canRun = { candidate ->
        !VoiceFeatureFlags.isReliabilityGovernorEnabled(this) || localWhisperAdmission(candidate).allowed
    }
)

internal fun MainActivity.isWhisperMemoryAdmissionFailure(reasonCode: String): Boolean =
    reasonCode in setOf("low_memory_signal", "insufficient_memory_headroom")

internal fun MainActivity.isWhisperMemoryFailure(error: Throwable?): Boolean = generateSequence(error) { it.cause }
    .any { cause ->
        cause is OutOfMemoryError ||
            (cause as? LocalWhisperException)?.code == NativeWhisperCode.OUT_OF_MEMORY ||
            cause.message.orEmpty().lowercase().let { message ->
                "out of memory" in message || "not enough memory" in message ||
                    "more available memory" in message || "memory headroom" in message
            }
    }

internal fun MainActivity.transcribeLocally(
    sourceFile: File,
    traceId: String = "",
    pcmSamples: ShortArray? = null,
    sampleRateHz: Int = 16_000,
    purpose: String = "voice",
    onSuccess: (String) -> Unit,
    onFailure: () -> Unit = {}
) {
    val language = LanguagePolicySettings.resolvedAsrLanguage(this)
    val highAccuracyFinal = highAccuracyAsrFinals.remove(traceId)
    if (highAccuracyFinal != null && pcmSamples != null && highAccuracyFinal.text.isNotBlank()) {
        sourceFile.delete()
        val pcmCopy = pcmSamples.copyOf()
        VoiceLatencyTelemetry.record(
            this,
            traceId,
            VoiceTraceEvents.ASR_FINAL_RECEIVED,
            mapOf(
                "asr_provider" to "qnn_htp",
                "model_profile_id" to highAccuracyFinal.modelProfileId,
                "duration_ms" to highAccuracyFinal.inferenceMs.toString(),
                "success" to "true"
            ),
            once = true
        )
        runOnUiThread {
            handleVoiceFastTranscript(
                purpose = purpose,
                traceId = traceId,
                transcript = highAccuracyFinal.text,
                pcmSnapshot = pcmCopy,
                sampleRateHz = sampleRateHz,
                language = language,
                decodedModelProfileId = highAccuracyFinal.modelProfileId,
                confidence = null,
                providerId = "qnn_htp",
                onSuccess = onSuccess,
                onFailure = onFailure
            )
        }
        return
    }
    val onlineFinal = onlineRealtimeAsrFinals.remove(traceId)
    if (onlineFinal != null && pcmSamples != null) {
        sourceFile.delete()
        val pcmCopy = pcmSamples.copyOf()
        runOnUiThread {
            handleVoiceFastTranscript(
                purpose = purpose,
                traceId = traceId,
                transcript = onlineFinal.text,
                pcmSnapshot = pcmCopy,
                sampleRateHz = sampleRateHz,
                language = language,
                decodedModelProfileId = onlineFinal.modelProfileId,
                confidence = onlineFinal.confidence,
                providerId = onlineFinal.providerId,
                onSuccess = onSuccess,
                onFailure = onFailure
            )
        }
        return
    }
    val requestedLocalProfile = WhisperModelManager.model(VoiceAssistantSettings.get(this).asrModel)
    val qnnPreparationStatus = highAccuracyAsrController.preparationStatus.value
    val qnnFallback = if (isHighAccuracyQnnSelected()) {
        selectWhisperQnnRescue()
    } else null
    var selectedLocalProfile = qnnFallback ?: requestedLocalProfile
    var fallbackFromProfileId: String? = qnnFallback?.let { requestedLocalProfile.id }
    var fallbackReasonCode = qnnFallback?.let { qnnPreparationStatus.reasonCode }.orEmpty()
    if (qnnFallback != null) {
        Log.w(
            "GalaxySSILocalASR",
            "QNN transcription fallback reason=${qnnPreparationStatus.reasonCode} " +
                "from=${requestedLocalProfile.id} to=${qnnFallback.id}"
        )
    }
    if (VoiceFeatureFlags.isReliabilityGovernorEnabled(this)) {
        val admission = localWhisperAdmission(selectedLocalProfile)
        if (!admission.allowed) {
            val fallback = if (isWhisperMemoryAdmissionFailure(admission.fallbackReasonCode)) {
                selectWhisperMemoryFallback(selectedLocalProfile)
            } else null
            if (fallback != null) {
                fallbackFromProfileId = selectedLocalProfile.id
                fallbackReasonCode = admission.fallbackReasonCode
                selectedLocalProfile = fallback
                Log.w(
                    "GalaxySSILocalASR",
                    "Local transcription memory fallback from=${requestedLocalProfile.id} to=${fallback.id}"
                )
            } else {
                sourceFile.delete()
                Log.w(
                    "GalaxySSILocalASR",
                    "Local transcription gated reason=${admission.fallbackReasonCode} profile=${selectedLocalProfile.id}"
                )
                failVoiceCoordinator(traceId, admission.fallbackReasonCode.ifBlank { "local_asr_gated" })
                VoiceLatencyTelemetry.record(
                    this,
                    traceId,
                    VoiceTraceEvents.ASR_FINAL_FAILED,
                    mapOf(
                        "asr_provider" to "whisper.cpp",
                        "model_profile_id" to selectedLocalProfile.id,
                        "error_code" to admission.fallbackReasonCode.ifBlank { "local_asr_gated" },
                        "fallback" to "false"
                    ),
                    once = true
                )
                if (isWhisperMemoryAdmissionFailure(admission.fallbackReasonCode)) {
                    Toast.makeText(
                        this,
                        getString(R.string.voice_asr_model_memory_insufficient, selectedLocalProfile.displayName),
                        Toast.LENGTH_LONG
                    ).show()
                }
                onFailure()
                return
            }
        }
    }
    VoiceRuntimeHealthRegistry.begin(
        VoiceRuntimeChannel.LOCAL_WHISPER_ASR
    )
    voiceAssistantScope.launch {
        var preparedPcm: ShortArray? = null
        val result = runCatching {
            val finalPcm = if (pcmSamples != null) {
                pcmSamples.copyOf()
            } else {
                val decodeStartedAt = SystemClock.elapsedRealtime()
                VoiceLatencyTelemetry.record(
                    this@transcribeLocally,
                    traceId,
                    VoiceTraceEvents.ASR_DECODE_STARTED,
                    mapOf("audio_source" to "compatibility_file")
                )
                LocalWhisperAsr.decodeAudioFileToPcm16(sourceFile).also { decoded ->
                    VoiceLatencyTelemetry.record(
                        this@transcribeLocally,
                        traceId,
                        VoiceTraceEvents.ASR_DECODE_COMPLETED,
                        mapOf(
                            "audio_source" to "compatibility_file",
                            "duration_ms" to (SystemClock.elapsedRealtime() - decodeStartedAt).toString(),
                            "audio_duration_ms" to (decoded.size.toLong() * 1_000L / sampleRateHz).toString()
                        )
                    )
                }
            }
            preparedPcm = finalPcm
            var activeProfile = selectedLocalProfile
            var memoryFallbackFromProfileId = fallbackFromProfileId
            val attemptedProfiles = linkedSetOf<String>()
            val liveSession = liveWhisperSessions[traceId]?.takeIf { session ->
                memoryFallbackFromProfileId == null && session.modelProfileId == activeProfile.id
            }
            val decoded = if (liveSession != null) {
                VoiceLatencyTelemetry.record(
                    this@transcribeLocally,
                    traceId,
                    VoiceTraceEvents.ASR_FINAL_STARTED,
                    mapOf(
                        "asr_provider" to "whisper.cpp",
                        "audio_source" to "audio_record_pcm16",
                        "audio_duration_ms" to (finalPcm.size.toLong() * 1_000L / sampleRateHz).toString()
                    ),
                    once = true
                )
                val native = liveSession.finish(
                    PcmSnapshot(
                        samples = finalPcm,
                        sampleRateHz = sampleRateHz,
                        speechDetected = true,
                        speechStartSample = 0L,
                        speechEndSampleExclusive = finalPcm.size.toLong(),
                        captureStartSample = 0L,
                        captureEndSampleExclusive = finalPcm.size.toLong()
                    )
                )
                VoiceLatencyTelemetry.record(
                    this@transcribeLocally,
                    traceId,
                    VoiceTraceEvents.ASR_FINAL_RECEIVED,
                    mapOf(
                        "asr_provider" to "whisper.cpp",
                        "model_profile_id" to liveSession.modelProfileId,
                        "success" to "true"
                    ),
                    once = true
                )
                Triple(
                    native.text,
                    liveSession.modelProfileId,
                    whisperConfidence(native.segments.map { it.averageLogProb })
                )
            } else {
                var outcome: LocalWhisperTranscriptionOutcome? = null
                while (outcome == null) {
                    attemptedProfiles += activeProfile.id
                    try {
                        outcome = LocalWhisperAsr.transcribePcmOutcome(
                            this@transcribeLocally,
                            finalPcm,
                            sampleRateHz,
                            language,
                            traceId,
                            source = if (pcmSamples != null) "audio_record_pcm16" else "compatibility_file",
                            requestedProfileIdOverride = activeProfile.id
                        )
                    } catch (error: Throwable) {
                        if (!isWhisperMemoryFailure(error)) throw error
                        if (VoiceFeatureFlags.isReliabilityGovernorEnabled(this@transcribeLocally)) {
                            voiceReliabilityController.reportFailure(
                                VoicePipelineFeature.LOCAL_WHISPER_REALTIME,
                                activeProfile.id,
                                error,
                                "out_of_memory"
                            )
                        }
                        val fallback = selectWhisperMemoryFallback(activeProfile, attemptedProfiles)
                            ?: throw error
                        if (memoryFallbackFromProfileId == null) {
                            memoryFallbackFromProfileId = activeProfile.id
                        }
                        fallbackReasonCode = "qnn_out_of_memory"
                        Log.w(
                            "GalaxySSILocalASR",
                            "Whisper memory fallback from=${activeProfile.id} to=${fallback.id}",
                            error
                        )
                        activeProfile = fallback
                    }
                }
                val completed = requireNotNull(outcome)
                Triple(completed.text, completed.profileId, completed.confidence)
            }
            VoiceFastDecodeResult(
                text = decoded.first,
                pcm16 = finalPcm,
                modelProfileId = decoded.second,
                confidence = decoded.third,
                fallbackFromProfileId = memoryFallbackFromProfileId,
                fallbackReasonCode = fallbackReasonCode
            )
        }
        closeLiveWhisperSession(traceId)
        sourceFile.delete()
        runOnUiThread {
            val decoded = result.getOrNull()
            val transcript = decoded?.text.orEmpty().trim()
            if (transcript.isNotBlank()) {
                val completed = requireNotNull(decoded)
                VoiceRuntimeHealthRegistry.success(
                    VoiceRuntimeChannel.LOCAL_WHISPER_ASR
                )
                if (VoiceFeatureFlags.isReliabilityGovernorEnabled(this@transcribeLocally)) {
                    voiceReliabilityController.reportSuccess(
                        VoicePipelineFeature.LOCAL_WHISPER_REALTIME,
                        completed.modelProfileId
                    )
                }
                completed.fallbackFromProfileId
                    ?.takeIf { isWhisperMemoryAdmissionFailure(completed.fallbackReasonCode) ||
                        completed.fallbackReasonCode == "qnn_out_of_memory" }
                    ?.let { fallbackFromId ->
                    val fallbackFrom = WhisperModelManager.model(fallbackFromId)
                    val fallbackTo = WhisperModelManager.model(completed.modelProfileId)
                    Toast.makeText(
                        this@transcribeLocally,
                        getString(
                            R.string.voice_asr_model_memory_fallback,
                            fallbackFrom.displayName,
                            fallbackTo.displayName
                        ),
                        Toast.LENGTH_LONG
                    ).show()
                }
                handleVoiceFastTranscript(
                    purpose = purpose,
                    traceId = traceId,
                    transcript = transcript,
                    pcmSnapshot = completed.pcm16,
                    sampleRateHz = sampleRateHz,
                    language = language,
                    decodedModelProfileId = completed.modelProfileId,
                    confidence = completed.confidence,
                    onSuccess = onSuccess,
                    onFailure = onFailure
                )
            } else {
                preparedPcm?.wipeSensitive()
                val transcriptionError = result.exceptionOrNull()
                VoiceRuntimeHealthRegistry.failure(
                    VoiceRuntimeChannel.LOCAL_WHISPER_ASR,
                    transcriptionError?.message
                        ?: getString(R.string.voice_status_transcription_failed)
                )
                if (VoiceFeatureFlags.isReliabilityGovernorEnabled(this@transcribeLocally)) {
                    voiceReliabilityController.reportFailure(
                        VoicePipelineFeature.LOCAL_WHISPER_REALTIME,
                        WhisperModelManager.model(VoiceAssistantSettings.get(this@transcribeLocally).asrModel).id,
                        transcriptionError,
                        transcriptionError?.javaClass?.simpleName ?: "empty_transcript"
                    )
                }
                Log.e("GalaxySSILocalASR", "Local transcription failed", transcriptionError)
                Toast.makeText(
                    this@transcribeLocally,
                    if (isWhisperMemoryFailure(transcriptionError)) {
                        getString(
                            R.string.voice_asr_model_memory_insufficient,
                            requestedLocalProfile.displayName
                        )
                    } else {
                        transcriptionError?.message ?: getString(R.string.voice_status_transcription_failed)
                    },
                    Toast.LENGTH_LONG
                ).show()
                onFailure()
                failVoiceCoordinator(
                    traceId,
                    transcriptionError?.javaClass?.simpleName ?: "empty_transcript"
                )
                VoiceLatencyTelemetry.record(
                    this@transcribeLocally,
                    traceId,
                    VoiceTraceEvents.SESSION_FAILED,
                    mapOf("error_code" to transcriptionError?.javaClass?.simpleName.orEmpty()),
                    once = true
                )
            }
        }
    }
}

internal fun MainActivity.playVoiceMessage(msgId: Long) {
    messages.values.asSequence().flatten().firstOrNull { it.id == msgId }
        ?.attachments?.firstOrNull { it.mimeType.startsWith("audio/") }
        ?.let {
            playPeerAudioAttachment(it)
            return
        }
    val voiceFile = listOf("opus", "wav", "m4a")
        .map { File(cacheDir, "voices/msg_${msgId}.$it") }
        .firstOrNull(File::exists)
        ?: File(cacheDir, "voices/msg_${msgId}.wav")
    if (!voiceFile.exists()) {
        Toast.makeText(this, getString(R.string.voice_file_missing), Toast.LENGTH_SHORT).show()
        return
    }
    player?.let {
        if (it.isPlaying) {
            it.stop()
            it.release()
            player = null
            return
        }
        it.release()
        player = null
    }
    var candidate: android.media.MediaPlayer? = null
    try {
        val activePlayer = android.media.MediaPlayer()
        candidate = activePlayer
        activePlayer.apply {
            setAudioAttributes(PeerVoiceMessageAudio.playbackAttributes())
            setDataSource(voiceFile.absolutePath)
            prepare()
            setOnCompletionListener { completed ->
                completed.release()
                if (player === completed) player = null
            }
            start()
        }
        player = candidate
    } catch (e: Exception) {
        candidate?.let { failedPlayer ->
            runCatching { failedPlayer.release() }
        }
        Toast.makeText(this, getString(R.string.toast_send_failed, e.message ?: ""), Toast.LENGTH_SHORT).show()
    }
}

internal fun MainActivity.createRecorder() = MediaRecorder()

// ===== Message Management =====
