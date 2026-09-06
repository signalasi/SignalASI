# S20U Blob preparation and recovery acceptance, 2026-09-06

## Device and scope

The default test device is the user's S20U, `SM-G9880`. The other attached
SM-T575 was not operated. Source main `abdae09540eb0e46a182bfdcfb1c296927b5e466`
contains the bounded-buffer correction from PR #2827.

Overwrite-installed Android **1.0.21 / 867**, replacing 1.0.20 / 866 without
uninstalling, resetting, clearing data, regenerating identity, or re-pairing.
First-install time stayed `2026-09-05 23:09:22`; update time was
`2026-09-06 17:32:09` (device local time). APK SHA-256:
`424D81E7555D7BA2409FA6FE3971D60A09D926C4788688B25CB1122ED5B89BA4`.

The fixture transfers real encrypted bytes over certificate-validated HTTPS via
ADB reverse, with the real Desktop Blob receiver and durable input ingestion.
Offer/receipt transport is a test endpoint, not paired Signal/MQTT. The fixture
uses only synthetic data and isolated state; it does not provision a production
relay or demonstrate public-network throughput or full Agent task submission.

## Same-scale measurements

Each size was tested before the fix, after the fix, and again with explicit
benchmark acceptance enabled. No low-result-only selection was used.

| APK / run | File | Preparation plus first-chunk sampled PSS growth | Preparation time | Checkpoint recovery | Remaining upload | Maximum main callback |
| --- | --- | --- | --- | --- | --- | --- |
| 1.0.20 baseline | 16 MiB | 39,436 KiB / 38.5 MiB | 197 ms | 14 ms | 4,424 ms | 5 ms |
| 1.0.21 first | 16 MiB | 7,540 KiB / 7.4 MiB | 335 ms | 15 ms | 4,357 ms | 6 ms |
| 1.0.21 gated | 16 MiB | 8,126 KiB / 7.9 MiB | 339 ms | 16 ms | 4,347 ms | 1 ms |
| 1.0.20 baseline | 152 MiB | 182,513 KiB / 178.2 MiB | 1,700 ms | 13 ms | 33,931 ms | 17 ms |
| 1.0.21 first | 152 MiB | 13,935 KiB / 13.6 MiB | 2,724 ms | 20 ms | 24,569 ms | 3 ms |
| 1.0.21 gated | 152 MiB | 14,107 KiB / 13.8 MiB | 2,722 ms | 15 ms | 25,364 ms | 8 ms |

The 152 MiB preparation/first-chunk growth fell by about 92% in these samples.
Preparation became slower by about 1.02 seconds; this is not an across-the-board
latency win. The upload figures include an injected two-second stalled chunk and
are local transport observations, not WAN estimates. There are too few runs for
P50/P95/P99 claims or a claim of a statistically controlled speedup.

PSS is sampled at 100 ms intervals. Its interrupt phase includes preparation and
the first accepted 1 MiB upload; `prepare_ms` separately ends before upload.
Shorter allocation spikes can be missed. Resume-phase sampled growth was
46,940/48,523 KiB for the two 16 MiB runs and 46,636/49,418 KiB for 152 MiB;
overall App memory is not claimed to be only the preparation buffer size.

## Functional and responsiveness evidence

- Each run killed the App process after the first accepted 1 MiB, verified that
  the process had died, then recovered the encrypted Android Keystore checkpoint
  in another instrumentation process.
- The complete 16 MiB and 152 MiB hashes matched on Desktop. Each accepted
  1 MiB chunk was uploaded exactly once, including the chunk accepted before death.
- Every run verified a 3,145,801-byte return download and matching hash.
- Certificate rejection remained active outside the fixture's scoped trusted CA.
- The two gated runs completed 47 and 335 independent control/main-thread probes,
  with zero failures. Control latency maxima were 103 ms and 97 ms; main callback
  maxima were 1 ms and 8 ms. This is not a frame-time or real chat UI benchmark.
- The test runner removed only its test package, fixture files and scoped ADB
  reverse entry, and reopened GalaxySSI. The real App and its data remained installed.
- Post-test screenshot inspection showed the existing Chinese conversation and
  completed response, not an empty/reset page. The crash buffer was empty; retained
  process-exit records matched installation, instrumentation force-stops and the
  intentional SIGKILL. This does not prove the absence of all future ANRs.

## Enforced benchmark, not a product limit

The runner previously returned success for complete transfers even when the
preparation phase grew by 178.2 MiB. It now records and enforces a **32 MiB sampled
preparation-growth benchmark budget**, with explicit `--max-prepare-growth-mib`
override recorded in the result. This reserves headroom beyond the bounded
roughly 2 MiB caller-owned buffers for ART, crypto, checkpoint and first-request
overhead while rejecting the observed size-dependent regression.

This is an engineering acceptance budget for this isolated S20U benchmark, not
an Android memory cap, file size limit, Agent action budget or user-facing refusal.
It does not unload ASR/QNN or alter scheduling. Other benchmark limits are
5,000 ms checkpoint recovery, 500 ms control probes and 100 ms main callbacks.
Missing, malformed or inconsistent evidence fails; failures and original metrics
are saved before returning nonzero. Unit regression verifies that the original
178.2 MiB result cannot pass solely because the file arrived.

Thirteen tooling tests passed on Windows, including malformed metrics, absent
probes, integrity/death/TLS evidence, deadline failures, the observed memory
regression, persisted failure reports, and Windows Gradle exit propagation.
Repository checks also passed.

```powershell
python tools/dev/test-android-blob-device.py --serial <S20U-serial> `
  --expected-app-version 1.0.21 --size-mib 152 --max-prepare-growth-mib 32 `
  --test-apk build/galaxyssi-blob-buffer-tests-1.0.21.apk
```

Local evidence directories, each retaining phase logs and `result.json`:

- Baseline: `build/blob-device-16mib-7f7d335c`, `build/blob-device-152mib-164a3891`.
- First post-fix: `build/blob-device-16mib-24fe481d`, `build/blob-device-152mib-aace45b8`.
- Gated repeats: `build/blob-device-16mib-a8bb4f4d`, `build/blob-device-152mib-6c549540`.
- Post-test inspection: `build/blob-s20-postfix-{crash,exit-info}.log` and
  `build/blob-s20-postfix-screen.png` (kept locally, not committed).

The older baseline driver did not embed `app_version`; its version is established
by the contemporaneous installation records in the earlier sender report. The
new driver verifies the installed version before test operations and records it.

## Remaining work

This closes the sampled-memory and isolated process-death retest for the bounded
preparation correction, not the whole communication goal. Actual paired Agent
submission, relay provisioning, contact and artifact routes, durable error
observations, expired-offer replacement, origin migration, retention and
deletion hooks remain incomplete. In particular, source/expiry errors currently
stay in retry journals; the user and Agent still need actionable recovery events.
No additional production code, native binaries or product version changes were
made by this acceptance-tooling follow-up.
