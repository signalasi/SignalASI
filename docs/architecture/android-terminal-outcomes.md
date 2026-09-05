# Android terminal outcome recovery

Scope: Android and Desktop, following PR #2820. iOS is deferred. The default
physical test device is S20U (SM-G9880), not the original roadmap's S26U.

## One result interpretation

Normal encrypted text replies and recovered result pages use the same decoder.
The explicit completed, failed, timed_out or cancelled state determines success;
a contradictory success flag cannot turn cancellation into completion. Legacy
replies without task_status preserve their explicit success value. Original error
text wins over a generic display message. A status-only cancellation uses the
App's localized cancellation label; it does not invent a provider error.

Desktop sends non-success terminal events through its durable result channel.
If that channel raises an exception, the status-event path remains available.
The archive can also reconstruct an outcome from the committed task, as described
in terminal-outcome-recovery.md. No model or tool is rerun to retrieve a result.

## Execution generations

The existing source/contact/conversation/turn/task identity gains a persisted
execution generation and status sequence. Generation one keeps existing encrypted
inbox identities; retries receive different identities. Remote transport still
validates the paired route and full task scope before accepting these fields.

An opaque hashed-scope SQLite table retains the newest observed generation.
Within one transaction, inbox append advances that table, saves the encrypted
body, and retires older-generation pending bodies. Insert or retirement failure
rolls back the complete append. Schema-one migration adds metadata and indexes
without rewriting old encrypted bodies or resetting App identity.

Older generations are rejected even after reopening the database. Status metadata
within a generation cannot regress its sequence. Canonical final bodies are
different: subsequent metadata updates can have a higher revision than the
already archived body, so those revisions must not suppress that body's delivery.
The remembered metadata sequence is never decreased by accepting a final body.

Discovery queries initially do not know a generation and remain eligible to ask
the Desktop. A verified observation pins all subsequent page requests, responses,
body validation and receipts to its generation. A newer observation invalidates
an older in-flight transfer at its next eligibility check.

## Runtime and presentation

Failed and timed-out outcomes enter the existing failure/fallback runtime with
their real reason. Cancellation ends the task as CANCELLED without selecting
another provider. Direct conversation results and voice interaction completion
also preserve cancellation/failure instead of displaying success.

Observing a terminal status is not the same as durably receiving its body. The
recovery coordinator keeps a waiting projection until the final outcome can be
consumed. Explicit local user cancellation remains terminal and blocks recovery.
An acknowledgement for an older handled inbox entry cannot retire a later
generation's reply. Completion suppression keys also include the generation.
UI restore and deferred-consumption keys reuse the same hashed reply identity;
queued replies are checked again against the persisted execution head before
consumption. An exception during restore releases its in-flight key.

## Boundaries still open

- The Desktop task-result outbox is still task-keyed; generation-aware CAS
  retirement remains required to close publish/replace races.
- Recovery page assembly is memory-only. Persistent encrypted partial-page
  checkpoints are not implemented in this phase.
- Inbox append is atomic, but transport, Run, transcript and managed-supervisor
  stores do not share one atomic commit. Managed response interception follows
  its existing separate ledger path.
- A generation check is not an exactly-once guarantee for external tool effects.
- Real paired process/network chaos acceptance requires the new Desktop to be
  running. Local fault injection cannot substitute for that acceptance.
- No ASR/QNN inference code, native model binaries, provider model preference or
  new cumulative action budget is introduced by this change.
