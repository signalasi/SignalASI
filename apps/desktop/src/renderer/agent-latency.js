(function (root) {
  const labels = {
    desktop_transport_queue_ms: 'Transport queue to dispatch',
    desktop_broker_ack_ms: 'Wire send to broker confirmation',
    desktop_peer_receipt_ms: 'Queued to encrypted peer receipt',
    desktop_prepare_ms: 'Receive to task creation',
    desktop_receive_queue_ms: 'Inbound queue',
    desktop_decrypt_ms: 'Decrypt and validate',
    desktop_queue_ms: 'Task queue',
    desktop_first_output_ms: 'Execution to first output',
    desktop_execution_ms: 'Execution to completion',
    desktop_finalize_ms: 'First output to completion',
    desktop_response_enqueue_ms: 'Completion to response queued',
    desktop_tool_ms: 'Tool execution',
    desktop_model_submit_ms: 'Model submission',
    desktop_model_first_output_ms: 'Model request to first output',
  };
  function render(report, { escapeHtml, t = (value) => value } = {}) {
    const escape = escapeHtml;
    const metrics = report?.metrics || {};
    const number = (value) => typeof value === 'number' && Number.isFinite(value) && value >= 0
      ? value.toLocaleString('en-US', { maximumFractionDigits: 1 }) : '-';
    const rows = Object.entries(labels).map(([id, label]) => {
      const metric = metrics[id] || {};
      return `<tr><th scope="row">${escape(t(label))}<small>${escape(
        `${t('Samples')} ${number(metric.count)} / ${t('Incomplete')} ${number(metric.incomplete)} / ${t('Failed')} ${number(metric.unsuccessful)}`
      )}</small></th>${['p50_ms', 'p95_ms', 'p99_ms'].map((key) => `<td>${number(metric[key])}</td>`).join('')}</tr>`;
    }).join('');
    return `<h3>${escape(t('Recent stage timings'))}</h3>
      <div class="agent-latency-scroll"><table class="agent-latency-table"><thead><tr><th>${escape(t('Stage'))}</th><th>P50 ms</th><th>P95 ms</th><th>P99 ms</th></tr></thead><tbody>${rows}</tbody></table></div>
      <p class="performance-evidence-note">${report?.loading ? escape(t('Loading measurements')) : ''}
      ${escape(t('Dropped'))}: ${number(report?.dropped_events)} / ${escape(t('Write failures'))}: ${number(report?.write_failures)}</p>`;
  }
  root.GalaxySSIAgentLatency = { render };
  if (typeof module !== 'undefined') module.exports = { render };
})(globalThis);
