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
6. `AgentManagedConnectorResponseRegistry` consumes each managed connector response once and prevents internal replies from becoming independent user messages.
7. `AgentTeamExecutionStore` derives a user-auditable snapshot from append-only subagent events.

An observer failure is isolated. The primary receives the failure and remaining evidence and may still complete. Unknown dependencies, cycles, multiple responding members, missing capabilities, offline endpoints, and capacity exhaustion fail deterministically.

## Persistence And Recovery

Team definitions, requests, child lifecycle events, outputs, errors, provenance, and terminal state are encrypted at rest. Stable supervisor and child Run IDs prevent duplicate starts. On host restart, nonterminal team Runs are marked `INTERRUPTED`; SignalASI does not silently replay remote work with an unknown outcome.

Late managed responses across process death remain a release gate. Until the durable late-response correlation scenario is verified, interrupted teams are inspectable but are not automatically resumed.

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
- interruption marking without silent replay.

Release still requires a real-device paired-team scenario, forced process death, late response correlation, reconnect, cancellation, and UI inspection.
