# Paired Relay settings verification (2026-09-06)

## Scope and environment

- Base includes merged PR #2830, `d64675469`.
- Desktop source version: 1.0.21.
- Windows, Python 3.11, isolated temporary backend state and data directories.
- Production Desktop, all existing pairings, and phone data were not modified.
- No Android source/native-library changes or new APK in this phase.

## Focused results

- Pair configuration and pairing lifecycle: 48 tests and 18 subtests passed
  before the main merge (15.27 seconds).
- After merging main, receiver/configuration/API: 38 tests and 11 subtests passed
  (21.40 seconds), including real HTTPS expiry through the production adapter.
- Additional clear-all test: pair settings suite 16 tests and 11 subtests passed
  (2.07 seconds).
- Desktop check: 26 tests passed, followed by the structure guard.
- Root repository check passed, including protocol, i18n, artifact, and size
  policies. This command also runs the existing iOS structure check; no iOS
  implementation work was performed.
- Playwright loaded the actual renderer HTML/CSS with mocked settings IPC at
  1280x1000 and 640x960. Device switching, save states, secret clearing, and
  control bounds passed. This is not a live Electron/backend or phone test.

## Initial full regression and findings

The first full backend run completed in 314.27 seconds with 1463 passed,
2 failed, 1 teardown error, and 418 passed subtests.

1. The existing real-expiry test fixture omitted the local paired fingerprint
   and provisioning credential. The new production origin resolver correctly
   requires complete paired identity/configuration. The fixture was completed;
   the production checks were not weakened. Focused real HTTPS regression passed.
2. `TaskResultOutboxTests.test_concurrent_first_open_keeps_every_distinct_result`
   hit `sqlite3.OperationalError: database is locked` at
   `link_delivery._connect()` while setting WAL mode. Its temporary database
   remained open, causing Windows cleanup to fail with WinError 32. That code is
   identical to main in this PR. This is a genuine intermittent initialization
   race to fix in a separate, focused reliability PR, not a reason to remove
   concurrency coverage or increase a test timeout.

The original log is retained locally as `build/blob-settings-backend.log` and
the JUnit report as `build/blob-settings-backend.xml`. A clean full rerun is
recorded separately, so it cannot overwrite evidence of the first failure.

## Final full regression

After completing the paired fixture and adding lifecycle coverage, the full
backend suite passed: **1466 tests and 418 subtests**, 3 dependency deprecation
warnings, **284.82 seconds**. Results are retained in
`build/blob-settings-backend-final.log` and
`build/blob-settings-backend-final.xml`. No tests were excluded or skipped.
The intermittent WAL initialization race did not recur in this run; it remains
an open reliability issue rather than a claimed fix.

## Remaining acceptance

- Actual per-device configuration delivery over the paired MQTT connection.
- Public HTTPS Relay deployment and phone-to-Desktop large-file acceptance.
- Operator configuration changes during an existing transfer and explicit
  migration/replanning behavior.
- Complete production outbound artifact integration.
- The independent delivery-database first-open race described above.

No public Relay availability, phone acknowledgement, new installation, or live
end-to-end acceptance is claimed by the tests in this phase.
