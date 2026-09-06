package com.galaxyssi.chat.blob

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.database.DatabaseErrorHandler
import android.database.sqlite.SQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.galaxyssi.chat.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class BlobFailureDeliveryDeviceTest {
    private val pending = AgentPendingDelivery(8900001, "附件恢复测试会话", "test-turn", "test-task", "test-codex")
    private fun body() = JSONObject().put("manifest", JSONObject()
        .put("client_route_id", "test-route").put("conversation_id", pending.conversationId)
        .put("task_id", pending.taskId).put("turn_id", pending.turnId).put("contact_id", pending.contactId)
        .put("client_message_id", pending.sourceMessageId).put("attachment_id", "test-attachment")
        .put("transfer_id", "a".repeat(64)).put("sha256", "b".repeat(64)).put("size_bytes", 123)).also {
        it.put("receipt", BlobFailureContract.receipt(it.getJSONObject("manifest"), "blob_expired"))
    }

    private fun fixture(block: (Context) -> Unit) {
        val context = FailureContext(InstrumentationRegistry.getInstrumentation().targetContext)
        try { block(context) } finally {
            AgentPendingDeliveryStore.close(context)
            AgentConnectorResponseStore.clear(context)
            context.cleanup()
        }
    }

    @Test fun failureUsesEncryptedInboxAndRetainsPendingUntilRuntimeConsumesIt() = fixture { context ->
        AgentPendingDeliveryStore.put(context, pending)
        assertTrue(BlobFailureDelivery.persist(context, body()))
        assertEquals(pending, AgentPendingDeliveryStore.find(context, pending.sourceMessageId))
        assertNull(AgentTerminalDeliveryStore.find(context, pending.sourceMessageId))
        AgentConnectorResponseInbox(context).use { reopened ->
            val response = reopened.page().responses.single()
            assertEquals("blob_expired", response.deliveryFailureCode)
            assertFalse(response.success)
            assertEquals(pending.conversationId, response.conversationId)
            assertEquals(pending.turnId, response.turnId)
            assertEquals(pending.taskId, response.taskId)
            assertTrue(BlobFailureDelivery.persist(context, body()))
            assertEquals(1, reopened.page().responses.size)
            assertTrue(reopened.acknowledge(response))
        }
        AgentPendingDeliveryStore.remove(context, pending.sourceMessageId)
        assertTrue(BlobFailureDelivery.persist(context, body()))
        assertTrue(AgentConnectorResponseStore.pending(context).isEmpty())
    }

    @Test fun failureCannotCrossPendingIdentityOrReviveTerminalRequest() = fixture { context ->
        val variants = listOf(pending.copy(conversationId = "other"), pending.copy(turnId = "other"),
            pending.copy(taskId = "other"), pending.copy(contactId = "other"))
        variants.forEach {
            AgentPendingDeliveryStore.put(context, it)
            assertFalse(BlobFailureDelivery.persist(context, body()))
            assertTrue(AgentConnectorResponseStore.pending(context).isEmpty())
            AgentPendingDeliveryStore.remove(context, it.sourceMessageId)
        }
        AgentTerminalDeliveryStore.mark(context, pending, "用户已取消")
        assertTrue(BlobFailureDelivery.persist(context, body()))
        assertTrue(AgentConnectorResponseStore.pending(context).isEmpty())
    }

    @Test fun supervisedFailureSurvivesEncryptedLedgerRecreation() = fixture { context ->
        val ledger = EncryptedAgentManagedResponseLedger(context)
        ledger.register(AgentManagedResponseRecord("blob-test-owner", "blob-test-supervisor", pending.contactId,
            AgentDeliveryMode.RESPOND, pending.sourceMessageId, pending.contactId, pending.conversationId,
            pending.turnId, pending.taskId))
        AgentPendingDeliveryStore.put(context, pending)
        assertTrue(BlobFailureDelivery.persist(context, body()))
        val restored = EncryptedAgentManagedResponseLedger(context).completedUnapplied().single().response!!
        assertEquals("blob_expired", restored.deliveryFailureCode)
        assertFalse(restored.success)
        assertEquals(pending.taskId, restored.taskId)
        assertTrue(AgentConnectorResponseStore.pending(context).isEmpty())
        assertNull(AgentTerminalDeliveryStore.find(context, pending.sourceMessageId))
    }
}

private class FailureContext(base: Context) : ContextWrapper(base) {
    private val prefix = "blob-failure-test-${UUID.randomUUID()}"
    private val databases = linkedSetOf<String>()
    private val preferences = linkedSetOf<String>()
    override fun getApplicationContext(): Context = this
    override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
        preferences.add(name)
        return super.getSharedPreferences("$prefix-$name", mode)
    }
    override fun getDatabasePath(name: String): File {
        val file = if (File(name).isAbsolute) File(name) else baseContext.getDatabasePath("$prefix-$name")
        check(file.parentFile == baseContext.getDatabasePath("test.db").parentFile && file.name.startsWith(prefix))
        databases.add(file.absolutePath)
        return file
    }
    override fun openOrCreateDatabase(name: String, mode: Int, factory: SQLiteDatabase.CursorFactory?): SQLiteDatabase {
        return baseContext.openOrCreateDatabase(getDatabasePath(name).absolutePath, mode, factory)
    }
    override fun openOrCreateDatabase(name: String, mode: Int, factory: SQLiteDatabase.CursorFactory?,
        errorHandler: DatabaseErrorHandler?): SQLiteDatabase {
        return baseContext.openOrCreateDatabase(getDatabasePath(name).absolutePath, mode, factory, errorHandler)
    }
    fun cleanup() {
        databases.forEach { baseContext.deleteDatabase(it) }
        preferences.forEach { baseContext.deleteSharedPreferences("$prefix-$it") }
    }
}
