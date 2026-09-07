# Pairing confirmation and first-message readiness

Android and Desktop: 1.0.24.

## Correctness boundaries

- Queuing a pairing claim is not successful pairing. Android shows a waiting
  message and creates the verified contact only after a validated confirmation.
- A pairing token remains bound to one identity and route. An authenticated
  rejection on the attempted relationship stops retries and explains that a
  fresh QR is required. Expiry also surfaces a failure instead of failing silently.
- Desktop refreshes a displayed QR after a new pairing consumes it. Gateway
  polling runs every two seconds only while the QR section is visible, using
  the local backend, not MQTT. Concurrent QR/status requests are coalesced.
- Desktop holds the confirmation until SUBACK accepts that relationship's
  receive topics. Other devices' subscriptions cannot unlock this confirmation.
  A replayed claim is idempotent and cannot reset the peer Signal session.
- Android bootstraps Signal for a newly paired route even if an old route had
  a session. Confirmations replayed for an already paired route preserve the
  active ratchet. Authenticated traffic that already established the new route
  also preserves its session.
- Confirmation wakes the existing reliable outbox and updates secure readiness.
  Existing messages, contacts, identities, ASR, QNN, and transport encryption
  are not cleared or modified as part of this fix.

## Regression coverage

Backend tests cover delayed/rejected SUBACK, independent phones, replayed claims,
revocation before SUBACK, reconnection and authenticated rejection of a used QR.
Renderer tests execute the actual QR refresh functions with an isolated backend
stub. Android tests distinguish new-route bootstrap from confirmation replay.

The reported S26U incident cannot be conclusively attributed to a particular
race without its device log. These are code-proven gaps matching that symptom;
unit/integration results are not a substitute for S26U end-to-end verification.

Validation on this branch:
- Python pairing/protocol/subscription suites: 46 tests passed after removing
  repeated inherited/imported test executions from the new suite.
- Android protocol and confirmation policy: 40 tests passed; Kotlin compilation passed.
- Renderer QR lifecycle: 6 tests passed.
- Opt-in public broker probe: passed against broker.emqx.io:8883 with certificate
  validation. The first confirmation was deliberately ignored, the claim was
  replayed, and the immediately sent opaque probe received its receipt in 782 ms.
  This validates real MQTT transport, not Android UI or inner Signal ratchets.
- Repository, Desktop structure, Kotlin size and whitespace checks passed.
- No phone installation, pairing reset or production Desktop restart performed.

Run the live probe only with isolated state and explicit
`GALAXYSSI_LIVE_PAIRING_TEST=1`. Its identities and payload markers are random;
it does not use production contacts or stored conversation content.
