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
| Native tools, Skills, and specialist Agents | host registry, Skill adapter, supervised handoff contract | `GlobalAutonomousToolsTest`, `GlobalAutonomousSkillsTest`, `GlobalAgentCollaborationTest` |
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
```

The gates verify host policy and build integrity. Real network research, paired Desktop execution, provider billing telemetry, Android background timing, and third-party capabilities remain environment-dependent and must fail visibly, preserve durable state, and resume or fall back without weakening privacy or authorization.

## Non-Goals

- SignalASI does not expose hidden model chain of thought. It exposes concise progress, tool activity, evidence, uncertainty, and results.
- SignalASI does not treat model text as proof of an external action or objective completion.
- SignalASI does not bypass Android permissions, device-owner restrictions, provider policy, external credentials, or user confirmation for consequential actions.
- SignalASI does not send every conversation or the complete personal world model to every model call.
