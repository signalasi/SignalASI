# Android Agent Task History

SignalASI stores Android Agent task records separately from transcript entries.
Each task record keeps its goal, route, risk decision, result, verification,
artifacts, and complete execution timeline.

## Persistence

- Each task is an individually encrypted SQLite row.
- The task ID is the stable upsert key.
- Session and update-time indexes support conversation lookup and recent-task
  views without loading the full database.
- There is no total task count or per-session task count that deletes older
  records.
- Query limits control only the size of one result page or view.
- Encrypted backups include every task record and complete task timeline.
- Explicit conversation deletion and full data reset are the only deletion
  paths.

Development builds do not import the obsolete
`signalasi_agent_tasks` SharedPreferences payload.
