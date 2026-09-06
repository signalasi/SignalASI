package com.galaxyssi.chat.metrics

import java.util.UUID

/** Best-effort metadata only. This registry never owns delivery or retry decisions. */
internal class AgentTransportTiming(
    private val emit: (String, String, String, String, Long) -> Unit,
    private val nowNs: () -> Long = System::nanoTime,
    private val limit: Int = 1024,
    private val ttlNs: Long = 3_600_000_000_000L
) {
    private data class Message(val trace: String, val operation: String, val born: Long, var dispatched: Boolean = false)
    internal data class Attempt(val id: String, val trace: String, val born: Long)
    private val messages = LinkedHashMap<String, Message>()
    private val attempts = LinkedHashMap<String, Attempt>()

    private fun key(endpoint: String, message: String) = AgentLatencyContract.opaqueId("${endpoint.length}:$endpoint$message")
    private fun send(trace: String, stage: String, operation: String, outcome: String = "", at: Long = nowNs()) {
        runCatching { emit(trace, stage, operation, outcome, at) }
    }
    private fun prune(now: Long) {
        messages.entries.removeAll { now - it.value.born > ttlNs }
        attempts.entries.removeAll { now - it.value.born > ttlNs }
        while (messages.size >= limit.coerceAtLeast(1)) messages.remove(messages.keys.first())
        while (attempts.size >= limit.coerceAtLeast(1)) attempts.remove(attempts.keys.first())
    }

    @Synchronized fun queued(endpoint: String, messageId: String, taskId: String) {
        if (endpoint.isBlank() || messageId.isBlank() || taskId.isBlank()) return
        val key = key(endpoint, messageId)
        if (key in messages) return
        val now = nowNs()
        prune(now)
        val message = Message(AgentLatencyContract.opaqueId(taskId), key, now)
        messages[key] = message
        send(message.trace, "phone_transport_queued", key, at = now)
    }

    @Synchronized fun begin(endpoint: String, messageId: String): Attempt? {
        val now = nowNs()
        val message = messages[key(endpoint, messageId)] ?: return null
        if (now - message.born > ttlNs) { messages.remove(message.operation); return null }
        if (!message.dispatched) {
            message.dispatched = true
            send(message.trace, "phone_transport_dispatched", message.operation, at = now)
        }
        val attempt = Attempt(AgentLatencyContract.opaqueId(UUID.randomUUID().toString()), message.trace, now)
        if (attempts.size >= limit.coerceAtLeast(1)) attempts.remove(attempts.keys.first())
        attempts[attempt.id] = attempt
        send(attempt.trace, "phone_wire_started", attempt.id, at = now)
        return attempt
    }

    @Synchronized fun broker(attempt: Attempt?, outcome: String = "completed", at: Long = nowNs()) {
        if (attempt == null || attempts.remove(attempt.id) != attempt) return
        send(attempt.trace, "phone_broker_acked", attempt.id, outcome, at)
    }

    @Synchronized fun received(endpoint: String, messageId: String) {
        val message = messages.remove(key(endpoint, messageId)) ?: return
        if (nowNs() - message.born <= ttlNs) send(message.trace, "phone_peer_received", message.operation, "completed")
    }

    @Synchronized fun disconnected() {
        attempts.values.toList().forEach { broker(it, "cancelled") }
    }
}
