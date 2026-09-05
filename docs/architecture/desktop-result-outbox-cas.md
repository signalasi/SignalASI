# Desktop task result outbox isolation

Desktop 1.0.12. Android's generation-aware receiver is covered by the preceding
terminal-outcomes change. This phase does not modify Android, iOS or native models.

## Ownership and identity

The logical outbox now uses an opaque, device-key-derived scope over all seven
execution fields: client route, conversation, task, turn, contact, source message
and agent. `execution_generation` is a positive JSON-safe integer. The MQTT result
message ID includes the complete scope and generation, not just route/task.

Each accepted result has an independent random revision. Sending workers capture
that revision, then perform network work without holding a database transaction.
Handoff updates require matching scope, generation and revision, and a pending
state. An older worker finishing after a new retry therefore cannot remove the
new reply. A pre-publication check also skips already superseded snapshots; a
generation change during network I/O can still send an old packet, which Android
rejects using its persisted generation fence.

The first pending body is canonical for that generation. Duplicate callbacks do
not overwrite it. Older generations are rejected even after handoff: the opaque
head remains while the handed-off encrypted payload and envelope are cleared.
Head records are removed on explicit route revocation, not an arbitrary age TTL.

Automatic callbacks cannot resurrect a handed-off generation. The existing
operator replay endpoint is explicit: it can create a new revision and wire
message ID for the current generation, including recovery from an exhausted
ciphertext. It cannot revive an older generation. If a canonical body is still
pending, replay uses that body instead of a later rendering.

## Storage, migration and bounded replay

The existing encrypted delivery SQLite database receives a new indexed table.
The secure-storage version and device key are unchanged. Existing encrypted
logical replies migrate transactionally, at most 32 per recovery pass. Enqueue
first migrates any legacy row with the same task ID to preserve its canonical
body. A failed insert/delete rolls back the entire migration.

Unreadable or identity-mismatched legacy/current rows retain their ciphertext
and enter quarantine. Recovery continues for healthy rows. Logs report the
condition without the plaintext body. This phase does not add a quarantine UI.

Pending reads return at most 32 records using an index on state, last attempt,
creation time and scope. Deferred rows rotate to the back, so an offline route
does not monopolize every pass. This is a record bound, not a byte-memory limit;
a single large reply can still require its full plaintext body in the worker.
Body decryption runs after the writer transaction closes. Corruption quarantine
also uses a revision comparison, so an old damaged snapshot cannot quarantine
a newer valid result committed while the old body was being decoded.

Unsent logical results no longer expire after seven days or disappear on broker
epoch changes. Broker-bound ciphertext cleanup retains its existing behavior.
Fresh-store initialization rechecks its version under a SQLite writer lock so
concurrent first-open cannot reset a store initialized by another worker.

## Handoff is not end-to-end acknowledgement

Queued, sending or published ciphertext means the existing durable transport has
taken ownership. Failed ciphertext does not: the logical result remains without
an automatic retry loop against the exhausted packet. Other routes continue.

A successful publish can also retire the observed logical revision even if a
fast phone acknowledgement already removed the ciphertext. This does not claim
that the phone rendered the reply. The separate encrypted final-result archive
and its phone receipt remain responsible for end-to-end result recovery.

Archive, logical outbox, transport and phone transcript are not one transaction.
Cross-store atomic acknowledgement, persisted partial-page checkpoints and live
paired chaos remain separate unfinished requirements. In particular, clearing a
broker epoch after transport handoff still relies on the result archive for
recovery; this phase guarantees retention of results not yet handed off.

## Verification

`tools/dev/test-run-kernel.py` uses an isolated Desktop state directory and runs
the new outbox tests with the existing execution/MQTT regressions. Tests include
stale completions, seven-dimensional identity separation, revision ABA, rollback,
corruption, encrypted migration, concurrent writers, explicit replay, and actual
subprocess exits before/after handoff. Bridge unit cases mock the MQTT publisher;
they are not counted as live broker, phone or commercial-provider verification.

`tools/dev/benchmark-task-result-outbox.py` writes 10,000 results through the real
encrypted outbox API, reads all scopes through bounded pages, checks persisted
count, and asserts the query uses the pending index. It deletes only its own
temporary test directory. It is not a phone conversation-list benchmark.
