# Durable result receipt verification

Date: 2026-09-06. Android 1.0.18 / 864; Desktop source 1.0.13. This phase builds
on PR #2823's Android page checkpoints (12e674665), with main 68c26b6a0 fetched
during development. It does not change iOS or native ASR/QNN code.

## Automated regression evidence

- Android: 69 JVM tests passed in eight suites, zero failures/errors/skips. Eleven
  are new receipt identity/backoff/coordinator cases; prior reply checkpoint,
  recovery, codec, retention and execution-outcome suites also passed.
- Desktop: the final isolated kernel/MQTT regression run passed 244 tests in
  192.328 seconds, including seven new archive receipt confirmation cases.
  The focused archive/receipt run passed 21 tests in 26.307 seconds.
- Desktop renderer/structure checks: 16 tests passed and structure check passed.
- Repository checks passed, including incidental read-only iOS structure checks.
- Initial Android build succeeded in 15m 30s. Host available physical memory
  temporarily fell below 1 GiB, so no extra heavy tasks were started. Final build
  after the indexed-query improvement succeeded in 10m 12s. Existing SDK/C++ and
  Android API deprecation warnings remain; no build errors occurred.

## Query review and correction

An intermediate `state IN (0,1) ORDER BY next_attempt_at,receipt_id LIMIT 32`
query used an index for filtering but still reported `USE TEMP B-TREE FOR ORDER
BY` in SQLite's query plan. That did not guarantee bounded work on a long queue.

The final query uses two ordered index seeks, each limited to 32 candidates,
inside one SQL statement/snapshot. Kotlin merges at most 64 candidates and
returns at most 32. The next-deadline query reads at most one indexed row per
state. S20U tests inspect the actual production SQL's EXPLAIN plan: both seeks
use `result_receipts_due`, with no temporary B-tree. A 129-receipt case drains all
distinct rows in bounded pages; a 70-receipt mixed-state case verifies ordering
and no repeated claims. These are correctness/query-plan tests, not a 10,000-row
phone latency benchmark or a P95 claim.

## S20U device and process-death tests

Only R5CN319CESA / SM-G9880 was operated. Test databases use unique names and the
real Android Keystore/SQLite APIs; production identities, pairing and chat data
were not reset. The other connected device was left untouched.

- Initial instrumentation: 40 passed, seven intentional skips, zero failures.
- Final instrumentation: 41 passed, seven intentional skips, zero failures;
  runner duration 13.506s. `OK (48 tests)` includes the skipped cases.
- The six process save/recover cases then ran separately and all passed. Thus
  the final APK has 47 distinct passing device cases; the remaining live paired
  Desktop probe was not performed.

The final process sequence saved two receipt states (awaiting confirmation and
confirmed/cleanup pending), two result pages, and the previous terminal-outcome
fixture. An explicit `am force-stop com.galaxyssi.chat` was followed by an absent
`pidof` result (exit 1). New processes restored both receipt states, completed
cleanup, resumed result pages starting at zero-based page 2, and restored the
terminal outcome. No model/tool execution was requested by these tests.

| Fixture ID | Save test body | Recovery test body |
| --- | ---: | ---: |
| receipt-final-20260906-1050-4ef9d6 | 0.091s | 0.057s |
| page-final-20260906-1050-1cb12 | 0.126s | 0.414s |
| terminal-final-20260906-1050-f9228 | 0.010s | 0.012s |

Those are instrumentation test-body durations, not whole-App recovery latency.
Storage/publisher fixtures are not a live broker/provider handshake, radio
interruption, phone power-loss test or whole-system chaos acceptance. Desktop
archive tests cover repeated/lost confirmations using its real encrypted SQLite
archive and reopen, but do not claim a real paired exchange.

## Installed final artifact

Final APK: `build/galaxyssi-durable-receipts-1.0.18.apk`.

- Bytes: 417,950,390.
- SHA-256: `8E24CE28B1DA90DC034D9F197FDB81107E09100C05F81F452BBB06263BC4855B`.

Final instrumentation APK: `build/galaxyssi-durable-receipts-tests.apk`.

- Bytes: 1,576,700.
- SHA-256: `E11767CE5806F813BA2666C57D2720766A1DA582BFAAFAE7F4042F0DBB190040`.

The final APK was installed with `adb install --no-streaming -r`. Package data
shows version 1.0.18 / 864, last update 2026-09-06 10:47:37, and unchanged first
installation 2026-09-05 23:09:22. Pre-update S20U MemAvailable was 4,944,776 KiB.
All 88 packaged native `.so` entries are SHA-256 identical to the 1.0.17 APK.
This establishes unchanged libraries, not a new ASR/QNN latency measurement.

The instrumentation package alone was uninstalled after testing. The ordinary
App was relaunched; Android reported a 731ms cold Activity launch, which is not
a complete transcript-load/P95 measurement. The screenshot still shows the
pre-existing conversation and reply, not a newly completed provider call. The
captured crash buffer was empty and was not cleared before capture.

Local logs/screenshots remain under `build/android-durable-receipts-*`, with
separate `*-final.log` files for the final APK; backend and UI logs are under
`build/desktop-durable-receipts-*`. JVM XML remains in Gradle's test-results
directory. These large/local artifacts are not committed to the repository.

## Remaining boundaries

The running Desktop was not replaced by this phase and the earlier deployment
rejection was not bypassed. Its current version cannot be inferred from this
worktree's 1.0.13 source. Live paired handshake/chaos verification is still needed.
Managed-response ledgers, old replies lacking receipt metadata, cancelled/deleted
scope cleanup, full Run/transport/transcript atomicity, and whole-chain latency
percentiles remain part of the active larger goal, not completed by these tests.
