package com.signalasi.chat

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
class AgentTaskDatabaseInstrumentedTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val databaseNames = mutableListOf<String>()

    @After
    fun cleanUp() {
        databaseNames.forEach(context::deleteDatabase)
        AgentEncryptedPreferences(context, LEGACY_PREFERENCES).clear()
    }

    @Test
    fun retainsEveryTaskBeyondLegacyCountLimit() {
        val database = database()
        repeat(1_205) { index ->
            database.upsert(record(index, "session-main"))
        }

        assertEquals(1_205, database.count())
        assertEquals(1_205, database.forSession("session-main", 2_000).size)
        assertEquals("task-1204", database.recent(1).single().taskId)
        assertEquals("task-0000", database.find("task-0000")?.taskId)

        val restored = database()
        restored.replaceAllJson(database.exportJson())
        assertEquals(1_205, restored.count())
        assertEquals("task-0000", restored.find("task-0000")?.taskId)
        restored.close()
        database.close()
    }

    @Test
    fun storesCompleteTaskPayloadEncryptedWithoutTimelineTruncation() {
        val database = database()
        val marker = "private-agent-task-marker"
        val timeline = (0 until 175).map { index -> "step $index $marker" }
        val files = (0 until 125).map { index -> "output/file-$index.txt" }
        database.upsert(
            record(1, "session-private").copy(
                result = "result $marker",
                verification = "verified $marker",
                outputFiles = files,
                executionLog = timeline
            )
        )

        val restored = database.find("task-0001")!!
        assertEquals(files, restored.outputFiles)
        assertEquals(timeline, restored.executionLog)
        val encrypted = database.encryptedPayloadForTest("task-0001").orEmpty()
        assertTrue(encrypted.startsWith("enc:v1:"))
        assertFalse(encrypted.contains(marker))
        database.close()
    }

    @Test
    fun rebindAndDeleteAffectOnlySelectedSessions() {
        val database = database()
        database.upsert(record(1, "session-source"))
        database.upsert(record(2, "session-source"))
        database.upsert(record(3, "session-keep"))

        assertEquals(2, database.rebindSession("session-source", "session-target"))
        assertTrue(database.forSession("session-source", 10).isEmpty())
        assertEquals(2, database.forSession("session-target", 10).size)

        database.deleteByTaskOrSessionIds(setOf("task-0001", "session-keep"))
        assertNull(database.find("task-0001"))
        assertNull(database.find("task-0003"))
        assertEquals("task-0002", database.find("task-0002")?.taskId)
        database.close()
    }

    @Test
    fun obsoleteSharedPreferencesTasksAreNotImported() {
        AgentEncryptedPreferences(context, LEGACY_PREFERENCES).writeString(
            LEGACY_KEY,
            JSONArray().put(
                JSONObject()
                    .put("task_id", "legacy-task")
                    .put("goal", "legacy goal")
            ).toString()
        )
        val databaseName = newDatabaseName()
        val database = AgentTaskDatabase(context, databaseName)

        assertEquals(0, database.count())
        assertNull(database.find("legacy-task"))
        database.close()
    }

    private fun database(): AgentTaskDatabase =
        AgentTaskDatabase(context, newDatabaseName())

    private fun newDatabaseName(): String {
        val name = "signalasi_agent_tasks_test_${UUID.randomUUID()}.db"
        databaseNames += name
        return name
    }

    private fun record(index: Int, sessionId: String): AgentTaskRecord =
        AgentTaskRecord(
            taskId = "task-${index.toString().padStart(4, '0')}",
            sessionId = sessionId,
            goal = "request $index",
            phase = AgentPhase.COMPLETED,
            routeKind = AgentRouteKind.DESKTOP_AGENT,
            targetTitle = "Codex",
            risk = AgentRisk.LOW,
            blocked = false,
            result = "result $index",
            verification = "verified",
            outputFiles = listOf("output-$index.txt"),
            executionLog = listOf("step $index"),
            createdAtMillis = 10_000L + index,
            updatedAtMillis = 20_000L + index
        )

    private companion object {
        const val LEGACY_PREFERENCES = "signalasi_agent_tasks"
        const val LEGACY_KEY = "items"
    }
}
