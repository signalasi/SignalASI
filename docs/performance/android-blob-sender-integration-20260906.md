# Android Blob sender integration checkpoint

Date: 2026-09-06. Main fetched before final verification and submission:
`3c48121ac` (PR #2826). Android 1.0.20 / 866; Desktop source 1.0.16.
This is a staged phone-to-Desktop Agent input path, not completion of the
whole communication data-plane or reliability goal.

Follow-up: [bounded preparation and regression gates](android-blob-bounded-preparation-20260906.md)
records real 16/152 MiB S20U HTTPS tests, the preparation memory finding, the
1.0.21 buffer correction and full JVM regression. Post-fix phone measurements
and production paired-network acceptance remain pending.

## Application integration

- A paired Desktop can provide a versioned `blob_relay_config` only when the
  phone explicitly requests it with `request_blob_configuration: true`.
  Desktop settings come from `GALAXYSSI_BLOB_RELAY_URL` and
  `GALAXYSSI_BLOB_PROVISION_TOKEN`; both are required to enable this path.
  Configuration is encrypted at rest, committed before publication, and sent
  through the existing durable Signal control queue. Rotation and disablement
  advance a persisted revision even if the wall clock moves backwards.
- Android pins configuration to the authenticated Desktop, route and paired
  fingerprint, validates the HTTPS origin and credentials, and saves it with
  the existing device-bound encrypted preferences. Configuration never enters
  chat listeners, model prompts or notifications, including phone-origin and
  recovered control payloads. No production endpoint or credential is included.
- New Agent attachment sends register encrypted SQLite jobs before publishing
  the attachment-dependent task. A configured transfer uses the Blob worker
  instead of queuing legacy MQTT chunks. It does not fall back to MQTT after
  acquiring Blob ownership. Devices without configuration retain the existing
  transport until rollout is configured; bulk MQTT removal is not yet complete.
- Separate scheduler/upload workers stream the existing encrypted source into
  ciphertext staging, checkpoint capabilities and accepted blocks, and publish
  only a small private offer through Signal. Retry is independent of model
  task/action counts. No UI thread waits on relay network operations.
- Relay upload completion does not release the task. A verified, identity-bound
  Desktop `stored` receipt first commits the cleanup phase. Only that phase
  releases the task dependency, cleans the original transfer staging and revokes
  relay ciphertext. Process death can resume this phase without another upload.
- Cancellation fences late upload callbacks and late receipts. Reusing a job
  identity with a different immutable manifest is rejected, not silently routed
  into the previous request. Numeric receipt lengths cannot be coerced strings.
- Attachment receipt/recovery handling now marks the durable inbox complete
  after the background handler succeeds. Persistence failure leaves it pending
  and schedules replay. Concurrent replays do not queue duplicate handlers;
  authenticated source identity survives inbox replay.

## Automated verification

| Check | Result |
| --- | --- |
| Android JVM, 13 suites | 62 passed, zero failed/errors/skips |
| Kotlin/Python HTTPS interoperability | Passed, 3,145,801 bytes each way, matching SHA-256 |
| Kotlin resume | Each of four accepted chunks uploaded exactly once |
| Full isolated Desktop backend | 1,427 tests and 398 subtests passed, 504.35s |
| Configuration/ingress/subscription targeted run | 25 passed, overlapping full-suite coverage |
| Desktop renderer and structure | 16 passed, structure passed |
| Repository guard and whitespace check | Passed |
| APK ZIP / native 16 KiB alignment | 72 Android AArch64 libraries passed |
| APK and instrumentation APK build | Passed, 7m 18s |

The interoperability relay is a separate local HTTPS process with a temporary
certificate trusted only by its test clients. This is not WAN throughput or
Android network evidence. Full backend tests use isolated HOME/state directories
and supported dependency versions under `build/blob-minimum-deps`; they do not
open the production Desktop store. Two existing websockets deprecation warnings
remain. Test counts from separate runs must not be summed as unique coverage.

## S20U evidence

Only the S20U / `SM-G9880` was operated. The separately attached SM-T575
was untouched. Before installation, S20U showed a completed Chinese chat;
no running reply was interrupted.

- Previous installed version: 1.0.19 / 865. MemAvailable: 4,124,196 KiB.
- Overwrite-installed 1.0.20 / 866 using `adb install --no-streaming -r`.
  First-install time remained 2026-09-05 23:09:22; update time 15:45:27.
- Six regular real Keystore/SQLite tests passed, runner duration 0.440s:
  encrypted rows/reopen, bounded claims, activation racing a claim, duplicate
  identity rejection, cancelled-upload late receipt, and receipt-phase fencing.
- One two-process restart scenario passed in two separate instrumentation
  invocations. The first persisted and claimed cleanup after a stored receipt.
  `am force-stop` then removed the App process (verified by `pidof`). The second
  recovered the abandoned claim, retained the exact receipt and conversation,
  and completed cleanup state. Checkpoint recovery body: **24ms**. This does not
  prove whole-task recovery, transfer-throughput or UI-readiness percentiles.
- Test-created databases alone were deleted. The test package alone was
  uninstalled. The production App, pairing, identity and conversations were not
  cleared or regenerated. App reopening displayed the same conversation/reply.
  StartupActivity reported a 775ms cold launch, not full UI readiness.
- Captured crash buffer was empty; it was not cleared before inspection.
- All 88 packaged native library hashes match 1.0.19 exactly. This proves no
  ASR/QNN native binary change, not a fresh ASR/QNN performance benchmark.

Artifacts:

- `build/galaxyssi-blob-sender-1.0.20.apk`: 410,522,171 bytes;
  SHA-256 `E38E1F813C4BC20BC062151C5B656824768B72FF73328667E350366FAE270E79`.
- `build/galaxyssi-blob-sender-tests.apk`: 1,584,717 bytes;
  SHA-256 `A26C7BFC2B7E7811B1B0AECBD077EF60D15D7E88114D8E36765B638FEA7577EE`.
- Logs: `build/blob-sender-full-backend.log`, `build/blob-config-regression.log`,
  `build/android-blob-interop-gradle.log`, `build/android-blob-sender-interoperability.log`,
  `build/blob-s20-journal-tests.log`, `build/blob-s20-restart-persist.log`,
  `build/blob-s20-restart-recover.log`. Local screenshots and APKs are not committed.

## Remaining integration and acceptance

### Instrumentation entry-point follow-up

The two-process scenario requires an external driver to supply a test ID and
phase around force-stop. Like the existing Run Kernel restart tests, a general
instrumentation run without that ID now reports an assumption skip instead of
failing on a missing parameter. This skip is not counted as recovery evidence.
With explicit parameters, both phases were rerun on S20U and passed (0.092s and
0.034s runner durations). The App was reopened and only the test package removed.
No production App code or installed APK changed in this follow-up.

Final test APK: `build/galaxyssi-blob-sender-tests-final.apk`, 1,584,717 bytes,
SHA-256 `3CAC6AF2F9FC17B3DF36CEED4CF06976987A356E99BBAE7F072A40E9FA2798D6`.
Logs: `build/blob-s20-restart-default.log` and
`build/blob-s20-restart-final-{persist,recover}.log`.

### Outstanding acceptance

- No production relay has been provisioned and no running Desktop was replaced.
  Android-to-Desktop Agent attachment submission over the actual paired public
  network has not been validated. This installation alone does not move existing
  attachments off MQTT. Never report localhost measurements as that result.
- Contact/App-to-App and generated-artifact routes still use their existing
  transport. File-card receive actions, full progress/error/repair presentation
  and transfer cancellation from conversation/task deletion need integration.
- Retry failures currently remain in the sender/receiver diagnostic journals;
  user-visible expiry/repair and propagation into the Agent loop remain work.
- Completed journal retention, source expiry and relay-origin migration policy
  are not finished. A conflicting reused attachment recovery ID is now rejected,
  but the broader recovery-request transfer-ID scheme still needs redesign.
- Real-device HTTPS transfer, disconnect/resume under load, concurrent chat,
  large attachments and production process/device restart remain required.
- iOS is deferred. The larger kernel/DAG/memory/concurrency/evolution goal
  remains active and is not completed by this phase.
