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

// ===== Top-level extension functions =====
internal fun View.dp(dp: Int): Int = (dp * resources.displayMetrics.density).toInt()
internal fun Activity.dp(dp: Int): Int = (dp * resources.displayMetrics.density).toInt()
internal fun View.getColorCompat(id: Int): Int = context.getColor(id)
internal fun Activity.getColorCompat(id: Int): Int = getColor(id)

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
    var taskStatusSeq: Long = 0L,
    var remoteMessageId: String = "",
    var attachments: List<PeerChatAttachment> = emptyList(),
    var voiceTranscript: String = "",
    var voiceTranscriptionPending: Boolean = false,
    val historySequence: Long = 0L
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
internal enum class AgentScreenCommandKind {
    TAP,
    TYPE,
    SCROLL
}

// ===== MessageAdapter =====
internal class MessageAdapter(
    initialMessages: List<ChatMessage>,
    internal val onPlayVoiceMessage: ((Long) -> Unit)? = null,
    internal val onMessageActions: ((Int) -> Unit)? = null,
    internal val onOpenAttachment: ((PeerChatAttachment) -> Unit)? = null
) : RecyclerView.Adapter<MessageAdapter.VH>() {

    internal var messages: List<ChatMessage> = MessageListSnapshot.copy(initialMessages)
        private set
    private val updateTracker = MessageAdapterUpdateTracker(messages)

    init {
        setHasStableIds(true)
    }

    fun syncMessages(updatedMessages: List<ChatMessage>) {
        val snapshot = MessageListSnapshot.copy(updatedMessages)
        messages = snapshot
        updateTracker.sync(snapshot, this)
    }

    override fun getItemId(position: Int): Long = messages[position].id

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_message, parent, false)
        return VH(view)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val message = messages[position]
        val messageMaxWidth = holder.messageMaxWidth()
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
        holder.bubble.maxWidth = messageMaxWidth
        holder.bubble.visibility = if (message.content.isBlank()) View.GONE else View.VISIBLE
        holder.bubble.setLineSpacing(0f, 1.18f)
        holder.timeText.text = bubbleTime(message.timestamp)
        holder.statusText.visibility = View.GONE
        holder.attachments.removeAllViews()
        holder.attachments.visibility = if (message.attachments.isEmpty()) View.GONE else View.VISIBLE
        message.attachments.forEach { attachment ->
            holder.attachments.addView(
                when {
                    attachment.mimeType.startsWith("image/") ->
                        peerImageAttachment(holder, attachment, position)
                    attachment.mimeType.startsWith("audio/") ->
                        peerAudioAttachment(holder, message, attachment, position)
                    else -> peerFileAttachment(holder, message, attachment, position)
                }
            )
        }

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
            bindContactAvatar(holder.avatar, CONTACT_ME, localUser = true)
            holder.avatar.scaleType = ImageView.ScaleType.CENTER_CROP
            holder.bubble.background = holder.itemView.context.getDrawable(R.drawable.bubble_self_background)
            holder.bubble.setTextColor(holder.itemView.context.getColor(R.color.text_primary))
            moveAvatarToEnd(holder)
            setContainerMargins(holder, start = 0, end = 8)
            holder.meta.gravity = Gravity.END
        } else {
            holder.row.gravity = Gravity.START
            bindContactAvatar(holder.avatar, message.contact)
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

    override fun onBindViewHolder(holder: VH, position: Int, payloads: MutableList<Any>) {
        if (payloads.isNotEmpty() && payloads.all { it == MessageRowSnapshotFactory.ATTACHMENT_PROGRESS } &&
            holder.attachments.childCount == messages[position].attachments.size) {
            messages[position].attachments.forEachIndexed { index, attachment ->
                if (PeerAttachmentTransferProgress.isActive(attachment.transferProgress, attachment.transferState)) {
                    when (val view = holder.attachments.getChildAt(index)) {
                        is PeerImageAttachmentView -> view.updateProgress(attachment.transferProgress)
                        is PeerFileAttachmentView -> view.updateProgress(attachment)
                    }
                }
            }
        } else onBindViewHolder(holder, position)
    }

    private fun peerImageAttachment(
        holder: VH,
        attachment: PeerChatAttachment,
        position: Int
    ): View = PeerImageAttachmentView(holder.itemView.context).apply {
        val initialSize = agentImageThumbnailSize(1, 2)
        layoutParams = LinearLayout.LayoutParams(
            holder.itemView.dp(initialSize.widthDp),
            holder.itemView.dp(initialSize.heightDp)
        ).apply { topMargin = holder.itemView.dp(4) }
        bind(
            attachment = attachment,
            onOpen = { onOpenAttachment?.invoke(attachment) },
            onLongPress = { onMessageActions?.invoke(position) }
        )
    }

    private fun peerFileAttachment(
        holder: VH,
        message: ChatMessage,
        attachment: PeerChatAttachment,
        position: Int
    ): View = PeerFileAttachmentView(holder.itemView.context).apply {
        bind(
            attachment = attachment,
            mine = message.isMine,
            maxWidthPx = holder.messageMaxWidth(),
            onOpen = { onOpenAttachment?.invoke(attachment) },
            onLongPress = { onMessageActions?.invoke(position) }
        )
        layoutParams = LinearLayout.LayoutParams(
            holder.itemView.dp(238).coerceAtMost(holder.messageMaxWidth()).coerceAtLeast(holder.itemView.dp(200)),
            holder.itemView.dp(68)
        ).apply { topMargin = holder.itemView.dp(4) }
    }

    private fun peerAudioAttachment(
        holder: VH,
        message: ChatMessage,
        attachment: PeerChatAttachment,
        position: Int
    ): View {
        val context = holder.itemView.context
        val audioBubble = TextView(context).apply {
            val seconds = (attachment.durationMillis / 1_000L).coerceAtLeast(1L)
            text = context.getString(R.string.peer_voice_duration, seconds)
            textSize = 15f
            gravity = Gravity.CENTER_VERTICAL
            minWidth = holder.itemView.dp(112)
            setTextColor(context.getColor(R.color.text_primary))
            background = context.getDrawable(
                if (message.isMine) R.drawable.bubble_self_background else R.drawable.bubble_other_background
            )
            setPadding(holder.itemView.dp(13), holder.itemView.dp(9), holder.itemView.dp(13), holder.itemView.dp(9))
            setCompoundDrawablesRelativeWithIntrinsicBounds(R.drawable.ic_rich_play, 0, 0, 0)
            compoundDrawablePadding = holder.itemView.dp(9)
            setOnClickListener { onOpenAttachment?.invoke(attachment) }
            setOnLongClickListener {
                onMessageActions?.invoke(position)
                true
            }
        }
        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = if (message.isMine) Gravity.END else Gravity.START
            addView(audioBubble, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ))
            val transcript = when {
                message.voiceTranscriptionPending -> context.getString(R.string.peer_voice_transcribing)
                message.voiceTranscript.isNotBlank() -> message.voiceTranscript
                else -> ""
            }
            if (transcript.isNotBlank()) {
                addView(TextView(context).apply {
                    text = transcript
                    textSize = 14f
                    maxWidth = holder.messageMaxWidth()
                    setTextColor(context.getColor(R.color.text_secondary))
                    setLineSpacing(0f, 1.12f)
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    ).apply { topMargin = holder.itemView.dp(6) }
                })
            }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = holder.itemView.dp(4) }
        }
    }

    internal fun moveAvatarToEnd(holder: VH) {
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

    internal fun moveAvatarToStart(holder: VH) {
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

    internal fun setContainerMargins(holder: VH, start: Int, end: Int) {
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
        val attachments: LinearLayout = view.findViewById(R.id.messageAttachments)
        val meta: LinearLayout = view.findViewById(R.id.messageMeta)
        val statusText: TextView = view.findViewById(R.id.statusText)
        val timeText: TextView = view.findViewById(R.id.timeText)
        val systemText: TextView = view.findViewById(R.id.systemText)

        fun messageMaxWidth(): Int {
            val appWidth = itemView.rootView.width.takeIf { it > 0 }
                ?: itemView.resources.displayMetrics.widthPixels
            return (appWidth * 0.75f).toInt()
        }
    }
}

// ===== Top-level Helpers =====
internal fun contactAvatarRes(contact: Contact): Int {
    if (contact.id.startsWith("cloud:")) return cloudProviderLogoRes(contact.id.substringAfter("cloud:"))
    if (contact.id.startsWith("desktop_") && !contact.id.contains(":")) return R.drawable.ic_avatar_device
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
        "gemini", "gemini-cli", "google-gemini" -> R.drawable.logo_provider_gemini
        "openclaw" -> R.drawable.ic_avatar_custom_agent
        "local-llm" -> R.drawable.ic_avatar_custom_agent
        "cloud-model" -> R.drawable.ic_avatar_cloud_model
        "custom-agent" -> R.drawable.ic_avatar_custom_agent
        else -> if (agentId.endsWith("-agent") || agentId.contains("_agent")) {
            R.drawable.ic_agent_node
        } else if (contact.id.startsWith("hermes:")) {
            R.drawable.ic_avatar_user
        } else {
            R.drawable.ic_avatar_user
        }
    }
}

internal fun bindContactAvatar(
    imageView: ImageView,
    contact: Contact,
    localUser: Boolean = false
) {
    val context = imageView.context
    imageView.scaleType = ImageView.ScaleType.CENTER_CROP
    imageView.imageTintList = null

    if (localUser || contact.id == CONTACT_ME.id) {
        val profile = AppStore.profile(context)
        val savedAvatar = profile.optString("avatar_uri", "")
        if (savedAvatar.isNotBlank()) {
            runCatching { imageView.setImageURI(Uri.parse(savedAvatar)) }
                .onSuccess { return }
        }
        imageView.setImageDrawable(
            GalaxySSIIdenticonDrawable(GalaxySSICrypto.localIdentitySha256())
        )
        return
    }

    val storedContact = AppStore.contactById(context, contact.id)
    if (storedContact?.optString("type") == "person" || contactUsesGeneratedAvatar(contact)) {
        val fingerprint = storedContact?.optString("identity_fingerprint")
            .orEmpty()
            .ifBlank { contact.id }
        imageView.setImageDrawable(GalaxySSIIdenticonDrawable(fingerprint))
        return
    }

    imageView.setImageResource(contactAvatarRes(contact))
}

internal fun contactUsesGeneratedAvatar(contact: Contact): Boolean {
    if (contact.id.startsWith("cloud:") || contact.id.startsWith("hermes:")) return false
    if (contact.id.startsWith("desktop_") && !contact.id.contains(":")) return false
    val agentId = agentIdFromContactId(contact.id)
    if (agentId.endsWith("-agent") || agentId.contains("_agent")) return false
    return when (agentId) {
        "me", "system", "hermes", "pc_agent", "home_hub", "news_agent",
        "automation_center", "codex", "claude", "gemini", "gemini-cli",
        "google-gemini", "openclaw", "local-llm", "cloud-model", "custom-agent" -> false
        else -> true
    }
}

internal fun cloudProviderLogoRes(provider: String): Int {
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

internal fun agentIdFromContactId(contactId: String): String =
    if (contactId.startsWith("desktop_") && contactId.contains(":")) {
        contactId.substringAfter(":")
    } else {
        contactId
    }

internal val avatarColors = listOf(
    0xFF07C160.toInt(), 0xFF5AC8FA.toInt(), 0xFFFF9500.toInt(),
    0xFFFF2D55.toInt(), 0xFF5856D6.toInt(), 0xFFFFCC02.toInt(),
    0xFF34C759.toInt(), 0xFFAF52DE.toInt(), 0xFFFF3B30.toInt(),
    0xFF007AFF.toInt()
)

internal fun avatarColorForName(name: String): Int {
    val index = abs(name.hashCode()) % avatarColors.size
    return avatarColors[index]
}

internal fun listTime(timestamp: Long): String {
    val nowKey = dateKey(System.currentTimeMillis())
    return if (dateKey(timestamp) == nowKey) bubbleTime(timestamp)
    else SimpleDateFormat("MM/dd", Locale.CHINA).format(Date(timestamp))
}

internal fun bubbleTime(timestamp: Long): String = SimpleDateFormat("HH:mm", Locale.CHINA).format(Date(timestamp))

internal fun dayDivider(timestamp: Long): String {
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

internal fun dateKey(timestamp: Long): String = SimpleDateFormat("yyyyMMdd", Locale.CHINA).format(Date(timestamp))
