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
class AgentResultReceiptDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private fun fields(source: Long = 42001, generation: Long = 2) = JSONObject()
        .put("client_route_id", "receipt-test-route").put("conversation_id", "receipt-test-conversation")
        .put("task_id", "receipt-test-task-$source").put("turn_id", "receipt-test-turn-$source")
        .put("contact_id", "receipt-test-contact").put("source_message_id", source.toString())
        .put("agent_id", "codex").put("execution_generation", generation).put("sha256", "a".repeat(64))
    private fun receipt(source: Long = 42001, generation: Long = 2) = AgentResultReceipt.from(fields(source, generation), "receipt-desktop")!!
    private fun response(source: Long = 42001, generation: Long = 2) = AgentConnectorResponse(
        source, "receipt-test-contact", "\u56de\u590d\u786e\u8ba4\u6301\u4e45\u5316\u6d4b\u8bd5",
        "receipt-test-conversation", "receipt-test-turn-$source", "receipt-test-task-$source",
        taskStatus = "completed", executionGeneration = generation)
    private fun inbox(name: String) = AgentConnectorResponseInbox(context, name, "legacy-$name")
    private inline fun isolated(block: (String) -> Unit) {
        val name = "receipt-test-${UUID.randomUUID()}.db"
        try { block(name) }
        finally { context.deleteDatabase(name); context.deleteSharedPreferences("legacy-$name") }
    }
    private fun sql(name: String, statement: String) = SQLiteDatabase.openDatabase(
        context.getDatabasePath(name).path, null, SQLiteDatabase.OPEN_READWRITE
    ).use { it.execSQL(statement) }

    @Test fun replyAndReceiptBothSurviveReopen() = isolated { name ->
        inbox(name).use { assertTrue(it.append(response(), receipt())) }
        inbox(name).use {
            assertEquals(response().content, it.page().responses.single().content)
            assertEquals(receipt(), it.dueReceipts(0).single().receipt)
            assertEquals(0L, it.nextReceiptWake())
        }
    }

    @Test fun receiptWriteFailureRollsBackReplyAndExecutionObservation() = isolated { name ->
        inbox(name).use { it.highWatermark() }
        sql(name, "CREATE TRIGGER fail_receipt BEFORE INSERT ON result_receipts BEGIN SELECT RAISE(ABORT,'test full disk'); END")
        inbox(name).use {
            assertTrue(runCatching { it.append(response(), receipt()) }.isFailure)
            assertFalse(it.wasRecorded(response()))
            assertTrue(it.page().responses.isEmpty())
            assertNull(it.nextReceiptWake())
            assertTrue(it.isCurrentExecution(response(generation = 1)))
        }
        sql(name, "DROP TRIGGER fail_receipt")
        inbox(name).use { assertTrue(it.append(response(), receipt())) }
    }

    @Test fun displayedReplyRemovalDoesNotDropItsReceipt() = isolated { name ->
        inbox(name).use {
            it.append(response(), receipt())
            it.acknowledge(response())
            assertTrue(it.page().responses.isEmpty())
            assertEquals(receipt(), it.dueReceipts(0).single().receipt)
        }
    }

    @Test fun successfulPublishClaimDoesNotRetireAndRetrySurvivesRestart() = isolated { name ->
        inbox(name).use {
            it.append(response(), receipt())
            val work = it.dueReceipts(0).single()
            assertTrue(it.claimReceipt(work, 1000))
            assertFalse(it.claimReceipt(work, 1000))
        }
        inbox(name).use {
            assertTrue(it.dueReceipts(5999).isEmpty())
            val retry = it.dueReceipts(6000).single()
            assertEquals(1, retry.attempts)
            assertEquals(0, retry.state)
        }
    }

    @Test fun confirmationIsDurableBeforeIdempotentCleanup() = isolated { name ->
        inbox(name).use {
            it.append(response(), receipt())
            val stale = it.dueReceipts(0).single()
            assertTrue(it.confirmReceipt(receipt()))
            assertFalse(it.claimReceipt(stale, 1000))
        }
        inbox(name).use {
            assertEquals(1, it.dueReceipts(0).single().state)
            assertTrue(it.cleanedReceipt(receipt()))
            assertFalse(it.cleanedReceipt(receipt()))
            assertNull(it.nextReceiptWake())
            assertFalse(it.append(response(), receipt()))
            assertNull(it.nextReceiptWake())
        }
    }

    @Test fun wrongReceiptCannotConfirmOrCleanAnotherGeneration() = isolated { name ->
        inbox(name).use {
            it.append(response(), receipt())
            it.append(response(generation = 3), receipt(generation = 3))
            assertFalse(it.confirmReceipt(receipt(42002)))
            assertFalse(it.cleanedReceipt(receipt(generation = 3)))
            assertTrue(it.confirmReceipt(receipt()))
            assertTrue(it.cleanedReceipt(receipt()))
            assertEquals(receipt(generation = 3), it.dueReceipts(0).single().receipt)
        }
    }

    @Test fun mismatchedReceiptRollsBackWithoutDiscardingOtherReceipts() = isolated { name ->
        inbox(name).use {
            it.append(response(), receipt())
            assertTrue(runCatching { it.append(response(42002), receipt(42003)) }.isFailure)
            assertFalse(it.wasRecorded(response(42002)))
            assertEquals(1, it.dueReceipts(0).size)
        }
    }

    @Test fun corruptReceiptDoesNotBlockOthersAndVerifiedReplayCanRepairIt() = isolated { name ->
        inbox(name).use {
            it.append(response(), receipt())
            it.append(response(42002), receipt(42002))
        }
        sql(name, "UPDATE result_receipts SET encrypted_value='corrupt' WHERE receipt_id='${receipt().id}'")
        inbox(name).use {
            assertEquals(receipt(42002), it.dueReceipts(0).single().receipt)
            assertFalse(it.append(response(), receipt()))
            assertEquals(2, it.dueReceipts(0).size)
        }
    }

    @Test fun boundedQueueDoesNotLoseReceiptsBeyondTheFirstPage() = isolated { name ->
        inbox(name).use { store ->
            repeat(129) { store.append(response(50000L + it), receipt(50000L + it)) }
            SQLiteDatabase.openDatabase(context.getDatabasePath(name).path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
                for ((query, args) in listOf(AgentResultReceiptJournal.DUE_SQL to arrayOf("0", "32", "0", "32"),
                    AgentResultReceiptJournal.NEXT_SQL to emptyArray<String>())) {
                    val plan = db.rawQuery("EXPLAIN QUERY PLAN $query", args).use { cursor -> buildList {
                        while (cursor.moveToNext()) add(cursor.getString(3))
                    } }
                    assertFalse(plan.any { it.contains("TEMP B-TREE") })
                    assertEquals(2, plan.count { it.contains("result_receipts_due") })
                }
            }
            val ids = mutableSetOf<String>()
            while (true) {
                val page = store.dueReceipts(0)
                if (page.isEmpty()) break
                assertTrue(page.size <= 32)
                page.forEach { work ->
                    assertTrue(ids.add(work.receipt.id))
                    assertTrue(store.confirmReceipt(work.receipt))
                    assertTrue(store.cleanedReceipt(work.receipt))
                }
            }
            assertEquals(129, ids.size)
        }
    }

    @Test fun awaitingAndConfirmedQueuesMergeByDeadlineWithoutRepeatingRows() = isolated { name ->
        inbox(name).use { store ->
            repeat(70) {
                val value = receipt(60000L + it)
                store.append(response(60000L + it), value)
                if (it % 2 == 0) store.confirmReceipt(value)
            }
            val first = store.dueReceipts(0)
            assertEquals(32, first.size)
            assertEquals(first.map { it.receipt.id }.sorted(), first.map { it.receipt.id })
            assertEquals(32, first.map { it.receipt.id }.distinct().size)
            first.forEach { assertTrue(store.claimReceipt(it, 0)) }
            val next = store.dueReceipts(0)
            assertEquals(32, next.size)
            assertTrue(next.none { work -> first.any { it.receipt.id == work.receipt.id } })
        }
    }

    @Test fun versionTwoDatabaseMigratesWithoutChangingReply() = isolated { name ->
        inbox(name).use { it.append(response()) }
        sql(name, "DROP TABLE result_receipts")
        sql(name, "PRAGMA user_version=2")
        inbox(name).use {
            assertEquals(response().content, it.page().responses.single().content)
            assertFalse(it.append(response(), receipt()))
            assertEquals(receipt(), it.dueReceipts(0).single().receipt)
        }
    }

    @Test fun acknowledgementWithoutDurableReplyIsNotAccepted() = isolated { name ->
        inbox(name).use {
            assertFalse(it.confirmReceipt(receipt()))
            assertFalse(it.cleanedReceipt(receipt()))
            assertNull(it.nextReceiptWake())
        }
    }

    @Test fun receiptBodiesAreEncryptedAndExplicitStoreClearRemovesThem() = isolated { name ->
        inbox(name).use { it.append(response(), receipt()) }
        val bytes = context.getDatabasePath(name).readBytes()
        try {
            val stored = String(bytes, Charsets.ISO_8859_1)
            assertFalse(stored.contains("receipt-test-conversation"))
            assertFalse(stored.contains("receipt-desktop"))
            assertFalse(stored.contains("client_route_id"))
        } finally { bytes.fill(0) }
        inbox(name).use { it.clear(); assertNull(it.nextReceiptWake()) }
    }

    @Test fun saveBeforeProcessDeath() {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("receipt_phase") == "save")
        val id = requireNotNull(args.getString("receipt_test_id"))
        require(Regex("[a-zA-Z0-9-]{8,80}").matches(id))
        inbox("receipt-process-$id.db").use {
            it.append(response(), receipt())
            it.append(response(42002), receipt(42002))
            assertTrue(it.confirmReceipt(receipt(42002)))
        }
    }

    @Test fun recoverAfterProcessDeath() {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("receipt_phase") == "recover")
        val id = requireNotNull(args.getString("receipt_test_id"))
        require(Regex("[a-zA-Z0-9-]{8,80}").matches(id))
        val name = "receipt-process-$id.db"
        try {
            inbox(name).use {
                assertTrue(it.wasRecorded(response()))
                val work = it.dueReceipts(0)
                assertEquals(2, work.size)
                assertEquals(0, work.single { entry -> entry.receipt == receipt() }.state)
                assertEquals(1, work.single { entry -> entry.receipt == receipt(42002) }.state)
                assertTrue(it.confirmReceipt(receipt()))
                work.forEach { entry -> assertTrue(it.cleanedReceipt(entry.receipt)) }
                assertNull(it.nextReceiptWake())
            }
        } finally { context.deleteDatabase(name); context.deleteSharedPreferences("legacy-$name") }
    }
}
