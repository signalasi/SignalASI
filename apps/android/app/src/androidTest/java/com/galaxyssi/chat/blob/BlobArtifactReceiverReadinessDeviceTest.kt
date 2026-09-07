package com.galaxyssi.chat.blob

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

@RunWith(AndroidJUnit4::class)
class BlobArtifactReceiverReadinessDeviceTest {
    private fun fixture(block: (File, BlobArtifactReceiveCoordinator) -> Unit) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(context.noBackupFilesDir, "blob-readiness-test-${UUID.randomUUID()}").apply { mkdirs() }
        val journal = BlobArtifactReceiveJournal(File(root, "journal.sqlite3"))
        val pipeline = BlobArtifactReceivePipeline(File(root, "staging"), BlobArtifactStorage(File(root, "artifacts")),
            checkIdentity = {}, publish = { false }, sendReceipt = { _, _ -> false }, observeFailure = { _, _ -> false })
        val coordinator = BlobArtifactReceiveCoordinator(root, journal, pipeline)
        try { block(root, coordinator) } finally { coordinator.close(); root.deleteRecursively() }
    }

    private fun ready(coordinator: BlobArtifactReceiveCoordinator): Result<Unit> {
        val done = CountDownLatch(1)
        val result = AtomicReference<Result<Unit>>()
        coordinator.prepare { result.set(it); done.countDown() }
        assertTrue("Receiver preparation did not return", done.await(5, TimeUnit.SECONDS))
        return result.get()
    }

    @Test fun receiverIsReadyOnlyAfterAcquiringProcessOwnership() = fixture { root, receiver ->
        FileChannel.open(File(root, "owner.lock").toPath(), StandardOpenOption.CREATE, StandardOpenOption.WRITE).use { channel ->
            channel.lock().use { assertTrue(ready(receiver).isFailure) }
        }
        assertTrue(ready(receiver).isSuccess)
        assertTrue(ready(receiver).isSuccess)
    }

    @Test fun readinessDoesNotRunOnTheCallerOrMainThread() = fixture { _, receiver ->
        val done = CountDownLatch(1)
        val callbackThread = AtomicReference<Thread>()
        val caller = Thread.currentThread()
        receiver.prepare { callbackThread.set(Thread.currentThread()); done.countDown() }
        assertTrue(done.await(5, TimeUnit.SECONDS))
        assertNotSame(caller, callbackThread.get())
        assertNotSame(android.os.Looper.getMainLooper().thread, callbackThread.get())
    }

    @Test fun closedReceiverNeverAdvertisesReadiness() = fixture { _, receiver ->
        receiver.close()
        assertTrue(ready(receiver).isFailure)
    }
}
