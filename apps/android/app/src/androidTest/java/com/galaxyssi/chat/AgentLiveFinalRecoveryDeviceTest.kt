package com.galaxyssi.chat

import android.content.Context
import android.os.Build
import android.os.Process
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.security.MessageDigest
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Run each phase in a separate instrumentation process, with adb force-stop between phases. */
@RunWith(AndroidJUnit4::class)
class AgentLiveFinalRecoveryDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val arguments = InstrumentationRegistry.getArguments()
    private val preferences by lazy { context.getSharedPreferences("live_final_recovery_test", Context.MODE_PRIVATE) }
    private val cipher by lazy { AgentRowStorageCipher(context, "live-final-recovery-test") }

    @Test fun submitAndDropOnlyTestFinal(): Unit = runBlocking {
        val id = enabledCase()
        assertFalse("Use a new case ID; setup must never silently resubmit", preferences.contains(id))
        val source = requireNotNull(arguments.getString("live_final_source")?.toLongOrNull())
        require(source > 0)
        connect()
        val contact = withContext(Dispatchers.IO) {
            val contacts = AppStore.contacts(context)
            (0 until contacts.length()).asSequence().mapNotNull { contacts.optJSONObject(it) }
                .firstOrNull { value ->
                    val contactId = value.optString("id")
                    val desktop = value.optString("desktop_id")
                    desktop.isNotBlank() && !AppStore.isDesktopDeviceContact(context, contactId) &&
                        AppStore.agentIdForContact(context, contactId) == "codex" &&
                        GalaxySSILinkProtocol.serverLink(context, desktop)?.paired == true
                } ?: error("No paired Codex contact; do not alter pairing to make this test pass")
        }
        val contactId = contact.getString("id")
        val turn = "$id-turn"
        val task = "$id-task"
        val prompt = "这是最终回复恢复测试。请只回复：最终回复恢复验证完成。不调用工具，不修改文件。"
        val state = withContext(Dispatchers.IO) {
            assertNull(AgentTaskIdentityStore.find(context, contactId, source))
            val store = AgentTranscriptStore(context)
            val previous = store.activeConversation().id
            val conversation = store.createConversation("最终回复恢复测试", privateMode = true)
            assertTrue(store.append(AgentTranscriptRole.USER, prompt, conversationId = conversation.id,
                turnId = turn, taskId = task))
            JSONObject().put("source", source).put("contact", contactId).put("conversation", conversation.id)
                .put("turn", turn).put("task", task).put("previous", previous)
                .put("phase", "created").put("setup_pid", Process.myPid()).also { save(id, it) }
        }
        val final = CompletableDeferred<AgentConnectorResponse>()
        val listener = object : GalaxySSIMqttClient.Listener {
            override fun onMessage(payload: String) {
                val envelope = runCatching { JSONObject(payload) }.getOrNull() ?: return
                if (envelope.optLong("source_message_id") != source ||
                    envelope.optString("contact_id") != contactId ||
                    envelope.optString("conversation_id") != state.getString("conversation") ||
                    envelope.optString("turn_id") != turn || envelope.optString("task_id") != task) return
                if (envelope.optString("type") == "text") {
                    val response = AgentRemoteOutcomeCodec.decode(envelope,
                        AgentRemoteOutcomeCodec.content(context, envelope)) ?: return
                    // Acknowledge transport consumption, but deliberately do not deliver this test body to the inbox/UI.
                    GalaxySSIMqttClient.completeIncomingDelivery(context, payload)
                    final.complete(response)
                } else if (envelope.optString("type") == "agent_task_event") {
                    GalaxySSIMqttClient.completeIncomingDelivery(context, payload)
                }
            }
        }
        GalaxySSIMqttClient.addListener(listener)
        try {
            withContext(Dispatchers.IO) {
                assertTrue("Normal paired transport rejected setup", GalaxySSIMqttClient.publishUserMessage(
                    content = prompt, contactId = contactId, clientMessageId = source,
                    conversationId = state.getString("conversation"), turnId = turn, taskId = task, runId = "$id-run"))
                state.put("phase", "submitted")
                save(id, state)
            }
            val response = withTimeout(120_000L) { final.await() }
            assertTrue("Provider did not complete the probe", response.success)
            assertEquals("completed", response.taskStatus)
            assertTrue("Provider did not return the requested marker", response.content.contains("最终回复恢复验证完成"))
            withContext(Dispatchers.IO) {
                assertFalse("Final was not actually dropped", AgentConnectorResponseStore.containsTurn(context,
                    response.conversationId, response.turnId))
                assertTrue(assistantEntries(state).isEmpty())
                state.put("response", AgentConnectorResponseCodec.encode(response))
                    .put("content_sha256", hash(response.content)).put("phase", "dropped")
                save(id, state)
                AgentPendingDeliveryStore.put(context, AgentPendingDelivery(source, response.conversationId,
                    turn, task, contactId))
            }
            println("LIVE_FINAL phase=dropped case=$id source=$source generation=${response.executionGeneration} pid=${Process.myPid()}")
        } finally {
            GalaxySSIMqttClient.removeListener(listener)
        }
    }

    @Test fun restartAutomaticallyFetchesArchivedBody(): Unit = runBlocking {
        val id = enabledCase()
        val state = withContext(Dispatchers.IO) { load(id) }
        assertEquals("dropped", state.getString("phase"))
        assertNotEquals("Run this phase after a real process stop", state.getInt("setup_pid"), Process.myPid())
        val expected = AgentConnectorResponseCodec.decode(state.getJSONObject("response"))
        withContext(Dispatchers.IO) {
            assertNotNull(AgentPendingDeliveryStore.find(context, expected.sourceMessageId, expected.contactId))
            assertFalse("Body must be absent before reconnect", AgentConnectorResponseStore.contains(context, expected))
        }
        val start = SystemClock.elapsedRealtime()
        connect()
        val connectedAt = SystemClock.elapsedRealtime()
        // No explicit query, fetch, synthetic result, or model resubmission: production connection readiness wakes recovery.
        val recovered = withTimeout(60_000L) {
            var value: AgentConnectorResponse? = null
            while (value == null) {
                value = withContext(Dispatchers.IO) { AgentConnectorResponseStore.find(context, expected) }
                if (value == null) delay(100)
            }
            value
        }
        assertEquals(expected.content, recovered.content)
        assertEquals(state.getString("content_sha256"), hash(recovered.content))
        assertEquals(expected.statusSequence, recovered.statusSequence)
        withContext(Dispatchers.IO) {
            assertTrue("UI must not have rendered in a headless process", assistantEntries(state).isEmpty())
            state.put("phase", "inbox").put("inbox_pid", Process.myPid())
                .put("connection_ms", connectedAt - start)
                .put("body_after_ready_ms", SystemClock.elapsedRealtime() - connectedAt)
                .put("recovery_ms", SystemClock.elapsedRealtime() - start)
            save(id, state)
        }
        println("LIVE_FINAL phase=inbox case=$id recovery_ms=${state.getLong("recovery_ms")} " +
            "connection_ms=${state.getLong("connection_ms")} body_after_ready_ms=${state.getLong("body_after_ready_ms")} " +
            "exact_body=true pid=${Process.myPid()}")
    }

    @Test fun coldUiConsumesExactlyOneRecoveredReply(): Unit = runBlocking {
        val id = enabledCase()
        val state = withContext(Dispatchers.IO) { load(id) }
        assertEquals("inbox", state.getString("phase"))
        assertNotEquals("Run UI phase after a real process stop", state.getInt("inbox_pid"), Process.myPid())
        val expected = AgentConnectorResponseCodec.decode(state.getJSONObject("response"))
        val start = SystemClock.elapsedRealtime()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            withTimeout(30_000L) {
                while (withContext(Dispatchers.IO) { assistantEntries(state).isEmpty() }) delay(100)
            }
            withContext(Dispatchers.IO) { assertTranscript(state, expected) }
            waitForVisibleReply(scenario, expected.content.trim())
            state.put("first_visible_ms", SystemClock.elapsedRealtime() - start)
            scenario.recreate()
            waitForVisibleReply(scenario, expected.content.trim())
            withContext(Dispatchers.IO) {
                assertTranscript(state, expected)
                assertFalse(AgentConnectorResponseStore.contains(context, expected))
                assertNull(AgentPendingDeliveryStore.find(context, expected.sourceMessageId, expected.contactId))
                state.put("phase", "rendered").put("ui_pid", Process.myPid())
                    .put("ui_ms", SystemClock.elapsedRealtime() - start)
                save(id, state)
            }
        }
        println("LIVE_FINAL phase=rendered case=$id first_visible_ms=${state.getLong("first_visible_ms")} " +
            "ui_ms=${state.getLong("ui_ms")} assistant_entries=1 recreation_verified=true")
    }

    @Test fun subsequentColdStartKeepsExactlyOneReply(): Unit = runBlocking {
        val id = enabledCase()
        val state = withContext(Dispatchers.IO) { load(id) }
        assertEquals("rendered", state.getString("phase"))
        assertNotEquals("Force-stop after UI consumption", state.getInt("ui_pid"), Process.myPid())
        val expected = AgentConnectorResponseCodec.decode(state.getJSONObject("response"))
        val start = SystemClock.elapsedRealtime()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            waitForVisibleReply(scenario, expected.content.trim())
            withContext(Dispatchers.IO) {
                assertTranscript(state, expected)
                assertFalse(AgentConnectorResponseStore.contains(context, expected))
                assertNull(AgentPendingDeliveryStore.find(context, expected.sourceMessageId, expected.contactId))
            }
        }
        println("LIVE_FINAL phase=cold_retained case=$id visible_ms=${SystemClock.elapsedRealtime() - start} assistant_entries=1")
    }

    private suspend fun waitForVisibleReply(scenario: ActivityScenario<MainActivity>, text: String) {
        withTimeout(30_000L) {
            var visible = false
            while (!visible) {
                scenario.onActivity { activity ->
                    visible = visibleText(activity.window.decorView, text)
                }
                if (!visible) delay(100)
            }
        }
    }

    private fun visibleText(view: View, text: String): Boolean {
        if (!view.isShown) return false
        if (view is TextView && view.text.toString().trim() == text) return true
        return view is ViewGroup && (0 until view.childCount).any { visibleText(view.getChildAt(it), text) }
    }

    private fun enabledCase(): String {
        assumeTrue("Explicit real-provider fault injection only", arguments.getString("live_final_probe") == "true")
        assertEquals("This test is authorized only on S20U", "SM-G9880", Build.MODEL)
        return requireNotNull(arguments.getString("live_final_id")).also {
            require(it.matches(Regex("live-final-[0-9]{10,20}")))
        }
    }

    private suspend fun connect() {
        withContext(Dispatchers.IO) { GalaxySSIMqttClient.connect(context) }
        withTimeout(20_000L) { while (!GalaxySSIMqttClient.isRequestReplyReady()) delay(100) }
    }

    private fun assistantEntries(state: JSONObject) = AgentTranscriptStore(context)
        .entriesForTurn(state.getString("turn"))
        .filter { it.conversationId == state.getString("conversation") && it.role == AgentTranscriptRole.ASSISTANT }

    private fun assertTranscript(state: JSONObject, expected: AgentConnectorResponse) {
        val entries = assistantEntries(state)
        assertEquals("Final must appear exactly once in its original conversation", 1, entries.size)
        assertEquals(expected.content.trim(), entries.single().text)
        assertEquals(expected.taskId, entries.single().taskId)
    }

    private fun save(id: String, state: JSONObject) {
        val encrypted = cipher.encrypt(state.toString(), id.toByteArray())
        check(preferences.edit().putString(id, encrypted).commit())
    }

    private fun load(id: String): JSONObject = JSONObject(requireNotNull(cipher.decrypt(
        requireNotNull(preferences.getString(id, null)), id.toByteArray())))

    private fun hash(text: String): String {
        val bytes = text.toByteArray(Charsets.UTF_8)
        return try { MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) } }
        finally { bytes.fill(0) }
    }
}
