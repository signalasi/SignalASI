# Personal Memory Evolution Architecture

SignalASI treats memory as an evidence-backed, temporal world model rather than a transcript archive. New events can add knowledge, strengthen accepted knowledge, supersede stale knowledge, expose conflicts, build relationships, and propose reusable Skills without silently changing sensitive identity or safety state.

The design is informed by the agentic-memory direction explored by A-MEM, graph-backed long-term memory explored by Mem0, and the multi-session reasoning categories measured by LoCoMo. SignalASI keeps its own host-owned trust, privacy, evidence, and execution boundaries.

## Durable Pipeline

```text
authorized semantic event
  -> deterministic understanding
  -> proposed world reduction
  -> candidate memory gate
  -> temporal evolution
  -> accepted personal world model
  -> typed entity relationship graph
  -> query planner
  -> prompt compiler
  -> selected model or Agent
```

The append-only event and evidence history is never rewritten. Evolution changes the current materialized view by marking older facts as superseded, completed, conflicted, or historical.

Every accepted world item now persists its temporal state directly. New evidence is assigned an explicit evolution action (`CREATE`, `STRENGTHEN`, `SUPERSEDE`, `LINK`, `CONSOLIDATE`, `REVIEW_CONFLICT`, or `BLOCK_PRIVATE`) together with bounded target item IDs. This makes the materialized transition explainable and prevents a planned goal from being compiled as current state.

Every proposal and review decision also emits a bounded encrypted evolution record. The record contains the subject, action, outcome, temporal state, target IDs, resulting item ID, and evidence count, but never copies the candidate value. Private candidates use a fixed non-sensitive subject. Record IDs are deterministic, so event replay and process recovery cannot duplicate the history.

## Core Invariants

1. Session-private content never enters durable memory, candidate payloads, relationship graphs, exports, or model context.
2. Identity, preference, and safety changes require explicit review unless they come from an already-authorized host control event.
3. Conflicting evidence cannot silently replace accepted state.
4. Explicit corrections may supersede an older state while retaining both evidence chains.
5. Current-state queries exclude superseded history unless the user asks a historical question.
6. Similar projects share general knowledge but do not share project-specific state.
7. `LOCAL_ONLY` memory is never compiled into context sent to a model or remote Agent.
8. Processing is idempotent by event ID, candidate ID, evidence ID, and graph identity.
9. User rejection prevents both world-model and graph mutation.
10. Backup, restore, reset, and causal deletion cover every durable memory structure.

## Temporal State Model

Durable memory distinguishes:

- `CURRENT`: accepted present state.
- `HISTORICAL`: a past fact or completed state that remains useful for history questions.
- `PLANNED`: intended work that has not become current state.
- `DEPRECATED`: explicitly superseded or removed state.
- `PENDING`: a candidate waiting for review.
- `CONFLICTED`: unresolved competing evidence.

The personal world model retains its operational status (`ACTIVE`, `CONFLICTED`, `SUPERSEDED`, or `COMPLETED`) while the memory evolution layer supplies the richer temporal interpretation used by review and retrieval.

## Candidate Memory Gate

Each proposed durable change is classified as a fact, preference, identity, decision, project state, goal, relation, or Skill opportunity.

- Low-risk durable evidence is merged automatically.
- Identity, preference, and safety changes wait in the encrypted memory inbox.
- Contradictions wait in the inbox unless the event contains an explicit replacement signal.
- Private data is rejected and its raw payload is discarded.
- Approval writes the world item and its relationship-graph projection.
- Approval resolves `PENDING` or `CONFLICTED` temporal state into `CURRENT`, `PLANNED`, `HISTORICAL`, or `DEPRECATED`; approved memory can therefore enter retrieval immediately without bypassing review.
- Rejection records the decision without changing the accepted world model.
- Semantically equivalent, same-polarity evidence strengthens the accepted item instead of creating another list entry.
- Causal deletion removes source candidates and their payloads from the inbox before the deletion event is committed.

## Typed Relationship Graph

The graph stores typed nodes for users, devices, applications, features, settings, Agents, models, tools, projects, concepts, and states. Relations include ownership, use, support, composition, state, naming, dependencies, connections, preferences, removals, and general relatedness.

Every node and relation carries temporal state, confidence, bounded evidence references, and observation time. Retrieval supports bounded multi-hop traversal and excludes historical relations unless the query plan explicitly requests them.

The graph projects ownership, use, support, components, state, naming, dependencies, connections, preferences, removal, and general relatedness. A removal event deprecates the affected entity and closes every current inbound and outbound relation, including support and dependency edges, instead of leaving stale capabilities reachable from current-state queries. Historical queries can still traverse those closed relations and their validity interval.

## Query Planner And Prompt Compiler

Before retrieval, the query planner classifies the request into one or more facets: project state, device capability, historical decision, personal identity, personal preference, security state, long-term goal, tool evidence, relationship, or general memory. A request can therefore combine device, project, relationship, security, and historical intent instead of losing all but the first match.

The plan controls:

- preferred world-item kinds and layers;
- current-only, history-only, or current-and-history temporal scope;
- graph traversal depth;
- preferred typed relationships used to rerank graph traversal;
- item and character budgets;
- project namespace isolation.

The prompt compiler emits only selected shareable evidence. It separates current, historical, and conflicted facts, adds an explicit unresolved-conflict warning, preserves a strict character budget, and labels all memory as untrusted evidence rather than instructions.

Compiled entries carry bounded evidence counts and opaque memory references. Planned state has its own section, and historical or deprecated facts are excluded unless the query plan explicitly needs history or completed-goal context.

## Memory Critic

An encrypted on-device audit runs after meaningful event batches. Its next daily deadline is also part of the durable AlarmManager wake schedule, so the audit does not depend on a new chat event arriving. It identifies:

- expired current state;
- unresolved conflicts;
- low-confidence evidence that is repeatedly reused;
- repeated decisions that may become reviewed Skills;
- completed goals that may be archived;
- candidates left waiting for review for too long.
- semantically duplicate memories that can be consolidated safely;
- cross-session topic clusters that should become durable long-term themes.

The critic may retire expired state and merge equivalent evidence while retaining the duplicate as superseded history. Stale inbox candidates have their own finding type and are not mislabeled as low-confidence accepted memory. The critic never auto-approves identity, preference, safety, Skill, or conflicting candidates. Long-term themes are evidence-only rollups: they introduce no new factual claims and remain visible in Memory Health.

## Self-Model Feedback

Terminal Runs update a separate encrypted Agent self-model. The self-model learns bounded route reliability, task-family strengths, limitations, latency, repeated failures, and explicit user corrections. It never stores raw sensitive requests or unknown external identifiers. Routing may use this calibration only after capability, privacy, trust, availability, and hard safety checks pass.

## Control Center

The Memory and Personalization page exposes:

- candidate memory inbox with approve and reject actions;
- encrypted evolution history showing applied, waiting, conflicting, blocked, approved, and rejected transitions;
- temporal relationship graph with entity and relation counts;
- memory health findings and a manual on-device audit;
- existing explicit memory editing, conflict resolution, pinning, and deletion.

## Evaluation Corpus

The regression suite follows LoCoMo-style categories adapted to SignalASI:

- cross-session retrieval;
- temporal ordering and historical questions;
- explicit user corrections;
- similar-project isolation;
- private-content exclusion;
- reusable tool evidence;
- long-horizon goal continuity;
- relationship-graph multi-hop queries;
- unresolved-conflict labeling;
- causal deletion of candidates and graph evidence;
- candidate review, rejection, and event replay idempotency.

`GlobalMemoryLoCoMoRegressionTest` is the stable cross-session corpus. Scenario names describe the user-visible memory contract rather than implementation details, which allows retrieval internals to evolve without weakening the acceptance criteria.

An isolated unit test is not sufficient release evidence. Production acceptance also requires encrypted backup/restore, process-death replay, real-device review UI, causal deletion, and model-context inspection.

## References

- A-MEM: Agentic Memory for LLM Agents: https://arxiv.org/abs/2502.12110
- Mem0: Building Production-Ready AI Agents with Scalable Long-Term Memory: https://arxiv.org/abs/2504.19413
- LoCoMo: Evaluating Very Long-Term Conversational Memory of LLM Agents: https://aclanthology.org/2024.acl-long.747/
