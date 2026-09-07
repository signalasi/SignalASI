# Desktop Diagnostic Clock Validation

## Scope

Desktop 1.0.33 moves Agent diagnostic timestamps to one shared
`time.perf_counter_ns()` source. This includes tracer defaults, transport timing,
recovery timing and explicit inbound/publish callback timestamps. Mixing these
origins would produce invalid durations, so the change covers all producers in
this clock domain. Each process retains its own clock identity.

No message protocol, retry deadline, watchdog policy, Android code, ASR/QNN
behavior or voice timing domain changes. The running Desktop was not replaced
for this validation. Existing journal events are not rewritten.

## Windows Clock Experiment

Command: `python tools/dev/measure-desktop-timing-clock.py --samples 100`.
The experiment does not start the backend or read production state. Both clocks
surround the same requested 1 ms sleep. The Windows Python 3.11 runtime reported:

| Observation | Previous clock | Shared diagnostic clock |
| --- | --- | --- |
| Implementation | GetTickCount64 | QueryPerformanceCounter |
| Reported resolution | 15,625,000 ns | 100 ns |
| Zero samples | 89 / 100 | 0 / 100 |
| Negative samples | 0 / 100 | 0 / 100 |
| P50 | 0 ms | 1.8012 ms |
| P95 | 16 ms | 1.9764 ms |
| P99 | 16 ms | 2.0418 ms |

Sleep scheduling explains why observed sleeps exceed 1 ms. These numbers
demonstrate measurement quantization, not faster Agent execution, recovery,
network delivery or compliance with application latency targets. Reported clock
resolution is not a guarantee of measurement accuracy.

## Regression Evidence

- 145 backend tests and 88 subtests passed with an independent temporary
  `GALAXYSSI_STATE_DIR`, covering timing, MQTT callback integration, recovery,
  result archives/receipts, terminal outcomes, routing, probes and voice timing.
- Eight new clock tests cover sub-millisecond spans, shared callback origins,
  injected-clock metadata, process isolation and coarse-clock producer guards.
- Desktop `npm run check`: 29 tests passed and structure checks passed.
- `node tools/dev/check-repo.js`: passed.
- Initial static-guard tests incorrectly matched the injected tracer method and
  did not account for a source BOM; both test defects were corrected before the
  successful regression run.

This is not a new real-device performance acceptance run. The previous paired
S20U recovery evidence remains separate; no phone data or pairing was changed.
