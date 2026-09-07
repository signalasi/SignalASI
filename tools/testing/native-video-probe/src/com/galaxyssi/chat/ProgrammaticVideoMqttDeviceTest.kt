package com.galaxyssi.chat

import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.VideoView
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.security.MessageDigest
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ProgrammaticVideoMqttDeviceTest {
    @Test fun realPhoneRequestReturnsPlayableVideoOverMqtt() {
        assertEquals("SM-T575", Build.MODEL)
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val reportFile = File(context.getExternalFilesDir(null), "programmatic-video-mqtt.json")
        val activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)) as MainActivity
        val deadline = SystemClock.elapsedRealtime() + 1_200_000
        var target: AgentCallableTarget? = null
        val readyDeadline = SystemClock.elapsedRealtime() + 60_000
        while (target == null && SystemClock.elapsedRealtime() < readyDeadline) {
            target = AppStoreAgentConnectorRegistry(context).availableTargets().firstOrNull {
                it.status == AgentConnectorStatus.AVAILABLE && it.kind == AgentConnectorKind.AGENT && it.id.endsWith(":codex")
            }
            if (target == null) SystemClock.sleep(500)
        }
        val selected = checkNotNull(target) { "No paired, available Codex Desktop target" }
        val store = AgentTranscriptStore(context)
        val turnId = "video-mqtt-${System.currentTimeMillis()}"
        val goal = "\u8bf7\u751f\u6210\u4e00\u4e2a8\u79d2\u7684\u4e8c\u8fdb\u5236\u8ba1\u6570\u79d1\u666e\u52a8\u753b\u89c6\u9891\u3002\u4ece00\u523011\u5faa\u73af\u9012\u589e\uff0c\u4e24\u4e2a\u65b9\u5757\u660e\u6697\u53d8\u5316\u5bf9\u5e94\u4e24\u4e2a\u4e8c\u8fdb\u5236\u4f4d\uff0c\u7528\u5927\u53f7\u4e2d\u6587\u6807\u9898\u201c\u4e8c\u8fdb\u5236\u8ba1\u6570\u201d\u3002\u65e0\u914d\u97f3\u3001\u65e0\u80cc\u666f\u97f3\u4e50\uff0c\u8fd4\u56deMP4\u3002"
        lateinit var conversation: AgentConversation
        instrumentation.runOnMainSync {
            conversation = store.createConversation("\u771f\u5b9e MQTT \u89c6\u9891\u9a8c\u6536", privateMode = true)
            check(store.append(role = AgentTranscriptRole.USER, text = goal,
                dedupeKey = "$turnId:user", conversationId = conversation.id, turnId = turnId, taskId = turnId))
            activity.continueAgentGoalSubmission(goal = goal, conversationId = conversation.id, turnId = turnId,
                forcedAction = AgentAction(id = turnId, kind = AgentActionKind.CALL_CONNECTOR,
                    target = selected.title, risk = AgentRisk.LOW, status = AgentActionStatus.PENDING_CONFIRMATION,
                    description = "Live video MQTT acceptance", parameters = mapOf("connector_id" to selected.id,
                        "prompt" to goal), requiresConfirmation = false),
                originalGoal = goal, executionModeOverride = AgentTaskExecutionMode.AUTO_COMPLETE)
        }
        val report = JSONObject().put("turn_id", turnId).put("conversation_id", conversation.id)
            .put("target_id", selected.id).put("status", "waiting")
        reportFile.writeText(report.toString(2))
        var resolved: AgentRichBlock? = null
        while (resolved == null && SystemClock.elapsedRealtime() < deadline) {
            val entries = store.list(conversation.id).filter { it.turnId == turnId }
            resolved = entries.flatMap { AgentRichContentCodec.decode(it.richOutputJson) }
                .filter { it.type == AgentRichBlockType.VIDEO }
                .map { AgentDesktopArtifactStore.resolveBlock(context, it) }
                .firstOrNull { it.uri.startsWith("content:") }
            report.put("latest_text", entries.lastOrNull()?.text.orEmpty()).put("entries", entries.size)
            reportFile.writeText(report.toString(2))
            if (resolved == null) SystemClock.sleep(1500)
        }
        val block = checkNotNull(resolved) { "No received video; inspect programmatic-video-mqtt.json" }
        val bytes = context.contentResolver.openInputStream(Uri.parse(block.uri))!!.use { it.readBytes() }
        val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        assertEquals(block.metadata["sha256"], hash)
        var video: VideoView? = null
        instrumentation.runOnMainSync {
            val view = AgentRichContentView(activity, {}, {}, { _, _ -> }).create(AgentTranscriptEntry(
                id = turnId, role = AgentTranscriptRole.ASSISTANT, text = "", timestampMillis = System.currentTimeMillis(),
                richOutputJson = AgentRichContentCodec.encode(listOf(block))))
            view.setBackgroundColor(android.graphics.Color.WHITE)
            activity.findViewById<ViewGroup>(android.R.id.content).addView(view)
            video = findVideo(view)
        }
        val player = checkNotNull(video)
        val playDeadline = SystemClock.elapsedRealtime() + 15_000
        var ready = false
        while (!ready && SystemClock.elapsedRealtime() < playDeadline) {
            instrumentation.runOnMainSync { ready = player.duration > 0 }
            SystemClock.sleep(100)
        }
        assertTrue("Video not prepared", ready)
        instrumentation.runOnMainSync { player.performClick() }
        SystemClock.sleep(1500)
        instrumentation.runOnMainSync {
            assertTrue(player.isPlaying && player.currentPosition > 300)
            player.seekTo(4000)
        }
        SystemClock.sleep(1000)
        instrumentation.runOnMainSync { assertTrue(player.currentPosition >= 3500) }
        File(context.getExternalFilesDir(null), "programmatic-video-mqtt.png").outputStream().use {
            instrumentation.uiAutomation.takeScreenshot().compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        report.put("status", "passed").put("sha256", hash).put("size_bytes", bytes.size)
            .put("artifact_uri", block.metadata["artifact_source_uri"]).put("duration_ms", player.duration)
        reportFile.writeText(report.toString(2))
        instrumentation.runOnMainSync { player.stopPlayback(); activity.finish() }
    }

    private fun findVideo(view: View): VideoView? {
        if (view is VideoView) return view
        if (view is ViewGroup) for (i in 0 until view.childCount) findVideo(view.getChildAt(i))?.let { return it }
        return null
    }
}
