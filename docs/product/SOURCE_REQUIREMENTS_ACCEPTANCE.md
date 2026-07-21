# Source Requirements Acceptance Matrix

This matrix preserves the complete scope of the two source specifications used for the current SignalASI upgrade:

1. Unified Agent access, Run control, device execution, memory, and Skill evolution.
2. One persistent Personal ASI above isolated topic conversations.

`Implemented` means a production path and direct automated evidence exist. `Partial` means useful code exists but at least one required production path or acceptance gate is missing. `Pending` means the required behavior is not yet present. Environment-dependent integrations still require deterministic failure, durable state, and recovery behavior in the host.

## Unified Agent And Run Control Plane

| ID | Requirement | Current evidence | Status | Remaining acceptance evidence |
| --- | --- | --- | --- | --- |
| CP-01 | Unified encrypted Agent registry | `EncryptedAgentRegistry`, connector projection | Implemented | Process-death and stale-heartbeat tests |
| CP-02 | Stable Agent metadata including identity, location, tools, permissions, protocol, cost, latency, trust, capacity, and heartbeat | `AgentRegistration` | Implemented | Registry migration is intentionally out of scope during early development |
| CP-03 | One Adapter contract for connect, status, capability, Run, message, cancel, events, and recovery | `AgentAdapter`, `AgentAdapterTransport` | Implemented | Production connector conformance tests |
| CP-04 | Dedicated Codex, Claude Code, OpenClaw, cloud, local-model, Windows, and Android adapters | Desktop named adapters plus Android `AgentProductionAdapterFactory` and descriptor-preserving registry projection | Implemented | Real connector conformance across every packaged runtime |
| CP-05 | External Agents remain independently upgradeable and failure-isolated | independently-upgradeable descriptors, separate transport/runtime failure domains, encrypted provider health ledger, circuit breaker, and crash-isolation tests | Implemented | Real process-crash injection across two paired Desktops |
| CP-06 | `RESPOND`, `OBSERVE`, and `IGNORE` delivery semantics | `AgentDeliveryMode`, observation context store, connector dispatch | Implemented | End-to-end multi-Agent delivery test |
| CP-07 | Conversation, message, task, Run, step, event, tool-call, device, and Agent identities remain separate | Run request/event/workspace models | Implemented | Correlation audit across Android and Desktop |
| CP-08 | Unified complete Run event state machine | `AgentRunControlEventType`, encrypted event store | Implemented | UI/event-stream end-to-end test |
| CP-09 | Stable installation, device, and Agent identity | registry plus SignalASI Link pairing identity | Implemented | Multi-device reconnect test |
| CP-10 | Restore active Run, cursor, permission wait, tool state, and checkpoint after interruption | `AgentRunRecoveryCoordinator`, Run event store, Workspace v2 snapshots, startup recovery, process-recreation tests | Implemented | Real-device forced process death while a paired Desktop Run and permission wait are active |
| CP-11 | Heartbeat and online, offline, busy, idle, degraded, updating, permission-required, and unreachable states | registry heartbeat and connector status projection | Implemented | TTL and multi-Run capacity integration tests |
| CP-12 | Capability discovery and capability-based routing | capability catalog, registry, `AgentResourceRouter` | Implemented | Cross-provider routing matrix gate |
| CP-13 | Single-Agent and multi-Agent Provider access modes | Desktop capability manifest -> Android registry -> `ActionExecutorAgentProvider` -> production `AgentAdapterDirectory`, with enumeration tests | Implemented | Real multi-Agent provider enumeration after reconnect |
| CP-14 | Structured handoff with return path, artifacts, checkpoint, and reason | encrypted handoff ledger and production connector Run events | Implemented | Android process-death and response-return integration test |
| CP-15 | Background orchestration and user-visible Agent teams | `AgentTeamCoordinator`, global collaboration contracts | Partial | Production team dispatch and UI expansion gate |
| CP-16 | Durable idempotency for dangerous or repeatable operations | native-tool receipts, `AgentRunStartReceiptStore`, stable handoff identity, process-recreation and unknown-outcome tests | Implemented | Real-device crash injection between remote acceptance and local receipt commit |
| CP-17 | Protocol version and feature negotiation with graceful downgrade | `AgentProtocolNegotiator` | Implemented | Provider connection compatibility test |
| CP-18 | Real Android, Windows, file, terminal, Office, camera, microphone, notification, browser, and smart-home execution | native-tool registry, Desktop gateway, MCP and Home Assistant connectors | Partial | Capability matrix must show only verified available operations; complete missing Android tools |
| CP-19 | Persistent Runs include goal, plan, steps, results, permissions, device, tools, artifacts, errors, checkpoints, and handoffs | Workspace v2 snapshots, Run stores, handoff ledger, complete process-recreation reconstruction test | Implemented | Real-device interrupted Run inspection in Control Center |
| CP-20 | Model, Agent, tool, Android, device, file, app, temporary, permanent, and consequential-action permission governance | `AgentPermissionGrantLedger`, host safety policy, constrained single-use/temporary/permanent grants, revocation propagation tests | Implemented | Real-device grant, consume, expire, revoke, and active-tool cancellation matrix |
| CP-21 | User-owned memory remains independent from model or Agent | encrypted memory, knowledge, transcript, task, and global world-model stores | Implemented | Export/erase boundary test |
| CP-22 | Generate and version Skills from successful Runs and user corrections | learning engine, conversation Skill compiler, version manager | Implemented | Full real-device learn, review, run, upgrade, rollback test |

## Persistent Personal ASI

| ID | Requirement | Current evidence | Status | Remaining acceptance evidence |
| --- | --- | --- | --- | --- |
| PA-01 | One encrypted Personal ASI exists above all topic conversations | `GlobalSuperAgentRuntime`, repository, acceptance contract | Implemented | Full release gate |
| PA-02 | Topic conversations retain isolated detailed context | transcript/context journal and selector | Implemented | Cross-topic leakage tests |
| PA-03 | Every authorized semantic message, file, decision, task, tool result, and feedback event enters one event bus | host-owned `GlobalEventPublisherContract`, canonical provenance metadata, publication policy, and complete missing/duplicate publisher gate | Implemented | Real-device event-ingress trace across message, attachment, tool, and feedback paths |
| PA-04 | Silent low-cost understanding runs before model escalation | understanding pipeline and cognition gate | Implemented | Performance and model-call budget gate |
| PA-05 | Cross-conversation topic graph and personal world model | world-model and knowledge-graph reducers | Implemented | Causal deletion and contradiction corpus |
| PA-06 | Current-topic suggestion, new-topic notice, urgent alert, and digest output | proactive delivery policy and inbox | Implemented | Real notification/UI test |
| PA-07 | Personal ASI can research or prepare work independently and report verified results | autonomy executor, research coordinator, long-horizon lifecycle | Implemented | External-source and paired-Agent scenario gate |
| PA-08 | Personal ASI can create, open, merge, rename, pause, and delete topic workspaces | conversation lifecycle and Agent-created topic receipts | Implemented | UI lifecycle regression gate |
| PA-09 | Automatic topic creation is visible, attributable, and never silently duplicated | stable topic keys and source receipts | Implemented | Process-death duplicate prevention test |
| PA-10 | Importance threshold, daily budget, cooldown, deduplication, and urgent bypass control proactive messages | proactive budget and learning policies | Implemented | Time-bound instrumentation gate |
| PA-11 | Quick facts, deep research, continuous monitoring, and proactive inference share one evidence system | intelligence acquisition engine and evidence ledger | Implemented | Live-source reliability and citation gate |
| PA-12 | Rules, small/local models, strong models, and specialist Agents are selected by need, privacy, cost, and capability | cognition gate and resource router | Implemented | Routing quality benchmark |
| PA-13 | Session, topic/project, user-global, and realtime context layers are selected rather than dumping history | context selector, world model, realtime provider | Implemented | Token-bound context composition gate |
| PA-14 | Intervention value balances relevance, benefit, urgency, novelty, omission, interruption, uncertainty, and repetition | intervention policy and adaptive feedback | Implemented | Feedback adaptation test corpus |
| PA-15 | Direct, trust-conditioned, and always-confirm autonomy tiers are host-owned | action governance and confirmation policy | Implemented | Consequential-action real-device matrix |
| PA-16 | One-chat UI exposes topic selection, Agent-created markers, suggestions, and global insights without a separate complex dashboard | current Agent UI and insight inbox | Partial | Visual acceptance across phone sizes and all lifecycle states |
| PA-17 | Long-horizon goals, dependencies, checkpoints, recovery, connectivity restoration, and background budgets | goal graph, continuity, scheduler, budget ledgers | Implemented | Reboot and Doze real-device soak test |
| PA-18 | Personal ASI can use native tools, Skills, MCP, specialist Agents, local/cloud models, Desktop, and smart-home resources | host registries and supervised collaboration | Partial | One end-to-end verified scenario per resource class |
| PA-19 | Private mode, paused tracking, causal deletion, retraction, export, and erase boundaries are enforced | publication policy, causal evidence lifecycle, `AgentPrivateDataInventory`, encrypted backup manifest, local-authority exclusions, and reset identity rotation | Implemented | Real-device encrypted backup/restore/reset audit |
| PA-20 | The personal Agent learns calibrated strengths, limitations, route quality, latency, and corrections from terminal Runs without retaining sensitive requests | encrypted `AgentSelfModel`, Run lifecycle projection, bounded router calibration, direct tests | Implemented | Real-device repeated-success, correction, and restore scenario |
| PA-21 | Memory evolves through a candidate inbox, resolved review state, persistent temporal state, an encrypted evolution journal, causal typed-graph retirement, identity/preference/security-aware query planning, prompt compilation, semantic consolidation, long-term themes, and scheduled self-audit | `GlobalMemoryEvolutionPolicy`, `GlobalEntityMemoryGraph`, `GlobalMemoryPromptCompiler`, `GlobalMemoryLoCoMoRegressionTest`, Memory Control Center | Implemented | Real-device candidate review plus process-death, export, causal-deletion, and evolution-history scenario |

## Release Rule

The upgrade is complete only when every row is `Implemented` and its remaining acceptance evidence has passed. A unit test for an isolated model does not prove a production integration, and a configured external service does not prove reliable behavior when it is unavailable.
