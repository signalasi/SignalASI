package com.signalasi.chat.ui

import android.app.Activity
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.widget.TextView
import android.widget.Toast
import com.signalasi.chat.R
import java.util.Locale

class AppleHoldToTalkController(
    private val activity: Activity,
    private val pressButton: TextView,
    private val idleContent: View? = null,
    private val recordingGroup: View,
    private val waveform: VoiceWaveformView,
    private val timer: TextView,
    private val hasPermission: () -> Boolean,
    private val requestPermission: () -> Unit,
    private val startRecording: () -> Boolean,
    private val currentAmplitude: () -> Int,
    private val finishRecording: (send: Boolean) -> Unit
) : View.OnTouchListener {
    private val handler = Handler(Looper.getMainLooper())
    private val cancelThreshold = 56f * activity.resources.displayMetrics.density
    private var recording = false
    private var cancelPending = false
    private var downY = 0f
    private var startedAt = 0L

    private val meter = object : Runnable {
        override fun run() {
            if (!recording) return
            val elapsed = SystemClock.elapsedRealtime() - startedAt
            timer.text = String.format(Locale.US, "%02d:%02d", elapsed / 60_000, elapsed / 1000 % 60)
            waveform.pushAmplitude(runCatching(currentAmplitude).getOrDefault(0))
            if (elapsed >= 120_000L) complete(sendRequested = true) else handler.postDelayed(this, 50L)
        }
    }

    override fun onTouch(view: View, event: MotionEvent): Boolean = when (event.actionMasked) {
        MotionEvent.ACTION_DOWN -> {
            if (!hasPermission()) {
                requestPermission()
            } else if (startRecording()) {
                recording = true
                cancelPending = false
                downY = event.rawY
                startedAt = SystemClock.elapsedRealtime()
                view.parent?.requestDisallowInterceptTouchEvent(true)
                view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                showRecordingUi()
                handler.post(meter)
            }
            true
        }
        MotionEvent.ACTION_MOVE -> {
            if (recording) updateCancelState(downY - event.rawY >= cancelThreshold)
            true
        }
        MotionEvent.ACTION_UP -> {
            complete(sendRequested = true)
            true
        }
        MotionEvent.ACTION_CANCEL -> {
            complete(sendRequested = false)
            true
        }
        else -> true
    }

    fun release() {
        if (recording) complete(sendRequested = false)
        handler.removeCallbacks(meter)
    }

    private fun showRecordingUi() {
        pressButton.text = activity.getString(R.string.voice_release_to_send)
        idleContent?.apply {
            alpha = 0f
            isEnabled = false
        }
        recordingGroup.visibility = View.VISIBLE
        waveform.reset()
        timer.text = "00:00"
    }

    private fun updateCancelState(cancel: Boolean) {
        if (cancelPending == cancel) return
        cancelPending = cancel
        pressButton.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
        pressButton.text = activity.getString(if (cancel) R.string.voice_release_to_cancel else R.string.voice_release_to_send)
        waveform.setCancelPending(cancel)
        timer.setTextColor(activity.getColor(if (cancel) R.color.apple_voice_cancel else R.color.text_secondary))
    }

    private fun complete(sendRequested: Boolean) {
        if (!recording) return
        handler.removeCallbacks(meter)
        val elapsed = SystemClock.elapsedRealtime() - startedAt
        val tooShort = elapsed < 800L
        val send = sendRequested && !cancelPending && !tooShort
        recording = false
        pressButton.parent?.requestDisallowInterceptTouchEvent(false)
        recordingGroup.visibility = View.GONE
        idleContent?.apply {
            alpha = 1f
            isEnabled = true
        }
        waveform.reset()
        timer.setTextColor(activity.getColor(R.color.text_secondary))
        pressButton.text = activity.getString(R.string.input_press_to_talk)
        finishRecording(send)
        if (tooShort && sendRequested && !cancelPending) {
            Toast.makeText(activity, R.string.voice_too_short, Toast.LENGTH_SHORT).show()
        } else if (cancelPending) {
            Toast.makeText(activity, R.string.voice_cancelled, Toast.LENGTH_SHORT).show()
        }
        cancelPending = false
    }
}
