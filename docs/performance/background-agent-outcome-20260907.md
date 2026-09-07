# Preserve remote execution outcomes in the Android background service

## Root cause

MainActivity and archive-result recovery decode remote replies with
`AgentRemoteOutcomeCodec`. MessageService instead rebuilt `AgentConnectorResponse`
from notification-preview text and a subset of identity fields. Constructor
defaults silently changed every reply to generation 1, sequence -1, blank remote
status, and success=true.

This is not just a display difference. When MQTT ingress already observed
generation 2 or 3, the response bus rejected the reconstructed generation-1 body
as stale. For generation 1, cancellation, failure, and timeout could instead pass
through as success. The background path also ignored exact-content encoding and
did not apply the foreground terminal-delivery fence.

## Fix

Android 1.0.34 (code 878) uses the existing canonical decoder in MessageService,
including exact text, actual failure reasons, terminal status, execution generation,
status sequence, and the same assistant rich-output policy as the foreground path.
Terminal user deliveries and stale generations are rejected before optional voice
projection or contact-history fallback. Valid replies use the existing
`AndroidAgentResultRecovery.publishResult` entry, so encrypted-inbox persistence
and result receipts behave consistently with foreground reception.

The exact reply is committed before optional voice work. Peer-chat envelopes stay
on the contact-history path even if they have a source ID or agent-like fields;
ordinary messages without complete agent scope retain normal contact delivery.
There are no protocol, Desktop, native model, ASR/QNN, pairing, or data-migration
changes in this patch.

## Baseline evidence

The regression APK was first run against unmodified Android 1.0.33/code 877 on
S20U SM-G9880. All seven original cases failed in 0.500 seconds:

- Generation 2 was rejected by the current-execution fence after reconstruction.
- Generation 3 could not reach the persistence assertion for the same reason.
- Cancellation became success despite an explicit remote cancelled status.
- Failed and timed-out outcomes also became success.
- Exact final text was replaced by the notification preview.
- A terminal user delivery still reached the managed-response consumer.

The baseline log is `build/background-outcome-baseline-device.log`. The test uses
the actual `MessageService.onMessage` callback, attaching an isolated Context to
the service instance without starting its lifecycle/network workers. It is not a
mock of the response constructor. A scoped managed-response consumer captures the
published response; one case additionally persists that captured response through
the actual encrypted inbox. This is distinct from a full background MQTT test.

All preferences, databases, and files used by the fixture are isolated. Test
notifications are forbidden, the voice bridge is disabled only in test preferences,
and cleanup removes only that fixture's storage. No real contact, conversation,
message, model setting, or pairing is cleared.

## Regression coverage

The final suite adds old-generation rejection with no contact-history fallback,
peer messages with a source ID, and ordinary messages without agent scope. Together
these cover ten concrete service-callback cases. Canonical decoder and late-reply
policy JVM regressions are also part of the targeted verification command.

These cases prove callback interpretation and persistence fencing with isolated
fixtures on the device. They do not establish network latency, real-provider
retry/cancellation timing, or the whole long-running-goal acceptance. The earlier
[live terminal-result recovery test](https://github.com/galaxyssi/GalaxySSI/pull/2844) is a
separate real-provider/process-death scenario, not a substitute for background
generation-2 live acceptance.

## Fixed-version verification

- The exact seven-test APK that failed on 1.0.33 was rerun unchanged after installing
  the fixed 1.0.34 Debug App: seven passes in 0.494 seconds.
- The first expanded ten-test run found one teardown-only concurrent modification
  in the fixture's preference-name set. The fixture now uses a concurrent set;
  no production assertion was weakened. The failed log is retained separately.
- The final ten-test suite passed three fresh instrumentation processes in
  0.596 / 0.615 / 0.837 seconds. Logs are
  `build/background-outcome-fixed-device-final-{1,2,3}.log`.
- Eleven canonical-outcome and five late-response-policy JVM tests passed.
- Debug, AndroidTest, and Release builds passed; all 72 AArch64 libraries passed
  the 16 KiB alignment audit. Repository checks passed.
- Latest main b74564fb5 was integrated before submission. Its added recovery test
  and documentation do not change the production source used for these APKs.
- S20U was restored to non-debug Release 1.0.34/code 878 with in-place installation;
  first-install time remained 2026-09-07 00:28:13. Only the test package was removed,
  and MainActivity was foregrounded. The tablet and Desktop were not operated.

The subsecond fixture durations are test execution times, not user-visible reply
latency measurements and not a performance percentile claim.
