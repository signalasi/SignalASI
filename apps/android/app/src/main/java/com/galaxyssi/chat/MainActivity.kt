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
import com.galaxyssi.chat.ui.ConnectingStartupView
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

internal class BaselineShiftSpan(private val shiftPx: Int) : CharacterStyle(), UpdateAppearance {
    override fun updateDrawState(tp: TextPaint) {
        tp.baselineShift += shiftPx
    }
}

class MainActivity : Activity(), GalaxySSIMqttClient.Listener {
    internal lateinit var deviceProfile: AgentDeviceProfile




    // UI Views
    internal lateinit var wakePage: FrameLayout
    internal lateinit var wakeAnimation: ImageView
    internal lateinit var mainPage: LinearLayout
    internal lateinit var mainTopBar: LinearLayout
    internal lateinit var agentPage: LinearLayout
    internal lateinit var agentSessionTitle: TextView
    internal lateinit var agentSubtitleText: TextView
    internal lateinit var agentOutputList: RecyclerView
    internal lateinit var agentOutputLayout: LinearLayoutManager
    internal lateinit var agentTranscriptAdapter: AgentTranscriptRecyclerAdapter
    internal lateinit var agentSettingsButton: ImageButton
    internal lateinit var agentMemoryCaptureButton: TextView
    internal lateinit var agentToolboxList: LinearLayout
    internal lateinit var agentCurrentAppText: TextView
    internal lateinit var agentCallableTargetsText: TextView
    internal lateinit var agentRunningTasksText: TextView
    internal lateinit var agentMemoryText: TextView
    internal lateinit var agentKnowledgeText: TextView
    @Volatile internal var agentOperationInFlight = false
    internal val activeAgentTasks = ConcurrentHashMap<Long, MobileNativeAgent>()
    internal val provisionalAgentTasks = ConcurrentHashMap.newKeySet<MobileNativeAgent>()
    internal val completedConnectorTaskIds = ConcurrentHashMap.newKeySet<String>()
    internal val supersededConnectorSourceIds = ConcurrentHashMap.newKeySet<Long>()
    internal val supervisedProjectConnectorSourceIds = ConcurrentHashMap.newKeySet<Long>()
    internal val agentRuntimeConversationIds = ConcurrentHashMap<MobileNativeAgent, String>()
    internal val agentRuntimeTurnIds = ConcurrentHashMap<MobileNativeAgent, String>()
    internal val agentConnectorResponsesInFlight = ConcurrentHashMap.newKeySet<String>()
    internal val pendingDirectConnectorActions = ConcurrentHashMap<String, AgentAction>()
    internal val pendingDirectConnectorRuns = ConcurrentHashMap<Long, PendingDirectConnectorRun>()
    internal val pendingAgentReplyIndicators =
        ConcurrentHashMap<String, PendingAgentReplyIndicator>()
    internal val directControlPlaneExecutor by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        AgentControlPlaneActionExecutor(this, AndroidAgentActionExecutor(this))
    }
    internal val directAgentActionExecutor by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        PhoneExecutionAuthority.guarded(
            NotifyingAgentActionExecutor(this, directControlPlaneExecutor)
        )
    }
    internal val agentConnectorTimeoutCallbacks = ConcurrentHashMap<String, Runnable>()
    internal val agentTimelineOperationsInFlight = ConcurrentHashMap.newKeySet<String>()
    internal val remoteAgentApprovalsInFlight = ConcurrentHashMap.newKeySet<String>()
    internal val remoteAgentApprovalTaskIds = ConcurrentHashMap.newKeySet<String>()
    internal val remoteTaskEventFingerprints = ConcurrentHashMap<String, String>()
    internal val agentExecutionPresentations =
        ConcurrentHashMap<String, AgentExecutionPresentation>()
    internal var pendingDirectSystemAction: PendingDirectSystemAction? = null
    internal lateinit var agentScreenSearchInput: EditText
    internal lateinit var agentScreenDetailList: LinearLayout
    internal lateinit var agentActionQueueList: LinearLayout
    internal lateinit var agentRequirementList: LinearLayout
    internal lateinit var agentPlanContextList: LinearLayout
    internal lateinit var agentVerificationList: LinearLayout
    internal lateinit var agentAuditTrailList: LinearLayout
    internal lateinit var agentRecentTaskList: LinearLayout
    internal lateinit var agentGoalInput: EditText
    internal lateinit var agentInsightBar: LinearLayout
    internal lateinit var agentInsightText: TextView
    internal lateinit var agentComposerRow: LinearLayout
    internal lateinit var agentPrimaryActionSlot: FrameLayout
    internal lateinit var agentActionTray: LinearLayout
    internal lateinit var agentRecordingCenter: View
    internal lateinit var agentRecordingInstruction: TextView
    internal lateinit var agentRecordingWaveform: VoiceWaveformView
    internal lateinit var agentRecordingTranscript: TextView
    internal lateinit var agentRecordingTimer: TextView
    internal lateinit var agentHoldToTalkController: AppleHoldToTalkController
    internal lateinit var agentAttachButton: ImageButton
    internal lateinit var agentSubmitButton: ImageButton
    internal lateinit var agentBrandLogo: ImageView
    internal lateinit var agentAttachmentPreviewScroll: HorizontalScrollView
    internal lateinit var agentAttachmentPreviewList: LinearLayout
    internal var agentActionTrayExpanded = false
    internal var agentComposerTextMode = false
    internal var agentComposerKeyboardObserved = false
    internal var agentComposerKeyboardClosedAt = 0L
    internal lateinit var discoverPage: LinearLayout
    internal lateinit var mePage: View
    internal lateinit var featurePage: LinearLayout
    internal lateinit var featureTitle: TextView
    internal lateinit var featureContent: LinearLayout
    internal lateinit var featureBackButton: ImageButton
    internal var activeDesktopControlId: String? = null
    internal var activeDesktopPerceptionId: String? = null
    internal var activeDesktopScreenView: DesktopRemoteScreenView? = null
    internal var activeDesktopScreenPlaceholder: TextView? = null
    internal lateinit var mainTitle: TextView
    internal lateinit var mainBackButton: ImageButton
    internal lateinit var mainActionButton: TextView
    internal lateinit var chatPage: LinearLayout
    internal lateinit var chatTitle: TextView
    internal lateinit var chatHeaderContent: LinearLayout
    internal lateinit var chatSubtitleRow: LinearLayout
    internal lateinit var chatModelTag: LinearLayout
    internal lateinit var chatModelButton: LinearLayout
    internal lateinit var chatModelLabel: TextView
    internal lateinit var chatSubtitle: TextView
    internal lateinit var chatAvatar: ImageView
    internal lateinit var statusDot: View
    internal lateinit var contactStatusDot: View
    internal lateinit var backButton: ImageButton
    internal lateinit var securityButton: ImageButton
    internal var messageAdapter: MessageAdapter? = null
    internal lateinit var messageInput: EditText
    internal lateinit var sendButton: ImageButton
    internal lateinit var imageButton: ImageButton
    internal lateinit var chatComposerRow: LinearLayout
    internal lateinit var chatPrimaryActionSlot: FrameLayout
    internal lateinit var chatRecordingCenter: View
    internal lateinit var chatRecordingInstruction: TextView
    internal lateinit var chatRecordingWaveform: VoiceWaveformView
    internal lateinit var chatRecordingTranscript: TextView
    internal lateinit var chatRecordingTimer: TextView
    internal lateinit var holdToTalkController: AppleHoldToTalkController
    internal lateinit var chatInputBar: LinearLayout
    internal lateinit var messageList: RecyclerView
    internal lateinit var controlCenterRenderer: ControlCenterRenderer
    internal val controlCenterHomeRenderCache = ControlCenterPageRenderCache()
    internal val controlCenterHomeRefreshPolicy =
        ControlCenterHomeRefreshPolicy(CONTROL_CENTER_HOME_CACHE_MILLIS)
    internal val controlCenterBackStack = ArrayDeque<ControlCenterDestination>()
    internal var controlCenterDestination: ControlCenterDestination? = null
    internal var renderingControlCenterDestination = false
    internal var featureBackAction: (() -> Unit)? = null
    internal var pendingVoiceEnableFromControlCenter = false
    @Volatile internal var runtimeCatalogRefreshInProgress = false
    @Volatile internal var pendingRuntimeCatalogPackId: String? = null
    @Volatile internal var runtimePackInstallInProgressId: String? = null
    @Volatile internal var lastSelfEvolutionRemoteSyncAtMillis = 0L
    internal val localModelSearchGeneration = AtomicLong(0L)
    internal val localModelRowBindings = linkedMapOf<String, LocalModelRowBinding>()
    internal val localModelDownloadRefresh = object : Runnable {
        override fun run() {
            if (localModelRowBindings.isEmpty()) return
            var active = false
            localModelRowBindings.values.forEach { binding ->
                val state = LocalModelManager.state(this@MainActivity, binding.profile)
                updateLocalModelRow(binding, state)
                active = active || state.state in setOf(
                    LocalModelInstallState.QUEUED,
                    LocalModelInstallState.DOWNLOADING,
                    LocalModelInstallState.VERIFYING,
                    LocalModelInstallState.INSTALLING
                )
            }
            if (active) handler.postDelayed(this, 750L)
        }
    }

    // State
    internal val handler = Handler(Looper.getMainLooper())
    internal val globalAgentRefreshInProgress = AtomicBoolean(false)
    internal val globalAgentRefreshRequested = AtomicBoolean(false)
    internal val globalInsightCountRefreshInProgress = AtomicBoolean(false)
    internal val agentTaskRecoveryInProgress = AtomicBoolean(false)
    internal val agentTaskRecoveryLastStartedAt = AtomicLong(0L)
    internal val agentEvalProgressRefreshGeneration = AtomicLong(0L)
    internal val agentRegistrySyncInProgress = AtomicBoolean(false)
    internal val agentRegistrySyncRequested = AtomicBoolean(false)
    internal val agentRegistrySyncLock = Any()
    internal val globalProactiveDeliveryListener = GlobalProactiveDeliveryListener {
        handler.post(::refreshGlobalAgentCognition)
    }
    internal val historyExecutor = Executors.newSingleThreadExecutor()
    internal val agentTranscriptContentExecutor = Executors.newSingleThreadExecutor()
    internal val agentRegistryHeartbeatExecutor = Executors.newSingleThreadExecutor()
    internal val agentSubmissionExecutor = Executors.newSingleThreadExecutor()
    internal val outboundMessageExecutor = Executors.newSingleThreadExecutor()
    internal val agentRoutingExecutor = Executors.newSingleThreadExecutor()
    internal val agentRuntimeRecoveryExecutor = Executors.newSingleThreadExecutor()
    internal val agentRouteSelectionExecutor = Executors.newSingleThreadExecutor()
    internal val agentTaskPersistenceExecutor = Executors.newSingleThreadExecutor()
    internal val agentEvalExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "GalaxySSI-AgentEvalOps")
    }
    internal val agentTaskLivenessExecutor = Executors.newSingleThreadExecutor()
    internal val agentTaskEventExecutor = Executors.newSingleThreadExecutor()
    internal val navigationContentExecutor = Executors.newFixedThreadPool(2)
    internal val navigationContentGate = NavigationContentGate()
    internal val cloudExecutor = Executors.newCachedThreadPool()
    internal val activeCloudStreams = ConcurrentHashMap<String, ActiveCloudStream>()
    internal val activeCloudStreamJobs = ConcurrentHashMap<String, Job>()
    internal val pendingHistoryMessages = linkedMapOf<Long, JSONObject>()
    internal val loadedHistoryContacts = mutableSetOf<String>()
    internal val historyPageCursors = mutableMapOf<String, Long?>()
    internal val historyHasMore = mutableMapOf<String, Boolean>()
    internal val historyForwardPageCursors = mutableMapOf<String, Long?>()
    internal val historyHasNewer = mutableMapOf<String, Boolean>()
    internal val historyLoadsInFlight = mutableSetOf<String>()
    internal val historySaveRunnable = Runnable { enqueuePendingChatHistorySave() }
    internal lateinit var mobileNativeAgent: MobileNativeAgent
    internal lateinit var agentTranscriptStore: AgentTranscriptStore
    internal val agentTaskCenter by lazy(LazyThreadSafetyMode.NONE) {
        AgentTaskCenter(SQLiteAgentTaskStore(this))
    }
    internal lateinit var globalSuperAgentRuntime: GlobalSuperAgentRuntime
    internal var openLatestGlobalInsightWhenDelivered = false
    internal var requestedGlobalInsightConversationId = ""
    internal lateinit var agentRunRecorder: AgentRunRecorder
    internal lateinit var agentSkillRuntime: AgentSkillRuntime
    internal lateinit var agentSkillMatcher: AgentSkillMatcher
    internal lateinit var agentLearningEngine: AgentLearningEngine
    internal lateinit var agentRunEventStore: AgentRunEventStore
    internal lateinit var voiceAgentRunBridge: VoiceAgentRunBridge
    internal val voiceAgentRunListener = VoiceAgentRunListener { update ->
        handler.post {
            if (!isFinishing && !isDestroyed) handleVoiceAgentRunUpdate(update)
        }
    }
    internal val pendingVoiceAgentRunCardUpdates = linkedMapOf<String, VoiceAgentRunSnapshot>()
    internal var voiceAgentRunCardRefreshScheduled = false
    internal val voiceAgentRunCardRefresh = Runnable {
        voiceAgentRunCardRefreshScheduled = false
        val snapshots = pendingVoiceAgentRunCardUpdates.values.toList()
        pendingVoiceAgentRunCardUpdates.clear()
        snapshots.forEach(::syncVoiceAgentRunCard)
    }
    internal lateinit var agentHandoffStore: EncryptedAgentHandoffStore
    internal lateinit var encryptedAgentRegistry: EncryptedAgentRegistry
    @Volatile internal var lastAgentRegistrySyncAtMillis = 0L
    internal val agentTurnGoals = ConcurrentHashMap<String, String>()
    internal val agentContextBeforeTurn = ConcurrentHashMap<String, AgentConversationContext>()
    internal val agentRecoveryActionsInFlight = linkedSetOf<String>()
    internal lateinit var agentMcpRegistry: AgentMcpRegistry
    internal lateinit var remoteSelfEvolutionStore: EncryptedAgentRemoteSelfEvolutionStore
    internal lateinit var agentMcpPackageRepository: AgentMcpPackageRepository
    internal lateinit var agentRuntimePackCatalogManager: AgentRuntimePackCatalogManager
    internal val agentRunIdsByTurn = ConcurrentHashMap<String, String>()
    internal var agentSessionsDialog: android.app.Dialog? = null
    internal var restoreHiddenConversationHub: (() -> Boolean)? = null
    internal var conversationHubContactsChangedListener: ((List<Contact>) -> Unit)? = null
    internal var showingFriendRequests = false
    internal var activeFriendRequestContactId = ""
    internal val pendingAgentConnectorStreamUpdates =
        ConcurrentHashMap<Long, AgentConnectorStreamUpdate>()
    internal val agentConnectorStreamAttempts = AgentConnectorStreamAttemptRegistry()
    internal val pendingAgentConnectorStreamRetirements = ConcurrentHashMap.newKeySet<Long>()
    internal val agentConnectorStreamRefreshScheduled = AtomicBoolean(false)
    internal val agentConnectorStreamRefreshRunnable = Runnable {
        agentConnectorStreamRefreshScheduled.set(false)
        val updates = pendingAgentConnectorStreamUpdates.values.toList()
        updates.forEach { update ->
            pendingAgentConnectorStreamUpdates.remove(update.sourceMessageId, update)
        }
        var shouldRender = false
        updates.forEach { update ->
            shouldRender = applyAgentConnectorStreamUpdate(update) || shouldRender
        }
        if (shouldRender && activeMainTab == PAGE_AGENT) {
            renderAgentTranscript(agentTranscriptWindow.entries)
        }
        scheduleAgentConnectorStreamRefresh()
    }
    internal val agentConnectorResponseListener = AgentConnectorResponseListener { response ->
        agentConnectorStreamAttempts.close(response.sourceMessageId)
        pendingAgentConnectorStreamUpdates.remove(response.sourceMessageId)
        val recoveryKey = "runtime-restore:${AgentConnectorResponseCodec.identity(response)}"
        if (!agentConnectorResponsesInFlight.add(recoveryKey)) return@AgentConnectorResponseListener
        agentRuntimeRecoveryExecutor.execute {
            try {
                if (!AgentConnectorResponseStore.isCurrentExecution(this, response)) return@execute
                runtimeForConnectorResponse(
                    sourceMessageId = response.sourceMessageId,
                    contactId = response.contactId,
                    conversationId = response.conversationId,
                    turnId = response.turnId,
                    taskId = response.taskId,
                    restorePersisted = true
                )
                if (isFinishing || isDestroyed) return@execute
                consumeAgentConnectorResponse(response)
            } finally { agentConnectorResponsesInFlight.remove(recoveryKey) }
        }
    }
    internal val agentConnectorStreamListener = AgentConnectorStreamListener { update ->
        if (!agentConnectorStreamAttempts.accept(update)) return@AgentConnectorStreamListener
        pendingAgentConnectorStreamUpdates.compute(update.sourceMessageId) { _, current ->
            when {
                !agentConnectorStreamAttempts.isCurrent(update) -> current
                current == null || update.attemptOrdinal >= current.attemptOrdinal -> update
                else -> current
            }
        }
        scheduleAgentConnectorStreamRefresh()
    }
    internal val liveAgentConnectorStreams = ConcurrentHashMap<Long, AgentTranscriptEntry>()
    internal val agentTaskLivenessListener = AgentTaskLivenessListener { signal ->
        runCatching {
            agentTaskLivenessExecutor.execute { handleAgentTaskLivenessSignal(signal) }
        }.onFailure { error ->
            if (!isFinishing && !isDestroyed) {
                Log.w("GalaxySSIAgent", "Task liveness signal could not be scheduled", error)
            }
        }
    }
    internal val agentStartupMaintenanceRunnable = Runnable {
        if (!isFinishing && !isDestroyed) {
            val foregroundTaskActive = initialAgentHydrationPending ||
                pendingAgentReplyIndicators.isNotEmpty() ||
                provisionalAgentTasks.isNotEmpty() ||
                activeAgentTasks.isNotEmpty() ||
                AgentTaskRuntime.supervisor(this).activeWorkspaces().any { workspace ->
                    workspace.status in setOf(
                        AgentWorkspaceStatus.CREATED,
                        AgentWorkspaceStatus.QUEUED,
                        AgentWorkspaceStatus.RUNNING,
                        AgentWorkspaceStatus.WAITING_RESPONSE
                    )
                }
            if (foregroundTaskActive) {
                handler.postDelayed(
                    { scheduleAgentStartupMaintenance() },
                    AGENT_BUSY_MAINTENANCE_RETRY_MILLIS
                )
                return@Runnable
            }
            requestRecoverableAgentRunReconciliation(
                reason = "startup",
                refreshRegistry = true
            )
        }
    }
    internal val agentVisualScreenListener = AgentVisualScreenListener { result ->
        runOnUiThread {
            if (result.success) {
                if (activeMainTab == PAGE_AGENT) {
                    renderAgentState(mobileNativeAgent.observeCurrentScreen())
                }
                Toast.makeText(
                    this,
                    getString(
                        R.string.agent_screen_visual_ready,
                        result.textLines.size,
                        result.scene.actionCandidateCount,
                        result.scene.inputCandidateCount
                    ),
                    Toast.LENGTH_SHORT
                ).show()
            } else if (result.error.isNotBlank()) {
                Toast.makeText(this, result.error, Toast.LENGTH_SHORT).show()
            }
        }
    }
    internal val messages = mutableMapOf<String, MutableList<ChatMessage>>()
    internal val summaries = mutableMapOf<String, ContactSummary>()
    internal var runtimePlaintextCleared = false
    internal var runtimePlaintextContactId = ""
    internal var runtimePlaintextConversationId = ""
    internal var selectedContact: Contact? = null
    internal var activeMainTab = PAGE_AGENT
    internal var recorder: MediaRecorder? = null
    internal var peerVoiceRecorder: PeerVoiceOpusRecorder? = null
    internal var recordingFile: File? = null
    internal var recordingStartedAt = 0L
    internal var recordingPurpose = ""
    internal var recordingVoiceTraceId = ""
    internal var agentVoiceDraftSnapshot: AgentVoiceDraftSnapshot? = null
    internal var activeVoiceTraceId = ""
    internal var pcmVoiceAudioHub: VoiceAudioHub? = null
    internal var pcmVoiceSession: VoiceAudioSession? = null
    internal val whisperDecodeSchedulerLock = Any()
    internal var whisperDecodeScheduler: WhisperDecodeScheduler? = null
    internal val liveWhisperSessions = ConcurrentHashMap<String, LiveWhisperTranscriptionSession>()
    internal val highAccuracyAsrTurns = ConcurrentHashMap<String, HighAccuracyLocalAsrTurn>()
    internal val highAccuracyAsrFinals = ConcurrentHashMap<String, HighAccuracyAsrResult>()
    internal val onlineRealtimeAsrTurns = ConcurrentHashMap<String, OnlineRealtimeAsrTurn>()
    internal val onlineRealtimeAsrFinals = ConcurrentHashMap<String, TranscriptHypothesis>()
    internal val onlineRealtimeAsrClient: OkHttpClient by lazy {
        OkHttpClient.Builder().retryOnConnectionFailure(false).build()
    }
    internal val onlineRealtimeAsrProviderLazy = lazy {
        RealtimeAsrProvider(
            id = "galaxyssi_realtime",
            client = onlineRealtimeAsrClient,
            credentialSource = CachingRealtimeAsrCredentialSource(
                HttpRealtimeAsrCredentialSource(
                    client = onlineRealtimeAsrClient,
                    brokerUrl = BuildConfig.REALTIME_ASR_CREDENTIAL_BROKER_URL
                )
            )
        )
    }
    internal val onlineRealtimeAsrProvider: RealtimeAsrProvider by onlineRealtimeAsrProviderLazy
    internal val onlineRealtimeAsrPreconnectorLazy = lazy {
        RealtimeAsrPreconnector(onlineRealtimeAsrProvider)
    }
    internal val onlineRealtimeAsrPreconnector: RealtimeAsrPreconnector by onlineRealtimeAsrPreconnectorLazy
    internal val voiceReliabilityController: AndroidVoiceReliabilityController by lazy {
        AndroidVoiceReliabilityController(applicationContext)
    }
    internal val voicePerformanceVisibleMetrics = setOf(
        "asr_total_ms",
        "model_first_delta_ms",
        "tts_first_audio_ms",
        "agent_accept_ms",
        "agent_first_progress_ms",
        "agent_first_output_ms"
    )
    internal var pcmCaptureStopping = false
    @Volatile internal var pcmVoiceAmplitude = 0
    internal var lastPcmAudioLevelDispatchAt = 0L
    internal val voiceTraceIdsByTurn = ConcurrentHashMap<String, String>()
    internal val voiceTurnContextsByTraceId = ConcurrentHashMap<String, VoiceTurnContext>()
    internal lateinit var voiceInteractionCoordinator: VoiceInteractionCoordinator
    internal lateinit var voiceExecutionLedger: VoiceExecutionLedger
    internal lateinit var voiceCorrectionJournal: VoiceCorrectionJournal
    internal val voiceSecondPassCoordinator = VoiceSecondPassCoordinator()
    internal val remoteWhisperNodeClient = RemoteWhisperNodeClient(GalaxySSILinkRemoteWhisperTransport)
    internal var voiceRiskConfirmationDialog: AlertDialog? = null
    internal var voiceRiskConfirmationCancellation: (() -> Unit)? = null
    internal var voiceCoordinatorObserverId = ""
    internal var recordingVoiceCoordinatorSessionId = ""
    internal val voiceCoordinatorIdsByTurn = ConcurrentHashMap<String, String>()
    internal val voiceCoordinatorIdsBySourceMessage = ConcurrentHashMap<Long, String>()
    internal var player: android.media.MediaPlayer? = null
    internal var peerAudioDataSource: android.media.MediaDataSource? = null
    internal var chatComposerTextMode = false
    internal var chatComposerKeyboardObserved = false
    internal var chatComposerKeyboardClosedAt = 0L
    internal var secureChannelReady = false
    internal var scanMode = "security"
    internal var latestAgentScreenContext: ScreenContext? = null
    internal var lastRenderedAgentState: AgentUiState? = null
    @Volatile internal var initialAgentHydrationPending = true
    internal val initialAgentHydrationReady = java.util.concurrent.CountDownLatch(1)
    internal var initialAgentHydrationScheduled = false
    internal var completedInitialResume = false
    internal var agentTranscriptPageLoading = false
    internal var agentTranscriptAllLoaded = false
    internal var agentTranscriptRefreshInProgress = false
    internal var agentTranscriptRefreshRequested = false
    internal var agentTranscriptRefreshConversationId = ""
    internal var agentTranscriptRefreshPageSize = INITIAL_VISIBLE_AGENT_TRANSCRIPT_ITEMS
    internal var agentRenderedConversationId = ""
    internal var agentTranscriptAutoFollow = true
    internal var agentTranscriptUserScrollActive = false
    internal val agentTranscriptWindow = AgentTranscriptWindow()
    internal val renderedAgentTranscriptIds = linkedSetOf<String>()
    internal val renderedAgentTranscriptSignatures = mutableMapOf<String, Int>()
    internal var renderedAgentTranscriptSourceEntries: List<AgentTranscriptEntry> = emptyList()
    internal val expandedAgentTranscriptEntries = linkedMapOf<String, AgentTranscriptEntry>()
    internal val expandedAgentTranscriptText = linkedMapOf<String, AgentExpandedTextOutput>()
    internal val agentTranscriptExpansionInFlight = linkedSetOf<String>()
    internal val agentTranscriptCleanupInFlight = ConcurrentHashMap.newKeySet<String>()
    internal val agentProcessCompletionLookups =
        ConcurrentHashMap<String, AgentProcessCompletionLookup>()
    internal val agentProcessCompletionLookupInFlight = ConcurrentHashMap.newKeySet<String>()
    internal val expandedAgentProcessGroups = linkedSetOf<String>()
    internal val collapsedActiveAgentProcessGroups = linkedSetOf<String>()
    internal var agentPlanProgressOverlay: View? = null
    internal var agentPlanProgressOverlayGroupKey = ""
    internal val agentResponseSectionExpansion = linkedMapOf<String, Boolean>()
    internal var pendingExportPassword: String? = null
    internal var pendingExportSkill: Pair<String, String>? = null
    internal var pendingRuntimeArtifactExport: AgentRuntimeArtifactActionPayload? = null
    internal var pendingExportIncludeMessages = true
    internal var pendingImportUri: Uri? = null
    internal val agentInputAttachments = mutableListOf<AgentInputAttachment>()
    internal var pendingAgentCameraUri: Uri? = null
    @Volatile internal var fileServerBaseUrl: String? = null
    internal var voiceOverlay: Dialog? = null
    internal var wakeStatusText: TextView? = null
    internal var wakeTranscriptText: TextView? = null
    internal var wakeReplyPanel: ScrollView? = null
    internal var speechRecognizer: SpeechRecognizer? = null
    internal var agentVoiceListening = false
    internal var voiceAssistantListening = false
    internal var voiceAssistantAwake = false
    internal var voiceAssistantSpeaking = false
    internal var voiceAssistantRecordingCommand = false
    internal var voiceCommandSpeechDetected = false
    internal var voiceCommandLastVoiceAt = 0L
    internal var voiceAssistantRestartPending = false
    internal var wakeReplyPinnedUntilMs = 0L
    internal var lastVoiceRecognitionStartAt = 0L
    internal val voiceAssistantScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    internal val highAccuracyAsrControllerDelegate = lazy(LazyThreadSafetyMode.NONE) {
        HighAccuracyLocalAsrController.create(this, voiceAssistantScope)
    }
    internal val highAccuracyAsrController by highAccuracyAsrControllerDelegate
    internal var wakeWordEngine: WakeWordEngine? = null
    internal var wakeWordDetectionJob: Job? = null
    internal var androidTts: TextToSpeech? = null
    internal var androidTtsInitialized = false
    internal var androidTtsReady = false
    internal val androidTtsRequests = VoiceTtsRequestRegistry()
    internal val progressiveAndroidTtsRequests = ProgressiveTtsUtteranceRegistry()
    internal lateinit var microsoftTts: MicrosoftEdgeTts
    internal val bargeInController = BargeInController()
    internal val progressiveTtsScheduler by lazy(LazyThreadSafetyMode.NONE) {
        TtsChunkScheduler(AgentProgressiveTtsChunkPlayer(this))
    }
    internal val ttsAudioManager by lazy(LazyThreadSafetyMode.NONE) {
        getSystemService(AudioManager::class.java)
    }
    internal var ttsAudioFocusRequest: AudioFocusRequest? = null
    internal var activeProgressiveSpeechSessionId = ""
    internal var activeProgressiveSpeechTraceId = ""
    internal var activeProgressiveSpeechProvider = ""
    internal val voiceHealthRows =
        mutableMapOf<VoiceHealthComponent, VoiceHealthRowBinding>()
    internal val voiceHealthRefresh = object : Runnable {
        override fun run() {
            if (!voiceHealthSurfaceVisible()) {
                voiceHealthRows.clear()
                return
            }
            refreshVoiceHealthRows()
            handler.postDelayed(this, 2_000L)
        }
    }
    internal var lastDebugSendKey: String? = null
    @Volatile internal var lastHistoryLoadedAt = 0L
    internal var pendingAsrModelSelection: String? = null
    internal val whisperBenchmarkProgress = ConcurrentHashMap<String, WhisperBenchmarkProgress>()
    internal val whisperBenchmarkNotices = ConcurrentHashMap<String, String>()
    internal val whisperBenchmarkRefreshScheduled = AtomicBoolean(false)
    internal val asrModelDownloadPoll = object : Runnable {
        override fun run() {
            val pendingId = pendingAsrModelSelection
            if (pendingId == null) {
                if (featurePage.visibility == View.VISIBLE &&
                    featureTitle.text == getString(R.string.voice_asr_provider)
                ) {
                    showAsrProviderPage()
                }
                return
            }
            val model = WhisperModelManager.model(pendingId)
            val state = WhisperModelManager.downloadState(this@MainActivity, model)
            when (state.status) {
                DownloadManager.STATUS_SUCCESSFUL -> {
                    VoiceAssistantSettings.setAsrModel(this@MainActivity, model.id)
                    pendingAsrModelSelection = null
                    Toast.makeText(this@MainActivity, getString(R.string.voice_asr_model_ready, model.displayName), Toast.LENGTH_SHORT).show()
                    if (hasWindowFocus()) ensureWhisperMicrophonePermission()
                    if (featurePage.visibility == View.VISIBLE) showAsrProviderPage()
                }
                DownloadManager.STATUS_FAILED -> {
                    pendingAsrModelSelection = null
                    Toast.makeText(this@MainActivity, getString(R.string.voice_asr_model_download_failed), Toast.LENGTH_LONG).show()
                    if (featurePage.visibility == View.VISIBLE) showAsrProviderPage()
                }
                else -> {
                    if (featurePage.visibility == View.VISIBLE && featureTitle.text == getString(R.string.voice_asr_provider)) {
                        showAsrProviderPage()
                    }
                    handler.postDelayed(this, 1_000L)
                }
            }
        }
    }

    internal val currentMessages: MutableList<ChatMessage>
        get() {
            val id = selectedContact?.id ?: return mutableListOf()
            return messages.getOrPut(id) { mutableListOf() }
        }

    internal fun isAgentActionTrayInitialized(): Boolean = ::agentActionTray.isInitialized
    internal fun isAgentGoalInputInitialized(): Boolean = ::agentGoalInput.isInitialized
    internal fun isAgentHandoffStoreInitialized(): Boolean = ::agentHandoffStore.isInitialized
    internal fun isAgentInsightBarInitialized(): Boolean = ::agentInsightBar.isInitialized
    internal fun isAgentInsightTextInitialized(): Boolean = ::agentInsightText.isInitialized
    internal fun isAgentMcpRegistryInitialized(): Boolean = ::agentMcpRegistry.isInitialized
    internal fun isAgentOutputLayoutInitialized(): Boolean = ::agentOutputLayout.isInitialized
    internal fun isAgentRunEventStoreInitialized(): Boolean = ::agentRunEventStore.isInitialized
    internal fun isAgentRunRecorderInitialized(): Boolean = ::agentRunRecorder.isInitialized
    internal fun isAgentTranscriptAdapterInitialized(): Boolean = ::agentTranscriptAdapter.isInitialized
    internal fun isAgentTranscriptStoreInitialized(): Boolean = ::agentTranscriptStore.isInitialized
    internal fun isEncryptedAgentRegistryInitialized(): Boolean = ::encryptedAgentRegistry.isInitialized
    internal fun isFeaturePageInitialized(): Boolean = ::featurePage.isInitialized
    internal fun isGlobalSuperAgentRuntimeInitialized(): Boolean = ::globalSuperAgentRuntime.isInitialized
    internal fun isHoldToTalkControllerInitialized(): Boolean = ::holdToTalkController.isInitialized
    internal fun isMobileNativeAgentInitialized(): Boolean = ::mobileNativeAgent.isInitialized
    internal fun isVoiceAgentRunBridgeInitialized(): Boolean = ::voiceAgentRunBridge.isInitialized
    internal fun isVoiceInteractionCoordinatorInitialized(): Boolean = ::voiceInteractionCoordinator.isInitialized

    internal fun newMessageId(): Long = ChatHistoryStore.reserveMessageId(this)

    override fun attachBaseContext(newBase: Context) {
        val localized = AppLanguage.wrap(newBase)
        super.attachBaseContext(localized)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // Agent capability events can be published during the long first launch. Mark the
        // activity foreground before that work so they do not start a deadline-bound FGS.
        AppForegroundTracker.onActivityForeground(this)
        val startupStartedAt = SystemClock.elapsedRealtime()
        var startupCheckpointAt = startupStartedAt
        fun traceStartup(stage: String) {
            if ((applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) == 0) return
            val now = SystemClock.elapsedRealtime()
            Log.i("GalaxySSIStartup", "$stage step=${now - startupCheckpointAt}ms total=${now - startupStartedAt}ms")
            startupCheckpointAt = now
        }
        AppDisplaySettings.applyToResources(this)
        super.onCreate(savedInstanceState)
        deviceProfile = AgentDeviceProfileDetector.detect(this)
        applyDeviceProfileWindowPolicy()
        configureSystemBars()
        setContentView(R.layout.activity_main)
        val startupConnectingView = findViewById<ConnectingStartupView>(R.id.startupConnectingView)
        traceStartup("content_view")
        AppStore.ensureInitialized(this)
        voiceInteractionCoordinator = VoiceInteractionCoordinatorRegistry.coordinator
        val voiceExecutionStore = AndroidVoiceExecutionRecordStore(this)
        voiceExecutionLedger = VoiceExecutionLedger(
            initialRecords = voiceExecutionStore.read(),
            persistence = voiceExecutionStore
        )
        voiceCorrectionJournal = VoiceCorrectionJournal(this)
        if (VoiceFeatureFlags.isCoordinatorEnabled(this)) {
            voiceCoordinatorObserverId = voiceInteractionCoordinator.observe { state ->
                if ((applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0) {
                    Log.d("GalaxySSIVoice", "Coordinator phase=${state.phase} revision=${state.revision}")
                }
            }
        }
        traceStartup("app_store")
        mobileNativeAgent = MobileNativeAgent(
            this,
            actionExecutor = directAgentActionExecutor,
            nativeToolEventSink = AgentNativeToolEventSink(::recordNativeToolLifecycleEvent)
        )
        agentRoutingExecutor.execute {
            runCatching { mobileNativeAgent.nativeToolCatalog() }
                .onFailure { Log.w("GalaxySSILatency", "native_tool_catalog_prewarm_failed", it) }
        }
        thread(name = "galaxyssi-control-plane-prewarm") {
            runCatching { directControlPlaneExecutor.warm() }
                .onFailure { Log.w("GalaxySSILatency", "control_plane_prewarm_failed", it) }
        }
        agentTranscriptStore = AgentTranscriptStore(this)
        navigationContentExecutor.execute {
            runCatching { agentTranscriptStore.prepareConversationPaging() }
                .onFailure { Log.w("GalaxySSILatency", "conversation_paging_prewarm_failed", it) }
        }
        AgentTaskRuntime.addLivenessListener(agentTaskLivenessListener)
        AgentTaskRuntime.supervisor(this)
        traceStartup("mobile_agent")
        globalSuperAgentRuntime = GlobalSuperAgentRuntime.get(this)
        agentRoutingExecutor.execute {
            runCatching { globalSuperAgentRuntime.prewarmContextSnapshot() }
                .onFailure { Log.w("GalaxySSILatency", "global_context_prewarm_failed", it) }
            runCatching { AndroidCognitionScheduler.requestImmediate(applicationContext) }
                .onFailure { Log.w("GalaxySSILatency", "background_cognition_restore_failed", it) }
        }
        traceStartup("global_runtime")
        openLatestGlobalInsightWhenDelivered = intent?.getBooleanExtra("galaxyssi_open_agent", false) == true
        requestedGlobalInsightConversationId = intent
            ?.getStringExtra("galaxyssi_agent_conversation_id")
            ?.trim()
            .orEmpty()
        intent?.removeExtra("galaxyssi_open_agent")
        intent?.removeExtra("galaxyssi_agent_conversation_id")
        requestedGlobalInsightConversationId.takeIf(String::isNotBlank)?.let(agentTranscriptStore::switchConversation)
        agentRunRecorder = AgentRunRecorder(this)
        agentRunEventStore = AgentRunEventStore(this)
        voiceAgentRunBridge = VoiceAgentRunBridge.get(this).also {
            it.addListener(voiceAgentRunListener)
        }
        agentHandoffStore = EncryptedAgentHandoffStore(this)
        encryptedAgentRegistry = EncryptedAgentRegistry(this)
        traceStartup("agent_registry")
        agentSkillRuntime = AgentSkillRuntime(
            store = EncryptedAgentSkillStore(this),
            availableNativeToolIds = mobileNativeAgent.nativeToolIds() + AGENT_ORCHESTRATION_TOOL_ID
        )
        traceStartup("skill_runtime")
        agentSkillMatcher = AgentSkillMatcher(agentSkillRuntime)
        agentLearningEngine = AgentLearningEngine(
            context = this,
            memoryStore = EncryptedAgentMemoryStore(this),
            skillRuntime = agentSkillRuntime,
            skillCompiler = AgentConversationSkillCompiler(agentSkillRuntime) {
                mobileNativeAgent.nativeToolCatalog()
            }
        )
        agentMcpRegistry = AgentMcpRegistry(EncryptedAgentMcpStore(this))
        remoteSelfEvolutionStore = EncryptedAgentRemoteSelfEvolutionStore(this)
        agentMcpPackageRepository = AgentMcpPackageRepository(this)
        agentRuntimePackCatalogManager = AgentRuntimePackCatalogManager(this)
        traceStartup("agent_stores")
        microsoftTts = MicrosoftEdgeTts(applicationContext)
        androidTts = TextToSpeech(this) { status ->
            androidTtsInitialized = true
            androidTtsReady = status == TextToSpeech.SUCCESS
            if (androidTtsReady) {
                configureAndroidTtsLanguage()
                androidTts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {
                        VoiceRuntimeHealthRegistry.begin(
                            VoiceRuntimeChannel.ANDROID_SYSTEM_TTS
                        )
                        runOnUiThread {
                            progressiveAndroidTtsRequests.started(utteranceId)?.onStarted?.invoke()
                        }
                    }
                    override fun onDone(utteranceId: String?) {
                        runOnUiThread {
                            val progressive = progressiveAndroidTtsRequests.finish(utteranceId)
                            if (progressive != null) {
                                VoiceRuntimeHealthRegistry.success(VoiceRuntimeChannel.ANDROID_SYSTEM_TTS)
                                progressive.onFinished(true)
                            } else {
                                onAndroidTtsFinished(utteranceId, success = true)
                            }
                        }
                    }
                    @Deprecated("Deprecated in Java")
                    override fun onError(utteranceId: String?) {
                        runOnUiThread {
                            val progressive = progressiveAndroidTtsRequests.finish(utteranceId)
                            if (progressive != null) {
                                VoiceRuntimeHealthRegistry.failure(
                                    VoiceRuntimeChannel.ANDROID_SYSTEM_TTS,
                                    "Android TTS utterance failed"
                                )
                                progressive.onFinished(false)
                            } else {
                                onAndroidTtsFinished(utteranceId, success = false)
                            }
                        }
                    }
                    override fun onStop(utteranceId: String?, interrupted: Boolean) {
                        runOnUiThread {
                            val progressive = progressiveAndroidTtsRequests.finish(utteranceId)
                            if (progressive != null) {
                                progressive.onFinished(false)
                            } else if (androidTtsRequests.discard(utteranceId)) {
                                voiceAssistantSpeaking = false
                            }
                        }
                    }
                })
            }
            if (::featurePage.isInitialized &&
                featurePage.visibility == View.VISIBLE &&
                controlCenterDestination?.route == ControlCenterRoute.VOICE
            ) {
                runOnUiThread { renderCurrentControlCenterDestination() }
            } else if (
                ::featurePage.isInitialized &&
                featurePage.visibility == View.VISIBLE &&
                featureTitle.text == getString(R.string.voice_tts_provider)
            ) {
                runOnUiThread { showTtsProviderPage() }
            }
        }

        wakePage = findViewById(R.id.wakePage)
        wakeAnimation = findViewById(R.id.wakeAnimation)
        mainPage = findViewById(R.id.mainPage)
        mainTopBar = findViewById(R.id.mainTopBar)
        agentPage = findViewById(R.id.agentPage)
        agentSessionTitle = findViewById(R.id.agentSessionTitle)
        agentSubtitleText = findViewById(R.id.agentSubtitleText)
        agentOutputList = findViewById(R.id.agentOutputList)
        agentSettingsButton = findViewById(R.id.agentSettingsButton)
        agentMemoryCaptureButton = findViewById(R.id.agentMemoryCaptureButton)
        agentToolboxList = findViewById(R.id.agentToolboxList)
        agentCurrentAppText = findViewById(R.id.agentCurrentAppText)
        agentCallableTargetsText = findViewById(R.id.agentCallableTargetsText)
        agentRunningTasksText = findViewById(R.id.agentRunningTasksText)
        agentMemoryText = findViewById(R.id.agentMemoryText)
        agentKnowledgeText = findViewById(R.id.agentKnowledgeText)
        agentScreenSearchInput = findViewById(R.id.agentScreenSearchInput)
        agentScreenDetailList = findViewById(R.id.agentScreenDetailList)
        agentActionQueueList = findViewById(R.id.agentActionQueueList)
        agentRequirementList = findViewById(R.id.agentRequirementList)
        agentPlanContextList = findViewById(R.id.agentPlanContextList)
        agentVerificationList = findViewById(R.id.agentVerificationList)
        agentAuditTrailList = findViewById(R.id.agentAuditTrailList)
        agentRecentTaskList = findViewById(R.id.agentRecentTaskList)
        agentGoalInput = findViewById(R.id.agentGoalInput)
        agentInsightBar = findViewById(R.id.agentInsightBar)
        agentInsightText = findViewById(R.id.agentInsightText)
        agentComposerRow = findViewById(R.id.agentComposerRow)
        agentPrimaryActionSlot = findViewById(R.id.agentPrimaryActionSlot)
        agentActionTray = findViewById(R.id.agentAttachmentActionTray)
        agentRecordingCenter = findViewById(R.id.agentRecordingCenter)
        agentRecordingInstruction = findViewById(R.id.agentRecordingInstruction)
        agentRecordingWaveform = findViewById(R.id.agentRecordingWaveform)
        agentRecordingTranscript = findViewById(R.id.agentRecordingTranscript)
        agentRecordingTimer = findViewById(R.id.agentRecordingTimer)
        agentAttachButton = findViewById(R.id.agentAttachButton)
        agentSubmitButton = findViewById(R.id.agentSubmitButton)
        agentBrandLogo = findViewById(R.id.agentBrandLogo)
        applyAgentBrandLogoTextScale()
        agentAttachmentPreviewScroll = findViewById(R.id.agentAttachmentPreviewScroll)
        agentAttachmentPreviewList = findViewById(R.id.agentAttachmentPreviewList)
        discoverPage = findViewById(R.id.discoverPage)
        mePage = findViewById(R.id.mePage)
        featurePage = findViewById(R.id.featurePage)
        featureTitle = findViewById(R.id.featureTitle)
        featureContent = findViewById(R.id.featureContent)
        featureBackButton = findViewById(R.id.featureBackButton)
        controlCenterRenderer = ControlCenterRenderer(this)
        mainTitle = findViewById(R.id.mainTitle)
        mainBackButton = findViewById(R.id.mainBackButton)
        mainActionButton = findViewById(R.id.mainActionButton)
        chatPage = findViewById(R.id.chatPage)
        statusDot = findViewById(R.id.statusDot)
        contactStatusDot = findViewById(R.id.contactStatusDot)
        messageInput = findViewById(R.id.messageInput)
        sendButton = findViewById(R.id.sendButton)
        imageButton = findViewById(R.id.imageButton)
        chatComposerRow = findViewById(R.id.chatComposerRow)
        chatPrimaryActionSlot = findViewById(R.id.chatPrimaryActionSlot)
        chatRecordingCenter = findViewById(R.id.chatRecordingCenter)
        chatRecordingInstruction = findViewById(R.id.chatRecordingInstruction)
        chatRecordingWaveform = findViewById(R.id.chatRecordingWaveform)
        chatRecordingTranscript = findViewById(R.id.chatRecordingTranscript)
        chatRecordingTimer = findViewById(R.id.chatRecordingTimer)
        chatTitle = findViewById(R.id.chatTitle)
        chatHeaderContent = findViewById(R.id.chatHeaderContent)
        chatSubtitleRow = findViewById(R.id.chatSubtitleRow)
        chatModelTag = findViewById(R.id.chatModelTag)
        chatModelButton = findViewById(R.id.chatModelButton)
        chatModelLabel = findViewById(R.id.chatModelLabel)
        chatSubtitle = findViewById(R.id.chatSubtitle)
        chatAvatar = findViewById(R.id.chatAvatar)
        backButton = findViewById(R.id.backButton)
        securityButton = findViewById(R.id.securityButton)
        chatInputBar = findViewById(R.id.chatInputBar)
        applyDeviceProfileInputTargets()
        messageList = findViewById(R.id.messageList)
        traceStartup("view_binding")

        loadChatHistory()
        traceStartup("chat_history")
        configureMainTabs()
        configureAgentPage()
        traceStartup("agent_page")
        configureMessages()
        configureInput()
        configureWakePage()
        traceStartup("chat_pages")
        configureSettingsControlCenter()
        refreshMePage()
        traceStartup("control_center")
        startMessageService()
        showMainTab(PAGE_AGENT)
        reopenRequestedControlCenterChild(intent)
        requestAgentNotificationPermissionIfNeeded()
        traceStartup("first_render")
        scheduleAgentInitialHydration()
        scheduleAgentStartupMaintenance()
        scheduleNavigationContentPrewarm()

        GalaxySSIMqttClient.addListener(this)
        GalaxySSIMqttClient.connect(this)
        handler.postDelayed({
            intent?.getStringExtra("galaxyssi_open_contact_id")
                ?.takeIf(String::isNotBlank)
                ?.let { contactId -> showChatPage(contactById(contactId)) }
            intent?.removeExtra("galaxyssi_open_contact_id")
            handleDebugSendIntent(intent)
            handleDebugIncomingIntent(intent)
        }, 1200)
        startupConnectingView.finishWhenReady()
        traceStartup("on_create_complete")
    }





    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent?.getBooleanExtra("galaxyssi_open_agent", false) == true) {
            requestedGlobalInsightConversationId = intent
                .getStringExtra("galaxyssi_agent_conversation_id")
                ?.trim()
                .orEmpty()
            intent.removeExtra("galaxyssi_open_agent")
            intent.removeExtra("galaxyssi_agent_conversation_id")
            openLatestGlobalInsightWhenDelivered = true
            requestedGlobalInsightConversationId.takeIf(String::isNotBlank)?.let(agentTranscriptStore::switchConversation)
            showMainTab(PAGE_AGENT)
            renderAgentState(mobileNativeAgent.reloadSession())
            refreshGlobalAgentCognition()
        }
        intent?.getStringExtra("galaxyssi_open_contact_id")
            ?.takeIf(String::isNotBlank)
            ?.let { contactId ->
                intent.removeExtra("galaxyssi_open_contact_id")
                showChatPage(contactById(contactId))
            }
        handleDebugSendIntent(intent)
        handleDebugIncomingIntent(intent)
    }






    override fun onDestroy() {
        initialAgentHydrationReady.countDown()
        if (::voiceInteractionCoordinator.isInitialized && voiceCoordinatorObserverId.isNotBlank()) {
            voiceInteractionCoordinator.removeObserver(voiceCoordinatorObserverId)
            voiceCoordinatorObserverId = ""
        }
        DesktopRemoteControl.stopAllScreenshotStreams()
        handler.removeCallbacks(asrModelDownloadPoll)
        handler.removeCallbacks(voiceHealthRefresh)
        handler.removeCallbacks(agentStartupMaintenanceRunnable)
        handler.removeCallbacks(voiceAgentRunCardRefresh)
        pendingVoiceAgentRunCardUpdates.clear()
        if (::voiceAgentRunBridge.isInitialized) {
            voiceAgentRunBridge.removeListener(voiceAgentRunListener)
        }
        agentConnectorTimeoutCallbacks.values.forEach(handler::removeCallbacks)
        agentConnectorTimeoutCallbacks.clear()
        stopVoiceAssistant()
        microsoftTts.shutdown()
        androidTts?.stop()
        androidTts?.shutdown()
        if (::holdToTalkController.isInitialized) holdToTalkController.release()
        if (::agentHoldToTalkController.isInitialized) agentHoldToTalkController.release()
        liveWhisperSessions.values.forEach(LiveWhisperTranscriptionSession::close)
        liveWhisperSessions.clear()
        highAccuracyAsrTurns.values.forEach(HighAccuracyLocalAsrTurn::cancel)
        highAccuracyAsrTurns.clear()
        highAccuracyAsrFinals.clear()
        if (highAccuracyAsrControllerDelegate.isInitialized()) highAccuracyAsrController.close()
        onlineRealtimeAsrTurns.values.forEach(OnlineRealtimeAsrTurn::close)
        onlineRealtimeAsrTurns.clear()
        onlineRealtimeAsrFinals.clear()
        if (onlineRealtimeAsrPreconnectorLazy.isInitialized()) onlineRealtimeAsrPreconnector.close()
        if (onlineRealtimeAsrProviderLazy.isInitialized()) onlineRealtimeAsrProvider.close()
        voiceRiskConfirmationCancellation?.invoke()
        voiceRiskConfirmationCancellation = null
        voiceRiskConfirmationDialog?.dismiss()
        voiceRiskConfirmationDialog = null
        voiceSecondPassCoordinator.cancelForInteractiveVoice()
        remoteWhisperNodeClient.cancelAll()
        activeCloudStreams.values.forEach { stream ->
            stream.flushRunnable?.let(handler::removeCallbacks)
            stream.sentenceCommitRunnable?.let(handler::removeCallbacks)
        }
        if (::microsoftTts.isInitialized) progressiveTtsScheduler.cancelActive(TtsCancelReason.APP_DESTROYED)
        progressiveAndroidTtsRequests.clear()
        releaseVoicePlaybackAudioFocus()
        activeCloudStreamJobs.values.forEach(Job::cancel)
        activeCloudStreamJobs.clear()
        activeCloudStreams.clear()
        whisperDecodeScheduler?.close()
        whisperDecodeScheduler = null
        player?.let { activePlayer ->
            runCatching { activePlayer.release() }
        }
        player = null
        peerAudioDataSource?.close()
        peerAudioDataSource = null
        stopRecording(send = false)
        pcmVoiceSession?.let { session ->
            pcmVoiceAudioHub?.requestStop(session, PcmStopReason.USER_CANCEL)
        }
        voiceAssistantScope.cancel()
        flushChatHistoryAsync()
        GalaxySSIMqttClient.removeListener(this)
        AgentTaskRuntime.removeLivenessListener(agentTaskLivenessListener)
        ScreenPerceptionState.removeVisualListener(agentVisualScreenListener)
        if (::agentRuntimePackCatalogManager.isInitialized) agentRuntimePackCatalogManager.close()
        agentRegistryHeartbeatExecutor.shutdown()
        agentSubmissionExecutor.shutdown()
        outboundMessageExecutor.shutdown()
        agentRoutingExecutor.shutdown()
        agentRuntimeRecoveryExecutor.shutdown()
        agentRouteSelectionExecutor.shutdown()
        agentTaskPersistenceExecutor.shutdown()
        agentEvalExecutor.shutdown()
        agentTaskLivenessExecutor.shutdown()
        agentTaskEventExecutor.shutdown()
        navigationContentExecutor.shutdown()
        agentTranscriptContentExecutor.shutdown()
        historyExecutor.shutdown()
        cloudExecutor.shutdown()
        super.onDestroy()
    }

    override fun onResume() {
        val resumeStartedAt = SystemClock.elapsedRealtime()
        var resumeCheckpointAt = resumeStartedAt
        fun traceResume(stage: String) {
            if ((applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) == 0) return
            val now = SystemClock.elapsedRealtime()
            Log.i("GalaxySSIStartup", "resume_$stage step=${now - resumeCheckpointAt}ms total=${now - resumeStartedAt}ms")
            resumeCheckpointAt = now
        }
        super.onResume()
        val restoredRuntimePlaintext = restoreRuntimePlaintextAfterForeground()
        if (restoredRuntimePlaintext) {
            navigationContentExecutor.execute {
                runCatching { agentTranscriptStore.prepareConversationPaging() }
                    .onFailure { Log.w("GalaxySSILatency", "conversation_paging_resume_prewarm_failed", it) }
            }
        }
        GalaxySSIMqttClient.addListener(this)
        traceResume("super")
        if (AppDisplaySettings.synchronizeNightMode(this)) {
            recreate()
            return
        }
        traceResume("display")
        val resumedConversationId = selectedContact?.id?.takeIf {
            chatPage.visibility == View.VISIBLE
        }
        AppForegroundTracker.onActivityForeground(this, resumedConversationId)
        AgentConnectorResponseBus.addListener(agentConnectorResponseListener)
        AndroidAgentRecoveryWake.request(this)
        AgentConnectorStreamBus.addListener(agentConnectorStreamListener)
        GlobalProactiveDeliveryBus.addListener(globalProactiveDeliveryListener)
        ScreenPerceptionState.addVisualListener(agentVisualScreenListener)
        if (isHighAccuracyQnnSelected() || highAccuracyAsrControllerDelegate.isInitialized()) {
            highAccuracyAsrController.onAppForegroundChanged(true)
            highAccuracyAsrController.onMicrophonePermissionChanged(
                checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
            )
        }
        prepareHighAccuracyAsrIfSelected()
        traceResume("listeners")
        val initialResume = !completedInitialResume
        val reloadedAgentState = if (!initialResume) {
            mobileNativeAgent.reloadSession()
        } else {
            completedInitialResume = true
            null
        }
        traceResume("agent_session")
        if (!initialResume) requestAgentRegistrySnapshotSync()
        traceResume("registry")
        val restoredAgentState = reloadedAgentState?.let { state ->
            if (state.runningTaskCount == 0 && ScreenPerceptionState.hasRecentVisualCapture()) {
                mobileNativeAgent.observeCurrentScreen()
            } else {
                state
            }
        }
        if (activeMainTab == PAGE_AGENT && !initialAgentHydrationPending && restoredAgentState != null) {
            renderAgentState(restoredAgentState)
        } else if (restoredRuntimePlaintext && activeMainTab == PAGE_AGENT && !initialAgentHydrationPending) {
            renderAgentState(mobileNativeAgent.reloadSession())
        }
        traceResume("agent_render")
        if (featurePage.visibility == View.VISIBLE && controlCenterDestination != null) {
            renderCurrentControlCenterDestination()
        }
        traceResume("control_center")
        if (!initialResume) consumePendingAgentConnectorResponses()
        traceResume("connector_responses")
        reloadChatHistoryIfChanged()
        traceResume("chat_history")
        if (!initialResume) maintainMcpCredentials()
        traceResume("mcp")
        if (!initialResume) refreshGlobalAgentCognition()
        activeDesktopControlId
            ?.takeIf { featurePage.visibility == View.VISIBLE }
            ?.let(DesktopRemoteControl::resumeScreenshotStream)
        traceResume("complete")
    }


    override fun onPause() {
        AppForegroundTracker.onActivityBackground(this)
        GalaxySSIMqttClient.removeListener(this)
        if (highAccuracyAsrControllerDelegate.isInitialized()) {
            highAccuracyAsrController.onAppForegroundChanged(false)
        }
        if (!isChangingConfigurations && isVoiceCaptureActive()) {
            if (pcmVoiceSession != null) {
                stopPcmRecording(send = false, reason = "app_background")
            } else {
                when (recordingPurpose) {
                    "voice_wakeup" -> stopVoiceCommandRecording(send = false, reason = "app_background")
                    "agent_input" -> stopAgentInputRecording(send = false)
                    else -> stopRecording(send = false)
                }
            }
        }
        DesktopRemoteControl.pauseScreenshotStreams()
        AgentConnectorResponseBus.removeListener(agentConnectorResponseListener)
        AgentConnectorStreamBus.removeListener(agentConnectorStreamListener)
        handler.removeCallbacks(agentConnectorStreamRefreshRunnable)
        pendingAgentConnectorStreamUpdates.clear()
        agentConnectorStreamAttempts.clear()
        pendingAgentConnectorStreamRetirements.clear()
        agentConnectorStreamRefreshScheduled.set(false)
        GlobalProactiveDeliveryBus.removeListener(globalProactiveDeliveryListener)
        ScreenPerceptionState.removeVisualListener(agentVisualScreenListener)
        clearRuntimePlaintextForBackground()
        super.onPause()
    }

    override fun onStop() {
        clearRuntimePlaintextForBackground()
        super.onStop()
    }

    override fun onTrimMemory(level: Int) {
        AgentEncryptedPreferenceCache.clearAll()
        if (level >= android.content.ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN) {
            clearRuntimePlaintextForBackground()
        } else if (::agentTranscriptStore.isInitialized) {
            agentTranscriptStore.clearRuntimeDecodeCache()
        }
        super.onTrimMemory(level)
    }









    internal fun AgentPhase.isTerminalAgentPhase(): Boolean = this in setOf(
        AgentPhase.COMPLETED,
        AgentPhase.FAILED,
        AgentPhase.CANCELLED,
        AgentPhase.BLOCKED
    )









    override fun onBackPressed() {
        if (SystemClock.elapsedRealtime() - agentComposerKeyboardClosedAt < 700L) {
            agentComposerKeyboardClosedAt = 0L
            return
        }
        if (SystemClock.elapsedRealtime() - chatComposerKeyboardClosedAt < 700L) {
            chatComposerKeyboardClosedAt = 0L
            return
        }
        if (::agentActionTray.isInitialized && agentActionTrayExpanded) {
            setAgentActionTrayExpanded(false)
            return
        }
        if (collapseChatActionTrayOnBack()) return
        if (::agentGoalInput.isInitialized && agentComposerTextMode) {
            exitAgentComposerTextMode(hideKeyboard = true)
            return
        }
        if (::messageInput.isInitialized && chatComposerTextMode) {
            exitChatComposerTextMode(hideKeyboard = true)
            return
        }
        if (featurePage.visibility == View.VISIBLE) {
            performFeatureBack()
            return
        }
        if (chatPage.visibility == View.VISIBLE) {
            returnFromContactChatToConversationHub()
            return
        }
        if (mainPage.visibility == View.VISIBLE && activeMainTab != PAGE_AGENT) {
            showMainTab(if (activeMainTab == PAGE_SETTINGS) PAGE_AGENT else PAGE_SETTINGS)
            return
        }
        super.onBackPressed()
    }

    override fun onConnectionChanged(connected: Boolean) {
        runOnUiThread {
            statusDot.setBackgroundResource(if (connected) R.drawable.status_dot_online else R.drawable.status_dot_offline)
        }
    }

    override fun onSecureChannelChanged(ready: Boolean) {
        secureChannelReady = ready
        runOnUiThread {
            contactStatusDot.setBackgroundResource(if (ready) R.drawable.status_dot_online else R.drawable.status_dot_offline)
        }
    }

    override fun onDeliveryFailed(sourceMessageId: Long, contactId: String, reason: String) {
        if (sourceMessageId <= 0L) return
        runOnUiThread {
            markPeerAttachmentTransferFailed(sourceMessageId, contactId)
            updateMessageStatus(
                sourceMessageId,
                contactId,
                getString(R.string.delivery_status_failed)
            )
        }
        val directBinding = pendingDirectConnectorRuns.remove(sourceMessageId)
        if (directBinding != null) {
            cancelConnectorTimeouts(sourceMessageId)
            pendingDirectConnectorActions.remove(directBinding.turnId)?.let { action ->
                recordDirectAgentRun(
                    turnId = directBinding.turnId,
                    action = action,
                    result = AgentActionResult(
                        actionId = action.id,
                        success = false,
                        message = getString(R.string.agent_message_not_delivered),
                        metadata = mapOf(
                            "source_message_id" to sourceMessageId.toString(),
                            "contact_id" to directBinding.contactId,
                            "conversation_id" to directBinding.conversationId,
                            "turn_id" to directBinding.turnId,
                            "task_id" to directBinding.taskId
                        )
                    )
                )
            }
            finishAgentDeliveryFailure(sourceMessageId, contactId, directBinding)
            logDeliveryFailure(sourceMessageId, contactId, reason)
            return
        }
        val runtime = runtimeForConnectorResponse(
            sourceMessageId,
            contactId,
            allowTransportOnly = true
        )
        if (runtime == null) {
            val delivery = AgentDeliveryFailureRecorder.record(
                this,
                sourceMessageId,
                contactId,
                getString(R.string.agent_message_not_delivered)
            )
            if (delivery != null) {
                runOnUiThread { finishAgentDeliveryFailureUi(delivery) }
            }
            logDeliveryFailure(sourceMessageId, contactId, reason)
            return
        }
        cancelConnectorTimeouts(sourceMessageId)
        val conversationId = agentRuntimeConversationIds[runtime].orEmpty()
        val turnId = agentRuntimeTurnIds[runtime].orEmpty()
        thread(name = "galaxyssi-delivery-failed-$sourceMessageId") {
            bindAgentExecutionLoop(runtime, turnId)
            var state = runtime.handleConnectorDeliveryFailure(
                sourceMessageId,
                getString(R.string.agent_message_not_delivered)
            ) ?: return@thread
            if (turnId.isNotBlank()) {
                state = finalizeAgentExecutionLoop(runtime, turnId, state)
                persistAgentWorkspaceSnapshot(turnId, state, runtime)
            }
            if (state.phase.isTerminalAgentPhase()) {
                val delivery = AgentDeliveryFailureRecorder.record(
                    this,
                    sourceMessageId,
                    contactId,
                    getString(R.string.agent_message_not_delivered)
                ) ?: AgentPendingDelivery(
                    sourceMessageId = sourceMessageId,
                    conversationId = conversationId,
                    turnId = turnId,
                    taskId = state.sessionId.ifBlank { turnId },
                    contactId = contactId
                ).also { fallback ->
                    agentTranscriptStore.upsert(
                        role = AgentTranscriptRole.ASSISTANT,
                        text = getString(R.string.agent_message_not_delivered),
                        dedupeKey = AgentDeliveryFailureRecorder.dedupeKey(sourceMessageId),
                        conversationId = fallback.conversationId,
                        turnId = fallback.turnId,
                        taskId = fallback.taskId
                    )
                }
                runOnUiThread {
                    renderAgentState(state, conversationId, turnId, syncTranscript = false)
                    finishAgentDeliveryFailureUi(delivery)
                }
            } else {
                val nextSourceMessageId = state.lastActionResult?.metadata
                    ?.get("source_message_id")
                    ?.toLongOrNull()
                    ?.takeIf { it > 0L && it != sourceMessageId }
                if (nextSourceMessageId != null) {
                    AgentPendingDeliveryStore.markRecoveryPredecessor(
                        this,
                        sourceMessageId,
                        nextSourceMessageId
                    )
                }
                runOnUiThread {
                    rebindAgentConnectorContinuation(
                        previousSourceMessageId = sourceMessageId,
                        runtime = runtime,
                        state = state,
                        conversationId = conversationId,
                        turnId = turnId
                    )
                    renderAgentState(state, conversationId, turnId)
                }
            }
        }
        logDeliveryFailure(sourceMessageId, contactId, reason)
    }

    override fun onMessage(payload: String) {
        val taskEnvelope = runCatching { JSONObject(payload) }.getOrNull()
            ?.takeIf { envelope -> envelope.optString("type") == "agent_task_event" }
        if (taskEnvelope != null) {
            runCatching {
                agentTaskEventExecutor.execute { handleAgentTaskEvent(taskEnvelope) }
            }.onFailure { error ->
                if (!isFinishing && !isDestroyed) {
                    Log.w("GalaxySSIAgent", "Agent task event could not be scheduled", error)
                }
            }
            return
        }
        runOnUiThread {
            var handled = true
            try {
                val envelope = runCatching { JSONObject(payload) }.getOrNull()
                envelope?.optString("desktop_id")?.takeIf(String::isNotBlank)?.let(::markDesktopDomainAvailableById)
                if (envelope != null && handlePeerAttachmentTransferProgress(envelope)) {
                    return@runOnUiThread
                }
                if (envelope != null && remoteWhisperNodeClient.handleIncoming(
                        envelope,
                        envelope.optString("desktop_id")
                    )
                ) return@runOnUiThread
                if (envelope?.optString("type") == "delivery_ack") {
                    val acknowledgedId = GalaxySSILinkDeliveryAckPolicy.clientSourceMessageId(envelope)
                        .toLongOrNull()
                    if (acknowledgedId != null) {
                        val runtime = runtimeForConnectorResponse(
                            acknowledgedId,
                            "",
                            allowTransportOnly = true
                        )
                        val acceptedState = runtime?.recordConnectorTransportAccepted(acknowledgedId)
                        val delivery = AgentPendingDeliveryStore.find(this, acknowledgedId)
                        if (runtime != null && acceptedState != null && delivery != null) {
                            cancelConnectorTimeouts(acknowledgedId)
                            scheduleConnectorTimeouts(
                                runtime = runtime,
                                sourceMessageId = acknowledgedId,
                                conversationId = delivery.conversationId,
                                turnId = delivery.turnId
                            )
                        }
                    }
                }
                if (envelope?.optString("type") == "artifact_available") {
                    if (::agentTranscriptAdapter.isInitialized) {
                        agentTranscriptAdapter.notifyDataSetChanged()
                    }
                    messageAdapter?.syncMessages(currentMessages)
                    val savedPath = envelope.optString("saved_path")
                    if (savedPath.isNotBlank()) {
                        Toast.makeText(
                            this,
                            getString(R.string.rich_output_downloaded, savedPath),
                            Toast.LENGTH_SHORT
                        ).show()
                    } else if (envelope.optBoolean("save_requested")) {
                        Toast.makeText(this, R.string.rich_output_download_failed, Toast.LENGTH_SHORT).show()
                    }
                    return@runOnUiThread
                }
                if (envelope?.optString("type") == "artifact_download_failed") {
                    Toast.makeText(this, R.string.rich_output_download_failed, Toast.LENGTH_SHORT).show()
                    return@runOnUiThread
                }
                if (envelope?.optString("type") == "phone_contact_request_received") {
                    refreshDirectoryContacts()
                    if (showingFriendRequests) showFriendRequestsDialog()
                    Toast.makeText(
                        this,
                        getString(
                            R.string.phone_contact_request_received,
                            envelope.optString("name", getString(R.string.fallback_contact_name))
                        ),
                        Toast.LENGTH_LONG
                    ).show()
                    return@runOnUiThread
                }
                if (envelope?.optString("type") == "phone_contact_session_ready") {
                    refreshDirectoryContacts()
                    return@runOnUiThread
                }
                if (envelope?.optString("type") == "phone_contact_request_approved") {
                    val contactId = envelope.optString("contact_id")
                    val refreshFriendRequests = showingFriendRequests ||
                        activeFriendRequestContactId == contactId
                    reloadChatHistoryIfChanged(force = true)
                    refreshDirectoryContacts()
                    if (refreshFriendRequests) showFriendRequestsDialog()
                    Toast.makeText(
                        this,
                        getString(
                            R.string.phone_contact_request_approved,
                            envelope.optString("name", getString(R.string.fallback_contact_name))
                        ),
                        Toast.LENGTH_LONG
                    ).show()
                    return@runOnUiThread
                }
                if (envelope?.optString("type") == "phone_contact_request_rejected") {
                    val contactId = envelope.optString("contact_id")
                    val refreshFriendRequests = showingFriendRequests ||
                        activeFriendRequestContactId == contactId
                    reloadChatHistoryIfChanged(force = true)
                    refreshDirectoryContacts()
                    if (refreshFriendRequests) showFriendRequestsDialog()
                    Toast.makeText(
                        this,
                        getString(
                            R.string.phone_contact_request_rejected,
                            envelope.optString("name", getString(R.string.fallback_contact_name))
                        ),
                        Toast.LENGTH_LONG
                    ).show()
                    return@runOnUiThread
                }
                if (handleAgentTaskApprovalResult(envelope)) return@runOnUiThread
                if (handleDesktopRemoteControlEvent(envelope)) return@runOnUiThread
                if (handleSelfEvolutionEvent(envelope)) return@runOnUiThread
                if (handleAgentTaskEvent(envelope)) return@runOnUiThread
                val msg = parseIncomingMessage(payload)
                if (msg.content.isBlank() && msg.attachments.isEmpty()) return@runOnUiThread
                markDesktopDomainAvailable(msg.contact.id)
                if (envelope?.optString("type") == "peer_message") {
                    msg.deliveryTrace.add(newTraceEvent("received", "MQTT inbound"))
                    msg.deliveryTrace.add(newTraceEvent("decrypted", "GalaxySSI Link"))
                    if (!mergeCompletedPeerAttachmentMessage(msg)) {
                        addMessage(msg, fromIncoming = true)
                    }
                    return@runOnUiThread
                }
                if (msg.taskId.isNotBlank() && messages[msg.contact.id].orEmpty().any {
                        !it.isMine && it.taskId == msg.taskId && it.content == msg.content
                    }
                ) return@runOnUiThread
                val sourceMessageId = envelope?.optString("source_message_id")?.toLongOrNull()
                    ?: envelope?.optLong("source_message_id", 0L)
                    ?: 0L
                val responseConversationId = envelope?.optString("conversation_id").orEmpty()
                val resolvedResponseConversationId = responseConversationId.takeIf(String::isNotBlank)
                    ?.let(agentTranscriptStore::resolveMergedConversationId)
                    .orEmpty()
                val responseTurnId = envelope?.optString("turn_id").orEmpty()
                val responseTaskId = envelope?.optString("task_id").orEmpty()
                if (sourceMessageId > 0L &&
                    AgentTerminalDeliveryStore.isTerminal(this@MainActivity, sourceMessageId)
                ) {
                    Log.i("GalaxySSIAgent", "Ignored inbound result for terminal source=$sourceMessageId")
                    return@runOnUiThread
                }
                val supersededResponse = sourceMessageId > 0L && sourceMessageId in supersededConnectorSourceIds
                val matchingAgentRuntime = if (sourceMessageId > 0L) {
                    runtimeForConnectorResponse(
                        sourceMessageId,
                        msg.contact.id,
                        responseConversationId,
                        responseTurnId,
                        responseTaskId
                    )
                } else null
                val originatingChatMessage = messages[msg.contact.id]
                    ?.firstOrNull { it.isMine && it.id == sourceMessageId }
                val nativeAgentResponse = AgentTaskIdentityPolicy.routesToMainAgent(
                    superseded = supersededResponse,
                    hasRuntime = matchingAgentRuntime != null,
                    resolvedConversationId = resolvedResponseConversationId
                )
                val managedAgentResponse = sourceMessageId > 0L &&
                    AgentManagedConnectorResponseRegistry.contains(AgentConnectorResponse(
                        sourceMessageId = sourceMessageId,
                        contactId = envelope?.optString("contact_id").orEmpty().ifBlank { msg.contact.id },
                        content = msg.content,
                        conversationId = responseConversationId,
                        turnId = responseTurnId,
                        taskId = responseTaskId
                    ))
                if (!nativeAgentResponse && originatingChatMessage != null) {
                    val expectedConversationId = AgentTaskIdentityPolicy.conversationId(
                        msg.contact.id,
                        ""
                    )
                    val expectedTurnId = AgentTaskIdentityPolicy.turnId(sourceMessageId, "")
                    val expectedTaskId = AgentTaskIdentityPolicy.taskId(
                        ownerId = GalaxySSICrypto.localGalaxySSIId(),
                        contactId = msg.contact.id,
                        sourceMessageId = sourceMessageId,
                        conversationId = expectedConversationId,
                        turnId = expectedTurnId
                    )
                    if (responseConversationId != expectedConversationId ||
                        responseTurnId != expectedTurnId ||
                        responseTaskId != expectedTaskId
                    ) {
                        Log.w("GalaxySSILink", "Rejected Agent response outside its originating chat turn")
                        return@runOnUiThread
                    }
                }
                if ((nativeAgentResponse || managedAgentResponse) && publishAgentConnectorResponse(envelope, msg)) {
                    return@runOnUiThread
                }
                val voiceTraceId = envelope?.optString("trace_id").orEmpty()
                if (voiceTraceId.isNotBlank()) {
                    activeVoiceTraceId = voiceTraceId
                    VoiceLatencyTelemetry.record(
                        this@MainActivity,
                        voiceTraceId,
                        VoiceTraceEvents.AGENT_FIRST_PARTIAL_RESULT,
                        mapOf("agent_provider" to envelope?.optString("agent_id", "remote_agent").orEmpty()),
                        once = true
                    )
                    VoiceLatencyTelemetry.record(
                        this@MainActivity,
                        voiceTraceId,
                        VoiceTraceEvents.AGENT_COMPLETED,
                        mapOf("task_status" to "completed", "success" to "true"),
                        once = true
                    )
                }
                msg.deliveryTrace.add(newTraceEvent("phone_reply_received", msg.taskId))
                msg.deliveryTrace.add(newTraceEvent("received", "MQTT inbound"))
                msg.deliveryTrace.add(newTraceEvent("decrypted", "GalaxySSI Link"))
                addMessage(msg, fromIncoming = true)
                if (supersededResponse) supersededConnectorSourceIds.remove(sourceMessageId)
                if (responseTaskId.isNotBlank()) {
                    completedConnectorTaskIds.add(AgentRemoteOutcomeCodec.taskKey(responseTaskId,
                        envelope?.let(AgentRemoteOutcomeCodec::version)?.generation ?: 1L))
                }
                if (!nativeAgentResponse && resolvedResponseConversationId.isNotBlank()) {
                    agentTranscriptContentExecutor.execute {
                        val conversationEntries = agentTranscriptStore.list(resolvedResponseConversationId)
                        val directResponseTurnId = AgentLateConnectorResponsePolicy.exactTurnId(
                            explicitTurnId = responseTurnId,
                            taskTurnId = responseTaskId.takeIf(String::isNotBlank)
                                ?.let(agentTranscriptStore::turnIdForTask)
                                .orEmpty(),
                            indexedTurnId = "",
                            conversationEntries = conversationEntries
                        )
                        if (!AgentLateConnectorResponsePolicy.canAccept(
                                sourceIsTerminal = false,
                                exactTurnId = directResponseTurnId,
                                conversationEntries = conversationEntries
                            )
                        ) return@execute
                        val exactTurnId = checkNotNull(directResponseTurnId)
                        agentTranscriptStore.upsert(
                            AgentTranscriptRole.ASSISTANT,
                            msg.content,
                            dedupeKey = AgentFinalResponseIdentity.dedupeKey(
                                turnId = exactTurnId,
                                sourceMessageId = sourceMessageId,
                                taskId = responseTaskId
                            ),
                            conversationId = resolvedResponseConversationId,
                            turnId = exactTurnId,
                            taskId = responseTaskId,
                            richOutputJson = AgentRichContentCodec.fromEnvelope(envelope)
                        )
                        if (resolvedResponseConversationId == agentTranscriptStore.activeConversation().id) {
                            requestAgentTranscriptWindowRefresh(resolvedResponseConversationId)
                        }
                    }
                }
                if (!nativeAgentResponse) {
                    showVoiceAssistantReply(msg)
                    maybeSpeakIncomingReply(msg, voiceTraceId)
                }
            } catch (error: Throwable) {
                handled = false
                Log.e("GalaxySSILink", "Deferred inbound message after UI handling failure", error)
            } finally {
                if (handled) GalaxySSIMqttClient.completeIncomingDelivery(this, payload)
            }
        }
    }


    internal fun JSONArray?.orEmptyJsonObjects(): List<JSONObject> {
        val array = this ?: return emptyList()
        return buildList {
            for (index in 0 until array.length()) {
                array.optJSONObject(index)?.let(::add)
            }
        }
    }












    override fun onPcInfo(ip: String, port: Int) {
        fileServerBaseUrl = "http://$ip:$port"
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_AGENT_SCREEN_CAPTURE) {
            if (resultCode == RESULT_OK && data != null) {
                AgentScreenCaptureService.start(this, resultCode, data)
                Toast.makeText(this, getString(R.string.agent_screen_capture_started), Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, getString(R.string.agent_screen_capture_permission_denied), Toast.LENGTH_SHORT).show()
            }
            return
        }
        if (requestCode == REQUEST_AGENT_CAMERA) {
            val uri = pendingAgentCameraUri
            pendingAgentCameraUri = null
            if (resultCode == RESULT_OK && uri != null) {
                addAgentInputUris(listOf(uri))
            } else if (uri != null) {
                contentResolver.delete(uri, null, null)
            }
            return
        }
        if (handleChatCameraActivityResult(requestCode, resultCode)) return
        if (requestCode == REQUEST_AGENT_ATTACHMENTS || requestCode == REQUEST_AGENT_IMAGES) {
            if (resultCode == RESULT_OK && data != null) {
                val uris = buildList {
                    data.clipData?.let { clips ->
                        for (index in 0 until clips.itemCount) add(clips.getItemAt(index).uri)
                    }
                    data.data?.let { if (it !in this) add(it) }
                }
                uris.forEach { uri ->
                    runCatching {
                        contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                }
                addAgentInputUris(uris)
            }
            return
        }
        val scanResult = IntentIntegrator.parseActivityResult(requestCode, resultCode, data)
        if (scanResult != null) {
            handleSecurityScan(scanResult.contents, autoConfirm = true)
            return
        }
        if (requestCode == REQUEST_IMPORT_BACKUP && resultCode == RESULT_OK) {
            importBackupFromUri(data?.data ?: return)
            return
        }
        if (requestCode == REQUEST_IMPORT_KNOWLEDGE) {
            if (resultCode == RESULT_OK) {
                importAgentKnowledgeFromUri(data?.data ?: return)
            }
            return
        }
        if (requestCode == REQUEST_OBSIDIAN_VAULT) {
            if (resultCode == RESULT_OK) data?.data?.let(::configureObsidianVault)
            return
        }
        if (requestCode == REQUEST_IMPORT_SKILL && resultCode == RESULT_OK) {
            importAgentSkillFromUri(data?.data ?: return)
            return
        }
        if (requestCode == REQUEST_IMPORT_MCP_PACKAGE && resultCode == RESULT_OK) {
            importAgentMcpPackageFromUri(data?.data ?: return)
            return
        }
        if (requestCode == REQUEST_IMPORT_RUNTIME_PACK && resultCode == RESULT_OK) {
            importRuntimePackFromUri(data?.data ?: return)
            return
        }
        if (requestCode == REQUEST_IMPORT_LOCAL_QNN_PACKAGE && resultCode == RESULT_OK) {
            importLocalQnnPackageFromUri(data?.data ?: return)
            return
        }
        if (requestCode == REQUEST_EXPORT_RUNTIME_ARTIFACT) {
            val pending = pendingRuntimeArtifactExport
            pendingRuntimeArtifactExport = null
            if (resultCode == RESULT_OK && data?.data != null && pending != null) {
                exportRuntimeArtifactToUri(pending, data.data!!)
            }
            return
        }
        if (requestCode == REQUEST_EXPORT_SKILL) {
            if (resultCode == RESULT_OK && data?.data != null) {
                exportAgentSkillToUri(data.data!!)
            } else {
                pendingExportSkill = null
            }
            return
        }
        if (requestCode == REQUEST_EXPORT_BACKUP) {
            if (resultCode == RESULT_OK && data?.data != null) {
                exportBackupToUri(data.data!!)
            } else {
                pendingExportPassword = null
            }
            return
        }
        if (requestCode != REQUEST_IMAGE || resultCode != RESULT_OK) {
            if (requestCode == REQUEST_PICK_AVATAR && resultCode == RESULT_OK) {
                handleAvatarPicked(data?.data)
            }
            return
        }
        val contact = selectedContact
        if (contact != null && (
                AppStore.isDesktopDeviceContact(this, contact.id) ||
                    AppStore.isPersonContact(this, contact.id)
            )
        ) {
            val uris = buildList {
                data?.clipData?.let { clips ->
                    for (index in 0 until clips.itemCount) add(clips.getItemAt(index).uri)
                }
                data?.data?.let { if (it !in this) add(it) }
            }
            sendPeerAttachments(contact, uris)
        } else {
            sendImage(data?.data ?: return)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_RECORD_AUDIO) {
            val granted = grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED
            if (highAccuracyAsrControllerDelegate.isInitialized()) {
                highAccuracyAsrController.onMicrophonePermissionChanged(granted)
            }
            if (granted) {
                Toast.makeText(this, getString(R.string.voice_record_permission_granted), Toast.LENGTH_SHORT).show()
                if (activeMainTab == PAGE_VOICE) startVoiceAssistant()
            }
        }
        if (requestCode == REQUEST_AGENT_CAMERA_PERMISSION &&
            grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED
        ) {
            openAgentCamera()
        }
        if (handleChatCameraPermissionResult(requestCode, grantResults)) return
        if (requestCode == REQUEST_CONTROL_CENTER_PERMISSION) {
            if (pendingVoiceEnableFromControlCenter) {
                if (grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
                    VoiceAssistantSettings.setEnabled(this, true)
                }
                pendingVoiceEnableFromControlCenter = false
            }
            if (featurePage.visibility == View.VISIBLE) {
                when {
                    featureTitle.text == getString(R.string.voice_asr_provider) -> showAsrProviderPage()
                    featureTitle.text == getString(R.string.voice_tts_provider) -> showTtsProviderPage()
                    controlCenterDestination != null -> renderCurrentControlCenterDestination()
                    else -> showOnDeviceAgentFeaturePage()
                }
            }
            return
        }
        if (requestCode == REQUEST_AGENT_NATIVE_PERMISSIONS) {
            val granted = grantResults.count { it == PackageManager.PERMISSION_GRANTED }
            val pending = pendingDirectSystemAction
            pendingDirectSystemAction = null
            if (pending != null) {
                if (grantResults.isNotEmpty() && granted == grantResults.size) {
                    executeDirectSystemAction(pending.action, pending.conversationId, pending.turnId)
                } else {
                    appendDirectSystemResult(
                        pending.action,
                        pending.conversationId,
                        pending.turnId,
                        AgentActionResult(pending.action.id, false, "Required Android permission was not granted")
                    )
                }
            }
            Toast.makeText(
                this,
                getString(R.string.agent_native_permissions_result, granted, grantResults.size),
                Toast.LENGTH_LONG
            ).show()
        }
    }

    // ===== Chat Page =====



    // ===== Contacts =====


































































































































    internal fun TextView.settingsText(title: String, subtitle: String) {
        text = SpannableString("$title\n$subtitle").apply {
            setSpan(RelativeSizeSpan(0.76f), title.length + 1, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            setSpan(ForegroundColorSpan(getColorCompat(R.color.text_secondary)), title.length + 1, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(62))
        gravity = Gravity.CENTER_VERTICAL
        includeFontPadding = false
        textSize = 15f
        setTextColor(getColorCompat(R.color.text_primary))
        setLineSpacing(dp(2).toFloat(), 1f)
    }

































































































































































































































    // ===== Input =====

    internal var emojiTokens = listOf("??", "??", "??", "??", "??", "??", "??")





    // ===== Send / Receive =====






















































    // ===== Recording =====



































    // ===== Chat History =====
































    // ===== Security / Scan =====







    // ===== Contact Detail Page =====


    // ===== Settings / Profile =====














































    internal fun Long?.orZero(): Long = this ?: 0L













    // ===== Backup =====
















    // ===== Avatar =====












    // ===== Media =====

    // ===== Helpers =====


}
