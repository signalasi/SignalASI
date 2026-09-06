package com.galaxyssi.chat.blob

import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/** The driver provisions a loopback TLS fixture and observes the intentional process death. */
@RunWith(AndroidJUnit4::class)
class BlobHttpsDeviceTest {
    @Test fun transferPhase() {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.containsKey("blobDeviceRun"))
        val run = requireNotNull(args.getString("blobDeviceRun"))
        require(run.matches(Regex("[a-f0-9]{32}")))
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(context.noBackupFilesDir, "blob-device-$run")
        val external = File(requireNotNull(context.getExternalFilesDir(null)), "blob-device-$run")
        if (args.getString("blobDevicePhase") == "cleanup") {
            check(!root.exists() || root.deleteRecursively())
            check(!external.exists() || external.deleteRecursively())
            return
        }
        BlobDeviceFixture(external).use { fixture ->
            val manifest = fixture.value.getJSONObject("manifest")
            val binding = BlobOutgoingContract.binding(manifest)
            val id = manifest.getString("transfer_id")
            val stagedDirectory = File(root, "sender")
            val client = BlobTransferClient(BlobHttp(fixture.origin, fixture.client), fixture.token)
            when (args.getString("blobDevicePhase")) {
                "interrupt" -> {
                    check(!root.exists()); check(root.mkdirs())
                    val badTls = runCatching {
                        BlobHttp(fixture.origin).json("GET", "/v1/blobs/${"0".repeat(32)}", fixture.token)
                    }.exceptionOrNull()
                    assertEquals("relay_tls_verification_failed", (badTls as? BlobFailure)?.code)
                    BlobDeviceMemory().use { memory ->
                        val started = SystemClock.elapsedRealtime()
                        BlobStaging.prepare(stagedDirectory, manifest.getLong("size_bytes"), manifest.getString("sha256"),
                            binding, { PatternInput(manifest.getLong("size_bytes")) }).use { staged ->
                            val prepared = SystemClock.elapsedRealtime() - started
                            BlobOutgoingJournal(File(root, "jobs.sqlite3")).use { journal ->
                                journal.register(id, JSONObject().put("manifest", manifest)
                                    .put("desktop_id", "device-test-desktop").put("fingerprint", "f".repeat(64)), active = true)
                                journal.claimDue(Long.MAX_VALUE, 1, emptySet()).single()
                                client.upload(staged, onOffer = { offer ->
                                    fixture.control("offer", BlobOutgoingContract.offerPayload(manifest, offer))
                                }, progress = { done, _ ->
                                    if (done >= BlobProtocol.CHUNK_BYTES) {
                                        fixture.event("interrupting", memory.snapshot().put("prepare_ms", prepared)
                                            .put("accepted_bytes", done).put("tls_rejection_verified", true))
                                        Process.killProcess(Process.myPid())
                                        error("Process death did not occur")
                                    }
                                })
                            }
                        }
                    }
                    fail("Interrupt phase unexpectedly returned")
                }
                "resume" -> BlobDeviceMemory().use { memory ->
                    BlobOutgoingJournal(File(root, "jobs.sqlite3")).use { journal ->
                        val recoveredAt = SystemClock.elapsedRealtime()
                        journal.recover()
                        val work = journal.claimDue(Long.MAX_VALUE, 1, emptySet()).single()
                        assertEquals(BlobOutgoingJournal.UPLOAD, work.phase)
                        val recoverMs = SystemClock.elapsedRealtime() - recoveredAt
                        val probes = Executors.newSingleThreadScheduledExecutor()
                        val controlMax = AtomicLong()
                        val uiMax = AtomicLong()
                        val probeCount = AtomicLong()
                        val probeFailures = AtomicLong()
                        probes.scheduleWithFixedDelay({
                            runCatching {
                                val start = SystemClock.elapsedRealtime()
                                fixture.control("status")
                                controlMax.accumulateAndGet(SystemClock.elapsedRealtime() - start, ::maxOf)
                                val latch = CountDownLatch(1)
                                val mainAt = SystemClock.elapsedRealtime()
                                Handler(Looper.getMainLooper()).post { latch.countDown() }
                                check(latch.await(5, TimeUnit.SECONDS))
                                uiMax.accumulateAndGet(SystemClock.elapsedRealtime() - mainAt, ::maxOf)
                                probeCount.incrementAndGet()
                            }.onFailure { probeFailures.incrementAndGet() }
                        }, 0, 100, TimeUnit.MILLISECONDS)
                        val started = SystemClock.elapsedRealtime()
                        try {
                            BlobStaging.open(stagedDirectory, binding).use { staged ->
                                client.upload(staged, onOffer = { offer ->
                                    fixture.control("offer", BlobOutgoingContract.offerPayload(manifest, offer))
                                })
                                val uploadedMs = SystemClock.elapsedRealtime() - started
                                var receipt: JSONObject? = null
                                val deadline = SystemClock.elapsedRealtime() + 120_000
                                while (receipt == null && SystemClock.elapsedRealtime() < deadline) {
                                    receipt = fixture.control("status").optJSONObject("receipt")
                                    if (receipt == null) Thread.sleep(100)
                                }
                                assertNotNull("Desktop receipt did not arrive", receipt)
                                assertTrue(BlobOutgoingContract.receiptMatches(manifest, receipt!!))
                                assertTrue(journal.stored(id, receipt, "device-test-desktop", "f".repeat(64)))
                                journal.defer(work, Long.MAX_VALUE, "late_upload_callback")
                                val cleanup = journal.claimDue(Long.MAX_VALUE, 1, emptySet()).single()
                                assertEquals(BlobOutgoingJournal.CLEANUP, cleanup.phase)
                                val returnOffer = fixture.value.getJSONObject("return_offer")
                                val returnBindingValue = fixture.value.getJSONObject("return_binding")
                                val returnBinding = returnBindingValue.keys().asSequence().associateWith(returnBindingValue::getString)
                                val downloadedAt = SystemClock.elapsedRealtime()
                                client.download(returnOffer, File(root, "receiver"), returnBinding).use { downloaded ->
                                    val digest = MessageDigest.getInstance("SHA-256")
                                    downloaded.copyPlaintext(object : OutputStream() {
                                        override fun write(value: Int) { digest.update(value.toByte()) }
                                        override fun write(bytes: ByteArray, offset: Int, length: Int) {
                                            assertTrue(length <= BlobProtocol.CHUNK_BYTES)
                                            digest.update(bytes, offset, length)
                                        }
                                    }, returnBinding)
                                    assertEquals(fixture.value.getString("return_sha256"), BlobProtocol.hex(digest.digest()))
                                }
                                val returnMs = SystemClock.elapsedRealtime() - downloadedAt
                                client.revoke(staged)
                                journal.finish(cleanup)
                                assertTrue(journal.claimDue(Long.MAX_VALUE, 1, emptySet()).isEmpty())
                                probes.shutdown(); check(probes.awaitTermination(20, TimeUnit.SECONDS))
                                fixture.event("completed", memory.snapshot().put("resume_checkpoint_ms", recoverMs)
                                    .put("remaining_upload_ms", uploadedMs).put("return_download_ms", returnMs)
                                    .put("control_probe_max_ms", controlMax.get()).put("main_callback_max_ms", uiMax.get())
                                    .put("control_probes", probeCount.get()).put("probe_failures", probeFailures.get()))
                                assertEquals(0L, probeFailures.get())
                                assertTrue(probeCount.get() > 0)
                            }
                        } finally {
                            probes.shutdownNow(); check(probes.awaitTermination(20, TimeUnit.SECONDS))
                        }
                    }
                }
                else -> error("Invalid device transfer phase")
            }
        }
    }

    private class PatternInput(private val size: Long) : InputStream() {
        private var position = 0L
        private val pattern = ByteArray(251) { it.toByte() }
        override fun read(): Int = if (position == size) -1 else ((position++ % 251).toInt())
        override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
            if (length == 0) return 0
            if (position == size) return -1
            val count = minOf(length.toLong(), size - position).toInt()
            var copied = 0
            while (copied < count) {
                val start = (position % 251).toInt()
                val width = minOf(251 - start, count - copied)
                System.arraycopy(pattern, start, bytes, offset + copied, width)
                copied += width; position += width
            }
            return count
        }
    }
}
