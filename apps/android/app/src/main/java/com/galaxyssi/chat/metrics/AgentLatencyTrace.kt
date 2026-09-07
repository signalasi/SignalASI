package com.galaxyssi.chat.metrics

import java.security.MessageDigest
import java.util.UUID
import kotlin.math.ceil

internal data class AgentTimingPoint(
    val traceId: String,
    val clockId: String,
    val stage: String,
    val monotonicNs: Long,
    val wallClockMs: Long,
    val operationId: String = "",
    val provider: String = "",
    val outcome: String = ""
)

internal data class AgentLatencyMetric(
    val count: Int, val incomplete: Int, val unsuccessful: Int,
    val p50Ms: Double?, val p95Ms: Double?, val p99Ms: Double?
)

internal object AgentLatencyContract {
    const val SCHEMA = "galaxyssi.agent-latency.v1"
    const val EVENT_LIMIT = 8_000
    val pairs = linkedMapOf(
        "phone_recovery_query_ms" to ("phone_recovery_query_started" to "phone_recovery_query_finished"),
        "phone_recovery_page_ms" to ("phone_recovery_page_started" to "phone_recovery_page_finished"),
        "phone_recovery_body_ms" to ("phone_recovery_body_started" to "phone_recovery_body_finished"),
        "phone_recovery_checkpoint_ms" to ("phone_recovery_checkpoint_started" to "phone_recovery_checkpoint_finished"),
        "phone_recovery_publish_ms" to ("phone_recovery_publish_started" to "phone_recovery_publish_finished"),
        "phone_transport_queue_ms" to ("phone_transport_queued" to "phone_transport_dispatched"),
        "phone_broker_ack_ms" to ("phone_wire_started" to "phone_broker_acked"),
        "phone_peer_receipt_ms" to ("phone_transport_queued" to "phone_peer_received"),
        "phone_context_route_ms" to ("phone_send_started" to "phone_publish_started"),
        "phone_send_prepare_ms" to ("phone_send_started" to "phone_request_queued"),
        "phone_send_first_visible_ms" to ("phone_send_started" to "phone_first_output_visible"),
        "phone_publish_prepare_ms" to ("phone_publish_started" to "phone_request_queued"),
        "phone_response_roundtrip_ms" to ("phone_request_queued" to "phone_response_received"),
        "phone_connector_first_visible_ms" to ("phone_publish_started" to "phone_first_output_visible"),
        "phone_connector_complete_visible_ms" to ("phone_publish_started" to "phone_final_output_visible"),
        "phone_render_ms" to ("phone_response_received" to "phone_first_output_visible"),
        "phone_final_consume_wait_ms" to ("phone_final_received" to "phone_final_consume_started"),
        "phone_final_accept_ms" to ("phone_final_consume_started" to "phone_final_accepted"),
        "phone_final_finalize_ms" to ("phone_final_accepted" to "phone_finalized"),
        "phone_final_checkpoint_ms" to ("phone_finalized" to "phone_final_checkpointed"),
        "phone_final_ui_queue_ms" to ("phone_final_checkpointed" to "phone_final_ui_started"),
        "phone_final_ui_prepare_ms" to ("phone_final_ui_started" to "phone_transcript_queued"),
        "phone_transcript_queue_ms" to ("phone_transcript_queued" to "phone_transcript_started"),
        "phone_transcript_write_ms" to ("phone_transcript_started" to "phone_transcript_persisted"),
        "phone_transcript_draw_ms" to ("phone_transcript_persisted" to "phone_final_output_visible"),
        "phone_final_delivery_ui_ms" to ("phone_final_received" to "phone_final_output_visible")
    )
    val stages = pairs.values.flatMap { listOf(it.first, it.second) }.toSet() + "phone_final_received"
    val outcomes = setOf("", "completed", "failed", "cancelled", "timed_out")
    private val hashPattern = Regex("[a-f0-9]{64}")
    private val clockPattern = Regex("[a-f0-9]{32}")
    private val providerPattern = Regex("[A-Za-z0-9._:-]{0,96}")

    fun opaqueId(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { byte -> HEX[(byte.toInt() and 255) ushr 4].toString() + HEX[byte.toInt() and 15] }

    fun valid(point: AgentTimingPoint): Boolean =
        hashPattern.matches(point.traceId) && clockPattern.matches(point.clockId) &&
            point.stage in stages && point.outcome in outcomes &&
            (point.operationId.isEmpty() || hashPattern.matches(point.operationId)) &&
            providerPattern.matches(point.provider) && point.monotonicNs >= 0 && point.wallClockMs >= 0

    fun summarize(points: List<AgentTimingPoint>): Map<String, AgentLatencyMetric> {
        val groups = points.filter(::valid).groupBy { Triple(it.traceId, it.clockId, it.operationId) }
        return pairs.mapValues { (_, stages) ->
            var incomplete = 0
            var unsuccessful = 0
            val samples = groups.values.mapNotNull { group ->
                val start = group.filter { it.stage == stages.first }.minOfOrNull { it.monotonicNs }
                    ?: return@mapNotNull null
                val end = group.filter { it.stage == stages.second && it.monotonicNs >= start }
                    .minByOrNull { it.monotonicNs }
                when {
                    end == null -> { incomplete++; null }
                    end.outcome in setOf("failed", "cancelled", "timed_out") -> { unsuccessful++; null }
                    else -> (end.monotonicNs - start) / 1_000_000.0
                }
            }.sorted()
            fun percentile(p: Double): Double? = if (samples.isEmpty()) null else samples[ceil(p * samples.size).toInt() - 1]
            AgentLatencyMetric(samples.size, incomplete, unsuccessful, percentile(.5), percentile(.95), percentile(.99))
        }
    }

    private const val HEX = "0123456789abcdef"
}

internal class AgentLatencyTurnStarts {
    private val starts = LinkedHashMap<String, Long>()
    @Synchronized fun begin(turnId: String, atNs: Long) {
        if (turnId.isBlank()) return
        starts.putIfAbsent(AgentLatencyContract.opaqueId(turnId), atNs)
        // Diagnostic retention only; this never limits Agent turns or actions.
        if (starts.size > AgentLatencyContract.EVENT_LIMIT) starts.remove(starts.keys.first())
    }
    @Synchronized fun take(turnId: String): Long? = starts.remove(AgentLatencyContract.opaqueId(turnId))
}

internal interface AgentTimingSink {
    fun append(point: AgentTimingPoint)
    fun snapshot(): List<AgentTimingPoint>
}

internal class AgentLatencyTracer(
    private val sink: AgentTimingSink,
    private val monotonicNs: () -> Long = System::nanoTime,
    private val wallClockMs: () -> Long = System::currentTimeMillis,
    private val clockId: String = UUID.randomUUID().toString().replace("-", "")
) {
    private val seen = LinkedHashSet<Triple<String, String, String>>()
    private val finalOutcomes = LinkedHashMap<String, String>()

    fun record(taskId: String, stage: String, outcome: String = "", atNs: Long? = null) {
        if (taskId.isBlank() || stage !in AgentLatencyContract.stages) return
        recordOpaque(AgentLatencyContract.opaqueId(taskId), stage, "", outcome, atNs)
    }

    fun recordOpaque(trace: String, stage: String, operation: String, outcome: String = "", atNs: Long? = null) {
        if (stage !in AgentLatencyContract.stages) return
        synchronized(seen) {
            if (!seen.add(Triple(trace, stage, operation))) return
            if (seen.size > AgentLatencyContract.EVENT_LIMIT) seen.remove(seen.first())
            if (stage == "phone_final_received") {
                finalOutcomes[trace] = outcome.takeIf { it in setOf("failed", "cancelled", "timed_out") } ?: "completed"
                if (finalOutcomes.size > AgentLatencyContract.EVENT_LIMIT) finalOutcomes.remove(finalOutcomes.keys.first())
            }
        }
        sink.append(AgentTimingPoint(
            trace, clockId, stage, (atNs ?: monotonicNs()).coerceAtLeast(0), wallClockMs().coerceAtLeast(0),
            operationId = operation,
            outcome = outcome.takeIf { it in AgentLatencyContract.outcomes }.orEmpty()
        ))
    }

    fun hasFinalResponse(taskId: String): Boolean = synchronized(seen) {
        taskId.isNotBlank() && Triple(AgentLatencyContract.opaqueId(taskId), "phone_final_received", "") in seen
    }

    fun finalOutcome(taskId: String): String = synchronized(seen) {
        finalOutcomes[AgentLatencyContract.opaqueId(taskId)].orEmpty()
    }

    fun shouldObserve(taskId: String, final: Boolean): Boolean {
        if (taskId.isBlank()) return false
        val trace = AgentLatencyContract.opaqueId(taskId)
        return synchronized(seen) {
            Triple(trace, "phone_publish_started", "") in seen &&
                Triple(trace, "phone_response_received", "") in seen &&
                (Triple(trace, "phone_first_output_visible", "") !in seen ||
                    (final && Triple(trace, "phone_final_received", "") in seen &&
                        Triple(trace, "phone_final_output_visible", "") !in seen))
        }
    }

    fun visible(taskId: String, final: Boolean) {
        if (!shouldObserve(taskId, final)) return
        val finalReceived = synchronized(seen) {
            Triple(AgentLatencyContract.opaqueId(taskId), "phone_final_received", "") in seen
        }
        val outcome = synchronized(seen) { finalOutcomes[AgentLatencyContract.opaqueId(taskId)] ?: "completed" }
        record(taskId, "phone_first_output_visible", outcome = if (final && finalReceived) outcome else "")
        if (final && finalReceived) record(taskId, "phone_final_output_visible", outcome = outcome)
    }
}
