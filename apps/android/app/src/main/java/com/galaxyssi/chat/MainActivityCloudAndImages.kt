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

internal fun MainActivity.runDebugBackupRoundtrip(token: String) {
    if ((applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) == 0) return
    val prefs = getSharedPreferences("galaxyssi_debug", Context.MODE_PRIVATE)
    historyExecutor.execute {
        val originalHistory = ChatHistoryStore.readAll(this)
        val password = "GalaxySSIBackupSmoke2026"
        val contactToken = "backup-smoke-${token.lowercase(Locale.US).replace(Regex("[^a-z0-9_-]+"), "-")}"
        val messageToken = "BACKUP_ROUNDTRIP_MESSAGE_$token"
        runCatching {
            val contact = AppStore.addCloudModelContact(
                this,
                displayName = "Backup Smoke Model",
                provider = "Backup Smoke",
                modelId = contactToken,
                endpoint = "http://127.0.0.1:9/v1/chat/completions",
                apiKey = "backup-smoke-key",
                apiStyle = "openai"
            )
            val contactId = contact.optString("id")
            val message = JSONObject()
                .put("id", System.currentTimeMillis())
                .put("content", messageToken)
                .put("isMine", false)
                .put("contactId", contactId)
                .put("isSystem", false)
                .put("timestamp", System.currentTimeMillis())
                .put("deliveryStatus", "")
                .put("deliveryTrace", JSONArray().put(JSONObject()
                    .put("stage", "backup_roundtrip_seed")
                    .put("at", System.currentTimeMillis())
                    .put("detail", "debug")))
            val historyRoot = JSONObject()
            historyRoot.put(contactId, JSONArray().put(message))
            ChatHistoryStore.replaceAll(this, historyRoot)

            val backup = AppStore.exportBackup(
                this,
                password = password,
                includeContacts = true,
                includeMessages = true
            )
            val backupText = backup.readText(Charsets.UTF_8)
            AgentEncryptedPreferences(this, "galaxyssi_app_store").apply {
                writeString("contacts", JSONArray().toString())
                writeString("friend_requests", JSONArray().toString())
            }
            ChatHistoryStore.replaceAll(this, JSONObject())

            AppStore.importBackup(this, backup, password, includeMessages = true)
            val restoredContacts = AppStore.contacts(this).toString()
            val restoredHistory = ChatHistoryStore.readAll(this).toString()
            val contactRestored = restoredContacts.contains(contactToken) && restoredContacts.contains("Backup Smoke")
            val messageRestored = restoredHistory.contains(messageToken)
            backup.delete()
            val ok = backupText.contains("\"type\":\"galaxyssi_backup\"") && contactRestored && messageRestored
            prefs.edit()
                .putString("backup_roundtrip_result", JSONObject()
                    .put("ok", ok)
                    .put("token", token)
                    .put("contact_id", contactId)
                    .put("contact_restored", contactRestored)
                    .put("message_restored", messageRestored)
                    .put("encrypted_backup", backupText.contains("\"cipher\":\"aes-256-gcm\""))
                    .toString())
                .commit()
        }.getOrElse { error ->
            prefs.edit()
                .putString("backup_roundtrip_result", JSONObject()
                    .put("ok", false)
                    .put("token", token)
                    .put("error", error.message ?: error.javaClass.simpleName)
                    .toString())
                .commit()
        }
        runCatching {
            ChatHistoryStore.replaceAll(this, originalHistory)
        }.onFailure { error ->
            prefs.edit()
                .putString("backup_roundtrip_result", JSONObject()
                    .put("ok", false)
                    .put("token", token)
                    .put("error", "history_restore_failed:${error.message ?: error.javaClass.simpleName}")
                    .toString())
                .commit()
        }
        handler.post {
            loadChatHistory()
            refreshDirectoryContacts()
        }
    }
}

internal fun MainActivity.runDebugChatHistoryProbe(encodedRequest: String) {
    if ((applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) == 0) return
    val prefs = getSharedPreferences("galaxyssi_debug", Context.MODE_PRIVATE)
    historyExecutor.execute {
        var requestId = ""
        val result = runCatching {
            val decoded = String(Base64.decode(encodedRequest, Base64.DEFAULT), Charsets.UTF_8)
            val request = JSONObject(decoded)
            requestId = request.optString("request_id")
            DebugChatHistoryProbe.run(this, request)
        }.getOrElse { error ->
            JSONObject()
                .put("request_id", requestId)
                .put("storage", "encrypted_sqlite")
                .put("error", error.message ?: error.javaClass.simpleName)
        }
        prefs.edit()
            .putString("chat_history_probe_result", result.toString())
            .commit()
    }
}

internal fun MainActivity.runDebugSecureStateProbe(encodedRequest: String) {
    if ((applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) == 0) return
    val prefs = getSharedPreferences("galaxyssi_debug", Context.MODE_PRIVATE)
    var requestId = ""
    val result = runCatching {
        val decoded = String(Base64.decode(encodedRequest, Base64.DEFAULT), Charsets.UTF_8)
        val request = JSONObject(decoded)
        requestId = request.optString("request_id")
        DebugSecureStateProbe.run(this, request)
    }.getOrElse { error ->
        JSONObject()
            .put("request_id", requestId)
            .put("ok", false)
            .put("storage", "android-keystore-aes-gcm")
            .put("error", error.message ?: error.javaClass.simpleName)
    }
    prefs.edit()
        .putString("secure_state_probe_result", result.toString())
        .commit()
    refreshDirectoryContacts()
}

internal fun MainActivity.runDebugCloudModelsRoundtrip(token: String) {
    if ((applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) == 0) return
    val prefs = getSharedPreferences("galaxyssi_debug", Context.MODE_PRIVATE)
    runCatching {
        val deepseekContact = debugSeedCloudProvider("DeepSeek")
            ?: throw IllegalStateException("DeepSeek provider was not seeded")
        val openAiContact = debugSeedCloudProvider("OpenAI")
            ?: throw IllegalStateException("OpenAI provider was not seeded")
        val deepseekSecondModel = modelsForProvider("DeepSeek").getOrNull(1)
            ?: throw IllegalStateException("DeepSeek second model is missing")
        val switched = AppStore.setSelectedCloudModel(this, deepseekContact.id, deepseekSecondModel.modelId)
        val deepseekRaw = AppStore.contactById(this, deepseekContact.id)
            ?: throw IllegalStateException("DeepSeek contact is missing")
        val openAiRaw = AppStore.contactById(this, openAiContact.id)
            ?: throw IllegalStateException("OpenAI contact is missing")
        val contacts = AppStore.contacts(this)
        var cloudProviderContacts = 0
        var desktopCloudPresent = false
        for (i in 0 until contacts.length()) {
            val contact = contacts.optJSONObject(i) ?: continue
            if (contact.optString("delivery_mode") == "cloud_api") cloudProviderContacts += 1
            if (contact.optString("agent_id") == "cloud-model" || contact.optString("agent_kind") == "cloud-model") {
                desktopCloudPresent = true
            }
        }
        val deepseekModels = deepseekRaw.optJSONArray("cloud_models") ?: JSONArray()
        val openAiModels = openAiRaw.optJSONArray("cloud_models") ?: JSONArray()
        val ok = switched &&
            deepseekRaw.optString("name") == "DeepSeek" &&
            openAiRaw.optString("name") == "OpenAI" &&
            deepseekModels.length() >= 2 &&
            openAiModels.length() >= 2 &&
            deepseekRaw.optString("selected_cloud_model") == deepseekSecondModel.modelId &&
            deepseekRaw.optString("cloud_model") == deepseekSecondModel.modelId &&
            !desktopCloudPresent
        prefs.edit()
            .putString("cloud_models_roundtrip_result", JSONObject()
                .put("ok", ok)
                .put("token", token)
                .put("deepseek_contact_id", deepseekContact.id)
                .put("openai_contact_id", openAiContact.id)
                .put("deepseek_name", deepseekRaw.optString("name"))
                .put("openai_name", openAiRaw.optString("name"))
                .put("deepseek_model_count", deepseekModels.length())
                .put("openai_model_count", openAiModels.length())
                .put("selected_model", deepseekRaw.optString("selected_cloud_model"))
                .put("cloud_provider_contacts", cloudProviderContacts)
                .put("desktop_cloud_present", desktopCloudPresent)
                .toString())
            .commit()
        refreshDirectoryContacts()
        showChatPage(Contact(deepseekContact.id, deepseekRaw.optString("name", "DeepSeek"), ""))
    }.getOrElse { error ->
        prefs.edit()
            .putString("cloud_models_roundtrip_result", JSONObject()
                .put("ok", false)
                .put("token", token)
                .put("error", error.message ?: error.javaClass.simpleName)
                .toString())
            .commit()
    }
}

internal fun MainActivity.runDebugVoiceSettingsRoundtrip(token: String) {
    if ((applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) == 0) return
    val prefs = getSharedPreferences("galaxyssi_debug", Context.MODE_PRIVATE)
    runCatching {
        val desktopId = "desktop_voice_settings_smoke"
        val pairingSecret = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(
            ByteArray(32) { index -> (index + 1).toByte() }
        )
        val pairing = JSONObject()
            .put("type", "opaque_pairing")
            .put("version", GalaxySSILinkProtocol.VERSION)
            .put("desktop_id", desktopId)
            .put("desktop_name", "VOICE-PC")
            .put("identity_key_sha256", "ab".repeat(32))
            .put("pairing_topic", GalaxySSILinkProtocol.pairingTopic(pairingSecret))
            .put("pairing_token", UUID.randomUUID().toString().replace("-", "") + token.take(12))
            .put("pairing_secret", pairingSecret)
            .put(
                "pairing_access",
                JSONObject()
                    .put("contract_version", GalaxySSILinkProtocol.ACCESS_CONTRACT)
                    .put("version", 1)
                    .put("profile", GalaxySSILinkProtocol.ACCESS_RESTRICTED)
                    .put(
                        "scopes",
                        JSONArray(
                            listOf(
                                GalaxySSILinkProtocol.SCOPE_AGENT_CHAT,
                                GalaxySSILinkProtocol.SCOPE_EXPLICIT_ATTACHMENTS,
                                GalaxySSILinkProtocol.SCOPE_TASK_WORKSPACE
                            )
                        )
                    )
            )
            .put("created_at", System.currentTimeMillis())
        AppStore.markDesktopVerified(this, pairing)
        checkNotNull(GalaxySSILinkProtocol.markPaired(this, desktopId))
        val codexId = "$desktopId:codex"
        val welcome = "VOICE_SETTINGS_WELCOME_$token"
        VoiceAssistantSettings.setEnabled(this, true)
        VoiceAssistantSettings.setWakeProvider(this, VoiceAssistantSettings.WAKE_PROVIDER_ANDROID_ASR)
        VoiceAssistantSettings.setWakeModel(this, VoiceAssistantSettings.DEFAULT_WAKE_MODEL)
        VoiceAssistantSettings.setWakeThreshold(this, 0.73f)
        VoiceAssistantSettings.setAsrProvider(this, VoiceAssistantSettings.ASR_PROVIDER_LOCAL_WHISPER)
        VoiceAssistantSettings.setAsrRuntimeMode(this, WhisperUserVoiceMode.ACCURATE)
        VoiceAssistantSettings.setAsrLanguage(this, "en-US")
        VoiceAssistantSettings.setTtsProvider(this, VoiceAssistantSettings.PROVIDER_ANDROID)
        VoiceAssistantSettings.setTtsLanguage(this, "zh-TW")
        VoiceAssistantSettings.setResponseLanguage(this, "zh-HK")
        VoiceAssistantSettings.setMicrosoftVoice(this, "zh-CN-XiaoxiaoNeural")
        VoiceAssistantSettings.setWelcomeText(this, welcome)
        VoiceAssistantSettings.setSpeakReplies(this, false)
        VoiceAssistantSettings.setTargetContact(this, codexId)
        VoiceAssistantSettings.setRoutingMode(this, VoiceAssistantSettings.ROUTING_MODE_NATIVE_AGENT)
        val config = VoiceAssistantSettings.get(this)
        val resolvedTarget = resolveVoiceAssistantTargetContactId(config.targetContactId)
        val ok = config.enabled &&
            config.wakeProvider == VoiceAssistantSettings.WAKE_PROVIDER_ANDROID_ASR &&
            config.wakeWords == WakeWordPolicy.configuredWords &&
            config.wakeModel == VoiceAssistantSettings.DEFAULT_WAKE_MODEL &&
            kotlin.math.abs(config.wakeThreshold - 0.73f) < 0.001f &&
            config.asrProvider == VoiceAssistantSettings.ASR_PROVIDER_LOCAL_WHISPER &&
            config.asrRuntimeMode == WhisperUserVoiceMode.ACCURATE &&
            config.asrLanguage == "en-US" &&
            config.ttsProvider == VoiceAssistantSettings.PROVIDER_ANDROID &&
            config.ttsLanguage == "zh-TW" &&
            config.responseLanguage == "zh-HK" &&
            config.microsoftVoice == "zh-CN-XiaoxiaoNeural" &&
            config.welcomeText == welcome &&
            !config.speakReplies &&
            config.routingMode == VoiceAssistantSettings.ROUTING_MODE_NATIVE_AGENT &&
            config.targetContactId == codexId &&
            resolvedTarget == codexId
        prefs.edit()
            .putString("voice_settings_roundtrip_result", JSONObject()
                .put("ok", ok)
                .put("token", token)
                .put("enabled", config.enabled)
                .put("wake_provider", config.wakeProvider)
                .put("wake_word", WakeWordPolicy.WAKE_WORD)
                .put("wake_model", config.wakeModel)
                .put("wake_threshold", config.wakeThreshold.toDouble())
                .put("asr_provider", config.asrProvider)
                .put("asr_runtime_mode", config.asrRuntimeMode.name)
                .put("asr_language", config.asrLanguage)
                .put("tts_provider", config.ttsProvider)
                .put("tts_language", config.ttsLanguage)
                .put("response_language", config.responseLanguage)
                .put("microsoft_voice", config.microsoftVoice)
                .put("welcome_text", config.welcomeText)
                .put("speak_replies", config.speakReplies)
                .put("routing_mode", config.routingMode)
                .put("target_contact_id", config.targetContactId)
                .put("resolved_target_contact_id", resolvedTarget)
                .toString())
            .commit()
        refreshDirectoryContacts()
        showVoiceAssistantSettingsPage()
    }.getOrElse { error ->
        prefs.edit()
            .putString("voice_settings_roundtrip_result", JSONObject()
                .put("ok", false)
                .put("token", token)
                .put("error", error.message ?: error.javaClass.simpleName)
                .toString())
            .commit()
    }
}

internal fun MainActivity.runDebugControlCenterRoundtrip(token: String) {
    if ((applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) == 0) return
    val debugPreferences = getSharedPreferences("galaxyssi_debug", Context.MODE_PRIVATE)
    val safetyStore = SharedPreferencesAgentSafetySettingsStore(this)
    val plannerStore = AgentModelPlannerSettingsStore(this)
    val deviceStore = CustomDeviceConnectorStore(this)
    val originalSafety = safetyStore.load()
    val originalPlanner = plannerStore.load()
    val originalHomeAssistant = HomeAssistantSettingsStore.load(this)
    val originalDevices = deviceStore.exportJson()
    var settingsPersisted = false
    var devicesPersisted = false
    var errorMessage = ""
    try {
        val nextMode = PermissionMode.entries.first { it != originalSafety.permissionMode }
        val changedSafety = originalSafety.copy(
            permissionMode = nextMode,
            highRiskGuard = !originalSafety.highRiskGuard,
            memoryCapture = !originalSafety.memoryCapture,
            screenObservationAllowed = !originalSafety.screenObservationAllowed,
            localActionsAllowed = !originalSafety.localActionsAllowed,
            connectorCallsAllowed = !originalSafety.connectorCallsAllowed,
            deviceControlAllowed = !originalSafety.deviceControlAllowed,
            executionPaused = !originalSafety.executionPaused
        )
        val changedPlanner = originalPlanner.copy(
            enabled = !originalPlanner.enabled,
            shareScreenText = !originalPlanner.shareScreenText,
            maxActions = if (originalPlanner.maxActions == 12) 7 else 12,
            cloudContactId = "control-center-$token".take(120),
            dynamicReplanning = !originalPlanner.dynamicReplanning,
            maxReplans = if (originalPlanner.maxReplans == 5) 2 else 5,
            multiAgentCoordination = !originalPlanner.multiAgentCoordination,
            shareAgentOutputsWithPlanner = !originalPlanner.shareAgentOutputsWithPlanner,
            maxAgentHops = if (originalPlanner.maxAgentHops == 8) 3 else 8,
            maxToolCalls = if (originalPlanner.maxToolCalls == 32) 12 else 32
        )
        val changedHomeAssistant = HomeAssistantSettings(
            enabled = true,
            baseUrl = "https://control-center.invalid/",
            accessToken = "temporary-$token",
            defaultEntityId = "light.control_center_test"
        )
        val temporaryDevice = CustomDeviceConnector(
            id = "control-center-$token".take(80),
            name = "Control Center Test Device",
            transport = CustomDeviceTransport.HTTP_REST,
            endpoint = "https://device.invalid/api",
            commandTarget = "test",
            risk = AgentRisk.LOW
        )
        safetyStore.save(changedSafety)
        plannerStore.save(changedPlanner)
        HomeAssistantSettingsStore.save(this, changedHomeAssistant)
        deviceStore.upsert(temporaryDevice)
        settingsPersisted = safetyStore.load() == changedSafety &&
            plannerStore.load() == changedPlanner &&
            HomeAssistantSettingsStore.load(this) == changedHomeAssistant.copy(baseUrl = changedHomeAssistant.baseUrl.trimEnd('/'))
        devicesPersisted = deviceStore.find(temporaryDevice.id) == temporaryDevice
    } catch (error: Throwable) {
        errorMessage = error.message ?: error.javaClass.simpleName
    } finally {
        safetyStore.save(originalSafety)
        plannerStore.save(originalPlanner)
        HomeAssistantSettingsStore.save(this, originalHomeAssistant)
        deviceStore.restoreJson(originalDevices)
    }
    val restored = safetyStore.load() == originalSafety &&
        plannerStore.load() == originalPlanner &&
        HomeAssistantSettingsStore.load(this) == originalHomeAssistant.copy(baseUrl = originalHomeAssistant.baseUrl.trim().trimEnd('/')) &&
        deviceStore.exportJson().toString() == originalDevices.toString()
    debugPreferences.edit()
        .putString(
            "control_center_roundtrip_result",
            JSONObject()
                .put("ok", settingsPersisted && devicesPersisted && restored && errorMessage.isBlank())
                .put("token", token)
                .put("settings_persisted", settingsPersisted)
                .put("devices_persisted", devicesPersisted)
                .put("restored", restored)
                .put("error", errorMessage)
                .toString()
        )
        .commit()
    renderControlCenterHome()
}

internal fun MainActivity.scheduleDebugOutgoing(contactId: String, content: String, attempt: Int) {
    val key = "$contactId|$content"
    if (attempt == 0) {
        if (lastDebugSendKey == key) return
        lastDebugSendKey = key
    }
    val delayMs = if (attempt == 0) 300L else 1000L
    handler.postDelayed({
        val contact = contactById(contactId)
        val raw = AppStore.contactById(this, contact.id)
        if (raw?.optString("delivery_mode") == "cloud_api") {
            showChatPage(contact)
            sendOutgoingText(contact, content)
            Toast.makeText(this, getString(R.string.debug_message_sent_to, contact.name), Toast.LENGTH_SHORT).show()
            return@postDelayed
        }
        if (!GalaxySSIMqttClient.isConnected() || !GalaxySSIMqttClient.isSecureReady()) {
            if (attempt < 12) {
                scheduleDebugOutgoing(contactId, content, attempt + 1)
            } else {
                Toast.makeText(this, getString(R.string.debug_send_secure_channel_not_ready), Toast.LENGTH_SHORT).show()
            }
            return@postDelayed
        }
        sendOutgoingText(contact, content)
        Toast.makeText(this, getString(R.string.debug_message_sent_to, contact.name), Toast.LENGTH_SHORT).show()
    }, delayMs)
}

internal fun MainActivity.scheduleDebugAgentGoal(
    token: String,
    goal: String,
    newConversation: Boolean,
    attachment: DebugAgentAttachment? = null
) {
    handler.postDelayed({
        if (newConversation) createAgentConversation()
        showMainTab(PAGE_AGENT)
        val conversationId = agentTranscriptStore.activeConversation().id
        val startedAt = System.currentTimeMillis()
        getSharedPreferences(DEBUG_AGENT_PREFS, Context.MODE_PRIVATE).edit()
            .putString(token, JSONObject()
                .put("token", token)
                .put("conversation_id", conversationId)
                .put("started_at", startedAt)
                .put("complete", false)
                .toString())
            .commit()
        attachment?.let { addDebugAgentAttachment(token, it) }
        agentGoalInput.setText(goal)
        agentGoalInput.setSelection(agentGoalInput.text?.length ?: 0)
        submitAgentGoal()
        scheduleDebugAgentSnapshot(token, conversationId, startedAt, attempt = 0)
    }, 250L)
}

internal fun MainActivity.addDebugAgentAttachment(token: String, attachment: DebugAgentAttachment) {
    val safeName = attachment.name
        .replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "_")
        .trim('.', ' ')
        .ifBlank { "attachment.txt" }
    val directory = File(cacheDir, "debug-agent-inputs")
    check(directory.mkdirs() || directory.isDirectory)
    val file = File(directory, "${token.hashCode().toUInt()}-$safeName")
    file.writeBytes(attachment.bytes)
    agentInputAttachments.clear()
    agentInputAttachments += AgentInputAttachment(
        id = "debug-${UUID.randomUUID()}",
        uri = Uri.fromFile(file),
        displayName = safeName,
        mimeType = when (safeName.substringAfterLast('.', "").lowercase(Locale.US)) {
            "txt", "log", "md", "csv" -> "text/plain"
            "json" -> "application/json"
            else -> "application/octet-stream"
        },
        sizeBytes = attachment.bytes.size.toLong()
    )
    renderAgentInputAttachments()
}

internal fun MainActivity.scheduleDebugAgentSnapshot(
    token: String,
    conversationId: String,
    startedAt: Long,
    attempt: Int
) {
    handler.postDelayed({
        if (isFinishing || isDestroyed) return@postDelayed
        agentTranscriptContentExecutor.execute {
            val entries = agentTranscriptStore.page(
                conversationId = conversationId,
                pageSize = DEBUG_AGENT_TRANSCRIPT_PAGE_ITEMS
            ).entries.filter { it.timestampMillis >= startedAt - 1_000L }
            val userEntry = entries.lastOrNull { it.role == AgentTranscriptRole.USER }
            val turnId = userEntry?.turnId.orEmpty()
            val turnEntries = if (turnId.isBlank()) entries else entries.filter { it.turnId == turnId }
            val assistantEntry = turnEntries.lastOrNull { it.role == AgentTranscriptRole.ASSISTANT }
            val runtime = agentRuntimeConversationIds.entries
                .firstOrNull { it.value == conversationId }
                ?.key
                ?: mobileNativeAgent
            val phase = runCatching { runtime.snapshot().phase.name }.getOrDefault("")
            val terminalDedupe = assistantEntry?.dedupeKey.orEmpty().let { key ->
                key.startsWith("result:") ||
                    key.startsWith("direct-system:") ||
                    key.startsWith("fast-local:") ||
                    key.startsWith("skill-command:") ||
                    key.startsWith("skill-result:")
            }
            val approval = assistantEntry?.richOutputJson.orEmpty().contains("\"APPROVAL\"")
            val terminalPhase = phase in setOf("COMPLETED", "FAILED", "CANCELLED", "BLOCKED")
            val complete = assistantEntry != null && !approval && (terminalDedupe || terminalPhase)
            val payload = JSONObject()
                .put("token", token)
                .put("conversation_id", conversationId)
                .put("turn_id", turnId)
                .put("started_at", startedAt)
                .put("captured_at", System.currentTimeMillis())
                .put("phase", phase)
                .put("complete", complete)
                .put("entries", JSONArray().apply {
                    turnEntries.forEach { entry ->
                        put(JSONObject()
                            .put("role", entry.role.name)
                            .put("text", entry.text)
                            .put("timestamp", entry.timestampMillis)
                            .put("dedupe_key", entry.dedupeKey)
                            .put("task_id", entry.taskId)
                            .put("rich_output_json", entry.richOutputJson))
                    }
                })
            getSharedPreferences(DEBUG_AGENT_PREFS, Context.MODE_PRIVATE).edit()
                .putString(token, payload.toString())
                .commit()
            if (!complete && attempt < 960) {
                handler.post {
                    scheduleDebugAgentSnapshot(token, conversationId, startedAt, attempt + 1)
                }
            }
        }
    }, 250L)
}

private const val DEBUG_AGENT_TRANSCRIPT_PAGE_ITEMS = 100

internal fun MainActivity.sendImage(uri: Uri) {
    val contact = selectedContact
    if (contact != null && (
            AppStore.isDesktopDeviceContact(this, contact.id) ||
                AppStore.isPersonContact(this, contact.id)
        )
    ) {
        sendPeerAttachments(contact, listOf(uri))
        return
    }
    val meta = imageMeta(uri)
    val msg = ChatMessage(newMessageId(), getString(R.string.message_image_prefix, meta.name), true, CONTACT_ME)
    addMessage(msg)
}

internal fun MainActivity.sendImageForChatContact(contact: Contact, uri: Uri) {
    if (AppStore.isDesktopDeviceContact(this, contact.id) || AppStore.isPersonContact(this, contact.id)) {
        sendPeerAttachments(contact, listOf(uri))
        return
    }
    val meta = imageMeta(uri)
    val msg = ChatMessage(newMessageId(), getString(R.string.message_image_prefix, meta.name), true, CONTACT_ME)
    addMessage(msg)
}

internal fun MainActivity.sendPeerAttachments(contact: Contact, uris: List<Uri>) {
    val attachments = uris.distinct().take(12).mapNotNull { uri ->
        runCatching {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        runCatching { agentAttachmentMetadata(uri) }.getOrNull()
    }
    if (attachments.isEmpty()) return
    val message = ChatMessage(
        id = newMessageId(),
        content = "",
        isMine = true,
        contact = CONTACT_ME,
        deliveryStatus = getString(R.string.delivery_status_sending),
        deliveryTrace = mutableListOf(newTraceEvent("created", "peer_file")),
        attachments = attachments.map { attachment ->
            PeerChatAttachment(
                name = attachment.displayName,
                mimeType = attachment.mimeType,
                sizeBytes = attachment.sizeBytes,
                uri = attachment.uri.toString(),
                transferProgress = 0,
                transferState = PeerAttachmentTransferProgress.STATE_UPLOADING
            )
        }
    )
    addMessage(message)
    val topic = AppStore.outgoingTopicForContact(this, contact.id)
    if (topic == null) {
        updateMessageStatus(message.id, contact.id, getString(R.string.delivery_status_failed))
        return
    }
    outboundMessageExecutor.execute {
        val result = GalaxySSIMqttClient.publishPeerMessageResult(
            content = "",
            contactId = contact.id,
            topicOverride = topic,
            clientMessageId = message.id,
            deliveryTrace = deliveryTraceJson(message.deliveryTrace),
            attachments = attachments
        )
        runOnUiThread {
            if (result == MqttPublishResult.FAILED) {
                markPeerAttachmentTransferFailed(message.id, contact.id)
            }
            updateMessageStatus(
                message.id,
                contact.id,
                getString(when (result) {
                    MqttPublishResult.PUBLISHED -> R.string.delivery_status_sent
                    MqttPublishResult.QUEUED -> R.string.delivery_status_queued
                    MqttPublishResult.FAILED -> R.string.delivery_status_failed
                })
            )
        }
    }
}

internal fun MainActivity.openPeerAttachment(attachment: PeerChatAttachment) {
    val source = attachment.resolvedUri(this)
    if (source == null) {
        val contactId = selectedContact?.id ?: messages.entries.firstOrNull { (_, values) ->
            values.any { message -> attachment in message.attachments }
        }?.key
        if (attachment.artifactUri.isNotBlank() && contactId != null &&
            GalaxySSIMqttDesktopControl.requestPeerArtifactFetch(attachment, contactId)
        ) return
        if (attachment.transferId.isNotBlank() && attachment.transferState in setOf(
                PeerAttachmentTransferProgress.STATE_AVAILABLE,
                PeerAttachmentTransferProgress.STATE_FAILED
            )
        ) {
            if (contactId != null && GalaxySSIMqttClient.requestPeerAttachmentDownload(
                    this,
                    attachment,
                    contactId
                )
            ) return
        }
        if (attachment.transferState != PeerAttachmentTransferProgress.STATE_DOWNLOADING) {
            Toast.makeText(this, R.string.rich_output_download_failed, Toast.LENGTH_SHORT).show()
        }
        return
    }
    if (attachment.mimeType.startsWith("image/")) {
        showAgentImagePreview(source, attachment.name) { savePeerAttachment(attachment) }
        return
    }
    if (attachment.mimeType.startsWith("audio/")) {
        playPeerAudioAttachment(attachment)
        return
    }
    AlertDialog.Builder(this)
        .setTitle(attachment.name)
        .setItems(
            arrayOf(
                getString(R.string.peer_attachment_open),
                getString(R.string.peer_attachment_save)
            )
        ) { _, which ->
            if (which == 0) {
                runCatching {
                    startActivity(Intent(Intent.ACTION_VIEW).apply {
                        setDataAndType(source, attachment.mimeType)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    })
                }.onFailure {
                    Toast.makeText(this, R.string.rich_output_download_failed, Toast.LENGTH_SHORT).show()
                }
            } else {
                savePeerAttachment(attachment)
            }
        }
        .show()
}

internal fun MainActivity.playPeerAudioAttachment(attachment: PeerChatAttachment) {
    val source = attachment.resolvedUri(this) ?: run {
        Toast.makeText(this, R.string.voice_file_missing, Toast.LENGTH_SHORT).show()
        return
    }
    player?.let {
        if (it.isPlaying) it.stop()
        it.release()
        player = null
    }
    peerAudioDataSource?.close()
    peerAudioDataSource = null
    var candidate: android.media.MediaPlayer? = null
    var candidateSource: WipingByteArrayMediaDataSource? = null
    runCatching {
        val voiceBytes = contentResolver.openInputStream(source)?.use { input -> input.readBytes() }
            ?: throw java.io.FileNotFoundException("Encrypted voice attachment is unavailable")
        val mediaSource = WipingByteArrayMediaDataSource(voiceBytes)
        candidateSource = mediaSource
        peerAudioDataSource = mediaSource
        val activePlayer = android.media.MediaPlayer()
        candidate = activePlayer
        activePlayer.apply {
            setAudioAttributes(PeerVoiceMessageAudio.playbackAttributes())
            setDataSource(mediaSource)
            prepare()
            setOnCompletionListener { completed ->
                completed.release()
                if (player === completed) player = null
                if (peerAudioDataSource === mediaSource) peerAudioDataSource = null
                mediaSource.close()
            }
            start()
        }
        player = candidate
    }.onFailure {
        candidate?.let { failedPlayer ->
            runCatching { failedPlayer.release() }
        }
        candidateSource?.let { failedSource ->
            if (peerAudioDataSource === failedSource) peerAudioDataSource = null
            failedSource.close()
        }
        Toast.makeText(this, R.string.voice_file_missing, Toast.LENGTH_SHORT).show()
    }
}

internal fun MainActivity.savePeerAttachment(attachment: PeerChatAttachment) {
    outboundMessageExecutor.execute {
        val result = PeerIncomingAttachmentStore.saveToDownloads(this, attachment)
        runOnUiThread {
            Toast.makeText(
                this,
                if (result.isSuccess) R.string.peer_attachment_saved else R.string.rich_output_download_failed,
                Toast.LENGTH_SHORT
            ).show()
        }
    }
}

internal fun MainActivity.targetTopicForSelectedContact(): String? {
    val contact = selectedContact ?: return null
    return AppStore.outgoingTopicForContact(this, contact.id)
}

internal fun MainActivity.markDeliveredSoon(msg: ChatMessage, contactId: String) {
    handler.postDelayed({
        appendDeliveryTrace(msg.id, contactId, "delivered_local_estimate", "QoS1 publish accepted")
        updateMessageStatus(msg.id, contactId, getString(R.string.delivery_status_delivered))
    }, 700)
}

internal fun MainActivity.updateMessageStatus(messageId: Long, contactId: String, status: String) {
    val list = messages[contactId] ?: return
    val index = list.indexOfFirst { it.id == messageId }
    if (index >= 0) {
        list[index].deliveryStatus = status
        saveChatHistory(list[index])
        if (chatPage.visibility == View.VISIBLE && selectedContact?.id == contactId) {
            messageList.post {
                if (chatPage.visibility == View.VISIBLE && selectedContact?.id == contactId) {
                    messageAdapter?.syncMessages(currentMessages)
                }
            }
        }
    }
}

internal fun MainActivity.appendDeliveryTrace(messageId: Long, contactId: String, stage: String, detail: String = "") {
    val list = messages[contactId] ?: return
    val index = list.indexOfFirst { it.id == messageId }
    if (index < 0) return
    list[index].deliveryTrace.add(newTraceEvent(stage, detail))
    saveChatHistory(list[index])
    if (chatPage.visibility == View.VISIBLE && selectedContact?.id == contactId) {
        messageList.post {
            if (chatPage.visibility == View.VISIBLE && selectedContact?.id == contactId) {
                messageAdapter?.syncMessages(currentMessages)
            }
        }
    }
}

internal fun MainActivity.mergeDeliveryTrace(messageId: Long, contactId: String, trace: List<DeliveryTraceEvent>, status: String? = null) {
    val list = messages[contactId] ?: return
    val index = list.indexOfFirst { it.id == messageId }
    if (index < 0) return
    val message = list[index]
    val existing = message.deliveryTrace.map { "${it.stage}|${it.at}|${it.detail}" }.toMutableSet()
    trace.forEach { event ->
        if (event.stage == "agent_running") {
            val runningIndex = message.deliveryTrace.indexOfLast { it.stage == event.stage }
            if (runningIndex >= 0) {
                message.deliveryTrace[runningIndex] = event
                return@forEach
            }
        }
        val key = "${event.stage}|${event.at}|${event.detail}"
        if (existing.add(key)) {
            message.deliveryTrace.add(event)
        }
    }
    if (!status.isNullOrBlank()) {
        message.deliveryStatus = status
    }
    saveChatHistory(message)
    if (chatPage.visibility == View.VISIBLE && selectedContact?.id == contactId) {
        messageList.post {
            if (chatPage.visibility == View.VISIBLE && selectedContact?.id == contactId) {
                messageAdapter?.syncMessages(currentMessages)
            }
        }
    }
}

internal fun MainActivity.markContactRead(contactId: String) {
    MessageService.cancelIncomingMessageNotification(this, contactId)
    val readAt = System.currentTimeMillis()
    val list = messages[contactId].orEmpty()
    val changedMessages = mutableListOf<ChatMessage>()
    list.forEach { message ->
        if (!message.isMine && !message.isSystem && !hasTraceStage(message, "read")) {
            message.deliveryTrace.add(DeliveryTraceEvent("read", readAt, "chat_opened"))
            message.deliveryStatus = getString(R.string.delivery_status_read)
            changedMessages += message
        }
    }
    if (changedMessages.isNotEmpty()) {
        saveChatHistory(changedMessages)
        if (chatPage.visibility == View.VISIBLE && selectedContact?.id == contactId) {
            messageList.post {
                if (chatPage.visibility == View.VISIBLE && selectedContact?.id == contactId) {
                    messageAdapter?.syncMessages(currentMessages)
                }
            }
        }
    }
    runCatching {
        historyExecutor.execute {
            val changed = ChatHistoryStore.markContactRead(this, contactId, readAt)
            if (changed > 0) {
                lastHistoryLoadedAt = maxOf(lastHistoryLoadedAt, ChatHistoryStore.updatedVersion(this))
            }
        }
    }
}

internal fun MainActivity.hasTraceStage(message: ChatMessage, stage: String): Boolean {
    return message.deliveryTrace.any { it.stage == stage }
}

internal fun MainActivity.newTraceEvent(stage: String, detail: String = ""): DeliveryTraceEvent {
    return DeliveryTraceEvent(stage = stage, at = System.currentTimeMillis(), detail = detail)
}

internal fun MainActivity.deliveryTraceText(message: ChatMessage): String {
    if (message.deliveryTrace.isEmpty()) return getString(R.string.delivery_trace_empty)
    val origin = message.deliveryTrace.first().at
    return message.deliveryTrace.takeLast(32).joinToString("\n") { event ->
        val detail = event.detail.takeIf { it.isNotBlank() }?.let { " · $it" }.orEmpty()
        val clock = java.text.SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault()).format(java.util.Date(event.at))
        "$clock +${(event.at - origin).coerceAtLeast(0L)} ms ${deliveryTraceLabel(event.stage)}$detail"
    }
}

internal fun MainActivity.deliveryTraceLabel(stage: String): String = when (stage) {
    "created" -> getString(R.string.delivery_trace_created)
    "persisted" -> getString(R.string.delivery_trace_persisted)
    "queued" -> getString(R.string.delivery_trace_queued)
    "mqtt_published" -> getString(R.string.delivery_trace_mqtt_published)
    "publish_failed" -> getString(R.string.delivery_trace_publish_failed)
    "delivered_local_estimate" -> getString(R.string.delivery_trace_delivered_estimate)
    "desktop_received" -> getString(R.string.delivery_trace_desktop_received)
    "desktop_plain" -> getString(R.string.delivery_trace_desktop_plain)
    "desktop_decrypted" -> getString(R.string.delivery_trace_desktop_decrypted)
    "agent_started" -> getString(R.string.delivery_trace_agent_started)
    "agent_first_output" -> getString(R.string.delivery_trace_agent_first_output)
    "agent_replied" -> getString(R.string.delivery_trace_agent_replied)
    "agent_accepted" -> getString(R.string.agent_task_status_accepted)
    "agent_queued" -> getString(R.string.agent_task_status_queued)
    "agent_recovering" -> getString(R.string.agent_task_status_recovering)
    "agent_running" -> getString(R.string.agent_task_status_running)
    "agent_waiting_input" -> getString(R.string.agent_task_status_waiting_input)
    "agent_completed" -> getString(R.string.agent_task_status_completed)
    "agent_failed" -> getString(R.string.agent_task_status_failed)
    "agent_cancelled" -> getString(R.string.agent_task_status_cancelled)
    "agent_timed_out" -> getString(R.string.agent_task_status_timed_out)
    "desktop_reply_publish_queued" -> getString(R.string.delivery_trace_desktop_reply_queued)
    "desktop_reply_broker_ack" -> getString(R.string.delivery_trace_desktop_reply_ack)
    "desktop_broker_ack" -> getString(R.string.delivery_trace_desktop_broker_ack)
    "desktop_mobile_test_queued" -> getString(R.string.delivery_trace_desktop_mobile_test_queued)
    "desktop_agent_push_queued" -> getString(R.string.delivery_trace_agent_push_queued)
    "desktop_connector_status" -> getString(R.string.delivery_trace_connector_status)
    "desktop_pairing_confirmed" -> getString(R.string.delivery_trace_pairing_confirmed)
    "desktop_pairing_revocation_queued" -> getString(R.string.delivery_trace_pairing_revocation_queued)
    "received" -> getString(R.string.delivery_trace_received)
    "decrypted" -> getString(R.string.delivery_trace_decrypted)
    "cloud_request" -> getString(R.string.delivery_trace_cloud_request)
    "cloud_reply" -> getString(R.string.delivery_trace_cloud_reply)
    "cloud_reply_received" -> getString(R.string.delivery_trace_cloud_reply_received)
    "cloud_error" -> getString(R.string.delivery_trace_cloud_error)
    "local_saved" -> getString(R.string.delivery_status_local_saved)
    else -> stage
}

internal fun MainActivity.parseIncomingMessage(payload: String): ChatMessage {
    val json = runCatching { JSONObject(payload) }.getOrNull()
    val incomingTrace = incomingDeliveryTrace(json)
    if (json?.optString("type") == "delivery_ack") {
        applyDeliveryAck(json, incomingTrace)
        return ChatMessage(newMessageId(), "", false, CONTACT_SYSTEM, isSystem = true, deliveryTrace = incomingTrace)
    }
    if (json?.optString("type") == "capability_manifest") {
        json.optJSONArray("connector_agents")?.let { agents ->
            AppStore.updateConnectorAgentStatuses(this, agents)
            requestAgentRegistrySnapshotSync(force = true)
            refreshDirectoryContacts()
        }
        return ChatMessage(newMessageId(), "", false, CONTACT_SYSTEM, isSystem = true, deliveryTrace = incomingTrace)
    }
    if (json?.optString("type") == "pairing_revoked") {
        Log.w("GalaxySSIDebug", "Pairing revoked control message received")
        val desktopId = json.optString("desktop_id")
        val revokedContactIds = json.optJSONArray("revoked_contact_ids")
            ?.let { array ->
                (0 until array.length()).mapNotNull { index ->
                    array.optString(index).takeIf(String::isNotBlank)
                }.toSet()
            }
            .orEmpty()
        revokedContactIds.forEach { contactId ->
            val removedMessages = messages.remove(contactId).orEmpty()
            discardPendingChatHistory(removedMessages.map(ChatMessage::id))
            summaries.remove(contactId)
            loadedHistoryContacts.remove(contactId)
        }
        if (selectedContact?.id in revokedContactIds) {
            selectedContact = null
            chatPage.visibility = View.GONE
            mainPage.visibility = View.VISIBLE
            showConversationHub(ConversationHubTab.CONVERSATIONS)
        }
        requestAgentRegistrySnapshotSync(force = true)
        refreshDirectoryContacts()
        val content = json.optString("content")
            .ifBlank { getString(R.string.system_pairing_revoked_default) }
        return ChatMessage(newMessageId(), content, false, CONTACT_SYSTEM, deliveryTrace = incomingTrace)
    }
    if (ConnectorControlMessagePolicy.isSilentStatus(json?.optString("type").orEmpty())) {
        json?.optJSONArray("connector_agents")?.let { agents ->
            AppStore.updateConnectorAgentStatuses(this, agents)
            refreshDirectoryContacts()
        }
        return ChatMessage(newMessageId(), "", false, CONTACT_SYSTEM, isSystem = true, deliveryTrace = incomingTrace)
    }
    if (json?.optString("type") == "pairing_confirmed") {
        Toast.makeText(
            this,
            getString(R.string.pairing_desktop_added, json.optString("desktop_display_name").ifBlank { json.optString("desktop_name") }),
            Toast.LENGTH_LONG
        ).show()
        refreshDirectoryContacts()
        json.optJSONArray("connector_agents")?.let { agents ->
            AppStore.updateConnectorAgentStatuses(this, agents)
            requestAgentRegistrySnapshotSync(force = true)
            refreshDirectoryContacts()
        }
        val content = json.optString("content")
            .ifBlank { getString(R.string.system_connector_status_updated) }
        return ChatMessage(newMessageId(), content, false, CONTACT_SYSTEM, deliveryTrace = incomingTrace)
    }
    if (json?.optString("type") == "profile_update") {
        val senderId = json.optString("sender")
            .ifBlank { json.optString("galaxyssi_id") }
            .ifBlank { json.optString("hermes_id") }
        val newName = json.optString("name")
        if (senderId.isNotBlank() && newName.isNotBlank()) {
            AppStore.updateContactName(this, senderId, newName)
            refreshDirectoryContacts()
        }
        val content = getString(R.string.system_profile_updated, newName.ifBlank { senderId })
        return ChatMessage(newMessageId(), content, false, CONTACT_SYSTEM, deliveryTrace = incomingTrace)
    }
    val content = exactConnectorContent(json)
        ?: PeerChatPresentation.incomingContent(payload, json)
    val sender = json?.optString("sender", "hermes") ?: "hermes"
    val contactId = json?.optString("contact_id", CONTACT_HERMES.id)?.takeIf { it.isNotBlank() } ?: CONTACT_HERMES.id
    val contact = contactById(if (sender == "system") CONTACT_SYSTEM.id else contactId)
    val attachments = PeerChatAttachment.decode(json?.optJSONArray("attachments"))
    return ChatMessage(
        newMessageId(),
        content,
        sender == "self",
        contact,
        deliveryTrace = incomingTrace,
        taskId = json?.optString("task_id").orEmpty(),
        taskStatus = json?.optString("task_status").orEmpty(),
        taskStatusSeq = json?.optLong("status_seq", 0L) ?: 0L,
        remoteMessageId = json?.optString("message_id").orEmpty(),
        attachments = attachments
    )
}

internal fun MainActivity.exactConnectorContent(json: JSONObject?): String? {
    if (json?.optString("exact_content_encoding") != "base64-utf8") return null
    val encoded = json.optString("exact_content_b64")
    if (encoded.isBlank() || encoded.length > 256 * 1024) return null
    return runCatching {
        String(Base64.decode(encoded, Base64.DEFAULT), Charsets.UTF_8)
            .takeIf { value -> value.toByteArray(Charsets.UTF_8).size <= 128 * 1024 }
    }.getOrNull()
}

internal fun MainActivity.beginVoiceCoordinatorSession(purpose: String, traceId: String): String {
    if (!isVoiceInteractionCoordinatorInitialized() || !VoiceFeatureFlags.isCoordinatorEnabled(this)) return ""
    val settings = VoiceAssistantSettings.get(this)
    val transition = runCatching {
        voiceInteractionCoordinator.begin(
            VoiceSessionConfig(
                requestedSessionId = traceId,
                source = purpose,
                language = LanguagePolicySettings.resolvedAsrLanguage(this),
                targetId = selectedContact?.id.orEmpty().ifBlank { settings.targetContactId },
                routingMode = settings.routingMode,
                speakReplies = settings.speakReplies
            )
        )
    }.getOrNull() ?: return ""
    if (!transition.accepted) return ""
    return transition.current.sessionId.also { recordingVoiceCoordinatorSessionId = it }
}

internal fun MainActivity.voiceCoordinatorSession(traceId: String): String {
    if (!isVoiceInteractionCoordinatorInitialized() || !VoiceFeatureFlags.isCoordinatorEnabled(this)) return ""
    if (traceId.isBlank()) return ""
    val current = voiceInteractionCoordinator.snapshot()
    return traceId.takeIf { it == current.sessionId }.orEmpty()
}

internal fun MainActivity.dispatchVoiceCoordinator(event: VoiceInteractionEvent) {
    if (!isVoiceInteractionCoordinatorInitialized() || !VoiceFeatureFlags.isCoordinatorEnabled(this)) return
    runCatching { voiceInteractionCoordinator.dispatch(event) }
        .onFailure { Log.w("GalaxySSIVoice", "Coordinator event rejected safely", it) }
}

internal fun MainActivity.acceptVoiceCoordinatorFinal(traceId: String, transcript: TranscriptHypothesis): Boolean {
    val sessionId = voiceCoordinatorSession(traceId)
    if (sessionId.isBlank()) return true
    val transition = runCatching {
        voiceInteractionCoordinator.dispatch(
            VoiceInteractionEvent.TranscriptFinal(
                sessionId,
                transcript
            )
        )
    }.getOrElse {
        Log.w("GalaxySSIVoice", "Coordinator final transcript failed safely", it)
        return true
    }
    return transition.commands.any { it is VoiceInteractionCommand.RouteFinalTranscript }
}
