# Agent Request Stage Timings

Android and Desktop record `galaxyssi.agent-latency.v1` diagnostic points for
ordinary connector requests, independently of the existing voice session IDs.
ASR/QNN scheduling, TTS, routing decisions, execution permissions and the Link
wire protocol are unchanged. No diagnostic events are automatically sent to a
phone, Desktop, provider or analytics service.

## Identity and Clocks

- `trace_id`: SHA-256 of the existing task ID, never the prompt or conversation
  text. The normal authenticated route/conversation/task/turn checks still own
  delivery authorization; this hash is not an authorization mechanism.
- `clock_id`: random identifier for one tracer/process lifetime.
- `monotonic_ns`: the local monotonic clock at the observed boundary.
- `wall_clock_ms`: diagnostic ordering aid, never used to subtract durations.
- `operation_id`: hashed tool event ID for independent concurrent tool spans.
- `stage`, `provider`, `outcome`: constrained metadata. No text, tool arguments,
  URLs, filesystem paths, attachments, access tokens or cryptographic material.

Durations only join points sharing trace, clock and operation IDs. A phone's
clock is never subtracted from the Desktop clock. Process restarts cannot turn
old starts and new finishes into fabricated durations. Repeated stage updates
are idempotent within a bounded recent-event window.

Desktop Agent diagnostics use a shared `perf_counter_ns()` clock, including
explicit MQTT callback timestamps, transport ACKs and recovery spans. On the
Windows Python 3.11 runtime this uses QueryPerformanceCounter rather than the
coarser GetTickCount64 clock. The serialized `monotonic_ns` field remains a
monotonic timestamp; its name is not a promise of a particular Python function.
Summary `active_clock` metadata describes only the current tracer, not retained
events from earlier process clocks. Injected test clocks are labeled as such.
Watchdog deadlines, retry policies and the separate voice clock are unchanged.
See the [clock validation record](../performance/desktop-timing-clock-20260907.md).

## Observed Boundaries

| Metric | Start | End |
| --- | --- | --- |
| Phone context/routing | Agent page creates the user turn | First connector publish begins |
| Phone send preparation | Same user turn boundary | First connector request accepted into the local queue |
| Phone user first visible | Same user turn boundary | First associated assistant row completes a visible draw traversal |
| Phone publish preparation | Connector publish begins | Local queue accepts request |
| Phone response round trip | Local queue accepts request | Authenticated reply/visible partial reaches the phone |
| Phone connector first/final visible | Connector publish begins | Associated first/final assistant row is drawn |
| Phone render | Authenticated reply/visible partial received | First assistant row is drawn |
| Desktop inbound queue | Last required MQTT envelope enters callback | Signal decryption starts |
| Desktop decryption | Signal decryption starts | Application envelope is validated |
| Desktop preparation | MQTT envelope received | High-level task is durably created |
| Desktop task queue | Task created | Agent runner starts |
| Desktop first output | Agent runner starts | First output reported by the task manager |
| Desktop execution | Agent runner starts | Task enters a terminal state |
| Desktop finalization | First output | Task enters a terminal state |
| Desktop response enqueue | Terminal state | Final response publish/queue call returns |
| Desktop tool | Stable tool event starts | The same tool event finishes |
| Model submission | Codex turn submission starts | Submission call returns |
| Model first output | Codex turn submission starts | First task output is observed |

The phone user-turn timer is consumed by the first connector request only, not
reused for every later action in a long Agent loop. Historical/offscreen rows do
not create new first-visible samples. Identical final text keeps the existing
row rather than recreating it; its final draw can still be measured.

Queue acceptance is **not** a broker ACK or peer delivery. The response round
trip includes transport, Desktop execution and return transport; it is not
labeled network-only latency. Phone draw timing observes the View draw
traversal, not physical display scan-out. Waiting while the user leaves a page
can therefore extend send-to-visible measurements.

Provider-internal queue time is not exposed by these APIs. Model-first-output
includes provider queueing, processing and client delivery. Provider adapters
without a distinct submission callback leave that metric unmeasured rather
than manufacturing timestamps. Model output may arrive before the submission
call returns, so TTFT begins at call start, not at the submission ACK.

## Storage and UI

Both recorders retain at most 8,000 recent points, queue at most 1,024 pending
writes, and rotate approximately 2 MiB files with one previous file. These are
diagnostic retention limits, **not Agent action/turn limits**. Writes and initial
history loading run on a separate thread. Producers and snapshots never wait
for journal file I/O. Dropped writes, corrupt records and write failures are
reported. The journal is best effort, not the durable Run Kernel ledger.

- Android: app-private `noBackupFilesDir/diagnostics/agent_latency_v1.jsonl`.
  Agent stage timings appear below the existing voice performance dashboard.
  Computation is off the UI thread; refresh is explicit.
  Navigation: My Agent -> General -> Developer options -> Voice performance.
  P50/P95/P99 occupy three value lines, using compact ms/s/min/h/d display
  units for long-running tasks. The underlying metric values remain in ms.
- Desktop: `diagnostics/agent_latency_v1.jsonl` under the Desktop state root.
  The existing Performance lab includes a recent-stage table.
- Loopback API: `GET /api/agents/latency`; `include_events=true` adds the bounded
  content-free points for deliberate local analysis.

P50/P95/P99 use nearest-rank percentiles. Empty datasets display unavailable,
not zero. Failed/cancelled/timed-out stage completions are excluded from success
percentiles and counted separately; missing end points count as incomplete.
Stage tables describe bounded recent evidence, independently of the older
Performance lab's execution-log time-window/ranking controls.

## Verification and Remaining Scope

Unit tests cover task/clock isolation, exact percentiles, duplicate and reordered
events, failures, concurrent tools, corrupt/oversized/partial records, disk
backpressure, journal reopening, and real task-manager lifecycle integration.
The Desktop visual smoke verifies narrow and wide table layouts using clearly
synthetic rendering data. Android instrumented journal tests use isolated files
and report diagnostic producer overhead, not model/network latency.

The S26U blocked-writer test uses a four-entry test queue and 1,000 append
attempts. Dropping diagnostic writes under that deliberately stalled test is
expected; it does not drop chat messages, tasks, or Run Kernel events. Its
report is explicitly labeled `synthetic_workload` and must never enter the
production latency journal. Layout tests build a separate metric View, so
their sample percentile values cannot pollute production statistics either.

This is the connector timing foundation, not completion of the overall
performance objective. [Transport ACK latency](transport-ack-latency.md) adds
explicit broker/peer-receipt spans for task-associated durable envelopes.
Still required: complete real paired ACK acceptance,
blob/image stages, cloud/local provider and model-load boundaries, runtime
verification spans, cold-start/list/gesture frame measurements, actual provider
and S26U replay/chaos samples, and enforced real P95/P99 acceptance gates. No
chat latency or UI SLO is claimed from synthetic/unit-test evidence.

### Validation Record: 2026-09-05

- Base: `3f3b6a651` (latest main fetched before submission).
- Android candidate: 1.0.5 / 851; Desktop source: 1.0.5.
- Final Android APK, test APK and 16 targeted JVM tests: passed.
- Final APK audits: 72 Android AArch64 libraries passed 16 KB checks;
  all 24 expected QNN runtime libraries were present.
- Desktop source checks and 19 renderer regressions: passed.
- New backend timing tests: 19 passed. Timing plus the complete native-tool
  test module: 35 passed in a separate follow-up run.
- Full backend run: 1,219 passed, one Windows process-inventory host probe
  failed. That probe passed three immediate isolated retries and the later
  full native-tool module run. Its original failure remains recorded; this
  is not an all-green full-suite claim or a proven root-cause fix.
- Actual ASGI routing checked aggregate-only defaults, explicit event export,
  task-ID redaction and non-loopback rejection without starting MQTT.
- Desktop visual fixture checks passed at widths 360 and 640.
- S26U: the final APK and test APK were installed with `adb install -r`.
  All three instrumentation tests passed, including blocked-writer liveness,
  clock-domain separation after journal reopening, and percentile row layout.
  The 1,000-append blocked-writer fixture measured P50 0.073958 ms,
  P95 0.080052 ms, P99 0.084427 ms. These numbers measure diagnostic append
  overhead only, not real chat or model/network latency.
- S26U navigation to the new section and its empty state was verified. It
  exposed the one-line value limitation, fixed by an opt-in three-line value
  setting and compact duration units; all other rows default to one line.
- S26U final installation: 1.0.5 / 851, updated at 15:41:22 local time;
  the first installation timestamp remained unchanged. Only the test APK was
  subsequently uninstalled; the main app was reopened and its screen checked.
  No app data, models or pairings were reset. The currently running Desktop
  remains the verified 1.0.4 package: backend healthy, MQTT connected/ready,
  with all 16 expected subscriptions active and none pending or missing.
