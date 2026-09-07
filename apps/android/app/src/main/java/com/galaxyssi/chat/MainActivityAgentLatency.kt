package com.galaxyssi.chat

import android.widget.LinearLayout
import com.galaxyssi.chat.metrics.AgentLatencyTelemetry
import java.util.Locale

internal fun agentLatencyDisplayValue(milliseconds: Double?): String {
    if (milliseconds == null || !milliseconds.isFinite() || milliseconds < 0) return "-"
    val (divisor, unit) = when {
        milliseconds >= 86_400_000 -> 86_400_000.0 to "d"
        milliseconds >= 3_600_000 -> 3_600_000.0 to "h"
        milliseconds >= 60_000 -> 60_000.0 to "min"
        milliseconds >= 1_000 -> 1_000.0 to "s"
        else -> 1.0 to "ms"
    }
    return String.format(Locale.US, "%.1f %s", milliseconds / divisor, unit)
}

internal fun MainActivity.addAgentLatencySection() {
    addSectionTitle(getString(R.string.agent_latency_title))
    val rows = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    featureContent.addView(rows)
    var generation = 0
    fun refresh() {
        val request = ++generation
        rows.removeAllViews()
        rows.addView(featureValueRow(getString(R.string.agent_latency_loading), "", R.drawable.ic_settings_diagnostics, ""))
        agentRuntimeRecoveryExecutor.execute {
            val snapshot = AgentLatencyTelemetry.summary(this)
            runOnUiThread {
                if (isFinishing || isDestroyed || !rows.isAttachedToWindow || request != generation) return@runOnUiThread
                rows.removeAllViews()
                val labels = mapOf(
                    "phone_recovery_query_ms" to R.string.agent_latency_recovery_query,
                    "phone_recovery_page_ms" to R.string.agent_latency_recovery_page,
                    "phone_recovery_body_ms" to R.string.agent_latency_recovery_body,
                    "phone_recovery_checkpoint_ms" to R.string.agent_latency_recovery_checkpoint,
                    "phone_recovery_publish_ms" to R.string.agent_latency_recovery_publish,
                    "phone_transport_queue_ms" to R.string.agent_latency_transport_queue,
                    "phone_broker_ack_ms" to R.string.agent_latency_broker_ack,
                    "phone_peer_receipt_ms" to R.string.agent_latency_peer_receipt,
                    "phone_context_route_ms" to R.string.agent_latency_context,
                    "phone_send_prepare_ms" to R.string.agent_latency_total_prepare,
                    "phone_send_first_visible_ms" to R.string.agent_latency_user_first,
                    "phone_publish_prepare_ms" to R.string.agent_latency_prepare,
                    "phone_response_roundtrip_ms" to R.string.agent_latency_roundtrip,
                    "phone_connector_first_visible_ms" to R.string.agent_latency_first,
                    "phone_connector_complete_visible_ms" to R.string.agent_latency_final,
                    "phone_render_ms" to R.string.agent_latency_render
                )
                snapshot.first.forEach { (id, metric) ->
                    val label = labels[id] ?: return@forEach
                    rows.addView(featureValueRow(
                        getString(label),
                        getString(R.string.agent_latency_counts, metric.count, metric.incomplete, metric.unsuccessful),
                        R.drawable.ic_settings_diagnostics,
                        if (metric.count == 0) getString(R.string.agent_latency_empty) else getString(
                            R.string.agent_latency_values, agentLatencyDisplayValue(metric.p50Ms),
                            agentLatencyDisplayValue(metric.p95Ms), agentLatencyDisplayValue(metric.p99Ms)
                        ),
                        valueMaxLines = 3
                    ))
                }
                rows.addView(featureValueRow(
                    getString(R.string.agent_latency_recent),
                    getString(R.string.agent_latency_health, snapshot.second["dropped_events"].toString(),
                        snapshot.second["write_failures"].toString()),
                    R.drawable.ic_settings_diagnostics,
                    if (snapshot.second["loading"] == true) getString(R.string.agent_latency_loading)
                    else getString(R.string.agent_latency_refresh)
                ).apply { setOnClickListener { refresh() } })
            }
        }
    }
    refresh()
}
