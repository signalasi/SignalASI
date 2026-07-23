# Android Workflow Execution History

SignalASI stores workflow execution history in an indexed SQLite database. Each
record payload is encrypted independently with the Android Keystore-backed Agent
storage key.

The history source has no count-based retention limit. UI callers request a
bounded recent result set, but that query window does not delete older records.
Backup export and restore operate on the complete record set.

The database indexes:

- newest execution order;
- workflow-specific execution order;
- stable record identity through a SHA-256 lookup hash.

Raw record IDs, workflow IDs, names, statuses, and result summaries remain inside
the encrypted payload. The index stores only hashes and timestamps.

This development-stage schema starts clean. It does not import or read the
obsolete encrypted-preferences history format.
