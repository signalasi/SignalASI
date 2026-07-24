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
class ChatHistoryDatabaseInstrumentedTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val databaseNames = mutableListOf<String>()

    @After
    fun cleanUp() {
        databaseNames.forEach(context::deleteDatabase)
    }

    @Test
    fun retainsAndPagesEveryMessageWithoutCountLimit() {
        val database = database()
        val expectedIds = (1L..1_205L).toList()
        expectedIds.forEach { id ->
            assertTrue(database.upsert(message(id, "contact-main", "message $id")))
        }

        val complete = database.readContact("contact-main")
        assertEquals(expectedIds, (0 until complete.length()).map { complete.getJSONObject(it).getLong("id") })

        val pagedIds = mutableListOf<Long>()
        var cursor: Long? = null
        do {
            val page = database.page(
                contactId = "contact-main",
                beforeSequenceExclusive = cursor,
                pageSize = 137
            )
            pagedIds += page.messages.map { it.getLong("id") }
            cursor = page.nextBeforeSequence
        } while (page.hasMore)

        assertEquals(expectedIds.size, pagedIds.size)
        assertEquals(expectedIds.toSet(), pagedIds.toSet())
        assertEquals(expectedIds.size, pagedIds.distinct().size)
        database.close()
    }

    @Test
    fun storesLongContentEncryptedWithoutTruncation() {
        val database = database()
        val marker = "private-chat-history-marker"
        val content = "message content ".repeat(4_000) + marker
        assertTrue(database.upsert(message(1L, "contact-long", content)))

        assertEquals(content, database.readContact("contact-long").getJSONObject(0).getString("content"))
        val encryptedPayload = database.encryptedPayloadForTest(1L).orEmpty()
        assertTrue(encryptedPayload.startsWith("enc:v1:"))
        assertFalse(encryptedPayload.contains(marker))
        database.close()
    }

    @Test
    fun deletedMessageCannotBeRestoredByStaleSnapshot() {
        val database = database()
        val item = message(7L, "contact-delete", "delete me")
        assertTrue(database.upsert(item))
        assertTrue(database.deleteMessage(7L))

        val staleSnapshot = JSONObject().put("contact-delete", JSONArray().put(item))
        assertFalse(database.mergeSnapshot(staleSnapshot))
        assertNull(database.findMessage(7L))
        database.close()
    }

    @Test
    fun deleteBeforeDelayedInsertStillPreventsResurrection() {
        val database = database()
        val item = message(8L, "contact-delete", "queued write")

        assertFalse(database.deleteMessage(8L))
        assertFalse(database.upsert(item))
        assertNull(database.findMessage(8L))
        database.close()
    }

    @Test
    fun deleteContactBlocksMessagesThatWereStillQueued() {
        val database = database()
        val item = message(9L, "contact-delete", "queued contact write")

        assertEquals(0, database.deleteContact("contact-delete", listOf(9L)))
        assertFalse(database.upsert(item))
        assertEquals(0, database.readContact("contact-delete").length())
        database.close()
    }

    @Test
    fun upsertAllPersistsTheBatchWithOneVersionChange() {
        val database = database()
        val beforeVersion = database.updatedVersion()
        val items = listOf(
            message(21L, "contact-batch", "first"),
            message(22L, "contact-batch", "second"),
            message(23L, "contact-batch", "third")
        )

        assertTrue(database.upsertAll(items))
        assertEquals(beforeVersion + 1L, database.updatedVersion())
        assertEquals(3, database.readContact("contact-batch").length())
        database.close()
    }

    @Test
    fun contactSummariesUseLatestVisibleMessageAndIndexedUnreadCounts() {
        val database = database()
        assertTrue(database.upsert(message(31L, "contact-a", "first incoming", isMine = false)))
        assertTrue(
            database.upsert(
                message(32L, "contact-a", "read incoming", isMine = false, isRead = true)
            )
        )
        assertTrue(
            database.upsert(
                message(33L, "contact-a", "internal status", isMine = false, isSystem = true)
            )
        )
        assertTrue(database.upsert(message(34L, "contact-b", "outgoing", isMine = true)))

        val summaries = database.readContactSummaries().associateBy { it.contactId }

        assertEquals(setOf("contact-a", "contact-b"), summaries.keys)
        assertEquals("read incoming", summaries.getValue("contact-a").lastMessage.getString("content"))
        assertEquals(1, summaries.getValue("contact-a").unreadCount)
        assertEquals("outgoing", summaries.getValue("contact-b").lastMessage.getString("content"))
        assertEquals(0, summaries.getValue("contact-b").unreadCount)
        database.close()
    }

    @Test
    fun markContactReadUpdatesEveryIncomingMessageWithoutLoadingHistory() {
        val database = database()
        assertTrue(database.upsert(message(51L, "contact-read", "first", isMine = false)))
        assertTrue(database.upsert(message(52L, "contact-read", "second", isMine = false)))
        assertTrue(database.upsert(message(53L, "contact-read", "mine", isMine = true)))
        val beforeVersion = database.updatedVersion()

        assertEquals(2, database.markContactRead("contact-read", readAtMillis = 9_999L))

        assertEquals(beforeVersion + 1L, database.updatedVersion())
        assertEquals(0, database.readContactSummaries().single().unreadCount)
        val stored = database.readContact("contact-read")
        assertTrue(stored.getJSONObject(0).getBoolean("isRead"))
        assertTrue(stored.getJSONObject(1).getBoolean("isRead"))
        assertFalse(stored.getJSONObject(2).getBoolean("isRead"))
        assertEquals(9_999L, stored.getJSONObject(0).getLong("readAt"))
        database.close()
    }

    @Test
    fun deletedContactCannotBeRestoredByStaleSnapshot() {
        val database = database()
        val first = message(11L, "contact-delete", "first")
        val second = message(12L, "contact-delete", "second")
        assertTrue(database.upsert(first))
        assertTrue(database.upsert(second))

        assertEquals(2, database.deleteContact("contact-delete"))

        val staleSnapshot = JSONObject()
            .put("contact-delete", JSONArray().put(first).put(second))
        assertFalse(database.mergeSnapshot(staleSnapshot))
        assertEquals(0, database.readContact("contact-delete").length())
        database.close()
    }

    @Test
    fun replacementPreservesEveryBackupMessageAndAdvancesIds() {
        val database = database()
        val root = JSONObject()
            .put(
                "contact-a",
                JSONArray()
                    .put(message(41L, "contact-a", "first"))
                    .put(message(42L, "contact-a", "second"))
            )
            .put("contact-b", JSONArray().put(message(90L, "contact-b", "third")))

        database.replaceAll(root)

        assertEquals(root.toString(), database.readAll().toString())
        assertEquals(91L, database.reserveMessageId())
        database.close()
    }

    private fun database(): ChatHistoryDatabase {
        val name = "signalasi_chat_history_test_${UUID.randomUUID()}.db"
        databaseNames += name
        return ChatHistoryDatabase(context, name)
    }

    private fun message(
        id: Long,
        contactId: String,
        content: String,
        isMine: Boolean = id % 2L == 0L,
        isSystem: Boolean = false,
        isRead: Boolean = false
    ): JSONObject =
        JSONObject()
            .put("id", id)
            .put("content", content)
            .put("isMine", isMine)
            .put("contactId", contactId)
            .put("isSystem", isSystem)
            .put("isRead", isRead)
            .put("readAt", if (isRead) id else 0L)
            .put("timestamp", id)
            .put("deliveryStatus", "")
            .put("deliveryTrace", JSONArray())
            .put("taskId", "")
            .put("taskStatus", "")
            .put("taskStatusSeq", 0L)
            .put("remoteMessageId", "")
}
