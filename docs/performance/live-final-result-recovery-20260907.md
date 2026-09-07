# Live final-result recovery across Android process death

## Scope

This acceptance test extends read-only task inspection to a real terminal reply:
S20U sends one Chinese probe through its existing paired Codex contact; Desktop
executes that probe once. A test-only listener consumes the test transport envelope
without publishing its final body to the response inbox or transcript. The App is
then force-stopped between each recovery stage.

There is no production fault-injection switch, fake provider, injected result body,
direct invocation of the result-fetch API, pairing reset, or application-data wipe.
Only the newly created test conversation/source/turn/task is intercepted. Other
messages are not consumed by the test listener. Model, native, release, and protocol
code are unchanged by this test-only change; App remains 1.0.33.

## Four independent processes

1. **Submit and drop:** create a real private test conversation and user entry,
   publish through the normal paired transport, wait for the actual completed
   reply, and prove its body is absent from both inbox and transcript. Preserve a
   durable pending delivery. Expected content, scope, generation, sequence, and
   SHA-256 are stored in a Keystore-backed encrypted test checkpoint.
2. **Reconnect and recover:** assert that the PID changed and the body is absent.
   Normal subscription readiness wakes production pending-delivery discovery.
   Production archive paging verifies scope and hashes and commits the actual
   reply to the encrypted inbox. Assert exact text, hash, identity, and sequence;
   do not submit the task again or launch the UI in this stage.
3. **Cold UI:** after another process stop, launch real MainActivity. Its ordinary
   inbox consumer must create exactly one assistant entry in the original
   conversation and remove the pending-delivery/inbox work. Wait for the exact
   reply in a shown TextView, recreate the Activity, and assert it again.
4. **Cold retention:** stop the process again and cold-launch MainActivity. Verify
   the reply is visible exactly once in the transcript and neither queue has
   resurrected the already consumed reply.

PID checks prevent accidentally replacing process-death testing with closing and
reopening a database object. Setup refuses an existing case ID rather than silently
resubmitting a task after failure. All four tests are opt-in and restricted to the
authorized SM-G9880 model; normal CI invocation skips live-provider operations.

## Reproduction

Install the current Debug App and AndroidTest APK with `adb -s <S20U-serial> install
-r`, preserving application data and the installed signing identity. Ensure the
existing Desktop/Codex pairing is available. Then run from the repository root:

```powershell
& .\tools\dev\test-android-live-final-recovery.ps1 -Serial <S20U-serial>
```

The script saves each instrumentation result and case-scoped timing lines under
`build/live-final-<source>/`. It fails on JUnit failure even if ADB exits with zero.
After diagnosing a failure, an explicit `-Source <source> -Phase inbox|ui|cold`
resumes that stage without creating another provider request. Do not rerun setup
with an existing source. Keep the original failed log before resuming a phase.

Restore the non-debug Release App using an in-place installation after testing,
remove only `com.galaxyssi.chat.test`, and foreground the App. Test conversations
remain available for inspection; no user conversation or pairing is deleted.

## Measured evidence

Device: Samsung S20 Ultra SM-G9880, existing paired Desktop on the public MQTT
transport. Before testing, `/proc/meminfo` reported 4,842,744 KiB MemAvailable.
No tablet or S26U operation was performed by this test.

The shared running Desktop was independently updated by another task to its
1.0.31 video worktree (Electron PID 63880, backend PID 49072), confirmed by process
inspection and that workspace's package manifest. This test did not start or
restart Desktop, construct a backend runtime store, or directly mutate Desktop's
production state directory. Results are against that real shared runtime, not a
claim of an isolated pure-main Desktop benchmark. The Android branch integrated
latest main e3701bb35 before final verification.

An initial three-stage exploratory run (source 1788753520852) passed. Reconnection
through exact inbox body took 11,092 ms. A subsequent manual cold-start screenshot
confirmed the final reply in the original conversation. This first revision did
not yet assert shown TextViews automatically; it is not counted as a four-stage
UI acceptance run.

The complete four-stage run (source 1788753849604) passed all stages:

| Measurement | Elapsed |
| --- | ---: |
| Connection start through subscription readiness | 2,043 ms |
| Readiness through archived body in encrypted inbox | 5,893 ms |
| Connection start through exact body in inbox | 7,936 ms |
| MainActivity launch through first visible reply | 1,433 ms |
| Launch, visible reply, Activity recreation, and verification | 1,887 ms |
| Subsequent cold-launch visibility/retention check | 1,090 ms |

Instrumentation totals (including test setup/teardown) were 17.129 / 8.046 / 2.217 /
1.142 seconds. JUnit output, rather than ADB's exit code alone, confirmed four
passes. The test uses a short actual Codex reply; it is not a large-payload test.

The final case-scoped-metrics revision was then run in all four separate processes
(source 1788754146939), again passing every stage:

| Measurement | Elapsed |
| --- | ---: |
| Subscription readiness | 1,976 ms |
| Readiness through archived body | 6,187 ms |
| Connection start through exact inbox body | 8,163 ms |
| Cold UI first visible reply | 1,416 ms |
| UI launch plus Activity recreation checks | 1,857 ms |
| Subsequent cold-launch retention check | 1,071 ms |

Final instrumentation totals were 17.867 / 8.293 / 2.215 / 1.168 seconds.
`build/live-final-1788754146939/` holds the four JUnit logs and case-scoped metrics.
After testing, in-place Release installation restored version 1.0.33 (code 877),
without the DEBUGGABLE flag. First-install time remained 2026-09-07 00:28:13;
only the test package was uninstalled and MainActivity was foregrounded.

## Acceptance still open

These observations establish actual final-body recovery and UI deduplication for
this scenario, not P95/P99 latency, all recovery paths, or the whole project goal.
The observed reconnect-to-body times are above five seconds. Production tracing
must separate network readiness, status query, archive paging, and persistence to
locate the remaining cost. Large paged live replies, deterministic broker ACK
loss, mid-page process death, device reboot, provider timeout/cancellation, and
concurrent conversation fault cases still need dedicated live coverage. Existing
isolated page/checkpoint tests cannot substitute for those measurements.
