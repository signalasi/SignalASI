# Desktop Blob input integration checkpoint

This is an unreleased continuation of the Android Blob branch, based on merged
main `3c48121ac`. It is not completion of phone/Desktop or App/App migration.

The subsequent Android scheduling/configuration work and S20U installation are
recorded in [Android sender integration](android-blob-sender-integration-20260906.md).
The remainder of this report describes the earlier Desktop receiver checkpoint.

## Implemented path

- `mqtt_bridge` recognizes `input_attachment_blob_offer` only after the existing
  Signal envelope and paired sender checks. The encrypted input job commits
  before binding its ciphertext replay ID, claiming the message or publishing
  the transport acceptance ACK. This control type never falls into Agent dispatch.
- `blob_input_contract` bounds the control message, validates the existing input
  identity and file metadata, and pins the private offer to a locally configured
  relay origin. Arbitrary URLs in offers are not download authority.
- The exact AEAD binding is the canonical object containing `client_route_id`,
  `conversation_id`, `task_id`, `turn_id`, `attachment_id`, `transfer_id` and
  `contact_id`. File size/hash are also authenticated by Blob v1. The receiver
  journals the verified phone fingerprint separately and rechecks it before
  processing, committing and queuing a receipt.
- `blob_input_journal` uses encrypted rows, opaque IDs, SQLite WAL/FULL, indexed
  bounded due queries and claim tokens. A download can become receipt-pending
  only after the verified output is saved. The offer/key is removed from that
  journal body at this transition. Receipt failures do not trigger a re-download.
- `blob_input_receiver` owns the journal through an OS process lock. On startup
  it recovers abandoned claims, independently downloads files with a bounded
  worker pool, and retries recoverable failures without a task/action-count cap.
  A transient startup database error does not require another user message.
- Downloaded chunks remain ciphertext. A verified stream is committed into the
  existing, scoped temporary task input area without JSON/Base64 re-encoding or
  recreating the legacy 256 KiB plaintext chunk files. File and manifest writes
  are flushed before their final rename. Hard power-loss directory durability
  is not established by these tests.
- A normal `input_attachment_receipt: stored` is queued through the existing
  durable Signal outbox. Existing attachment-recovery waiters are notified.
  Only after this handoff does the worker remove its encrypted download staging
  and mark its job done. If it dies during cleanup, the receipt phase can replay.

The input manifest still carries `chunk_size_bytes` and `chunk_count` for the
existing input descriptor contract. These fields do **not** cause Blob bulk data
to be split into legacy MQTT messages. Bulk transfer uses the independent v1
1 MiB ciphertext blocks. No attachment bytes occur in the new control offer.

## Verification scope

The new test modules cover encrypted row storage and row-swap rejection, bounded
indexed claims, duplicate/conflicting offers, stale worker claims, receipt-phase
recovery, retained pairing fingerprints, actual MQTT ingress ordering, disk
failure before ACK, independent workers, authenticated HTTPS downloads, missing
chunk resume, and removal of failed temporary output. Real subprocess death
after the first downloaded chunk verifies OS-lock release and missing-only
recovery. TLS trust is scoped to a temporary test certificate, not the OS store.

Verification results:

- Isolated full backend pytest run: **1,415 passed, 395 subtests passed**, 438.66s.
  Two existing websockets deprecation warnings remain. Log:
  `build/blob-input-full-backend.log`.
- Follow-up run after the startup-journal retry and strict numeric validation
  changes: **67 targeted tests passed**, 36.862s. This includes 27 new contract,
  journal, receiver and bridge cases and 40 existing crypto, HTTP, input transfer
  and process-lock cases. Counts overlap with the full suite and are not additive.
  Log: `build/blob-input-final-targeted.log`.
- A real child receiver exits with code 73 immediately after its first HTTPS
  chunk commit. The parent recovers the running job, finishes within the test's
  five-second local deadline, and observes only one GET of chunk zero. This is
  isolated Desktop subprocess evidence, not a paired phone recovery percentile.
- Repository checks and `git diff --check`: passed. Log:
  `build/blob-input-repository-check.log`.

The first targeted run exposed unclosed test SQLite connections and an overly
broad URL exception conversion. Both were corrected before the passing runs.
No public service, production user store, phone UI or model provider is involved.

## Not yet delivered

- Android durable sender scheduling, private relay configuration delivery and
  selection of this path by the real attachment UI/Agent flow.
- Contact and generated-artifact Blob routes, UI progress and sender cleanup.
- Public-network S20U/Desktop and App/App tests, large-file/UI/ASR regressions,
  throughput percentiles and whole-system chaos acceptance.
- User-facing repair/re-offer for expired or corrupted relay sessions; diagnostic
  retry state is currently local to this new receiver journal.
- Immediate interruption of an in-flight Desktop HTTP call. Stop is observed at
  chunk boundaries; a current request remains subject to its 60-second timeout.
- Retention/cleanup of completed journal metadata and explicit transfer deletion
  hooks from conversation/task deletion.

`GALAXYSSI_BLOB_RELAY_URL` is a local receiver configuration input. This change
does not advertise Blob readiness in the capability manifest, distribute a
provisioning token, launch a production relay, replace Desktop, or install an APK.
Release versions will be incremented with the completed application integration.
