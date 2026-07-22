# Personal ASI Acceptance Contract

This document maps the persistent Personal ASI product requirement to host-owned implementation and automated evidence. It is an acceptance contract, not a claim that every external model, network, paired computer, or third-party service is always available.

## Product Boundary

SignalASI owns one encrypted Personal ASI above topic conversations. Conversations remain isolated workspaces, while authorized semantic events contribute to a bounded cross-conversation world model. The Android host owns safety, lifecycle, routing, budgets, provenance, recovery, and delivery. Models and specialist Agents provide untrusted proposals and evidence; they never own authority or completion state.

## Acceptance Matrix

| Requirement | Host implementation | Automated evidence |
| --- | --- | --- |
| Two-layer Agent architecture | `AgentTranscriptStore`, `GlobalAgentRepository`, `GlobalSuperAgentRuntime` | `GlobalPersonalAsiAcceptanceTest`, `GlobalAgentCognitionTest` |
| Silent low-cost understanding | `GlobalUnderstandingPipeline`, `GlobalCognitionGate` | `GlobalAgentCognitionTest`, `GlobalAgentDeliberationTest` |
| Cross-conversation world model | `GlobalWorldModelReducer`, `GlobalTopicProjectGraphReducer` | `GlobalAgentCognitionTest`, `GlobalAgentKnowledgeGraphTest` |
| Bounded context, not transcript dumping | `GlobalConversationContextJournalPolicy`, `GlobalAgentContextSelector` | `GlobalConversationContextJournalTest`, `GlobalAgentCognitionTest` |
| Private and paused tracking boundaries | event publication policy, context selector, delivery router | `GlobalAgentCognitionTest`, `GlobalProactiveDeliveryTest` |
| Causal deletion and retraction | `GlobalAgentEvidenceLifecycle`, retraction markers and delivery cleanup | `GlobalConversationMergeLifecycleTest`, `GlobalAgentContinuityTest` |
| Intervention value and adaptive frequency | `GlobalInterventionPolicy`, `GlobalAgentLearningPolicy` | `GlobalAgentCognitionTest`, `GlobalProactiveInboxTest` |
| Current-topic, new-topic, urgent, and digest delivery | `GlobalProactiveDeliveryPolicy`, `GlobalProactiveConversationRouter` | `GlobalProactiveDeliveryTest` |
| Agent-created topic ownership and source receipt | stable topic keys, parent conversation binding, Open topic action | `GlobalPersonalAsiAcceptanceTest`, `GlobalProactiveDeliveryTest` |
| Proactive discovery without new chat events | `GlobalProactiveDiscoveryCoordinator`, durable discovery leases | `GlobalProactiveDiscoveryTest` |
| Quick, deep, continuous, and proactive research | research plans, evidence ledger, source quality and change gates | `GlobalIntelligenceAcquisitionTest` |
| Dynamic action graphs and replanning | autonomous run planner, dependency graph, review policy | `GlobalAgentLongHorizonTest`, `GlobalAgentDeliberationTest` |
| Native tools, Skills, and specialist Agents | host registry, Skill adapter, guarded planner-to-team compiler, supervised handoff contract, and one-response completion bridge | `GlobalAutonomousToolsTest`, `GlobalAutonomousSkillsTest`, `GlobalAgentCollaborationTest`, `AgentTeamPlanBridgeTest`, `AgentCollaborationRuntimeTest`, `AgentManagedResponsePersistenceTest` |
| Verified completion | native receipts, evidence contracts, contested-result handling | `GlobalAgentLongHorizonTest`, `GlobalAgentCollaborationTest` |
| Long-horizon goals and dependencies | encrypted goal graph, checkpoints, lifecycle outbox | `GlobalAgentLongHorizonTest`, `GlobalAgentKnowledgeGraphTest` |
| Reboot, process-death, service, and connectivity recovery | durable leases, wake scheduler, boot and connectivity restoration | `GlobalAgentContinuityTest`, `GlobalAgentServiceContinuityTest` |
| Battery, network, call, token, cost, and concurrency limits | host background and model-call ledgers | `GlobalBackgroundExecutionBudgetTest`, `GlobalModelCallBudgetTest` |
| Realtime authoritative task state | `GlobalRealtimeContextProvider` | `GlobalRealtimeContextTest` |
| User-visible control and feedback | Control Center, insight inbox, open/merge/rename/pause/delete, adaptive feedback | Android build gate plus policy tests above |

## Required Release Gates

Run all of the following before accepting an Android revision:

```text
apps/android/gradlew.bat :app:testDebugUnitTest --no-daemon
npm run check
npm run check:android
npm run test:release:device
```

The local gates verify host policy and build integrity. The device bundle additionally verifies the Control Center, lifecycle UI, fresh encrypted pairing, notification reply, Home Assistant state-changing execution with readback, typed Windows/file/terminal/Office tools, and paired multi-Agent process-death recovery. Real network research, provider billing telemetry, Android background timing, and unavailable third-party capabilities remain environment-dependent and must fail visibly, preserve durable state, and resume or fall back without weakening privacy or authorization.

## Direct Device Evidence

- `test:android:agent-lifecycle-ui` covers seven lifecycle states at compact and large-phone dimensions, including fixed headers, bounded scrolling, and expandable details.
- `test:android:cross-resource` establishes a fresh secure pairing, exercises Android notification reply and stale-target rejection, verifies Home Assistant service execution through state readback, and executes encrypted Windows, process, file, terminal, Office, and verification tools.
- `test:android:team-paired-process-death` force-stops the phone during a naturally late paired Desktop Agent Run, then proves one synthesized response, no ordinary-chat leakage, and no unapplied completion after recreation.
- Native-tool and local-MCP device reports cover 118 bounded Android operations, real camera and microphone artifacts, and plain plus credential-bound local stdio MCP execution inside the phone runtime.

## Non-Goals

- SignalASI does not expose hidden model chain of thought. It exposes concise progress, tool activity, evidence, uncertainty, and results.
- SignalASI does not treat model text as proof of an external action or objective completion.
- SignalASI does not bypass Android permissions, device-owner restrictions, provider policy, external credentials, or user confirmation for consequential actions.
- SignalASI does not send every conversation or the complete personal world model to every model call.
