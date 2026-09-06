# Blob failure observations

Android and Desktop treat a terminal attachment failure as an Agent observation,
not as a successful upload, an indefinitely retryable network error, or evidence
that the selected model is unavailable.

## Durable state transitions

- Desktop persists a scoped `input_attachment_receipt` with `status=failed` and
  a validated `error_code` before publishing it through the encrypted control
  channel. Restarting retries that receipt, without downloading the failed Blob
  again. Pairing and fingerprint checks still apply to receipt delivery.
- Android persists upload failures and authenticated failure receipts in its
  encrypted outgoing journal. Its failure phase remains pending until the
  observation reaches the encrypted Agent inbox or a durable attachment-recovery
  response. Only then may cleanup revoke the old relay object and remove staging.
- A failure does not release attachment-dependent task requests. Their obsolete
  transport records are discarded; the Agent observes the failure and decides
  what action to take next.
- A successful receipt, failure receipt and cancellation fence late worker
  callbacks. A duplicate receipt does not replace the first committed outcome.

## Agent integration

Initial-upload failures use the existing durable connector response inbox with a
typed `delivery_failure_code`. This avoids the legacy service/UI failure-listener
race that marked a request terminal before the Agent could consume its failure.
Source message, contact, conversation, task and turn must match the pending run.
Cancelled, terminal and superseded requests cannot be revived by a late failure.

The Android execution loop passes the verified failure to its planner without
penalizing model availability or automatically switching providers. Desktop
attachment recovery returns the same observation to both the Codex continuation
and generic Agent recovery paths. Model output and transport observations remain
distinct; no hidden reasoning content is fabricated by the framework.

## Fresh recovery identity

For an initial transfer, the canonical transfer identity remains the SHA-256 of
the route, conversation, task, turn, attachment and content digest joined by a
NUL separator. A recovery request appends its `attachment_request_id` to that input.
Repeated deliveries of one request are idempotent; a new recovery request gets a
new transfer ID, staging object and relay capability. It cannot reuse an expired
Blob or accept a receipt from the previous attempt.

The task attachment-count check counts distinct logical attachments rather than
recovery attempts. Content size, attachment binding, hash and authenticated
encryption checks are unchanged.

## Failure classification

Terminal codes are shared by Android `BlobFailureContract` and Desktop
`blob_failures.py`: expiration, missing source/Blob, changed source, authentication
or hash/size mismatch, binding mismatch and corrupt local chunks. Only bounded
codes and user-facing descriptions enter observations. Relay credentials, signed
URLs, private paths and raw exception messages are not copied into receipts.

Connection errors, relay capacity errors, cancellation and `chunk_not_ready`
retain their existing retry/checkpoint behavior. HTTP status alone does not
determine terminality: a not-yet-uploaded chunk may return a retryable 404.

## Validation scope

The regression suites exercise a real local HTTPS relay, receiver process death,
missing-chunk resume, expiry, corrupted ciphertext, receipt replay, fresh recovery,
strict scope checks and encrypted Android checkpoint/inbox recreation. The Android
runtime test injects a planner to inspect its observation boundary; it does not
claim that a live provider has autonomously completed a replacement transfer.

These tests do not establish public-network throughput or complete the broader
multi-device, real-provider chaos acceptance. Existing native ASR/QNN libraries
and their scheduling paths are not changed by this phase.

Measured S20U results and build provenance are recorded in
[the validation report](../performance/blob-failure-recovery-20260906.md).
