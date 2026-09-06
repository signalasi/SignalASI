package com.galaxyssi.chat

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** Sleeps until a lifecycle event or the earliest persisted retry; idle queues do not poll. */
internal class AgentResultReceiptCoordinator(
    scope: CoroutineScope,
    private val drain: suspend () -> Long?,
    private val clock: () -> Long = System::currentTimeMillis,
    private val failed: (Exception) -> Unit = {}
) {
    private val events = Channel<Unit>(Channel.CONFLATED)
    @Volatile private var connected = false

    init {
        scope.launch {
            while (isActive) {
                if (!connected) { events.receive(); continue }
                val next = try { drain() }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (error: Exception) { failed(error); clock() + 30_000L }
                if (next == null || !connected) events.receive()
                else withTimeoutOrNull((next - clock()).coerceAtLeast(1L)) { events.receive() }
            }
        }
    }

    fun connectionChanged(value: Boolean) {
        if (value != connected) { connected = value; events.trySend(Unit) }
    }

    fun request(isConnected: Boolean) { connected = isConnected; events.trySend(Unit) }
}
