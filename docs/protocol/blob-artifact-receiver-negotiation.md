# Blob artifact receiver negotiation

This output data path is under development. Production Android does not yet
advertise the capability; the existing input upload capability is unchanged.

## Capability declaration

After initializing its durable artifact receiver, a phone may send this payload
inside the authenticated Signal application envelope to its paired Desktop:

```json
{
  "type": "artifact_blob_capability",
  "version": 1,
  "revision": 1,
  "enabled": true,
  "client_route_id": "<current opaque route>",
  "desktop_id": "<paired Desktop ID>",
  "desktop_fingerprint": "<paired Desktop identity SHA-256>"
}
```

`version` must be integer 1, `revision` an integer in 1..2^53-1, and `enabled`
a JSON boolean. The phone must durably increment its revision when changing
receiver availability. A later disabled declaration prevents new output
negotiation; it is not permission to erase already accepted work or source files.

Desktop stores the declaration in the encrypted per-pair configuration, bound
to route, Signal sender, phone fingerprint and Desktop fingerprint. A replaced
identity cannot inherit an earlier declaration. Revocation removes the record.
Identical revisions are idempotent, conflicting equal revisions are rejected,
and delayed lower revisions are consumed without rolling back newer state.

The declaration does not change Relay credentials, input-upload opt-in, or
enable a Relay. Output dispatch must require this declaration and an enabled
Relay configuration separately. Requesting an input-upload configuration is
not evidence that the phone can receive task outputs.

## Persistence and ACK ordering

Desktop handles the declaration after Signal authentication and sender binding,
before recording the ciphertext replay mapping, claiming the logical message,
or emitting a transport ACK. Configuration persistence failure leaves the
message unacknowledged and does not create a success replay entry. The payload
is consumed as control data and never dispatched to an Agent/chat handler.

`artifact_blob_receipt` follows the same pre-ACK boundary. It transitions an
existing `blob-output/artifact-jobs.sqlite3` job to cleanup only when the receipt
matches the immutable transfer manifest, the envelope conversation, current
route, Signal sender and both paired fingerprints. A receipt cannot create a
job or a bulk worker. Unknown, stale or mismatched receipts are consumed without
changing source ownership; their transport ACK acknowledges only that the
control packet was handled, not that artifact storage was validated.

A valid receipt is committed before its transport ACK. Duplicate receipts
preserve an existing cleanup claim. Database failure propagates before replay
binding/acceptance, allowing retry. Startup worker recovery and source ownership
commit remain separate from the incoming transport callback, so Relay cleanup
cannot block the MQTT receive thread.

## Current verification boundary

Tests exercise the actual `mqtt_bridge.on_message` path with authenticated
opaque transport envelopes and isolated SQLite/secure configuration files;
Signal decryption is replaced by a fixture in these dispatch tests. They cover
write failure, repeated/late declarations, foreign identity/conversation/hash,
and normal chat with no Blob store side effects. These tests do not establish
Android-to-Desktop end-to-end deployment or public Relay performance.
