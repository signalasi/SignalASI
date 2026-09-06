package com.galaxyssi.chat.blob

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Invoked in two separate instrumentation processes around am force-stop. */
@RunWith(AndroidJUnit4::class)
class BlobOutgoingRestartDeviceTest {
    @Test fun persistOrRecover() {
        val arguments = InstrumentationRegistry.getArguments()
        assumeTrue(arguments.containsKey("blobRestartId"))
        val name = requireNotNull(arguments.getString("blobRestartId"))
        require(name.matches(Regex("blob-outgoing-restart-[a-z0-9-]{1,64}")))
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(context.noBackupFilesDir, name)
        val path = File(root, "jobs.sqlite3")
        val id = "a".repeat(64)
        when (arguments.getString("blobRestartPhase")) {
            "persist" -> {
                check(!root.exists()); check(root.mkdirs())
                val body = JSONObject().put("desktop_id", "restart-test-desktop").put("fingerprint", "f".repeat(64))
                    .put("manifest", JSONObject().put("client_route_id", "route").put("conversation_id", "重启恢复测试")
                        .put("task_id", "task").put("turn_id", "turn").put("attachment_id", "attachment")
                        .put("transfer_id", id).put("contact_id", "contact").put("sha256", "b".repeat(64))
                        .put("size_bytes", 123))
                BlobOutgoingJournal(path).use {
                    it.register(id, body, active = true)
                    it.claimDue(10, 1, emptySet()).single()
                    val receipt = JSONObject(body.getJSONObject("manifest").toString()).put("status", "stored")
                    assertTrue(it.stored(id, receipt, "restart-test-desktop", "f".repeat(64)))
                    assertEquals(BlobOutgoingJournal.CLEANUP, it.claimDue(11, 1, emptySet()).single().phase)
                }
            }
            "recover" -> {
                check(path.exists())
                try {
                    val started = android.os.SystemClock.elapsedRealtime()
                    BlobOutgoingJournal(path).use {
                        it.recover()
                        val work = it.claimDue(12, 1, emptySet()).single()
                        assertEquals(BlobOutgoingJournal.CLEANUP, work.phase)
                        assertEquals("重启恢复测试", work.body.getJSONObject("manifest").getString("conversation_id"))
                        assertEquals("stored", work.body.getJSONObject("receipt").getString("status"))
                        it.finish(work)
                        assertTrue(it.claimDue(Long.MAX_VALUE, 1, emptySet()).isEmpty())
                    }
                    val elapsed = android.os.SystemClock.elapsedRealtime() - started
                    android.util.Log.i("BlobRestartTest", "checkpoint_recovery_ms=$elapsed")
                    assertTrue("Checkpoint recovery took $elapsed ms", elapsed < 5_000)
                } finally { check(root.deleteRecursively()) }
            }
            else -> error("Unknown test phase")
        }
    }
}
