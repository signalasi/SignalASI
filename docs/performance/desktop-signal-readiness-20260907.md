# Desktop encrypted transport readiness and recovery

## Observed failure

During S20U Blob capability validation, Desktop's `/health` reported a ready
MQTT bridge while the checkout lacked its Signal JVM sidecar distribution.
The renderer treated broker connectivity as online status. The encrypted
control path could not work until the runtime was built, despite the green
connection indication. This is distinct from Relay provisioning or broker ACK
latency.

## Change

- Backend liveness remains `status: ok`; top-level `ready` now requires both
  MQTT/subscription readiness and a fresh successful Signal sidecar observation.
- `signal_sidecar` reports readiness, supervision, observation age, a bounded
  error code and recovery attempts. It never reports exception paths or secrets.
- The health handler reads a cached snapshot, not the sidecar HTTP endpoint or
  its startup lock. An observation that becomes stale cannot remain ready.
- A lifecycle-owned background supervisor observes the local sidecar every two
  seconds and retries startup when unavailable, including when missing runtime
  files are subsequently provided. No periodic MQTT message is added.
- Sidecar health probes use a 500 ms request timeout; existing signing/encryption
  requests retain their 20-second timeout. Normal healthy probes do not restart
  a process or reload the identity store.
- Shutdown joins the supervisor before stopping its owned sidecar. On Windows,
  launcher termination now waits for exit, and failed termination retains the
  owned handle instead of allowing a second untracked process.
- Desktop IPC and its online badge use the combined ready field, not just broker
  connectivity. Desktop version is 1.0.30; this change does not modify Android.

## Evidence

- 17 Python lifecycle/supervisor tests, including an isolated real JVM process
  death test. The test uses a fresh temporary identity store and random loopback
  port, never the running Desktop's identity store.
- Using production-default observation timing, recovery after killing the test
  sidecar took 3,703 ms. Maximum health snapshot read time during recovery was
  0.205 ms. The identity fingerprint was unchanged after restart.
- The final-source rerun also passed: recovery 3,656 ms, maximum snapshot read
  0.128 ms, same identity, and no resource warning.
- An earlier run exposed a Popen ResourceWarning. After adding explicit launcher
  reaping, reruns with ResourceWarning promoted to an error passed without it.
- 14 MQTT lifecycle, Blob capability ingress and configuration API regressions
  passed, including intentional initialization/storage failure fixtures.
- 29 Desktop Node tests and source structure checks passed, including IPC mapping
  and the renderer readiness condition.
- Repository guard passed with newly added files staged.
- Before restarting the real Desktop, its runtime reported zero active and zero
  queued runs. The new visible Desktop window started from this checkout.
  `/health` reported top-level ready, sidecar ready/supervised, MQTT connected,
  and ten active subscriptions with none missing. Existing pairing was retained.

## Limits

This is one isolated JVM recovery measurement, not a P95/P99 benchmark or a
whole-Agent-run recovery guarantee. The production phone was not deliberately
disconnected and its data was not reset. Public Relay deployment, production
large-file acceptance, and the remaining unified Agent goal are still pending.
