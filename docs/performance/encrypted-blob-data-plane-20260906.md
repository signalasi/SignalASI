# Encrypted Blob data-plane verification

Date: 2026-09-06. Base: main `01d752790`, including merged transport timing
PR #2825. Desktop source version: 1.0.15. Android source/native libraries and
the installed S20U App are unchanged in this phase.

## What was verified

The new relay/client perform actual socket-based binary uploads and downloads,
not only fixture API calls. The relay is a separate FastAPI app using temporary
SQLite storage; it is not the operator's Desktop backend or file service.

- Strict public manifest, layout, size and missing-bitmap validation.
- AES-256-GCM, unique preparation keys/nonces, full-file/hash and binding checks.
- A fixed protocol vector including Chinese UTF-8 text, known AAD and ciphertext.
- Empty file, exact 1 MiB boundary, partial last chunk, swapped chunks, changed
  source, wrong conversation/turn, wrong key, and corrupt local checkpoints.
- Ciphertext-only relay and staging; device-encrypted private checkpoints.
- Separate hashed read/write capabilities; wrong credential, creation conflict,
  expiry, revocation, quota, CAS reference retention and bounded collection.
- Concurrent duplicate writes commit once; corrupt stored chunks become missing
  and can be repaired without losing the immutable manifest.
- Lost creation/PUT responses, reopened sender/receiver stores, missing-only
  resume and a freshly instantiated relay listening on the same origin.
- A real sender subprocess calls `os._exit(73)` after two relay-accepted chunks.
  A new worker reopens its encrypted checkpoint, acquires the released OS lock,
  skips those two chunks and completes the remaining one without key changes.
- Competing workers cannot overwrite capabilities; a stale worker reloads the
  current checkpoint under the per-transfer cross-process lock.
- A private offer is available before bulk bytes, allowing future early file-card
  rendering. This is an API callback test, not an implemented Android UI change.
- Authentication before body parsing, bounded chunked request bodies,
  certificate rejection/trusted TLS roundtrip and redirect rejection.
- A stalled upload does not block a separate status request. This is not a
  measurement of production MQTT/UI latency under load.

## Final checks

| Check | Result |
| --- | --- |
| Isolated kernel/MQTT/Blob regression runner | 284 passed, 136.709 s |
| New Blob cases included above | 40 distinct tests |
| Desktop renderer tests | 16 passed, structure check passed |
| Repository guard | Passed |
| `git diff --check` | Passed |

Final Python regression used independently installed test dependencies under
`build/blob-minimum-deps`: cryptography 43.0.3 and HTTPX 0.27.0. The shared Hermes
Python environment was not changed. The host default environment has newer
cryptography/HTTPX versions; earlier runs there also passed, but the final run
above validates the APIs against the older supported direct dependency versions.
Pip printed conflicts with unrelated host packages while installing into the
separate target directory; no global package replacement was performed.

The strict certificate test initially found that HTTPX 0.27 wrapped the SSL
verification exception in `__context__`, not only `__cause__`. Validation already
rejected the certificate, but the error was mislabeled as a generic connection
failure. The client now inspects both chains and returns the specific TLS
verification failure without weakening certificate checks. Earlier Windows test
cleanup also found two test-owned SQLite handles that needed explicit closure.

## Full backend CI isolation follow-up

The first complete backend CI run failed: 18 failed and 1,370 passed. These
failures were not present in the narrower kernel/MQTT regression above. The
Windows RSS fixture changed the signature of a globally cached ctypes function,
so later Codex harness calls using another ctypes structure raised ArgumentError.
The process-recovery test also assumed the backend directory was its working
directory; CI starts pytest from apps/desktop, and the child could not import
blob_crypto.

The RSS fixture now loads an independent WinDLL function and tests that the
shared API signature remains unchanged. The subprocess explicitly selects the
backend working directory. The test runner's new --pytest mode uses the same
working directory and whole-suite ordering as CI, retaining the isolated test
HOME and state directory so tests cannot open the live Desktop database.

After these fixes, the full backend suite passed: **1,389 tests and 392 subtests,
zero failures, two existing WebSocket deprecation warnings, 399.27 seconds**.
This run used the isolated supported dependencies described above. Its log is
build/blob-ci-full-pytest.log; the original CI failure remains recorded in
build/blob-ci-backend-failure.log. This is local full-suite evidence, not a claim
that a later remote CI run or production phone transfer has completed.

```powershell
python tools/dev/test-run-kernel.py --pytest
```

## 152 MiB real loopback transfer

The final full-regression run generated a 152 MiB file, prepared 152 distinct
ciphertext blocks and transferred all bytes in both directions. HTTP compression
was disabled. The same HTTP connection was reused for manifest, block and status
requests. Blocks contained 1 MiB plaintext + 16 authentication bytes; the total
ciphertext payload was 159,385,984 bytes in each direction, excluding HTTP/TCP.

| Phase | Final run |
| --- | --- |
| Hash/encrypt/persist preparation | 1.843 s |
| Upload and relay persistence | 12.375 s |
| Download, persist ciphertext and authenticate | 6.219 s |
| Entire test including final verification | 21.156 s |
| Sampled process RSS growth over pre-transfer baseline | 9.484 MiB |
| Reused TCP connections | 1 |

RSS was sampled approximately every 5 ms in the process containing both client
and relay. It is not a strict allocation peak or Android measurement. The test
asserts bounded chunk sizes and, for 100+ MiB samples, less than 96 MiB sampled
RSS growth. Two preceding 152 MiB runs took 17.921 s and 26.063 s with sampled
growth of 8.535 MiB and 15.629 MiB respectively. These are different local runs,
not P50/P95/P99 statistics. Warm caches and host load affect the results.

The large test used explicit test-only loopback HTTP. Certificate-validating
HTTPS was tested separately with a small file and a test-only trusted certificate.
Do not report these numbers as WAN/TLS throughput, S20U performance, or the
end-to-end chat latency target.

## Reproduce

From the repository root, using a Python environment with backend requirements:

```powershell
$env:GALAXYSSI_BLOB_LARGE_TEST_MIB = '152'
python tools/dev/test-run-kernel.py
```

Without the size variable, the ordinary Blob regression uses 3 MiB. Local logs:
`build/blob-final-regression.log`, `build/blob-desktop-check.log`,
`build/blob-repository-final.log`. Large temporary files, test credentials and
relay databases are deleted by the tests and are not committed.

## Unverified / still required

- No production Desktop process was restarted or replaced.
- No App APK was installed or phone UI interrupted in this phase.
- No attachment was sent to an unknown third-party service.
- Android OkHttp encryption/download wiring and actual Agent/contact/artifact
  transport selection are not yet implemented for this Blob protocol.
- Private offers still need the existing encrypted control queue integration,
  full caller identity binding and durable recipient-receipt cleanup.
- Real paired S20U/Desktop and App/App public-network transfer, disconnect,
  reboot, expiry/repair, UI progress and simultaneous chat tests remain required.
- The overall reliability/performance goal remains active; this phase alone
  does not establish that bulk bytes have been removed from MQTT.
