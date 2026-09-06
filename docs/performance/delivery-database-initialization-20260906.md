# Delivery database initialization reliability

## Failure and scope

The full backend regression for PR #2831 exposed an intermittent failure in
unchanged main `d64675469`: concurrent first-open of the delivery database raised
`sqlite3.OperationalError: database is locked` while enabling WAL. The failed
connection remained open, so Windows also rejected temporary database cleanup.

This affects initialization used by the encrypted logical task-result outbox as
well as other Link delivery operations. A queue write cannot proceed when its
database connection fails to initialize. This finding does not establish the
cause of every previous real-device delivery failure.

Desktop source version advances to 1.0.22. Android, models, ASR/QNN, MQTT protocol,
pairing secrets, and the running 1.0.21 Desktop are unchanged in this phase.

## Fix

- Coordinate initialization using the existing in-process reentrant delivery
  lock. Callers retain ownership of successful connections and close them as
  before; business operations do not run inside an added global transaction.
- Inspect journal mode before enabling WAL, avoiding a repeated WAL-mode change
  on established databases.
- Close a failed connection on every initialization exception, including
  interruption. Closing also rolls back unfinished initialization transactions.
- Retry initialization only for structured SQLite BUSY/LOCKED error codes,
  including extended codes. Do not classify arbitrary exception text as a lock.
- Keep a monotonic 10-second initialization retry window, derived from the
  existing SQLite connection timeout. It is not an Agent action budget or task
  duration limit. Do not retry message writes or erase queued results.
- Recheck the legacy outbound priority-column migration under a SQLite writer
  transaction so two independent processes cannot both add the same column.

## Focused verification

The focused suite passed **42 tests and 33 subtests in 30.48 seconds**:

- 12 fresh-database trials with eight synchronized threads, each writing three
  independent replies: 288 replies with exact scope equality on read-back.
- Four independent processes synchronizing their first open and committing 16
  replies, followed by database integrity, WAL-mode, and separate-process reopen
  checks.
- The same four-process test against a legacy database without the priority
  column.
- BUSY and extended LOCKED faults close the failed handles and retry using fresh
  connections.
- Corruption, constraint failure, unclassified errors, and interruption close
  immediately without being retried.
- Persistent lock contention stops at the retry deadline and returns the error.
- A failed initialization transaction cannot delete previously committed replies.
- Warm opens retain encrypted state keys and do not reset WAL mode.
- Existing outbox generation fences, stale receipts, canonical replies, crash
  recovery, paging, and handoff tests remain included.

The first test-harness run exposed two unclosed inspection connections: Python's
SQLite context manager commits/rolls back but does not close the handle. The
test harness now uses `contextlib.closing`; the subprocess write/reopen assertions
had already succeeded. No cleanup errors were suppressed.

## Baseline comparison

A local isolated harness loads only `_connect` from main `d64675469` with Python
AST, injects a non-retryable initialization failure into a real connection, and
compares handle state. The old implementation leaves it open; the candidate
closes it. Both tests use temporary databases and explicitly release handles
after recording the result.

Three alternating-order runs measured 300 warm open/close operations per version
per run while other host builds were active:

| Run | Main median / P95 | Candidate median / P95 |
| --- | --- | --- |
| Main first | 3.053 / 5.076 ms | 4.051 / 8.353 ms |
| Candidate first | 4.210 / 9.864 ms | 4.273 / 6.958 ms |
| Main first | 3.898 / 5.852 ms | 3.615 / 5.753 ms |

These are noisy connection-only measurements, not end-to-end message latency or
proof of a speedup. The first run is slower, the later runs are mixed; additional
live workload measurements are required before making performance claims.

## Regression environment recovery

The first complete run reported 1,429 passed and 24 failed tests. Inspection of
the full tracebacks found missing Signal sidecar runtime errors, including
secondary failures where ingress stopped before acknowledging or routing a
message. This was not accepted as a passing regression run.

An attempted reuse of another worktree's packaged sidecar was also stopped:
that artifact had changed to require an instance-proof key not implemented in
this main revision. The matching sidecar is now built from this worktree with
`gradle installDist --offline --no-daemon`, using cached dependencies. Regression
runs select this local artifact, an isolated loopback port, and temporary data
directories. Shared build outputs are not used as version-stable test fixtures.

The fault tests patch only `link_delivery.time`, rather than modifying the
process-wide Python time module. The revised initialization suite passed all
8 tests and 16 subtests in 15.96 seconds.

With the matching sidecar, the complete pre-merge run reached 1,451 passed and
423 subtests passed in 647.07 seconds. Two QR integration subprocesses exceeded
their existing 30-second deadlines. A separate real QR diagnostic completed in
9.69 seconds (260-byte optical payload); this does not establish that the two
timeouts were fixed. They remain subject to the merged-main regression below.

## Operational boundary

All database and fault tests use isolated temporary directories. No production
database, phone data, or pairing state is deleted or reset. The existing
encrypted-storage version and message-generation fences are retained.
