# Recovery subscription readiness

## Observed failure

On S20U (SM-G9880), a read-only paired recovery request after application
force-stop was queued at 09:29:21.028 while subscription completion was logged
at 09:29:21.228. The query failed at 09:29:29.034. Another query of the same
existing task had completed in 5,641 ms. These observations prove that recovery
could publish before receive subscriptions were acknowledged; they do not prove
that this race explains every observed timeout on the public broker.

## Change

Android 1.0.32 explicitly separates MQTT connection from request/reply readiness.
Readiness requires the current subscription generation to finish successfully.
Initial state, new subscription generations, failed subscriptions, and disconnect
are not ready. Stale callbacks cannot restore readiness.

Recovery and final-result receipt workers retain pending wakeups while the
subscriptions are unavailable. SUBACK completion wakes the workers; merely
connecting the socket no longer starts recovery. Foreground and secure-channel
events use the same readiness predicate. This does not block ordinary message
queueing, pairing bootstrap, ASR, or model inference.

Remote status inspection checks readiness before publishing. Debug diagnostics
distinguish publish rejection, response timeout, authenticated remote unavailability,
and authenticated response, without logging message content, route identifiers,
keys, or request identifiers. Release does not emit these diagnostics. The
eight-second response wait and identity validation are unchanged.

## Acceptance

- 43 targeted JVM tests passed, including subscription generation fencing,
  coalesced wakeups across subscription rotation, result receipts, and distinct
  query outcomes. Debug and instrumentation APKs built successfully.
- Five S20U force-stop/query runs reused the existing task without provider
  resubmission: three passed in 3,026 / 3,566 / 2,666 ms of query time; two failed
  with `response_timeout`. All five started after subscription completion.
- Release 1.0.32 built successfully; all 72 AArch64 libraries passed the 16 KiB
  audit. Release installation preserves pairing and application data.

The remaining failures are not remote `unavailable` observations. A subsequent
read-only Desktop queue inspection found two published messages awaiting delivery
confirmation and three queued messages (roughly 1-2 KiB each), including queue
ages over one minute. Recovery responses currently have normal priority, and
Android transport delivery receipts are ephemeral publishes, not restart-durable
receipt intents. Receipt loss during process death and resulting queue backpressure
need further fault-injection verification and correction. No queue was cleared.

Full result-loss recovery and a five-second end-to-end recovery target remain
unaccepted; these five samples are not a P95 measurement or a reliability pass.
