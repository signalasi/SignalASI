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

internal fun MainActivity.addMessage(msg: ChatMessage, fromIncoming: Boolean = false) {
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
    if (!stored.isSystem) {
        val summary = summaries.getOrPut(stored.contact.id) { ContactSummary() }
        summary.lastMessage = stored.content.ifBlank { stored.attachments.firstOrNull()?.name.orEmpty() }
        summary.lastAt = stored.timestamp
        if (fromIncoming && (chatPage.visibility != View.VISIBLE || selectedContact?.id != stored.contact.id)) {
            summary.unreadCount += 1
        }
    }
    saveChatHistory(stored)
    if (!stored.isSystem && !AppStore.isDesktopDeviceContact(this, stored.contact.id)) {
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
    refreshDirectoryContacts()
}

internal fun MainActivity.deleteMessageAt(contactId: String, position: Int) {
    val list = messages[contactId] ?: return
    if (position < 0 || position >= list.size) return
    val removed = list.removeAt(position)
    PeerImageThumbnailRepository.remove(removed.attachments)
    PeerIncomingAttachmentStore.deleteLocalCopies(this, removed.attachments)
    if (!removed.isSystem) {
        GlobalConversationEventBus.publishChatMessageDeleted(this, contactId, removed.id)
    }
    discardPendingChatHistory(listOf(removed.id))
    runCatching {
        historyExecutor.execute {
            ChatHistoryStore.deleteMessage(this, removed.id)
            handler.post {
                if (!isDestroyed) loadChatOverview(force = true, reloadSelectedChat = false)
            }
        }
    }
    if (chatPage.visibility == View.VISIBLE && selectedContact?.id == contactId) {
        messageList.post {
            messageAdapter?.syncMessages(currentMessages)
        }
    }
}

internal fun MainActivity.showMessageActions(position: Int) {
    val contact = selectedContact ?: return
    val message = currentMessages.getOrNull(position) ?: return
    val actions = PeerMessageActionPolicy.actionsFor(message)
    AlertDialog.Builder(this)
        .setItems(actions.map { action ->
            when (action) {
                PeerMessageAction.COPY -> getString(R.string.common_copy)
                PeerMessageAction.DELETE -> getString(R.string.message_delete_title)
            }
        }.toTypedArray()) { dialog, which ->
            when (actions[which]) {
                PeerMessageAction.COPY -> {
                    val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    clipboard.setPrimaryClip(
                        ClipData.newPlainText(
                            "GalaxySSI message",
                            PeerMessageActionPolicy.copyText(message)
                        )
                    )
                    Toast.makeText(this, getString(R.string.toast_copied), Toast.LENGTH_SHORT).show()
                }
                PeerMessageAction.DELETE -> {
                    val currentPosition = messages[contact.id]
                        ?.indexOfFirst { candidate -> candidate.id == message.id }
                        ?: -1
                    if (currentPosition >= 0) {
                        deleteMessageAt(contact.id, currentPosition)
                        Toast.makeText(this, getString(R.string.toast_deleted), Toast.LENGTH_SHORT).show()
                    }
                }
            }
            dialog.dismiss()
        }
        .show()
}

internal fun MainActivity.refreshVisibleMessages(contactId: String) {
    if (chatPage.visibility != View.VISIBLE || selectedContact?.id != contactId) return
    messageList.post {
        if (chatPage.visibility == View.VISIBLE && selectedContact?.id == contactId) {
            val followLatest = ChatMessageViewportPolicy.followLatest(
                systemNotifications = contactId == CONTACT_SYSTEM.id,
                nearBottom = isMessageListNearBottom()
            )
            messageAdapter?.syncMessages(currentMessages)
            if (followLatest) scrollToBottom()
        }
    }
}

internal fun MainActivity.isMessageListNearBottom(): Boolean {
    val layout = messageList.layoutManager as? LinearLayoutManager ?: return true
    val itemCount = messageAdapter?.itemCount ?: return true
    if (itemCount <= 1) return true
    return layout.findLastVisibleItemPosition() >= itemCount - 3
}

internal fun MainActivity.loadChatHistory() {
    messages.clear()
    loadedHistoryContacts.clear()
    historyPageCursors.clear()
    historyHasMore.clear()
    historyForwardPageCursors.clear()
    historyHasNewer.clear()
    historyLoadsInFlight.clear()
    loadChatOverview(force = true)
}

internal fun MainActivity.reloadChatHistoryIfChanged(force: Boolean = false) {
    loadChatOverview(force)
}

internal fun MainActivity.loadChatOverview(
    force: Boolean,
    reloadSelectedChat: Boolean = true
) {
    runCatching {
        historyExecutor.execute {
            runCatching {
                val removedTransportMessages = ChatHistoryStore.pruneInternalTransportMessages(this)
                if (removedTransportMessages > 0) {
                    Log.i("GalaxySSIHistory", "Removed internal transport messages count=$removedTransportMessages")
                }
                val updatedVersion = ChatHistoryStore.updatedVersion(this)
                if (!force && updatedVersion <= lastHistoryLoadedAt) return@execute
                val rows = ChatHistoryStore.contactSummaries(this)
                updatedVersion to rows
            }.onSuccess { (updatedVersion, rows) ->
                handler.post {
                    if (isDestroyed) return@post
                    val storedContactIds = rows.mapTo(mutableSetOf()) { it.contactId }
                    rows.forEach { row ->
                        val message = storedChatMessage(row.contactId, row.lastMessage) ?: return@forEach
                        val existing = summaries[row.contactId]
                        if (existing == null || message.timestamp >= existing.lastAt) {
                            summaries[row.contactId] = ContactSummary(
                                lastMessage = message.content,
                                lastAt = message.timestamp,
                                unreadCount = row.unreadCount
                            )
                        }
                    }
                    val liveContactIds = messages
                        .filterValues { it.isNotEmpty() }
                        .keys
                    summaries.keys.toList().forEach { contactId ->
                        if (contactId !in storedContactIds && contactId !in liveContactIds) {
                            summaries.remove(contactId)
                        }
                    }
                    lastHistoryLoadedAt = maxOf(lastHistoryLoadedAt, updatedVersion)
                    if (
                        rows.isEmpty() &&
                        pendingHistoryMessages.isEmpty() &&
                        messages.values.all { it.isEmpty() }
                    ) {
                        seedWelcomeSystemNotification()
                    }
                    refreshDirectoryContacts()
                    val selectedId = selectedContact?.id
                    if (reloadSelectedChat && selectedId != null && chatPage.visibility == View.VISIBLE) {
                        loadLatestChatHistory(selectedId, force = true, scrollAfterLoad = false)
                    }
                }
            }.onFailure { error ->
                Log.e("GalaxySSIHistory", "Could not load chat overview", error)
            }
        }
    }
}

internal fun MainActivity.loadLatestChatHistory(
    contactId: String,
    force: Boolean,
    scrollAfterLoad: Boolean,
    scrollToStartAfterLoad: Boolean = false
) {
    if (!force && contactId in loadedHistoryContacts) {
        when {
            scrollToStartAfterLoad -> messageList.post(::scrollToMessageListStart)
            scrollAfterLoad -> messageList.post(::scrollToBottom)
        }
        return
    }
    val loadKey = "latest:$contactId"
    if (!historyLoadsInFlight.add(loadKey)) return
    val requestStartedAt = SystemClock.elapsedRealtime()
    runCatching {
        historyExecutor.execute {
            runCatching {
                val queryStartedAt = SystemClock.elapsedRealtime()
                val page = ChatHistoryStore.page(
                    this,
                    contactId,
                    pageSize = CHAT_HISTORY_PAGE_ITEMS
                )
                Triple(
                    page,
                    ChatHistoryStore.updatedVersion(this),
                    SystemClock.elapsedRealtime() - queryStartedAt
                )
            }.onSuccess { (page, updatedVersion, queryElapsedMillis) ->
                handler.post {
                    historyLoadsInFlight.remove(loadKey)
                    if (isDestroyed) return@post
                    val list = messages.getOrPut(contactId) { mutableListOf() }
                    val combined = linkedMapOf<Long, ChatMessage>()
                    page.messages.mapNotNull { storedChatMessage(contactId, it) }
                        .forEach { combined[it.id] = it }
                    list.forEach { combined[it.id] = it }
                    val sorted = combined.values.sortedWith(
                        compareBy<ChatMessage> { it.timestamp }.thenBy { it.id }
                    )
                    list.clear()
                    list.addAll(sorted.takeLast(CHAT_HISTORY_WINDOW_ITEMS))
                    loadedHistoryContacts.add(contactId)
                    historyPageCursors[contactId] = list.firstHistorySequence()
                    historyHasMore[contactId] = page.hasMore || sorted.size > list.size
                    historyForwardPageCursors[contactId] = null
                    historyHasNewer[contactId] = false
                    lastHistoryLoadedAt = maxOf(lastHistoryLoadedAt, updatedVersion)
                    if (chatPage.visibility == View.VISIBLE && selectedContact?.id == contactId) {
                        messageAdapter?.syncMessages(currentMessages)
                        when {
                            scrollToStartAfterLoad -> messageList.post(::scrollToMessageListStart)
                            scrollAfterLoad -> messageList.post(::scrollToBottom)
                        }
                    }
                    Log.i(
                        "GalaxySSIHistory",
                        "latest_page query_ms=$queryElapsedMillis " +
                            "total_ms=${SystemClock.elapsedRealtime() - requestStartedAt} " +
                            "items=${page.messages.size} loaded=${list.size} has_more=${page.hasMore}"
                    )
                }
            }.onFailure { error ->
                handler.post { historyLoadsInFlight.remove(loadKey) }
                Log.e("GalaxySSIHistory", "Could not load latest chat page", error)
            }
        }
    }.onFailure {
        historyLoadsInFlight.remove(loadKey)
    }
}

internal fun MainActivity.loadOlderChatHistory(contactId: String) {
    if (historyHasMore[contactId] != true) return
    val beforeSequence = historyPageCursors[contactId] ?: return
    val loadKey = "older:$contactId"
    if (!historyLoadsInFlight.add(loadKey)) return
    val requestStartedAt = SystemClock.elapsedRealtime()
    runCatching {
        historyExecutor.execute {
            runCatching {
                val queryStartedAt = SystemClock.elapsedRealtime()
                ChatHistoryStore.page(
                    this,
                    contactId,
                    beforeSequenceExclusive = beforeSequence,
                    pageSize = CHAT_HISTORY_PAGE_ITEMS
                ) to (SystemClock.elapsedRealtime() - queryStartedAt)
            }.onSuccess { (page, queryElapsedMillis) ->
                handler.post {
                    historyLoadsInFlight.remove(loadKey)
                    if (isDestroyed) return@post
                    val list = messages.getOrPut(contactId) { mutableListOf() }
                    val existingIds = list.mapTo(mutableSetOf(), ChatMessage::id)
                    val older = page.messages
                        .mapNotNull { storedChatMessage(contactId, it) }
                        .filter { existingIds.add(it.id) }
                    historyPageCursors[contactId] = page.nextBeforeSequence
                    historyHasMore[contactId] = page.hasMore
                    if (older.isEmpty()) return@post
                    val layout = messageList.layoutManager as? LinearLayoutManager
                    val firstVisible = layout?.findFirstVisibleItemPosition() ?: 0
                    val firstOffset = layout?.findViewByPosition(firstVisible)?.top ?: 0
                    list.addAll(0, older)
                    val overflow = (list.size - CHAT_HISTORY_WINDOW_ITEMS).coerceAtLeast(0)
                    if (overflow > 0) {
                        repeat(overflow) { list.removeAt(list.lastIndex) }
                        historyForwardPageCursors[contactId] = list.lastHistorySequence()
                        historyHasNewer[contactId] = true
                    }
                    if (chatPage.visibility == View.VISIBLE && selectedContact?.id == contactId) {
                        messageAdapter?.syncMessages(currentMessages)
                        layout?.scrollToPositionWithOffset(firstVisible + older.size, firstOffset)
                    }
                    Log.i(
                        "GalaxySSIHistory",
                        "older_page query_ms=$queryElapsedMillis " +
                            "total_ms=${SystemClock.elapsedRealtime() - requestStartedAt} " +
                            "items=${page.messages.size} loaded=${list.size} has_more=${page.hasMore}"
                    )
                }
            }.onFailure { error ->
                handler.post { historyLoadsInFlight.remove(loadKey) }
                Log.e("GalaxySSIHistory", "Could not load older chat page", error)
            }
        }
    }.onFailure {
        historyLoadsInFlight.remove(loadKey)
    }
}

internal fun MainActivity.loadNewerChatHistory(contactId: String) {
    if (historyHasNewer[contactId] != true) return
    val afterSequence = historyForwardPageCursors[contactId] ?: return
    val loadKey = "newer:$contactId"
    if (!historyLoadsInFlight.add(loadKey)) return
    val requestStartedAt = SystemClock.elapsedRealtime()
    runCatching {
        historyExecutor.execute {
            runCatching {
                val queryStartedAt = SystemClock.elapsedRealtime()
                ChatHistoryStore.pageAfter(
                    this,
                    contactId,
                    afterSequenceExclusive = afterSequence,
                    pageSize = CHAT_HISTORY_PAGE_ITEMS
                ) to (SystemClock.elapsedRealtime() - queryStartedAt)
            }.onSuccess { (page, queryElapsedMillis) ->
                handler.post {
                    historyLoadsInFlight.remove(loadKey)
                    if (isDestroyed) return@post
                    val list = messages.getOrPut(contactId) { mutableListOf() }
                    val existingIds = list.mapTo(mutableSetOf(), ChatMessage::id)
                    val newer = page.messages
                        .mapNotNull { storedChatMessage(contactId, it) }
                        .filter { existingIds.add(it.id) }
                    historyForwardPageCursors[contactId] = page.nextAfterSequence
                    historyHasNewer[contactId] = page.hasMore
                    if (newer.isEmpty()) return@post
                    val layout = messageList.layoutManager as? LinearLayoutManager
                    val firstVisible = layout?.findFirstVisibleItemPosition() ?: 0
                    val firstOffset = layout?.findViewByPosition(firstVisible)?.top ?: 0
                    list.addAll(newer)
                    val overflow = (list.size - CHAT_HISTORY_WINDOW_ITEMS).coerceAtLeast(0)
                    if (overflow > 0) {
                        repeat(overflow) { list.removeAt(0) }
                        historyPageCursors[contactId] = list.firstHistorySequence()
                        historyHasMore[contactId] = true
                    }
                    if (chatPage.visibility == View.VISIBLE && selectedContact?.id == contactId) {
                        messageAdapter?.syncMessages(currentMessages)
                        layout?.scrollToPositionWithOffset(
                            (firstVisible - overflow).coerceAtLeast(0),
                            firstOffset
                        )
                    }
                    Log.i(
                        "GalaxySSIHistory",
                        "newer_page query_ms=$queryElapsedMillis " +
                            "total_ms=${SystemClock.elapsedRealtime() - requestStartedAt} " +
                            "items=${page.messages.size} loaded=${list.size} has_more=${page.hasMore}"
                    )
                }
            }.onFailure { error ->
                handler.post { historyLoadsInFlight.remove(loadKey) }
                Log.e("GalaxySSIHistory", "Could not load newer chat page", error)
            }
        }
    }.onFailure {
        historyLoadsInFlight.remove(loadKey)
    }
}

private fun List<ChatMessage>.firstHistorySequence(): Long? =
    firstOrNull { it.historySequence > 0L }?.historySequence

private fun List<ChatMessage>.lastHistorySequence(): Long? =
    lastOrNull { it.historySequence > 0L }?.historySequence

internal fun MainActivity.storedChatMessage(contactId: String, item: JSONObject): ChatMessage? {
    val contact = contactById(contactId) ?: return null
    val savedId = item.optLong("id", 0L)
    val savedContent = PeerChatPresentation.storedContent(item.optString("content"))
    val attachments = PeerChatAttachment.decode(item.optJSONArray("attachments"))
    if (savedId <= 0L || (savedContent.isBlank() && attachments.isEmpty())) return null
    val messageContact = contactById(item.optString("contactId", contactId)) ?: contact
    val deliveryTrace = parseDeliveryTrace(item.optJSONArray("deliveryTrace"))
    if (item.optBoolean("isRead") && deliveryTrace.none { it.stage == "read" }) {
        deliveryTrace.add(
            DeliveryTraceEvent(
                stage = "read",
                at = item.optLong("readAt", item.optLong("timestamp", System.currentTimeMillis())),
                detail = "chat_opened"
            )
        )
    }
    return ChatMessage(
        id = savedId,
        content = savedContent,
        isMine = item.optBoolean("isMine"),
        contact = messageContact,
        isSystem = item.optBoolean("isSystem"),
        timestamp = item.optLong("timestamp", System.currentTimeMillis()),
        deliveryStatus = item.optString("deliveryStatus").takeIf { it.isNotBlank() },
        taskId = item.optString("taskId"),
        taskStatus = item.optString("taskStatus"),
        taskStatusSeq = item.optLong("taskStatusSeq", 0L),
        remoteMessageId = item.optString("remoteMessageId"),
        deliveryTrace = deliveryTrace,
        attachments = attachments,
        voiceTranscript = item.optString("voiceTranscript"),
        historySequence = item.optLong(ChatHistoryDatabase.HISTORY_SEQUENCE, 0L)
    )
}

internal fun MainActivity.seedWelcomeSystemNotification() {
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
    saveChatHistory(welcome)
}

internal fun MainActivity.parseDeliveryTrace(array: JSONArray?): MutableList<DeliveryTraceEvent> {
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

internal fun MainActivity.incomingDeliveryTrace(json: JSONObject?): MutableList<DeliveryTraceEvent> {
    if (json == null) return mutableListOf()
    return parseDeliveryTrace(json.optJSONArray("delivery_trace") ?: json.optJSONArray("deliveryTrace"))
}

internal fun MainActivity.applyDeliveryAck(json: JSONObject, trace: List<DeliveryTraceEvent>) {
    val messageId = GalaxySSILinkDeliveryAckPolicy.clientSourceMessageId(json).toLongOrNull()
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

internal fun MainActivity.deliveryTraceJson(trace: List<DeliveryTraceEvent>): JSONArray {
    val array = JSONArray()
    trace.forEach { event ->
        array.put(JSONObject()
            .put("stage", event.stage)
            .put("at", event.at)
            .put("detail", event.detail))
    }
    return array
}

internal fun MainActivity.saveChatHistory(message: ChatMessage) {
    saveChatHistory(listOf(message))
}

internal fun MainActivity.saveChatHistory(changedMessages: Collection<ChatMessage>) {
    if (changedMessages.isEmpty()) return
    changedMessages.forEach { message ->
        pendingHistoryMessages[message.id] = chatHistoryJson(message)
    }
    handler.removeCallbacks(historySaveRunnable)
    handler.postDelayed(historySaveRunnable, 120L)
}

internal fun MainActivity.flushChatHistoryAsync() {
    handler.removeCallbacks(historySaveRunnable)
    enqueuePendingChatHistorySave()
}

internal fun MainActivity.flushChatHistoryForRuntimeBoundary() {
    handler.removeCallbacks(historySaveRunnable)
    if (pendingHistoryMessages.isEmpty()) return
    val batch = pendingHistoryMessages.values.toList()
    val saved = runCatching { ChatHistoryStore.upsertAll(this, batch) }
        .onSuccess { lastHistoryLoadedAt = ChatHistoryStore.updatedVersion(this) }
        .onFailure { error ->
            Log.e("GalaxySSIHistory", "Could not persist pending messages at runtime boundary", error)
        }
        .isSuccess
    if (saved) pendingHistoryMessages.clear()
}

internal fun MainActivity.discardPendingChatHistory(messageIds: Collection<Long>) {
    messageIds.forEach(pendingHistoryMessages::remove)
    if (pendingHistoryMessages.isEmpty()) handler.removeCallbacks(historySaveRunnable)
}

internal fun MainActivity.enqueuePendingChatHistorySave() {
    if (pendingHistoryMessages.isEmpty()) return
    val batch = pendingHistoryMessages.values.map { JSONObject(it.toString()) }
    pendingHistoryMessages.clear()
    runCatching {
        historyExecutor.execute {
            ChatHistoryStore.upsertAll(this, batch)
            lastHistoryLoadedAt = ChatHistoryStore.updatedVersion(this)
        }
    }
}

internal fun MainActivity.chatHistoryJson(message: ChatMessage): JSONObject =
    JSONObject()
        .put("id", message.id)
        .put("content", message.content)
        .put("isMine", message.isMine)
        .put("contactId", message.contact.id)
        .put("isSystem", message.isSystem)
        .put("isRead", hasTraceStage(message, "read"))
        .put("readAt", message.deliveryTrace.lastOrNull { it.stage == "read" }?.at ?: 0L)
        .put("timestamp", message.timestamp)
        .put("deliveryStatus", message.deliveryStatus ?: "")
        .put("taskId", message.taskId)
        .put("taskStatus", message.taskStatus)
        .put("taskStatusSeq", message.taskStatusSeq)
        .put("remoteMessageId", message.remoteMessageId)
        .put("voiceTranscript", message.voiceTranscript)
        .put("attachments", PeerChatAttachment.encode(message.attachments))
        .put("deliveryTrace", deliveryTraceJson(message.deliveryTrace))

// ===== Refreshing =====

internal fun MainActivity.refreshDirectoryContacts() {
    val items = buildDirectoryContacts()
    runOnUiThread {
        conversationHubContactsChangedListener?.invoke(items)
    }
}

internal fun MainActivity.buildDirectoryContacts(): List<Contact> = storedContacts()

internal fun MainActivity.buildChatContacts(): List<Contact> {
    val items = storedContacts().toMutableList()
    items.sortWith(compareBy<Contact> { contactPriority(it.id) }.thenBy { it.name.lowercase(Locale.getDefault()) })
    items.add(CONTACT_SYSTEM)
    return items
}

internal fun MainActivity.storedContacts(): List<Contact> {
    val contacts = AppStore.contacts(this)
    val items = mutableListOf<Contact>()
    for (i in 0 until contacts.length()) {
        val c = contacts.optJSONObject(i) ?: continue
        if (c.optBoolean("deleted", false)) continue
        if (c.optString("trust_state") == "deleted") continue
        val id = c.optString("id").ifBlank { jsonGalaxySSIId(c) }
        if (id.isBlank()) continue
        val name = c.optString("name", id)
        items.add(Contact(id, name, ""))
    }
    return items.sortedWith(compareBy<Contact> { contactPriority(it.id) }.thenBy { it.name.lowercase(Locale.getDefault()) })
}

internal fun MainActivity.contactPriority(id: String): Int = when {
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

internal fun MainActivity.showAddContactMenu() {
    showFeaturePage(getString(R.string.add_contact_title))
    featureContent.addView(featureHeroCard(
        getString(R.string.add_contact_hero_title),
        getString(R.string.add_contact_hero_subtitle),
        R.drawable.galaxyssi_mark,
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

internal fun MainActivity.showCloudProviderPage(returnToContacts: Boolean = false) {
    showFeaturePage(getString(R.string.cloud_models_title))
    if (returnToContacts) {
        setFeatureBackAction {
            hideFeaturePage()
            showConversationHub(ConversationHubTab.CONTACTS)
        }
    }
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
            setOnClickListener { showCloudModelPage(provider, returnToContacts) }
        })
    }
}

internal fun MainActivity.showCloudModelPage(provider: String, returnToContacts: Boolean = false) {
    showFeaturePage(provider)
    setFeatureBackAction { showCloudProviderPage(returnToContacts) }
    featureContent.addView(featureHeroCard(provider, providerSubtitle(provider), providerIcon(provider), providerColor(provider), getString(R.string.cloud_select_model)))
    addSectionTitle(getString(R.string.cloud_section_model))
    modelsForProvider(provider).forEach { preset ->
        featureContent.addView(featureRow(preset.name, "", R.drawable.ic_protocol_link, getString(R.string.common_select)).apply {
            setOnClickListener { showCloudModelConfigPage(preset, returnToContacts) }
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
                ), returnToContacts)
            }
        })
    }
}

internal fun MainActivity.debugSeedCloudProvider(provider: String): Contact? {
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
            "sk-galaxyssi-smoke-key",
            preset.apiStyle
        )
    }
    val contact = raw ?: return null
    refreshDirectoryContacts()
    return Contact(contact.getString("id"), contact.optString("name", normalizedProvider), "")
}

internal fun MainActivity.showCloudModelConfigPage(
    preset: CloudModelPreset,
    returnToContacts: Boolean = false
) {
    showFeaturePage(getString(R.string.cloud_config_title))
    setFeatureBackAction { showCloudModelPage(preset.provider, returnToContacts) }
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
                Toast.makeText(this@showCloudModelConfigPage, getString(R.string.cloud_required_fields), Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val raw = AppStore.addCloudModelContact(
                this@showCloudModelConfigPage,
                nameInput.text?.toString()?.trim().orEmpty(),
                preset.provider,
                modelId,
                endpoint,
                apiKey,
                preset.apiStyle
            )
            val contact = Contact(raw.getString("id"), raw.optString("name", modelId), "")
            Toast.makeText(this@showCloudModelConfigPage, getString(R.string.cloud_added_model, preset.name), Toast.LENGTH_SHORT).show()
            refreshDirectoryContacts()
            if (returnToContacts) {
                hideFeaturePage()
                showConversationHub(ConversationHubTab.CONTACTS)
            } else {
                showChatPage(contact)
            }
        }
    }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(46)).apply { topMargin = dp(20) })
}

internal fun MainActivity.showCloudModelSwitchPage(contact: Contact) {
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
                    val switched = AppStore.setSelectedCloudModel(this@showCloudModelSwitchPage, contact.id, modelId)
                    if (!switched) {
                        val endpoint = model.optString("endpoint").ifBlank { raw.optString("cloud_endpoint") }
                        val apiKey = model.optString("api_key").ifBlank { raw.optString("cloud_api_key") }
                        val apiStyle = model.optString("api_style").ifBlank { raw.optString("cloud_api_style", "openai") }
                        if (endpoint.isBlank() || apiKey.isBlank()) {
                            Toast.makeText(this@showCloudModelSwitchPage, getString(R.string.cloud_configure_api_key_first, provider), Toast.LENGTH_SHORT).show()
                            return@setOnClickListener
                        }
                        AppStore.addCloudModelContact(this@showCloudModelSwitchPage, modelName, provider, modelId, endpoint, apiKey, apiStyle)
                        AppStore.setSelectedCloudModel(this@showCloudModelSwitchPage, contact.id, modelId)
                    }
                    Toast.makeText(this@showCloudModelSwitchPage, getString(R.string.cloud_switched_model, modelName), Toast.LENGTH_SHORT).show()
                    showChatPage(contact)
                }
            })
        }
    }
}

internal fun MainActivity.cloudModelInput(label: String, value: String = "", password: Boolean = false): EditText {
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

internal fun MainActivity.cloudProviders(): List<String> = CLOUD_MODEL_PRESETS.map { it.provider }.distinct()

internal fun MainActivity.modelsForProvider(provider: String): List<CloudModelPreset> =
    CLOUD_MODEL_PRESETS.filter { it.provider == provider }

internal fun MainActivity.providerSubtitle(provider: String): String = when (provider) {
    "OpenAI" -> getString(R.string.cloud_provider_openai_subtitle)
    "Anthropic" -> getString(R.string.cloud_provider_anthropic_subtitle)
    "Google Gemini" -> getString(R.string.cloud_provider_gemini_subtitle)
    "DeepSeek" -> getString(R.string.cloud_provider_deepseek_subtitle)
    "Qwen" -> getString(R.string.cloud_provider_qwen_subtitle)
    "OpenRouter" -> getString(R.string.cloud_provider_openrouter_subtitle)
    else -> getString(R.string.cloud_provider_custom_subtitle)
}

internal fun MainActivity.providerIcon(provider: String): Int = cloudProviderLogoRes(provider)

internal fun MainActivity.providerColor(provider: String): String = when (provider) {
    "OpenAI" -> "#14C66A"
    "Anthropic" -> "#FF6B5F"
    "Google Gemini" -> "#5B6CFF"
    "DeepSeek" -> "#3F84FF"
    "Qwen" -> "#00A7A7"
    "OpenRouter" -> "#7C5CFF"
    else -> "#6C7A89"
}

internal fun MainActivity.providerTitleWithModelTag(provider: String): SpannableString {
    val suffix = "  ● ${getString(R.string.cloud_model_tag)}"
    return SpannableString(provider + suffix).apply {
        val dotStart = provider.length + 2
        setSpan(RelativeSizeSpan(0.61f), dotStart, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        setSpan(BaselineShiftSpan(dp(1)), dotStart, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        setSpan(ForegroundColorSpan(getColorCompat(R.color.wechat_green)), dotStart, dotStart + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        setSpan(ForegroundColorSpan(getColorCompat(R.color.text_secondary)), dotStart + 2, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    }
}

internal fun MainActivity.modelDisplayLabel(modelId: String): String {
    if (modelId.isBlank()) return getString(R.string.cloud_select_model)
    val lower = modelId.lowercase(Locale.getDefault())
    if (lower.startsWith("gpt-")) return "GPT-" + modelId.substringAfter("-").replace("-", " ")
    if (lower.startsWith("deepseek-")) return modelId
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

internal fun MainActivity.selectedCloudModelLabel(contactId: String): String {
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

internal fun MainActivity.modelSelectorLabel(label: String): SpannableString {
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

internal fun MainActivity.shortModelLabel(modelId: String): String {
    if (modelId.isBlank()) return getString(R.string.cloud_model_tag)
    return if (modelId.length <= 12) modelId else modelId.take(10) + "..."
}

internal fun MainActivity.startSecurityScan() {
    IntentIntegrator(this).apply {
        setDesiredBarcodeFormats("QR_CODE")
        setOrientationLocked(false)
        setCameraId(0)
        setBeepEnabled(false)
        setBarcodeImageEnabled(false)
        initiateScan()
    }
}

internal fun MainActivity.handleSecurityScan(contents: String?, autoConfirm: Boolean = false) {
    if (contents.isNullOrBlank()) return
    try {
        val scanned = JSONObject(contents)
        val pairingQr = GalaxySSILinkProtocol.normalizePairingQr(scanned)
        if (pairingQr != null) {
            if (GalaxySSILinkProtocol.validatePairingQr(pairingQr) &&
                GalaxySSICrypto.verifyPcIdentityFromQr(pairingQr.toString())
            ) {
                if (autoConfirm) {
                    completeDesktopPairing(pairingQr)
                } else {
                    showDesktopPairingConfirmPage(pairingQr)
                }
            } else {
                Toast.makeText(this, getString(R.string.pairing_invalid_identity_qr), Toast.LENGTH_LONG).show()
            }
        } else {
            val phoneQr = PhoneContactCard.normalizeQr(scanned)
            if (phoneQr != null && AppStore.importContactQrAsRequest(this, phoneQr.toString())) {
                val requestPublished = GalaxySSIMqttClient.publishPhoneContactRequest(phoneQr)
                Toast.makeText(
                    this,
                    getString(
                        if (requestPublished) {
                            R.string.phone_contact_scan_added
                        } else {
                            R.string.phone_contact_scan_pending
                        },
                        phoneQr.optString("name", getString(R.string.fallback_contact_name))
                    ),
                    Toast.LENGTH_LONG
                ).show()
                refreshDirectoryContacts()
                showFriendRequestsDialog()
            } else {
                Toast.makeText(this, getString(R.string.pairing_invalid_identity_qr), Toast.LENGTH_LONG).show()
            }
        }
    } catch (e: Exception) {
        Toast.makeText(this, getString(R.string.pairing_scan_failed, e.message.orEmpty()), Toast.LENGTH_SHORT).show()
    }
}

internal fun MainActivity.showDesktopPairingConfirmPage(pairingQr: JSONObject) {
    val desktopName = pairingQr.optString("desktop_display_name")
        .ifBlank { pairingQr.optJSONObject("desktop_device")?.optString("display_name").orEmpty() }
        .ifBlank { pairingQr.optString("desktop_name") }
        .ifBlank { "PC" }
    val desktopId = pairingQr.optString("desktop_id").ifBlank {
        "desktop_${pairingQr.optString("identity_key_sha256").take(16)}"
    }
    val pairingAccess = GalaxySSILinkProtocol.pairingAccess(
        pairingQr.optJSONObject("pairing_access")
    ) ?: return
    showFeaturePage(getString(R.string.pairing_confirm_title))
    featureContent.addView(featureHeroCard(desktopName, getString(R.string.pairing_confirm_subtitle), R.drawable.ic_security_shield, "#14C66A", getString(R.string.pairing_pending_confirm)))
    addSectionTitle(getString(R.string.pairing_section_device))
    featureContent.addView(featureRow("Desktop ID", desktopId, R.drawable.ic_device_node, getString(R.string.common_copy)).apply {
        setOnClickListener { copyText(desktopId, getString(R.string.security_copied_desktop_id)) }
    })
    addSectionTitle(getString(R.string.pairing_section_after_confirm))
    featureContent.addView(featureRow(getString(R.string.pairing_save_trust), getString(R.string.pairing_save_trust_subtitle), R.drawable.ic_protocol_link, getString(R.string.status_enabled)))
    featureContent.addView(
        featureRow(
            getString(
                if (pairingAccess.fullDesktopExecutor) {
                    R.string.pairing_access_full
                } else {
                    R.string.pairing_access_restricted
                }
            ),
            getString(
                if (pairingAccess.fullDesktopExecutor) {
                    R.string.pairing_access_full_subtitle
                } else {
                    R.string.pairing_access_restricted_subtitle
                }
            ),
            R.drawable.ic_security_shield,
            getString(R.string.status_enabled)
        )
    )
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

internal fun MainActivity.completeDesktopPairing(pairingQr: JSONObject) {
    if (!GalaxySSIMqttClient.publishPairingClaim(pairingQr)) {
        Toast.makeText(this, getString(R.string.pairing_scan_failed, "GalaxySSI Link is offline"), Toast.LENGTH_LONG).show()
        return
    }
    val pairedName = pairingQr.optString("desktop_display_name")
        .ifBlank { pairingQr.optJSONObject("desktop_device")?.optString("display_name").orEmpty() }
        .ifBlank { pairingQr.optString("desktop_name", "PC") }
    Toast.makeText(this, getString(R.string.desktop_pairing_waiting, pairedName), Toast.LENGTH_LONG).show()
    refreshDirectoryContacts()
    hideFeaturePage()
    showConversationHub(ConversationHubTab.CONTACTS)
}

internal fun MainActivity.copyText(value: String, toast: String) {
    getSystemService(ClipboardManager::class.java)
        .setPrimaryClip(ClipData.newPlainText("GalaxySSI", value))
    Toast.makeText(this, toast, Toast.LENGTH_SHORT).show()
}

internal fun MainActivity.showMyQrPayload() {
    val payload = runCatching {
        PhoneContactCard.compactQr(AppStore.myQrPayload(this)).toString()
    }.getOrElse { error ->
        Log.e("GalaxySSIPhoneQr", "Could not build canonical phone contact QR", error)
        Toast.makeText(this, getString(R.string.contact_qr_generation_failed), Toast.LENGTH_LONG).show()
        return
    }
    GalaxySSIMqttClient.refreshOpaqueSubscriptions(this)
    val qrCodeBitmap = runCatching { qrBitmap(payload, 720) }.getOrElse { error ->
        Log.e("GalaxySSIPhoneQr", "Could not render phone contact QR", error)
        Toast.makeText(this, getString(R.string.contact_qr_generation_failed), Toast.LENGTH_LONG).show()
        return
    }
    showFeaturePage(getString(R.string.contact_my_qr_title))
    featureContent.gravity = Gravity.CENTER_HORIZONTAL
    featureContent.addView(LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        setPadding(dp(20), dp(24), dp(20), dp(24))
        background = getDrawable(R.drawable.glass_card_background)
        addView(ImageView(this@showMyQrPayload).apply {
            setImageBitmap(qrCodeBitmap)
            setBackgroundColor(Color.WHITE)
            setPadding(dp(10), dp(10), dp(10), dp(10))
        }, LinearLayout.LayoutParams(dp(260), dp(260)))
        addView(TextView(this@showMyQrPayload).apply {
            text = AppStore.profile(this@showMyQrPayload).optString("name", getString(R.string.app_name))
            gravity = Gravity.CENTER
            setTextColor(getColorCompat(R.color.text_primary))
            textSize = 18f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(0, dp(18), 0, dp(4))
        })
        addView(TextView(this@showMyQrPayload).apply {
            text = GalaxySSICrypto.localGalaxySSIId()
            gravity = Gravity.CENTER
            setTextColor(getColorCompat(R.color.text_secondary))
            textSize = 12f
        })
        addView(TextView(this@showMyQrPayload).apply {
            text = getString(R.string.contact_my_qr_subtitle)
            gravity = Gravity.CENTER
            setTextColor(getColorCompat(R.color.text_secondary))
            textSize = 13f
            setPadding(dp(14), dp(14), dp(14), 0)
        })
    }, LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT,
        LinearLayout.LayoutParams.WRAP_CONTENT
    ).apply { topMargin = dp(8) })
    featureContent.gravity = Gravity.NO_GRAVITY
}

internal fun MainActivity.qrBitmap(content: String, size: Int): Bitmap {
    val matrix = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, size, size)
    return Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888).apply {
        for (x in 0 until size) {
            for (y in 0 until size) {
                setPixel(x, y, if (matrix[x, y]) Color.BLACK else Color.WHITE)
            }
        }
    }
}

internal fun MainActivity.showContactDetail(contact: Contact) {
    val raw = AppStore.contactById(this, contact.id)
    val id = jsonGalaxySSIId(raw, contact.id)
    val isCloudModel = raw?.optString("delivery_mode") == "cloud_api" ||
        raw?.optString("agent_kind") == "cloud-model"
    val hidesTechnicalIdentity = isCloudModel ||
        raw?.optString("delivery_mode") == "pc_connector" ||
        raw?.optString("agent_kind").orEmpty().isNotBlank()
    showFeaturePage(getString(R.string.contact_detail_title))
    featureContent.addView(LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        setPadding(dp(20), dp(22), dp(20), dp(22))
        background = getDrawable(R.drawable.glass_card_background)
        addView(ImageView(this@showContactDetail).apply {
            bindContactAvatar(this, contact)
            scaleType = ImageView.ScaleType.CENTER_CROP
            setBackgroundResource(R.drawable.rounded_avatar_bg)
            clipToOutline = true
        }, LinearLayout.LayoutParams(dp(72), dp(72)).apply { bottomMargin = dp(12) })
        addView(TextView(this@showContactDetail).apply {
            text = contact.name
            gravity = Gravity.CENTER
            setTextColor(getColorCompat(R.color.text_primary))
            textSize = 22f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        })
        if (!hidesTechnicalIdentity) {
            addView(TextView(this@showContactDetail).apply {
                text = id
                gravity = Gravity.CENTER
                setTextColor(getColorCompat(R.color.text_secondary))
                textSize = 12f
                setPadding(0, dp(5), 0, 0)
            })
        }
    }, LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT,
        LinearLayout.LayoutParams.WRAP_CONTENT
    ).apply { bottomMargin = dp(14) })
    addSectionTitle(getString(R.string.contact_section_identity))
    featureContent.addView(featureRow(getString(R.string.contact_remark_name), contact.name, R.drawable.ic_protocol_link, getString(R.string.common_edit)).apply {
        setOnClickListener { showEditContactNamePage(contact) }
    })
    if (!isCloudModel) {
        featureContent.addView(featureRow(getString(R.string.settings_galaxyssi_id), id, R.drawable.ic_protocol_link, getString(R.string.common_copy)))
    }
    if (raw?.optString("type") == "device") {
        addSectionTitle(getString(R.string.contact_device_details))
        raw.optString("device_model").takeIf { it.isNotBlank() }?.let { model ->
            featureContent.addView(featureRow(getString(R.string.contact_device_model), model, R.drawable.ic_device_node, ""))
        }
        val platform = listOf(raw.optString("platform"), raw.optString("platform_version"))
            .filter(String::isNotBlank)
            .joinToString(" ")
        if (platform.isNotBlank()) {
            featureContent.addView(featureRow(getString(R.string.contact_device_platform), platform, R.drawable.ic_device_node, ""))
        }
        raw.optString("host_name").takeIf { it.isNotBlank() }?.let { host ->
            featureContent.addView(featureRow(getString(R.string.contact_device_host), host, R.drawable.ic_device_node, ""))
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
            openContactMessaging(contact)
        }
    }, LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT,
        dp(46)
    ).apply {
        topMargin = dp(20)
    })
}

internal fun MainActivity.showEditContactNamePage(contact: Contact) {
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
                Toast.makeText(this@showEditContactNamePage, getString(R.string.contact_remark_required), Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            AppStore.renameContact(this@showEditContactNamePage, contact.id, newName)
            refreshDirectoryContacts()
            Toast.makeText(this@showEditContactNamePage, getString(R.string.contact_remark_saved), Toast.LENGTH_SHORT).show()
            showContactDetail(contactById(contact.id))
        }
    }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(46)))
}

internal fun MainActivity.refreshMePage() {
    controlCenterHomeRefreshPolicy.invalidate()
    refreshSettingsControlCenter(force = true)
}

internal fun MainActivity.showEditNicknameDialog() {
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
                Toast.makeText(this@showEditNicknameDialog, getString(R.string.profile_nickname_required), Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            AppStore.updateProfileName(this@showEditNicknameDialog, newName)
            refreshMePage()
            notifyContactsProfileUpdated()
            Toast.makeText(this@showEditNicknameDialog, getString(R.string.profile_nickname_saved_notified), Toast.LENGTH_SHORT).show()
            hideFeaturePage()
        }
    }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(46)))
}

internal fun MainActivity.notifyContactsProfileUpdated() {
    val contacts = AppStore.contacts(this)
    for (i in 0 until contacts.length()) {
        val contact = contacts.optJSONObject(i) ?: continue
        if (contact.optBoolean("deleted", false)) continue
        if (contact.optString("type") != "person") continue
        if (contact.optString("trust_state") != "verified") continue
        if (contact.optString("signal_session") != "ready") continue
        val contactId = contact.optString("id").ifBlank { jsonGalaxySSIId(contact) }
        if (contactId.isNotBlank()) GalaxySSIMqttClient.publishProfileUpdate(contactId)
    }
}

internal fun MainActivity.showFriendRequestsDialog() {
    val requests = AppStore.friendRequests(this)
    val visible = (0 until requests.length())
        .mapNotNull { requests.optJSONObject(it) }
        .filter { request ->
            FriendRequestPresentationPolicy.isVisible(
                request,
                AppStore.canCommunicateWith(this, jsonGalaxySSIId(request))
            )
        }
    AppStore.markFriendRequestsRead(this)
    showFeaturePage(getString(R.string.new_friends))
    showingFriendRequests = true
    setFeatureBackAction {
        hideFeaturePage()
        showConversationHub(ConversationHubTab.CONTACTS)
    }
    if (visible.isEmpty()) {
        featureContent.addView(featureHeroCard(getString(R.string.friend_request_empty_title), getString(R.string.friend_request_empty_subtitle), R.drawable.ic_avatar_group, "#8E8E93", getString(R.string.common_empty)))
        return
    }
    val incoming = visible.filter { it.optString("direction") != "outgoing" }
    val outgoing = visible.filter { it.optString("direction") == "outgoing" }
    fun addRequests(title: String, items: List<JSONObject>, trailing: (JSONObject) -> Int) {
        if (items.isEmpty()) return
        addSectionTitle(title)
        items.forEach { request ->
            featureContent.addView(featureRow(
                request.optString("name", getString(R.string.fallback_contact_name)),
                jsonGalaxySSIId(request),
                R.drawable.ic_avatar_group,
                getString(trailing(request))
            ).apply {
                setOnClickListener { showFriendRequestDetail(request) }
            })
        }
    }
    addRequests(
        getString(R.string.friend_request_received_section),
        incoming,
        { R.string.friend_request_view }
    )
    addRequests(
        getString(R.string.friend_request_sent_section),
        outgoing,
        { request ->
            if (FriendRequestPresentationPolicy.isAdded(
                    request,
                    AppStore.canCommunicateWith(this, jsonGalaxySSIId(request))
                )
            ) {
                R.string.friend_request_added
            } else {
                R.string.friend_request_waiting
            }
        }
    )
}

internal fun MainActivity.showFriendRequestDetail(request: JSONObject) {
    val outgoing = request.optString("direction") == "outgoing"
    val contactId = jsonGalaxySSIId(request)
    val added = FriendRequestPresentationPolicy.isAdded(
        request,
        AppStore.canCommunicateWith(this, contactId)
    )
    val displayName = request.optString("name", getString(R.string.fallback_contact_name))
    showFeaturePage(request.optString("name", "Friend"))
    activeFriendRequestContactId = contactId
    setFeatureBackAction { showFriendRequestsDialog() }
    featureContent.addView(featureHeroCard(
        displayName,
        contactId,
        R.drawable.ic_avatar_group,
        "#14C66A",
        getString(
            when {
                added -> R.string.friend_request_added
                outgoing -> R.string.friend_request_waiting
                else -> R.string.friend_request_incoming
            }
        )
    ))
    addSectionTitle(getString(R.string.contact_section_identity))
    featureContent.addView(featureRow(getString(R.string.settings_galaxyssi_id), contactId, R.drawable.ic_protocol_link, getString(R.string.common_copy)))
    if (outgoing || added) {
        featureContent.addView(featureRow(
            getString(R.string.friend_request_sent_section),
            getString(
                if (added) R.string.friend_request_added else R.string.friend_request_waiting_subtitle
            ),
            R.drawable.ic_protocol_link,
            getString(if (added) R.string.friend_request_added else R.string.friend_request_waiting)
        ))
        return
    }
    featureContent.addView(TextView(this).apply {
        text = getString(R.string.friend_request_approve)
        gravity = Gravity.CENTER
        setTextColor(Color.WHITE)
        textSize = 17f
        background = getDrawable(R.drawable.send_button_background)
        setOnClickListener {
            if (!GalaxySSIMqttClient.publishPhoneContactDecision(contactId, approved = true)) {
                Toast.makeText(
                    this@showFriendRequestDetail,
                    getString(R.string.friend_request_decision_failed),
                    Toast.LENGTH_LONG
                ).show()
                return@setOnClickListener
            }
            if (!AppStore.approveFriendRequest(this@showFriendRequestDetail, request.optString("id"))) {
                Toast.makeText(
                    this@showFriendRequestDetail,
                    getString(R.string.friend_request_decision_failed),
                    Toast.LENGTH_LONG
                ).show()
                return@setOnClickListener
            }
            reloadChatHistoryIfChanged(force = true)
            refreshDirectoryContacts()
            Toast.makeText(
                this@showFriendRequestDetail,
                getString(R.string.friend_request_added_named, displayName),
                Toast.LENGTH_SHORT
            ).show()
            hideFeaturePage()
            showConversationHub(ConversationHubTab.CONTACTS)
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
            if (!GalaxySSIMqttClient.publishPhoneContactDecision(contactId, approved = false)) {
                Toast.makeText(
                    this@showFriendRequestDetail,
                    getString(R.string.friend_request_decision_failed),
                    Toast.LENGTH_LONG
                ).show()
                return@setOnClickListener
            }
            AppStore.rejectFriendRequest(this@showFriendRequestDetail, request.optString("id"))
            Toast.makeText(this@showFriendRequestDetail, getString(R.string.common_rejected), Toast.LENGTH_SHORT).show()
            showFriendRequestsDialog()
        }
    }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(48)).apply {
        topMargin = dp(8)
    })
}

internal fun MainActivity.showCreateGroupDialogV2() {
    showCreateGroupFeaturePage()
}

internal fun MainActivity.showCreateGroupDialog() {
    showCreateGroupDialogV2()
}

internal fun MainActivity.showGroupFeaturePage() {
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

internal fun MainActivity.showCreateGroupFeaturePage() {
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

internal fun MainActivity.showAgentFeaturePage() {
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
        AgentUi("hermes", "Hermes", getString(R.string.agent_private_assistant_subtitle), R.drawable.hermes_logo, if (connected("hermes")) getString(R.string.status_running) else getString(R.string.status_pending_pairing), "#14C66A", connected("hermes")),
        AgentUi("codex", agentName("codex", "Codex"), detail("codex", getString(R.string.agent_codex_subtitle)), R.drawable.logo_codex_product, badge("codex", getString(R.string.common_paired)), color("codex", "#5B6CFF"), connected("codex")),
        AgentUi("claude", agentName("claude", "Claude Code"), detail("claude", getString(R.string.agent_claude_subtitle)), R.drawable.logo_claude_code, badge("claude", getString(R.string.common_paired)), color("claude", "#FF6B5F"), connected("claude")),
        AgentUi("openclaw", agentName("openclaw", "OpenClaw"), detail("openclaw", getString(R.string.agent_openclaw_subtitle)), R.drawable.ic_avatar_custom_agent, badge("openclaw", getString(R.string.common_paired)), color("openclaw", "#2878FF"), connected("openclaw")),
        AgentUi("local-llm", agentName("local-llm", "Local LLM"), detail("local-llm", getString(R.string.agent_local_llm_subtitle)), R.drawable.ic_avatar_custom_agent, badge("local-llm", getString(R.string.common_paired)), color("local-llm", "#00A7A7"), connected("local-llm")),
        AgentUi("custom-agent", agentName("custom-agent", "Custom Agent"), detail("custom-agent", getString(R.string.agent_custom_subtitle)), R.drawable.ic_avatar_custom_agent, badge("custom-agent", getString(R.string.common_paired)), color("custom-agent", "#6C7A89"), connected("custom-agent"))
    )
    val registrations = mobileNativeAgent.agentRegistrySnapshot()
    val agentRows = coreAgents + dynamicConnectorAgents(fixedAgentIds) + listOf(
        AgentUi("news_agent", "News Agent", getString(R.string.agent_news_subtitle), R.drawable.ic_agent_node, getString(R.string.badge_automation), "#F0A500", connected("news_agent")),
        AgentUi("home_hub", "Home Agent", getString(R.string.agent_home_subtitle), R.drawable.ic_device_node, getString(R.string.badge_device), "#6C7A89", connected("home_hub"))
    )
    val agents = agentRows.map { agent ->
        agent.copy(
            identity = findAgentRegistration(registrations, agent.contactId)
                ?.let(AgentIdentityPresenter::present)
        )
    }
    showFeaturePage("AI Agent")
    addSegmentTabs(listOf(getString(R.string.discover_segment_all), getString(R.string.discover_segment_local), getString(R.string.discover_segment_official), getString(R.string.discover_segment_running)))
    featureContent.addView(featureRow(getString(R.string.discover_add_cloud_model), getString(R.string.discover_add_cloud_model_subtitle), R.drawable.ic_avatar_cloud_model, "+").apply {
        setOnClickListener { showCloudProviderPage() }
    })
    agents.forEach { agent ->
        featureContent.addView(agentFeatureRow(agent))
    }
}
