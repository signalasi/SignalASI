# Android recovery-stage timing

Android 1.0.36 adds recovery measurements to the existing bounded, asynchronous
latency journal and on-device P50/P95/P99 view. It does not change routing,
timeouts, retry policy, message delivery, model selection, ASR or QNN execution.

| Metric | Scope | Completed sample |
| --- | --- | --- |
| `phone_recovery_query_ms` | A status batch, from publish preparation through correlated response | Response received with no unavailable item |
| `phone_recovery_page_ms` | One page request, including publish preparation and response wait | An authenticated, identity/version-matched ready page response was received |
| `phone_recovery_body_ms` | Entire fetch, cached page reads, writes, reconstruction and validation | Full body passes digest, identity, generation, terminal status and content validation |
| `phone_recovery_checkpoint_ms` | One encrypted page checkpoint write | The storage write returned success |
| `phone_recovery_publish_ms` | Recovered outcome publication to the existing response bus | The publish call returned without throwing |

Page response success alone is **not** proof of a valid body: a corrupt page can
complete its request round trip while the body fails validation. Publish-call
completion is **not** a persistence or visible-output acknowledgement: the bus
may use a managed consumer, reject a stale outcome, or use the encrypted inbox.
The existing delivery/consume/transcript/draw stages remain separate.

Each invocation uses a fresh hashed operation identifier and a hashed task ID.
Repeated pages and retry attempts cannot be merged into one artificial success.
A batch has one round-trip measurement, not one duplicate measurement per item.
Cached pages produce no network or write samples. The body duration includes its
child operations; these overlapping durations must not be summed.

Uncompleted operations are counted separately. Publish rejection, unavailable
results, validation failure, checkpoint failure, timeout and coroutine
cancellation do not contribute successful latency samples. Timeout and
cancellation outcomes retain their distinct event labels. A failed remote task
can still be successfully looked up and its terminal result successfully fetched.

No message text, attachment content, digest, request nonce, exception text or raw
task identity is written to the timing journal. Failure in diagnostic emission
must not change the recovery result or mask its original exception. Local-only
privacy checks and invalid request rejection remain before measurement/network
work. Monotonic clock domains remain process-local; measurements from different
processes must not be joined using wall-clock subtraction.

## Verification scope

`AgentRecoveryTimingTest` exercises exact durations, close idempotence, independent
attempts, incomplete samples, diagnostics failure, query rejection/timeout,
cancellation cleanup, batch accounting, local-only rejection, corrupt page versus
validated body, failed checkpoints, cached fetches and original publish errors.
Existing recovery client and latency tests are included in the regression run.

Verification on the change:

- `AgentRecoveryTimingTest`: 12 passed.
- `AgentRemoteRecoveryClientTest`: 8 passed.
- `AgentResultRecoveryClientTest`: 13 passed.
- `AgentLatencyTraceTest`: 16 passed.
- Android Debug Kotlin/Java and unit-test compilation passed; the Gradle run
  completed in 7 minutes 33 seconds with 49 tests, no failures or skips.
- `node tools/dev/check-repo.js` and `git diff --check` passed.

The S20U was inspected read-only and still had version 1.0.34 (878); its first
installation timestamp remained 2026-09-07 00:28:13. No installation, reset,
pairing change, or shared Desktop restart was performed for this change.

This change alone does not establish real-network percentiles or the end-to-end
recovery performance target. S20U live recovery tests and the existing
process-death/visible-result test need to be repeated on an integrated build that
also retains the pending background-outcome fix from PR #2845. Do not install a
main-only build over the currently tested S20U build if it would remove that fix.
Do not clear user data or pairing to obtain successful measurements.
