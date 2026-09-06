# Android result-page checkpoint verification

Date: 2026-09-06. Base main: 68c26b6a0 (merged PR #2822). Android version
1.0.17, version code 863. Desktop source remains 1.0.12; no Desktop wire protocol,
native ASR/QNN implementation, pairing key or user database reset is part of this
change. S20U is the user's current acceptance device; iOS development is deferred.

## Build and regression evidence

- Final Gradle build completed successfully in 8 minutes 37 seconds, producing
  the debug APK and instrumentation APK. Existing SDK XML/deprecation warnings
  remain; there were no build errors.
- Six JVM suites: 58 tests passed, zero failures/errors/skips. These cover page
  checkpoints (10), page recovery (13), response codec (7), response retention
  (6), recovery wake coordination (11) and remote outcomes (11).
- Repository checks passed. The incidental read-only iOS structure check does
  not mean iOS features were developed or tested on a device.
- Combined S20U instrumentation: 28 passed, five intentionally skipped, zero
  failures. The runner's `OK (33 tests)` includes those five skipped cases.
- Four save/recover phase tests were subsequently run individually and passed
  across an explicit App force-stop. Therefore 32 distinct device cases passed;
  the remaining live paired-Desktop probe was not performed in this phase.

The device tests use real SQLite and Android Keystore with unique test database
names. They cover reopen, missing/corrupt pages, encrypted at-rest markers,
scope/generation separation, digest-bound deletion, transaction rollback,
unreadable manifests, inbox persistence APIs and ordinary replies bypassing
checkpoint storage. Page replies come from a local authenticated-response
fixture, not a live provider or broker. The inbox/cleanup test verifies the
storage operations in sequence, not an atomic transaction across databases.

## Actual process-death recovery

Only S20U serial R5CN319CESA (SM-G9880) was operated. A second connected device
belonged to unrelated work and was left untouched.

1. Save checkpoint prefix using ID
   `page-20260906-55f3f0e2-13ae-4e56-bcda-a98fb7dc5c13`.
   The isolated checkpoint database contained exactly two pages. The test body
   took 0.126 seconds.
2. Save the previous phase's terminal-outcome case using ID
   `terminal-process-checkpoint-20260906-854e03cc`; test body 0.077 seconds.
3. Execute `am force-stop com.galaxyssi.chat`. `pidof com.galaxyssi.chat` returned
   no PID (exit 1), establishing that recovery did not reuse the old process.
4. Run the checkpoint recovery case in a new instrumentation process. The first
   request was zero-based page 2; no request redownloaded the first two pages.
   The complete recovered reply matched the expected body. Test body: 0.372 s.
5. Run the terminal-outcome recovery case; it passed in 0.044 seconds.

Those durations are instrumentation test bodies, not App cold-start time or an
end-to-end recovery SLO. This is not a phone power-loss or live MQTT chaos test.
Tests clean up only their own named databases. The instrumentation package was
removed afterwards, not the production App.

## Installed artifact and native regression boundary

The production APK was installed with `adb install --no-streaming -r`, preserving
App data. Package inspection confirmed:

- Installed version: 1.0.17 / 863.
- First install: 2026-09-05 23:09:22, unchanged from the 1.0.16 baseline.
- Last update: 2026-09-06 08:12:11.
- Before installation: MemAvailable 5,519,432 KiB; approximately 176 GiB of
  storage free. These are environment observations, not memory benchmarks.

APK `build/galaxyssi-result-checkpoints-1.0.17.apk`:

- Size: 417,924,135 bytes.
- SHA-256: `95B2B3C2DF6F00D3AD7BEDCCAAC757DC7A18D986919C5BFF32D48A6137AC112D`.

Instrumentation APK `build/galaxyssi-result-checkpoints-tests.apk`:

- Size: 1,564,141 bytes.
- SHA-256: `C3179EF2A5C46E37EEAE2095C7161DAC05D0858EC5822DB716ADF71C28C69988`.

All 88 `lib/**/*.so` entries were individually SHA-256 compared with the previous
1.0.16 APK: 88 present, zero changed or missing. This establishes unchanged
packaged native libraries, not a new ASR/QNN latency measurement.

After relaunch, the screenshot still showed the pre-existing conversation and
reply. It is not evidence of a newly completed model call. The captured Android
crash buffer was empty; it was not cleared before capture. A clean snapshot is
not a long-running ANR/crash acceptance result.

Local evidence is retained under `build/android-result-checkpoint-*`: JVM XML is
under the normal Gradle test-results directory; build-release, device, process
save/recover, terminal save/recover, repository and crash logs are separate. The
visual check is `build/android-result-checkpoint-s20.png`. These local artifacts
are intentionally not committed as source files.

## Remaining acceptance work

- Live paired Desktop/broker and real-provider interruption/recovery tests.
- Durable acknowledgement intent and cross-store recovery/cleanup semantics.
- Lifecycle cleanup for cancelled/deleted scopes and encrypted orphan pages.
- End-to-end cold-start/recovery percentiles and large-result memory profiling.
- The existing running Desktop has not been replaced with this worktree's build;
  the earlier deployment rejection was not bypassed.

The larger execution/recovery/performance goal remains active. This phase adds
durable partial-result download, not complete unified-kernel or chaos acceptance.
