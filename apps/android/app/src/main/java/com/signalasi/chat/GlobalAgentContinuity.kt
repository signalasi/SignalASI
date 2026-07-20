package com.signalasi.chat

data class GlobalEventProcessingFailure(
    val eventId: String,
    val attemptCount: Int,
    val firstFailedAtMillis: Long,
    val lastFailedAtMillis: Long,
    val nextAttemptAtMillis: Long,
    val errorFingerprint: String,
    val reason: String,
    val quarantined: Boolean = false
)

data class GlobalDeadLetterEvent(
    val event: GlobalConversationEvent,
    val failure: GlobalEventProcessingFailure,
    val quarantinedAtMillis: Long
)

data class GlobalEventQueueState(
    val ready: List<GlobalConversationEvent> = emptyList(),
    val overflow: List<GlobalConversationEvent> = emptyList()
)

data class GlobalEventQueueMutation(
    val state: GlobalEventQueueState,
    val acceptedCount: Int,
    val capacityRejected: List<GlobalConversationEvent> = emptyList()
)

object GlobalEventQueuePolicy {
    fun enqueue(
        state: GlobalEventQueueState,
        incoming: List<GlobalConversationEvent>,
        deadLetterEventIds: Set<String> = emptySet(),
        readyCapacity: Int = DEFAULT_READY_CAPACITY,
        overflowCapacity: Int = DEFAULT_OVERFLOW_CAPACITY
    ): GlobalEventQueueMutation {
        require(readyCapacity > 0)
        require(overflowCapacity >= 0)
        val knownIds = (state.ready.asSequence() + state.overflow.asSequence())
            .map(GlobalConversationEvent::id)
            .filter(String::isNotBlank)
            .toMutableSet()
            .apply { addAll(deadLetterEventIds) }
        val additions = incoming.asSequence()
            .filter { it.id.isNotBlank() && knownIds.add(it.id) }
            .toList()
        if (additions.isEmpty()) return GlobalEventQueueMutation(state, 0)

        val readyRoom = (readyCapacity - state.ready.size).coerceAtLeast(0)
        val readyAdditions = additions.take(readyRoom)
        val remaining = additions.drop(readyAdditions.size)
        val overflowRoom = (overflowCapacity - state.overflow.size).coerceAtLeast(0)
        val overflowAdditions = remaining.take(overflowRoom)
        val rejected = remaining.drop(overflowAdditions.size)
        return GlobalEventQueueMutation(
            state = GlobalEventQueueState(
                ready = state.ready + readyAdditions,
                overflow = state.overflow + overflowAdditions
            ),
            acceptedCount = additions.size,
            capacityRejected = rejected
        )
    }

    fun removeAndPromote(
        state: GlobalEventQueueState,
        removedEventIds: Set<String>,
        readyCapacity: Int = DEFAULT_READY_CAPACITY
    ): GlobalEventQueueState {
        require(readyCapacity > 0)
        if (removedEventIds.isEmpty() && state.ready.size >= readyCapacity) return state
        val remainingReady = state.ready.filterNot { it.id in removedEventIds }.take(readyCapacity)
        val remainingOverflow = state.overflow.filterNot { it.id in removedEventIds }
        val promoteCount = (readyCapacity - remainingReady.size).coerceAtLeast(0)
        return GlobalEventQueueState(
            ready = remainingReady + remainingOverflow.take(promoteCount),
            overflow = remainingOverflow.drop(promoteCount)
        )
    }

    const val DEFAULT_READY_CAPACITY = 2_000
    const val DEFAULT_OVERFLOW_CAPACITY = 8_000
}

object GlobalEventRetryPolicy {
    fun recordFailure(
        eventId: String,
        previous: GlobalEventProcessingFailure?,
        error: Throwable,
        nowMillis: Long = System.currentTimeMillis()
    ): GlobalEventProcessingFailure {
        val attempt = (previous?.attemptCount ?: 0) + 1
        val reason = naturalReason(error)
        val quarantined = attempt >= MAX_ATTEMPTS
        return GlobalEventProcessingFailure(
            eventId = eventId,
            attemptCount = attempt,
            firstFailedAtMillis = previous?.firstFailedAtMillis?.takeIf { it > 0L } ?: nowMillis,
            lastFailedAtMillis = nowMillis,
            nextAttemptAtMillis = if (quarantined) 0L else nowMillis + retryDelayMillis(attempt),
            errorFingerprint = GlobalAgentText.stableKey(error.javaClass.name, reason),
            reason = reason,
            quarantined = quarantined
        )
    }

    fun capacityFailure(eventId: String, nowMillis: Long = System.currentTimeMillis()): GlobalEventProcessingFailure =
        GlobalEventProcessingFailure(
            eventId = eventId,
            attemptCount = 0,
            firstFailedAtMillis = nowMillis,
            lastFailedAtMillis = nowMillis,
            nextAttemptAtMillis = 0L,
            errorFingerprint = GlobalAgentText.stableKey("event_queue_capacity", eventId),
            reason = "The durable Agent event queue reached its safety capacity",
            quarantined = true
        )

    fun eligible(failure: GlobalEventProcessingFailure?, nowMillis: Long): Boolean =
        failure == null || (!failure.quarantined && failure.nextAttemptAtMillis <= nowMillis)

    fun retryDelayMillis(attemptCount: Int): Long = when (attemptCount.coerceAtLeast(1)) {
        1 -> 30_000L
        2 -> 2L * 60L * 1_000L
        else -> 10L * 60L * 1_000L
    }

    private fun naturalReason(error: Throwable): String = error.message
        ?.replace(Regex("\\s+"), " ")
        ?.take(500)
        ?.ifBlank { null }
        ?: error.javaClass.simpleName.ifBlank { "Event processing failed" }

    const val MAX_ATTEMPTS = 3
}
