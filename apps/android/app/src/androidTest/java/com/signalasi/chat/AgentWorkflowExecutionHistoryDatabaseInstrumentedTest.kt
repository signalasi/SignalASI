package com.signalasi.chat

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class AgentWorkflowExecutionHistoryDatabaseInstrumentedTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val databaseNames = mutableListOf<String>()
    private val legacyPreferencesNames = mutableListOf<String>()

    @After
    fun cleanUp() {
        databaseNames.forEach(context::deleteDatabase)
        legacyPreferencesNames.forEach { name ->
            context.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear().commit()
        }
    }

    @Test
    fun retainsEveryExecutionBeyondObsoleteCountAndSizeLimits() {
        val store = store()
        val records = (0 until 1_205).map { index ->
            record(
                id = "execution-$index",
                workflowId = "workflow-${index % 7}",
                startedAtMillis = index.toLong(),
                resultSummary = "result-$index-" + "x".repeat(480)
            )
        }
        records.forEach(store::upsert)

        val complete = store.recent(limit = 2_000)

        assertEquals(records.size, complete.size)
        assertEquals(records.map { it.id }.reversed(), complete.map { it.id })
        assertEquals(records.last(), store.findById(records.last().id))
    }

    @Test
    fun exportsAndRestoresTheCompleteEncryptedHistory() {
        val source = store()
        val marker = "private-workflow-result"
        val records = (0 until 725).map { index ->
            record(
                id = "backup-$index",
                workflowId = "workflow-${index % 3}",
                startedAtMillis = index.toLong(),
                resultSummary = if (index == 724) marker else "result-$index"
            )
        }
        records.forEach(source::upsert)

        val backup = source.exportJson()
        val restored = store()
        restored.replaceAllJson(backup)

        assertEquals(records.size, backup.length())
        assertEquals(records.map { it.id }.reversed(), restored.recent(1_000).map { it.id })
        val sourceDatabaseName = databaseNames.first()
        val encryptedPayload = AgentWorkflowExecutionHistoryDatabase(
            context,
            sourceDatabaseName
        ).useForTest { database ->
            database.encryptedPayloadForTest("backup-724").orEmpty()
        }
        assertTrue(encryptedPayload.startsWith("enc:v1:"))
        assertFalse(encryptedPayload.contains(marker))
    }

    @Test
    fun deletesOnlyTheRequestedWorkflowHistory() {
        val store = store()
        store.upsert(record("a-1", "workflow-a", 1L))
        store.upsert(record("a-2", "workflow-a", 2L))
        store.upsert(record("b-1", "workflow-b", 3L))

        assertEquals(2, store.deleteForWorkflow("workflow-a"))
        assertNull(store.findById("a-1"))
        assertEquals(listOf("b-1"), store.recent(10).map { it.id })
    }

    @Test
    fun ignoresObsoletePreferencesHistory() {
        val legacyName = "signalasi_agent_workflow_execution_history"
        legacyPreferencesNames += legacyName
        AgentEncryptedPreferences(context, legacyName).writeString(
            "items",
            JSONArray()
                .put(JSONObject().put("id", "obsolete-record"))
                .toString()
        )

        val store = store()

        assertTrue(store.recent(10).isEmpty())
    }

    private fun store(): AgentWorkflowExecutionHistoryStore {
        val name = "signalasi_workflow_history_test_${UUID.randomUUID()}.db"
        databaseNames += name
        return AgentWorkflowExecutionHistoryStore(context, name)
    }

    private fun record(
        id: String,
        workflowId: String,
        startedAtMillis: Long,
        resultSummary: String = "result"
    ) = AgentWorkflowExecutionRecord(
        id = id,
        workflowId = workflowId,
        workflowName = "Workflow $workflowId",
        source = AgentWorkflowExecutionSource.MANUAL,
        status = AgentWorkflowExecutionStatus.COMPLETED,
        startedAtMillis = startedAtMillis,
        completedAtMillis = startedAtMillis + 1L,
        resultSummary = resultSummary
    )

    private inline fun <T> AgentWorkflowExecutionHistoryDatabase.useForTest(
        block: (AgentWorkflowExecutionHistoryDatabase) -> T
    ): T = try {
        block(this)
    } finally {
        close()
    }
}
