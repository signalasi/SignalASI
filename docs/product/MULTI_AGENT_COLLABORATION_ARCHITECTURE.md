# Multi-Agent Collaboration Architecture

SignalASI treats an Agent team as one host-owned Run, not as several unrelated chat messages. The host owns the team graph, identity, delivery policy, evidence handoff, final response, persistence, and cancellation boundary.

## Delivery Semantics

- `RESPOND`: exactly one primary Agent may publish the final user-facing result.
- `OBSERVE`: the member is executed and returns bounded internal evidence to the primary Agent.
- `IGNORE`: the member is never dispatched.

The Android connector executor uses `RESPOND` at the transport boundary for managed observer work because the legacy connector implementation interpreted `OBSERVE` as context injection only. The host preserves the original `OBSERVE` policy in the Run contract and intercepts its response before transcript delivery.

## Execution Flow

1. `AgentProductionTeamController` validates and starts a durable team Run.
2. `AgentTeamExecutionRuntime` converts members into a bounded acyclic subagent graph.
3. Observer members execute concurrently within host limits.
4. Observer outputs, failures, provenance, and truncation state form a structured dependency handoff.
5. The primary member waits for terminal observer states, then produces the single final answer.
6. `AgentManagedConnectorResponseRegistry` consumes live replies, while the encrypted managed-response ledger preserves correlation through process death and suppresses duplicate delivery.
7. `AgentTeamExecutionStore` derives a user-auditable snapshot from append-only subagent events.

An observer failure is isolated. The primary receives the failure and remaining evidence and may still complete. Unknown dependencies, cycles, multiple responding members, missing capabilities, offline endpoints, and capacity exhaustion fail deterministically.

## Persistence And Recovery

Team definitions, requests, child lifecycle events, outputs, errors, provenance, and terminal state are encrypted at rest. Stable supervisor and child Run IDs prevent duplicate starts. On host restart, nonterminal team Runs are marked `INTERRUPTED`; SignalASI does not silently replay remote work with an unknown outcome.

Each dispatched managed connector Run also writes an encrypted correlation record before the response is released. A response that arrives after process recreation is attached to its original child and supervisor, advances the durable team snapshot idempotently, and never falls through into ordinary chat. Applied correlation records remain as bounded, seven-day deduplication tombstones. Interrupted work is not automatically replayed; only independently returned evidence is reconciled.

## User Experience

Background teams expose only their aggregate status by default. The existing Recent Tasks page includes an Agent teams section. Opening a team explicitly expands members, roles, status, errors, and the final result. Visible teams may expose member details immediately through `AgentTeamProgressPolicy`.

Internal orchestration, hidden reasoning, and observer-only content never appear as separate assistant replies.

## Verification

Automated coverage proves:

- observer concurrency and bounded execution;
- exactly one responding Agent;
- `IGNORE` never dispatches;
- observer failure isolation;
- dependency-cycle and capability rejection;
- stable child Run identity and structured handoff;
- one-shot async response interception and event replay;
- production connector bridge execution before primary synthesis;
- interruption marking without silent replay;
- late-response reconciliation into the original interrupted team;
- duplicate live and late response suppression;
- Android instrumentation coverage for encrypted store recreation and ordinary-chat isolation.

Release still requires a real-device paired-team scenario with forced process death, a naturally late Desktop reply, reconnect, cancellation, and UI inspection.
