# Restart-durable transport receipts

## Defect and scope

Android previously published Desktop and phone delivery acknowledgements directly
from memory. A process exit before broker acknowledgement could lose the receipt,
leaving the sender waiting until it retransmitted the original ciphertext.
Earlier S20U force-stop tests saw small control messages accumulate in Desktop's
two-message per-route delivery window and recovery queries time out.

Android 1.0.33 records the receipt intent before dispatching the received message
to application consumers. A separate encrypted, indexed SQLite journal persists
the endpoint binding, original message ID, and prepared Signal ciphertext. Each
retry reuses that ciphertext and resolves the current rotating topic at send time.
Broker acknowledgement removes only the matching attempt; late acknowledgements
cannot delete newer work. A replay coalesces with an existing intent.

The binding includes peer type, endpoint, internal route, both fingerprints, and
the relationship key digest. Revoked/replaced relationships cannot receive old
receipts. Phone and Desktop receipts use the same mechanism. No protocol change,
pairing reset, application-data deletion, or native-model modification is required.

## Scheduling and privacy

- Existing event-driven receipt scheduling runs on an IO coroutine scope.
- Receipt queries page four records at a time and allow two in-flight receipt
  publishes, preserving capacity in the existing 12-slot MQTT client.
- Unconfirmed work remains retryable after 12 seconds. Empty queues do not poll.
- Connection/subscription readiness restarts pending work immediately; broker
  callbacks wake the worker without blocking the UI on a database write.
- Row encryption uses the existing Keystore-backed AgentRowStorageCipher;
  only opaque row keys, attempt tokens, and scheduling metadata are plaintext.
- Invalid/corrupt rows are deferred without starving the rest of a page.

## Verification

- 39 targeted JVM tests passed, including binding separation, malformed identities,
  subscription fences, early broker ACK registration, and event scheduling.
- Eight S20U instrumented journal tests passed: reopening before encryption,
  reopening with prepared ciphertext before acknowledgement, replay coalescing,
  stale ACK fencing, retry persistence, 100-record paged drain, encrypted row
  tamper rejection, and oversized ciphertext rejection. These are local journal
  reopen/fault simulations, not claims of broker fault injection.
- Five separate S20U process force-stop/live paired query runs all passed using
  the existing task; no provider prompt was resubmitted. Query times were
  3431 / 3874 / 4667 / 4545 / 6816 ms. Instrumentation totals, including its setup,
  were 5.453 / 6.026 / 6.772 / 6.678 / 8.987 seconds.
- Debug, test, and Release builds passed. All 72 AArch64 libraries passed the
  16 KiB alignment audit.
- Read-only, route-scoped Desktop inspection after foregrounding S20U showed
  three historical failed deliveries and one older published delivery; queued
  entries had drained. No queue entry was manually removed.

## Remaining acceptance

Five queries are not a P95/P99 measurement. The five-second end-to-end recovery
target, full terminal-result-loss/UI recovery, deterministic real broker ACK-loss
injection, and a second live phone peer still require broader verification.
Historical failed deliveries remain intact for diagnosis rather than being
silently retried or deleted.

Follow-up: [live final-result recovery](live-final-result-recovery-20260907.md)
now verifies a short real Codex body across process death, encrypted-inbox recovery,
visible UI consumption, and subsequent cold-start deduplication on S20U. Its small
sample does not close the broader performance, large-body, or broker-fault gates.
