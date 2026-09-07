package com.galaxyssi.chat

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.content.res.Configuration
import android.database.DatabaseErrorHandler
import android.database.sqlite.SQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.galaxyssi.chat.voice.VoiceFeatureFlags
import java.io.File
import java.util.Base64
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Exercise the actual service callback with isolated storage, without starting its network/lifecycle workers. */
@RunWith(AndroidJUnit4::class)
class BackgroundAgentOutcomeDeviceTest {
    private lateinit var context: BackgroundOutcomeContext
    private lateinit var service: MessageService
    private val owner = "background-outcome-${UUID.randomUUID()}"

    @Before fun setup() {
        assertFalse("The test requires a headless instrumentation process", AppForegroundTracker.isForeground())
        context = BackgroundOutcomeContext(InstrumentationRegistry.getInstrumentation().targetContext, owner)
        VoiceFeatureFlags.setAgentVoiceRunBridgeEnabled(context, false)
        service = MessageService()
        MessageService::class.java.getDeclaredMethod("attachBaseContext", Context::class.java).apply {
            isAccessible = true
        }.invoke(service, context)
    }

    @After fun cleanup() {
        AgentManagedConnectorResponseRegistry.unregisterOwner(owner)
        if (::context.isInitialized) {
            AgentConnectorResponseStore.clear(context)
            context.cleanup()
        }
    }

    private fun payload(status: String = "completed", generation: Long = 1) = JSONObject()
        .put("type", "text").put("source_message_id", 890001L).put("contact_id", "$owner-contact")
        .put("conversation_id", "$owner-conversation").put("turn_id", "$owner-turn").put("task_id", "$owner-task")
        .put("task_status", status).put("execution_generation", generation).put("status_sequence", 27L)
        .put("content", "后台回复验证")

    private fun receive(payload: JSONObject): AgentConnectorResponse? {
        var result: AgentConnectorResponse? = null
        AgentManagedConnectorResponseRegistry.register(payload.getLong("source_message_id"),
            payload.getString("contact_id"), owner, payload.getString("conversation_id"),
            payload.getString("turn_id"), payload.getString("task_id")) {
            result = it
            true
        }
        service.onMessage(payload.toString())
        return result
    }

    @Test fun retryGenerationSurvivesBackgroundServiceAndCurrentExecutionFence() {
        val payload = payload(generation = 2)
        val observation = AgentRemoteOutcomeCodec.observation(payload)!!
        assertTrue(AgentConnectorResponseStore.observeExecution(context, observation, finalReply = true))
        val response = receive(payload)
        assertNotNull("The service must not downgrade generation 2 to the stale generation 1", response)
        assertEquals(2L, response!!.executionGeneration)
        assertEquals(27L, response.statusSequence)
        assertEquals("completed", response.taskStatus)
    }

    @Test fun cancelledOutcomeIsNotRecreatedAsSuccess() {
        val response = receive(payload("cancelled").put("success", true).put("error", "用户取消了任务"))!!
        assertFalse("Cancellation cannot become success in background", response.success)
        assertEquals("cancelled", response.taskStatus)
        assertEquals("用户取消了任务", response.content)
    }

    @Test fun failedOutcomeRetainsActualCause() {
        val response = receive(payload("failed").put("error", "模型返回 HTTP 429"))!!
        assertFalse(response.success)
        assertTrue(response.remoteFailure)
        assertEquals("模型返回 HTTP 429", response.content)
    }

    @Test fun timedOutOutcomeRetainsItsSequence() {
        val response = receive(payload("timed_out").put("status_sequence", 98L))!!
        assertFalse(response.success)
        assertEquals("timed_out", response.taskStatus)
        assertEquals(98L, response.statusSequence)
    }

    @Test fun exactFinalTextWinsOverNotificationPreview() {
        val exact = "第一段。\n\n第二段。"
        val response = receive(payload().put("content", "通知预览")
            .put("exact_content_encoding", "base64-utf8")
            .put("exact_content_b64", Base64.getEncoder().encodeToString(exact.toByteArray())))!!
        assertEquals(exact, response.content)
    }

    @Test fun terminalUserDeliveryDoesNotComeBackThroughBackgroundService() {
        val payload = payload()
        val pending = AgentPendingDelivery(payload.getLong("source_message_id"), payload.getString("conversation_id"),
            payload.getString("turn_id"), payload.getString("task_id"), payload.getString("contact_id"))
        AgentTerminalDeliveryStore.mark(context, pending, "用户已停止等待")
        assertNull("A terminal user delivery must not resurrect", receive(payload))
    }

    @Test fun versionedBackgroundReplyCanBePersistedWithoutBeingRejectedAsStale() {
        val payload = payload(generation = 3)
        assertTrue(AgentConnectorResponseStore.observeExecution(context,
            AgentRemoteOutcomeCodec.observation(payload)!!, finalReply = true))
        val response = receive(payload)!!
        assertTrue(AgentConnectorResponseStore.append(context, response))
        val restored = AgentConnectorResponseStore.find(context, response)!!
        assertEquals(3L, restored.executionGeneration)
        assertEquals("completed", restored.taskStatus)
        assertEquals(response.content, restored.content)
    }

    @Test fun oldGenerationDoesNotReturnAfterANewerExecutionWasObserved() {
        val current = payload(generation = 2)
        assertTrue(AgentConnectorResponseStore.observeExecution(context,
            AgentRemoteOutcomeCodec.observation(current)!!, finalReply = true))
        assertNull(receive(payload(generation = 1)))
        assertTrue(AgentConnectorResponseStore.pending(context).isEmpty())
        assertEquals(0, ChatHistoryStore.readContact(context, current.getString("contact_id")).length())
    }

    @Test fun peerMessageWithSourceIdStaysInContactHistory() {
        val peer = payload().put("peer_chat", true).put("content", "联系人消息验证")
        assertNull("Peer chat is not an agent terminal response", receive(peer))
        assertTrue(AgentConnectorResponseStore.pending(context).isEmpty())
        assertEquals(1, ChatHistoryStore.readContact(context, peer.getString("contact_id")).length())
    }

    @Test fun ordinaryMessageWithoutAgentScopeStaysInContactHistory() {
        val ordinary = payload().apply {
            remove("conversation_id"); remove("turn_id"); remove("task_id")
        }
        service.onMessage(ordinary.toString())
        assertTrue(AgentConnectorResponseStore.pending(context).isEmpty())
        assertEquals(1, ChatHistoryStore.readContact(context, ordinary.getString("contact_id")).length())
    }
}

private class BackgroundOutcomeContext(base: Context, private val prefix: String) : ContextWrapper(base) {
    private val root = File(base.cacheDir, prefix).apply { mkdirs() }
    private val preferenceNames = ConcurrentHashMap.newKeySet<String>()
    override fun getApplicationContext(): Context = this
    override fun createConfigurationContext(configuration: Configuration): Context = this
    override fun getFilesDir(): File = File(root, "files").apply { mkdirs() }
    override fun getCacheDir(): File = File(root, "cache").apply { mkdirs() }
    override fun getNoBackupFilesDir(): File = File(root, "no-backup").apply { mkdirs() }
    override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
        preferenceNames += "$prefix-$name"
        return baseContext.getSharedPreferences("$prefix-$name", mode)
    }
    override fun getDatabasePath(name: String): File {
        val file = if (File(name).isAbsolute) File(name) else File(root, "db/$name")
        require(file.canonicalPath.startsWith(root.canonicalPath + File.separator))
        file.parentFile!!.mkdirs()
        return file
    }
    override fun openOrCreateDatabase(name: String, mode: Int, factory: SQLiteDatabase.CursorFactory?): SQLiteDatabase =
        SQLiteDatabase.openOrCreateDatabase(getDatabasePath(name), factory)
    override fun openOrCreateDatabase(name: String, mode: Int, factory: SQLiteDatabase.CursorFactory?,
        errorHandler: DatabaseErrorHandler?): SQLiteDatabase =
        SQLiteDatabase.openOrCreateDatabase(getDatabasePath(name).absolutePath, factory, errorHandler)
    override fun deleteDatabase(name: String): Boolean = SQLiteDatabase.deleteDatabase(getDatabasePath(name))
    override fun getSystemService(name: String): Any? {
        check(name != Context.NOTIFICATION_SERVICE) { "Test notifications are forbidden" }
        return super.getSystemService(name)
    }
    fun cleanup() {
        preferenceNames.forEach { baseContext.deleteSharedPreferences(it) }
        root.deleteRecursively()
    }
}
