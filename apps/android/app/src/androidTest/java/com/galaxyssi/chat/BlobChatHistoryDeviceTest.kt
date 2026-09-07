package com.galaxyssi.chat

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class BlobChatHistoryDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private fun fixture(block: (String, ChatHistoryDatabase) -> Unit) {
        val name = "blob-chat-history-${UUID.randomUUID()}.db"
        val db = ChatHistoryDatabase(context, name)
        try { block(name, db) } finally { db.close(); context.deleteDatabase(name) }
    }

    private fun event(type: String = "artifact_available") = JSONObject()
        .put("type", type).put("blob_publication", true).put("peer_chat", true)
        .put("desktop_id", "desktop").put("contact_id", "desktop").put("source_message_id", "remote-message")
        .put("transfer_id", "a".repeat(64)).put("artifact_uri", "galaxyssi-artifact://blob/" + "b".repeat(64))
        .put("sha256", "c".repeat(64)).put("size_bytes", 1234L).put("name", "private-document.pdf")

    private fun message(id: Long = 1L, contact: String = "desktop") = JSONObject()
        .put("id", id).put("contactId", contact).put("remoteMessageId", "remote-message")
        .put("timestamp", 1000L + id).put("content", "original message").put("isRead", true)
        .put("isMine", false).put("isSystem", false).put("readAt", 1500L)
        .put("attachments", JSONArray().put(JSONObject().put("name", "private-document.pdf")
            .put("artifact_uri", "galaxyssi-artifact://blob/" + "b".repeat(64)).put("transfer_id", "a".repeat(64))
            .put("sha256", "c".repeat(64)).put("size_bytes", 1234L).put("transfer_state", "downloading")
            .put("transfer_progress", 17)))

    private fun state(db: ChatHistoryDatabase, id: Long = 1L) = db.findMessage(id)!!
        .getJSONArray("attachments").getJSONObject(0).getString("transfer_state")
    private fun count(db: ChatHistoryDatabase): Long = db.readableDatabase.rawQuery(
        "SELECT COUNT(*) FROM ${BlobChatAttachmentEvents.TABLE}", null).use { it.moveToFirst(); it.getLong(0) }

    @Test fun completionSurvivesDatabaseCloseAndColdReopen() = fixture { name, db ->
        db.upsert(message())
        val sequence = db.page("desktop").messages.single().getLong(ChatHistoryDatabase.HISTORY_SEQUENCE)
        assertTrue(db.applyBlobAttachmentEvent(event()))
        db.close()
        ChatHistoryDatabase(context, name).use { reopened ->
            assertEquals("complete", state(reopened))
            val page = reopened.page("desktop").messages.single()
            assertEquals(sequence, page.getLong(ChatHistoryDatabase.HISTORY_SEQUENCE))
            assertEquals("original message", page.getString("content"))
            assertTrue(page.getBoolean("isRead"))
            assertEquals(1L, count(reopened))
        }
    }

    @Test fun terminalEventBeforeCardIsAppliedWhenTheCardArrives() = fixture { _, db ->
        assertFalse(db.applyBlobAttachmentEvent(event()))
        assertTrue(db.page("desktop").messages.isEmpty())
        db.upsert(message())
        assertEquals("complete", state(db))
    }

    @Test fun lateCardCanReadTerminalEventWithoutInsertingAChatRowOrChangingVersion() = fixture { _, db ->
        db.applyBlobAttachmentEvent(event())
        val version = db.updatedVersion()
        val found = db.matchingBlobAttachmentEvents(message()).single()
        assertEquals("artifact_available", found.getString("type"))
        assertEquals(version, db.updatedVersion())
        assertTrue(db.page("desktop").messages.isEmpty())
    }

    @Test fun hydrationRejectsOtherContactsMessagesAndAttachmentIdentities() = fixture { _, db ->
        db.applyBlobAttachmentEvent(event())
        val wrongMessages = listOf(message(contact = "other"), message().put("isMine", true),
            message().put("remoteMessageId", "other-message"))
        wrongMessages.forEach { assertTrue(db.matchingBlobAttachmentEvents(it).isEmpty()) }
        mapOf("transfer_id" to "d".repeat(64), "sha256" to "d".repeat(64),
            "artifact_uri" to "galaxyssi-artifact://blob/" + "d".repeat(64), "size_bytes" to 999L)
            .forEach { (key, value) ->
                val wrong = message()
                wrong.getJSONArray("attachments").getJSONObject(0).put(key, value)
                assertTrue(key, db.matchingBlobAttachmentEvents(wrong).isEmpty())
            }
    }

    @Test fun hydrationSurvivesReopenAndDoesNotDuplicateEventsForRepeatedAttachments() = fixture { name, db ->
        db.applyBlobAttachmentEvent(event("artifact_download_failed"))
        db.close()
        ChatHistoryDatabase(context, name).use { reopened ->
            val incoming = message()
            incoming.getJSONArray("attachments").put(incoming.getJSONArray("attachments").getJSONObject(0))
            assertEquals("artifact_download_failed", reopened.matchingBlobAttachmentEvents(incoming)
                .single().getString("type"))
            reopened.applyBlobAttachmentEvent(event())
            assertEquals("artifact_available", reopened.matchingBlobAttachmentEvents(incoming)
                .single().getString("type"))
        }
    }

    @Test fun hydrationCannotOverwriteAlreadyCompletedCardsAndProgressIsNotHydrated() = fixture { _, db ->
        db.applyBlobAttachmentEvent(event("artifact_blob_progress"))
        assertTrue(db.matchingBlobAttachmentEvents(message()).isEmpty())
        db.applyBlobAttachmentEvent(event("artifact_download_failed"))
        val complete = message()
        complete.getJSONArray("attachments").getJSONObject(0).put("transfer_state", "complete")
        assertTrue(db.matchingBlobAttachmentEvents(complete).isEmpty())
    }

    @Test fun failedTransferAlsoSurvivesReopenAndLaterCompletion() = fixture { name, db ->
        db.upsert(message())
        assertTrue(db.applyBlobAttachmentEvent(event("artifact_download_failed")))
        db.close()
        ChatHistoryDatabase(context, name).use { reopened ->
            assertEquals("failed", state(reopened))
            assertTrue(reopened.applyBlobAttachmentEvent(event()))
            assertEquals("complete", state(reopened))
        }
    }

    @Test fun staleMemoryWritesAndLateFailureCannotUndoCompletion() = fixture { _, db ->
        db.upsert(message())
        db.applyBlobAttachmentEvent(event())
        val version = db.updatedVersion()
        assertFalse(db.applyBlobAttachmentEvent(event()))
        assertEquals(version, db.updatedVersion())
        db.upsert(message())
        db.applyBlobAttachmentEvent(event("artifact_download_failed"))
        assertEquals("complete", state(db))
        assertEquals(1L, count(db))
    }

    @Test fun progressNeverWritesTerminalProjectionOrChangesDatabaseVersion() = fixture { _, db ->
        db.upsert(message())
        val version = db.updatedVersion()
        repeat(100) { db.applyBlobAttachmentEvent(event("artifact_blob_progress").put("progress", it)) }
        assertEquals(version, db.updatedVersion())
        assertEquals(0L, count(db))
        assertEquals("downloading", state(db))
    }

    @Test fun identicalRemoteIdsInOtherContactsAndOutboundMessagesAreUntouched() = fixture { _, db ->
        db.upsert(message())
        db.upsert(message(2, "other"))
        db.upsert(message(3).put("isMine", true))
        db.applyBlobAttachmentEvent(event())
        assertEquals("complete", state(db))
        assertEquals("downloading", state(db, 2))
        assertEquals("downloading", state(db, 3))
    }

    @Test fun deletionRemovesProjectionAndLateEventsDoNotResurrectMessages() = fixture { _, db ->
        db.upsert(message())
        db.applyBlobAttachmentEvent(event())
        assertTrue(db.deleteMessage(1))
        assertEquals(0L, count(db))
        db.applyBlobAttachmentEvent(event())
        assertFalse(db.upsert(message()))
        assertNull(db.findMessage(1))
        assertTrue(db.page("desktop").messages.isEmpty())
    }

    @Test fun clearingOrReplacingHistoryClearsProjectionData() = fixture { _, db ->
        db.applyBlobAttachmentEvent(event())
        db.deleteContact("desktop")
        assertEquals(0L, count(db))
        db.applyBlobAttachmentEvent(event())
        db.clear()
        assertEquals(0L, count(db))
        db.applyBlobAttachmentEvent(event())
        db.replaceAll(JSONObject())
        assertEquals(0L, count(db))
    }

    @Test fun migrationFromV4PreservesExistingMessagesAndAddsOnlyProjectionTable() = fixture { name, db ->
        db.upsert(message())
        db.writableDatabase.execSQL("DROP TABLE ${BlobChatAttachmentEvents.TABLE}")
        db.writableDatabase.version = 4
        db.close()
        ChatHistoryDatabase(context, name).use { upgraded ->
            upgraded.readableDatabase.rawQuery(
                "SELECT metadata_value FROM chat_metadata WHERE metadata_key='legacy_rows_migrated'", null
            ).use { cursor -> assertTrue(cursor.moveToFirst()); assertEquals(1L, cursor.getLong(0)) }
            assertEquals("original message", upgraded.findMessage(1)!!.getString("content"))
            assertEquals(0L, count(upgraded))
            assertTrue(upgraded.applyBlobAttachmentEvent(event()))
            assertEquals("complete", state(upgraded))
        }
    }

    @Test fun missingMigrationMetadataIsInitializedWithoutReplacingMessages() = fixture { name, db ->
        db.upsert(message())
        db.writableDatabase.execSQL("DROP TABLE ${BlobChatAttachmentEvents.TABLE}")
        db.writableDatabase.execSQL("DELETE FROM chat_metadata WHERE metadata_key='legacy_rows_migrated'")
        db.writableDatabase.version = 4
        db.close()
        ChatHistoryDatabase(context, name).use { upgraded ->
            assertEquals("original message", upgraded.findMessage(1)!!.getString("content"))
            assertTrue(upgraded.applyBlobAttachmentEvent(event()))
            assertEquals("complete", state(upgraded))
        }
    }

    @Test fun plainMessagesAndLegacyAttachmentsNeverQueryBlobProjection() = fixture { _, db ->
        db.writableDatabase.execSQL("DROP TABLE ${BlobChatAttachmentEvents.TABLE}")
        assertTrue(db.upsert(message().put("attachments", JSONArray())))
        val legacy = message(2).put("remoteMessageId", "legacy-message")
        legacy.getJSONArray("attachments").getJSONObject(0).put("artifact_uri", "content://legacy/file")
        assertTrue(db.upsert(legacy))
        assertTrue(db.matchingBlobAttachmentEvents(message().put("attachments", JSONArray())).isEmpty())
        assertTrue(db.matchingBlobAttachmentEvents(legacy).isEmpty())
        assertEquals(2, db.page("desktop").messages.size)
    }

    @Test fun checkpointIsEncryptedAndMalformedAuxiliaryDataDoesNotBreakMessages() = fixture { _, db ->
        db.applyBlobAttachmentEvent(event())
        db.readableDatabase.rawQuery("SELECT payload FROM ${BlobChatAttachmentEvents.TABLE}", null).use {
            assertTrue(it.moveToFirst())
            assertFalse(it.getString(0).contains("private-document"))
        }
        db.writableDatabase.execSQL("UPDATE ${BlobChatAttachmentEvents.TABLE} SET payload='broken'")
        assertTrue(db.upsert(message()))
        assertEquals("original message", db.findMessage(1)!!.getString("content"))
        db.applyBlobAttachmentEvent(event())
        assertEquals("complete", state(db))
    }
}
