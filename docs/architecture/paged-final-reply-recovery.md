# Paged final reply recovery

Android and Desktop extend verified Run observations with a read-only pull path
for a completed reply that has not reached the phone. No model call, command,
task submission, filesystem scan, or artifact regeneration is performed by a pull.
iOS is unchanged.

## Canonical pending result

Before normal final delivery, Desktop stores the exact final text/rich-output
envelope in an immutable encrypted SQLite archive. A retry cannot overwrite the
first canonical envelope. Full seven-part scope (route, conversation, task, turn,
contact, source message, Agent) is protected by the existing device-bound storage
key; chunks are AES-GCM protected with scope/page-specific associated data.
This stores reply envelopes, not an extra copy of attachment file bytes.

An authenticated phone queries one 16 KiB result page per request. These are
**recovery-result pages, not changes to the attachment or MQTT packet sizes**.
Each request has a fresh nonce; each response repeats the exact scope and page
index, manifest size/count, full SHA-256 and page SHA-256. Archive reads select
only the requested chunk, never all task history or model output records.

The phone fetches only after a verified completed observation. It checks current
pairing, registered task identity, pending delivery, cancellation/terminal markers,
supersession and existing inbox content before and during recovery. At most two
reply assemblies run concurrently off the main thread. Timeouts bound one page
observation, not Agent execution; failures defer rather than fabricate an answer.
Temporary byte buffers are overwritten, including replaced accumulation buffers.
The assembled single JSON reply still requires memory proportional to that reply;
this is not a fully streaming renderer for arbitrarily large answers.

## Durable acceptance

The reconstructed envelope must match the requested identity and completed text
type, then use the existing encrypted connector inbox and response bus. Original
and recovered deliveries share the same inbox identity, so they cannot create a
second message. Final local cancellation/turn guards still apply at consumption.

Desktop only deletes archived body pages on a scoped `agent_task_result_received`
receipt with the matching full digest. Android commits its receipt intent with
the inbox row, not merely after a broker ACK or an in-memory callback. The
application confirmation and durable cleanup are described in
[durable-result-receipts.md](durable-result-receipts.md).
Acknowledged archive tombstones prevent resurrection by a
late publish retry. A lost receipt retains an encrypted pending copy; no pending
body is silently age-evicted. Managed replies that bypass this inbox do not yet
send this receipt and remain encrypted on Desktop until their own durable
acceptance lifecycle is integrated.

## Boundaries and verification

This follows the verified-observation PR and adds final reply recovery. Old tasks
without an archive return unavailable; they are not rerendered from raw model
output or automatically executed again. Android 1.0.17 added encrypted
[missing-page checkpoints](android-result-page-checkpoints.md), so process death
no longer forces already committed pages to download again. Full event replay,
every runtime's UI reattachment and the end-to-end five-second recovery SLO
remain separate acceptance work.

Unit tests cover exact scope, encrypted persistence, immutable output, Unicode
page boundaries, tampering, wrong/missing pages, cancellation, late/duplicate
responses, private local-only conversations, and durable acknowledgement.
S20U device tests use an isolated encrypted inbox, reopen it and verify one copy
before/after acknowledgement. These tests do not claim live paired Desktop
acceptance; that requires the new Desktop build actually running.
