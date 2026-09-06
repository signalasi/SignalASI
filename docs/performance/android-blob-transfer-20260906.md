# Android Blob transfer work in progress

Date: 2026-09-06. Base: main `3c48121ac`, including merged PR #2826 and its
full-backend CI isolation fix. That PR's required checks passed remotely.
This document records an intermediate Android implementation, not completion
of the application transport migration or the larger reliability goal.

## Implemented worker components

- `blob/BlobProtocol.kt`: the shared v1 manifest, canonical JSON, binding hash,
  missing bitmap, exact chunk layout, nonce and AES-256-GCM AAD contract.
- `blob/BlobStaging.kt`: ciphertext-only files, device-encrypted checkpoints,
  atomic replacement, exclusive file ownership, missing/corrupt chunk recovery,
  authenticated streaming output and explicit key-buffer cleanup.
- `blob/BlobHttp.kt`: certificate-verified OkHttp, bounded bodies, no redirects
  or transparent compression, shared bounded bulk slots, independent control
  requests, immediate per-transfer cancellation and normalized error codes.
- `blob/BlobTransferClient.kt`: durable creation capabilities, early private
  offer callback, missing-only upload/download, authentication before completion,
  and revocation. Relay completion is not a peer persistence acknowledgement.
- `blob/BlobChunkInputStream.kt`: lazy access to existing encrypted attachment
  chunks, wiping the previous chunk and the final buffer on close or error.
  `AgentPreparedOutboundAttachment.openPlaintext()` uses this adapter. The
  existing MQTT chunk payload format remains unchanged.

The largest production file added by this phase is below 20 KiB. Mutable key
and plaintext buffers owned by these components are erased after use. JVM
Strings and cryptographic-provider internal buffers do not offer guaranteed
zeroization; no stronger memory-erasure claim is made. Checkpoint replacement
uses a synced encrypted temporary file and atomic rename. Hard-power-loss
durability of directory entries has not been established by these tests.

## Verification

Final invocation:

```powershell
python tools/dev/test-android-blob-interoperability.py
```

Use the existing Android SDK/JDK and backend dependencies; the test does not
download or install them. This host used the isolated cryptography 43.0.3 and
HTTPX 0.27.0 dependencies already prepared for the backend verification.

- Android production/test Kotlin compilation: passed.
- Repository checks and `git diff --check`: passed. The repository check also
  reads the existing iOS structure; it does not implement or test an iOS Blob client.
- **46 JVM tests passed, zero failures/errors/skips**: 29 new Blob cases and
  17 existing attachment protocol, order, encryption, progress and presentation
  cases. Test XML is under the normal Android testDebugUnitTest results.
- The optional cross-runtime case was explicitly enabled and ran, not skipped.
  A per-run fixture path is a Gradle test input, so a cached result cannot count
  as a newly completed exchange. The launcher also requires the Kotlin output
  offer to exist and independently checks the Python receiver result.
- Python's real relay and client exchanged **3,145,801 bytes in each direction**
  with the Android Kotlin worker classes running on the JVM over loopback HTTPS.
- A generated, one-day test CA is trusted only by those test clients. The
  default Kotlin client rejected it. No system trust store was changed.
- Kotlin cancelled upload after the first accepted chunk, closed/reopened its
  checkpoint, and resumed. Python's relay observed exactly one successful PUT
  for each of four chunks: `[1, 1, 1, 1]`. Both receivers authenticated and
  verified the full-file hash. Chinese binding values also matched across runtimes.
- Test-only keys, capabilities, source files, relay database and listener are
  confined to a new temporary directory and disposed by the launcher.
- Logs: `build/android-blob-unit.log`, `build/android-blob-interop-gradle.log`,
  and `build/android-blob-interoperability.log`.

The final Gradle run took 7m17s, including recompilation. It warned that the
host's default 2 GiB Gradle heap was low, but completed successfully. No App
memory or ASR/QNN policy was adjusted to address a build-tool warning.

## Remaining integration and acceptance

The new worker is **not yet selected by the live chat/Agent transport**. There
was no APK installation, production Desktop replacement, model invocation,
phone operation, or pairing change in this phase. S20U was not connected to ADB;
the separately connected SM-T575 was untouched. JVM storage tests use a scoped
test key, not the phone's real Keystore. These results are not phone/WAN
throughput, UI latency, P95/P99, or whole-system chaos evidence.

Next required work, without replacing the requested end state:

1. Deliver relay configuration only through a verified paired control channel;
   keep provisioning credentials out of status lists, model context and logs.
2. Bind offers to the actual execution/attachment identity, durably save them
   before acknowledging control messages, and publish only small private offers
   inside Signal encryption. Do not place bulk bytes in those MQTT envelopes.
3. Add a durable Android/desktop transfer coordinator with independent workers,
   bounded scheduling and restart/network recovery. Never perform HTTP transfers
   on UI or MQTT receipt threads. Preserve the current peer-stored receipt as
   the condition that releases an attachment-dependent Agent task.
4. Wire Agent input, contact and generated-artifact paths, progress presentation,
   and confirmed cleanup. Add the release version increment with that integration.
5. Validate actual paired S20U/Desktop and App/App transfer, process death,
   disconnect, large-file resume, simultaneous normal chat, and absence of UI/ASR
   regressions. A reachable configured relay is required for public-network tests.

The overall goal remains active. This intermediate checkpoint must not be used
to claim that all attachments have already been removed from MQTT.
