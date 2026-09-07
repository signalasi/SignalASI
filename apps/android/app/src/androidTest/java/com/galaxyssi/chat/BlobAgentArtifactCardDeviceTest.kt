package com.galaxyssi.chat

import android.content.Context
import android.os.Looper
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.galaxyssi.chat.blob.BlobAgentArtifactKey
import com.galaxyssi.chat.blob.BlobArtifactCardUpdates
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** Real Android views with isolated loader fixtures; no chat rows, pairing, or model files are modified. */
@RunWith(AndroidJUnit4::class)
class BlobAgentArtifactCardDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val uri = "galaxyssi-artifact://blob/" + "a".repeat(64)
    private fun metadata(task: String = "ui-test-task") = mapOf("blob_client_route_id" to "route",
        "blob_desktop_id" to "desktop", "blob_conversation_id" to "ui-test-conversation", "blob_task_id" to task,
        "blob_turn_id" to "ui-test-turn", "blob_execution_generation" to "1", "transfer_id" to "b".repeat(64),
        "sha256" to "c".repeat(64), "size_bytes" to "1234", "transport" to "encrypted-blob", "artifact_source_uri" to uri)
    private fun source(task: String = "ui-test-task") = AgentRichBlock(id = "test-file", type = AgentRichBlockType.FILE,
        title = "\u9644\u4ef6\u8fdb\u5ea6\u6d4b\u8bd5.txt", uri = uri, mimeType = "text/plain", metadata = metadata(task))
    private fun key(task: String = "ui-test-task") = requireNotNull(BlobAgentArtifactKey.from(
        "ui-test-conversation", task, "ui-test-turn", uri, metadata(task)))
    private fun event(type: String = "artifact_blob_progress", progress: Int = 57) = JSONObject()
        .put("type", type).put("blob_publication", true).put("peer_chat", false).put("client_route_id", "route")
        .put("desktop_id", "desktop").put("conversation_id", "ui-test-conversation").put("task_id", "ui-test-task")
        .put("turn_id", "ui-test-turn").put("execution_generation", 1).put("transfer_id", "b".repeat(64))
        .put("artifact_uri", uri).put("sha256", "c".repeat(64)).put("size_bytes", 1234).put("progress", progress)
    private fun ready() = BlobAgentArtifactSnapshot(source().copy(uri = "content://fixture/verified"))
    private fun main(block: () -> Unit) = instrumentation.runOnMainSync { block() }
    private fun await(condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 5_000
        while (!condition()) {
            check(SystemClock.elapsedRealtime() < deadline) { "UI fixture did not settle" }
            SystemClock.sleep(10)
        }
    }
    private fun text(view: View): String = when (view) {
        is TextView -> view.text.toString()
        is ViewGroup -> (0 until view.childCount).joinToString(" ") { text(view.getChildAt(it)) }
        else -> ""
    }
    private fun awaitText(view: View, expected: String) = await {
        var actual = ""
        main { actual = text(view) }
        actual.contains(expected)
    }
    private fun awaitFrames(view: View) {
        val drawn = CountDownLatch(1)
        main { view.postOnAnimation { view.postOnAnimation { drawn.countDown() } } }
        assertTrue(drawn.await(5, TimeUnit.SECONDS))
        instrumentation.waitForIdleSync()
    }
    private fun screenshot(view: View, name: String) {
        awaitFrames(view)
        val bitmap = instrumentation.uiAutomation.takeScreenshot()
        try {
            java.io.File(requireNotNull(view.context.externalCacheDir), name).outputStream().use {
                assertTrue(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it))
            }
        } finally { bitmap.recycle() }
    }
    private fun fixture(block: (MainActivity, LinearLayout, TextView) -> Unit) {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            lateinit var activity: MainActivity
            lateinit var root: LinearLayout
            lateinit var original: TextView
            scenario.onActivity {
                activity = it
                root = LinearLayout(it).apply { orientation = LinearLayout.VERTICAL; setPadding(24, 80, 24, 24) }
                original = TextView(it).apply { text = "\u539f\u6709\u56de\u590d\u4fdd\u6301\u4e0d\u53d8"; textSize = 18f }
                root.addView(original)
                it.setContentView(root)
            }
            try { block(activity, root, original) } finally { main { root.removeAllViews() } }
        }
    }
    private fun card(activity: Context, task: String = "ui-test-task", renders: AtomicInteger = AtomicInteger(),
        loader: (Context, AgentRichBlock, BlobAgentArtifactKey) -> BlobAgentArtifactSnapshot): BlobAgentArtifactCardView =
        BlobAgentArtifactCardView(activity, source(task), key(task), { block ->
            renders.incrementAndGet()
            TextView(activity).apply { text = "\u5df2\u63a5\u6536 ${block.title}" }
        }, loader)

    @Test fun progressUpdatesOnlyTheCardWithoutAnotherDiskLoad() = fixture { activity, root, original ->
        val reads = AtomicInteger()
        lateinit var view: BlobAgentArtifactCardView
        main { view = card(activity) { _, _, _ ->
            assertNotEquals(Looper.getMainLooper(), Looper.myLooper())
            reads.incrementAndGet(); BlobAgentArtifactSnapshot()
        }; root.addView(view) }
        await { reads.get() == 1 }
        BlobArtifactCardUpdates.publish(event())
        awaitText(view, "57%")
        screenshot(view, "blob-agent-card-progress-ui-test.png")
        BlobArtifactCardUpdates.publish(event(progress = 100))
        awaitText(view, "99%")
        main { assertSame(original, root.getChildAt(0)); assertEquals(1, reads.get()) }
    }

    @Test fun completionReplacesOneCardOnceAndIgnoresLateProgressFailureAndDuplicateCompletion() = fixture { activity, root, original ->
        val reads = AtomicInteger()
        val renders = AtomicInteger()
        lateinit var view: BlobAgentArtifactCardView
        main { view = card(activity, renders = renders) { _, _, _ ->
            if (reads.incrementAndGet() == 1) BlobAgentArtifactSnapshot() else ready()
        }; root.addView(view) }
        await { reads.get() == 1 }
        BlobArtifactCardUpdates.publish(event("artifact_available"))
        BlobArtifactCardUpdates.publish(event("artifact_available"))
        BlobArtifactCardUpdates.publish(event("artifact_download_failed"))
        await { renders.get() == 1 }
        BlobArtifactCardUpdates.publish(event(progress = 20))
        instrumentation.waitForIdleSync()
        main { assertSame(original, root.getChildAt(0)); assertEquals(1, renders.get()); assertEquals(2, reads.get()) }
    }

    @Test fun slowDiskReadDoesNotBlockMainOrRollBackNewerProgress() = fixture { activity, root, _ ->
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        lateinit var view: BlobAgentArtifactCardView
        try {
            main { view = card(activity) { _, _, _ ->
                entered.countDown(); check(release.await(5, TimeUnit.SECONDS))
                BlobAgentArtifactSnapshot(event = event("artifact_download_failed"))
            }; root.addView(view) }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            BlobArtifactCardUpdates.publish(event(progress = 65))
            awaitText(view, "65%")
            release.countDown()
            instrumentation.waitForIdleSync()
            awaitText(view, "65%")
        } finally { release.countDown() }
    }

    @Test fun detachedCardCannotBeChangedByItsOldReadOrQueuedEvent() = fixture { activity, root, _ ->
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val renders = AtomicInteger()
        lateinit var old: BlobAgentArtifactCardView
        lateinit var replacement: BlobAgentArtifactCardView
        try {
            main { old = card(activity, renders = renders) { _, _, _ ->
                entered.countDown(); check(release.await(5, TimeUnit.SECONDS)); ready()
            }; root.addView(old) }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            main {
                root.removeView(old)
                replacement = card(activity, task = "another-task") { _, _, _ -> BlobAgentArtifactSnapshot() }
                root.addView(replacement)
            }
            BlobArtifactCardUpdates.publish(event("artifact_available"))
            release.countDown()
            instrumentation.waitForIdleSync()
            main { assertEquals(0, renders.get()); assertFalse(text(replacement).contains("57%")) }
        } finally { release.countDown() }
    }

    @Test fun reattachingLoadsCompletedStateWithoutNeedingAnotherTransportEvent() = fixture { activity, root, _ ->
        val stored = AtomicReference(BlobAgentArtifactSnapshot())
        val reads = AtomicInteger()
        val renders = AtomicInteger()
        lateinit var view: BlobAgentArtifactCardView
        main { view = card(activity, renders = renders) { _, _, _ -> reads.incrementAndGet(); stored.get() }; root.addView(view) }
        await { reads.get() == 1 }
        main { root.removeView(view) }
        stored.set(ready())
        main { root.addView(view) }
        await { renders.get() == 1 }
        assertEquals(2, reads.get())
    }

    @Test fun sameTransferTextCannotCrossTaskBoundary() = fixture { activity, root, _ ->
        lateinit var first: BlobAgentArtifactCardView
        lateinit var second: BlobAgentArtifactCardView
        main {
            first = card(activity) { _, _, _ -> BlobAgentArtifactSnapshot() }
            second = card(activity, task = "another-task") { _, _, _ -> BlobAgentArtifactSnapshot() }
            root.addView(first); root.addView(second)
        }
        BlobArtifactCardUpdates.publish(event(progress = 76))
        awaitText(first, "76%")
        main { assertFalse(text(second).contains("76%")) }
    }

    @Test fun storageReadFailureStopsWaitingAndKeepsTheRestOfTheReply() = fixture { activity, root, original ->
        lateinit var view: BlobAgentArtifactCardView
        main { view = card(activity) { _, _, _ -> throw java.io.IOException("fixture failure") }; root.addView(view) }
        awaitText(view, activity.getString(R.string.blob_artifact_failed))
        main { assertSame(original, root.getChildAt(0)) }
    }

    @Test fun richContentFactoryUsesScopedCardsForFilesAndGalleryRows() = fixture { activity, root, _ ->
        val file = source()
        val otherUri = "galaxyssi-artifact://blob/" + "d".repeat(64)
        val gallery = AgentRichBlock(id = "gallery", type = AgentRichBlockType.GALLERY,
            rows = listOf(listOf(uri, "\u6d4b\u8bd5\u56fe\u7247", "image/png"),
                listOf(otherUri, "\u7b2c\u4e8c\u5f20\u56fe\u7247", "image/png")),
            metadata = mapOf("blob_item_" + uri.substringAfterLast('/') to JSONObject(metadata()).toString(),
                "blob_item_" + otherUri.substringAfterLast('/') to JSONObject(metadata()).put("artifact_source_uri", otherUri).toString()))
        main {
            val content = AgentRichContentView(activity, {}, {}, { _, _ -> }).create(AgentTranscriptEntry(
                "ui-fixture", AgentTranscriptRole.ASSISTANT, "", 1L, conversationId = "ui-test-conversation",
                taskId = "ui-test-task", turnId = "ui-test-turn", richOutputJson = AgentRichContentCodec.encode(listOf(file, gallery))))
            fun count(view: View): Int = if (view is BlobAgentArtifactCardView) 1 else if (view is ViewGroup) {
                (0 until view.childCount).sumOf { count(view.getChildAt(it)) }
            } else 0
            assertEquals(3, count(content))
            root.addView(content)
        }
        BlobArtifactCardUpdates.publish(event(progress = 57))
        awaitText(root, "57%")
        screenshot(root, "blob-agent-gallery-progress-ui-test.png")
    }
}
