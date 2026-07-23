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
    fun retainsAndPagesEveryMessageBeyondLegacyCountLimit() {
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

    private fun message(id: Long, contactId: String, content: String): JSONObject =
        JSONObject()
            .put("id", id)
            .put("content", content)
            .put("isMine", id % 2L == 0L)
            .put("contactId", contactId)
            .put("isSystem", false)
            .put("timestamp", id)
            .put("deliveryStatus", "")
            .put("deliveryTrace", JSONArray())
            .put("taskId", "")
            .put("taskStatus", "")
            .put("taskStatusSeq", 0L)
            .put("remoteMessageId", "")
}
