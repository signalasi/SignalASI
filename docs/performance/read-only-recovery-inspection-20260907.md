# Read-only recovery inspection

## Scope

Android 1.0.31 separates authenticated remote status inspection from recovery
application. `AndroidAgentRemoteRecovery.inspect` retains the paired route,
identity, generation, and response validation used by recovery, but does not
register execution state or request terminal-result redelivery. Normal recovery
and pending-reply recovery retain their existing behavior.

The live instrumentation query now uses this inspection entry and guards against
opening the execution inbox or result-page database through its supplied context.
This is not a claim that the background application performs no database writes:
normal MQTT processing and unrelated application work can continue independently.

## Explicit live setup

`AgentRemoteRecoveryDeviceTest#submitExplicitLiveRecoveryProbe` is separately
opted into with `live_recovery_setup=true`, a `recovery-live-` prefixed
`live_recovery_id`, and a positive `live_recovery_source`. It sends one Chinese
verification request through the paired Codex transport. It does not ask for
tools or file changes. Reusing its registered source does not submit again.

The read-only test never implicitly performs this setup. Select the query with
`AgentRemoteRecoveryDeviceTest#pairedDesktopReturnsLiveReadOnlyRecoveryObservation`,
`live_recovery_probe=true`, and optionally the exact `live_recovery_source`.
An absent task identity or missing verified reply fails the query; it is not
reported as successful recovery.

## Validation at PR creation

- 51 JVM tests passed across AgentRemoteRecoveryClientTest,
  AgentRunRecoveryCoordinatorTest, AgentTaskIdentityPolicyTest,
  AgentResultRecoveryClientTest, and AgentRecoveryWakeCoordinatorTest.
- Debug APK and instrumentation APK assembled successfully.
- S20U paired live inspection and process-restart verification remain pending.
- This change does not establish full result-loss recovery or the five-second
  end-to-end recovery target.
- No pairing reset, application-data clearing, ASR, or native-model change.
