# Android Blob preparation and regression-gate follow-up

Date: 2026-09-06. Base main: `3c48121ac` (PR #2826).
Android source/build: 1.0.21 / 867. Installed S20U remains 1.0.20 / 866.
Desktop source remains 1.0.16. This is a follow-up to PR #2827, not
production data-plane acceptance. iOS is unchanged.

## Real S20U baseline

The new driver `tools/dev/test-android-blob-device.py` uses only the explicitly
specified SM-G9880. It installs an instrumentation APK, not a replacement App,
and creates isolated synthetic data and temporary test-client TLS trust.
It neither changes Android's trust store nor replaces pairing or user data.

An isolated HTTPS relay is reached through scoped ADB reverse forwarding. The
phone uses the production staging, transfer client, Keystore and SQLite journal.
The Desktop uses its actual BlobInputReceiver and scoped input-file commit.
Only offer/receipt control delivery substitutes authenticated test endpoints
for Signal/MQTT. This is not an actual paired production coordinator or WAN test.

Both runs intentionally killed the App process after the first accepted MiB,
reopened the encrypted checkpoint in another process, transferred only missing
chunks, obtained a matching Desktop stored receipt, and verified SHA-256 of the
complete output. Each accepted upload chunk arrived exactly once. Both also
downloaded and verified 3,145,801 bytes of Python-produced ciphertext content.
Test packages, files and reverse mappings were removed and the App reopened.

| Metric | 16 MiB | 152 MiB |
| --- | ---: | ---: |
| Preparation time | 197 ms | 1,700 ms |
| Preparation-phase PSS baseline | 141,032 KiB | 141,308 KiB |
| Sampled preparation-phase PSS peak | 180,468 KiB | 323,821 KiB |
| Sampled PSS growth | 39,436 KiB | 182,513 KiB |
| Journal recovery test body | 14 ms | 13 ms |
| Remaining upload, including injected 2-second stall | 4,424 ms | 33,931 ms |
| Return download and verification | 656 ms | 643 ms |
| Maximum independent control probe | 125 ms | 120 ms |
| Maximum main-looper callback probe | 5 ms | 17 ms |
| Completed probes / failures | 49 / 0 | 361 / 0 |

These are single-run measurements, not percentiles. Preparation-phase PSS is
sampled every 100 ms through the first upload checkpoint, not an exact allocation
peak. Main-looper callbacks are not a scrolling/frame-time benchmark. The 152 MiB
functional pass exposed approximately 178.2 MiB of PSS growth during preparation;
it is not a memory-performance pass. Resume-phase growth was about 36.5 MiB.

## Bounded-buffer correction

- Preparation now reuses a plaintext buffer of at most 1 MiB, a ciphertext buffer
  of at most 1 MiB plus the authentication tag, and one reinitialized JCE Cipher.
- Hashing and atomic file writes consume only the actual chunk length. A short
  last chunk cannot include stale buffer bytes.
- Caller-owned buffers are wiped on normal completion and exceptions, including
  cancellation. No explicit GC, file-size reduction, plaintext staging, protocol
  change, ASR/QNN change or memory-based task rejection was introduced.
- Unit tests verify shared Python ciphertext vectors, caller-buffer boundaries,
  full/short-chunk cipher reuse, plaintext-buffer identity across chunks,
  cancellation cleanup, and authentication-failure output cleanup.

This removes repeated large caller-owned allocations. JCE provider allocations
and actual ART/PSS behavior still require the same-scale S20U remeasurement.

## Regression failures found and corrected

1. On Linux, MockWebServer can expose a `localhost` URL. The test-only HTTP
   exception accepts literal loopback addresses, so the old fixture failed before
   transfer tests ran. Tests now build their literal loopback URL explicitly and
   separately verify that a hostname never broadens the HTTP exception. Production
   HTTPS validation was not relaxed.
2. An initial follow-up incorrectly assumed MockWebServer would preserve a supplied
   hostname on Windows. The full run reported 2,970 tests, one failure and one skip.
   The fixture now explicitly constructs the hostname case, independent of DNS.
3. That failed Gradle run nevertheless returned exit code zero through the Windows
   wrapper: `%ERRORLEVEL%` was expanded before the parenthesized `call` ran. Moving
   exit-code evaluation outside that block preserves failures. A Windows fixture
   verifies real wrapper exit codes 0, 7 and 42 without launching Gradle.
4. The HTTPS driver now checks structured JUnit reports as well as the launcher
   exit code. A successful transport vector cannot mask failed regressions or
   missing reports. Tooling regression tests run in Linux and Windows CI.

The original CI failure log is retained in `build/blob-pr2827-android-ci.log`.
The earlier local failed-run log was overwritten by the rerun; its directly
observed counts and failure are recorded above. The current all-Gradle log
contains the corrected run, not the earlier failure.

## Final local verification

- Full Android JVM suite with a live HTTPS fixture: 2,973 tests, 2,972 passed,
  zero failures/errors, one existing skip in AgentWorkspaceFileToolsTest.
- Kotlin/Python HTTPS transfer: 3,145,801 bytes each direction, matching SHA-256;
  each of the four Kotlin upload chunks accepted once across resume.
- App and instrumentation APK builds passed; combined run took 16m24s.
- Five tooling regression tests passed on Windows, including exit-code propagation
  and rejection of stale or ambiguous installed-App versions before device testing.
- Repository checks passed, including the CI-hook/documentation follow-up.
- 72 Android AArch64 libraries passed 16 KiB alignment checks. All 88 packaged
  native-library hashes equal 1.0.20, which does not itself prove inference latency.

Artifacts:

- `build/galaxyssi-blob-buffer-1.0.21.apk`: 410,524,075 bytes;
  SHA-256 `424D81E7555D7BA2409FA6FE3971D60A09D926C4788688B25CB1122ED5B89BA4`.
- `build/galaxyssi-blob-buffer-tests-1.0.21.apk`: 1,584,720 bytes;
  SHA-256 `26ED43CB54FA55EE53A1C785DDA35EB6075928F067B42352D3B6FBF5ED2FD843`.
- Logs: `build/android-blob-buffer-verification.log`,
  `build/android-blob-interop-all-gradle.log`, `build/blob-device-16mib-run.log`,
  `build/blob-device-152mib-run.log`, and per-run `build/blob-device-*/result.json`.

## Still required

At the original checkpoint, the user was actively using S20U and post-fix device
verification was pending. The subsequent 1.0.21 installation and same-scale
measurements are now recorded in
[S20U post-fix acceptance](android-blob-s20-acceptance-20260906.md).
The device driver requires `--expected-app-version 1.0.21` for this comparison;
it must not silently benchmark the previously installed 1.0.20 APK.

Actual paired public-network submission, production relay deployment, contact
and artifact routes, full error/progress UI, source-expiry repair and retention
remain open as described in the sender integration report. GitHub review status
is separate from these outstanding acceptance requirements.
