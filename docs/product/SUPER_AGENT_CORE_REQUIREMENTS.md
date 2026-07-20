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
- Retry transient event-processing failures with bounded backoff, isolate deterministic failures without dropping evidence, and automatically replay older-version isolation records once after a repaired app upgrade.
- Revise autonomous plans from actual tool, research, and Agent outcomes while preserving completed evidence.
- Run independent safe work concurrently, with leases, bounded retries, deduplication, and explicit partial states.
- Keep internal cognition, research, and plan-review responses out of ordinary contact conversations.
- Create or reuse topic conversations for substantial work and deliver only useful results at the right time.
- Require local policy approval for irreversible external effects regardless of which model or Agent proposed them.
- Project remembered authorization, paired Agent, MCP, smart-device, and resource-health transitions into the encrypted world model without exposing endpoints, account fields, tokens, or device identifiers.

Model output never owns lifecycle or safety state. The Android host validates structured cognition, action vocabularies, plan revisions, resource routes, confirmations, retries, and completion evidence before persistence or execution.

### Proactive Delivery Contract

Global findings are not complete when they are merely stored. The host must close the loop by routing each eligible result to exactly one durable destination:

- Use the source conversation for findings that directly extend the active topic.
- Reuse an existing eligible topic workspace before creating a new one.
- Create one Agent-owned child workspace only when the topic is substantial, no matching workspace exists, and automatic creation is enabled.
- Merge low-priority findings into bounded digest batches without silently consuming overflow items.
- Persist a stable topic ownership key so renaming a workspace does not break future routing.
- Exclude private, tracking-paused, archived, and deleted destinations. A deleted source leaves a bounded tombstone so delayed work cannot recreate it.
- Claim delivery with a durable lease before writing the transcript. A stale claim may recover after restart; transcript dedupe and a stable delivery group make completion idempotent.
- Recheck the adaptive daily budget and per-topic cooldown at delivery time, not only when an insight is first planned.
- Persist the result before notifying. Notifications open the actual destination workspace and are recoverable without repeating old notifications.
- Preserve feedback provenance from the rendered transcript entry to every contributing proactive message.
- Project delivered findings into one local proactive inbox. Digest members share one stable inbox item, while current-topic and Agent-created-topic findings retain their exact durable destination.
- Show a low-interruption new-finding indicator above the composer instead of inserting extra status chatter. Opening the inbox durably marks only delivered findings as viewed.
- Expose the topic, source workspace, delivery class, urgency, destination, and concise result without leaking internal event IDs or model deliberation.
- Let the user open the destination and record Helpful, Not relevant, or Too frequent feedback directly from each finding. Negative feedback removes the item; every response updates the adaptive intervention profile and preserves causal provenance.

### No-Event Proactive Discovery Contract

The global Agent must continue reasoning about accumulated authorized state even when no new conversation event arrives. A local discovery pass periodically reviews the personal world model and durable goal graph for unresolved cross-topic conflicts, material risks, supported opportunities, and stalled goals.

- Scan only shareable, non-expired evidence from active, non-deleted conversations. Local-only or retracted evidence must never enter a delegated discovery prompt.
- Use deterministic local scoring before model deliberation. Low-value observations remain silent and consume no model budget.
- Persist the scan cursor, lease, completion time, emitted finding fingerprints, and daily deliberation budget in encrypted storage.
- Recover an expired scan lease after process death, and use deterministic cognition task IDs so replay cannot dispatch duplicate work.
- Never deliberate the same unchanged finding twice. Materially changed evidence may be reconsidered after a bounded cooldown; failed tasks may retry only after their own recovery window.
- Preserve all contributing evidence roots and conversation IDs in the synthetic review event. Deleting any source conversation invalidates pending cognition, research, autonomous work, and proactive output derived from that finding.
- Route high-value candidates into the existing structured cognition layer. That layer may request proactive inference research, prepare reversible work, update a long-horizon goal, create a topic workspace, or remain silent.
- Schedule the next scan through the durable wake scheduler so discovery continues across app backgrounding and restart without polling the MQTT transport.

### Durable Knowledge and Execution Graphs

The global runtime stores three related encrypted graphs rather than treating conversation history as one flat prompt:

- The topic and project graph links conversations, topics, projects, supporting evidence, and conflicts. Topic nodes can be promoted into durable projects as work becomes substantial.
- The long-horizon goal graph records prerequisite goals, project ownership, checkpoints, completion criteria, and verified completion evidence.
- The autonomous action graph records stable step keys, prerequisite steps, independent branches, leases, retries, and result evidence.

Ready action branches are reserved atomically before execution so multiple background workers may run independent work without dispatching the same step twice. A failed prerequisite blocks only its dependent branch. Completion is accepted only when the host-side verification contract has sufficient evidence; delegated model text alone cannot verify research, device changes, or other objective effects.

### Host-Validated Autonomous Tool Contract

The global Agent may invoke phone-native tools, MCP capabilities, device integrations, and local runtimes from its durable action graph. Tool use follows one host-owned protocol:

- Expose only a bounded, goal-relevant catalog of tools that are registered, currently available, and not blocked. The prompt contains the exact stable tool ID, risk classification, description, and input JSON Schema.
- Treat every model-generated `INVOKE_TOOL` action as a proposal. The Android host resolves the exact registered descriptor again and rejects unknown, stale, unavailable, blocked, or schema-invalid requests.
- Never trust model-provided risk, external-effect, reversibility, permission, consent, or confirmation fields. The registered local descriptor and current local policy are authoritative.
- Apply the existing direct, confirm-once, and always-confirm tiers after validating the tool. Remembered consent remains scoped to the registered action contract and can be revoked locally.
- Keep credentials, access tokens, pairing secrets, and MCP session state outside model prompts. A model receives only the capability contract and the minimum task input needed for selection.
- Bind file and runtime operations to the run workspace, use deterministic idempotency keys where required, and preserve action leases so process recovery cannot dispatch the same side effect twice.
- Persist a native execution receipt containing provenance, duration, input and output hashes, verification state, and an encrypted receipt reference. Raw transport logs are not completion evidence.
- Publish tool success and failure as causally linked global events. Successful discovery actions and non-retryable failures trigger bounded replanning when downstream work remains.
- Accept completion only when the action verification contract is satisfied by a native receipt or stronger evidence. A model summary cannot substitute for observed tool execution.
- Retracting any source event invalidates pending derived tool work and proactive output through the same causal evidence lifecycle used by cognition, research, and long-horizon goals.

### Bounded Conversational Continuity

The global Agent must resolve references inside a topic workspace without resending an entire transcript or relying only on lossy world-model summaries:

- Append authorized semantic events to an encrypted rolling context journal while keeping the normal event queue focused on pending processing.
- Retain only recent user, assistant, attachment, artifact, task-result, tool-result, cognition-result, and feedback evidence. Exclude process chatter, system status noise, private conversations, and paused tracking.
- Limit retention globally and per conversation, truncate individual event payloads, and keep only an explicit metadata allowlist so credentials and transport internals cannot enter a model prompt.
- Select a causal same-conversation window ending at the source event. Exclude the source event itself and any later event so delayed workers cannot reinterpret a request using future instructions.
- Preserve the selected events in chronological order and stop at the first character-budget boundary instead of creating gaps in recent context.
- Mark the entire window as untrusted evidence rather than instructions, then combine it with only the relevant topic graph and shareable world-model layers.
- Purge retracted messages immediately. Deleting a conversation or changing its global visibility to excluded removes its complete journal window before another model call.
- Treat private mode and paused global tracking as bidirectional boundaries: neither publish that conversation into global cognition nor inject cross-conversation world or realtime context into its replies.
- Use the same bounded window for private cognition, research assignments, reversible autonomous preparation, and dynamic plan review.

### Host-Validated Autonomous Skill Contract

Installed Skills are executable workflows, not prompt text. The global Agent may discover and run a Skill only through a host-owned adapter layered on the native tool registry.

- Expose only installed, enabled, auto-invocable Skills whose steps form a validated deterministic native-tool workflow. General Agent-orchestration Skills remain in the conversational delegation path.
- Project each eligible Skill as a stable synthetic tool with a bounded description, exact parameter schema, current availability, aggregate risk, permissions, consent requirements, timeout, and required idempotency key.
- Treat Skill titles, descriptions, trigger examples, resources, and expanded values as untrusted capability data. Never expose Skill resources, secrets, stored credentials, or raw instructions in a model catalog.
- Re-resolve the installation and every underlying native tool immediately before execution. Disabling, deleting, or invalidating a Skill takes effect without restarting the Agent.
- Expand typed parameters locally, preserve the declared dependency order, bind workspace tools to the run workspace, and stop on the first failed step.
- Execute every step through the normal native registry so availability, input and output schemas, Android permissions, consent, cancellation, timeout, idempotency, provenance, and receipts cannot be bypassed by the Skill.
- Derive the workflow confirmation tier from the highest-risk step and the union of required consents. Model-provided safety claims never lower that tier.
- Return a verified workflow receipt only when every step completed and produced host-observed native evidence. Record Skill usage only after full success.
- Keep ordinary, disabled, interactive-only, missing-dependency, and unavailable Skills out of autonomous selection while preserving them for explicit user-controlled workflows.

### Supervisor-Owned Specialist Collaboration Contract

Professional Agent delegation is a host-supervised subtask protocol, not an unrestricted prompt relay. The global Agent owns the plan, assignment identity, safety boundary, fallback order, and evidence state across every specialist.

- Derive the specialist role locally from the action kind and required capabilities. A remote model cannot self-assign authority or broaden the task.
- Give every dispatch a deterministic assignment ID scoped to the run, action, objective, expected result, and selected resource so restart recovery can reject stale or cross-task replies.
- Send a bounded handoff containing the objective, success criteria, allowed operations, prohibited effects, and minimum relevant context. Mark all context, retrieved content, files, and prior Agent output as untrusted evidence.
- Require a structured result envelope with the assignment ID, completion state, concise summary, claims, artifact references, evidence references, uncertainties, and a blocked reason. Never request or persist hidden chain of thought.
- Accept legacy text-only Agent replies for compatible low-risk analysis and drafting, but assign lower evidence confidence. A legacy reply cannot satisfy a stricter read-only verification contract by itself.
- Treat blocked, failed, empty, malformed-contract, and stale-contract replies as failed attempts eligible for health-aware fallback. Record observed success and latency for future routing.
- Persist delegated results and artifacts as unverified evidence with encrypted provenance. An Agent claim is not verified merely because it follows the response schema.
- Detect materially opposed specialist claims locally. Add one deterministic high-priority read-only verifier step that depends on both completed sources; do not duplicate that step on replay.
- Keep independent non-conflicting branches concurrent. Conflict verification must not serialize unrelated work or discard already completed evidence.
- Deliver only the useful summary, findings, artifacts, evidence references, and uncertainty. Internal routing, assignment bookkeeping, and orchestration logs remain outside the user transcript.

### Authoritative Realtime Context

The realtime layer is derived on demand from authoritative encrypted task stores. It is not another model-authored memory summary and is not copied into a second database.

- Project active cognition, research, autonomous runs, plan reviews, long-horizon goals, confirmations, resource waits, failures, and recently completed work into one bounded host-state view.
- Include aggregate cognition-pipeline continuity state so status queries can report pending, retrying, and isolated events without exposing event identifiers, payloads, or failure details.
- Include only work from the current conversation, a materially related topic, or an explicit global-status query. Do not expose every unrelated background task to every model call.
- Exclude deleted conversations and the task currently being executed so a worker cannot mistake its own state for separate work.
- Keep completed work only for a short recency window and retain attention states long enough to support recovery without allowing stale history to dominate current decisions.
- Treat status and progress counters as host-authoritative. Treat titles, goals, and progress text as untrusted evidence rather than instructions.
- Never expose internal task IDs, connector routes, MQTT topics, resource identifiers, access tokens, endpoints, local paths, or raw error logs in the rendered context.
- Add the relevant realtime view to ordinary conversation context, private cognition, independent research, autonomous action execution, and dynamic plan review.
- Use realtime state to avoid duplicate work, answer progress questions accurately, select useful next actions, and revise plans from actual execution state.

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
