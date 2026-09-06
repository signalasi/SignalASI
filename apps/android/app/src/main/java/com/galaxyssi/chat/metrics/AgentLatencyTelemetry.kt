package com.galaxyssi.chat.metrics

import android.content.Context
import android.graphics.Rect
import android.os.SystemClock
import android.view.View
import android.view.ViewTreeObserver
import java.io.File
import org.json.JSONObject

internal object AgentLatencyTelemetry {
    private val turnStarts = AgentLatencyTurnStarts()
    private val replyBindings = AgentReplyTraceBindings()
    @Volatile private var journal: AgentTimingJournal? = null
    @Volatile private var tracer: AgentLatencyTracer? = null
    val transport = AgentTransportTiming(
        emit = { trace, stage, operation, outcome, at ->
            tracer?.recordOpaque(trace, stage, operation, outcome, at)
        },
        nowNs = SystemClock::elapsedRealtimeNanos
    )

    fun transportQueued(context: Context, endpoint: String, messageId: String, taskId: String) {
        if (taskId.isBlank()) return
        runCatching { get(context); transport.queued(endpoint, messageId, taskId) }
    }

    private fun get(context: Context): AgentLatencyTracer = tracer ?: synchronized(this) {
        tracer ?: AgentTimingJournal(File(context.applicationContext.noBackupFilesDir, "diagnostics/agent_latency_v1.jsonl"))
            .let { store ->
                journal = store
                AgentLatencyTracer(store, SystemClock::elapsedRealtimeNanos).also { tracer = it }
            }
    }

    fun record(context: Context, taskId: String, stage: String, outcome: String = "", atNs: Long? = null) {
        runCatching { get(context).record(taskId, stage, outcome, atNs) }
    }

    fun bindReply(conversationId: String, turnId: String, entryTaskId: String, transportTaskId: String) {
        replyBindings.bind(conversationId, turnId, entryTaskId, transportTaskId)
    }

    fun replyTaskId(conversationId: String, turnId: String, entryTaskId: String): String =
        replyBindings.resolve(conversationId, turnId, entryTaskId)

    fun replyStage(context: Context, taskId: String, stage: String) {
        val current = tracer ?: return
        if (current.hasFinalResponse(taskId)) record(context, taskId, stage, current.finalOutcome(taskId))
    }

    fun beginTurn(turnId: String) {
        turnStarts.begin(turnId, SystemClock.elapsedRealtimeNanos())
    }

    fun publishStarted(context: Context, taskId: String, turnId: String, atNs: Long) {
        turnStarts.take(turnId)?.let { record(context, taskId, "phone_send_started", atNs = it) }
        record(context, taskId, "phone_publish_started", atNs = atNs)
    }

    fun received(context: Context, payload: JSONObject) {
        if (payload.optBoolean("peer_chat") || payload.optString("task_id").isBlank()) return
        val partial = payload.optJSONObject("partial_result")
        val final = payload.optString("type") == "text" && payload.optString("source_message_id").isNotBlank()
        val visiblePartial = payload.optString("type") == "agent_task_event" &&
            partial?.optBoolean("user_visible", true) == true && partial.optString("text").isNotBlank()
        if (final || visiblePartial) record(context, payload.optString("task_id"), "phone_response_received")
        if (final) record(context, payload.optString("task_id"), "phone_final_received", payload.optString("task_status"))
    }

    fun summary(context: Context): Pair<Map<String, AgentLatencyMetric>, Map<String, Any>> {
        get(context)
        return AgentLatencyContract.summarize(journal?.snapshot().orEmpty()) to journal?.health().orEmpty()
    }

    fun observeDraw(view: View, taskId: String, final: Boolean): (() -> Unit)? {
        val current = tracer ?: return null
        if (!current.shouldObserve(taskId, final)) return null
        val observer = view.viewTreeObserver
        var fired = false
        var cancelled = false
        lateinit var listener: ViewTreeObserver.OnDrawListener
        val remove = { if (observer.isAlive) observer.removeOnDrawListener(listener) }
        listener = ViewTreeObserver.OnDrawListener {
            if (!fired && view.isShown && view.hasWindowFocus() && view.getGlobalVisibleRect(Rect())) {
                fired = true
                // Leave the draw traversal; detached or recycled rows are not visible samples.
                view.post {
                    remove()
                    if (!cancelled && view.isAttachedToWindow && view.isShown && view.hasWindowFocus()) {
                        runCatching { current.visible(taskId, final) }
                    }
                }
            }
        }
        observer.addOnDrawListener(listener)
        view.invalidate()
        return { cancelled = true; remove() }
    }
}
