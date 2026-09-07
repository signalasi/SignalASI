package com.galaxyssi.chat

import android.content.Intent
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.VideoView
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.security.MessageDigest
import java.util.UUID

class NativeVideoPlaybackDeviceTest {
    @Test fun fragmented240pVideoPlaysInTheExistingAgentOutputView() {
        assertEquals("This probe is authorized only on SM-T575", "SM-T575", Build.MODEL)
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val payloads = JSONArray(instrumentation.context.assets.open("native-video-chunks.json").bufferedReader().use { it.readText() })
        assertTrue(payloads.length() >= 1)
        val taskId = "video-device-probe-" + UUID.randomUUID().toString()
        val artifactUri = "galaxyssi-artifact://$taskId/outputs/synthetic-240p.mp4"
        val artifactId = MessageDigest.getInstance("SHA-256").digest(taskId.toByteArray()).joinToString("") { "%02x".format(it) }
        for (index in 0 until payloads.length()) {
            payloads.getJSONObject(index).put("task_id", taskId).put("artifact_uri", artifactUri).put("artifact_id", artifactId)
        }
        val first = payloads.getJSONObject(0)
        val invalid = JSONObject(first.toString()).put("chunk_sha256", "0".repeat(64))
        assertTrue(runCatching { AgentDesktopArtifactStore.ingest(context, invalid) }.isFailure)
        for (index in payloads.length() - 1 downTo 0) {
            val result = AgentDesktopArtifactStore.ingest(context, payloads.getJSONObject(index))
            assertEquals(index == 0, result.completed)
        }
        assertTrue(AgentDesktopArtifactStore.ingest(context, first).completed)
        val block = AgentDesktopArtifactStore.resolveBlock(context, AgentRichBlock(
            id = "native-video-probe", type = AgentRichBlockType.VIDEO,
            title = InstrumentationRegistry.getArguments().getString("video_title")
                ?: "240p playback test (synthetic fixture, not AI generated)",
            uri = first.getString("artifact_uri"), mimeType = "video/mp4"
        ))
        assertTrue(block.uri.startsWith("content:"))
        val bytes = context.contentResolver.openInputStream(Uri.parse(block.uri))!!.use { it.readBytes() }
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        assertEquals(first.getString("sha256"), digest)
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(context, Uri.parse(block.uri))
            val width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)!!.toInt()
            val height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)!!.toInt()
            assertTrue(minOf(width, height) <= 240)
            assertNotNull(retriever.getFrameAtTime(1_000_000))
        } finally { retriever.release() }
        val activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)) as MainActivity
        lateinit var video: VideoView
        var videoReady = false
        try {
            instrumentation.runOnMainSync {
                val view = AgentRichContentView(activity, {}, {}, { _, _ -> }).create(AgentTranscriptEntry(
                    id = "native-video-probe", role = AgentTranscriptRole.ASSISTANT,
                    text = "240p video transport and playback test", timestampMillis = System.currentTimeMillis(),
                    richOutputJson = AgentRichContentCodec.encode(listOf(block))
                ))
                // Keep real navigation views attached while startup prewarming finishes.
                view.setBackgroundColor(android.graphics.Color.WHITE)
                activity.findViewById<ViewGroup>(android.R.id.content).addView(view,
                    ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
                video = findVideo(view) ?: error("Existing Agent rich output did not create VideoView")
                videoReady = true
            }
            val deadline = SystemClock.elapsedRealtime() + 15_000
            var prepared = false
            while (!prepared && SystemClock.elapsedRealtime() < deadline) {
                instrumentation.runOnMainSync { prepared = video.duration > 0 }
                SystemClock.sleep(100)
            }
            assertTrue("Video preparation timed out", prepared)
            instrumentation.runOnMainSync { assertTrue(video.performClick()) }
            SystemClock.sleep(1400)
            instrumentation.runOnMainSync {
                assertTrue("Playback did not advance", video.currentPosition > 250)
                assertTrue(video.isPlaying)
                video.pause()
                video.seekTo(5000)
                video.start()
            }
            SystemClock.sleep(1200)
            instrumentation.runOnMainSync { assertTrue("Seek did not advance", video.currentPosition >= 4000) }
            val screenshot = instrumentation.uiAutomation.takeScreenshot()
            File(context.getExternalFilesDir(null), "native-video-probe.png").outputStream().use {
                screenshot.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
        } finally {
            instrumentation.runOnMainSync {
                if (videoReady) video.stopPlayback()
                activity.finish()
            }
        }
    }

    private fun findVideo(view: View): VideoView? {
        if (view is VideoView) return view
        if (view is ViewGroup) for (index in 0 until view.childCount) {
            findVideo(view.getChildAt(index))?.let { return it }
        }
        return null
    }
}
