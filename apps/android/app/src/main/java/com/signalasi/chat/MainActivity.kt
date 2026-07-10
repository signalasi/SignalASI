package com.signalasi.chat

import android.app.Activity
import android.app.Dialog
import android.content.ClipData
import android.content.ClipboardManager
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
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
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
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlin.math.abs
import kotlin.math.sin

private class BaselineShiftSpan(private val shiftPx: Int) : CharacterStyle(), UpdateAppearance {
    override fun updateDrawState(tp: TextPaint) {
        tp.baselineShift += shiftPx
    }
}

class MainActivity : Activity(), SignalASIMqttClient.Listener {

    companion object {
        private const val REQUEST_IMAGE = 2001
        private const val REQUEST_RECORD_AUDIO = 2002
        private const val REQUEST_IMPORT_BACKUP = 2003
        private const val REQUEST_EXPORT_BACKUP = 2004
        private const val REQUEST_PICK_AVATAR = 2005
        private const val REQUEST_IMPORT_KNOWLEDGE = 2006
        private const val REQUEST_AGENT_SCREEN_CAPTURE = 2007
        private const val HISTORY_PREFS = "signalasi_chat_history"
        private const val OLD_HISTORY_PREFS = "hermes_chat_history"
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
    private lateinit var agentStatusCard: LinearLayout
    private lateinit var agentStatusTitle: TextView
    private lateinit var agentStatusSubtitle: TextView
    private lateinit var agentSafetyBadge: TextView
    private lateinit var agentQuickUnderstandButton: TextView
    private lateinit var agentQuickSaveScreenButton: TextView
    private lateinit var agentQuickSearchKnowledgeButton: TextView
    private lateinit var agentQuickPermissionsButton: TextView
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
    private lateinit var agentSubmitButton: TextView
    private lateinit var agentStepObserveNumber: TextView
    private lateinit var agentStepAnalyzeNumber: TextView
    private lateinit var agentStepPlanNumber: TextView
    private lateinit var agentStepActNumber: TextView
    private lateinit var agentStepObserveStatus: TextView
    private lateinit var agentStepAnalyzeStatus: TextView
    private lateinit var agentStepPlanStatus: TextView
    private lateinit var agentStepActStatus: TextView
    private lateinit var contactPage: LinearLayout
    private lateinit var directoryPage: LinearLayout
    private lateinit var discoverPage: LinearLayout
    private lateinit var mePage: View
    private lateinit var featurePage: LinearLayout
    private lateinit var featureTitle: TextView
    private lateinit var featureContent: LinearLayout
    private lateinit var featureBackButton: TextView
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
    private lateinit var emojiButton: ImageButton
    private lateinit var emojiPanel: HorizontalScrollView
    private lateinit var emojiContainer: LinearLayout
    private lateinit var chatInputBar: LinearLayout
    private lateinit var messageList: RecyclerView
    private lateinit var meProfileText: TextView
    private lateinit var meIdSubtitleText: TextView
    private lateinit var meIdText: TextView
    private lateinit var meAvatar: ImageView
    private lateinit var tabAgent: TextView
    private lateinit var tabMessages: TextView
    private lateinit var tabContacts: TextView
    private lateinit var tabDiscover: TextView
    private lateinit var tabMe: TextView
    private lateinit var featureTabAgent: TextView
    private lateinit var featureTabMessages: TextView
    private lateinit var featureTabContacts: TextView
    private lateinit var featureTabDiscover: TextView
    private lateinit var featureTabMe: TextView
    private lateinit var featureBottomTabs: LinearLayout
    private lateinit var featureBottomDivider: View

    // State
    private val handler = Handler(Looper.getMainLooper())
    private val historyExecutor = Executors.newSingleThreadExecutor()
    private val cloudExecutor = Executors.newCachedThreadPool()
    private val historySaveSeq = AtomicInteger()
    private lateinit var mobileNativeAgent: MobileNativeAgent
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
                    getString(R.string.agent_screen_ocr_ready, result.textLines.size),
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
    private var player: android.media.MediaPlayer? = null
    private var voiceMode = false
    private var secureChannelReady = false
    private var scanMode = "security"
    private var latestAgentScreenContext: ScreenContext? = null
    private val directoryContacts = mutableListOf<Contact>()
    private var pendingExportPassword: String? = null
    private var pendingExportIncludeMessages = true
    private var pendingImportUri: Uri? = null
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
    private var pendingVoiceAgentTranscriptId = 0L
    private var wakeReplyPinnedUntilMs = 0L
    private var lastVoiceRecognitionStartAt = 0L
    private val voiceAssistantScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var wakeWordEngine: WakeWordEngine? = null
    private var wakeWordDetectionJob: Job? = null
    private var androidTts: TextToSpeech? = null
    private var androidTtsReady = false
    private lateinit var microsoftTts: MicrosoftEdgeTts
    private var mainSwipeStartX = 0f
    private var mainSwipeStartY = 0f
    private var mainSwipeHandled = false
    private var lastDebugSendKey: String? = null
    private var lastHistoryLoadedAt = 0L

    private val currentMessages: MutableList<ChatMessage>
        get() {
            val id = selectedContact?.id ?: return mutableListOf()
            return messages.getOrPut(id) { mutableListOf() }
        }

    private fun newMessageId(): Long = nextMessageId++

    override fun attachBaseContext(newBase: Context) {
        val localized = AppLanguage.wrap(newBase)
        val config = Configuration(localized.resources.configuration).apply {
            fontScale = 1.0f
        }
        super.attachBaseContext(localized.createConfigurationContext(config))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppLanguage.applyToResources(this)
        configureSystemBars()
        setContentView(R.layout.activity_main)
        AppStore.ensureInitialized(this)
        mobileNativeAgent = MobileNativeAgent(this)
        microsoftTts = MicrosoftEdgeTts(applicationContext)
        androidTts = TextToSpeech(this) { status ->
            androidTtsReady = status == TextToSpeech.SUCCESS
            if (androidTtsReady) {
                androidTts?.language = Locale.SIMPLIFIED_CHINESE
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
        }

        wakePage = findViewById(R.id.wakePage)
        wakeAnimation = findViewById(R.id.wakeAnimation)
        mainPage = findViewById(R.id.mainPage)
        mainTopBar = findViewById(R.id.mainTopBar)
        agentPage = findViewById(R.id.agentPage)
        agentStatusCard = findViewById(R.id.agentStatusCard)
        agentStatusTitle = findViewById(R.id.agentStatusTitle)
        agentStatusSubtitle = findViewById(R.id.agentStatusSubtitle)
        agentSafetyBadge = findViewById(R.id.agentSafetyBadge)
        agentQuickUnderstandButton = findViewById(R.id.agentQuickUnderstandButton)
        agentQuickSaveScreenButton = findViewById(R.id.agentQuickSaveScreenButton)
        agentQuickSearchKnowledgeButton = findViewById(R.id.agentQuickSearchKnowledgeButton)
        agentQuickPermissionsButton = findViewById(R.id.agentQuickPermissionsButton)
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
        agentSubmitButton = findViewById(R.id.agentSubmitButton)
        agentStepObserveNumber = findViewById(R.id.agentStepObserveNumber)
        agentStepAnalyzeNumber = findViewById(R.id.agentStepAnalyzeNumber)
        agentStepPlanNumber = findViewById(R.id.agentStepPlanNumber)
        agentStepActNumber = findViewById(R.id.agentStepActNumber)
        agentStepObserveStatus = findViewById(R.id.agentStepObserveStatus)
        agentStepAnalyzeStatus = findViewById(R.id.agentStepAnalyzeStatus)
        agentStepPlanStatus = findViewById(R.id.agentStepPlanStatus)
        agentStepActStatus = findViewById(R.id.agentStepActStatus)
        contactPage = findViewById(R.id.contactPage)
        directoryPage = findViewById(R.id.directoryPage)
        discoverPage = findViewById(R.id.discoverPage)
        mePage = findViewById(R.id.mePage)
        featurePage = findViewById(R.id.featurePage)
        featureTitle = findViewById(R.id.featureTitle)
        featureContent = findViewById(R.id.featureContent)
        featureBackButton = findViewById(R.id.featureBackButton)
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
        tabAgent = findViewById(R.id.tabAgent)
        tabMessages = findViewById(R.id.tabMessages)
        tabContacts = findViewById(R.id.tabContacts)
        tabDiscover = findViewById(R.id.tabDiscover)
        tabMe = findViewById(R.id.tabMe)
        featureTabAgent = findViewById(R.id.featureTabAgent)
        featureTabMessages = findViewById(R.id.featureTabMessages)
        featureTabContacts = findViewById(R.id.featureTabContacts)
        featureTabDiscover = findViewById(R.id.featureTabDiscover)
        featureTabMe = findViewById(R.id.featureTabMe)
        featureBottomTabs = findViewById(R.id.featureBottomTabs)
        featureBottomDivider = findViewById(R.id.featureBottomDivider)
        chatInputBar = findViewById(R.id.chatInputBar)
        messageList = findViewById(R.id.messageList)
        val backButton2 = findViewById<TextView>(R.id.backButton)

        loadChatHistory()
        configureMainTabs()
        configureAgentPage()
        configureContacts()
        configureMessages()
        configureInput()
        configureWakePage()
        styleSettingsRows()
        startMessageService()
        showMainTab(PAGE_AGENT)

        SignalASIMqttClient.addListener(this)
        SignalASIMqttClient.connect(this)
        handler.postDelayed({
            handleDebugSendIntent(intent)
            handleDebugIncomingIntent(intent)
        }, 1200)
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent?.getBooleanExtra("signalasi_open_agent", false) == true) {
            intent.removeExtra("signalasi_open_agent")
            showMainTab(PAGE_AGENT)
            renderAgentState(mobileNativeAgent.reloadSession())
        }
        handleDebugSendIntent(intent)
        handleDebugIncomingIntent(intent)
    }

    private fun startMessageService() {
        val intent = Intent(this, MessageService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }

    private fun configureSystemBars() {
        window.statusBarColor = getColorCompat(R.color.bar_bg)
        window.navigationBarColor = getColorCompat(R.color.bar_bg)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            var flags = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                flags = flags or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
            }
            window.decorView.systemUiVisibility = flags
        }
    }

    override fun onDestroy() {
        stopVoiceAssistant()
        voiceAssistantScope.cancel()
        microsoftTts.shutdown()
        androidTts?.stop()
        androidTts?.shutdown()
        stopRecording(send = false)
        saveChatHistory(sync = true)
        SignalASIMqttClient.removeListener(this)
        ScreenPerceptionState.removeVisualListener(agentVisualScreenListener)
        historyExecutor.shutdown()
        super.onDestroy()
    }

    override fun onResume() {
        super.onResume()
        AppForegroundTracker.onActivityResumed()
        AgentConnectorResponseBus.addListener(agentConnectorResponseListener)
        ScreenPerceptionState.addVisualListener(agentVisualScreenListener)
        val reloadedAgentState = mobileNativeAgent.reloadSession()
        val restoredAgentState = if (
            reloadedAgentState.runningTaskCount == 0 && ScreenPerceptionState.hasRecentVisualCapture()
        ) {
            mobileNativeAgent.observeCurrentScreen()
        } else {
            reloadedAgentState
        }
        if (activeMainTab == PAGE_AGENT) renderAgentState(restoredAgentState)
        consumePendingAgentConnectorResponses()
        reloadChatHistoryIfChanged()
    }

    override fun onPause() {
        AgentConnectorResponseBus.removeListener(agentConnectorResponseListener)
        ScreenPerceptionState.removeVisualListener(agentVisualScreenListener)
        saveChatHistory(sync = true)
        AppForegroundTracker.onActivityPaused()
        super.onPause()
    }

    private fun publishAgentConnectorResponse(envelope: JSONObject?, message: ChatMessage) {
        if (envelope?.optString("type").orEmpty().ifBlank { "text" } != "text") return
        val sourceMessageId = envelope?.optString("source_message_id")?.toLongOrNull()
            ?: envelope?.optLong("source_message_id", 0L)?.takeIf { it > 0L }
            ?: return
        AgentConnectorResponseBus.publish(
            this,
            AgentConnectorResponse(
                sourceMessageId = sourceMessageId,
                contactId = envelope?.optString("contact_id").orEmpty().ifBlank { message.contact.id },
                content = message.content
            )
        )
    }

    private fun consumeAgentConnectorResponse(response: AgentConnectorResponse) {
        if (agentOperationInFlight) return
        if (!mobileNativeAgent.canAcceptConnectorResponse(response.sourceMessageId, response.contactId)) return
        AgentConnectorResponseStore.remove(this, response)
        runAgentOperationAsync(
            operation = {
                mobileNativeAgent.acceptConnectorResponse(
                    sourceMessageId = response.sourceMessageId,
                    contactId = response.contactId,
                    content = response.content,
                    success = response.success
                ) ?: mobileNativeAgent.snapshot()
            },
            onComplete = { state ->
                if (VoiceAssistantSettings.get(this).routingMode == VoiceAssistantSettings.ROUTING_MODE_NATIVE_AGENT &&
                    voiceAssistantAwake
                ) {
                    presentVoiceAgentState(state)
                }
            }
        )
    }

    private fun consumePendingAgentConnectorResponses() {
        AgentConnectorResponseStore.pending(this).forEach { response ->
            consumeAgentConnectorResponse(response)
        }
    }

    override fun onBackPressed() {
        if (featurePage.visibility == View.VISIBLE) {
            hideFeaturePage()
            return
        }
        if (chatPage.visibility == View.VISIBLE) {
            chatPage.visibility = View.GONE
            wakePage.visibility = View.GONE
            mainPage.visibility = View.VISIBLE
            showMainTab(PAGE_MESSAGES)
            return
        }
        super.onBackPressed()
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (mainPage.visibility == View.VISIBLE && chatPage.visibility != View.VISIBLE && featurePage.visibility != View.VISIBLE) {
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    mainSwipeStartX = ev.rawX
                    mainSwipeStartY = ev.rawY
                    mainSwipeHandled = false
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = ev.rawX - mainSwipeStartX
                    val dy = ev.rawY - mainSwipeStartY
                    if (!mainSwipeHandled && kotlin.math.abs(dx) > dp(96) && kotlin.math.abs(dx) > kotlin.math.abs(dy) * 1.6f) {
                        showAdjacentMainTab(if (dx < 0) 1 else -1)
                        mainSwipeHandled = true
                        return true
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (mainSwipeHandled) return true
                }
            }
        }
        return super.dispatchTouchEvent(ev)
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
            if (handleVoiceAgentTranscript(envelope)) return@runOnUiThread
            val msg = parseIncomingMessage(payload)
            if (msg.content.isBlank()) return@runOnUiThread
            msg.deliveryTrace.add(newTraceEvent("received", "MQTT inbound"))
            msg.deliveryTrace.add(newTraceEvent("decrypted", "SignalASI Link"))
            addMessage(msg, fromIncoming = true)
            val sourceMessageId = envelope?.optString("source_message_id")?.toLongOrNull()
                ?: envelope?.optLong("source_message_id", 0L)
                ?: 0L
            val nativeAgentResponse = VoiceAssistantSettings.get(this).routingMode ==
                VoiceAssistantSettings.ROUTING_MODE_NATIVE_AGENT &&
                mobileNativeAgent.canAcceptConnectorResponse(sourceMessageId, msg.contact.id)
            publishAgentConnectorResponse(envelope, msg)
            if (!nativeAgentResponse) {
                showVoiceAssistantReply(msg)
                maybeSpeakIncomingReply(msg)
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
        val scanResult = IntentIntegrator.parseActivityResult(requestCode, resultCode, data)
        if (scanResult != null) {
            handleSecurityScan(scanResult.contents)
            return
        }
        if (requestCode == REQUEST_IMPORT_BACKUP && resultCode == RESULT_OK) {
            importBackupFromUri(data?.data ?: return)
            return
        }
        if (requestCode == REQUEST_IMPORT_KNOWLEDGE && resultCode == RESULT_OK) {
            importAgentKnowledgeFromUri(data?.data ?: return)
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
        tabAgent.setOnClickListener { showMainTab(PAGE_AGENT) }
        tabMessages.setOnClickListener { showMainTab(PAGE_MESSAGES) }
        tabContacts.setOnClickListener { showMainTab(PAGE_CONTACTS) }
        tabDiscover.setOnClickListener { showMainTab(PAGE_DISCOVER) }
        tabMe.setOnClickListener { showMainTab(PAGE_SETTINGS) }
        featureTabAgent.setOnClickListener { showMainTab(PAGE_AGENT) }
        featureTabMessages.setOnClickListener { showMainTab(PAGE_MESSAGES) }
        featureTabContacts.setOnClickListener { showMainTab(PAGE_CONTACTS) }
        featureTabDiscover.setOnClickListener { showMainTab(PAGE_DISCOVER) }
        featureTabMe.setOnClickListener { showMainTab(PAGE_SETTINGS) }
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
        findViewById<View>(R.id.signalLinkProtocolButton).setOnClickListener { showSignalLinkProtocolPage() }
        findViewById<View>(R.id.advancedOptionsButton).setOnClickListener { showAdvancedOptionsFeaturePage() }
        findViewById<View>(R.id.localModelSettingsButton).setOnClickListener { showLocalModelFeaturePage() }
        findViewById<View>(R.id.voiceAssistantSettingsButton).setOnClickListener { showVoiceAssistantSettingsPage() }
        findViewById<View>(R.id.onDeviceAgentButton).setOnClickListener { showOnDeviceAgentFeaturePage() }
        findViewById<View>(R.id.destroyDataButton).setOnClickListener { confirmDestroyAllData() }
        backButton.setOnClickListener { showContactPage() }
        featureBackButton.setOnClickListener { hideFeaturePage() }
    }

    private fun configureAgentPage() {
        renderAgentState(restoredOrFreshAgentState())
        agentStatusCard.setOnClickListener {
            val state = mobileNativeAgent.snapshot()
            if (state.currentScreen.isAccessibilityEnabled || ScreenPerceptionState.hasRecentVisualCapture()) {
                renderAgentState(mobileNativeAgent.observeCurrentScreen())
            } else {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
        }
        agentQuickUnderstandButton.setOnClickListener {
            startAgentScreenUnderstanding()
        }
        agentQuickSaveScreenButton.setOnClickListener {
            prefillAgentGoal("save screen")
        }
        agentQuickSearchKnowledgeButton.setOnClickListener {
            prefillAgentGoal("search knowledge ")
        }
        agentQuickPermissionsButton.setOnClickListener {
            showOnDeviceAgentFeaturePage()
        }
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
        agentKnowledgeText.setOnClickListener { openAgentKnowledgeImportPicker() }
        agentSubmitButton.setOnClickListener { handleAgentPrimaryAction() }
        agentGoalInput.setOnEditorActionListener { _, _, _ ->
            submitAgentGoal()
            true
        }
        agentScreenSearchInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                latestAgentScreenContext?.let { renderAgentScreenDetails(it) }
            }
            override fun afterTextChanged(s: Editable?) = Unit
        })
        agentVoiceButton.setOnClickListener {
            val state = mobileNativeAgent.snapshot()
            if (state.phase == AgentPhase.PAUSED) {
                renderAgentState(mobileNativeAgent.resumeCurrentTask())
            } else if (state.phase == AgentPhase.WAITING_RESPONSE) {
                renderAgentState(mobileNativeAgent.cancelCurrentTask())
            } else if (state.pendingAction != null) {
                renderAgentState(mobileNativeAgent.cancelCurrentTask())
            } else if (agentVoiceListening) {
                stopAgentVoiceInput()
            } else {
                startAgentVoiceInput()
            }
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

    private fun restoredOrFreshAgentState(): AgentUiState {
        val state = mobileNativeAgent.snapshot()
        val hasRecoverableState = state.currentGoal.isNotBlank() ||
            state.phase == AgentPhase.PAUSED ||
            state.pendingAction != null ||
            state.lastActionResult != null
        return if (hasRecoverableState) state else mobileNativeAgent.observeCurrentScreen()
    }

    private fun handleAgentPrimaryAction() {
        if (agentOperationInFlight) return
        val state = mobileNativeAgent.snapshot()
        if (state.phase == AgentPhase.PAUSED) {
            renderAgentState(mobileNativeAgent.resumeCurrentTask())
        } else if (state.phase == AgentPhase.WAITING_RESPONSE) {
            return
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
        agentSubmitButton.isEnabled = false
        thread(name = "signalasi-agent-operation") {
            val outcome = runCatching(operation)
            runOnUiThread {
                agentOperationInFlight = false
                agentSubmitButton.isEnabled = true
                val state = outcome.getOrElse { mobileNativeAgent.snapshot() }
                renderAgentState(state)
                onComplete(state)
                consumePendingAgentConnectorResponses()
                outcome.exceptionOrNull()?.let { error ->
                    Toast.makeText(this, error.message ?: "Agent operation failed", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun submitAgentGoal() {
        val goal = agentGoalInput.text?.toString()?.trim().orEmpty()
        if (goal.isBlank()) {
            Toast.makeText(this, getString(R.string.agent_empty_goal), Toast.LENGTH_SHORT).show()
            return
        }
        agentGoalInput.setText("")
        hideKeyboard()
        runAgentOperationAsync { mobileNativeAgent.submitGoal(goal) }
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
        if (config.routingMode == VoiceAssistantSettings.ROUTING_MODE_NATIVE_AGENT) {
            val transcriptStore = VoiceAgentTranscriptStore(this)
            transcriptStore.consume()?.let { transcript ->
                voiceAssistantAwake = true
                processVoiceAgentTranscript(transcript.success, transcript.content)
                return
            }
            pendingVoiceAgentTranscriptId = transcriptStore.pendingRequestId()
            if (pendingVoiceAgentTranscriptId > 0L) {
                voiceAssistantAwake = true
                updateWakeVoiceUi(
                    getString(R.string.voice_status_transcribing),
                    getString(R.string.voice_status_waiting_transcript, voiceAssistantTargetContact(config).name)
                )
                return
            }
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
        pendingVoiceAgentTranscriptId = 0L
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
        val config = VoiceAssistantSettings.get(this)
        if (config.asrProvider == VoiceAssistantSettings.ASR_PROVIDER_PC_STT) {
            startVoiceCommandRecording()
            return
        }
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            updateWakeVoiceUi(getString(R.string.voice_status_asr_unavailable), getString(R.string.voice_status_switch_pc_stt))
            scheduleVoiceRestart(1200L)
            return
        }
        voiceAssistantAwake = true
        updateWakeVoiceUi(getString(R.string.voice_status_awake_listening), getString(R.string.voice_status_say_task))
        startVoiceRecognition()
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

    private fun handleVoiceAgentTranscript(envelope: JSONObject?): Boolean {
        val payload = envelope ?: return false
        if (payload.optString("type") != "voice_transcript") return false
        val sourceMessageId = payload.optString("source_message_id").toLongOrNull()
            ?: payload.optLong("source_message_id", 0L)
        val transcriptStore = VoiceAgentTranscriptStore(this)
        val storedRequestId = transcriptStore.pendingRequestId()
        if (sourceMessageId <= 0L ||
            (sourceMessageId != pendingVoiceAgentTranscriptId && sourceMessageId != storedRequestId)
        ) return true
        if (activeMainTab != PAGE_VOICE || wakePage.visibility != View.VISIBLE) {
            transcriptStore.saveResponse(payload)
            return true
        }
        pendingVoiceAgentTranscriptId = 0L
        transcriptStore.clear()
        processVoiceAgentTranscript(
            payload.optBoolean("transcription_success", false),
            payload.optString("content")
        )
        return true
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
        androidTts?.language = Locale.SIMPLIFIED_CHINESE
        androidTts?.speak(text, TextToSpeech.QUEUE_FLUSH, Bundle(), utteranceId)
        handler.postDelayed({
            if (voiceAssistantSpeaking) {
                voiceAssistantSpeaking = false
                after()
            }
        }, 20_000L)
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
            R.id.signalLinkProtocolButton, R.id.advancedOptionsButton, R.id.localModelSettingsButton,
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
    }

    private fun renderAgentState(state: AgentUiState) {
        val isPlanning = state.phase != AgentPhase.OBSERVING
        val visualScreenReady = ScreenPerceptionState.hasRecentVisualCapture()
        val screenAccessEnabled = state.currentScreen.isAccessibilityEnabled || visualScreenReady
        val pendingAction = state.pendingAction
        agentStatusTitle.text = when {
            !screenAccessEnabled && !isPlanning -> getString(R.string.agent_status_accessibility_needed)
            visualScreenReady && !state.currentScreen.isAccessibilityEnabled && !isPlanning ->
                getString(R.string.agent_status_visual_ready)
            state.phase == AgentPhase.BLOCKED -> getString(R.string.agent_status_blocked)
            state.phase == AgentPhase.WAITING_CONFIRMATION -> getString(R.string.agent_status_waiting_confirmation)
            state.phase == AgentPhase.EXECUTING -> getString(R.string.agent_status_executing)
            state.phase == AgentPhase.VERIFYING -> getString(R.string.agent_status_verifying)
            state.phase == AgentPhase.WAITING_RESPONSE -> getString(R.string.agent_status_waiting_response)
            state.phase == AgentPhase.PAUSED -> getString(R.string.agent_status_paused)
            state.phase == AgentPhase.COMPLETED -> getString(R.string.agent_status_completed)
            state.phase == AgentPhase.FAILED -> getString(R.string.agent_status_failed)
            state.phase == AgentPhase.CANCELLED -> getString(R.string.agent_status_cancelled)
            isPlanning -> getString(R.string.agent_status_planning)
            else -> getString(R.string.agent_status_observing)
        }
        agentStatusSubtitle.text = when {
            !screenAccessEnabled && !isPlanning -> getString(R.string.agent_status_accessibility_needed_subtitle)
            visualScreenReady && !state.currentScreen.isAccessibilityEnabled && !isPlanning ->
                getString(R.string.agent_status_visual_ready_subtitle)
            state.phase == AgentPhase.BLOCKED -> state.plan?.safetyReview?.reason
                ?.ifBlank { getString(R.string.agent_status_blocked_subtitle) }
                ?: getString(R.string.agent_status_blocked_subtitle)
            state.phase == AgentPhase.PAUSED -> getString(R.string.agent_status_paused_subtitle)
            state.phase == AgentPhase.CANCELLED -> getString(R.string.agent_status_cancelled_subtitle)
            pendingAction != null -> getString(R.string.agent_status_confirm_subtitle, pendingAction.description)
            state.lastActionResult != null -> getString(R.string.agent_status_result_subtitle, state.lastActionResult.message)
            isPlanning -> getString(R.string.agent_status_goal_subtitle)
            else -> getString(R.string.agent_status_default_subtitle)
        }
        agentSafetyBadge.text = if (state.phase == AgentPhase.BLOCKED) {
            getString(R.string.agent_badge_blocked)
        } else {
            getString(R.string.agent_badge_safe)
        }
        agentCurrentAppText.text = getString(R.string.agent_current_app_value, state.currentScreen.foregroundApp)
        agentCallableTargetsText.text = when {
            state.phase == AgentPhase.BLOCKED -> getString(
                R.string.agent_blocked_detail_value,
                state.plan?.safetyReview?.reason.orEmpty().ifBlank { getString(R.string.agent_status_blocked_subtitle) },
                state.plan?.safetyReview?.deniedPermissions?.joinToString(", ").orEmpty().ifBlank { "-" }
            )
            state.lastActionResult != null -> getString(R.string.agent_action_result_value, state.lastActionResult.message)
            pendingAction != null -> getString(
                R.string.agent_pending_plan_value,
                pendingAction.description,
                state.plan?.expectedResult.orEmpty().ifBlank { pendingAction.description }
            )
            screenAccessEnabled -> {
                getString(
                    R.string.agent_screen_context_value,
                state.currentScreen.visibleTextCount,
                state.currentScreen.clickableNodeCount,
                state.currentScreen.inputFieldCount
            )
            }
            else -> getString(R.string.agent_accessibility_status_disabled)
        }
        agentRunningTasksText.text = if (state.phase == AgentPhase.BLOCKED) {
            getString(
                R.string.agent_safety_policy_value,
                state.plan?.safetyReview?.mode?.name.orEmpty().ifBlank { state.permissionMode.name },
                state.plan?.safetyReview?.risk?.name?.lowercase(Locale.US).orEmpty().ifBlank { "low" }
            )
        } else if (pendingAction != null) {
            getString(
                R.string.agent_action_risk_value,
                pendingAction.risk.name.lowercase(Locale.US),
                pendingAction.target
            )
        } else {
            getString(
                R.string.agent_running_tasks_targets_value,
                state.runningTaskCount,
                state.runtimeContext.callableCount
            )
        }
        val memorySnapshot = mobileNativeAgent.memorySnapshot()
        agentMemoryText.text = getString(
            R.string.agent_memory_value,
            memorySnapshot.activeCount,
            memorySnapshot.conflicts.size
        )
        agentKnowledgeText.text = getString(
            R.string.agent_knowledge_value,
            state.runtimeContext.knowledgeStats.itemCount,
            state.runtimeContext.knowledgeStats.sourceCount,
            state.runtimeContext.knowledgeItems.size
        )
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
        agentVoiceButton.text = when {
            state.phase == AgentPhase.PAUSED -> getString(R.string.agent_resume_button)
            state.phase == AgentPhase.WAITING_RESPONSE -> getString(R.string.common_cancel)
            pendingAction != null -> getString(R.string.common_cancel)
            else -> getString(R.string.agent_voice_button)
        }
        agentSubmitButton.text = when {
            state.phase == AgentPhase.PAUSED -> "›"
            state.phase == AgentPhase.WAITING_RESPONSE -> "…"
            pendingAction != null -> getString(R.string.agent_confirm_button)
            else -> "›"
        }
        agentSubmitButton.isEnabled = state.phase != AgentPhase.WAITING_RESPONSE && !agentOperationInFlight

        val statusViews = mapOf(
            AgentStepKind.OBSERVE_SCREEN to Pair(agentStepObserveNumber, agentStepObserveStatus),
            AgentStepKind.ANALYZE_GOAL to Pair(agentStepAnalyzeNumber, agentStepAnalyzeStatus),
            AgentStepKind.BUILD_PLAN to Pair(agentStepPlanNumber, agentStepPlanStatus),
            AgentStepKind.CONFIRM_AND_ACT to Pair(agentStepActNumber, agentStepActStatus)
        )
        state.steps.forEach { step ->
            val views = statusViews[step.kind] ?: return@forEach
            val numberView = views.first
            val statusView = views.second
            val current = step.status == AgentStepStatus.CURRENT || step.status == AgentStepStatus.DONE
            numberView.setBackgroundResource(
                if (current) R.drawable.agent_step_number_background else R.drawable.agent_step_number_outline_background
            )
            numberView.setTextColor(
                if (current) getColorCompat(R.color.white) else getColorCompat(R.color.wechat_green)
            )
            statusView.text = when (step.status) {
                AgentStepStatus.CURRENT -> getString(R.string.agent_step_status_current)
                AgentStepStatus.DONE -> getString(R.string.agent_step_status_done)
                AgentStepStatus.WAITING -> getString(R.string.agent_step_status_waiting)
                AgentStepStatus.SAFE -> getString(R.string.agent_step_status_safe)
            }
            statusView.setTextColor(
                if (step.status == AgentStepStatus.CURRENT || step.status == AgentStepStatus.DONE) {
                    getColorCompat(R.color.wechat_green)
                } else {
                    getColorCompat(R.color.text_secondary)
                }
            )
        }
        renderAgentToolbox(state)
        renderAgentActionQueue(state)
        renderAgentRequirements(state)
        renderAgentPlanContext(state)
        renderAgentVerification(state)
        renderAgentRecentTasks(state)
        renderAgentAuditTrail(state)
        latestAgentScreenContext = state.currentScreen
        renderAgentScreenDetails(state.currentScreen)
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
        }
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
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.agent_step_background)
            setPadding(dp(14), dp(10), dp(14), dp(10))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(6) }
            setOnClickListener {
                prefillAgentGoal(agentScreenCommand(label, commandKind))
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
                text = getString(R.string.agent_screen_bounds, item.bounds.ifBlank { "-" })
            })
        }
    }

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
        val inactive = getColorCompat(R.color.text_secondary)
        if (tab != PAGE_AGENT && agentVoiceListening) {
            stopAgentVoiceInput()
        }
        activeMainTab = tab
        wakePage.visibility = if (tab == PAGE_VOICE) View.VISIBLE else View.GONE
        chatPage.visibility = View.GONE
        featurePage.visibility = View.GONE
        if (tab == PAGE_VOICE) {
            startVoiceAssistant()
        } else {
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
                PAGE_SETTINGS -> getString(R.string.tab_settings)
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

        setTabSelected(tabAgent, tab == PAGE_AGENT, inactive)
        setTabSelected(tabMessages, tab == PAGE_MESSAGES, inactive)
        setTabSelected(tabContacts, tab == PAGE_CONTACTS, inactive)
        setTabSelected(tabDiscover, tab == PAGE_DISCOVER, inactive)
        setTabSelected(tabMe, tab == PAGE_SETTINGS, inactive)
        setFeatureTabsSelected(tab)

        val activeIds = listOf(PAGE_AGENT, PAGE_MESSAGES, PAGE_CONTACTS, PAGE_DISCOVER, PAGE_SETTINGS)
        if (tab in activeIds) {
            if (tab == PAGE_AGENT) renderAgentState(mobileNativeAgent.snapshot())
            if (tab == PAGE_MESSAGES) refreshContactList()
            if (tab == PAGE_SETTINGS) refreshMePage()
            if (tab == PAGE_CONTACTS || tab == PAGE_DISCOVER) refreshDirectoryContacts()
        }
    }

    private fun showAdjacentMainTab(direction: Int) {
        val tabs = listOf(PAGE_AGENT, PAGE_MESSAGES, PAGE_CONTACTS, PAGE_DISCOVER, PAGE_SETTINGS)
        val currentIndex = tabs.indexOf(activeMainTab).let { if (it >= 0) it else 0 }
        val nextIndex = Math.floorMod(currentIndex + direction, tabs.size)
        showMainTab(tabs[nextIndex])
    }

    private fun setTabSelected(tabView: TextView, selected: Boolean, inactive: Int) {
        val color = if (selected) getColorCompat(R.color.wechat_green) else inactive
        val iconRes = when (tabView.id) {
            R.id.tabAgent, R.id.featureTabAgent -> if (selected) R.drawable.ic_tab_agent_filled else R.drawable.ic_tab_agent
            R.id.tabMessages, R.id.featureTabMessages -> if (selected) R.drawable.ic_tab_chat_filled else R.drawable.ic_tab_chat
            R.id.tabContacts, R.id.featureTabContacts -> if (selected) R.drawable.ic_tab_contacts else R.drawable.ic_tab_contacts_outline
            R.id.tabDiscover, R.id.featureTabDiscover -> if (selected) R.drawable.ic_tab_discover_filled else R.drawable.ic_tab_discover
            R.id.tabMe, R.id.featureTabMe -> if (selected) R.drawable.ic_tab_settings_filled else R.drawable.ic_tab_settings
            else -> 0
        }
        if (iconRes != 0) {
            val icon = getDrawable(iconRes)
            icon?.setBounds(0, 0, dp(24), dp(24))
            tabView.setCompoundDrawables(null, icon, null, null)
            tabView.compoundDrawablePadding = -dp(8)
        }
        tabView.setTextColor(color)
        tabView.compoundDrawableTintList = android.content.res.ColorStateList.valueOf(color)
        tabView.setTypeface(tabView.typeface, android.graphics.Typeface.NORMAL)
    }

    private fun setFeatureTabsSelected(tab: String = activeMainTab) {
        val inactive = getColorCompat(R.color.text_secondary)
        setTabSelected(featureTabAgent, tab == PAGE_AGENT, inactive)
        setTabSelected(featureTabMessages, tab == PAGE_MESSAGES, inactive)
        setTabSelected(featureTabContacts, tab == PAGE_CONTACTS, inactive)
        setTabSelected(featureTabDiscover, tab == PAGE_DISCOVER, inactive)
        setTabSelected(featureTabMe, tab == PAGE_SETTINGS, inactive)
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
        pressToTalkButton.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                        requestPermissions(arrayOf(android.Manifest.permission.RECORD_AUDIO), REQUEST_RECORD_AUDIO)
                        return@setOnTouchListener true
                    }
                    pressToTalkButton.text = getString(R.string.input_press_to_talk)
                    pressToTalkButton.alpha = 0.72f
                    startRecording()
                    true
                }
                MotionEvent.ACTION_UP -> {
                    pressToTalkButton.text = getString(R.string.input_press_to_talk)
                    pressToTalkButton.alpha = 1f
                    stopRecording(send = true)
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
                    pressToTalkButton.text = getString(R.string.input_press_to_talk)
                    pressToTalkButton.alpha = 1f
                    stopRecording(send = false)
                    true
                }
                else -> true
            }
        }
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
            appendDeliveryTrace(msg.id, contact.id, "local_saved", "missing target topic")
            updateMessageStatus(msg.id, contact.id, getString(R.string.delivery_status_local_saved))
        }
    }

    private fun requestCloudModelReply(contact: Contact, raw: JSONObject, contextTurns: List<ChatMessage>, outgoingId: Long) {
        cloudExecutor.execute {
            val result = runCatching { CloudModelClient.send(this@MainActivity, raw, contextTurns) }
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
        val scanPayload = intent?.getStringExtra("signalasi_debug_scan_payload")?.trim().orEmpty()
        val scanPayloadB64 = intent?.getStringExtra("signalasi_debug_scan_payload_b64")?.trim().orEmpty()
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
            intent?.removeExtra("signalasi_debug_open_language_settings")
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
            return
        }
        intent?.removeExtra("signalasi_debug_revoke")
        intent?.removeExtra("signalasi_debug_status")
        intent?.removeExtra("signalasi_debug_pairing")
        intent?.removeExtra("signalasi_debug_open_agents")
        intent?.removeExtra("signalasi_debug_open_security")
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
            val pairing = JSONObject()
                .put("desktop_id", desktopId)
                .put("desktop_name", "VOICE-PC")
                .put("identity_key_sha256", "VOICE_SETTINGS_SMOKE_FINGERPRINT_0000000000000000000000000000")
            AppStore.markDesktopVerified(this, pairing)
            val codexId = "$desktopId:codex"
            val welcome = "VOICE_SETTINGS_WELCOME_$token"
            VoiceAssistantSettings.setEnabled(this, true)
            VoiceAssistantSettings.setWakeProvider(this, VoiceAssistantSettings.WAKE_PROVIDER_ANDROID_ASR)
            VoiceAssistantSettings.setWakeWords(this, "SignalASI,voice smoke,$token")
            VoiceAssistantSettings.setWakeModel(this, VoiceAssistantSettings.DEFAULT_WAKE_MODEL)
            VoiceAssistantSettings.setWakeThreshold(this, 0.73f)
            VoiceAssistantSettings.setAsrProvider(this, VoiceAssistantSettings.ASR_PROVIDER_ANDROID)
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
                config.asrProvider == VoiceAssistantSettings.ASR_PROVIDER_ANDROID &&
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
        return message.deliveryTrace.takeLast(8).joinToString("\n") { event ->
            val detail = event.detail.takeIf { it.isNotBlank() }?.let { " · $it" }.orEmpty()
            "${bubbleTime(event.at)} ${deliveryTraceLabel(event.stage)}$detail"
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
        if (json?.optString("type") == "pairing_revoked") {
            Log.w("SignalASIDebug", "Pairing revoked control message received")
            val desktopId = json.optString("desktop_id")
            if (desktopId.isNotBlank()) {
                AppStore.deleteDesktopConnector(this, desktopId, deleteMessages = false)
            } else {
                AppStore.deleteContact(this, "hermes", deleteMessages = false)
            }
            SignalASIMqttClient.forgetSecureChannel()
            refreshContactList()
            refreshDirectoryContacts()
            val content = json.optString("content")
                .ifBlank { getString(R.string.system_pairing_revoked_default) }
            return ChatMessage(newMessageId(), content, false, CONTACT_SYSTEM, deliveryTrace = incomingTrace)
        }
        if (json?.optString("type") == "pairing_confirmed" || json?.optString("type") == "connector_status") {
            json.optJSONArray("connector_agents")?.let { agents ->
                AppStore.updateConnectorAgentStatuses(this, agents)
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
        val content = json?.optString("content", payload)?.takeIf { it.isNotBlank() } ?: payload
        val sender = json?.optString("sender", "hermes") ?: "hermes"
        val contactId = json?.optString("contact_id", CONTACT_HERMES.id)?.takeIf { it.isNotBlank() } ?: CONTACT_HERMES.id
        val contact = contactById(if (sender == "system") CONTACT_SYSTEM.id else contactId)
        return ChatMessage(newMessageId(), content, sender == "self", contact, deliveryTrace = incomingTrace)
    }

    // ===== Recording =====
    private fun startRecording() {
        val file = File(cacheDir, "voice_${System.currentTimeMillis()}.m4a")
        recordingFile = file
        recordingStartedAt = System.currentTimeMillis()
        recorder = MediaRecorder().apply {
            setAudioSource(MediaRecorder.AudioSource.MIC)
            setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            setOutputFile(file.absolutePath)
            prepare()
            start()
        }
        voiceButton.alpha = 0.55f
        showVoiceOverlay()
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
        val activeRecorder = recorder ?: return
        recorder = null
        voiceButton.alpha = 1f
        hideVoiceOverlay()
        runCatching { activeRecorder.stop() }
        activeRecorder.release()
        val file = recordingFile
        recordingFile = null
        if (!send || file == null || !file.exists()) {
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
        val topic = AppStore.outgoingTopicForContact(this, contact.id) ?: return false
        val requestId = newMessageId()
        val transcriptStore = VoiceAgentTranscriptStore(this)
        pendingVoiceAgentTranscriptId = requestId
        transcriptStore.begin(requestId)
        val sent = runCatching {
            val audioBase64 = Base64.encodeToString(sourceFile.readBytes(), Base64.NO_WRAP)
            SignalASIMqttClient.publishInlineAudioMessage(
                fileName = sourceFile.name,
                size = sourceFile.length(),
                contentType = "audio/mp4",
                audioBase64 = audioBase64,
                contactId = contact.id,
                topicOverride = topic,
                audioMode = "transcribe_only",
                clientMessageId = requestId
            )
        }.getOrElse {
            Log.e("SignalASIVoice", "Voice Agent transcription request failed", it)
            false
        }
        if (!sent) {
            pendingVoiceAgentTranscriptId = 0L
            transcriptStore.clear()
        }
        sourceFile.delete()
        return sent
    }

    private fun publishInlineVoiceFile(messageId: Long, contact: Contact, file: File) {
        val target = AppStore.outgoingTopicForContact(this, contact.id) ?: "signalasichat/android/send"
        Log.i("SignalASIVoice", "Inline voice publish begin target=${contact.id} topic=$target bytes=${file.length()} messageId=$messageId")
        val sent = runCatching {
            Log.i("SignalASIVoice", "Inline voice read begin file=${file.name}")
            val audioBase64 = Base64.encodeToString(file.readBytes(), Base64.NO_WRAP)
            Log.i("SignalASIVoice", "Inline voice read done b64=${audioBase64.length}")
            Log.i("SignalASIVoice", "Inline voice mqtt publish begin")
            SignalASIMqttClient.publishInlineAudioMessage(
                fileName = file.name,
                size = file.length(),
                contentType = "audio/mp4",
                audioBase64 = audioBase64,
                contactId = contact.id,
                topicOverride = target
            ).also { Log.i("SignalASIVoice", "Inline voice mqtt publish returned=$it") }
        }.getOrElse {
            Log.e("SignalASIVoice", "Inline voice publish failed", it)
            false
        }
        Log.i("SignalASIVoice", "Inline voice publish result=$sent target=${contact.id} bytes=${file.length()} messageId=$messageId")
        updateMessageStatus(messageId, contact.id, if (sent) getString(R.string.delivery_status_mqtt_inline) else getString(R.string.delivery_status_send_failed))
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
        refreshVisibleMessages(stored.contact.id)
        refreshContactList()
    }

    private fun deleteMessageAt(contactId: String, position: Int) {
        val list = messages[contactId] ?: return
        if (position < 0 || position >= list.size) return
        list.removeAt(position)
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
        featureContent.addView(featureRow(getString(R.string.message_delete_title), getString(R.string.message_delete_subtitle), R.drawable.ic_delete, getString(R.string.common_delete)).apply {
            setOnClickListener {
                deleteMessageAt(contact.id, position)
                Toast.makeText(this@MainActivity, getString(R.string.toast_deleted), Toast.LENGTH_SHORT).show()
                showChatPage(contact)
            }
        })
        addSectionTitle(getString(R.string.section_details))
        featureContent.addView(featureRow(getString(R.string.message_sent_time), bubbleTime(message.timestamp), R.drawable.ic_protocol_link, ""))
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
        migrateHistoryPrefs()
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
                val message = ChatMessage(
                    id = item.optLong("id", newMessageId()),
                    content = savedContent,
                    isMine = item.optBoolean("isMine"),
                    contact = messageContact,
                    isSystem = item.optBoolean("isSystem"),
                    timestamp = item.optLong("timestamp", System.currentTimeMillis()),
                    deliveryStatus = item.optString("deliveryStatus").takeIf { it.isNotBlank() },
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
    }

    private fun reloadChatHistoryIfChanged(force: Boolean = false) {
        migrateHistoryPrefs()
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
        mergeDeliveryTrace(messageId, contactId, trace, status)
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
        migrateHistoryPrefs()
        val snapshot = messages.mapValues { (_, list) ->
            list.takeLast(MAX_SAVED_MESSAGES_PER_CONTACT).map { it.copy() }
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

    private fun migrateHistoryPrefs() {
        val oldPrefs = getSharedPreferences(OLD_HISTORY_PREFS, MODE_PRIVATE)
        val newPrefs = getSharedPreferences(HISTORY_PREFS, MODE_PRIVATE)
        if (oldPrefs.all.isEmpty() || newPrefs.all.isNotEmpty()) return
        val editor = newPrefs.edit()
        oldPrefs.all.forEach { (key, value) ->
            when (value) {
                is String -> editor.putString(key, value)
                is Int -> editor.putInt(key, value)
                is Long -> editor.putLong(key, value)
                is Boolean -> editor.putBoolean(key, value)
                is Float -> editor.putFloat(key, value)
                is Set<*> -> {
                    @Suppress("UNCHECKED_CAST")
                    editor.putStringSet(key, value as Set<String>)
                }
            }
        }
        editor.commit()
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
        messages.keys.forEach { id ->
            if (id == CONTACT_SYSTEM.id) return@forEach
            val contact = contactById(id)
            if (items.none { it.id == contact.id } && AppStore.canCommunicateWith(this, contact.id)) {
                items.add(contact)
            }
        }
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
        id == "local-llm" -> 3
        id.startsWith("cloud:") -> 4
        id == "custom-agent" -> 5
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
                if (apiKey.isBlank() || modelId.isBlank() || endpoint.isBlank()) {
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
        featureBackButton.setOnClickListener { showChatPage(contact) }
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
                if (SignalASICrypto.verifyPcIdentityFromQr(contents)) {
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
        AppStore.markDesktopVerified(this, pairingQr)
        SignalASIMqttClient.publishPairingClaim(pairingQr)
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
        meProfileText.text = name
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
        val fixedAgentIds = setOf("hermes", "codex", "claude", "local-llm", "custom-agent")
        val coreAgents = listOf(
            AgentUi("hermes", "Hermes", getString(R.string.agent_private_assistant_subtitle), R.drawable.ic_avatar_hermes, if (connected("hermes")) getString(R.string.status_running) else getString(R.string.status_pending_pairing), "#14C66A", connected("hermes")),
            AgentUi("codex", agentName("codex", "Codex"), detail("codex", getString(R.string.agent_codex_subtitle)), R.drawable.logo_codex_product, badge("codex", getString(R.string.common_paired)), color("codex", "#5B6CFF"), connected("codex")),
            AgentUi("claude", agentName("claude", "Claude Code"), detail("claude", getString(R.string.agent_claude_subtitle)), R.drawable.logo_claude_code, badge("claude", getString(R.string.common_paired)), color("claude", "#FF6B5F"), connected("claude")),
            AgentUi("local-llm", agentName("local-llm", "Local LLM"), detail("local-llm", getString(R.string.agent_local_llm_subtitle)), R.drawable.ic_avatar_custom_agent, badge("local-llm", getString(R.string.common_paired)), color("local-llm", "#00A7A7"), connected("local-llm")),
            AgentUi("custom-agent", agentName("custom-agent", "Custom Agent"), detail("custom-agent", getString(R.string.agent_custom_subtitle)), R.drawable.ic_avatar_custom_agent, badge("custom-agent", getString(R.string.common_paired)), color("custom-agent", "#6C7A89"), connected("custom-agent"))
        )
        val agents = coreAgents + dynamicConnectorAgents(fixedAgentIds) + listOf(
            AgentUi("news_agent", "News Agent", getString(R.string.agent_news_subtitle), R.drawable.ic_agent_node, getString(R.string.badge_automation), "#F0A500", connected("news_agent")),
            AgentUi("home_hub", "Home Agent", getString(R.string.agent_home_subtitle), R.drawable.ic_device_node, getString(R.string.badge_device), "#6C7A89", connected("home_hub"))
        )
        showFeaturePage("AI Agent", showBottomTabs = true)
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
        showFeaturePage(getString(R.string.device_management_title))
        featureContent.addView(featureHeroCard(getString(R.string.device_management_title), getString(R.string.device_management_subtitle), R.drawable.ic_device_node, "#5B6CFF", getString(R.string.count_devices, customDevices.size + 3)))
        addSectionTitle(getString(R.string.section_my_devices))
        featureContent.addView(featureRow("Phone Agent", getString(R.string.device_phone_agent_subtitle), R.drawable.ic_device_node, getString(R.string.status_online)))
        featureContent.addView(featureRow("PC Agent", getString(R.string.device_pc_agent_subtitle), R.drawable.ic_device_node, getString(R.string.status_online)))
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
        featureContent.addView(featureRow(getString(R.string.device_file_sync), getString(R.string.device_file_sync_subtitle), R.drawable.ic_import, getString(R.string.status_enabled)))
        featureContent.addView(featureRow(getString(R.string.device_remote_control), getString(R.string.device_remote_control_subtitle), R.drawable.ic_security_shield, getString(R.string.status_protected)))
    }

    private fun showCustomDeviceConnectorEditor(connector: CustomDeviceConnector) {
        showFeaturePage(getString(R.string.device_custom_editor_title))
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
        val lastActivityAt: Long
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
                    lastActivityAt = contacts.maxOfOrNull { it.optLong("setup_updated_at", it.optLong("created_at", 0L)) } ?: 0L
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
        featureContent.addView(featureHeroCard(device.name, getString(R.string.security_verified_desktop_connector), R.drawable.ic_device_node, "#14C66A", getString(R.string.count_items, device.agentCount)))
        addSectionTitle(getString(R.string.security_section_identity))
        featureContent.addView(featureRow("Desktop ID", device.id, R.drawable.ic_protocol_link, getString(R.string.common_copy)).apply {
            setOnClickListener { copyText(device.id, getString(R.string.security_copied_desktop_id)) }
        })
        featureContent.addView(featureRow(getString(R.string.security_desktop_fingerprint), formatFingerprint(device.fingerprint), R.drawable.ic_security_shield, getString(R.string.common_copy)).apply {
            setOnClickListener { copyText(device.fingerprint, getString(R.string.security_copied_desktop_fingerprint)) }
        })
        featureContent.addView(featureRow(getString(R.string.security_last_active_title), securityTime(device.lastActivityAt), R.drawable.ic_protocol_link, ""))
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
        showFeaturePage("Signal Link Protocol")
        featureContent.addView(featureHeroCard("Signal Link Protocol", getString(R.string.protocol_version_subtitle), R.drawable.ic_protocol_link, "#5B6CFF", getString(R.string.protocol_badge_stable)))
        addSectionTitle(getString(R.string.protocol_section_layers))
        featureContent.addView(featureRow(getString(R.string.protocol_identity_layer), getString(R.string.protocol_identity_layer_subtitle), R.drawable.ic_security_shield, getString(R.string.protocol_badge_enabled)))
        featureContent.addView(featureRow(getString(R.string.protocol_session_layer), getString(R.string.protocol_session_layer_subtitle), R.drawable.ic_protocol_link, getString(R.string.protocol_badge_enabled)))
        featureContent.addView(featureRow(getString(R.string.protocol_transport_layer), getString(R.string.protocol_transport_layer_subtitle), R.drawable.ic_device_node, "MQTT"))
        addSectionTitle(getString(R.string.protocol_section_current_endpoint))
        featureContent.addView(featureRow(getString(R.string.protocol_pc_endpoint), getString(R.string.protocol_pc_endpoint_subtitle), R.drawable.ic_avatar_hermes, getString(R.string.protocol_badge_online)))
    }

    private fun showAdvancedOptionsFeaturePage() {
        showFeaturePage(getString(R.string.advanced_options_title))
        featureContent.addView(featureHeroCard(getString(R.string.advanced_options_title), getString(R.string.advanced_options_subtitle), R.drawable.ic_protocol_link, "#8E8E93", getString(R.string.advanced_badge_careful)))
        addSectionTitle(getString(R.string.advanced_section_diagnostics))
        featureContent.addView(featureRow(getString(R.string.advanced_protocol_logs), getString(R.string.advanced_protocol_logs_subtitle), R.drawable.ic_protocol_link, getString(R.string.common_view)))
        featureContent.addView(featureRow(getString(R.string.advanced_agent_permission_audit), getString(R.string.advanced_agent_permission_audit_subtitle), R.drawable.ic_security_shield, getString(R.string.common_view)))
        addSectionTitle(getString(R.string.advanced_section_experiments))
        featureContent.addView(featureRow(getString(R.string.advanced_force_relay), getString(R.string.advanced_force_relay_subtitle), R.drawable.ic_device_node, getString(R.string.common_off)))
        featureContent.addView(featureRow(getString(R.string.advanced_debug_mode), getString(R.string.advanced_debug_mode_subtitle), R.drawable.ic_agent_node, getString(R.string.common_off)))
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
        featureContent.addView(featureRow(getString(R.string.voice_asr_provider), asrProviderLabel(config.asrProvider), R.drawable.ic_agent_node, getString(R.string.common_select)).apply {
            setOnClickListener {
                val pcStt = getString(R.string.voice_asr_provider_pc)
                val androidAsr = getString(R.string.voice_asr_provider_android)
                showChoiceDialog(getString(R.string.voice_asr_provider), listOf(pcStt, androidAsr), asrProviderLabel(config.asrProvider)) {
                    val provider = if (it == pcStt) {
                        VoiceAssistantSettings.ASR_PROVIDER_PC_STT
                    } else {
                        VoiceAssistantSettings.ASR_PROVIDER_ANDROID
                    }
                    VoiceAssistantSettings.setAsrProvider(this@MainActivity, provider)
                    showVoiceAssistantSettingsPage()
                }
            }
        })
        featureContent.addView(featureRow(getString(R.string.voice_asr_language), config.asrLanguage, R.drawable.ic_protocol_link, getString(R.string.common_select)).apply {
            setOnClickListener {
                showChoiceDialog(getString(R.string.voice_asr_language), listOf("zh-CN", "en-US", "zh-HK", "zh-TW"), config.asrLanguage) {
                    VoiceAssistantSettings.setAsrLanguage(this@MainActivity, it)
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

    private fun wakeProviderLabel(provider: String): String =
        if (provider == VoiceAssistantSettings.WAKE_PROVIDER_ANDROID_ASR) getString(R.string.voice_wake_engine_android_asr) else getString(R.string.voice_wake_engine_openwakeword)

    private fun asrProviderLabel(provider: String): String =
        if (provider == VoiceAssistantSettings.ASR_PROVIDER_ANDROID) getString(R.string.voice_asr_provider_android) else getString(R.string.voice_asr_provider_pc)

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
            setOnClickListener { showAgentMemoryPage() }
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
        featureContent.addView(featureRow(getString(R.string.on_device_agent_microphone), getString(R.string.on_device_agent_microphone_subtitle), R.drawable.ic_agent_node, getString(R.string.permission_allowed)))
        featureContent.addView(featureRow(getString(R.string.on_device_agent_camera), getString(R.string.on_device_agent_camera_subtitle), R.drawable.ic_scan, getString(R.string.permission_allowed)))
        featureContent.addView(featureRow(getString(R.string.on_device_agent_location), getString(R.string.on_device_agent_location_subtitle), R.drawable.ic_device_node, getString(R.string.permission_while_using)))
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

    private fun showAgentMemoryPage() {
        showFeaturePage(getString(R.string.agent_memory_title))
        val snapshot = mobileNativeAgent.memorySnapshot()
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
                    setOnClickListener { showAgentMemoryConflictDialog(conflict) }
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
                    setOnClickListener { showAgentMemoryItemActions(item) }
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

    private fun showAgentMemoryConflictDialog(conflict: AgentMemoryConflict) {
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
                    resolveAgentMemoryConflict(conflict, candidates[which], null)
                } else {
                    val initial = candidates.joinToString("\n") { it.value }
                    showTextSettingDialog(getString(R.string.agent_memory_merge_title), initial) { merged ->
                        resolveAgentMemoryConflict(conflict, candidates.last(), merged)
                    }
                }
            }
            .setNegativeButton(getString(R.string.common_cancel), null)
            .show()
    }

    private fun resolveAgentMemoryConflict(
        conflict: AgentMemoryConflict,
        selected: AgentMemoryItem,
        mergedValue: String?
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
        showAgentMemoryPage()
    }

    private fun showAgentMemoryItemActions(item: AgentMemoryItem) {
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
                        showAgentMemoryPage()
                    }
                    1 -> {
                        mobileNativeAgent.setMemoryItemImportant(item.id, !item.important)
                        showAgentMemoryPage()
                    }
                    2 -> confirmAgentMemoryDeletion(item)
                }
            }
            .setNegativeButton(getString(R.string.common_cancel), null)
            .show()
    }

    private fun confirmAgentMemoryDeletion(item: AgentMemoryItem) {
        android.app.AlertDialog.Builder(this)
            .setTitle(getString(R.string.agent_memory_delete_title))
            .setMessage(getString(R.string.agent_memory_delete_message, item.value.take(120)))
            .setPositiveButton(getString(R.string.common_delete)) { _, _ ->
                if (mobileNativeAgent.deleteMemoryItem(item.id)) {
                    Toast.makeText(this, getString(R.string.agent_memory_deleted), Toast.LENGTH_SHORT).show()
                }
                showAgentMemoryPage()
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

    private fun showAgentModelPlannerSourceDialog() {
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
                showOnDeviceAgentFeaturePage()
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
                if (id.isBlank() || selected.optString("cloud_model").isBlank()) continue
                if (selected.optString("cloud_endpoint").isBlank() || selected.optString("cloud_api_key").isBlank()) continue
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
                    Toast.makeText(this@MainActivity, getString(R.string.language_changed), Toast.LENGTH_SHORT).show()
                    recreate()
                }
            }
        }
    }

    private fun showFeaturePage(title: String, showBottomTabs: Boolean = false) {
        stopVoiceAssistant()
        wakePage.visibility = View.GONE
        mainPage.visibility = View.GONE
        chatPage.visibility = View.GONE
        featurePage.visibility = View.VISIBLE
        featureBottomDivider.visibility = if (showBottomTabs) View.VISIBLE else View.GONE
        featureBottomTabs.visibility = if (showBottomTabs) View.VISIBLE else View.GONE
        featureTitle.text = title
        featureContent.removeAllViews()
        featureContent.gravity = Gravity.NO_GRAVITY
        featureBackButton.setOnClickListener { hideFeaturePage() }
        if (showBottomTabs) setFeatureTabsSelected()
    }

    private fun hideFeaturePage() {
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

    private fun confirmDestroyAllData() {
        showFeaturePage(getString(R.string.destroy_data_title))
        featureContent.addView(featureHeroCard(
            getString(R.string.destroy_data_hero_title),
            getString(R.string.destroy_data_hero_subtitle),
            R.drawable.ic_delete,
            "#FF3B30",
            getString(R.string.destroy_data_badge)
        ))
        addSectionTitle(getString(R.string.destroy_data_scope))
        featureContent.addView(featureRow(getString(R.string.destroy_data_regenerate_identity), getString(R.string.destroy_data_regenerate_identity_subtitle), R.drawable.ic_security_shield, getString(R.string.common_clear)))
        featureContent.addView(featureRow(getString(R.string.destroy_data_contacts), getString(R.string.destroy_data_contacts_subtitle), R.drawable.ic_group, getString(R.string.common_clear)))
        featureContent.addView(featureRow(getString(R.string.destroy_data_messages), getString(R.string.destroy_data_messages_subtitle), R.drawable.ic_delete, getString(R.string.common_clear)))
        featureContent.addView(TextView(this).apply {
            text = getString(R.string.destroy_data_title)
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            textSize = 17f
            background = GradientDrawable().apply {
                cornerRadius = dp(8).toFloat()
                setColor(Color.parseColor("#FF3B30"))
            }
            setOnClickListener {
                AppStore.destroyAllPrivateData(this@MainActivity)
                messages.clear()
                summaries.clear()
                directoryContacts.clear()
                currentMessages.clear()
                Toast.makeText(this@MainActivity, getString(R.string.destroy_data_success), Toast.LENGTH_LONG).show()
                restartFreshApp()
            }
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(46)).apply {
            topMargin = dp(18)
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

    private fun importAgentKnowledgeFromUri(uri: Uri) {
        runCatching {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        thread(name = "signalasi-knowledge-import") {
            val result = AgentKnowledgeImporter(applicationContext).importDocument(uri)
            runOnUiThread {
                renderAgentState(mobileNativeAgent.recordKnowledgeImport(result))
                Toast.makeText(this, result.message, Toast.LENGTH_LONG).show()
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
    val deliveryTrace: MutableList<DeliveryTraceEvent> = mutableListOf()
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

