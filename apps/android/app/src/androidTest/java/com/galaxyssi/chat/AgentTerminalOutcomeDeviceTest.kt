package com.galaxyssi.chat

import android.database.sqlite.SQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.UUID
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AgentTerminalOutcomeDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private fun response(generation: Long = 1, sequence: Long = 10, status: String = "failed") =
        AgentConnectorResponse(42, "isolated-codex", "\u7f51\u7edc\u8fde\u63a5\u5931\u8d25",
            "isolated-conversation", "isolated-turn", "isolated-task", success = status == "completed",
            taskStatus = status, executionGeneration = generation, statusSequence = sequence)

    private inline fun database(test: (String, AgentConnectorResponseInbox) -> Unit) {
        val name = "terminal-test-${UUID.randomUUID()}.db"
        val inbox = AgentConnectorResponseInbox(context, name, "legacy-$name")
        try { test(name, inbox) }
        finally { inbox.close(); context.deleteDatabase(name); context.deleteSharedPreferences("legacy-$name") }
    }

    @Test fun allOutcomesRetainActualStatusAcrossEncryptedReopen() = database { name, inbox ->
        for ((index, status) in AgentRemoteOutcomeCodec.TERMINAL.withIndex()) {
            assertTrue(inbox.append(response(status = status).copy(sourceMessageId = 42L + index)))
        }
        inbox.close()
        AgentConnectorResponseInbox(context, name, "legacy-$name").use { reopened ->
            assertEquals(AgentRemoteOutcomeCodec.TERMINAL, reopened.page().responses.map { it.taskStatus }.toSet())
            reopened.page().responses.forEach { assertEquals(it.taskStatus == "completed", it.success) }
        }
    }

    @Test fun newerExecutionRetiresOldBodyAndRejectsLateFailure() = database { _, inbox ->
        val old = response()
        assertTrue(inbox.append(old))
        assertTrue(inbox.observeExecution(response(2, 1).copy(content = "", taskStatus = "running")))
        assertTrue(inbox.page().responses.isEmpty())
        assertFalse(inbox.append(old))
        assertNull(inbox.find(old))
        val current = response(2, 3, "completed")
        assertTrue(inbox.append(current))
        assertEquals(current, inbox.page().responses.single())
        assertFalse(inbox.observeExecution(response(1, 999)))
    }

    @Test fun oldHandledReplyCannotAcknowledgeNewGeneration() = database { _, inbox ->
        val old = response()
        val current = response(2, 2, "completed")
        assertTrue(inbox.append(old))
        assertTrue(inbox.append(current))
        inbox.acknowledgeThrough(old)
        assertTrue(inbox.contains(current))
        assertTrue(inbox.wasRecorded(old))
    }

    @Test fun unrelatedTaskAndConversationWatermarksAreIndependent() = database { _, inbox ->
        val current = response(5, 100)
        assertTrue(inbox.observeExecution(current))
        val unrelated = listOf(response().copy(sourceMessageId = 43), response().copy(contactId = "other"),
            response().copy(conversationId = "other"), response().copy(turnId = "other"), response().copy(taskId = "other"))
        unrelated.forEach { assertTrue(inbox.append(it)) }
        assertEquals(5, inbox.page().responses.size)
        assertFalse(inbox.append(response()))
    }

    @Test fun missingLegacySequenceDoesNotEraseKnownRevision() = database { _, inbox ->
        assertTrue(inbox.observeExecution(response(sequence = 100)))
        assertTrue(inbox.append(response(sequence = -1)))
        assertFalse(inbox.observeExecution(response(sequence = 99)))
        assertTrue(inbox.isCurrentExecution(response(sequence = 100)))
    }

    @Test fun duplicateReplyKeepsFirstBodyWithoutASecondRow() = database { _, inbox ->
        val original = response(2)
        assertTrue(inbox.append(original))
        assertFalse(inbox.append(original.copy(content = "different replay text")))
        assertEquals(original.content, inbox.page().responses.single().content)
    }

    @Test fun laterMetadataRevisionDoesNotHideCanonicalTerminalReply() = database { _, inbox ->
        assertTrue(inbox.observeExecution(response(2, 100)))
        assertTrue(inbox.append(response(2, 90, "completed")))
        assertEquals(90L, inbox.page().responses.single().statusSequence)
        assertFalse(inbox.observeExecution(response(2, 99)))
    }

    @Test fun bodyInsertFailureRollsBackWatermarkAndOldBodyRetirement() = database { name, inbox ->
        assertTrue(inbox.append(response()))
        SQLiteDatabase.openDatabase(context.getDatabasePath(name).path, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
            db.execSQL("CREATE TRIGGER fail_body BEFORE INSERT ON inbox BEGIN SELECT RAISE(ABORT,'test body failure'); END")
        }
        assertTrue(runCatching { inbox.append(response(2)) }.isFailure)
        assertTrue(inbox.isCurrentExecution(response()))
        assertEquals(1L, inbox.page().responses.single().executionGeneration)
        SQLiteDatabase.openDatabase(context.getDatabasePath(name).path, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
            db.execSQL("DROP TRIGGER fail_body")
        }
        assertTrue(inbox.append(response(2)))
    }

    @Test fun retirementFailureCannotAdvanceExecutionHead() = database { name, inbox ->
        assertTrue(inbox.append(response()))
        SQLiteDatabase.openDatabase(context.getDatabasePath(name).path, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
            db.execSQL("CREATE TRIGGER fail_retirement BEFORE UPDATE ON inbox BEGIN SELECT RAISE(ABORT,'test retirement failure'); END")
        }
        assertTrue(runCatching { inbox.observeExecution(response(2)) }.isFailure)
        assertTrue(inbox.isCurrentExecution(response()))
        assertTrue(inbox.contains(response()))
    }

    @Test fun versionOneDatabaseMigratesWithoutChangingEncryptedIdentity() {
        val name = "terminal-upgrade-${UUID.randomUUID()}.db"
        val original = response(status = "completed").copy(taskStatus = "", statusSequence = -1)
        val key = AgentConnectorResponseCodec.scopeIdentity(original)
        val body = AgentConnectorResponseCodec.encode(original).apply {
            remove("task_status"); remove("execution_generation"); remove("status_sequence")
        }.toString()
        try {
            SQLiteDatabase.openOrCreateDatabase(context.getDatabasePath(name), null).use { db ->
                db.execSQL("CREATE TABLE inbox(sequence INTEGER PRIMARY KEY AUTOINCREMENT,identity_key TEXT NOT NULL UNIQUE," +
                    "turn_key TEXT NOT NULL,handled INTEGER NOT NULL DEFAULT 0,encrypted_value TEXT)")
                db.execSQL("CREATE INDEX inbox_pending ON inbox(handled,sequence)")
                db.execSQL("CREATE INDEX inbox_turn ON inbox(turn_key,handled)")
                db.execSQL("CREATE TABLE inbox_metadata(name TEXT PRIMARY KEY NOT NULL)")
                db.execSQL("INSERT INTO inbox_metadata VALUES('legacy_migrated')")
                db.execSQL("INSERT INTO inbox(identity_key,turn_key,encrypted_value) VALUES(?,?,?)", arrayOf(key,
                    AgentConnectorResponseCodec.turnKey(original.conversationId, original.turnId),
                    AgentStorageCipher.encrypt(body, "connector-inbox:$name:$key".toByteArray(Charsets.UTF_8))))
                db.version = 1
            }
            AgentConnectorResponseInbox(context, name, "legacy-$name").use { inbox ->
                assertEquals(original, inbox.find(original))
                assertTrue(inbox.observeExecution(response(2)))
                assertTrue(inbox.page().responses.isEmpty())
                assertTrue(inbox.wasRecorded(original))
            }
        } finally { context.deleteDatabase(name); context.deleteSharedPreferences("legacy-$name") }
    }

    @Test fun metadataContainsNoPlaintextConversationOrError() = database { name, inbox ->
        inbox.append(response())
        SQLiteDatabase.openDatabase(context.getDatabasePath(name).path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
            db.rawQuery("SELECT scope_key FROM remote_executions", null).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertTrue(cursor.getString(0).matches(Regex("[a-f0-9]{64}")))
            }
            db.rawQuery("SELECT encrypted_value FROM inbox", null).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertFalse(cursor.getString(0).contains(response().content))
                assertFalse(cursor.getString(0).contains("isolated-conversation"))
            }
        }
    }

    @Test fun statusOnlyCancellationUsesTheCurrentAppLanguage() {
        val payload = JSONObject().put("task_status", "cancelled").put("terminal_reason", "cancelled")
        assertEquals(context.getString(R.string.agent_task_status_cancelled), AgentRemoteOutcomeCodec.content(context, payload))
        assertEquals("original provider error", AgentRemoteOutcomeCodec.content(context,
            payload.put("task_status", "failed").put("error", "original provider error"), "translated generic failure"))
    }

    private fun processName(): String {
        val id = InstrumentationRegistry.getArguments().getString("terminalRecoveryId").orEmpty()
        assumeTrue(id.startsWith("terminal-process-") && id.matches(Regex("[a-zA-Z0-9-]+")))
        return "$id.db"
    }

    @Test fun saveBeforeProcessDeath() {
        val name = processName()
        AgentConnectorResponseInbox(context, name, "legacy-$name").use { inbox ->
            assertTrue(inbox.append(response()))
            assertTrue(inbox.observeExecution(response(3, 1).copy(content = "")))
            assertTrue(inbox.append(response(3, 2, "cancelled")))
        }
    }

    @Test fun recoverAfterProcessDeath() {
        val name = processName()
        try {
            AgentConnectorResponseInbox(context, name, "legacy-$name").use { inbox ->
                assertFalse(inbox.append(response()))
                val restored = inbox.page().responses.single()
                assertEquals(3L, restored.executionGeneration)
                assertEquals("cancelled", restored.taskStatus)
                assertFalse(restored.success)
                assertTrue(inbox.acknowledge(restored))
                assertFalse(inbox.append(restored))
            }
        } finally { context.deleteDatabase(name); context.deleteSharedPreferences("legacy-$name") }
    }
}
