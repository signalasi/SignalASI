# Integrated recovery-stage verification on S20U

## Build and environment

This is a **local integration test**, not a main-only release acceptance.
The base was main `b74564fb5`; the tested integration commit `f9fbfa07c` includes:

- PR #2845: background outcome generation/status preservation.
- PR #2846: Desktop recovery timing.
- PR #2847: video delivery and Android fullscreen presentation.
- PR #2848: Android recovery timing.

Only version conflicts were resolved: Android 1.0.36 (880), Desktop 1.0.32.
No feature was removed to make the merge pass. Tests operated only on S20U
SM-G9880 (`R5CN319CESA`). The connected SM-T575 was not operated on.
Before the test, MemAvailable was 5,072,280 KiB. The installed App's original
first-install timestamp, 2026-09-07 00:28:13, stayed unchanged.

Desktop was gracefully stopped and restarted from the integration worktree.
Its health endpoint reported encrypted transport ready, sidecar ready, and all
10 subscriptions acknowledged; Codex was prewarmed. No pairing was reset.
All backend unit tests selected a fresh `GALAXYSSI_STATE_DIR` before imports,
independent of this real runtime.

## Checks

- Debug, instrumentation and Release APK builds passed (13-minute Gradle run).
- 65 JVM tests passed: recovery clients/timing/latency, outcome codec and late
  response policy; no failures or skips.
- 63 backend recovery tests and 71 subtests passed in isolated storage.
- Desktop `npm.cmd run check`: 29 tests and structure checks passed.
- Both Debug and Release APKs passed the 16 KiB audit for 72 AArch64 libraries.
- S20U `BackgroundAgentOutcomeDeviceTest`: all 10 cases passed (0.709 seconds).
- The timing report parser passed syntax, exact-duration and clock-isolation
  fixture checks, then successfully read both real test cases.

## Real recovery runs

The existing four-phase test submitted a Chinese request to the paired Codex,
deliberately dropped only that test's final body before inbox/UI delivery, and
force-stopped the App between phases. Readiness-driven production recovery then
fetched the archived answer without explicit query calls or model resubmission.
It checked exact content, digest, generation/sequence, original conversation,
single transcript entry, Activity recreation and another cold start.

| Measurement | Case 1788759774935 | Case 1788759895137 |
| --- | ---: | ---: |
| Four test phases | 4/4 passed | 4/4 passed |
| Connect to request/reply readiness | 2,096 ms | 2,046 ms |
| Ready to exact body in encrypted inbox | 5,636 ms | 7,135 ms |
| Combined connect and inbox recovery | 7,732 ms | 9,181 ms |
| Separate cold UI first visible reply | 1,713 ms | 1,718 ms |
| Subsequent cold start visible reply | 1,153 ms | 1,218 ms |
| Assistant entries in original conversation | 1 | 1 |

The cold UI measurement is a **separate process phase**, not part of the inbox
timer. It must not be described as one continuous end-to-end startup percentile.

## Measured recovery operations

| Phone operation | Case 1788759774935 | Case 1788759895137 |
| --- | ---: | ---: |
| Initial status query round trip | 3,330.474 ms | 3,192.365 ms |
| Additional query overlapping body fetch | Incomplete | 2,328.009 ms |
| Result page round trip | 1,798.915 ms | 3,445.100 ms |
| Complete body fetch and validation | 1,990.545 ms | 3,653.400 ms |
| Encrypted page write | 17.592 ms | 24.132 ms |
| Response-bus publish call | 56.484 ms | 44.398 ms |

Body duration includes page and checkpoint work. These nested durations are not
additive. The first case's additional query lacked a recorded completion before
process termination; it remains incomplete, not a successful low-latency sample.
The second case proves a repeated status query overlaps the body transfer.
Its actual causal contribution to the longer page wait is not established by
two observations alone.

Desktop archive reads recorded 15 ms in each run and response publish calls
recorded 94-438 ms. However, this runtime uses Python 3.11's `GetTickCount64()`
for `time.monotonic_ns()`; `time.get_clock_info('monotonic')` reports 0.015625 s
resolution. Zero/15 ms samples are clock quantization, **not proof of zero or
precise 15 ms computation**. The same runtime's performance counter uses
`QueryPerformanceCounter()` with reported 0.0000001 s resolution. A follow-up
must migrate tracing consistently, including caller-supplied timestamps, rather
than mix clock origins in one trace domain.

## Reproduction and evidence

Run on the authorized S20U with the integrated Debug APK and instrumentation APK:

```powershell
./tools/dev/test-android-live-final-recovery.ps1 -Serial R5CN319CESA
./tools/dev/report-android-live-recovery-timing.ps1 -Serial R5CN319CESA -Source <printed-source>
```

The report reads existing bounded diagnostics only and filters the SHA-256 task
identifier for that case. It never reads message bodies or initializes a task
runtime store. It pairs only same-clock, same-operation start/end events and
fails clearly when either side has no recovery samples. It does not subtract
phone and Desktop timestamps. Retention/rotation can omit older samples.

Local evidence is under `build/live-final-1788759774935` and
`build/live-final-1788759895137`: four phase logs, metrics and `stage-timings.json`.
The second directory also contains `visible-release.png`, inspected after
restoring Release: the original user prompt and one exact recovered answer were
visible. The temporary phone screenshot was removed after collection.

## Remaining acceptance

Two successful short-reply cases do not establish P95/P99, large-body recovery,
broker chaos coverage or the overall reliability goal. The five-second full
recovery target is not met by these samples. Next work is to avoid redundant
discovery during an active body fetch and improve Desktop tracing clock
precision, then repeat equivalent live tests before claiming improvement.

After testing, S20U was restored to non-debug Release 1.0.36 (880), the test-only
package was removed and MainActivity was opened. The main App was never
uninstalled or cleared. Desktop 1.0.32 remains running from the integrated tree.
