package com.galaxyssi.chat

import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.galaxyssi.chat.metrics.*
import org.eclipse.paho.client.mqttv3.*
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.DataInputStream
import java.io.File
import java.net.ServerSocket
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

@RunWith(AndroidJUnit4::class)
class AgentTransportTimingDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun realPahoPubackUsesAttemptContextAndDoesNotImplyPeerReceipt() {
        withJournal { journal ->
            val tracer = AgentLatencyTracer(journal, SystemClock::elapsedRealtimeNanos)
            val timing = AgentTransportTiming({ trace, stage, op, outcome, at ->
                tracer.recordOpaque(trace, stage, op, outcome, at)
            }, SystemClock::elapsedRealtimeNanos)
            val published = CountDownLatch(1)
            val allowAck = CountDownLatch(1)
            val receivedAck = CountDownLatch(1)
            val finish = CountDownLatch(1)
            val failure = AtomicReference<Throwable?>()
            ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1")).use { server ->
                server.soTimeout = 10_000
                val worker = thread(name = "test-local-puback") {
                    runCatching {
                        server.accept().use { socket ->
                            socket.soTimeout = 10_000
                            val input = DataInputStream(socket.getInputStream())
                            fun packet(): Pair<Int, ByteArray> {
                                val header = input.readUnsignedByte()
                                var size = 0; var multiplier = 1
                                do {
                                    val digit = input.readUnsignedByte()
                                    size += (digit and 127) * multiplier
                                    multiplier *= 128
                                    require(multiplier <= 268_435_456 && size <= 4096)
                                } while (digit and 128 != 0)
                                return header to ByteArray(size).also(input::readFully)
                            }
                            assertEquals(1, packet().first ushr 4)
                            socket.getOutputStream().write(byteArrayOf(0x20, 2, 0, 0))
                            socket.getOutputStream().flush()
                            val (header, body) = packet()
                            assertEquals(3, header ushr 4)
                            val topicLength = (body[0].toInt() and 255) * 256 + (body[1].toInt() and 255)
                            published.countDown()
                            assertTrue(allowAck.await(10, TimeUnit.SECONDS))
                            socket.getOutputStream().write(byteArrayOf(0x40, 2, body[topicLength + 2], body[topicLength + 3]))
                            socket.getOutputStream().flush()
                            finish.await(10, TimeUnit.SECONDS)
                        }
                    }.onFailure { failure.set(it) }
                }
                val client = MqttAsyncClient("tcp://127.0.0.1:${server.localPort}", "timing-${UUID.randomUUID()}", MemoryPersistence())
                try {
                    client.setCallback(object : MqttCallback {
                        override fun connectionLost(cause: Throwable?) = Unit
                        override fun messageArrived(topic: String?, message: MqttMessage?) = Unit
                        override fun deliveryComplete(token: IMqttDeliveryToken?) {
                            timing.broker(token?.userContext as? AgentTransportTiming.Attempt)
                            receivedAck.countDown()
                        }
                    })
                    client.connect().waitForCompletion(10_000)
                    timing.queued("test-peer", "message", "test-task")
                    val attempt = timing.begin("test-peer", "message")!!
                    val token = client.publish("isolated-test", MqttMessage(byteArrayOf(1, 2, 3)).apply { qos = 1 }, attempt, null)
                    assertTrue(published.await(10, TimeUnit.SECONDS))
                    assertFalse(token.isComplete)
                    assertEquals(0, AgentLatencyContract.summarize(journal.snapshot()).getValue("phone_broker_ack_ms").count)
                    allowAck.countDown()
                    assertTrue(receivedAck.await(10, TimeUnit.SECONDS))
                    val metrics = AgentLatencyContract.summarize(journal.snapshot())
                    assertEquals(1, metrics.getValue("phone_broker_ack_ms").count)
                    assertEquals(0, metrics.getValue("phone_peer_receipt_ms").count)
                    timing.received("wrong-peer", "message")
                    timing.received("test-peer", "message")
                    assertEquals(1, AgentLatencyContract.summarize(journal.snapshot()).getValue("phone_peer_receipt_ms").count)
                } finally {
                    allowAck.countDown(); finish.countDown()
                    runCatching { client.disconnectForcibly(0, 0, false) }
                    runCatching { client.close(true) }
                    worker.join(10_000)
                }
                failure.get()?.let { throw AssertionError("local broker failed", it) }
                assertFalse(worker.isAlive)
            }
        }
    }

    @Test fun concurrentAttemptsRemainDistinctInActualAndroidJournal() {
        withJournal { journal ->
            val tracer = AgentLatencyTracer(journal, SystemClock::elapsedRealtimeNanos)
            val timing = AgentTransportTiming({ trace, stage, op, outcome, at ->
                tracer.recordOpaque(trace, stage, op, outcome, at)
            }, SystemClock::elapsedRealtimeNanos)
            val workers = (0 until 4).map { worker -> thread {
                repeat(20) { index ->
                    val id = "message-$worker-$index"
                    timing.queued("peer", id, "task-$worker")
                    timing.broker(timing.begin("peer", id))
                    timing.received("peer", id)
                }
            } }
            workers.forEach { it.join(10_000); assertFalse(it.isAlive) }
            journal.close()
            val metrics = AgentLatencyContract.summarize(journal.snapshot())
            assertEquals(80, metrics.getValue("phone_broker_ack_ms").count)
            assertEquals(80, metrics.getValue("phone_peer_receipt_ms").count)
            assertTrue(journal.snapshot().all(AgentLatencyContract::valid))
        }
    }

    private fun withJournal(block: (AgentTimingJournal) -> Unit) {
        val directory = File(context.cacheDir, "transport-timing-test-${UUID.randomUUID()}").apply { mkdirs() }
        try { AgentTimingJournal(File(directory, "isolated.jsonl")).use(block) }
        finally { directory.deleteRecursively() }
    }
}
