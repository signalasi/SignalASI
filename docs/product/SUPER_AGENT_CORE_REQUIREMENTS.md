# SignalASI Super Agent Core Requirements

## Product Definition

SignalASI is a personal super-agent operating system, not a chat client. It accepts outcomes rather than isolated prompts, understands the user's current device and trusted computing environment, selects the best available intelligence and tools, executes work across time, verifies results, and presents durable interactive outputs.

The product should exceed code-only agents by combining the strengths of coding agents, research agents, device automation, private memory, multimodal understanding, and trusted cross-device execution in one user-controlled runtime.

## Global Personal ASI Runtime

SignalASI has one persistent global Agent above all topic conversations. Conversations are bounded workspaces, not separate assistants with separate memories. Every authorized message, task update, tool result, and user feedback event enters the encrypted local cognition pipeline.

The global runtime must:

- Maintain conversation, topic/project, user-global, and realtime state as separate layers.
- Use low-cost local extraction first and request model deliberation only for valuable cross-topic reasoning.
- Link related conversations into one personal world model without sending complete history to every model.
- Turn durable goals into persistent checkpoints that survive app restarts and temporary resource outages.
- Revise autonomous plans from actual tool, research, and Agent outcomes while preserving completed evidence.
- Run independent safe work concurrently, with leases, bounded retries, deduplication, and explicit partial states.
- Keep internal cognition, research, and plan-review responses out of ordinary contact conversations.
- Create or reuse topic conversations for substantial work and deliver only useful results at the right time.
- Require local policy approval for irreversible external effects regardless of which model or Agent proposed them.
- Project remembered authorization, paired Agent, MCP, smart-device, and resource-health transitions into the encrypted world model without exposing endpoints, account fields, tokens, or device identifiers.

Model output never owns lifecycle or safety state. The Android host validates structured cognition, action vocabularies, plan revisions, resource routes, confirmations, retries, and completion evidence before persistence or execution.

### Durable Knowledge and Execution Graphs

The global runtime stores three related encrypted graphs rather than treating conversation history as one flat prompt:

- The topic and project graph links conversations, topics, projects, supporting evidence, and conflicts. Topic nodes can be promoted into durable projects as work becomes substantial.
- The long-horizon goal graph records prerequisite goals, project ownership, checkpoints, completion criteria, and verified completion evidence.
- The autonomous action graph records stable step keys, prerequisite steps, independent branches, leases, retries, and result evidence.

Ready action branches are reserved atomically before execution so multiple background workers may run independent work without dispatching the same step twice. A failed prerequisite blocks only its dependent branch. Completion is accepted only when the host-side verification contract has sufficient evidence; delegated model text alone cannot verify research, device changes, or other objective effects.

## Core Capability Pillars

### 1. Goal and Context Understanding

- Accept text, voice, images, camera input, documents, archives, links, screen context, notifications, and device state.
- Resolve ambiguity using bounded questions instead of guessing destructive intent.
- Maintain Conversation, Turn, Plan, Task, Tool Call, Artifact, and Result identities.
- Preserve relevant context across turns without resending the entire history.
- Distinguish a quick answer from a durable task that can continue in the background.

### 2. Dynamic Planning and Recovery

- Produce an executable action graph with dependencies, budgets, risks, expected evidence, and fallback resources.
- Replan after tool failure, changed screens, unavailable services, partial results, or new user instructions.
- Pause, resume, cancel, retry, branch, and continue tasks after app or device restart.
- Execute independent graph branches concurrently while preserving ordering where dependencies exist.
- Support checkpoints, compensating actions, and explicit rollback where the underlying tool allows it.

### 3. On-Device Perception and Action

- Understand Android UI trees, screenshots, OCR, visual elements, notifications, clipboard, files, and current app state.
- Use deterministic Android capabilities before model-driven interaction when possible.
- Operate apps through Accessibility, intents, content providers, system APIs, and approved device integrations.
- Verify every consequential action against observable post-action state.
- Keep payment, identity, security, installation, deletion, and external communication behind policy controls.

### 4. Universal Resource Orchestration

- Route work across phone tools, phone models, trusted Desktop agents, remote local models, cloud models, MCP servers, skills, web tools, Home Assistant, and custom devices.
- Select resources using capability, trust boundary, health, latency, cost, quality, context size, and user policy.
- Apply bounded fallbacks without weakening privacy or action confirmation.
- Prefer deterministic tools for exact operations and specialist agents for code, research, or long-running work.
- Record observed success, latency, token use, cost, and failure history for future routing.
- Emit capability observations only for material registration, availability, capacity, setup, failure, and recovery changes; repeated heartbeats and same-state health samples must remain silent.

### 5. Multi-Agent Collaboration

- Decompose one goal into specialist subtasks and run safe independent subtasks concurrently.
- Pass structured artifacts and bounded evidence between agents, not untrusted free-form instructions.
- Maintain one supervisor-owned plan and one local safety authority.
- Resolve conflicting agent outputs through evidence, validation, or a stronger verifier.
- Expose subtask state and provenance without flooding the primary output.

### 6. Verification and Evidence

- Define success criteria before execution when the task has side effects or an objective result.
- Validate code with builds/tests, research with citations, live data with timestamps, and device actions with observed state.
- Mark results as verified, partially verified, unverified, or failed.
- Preserve source links, tool receipts, changed files, screenshots, and generated artifacts.
- Never report completion solely because a model said the work was complete.

### 7. Personal Memory and Knowledge

- Maintain user-controlled identity, preference, relationship, task, workflow, and knowledge memories.
- Support local semantic retrieval with source-level citations and access control.
- Project memory and knowledge mutations into the global event stream with causal retraction, so edits, conflicts, access changes, and deletion update the personal world model instead of leaving stale facts.
- Keep local-only knowledge available to on-device cognition while excluding it and its topic graph evidence from generic remote model prompts.
- Separate ephemeral context, task memory, and durable personal memory.
- Allow review, edit, deletion, export, encrypted backup, private mode, and retention policies.
- Learn low-risk preferences only when enabled and never learn secrets by default.

### 8. Safe Autonomy

- Use Observe, Assist, Confirm, and Autonomous policy modes.
- Evaluate risk locally before every side effect, including actions proposed by remote agents.
- Require confirmation for high-risk external communication, payment, deletion, installation, identity, and security changes.
- Provide a global stop control, task cancellation, audit history, and revocation of paired resources.
- Treat model output, web content, retrieved documents, MCP output, and agent output as untrusted data.

### 9. Continuous and Background Operation

- Continue durable tasks through screen changes, app backgrounding, reconnects, and process restart.
- Support schedules, event triggers, notifications, incoming messages, and long-running remote jobs.
- Report meaningful state transitions and only notify the user when attention or a result is available.
- Enforce battery, network, privacy, and execution budgets.
- Synchronize task state across trusted clients without exposing plaintext to the transport.

### 10. Structured Multimodal Results

- Render text, headings, lists, quotations, code, tables, metrics, charts, images, audio, video, links, citations, files, diffs, task progress, tool summaries, approvals, forms, and action controls.
- Preserve rich results as durable conversation records and exportable artifacts.
- Stream incremental blocks without re-rendering the entire conversation.
- Keep content selectable, accessible, localized, and responsive on small screens.
- Allow a result block to reference an artifact or follow-up action without executing arbitrary code.

## Rich Output Architecture

SignalASI uses a versioned structured block document as the canonical result format. Raw HTML is not canonical because unrestricted HTML and JavaScript create security, accessibility, lifecycle, and visual-consistency problems. Markdown and sanitized HTML can be represented as bounded leaf blocks when needed.

The initial native renderer supports:

| Block | Purpose |
| --- | --- |
| `text`, `heading`, `quote` | Prose and semantic hierarchy |
| `code` | Selectable monospaced code with a language label |
| `table` | Horizontally scrollable columns and rows |
| `image` | Bounded local or HTTPS image preview |
| `video`, `audio` | User-controlled media playback |
| `file`, `link`, `citation` | Artifacts, navigation, and evidence |
| `status`, `progress`, `metric` | Task and result state |
| `actions`, `approval`, `form` | Explicit user interaction in later protocol revisions |

Every block must have a stable ID, bounded payload size, declared type, safe URI policy, and optional provenance. Unsupported blocks degrade to a readable text or file representation.

## Priority Delivery

### Foundation

- Versioned rich-output schema and native Android renderer.
- Backward-compatible parsing of existing text, fenced code, and Markdown tables.
- Encrypted persistence of structured blocks per transcript entry.
- Desktop delivery of rich blocks and generated artifact metadata.

### Agent Runtime

- Durable action graph, concurrent independent tasks, checkpoints, and recovery.
- Uniform tool and agent contracts with health-aware routing.
- Verification contracts and evidence collection.
- Streaming task events and block updates.

### Super-Agent Expansion

- Screen and visual understanding across complex apps.
- Local semantic memory and knowledge retrieval.
- Interactive approvals, forms, diffs, charts, and artifact viewers.
- Cross-device task handoff and collaborative agent execution.
- User-defined skills, workflows, MCP resources, and smart-device control.

## Success Metrics

- Goal completion rate with verified evidence.
- Recovery rate after resource or UI failure.
- Median time to first meaningful output and final verified result.
- Percentage of tasks completed with deterministic local tools before model calls.
- User intervention rate by risk category.
- Cost, latency, and token use per successful task.
- Rich-result rendering success and crash-free sessions.
- Memory citation accuracy and user-controlled deletion success.
