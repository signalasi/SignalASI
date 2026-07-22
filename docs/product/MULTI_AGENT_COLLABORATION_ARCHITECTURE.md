# Multi-Agent Collaboration Architecture

SignalASI treats an Agent team as one host-owned Run, not as several unrelated chat messages. The host owns the team graph, identity, delivery policy, evidence handoff, final response, persistence, and cancellation boundary.

## Delivery Semantics

- `RESPOND`: exactly one primary Agent may publish the final user-facing result.
- `OBSERVE`: the member is executed and returns bounded internal evidence to the primary Agent.
- `IGNORE`: the member is never dispatched.

The Android connector executor uses `RESPOND` at the transport boundary for managed observer work because the legacy connector implementation interpreted `OBSERVE` as context injection only. The host preserves the original `OBSERVE` policy in the Run contract and intercepts its response before transcript delivery.

## Execution Flow

1. A model may propose several Agent calls and dependencies, but `AgentTeamPlanCompiler` accepts them only when every member is an available Agent, identities are unique, dependencies are internal and acyclic, and one final node transitively depends on every specialist branch.
2. The compiler derives roles locally, assigns the unique sink as `RESPOND`, assigns all specialists as `OBSERVE`, creates stable team and supervisor identities, and replaces the branch with one host-owned Action.
3. `AgentProductionTeamController` validates and starts a durable team Run.
4. `AgentTeamExecutionRuntime` converts members into a bounded acyclic subagent graph.
5. Observer members execute concurrently within host limits.
6. Observer outputs, failures, provenance, and truncation state form a structured dependency handoff.
7. The primary member waits for terminal observer states, then produces the single final answer.
8. `AgentManagedConnectorResponseRegistry` consumes live replies, while the encrypted managed-response ledger preserves correlation through process death and suppresses duplicate delivery.
9. `AgentTeamExecutionStore` derives a user-auditable snapshot from append-only subagent events.
10. `AgentConnectorTeamCompletionSink` projects a terminal team into one durable synthetic connector response. The originating `MobileNativeAgent` consumes that response exactly like any other awaited resource and continues downstream plan dependencies in the same conversation.

An observer failure is isolated. The primary receives the failure and remaining evidence and may still complete. Unknown dependencies, cycles, multiple responding members, missing capabilities, offline endpoints, and capacity exhaustion fail deterministically.

## Persistence And Recovery

Team definitions, requests, child lifecycle events, outputs, errors, provenance, and terminal state are encrypted at rest. Stable supervisor and child Run IDs prevent duplicate starts. On host restart, nonterminal team Runs are marked `INTERRUPTED`; SignalASI does not silently replay remote work with an unknown outcome.

Each dispatched managed connector Run also writes an encrypted correlation record before the response is released. A response that arrives after process recreation is attached to its original child and supervisor, advances the durable team snapshot idempotently, and never falls through into ordinary chat. Applied correlation records remain as bounded, seven-day deduplication tombstones. Interrupted work is not automatically replayed; only independently returned evidence is reconciled.

The process-wide team controller is initialized with the global Agent runtime rather than on first UI access. Background connector responses therefore trigger reconciliation even when no Activity has opened the team page. Managed team assignments use stable host-owned source identities without creating user chat rows. A managed response consumed by the team returns immediately from the background message service, so observer evidence, primary assignments, and primary intermediate replies cannot be appended to ordinary chat history.

Terminal team delivery has a separate encrypted deduplication ledger. The final response is persisted before its supervisor identity is marked delivered, so a crash cannot silently lose the result. Reopening the same persisted Action does not start a second team when its stable supervisor snapshot already exists.

An explicit retry is a new attempt rather than a replay of the old supervisor. The host rekeys the team, supervisor, idempotency key, and synthetic response identity before execution, then persists those identities with the reset Action.

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
- guarded planner-graph compilation and downstream dependency remapping;
- stable, single-response completion projection into the originating task;
- interruption marking without silent replay;
- late-response reconciliation into the original interrupted team;
- duplicate live and late response suppression;
- Android instrumentation coverage for encrypted store recreation and ordinary-chat isolation.
- two-process Android device coverage that force-stops the app between team creation and background response delivery, then proves recovery, single final delivery, duplicate suppression, and zero raw child-message leakage.
- real paired-Desktop coverage that dispatches through the encrypted production connector, force-stops Android after the delayed primary assignment is accepted, and proves the naturally late result completes the original team exactly once after process recreation.

The paired process-death and reconnect scenario is automated by `test:android:team-paired-process-death`. Release inspection still covers user cancellation and the expanded Recent Tasks team UI.
