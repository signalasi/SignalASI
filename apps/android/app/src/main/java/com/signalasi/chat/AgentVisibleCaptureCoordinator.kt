package com.signalasi.chat

import android.app.Activity
import java.lang.ref.WeakReference
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

enum class AgentVisibleCaptureKind(val wireValue: String) {
    PHOTO("photo"),
    AUDIO("audio")
}

enum class AgentVisibleCaptureStatus {
    SUCCEEDED,
    CANCELLED,
    FAILED
}

data class AgentVisibleCaptureArtifact(
    val kind: AgentVisibleCaptureKind,
    val contentUri: String,
    val mimeType: String,
    val sizeBytes: Long,
    val widthPixels: Int = 0,
    val heightPixels: Int = 0,
    val durationMillis: Long = 0L,
    val capturedAtEpochMillis: Long,
    val completedBy: String
)

data class AgentVisibleCaptureOutcome(
    val status: AgentVisibleCaptureStatus,
    val artifact: AgentVisibleCaptureArtifact? = null,
    val code: String = "",
    val message: String = ""
)

object AgentVisibleCaptureContract {
    const val EXTRA_REQUEST_ID = "signalasi.extra.VISIBLE_CAPTURE_REQUEST_ID"
    const val EXTRA_CAMERA_FACING = "signalasi.extra.CAMERA_FACING"
    const val EXTRA_MAX_DURATION_SECONDS = "signalasi.extra.MAX_DURATION_SECONDS"
}

/**
 * Coordinates one foreground, user-visible media capture with the synchronous native-tool receipt.
 * Raw media never passes through this object; only the final content URI and bounded metadata do.
 */
object AgentVisibleCaptureCoordinator {
    private data class PendingCapture(
        val kind: AgentVisibleCaptureKind,
        val latch: CountDownLatch = CountDownLatch(1),
        val outcome: AtomicReference<AgentVisibleCaptureOutcome?> = AtomicReference(null),
        val activity: AtomicReference<WeakReference<Activity>?> = AtomicReference(null),
        val released: AtomicBoolean = AtomicBoolean(false)
    )

    private val pending = ConcurrentHashMap<String, PendingCapture>()
    private val activeRequestId = AtomicReference<String?>(null)

    fun begin(requestId: String, kind: AgentVisibleCaptureKind): Boolean {
        if (requestId.isBlank() || !activeRequestId.compareAndSet(null, requestId)) return false
        val previous = pending.putIfAbsent(requestId, PendingCapture(kind))
        if (previous != null) {
            activeRequestId.compareAndSet(requestId, null)
            return false
        }
        return true
    }

    fun attach(requestId: String, kind: AgentVisibleCaptureKind, activity: Activity): Boolean {
        val capture = pending[requestId] ?: return false
        if (capture.kind != kind || capture.released.get()) return false
        capture.activity.set(WeakReference(activity))
        return true
    }

    fun complete(requestId: String, outcome: AgentVisibleCaptureOutcome): Boolean {
        val capture = pending[requestId] ?: return false
        if (!capture.outcome.compareAndSet(null, outcome)) return false
        activeRequestId.compareAndSet(requestId, null)
        capture.latch.countDown()
        return true
    }

    fun await(requestId: String, invocation: AgentNativeToolInvocation): AgentVisibleCaptureOutcome {
        val capture = pending[requestId]
            ?: return AgentVisibleCaptureOutcome(
                AgentVisibleCaptureStatus.FAILED,
                code = "capture_request_missing",
                message = "The visible capture request is no longer active"
            )
        while (true) {
            invocation.checkpoint()
            if (capture.latch.await(minOf(invocation.remainingTimeMillis, POLL_MILLIS), TimeUnit.MILLISECONDS)) {
                return capture.outcome.get() ?: AgentVisibleCaptureOutcome(
                    AgentVisibleCaptureStatus.FAILED,
                    code = "capture_result_missing",
                    message = "The visible capture finished without a result"
                )
            }
        }
    }

    fun release(requestId: String) {
        val capture = pending.remove(requestId) ?: return
        if (!capture.released.compareAndSet(false, true)) return
        activeRequestId.compareAndSet(requestId, null)
        capture.activity.get()?.get()?.let { activity ->
            activity.runOnUiThread {
                if (!activity.isFinishing) activity.finish()
            }
        }
        capture.activity.set(null)
        capture.latch.countDown()
    }

    fun isBusy(): Boolean = activeRequestId.get() != null

    internal fun resetForTests() {
        pending.keys.toList().forEach(::release)
        activeRequestId.set(null)
    }

    private const val POLL_MILLIS = 200L
}
