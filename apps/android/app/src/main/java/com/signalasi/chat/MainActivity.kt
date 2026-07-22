package com.signalasi.chat

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
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.database.Cursor
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.Paint
import android.graphics.drawable.AnimatedImageDrawable
import android.graphics.drawable.GradientDrawable
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
import com.signalasi.chat.SignalASIMqttClient.Listener
import com.signalasi.chat.ui.AppleHoldToTalkController
import com.signalasi.chat.ui.VoiceWaveformView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
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
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sin

private class BaselineShiftSpan(private val shiftPx: Int) : CharacterStyle(), UpdateAppearance {
    override fun updateDrawState(tp: TextPaint) {
        tp.baselineShift += shiftPx
    }
}

class MainActivity : Activity(), SignalASIMqttClient.Listener {

    private data class PendingDirectSystemAction(
        val action: AgentAction,
        val conversationId: String,
        val turnId: String
    )

    private data class AgentInitialHydration(
        val state: AgentUiState,
        val conversation: AgentConversation,
        val entries: List<AgentTranscriptEntry>,
        val contextCount: Int,
        val insightCount: Int
    )

    private data class ControlCenterDestination(
        val route: ControlCenterRoute,
        val payload: String = ""
    )

    companion object {
        private const val REQUEST_IMAGE = 2001
        private const val REQUEST_RECORD_AUDIO = 2002
        private const val REQUEST_IMPORT_BACKUP = 2003
        private const val REQUEST_EXPORT_BACKUP = 2004
        private const val REQUEST_PICK_AVATAR = 2005
        private const val REQUEST_IMPORT_KNOWLEDGE = 2006
        private const val REQUEST_AGENT_SCREEN_CAPTURE = 2007
        private const val REQUEST_AGENT_CAMERA = 2008
        private const val REQUEST_AGENT_CAMERA_PERMISSION = 2009
        private const val REQUEST_AGENT_NATIVE_PERMISSIONS = 2010
        private const val REQUEST_AGENT_NOTIFICATIONS = 2011
        private const val REQUEST_IMPORT_SKILL = 2012
        private const val REQUEST_EXPORT_SKILL = 2013
        private const val REQUEST_AGENT_ATTACHMENTS = 2014
        private const val REQUEST_AGENT_IMAGES = 2015
        private const val REQUEST_CONTROL_CENTER_PERMISSION = 2016
        private const val REQUEST_IMPORT_MCP_PACKAGE = 2017
        private const val REQUEST_IMPORT_RUNTIME_PACK = 2018
        private const val REQUEST_EXPORT_RUNTIME_ARTIFACT = 2019
        private const val MAX_VISIBLE_AGENT_PROCESS_STEPS = 20
        private const val INITIAL_VISIBLE_AGENT_TRANSCRIPT_ITEMS = 24
        private const val AGENT_TRANSCRIPT_PAGE_ITEMS = 24
        private const val AGENT_PROCESS_TIMER_TICK_MS = 250L
        private const val GLOBAL_AGENT_FOREGROUND_RETRY_MILLIS = 5_000L
        private const val AGENT_BRAND_LOGO_BASE_DP = 39
        private const val AGENT_BRAND_LOGO_MIN_DP = 32
        private const val AGENT_BRAND_LOGO_MAX_DP = 56
        private const val EXTRA_REOPEN_CONTROL_CENTER_CHILD = "signalasi_reopen_control_center_child"
        private const val CONTROL_CENTER_CHILD_TEXT_SIZE = "text_size"
        private const val CAPABILITY_KIND_MCP = "mcp"
        private const val CAPABILITY_KIND_SKILL = "skill"
        private const val MAX_AGENT_ATTACHMENTS = 10
        private const val MAX_AGENT_ATTACHMENT_BYTES = 20L * 1024L * 1024L
        private const val AGENT_REGISTRY_SYNC_INTERVAL_MILLIS = 5_000L
        private const val UI_PREFS = "signalasi_ui_preferences"
        private const val DEBUG_AGENT_PREFS = "signalasi_debug_agent"
        private const val HISTORY_PREFS = "signalasi_chat_history"
        private const val HISTORY_KEY = "messages"
        private const val HISTORY_UPDATED_KEY = "updated_at"
        private const val MAX_SAVED_MESSAGES_PER_CONTACT = 500
        private const val PAGE_VOICE = "page_voice"
        private const val PAGE_AGENT = "page_agent"
        private const val PAGE_MESSAGES = "page_messages"
        private const val PAGE_CONTACTS = "page_contacts"
        private const val PAGE_DISCOVER = "page_discover"
        private const val PAGE_SETTINGS = "page_settings"
        private const val TAB_MESSAGES = "SignalASI"
        private const val TAB_CONTACTS = "\u901a\u8baf\u5f55"
        private const val TAB_DISCOVER = "\u53d1\u73b0"
        private const val TAB_SETTINGS = "\u8bbe\u7f6e"
        private val CONTACT_HERMES = Contact("hermes", "Hermes Agent", "")
        private val CONTACT_SYSTEM = Contact("system", "\u7cfb\u7edf\u901a\u77e5", "")
        private val CONTACT_ME = Contact("me", "\u6211", "")
        private val CONTACT_PC = Contact("pc_agent", "PC Agent", "")
        private val CONTACT_HOME = Contact("home_hub", "Home Hub", "")
        private val CONTACT_NEWS = Contact("news_agent", "\u65b0\u95fb Agent", "")
        private val CONTACT_AUTOMATION = Contact("automation_center", "\u81ea\u52a8\u5316\u4e2d\u5fc3", "")
        private val CONTACTS = listOf(CONTACT_HERMES, CONTACT_SYSTEM)
        private val CHAT_CONTACTS = listOf(CONTACT_HERMES)
        private val CLOUD_MODEL_PRESETS = listOf(
            CloudModelPreset("OpenAI", "GPT-5.5", "gpt-5.5", "https://api.openai.com/v1/chat/completions", "openai"),
            CloudModelPreset("OpenAI", "GPT-5.4 mini", "gpt-5.4-mini", "https://api.openai.com/v1/chat/completions", "openai"),
            CloudModelPreset("OpenAI", "GPT-5.4 nano", "gpt-5.4-nano", "https://api.openai.com/v1/chat/completions", "openai"),
            CloudModelPreset("OpenAI", "GPT-5", "gpt-5", "https://api.openai.com/v1/chat/completions", "openai"),
            CloudModelPreset("Anthropic", "Claude Opus 4.7", "claude-opus-4-7-latest", "https://api.anthropic.com/v1/messages", "anthropic"),
            CloudModelPreset("Anthropic", "Claude Sonnet 5", "claude-sonnet-5-latest", "https://api.anthropic.com/v1/messages", "anthropic"),
            CloudModelPreset("Anthropic", "Claude Haiku 4.5", "claude-haiku-4-5-latest", "https://api.anthropic.com/v1/messages", "anthropic"),
            CloudModelPreset("Google Gemini", "Gemini 3.5 Flash", "gemini-3.5-flash", "https://generativelanguage.googleapis.com/v1beta/models/gemini-3.5-flash:generateContent", "gemini"),
            CloudModelPreset("Google Gemini", "Gemini 3.1 Pro", "gemini-3.1-pro", "https://generativelanguage.googleapis.com/v1beta/models/gemini-3.1-pro:generateContent", "gemini"),
            CloudModelPreset("Google Gemini", "Gemini 3.1 Flash Lite", "gemini-3.1-flash-lite", "https://generativelanguage.googleapis.com/v1beta/models/gemini-3.1-flash-lite:generateContent", "gemini"),
            CloudModelPreset("DeepSeek", "DeepSeek V4 Pro", "deepseek-v4-pro", "https://api.deepseek.com/chat/completions", "openai"),
            CloudModelPreset("DeepSeek", "DeepSeek V4 Flash", "deepseek-v4-flash", "https://api.deepseek.com/chat/completions", "openai"),
            CloudModelPreset("Qwen", "Qwen 3.7 Max", "qwen3.7-max", "https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions", "openai"),
            CloudModelPreset("Qwen", "Qwen 3.7 Plus", "qwen3.7-plus", "https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions", "openai"),
            CloudModelPreset("Qwen", "Qwen 3.6 Flash", "qwen3.6-flash", "https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions", "openai"),
            CloudModelPreset("OpenRouter", "OpenRouter Auto", "openrouter/auto", "https://openrouter.ai/api/v1/chat/completions", "openai"),
            CloudModelPreset("OpenRouter", "OpenAI GPT-5.5 via OpenRouter", "openai/gpt-5.5", "https://openrouter.ai/api/v1/chat/completions", "openai"),
            CloudModelPreset("Custom", "OpenAI Compatible", "model-id", "https://api.example.com/v1/chat/completions", "openai")
        )
    }

    // UI Views
    private lateinit var wakePage: FrameLayout
    private lateinit var wakeAnimation: ImageView
    private lateinit var mainPage: LinearLayout
    private lateinit var mainTopBar: LinearLayout
    private lateinit var agentPage: LinearLayout
    private lateinit var agentSessionTitle: TextView
    private lateinit var agentSubtitleText: TextView
    private lateinit var agentOutputScroll: ScrollView
    private lateinit var agentOutputList: LinearLayout
    private lateinit var agentSettingsButton: ImageButton
    private lateinit var agentPermissionModeButton: TextView
    private lateinit var agentHighRiskGuardButton: TextView
    private lateinit var agentMemoryCaptureButton: TextView
    private lateinit var agentToolboxList: LinearLayout
    private lateinit var agentCurrentAppText: TextView
    private lateinit var agentCallableTargetsText: TextView
    private lateinit var agentRunningTasksText: TextView
    private lateinit var agentMemoryText: TextView
    private lateinit var agentKnowledgeText: TextView
    @Volatile private var agentOperationInFlight = false
    private val activeAgentTasks = ConcurrentHashMap<Long, MobileNativeAgent>()
    private val provisionalAgentTasks = ConcurrentHashMap.newKeySet<MobileNativeAgent>()
    private val completedConnectorTaskIds = ConcurrentHashMap.newKeySet<String>()
    private val supersededConnectorSourceIds = ConcurrentHashMap.newKeySet<Long>()
    private val agentRuntimeConversationIds = ConcurrentHashMap<MobileNativeAgent, String>()
    private val agentRuntimeTurnIds = ConcurrentHashMap<MobileNativeAgent, String>()
    private val agentConnectorResponsesInFlight = ConcurrentHashMap.newKeySet<String>()
    private var pendingDirectSystemAction: PendingDirectSystemAction? = null
    private lateinit var agentScreenSearchInput: EditText
    private lateinit var agentScreenDetailList: LinearLayout
    private lateinit var agentActionQueueList: LinearLayout
    private lateinit var agentRequirementList: LinearLayout
    private lateinit var agentPlanContextList: LinearLayout
    private lateinit var agentVerificationList: LinearLayout
    private lateinit var agentAuditTrailList: LinearLayout
    private lateinit var agentRecentTaskList: LinearLayout
    private lateinit var agentGoalInput: EditText
    private lateinit var agentVoiceButton: TextView
    private lateinit var agentInsightBar: LinearLayout
    private lateinit var agentInsightText: TextView
    private lateinit var agentInputContent: LinearLayout
    private lateinit var agentRecordingCenter: LinearLayout
    private lateinit var agentRecordingWaveform: VoiceWaveformView
    private lateinit var agentRecordingTimer: TextView
    private lateinit var agentHoldToTalkController: AppleHoldToTalkController
    private lateinit var agentAttachButton: ImageButton
    private lateinit var agentSubmitButton: ImageButton
    private lateinit var agentBrandLogo: ImageView
    private lateinit var agentAttachmentPreviewScroll: HorizontalScrollView
    private lateinit var agentAttachmentPreviewList: LinearLayout
    private lateinit var contactPage: LinearLayout
    private lateinit var directoryPage: LinearLayout
    private lateinit var discoverPage: LinearLayout
    private lateinit var mePage: View
    private lateinit var featurePage: LinearLayout
    private lateinit var featureTitle: TextView
    private lateinit var featureContent: LinearLayout
    private lateinit var featureBackButton: TextView
    private var activeDesktopControlId: String? = null
    private lateinit var mainTitle: TextView
    private lateinit var mainActionButton: TextView
    private lateinit var chatPage: LinearLayout
    private lateinit var chatTitle: TextView
    private lateinit var chatModelTag: LinearLayout
    private lateinit var chatModelButton: LinearLayout
    private lateinit var chatModelLabel: TextView
    private lateinit var chatSubtitle: TextView
    private lateinit var chatAvatar: ImageView
    private lateinit var statusDot: View
    private lateinit var contactStatusDot: View
    private lateinit var backButton: TextView
    private lateinit var securityButton: ImageButton
    private var contactAdapter: ContactAdapter? = null
    private var directoryAdapter: ContactAdapter? = null
    private var messageAdapter: MessageAdapter? = null
    private lateinit var messageInput: EditText
    private lateinit var sendButton: ImageButton
    private lateinit var imageButton: ImageButton
    private lateinit var voiceButton: ImageButton
    private lateinit var pressToTalkButton: TextView
    private lateinit var chatRecordingCenter: LinearLayout
    private lateinit var chatRecordingWaveform: VoiceWaveformView
    private lateinit var chatRecordingTimer: TextView
    private lateinit var holdToTalkController: AppleHoldToTalkController
    private lateinit var emojiButton: ImageButton
    private lateinit var emojiPanel: HorizontalScrollView
    private lateinit var emojiContainer: LinearLayout
    private lateinit var chatInputBar: LinearLayout
    private lateinit var messageList: RecyclerView
    private lateinit var meProfileText: TextView
    private lateinit var meIdSubtitleText: TextView
    private lateinit var meIdText: TextView
    private lateinit var meAvatar: ImageView
    private lateinit var controlCenterRenderer: ControlCenterRenderer
    private val controlCenterBackStack = ArrayDeque<ControlCenterDestination>()
    private var controlCenterDestination: ControlCenterDestination? = null
    private var renderingControlCenterDestination = false
    private var featureBackAction: (() -> Unit)? = null
    private var pendingVoiceEnableFromControlCenter = false
    @Volatile private var runtimeCatalogRefreshInProgress = false
    @Volatile private var pendingRuntimeCatalogPackId: String? = null
    @Volatile private var runtimePackInstallInProgressId: String? = null

    // State
    private val handler = Handler(Looper.getMainLooper())
    private val globalAgentRefreshInProgress = AtomicBoolean(false)
    private val globalAgentRefreshRequested = AtomicBoolean(false)
    private val globalProactiveDeliveryListener = GlobalProactiveDeliveryListener {
        handler.post(::refreshGlobalAgentCognition)
    }
    private val historyExecutor = Executors.newSingleThreadExecutor()
    private val cloudExecutor = Executors.newCachedThreadPool()
    private val historySaveSeq = AtomicInteger()
    private val historySaveRunnable = Runnable { enqueueChatHistorySave() }
    private lateinit var mobileNativeAgent: MobileNativeAgent
    private lateinit var agentTranscriptStore: AgentTranscriptStore
    private lateinit var globalSuperAgentRuntime: GlobalSuperAgentRuntime
    private var openLatestGlobalInsightWhenDelivered = false
    private var requestedGlobalInsightConversationId = ""
    private lateinit var agentRunRecorder: AgentRunRecorder
    private lateinit var agentSkillRuntime: AgentSkillRuntime
    private lateinit var agentSkillMatcher: AgentSkillMatcher
    private lateinit var agentLearningEngine: AgentLearningEngine
    private lateinit var agentRunEventStore: AgentRunEventStore
    private lateinit var agentHandoffStore: EncryptedAgentHandoffStore
    private lateinit var encryptedAgentRegistry: EncryptedAgentRegistry
    private var lastAgentRegistrySyncAtMillis = 0L
    private lateinit var agentMcpRegistry: AgentMcpRegistry
    private lateinit var agentMcpPackageRepository: AgentMcpPackageRepository
    private lateinit var agentRuntimePackCatalogManager: AgentRuntimePackCatalogManager
    private val agentRunIdsByTurn = ConcurrentHashMap<String, String>()
    private var agentSessionsDialog: android.app.Dialog? = null
    private val agentConnectorResponseListener = AgentConnectorResponseListener { response ->
        runOnUiThread { consumeAgentConnectorResponse(response) }
    }
    private val agentVisualScreenListener = AgentVisualScreenListener { result ->
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
    private val messages = mutableMapOf<String, MutableList<ChatMessage>>()
    private val summaries = mutableMapOf<String, ContactSummary>()
    private var selectedContact: Contact? = null
    private var activeMainTab = PAGE_AGENT
    private var nextMessageId = 1L
    private var recorder: MediaRecorder? = null
    private var recordingFile: File? = null
    private var recordingStartedAt = 0L
    private var recordingPurpose = ""
    private var player: android.media.MediaPlayer? = null
    private var voiceMode = false
    private var secureChannelReady = false
    private var scanMode = "security"
    private var latestAgentScreenContext: ScreenContext? = null
    private var lastRenderedAgentState: AgentUiState? = null
    @Volatile private var initialAgentHydrationPending = true
    private var initialAgentHydrationScheduled = false
    private var completedInitialResume = false
    private var agentTranscriptVisibleLimit = INITIAL_VISIBLE_AGENT_TRANSCRIPT_ITEMS
    private var agentTranscriptPageLoading = false
    private var agentTranscriptAllLoaded = false
    private var agentRenderedConversationId = ""
    private var agentTranscriptAutoFollow = true
    private val renderedAgentTranscriptIds = linkedSetOf<String>()
    private val expandedAgentProcessGroups = linkedSetOf<String>()
    private val collapsedActiveAgentProcessGroups = linkedSetOf<String>()
    private val expandedAgentToolSegments = linkedSetOf<String>()
    private val directoryContacts = mutableListOf<Contact>()
    private var pendingExportPassword: String? = null
    private var pendingExportSkill: Pair<String, String>? = null
    private var pendingRuntimeArtifactExport: AgentRuntimeArtifactActionPayload? = null
    private var pendingExportIncludeMessages = true
    private var pendingImportUri: Uri? = null
    private val agentInputAttachments = mutableListOf<AgentInputAttachment>()
    private var pendingAgentCameraUri: Uri? = null
    @Volatile private var fileServerBaseUrl: String? = null
    private var voiceOverlay: Dialog? = null
    private var wakeStatusText: TextView? = null
    private var wakeTranscriptText: TextView? = null
    private var wakeReplyPanel: ScrollView? = null
    private var speechRecognizer: SpeechRecognizer? = null
    private var agentVoiceListening = false
    private var voiceAssistantListening = false
    private var voiceAssistantAwake = false
    private var voiceAssistantSpeaking = false
    private var voiceAssistantRecordingCommand = false
    private var voiceCommandSpeechDetected = false
    private var voiceCommandLastVoiceAt = 0L
    private var voiceAssistantRestartPending = false
    private var wakeReplyPinnedUntilMs = 0L
    private var lastVoiceRecognitionStartAt = 0L
    private val voiceAssistantScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var wakeWordEngine: WakeWordEngine? = null
    private var wakeWordDetectionJob: Job? = null
    private var androidTts: TextToSpeech? = null
    private var androidTtsReady = false
    private lateinit var microsoftTts: MicrosoftEdgeTts
    private var lastDebugSendKey: String? = null
    private var lastHistoryLoadedAt = 0L
    private var pendingAsrModelSelection: String? = null
    private val asrModelDownloadPoll = object : Runnable {
        override fun run() {
            val pendingId = pendingAsrModelSelection ?: return
            val model = WhisperModelManager.model(pendingId)
            val state = WhisperModelManager.downloadState(this@MainActivity, model)
            when (state.status) {
                DownloadManager.STATUS_SUCCESSFUL -> {
                    VoiceAssistantSettings.setAsrModel(this@MainActivity, model.id)
                    pendingAsrModelSelection = null
                    Toast.makeText(this@MainActivity, getString(R.string.voice_asr_model_ready, model.displayName), Toast.LENGTH_SHORT).show()
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

    private val currentMessages: MutableList<ChatMessage>
        get() {
            val id = selectedContact?.id ?: return mutableListOf()
            return messages.getOrPut(id) { mutableListOf() }
        }

    private fun newMessageId(): Long = nextMessageId++

    override fun attachBaseContext(newBase: Context) {
        val localized = AppLanguage.wrap(newBase)
        super.attachBaseContext(localized)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        val startupStartedAt = SystemClock.elapsedRealtime()
        var startupCheckpointAt = startupStartedAt
        fun traceStartup(stage: String) {
            if ((applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) == 0) return
            val now = SystemClock.elapsedRealtime()
            Log.i("SignalASIStartup", "$stage step=${now - startupCheckpointAt}ms total=${now - startupStartedAt}ms")
            startupCheckpointAt = now
        }
        AppDisplaySettings.applyToResources(this)
        super.onCreate(savedInstanceState)
        configureSystemBars()
        setContentView(R.layout.activity_main)
        traceStartup("content_view")
        AppStore.ensureInitialized(this)
        traceStartup("app_store")
        mobileNativeAgent = MobileNativeAgent(
            this,
            nativeToolEventSink = AgentNativeToolEventSink(::recordNativeToolLifecycleEvent)
        )
        agentTranscriptStore = AgentTranscriptStore(this)
        traceStartup("mobile_agent")
        globalSuperAgentRuntime = GlobalSuperAgentRuntime.get(this)
        traceStartup("global_runtime")
        openLatestGlobalInsightWhenDelivered = intent?.getBooleanExtra("signalasi_open_agent", false) == true
        requestedGlobalInsightConversationId = intent
            ?.getStringExtra("signalasi_agent_conversation_id")
            ?.trim()
            .orEmpty()
        intent?.removeExtra("signalasi_open_agent")
        intent?.removeExtra("signalasi_agent_conversation_id")
        requestedGlobalInsightConversationId.takeIf(String::isNotBlank)?.let(agentTranscriptStore::switchConversation)
        agentRunRecorder = AgentRunRecorder(this)
        agentRunEventStore = AgentRunEventStore(this)
        agentHandoffStore = EncryptedAgentHandoffStore(this)
        encryptedAgentRegistry = EncryptedAgentRegistry(this)
        syncAgentRegistrySnapshot(force = true)
        reconcileRecoverableAgentRuns()
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
        agentMcpPackageRepository = AgentMcpPackageRepository(this)
        agentRuntimePackCatalogManager = AgentRuntimePackCatalogManager(this)
        agentTranscriptStore.removeExactText("Create a safe local task plan")
        agentTranscriptStore.removeExactText("Task plan confirmed")
        agentTranscriptStore.removeObsoletePlannerProcessEntries()
        traceStartup("agent_stores")
        microsoftTts = MicrosoftEdgeTts(applicationContext)
        androidTts = TextToSpeech(this) { status ->
            androidTtsReady = status == TextToSpeech.SUCCESS
            if (androidTtsReady) {
                configureAndroidTtsLanguage()
                androidTts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) = Unit
                    override fun onDone(utteranceId: String?) {
                        runOnUiThread { onVoiceSpeechFinished() }
                    }
                    @Deprecated("Deprecated in Java")
                    override fun onError(utteranceId: String?) {
                        runOnUiThread { onVoiceSpeechFinished() }
                    }
                })
            }
            if (::featurePage.isInitialized &&
                featurePage.visibility == View.VISIBLE &&
                controlCenterDestination?.route == ControlCenterRoute.VOICE
            ) {
                runOnUiThread { renderCurrentControlCenterDestination() }
            }
        }

        wakePage = findViewById(R.id.wakePage)
        wakeAnimation = findViewById(R.id.wakeAnimation)
        mainPage = findViewById(R.id.mainPage)
        mainTopBar = findViewById(R.id.mainTopBar)
        agentPage = findViewById(R.id.agentPage)
        agentSessionTitle = findViewById(R.id.agentSessionTitle)
        agentSubtitleText = findViewById(R.id.agentSubtitleText)
        agentOutputScroll = findViewById(R.id.agentOutputScroll)
        agentOutputList = findViewById(R.id.agentOutputList)
        agentSettingsButton = findViewById(R.id.agentSettingsButton)
        agentPermissionModeButton = findViewById(R.id.agentPermissionModeButton)
        agentHighRiskGuardButton = findViewById(R.id.agentHighRiskGuardButton)
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
        agentVoiceButton = findViewById(R.id.agentVoiceButton)
        agentInsightBar = findViewById(R.id.agentInsightBar)
        agentInsightText = findViewById(R.id.agentInsightText)
        agentInputContent = findViewById(R.id.agentInputContent)
        agentRecordingCenter = findViewById(R.id.agentRecordingCenter)
        agentRecordingWaveform = findViewById(R.id.agentRecordingWaveform)
        agentRecordingTimer = findViewById(R.id.agentRecordingTimer)
        agentAttachButton = findViewById(R.id.agentAttachButton)
        agentSubmitButton = findViewById(R.id.agentSubmitButton)
        agentBrandLogo = findViewById(R.id.agentBrandLogo)
        applyAgentBrandLogoTextScale()
        agentAttachmentPreviewScroll = findViewById(R.id.agentAttachmentPreviewScroll)
        agentAttachmentPreviewList = findViewById(R.id.agentAttachmentPreviewList)
        contactPage = findViewById(R.id.contactPage)
        directoryPage = findViewById(R.id.directoryPage)
        discoverPage = findViewById(R.id.discoverPage)
        mePage = findViewById(R.id.mePage)
        featurePage = findViewById(R.id.featurePage)
        featureTitle = findViewById(R.id.featureTitle)
        featureContent = findViewById(R.id.featureContent)
        featureBackButton = findViewById(R.id.featureBackButton)
        controlCenterRenderer = ControlCenterRenderer(this)
        mainTitle = findViewById(R.id.mainTitle)
        mainActionButton = findViewById(R.id.mainActionButton)
        chatPage = findViewById(R.id.chatPage)
        statusDot = findViewById(R.id.statusDot)
        contactStatusDot = findViewById(R.id.contactStatusDot)
        messageInput = findViewById(R.id.messageInput)
        sendButton = findViewById(R.id.sendButton)
        imageButton = findViewById(R.id.imageButton)
        voiceButton = findViewById(R.id.voiceButton)
        pressToTalkButton = findViewById(R.id.pressToTalkButton)
        chatRecordingCenter = findViewById(R.id.chatRecordingCenter)
        chatRecordingWaveform = findViewById(R.id.chatRecordingWaveform)
        chatRecordingTimer = findViewById(R.id.chatRecordingTimer)
        emojiButton = findViewById(R.id.emojiButton)
        emojiPanel = findViewById(R.id.emojiPanel)
        emojiContainer = findViewById(R.id.emojiContainer)
        chatTitle = findViewById(R.id.chatTitle)
        chatModelTag = findViewById(R.id.chatModelTag)
        chatModelButton = findViewById(R.id.chatModelButton)
        chatModelLabel = findViewById(R.id.chatModelLabel)
        chatSubtitle = findViewById(R.id.chatSubtitle)
        chatAvatar = findViewById(R.id.chatAvatar)
        backButton = findViewById(R.id.backButton)
        securityButton = findViewById(R.id.securityButton)
        meProfileText = findViewById(R.id.meProfileText)
        meIdSubtitleText = findViewById(R.id.meIdSubtitleText)
        meIdText = findViewById(R.id.meIdText)
        meAvatar = findViewById(R.id.meAvatar)
        chatInputBar = findViewById(R.id.chatInputBar)
        messageList = findViewById(R.id.messageList)
        val backButton2 = findViewById<TextView>(R.id.backButton)
        traceStartup("view_binding")

        loadChatHistory()
        traceStartup("chat_history")
        configureMainTabs()
        configureAgentPage()
        traceStartup("agent_page")
        configureContacts()
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

        SignalASIMqttClient.addListener(this)
        SignalASIMqttClient.connect(this)
        handler.postDelayed({
            handleDebugSendIntent(intent)
            handleDebugIncomingIntent(intent)
        }, 1200)
        traceStartup("on_create_complete")
    }

    private fun scheduleAgentInitialHydration() {
        if (initialAgentHydrationScheduled) return
        initialAgentHydrationScheduled = true
        agentPage.postDelayed({
            thread(name = "signalasi-agent-initial-hydration") {
                val hydrationStartedAt = SystemClock.elapsedRealtime()
                val outcome = runCatching {
                    val state = mobileNativeAgent.snapshot()
                    val conversation = agentTranscriptStore.activeConversation()
                    val entries = agentTranscriptStore.list(conversation.id)
                    val contextCount = entries.count { it.role != AgentTranscriptRole.PROCESS }
                    val insightCount = globalSuperAgentRuntime.newProactiveInsightCount()
                    AgentInitialHydration(state, conversation, entries, contextCount, insightCount)
                }
                runOnUiThread {
                    outcome.onSuccess { hydration ->
                        resetAgentTranscriptRendering(hydration.conversation.id)
                        renderAgentState(
                            hydration.state,
                            conversationId = hydration.conversation.id,
                            syncTranscript = false,
                            activeConversationId = hydration.conversation.id
                        )
                        renderAgentTranscript(hydration.entries)
                        refreshAgentConversationHeader(hydration.conversation, hydration.contextCount)
                        refreshGlobalInsightIndicator(hydration.insightCount)
                        Log.i(
                            "SignalASIStartup",
                            "agent_hydration total=${SystemClock.elapsedRealtime() - hydrationStartedAt}ms entries=${hydration.entries.size} visible=${renderedAgentTranscriptIds.size}"
                        )
                    }.onFailure { error ->
                        Log.w("SignalASIStartup", "Initial Agent hydration failed", error)
                }
                    initialAgentHydrationPending = false
                    consumePendingAgentConnectorResponsesAsync()
                    scheduleAgentSkillBootstrap()
                }
            }
        }, 100L)
    }

    private fun scheduleAgentSkillBootstrap() {
        val runtime = agentSkillRuntime
        thread(name = "signalasi-skill-bootstrap") {
            runCatching { AgentBuiltInSkills.synchronizeIfNeeded(applicationContext, runtime) }
        }
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent?.getBooleanExtra("signalasi_open_agent", false) == true) {
            requestedGlobalInsightConversationId = intent
                .getStringExtra("signalasi_agent_conversation_id")
                ?.trim()
                .orEmpty()
            intent.removeExtra("signalasi_open_agent")
            intent.removeExtra("signalasi_agent_conversation_id")
            openLatestGlobalInsightWhenDelivered = true
            requestedGlobalInsightConversationId.takeIf(String::isNotBlank)?.let(agentTranscriptStore::switchConversation)
            showMainTab(PAGE_AGENT)
            renderAgentState(mobileNativeAgent.reloadSession())
            refreshGlobalAgentCognition()
        }
        handleDebugSendIntent(intent)
        handleDebugIncomingIntent(intent)
    }

    private fun startMessageService() {
        // MainActivity is visible here, so avoid starting the foreground-service deadline
        // before the service's main-thread lifecycle callback can run.
        startService(Intent(this, MessageService::class.java))
    }

    private fun requestAgentNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(
                arrayOf(android.Manifest.permission.POST_NOTIFICATIONS),
                REQUEST_AGENT_NOTIFICATIONS
            )
        }
    }

    private fun configureSystemBars() {
        window.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        window.statusBarColor = getColorCompat(R.color.bar_bg)
        window.navigationBarColor = getColorCompat(R.color.bar_bg)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val isNight = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
                Configuration.UI_MODE_NIGHT_YES
            var flags = if (isNight) 0 else View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
            if (!isNight && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                flags = flags or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
            }
            window.decorView.systemUiVisibility = flags
        }
    }

    override fun onDestroy() {
        handler.removeCallbacks(asrModelDownloadPoll)
        stopVoiceAssistant()
        voiceAssistantScope.cancel()
        microsoftTts.shutdown()
        androidTts?.stop()
        androidTts?.shutdown()
        if (::holdToTalkController.isInitialized) holdToTalkController.release()
        if (::agentHoldToTalkController.isInitialized) agentHoldToTalkController.release()
        stopRecording(send = false)
        saveChatHistory(sync = true)
        SignalASIMqttClient.removeListener(this)
        ScreenPerceptionState.removeVisualListener(agentVisualScreenListener)
        if (::agentRuntimePackCatalogManager.isInitialized) agentRuntimePackCatalogManager.close()
        historyExecutor.shutdown()
        super.onDestroy()
    }

    override fun onResume() {
        val resumeStartedAt = SystemClock.elapsedRealtime()
        var resumeCheckpointAt = resumeStartedAt
        fun traceResume(stage: String) {
            if ((applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) == 0) return
            val now = SystemClock.elapsedRealtime()
            Log.i("SignalASIStartup", "resume_$stage step=${now - resumeCheckpointAt}ms total=${now - resumeStartedAt}ms")
            resumeCheckpointAt = now
        }
        super.onResume()
        traceResume("super")
        if (AppDisplaySettings.synchronizeNightMode(this)) {
            recreate()
            return
        }
        traceResume("display")
        AppForegroundTracker.onActivityResumed()
        AgentConnectorResponseBus.addListener(agentConnectorResponseListener)
        GlobalProactiveDeliveryBus.addListener(globalProactiveDeliveryListener)
        ScreenPerceptionState.addVisualListener(agentVisualScreenListener)
        traceResume("listeners")
        val initialResume = !completedInitialResume
        val reloadedAgentState = if (!initialResume) {
            mobileNativeAgent.reloadSession()
        } else {
            completedInitialResume = true
            null
        }
        traceResume("agent_session")
        syncAgentRegistrySnapshot()
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
        traceResume("complete")
    }

    private fun maintainMcpCredentials() {
        if (!::agentMcpRegistry.isInitialized) return
        val due = agentMcpRegistry.list().filter {
            it.enabled && it.authProfile.refreshExchange != null &&
                it.effectiveAuthState(System.currentTimeMillis()) == AgentMcpAuthState.REFRESHING
        }
        if (due.isEmpty()) return
        thread(name = "signalasi-mcp-maintenance") {
            val coordinator = AgentMcpAuthenticationCoordinator(agentMcpRegistry)
            due.forEach { connection -> runCatching { runBlocking { coordinator.refreshIfNeeded(connection.id) } } }
            runOnUiThread {
                if (controlCenterDestination?.route == ControlCenterRoute.MCP) {
                    renderCurrentControlCenterDestination()
                }
            }
        }
    }

    override fun onPause() {
        AgentConnectorResponseBus.removeListener(agentConnectorResponseListener)
        GlobalProactiveDeliveryBus.removeListener(globalProactiveDeliveryListener)
        ScreenPerceptionState.removeVisualListener(agentVisualScreenListener)
        saveChatHistory(sync = true)
        AppForegroundTracker.onActivityPaused()
        super.onPause()
    }

    private fun publishAgentConnectorResponse(envelope: JSONObject?, message: ChatMessage): Boolean {
        if (envelope?.optString("type").orEmpty().ifBlank { "text" } != "text") return false
        val sourceMessageId = envelope?.optString("source_message_id")?.toLongOrNull()
            ?: envelope?.optLong("source_message_id", 0L)?.takeIf { it > 0L }
            ?: return false
        val response = AgentConnectorResponse(
            sourceMessageId = sourceMessageId,
            contactId = envelope?.optString("contact_id").orEmpty().ifBlank { message.contact.id },
            content = message.content,
            conversationId = envelope?.optString("conversation_id").orEmpty(),
            turnId = envelope?.optString("turn_id").orEmpty(),
            taskId = envelope?.optString("task_id").orEmpty(),
            richOutputJson = CodexStyleResponsePolicy.filterAssistantRichOutput(
                AgentRichContentCodec.fromEnvelope(envelope)
            )
        )
        if (::globalSuperAgentRuntime.isInitialized &&
            globalSuperAgentRuntime.consumeResearchResponse(response)
        ) {
            refreshGlobalAgentCognition()
            return true
        }
        return AgentConnectorResponseBus.publish(this, response)
    }

    private fun consumeAgentConnectorResponse(response: AgentConnectorResponse) {
        if (::globalSuperAgentRuntime.isInitialized &&
            globalSuperAgentRuntime.consumeResearchResponse(response)
        ) {
            AgentConnectorResponseStore.remove(this, response)
            refreshGlobalAgentCognition()
            return
        }
        val runtime = runtimeForConnectorResponse(
            response.sourceMessageId,
            response.contactId,
            response.conversationId,
            response.turnId
        )
        if (runtime == null) {
            consumeOrphanedAgentConnectorResponse(response)
            return
        }
        val responseKey = "${response.sourceMessageId}:${response.contactId}"
        if (!agentConnectorResponsesInFlight.add(responseKey)) return
        resumeAgentConnectorResponse(response, runtime, responseKey)
    }

    private fun resumeAgentConnectorResponse(
        response: AgentConnectorResponse,
        runtime: MobileNativeAgent,
        responseKey: String,
        attempt: Int = 0
    ) {
        val turnId = agentRuntimeTurnIds[runtime].orEmpty().ifBlank { response.turnId }
        val conversationId = connectorConversationId(response.conversationId, runtime, turnId)
        if (conversationId == null) {
            Log.w(
                "SignalASIAgent",
                "Discarding unroutable connector response source=${response.sourceMessageId} turn=${turnId.take(8)}"
            )
            AgentConnectorResponseStore.remove(this, response)
            activeAgentTasks.remove(response.sourceMessageId)
            agentConnectorResponsesInFlight.remove(responseKey)
            return
        }
        val supervisor = AgentTaskRuntime.supervisor(this)
        if (turnId.isBlank()) {
            consumeLegacyAgentConnectorResponse(response, runtime, responseKey, conversationId)
            return
        }
        if (turnId in supervisor.activeTaskIds()) {
            if (attempt < 100) {
                handler.postDelayed(
                    { resumeAgentConnectorResponse(response, runtime, responseKey, attempt + 1) },
                    100L
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
                hook = AgentTaskResumeHook { context, _ ->
                    val state = try {
                        runtime.acceptConnectorResponse(
                            sourceMessageId = response.sourceMessageId,
                            contactId = response.contactId,
                            content = response.content,
                            success = response.success,
                            richOutputJson = response.richOutputJson
                        ) ?: runtime.snapshot()
                    } catch (failure: Throwable) {
                        agentConnectorResponsesInFlight.remove(responseKey)
                        throw failure
                    }
                    context.appendEvent(
                        kind = "agent.connector.response",
                        message = state.phase.name,
                        payloadJson = JSONObject()
                            .put("source_message_id", response.sourceMessageId)
                            .put("contact_id", response.contactId)
                            .put("success", response.success)
                            .toString()
                    )
                    AgentConnectorResponseStore.remove(this@MainActivity, response)
                    activeAgentTasks.remove(response.sourceMessageId)
                    runOnUiThread {
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
            if (attempt < 100) {
                handler.postDelayed(
                    { resumeAgentConnectorResponse(response, runtime, responseKey, attempt + 1) },
                    100L
                )
            } else {
                agentConnectorResponsesInFlight.remove(responseKey)
                consumeLegacyAgentConnectorResponse(response, runtime, responseKey, conversationId)
            }
        }
    }

    private fun consumeLegacyAgentConnectorResponse(
        response: AgentConnectorResponse,
        runtime: MobileNativeAgent,
        responseKey: String,
        conversationId: String
    ) {
        thread(name = "signalasi-agent-response-${response.sourceMessageId}") {
            val state = runtime.acceptConnectorResponse(
                sourceMessageId = response.sourceMessageId,
                contactId = response.contactId,
                content = response.content,
                success = response.success,
                richOutputJson = response.richOutputJson
            ) ?: runtime.snapshot()
            AgentConnectorResponseStore.remove(this, response)
            activeAgentTasks.remove(response.sourceMessageId)
            runOnUiThread {
                finishAgentConnectorResponseUi(
                    response,
                    runtime,
                    state,
                    conversationId,
                    agentRuntimeTurnIds[runtime].orEmpty(),
                    responseKey
                )
            }
        }
    }

    private fun finishAgentConnectorResponseUi(
        response: AgentConnectorResponse,
        runtime: MobileNativeAgent,
        state: AgentUiState,
        conversationId: String,
        turnId: String,
        responseKey: String
    ) {
        agentConnectorResponsesInFlight.remove(responseKey)
        agentTranscriptStore.recordUsage(
            conversationId, response.inputTokens, response.outputTokens, response.costMicros
        )
        if (turnId.isNotBlank()) finishStructuredAgentHandoff(turnId, response)
        renderAgentState(state, conversationId, turnId)
        if (turnId.isNotBlank()) recordAgentRunFromState(turnId, state)
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

    private fun consumePendingAgentConnectorResponses() {
        AgentConnectorResponseStore.pending(this).forEach { response ->
            consumeAgentConnectorResponse(response)
        }
    }

    private fun consumePendingAgentConnectorResponsesAsync() {
        thread(name = "signalasi-pending-agent-responses") {
            val pending = runCatching { AgentConnectorResponseStore.pending(applicationContext) }
                .getOrDefault(emptyList())
            Log.i(
                "SignalASIAgent",
                "Pending connector responses count=${pending.size}"
            )
            if (pending.isEmpty()) return@thread
            runOnUiThread { pending.forEach(::consumeAgentConnectorResponse) }
        }
    }

    private fun runtimeForConnectorResponse(
        sourceMessageId: Long,
        contactId: String,
        conversationId: String = "",
        turnId: String = ""
    ): MobileNativeAgent? {
        activeAgentTasks[sourceMessageId]
            ?.takeIf { it.canAcceptConnectorResponse(sourceMessageId, contactId) }
            ?.let { return it }
        provisionalAgentTasks.firstOrNull {
            it.canAcceptConnectorResponse(sourceMessageId, contactId)
        }?.let { runtime ->
            activeAgentTasks[sourceMessageId] = runtime
            provisionalAgentTasks.remove(runtime)
            return runtime
        }
        val cleanTurnId = turnId.trim()
        if (cleanTurnId.isNotBlank()) {
            val restored = MobileNativeAgent(
                this,
                sessionStore = SharedPreferencesAgentSessionStore(this, "task:$cleanTurnId"),
                nativeToolEventSink = AgentNativeToolEventSink(::recordNativeToolLifecycleEvent)
            )
            if (restored.canAcceptConnectorResponse(sourceMessageId, contactId)) {
                activeAgentTasks[sourceMessageId] = restored
                agentRuntimeTurnIds[restored] = cleanTurnId
                connectorConversationId(conversationId, restored, cleanTurnId)?.let {
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
                sessionStore = SharedPreferencesAgentSessionStore(this, storageKey),
                nativeToolEventSink = AgentNativeToolEventSink(::recordNativeToolLifecycleEvent)
            )
            if (restored.canAcceptConnectorResponse(sourceMessageId, contactId)) {
                activeAgentTasks[sourceMessageId] = restored
                agentRuntimeTurnIds[restored] = storedTurnId
                connectorConversationId(conversationId, restored, storedTurnId)?.let {
                    agentRuntimeConversationIds[restored] = it
                }
                Log.i(
                    "SignalASIAgent",
                    "Recovered connector response source=$sourceMessageId from saved task ${storedTurnId.take(8)}"
                )
                return restored
            }
        }
        return mobileNativeAgent.takeIf {
            it.canAcceptConnectorResponse(sourceMessageId, contactId)
        }
    }

    private fun consumeOrphanedAgentConnectorResponse(response: AgentConnectorResponse): Boolean {
        val conversationId = response.conversationId.trim()
            .takeIf(String::isNotBlank)
            ?.let(agentTranscriptStore::resolveMergedConversationId)
            ?: return false
        val turnId = latestUnansweredAgentTurnId(conversationId) ?: return false
        val taskId = response.taskId.ifBlank { turnId }
        val stored = agentTranscriptStore.upsert(
            role = AgentTranscriptRole.ASSISTANT,
            text = response.content,
            dedupeKey = "connector-response:$taskId",
            conversationId = conversationId,
            turnId = turnId,
            taskId = taskId,
            richOutputJson = response.richOutputJson
        )
        if (!stored) return false
        agentTranscriptStore.deleteByDedupeKey(conversationId, "connector-task:$taskId")
        AgentConnectorResponseStore.remove(this, response)
        completedConnectorTaskIds.add(taskId)
        agentTranscriptStore.recordUsage(
            conversationId, response.inputTokens, response.outputTokens, response.costMicros
        )
        if (conversationId == agentTranscriptStore.activeConversation().id) {
            renderAgentTranscript(agentTranscriptStore.list(conversationId))
            refreshAgentConversationHeader()
        }
        Log.i(
            "SignalASIAgent",
            "Recovered orphan connector response source=${response.sourceMessageId} turn=${turnId.take(8)}"
        )
        return true
    }

    private fun latestUnansweredAgentTurnId(conversationId: String): String? {
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

    private fun connectorConversationId(
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

    override fun onBackPressed() {
        if (featurePage.visibility == View.VISIBLE) {
            performFeatureBack()
            return
        }
        if (chatPage.visibility == View.VISIBLE) {
            chatPage.visibility = View.GONE
            wakePage.visibility = View.GONE
            mainPage.visibility = View.VISIBLE
            showMainTab(PAGE_MESSAGES)
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

    override fun onMessage(payload: String) {
        runOnUiThread {
            val envelope = runCatching { JSONObject(payload) }.getOrNull()
            envelope?.optString("desktop_id")?.takeIf(String::isNotBlank)?.let(::markDesktopDomainAvailableById)
            if (envelope?.optString("type") == "delivery_ack") {
                val acknowledgedId = envelope.optString("source_message_id")
                    .ifBlank { envelope.optString("reply_to") }
                    .toLongOrNull()
                if (acknowledgedId != null) {
                    runtimeForConnectorResponse(acknowledgedId, "")
                        ?.recordConnectorTransportAccepted(acknowledgedId)
                }
            }
            if (handleDesktopRemoteControlEvent(envelope)) return@runOnUiThread
            if (handleAgentTaskEvent(envelope)) return@runOnUiThread
            val msg = parseIncomingMessage(payload)
            if (msg.content.isBlank()) return@runOnUiThread
            markDesktopDomainAvailable(msg.contact.id)
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
            val supersededResponse = sourceMessageId > 0L && sourceMessageId in supersededConnectorSourceIds
            val matchingAgentRuntime = if (sourceMessageId > 0L) {
                runtimeForConnectorResponse(
                    sourceMessageId,
                    msg.contact.id,
                    responseConversationId,
                    responseTurnId
                )
            } else null
            val nativeAgentResponse = supersededResponse || matchingAgentRuntime != null
            if (publishAgentConnectorResponse(envelope, msg)) return@runOnUiThread
            msg.deliveryTrace.add(newTraceEvent("phone_reply_received", msg.taskId))
            msg.deliveryTrace.add(newTraceEvent("received", "MQTT inbound"))
            msg.deliveryTrace.add(newTraceEvent("decrypted", "SignalASI Link"))
            addMessage(msg, fromIncoming = true)
            if (supersededResponse) supersededConnectorSourceIds.remove(sourceMessageId)
            if (responseTaskId.isNotBlank()) {
                completedConnectorTaskIds.add(responseTaskId)
            }
            if (!nativeAgentResponse && resolvedResponseConversationId.isNotBlank()) {
                val directResponseTurnId = responseTurnId.ifBlank {
                    latestUnansweredAgentTurnId(resolvedResponseConversationId).orEmpty()
                }
                agentTranscriptStore.append(
                    AgentTranscriptRole.ASSISTANT,
                    msg.content,
                    conversationId = resolvedResponseConversationId,
                    turnId = directResponseTurnId,
                    taskId = envelope?.optString("task_id").orEmpty(),
                    richOutputJson = AgentRichContentCodec.fromEnvelope(envelope)
                )
                if (resolvedResponseConversationId == agentTranscriptStore.activeConversation().id) {
                    renderAgentTranscript(agentTranscriptStore.list(resolvedResponseConversationId))
                }
            }
            if (!nativeAgentResponse) {
                showVoiceAssistantReply(msg)
                maybeSpeakIncomingReply(msg)
            }
        }
    }

    private fun handleAgentTaskEvent(envelope: JSONObject?): Boolean {
        if (envelope?.optString("type") != "agent_task_event") return false
        val sourceMessageId = envelope.optString("source_message_id").toLongOrNull()
            ?: envelope.optLong("source_message_id", 0L).takeIf { it > 0L }
            ?: return true
        val contactId = envelope.optString("contact_id").takeIf { it.isNotBlank() }
            ?: selectedContact?.id
            ?: return true
        markDesktopDomainAvailable(contactId)
        if (sourceMessageId in supersededConnectorSourceIds) return true
        val status = envelope.optString("task_status")
        val taskId = envelope.optString("task_id")
        updateAgentRegistryTaskHeartbeat(contactId, status)
        if (taskId in completedConnectorTaskIds && status !in setOf("completed", "failed", "cancelled", "timed_out")) {
            return true
        }
        val statusSeq = envelope.optLong("status_seq", 0L)
        val existingMessage = messages[contactId]?.firstOrNull { it.id == sourceMessageId }
        if (existingMessage != null && statusSeq > 0L && statusSeq < existingMessage.taskStatusSeq) return true
        val baseStatusLabel = when (status) {
            "accepted" -> getString(R.string.agent_task_status_accepted)
            "queued" -> getString(R.string.agent_task_status_queued)
            "starting" -> getString(R.string.agent_task_status_starting)
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
        if (existingMessage != null) {
            val eventTrace = incomingDeliveryTrace(envelope).apply {
                add(newTraceEvent("phone_task_event_received", status))
            }
            mergeDeliveryTrace(sourceMessageId, contactId, eventTrace, statusLabel)
        } else {
            ChatHistoryStore.applyAgentTaskEvent(this, envelope)
            reloadChatHistoryIfChanged(force = true)
        }
        val envelopeConversationId = envelope.optString("conversation_id")
        val envelopeTurnId = envelope.optString("turn_id")
        val taskRuntime = runtimeForConnectorResponse(
            sourceMessageId,
            contactId,
            envelopeConversationId,
            envelopeTurnId
        )
        val nativeState = taskRuntime?.recordConnectorTaskStatus(
            sourceMessageId = sourceMessageId,
            contactId = contactId,
            taskId = taskId,
            taskStatus = status,
            statusSeq = statusSeq
        )
        val targetName = contactById(contactId).name
        val turnId = envelopeTurnId.takeIf { it.isNotBlank() }
            ?: taskRuntime?.let(agentRuntimeTurnIds::get).orEmpty()
        val conversationId = connectorConversationId(
            envelopeConversationId,
            taskRuntime,
            turnId
        ) ?: return true
        // A task can gain a Codex turn id after it starts. Keep one stable key so
        // accepted, running steps, and completion update one process row in place.
        val connectorProcessKey = "connector-task:$taskId"
        if (turnId.isNotBlank()) {
            agentTranscriptStore.deleteByDedupeKey(conversationId, "connector-turn:$turnId")
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
        if (taskId.isNotBlank()) {
            val taskStore = SharedPreferencesAgentTaskStore(this)
            val existingTask = taskStore.find(taskId)
            val outputFiles = buildList {
                val files = envelope.optJSONArray("output_files") ?: org.json.JSONArray()
                for (index in 0 until files.length()) {
                    val item = files.optJSONObject(index) ?: continue
                    item.optString("relative_path").takeIf { it.isNotBlank() }?.let(::add)
                }
            }
            val sourceGoal = agentTranscriptStore.list(conversationId)
                .lastOrNull { it.turnId == turnId && it.role == AgentTranscriptRole.USER }
                ?.text.orEmpty()
            val eventTime = listTime(envelope.optLong("updated_at", System.currentTimeMillis()))
            val eventLine = "$eventTime · $targetName · $statusLabel"
            val executionLog = (existingTask?.executionLog.orEmpty() + eventLine)
                .distinct()
                .takeLast(60)
            taskStore.upsert(AgentTaskRecord(
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
                result = envelope.optString("error").ifBlank { existingTask?.result.orEmpty() },
                verification = existingTask?.verification.orEmpty(),
                outputFiles = if (outputFiles.isNotEmpty()) outputFiles else existingTask?.outputFiles.orEmpty(),
                executionLog = executionLog,
                createdAtMillis = existingTask?.createdAtMillis
                    ?: envelope.optLong("created_at", System.currentTimeMillis()),
                updatedAtMillis = envelope.optLong("updated_at", System.currentTimeMillis())
            ))
        }
        if (conversationId == agentTranscriptStore.activeConversation().id) {
            renderAgentTranscript(agentTranscriptStore.list(conversationId))
        }
        if (nativeState != null) {
            renderAgentState(nativeState, conversationId, turnId)
            when (status) {
                "cancelled" -> {
                    activeAgentTasks.remove(sourceMessageId)
                    taskRuntime?.cancelCurrentTask()?.let {
                        renderAgentState(it, conversationId, turnId)
                    }
                }
                "failed", "timed_out", "not_found" -> AgentConnectorResponseBus.publish(
                    this,
                    AgentConnectorResponse(
                        sourceMessageId = sourceMessageId,
                        contactId = contactId,
                        content = envelope.optString("error").ifBlank { statusLabel },
                        conversationId = conversationId,
                        turnId = turnId,
                        taskId = taskId,
                        success = false
                    )
                )
            }
        }
        return true
    }

    private fun markDesktopDomainAvailable(contactId: String) {
        val desktopId = AppStore.desktopIdForContact(this, contactId)
        if (desktopId.isNotBlank()) markDesktopDomainAvailableById(desktopId)
    }

    private fun markDesktopDomainAvailableById(desktopId: String) {
        AgentResourceHealthStore(this).markAvailable("domain:$desktopId")
    }

    private fun syncAgentRegistrySnapshot(force: Boolean = false) {
        if (!::encryptedAgentRegistry.isInitialized || !::mobileNativeAgent.isInitialized) return
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

    private fun agentRegistrationMetadataChanged(
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

    private fun updateAgentRegistryTaskHeartbeat(contactId: String, taskStatus: String) {
        if (contactId.isBlank() || !::encryptedAgentRegistry.isInitialized) return
        syncAgentRegistrySnapshot(force = true)
        val registrations = encryptedAgentRegistry.list()
        val registration = registrations.firstOrNull { it.agentId == contactId }
            ?: registrations.firstOrNull { it.deviceId == contactId }
            ?: registrations.firstOrNull { it.agentId.endsWith(":$contactId") || contactId.endsWith(":${it.agentId}") }
            ?: return
        val endpointStatus = when (taskStatus) {
            "accepted", "queued", "starting", "running", "waiting_input", "waiting_approval" -> AgentEndpointStatus.BUSY
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

    private fun reconcileRecoverableAgentRuns() {
        if (!::agentRunEventStore.isInitialized || !::agentRunRecorder.isInitialized) return
        val registrations = encryptedAgentRegistry.list()
        val recoverableSource = {
            agentHandoffStore.active().map { handoff ->
                AgentRecoverableRun(
                    handle = AgentRunHandle(
                        runId = handoff.request.runId,
                        taskId = handoff.request.taskId,
                        agentId = handoff.request.toAgentId,
                        remoteRunId = handoff.sourceMessageId.takeIf { it > 0L }?.toString()
                            ?: handoff.request.runId,
                        acceptedAtMillis = handoff.request.createdAtMillis
                    ),
                    lastEventSequence = handoff.request.checkpoint["last_event_sequence"]
                        ?.toString()?.toLongOrNull() ?: 0L,
                    checkpoint = handoff.request.checkpoint
                )
            }
        }
        val provider = ActionExecutorAgentProvider(
            registrationSource = { AppStoreAgentConnectorRegistry(this).registrations() },
            delegate = AndroidAgentActionExecutor(this),
            recoverableSource = recoverableSource,
            runStartReceipts = EncryptedAgentRunStartReceiptStore(this),
            healthLedger = EncryptedAgentProviderHealthLedger(this),
            managedResponses = EncryptedAgentManagedResponseLedger(this)
        )
        val directory = AgentAdapterDirectory().apply { register(provider) }
        val results = runBlocking {
            AgentRunRecoveryCoordinator(
                runStore = agentRunEventStore,
                workspaceStore = EncryptedAgentWorkspaceStore(this@MainActivity),
                recordedRun = agentRunRecorder::run,
                registration = { agentId, deviceId ->
                    registrations.firstOrNull { it.agentId == agentId }
                        ?: registrations.firstOrNull { it.deviceId == deviceId }
                },
                adapterResolver = directory::resolveAdapter,
                markInterrupted = { runId, reason -> agentRunRecorder.markInterrupted(runId, reason) }
            ).recover()
        }
        results.filter { it.outcome in setOf(
            AgentRunRecoveryOutcome.RESTORED_LOCAL_WAIT,
            AgentRunRecoveryOutcome.RECONNECTED_REMOTE,
            AgentRunRecoveryOutcome.WAITING_FOR_REMOTE,
            AgentRunRecoveryOutcome.ALREADY_CURRENT
        ) }.forEach { result ->
            agentRunEventStore.events(result.runId).lastOrNull()?.messageId
                ?.takeIf(String::isNotBlank)
                ?.let { messageId -> agentRunIdsByTurn[messageId] = result.runId }
        }
        reconcileStructuredAgentHandoffs()
    }

    private fun reconcileStructuredAgentHandoffs() {
        if (!::agentHandoffStore.isInitialized) return
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
            handleSecurityScan(scanResult.contents)
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
        sendImage(data?.data ?: return)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_RECORD_AUDIO && grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, getString(R.string.voice_record_permission_granted), Toast.LENGTH_SHORT).show()
            if (activeMainTab == PAGE_VOICE) startVoiceAssistant()
            if (activeMainTab == PAGE_AGENT) startAgentVoiceInput()
        }
        if (requestCode == REQUEST_AGENT_CAMERA_PERMISSION &&
            grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED
        ) {
            openAgentCamera()
        }
        if (requestCode == REQUEST_CONTROL_CENTER_PERMISSION) {
            if (pendingVoiceEnableFromControlCenter) {
                if (grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
                    VoiceAssistantSettings.setEnabled(this, true)
                }
                pendingVoiceEnableFromControlCenter = false
            }
            if (featurePage.visibility == View.VISIBLE) {
                if (controlCenterDestination != null) renderCurrentControlCenterDestination()
                else showOnDeviceAgentFeaturePage()
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
    private fun displayContactName(contact: Contact): String = when (contact.id) {
        CONTACT_SYSTEM.id -> getString(R.string.chat_system_notice)
        CONTACT_ME.id -> getString(R.string.chat_me)
        else -> contact.name
    }

    private fun showChatPage(contact: Contact) {
        reloadChatHistoryIfChanged()
        selectedContact = contact
        val raw = AppStore.contactById(this, contact.id)
        val isCloud = raw?.optString("delivery_mode") == "cloud_api"
        chatTitle.text = displayContactName(contact)
        chatModelTag.visibility = if (isCloud) View.VISIBLE else View.GONE
        statusDot.visibility = if (isCloud) View.GONE else View.VISIBLE
        chatSubtitle.visibility = if (isCloud) View.GONE else View.VISIBLE
        chatSubtitle.text = when {
            contact.id == CONTACT_SYSTEM.id -> getString(R.string.chat_system_notice)
            else -> getString(R.string.chat_link_encrypted)
        }
        chatModelButton.visibility = if (isCloud) View.VISIBLE else View.GONE
        chatModelButton.background = getDrawable(R.drawable.model_selector_background)
        chatModelLabel.text = if (isCloud) selectedCloudModelLabel(contact.id) else ""
        chatModelButton.setOnClickListener {
            if (isCloud) showCloudModelSwitchPage(contact)
        }
        chatAvatar.setImageResource(contactAvatarRes(contact))
        messageInput.clearFocus()
        hideKeyboard()
        summaries[contact.id]?.unreadCount = 0
        markContactRead(contact.id)
        messageAdapter = MessageAdapter(currentMessages,
            onPlayVoiceMessage = { msgId -> playVoiceMessage(msgId) },
            onMessageActions = { position -> showMessageActionsPage(position) })
        messageList.adapter = messageAdapter
        val notificationsOnly = contact.id == CONTACT_SYSTEM.id
        chatInputBar.visibility = if (notificationsOnly) View.GONE else View.VISIBLE
        wakePage.visibility = View.GONE
        mainPage.visibility = View.GONE
        featurePage.visibility = View.GONE
        chatPage.visibility = View.VISIBLE
        messageList.post { scrollToBottom() }
        handler.postDelayed({ scrollToBottom() }, 250L)
        handler.postDelayed({ scrollToBottom() }, 700L)
        refreshContactList()
    }

    private fun scrollToBottom() {
        val lastIndex = (messageList.adapter?.itemCount ?: currentMessages.size) - 1
        if (lastIndex >= 0) {
            messageAdapter?.notifyDataSetChanged()
            (messageList.layoutManager as? LinearLayoutManager)
                ?.scrollToPositionWithOffset(lastIndex, 0)
                ?: messageList.scrollToPosition(lastIndex)
        }
    }

    private fun showContactPage() {
        showMainTab(PAGE_MESSAGES)
    }

    // ===== Contacts =====
    private fun configureContacts() {
        val items = buildDirectoryContacts()
        directoryContacts.clear()
        directoryContacts.addAll(items)
        directoryAdapter = ContactAdapter(directoryContacts, summaries, { contact ->
            showContactDetail(contact)
        }, { contact ->
            confirmDeleteContact(contact)
        }, showSummary = false)
        findViewById<RecyclerView>(R.id.directoryList).apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = directoryAdapter
        }

        val chatItems = buildChatContacts()
        ensureDesignSummaries()
        contactAdapter = ContactAdapter(chatItems, summaries, { contact ->
            showChatPage(contact)
        }, { contact ->
            confirmDeleteChat(contact)
        }, showSummary = true)
        findViewById<RecyclerView>(R.id.contactList).apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = contactAdapter
        }
    }

    private fun configureMainTabs() {
        mainTitle.setOnClickListener {
            if (activeMainTab == PAGE_MESSAGES && mainPage.visibility == View.VISIBLE) {
                showMainTab(PAGE_VOICE)
            }
        }
        findViewById<View>(R.id.settingsMessagesButton).setOnClickListener { showMainTab(PAGE_MESSAGES) }
        findViewById<View>(R.id.settingsContactsButton).setOnClickListener { showMainTab(PAGE_CONTACTS) }
        findViewById<View>(R.id.settingsDiscoverButton).setOnClickListener { showMainTab(PAGE_DISCOVER) }
        findViewById<View>(R.id.settingsAgentMemoryButton).setOnClickListener { showAgentMemoryPage() }
        findViewById<View>(R.id.settingsAgentKnowledgeButton).setOnClickListener { showAgentKnowledgePage() }
        findViewById<View>(R.id.settingsAgentControlButton).setOnClickListener { showOnDeviceAgentFeaturePage() }
        findViewById<View>(R.id.settingsRecentTasksButton).setOnClickListener { showAgentRecentTasksPage() }
        meProfileText.setOnClickListener { showEditNicknameDialog() }
        findViewById<View>(R.id.meProfileCard).setOnClickListener { showEditNicknameDialog() }
        meAvatar.setOnClickListener { pickAvatar() }
        mainActionButton.setOnClickListener {
            showAddContactMenu()
        }
        findViewById<View>(R.id.newFriendsButton).setOnClickListener { showFriendRequestsDialog() }
        findViewById<View>(R.id.groupChatsButton).setOnClickListener { showGroupFeaturePage() }
        findViewById<View>(R.id.myAgentsButton).setOnClickListener { showAgentFeaturePage() }
        findViewById<View>(R.id.myDevicesButton).setOnClickListener { showDeviceFeaturePage() }
        findViewById<View>(R.id.aiAgentButton).setOnClickListener { showAgentFeaturePage() }
        findViewById<View>(R.id.deviceCenterButton).setOnClickListener { showDeviceFeaturePage() }
        findViewById<View>(R.id.automationButton).setOnClickListener { showAutomationFeaturePage() }
        findViewById<View>(R.id.securityCenterButton).setOnClickListener { showSecurityFeaturePage() }
        findViewById<View>(R.id.labButton).setOnClickListener { showLocalModelFeaturePage() }
        findViewById<TextView>(R.id.scanButton).setOnClickListener {
            scanMode = "contact"
            startSecurityScan()
        }
        findViewById<TextView>(R.id.myQrButton).setOnClickListener { showMyQrPayload() }
        findViewById<TextView>(R.id.createGroupButton).setOnClickListener { showCreateGroupFeaturePage() }
        findViewById<View>(R.id.exportBackupButton).setOnClickListener { showExportBackupDialog() }
        findViewById<View>(R.id.importBackupButton).setOnClickListener { openBackupImportPicker() }
        findViewById<View>(R.id.languageSettingsButton).setOnClickListener { showLanguageSettingsPage() }
        findViewById<View>(R.id.protocolQualityButton).setOnClickListener { showProtocolQualityFeaturePage() }
        findViewById<View>(R.id.advancedOptionsButton).setOnClickListener { showAdvancedOptionsFeaturePage() }
        findViewById<View>(R.id.localModelSettingsButton).setOnClickListener { showLocalModelFeaturePage() }
        findViewById<View>(R.id.voiceAssistantSettingsButton).setOnClickListener { showVoiceAssistantSettingsPage() }
        findViewById<View>(R.id.onDeviceAgentButton).setOnClickListener { showOnDeviceAgentFeaturePage() }
        findViewById<View>(R.id.destroyDataButton).setOnClickListener { confirmDestroyAllData() }
        findViewById<View>(R.id.aboutSignalASIButton).setOnClickListener { showAboutSignalASIPage() }
        backButton.setOnClickListener { showContactPage() }
        setFeatureBackAction()
    }

    private fun configureAgentPage() {
        agentOutputScroll.setOnScrollChangeListener { view, _, scrollY, _, _ ->
            val child = (view as ScrollView).getChildAt(0)
            val remaining = child?.height?.minus(scrollY + view.height) ?: 0
            agentTranscriptAutoFollow = remaining <= dp(56)
            if (!initialAgentHydrationPending && !agentTranscriptAutoFollow && scrollY <= dp(12)) {
                loadOlderAgentTranscriptEntries()
            }
        }
        findViewById<View>(R.id.agentSessionSummary).setOnClickListener { showAgentSessionsPage() }
        agentSettingsButton.setOnClickListener { showMainTab(PAGE_SETTINGS) }
        agentInsightBar.setOnClickListener { showGlobalInsightsDialog() }
        agentPermissionModeButton.setOnClickListener {
            val next = nextAgentPermissionMode(mobileNativeAgent.safetySettings().permissionMode)
            renderAgentState(mobileNativeAgent.updatePermissionMode(next))
            Toast.makeText(this, getString(R.string.on_device_agent_mode_changed, permissionModeLabel(next)), Toast.LENGTH_SHORT).show()
        }
        agentHighRiskGuardButton.setOnClickListener {
            val next = !mobileNativeAgent.safetySettings().highRiskGuard
            renderAgentState(mobileNativeAgent.updateHighRiskGuard(next))
        }
        agentMemoryCaptureButton.setOnClickListener {
            val next = !mobileNativeAgent.safetySettings().memoryCapture
            renderAgentState(mobileNativeAgent.updateMemoryCapture(next))
        }
        agentMemoryText.setOnClickListener { showAgentMemoryPage() }
        agentKnowledgeText.setOnClickListener { showAgentKnowledgePage() }
        agentAttachButton.setOnClickListener { showAgentAttachmentMenu() }
        agentSubmitButton.setOnClickListener {
            Log.d("SignalASIAgent", "Agent submit clicked")
            handleAgentPrimaryAction()
        }
        agentGoalInput.setOnEditorActionListener { _, _, _ ->
            submitAgentGoal()
            true
        }
        agentGoalInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                updateAgentSubmitButtonAppearance(s?.isNotBlank() == true || agentInputAttachments.isNotEmpty())
            }
            override fun afterTextChanged(s: Editable?) = Unit
        })
        updateAgentSubmitButtonAppearance(agentGoalInput.text?.isNotBlank() == true || agentInputAttachments.isNotEmpty())
        agentScreenSearchInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                latestAgentScreenContext?.let { renderAgentScreenDetails(it) }
            }
            override fun afterTextChanged(s: Editable?) = Unit
        })
        agentHoldToTalkController = AppleHoldToTalkController(
            activity = this,
            pressButton = agentVoiceButton,
            idleContent = agentInputContent,
            recordingGroup = agentRecordingCenter,
            waveform = agentRecordingWaveform,
            timer = agentRecordingTimer,
            hasPermission = {
                checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
            },
            requestPermission = {
                requestPermissions(arrayOf(android.Manifest.permission.RECORD_AUDIO), REQUEST_RECORD_AUDIO)
            },
            startRecording = { startRecording("agent_input") },
            currentAmplitude = { recorder?.maxAmplitude ?: 0 },
            finishRecording = { send -> stopAgentInputRecording(send) }
        )
        agentVoiceButton.setOnTouchListener(agentHoldToTalkController)
    }

    private fun loadOlderAgentTranscriptEntries() {
        if (agentTranscriptPageLoading || agentTranscriptAllLoaded || agentRenderedConversationId.isBlank()) return
        agentTranscriptPageLoading = true
        val conversationId = agentRenderedConversationId
        thread(name = "signalasi-agent-transcript-page") {
            val entries = runCatching { agentTranscriptStore.list(conversationId) }.getOrDefault(emptyList())
            runOnUiThread {
                if (conversationId != agentRenderedConversationId) {
                    agentTranscriptPageLoading = false
                    return@runOnUiThread
                }
                val firstRenderedId = renderedAgentTranscriptIds.firstOrNull()
                val firstRenderedIndex = entries.indexOfFirst { it.id == firstRenderedId }
                val currentlyVisible = if (firstRenderedIndex >= 0) {
                    entries.size - firstRenderedIndex
                } else {
                    agentTranscriptVisibleLimit
                }
                val nextLimit = minOf(
                    entries.size,
                    maxOf(agentTranscriptVisibleLimit, currentlyVisible) + AGENT_TRANSCRIPT_PAGE_ITEMS
                )
                agentTranscriptAllLoaded = nextLimit >= entries.size
                if (nextLimit > currentlyVisible) {
                    agentTranscriptVisibleLimit = nextLimit
                    agentOutputList.removeAllViews()
                    renderedAgentTranscriptIds.clear()
                    renderAgentTranscript(entries)
                }
                agentTranscriptPageLoading = false
            }
        }
    }

    private fun resetAgentTranscriptRendering(conversationId: String = "") {
        agentOutputList.removeAllViews()
        renderedAgentTranscriptIds.clear()
        agentTranscriptVisibleLimit = INITIAL_VISIBLE_AGENT_TRANSCRIPT_ITEMS
        agentTranscriptPageLoading = false
        agentTranscriptAllLoaded = false
        agentRenderedConversationId = conversationId
    }

    private fun cancelRemoteAgentTask(state: AgentUiState) {
        val result = state.lastActionResult
        val taskId = result?.metadata?.get("remote_task_id").orEmpty()
        val contactId = result?.metadata?.get("contact_id").orEmpty()
        val sourceMessageId = result?.metadata?.get("source_message_id")?.toLongOrNull() ?: 0L
        if (taskId.isBlank() || contactId.isBlank() || sourceMessageId <= 0L) {
            renderAgentState(mobileNativeAgent.cancelCurrentTask())
            return
        }
        val sent = SignalASIMqttClient.publishAgentTaskCancel(
            taskId = taskId,
            contactId = contactId,
            sourceMessageId = sourceMessageId,
            topicOverride = AppStore.outgoingTopicForContact(this, contactId)
        )
        if (sent) {
            agentTranscriptStore.upsert(
                AgentTranscriptRole.PROCESS,
                "${contactById(contactId).name} · ${getString(R.string.agent_task_status_cancelling)}",
                dedupeKey = "remote-task:$taskId"
            )
            renderAgentTranscript(agentTranscriptStore.list())
        } else {
            Toast.makeText(this, getString(R.string.delivery_status_send_failed), Toast.LENGTH_SHORT).show()
        }
    }

    private fun startAgentScreenUnderstanding() {
        if (AgentScreenCaptureService.requestCapture(this)) {
            Toast.makeText(this, getString(R.string.agent_screen_capture_running), Toast.LENGTH_SHORT).show()
            return
        }
        val manager = getSystemService(android.media.projection.MediaProjectionManager::class.java)
        startActivityForResult(manager.createScreenCaptureIntent(), REQUEST_AGENT_SCREEN_CAPTURE)
    }

    private fun handleAgentPrimaryAction() {
        if (!agentGoalInput.text?.toString()?.trim().isNullOrBlank() || agentInputAttachments.isNotEmpty()) {
            submitAgentGoal()
            return
        }
        val state = mobileNativeAgent.snapshot()
        if (state.phase == AgentPhase.PAUSED) {
            renderAgentState(mobileNativeAgent.resumeCurrentTask())
        } else if (state.phase == AgentPhase.WAITING_RESPONSE) {
            Toast.makeText(this, getString(R.string.agent_empty_goal), Toast.LENGTH_SHORT).show()
        } else if (state.pendingAction != null) {
            if (state.pendingAction.risk.weight >= AgentRisk.HIGH.weight) {
                showHighRiskAgentConfirmation(state.pendingAction)
            } else {
                runAgentOperationAsync { mobileNativeAgent.approveNextAction() }
            }
        } else {
            submitAgentGoal()
        }
    }

    private fun showHighRiskAgentConfirmation(action: AgentAction) {
        android.app.AlertDialog.Builder(this)
            .setTitle(getString(R.string.agent_high_risk_confirmation_title))
            .setMessage(
                getString(
                    R.string.agent_high_risk_confirmation_message,
                    action.description,
                    action.target.ifBlank { "-" },
                    action.risk.name
                )
            )
            .setPositiveButton(getString(R.string.agent_high_risk_confirmation_execute)) { _, _ ->
                runAgentOperationAsync { mobileNativeAgent.approveNextAction(highRiskConfirmed = true) }
            }
            .setNegativeButton(getString(R.string.common_cancel), null)
            .show()
    }

    private fun runAgentOperationAsync(
        onComplete: (AgentUiState) -> Unit = {},
        operation: () -> AgentUiState
    ) {
        if (agentOperationInFlight) return
        agentOperationInFlight = true
        thread(name = "signalasi-agent-operation") {
            val outcome = runCatching(operation)
            runOnUiThread {
                agentOperationInFlight = false
                val state = outcome.getOrElse { mobileNativeAgent.snapshot() }
                renderAgentState(state)
                onComplete(state)
                consumePendingAgentConnectorResponses()
                outcome.exceptionOrNull()?.let { error ->
                    Toast.makeText(this@MainActivity, error.message ?: "Agent operation failed", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun submitAgentGoal() {
        val goal = agentGoalInput.text?.toString()?.trim().orEmpty()
        val attachments = agentInputAttachments.toList()
        if (goal.isBlank() && attachments.isEmpty()) {
            Toast.makeText(this, getString(R.string.agent_empty_goal), Toast.LENGTH_SHORT).show()
            return
        }
        val conversation = agentTranscriptStore.activeConversation()
        AgentLearningAnalyzer.correctionFeedback(goal)?.let { feedback ->
            agentRunRecorder.addFeedback(conversation.id, feedback)?.let { correctedRun ->
                agentLearningEngine.observeFeedback(correctedRun, agentRunRecorder.recentRuns())
            }
        }
        val turnId = UUID.randomUUID().toString()
        val attachmentLabel = when (attachments.size) {
            0 -> ""
            1 -> "[${attachments.first().displayName}]"
            else -> getString(R.string.agent_attachment_count, attachments.size)
        }
        agentTranscriptStore.append(
            AgentTranscriptRole.USER,
            goal.ifBlank { attachmentLabel },
            conversationId = conversation.id,
            turnId = turnId,
            taskId = turnId,
            richOutputJson = AgentRichContentCodec.encode(attachments.map(AgentInputAttachment::richBlock))
        )
        refreshGlobalAgentCognition()
        AgentTurnAttachmentRegistry.put(turnId, attachments)
        refreshAgentConversationHeader()
        renderAgentTranscript(agentTranscriptStore.list())
        agentGoalInput.setText("")
        agentInputAttachments.clear()
        renderAgentInputAttachments()
        agentGoalInput.clearFocus()
        getSystemService(InputMethodManager::class.java)
            .hideSoftInputFromWindow(agentGoalInput.windowToken, 0)
        val baseGoal = goal.ifBlank { getString(R.string.agent_attachment_default_goal) }
        if (attachments.isEmpty()) {
            continueAgentGoalSubmission(baseGoal, conversation.id, turnId)
            return
        }
        thread(name = "signalasi-agent-attachments") {
            val staged = runCatching {
                AgentAttachmentWorkspaceStager.stage(
                    applicationContext,
                    conversation.id,
                    turnId,
                    attachments
                )
            }.getOrDefault(emptyList())
            val executionGoal = buildString {
                append(baseGoal)
                append("\n\nAttached input:\n")
                attachments.forEach { attachment ->
                    append("- ").append(attachment.displayName)
                    append(" (").append(attachment.mimeType).append(", ")
                    append(AgentInputAttachment.humanSize(attachment.sizeBytes)).append(")\n")
                }
                if (staged.isNotEmpty()) {
                    append("Phone project paths (untrusted user content):\n")
                    staged.forEach { item ->
                        append("- ").append(item.relativePath)
                            .append(" | sha256=").append(item.sha256).append("\n")
                    }
                }
                if (goal.isBlank()) {
                    append("Do not inspect the attached content until the user provides a task.")
                } else {
                    append("Use the attached content when completing the request.")
                }
            }
            runOnUiThread {
                continueAgentGoalSubmission(
                    executionGoal,
                    conversation.id,
                    turnId,
                    forcedAction = if (staged.isEmpty()) attachmentConnectorAction(executionGoal) else null
                )
            }
            if (goal.isNotBlank() && !conversation.privateMode && !conversation.trackingPaused) {
                attachments.filterNot { attachment ->
                    attachment.mimeType.startsWith("image/") ||
                        attachment.mimeType.startsWith("video/") ||
                        attachment.mimeType.startsWith("audio/")
                }.forEach { attachment ->
                    runCatching { AgentKnowledgeImporter(applicationContext).importDocument(attachment.uri) }
                }
            }
        }
    }

    private fun attachmentConnectorAction(goal: String): AgentAction? {
        val targets = AppStoreAgentConnectorRegistry(this).availableTargets()
        val target = listOf("codex", "hermes").firstNotNullOfOrNull { preferredId ->
            targets.firstOrNull { candidate ->
                candidate.status == AgentConnectorStatus.AVAILABLE &&
                    (candidate.id == preferredId || candidate.id.endsWith(":$preferredId"))
            }
        } ?: return null
        return AgentAction(
            id = "attachment-${target.id}",
            kind = AgentActionKind.CALL_CONNECTOR,
            target = target.title,
            risk = AgentRisk.LOW,
            status = AgentActionStatus.PENDING_CONFIRMATION,
            description = "Process the attached input with ${target.title}",
            parameters = mapOf("connector_id" to target.id, "prompt" to goal),
            requiresConfirmation = false
        )
    }

    private fun continueAgentGoalSubmission(
        goal: String,
        conversationId: String,
        turnId: String,
        forcedAction: AgentAction? = null
    ) {
        val routingStartedAt = SystemClock.elapsedRealtime()
        val localConversationContext = agentTranscriptStore.context(conversationId)
        AgentFastLocalResponse.reply(goal, localConversationContext)?.let { response ->
            agentTranscriptStore.append(
                AgentTranscriptRole.ASSISTANT,
                response,
                dedupeKey = "fast-local:$turnId",
                conversationId = conversationId,
                turnId = turnId,
                taskId = turnId
            )
            Log.d("SignalASIAgent", "fast_local_completed turn=${turnId.take(8)}")
            renderAgentTranscript(agentTranscriptStore.list(conversationId))
            return
        }
        val conversationContext = globalSuperAgentRuntime.augmentContext(
            localConversationContext,
            goal
        )
        if (handleAgentSkillCommand(goal, conversationId, turnId)) return
        val skillMatch = agentSkillMatcher.match(goal)
        val deterministicAction = forcedAction ?: deterministicSystemActionFor(goal, conversationContext)
        Log.d(
            "SignalASIAgent",
            "route_resolved turn=${turnId.take(8)} tool=${deterministicAction?.parameters?.get("tool_id").orEmpty()} " +
                "action=${deterministicAction?.id.orEmpty()} skill=${skillMatch != null} " +
                "elapsed_ms=${SystemClock.elapsedRealtime() - routingStartedAt}"
        )
        val run = agentRunRecorder.begin(
            conversationId = conversationId,
            request = goal,
            activeSkillId = if (deterministicAction == null) skillMatch?.installation?.id.orEmpty() else ""
        )
        val selectedAgentId = (forcedAction ?: deterministicAction)
            ?.parameters?.get("connector_id")
            .orEmpty()
            .ifBlank { "signalasi-mobile" }
        appendRunControlEvent(
            run = run,
            messageId = turnId,
            taskId = turnId,
            agentId = selectedAgentId,
            type = AgentRunControlEventType.RUN_CREATED
        )
        appendRunControlEvent(
            run = run,
            messageId = turnId,
            taskId = turnId,
            agentId = selectedAgentId,
            type = AgentRunControlEventType.RUN_STARTED
        )
        Log.d(
            "SignalASIAgent",
            "run_recorded turn=${turnId.take(8)} elapsed_ms=${SystemClock.elapsedRealtime() - routingStartedAt}"
        )
        agentRunIdsByTurn[turnId] = run.runId
        if (forcedAction != null) {
            Log.d("SignalASIAgent", "route_forced_connector turn=${turnId.take(8)} action=${forcedAction.id}")
            executeConcurrentAgentGoal(
                goal,
                conversationContext,
                conversationId,
                turnId,
                forcedAction
            )
        } else if (deterministicAction != null) {
            if (AgentConfirmationPolicy.tier(deterministicAction) == AgentConfirmationTier.DIRECT) {
                Log.d("SignalASIAgent", "route_direct turn=${turnId.take(8)} action=${deterministicAction.id}")
                executeDirectSystemAction(deterministicAction, conversationId, turnId)
            } else {
                Log.d("SignalASIAgent", "route_protected turn=${turnId.take(8)} action=${deterministicAction.id}")
                executeConcurrentAgentGoal(
                    goal,
                    conversationContext,
                    conversationId,
                    turnId,
                    deterministicAction
                )
            }
        } else if (skillMatch != null && executeMatchedSkill(skillMatch, conversationId, turnId, goal, conversationContext)) {
            return
        } else {
            executeConcurrentAgentGoal(goal, conversationContext, conversationId, turnId)
        }
    }

    private fun updateAgentSubmitButtonAppearance(hasInput: Boolean) {
        agentSubmitButton.setBackgroundResource(
            if (hasInput) R.drawable.agent_send_button_active_background
            else R.drawable.agent_send_button_background
        )
        agentSubmitButton.imageTintList = android.content.res.ColorStateList.valueOf(
            if (hasInput) android.graphics.Color.parseColor("#087F69") else android.graphics.Color.WHITE
        )
    }

    private fun executeConcurrentAgentGoal(
        goal: String,
        conversationContext: AgentConversationContext,
        conversationId: String,
        turnId: String,
        deterministicAction: AgentAction? = null
    ) {
        val workspace = AgentWorkspace(
            workspaceId = turnId,
            sessionId = turnId,
            conversationId = conversationId,
            taskId = turnId,
            goal = goal,
            agentId = deterministicAction?.parameters?.get("connector_id").orEmpty()
                .ifBlank { "signalasi-mobile" },
            deviceId = AppStore.profile(this).optString("device_id")
                .ifBlank { AppStore.profile(this).optString("signalasi_id") },
            status = AgentWorkspaceStatus.CREATED
        )
        AgentTaskRuntime.supervisor(this).submit(workspace, AgentTaskLane.READ_REASONING) {
            val runtime = MobileNativeAgent(
                this@MainActivity,
                planner = deterministicAction?.let { selectedAction ->
                    object : AgentPlanner {
                        override fun plan(request: AgentRequest): AgentPlan =
                            AgentPlanFactory.actions(request, listOf(selectedAction)).copy(
                                plannerProfile = "deterministic-native-route",
                                routeRationale = "An exact phone-native route was selected before model planning."
                            )
                    }
                } ?: GuardedModelAgentPlanner(this@MainActivity),
                sessionStore = SharedPreferencesAgentSessionStore(this@MainActivity, "task:$turnId"),
                nativeToolEventSink = AgentNativeToolEventSink(::recordNativeToolLifecycleEvent)
            )
            provisionalAgentTasks.add(runtime)
            agentRuntimeConversationIds[runtime] = conversationId
            agentRuntimeTurnIds[runtime] = turnId
            val outcome = runCatching {
                var state = runtime.submitGoal(goal, conversationContext, turnId)
                var approvals = 0
                while (state.pendingAction != null &&
                    state.phase != AgentPhase.WAITING_RESPONSE &&
                    state.phase != AgentPhase.WAITING_CONFIRMATION &&
                    state.pendingAction.risk != AgentRisk.BLOCKED &&
                    approvals++ < 32
                ) {
                    state = runtime.approveNextAction(highRiskConfirmed = true)
                }
                state
            }
            val state = outcome.getOrElse { runtime.snapshot() }
            persistAgentWorkspaceSnapshot(turnId, state)
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
                    activeAgentTasks[sourceId] = runtime
                    provisionalAgentTasks.remove(runtime)
                    scheduleConnectorTimeouts(runtime, sourceId, conversationId, turnId)
                }
                renderAgentState(state, conversationId, turnId)
                recordAgentRunFromState(turnId, state)
                requestMissingAgentNativePermissions(state)
                consumePendingAgentConnectorResponses()
                outcome.exceptionOrNull()?.let { error ->
                    provisionalAgentTasks.remove(runtime)
                    Toast.makeText(this@MainActivity, error.message ?: "Agent operation failed", Toast.LENGTH_LONG).show()
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

    private fun scheduleConnectorTimeouts(
        runtime: MobileNativeAgent,
        sourceMessageId: Long,
        conversationId: String,
        turnId: String
    ) {
        val metadata = runtime.pendingConnectorMetadata(sourceMessageId)
        if (metadata["resource_location"] != "desktop") return
        val deadlines = AgentConnectorTimingPolicy.deadlines(metadata["has_attachments"] == "true")
        scheduleConnectorTimeout(
            runtime, sourceMessageId, conversationId, turnId,
            deadlines.acceptedMs, AgentConnectorTimeoutStage.NOT_ACCEPTED
        )
        scheduleConnectorTimeout(
            runtime, sourceMessageId, conversationId, turnId,
            deadlines.runningMs, AgentConnectorTimeoutStage.NOT_RUNNING
        )
        if (metadata["routing_requires_live_data"] == "true") {
            scheduleConnectorTimeout(
                runtime, sourceMessageId, conversationId, turnId,
                deadlines.liveStaleMs, AgentConnectorTimeoutStage.READ_ONLY_STALE
            )
        }
    }

    private fun scheduleConnectorTimeout(
        runtime: MobileNativeAgent,
        sourceMessageId: Long,
        conversationId: String,
        turnId: String,
        delayMs: Long,
        stage: AgentConnectorTimeoutStage
    ) {
        handler.postDelayed({
            thread(name = "signalasi-connector-timeout-${stage.name.lowercase(Locale.US)}") {
                val before = runtime.pendingConnectorMetadata(sourceMessageId)
                val state = runtime.handleConnectorTimeout(sourceMessageId, stage) ?: return@thread
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
                    SignalASIMqttClient.publishAgentTaskCancel(
                        taskId = remoteTaskId,
                        contactId = contactId,
                        sourceMessageId = sourceMessageId,
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
                        scheduleConnectorTimeouts(runtime, replacementSourceId, conversationId, turnId)
                    }
                    renderAgentState(state, conversationId, turnId)
                    recordAgentRunFromState(turnId, state)
                }
            }
        }, delayMs)
    }

    private fun deterministicSystemActionFor(
        goal: String,
        conversationContext: AgentConversationContext
    ): AgentAction? {
        val state = mobileNativeAgent.snapshot()
        return RuleBasedAgentPlanner(this).deterministicLocalAction(
            AgentRequest(
                goal = goal,
                screen = state.currentScreen,
                targets = state.callableTargets,
                memories = emptyList(),
                runtimeContext = state.runtimeContext,
                conversationContext = conversationContext
            )
        )
    }

    private fun executeDirectSystemAction(
        action: AgentAction,
        conversationId: String,
        turnId: String
    ) {
        val missingPermissions = if (action.kind == AgentActionKind.CALL_NATIVE_TOOL) {
            val toolId = action.parameters["tool_id"].orEmpty()
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
            pendingDirectSystemAction = PendingDirectSystemAction(action, conversationId, turnId)
            requestPermissions(missingPermissions.toTypedArray(), REQUEST_AGENT_NATIVE_PERMISSIONS)
            return
        }
        val screen = mobileNativeAgent.snapshot().currentScreen
        thread(name = "signalasi-agent-system-action") {
            val outcome = runCatching {
                if (action.kind == AgentActionKind.CALL_NATIVE_TOOL) {
                    val notifications = AgentActionNotificationCenter(this@MainActivity)
                    notifications.showRunning(action)
                    mobileNativeAgent.executeDirectAction(action, conversationId, turnId).also { result ->
                        notifications.showResult(action, result)
                    }
                } else {
                    PhoneExecutionAuthority.guarded(
                        NotifyingAgentActionExecutor(
                            this@MainActivity,
                            AgentControlPlaneActionExecutor(
                                this@MainActivity,
                                AndroidAgentActionExecutor(this@MainActivity)
                            )
                        )
                    ).execute(action, screen)
                }
            }
            runOnUiThread {
                val result = outcome.getOrElse { error ->
                    AgentActionResult(action.id, false, error.message ?: "Agent operation failed")
                }
                appendDirectSystemResult(action, conversationId, turnId, result)
                recordDirectAgentRun(turnId, action, result)
            }
        }
    }

    private fun handleAgentSkillCommand(goal: String, conversationId: String, turnId: String): Boolean {
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
        renderAgentTranscript(agentTranscriptStore.list(conversationId))
        return true
    }

    private fun executeMatchedSkill(
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
        thread(name = "signalasi-agent-skill") {
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
                    renderAgentTranscript(agentTranscriptStore.list(conversationId))
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

    private fun recordDirectAgentRun(turnId: String, action: AgentAction, result: AgentActionResult) {
        val runId = agentRunIdsByTurn.remove(turnId) ?: return
        agentRunRecorder.run(runId) ?: return
        val toolName = action.parameters["tool_id"].orEmpty().ifBlank { "android.${action.kind.name.lowercase()}" }
        val nativeResultJson = result.metadata["native_tool_output"].orEmpty()
        val invocationId = result.metadata["invocation_id"].orEmpty().ifBlank { action.id }
        val call = AgentToolCallRecord(
            id = invocationId,
            toolName = toolName,
            status = if (result.success) AgentToolCallStatus.SUCCEEDED else AgentToolCallStatus.FAILED,
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
            executionResourceId = "signalasi-mobile"
        )?.let(::observeCompletedAgentRun)
    }

    private fun recordSkillAgentRun(turnId: String, result: AgentSkillExecutionResult) {
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

    private fun persistAgentWorkspaceSnapshot(turnId: String, state: AgentUiState) {
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
                .ifBlank { "signalasi-mobile" }
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
                .toString()
            val profile = AppStore.profile(this)
            val supervisor = AgentTaskRuntime.supervisor(this)
            supervisor.recordExecutionSnapshot(
                turnId,
                AgentWorkspaceExecutionSnapshot(
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
                    deviceId = profile.optString("device_id").ifBlank { profile.optString("signalasi_id") },
                    remoteRunId = result?.metadata?.get("remote_task_id").orEmpty()
                        .ifBlank { sourceMessageId },
                    lastRemoteEventSequence = result?.metadata?.get("last_event_sequence")?.toLongOrNull() ?: 0L
                )
            )
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
                    .toString()
            )
        }.onFailure { error ->
            Log.w("SignalASIAgent", "workspace_snapshot_failed turn=${turnId.take(8)}", error)
        }
    }

    private fun recordAgentRunFromState(turnId: String, state: AgentUiState) {
        if (state.phase !in setOf(AgentPhase.COMPLETED, AgentPhase.FAILED, AgentPhase.CANCELLED, AgentPhase.BLOCKED)) return
        val runId = agentRunIdsByTurn.remove(turnId) ?: return
        val run = agentRunRecorder.run(runId) ?: return
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
        if (routeTarget.isNotBlank() && routeTarget != "signalasi-mobile" && !handoffAlreadyRecorded) {
            appendRunControlEvent(
                run = run,
                messageId = turnId,
                taskId = turnId,
                agentId = routeTarget,
                type = AgentRunControlEventType.HANDOFF,
                payload = mapOf(
                    "from_agent_id" to "signalasi-mobile",
                    "to_agent_id" to routeTarget,
                    "reason" to state.plan?.routeRationale.orEmpty(),
                    "return_to_agent_id" to "signalasi-mobile"
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
            executionResourceId = routeTarget.ifBlank { "signalasi-mobile" }
        )?.let(::observeCompletedAgentRun)
    }

    private fun observeCompletedAgentRun(run: AgentRecordedRun) {
        val existing = agentRunEventStore.events(run.runId).lastOrNull()
        appendRunControlEvent(
            run = run,
            messageId = existing?.messageId.orEmpty().ifBlank { run.runId },
            taskId = existing?.taskId.orEmpty().ifBlank { run.taskThreadId },
            agentId = existing?.agentId.orEmpty().ifBlank { "signalasi-mobile" },
            type = when (run.status) {
                AgentRecordedRunStatus.COMPLETED -> AgentRunControlEventType.RUN_COMPLETED
                AgentRecordedRunStatus.CANCELLED -> AgentRunControlEventType.RUN_CANCELLED
                AgentRecordedRunStatus.RUNNING, AgentRecordedRunStatus.FAILED -> AgentRunControlEventType.RUN_FAILED
            }
        )
        val privateMode = agentTranscriptStore.context(run.conversationId).privateMode
        agentLearningEngine.observeCompletedRun(
            run = run,
            recentRuns = agentRunRecorder.recentRuns(),
            privateMode = privateMode,
            memoryCaptureEnabled = mobileNativeAgent.safetySettings().memoryCapture
        )
    }

    private fun recordNativeToolLifecycleEvent(event: AgentNativeToolLifecycleEvent) {
        val runId = agentRunIdsByTurn[event.turnId] ?: return
        val run = agentRunRecorder.run(runId) ?: return
        val type = when (event.stage) {
            AgentNativeToolLifecycleStage.STARTED -> AgentRunControlEventType.TOOL_STARTED
            AgentNativeToolLifecycleStage.PROGRESS -> AgentRunControlEventType.TOOL_PROGRESS
            AgentNativeToolLifecycleStage.FINISHED -> AgentRunControlEventType.TOOL_COMPLETED
        }
        appendRunControlEvent(
            run = run,
            messageId = event.turnId.ifBlank { event.invocationId },
            taskId = event.turnId.ifBlank { run.taskThreadId },
            agentId = "signalasi-mobile",
            type = type,
            payload = buildMap {
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

    private fun runtimeArtifactsFromResult(resultJson: String): List<AgentArtifactReference> = runCatching {
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

    private fun appendRunControlEvent(
        run: AgentRecordedRun,
        messageId: String,
        taskId: String,
        agentId: String,
        type: AgentRunControlEventType,
        payload: AgentNativeJsonObject = emptyMap(),
        stepId: String = "",
        toolCallId: String = ""
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
                deviceId = profile.optString("device_id").ifBlank { profile.optString("signalasi_id") },
                type = type,
                sequence = 0L,
                payload = payload
            )
        )
    }

    private fun appendDirectSystemResult(
        action: AgentAction,
        conversationId: String,
        turnId: String,
        result: AgentActionResult
    ) {
        agentTranscriptStore.append(
            AgentTranscriptRole.ASSISTANT,
            result.message,
            dedupeKey = "direct-system:$turnId:${action.id}",
            conversationId = conversationId,
            turnId = turnId,
            taskId = turnId
        )
        renderAgentTranscript(agentTranscriptStore.list(conversationId))
    }

    private fun requestMissingAgentNativePermissions(state: AgentUiState): Boolean {
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

    private fun refreshAgentConversationHeader(
        conversation: AgentConversation = agentTranscriptStore.activeConversation(),
        contextCount: Int = agentTranscriptStore.context(conversation.id).turns.size
    ) {
        agentSessionTitle.text = agentConversationDisplayTitle(conversation)
        val baseSubtitle = getString(
            R.string.agent_session_context_count,
            agentConversationSourceLabel(conversation),
            contextCount
        )
        agentSubtitleText.text = if (conversation.summary.isNotBlank()) {
            "$baseSubtitle · ${getString(R.string.agent_session_context_compacted)}"
        } else baseSubtitle
    }

    private fun agentConversationDisplayTitle(conversation: AgentConversation): String =
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

    private fun refreshGlobalAgentCognition() {
        if (!::globalSuperAgentRuntime.isInitialized || !::agentTranscriptStore.isInitialized) return
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
        thread(name = "signalasi-global-agent-cognition") {
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
                            renderAgentTranscript(agentTranscriptStore.list())
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

    private fun foregroundAgentTurnInProgress(): Boolean {
        val entries = agentTranscriptStore.list()
        val latestUser = entries.lastOrNull { it.role == AgentTranscriptRole.USER } ?: return false
        return entries.none { entry ->
            entry.role == AgentTranscriptRole.ASSISTANT &&
                entry.timestampMillis >= latestUser.timestampMillis &&
                (latestUser.turnId.isBlank() || entry.turnId == latestUser.turnId)
        }
    }

    private fun agentConversationSourceLabel(conversation: AgentConversation): String {
        val selected = conversation.selectedModelOrAgent
        if (!selected.equals("Multiple Executors", ignoreCase = true)) {
            return agentTraceTargetLabel(selected)
        }
        val entries = agentTranscriptStore.list(conversation.id)
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

    private fun createAgentConversation() {
        agentInputAttachments.clear()
        renderAgentInputAttachments()
        agentGoalInput.setText("")
        val conversation = agentTranscriptStore.createConversation()
        lastRenderedAgentState = null
        renderAgentState(mobileNativeAgent.startNewConversation(conversation.id), conversation.id, syncTranscript = false)
        resetAgentTranscriptRendering(conversation.id)
        refreshAgentConversationHeader()
        renderAgentTranscript(agentTranscriptStore.list())
        if (featurePage.visibility == View.VISIBLE) hideFeaturePage()
    }

    private fun showAgentSessionsPage(showArchived: Boolean = false) {
        agentSessionsDialog?.dismiss()
        val dialog = android.app.Dialog(this)
        agentSessionsDialog = dialog
        val sessionContent = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(20))
            background = getDrawable(R.drawable.glass_card_background)
        }
        val scrollContent = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        sessionContent.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(TextView(this@MainActivity).apply {
                text = getString(R.string.agent_sessions_title)
                textSize = 20f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                setTextColor(getColorCompat(R.color.text_primary))
            }, LinearLayout.LayoutParams(0, dp(48), 1f))
            addView(ImageButton(this@MainActivity).apply {
                setImageResource(android.R.drawable.ic_menu_close_clear_cancel)
                contentDescription = getString(android.R.string.cancel)
                setBackgroundColor(android.graphics.Color.TRANSPARENT)
                setOnClickListener { dialog.dismiss() }
            }, LinearLayout.LayoutParams(dp(48), dp(48)))
        })
        sessionContent.addView(android.widget.ScrollView(this).apply {
            isFillViewport = true
            addView(scrollContent)
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        val allConversations = agentTranscriptStore.conversations(includeArchived = true)
        scrollContent.addView(featureHeroCard(
            getString(R.string.agent_sessions_title),
            getString(R.string.agent_tab_subtitle),
            R.drawable.ic_agent_history,
            "#14C66A",
            allConversations.size.toString()
        ))
        scrollContent.addView(featureRow(
            getString(R.string.agent_session_new),
            "",
            R.drawable.ic_input_plus,
            "+"
        ).apply { setOnClickListener { dialog.dismiss(); createAgentConversation() } })
        scrollContent.addView(featureRow(
            getString(R.string.agent_session_archived),
            allConversations.count { it.status == AgentConversationStatus.ARCHIVED }.toString(),
            R.drawable.ic_agent_history,
            getString(R.string.common_view)
        ).apply { setOnClickListener { dialog.dismiss(); showAgentSessionsPage(showArchived = true) } })
        val searchInput = EditText(this).apply {
            hint = getString(R.string.agent_session_search)
            setSingleLine(true)
            setTextColor(getColorCompat(R.color.text_primary))
            setHintTextColor(getColorCompat(R.color.text_secondary))
            setPadding(dp(16), 0, dp(16), 0)
            background = getDrawable(R.drawable.glass_card_background)
        }
        scrollContent.addView(searchInput, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            dp(48)
        ).apply { bottomMargin = dp(12) })
        scrollContent.addView(TextView(this).apply {
            text = if (showArchived) getString(R.string.agent_session_archived) else getString(R.string.agent_sessions_title)
            setTextColor(getColorCompat(R.color.text_secondary))
            textSize = 12f
            setPadding(dp(4), dp(12), 0, dp(7))
        })
        val rows = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scrollContent.addView(rows)
        fun renderRows(query: String) {
            rows.removeAllViews()
            val normalizedQuery = query.trim().lowercase(Locale.US)
            val filtered = allConversations.filter { conversation ->
                val statusMatch = if (showArchived) {
                    conversation.status == AgentConversationStatus.ARCHIVED
                } else {
                    conversation.status == AgentConversationStatus.ACTIVE
                }
                statusMatch && (normalizedQuery.isBlank() || conversation.title.lowercase(Locale.US).contains(normalizedQuery))
            }
            if (filtered.isEmpty()) {
                rows.addView(featureRow(
                    getString(R.string.agent_session_no_results), "", R.drawable.ic_agent_history, ""
                ))
                return
            }
            val startOfToday = java.util.Calendar.getInstance().apply {
                set(java.util.Calendar.HOUR_OF_DAY, 0)
                set(java.util.Calendar.MINUTE, 0)
                set(java.util.Calendar.SECOND, 0)
                set(java.util.Calendar.MILLISECOND, 0)
            }.timeInMillis
            val groups = if (showArchived) {
                listOf(getString(R.string.agent_session_archived) to filtered)
            } else {
                listOf(
                    getString(R.string.agent_session_today) to filtered.filter { it.updatedAt >= startOfToday },
                    getString(R.string.agent_session_yesterday) to filtered.filter {
                        it.updatedAt in (startOfToday - 86_400_000L) until startOfToday
                    },
                    getString(R.string.agent_session_earlier) to filtered.filter {
                        it.updatedAt < startOfToday - 86_400_000L
                    }
                ).filter { it.second.isNotEmpty() }
            }
            groups.forEach { (groupTitle, conversations) ->
                rows.addView(TextView(this@MainActivity).apply {
                    text = groupTitle
                    setTextColor(getColorCompat(R.color.text_secondary))
                    textSize = 12f
                    setPadding(dp(4), dp(12), 0, dp(7))
                })
                conversations.forEach { conversation ->
            val messages = agentTranscriptStore.list(conversation.id)
            val sessionRow = featureRow(
                 agentConversationDisplayTitle(conversation).let { title ->
                     if (conversation.pinned) "● $title" else title
                 },
                 getString(
                     R.string.agent_session_message_count,
                    conversation.selectedModelOrAgent,
                    messages.size,
                    listTime(conversation.updatedAt)
                 ),
                 R.drawable.ic_agent_history,
                 "…"
             ).apply {
                 setPadding(dp(14), dp(10), dp(4), dp(10))
                 setOnClickListener {
                    val destination = agentTranscriptStore.resolveMergedConversationId(conversation.id)
                        ?: conversation.id
                    if (destination == conversation.id && conversation.status == AgentConversationStatus.ARCHIVED) {
                        agentTranscriptStore.restoreConversation(conversation.id)
                    }
                    agentTranscriptStore.switchConversation(destination)
                    resetAgentTranscriptRendering(destination)
                    refreshAgentConversationHeader()
                    renderAgentTranscript(agentTranscriptStore.list())
                    dialog.dismiss()
                }
                setOnLongClickListener {
                     showAgentConversationActions(conversation)
                     true
                 }
             }
             (sessionRow as ViewGroup).let { rowGroup ->
                 rowGroup.getChildAt(rowGroup.childCount - 1).apply {
                     isClickable = true
                     isFocusable = true
                     contentDescription = getString(R.string.agent_session_more_actions)
                     if (this is TextView) {
                         text = ""
                         val menuDrawable = getDrawable(R.drawable.ic_more_horizontal)?.mutate()?.apply {
                             setTint(getColorCompat(R.color.text_primary))
                             setBounds(0, 0, dp(27), dp(27))
                         }
                         setCompoundDrawables(menuDrawable, null, null, null)
                         gravity = Gravity.CENTER
                         layoutParams = LinearLayout.LayoutParams(dp(44), dp(44))
                     }
                     setOnClickListener { showAgentConversationQuickActions(conversation, showArchived) }
                 }
             }
             rows.addView(sessionRow)
                }
            }
        }
        searchInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = renderRows(s?.toString().orEmpty())
            override fun afterTextChanged(s: Editable?) = Unit
        })
        renderRows("")
        dialog.setContentView(sessionContent)
        dialog.setOnDismissListener { if (agentSessionsDialog === dialog) agentSessionsDialog = null }
        dialog.window?.apply {
            setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))
            setDimAmount(0.32f)
            addFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            setGravity(Gravity.BOTTOM)
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, (resources.displayMetrics.heightPixels * 0.86f).toInt())
        }
        dialog.show()
        dialog.window?.setLayout(
            ViewGroup.LayoutParams.MATCH_PARENT,
            (resources.displayMetrics.heightPixels * 0.86f).toInt()
        )
    }

    private fun showAgentConversationQuickActions(conversation: AgentConversation, showArchived: Boolean) {
        val actions = mutableListOf<Pair<String, () -> Unit>>()
        actions += getString(R.string.agent_session_modify) to {
            showTextSettingDialog(
                getString(R.string.agent_session_modify),
                conversation.title
            ) { title ->
                agentTranscriptStore.renameConversation(conversation.id, title)
                refreshAgentConversationHeader()
                showAgentSessionsPage(showArchived)
            }
        }
        if (canMergeAgentConversation(conversation)) {
            actions += getString(R.string.agent_session_merge_into_original) to {
                confirmMergeAgentConversation(conversation)
            }
        }
        if (conversation.mergedIntoConversationId.isBlank()) {
            actions += getString(
                if (conversation.trackingPaused) R.string.agent_session_resume_tracking
                else R.string.agent_session_pause_tracking
            ) to {
                agentTranscriptStore.setTrackingPaused(conversation.id, !conversation.trackingPaused)
                showAgentSessionsPage(showArchived)
            }
        }
        actions += getString(R.string.common_delete) to {
            confirmDeleteAgentConversation(conversation, showArchived)
        }
        actions += getString(R.string.agent_session_delete_more) to {
            showAgentConversationMultiDelete(showArchived)
        }
        android.app.AlertDialog.Builder(this)
            .setTitle(conversation.title)
            .setItems(actions.map(Pair<String, () -> Unit>::first).toTypedArray()) { _, which ->
                actions[which].second.invoke()
            }
            .setNegativeButton(getString(R.string.common_cancel), null)
            .show()
    }

    private fun showAgentConversationMultiDelete(showArchived: Boolean) {
        val candidates = agentTranscriptStore.conversations(includeArchived = true).filter { conversation ->
            if (showArchived) {
                conversation.status == AgentConversationStatus.ARCHIVED
            } else {
                conversation.status == AgentConversationStatus.ACTIVE
            }
        }
        if (candidates.isEmpty()) {
            Toast.makeText(this, getString(R.string.agent_session_no_results), Toast.LENGTH_SHORT).show()
            return
        }
        val selected = BooleanArray(candidates.size)
        val labels = candidates.map(::agentConversationDisplayTitle).toTypedArray()
        lateinit var selectionDialog: android.app.AlertDialog
        selectionDialog = android.app.AlertDialog.Builder(this)
            .setTitle(getString(R.string.agent_session_select_delete))
            .setMultiChoiceItems(labels, selected) { _, which, checked ->
                selected[which] = checked
                val count = selected.count { it }
                selectionDialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE)?.apply {
                    isEnabled = count > 0
                    text = getString(R.string.agent_session_delete_selected, count)
                }
            }
            .setPositiveButton(getString(R.string.agent_session_delete_selected, 0), null)
            .setNegativeButton(getString(R.string.common_cancel), null)
            .create()
        selectionDialog.setOnShowListener {
            selectionDialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).apply {
                isEnabled = false
                setOnClickListener {
                    val chosen = candidates.filterIndexed { index, _ -> selected[index] }
                    if (chosen.isEmpty()) return@setOnClickListener
                    android.app.AlertDialog.Builder(this@MainActivity)
                        .setTitle(getString(R.string.agent_session_delete_more))
                        .setMessage(getString(R.string.agent_session_delete_selected_confirm, chosen.size))
                        .setPositiveButton(getString(R.string.common_delete)) { _, _ ->
                            chosen.forEach(::deleteAgentConversationData)
                            selectionDialog.dismiss()
                            refreshAgentConversationHeader()
                            renderedAgentTranscriptIds.clear()
                            agentOutputList.removeAllViews()
                            renderAgentTranscript(agentTranscriptStore.list())
                            showAgentSessionsPage(showArchived)
                        }
                        .setNegativeButton(getString(R.string.common_cancel), null)
                        .show()
                }
            }
        }
        selectionDialog.show()
    }

    private fun deleteAgentConversationData(conversation: AgentConversation) {
        if (conversation.mergedIntoConversationId.isBlank()) {
            val taskIds = agentTranscriptStore.taskIds(conversation.id)
            SignalASIMqttClient.publishAgentConversationDelete(conversation.id, taskIds)
            SharedPreferencesAgentTaskStore(this).delete(taskIds + conversation.id)
        }
        agentTranscriptStore.deleteConversation(conversation.id)
    }

    private fun confirmDeleteAgentConversation(conversation: AgentConversation, showArchived: Boolean) {
        android.app.AlertDialog.Builder(this)
            .setTitle(getString(R.string.agent_session_delete))
            .setMessage(getString(R.string.agent_session_delete_confirm))
            .setPositiveButton(getString(R.string.common_delete)) { _, _ ->
                deleteAgentConversationData(conversation)
                refreshAgentConversationHeader()
                renderAgentTranscript(agentTranscriptStore.list())
                showAgentSessionsPage(showArchived)
            }
            .setNegativeButton(getString(R.string.common_cancel), null)
            .show()
    }

    private fun showAgentConversationActions(conversation: AgentConversation) {
        val actions = mutableListOf<Pair<String, () -> Unit>>()
        actions += getString(R.string.agent_session_rename) to {
            showTextSettingDialog(getString(R.string.agent_session_rename), conversation.title) {
                agentTranscriptStore.renameConversation(conversation.id, it)
                showAgentSessionsPage()
            }
        }
        if (canMergeAgentConversation(conversation)) {
            actions += getString(R.string.agent_session_merge_into_original) to {
                confirmMergeAgentConversation(conversation)
            }
        }
        actions += getString(if (conversation.pinned) R.string.agent_session_unpin else R.string.agent_session_pin) to {
            agentTranscriptStore.setPinned(conversation.id, !conversation.pinned)
            showAgentSessionsPage()
        }
        if (conversation.mergedIntoConversationId.isBlank()) {
            actions += getString(if (conversation.privateMode) R.string.agent_session_standard else R.string.agent_session_private) to {
                agentTranscriptStore.setPrivateMode(conversation.id, !conversation.privateMode)
                showAgentSessionsPage()
            }
            actions += getString(
                if (conversation.trackingPaused) R.string.agent_session_resume_tracking
                else R.string.agent_session_pause_tracking
            ) to {
                agentTranscriptStore.setTrackingPaused(conversation.id, !conversation.trackingPaused)
                showAgentSessionsPage()
            }
        }
        actions += getString(R.string.agent_session_context_policy) to {
            showAgentConversationContextPolicy(conversation)
        }
        actions += getString(R.string.agent_session_summary) to {
            showTextSettingDialog(getString(R.string.agent_session_summary), conversation.summary) {
                agentTranscriptStore.updateSummary(conversation.id, it)
                showAgentSessionsPage()
            }
        }
        actions += getString(R.string.agent_session_details) to {
            showAgentConversationDetails(conversation)
        }
        if (conversation.mergedIntoConversationId.isBlank()) {
            actions += getString(
                if (conversation.status == AgentConversationStatus.ARCHIVED) R.string.agent_session_restore
                else R.string.agent_session_archive
            ) to {
                if (conversation.status == AgentConversationStatus.ARCHIVED) {
                    agentTranscriptStore.restoreConversation(conversation.id)
                } else {
                    agentTranscriptStore.archiveConversation(conversation.id)
                }
                showAgentSessionsPage()
            }
        }
        actions += getString(R.string.agent_session_delete) to {
            confirmDeleteAgentConversation(conversation, conversation.status == AgentConversationStatus.ARCHIVED)
        }
        android.app.AlertDialog.Builder(this)
            .setTitle(conversation.title)
            .setItems(actions.map(Pair<String, () -> Unit>::first).toTypedArray()) { _, which ->
                actions[which].second.invoke()
            }
            .setNegativeButton(getString(R.string.common_cancel), null)
            .show()
    }

    private fun canMergeAgentConversation(conversation: AgentConversation): Boolean {
        if (!conversation.createdByAgent || conversation.parentConversationId.isBlank() ||
            conversation.mergedIntoConversationId.isNotBlank()
        ) return false
        val parent = agentTranscriptStore.conversation(conversation.parentConversationId) ?: return false
        return parent.privateMode == conversation.privateMode
    }

    private fun confirmMergeAgentConversation(conversation: AgentConversation) {
        val target = agentTranscriptStore.conversation(conversation.parentConversationId)
        if (target == null) {
            Toast.makeText(this, getString(R.string.agent_session_merge_target_missing), Toast.LENGTH_SHORT).show()
            return
        }
        android.app.AlertDialog.Builder(this)
            .setTitle(getString(R.string.agent_session_merge_into_original))
            .setMessage(getString(R.string.agent_session_merge_confirm, conversation.title, target.title))
            .setPositiveButton(getString(R.string.agent_session_merge_confirm_action)) { _, _ ->
                val result = agentTranscriptStore.mergeConversationIntoParent(conversation.id)
                if (!result.merged) {
                    Toast.makeText(
                        this,
                        getString(agentConversationMergeFailureMessage(result.failure)),
                        Toast.LENGTH_SHORT
                    ).show()
                    return@setPositiveButton
                }
                val targetId = result.targetConversation?.id.orEmpty()
                agentRuntimeConversationIds.entries.toList()
                    .filter { it.value == conversation.id }
                    .forEach { agentRuntimeConversationIds[it.key] = targetId }
                agentSessionsDialog?.dismiss()
                resetAgentTranscriptRendering(targetId)
                showMainTab(PAGE_AGENT)
                refreshAgentConversationHeader()
                renderAgentTranscript(agentTranscriptStore.list(targetId))
                refreshGlobalAgentCognition()
                Toast.makeText(
                    this,
                    getString(R.string.agent_session_merge_success, result.copiedEntryCount),
                    Toast.LENGTH_SHORT
                ).show()
            }
            .setNegativeButton(getString(R.string.common_cancel), null)
            .show()
    }

    private fun agentConversationMergeFailureMessage(failure: AgentConversationMergeFailure): Int = when (failure) {
        AgentConversationMergeFailure.ALREADY_MERGED -> R.string.agent_session_merge_already_done
        AgentConversationMergeFailure.PRIVACY_MISMATCH -> R.string.agent_session_merge_privacy_mismatch
        else -> R.string.agent_session_merge_unavailable
    }

    private fun showAgentConversationContextPolicy(conversation: AgentConversation) {
        val labels = listOf(
            getString(R.string.agent_session_context_minimal),
            getString(R.string.agent_session_context_balanced),
            getString(R.string.agent_session_context_extended)
        )
        val values = listOf("minimal", "balanced", "extended")
        val selected = values.indexOf(conversation.contextPolicy).coerceAtLeast(1)
        android.app.AlertDialog.Builder(this)
            .setTitle(getString(R.string.agent_session_context_policy))
            .setSingleChoiceItems(labels.toTypedArray(), selected) { dialog, which ->
                agentTranscriptStore.setContextPolicy(conversation.id, values[which])
                dialog.dismiss()
                showAgentSessionsPage()
            }
            .setNegativeButton(getString(R.string.common_cancel), null)
            .show()
    }

    private fun showAgentConversationDetails(conversation: AgentConversation) {
        val metrics = agentTranscriptStore.metrics(conversation.id)
        val contextPreview = agentTranscriptStore.context(conversation.id)
        val sessionTasks = SharedPreferencesAgentTaskStore(this).forSession(conversation.id)
        showFeaturePage(getString(R.string.agent_session_details))
        setFeatureBackAction { showAgentSessionsPage() }
        featureContent.addView(featureHeroCard(
            conversation.title,
            conversation.selectedModelOrAgent,
            R.drawable.ic_agent_history,
            "#14C66A",
            if (conversation.privateMode) getString(R.string.agent_session_private) else getString(R.string.agent_session_standard)
        ))
        addSectionTitle(getString(R.string.agent_session_details))
        featureContent.addView(featureValueRow(
            getString(R.string.agent_session_turns), "", R.drawable.ic_agent_history, metrics.turnCount.toString()
        ))
        featureContent.addView(featureValueRow(
            getString(R.string.agent_session_tasks), "", R.drawable.ic_agent_node, metrics.taskCount.toString()
        ))
        featureContent.addView(featureValueRow(
            getString(R.string.agent_session_context_tokens), "", R.drawable.ic_protocol_link,
            metrics.estimatedContextTokens.toString()
        ))
        featureContent.addView(featureValueRow(
            getString(R.string.agent_session_input_tokens), "", R.drawable.ic_protocol_link,
            metrics.inputTokens.takeIf { it > 0L }?.toString() ?: "-"
        ))
        featureContent.addView(featureValueRow(
            getString(R.string.agent_session_output_tokens), "", R.drawable.ic_protocol_link,
            metrics.outputTokens.takeIf { it > 0L }?.toString() ?: "-"
        ))
        featureContent.addView(featureValueRow(
            getString(R.string.agent_session_latency), "", R.drawable.ic_protocol_link,
            if (metrics.lastResponseLatencyMillis > 0L) "${metrics.lastResponseLatencyMillis / 1000.0}s" else "-"
        ))
        featureContent.addView(featureValueRow(
            getString(R.string.agent_session_cost), "", R.drawable.ic_security_shield,
            if (metrics.costMicros > 0L) String.format(Locale.US, "$%.6f", metrics.costMicros / 1_000_000.0)
            else getString(R.string.agent_session_cost_unavailable)
        ))
        addSectionTitle(getString(R.string.agent_session_context_preview))
        featureContent.addView(featureRow(
            getString(R.string.agent_session_summary),
            conversation.summary.ifBlank { getString(R.string.agent_session_summary_empty) },
            R.drawable.ic_agent_memory,
            "›"
        ).apply {
            setOnClickListener {
            showTextSettingDialog(getString(R.string.agent_session_summary), conversation.summary) {
                agentTranscriptStore.updateSummary(conversation.id, it)
                showAgentConversationDetails(
                    agentTranscriptStore.conversations(includeArchived = true)
                    .firstOrNull { item -> item.id == conversation.id } ?: conversation
                )
            }
            }
        })
        featureContent.addView(featureRow(
            getString(R.string.agent_session_recent_context),
            getString(R.string.agent_session_context_messages, contextPreview.turns.size),
            R.drawable.ic_protocol_link,
            "›"
        ).apply {
            setOnClickListener {
            android.app.AlertDialog.Builder(this@MainActivity)
                .setTitle(getString(R.string.agent_session_recent_context))
                .setMessage(contextPreview.asPromptBlock())
                .setPositiveButton(android.R.string.ok, null)
                .show()
            }
        })
        addSectionTitle(getString(R.string.agent_session_tasks))
        if (sessionTasks.isEmpty()) {
            featureContent.addView(featureRow(
                getString(R.string.agent_recent_empty), "", R.drawable.ic_agent_history, ""
            ))
        } else {
            sessionTasks.forEachIndexed { index, task ->
                featureContent.addView(agentRecentTaskRow(task, index))
            }
        }
    }

    private fun startAgentVoiceInput() {
        if (!ensureRecordPermission()) return
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            Toast.makeText(this, getString(R.string.voice_status_asr_unavailable), Toast.LENGTH_SHORT).show()
            return
        }
        stopVoiceAssistant()
        ensureSpeechRecognizer()
        val config = VoiceAssistantSettings.get(this)
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, config.asrLanguage)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, packageName)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 1000L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 800L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 800L)
        }
        runCatching {
            agentVoiceListening = true
            agentVoiceButton.text = getString(R.string.agent_voice_listening)
            speechRecognizer?.startListening(intent)
        }.onFailure {
            agentVoiceListening = false
            agentVoiceButton.text = getString(R.string.agent_voice_button)
            Toast.makeText(this, it.message ?: getString(R.string.voice_status_retry_later), Toast.LENGTH_SHORT).show()
        }
    }

    private fun stopAgentVoiceInput() {
        if (!agentVoiceListening) return
        agentVoiceListening = false
        runCatching { speechRecognizer?.cancel() }
        agentVoiceButton.text = getString(R.string.agent_voice_button)
    }

    private fun handleAgentVoiceResult(text: String) {
        agentVoiceListening = false
        agentVoiceButton.text = getString(R.string.agent_voice_button)
        if (text.isBlank()) {
            Toast.makeText(this, getString(R.string.voice_error_no_valid_speech), Toast.LENGTH_SHORT).show()
            return
        }
        agentGoalInput.setText(text)
        agentGoalInput.setSelection(agentGoalInput.text?.length ?: 0)
        submitAgentGoal()
    }

    private fun configureWakePage() {
        findViewById<View>(R.id.wakeTitleHitArea).setOnClickListener {
            showMainTab(PAGE_MESSAGES)
        }
        addWakeVoiceStatusViews()
        wakeAnimation.visibility = View.VISIBLE
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val source = ImageDecoder.createSource(resources, R.drawable.voice_wakeup_ice_text_fixed)
            val drawable = ImageDecoder.decodeDrawable(source)
            wakeAnimation.setImageDrawable(drawable)
            (drawable as? AnimatedImageDrawable)?.start()
        } else {
            wakeAnimation.setImageResource(R.drawable.voice_wakeup_ice_text_fixed)
        }
    }

    private fun addWakeVoiceStatusViews() {
        if (wakeStatusText != null) return
        wakeStatusText = TextView(this).apply {
            text = getString(R.string.voice_status_low_power)
            gravity = Gravity.CENTER
            setTextColor(Color.parseColor("#00EFDE"))
            textSize = 15f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            includeFontPadding = false
            setPadding(dp(18), 0, dp(18), 0)
            background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                cornerRadius = dp(17).toFloat()
                setColor(Color.argb(76, 0, 239, 222))
                setStroke(dp(1), Color.argb(170, 0, 239, 222))
            }
        }
        wakeTranscriptText = TextView(this).apply {
            text = getString(R.string.voice_hint_wake)
            gravity = Gravity.START
            setTextColor(Color.parseColor("#F0FDFF"))
            textSize = 15.5f
            maxLines = 30
            includeFontPadding = false
            setLineSpacing(dp(4).toFloat(), 1.0f)
            setPadding(dp(20), dp(18), dp(20), dp(18))
        }
        val replyPanel = ScrollView(this).apply {
            isFillViewport = false
            isVerticalScrollBarEnabled = true
            overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
            elevation = dp(10).toFloat()
            alpha = 0.96f
            visibility = View.GONE
            background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                cornerRadius = dp(22).toFloat()
                setColor(Color.argb(142, 4, 18, 28))
                setStroke(dp(1), Color.argb(150, 58, 245, 255))
            }
            addView(wakeTranscriptText, ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ))
        }
        wakeReplyPanel = replyPanel
        wakePage.addView(replyPanel, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            dp(360),
            Gravity.TOP or Gravity.CENTER_HORIZONTAL
        ).apply {
            leftMargin = dp(18)
            rightMargin = dp(18)
            topMargin = dp(196)
        })
    }

    private fun startVoiceAssistant() {
        val config = VoiceAssistantSettings.get(this)
        if (!config.enabled) {
            updateWakeVoiceUi(getString(R.string.voice_status_disabled), getString(R.string.voice_status_disabled_detail))
            return
        }
        if (!ensureRecordPermission()) {
            updateWakeVoiceUi(getString(R.string.voice_status_permission_required), getString(R.string.voice_status_permission_detail))
            return
        }
        if (config.wakeProvider == VoiceAssistantSettings.WAKE_PROVIDER_OPEN_WAKE_WORD) {
            voiceAssistantAwake = false
            voiceAssistantSpeaking = false
            startOpenWakeWordListening(config)
            return
        }
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            updateWakeVoiceUi(getString(R.string.voice_status_asr_unavailable), getString(R.string.voice_status_asr_unavailable_detail))
            return
        }
        ensureSpeechRecognizer()
        voiceAssistantAwake = false
        voiceAssistantSpeaking = false
        startWakeListening()
    }

    private fun stopVoiceAssistant() {
        voiceAssistantRestartPending = false
        voiceAssistantListening = false
        voiceAssistantAwake = false
        voiceAssistantSpeaking = false
        if (voiceAssistantRecordingCommand) stopVoiceCommandRecording(send = false)
        voiceCommandSpeechDetected = false
        voiceCommandLastVoiceAt = 0L
        releaseWakeWordEngine()
        runCatching { speechRecognizer?.cancel() }
        runCatching { speechRecognizer?.destroy() }
        speechRecognizer = null
        microsoftTts.stop()
        androidTts?.stop()
    }

    private fun ensureSpeechRecognizer() {
        if (speechRecognizer != null) return
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this).apply {
            setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {
                    voiceAssistantListening = true
                    Log.i("SignalASIVoice", "ASR ready, awake=$voiceAssistantAwake")
                }
                override fun onBeginningOfSpeech() = Unit
                override fun onRmsChanged(rmsdB: Float) = Unit
                override fun onBufferReceived(buffer: ByteArray?) = Unit
                override fun onEndOfSpeech() {
                    voiceAssistantListening = false
                }
                override fun onError(error: Int) {
                    Log.w("SignalASIVoice", "ASR error=$error awake=$voiceAssistantAwake")
                    voiceAssistantListening = false
                    if (agentVoiceListening) {
                        agentVoiceListening = false
                        agentVoiceButton.text = getString(R.string.agent_voice_button)
                        Toast.makeText(this@MainActivity, speechErrorDetail(error), Toast.LENGTH_SHORT).show()
                        return
                    }
                    if (activeMainTab == PAGE_VOICE && !voiceAssistantSpeaking) {
                        updateWakeVoiceUi(
                            if (voiceAssistantAwake) getString(R.string.voice_status_continue_listening) else getString(R.string.voice_status_low_power),
                            speechErrorDetail(error)
                        )
                        scheduleVoiceRestart(if (voiceAssistantAwake) 700L else 1000L)
                    }
                }
                override fun onResults(results: Bundle?) {
                    voiceAssistantListening = false
                    val text = bestSpeechResult(results)
                    Log.i("SignalASIVoice", "ASR result=$text awake=$voiceAssistantAwake")
                    if (agentVoiceListening) {
                        handleAgentVoiceResult(text)
                        return
                    }
                    handleVoiceRecognitionText(text)
                }
                override fun onPartialResults(partialResults: Bundle?) {
                    val text = bestSpeechResult(partialResults)
                    if (agentVoiceListening) {
                        if (text.isNotBlank()) {
                            agentGoalInput.setText(text)
                            agentGoalInput.setSelection(agentGoalInput.text?.length ?: 0)
                        }
                        return
                    }
                    if (text.isNotBlank()) {
                        updateWakeVoiceUi(
                            if (voiceAssistantAwake) getString(R.string.voice_status_recognizing) else getString(R.string.voice_status_low_power),
                            text
                        )
                    }
                }
                override fun onEvent(eventType: Int, params: Bundle?) = Unit
            })
        }
    }

    private fun startOpenWakeWordListening(config: VoiceAssistantConfig) {
        if (activeMainTab != PAGE_VOICE || wakePage.visibility != View.VISIBLE || voiceAssistantSpeaking) return
        releaseWakeWordEngine()
        updateWakeVoiceUi(getString(R.string.voice_status_local_wake_listening), getString(R.string.voice_status_local_wake_detail))
        runCatching {
            val engine = WakeWordEngine(
                context = applicationContext,
                models = listOf(WakeWordModel("SignalASI", config.wakeModel, threshold = config.wakeThreshold)),
                detectionMode = DetectionMode.SINGLE_BEST,
                detectionCooldownMs = 2500L,
                scope = voiceAssistantScope
            )
            wakeWordEngine = engine
            wakeWordDetectionJob = voiceAssistantScope.launch {
                engine.detections.collect { detection ->
                    runOnUiThread {
                        if (activeMainTab != PAGE_VOICE || wakePage.visibility != View.VISIBLE || voiceAssistantSpeaking) return@runOnUiThread
                        Log.i("SignalASIVoice", "openWakeWord detected model=${detection.model.name} score=${detection.score}")
                        releaseWakeWordEngine()
                        onVoiceWakeDetected("openWakeWord ${detection.model.name} ${"%.2f".format(Locale.US, detection.score)}")
                    }
                }
            }
            engine.start()
            voiceAssistantListening = true
            Log.i("SignalASIVoice", "openWakeWord started model=${config.wakeModel} threshold=${config.wakeThreshold}")
        }.onFailure {
            voiceAssistantListening = false
            Log.e("SignalASIVoice", "openWakeWord start failed", it)
            updateWakeVoiceUi(getString(R.string.voice_status_local_wake_failed), it.message ?: getString(R.string.voice_status_check_model_permission))
        }
    }

    private fun releaseWakeWordEngine() {
        wakeWordDetectionJob?.cancel()
        wakeWordDetectionJob = null
        runCatching { wakeWordEngine?.release() }
        wakeWordEngine = null
        voiceAssistantListening = false
    }

    private fun startWakeListening() {
        if (activeMainTab != PAGE_VOICE || wakePage.visibility != View.VISIBLE || voiceAssistantSpeaking) return
        val config = VoiceAssistantSettings.get(this)
        if (config.wakeProvider == VoiceAssistantSettings.WAKE_PROVIDER_OPEN_WAKE_WORD) {
            voiceAssistantAwake = false
            startOpenWakeWordListening(config)
            return
        }
        voiceAssistantAwake = false
        updateWakeVoiceUi(getString(R.string.voice_status_low_power), getString(R.string.voice_hint_wake))
        startVoiceRecognition()
    }

    private fun startCommandListening() {
        if (activeMainTab != PAGE_VOICE || wakePage.visibility != View.VISIBLE || voiceAssistantSpeaking) return
        startVoiceCommandRecording()
    }

    private fun startVoiceCommandRecording() {
        if (activeMainTab != PAGE_VOICE || wakePage.visibility != View.VISIBLE) return
        if (!ensureRecordPermission() || recorder != null) return
        val config = VoiceAssistantSettings.get(this)
        val contact = voiceAssistantTargetContact(config)
        val file = File(cacheDir, "voice_cmd_${System.currentTimeMillis()}.m4a")
        recordingFile = file
        recordingStartedAt = System.currentTimeMillis()
        voiceAssistantAwake = true
        voiceAssistantRecordingCommand = true
        voiceCommandSpeechDetected = false
        voiceCommandLastVoiceAt = 0L
        Log.i("SignalASIVoice", "Voice command recording started target=${contact.id} file=${file.name}")
        updateWakeVoiceUi(getString(R.string.voice_status_awake_auto_recording), getString(R.string.voice_status_auto_recording_detail))
        runCatching {
            recorder = createRecorder().apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setOutputFile(file.absolutePath)
                prepare()
                start()
            }
            monitorVoiceCommandRecording()
        }.onFailure {
            voiceAssistantRecordingCommand = false
            voiceCommandSpeechDetected = false
            voiceCommandLastVoiceAt = 0L
            recorder = null
            recordingFile = null
            file.delete()
            Log.e("SignalASIVoice", "Voice command recording failed", it)
            updateWakeVoiceUi(getString(R.string.voice_status_recording_failed), it.message ?: getString(R.string.voice_status_check_microphone))
            scheduleVoiceRestart(1200L)
        }
    }

    private fun monitorVoiceCommandRecording() {
        if (!voiceAssistantRecordingCommand) return
        val activeRecorder = recorder ?: return
        val now = System.currentTimeMillis()
        val elapsed = now - recordingStartedAt
        val amplitude = runCatching { activeRecorder.maxAmplitude }.getOrDefault(0)
        val hasVoice = amplitude > 1600
        if (hasVoice) {
            if (!voiceCommandSpeechDetected) {
                Log.i("SignalASIVoice", "Voice command speech detected amplitude=$amplitude elapsed=${elapsed}ms")
                updateWakeVoiceUi(getString(R.string.voice_status_recording), getString(R.string.voice_status_recording_detail))
            }
            voiceCommandSpeechDetected = true
            voiceCommandLastVoiceAt = now
        }
        val silenceMs = if (voiceCommandLastVoiceAt > 0L) now - voiceCommandLastVoiceAt else elapsed
        when {
            !voiceCommandSpeechDetected && elapsed >= 3000L -> {
                Log.i("SignalASIVoice", "Voice command no speech timeout elapsed=${elapsed}ms amplitude=$amplitude")
                stopVoiceCommandRecording(send = true, reason = "no_speech_timeout")
            }
            voiceCommandSpeechDetected && silenceMs >= 3000L -> {
                Log.i("SignalASIVoice", "Voice command silence timeout silence=${silenceMs}ms elapsed=${elapsed}ms")
                stopVoiceCommandRecording(send = true, reason = "silence_timeout")
            }
            elapsed >= 30_000L -> {
                Log.i("SignalASIVoice", "Voice command max duration timeout elapsed=${elapsed}ms")
                stopVoiceCommandRecording(send = true, reason = "max_duration")
            }
            else -> {
                handler.postDelayed({ monitorVoiceCommandRecording() }, 250L)
            }
        }
    }

    private fun stopVoiceCommandRecording(send: Boolean, reason: String = "manual") {
        val activeRecorder = recorder
        recorder = null
        voiceAssistantRecordingCommand = false
        Log.i("SignalASIVoice", "Voice command recording stopping send=$send reason=$reason speechDetected=$voiceCommandSpeechDetected")
        runCatching { activeRecorder?.stop() }
        activeRecorder?.release()
        val file = recordingFile
        recordingFile = null
        if (!send || file == null || !file.exists()) {
            file?.delete()
            if (activeMainTab == PAGE_VOICE) startWakeListening()
            return
        }
        val config = VoiceAssistantSettings.get(this)
        val contact = voiceAssistantTargetContact(config)
        val seconds = ((System.currentTimeMillis() - recordingStartedAt) / 1000).coerceAtLeast(1)
        if (!voiceCommandSpeechDetected && seconds <= 3) {
            Log.i("SignalASIVoice", "Voice command discarded: no speech detected bytes=${file.length()}")
            file.delete()
            updateWakeVoiceUi(getString(R.string.voice_status_no_speech), getString(R.string.voice_status_waiting_wake))
            if (activeMainTab == PAGE_VOICE) startWakeListening()
            return
        }
        selectedContact = contact
        val nativeAgentRoute = config.routingMode == VoiceAssistantSettings.ROUTING_MODE_NATIVE_AGENT
        val sent = if (nativeAgentRoute) {
            requestVoiceAgentTranscription(file, contact)
        } else {
            sendVoiceRecordingThroughPipeline(
                sourceFile = file,
                contact = contact,
                seconds = seconds,
                label = getString(R.string.voice_command_label, seconds),
                source = "voice_wakeup"
            )
        }
        Log.i("SignalASIVoice", "Voice command recording stopped duration=${seconds}s sent=$sent target=${contact.id}")
        updateWakeVoiceUi(
            when {
                !sent -> getString(R.string.voice_status_transcription_failed)
                nativeAgentRoute -> getString(R.string.voice_status_transcribing)
                else -> getString(R.string.voice_status_command_sent)
            },
            when {
                !sent -> getString(R.string.voice_status_retry_later)
                nativeAgentRoute -> getString(R.string.voice_status_waiting_transcript, contact.name)
                else -> getString(R.string.voice_status_waiting_reply, contact.name)
            }
        )
        voiceCommandSpeechDetected = false
        voiceCommandLastVoiceAt = 0L
        if (!nativeAgentRoute || !sent) {
            handler.postDelayed({
                if (activeMainTab == PAGE_VOICE && wakePage.visibility == View.VISIBLE && !voiceAssistantSpeaking && !voiceAssistantRecordingCommand) {
                    startWakeListening()
                }
            }, 800L)
        }
    }

    private fun startVoiceRecognition() {
        if (activeMainTab != PAGE_VOICE || wakePage.visibility != View.VISIBLE || voiceAssistantSpeaking) return
        if (!ensureRecordPermission()) return
        if (voiceAssistantListening) {
            Log.i("SignalASIVoice", "ASR start ignored: already listening")
            return
        }
        val now = System.currentTimeMillis()
        if (now - lastVoiceRecognitionStartAt < 900L) {
            scheduleVoiceRestart(950L - (now - lastVoiceRecognitionStartAt))
            return
        }
        val config = VoiceAssistantSettings.get(this)
        ensureSpeechRecognizer()
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, config.asrLanguage)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, packageName)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 1200L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 800L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 800L)
        }
        runCatching {
            lastVoiceRecognitionStartAt = System.currentTimeMillis()
            speechRecognizer?.startListening(intent)
            voiceAssistantListening = true
            Log.i("SignalASIVoice", "ASR start language=${config.asrLanguage} awake=$voiceAssistantAwake")
        }.onFailure {
            voiceAssistantListening = false
            Log.e("SignalASIVoice", "ASR start failed", it)
            updateWakeVoiceUi(getString(R.string.voice_status_asr_start_failed), it.message ?: getString(R.string.voice_status_retry_later))
            scheduleVoiceRestart(1200L)
        }
    }

    private fun handleVoiceRecognitionText(text: String) {
        if (text.isBlank()) {
            scheduleVoiceRestart(500L)
            return
        }
        if (!voiceAssistantAwake) {
            if (containsWakeWord(text, VoiceAssistantSettings.get(this))) {
                onVoiceWakeDetected(text)
            } else {
                updateWakeVoiceUi(getString(R.string.voice_status_low_power), text)
                scheduleVoiceRestart(600L)
            }
            return
        }
        onVoiceCommand(text)
    }

    private fun onVoiceWakeDetected(text: String) {
        voiceAssistantAwake = true
        updateWakeVoiceUi(getString(R.string.voice_status_awake), text)
        speakWakeWelcomeThenListen(VoiceAssistantSettings.get(this).welcomeText)
    }

    private fun speakWakeWelcomeThenListen(text: String) {
        var progressed = false
        fun continueToCommand() {
            if (progressed) return
            progressed = true
            microsoftTts.stop()
            androidTts?.stop()
            voiceAssistantSpeaking = false
            startCommandListening()
        }
        handler.postDelayed({
            if (voiceAssistantAwake && activeMainTab == PAGE_VOICE) {
                Log.i("SignalASIVoice", "Wake welcome timeout, continue to command recording")
                continueToCommand()
            }
        }, 4500L)
        speakWithConfiguredTts(text, timeoutMs = 4500L, fallbackToAndroid = false) {
            Log.i("SignalASIVoice", "Wake welcome finished, continue to command recording")
            continueToCommand()
        }
    }

    private fun onVoiceCommand(text: String) {
        val config = VoiceAssistantSettings.get(this)
        if (config.routingMode == VoiceAssistantSettings.ROUTING_MODE_NATIVE_AGENT) {
            submitVoiceAgentGoal(text)
            return
        }
        val contact = voiceAssistantTargetContact(config)
        updateWakeVoiceUi(getString(R.string.voice_status_sent_to, contact.name), text)
        sendOutgoingText(contact, text)
        scheduleVoiceRestart(1200L)
    }

    private fun submitVoiceAgentGoal(text: String) {
        val goal = text.trim()
        if (goal.isBlank()) {
            scheduleVoiceRestart(500L)
            return
        }
        if (agentOperationInFlight) {
            updateWakeVoiceUi(getString(R.string.voice_agent_busy), goal)
            scheduleVoiceRestart(1000L)
            return
        }
        updateWakeVoiceUi(getString(R.string.voice_agent_planning), goal)
        runAgentOperationAsync(
            operation = { mobileNativeAgent.submitGoal(goal) },
            onComplete = ::presentVoiceAgentState
        )
    }

    private fun presentVoiceAgentState(state: AgentUiState) {
        if (activeMainTab != PAGE_VOICE || wakePage.visibility != View.VISIBLE) return
        val pending = state.pendingAction
        val detail = when (state.phase) {
            AgentPhase.WAITING_CONFIRMATION -> getString(
                R.string.voice_agent_confirmation_required,
                pending?.description ?: state.plan?.expectedResult.orEmpty()
            )
            AgentPhase.WAITING_RESPONSE -> state.lastActionResult?.message
                ?.ifBlank { getString(R.string.voice_agent_waiting_response) }
                ?: getString(R.string.voice_agent_waiting_response)
            AgentPhase.BLOCKED,
            AgentPhase.FAILED -> state.lastActionResult?.message
                ?.ifBlank { getString(R.string.voice_agent_failed) }
                ?: getString(R.string.voice_agent_failed)
            AgentPhase.PAUSED -> getString(R.string.voice_agent_paused)
            AgentPhase.COMPLETED -> state.lastActionResult?.message
                ?.ifBlank { state.plan?.expectedResult.orEmpty() }
                ?.ifBlank { getString(R.string.voice_agent_completed) }
                ?: getString(R.string.voice_agent_completed)
            else -> state.lastActionResult?.message
                ?.ifBlank { state.plan?.expectedResult.orEmpty() }
                ?.ifBlank { getString(R.string.voice_agent_running) }
                ?: getString(R.string.voice_agent_running)
        }.take(4_000)
        val status = when (state.phase) {
            AgentPhase.WAITING_CONFIRMATION -> getString(R.string.voice_agent_needs_confirmation)
            AgentPhase.WAITING_RESPONSE -> getString(R.string.voice_agent_waiting)
            AgentPhase.COMPLETED -> getString(R.string.voice_agent_completed)
            AgentPhase.BLOCKED,
            AgentPhase.FAILED -> getString(R.string.voice_agent_failed)
            AgentPhase.PAUSED -> getString(R.string.voice_agent_paused)
            else -> getString(R.string.voice_agent_running)
        }
        wakeReplyPinnedUntilMs = System.currentTimeMillis() + 60_000L
        updateWakeVoiceUi(status, detail)
        val config = VoiceAssistantSettings.get(this)
        val waitingForRemoteAgent = state.phase == AgentPhase.WAITING_RESPONSE
        if (config.speakReplies && detail.isNotBlank()) {
            speakWithConfiguredTts(detail.take(1_200)) {
                if (!waitingForRemoteAgent && voiceAssistantAwake && activeMainTab == PAGE_VOICE) {
                    startCommandListening()
                }
            }
        } else if (!waitingForRemoteAgent) {
            scheduleVoiceRestart(900L)
        }
    }

    private fun processVoiceAgentTranscript(success: Boolean, content: String) {
        val transcript = content.trim()
        if (!success || transcript.isBlank()) {
            updateWakeVoiceUi(
                getString(R.string.voice_status_transcription_failed),
                transcript.ifBlank { getString(R.string.voice_status_retry_later) }
            )
            scheduleVoiceRestart(1200L)
            return
        }
        updateWakeVoiceUi(getString(R.string.voice_status_transcribed), transcript)
        submitVoiceAgentGoal(transcript)
    }

    private fun voiceAssistantTargetContact(config: VoiceAssistantConfig = VoiceAssistantSettings.get(this)): Contact {
        val contactId = if (config.routingMode == VoiceAssistantSettings.ROUTING_MODE_NATIVE_AGENT) {
            resolveVoiceAssistantSttContactId(config.targetContactId)
        } else {
            resolveVoiceAssistantTargetContactId(config.targetContactId)
        }
        return contactById(contactId)
    }

    private fun resolveVoiceAssistantSttContactId(configuredId: String): String {
        if (configuredId.isNotBlank() &&
            AppStore.usesPcConnectorTunnel(this, configuredId) &&
            AppStore.outgoingTopicForContact(this, configuredId) != null
        ) {
            return configuredId
        }
        val contacts = AppStore.contacts(this)
        for (index in 0 until contacts.length()) {
            val raw = contacts.optJSONObject(index) ?: continue
            if (raw.optBoolean("deleted", false)) continue
            val id = raw.optString("id").ifBlank { jsonSignalasiId(raw) }
            if (id.isNotBlank() &&
                AppStore.usesPcConnectorTunnel(this, id) &&
                AppStore.outgoingTopicForContact(this, id) != null
            ) {
                return id
            }
        }
        return CONTACT_HERMES.id
    }

    private fun resolveVoiceAssistantTargetContactId(configuredId: String): String {
        val configured = configuredId.ifBlank { CONTACT_HERMES.id }
        if (configured != CONTACT_HERMES.id && AppStore.canCommunicateWith(this, configured)) {
            return configured
        }

        val contacts = AppStore.contacts(this)
        for (i in 0 until contacts.length()) {
            val raw = contacts.optJSONObject(i) ?: continue
            if (raw.optBoolean("deleted", false) || raw.optString("trust_state") == "deleted") continue
            val id = raw.optString("id").ifBlank { jsonSignalasiId(raw) }
            if (id.isBlank() || id == CONTACT_HERMES.id || !AppStore.canCommunicateWith(this, id)) continue

            val agentId = raw.optString("agent_id")
            val desktopId = raw.optString("desktop_id")
            val deliveryMode = raw.optString("delivery_mode")
            val name = raw.optString("name", id)
            val isHermesAgent = agentId == CONTACT_HERMES.id ||
                id.substringAfter(':', "") == CONTACT_HERMES.id ||
                name.contains("Hermes", ignoreCase = true)
            val usesDesktopTunnel = desktopId.isNotBlank() ||
                deliveryMode == "pc_connector" ||
                raw.optString("parent_contact") == CONTACT_HERMES.id ||
                raw.optString("signal_session") == "pc_tunnel"
            if (isHermesAgent && usesDesktopTunnel) return id
        }

        return configured.takeIf { AppStore.canCommunicateWith(this, it) } ?: CONTACT_HERMES.id
    }

    private fun maybeSpeakIncomingReply(msg: ChatMessage) {
        val config = VoiceAssistantSettings.get(this)
        if (!config.speakReplies || !voiceAssistantAwake || activeMainTab != PAGE_VOICE) return
        if (msg.isMine || msg.contact.id == CONTACT_SYSTEM.id || msg.content.isBlank()) return
        speakWithConfiguredTts(msg.content.take(600)) {
            startCommandListening()
        }
    }

    private fun showVoiceAssistantReply(msg: ChatMessage) {
        if (activeMainTab != PAGE_VOICE || wakePage.visibility != View.VISIBLE) return
        if (msg.isMine || msg.contact.id == CONTACT_SYSTEM.id || msg.content.isBlank()) return
        val targetId = resolveVoiceAssistantTargetContactId(VoiceAssistantSettings.get(this).targetContactId)
        if (msg.contact.id != targetId) return
        wakeReplyPinnedUntilMs = System.currentTimeMillis() + 60_000L
        updateWakeVoiceUi(getString(R.string.voice_status_reply_received), msg.content)
    }

    private fun speakWithConfiguredTts(
        text: String,
        timeoutMs: Long = 20_000L,
        fallbackToAndroid: Boolean = true,
        after: () -> Unit
    ) {
        if (text.isBlank()) {
            after()
            return
        }
        runCatching { speechRecognizer?.cancel() }
        voiceAssistantListening = false
        voiceAssistantSpeaking = true
        updateWakeVoiceUi(getString(R.string.voice_status_speaking), text.take(60))
        val config = VoiceAssistantSettings.get(this)
        if (config.ttsProvider == VoiceAssistantSettings.PROVIDER_MICROSOFT_EDGE) {
            var completed = false
            handler.postDelayed({
                if (!completed && voiceAssistantSpeaking) {
                    completed = true
                    microsoftTts.stop()
                    voiceAssistantSpeaking = false
                    after()
                }
            }, timeoutMs)
            microsoftTts.speak(text, config.microsoftVoice) { success, _ ->
                runOnUiThread {
                    if (completed) return@runOnUiThread
                    if (success) {
                        completed = true
                        voiceAssistantSpeaking = false
                        after()
                    } else if (fallbackToAndroid) {
                        completed = true
                        speakWithAndroidTts(text, after)
                    } else {
                        completed = true
                        voiceAssistantSpeaking = false
                        after()
                    }
                }
            }
        } else {
            speakWithAndroidTts(text, after)
        }
    }

    private fun speakWithAndroidTts(text: String, after: () -> Unit) {
        if (!androidTtsReady) {
            voiceAssistantSpeaking = false
            after()
            return
        }
        val utteranceId = "signalasi_voice_${System.currentTimeMillis()}"
        configureAndroidTtsLanguage()
        androidTts?.speak(text, TextToSpeech.QUEUE_FLUSH, Bundle(), utteranceId)
        handler.postDelayed({
            if (voiceAssistantSpeaking) {
                voiceAssistantSpeaking = false
                after()
            }
        }, 20_000L)
    }

    private fun configureAndroidTtsLanguage() {
        val languageTag = VoiceAssistantSettings.get(this).asrLanguage.ifBlank { "zh-CN" }
        androidTts?.language = Locale.forLanguageTag(languageTag)
    }

    private fun onVoiceSpeechFinished() {
        if (!voiceAssistantSpeaking) return
        voiceAssistantSpeaking = false
        if (activeMainTab == PAGE_VOICE) {
            if (voiceAssistantAwake) startCommandListening() else startWakeListening()
        }
    }

    private fun scheduleVoiceRestart(delayMs: Long) {
        if (voiceAssistantRestartPending || activeMainTab != PAGE_VOICE || wakePage.visibility != View.VISIBLE || voiceAssistantSpeaking) return
        voiceAssistantRestartPending = true
        handler.postDelayed({
            voiceAssistantRestartPending = false
            if (activeMainTab != PAGE_VOICE || wakePage.visibility != View.VISIBLE || voiceAssistantSpeaking) return@postDelayed
            if (voiceAssistantAwake) startCommandListening() else startWakeListening()
        }, delayMs)
    }

    private fun bestSpeechResult(bundle: Bundle?): String {
        return bundle
            ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            ?.firstOrNull()
            ?.trim()
            .orEmpty()
    }

    private fun speechErrorLabel(error: Int): String = when (error) {
        SpeechRecognizer.ERROR_AUDIO -> getString(R.string.voice_error_audio)
        SpeechRecognizer.ERROR_CLIENT -> getString(R.string.voice_error_client)
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> getString(R.string.voice_error_permission)
        SpeechRecognizer.ERROR_NETWORK -> getString(R.string.voice_error_network)
        SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> getString(R.string.voice_error_network_timeout)
        SpeechRecognizer.ERROR_NO_MATCH -> getString(R.string.voice_error_no_match)
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> getString(R.string.voice_error_busy)
        SpeechRecognizer.ERROR_SERVER -> getString(R.string.voice_error_server)
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> getString(R.string.voice_error_speech_timeout)
        else -> getString(R.string.voice_error_unknown, error)
    }

    private fun speechErrorDetail(error: Int): String {
        val label = speechErrorLabel(error)
        return when (error) {
            SpeechRecognizer.ERROR_NETWORK,
            SpeechRecognizer.ERROR_NETWORK_TIMEOUT,
            SpeechRecognizer.ERROR_SERVER -> getString(R.string.voice_error_system_retry, label)
            SpeechRecognizer.ERROR_NO_MATCH,
            SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> getString(R.string.voice_error_no_valid_speech)
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY,
            SpeechRecognizer.ERROR_CLIENT -> getString(R.string.voice_error_recovering)
            else -> getString(R.string.voice_error_waiting, label)
        }
    }

    private fun containsWakeWord(text: String, config: VoiceAssistantConfig): Boolean {
        val normalized = text.lowercase(Locale.getDefault()).replace("\\s+".toRegex(), "")
        val configuredMatched = config.wakeWords.any { word ->
            val w = word.lowercase(Locale.getDefault()).replace("\\s+".toRegex(), "")
            w.isNotBlank() && (normalized.contains(w) || text.contains(word, ignoreCase = true))
        }
        if (configuredMatched) return true
        val quickWakeWords = resources.getStringArray(R.array.voice_quick_wake_words).toList()
        return quickWakeWords.any { word ->
            normalized.contains(word.lowercase(Locale.getDefault()).replace("\\s+".toRegex(), "")) ||
                text.contains(word, ignoreCase = true)
        }
    }

    private fun updateWakeVoiceUi(status: String, detail: String) {
        val replyPinned = System.currentTimeMillis() < wakeReplyPinnedUntilMs
        val isReplyUpdate = status == getString(R.string.voice_status_reply_received) || status == getString(R.string.voice_status_speaking)
        val isUserActionUpdate = status.startsWith(getString(R.string.voice_status_awake_listening).substringBefore("，")) ||
            status.startsWith(getString(R.string.voice_status_awake_auto_recording).substringBefore("，")) ||
            status.startsWith(getString(R.string.voice_status_recording)) ||
            status.startsWith(getString(R.string.voice_status_command_sent)) ||
            status.startsWith(getString(R.string.voice_status_sent_to, "")) ||
            status.startsWith(getString(R.string.voice_status_recording_failed)) ||
            status.startsWith(getString(R.string.voice_status_no_speech))
        if (replyPinned && !isReplyUpdate && !isUserActionUpdate) {
            wakeStatusText?.text = status
            return
        }
        if (isUserActionUpdate) wakeReplyPinnedUntilMs = 0L
        wakeStatusText?.text = status
        wakeTranscriptText?.text = detail
        if (isReplyUpdate || isUserActionUpdate) {
            wakeReplyPanel?.visibility = View.VISIBLE
        }
    }

    private fun styleSettingsRows() {
        // Apply WeChat-style row styling
        listOf(
            R.id.newFriendsButton, R.id.groupChatsButton,
            R.id.myAgentsButton, R.id.myDevicesButton,
            R.id.languageSettingsButton,
            R.id.exportBackupButton, R.id.importBackupButton, R.id.protocolQualityButton,
            R.id.advancedOptionsButton, R.id.localModelSettingsButton,
            R.id.voiceAssistantSettingsButton, R.id.onDeviceAgentButton, R.id.destroyDataButton
        ).forEach { id ->
            findViewById<View>(id)?.let {
                if (it is TextView) {
                    it.compoundDrawablePadding = 12
                    it.compoundDrawableTintList = android.content.res.ColorStateList.valueOf(
                        getColorCompat(R.color.text_secondary)
                    )
                    it.setPadding(dp(16), 0, dp(16), 0)
                }
            }
        }
        normalizeSettingsRowVisuals()
    }

    private fun configureSettingsControlCenter() {
        controlCenterBackStack.clear()
        controlCenterDestination = null
    }

    private fun removeSettingsRow(card: ViewGroup, rowId: Int) {
        val row = findViewById<View>(rowId) ?: return
        val index = card.indexOfChild(row)
        if (index < 0) return
        card.removeViewAt(index)
        val dividerIndex = index.coerceAtMost(card.childCount - 1)
        if (dividerIndex >= 0 && card.getChildAt(dividerIndex).layoutParams.height <= dp(1)) {
            card.removeViewAt(dividerIndex)
        }
    }

    private fun normalizeSettingsRowVisuals() {
        val cards = listOf(
            R.id.settingsAgentToolsCard, R.id.settingsLocalAgentCard,
            R.id.settingsProtocolCard, R.id.settingsDataCard,
            R.id.settingsIdentityCard, R.id.settingsGeneralCard,
            R.id.settingsPagesCard
        )
        cards.forEach { normalizeSettingsRows(findViewById(it)) }
        normalizeSettingsTextRow(findViewById(R.id.destroyDataButton))
        listOf(R.id.meProfileArrow, R.id.aboutSignalASIArrow).forEach { id ->
            findViewById<ImageView>(id).apply {
                setImageDrawable(settingsDrawable(R.drawable.ic_arrow_right, "#C7C7CC", 16))
                layoutParams = layoutParams.apply { width = dp(24) }
                scaleType = ImageView.ScaleType.CENTER
            }
        }
        findViewById<ViewGroup>(R.id.aboutSignalASIButton).apply {
            layoutParams = layoutParams.apply { height = dp(62) }
            val icon = getChildAt(0) as ImageView
            icon.layoutParams = icon.layoutParams.apply { width = dp(24); height = dp(24) }
            icon.background = null
            icon.setPadding(0, 0, 0, 0)
            icon.imageTintList = android.content.res.ColorStateList.valueOf(getColorCompat(R.color.text_primary))
            val title = ((getChildAt(1) as LinearLayout).getChildAt(0) as TextView)
            title.text = getString(R.string.settings_about_short)
            title.textSize = 15f
            title.setTextColor(getColorCompat(R.color.text_primary))
            title.setTypeface(title.typeface, android.graphics.Typeface.NORMAL)
        }
    }

    private fun normalizeSettingsRows(view: View) {
        when (view) {
            is TextView -> normalizeSettingsTextRow(view)
            is ViewGroup -> for (index in 0 until view.childCount) normalizeSettingsRows(view.getChildAt(index))
        }
    }

    private fun normalizeSettingsTextRow(row: TextView) {
        val current = row.compoundDrawablesRelative
        if (current.all { it == null }) return
        val start = current[0]?.constantState?.newDrawable(resources)?.mutate()?.apply {
            setTint(Color.parseColor("#202124"))
            setBounds(0, 0, dp(24), dp(24))
        }
        val end = settingsDrawable(R.drawable.ic_arrow_right, "#C7C7CC", 16)
        row.compoundDrawableTintList = null
        row.setCompoundDrawablesRelative(start, null, end, null)
        row.compoundDrawablePadding = dp(14)
        row.textSize = 15f
        row.setTextColor(getColorCompat(R.color.text_primary))
        row.setTypeface(row.typeface, android.graphics.Typeface.NORMAL)
    }

    private fun settingsDrawable(resourceId: Int, color: String, sizeDp: Int) =
        requireNotNull(getDrawable(resourceId)).mutate().apply {
            setTint(Color.parseColor(color))
            setBounds(0, 0, dp(sizeDp), dp(sizeDp))
        }

    private fun sectionTitle(viewId: Int, textId: Int) {
        findViewById<TextView>(viewId).apply {
            setText(textId)
            setTextColor(getColorCompat(R.color.text_primary))
            textSize = 13f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(dp(3), dp(2), 0, 0)
        }
    }

    private fun settingsSectionTitleView(textId: Int): TextView = TextView(this).apply {
        setText(textId)
        setTextColor(getColorCompat(R.color.text_primary))
        textSize = 13f
        setTypeface(typeface, android.graphics.Typeface.BOLD)
        setPadding(dp(3), dp(14), 0, dp(6))
    }

    private fun rebuildProfileStatusBadges() {
        val textColumn = meProfileText.parent as LinearLayout
        if (textColumn.childCount > 2) return
        textColumn.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(6), 0, 0)
            addView(settingsBadge(R.string.settings_badge_agent_enabled, "#E8F8EF", "#27885A"))
            addView(settingsBadge(R.string.settings_badge_connection_ok, "#EAF2FF", "#3678D4").apply {
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    dp(24)
                ).apply { marginStart = dp(7) }
            })
        })
    }

    private fun settingsBadge(textId: Int, backgroundColor: String, textColor: String): TextView =
        TextView(this).apply {
            setText(textId)
            setTextColor(Color.parseColor(textColor))
            textSize = 11f
            gravity = Gravity.CENTER
            includeFontPadding = false
            setPadding(dp(9), 0, dp(9), 0)
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(12).toFloat()
                setColor(Color.parseColor(backgroundColor))
            }
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(24))
        }

    private fun rebuildIdentitySecurityCard() {
        val card = findViewById<LinearLayout>(R.id.settingsIdentityCard)
        (meIdText.parent as? ViewGroup)?.removeView(meIdText)
        card.removeAllViews()
        card.addView(meIdText)
        card.addView(settingsDivider())
        card.addView(dynamicSettingsRow(
            R.string.settings_trusted_devices,
            R.string.settings_trusted_devices_subtitle,
            R.drawable.ic_settings_devices
        ) { showSecurityFeaturePage() })
        card.addView(settingsDivider())
        card.addView(dynamicSettingsRow(
            R.string.settings_permission_audit,
            R.string.settings_permission_audit_subtitle,
            R.drawable.ic_security_shield
        ) { showOnDeviceAgentFeaturePage() })
    }

    private fun rebuildGeneralCard() {
        val card = findViewById<LinearLayout>(R.id.settingsGeneralCard)
        val language = findViewById<TextView>(R.id.languageSettingsButton)
        (language.parent as? ViewGroup)?.removeView(language)
        card.removeAllViews()
        card.addView(language)
        card.addView(settingsDivider())
        card.addView(dynamicSettingsRow(
            R.string.settings_notifications,
            R.string.settings_notifications_subtitle,
            R.drawable.ic_settings_notification
        ) {
            startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
            })
        })
    }

    private fun dynamicSettingsRow(titleId: Int, subtitleId: Int, iconId: Int, action: () -> Unit): TextView =
        TextView(this).apply {
            setCompoundDrawablesWithIntrinsicBounds(iconId, 0, R.drawable.ic_arrow_right, 0)
            compoundDrawablePadding = dp(14)
            setPadding(dp(16), 0, dp(16), 0)
            setOnClickListener { action() }
            settingsText(getString(titleId), getString(subtitleId))
        }

    private fun settingsDivider(): View = View(this).apply {
        setBackgroundColor(getColorCompat(R.color.separator))
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1).apply {
            marginStart = dp(56)
        }
    }

    private fun TextView.settingsText(title: String, subtitle: String) {
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

    private fun applySettingsCardSurfaces() {
        listOf(
            R.id.meProfileCard, R.id.settingsAgentToolsCard, R.id.settingsLocalAgentCard,
            R.id.settingsProtocolCard, R.id.settingsDataCard, R.id.settingsIdentityCard,
            R.id.settingsGeneralCard, R.id.settingsPagesCard,
            R.id.aboutSignalASIButton, R.id.destroyDataButton
        ).forEach { findViewById<View>(it).setBackgroundResource(R.drawable.settings_control_card_background) }
    }

    private fun settingsStatusRow(viewId: Int, titleId: Int, status: String) {
        findViewById<TextView>(viewId).apply {
            settingsText(getString(titleId), status)
            setTypeface(typeface, android.graphics.Typeface.NORMAL)
        }
    }

    private fun refreshSettingsControlCenter() {
        if (activeMainTab == PAGE_SETTINGS && featurePage.visibility != View.VISIBLE) {
            renderControlCenterHome()
        }
    }

    private fun renderControlCenterHome() {
        val content = findViewById<LinearLayout>(R.id.settingsContent)
        val state = mobileNativeAgent.snapshot()
        val tools = mobileNativeAgent.nativeToolCatalog()
        val availableTools = tools.count { it.availability.status == AgentNativeToolAvailabilityStatus.AVAILABLE }
        val toolsNeedingAttention = tools.size - availableTools
        val availableResources = controlCenterResourceTargets(state.callableTargets)
            .count { it.status == AgentConnectorStatus.AVAILABLE }
        val trustedDeviceCount = desktopSecuritySummaries(activePcConnectorContacts()).size
        val memoryCount = mobileNativeAgent.memorySnapshot().activeCount
        val knowledgeCount = mobileNativeAgent.knowledgeSourceGroups().size
        val recentTasks = state.recentTasks.size
        val safety = mobileNativeAgent.safetySettings()
        val planner = mobileNativeAgent.modelPlannerSettings()
        val privacyProtected = !planner.shareScreenText && !planner.shareAgentOutputsWithPlanner
        val secure = SignalASIMqttClient.isConnected() && SignalASIMqttClient.isSecureReady()
        val homeAssistant = HomeAssistantSettingsStore.load(this)
        val homeAssistantReady = homeAssistant.configured
        val onDeviceRuntime = AgentOnDeviceRuntimeManager(this).status()
        val globalRuntime = if (::globalSuperAgentRuntime.isInitialized) {
            globalSuperAgentRuntime
        } else GlobalSuperAgentRuntime.get(this)
        val globalDashboard = globalRuntime.dashboard()
        val globalSettings = globalRuntime.settings()
        val remoteControlDevices = desktopControlDevices()
        val remoteControlSnapshots = remoteControlDevices.map { device ->
            DesktopRemoteControl.snapshot(this, device.id)
        }
        val remoteControlStatus = when {
            remoteControlDevices.isEmpty() -> getString(R.string.status_needs_setup)
            remoteControlSnapshots.any { it.authorized } -> getString(R.string.status_enabled)
            remoteControlSnapshots.any { it.pending } -> getString(R.string.desktop_control_pending)
            remoteControlSnapshots.any { it.enabled } -> getString(R.string.desktop_control_not_authorized)
            else -> getString(R.string.desktop_control_executor_off)
        }
        val remoteControlTone = when {
            remoteControlSnapshots.any { it.authorized } -> ControlCenterTone.GREEN
            remoteControlSnapshots.any { it.pending } -> ControlCenterTone.AMBER
            remoteControlDevices.isEmpty() -> ControlCenterTone.NEUTRAL
            else -> ControlCenterTone.BLUE
        }

        controlCenterRenderer.render(
            content,
            ControlCenterPageSpec(
                hero = ControlCenterHeroSpec(
                    title = getString(R.string.settings_my_signalasi),
                    subtitle = getString(R.string.cc_product_subtitle),
                    iconRes = R.drawable.signalasi_mark_large,
                    preserveIconColor = true,
                    actionId = routeAction(ControlCenterRoute.PROFILE),
                    badges = listOf(
                        ControlCenterBadgeSpec(
                            getString(if (safety.executionPaused) R.string.on_device_agent_status_paused else R.string.cc_core_ready),
                            if (safety.executionPaused) ControlCenterTone.AMBER else ControlCenterTone.GREEN
                        ),
                        ControlCenterBadgeSpec(getString(R.string.cc_trusted_devices_badge, trustedDeviceCount), ControlCenterTone.BLUE),
                        ControlCenterBadgeSpec(
                            getString(if (privacyProtected) R.string.cc_privacy_badge else R.string.cc_status_review),
                            if (privacyProtected) ControlCenterTone.NEUTRAL else ControlCenterTone.AMBER
                        )
                    ),
                    metrics = listOf(
                        ControlCenterMetricSpec(availableResources.toString(), getString(R.string.cc_metric_resources)),
                        ControlCenterMetricSpec(recentTasks.toString(), getString(R.string.cc_metric_today_tasks)),
                        ControlCenterMetricSpec(getString(if (secure) R.string.cc_status_secure else R.string.cc_status_normal), getString(R.string.cc_metric_security))
                    )
                ),
                sections = listOf(
                    ControlCenterSectionSpec(
                        getString(R.string.cc_section_intelligent_core),
                        listOf(
                            ccRouteRow(
                                ControlCenterRoute.GLOBAL_AGENT,
                                getString(R.string.cc_global_agent_title),
                                getString(
                                    R.string.cc_global_agent_home_subtitle,
                                    globalDashboard.topicCount,
                                    globalDashboard.activeGoalCount,
                                    globalDashboard.pendingInsightCount
                                ),
                                R.drawable.ic_agent_node,
                                getString(if (globalSettings.enabled) R.string.cc_status_online else R.string.on_device_agent_status_paused),
                                if (globalSettings.enabled) ControlCenterTone.VIOLET else ControlCenterTone.AMBER
                            ),
                            ccRouteRow(ControlCenterRoute.AGENT_CORE, R.string.cc_agent_core_title, R.string.cc_agent_core_subtitle, R.drawable.ic_agent_node, getString(if (safety.executionPaused) R.string.on_device_agent_status_paused else R.string.cc_status_online), if (safety.executionPaused) ControlCenterTone.AMBER else ControlCenterTone.GREEN),
                            ccRouteRow(ControlCenterRoute.RESOURCE_ROUTING, R.string.cc_resource_routing_title, R.string.cc_resource_routing_subtitle, R.drawable.ic_settings_model, getString(if (availableResources > 0) R.string.cc_status_available else R.string.status_needs_setup), if (availableResources > 0) ControlCenterTone.BLUE else ControlCenterTone.AMBER),
                            ccRouteRow(ControlCenterRoute.MEMORY, getString(R.string.cc_memory_title), getString(R.string.cc_memory_subtitle, memoryCount), R.drawable.ic_agent_memory, getString(if (safety.memoryCapture) R.string.status_enabled else R.string.common_off), if (safety.memoryCapture) ControlCenterTone.GREEN else ControlCenterTone.NEUTRAL),
                            ccRouteRow(ControlCenterRoute.LEARNING, R.string.cc_learning_title, R.string.cc_learning_subtitle, R.drawable.ic_agent_skill, agentLearningEngine.proposals(AgentLearningProposalStatus.PENDING).size.toString(), ControlCenterTone.VIOLET),
                            ccRouteRow(ControlCenterRoute.KNOWLEDGE, getString(R.string.cc_knowledge_title), getString(R.string.cc_knowledge_subtitle, knowledgeCount), R.drawable.ic_agent_knowledge, knowledgeCount.toString(), ControlCenterTone.AMBER)
                        )
                    ),
                    ControlCenterSectionSpec(
                        getString(R.string.cc_section_execution_devices),
                        listOf(
                            ccRouteRow(ControlCenterRoute.PHONE_CAPABILITIES, getString(R.string.cc_phone_title), getString(R.string.cc_phone_subtitle, availableTools, toolsNeedingAttention), R.drawable.ic_agent_control, "$availableTools/${tools.size}", if (availableTools > 0) ControlCenterTone.GREEN else ControlCenterTone.AMBER),
                            ccRouteRow(ControlCenterRoute.ON_DEVICE_RUNTIME, R.string.cc_runtime_title, R.string.cc_runtime_subtitle, R.drawable.ic_settings_diagnostics, getString(if (onDeviceRuntime.backendReady) R.string.cc_status_ready else R.string.status_needs_setup), if (onDeviceRuntime.backendReady) ControlCenterTone.GREEN else ControlCenterTone.AMBER),
                            ccRouteRow(ControlCenterRoute.APP_TOOLS, R.string.cc_apps_title, R.string.cc_apps_subtitle, R.drawable.ic_tab_discover, "", ControlCenterTone.BLUE),
                            ControlCenterRowSpec(
                                actionId = "desktop.remote_control",
                                title = getString(R.string.desktop_control_title),
                                subtitle = getString(R.string.desktop_control_home_subtitle),
                                iconRes = R.drawable.ic_device_node,
                                status = remoteControlStatus,
                                tone = remoteControlTone
                            ),
                            ccRouteRow(
                                ControlCenterRoute.SMART_SPACES,
                                R.string.cc_spaces_title,
                                R.string.cc_spaces_subtitle,
                                R.drawable.ic_device_node,
                                getString(
                                    when {
                                        !homeAssistant.credentialsConfigured -> R.string.cc_status_not_configured
                                        homeAssistantReady -> R.string.status_enabled
                                        else -> R.string.common_off
                                    }
                                ),
                                if (homeAssistantReady) ControlCenterTone.GREEN else ControlCenterTone.AMBER
                            ),
                            ccRouteRow(ControlCenterRoute.TASKS, R.string.cc_tasks_title, R.string.cc_tasks_subtitle, R.drawable.ic_agent_history, recentTasks.toString(), if (state.runningTaskCount > 0) ControlCenterTone.AMBER else ControlCenterTone.NEUTRAL),
                            ccRouteRow(
                                ControlCenterRoute.MCP,
                                R.string.agent_capability_library_title,
                                R.string.agent_capability_library_subtitle,
                                R.drawable.ic_agent_skill,
                                (agentMcpRegistry.list().size + agentSkillRuntime.list(enabledOnly = true).map { it.id }.distinct().size).toString(),
                                ControlCenterTone.VIOLET
                            )
                        )
                    ),
                    ControlCenterSectionSpec(
                        getString(R.string.cc_section_connection_trust),
                        listOf(
                            ccRouteRow(ControlCenterRoute.SYSTEM_STATUS, R.string.cc_system_status_title, if (secure) R.string.cc_all_services_normal_subtitle else R.string.cc_services_need_attention_subtitle, R.drawable.ic_info_outline, getString(if (secure) R.string.cc_status_normal else R.string.cc_status_degraded), if (secure) ControlCenterTone.GREEN else ControlCenterTone.AMBER),
                            ccRouteRow(ControlCenterRoute.NODES, R.string.cc_nodes_title, R.string.cc_nodes_subtitle, R.drawable.ic_protocol_link, availableResources.toString(), if (availableResources > 0) ControlCenterTone.GREEN else ControlCenterTone.AMBER),
                            ccRouteRow(ControlCenterRoute.SECURITY, R.string.cc_security_title, R.string.cc_security_subtitle, R.drawable.ic_security_shield, getString(if (secure) R.string.cc_status_secure else R.string.cc_status_normal), ControlCenterTone.GREEN),
                            ccRouteRow(ControlCenterRoute.PERMISSIONS_AUDIT, R.string.cc_audit_title, R.string.cc_audit_subtitle, R.drawable.ic_settings_fingerprint, "", ControlCenterTone.VIOLET)
                        )
                    ),
                    ControlCenterSectionSpec(
                        getString(R.string.cc_section_interaction_system),
                        listOf(
                            ccRouteRow(ControlCenterRoute.VOICE, R.string.cc_voice_title, R.string.cc_voice_subtitle, R.drawable.ic_settings_voice, getString(if (VoiceAssistantSettings.get(this).enabled) R.string.status_enabled else R.string.common_off), ControlCenterTone.BLUE),
                            ccRouteRow(ControlCenterRoute.DATA_BACKUP, R.string.cc_data_title, R.string.cc_data_subtitle, R.drawable.ic_settings_upload, "", ControlCenterTone.VIOLET)
                        )
                    ),
                    ControlCenterSectionSpec(
                        getString(R.string.cc_section_app_services),
                        listOf(ccRouteRow(ControlCenterRoute.APP_SERVICES, R.string.cc_app_services_title, R.string.cc_app_services_subtitle, R.drawable.ic_more_horizontal, "", ControlCenterTone.NEUTRAL))
                    ),
                    ControlCenterSectionSpec(
                        getString(R.string.cc_section_general),
                        listOf(ccRouteRow(ControlCenterRoute.GENERAL, R.string.cc_general_title, R.string.cc_general_subtitle, R.drawable.ic_tab_settings, "", ControlCenterTone.NEUTRAL))
                    )
                )
            ),
            ::handleControlCenterAction
        )
    }

    private fun ccRouteRow(
        route: ControlCenterRoute,
        titleId: Int,
        subtitleId: Int,
        iconRes: Int,
        status: String,
        tone: ControlCenterTone
    ): ControlCenterRowSpec = ccRouteRow(
        route,
        getString(titleId),
        getString(subtitleId),
        iconRes,
        status,
        tone
    )

    private fun ccRouteRow(
        route: ControlCenterRoute,
        title: String,
        subtitle: String,
        iconRes: Int,
        status: String,
        tone: ControlCenterTone
    ): ControlCenterRowSpec = ControlCenterRowSpec(
        actionId = routeAction(route),
        title = title,
        subtitle = subtitle,
        iconRes = iconRes,
        status = status,
        tone = tone
    )

    private fun routeAction(route: ControlCenterRoute): String = "route:${route.wireValue}"

    private fun handleControlCenterAction(actionId: String) {
        if (actionId.startsWith("route:")) {
            ControlCenterRoute.fromWireValue(actionId.substringAfter("route:"))?.let {
                openControlCenterDestination(ControlCenterDestination(it))
            }
            return
        }
        when (actionId) {
            "global.toggle_enabled" -> updateGlobalAgentSettings { it.copy(enabled = !it.enabled) }
            "global.toggle_proactive" -> updateGlobalAgentSettings {
                it.copy(proactiveInsightsEnabled = !it.proactiveInsightsEnabled)
            }
            "global.toggle_model_understanding" -> updateGlobalAgentSettings {
                it.copy(modelUnderstandingEnabled = !it.modelUnderstandingEnabled)
            }
            "global.toggle_autonomous_preparation" -> updateGlobalAgentSettings {
                it.copy(autonomousPreparationEnabled = !it.autonomousPreparationEnabled)
            }
            "global.toggle_autonomous_tools" -> updateGlobalAgentSettings {
                it.copy(autonomousToolExecutionEnabled = !it.autonomousToolExecutionEnabled)
            }
            "global.toggle_dynamic_replanning" -> updateGlobalAgentSettings {
                it.copy(dynamicAutonomousReplanningEnabled = !it.dynamicAutonomousReplanningEnabled)
            }
            "global.toggle_long_horizon" -> updateGlobalAgentSettings {
                it.copy(longHorizonPlanningEnabled = !it.longHorizonPlanningEnabled)
            }
            "global.toggle_discovery" -> updateGlobalAgentSettings {
                it.copy(proactiveDiscoveryEnabled = !it.proactiveDiscoveryEnabled)
            }
            "global.toggle_cloud_cognition" -> updateGlobalAgentSettings {
                it.copy(allowCloudCognition = !it.allowCloudCognition)
            }
            "global.toggle_learning" -> updateGlobalAgentSettings {
                it.copy(adaptiveLearningEnabled = !it.adaptiveLearningEnabled)
            }
            "global.toggle_research" -> updateGlobalAgentSettings {
                it.copy(autonomousResearchEnabled = !it.autonomousResearchEnabled)
            }
            "global.toggle_auto_conversations" -> updateGlobalAgentSettings {
                it.copy(autoCreateConversationsEnabled = !it.autoCreateConversationsEnabled)
            }
            "global.toggle_notifications" -> updateGlobalAgentSettings {
                it.copy(notificationsEnabled = !it.notificationsEnabled)
            }
            "global.toggle_battery_protection" -> updateGlobalAgentSettings {
                it.copy(protectBatteryForBackgroundWork = !it.protectBatteryForBackgroundWork)
            }
            "global.toggle_metered_research" -> updateGlobalAgentSettings {
                it.copy(allowMeteredBackgroundResearch = !it.allowMeteredBackgroundResearch)
            }
            "global.daily_model_calls" -> showGlobalDailyModelCallBudgetDialog()
            "global.concurrent_model_calls" -> showGlobalConcurrentModelCallBudgetDialog()
            "global.daily_model_tokens" -> showGlobalDailyModelTokenBudgetDialog()
            "global.daily_reported_cost" -> showGlobalDailyReportedCostBudgetDialog()
            "global.process_now" -> processGlobalAgentNow()
            "global.world.goals" -> showGlobalWorldItemsDialog(GlobalWorldItemKind.GOAL)
            "global.world.tasks" -> showGlobalWorldItemsDialog(GlobalWorldItemKind.TASK)
            "global.world.conflicts" -> showGlobalWorldConflictsDialog()
            "global.world.links" -> showGlobalConversationLinksDialog()
            "global.research" -> showGlobalResearchTasksDialog()
            "global.cognition" -> showGlobalCognitionTasksDialog()
            "global.runs" -> showGlobalAutonomousRunsDialog()
            "global.long_horizon" -> showGlobalLongHorizonGoalsDialog()
            "global.insights" -> showGlobalPendingInsightsDialog()
            "global.learning" -> showGlobalLearningDialog()
            "global.continuity" -> showGlobalContinuityDialog()
            "profile.nickname" -> openExistingControlCenterPage { showEditNicknameDialog() }
            "profile.copy_id" -> copyText(SignalASICrypto.localSignalasiId(), getString(R.string.security_copied_signalasi_id))
            "profile.copy_fingerprint" -> copyText(SignalASICrypto.localIdentitySha256(), getString(R.string.security_copied_phone_fingerprint))
            "profile.recovery" -> openControlCenterDestination(ControlCenterDestination(ControlCenterRoute.DATA_BACKUP))
            "agent.execution_policy" -> openControlCenterDestination(ControlCenterDestination(ControlCenterRoute.EXECUTION_POLICY))
            "agent.permission_mode" -> openExistingControlCenterPage { showPermissionModeSettingsPage() }
            "agent.toggle_pause" -> {
                val next = !mobileNativeAgent.safetySettings().executionPaused
                mobileNativeAgent.updateExecutionPaused(next)
                renderCurrentControlCenterDestination()
            }
            "agent.planner" -> openExistingControlCenterPage { showAgentPlannerSettingsPage() }
            "agent.planner.toggle_enabled" -> {
                mobileNativeAgent.updateModelPlannerEnabled(!mobileNativeAgent.modelPlannerSettings().enabled)
                showAgentPlannerSettingsPage()
            }
            "agent.planner.toggle_screen_text" -> {
                mobileNativeAgent.updateModelPlannerScreenText(!mobileNativeAgent.modelPlannerSettings().shareScreenText)
                showAgentPlannerSettingsPage()
            }
            "agent.planner.model_source" -> showAgentModelPlannerSourceDialog { showAgentPlannerSettingsPage() }
            "agent.planner.toggle_replanning" -> {
                mobileNativeAgent.updateModelPlannerDynamicReplanning(!mobileNativeAgent.modelPlannerSettings().dynamicReplanning)
                showAgentPlannerSettingsPage()
            }
            "agent.planner.max_replans" -> {
                val current = mobileNativeAgent.modelPlannerSettings().maxReplans
                mobileNativeAgent.updateModelPlannerMaxReplans(if (current < 3) 3 else if (current < 5) 5 else 1)
                showAgentPlannerSettingsPage()
            }
            "agent.planner.toggle_multi_agent" -> {
                mobileNativeAgent.updateMultiAgentCoordination(!mobileNativeAgent.modelPlannerSettings().multiAgentCoordination)
                showAgentPlannerSettingsPage()
            }
            "agent.planner.toggle_share_outputs" -> {
                mobileNativeAgent.updateShareAgentOutputsWithPlanner(!mobileNativeAgent.modelPlannerSettings().shareAgentOutputsWithPlanner)
                showAgentPlannerSettingsPage()
            }
            "agent.planner.max_hops" -> {
                val current = mobileNativeAgent.modelPlannerSettings().maxAgentHops
                mobileNativeAgent.updateMaxAgentHops(if (current < 4) 4 else if (current < 8) 8 else 2)
                showAgentPlannerSettingsPage()
            }
            "agent.planner.max_tools" -> {
                val current = mobileNativeAgent.modelPlannerSettings().maxToolCalls
                mobileNativeAgent.updateMaxToolCalls(if (current < 16) 16 else if (current < 32) 32 else 8)
                showAgentPlannerSettingsPage()
            }
            "agent.planner.max_actions" -> {
                val current = mobileNativeAgent.modelPlannerSettings().maxActions
                mobileNativeAgent.updateModelPlannerMaxActions(if (current < 8) 8 else if (current < 12) 12 else 4)
                showAgentPlannerSettingsPage()
            }
            "memory.manage" -> openExistingControlCenterPage { showAgentMemoryPage() }
            "memory.inbox" -> openExistingControlCenterPage { showGlobalMemoryInboxPage() }
            "memory.evolution_history" -> openExistingControlCenterPage { showGlobalMemoryEvolutionHistoryPage() }
            "memory.graph" -> openExistingControlCenterPage { showGlobalMemoryGraphPage() }
            "memory.audit" -> openExistingControlCenterPage { showGlobalMemoryAuditPage() }
            "memory.toggle_capture" -> {
                val next = !mobileNativeAgent.safetySettings().memoryCapture
                mobileNativeAgent.updateMemoryCapture(next)
                renderCurrentControlCenterDestination()
            }
            "learning.toggle_capture" -> {
                val next = !mobileNativeAgent.safetySettings().memoryCapture
                mobileNativeAgent.updateMemoryCapture(next)
                renderCurrentControlCenterDestination()
            }
            "runtime.catalog_refresh" -> refreshRuntimePackCatalog()
            "runtime.lifecycle" -> showRuntimeLifecycleDialog()
            "runtime.import" -> openRuntimePackPicker()
            "runtime.software_search" -> showRuntimeSoftwareSearchDialog()
            "runtime.software_clear_search" -> {
                controlCenterDestination = ControlCenterDestination(ControlCenterRoute.SOFTWARE_CENTER)
                renderCurrentControlCenterDestination()
            }
            "phone.catalog" -> openExistingControlCenterPage { showNativeToolCatalogPage() }
            "apps.adapters" -> openExistingControlCenterPage { showAgentAppAdaptersPage() }
            "desktop.remote_control" -> openExistingControlCenterPage { showDesktopControlPicker() }
            "spaces.configure" -> openExistingControlCenterPage { showDeviceFeaturePage() }
            "spaces.entities" -> showHomeAssistantCollectionPage("entities")
            "spaces.automations" -> showHomeAssistantCollectionPage("automations")
            "nodes.scan" -> {
                scanMode = "security"
                startSecurityScan()
            }
            "permissions.accessibility" -> startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            "permissions.notifications" -> startActivity(Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS"))
            "permissions.microphone" -> requestPermissions(arrayOf(android.Manifest.permission.RECORD_AUDIO), REQUEST_CONTROL_CENTER_PERMISSION)
            "permissions.camera" -> requestPermissions(arrayOf(android.Manifest.permission.CAMERA), REQUEST_CONTROL_CENTER_PERMISSION)
            "audit.operations" -> openExistingControlCenterPage { showAgentAuditOperationsPage() }
            "voice.settings" -> openExistingControlCenterPage { showVoiceAssistantSettingsPage() }
            "voice.asr" -> openExistingControlCenterPage { showAsrProviderPage() }
            "voice.toggle_enabled" -> {
                val next = !VoiceAssistantSettings.get(this).enabled
                if (next && checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                    pendingVoiceEnableFromControlCenter = true
                    requestPermissions(arrayOf(android.Manifest.permission.RECORD_AUDIO), REQUEST_CONTROL_CENTER_PERMISSION)
                } else {
                    VoiceAssistantSettings.setEnabled(this, next)
                    renderCurrentControlCenterDestination()
                }
            }
            "data.export" -> openExistingControlCenterPage { showExportBackupDialog() }
            "data.import" -> openBackupImportPicker()
            "data.cache" -> clearRebuildableCache()
            "general.language" -> openExistingControlCenterPage { showLanguageSettingsPage() }
            "general.notifications" -> startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
            })
            "general.appearance" -> startActivity(Intent(Settings.ACTION_DISPLAY_SETTINGS))
            "general.text_size" -> openExistingControlCenterPage { showTextSizeSettingsPage() }
            "general.about" -> openExistingControlCenterPage { showAboutSignalASIPage() }
            "general.advanced" -> openControlCenterDestination(ControlCenterDestination(ControlCenterRoute.ADVANCED))
            "apps.messages" -> exitControlCenterToTab(PAGE_MESSAGES)
            "apps.contacts" -> exitControlCenterToTab(PAGE_CONTACTS)
            "apps.discover" -> exitControlCenterToTab(PAGE_DISCOVER)
            "apps.chat_history" -> showAgentSessionsPage()
            "security.manage" -> openExistingControlCenterPage { showSecurityFeaturePage() }
            "security.toggle_guard" -> {
                mobileNativeAgent.updateHighRiskGuard(!mobileNativeAgent.safetySettings().highRiskGuard)
                renderCurrentControlCenterDestination()
            }
            "routing.add_cloud" -> openExistingControlCenterPage { showCloudProviderPage() }
            "routing.manage" -> openExistingControlCenterPage { showAgentFeaturePage() }
            "routing.policy" -> openExistingControlCenterPage { showRoutingPolicyPage() }
            "apps.background" -> startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.parse("package:$packageName")
            })
            "advanced.protocol" -> openExistingControlCenterPage { showSignalLinkProtocolPage() }
            "advanced.audit" -> openExistingControlCenterPage { showAgentAuditOperationsPage() }
            "advanced.permissions" -> openControlCenterDestination(ControlCenterDestination(ControlCenterRoute.PERMISSIONS_AUDIT))
            "advanced.app_details" -> startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.parse("package:$packageName")
            })
            "advanced.cache" -> clearRebuildableCache()
            "reset.begin" -> showResetConfirmationDialog()
            else -> when {
                actionId.startsWith("agent.permission_mode:") -> {
                    val mode = runCatching {
                        PermissionMode.valueOf(actionId.substringAfter(':').uppercase(Locale.ROOT))
                    }.getOrNull() ?: return
                    mobileNativeAgent.updatePermissionMode(mode)
                    showPermissionModeSettingsPage()
                }
                actionId.startsWith("general.text_scale:") -> {
                    AppDisplaySettings.setTextScale(
                        this,
                        AppDisplaySettings.TextScaleMode.fromWireValue(actionId.substringAfter(':'))
                    )
                    recreateIntoControlCenterChild(CONTROL_CENTER_CHILD_TEXT_SIZE)
                }
                actionId.startsWith("memory.group:") -> {
                    val kinds = when (actionId.substringAfter("memory.group:")) {
                        "identity" -> setOf(AgentMemoryKind.IDENTITY, AgentMemoryKind.PREFERENCE)
                        "people" -> setOf(AgentMemoryKind.CONTACT)
                        "work" -> setOf(AgentMemoryKind.TASK, AgentMemoryKind.WORKFLOW)
                        "knowledge" -> setOf(AgentMemoryKind.KNOWLEDGE, AgentMemoryKind.SAFETY)
                        else -> emptySet()
                    }
                    if (kinds.isNotEmpty()) {
                        openExistingControlCenterPage { showAgentMemoryPage(kinds) }
                    }
                }
                actionId.startsWith("learning.proposal:") -> {
                    showLearningProposalDialog(actionId.substringAfter("learning.proposal:"))
                }
                actionId.startsWith("runtime.pack:") -> {
                    showRuntimePackDialog(actionId.substringAfter("runtime.pack:"))
                }
                actionId.startsWith("runtime.catalog_pack:") -> {
                    showRuntimeCatalogPackDialog(actionId.substringAfter("runtime.catalog_pack:"))
                }
                actionId.startsWith("runtime.auto_install:") -> {
                    autoInstallRuntimePack(actionId.substringAfter("runtime.auto_install:"))
                }
                actionId.startsWith("runtime.receipt:") -> {
                    showRuntimeReceiptDialog(actionId.substringAfter("runtime.receipt:"))
                }
                actionId.startsWith("routing.target:") -> showControlCenterTarget(actionId.substringAfter("routing.target:"))
                actionId.startsWith("tool.detail:") -> showNativeToolDetailPage(actionId.substringAfter("tool.detail:"))
                actionId.startsWith("node.desktop:") -> showControlCenterDesktop(actionId.substringAfter("node.desktop:"))
                actionId.startsWith("ha.entity:") -> showHomeAssistantEntityDetailPage(actionId.substringAfter("ha.entity:"))
            }
        }
    }

    private fun openControlCenterDestination(
        destination: ControlCenterDestination,
        pushCurrent: Boolean = true
    ) {
        if (pushCurrent) {
            controlCenterDestination?.let(controlCenterBackStack::addLast)
        }
        controlCenterDestination = destination
        renderCurrentControlCenterDestination()
    }

    private fun renderCurrentControlCenterDestination() {
        val destination = controlCenterDestination ?: return
        renderingControlCenterDestination = true
        try {
            when (destination.route) {
                ControlCenterRoute.PROFILE -> renderControlCenterProfilePage()
                ControlCenterRoute.SYSTEM_STATUS -> renderControlCenterSystemStatusPage()
                ControlCenterRoute.GLOBAL_AGENT -> renderControlCenterGlobalAgentPage()
                ControlCenterRoute.AGENT_CORE -> renderControlCenterAgentCorePage()
                ControlCenterRoute.EXECUTION_POLICY -> renderControlCenterExecutionPolicyPage()
                ControlCenterRoute.RESOURCE_ROUTING -> renderControlCenterRoutingPage()
                ControlCenterRoute.MEMORY -> renderControlCenterMemoryPage()
                ControlCenterRoute.LEARNING -> renderControlCenterLearningPage()
                ControlCenterRoute.KNOWLEDGE -> showAgentKnowledgePage()
                ControlCenterRoute.MCP -> showCapabilityLibraryPage(
                    if (destination.payload == CAPABILITY_KIND_SKILL) AgentCapabilityCatalogKind.SKILL
                    else AgentCapabilityCatalogKind.MCP
                )
                ControlCenterRoute.TASKS -> showAgentRecentTasksPage()
                ControlCenterRoute.PHONE_CAPABILITIES -> renderControlCenterPhoneCapabilitiesPage()
                ControlCenterRoute.ON_DEVICE_RUNTIME -> renderControlCenterRuntimePage()
                ControlCenterRoute.SOFTWARE_CENTER -> renderControlCenterSoftwareCenterPage(destination.payload)
                ControlCenterRoute.APP_TOOLS -> renderControlCenterAppToolsPage()
                ControlCenterRoute.SMART_SPACES -> renderControlCenterSmartSpacesPage()
                ControlCenterRoute.NODES -> renderControlCenterNodesPage()
                ControlCenterRoute.SECURITY -> renderControlCenterSecurityPage()
                ControlCenterRoute.PERMISSIONS_AUDIT -> renderControlCenterPermissionsPage()
                ControlCenterRoute.VOICE -> renderControlCenterVoicePage()
                ControlCenterRoute.DATA_BACKUP -> renderControlCenterDataPage()
                ControlCenterRoute.GENERAL -> renderControlCenterGeneralPage()
                ControlCenterRoute.APP_SERVICES -> renderControlCenterAppServicesPage()
                ControlCenterRoute.ADVANCED -> renderControlCenterAdvancedPage()
                ControlCenterRoute.RESET -> renderControlCenterResetPage()
            }
        } finally {
            renderingControlCenterDestination = false
        }
        setFeatureBackAction()
    }

    private fun openExistingControlCenterPage(render: () -> Unit) {
        controlCenterDestination?.let(controlCenterBackStack::addLast)
        controlCenterDestination = null
        renderingControlCenterDestination = true
        try {
            render()
        } finally {
            renderingControlCenterDestination = false
        }
        setFeatureBackAction()
    }

    private fun navigateControlCenterBack() {
        if (controlCenterBackStack.isNotEmpty()) {
            openControlCenterDestination(controlCenterBackStack.removeLast(), pushCurrent = false)
        } else {
            controlCenterDestination = null
            hideFeaturePage()
            showMainTab(PAGE_SETTINGS)
        }
    }

    private fun exitControlCenterToTab(tab: String) {
        controlCenterDestination = null
        controlCenterBackStack.clear()
        showMainTab(tab)
    }

    private fun showControlCenterFeature(title: String, page: ControlCenterPageSpec) {
        showFeaturePage(title)
        controlCenterRenderer.render(featureContent, page, ::handleControlCenterAction)
    }

    private fun renderControlCenterGlobalAgentPage() {
        val runtime = if (::globalSuperAgentRuntime.isInitialized) {
            globalSuperAgentRuntime
        } else GlobalSuperAgentRuntime.get(this)
        val settings = runtime.settings()
        val modelBudget = runtime.modelCallBudgetSnapshot()
        val dashboard = runtime.dashboard()
        val continuity = runtime.continuitySnapshot()
        val research = runtime.researchTasks()
        val activeResearch = research.count {
            it.status in setOf(
                GlobalResearchTaskStatus.QUEUED,
                GlobalResearchTaskStatus.RUNNING,
                GlobalResearchTaskStatus.SCHEDULED,
                GlobalResearchTaskStatus.WAITING_FOR_RESOURCE
            )
        }
        val activeCognition = dashboard.queuedCognitionCount
        val activeRuns = dashboard.activeAutonomousRunCount
        showControlCenterFeature(
            getString(R.string.cc_global_agent_title),
            ControlCenterPageSpec(
                banner = if (dashboard.unresolvedConflictCount > 0) {
                    ControlCenterBannerSpec(
                        title = getString(R.string.cc_global_conflicts_banner, dashboard.unresolvedConflictCount),
                        subtitle = getString(R.string.cc_global_conflicts_banner_subtitle),
                        iconRes = R.drawable.ic_info_outline,
                        tone = ControlCenterTone.AMBER,
                        actionId = "global.world.conflicts"
                    )
                } else null,
                hero = ControlCenterHeroSpec(
                    title = getString(R.string.cc_global_agent_title),
                    subtitle = getString(R.string.cc_global_agent_subtitle),
                    iconRes = R.drawable.signalasi_mark_large,
                    preserveIconColor = true,
                    badges = listOf(
                        ControlCenterBadgeSpec(
                            getString(if (settings.enabled) R.string.cc_global_understanding_active else R.string.on_device_agent_status_paused),
                            if (settings.enabled) ControlCenterTone.GREEN else ControlCenterTone.AMBER
                        ),
                        ControlCenterBadgeSpec(
                            getString(if (settings.autonomousResearchEnabled) R.string.cc_global_research_active else R.string.common_off),
                            if (settings.autonomousResearchEnabled) ControlCenterTone.BLUE else ControlCenterTone.NEUTRAL
                        )
                    ),
                    metrics = listOf(
                        ControlCenterMetricSpec(dashboard.topicCount.toString(), getString(R.string.cc_global_metric_topics)),
                        ControlCenterMetricSpec(dashboard.crossConversationLinkCount.toString(), getString(R.string.cc_global_metric_links)),
                        ControlCenterMetricSpec(dashboard.pendingInsightCount.toString(), getString(R.string.cc_global_metric_insights))
                    )
                ),
                sections = listOf(
                    ControlCenterSectionSpec(
                        getString(R.string.cc_global_section_autonomy),
                        listOf(
                            ControlCenterRowSpec("global.toggle_enabled", getString(R.string.cc_global_master_title), getString(R.string.cc_global_master_subtitle), R.drawable.ic_agent_node, switchValue = settings.enabled, showChevron = false),
                            ControlCenterRowSpec("global.toggle_model_understanding", getString(R.string.cc_global_model_understanding_title), getString(R.string.cc_global_model_understanding_subtitle), R.drawable.ic_settings_model, switchValue = settings.modelUnderstandingEnabled, showChevron = false, enabled = settings.enabled),
                            ControlCenterRowSpec("global.toggle_autonomous_preparation", getString(R.string.cc_global_autonomous_preparation_title), getString(R.string.cc_global_autonomous_preparation_subtitle), R.drawable.ic_agent_control, switchValue = settings.autonomousPreparationEnabled, showChevron = false, enabled = settings.enabled),
                            ControlCenterRowSpec("global.toggle_autonomous_tools", getString(R.string.cc_global_autonomous_tools_title), getString(R.string.cc_global_autonomous_tools_subtitle), R.drawable.ic_agent_control, switchValue = settings.autonomousToolExecutionEnabled, showChevron = false, enabled = settings.enabled && settings.autonomousPreparationEnabled),
                            ControlCenterRowSpec("global.toggle_dynamic_replanning", getString(R.string.cc_global_dynamic_replanning_title), getString(R.string.cc_global_dynamic_replanning_subtitle), R.drawable.ic_reset_data, switchValue = settings.dynamicAutonomousReplanningEnabled, showChevron = false, enabled = settings.enabled && settings.autonomousPreparationEnabled),
                            ControlCenterRowSpec("global.toggle_long_horizon", getString(R.string.cc_global_long_horizon_toggle_title), getString(R.string.cc_global_long_horizon_toggle_subtitle), R.drawable.ic_agent_history, switchValue = settings.longHorizonPlanningEnabled, showChevron = false, enabled = settings.enabled),
                            ControlCenterRowSpec("global.toggle_discovery", getString(R.string.cc_global_discovery_title), getString(R.string.cc_global_discovery_subtitle), R.drawable.ic_tab_discover, switchValue = settings.proactiveDiscoveryEnabled, showChevron = false, enabled = settings.enabled && settings.modelUnderstandingEnabled),
                            ControlCenterRowSpec("global.toggle_proactive", getString(R.string.cc_global_proactive_title), getString(R.string.cc_global_proactive_subtitle), R.drawable.ic_agent_memory, switchValue = settings.proactiveInsightsEnabled, showChevron = false, enabled = settings.enabled),
                            ControlCenterRowSpec("global.toggle_learning", getString(R.string.cc_global_learning_toggle_title), getString(R.string.cc_global_learning_toggle_subtitle), R.drawable.ic_agent_skill, switchValue = settings.adaptiveLearningEnabled, showChevron = false, enabled = settings.enabled),
                            ControlCenterRowSpec("global.toggle_research", getString(R.string.cc_global_research_title), getString(R.string.cc_global_research_subtitle), R.drawable.ic_agent_knowledge, switchValue = settings.autonomousResearchEnabled, showChevron = false, enabled = settings.enabled),
                            ControlCenterRowSpec("global.toggle_auto_conversations", getString(R.string.cc_global_topics_title), getString(R.string.cc_global_topics_subtitle), R.drawable.ic_agent_history, switchValue = settings.autoCreateConversationsEnabled, showChevron = false, enabled = settings.enabled),
                            ControlCenterRowSpec("global.toggle_notifications", getString(R.string.cc_global_notifications_title), getString(R.string.cc_global_notifications_subtitle), R.drawable.ic_settings_notification, switchValue = settings.notificationsEnabled, showChevron = false, enabled = settings.enabled)
                        )
                    ),
                    ControlCenterSectionSpec(
                        getString(R.string.cc_global_section_world),
                        listOf(
                            ControlCenterRowSpec("global.world.goals", getString(R.string.cc_global_goals_title), getString(R.string.cc_global_goals_subtitle), R.drawable.ic_agent_node, dashboard.activeGoalCount.toString(), ControlCenterTone.VIOLET),
                            ControlCenterRowSpec("global.world.tasks", getString(R.string.cc_global_tasks_title), getString(R.string.cc_global_tasks_subtitle), R.drawable.ic_agent_history, dashboard.activeTaskCount.toString(), ControlCenterTone.BLUE),
                            ControlCenterRowSpec("global.world.conflicts", getString(R.string.cc_global_conflicts_title), getString(R.string.cc_global_conflicts_subtitle), R.drawable.ic_info_outline, dashboard.unresolvedConflictCount.toString(), if (dashboard.unresolvedConflictCount > 0) ControlCenterTone.AMBER else ControlCenterTone.NEUTRAL),
                            ControlCenterRowSpec("global.world.links", getString(R.string.cc_global_links_title), getString(R.string.cc_global_links_subtitle), R.drawable.ic_protocol_link, dashboard.crossConversationLinkCount.toString(), ControlCenterTone.GREEN)
                        )
                    ),
                    ControlCenterSectionSpec(
                        getString(R.string.cc_global_section_intelligence),
                        listOf(
                            ControlCenterRowSpec("global.cognition", getString(R.string.cc_global_cognition_queue_title), getString(R.string.cc_global_cognition_queue_subtitle), R.drawable.ic_settings_model, activeCognition.toString(), if (activeCognition > 0) ControlCenterTone.VIOLET else ControlCenterTone.NEUTRAL),
                            ControlCenterRowSpec("global.runs", getString(R.string.cc_global_runs_title), getString(R.string.cc_global_runs_subtitle), R.drawable.ic_agent_control, (activeRuns + dashboard.waitingConfirmationCount).toString(), if (activeRuns > 0) ControlCenterTone.GREEN else if (dashboard.waitingConfirmationCount > 0) ControlCenterTone.AMBER else ControlCenterTone.NEUTRAL),
                            ControlCenterRowSpec("global.long_horizon", getString(R.string.cc_global_long_horizon_title), getString(R.string.cc_global_long_horizon_subtitle), R.drawable.ic_agent_history, dashboard.longHorizonGoalCount.toString(), if (dashboard.blockedLongHorizonGoalCount > 0) ControlCenterTone.AMBER else if (dashboard.longHorizonGoalCount > 0) ControlCenterTone.VIOLET else ControlCenterTone.NEUTRAL),
                            ControlCenterRowSpec("global.research", getString(R.string.cc_global_research_queue_title), getString(R.string.cc_global_research_queue_subtitle), R.drawable.ic_agent_knowledge, activeResearch.toString(), if (activeResearch > 0) ControlCenterTone.BLUE else ControlCenterTone.NEUTRAL),
                            ControlCenterRowSpec("global.insights", getString(R.string.cc_global_pending_insights_title), getString(R.string.cc_global_pending_insights_subtitle), R.drawable.ic_agent_memory, dashboard.pendingInsightCount.toString(), if (dashboard.pendingInsightCount > 0) ControlCenterTone.VIOLET else ControlCenterTone.NEUTRAL),
                            ControlCenterRowSpec("global.learning", getString(R.string.cc_global_learning_title), getString(R.string.cc_global_learning_subtitle), R.drawable.ic_agent_skill, getString(R.string.cc_global_learning_status, dashboard.feedbackCount, dashboard.learnedTopicCount), if (dashboard.feedbackCount > 0) ControlCenterTone.GREEN else ControlCenterTone.NEUTRAL),
                            ControlCenterRowSpec(
                                "global.continuity",
                                getString(R.string.cc_global_continuity_title),
                                getString(
                                    R.string.cc_global_continuity_subtitle,
                                    continuity.pendingEventCount,
                                    continuity.retryingEvents.size,
                                    continuity.quarantinedEvents.size
                                ),
                                R.drawable.ic_security_shield,
                                getString(
                                    when {
                                        continuity.quarantinedEvents.isNotEmpty() -> R.string.cc_global_continuity_attention
                                        continuity.retryingEvents.isNotEmpty() || continuity.pendingEventCount > 0 -> R.string.cc_global_continuity_recovering
                                        else -> R.string.cc_global_continuity_healthy
                                    }
                                ),
                                when {
                                    continuity.quarantinedEvents.isNotEmpty() -> ControlCenterTone.AMBER
                                    continuity.retryingEvents.isNotEmpty() || continuity.pendingEventCount > 0 -> ControlCenterTone.BLUE
                                    else -> ControlCenterTone.GREEN
                                }
                            ),
                            ControlCenterRowSpec("global.process_now", getString(R.string.cc_global_process_now_title), getString(R.string.cc_global_process_now_subtitle), R.drawable.ic_reset_data, getString(R.string.cc_global_process_now_action), ControlCenterTone.GREEN, showChevron = false, enabled = settings.enabled)
                        )
                    ),
                    ControlCenterSectionSpec(
                        getString(R.string.cc_global_section_resources),
                        listOf(
                            ControlCenterRowSpec("global.toggle_battery_protection", getString(R.string.cc_global_battery_protection_title), getString(R.string.cc_global_battery_protection_subtitle), R.drawable.ic_resource_battery, switchValue = settings.protectBatteryForBackgroundWork, showChevron = false, enabled = settings.enabled),
                            ControlCenterRowSpec("global.toggle_metered_research", getString(R.string.cc_global_metered_research_title), getString(R.string.cc_global_metered_research_subtitle), R.drawable.ic_resource_network, switchValue = settings.allowMeteredBackgroundResearch, showChevron = false, enabled = settings.enabled && settings.autonomousResearchEnabled),
                            ControlCenterRowSpec(
                                "global.daily_model_calls",
                                getString(R.string.cc_global_daily_model_calls_title),
                                getString(R.string.cc_global_daily_model_calls_subtitle),
                                R.drawable.ic_settings_model,
                                getString(
                                    R.string.cc_global_daily_model_calls_status,
                                    modelBudget.dispatchesInWindow,
                                    modelBudget.dailyLimit
                                ),
                                if (modelBudget.dispatchesInWindow >= modelBudget.dailyLimit) ControlCenterTone.AMBER else ControlCenterTone.BLUE
                            ),
                            ControlCenterRowSpec(
                                "global.concurrent_model_calls",
                                getString(R.string.cc_global_concurrent_model_calls_title),
                                getString(R.string.cc_global_concurrent_model_calls_subtitle),
                                R.drawable.ic_agent_history,
                                getString(
                                    R.string.cc_global_concurrent_model_calls_status,
                                    modelBudget.activeCalls,
                                    modelBudget.concurrencyLimit
                                ),
                                if (modelBudget.activeCalls >= modelBudget.concurrencyLimit) ControlCenterTone.AMBER else ControlCenterTone.GREEN
                            ),
                            ControlCenterRowSpec(
                                "global.daily_model_tokens",
                                getString(R.string.cc_global_daily_model_tokens_title),
                                getString(R.string.cc_global_daily_model_tokens_subtitle),
                                R.drawable.ic_protocol_link,
                                getString(
                                    R.string.cc_global_daily_model_tokens_status,
                                    formatCompactCount(modelBudget.totalTokensInWindow),
                                    formatCompactCount(modelBudget.dailyTokenLimit)
                                ),
                                if (modelBudget.totalTokensInWindow >= modelBudget.dailyTokenLimit) ControlCenterTone.AMBER else ControlCenterTone.VIOLET
                            ),
                            ControlCenterRowSpec(
                                "global.daily_reported_cost",
                                getString(R.string.cc_global_daily_reported_cost_title),
                                getString(
                                    R.string.cc_global_daily_reported_cost_subtitle,
                                    modelBudget.unpricedDispatches
                                ),
                                R.drawable.ic_security_shield,
                                getString(
                                    R.string.cc_global_daily_reported_cost_status,
                                    formatUsdMicros(modelBudget.reportedCostMicrosInWindow),
                                    formatUsdMicros(modelBudget.dailyReportedCostLimitMicros)
                                ),
                                if (modelBudget.dailyReportedCostLimitMicros > 0L &&
                                    modelBudget.reportedCostMicrosInWindow >= modelBudget.dailyReportedCostLimitMicros
                                ) ControlCenterTone.AMBER else ControlCenterTone.BLUE
                            )
                        )
                    ),
                    ControlCenterSectionSpec(
                        getString(R.string.cc_global_section_privacy),
                        listOf(
                            ControlCenterRowSpec("global.toggle_cloud_cognition", getString(R.string.cc_global_cloud_cognition_title), getString(R.string.cc_global_cloud_cognition_subtitle), R.drawable.ic_security_shield, switchValue = settings.allowCloudCognition, showChevron = false, enabled = settings.enabled && settings.modelUnderstandingEnabled),
                            ControlCenterRowSpec("apps.chat_history", getString(R.string.cc_global_sessions_title), getString(R.string.cc_global_sessions_subtitle), R.drawable.ic_agent_history, "", ControlCenterTone.NEUTRAL)
                        )
                    )
                ),
                footer = getString(R.string.cc_global_footer)
            )
        )
    }

    private fun updateGlobalAgentSettings(transform: (GlobalAgentSettings) -> GlobalAgentSettings) {
        val runtime = if (::globalSuperAgentRuntime.isInitialized) globalSuperAgentRuntime else GlobalSuperAgentRuntime.get(this)
        runtime.updateSettings(transform)
        renderControlCenterGlobalAgentPage()
    }

    private fun showGlobalDailyModelCallBudgetDialog() {
        val runtime = if (::globalSuperAgentRuntime.isInitialized) globalSuperAgentRuntime else GlobalSuperAgentRuntime.get(this)
        val values = intArrayOf(12, 24, 48, 96, 200)
        val current = runtime.settings().dailyBackgroundModelCallBudget
        val selected = values.indices.minByOrNull { kotlin.math.abs(values[it] - current) } ?: 0
        val labels = values.map { getString(R.string.cc_global_calls_per_day, it) }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle(R.string.cc_global_daily_model_calls_title)
            .setSingleChoiceItems(labels, selected) { dialog, index ->
                updateGlobalAgentSettings { it.copy(dailyBackgroundModelCallBudget = values[index]) }
                dialog.dismiss()
            }
            .setNegativeButton(R.string.common_cancel, null)
            .show()
    }

    private fun showGlobalConcurrentModelCallBudgetDialog() {
        val runtime = if (::globalSuperAgentRuntime.isInitialized) globalSuperAgentRuntime else GlobalSuperAgentRuntime.get(this)
        val values = intArrayOf(1, 2, 3, 4, 5, 6)
        val current = runtime.settings().maxConcurrentBackgroundModelCalls
        val selected = values.indexOf(current).coerceAtLeast(0)
        val labels = values.map { getString(R.string.cc_global_concurrent_calls, it) }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle(R.string.cc_global_concurrent_model_calls_title)
            .setSingleChoiceItems(labels, selected) { dialog, index ->
                updateGlobalAgentSettings { it.copy(maxConcurrentBackgroundModelCalls = values[index]) }
                dialog.dismiss()
            }
            .setNegativeButton(R.string.common_cancel, null)
            .show()
    }

    private fun showGlobalDailyModelTokenBudgetDialog() {
        val runtime = if (::globalSuperAgentRuntime.isInitialized) globalSuperAgentRuntime else GlobalSuperAgentRuntime.get(this)
        val values = longArrayOf(50_000L, 100_000L, 250_000L, 500_000L, 1_000_000L, 2_000_000L)
        val current = runtime.settings().dailyBackgroundTokenBudget
        val selected = values.indices.minByOrNull { kotlin.math.abs(values[it] - current) } ?: 0
        val labels = values.map { getString(R.string.cc_global_tokens_per_day, formatCompactCount(it)) }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle(R.string.cc_global_daily_model_tokens_title)
            .setSingleChoiceItems(labels, selected) { dialog, index ->
                updateGlobalAgentSettings { it.copy(dailyBackgroundTokenBudget = values[index]) }
                dialog.dismiss()
            }
            .setNegativeButton(R.string.common_cancel, null)
            .show()
    }

    private fun showGlobalDailyReportedCostBudgetDialog() {
        val runtime = if (::globalSuperAgentRuntime.isInitialized) globalSuperAgentRuntime else GlobalSuperAgentRuntime.get(this)
        val values = longArrayOf(250_000L, 500_000L, 1_000_000L, 2_000_000L, 5_000_000L, 10_000_000L)
        val current = runtime.settings().dailyBackgroundReportedCostBudgetMicros
        val selected = values.indices.minByOrNull { kotlin.math.abs(values[it] - current) } ?: 0
        val labels = values.map(::formatUsdMicros).toTypedArray()
        AlertDialog.Builder(this)
            .setTitle(R.string.cc_global_daily_reported_cost_title)
            .setSingleChoiceItems(labels, selected) { dialog, index ->
                updateGlobalAgentSettings { it.copy(dailyBackgroundReportedCostBudgetMicros = values[index]) }
                dialog.dismiss()
            }
            .setNegativeButton(R.string.common_cancel, null)
            .show()
    }

    private fun formatCompactCount(value: Long): String = when {
        value >= 1_000_000L -> String.format(Locale.US, "%.1fM", value / 1_000_000.0).replace(".0M", "M")
        value >= 1_000L -> String.format(Locale.US, "%.1fK", value / 1_000.0).replace(".0K", "K")
        else -> value.toString()
    }

    private fun formatUsdMicros(value: Long): String = String.format(Locale.US, "$%.2f", value.coerceAtLeast(0L) / 1_000_000.0)

    private fun processGlobalAgentNow() {
        val runtime = if (::globalSuperAgentRuntime.isInitialized) globalSuperAgentRuntime else GlobalSuperAgentRuntime.get(this)
        thread(name = "signalasi-global-agent-manual") {
            val batch = runCatching { runtime.processPending(250) }.getOrNull()
            runCatching { runtime.processLongHorizonCycle() }
            runCatching { runtime.processProactiveDiscoveryCycle(force = true) }
            repeat(2) {
                runCatching { runtime.executeCognitionCycle(explicitUserOverride = true) }
                runCatching { runtime.executeAutonomousCycle(explicitUserOverride = true) }
                runCatching { runtime.executeResearchCycle(explicitUserOverride = true) }
            }
            runCatching { runtime.processPending(250) }
            runCatching { runtime.processLongHorizonCycle() }
            runCatching { runtime.scheduleNextWake() }
            runtime.deliverPending(agentTranscriptStore).let { delivered ->
                if (delivered.isNotEmpty()) {
                    runtime.markNotified(delivered.map(GlobalProactiveMessage::id).toSet())
                }
            }
            runOnUiThread {
                renderAgentTranscript(agentTranscriptStore.list())
                if (controlCenterDestination?.route == ControlCenterRoute.GLOBAL_AGENT) {
                    renderControlCenterGlobalAgentPage()
                }
                Toast.makeText(
                    this,
                    getString(R.string.cc_global_processed_result, batch?.processedEventCount ?: 0),
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    private fun showGlobalWorldItemsDialog(kind: GlobalWorldItemKind) {
        val items = globalSuperAgentRuntime.worldSnapshot().items
            .filter { it.kind == kind && it.status in setOf(GlobalWorldItemStatus.ACTIVE, GlobalWorldItemStatus.CONFLICTED) }
            .sortedByDescending(GlobalWorldItem::lastSeenAtMillis)
            .take(30)
        val title = getString(if (kind == GlobalWorldItemKind.GOAL) R.string.cc_global_goals_title else R.string.cc_global_tasks_title)
        val message = items.takeIf(List<GlobalWorldItem>::isNotEmpty)?.joinToString("\n\n") {
            "\u2022 ${it.value}\n${it.topic} \u00b7 ${it.conversationIds.size}"
        } ?: getString(R.string.cc_global_empty)
        AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun showGlobalWorldConflictsDialog() {
        val conflicts = globalSuperAgentRuntime.worldSnapshot().items
            .filter { it.status == GlobalWorldItemStatus.CONFLICTED }
            .groupBy { it.conflictGroupId.ifBlank { it.stableKey } }
            .values
            .take(20)
        val message = conflicts.takeIf(Collection<List<GlobalWorldItem>>::isNotEmpty)?.joinToString("\n\n") { group ->
            group.joinToString("\n") { "\u2022 ${it.value}" }
        } ?: getString(R.string.cc_global_empty)
        AlertDialog.Builder(this)
            .setTitle(R.string.cc_global_conflicts_title)
            .setMessage(message)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun showGlobalConversationLinksDialog() {
        val graph = globalSuperAgentRuntime.topicGraphSnapshot()
        val nodesById = graph.nodes.associateBy(GlobalTopicNode::id)
        val nodeLines = graph.activeNodes()
            .sortedWith(compareByDescending<GlobalTopicNode> { it.kind == GlobalTopicNodeKind.PROJECT }
                .thenByDescending { it.lastSeenAtMillis })
            .take(20)
            .map { node ->
                val kind = getString(if (node.kind == GlobalTopicNodeKind.PROJECT) {
                    R.string.cc_global_topic_kind_project
                } else R.string.cc_global_topic_kind_topic)
                "\u2022 $kind \u00b7 ${node.name}\n${node.conversationIds.size} \u00b7 ${(node.confidence * 100).toInt()}%"
            }
        val relationLines = graph.relations
            .sortedByDescending(GlobalTopicRelation::strength)
            .take(20)
            .mapNotNull { relation ->
                val from = nodesById[relation.fromNodeId]?.name ?: return@mapNotNull null
                val to = nodesById[relation.toNodeId]?.name ?: return@mapNotNull null
                "$from ${globalTopicRelationLabel(relation.kind)} $to \u00b7 ${(relation.strength * 100).toInt()}%"
            }
        val message = (nodeLines + relationLines).takeIf(List<String>::isNotEmpty)
            ?.joinToString("\n\n") ?: getString(R.string.cc_global_empty)
        AlertDialog.Builder(this)
            .setTitle(R.string.cc_global_links_title)
            .setMessage(message)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun showGlobalResearchTasksDialog() {
        val tasks = globalSuperAgentRuntime.researchTasks()
            .sortedByDescending(GlobalResearchTask::updatedAtMillis)
            .take(30)
        val message = tasks.takeIf(List<GlobalResearchTask>::isNotEmpty)?.joinToString("\n\n") {
            val plan = it.researchPlan
            val progress = if (plan.units.isNotEmpty()) {
                "\n" + getString(
                    R.string.cc_global_research_progress,
                    plan.completedUnits().size,
                    plan.units.size,
                    globalResearchPlanPhaseLabel(plan.phase),
                    it.evidenceLedger.independentSourceCount,
                    (it.evidenceLedger.overallConfidence * 100).toInt()
                ) + if (it.evidenceLedger.verified) {
                    " \u00b7 ${getString(R.string.cc_global_research_verified)}"
                } else ""
            } else ""
            "\u2022 ${it.topic}\n${globalResearchStatusLabel(it.status)}$progress" +
                it.lastError.takeIf(String::isNotBlank)?.let { error -> "\n${error.take(120)}" }.orEmpty()
        } ?: getString(R.string.cc_global_empty)
        AlertDialog.Builder(this)
            .setTitle(R.string.cc_global_research_queue_title)
            .setMessage(message)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun globalResearchPlanPhaseLabel(phase: GlobalResearchPlanPhase): String = getString(when (phase) {
        GlobalResearchPlanPhase.UNPLANNED -> R.string.cc_global_research_phase_unplanned
        GlobalResearchPlanPhase.COLLECTING -> R.string.cc_global_research_phase_collecting
        GlobalResearchPlanPhase.SYNTHESIS_PENDING -> R.string.cc_global_research_phase_synthesis_pending
        GlobalResearchPlanPhase.SYNTHESIZING -> R.string.cc_global_research_phase_synthesizing
        GlobalResearchPlanPhase.COMPLETED -> R.string.cc_global_status_completed
    })

    private fun showGlobalCognitionTasksDialog() {
        val tasks = globalSuperAgentRuntime.cognitionTasks()
            .sortedByDescending(GlobalCognitionTask::updatedAtMillis)
            .take(30)
        val message = tasks.takeIf(List<GlobalCognitionTask>::isNotEmpty)?.joinToString("\n\n") { task ->
            val topic = task.result.topic.ifBlank { task.baselineUnderstanding.topic }
            buildString {
                append("\u2022 ").append(topic)
                append("\n").append(globalCognitionStatusLabel(task.status))
                if (task.resourceId.isNotBlank()) append(" \u00b7 ").append(task.resourceId)
                if (task.result.confidence > 0.0) {
                    append(" \u00b7 ").append((task.result.confidence * 100).toInt()).append('%')
                }
                task.lastError.takeIf(String::isNotBlank)?.let { append("\n").append(it.take(160)) }
            }
        } ?: getString(R.string.cc_global_empty)
        AlertDialog.Builder(this)
            .setTitle(R.string.cc_global_cognition_queue_title)
            .setMessage(message)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun showGlobalContinuityDialog() {
        val runtime = if (::globalSuperAgentRuntime.isInitialized) {
            globalSuperAgentRuntime
        } else GlobalSuperAgentRuntime.get(this)
        val snapshot = runtime.continuitySnapshot()
        val details = buildString {
            append(getString(
                R.string.cc_global_continuity_dialog_summary,
                snapshot.pendingEventCount,
                snapshot.retryingEvents.size,
                snapshot.quarantinedEvents.size
            ))
            if (snapshot.retryingEvents.isNotEmpty()) {
                append("\n\n").append(getString(
                    R.string.cc_global_continuity_retrying_detail,
                    snapshot.retryingEvents.maxOf(GlobalEventProcessingFailure::attemptCount)
                ))
            }
            if (snapshot.nextRetryAtMillis > System.currentTimeMillis()) {
                append("\n").append(getString(
                    R.string.cc_global_continuity_next_retry,
                    SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(snapshot.nextRetryAtMillis))
                ))
            }
            if (snapshot.quarantinedEvents.isNotEmpty()) {
                append("\n\n").append(getString(R.string.cc_global_continuity_isolated_detail))
            } else if (snapshot.pendingEventCount == 0 && snapshot.retryingEvents.isEmpty()) {
                append("\n\n").append(getString(R.string.cc_global_continuity_healthy_detail))
            }
        }
        val builder = AlertDialog.Builder(this)
            .setTitle(R.string.cc_global_continuity_title)
            .setMessage(details)
            .setPositiveButton(android.R.string.ok, null)
        if (snapshot.quarantinedEvents.isNotEmpty()) {
            builder.setNeutralButton(R.string.cc_global_continuity_retry_action) { _, _ ->
                thread(name = "signalasi-global-continuity-replay") {
                    val replayed = runtime.replayQuarantinedEvents()
                    runOnUiThread {
                        Toast.makeText(
                            this,
                            getString(R.string.cc_global_continuity_retry_result, replayed),
                            Toast.LENGTH_SHORT
                        ).show()
                        if (controlCenterDestination?.route == ControlCenterRoute.GLOBAL_AGENT) {
                            renderControlCenterGlobalAgentPage()
                        }
                    }
                }
            }
        }
        builder.show()
    }

    private fun showGlobalAutonomousRunsDialog() {
        val runs = globalSuperAgentRuntime.autonomousRuns()
            .sortedByDescending(GlobalAutonomousRun::updatedAtMillis)
            .take(30)
        if (runs.isEmpty()) {
            AlertDialog.Builder(this)
                .setTitle(R.string.cc_global_runs_title)
                .setMessage(R.string.cc_global_empty)
                .setPositiveButton(android.R.string.ok, null)
                .show()
            return
        }
        val labels = runs.map { run ->
            getString(
                R.string.cc_global_run_row,
                run.topic.ifBlank { run.goal.take(80) },
                globalAutonomousRunStatusLabel(run.status),
                run.completedActions().size,
                run.actions.size
            )
        }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle(R.string.cc_global_runs_title)
            .setItems(labels) { _, index -> showGlobalAutonomousRunDialog(runs[index]) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showGlobalAutonomousRunDialog(run: GlobalAutonomousRun) {
        val details = run.actions.joinToString("\n\n") { action ->
            "\u2022 ${action.goal}\n${globalAutonomousActionStatusLabel(action.status)}" +
                action.dependsOnActionIds.takeIf(Set<String>::isNotEmpty)?.let {
                    " \u00b7 ${getString(R.string.cc_global_dependency_count, it.size)}"
                }.orEmpty() +
                action.toolId.takeIf(String::isNotBlank)?.let {
                    "\n${getString(R.string.cc_global_tool_label, it)}"
                }.orEmpty() +
                action.toolInputJson.takeIf(String::isNotBlank)?.let {
                    "\n${getString(R.string.cc_global_tool_input_label, it.take(320))}"
                }.orEmpty() +
                "\n${getString(R.string.cc_global_verification_label, globalActionVerificationLabel(action.verificationStatus))}" +
                action.result.takeIf(String::isNotBlank)?.let { "\n${it.take(300)}" }.orEmpty() +
                action.evidence.takeIf(List<GlobalActionEvidence>::isNotEmpty)?.let { evidence ->
                    "\n" + evidence.take(3).joinToString("\n") { "${it.kind.name.lowercase()}: ${it.summary.take(180)}" }
                }.orEmpty() +
                action.lastError.takeIf(String::isNotBlank)?.let { "\n${it.take(160)}" }.orEmpty()
        }
        val builder = AlertDialog.Builder(this)
            .setTitle(run.topic.ifBlank { getString(R.string.cc_global_runs_title) })
            .setMessage(details)
            .setNegativeButton(android.R.string.cancel, null)
        if (run.status == GlobalAutonomousRunStatus.WAITING_CONFIRMATION) {
            builder.setNeutralButton(R.string.common_reject) { _, _ ->
                globalSuperAgentRuntime.rejectAutonomousRun(run.id)
                renderControlCenterGlobalAgentPage()
            }
            builder.setPositiveButton(R.string.common_confirm) { _, _ ->
                globalSuperAgentRuntime.approveAutonomousRun(run.id)
                processGlobalAgentNow()
            }
        } else {
            builder.setPositiveButton(android.R.string.ok, null)
        }
        builder.show()
    }

    private fun showGlobalLongHorizonGoalsDialog() {
        val goals = globalSuperAgentRuntime.longHorizonGoals()
            .sortedWith(compareByDescending<GlobalLongHorizonGoal> { it.priority }
                .thenByDescending { it.updatedAtMillis })
            .take(50)
        if (goals.isEmpty()) {
            AlertDialog.Builder(this)
                .setTitle(R.string.cc_global_long_horizon_title)
                .setMessage(R.string.cc_global_empty)
                .setPositiveButton(android.R.string.ok, null)
                .show()
            return
        }
        val labels = goals.map { goal ->
            getString(
                R.string.cc_global_long_horizon_row,
                goal.title,
                globalLongHorizonStatusLabel(goal.status),
                goal.checkpointCount
            )
        }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle(R.string.cc_global_long_horizon_title)
            .setItems(labels) { _, index -> showGlobalLongHorizonGoalDialog(goals[index]) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showGlobalLongHorizonGoalDialog(goal: GlobalLongHorizonGoal) {
        val details = buildString {
            append(globalLongHorizonStatusLabel(goal.status))
            append(" \u00b7 ").append((goal.priority * 100).toInt()).append('%')
            if (goal.progressSummary.isNotBlank()) append("\n\n").append(goal.progressSummary.take(1_000))
            if (goal.blocker.isNotBlank()) append("\n\n").append(goal.blocker.take(600))
            if (goal.dependencyGoalIds.isNotEmpty()) {
                append("\n\n").append(getString(R.string.cc_global_dependency_count, goal.dependencyGoalIds.size))
            }
            if (goal.verificationSummary.isNotBlank()) {
                append("\n\n").append(goal.verificationSummary.take(1_000))
            }
            if (goal.nextCheckAtMillis > 0L) {
                append("\n\n")
                append(SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(goal.nextCheckAtMillis)))
            }
        }
        val builder = AlertDialog.Builder(this)
            .setTitle(goal.title)
            .setMessage(details)
            .setNegativeButton(android.R.string.cancel, null)
        if (goal.status == GlobalLongHorizonGoalStatus.PAUSED) {
            builder.setPositiveButton(R.string.cc_global_goal_resume) { _, _ ->
                globalSuperAgentRuntime.resumeLongHorizonGoal(goal.id)
                processGlobalAgentNow()
            }
        } else if (goal.status != GlobalLongHorizonGoalStatus.COMPLETED) {
            builder.setNeutralButton(R.string.cc_global_goal_pause) { _, _ ->
                globalSuperAgentRuntime.pauseLongHorizonGoal(goal.id)
                renderControlCenterGlobalAgentPage()
            }
            builder.setPositiveButton(android.R.string.ok, null)
        } else {
            builder.setPositiveButton(android.R.string.ok, null)
        }
        builder.show()
    }

    private fun globalCognitionStatusLabel(status: GlobalCognitionTaskStatus): String = getString(when (status) {
        GlobalCognitionTaskStatus.QUEUED -> R.string.cc_global_status_queued
        GlobalCognitionTaskStatus.RUNNING -> R.string.cc_global_status_running
        GlobalCognitionTaskStatus.WAITING_FOR_RESOURCE -> R.string.cc_global_status_waiting
        GlobalCognitionTaskStatus.COMPLETED -> R.string.cc_global_status_completed
        GlobalCognitionTaskStatus.FAILED -> R.string.cc_global_status_failed
    })

    private fun globalAutonomousRunStatusLabel(status: GlobalAutonomousRunStatus): String = getString(when (status) {
        GlobalAutonomousRunStatus.QUEUED -> R.string.cc_global_status_queued
        GlobalAutonomousRunStatus.RUNNING -> R.string.cc_global_status_running
        GlobalAutonomousRunStatus.REPLANNING -> R.string.cc_global_status_replanning
        GlobalAutonomousRunStatus.WAITING_FOR_RESOURCE -> R.string.cc_global_status_waiting
        GlobalAutonomousRunStatus.WAITING_CONFIRMATION -> R.string.cc_global_status_confirmation
        GlobalAutonomousRunStatus.COMPLETED -> R.string.cc_global_status_completed
        GlobalAutonomousRunStatus.PARTIAL -> R.string.cc_global_status_partial
        GlobalAutonomousRunStatus.FAILED -> R.string.cc_global_status_failed
        GlobalAutonomousRunStatus.PAUSED -> R.string.on_device_agent_status_paused
    })

    private fun globalLongHorizonStatusLabel(status: GlobalLongHorizonGoalStatus): String = getString(when (status) {
        GlobalLongHorizonGoalStatus.ACTIVE -> R.string.cc_global_status_active
        GlobalLongHorizonGoalStatus.IN_PROGRESS -> R.string.cc_global_status_in_progress
        GlobalLongHorizonGoalStatus.WAITING_DEPENDENCY -> R.string.cc_global_status_waiting_dependency
        GlobalLongHorizonGoalStatus.WAITING_CONFIRMATION -> R.string.cc_global_status_confirmation
        GlobalLongHorizonGoalStatus.BLOCKED -> R.string.cc_global_status_blocked
        GlobalLongHorizonGoalStatus.COMPLETED -> R.string.cc_global_status_completed
        GlobalLongHorizonGoalStatus.PAUSED -> R.string.on_device_agent_status_paused
    })

    private fun globalAutonomousActionStatusLabel(status: GlobalAutonomousActionStatus): String = getString(when (status) {
        GlobalAutonomousActionStatus.PENDING -> R.string.cc_global_status_queued
        GlobalAutonomousActionStatus.RUNNING -> R.string.cc_global_status_running
        GlobalAutonomousActionStatus.WAITING_CONFIRMATION -> R.string.cc_global_status_confirmation
        GlobalAutonomousActionStatus.COMPLETED -> R.string.cc_global_status_completed
        GlobalAutonomousActionStatus.FAILED -> R.string.cc_global_status_failed
        GlobalAutonomousActionStatus.SKIPPED -> R.string.cc_global_status_skipped
    })

    private fun globalActionVerificationLabel(status: GlobalActionVerificationStatus): String = getString(when (status) {
        GlobalActionVerificationStatus.PENDING -> R.string.cc_global_verification_pending
        GlobalActionVerificationStatus.SUPPORTED -> R.string.cc_global_verification_supported
        GlobalActionVerificationStatus.VERIFIED -> R.string.cc_global_verification_verified
        GlobalActionVerificationStatus.INSUFFICIENT -> R.string.cc_global_verification_insufficient
        GlobalActionVerificationStatus.CONTESTED -> R.string.cc_global_verification_contested
    })

    private fun globalTopicRelationLabel(kind: GlobalTopicRelationKind): String = getString(when (kind) {
        GlobalTopicRelationKind.CONTAINS -> R.string.cc_global_relation_contains
        GlobalTopicRelationKind.RELATED_TO -> R.string.cc_global_relation_related
        GlobalTopicRelationKind.SUPPORTS -> R.string.cc_global_relation_supports
        GlobalTopicRelationKind.CONFLICTS_WITH -> R.string.cc_global_relation_conflicts
    })

    private fun showGlobalInsightsDialog() {
        val runtime = if (::globalSuperAgentRuntime.isInitialized) {
            globalSuperAgentRuntime
        } else GlobalSuperAgentRuntime.get(this)
        val items = runtime.proactiveInboxItems(limit = 40)
        if (items.isEmpty()) {
            AlertDialog.Builder(this)
                .setTitle(R.string.agent_global_insights_title)
                .setMessage(R.string.agent_global_insights_empty)
                .setPositiveButton(android.R.string.ok, null)
                .show()
            refreshGlobalInsightIndicator()
            return
        }

        runtime.markProactiveInboxViewed(items.flatMapTo(linkedSetOf(), GlobalProactiveInboxItem::messageIds))
        refreshGlobalInsightIndicator()
        val conversations = agentTranscriptStore.conversations(includeArchived = true).associateBy(AgentConversation::id)
        val list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(4), dp(16), dp(8))
        }
        val scroll = ScrollView(this).apply {
            isFillViewport = false
            overScrollMode = View.OVER_SCROLL_NEVER
            addView(list, ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ))
        }
        var dialog: AlertDialog? = null
        items.forEach { item ->
            val sourceTitle = conversations[item.sourceConversationId]?.let(::agentConversationDisplayTitle)
                ?: item.topic.ifBlank { getString(R.string.app_name) }
            val targetLabel = getString(when (item.target) {
                GlobalProactiveTarget.CURRENT_CONVERSATION -> R.string.agent_global_insight_current_topic
                GlobalProactiveTarget.NEW_CONVERSATION -> R.string.agent_global_insight_new_topic
                GlobalProactiveTarget.GLOBAL_DIGEST -> R.string.agent_global_insight_digest
            })
            val metadata = buildString {
                append(targetLabel)
                if (item.urgent) append(" \u00b7 ").append(getString(R.string.agent_global_insight_urgent))
                append(" \u00b7 ").append(getString(R.string.agent_global_insight_source, sourceTitle))
                if (item.deliveredAtMillis > 0L) {
                    append(" \u00b7 ")
                    append(SimpleDateFormat("MMM d, HH:mm", Locale.getDefault()).format(Date(item.deliveredAtMillis)))
                }
            }
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(13), dp(12), dp(13), dp(12))
                background = GradientDrawable().apply {
                    cornerRadius = dp(8).toFloat()
                    setColor(getColor(R.color.surface_bg))
                    setStroke(dp(1), getColor(R.color.separator))
                }
            }
            card.addView(TextView(this).apply {
                text = metadata
                setTextColor(getColor(R.color.text_secondary))
                textSize = 11f
                maxLines = 2
            })
            card.addView(TextView(this).apply {
                text = item.title.ifBlank { getString(R.string.agent_global_insights_title) }
                setTextColor(getColor(R.color.text_primary))
                textSize = 15f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                setPadding(0, dp(7), 0, 0)
            })
            card.addView(TextView(this).apply {
                text = item.content
                setTextColor(getColor(R.color.text_primary))
                textSize = 14f
                setLineSpacing(dp(3).toFloat(), 1f)
                setTextIsSelectable(true)
                setPadding(0, dp(6), 0, dp(8))
            })
            if (item.destinationConversationId.isNotBlank()) {
                card.addView(globalInsightActionButton(
                    label = getString(R.string.agent_global_insight_open_topic),
                    emphasized = true
                ) {
                    dialog?.dismiss()
                    openGlobalInsightTopic(item)
                }, LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    dp(40)
                ))
            }
            card.addView(TextView(this).apply {
                text = getString(R.string.agent_global_insight_feedback_hint)
                setTextColor(getColor(R.color.text_secondary))
                textSize = 11f
                setPadding(0, dp(9), 0, dp(5))
            })
            val feedbackRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            listOf(
                R.string.agent_global_feedback_helpful to GlobalAgentFeedbackKind.HELPFUL,
                R.string.agent_global_feedback_not_relevant to GlobalAgentFeedbackKind.NOT_RELEVANT,
                R.string.agent_global_feedback_too_frequent to GlobalAgentFeedbackKind.TOO_FREQUENT
            ).forEachIndexed { index, (labelId, kind) ->
                feedbackRow.addView(globalInsightActionButton(
                    label = getString(labelId),
                    emphasized = item.feedbackKind == kind
                ) {
                    if (recordGlobalInsightFeedback(item.key, kind)) dialog?.dismiss()
                }, LinearLayout.LayoutParams(0, dp(40), 1f).apply {
                    if (index > 0) leftMargin = dp(6)
                })
            }
            card.addView(feedbackRow)
            list.addView(card, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(10) })
        }
        dialog = AlertDialog.Builder(this)
            .setTitle(R.string.agent_global_insights_title)
            .setView(scroll)
            .setNegativeButton(R.string.common_close, null)
            .create()
        dialog.show()
        dialog.window?.setLayout(
            (resources.displayMetrics.widthPixels * 0.94f).toInt(),
            (resources.displayMetrics.heightPixels * 0.80f).toInt()
        )
    }

    private fun showGlobalPendingInsightsDialog() {
        val messages = globalSuperAgentRuntime.pendingProactiveMessages().takeLast(30)
        val message = messages.takeIf(List<GlobalProactiveMessage>::isNotEmpty)?.joinToString("\n\n") {
            "\u2022 ${it.title}\n${it.content.take(240)}"
        } ?: getString(R.string.cc_global_empty)
        AlertDialog.Builder(this)
            .setTitle(R.string.cc_global_pending_insights_title)
            .setMessage(message)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun globalInsightActionButton(
        label: String,
        emphasized: Boolean,
        onClick: () -> Unit
    ): TextView = TextView(this).apply {
        text = label
        gravity = Gravity.CENTER
        maxLines = 2
        textSize = 12f
        setTextColor(getColor(if (emphasized) R.color.agent_insight_text else R.color.text_primary))
        background = GradientDrawable().apply {
            cornerRadius = dp(7).toFloat()
            setColor(getColor(if (emphasized) R.color.agent_insight_bg else R.color.page_bg))
            setStroke(dp(1), getColor(if (emphasized) R.color.agent_insight_stroke else R.color.separator))
        }
        setOnClickListener { onClick() }
    }

    private fun openGlobalInsightTopic(item: GlobalProactiveInboxItem) {
        openAgentConversation(item.destinationConversationId)
    }

    private fun openAgentConversation(conversationId: String) {
        val destination = agentTranscriptStore.resolveMergedConversationId(conversationId) ?: return
        agentTranscriptStore.conversation(destination)?.takeIf {
            it.status == AgentConversationStatus.ARCHIVED
        }?.let { agentTranscriptStore.restoreConversation(destination) }
        if (!agentTranscriptStore.switchConversation(destination)) return
        resetAgentTranscriptRendering(destination)
        showMainTab(PAGE_AGENT)
        refreshAgentConversationHeader()
        renderAgentTranscript(agentTranscriptStore.list(destination))
        refreshGlobalInsightIndicator()
    }

    private fun refreshGlobalInsightIndicator(countOverride: Int? = null) {
        if (!::agentInsightBar.isInitialized || !::agentInsightText.isInitialized) return
        val runtime = if (::globalSuperAgentRuntime.isInitialized) {
            globalSuperAgentRuntime
        } else return
        val count = countOverride ?: runtime.newProactiveInsightCount()
        agentInsightBar.visibility = if (count > 0) View.VISIBLE else View.GONE
        if (count > 0) {
            agentInsightText.text = resources.getQuantityString(R.plurals.agent_global_new_insights, count, count)
        }
    }

    private fun showGlobalLearningDialog() {
        val runtime = if (::globalSuperAgentRuntime.isInitialized) {
            globalSuperAgentRuntime
        } else GlobalSuperAgentRuntime.get(this)
        val profile = runtime.adaptiveProfile()
        val topicSummary = profile.topicAffinity.entries
            .sortedByDescending { kotlin.math.abs(it.value) }
            .take(8)
            .joinToString("\n") { (topic, affinity) ->
                "\u2022 $topic \u00b7 ${if (affinity >= 0.0) "+" else ""}${(affinity * 100).toInt()}%"
            }
        val message = buildString {
            append(getString(
                R.string.cc_global_learning_summary,
                profile.sampleCount,
                profile.helpfulCount,
                profile.notRelevantCount,
                profile.tooFrequentCount
            ))
            if (topicSummary.isNotBlank()) {
                append("\n\n")
                append(getString(R.string.cc_global_learning_topics))
                append("\n")
                append(topicSummary)
            }
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.cc_global_learning_title)
            .setMessage(message)
            .setPositiveButton(android.R.string.ok, null)
            .setNeutralButton(R.string.cc_global_learning_reset) { _, _ ->
                AlertDialog.Builder(this)
                    .setTitle(R.string.cc_global_learning_reset)
                    .setMessage(R.string.cc_global_learning_reset_confirm)
                    .setNegativeButton(android.R.string.cancel, null)
                    .setPositiveButton(R.string.cc_global_learning_reset) { _, _ ->
                        runtime.clearAdaptiveFeedback()
                        renderControlCenterGlobalAgentPage()
                    }
                    .show()
            }
            .show()
    }

    private fun globalResearchStatusLabel(status: GlobalResearchTaskStatus): String = getString(when (status) {
        GlobalResearchTaskStatus.QUEUED -> R.string.cc_global_status_queued
        GlobalResearchTaskStatus.RUNNING -> R.string.cc_global_status_running
        GlobalResearchTaskStatus.SCHEDULED -> R.string.cc_global_status_scheduled
        GlobalResearchTaskStatus.WAITING_FOR_RESOURCE -> R.string.cc_global_status_waiting
        GlobalResearchTaskStatus.COMPLETED -> R.string.cc_global_status_completed
        GlobalResearchTaskStatus.FAILED -> R.string.cc_global_status_failed
        GlobalResearchTaskStatus.PAUSED -> R.string.on_device_agent_status_paused
    })

    private fun renderControlCenterProfilePage() {
        val profile = AppStore.profile(this)
        val nickname = profile.optString("name", getString(R.string.settings_profile_me))
        val signalasiId = SignalASICrypto.localSignalasiId()
        val fingerprint = SignalASICrypto.localIdentitySha256().filter(Char::isLetterOrDigit)
        val safety = mobileNativeAgent.safetySettings()
        showControlCenterFeature(
            getString(R.string.cc_profile_title),
            ControlCenterPageSpec(
                hero = ControlCenterHeroSpec(
                    title = nickname,
                    subtitle = getString(R.string.cc_profile_subtitle),
                    iconRes = R.drawable.signalasi_mark_large,
                    preserveIconColor = true,
                    badges = listOf(
                        ControlCenterBadgeSpec(
                            getString(if (safety.executionPaused) R.string.on_device_agent_status_paused else R.string.settings_badge_agent_enabled),
                            if (safety.executionPaused) ControlCenterTone.AMBER else ControlCenterTone.GREEN
                        ),
                        ControlCenterBadgeSpec(getString(R.string.cc_identity_verified), ControlCenterTone.BLUE)
                    )
                ),
                sections = listOf(
                    ControlCenterSectionSpec(
                        getString(R.string.cc_section_identity),
                        listOf(
                            ControlCenterRowSpec("profile.nickname", getString(R.string.cc_nickname_title), getString(R.string.cc_nickname_subtitle), R.drawable.ic_avatar_profile, nickname),
                            ControlCenterRowSpec("profile.copy_id", getString(R.string.settings_signalasi_id), signalasiId, R.drawable.ic_protocol_link, getString(R.string.common_copy), ControlCenterTone.BLUE, showChevron = false),
                            ControlCenterRowSpec("profile.copy_fingerprint", getString(R.string.settings_identity_fingerprint), compactFingerprint(fingerprint), R.drawable.ic_settings_fingerprint, getString(R.string.cc_identity_verified), ControlCenterTone.GREEN, showChevron = false)
                        )
                    ),
                    ControlCenterSectionSpec(
                        getString(R.string.common_status),
                        listOf(
                            ControlCenterRowSpec(routeAction(ControlCenterRoute.AGENT_CORE), getString(R.string.cc_agent_identity_title), getString(R.string.cc_agent_identity_subtitle), R.drawable.ic_agent_node, getString(if (safety.executionPaused) R.string.on_device_agent_status_paused else R.string.status_enabled), if (safety.executionPaused) ControlCenterTone.AMBER else ControlCenterTone.VIOLET),
                            ControlCenterRowSpec("", getString(R.string.cc_device_info_title), "${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE}", R.drawable.ic_device_node, showChevron = false)
                        )
                    ),
                    ControlCenterSectionSpec(
                        getString(R.string.security_section_identity),
                        listOf(ControlCenterRowSpec("profile.recovery", getString(R.string.cc_identity_recovery_title), getString(R.string.cc_identity_recovery_subtitle), R.drawable.ic_settings_upload, "", ControlCenterTone.AMBER))
                    )
                )
            )
        )
    }

    private fun renderControlCenterSystemStatusPage() {
        val state = mobileNativeAgent.snapshot()
        val safety = mobileNativeAgent.safetySettings()
        val tools = mobileNativeAgent.nativeToolCatalog()
        val visibleTargets = controlCenterResourceTargets(state.callableTargets)
        val availableResources = visibleTargets.count { it.status == AgentConnectorStatus.AVAILABLE }
        val linkReady = SignalASIMqttClient.isConnected() && SignalASIMqttClient.isSecureReady()
        val knowledgeCount = mobileNativeAgent.knowledgeSourceGroups().size
        val needsAttention = safety.executionPaused || !linkReady ||
            state.callableTargets.any { it.status == AgentConnectorStatus.NEEDS_SETUP }
        showControlCenterFeature(
            getString(R.string.cc_system_status_title),
            ControlCenterPageSpec(
                banner = ControlCenterBannerSpec(
                    title = getString(if (needsAttention) R.string.cc_services_need_attention else R.string.cc_all_services_normal),
                    subtitle = getString(if (needsAttention) R.string.cc_services_need_attention_subtitle else R.string.cc_all_services_normal_subtitle),
                    iconRes = if (needsAttention) R.drawable.ic_info_outline else R.drawable.ic_security_shield,
                    tone = if (needsAttention) ControlCenterTone.AMBER else ControlCenterTone.GREEN
                ),
                hero = ControlCenterHeroSpec(
                    title = getString(R.string.cc_system_status_title),
                    subtitle = getString(if (needsAttention) R.string.cc_services_need_attention_subtitle else R.string.cc_all_services_normal_subtitle),
                    iconRes = R.drawable.ic_info_outline,
                    metrics = listOf(
                        ControlCenterMetricSpec(tools.count { it.availability.status == AgentNativeToolAvailabilityStatus.AVAILABLE }.toString(), getString(R.string.cc_metric_native_tools)),
                        ControlCenterMetricSpec("$availableResources/${visibleTargets.size}", getString(R.string.cc_metric_available_resources)),
                        ControlCenterMetricSpec(state.recentTasks.size.toString(), getString(R.string.cc_metric_tasks))
                    )
                ),
                sections = listOf(
                    ControlCenterSectionSpec(
                        getString(R.string.cc_section_core_services),
                        listOf(
                            ControlCenterRowSpec(routeAction(ControlCenterRoute.AGENT_CORE), getString(R.string.cc_service_runtime), getString(if (safety.executionPaused) R.string.cc_agent_paused_subtitle else R.string.cc_service_runtime_subtitle), R.drawable.ic_agent_node, getString(if (safety.executionPaused) R.string.on_device_agent_status_paused else R.string.cc_status_online), if (safety.executionPaused) ControlCenterTone.AMBER else ControlCenterTone.GREEN),
                            ControlCenterRowSpec(routeAction(ControlCenterRoute.NODES), getString(R.string.cc_service_link), getString(if (linkReady) R.string.cc_service_link_connected else R.string.cc_service_link_offline), R.drawable.ic_protocol_link, getString(if (linkReady) R.string.cc_status_online else R.string.cc_status_degraded), if (linkReady) ControlCenterTone.GREEN else ControlCenterTone.AMBER),
                            ControlCenterRowSpec(routeAction(ControlCenterRoute.RESOURCE_ROUTING), getString(R.string.cc_service_router), getString(R.string.cc_service_router_subtitle, availableResources, visibleTargets.size), R.drawable.ic_settings_model, getString(if (availableResources > 0) R.string.cc_status_ready else R.string.cc_status_degraded), if (availableResources > 0) ControlCenterTone.BLUE else ControlCenterTone.AMBER),
                            ControlCenterRowSpec(routeAction(ControlCenterRoute.KNOWLEDGE), getString(R.string.cc_service_knowledge), getString(R.string.cc_service_knowledge_subtitle, knowledgeCount), R.drawable.ic_agent_knowledge, getString(if (knowledgeCount > 0) R.string.cc_status_ready else R.string.status_needs_setup), if (knowledgeCount > 0) ControlCenterTone.BLUE else ControlCenterTone.NEUTRAL)
                        )
                    )
                )
            )
        )
    }

    private fun renderControlCenterAgentCorePage() {
        val safety = mobileNativeAgent.safetySettings()
        val planner = mobileNativeAgent.modelPlannerSettings()
        showControlCenterFeature(
            getString(R.string.cc_agent_core_title),
            ControlCenterPageSpec(
                banner = ControlCenterBannerSpec(
                    title = getString(if (safety.executionPaused) R.string.cc_agent_paused else R.string.cc_agent_running),
                    subtitle = getString(if (safety.executionPaused) R.string.cc_agent_paused_subtitle else R.string.cc_agent_running_subtitle),
                    iconRes = R.drawable.ic_agent_node,
                    tone = if (safety.executionPaused) ControlCenterTone.AMBER else ControlCenterTone.GREEN
                ),
                sections = listOf(
                    ControlCenterSectionSpec(
                        getString(R.string.cc_section_autonomy),
                        listOf(ControlCenterRowSpec("agent.execution_policy", getString(R.string.cc_autonomy_title), getString(R.string.cc_autonomy_subtitle), R.drawable.ic_security_shield, permissionModeLabel(safety.permissionMode), ControlCenterTone.BLUE))
                    ),
                    ControlCenterSectionSpec(
                        getString(R.string.cc_section_core_capabilities),
                        listOf(
                            ControlCenterRowSpec("agent.planner", getString(R.string.cc_planning_title), getString(R.string.cc_planning_subtitle, planner.maxReplans), R.drawable.ic_agent_control, getString(if (planner.dynamicReplanning) R.string.status_enabled else R.string.common_off), ControlCenterTone.BLUE),
                            ControlCenterRowSpec("agent.planner", getString(R.string.cc_multitask_title), getString(R.string.cc_multitask_subtitle), R.drawable.ic_agent_history, getString(if (planner.multiAgentCoordination) R.string.status_enabled else R.string.common_off), ControlCenterTone.GREEN),
                            ControlCenterRowSpec(routeAction(ControlCenterRoute.RESOURCE_ROUTING), getString(R.string.cc_failure_recovery_title), getString(R.string.cc_failure_recovery_subtitle), R.drawable.ic_reset_data, getString(R.string.cc_status_ready), ControlCenterTone.AMBER),
                            ControlCenterRowSpec(routeAction(ControlCenterRoute.RESOURCE_ROUTING), getString(R.string.cc_resource_routing_title), getString(R.string.cc_resource_routing_subtitle), R.drawable.ic_settings_model, "", ControlCenterTone.VIOLET)
                        )
                    ),
                    ControlCenterSectionSpec(
                        getString(R.string.cc_section_runtime_protection),
                        listOf(
                            ControlCenterRowSpec("agent.toggle_pause", getString(R.string.cc_pause_all_title), getString(R.string.cc_pause_all_subtitle), R.drawable.ic_agent_history, switchValue = safety.executionPaused, showChevron = false),
                            ControlCenterRowSpec("agent.planner", getString(R.string.cc_advanced_agent_settings), getString(R.string.cc_advanced_agent_settings_subtitle), R.drawable.ic_settings_diagnostics, "", ControlCenterTone.NEUTRAL)
                        )
                    )
                )
            )
        )
    }

    private fun renderControlCenterMemoryPage() {
        val snapshot = mobileNativeAgent.memorySnapshot()
        val globalMemory = GlobalSuperAgentRuntime.get(this)
        val pendingCandidates = globalMemory.memoryInboxSnapshot().pending()
        val evolutionRecords = globalMemory.memoryEvolutionRecordsSnapshot()
        val entityGraph = globalMemory.entityMemoryGraphSnapshot()
        val memoryAudit = globalMemory.memoryAuditSnapshot()
        val captureEnabled = mobileNativeAgent.safetySettings().memoryCapture
        val countFor: (Set<AgentMemoryKind>) -> Int = { kinds ->
            snapshot.activeItems.count { it.kind in kinds }
        }
        showControlCenterFeature(
            getString(R.string.cc_memory_title),
            ControlCenterPageSpec(
                hero = ControlCenterHeroSpec(
                    title = getString(R.string.cc_memory_overview_title),
                    subtitle = getString(R.string.cc_memory_overview_subtitle),
                    iconRes = R.drawable.ic_agent_memory,
                    badges = listOf(
                        ControlCenterBadgeSpec(
                            getString(if (captureEnabled) R.string.cc_memory_capture_on else R.string.cc_memory_capture_off),
                            if (captureEnabled) ControlCenterTone.GREEN else ControlCenterTone.NEUTRAL
                        ),
                        ControlCenterBadgeSpec(
                            getString(R.string.cc_memory_conflict_badge, snapshot.conflicts.size),
                            if (snapshot.conflicts.isEmpty()) ControlCenterTone.BLUE else ControlCenterTone.AMBER
                        )
                    ),
                    metrics = listOf(
                        ControlCenterMetricSpec(snapshot.activeCount.toString(), getString(R.string.cc_memory_metric_active)),
                        ControlCenterMetricSpec(snapshot.historyCount.toString(), getString(R.string.cc_memory_metric_history)),
                        ControlCenterMetricSpec(snapshot.conflicts.size.toString(), getString(R.string.cc_memory_metric_conflicts))
                    ),
                    actionId = "memory.manage"
                ),
                sections = listOf(
                    ControlCenterSectionSpec(
                        getString(R.string.cc_memory_section_categories),
                        listOf(
                            ControlCenterRowSpec(
                                "memory.group:identity",
                                getString(R.string.cc_memory_identity_preferences_title),
                                getString(R.string.cc_memory_identity_preferences_subtitle),
                                R.drawable.ic_avatar_profile,
                                countFor(setOf(AgentMemoryKind.IDENTITY, AgentMemoryKind.PREFERENCE)).toString(),
                                ControlCenterTone.BLUE
                            ),
                            ControlCenterRowSpec(
                                "memory.group:people",
                                getString(R.string.cc_memory_people_title),
                                getString(R.string.cc_memory_people_subtitle),
                                R.drawable.ic_tab_contacts_outline,
                                countFor(setOf(AgentMemoryKind.CONTACT)).toString(),
                                ControlCenterTone.GREEN
                            ),
                            ControlCenterRowSpec(
                                "memory.group:work",
                                getString(R.string.cc_memory_work_title),
                                getString(R.string.cc_memory_work_subtitle),
                                R.drawable.ic_agent_history,
                                countFor(setOf(AgentMemoryKind.TASK, AgentMemoryKind.WORKFLOW)).toString(),
                                ControlCenterTone.VIOLET
                            ),
                            ControlCenterRowSpec(
                                "memory.group:knowledge",
                                getString(R.string.cc_memory_knowledge_title),
                                getString(R.string.cc_memory_knowledge_subtitle),
                                R.drawable.ic_agent_knowledge,
                                countFor(setOf(AgentMemoryKind.KNOWLEDGE, AgentMemoryKind.SAFETY)).toString(),
                                ControlCenterTone.AMBER
                            )
                        )
                    ),
                    ControlCenterSectionSpec(
                        getString(R.string.cc_memory_section_controls),
                        listOf(
                            ControlCenterRowSpec(
                                "memory.toggle_capture",
                                getString(R.string.cc_memory_capture_title),
                                getString(R.string.cc_memory_capture_subtitle),
                                R.drawable.ic_security_shield,
                                switchValue = captureEnabled,
                                showChevron = false
                            ),
                            ControlCenterRowSpec(
                                "memory.manage",
                                getString(R.string.cc_memory_manage_title),
                                getString(R.string.cc_memory_manage_subtitle),
                                R.drawable.ic_agent_memory,
                                getString(R.string.common_view),
                                ControlCenterTone.BLUE
                            )
                        )
                    ),
                    ControlCenterSectionSpec(
                        getString(R.string.cc_memory_section_evolution),
                        listOf(
                            ControlCenterRowSpec(
                                "memory.inbox",
                                getString(R.string.cc_memory_inbox_title),
                                getString(R.string.cc_memory_inbox_subtitle),
                                R.drawable.ic_agent_memory,
                                pendingCandidates.size.toString(),
                                if (pendingCandidates.isEmpty()) ControlCenterTone.GREEN else ControlCenterTone.AMBER
                            ),
                            ControlCenterRowSpec(
                                "memory.evolution_history",
                                getString(R.string.cc_memory_evolution_history_title),
                                getString(R.string.cc_memory_evolution_history_subtitle),
                                R.drawable.ic_agent_history,
                                evolutionRecords.size.toString(),
                                ControlCenterTone.VIOLET
                            ),
                            ControlCenterRowSpec(
                                "memory.graph",
                                getString(R.string.cc_memory_graph_title),
                                getString(R.string.cc_memory_graph_subtitle),
                                R.drawable.ic_protocol_link,
                                getString(R.string.cc_memory_graph_status, entityGraph.nodes.size, entityGraph.relations.size),
                                ControlCenterTone.BLUE
                            ),
                            ControlCenterRowSpec(
                                "memory.audit",
                                getString(R.string.cc_memory_audit_title),
                                getString(R.string.cc_memory_audit_subtitle),
                                R.drawable.ic_security_shield,
                                memoryAudit.findings.size.toString(),
                                if (memoryAudit.findings.isEmpty()) ControlCenterTone.GREEN else ControlCenterTone.AMBER
                            )
                        )
                    )
                )
            )
        )
    }

    private fun showGlobalMemoryInboxPage() {
        val runtime = GlobalSuperAgentRuntime.get(this)
        val pending = runtime.memoryInboxSnapshot().pending()
        showFeaturePage(getString(R.string.cc_memory_inbox_title))
        featureContent.addView(featureHeroCard(
            getString(R.string.cc_memory_inbox_hero_title),
            getString(R.string.cc_memory_inbox_hero_subtitle),
            R.drawable.ic_agent_memory,
            "#5B6CFF",
            pending.size.toString()
        ))
        addSectionTitle(getString(R.string.cc_memory_inbox_pending_section))
        if (pending.isEmpty()) {
            featureContent.addView(featureRow(
                getString(R.string.cc_memory_inbox_empty),
                getString(R.string.cc_memory_inbox_empty_subtitle),
                R.drawable.ic_security_shield,
                ""
            ))
            return
        }
        pending.forEach { candidate ->
            featureContent.addView(featureRow(
                candidate.item.value.ifBlank { candidate.item.topic }.replace(Regex("\\s+"), " ").take(90),
                getString(
                    R.string.cc_memory_candidate_subtitle,
                    memoryCandidateKindLabel(candidate.kind),
                    memoryTemporalStateLabel(candidate.temporalState),
                    memoryEvolutionActionLabel(candidate.action)
                ),
                R.drawable.ic_agent_memory,
                getString(R.string.agent_memory_review)
            ).apply {
                setOnClickListener { showGlobalMemoryCandidateDialog(candidate) }
            })
        }
    }

    private fun showGlobalMemoryCandidateDialog(candidate: GlobalMemoryCandidate) {
        val detail = getString(
            R.string.cc_memory_candidate_dialog_message,
            memoryCandidateKindLabel(candidate.kind),
            candidate.item.topic.ifBlank { getString(R.string.agent_memory_key_none) },
            memoryEvolutionActionLabel(candidate.action),
            candidate.item.value.ifBlank { getString(R.string.cc_memory_candidate_private_value) }
        )
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.cc_memory_candidate_dialog_title))
            .setMessage(detail)
            .setPositiveButton(R.string.cc_memory_candidate_approve) { _, _ ->
                val approved = GlobalSuperAgentRuntime.get(this).approveMemoryCandidate(candidate.id)
                Toast.makeText(
                    this,
                    getString(if (approved) R.string.cc_memory_candidate_approved else R.string.cc_memory_candidate_unchanged),
                    Toast.LENGTH_SHORT
                ).show()
                showGlobalMemoryInboxPage()
            }
            .setNegativeButton(R.string.common_reject) { _, _ ->
                val rejected = GlobalSuperAgentRuntime.get(this).rejectMemoryCandidate(candidate.id)
                Toast.makeText(
                    this,
                    getString(if (rejected) R.string.cc_memory_candidate_rejected else R.string.cc_memory_candidate_unchanged),
                    Toast.LENGTH_SHORT
                ).show()
                showGlobalMemoryInboxPage()
            }
            .setNeutralButton(R.string.common_cancel, null)
            .show()
    }

    private fun showGlobalMemoryGraphPage() {
        val graph = GlobalSuperAgentRuntime.get(this).entityMemoryGraphSnapshot()
        val nodesById = graph.nodes.associateBy(GlobalEntityNode::id)
        showFeaturePage(getString(R.string.cc_memory_graph_title))
        featureContent.addView(featureHeroCard(
            getString(R.string.cc_memory_graph_hero_title),
            getString(R.string.cc_memory_graph_hero_subtitle),
            R.drawable.ic_protocol_link,
            "#2F80ED",
            getString(R.string.cc_memory_graph_status, graph.nodes.size, graph.relations.size)
        ))
        addSectionTitle(getString(R.string.cc_memory_graph_current_entities))
        if (graph.nodes.isEmpty()) {
            featureContent.addView(featureRow(
                getString(R.string.cc_memory_graph_empty),
                getString(R.string.cc_memory_graph_empty_subtitle),
                R.drawable.ic_protocol_link,
                ""
            ))
        } else {
            graph.nodes.sortedByDescending(GlobalEntityNode::lastSeenAtMillis).take(40).forEach { node ->
                featureContent.addView(featureRow(
                    node.label,
                    getString(
                        R.string.cc_memory_graph_node_subtitle,
                        node.kind.name.lowercase(Locale.ROOT).replace('_', ' '),
                        memoryTemporalStateLabel(node.temporalState)
                    ),
                    R.drawable.ic_agent_node,
                    ""
                ))
            }
        }
        if (graph.relations.isNotEmpty()) {
            addSectionTitle(getString(R.string.cc_memory_graph_relations))
            graph.relations.sortedByDescending(GlobalEntityRelation::lastSeenAtMillis).take(40).forEach { relation ->
                val from = nodesById[relation.fromNodeId]?.label ?: return@forEach
                val to = nodesById[relation.toNodeId]?.label ?: return@forEach
                featureContent.addView(featureRow(
                    getString(R.string.cc_memory_graph_relation_title, from, to),
                    relation.kind.name.lowercase(Locale.ROOT).replace('_', ' '),
                    R.drawable.ic_protocol_link,
                    memoryTemporalStateLabel(relation.temporalState)
                ))
            }
        }
    }

    private fun showGlobalMemoryEvolutionHistoryPage() {
        val records = GlobalSuperAgentRuntime.get(this).memoryEvolutionRecordsSnapshot()
            .sortedByDescending(GlobalMemoryEvolutionRecord::createdAtMillis)
        showFeaturePage(getString(R.string.cc_memory_evolution_history_title))
        featureContent.addView(featureHeroCard(
            getString(R.string.cc_memory_evolution_history_hero_title),
            getString(R.string.cc_memory_evolution_history_hero_subtitle),
            R.drawable.ic_agent_history,
            "#6C5CE7",
            records.size.toString()
        ))
        addSectionTitle(getString(R.string.cc_memory_evolution_history_recent))
        if (records.isEmpty()) {
            featureContent.addView(featureRow(
                getString(R.string.cc_memory_evolution_history_empty),
                getString(R.string.cc_memory_evolution_history_empty_subtitle),
                R.drawable.ic_agent_history,
                ""
            ))
            return
        }
        records.take(100).forEach { record ->
            featureContent.addView(featureRow(
                record.subject,
                getString(
                    R.string.cc_memory_evolution_history_item_subtitle,
                    memoryEvolutionActionLabel(record.action),
                    memoryEvolutionOutcomeLabel(record.outcome),
                    record.evidenceCount
                ),
                R.drawable.ic_agent_history,
                securityTime(record.createdAtMillis)
            ))
        }
    }

    private fun showGlobalMemoryAuditPage(runNow: Boolean = false) {
        val runtime = GlobalSuperAgentRuntime.get(this)
        val report = if (runNow) runtime.runMemoryAudit() else runtime.memoryAuditSnapshot()
        showFeaturePage(getString(R.string.cc_memory_audit_title))
        featureContent.addView(featureHeroCard(
            getString(R.string.cc_memory_audit_hero_title),
            getString(R.string.cc_memory_audit_hero_subtitle),
            R.drawable.ic_security_shield,
            "#16A085",
            report.findings.size.toString()
        ).apply { setOnClickListener { showGlobalMemoryAuditPage(runNow = true) } })
        addSectionTitle(getString(R.string.cc_memory_audit_findings))
        if (report.findings.isEmpty()) {
            featureContent.addView(featureRow(
                getString(R.string.cc_memory_audit_clean),
                getString(R.string.cc_memory_audit_clean_subtitle),
                R.drawable.ic_security_shield,
                getString(R.string.cc_status_ready)
            ))
        } else {
            report.findings.forEach { finding ->
                featureContent.addView(featureRow(
                    memoryAuditFindingLabel(finding.kind),
                    getString(R.string.cc_memory_audit_evidence_count, finding.evidenceCount),
                    R.drawable.ic_security_shield,
                    ""
                ))
            }
        }
        if (report.themes.isNotEmpty()) {
            addSectionTitle(getString(R.string.cc_memory_audit_themes))
            report.themes.forEach { theme ->
                featureContent.addView(featureRow(
                    theme.title,
                    getString(
                        R.string.cc_memory_theme_subtitle,
                        theme.itemCount,
                        theme.conversationCount,
                        theme.evidenceCount
                    ),
                    R.drawable.ic_agent_knowledge,
                    ""
                ))
            }
        }
    }

    private fun memoryCandidateKindLabel(kind: GlobalMemoryCandidateKind): String = getString(
        when (kind) {
            GlobalMemoryCandidateKind.IDENTITY -> R.string.agent_memory_kind_identity
            GlobalMemoryCandidateKind.PREFERENCE -> R.string.agent_memory_kind_preference
            GlobalMemoryCandidateKind.GOAL, GlobalMemoryCandidateKind.PROJECT_STATE -> R.string.agent_memory_kind_task
            GlobalMemoryCandidateKind.DECISION, GlobalMemoryCandidateKind.SKILL_OPPORTUNITY -> R.string.agent_memory_kind_workflow
            GlobalMemoryCandidateKind.RELATION -> R.string.agent_memory_kind_contact
            GlobalMemoryCandidateKind.FACT -> R.string.agent_memory_kind_knowledge
        }
    )

    private fun memoryTemporalStateLabel(state: GlobalMemoryTemporalState): String = getString(
        when (state) {
            GlobalMemoryTemporalState.HISTORICAL -> R.string.cc_memory_state_historical
            GlobalMemoryTemporalState.CURRENT -> R.string.cc_memory_state_current
            GlobalMemoryTemporalState.PLANNED -> R.string.cc_memory_state_planned
            GlobalMemoryTemporalState.DEPRECATED -> R.string.cc_memory_state_deprecated
            GlobalMemoryTemporalState.PENDING -> R.string.cc_memory_state_pending
            GlobalMemoryTemporalState.CONFLICTED -> R.string.cc_memory_state_conflicted
        }
    )

    private fun memoryEvolutionActionLabel(action: GlobalMemoryEvolutionAction): String = getString(
        when (action) {
            GlobalMemoryEvolutionAction.CREATE -> R.string.cc_memory_action_create
            GlobalMemoryEvolutionAction.STRENGTHEN -> R.string.cc_memory_action_strengthen
            GlobalMemoryEvolutionAction.SUPERSEDE -> R.string.cc_memory_action_supersede
            GlobalMemoryEvolutionAction.LINK -> R.string.cc_memory_action_link
            GlobalMemoryEvolutionAction.CONSOLIDATE -> R.string.cc_memory_action_consolidate
            GlobalMemoryEvolutionAction.REVIEW_CONFLICT -> R.string.cc_memory_action_review_conflict
            GlobalMemoryEvolutionAction.BLOCK_PRIVATE -> R.string.cc_memory_action_block_private
        }
    )

    private fun memoryAuditFindingLabel(kind: GlobalMemoryAuditFindingKind): String = getString(
        when (kind) {
            GlobalMemoryAuditFindingKind.EXPIRED -> R.string.cc_memory_audit_expired
            GlobalMemoryAuditFindingKind.DUPLICATE -> R.string.cc_memory_audit_duplicate
            GlobalMemoryAuditFindingKind.LOW_CONFIDENCE_REUSED -> R.string.cc_memory_audit_low_confidence
            GlobalMemoryAuditFindingKind.STALE_CANDIDATE -> R.string.cc_memory_audit_stale_candidate
            GlobalMemoryAuditFindingKind.UNRESOLVED_CONFLICT -> R.string.cc_memory_audit_conflict
            GlobalMemoryAuditFindingKind.SKILL_CANDIDATE -> R.string.cc_memory_audit_skill_candidate
            GlobalMemoryAuditFindingKind.COMPLETED_GOAL -> R.string.cc_memory_audit_completed_goal
        }
    )

    private fun memoryEvolutionOutcomeLabel(outcome: GlobalMemoryEvolutionOutcome): String = getString(
        when (outcome) {
            GlobalMemoryEvolutionOutcome.APPLIED -> R.string.cc_memory_evolution_outcome_applied
            GlobalMemoryEvolutionOutcome.WAITING_REVIEW -> R.string.cc_memory_evolution_outcome_waiting
            GlobalMemoryEvolutionOutcome.CONFLICTED -> R.string.cc_memory_evolution_outcome_conflicted
            GlobalMemoryEvolutionOutcome.PRIVATE_BLOCKED -> R.string.cc_memory_evolution_outcome_private_blocked
            GlobalMemoryEvolutionOutcome.APPROVED -> R.string.cc_memory_evolution_outcome_approved
            GlobalMemoryEvolutionOutcome.REJECTED -> R.string.cc_memory_evolution_outcome_rejected
        }
    )

    private fun renderControlCenterLearningPage() {
        val pending = agentLearningEngine.proposals(AgentLearningProposalStatus.PENDING)
        val approved = agentLearningEngine.proposals(AgentLearningProposalStatus.APPROVED)
        val rejected = agentLearningEngine.proposals(AgentLearningProposalStatus.REJECTED)
        val captureEnabled = mobileNativeAgent.safetySettings().memoryCapture
        val proposalRows = pending.map { proposal ->
            ControlCenterRowSpec(
                actionId = "learning.proposal:${proposal.id}",
                title = proposal.title,
                subtitle = getString(R.string.cc_learning_evidence_subtitle, proposal.evidenceRunIds.size),
                iconRes = R.drawable.ic_agent_skill,
                status = getString(R.string.cc_learning_review),
                tone = ControlCenterTone.VIOLET
            )
        }.ifEmpty {
            listOf(
                ControlCenterRowSpec(
                    actionId = "",
                    title = getString(R.string.cc_learning_no_proposals_title),
                    subtitle = getString(R.string.cc_learning_no_proposals_subtitle),
                    iconRes = R.drawable.ic_agent_skill,
                    status = getString(R.string.cc_status_ready),
                    tone = ControlCenterTone.GREEN,
                    showChevron = false
                )
            )
        }
        showControlCenterFeature(
            getString(R.string.cc_learning_title),
            ControlCenterPageSpec(
                banner = ControlCenterBannerSpec(
                    title = getString(R.string.cc_learning_banner_title),
                    subtitle = getString(R.string.cc_learning_banner_subtitle),
                    iconRes = R.drawable.ic_agent_skill,
                    tone = if (pending.isEmpty()) ControlCenterTone.GREEN else ControlCenterTone.VIOLET
                ),
                hero = ControlCenterHeroSpec(
                    title = getString(R.string.cc_learning_overview_title),
                    subtitle = getString(R.string.cc_learning_overview_subtitle),
                    iconRes = R.drawable.ic_agent_skill,
                    metrics = listOf(
                        ControlCenterMetricSpec(pending.size.toString(), getString(R.string.cc_learning_metric_pending)),
                        ControlCenterMetricSpec(approved.size.toString(), getString(R.string.cc_learning_metric_approved)),
                        ControlCenterMetricSpec(rejected.size.toString(), getString(R.string.cc_learning_metric_rejected))
                    )
                ),
                sections = listOf(
                    ControlCenterSectionSpec(getString(R.string.cc_learning_section_proposals), proposalRows),
                    ControlCenterSectionSpec(
                        getString(R.string.cc_learning_section_policy),
                        listOf(
                            ControlCenterRowSpec(
                                actionId = "learning.toggle_capture",
                                title = getString(R.string.cc_learning_memory_title),
                                subtitle = getString(R.string.cc_learning_memory_subtitle),
                                iconRes = R.drawable.ic_agent_memory,
                                switchValue = captureEnabled,
                                showChevron = false
                            ),
                            ControlCenterRowSpec(
                                actionId = "",
                                title = getString(R.string.cc_learning_review_policy_title),
                                subtitle = getString(R.string.cc_learning_review_policy_subtitle),
                                iconRes = R.drawable.ic_security_shield,
                                status = getString(R.string.cc_learning_review),
                                tone = ControlCenterTone.BLUE,
                                showChevron = false
                            )
                        )
                    )
                )
            )
        )
    }

    private fun showLearningProposalDialog(proposalId: String) {
        val proposal = agentLearningEngine.proposals().firstOrNull { it.id == proposalId } ?: return
        AlertDialog.Builder(this)
            .setTitle(proposal.title)
            .setMessage(
                getString(
                    R.string.cc_learning_dialog_message,
                    proposal.summary,
                    proposal.evidenceRunIds.size
                )
            )
            .setPositiveButton(R.string.cc_learning_approve) { _, _ ->
                val installed = agentLearningEngine.approve(proposal.id)
                Toast.makeText(
                    this,
                    getString(if (installed != null) R.string.cc_learning_approved else R.string.cc_learning_action_failed),
                    Toast.LENGTH_SHORT
                ).show()
                renderControlCenterLearningPage()
            }
            .setNegativeButton(R.string.cc_learning_reject) { _, _ ->
                agentLearningEngine.reject(proposal.id)
                renderControlCenterLearningPage()
            }
            .setNeutralButton(R.string.common_cancel, null)
            .show()
    }

    private fun renderControlCenterRuntimePage() {
        val status = AgentOnDeviceRuntimeManager(this).status()
        val catalogManager = agentRuntimePackCatalogManager
        val catalog = catalogManager.cachedVerified()
        val catalogEntries = catalogManager.cachedCompatible()
        val catalogById = catalogEntries.associateBy(AgentRuntimePackCatalogEntry::packId)
        val receipts = AgentRuntimeExecutionReceiptStore(this).list(limit = 5)
        val readyPacks = status.packs.count { it.state == AgentRuntimePackState.READY }
        val environmentPackIds = setOf("linux-base", "python-uv")
        val environmentPacks = status.packs.filter { it.id in environmentPackIds }
        val softwarePacks = status.packs.filterNot { it.id in environmentPackIds }
        val softwareReady = softwarePacks.count { it.state == AgentRuntimePackState.READY }
        val environmentRows = environmentPacks.map { pack ->
            val catalogEntry = catalogById[pack.id]
            val preparing = runtimeCatalogRefreshInProgress && pendingRuntimeCatalogPackId == pack.id
            val stateText = when {
                preparing -> getString(R.string.cc_runtime_install_preparing)
                pack.state == AgentRuntimePackState.READY -> getString(R.string.cc_status_ready)
                pack.state == AgentRuntimePackState.NOT_INSTALLED -> getString(R.string.cc_runtime_catalog_install)
                else -> getString(R.string.cc_runtime_catalog_repair)
            }
            ControlCenterRowSpec(
                actionId = if (preparing) {
                    ""
                } else if (pack.state == AgentRuntimePackState.READY) {
                    "runtime.pack:${pack.id}"
                } else {
                    "runtime.auto_install:${pack.id}"
                },
                title = runtimePackTitle(pack.id),
                subtitle = catalogEntry?.let { entry ->
                    getString(
                        R.string.cc_runtime_catalog_pack_subtitle,
                        entry.version,
                        formatBytes(entry.archiveSizeBytes),
                        entry.license
                    )
                } ?: pack.reason.ifBlank {
                    pack.manifest?.capabilities?.joinToString().orEmpty().ifBlank {
                        getString(R.string.cc_runtime_pack_subtitle)
                    }
                },
                iconRes = R.drawable.ic_settings_diagnostics,
                status = stateText,
                tone = when (pack.state) {
                    AgentRuntimePackState.READY -> ControlCenterTone.GREEN
                    AgentRuntimePackState.NOT_INSTALLED -> ControlCenterTone.BLUE
                    AgentRuntimePackState.INVALID, AgentRuntimePackState.INCOMPATIBLE -> ControlCenterTone.AMBER
                },
                showChevron = true
            )
        }
        val receiptRows = if (receipts.isEmpty()) {
            listOf(
                ControlCenterRowSpec(
                    actionId = "",
                    title = getString(R.string.cc_runtime_receipt_empty_title),
                    subtitle = getString(R.string.cc_runtime_receipt_empty_subtitle),
                    iconRes = R.drawable.ic_agent_history,
                    status = "",
                    tone = ControlCenterTone.NEUTRAL,
                    showChevron = false
                )
            )
        } else {
            receipts.map { receipt ->
                ControlCenterRowSpec(
                    actionId = "runtime.receipt:${receipt.requestId}",
                    title = getString(
                        R.string.cc_runtime_receipt_title,
                        runtimeLanguageTitle(receipt.language)
                    ),
                    subtitle = getString(
                        R.string.cc_runtime_receipt_subtitle,
                        listTime(receipt.createdAtMillis),
                        receipt.requestId.take(8)
                    ),
                    iconRes = R.drawable.ic_agent_history,
                    status = runtimeReceiptStatus(receipt.status),
                    tone = if (receipt.status == AgentRuntimeReceiptStatus.COMPLETED) {
                        ControlCenterTone.GREEN
                    } else if (receipt.status == AgentRuntimeReceiptStatus.RUNNING) {
                        ControlCenterTone.BLUE
                    } else {
                        ControlCenterTone.AMBER
                    },
                    showChevron = true
                )
            }
        }
        showControlCenterFeature(
            getString(R.string.cc_runtime_title),
            ControlCenterPageSpec(
                banner = ControlCenterBannerSpec(
                    title = getString(if (status.backendReady) R.string.cc_runtime_ready_title else R.string.cc_runtime_setup_title),
                    subtitle = status.reason,
                    iconRes = R.drawable.ic_settings_diagnostics,
                    tone = if (status.backendReady) ControlCenterTone.GREEN else ControlCenterTone.AMBER
                ),
                hero = ControlCenterHeroSpec(
                    title = getString(R.string.cc_runtime_overview_title),
                    subtitle = getString(R.string.cc_runtime_overview_subtitle),
                    iconRes = R.drawable.ic_settings_diagnostics,
                    badges = listOf(
                        ControlCenterBadgeSpec(status.architecture.ifBlank { "unknown" }, ControlCenterTone.BLUE),
                        ControlCenterBadgeSpec(status.backend.wireValue, if (status.backendReady) ControlCenterTone.GREEN else ControlCenterTone.NEUTRAL)
                    ),
                    metrics = listOf(
                        ControlCenterMetricSpec(readyPacks.toString(), getString(R.string.cc_runtime_metric_ready)),
                        ControlCenterMetricSpec(status.packs.size.toString(), getString(R.string.cc_runtime_metric_total)),
                        ControlCenterMetricSpec(AgentRuntimeLanguage.entries.count(status::languageReady).toString(), getString(R.string.cc_runtime_metric_languages))
                    )
                ),
                sections = listOf(
                    ControlCenterSectionSpec(
                        getString(R.string.cc_runtime_section_management),
                        listOf(
                            ControlCenterRowSpec(
                                actionId = if (status.lifecyclePhase in setOf(
                                        AgentRuntimeLifecyclePhase.STARTING,
                                        AgentRuntimeLifecyclePhase.STOPPING
                                    )) "" else "runtime.lifecycle",
                                title = getString(R.string.cc_runtime_lifecycle_title),
                                subtitle = status.lifecycleReason.ifBlank {
                                    getString(R.string.cc_runtime_lifecycle_subtitle)
                                },
                                iconRes = R.drawable.ic_protocol_link,
                                status = runtimeLifecycleLabel(status.lifecyclePhase),
                                tone = when (status.lifecyclePhase) {
                                    AgentRuntimeLifecyclePhase.READY -> ControlCenterTone.GREEN
                                    AgentRuntimeLifecyclePhase.STARTING -> ControlCenterTone.BLUE
                                    AgentRuntimeLifecyclePhase.BLOCKED,
                                    AgentRuntimeLifecyclePhase.DEGRADED,
                                    AgentRuntimeLifecyclePhase.BACKING_OFF -> ControlCenterTone.AMBER
                                    AgentRuntimeLifecyclePhase.STOPPED,
                                    AgentRuntimeLifecyclePhase.STOPPING -> ControlCenterTone.NEUTRAL
                                },
                                showChevron = status.lifecyclePhase !in setOf(
                                    AgentRuntimeLifecyclePhase.STARTING,
                                    AgentRuntimeLifecyclePhase.STOPPING
                                )
                            ),
                            ControlCenterRowSpec(
                                actionId = routeAction(ControlCenterRoute.SOFTWARE_CENTER),
                                title = getString(R.string.cc_runtime_software_center_title),
                                subtitle = getString(R.string.cc_runtime_software_center_subtitle),
                                iconRes = R.drawable.ic_local_model,
                                status = getString(
                                    R.string.cc_runtime_software_center_status,
                                    softwareReady,
                                    softwarePacks.size
                                ),
                                tone = if (softwareReady == softwarePacks.size) ControlCenterTone.GREEN else ControlCenterTone.BLUE
                            ),
                            ControlCenterRowSpec(
                                actionId = if (runtimeCatalogRefreshInProgress) "" else "runtime.catalog_refresh",
                                title = getString(R.string.cc_runtime_catalog_refresh_title),
                                subtitle = if (catalog == null) {
                                    getString(R.string.cc_runtime_catalog_refresh_subtitle)
                                } else {
                                    getString(
                                        R.string.cc_runtime_catalog_loaded_subtitle,
                                        catalog.catalogVersion,
                                        catalogEntries.size
                                    )
                                },
                                iconRes = R.drawable.ic_settings_diagnostics,
                                status = if (runtimeCatalogRefreshInProgress) {
                                    getString(R.string.cc_runtime_catalog_refreshing)
                                } else {
                                    catalog?.catalogVersion.orEmpty()
                                },
                                tone = if (catalog == null) ControlCenterTone.NEUTRAL else ControlCenterTone.GREEN,
                                showChevron = !runtimeCatalogRefreshInProgress
                            )
                        )
                    ),
                    ControlCenterSectionSpec(getString(R.string.cc_runtime_section_environment), environmentRows),
                    ControlCenterSectionSpec(getString(R.string.cc_runtime_section_receipts), receiptRows),
                    ControlCenterSectionSpec(
                        getString(R.string.cc_runtime_section_security),
                        listOf(
                            ControlCenterRowSpec("", getString(R.string.cc_runtime_isolation_title), getString(R.string.cc_runtime_isolation_subtitle), R.drawable.ic_security_shield, getString(R.string.cc_status_ready), ControlCenterTone.GREEN, showChevron = false),
                            ControlCenterRowSpec("", getString(R.string.cc_runtime_network_title), getString(R.string.cc_runtime_network_subtitle), R.drawable.ic_protocol_link, getString(R.string.common_off), ControlCenterTone.NEUTRAL, showChevron = false)
                        )
                    )
                )
            )
        )
    }

    private fun renderControlCenterSoftwareCenterPage(query: String = "") {
        val runtimeManager = AgentOnDeviceRuntimeManager(this)
        val packStatuses = runtimeManager.packStatuses()
        val catalogManager = agentRuntimePackCatalogManager
        val catalog = catalogManager.cachedVerified()
        val catalogById = catalogManager.cachedCompatible().associateBy(AgentRuntimePackCatalogEntry::packId)
        val installedById = packStatuses.associateBy(AgentRuntimePackStatus::id)
        val softwareIds = AgentOnDeviceRuntimeManager.REQUIRED_PACKS.filterNot {
            it == "linux-base" || it == "python-uv"
        }
        val normalizedQuery = query.trim().lowercase(Locale.ROOT)
        val visibleIds = softwareIds.filter { packId ->
            normalizedQuery.isBlank() ||
                packId.lowercase(Locale.ROOT).contains(normalizedQuery) ||
                runtimePackTitle(packId).lowercase(Locale.ROOT).contains(normalizedQuery)
        }
        val softwareRows = if (visibleIds.isEmpty()) {
            listOf(
                ControlCenterRowSpec(
                    actionId = "runtime.software_clear_search",
                    title = getString(R.string.cc_runtime_software_no_results_title),
                    subtitle = getString(R.string.cc_runtime_software_no_results_subtitle, query),
                    iconRes = R.drawable.ic_settings_model,
                    status = getString(R.string.cc_runtime_software_clear_search),
                    tone = ControlCenterTone.NEUTRAL
                )
            )
        } else {
            visibleIds.map { packId ->
                val installed = installedById[packId]
                val entry = catalogById[packId]
                val ready = installed?.state == AgentRuntimePackState.READY
                val sameVersion = ready && entry != null && installed?.manifest?.version == entry.version
                val update = ready && entry != null && !sameVersion
                val preparing = !ready && (
                    runtimePackInstallInProgressId == packId ||
                        (runtimeCatalogRefreshInProgress && pendingRuntimeCatalogPackId == packId)
                    )
                val actionId = if (preparing) {
                    ""
                } else if (ready && !update) {
                    "runtime.pack:$packId"
                } else {
                    "runtime.auto_install:$packId"
                }
                val subtitle = entry?.let {
                    getString(
                        R.string.cc_runtime_catalog_pack_subtitle,
                        it.version,
                        formatBytes(it.archiveSizeBytes),
                        it.license
                    )
                } ?: installed?.manifest?.let {
                    getString(
                        R.string.cc_runtime_software_installed_subtitle,
                        it.version,
                        formatBytes(it.installedSizeBytes)
                    )
                } ?: getString(R.string.cc_runtime_software_lookup_subtitle)
                val statusText = getString(
                    when {
                        preparing -> R.string.cc_runtime_install_preparing
                        update -> R.string.cc_runtime_catalog_update
                        ready -> R.string.cc_runtime_catalog_installed
                        installed?.state == AgentRuntimePackState.INVALID ||
                            installed?.state == AgentRuntimePackState.INCOMPATIBLE -> R.string.cc_runtime_catalog_repair
                        else -> R.string.cc_runtime_catalog_install
                    }
                )
                ControlCenterRowSpec(
                    actionId = actionId,
                    title = runtimePackTitle(packId),
                    subtitle = subtitle,
                    iconRes = if (packId == "ffmpeg") R.drawable.ic_tab_discover else R.drawable.ic_settings_diagnostics,
                    status = statusText,
                    tone = when {
                        ready && !update -> ControlCenterTone.GREEN
                        installed?.state == AgentRuntimePackState.INVALID ||
                            installed?.state == AgentRuntimePackState.INCOMPATIBLE -> ControlCenterTone.AMBER
                        else -> ControlCenterTone.BLUE
                    }
                )
            }
        }
        val readyCount = packStatuses.count {
            it.id in softwareIds && it.state == AgentRuntimePackState.READY
        }
        showControlCenterFeature(
            getString(R.string.cc_runtime_software_center_title),
            ControlCenterPageSpec(
                banner = ControlCenterBannerSpec(
                    title = getString(R.string.cc_runtime_software_banner_title),
                    subtitle = getString(R.string.cc_runtime_software_banner_subtitle),
                    iconRes = R.drawable.ic_local_model,
                    tone = ControlCenterTone.BLUE
                ),
                hero = ControlCenterHeroSpec(
                    title = getString(R.string.cc_runtime_software_overview_title),
                    subtitle = getString(R.string.cc_runtime_software_overview_subtitle),
                    iconRes = R.drawable.ic_local_model,
                    badges = listOf(
                        ControlCenterBadgeSpec(
                            getString(R.string.cc_runtime_software_verified_badge),
                            ControlCenterTone.GREEN
                        ),
                        ControlCenterBadgeSpec(runtimeManager.architecture().ifBlank { "unknown" }, ControlCenterTone.BLUE)
                    ),
                    metrics = listOf(
                        ControlCenterMetricSpec(readyCount.toString(), getString(R.string.cc_runtime_software_metric_installed)),
                        ControlCenterMetricSpec(softwareIds.size.toString(), getString(R.string.cc_runtime_software_metric_available)),
                        ControlCenterMetricSpec(catalog?.catalogVersion.orEmpty().ifBlank { "-" }, getString(R.string.cc_runtime_software_metric_catalog))
                    )
                ),
                sections = listOf(
                    ControlCenterSectionSpec(
                        getString(R.string.cc_runtime_software_section_find),
                        listOf(
                            ControlCenterRowSpec(
                                actionId = "runtime.software_search",
                                title = getString(R.string.cc_runtime_software_search_title),
                                subtitle = if (query.isBlank()) {
                                    getString(R.string.cc_runtime_software_search_subtitle)
                                } else {
                                    getString(R.string.cc_runtime_software_search_active, query)
                                },
                                iconRes = R.drawable.ic_settings_model,
                                status = if (query.isBlank()) "" else getString(R.string.cc_runtime_software_clear_search),
                                tone = ControlCenterTone.NEUTRAL
                            ),
                            ControlCenterRowSpec(
                                actionId = if (runtimeCatalogRefreshInProgress) "" else "runtime.catalog_refresh",
                                title = getString(R.string.cc_runtime_catalog_refresh_title),
                                subtitle = if (catalog == null) {
                                    getString(R.string.cc_runtime_catalog_refresh_subtitle)
                                } else {
                                    getString(
                                        R.string.cc_runtime_catalog_loaded_subtitle,
                                        catalog.catalogVersion,
                                        catalogById.size
                                    )
                                },
                                iconRes = R.drawable.ic_settings_diagnostics,
                                status = if (runtimeCatalogRefreshInProgress) {
                                    getString(R.string.cc_runtime_catalog_refreshing)
                                } else {
                                    catalog?.catalogVersion.orEmpty()
                                },
                                tone = if (catalog == null) ControlCenterTone.NEUTRAL else ControlCenterTone.GREEN,
                                showChevron = !runtimeCatalogRefreshInProgress
                            )
                        )
                    ),
                    ControlCenterSectionSpec(getString(R.string.cc_runtime_software_section_catalog), softwareRows),
                    ControlCenterSectionSpec(
                        getString(R.string.cc_runtime_software_section_advanced),
                        listOf(
                            ControlCenterRowSpec(
                                actionId = "runtime.import",
                                title = getString(R.string.cc_runtime_import_title),
                                subtitle = getString(R.string.cc_runtime_import_subtitle),
                                iconRes = R.drawable.ic_import,
                                status = "",
                                tone = ControlCenterTone.NEUTRAL
                            )
                        )
                    )
                ),
                footer = getString(R.string.cc_runtime_software_footer)
            )
        )
    }

    private fun showRuntimeSoftwareSearchDialog() {
        val currentQuery = controlCenterDestination
            ?.takeIf { it.route == ControlCenterRoute.SOFTWARE_CENTER }
            ?.payload
            .orEmpty()
        val input = EditText(this).apply {
            setText(currentQuery)
            hint = getString(R.string.cc_runtime_software_search_hint)
            inputType = InputType.TYPE_CLASS_TEXT
            setSingleLine(true)
            setSelection(text.length)
            setPadding(dp(20), dp(8), dp(20), dp(8))
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.cc_runtime_software_search_title)
            .setView(input)
            .setPositiveButton(R.string.cc_runtime_software_search_action, null)
            .setNegativeButton(R.string.common_cancel, null)
            .setNeutralButton(R.string.cc_runtime_software_clear_search, null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                controlCenterDestination = ControlCenterDestination(
                    ControlCenterRoute.SOFTWARE_CENTER,
                    input.text?.toString().orEmpty().trim()
                )
                dialog.dismiss()
                renderCurrentControlCenterDestination()
            }
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                controlCenterDestination = ControlCenterDestination(ControlCenterRoute.SOFTWARE_CENTER)
                dialog.dismiss()
                renderCurrentControlCenterDestination()
            }
        }
        dialog.show()
    }

    private fun runtimePackTitle(id: String): String = when (id) {
        "linux-base" -> getString(R.string.cc_runtime_pack_linux)
        "python-uv" -> getString(R.string.cc_runtime_pack_python)
        "node-js" -> getString(R.string.cc_runtime_pack_node)
        "go" -> "Go"
        "rust" -> "Rust"
        "cpp" -> "C / C++"
        "java" -> "Java"
        "browser-automation" -> getString(R.string.cc_runtime_pack_browser)
        "ffmpeg" -> "FFmpeg"
        else -> id
    }

    private fun runtimeLanguageTitle(language: AgentRuntimeLanguage): String = when (language) {
        AgentRuntimeLanguage.SHELL -> "Shell"
        AgentRuntimeLanguage.PYTHON -> "Python"
        AgentRuntimeLanguage.UV -> "uv"
        AgentRuntimeLanguage.JAVASCRIPT -> "JavaScript"
        AgentRuntimeLanguage.TYPESCRIPT -> "TypeScript"
        AgentRuntimeLanguage.GO -> "Go"
        AgentRuntimeLanguage.RUST -> "Rust"
        AgentRuntimeLanguage.C -> "C"
        AgentRuntimeLanguage.CPP -> "C++"
        AgentRuntimeLanguage.JAVA -> "Java"
        AgentRuntimeLanguage.BROWSER -> getString(R.string.cc_runtime_pack_browser)
        AgentRuntimeLanguage.FFMPEG -> "FFmpeg"
        AgentRuntimeLanguage.FFPROBE -> "ffprobe"
    }

    private fun runtimeReceiptStatus(status: AgentRuntimeReceiptStatus): String = getString(
        when (status) {
            AgentRuntimeReceiptStatus.RUNNING -> R.string.cc_runtime_receipt_running
            AgentRuntimeReceiptStatus.COMPLETED -> R.string.cc_runtime_receipt_completed
            AgentRuntimeReceiptStatus.FAILED -> R.string.cc_runtime_receipt_failed
            AgentRuntimeReceiptStatus.CANCELLED -> R.string.cc_runtime_receipt_cancelled
            AgentRuntimeReceiptStatus.TIMED_OUT -> R.string.cc_runtime_receipt_timed_out
        }
    )

    private fun runtimeLifecycleLabel(phase: AgentRuntimeLifecyclePhase): String = getString(
        when (phase) {
            AgentRuntimeLifecyclePhase.BLOCKED -> R.string.cc_runtime_lifecycle_blocked
            AgentRuntimeLifecyclePhase.STOPPED -> R.string.cc_runtime_lifecycle_stopped
            AgentRuntimeLifecyclePhase.STARTING -> R.string.cc_runtime_lifecycle_starting
            AgentRuntimeLifecyclePhase.READY -> R.string.cc_runtime_lifecycle_ready
            AgentRuntimeLifecyclePhase.DEGRADED -> R.string.cc_runtime_lifecycle_degraded
            AgentRuntimeLifecyclePhase.BACKING_OFF -> R.string.cc_runtime_lifecycle_backing_off
            AgentRuntimeLifecyclePhase.STOPPING -> R.string.cc_runtime_lifecycle_stopping
        }
    )

    private fun showRuntimeLifecycleDialog() {
        val snapshot = AgentOnDeviceRuntimeLifecycle.inspect(this)
        val nextAttempt = snapshot.nextAttemptAtMillis.takeIf { it > 0L }?.let(::listTime)
            ?: getString(R.string.cc_runtime_lifecycle_not_scheduled)
        val builder = AlertDialog.Builder(this)
            .setTitle(R.string.cc_runtime_lifecycle_title)
            .setMessage(
                getString(
                    R.string.cc_runtime_lifecycle_details,
                    runtimeLifecycleLabel(snapshot.phase),
                    snapshot.reason.ifBlank { getString(R.string.status_unknown) },
                    snapshot.controllerId.ifBlank { getString(R.string.cc_runtime_lifecycle_no_controller) },
                    snapshot.consecutiveFailures,
                    nextAttempt
                )
            )
            .setNegativeButton(R.string.common_cancel, null)
        when (snapshot.phase) {
            AgentRuntimeLifecyclePhase.READY,
            AgentRuntimeLifecyclePhase.DEGRADED -> {
                builder.setPositiveButton(R.string.cc_runtime_lifecycle_restart) { _, _ ->
                    runRuntimeLifecycleOperation(restart = true, stop = false)
                }
                builder.setNeutralButton(R.string.cc_runtime_lifecycle_stop) { _, _ ->
                    runRuntimeLifecycleOperation(restart = false, stop = true)
                }
            }
            AgentRuntimeLifecyclePhase.STOPPED,
            AgentRuntimeLifecyclePhase.BACKING_OFF -> builder.setPositiveButton(
                R.string.cc_runtime_lifecycle_start
            ) { _, _ -> runRuntimeLifecycleOperation(restart = false, stop = false) }
            AgentRuntimeLifecyclePhase.BLOCKED,
            AgentRuntimeLifecyclePhase.STARTING,
            AgentRuntimeLifecyclePhase.STOPPING -> Unit
        }
        builder.show()
    }

    private fun runRuntimeLifecycleOperation(restart: Boolean, stop: Boolean) {
        Toast.makeText(this, R.string.cc_runtime_lifecycle_working, Toast.LENGTH_SHORT).show()
        thread(name = "signalasi-runtime-lifecycle") {
            val outcome = runCatching {
                when {
                    stop -> AgentOnDeviceRuntimeLifecycle.stop(this)
                    restart -> AgentOnDeviceRuntimeLifecycle.restart(this)
                    else -> AgentOnDeviceRuntimeLifecycle.start(this, force = true)
                }
            }
            runOnUiThread {
                Toast.makeText(
                    this,
                    outcome.fold(
                        onSuccess = { result ->
                            getString(
                                R.string.cc_runtime_lifecycle_result,
                                runtimeLifecycleLabel(result.phase),
                                result.reason
                            )
                        },
                        onFailure = { error ->
                            getString(
                                R.string.cc_runtime_lifecycle_failed,
                                error.message ?: getString(R.string.status_unknown)
                            )
                        }
                    ),
                    Toast.LENGTH_LONG
                ).show()
                renderRuntimeControlCenterIfVisible()
            }
        }
    }

    private fun scheduleRuntimeLifecycleStartup() {
        thread(name = "signalasi-runtime-autostart") {
            runCatching { AgentEmbeddedRuntimeBootstrap.ensureInstalled(this) }
            runCatching { AgentOnDeviceRuntimeLifecycle.ensureRunning(this) }
            runOnUiThread {
                renderRuntimeControlCenterIfVisible()
            }
        }
    }

    private fun showRuntimeReceiptDialog(requestId: String) {
        val receipt = AgentRuntimeExecutionReceiptStore(this).find(requestId) ?: return
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.cc_runtime_receipt_title, runtimeLanguageTitle(receipt.language)))
            .setMessage(
                getString(
                    R.string.cc_runtime_receipt_details,
                    receipt.requestId,
                    runtimeReceiptStatus(receipt.status),
                    receipt.sourceSha256,
                    receipt.packVersions.entries.joinToString { "${it.key} ${it.value}" }.ifBlank { "-" },
                    receipt.exitCode?.toString() ?: "-",
                    receipt.artifacts.size,
                    receipt.error.ifBlank { "-" }
                )
            )
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun refreshRuntimePackCatalog() {
        if (runtimeCatalogRefreshInProgress) return
        runtimeCatalogRefreshInProgress = true
        renderRuntimeControlCenterIfVisible()
        thread(name = "signalasi-runtime-catalog-refresh") {
            val outcome = runCatching { agentRuntimePackCatalogManager.refresh() }
            runOnUiThread {
                runtimeCatalogRefreshInProgress = false
                val requestedPackId = pendingRuntimeCatalogPackId
                pendingRuntimeCatalogPackId = null
                outcome.fold(
                    onSuccess = { catalog ->
                        val requestedEntry = requestedPackId?.let { packId ->
                            agentRuntimePackCatalogManager.cachedCompatible()
                                .firstOrNull { it.packId == packId }
                        }
                        when {
                            requestedEntry != null -> installRuntimeCatalogPack(requestedEntry)
                            requestedPackId != null -> Toast.makeText(
                                this,
                                getString(
                                    R.string.cc_runtime_catalog_pack_unavailable,
                                    runtimePackTitle(requestedPackId)
                                ),
                                Toast.LENGTH_LONG
                            ).show()
                            else -> Toast.makeText(
                                this,
                                getString(R.string.cc_runtime_catalog_refresh_success, catalog.catalogVersion),
                                Toast.LENGTH_LONG
                            ).show()
                        }
                    },
                    onFailure = { error ->
                        Toast.makeText(
                            this,
                            getString(
                                R.string.cc_runtime_catalog_refresh_failed,
                                error.message ?: getString(R.string.status_unknown)
                            ),
                            Toast.LENGTH_LONG
                        ).show()
                    }
                )
                renderRuntimeControlCenterIfVisible()
            }
        }
    }

    private fun autoInstallRuntimePack(packId: String) {
        if (packId !in AgentOnDeviceRuntimeManager.REQUIRED_PACKS) {
            Toast.makeText(
                this,
                getString(R.string.cc_runtime_catalog_pack_unavailable, runtimePackTitle(packId)),
                Toast.LENGTH_LONG
            ).show()
            return
        }
        val entry = agentRuntimePackCatalogManager.cachedCompatible()
            .firstOrNull { it.packId == packId }
        if (entry != null) {
            installRuntimeCatalogPack(entry)
            return
        }
        pendingRuntimeCatalogPackId = packId
        Toast.makeText(
            this,
            getString(R.string.cc_runtime_install_loading_catalog, runtimePackTitle(packId)),
            Toast.LENGTH_SHORT
        ).show()
        refreshRuntimePackCatalog()
    }

    private fun renderRuntimeControlCenterIfVisible() {
        when (controlCenterDestination?.route) {
            ControlCenterRoute.ON_DEVICE_RUNTIME -> renderControlCenterRuntimePage()
            ControlCenterRoute.SOFTWARE_CENTER -> renderControlCenterSoftwareCenterPage(
                controlCenterDestination?.payload.orEmpty()
            )
            else -> Unit
        }
    }

    private fun showRuntimeCatalogPackDialog(packId: String) {
        val entry = agentRuntimePackCatalogManager.cachedCompatible()
            .firstOrNull { it.packId == packId } ?: return
        val installed = AgentOnDeviceRuntimeManager(this).status().packs.firstOrNull { it.id == packId }
        val sameVersion = installed?.state == AgentRuntimePackState.READY &&
            installed.manifest?.version == entry.version
        val dependencies = entry.dependencies.joinToString { runtimePackTitle(it) }
            .ifBlank { getString(R.string.cc_runtime_catalog_no_dependencies) }
        val message = getString(
            R.string.cc_runtime_catalog_details,
            entry.version,
            entry.architecture,
            formatBytes(entry.archiveSizeBytes),
            formatBytes(entry.installedSizeBytes),
            entry.license,
            dependencies,
            entry.releaseNotes.ifBlank { getString(R.string.cc_runtime_catalog_no_release_notes) }
        )
        AlertDialog.Builder(this)
            .setTitle(runtimePackTitle(packId))
            .setMessage(message)
            .setPositiveButton(
                if (sameVersion) R.string.cc_runtime_catalog_reinstall else R.string.cc_runtime_catalog_install
            ) { _, _ -> installRuntimeCatalogPack(entry) }
            .setNegativeButton(R.string.common_cancel, null)
            .show()
    }

    private fun installRuntimeCatalogPack(entry: AgentRuntimePackCatalogEntry) {
        if (runtimePackInstallInProgressId != null) return
        val manager = agentRuntimePackCatalogManager
        val plan = runCatching { manager.installationPlan(entry) }.getOrElse { error ->
            Toast.makeText(
                this,
                getString(R.string.cc_runtime_install_failed, error.message ?: getString(R.string.status_unknown)),
                Toast.LENGTH_LONG
            ).show()
            return
        }
        runtimeCatalogRefreshInProgress = false
        pendingRuntimeCatalogPackId = null
        runtimePackInstallInProgressId = entry.packId
        val installedById = AgentOnDeviceRuntimeManager(this).packStatuses().associateBy(AgentRuntimePackStatus::id)
        val pending = plan.filter { item ->
            item.packId == entry.packId || installedById[item.packId]?.let { installed ->
                installed.state != AgentRuntimePackState.READY || installed.manifest?.version != item.version
            } != false
        }
        val cancellation = AgentNativeToolCancellationSource()
        val progressText = TextView(this).apply {
            setPadding(dp(24), dp(8), dp(24), dp(8))
            setTextColor(Color.rgb(43, 48, 58))
            textSize = 15f
            text = getString(R.string.cc_runtime_install_progress_preparing)
        }
        val progressDialog = AlertDialog.Builder(this)
            .setTitle(getString(R.string.cc_runtime_install_progress_title))
            .setView(progressText)
            .setNegativeButton(R.string.common_cancel) { _, _ -> cancellation.cancel() }
            .create()
        progressDialog.setCanceledOnTouchOutside(false)
        progressDialog.setOnCancelListener { cancellation.cancel() }
        progressDialog.show()

        var lastProgressKey = ""
        fun updateProgress(pack: AgentRuntimePackCatalogEntry, index: Int, stage: String, percent: Int) {
            val boundedPercent = percent.coerceIn(0, 100)
            val progressKey = "${pack.packId}:$index:$stage:$boundedPercent"
            if (progressKey == lastProgressKey) return
            lastProgressKey = progressKey
            runOnUiThread {
                if (progressDialog.isShowing) {
                    progressText.text = getString(
                        R.string.cc_runtime_install_progress,
                        runtimePackTitle(pack.packId),
                        index + 1,
                        pending.size,
                        stage,
                        boundedPercent
                    )
                }
            }
        }

        thread(name = "signalasi-runtime-catalog-install") {
            val outcome = runCatching {
                pending.forEachIndexed { index, pack ->
                    if (cancellation.token.isCancellationRequested) throw AgentNativeToolCancelledException()
                    val result = manager.downloadAndInstall(
                        pack,
                        cancellation.token,
                        onDownloadProgress = { progress ->
                            val percent = if (progress.totalBytes > 0L) {
                                ((progress.downloadedBytes * 80L) / progress.totalBytes).toInt()
                            } else 0
                            updateProgress(
                                pack,
                                index,
                                getString(R.string.cc_runtime_install_stage_downloading),
                                percent
                            )
                        },
                        onInstallProgress = { progress ->
                            val percent = when (progress.stage) {
                                AgentRuntimePackInstallStage.PREPARING -> 81
                                AgentRuntimePackInstallStage.COPYING -> 83
                                AgentRuntimePackInstallStage.EXTRACTING -> 88
                                AgentRuntimePackInstallStage.VERIFYING -> 94
                                AgentRuntimePackInstallStage.ACTIVATING -> 98
                                AgentRuntimePackInstallStage.COMPLETED -> 100
                            }
                            updateProgress(
                                pack,
                                index,
                                getString(runtimeInstallStageLabel(progress.stage)),
                                percent
                            )
                        }
                    )
                    check(result.state == AgentRuntimePackState.READY) {
                        result.reason.ifBlank { "Runtime pack did not become ready" }
                    }
                }
            }
            runOnUiThread {
                if (progressDialog.isShowing) progressDialog.dismiss()
                runtimePackInstallInProgressId = null
                runtimeCatalogRefreshInProgress = false
                pendingRuntimeCatalogPackId = null
                val installedSuccessfully = outcome.isSuccess
                val message = outcome.fold(
                    onSuccess = {
                        getString(R.string.cc_runtime_install_success, runtimePackTitle(entry.packId), entry.version)
                    },
                    onFailure = { error ->
                        if (error is AgentNativeToolCancelledException) {
                            getString(R.string.cc_runtime_install_cancelled)
                        } else {
                            getString(
                                R.string.cc_runtime_install_failed,
                                error.message ?: getString(R.string.status_unknown)
                            )
                        }
                    }
                )
                Toast.makeText(this, message, Toast.LENGTH_LONG).show()
                renderRuntimeControlCenterIfVisible()
                if (installedSuccessfully) scheduleRuntimeLifecycleStartup()
            }
        }
    }

    private fun runtimeInstallStageLabel(stage: AgentRuntimePackInstallStage): Int = when (stage) {
        AgentRuntimePackInstallStage.PREPARING -> R.string.cc_runtime_install_stage_preparing
        AgentRuntimePackInstallStage.COPYING -> R.string.cc_runtime_install_stage_copying
        AgentRuntimePackInstallStage.EXTRACTING -> R.string.cc_runtime_install_stage_extracting
        AgentRuntimePackInstallStage.VERIFYING -> R.string.cc_runtime_install_stage_verifying
        AgentRuntimePackInstallStage.ACTIVATING -> R.string.cc_runtime_install_stage_activating
        AgentRuntimePackInstallStage.COMPLETED -> R.string.cc_runtime_install_stage_completed
    }

    private fun openRuntimePackPicker() {
        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = AgentRuntimePackInstaller.PACKAGE_MIME_TYPE
            putExtra(
                Intent.EXTRA_MIME_TYPES,
                arrayOf(AgentRuntimePackInstaller.PACKAGE_MIME_TYPE, "application/zip", "application/octet-stream")
            )
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        }, REQUEST_IMPORT_RUNTIME_PACK)
    }

    private fun importRuntimePackFromUri(uri: Uri) {
        runCatching {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        Toast.makeText(this, getString(R.string.cc_runtime_installing), Toast.LENGTH_SHORT).show()
        thread(name = "signalasi-runtime-pack-install") {
            val outcome = runCatching { AgentRuntimePackInstaller(this).install(uri) }
            runOnUiThread {
                outcome.fold(
                    onSuccess = { result ->
                        scheduleRuntimeLifecycleStartup()
                        val message = if (result.state == AgentRuntimePackState.READY) {
                            getString(R.string.cc_runtime_install_success, runtimePackTitle(result.packId), result.version)
                        } else {
                            getString(
                                R.string.cc_runtime_install_incomplete,
                                runtimePackTitle(result.packId),
                                result.reason.ifBlank { getString(R.string.status_needs_setup) }
                            )
                        }
                        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
                    },
                    onFailure = { error ->
                        Toast.makeText(
                            this,
                            getString(R.string.cc_runtime_install_failed, error.message ?: getString(R.string.status_unknown)),
                            Toast.LENGTH_LONG
                        ).show()
                    }
                )
                renderRuntimeControlCenterIfVisible()
            }
        }
    }

    private fun showRuntimePackDialog(packId: String) {
        val pack = AgentOnDeviceRuntimeManager(this).status().packs.firstOrNull { it.id == packId } ?: return
        val manifest = pack.manifest
        val message = getString(
            R.string.cc_runtime_pack_details_message,
            manifest?.version.orEmpty().ifBlank { getString(R.string.status_unknown) },
            manifest?.architecture.orEmpty().ifBlank { getString(R.string.status_unknown) },
            formatBytes(manifest?.installedSizeBytes ?: 0L),
            manifest?.license.orEmpty().ifBlank { getString(R.string.status_unknown) }
        )
        AlertDialog.Builder(this)
            .setTitle(runtimePackTitle(packId))
            .setMessage(message)
            .setPositiveButton(R.string.cc_runtime_uninstall) { _, _ -> confirmRuntimePackUninstall(packId) }
            .setNegativeButton(R.string.common_cancel, null)
            .show()
    }

    private fun confirmRuntimePackUninstall(packId: String) {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.cc_runtime_uninstall_title, runtimePackTitle(packId)))
            .setMessage(R.string.cc_runtime_uninstall_message)
            .setPositiveButton(R.string.cc_runtime_uninstall) { _, _ ->
                thread(name = "signalasi-runtime-pack-uninstall") {
                    val outcome = runCatching { AgentRuntimePackInstaller(this).uninstall(packId) }
                    runOnUiThread {
                        Toast.makeText(
                            this,
                            outcome.fold(
                                onSuccess = {
                                    if (packId != "linux-base") scheduleRuntimeLifecycleStartup()
                                    getString(R.string.cc_runtime_uninstall_success, runtimePackTitle(packId))
                                },
                                onFailure = { getString(R.string.cc_runtime_uninstall_failed, it.message ?: getString(R.string.status_unknown)) }
                            ),
                            Toast.LENGTH_LONG
                        ).show()
                        renderRuntimeControlCenterIfVisible()
                    }
                }
            }
            .setNegativeButton(R.string.common_cancel, null)
            .show()
    }

    private fun showPermissionModeSettingsPage() {
        val selected = mobileNativeAgent.safetySettings().permissionMode
        showControlCenterFeature(
            getString(R.string.on_device_agent_permission_mode),
            ControlCenterPageSpec(
                banner = ControlCenterBannerSpec(
                    getString(R.string.cc_permission_mode_banner_title),
                    getString(R.string.cc_permission_mode_banner_subtitle),
                    R.drawable.ic_security_shield,
                    ControlCenterTone.BLUE
                ),
                sections = listOf(
                    ControlCenterSectionSpec(
                        getString(R.string.cc_permission_mode_section),
                        PermissionMode.entries.map { mode ->
                            val isSelected = mode == selected
                            ControlCenterRowSpec(
                                actionId = if (isSelected) "" else "agent.permission_mode:${mode.name}",
                                title = permissionModeLabel(mode),
                                subtitle = permissionModeDescription(mode),
                                iconRes = R.drawable.ic_security_shield,
                                status = if (isSelected) getString(R.string.settings_language_selected) else "",
                                tone = if (isSelected) ControlCenterTone.GREEN else ControlCenterTone.NEUTRAL,
                                showChevron = false
                            )
                        }
                    )
                )
            )
        )
    }

    private fun permissionModeDescription(mode: PermissionMode): String = getString(
        when (mode) {
            PermissionMode.OBSERVE_ONLY -> R.string.cc_permission_observe_subtitle
            PermissionMode.SUGGEST_ONLY -> R.string.cc_permission_suggest_subtitle
            PermissionMode.ASK_BEFORE_ACTION -> R.string.cc_permission_ask_subtitle
            PermissionMode.AUTO_LOW_RISK -> R.string.cc_permission_auto_subtitle
        }
    )

    private fun showAgentPlannerSettingsPage() {
        val settings = mobileNativeAgent.modelPlannerSettings()
        val sources = configuredAgentPlannerSources()
        val plannerReady = !settings.enabled || sources.isNotEmpty()
        showControlCenterFeature(
            getString(R.string.cc_planner_settings_title),
            ControlCenterPageSpec(
                banner = ControlCenterBannerSpec(
                    getString(if (settings.enabled) R.string.on_device_agent_model_planner else R.string.cc_planner_local_title),
                    getString(
                        when {
                            !settings.enabled -> R.string.cc_planner_local_subtitle
                            plannerReady -> R.string.cc_planner_ready_subtitle
                            else -> R.string.cc_planner_needs_model_subtitle
                        }
                    ),
                    R.drawable.ic_agent_control,
                    if (plannerReady) ControlCenterTone.BLUE else ControlCenterTone.AMBER
                ),
                sections = listOf(
                    ControlCenterSectionSpec(
                        getString(R.string.on_device_agent_section_intelligence),
                        listOf(
                            ControlCenterRowSpec("agent.planner.toggle_enabled", getString(R.string.on_device_agent_model_planner), getString(R.string.on_device_agent_model_planner_subtitle), R.drawable.ic_agent_node, switchValue = settings.enabled, showChevron = false),
                            ControlCenterRowSpec(if (sources.isEmpty()) "routing.add_cloud" else "agent.planner.model_source", getString(R.string.on_device_agent_model_source), getString(R.string.on_device_agent_model_source_subtitle), R.drawable.ic_protocol_link, if (sources.isEmpty()) getString(R.string.status_needs_setup) else agentModelPlannerSourceLabel(settings.cloudContactId), if (plannerReady) ControlCenterTone.BLUE else ControlCenterTone.AMBER),
                            ControlCenterRowSpec("agent.planner.toggle_replanning", getString(R.string.on_device_agent_dynamic_replanning), getString(R.string.on_device_agent_dynamic_replanning_subtitle), R.drawable.ic_agent_control, switchValue = settings.dynamicReplanning, showChevron = false),
                            ControlCenterRowSpec("agent.planner.max_replans", getString(R.string.on_device_agent_max_replans), getString(R.string.on_device_agent_max_replans_subtitle), R.drawable.ic_reset_data, settings.maxReplans.toString(), ControlCenterTone.BLUE),
                            ControlCenterRowSpec("agent.planner.toggle_multi_agent", getString(R.string.on_device_agent_multi_agent_coordination), getString(R.string.on_device_agent_multi_agent_coordination_subtitle), R.drawable.ic_protocol_link, switchValue = settings.multiAgentCoordination, showChevron = false)
                        )
                    ),
                    ControlCenterSectionSpec(
                        getString(R.string.cc_section_task_control),
                        listOf(
                            ControlCenterRowSpec("agent.planner.max_actions", getString(R.string.on_device_agent_model_max_actions), getString(R.string.on_device_agent_model_max_actions_subtitle), R.drawable.ic_agent_history, settings.maxActions.toString(), ControlCenterTone.BLUE),
                            ControlCenterRowSpec("agent.planner.max_tools", getString(R.string.on_device_agent_max_tool_calls), getString(R.string.on_device_agent_max_tool_calls_subtitle), R.drawable.ic_agent_control, settings.maxToolCalls.toString(), ControlCenterTone.VIOLET),
                            ControlCenterRowSpec("agent.planner.max_hops", getString(R.string.on_device_agent_max_agent_hops), getString(R.string.on_device_agent_max_agent_hops_subtitle), R.drawable.ic_protocol_link, settings.maxAgentHops.toString(), ControlCenterTone.AMBER)
                        )
                    ),
                    ControlCenterSectionSpec(
                        getString(R.string.cc_section_privacy_boundary),
                        listOf(
                            ControlCenterRowSpec("agent.planner.toggle_screen_text", getString(R.string.on_device_agent_model_screen_text), getString(R.string.on_device_agent_model_screen_text_subtitle), R.drawable.ic_scan, switchValue = settings.shareScreenText, showChevron = false),
                            ControlCenterRowSpec("agent.planner.toggle_share_outputs", getString(R.string.on_device_agent_share_agent_outputs), getString(R.string.on_device_agent_share_agent_outputs_subtitle), R.drawable.ic_security_shield, switchValue = settings.shareAgentOutputsWithPlanner, showChevron = false)
                        )
                    )
                )
            )
        )
    }

    private fun renderControlCenterExecutionPolicyPage() {
        val safety = mobileNativeAgent.safetySettings()
        val planner = mobileNativeAgent.modelPlannerSettings()
        val privacyProtected = !planner.shareScreenText && !planner.shareAgentOutputsWithPlanner
        val notificationsEnabled = appNotificationsEnabled()
        showControlCenterFeature(
            getString(R.string.cc_execution_policy_title),
            ControlCenterPageSpec(
                sections = listOf(
                    ControlCenterSectionSpec(
                        getString(R.string.cc_permission_mode_section),
                        listOf(
                            ControlCenterRowSpec("agent.permission_mode", getString(R.string.on_device_agent_permission_mode), getString(R.string.on_device_agent_permission_mode_subtitle), R.drawable.ic_security_shield, permissionModeLabel(safety.permissionMode), ControlCenterTone.BLUE),
                            ControlCenterRowSpec("security.toggle_guard", getString(R.string.on_device_agent_high_risk_guard), getString(R.string.on_device_agent_high_risk_guard_subtitle), R.drawable.ic_security_shield, switchValue = safety.highRiskGuard, showChevron = false)
                        )
                    ),
                    ControlCenterSectionSpec(
                        getString(R.string.cc_section_confirmation_rules),
                        listOf(
                            ControlCenterRowSpec("", getString(R.string.cc_direct_execution_title), getString(R.string.cc_direct_execution_subtitle), R.drawable.ic_send_plane, getString(R.string.cc_status_direct), ControlCenterTone.GREEN, showChevron = false),
                            ControlCenterRowSpec("", getString(R.string.cc_first_confirm_title), getString(R.string.cc_first_confirm_subtitle), R.drawable.ic_info_outline, getString(R.string.cc_status_ask), ControlCenterTone.AMBER, showChevron = false),
                            ControlCenterRowSpec("", getString(R.string.cc_always_confirm_title), getString(R.string.cc_always_confirm_subtitle), R.drawable.ic_security_shield, getString(R.string.common_confirm), ControlCenterTone.RED, showChevron = false)
                        )
                    ),
                    ControlCenterSectionSpec(
                        getString(R.string.cc_section_task_control),
                        listOf(
                            ControlCenterRowSpec("", getString(R.string.cc_max_concurrency_title), getString(R.string.cc_max_concurrency_subtitle), R.drawable.ic_agent_history, "3 + 1", ControlCenterTone.BLUE, showChevron = false),
                            ControlCenterRowSpec("agent.planner", getString(R.string.cc_tool_budget_title), getString(R.string.cc_tool_budget_subtitle), R.drawable.ic_agent_control, planner.maxToolCalls.toString(), ControlCenterTone.VIOLET),
                            ControlCenterRowSpec("general.notifications", getString(R.string.cc_long_task_notifications_title), getString(R.string.cc_long_task_notifications_subtitle), R.drawable.ic_settings_notification, getString(if (notificationsEnabled) R.string.status_enabled else R.string.status_needs_setup), if (notificationsEnabled) ControlCenterTone.GREEN else ControlCenterTone.AMBER)
                        )
                    ),
                    ControlCenterSectionSpec(
                        getString(R.string.cc_section_privacy_boundary),
                        listOf(ControlCenterRowSpec("agent.planner", getString(R.string.cc_sensitive_local_title), getString(R.string.cc_sensitive_local_subtitle), R.drawable.ic_security_shield, getString(if (privacyProtected) R.string.status_enabled else R.string.cc_status_review), if (privacyProtected) ControlCenterTone.GREEN else ControlCenterTone.AMBER))
                    )
                )
            )
        )
    }

    private fun renderControlCenterRoutingPage() {
        val targets = controlCenterResourceTargets(mobileNativeAgent.snapshot().callableTargets)
        val available = targets.count { it.status == AgentConnectorStatus.AVAILABLE }
        val resourceRows = targets
            .filterNot { it.id == "phone" || it.id == "local-system" }
            .distinctBy { it.id }
            .sortedWith(compareBy<AgentCallableTarget> { it.status != AgentConnectorStatus.AVAILABLE }.thenBy { it.title.lowercase(Locale.ROOT) })
            .take(12)
            .map { target ->
                ControlCenterRowSpec(
                    actionId = "routing.target:${target.id}",
                    title = target.title,
                    subtitle = controlCenterTargetSubtitle(target),
                    iconRes = controlCenterTargetIcon(target),
                    status = controlCenterTargetStatus(target.status),
                    tone = controlCenterTargetTone(target.status),
                    preserveIconColor = target.id.contains("codex", true) || target.id.contains("claude", true) || target.id.contains("hermes", true)
                )
            }
        showControlCenterFeature(
            getString(R.string.cc_resource_routing_title),
            ControlCenterPageSpec(
                banner = ControlCenterBannerSpec(
                    title = if (available > 0) getString(R.string.cc_routing_enabled) else getString(R.string.cc_no_resources_title),
                    subtitle = if (available > 0) getString(R.string.cc_routing_enabled_subtitle) else getString(R.string.cc_no_resources_subtitle),
                    iconRes = R.drawable.ic_settings_model,
                    tone = if (available > 0) ControlCenterTone.BLUE else ControlCenterTone.AMBER
                ),
                sections = listOf(
                    ControlCenterSectionSpec(
                        getString(R.string.cc_section_current_strategy),
                        listOf(ControlCenterRowSpec("routing.policy", getString(R.string.cc_balanced_strategy_title), getString(R.string.cc_balanced_strategy_subtitle), R.drawable.ic_agent_control, getString(R.string.cc_status_automatic), ControlCenterTone.BLUE))
                    ),
                    ControlCenterSectionSpec(
                        getString(R.string.cc_section_available_resources),
                        resourceRows.ifEmpty {
                            listOf(ControlCenterRowSpec("routing.add_cloud", getString(R.string.cc_add_cloud_provider_title), getString(R.string.cc_add_cloud_provider_subtitle), R.drawable.ic_avatar_cloud_model, getString(R.string.common_next_step), ControlCenterTone.AMBER))
                        }
                    ),
                    ControlCenterSectionSpec(
                        getString(R.string.cc_section_rules),
                        listOf(
                            ControlCenterRowSpec("routing.policy", getString(R.string.cc_route_by_task_title), getString(R.string.cc_route_by_task_subtitle), R.drawable.ic_protocol_link, getString(R.string.common_view), ControlCenterTone.NEUTRAL),
                            ControlCenterRowSpec("routing.add_cloud", getString(R.string.cc_add_cloud_provider_title), getString(R.string.cc_add_cloud_provider_subtitle), R.drawable.ic_avatar_cloud_model, "+", ControlCenterTone.VIOLET),
                            ControlCenterRowSpec("routing.manage", getString(R.string.cc_nodes_title), getString(R.string.cc_nodes_subtitle), R.drawable.ic_agent_node, "", ControlCenterTone.BLUE)
                        )
                    )
                )
            )
        )
    }

    private fun showRoutingPolicyPage() {
        showControlCenterFeature(
            getString(R.string.cc_route_by_task_title),
            ControlCenterPageSpec(
                banner = ControlCenterBannerSpec(
                    getString(R.string.cc_routing_policy_banner_title),
                    getString(R.string.cc_routing_policy_banner_subtitle),
                    R.drawable.ic_agent_control,
                    ControlCenterTone.BLUE
                ),
                sections = listOf(
                    ControlCenterSectionSpec(
                        getString(R.string.cc_section_current_strategy),
                        listOf(
                            routingPolicyInfoRow(R.string.cc_routing_balanced_title, R.string.cc_routing_balanced_subtitle, ControlCenterTone.BLUE),
                            routingPolicyInfoRow(R.string.cc_routing_fast_title, R.string.cc_routing_fast_subtitle, ControlCenterTone.GREEN),
                            routingPolicyInfoRow(R.string.cc_routing_economy_title, R.string.cc_routing_economy_subtitle, ControlCenterTone.AMBER),
                            routingPolicyInfoRow(R.string.cc_routing_quality_title, R.string.cc_routing_quality_subtitle, ControlCenterTone.VIOLET),
                            routingPolicyInfoRow(R.string.cc_routing_private_title, R.string.cc_routing_private_subtitle, ControlCenterTone.NEUTRAL)
                        )
                    )
                )
            )
        )
    }

    private fun routingPolicyInfoRow(title: Int, subtitle: Int, tone: ControlCenterTone) =
        ControlCenterRowSpec(
            actionId = "",
            title = getString(title),
            subtitle = getString(subtitle),
            iconRes = R.drawable.ic_protocol_link,
            tone = tone,
            showChevron = false
        )

    private fun controlCenterTargetSubtitle(target: AgentCallableTarget): String {
        val capabilities = target.capabilities.take(3).joinToString(" · ") {
            controlCenterCapabilityLabel(it)
        }
        return capabilities.ifBlank { controlCenterTargetKindLabel(target.kind) }
    }

    private fun controlCenterResourceTargets(
        targets: List<AgentCallableTarget>
    ): List<AgentCallableTarget> = targets.filterNot { target ->
        target.id == "cloud-models"
    }

    private fun controlCenterCapabilityLabel(capability: AgentCapability): String = getString(
        when (capability) {
            AgentCapability.CHAT -> R.string.cc_capability_chat
            AgentCapability.REASONING -> R.string.cc_capability_reasoning
            AgentCapability.LIVE_DATA -> R.string.cc_capability_live_data
            AgentCapability.TOOL_USE -> R.string.cc_capability_tool_use
            AgentCapability.MCP -> R.string.cc_capability_mcp
            AgentCapability.SKILL -> R.string.cc_capability_skill
            AgentCapability.LOCAL_INFERENCE -> R.string.cc_capability_local_inference
            AgentCapability.RESEARCH -> R.string.cc_capability_research
            AgentCapability.CODE -> R.string.cc_capability_code
            AgentCapability.TASK_EXECUTION -> R.string.cc_capability_task_execution
            AgentCapability.SMART_HOME -> R.string.cc_capability_smart_home
            AgentCapability.DEVICE_CONTROL -> R.string.cc_capability_device_control
            AgentCapability.KNOWLEDGE_SEARCH -> R.string.cc_capability_knowledge_search
            AgentCapability.SCREEN_READING -> R.string.cc_capability_screen_reading
            AgentCapability.CLIPBOARD -> R.string.cc_capability_clipboard
            AgentCapability.SYSTEM_SETTINGS -> R.string.cc_capability_system_settings
            AgentCapability.APP_NAVIGATION -> R.string.cc_capability_app_navigation
            AgentCapability.ALARM -> R.string.cc_capability_alarm
        }
    )

    private fun controlCenterTargetKindLabel(kind: AgentConnectorKind): String = getString(
        when (kind) {
            AgentConnectorKind.MODEL -> R.string.cc_kind_model
            AgentConnectorKind.AGENT -> R.string.cc_kind_agent
            AgentConnectorKind.DEVICE -> R.string.cc_kind_device
            AgentConnectorKind.KNOWLEDGE -> R.string.cc_kind_knowledge
        }
    )

    private fun controlCenterTargetStatus(status: AgentConnectorStatus): String = getString(
        when (status) {
            AgentConnectorStatus.AVAILABLE -> R.string.cc_status_available
            AgentConnectorStatus.NEEDS_SETUP -> R.string.status_needs_setup
            AgentConnectorStatus.DISCONNECTED -> R.string.status_disconnected
        }
    )

    private fun controlCenterTargetTone(status: AgentConnectorStatus): ControlCenterTone = when (status) {
        AgentConnectorStatus.AVAILABLE -> ControlCenterTone.GREEN
        AgentConnectorStatus.NEEDS_SETUP -> ControlCenterTone.AMBER
        AgentConnectorStatus.DISCONNECTED -> ControlCenterTone.NEUTRAL
    }

    private fun controlCenterTargetIcon(target: AgentCallableTarget): Int = when {
        target.id.contains("codex", true) -> R.drawable.logo_codex_product
        target.id.contains("claude", true) -> R.drawable.logo_claude_code
        target.id.contains("hermes", true) -> R.drawable.ic_avatar_hermes
        target.kind == AgentConnectorKind.MODEL && target.id.startsWith("cloud:") -> R.drawable.ic_avatar_cloud_model
        target.kind == AgentConnectorKind.MODEL -> R.drawable.ic_local_model
        target.kind == AgentConnectorKind.DEVICE -> R.drawable.ic_device_node
        else -> R.drawable.ic_agent_node
    }

    private fun showControlCenterTarget(targetId: String) {
        val target = mobileNativeAgent.snapshot().callableTargets.firstOrNull { it.id == targetId }
        if (target == null) {
            openExistingControlCenterPage { showAgentFeaturePage() }
            return
        }
        when {
            target.id.startsWith("cloud:") -> openExistingControlCenterPage {
                showCloudModelPage(target.id.substringAfter("cloud:"))
            }
            AppStore.contactById(this, target.id) != null -> openExistingControlCenterPage {
                showContactDetail(contactById(target.id))
            }
            else -> openExistingControlCenterPage {
                showFeatureItemPage(
                    target.title,
                    controlCenterTargetSubtitle(target),
                    controlCenterTargetIcon(target),
                    controlCenterTargetStatus(target.status)
                )
            }
        }
    }

    private fun renderControlCenterPhoneCapabilitiesPage() {
        val runtime = mobileNativeAgent.snapshot().runtimeContext
        val tools = runtime.nativeTools
        val capabilityMatrix = runtime.capabilityMatrix
        val available = capabilityMatrix.availableNativeToolIds.size
        val attention = tools.size - available
        showControlCenterFeature(
            getString(R.string.cc_phone_title),
            ControlCenterPageSpec(
                banner = ControlCenterBannerSpec(
                    title = getString(R.string.cc_phone_ready_title, available),
                    subtitle = getString(R.string.cc_phone_ready_subtitle, attention),
                    iconRes = R.drawable.ic_agent_control,
                    tone = if (attention == 0) ControlCenterTone.GREEN else ControlCenterTone.AMBER
                ),
                sections = listOf(
                    ControlCenterSectionSpec(
                        getString(R.string.cc_section_device_control),
                        listOf(
                            ControlCenterRowSpec("phone.catalog", getString(R.string.cc_camera_flash_title), getString(R.string.cc_camera_flash_subtitle), R.drawable.ic_scan, phoneCapabilityStatus(capabilityMatrix, tools, "camera", "torch"), ControlCenterTone.AMBER),
                            ControlCenterRowSpec("phone.catalog", getString(R.string.cc_audio_title), getString(R.string.cc_audio_subtitle), R.drawable.ic_input_voice, phoneCapabilityStatus(capabilityMatrix, tools, "audio", "volume"), ControlCenterTone.BLUE),
                            ControlCenterRowSpec("phone.catalog", getString(R.string.cc_alarm_timer_title), getString(R.string.cc_alarm_timer_subtitle), R.drawable.ic_agent_history, phoneCapabilityStatus(capabilityMatrix, tools, "alarm", "timer"), ControlCenterTone.GREEN),
                            ControlCenterRowSpec("phone.catalog", getString(R.string.cc_network_title), getString(R.string.cc_network_subtitle), R.drawable.ic_protocol_link, phoneCapabilityStatus(capabilityMatrix, tools, "network", "wifi", "bluetooth", "nfc"), ControlCenterTone.VIOLET)
                        )
                    ),
                    ControlCenterSectionSpec(
                        getString(R.string.cc_section_information_system),
                        listOf(
                            ControlCenterRowSpec("phone.catalog", getString(R.string.cc_device_status_title), getString(R.string.cc_device_status_subtitle), R.drawable.ic_device_node, phoneCapabilityStatus(capabilityMatrix, tools, "battery", "storage", "sensor"), ControlCenterTone.GREEN),
                            ControlCenterRowSpec("phone.catalog", getString(R.string.cc_location_title), getString(R.string.cc_location_subtitle), R.drawable.ic_avatar_scan, phoneCapabilityStatus(capabilityMatrix, tools, "location"), ControlCenterTone.AMBER),
                            ControlCenterRowSpec("phone.catalog", getString(R.string.cc_tool_catalog_title), getString(R.string.cc_tool_catalog_subtitle), R.drawable.ic_agent_control, tools.size.toString(), ControlCenterTone.NEUTRAL)
                        )
                    )
                )
            )
        )
    }

    private fun phoneCapabilityStatus(
        capabilityMatrix: AgentRuntimeCapabilitySnapshot,
        tools: List<AgentNativeToolDescriptor>,
        vararg keywords: String
    ): String {
        val matching = tools.filter { tool -> keywords.any { keyword -> tool.id.contains(keyword, true) } }
        val available = matching.count { capabilityMatrix.isNativeToolExecutable(it.id) }
        return when {
            matching.isEmpty() -> getString(R.string.cc_status_not_configured)
            available == matching.size -> getString(R.string.cc_status_available)
            available > 0 -> getString(R.string.cc_status_available_ratio, available, matching.size)
            matching.any { it.availability.status == AgentNativeToolAvailabilityStatus.REQUIRES_SETUP } -> getString(R.string.status_needs_setup)
            else -> getString(R.string.cc_status_unavailable)
        }
    }

    private fun renderControlCenterAppToolsPage() {
        val adapterReadiness = agentAdapterReadiness()
        val adapterCount = adapterReadiness.count { it.value }
        val accessibility = mobileNativeAgent.snapshot().currentScreen.isAccessibilityEnabled
        showControlCenterFeature(
            getString(R.string.cc_app_tools_title),
            ControlCenterPageSpec(
                banner = ControlCenterBannerSpec(
                    title = getString(R.string.cc_adapters_available, adapterCount),
                    subtitle = getString(R.string.cc_adapters_available_subtitle),
                    iconRes = R.drawable.ic_tab_discover,
                    tone = if (accessibility) ControlCenterTone.GREEN else ControlCenterTone.AMBER
                ),
                sections = listOf(
                    ControlCenterSectionSpec(
                        getString(R.string.cc_section_core_modules),
                        listOf(
                            ControlCenterRowSpec("apps.adapters", getString(R.string.cc_manage_adapters_title), getString(R.string.cc_manage_adapters_subtitle), R.drawable.ic_tab_discover, adapterCount.toString(), ControlCenterTone.BLUE),
                            ControlCenterRowSpec("permissions.accessibility", getString(R.string.cc_accessibility_executor_title), getString(R.string.cc_accessibility_executor_subtitle), R.drawable.ic_agent_control, getString(if (accessibility) R.string.status_enabled else R.string.status_needs_setup), if (accessibility) ControlCenterTone.GREEN else ControlCenterTone.AMBER)
                        )
                    ),
                    ControlCenterSectionSpec(
                        getString(R.string.cc_section_app_services),
                        listOf(
                            ControlCenterRowSpec("apps.messages", getString(R.string.cc_messages_title), getString(R.string.cc_messages_subtitle), R.drawable.ic_tab_chat, "", ControlCenterTone.GREEN),
                            ControlCenterRowSpec("apps.contacts", getString(R.string.cc_contacts_title), getString(R.string.cc_contacts_subtitle), R.drawable.ic_tab_contacts_outline, "", ControlCenterTone.BLUE),
                            ControlCenterRowSpec("apps.discover", getString(R.string.cc_discover_title), getString(R.string.cc_discover_subtitle), R.drawable.ic_tab_discover, "", ControlCenterTone.VIOLET)
                        )
                    )
                )
            )
        )
    }

    private fun renderControlCenterSmartSpacesPage() {
        val homeAssistant = HomeAssistantSettingsStore.load(this)
        val homeAssistantReady = homeAssistant.configured
        val customDevices = CustomDeviceConnectorStore(this).list()
        showControlCenterFeature(
            getString(R.string.cc_smart_spaces_title),
            ControlCenterPageSpec(
                hero = ControlCenterHeroSpec(
                    title = getString(R.string.cc_home_assistant_title),
                    subtitle = getString(
                        when {
                            !homeAssistant.credentialsConfigured -> R.string.cc_home_assistant_not_configured
                            homeAssistantReady -> R.string.cc_home_assistant_connected
                            else -> R.string.cc_home_assistant_disabled
                        }
                    ),
                    iconRes = R.drawable.ic_device_node,
                    actionId = "spaces.configure",
                    badges = listOf(ControlCenterBadgeSpec(
                        getString(
                            when {
                                !homeAssistant.credentialsConfigured -> R.string.cc_status_not_configured
                                homeAssistantReady -> R.string.status_enabled
                                else -> R.string.common_off
                            }
                        ),
                        if (homeAssistantReady) ControlCenterTone.GREEN else ControlCenterTone.AMBER
                    )),
                    metrics = listOf(
                        ControlCenterMetricSpec(customDevices.count { it.configured }.toString(), getString(R.string.count_devices, customDevices.size)),
                        ControlCenterMetricSpec(if (homeAssistant.enabled) getString(R.string.common_on) else getString(R.string.common_off), getString(R.string.common_status)),
                        ControlCenterMetricSpec(
                            getString(
                                when {
                                    !homeAssistant.credentialsConfigured -> R.string.status_needs_setup
                                    homeAssistantReady -> R.string.cc_status_ready
                                    else -> R.string.common_off
                                }
                            ),
                            getString(R.string.cc_metric_security)
                        )
                    )
                ),
                sections = listOf(
                    ControlCenterSectionSpec(
                        getString(R.string.cc_smart_spaces_title),
                        listOf(
                            ControlCenterRowSpec("spaces.entities", getString(R.string.cc_home_entities_title), getString(R.string.cc_home_entities_subtitle), R.drawable.ic_group, if (homeAssistantReady) getString(R.string.common_view) else getString(R.string.status_needs_setup), if (homeAssistantReady) ControlCenterTone.GREEN else ControlCenterTone.AMBER, enabled = homeAssistantReady),
                            ControlCenterRowSpec("spaces.automations", getString(R.string.cc_home_automations_title), getString(R.string.cc_home_automations_subtitle), R.drawable.ic_automation_line, if (homeAssistantReady) getString(R.string.common_view) else getString(R.string.status_needs_setup), if (homeAssistantReady) ControlCenterTone.BLUE else ControlCenterTone.AMBER, enabled = homeAssistantReady),
                            ControlCenterRowSpec("spaces.configure", getString(R.string.cc_custom_devices_title), getString(R.string.cc_custom_devices_subtitle), R.drawable.ic_device_node, customDevices.size.toString(), ControlCenterTone.VIOLET),
                            ControlCenterRowSpec(routeAction(ControlCenterRoute.EXECUTION_POLICY), getString(R.string.cc_high_risk_devices_title), getString(R.string.cc_high_risk_devices_subtitle), R.drawable.ic_security_shield, getString(R.string.common_confirm), ControlCenterTone.RED)
                        )
                    )
                )
            )
        )
    }

    private fun renderControlCenterNodesPage() {
        val state = mobileNativeAgent.snapshot()
        val desktops = desktopSecuritySummaries(activePcConnectorContacts())
        val targets = controlCenterResourceTargets(state.callableTargets).distinctBy { it.id }
        val availableTargetIds = targets
            .filter { it.status == AgentConnectorStatus.AVAILABLE }
            .mapTo(linkedSetOf()) { it.id }
        val desktopRows = desktops.map { desktop ->
            val online = desktop.agentIds.any { it in availableTargetIds }
            ControlCenterRowSpec(
                actionId = "node.desktop:${desktop.id}",
                title = desktop.name,
                subtitle = getString(R.string.count_items, desktop.agentCount),
                iconRes = R.drawable.ic_device_node,
                status = getString(if (online) R.string.cc_status_online else R.string.status_disconnected),
                tone = if (online) ControlCenterTone.GREEN else ControlCenterTone.NEUTRAL
            )
        }.ifEmpty {
            listOf(ControlCenterRowSpec("nodes.scan", getString(R.string.cc_no_desktop_title), getString(R.string.cc_no_desktop_subtitle), R.drawable.ic_scan, getString(R.string.security_scan), ControlCenterTone.AMBER))
        }
        val localRows = targets.filter { it.kind == AgentConnectorKind.MODEL && !it.id.startsWith("cloud:") }
            .map { target -> controlCenterTargetRow(target) }
        val cloudRows = targets.filter { it.id.startsWith("cloud:") }
            .map { target -> controlCenterTargetRow(target) }
            .ifEmpty {
                listOf(ControlCenterRowSpec("routing.add_cloud", getString(R.string.cc_add_cloud_provider_title), getString(R.string.cc_add_cloud_provider_subtitle), R.drawable.ic_avatar_cloud_model, "+", ControlCenterTone.VIOLET))
            }
        val available = targets.count { it.status == AgentConnectorStatus.AVAILABLE }
        showControlCenterFeature(
            getString(R.string.cc_nodes_title),
            ControlCenterPageSpec(
                banner = ControlCenterBannerSpec(
                    title = getString(R.string.cc_nodes_ready_title, available),
                    subtitle = getString(R.string.cc_nodes_ready_subtitle),
                    iconRes = R.drawable.ic_protocol_link,
                    tone = if (available > 0) ControlCenterTone.GREEN else ControlCenterTone.AMBER
                ),
                sections = listOf(
                    ControlCenterSectionSpec(getString(R.string.default_desktop_name), desktopRows),
                    ControlCenterSectionSpec(getString(R.string.cc_section_this_device), localRows),
                    ControlCenterSectionSpec(getString(R.string.cc_section_cloud_apis), cloudRows)
                )
            )
        )
    }

    private fun controlCenterTargetRow(target: AgentCallableTarget): ControlCenterRowSpec =
        ControlCenterRowSpec(
            actionId = "routing.target:${target.id}",
            title = target.title,
            subtitle = controlCenterTargetSubtitle(target),
            iconRes = controlCenterTargetIcon(target),
            status = controlCenterTargetStatus(target.status),
            tone = controlCenterTargetTone(target.status),
            preserveIconColor = target.id.contains("codex", true) || target.id.contains("claude", true) || target.id.contains("hermes", true)
        )

    private fun renderControlCenterSecurityPage() {
        val fingerprint = SignalASICrypto.localIdentitySha256()
        val trustedDevices = desktopSecuritySummaries(activePcConnectorContacts()).size
        val trustedContacts = storedContacts().count { AppStore.canCommunicateWith(this, it.id) }
        val guard = mobileNativeAgent.safetySettings().highRiskGuard
        val notificationsEnabled = appNotificationsEnabled()
        showControlCenterFeature(
            getString(R.string.cc_security_title),
            ControlCenterPageSpec(
                banner = ControlCenterBannerSpec(
                    title = getString(R.string.security_privacy_title),
                    subtitle = getString(R.string.security_privacy_subtitle),
                    iconRes = R.drawable.ic_security_shield,
                    tone = ControlCenterTone.GREEN
                ),
                sections = listOf(
                    ControlCenterSectionSpec(
                        getString(R.string.cc_section_identity),
                        listOf(
                            ControlCenterRowSpec("profile.copy_fingerprint", getString(R.string.settings_identity_fingerprint), compactFingerprint(fingerprint), R.drawable.ic_settings_fingerprint, getString(R.string.common_copy), ControlCenterTone.BLUE, showChevron = false),
                            ControlCenterRowSpec("profile.copy_id", getString(R.string.settings_signalasi_id), SignalASICrypto.localSignalasiId(), R.drawable.ic_protocol_link, getString(R.string.common_copy), ControlCenterTone.NEUTRAL, showChevron = false)
                        )
                    ),
                    ControlCenterSectionSpec(
                        getString(R.string.security_section_paired_devices),
                        listOf(
                            ControlCenterRowSpec("security.manage", getString(R.string.settings_trusted_devices), getString(R.string.settings_trusted_devices_subtitle), R.drawable.ic_settings_devices, trustedDevices.toString(), ControlCenterTone.GREEN),
                            ControlCenterRowSpec("apps.contacts", getString(R.string.cc_contacts_title), getString(R.string.cc_contacts_subtitle), R.drawable.ic_tab_contacts_outline, trustedContacts.toString(), ControlCenterTone.VIOLET)
                        )
                    ),
                    ControlCenterSectionSpec(
                        getString(R.string.feature_identity_protection),
                        listOf(
                            ControlCenterRowSpec("security.toggle_guard", getString(R.string.on_device_agent_high_risk_guard), getString(R.string.cc_always_confirm_subtitle), R.drawable.ic_security_shield, switchValue = guard, showChevron = false),
                            ControlCenterRowSpec("general.notifications", getString(R.string.cc_notifications_title), getString(R.string.cc_notifications_subtitle), R.drawable.ic_settings_notification, getString(if (notificationsEnabled) R.string.status_enabled else R.string.status_needs_setup), if (notificationsEnabled) ControlCenterTone.GREEN else ControlCenterTone.AMBER)
                        )
                    )
                )
            )
        )
    }

    private fun renderControlCenterPermissionsPage() {
        val accessibility = SignalASIAccessibilityService.isActive()
        val notificationAccess = SignalASINotificationListenerService.currentContext().hasAccess
        val microphone = checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        val camera = checkSelfPermission(android.Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        val granted = listOf(accessibility, notificationAccess, microphone, camera).count { it }
        showControlCenterFeature(
            getString(R.string.cc_permissions_title),
            ControlCenterPageSpec(
                hero = ControlCenterHeroSpec(
                    title = getString(R.string.cc_permissions_title),
                    subtitle = getString(R.string.cc_permissions_summary, granted, 4),
                    iconRes = R.drawable.ic_settings_fingerprint,
                    badges = listOf(ControlCenterBadgeSpec(permissionModeLabel(mobileNativeAgent.safetySettings().permissionMode), ControlCenterTone.BLUE)),
                    metrics = listOf(
                        ControlCenterMetricSpec("$granted/4", getString(R.string.cc_section_android_permissions)),
                        ControlCenterMetricSpec(mobileNativeAgent.snapshot().auditTrail.size.toString(), getString(R.string.feature_audit_log)),
                        ControlCenterMetricSpec(getString(R.string.cc_status_secure), getString(R.string.cc_metric_security))
                    )
                ),
                sections = listOf(
                    ControlCenterSectionSpec(
                        getString(R.string.cc_section_android_permissions),
                        listOf(
                            permissionRow("permissions.accessibility", R.string.cc_accessibility_title, R.string.cc_accessibility_subtitle, R.drawable.ic_agent_control, accessibility),
                            permissionRow("permissions.notifications", R.string.cc_notification_access_title, R.string.cc_notification_access_subtitle, R.drawable.ic_settings_notification, notificationAccess),
                            permissionRow("permissions.microphone", R.string.cc_microphone_permission_title, R.string.cc_microphone_permission_subtitle, R.drawable.ic_input_voice, microphone),
                            permissionRow("permissions.camera", R.string.cc_camera_permission_title, R.string.cc_camera_permission_subtitle, R.drawable.ic_scan, camera)
                        )
                    ),
                    ControlCenterSectionSpec(
                        getString(R.string.feature_audit_log),
                        listOf(ControlCenterRowSpec("audit.operations", getString(R.string.cc_recent_operations_title), getString(R.string.cc_recent_operations_subtitle), R.drawable.ic_agent_history, getString(R.string.common_view), ControlCenterTone.VIOLET))
                    )
                )
            )
        )
    }

    private fun permissionRow(action: String, title: Int, subtitle: Int, icon: Int, granted: Boolean) =
        ControlCenterRowSpec(
            actionId = action,
            title = getString(title),
            subtitle = getString(subtitle),
            iconRes = icon,
            status = getString(if (granted) R.string.permission_allowed else R.string.permission_needs_setup),
            tone = if (granted) ControlCenterTone.GREEN else ControlCenterTone.AMBER
        )

    private fun renderControlCenterVoicePage() {
        val config = VoiceAssistantSettings.get(this)
        val selectedModel = WhisperModelManager.model(config.asrModel)
        val microphoneAllowed = checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        val modelAvailable = WhisperModelManager.isAvailable(this, selectedModel)
        val asrReady = microphoneAllowed && modelAvailable
        val ttsReady = if (config.ttsProvider == VoiceAssistantSettings.PROVIDER_ANDROID) {
            androidTtsReady
        } else {
            validatedInternetAvailable()
        }
        showControlCenterFeature(
            getString(R.string.cc_voice_title),
            ControlCenterPageSpec(
                hero = ControlCenterHeroSpec(
                    title = getString(R.string.voice_low_power_title),
                    subtitle = getString(R.string.voice_low_power_subtitle),
                    iconRes = R.drawable.ic_settings_voice,
                    actionId = "voice.settings",
                    badges = listOf(
                        ControlCenterBadgeSpec(getString(if (config.enabled) R.string.status_enabled else R.string.common_off), if (config.enabled) ControlCenterTone.GREEN else ControlCenterTone.NEUTRAL),
                        ControlCenterBadgeSpec(getString(if (asrReady) R.string.cc_status_ready else R.string.status_needs_setup), if (asrReady) ControlCenterTone.BLUE else ControlCenterTone.AMBER),
                        ControlCenterBadgeSpec("TTS", ControlCenterTone.VIOLET)
                    )
                ),
                sections = listOf(
                    ControlCenterSectionSpec(
                        getString(R.string.voice_section_listening),
                        listOf(
                            ControlCenterRowSpec("voice.settings", getString(R.string.voice_wake_words), config.wakeWords.joinToString(", "), R.drawable.ic_input_voice, "", ControlCenterTone.BLUE),
                            ControlCenterRowSpec("voice.settings", getString(R.string.voice_wake_engine), wakeProviderLabel(config.wakeProvider), R.drawable.ic_agent_node, getString(if (config.enabled) R.string.status_enabled else R.string.common_off), if (config.enabled) ControlCenterTone.GREEN else ControlCenterTone.NEUTRAL),
                            ControlCenterRowSpec("voice.toggle_enabled", getString(R.string.voice_low_power_monitor), getString(R.string.voice_low_power_monitor_subtitle), R.drawable.ic_voice_settings, switchValue = config.enabled, showChevron = false)
                        )
                    ),
                    ControlCenterSectionSpec(
                        getString(R.string.voice_section_asr),
                        listOf(
                            ControlCenterRowSpec(
                                "voice.asr",
                                getString(R.string.voice_asr_provider),
                                selectedModel.displayName,
                                R.drawable.ic_settings_voice,
                                getString(if (asrReady) R.string.cc_status_ready else R.string.status_needs_setup),
                                if (asrReady) ControlCenterTone.GREEN else ControlCenterTone.AMBER
                            ),
                            ControlCenterRowSpec("voice.settings", getString(R.string.voice_tts_provider), ttsProviderLabel(config.ttsProvider), R.drawable.ic_send_plane, getString(if (ttsReady) R.string.cc_status_available else R.string.status_needs_setup), if (ttsReady) ControlCenterTone.VIOLET else ControlCenterTone.AMBER)
                        )
                    ),
                    ControlCenterSectionSpec(
                        getString(R.string.voice_section_target),
                        listOf(ControlCenterRowSpec("voice.settings", getString(R.string.voice_routing_mode), getString(R.string.voice_routing_mode_subtitle), R.drawable.ic_agent_node, voiceRoutingModeLabel(config.routingMode), ControlCenterTone.BLUE))
                    )
                )
            )
        )
    }

    private fun renderControlCenterDataPage() {
        val device = mobileNativeAgent.snapshot().currentScreen.deviceStatus
        val cacheBytes = directorySize(cacheDir)
        val storageSubtitle = if (device.totalStorageMb > 0L) {
            getString(R.string.cc_storage_subtitle) + " · ${formatMegabytes(device.freeStorageMb)}"
        } else {
            getString(R.string.cc_storage_subtitle)
        }
        showControlCenterFeature(
            getString(R.string.cc_data_title),
            ControlCenterPageSpec(
                banner = ControlCenterBannerSpec(
                    title = getString(R.string.cc_data_private_title),
                    subtitle = getString(R.string.cc_data_private_subtitle),
                    iconRes = R.drawable.ic_security_shield,
                    tone = ControlCenterTone.GREEN
                ),
                sections = listOf(
                    ControlCenterSectionSpec(
                        getString(R.string.cc_section_backup),
                        listOf(
                            ControlCenterRowSpec("data.export", getString(R.string.cc_create_backup_title), getString(R.string.cc_create_backup_subtitle), R.drawable.ic_settings_upload, getString(R.string.common_export), ControlCenterTone.BLUE),
                            ControlCenterRowSpec("data.import", getString(R.string.cc_import_backup_title), getString(R.string.cc_import_backup_subtitle), R.drawable.ic_settings_download, getString(R.string.common_import), ControlCenterTone.GREEN)
                        )
                    ),
                    ControlCenterSectionSpec(
                        getString(R.string.cc_section_storage),
                        listOf(
                            ControlCenterRowSpec("", getString(R.string.cc_storage_title), storageSubtitle, R.drawable.ic_device_node, if (device.freeStorageMb > 0L) formatMegabytes(device.freeStorageMb) else "", ControlCenterTone.VIOLET, showChevron = false),
                            ControlCenterRowSpec("data.cache", getString(R.string.cc_clear_cache_title), getString(R.string.cc_clear_cache_subtitle), R.drawable.ic_delete, formatBytes(cacheBytes), ControlCenterTone.AMBER)
                        )
                    )
                )
            )
        )
    }

    private fun renderControlCenterGeneralPage() {
        val textScale = AppDisplaySettings.textScale(this)
        val notificationsEnabled = appNotificationsEnabled()
        showControlCenterFeature(
            getString(R.string.cc_general_page_title),
            ControlCenterPageSpec(
                sections = listOf(
                    ControlCenterSectionSpec(
                        getString(R.string.settings_control_general),
                        listOf(
                            ControlCenterRowSpec("general.language", getString(R.string.settings_language), AppLanguage.displayName(this), R.drawable.ic_settings_language, "", ControlCenterTone.NEUTRAL),
                            ControlCenterRowSpec("general.appearance", getString(R.string.cc_appearance_title), getString(R.string.cc_appearance_subtitle), R.drawable.ic_tab_discover, getString(R.string.cc_managed_by_android), ControlCenterTone.BLUE),
                            ControlCenterRowSpec("general.text_size", getString(R.string.cc_text_size_title), appTextScaleLabel(textScale), R.drawable.ic_info_outline, "", ControlCenterTone.NEUTRAL)
                        )
                    ),
                    ControlCenterSectionSpec(
                        getString(R.string.cc_notifications_title),
                        listOf(ControlCenterRowSpec("general.notifications", getString(R.string.cc_notifications_title), getString(R.string.cc_notifications_subtitle), R.drawable.ic_settings_notification, getString(if (notificationsEnabled) R.string.status_enabled else R.string.status_needs_setup), if (notificationsEnabled) ControlCenterTone.GREEN else ControlCenterTone.AMBER))
                    ),
                    ControlCenterSectionSpec(
                        getString(R.string.settings_about_section),
                        listOf(
                            ControlCenterRowSpec("general.about", getString(R.string.cc_about_title), getString(R.string.cc_about_subtitle), R.drawable.ic_info_outline, "v${installedVersionName()}", ControlCenterTone.NEUTRAL),
                            ControlCenterRowSpec("general.advanced", getString(R.string.cc_developer_title), getString(R.string.cc_developer_subtitle), R.drawable.ic_settings_diagnostics, "", ControlCenterTone.NEUTRAL)
                        )
                    ),
                    ControlCenterSectionSpec(
                        getString(R.string.settings_reset_short),
                        listOf(ControlCenterRowSpec(routeAction(ControlCenterRoute.RESET), getString(R.string.cc_reset_title), getString(R.string.cc_reset_subtitle), R.drawable.ic_reset_data, "", ControlCenterTone.RED))
                    )
                )
            )
        )
    }

    private fun showTextSizeSettingsPage() {
        val selected = AppDisplaySettings.textScale(this)
        showControlCenterFeature(
            getString(R.string.cc_text_size_title),
            ControlCenterPageSpec(
                banner = ControlCenterBannerSpec(
                    getString(R.string.cc_text_size_preview_title),
                    getString(R.string.cc_text_size_preview_subtitle),
                    R.drawable.ic_info_outline,
                    ControlCenterTone.GREEN
                ),
                sections = listOf(
                    ControlCenterSectionSpec(
                        getString(R.string.cc_text_size_section),
                        AppDisplaySettings.TextScaleMode.entries.map { mode ->
                            val isSelected = mode == selected
                            ControlCenterRowSpec(
                                actionId = if (isSelected) "" else "general.text_scale:${mode.wireValue}",
                                title = appTextScaleLabel(mode),
                                subtitle = appTextScaleDescription(mode),
                                iconRes = R.drawable.ic_info_outline,
                                status = if (isSelected) getString(R.string.settings_language_selected) else "",
                                tone = if (isSelected) ControlCenterTone.GREEN else ControlCenterTone.NEUTRAL,
                                showChevron = false
                            )
                        }
                    ),
                    ControlCenterSectionSpec(
                        getString(R.string.cc_text_size_preview_section),
                        listOf(
                            ControlCenterRowSpec(
                                actionId = "",
                                title = getString(R.string.cc_text_size_preview_sample_title),
                                subtitle = getString(R.string.cc_text_size_preview_sample_subtitle),
                                iconRes = R.drawable.ic_agent_node,
                                showChevron = false
                            )
                        )
                    )
                )
            )
        )
    }

    private fun appTextScaleLabel(mode: AppDisplaySettings.TextScaleMode): String = getString(
        when (mode) {
            AppDisplaySettings.TextScaleMode.SYSTEM -> R.string.cc_text_size_system
            AppDisplaySettings.TextScaleMode.STANDARD -> R.string.cc_text_size_standard
            AppDisplaySettings.TextScaleMode.COMFORTABLE -> R.string.cc_text_size_comfortable
            AppDisplaySettings.TextScaleMode.LARGE -> R.string.cc_text_size_large
            AppDisplaySettings.TextScaleMode.EXTRA_LARGE -> R.string.cc_text_size_extra_large
        }
    )

    private fun appTextScaleDescription(mode: AppDisplaySettings.TextScaleMode): String = getString(
        when (mode) {
            AppDisplaySettings.TextScaleMode.SYSTEM -> R.string.cc_text_size_system_subtitle
            AppDisplaySettings.TextScaleMode.STANDARD -> R.string.cc_text_size_standard_subtitle
            AppDisplaySettings.TextScaleMode.COMFORTABLE -> R.string.cc_text_size_comfortable_subtitle
            AppDisplaySettings.TextScaleMode.LARGE -> R.string.cc_text_size_large_subtitle
            AppDisplaySettings.TextScaleMode.EXTRA_LARGE -> R.string.cc_text_size_extra_large_subtitle
        }
    )

    private fun recreateIntoControlCenterChild(child: String) {
        intent.putExtra(EXTRA_REOPEN_CONTROL_CENTER_CHILD, child)
        recreate()
    }

    private fun reopenRequestedControlCenterChild(sourceIntent: Intent?) {
        val child = sourceIntent?.getStringExtra(EXTRA_REOPEN_CONTROL_CENTER_CHILD).orEmpty()
        if (child.isBlank()) return
        sourceIntent?.removeExtra(EXTRA_REOPEN_CONTROL_CENTER_CHILD)
        showMainTab(PAGE_SETTINGS)
        openControlCenterDestination(ControlCenterDestination(ControlCenterRoute.GENERAL))
        when (child) {
            CONTROL_CENTER_CHILD_TEXT_SIZE -> openExistingControlCenterPage { showTextSizeSettingsPage() }
        }
    }

    private fun renderControlCenterAdvancedPage() {
        showControlCenterFeature(
            getString(R.string.advanced_options_title),
            ControlCenterPageSpec(
                banner = ControlCenterBannerSpec(
                    title = getString(R.string.cc_advanced_diagnostics_title),
                    subtitle = getString(R.string.cc_advanced_diagnostics_subtitle),
                    iconRes = R.drawable.ic_settings_diagnostics,
                    tone = ControlCenterTone.NEUTRAL
                ),
                sections = listOf(
                    ControlCenterSectionSpec(
                        getString(R.string.advanced_section_diagnostics),
                        listOf(
                            ControlCenterRowSpec("advanced.protocol", getString(R.string.advanced_protocol_logs), getString(R.string.advanced_protocol_logs_subtitle), R.drawable.ic_protocol_link, getString(R.string.common_view), ControlCenterTone.BLUE),
                            ControlCenterRowSpec("advanced.audit", getString(R.string.advanced_agent_permission_audit), getString(R.string.advanced_agent_permission_audit_subtitle), R.drawable.ic_security_shield, getString(R.string.common_view), ControlCenterTone.VIOLET),
                            ControlCenterRowSpec("advanced.permissions", getString(R.string.cc_permissions_title), getString(R.string.cc_permissions_summary, controlCenterGrantedPermissionCount(), 4), R.drawable.ic_settings_fingerprint, getString(R.string.common_view), ControlCenterTone.AMBER)
                        )
                    ),
                    ControlCenterSectionSpec(
                        getString(R.string.cc_advanced_maintenance_section),
                        listOf(
                            ControlCenterRowSpec("advanced.app_details", getString(R.string.cc_advanced_app_details_title), getString(R.string.cc_advanced_app_details_subtitle), R.drawable.ic_info_outline, "", ControlCenterTone.NEUTRAL),
                            ControlCenterRowSpec("advanced.cache", getString(R.string.cc_clear_cache_title), getString(R.string.cc_clear_cache_subtitle), R.drawable.ic_delete, formatBytes(directorySize(cacheDir)), ControlCenterTone.AMBER)
                        )
                    )
                ),
                footer = getString(R.string.cc_advanced_footer)
            )
        )
    }

    private fun controlCenterGrantedPermissionCount(): Int = listOf(
        SignalASIAccessibilityService.isActive(),
        SignalASINotificationListenerService.currentContext().hasAccess,
        checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED,
        checkSelfPermission(android.Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
    ).count { it }

    private fun appNotificationsEnabled(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            getSystemService(NotificationManager::class.java)?.areNotificationsEnabled() == true
        } else {
            true
        }

    private fun validatedInternetAvailable(): Boolean {
        val connectivity = getSystemService(ConnectivityManager::class.java) ?: return false
        val network = connectivity.activeNetwork ?: return false
        return connectivity.getNetworkCapabilities(network)
            ?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true
    }

    private fun renderControlCenterAppServicesPage() {
        val contactCount = storedContacts().size
        showControlCenterFeature(
            getString(R.string.cc_app_services_page_title),
            ControlCenterPageSpec(
                sections = listOf(
                    ControlCenterSectionSpec(
                        getString(R.string.cc_section_core_modules),
                        listOf(
                            ControlCenterRowSpec("apps.messages", getString(R.string.cc_messages_title), getString(R.string.cc_messages_subtitle), R.drawable.ic_tab_chat, getString(R.string.cc_status_normal), ControlCenterTone.GREEN),
                            ControlCenterRowSpec("apps.contacts", getString(R.string.cc_contacts_title), getString(R.string.cc_contacts_subtitle), R.drawable.ic_tab_contacts_outline, contactCount.toString(), ControlCenterTone.BLUE),
                            ControlCenterRowSpec("apps.discover", getString(R.string.cc_discover_title), getString(R.string.cc_discover_subtitle), R.drawable.ic_tab_discover, "", ControlCenterTone.VIOLET)
                        )
                    ),
                    ControlCenterSectionSpec(
                        getString(R.string.cc_section_message_settings),
                        listOf(
                            ControlCenterRowSpec("apps.background", getString(R.string.cc_background_connection_title), getString(R.string.cc_background_connection_subtitle), R.drawable.ic_protocol_link, getString(if (SignalASIMqttClient.isConnected()) R.string.cc_status_online else R.string.cc_status_degraded), if (SignalASIMqttClient.isConnected()) ControlCenterTone.GREEN else ControlCenterTone.AMBER),
                            ControlCenterRowSpec("apps.chat_history", getString(R.string.cc_chat_history_title), getString(R.string.cc_chat_history_subtitle), R.drawable.ic_agent_history, "", ControlCenterTone.NEUTRAL)
                        )
                    )
                )
            )
        )
    }

    private fun showControlCenterDesktop(desktopId: String) {
        val desktop = desktopSecuritySummaries(activePcConnectorContacts()).firstOrNull { it.id == desktopId }
        if (desktop == null) {
            renderCurrentControlCenterDestination()
            return
        }
        openExistingControlCenterPage { showDesktopSecurityDetail(desktop) }
    }

    private fun showNativeToolCatalogPage() {
        val runtime = mobileNativeAgent.snapshot().runtimeContext
        val tools = runtime.nativeTools
        val sections = tools
            .groupBy { it.location }
            .entries
            .sortedBy { it.key.ordinal }
            .map { (location, descriptors) ->
                ControlCenterSectionSpec(
                    nativeToolLocationLabel(location),
                    descriptors.sortedBy { it.title.lowercase(Locale.ROOT) }.map { tool ->
                        val available = runtime.isNativeToolExecutable(tool.id)
                        val effectiveStatus = nativeToolEffectiveAvailability(tool)
                        ControlCenterRowSpec(
                            actionId = "tool.detail:${tool.id}",
                            title = tool.title,
                            subtitle = tool.description,
                            iconRes = nativeToolIcon(tool),
                            status = getString(nativeToolAvailabilityLabel(effectiveStatus)),
                            tone = when {
                                !available -> nativeToolAvailabilityTone(effectiveStatus)
                                tool.risk == AgentNativeToolRisk.HIGH -> ControlCenterTone.RED
                                tool.risk == AgentNativeToolRisk.MEDIUM -> ControlCenterTone.AMBER
                                else -> ControlCenterTone.GREEN
                            }
                        )
                    }
                )
            }
        showControlCenterFeature(
            getString(R.string.cc_tool_catalog_title),
            ControlCenterPageSpec(
                hero = ControlCenterHeroSpec(
                    title = getString(R.string.cc_tool_catalog_title),
                    subtitle = getString(R.string.cc_tool_catalog_subtitle),
                    iconRes = R.drawable.ic_agent_control,
                    metrics = listOf(
                        ControlCenterMetricSpec(tools.size.toString(), getString(R.string.cc_metric_native_tools)),
                        ControlCenterMetricSpec(runtime.capabilityMatrix.availableNativeToolIds.size.toString(), getString(R.string.cc_metric_available_resources)),
                        ControlCenterMetricSpec(tools.count { it.risk == AgentNativeToolRisk.HIGH }.toString(), getString(R.string.cc_tool_risk_high))
                    )
                ),
                sections = sections
            )
        )
    }

    private fun showNativeToolDetailPage(toolId: String) {
        val tool = mobileNativeAgent.nativeToolCatalog().firstOrNull { it.id == toolId } ?: return
        val effectiveStatus = nativeToolEffectiveAvailability(tool)
        showControlCenterFeature(
            tool.title,
            ControlCenterPageSpec(
                hero = ControlCenterHeroSpec(
                    title = tool.title,
                    subtitle = tool.description,
                    iconRes = nativeToolIcon(tool),
                    badges = listOf(
                        ControlCenterBadgeSpec(nativeToolRiskLabel(tool.risk), nativeToolRiskTone(tool.risk)),
                        ControlCenterBadgeSpec(nativeToolLocationLabel(tool.location), ControlCenterTone.BLUE),
                        ControlCenterBadgeSpec(
                            getString(nativeToolAvailabilityLabel(effectiveStatus)),
                            nativeToolAvailabilityTone(effectiveStatus)
                        )
                    )
                ),
                sections = listOf(
                    ControlCenterSectionSpec(
                        getString(R.string.section_details),
                        listOf(
                            ControlCenterRowSpec("", getString(R.string.cc_tool_id), tool.id, R.drawable.ic_protocol_link, "v${tool.version}", ControlCenterTone.NEUTRAL, showChevron = false),
                            ControlCenterRowSpec("", getString(R.string.feature_run_scope), nativeToolLocationLabel(tool.location), R.drawable.ic_device_node, "", ControlCenterTone.BLUE, showChevron = false),
                            ControlCenterRowSpec("", getString(R.string.on_device_agent_section_permissions), getString(R.string.cc_tool_permissions, tool.requiredPermissions.size, nativeToolRiskLabel(tool.risk)), R.drawable.ic_security_shield, "", nativeToolRiskTone(tool.risk), showChevron = false),
                            ControlCenterRowSpec("", getString(R.string.common_status), tool.availability.reason, R.drawable.ic_info_outline, getString(nativeToolAvailabilityLabel(effectiveStatus)), nativeToolAvailabilityTone(effectiveStatus), showChevron = false)
                        )
                    )
                )
            )
        )
        setFeatureBackAction { showNativeToolCatalogPage() }
    }

    private fun nativeToolLocationLabel(location: AgentNativeToolLocation): String = getString(
        when (location) {
            AgentNativeToolLocation.PHONE -> R.string.cc_tools_phone
            AgentNativeToolLocation.DESKTOP -> R.string.cc_tools_desktop
            AgentNativeToolLocation.APPLICATION -> R.string.cc_tools_application
            AgentNativeToolLocation.ANDROID_SYSTEM -> R.string.cc_tools_android_system
            AgentNativeToolLocation.ACCESSIBILITY_SERVICE -> R.string.cc_tools_accessibility
            AgentNativeToolLocation.UNKNOWN -> R.string.cc_tools_other
        }
    )

    private fun nativeToolAvailabilityLabel(status: AgentNativeToolAvailabilityStatus): Int = when (status) {
        AgentNativeToolAvailabilityStatus.AVAILABLE -> R.string.cc_status_available
        AgentNativeToolAvailabilityStatus.REQUIRES_SETUP -> R.string.status_needs_setup
        AgentNativeToolAvailabilityStatus.UNAVAILABLE -> R.string.cc_status_unavailable
    }

    private fun nativeToolEffectiveAvailability(tool: AgentNativeToolDescriptor): AgentNativeToolAvailabilityStatus =
        if (tool.risk == AgentNativeToolRisk.BLOCKED) {
            AgentNativeToolAvailabilityStatus.UNAVAILABLE
        } else {
            tool.availability.status
        }

    private fun nativeToolAvailabilityTone(status: AgentNativeToolAvailabilityStatus): ControlCenterTone = when (status) {
        AgentNativeToolAvailabilityStatus.AVAILABLE -> ControlCenterTone.GREEN
        AgentNativeToolAvailabilityStatus.REQUIRES_SETUP -> ControlCenterTone.AMBER
        AgentNativeToolAvailabilityStatus.UNAVAILABLE -> ControlCenterTone.NEUTRAL
    }

    private fun nativeToolRiskLabel(risk: AgentNativeToolRisk): String = getString(
        when (risk) {
            AgentNativeToolRisk.LOW -> R.string.cc_tool_risk_low
            AgentNativeToolRisk.MEDIUM -> R.string.cc_tool_risk_medium
            AgentNativeToolRisk.HIGH -> R.string.cc_tool_risk_high
            AgentNativeToolRisk.BLOCKED -> R.string.cc_tool_risk_blocked
        }
    )

    private fun nativeToolRiskTone(risk: AgentNativeToolRisk): ControlCenterTone = when (risk) {
        AgentNativeToolRisk.LOW -> ControlCenterTone.GREEN
        AgentNativeToolRisk.MEDIUM -> ControlCenterTone.AMBER
        AgentNativeToolRisk.HIGH, AgentNativeToolRisk.BLOCKED -> ControlCenterTone.RED
    }

    private fun nativeToolIcon(tool: AgentNativeToolDescriptor): Int = when {
        tool.id.contains("camera") || tool.id.contains("torch") -> R.drawable.ic_scan
        tool.id.contains("audio") || tool.id.contains("microphone") -> R.drawable.ic_input_voice
        tool.id.contains("notification") -> R.drawable.ic_settings_notification
        tool.id.contains("contact") -> R.drawable.ic_tab_contacts_outline
        tool.id.contains("message") || tool.id.contains("sms") -> R.drawable.ic_tab_chat
        tool.id.contains("file") || tool.id.contains("storage") -> R.drawable.ic_import
        tool.id.contains("network") || tool.id.contains("wifi") || tool.id.contains("bluetooth") -> R.drawable.ic_protocol_link
        tool.id.contains("security") || tool.risk == AgentNativeToolRisk.HIGH -> R.drawable.ic_security_shield
        else -> R.drawable.ic_agent_control
    }

    private fun showAgentAuditOperationsPage() {
        val state = mobileNativeAgent.snapshot()
        val auditRows = state.auditTrail.asReversed().take(20).map { entry ->
            ControlCenterRowSpec(
                actionId = "",
                title = controlCenterAuditEventLabel(entry.event),
                subtitle = entry.detail.ifBlank { securityTime(entry.timestampMillis) },
                iconRes = R.drawable.ic_protocol_link,
                status = securityTime(entry.timestampMillis),
                tone = ControlCenterTone.BLUE,
                showChevron = false
            )
        }
        val taskRows = state.recentTasks.take(20).map { task ->
            ControlCenterRowSpec(
                actionId = "",
                title = task.goal,
                subtitle = task.targetTitle,
                iconRes = R.drawable.ic_agent_history,
                status = agentTaskStatusText(task),
                tone = if (task.phase == AgentPhase.COMPLETED) ControlCenterTone.GREEN else if (task.phase == AgentPhase.FAILED) ControlCenterTone.RED else ControlCenterTone.AMBER,
                showChevron = false
            )
        }
        showControlCenterFeature(
            getString(R.string.feature_audit_log),
            ControlCenterPageSpec(
                sections = listOf(
                    ControlCenterSectionSpec(
                        getString(R.string.feature_audit_log),
                        auditRows.ifEmpty { listOf(ControlCenterRowSpec("", getString(R.string.cc_audit_empty), getString(R.string.cc_audit_empty_subtitle), R.drawable.ic_info_outline, "", showChevron = false)) }
                    ),
                    ControlCenterSectionSpec(getString(R.string.cc_tasks_title), taskRows)
                )
            )
        )
    }

    private fun controlCenterAuditEventLabel(event: AgentAuditEvent): String = getString(
        when (event) {
            AgentAuditEvent.SCREEN_OBSERVED,
            AgentAuditEvent.SCREEN_VERIFIED -> R.string.cc_audit_screen
            AgentAuditEvent.CHECKPOINT_SAVED,
            AgentAuditEvent.CHECKPOINT_RESTORED,
            AgentAuditEvent.CHECKPOINT_RESTORE_FAILED,
            AgentAuditEvent.ACTION_RECOVERY_STARTED,
            AgentAuditEvent.ACTION_RECOVERY_COMPLETED,
            AgentAuditEvent.ACTION_RECOVERY_MANUAL_REQUIRED -> R.string.cc_audit_recovery
            AgentAuditEvent.PLAN_REPLANNED,
            AgentAuditEvent.PLAN_REPLAN_LIMIT_REACHED,
            AgentAuditEvent.PLAN_EDITED,
            AgentAuditEvent.PLAN_EDIT_REJECTED,
            AgentAuditEvent.REASONING_SUMMARY -> R.string.cc_audit_planning
            AgentAuditEvent.TOOL_STARTED,
            AgentAuditEvent.TOOL_COMPLETED,
            AgentAuditEvent.TOOL_OUTPUT_HANDOFF,
            AgentAuditEvent.TOOL_GRAPH_BLOCKED,
            AgentAuditEvent.AUTONOMY_GUARD_BLOCKED,
            AgentAuditEvent.INVOCATION_AUDIT,
            AgentAuditEvent.CONNECTOR_RESPONSE_RECEIVED -> R.string.cc_audit_resource
            AgentAuditEvent.GOAL_RECEIVED -> R.string.cc_audit_goal
            AgentAuditEvent.MEMORY_SKIPPED,
            AgentAuditEvent.MEMORY_FORGOTTEN,
            AgentAuditEvent.MEMORY_UPDATED,
            AgentAuditEvent.MEMORY_CONFLICT_DETECTED,
            AgentAuditEvent.MEMORY_CONFLICT_RESOLVED -> R.string.cc_audit_memory
            AgentAuditEvent.KNOWLEDGE_IMPORTED,
            AgentAuditEvent.KNOWLEDGE_ACCESSED,
            AgentAuditEvent.KNOWLEDGE_ACCESS_UPDATED -> R.string.cc_audit_knowledge
            AgentAuditEvent.WORKFLOW_UPDATED,
            AgentAuditEvent.WORKFLOW_RUN -> R.string.cc_audit_workflow
            AgentAuditEvent.ACTION_EXECUTED,
            AgentAuditEvent.ACTION_BLOCKED -> R.string.cc_audit_action
            AgentAuditEvent.TASK_CANCELLED,
            AgentAuditEvent.TASK_PAUSED,
            AgentAuditEvent.TASK_RESUMED,
            AgentAuditEvent.TASK_INTERRUPTED -> R.string.cc_audit_task
            AgentAuditEvent.SETTINGS_UPDATED -> R.string.cc_audit_settings
        }
    )

    private fun showHomeAssistantCollectionPage(collection: String) {
        val title = if (collection == "automations") getString(R.string.cc_home_automations_title) else getString(R.string.cc_home_entities_title)
        showControlCenterFeature(
            title,
            ControlCenterPageSpec(
                banner = ControlCenterBannerSpec(getString(R.string.cc_loading), getString(R.string.cc_home_assistant_connected), R.drawable.ic_device_node),
                sections = emptyList()
            )
        )
        thread(name = "signalasi-home-assistant-$collection") {
            val result = if (collection == "automations") {
                HomeAssistantDeviceClient.listAutomations(this)
            } else {
                HomeAssistantDeviceClient.listEntities(this)
            }
            runOnUiThread {
                if (featurePage.visibility != View.VISIBLE || featureTitle.text.toString() != title) return@runOnUiThread
                val rows = result.entities.take(80).map { entity ->
                    ControlCenterRowSpec(
                        actionId = "ha.entity:${entity.entityId}",
                        title = entity.friendlyName.ifBlank { entity.entityId },
                        subtitle = entity.entityId,
                        iconRes = R.drawable.ic_device_node,
                        status = entity.state,
                        tone = if (entity.state.equals("unavailable", true)) ControlCenterTone.AMBER else ControlCenterTone.GREEN
                    )
                }
                controlCenterRenderer.render(
                    featureContent,
                    ControlCenterPageSpec(
                        banner = if (result.success) null else ControlCenterBannerSpec(getString(R.string.cc_home_load_failed), result.message, R.drawable.ic_info_outline, ControlCenterTone.AMBER),
                        sections = listOf(ControlCenterSectionSpec(
                            title,
                            rows.ifEmpty { listOf(ControlCenterRowSpec("", getString(R.string.cc_home_empty), result.message, R.drawable.ic_info_outline, "", showChevron = false)) }
                        ))
                    ),
                    ::handleControlCenterAction
                )
            }
        }
    }

    private fun showHomeAssistantEntityDetailPage(entityId: String) {
        val parentTitle = featureTitle.text.toString()
        showControlCenterFeature(
            entityId,
            ControlCenterPageSpec(
                banner = ControlCenterBannerSpec(getString(R.string.cc_loading), entityId, R.drawable.ic_device_node),
                sections = emptyList()
            )
        )
        setFeatureBackAction {
            showHomeAssistantCollectionPage(if (parentTitle == getString(R.string.cc_home_automations_title)) "automations" else "entities")
        }
        thread(name = "signalasi-home-assistant-entity") {
            val result = HomeAssistantDeviceClient.readEntity(this, entityId)
            runOnUiThread {
                if (featurePage.visibility != View.VISIBLE || featureTitle.text.toString() != entityId) return@runOnUiThread
                val entity = result.entities.firstOrNull()
                val displayTitle = entity?.friendlyName?.ifBlank { entityId } ?: entityId
                controlCenterRenderer.render(
                    featureContent,
                    ControlCenterPageSpec(
                        hero = ControlCenterHeroSpec(
                            title = displayTitle,
                            subtitle = entityId,
                            iconRes = R.drawable.ic_device_node,
                            badges = listOf(ControlCenterBadgeSpec(entity?.state ?: result.message, if (result.success) ControlCenterTone.GREEN else ControlCenterTone.AMBER))
                        ),
                        sections = listOf(ControlCenterSectionSpec(
                            getString(R.string.section_details),
                            listOf(ControlCenterRowSpec("", getString(R.string.common_status), getString(R.string.cc_entity_state, entity?.state ?: result.message), R.drawable.ic_info_outline, "", showChevron = false))
                        ))
                    ),
                    ::handleControlCenterAction
                )
            }
        }
    }

    private fun clearRebuildableCache() {
        cacheDir.listFiles().orEmpty().forEach { runCatching { it.deleteRecursively() } }
        Toast.makeText(this, getString(R.string.cc_cache_cleared), Toast.LENGTH_SHORT).show()
        renderCurrentControlCenterDestination()
    }

    private fun directorySize(root: File): Long = runCatching {
        root.walkTopDown().filter(File::isFile).sumOf(File::length)
    }.getOrDefault(0L)

    private fun formatMegabytes(value: Long): String = if (value >= 1024L) {
        String.format(Locale.US, "%.1f GB", value / 1024.0)
    } else {
        "$value MB"
    }

    private fun formatBytes(value: Long): String = when {
        value >= 1024L * 1024L * 1024L -> String.format(Locale.US, "%.1f GB", value / (1024.0 * 1024.0 * 1024.0))
        value >= 1024L * 1024L -> String.format(Locale.US, "%.1f MB", value / (1024.0 * 1024.0))
        value >= 1024L -> String.format(Locale.US, "%.1f KB", value / 1024.0)
        else -> "$value B"
    }

    private fun compactFingerprint(value: String): String {
        val clean = value.filter(Char::isLetterOrDigit)
        return if (clean.length > 14) "${clean.take(6)}…${clean.takeLast(5)}" else clean
    }

    private fun installedVersionName(): String = runCatching {
        packageManager.getPackageInfo(packageName, 0).versionName.orEmpty()
    }.getOrDefault("").ifBlank { "0.1" }

    private fun renderAgentState(
        state: AgentUiState,
        conversationId: String = agentTranscriptStore.activeConversation().id,
        turnId: String = "",
        syncTranscript: Boolean = true,
        activeConversationId: String? = null
    ) {
        if (turnId.isNotBlank()) recordRunControlProgress(state, turnId)
        val currentConversationId = activeConversationId ?: agentTranscriptStore.activeConversation().id
        if (conversationId != currentConversationId) {
            if (syncTranscript) syncAgentTranscript(state, conversationId, turnId)
            return
        }
        agentRenderedConversationId = conversationId
        if (state == lastRenderedAgentState) return
        lastRenderedAgentState = state
        val pendingAction = state.pendingAction
        if (syncTranscript) renderAgentOutput(state, conversationId, turnId)
        val safetySettings = mobileNativeAgent.safetySettings()
        agentPermissionModeButton.text = getString(
            R.string.agent_safety_permission_mode_value,
            permissionModeLabel(safetySettings.permissionMode)
        )
        agentHighRiskGuardButton.text = getString(
            R.string.agent_safety_high_risk_guard_value,
            onOffLabel(safetySettings.highRiskGuard)
        )
        agentHighRiskGuardButton.setTextColor(
            if (safetySettings.highRiskGuard) getColorCompat(R.color.wechat_green) else getColorCompat(R.color.text_secondary)
        )
        agentMemoryCaptureButton.text = getString(
            R.string.agent_safety_memory_capture_value,
            onOffLabel(safetySettings.memoryCapture)
        )
        agentMemoryCaptureButton.setTextColor(
            if (safetySettings.memoryCapture) getColorCompat(R.color.wechat_green) else getColorCompat(R.color.text_secondary)
        )
        agentVoiceButton.text = if (agentVoiceListening) {
            getString(R.string.agent_voice_listening)
        } else {
            getString(R.string.agent_voice_button)
        }
        agentSubmitButton.isEnabled = true
        agentSubmitButton.alpha = 1f
        latestAgentScreenContext = state.currentScreen
    }

    private fun recordRunControlProgress(state: AgentUiState, turnId: String) {
        val runId = agentRunIdsByTurn[turnId] ?: return
        val run = agentRunRecorder.run(runId) ?: return
        val action = state.pendingAction ?: state.plan?.actions?.lastOrNull { candidate ->
            candidate.status == AgentActionStatus.RUNNING ||
                candidate.status == AgentActionStatus.WAITING_RESPONSE
        }
        if (state.phase == AgentPhase.WAITING_RESPONSE && action?.kind == AgentActionKind.CALL_CONNECTOR) {
            recordStructuredAgentHandoff(state, turnId, run, action)
        }
        val eventType = when (state.phase) {
            AgentPhase.OBSERVING -> AgentRunControlEventType.STEP_STARTED
            AgentPhase.PLANNING -> AgentRunControlEventType.PLANNING
            AgentPhase.WAITING_CONFIRMATION -> AgentRunControlEventType.WAITING_FOR_USER
            AgentPhase.EXECUTING -> if (action != null) {
                AgentRunControlEventType.TOOL_STARTED
            } else {
                AgentRunControlEventType.STEP_STARTED
            }
            AgentPhase.VERIFYING -> AgentRunControlEventType.TOOL_PROGRESS
            AgentPhase.WAITING_RESPONSE -> AgentRunControlEventType.WAITING_FOR_DEVICE
            AgentPhase.PAUSED -> AgentRunControlEventType.PAUSED
            AgentPhase.CANCELLED,
            AgentPhase.BLOCKED,
            AgentPhase.FAILED,
            AgentPhase.COMPLETED -> return
        }
        val stepId = action?.id.orEmpty().ifBlank { state.steps.firstOrNull { it.status == AgentStepStatus.CURRENT }?.kind?.name.orEmpty() }
        val toolCallId = action?.takeIf {
            it.kind == AgentActionKind.CALL_NATIVE_TOOL || it.kind == AgentActionKind.CALL_CONNECTOR
        }?.id.orEmpty()
        val latest = agentRunEventStore.events(runId).lastOrNull()
        if (latest?.type == eventType && latest.stepId == stepId && latest.toolCallId == toolCallId) return
        val agentId = state.plan?.route?.targetId.orEmpty()
            .ifBlank { state.plan?.selectedAgentOrModel.orEmpty() }
            .ifBlank { "signalasi-mobile" }
        appendRunControlEvent(
            run = run,
            messageId = turnId,
            taskId = turnId,
            agentId = agentId,
            type = eventType,
            payload = mapOf(
                "phase" to state.phase.name.lowercase(Locale.ROOT),
                "action_kind" to action?.kind?.name.orEmpty().lowercase(Locale.ROOT),
                "delivery_mode" to state.plan?.route?.deliveryMode.orEmpty()
            ),
            stepId = stepId,
            toolCallId = toolCallId
        )
    }

    private fun recordStructuredAgentHandoff(
        state: AgentUiState,
        turnId: String,
        run: AgentRecordedRun,
        action: AgentAction
    ) {
        if (!::agentHandoffStore.isInitialized) return
        val route = state.plan?.route
        val fromAgentId = "signalasi-mobile"
        val toAgentId = route?.targetId.orEmpty()
            .ifBlank { action.parameters["connector_id"].orEmpty() }
            .ifBlank { action.target }
        if (toAgentId.isBlank() || toAgentId == fromAgentId) return
        val sourceMessageId = state.lastActionResult?.metadata
            ?.get("source_message_id")?.toLongOrNull()?.coerceAtLeast(0L) ?: 0L
        val handoffId = AgentHandoffLifecycle.stableId(run.runId, action.id, fromAgentId, toAgentId)
        val mutation = agentHandoffStore.beginActive(
            AgentHandoffRequest(
                handoffId = handoffId,
                conversationId = run.conversationId,
                taskId = turnId,
                runId = run.runId,
                fromAgentId = fromAgentId,
                toAgentId = toAgentId,
                returnToAgentId = fromAgentId,
                reason = state.plan?.routeRationale.orEmpty().ifBlank { action.description },
                deliveryMode = when (route?.deliveryMode.orEmpty().lowercase(Locale.ROOT)) {
                    "observe", "inject", "context" -> AgentDeliveryMode.OBSERVE
                    "ignore", "none", "skip" -> AgentDeliveryMode.IGNORE
                    else -> AgentDeliveryMode.RESPOND
                },
                requiredCapabilities = route?.capabilities.orEmpty().toSet(),
                artifactIds = action.outputSourceIds(),
                checkpoint = mapOf(
                    "source_message_id" to sourceMessageId,
                    "last_event_sequence" to (agentRunEventStore.events(run.runId).lastOrNull()?.sequence ?: 0L)
                ),
                context = mapOf(
                    "turn_id" to turnId,
                    "step_id" to action.id,
                    "route_kind" to route?.kind?.name.orEmpty(),
                    "delivery_mode" to route?.deliveryMode.orEmpty()
                )
            ),
            sourceMessageId = sourceMessageId
        )
        if (!mutation.created) return
        appendRunControlEvent(
            run = run,
            messageId = turnId,
            taskId = turnId,
            agentId = toAgentId,
            type = AgentRunControlEventType.HANDOFF,
            payload = mapOf(
                "handoff_id" to handoffId,
                "from_agent_id" to fromAgentId,
                "to_agent_id" to toAgentId,
                "return_to_agent_id" to fromAgentId,
                "reason" to mutation.record.request.reason,
                "delivery_mode" to mutation.record.request.deliveryMode.name.lowercase(Locale.ROOT),
                "source_message_id" to sourceMessageId,
                "artifact_ids" to mutation.record.request.artifactIds
            ),
            stepId = action.id,
            toolCallId = action.id
        )
    }

    private fun finishStructuredAgentHandoff(turnId: String, response: AgentConnectorResponse) {
        if (!::agentHandoffStore.isInitialized) return
        val runId = agentRunIdsByTurn[turnId] ?: return
        val run = agentRunRecorder.run(runId) ?: return
        val state = if (response.success) AgentHandoffState.RETURNED else AgentHandoffState.FAILED
        val record = agentHandoffStore.finish(
            runId = runId,
            sourceMessageId = response.sourceMessageId,
            state = state,
            resultSummary = response.content
        ) ?: return
        appendRunControlEvent(
            run = run,
            messageId = turnId,
            taskId = turnId,
            agentId = record.request.toAgentId,
            type = AgentRunControlEventType.STEP_COMPLETED,
            payload = mapOf(
                "handoff_id" to record.request.handoffId,
                "handoff_state" to record.state.name.lowercase(Locale.ROOT),
                "from_agent_id" to record.request.toAgentId,
                "to_agent_id" to record.request.returnToAgentId,
                "source_message_id" to response.sourceMessageId,
                "success" to response.success
            ),
            stepId = record.request.context["step_id"]?.toString().orEmpty(),
            toolCallId = record.request.context["step_id"]?.toString().orEmpty()
        )
    }

    private fun renderAgentOutput(state: AgentUiState, conversationId: String, turnId: String) {
        syncAgentTranscript(state, conversationId, turnId)
        if (conversationId == agentTranscriptStore.activeConversation().id) {
            refreshAgentConversationHeader()
            renderAgentTranscript(agentTranscriptStore.list(conversationId))
        }
    }

    private fun syncAgentTranscript(state: AgentUiState, conversationId: String, turnId: String) {
        state.plan?.selectedAgentOrModel?.takeIf { it.isNotBlank() }?.let {
            agentTranscriptStore.setSelectedModelOrAgent(conversationId, agentTraceTargetLabel(it))
        }
        val planId = state.plan?.planId.orEmpty().ifBlank {
            "${state.sessionId}:${state.currentGoal.hashCode()}"
        }
        state.auditTrail.forEach { entry ->
            val line = agentExecutionLine(state, entry) ?: return@forEach
            agentTranscriptStore.append(
                AgentTranscriptRole.PROCESS,
                line,
                dedupeKey = "audit:${entry.timestampMillis}:${entry.event.name}:${entry.detail.hashCode()}",
                timestampMillis = entry.timestampMillis,
                conversationId = conversationId,
                turnId = turnId,
                taskId = state.sessionId
            )
        }
        state.pendingAction?.takeIf { it.kind != AgentActionKind.CALL_CONNECTOR }?.let { pending ->
            val description = pending.description.trim()
            val processDescription = localizedAgentProcessText(description)
            if (state.phase == AgentPhase.WAITING_CONFIRMATION &&
                description.isNotBlank()
            ) {
                val richOutput = AgentRichContentCodec.encode(listOf(
                    AgentRichBlock(
                        id = "approval:${state.sessionId}:${pending.id}",
                        type = AgentRichBlockType.APPROVAL,
                        title = agentApprovalTitle(pending),
                        text = getString(
                            R.string.agent_inline_approval_detail,
                            agentRiskLabel(pending.risk)
                        ),
                        fallbackText = getString(R.string.agent_inline_approval_waiting),
                        actions = listOf(
                            AgentRichAction("cancel", getString(R.string.common_cancel), "reject_task"),
                            AgentRichAction(
                                "confirm",
                                getString(R.string.agent_inline_approval_confirm),
                                "approve_task"
                            )
                        )
                    )
                ))
                agentTranscriptStore.append(
                    AgentTranscriptRole.ASSISTANT,
                    description,
                    dedupeKey = "approval:$planId:${pending.id}",
                    conversationId = conversationId,
                    turnId = turnId,
                    taskId = state.sessionId,
                    richOutputJson = richOutput
                )
            } else if (description.isNotBlank() &&
                description != "Create a safe local task plan" &&
                !description.contains(':') &&
                !pending.isPhoneDevelopmentRuntimeHandoff()
            ) {
                agentTranscriptStore.append(
                    AgentTranscriptRole.PROCESS,
                    processDescription,
                    dedupeKey = "pending:$planId:${pending.id}:${description.hashCode()}",
                    conversationId = conversationId,
                    turnId = turnId,
                    taskId = state.sessionId
                )
            }
        }
        val connectorMetadata = state.lastActionResult?.metadata.orEmpty()
        val connectorPublished = state.phase == AgentPhase.WAITING_RESPONSE &&
            connectorMetadata["awaiting_response"] == "true" &&
            connectorMetadata["source_message_id"].orEmpty().isNotBlank()
        val remoteTaskCreated = connectorMetadata["remote_task_id"].orEmpty().isNotBlank()
        if (connectorPublished && !remoteTaskCreated && turnId.isNotBlank()) {
            val target = connectorMetadata["target"].orEmpty()
                .ifBlank { state.plan?.route?.targetTitle.orEmpty() }
                .ifBlank { state.plan?.selectedAgentOrModel.orEmpty() }
                .ifBlank { getString(R.string.tab_agent) }
            agentTranscriptStore.upsert(
                AgentTranscriptRole.PROCESS,
                "$target · ${getString(R.string.agent_task_status_starting)}",
                dedupeKey = "connector-turn:$turnId",
                conversationId = conversationId,
                turnId = turnId,
                taskId = state.sessionId
            )
        }
        val result = CodexStyleResponsePolicy.sanitizeAssistantText(
            state.lastActionResult?.message.orEmpty()
        )
        val settledConnectorResult = state.lastActionResult?.metadata?.get("awaiting_response") == "false"
        val terminal = state.phase == AgentPhase.COMPLETED ||
            state.phase == AgentPhase.FAILED ||
            state.phase == AgentPhase.CANCELLED ||
            state.phase == AgentPhase.BLOCKED
        if (result.isNotBlank() && (settledConnectorResult || terminal) && !isTransientAgentResult(result)) {
            val actionId = state.lastActionResult?.actionId.orEmpty()
            agentTranscriptStore.append(
                AgentTranscriptRole.ASSISTANT,
                result,
                dedupeKey = "result:$planId:$actionId:${result.hashCode()}",
                conversationId = conversationId,
                turnId = turnId,
                taskId = state.sessionId,
                richOutputJson = CodexStyleResponsePolicy.filterAssistantRichOutput(
                    state.lastActionResult?.metadata?.get("rich_output").orEmpty()
                )
            )
        }
    }

    private fun agentApprovalTitle(action: AgentAction): String {
        val timerSeconds = action.parameters["timer_seconds"]?.toIntOrNull()
        return when {
            timerSeconds != null && timerSeconds % 60 == 0 -> getString(
                R.string.agent_inline_approval_timer_minutes,
                timerSeconds / 60
            )
            timerSeconds != null -> getString(R.string.agent_inline_approval_timer_seconds, timerSeconds)
            else -> action.description
        }
    }

    private fun agentRiskLabel(risk: AgentRisk): String = getString(
        when (risk) {
            AgentRisk.LOW -> R.string.agent_risk_low
            AgentRisk.MEDIUM -> R.string.agent_risk_medium
            AgentRisk.HIGH -> R.string.agent_risk_high
            AgentRisk.BLOCKED -> R.string.agent_risk_blocked
        }
    )

    private fun isTransientAgentResult(value: String): Boolean {
        val normalized = value.trim().lowercase(Locale.US)
        return normalized.startsWith("waiting for ") ||
            normalized.startsWith("sent the request") ||
            normalized.startsWith("received a response") ||
            normalized.startsWith("\u5df2\u5c06\u8bf7\u6c42\u53d1\u9001") ||
            normalized.startsWith("\u5df2\u6536\u5230")
    }

    private fun renderAgentTranscript(entries: List<AgentTranscriptEntry>) {
        val firstRenderedId = renderedAgentTranscriptIds.firstOrNull()
        val firstRenderedIndex = entries.indexOfFirst { it.id == firstRenderedId }
        val visibleWindow = if (firstRenderedIndex >= 0) {
            entries.drop(firstRenderedIndex)
        } else {
            entries.takeLast(agentTranscriptVisibleLimit)
        }
        agentTranscriptAllLoaded = visibleWindow.size >= entries.size
        val filteredEntries = visibleWindow.filterNot { entry ->
            val staleApproval = isAgentApprovalEntry(entry) &&
                (isDirectActionApprovalEntry(entry) || !isAgentApprovalStillWaiting(entry.taskId))
            if (staleApproval) agentTranscriptStore.deleteEntry(entry.id)
            staleApproval
        }
        val visibleEntries = AgentTranscriptPresentationPolicy.collapseProcessGroups(filteredEntries)
        val incomingIds = visibleEntries.map(AgentTranscriptEntry::id)
        val renderedIds = renderedAgentTranscriptIds.toList()
        val canAppend = renderedIds.size <= incomingIds.size &&
            incomingIds.take(renderedIds.size) == renderedIds
        val shouldFollow = agentTranscriptAutoFollow
        val preservedScrollY = agentOutputScroll.scrollY
        if (!canAppend) {
            agentOutputList.removeAllViews()
            renderedAgentTranscriptIds.clear()
        }
        visibleEntries.asSequence()
            .filterNot { it.id in renderedAgentTranscriptIds }
            .forEach { entry ->
                agentOutputList.addView(agentTranscriptRow(entry))
                renderedAgentTranscriptIds += entry.id
            }
        if (incomingIds == renderedIds) return
        agentOutputScroll.post {
            if (shouldFollow) {
                agentOutputScroll.fullScroll(View.FOCUS_DOWN)
            } else if (!canAppend) {
                agentOutputScroll.scrollTo(0, preservedScrollY)
            }
        }
    }

    private fun isAgentApprovalEntry(entry: AgentTranscriptEntry): Boolean =
        entry.taskId.isNotBlank() && AgentRichContentCodec.decode(entry.richOutputJson).any { block ->
            block.type == AgentRichBlockType.APPROVAL && block.actions.any { action ->
                action.verb == "approve_task" || action.verb == "reject_task"
            }
        }

    private fun isDirectActionApprovalEntry(entry: AgentTranscriptEntry): Boolean =
        AgentRichContentCodec.decode(entry.richOutputJson).any { block ->
            if (block.type != AgentRichBlockType.APPROVAL) return@any false
            val value = listOf(block.title, block.text, block.fallbackText, entry.text)
                .joinToString(" ")
                .lowercase(Locale.US)
            listOf(
                "timer", "alarm", "camera", "flashlight", "torch", "volume", "battery status",
                "device status", "open app", "launch app", "\u8ba1\u65f6\u5668", "\u95f9\u949f", "\u62cd\u7167",
                "\u624b\u7535\u7b52", "\u97f3\u91cf", "\u7535\u91cf", "\u8bbe\u5907\u72b6\u6001", "\u6253\u5f00 app"
            ).any(value::contains)
        }

    private fun isAgentApprovalStillWaiting(taskId: String): Boolean {
        if (taskId.isBlank()) return false
        return buildList {
            add(mobileNativeAgent)
            addAll(activeAgentTasks.values)
            addAll(provisionalAgentTasks)
        }.distinct().any { runtime ->
            val state = runtime.snapshot()
            state.sessionId == taskId &&
                state.phase == AgentPhase.WAITING_CONFIRMATION &&
                state.pendingAction?.let(AgentConfirmationPolicy::tier) != AgentConfirmationTier.DIRECT
        }
    }

    private fun agentTranscriptRow(entry: AgentTranscriptEntry): View {
        val content = when (entry.role) {
            AgentTranscriptRole.USER -> agentUserTranscriptRow(entry)
            AgentTranscriptRole.ASSISTANT -> AgentRichContentView(
                activity = this,
                onTextViewReady = { textView -> attachAgentTranscriptActions(textView, entry) },
                onAction = { action -> handleAgentRichAction(entry, action) },
                onFormSubmit = { block, values -> handleAgentRichForm(entry, block, values) }
            ).create(entry.copy(
                text = CodexStyleResponsePolicy.sanitizeAssistantText(entry.text),
                richOutputJson = CodexStyleResponsePolicy.filterAssistantRichOutput(entry.richOutputJson)
            ))
            AgentTranscriptRole.PROCESS -> agentProcessTranscriptRow(entry)
        }
        if (entry.sourceConversationId.isBlank() || entry.role == AgentTranscriptRole.PROCESS) return content
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = if (entry.role == AgentTranscriptRole.USER) Gravity.END else Gravity.START
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            addView(TextView(this@MainActivity).apply {
                text = getString(
                    R.string.agent_session_merged_from,
                    entry.sourceConversationTitle.ifBlank { entry.sourceConversationId.take(12) }
                )
                setTextColor(getColorCompat(R.color.text_secondary))
                textSize = 11f
                includeFontPadding = false
                setPadding(dp(2), dp(8), dp(2), 0)
            })
            addView(content)
        }
    }

    private fun agentProcessTranscriptRow(entry: AgentTranscriptEntry): View {
        val groupKey = AgentTranscriptPresentationPolicy.processGroupKey(entry)
        val turnEntries = agentTranscriptStore.list(entry.conversationId)
        val processEntries = turnEntries
            .filter { candidate ->
                candidate.role == AgentTranscriptRole.PROCESS &&
                    !AgentTranscriptPresentationPolicy.isRedundantConnectorCompletion(candidate) &&
                    !AgentTranscriptPresentationPolicy.isInternalRuntimeHandoff(candidate) &&
                    when {
                        entry.turnId.isNotBlank() -> candidate.turnId == entry.turnId ||
                            candidate.id == entry.id ||
                            (candidate.turnId.isBlank() && candidate.taskId == entry.taskId)
                        entry.taskId.isNotBlank() -> candidate.taskId == entry.taskId
                        else -> candidate.id == entry.id
                    }
            }
            .sortedBy(AgentTranscriptEntry::timestampMillis)
            .distinctBy { it.text.trim() }
            .takeLast(MAX_VISIBLE_AGENT_PROCESS_STEPS)
        val startedAt = processEntries.firstOrNull()?.timestampMillis ?: entry.timestampMillis
        val completedAt = agentProcessCompletionTimestamp(entry, turnEntries)
        val completed = completedAt != null
        val expanded = AgentTranscriptPresentationPolicy.processExpanded(
            completed = completed,
            manuallyExpanded = groupKey in expandedAgentProcessGroups,
            manuallyCollapsedWhileActive = groupKey in collapsedActiveAgentProcessGroups
        )
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(10)
            }
            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                isClickable = true
                isFocusable = true
                minimumHeight = dp(34)
                setPadding(0, dp(5), 0, dp(5))
                addView(TextView(this@MainActivity).apply {
                    setTextColor(getColorCompat(R.color.text_secondary))
                    textSize = 14f
                    includeFontPadding = false
                    maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                    val statusView = this
                    val ticker = object : Runnable {
                        override fun run() {
                            val completionTimestamp = agentProcessCompletionTimestamp(entry)
                            val elapsedMillis = (
                                (completionTimestamp ?: System.currentTimeMillis()) - startedAt
                            ).coerceAtLeast(0L)
                            statusView.text = getString(
                                if (completionTimestamp != null) {
                                    R.string.agent_trace_processed
                                } else {
                                    R.string.agent_trace_processing
                                },
                                agentTraceDuration(elapsedMillis),
                                ""
                            ).trimEnd()
                            if (completionTimestamp == null && statusView.isAttachedToWindow) {
                                statusView.postDelayed(this, AGENT_PROCESS_TIMER_TICK_MS)
                            }
                        }
                    }
                    addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
                        override fun onViewAttachedToWindow(view: View) {
                            statusView.removeCallbacks(ticker)
                            ticker.run()
                        }

                        override fun onViewDetachedFromWindow(view: View) {
                            statusView.removeCallbacks(ticker)
                        }
                    })
                })
                addView(ImageView(this@MainActivity).apply {
                    setImageResource(R.drawable.ic_chevron_down)
                    imageTintList = android.content.res.ColorStateList.valueOf(
                        getColorCompat(R.color.text_secondary)
                    )
                    rotation = if (expanded) 180f else 0f
                    contentDescription = getString(R.string.agent_trace_processed_details)
                }, LinearLayout.LayoutParams(dp(17), dp(17)).apply {
                    marginStart = dp(4)
                })
                setOnClickListener {
                    if (completed) {
                        if (expanded) expandedAgentProcessGroups.remove(groupKey)
                        else expandedAgentProcessGroups.add(groupKey)
                    } else {
                        if (expanded) collapsedActiveAgentProcessGroups.add(groupKey)
                        else collapsedActiveAgentProcessGroups.remove(groupKey)
                    }
                    renderedAgentTranscriptIds.clear()
                    agentOutputList.removeAllViews()
                    renderAgentTranscript(agentTranscriptStore.list(entry.conversationId))
                }
            })
            if (expanded) {
                AgentTranscriptPresentationPolicy.processSegments(processEntries)
                    .forEachIndexed { index, segment ->
                        when (segment.kind) {
                            AgentTranscriptPresentationPolicy.ProcessContentKind.NARRATION ->
                                segment.entries.forEach { narration ->
                                    addView(agentProcessNarrationRow(narration))
                                }
                            AgentTranscriptPresentationPolicy.ProcessContentKind.TOOL_ACTIVITY -> {
                                val segmentKey = "$groupKey:tools:$index:${segment.entries.firstOrNull()?.id.orEmpty()}"
                                addView(agentToolSegmentRow(segmentKey, segment.entries))
                            }
                        }
                    }
                if (processEntries.isEmpty()) {
                    addView(agentProcessStepRow(entry))
                }
            }
            addView(View(this@MainActivity).apply {
                setBackgroundColor(Color.parseColor("#E8EAED"))
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)).apply {
                topMargin = if (expanded) dp(5) else dp(2)
            })
        }
    }

    private fun agentProcessNarrationRow(entry: AgentTranscriptEntry): View = TextView(this).apply {
        text = localizedAgentProcessText(entry.text)
        setTextColor(getColorCompat(R.color.text_primary))
        textSize = 16f
        includeFontPadding = false
        setLineSpacing(dp(4).toFloat(), 1f)
        setPadding(0, dp(8), 0, dp(8))
        attachAgentTranscriptActions(this, entry)
    }

    private fun agentToolSegmentRow(
        segmentKey: String,
        entries: List<AgentTranscriptEntry>
    ): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        val detailsExpanded = segmentKey in expandedAgentToolSegments
        addView(LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(31)
            isClickable = true
            isFocusable = true
            addView(ImageView(this@MainActivity).apply {
                setImageResource(agentProcessIconResource(entries.firstOrNull()?.text.orEmpty()))
                imageTintList = android.content.res.ColorStateList.valueOf(
                    getColorCompat(R.color.text_secondary)
                )
                contentDescription = null
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }, LinearLayout.LayoutParams(dp(16), dp(16)).apply { marginEnd = dp(8) })
            addView(TextView(this@MainActivity).apply {
                text = if (entries.size == 1) {
                    localizedAgentProcessText(entries.single().text)
                } else {
                    getString(R.string.agent_trace_tool_group_count, entries.size)
                }
                setTextColor(getColorCompat(R.color.text_secondary))
                textSize = 14f
                includeFontPadding = false
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(ImageView(this@MainActivity).apply {
                setImageResource(R.drawable.ic_chevron_down)
                imageTintList = android.content.res.ColorStateList.valueOf(
                    getColorCompat(R.color.text_secondary)
                )
                rotation = if (detailsExpanded) 180f else 0f
                contentDescription = getString(R.string.agent_trace_tool_group_details)
            }, LinearLayout.LayoutParams(dp(15), dp(15)).apply { marginStart = dp(4) })
            setOnClickListener {
                if (detailsExpanded) expandedAgentToolSegments.remove(segmentKey)
                else expandedAgentToolSegments.add(segmentKey)
                renderedAgentTranscriptIds.clear()
                agentOutputList.removeAllViews()
                renderAgentTranscript(agentTranscriptStore.list())
            }
        })
        if (detailsExpanded) {
            val commandEntries = entries.filter { detail ->
                detail.dedupeKey.contains(":TOOL_STARTED:") &&
                    (detail.text.startsWith("Phone Linux:", true) || detail.text.startsWith("\u672c\u673a Linux:", true))
            }
            (commandEntries.ifEmpty { entries }).forEach { detail ->
                addView(agentProcessStepRow(detail), LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { marginStart = dp(24) })
            }
        }
    }

    private fun agentProcessStepRow(entry: AgentTranscriptEntry): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = dp(31)
        addView(ImageView(this@MainActivity).apply {
            setImageResource(agentProcessIconResource(entry.text))
            imageTintList = android.content.res.ColorStateList.valueOf(
                getColorCompat(R.color.text_secondary)
            )
            contentDescription = null
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, LinearLayout.LayoutParams(dp(16), dp(16)).apply {
            marginEnd = dp(8)
        })
        addView(TextView(this@MainActivity).apply {
            text = localizedAgentProcessText(entry.text)
            setTextColor(getColorCompat(R.color.text_secondary))
            textSize = 13f
            includeFontPadding = false
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            attachAgentTranscriptActions(this, entry)
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
    }

    private fun agentProcessIconResource(value: String): Int = when (
        AgentTranscriptPresentationPolicy.processVisualKind(value)
    ) {
        AgentTranscriptPresentationPolicy.ProcessVisualKind.ANALYSIS -> R.drawable.ic_process_analysis
        AgentTranscriptPresentationPolicy.ProcessVisualKind.COMMAND -> R.drawable.ic_process_terminal
        AgentTranscriptPresentationPolicy.ProcessVisualKind.FILE -> R.drawable.ic_process_file
        AgentTranscriptPresentationPolicy.ProcessVisualKind.IMAGE -> R.drawable.ic_process_image
        AgentTranscriptPresentationPolicy.ProcessVisualKind.NETWORK -> R.drawable.ic_process_network
        AgentTranscriptPresentationPolicy.ProcessVisualKind.GENERIC -> R.drawable.ic_process_terminal
    }

    private fun agentUserTranscriptRow(entry: AgentTranscriptEntry): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.END
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(14) }
        val blocks = AgentRichContentCodec.decode(entry.richOutputJson)
            .filter { it.type == AgentRichBlockType.IMAGE || it.type == AgentRichBlockType.FILE }
        blocks.forEach { block ->
            addView(agentUserAttachmentBlock(block), LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(6) })
        }
        val attachmentOnlyLabel = blocks.isNotEmpty() && (
            entry.text == "[${blocks.firstOrNull()?.title.orEmpty()}]" ||
                entry.text == getString(R.string.agent_attachment_count, blocks.size)
            )
        if (!attachmentOnlyLabel) {
            addView(TextView(this@MainActivity).apply {
                text = entry.text
                setTextColor(getColorCompat(R.color.text_primary))
                textSize = 16f
                setLineSpacing(dp(3).toFloat(), 1f)
                setTextIsSelectable(true)
                maxWidth = (resources.displayMetrics.widthPixels * 0.78f).toInt()
                setPadding(dp(15), dp(10), dp(15), dp(10))
                setBackgroundResource(R.drawable.bubble_self_background)
                attachAgentTranscriptActions(this, entry)
            }, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ))
        }
    }

    private fun agentUserAttachmentBlock(block: AgentRichBlock): View {
        val uri = Uri.parse(block.uri)
        if (block.type == AgentRichBlockType.IMAGE) {
            return ImageView(this).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                contentDescription = block.title
                background = GradientDrawable().apply {
                    cornerRadius = dp(8).toFloat()
                    setColor(Color.parseColor("#F4F6F8"))
                }
                clipToOutline = true
                setOnClickListener { showAgentImagePreview(uri, block.title) }
                loadAgentImageThumbnail(this, uri, dp(224), dp(168))
                layoutParams = LinearLayout.LayoutParams(dp(112), dp(84))
            }
        }
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = GradientDrawable().apply {
                cornerRadius = dp(8).toFloat()
                setColor(Color.parseColor("#F4F6F8"))
                setStroke(dp(1), Color.parseColor("#DDE2E7"))
            }
            addView(ImageView(this@MainActivity).apply {
                setImageResource(R.drawable.ic_agent_attach)
                imageTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#53606D"))
            }, LinearLayout.LayoutParams(dp(30), dp(30)).apply { marginEnd = dp(9) })
            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                addView(TextView(this@MainActivity).apply {
                    text = block.title
                    textSize = 14f
                    setTextColor(getColorCompat(R.color.text_primary))
                    maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
                })
                if (block.text.isNotBlank()) addView(TextView(this@MainActivity).apply {
                    text = block.text
                    textSize = 11f
                    setTextColor(getColorCompat(R.color.text_secondary))
                })
            }, LinearLayout.LayoutParams(dp(190), ViewGroup.LayoutParams.WRAP_CONTENT))
            setOnClickListener {
                runCatching {
                    startActivity(Intent(Intent.ACTION_VIEW).apply {
                        setDataAndType(uri, block.mimeType.ifBlank { "application/octet-stream" })
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    })
                }
            }
        }
    }

    private fun loadAgentImageThumbnail(image: ImageView, uri: Uri, width: Int, height: Int) {
        val requestKey = uri.toString()
        image.tag = requestKey
        thread(name = "signalasi-image-thumbnail") {
            val bitmap = AgentImagePipeline.loadPreview(applicationContext, uri, width, height)
            runOnUiThread {
                if (!isDestroyed && image.tag == requestKey && bitmap != null) {
                    image.setImageBitmap(bitmap)
                } else {
                    bitmap?.recycle()
                }
            }
        }
    }

    private fun showAgentImagePreview(uri: Uri, title: String) {
        val dialog = Dialog(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        val viewport = SignalASIPinchZoomViewport(this)
        val image = ImageView(this).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            contentDescription = title
        }
        viewport.attach(image)
        root.addView(viewport, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        ))
        if (title.isNotBlank()) {
            root.addView(TextView(this).apply {
                text = title
                textSize = 13f
                setTextColor(Color.WHITE)
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
                setPadding(dp(16), dp(8), dp(16), dp(8))
                background = GradientDrawable().apply {
                    cornerRadius = dp(8).toFloat()
                    setColor(Color.argb(150, 0, 0, 0))
                }
            }, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            ).apply {
                leftMargin = dp(24)
                rightMargin = dp(24)
                bottomMargin = dp(24)
            })
        }
        root.addView(ImageButton(this).apply {
            setImageResource(android.R.drawable.ic_menu_close_clear_cancel)
            imageTintList = android.content.res.ColorStateList.valueOf(Color.WHITE)
            contentDescription = getString(R.string.agent_image_preview_close)
            setPadding(dp(10), dp(10), dp(10), dp(10))
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.argb(165, 30, 30, 30))
            }
            setOnClickListener { dialog.dismiss() }
        }, FrameLayout.LayoutParams(dp(48), dp(48), Gravity.TOP or Gravity.END).apply {
            topMargin = dp(20)
            rightMargin = dp(16)
        })
        dialog.setContentView(root)
        var previewBitmap: Bitmap? = null
        dialog.setOnDismissListener {
            image.setImageDrawable(null)
            previewBitmap?.recycle()
            previewBitmap = null
        }
        dialog.show()
        val metrics = resources.displayMetrics
        thread(name = "signalasi-image-preview") {
            val bitmap = AgentImagePipeline.loadPreview(
                applicationContext,
                uri,
                metrics.widthPixels * 2,
                metrics.heightPixels * 2
            )
            runOnUiThread {
                if (dialog.isShowing && bitmap != null) {
                    previewBitmap = bitmap
                    image.setImageBitmap(bitmap)
                } else {
                    bitmap?.recycle()
                }
            }
        }
    }

    private fun attachAgentTranscriptActions(textView: TextView, entry: AgentTranscriptEntry) {
        textView.setOnLongClickListener {
            val feedbackEntry = entry.role == AgentTranscriptRole.ASSISTANT &&
                (entry.dedupeKey.startsWith("global-agent:") ||
                    entry.dedupeKey.startsWith("global-agent-digest:"))
            val helpfulLabel = getString(R.string.agent_global_feedback_helpful)
            val notRelevantLabel = getString(R.string.agent_global_feedback_not_relevant)
            val tooFrequentLabel = getString(R.string.agent_global_feedback_too_frequent)
            val copyLabel = getString(R.string.common_copy)
            val selectAllLabel = getString(R.string.common_select_all)
            val deleteLabel = getString(R.string.common_delete)
            val labels = buildList {
                if (feedbackEntry) {
                    add(helpfulLabel)
                    add(notRelevantLabel)
                    add(tooFrequentLabel)
                }
                add(copyLabel)
                add(selectAllLabel)
                add(deleteLabel)
            }.toTypedArray()
            android.app.AlertDialog.Builder(this)
                .setItems(labels) { _, which ->
                    when (labels[which]) {
                        helpfulLabel -> recordGlobalInsightFeedback(entry, GlobalAgentFeedbackKind.HELPFUL)
                        notRelevantLabel -> recordGlobalInsightFeedback(entry, GlobalAgentFeedbackKind.NOT_RELEVANT)
                        tooFrequentLabel -> recordGlobalInsightFeedback(entry, GlobalAgentFeedbackKind.TOO_FREQUENT)
                        copyLabel -> {
                            val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
                            clipboard.setPrimaryClip(ClipData.newPlainText(getString(R.string.app_name), entry.text))
                            Toast.makeText(this, getString(R.string.toast_copied), Toast.LENGTH_SHORT).show()
                        }
                        selectAllLabel -> {
                            textView.requestFocus()
                            (textView.text as? android.text.Spannable)?.let(android.text.Selection::selectAll)
                            Toast.makeText(this, getString(R.string.agent_message_all_selected), Toast.LENGTH_SHORT).show()
                        }
                        deleteLabel -> {
                            if (agentTranscriptStore.deleteEntry(entry.id)) {
                                renderedAgentTranscriptIds.clear()
                                agentOutputList.removeAllViews()
                                renderAgentTranscript(agentTranscriptStore.list())
                            }
                        }
                    }
                }
                .show()
            true
        }
    }

    private fun recordGlobalInsightFeedback(entry: AgentTranscriptEntry, kind: GlobalAgentFeedbackKind) {
        recordGlobalInsightFeedback(entry.dedupeKey, kind)
    }

    private fun recordGlobalInsightFeedback(dedupeKey: String, kind: GlobalAgentFeedbackKind): Boolean {
        val runtime = if (::globalSuperAgentRuntime.isInitialized) {
            globalSuperAgentRuntime
        } else GlobalSuperAgentRuntime.get(this)
        val count = runtime.recordProactiveFeedback(dedupeKey, kind)
        refreshGlobalInsightIndicator()
        Toast.makeText(
            this,
            getString(
                if (count > 0) R.string.agent_global_feedback_saved
                else R.string.agent_global_feedback_unavailable
            ),
            Toast.LENGTH_SHORT
        ).show()
        return count > 0
    }

    private fun handleAgentRichAction(entry: AgentTranscriptEntry, action: AgentRichAction) {
        when (action.verb) {
            "copy" -> {
                val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText(action.label, action.value))
                Toast.makeText(this, getString(R.string.toast_copied), Toast.LENGTH_SHORT).show()
            }
            "open_uri" -> {
                val uri = runCatching { Uri.parse(action.value) }.getOrNull()
                if (uri?.scheme?.lowercase() in setOf("https", "content", "file", "android.resource")) {
                    runCatching { startActivity(Intent(Intent.ACTION_VIEW, uri)) }
                        .onFailure { Toast.makeText(this, action.value, Toast.LENGTH_SHORT).show() }
                }
            }
            "set_input" -> setAgentRichInput(action.value, submit = false)
            "submit_prompt" -> setAgentRichInput(action.value, submit = true)
            "open_conversation" -> openAgentConversation(action.value)
            "approve_task", "reject_task" -> runAgentRichTaskDecision(
                entry,
                approved = action.verb == "approve_task"
            )
            "preview_runtime_artifact" -> previewRuntimeArtifact(action.value)
            "save_runtime_artifact" -> saveRuntimeArtifact(action.value)
            else -> Toast.makeText(this, action.label, Toast.LENGTH_SHORT).show()
        }
    }

    private fun previewRuntimeArtifact(rawPayload: String) {
        val payload = AgentRuntimeArtifactActionPayload.decode(rawPayload)
        if (payload == null) {
            Toast.makeText(this, R.string.agent_runtime_artifact_unavailable, Toast.LENGTH_SHORT).show()
            return
        }
        thread(name = "signalasi-artifact-preview") {
            val resolved = AgentRuntimeArtifactUi.resolve(this, payload)
            val preview = resolved.mapCatching { file -> AgentRuntimeArtifactUi.preview(file).getOrThrow() }
            runOnUiThread {
                preview.fold(
                    onSuccess = { source -> showRuntimeArtifactPreview(payload, source) },
                    onFailure = {
                        Toast.makeText(this, R.string.agent_runtime_artifact_unavailable, Toast.LENGTH_LONG).show()
                    }
                )
            }
        }
    }

    private fun showRuntimeArtifactPreview(payload: AgentRuntimeArtifactActionPayload, source: String) {
        val content = TextView(this).apply {
            text = source
            textSize = 13.5f
            typeface = android.graphics.Typeface.MONOSPACE
            setTextColor(getColorCompat(R.color.text_primary))
            setTextIsSelectable(true)
            setPadding(dp(16), dp(12), dp(16), dp(18))
        }
        val viewport = ScrollView(this).apply {
            isFillViewport = true
            addView(HorizontalScrollView(this@MainActivity).apply {
                isHorizontalScrollBarEnabled = true
                addView(content, ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ))
            })
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle(payload.displayName)
            .setView(viewport)
            .setNegativeButton(R.string.common_close, null)
            .setNeutralButton(R.string.common_copy) { _, _ ->
                val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText(payload.displayName, source))
                Toast.makeText(this, R.string.toast_copied, Toast.LENGTH_SHORT).show()
            }
            .setPositiveButton(R.string.common_save) { _, _ -> saveRuntimeArtifact(payload.encode()) }
            .create()
        dialog.setOnShowListener {
            dialog.window?.setLayout((resources.displayMetrics.widthPixels * 0.94f).toInt(), (resources.displayMetrics.heightPixels * 0.82f).toInt())
        }
        dialog.show()
    }

    private fun saveRuntimeArtifact(rawPayload: String) {
        val payload = AgentRuntimeArtifactActionPayload.decode(rawPayload)
        if (payload == null) {
            Toast.makeText(this, R.string.agent_runtime_artifact_unavailable, Toast.LENGTH_SHORT).show()
            return
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            pendingRuntimeArtifactExport = payload
            startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = payload.mimeType
                putExtra(Intent.EXTRA_TITLE, payload.displayName)
            }, REQUEST_EXPORT_RUNTIME_ARTIFACT)
            return
        }
        thread(name = "signalasi-artifact-save") {
            val result = AgentRuntimeArtifactUi.resolve(this, payload).mapCatching { source ->
                AgentRuntimeArtifactExporter(this).saveToDownloads(source, payload).getOrThrow()
            }
            runOnUiThread {
                result.fold(
                    onSuccess = { path ->
                        Toast.makeText(this, getString(R.string.agent_runtime_artifact_saved, path), Toast.LENGTH_LONG).show()
                    },
                    onFailure = {
                        Toast.makeText(this, R.string.agent_runtime_artifact_save_failed, Toast.LENGTH_LONG).show()
                    }
                )
            }
        }
    }

    private fun exportRuntimeArtifactToUri(payload: AgentRuntimeArtifactActionPayload, destination: Uri) {
        thread(name = "signalasi-artifact-export") {
            val result = AgentRuntimeArtifactUi.resolve(this, payload).mapCatching { source ->
                AgentRuntimeArtifactExporter(this).copyToUri(source, destination).getOrThrow()
            }
            runOnUiThread {
                Toast.makeText(
                    this,
                    if (result.isSuccess) R.string.agent_runtime_artifact_saved_picker else R.string.agent_runtime_artifact_save_failed,
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    private fun handleAgentRichForm(
        entry: AgentTranscriptEntry,
        block: AgentRichBlock,
        values: Map<String, String>
    ) {
        val response = JSONObject().apply {
            put("form_id", block.id)
            put("task_id", entry.taskId)
            put("values", JSONObject(values))
        }
        setAgentRichInput("${block.title.ifBlank { "Form response" }}: $response", submit = true)
    }

    private fun setAgentRichInput(value: String, submit: Boolean) {
        if (value.isBlank()) return
        agentGoalInput.setText(value)
        agentGoalInput.setSelection(agentGoalInput.text?.length ?: 0)
        if (submit) submitAgentGoal() else {
            agentGoalInput.requestFocus()
            getSystemService(InputMethodManager::class.java)
                .showSoftInput(agentGoalInput, InputMethodManager.SHOW_IMPLICIT)
        }
    }

    private fun runAgentRichTaskDecision(entry: AgentTranscriptEntry, approved: Boolean) {
        val runtimes = buildList {
            addAll(activeAgentTasks.values)
            addAll(provisionalAgentTasks)
            add(mobileNativeAgent)
        }.distinct()
        val runtime = runtimes.firstOrNull { it.snapshot().sessionId == entry.taskId }
        if (runtime == null) {
            Toast.makeText(this, getString(R.string.agent_task_detail_unavailable), Toast.LENGTH_SHORT).show()
            return
        }
        agentTranscriptStore.deleteEntry(entry.id)
        renderedAgentTranscriptIds.clear()
        agentOutputList.removeAllViews()
        renderAgentTranscript(agentTranscriptStore.list(entry.conversationId))
        thread(name = "signalasi-rich-decision") {
            val state = if (approved) {
                runtime.approveNextAction(highRiskConfirmed = true)
            } else {
                runtime.cancelCurrentTask()
            }
            runOnUiThread { renderAgentState(state, entry.conversationId, entry.turnId) }
        }
    }

    private fun agentExecutionLine(state: AgentUiState, entry: AgentAuditEntry): String? {
        val route = state.plan?.route?.targetTitle
            .orEmpty()
            .ifBlank { state.plan?.selectedAgentOrModel.orEmpty() }
            .ifBlank { getString(R.string.agent_output_on_device) }
        val phoneNativePlan = state.plan?.actions?.any { it.kind == AgentActionKind.CALL_NATIVE_TOOL } == true
        return when (entry.event) {
            AgentAuditEvent.REASONING_SUMMARY -> when {
                auditDetailValue(entry.detail, "summary_key") == "phone_development_repair" ->
                    getString(R.string.agent_trace_phone_development_repair)
                else -> auditDetailValue(entry.detail, "summary")
                    .ifBlank { getString(R.string.agent_trace_reasoning_summary, route) }
            }
            AgentAuditEvent.TOOL_STARTED -> getString(
                if (auditDetailValue(entry.detail, "command").isNotBlank()) {
                    R.string.agent_trace_phone_linux_command
                } else {
                    R.string.agent_trace_tool_started
                },
                auditDetailValue(entry.detail, "command").ifBlank {
                    agentTraceTargetLabel(auditDetailValue(entry.detail, "target").ifBlank { route })
                }
            )
            AgentAuditEvent.TOOL_COMPLETED -> {
                val target = agentTraceTargetLabel(
                    auditDetailValue(entry.detail, "target").ifBlank { route }
                )
                val duration = auditDetailValue(entry.detail, "duration_ms").toLongOrNull() ?: 0L
                val succeeded = auditDetailValue(entry.detail, "success") == "true"
                getString(
                    if (succeeded) R.string.agent_trace_tool_completed else R.string.agent_trace_tool_failed,
                    target,
                    agentTraceDuration(duration)
                )
            }
            AgentAuditEvent.PLAN_REPLANNED,
            AgentAuditEvent.PLAN_EDITED -> getString(R.string.agent_trace_plan_updated)
            AgentAuditEvent.TOOL_OUTPUT_HANDOFF -> getString(R.string.agent_trace_tool_result_ready)
            AgentAuditEvent.ACTION_RECOVERY_STARTED ->
                if (phoneNativePlan) null else getString(R.string.agent_trace_recovery_started)
            AgentAuditEvent.ACTION_RECOVERY_COMPLETED ->
                if (phoneNativePlan) null else getString(R.string.agent_trace_recovery_completed)
            AgentAuditEvent.ACTION_RECOVERY_MANUAL_REQUIRED ->
                if (phoneNativePlan) null else getString(R.string.agent_trace_recovery_manual)
            AgentAuditEvent.CONNECTOR_RESPONSE_RECEIVED -> null
            AgentAuditEvent.ACTION_EXECUTED -> when {
                entry.detail.contains("FAILED", ignoreCase = true) && phoneNativePlan -> null
                entry.detail.contains("FAILED", ignoreCase = true) -> getString(R.string.agent_trace_request_failed, route)
                else -> null
            }
            AgentAuditEvent.ACTION_BLOCKED -> if (
                entry.detail.startsWith("secondary_confirmation_required:")
            ) {
                null
            } else {
                getString(R.string.agent_trace_action_blocked)
            }
            AgentAuditEvent.TASK_PAUSED -> getString(R.string.agent_trace_task_paused)
            AgentAuditEvent.TASK_RESUMED -> getString(R.string.agent_trace_task_resumed)
            AgentAuditEvent.TASK_INTERRUPTED -> getString(R.string.agent_trace_task_interrupted)
            else -> null
        }
    }

    private fun auditDetailValue(detail: String, key: String): String = detail
        .split(';')
        .asSequence()
        .map(String::trim)
        .firstOrNull { it.startsWith("$key=") }
        ?.substringAfter('=')
        .orEmpty()

    private fun agentTraceDuration(durationMillis: Long): String =
        AgentTranscriptPresentationPolicy.formatElapsedSeconds(durationMillis)

    private fun agentProcessCompletionTimestamp(
        entry: AgentTranscriptEntry,
        entries: List<AgentTranscriptEntry> = agentTranscriptStore.list(entry.conversationId)
    ): Long? = entries.asSequence()
        .filter { candidate ->
            candidate.role == AgentTranscriptRole.ASSISTANT &&
                !isAgentApprovalEntry(candidate) &&
                when {
                    entry.turnId.isNotBlank() -> candidate.turnId == entry.turnId
                    entry.taskId.isNotBlank() -> candidate.taskId == entry.taskId
                    else -> candidate.timestampMillis >= entry.timestampMillis
                }
        }
        .maxOfOrNull(AgentTranscriptEntry::timestampMillis)

    private fun agentTraceTargetLabel(target: String): String {
        val normalized = target.lowercase(Locale.US)
        return when {
            normalized == "codex" || normalized == "codex agent" ->
                connectorAgentDisplayName("codex", "Codex")
            "on-device linux" in normalized || "phone linux" in normalized ->
                getString(R.string.agent_trace_phone_linux)
            "runtime package manager" in normalized ->
                getString(R.string.agent_trace_runtime_package_manager)
            else -> target
        }
    }

    private fun connectorAgentDisplayName(agentId: String, fallbackName: String): String {
        val contacts = AppStore.contacts(this)
        for (index in 0 until contacts.length()) {
            val contact = contacts.optJSONObject(index) ?: continue
            if (!contact.optString("agent_id").equals(agentId, ignoreCase = true)) continue
            val desktopName = contact.optString("desktop_name").trim()
            if (desktopName.isNotBlank()) return "$fallbackName \u00b7 $desktopName"
            return contact.optString("name").trim().ifBlank { fallbackName }
        }
        return fallbackName
    }

    private fun localizedAgentProcessText(value: String): String {
        val replacements = listOf(
            "Execute in the on-device Linux sandbox",
            "Run and verify in the phone's on-device Linux runtime",
            "Run and verify in the phone\u2019s on-device Linux runtime"
        )
        return replacements.fold(value) { rendered, internalTitle ->
            rendered.replace(
                oldValue = internalTitle,
                newValue = getString(R.string.agent_trace_phone_linux_verify),
                ignoreCase = true
            )
        }
    }

    private fun renderAgentToolbox(state: AgentUiState) {
        agentToolboxList.removeAllViews()
        val tools = state.runtimeContext.systemTools.take(6)
        if (tools.isEmpty()) {
            agentToolboxList.addView(agentToolboxEmptyRow())
            return
        }
        tools.forEachIndexed { index, tool ->
            agentToolboxList.addView(agentToolboxRow(tool, index))
        }
    }

    private fun renderAgentRecentTasks(state: AgentUiState) {
        agentRecentTaskList.removeAllViews()
        if (state.recentTasks.isEmpty()) {
            agentRecentTaskList.addView(agentRecentEmptyRow())
            return
        }
        state.recentTasks.forEachIndexed { index, task ->
            agentRecentTaskList.addView(agentRecentTaskRow(task, index))
        }
    }

    private fun showAgentRecentTasksPage() {
        val state = mobileNativeAgent.snapshot()
        val teams = globalSuperAgentRuntime.agentTeamSnapshots().take(20)
        showFeaturePage(getString(R.string.cc_tasks_title))
        if (state.recentTasks.isEmpty() && teams.isEmpty()) {
            featureContent.addView(agentRecentEmptyRow())
            return
        }
        if (state.recentTasks.isNotEmpty()) {
            addSectionTitle(getString(R.string.agent_section_recent_tasks))
            state.recentTasks.take(20).forEachIndexed { index, task ->
                featureContent.addView(agentRecentTaskRow(task, index))
            }
        }
        if (teams.isNotEmpty()) {
            addSectionTitle(getString(R.string.agent_team_section_title))
            teams.forEach { team ->
                featureContent.addView(featureRow(
                    title = team.goal.ifBlank { team.teamId },
                    subtitle = getString(
                        R.string.agent_team_summary,
                        team.members.count { it.deliveryMode != AgentDeliveryMode.IGNORE },
                        agentTeamStateText(team.state)
                    ),
                    iconRes = R.drawable.ic_agent_history,
                    action = getString(R.string.agent_team_details_action)
                ).apply { setOnClickListener { showAgentTeamDetails(team) } })
            }
        }
    }

    private fun showAgentTeamDetails(team: AgentTeamExecutionSnapshot) {
        val projection = AgentTeamProgressPolicy.project(team, expanded = true)
        val detail = buildString {
            appendLine(team.goal)
            appendLine()
            appendLine("${getString(R.string.agent_task_detail_status)}: ${agentTeamStateText(team.state)}")
            appendLine("${getString(R.string.agent_team_primary_label)}: ${team.primaryAgentId}")
            appendLine()
            appendLine(getString(R.string.agent_team_members_label))
            projection.members.filter { it.deliveryMode != AgentDeliveryMode.IGNORE }.forEach { member ->
                append("- ").append(member.agentId)
                if (member.role.isNotBlank()) append(" · ").append(member.role)
                append(" · ").append(agentTeamMemberStateText(member.status))
                if (member.errorMessage.isNotBlank()) append(" · ").append(member.errorMessage.take(160))
                appendLine()
            }
            if (projection.finalOutput.isNotBlank()) {
                appendLine()
                appendLine(getString(R.string.agent_team_result_label))
                append(projection.finalOutput)
            }
        }.trim()
        android.app.AlertDialog.Builder(this)
            .setTitle(getString(R.string.agent_team_details_title))
            .setMessage(detail)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun agentTeamStateText(state: AgentTeamExecutionState): String = getString(when (state) {
        AgentTeamExecutionState.QUEUED -> R.string.agent_team_state_queued
        AgentTeamExecutionState.RUNNING -> R.string.agent_team_state_running
        AgentTeamExecutionState.SUCCEEDED -> R.string.agent_team_state_succeeded
        AgentTeamExecutionState.COMPLETED_WITH_FAILURES -> R.string.agent_team_state_completed_with_failures
        AgentTeamExecutionState.FAILED -> R.string.agent_team_state_failed
        AgentTeamExecutionState.CANCELLED -> R.string.agent_team_state_cancelled
        AgentTeamExecutionState.INTERRUPTED -> R.string.agent_team_state_interrupted
    })

    private fun agentTeamMemberStateText(state: AgentSubagentStatus): String = getString(when (state) {
        AgentSubagentStatus.QUEUED -> R.string.agent_team_state_queued
        AgentSubagentStatus.RUNNING -> R.string.agent_team_state_running
        AgentSubagentStatus.SUCCEEDED -> R.string.agent_team_state_succeeded
        AgentSubagentStatus.FAILED -> R.string.agent_team_state_failed
        AgentSubagentStatus.CANCELLED -> R.string.agent_team_state_cancelled
        AgentSubagentStatus.SKIPPED -> R.string.agent_team_member_state_skipped
    })

    private fun showCapabilityLibraryPage(selectedKind: AgentCapabilityCatalogKind) {
        showFeaturePage(getString(R.string.agent_capability_library_title))
        setFeatureBackAction()
        val installedCount = agentMcpRegistry.list().size + agentSkillRuntime.list().map { it.id }.distinct().size
        featureContent.addView(featureHeroCard(
            getString(R.string.agent_capability_library_title),
            getString(R.string.agent_capability_library_subtitle),
            R.drawable.ic_agent_skill,
            if (selectedKind == AgentCapabilityCatalogKind.MCP) "#2979FF" else "#7C4DFF",
            installedCount.toString()
        ))
        addCapabilityLibraryTabs(selectedKind)
        when (selectedKind) {
            AgentCapabilityCatalogKind.MCP -> renderMcpCapabilityLibrary()
            AgentCapabilityCatalogKind.SKILL -> renderSkillCapabilityLibrary()
        }
    }

    private fun returnToCapabilityLibrary(kind: AgentCapabilityCatalogKind) {
        if (controlCenterBackStack.lastOrNull()?.route == ControlCenterRoute.MCP) controlCenterBackStack.removeLast()
        openControlCenterDestination(
            ControlCenterDestination(ControlCenterRoute.MCP, capabilityKindPayload(kind)),
            pushCurrent = false
        )
    }

    private fun addCapabilityLibraryTabs(selectedKind: AgentCapabilityCatalogKind) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dp(3), dp(3), dp(3), dp(3))
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(10).toFloat()
                setColor(Color.parseColor("#EEF1F5"))
            }
        }
        listOf(
            AgentCapabilityCatalogKind.MCP to getString(R.string.agent_mcp_title),
            AgentCapabilityCatalogKind.SKILL to getString(R.string.agent_skills_title)
        ).forEach { (kind, label) ->
            val selected = kind == selectedKind
            row.addView(TextView(this).apply {
                text = label
                gravity = Gravity.CENTER
                textSize = 14f
                setTypeface(typeface, if (selected) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
                setTextColor(getColorCompat(if (selected) R.color.text_primary else R.color.text_secondary))
                background = if (selected) GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = dp(8).toFloat()
                    setColor(Color.WHITE)
                    setStroke(1, Color.parseColor("#DDE3EA"))
                } else null
                setOnClickListener {
                    if (!selected) {
                        openControlCenterDestination(
                            ControlCenterDestination(ControlCenterRoute.MCP, capabilityKindPayload(kind)),
                            pushCurrent = false
                        )
                    }
                }
            }, LinearLayout.LayoutParams(0, dp(40), 1f))
        }
        featureContent.addView(row, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            dp(46)
        ).apply { bottomMargin = dp(14) })
    }

    private fun renderMcpCapabilityLibrary() {
        featureContent.addView(featureRow(
            getString(R.string.agent_mcp_add_remote),
            getString(R.string.agent_mcp_add_remote_subtitle),
            R.drawable.ic_protocol_link,
            getString(R.string.agent_capability_add)
        ).apply { setOnClickListener { showRemoteMcpSetupPage() } })
        featureContent.addView(featureRow(
            getString(R.string.agent_mcp_install_package),
            getString(R.string.agent_mcp_install_package_subtitle),
            R.drawable.ic_import,
            getString(R.string.common_select)
        ).apply { setOnClickListener { openMcpPackagePicker() } })

        val installed = agentMcpRegistry.list()
        addSectionTitle(getString(R.string.agent_capability_installed))
        if (installed.isEmpty()) {
            featureContent.addView(featureRow(
                getString(R.string.agent_mcp_empty),
                getString(R.string.agent_mcp_empty_subtitle),
                R.drawable.ic_agent_skill,
                ""
            ))
        } else {
            installed.forEach { connection ->
                featureContent.addView(featureRow(
                    connection.displayName,
                    mcpConnectionSubtitle(connection),
                    R.drawable.ic_agent_skill,
                    mcpConnectionStatus(connection)
                ).apply { setOnClickListener { showMcpConnectionDetailPage(connection.id) } })
            }
        }

        addSectionTitle(getString(R.string.agent_capability_recommended))
        AgentDefaultCapabilityCatalog.mcpEntries.forEach { entry ->
            val connection = installed.firstOrNull { it.catalogId == entry.id }
            featureContent.addView(featureRow(
                entry.name,
                localizedMcpSummary(entry),
                R.drawable.ic_agent_skill,
                when {
                    connection != null -> mcpConnectionStatus(connection)
                    entry.requiresPackage -> getString(R.string.agent_capability_install)
                    else -> getString(R.string.agent_capability_add)
                }
            ).apply {
                setOnClickListener {
                    when {
                        connection != null -> showMcpConnectionDetailPage(connection.id)
                        entry.requiresPackage -> openMcpPackagePicker()
                        else -> showRemoteMcpSetupPage(entry)
                    }
                }
            })
        }
    }

    private fun renderSkillCapabilityLibrary() {
        featureContent.addView(featureRow(
            getString(R.string.agent_skill_install_local),
            getString(R.string.agent_skill_install_local_subtitle),
            R.drawable.ic_import,
            getString(R.string.common_select)
        ).apply {
            setOnClickListener {
                startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "application/zip"
                    putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("application/zip", "application/octet-stream"))
                }, REQUEST_IMPORT_SKILL)
            }
        })
        val installations = agentSkillRuntime.list()
            .groupBy { it.id }
            .values
            .mapNotNull { versions -> versions.maxByOrNull { skillVersionParts(it.version) } }
            .sortedBy { it.manifest.title.lowercase(Locale.ROOT) }
        addSectionTitle(getString(R.string.agent_capability_installed))
        if (installations.isEmpty()) {
            featureContent.addView(featureRow(
                getString(R.string.agent_skills_empty),
                getString(R.string.agent_skills_empty_subtitle),
                R.drawable.ic_agent_skill,
                ""
            ))
        } else {
            installations.forEach { installation ->
                val state = getString(if (installation.enabled) R.string.agent_skill_enabled else R.string.agent_skill_disabled)
                featureContent.addView(featureRow(
                    installation.manifest.title,
                    listOf(installation.manifest.description, "v${installation.version}", getString(R.string.agent_skill_uses, installation.useCount))
                        .filter(String::isNotBlank).joinToString(" · "),
                    R.drawable.ic_agent_skill,
                    state
                ).apply { setOnClickListener { showAgentSkillDetailPage(installation.id, installation.version) } })
            }
        }

        val installedIds = installations.mapTo(mutableSetOf()) { it.id }
        val nativeTools = mobileNativeAgent.nativeToolCatalog().mapTo(mutableSetOf()) { it.id }
        addSectionTitle(getString(R.string.agent_capability_recommended))
        AgentDefaultCapabilityCatalog.skillEntries.forEach { entry ->
            val dependency = AgentCapabilityDependencyResolver.resolve(entry, agentMcpRegistry.list(), nativeTools)
            val installed = entry.id in installedIds
            featureContent.addView(featureRow(
                entry.name,
                localizedSkillSummary(entry, dependency),
                R.drawable.ic_agent_skill,
                when {
                    installed -> getString(R.string.agent_capability_added)
                    dependency.available -> getString(R.string.agent_capability_add)
                    else -> getString(R.string.agent_capability_requires_setup)
                }
            ).apply {
                setOnClickListener {
                    when {
                        installed -> installations.firstOrNull { it.id == entry.id }
                            ?.let { showAgentSkillDetailPage(it.id, it.version) }
                        !dependency.available -> {
                            Toast.makeText(this@MainActivity, getString(R.string.agent_skill_dependency_missing), Toast.LENGTH_LONG).show()
                            openControlCenterDestination(ControlCenterDestination(ControlCenterRoute.MCP), pushCurrent = false)
                        }
                        else -> runCatching { agentSkillRuntime.install(entry.manifest) }
                            .onSuccess { showAgentSkillDetailPage(it.id, it.version) }
                            .onFailure { Toast.makeText(this@MainActivity, it.message ?: getString(R.string.agent_skill_install_failed), Toast.LENGTH_LONG).show() }
                    }
                }
            })
        }
    }

    private fun showRemoteMcpSetupPage(entry: AgentMcpCatalogEntry? = null) {
        showFeaturePage(getString(R.string.agent_mcp_add_remote))
        setFeatureBackAction { returnToCapabilityLibrary(AgentCapabilityCatalogKind.MCP) }
        featureContent.addView(featureHeroCard(
            entry?.name ?: getString(R.string.agent_mcp_custom_server),
            entry?.let(::localizedMcpSummary) ?: getString(R.string.agent_mcp_custom_server_subtitle),
            R.drawable.ic_agent_skill,
            "#2979FF",
            getString(R.string.agent_mcp_remote_badge)
        ))
        val nameInput = capabilityTextInput(
            getString(R.string.agent_mcp_server_name),
            entry?.name.orEmpty(),
            getString(R.string.agent_mcp_server_name_hint)
        )
        val endpointInput = capabilityTextInput(
            getString(R.string.agent_mcp_server_url),
            entry?.defaultEndpoint.orEmpty(),
            "https://example.com/mcp"
        )
        val profiles = entry?.authProfiles ?: listOf(
            AgentMcpAuthProfile(AgentMcpAuthMethod.NONE),
            AgentMcpAuthProfile(AgentMcpAuthMethod.BEARER_TOKEN),
            AgentMcpAuthProfile(AgentMcpAuthMethod.API_KEY),
            AgentMcpAuthProfile(AgentMcpAuthMethod.USERNAME_PASSWORD),
            AgentMcpAuthProfile(AgentMcpAuthMethod.OAUTH2, supportsRefresh = true),
            AgentMcpAuthProfile(AgentMcpAuthMethod.DEVICE_CODE),
            AgentMcpAuthProfile(AgentMcpAuthMethod.DYNAMIC)
        )
        addCapabilityFormLabel(getString(R.string.agent_mcp_auth_method))
        val authSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(
                this@MainActivity,
                android.R.layout.simple_spinner_dropdown_item,
                profiles.map { mcpAuthMethodLabel(it.method) }
            )
            background = getDrawable(R.drawable.glass_card_background)
            setPadding(dp(12), 0, dp(12), 0)
        }
        featureContent.addView(authSpinner, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            dp(52)
        ).apply { bottomMargin = dp(14) })
        featureContent.addView(capabilityPrimaryButton(getString(R.string.agent_mcp_continue)) {
            runCatching {
                val profile = profiles[authSpinner.selectedItemPosition.coerceIn(profiles.indices)]
                agentMcpRegistry.addRemote(
                    displayName = nameInput.text.toString(),
                    endpoint = endpointInput.text.toString(),
                    authProfile = profile,
                    catalogId = entry?.id.orEmpty()
                )
            }.onSuccess { connection ->
                if (connection.authProfile.method == AgentMcpAuthMethod.NONE) {
                    showMcpConnectionDetailPage(connection.id)
                } else {
                    agentMcpRegistry.beginAuthentication(connection.id)
                    showMcpAuthenticationPage(connection.id)
                }
            }.onFailure { error ->
                Toast.makeText(this@MainActivity, error.message ?: getString(R.string.agent_mcp_add_failed), Toast.LENGTH_LONG).show()
            }
        })
    }

    private fun showMcpAuthenticationPage(connectionId: String) {
        val connection = agentMcpRegistry.get(connectionId) ?: return showCapabilityLibraryPage(AgentCapabilityCatalogKind.MCP)
        val step = connection.currentAuthStep ?: return showMcpConnectionDetailPage(connectionId)
        showFeaturePage(getString(R.string.agent_mcp_sign_in))
        setFeatureBackAction { showMcpConnectionDetailPage(connectionId) }
        featureContent.addView(featureHeroCard(
            connection.displayName,
            step.description.ifBlank { getString(R.string.agent_mcp_sign_in_subtitle) },
            R.drawable.ic_security_shield,
            "#2979FF",
            getString(R.string.agent_mcp_step_count, connection.authStepIndex + 1, connection.authProfile.steps.size)
        ))
        if (connection.authProfile.authorizationUrl.isNotBlank()) {
            featureContent.addView(featureRow(
                getString(R.string.agent_mcp_open_authorization),
                connection.authProfile.authorizationUrl,
                R.drawable.ic_protocol_link,
                getString(R.string.common_open)
            ).apply {
                setOnClickListener { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(connection.authProfile.authorizationUrl))) }
            })
        }
        val inputs = linkedMapOf<String, View>()
        step.fields.forEach { field ->
            addCapabilityFormLabel(field.label)
            val input: View = when (field.type) {
                AgentMcpAuthFieldType.SELECT -> Spinner(this).apply {
                    adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, field.options)
                    background = getDrawable(R.drawable.glass_card_background)
                    setPadding(dp(12), 0, dp(12), 0)
                }
                AgentMcpAuthFieldType.CHECKBOX -> CheckBox(this).apply {
                    text = field.placeholder.ifBlank { field.label }
                    setTextColor(getColorCompat(R.color.text_primary))
                }
                else -> EditText(this).apply {
                    hint = field.placeholder
                    textSize = 15f
                    setTextColor(getColorCompat(R.color.text_primary))
                    setHintTextColor(getColorCompat(R.color.text_secondary))
                    setSingleLine(true)
                    setPadding(dp(14), 0, dp(14), 0)
                    inputType = when (field.type) {
                        AgentMcpAuthFieldType.PASSWORD,
                        AgentMcpAuthFieldType.API_KEY -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                        AgentMcpAuthFieldType.PHONE -> InputType.TYPE_CLASS_PHONE
                        AgentMcpAuthFieldType.EMAIL -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
                        AgentMcpAuthFieldType.OTP,
                        AgentMcpAuthFieldType.TOTP -> InputType.TYPE_CLASS_NUMBER
                        AgentMcpAuthFieldType.URL -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
                        else -> InputType.TYPE_CLASS_TEXT
                    }
                    background = getDrawable(R.drawable.glass_card_background)
                }
            }
            inputs[field.id] = input
            featureContent.addView(input, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(52)
            ).apply { bottomMargin = dp(12) })
        }
        featureContent.addView(capabilityPrimaryButton(getString(R.string.agent_mcp_continue)) {
            val values = inputs.mapValues { (_, view) ->
                when (view) {
                    is EditText -> view.text.toString()
                    is Spinner -> view.selectedItem?.toString().orEmpty()
                    is CheckBox -> view.isChecked.toString()
                    else -> ""
                }
            }
            thread(name = "signalasi-mcp-auth") {
                val result = runCatching {
                    runBlocking {
                        AgentMcpAuthenticationCoordinator(
                            agentMcpRegistry
                        ).submitStep(connectionId, values)
                    }
                }
                runOnUiThread {
                    result.onSuccess { updated ->
                        if (updated.authState == AgentMcpAuthState.AUTHENTICATED) showMcpConnectionDetailPage(connectionId)
                        else showMcpAuthenticationPage(connectionId)
                    }.onFailure {
                        Toast.makeText(this@MainActivity, it.message ?: getString(R.string.agent_mcp_auth_failed), Toast.LENGTH_LONG).show()
                    }
                }
            }
        })
    }

    private fun showMcpConnectionDetailPage(connectionId: String) {
        val connection = agentMcpRegistry.get(connectionId) ?: return showCapabilityLibraryPage(AgentCapabilityCatalogKind.MCP)
        showFeaturePage(connection.displayName)
        setFeatureBackAction { returnToCapabilityLibrary(AgentCapabilityCatalogKind.MCP) }
        featureContent.addView(featureHeroCard(
            connection.displayName,
            mcpConnectionSubtitle(connection),
            R.drawable.ic_agent_skill,
            if (connection.isCallable(System.currentTimeMillis())) "#14C66A" else "#F0A500",
            mcpConnectionStatus(connection)
        ))
        if (connection.transport == AgentMcpTransportKind.LOCAL_STDIO) {
            val runtime = agentMcpPackageRepository.get(connection.id)?.localRuntime
            featureContent.addView(featureRow(
                getString(R.string.agent_mcp_runtime),
                runtime?.let { getString(R.string.agent_mcp_runtime_value, it.language.wireValue) }
                    ?: getString(R.string.badge_unavailable),
                R.drawable.ic_local_model,
                getString(R.string.agent_mcp_local_package_badge)
            ))
        } else {
            featureContent.addView(featureRow(
                getString(R.string.agent_mcp_endpoint),
                connection.endpoint,
                R.drawable.ic_protocol_link,
                ""
            ))
        }
        featureContent.addView(featureRow(
            getString(R.string.agent_mcp_auth_method),
            mcpAuthMethodLabel(connection.authProfile.method),
            R.drawable.ic_security_shield,
            mcpAuthStateLabel(connection.effectiveAuthState(System.currentTimeMillis()))
        ).apply {
            setOnClickListener {
                if (connection.authProfile.method != AgentMcpAuthMethod.NONE) {
                    agentMcpRegistry.beginAuthentication(connectionId)
                    showMcpAuthenticationPage(connectionId)
                }
            }
        })
        featureContent.addView(featureRow(
            getString(R.string.agent_mcp_tools),
            connection.toolIds.joinToString(" · ").ifBlank { getString(R.string.agent_mcp_tools_not_discovered) },
            R.drawable.ic_agent_skill,
            connection.toolIds.size.toString()
        ))
        featureContent.addView(featureRow(
            getString(R.string.agent_mcp_test_connection),
            connection.lastError.ifBlank { getString(R.string.agent_mcp_test_connection_subtitle) },
            R.drawable.ic_info_outline,
            getString(R.string.common_test)
        ).apply { setOnClickListener { testMcpConnection(connectionId) } })
        featureContent.addView(featureRow(
            getString(if (connection.enabled) R.string.common_enabled else R.string.status_disabled),
            getString(R.string.agent_mcp_enable_subtitle),
            R.drawable.ic_agent_control,
            getString(if (connection.enabled) R.string.common_disable else R.string.common_enable)
        ).apply {
            setOnClickListener {
                agentMcpRegistry.setEnabled(connectionId, !connection.enabled)
                showMcpConnectionDetailPage(connectionId)
            }
        })
        featureContent.addView(featureRow(
            getString(R.string.agent_mcp_remove),
            getString(R.string.agent_mcp_remove_subtitle),
            R.drawable.ic_delete,
            getString(R.string.common_delete)
        ).apply {
            setOnClickListener {
                AlertDialog.Builder(this@MainActivity)
                    .setTitle(getString(R.string.agent_mcp_remove))
                    .setMessage(connection.displayName)
                    .setNegativeButton(getString(R.string.common_cancel), null)
                    .setPositiveButton(getString(R.string.common_delete)) { _, _ ->
                        AgentMcpClientManager(this@MainActivity, agentMcpRegistry, agentMcpPackageRepository).close(connectionId)
                        agentMcpPackageRepository.delete(connectionId)
                        agentMcpRegistry.delete(connectionId)
                        showCapabilityLibraryPage(AgentCapabilityCatalogKind.MCP)
                    }.show()
            }
        })
    }

    private fun testMcpConnection(connectionId: String) {
        Toast.makeText(this, getString(R.string.agent_mcp_testing), Toast.LENGTH_SHORT).show()
        thread(name = "signalasi-mcp-test") {
            val result = runCatching {
                runBlocking { AgentMcpClientManager(this@MainActivity, agentMcpRegistry, agentMcpPackageRepository).listTools(connectionId) }
            }
            runOnUiThread {
                result.onSuccess {
                    Toast.makeText(this, getString(R.string.agent_mcp_test_success, it.size), Toast.LENGTH_SHORT).show()
                }.onFailure {
                    Toast.makeText(this, it.message ?: getString(R.string.agent_mcp_test_failed), Toast.LENGTH_LONG).show()
                }
                showMcpConnectionDetailPage(connectionId)
            }
        }
    }

    private fun openMcpPackagePicker() {
        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/zip"
            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("application/zip", "application/octet-stream", "application/vnd.signalasi.mcp"))
        }, REQUEST_IMPORT_MCP_PACKAGE)
    }

    private fun importAgentMcpPackageFromUri(uri: Uri) {
        val inspection = runCatching {
            contentResolver.openInputStream(uri)?.use { AgentMcpPackageInstaller().inspect(it) }
                ?: error("Unable to open MCP package")
        }.getOrElse { error ->
            Toast.makeText(this, error.message ?: getString(R.string.agent_mcp_package_invalid), Toast.LENGTH_LONG).show()
            return
        }
        val manifest = inspection.manifest
        val details = buildString {
            append(manifest.description)
            if (manifest.transport == AgentMcpTransportKind.LOCAL_STDIO) {
                manifest.localRuntime?.let { runtime ->
                    append("\n\n").append(getString(R.string.agent_mcp_runtime)).append(": ")
                    append(getString(R.string.agent_mcp_runtime_value, runtime.language.wireValue))
                }
            } else {
                append("\n\n").append(getString(R.string.agent_mcp_tools)).append(":\n")
                manifest.tools.take(12).forEach { append("• ").append(it.title).append('\n') }
            }
            append("\n").append(getString(if (inspection.integrityVerified) R.string.agent_mcp_integrity_verified else R.string.agent_mcp_integrity_unsigned))
        }
        AlertDialog.Builder(this)
            .setTitle("${manifest.name} · v${manifest.version}")
            .setMessage(details.trim())
            .setNegativeButton(getString(R.string.common_cancel), null)
            .setPositiveButton(getString(R.string.agent_capability_install)) { _, _ ->
                runCatching {
                    agentMcpPackageRepository.save(inspection)
                    agentMcpRegistry.installPackage(manifest, inspection.packageSha256)
                }.onSuccess { connection ->
                    if (connection.authProfile.method == AgentMcpAuthMethod.NONE) {
                        showMcpConnectionDetailPage(connection.id)
                    } else {
                        agentMcpRegistry.beginAuthentication(connection.id)
                        showMcpAuthenticationPage(connection.id)
                    }
                }.onFailure { error ->
                    agentMcpPackageRepository.delete(manifest.id)
                    Toast.makeText(this, error.message ?: getString(R.string.agent_mcp_install_failed), Toast.LENGTH_LONG).show()
                }
            }.show()
    }

    private fun capabilityTextInput(label: String, value: String, hintValue: String): EditText {
        addCapabilityFormLabel(label)
        return EditText(this).apply {
            setText(value)
            hint = hintValue
            textSize = 15f
            setTextColor(getColorCompat(R.color.text_primary))
            setHintTextColor(getColorCompat(R.color.text_secondary))
            setSingleLine(true)
            setPadding(dp(14), 0, dp(14), 0)
            background = getDrawable(R.drawable.glass_card_background)
            featureContent.addView(this, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(52)
            ).apply { bottomMargin = dp(12) })
        }
    }

    private fun addCapabilityFormLabel(label: String) {
        featureContent.addView(TextView(this).apply {
            text = label
            textSize = 12f
            setTextColor(getColorCompat(R.color.text_secondary))
            setPadding(dp(4), dp(3), 0, dp(6))
        })
    }

    private fun capabilityPrimaryButton(label: String, action: () -> Unit): TextView = TextView(this).apply {
        text = label
        gravity = Gravity.CENTER
        textSize = 15f
        setTypeface(typeface, android.graphics.Typeface.BOLD)
        setTextColor(Color.WHITE)
        background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(10).toFloat()
            setColor(getColorCompat(R.color.signalasi_green))
        }
        setOnClickListener { action() }
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(50)).apply {
            topMargin = dp(5)
            bottomMargin = dp(14)
        }
    }

    private fun mcpConnectionSubtitle(connection: AgentMcpConnection): String = listOf(
        if (connection.distribution == AgentMcpDistribution.LOCAL_PACKAGE) getString(R.string.agent_mcp_local_package_badge) else getString(R.string.agent_mcp_remote_badge),
        mcpAuthMethodLabel(connection.authProfile.method),
        connection.lastError.takeIf(String::isNotBlank)
    ).filterNotNull().joinToString(" · ")

    private fun mcpConnectionStatus(connection: AgentMcpConnection): String = when {
        !connection.enabled -> getString(R.string.status_disabled)
        connection.state == AgentMcpConnectionState.CONNECTED -> getString(R.string.status_connected)
        connection.effectiveAuthState(System.currentTimeMillis()) in setOf(
            AgentMcpAuthState.NOT_CONFIGURED,
            AgentMcpAuthState.CHALLENGE_REQUIRED,
            AgentMcpAuthState.REAUTHENTICATION_REQUIRED,
            AgentMcpAuthState.ERROR
        ) -> getString(R.string.agent_capability_requires_setup)
        connection.state == AgentMcpConnectionState.ERROR -> getString(R.string.agent_mcp_status_error)
        else -> getString(R.string.status_ready)
    }

    private fun mcpAuthMethodLabel(method: AgentMcpAuthMethod): String = getString(when (method) {
        AgentMcpAuthMethod.NONE -> R.string.agent_mcp_auth_none
        AgentMcpAuthMethod.BEARER_TOKEN -> R.string.agent_mcp_auth_token
        AgentMcpAuthMethod.API_KEY -> R.string.agent_mcp_auth_api_key
        AgentMcpAuthMethod.USERNAME_PASSWORD -> R.string.agent_mcp_auth_password
        AgentMcpAuthMethod.OAUTH2 -> R.string.agent_mcp_auth_oauth
        AgentMcpAuthMethod.DEVICE_CODE -> R.string.agent_mcp_auth_device_code
        AgentMcpAuthMethod.DYNAMIC -> R.string.agent_mcp_auth_dynamic
    })

    private fun mcpAuthStateLabel(state: AgentMcpAuthState): String = getString(when (state) {
        AgentMcpAuthState.NOT_REQUIRED -> R.string.agent_mcp_auth_not_required
        AgentMcpAuthState.AUTHENTICATED -> R.string.agent_mcp_auth_authenticated
        AgentMcpAuthState.REFRESHING -> R.string.agent_mcp_auth_refreshing
        AgentMcpAuthState.REAUTHENTICATION_REQUIRED -> R.string.agent_mcp_auth_reauth_required
        AgentMcpAuthState.ERROR -> R.string.agent_mcp_status_error
        else -> R.string.agent_capability_requires_setup
    })

    private fun localizedMcpSummary(entry: AgentMcpCatalogEntry): String = getString(when (entry.id) {
        "signalasi.mcp.github" -> R.string.agent_mcp_catalog_github
        "signalasi.mcp.notion" -> R.string.agent_mcp_catalog_notion
        "signalasi.mcp.home_assistant" -> R.string.agent_mcp_catalog_home_assistant
        "signalasi.mcp.relay_controller" -> R.string.agent_mcp_catalog_relay
        else -> R.string.agent_mcp_custom_server_subtitle
    })

    private fun localizedSkillSummary(
        entry: AgentSkillCatalogEntry,
        dependency: AgentCapabilityDependencyStatus
    ): String {
        val summary = getString(when (entry.id) {
            "signalasi.catalog.deep-research" -> R.string.agent_skill_catalog_research
            "signalasi.catalog.device-health" -> R.string.agent_skill_catalog_device_health
            "signalasi.catalog.github-triage" -> R.string.agent_skill_catalog_github
            "signalasi.catalog.notion-brief" -> R.string.agent_skill_catalog_notion
            "signalasi.catalog.smart-home-routine" -> R.string.agent_skill_catalog_smart_home
            else -> R.string.agent_skills_subtitle
        })
        return if (dependency.available) summary else "$summary · ${getString(R.string.agent_skill_dependency_missing)}"
    }

    private fun showAgentSkillDetailPage(id: String, version: String) {
        val installation = agentSkillRuntime.get(id, version)
            ?: return showCapabilityLibraryPage(AgentCapabilityCatalogKind.SKILL)
        val manifest = installation.manifest
        showFeaturePage(manifest.title)
        setFeatureBackAction { returnToCapabilityLibrary(AgentCapabilityCatalogKind.SKILL) }
        featureContent.addView(featureHeroCard(
            manifest.title,
            manifest.description.ifBlank { manifest.instructions.take(180) },
            R.drawable.ic_agent_node,
            "#14C66A",
            "v${manifest.version}"
        ))
        featureContent.addView(featureRow(
            getString(if (installation.enabled) R.string.agent_skill_enabled else R.string.agent_skill_disabled),
            manifest.source,
            R.drawable.ic_agent_control,
            getString(if (installation.enabled) R.string.common_disable else R.string.common_enable)
        ).apply {
            setOnClickListener {
                if (installation.enabled) agentSkillRuntime.disable(id, version) else agentSkillRuntime.enable(id, version)
                showAgentSkillDetailPage(id, version)
            }
        })
        featureContent.addView(featureRow(
            getString(R.string.agent_skill_auto_invoke),
            manifest.triggerExamples.take(3).joinToString(" · "),
            R.drawable.ic_protocol_link,
            getString(if (installation.autoInvoke) R.string.status_enabled else R.string.status_disabled)
        ).apply {
            setOnClickListener {
                agentSkillRuntime.setAutoInvoke(id, version, !installation.autoInvoke)
                showAgentSkillDetailPage(id, version)
            }
        })
        featureContent.addView(featureRow(
            getString(R.string.agent_skill_workflow),
            manifest.steps.joinToString(" → ") { it.toolId },
            R.drawable.ic_agent_history,
            manifest.steps.size.toString()
        ))
        featureContent.addView(featureRow(
            getString(R.string.agent_skill_permissions),
            (manifest.permissions + manifest.nativeTools).joinToString("\n"),
            R.drawable.ic_security_shield,
            manifest.permissions.size.toString()
        ))
        val versions = agentSkillRuntime.list().filter { it.id == id }.sortedByDescending { skillVersionParts(it.version) }
        featureContent.addView(featureRow(
            getString(R.string.agent_skill_versions),
            versions.joinToString(" · ") { "v${it.version}" },
            R.drawable.ic_agent_history,
            versions.size.toString()
        ).apply {
            setOnClickListener {
                val labels = versions.map { "v${it.version}${if (it.version == version) " · current" else ""}" }
                android.app.AlertDialog.Builder(this@MainActivity)
                    .setTitle(getString(R.string.agent_skill_versions))
                    .setItems(labels.toTypedArray()) { _, index -> showAgentSkillDetailPage(id, versions[index].version) }
                    .setNegativeButton(getString(R.string.common_cancel), null)
                    .show()
            }
        })
        featureContent.addView(featureRow(
            getString(R.string.agent_skill_run_test),
            getString(R.string.agent_skill_test_passed),
            R.drawable.ic_agent_screen,
            getString(R.string.common_view)
        ).apply {
            setOnClickListener {
                val result = agentSkillRuntime.validate(manifest)
                Toast.makeText(
                    this@MainActivity,
                    if (result.isValid) getString(R.string.agent_skill_test_passed)
                    else result.issues.joinToString("; ") { it.message },
                    Toast.LENGTH_LONG
                ).show()
            }
        })
        featureContent.addView(featureRow(
            getString(R.string.agent_skill_export),
            getString(R.string.agent_skill_export_subtitle),
            R.drawable.ic_export,
            getString(R.string.common_export)
        ).apply {
            setOnClickListener {
                pendingExportSkill = id to version
                val safeTitle = manifest.title.replace(Regex("[^a-zA-Z0-9._-]+"), "-")
                    .trim('-').ifBlank { "signalasi-skill" }
                startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "application/zip"
                    putExtra(Intent.EXTRA_TITLE, "$safeTitle-v${manifest.version}.skill.zip")
                }, REQUEST_EXPORT_SKILL)
            }
        })
        featureContent.addView(featureRow(
            getString(R.string.agent_skill_uninstall),
            "${manifest.title} v${manifest.version}",
            R.drawable.ic_delete,
            getString(R.string.common_delete)
        ).apply {
            setOnClickListener {
                android.app.AlertDialog.Builder(this@MainActivity)
                    .setTitle(getString(R.string.agent_skill_uninstall))
                    .setMessage("${manifest.title} v${manifest.version}")
                    .setNegativeButton(getString(R.string.common_cancel), null)
                    .setPositiveButton(getString(R.string.common_delete)) { _, _ ->
                        agentSkillRuntime.delete(id, version)
                        showCapabilityLibraryPage(AgentCapabilityCatalogKind.SKILL)
                    }.show()
            }
        })
    }

    private fun capabilityKindPayload(kind: AgentCapabilityCatalogKind): String =
        if (kind == AgentCapabilityCatalogKind.SKILL) CAPABILITY_KIND_SKILL else CAPABILITY_KIND_MCP

    private fun skillVersionParts(version: String): String = version.split('.')
        .joinToString(".") { (it.toIntOrNull() ?: 0).toString().padStart(8, '0') }

    private fun importAgentSkillFromUri(uri: Uri) {
        val bytes = runCatching {
            contentResolver.openInputStream(uri)?.use { input ->
                val output = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(16 * 1024)
                var total = 0
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    total += count
                    require(total <= AgentSkillPackageInstaller.MAX_PACKAGE_BYTES) { "Skill package is too large" }
                    output.write(buffer, 0, count)
                }
                output.toByteArray()
            } ?: error("Unable to open Skill package")
        }.getOrElse { error ->
            Toast.makeText(this, error.message ?: getString(R.string.agent_skill_install_failed), Toast.LENGTH_LONG).show()
            return
        }
        val installer = AgentSkillPackageInstaller(agentSkillRuntime)
        val inspection = runCatching { installer.inspect(bytes.inputStream()) }.getOrElse { error ->
            Toast.makeText(this, error.message ?: getString(R.string.agent_skill_install_failed), Toast.LENGTH_LONG).show()
            return
        }
        val manifest = inspection.manifest
        val details = buildString {
            append(manifest.description.ifBlank { manifest.instructions.take(220) })
            append("\n\n").append(getString(R.string.agent_skill_workflow)).append(":\n")
            manifest.nativeTools.forEach { append("• ").append(it).append('\n') }
            append('\n').append(getString(R.string.agent_skill_permissions)).append(":\n")
            if (manifest.permissions.isEmpty()) append("• None\n") else manifest.permissions.forEach { append("• ").append(it).append('\n') }
            append('\n').append(if (inspection.integrityVerified) getString(R.string.agent_skill_integrity_verified) else getString(R.string.agent_skill_integrity_unsigned))
        }
        android.app.AlertDialog.Builder(this)
            .setTitle("${manifest.title} · v${manifest.version}")
            .setMessage(details.trim())
            .setNegativeButton(getString(R.string.common_cancel), null)
            .setPositiveButton(getString(R.string.agent_skill_install)) { _, _ ->
                runCatching { installer.install(bytes.inputStream(), allowUnsignedLocalPackage = true) }
                    .onSuccess { showAgentSkillDetailPage(it.id, it.version) }
                    .onFailure { Toast.makeText(this, it.message ?: getString(R.string.agent_skill_install_failed), Toast.LENGTH_LONG).show() }
            }.show()
    }

    private fun exportAgentSkillToUri(uri: Uri) {
        val target = pendingExportSkill
        pendingExportSkill = null
        val installation = target?.let { agentSkillRuntime.get(it.first, it.second) }
        if (installation == null) {
            Toast.makeText(this, getString(R.string.agent_skill_export_failed, "Skill not found"), Toast.LENGTH_LONG).show()
            return
        }
        runCatching {
            val archive = AgentSkillPackageExporter.export(installation.manifest)
            contentResolver.openOutputStream(uri, "w")?.use { it.write(archive) }
                ?: error("Unable to open export destination")
        }.onSuccess {
            Toast.makeText(this, getString(R.string.agent_skill_export_success), Toast.LENGTH_LONG).show()
        }.onFailure { error ->
            Toast.makeText(
                this,
                getString(R.string.agent_skill_export_failed, error.message ?: error.javaClass.simpleName),
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun renderAgentActionQueue(state: AgentUiState) {
        agentActionQueueList.removeAllViews()
        val actions = state.plan?.actions.orEmpty()
        if (actions.isEmpty()) {
            agentActionQueueList.addView(agentActionQueueEmptyRow())
            return
        }
        actions.forEachIndexed { index, action ->
            agentActionQueueList.addView(agentActionQueueRow(action, index))
        }
    }

    private fun renderAgentRequirements(state: AgentUiState) {
        agentRequirementList.removeAllViews()
        val requirements = state.plan?.requiredPermissions.orEmpty()
        if (requirements.isEmpty()) {
            agentRequirementList.addView(agentRequirementsEmptyRow())
            return
        }
        requirements.forEachIndexed { index, requirement ->
            agentRequirementList.addView(agentRequirementRow(requirement, index))
        }
    }

    private fun renderAgentPlanContext(state: AgentUiState) {
        agentPlanContextList.removeAllViews()
        val plan = state.plan
        if (plan == null) {
            agentPlanContextList.addView(agentPlanContextEmptyRow())
            return
        }

        val routeLabel = listOfNotNull(
            plan.route.kind.name.lowercase(Locale.US).replace('_', ' '),
            plan.route.targetTitle.ifBlank { plan.selectedAgentOrModel }.ifBlank { null }
        ).joinToString(" / ")
        val rows = listOf(
            R.string.agent_plan_context_goal to state.currentGoal.ifBlank { plan.goal },
            R.string.agent_plan_context_planner to plan.plannerProfile.ifBlank { "rule-based-local" },
            R.string.agent_plan_context_route to routeLabel.ifBlank { plan.selectedAgentOrModel.ifBlank { "-" } },
            R.string.agent_plan_context_reason to plan.routeRationale.ifBlank { "-" },
            R.string.agent_plan_context_expected to plan.expectedResult.ifBlank { "-" },
            R.string.agent_plan_context_rollback to plan.rollbackStrategy.ifBlank { "-" },
            R.string.agent_plan_context_revision to getString(
                R.string.agent_plan_context_revision_value,
                plan.revision,
                plan.replanCount
            ),
            R.string.agent_plan_context_checkpoints to plan.checkpoints.count {
                it.status == AgentCheckpointStatus.ACTIVE
            }.toString(),
            R.string.agent_plan_context_tool_graph to getString(
                R.string.agent_plan_context_tool_graph_value,
                plan.toolGraphDepth(),
                plan.actionHistory.size
            ),
            R.string.agent_plan_context_tool_budget to getString(
                R.string.agent_plan_context_tool_budget_value,
                AgentAutonomyGuard.completedToolCalls(plan),
                mobileNativeAgent.modelPlannerSettings().maxToolCalls
            ),
            R.string.agent_plan_context_timeout to getString(R.string.agent_plan_context_timeout_value, plan.timeoutSeconds)
        )
        rows.forEachIndexed { index, row ->
            agentPlanContextList.addView(agentPlanContextRow(getString(row.first), row.second, index))
        }
    }

    private fun renderAgentVerification(state: AgentUiState) {
        agentVerificationList.removeAllViews()
        val results = state.plan?.verificationResults.orEmpty().takeLast(4).asReversed()
        if (results.isEmpty()) {
            agentVerificationList.addView(agentVerificationEmptyRow())
            return
        }
        results.forEachIndexed { index, result ->
            agentVerificationList.addView(agentVerificationRow(result, index))
        }
    }

    private fun renderAgentAuditTrail(state: AgentUiState) {
        agentAuditTrailList.removeAllViews()
        val events = state.auditTrail.takeLast(6).asReversed()
        if (events.isEmpty()) {
            agentAuditTrailList.addView(agentAuditEmptyRow())
            return
        }
        events.forEachIndexed { index, entry ->
            agentAuditTrailList.addView(agentAuditRow(entry, index))
        }
    }

    private fun agentToolboxEmptyRow(): View {
        return TextView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(48)
            )
            setBackgroundResource(R.drawable.agent_step_background)
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), 0, dp(14), 0)
            setTextColor(getColorCompat(R.color.text_secondary))
            textSize = 13f
            text = getString(R.string.agent_toolbox_empty)
        }
    }

    private fun agentVerificationEmptyRow(): View {
        return TextView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(48)
            )
            setBackgroundResource(R.drawable.agent_step_background)
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), 0, dp(14), 0)
            setTextColor(getColorCompat(R.color.text_secondary))
            textSize = 13f
            text = getString(R.string.agent_verification_empty)
        }
    }

    private fun agentPlanContextEmptyRow(): View {
        return TextView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(48)
            )
            setBackgroundResource(R.drawable.agent_step_background)
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), 0, dp(14), 0)
            setTextColor(getColorCompat(R.color.text_secondary))
            textSize = 13f
            text = getString(R.string.agent_plan_context_empty)
        }
    }

    private fun agentActionQueueEmptyRow(): View {
        return TextView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(48)
            )
            setBackgroundResource(R.drawable.agent_step_background)
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), 0, dp(14), 0)
            setTextColor(getColorCompat(R.color.text_secondary))
            textSize = 13f
            text = getString(R.string.agent_action_queue_empty)
        }
    }

    private fun agentRequirementsEmptyRow(): View {
        return TextView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(48)
            )
            setBackgroundResource(R.drawable.agent_step_background)
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), 0, dp(14), 0)
            setTextColor(getColorCompat(R.color.text_secondary))
            textSize = 13f
            text = getString(R.string.agent_requirements_empty)
        }
    }

    private fun agentRecentEmptyRow(): View {
        return TextView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(54)
            )
            setBackgroundResource(R.drawable.agent_step_background)
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), 0, dp(14), 0)
            setTextColor(getColorCompat(R.color.text_secondary))
            textSize = 14f
            text = getString(R.string.agent_recent_empty)
        }
    }

    private fun agentAuditEmptyRow(): View {
        return TextView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(48)
            )
            setBackgroundResource(R.drawable.agent_step_background)
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), 0, dp(14), 0)
            setTextColor(getColorCompat(R.color.text_secondary))
            textSize = 13f
            text = getString(R.string.agent_audit_empty)
        }
    }

    private fun agentToolboxRow(tool: AgentSystemTool, index: Int): View {
        val statusColor = when (tool.risk) {
            AgentRisk.LOW -> getColorCompat(R.color.wechat_green)
            AgentRisk.MEDIUM -> getColorCompat(R.color.signalasi_green)
            AgentRisk.HIGH,
            AgentRisk.BLOCKED -> getColorCompat(R.color.unread_red)
        }
        val example = tool.examples.firstOrNull().orEmpty()
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundResource(R.drawable.agent_step_background)
            setPadding(dp(14), dp(10), dp(14), dp(10))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                if (index > 0) topMargin = dp(8)
            }
            if (example.isNotBlank()) {
                setOnClickListener { prefillAgentGoal(example) }
            }

            addView(TextView(this@MainActivity).apply {
                layoutParams = LinearLayout.LayoutParams(dp(9), dp(9)).apply {
                    marginEnd = dp(12)
                }
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(statusColor)
                }
            })

            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                addView(TextView(this@MainActivity).apply {
                    setTextColor(getColorCompat(R.color.text_primary))
                    textSize = 13f
                    maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                    text = tool.title
                })
                addView(TextView(this@MainActivity).apply {
                    setTextColor(getColorCompat(R.color.text_secondary))
                    textSize = 11f
                    maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                    text = example.ifBlank { tool.kind.name.lowercase(Locale.US).replace('_', ' ') }
                })
            })

            addView(TextView(this@MainActivity).apply {
                setTextColor(statusColor)
                textSize = 12f
                setTypeface(null, android.graphics.Typeface.BOLD)
                text = getString(
                    R.string.agent_toolbox_meta,
                    tool.kind.name.lowercase(Locale.US).replace('_', ' '),
                    tool.risk.name.lowercase(Locale.US)
                )
            })
        }
    }

    private fun agentPlanContextRow(title: String, value: String, index: Int): View {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundResource(R.drawable.agent_step_background)
            setPadding(dp(14), dp(10), dp(14), dp(10))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                if (index > 0) topMargin = dp(8)
            }

            addView(TextView(this@MainActivity).apply {
                layoutParams = LinearLayout.LayoutParams(dp(82), ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    marginEnd = dp(10)
                }
                setTextColor(getColorCompat(R.color.text_secondary))
                textSize = 12f
                setTypeface(null, android.graphics.Typeface.BOLD)
                text = title
            })

            addView(TextView(this@MainActivity).apply {
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                setTextColor(getColorCompat(R.color.text_primary))
                textSize = 13f
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                text = value
            })
        }
    }

    private fun agentVerificationRow(result: AgentVerificationResult, index: Int): View {
        val statusColor = if (result.success) getColorCompat(R.color.wechat_green) else getColorCompat(R.color.unread_red)
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundResource(R.drawable.agent_step_background)
            setPadding(dp(14), dp(10), dp(14), dp(10))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                if (index > 0) topMargin = dp(8)
            }

            addView(TextView(this@MainActivity).apply {
                layoutParams = LinearLayout.LayoutParams(dp(9), dp(9)).apply {
                    marginEnd = dp(12)
                }
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(statusColor)
                }
            })

            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)

                addView(TextView(this@MainActivity).apply {
                    setTextColor(getColorCompat(R.color.text_primary))
                    textSize = 13f
                    maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                    text = result.observedTitle.ifBlank { result.observedApp.ifBlank { "-" } }
                })

                addView(TextView(this@MainActivity).apply {
                    setTextColor(getColorCompat(R.color.text_secondary))
                    textSize = 11f
                    maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                    text = getString(
                        R.string.agent_verification_meta,
                        result.observedApp.ifBlank { "-" },
                        result.visibleTextCount,
                        result.clickableNodeCount
                    )
                })

                if (result.recoveryDecision != AgentRecoveryDecision.NOT_NEEDED) {
                    addView(TextView(this@MainActivity).apply {
                        setTextColor(
                            getColorCompat(
                                if (result.recoveryDecision == AgentRecoveryDecision.RETRY_SUCCEEDED) {
                                    R.color.wechat_green
                                } else {
                                    R.color.text_secondary
                                }
                            )
                        )
                        textSize = 11f
                        maxLines = 1
                        ellipsize = android.text.TextUtils.TruncateAt.END
                        text = getString(
                            R.string.agent_verification_recovery,
                            recoveryDecisionLabel(result.recoveryDecision),
                            result.recoveryAttemptCount
                        )
                    })
                }

                addView(TextView(this@MainActivity).apply {
                    setTextColor(getColorCompat(R.color.text_secondary))
                    textSize = 11f
                    maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                    text = getString(
                        R.string.agent_verification_observation,
                        observationDecisionLabel(result.observationDecision),
                        result.observationSampleCount,
                        result.observationDurationMillis
                    )
                })

                if (result.evidence.isNotBlank()) {
                    addView(TextView(this@MainActivity).apply {
                        setTextColor(getColorCompat(R.color.text_secondary))
                        textSize = 11f
                        maxLines = 1
                        ellipsize = android.text.TextUtils.TruncateAt.END
                        text = result.evidence
                    })
                }
            })

            addView(TextView(this@MainActivity).apply {
                setTextColor(statusColor)
                textSize = 12f
                setTypeface(null, android.graphics.Typeface.BOLD)
                text = getString(
                    if (result.success) R.string.agent_verification_success else R.string.agent_verification_failed
                )
            })

            addView(TextView(this@MainActivity).apply {
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    marginStart = dp(8)
                }
                setTextColor(getColorCompat(R.color.text_secondary))
                textSize = 11f
                text = getString(R.string.agent_audit_meta, agentAuditAge(result.timestampMillis))
            })
        }
    }

    private fun observationDecisionLabel(decision: AgentObservationDecision): String = getString(
        when (decision) {
            AgentObservationDecision.ACTION_FAILED -> R.string.agent_observation_action_failed
            AgentObservationDecision.NO_CHANGE_REQUIRED -> R.string.agent_observation_no_change_required
            AgentObservationDecision.CHANGED_AND_STABLE -> R.string.agent_observation_changed_stable
            AgentObservationDecision.CHANGED_BUT_UNSTABLE -> R.string.agent_observation_changed_unstable
            AgentObservationDecision.TIMED_OUT -> R.string.agent_observation_timed_out
        }
    )

    private fun recoveryDecisionLabel(decision: AgentRecoveryDecision): String = getString(
        when (decision) {
            AgentRecoveryDecision.NOT_NEEDED -> R.string.agent_recovery_not_needed
            AgentRecoveryDecision.RETRY_SUCCEEDED -> R.string.agent_recovery_succeeded
            AgentRecoveryDecision.RETRY_FAILED -> R.string.agent_recovery_failed
            AgentRecoveryDecision.MANUAL_REQUIRED -> R.string.agent_recovery_manual_required
        }
    )

    private fun agentRecentTaskRow(task: AgentTaskRecord, index: Int): View {
        val statusText = agentTaskStatusText(task)
        val statusColor = when {
            task.blocked -> getColorCompat(R.color.unread_red)
            task.phase == AgentPhase.COMPLETED -> getColorCompat(R.color.wechat_green)
            task.phase == AgentPhase.FAILED -> getColorCompat(R.color.unread_red)
            task.phase == AgentPhase.CANCELLED -> getColorCompat(R.color.text_secondary)
            task.phase == AgentPhase.PAUSED -> getColorCompat(R.color.text_secondary)
            else -> getColorCompat(R.color.signalasi_green)
        }
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundResource(R.drawable.agent_step_background)
            setPadding(dp(14), dp(10), dp(14), dp(10))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                if (index > 0) topMargin = dp(8)
            }

            addView(TextView(this@MainActivity).apply {
                layoutParams = LinearLayout.LayoutParams(dp(28), dp(28)).apply {
                    marginEnd = dp(10)
                }
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(statusColor)
                }
                gravity = Gravity.CENTER
                setTextColor(getColorCompat(R.color.white))
                textSize = 12f
                setTypeface(null, android.graphics.Typeface.BOLD)
                text = "${index + 1}"
            })

            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)

                addView(TextView(this@MainActivity).apply {
                    setTextColor(getColorCompat(R.color.text_primary))
                    textSize = 14f
                    maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                    text = task.goal
                })

                addView(TextView(this@MainActivity).apply {
                    setTextColor(getColorCompat(R.color.text_secondary))
                    textSize = 12f
                    maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                    text = getString(
                        R.string.agent_recent_meta,
                        task.routeKind.name.lowercase(Locale.US).replace('_', ' '),
                        task.targetTitle.ifBlank { "-" },
                        task.risk.name.lowercase(Locale.US)
                    )
                })
            })

            addView(TextView(this@MainActivity).apply {
                setTextColor(statusColor)
                textSize = 12f
                setTypeface(null, android.graphics.Typeface.BOLD)
                text = statusText
            })
            isClickable = true
            isFocusable = true
            setOnClickListener { showAgentTaskDetails(task) }
        }
    }

    private fun showAgentTaskDetails(task: AgentTaskRecord) {
        val detail = buildString {
            appendLine(task.goal)
            appendLine()
            appendLine("${getString(R.string.agent_task_detail_status)}: ${agentTaskStatusText(task)}")
            appendLine("${getString(R.string.agent_task_detail_route)}: ${task.routeKind.name.lowercase(Locale.US).replace('_', ' ')}")
            appendLine("${getString(R.string.agent_task_detail_target)}: ${task.targetTitle.ifBlank { "-" }}")
            appendLine("${getString(R.string.agent_task_detail_risk)}: ${task.risk.name.lowercase(Locale.US)}")
            appendLine("${getString(R.string.agent_task_detail_updated)}: ${listTime(task.updatedAtMillis)}")
            if (task.result.isNotBlank()) {
                appendLine()
                appendLine(getString(R.string.agent_task_detail_result))
                appendLine(task.result)
            }
            if (task.verification.isNotBlank()) {
                appendLine()
                appendLine(getString(R.string.agent_task_detail_verification))
                append(task.verification)
            }
            if (task.outputFiles.isNotEmpty()) {
                appendLine()
                appendLine(getString(R.string.agent_task_detail_files))
                append(task.outputFiles.joinToString("\n"))
            }
            if (task.executionLog.isNotEmpty()) {
                appendLine()
                appendLine(getString(R.string.agent_task_detail_timeline))
                append(task.executionLog.joinToString("\n"))
            }
        }.trim()
        android.app.AlertDialog.Builder(this)
            .setTitle(getString(R.string.agent_task_detail_title))
            .setMessage(detail)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun agentRequirementRow(requirement: AgentPermissionRequirement, index: Int): View {
        val statusColor = if (requirement.granted) getColorCompat(R.color.wechat_green) else getColorCompat(R.color.unread_red)
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundResource(R.drawable.agent_step_background)
            setPadding(dp(14), dp(10), dp(14), dp(10))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                if (index > 0) topMargin = dp(8)
            }

            addView(TextView(this@MainActivity).apply {
                layoutParams = LinearLayout.LayoutParams(dp(9), dp(9)).apply {
                    marginEnd = dp(12)
                }
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(statusColor)
                }
            })

            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                addView(TextView(this@MainActivity).apply {
                    setTextColor(getColorCompat(R.color.text_primary))
                    textSize = 13f
                    maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                    text = requirement.title
                })
                addView(TextView(this@MainActivity).apply {
                    setTextColor(getColorCompat(R.color.text_secondary))
                    textSize = 11f
                    maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                    text = requirement.id
                })
            })

            addView(TextView(this@MainActivity).apply {
                setTextColor(statusColor)
                textSize = 12f
                setTypeface(null, android.graphics.Typeface.BOLD)
                text = getString(if (requirement.granted) R.string.agent_requirement_granted else R.string.agent_requirement_missing)
            })
        }
    }

    private fun agentActionQueueRow(action: AgentAction, index: Int): View {
        val statusColor = agentActionStatusColor(action.status)
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundResource(R.drawable.agent_step_background)
            setPadding(dp(14), dp(10), dp(14), dp(10))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                if (index > 0) topMargin = dp(8)
            }

            addView(TextView(this@MainActivity).apply {
                layoutParams = LinearLayout.LayoutParams(dp(28), dp(28)).apply {
                    marginEnd = dp(10)
                }
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(statusColor)
                }
                gravity = Gravity.CENTER
                setTextColor(getColorCompat(R.color.white))
                textSize = 12f
                setTypeface(null, android.graphics.Typeface.BOLD)
                text = "${index + 1}"
            })

            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)

                addView(TextView(this@MainActivity).apply {
                    setTextColor(getColorCompat(R.color.text_primary))
                    textSize = 14f
                    maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                    text = action.description.ifBlank { action.kind.name.lowercase(Locale.US) }
                })

                addView(TextView(this@MainActivity).apply {
                    setTextColor(getColorCompat(R.color.text_secondary))
                    textSize = 12f
                    maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                    text = getString(
                        R.string.agent_action_queue_meta,
                        action.target.ifBlank { action.kind.name.lowercase(Locale.US) },
                        action.risk.name.lowercase(Locale.US)
                    )
                })

                if (action.dependencyIds().isNotEmpty()) {
                    addView(TextView(this@MainActivity).apply {
                        setTextColor(getColorCompat(R.color.text_secondary))
                        textSize = 11f
                        maxLines = 1
                        ellipsize = android.text.TextUtils.TruncateAt.END
                        text = getString(
                            R.string.agent_action_queue_dependencies,
                            action.dependencyIds().size,
                            action.outputSourceIds().size
                        )
                    })
                }

                if (action.result.isNotBlank()) {
                    addView(TextView(this@MainActivity).apply {
                        setTextColor(getColorCompat(R.color.text_secondary))
                        textSize = 11f
                        maxLines = 1
                        ellipsize = android.text.TextUtils.TruncateAt.END
                        text = getString(R.string.agent_action_queue_result, action.result)
                    })
                }
            })

            addView(TextView(this@MainActivity).apply {
                setTextColor(statusColor)
                textSize = 12f
                setTypeface(null, android.graphics.Typeface.BOLD)
                text = action.status.name.lowercase(Locale.US).replace('_', ' ')
            })
            if (action.status in setOf(
                    AgentActionStatus.PROPOSED,
                    AgentActionStatus.PENDING_CONFIRMATION
                )
            ) {
                setOnClickListener { showAgentPlanActionEditMenu(action) }
            }
        }
    }

    private fun showAgentPlanActionEditMenu(action: AgentAction) {
        val labels = arrayOf(
            getString(R.string.agent_plan_edit_action),
            getString(R.string.agent_plan_move_up),
            getString(R.string.agent_plan_move_down),
            getString(R.string.agent_plan_remove_action)
        )
        android.app.AlertDialog.Builder(this)
            .setTitle(getString(R.string.agent_plan_edit_title))
            .setItems(labels) { _, which ->
                when (which) {
                    0 -> showAgentPlanActionInputDialog(action)
                    1 -> runAgentOperationAsync { mobileNativeAgent.movePendingAction(action.id, -1) }
                    2 -> runAgentOperationAsync { mobileNativeAgent.movePendingAction(action.id, 1) }
                    3 -> confirmRemoveAgentPlanAction(action)
                }
            }
            .setNegativeButton(getString(R.string.common_cancel), null)
            .show()
    }

    private fun showAgentPlanActionInputDialog(action: AgentAction) {
        val descriptionInput = EditText(this).apply {
            hint = getString(R.string.agent_plan_action_description)
            setText(action.description)
            selectAll()
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            minLines = 2
        }
        val inputKey = AgentPlanEditor.inputKey(action)
        val actionInput = inputKey?.let {
            EditText(this).apply {
                hint = getString(R.string.agent_plan_action_input)
                setText(AgentPlanEditor.inputValue(action))
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
                minLines = 3
            }
        }
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), 0)
            addView(descriptionInput, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ))
            actionInput?.let { input ->
                addView(input, LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(12) })
            }
        }
        android.app.AlertDialog.Builder(this)
            .setTitle(getString(R.string.agent_plan_edit_action))
            .setView(container)
            .setPositiveButton(getString(R.string.common_save)) { _, _ ->
                runAgentOperationAsync {
                    mobileNativeAgent.updatePendingAction(
                        action.id,
                        descriptionInput.text?.toString().orEmpty(),
                        actionInput?.text?.toString().orEmpty()
                    )
                }
            }
            .setNegativeButton(getString(R.string.common_cancel), null)
            .show()
    }

    private fun confirmRemoveAgentPlanAction(action: AgentAction) {
        android.app.AlertDialog.Builder(this)
            .setTitle(getString(R.string.agent_plan_remove_action))
            .setMessage(getString(R.string.agent_plan_remove_action_message, action.description))
            .setPositiveButton(getString(R.string.common_delete)) { _, _ ->
                runAgentOperationAsync { mobileNativeAgent.removePendingAction(action.id) }
            }
            .setNegativeButton(getString(R.string.common_cancel), null)
            .show()
    }

    private fun agentActionStatusColor(status: AgentActionStatus): Int = when (status) {
        AgentActionStatus.COMPLETED -> getColorCompat(R.color.wechat_green)
        AgentActionStatus.FAILED,
        AgentActionStatus.BLOCKED -> getColorCompat(R.color.unread_red)
        AgentActionStatus.RUNNING -> getColorCompat(R.color.signalasi_green)
        AgentActionStatus.WAITING_RESPONSE -> getColorCompat(R.color.signalasi_green)
        else -> getColorCompat(R.color.text_secondary)
    }

    private fun agentAuditRow(entry: AgentAuditEntry, index: Int): View {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundResource(R.drawable.agent_step_background)
            setPadding(dp(14), dp(10), dp(14), dp(10))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                if (index > 0) topMargin = dp(8)
            }

            addView(TextView(this@MainActivity).apply {
                layoutParams = LinearLayout.LayoutParams(dp(9), dp(9)).apply {
                    marginEnd = dp(12)
                }
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(getColorCompat(R.color.wechat_green))
                }
            })

            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)

                addView(TextView(this@MainActivity).apply {
                    setTextColor(getColorCompat(R.color.text_primary))
                    textSize = 13f
                    maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                    text = entry.event.name.lowercase(Locale.US).replace('_', ' ')
                })

                addView(TextView(this@MainActivity).apply {
                    setTextColor(getColorCompat(R.color.text_secondary))
                    textSize = 11f
                    maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                    text = entry.detail.ifBlank { "-" }
                })
            })

            addView(TextView(this@MainActivity).apply {
                setTextColor(getColorCompat(R.color.text_secondary))
                textSize = 11f
                text = getString(R.string.agent_audit_meta, agentAuditAge(entry.timestampMillis))
            })
        }
    }

    private fun agentAuditAge(timestampMillis: Long): String {
        val deltaSeconds = ((System.currentTimeMillis() - timestampMillis).coerceAtLeast(0L) / 1000L)
        return when {
            deltaSeconds < 60 -> "${deltaSeconds}s"
            deltaSeconds < 3600 -> "${deltaSeconds / 60}m"
            deltaSeconds < 86_400 -> "${deltaSeconds / 3600}h"
            else -> "${deltaSeconds / 86_400}d"
        }
    }

    private fun renderAgentScreenDetails(screen: ScreenContext) {
        agentScreenDetailList.removeAllViews()
        if (!screen.isAccessibilityEnabled && !ScreenPerceptionState.hasRecentVisualCapture()) {
            agentScreenDetailList.addView(agentScreenEmptyRow(getString(R.string.agent_screen_disabled)))
            return
        }

        val query = agentScreenSearchInput.text?.toString()?.trim().orEmpty()
        val normalizedQuery = query.lowercase(Locale.US)
        val visibleTexts = screen.visibleTexts
            .filter { matchesScreenQuery(it, normalizedQuery) }
            .take(5)
        val selectedTextMatches = screen.selectedText.isNotBlank() &&
            matchesScreenQuery(screen.selectedText, normalizedQuery)
        val focusedInputField = screen.focusedInputField?.takeIf { field ->
            matchesScreenQuery(field.label, normalizedQuery) ||
                matchesScreenQuery(field.viewId, normalizedQuery) ||
                matchesScreenQuery(field.className, normalizedQuery)
        }
        val actions = screen.clickableElements
            .filter { matchesScreenQuery(it.label, normalizedQuery) || matchesScreenQuery(it.viewId, normalizedQuery) }
            .take(5)
        val fields = screen.inputFields
            .filter { matchesScreenQuery(it.label, normalizedQuery) || matchesScreenQuery(it.viewId, normalizedQuery) }
            .take(5)
        val scrollRegions = screen.scrollableRegions
            .filter { matchesScreenQuery(it.label, normalizedQuery) || matchesScreenQuery(it.viewId, normalizedQuery) }
            .take(5)
        val clipboardMatches = screen.clipboard.hasText && (
            normalizedQuery.isBlank() ||
                matchesScreenQuery(screen.clipboard.preview, normalizedQuery) ||
                matchesScreenQuery(screen.clipboard.textHash, normalizedQuery) ||
                screen.clipboard.sensitiveFlags.any { matchesScreenQuery(it, normalizedQuery) }
            )
        val notificationItems = screen.notifications.items
            .filter { item ->
                matchesScreenQuery(item.packageName, normalizedQuery) ||
                    matchesScreenQuery(item.title, normalizedQuery) ||
                    matchesScreenQuery(item.textPreview, normalizedQuery) ||
                    item.sensitiveFlags.any { matchesScreenQuery(it, normalizedQuery) }
            }
            .take(3)
        val showNotificationAccessRow = !screen.notifications.hasAccess && normalizedQuery.isBlank()
        val notificationsMatch = notificationItems.isNotEmpty() || showNotificationAccessRow
        val deviceStatusMatches = normalizedQuery.isBlank() ||
            matchesScreenQuery(screen.deviceStatus.network, normalizedQuery) ||
            matchesScreenQuery(screen.deviceStatus.batteryPercent.toString(), normalizedQuery) ||
            matchesScreenQuery(screen.deviceStatus.freeStorageMb.toString(), normalizedQuery)
        val launchableApps = screen.installedApps
            .filter { app ->
                matchesScreenQuery(app.label, normalizedQuery) ||
                    matchesScreenQuery(app.packageName, normalizedQuery)
            }
            .take(if (normalizedQuery.isBlank()) 4 else 8)

        val hasAny = selectedTextMatches || focusedInputField != null || clipboardMatches || notificationsMatch || deviceStatusMatches || launchableApps.isNotEmpty() || visibleTexts.isNotEmpty() || actions.isNotEmpty() || fields.isNotEmpty() || scrollRegions.isNotEmpty()
        agentScreenDetailList.addView(agentScreenSummaryRow(screen))
        if (!hasAny) {
            agentScreenDetailList.addView(agentScreenEmptyRow(getString(R.string.agent_screen_empty)))
            return
        }
        if (selectedTextMatches) {
            addScreenTextSection(getString(R.string.agent_screen_selected_text), listOf(screen.selectedText))
        }
        focusedInputField?.let {
            addScreenElementSection(getString(R.string.agent_screen_focused_input), listOf(it), AgentScreenCommandKind.TYPE)
        }
        if (clipboardMatches) {
            addScreenClipboardSection(screen.clipboard)
        }
        if (notificationsMatch) {
            addScreenNotificationSection(notificationItems, showNotificationAccessRow)
        }
        if (deviceStatusMatches) {
            addScreenDeviceStatusSection(screen.deviceStatus)
        }
        if (screen.visualScene.available) {
            agentScreenDetailList.addView(agentScreenSectionTitle(getString(R.string.agent_screen_visual_grounding)))
            agentScreenDetailList.addView(agentVisualSummaryRow(screen.visualScene))
        }
        addScreenInstalledAppsSection(launchableApps)
        addScreenTextSection(getString(R.string.agent_screen_texts), visibleTexts)
        addScreenElementSection(getString(R.string.agent_screen_actions), actions, AgentScreenCommandKind.TAP)
        addScreenElementSection(getString(R.string.agent_screen_fields), fields, AgentScreenCommandKind.TYPE)
        addScreenElementSection(getString(R.string.agent_screen_scrollable_regions), scrollRegions, AgentScreenCommandKind.SCROLL)
    }

    private fun matchesScreenQuery(value: String, normalizedQuery: String): Boolean =
        normalizedQuery.isBlank() || value.lowercase(Locale.US).contains(normalizedQuery)

    private fun addScreenTextSection(title: String, items: List<String>) {
        if (items.isEmpty()) return
        agentScreenDetailList.addView(agentScreenSectionTitle(title))
        items.forEach { item ->
            agentScreenDetailList.addView(agentScreenTextRow(item))
        }
    }

    private fun addScreenClipboardSection(clipboard: ClipboardContext) {
        agentScreenDetailList.addView(agentScreenSectionTitle(getString(R.string.agent_screen_clipboard)))
        agentScreenDetailList.addView(agentClipboardRow(clipboard))
    }

    private fun addScreenNotificationSection(items: List<AgentNotificationItem>, showAccessRow: Boolean) {
        agentScreenDetailList.addView(agentScreenSectionTitle(getString(R.string.agent_screen_notifications)))
        if (showAccessRow) {
            agentScreenDetailList.addView(agentScreenEmptyRow(getString(R.string.agent_screen_notifications_locked)))
        }
        items.forEach { item ->
            agentScreenDetailList.addView(agentNotificationRow(item))
        }
    }

    private fun addScreenDeviceStatusSection(status: AgentDeviceStatusContext) {
        agentScreenDetailList.addView(agentScreenSectionTitle(getString(R.string.agent_screen_device_status)))
        agentScreenDetailList.addView(agentDeviceStatusRow(status))
    }

    private fun addScreenInstalledAppsSection(apps: List<InstalledAppInfo>) {
        if (apps.isEmpty()) return
        agentScreenDetailList.addView(agentScreenSectionTitle(getString(R.string.agent_screen_launchable_apps)))
        apps.forEach { app ->
            agentScreenDetailList.addView(agentInstalledAppRow(app))
        }
    }

    private fun addScreenElementSection(
        title: String,
        items: List<ScreenElement>,
        commandKind: AgentScreenCommandKind
    ) {
        if (items.isEmpty()) return
        agentScreenDetailList.addView(agentScreenSectionTitle(title))
        items.forEach { item ->
            agentScreenDetailList.addView(agentScreenElementRow(item, commandKind))
        }
    }

    private fun agentClipboardRow(clipboard: ClipboardContext): View {
        val summary = if (clipboard.sensitiveFlags.isNotEmpty()) {
            getString(R.string.agent_screen_clipboard_sensitive, clipboard.textLength)
        } else {
            getString(
                R.string.agent_screen_clipboard_summary,
                clipboard.textLength,
                clipboard.preview.ifBlank { clipboard.textHash.ifBlank { "-" } }
            )
        }
        return TextView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(6) }
            setBackgroundResource(R.drawable.agent_step_background)
            setPadding(dp(14), dp(10), dp(14), dp(10))
            setTextColor(getColorCompat(R.color.text_primary))
            textSize = 13f
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
            text = summary
            setOnClickListener { prefillAgentGoal("paste clipboard") }
        }
    }

    private fun agentNotificationRow(item: AgentNotificationItem): View {
        val title = item.title.ifBlank { item.packageName.ifBlank { "-" } }
        val baseDetail = if (item.sensitiveFlags.isNotEmpty()) {
            getString(R.string.agent_screen_notification_sensitive, item.packageName.ifBlank { "-" })
        } else {
            getString(
                R.string.agent_screen_notification_summary,
                item.category.ifBlank { item.packageName.ifBlank { "-" } },
                item.textPreview.ifBlank { title }
            )
        }
        val replyAvailable = item.canReply && item.sensitiveFlags.isEmpty()
        val detail = if (replyAvailable) {
            "$baseDetail\n${getString(R.string.agent_screen_notification_reply_available)}"
        } else {
            baseDetail
        }
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.agent_step_background)
            setPadding(dp(14), dp(10), dp(14), dp(10))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(6) }
            setOnClickListener {
                if (replyAvailable) {
                    prefillAgentGoal("reply notification ${item.packageName} :: ")
                } else {
                    prefillAgentGoal("read notifications")
                }
            }

            addView(TextView(this@MainActivity).apply {
                setTextColor(getColorCompat(R.color.text_primary))
                textSize = 13f
                maxLines = 2
                ellipsize = android.text.TextUtils.TruncateAt.END
                text = title
            })
            addView(TextView(this@MainActivity).apply {
                setTextColor(getColorCompat(R.color.text_secondary))
                textSize = 11f
                maxLines = 2
                ellipsize = android.text.TextUtils.TruncateAt.END
                text = detail
            })
        }
    }

    private fun agentDeviceStatusRow(status: AgentDeviceStatusContext): View {
        val power = if (status.powerSaveMode) "power save" else if (status.charging) "charging" else "battery"
        return TextView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(6) }
            setBackgroundResource(R.drawable.agent_step_background)
            setPadding(dp(14), dp(10), dp(14), dp(10))
            setTextColor(getColorCompat(R.color.text_primary))
            textSize = 13f
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
            text = getString(
                R.string.agent_screen_device_status_summary,
                status.batteryPercent,
                power,
                status.network,
                status.freeStorageMb
            )
            setOnClickListener { prefillAgentGoal("device status") }
        }
    }

    private fun agentInstalledAppRow(app: InstalledAppInfo): View {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.agent_step_background)
            setPadding(dp(14), dp(10), dp(14), dp(10))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(6) }
            setOnClickListener { prefillAgentGoal("open ${app.label}") }

            addView(TextView(this@MainActivity).apply {
                setTextColor(getColorCompat(R.color.text_primary))
                textSize = 13f
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                text = app.label
            })
            addView(TextView(this@MainActivity).apply {
                setTextColor(getColorCompat(R.color.text_secondary))
                textSize = 11f
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                text = getString(R.string.agent_screen_launchable_app_summary, app.packageName, "open")
            })
        }
    }

    private fun agentScreenSummaryRow(screen: ScreenContext): View {
        val pageTitle = screen.pageTitle.ifBlank { screen.foregroundApp }
        val ageSeconds = maxOf(0L, screen.snapshotAgeMillis / 1000L)
        return TextView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(44)
            )
            setBackgroundResource(R.drawable.agent_step_background)
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), 0, dp(14), 0)
            setTextColor(getColorCompat(R.color.text_primary))
            textSize = 14f
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            text = getString(R.string.agent_screen_summary, pageTitle, ageSeconds)
        }
    }

    private fun agentScreenSectionTitle(title: String): View {
        return TextView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(10) }
            setPadding(dp(4), 0, dp(4), dp(4))
            setTextColor(getColorCompat(R.color.text_secondary))
            textSize = 12f
            setTypeface(null, android.graphics.Typeface.BOLD)
            text = title
        }
    }

    private fun agentScreenTextRow(value: String): View {
        return TextView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(6) }
            setBackgroundResource(R.drawable.agent_step_background)
            setPadding(dp(14), dp(10), dp(14), dp(10))
            setTextColor(getColorCompat(R.color.text_primary))
            textSize = 13f
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
            text = value
            setOnClickListener {
                prefillAgentGoal("save note $value")
            }
        }
    }

    private fun agentScreenElementRow(item: ScreenElement, commandKind: AgentScreenCommandKind): View {
        val label = item.label.ifBlank { item.viewId.ifBlank { item.className } }
        val commandTarget = item.viewId.takeIf { item.origin == AgentElementOrigin.VISUAL_OCR && it.isNotBlank() }
            ?: label
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.agent_step_background)
            setPadding(dp(14), dp(10), dp(14), dp(10))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(6) }
            setOnClickListener {
                prefillAgentGoal(agentScreenCommand(commandTarget, commandKind))
            }

            addView(TextView(this@MainActivity).apply {
                setTextColor(getColorCompat(R.color.text_primary))
                textSize = 13f
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                text = label
            })
            addView(TextView(this@MainActivity).apply {
                setTextColor(getColorCompat(R.color.text_secondary))
                textSize = 11f
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                text = getString(
                    R.string.agent_screen_element_grounding,
                    item.bounds.ifBlank { "-" },
                    elementOriginLabel(item.origin),
                    visualRoleLabel(item.visualRole),
                    (item.confidence * 100).toInt().coerceIn(0, 100)
                )
            })
        }
    }

    private fun agentVisualSummaryRow(scene: AgentVisualScene): View = TextView(this).apply {
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = dp(6) }
        setBackgroundResource(R.drawable.agent_step_background)
        setPadding(dp(14), dp(10), dp(14), dp(10))
        setTextColor(getColorCompat(R.color.text_primary))
        textSize = 13f
        text = getString(
            R.string.agent_screen_visual_summary,
            scene.modelProfile,
            scene.elements.size,
            scene.actionCandidateCount,
            scene.inputCandidateCount
        )
    }

    private fun elementOriginLabel(origin: AgentElementOrigin): String = getString(
        when (origin) {
            AgentElementOrigin.ACCESSIBILITY -> R.string.agent_screen_origin_accessibility
            AgentElementOrigin.VISUAL_OCR -> R.string.agent_screen_origin_visual
            AgentElementOrigin.FUSED -> R.string.agent_screen_origin_fused
        }
    )

    private fun visualRoleLabel(role: AgentVisualRole): String = getString(
        when (role) {
            AgentVisualRole.TITLE -> R.string.agent_visual_role_title
            AgentVisualRole.BUTTON -> R.string.agent_visual_role_button
            AgentVisualRole.INPUT -> R.string.agent_visual_role_input
            AgentVisualRole.NAVIGATION -> R.string.agent_visual_role_navigation
            AgentVisualRole.LIST_ITEM -> R.string.agent_visual_role_list_item
            AgentVisualRole.TEXT -> R.string.agent_visual_role_text
            AgentVisualRole.UNKNOWN -> R.string.agent_visual_role_unknown
        }
    )

    private fun agentScreenCommand(label: String, kind: AgentScreenCommandKind): String = when (kind) {
        AgentScreenCommandKind.TAP -> "tap $label"
        AgentScreenCommandKind.TYPE -> "type text into $label"
        AgentScreenCommandKind.SCROLL -> "swipe up"
    }

    private fun prefillAgentGoal(command: String) {
        agentGoalInput.setText(command)
        agentGoalInput.setSelection(agentGoalInput.text?.length ?: 0)
        agentGoalInput.requestFocus()
        getSystemService(InputMethodManager::class.java).showSoftInput(agentGoalInput, InputMethodManager.SHOW_IMPLICIT)
    }

    private fun agentScreenEmptyRow(message: String): View {
        return TextView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(48)
            ).apply { topMargin = dp(8) }
            setBackgroundResource(R.drawable.agent_step_background)
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), 0, dp(14), 0)
            setTextColor(getColorCompat(R.color.text_secondary))
            textSize = 13f
            text = message
        }
    }

    private fun agentTaskStatusText(task: AgentTaskRecord): String = when {
        task.blocked -> getString(R.string.agent_recent_status_blocked)
        task.phase == AgentPhase.COMPLETED -> getString(R.string.agent_recent_status_done)
        task.phase == AgentPhase.FAILED -> getString(R.string.agent_recent_status_failed)
        task.phase == AgentPhase.CANCELLED -> getString(R.string.agent_recent_status_cancelled)
        task.phase == AgentPhase.PAUSED -> getString(R.string.agent_recent_status_paused)
        task.phase == AgentPhase.EXECUTING ||
            task.phase == AgentPhase.VERIFYING ||
            task.phase == AgentPhase.PLANNING ||
            task.phase == AgentPhase.WAITING_RESPONSE ||
            task.phase == AgentPhase.WAITING_CONFIRMATION -> getString(R.string.agent_recent_status_running)
        else -> task.phase.name.lowercase(Locale.US)
    }

    private fun showMainTab(tab: String) {
        val previousTab = activeMainTab
        if (tab != PAGE_AGENT && agentVoiceListening) {
            stopAgentVoiceInput()
        }
        activeMainTab = tab
        controlCenterDestination = null
        controlCenterBackStack.clear()
        wakePage.visibility = if (tab == PAGE_VOICE) View.VISIBLE else View.GONE
        chatPage.visibility = View.GONE
        featurePage.visibility = View.GONE
        if (tab == PAGE_VOICE && previousTab != PAGE_VOICE) {
            startVoiceAssistant()
        } else if (previousTab == PAGE_VOICE && tab != PAGE_VOICE) {
            stopVoiceAssistant()
        }

        if (tab == PAGE_AGENT || tab == PAGE_MESSAGES || tab == PAGE_CONTACTS || tab == PAGE_DISCOVER || tab == PAGE_SETTINGS) {
            mainPage.visibility = View.VISIBLE
            mainTopBar.visibility = if (tab == PAGE_AGENT) View.GONE else View.VISIBLE
            mainActionButton.visibility = if (tab == PAGE_CONTACTS) View.VISIBLE else View.INVISIBLE
            mainActionButton.text = when (tab) {
                PAGE_CONTACTS -> "+"
                else -> ""
            }
            mainTitle.text = when (tab) {
                PAGE_AGENT -> getString(R.string.tab_agent)
                PAGE_MESSAGES -> getString(R.string.title_messages)
                PAGE_CONTACTS -> getString(R.string.tab_contacts)
                PAGE_DISCOVER -> getString(R.string.tab_discover)
                PAGE_SETTINGS -> getString(R.string.settings_control_center_title)
                else -> ""
            }
        } else {
            mainPage.visibility = View.GONE
        }
        agentPage.visibility = if (tab == PAGE_AGENT) View.VISIBLE else View.GONE
        contactPage.visibility = if (tab == PAGE_MESSAGES) View.VISIBLE else View.GONE
        directoryPage.visibility = if (tab == PAGE_CONTACTS) View.VISIBLE else View.GONE
        discoverPage.visibility = if (tab == PAGE_DISCOVER) View.VISIBLE else View.GONE
        mePage.visibility = if (tab == PAGE_SETTINGS) View.VISIBLE else View.GONE
        if (tab == PAGE_SETTINGS) refreshSettingsControlCenter()
        if (tab == PAGE_AGENT) refreshGlobalInsightIndicator()

    }

    private fun applyAgentBrandLogoTextScale() {
        val sizeDp = (AGENT_BRAND_LOGO_BASE_DP * resources.configuration.fontScale)
            .roundToInt()
            .coerceIn(AGENT_BRAND_LOGO_MIN_DP, AGENT_BRAND_LOGO_MAX_DP)
        agentBrandLogo.layoutParams = agentBrandLogo.layoutParams.apply {
            width = dp(sizeDp)
            height = dp(sizeDp)
        }
    }

    private fun configureMessages() {
        messageAdapter = MessageAdapter(currentMessages,
            onPlayVoiceMessage = { msgId -> playVoiceMessage(msgId) },
            onMessageActions = { position -> showMessageActionsPage(position) })
        messageList.apply {
            layoutManager = LinearLayoutManager(this@MainActivity).apply { stackFromEnd = true }
            adapter = messageAdapter
        }
    }

    // ===== Input =====
    private fun configureInput() {
        sendButton.setOnClickListener { sendText() }
        sendButton.visibility = View.GONE
        messageInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_SEND) {
                sendText()
                true
            } else false
        }
        messageInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = updateInputActions()
            override fun afterTextChanged(s: Editable?) = Unit
        })
        imageButton.setOnClickListener {
            startActivityForResult(Intent(Intent.ACTION_GET_CONTENT).apply {
                type = "image/*"
                addCategory(Intent.CATEGORY_OPENABLE)
            }, REQUEST_IMAGE)
        }
        emojiButton.setOnClickListener {
            if (voiceMode) setVoiceMode(false)
            emojiPanel.visibility = if (emojiPanel.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        }

        listOf("??", "??", "??", "??", "??", "??", "??").forEach { token ->
            addEmoji(token)
        }
        voiceButton.setOnClickListener {
            setVoiceMode(!voiceMode)
        }
        holdToTalkController = AppleHoldToTalkController(
            activity = this,
            pressButton = pressToTalkButton,
            recordingGroup = chatRecordingCenter,
            waveform = chatRecordingWaveform,
            timer = chatRecordingTimer,
            hasPermission = {
                checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
            },
            requestPermission = {
                requestPermissions(arrayOf(android.Manifest.permission.RECORD_AUDIO), REQUEST_RECORD_AUDIO)
            },
            startRecording = { startRecording("chat_message") },
            currentAmplitude = { recorder?.maxAmplitude ?: 0 },
            finishRecording = { send -> stopRecording(send) }
        )
        pressToTalkButton.setOnTouchListener(holdToTalkController)
        updateInputActions()
    }

    private var emojiTokens = listOf("??", "??", "??", "??", "??", "??", "??")

    private fun addEmoji(token: String) {
        val tv = TextView(this).apply {
            text = token
            textSize = if (token.length > 2) 15f else 24f
            gravity = Gravity.CENTER
            setTextColor(getColorCompat(R.color.text_primary))
            setPadding(dp(12), 0, dp(12), 0)
            minWidth = dp(46)
            setOnClickListener {
                val start = messageInput.selectionStart.coerceAtLeast(0)
                messageInput.text?.insert(start, token)
            }
        }
        emojiContainer.addView(tv, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT))
    }

    private fun setVoiceMode(enabled: Boolean) {
        voiceMode = enabled
        messageInput.visibility = if (enabled) View.GONE else View.VISIBLE
        pressToTalkButton.visibility = if (enabled) View.VISIBLE else View.GONE
        voiceButton.setImageResource(if (enabled) R.drawable.ic_input_keyboard else R.drawable.ic_input_voice)
        emojiPanel.visibility = View.GONE
        if (enabled) hideKeyboard()
        updateInputActions()
    }

    private fun updateInputActions() {
        val hasText = !voiceMode && !messageInput.text?.toString()?.trim().isNullOrEmpty()
        sendButton.visibility = if (hasText) View.VISIBLE else View.GONE
        imageButton.visibility = if (hasText) View.GONE else View.VISIBLE
    }

    private fun ensureRecordPermission(): Boolean {
        return if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            true
        } else {
            requestPermissions(arrayOf(android.Manifest.permission.RECORD_AUDIO), REQUEST_RECORD_AUDIO)
            false
        }
    }

    // ===== Send / Receive =====
    private fun sendText() {
        val content = messageInput.text?.toString()?.trim().orEmpty()
        if (content.isEmpty()) return
        val contact = selectedContact ?: return
        messageInput.text?.clear()
        sendOutgoingText(contact, content)
    }

    private fun sendOutgoingText(contact: Contact, content: String) {
        selectedContact = contact
        val msg = ChatMessage(
            newMessageId(),
            content,
            true,
            CONTACT_ME,
            deliveryStatus = getString(R.string.delivery_status_sending),
            deliveryTrace = mutableListOf(newTraceEvent("created", "user_send"))
        )
        addMessage(msg)
        val raw = AppStore.contactById(this, contact.id)
        if (raw?.optString("delivery_mode") == "cloud_api") {
            updateMessageStatus(msg.id, contact.id, getString(R.string.delivery_status_requesting))
            appendDeliveryTrace(msg.id, contact.id, "cloud_request", raw.optString("cloud_provider"))
            val selectedModel = AppStore.selectedCloudModelContact(this, contact.id) ?: raw
            val contextTurns = (messages[contact.id] ?: currentMessages)
                .filterNot { it.isSystem }
                .takeLast(14)
                .map { it.copy() }
            requestCloudModelReply(contact, selectedModel, contextTurns, msg.id)
            return
        }
        val target = AppStore.outgoingTopicForContact(this, contact.id)
        if (target != null) {
            appendDeliveryTrace(msg.id, contact.id, "queued", target)
            val published = SignalASIMqttClient.publishUserMessage(
                content,
                contact.id,
                topicOverride = target,
                clientMessageId = msg.id,
                deliveryTrace = deliveryTraceJson(msg.deliveryTrace)
            )
            if (published) {
                appendDeliveryTrace(msg.id, contact.id, "mqtt_published", target)
                updateMessageStatus(msg.id, contact.id, getString(R.string.delivery_status_sent))
                markDeliveredSoon(msg, contact.id)
            } else {
                appendDeliveryTrace(msg.id, contact.id, "publish_failed", target)
                updateMessageStatus(msg.id, contact.id, getString(R.string.delivery_status_failed))
            }
        } else {
            appendDeliveryTrace(msg.id, contact.id, "link_unavailable", "No paired SignalASI Link v1 relationship")
            updateMessageStatus(msg.id, contact.id, getString(R.string.delivery_status_failed))
        }
    }

    private fun requestCloudModelReply(contact: Contact, raw: JSONObject, contextTurns: List<ChatMessage>, outgoingId: Long) {
        cloudExecutor.execute {
            val result = runCatching {
                CloudModelClient.send(this@MainActivity, raw, contextTurns) { event ->
                    runOnUiThread {
                        val detail = event.detail
                            .replace(Regex("[\\r\\n]+"), " ")
                            .take(120)
                        val text = if (event.stage == "running") {
                            getString(R.string.cloud_tool_running, event.tool, detail)
                        } else {
                            getString(R.string.cloud_tool_completed, event.tool, detail)
                        }
                        addMessage(ChatMessage(
                            newMessageId(),
                            text,
                            false,
                            contact,
                            isSystem = true,
                            deliveryTrace = mutableListOf(newTraceEvent("cloud_tool_${event.stage}", event.tool))
                        ))
                    }
                }
            }
            runOnUiThread {
                if (result.isSuccess) {
                    appendDeliveryTrace(outgoingId, contact.id, "cloud_reply", raw.optString("selected_cloud_model"))
                    updateMessageStatus(outgoingId, contact.id, getString(R.string.delivery_status_replied))
                    val reply = ChatMessage(
                        newMessageId(),
                        result.getOrThrow(),
                        false,
                        contact,
                        deliveryTrace = mutableListOf(newTraceEvent("cloud_reply_received", raw.optString("selected_cloud_model")))
                    )
                    addMessage(reply, fromIncoming = true)
                    maybeSpeakIncomingReply(reply)
                } else {
                    appendDeliveryTrace(outgoingId, contact.id, "cloud_error", result.exceptionOrNull()?.message?.take(120).orEmpty())
                    updateMessageStatus(outgoingId, contact.id, getString(R.string.delivery_status_failed))
                    addMessage(ChatMessage(
                        newMessageId(),
                        getString(R.string.cloud_request_failed, result.exceptionOrNull()?.message?.take(220) ?: getString(R.string.cloud_unknown_error)),
                        false,
                        contact,
                        deliveryTrace = mutableListOf(newTraceEvent("cloud_error", result.exceptionOrNull()?.message?.take(120).orEmpty()))
                    ), fromIncoming = true)
                }
            }
        }
    }

    private fun handleDebugSendIntent(intent: Intent?) {
        if ((applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) == 0) return
        val agentGoalEncoded = intent?.getStringExtra("signalasi_debug_agent_goal_b64")?.trim().orEmpty()
        if (agentGoalEncoded.isNotBlank()) {
            val token = intent?.getStringExtra("signalasi_debug_agent_token")?.trim().orEmpty()
                .ifBlank { UUID.randomUUID().toString() }
            val newConversation = intent?.getBooleanExtra("signalasi_debug_agent_new_conversation", true) != false
            intent?.removeExtra("signalasi_debug_agent_goal_b64")
            intent?.removeExtra("signalasi_debug_agent_token")
            intent?.removeExtra("signalasi_debug_agent_new_conversation")
            val goal = runCatching {
                String(Base64.decode(agentGoalEncoded, Base64.DEFAULT), Charsets.UTF_8)
            }.getOrDefault("").trim()
            if (goal.isNotBlank()) scheduleDebugAgentGoal(token, goal, newConversation)
        }
        val contactId = intent?.getStringExtra("signalasi_debug_contact")?.trim().orEmpty()
        val content = intent?.getStringExtra("signalasi_debug_text")?.trim().orEmpty()
        if (contactId.isBlank() || content.isBlank()) return
        intent?.removeExtra("signalasi_debug_contact")
        intent?.removeExtra("signalasi_debug_text")
        scheduleDebugOutgoing(contactId, content, attempt = 0)
    }

    private fun handleDebugIncomingIntent(intent: Intent?) {
        if ((applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) == 0) return
        val revoke = intent?.getBooleanExtra("signalasi_debug_revoke", false) == true
        val status = intent?.getBooleanExtra("signalasi_debug_status", false) == true
        val pairing = intent?.getBooleanExtra("signalasi_debug_pairing", false) == true
        val openAgents = intent?.getBooleanExtra("signalasi_debug_open_agents", false) == true
        val openSecurity = intent?.getBooleanExtra("signalasi_debug_open_security", false) == true
        val openVoice = intent?.getBooleanExtra("signalasi_debug_open_voice", false) == true
        val openVoiceSettings = intent?.getBooleanExtra("signalasi_debug_open_voice_settings", false) == true
        val openLanguageSettings = intent?.getBooleanExtra("signalasi_debug_open_language_settings", false) == true
        val openOnDeviceAgent = intent?.getBooleanExtra("signalasi_debug_open_on_device_agent", false) == true
        val openBackupExport = intent?.getBooleanExtra("signalasi_debug_open_backup_export", false) == true
        val openBackupImport = intent?.getBooleanExtra("signalasi_debug_open_backup_import", false) == true
        val openDestroyData = intent?.getBooleanExtra("signalasi_debug_open_destroy_data", false) == true
        val destroyAllData = intent?.getBooleanExtra("signalasi_debug_destroy_all_data", false) == true
        val openProtocolQuality = intent?.getBooleanExtra("signalasi_debug_open_protocol_quality", false) == true
        val openSignalLinkProtocol = intent?.getBooleanExtra("signalasi_debug_open_signal_link_protocol", false) == true
        val openAdvancedOptions = intent?.getBooleanExtra("signalasi_debug_open_advanced_options", false) == true
        val openRecentTasks = intent?.getBooleanExtra("signalasi_debug_open_recent_tasks", false) == true
        val openMessages = intent?.getBooleanExtra("signalasi_debug_open_messages", false) == true
        val openContacts = intent?.getBooleanExtra("signalasi_debug_open_contacts", false) == true
        val openContactId = intent?.getStringExtra("signalasi_debug_open_contact")?.trim().orEmpty()
        val openContactDetailId = intent?.getStringExtra("signalasi_debug_open_contact_detail")?.trim().orEmpty()
        val openNewFriends = intent?.getBooleanExtra("signalasi_debug_open_new_friends", false) == true
        val openGroup = intent?.getBooleanExtra("signalasi_debug_open_group", false) == true
        val openCreateGroup = intent?.getBooleanExtra("signalasi_debug_open_create_group", false) == true
        val openDevice = intent?.getBooleanExtra("signalasi_debug_open_device", false) == true
        val openAutomation = intent?.getBooleanExtra("signalasi_debug_open_automation", false) == true
        val openLocalModel = intent?.getBooleanExtra("signalasi_debug_open_local_model", false) == true
        val openCloudProviders = intent?.getBooleanExtra("signalasi_debug_open_cloud_providers", false) == true
        val openCloudProvider = intent?.getStringExtra("signalasi_debug_open_cloud_provider")?.trim().orEmpty()
        val seedCloudProvider = intent?.getStringExtra("signalasi_debug_seed_cloud_provider")?.trim().orEmpty()
        val openCloudSwitchProvider = intent?.getStringExtra("signalasi_debug_open_cloud_switch_provider")?.trim().orEmpty()
        val approveFriendId = intent?.getStringExtra("signalasi_debug_approve_friend")?.trim().orEmpty()
        val deleteContactId = intent?.getStringExtra("signalasi_debug_delete_contact")?.trim().orEmpty()
        val renameContactId = intent?.getStringExtra("signalasi_debug_rename_contact")?.trim().orEmpty()
        val renameContactNameB64 = intent?.getStringExtra("signalasi_debug_rename_name_b64")?.trim().orEmpty()
        val renameContactName = if (renameContactNameB64.isNotBlank()) {
            runCatching {
                String(Base64.decode(renameContactNameB64, Base64.NO_WRAP), Charsets.UTF_8).trim()
            }.getOrDefault("")
        } else {
            intent?.getStringExtra("signalasi_debug_rename_name")?.trim().orEmpty()
        }
        val backupRoundtripToken = intent?.getStringExtra("signalasi_debug_backup_roundtrip")?.trim().orEmpty()
        val cloudModelsRoundtripToken = intent?.getStringExtra("signalasi_debug_cloud_models_roundtrip")?.trim().orEmpty()
        val voiceSettingsRoundtripToken = intent?.getStringExtra("signalasi_debug_voice_settings_roundtrip")?.trim().orEmpty()
        val controlCenterRoundtripToken = intent?.getStringExtra("signalasi_debug_control_center_roundtrip")?.trim().orEmpty()
        val controlCenterThemeToken = intent?.getStringExtra("signalasi_debug_control_center_theme")?.trim().orEmpty()
        val homeAssistantTestUrl = intent?.getStringExtra("signalasi_debug_home_assistant_url")?.trim().orEmpty()
        val controlCenterPage = intent?.getStringExtra("signalasi_debug_control_center_page")?.trim().orEmpty()
        val scanPayload = intent?.getStringExtra("signalasi_debug_scan_payload")?.trim().orEmpty()
        val scanPayloadB64 = intent?.getStringExtra("signalasi_debug_scan_payload_b64")?.trim().orEmpty()
        if (controlCenterThemeToken.isNotBlank()) {
            intent?.removeExtra("signalasi_debug_control_center_theme")
            val isNight = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
                Configuration.UI_MODE_NIGHT_YES
            getSharedPreferences("signalasi_debug", MODE_PRIVATE).edit()
                .putString(
                    "control_center_theme_result",
                    JSONObject()
                        .put("token", controlCenterThemeToken)
                        .put("night", isNight)
                        .put("page_bg", getColorCompat(R.color.page_bg).toLong() and 0xffffffffL)
                        .toString()
                )
                .commit()
            showMainTab(PAGE_SETTINGS)
            return
        }
        if (controlCenterPage.isNotBlank()) {
            intent?.removeExtra("signalasi_debug_control_center_page")
            showMainTab(PAGE_SETTINGS)
            if (!controlCenterPage.equals("home", ignoreCase = true)) {
                ControlCenterRoute.fromWireValue(controlCenterPage)?.let {
                    openControlCenterDestination(ControlCenterDestination(it))
                }
            }
            return
        }
        if (homeAssistantTestUrl.isNotBlank()) {
            intent?.removeExtra("signalasi_debug_home_assistant_url")
            HomeAssistantSettingsStore.save(
                this,
                HomeAssistantSettings(
                    enabled = true,
                    baseUrl = homeAssistantTestUrl,
                    accessToken = "signalasi-control-center-test",
                    defaultEntityId = "light.qa_lamp"
                )
            )
            showMainTab(PAGE_SETTINGS)
            openControlCenterDestination(ControlCenterDestination(ControlCenterRoute.SMART_SPACES))
            return
        }
        if (approveFriendId.isNotBlank()) {
            intent?.removeExtra("signalasi_debug_approve_friend")
            AppStore.approveFriendRequestForSignalasiId(this, approveFriendId)
            refreshContactList()
            refreshDirectoryContacts()
            return
        }
        if (deleteContactId.isNotBlank()) {
            intent?.removeExtra("signalasi_debug_delete_contact")
            AppStore.deleteContact(this, deleteContactId, deleteMessages = false)
            refreshContactList()
            refreshDirectoryContacts()
            return
        }
        if (renameContactId.isNotBlank() && renameContactName.isNotBlank()) {
            intent?.removeExtra("signalasi_debug_rename_contact")
            intent?.removeExtra("signalasi_debug_rename_name")
            intent?.removeExtra("signalasi_debug_rename_name_b64")
            AppStore.renameContact(this, renameContactId, renameContactName)
            refreshContactList()
            refreshDirectoryContacts()
            if (openContactDetailId.isNotBlank()) {
                showContactDetail(contactById(openContactDetailId))
            }
            return
        }
        if (scanPayloadB64.isNotBlank()) {
            intent?.removeExtra("signalasi_debug_scan_payload_b64")
            val decoded = runCatching {
                String(Base64.decode(scanPayloadB64, Base64.NO_WRAP), Charsets.UTF_8)
            }.getOrDefault("")
            handleSecurityScan(decoded, intent?.getBooleanExtra("signalasi_debug_auto_confirm_scan", false) == true)
            return
        }
        if (scanPayload.isNotBlank()) {
            intent?.removeExtra("signalasi_debug_scan_payload")
            handleSecurityScan(scanPayload, intent?.getBooleanExtra("signalasi_debug_auto_confirm_scan", false) == true)
            return
        }
        if (backupRoundtripToken.isNotBlank()) {
            intent?.removeExtra("signalasi_debug_backup_roundtrip")
            runDebugBackupRoundtrip(backupRoundtripToken)
            return
        }
        if (cloudModelsRoundtripToken.isNotBlank()) {
            intent?.removeExtra("signalasi_debug_cloud_models_roundtrip")
            runDebugCloudModelsRoundtrip(cloudModelsRoundtripToken)
            return
        }
        if (voiceSettingsRoundtripToken.isNotBlank()) {
            intent?.removeExtra("signalasi_debug_voice_settings_roundtrip")
            runDebugVoiceSettingsRoundtrip(voiceSettingsRoundtripToken)
            return
        }
        if (controlCenterRoundtripToken.isNotBlank()) {
            intent?.removeExtra("signalasi_debug_control_center_roundtrip")
            runDebugControlCenterRoundtrip(controlCenterRoundtripToken)
            return
        }
        if (destroyAllData) {
            intent?.removeExtra("signalasi_debug_destroy_all_data")
            AppStore.destroyAllPrivateData(this)
            messages.clear()
            summaries.clear()
            directoryContacts.clear()
            currentMessages.clear()
            nextMessageId = 1L
            loadChatHistory()
            refreshContactList()
            refreshDirectoryContacts()
            showMainTab(PAGE_MESSAGES)
            return
        }
        if (pairing) {
            SignalASICrypto.debugSetVerifiedPcFingerprint(this, "DEBUG_PC_FINGERPRINT_FOR_UI_TEST_000000000000000000000000")
            AppStore.markHermesVerified(this)
            refreshContactList()
            refreshDirectoryContacts()
        }
        val payload = if (revoke) {
            """{"type":"pairing_revoked","content":"desktop_revoked"}"""
        } else if (status) {
            """{"type":"connector_status","content":"debug connector status","connector_agents":[{"id":"codex","name":"Codex Agent","status":"ready","detail":"debug ready","setup":"Ready for live use","kind":"local-cli"},{"id":"claude","name":"Claude Code","status":"needs_setup","detail":"debug missing claude","setup":"Install Claude Code CLI","kind":"local-cli"},{"id":"local-llm","name":"Local LLM","status":"needs_setup","detail":"debug missing local model","setup":"Start Ollama or configure LM Studio","kind":"local-model"},{"id":"custom-agent","name":"Custom Agent","status":"needs_setup","detail":"debug missing custom command","setup":"Set a CLI or MCP wrapper command","kind":"custom-cli"},{"id":"research-agent","name":"Research Agent","status":"ready","detail":"debug dynamic connector","setup":"Ready for live use","kind":"custom-cli"}]}"""
        } else {
            val encoded = intent?.getStringExtra("signalasi_debug_incoming_b64")?.trim().orEmpty()
            if (encoded.isNotBlank()) {
                String(Base64.decode(encoded, Base64.DEFAULT), Charsets.UTF_8).trim()
            } else {
                intent?.getStringExtra("signalasi_debug_incoming")?.trim().orEmpty()
            }
        }
        if (payload.isBlank()) {
            intent?.removeExtra("signalasi_debug_open_agents")
            intent?.removeExtra("signalasi_debug_open_contact")
            intent?.removeExtra("signalasi_debug_open_contact_detail")
            intent?.removeExtra("signalasi_debug_open_new_friends")
            intent?.removeExtra("signalasi_debug_open_group")
            intent?.removeExtra("signalasi_debug_open_create_group")
            intent?.removeExtra("signalasi_debug_open_device")
            intent?.removeExtra("signalasi_debug_open_automation")
            intent?.removeExtra("signalasi_debug_open_local_model")
            intent?.removeExtra("signalasi_debug_open_cloud_providers")
            intent?.removeExtra("signalasi_debug_open_cloud_provider")
            intent?.removeExtra("signalasi_debug_seed_cloud_provider")
            intent?.removeExtra("signalasi_debug_open_cloud_switch_provider")
            intent?.removeExtra("signalasi_debug_rename_contact")
            intent?.removeExtra("signalasi_debug_rename_name")
            intent?.removeExtra("signalasi_debug_rename_name_b64")
            intent?.removeExtra("signalasi_debug_open_messages")
            intent?.removeExtra("signalasi_debug_open_contacts")
            intent?.removeExtra("signalasi_debug_open_voice")
            intent?.removeExtra("signalasi_debug_open_language_settings")
            intent?.removeExtra("signalasi_debug_open_recent_tasks")
            val seededCloudContact = if (seedCloudProvider.isNotBlank() || openCloudSwitchProvider.isNotBlank()) {
                debugSeedCloudProvider(seedCloudProvider.ifBlank { openCloudSwitchProvider })
            } else {
                null
            }
            if (openMessages) {
                reloadChatHistoryIfChanged(force = true)
                showMainTab(PAGE_MESSAGES)
            }
            if (openContacts) {
                reloadChatHistoryIfChanged(force = true)
                showMainTab(PAGE_CONTACTS)
            }
            if (openVoice) {
                showMainTab(PAGE_VOICE)
            }
            if (openContactId.isNotBlank()) {
                reloadChatHistoryIfChanged(force = true)
                showChatPage(contactById(openContactId))
            }
            if (openContactDetailId.isNotBlank()) {
                showContactDetail(contactById(openContactDetailId))
            }
            if (openNewFriends) {
                showFriendRequestsDialog()
            }
            if (openGroup) {
                showGroupFeaturePage()
            }
            if (openCreateGroup) {
                showCreateGroupFeaturePage()
            }
            if (openDevice) {
                showDeviceFeaturePage()
            }
            if (openAutomation) {
                showAutomationFeaturePage()
            }
            if (openLocalModel) {
                showLocalModelFeaturePage()
            }
            if (openCloudProviders) {
                showCloudProviderPage()
            }
            if (openCloudProvider.isNotBlank()) {
                showCloudModelPage(openCloudProvider)
            }
            if (openCloudSwitchProvider.isNotBlank() && seededCloudContact != null) {
                showCloudModelSwitchPage(seededCloudContact)
            } else if (seedCloudProvider.isNotBlank() && seededCloudContact != null) {
                showChatPage(seededCloudContact)
            }
            if (openAgents) {
                showAgentFeaturePage()
            }
            if (openSecurity) {
                showSecurityFeaturePage()
            }
            if (openVoiceSettings) {
                showVoiceAssistantSettingsPage()
            }
            if (openLanguageSettings) {
                showLanguageSettingsPage()
            }
            if (openOnDeviceAgent) {
                showOnDeviceAgentFeaturePage()
            }
            if (openBackupExport) {
                showExportBackupDialog()
            }
            if (openBackupImport) {
                showBackupImportPasswordPreview()
            }
            if (openDestroyData) {
                confirmDestroyAllData()
            }
            if (openProtocolQuality) {
                showProtocolQualityFeaturePage()
            }
            if (openSignalLinkProtocol) {
                showSignalLinkProtocolPage()
            }
            if (openAdvancedOptions) {
                showAdvancedOptionsFeaturePage()
            }
            if (openRecentTasks) {
                showAgentRecentTasksPage()
            }
            return
        }
        intent?.removeExtra("signalasi_debug_revoke")
        intent?.removeExtra("signalasi_debug_status")
        intent?.removeExtra("signalasi_debug_pairing")
        intent?.removeExtra("signalasi_debug_open_agents")
        intent?.removeExtra("signalasi_debug_open_security")
        intent?.removeExtra("signalasi_debug_open_voice")
        intent?.removeExtra("signalasi_debug_open_voice_settings")
        intent?.removeExtra("signalasi_debug_open_language_settings")
        intent?.removeExtra("signalasi_debug_open_on_device_agent")
        intent?.removeExtra("signalasi_debug_open_backup_export")
        intent?.removeExtra("signalasi_debug_open_backup_import")
        intent?.removeExtra("signalasi_debug_open_destroy_data")
        intent?.removeExtra("signalasi_debug_destroy_all_data")
        intent?.removeExtra("signalasi_debug_open_protocol_quality")
        intent?.removeExtra("signalasi_debug_open_signal_link_protocol")
        intent?.removeExtra("signalasi_debug_open_advanced_options")
        intent?.removeExtra("signalasi_debug_open_recent_tasks")
        intent?.removeExtra("signalasi_debug_open_contact")
        intent?.removeExtra("signalasi_debug_open_contact_detail")
        intent?.removeExtra("signalasi_debug_open_new_friends")
        intent?.removeExtra("signalasi_debug_open_group")
        intent?.removeExtra("signalasi_debug_open_create_group")
        intent?.removeExtra("signalasi_debug_open_device")
        intent?.removeExtra("signalasi_debug_open_automation")
        intent?.removeExtra("signalasi_debug_open_local_model")
        intent?.removeExtra("signalasi_debug_open_cloud_providers")
        intent?.removeExtra("signalasi_debug_open_cloud_provider")
        intent?.removeExtra("signalasi_debug_seed_cloud_provider")
        intent?.removeExtra("signalasi_debug_open_cloud_switch_provider")
        intent?.removeExtra("signalasi_debug_rename_contact")
        intent?.removeExtra("signalasi_debug_rename_name")
        intent?.removeExtra("signalasi_debug_rename_name_b64")
        intent?.removeExtra("signalasi_debug_open_messages")
        intent?.removeExtra("signalasi_debug_open_contacts")
        intent?.removeExtra("signalasi_debug_incoming")
        intent?.removeExtra("signalasi_debug_incoming_b64")
        Log.i("SignalASIDebug", "Processing debug incoming payload")
        if (openVoice) {
            showMainTab(PAGE_VOICE)
        }
        onMessage(payload)
        if (openContacts) {
            reloadChatHistoryIfChanged(force = true)
            showMainTab(PAGE_CONTACTS)
        }
        if (openContactId.isNotBlank()) {
            reloadChatHistoryIfChanged(force = true)
            showChatPage(contactById(openContactId))
        }
        if (openContactDetailId.isNotBlank()) {
            showContactDetail(contactById(openContactDetailId))
        }
        if (openNewFriends) {
            showFriendRequestsDialog()
        }
        if (openGroup) {
            showGroupFeaturePage()
        }
        if (openCreateGroup) {
            showCreateGroupFeaturePage()
        }
        if (openDevice) {
            showDeviceFeaturePage()
        }
        if (openAutomation) {
            showAutomationFeaturePage()
        }
        if (openLocalModel) {
            showLocalModelFeaturePage()
        }
        if (openCloudProviders) {
            showCloudProviderPage()
        }
        if (openCloudProvider.isNotBlank()) {
            showCloudModelPage(openCloudProvider)
        }
        if (openAgents) {
            showAgentFeaturePage()
        }
        if (openSecurity) {
            showSecurityFeaturePage()
        }
        if (openVoiceSettings) {
            showVoiceAssistantSettingsPage()
        }
        if (openLanguageSettings) {
            showLanguageSettingsPage()
        }
        if (openOnDeviceAgent) {
            showOnDeviceAgentFeaturePage()
        }
        if (openBackupExport) {
            showExportBackupDialog()
        }
        if (openBackupImport) {
            showBackupImportPasswordPreview()
        }
        if (openDestroyData) {
            confirmDestroyAllData()
        }
        if (openProtocolQuality) {
            showProtocolQualityFeaturePage()
        }
        if (openSignalLinkProtocol) {
            showSignalLinkProtocolPage()
        }
        if (openAdvancedOptions) {
            showAdvancedOptionsFeaturePage()
        }
        Toast.makeText(this, getString(R.string.debug_incoming_processed), Toast.LENGTH_SHORT).show()
    }

    private fun runDebugBackupRoundtrip(token: String) {
        if ((applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) == 0) return
        val prefs = getSharedPreferences("signalasi_debug", MODE_PRIVATE)
        val password = "SignalASIBackupSmoke2026"
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
            getSharedPreferences(HISTORY_PREFS, MODE_PRIVATE).edit()
                .putString(HISTORY_KEY, historyRoot.toString())
                .putLong(HISTORY_UPDATED_KEY, System.currentTimeMillis())
                .commit()

            val backup = AppStore.exportBackup(
                this,
                password = password,
                includeContacts = true,
                includeMessages = true
            )
            val backupText = backup.readText(Charsets.UTF_8)
            getSharedPreferences("signalasi_app_store", MODE_PRIVATE).edit()
                .putString("contacts", JSONArray().toString())
                .putString("friend_requests", JSONArray().toString())
                .commit()
            getSharedPreferences(HISTORY_PREFS, MODE_PRIVATE).edit()
                .putString(HISTORY_KEY, JSONObject().toString())
                .putLong(HISTORY_UPDATED_KEY, System.currentTimeMillis())
                .commit()

            AppStore.importBackup(this, backup, password, includeMessages = true)
            val restoredContacts = AppStore.contacts(this).toString()
            val restoredHistory = getSharedPreferences(HISTORY_PREFS, MODE_PRIVATE).getString(HISTORY_KEY, "{}").orEmpty()
            val contactRestored = restoredContacts.contains(contactToken) && restoredContacts.contains("Backup Smoke")
            val messageRestored = restoredHistory.contains(messageToken)
            backup.delete()
            val ok = backupText.contains("\"type\":\"signalasi_backup\"") && contactRestored && messageRestored
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
            loadChatHistory()
            refreshContactList()
            refreshDirectoryContacts()
        }.getOrElse { error ->
            prefs.edit()
                .putString("backup_roundtrip_result", JSONObject()
                    .put("ok", false)
                    .put("token", token)
                    .put("error", error.message ?: error.javaClass.simpleName)
                    .toString())
                .commit()
        }
    }

    private fun runDebugCloudModelsRoundtrip(token: String) {
        if ((applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) == 0) return
        val prefs = getSharedPreferences("signalasi_debug", MODE_PRIVATE)
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
            refreshContactList()
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

    private fun runDebugVoiceSettingsRoundtrip(token: String) {
        if ((applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) == 0) return
        val prefs = getSharedPreferences("signalasi_debug", MODE_PRIVATE)
        runCatching {
            val desktopId = "desktop_voice_settings_smoke"
            val serverRouteId = SignalASILinkProtocol.newRouteId()
            val pairingSecret = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(
                ByteArray(32) { index -> (index + 1).toByte() }
            )
            val pairing = JSONObject()
                .put("type", "signalasi_verify")
                .put("protocol", SignalASILinkProtocol.NAME)
                .put("version", SignalASILinkProtocol.VERSION)
                .put("role", "server")
                .put("desktop_id", desktopId)
                .put("desktop_name", "VOICE-PC")
                .put("identity_key_sha256", "ab".repeat(32))
                .put("server_route_id", serverRouteId)
                .put("pairing_topic", "${SignalASILinkProtocol.TOPIC_ROOT}/$serverRouteId/pair")
                .put("pairing_token", UUID.randomUUID().toString().replace("-", "") + token.take(12))
                .put("pairing_secret", pairingSecret)
                .put("created_at", System.currentTimeMillis())
            AppStore.markDesktopVerified(this, pairing)
            checkNotNull(SignalASILinkProtocol.markPaired(this, desktopId))
            val codexId = "$desktopId:codex"
            val welcome = "VOICE_SETTINGS_WELCOME_$token"
            VoiceAssistantSettings.setEnabled(this, true)
            VoiceAssistantSettings.setWakeProvider(this, VoiceAssistantSettings.WAKE_PROVIDER_ANDROID_ASR)
            VoiceAssistantSettings.setWakeWords(this, "SignalASI,voice smoke,$token")
            VoiceAssistantSettings.setWakeModel(this, VoiceAssistantSettings.DEFAULT_WAKE_MODEL)
            VoiceAssistantSettings.setWakeThreshold(this, 0.73f)
            VoiceAssistantSettings.setAsrProvider(this, VoiceAssistantSettings.ASR_PROVIDER_LOCAL_WHISPER)
            VoiceAssistantSettings.setAsrLanguage(this, "en-US")
            VoiceAssistantSettings.setTtsProvider(this, VoiceAssistantSettings.PROVIDER_ANDROID)
            VoiceAssistantSettings.setMicrosoftVoice(this, "zh-CN-XiaoxiaoNeural")
            VoiceAssistantSettings.setWelcomeText(this, welcome)
            VoiceAssistantSettings.setSpeakReplies(this, false)
            VoiceAssistantSettings.setTargetContact(this, codexId)
            VoiceAssistantSettings.setRoutingMode(this, VoiceAssistantSettings.ROUTING_MODE_NATIVE_AGENT)
            val config = VoiceAssistantSettings.get(this)
            val resolvedTarget = resolveVoiceAssistantTargetContactId(config.targetContactId)
            val ok = config.enabled &&
                config.wakeProvider == VoiceAssistantSettings.WAKE_PROVIDER_ANDROID_ASR &&
                config.wakeWords.contains("voice smoke") &&
                config.wakeModel == VoiceAssistantSettings.DEFAULT_WAKE_MODEL &&
                kotlin.math.abs(config.wakeThreshold - 0.73f) < 0.001f &&
                config.asrProvider == VoiceAssistantSettings.ASR_PROVIDER_LOCAL_WHISPER &&
                config.asrLanguage == "en-US" &&
                config.ttsProvider == VoiceAssistantSettings.PROVIDER_ANDROID &&
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
                    .put("wake_words", JSONArray(config.wakeWords))
                    .put("wake_model", config.wakeModel)
                    .put("wake_threshold", config.wakeThreshold.toDouble())
                    .put("asr_provider", config.asrProvider)
                    .put("asr_language", config.asrLanguage)
                    .put("tts_provider", config.ttsProvider)
                    .put("microsoft_voice", config.microsoftVoice)
                    .put("welcome_text", config.welcomeText)
                    .put("speak_replies", config.speakReplies)
                    .put("routing_mode", config.routingMode)
                    .put("target_contact_id", config.targetContactId)
                    .put("resolved_target_contact_id", resolvedTarget)
                    .toString())
                .commit()
            refreshContactList()
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

    private fun runDebugControlCenterRoundtrip(token: String) {
        if ((applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) == 0) return
        val debugPreferences = getSharedPreferences("signalasi_debug", MODE_PRIVATE)
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

    private fun scheduleDebugOutgoing(contactId: String, content: String, attempt: Int) {
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
            if (!SignalASIMqttClient.isConnected() || !SignalASIMqttClient.isSecureReady()) {
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

    private fun scheduleDebugAgentGoal(token: String, goal: String, newConversation: Boolean) {
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
            agentGoalInput.setText(goal)
            agentGoalInput.setSelection(agentGoalInput.text?.length ?: 0)
            submitAgentGoal()
            scheduleDebugAgentSnapshot(token, conversationId, startedAt, attempt = 0)
        }, 250L)
    }

    private fun scheduleDebugAgentSnapshot(
        token: String,
        conversationId: String,
        startedAt: Long,
        attempt: Int
    ) {
        handler.postDelayed({
            val entries = agentTranscriptStore.list(conversationId)
                .filter { it.timestampMillis >= startedAt - 1_000L }
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
                scheduleDebugAgentSnapshot(token, conversationId, startedAt, attempt + 1)
            }
        }, 250L)
    }

    private fun sendImage(uri: Uri) {
        val meta = imageMeta(uri)
        val msg = ChatMessage(newMessageId(), getString(R.string.message_image_prefix, meta.name), true, CONTACT_ME)
        addMessage(msg)
    }

    private fun targetTopicForSelectedContact(): String? {
        val contact = selectedContact ?: return null
        return AppStore.outgoingTopicForContact(this, contact.id)
    }

    private fun markDeliveredSoon(msg: ChatMessage, contactId: String) {
        handler.postDelayed({
            appendDeliveryTrace(msg.id, contactId, "delivered_local_estimate", "QoS1 publish accepted")
            updateMessageStatus(msg.id, contactId, getString(R.string.delivery_status_delivered))
        }, 700)
    }

    private fun updateMessageStatus(messageId: Long, contactId: String, status: String) {
        val list = messages[contactId] ?: return
        val index = list.indexOfFirst { it.id == messageId }
        if (index >= 0) {
            list[index].deliveryStatus = status
            saveChatHistory()
            if (chatPage.visibility == View.VISIBLE && selectedContact?.id == contactId) {
                messageList.post {
                    if (chatPage.visibility == View.VISIBLE && selectedContact?.id == contactId) {
                        messageAdapter?.notifyItemChanged(index)
                    }
                }
            }
        }
    }

    private fun appendDeliveryTrace(messageId: Long, contactId: String, stage: String, detail: String = "") {
        val list = messages[contactId] ?: return
        val index = list.indexOfFirst { it.id == messageId }
        if (index < 0) return
        list[index].deliveryTrace.add(newTraceEvent(stage, detail))
        saveChatHistory()
        if (chatPage.visibility == View.VISIBLE && selectedContact?.id == contactId) {
            messageList.post {
                if (chatPage.visibility == View.VISIBLE && selectedContact?.id == contactId) {
                    messageAdapter?.notifyItemChanged(index)
                }
            }
        }
    }

    private fun mergeDeliveryTrace(messageId: Long, contactId: String, trace: List<DeliveryTraceEvent>, status: String? = null) {
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
        saveChatHistory()
        if (chatPage.visibility == View.VISIBLE && selectedContact?.id == contactId) {
            messageList.post {
                if (chatPage.visibility == View.VISIBLE && selectedContact?.id == contactId) {
                    messageAdapter?.notifyItemChanged(index)
                }
            }
        }
    }

    private fun markContactRead(contactId: String) {
        val list = messages[contactId] ?: return
        var changed = false
        list.forEach { message ->
            if (!message.isMine && !message.isSystem && !hasTraceStage(message, "read")) {
                message.deliveryTrace.add(newTraceEvent("read", "chat_opened"))
                message.deliveryStatus = getString(R.string.delivery_status_read)
                changed = true
            }
        }
        if (!changed) return
        saveChatHistory()
        if (chatPage.visibility == View.VISIBLE && selectedContact?.id == contactId) {
            messageList.post {
                if (chatPage.visibility == View.VISIBLE && selectedContact?.id == contactId) {
                    messageAdapter?.notifyDataSetChanged()
                }
            }
        }
    }

    private fun hasTraceStage(message: ChatMessage, stage: String): Boolean {
        return message.deliveryTrace.any { it.stage == stage }
    }

    private fun newTraceEvent(stage: String, detail: String = ""): DeliveryTraceEvent {
        return DeliveryTraceEvent(stage = stage, at = System.currentTimeMillis(), detail = detail)
    }

    private fun deliveryTraceText(message: ChatMessage): String {
        if (message.deliveryTrace.isEmpty()) return getString(R.string.delivery_trace_empty)
        val origin = message.deliveryTrace.first().at
        return message.deliveryTrace.takeLast(32).joinToString("\n") { event ->
            val detail = event.detail.takeIf { it.isNotBlank() }?.let { " · $it" }.orEmpty()
            val clock = java.text.SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault()).format(java.util.Date(event.at))
            "$clock +${(event.at - origin).coerceAtLeast(0L)} ms ${deliveryTraceLabel(event.stage)}$detail"
        }
    }

    private fun deliveryTraceLabel(stage: String): String = when (stage) {
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
        "agent_replied" -> getString(R.string.delivery_trace_agent_replied)
        "agent_accepted" -> getString(R.string.agent_task_status_accepted)
        "agent_queued" -> getString(R.string.agent_task_status_queued)
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

    private fun parseIncomingMessage(payload: String): ChatMessage {
        val json = runCatching { JSONObject(payload) }.getOrNull()
        val incomingTrace = incomingDeliveryTrace(json)
        if (json?.optString("type") == "delivery_ack") {
            applyDeliveryAck(json, incomingTrace)
            return ChatMessage(newMessageId(), "", false, CONTACT_SYSTEM, isSystem = true, deliveryTrace = incomingTrace)
        }
        if (json?.optString("type") == "capability_manifest") {
            json.optJSONArray("connector_agents")?.let { agents ->
                AppStore.updateConnectorAgentStatuses(this, agents)
                syncAgentRegistrySnapshot(force = true)
                refreshContactList()
                refreshDirectoryContacts()
            }
            return ChatMessage(newMessageId(), "", false, CONTACT_SYSTEM, isSystem = true, deliveryTrace = incomingTrace)
        }
        if (json?.optString("type") == "pairing_revoked") {
            Log.w("SignalASIDebug", "Pairing revoked control message received")
            val desktopId = json.optString("desktop_id")
            if (desktopId.isNotBlank()) {
                AppStore.deleteDesktopConnector(this, desktopId, deleteMessages = false)
            } else {
                AppStore.deleteContact(this, "hermes", deleteMessages = false)
            }
            SignalASIMqttClient.forgetSecureChannel()
            syncAgentRegistrySnapshot(force = true)
            refreshContactList()
            refreshDirectoryContacts()
            val content = json.optString("content")
                .ifBlank { getString(R.string.system_pairing_revoked_default) }
            return ChatMessage(newMessageId(), content, false, CONTACT_SYSTEM, deliveryTrace = incomingTrace)
        }
        if (json?.optString("type") == "pairing_confirmed" || json?.optString("type") == "connector_status") {
            json.optJSONArray("connector_agents")?.let { agents ->
                AppStore.updateConnectorAgentStatuses(this, agents)
                syncAgentRegistrySnapshot(force = true)
                refreshContactList()
                refreshDirectoryContacts()
            }
            val content = json.optString("content")
                .ifBlank { getString(R.string.system_connector_status_updated) }
            return ChatMessage(newMessageId(), content, false, CONTACT_SYSTEM, deliveryTrace = incomingTrace)
        }
        if (json?.optString("type") == "profile_update") {
            val senderId = json.optString("sender")
                .ifBlank { json.optString("signalasi_id") }
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
            ?: json?.optString("content", payload)?.takeIf { it.isNotBlank() }
            ?: payload
        val sender = json?.optString("sender", "hermes") ?: "hermes"
        val contactId = json?.optString("contact_id", CONTACT_HERMES.id)?.takeIf { it.isNotBlank() } ?: CONTACT_HERMES.id
        val contact = contactById(if (sender == "system") CONTACT_SYSTEM.id else contactId)
        return ChatMessage(
            newMessageId(),
            content,
            sender == "self",
            contact,
            deliveryTrace = incomingTrace,
            taskId = json?.optString("task_id").orEmpty(),
            taskStatus = json?.optString("task_status").orEmpty()
        )
    }

    private fun exactConnectorContent(json: JSONObject?): String? {
        if (json?.optString("exact_content_encoding") != "base64-utf8") return null
        val encoded = json.optString("exact_content_b64")
        if (encoded.isBlank() || encoded.length > 256 * 1024) return null
        return runCatching {
            String(Base64.decode(encoded, Base64.DEFAULT), Charsets.UTF_8)
                .takeIf { value -> value.toByteArray(Charsets.UTF_8).size <= 128 * 1024 }
        }.getOrNull()
    }

    // ===== Recording =====
    private fun startRecording(purpose: String): Boolean {
        if (recorder != null) return false
        val file = File(cacheDir, "voice_${System.currentTimeMillis()}.m4a")
        var candidate: MediaRecorder? = null
        return runCatching {
            candidate = createRecorder().apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setAudioEncodingBitRate(96_000)
                setAudioSamplingRate(44_100)
                setOutputFile(file.absolutePath)
                prepare()
                start()
            }
            recorder = candidate
            recordingFile = file
            recordingStartedAt = System.currentTimeMillis()
            recordingPurpose = purpose
            Log.i("SignalASIVoice", "Recording started purpose=$purpose file=${file.name}")
            true
        }.getOrElse {
            runCatching { candidate?.reset() }
            runCatching { candidate?.release() }
            recorder = null
            recordingFile = null
            recordingPurpose = ""
            file.delete()
            Log.e("SignalASIVoice", "Chat recording start failed", it)
            false
        }
    }

    private fun showVoiceOverlay() {
        val waveView = object : View(this) {
            private var phase = 0f
            private val paint = Paint().apply {
                color = 0xFFFFFFFF.toInt()
                style = Paint.Style.FILL
                isAntiAlias = true
            }

            override fun onDraw(canvas: Canvas) {
                val w = width.toFloat()
                val h = height.toFloat()
                val amp = (recorder?.maxAmplitude ?: 0).coerceIn(0, 32767) / 32767f
                val cy = h / 2f
                val maxAmp = h * 0.4f * (amp * 0.8f + 0.2f)
                val step = 2f
                var x = 0f
                while (x < w) {
                    val y = cy + sin(phase + x * 0.08f).toFloat() * maxAmp
                    canvas.drawCircle(x, y, 4.5f, paint)
                    x += step
                }
                phase += 0.03f
                postInvalidateDelayed(80)
            }
        }
        waveView.setBackgroundColor(0xBF07C160.toInt())
        val size = dp(270)
        voiceOverlay = Dialog(this, android.R.style.Theme_Translucent_NoTitleBar).apply {
            setContentView(waveView)
            window?.setLayout(size, dp(120))
            window?.setGravity(Gravity.CENTER)
            setCancelable(false)
            setCanceledOnTouchOutside(false)
            show()
        }
        waveView.post { waveView.invalidate() }
    }

    private fun hideVoiceOverlay() {
        voiceOverlay?.dismiss()
        voiceOverlay = null
    }

    private fun stopRecording(send: Boolean) {
        if (recordingPurpose == "agent_input") {
            stopAgentInputRecording(send)
            return
        }
        val activeRecorder = recorder ?: return
        recorder = null
        val stoppedCleanly = runCatching {
            activeRecorder.stop()
            true
        }.getOrDefault(false)
        runCatching { activeRecorder.reset() }
        runCatching { activeRecorder.release() }
        val file = recordingFile
        recordingFile = null
        recordingPurpose = ""
        if (!send || !stoppedCleanly || file == null || !file.exists() || file.length() <= 0L) {
            file?.delete()
            return
        }
        val seconds = ((System.currentTimeMillis() - recordingStartedAt) / 1000).coerceAtLeast(1)
        val contact = selectedContact ?: CONTACT_HERMES
        sendVoiceRecordingThroughPipeline(
            sourceFile = file,
            contact = contact,
            seconds = seconds,
            label = "${getString(R.string.message_voice_prefix)} ${seconds}s",
            source = "chat_hold_to_talk"
        )
    }

    private fun stopAgentInputRecording(send: Boolean) {
        val activeRecorder = recorder ?: return
        val purpose = recordingPurpose
        recorder = null
        val stoppedCleanly = runCatching {
            activeRecorder.stop()
            true
        }.getOrDefault(false)
        runCatching { activeRecorder.reset() }
        runCatching { activeRecorder.release() }
        val file = recordingFile
        recordingFile = null
        recordingPurpose = ""
        Log.i("SignalASIVoice", "Agent input recording stopped purpose=$purpose send=$send clean=$stoppedCleanly bytes=${file?.length() ?: 0L}")
        if (!send || !stoppedCleanly || file == null || !file.exists() || file.length() <= 0L) {
            file?.delete()
            return
        }
        val durationMs = (System.currentTimeMillis() - recordingStartedAt).coerceAtLeast(1L)
        val seconds = ((durationMs + 999L) / 1000L).coerceAtLeast(1L)
        agentTranscriptStore.append(
            AgentTranscriptRole.USER,
            getString(R.string.agent_voice_message_label, seconds),
            dedupeKey = "agent-voice-pending:${file.name}"
        )
        renderAgentTranscript(agentTranscriptStore.list())
        requestAgentInputTranscription(file)
    }

    private fun requestAgentInputTranscription(sourceFile: File): Boolean {
        transcribeLocally(sourceFile, onSuccess = { transcript ->
            agentGoalInput.setText(transcript)
            agentGoalInput.setSelection(agentGoalInput.text?.length ?: 0)
            submitAgentGoal()
        })
        return true
    }

    private fun sendVoiceRecordingThroughPipeline(
        sourceFile: File,
        contact: Contact,
        seconds: Long,
        label: String,
        source: String
    ): Boolean {
        if (!sourceFile.exists()) return false
        val msgId = newMessageId()
        val voiceFile = File(cacheDir, "voices/msg_${msgId}.m4a").apply {
            parentFile?.mkdirs()
        }
        val moved = sourceFile.renameTo(voiceFile)
        val finalFile = if (moved) voiceFile else sourceFile
        val msg = ChatMessage(msgId, label, true, CONTACT_ME)
        addMessage(msg)
        Log.i("SignalASIVoice", "Voice pipeline send source=$source target=${contact.id} seconds=$seconds bytes=${finalFile.length()} messageId=$msgId")
        publishInlineVoiceFile(msg.id, contact, finalFile)
        return true
    }

    private fun requestVoiceAgentTranscription(sourceFile: File, contact: Contact): Boolean {
        if (!sourceFile.exists()) return false
        transcribeLocally(sourceFile, onSuccess = { transcript -> submitVoiceAgentGoal(transcript) })
        return true
    }

    private fun publishInlineVoiceFile(messageId: Long, contact: Contact, file: File) {
        updateMessageStatus(messageId, contact.id, getString(R.string.voice_status_transcribing))
        transcribeLocally(file, onSuccess = { transcript ->
            updateMessageStatus(messageId, contact.id, getString(R.string.voice_status_transcribed))
            sendOutgoingText(contact, transcript)
        }, onFailure = {
            updateMessageStatus(messageId, contact.id, getString(R.string.delivery_status_failed))
        })
    }

    private fun transcribeLocally(
        sourceFile: File,
        onSuccess: (String) -> Unit,
        onFailure: () -> Unit = {}
    ) {
        val language = VoiceAssistantSettings.get(this).asrLanguage
        voiceAssistantScope.launch {
            val result = runCatching { LocalWhisperAsr.transcribe(this@MainActivity, sourceFile, language) }
            sourceFile.delete()
            runOnUiThread {
                val transcript = result.getOrNull().orEmpty().trim()
                if (transcript.isNotBlank()) {
                    onSuccess(transcript)
                } else {
                    Log.e("SignalASILocalASR", "Local transcription failed", result.exceptionOrNull())
                    Toast.makeText(
                        this@MainActivity,
                        result.exceptionOrNull()?.message ?: getString(R.string.voice_status_transcription_failed),
                        Toast.LENGTH_LONG
                    ).show()
                    onFailure()
                }
            }
        }
    }

    private fun playVoiceMessage(msgId: Long) {
        val voiceFile = File(cacheDir, "voices/msg_${msgId}.m4a")
        if (!voiceFile.exists()) {
            Toast.makeText(this, getString(R.string.voice_file_missing), Toast.LENGTH_SHORT).show()
            return
        }
        player?.let { if (it.isPlaying) { it.stop(); it.release(); player = null; return } }
        try {
            android.media.MediaPlayer().apply {
                setDataSource(voiceFile.absolutePath)
                prepare()
                start()
                setOnCompletionListener { release(); player = null }
                player = this
            }
        } catch (e: Exception) {
            Toast.makeText(this, getString(R.string.toast_send_failed, e.message ?: ""), Toast.LENGTH_SHORT).show()
        }
    }

    private fun createRecorder() = MediaRecorder()

    // ===== Message Management =====
    private fun addMessage(msg: ChatMessage, fromIncoming: Boolean = false) {
        val targetContact = if (msg.isMine) selectedContact else msg.contact
        val list = messages.getOrPut(targetContact?.id ?: msg.contact.id) { mutableListOf() }
        val stored = if (msg.contact.id == (targetContact?.id ?: msg.contact.id)) msg else msg.copy(contact = targetContact ?: msg.contact)
        stored.deliveryTrace.add(newTraceEvent("persisted", "local_history"))
        val isVisibleIncoming = fromIncoming && !stored.isMine && !stored.isSystem &&
            chatPage.visibility == View.VISIBLE && selectedContact?.id == stored.contact.id
        if (isVisibleIncoming && !hasTraceStage(stored, "read")) {
            stored.deliveryTrace.add(newTraceEvent("read", "chat_visible"))
            stored.deliveryStatus = getString(R.string.delivery_status_read)
        }
        list.add(stored)
        trimHistory(list)
        if (!stored.isSystem) {
            val summary = summaries.getOrPut(stored.contact.id) { ContactSummary() }
            summary.lastMessage = stored.content
            summary.lastAt = stored.timestamp
            if (fromIncoming && (chatPage.visibility != View.VISIBLE || selectedContact?.id != stored.contact.id)) {
                summary.unreadCount += 1
            }
        }
        saveChatHistory()
        if (!stored.isSystem) {
            GlobalConversationEventBus.publishChatMessage(
                this,
                stored.contact.id,
                stored.contact.name,
                stored.id,
                stored.content,
                if (stored.isMine) GlobalConversationActor.USER else GlobalConversationActor.ASSISTANT,
                stored.timestamp,
                mapOf(
                    "direction" to if (stored.isMine) "outgoing" else "incoming",
                    "task_id" to stored.taskId
                )
            )
        }
        refreshVisibleMessages(stored.contact.id)
        refreshContactList()
    }

    private fun deleteMessageAt(contactId: String, position: Int) {
        val list = messages[contactId] ?: return
        if (position < 0 || position >= list.size) return
        val removed = list.removeAt(position)
        if (!removed.isSystem) {
            GlobalConversationEventBus.publishChatMessageDeleted(this, contactId, removed.id)
        }
        saveChatHistory()
        if (chatPage.visibility == View.VISIBLE && selectedContact?.id == contactId) {
            messageList.post {
                messageAdapter?.notifyItemRemoved(position)
            }
        }
    }

    private fun showMessageActionsPage(position: Int) {
        val contact = selectedContact ?: return
        val message = currentMessages.getOrNull(position) ?: return
        showFeaturePage(getString(R.string.message_actions_title))
        featureContent.addView(featureHeroCard(
            if (message.isMine) getString(R.string.message_sent_by_me) else contact.name,
            message.content.take(80),
            contactAvatarRes(if (message.isMine) CONTACT_ME else message.contact),
            if (message.isMine) "#14C66A" else "#5B6CFF",
            bubbleTime(message.timestamp)
        ))
        addSectionTitle(getString(R.string.section_actions))
        featureContent.addView(featureRow(getString(R.string.message_copy_title), getString(R.string.message_copy_subtitle), R.drawable.ic_protocol_link, getString(R.string.common_copy)).apply {
            setOnClickListener {
                val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("SignalASI message", message.content))
                Toast.makeText(this@MainActivity, getString(R.string.toast_copied), Toast.LENGTH_SHORT).show()
                showChatPage(contact)
            }
        })
        if (message.taskId.isNotBlank() && message.taskStatus in setOf("accepted", "queued", "running", "waiting_input")) {
            featureContent.addView(featureRow(getString(R.string.agent_task_cancel_title), getString(R.string.agent_task_cancel_subtitle), R.drawable.ic_delete, getString(R.string.common_cancel)).apply {
                setOnClickListener {
                    val sent = SignalASIMqttClient.publishAgentTaskCancel(
                        taskId = message.taskId,
                        contactId = contact.id,
                        sourceMessageId = message.id,
                        topicOverride = AppStore.outgoingTopicForContact(this@MainActivity, contact.id)
                    )
                    if (sent) {
                        message.deliveryStatus = getString(R.string.agent_task_status_cancelling)
                        saveChatHistory()
                    }
                    showChatPage(contact)
                }
            })
        }
        featureContent.addView(featureRow(getString(R.string.message_delete_title), getString(R.string.message_delete_subtitle), R.drawable.ic_delete, getString(R.string.common_delete)).apply {
            setOnClickListener {
                deleteMessageAt(contact.id, position)
                Toast.makeText(this@MainActivity, getString(R.string.toast_deleted), Toast.LENGTH_SHORT).show()
                showChatPage(contact)
            }
        })
        addSectionTitle(getString(R.string.section_details))
        featureContent.addView(featureRow(getString(R.string.message_sent_time), bubbleTime(message.timestamp), R.drawable.ic_protocol_link, ""))
        if (message.taskId.isNotBlank()) {
            featureContent.addView(featureRow(getString(R.string.agent_task_details_title), message.taskId, R.drawable.ic_protocol_link, message.deliveryStatus.orEmpty()))
        }
        featureContent.addView(featureRow(getString(R.string.message_security_status), getString(R.string.message_security_status_subtitle), R.drawable.ic_security_shield, ""))
        featureContent.addView(featureRow(getString(R.string.message_delivery_trace), deliveryTraceText(message), R.drawable.ic_protocol_link, ""))
    }

    private fun trimHistory(list: MutableList<ChatMessage>) {
        while (list.size > MAX_SAVED_MESSAGES_PER_CONTACT) list.removeAt(0)
    }

    private fun refreshVisibleMessages(contactId: String) {
        if (chatPage.visibility != View.VISIBLE || selectedContact?.id != contactId) return
        messageList.post {
            if (chatPage.visibility == View.VISIBLE && selectedContact?.id == contactId) {
                messageAdapter?.notifyDataSetChanged()
                scrollToBottom()
            }
        }
    }

    // ===== Chat History =====
    private fun loadChatHistory() {
        val prefs = getSharedPreferences(HISTORY_PREFS, MODE_PRIVATE)
        val raw = prefs.getString(HISTORY_KEY, null)
        lastHistoryLoadedAt = prefs.getLong(HISTORY_UPDATED_KEY, 0L)
        if (raw.isNullOrBlank()) {
            seedWelcomeSystemNotification()
            return
        }
        val root = runCatching { JSONObject(raw) }.getOrNull() ?: return
        messages.clear()
        summaries.clear()
        var maxId = 0L
        var removedTransientSystemEvents = false
        val contactIds = mutableSetOf<String>()
        val keys = root.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            if (key.isNotBlank()) contactIds.add(key)
        }
        contactIds.forEach { contactId ->
            val contact = contactById(contactId) ?: return@forEach
            val array = root.optJSONArray(contactId) ?: return@forEach
            val list = mutableListOf<ChatMessage>()
            for (i in 0 until array.length()) {
                val item = array.optJSONObject(i) ?: continue
                val messageContact = contactById(item.optString("contactId", contactId)) ?: contact
                val savedContent = item.optString("content")
                if (contactId == CONTACT_SYSTEM.id && isLegacySystemChatStarter(savedContent)) continue
                if (contactId == CONTACT_SYSTEM.id && isTransientCloudSystemEvent(item)) {
                    removedTransientSystemEvents = true
                    continue
                }
                val message = ChatMessage(
                    id = item.optLong("id", newMessageId()),
                    content = savedContent,
                    isMine = item.optBoolean("isMine"),
                    contact = messageContact,
                    isSystem = item.optBoolean("isSystem"),
                    timestamp = item.optLong("timestamp", System.currentTimeMillis()),
                    deliveryStatus = item.optString("deliveryStatus").takeIf { it.isNotBlank() },
                    taskId = item.optString("taskId"),
                    taskStatus = item.optString("taskStatus"),
                    taskStatusSeq = item.optLong("taskStatusSeq", 0L),
                    deliveryTrace = parseDeliveryTrace(item.optJSONArray("deliveryTrace"))
                )
                if (message.content.isBlank()) continue
                list.add(message)
                maxId = maxOf(maxId, message.id)
            }
            if (list.isNotEmpty()) {
                messages[contactId] = list
                list.lastOrNull { !it.isSystem }?.let { last ->
                    val unread = list.count { message ->
                        !message.isMine && !message.isSystem && !hasTraceStage(message, "read")
                    }
                    summaries[contactId] = ContactSummary(last.content, last.timestamp, unread)
                }
            }
        }
        nextMessageId = maxOf(nextMessageId, maxId + 1)
        if (messages.isEmpty()) seedWelcomeSystemNotification()
        else if (removedTransientSystemEvents) saveChatHistory()
    }

    private fun reloadChatHistoryIfChanged(force: Boolean = false) {
        val updatedAt = getSharedPreferences(HISTORY_PREFS, MODE_PRIVATE).getLong(HISTORY_UPDATED_KEY, 0L)
        if (!force && updatedAt <= lastHistoryLoadedAt) return
        val selectedId = selectedContact?.id
        loadChatHistory()
        refreshContactList()
        refreshDirectoryContacts()
        selectedId?.let { contactId ->
            refreshVisibleMessages(contactId)
        }
    }

    private fun seedWelcomeSystemNotification() {
        messages.clear()
        summaries.clear()
        val content = getString(R.string.system_welcome_message)
        val welcome = ChatMessage(
            id = newMessageId(),
            content = content,
            isMine = false,
            contact = CONTACT_SYSTEM,
            isSystem = false,
            timestamp = System.currentTimeMillis(),
            deliveryStatus = null
        )
        messages[CONTACT_SYSTEM.id] = mutableListOf(welcome)
        summaries[CONTACT_SYSTEM.id] = ContactSummary(content, welcome.timestamp, 0)
        saveChatHistory()
    }

    private fun ensureDesignSummaries() {
        val now = System.currentTimeMillis()
        if (summaries[CONTACT_SYSTEM.id]?.lastMessage.isNullOrBlank()) {
            summaries[CONTACT_SYSTEM.id] = ContactSummary(getString(R.string.system_welcome_title), now, 0)
        }
    }

    private fun isLegacySystemChatStarter(content: String): Boolean {
        return content.contains(getString(R.string.legacy_chat_started_marker)) ||
            content.contains(getString(R.string.legacy_chat_started_suffix)) ||
            content.contains("\u5bf9\u8bdd\u5df2\u5f00\u59cb") ||
            content.contains("\u7684\u5bf9\u8bdd\u5df2\u5f00\u59cb")
    }

    private fun isTransientCloudSystemEvent(item: JSONObject): Boolean {
        val trace = item.optJSONArray("deliveryTrace") ?: return false
        for (index in 0 until trace.length()) {
            val stage = trace.optJSONObject(index)?.optString("stage").orEmpty()
            if (stage == "cloud_voice_transcribed" || stage.startsWith("cloud_tool_")) return true
        }
        return false
    }

    private fun parseDeliveryTrace(array: JSONArray?): MutableList<DeliveryTraceEvent> {
        if (array == null) return mutableListOf()
        val trace = mutableListOf<DeliveryTraceEvent>()
        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i) ?: continue
            val stage = item.optString("stage")
            if (stage.isBlank()) continue
            trace.add(DeliveryTraceEvent(
                stage = stage,
                at = item.optLong("at", System.currentTimeMillis()),
                detail = item.optString("detail")
            ))
        }
        return trace
    }

    private fun incomingDeliveryTrace(json: JSONObject?): MutableList<DeliveryTraceEvent> {
        if (json == null) return mutableListOf()
        return parseDeliveryTrace(json.optJSONArray("delivery_trace") ?: json.optJSONArray("deliveryTrace"))
    }

    private fun applyDeliveryAck(json: JSONObject, trace: List<DeliveryTraceEvent>) {
        val messageId = json.optString("source_message_id").toLongOrNull()
            ?: json.optLong("source_message_id", 0L).takeIf { it > 0L }
            ?: return
        val contactId = json.optString("contact_id").takeIf { it.isNotBlank() } ?: selectedContact?.id ?: return
        val status = when (json.optString("delivery_status")) {
            "broker_ack" -> getString(R.string.delivery_status_confirmed)
            "read" -> getString(R.string.delivery_status_read)
            "notified" -> getString(R.string.delivery_status_notified)
            else -> json.optString("delivery_status").ifBlank { getString(R.string.delivery_status_confirmed) }
        }
        val taskBound = messages[contactId]?.firstOrNull { it.id == messageId }?.taskId?.isNotBlank() == true
        mergeDeliveryTrace(messageId, contactId, trace, if (taskBound) null else status)
    }

    private fun deliveryTraceJson(trace: List<DeliveryTraceEvent>): JSONArray {
        val array = JSONArray()
        trace.forEach { event ->
            array.put(JSONObject()
                .put("stage", event.stage)
                .put("at", event.at)
                .put("detail", event.detail))
        }
        return array
    }

    private fun saveChatHistory(sync: Boolean = false) {
        if (!sync) {
            handler.removeCallbacks(historySaveRunnable)
            handler.postDelayed(historySaveRunnable, 120L)
            return
        }
        handler.removeCallbacks(historySaveRunnable)
        persistChatHistorySnapshot(sync = true)
    }

    private fun enqueueChatHistorySave() {
        persistChatHistorySnapshot(sync = false)
    }

    private fun persistChatHistorySnapshot(sync: Boolean) {
        val snapshot = messages.mapValues { (_, list) ->
            list.takeLast(MAX_SAVED_MESSAGES_PER_CONTACT).map { message ->
                message.copy(deliveryTrace = message.deliveryTrace.toMutableList())
            }
        }
        val saveSeq = historySaveSeq.incrementAndGet()
        val writeSnapshot = {
            val root = JSONObject()
            snapshot.forEach { (contactId, list) ->
                val array = JSONArray()
                list.forEach { message ->
                    array.put(JSONObject()
                        .put("id", message.id)
                        .put("content", message.content)
                        .put("isMine", message.isMine)
                        .put("contactId", message.contact.id)
                        .put("isSystem", message.isSystem)
                        .put("timestamp", message.timestamp)
                        .put("deliveryStatus", message.deliveryStatus ?: "")
                        .put("taskId", message.taskId)
                        .put("taskStatus", message.taskStatus)
                        .put("taskStatusSeq", message.taskStatusSeq)
                        .put("deliveryTrace", deliveryTraceJson(message.deliveryTrace)))
                }
                root.put(contactId, array)
            }
            val updatedAt = System.currentTimeMillis()
            getSharedPreferences(HISTORY_PREFS, MODE_PRIVATE).edit()
                .putString(HISTORY_KEY, root.toString())
                .putLong(HISTORY_UPDATED_KEY, updatedAt)
                .apply()
            lastHistoryLoadedAt = updatedAt
        }
        if (sync) {
            writeSnapshot()
            return
        }
        runCatching {
            historyExecutor.execute {
                if (saveSeq < historySaveSeq.get()) return@execute
                writeSnapshot()
            }
        }
    }

    // ===== Refreshing =====
    private fun refreshContactList() {
        ensureDesignSummaries()
        contactAdapter?.replaceContacts(buildChatContacts())
    }

    private fun refreshDirectoryContacts() {
        val items = buildDirectoryContacts()
        directoryContacts.clear()
        directoryContacts.addAll(items)
        runOnUiThread {
            directoryAdapter?.replaceContacts(items)
        }
    }

    private fun buildDirectoryContacts(): List<Contact> = storedContacts()

    private fun buildChatContacts(): List<Contact> {
        val items = storedContacts().toMutableList()
        items.sortWith(compareBy<Contact> { contactPriority(it.id) }.thenBy { it.name.lowercase(Locale.getDefault()) })
        items.add(CONTACT_SYSTEM)
        return items
    }

    private fun storedContacts(): List<Contact> {
        val contacts = AppStore.contacts(this)
        val items = mutableListOf<Contact>()
        for (i in 0 until contacts.length()) {
            val c = contacts.optJSONObject(i) ?: continue
            if (c.optBoolean("deleted", false)) continue
            if (c.optString("trust_state") == "deleted") continue
            val id = c.optString("id").ifBlank { jsonSignalasiId(c) }
            if (id.isBlank()) continue
            val name = c.optString("name", id)
            items.add(Contact(id, name, ""))
        }
        return items.sortedWith(compareBy<Contact> { contactPriority(it.id) }.thenBy { it.name.lowercase(Locale.getDefault()) })
    }

    private fun contactPriority(id: String): Int = when {
        id == "hermes" -> 0
        id == "codex" -> 1
        id == "claude" -> 2
        id == "openclaw" -> 3
        id == "local-llm" -> 4
        id.startsWith("cloud:") -> 5
        id == "custom-agent" -> 6
        id == CONTACT_SYSTEM.id -> 99
        else -> 20
    }

    private fun showAddContactMenu() {
        showFeaturePage(getString(R.string.add_contact_title))
        featureContent.addView(featureHeroCard(
            getString(R.string.add_contact_hero_title),
            getString(R.string.add_contact_hero_subtitle),
            R.drawable.signalasi_mark,
            "#14C66A",
            getString(R.string.common_select)
        ))
        addSectionTitle(getString(R.string.add_contact_section_methods))
        featureContent.addView(featureRow(getString(R.string.add_contact_scan_title), getString(R.string.add_contact_scan_subtitle), R.drawable.ic_scan, getString(R.string.security_scan)).apply {
            setOnClickListener {
                scanMode = "contact"
                startSecurityScan()
            }
        })
        featureContent.addView(featureRow(getString(R.string.add_cloud_model_title), getString(R.string.add_cloud_model_subtitle), R.drawable.ic_avatar_cloud_model, getString(R.string.add_contact_title)).apply {
            setOnClickListener { showCloudProviderPage() }
        })
    }

    private fun showCloudProviderPage() {
        showFeaturePage(getString(R.string.cloud_models_title))
        featureContent.addView(featureHeroCard(
            getString(R.string.cloud_select_provider),
            getString(R.string.cloud_provider_hero_subtitle),
            R.drawable.ic_avatar_cloud_model,
            "#5B6CFF",
            getString(R.string.cloud_direct)
        ))
        addSectionTitle("Provider")
        cloudProviders().forEach { provider ->
            featureContent.addView(featureRow(provider, providerSubtitle(provider), providerIcon(provider), getString(R.string.cloud_provider_count, modelsForProvider(provider).size)).apply {
                setOnClickListener { showCloudModelPage(provider) }
            })
        }
    }

    private fun showCloudModelPage(provider: String) {
        showFeaturePage(provider)
        setFeatureBackAction { showCloudProviderPage() }
        featureContent.addView(featureHeroCard(provider, providerSubtitle(provider), providerIcon(provider), providerColor(provider), getString(R.string.cloud_select_model)))
        addSectionTitle(getString(R.string.cloud_section_model))
        modelsForProvider(provider).forEach { preset ->
            featureContent.addView(featureRow(preset.name, "", R.drawable.ic_protocol_link, getString(R.string.common_select)).apply {
                setOnClickListener { showCloudModelConfigPage(preset) }
            })
        }
        if (provider != "Custom") {
            featureContent.addView(featureRow(getString(R.string.cloud_custom_model_id), getString(R.string.cloud_custom_model_subtitle, provider), R.drawable.ic_import, getString(R.string.common_edit)).apply {
                setOnClickListener {
                    val base = modelsForProvider(provider).firstOrNull()
                    showCloudModelConfigPage(CloudModelPreset(
                        provider,
                        "${provider} Custom",
                        "model-id",
                        base?.endpoint ?: "https://api.example.com/v1/chat/completions",
                        base?.apiStyle ?: "openai"
                    ))
                }
            })
        }
    }

    private fun debugSeedCloudProvider(provider: String): Contact? {
        if ((applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) == 0) return null
        val normalizedProvider = provider.trim().ifBlank { "DeepSeek" }
        val presets = modelsForProvider(normalizedProvider).ifEmpty { modelsForProvider("DeepSeek") }
        if (presets.isEmpty()) return null
        var raw: JSONObject? = null
        presets.take(2).forEach { preset ->
            raw = AppStore.addCloudModelContact(
                this,
                preset.name,
                preset.provider,
                preset.modelId,
                preset.endpoint,
                "sk-signalasi-smoke-key",
                preset.apiStyle
            )
        }
        val contact = raw ?: return null
        refreshContactList()
        refreshDirectoryContacts()
        return Contact(contact.getString("id"), contact.optString("name", normalizedProvider), "")
    }

    private fun showCloudModelConfigPage(preset: CloudModelPreset) {
        showFeaturePage(getString(R.string.cloud_config_title))
        setFeatureBackAction { showCloudModelPage(preset.provider) }
        featureContent.addView(featureHeroCard(
            preset.name,
            "${preset.provider} · ${preset.apiStyle}",
            providerIcon(preset.provider),
            providerColor(preset.provider),
            "API"
        ))
        addSectionTitle(getString(R.string.cloud_section_contact))
        val nameInput = cloudModelInput(getString(R.string.cloud_contact_name), preset.name)
        addSectionTitle("Provider")
        featureContent.addView(featureValueRow("Provider", "", providerIcon(preset.provider), preset.provider))
        addSectionTitle(getString(R.string.cloud_section_model))
        val modelInput = cloudModelInput(getString(R.string.cloud_model_id), preset.modelId)
        val endpointInput = cloudModelInput("API Endpoint", preset.endpoint)
        addSectionTitle(getString(R.string.cloud_section_key))
        val keyInput = cloudModelInput("API Key", "", password = true)
        featureContent.addView(TextView(this).apply {
            text = getString(R.string.cloud_save_start_chat)
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            textSize = 17f
            background = getDrawable(R.drawable.send_button_background)
            setOnClickListener {
                val apiKey = keyInput.text?.toString()?.trim().orEmpty()
                val modelId = modelInput.text?.toString()?.trim().orEmpty()
                val endpoint = endpointInput.text?.toString()?.trim().orEmpty()
                if (!CloudModelCredentialPolicy.isStoredCredential(apiKey) || modelId.isBlank() || endpoint.isBlank()) {
                    Toast.makeText(this@MainActivity, getString(R.string.cloud_required_fields), Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                val raw = AppStore.addCloudModelContact(
                    this@MainActivity,
                    nameInput.text?.toString()?.trim().orEmpty(),
                    preset.provider,
                    modelId,
                    endpoint,
                    apiKey,
                    preset.apiStyle
                )
                val contact = Contact(raw.getString("id"), raw.optString("name", modelId), "")
                Toast.makeText(this@MainActivity, getString(R.string.cloud_added_model, preset.name), Toast.LENGTH_SHORT).show()
                refreshContactList()
                refreshDirectoryContacts()
                showChatPage(contact)
            }
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(46)).apply { topMargin = dp(20) })
    }

    private fun showCloudModelSwitchPage(contact: Contact) {
        val raw = AppStore.contactById(this, contact.id) ?: return
        val provider = raw.optString("cloud_provider", contact.name)
        val selected = AppStore.selectedCloudModelId(this, contact.id)
        showFeaturePage(getString(R.string.cloud_switch_model_title))
        setFeatureBackAction { showChatPage(contact) }
        addSectionTitle(getString(R.string.cloud_section_model))
        val models = AppStore.cloudModels(this, contact.id)
        val modelRows = LinkedHashMap<String, JSONObject>()
        modelsForProvider(provider).forEach { preset ->
            modelRows[preset.modelId] = JSONObject()
                .put("name", preset.name)
                .put("model_id", preset.modelId)
                .put("endpoint", preset.endpoint)
                .put("api_style", preset.apiStyle)
                .put("api_key", raw.optString("cloud_api_key"))
        }
        for (i in 0 until models.length()) {
            val model = models.optJSONObject(i) ?: continue
            val modelId = model.optString("model_id")
            if (modelId.isNotBlank()) modelRows[modelId] = model
        }
        if (modelRows.isEmpty()) {
            featureContent.addView(featureRow(getString(R.string.cloud_no_models), getString(R.string.cloud_no_models_subtitle), R.drawable.ic_protocol_link, getString(R.string.add_contact_title)).apply {
                setOnClickListener { showCloudModelPage(provider) }
            })
        } else {
            modelRows.values.forEach { model ->
                val modelId = model.optString("model_id")
                val modelName = model.optString("name", modelId)
                val isSelected = modelId == selected
                featureContent.addView(modelSwitchRow(modelName, if (isSelected) getString(R.string.section_current) else getString(R.string.common_select), isSelected).apply {
                    setOnClickListener {
                        val switched = AppStore.setSelectedCloudModel(this@MainActivity, contact.id, modelId)
                        if (!switched) {
                            val endpoint = model.optString("endpoint").ifBlank { raw.optString("cloud_endpoint") }
                            val apiKey = model.optString("api_key").ifBlank { raw.optString("cloud_api_key") }
                            val apiStyle = model.optString("api_style").ifBlank { raw.optString("cloud_api_style", "openai") }
                            if (endpoint.isBlank() || apiKey.isBlank()) {
                                Toast.makeText(this@MainActivity, getString(R.string.cloud_configure_api_key_first, provider), Toast.LENGTH_SHORT).show()
                                return@setOnClickListener
                            }
                            AppStore.addCloudModelContact(this@MainActivity, modelName, provider, modelId, endpoint, apiKey, apiStyle)
                            AppStore.setSelectedCloudModel(this@MainActivity, contact.id, modelId)
                        }
                        Toast.makeText(this@MainActivity, getString(R.string.cloud_switched_model, modelName), Toast.LENGTH_SHORT).show()
                        showChatPage(contact)
                    }
                })
            }
        }
    }

    private fun cloudModelInput(label: String, value: String = "", password: Boolean = false): EditText {
        featureContent.addView(TextView(this).apply {
            text = label
            textSize = 13f
            setTextColor(getColorCompat(R.color.text_secondary))
            setPadding(dp(4), dp(10), dp(4), dp(5))
        })
        return EditText(this).apply {
            setSingleLine(true)
            setText(value)
            textSize = 15f
            setPadding(dp(14), 0, dp(14), 0)
            setBackgroundResource(R.drawable.message_input_background)
            inputType = if (password) {
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            } else {
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            }
            featureContent.addView(this, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(48)).apply {
                bottomMargin = dp(8)
            })
        }
    }

    private fun cloudProviders(): List<String> = CLOUD_MODEL_PRESETS.map { it.provider }.distinct()

    private fun modelsForProvider(provider: String): List<CloudModelPreset> =
        CLOUD_MODEL_PRESETS.filter { it.provider == provider }

    private fun providerSubtitle(provider: String): String = when (provider) {
        "OpenAI" -> getString(R.string.cloud_provider_openai_subtitle)
        "Anthropic" -> getString(R.string.cloud_provider_anthropic_subtitle)
        "Google Gemini" -> getString(R.string.cloud_provider_gemini_subtitle)
        "DeepSeek" -> getString(R.string.cloud_provider_deepseek_subtitle)
        "Qwen" -> getString(R.string.cloud_provider_qwen_subtitle)
        "OpenRouter" -> getString(R.string.cloud_provider_openrouter_subtitle)
        else -> getString(R.string.cloud_provider_custom_subtitle)
    }

    private fun providerIcon(provider: String): Int = cloudProviderLogoRes(provider)

    private fun providerColor(provider: String): String = when (provider) {
        "OpenAI" -> "#14C66A"
        "Anthropic" -> "#FF6B5F"
        "Google Gemini" -> "#5B6CFF"
        "DeepSeek" -> "#3F84FF"
        "Qwen" -> "#00A7A7"
        "OpenRouter" -> "#7C5CFF"
        else -> "#6C7A89"
    }

    private fun providerTitleWithModelTag(provider: String): SpannableString {
        val suffix = "  ● ${getString(R.string.cloud_model_tag)}"
        return SpannableString(provider + suffix).apply {
            val dotStart = provider.length + 2
            setSpan(RelativeSizeSpan(0.61f), dotStart, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            setSpan(BaselineShiftSpan(dp(1)), dotStart, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            setSpan(ForegroundColorSpan(getColorCompat(R.color.wechat_green)), dotStart, dotStart + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            setSpan(ForegroundColorSpan(getColorCompat(R.color.text_secondary)), dotStart + 2, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
    }

    private fun modelDisplayLabel(modelId: String): String {
        if (modelId.isBlank()) return getString(R.string.cloud_select_model)
        val lower = modelId.lowercase(Locale.getDefault())
        if (lower.startsWith("gpt-")) return "GPT-" + modelId.substringAfter("-").replace("-", " ")
        if (lower.startsWith("deepseek-")) return "DeepSeek " + modelId.substringAfter("-")
            .split("-", "_", "/")
            .filter { it.isNotBlank() }
            .joinToString(" ") { token ->
                when (token.lowercase(Locale.getDefault())) {
                    "v4" -> "V4"
                    "v5" -> "V5"
                    else -> token.replaceFirstChar { c -> c.titlecase(Locale.getDefault()) }
                }
            }
        return modelId.split("-", "_", "/")
            .filter { it.isNotBlank() }
            .joinToString(" ") { token ->
                when (token.lowercase(Locale.getDefault())) {
                    "gpt" -> "GPT"
                    "claude" -> "Claude"
                    "deepseek" -> "DeepSeek"
                    "gemini" -> "Gemini"
                    "qwen" -> "Qwen"
                    "v4" -> "V4"
                    "v5" -> "V5"
                    else -> token.replaceFirstChar { c -> c.titlecase(Locale.getDefault()) }
                }
            }
    }

    private fun selectedCloudModelLabel(contactId: String): String {
        val selectedId = AppStore.selectedCloudModelId(this, contactId)
        if (selectedId.isBlank()) return getString(R.string.cloud_select_model)
        val models = AppStore.cloudModels(this, contactId)
        for (i in 0 until models.length()) {
            val model = models.optJSONObject(i) ?: continue
            if (model.optString("model_id") == selectedId) {
                return model.optString("name").ifBlank { modelDisplayLabel(selectedId) }
            }
        }
        return CLOUD_MODEL_PRESETS.firstOrNull { it.modelId == selectedId }?.name ?: modelDisplayLabel(selectedId)
    }

    private fun modelSelectorLabel(label: String): SpannableString {
        return SpannableString("$label  ⌄").apply {
            val arrowStart = label.length + 2
            setSpan(
                ForegroundColorSpan(getColorCompat(R.color.text_secondary)),
                arrowStart,
                length,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
            setSpan(RelativeSizeSpan(1.3f), arrowStart, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            setSpan(BaselineShiftSpan(dp(2)), arrowStart, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
    }

    private fun shortModelLabel(modelId: String): String {
        if (modelId.isBlank()) return getString(R.string.cloud_model_tag)
        return if (modelId.length <= 12) modelId else modelId.take(10) + "..."
    }
    // ===== Security / Scan =====
    private fun startSecurityScan() {
        IntentIntegrator(this).apply {
            setDesiredBarcodeFormats("QR_CODE")
            setOrientationLocked(false)
            initiateScan()
        }
    }

    private fun handleSecurityScan(contents: String?, autoConfirm: Boolean = false) {
        if (contents.isNullOrBlank()) return
        try {
            val json = JSONObject(contents)
            if (json.optString("type") == "signalasi_verify") {
                if (SignalASILinkProtocol.validatePairingQr(json) && SignalASICrypto.verifyPcIdentityFromQr(contents)) {
                    if (autoConfirm) {
                        completeDesktopPairing(json)
                    } else {
                        showDesktopPairingConfirmPage(json)
                    }
                } else {
                    Toast.makeText(this, getString(R.string.pairing_invalid_identity_qr), Toast.LENGTH_LONG).show()
                }
            } else if (json.has("signalasi_id") || json.has("hermes_id")) {
                AppStore.importContactQrAsRequest(this, contents)
                Toast.makeText(this, getString(R.string.pairing_contact_request_received, json.optString("name", "")), Toast.LENGTH_LONG).show()
                refreshDirectoryContacts()
            } else {
                Toast.makeText(this, getString(R.string.pairing_invalid_qr), Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            Toast.makeText(this, getString(R.string.pairing_scan_failed, e.message.orEmpty()), Toast.LENGTH_SHORT).show()
        }
    }

    private fun showDesktopPairingConfirmPage(pairingQr: JSONObject) {
        val desktopName = pairingQr.optString("desktop_name").ifBlank { "PC" }
        val desktopId = pairingQr.optString("desktop_id").ifBlank {
            "desktop_${pairingQr.optString("identity_key_sha256").take(16)}"
        }
        val desktopFingerprint = pairingQr.optString("identity_key_sha256")
            .ifBlank { pairingQr.optString("identity_fingerprint") }
        showFeaturePage(getString(R.string.pairing_confirm_title))
        featureContent.addView(featureHeroCard(desktopName, getString(R.string.pairing_confirm_subtitle), R.drawable.ic_security_shield, "#14C66A", getString(R.string.pairing_pending_confirm)))
        addSectionTitle(getString(R.string.pairing_section_device))
        featureContent.addView(featureRow("Desktop ID", desktopId, R.drawable.ic_device_node, getString(R.string.common_copy)).apply {
            setOnClickListener { copyText(desktopId, getString(R.string.security_copied_desktop_id)) }
        })
        addSectionTitle(getString(R.string.pairing_section_fingerprints))
        featureContent.addView(featureRow(getString(R.string.security_phone_fingerprint), formatFingerprint(SignalASICrypto.localIdentitySha256()), R.drawable.ic_security_shield, getString(R.string.common_copy)).apply {
            setOnClickListener { copyText(SignalASICrypto.localIdentitySha256(), getString(R.string.security_copied_phone_fingerprint)) }
        })
        featureContent.addView(featureRow(getString(R.string.security_desktop_fingerprint), formatFingerprint(desktopFingerprint), R.drawable.ic_security_shield, getString(R.string.common_copy)).apply {
            setOnClickListener { copyText(desktopFingerprint, getString(R.string.security_copied_desktop_fingerprint)) }
        })
        addSectionTitle(getString(R.string.pairing_section_after_confirm))
        featureContent.addView(featureRow(getString(R.string.pairing_save_trust), getString(R.string.pairing_save_trust_subtitle), R.drawable.ic_protocol_link, getString(R.string.status_enabled)))
        featureContent.addView(TextView(this).apply {
            text = getString(R.string.pairing_confirm_title)
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            textSize = 17f
            background = getDrawable(R.drawable.send_button_background)
            setOnClickListener { completeDesktopPairing(pairingQr) }
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(46)).apply {
            topMargin = dp(18)
        })
        featureContent.addView(TextView(this).apply {
            text = getString(R.string.common_cancel)
            gravity = Gravity.CENTER
            setTextColor(getColorCompat(R.color.text_secondary))
            textSize = 16f
            setOnClickListener { hideFeaturePage() }
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(44)).apply {
            topMargin = dp(8)
        })
    }

    private fun completeDesktopPairing(pairingQr: JSONObject) {
        if (!SignalASIMqttClient.publishPairingClaim(pairingQr)) {
            Toast.makeText(this, getString(R.string.pairing_scan_failed, "SignalASI Link is offline"), Toast.LENGTH_LONG).show()
            return
        }
        AppStore.markDesktopVerified(this, pairingQr)
        Toast.makeText(this, getString(R.string.pairing_desktop_added, pairingQr.optString("desktop_name", "PC")), Toast.LENGTH_LONG).show()
        refreshContactList()
        refreshDirectoryContacts()
        showMainTab(PAGE_CONTACTS)
    }

    private fun copyText(value: String, toast: String) {
        getSystemService(ClipboardManager::class.java)
            .setPrimaryClip(ClipData.newPlainText("SignalASI", value))
        Toast.makeText(this, toast, Toast.LENGTH_SHORT).show()
    }

    private fun showMyQrPayload() {
        val payload = JSONObject().apply {
            put("signalasi_id", SignalASICrypto.localSignalasiId())
            put("name", AppStore.profile(this@MainActivity).optString("name"))
            put("identity_fingerprint", SignalASICrypto.localIdentitySha256())
            put("identity_public_key", SignalASICrypto.localIdentityPublicKey())
        }.toString()
        val qrCodeBitmap = qrBitmap(payload, 320)
        showFeaturePage(getString(R.string.contact_my_qr_title))
        featureContent.gravity = Gravity.CENTER_HORIZONTAL
        featureContent.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(20), dp(24), dp(20), dp(24))
            background = getDrawable(R.drawable.glass_card_background)
            addView(ImageView(this@MainActivity).apply {
                setImageBitmap(qrCodeBitmap)
                setBackgroundColor(Color.WHITE)
                setPadding(dp(10), dp(10), dp(10), dp(10))
            }, LinearLayout.LayoutParams(dp(260), dp(260)))
            addView(TextView(this@MainActivity).apply {
                text = AppStore.profile(this@MainActivity).optString("name", getString(R.string.app_name))
                gravity = Gravity.CENTER
                setTextColor(getColorCompat(R.color.text_primary))
                textSize = 18f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                setPadding(0, dp(18), 0, dp(4))
            })
            addView(TextView(this@MainActivity).apply {
                text = SignalASICrypto.localSignalasiId()
                gravity = Gravity.CENTER
                setTextColor(getColorCompat(R.color.text_secondary))
                textSize = 12f
            })
        }, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(8) })
        addSectionTitle(getString(R.string.contact_my_fingerprint))
        featureContent.addView(featureRow(formatFingerprint(SignalASICrypto.localIdentitySha256()), getString(R.string.contact_scan_confirm_identity), R.drawable.ic_security_shield, getString(R.string.common_copy)))
        featureContent.gravity = Gravity.NO_GRAVITY
    }

    private fun qrBitmap(content: String, size: Int): Bitmap {
        val matrix = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, size, size)
        return Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888).apply {
            for (x in 0 until size) {
                for (y in 0 until size) {
                    setPixel(x, y, if (matrix[x, y]) Color.BLACK else Color.WHITE)
                }
            }
        }
    }

    // ===== Contact Detail Page =====
    private fun showContactDetail(contact: Contact) {
        val raw = AppStore.contactById(this, contact.id)
        val storedIdentity = raw?.optString("identity_fingerprint").orEmpty()
        val identity = if (storedIdentity.isNotBlank()) {
            storedIdentity
        } else if (contact.id == CONTACT_HERMES.id) {
            runCatching { SignalASICrypto.verifiedPcFingerprint() }.getOrDefault("")
        } else {
            ""
        }
        val id = jsonSignalasiId(raw, contact.id)
        showFeaturePage(getString(R.string.contact_detail_title))
        featureContent.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(20), dp(22), dp(20), dp(22))
            background = getDrawable(R.drawable.glass_card_background)
            addView(ImageView(this@MainActivity).apply {
                setImageResource(contactAvatarRes(contact))
                scaleType = ImageView.ScaleType.CENTER_CROP
                setBackgroundResource(R.drawable.rounded_avatar_bg)
                clipToOutline = true
            }, LinearLayout.LayoutParams(dp(72), dp(72)).apply { bottomMargin = dp(12) })
            addView(TextView(this@MainActivity).apply {
                text = contact.name
                gravity = Gravity.CENTER
                setTextColor(getColorCompat(R.color.text_primary))
                textSize = 22f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
            })
            addView(TextView(this@MainActivity).apply {
                text = id
                gravity = Gravity.CENTER
                setTextColor(getColorCompat(R.color.text_secondary))
                textSize = 12f
                setPadding(0, dp(5), 0, 0)
            })
        }, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = dp(14) })
        addSectionTitle(getString(R.string.contact_section_identity))
        featureContent.addView(featureRow(getString(R.string.contact_remark_name), contact.name, R.drawable.ic_protocol_link, getString(R.string.common_edit)).apply {
            setOnClickListener { showEditContactNamePage(contact) }
        })
        featureContent.addView(featureRow(getString(R.string.settings_signalasi_id), id, R.drawable.ic_protocol_link, getString(R.string.common_copy)))
        featureContent.addView(featureRow(getString(R.string.settings_fingerprint), formatFingerprint(identity).ifBlank { getString(R.string.contact_fingerprint_unverified) }, R.drawable.ic_security_shield, getString(R.string.common_copy)))
        if (raw?.optString("delivery_mode") == "pc_connector") {
            val setupStatus = when (raw.optString("setup_status")) {
                "ready" -> getString(R.string.common_ready)
                "needs_setup" -> getString(R.string.common_needs_setup)
                else -> getString(R.string.common_paired)
            }
            addSectionTitle(getString(R.string.contact_connector_section))
            featureContent.addView(featureRow(getString(R.string.common_status), raw.optString("setup_detail").ifBlank { setupStatus }, R.drawable.ic_agent_node, setupStatus))
            raw.optString("setup_next_step").takeIf { it.isNotBlank() }?.let { next ->
                featureContent.addView(featureRow(getString(R.string.common_next_step), next, R.drawable.ic_protocol_link, getString(R.string.common_view)))
            }
        }
        featureContent.addView(TextView(this).apply {
            text = getString(R.string.contact_send_message)
            gravity = Gravity.CENTER
            setTextColor(android.graphics.Color.WHITE)
            textSize = 17f
            setTypeface(typeface, android.graphics.Typeface.NORMAL)
            background = getDrawable(R.drawable.send_button_background)
            setOnClickListener {
                showChatPage(contact)
            }
        }, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            dp(46)
        ).apply {
            topMargin = dp(20)
        })
    }

    private fun showEditContactNamePage(contact: Contact) {
        showFeaturePage(getString(R.string.contact_edit_remark_title))
        featureContent.addView(featureHeroCard(contact.name, getString(R.string.contact_edit_remark_subtitle), contactAvatarRes(contact), "#14C66A", getString(R.string.common_save)))
        addSectionTitle(getString(R.string.contact_remark_name))
        val input = EditText(this).apply {
            setSingleLine(true)
            setText(contact.name)
            selectAll()
            setBackgroundResource(R.drawable.message_input_background)
            setPadding(dp(14), dp(10), dp(14), dp(10))
            textSize = 16f
        }
        featureContent.addView(input, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(48)).apply {
            bottomMargin = dp(18)
        })
        featureContent.addView(TextView(this).apply {
            text = getString(R.string.common_save)
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            textSize = 17f
            background = getDrawable(R.drawable.send_button_background)
            setOnClickListener {
                val newName = input.text?.toString().orEmpty().trim()
                if (newName.isBlank()) {
                    Toast.makeText(this@MainActivity, getString(R.string.contact_remark_required), Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                AppStore.renameContact(this@MainActivity, contact.id, newName)
                refreshContactList()
                refreshDirectoryContacts()
                Toast.makeText(this@MainActivity, getString(R.string.contact_remark_saved), Toast.LENGTH_SHORT).show()
                showContactDetail(contactById(contact.id))
            }
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(46)))
    }

    // ===== Settings / Profile =====
    private fun refreshMePage() {
        val currentProfile = AppStore.profile(this)
        val name = currentProfile.optString("name", "Me")
        val savedFingerprint = currentProfile.optString("identity_fingerprint", "")
        val fingerprint = if (savedFingerprint.filter { it.isLetterOrDigit() }.length >= 64) {
            savedFingerprint
        } else {
            SignalASICrypto.localIdentitySha256()
        }
        meProfileText.text = if (name == "Me") getString(R.string.settings_profile_me) else name
        meProfileText.textSize = 17f
        meProfileText.setTypeface(meProfileText.typeface, android.graphics.Typeface.BOLD)
        meProfileText.gravity = Gravity.CENTER_VERTICAL
        meIdSubtitleText.text = "${getString(R.string.settings_signalasi_id)}: ${currentProfile.optString("signalasi_id", "").takeLast(8).ifBlank { getString(R.string.profile_id_unavailable) }}"
        meIdText.text = formatFingerprint(fingerprint).ifBlank { currentProfile.optString("signalasi_id", "") }
        meAvatar.setImageResource(R.drawable.ic_avatar_profile)
        meAvatar.scaleType = ImageView.ScaleType.CENTER_CROP
        val savedAvatar = AppStore.profile(this).optString("avatar_uri", "")
        if (savedAvatar.isNotBlank()) {
            try { meAvatar.setImageURI(Uri.parse(savedAvatar)) } catch (_: Exception) {}
        }
        refreshSettingsControlCenter()
    }

    private fun showEditNicknameDialog() {
        val profile = AppStore.profile(this)
        val name = profile.optString("name", getString(R.string.settings_profile_me))
        showFeaturePage(getString(R.string.profile_nickname_title))
        featureContent.addView(featureHeroCard(getString(R.string.profile_nickname_settings), getString(R.string.profile_nickname_subtitle), R.drawable.ic_avatar_profile, "#24292F", getString(R.string.common_save)))
        addSectionTitle(getString(R.string.profile_nickname_title))
        val input = EditText(this).apply {
            setSingleLine(true)
            setText(name)
            selectAll()
            setBackgroundResource(R.drawable.message_input_background)
            setPadding(dp(14), dp(10), dp(14), dp(10))
            textSize = 16f
        }
        featureContent.addView(input, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(48)).apply {
            bottomMargin = dp(18)
        })
        featureContent.addView(TextView(this).apply {
            text = getString(R.string.common_save)
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            textSize = 17f
            background = getDrawable(R.drawable.send_button_background)
            setOnClickListener {
                val newName = input.text?.toString().orEmpty().trim()
                if (newName.isBlank()) {
                    Toast.makeText(this@MainActivity, getString(R.string.profile_nickname_required), Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                AppStore.updateProfileName(this@MainActivity, newName)
                refreshMePage()
                notifyContactsProfileUpdated()
                Toast.makeText(this@MainActivity, getString(R.string.profile_nickname_saved_notified), Toast.LENGTH_SHORT).show()
                hideFeaturePage()
            }
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(46)))
    }

    private fun notifyContactsProfileUpdated() {
        val contacts = AppStore.contacts(this)
        for (i in 0 until contacts.length()) {
            val contact = contacts.optJSONObject(i) ?: continue
            if (contact.optBoolean("deleted", false)) continue
            if (contact.optString("type") != "person") continue
            if (contact.optString("trust_state") != "verified") continue
            if (contact.optString("signal_session") != "ready") continue
            val contactId = contact.optString("id").ifBlank { jsonSignalasiId(contact) }
            if (contactId.isNotBlank()) SignalASIMqttClient.publishProfileUpdate(contactId)
        }
    }

    private fun showFriendRequestsDialog() {
        val requests = AppStore.friendRequests(this)
        val pending = (0 until requests.length())
            .mapNotNull { requests.optJSONObject(it) }
            .filter { it.optString("status") == "pending" }
        showFeaturePage(getString(R.string.new_friends))
        if (pending.isEmpty()) {
            featureContent.addView(featureHeroCard(getString(R.string.friend_request_empty_title), getString(R.string.friend_request_empty_subtitle), R.drawable.ic_avatar_group, "#8E8E93", getString(R.string.common_empty)))
            return
        }
        if (pending.size == 1) {
            showFriendRequestDetail(pending.first())
            return
        }
        addSectionTitle(getString(R.string.friend_request_pending))
        pending.forEach { request ->
            featureContent.addView(featureRow(
                request.optString("name", "Friend"),
                jsonSignalasiId(request),
                R.drawable.ic_avatar_group,
                getString(R.string.friend_request_view)
            ).apply {
                setOnClickListener { showFriendRequestDetail(request) }
            })
        }
    }

    private fun showFriendRequestDetail(request: JSONObject) {
        showFeaturePage(request.optString("name", "Friend"))
        featureContent.addView(featureHeroCard(
            request.optString("name", "Friend"),
            jsonSignalasiId(request),
            R.drawable.ic_avatar_group,
            "#14C66A",
            getString(R.string.friend_request_pending)
        ))
        addSectionTitle(getString(R.string.contact_section_identity))
        featureContent.addView(featureRow(getString(R.string.settings_signalasi_id), jsonSignalasiId(request), R.drawable.ic_protocol_link, getString(R.string.common_copy)))
        featureContent.addView(featureRow(getString(R.string.settings_fingerprint), formatFingerprint(request.optString("identity_fingerprint")), R.drawable.ic_security_shield, getString(R.string.common_copy)))
        featureContent.addView(TextView(this).apply {
            text = getString(R.string.friend_request_approve)
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            textSize = 17f
            background = getDrawable(R.drawable.send_button_background)
            setOnClickListener {
                AppStore.approveFriendRequest(this@MainActivity, request.optString("id"))
                refreshDirectoryContacts()
                Toast.makeText(this@MainActivity, getString(R.string.friend_request_added), Toast.LENGTH_SHORT).show()
                hideFeaturePage()
            }
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(46)).apply {
            topMargin = dp(16)
        })
        featureContent.addView(TextView(this).apply {
            text = getString(R.string.friend_request_reject)
            gravity = Gravity.CENTER
            setTextColor(Color.parseColor("#FF3B30"))
            textSize = 16f
            setOnClickListener {
                AppStore.rejectFriendRequest(this@MainActivity, request.optString("id"))
                Toast.makeText(this@MainActivity, getString(R.string.common_rejected), Toast.LENGTH_SHORT).show()
                hideFeaturePage()
            }
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(48)).apply {
            topMargin = dp(8)
        })
    }

    private fun showCreateGroupDialogV2() {
        showCreateGroupFeaturePage()
    }

    private fun showCreateGroupDialog() {
        showCreateGroupDialogV2()
    }

    private fun showGroupFeaturePage() {
        showFeaturePage(getString(R.string.group_feature_title))
        featureContent.addView(featureHeroCard(getString(R.string.group_feature_title), getString(R.string.group_feature_subtitle), R.drawable.ic_avatar_group, "#5B6CFF", getString(R.string.badge_planned)))
        addSectionTitle(getString(R.string.section_current))
        featureContent.addView(featureRow(getString(R.string.discover_create_group).substringBefore("\n"), getString(R.string.discover_create_group).substringAfter("\n"), R.drawable.ic_group, ""))
        addSectionTitle(getString(R.string.section_capabilities))
        featureContent.addView(featureRow(getString(R.string.group_member_verification), getString(R.string.group_member_verification_subtitle), R.drawable.ic_security_shield, getString(R.string.badge_designing)))
        featureContent.addView(featureRow(getString(R.string.group_message_encryption), getString(R.string.group_message_encryption_subtitle), R.drawable.ic_protocol_link, getString(R.string.badge_designing)))
        featureContent.addView(TextView(this).apply {
            text = getString(R.string.discover_create_group).substringBefore("\n")
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            textSize = 17f
            background = getDrawable(R.drawable.send_button_background)
            setOnClickListener { showCreateGroupFeaturePage() }
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(46)).apply {
            topMargin = dp(18)
        })
    }

    private fun showCreateGroupFeaturePage() {
        val createGroupTitle = getString(R.string.discover_create_group).substringBefore("\n")
        showFeaturePage(createGroupTitle)
        featureContent.addView(featureHeroCard(createGroupTitle, getString(R.string.group_create_subtitle), R.drawable.ic_avatar_group, "#5B6CFF", getString(R.string.badge_unavailable)))
        addSectionTitle(getString(R.string.section_flow))
        featureContent.addView(featureRow(getString(R.string.group_select_members), getString(R.string.group_select_members_subtitle), R.drawable.ic_avatar_group, getString(R.string.common_next_step)))
        featureContent.addView(featureRow(getString(R.string.group_member_verification), getString(R.string.group_member_verification_subtitle), R.drawable.ic_security_shield, getString(R.string.badge_designing)))
        featureContent.addView(featureRow(getString(R.string.group_create_session), getString(R.string.group_create_session_subtitle), R.drawable.ic_protocol_link, getString(R.string.badge_designing)))
        addSectionTitle(getString(R.string.section_status))
        featureContent.addView(featureRow(getString(R.string.group_feature_status), getString(R.string.group_feature_status_subtitle), R.drawable.ic_group, getString(R.string.badge_planned)))
    }

    private fun showAgentFeaturePage() {
        fun rawContact(id: String): JSONObject? = AppStore.contactById(this, id)
        fun connected(id: String): Boolean {
            val raw = rawContact(id) ?: return false
            return !raw.optBoolean("deleted", false) && raw.optString("trust_state") != "deleted"
        }
        fun status(id: String): String = rawContact(id)?.optString("setup_status")?.takeIf { it.isNotBlank() } ?: "unknown"
        fun badge(id: String, fallback: String): String = when (status(id)) {
            "ready" -> getString(R.string.status_ready)
            "needs_setup" -> getString(R.string.status_needs_setup)
            else -> if (connected(id)) fallback else getString(R.string.status_pending_connection)
        }
        fun color(id: String, fallback: String): String = when (status(id)) {
            "ready" -> "#14C66A"
            "needs_setup" -> "#F0A500"
            else -> fallback
        }
        fun detail(id: String, fallback: String): String {
            val raw = rawContact(id) ?: return fallback
            return raw.optString("setup_detail").ifBlank {
                raw.optString("setup_next_step").ifBlank { fallback }
            }
        }
        fun agentName(id: String, fallback: String): String {
            return rawContact(id)?.optString("name")?.takeIf { it.isNotBlank() } ?: fallback
        }
        val fixedAgentIds = setOf("hermes", "codex", "claude", "openclaw", "local-llm", "custom-agent")
        val coreAgents = listOf(
            AgentUi("hermes", "Hermes", getString(R.string.agent_private_assistant_subtitle), R.drawable.ic_avatar_hermes, if (connected("hermes")) getString(R.string.status_running) else getString(R.string.status_pending_pairing), "#14C66A", connected("hermes")),
            AgentUi("codex", agentName("codex", "Codex"), detail("codex", getString(R.string.agent_codex_subtitle)), R.drawable.logo_codex_product, badge("codex", getString(R.string.common_paired)), color("codex", "#5B6CFF"), connected("codex")),
            AgentUi("claude", agentName("claude", "Claude Code"), detail("claude", getString(R.string.agent_claude_subtitle)), R.drawable.logo_claude_code, badge("claude", getString(R.string.common_paired)), color("claude", "#FF6B5F"), connected("claude")),
            AgentUi("openclaw", agentName("openclaw", "OpenClaw"), detail("openclaw", getString(R.string.agent_openclaw_subtitle)), R.drawable.ic_avatar_custom_agent, badge("openclaw", getString(R.string.common_paired)), color("openclaw", "#2878FF"), connected("openclaw")),
            AgentUi("local-llm", agentName("local-llm", "Local LLM"), detail("local-llm", getString(R.string.agent_local_llm_subtitle)), R.drawable.ic_avatar_custom_agent, badge("local-llm", getString(R.string.common_paired)), color("local-llm", "#00A7A7"), connected("local-llm")),
            AgentUi("custom-agent", agentName("custom-agent", "Custom Agent"), detail("custom-agent", getString(R.string.agent_custom_subtitle)), R.drawable.ic_avatar_custom_agent, badge("custom-agent", getString(R.string.common_paired)), color("custom-agent", "#6C7A89"), connected("custom-agent"))
        )
        val agents = coreAgents + dynamicConnectorAgents(fixedAgentIds) + listOf(
            AgentUi("news_agent", "News Agent", getString(R.string.agent_news_subtitle), R.drawable.ic_agent_node, getString(R.string.badge_automation), "#F0A500", connected("news_agent")),
            AgentUi("home_hub", "Home Agent", getString(R.string.agent_home_subtitle), R.drawable.ic_device_node, getString(R.string.badge_device), "#6C7A89", connected("home_hub"))
        )
        showFeaturePage("AI Agent")
        addSegmentTabs(listOf(getString(R.string.discover_segment_all), getString(R.string.discover_segment_local), getString(R.string.discover_segment_official), getString(R.string.discover_segment_running)))
        featureContent.addView(featureRow(getString(R.string.discover_add_cloud_model), getString(R.string.discover_add_cloud_model_subtitle), R.drawable.ic_avatar_cloud_model, "+").apply {
            setOnClickListener { showCloudProviderPage() }
        })
        agents.forEach { agent ->
            featureContent.addView(agentFeatureRow(agent))
        }
    }

    private fun showLocalModelFeaturePage() {
        showFeaturePage(getString(R.string.local_model_title))
        featureContent.addView(localModelStatusCard())
        addSectionTitle(getString(R.string.local_model_section_manage))
        featureContent.addView(featureValueRow(getString(R.string.local_model_select), "", R.drawable.ic_local_model, "Qwen 7B (4bit)"))
        featureContent.addView(featureValueRow(getString(R.string.local_model_vision), "", R.drawable.ic_import, ""))
        addSectionTitle(getString(R.string.local_model_section_inference))
        featureContent.addView(featureSwitchRow(getString(R.string.local_model_enable), getString(R.string.local_model_enable_subtitle), R.drawable.ic_protocol_link, true))
        addSectionTitle(getString(R.string.local_model_section_permissions))
        featureContent.addView(featureValueRow(getString(R.string.on_device_agent_microphone), "", R.drawable.ic_agent_node, getString(R.string.permission_allowed)))
        featureContent.addView(featureValueRow(getString(R.string.on_device_agent_camera), "", R.drawable.ic_scan, getString(R.string.permission_allowed)))
        featureContent.addView(featureValueRow(getString(R.string.local_model_location), "", R.drawable.ic_device_node, getString(R.string.permission_while_using_allowed)))
        featureContent.addView(featureValueRow(getString(R.string.local_model_notification_permission), "", R.drawable.ic_agent_node, getString(R.string.permission_allowed)))
        addSectionTitle(getString(R.string.local_model_section_privacy_storage))
        featureContent.addView(featureSwitchRow(getString(R.string.local_model_offline_mode), getString(R.string.local_model_offline_mode_subtitle), R.drawable.ic_security_shield, true))
        featureContent.addView(featureStorageRow())
    }

    private fun showDeviceFeaturePage() {
        val homeAssistant = HomeAssistantSettingsStore.load(this)
        val customDevices = CustomDeviceConnectorStore(this).list()
        val pairedDesktopCount = desktopSecuritySummaries(activePcConnectorContacts()).size
        val desktopOnline = pairedDesktopCount > 0 && SignalASIMqttClient.isConnected()
        val visibleDeviceCount = 1 + customDevices.size +
            (if (pairedDesktopCount > 0) pairedDesktopCount else 0) +
            (if (homeAssistant.configured) 1 else 0)
        showFeaturePage(getString(R.string.device_management_title))
        featureContent.addView(featureHeroCard(getString(R.string.device_management_title), getString(R.string.device_management_subtitle), R.drawable.ic_device_node, "#5B6CFF", getString(R.string.count_devices, visibleDeviceCount)))
        addSectionTitle(getString(R.string.section_my_devices))
        featureContent.addView(featureRow("Phone Agent", getString(R.string.device_phone_agent_subtitle), R.drawable.ic_device_node, getString(R.string.status_online)))
        featureContent.addView(featureRow(
            "PC Agent",
            getString(R.string.device_pc_agent_subtitle),
            R.drawable.ic_device_node,
            getString(
                when {
                    desktopOnline -> R.string.status_online
                    pairedDesktopCount > 0 -> R.string.status_disconnected
                    else -> R.string.status_needs_setup
                }
            )
        ))
        featureContent.addView(featureRow(
            getString(R.string.device_home_assistant),
            getString(R.string.device_home_assistant_subtitle),
            R.drawable.ic_device_node,
            getString(if (homeAssistant.configured) R.string.device_home_assistant_configured else R.string.device_home_assistant_not_configured)
        ))
        customDevices.forEach { connector ->
            featureContent.addView(featureRow(
                connector.name,
                connector.transport.name.replace('_', ' '),
                R.drawable.ic_device_node,
                getString(if (connector.configured) R.string.status_enabled else R.string.common_needs_setup)
            ).apply {
                setOnClickListener { showCustomDeviceConnectorEditor(connector) }
            })
        }
        featureContent.addView(featureRow(
            getString(R.string.device_custom_add),
            getString(R.string.device_custom_add_subtitle),
            R.drawable.ic_device_node,
            "+"
        ).apply {
            setOnClickListener {
                showCustomDeviceConnectorEditor(
                    CustomDeviceConnector(
                        name = getString(R.string.device_custom_default_name),
                        transport = CustomDeviceTransport.HTTP_REST,
                        endpoint = ""
                    )
                )
            }
        })
        addSectionTitle(getString(R.string.device_home_assistant))
        featureContent.addView(featureRow(getString(R.string.device_home_assistant), getString(R.string.device_home_assistant_subtitle), R.drawable.ic_security_shield, onOffLabel(homeAssistant.enabled)).apply {
            setOnClickListener {
                HomeAssistantSettingsStore.setEnabled(this@MainActivity, !homeAssistant.enabled)
                showDeviceFeaturePage()
            }
        })
        featureContent.addView(featureRow(getString(R.string.device_home_assistant_url), homeAssistant.baseUrl.ifBlank { getString(R.string.device_home_assistant_url_subtitle) }, R.drawable.ic_protocol_link, getString(R.string.common_edit)).apply {
            setOnClickListener {
                showTextSettingDialog(getString(R.string.device_home_assistant_url), homeAssistant.baseUrl) {
                    HomeAssistantSettingsStore.setBaseUrl(this@MainActivity, it)
                    showDeviceFeaturePage()
                }
            }
        })
        featureContent.addView(featureRow(getString(R.string.device_home_assistant_token), maskedSecret(homeAssistant.accessToken).ifBlank { getString(R.string.device_home_assistant_token_subtitle) }, R.drawable.ic_security_shield, getString(R.string.common_edit)).apply {
            setOnClickListener {
                showTextSettingDialog(getString(R.string.device_home_assistant_token), homeAssistant.accessToken) {
                    HomeAssistantSettingsStore.setAccessToken(this@MainActivity, it)
                    showDeviceFeaturePage()
                }
            }
        })
        featureContent.addView(featureRow(getString(R.string.device_home_assistant_default_entity), homeAssistant.defaultEntityId.ifBlank { getString(R.string.device_home_assistant_default_entity_subtitle) }, R.drawable.ic_device_node, getString(R.string.common_edit)).apply {
            setOnClickListener {
                showTextSettingDialog(getString(R.string.device_home_assistant_default_entity), homeAssistant.defaultEntityId) {
                    HomeAssistantSettingsStore.setDefaultEntityId(this@MainActivity, it)
                    showDeviceFeaturePage()
                }
            }
        })
        addSectionTitle(getString(R.string.section_device_capabilities))
        featureContent.addView(featureRow(
            getString(R.string.device_file_sync),
            getString(R.string.device_file_sync_subtitle),
            R.drawable.ic_import,
            getString(if (desktopOnline) R.string.status_enabled else R.string.status_needs_setup)
        ))
        featureContent.addView(featureRow(
            getString(R.string.device_remote_control),
            getString(R.string.device_remote_control_subtitle),
            R.drawable.ic_security_shield,
            getString(
                when {
                    pairedDesktopCount == 0 -> R.string.status_needs_setup
                    desktopSecuritySummaries(activePcConnectorContacts()).any {
                        DesktopRemoteControl.snapshot(this, it.id).authorized
                    } -> R.string.status_enabled
                    else -> R.string.status_protected
                }
            )
        ).apply {
            setOnClickListener { showDesktopControlPicker() }
        })
    }

    private fun desktopControlDevices(): List<DesktopSecuritySummary> {
        val fromContacts = desktopSecuritySummaries(activePcConnectorContacts()).associateBy { it.id }.toMutableMap()
        SignalASILinkProtocol.allServerLinks(this).filter { it.paired }.forEach { link ->
            fromContacts.putIfAbsent(
                link.desktopId,
                DesktopSecuritySummary(
                    id = link.desktopId,
                    name = link.desktopName,
                    fingerprint = link.desktopFingerprint,
                    agentCount = 0,
                    lastActivityAt = 0L,
                    agentIds = emptySet()
                )
            )
        }
        return fromContacts.values.sortedBy { it.name.lowercase(Locale.ROOT) }
    }

    private fun showDesktopControlPicker() {
        val devices = desktopControlDevices()
        if (devices.size == 1) {
            DesktopRemoteControl.requestAuthorizations(devices.single().id)
            showDesktopRemoteControlPage(devices.single())
            return
        }
        showFeaturePage(getString(R.string.desktop_control_title))
        setFeatureBackAction { showDeviceFeaturePage() }
        featureContent.addView(featureHeroCard(
            getString(R.string.desktop_control_title),
            getString(R.string.desktop_control_picker_subtitle),
            R.drawable.ic_device_node,
            "#2878FF",
            getString(R.string.count_devices, devices.size)
        ))
        addSectionTitle(getString(R.string.desktop_control_computers))
        if (devices.isEmpty()) {
            featureContent.addView(featureRow(
                getString(R.string.security_no_paired_pc),
                getString(R.string.security_no_paired_pc_subtitle),
                R.drawable.ic_device_node,
                getString(R.string.security_scan)
            ).apply {
                setOnClickListener {
                    scanMode = "security"
                    startSecurityScan()
                }
            })
        } else {
            devices.forEach { device ->
                val snapshot = DesktopRemoteControl.snapshot(this, device.id)
                featureContent.addView(featureRow(
                    device.name,
                    formatFingerprint(device.fingerprint),
                    R.drawable.ic_device_node,
                    getString(
                        when {
                            snapshot.authorized -> R.string.status_enabled
                            snapshot.pending -> R.string.desktop_control_pending
                            else -> R.string.security_manage
                        }
                    )
                ).apply {
                    setOnClickListener {
                        DesktopRemoteControl.requestAuthorizations(device.id)
                        showDesktopRemoteControlPage(device)
                    }
                })
            }
        }
    }

    private fun showDesktopRemoteControlPage(device: DesktopSecuritySummary) {
        val snapshot = DesktopRemoteControl.snapshot(this, device.id)
        showFeaturePage(getString(R.string.desktop_control_title))
        activeDesktopControlId = device.id
        setFeatureBackAction { showDesktopControlPicker() }
        featureContent.addView(featureHeroCard(
            device.name,
            getString(R.string.desktop_control_trusted_subtitle),
            R.drawable.ic_device_node,
            when {
                snapshot.authorized -> "#14C66A"
                snapshot.pending -> "#F0A500"
                else -> "#8A939B"
            },
            getString(
                when {
                    snapshot.authorized -> R.string.desktop_control_authorized
                    snapshot.pending -> R.string.desktop_control_pending
                    !snapshot.enabled -> R.string.desktop_control_executor_off
                    else -> R.string.desktop_control_not_authorized
                }
            )
        ))

        addSectionTitle(getString(R.string.desktop_control_live_display))
        val screenshotFrame = FrameLayout(this).apply {
            background = GradientDrawable().apply {
                cornerRadius = dp(8).toFloat()
                setColor(Color.parseColor("#11161C"))
            }
            clipToOutline = true
        }
        val screenshotView = DesktopRemoteScreenView(this).apply {
            setScreenContentDescription(getString(R.string.desktop_control_screen_content_description))
        }
        screenshotFrame.addView(screenshotView, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        ))
        val placeholder = TextView(this).apply {
            text = getString(
                if (snapshot.authorized) R.string.desktop_control_tap_refresh
                else R.string.desktop_control_authorization_required
            )
            gravity = Gravity.CENTER
            setTextColor(Color.parseColor("#AAB3BD"))
            textSize = 14f
        }
        screenshotFrame.addView(placeholder, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        ))
        snapshot.screenshot?.let { screenshot ->
            android.graphics.BitmapFactory.decodeByteArray(
                screenshot.jpegBytes,
                0,
                screenshot.jpegBytes.size
            )?.let(screenshotView::setScreenshot)
            placeholder.visibility = View.GONE
            if (snapshot.authorized) {
                screenshotView.onImageTap = { xRatio, yRatio ->
                    val x = (xRatio * screenshot.originalWidth).roundToInt()
                        .coerceIn(0, screenshot.originalWidth - 1)
                    val y = (yRatio * screenshot.originalHeight).roundToInt()
                        .coerceIn(0, screenshot.originalHeight - 1)
                    if (DesktopRemoteControl.click(
                            device.id,
                            x,
                            y,
                            screenshot.originalWidth,
                            screenshot.originalHeight
                        )
                    ) {
                        Toast.makeText(
                            this@MainActivity,
                            getString(R.string.desktop_control_click_sent, x, y),
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            }
        }
        featureContent.addView(screenshotFrame, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            dp(432)
        ).apply {
            topMargin = dp(2)
        })
        featureContent.addView(featureRow(
            getString(R.string.desktop_control_refresh_screen),
            snapshot.screenshot?.let {
                getString(
                    R.string.desktop_control_screen_metadata,
                    it.originalWidth,
                    it.originalHeight,
                    securityTime(it.capturedAt)
                )
            }.orEmpty().ifBlank { getString(R.string.desktop_control_refresh_screen_subtitle) },
            R.drawable.ic_import,
            getString(R.string.common_view)
        ).apply {
            isEnabled = snapshot.authorized
            alpha = if (snapshot.authorized) 1f else 0.5f
            setOnClickListener {
                if (DesktopRemoteControl.requestScreenshot(device.id)) {
                    Toast.makeText(this@MainActivity, getString(R.string.desktop_control_request_sent), Toast.LENGTH_SHORT).show()
                }
            }
        })

        if (snapshot.lastActionSummary.isNotBlank()) {
            featureContent.addView(featureRow(
                getString(R.string.desktop_control_latest_action),
                snapshot.lastActionSummary,
                R.drawable.ic_agent_history,
                desktopControlStatusLabel(snapshot.lastActionStatus)
            ))
        }

        addSectionTitle(getString(R.string.desktop_control_actions))
        featureContent.addView(desktopControlButtonRow(
            getString(R.string.desktop_control_scroll_up) to { DesktopRemoteControl.scroll(device.id, 480) },
            getString(R.string.desktop_control_scroll_down) to { DesktopRemoteControl.scroll(device.id, -480) },
            enabled = snapshot.authorized
        ))
        featureContent.addView(desktopControlButtonRow(
            getString(R.string.desktop_control_alt_tab) to { DesktopRemoteControl.hotkey(device.id, "alt", "tab") },
            getString(R.string.desktop_control_escape) to { DesktopRemoteControl.hotkey(device.id, "escape") },
            enabled = snapshot.authorized
        ))
        featureContent.addView(featureRow(
            getString(R.string.desktop_control_type_text),
            getString(R.string.desktop_control_type_text_subtitle),
            R.drawable.ic_protocol_link,
            getString(R.string.common_edit)
        ).apply {
            isEnabled = snapshot.authorized
            alpha = if (snapshot.authorized) 1f else 0.5f
            setOnClickListener {
                showTextSettingDialog(getString(R.string.desktop_control_type_text), "") { text ->
                    if (!DesktopRemoteControl.typeText(device.id, text)) {
                        Toast.makeText(this@MainActivity, getString(R.string.desktop_control_request_failed), Toast.LENGTH_SHORT).show()
                    }
                }
            }
        })

        addSectionTitle(getString(R.string.desktop_control_authorization))
        val desktopFingerprint = snapshot.desktopFingerprint.ifBlank { device.fingerprint }
        featureContent.addView(featureRow(
            getString(R.string.security_desktop_fingerprint),
            formatFingerprint(desktopFingerprint),
            R.drawable.ic_security_shield,
            getString(R.string.common_copy)
        ).apply {
            isEnabled = desktopFingerprint.isNotBlank()
            setOnClickListener {
                copyText(desktopFingerprint, getString(R.string.security_copied_desktop_fingerprint))
            }
        })
        snapshot.authorizations.filter { it.status != "revoked" }.forEach { authorization ->
            val timeSummary = when {
                authorization.grantedAt > 0L && authorization.lastUsedAt > 0L -> getString(
                    R.string.desktop_control_granted_and_used,
                    securityTime(authorization.grantedAt),
                    securityTime(authorization.lastUsedAt)
                )
                authorization.grantedAt > 0L -> getString(
                    R.string.desktop_control_granted_at,
                    securityTime(authorization.grantedAt)
                )
                else -> ""
            }
            featureContent.addView(featureRow(
                authorization.phoneName.ifBlank { getString(R.string.desktop_control_this_phone) },
                listOf(formatFingerprint(authorization.phoneFingerprint), timeSummary)
                    .filter { it.isNotBlank() }
                    .joinToString("\n"),
                R.drawable.ic_security_shield,
                desktopControlStatusLabel(authorization.status)
            ))
        }
        if (snapshot.currentAuthorization?.status == "active") {
            featureContent.addView(featureRow(
                getString(R.string.desktop_control_revoke),
                getString(R.string.desktop_control_revoke_subtitle),
                R.drawable.ic_delete,
                getString(R.string.security_revoke)
            ).apply {
                setOnClickListener {
                    AlertDialog.Builder(this@MainActivity)
                        .setTitle(getString(R.string.desktop_control_revoke))
                        .setMessage(getString(R.string.desktop_control_revoke_confirm))
                        .setPositiveButton(getString(R.string.security_revoke)) { _, _ ->
                            DesktopRemoteControl.revoke(device.id, snapshot.currentAuthorization.authorizationId)
                        }
                        .setNegativeButton(getString(R.string.common_cancel), null)
                        .show()
                }
            })
        }
        addSectionTitle(getString(R.string.desktop_control_recent_activity))
        if (snapshot.recentAudit.isEmpty()) {
            featureContent.addView(featureRow(
                getString(R.string.desktop_control_no_recent_activity),
                "",
                R.drawable.ic_agent_history,
                ""
            ))
        } else {
            snapshot.recentAudit.take(12).forEach { event ->
                featureContent.addView(featureRow(
                    event.summary.ifBlank { event.eventType },
                    securityTime(event.createdAt),
                    R.drawable.ic_agent_history,
                    desktopControlStatusLabel(event.status)
                ))
            }
        }
        featureContent.addView(TextView(this).apply {
            text = getString(R.string.desktop_control_security_footer)
            setTextColor(getColorCompat(R.color.text_secondary))
            textSize = 12f
            setPadding(dp(4), dp(14), dp(4), dp(20))
        })
    }

    private fun desktopControlButtonRow(
        left: Pair<String, () -> Boolean>,
        right: Pair<String, () -> Boolean>,
        enabled: Boolean
    ): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        addView(desktopControlActionButton(left.first, enabled, left.second), LinearLayout.LayoutParams(
            0,
            dp(44),
            1f
        ).apply { marginEnd = dp(5) })
        addView(desktopControlActionButton(right.first, enabled, right.second), LinearLayout.LayoutParams(
            0,
            dp(44),
            1f
        ).apply { marginStart = dp(5) })
    }

    private fun desktopControlActionButton(
        label: String,
        enabled: Boolean,
        action: () -> Boolean
    ): TextView = TextView(this).apply {
        text = label
        gravity = Gravity.CENTER
        textSize = 14f
        setTextColor(Color.parseColor(if (enabled) "#1677E8" else "#9AA3AC"))
        background = GradientDrawable().apply {
            cornerRadius = dp(8).toFloat()
            setColor(Color.parseColor("#F2F6FE"))
            setStroke(dp(1), Color.parseColor("#DCE8F8"))
        }
        isEnabled = enabled
        setOnClickListener {
            if (!action()) {
                Toast.makeText(this@MainActivity, getString(R.string.desktop_control_request_failed), Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun desktopControlStatusLabel(status: String): String = getString(
        when (status) {
            "active", "succeeded" -> R.string.status_enabled
            "pending", "sending", "running" -> R.string.desktop_control_pending
            "revoked" -> R.string.desktop_control_revoked
            "failed" -> R.string.agent_task_status_failed
            else -> R.string.status_unknown
        }
    )

    private fun handleDesktopRemoteControlEvent(json: JSONObject?): Boolean {
        val payload = json ?: return false
        if (payload.optString("type") !in setOf(
                "desktop_control_authorizations",
                "desktop_control_authorization_changed",
                "desktop_executor_event",
                "desktop_action_receipt"
            )
        ) return false
        val desktopId = payload.optString("desktop_id")
        if (desktopId.isNotBlank() && activeDesktopControlId == desktopId && featurePage.visibility == View.VISIBLE) {
            desktopControlDevices().firstOrNull { it.id == desktopId }?.let(::showDesktopRemoteControlPage)
        }
        return true
    }

    private fun showCustomDeviceConnectorEditor(connector: CustomDeviceConnector) {
        showFeaturePage(getString(R.string.device_custom_editor_title))
        setFeatureBackAction { showDeviceFeaturePage() }
        featureContent.addView(featureHeroCard(
            connector.name,
            getString(R.string.device_custom_editor_subtitle),
            R.drawable.ic_device_node,
            "#14C66A",
            connector.transport.name.replace('_', ' ')
        ))
        addSectionTitle(getString(R.string.device_custom_section_connection))
        featureContent.addView(featureRow(getString(R.string.device_custom_name), connector.name, R.drawable.ic_device_node, getString(R.string.common_edit)).apply {
            setOnClickListener {
                showTextSettingDialog(getString(R.string.device_custom_name), connector.name) {
                    showCustomDeviceConnectorEditor(connector.copy(name = it))
                }
            }
        })
        featureContent.addView(featureRow(
            getString(R.string.device_custom_transport),
            getString(R.string.device_custom_transport_subtitle),
            R.drawable.ic_protocol_link,
            connector.transport.name.replace('_', ' ')
        ).apply {
            setOnClickListener {
                val options = CustomDeviceTransport.entries.map { it.name.replace('_', ' ') }
                showChoiceDialog(getString(R.string.device_custom_transport), options, connector.transport.name.replace('_', ' ')) { selected ->
                    showCustomDeviceConnectorEditor(connector.copy(transport = CustomDeviceTransport.valueOf(selected.replace(' ', '_'))))
                }
            }
        })
        featureContent.addView(featureRow(
            getString(R.string.device_custom_endpoint),
            connector.endpoint.ifBlank { getString(R.string.device_custom_endpoint_subtitle) },
            R.drawable.ic_protocol_link,
            getString(R.string.common_edit)
        ).apply {
            setOnClickListener {
                showTextSettingDialog(getString(R.string.device_custom_endpoint), connector.endpoint) {
                    showCustomDeviceConnectorEditor(connector.copy(endpoint = it))
                }
            }
        })
        featureContent.addView(featureRow(
            getString(R.string.device_custom_target),
            connector.commandTarget.ifBlank { getString(R.string.device_custom_target_subtitle) },
            R.drawable.ic_device_node,
            getString(R.string.common_edit)
        ).apply {
            setOnClickListener {
                showTextSettingDialog(getString(R.string.device_custom_target), connector.commandTarget) {
                    showCustomDeviceConnectorEditor(connector.copy(commandTarget = it))
                }
            }
        })
        featureContent.addView(featureRow(
            getString(R.string.device_custom_username),
            connector.username.ifBlank { getString(R.string.common_empty) },
            R.drawable.ic_agent_node,
            getString(R.string.common_edit)
        ).apply {
            setOnClickListener {
                showTextSettingDialog(getString(R.string.device_custom_username), connector.username) {
                    showCustomDeviceConnectorEditor(connector.copy(username = it))
                }
            }
        })
        featureContent.addView(featureRow(
            getString(R.string.device_custom_token),
            maskedSecret(connector.authToken).ifBlank { getString(R.string.common_empty) },
            R.drawable.ic_security_shield,
            getString(R.string.common_edit)
        ).apply {
            setOnClickListener {
                showTextSettingDialog(getString(R.string.device_custom_token), connector.authToken) {
                    showCustomDeviceConnectorEditor(connector.copy(authToken = it))
                }
            }
        })
        addSectionTitle(getString(R.string.device_custom_section_safety))
        featureContent.addView(featureRow(
            getString(R.string.device_custom_risk),
            getString(R.string.device_custom_risk_subtitle),
            R.drawable.ic_security_shield,
            connector.risk.name
        ).apply {
            setOnClickListener {
                val options = listOf(AgentRisk.LOW, AgentRisk.MEDIUM, AgentRisk.HIGH).map { it.name }
                showChoiceDialog(getString(R.string.device_custom_risk), options, connector.risk.name) { selected ->
                    showCustomDeviceConnectorEditor(connector.copy(risk = AgentRisk.valueOf(selected)))
                }
            }
        })
        featureContent.addView(featureSwitchRow(
            getString(R.string.device_custom_enabled),
            getString(R.string.device_custom_enabled_subtitle),
            R.drawable.ic_device_node,
            connector.enabled
        ).apply {
            setOnClickListener { showCustomDeviceConnectorEditor(connector.copy(enabled = !connector.enabled)) }
        })
        addSectionTitle(getString(R.string.section_actions))
        featureContent.addView(featureRow(
            getString(R.string.common_save),
            getString(R.string.device_custom_save_subtitle),
            R.drawable.ic_import,
            getString(R.string.common_save)
        ).apply {
            setOnClickListener {
                if (connector.name.isBlank() || connector.endpoint.isBlank()) {
                    Toast.makeText(this@MainActivity, getString(R.string.device_custom_required), Toast.LENGTH_SHORT).show()
                } else {
                    CustomDeviceConnectorStore(this@MainActivity).upsert(connector)
                    showDeviceFeaturePage()
                }
            }
        })
        if (CustomDeviceConnectorStore(this).find(connector.id) != null) {
            featureContent.addView(featureRow(
                getString(R.string.common_delete),
                getString(R.string.device_custom_delete_subtitle),
                R.drawable.ic_security_shield,
                getString(R.string.common_delete)
            ).apply {
                setOnClickListener {
                    CustomDeviceConnectorStore(this@MainActivity).delete(connector.id)
                    showDeviceFeaturePage()
                }
            })
        }
    }

    private fun maskedSecret(value: String): String = when {
        value.isBlank() -> ""
        value.length <= 8 -> "****"
        else -> "${value.take(4)}****${value.takeLast(4)}"
    }

    private fun showAutomationFeaturePage() {
        val workflows = SharedPreferencesAgentWorkflowStore(this).list()
        val schedules = AgentWorkflowScheduleStore(this).list()
        val triggers = AgentWorkflowTriggerStore(this).list()
        val recentExecutions = AgentWorkflowExecutionHistoryStore(this).recent()
        val templates = AgentWorkflowTemplates.all
        showFeaturePage(getString(R.string.automation_title))
        featureContent.addView(featureHeroCard(
            getString(R.string.automation_hero_title),
            getString(R.string.automation_hero_subtitle),
            R.drawable.ic_send_plane,
            "#FFB020",
            getString(R.string.count_items, workflows.size)
        ))
        addSectionTitle(getString(R.string.automation_saved_workflows))
        if (workflows.isEmpty()) {
            featureContent.addView(featureRow(
                getString(R.string.automation_no_workflows),
                getString(R.string.automation_create_workflow_hint),
                R.drawable.ic_send_plane,
                ""
            ))
        } else {
            workflows.forEach { workflow ->
                featureContent.addView(featureRow(
                    workflow.name,
                    workflow.goal,
                    R.drawable.ic_send_plane,
                    getString(R.string.automation_run)
                ).apply {
                    setOnClickListener { openAgentWorkflow("run workflow ${workflow.name}") }
                })
            }
        }
        addSectionTitle(getString(R.string.automation_schedules))
        if (schedules.isEmpty()) {
            featureContent.addView(featureRow(
                getString(R.string.automation_no_schedules),
                getString(R.string.automation_schedule_hint),
                R.drawable.ic_protocol_link,
                ""
            ))
        } else {
            schedules.forEach { schedule ->
                featureContent.addView(featureRow(
                    schedule.workflowName,
                    automationScheduleSubtitle(schedule),
                    R.drawable.ic_protocol_link,
                    getString(R.string.status_enabled)
                ).apply {
                    setOnClickListener { openAgentWorkflow("cancel schedule ${schedule.workflowName}") }
                })
            }
        }
        addSectionTitle(getString(R.string.automation_event_triggers))
        if (triggers.isEmpty()) {
            featureContent.addView(featureRow(
                getString(R.string.automation_no_event_triggers),
                getString(R.string.automation_event_trigger_hint),
                R.drawable.ic_protocol_link,
                ""
            ))
        } else {
            triggers.forEach { trigger ->
                featureContent.addView(featureRow(
                    trigger.workflowName,
                    automationTriggerSubtitle(trigger),
                    R.drawable.ic_protocol_link,
                    getString(R.string.common_delete)
                ).apply {
                    setOnClickListener { openAgentWorkflow("delete trigger ${trigger.id}") }
                })
            }
        }
        addSectionTitle(getString(R.string.automation_recent_executions))
        if (recentExecutions.isEmpty()) {
            featureContent.addView(featureRow(
                getString(R.string.automation_no_recent_executions),
                getString(R.string.automation_run_command_hint),
                R.drawable.ic_security_shield,
                ""
            ))
        } else {
            recentExecutions.forEach { execution ->
                featureContent.addView(featureRow(
                    execution.workflowName,
                    automationExecutionSubtitle(execution),
                    R.drawable.ic_security_shield,
                    getString(
                        R.string.automation_run_status,
                        automationExecutionStatusLabel(execution.status)
                    )
                ))
            }
        }
        addSectionTitle(getString(R.string.automation_templates))
        templates.forEach { template ->
            featureContent.addView(featureRow(
                template.name,
                template.goal,
                R.drawable.ic_agent_node,
                getString(R.string.automation_run)
            ).apply {
                setOnClickListener { openAgentWorkflow("run template ${template.name}") }
            })
        }
    }

    private fun openAgentWorkflow(command: String) {
        hideFeaturePage()
        showMainTab(PAGE_AGENT)
        prefillAgentGoal(command)
    }

    private fun automationScheduleSubtitle(schedule: AgentWorkflowSchedule): String {
        val cadence = when (schedule.kind) {
            AgentWorkflowScheduleKind.DAILY -> "%02d:%02d".format(Locale.US, schedule.hour, schedule.minute)
            AgentWorkflowScheduleKind.INTERVAL -> getString(
                R.string.automation_every_minutes,
                schedule.intervalMinutes
            )
        }
        val next = if (schedule.nextRunAtMillis > 0L) {
            SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(schedule.nextRunAtMillis))
        } else {
            "-"
        }
        return getString(R.string.automation_schedule_subtitle, cadence, next)
    }

    private fun automationTriggerSubtitle(trigger: AgentWorkflowTrigger): String {
        val event = when (trigger.kind) {
            AgentWorkflowTriggerKind.NOTIFICATION_PACKAGE ->
                getString(R.string.automation_trigger_notification_package, trigger.condition)
            AgentWorkflowTriggerKind.NOTIFICATION_TEXT ->
                getString(R.string.automation_trigger_notification_text, trigger.condition)
            AgentWorkflowTriggerKind.POWER_CONNECTED ->
                getString(R.string.automation_trigger_power_connected)
            AgentWorkflowTriggerKind.BATTERY_LOW ->
                getString(R.string.automation_trigger_battery_low)
        }
        val status = getString(if (trigger.enabled) R.string.status_enabled else R.string.common_off)
        return listOf(
            getString(
                R.string.automation_trigger_subtitle,
                event,
                trigger.cooldownMinutes,
                status
            ),
            automationTriggerConditionCountLabel(trigger)
        ).joinToString("\n")
    }

    private fun automationTriggerConditionCountLabel(trigger: AgentWorkflowTrigger): String =
        getString(
            R.string.automation_trigger_condition_count,
            trigger.conditions.size
        )

    private fun automationExecutionSubtitle(execution: AgentWorkflowExecutionRecord): String {
        val source = automationExecutionSourceLabel(execution.source)
        val timestamp = execution.completedAtMillis.takeIf { it > 0L }
            ?: execution.startedAtMillis.takeIf { it > 0L }
        val time = timestamp?.let {
            SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(it))
        } ?: getString(R.string.status_unknown)
        val result = execution.resultSummary.trim()
            .ifBlank { getString(R.string.automation_run_result_empty) }
        return listOf(
            getString(R.string.automation_run_source, source),
            getString(R.string.automation_run_time, time),
            getString(R.string.automation_run_result, result)
        ).joinToString("\n")
    }

    private fun automationExecutionSourceLabel(source: AgentWorkflowExecutionSource): String = getString(
        when (source) {
            AgentWorkflowExecutionSource.MANUAL -> R.string.automation_run_source_manual
            AgentWorkflowExecutionSource.SCHEDULE -> R.string.automation_run_source_schedule
            AgentWorkflowExecutionSource.EVENT -> R.string.automation_run_source_event
        }
    )

    private fun automationExecutionStatusLabel(status: AgentWorkflowExecutionStatus): String = getString(
        when (status) {
            AgentWorkflowExecutionStatus.RUNNING -> R.string.automation_run_status_running
            AgentWorkflowExecutionStatus.WAITING_CONFIRMATION ->
                R.string.automation_run_status_waiting_confirmation
            AgentWorkflowExecutionStatus.WAITING_RESPONSE ->
                R.string.automation_run_status_waiting_response
            AgentWorkflowExecutionStatus.COMPLETED -> R.string.automation_run_status_completed
            AgentWorkflowExecutionStatus.SKIPPED -> R.string.automation_run_status_skipped
            AgentWorkflowExecutionStatus.FAILED -> R.string.automation_run_status_failed
            AgentWorkflowExecutionStatus.CANCELLED -> R.string.automation_run_status_cancelled
            AgentWorkflowExecutionStatus.BLOCKED -> R.string.automation_run_status_blocked
        }
    )

    private fun showSecurityFeaturePage() {
        val connectorContacts = activePcConnectorContacts()
        val desktops = desktopSecuritySummaries(connectorContacts)
        showFeaturePage(getString(R.string.security_title))
        featureContent.addView(featureHeroCard(getString(R.string.security_privacy_title), getString(R.string.security_privacy_subtitle), R.drawable.ic_security_shield, "#14C66A", getString(R.string.count_devices, desktops.size)))
        addSectionTitle(getString(R.string.security_section_identity))
        val localFingerprint = SignalASICrypto.localIdentitySha256()
        featureContent.addView(featureRow(getString(R.string.security_phone_fingerprint), formatFingerprint(localFingerprint), R.drawable.ic_security_shield, getString(R.string.common_copy)).apply {
            setOnClickListener { copyText(localFingerprint, getString(R.string.security_copied_phone_fingerprint)) }
        })
        featureContent.addView(featureRow(getString(R.string.settings_signalasi_id), SignalASICrypto.localSignalasiId(), R.drawable.ic_protocol_link, getString(R.string.common_copy)).apply {
            setOnClickListener { copyText(SignalASICrypto.localSignalasiId(), getString(R.string.security_copied_signalasi_id)) }
        })
        addSectionTitle(getString(R.string.security_section_paired_devices))
        if (desktops.isEmpty()) {
            featureContent.addView(featureRow(getString(R.string.security_no_paired_pc), getString(R.string.security_no_paired_pc_subtitle), R.drawable.ic_device_node, getString(R.string.security_scan)).apply {
                setOnClickListener {
                    scanMode = "security"
                    startSecurityScan()
                }
            })
        } else {
            desktops.forEach { device ->
                featureContent.addView(featureRow(
                    device.name,
                    "${formatFingerprint(device.fingerprint)}\n${getString(R.string.security_last_active, securityTime(device.lastActivityAt))}",
                    R.drawable.ic_device_node,
                    getString(R.string.security_manage)
                ).apply {
                    setOnClickListener { showDesktopSecurityDetail(device) }
                })
            }
        }
        addSectionTitle(getString(R.string.security_section_agent_permissions))
        featureContent.addView(featureRow(getString(R.string.security_on_device_agent_permissions), getString(R.string.security_on_device_agent_permissions_subtitle), R.drawable.ic_agent_node, getString(R.string.common_view)).apply {
            setOnClickListener { showOnDeviceAgentFeaturePage() }
        })
        connectorContacts.take(8).forEach { contact ->
            val name = contact.optString("agent_name").ifBlank { contact.optString("name", "Agent") }
            val status = contact.optString("setup_status").ifBlank { "unknown" }
            val updatedAt = contact.optLong("setup_updated_at", contact.optLong("created_at", 0L))
            featureContent.addView(featureRow(
                name,
                getString(R.string.security_permission_status, securityStatusLabel(status), securityTime(updatedAt)),
                agentIconForKind(contact.optString("agent_kind"), contact.optString("agent_id")),
                securityStatusLabel(status)
            ))
        }
        if (connectorContacts.size > 8) {
            featureContent.addView(featureRow(getString(R.string.security_more_agents), getString(R.string.security_more_agents_subtitle, connectorContacts.size - 8), R.drawable.ic_agent_node, getString(R.string.common_view)))
        }
        addSectionTitle(getString(R.string.security_section_message_protection))
        featureContent.addView(featureRow("Signal Protocol", getString(R.string.security_signal_protocol_subtitle), R.drawable.ic_protocol_link, "v1.0.3"))
        featureContent.addView(featureRow(getString(R.string.security_fingerprint_confirm), getString(R.string.security_fingerprint_confirm_subtitle), R.drawable.ic_security_shield, getString(R.string.status_enabled)))
        featureContent.addView(featureRow(getString(R.string.security_revoke_all_pc), getString(R.string.security_revoke_all_pc_subtitle), R.drawable.ic_delete, getString(R.string.security_manage)).apply {
            setOnClickListener { showRevokeAllPcPairingsPage() }
        })
    }

    private data class DesktopSecuritySummary(
        val id: String,
        val name: String,
        val fingerprint: String,
        val agentCount: Int,
        val lastActivityAt: Long,
        val agentIds: Set<String>
    )

    private fun activePcConnectorContacts(): List<JSONObject> {
        val contacts = AppStore.contacts(this)
        val result = mutableListOf<JSONObject>()
        for (i in 0 until contacts.length()) {
            val contact = contacts.optJSONObject(i) ?: continue
            if (contact.optBoolean("deleted", false) || contact.optString("trust_state") == "deleted") continue
            if (contact.optString("delivery_mode") != "pc_connector") continue
            result.add(contact)
        }
        return result
    }

    private fun desktopSecuritySummaries(connectorContacts: List<JSONObject>): List<DesktopSecuritySummary> {
        return connectorContacts
            .groupBy { contact ->
                contact.optString("desktop_id").ifBlank {
                    "desktop_${contact.optString("desktop_fingerprint", contact.optString("identity_fingerprint")).take(16)}"
                }
            }
            .map { (desktopId, contacts) ->
                val first = contacts.firstOrNull() ?: JSONObject()
                val fingerprint = first.optString("desktop_fingerprint").ifBlank { first.optString("identity_fingerprint") }
                DesktopSecuritySummary(
                    id = desktopId,
                    name = first.optString("desktop_name").ifBlank { "PC" },
                    fingerprint = fingerprint,
                    agentCount = contacts.size,
                    lastActivityAt = contacts.maxOfOrNull { it.optLong("setup_updated_at", it.optLong("created_at", 0L)) } ?: 0L,
                    agentIds = contacts.flatMapTo(linkedSetOf()) { contact ->
                        listOf(
                            contact.optString("id"),
                            jsonSignalasiId(contact),
                            contact.optString("agent_id")
                        ).filter(String::isNotBlank)
                    }
                )
            }
            .sortedBy { it.name.lowercase(Locale.ROOT) }
    }

    private fun showDesktopSecurityDetail(device: DesktopSecuritySummary) {
        val agents = activePcConnectorContacts().filter { contact ->
            contact.optString("desktop_id") == device.id ||
                contact.optString("parent_contact") == device.id ||
                contact.optString("id").ifBlank { jsonSignalasiId(contact) }.startsWith("${device.id}:")
        }
        showFeaturePage(getString(R.string.security_device_detail_title))
        setFeatureBackAction { showSecurityFeaturePage() }
        featureContent.addView(featureHeroCard(device.name, getString(R.string.security_verified_desktop_connector), R.drawable.ic_device_node, "#14C66A", getString(R.string.count_items, device.agentCount)))
        addSectionTitle(getString(R.string.security_section_identity))
        featureContent.addView(featureRow("Desktop ID", device.id, R.drawable.ic_protocol_link, getString(R.string.common_copy)).apply {
            setOnClickListener { copyText(device.id, getString(R.string.security_copied_desktop_id)) }
        })
        featureContent.addView(featureRow(getString(R.string.security_desktop_fingerprint), formatFingerprint(device.fingerprint), R.drawable.ic_security_shield, getString(R.string.common_copy)).apply {
            setOnClickListener { copyText(device.fingerprint, getString(R.string.security_copied_desktop_fingerprint)) }
        })
        featureContent.addView(featureRow(getString(R.string.security_last_active_title), securityTime(device.lastActivityAt), R.drawable.ic_protocol_link, ""))
        featureContent.addView(featureRow(
            getString(R.string.device_remote_control),
            getString(R.string.desktop_control_manage_subtitle),
            R.drawable.ic_security_shield,
            getString(if (DesktopRemoteControl.snapshot(this, device.id).authorized) R.string.status_enabled else R.string.security_manage)
        ).apply {
            setOnClickListener {
                DesktopRemoteControl.requestAuthorizations(device.id)
                showDesktopRemoteControlPage(device)
            }
        })
        addSectionTitle("Agent")
        agents.forEach { contact ->
            val name = contact.optString("agent_name").ifBlank { contact.optString("name", "Agent") }
            val status = contact.optString("setup_status").ifBlank { "unknown" }
            featureContent.addView(featureRow(
                name,
                contact.optString("setup_detail").ifBlank { contact.optString("agent_kind") },
                agentIconForKind(contact.optString("agent_kind"), contact.optString("agent_id")),
                securityStatusLabel(status)
            ))
        }
        addSectionTitle(getString(R.string.security_section_danger))
        featureContent.addView(featureRow(getString(R.string.security_revoke_this_pc), getString(R.string.security_revoke_this_pc_subtitle), R.drawable.ic_delete, getString(R.string.security_revoke)).apply {
            setOnClickListener { confirmRevokeDesktop(device) }
        })
    }

    private fun confirmRevokeDesktop(device: DesktopSecuritySummary) {
        showFeaturePage(getString(R.string.security_revoke_device_title))
        featureContent.addView(featureHeroCard(device.name, getString(R.string.security_revoke_device_subtitle), R.drawable.ic_delete, "#FF3B30", getString(R.string.common_confirm)))
        featureContent.addView(featureRow(getString(R.string.security_revoke_scope), getString(R.string.count_pc_connector_agents, device.agentCount), R.drawable.ic_agent_node, getString(R.string.security_delete)))
        featureContent.addView(featureRow(getString(R.string.security_desktop_fingerprint), formatFingerprint(device.fingerprint), R.drawable.ic_security_shield, ""))
        featureContent.addView(TextView(this).apply {
            text = getString(R.string.security_revoke_this_pc)
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            textSize = 17f
            background = GradientDrawable().apply {
                cornerRadius = dp(8).toFloat()
                setColor(Color.parseColor("#FF3B30"))
            }
            setOnClickListener {
                if (AppStore.revokeDesktopConnector(this@MainActivity, device.id)) {
                    refreshContactList()
                    refreshDirectoryContacts()
                    Toast.makeText(this@MainActivity, getString(R.string.security_revoked_device, device.name), Toast.LENGTH_LONG).show()
                }
                showSecurityFeaturePage()
            }
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(46)).apply {
            topMargin = dp(18)
        })
    }

    private fun showRevokeAllPcPairingsPage() {
        showFeaturePage(getString(R.string.security_revoke_all_pc))
        val desktops = desktopSecuritySummaries(activePcConnectorContacts())
        featureContent.addView(featureHeroCard(getString(R.string.security_revoke_all_pc_title), getString(R.string.security_revoke_all_pc_hero_subtitle), R.drawable.ic_delete, "#FF3B30", getString(R.string.count_devices, desktops.size)))
        desktops.forEach { device ->
            featureContent.addView(featureRow(device.name, getString(R.string.security_device_agent_fingerprint_summary, device.agentCount, formatFingerprint(device.fingerprint)), R.drawable.ic_device_node, getString(R.string.security_will_revoke)))
        }
        featureContent.addView(TextView(this).apply {
            text = getString(R.string.security_revoke_all_pc)
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            textSize = 17f
            background = GradientDrawable().apply {
                cornerRadius = dp(8).toFloat()
                setColor(Color.parseColor("#FF3B30"))
            }
            setOnClickListener {
                desktops.forEach { AppStore.revokeDesktopConnector(this@MainActivity, it.id) }
                refreshContactList()
                refreshDirectoryContacts()
                Toast.makeText(this@MainActivity, getString(R.string.security_revoked_all_pc), Toast.LENGTH_LONG).show()
                showSecurityFeaturePage()
            }
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(46)).apply {
            topMargin = dp(18)
        })
    }

    private fun securityStatusLabel(status: String): String = when (status) {
        "ready" -> getString(R.string.status_ready)
        "needs_setup" -> getString(R.string.status_needs_setup)
        "unknown" -> getString(R.string.status_unknown)
        else -> status.ifBlank { getString(R.string.status_unknown) }
    }

    private fun securityTime(timestamp: Long): String {
        if (timestamp <= 0L) return getString(R.string.status_unknown)
        return SimpleDateFormat("MM/dd HH:mm", Locale.getDefault()).format(Date(timestamp))
    }

    private fun agentIconForKind(kind: String, agentId: String): Int = when {
        agentId == "hermes" -> R.drawable.ic_avatar_hermes
        agentId == "codex" -> R.drawable.logo_codex_product
        agentId == "claude" -> R.drawable.logo_claude_code
        agentId == "openclaw" -> R.drawable.ic_avatar_custom_agent
        kind == "local-model" -> R.drawable.ic_local_model
        else -> R.drawable.ic_agent_node
    }

    private fun showProtocolQualityFeaturePage() {
        showFeaturePage(getString(R.string.protocol_quality_title))
        featureContent.addView(featureHeroCard("SignalASI Link", getString(R.string.protocol_quality_hero_subtitle), R.drawable.ic_protocol_link, "#5B6CFF", "v1.0.3"))
        addSectionTitle(getString(R.string.protocol_section_quality))
        featureContent.addView(featureRow(getString(R.string.protocol_delivery_ack), getString(R.string.protocol_delivery_ack_subtitle), R.drawable.ic_send_plane, getString(R.string.common_on)))
        featureContent.addView(featureRow(getString(R.string.protocol_offline_queue), getString(R.string.protocol_offline_queue_subtitle), R.drawable.ic_import, getString(R.string.protocol_badge_enabled)))
        addSectionTitle(getString(R.string.protocol_section_security))
        featureContent.addView(featureRow(getString(R.string.protocol_identity_key), getString(R.string.protocol_identity_key_subtitle), R.drawable.ic_security_shield, getString(R.string.protocol_badge_enabled)))
        featureContent.addView(featureRow(getString(R.string.protocol_session_rotation), getString(R.string.protocol_session_rotation_subtitle), R.drawable.ic_protocol_link, getString(R.string.protocol_badge_enabled)))
    }

    private fun showSignalLinkProtocolPage() {
        val transportConnected = SignalASIMqttClient.isConnected()
        val secureSessionReady = transportConnected && SignalASIMqttClient.isSecureReady()
        showFeaturePage("Signal Link Protocol")
        featureContent.addView(featureHeroCard(
            "Signal Link Protocol",
            getString(if (secureSessionReady) R.string.cc_service_link_connected else R.string.cc_service_link_offline),
            R.drawable.ic_protocol_link,
            if (secureSessionReady) "#14C66A" else "#F0A500",
            getString(if (secureSessionReady) R.string.protocol_badge_stable else R.string.status_disconnected)
        ))
        addSectionTitle(getString(R.string.protocol_section_layers))
        featureContent.addView(featureRow(getString(R.string.protocol_identity_layer), getString(R.string.protocol_identity_layer_subtitle), R.drawable.ic_security_shield, getString(R.string.protocol_badge_enabled)))
        featureContent.addView(featureRow(
            getString(R.string.protocol_session_layer),
            getString(R.string.protocol_session_layer_subtitle),
            R.drawable.ic_protocol_link,
            getString(if (secureSessionReady) R.string.protocol_badge_enabled else R.string.status_disconnected)
        ))
        featureContent.addView(featureRow(
            getString(R.string.protocol_transport_layer),
            getString(R.string.protocol_transport_layer_subtitle),
            R.drawable.ic_device_node,
            if (transportConnected) "MQTT" else getString(R.string.status_disconnected)
        ))
        addSectionTitle(getString(R.string.protocol_section_current_endpoint))
        featureContent.addView(featureRow(
            getString(R.string.protocol_pc_endpoint),
            getString(if (secureSessionReady) R.string.cc_service_link_connected else R.string.cc_service_link_offline),
            R.drawable.ic_device_node,
            getString(if (secureSessionReady) R.string.protocol_badge_online else R.string.status_disconnected)
        ))
    }

    private fun showAdvancedOptionsFeaturePage() {
        renderControlCenterAdvancedPage()
    }

    private fun showVoiceAssistantSettingsPage() {
        val config = VoiceAssistantSettings.get(this)
        showFeaturePage(getString(R.string.voice_settings_title))
        featureContent.addView(featureHeroCard(
            getString(R.string.voice_low_power_title),
            getString(R.string.voice_low_power_subtitle),
            R.drawable.ic_input_voice,
            "#00EFDE",
            onOffLabel(config.enabled)
        ))
        addSectionTitle(getString(R.string.voice_section_listening))
        featureContent.addView(featureRow(getString(R.string.voice_low_power_monitor), getString(R.string.voice_low_power_monitor_subtitle), R.drawable.ic_input_voice, onOffLabel(config.enabled)).apply {
            setOnClickListener {
                VoiceAssistantSettings.setEnabled(this@MainActivity, !config.enabled)
                showVoiceAssistantSettingsPage()
            }
        })
        featureContent.addView(featureRow(getString(R.string.voice_wake_engine), wakeProviderLabel(config.wakeProvider), R.drawable.ic_agent_node, getString(R.string.common_select)).apply {
            setOnClickListener {
                val openWakeWord = getString(R.string.voice_wake_engine_openwakeword)
                val androidAsr = getString(R.string.voice_wake_engine_android_asr)
                showChoiceDialog(getString(R.string.voice_wake_engine), listOf(openWakeWord, androidAsr), wakeProviderLabel(config.wakeProvider)) {
                    val provider = if (it == openWakeWord) {
                        VoiceAssistantSettings.WAKE_PROVIDER_OPEN_WAKE_WORD
                    } else {
                        VoiceAssistantSettings.WAKE_PROVIDER_ANDROID_ASR
                    }
                    VoiceAssistantSettings.setWakeProvider(this@MainActivity, provider)
                    showVoiceAssistantSettingsPage()
                }
            }
        })
        featureContent.addView(featureRow(getString(R.string.voice_wake_words), config.wakeWords.joinToString(", "), R.drawable.ic_protocol_link, getString(R.string.common_edit)).apply {
            setOnClickListener {
                showTextSettingDialog(getString(R.string.voice_wake_words), config.wakeWords.joinToString(", ")) {
                    VoiceAssistantSettings.setWakeWords(this@MainActivity, it)
                    showVoiceAssistantSettingsPage()
                }
            }
        })
        featureContent.addView(featureRow(getString(R.string.voice_openwakeword_model), config.wakeModel, R.drawable.ic_protocol_link, getString(R.string.common_select)).apply {
            setOnClickListener {
                showChoiceDialog(getString(R.string.voice_openwakeword_model), VoiceAssistantSettings.SUPPORTED_WAKE_MODELS, config.wakeModel) {
                    VoiceAssistantSettings.setWakeModel(this@MainActivity, it)
                    showVoiceAssistantSettingsPage()
                }
            }
        })
        featureContent.addView(featureRow(getString(R.string.voice_wake_threshold), "%.2f".format(Locale.US, config.wakeThreshold), R.drawable.ic_security_shield, getString(R.string.common_edit)).apply {
            setOnClickListener {
                showTextSettingDialog(getString(R.string.voice_wake_threshold), "%.2f".format(Locale.US, config.wakeThreshold)) {
                    VoiceAssistantSettings.setWakeThreshold(this@MainActivity, it.toFloatOrNull() ?: config.wakeThreshold)
                    showVoiceAssistantSettingsPage()
                }
            }
        })
        addSectionTitle(getString(R.string.voice_section_asr))
        featureContent.addView(featureRow(
            getString(R.string.voice_asr_provider),
            getString(R.string.voice_asr_provider_local_whisper, WhisperModelManager.model(config.asrModel).displayName),
            R.drawable.ic_agent_node,
            getString(R.string.common_select)
        ).apply {
            setOnClickListener { showAsrProviderPage() }
        })
        featureContent.addView(featureRow(getString(R.string.voice_asr_language), config.asrLanguage, R.drawable.ic_protocol_link, getString(R.string.common_select)).apply {
            setOnClickListener {
                showChoiceDialog(getString(R.string.voice_asr_language), listOf("zh-CN", "en-US", "zh-HK", "zh-TW"), config.asrLanguage) {
                    VoiceAssistantSettings.setAsrLanguage(this@MainActivity, it)
                    configureAndroidTtsLanguage()
                    showVoiceAssistantSettingsPage()
                }
            }
        })

        addSectionTitle(getString(R.string.voice_section_tts))
        featureContent.addView(featureRow(getString(R.string.voice_tts_provider), ttsProviderLabel(config.ttsProvider), R.drawable.ic_send_plane, getString(R.string.common_select)).apply {
            setOnClickListener {
                val microsoft = getString(R.string.voice_tts_microsoft)
                val androidTts = getString(R.string.voice_tts_android)
                showChoiceDialog(getString(R.string.voice_tts_provider), listOf(microsoft, androidTts), ttsProviderLabel(config.ttsProvider)) {
                    val provider = if (it == microsoft) VoiceAssistantSettings.PROVIDER_MICROSOFT_EDGE else VoiceAssistantSettings.PROVIDER_ANDROID
                    VoiceAssistantSettings.setTtsProvider(this@MainActivity, provider)
                    showVoiceAssistantSettingsPage()
                }
            }
        })
        featureContent.addView(featureRow(getString(R.string.voice_microsoft_voice), config.microsoftVoice, R.drawable.ic_protocol_link, getString(R.string.common_edit)).apply {
            setOnClickListener {
                showTextSettingDialog(getString(R.string.voice_microsoft_voice), config.microsoftVoice) {
                    VoiceAssistantSettings.setMicrosoftVoice(this@MainActivity, it)
                    showVoiceAssistantSettingsPage()
                }
            }
        })
        featureContent.addView(featureRow(getString(R.string.voice_welcome_text), config.welcomeText, R.drawable.ic_send_plane, getString(R.string.common_edit)).apply {
            setOnClickListener {
                showTextSettingDialog(getString(R.string.voice_welcome_text), config.welcomeText) {
                    VoiceAssistantSettings.setWelcomeText(this@MainActivity, it)
                    showVoiceAssistantSettingsPage()
                }
            }
        })
        featureContent.addView(featureRow(getString(R.string.voice_speak_replies), getString(R.string.voice_speak_replies_subtitle), R.drawable.ic_send_plane, onOffLabel(config.speakReplies)).apply {
            setOnClickListener {
                VoiceAssistantSettings.setSpeakReplies(this@MainActivity, !config.speakReplies)
                showVoiceAssistantSettingsPage()
            }
        })

        addSectionTitle(getString(R.string.voice_section_target))
        featureContent.addView(featureRow(
            getString(R.string.voice_routing_mode),
            getString(R.string.voice_routing_mode_subtitle),
            R.drawable.ic_agent_node,
            voiceRoutingModeLabel(config.routingMode)
        ).apply {
            setOnClickListener {
                val nativeAgent = getString(R.string.voice_routing_native_agent)
                val contactChat = getString(R.string.voice_routing_contact)
                showChoiceDialog(
                    getString(R.string.voice_routing_mode),
                    listOf(nativeAgent, contactChat),
                    voiceRoutingModeLabel(config.routingMode)
                ) { selected ->
                    VoiceAssistantSettings.setRoutingMode(
                        this@MainActivity,
                        if (selected == nativeAgent) VoiceAssistantSettings.ROUTING_MODE_NATIVE_AGENT
                        else VoiceAssistantSettings.ROUTING_MODE_CONTACT
                    )
                    showVoiceAssistantSettingsPage()
                }
            }
        })
        val targetContact = voiceAssistantTargetContact(config)
        val targetTitle = if (config.routingMode == VoiceAssistantSettings.ROUTING_MODE_NATIVE_AGENT) {
            getString(R.string.voice_stt_target)
        } else {
            getString(R.string.voice_default_target)
        }
        featureContent.addView(featureRow(targetTitle, targetContact.name, R.drawable.ic_avatar_hermes, getString(R.string.common_select)).apply {
            setOnClickListener {
                val contacts = storedContacts().filter { contact ->
                    if (config.routingMode == VoiceAssistantSettings.ROUTING_MODE_NATIVE_AGENT) {
                        AppStore.usesPcConnectorTunnel(this@MainActivity, contact.id) &&
                            AppStore.outgoingTopicForContact(this@MainActivity, contact.id) != null
                    } else {
                        AppStore.canCommunicateWith(this@MainActivity, contact.id)
                    }
                }.ifEmpty { listOf(CONTACT_HERMES) }
                val labels = contacts.map { "${it.name} (${it.id})" }
                val current = contacts.indexOfFirst { it.id == targetContact.id }.coerceAtLeast(0)
                android.app.AlertDialog.Builder(this@MainActivity)
                    .setTitle(targetTitle)
                    .setSingleChoiceItems(labels.toTypedArray(), current) { dialog, which ->
                        VoiceAssistantSettings.setTargetContact(this@MainActivity, contacts[which].id)
                        dialog.dismiss()
                        showVoiceAssistantSettingsPage()
                    }
                    .setNegativeButton(getString(R.string.common_cancel), null)
                    .show()
            }
        })
    }

    private fun showAsrProviderPage() {
        handler.removeCallbacks(asrModelDownloadPoll)
        val config = VoiceAssistantSettings.get(this)
        val selected = WhisperModelManager.model(config.asrModel)
        showFeaturePage(getString(R.string.voice_asr_provider))
        setFeatureBackAction { showVoiceAssistantSettingsPage() }
        featureContent.addView(featureHeroCard(
            getString(R.string.voice_asr_local_title),
            getString(R.string.voice_asr_local_subtitle),
            R.drawable.ic_agent_node,
            "#14C66A",
            selected.displayName
        ))
        addSectionTitle(getString(R.string.voice_asr_model_section))
        var hasActiveDownload = false
        WhisperModelManager.models.forEach { model ->
            val state = WhisperModelManager.downloadState(this, model)
            val available = WhisperModelManager.isAvailable(this, model)
            val isSelected = selected.id == model.id
            val isDownloading = state.status == DownloadManager.STATUS_PENDING ||
                state.status == DownloadManager.STATUS_RUNNING ||
                state.status == DownloadManager.STATUS_PAUSED
            if (isDownloading && pendingAsrModelSelection == null) pendingAsrModelSelection = model.id
            hasActiveDownload = hasActiveDownload || isDownloading
            val subtitle = if (model.bundled) {
                getString(R.string.voice_asr_model_bundled, model.sizeLabel)
            } else {
                getString(R.string.voice_asr_model_download_size, model.sizeLabel)
            }
            val action = when {
                isSelected -> getString(R.string.section_current)
                available -> getString(R.string.settings_language_use)
                isDownloading -> if (state.progress > 0) "${state.progress}%" else getString(R.string.voice_asr_model_waiting)
                state.status == DownloadManager.STATUS_FAILED -> getString(R.string.common_retry)
                else -> getString(R.string.voice_asr_model_download)
            }
            featureContent.addView(featureRow(model.displayName, subtitle, R.drawable.ic_local_model, action).apply {
                isClickable = !isSelected && !isDownloading
                isFocusable = isClickable
                setOnClickListener(if (!isSelected && !isDownloading) View.OnClickListener {
                    if (available) {
                        VoiceAssistantSettings.setAsrModel(this@MainActivity, model.id)
                        showAsrProviderPage()
                    } else {
                        runCatching { WhisperModelManager.enqueue(this@MainActivity, model) }
                            .onSuccess {
                                pendingAsrModelSelection = model.id
                                Toast.makeText(this@MainActivity, getString(R.string.voice_asr_model_download_started, model.displayName), Toast.LENGTH_SHORT).show()
                                handler.post(asrModelDownloadPoll)
                            }
                            .onFailure {
                                Toast.makeText(this@MainActivity, getString(R.string.voice_asr_model_download_failed), Toast.LENGTH_LONG).show()
                            }
                    }
                } else null)
            })
        }
        featureContent.addView(TextView(this).apply {
            text = getString(R.string.voice_asr_model_mirror_note)
            setTextColor(getColorCompat(R.color.text_secondary))
            textSize = 12f
            setPadding(dp(4), dp(4), dp(4), dp(18))
        })
        if (hasActiveDownload) handler.postDelayed(asrModelDownloadPoll, 1_000L)
    }

    private fun wakeProviderLabel(provider: String): String =
        if (provider == VoiceAssistantSettings.WAKE_PROVIDER_ANDROID_ASR) getString(R.string.voice_wake_engine_android_asr) else getString(R.string.voice_wake_engine_openwakeword)

    private fun ttsProviderLabel(provider: String): String =
        if (provider == VoiceAssistantSettings.PROVIDER_ANDROID) getString(R.string.voice_tts_android) else getString(R.string.voice_tts_microsoft)

    private fun voiceRoutingModeLabel(mode: String): String =
        if (mode == VoiceAssistantSettings.ROUTING_MODE_CONTACT) {
            getString(R.string.voice_routing_contact)
        } else {
            getString(R.string.voice_routing_native_agent)
        }

    private fun onOffLabel(enabled: Boolean): String =
        getString(if (enabled) R.string.common_on else R.string.common_off)

    private fun showTextSettingDialog(title: String, initial: String, onSave: (String) -> Unit) {
        val input = EditText(this).apply {
            setText(initial)
            selectAll()
            minLines = if (initial.length > 40) 3 else 1
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            setPadding(dp(18), dp(10), dp(18), dp(10))
        }
        android.app.AlertDialog.Builder(this)
            .setTitle(title)
            .setView(input)
            .setPositiveButton(getString(R.string.common_save)) { _, _ -> onSave(input.text?.toString()?.trim().orEmpty()) }
            .setNegativeButton(getString(R.string.common_cancel), null)
            .show()
    }

    private fun showChoiceDialog(title: String, options: List<String>, current: String, onChoose: (String) -> Unit) {
        val selected = options.indexOf(current).coerceAtLeast(0)
        android.app.AlertDialog.Builder(this)
            .setTitle(title)
            .setSingleChoiceItems(options.toTypedArray(), selected) { dialog, which ->
                onChoose(options[which])
                dialog.dismiss()
            }
            .setNegativeButton(getString(R.string.common_cancel), null)
            .show()
    }

    private fun showOnDeviceAgentFeaturePage() {
        showFeaturePage(getString(R.string.on_device_agent_title))
        val safetySettings = mobileNativeAgent.safetySettings()
        val modelPlannerSettings = mobileNativeAgent.modelPlannerSettings()
        featureContent.addView(featureHeroCard(
            getString(R.string.on_device_agent_hero_title),
            getString(R.string.on_device_agent_hero_subtitle),
            R.drawable.ic_agent_node,
            "#5B6CFF",
            getString(
                if (safetySettings.executionPaused) R.string.on_device_agent_status_paused
                else R.string.on_device_agent_status_running
            )
        ))
        addSectionTitle(getString(R.string.on_device_agent_section_execution))
        featureContent.addView(featureValueRow(
            getString(R.string.on_device_agent_permission_mode),
            getString(R.string.on_device_agent_permission_mode_subtitle),
            R.drawable.ic_security_shield,
            permissionModeLabel(safetySettings.permissionMode)
        ).apply {
            setOnClickListener { cycleAgentPermissionMode() }
        })
        featureContent.addView(featureSwitchRow(
            getString(R.string.on_device_agent_high_risk_guard),
            getString(R.string.on_device_agent_high_risk_guard_subtitle),
            R.drawable.ic_security_shield,
            safetySettings.highRiskGuard
        ).apply {
            setOnClickListener { toggleAgentHighRiskGuard() }
        })
        featureContent.addView(featureSwitchRow(
            getString(R.string.on_device_agent_memory_capture),
            getString(R.string.on_device_agent_memory_capture_subtitle),
            R.drawable.ic_agent_node,
            safetySettings.memoryCapture
        ).apply {
            setOnClickListener { toggleAgentMemoryCapture() }
        })
        val memorySnapshot = mobileNativeAgent.memorySnapshot()
        featureContent.addView(featureValueRow(
            getString(R.string.agent_memory_title),
            getString(R.string.agent_memory_management_subtitle),
            R.drawable.ic_agent_node,
            getString(R.string.agent_memory_value, memorySnapshot.activeCount, memorySnapshot.conflicts.size)
        ).apply {
            setOnClickListener {
                showAgentMemoryPage()
                setFeatureBackAction { showOnDeviceAgentFeaturePage() }
            }
        })
        featureContent.addView(featureSwitchRow(
            getString(R.string.on_device_agent_execution_pause),
            getString(R.string.on_device_agent_execution_pause_subtitle),
            R.drawable.ic_security_shield,
            safetySettings.executionPaused
        ).apply {
            setOnClickListener { toggleAgentExecutionPaused() }
        })
        addSectionTitle(getString(R.string.on_device_agent_section_intelligence))
        featureContent.addView(featureSwitchRow(
            getString(R.string.on_device_agent_model_planner),
            getString(R.string.on_device_agent_model_planner_subtitle),
            R.drawable.ic_agent_node,
            modelPlannerSettings.enabled
        ).apply {
            setOnClickListener { toggleAgentModelPlanner() }
        })
        featureContent.addView(featureSwitchRow(
            getString(R.string.on_device_agent_model_screen_text),
            getString(R.string.on_device_agent_model_screen_text_subtitle),
            R.drawable.ic_scan,
            modelPlannerSettings.shareScreenText
        ).apply {
            setOnClickListener { toggleAgentModelScreenText() }
        })
        featureContent.addView(featureValueRow(
            getString(R.string.on_device_agent_model_source),
            getString(R.string.on_device_agent_model_source_subtitle),
            R.drawable.ic_protocol_link,
            agentModelPlannerSourceLabel(modelPlannerSettings.cloudContactId)
        ).apply {
            setOnClickListener { showAgentModelPlannerSourceDialog() }
        })
        featureContent.addView(featureSwitchRow(
            getString(R.string.on_device_agent_dynamic_replanning),
            getString(R.string.on_device_agent_dynamic_replanning_subtitle),
            R.drawable.ic_agent_node,
            modelPlannerSettings.dynamicReplanning
        ).apply {
            setOnClickListener { toggleAgentDynamicReplanning() }
        })
        featureContent.addView(featureValueRow(
            getString(R.string.on_device_agent_max_replans),
            getString(R.string.on_device_agent_max_replans_subtitle),
            R.drawable.ic_agent_node,
            modelPlannerSettings.maxReplans.toString()
        ).apply {
            setOnClickListener { cycleAgentModelMaxReplans() }
        })
        featureContent.addView(featureSwitchRow(
            getString(R.string.on_device_agent_multi_agent_coordination),
            getString(R.string.on_device_agent_multi_agent_coordination_subtitle),
            R.drawable.ic_protocol_link,
            modelPlannerSettings.multiAgentCoordination
        ).apply {
            setOnClickListener { toggleMultiAgentCoordination() }
        })
        featureContent.addView(featureSwitchRow(
            getString(R.string.on_device_agent_share_agent_outputs),
            getString(R.string.on_device_agent_share_agent_outputs_subtitle),
            R.drawable.ic_security_shield,
            modelPlannerSettings.shareAgentOutputsWithPlanner
        ).apply {
            setOnClickListener { toggleShareAgentOutputsWithPlanner() }
        })
        featureContent.addView(featureValueRow(
            getString(R.string.on_device_agent_max_agent_hops),
            getString(R.string.on_device_agent_max_agent_hops_subtitle),
            R.drawable.ic_protocol_link,
            modelPlannerSettings.maxAgentHops.toString()
        ).apply {
            setOnClickListener { cycleMaxAgentHops() }
        })
        featureContent.addView(featureValueRow(
            getString(R.string.on_device_agent_max_tool_calls),
            getString(R.string.on_device_agent_max_tool_calls_subtitle),
            R.drawable.ic_security_shield,
            modelPlannerSettings.maxToolCalls.toString()
        ).apply {
            setOnClickListener { cycleMaxToolCalls() }
        })
        featureContent.addView(featureValueRow(
            getString(R.string.on_device_agent_model_max_actions),
            getString(R.string.on_device_agent_model_max_actions_subtitle),
            R.drawable.ic_agent_node,
            modelPlannerSettings.maxActions.toString()
        ).apply {
            setOnClickListener { cycleAgentModelMaxActions() }
        })
        addSectionTitle(getString(R.string.on_device_agent_section_capabilities))
        val visualScene = mobileNativeAgent.snapshot().currentScreen.visualScene
        featureContent.addView(featureValueRow(
            getString(R.string.on_device_agent_visual_model),
            getString(R.string.on_device_agent_visual_model_subtitle),
            R.drawable.ic_scan,
            if (visualScene.available) visualScene.modelProfile else getString(R.string.permission_needs_setup)
        ).apply {
            setOnClickListener { startAgentScreenUnderstanding() }
        })
        featureContent.addView(featureValueRow(
            getString(R.string.agent_app_adapters_title),
            getString(R.string.agent_app_adapters_subtitle),
            R.drawable.ic_protocol_link,
            getString(R.string.agent_app_adapters_count, agentAdapterReadiness().count { it.value })
        ).apply {
            setOnClickListener {
                showAgentAppAdaptersPage()
                setFeatureBackAction { showOnDeviceAgentFeaturePage() }
            }
        })
        featureContent.addView(featureSwitchRow(
            getString(R.string.on_device_agent_allow_screen_observation),
            getString(R.string.on_device_agent_allow_screen_observation_subtitle),
            R.drawable.ic_scan,
            safetySettings.screenObservationAllowed
        ).apply {
            setOnClickListener { toggleAgentScreenObservation() }
        })
        featureContent.addView(featureSwitchRow(
            getString(R.string.on_device_agent_allow_local_actions),
            getString(R.string.on_device_agent_allow_local_actions_subtitle),
            R.drawable.ic_agent_node,
            safetySettings.localActionsAllowed
        ).apply {
            setOnClickListener { toggleAgentLocalActions() }
        })
        featureContent.addView(featureSwitchRow(
            getString(R.string.on_device_agent_allow_connectors),
            getString(R.string.on_device_agent_allow_connectors_subtitle),
            R.drawable.ic_protocol_link,
            safetySettings.connectorCallsAllowed
        ).apply {
            setOnClickListener { toggleAgentConnectorCalls() }
        })
        featureContent.addView(featureSwitchRow(
            getString(R.string.on_device_agent_allow_devices),
            getString(R.string.on_device_agent_allow_devices_subtitle),
            R.drawable.ic_device_node,
            safetySettings.deviceControlAllowed
        ).apply {
            setOnClickListener { toggleAgentDeviceControl() }
        })
        addSectionTitle(getString(R.string.on_device_agent_section_permissions))
        val screenAccessAllowed = SignalASIAccessibilityService.isActive()
        val notificationAccessAllowed = SignalASINotificationListenerService.currentContext().hasAccess
        val microphoneAllowed = checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        val cameraAllowed = checkSelfPermission(android.Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        val locationAllowed = checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            checkSelfPermission(android.Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        featureContent.addView(featureRow(
            getString(R.string.on_device_agent_screen_access),
            getString(R.string.on_device_agent_screen_access_subtitle),
            R.drawable.ic_agent_node,
            if (screenAccessAllowed) getString(R.string.permission_allowed) else getString(R.string.permission_needs_setup)
        ).apply {
            setOnClickListener { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        })
        featureContent.addView(featureRow(
            getString(R.string.on_device_agent_visual_capture),
            getString(R.string.on_device_agent_visual_capture_subtitle),
            R.drawable.ic_scan,
            if (AgentScreenCaptureService.isActive()) getString(R.string.permission_allowed)
            else getString(R.string.permission_needs_setup)
        ).apply {
            setOnClickListener {
                if (AgentScreenCaptureService.isActive()) {
                    AgentScreenCaptureService.stop(this@MainActivity)
                    showOnDeviceAgentFeaturePage()
                } else {
                    startAgentScreenUnderstanding()
                }
            }
        })
        featureContent.addView(featureRow(
            getString(R.string.on_device_agent_microphone),
            getString(R.string.on_device_agent_microphone_subtitle),
            R.drawable.ic_agent_node,
            getString(if (microphoneAllowed) R.string.permission_allowed else R.string.permission_needs_setup)
        ).apply {
            if (!microphoneAllowed) setOnClickListener {
                requestPermissions(arrayOf(android.Manifest.permission.RECORD_AUDIO), REQUEST_CONTROL_CENTER_PERMISSION)
            }
        })
        featureContent.addView(featureRow(
            getString(R.string.on_device_agent_camera),
            getString(R.string.on_device_agent_camera_subtitle),
            R.drawable.ic_scan,
            getString(if (cameraAllowed) R.string.permission_allowed else R.string.permission_needs_setup)
        ).apply {
            if (!cameraAllowed) setOnClickListener {
                requestPermissions(arrayOf(android.Manifest.permission.CAMERA), REQUEST_CONTROL_CENTER_PERMISSION)
            }
        })
        featureContent.addView(featureRow(
            getString(R.string.on_device_agent_location),
            getString(R.string.on_device_agent_location_subtitle),
            R.drawable.ic_device_node,
            getString(if (locationAllowed) R.string.permission_while_using else R.string.permission_needs_setup)
        ).apply {
            if (!locationAllowed) setOnClickListener {
                requestPermissions(
                    arrayOf(
                        android.Manifest.permission.ACCESS_FINE_LOCATION,
                        android.Manifest.permission.ACCESS_COARSE_LOCATION
                    ),
                    REQUEST_CONTROL_CENTER_PERMISSION
                )
            }
        })
        featureContent.addView(featureRow(
            getString(R.string.on_device_agent_notifications),
            getString(R.string.on_device_agent_notifications_subtitle),
            R.drawable.ic_agent_node,
            if (notificationAccessAllowed) getString(R.string.permission_allowed) else getString(R.string.permission_needs_setup)
        ).apply {
            setOnClickListener { startActivity(Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS")) }
        })
    }

    private fun cycleAgentPermissionMode() {
        val current = mobileNativeAgent.safetySettings().permissionMode
        val next = nextAgentPermissionMode(current)
        mobileNativeAgent.updatePermissionMode(next)
        Toast.makeText(this, getString(R.string.on_device_agent_mode_changed, permissionModeLabel(next)), Toast.LENGTH_SHORT).show()
        showOnDeviceAgentFeaturePage()
    }

    private fun showAgentMemoryPage(filterKinds: Set<AgentMemoryKind> = emptySet()) {
        showFeaturePage(getString(R.string.agent_memory_title))
        val sourceSnapshot = mobileNativeAgent.memorySnapshot()
        val snapshot = if (filterKinds.isEmpty()) {
            sourceSnapshot
        } else {
            AgentMemorySnapshot(
                activeItems = sourceSnapshot.activeItems.filter { it.kind in filterKinds },
                conflicts = sourceSnapshot.conflicts.filter { it.kind in filterKinds },
                historyItems = sourceSnapshot.historyItems.filter { it.kind in filterKinds }
            )
        }
        featureContent.addView(featureHeroCard(
            getString(R.string.agent_memory_hero_title),
            getString(
                R.string.agent_memory_hero_subtitle,
                snapshot.activeCount,
                snapshot.conflicts.size,
                snapshot.historyCount
            ),
            R.drawable.ic_agent_node,
            "#5B6CFF",
            getString(
                if (mobileNativeAgent.safetySettings().memoryCapture) R.string.common_on
                else R.string.common_off
            )
        ))

        addSectionTitle(getString(R.string.agent_memory_section_conflicts))
        if (snapshot.conflicts.isEmpty()) {
            featureContent.addView(featureRow(
                getString(R.string.agent_memory_no_conflicts),
                getString(R.string.agent_memory_no_conflicts_subtitle),
                R.drawable.ic_security_shield,
                ""
            ))
        } else {
            snapshot.conflicts.forEach { conflict ->
                featureContent.addView(featureRow(
                    conflict.key.ifBlank { memoryKindLabel(conflict.kind) },
                    getString(
                        R.string.agent_memory_conflict_subtitle,
                        memoryKindLabel(conflict.kind),
                        conflict.candidates.size
                    ),
                    R.drawable.ic_security_shield,
                    getString(R.string.agent_memory_review)
                ).apply {
                    setOnClickListener { showAgentMemoryConflictDialog(conflict, filterKinds) }
                })
            }
        }

        addSectionTitle(getString(R.string.agent_memory_section_saved))
        if (snapshot.activeItems.isEmpty()) {
            featureContent.addView(featureRow(
                getString(R.string.agent_memory_empty),
                getString(R.string.agent_memory_empty_subtitle),
                R.drawable.ic_agent_node,
                ""
            ))
        } else {
            snapshot.activeItems.forEach { item ->
                val key = item.key.ifBlank { getString(R.string.agent_memory_key_none) }
                val action = getString(
                    if (item.important) R.string.agent_memory_pinned else R.string.common_edit
                )
                featureContent.addView(featureRow(
                    item.value.replace(Regex("\\s+"), " ").take(80),
                    getString(
                        R.string.agent_memory_item_subtitle,
                        memoryKindLabel(item.kind),
                        item.version,
                        key
                    ),
                    R.drawable.ic_agent_node,
                    action
                ).apply {
                    setOnClickListener { showAgentMemoryItemActions(item, filterKinds) }
                })
            }
        }

        if (snapshot.historyItems.isNotEmpty()) {
            addSectionTitle(getString(R.string.agent_memory_section_history))
            snapshot.historyItems.take(20).forEach { item ->
                featureContent.addView(featureRow(
                    item.value.replace(Regex("\\s+"), " ").take(80),
                    getString(
                        R.string.agent_memory_history_subtitle,
                        memoryKindLabel(item.kind),
                        item.version,
                        memorySourceLabel(item.source)
                    ),
                    R.drawable.ic_protocol_link,
                    ""
                ))
            }
        }
    }

    private fun showAgentAppAdaptersPage() {
        showFeaturePage(getString(R.string.agent_app_adapters_title))
        val readiness = agentAdapterReadiness()
        val accessibilityReady = SignalASIAccessibilityService.isActive()
        val notificationReady = SignalASINotificationListenerService.currentContext().hasAccess
        featureContent.addView(featureHeroCard(
            getString(R.string.agent_app_adapters_hero_title),
            getString(R.string.agent_app_adapters_hero_subtitle),
            R.drawable.ic_protocol_link,
            "#16A085",
            getString(R.string.agent_app_adapters_count, readiness.count { it.value })
        ))
        addSectionTitle(getString(R.string.agent_app_adapters_section))
        featureContent.addView(featureRow(
            getString(R.string.agent_adapter_wechat),
            getString(
                R.string.agent_adapter_wechat_subtitle,
                onOffLabel(accessibilityReady),
                onOffLabel(notificationReady)
            ),
            R.drawable.ic_tab_chat,
            getString(
                if (readiness.getValue("wechat")) R.string.permission_allowed
                else R.string.permission_needs_setup
            )
        ))
        featureContent.addView(featureRow(
            getString(R.string.agent_adapter_sms),
            getString(R.string.agent_adapter_sms_subtitle),
            R.drawable.ic_tab_chat,
            getString(if (readiness.getValue("sms")) R.string.permission_allowed else R.string.permission_needs_setup)
        ))
        featureContent.addView(featureRow(
            getString(R.string.agent_adapter_phone),
            getString(R.string.agent_adapter_phone_subtitle),
            R.drawable.ic_device_node,
            getString(if (readiness.getValue("phone")) R.string.permission_allowed else R.string.permission_needs_setup)
        ))
        featureContent.addView(featureRow(
            getString(R.string.agent_adapter_browser),
            getString(R.string.agent_adapter_browser_subtitle),
            R.drawable.ic_tab_discover,
            getString(if (readiness.getValue("browser")) R.string.permission_allowed else R.string.permission_needs_setup)
        ))
        featureContent.addView(featureRow(
            getString(R.string.agent_adapter_files),
            getString(R.string.agent_adapter_files_subtitle),
            R.drawable.ic_protocol_link,
            getString(if (readiness.getValue("files")) R.string.permission_allowed else R.string.permission_needs_setup)
        ))
    }

    private fun agentAdapterReadiness(): LinkedHashMap<String, Boolean> {
        val accessibilityReady = SignalASIAccessibilityService.isActive()
        val notificationReady = SignalASINotificationListenerService.currentContext().hasAccess
        val wechatInstalled = packageManager.getLaunchIntentForPackage("com.tencent.mm") != null
        val smsReady = Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:"))
            .resolveActivity(packageManager) != null
        val phoneReady = packageManager.hasSystemFeature(PackageManager.FEATURE_TELEPHONY) &&
            Intent(Intent.ACTION_DIAL, Uri.parse("tel:"))
                .resolveActivity(packageManager) != null
        val browserReady = Intent(Intent.ACTION_VIEW, Uri.parse("https://signalasi.org"))
            .resolveActivity(packageManager) != null
        val filesReady = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            type = "*/*"
            addCategory(Intent.CATEGORY_OPENABLE)
        }.resolveActivity(packageManager) != null
        return linkedMapOf(
            "wechat" to (wechatInstalled && accessibilityReady && notificationReady),
            "sms" to smsReady,
            "phone" to phoneReady,
            "browser" to browserReady,
            "files" to filesReady
        )
    }

    private fun showAgentKnowledgePage(query: String = "") {
        showFeaturePage(getString(R.string.agent_knowledge_title))
        val stats = mobileNativeAgent.snapshot().runtimeContext.knowledgeStats
        val sourceGroups = mobileNativeAgent.knowledgeSourceGroups()
        featureContent.addView(featureHeroCard(
            getString(R.string.agent_knowledge_hero_title),
            getString(R.string.agent_knowledge_hero_subtitle),
            R.drawable.ic_protocol_link,
            "#08A88A",
            getString(R.string.agent_knowledge_source_badge, stats.sourceCount)
        ))

        addSectionTitle(getString(R.string.agent_knowledge_section_actions))
        featureContent.addView(featureRow(
            getString(R.string.agent_knowledge_import),
            getString(R.string.agent_knowledge_import_subtitle),
            R.drawable.ic_protocol_link,
            getString(R.string.agent_knowledge_add)
        ).apply { setOnClickListener { openAgentKnowledgeImportPicker() } })
        featureContent.addView(featureRow(
            getString(R.string.agent_knowledge_search),
            if (query.isBlank()) getString(R.string.agent_knowledge_search_subtitle) else query,
            R.drawable.ic_tab_discover,
            getString(R.string.agent_knowledge_search_action)
        ).apply {
            setOnClickListener {
                showTextSettingDialog(getString(R.string.agent_knowledge_search), query) { value ->
                    showAgentKnowledgePage(value)
                }
            }
        })

        if (query.isNotBlank()) {
            val hits = mobileNativeAgent.searchKnowledge(query)
            addSectionTitle(getString(R.string.agent_knowledge_section_results, hits.size))
            if (hits.isEmpty()) {
                featureContent.addView(featureValueRow(
                    getString(R.string.agent_knowledge_no_results),
                    query,
                    R.drawable.ic_tab_discover,
                    ""
                ))
            } else {
                hits.forEachIndexed { index, hit ->
                    featureContent.addView(featureValueRow(
                        "[${index + 1}] ${hit.item.title.substringBeforeLast(" [")}",
                        hit.excerpt,
                        R.drawable.ic_protocol_link,
                        getString(R.string.agent_knowledge_score, hit.score)
                    ))
                }
            }
        }

        addSectionTitle(getString(R.string.agent_knowledge_section_sources, sourceGroups.size))
        if (sourceGroups.isEmpty()) {
            featureContent.addView(featureValueRow(
                getString(R.string.agent_knowledge_empty_title),
                getString(R.string.agent_knowledge_empty_subtitle),
                R.drawable.ic_protocol_link,
                ""
            ))
        } else {
            sourceGroups.forEach { group ->
                featureContent.addView(featureValueRow(
                    group.title,
                    getString(
                        R.string.agent_knowledge_source_subtitle,
                        group.chunkCount,
                        knowledgeCloudAccessLabel(group.cloudAccess),
                        knowledgeAgentAccessLabel(group.agentAccess)
                    ),
                    R.drawable.ic_protocol_link,
                    getString(R.string.agent_knowledge_manage)
                ).apply { setOnClickListener { showAgentKnowledgeSourceActions(group) } })
            }
        }

        val audit = mobileNativeAgent.knowledgeAccessAudit(limit = 8)
        if (audit.isNotEmpty()) {
            addSectionTitle(getString(R.string.agent_knowledge_section_audit))
            audit.forEach { entry ->
                val time = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(entry.timestampMillis))
                featureContent.addView(featureValueRow(
                    entry.targetId,
                    getString(
                        R.string.agent_knowledge_audit_subtitle,
                        entry.sourceCount,
                        entry.evidenceModes.joinToString(" / ") { knowledgeEvidenceModeLabel(it) },
                        entry.blockedMatchCount
                    ),
                    R.drawable.ic_security_shield,
                    time
                ))
            }
        }
    }

    private fun showAgentKnowledgeSourceActions(group: AgentKnowledgeSourceGroup) {
        val options = arrayOf(
            getString(R.string.agent_knowledge_cloud_access),
            getString(R.string.agent_knowledge_agent_access)
        )
        android.app.AlertDialog.Builder(this)
            .setTitle(group.title)
            .setItems(options) { _, which ->
                if (which == 0) showAgentKnowledgeCloudAccessDialog(group)
                else showAgentKnowledgeAgentAccessDialog(group)
            }
            .setNegativeButton(getString(R.string.common_cancel), null)
            .show()
    }

    private fun showAgentKnowledgeCloudAccessDialog(group: AgentKnowledgeSourceGroup) {
        val policies = AgentKnowledgeCloudAccess.entries
        val labels = policies.map(::knowledgeCloudAccessLabel)
        android.app.AlertDialog.Builder(this)
            .setTitle(getString(R.string.agent_knowledge_cloud_access))
            .setSingleChoiceItems(labels.toTypedArray(), policies.indexOf(group.cloudAccess)) { dialog, which ->
                mobileNativeAgent.updateKnowledgeSourceAccess(
                    group.itemIds,
                    policies[which],
                    group.agentAccess,
                    group.allowedAgentIds
                )
                dialog.dismiss()
                showAgentKnowledgePage()
            }
            .setNegativeButton(getString(R.string.common_cancel), null)
            .show()
    }

    private fun showAgentKnowledgeAgentAccessDialog(group: AgentKnowledgeSourceGroup) {
        val policies = AgentKnowledgeAgentAccess.entries
        val labels = policies.map(::knowledgeAgentAccessLabel)
        android.app.AlertDialog.Builder(this)
            .setTitle(getString(R.string.agent_knowledge_agent_access))
            .setSingleChoiceItems(labels.toTypedArray(), policies.indexOf(group.agentAccess)) { dialog, which ->
                dialog.dismiss()
                val selected = policies[which]
                if (selected == AgentKnowledgeAgentAccess.SELECTED_AGENTS) {
                    showTextSettingDialog(
                        getString(R.string.agent_knowledge_selected_agents),
                        group.allowedAgentIds.joinToString(", ")
                    ) { rawIds ->
                        mobileNativeAgent.updateKnowledgeSourceAccess(
                            group.itemIds,
                            group.cloudAccess,
                            selected,
                            rawIds.split(',').map { it.trim() }.filter { it.isNotBlank() }
                        )
                        showAgentKnowledgePage()
                    }
                } else {
                    mobileNativeAgent.updateKnowledgeSourceAccess(
                        group.itemIds,
                        group.cloudAccess,
                        selected
                    )
                    showAgentKnowledgePage()
                }
            }
            .setNegativeButton(getString(R.string.common_cancel), null)
            .show()
    }

    private fun knowledgeCloudAccessLabel(value: AgentKnowledgeCloudAccess): String = getString(
        when (value) {
            AgentKnowledgeCloudAccess.DENY -> R.string.agent_knowledge_access_local_only
            AgentKnowledgeCloudAccess.SUMMARY_ONLY -> R.string.agent_knowledge_access_summary
            AgentKnowledgeCloudAccess.FULL -> R.string.agent_knowledge_access_full
        }
    )

    private fun knowledgeAgentAccessLabel(value: AgentKnowledgeAgentAccess): String = getString(
        when (value) {
            AgentKnowledgeAgentAccess.LOCAL_ONLY -> R.string.agent_knowledge_agent_local_only
            AgentKnowledgeAgentAccess.SELECTED_AGENTS -> R.string.agent_knowledge_agent_selected
            AgentKnowledgeAgentAccess.ANY_PAIRED_AGENT -> R.string.agent_knowledge_agent_any
        }
    )

    private fun knowledgeEvidenceModeLabel(value: AgentKnowledgeEvidenceMode): String = getString(
        when (value) {
            AgentKnowledgeEvidenceMode.FULL -> R.string.agent_knowledge_access_full
            AgentKnowledgeEvidenceMode.SUMMARY -> R.string.agent_knowledge_access_summary
        }
    )

    private fun showAgentMemoryConflictDialog(
        conflict: AgentMemoryConflict,
        filterKinds: Set<AgentMemoryKind> = emptySet()
    ) {
        val candidates = conflict.candidates.sortedBy { it.version }
        val options = candidates.map { item ->
            getString(
                R.string.agent_memory_use_candidate,
                item.version,
                item.value.replace(Regex("\\s+"), " ").take(90)
            )
        } + getString(R.string.agent_memory_merge_values)
        android.app.AlertDialog.Builder(this)
            .setTitle(conflict.key.ifBlank { getString(R.string.agent_memory_conflict_title) })
            .setItems(options.toTypedArray()) { _, which ->
                if (which < candidates.size) {
                    resolveAgentMemoryConflict(conflict, candidates[which], null, filterKinds)
                } else {
                    val initial = candidates.joinToString("\n") { it.value }
                    showTextSettingDialog(getString(R.string.agent_memory_merge_title), initial) { merged ->
                        resolveAgentMemoryConflict(conflict, candidates.last(), merged, filterKinds)
                    }
                }
            }
            .setNegativeButton(getString(R.string.common_cancel), null)
            .show()
    }

    private fun resolveAgentMemoryConflict(
        conflict: AgentMemoryConflict,
        selected: AgentMemoryItem,
        mergedValue: String?,
        filterKinds: Set<AgentMemoryKind> = emptySet()
    ) {
        val resolved = mobileNativeAgent.resolveMemoryConflict(conflict.groupId, selected.id, mergedValue)
        Toast.makeText(
            this,
            getString(
                if (resolved != null) R.string.agent_memory_merge_saved
                else R.string.agent_memory_conflict_resolution_failed
            ),
            Toast.LENGTH_SHORT
        ).show()
        showAgentMemoryPage(filterKinds)
    }

    private fun showAgentMemoryItemActions(
        item: AgentMemoryItem,
        filterKinds: Set<AgentMemoryKind> = emptySet()
    ) {
        val options = listOf(
            getString(R.string.common_edit),
            getString(
                if (item.important) R.string.agent_memory_remove_important
                else R.string.agent_memory_mark_important
            ),
            getString(R.string.common_delete)
        )
        android.app.AlertDialog.Builder(this)
            .setTitle(getString(R.string.agent_memory_item_actions_title))
            .setItems(options.toTypedArray()) { _, which ->
                when (which) {
                    0 -> showTextSettingDialog(
                        getString(R.string.agent_memory_edit_title),
                        item.value
                    ) { value ->
                        val result = mobileNativeAgent.updateMemoryItem(item.id, value, item.key)
                        Toast.makeText(
                            this,
                            getString(
                                when {
                                    result?.conflict != null -> R.string.agent_memory_conflict_created
                                    result != null -> R.string.agent_memory_updated
                                    else -> R.string.agent_memory_conflict_resolution_failed
                                }
                            ),
                            Toast.LENGTH_SHORT
                        ).show()
                        showAgentMemoryPage(filterKinds)
                    }
                    1 -> {
                        mobileNativeAgent.setMemoryItemImportant(item.id, !item.important)
                        showAgentMemoryPage(filterKinds)
                    }
                    2 -> confirmAgentMemoryDeletion(item, filterKinds)
                }
            }
            .setNegativeButton(getString(R.string.common_cancel), null)
            .show()
    }

    private fun confirmAgentMemoryDeletion(
        item: AgentMemoryItem,
        filterKinds: Set<AgentMemoryKind> = emptySet()
    ) {
        android.app.AlertDialog.Builder(this)
            .setTitle(getString(R.string.agent_memory_delete_title))
            .setMessage(getString(R.string.agent_memory_delete_message, item.value.take(120)))
            .setPositiveButton(getString(R.string.common_delete)) { _, _ ->
                if (mobileNativeAgent.deleteMemoryItem(item.id)) {
                    Toast.makeText(this, getString(R.string.agent_memory_deleted), Toast.LENGTH_SHORT).show()
                }
                showAgentMemoryPage(filterKinds)
            }
            .setNegativeButton(getString(R.string.common_cancel), null)
            .show()
    }

    private fun memoryKindLabel(kind: AgentMemoryKind): String = getString(
        when (kind) {
            AgentMemoryKind.IDENTITY -> R.string.agent_memory_kind_identity
            AgentMemoryKind.CONTACT -> R.string.agent_memory_kind_contact
            AgentMemoryKind.TASK -> R.string.agent_memory_kind_task
            AgentMemoryKind.PREFERENCE -> R.string.agent_memory_kind_preference
            AgentMemoryKind.WORKFLOW -> R.string.agent_memory_kind_workflow
            AgentMemoryKind.KNOWLEDGE -> R.string.agent_memory_kind_knowledge
            AgentMemoryKind.SAFETY -> R.string.agent_memory_kind_safety
        }
    )

    private fun memorySourceLabel(source: String): String = getString(
        when {
            source == "agent_memory_command" -> R.string.agent_memory_source_explicit
            source == "memory_edit" -> R.string.agent_memory_source_edit
            source.startsWith("memory_conflict_") -> R.string.agent_memory_source_resolution
            else -> R.string.agent_memory_source_agent
        }
    )

    private fun nextAgentPermissionMode(current: PermissionMode): PermissionMode = when (current) {
        PermissionMode.OBSERVE_ONLY -> PermissionMode.SUGGEST_ONLY
        PermissionMode.SUGGEST_ONLY -> PermissionMode.ASK_BEFORE_ACTION
        PermissionMode.ASK_BEFORE_ACTION -> PermissionMode.AUTO_LOW_RISK
        PermissionMode.AUTO_LOW_RISK -> PermissionMode.OBSERVE_ONLY
    }

    private fun toggleAgentHighRiskGuard() {
        val next = !mobileNativeAgent.safetySettings().highRiskGuard
        mobileNativeAgent.updateHighRiskGuard(next)
        showOnDeviceAgentFeaturePage()
    }

    private fun toggleAgentMemoryCapture() {
        val next = !mobileNativeAgent.safetySettings().memoryCapture
        mobileNativeAgent.updateMemoryCapture(next)
        showOnDeviceAgentFeaturePage()
    }

    private fun toggleAgentExecutionPaused() {
        val next = !mobileNativeAgent.safetySettings().executionPaused
        mobileNativeAgent.updateExecutionPaused(next)
        showOnDeviceAgentFeaturePage()
    }

    private fun toggleAgentModelPlanner() {
        val next = !mobileNativeAgent.modelPlannerSettings().enabled
        mobileNativeAgent.updateModelPlannerEnabled(next)
        showOnDeviceAgentFeaturePage()
    }

    private fun toggleAgentModelScreenText() {
        val next = !mobileNativeAgent.modelPlannerSettings().shareScreenText
        mobileNativeAgent.updateModelPlannerScreenText(next)
        showOnDeviceAgentFeaturePage()
    }

    private fun cycleAgentModelMaxActions() {
        val current = mobileNativeAgent.modelPlannerSettings().maxActions
        val next = when {
            current < 4 -> 4
            current < 8 -> 8
            current < 12 -> 12
            else -> 4
        }
        mobileNativeAgent.updateModelPlannerMaxActions(next)
        showOnDeviceAgentFeaturePage()
    }

    private fun showAgentModelPlannerSourceDialog(
        onChanged: () -> Unit = { showOnDeviceAgentFeaturePage() }
    ) {
        val settings = mobileNativeAgent.modelPlannerSettings()
        val sources = configuredAgentPlannerSources()
        val ids = listOf("") + sources.map { it.first }
        val labels = listOf(getString(R.string.on_device_agent_model_source_automatic)) +
            sources.map { it.second }
        val selected = ids.indexOf(settings.cloudContactId).coerceAtLeast(0)
        android.app.AlertDialog.Builder(this)
            .setTitle(getString(R.string.on_device_agent_model_source))
            .setSingleChoiceItems(labels.toTypedArray(), selected) { dialog, which ->
                mobileNativeAgent.updateModelPlannerCloudContact(ids[which])
                dialog.dismiss()
                onChanged()
            }
            .setNegativeButton(getString(R.string.common_cancel), null)
            .show()
    }

    private fun agentModelPlannerSourceLabel(contactId: String): String =
        configuredAgentPlannerSources().firstOrNull { it.first == contactId }?.second
            ?: getString(R.string.on_device_agent_model_source_automatic)

    private fun configuredAgentPlannerSources(): List<Pair<String, String>> {
        val contacts = AppStore.contacts(this)
        return buildList {
            for (index in 0 until contacts.length()) {
                val contact = contacts.optJSONObject(index) ?: continue
                if (contact.optBoolean("deleted", false)) continue
                if (contact.optString("delivery_mode") != "cloud_api") continue
                if (contact.optString("setup_status").ifBlank { "ready" } != "ready") continue
                val id = contact.optString("id").ifBlank { contact.optString("signalasi_id") }
                val selected = AppStore.selectedCloudModelContact(this@MainActivity, id) ?: contact
                if (id.isBlank() || !CloudModelCredentialPolicy.isAutoRoutable(selected)) continue
                val provider = selected.optString("cloud_provider")
                    .ifBlank { selected.optString("display_name") }
                    .ifBlank { selected.optString("name") }
                    .ifBlank { id }
                val model = selected.optString("cloud_model")
                add(id to "$provider / $model")
            }
        }
    }

    private fun toggleAgentDynamicReplanning() {
        val next = !mobileNativeAgent.modelPlannerSettings().dynamicReplanning
        mobileNativeAgent.updateModelPlannerDynamicReplanning(next)
        showOnDeviceAgentFeaturePage()
    }

    private fun cycleAgentModelMaxReplans() {
        val current = mobileNativeAgent.modelPlannerSettings().maxReplans
        val next = when {
            current < 3 -> 3
            current < 5 -> 5
            else -> 1
        }
        mobileNativeAgent.updateModelPlannerMaxReplans(next)
        showOnDeviceAgentFeaturePage()
    }

    private fun toggleMultiAgentCoordination() {
        val next = !mobileNativeAgent.modelPlannerSettings().multiAgentCoordination
        mobileNativeAgent.updateMultiAgentCoordination(next)
        showOnDeviceAgentFeaturePage()
    }

    private fun toggleShareAgentOutputsWithPlanner() {
        val next = !mobileNativeAgent.modelPlannerSettings().shareAgentOutputsWithPlanner
        mobileNativeAgent.updateShareAgentOutputsWithPlanner(next)
        showOnDeviceAgentFeaturePage()
    }

    private fun cycleMaxAgentHops() {
        val current = mobileNativeAgent.modelPlannerSettings().maxAgentHops
        val next = when {
            current < 4 -> 4
            current < 8 -> 8
            else -> 2
        }
        mobileNativeAgent.updateMaxAgentHops(next)
        showOnDeviceAgentFeaturePage()
    }

    private fun cycleMaxToolCalls() {
        val current = mobileNativeAgent.modelPlannerSettings().maxToolCalls
        val next = when {
            current < 16 -> 16
            current < 32 -> 32
            else -> 8
        }
        mobileNativeAgent.updateMaxToolCalls(next)
        showOnDeviceAgentFeaturePage()
    }

    private fun toggleAgentScreenObservation() {
        val next = !mobileNativeAgent.safetySettings().screenObservationAllowed
        mobileNativeAgent.updateScreenObservationAllowed(next)
        showOnDeviceAgentFeaturePage()
    }

    private fun toggleAgentLocalActions() {
        val next = !mobileNativeAgent.safetySettings().localActionsAllowed
        mobileNativeAgent.updateLocalActionsAllowed(next)
        showOnDeviceAgentFeaturePage()
    }

    private fun toggleAgentConnectorCalls() {
        val next = !mobileNativeAgent.safetySettings().connectorCallsAllowed
        mobileNativeAgent.updateConnectorCallsAllowed(next)
        showOnDeviceAgentFeaturePage()
    }

    private fun toggleAgentDeviceControl() {
        val next = !mobileNativeAgent.safetySettings().deviceControlAllowed
        mobileNativeAgent.updateDeviceControlAllowed(next)
        showOnDeviceAgentFeaturePage()
    }

    private fun permissionModeLabel(mode: PermissionMode): String = when (mode) {
        PermissionMode.OBSERVE_ONLY -> getString(R.string.permission_mode_observe_only)
        PermissionMode.SUGGEST_ONLY -> getString(R.string.permission_mode_suggest_only)
        PermissionMode.ASK_BEFORE_ACTION -> getString(R.string.permission_mode_ask_before_action)
        PermissionMode.AUTO_LOW_RISK -> getString(R.string.permission_mode_auto_low_risk)
    }

    private fun showLanguageSettingsPage() {
        showFeaturePage(getString(R.string.settings_language_title))
        featureContent.addView(featureHeroCard(
            getString(R.string.settings_language_title),
            getString(R.string.settings_language_subtitle),
            R.drawable.ic_protocol_link,
            "#14C66A",
            AppLanguage.displayName(this)
        ))
        addSectionTitle(getString(R.string.settings_current_language))
        featureContent.addView(languageChoiceRow(
            getString(R.string.settings_language_zh),
            AppLanguage.ZH_CN
        ))
        featureContent.addView(languageChoiceRow(
            getString(R.string.settings_language_en),
            AppLanguage.EN
        ))
    }

    private fun languageChoiceRow(title: String, language: String): View {
        val selected = AppLanguage.current(this) == language
        return featureRow(
            title,
            if (selected) getString(R.string.settings_language_selected) else "",
            R.drawable.ic_protocol_link,
            if (selected) getString(R.string.settings_language_selected) else getString(R.string.settings_language_use)
        ).apply {
            setOnClickListener {
                if (!selected) {
                    AppLanguage.set(this@MainActivity, language)
                    val refreshIntent = Intent(this@MainActivity, MessageService::class.java).apply {
                        action = MessageService.ACTION_REFRESH_LANGUAGE
                    }
                    startService(refreshIntent)
                    Toast.makeText(this@MainActivity, getString(R.string.language_changed), Toast.LENGTH_SHORT).show()
                    recreate()
                }
            }
        }
    }

    private fun showFeaturePage(title: String) {
        activeDesktopControlId = null
        if (!renderingControlCenterDestination &&
            featurePage.visibility == View.VISIBLE &&
            controlCenterDestination != null
        ) {
            controlCenterBackStack.addLast(checkNotNull(controlCenterDestination))
            controlCenterDestination = null
        }
        stopVoiceAssistant()
        wakePage.visibility = View.GONE
        mainPage.visibility = View.GONE
        chatPage.visibility = View.GONE
        featurePage.visibility = View.VISIBLE
        featureTitle.text = title
        featureContent.removeAllViews()
        featureContent.gravity = Gravity.NO_GRAVITY
        setFeatureBackAction()
    }

    private fun setFeatureBackAction(action: (() -> Unit)? = null) {
        featureBackAction = action
        featureBackButton.setOnClickListener { performFeatureBack() }
    }

    private fun performFeatureBack() {
        val action = featureBackAction
        featureBackAction = null
        if (action != null) {
            action()
        } else if (controlCenterDestination != null || controlCenterBackStack.isNotEmpty()) {
            navigateControlCenterBack()
        } else {
            hideFeaturePage()
        }
    }

    private fun hideFeaturePage() {
        featureBackAction = null
        controlCenterDestination = null
        controlCenterBackStack.clear()
        featurePage.visibility = View.GONE
        wakePage.visibility = if (activeMainTab == PAGE_VOICE) View.VISIBLE else View.GONE
        mainPage.visibility = View.VISIBLE
        if (activeMainTab == PAGE_VOICE) {
            mainPage.visibility = View.GONE
            startVoiceAssistant()
        }
    }

    private fun addSegmentTabs(labels: List<String>) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(2), 0, dp(2), dp(12))
        }
        labels.forEachIndexed { index, label ->
            row.addView(TextView(this).apply {
                text = label
                gravity = Gravity.CENTER
                setTextColor(getColorCompat(if (index == 0) R.color.signalasi_green else R.color.text_secondary))
                textSize = 14f
                if (index == 0) setTypeface(typeface, android.graphics.Typeface.BOLD)
            }, LinearLayout.LayoutParams(0, dp(38), 1f))
        }
        featureContent.addView(row)
    }

    private data class AgentUi(
        val contactId: String,
        val title: String,
        val subtitle: String,
        val iconRes: Int,
        val badge: String,
        val badgeColor: String,
        val connected: Boolean
    )

    private fun agentFeatureRow(agent: AgentUi): View {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            isClickable = true
            isFocusable = true
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = getDrawable(R.drawable.glass_card_background)
            addView(featureIcon(agent.iconRes, Color.parseColor(agent.badgeColor)))
            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(13), 0, 0, 0)
                addView(LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    addView(TextView(this@MainActivity).apply {
                        text = agent.title
                        setTextColor(getColorCompat(R.color.text_primary))
                        textSize = 16f
                        setTypeface(typeface, android.graphics.Typeface.BOLD)
                    })
                    addView(statusPill(agent.badge, Color.parseColor(agent.badgeColor)), LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        dp(22)
                    ).apply { leftMargin = dp(8) })
                })
                addView(TextView(this@MainActivity).apply {
                    text = agent.subtitle
                    setTextColor(getColorCompat(R.color.text_secondary))
                    textSize = 12.5f
                })
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(TextView(this@MainActivity).apply {
                text = if (agent.connected) agent.badge else getString(R.string.status_disconnected)
                gravity = Gravity.CENTER
                setTextColor(Color.parseColor(agent.badgeColor))
                textSize = 12f
            }, LinearLayout.LayoutParams(dp(54), dp(34)))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(76)
            ).apply { bottomMargin = dp(10) }
            setOnClickListener {
                if (agent.connected) {
                    showContactDetail(contactById(agent.contactId))
                } else {
                    showFeatureItemPage(agent.title, agent.subtitle, agent.iconRes, getString(R.string.common_connect))
                }
            }
        }
    }

    private fun dynamicConnectorAgents(excludeIds: Set<String>): List<AgentUi> {
        val contacts = AppStore.contacts(this)
        val items = mutableListOf<AgentUi>()
        for (i in 0 until contacts.length()) {
            val raw = contacts.optJSONObject(i) ?: continue
            if (raw.optBoolean("deleted", false) || raw.optString("trust_state") == "deleted") continue
            val id = raw.optString("id").ifBlank { jsonSignalasiId(raw) }
            if (id.isBlank() || id in excludeIds) continue
            val isPcConnector = raw.optString("delivery_mode") == "pc_connector" ||
                raw.optString("parent_contact") == "hermes" ||
                raw.optString("signal_session") == "pc_tunnel"
            if (!isPcConnector) continue
            val setupStatus = raw.optString("setup_status")
            val connected = raw.optString("trust_state") != "deleted"
            val badge = when (setupStatus) {
                "ready" -> getString(R.string.status_ready)
                "needs_setup" -> getString(R.string.status_needs_setup)
                else -> if (connected) getString(R.string.common_paired) else getString(R.string.status_pending_connection)
            }
            val color = when (setupStatus) {
                "ready" -> "#14C66A"
                "needs_setup" -> "#F0A500"
                else -> colorForAgentKind(raw.optString("agent_kind"))
            }
            val subtitle = raw.optString("setup_detail").ifBlank {
                raw.optString("setup_next_step").ifBlank {
                    when (raw.optString("agent_kind")) {
                        "local-cli" -> getString(R.string.agent_connector_local_cli)
                        "local-model" -> getString(R.string.agent_connector_local_model)
                        "cloud-model" -> getString(R.string.agent_connector_cloud_model)
                        else -> getString(R.string.agent_connector_custom)
                    }
                }
            }
            items.add(AgentUi(
                id,
                raw.optString("name", id),
                subtitle,
                iconForAgentKind(id, raw.optString("agent_kind")),
                badge,
                color,
                connected
            ))
        }
        return items.sortedWith(compareBy<AgentUi> { contactPriority(it.contactId) }.thenBy { it.title.lowercase(Locale.getDefault()) })
    }

    private fun iconForAgentKind(id: String, kind: String): Int = when {
        agentIdFromContactId(id) == "custom-agent" || kind == "custom-cli" -> R.drawable.ic_avatar_custom_agent
        agentIdFromContactId(id) == "local-llm" || kind == "local-model" -> R.drawable.ic_avatar_custom_agent
        kind == "cloud-model" -> R.drawable.ic_avatar_custom_agent
        kind == "local-cli" -> R.drawable.ic_agent_node
        else -> R.drawable.ic_agent_node
    }

    private fun colorForAgentKind(kind: String): String = when (kind) {
        "local-model" -> "#00A7A7"
        "cloud-model" -> "#5B6CFF"
        "local-cli" -> "#5B6CFF"
        else -> "#6C7A89"
    }

    private fun localModelHeaderCard(): View {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
            background = getDrawable(R.drawable.glass_card_background)
            addView(featureIcon(R.drawable.ic_local_model, Color.parseColor("#00A7A7")))
            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(14), 0, 0, 0)
                addView(TextView(this@MainActivity).apply {
                    text = "MiniCPM-V 2.6"
                    setTextColor(getColorCompat(R.color.text_primary))
                    textSize = 17f
                    setTypeface(typeface, android.graphics.Typeface.BOLD)
                })
                addView(TextView(this@MainActivity).apply {
                    text = getString(R.string.agent_new_connection_subtitle)
                    setTextColor(getColorCompat(R.color.text_secondary))
                    textSize = 12.5f
                })
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(statusPill(getString(R.string.status_online), getColorCompat(R.color.signalasi_green)))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(86)
            ).apply { bottomMargin = dp(14) }
        }
    }

    private fun localModelStatusCard(): View {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
            background = getDrawable(R.drawable.glass_card_background)
            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(TextView(this@MainActivity).apply {
                    text = getString(R.string.local_model_status_title)
                    setTextColor(getColorCompat(R.color.text_primary))
                    textSize = 15.5f
                    setTypeface(typeface, android.graphics.Typeface.BOLD)
                }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                addView(statusPill(getString(R.string.status_running), getColorCompat(R.color.signalasi_green)))
            })
            addView(localModelMetric("Qwen 7B (4bit)", getString(R.string.local_model_memory_usage), "3.2GB / 6GB", 53))
            addView(localModelMetric(getString(R.string.local_model_inference_speed), "", "", 72))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(14) }
        }
    }

    private fun localModelMetric(left: String, middle: String, right: String, progress: Int): View {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(10), 0, 0)
            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(TextView(this@MainActivity).apply {
                    text = left
                    setTextColor(getColorCompat(R.color.text_primary))
                    textSize = 12.5f
                }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                if (middle.isNotBlank()) {
                    addView(TextView(this@MainActivity).apply {
                        text = middle
                        setTextColor(getColorCompat(R.color.text_secondary))
                        textSize = 11.5f
                    })
                }
                if (right.isNotBlank()) {
                    addView(TextView(this@MainActivity).apply {
                        text = right
                        setTextColor(getColorCompat(R.color.text_secondary))
                        textSize = 11.5f
                        setPadding(dp(6), 0, 0, 0)
                    })
                }
            })
            addView(ProgressBar(this@MainActivity, null, android.R.attr.progressBarStyleHorizontal).apply {
                max = 100
                this.progress = progress
                progressTintList = android.content.res.ColorStateList.valueOf(getColorCompat(R.color.signalasi_green))
                progressBackgroundTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#E5E7EB"))
            }, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(5)
            ).apply { topMargin = dp(7) })
        }
    }

    private fun featureValueRow(title: String, subtitle: String, iconRes: Int, value: String): View {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(10), dp(14), dp(10))
            background = getDrawable(R.drawable.glass_card_background)
            addView(featureIcon(iconRes, featureIconColor(iconRes)))
            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(12), 0, 0, 0)
                addView(TextView(this@MainActivity).apply {
                    text = title
                    setTextColor(getColorCompat(R.color.text_primary))
                    textSize = 15f
                })
                if (subtitle.isNotBlank()) {
                    addView(TextView(this@MainActivity).apply {
                        text = subtitle
                        setTextColor(getColorCompat(R.color.text_secondary))
                        textSize = 12f
                    })
                }
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(TextView(this@MainActivity).apply {
                text = value.ifBlank { "?" }
                setTextColor(getColorCompat(R.color.text_secondary))
                textSize = if (value.isBlank()) 22f else 12.5f
                gravity = Gravity.CENTER_VERTICAL or Gravity.END
                maxLines = 1
            }, LinearLayout.LayoutParams(dp(120), LinearLayout.LayoutParams.WRAP_CONTENT))
            minimumHeight = dp(64)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(8) }
        }
    }

    private fun featureSwitchRow(title: String, subtitle: String, iconRes: Int, checked: Boolean): View {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(10), dp(14), dp(10))
            background = getDrawable(R.drawable.glass_card_background)
            addView(featureIcon(iconRes, featureIconColor(iconRes)))
            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(12), 0, 0, 0)
                addView(TextView(this@MainActivity).apply {
                    text = title
                    setTextColor(getColorCompat(R.color.text_primary))
                    textSize = 15f
                })
                addView(TextView(this@MainActivity).apply {
                    text = subtitle
                    setTextColor(getColorCompat(R.color.text_secondary))
                    textSize = 12f
                })
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(switchPill(checked), LinearLayout.LayoutParams(dp(46), dp(26)))
            minimumHeight = dp(64)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(8) }
        }
    }

    private fun switchPill(checked: Boolean): View {
        return FrameLayout(this).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(13).toFloat()
                setColor(if (checked) getColorCompat(R.color.signalasi_green) else Color.parseColor("#D1D5DB"))
            }
            addView(View(this@MainActivity).apply {
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(Color.WHITE)
                }
            }, FrameLayout.LayoutParams(dp(22), dp(22)).apply {
                gravity = if (checked) Gravity.END or Gravity.CENTER_VERTICAL else Gravity.START or Gravity.CENTER_VERTICAL
                leftMargin = dp(2)
                rightMargin = dp(2)
            })
        }
    }

    private fun featureStorageRow(): View {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = getDrawable(R.drawable.glass_card_background)
            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(TextView(this@MainActivity).apply {
                    text = getString(R.string.local_model_storage_usage)
                    setTextColor(getColorCompat(R.color.text_primary))
                    textSize = 15f
                }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                addView(TextView(this@MainActivity).apply {
                    text = "12.4GB / 64GB"
                    setTextColor(getColorCompat(R.color.text_secondary))
                    textSize = 12f
                })
            })
            addView(ProgressBar(this@MainActivity, null, android.R.attr.progressBarStyleHorizontal).apply {
                max = 100
                progress = 19
                progressTintList = android.content.res.ColorStateList.valueOf(getColorCompat(R.color.signalasi_green))
                progressBackgroundTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#E5E7EB"))
            }, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(5)
            ).apply { topMargin = dp(8) })
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(8) }
        }
    }

    private fun addSectionTitle(title: String) {
        featureContent.addView(TextView(this).apply {
            text = title
            setTextColor(getColorCompat(R.color.text_secondary))
            textSize = 12f
            setPadding(dp(4), dp(4), 0, dp(7))
        }, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ))
    }

    private fun featureHeroCard(title: String, subtitle: String, iconRes: Int, colorHex: String, badge: String): View {
        val color = Color.parseColor(colorHex)
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
            background = getDrawable(R.drawable.glass_card_background)
            addView(featureIcon(iconRes, color), LinearLayout.LayoutParams(dp(48), dp(48)))
            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(14), 0, 0, 0)
                addView(TextView(this@MainActivity).apply {
                    text = title
                    setTextColor(getColorCompat(R.color.text_primary))
                    textSize = 17f
                    setTypeface(typeface, android.graphics.Typeface.BOLD)
                })
                addView(TextView(this@MainActivity).apply {
                    text = subtitle
                    setTextColor(getColorCompat(R.color.text_secondary))
                    textSize = 12.5f
                    setPadding(0, dp(3), 0, 0)
                })
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(statusPill(badge, color))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(92)
            ).apply { bottomMargin = dp(14) }
        }
    }

    private fun featureRow(title: String, subtitle: String, iconRes: Int, action: String): View {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            isClickable = action.isNotBlank()
            isFocusable = action.isNotBlank()
            setPadding(dp(14), dp(10), dp(14), dp(10))
            background = getDrawable(R.drawable.glass_card_background)
            addView(featureIcon(iconRes, featureIconColor(iconRes)))
            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(12), 0, 0, 0)
                addView(TextView(this@MainActivity).apply {
                    text = title
                    setTextColor(getColorCompat(R.color.text_primary))
                    textSize = 15.5f
                    setTypeface(typeface, android.graphics.Typeface.BOLD)
                })
                if (subtitle.isNotBlank()) {
                    addView(TextView(this@MainActivity).apply {
                        text = subtitle
                        setTextColor(getColorCompat(R.color.text_secondary))
                        textSize = 12f
                    })
                }
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(TextView(this@MainActivity).apply {
                text = action
                setTextColor(getColorCompat(R.color.signalasi_green))
                textSize = 13f
                gravity = Gravity.CENTER
                maxLines = 1
            }, LinearLayout.LayoutParams(dp(58), dp(34)))
            minimumHeight = dp(72)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(9) }
            if (action.isNotBlank()) {
                setOnClickListener {
                    if (action == getString(R.string.common_copy)) {
                        val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
                        clipboard.setPrimaryClip(ClipData.newPlainText(title, subtitle))
                        Toast.makeText(this@MainActivity, getString(R.string.toast_copied), Toast.LENGTH_SHORT).show()
                    } else {
                        showFeatureItemPage(title, subtitle, iconRes, action)
                    }
                }
            }
        }
    }

    private fun modelSwitchRow(title: String, action: String, isSelected: Boolean): View {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            isClickable = true
            isFocusable = true
            setPadding(dp(14), dp(9), dp(14), dp(9))
            background = getDrawable(R.drawable.glass_card_background)
            addView(featureIcon(R.drawable.ic_protocol_link, featureIconColor(R.drawable.ic_protocol_link)), LinearLayout.LayoutParams(dp(44), dp(44)))
            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(10), 0, dp(6), 0)
                addView(TextView(this@MainActivity).apply {
                    text = title
                    setTextColor(getColorCompat(R.color.text_primary))
                    textSize = 14.5f
                    setTypeface(typeface, android.graphics.Typeface.BOLD)
                    maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                })
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(TextView(this@MainActivity).apply {
                text = action
                setTextColor(getColorCompat(if (isSelected) R.color.text_secondary else R.color.signalasi_green))
                textSize = 12f
                gravity = Gravity.CENTER
                maxLines = 1
            }, LinearLayout.LayoutParams(dp(42), dp(30)))
            minimumHeight = dp(58)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(8) }
        }
    }

    private fun showFeatureItemPage(title: String, subtitle: String, iconRes: Int, action: String) {
        showFeaturePage(title)
        val color = when (iconRes) {
            R.drawable.ic_send_plane -> "#14C66A"
            R.drawable.ic_security_shield -> "#14C66A"
            R.drawable.ic_local_model -> "#00A7A7"
            R.drawable.ic_device_node -> "#6C7A89"
            else -> "#5B6CFF"
        }
        featureContent.addView(featureHeroCard(title, subtitle.ifBlank { getString(R.string.feature_default_subtitle) }, iconRes, color, action))
        addSectionTitle(getString(R.string.common_status))
        featureContent.addView(featureRow(getString(R.string.feature_current_status), action, iconRes, ""))
        featureContent.addView(featureRow(getString(R.string.feature_run_scope), itemScopeFor(title, action, iconRes), R.drawable.ic_device_node, ""))
        addSectionTitle(getString(R.string.feature_section_security))
        featureContent.addView(featureRow(getString(R.string.feature_identity_protection), getString(R.string.feature_identity_protection_subtitle), R.drawable.ic_security_shield, ""))
        featureContent.addView(featureRow(getString(R.string.feature_audit_log), getString(R.string.feature_audit_log_subtitle), R.drawable.ic_protocol_link, ""))
        addSectionTitle(getString(R.string.feature_section_management))
        featureContent.addView(featureRow(getString(R.string.feature_management_mode), itemManagementFor(action), R.drawable.ic_protocol_link, ""))
    }

    private fun itemScopeFor(title: String, action: String, iconRes: Int): String {
        val lowerTitle = title.lowercase(Locale.getDefault())
        return when {
            title.contains("\u7fa4") || lowerTitle.contains("group") -> getString(R.string.feature_scope_group)
            iconRes == R.drawable.ic_local_model || title.contains("\u6a21\u578b") || lowerTitle.contains("model") || lowerTitle.contains("llm") || lowerTitle.contains("minicpm") -> getString(R.string.feature_scope_model)
            lowerTitle.contains("relay") || title.contains("\u4f20\u8f93") -> getString(R.string.feature_scope_transport)
            title.contains("\u9ea6\u514b\u98ce") || title.contains("\u76f8\u673a") || title.contains("\u4f4d\u7f6e") || title.contains("\u901a\u77e5") ||
                lowerTitle.contains("microphone") || lowerTitle.contains("camera") || lowerTitle.contains("location") || lowerTitle.contains("notification") -> getString(R.string.feature_scope_permission)
            action == getString(R.string.common_off) -> getString(R.string.feature_scope_off)
            else -> getString(R.string.feature_scope_default)
        }
    }

    private fun itemManagementFor(action: String): String {
        return when (action) {
            getString(R.string.common_view) -> getString(R.string.feature_management_view)
            getString(R.string.security_manage) -> getString(R.string.feature_management_manage)
            getString(R.string.common_copy) -> getString(R.string.feature_management_copy)
            getString(R.string.common_on), getString(R.string.status_enabled) -> getString(R.string.feature_management_enable)
            getString(R.string.common_off) -> getString(R.string.feature_management_off)
            getString(R.string.common_next_step) -> getString(R.string.feature_management_next)
            else -> getString(R.string.feature_management_default)
        }
    }

    private fun featureIcon(iconRes: Int, color: Int): ImageView {
        if (isFullColorFeatureIcon(iconRes)) {
            return ImageView(this).apply {
                setImageResource(iconRes)
                setPadding(0, 0, 0, 0)
                scaleType = ImageView.ScaleType.CENTER_CROP
            }.also {
                it.layoutParams = LinearLayout.LayoutParams(dp(44), dp(44))
            }
        }
        return ImageView(this).apply {
            setImageResource(iconRes)
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(13).toFloat()
                setColor(color)
            }
            setPadding(dp(9), dp(9), dp(9), dp(9))
        }.also {
            it.imageTintList = android.content.res.ColorStateList.valueOf(Color.WHITE)
            it.layoutParams = LinearLayout.LayoutParams(dp(44), dp(44))
        }
    }

    private fun isFullColorFeatureIcon(iconRes: Int): Boolean {
        return iconRes == R.drawable.ic_avatar_hermes ||
            iconRes == R.drawable.hermes_logo ||
            iconRes == R.drawable.logo_codex_product ||
            iconRes == R.drawable.logo_claude_code ||
            iconRes == R.drawable.ic_avatar_group ||
            iconRes == R.drawable.ic_avatar_device ||
            iconRes == R.drawable.ic_avatar_news ||
            iconRes == R.drawable.ic_avatar_ai_agent ||
            iconRes == R.drawable.ic_avatar_custom_agent ||
            iconRes == R.drawable.ic_avatar_cloud_model ||
            iconRes == R.drawable.ic_send_plane
    }

    private fun statusPill(textValue: String, color: Int): TextView {
        return TextView(this).apply {
            text = textValue
            gravity = Gravity.CENTER
            textSize = 11f
            setTextColor(color)
            setPadding(dp(8), 0, dp(8), 0)
            background = GradientDrawable().apply {
                cornerRadius = dp(11).toFloat()
                setColor(adjustAlpha(color, 0.10f))
            }
        }
    }

    private fun featureIconColor(iconRes: Int): Int {
        return when (iconRes) {
            R.drawable.ic_local_model -> Color.parseColor("#00A7A7")
            R.drawable.ic_agent_node -> Color.parseColor("#5B6CFF")
            R.drawable.ic_device_node -> Color.parseColor("#6C7A89")
            R.drawable.ic_send_plane -> Color.parseColor("#14C66A")
            R.drawable.ic_security_shield -> Color.parseColor("#14C66A")
            R.drawable.ic_protocol_link -> Color.parseColor("#5B6CFF")
            R.drawable.ic_scan -> Color.parseColor("#14C66A")
            R.drawable.ic_import -> Color.parseColor("#8E8E93")
            else -> Color.parseColor("#8E8E93")
        }
    }

    private fun adjustAlpha(color: Int, factor: Float): Int {
        return Color.argb(
            (Color.alpha(color) * factor).toInt(),
            Color.red(color),
            Color.green(color),
            Color.blue(color)
        )
    }

    // ===== Backup =====
    private fun showExportBackupDialog() {
        showFeaturePage(getString(R.string.backup_export_title))
        featureContent.addView(featureHeroCard(
            getString(R.string.backup_encrypted_title),
            getString(R.string.backup_encrypted_subtitle),
            R.drawable.ic_import,
            "#14C66A",
            getString(R.string.common_export)
        ))
        addSectionTitle(getString(R.string.backup_password_section))
        val passwordInput = EditText(this).apply {
            setSingleLine(true)
            hint = getString(R.string.backup_password_hint)
            setBackgroundResource(R.drawable.message_input_background)
            setPadding(dp(14), dp(10), dp(14), dp(10))
            textSize = 16f
        }
        featureContent.addView(passwordInput, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(48)).apply {
            bottomMargin = dp(10)
        })
        val includeMessagesCb = CheckBox(this).apply {
            text = getString(R.string.backup_include_messages)
            setTextColor(getColorCompat(R.color.text_primary))
            textSize = 15f
            buttonTintList = android.content.res.ColorStateList.valueOf(getColorCompat(R.color.signalasi_green))
            isChecked = true
        }
        featureContent.addView(includeMessagesCb, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(48)).apply {
            bottomMargin = dp(18)
        })
        featureContent.addView(
            featureRow(
                getString(R.string.backup_include_agent_data),
                getString(R.string.backup_include_agent_data_subtitle),
                R.drawable.ic_agent_node,
                getString(R.string.backup_included)
            ),
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp(18)
            }
        )
        featureContent.addView(TextView(this).apply {
            text = getString(R.string.common_export)
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            textSize = 17f
            background = getDrawable(R.drawable.send_button_background)
            setOnClickListener {
                val pw = passwordInput.text?.toString().orEmpty()
                if (pw.isBlank()) {
                    Toast.makeText(this@MainActivity, getString(R.string.backup_password_required), Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                pendingExportPassword = pw
                pendingExportIncludeMessages = includeMessagesCb.isChecked
                startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "application/octet-stream"
                    putExtra(Intent.EXTRA_TITLE, getString(R.string.backup_export_file_name))
                }, REQUEST_EXPORT_BACKUP)
            }
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(46)))
    }

    private fun exportBackupToUri(uri: Uri) {
        val password = pendingExportPassword ?: return
        pendingExportPassword = null
        runCatching {
            val file = AppStore.exportBackup(this, password, includeMessages = pendingExportIncludeMessages, includeContacts = true)
            contentResolver.openOutputStream(uri)?.use { out ->
                file.inputStream().use { input -> input.copyTo(out) }
            }
            runOnUiThread { Toast.makeText(this, getString(R.string.backup_export_success), Toast.LENGTH_SHORT).show() }
        }.onFailure {
            runOnUiThread { Toast.makeText(this, getString(R.string.backup_export_failed, it.message ?: ""), Toast.LENGTH_LONG).show() }
        }
    }

    private fun openBackupImportPicker() {
        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
        }, REQUEST_IMPORT_BACKUP)
    }

    private fun importBackupFromUri(uri: Uri) {
        pendingImportUri = uri
        showPasswordFeaturePage(getString(R.string.backup_import_title), getString(R.string.backup_import_subtitle), getString(R.string.common_import)) { password ->
            importBackupWithPassword(uri, password)
        }
    }

    private fun showBackupImportPasswordPreview() {
        showPasswordFeaturePage(getString(R.string.backup_import_title), getString(R.string.backup_import_subtitle), getString(R.string.common_import)) {}
    }

    private fun renderControlCenterResetPage() {
        showControlCenterFeature(
            getString(R.string.destroy_data_title),
            ControlCenterPageSpec(
                banner = ControlCenterBannerSpec(
                    title = getString(R.string.destroy_data_hero_title),
                    subtitle = getString(R.string.destroy_data_hero_subtitle),
                    iconRes = R.drawable.ic_delete,
                    tone = ControlCenterTone.RED
                ),
                sections = listOf(
                    ControlCenterSectionSpec(
                        getString(R.string.destroy_data_scope),
                        listOf(
                            ControlCenterRowSpec(
                                "",
                                getString(R.string.destroy_data_regenerate_identity),
                                getString(R.string.destroy_data_regenerate_identity_subtitle),
                                R.drawable.ic_security_shield,
                                getString(R.string.cc_reset_removed_status),
                                ControlCenterTone.RED,
                                showChevron = false
                            ),
                            ControlCenterRowSpec(
                                "",
                                getString(R.string.destroy_data_contacts),
                                getString(R.string.destroy_data_contacts_subtitle),
                                R.drawable.ic_group,
                                getString(R.string.cc_reset_removed_status),
                                ControlCenterTone.RED,
                                showChevron = false
                            ),
                            ControlCenterRowSpec(
                                "",
                                getString(R.string.destroy_data_messages),
                                getString(R.string.destroy_data_messages_subtitle),
                                R.drawable.ic_delete,
                                getString(R.string.cc_reset_removed_status),
                                ControlCenterTone.RED,
                                showChevron = false
                            ),
                            ControlCenterRowSpec(
                                "",
                                getString(R.string.cc_reset_agent_data_title),
                                getString(R.string.cc_reset_agent_data_subtitle),
                                R.drawable.ic_agent_node,
                                getString(R.string.cc_reset_removed_status),
                                ControlCenterTone.RED,
                                showChevron = false
                            ),
                            ControlCenterRowSpec(
                                "",
                                getString(R.string.cc_reset_settings_assets_title),
                                getString(R.string.cc_reset_settings_assets_subtitle),
                                R.drawable.ic_settings_diagnostics,
                                getString(R.string.cc_reset_removed_status),
                                ControlCenterTone.RED,
                                showChevron = false
                            )
                        )
                    ),
                    ControlCenterSectionSpec(
                        getString(R.string.cc_reset_confirmation_section),
                        listOf(
                            ControlCenterRowSpec(
                                "reset.begin",
                                getString(R.string.cc_reset_begin_title),
                                getString(R.string.cc_reset_begin_subtitle),
                                R.drawable.ic_reset_data,
                                getString(R.string.cc_reset_irreversible),
                                ControlCenterTone.RED
                            )
                        )
                    )
                ),
                footer = getString(R.string.cc_reset_footer)
            )
        )
    }

    private fun confirmDestroyAllData() {
        renderControlCenterResetPage()
    }

    private fun showResetConfirmationDialog() {
        val input = EditText(this).apply {
            setSingleLine(true)
            hint = getString(R.string.cc_reset_input_hint)
            setPadding(dp(12), dp(8), dp(12), dp(8))
        }
        val dialog = android.app.AlertDialog.Builder(this)
            .setTitle(getString(R.string.cc_reset_dialog_title))
            .setMessage(getString(R.string.cc_reset_dialog_message))
            .setView(input)
            .setNegativeButton(getString(R.string.common_cancel), null)
            .setPositiveButton(getString(R.string.destroy_data_title), null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).apply {
                setTextColor(Color.parseColor("#C7372F"))
                setOnClickListener {
                    if (input.text?.toString()?.trim() != "RESET") {
                        input.error = getString(R.string.cc_reset_input_error)
                        return@setOnClickListener
                    }
                    dialog.dismiss()
                    destroyAllPrivateDataAndRestart()
                }
            }
        }
        dialog.show()
    }

    private fun destroyAllPrivateDataAndRestart() {
        AppStore.destroyAllPrivateData(this)
        messages.clear()
        summaries.clear()
        directoryContacts.clear()
        currentMessages.clear()
        Toast.makeText(this, getString(R.string.destroy_data_success), Toast.LENGTH_LONG).show()
        restartFreshApp()
    }

    private fun showAboutSignalASIPage() {
        val versionName = runCatching {
            packageManager.getPackageInfo(packageName, 0).versionName
        }.getOrNull().orEmpty().ifBlank { "0.1.0" }
        showFeaturePage(getString(R.string.settings_about_signalasi))
        featureContent.addView(featureHeroCard(
            getString(R.string.app_name),
            getString(R.string.about_product_subtitle),
            R.mipmap.ic_launcher,
            "#16B981",
            "v$versionName"
        ))
        addSectionTitle(getString(R.string.about_section_product))
        featureContent.addView(featureRow(
            getString(R.string.about_version),
            getString(R.string.about_version_subtitle),
            R.drawable.ic_info_outline,
            "v$versionName"
        ))
        featureContent.addView(featureRow(
            getString(R.string.settings_signal_link_protocol),
            getString(R.string.about_protocol_subtitle),
            R.drawable.ic_protocol_link,
            "v1.0.3"
        ).apply { setOnClickListener { showSignalLinkProtocolPage() } })
        addSectionTitle(getString(R.string.about_section_trust))
        featureContent.addView(featureRow(
            getString(R.string.about_security),
            getString(R.string.about_security_subtitle),
            R.drawable.ic_security_shield,
            getString(R.string.common_view)
        ).apply {
            setOnClickListener {
                showSecurityFeaturePage()
                setFeatureBackAction { showAboutSignalASIPage() }
            }
        })
        featureContent.addView(featureRow(
            getString(R.string.about_open_source),
            getString(R.string.about_open_source_subtitle),
            R.drawable.ic_protocol_link,
            getString(R.string.common_view)
        ).apply {
            setOnClickListener {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/signalasi/SignalASI")))
            }
        })
    }

    private fun restartFreshApp() {
        val intent = packageManager.getLaunchIntentForPackage(packageName)?.apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        }
        if (intent != null) startActivity(intent)
        finishAffinity()
        android.os.Process.killProcess(android.os.Process.myPid())
    }

    private fun promptPassword(title: String, message: String, onPassword: (String) -> Unit) {
        showPasswordFeaturePage(title, message, getString(R.string.common_confirm), onPassword)
    }

    private fun showPasswordFeaturePage(title: String, message: String, action: String, onPassword: (String) -> Unit) {
        showFeaturePage(title)
        featureContent.addView(featureHeroCard(title, message, R.drawable.ic_security_shield, "#5B6CFF", getString(R.string.backup_password_page_badge)))
        addSectionTitle(getString(R.string.backup_password_section_title))
        val input = EditText(this).apply {
            setSingleLine(true)
            hint = getString(R.string.backup_password_input_hint)
            setBackgroundResource(R.drawable.message_input_background)
            setPadding(dp(14), dp(10), dp(14), dp(10))
            textSize = 16f
        }
        featureContent.addView(input, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(48)).apply {
            bottomMargin = dp(18)
        })
        featureContent.addView(TextView(this).apply {
            text = action
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            textSize = 17f
            background = getDrawable(R.drawable.send_button_background)
            setOnClickListener {
                val pw = input.text?.toString().orEmpty()
                if (pw.isBlank()) {
                    Toast.makeText(this@MainActivity, getString(R.string.backup_password_input_required), Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                onPassword(pw)
            }
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(46)))
    }

    private fun importBackupWithPassword(uri: Uri, password: String) {
        runCatching {
            val target = File(cacheDir, "import_${System.currentTimeMillis()}.hcbak")
            contentResolver.openInputStream(uri)?.use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
            }
            AppStore.importBackup(this, target, password)
            target.delete()
            runOnUiThread {
                pendingImportUri = null
                Toast.makeText(this, getString(R.string.backup_import_success), Toast.LENGTH_LONG).show()
                recreate()
            }
        }.onFailure {
            runOnUiThread { Toast.makeText(this, getString(R.string.backup_import_failed, it.message ?: ""), Toast.LENGTH_LONG).show() }
        }
    }

    private fun confirmDeleteChat(contact: Contact): Boolean {
        showFeaturePage(getString(R.string.delete_chat_title))
        featureContent.addView(featureHeroCard(getString(R.string.delete_chat_hero_title, contact.name), getString(R.string.delete_chat_subtitle), R.drawable.ic_delete, "#FF3B30", getString(R.string.common_confirm)))
        featureContent.addView(TextView(this).apply {
            text = getString(R.string.delete_chat_title)
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            textSize = 17f
            background = GradientDrawable().apply {
                cornerRadius = dp(8).toFloat()
                setColor(Color.parseColor("#FF3B30"))
            }
            setOnClickListener {
                messages.remove(contact.id)
                summaries.remove(contact.id)
                saveChatHistory()
                refreshContactList()
                Toast.makeText(this@MainActivity, getString(R.string.delete_chat_toast), Toast.LENGTH_SHORT).show()
                hideFeaturePage()
            }
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(46)).apply {
            topMargin = dp(18)
        })
        return true
    }

    private fun confirmDeleteContact(contact: Contact): Boolean {
        val title = if (contact.id == CONTACT_HERMES.id) getString(R.string.delete_hermes_title) else getString(R.string.delete_contact_title)
        val message = if (contact.id == CONTACT_HERMES.id) {
            getString(R.string.delete_hermes_subtitle)
        } else {
            getString(R.string.delete_contact_subtitle)
        }
        showFeaturePage(title)
        featureContent.addView(featureHeroCard(contact.name, message, R.drawable.ic_delete, "#FF3B30", getString(R.string.common_confirm)))
        featureContent.addView(TextView(this).apply {
            text = getString(R.string.common_delete)
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            textSize = 17f
            background = GradientDrawable().apply {
                cornerRadius = dp(8).toFloat()
                setColor(Color.parseColor("#FF3B30"))
            }
            setOnClickListener {
                AppStore.deleteContact(this@MainActivity, contact.id, deleteMessages = false)
                refreshDirectoryContacts()
                Toast.makeText(this@MainActivity, getString(R.string.delete_contact_toast), Toast.LENGTH_LONG).show()
                hideFeaturePage()
            }
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(46)).apply {
            topMargin = dp(18)
        })
        return true
    }

    // ===== Avatar =====
    private fun pickAvatar() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "image/*"
        }
        startActivityForResult(intent, REQUEST_PICK_AVATAR)
    }

    private fun handleAvatarPicked(uri: Uri?) {
        if (uri == null) return
        try {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (_: Exception) {}
        showFeaturePage(getString(R.string.avatar_edit_title))
        featureContent.gravity = Gravity.CENTER_HORIZONTAL
        featureContent.addView(ImageView(this).apply {
            setImageURI(uri)
            scaleType = ImageView.ScaleType.CENTER_CROP
            setBackgroundResource(R.drawable.rounded_avatar_bg)
            clipToOutline = true
        }, LinearLayout.LayoutParams(dp(160), dp(160)).apply {
            topMargin = dp(24)
            bottomMargin = dp(24)
            gravity = Gravity.CENTER_HORIZONTAL
        })
        featureContent.addView(TextView(this).apply {
            text = getString(R.string.common_save)
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            textSize = 17f
            background = getDrawable(R.drawable.send_button_background)
            setOnClickListener {
                val profile = AppStore.profile(this@MainActivity)
                profile.put("avatar_uri", uri.toString())
                AppStore.updateProfile(this@MainActivity, profile)
                refreshMePage()
                Toast.makeText(this@MainActivity, getString(R.string.avatar_saved_toast), Toast.LENGTH_SHORT).show()
                featureContent.gravity = Gravity.NO_GRAVITY
                hideFeaturePage()
            }
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(46)))
    }

    private fun hideKeyboard() {
        getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(messageInput.windowToken, 0)
    }

    private fun showAgentAttachmentMenu() {
        val dialog = Dialog(this)
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(8), 0, dp(10))
            setBackgroundResource(R.drawable.agent_attachment_sheet_background)
        }
        fun addRow(label: String, action: () -> Unit) {
            content.addView(TextView(this).apply {
                text = label
                gravity = Gravity.CENTER_VERTICAL
                setTextColor(getColorCompat(R.color.text_primary))
                textSize = 17f
                setPadding(dp(22), 0, dp(22), 0)
                setOnClickListener {
                    dialog.dismiss()
                    action()
                }
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(58)))
            content.addView(View(this).apply {
                setBackgroundColor(getColorCompat(R.color.separator))
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)))
        }
        addRow(getString(R.string.agent_attachment_new_task)) { createAgentConversation() }
        addRow(getString(R.string.agent_attachment_take_photo)) { openAgentCamera() }
        addRow(getString(R.string.agent_attachment_add_photos)) { openAgentAttachmentPicker(imagesOnly = true) }
        addRow(getString(R.string.agent_attachment_add_file)) { openAgentAttachmentPicker(imagesOnly = false) }
        if (content.childCount > 0) content.removeViewAt(content.childCount - 1)
        dialog.setContentView(content)
        dialog.window?.apply {
            setBackgroundDrawableResource(android.R.color.transparent)
            setGravity(Gravity.BOTTOM)
            addFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            attributes = attributes.apply { dimAmount = 0.28f }
        }
        dialog.show()
        dialog.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    private fun openAgentCamera() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M &&
            checkSelfPermission(android.Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(android.Manifest.permission.CAMERA), REQUEST_AGENT_CAMERA_PERMISSION)
            return
        }
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "signalasi_${System.currentTimeMillis()}.jpg")
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/SignalASI")
            }
        }
        val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
        if (uri == null) {
            Toast.makeText(this, getString(R.string.agent_attachment_camera_unavailable), Toast.LENGTH_SHORT).show()
            return
        }
        pendingAgentCameraUri = uri
        val intent = Intent(MediaStore.ACTION_IMAGE_CAPTURE).apply {
            putExtra(MediaStore.EXTRA_OUTPUT, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        }
        if (intent.resolveActivity(packageManager) == null) {
            contentResolver.delete(uri, null, null)
            pendingAgentCameraUri = null
            Toast.makeText(this, getString(R.string.agent_attachment_camera_unavailable), Toast.LENGTH_SHORT).show()
            return
        }
        startActivityForResult(intent, REQUEST_AGENT_CAMERA)
    }

    private fun openAgentKnowledgeImportPicker() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            type = "*/*"
            addCategory(Intent.CATEGORY_OPENABLE)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf(
                "text/*",
                "application/json",
                "application/pdf",
                "application/msword",
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                "application/octet-stream"
            ))
        }
        startActivityForResult(intent, REQUEST_IMPORT_KNOWLEDGE)
    }

    private fun openAgentAttachmentPicker(imagesOnly: Boolean) {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            type = if (imagesOnly) "image/*" else "*/*"
            addCategory(Intent.CATEGORY_OPENABLE)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        }
        startActivityForResult(intent, if (imagesOnly) REQUEST_AGENT_IMAGES else REQUEST_AGENT_ATTACHMENTS)
    }

    private fun addAgentInputUris(uris: List<Uri>) {
        var rejected = 0
        uris.distinct().forEach { uri ->
            if (agentInputAttachments.size >= MAX_AGENT_ATTACHMENTS) {
                rejected++
                return@forEach
            }
            val metadata = agentAttachmentMetadata(uri)
            if (metadata.sizeBytes > MAX_AGENT_ATTACHMENT_BYTES || metadata.sizeBytes < 0L) {
                rejected++
                return@forEach
            }
            if (agentInputAttachments.any { it.uri == uri }) return@forEach
            agentInputAttachments += metadata
        }
        renderAgentInputAttachments()
        if (rejected > 0) {
            Toast.makeText(this, getString(R.string.agent_attachment_rejected), Toast.LENGTH_LONG).show()
        }
    }

    private fun agentAttachmentMetadata(uri: Uri): AgentInputAttachment {
        var name = uri.lastPathSegment?.substringAfterLast('/').orEmpty().ifBlank { "attachment" }
        var size = 0L
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)
            ?.use { cursor ->
                if (cursor.moveToFirst()) {
                    cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME).takeIf { it >= 0 }
                        ?.let { name = cursor.getString(it).orEmpty().ifBlank { name } }
                    cursor.getColumnIndex(OpenableColumns.SIZE).takeIf { it >= 0 }
                        ?.let { if (!cursor.isNull(it)) size = cursor.getLong(it) }
                }
            }
        val mimeType = contentResolver.getType(uri).orEmpty().ifBlank {
            android.webkit.MimeTypeMap.getSingleton()
                .getMimeTypeFromExtension(name.substringAfterLast('.', "").lowercase(Locale.US))
                ?: "application/octet-stream"
        }
        return AgentInputAttachment(
            id = UUID.randomUUID().toString(),
            uri = uri,
            displayName = name.take(180),
            mimeType = mimeType.take(160),
            sizeBytes = size
        )
    }

    private fun renderAgentInputAttachments() {
        agentAttachmentPreviewList.removeAllViews()
        agentInputAttachments.forEach { attachment ->
            agentAttachmentPreviewList.addView(agentInputAttachmentCard(attachment))
        }
        agentAttachmentPreviewScroll.visibility = if (agentInputAttachments.isEmpty()) View.GONE else View.VISIBLE
        updateAgentSubmitButtonAppearance(
            agentGoalInput.text?.toString()?.isNotBlank() == true || agentInputAttachments.isNotEmpty()
        )
        if (agentInputAttachments.isNotEmpty()) {
            agentAttachmentPreviewScroll.post { agentAttachmentPreviewScroll.fullScroll(View.FOCUS_RIGHT) }
        }
    }

    private fun agentInputAttachmentCard(attachment: AgentInputAttachment): View {
        val cardBackground = GradientDrawable().apply {
            cornerRadius = dp(8).toFloat()
            setColor(Color.parseColor("#F4F6F8"))
            setStroke(dp(1), Color.parseColor("#DDE2E7"))
        }
        val container = FrameLayout(this).apply {
            background = cardBackground
            clipToOutline = true
        }
        if (attachment.isImage) {
            container.addView(ImageView(this).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                contentDescription = attachment.displayName
                loadAgentImageThumbnail(this, attachment.uri, dp(140), dp(132))
            }, FrameLayout.LayoutParams(dp(70), dp(66)))
        } else {
            container.addView(LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(10), dp(7), dp(30), dp(7))
                addView(ImageView(this@MainActivity).apply {
                    setImageResource(R.drawable.ic_agent_attach)
                    imageTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#53606D"))
                }, LinearLayout.LayoutParams(dp(26), dp(26)).apply { marginEnd = dp(8) })
                addView(LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER_VERTICAL
                    addView(TextView(this@MainActivity).apply {
                        text = attachment.displayName
                        textSize = 13f
                        setTextColor(getColorCompat(R.color.text_primary))
                        maxLines = 1
                        ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
                    }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
                    addView(TextView(this@MainActivity).apply {
                        text = AgentInputAttachment.humanSize(attachment.sizeBytes)
                        textSize = 11f
                        setTextColor(getColorCompat(R.color.text_secondary))
                    })
                }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            }, FrameLayout.LayoutParams(dp(190), dp(66)))
        }
        container.addView(ImageButton(this).apply {
            setImageResource(android.R.drawable.ic_menu_close_clear_cancel)
            imageTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#59636E"))
            setBackgroundColor(Color.TRANSPARENT)
            contentDescription = getString(R.string.agent_attachment_remove)
            setPadding(dp(5), dp(5), dp(5), dp(5))
            setOnClickListener {
                agentInputAttachments.removeAll { it.id == attachment.id }
                renderAgentInputAttachments()
            }
        }, FrameLayout.LayoutParams(dp(28), dp(28), Gravity.TOP or Gravity.END))
        val width = if (attachment.isImage) dp(70) else dp(190)
        return container.apply {
            layoutParams = LinearLayout.LayoutParams(width, dp(66)).apply { marginEnd = dp(8) }
        }
    }

    private fun importAgentKnowledgeFromUri(uri: Uri) {
        runCatching {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        thread(name = "signalasi-knowledge-import") {
            val result = AgentKnowledgeImporter(applicationContext).importDocument(uri)
            runOnUiThread {
                renderAgentState(mobileNativeAgent.recordKnowledgeImport(result))
                Toast.makeText(this, result.message, Toast.LENGTH_LONG).show()
                if (result.success) {
                    showAgentKnowledgePage()
                }
            }
        }
    }

    // ===== Media =====
    private fun imageMeta(uri: Uri): ImageMeta {
        var name = "image"
        var size = 0L
        contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME).let { if (it >= 0) name = cursor.getString(it) ?: name }
                cursor.getColumnIndex(OpenableColumns.SIZE).let { if (it >= 0) size = cursor.getLong(it) }
            }
        }
        return ImageMeta(name, size)
    }

    // ===== Helpers =====
    private fun jsonSignalasiId(json: JSONObject?, fallback: String = ""): String {
        if (json == null) return fallback
        return json.optString("signalasi_id")
            .ifBlank { json.optString("hermes_id") }
            .ifBlank { json.optString("id") }
            .ifBlank { fallback }
    }

    private fun contactById(id: String): Contact {
        AppStore.contactById(this, id)?.let { raw ->
            if (!raw.optBoolean("deleted", false) && raw.optString("trust_state") != "deleted") {
                val contactId = raw.optString("id").ifBlank { jsonSignalasiId(raw, id) }
                return Contact(contactId, raw.optString("name", contactId), "")
            }
        }
        return when (id) {
            CONTACT_SYSTEM.id -> CONTACT_SYSTEM
            CONTACT_ME.id -> CONTACT_ME
            CONTACT_PC.id -> CONTACT_PC
            CONTACT_HOME.id -> CONTACT_HOME
            CONTACT_NEWS.id -> CONTACT_NEWS
            CONTACT_AUTOMATION.id -> CONTACT_AUTOMATION
            else -> {
                val contacts = AppStore.contacts(this)
                for (i in 0 until contacts.length()) {
                    val c = contacts.optJSONObject(i) ?: continue
                    val cid = c.optString("id").ifBlank { jsonSignalasiId(c) }
                    if (cid == id) return Contact(cid, c.optString("name", cid), "")
                }
                id.let { Contact(it, it, "") }
            }
        }
    }

    private fun dp(dp: Int): Int = (dp * resources.displayMetrics.density).toInt()

    private fun formatFingerprint(value: String): String {
        return value
            .filter { it.isLetterOrDigit() }
            .chunked(32)
            .take(2)
            .joinToString("\n")
    }
}

// ===== Top-level extension functions =====
private fun View.dp(dp: Int): Int = (dp * resources.displayMetrics.density).toInt()
private fun Activity.dp(dp: Int): Int = (dp * resources.displayMetrics.density).toInt()
private fun View.getColorCompat(id: Int): Int = context.getColor(id)
private fun Activity.getColorCompat(id: Int): Int = getColor(id)

// ===== Data Classes =====
data class Contact(val id: String, val name: String, val avatar: String)

data class ChatMessage(
    val id: Long,
    val content: String,
    val isMine: Boolean,
    val contact: Contact,
    val isSystem: Boolean = false,
    val timestamp: Long = System.currentTimeMillis(),
    var deliveryStatus: String? = null,
    val deliveryTrace: MutableList<DeliveryTraceEvent> = mutableListOf(),
    var taskId: String = "",
    var taskStatus: String = "",
    var taskStatusSeq: Long = 0L
)

data class DeliveryTraceEvent(
    val stage: String,
    val at: Long = System.currentTimeMillis(),
    val detail: String = ""
)


data class ContactSummary(
    var lastMessage: String = "",
    var lastAt: Long = 0L,
    var unreadCount: Int = 0
)

data class ImageMeta(val name: String, val size: Long)
data class UploadedFile(val fileId: String, val name: String, val size: Long, val contentType: String)
data class CloudModelPreset(
    val provider: String,
    val name: String,
    val modelId: String,
    val endpoint: String,
    val apiStyle: String
)
data class ContactTypeTag(val text: String, val textColor: Int, val bgColor: Int, val strokeColor: Int)

private enum class AgentScreenCommandKind {
    TAP,
    TYPE,
    SCROLL
}

// ===== ContactAdapter =====
private class ContactAdapter(
    private var allContacts: List<Contact>,
    var summaries: Map<String, ContactSummary>,
    private val onClick: (Contact) -> Unit,
    private val onLongClick: ((Contact) -> Boolean)? = null,
    private val showSummary: Boolean = true
) : RecyclerView.Adapter<ContactAdapter.VH>() {

    private val visibleContacts = allContacts.toMutableList()

    fun replaceContacts(contacts: List<Contact>) {
        allContacts = contacts.toList()
        visibleContacts.clear()
        visibleContacts.addAll(allContacts)
        notifyDataSetChanged()
    }

    fun filter(query: String) {
        visibleContacts.clear()
        val normalized = query.trim().lowercase(Locale.getDefault())
        visibleContacts.addAll(if (normalized.isBlank()) allContacts
            else allContacts.filter { it.name.lowercase(Locale.getDefault()).contains(normalized) || it.id.contains(normalized) })
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_contact, parent, false)
        return VH(view)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val contact = visibleContacts[position]
        val summary = summaries[contact.id] ?: ContactSummary()
        holder.avatar.setImageResource(contactAvatarRes(contact))
        holder.avatar.scaleType = ImageView.ScaleType.CENTER_CROP
        holder.avatar.clipToOutline = true
        holder.name.text = localizedContactName(holder.itemView.context, contact)
        val tag = contactTypeTag(holder.itemView.context, contact)
        if (tag == null) {
            holder.typeTag.visibility = View.GONE
            holder.name.maxWidth = Int.MAX_VALUE
        } else {
            holder.typeTag.visibility = View.VISIBLE
            holder.typeTag.text = tag.text
            holder.typeTag.setTextColor(tag.textColor)
            holder.typeTag.background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = holder.itemView.dp(4).toFloat()
                setColor(tag.bgColor)
                setStroke(holder.itemView.dp(1), tag.strokeColor)
            }
            val reserved = if (showSummary) 220 else 160
            holder.name.maxWidth = (holder.itemView.resources.displayMetrics.widthPixels - holder.itemView.dp(reserved))
                .coerceAtLeast(holder.itemView.dp(120))
        }
        val rowHeight = holder.itemView.dp(if (showSummary) 70 else 56)
        holder.row.layoutParams = (holder.row.layoutParams as LinearLayout.LayoutParams).apply {
            height = rowHeight
        }
        val avatarSize = holder.itemView.dp(if (showSummary) 44 else 36)
        holder.avatar.layoutParams = (holder.avatar.layoutParams as LinearLayout.LayoutParams).apply {
            width = avatarSize
            height = avatarSize
        }
        holder.name.textSize = if (showSummary) 15.5f else 15f
        holder.preview.visibility = if (showSummary) View.VISIBLE else View.GONE
        holder.time.visibility = if (showSummary) View.VISIBLE else View.GONE
        holder.preview.text = if (showSummary) summary.lastMessage.ifBlank { holder.itemView.context.getString(R.string.chat_no_messages) } else ""
        holder.time.text = if (showSummary && summary.lastAt > 0) listTime(summary.lastAt) else ""
        holder.badge.visibility = if (showSummary && summary.unreadCount > 0) View.VISIBLE else View.GONE
        holder.badge.text = if (summary.unreadCount > 99) "99+" else summary.unreadCount.toString()
        holder.itemView.setOnClickListener { onClick(contact) }
        holder.itemView.setOnLongClickListener { onLongClick?.invoke(contact) ?: false }
    }

    override fun getItemCount(): Int = visibleContacts.size

    private fun localizedContactName(context: Context, contact: Contact): String = when (contact.id) {
        "system" -> context.getString(R.string.chat_system_notice)
        "me" -> context.getString(R.string.chat_me)
        else -> contact.name
    }

    private fun contactTypeTag(context: Context, contact: Contact): ContactTypeTag? {
        if (contact.id == "system" || contact.id == "me" || contact.id.startsWith("group:")) return null
        val raw = AppStore.contactById(context, contact.id)
        val type = raw?.optString("type").orEmpty()
        val kind = raw?.optString("agent_kind").orEmpty()
        val deliveryMode = raw?.optString("delivery_mode").orEmpty()
        val agentId = agentIdFromContactId(contact.id)
        return when {
            contact.id.startsWith("cloud:") ||
                deliveryMode == "cloud_api" ||
                kind == "cloud-api" ||
                kind == "cloud-model" ||
                kind == "local-model" -> ContactTypeTag(context.getString(R.string.contact_tag_model), Color.parseColor("#4E6BFF"), Color.parseColor("#EEF2FF"), Color.parseColor("#9FB0FF"))
            type == "device" ||
                kind == "device" ||
                agentId == "pc_agent" ||
                agentId == "home_hub" ||
                agentId.contains("device", ignoreCase = true) ||
                agentId.contains("hub", ignoreCase = true) -> ContactTypeTag(context.getString(R.string.contact_tag_device), Color.parseColor("#2F80ED"), Color.parseColor("#EEF6FF"), Color.parseColor("#9DCAFF"))
            type == "agent" ||
                type == "hermes" ||
                kind == "local-cli" ||
                kind == "custom-cli" ||
                agentId == "hermes" ||
                agentId.endsWith("-agent") ||
                agentId.contains("_agent") -> ContactTypeTag("Agent", Color.parseColor("#10A65A"), Color.parseColor("#EEFFF6"), Color.parseColor("#8BE2B5"))
            else -> null
        }
    }

    class VH(view: View) : RecyclerView.ViewHolder(view) {
        val avatar: ImageView = view.findViewById(R.id.contactAvatar)
        val row: LinearLayout = view.findViewById(R.id.contactRow)
        val name: TextView = view.findViewById(R.id.contactName)
        val typeTag: TextView = view.findViewById(R.id.contactTypeTag)
        val preview: TextView = view.findViewById(R.id.contactPreview)
        val time: TextView = view.findViewById(R.id.contactTime)
        val badge: TextView = view.findViewById(R.id.unreadBadge)
    }
}

// ===== MessageAdapter =====
private class MessageAdapter(
    private val messages: List<ChatMessage>,
    private val onPlayVoiceMessage: ((Long) -> Unit)? = null,
    private val onMessageActions: ((Int) -> Unit)? = null
) : RecyclerView.Adapter<MessageAdapter.VH>() {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_message, parent, false)
        return VH(view)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val message = messages[position]
        val prevMsg = if (position > 0) messages[position - 1] else null
        val timeGap = prevMsg != null && (message.timestamp - prevMsg.timestamp) >= 30 * 60 * 1000L
        val showDivider = position == 0 || timeGap || dateKey(messages[position - 1].timestamp) != dateKey(message.timestamp)
        holder.timeDivider.visibility = if (showDivider) View.VISIBLE else View.GONE
        holder.timeDivider.text = dayDivider(message.timestamp)
        holder.meta.visibility = View.GONE

        if (message.isSystem) {
            holder.row.visibility = View.GONE
            holder.systemText.visibility = View.VISIBLE
            holder.systemText.text = message.content
            holder.itemView.setPadding(0, 4, 0, 4)
            return
        }

        holder.row.visibility = View.VISIBLE
        holder.systemText.visibility = View.GONE
        holder.avatar.visibility = View.VISIBLE
        holder.bubble.visibility = View.VISIBLE
        holder.bubble.text = message.content
        holder.bubble.setLineSpacing(0f, 1.18f)
        holder.timeText.text = bubbleTime(message.timestamp)
        holder.statusText.visibility = View.GONE

        if (message.content.startsWith(holder.itemView.context.getString(R.string.message_voice_prefix)) || message.content.startsWith("[\u8bed\u97f3]")) {
            holder.bubble.setOnClickListener { onPlayVoiceMessage?.invoke(message.id) }
        } else {
            holder.bubble.setOnClickListener(null)
        }

        holder.bubble.setOnLongClickListener {
            onMessageActions?.invoke(position)
            true
        }

        if (message.isMine) {
            holder.row.gravity = Gravity.END
            val avatarUri = AppStore.profile(holder.itemView.context).optString("avatar_uri", "")
            if (avatarUri.isNotBlank()) {
                try { holder.avatar.setImageURI(Uri.parse(avatarUri)) } catch (_: Exception) {}
            } else {
                holder.avatar.setImageResource(R.drawable.ic_avatar_user)
            }
            holder.avatar.scaleType = ImageView.ScaleType.CENTER_CROP
            holder.bubble.background = holder.itemView.context.getDrawable(R.drawable.bubble_self_background)
            holder.bubble.setTextColor(holder.itemView.context.getColor(R.color.text_primary))
            moveAvatarToEnd(holder)
            setContainerMargins(holder, start = 0, end = 8)
            holder.meta.gravity = Gravity.END
        } else {
            holder.row.gravity = Gravity.START
            holder.avatar.setImageResource(contactAvatarRes(message.contact))
            holder.avatar.scaleType = ImageView.ScaleType.CENTER_CROP
            holder.bubble.background = holder.itemView.context.getDrawable(R.drawable.bubble_other_background)
            holder.bubble.setTextColor(holder.itemView.context.getColor(R.color.text_primary))
            moveAvatarToStart(holder)
            setContainerMargins(holder, start = 7, end = 0)
            holder.meta.gravity = Gravity.START
        }
        holder.bubble.setPadding(
            holder.itemView.dp(13),
            holder.itemView.dp(8),
            holder.itemView.dp(13),
            holder.itemView.dp(8)
        )
        holder.itemView.setPadding(0, 9, 0, 9)
    }

    override fun getItemCount(): Int = messages.size

    private fun moveAvatarToEnd(holder: VH) {
        if (holder.row.indexOfChild(holder.avatar) < holder.row.indexOfChild(holder.container)) {
            holder.row.removeView(holder.avatar)
            holder.row.addView(holder.avatar)
        }
        val lp = holder.avatar.layoutParams as LinearLayout.LayoutParams
        lp.width = holder.itemView.dp(36)
        lp.height = holder.itemView.dp(36)
        lp.marginEnd = holder.itemView.dp(8)
        lp.marginStart = 0
        holder.avatar.layoutParams = lp
    }

    private fun moveAvatarToStart(holder: VH) {
        if (holder.row.indexOfChild(holder.avatar) > holder.row.indexOfChild(holder.container)) {
            holder.row.removeView(holder.avatar)
            holder.row.addView(holder.avatar, 0)
        }
        val lp = holder.avatar.layoutParams as LinearLayout.LayoutParams
        lp.width = holder.itemView.dp(36)
        lp.height = holder.itemView.dp(36)
        lp.marginStart = holder.itemView.dp(14)
        lp.marginEnd = 0
        holder.avatar.layoutParams = lp
    }

    private fun setContainerMargins(holder: VH, start: Int, end: Int) {
        val params = holder.container.layoutParams as LinearLayout.LayoutParams
        params.marginStart = holder.itemView.dp(start)
        params.marginEnd = holder.itemView.dp(end)
        holder.container.layoutParams = params
    }

    class VH(view: View) : RecyclerView.ViewHolder(view) {
        val timeDivider: TextView = view.findViewById(R.id.timeDivider)
        val row: LinearLayout = view.findViewById(R.id.messageRow)
        val avatar: ImageView = view.findViewById(R.id.messageAvatar)
        val container: LinearLayout = view.findViewById(R.id.bubbleContainer)
        val bubble: TextView = view.findViewById(R.id.messageBubble)
        val meta: LinearLayout = view.findViewById(R.id.messageMeta)
        val statusText: TextView = view.findViewById(R.id.statusText)
        val timeText: TextView = view.findViewById(R.id.timeText)
        val systemText: TextView = view.findViewById(R.id.systemText)
    }
}

// ===== Top-level Helpers =====
private fun contactAvatarRes(contact: Contact): Int {
    if (contact.id.startsWith("cloud:")) return cloudProviderLogoRes(contact.id.substringAfter("cloud:"))
    val agentId = agentIdFromContactId(contact.id)
    return when (agentId) {
        "me" -> R.drawable.ic_avatar_user
        "system" -> R.drawable.ic_avatar_system
        "hermes" -> R.drawable.hermes_logo
        "pc_agent" -> R.drawable.ic_avatar_device
        "home_hub" -> R.drawable.ic_avatar_device
        "news_agent" -> R.drawable.ic_avatar_news
        "automation_center" -> R.drawable.ic_send_plane
        "codex" -> R.drawable.logo_codex_product
        "claude" -> R.drawable.logo_claude_code
        "openclaw" -> R.drawable.ic_avatar_custom_agent
        "local-llm" -> R.drawable.ic_avatar_custom_agent
        "cloud-model" -> R.drawable.ic_avatar_cloud_model
        "custom-agent" -> R.drawable.ic_avatar_custom_agent
        else -> if (agentId.endsWith("-agent") || agentId.contains("_agent")) {
            R.drawable.ic_agent_node
        } else if (contact.id.startsWith("hermes:")) {
            R.drawable.ic_avatar_user
        } else {
            R.drawable.ic_avatar_hermes
        }
    }
}

private fun cloudProviderLogoRes(provider: String): Int {
    val key = provider.lowercase(Locale.getDefault())
        .replace(Regex("[^a-z0-9]+"), "-")
        .trim('-')
    return when (key) {
        "openai" -> R.drawable.logo_provider_openai
        "deepseek" -> R.drawable.logo_provider_deepseek
        "anthropic", "claude" -> R.drawable.logo_provider_anthropic
        "google-gemini", "gemini" -> R.drawable.logo_provider_gemini
        "qwen" -> R.drawable.logo_provider_qwen
        "openrouter", "open-router" -> R.drawable.logo_provider_openrouter
        else -> R.drawable.ic_avatar_cloud_model
    }
}

private fun agentIdFromContactId(contactId: String): String =
    if (contactId.startsWith("desktop_") && contactId.contains(":")) {
        contactId.substringAfter(":")
    } else {
        contactId
    }

private val avatarColors = listOf(
    0xFF07C160.toInt(), 0xFF5AC8FA.toInt(), 0xFFFF9500.toInt(),
    0xFFFF2D55.toInt(), 0xFF5856D6.toInt(), 0xFFFFCC02.toInt(),
    0xFF34C759.toInt(), 0xFFAF52DE.toInt(), 0xFFFF3B30.toInt(),
    0xFF007AFF.toInt()
)

private fun avatarColorForName(name: String): Int {
    val index = abs(name.hashCode()) % avatarColors.size
    return avatarColors[index]
}

private fun listTime(timestamp: Long): String {
    val nowKey = dateKey(System.currentTimeMillis())
    return if (dateKey(timestamp) == nowKey) bubbleTime(timestamp)
    else SimpleDateFormat("MM/dd", Locale.CHINA).format(Date(timestamp))
}

private fun bubbleTime(timestamp: Long): String = SimpleDateFormat("HH:mm", Locale.CHINA).format(Date(timestamp))

private fun dayDivider(timestamp: Long): String {
    val today = Calendar.getInstance()
    today.set(Calendar.HOUR_OF_DAY, 0)
    today.set(Calendar.MINUTE, 0)
    today.set(Calendar.SECOND, 0)
    today.set(Calendar.MILLISECOND, 0)
    val timeStr = SimpleDateFormat("HH:mm", Locale.CHINA).format(Date(timestamp))
    return if (timestamp >= today.timeInMillis) {
        timeStr
    } else {
        val yesterdayCal = Calendar.getInstance().apply {
            timeInMillis = today.timeInMillis
            add(Calendar.DAY_OF_MONTH, -1)
        }
        if (timestamp >= yesterdayCal.timeInMillis) "\u6628\u5929 $timeStr"
        else SimpleDateFormat("MM/dd HH:mm", Locale.CHINA).format(Date(timestamp))
    }
}

private fun dateKey(timestamp: Long): String = SimpleDateFormat("yyyyMMdd", Locale.CHINA).format(Date(timestamp))
