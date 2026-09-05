# Android terminal outcome recovery verification

Date: 2026-09-06. Android 1.0.16 (862), Desktop source 1.0.11.
Base main: 2a380ddc5, including merged PR #2820. iOS is unchanged.
Device: Samsung S20 Ultra SM-G9880; no S26U or SM-T575 operations.

## Local gates

- Desktop isolated Run Kernel/MQTT regression: 203 passed in 84.290s.
- Desktop renderer regressions: 16 passed; structure check passed.
- Repository checks passed, including read-only iOS structure inspection.
- Android focused JVM suite: 92 passed, no failures/errors/skips. Coverage:
  terminal codecs, response retention, generation identity, paged recovery,
  remote observations, coordinator, control-plane, and fallback contracts.
- Android and instrumentation APK builds passed. Final build took 5m1s; existing
  deprecation warnings remain. No production code was edited during compilation.

## Fault coverage

New device cases cover all terminal statuses across encrypted reopen, later
metadata versus canonical result revisions, old generations and receipts,
independent task dimensions, unchanged legacy encryption identities, schema-one
migration, body-insert rollback, retirement rollback, and opaque on-disk metadata.

Real MobileNativeAgent execution is exercised with injected provider outcomes:
manual model locking, Auto fallback, cancellation without a follow-up call, and
an older cancellation arriving after a newer execution. The eligibility test
uses isolated paired-link preferences and pending stores, not real pairing or
an external broker. It verifies unpinned discovery after a newer generation,
pinned stale-body rejection, cancellation, and already persisted replies.

Two optional instrumentation phases save and recover the same test database in
separate App processes. They are skipped in a normal class run and must be run
separately around an explicit force-stop. This is process-death testing, not a
phone reboot or power-loss guarantee.

## Intermediate failures retained

- First device run: 46 passed, 3 skipped, 3 failed. A fixture used a malformed
  route ID and two runtime fixtures omitted Agent Loop initialization.
- Second run: 47 passed, 3 skipped, 2 failed. Auto correctly tried a deferred
  fallback, but the fixture incorrectly assumed failure would end execution.
  Tests now explicitly distinguish manual locking from Auto and assert that
  the actual error is persisted before dispatching the fallback.
- Production route validation and Auto behavior were not weakened to pass these
  tests. Original logs are retained in build/android-terminal-outcome-device-tests.log
  and build/android-terminal-outcome-device-final.log.
- The focused rerun then found the synthetic deferred candidate was absent from
  its test catalog, so its adapter was intentionally blank. The fixture now
  declares both available test agents and verifies the selected adapter too.
  Its original failure is retained in android-terminal-outcome-focused-device.log.

## Final S20U results

- Full combination: **50 passed, 3 skipped, 0 failed**, 29.166s. The runner's
  OK (53 tests) includes two opt-in process tests and the live paired probe;
  it is not 53 passes. All 12 regular terminal inbox cases, two eligibility
  cases and ten runtime fallback/cancellation cases passed.
- Save/force-stop/recover: both optional tests passed in separate invocations.
  The shared ID was terminal-process-8c7ab938-3e5c-4e7c-91f3-550066b5ff6f.
  pidof confirmed the App absent after force-stop, before the recovery phase.
  Test bodies took 58ms and 42ms, not an end-to-end recovery latency SLO.
- Counting those phases, 52 distinct device tests passed; only the real paired
  new-Desktop probe remains unperformed. No commercial-provider fault is claimed.
- Cold Activity launch after testing: TotalTime 1,421ms, WaitTime 1,427ms.
  This is Activity launch timing, not time to first message or a percentile.
- Screenshot android-terminal-outcome-s20.png shows the original conversation
  and answer after cold launch. Its displayed 22-second answer is from a prior
  test; no new model-response latency was measured here.
- Crash buffer empty. All 88 native-library entries are byte-identical to
  Android 1.0.15; ASR/QNN libraries and inference code are unchanged.
- Final version 1.0.16 (862), update time 2026-09-06 06:52:12. The original
  first-install time is unchanged. Only the instrumentation APK was uninstalled.
  Test databases and the temporary phone screenshot were removed, not user data.

## Artifacts

Under build/ in this worktree:

- galaxyssi-terminal-outcomes-1.0.16.apk: 417,908,561 bytes;
  SHA-256 C2C74B857B072CDF8D4904771C19E0FB0CEF7BFCFB24C1CACBB6ED92166F1E81.
- galaxyssi-terminal-outcomes-tests.apk: 1,549,809 bytes;
  SHA-256 EC448345D5163E10EFCD661C270FD5F8DE194FBC0897C930D1C9788C69E12DA2.
- android-terminal-outcome-release-check.log: final build/JVM run.
- android-terminal-outcome-device-verified.log: complete final device rerun.
- android-terminal-outcome-process-save.log and process-recover.log: separate
  process phases (both filenames start with android-terminal-outcome-).
- android-terminal-outcome-backend.log, repository-final.log and crash.log:
  backend, repository and crash evidence (same filename prefix).

## Installation boundary

Pre-test installed version was 1.0.15 (861), first-install time
2026-09-05 23:09:22. MemAvailable was 5,852,360 KiB, available /data storage 174GiB.
Only overwrite installation is used; no production uninstall, data clear, identity
reset or re-pair. Temporary test databases are namespaced and removed by teardown.

The running Desktop remains the task-recovery 1.0.4 instance, PID 21368.
Its earlier deployment-policy rejection was not retried by another mechanism.
No live paired recovery against this new Desktop implementation is claimed.
Persistent result-page checkpoints, generation-aware Desktop outbox retirement,
cross-store atomic acknowledgement, real paired chaos and overall reliability
goal completion remain outstanding.
