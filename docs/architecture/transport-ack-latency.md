# Transport ACK latency

Android and Desktop extend the existing content-free Agent latency journal. This
change does not alter encryption, MQTT topics, packet sizes, retransmission
budgets, delivery ownership, or model execution. iOS remains deferred.

## Measured boundaries

Both implementations add the following metrics, prefixed `phone_` or `desktop_`:

| Metric | Start | End | Scope |
| --- | --- | --- | --- |
| `transport_queue_ms` | Encrypted message registered after local durable enqueue | First wire dispatch begins | One logical message |
| `broker_ack_ms` | Sealed packet(s) ready for MQTT publish | Broker completion callback; all fragments for a fragmented message | One publish attempt |
| `peer_receipt_ms` | Same durable enqueue registration | Authenticated peer `delivery_ack` matched by endpoint and transport message ID | One logical message |

Queue time includes waiting for connection, local dispatch capacity and wire
framing. Broker time includes Paho/network buffering and, for fragmented payloads,
the fragment scheduling window. It is not a pure network RTT or per-packet RTT.
Peer time includes queueing, retransmission, remote receipt processing and the
return encrypted ACK. It does not mean model completion, transcript persistence,
user read status, or durable final-result confirmation. The latter is the
separate [result receipt handshake](durable-result-receipts.md).

Metrics cover task-associated durable envelopes, including task control/events,
not only greetings. Direct peer chats (`peer_chat`) and envelopes without a task
ID do not enter the Agent latency distribution. Heartbeats and wire fragments
do not independently inflate the number of measured logical messages. Do not
interpret a mixed-task distribution as a simple-chat SLA.

## Attempt and callback identity

Logical operations hash a length-delimited endpoint/message pair. Every publish
attempt receives a new opaque operation ID. A previous attempt cannot complete a
new retry; a disconnected attempt ends as unsuccessful, and its callback handle
becomes inert. The logical peer-receipt interval can still span reconnects inside
the same process.

Android passes a typed attempt as the Paho token's user context, set before
publish. `deliveryComplete` records the single-packet ACK before durable queue
work, and the action listener supplies idempotent completion/failure coverage.
Fragment completion records only when all fragment acknowledgements have been
processed. Existing early-registration and retry logic remains authoritative.

Desktop single-packet callback bindings use client instance, connection generation
and MQTT MID. A bounded early-callback map retains the callback's monotonic time
when ACK precedes publish return. Stamps older than the attempt are rejected,
including after MID reuse. Fragments retain the attempt on their existing
transfer object; the final callback timestamp ends the span, before pumping more
fragments. Negative broker reason codes or publish failures are excluded from
successful percentiles.

Peer ACK matching occurs only in the existing decrypted, route-validated inbound
path. A different paired endpoint cannot close a same-ID diagnostic message.
Duplicate ACKs do not create additional samples. Receipt-before-broker-callback
is allowed; these are independent observations.

## Privacy, bounds and failure behavior

Timing registries retain only hashes, timestamps, booleans and bounded callback
keys. They have a 1,024-entry capacity per registry and a one-hour validity window
for logical-message measurement. These are diagnostic retention bounds, never
Agent action limits or transport retry budgets. Existing journal bounds and
off-thread writes remain unchanged.

This metadata is intentionally not added to the durable outbox or wire protocol.
After process death, old queued messages without a new registration have no new
timing sample. Concurrent outbox dispatch can precede best-effort registration;
that dispatch remains unmeasured. Missing/evicted starts must not be synthesized
or joined across process clock domains. Counters describe available evidence,
not proof of loss-free telemetry. Registry/sink failures must not decide whether
an application message is delivered.

All diagnostics stay local. No message text, file path, attachment bytes,
credentials or routing secrets are written or sent by this feature. Existing
hash-only trace IDs correlate to the normal task timing table; operation IDs
prevent concurrent messages and retries from collapsing into one sample.

## Acceptance boundary

Unit tests and localhost MQTT tests establish callback/metric correctness, not
public-broker performance, current Desktop deployment, paired final-result
recovery, or whole-chain P95/P99 compliance. Real phone/Desktop exchanges and
larger replay/chaos suites remain part of the active reliability goal.
