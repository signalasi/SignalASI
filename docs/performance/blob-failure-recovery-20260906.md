# Blob failure recovery validation, 2026-09-06

## Scope and installed build

This phase makes terminal attachment failures durable Agent observations on
Android and Desktop. It also gives a new recovery request a fresh transfer
identity, while duplicate deliveries of the same request remain idempotent.
See [the contract](../architecture/blob-failure-observations.md).

The S20U (`SM-G9880`) was overwrite-installed with Android **1.0.22 / 868**.
First-install time remained `2026-09-05 23:09:22`; last update was
`2026-09-06 20:06:20` (device local time). The production App was not uninstalled,
reset, cleared, or re-paired. Only the isolated instrumentation package was
removed after tests. APK SHA-256:
`D8A42EF48816E6E06598E36D6C795E45D541D64154E4177A36372A57E2651DE7`.

Desktop source is **1.0.20**. The running Desktop was not replaced because it was
serving another device validation. This report does not claim that the running
Desktop contains these changes.

## Android failure and recovery coverage

Four focused unit suites passed: attachment transfer protocol (6), connector
response codec (9), failure contract (4), and outgoing contract (7): **26 tests**.

On S20U, **14 instrumentation tests passed** in 3.4 seconds:

- Ten outgoing-journal tests cover restart, failure persistence, observation
  acknowledgement, duplicate receipts, cancellation and late-worker fencing.
- Three failure-delivery tests cover the encrypted inbox, managed response
  ledger, wrong binding, terminal requests and replay.
- One runtime test verifies that an attachment failure reaches the planner
  without penalizing the provider or automatically switching to another model.

The first instrumentation run exposed `SQLITE_READONLY_DBMOVED` after an isolated
test storage root was removed and recreated. `AgentEncryptedStorage` cached
database instances by logical name alone. Its key now uses the absolute database
path, keeping different storage roots distinct. No database schema, encryption
key or production data was changed. All 14 tests passed after rebuilding and
overwrite-installing the correction.

The runtime test injects a planner to inspect the observation boundary. It does
not demonstrate a live provider independently completing a replacement transfer.

## Same-scale 152 MiB transport regression

The final APK transferred **159,383,552 bytes** over certificate-validated HTTPS
through ADB reverse. The runner intentionally killed the App process after the
first accepted 1 MiB and recovered its encrypted checkpoint in a new process.

| Measurement | Observed value |
| --- | --- |
| Full upload SHA-256 | Matched |
| Accepted chunks | 152; each uploaded once |
| Verified return download | 3,145,801 bytes |
| Preparation time | 1,520 ms |
| Preparation / first-chunk sampled PSS growth | 13,774 KiB / 13.5 MiB |
| Checkpoint recovery | 16 ms |
| Remaining upload | 21,918 ms |
| Return download | 528 ms |
| Resume-phase sampled PSS growth | 49,500 KiB / 48.3 MiB |
| Maximum control probe latency | 78 ms |
| Maximum main-thread callback latency | 11 ms |
| Control probes / failures | 303 / 0 |

TLS rejection outside the fixture's scoped trust remained effective. This is
local transport, not WAN throughput, paired MQTT or frame-time measurement.
One repeat is not enough to infer percentiles or a statistically controlled
performance improvement. PSS samples can miss allocation spikes between samples.
The transport driver in this branch records metrics but does not yet contain
the strict benchmark gates introduced separately in PR #2829.

All 88 packaged native `.so` entries matched the previous 1.0.21 APK byte-for-byte.
The Android 16 KiB check passed for 72 Android AArch64 libraries. ASR/QNN binaries
and scheduling paths were not changed; no new inference benchmark is claimed.

## Desktop coverage and evidence

The final full backend run passed **1,445 tests and 407 subtests** in 382.62
seconds. Two existing WebSocket deprecation warnings remained. Repository
checks and all 16 Desktop renderer tests passed as well.

The focused backend suites cover durable failure receipts, receiver restart,
corrupted ciphertext, stale-receipt replay, exact scope binding and a real HTTPS
transfer under a fresh recovery request after expiration. Recovery adapters for
Codex and generic agents return typed failure observations to the next model
turn rather than treating missing verified files as successful restoration.

The conversation persistence regression now explicitly uses its fake Codex
delivery and an empty evolution store. It no longer initializes an unrelated
native coordinator or reads the host's real evolution directory. Dedicated
Auto/coordinator tests remain separate; the production timeout and repository
overlap checks are unchanged.

Local, uncommitted evidence:

- `build/blob-failure-s20-instrumentation-final.log`
- `build/blob-device-152mib-01c5828b/result.json` and phase logs
- `build/blob-failure-backend-verified-all.log` and JUnit XML
- `build/blob-failure-android-storage-scope.log`
- Android focused JUnit XML under `apps/android/app/build/test-results`

## Remaining acceptance

This closes the implemented failure-observation boundary and isolated transport
regression, not the whole communications goal. Production relay provisioning,
paired multi-device MQTT plus real-provider replanning, contact/artifact routes,
origin migration, retention/deletion hooks and broader chaos evaluation remain
separate work. No claim is made that every failed attachment can be repaired
without the user's source file or renewed access.
