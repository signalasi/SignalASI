package com.galaxyssi.chat

import java.util.LinkedHashMap

internal object MqttPublishGuard {
    inline fun <T> attempt(operation: () -> T): Result<T> = runCatching(operation)
}

internal enum class MqttPublishResult(val accepted: Boolean) {
    PUBLISHED(true),
    QUEUED(true),
    FAILED(false)
}

internal object MqttOutboxDispatchPolicy {
    fun result(connected: Boolean, published: Boolean): MqttPublishResult =
        if (connected && published) MqttPublishResult.PUBLISHED else MqttPublishResult.QUEUED
}

internal object MqttBrokerAckTimeoutPolicy {
    const val DEFAULT_TIMEOUT_MILLIS = 12_000L
    const val ATTACHMENT_TIMEOUT_MILLIS = 30_000L

    fun forPayloadType(payloadType: String): Long =
        if (payloadType == "input_attachment_chunk") {
            ATTACHMENT_TIMEOUT_MILLIS
        } else {
            DEFAULT_TIMEOUT_MILLIS
        }

    fun normalize(timeoutMillis: Long): Long =
        if (timeoutMillis == ATTACHMENT_TIMEOUT_MILLIS) {
            ATTACHMENT_TIMEOUT_MILLIS
        } else {
            DEFAULT_TIMEOUT_MILLIS
        }
}

internal class MqttBrokerAckWatchdog(
    timeoutMillis: Long
) {
    private val defaultTimeoutMillis = timeoutMillis

    init {
        require(defaultTimeoutMillis > 0L)
    }

    private data class PendingPublish(
        val publishedAtMillis: Long,
        val timeoutMillis: Long
    )

    private val pendingByMessageId = LinkedHashMap<Int, PendingPublish>()

    @Synchronized
    fun onPublished(
        messageId: Int,
        nowElapsedMillis: Long,
        timeoutMillis: Long = defaultTimeoutMillis
    ) {
        require(timeoutMillis > 0L)
        pendingByMessageId.putIfAbsent(
            messageId,
            PendingPublish(nowElapsedMillis, timeoutMillis)
        )
    }

    @Synchronized
    fun onAcknowledged(messageId: Int) {
        pendingByMessageId.remove(messageId)
    }

    @Synchronized
    fun nextCheckDelayMillis(nowElapsedMillis: Long): Long? =
        pendingByMessageId.values.minOfOrNull { pending ->
            (pending.timeoutMillis - (nowElapsedMillis - pending.publishedAtMillis))
                .coerceAtLeast(0L)
        }

    @Synchronized
    fun oldestPendingAgeMillis(nowElapsedMillis: Long): Long? =
        pendingByMessageId.values.minOfOrNull(PendingPublish::publishedAtMillis)?.let { publishedAt ->
            (nowElapsedMillis - publishedAt).coerceAtLeast(0L)
        }

    @Synchronized
    fun oldestTimedOutPendingAgeMillis(nowElapsedMillis: Long): Long? =
        pendingByMessageId.values
            .filter { pending ->
                nowElapsedMillis - pending.publishedAtMillis >= pending.timeoutMillis
            }
            .minOfOrNull(PendingPublish::publishedAtMillis)
            ?.let { publishedAt -> (nowElapsedMillis - publishedAt).coerceAtLeast(0L) }

    @Synchronized
    fun pendingCount(): Int = pendingByMessageId.size

    @Synchronized
    fun clear() {
        pendingByMessageId.clear()
    }
}

internal class MqttBrokerDeliveryRegistration(
    private val maxCompletedMessageIds: Int = 256
) {
    private enum class State {
        ACKNOWLEDGED_BEFORE_REGISTRATION,
        REGISTERED,
        COMPLETED
    }

    init {
        require(maxCompletedMessageIds > 0)
    }

    private val states = LinkedHashMap<Int, State>()

    @Synchronized
    fun onPublished(messageId: Int): Boolean {
        val acknowledgedEarly = states[messageId] == State.ACKNOWLEDGED_BEFORE_REGISTRATION
        states[messageId] = State.REGISTERED
        trimCompleted()
        return acknowledgedEarly
    }

    @Synchronized
    fun onAcknowledged(messageId: Int): Boolean = when (states[messageId]) {
        State.REGISTERED -> {
            states[messageId] = State.COMPLETED
            trimCompleted()
            true
        }
        State.COMPLETED,
        State.ACKNOWLEDGED_BEFORE_REGISTRATION -> false
        null -> {
            states[messageId] = State.ACKNOWLEDGED_BEFORE_REGISTRATION
            false
        }
    }

    @Synchronized
    fun clear() {
        states.clear()
    }

    private fun trimCompleted() {
        var removeCount = states.values.count { it == State.COMPLETED } - maxCompletedMessageIds
        if (removeCount <= 0) return
        val iterator = states.entries.iterator()
        while (iterator.hasNext() && removeCount > 0) {
            if (iterator.next().value == State.COMPLETED) {
                iterator.remove()
                removeCount -= 1
            }
        }
    }
}

internal class MqttConnectionRetryPolicy(
    private val delaysMillis: LongArray = longArrayOf(2_000L, 5_000L, 10_000L, 20_000L, 30_000L)
) {
    init {
        require(delaysMillis.isNotEmpty())
        require(delaysMillis.all { it >= 0L })
    }

    private var attempt = 0

    @Synchronized
    fun nextDelayMillis(): Long {
        val delay = delaysMillis[attempt.coerceAtMost(delaysMillis.lastIndex)]
        attempt += 1
        return delay
    }

    @Synchronized
    fun reset() {
        attempt = 0
    }
}

internal enum class MqttSubscriptionAttemptOutcome {
    STALE,
    PENDING,
    READY,
    RETRY
}

internal class MqttSubscriptionRecoveryState {
    private var generation = 0
    private var remaining = 0
    private var failed = false
    private var ready = false

    @Synchronized
    fun isReady(): Boolean = ready

    @Synchronized
    fun begin(subscriptionCount: Int): Int {
        require(subscriptionCount > 0)
        generation += 1
        remaining = subscriptionCount
        failed = false
        ready = false
        return generation
    }

    @Synchronized
    fun complete(attemptGeneration: Int, succeeded: Boolean): MqttSubscriptionAttemptOutcome {
        if (attemptGeneration != generation || remaining <= 0) {
            return MqttSubscriptionAttemptOutcome.STALE
        }
        if (!succeeded) failed = true
        remaining -= 1
        if (remaining > 0) return MqttSubscriptionAttemptOutcome.PENDING
        ready = !failed
        return if (failed) MqttSubscriptionAttemptOutcome.RETRY else MqttSubscriptionAttemptOutcome.READY
    }

    @Synchronized
    fun invalidate() {
        generation += 1
        remaining = 0
        failed = false
        ready = false
    }
}

internal fun mqttInboundRouteScope(topic: String): String = topic
