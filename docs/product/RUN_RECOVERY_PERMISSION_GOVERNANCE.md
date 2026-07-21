# Durable Run Recovery And Permission Governance

## Host-owned invariants

- A model or external Agent can request authority but cannot create its own grant.
- A Run start reserves its idempotency key before transport delivery.
- A previously reserved Run is never replayed until remote recovery proves that no duplicate will be created.
- Revoking a required grant stops active execution, pauses the durable Workspace, cancels unfinished tool records, and records a checkpoint.
- A remote outage keeps a durable Run recoverable. It does not silently replay or mark the task complete.

## Permission grant ledger

`AgentPermissionGrantLedger` stores encrypted, bounded grants for:

- models
- Agents
- tools
- Android permissions and capabilities
- devices
- files
- apps
- consequential actions

Every grant records its subject, scope, optional action/resource/target constraints, host issuer, approval evidence, lifetime, use count, timestamps, and terminal status. Lifetimes are single-use, temporary, or permanent. Authorization selects the most specific active match and atomically consumes bounded grants.

The existing confirm-once API now projects onto this ledger, so current policy and UI callers retain their behavior while gaining structured governance.

## Revocation propagation

`AgentPermissionRevocationCoordinator` joins the grant ledger, durable Workspace store, process-lifetime task supervisor, and Run event store. Revocation:

1. marks matching grants revoked;
2. identifies nonterminal Workspaces bound to the grant or scope;
3. stops active execution and changes the Workspace to `PAUSED`;
4. cancels pending or running tool records;
5. records a revocation event and recovery checkpoint;
6. appends `PERMISSION_REVOKED` to each correlated Run.

## Durable Run start idempotency

`AgentRunStartReceiptStore` persists a semantic request digest and one of four states:

- `RESERVED`
- `ACCEPTED`
- `OUTCOME_UNKNOWN`
- `CANCELLED`

The adapter returns an accepted handle directly on retry. If transport outcome is unknown, it queries `recoverRuns()` and accepts only a matching remote handle. Without remote evidence it blocks duplicate execution and leaves the original key unresolved.

## Workspace v2 reconstruction

The encrypted Workspace snapshot now includes:

- goal and parent Run
- Agent, device, remote Run, and delivery mode
- current plan and result
- permission grant and scope bindings
- tool call state
- artifacts and handoff identities
- error state
- local and remote event cursors
- recovery checkpoints

The task runtime writes this snapshot before completing or deferring a task.

## Startup recovery

`AgentRunRecoveryCoordinator` runs during Android startup and applies the host recovery policy:

- local confirmation or paused state: restore the local checkpoint;
- trusted durable Desktop Run: reconnect through the Agent Adapter and resume from the remote cursor;
- temporarily unavailable Desktop: keep the Workspace in `WAITING_RESPONSE`;
- non-replayable interrupted execution: fail deterministically;
- already terminal recorded Run: reconcile its terminal event.

Recovery writes are revision-checked and bounded. Repeated startup recovery does not intentionally replay Run start or tool execution.
