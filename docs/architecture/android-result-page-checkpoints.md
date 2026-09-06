# Android final-reply page checkpoints

Android 1.0.17 adds encrypted restart checkpoints to the existing canonical
final-reply recovery protocol. Desktop wire protocol and page size remain
unchanged. This is not another Agent execution path and never starts a tool or
model request.

## Transfer lifecycle

The verified recovery observation still supplies desktop identity, all seven
execution identity fields, generation and expected terminal status. The existing
paired-link, pending-delivery and current-execution checks remain mandatory.

1. Open the checkpoint for this desktop/scope/generation on the IO dispatcher.
2. Load its encrypted manifest if present, then verify saved pages independently.
3. Request only missing/unreadable pages using fresh request nonces and the pinned
   result digest. Validate page index, manifest, byte length and SHA-256 before
   writing a page.
4. Commit each page and the initial manifest in one SQLite transaction. A process
   stop after a committed page no longer forces previously saved pages to download.
5. Once all pages exist, reassemble and verify the full digest, JSON envelope,
   execution identity, generation and expected terminal status.
6. Publish through the existing response bus, which persists the encrypted inbox
   before UI notification. Only a recorded inbox result permits checkpoint
   deletion and the remote archive receipt.

Cancellation, offline publication failure and page timeout preserve successfully
committed pages. A newly superseded execution stops before writing its in-flight
page; existing generation admission remains responsible for rejecting old replies.
No independent scan of every checkpoint is added at startup. Existing recovery
wakeups discover pending executions and resume their matching checkpoints.

## Storage and privacy

`agent_result_pages.db` is private App storage, included in the existing explicit
App reset's database enumeration. It is not a cache directory or a Download file.
No key alias, pairing, identity, existing inbox schema or backup policy changes.

Each scope is an opaque hash of the desktop, generation and all seven identities.
Both manifest and page body are encrypted with the existing Android Keystore
AES-256-GCM storage cipher. AAD binds the database namespace, scope, result digest
and page number. Swapping encrypted pages across scopes or indices fails
authentication. Each database cursor reads at most one page-sized encrypted body.
Foreign keys cascade page deletion, and WAL uses the same FULL synchronous mode
as the response inbox.

A corrupt page is discarded using both its encrypted-value snapshot and manifest
snapshot, so an older corruption observation cannot delete a replacement. A
different valid manifest cannot be mixed into the same checkpoint. An unreadable
manifest can be replaced only when the authenticated network client has already
validated a new page; replacement and its first page commit atomically.

During network waits, saved plaintext pages are not accumulated in an in-memory
reply buffer. Byte arrays used for page validation and final assembly are wiped.
Base64/JSON strings used by the existing cipher API are short-lived and not kept
in a shared cache. Full JSON decoding still temporarily requires the complete
reply in memory; this phase does not claim a streaming JSON parser or zero-copy
plaintext processing.

## UI and acknowledgement

Database access, checkpoint cleanup and receipt publication run on Dispatchers.IO.
The UI-facing acknowledgement entry copies only identity fields and the digest,
not the complete reply or rich output. Validating a transfer does not by itself
delete its pages; transport acknowledgement is not proof of inbox persistence.

Checkpoint and inbox databases are still separate transactions. A stop after
inbox commit but before checkpoint cleanup can leave redundant encrypted pages.
This phase guarantees restartable partial download, not cross-store atomic
cleanup or complete lifecycle garbage collection for cancelled/deleted tasks.
Those remain explicit follow-up work in the larger recovery goal.

## Tests

JVM tests exercise interruption, missing middle pages, timeout/cancellation,
manifest pinning, stale nonces, superseded executions, invalid pages, persistence
failure, final digest validation and complete offline assembly.

S20U instrumentation uses isolated database names and the real Android Keystore
and SQLite APIs. Optional save/recover phases take `result_checkpoint_phase`
(`save` or `recover`) and `result_checkpoint_id`, and must run on opposite sides
of an explicit App force-stop. The publisher is a local authenticated-response
fixture: this does not count as a real broker/paired Desktop chaos test or as a
device power-loss test. Existing page request/response authentication remains
covered separately by the recovery regression suites.
