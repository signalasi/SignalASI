# Desktop recovery-stage timing

Desktop 1.0.32 adds four recovery measurements to the existing latency journal
and P50/P95/P99 view. These measure recovery operations, not model execution.

| Metric | Scope | Completed sample |
| --- | --- | --- |
| `desktop_recovery_lookup_ms` | One validated task status lookup and observation construction | A matching task with a known status was found |
| `desktop_recovery_page_ms` | One archive read, including lock wait, database access, decryption and page preparation | The requested archive page is ready |
| `desktop_recovery_restore_ms` | One attempt to repair a missing terminal archive from an existing task outcome | The repair returned success |
| `desktop_recovery_publish_ms` | One synchronous recovery response publish call | The publish function returned without raising |

A completed publish sample does **not** prove broker acknowledgement, delivery,
phone persistence or visible UI output. A status batch contributes one publish
sample, bound to its first validated item, rather than one duplicate sample per
item. Each lookup in that batch has its own lookup sample.

Every invocation receives an independent operation identifier. A missing page,
successful archive repair and subsequent page reread remain separate attempts,
even when the request nonce and page index are unchanged. Missing outcomes and
exceptions are unsuccessful samples; a successful lookup of a failed remote
task is still a successful lookup. Existing aggregation distinguishes completed,
failed and incomplete samples.

Timing uses the Desktop monotonic clock and the existing bounded, asynchronous
latency journal. Task and operation identifiers are hashed by the tracer. No
message text, attachment body, result digest or exception text is added to these
events. Invalid measurement metadata or a diagnostic sink failure must not alter
the underlying recovery operation or replace its exception. Existing route and
identity validation still occurs before archive access or task lookup.

## Verification

Backend regression tests ran with a fresh `GALAXYSSI_STATE_DIR` selected before
imports, isolated from the running Desktop and paired devices:

- 99 tests and 88 subtests passed across recovery timing, latency, transport
  timing, recovery queries, result archives, receipts, terminal outcomes and MQTT
  Agent recovery.
- Four latency renderer tests passed, including Chinese labels and all new rows.
- `npm.cmd run check` passed: 29 UI tests and Desktop structure validation.

The new tests cover monotonic duration, repeated attempts, identity/generation
isolation, unavailable versus successful lookup, original exception preservation,
diagnostic failure isolation, privacy and actual encrypted archive reads.

No shared Desktop restart or Android installation was performed for this change.
This is instrumentation, not a measured latency improvement. Phone-side request,
receipt persistence and UI spans, live cross-device timing, and representative
P95/P99 recovery acceptance remain follow-up work. The existing small real-device
recovery runs must not be presented as percentile or overall-goal acceptance.
