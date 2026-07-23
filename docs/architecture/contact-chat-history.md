# Contact Chat History

SignalASI stores ordinary contact messages as individually encrypted SQLite rows.

## Invariants

- Message history has no count-based retention limit.
- Message content is not truncated by the persistence layer.
- Message identifiers are allocated atomically across foreground and background writers.
- Each row is encrypted with AES-GCM and message-specific associated data.
- Contact and sequence indexes support incremental history paging.
- Background snapshots merge rows instead of replacing the complete database.
- Message deletion writes a tombstone so a stale foreground snapshot cannot restore deleted data.
- Backup export and import use the same database as live messaging.
- Reset, contact deletion, task status updates, and delivery trace updates all use the central store.

The current development build intentionally starts with the new store and does not import the former
SharedPreferences chat payload.
