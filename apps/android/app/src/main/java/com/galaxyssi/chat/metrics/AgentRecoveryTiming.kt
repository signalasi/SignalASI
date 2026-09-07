package com.galaxyssi.chat.metrics

import java.util.UUID

/** Independent attempts, including retries. Diagnostic failure never changes recovery. */
internal class AgentRecoveryTiming(
    private val emit: (String, String, String, String, Long) -> Unit,
    private val nowNs: () -> Long = System::nanoTime
) {
    fun begin(taskId: String, phase: String): Span {
        if (taskId.isBlank() || phase !in PHASES) return Span { }
        return try {
            val trace = AgentLatencyContract.opaqueId(taskId)
            val operation = AgentLatencyContract.opaqueId(UUID.randomUUID().toString())
            fun record(boundary: String, outcome: String) {
                runCatching { emit(trace, "phone_recovery_${phase}_$boundary", operation, outcome, nowNs()) }
            }
            record("started", "")
            Span { outcome -> record("finished", outcome) }
        } catch (_: Exception) { Span { } }
    }

    class Span internal constructor(private val finish: (String) -> Unit) : AutoCloseable {
        var outcome = "failed"
        private var closed = false
        @Synchronized override fun close() {
            if (closed) return
            closed = true
            runCatching { finish(outcome) }
        }
    }

    companion object {
        private val PHASES = setOf("query", "page", "body", "checkpoint", "publish")
    }
}
