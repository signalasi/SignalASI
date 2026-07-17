package com.signalasi.chat

import android.content.Intent
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.io.FileOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AgentRichContentRenderingTest {
    @Test
    fun rendersACompleteRichDocumentWithoutBlankOrCollapsedBlocks() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val activity = instrumentation.startActivitySync(
            Intent(instrumentation.targetContext, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        ) as MainActivity
        val blocks = showcaseBlocks()
        lateinit var document: LinearLayout
        lateinit var scroll: ScrollView
        instrumentation.runOnMainSync {
            document = AgentRichContentView(activity, {}, {}, { _, _ -> }).create(
                AgentTranscriptEntry(
                    id = "rich-showcase",
                    role = AgentTranscriptRole.ASSISTANT,
                    text = "Rich output showcase",
                    timestampMillis = System.currentTimeMillis(),
                    richOutputJson = AgentRichContentCodec.encode(blocks)
                )
            ) as LinearLayout
            scroll = ScrollView(activity).apply {
                setPadding(24, 24, 24, 24)
                addView(document, ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ))
            }
            activity.setContentView(scroll)
        }
        instrumentation.waitForIdleSync()
        SystemClock.sleep(700)

        instrumentation.runOnMainSync {
            assertEquals(blocks.size, document.childCount)
            for (index in 0 until document.childCount) {
                val child = document.getChildAt(index)
                assertEquals(View.VISIBLE, child.visibility)
                assertTrue("Block $index should have width", child.width > 0)
                assertTrue("Block $index should have height", child.height > 0)
            }
        }
        val screenshot = instrumentation.uiAutomation.takeScreenshot()
        val output = File(instrumentation.targetContext.filesDir, "rich-output-showcase.png")
        FileOutputStream(output).use { screenshot.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        instrumentation.runOnMainSync {
            scroll.scrollTo(0, ((document.height - scroll.height) / 2).coerceAtLeast(0))
        }
        instrumentation.waitForIdleSync()
        SystemClock.sleep(350)
        val middleScreenshot = instrumentation.uiAutomation.takeScreenshot()
        val middleOutput = File(instrumentation.targetContext.filesDir, "rich-output-showcase-middle.png")
        FileOutputStream(middleOutput).use {
            middleScreenshot.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }
        instrumentation.runOnMainSync { scroll.fullScroll(View.FOCUS_DOWN) }
        instrumentation.waitForIdleSync()
        SystemClock.sleep(350)
        val bottomScreenshot = instrumentation.uiAutomation.takeScreenshot()
        val bottomOutput = File(instrumentation.targetContext.filesDir, "rich-output-showcase-bottom.png")
        FileOutputStream(bottomOutput).use {
            bottomScreenshot.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }
        instrumentation.runOnMainSync { activity.finish() }
    }

    private fun showcaseBlocks(): List<AgentRichBlock> = listOf(
        AgentRichBlock("heading", AgentRichBlockType.HEADING, text = "Project status", metadata = mapOf("level" to "1")),
        AgentRichBlock("text", AgentRichBlockType.TEXT, text = "A concise answer with **bold**, *emphasis*, `inline code`, and a [source](https://example.com)."),
        AgentRichBlock("quote", AgentRichBlockType.QUOTE, text = "Keep every action observable and reversible."),
        AgentRichBlock("list", AgentRichBlockType.LIST, rows = listOf(
            listOf("checked", "Inspect the current state"),
            listOf("unchecked", "Verify the final result")
        ), metadata = mapOf("style" to "checklist")),
        AgentRichBlock("divider", AgentRichBlockType.DIVIDER),
        AgentRichBlock("code", AgentRichBlockType.CODE, text = "fun ready() = true", language = "kotlin"),
        AgentRichBlock("json", AgentRichBlockType.JSON, text = "{\n  \"ready\": true,\n  \"count\": 3\n}", language = "json"),
        AgentRichBlock("data", AgentRichBlockType.KEY_VALUE, title = "Runtime", rows = listOf(
            listOf("Status", "Ready"), listOf("Latency", "128 ms")
        )),
        AgentRichBlock("table", AgentRichBlockType.TABLE, title = "Checks", columns = listOf("Check", "State"), rows = listOf(
            listOf("Build", "Passed"), listOf("Policy", "Passed")
        )),
        AgentRichBlock("chart", AgentRichBlockType.CHART, title = "Latency", columns = listOf("Run", "Milliseconds"), rows = listOf(
            listOf("1", "180"), listOf("2", "142"), listOf("3", "128")
        )),
        AgentRichBlock("image", AgentRichBlockType.IMAGE, title = "Generated image", uri = "android.resource://com.signalasi.chat/drawable/signalasi_mark", text = "Tap to inspect the full-resolution result."),
        AgentRichBlock("gallery", AgentRichBlockType.GALLERY, title = "Image set", rows = listOf(
            listOf("android.resource://com.signalasi.chat/drawable/signalasi_mark", "SignalASI", "image/png"),
            listOf("android.resource://com.signalasi.chat/drawable/logo_codex_product", "Codex", "image/png")
        )),
        AgentRichBlock("file", AgentRichBlockType.FILE, title = "report.xlsx", uri = "content://files/report.xlsx", mimeType = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", metadata = mapOf("size" to "24 KB")),
        AgentRichBlock("notice", AgentRichBlockType.NOTICE, title = "Ready", text = "The generated artifact is available.", metadata = mapOf("style" to "success")),
        AgentRichBlock("timeline", AgentRichBlockType.TIMELINE, title = "Execution", rows = listOf(
            listOf("10:12", "Read input", "Completed"), listOf("10:13", "Create result", "Completed")
        )),
        AgentRichBlock("progress", AgentRichBlockType.PROGRESS, title = "Indexing", value = 68, maximum = 100),
        AgentRichBlock("metric", AgentRichBlockType.METRIC, title = "Documents", text = "1,248"),
        AgentRichBlock("actions", AgentRichBlockType.ACTIONS, title = "Next step", actions = listOf(
            AgentRichAction("open", "Open", "open")
        )),
        AgentRichBlock("form", AgentRichBlockType.FORM, title = "Run options", fields = listOf(
            AgentRichField("name", "Name", required = true),
            AgentRichField("mode", "Mode", inputType = "select", value = "Safe", options = listOf("Safe", "Fast"))
        )),
        AgentRichBlock("html", AgentRichBlockType.HTML, title = "Interactive result", text = "<div style='padding:24px'>Ready</div>", fallbackText = "Interactive preview")
    )
}
