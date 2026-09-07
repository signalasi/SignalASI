package com.galaxyssi.chat

import android.content.Intent
import android.os.Build
import android.os.SystemClock
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.inspector.WindowInspector
import android.widget.VideoView
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class VideoFullscreenDeviceTest {
    @Test fun receivedVideoFillsWidthAndDoubleTapPreservesPlayback() {
        assertEquals("SM-T575", Build.MODEL)
        check(Build.VERSION.SDK_INT >= 29)
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val report = JSONObject(File(context.getExternalFilesDir(null), "programmatic-video-mqtt.json").readText())
        assertEquals("passed", report.getString("status"))
        val block = AgentTranscriptStore(context).list(report.getString("conversation_id"))
            .flatMap { AgentRichContentCodec.decode(it.richOutputJson) }
            .filter { it.type == AgentRichBlockType.VIDEO }
            .map { AgentDesktopArtifactStore.resolveBlock(context, it) }
            .first { it.uri.startsWith("content:") }
        val activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)) as MainActivity
        lateinit var inline: VideoView
        var overlay: View? = null
        fun onMain(action: () -> Unit) = instrumentation.runOnMainSync(action)
        fun awaitState(message: String, predicate: () -> Boolean) {
            val deadline = SystemClock.elapsedRealtime() + 15_000
            var success = false
            while (!success && SystemClock.elapsedRealtime() < deadline) {
                onMain { success = predicate() }
                if (!success) SystemClock.sleep(100)
            }
            assertTrue(message, success)
        }
        fun fullscreen(): VideoView? = WindowInspector.getGlobalWindowViews()
            .filter { it !== activity.window.decorView }.flatMap { videos(it) }.firstOrNull()
        fun doubleTap(video: VideoView) = onMain {
            val time = SystemClock.uptimeMillis()
            listOf(0L to MotionEvent.ACTION_DOWN, 30L to MotionEvent.ACTION_UP,
                100L to MotionEvent.ACTION_DOWN, 130L to MotionEvent.ACTION_UP).forEach { (offset, action) ->
                val event = MotionEvent.obtain(time + if (offset >= 100) 100 else 0, time + offset,
                    action, video.width / 2f, video.height / 2f, 0)
                video.dispatchTouchEvent(event)
                event.recycle()
            }
        }
        try {
            onMain {
                val content = AgentRichContentView(activity, {}, {}, { _, _ -> }).create(AgentTranscriptEntry(
                    id = "video-fullscreen-acceptance", role = AgentTranscriptRole.ASSISTANT, text = "",
                    timestampMillis = System.currentTimeMillis(),
                    richOutputJson = AgentRichContentCodec.encode(listOf(block))))
                overlay = content
                activity.findViewById<ViewGroup>(android.R.id.content).addView(content)
                inline = videos(content).single()
            }
            awaitState("Inline player prepared") { inline.duration > 0 && inline.width > 0 }
            onMain {
                val parent = inline.parent as ViewGroup
                assertEquals(parent.width - parent.paddingLeft - parent.paddingRight, inline.width)
                assertEquals(inline.width * 240.0 / 426, inline.height.toDouble(), 1.0)
                inline.seekTo(4000)
            }
            awaitState("Inline seek completed") { inline.currentPosition >= 3000 }
            doubleTap(inline)
            awaitState("Double tap opened fullscreen") { fullscreen()?.duration?.let { it > 0 } == true }
            awaitState("Fullscreen retained paused position") {
                fullscreen()?.let { !it.isPlaying && it.currentPosition >= 3000 } == true
            }
            lateinit var full: VideoView
            onMain {
                full = fullscreen()!!
                assertTrue(full.rootView.height > inline.height)
                assertTrue(full.width >= inline.width)
                full.performClick()
            }
            awaitState("Fullscreen playback advanced") { full.isPlaying && full.currentPosition > 4200 }
            doubleTap(full)
            awaitState("Double tap closed fullscreen and resumed inline") {
                fullscreen() == null && inline.isPlaying && inline.currentPosition > 3500
            }
            onMain { inline.pause(); inline.seekTo(4000) }
            doubleTap(inline)
            awaitState("Fullscreen reopened") { fullscreen()?.duration?.let { it > 0 } == true }
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
            awaitState("Back closed fullscreen without resuming paused playback") {
                fullscreen() == null && !inline.isPlaying && inline.currentPosition >= 3000
            }
        } finally {
            onMain {
                overlay?.let { (it.parent as? ViewGroup)?.removeView(it) }
                activity.finish()
            }
        }
    }

    private fun videos(view: View): List<VideoView> = when (view) {
        is VideoView -> listOf(view)
        is ViewGroup -> (0 until view.childCount).flatMap { videos(view.getChildAt(it)) }
        else -> emptyList()
    }
}
