# Durable final-result receipts

Android 1.0.18 / Desktop 1.0.13 extend final-reply recovery with an application
receipt handshake. Broker acknowledgement is not proof that Desktop committed
the phone's receipt, and a UI callback is not proof that the phone saved a reply.

## Commit and handshake

1. A normal or page-recovered reply reaches the same Android response bus.
   After existing admission and managed-response handling, the encrypted inbox
   commits its reply and receipt intent in one SQLite transaction. A receipt
   insertion failure rolls back the reply and execution observation together.
2. Receipt metadata contains only Desktop identity, the seven execution fields,
   generation and canonical result digest. No reply body or rich output enters
   the retry queue. Metadata is Keystore encrypted with database/receipt AAD.
3. The worker claims up to 32 due records. Each of the two active states uses an
   ordered index seek; at most 64 candidate records are merged in memory, avoiding
   a temporary sort over the whole backlog. A claim persists its next retry before
   attempting publication. Neither publication success nor broker ACK retires it.
4. Android sends `agent_task_result_received`, adding a stable `receipt_id` to
   the existing scoped digest receipt. This ID is SHA-256 of an ordered JSON
   array containing Desktop, generation, digest and the seven identity fields.
5. Desktop commits archive acknowledgement and body-page deletion before sending
   `agent_task_result_receipt_confirmed`. The confirmation echoes only the seven
   fields, generation, digest and receipt ID. It has no model/tool invocation.
6. Android checks the authenticated Desktop, current paired route, full receipt
   ID and metadata before marking the intent confirmed. Confirmed cleanup work
   is itself durable. The worker clears matching page checkpoints, then retires
   the intent body, retaining an opaque tombstone to prevent resurrection.

The Desktop archive retains its scope/digest tombstone. If the confirmation is
lost or either process restarts, Android retries the same receipt and Desktop
can confirm it again without retrieving a body or re-executing an Agent. Requests
without a receipt ID retain the older archive acknowledgement behavior, but only
the new handshake can retire the new Android journal.

## Scheduling and failure isolation

The receipt coordinator is independent of reply observation, model requests and
the UI dispatcher. It wakes on connection/foreground events, a newly committed
receipt, a confirmation or the earliest persisted retry deadline. An empty queue
sleeps without polling. Offline events are coalesced; only one drain runs at once.

Retries back off from five seconds to five minutes. The attempts counter saturates
only to bound backoff arithmetic; it is not a delivery budget and never causes a
receipt to be discarded. Claim updates use compare-and-set state/attempt/deadline
checks so a stale sender cannot regress a concurrently confirmed receipt. One
failed publication or corrupt encrypted record does not block unrelated work.
Unreadable rows are quarantined; replay of the verified original result can
repair its receipt without restoring an already-handled chat message.

## Storage lifecycle

Inbox schema version 3 adds `result_receipts` with an indexed due queue. It leaves
existing inbox rows, keys, paired identities and execution generations unchanged.
States are awaiting confirmation (0), confirmed/cleanup pending (1), quarantined
(2) and retired (3). Pending/confirmed bodies have no count or age eviction.
Retired rows retain opaque identifiers only. An explicit inbox/App reset clears
this table; merely displaying/acknowledging the chat message does not.

A crash after inbox commit but before scheduling leaves the durable intent for
the next wake. A crash after Desktop acknowledgement but before confirmation
reuses the archive tombstone. A crash during Android checkpoint cleanup leaves
confirmed work for an idempotent retry. Inbox and checkpoint databases remain
separate, but cleanup intent no longer relies only on an in-memory callback.

## Boundaries

This applies to normal and recovered user-facing replies persisted by the
connector inbox. Managed/specialist replies intercepted into a different ledger
are not falsely acknowledged by this journal; that ledger's acceptance lifecycle
still needs integration. Old inbox rows without retained receipt metadata cannot
be retroactively assigned a digest. Cancelled/deleted scopes that never acquired
an inbox receipt can still need separate checkpoint lifecycle cleanup.

Tests must distinguish real SQLite/Keystore and process-death tests from live
broker/provider exchange. A storage fixture does not establish whole-system
recovery latency or a live paired handshake. No changes to ASR/QNN or iOS are
required by this protocol addition.
