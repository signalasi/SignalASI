package com.galaxyssi.chat

import java.util.Base64
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class AgentResultPageCheckpointTest {
    private class Checkpoint : AgentResultPageCheckpoint {
        var descriptor: AgentResultPageManifest? = null
        val pages = mutableMapOf<Int, ByteArray>()
        var writes = 0
        var allowWrite = true
        override fun manifest() = descriptor
        override fun read(manifest: AgentResultPageManifest, page: Int) = pages[page]?.copyOf()
        override fun write(manifest: AgentResultPageManifest, page: Int, bytes: ByteArray): Boolean {
            if (!allowWrite || (descriptor != null && descriptor != manifest)) return false
            descriptor = manifest; pages[page] = bytes.copyOf(); writes++; return true
        }
        override fun clear(digest: String) {
            if (descriptor?.digest == digest) {
                pages.values.forEach { it.fill(0) }; pages.clear(); descriptor = null
            }
        }
    }

    private fun body(content: String = "\u65ad\u70b9\u6062\u590d\u6d4b\u8bd5".repeat(6000)) = JSONObject()
        .put("client_route_id", "route").put("conversation_id", "conversation").put("task_id", "task")
        .put("turn_id", "turn").put("contact_id", "contact").put("source_message_id", "42")
        .put("agent_id", "codex").put("execution_generation", 2)
        .put("type", "text").put("task_status", "completed").put("content", content)

    private fun page(request: JSONObject, answer: JSONObject): JSONObject {
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

    @Test fun interruptedTransferResumesOnlyMissingPages(): Unit = runBlocking {
        val checkpoint = Checkpoint()
        val first = AgentResultRecoveryClient()
        assertNull(first.fetch("desktop", body(), checkpoint = checkpoint) {
            if (it.getInt("page_index") == 2) false else first.receive(page(it, body()), "desktop")
        })
        assertEquals(setOf(0, 1), checkpoint.pages.keys)
        val requested = mutableListOf<Int>()
        val second = AgentResultRecoveryClient()
        val result = second.fetch("desktop", body(), checkpoint = checkpoint) {
            requested += it.getInt("page_index")
            assertEquals(checkpoint.descriptor!!.digest, it.getString("sha256"))
            second.receive(page(it, body()), "desktop")
        }
        assertEquals(body().getString("content"), result!!.getString("content"))
        assertEquals(2, requested.first())
        assertTrue(requested.all { it >= 2 })
    }

    @Test fun completeCheckpointReopensWithoutNetworkAndIsNotPrematurelyCleared(): Unit = runBlocking {
        val checkpoint = Checkpoint()
        val first = AgentResultRecoveryClient()
        assertNotNull(first.fetch("desktop", body(), checkpoint = checkpoint) { first.receive(page(it, body()), "desktop") })
        assertNotNull(checkpoint.manifest())
        val result = AgentResultRecoveryClient().fetch("desktop", body(), checkpoint = checkpoint) { error("Must not redownload") }
        assertEquals(body().getString("content"), result!!.getString("content"))
        assertNotNull(checkpoint.manifest())
    }

    @Test fun aMissingMiddlePageIsFetchedWithoutRepeatingOtherPages(): Unit = runBlocking {
        val checkpoint = Checkpoint()
        val first = AgentResultRecoveryClient()
        first.fetch("desktop", body(), checkpoint = checkpoint) { first.receive(page(it, body()), "desktop") }
        checkpoint.pages.remove(1)!!.fill(0)
        val second = AgentResultRecoveryClient()
        val requested = mutableListOf<Int>()
        assertNotNull(second.fetch("desktop", body(), checkpoint = checkpoint) {
            requested += it.getInt("page_index"); second.receive(page(it, body()), "desktop")
        })
        assertEquals(listOf(1), requested)
    }

    @Test fun invalidWirePageNeverCreatesCheckpoint(): Unit = runBlocking {
        for (field in listOf("sha256", "page_sha256", "data_b64")) {
            val checkpoint = Checkpoint()
            val client = AgentResultRecoveryClient()
            assertNull(client.fetch("desktop", body(), checkpoint = checkpoint) {
                client.receive(page(it, body()).put(field, "invalid"), "desktop")
            })
            assertEquals(0, checkpoint.writes)
        }
    }

    @Test fun manifestCannotChangeAfterRestart(): Unit = runBlocking {
        val checkpoint = Checkpoint()
        val first = AgentResultRecoveryClient()
        first.fetch("desktop", body(), checkpoint = checkpoint) {
            if (it.getInt("page_index") > 0) false else first.receive(page(it, body()), "desktop")
        }
        val digest = checkpoint.descriptor!!.digest
        val second = AgentResultRecoveryClient()
        assertNull(second.fetch("desktop", body(), checkpoint = checkpoint) {
            second.receive(page(it, body("a".repeat(100000))), "desktop")
        })
        assertEquals(digest, checkpoint.descriptor!!.digest)
        assertEquals(setOf(0), checkpoint.pages.keys)
    }

    @Test fun fullDigestIsRecheckedForCachedPages(): Unit = runBlocking {
        val checkpoint = Checkpoint()
        val client = AgentResultRecoveryClient()
        client.fetch("desktop", body(), checkpoint = checkpoint) { client.receive(page(it, body()), "desktop") }
        checkpoint.pages[1]!![0] = 0
        assertNull(AgentResultRecoveryClient().fetch("desktop", body(), checkpoint = checkpoint) { error("No network expected") })
        assertNull(checkpoint.manifest())
    }

    @Test fun persistenceFailureDoesNotReturnOrAcknowledgeAnswer(): Unit = runBlocking {
        val checkpoint = Checkpoint().apply { allowWrite = false }
        val client = AgentResultRecoveryClient()
        assertNull(client.fetch("desktop", body(), checkpoint = checkpoint) { client.receive(page(it, body()), "desktop") })
        assertEquals(0, checkpoint.writes)
        assertEquals(0, client.pendingCount)
    }

    @Test fun cancellationPreservesPagesAndOldNonceCannotSatisfyRestart(): Unit = runBlocking {
        val checkpoint = Checkpoint()
        val client = AgentResultRecoveryClient()
        lateinit var stale: JSONObject
        val transfer = async(start = CoroutineStart.UNDISPATCHED) {
            client.fetch("desktop", body(), checkpoint = checkpoint) {
                if (it.getInt("page_index") == 0) client.receive(page(it, body()), "desktop")
                else { stale = it; true }
            }
        }
        transfer.cancel(); transfer.join()
        assertEquals(setOf(0), checkpoint.pages.keys)
        assertFalse(client.receive(page(stale, body()), "desktop"))
        assertNotNull(client.fetch("desktop", body(), checkpoint = checkpoint) { client.receive(page(it, body()), "desktop") })
    }

    @Test fun supersededExecutionDoesNotWriteAnInFlightPage(): Unit = runBlocking {
        val checkpoint = Checkpoint()
        val client = AgentResultRecoveryClient()
        var eligible = true
        assertNull(client.fetch("desktop", body(), checkpoint = checkpoint, stillPending = { eligible }) {
            client.receive(page(it, body()), "desktop"); eligible = false; true
        })
        assertEquals(0, checkpoint.writes)
    }

    @Test fun unknownOrPrivateScopeNeverReadsCheckpoint(): Unit = runBlocking {
        val checkpoint = Checkpoint()
        assertNull(AgentResultRecoveryClient().fetch("desktop", body().put("conversation_id", "self-evolution:test"),
            checkpoint = checkpoint) { error("Private context must not leave the phone") })
        assertEquals(0, checkpoint.writes)
    }
}
