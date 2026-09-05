# Desktop result outbox CAS verification

Date: 2026-09-06. Desktop source 1.0.12. Base main 27508517d, including merged
PR #2821. Android 1.0.16 is unchanged in this phase; iOS and native ASR/QNN are
unchanged. No production App data or pairing was modified.

## Regression coverage

- Final isolated execution/MQTT suite: 237 passed, 55.165 seconds, no skips.
- This includes 34 new logical-outbox cases. Three child processes actually
  exit with code 76 after enqueue/handoff or when rejecting an old receipt.
- Focused repeat: 37 passed, 10.698 seconds. This combines the new cases with
  three invocations of the existing external-stall recovery test.
- Desktop renderer regressions: 16 passed; Desktop structure check passed.
- Repository checks passed, including read-only iOS structure inspection.
- The outbox implementation is about 11 KB and its focused tests about 20 KB.

The new cases exercise old-worker completion after a new generation, same-task
independent scopes, stale/partial receipts, explicit replay revision ABA, failed
ciphertext ownership, concurrent first-open/writers, enqueue/handoff/migration
rollback, corrupt-row isolation, swapped ciphertext, legacy migration, bounded
rotation, generation fences after reopen, and encrypted at-rest state. A writer
is also launched from inside body decoding to prove decoding holds no database
writer transaction. Old corruption observations cannot quarantine a newer row.

## Intermediate failures retained

The first 228-case suite and expanded 233-case suite passed. Adding the explicit
operator replay argument then exposed one old archive mock that did not accept
the new keyword. The mock now accepts it and asserts normal delivery is not an
operator replay; archive-before-queue assertions remain intact.

A subsequent 237-case run failed the existing external-stall test's one-second
wait and then encountered Windows cleanup of a still-open test database. A
single isolated rerun passed in 0.154 seconds; three further focused invocations
and the final full suite passed without changing that test or production
recovery logic. The watchdog code starts only after handler registration, so
registration order is not claimed as the established root cause. The original
failure remains in `build/desktop-outbox-cas-backend-release.log`; this timing
flakiness is not declared fixed by a successful rerun.

## Scale test

`tools/dev/benchmark-task-result-outbox.py` uses real encrypted SQLite APIs in a
temporary, isolated Desktop state directory. It enqueues 10,000 distinct results
and rotates through 313 pages. All 10,000 distinct task IDs are observed, all
10,000 pending rows remain stored, each read returns at most 32 rows, and EXPLAIN
confirms `task_result_queue_pending` index use. Each result has about 0.75 KB of
content plus identity metadata. This is not a large-body, UI or phone benchmark.

The first run (before moving body decryption outside the writer transaction):

| Metric | Measured |
| --- | ---: |
| 10,000 durable writes | 135,838.520 ms |
| First 32-row page | 897.498 ms |
| Page P50 | 76.402 ms |
| Page P95 | 89.563 ms |
| Page P99 | 376.457 ms |
| Database bytes | 22,560,768 |

This is an intermediate implementation measurement, not an old-main baseline.
The final code additionally removes redundant decrypt-after-insert and releases
the writer transaction before normal pending-body decoding.

Final same-scale rerun (10,000 stored and 10,000 distinct read, 32-row limit):

| Metric | Measured |
| --- | ---: |
| 10,000 durable writes | 113,285.732 ms |
| First 32-row page | 74.549 ms |
| Page P50 | 71.205 ms |
| Page P95 | 83.860 ms |
| Page P99 | 398.675 ms |
| Database bytes | 22,544,384 |

First-page and write time improved in these two runs, but P99 did not improve.
These are local measurements under uncontrolled host load, not a repeatable
cross-device SLO claim. P99 tail latency still needs profiling; body decryption
no longer holds the writer even when decoding is slow.

## Evidence and boundaries

Local logs are under `build/desktop-outbox-cas-*`: `backend-acceptance.log` is the
final complete regression, `focused-final.log` the focused repeat, `ui.log` and
`repository-final.log` the checks, `scale.log` the initial scale run, and
`scale-final.log` the final rerun. Intermediate logs are retained, not rewritten
as passes.

No new Desktop process was launched and no new phone/broker exchange was claimed.
The earlier local deployment rejection was not bypassed. Source version 1.0.12
does not mean the existing running Desktop was upgraded. Cross-store atomic
acknowledgement, phone result-page checkpoints, true paired/provider chaos, and
the overall reliability goal remain incomplete.
