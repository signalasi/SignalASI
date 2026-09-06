# Transport ACK timing verification

Date: 2026-09-06. Android 1.0.19 / 865; Desktop source 1.0.14.
This phase builds on durable receipts commit `5e4240201`. Before submission,
main advanced to `595e9ab02` (PR #2823 merged); the receipts branch incorporated
it as `a6355c311`. Its tree is identical to `5e4240201`, so the working branch
fast-forwarded to that base without changing the tested source. It adds local
timing evidence, not a new wire protocol.

## Automated checks

- Android JVM: 59 tests across six suites, zero failures/errors/skips. Nine new
  transport timing cases, 16 existing timing cases, and 34 result checkpoint,
  recovery and receipt cases passed.
- Isolated Desktop kernel/MQTT regression: 244 tests passed in 118.316s.
- Additional timing/broker/route/wire/probe/subscription/supervisor run: 85 tests
  passed in 0.932s. Ten new tests cover independent ACK boundaries, attempts,
  endpoint isolation, early ACK callbacks, MID/generation reuse, bounded
  diagnostics, failure exclusion, actual bridge publish hooks and fragmented
  completion. These test counts overlap with the earlier 39-test targeted run;
  they must not be added together as distinct coverage.
- Desktop renderer/structure: 16 tests and structure check passed.
- Playwright timing-panel visual fixture: 360px and 640px layouts passed;
  all 14 metric rows present, no horizontal document overflow or clipped
  numeric cells. Screenshots are synthetic layout evidence, not live timings.
- Repository check and `git diff --check` passed.
- App, JVM and instrumentation build: 10m 28s, successful. Instrumentation-only
  rebuild after the test address correction: 1m 38s, successful. Existing
  native-path and deprecated API warnings remain.

## S20U device evidence

Only `R5CN319CESA` / `SM-G9880` was operated. The separately connected SM-T575
was untouched. S20U was in Samsung Watch setup, so no Activity/UI navigation,
provider prompt or production pairing change was performed.

The first two-case device run passed the concurrent journal case and failed the
localhost MQTT case: its server used the platform default loopback address,
while its client explicitly used IPv4. The test fixture now binds `127.0.0.1`.
Production uses its unchanged certificate-validated public-broker connection.

Final new cases: **2 passed, zero skipped/failed**, runner duration 0.779s:

1. Real Paho client against an isolated in-process localhost MQTT test server.
   Publishing returns while the test broker deliberately withholds PUBACK;
   no successful broker sample exists yet. The actual delivery callback carries
   its typed attempt context and closes the broker span when ACK is released.
   It does not create a peer receipt. A separately injected, matched logical
   receipt closes that span; a wrong peer does not.
2. Four threads each register/complete 20 messages in the actual Android journal.
   All 80 distinct broker and 80 distinct peer intervals survive. These are
   synthetic diagnostic messages, not 80 public network transfers.

Two existing non-UI journal regressions also passed (0.163s): blocked disk writer
does not block producers, and separate process clock domains never join. Thus
there are **4 distinct passing device cases** for this phase. Synthetic points
were kept in test-only journals and removed afterward, not written into the
production performance history.

Captured crash buffers were empty and were not cleared before capture. The
temporary `com.galaxyssi.chat.test` package alone was removed; the main App and
its pairing, conversations and model settings were retained.

## Installed artifact

- APK: `build/galaxyssi-transport-ack-1.0.19.apk`, 410,463,123 bytes.
- SHA-256: `970D07705D25BC9497C8BBC3997BECF28CBBB3127BC9AE0EF2E7108DF601031B`.
- Final test APK: `build/galaxyssi-transport-ack-tests.apk`, 1,584,722 bytes.
- Test SHA-256: `E243DE58559FB5D25FD31375A95B75CA52BECA27896E03B08500618A6BD32CB8`.
- Installed with `adb install --no-streaming -r`.
- Package reports 1.0.19 / 865, update time 2026-09-06 11:36:56.
- Original first-install time remains 2026-09-05 23:09:22.
- Pre-install MemAvailable: 5,037,000 KiB.
- All 88 packaged native `.so` hashes match 1.0.18 exactly. This establishes
  unchanged ASR/QNN libraries, not a fresh inference performance benchmark.

Logs are under `build/transport-ack-*`; Playwright screenshots are under
`build/reports/agent-latency/`. Large local artifacts are not committed.

## Remaining acceptance

The current running Desktop belongs to another worktree and was not replaced.
Its runtime version cannot be inferred from this phase's 1.0.14 source.
Real paired phone/Desktop ACK samples and a normal Chinese provider request
remain unperformed in this phase: S20U was still in Watch setup. No new
end-to-end 20-second result or P95/P99 claim is made from localhost/unit tests.
Blob data-plane separation, real chaos/recovery acceptance and the other
reliability roadmap items remain active.
