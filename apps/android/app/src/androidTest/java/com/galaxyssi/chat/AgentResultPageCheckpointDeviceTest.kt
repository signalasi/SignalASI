package com.galaxyssi.chat

import android.database.sqlite.SQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.Base64
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AgentResultPageCheckpointDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private fun body() = JSONObject().put("client_route_id", "checkpoint-test-route")
        .put("conversation_id", "checkpoint-test-conversation").put("task_id", "checkpoint-test-task")
        .put("turn_id", "checkpoint-test-turn").put("contact_id", "checkpoint-test-contact")
        .put("source_message_id", "92751").put("agent_id", "codex").put("execution_generation", 2)
        .put("type", "text").put("task_status", "completed")
        .put("content", "\u8fd9\u662f\u5b89\u5353\u52a0\u5bc6\u5206\u9875\u65ad\u70b9\u7eed\u4f20\u6d4b\u8bd5\u3002".repeat(2500))

    private fun page(request: JSONObject, answer: JSONObject = body()): JSONObject {
        val bytes = answer.toString().toByteArray(Charsets.UTF_8)
        val index = request.getInt("page_index")
        val chunk = bytes.copyOfRange(index * 16384, minOf(bytes.size, (index + 1) * 16384))
        return try {
            JSONObject(request.toString()).put("type", "agent_task_result_page").put("status", "ready")
                .put("sha256", AgentResultRecoveryClient.sha256(bytes)).put("total_bytes", bytes.size)
                .put("page_count", (bytes.size + 16383) / 16384)
                .put("page_sha256", AgentResultRecoveryClient.sha256(chunk))
                .put("data_b64", Base64.getEncoder().encodeToString(chunk))
        } finally { chunk.fill(0); bytes.fill(0) }
    }

    private suspend fun fetch(store: AgentResultPageDatabase, stopAt: Int = Int.MAX_VALUE,
        requests: MutableList<Int> = mutableListOf()): JSONObject? {
        val client = AgentResultRecoveryClient()
        return client.fetch("checkpoint-desktop", body(), checkpoint = store.checkpoint("checkpoint-desktop", body())) {
            val index = it.getInt("page_index")
            requests += index
            if (index == stopAt) false else client.receive(page(it), "checkpoint-desktop")
        }
    }

    private fun sql(name: String, command: String) {
        SQLiteDatabase.openDatabase(context.getDatabasePath(name).path, null, SQLiteDatabase.OPEN_READWRITE).use { it.execSQL(command) }
    }

    private fun count(name: String, table: String): Int = SQLiteDatabase.openDatabase(
        context.getDatabasePath(name).path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
        db.rawQuery("SELECT COUNT(*) FROM $table", null).use { it.moveToFirst(); it.getInt(0) }
    }

    @Test fun ordinaryRepliesDoNotScheduleCheckpointStorageWork() {
        val noStorage = object : android.content.ContextWrapper(null) {
            override fun getApplicationContext(): android.content.Context = error("Ordinary reply must return before context or IO access")
        }
        val response = AgentConnectorResponse(92751, "contact", "ordinary answer")
        AndroidAgentResultRecovery.acknowledge(noStorage, JSONObject().put("content", "ordinary answer"), response)
        AndroidAgentResultRecovery.acknowledge(noStorage,
            JSONObject().put("result_recovery", JSONObject().put("sha256", "invalid")), response)
    }

    @Test fun reopenRetainsValidatedPagesAndRequestsOnlyMissingOnes(): Unit = runBlocking {
        val name = "result-checkpoint-test-${UUID.randomUUID()}.db"
        try {
            AgentResultPageDatabase(context, name).use { assertNull(fetch(it, 2)) }
            assertEquals(2, count(name, "pages"))
            val requested = mutableListOf<Int>()
            AgentResultPageDatabase(context, name).use {
                assertEquals(body().getString("content"), fetch(it, requests = requested)!!.getString("content"))
            }
            assertEquals(2, requested.first())
            assertTrue(requested.all { it >= 2 })
        } finally { context.deleteDatabase(name) }
    }

    @Test fun completeCheckpointCanReassembleOfflineAfterReopen(): Unit = runBlocking {
        val name = "result-checkpoint-test-${UUID.randomUUID()}.db"
        try {
            AgentResultPageDatabase(context, name).use { assertNotNull(fetch(it)) }
            AgentResultPageDatabase(context, name).use { store ->
                val checkpoint = store.checkpoint("checkpoint-desktop", body())
                assertNotNull(AgentResultRecoveryClient().fetch("checkpoint-desktop", body(), checkpoint = checkpoint) {
                    error("A complete checkpoint must not publish")
                })
                assertNotNull(checkpoint.manifest())
            }
        } finally { context.deleteDatabase(name) }
    }

    @Test fun corruptedPageAloneIsRedownloaded(): Unit = runBlocking {
        val name = "result-checkpoint-test-${UUID.randomUUID()}.db"
        try {
            AgentResultPageDatabase(context, name).use { assertNotNull(fetch(it)) }
            sql(name, "UPDATE pages SET encrypted_page='corrupt' WHERE page_index=1")
            val requests = mutableListOf<Int>()
            AgentResultPageDatabase(context, name).use { assertNotNull(fetch(it, requests = requests)) }
            assertEquals(listOf(1), requests)
        } finally { context.deleteDatabase(name) }
    }

    @Test fun manifestAndBodyAreNeverPlaintextOnDisk(): Unit = runBlocking {
        val name = "result-checkpoint-test-${UUID.randomUUID()}.db"
        try {
            AgentResultPageDatabase(context, name).use { assertNotNull(fetch(it)) }
            context.getDatabasePath(name).parentFile!!.listFiles()!!.filter { it.name.startsWith(name) }.forEach { file ->
                val bytes = file.readBytes()
                try {
                    val text = String(bytes, Charsets.ISO_8859_1)
                    assertFalse(text.contains("checkpoint-test-conversation"))
                    assertFalse(text.contains("checkpoint-test-contact"))
                    assertFalse(text.contains("data_b64"))
                    val marker = body().getString("content").take(20).toByteArray(Charsets.UTF_8)
                    try { assertFalse(text.contains(String(marker, Charsets.ISO_8859_1))) } finally { marker.fill(0) }
                } finally { bytes.fill(0) }
            }
        } finally { context.deleteDatabase(name) }
    }

    @Test fun everyScopeDimensionAndGenerationIsIsolated(): Unit = runBlocking {
        val name = "result-checkpoint-test-${UUID.randomUUID()}.db"
        try {
            AgentResultPageDatabase(context, name).use { store ->
                assertNull(fetch(store, 1))
                val original = store.checkpoint("checkpoint-desktop", body()).manifest()!!
                for (field in AgentResultRecoveryClient.FIELDS + "execution_generation") {
                    val other = body().put(field, if (field == "execution_generation") 3 else "other")
                    val checkpoint = store.checkpoint("checkpoint-desktop", other)
                    assertNull(checkpoint.manifest())
                    checkpoint.clear(original.digest)
                }
                assertNull(store.checkpoint("other-desktop", body()).manifest())
                assertEquals(original, store.checkpoint("checkpoint-desktop", body()).manifest())
            }
        } finally { context.deleteDatabase(name) }
    }

    @Test fun onlyMatchingDigestCanClearCheckpointAndPages(): Unit = runBlocking {
        val name = "result-checkpoint-test-${UUID.randomUUID()}.db"
        try {
            AgentResultPageDatabase(context, name).use { store ->
                assertNull(fetch(store, 2))
                val checkpoint = store.checkpoint("checkpoint-desktop", body())
                checkpoint.clear("0".repeat(64))
                assertEquals(2, count(name, "pages"))
                checkpoint.clear(checkpoint.manifest()!!.digest)
                assertNull(checkpoint.manifest())
                assertEquals(0, count(name, "pages"))
            }
        } finally { context.deleteDatabase(name) }
    }

    @Test fun failedPageInsertRollsBackManifest(): Unit = runBlocking {
        val name = "result-checkpoint-test-${UUID.randomUUID()}.db"
        try {
            AgentResultPageDatabase(context, name).use { store ->
                val checkpoint = store.checkpoint("checkpoint-desktop", body())
                assertNull(checkpoint.manifest())
                sql(name, "CREATE TRIGGER fail_page BEFORE INSERT ON pages BEGIN SELECT RAISE(ABORT,'test disk failure'); END")
                val failed = runCatching { fetch(store) }
                assertTrue(failed.isFailure)
                assertNull(checkpoint.manifest())
                assertEquals(0, count(name, "pages"))
            }
        } finally { context.deleteDatabase(name) }
    }

    @Test fun unreadableManifestIsReplacedOnlyByValidatedWirePage(): Unit = runBlocking {
        val name = "result-checkpoint-test-${UUID.randomUUID()}.db"
        try {
            AgentResultPageDatabase(context, name).use { assertNull(fetch(it, 2)) }
            sql(name, "UPDATE manifests SET encrypted_manifest='corrupt'")
            val requests = mutableListOf<Int>()
            AgentResultPageDatabase(context, name).use { assertNotNull(fetch(it, requests = requests)) }
            assertEquals(0, requests.first())
        } finally { context.deleteDatabase(name) }
    }

    @Test fun inboxCommitPrecedesCheckpointDeletion(): Unit = runBlocking {
        val name = "result-checkpoint-test-${UUID.randomUUID()}.db"
        val inboxName = "checkpoint-inbox-test-${UUID.randomUUID()}.db"
        try {
            AgentResultPageDatabase(context, name).use { store ->
                val result = fetch(store)!!
                val checkpoint = store.checkpoint("checkpoint-desktop", body())
                val response = AgentRemoteOutcomeCodec.decode(result, result.getString("content"), "")!!
                AgentConnectorResponseInbox(context, inboxName, "legacy-$inboxName").use { inbox ->
                    assertFalse(inbox.wasRecorded(response))
                    assertNotNull(checkpoint.manifest())
                    assertTrue(inbox.append(response))
                    assertTrue(inbox.wasRecorded(response))
                    checkpoint.clear(result.getJSONObject("result_recovery").getString("sha256"))
                    assertNull(checkpoint.manifest())
                }
                AgentConnectorResponseInbox(context, inboxName, "legacy-$inboxName").use {
                    assertEquals(response.content, it.page().responses.single().content)
                }
            }
        } finally { context.deleteDatabase(name); context.deleteDatabase(inboxName); context.deleteSharedPreferences("legacy-$inboxName") }
    }

    @Test fun savePrefixForProcessDeath(): Unit = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("result_checkpoint_phase") == "save")
        val id = args.getString("result_checkpoint_id") ?: error("Test ID required")
        require(Regex("[a-zA-Z0-9-]{8,80}").matches(id))
        val name = "result-checkpoint-process-$id.db"
        AgentResultPageDatabase(context, name).use { assertNull(fetch(it, 2)) }
        assertEquals(2, count(name, "pages"))
    }

    @Test fun recoverPrefixAfterProcessDeath(): Unit = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("result_checkpoint_phase") == "recover")
        val id = args.getString("result_checkpoint_id") ?: error("Test ID required")
        require(Regex("[a-zA-Z0-9-]{8,80}").matches(id))
        val name = "result-checkpoint-process-$id.db"
        try {
            assertEquals(2, count(name, "pages"))
            val requests = mutableListOf<Int>()
            AgentResultPageDatabase(context, name).use { assertEquals(body().getString("content"),
                fetch(it, requests = requests)!!.getString("content")) }
            assertTrue(requests.isNotEmpty())
            assertEquals(2, requests.first())
            assertTrue(requests.all { it >= 2 })
        } finally { context.deleteDatabase(name) }
    }
}
