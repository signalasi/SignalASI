# Mobile-Native Super Agent Implementation Specification

Status: Authoritative product and engineering specification  
Protocol dependency: `docs/protocol/Phone-Native-Tool-Session-v1.md`  
Related specifications: `SUPER_AGENT_CORE_REQUIREMENTS.md`, `Super-Agent-Rich-Output.md`, `SignalASI-Link-Protocol.md`, and `TRUST_MODEL.md`

## 1. Purpose

SignalASI SHALL provide a mobile-native personal Agent with the useful reasoning and tool-use qualities of Codex, Claude Code, and Hermes while keeping the phone, not a model provider or paired computer, in control of the user's task.

The phone owns:

- The canonical workspace, task graph, state, permissions, consent decisions, audit journal, and artifacts.
- Resolution of model-proposed tool calls into locally registered capabilities.
- Execution of phone-side effects and verification of their observable results.
- Pause, resume, cancellation, recovery, retention, export, and deletion.

A local, private-network, paired-Desktop, remote, or cloud model MAY provide reasoning. It MUST NOT acquire ambient Android authority, mutate the phone workspace directly, approve its own permissions, or report a phone-side effect as complete without phone-generated evidence.

This document is English-first. Protocol names, identifiers, JSON fields, error codes, source comments, diagnostics, and normative documentation MUST use English. User-facing content MAY be localized.

The key words MUST, MUST NOT, REQUIRED, SHALL, SHALL NOT, SHOULD, SHOULD NOT, MAY, and OPTIONAL are normative.

## 2. Product Definition

The Mobile-Native Super Agent is a phone-resident execution supervisor, not merely a chat screen and not a remote terminal. It accepts an outcome, gathers bounded context, chooses one or more reasoning resources, produces a locally validated plan, executes approved capabilities, verifies results, and preserves durable rich output.

The target experience combines:

- Codex-like workspace reasoning, file inspection, exact patches, tests, artifacts, and observable task progress.
- Claude Code-like structured planning, tool use, long-running session continuity, and coding specialization.
- Hermes-like research, web, Skills, MCP, and broad tool orchestration.
- Android-native perception, UI action, notifications, media, system handoffs, hardware observations, and user consent.
- SignalASI trust, encrypted pairing, contact-based resources, local policy, and cross-device lifecycle visibility.

Product success means the user can start and control a durable task from the phone even when reasoning is delegated elsewhere. It does not mean that an ordinary Android app can bypass Android security or perform privileged operations.

## 3. Non-Negotiable Invariants

1. **Phone authority.** The phone is the final policy and execution authority for phone-owned sessions.
2. **Workspace ownership.** The canonical workspace is app-private phone storage. Remote resources receive bounded inputs and return proposals or artifacts.
3. **No model self-authorization.** Model, Agent, MCP, Skill, web, and retrieved content are untrusted data.
4. **Capability truth.** Only currently available, implemented, and authorized tools are advertised as executable.
5. **No privilege fiction.** Root, Shizuku, Device Owner, silent install, lock-screen bypass, secure-surface capture, and similar capabilities MUST NOT be presented as ordinary app operations.
6. **Local side-effect gate.** Every phone side effect is risk-rated and revalidated locally immediately before execution.
7. **Visible high-risk consent.** Payments, purchases, external messages, credential use, identity/security changes, deletion, installation, and other high-risk actions require a user-controlled flow and MAY remain prohibited.
8. **Observable completion.** A side effect is complete only after the phone or the authoritative external controller observes acceptable evidence.
9. **Bounded autonomy.** Calls, graph depth, parallelism, context, storage, time, cost, battery, and network use are bounded.
10. **Durable identity.** Session, conversation, turn, task, workspace, tool call, artifact, and result identities are stable and correlated.
11. **Safe degradation.** Unsupported tools and rich blocks fail visibly or degrade to readable fallback content. They do not trigger substitute side effects.
12. **User control.** The user can inspect, pause, resume, cancel, export, delete, and revoke the resources involved in a task.

## 4. Scope

### 4.1 In scope

- A native Android supervisor and task workspace.
- On-device, private-network, paired-Desktop, remote, and cloud reasoning providers.
- Deterministic phone tools and constrained tool adapters.
- App-private files, exact patches, archive handling, and artifacts.
- Sandboxed script execution when an explicit phone runtime is installed.
- Web fetch/search/browser handoff with provenance and freshness.
- OCR, screen perception, camera, microphone, audio, video, and document inputs.
- Accessibility-mediated UI inspection and action.
- Notifications, clipboard, intents, system screens, alarms, and app navigation.
- Honest hardware and smart-device capabilities.
- MCP clients and MCP resources behind the phone policy layer.
- Built-in and user-installed Skills.
- Supervisor-owned subagents and bounded parallel reasoning.
- Versioned rich output and interactive approvals/forms.
- Durable lifecycle, recovery, receipts, audit, and acceptance gates.

### 4.2 Out of scope unless a separately reviewed implementation is delivered

- Silent package installation or uninstallation.
- Device wipe, factory reset, or silent protected-setting changes.
- Unlocking the phone, bypassing lock screens, or automating authentication challenges.
- Reading `FLAG_SECURE` content or bypassing app anti-automation controls.
- Root command execution.
- Treating Shizuku or Device Owner as available without separate installation/provisioning, explicit authorization, a dedicated adapter, and a stricter policy profile.
- Silent background camera or microphone capture.
- Secure-element, payment, or general NFC transaction automation.
- Automatic purchase, transfer, checkout, credential disclosure, or login approval.
- Unrestricted cross-app messaging or call control.
- A general Android shell in the normal application sandbox.
- A claim that Android can inspect or control every installed app or hardware component.

## 5. System Roles and Trust Boundaries

| Role | Responsibility | Trust boundary |
| --- | --- | --- |
| Mobile Supervisor | Owns session, plan, policy, workspace, execution, verification, and user interaction | Trusted for phone policy and local state |
| Native Tool Registry | Describes and invokes implemented phone tools | Trusted code; availability is probed at invocation time |
| Reasoning Provider | Proposes plans, tool calls, transformations, or answers | Untrusted output; no ambient phone authority |
| Paired Desktop | Hosts local Agents, models, MCP servers, and computer-side workspaces | Trusted only for capabilities explicitly paired and advertised |
| MCP Server | Exposes typed resources, prompts, and tools | Untrusted service behind local capability and policy gates |
| Skill | Declares a reusable workflow and required capabilities | Trusted only to the installed package/signature level; no permission expansion |
| Subagent | Performs one bounded subtask | Receives least-privilege context and cannot bypass the supervisor |
| External Controller | Home Assistant or another configured device service | Authoritative only for its own reported state, not physical-world certainty |
| Transport | SignalASI Link, HTTPS, or private-network channel | Untrusted for authorization; encrypted transport does not imply tool permission |

The Mobile Supervisor MUST treat provider identity, transport trust, Android permission state, special access, user consent, and tool availability as separate facts.

## 6. Reference Architecture

```text
User input and phone context
          |
          v
Mobile Supervisor and local policy
          |
          +--> Phone workspace and encrypted journal
          |
          +--> Resource router
                 |--> On-device model
                 |--> Direct cloud or private-network model
                 |--> Paired Desktop Agent (Codex, Claude Code, Hermes, custom)
                 |--> MCP server or Skill
          |
          v
Locally validated action graph
          |
          v
Native Tool Registry --> permission/consent gate --> phone execution authority
          |                                              |
          +---------------- receipt and evidence <-------+
          |
          v
Verification, recovery, artifacts, and rich output
```

### 6.1 Control plane

The phone control plane SHALL:

- Normalize the user goal and input attachments.
- Build a current resource and capability catalog.
- Select reasoning providers based on capability, trust, privacy, health, latency, quality, context size, and cost.
- Validate every proposed graph node against a stable local tool or resource identifier.
- Enforce risk, permission, consent, idempotency, budgets, and policy mode.
- Serialize conflicting phone side effects while allowing safe read-only reasoning to run concurrently.
- Verify outputs and decide whether to complete, retry, replan, pause, or fail.

### 6.2 Data plane

The data plane MAY execute:

- App-private file operations.
- Sandboxed scripts with no ambient Android permissions.
- Network and web operations allowed by the session policy.
- Android Accessibility actions.
- Per-session MediaProjection and OCR.
- Camera, microphone, media, and document operations with appropriate consent.
- Intents and Android system handoffs.
- Notification and clipboard operations within platform limits.
- Configured smart-device commands.
- Encrypted calls to paired Desktop resources.
- Direct calls to configured local or cloud model APIs.

The data plane MUST return structured receipts. A textual model assertion is not a receipt.

## 7. Phone-Owned Workspace

### 7.1 Canonical structure

Every durable task SHALL have one app-private workspace identified by `workspace_id` and bound to `session_id`, `conversation_id`, and `task_id`.

The logical layout SHOULD be:

```text
workspaces/<workspace_id>/
  inputs/
  files/
  scripts/
  downloads/
  screenshots/
  outputs/
  logs/
  temp/
  manifest.json
```

The physical layout MAY differ, but external components MUST use workspace-relative paths and opaque artifact references.

### 7.2 Required workspace behavior

- Paths MUST be relative, normalized, and confined to the workspace root.
- Absolute paths, traversal, symlinks, special files, and archive path escape MUST be rejected.
- Reads, writes, search, patches, hashing, copy, move, delete, ZIP creation, listing, and extraction MUST enforce bounded sizes and counts.
- Mutations SHOULD be atomic where the platform supports it.
- Exact patches MUST fail on an unexpected occurrence count.
- Every mutation MUST create a tool receipt and SHOULD create a checkpoint before consequential changes.
- External user files MUST enter through Android Storage Access Framework or another user-authorized content URI flow.
- Export MUST use a user-visible destination or a share flow. The Agent MUST NOT infer broad external-storage access.
- Secrets and credentials MUST NOT be copied into a task workspace unless the user explicitly supplies them for that task and the target policy permits it.
- Temporary data MUST be deleted on expiry, task deletion, or explicit cleanup. Saved outputs require an explicit retention class.

### 7.3 Remote reasoning over a phone workspace

A remote provider SHALL NOT mount or own the phone workspace. The phone MAY send:

- A bounded file manifest.
- Selected file contents or chunks.
- Digests, diffs, search results, OCR, and screenshots.
- Opaque artifact handles whose bytes are fetched through an authenticated, task-scoped channel.

The provider returns a proposed patch, generated artifact, or typed tool call. The phone validates and applies it locally. A paired Desktop MAY maintain a mirror or temporary computer workspace, but that mirror is not canonical and MUST be identified as Desktop-owned evidence.

## 8. Reasoning Providers

### 8.1 Provider classes

| Provider | Typical use | Required constraint |
| --- | --- | --- |
| On-device model | Private classification, short reasoning, OCR/vision support | Available only when a real runtime and model are installed |
| Direct private-network model | Local LLM on a trusted network | Endpoint health, TLS/network policy, and explicit trust boundary |
| Direct cloud model | High-quality reasoning and multimodal generation | Explicit provider configuration and bounded data disclosure |
| Paired Desktop Codex/Claude Code | Repository and code-specialist tasks | Desktop capability manifest, isolated Desktop workspace, task lifecycle, no phone-side authority |
| Paired Desktop Hermes | Research, Skills, MCP, and synthesis | Same local policy and provenance requirements |
| Custom Agent | User-defined specialist | Explicit command/endpoint, typed capability declaration, least privilege |

### 8.2 Provider contract

Each provider MUST expose:

- Stable provider and resource IDs.
- Location and trust boundary.
- Health and setup state.
- Supported input/output modalities.
- Context and output limits.
- Tool, MCP, Skill, code, web, and vision support.
- Streaming and cancellation support.
- Cost and latency information when available.
- Data retention and privacy metadata when known.

Provider output MUST be either a user answer, a validated plan proposal, a bounded tool-call proposal, a patch/artifact proposal, or a subtask result. Hidden chain-of-thought is never required or persisted.

### 8.3 Routing rules

- Deterministic phone tools SHOULD answer exact local operations before a model call.
- Private mode MUST exclude cloud providers.
- A model without live tools MUST NOT answer freshness-sensitive work as verified current information.
- An unavailable provider MUST remain unavailable; the router MUST NOT silently weaken privacy or use an unrelated provider.
- Fallbacks MUST preserve risk and consent requirements and MUST be bounded.
- Explicit user provider selection SHOULD be honored unless it conflicts with policy or availability.

## 9. Native Tool Families

All tools SHALL use the descriptor, invocation, receipt, and error contract in `Phone-Native-Tool-Session-v1.md`.

| Family | Required capabilities | Android reality and product boundary |
| --- | --- | --- |
| Workspace files | List, stat, bounded read/write, search, exact patch, diff summary, hash, copy/move/delete, ZIP | App-private workspace only by default; external files require user-authorized URI access |
| Scripts | Run a script, capture stdout/stderr, enforce time/memory/network/file limits | Requires an explicit sandboxed runtime; no general shell, `su`, or ambient package access |
| Web | HTTPS fetch, search connector, browser handoff, citations, downloads | Network policy applies; signed-in browser state and cross-origin behavior are not assumed |
| OCR and screen | Accessibility tree, screenshot, OCR, grounded elements, post-action observation | Accessibility special access and per-session MediaProjection consent; secure content remains unavailable |
| Documents | Import text/PDF and selected files, extract bounded content, cite source | User-selected content only; parsing is untrusted and size-limited |
| Camera and microphone | User-visible capture, voice input, ASR | Runtime permission and privacy indicator; no silent unattended capture |
| Media | Image preview, audio/video playback, metadata, bounded conversion when implemented | Playback is codec-dependent; general transcoding MUST remain unavailable until implemented |
| UI action | Tap, long press, swipe, type, clear/paste, back, home, recents | Accessibility/focus/coordinates are fallible; protected screens and confirmations cannot be bypassed |
| Notifications | Read posted fields and reply through live `RemoteInput` | Notification-listener access; reply only when the notification explicitly supports it |
| Clipboard | Foreground-limited read and explicit write | Background restrictions and sensitive clipboard behavior apply |
| System handoff | Open apps, URLs, settings, dialer/composer, file picker, contacts, alarms/timers | Handoff does not prove completion; protected settings and submission remain user/system controlled |
| Hardware observation | Battery and network state; later location/sensors/Bluetooth/NFC | Only implemented and permissioned probes may be advertised |
| Smart devices | Read state and invoke allowlisted Home Assistant/custom services | Requires explicit configuration, entity/service scope, confirmation, and controller evidence |

### 9.1 Script execution requirements

Phone-native scripts are a future capability until a sandbox runtime is present. When implemented:

- The runtime MUST be embedded or explicitly installed and versioned.
- It MUST run without a general Android shell and without `su`.
- The default filesystem view MUST contain only the task workspace.
- Network, clock, randomness, clipboard, sensors, and other host functions MUST be explicit capabilities.
- Process creation, native library loading, package installation, and arbitrary Binder access MUST be denied by default.
- CPU time, wall time, memory, output, recursion, and file counts MUST be bounded.
- Cancellation MUST stop the runtime cooperatively and, where supported, forcibly.
- Script output is untrusted and MUST pass the same local side-effect gate as model output.

Desktop CLI execution is not phone-native script execution. It occurs in a Desktop-owned workspace and SHALL be labeled accordingly.

## 10. MCP

SignalASI SHALL support MCP as a resource protocol, not as a permission bypass.

- The phone MAY act as an MCP client to an on-device, private-network, cloud, or paired-Desktop server.
- Native Android stdio MCP hosting MUST be advertised only when a real process/runtime implementation is installed.
- MCP tools MUST be translated into the local typed tool contract with source server, tool name, schema, trust boundary, and health.
- The phone MUST re-evaluate risk and consent before every MCP side effect.
- MCP resources and prompts are untrusted context and MUST be size-limited and provenance-labeled.
- Server roots MUST NOT grant access outside the approved workspace or selected content URIs.
- OAuth, API keys, and server credentials MUST stay in the boundary selected by the user and MUST NOT be sent to reasoning providers.
- Server instructions cannot override system policy, user instructions, or Android constraints.
- A disconnected or schema-changing server MUST fail visibly and MAY require reapproval.

The initial interoperable path MAY use a paired Desktop MCP wrapper. Full phone-native MCP hosting is not implied by that path.

## 11. Skills

A Skill is a versioned, reviewable workflow package that declares:

- Skill ID, version, publisher, signature/trust state, description, and localization keys.
- Input and output schemas.
- Required tool, MCP, provider, and data capabilities.
- Risk class, confirmation points, and supported policy modes.
- Entry steps, bounded branching, retries, timeouts, and expected evidence.
- Workspace templates and resource limits.
- Network domains and external services, if any.

Skills MUST NOT contain a hidden permission grant or expand the authority of their caller. Installation, update, and first use of a Skill with side effects require user review. A Skill from a paired Desktop is a remote resource until installed and verified on the phone. A standalone manifest parser, validator, store, or graph expander is not an end-to-end Skill runtime until it is integrated with installation review, signatures, task supervision, the native tool registry, and receipts.

## 12. Subagents and Multi-Agent Work

The Mobile Supervisor MAY decompose a task into specialist subtasks. It SHALL remain the sole owner of the parent plan.

- Every subagent has a distinct `task_id`, parent task ID, purpose, input budget, output schema, deadline, and allowed resources.
- Only the minimum required context and artifacts are shared.
- Independent reasoning-only subtasks MAY run concurrently.
- Conflicting phone side effects MUST be serialized by phone execution authority.
- Subagent output is untrusted evidence, not an instruction with inherited authority.
- One subagent MUST NOT directly invoke another beyond the configured graph-depth limit.
- Results MUST include provider, model/Agent, tool, source, and artifact provenance.
- The supervisor resolves disagreement using evidence, deterministic validation, or an explicit verifier.
- Cancellation of a parent MUST propagate to active children.
- User-visible progress SHOULD summarize subtasks without exposing private chain-of-thought.

## 13. Permission and Risk Model

### 13.1 Policy modes

| Mode | Behavior |
| --- | --- |
| Observe Only | No side effects; only authorized observation and analysis |
| Suggest Only | Plans and drafts are allowed; execution is disabled |
| Ask Before Action | Every side effect requires confirmation |
| Auto Low Risk | Only locally classified low-risk, reversible actions may auto-run; medium/high remain confirmed |

The mode is a ceiling, not a grant. Android permissions, special access, per-session consent, tool availability, and action-specific confirmation still apply.

### 13.2 Risk classes

- **Low:** read-only or readily reversible local operation with no sensitive disclosure.
- **Medium:** local mutation, app navigation, clipboard write, network disclosure, or system handoff.
- **High:** external communication, sensitive capture, device control, deletion, installation handoff, identity/security settings, or consequential UI action.
- **Blocked:** prohibited by product policy or unavailable to an ordinary application.

High-risk confirmation MUST bind to the exact task, tool ID/version, normalized arguments digest, target, consequence summary, and expiry. A changed proposal invalidates the confirmation.

### 13.3 Sensitive categories

The runtime MUST use a stricter gate for:

- Credentials, secrets, one-time codes, private keys, and account recovery.
- Payments, transfers, purchases, orders, and subscriptions.
- Identity, security, login, permissions, installation, and device administration.
- External messages, calls, posts, and submissions.
- Destructive file, app, account, or device operations.
- Camera, microphone, screen capture, location, notifications, health, and personal data.

The runtime MAY refuse a category even after a request for confirmation.

## 14. Android Platform Constraints

| Capability | Required status |
| --- | --- |
| Accessibility UI tree and gestures | User-enabled special access; limited to exposed active-window semantics; cannot bypass secure UI |
| Screen capture and OCR | Per-session MediaProjection consent with foreground service; `FLAG_SECURE` remains hidden |
| Notifications | User-enabled listener; only posted content; reply requires live free-form `RemoteInput` |
| Clipboard | Foreground/platform limited; no promise of background read or paste completion |
| Camera and microphone | Runtime permission and user-visible capture; no silent background capture |
| Installed apps | Partial package visibility only; never a complete census |
| Settings | System screen handoff only for protected settings |
| Package installation | Not implemented; ordinary apps can at most invoke the system installer with user approval |
| Location, sensors, Bluetooth, NFC | Not executable until dedicated implementations and permissions exist |
| General media transcode | Not executable until a bounded pipeline exists |
| Device Owner | Privileged provisioning only; the app cannot self-elevate |
| Shizuku | Separate service and authorization required; no bundled integration at present |
| Root | Product-policy blocked; never advertise as ready |
| Lock screen and secure surfaces | No bypass, unlock, credential entry, or protected-screen capture |
| Calls, payments, and third-party sends | Protected or blocked; composer/dialer handoff is not submission |
| Background work | Subject to foreground-service types, Doze, process death, battery policy, and OEM restrictions |

## 15. Task Lifecycle

The user-visible lifecycle SHALL include:

```text
created -> planning -> waiting_confirmation -> executing -> verifying
                         |                  |          |
                         v                  v          v
                       paused <--------- recovering <-+
                         |
                         +-> blocked -> planning | waiting_confirmation | failed
                         +-> completed | failed | cancelled | expired
```

Remote reasoning MAY add `waiting_provider` and remote tasks MAY report accepted, queued, running, waiting_user, waiting_approval, paused, completed, failed, cancelled, or timed_out. The phone maps those events into its canonical workspace state.

Required behavior:

- Persist state before dispatching a side effect.
- Persist monotonically ordered events and tool-call records.
- Checkpoint before consequential mutations where rollback or recovery is meaningful.
- Reconcile nonterminal tasks after process or device restart.
- Never replay a non-idempotent side effect merely because an acknowledgement was lost.
- Support pause, resume, cancel, bounded retry, and replan.
- Propagate cancellation to remote providers and subagents when supported.
- Mark unresolved remote cancellation honestly.
- Complete only after required verification and artifact finalization.

## 16. Rich Output

The canonical result format is `Super-Agent-Rich-Output` version 1.

- Plain text fallback is mandatory.
- Native blocks MAY include prose, headings, quotes, code, tables, images, audio, video, files, links, citations, status, progress, metrics, tool activity, diffs, charts, actions, approvals, and forms.
- Raw executable HTML or JavaScript MUST NOT be canonical output.
- Artifact URIs MUST be authenticated, task-scoped, expiring where remote, and opened only through allowlisted handlers.
- Interactive actions MUST use controlled verbs and MUST bind to a live local task or create a new locally reviewed request.
- Unsupported blocks remain inert and render fallback text.
- Streaming updates MUST be monotonic and idempotent.

## 17. Functional Requirements

| ID | Requirement |
| --- | --- |
| MNSA-001 | The phone SHALL create a durable session and app-private workspace before dispatching remote reasoning. |
| MNSA-002 | The phone SHALL expose only tools whose implementation, platform, permission, special access, consent, and configuration state are truthfully known. |
| MNSA-003 | Every provider proposal SHALL be parsed against a bounded schema and resolved to locally known IDs. |
| MNSA-004 | Every side effect SHALL pass the local risk and consent gate immediately before execution. |
| MNSA-005 | Conflicting phone side effects SHALL be serialized; safe read-only reasoning MAY run concurrently. |
| MNSA-006 | File operations SHALL remain within the phone workspace unless a user-authorized content URI is explicitly used. |
| MNSA-007 | A remote provider SHALL return proposals or artifacts and SHALL NOT directly mutate the canonical phone workspace. |
| MNSA-008 | Tool results SHALL contain receipt, provenance, timing, normalized status, and verification evidence where applicable. |
| MNSA-009 | Non-idempotent calls SHALL NOT be automatically replayed after ambiguous delivery. |
| MNSA-010 | The Agent SHALL support pause, resume, cancellation, recovery, and restart reconciliation. |
| MNSA-011 | MCP and Skills SHALL use the same local policy and tool-result contract as built-in capabilities. |
| MNSA-012 | Subagents SHALL receive bounded context and authority and remain subordinate to one phone supervisor. |
| MNSA-013 | Rich output SHALL preserve fallback text, identity, provenance, and safe artifact references. |
| MNSA-014 | The UI SHALL distinguish unavailable, setup-required, consent-required, limited, privileged-only, policy-blocked, and failed capabilities. |
| MNSA-015 | The Agent SHALL never imply that a handoff, model statement, tap, or transport acknowledgement proves the real-world outcome. |

## 18. Acceptance Criteria

### 18.1 Workspace and files

- A task creates a phone workspace bound to all required identities and recovers it after process restart.
- Absolute paths, `..` traversal, symlinks, ZIP slip, oversized data, excessive entries, and compression bombs are rejected with stable errors.
- Read, write, search, exact patch, hash, archive, and artifact flows produce deterministic receipts.
- A remote coding provider can inspect selected phone-workspace files and return a patch that the phone validates and applies locally.
- External file import/export requires a user-mediated content URI or share flow.

### 18.2 Provider and tool protocol

- At least one on-device or direct-model path and one paired-Desktop Agent path complete a session through the same phone-owned protocol.
- Unknown tools, unknown versions, invalid schemas, stale confirmations, and unavailable capabilities fail before execution.
- Permission and consent tests prove that provider output cannot self-grant access.
- Idempotency tests prove that successful keyed calls are replay-safe and non-idempotent ambiguous calls are not repeated.
- Cancellation and timeout propagate to the active tool/provider and produce a terminal receipt.

### 18.3 Android action and platform honesty

- Accessibility, MediaProjection, notification, camera, and microphone flows display the Android-required user controls.
- Secure surfaces remain unreadable and are reported as unavailable, not empty success.
- Settings, dialer, SMS, installer, and picker flows are labeled as handoffs until post-state evidence exists.
- Root remains policy-blocked even on a rooted test device.
- Location, sensors, Bluetooth, NFC, package installation, general transcoding, Shizuku, and Device Owner are not advertised as ready without real implementations.

### 18.4 Safety and verification

- High-risk confirmation is bound to the exact action digest and cannot authorize changed arguments.
- Parent cancellation stops or marks unresolved all children and prevents later phone side effects.
- A model claim of success without phone/controller evidence is shown as unverified.
- Restart recovery does not duplicate an alarm, message, delete, purchase, or device command.
- Audit export identifies provider, tool, inputs digest, consent record, receipt, evidence, and artifacts without exposing secrets.

### 18.5 Rich output and user control

- Every supported block renders natively with accessible fallback.
- Unknown blocks remain readable and inert.
- Remote artifact links are authenticated and open from Android without exposing arbitrary local paths.
- Approval and form actions affect only the bound local task.
- The user can inspect, pause, resume, cancel, export, delete, and revoke a task and its resources.

### 18.6 Release evidence

Release readiness requires automated unit, integration, Android build, real-device, Desktop connector, encrypted Link, lifecycle, and recovery evidence. Existing `npm run check`, `npm run check:android`, Desktop smoke gates, Android device smoke gates, and protocol tests SHALL be extended rather than treated as proof of capabilities they do not exercise.

## 19. Delivery Plan

### Phase A: Contract and phone workspace

- Stabilize the native tool descriptor and Phone-Native Tool Session v1.
- Integrate encrypted workspace state and app-private file tools into `MobileNativeAgent`.
- Bind workspace, tool call, receipt, artifact, task, and transcript identities.
- Add session recovery, cancellation reconciliation, and capability diagnostics.

### Phase B: Provider adapters

- Adapt direct cloud/private-network models and paired Desktop Agents to the same proposal/result protocol.
- Implement bounded file-context exchange and phone-applied patches.
- Add artifact upload/download with authenticated task-scoped references.
- Normalize provider streaming, usage, cost, and cancellation.

### Phase C: Native tool coverage

- Register existing Android deterministic actions through the native registry.
- Add safe document, web, OCR, media, and user-authorized external-file adapters.
- Keep unavailable hardware and privileged paths explicitly unavailable.
- Add a sandboxed phone script runtime only after a separate security review.

### Phase D: MCP, Skills, and subagents

- Add typed MCP discovery, schema pinning, credentials isolation, and phone-side policy.
- Add signed/reviewable Skill packages.
- Add parent/child task graphs, bounded parallel reasoning, and consolidated evidence.
- Add cross-device handoff without transferring phone authority.

### Phase E: Product hardening

- Complete real-device restart, Doze, offline, process-death, and cancellation tests.
- Add retention, export, deletion, revocation, and privacy controls.
- Conduct threat modeling, abuse testing, dependency review, and public cryptographic review before production-grade claims.

## 20. Current Repository Implementation Matrix

Snapshot basis: source tree inspected on 2026-07-21. `Implemented` means source exists in the current repository; it does not by itself mean release-grade or real-device accepted. `Scaffold` means a contract or isolated implementation exists but is not wired end to end.

| Capability | Status | Current repository evidence | Required next work |
| --- | --- | --- | --- |
| Mobile supervisor loop | Partial | `apps/android/.../MobileNativeAgent.kt` implements planning, confirmation, execution, verification, recovery, pause/resume/cancel, and persistence | Move all execution through the v1 native tool/session contract and durable workspace |
| Phone execution authority | Partial | `PhoneExecutionAuthority.kt` serializes phone side effects and tracks cancellation | Bind authority to durable task/workspace IDs and native tool receipts; add restart reconciliation |
| Guarded model reasoning | Partial | `GuardedModelAgentPlanner.kt` uses a configured cloud contact, local validation, sensitive-context fallback, and deterministic fallback | Generalize to all provider classes and Phone-Native Tool Session messages |
| Resource routing | Partial | `AgentResourceRouter.kt` catalogs and scores phone tools, cloud/local models, Desktop Agents, MCP/Skill-like contacts, and devices | Consume protocol capability manifests and enforce one canonical health/policy model |
| Durable phone workspace state | Scaffold | `AgentWorkspaceStore.kt` defines encrypted bounded workspace, journal, calls, checkpoints, artifacts, revision checks, and recovery queries | Integrate with `MobileNativeAgent`, transcript/task stores, lifecycle events, and cleanup |
| App-private workspace files | Scaffold | `AgentWorkspaceFileTools.kt` provides confined file operations, exact patching, hashing, and ZIP defenses with unit tests | Wire as registered native tools and bind every mutation to policy, receipt, checkpoint, and artifact records |
| Native tool contract | Scaffold | `AgentNativeToolRegistry.kt` defines schemas, permission/consent gates, availability, timeout, cancellation, idempotency, verification, receipts, and provenance | Register production Android tools and route `MobileNativeAgent` through the registry |
| Default native tool catalog | Scaffold | `AgentPhoneNativeToolCatalog.kt` registers the app-private workspace tools and selected existing Android actions with typed descriptors and live capability probes | Instantiate it in the production supervisor, bind real task/workspace context, and persist all receipts |
| Honest phone capability catalog | Scaffold | `AgentPhoneCapabilityCatalog.kt` distinguishes ready, limited, consent/setup required, not implemented, privileged-only, and blocked capabilities | Surface diagnostics in product UI and use them for live tool advertisement |
| Accessibility UI tools | Partial | `SignalASIAccessibilityService.kt`, `AgentSpecializedAppPlanner.kt`, `AgentVisualGrounding.kt`, and the Android executor support tree/gesture flows | Register typed tools, bind exact post-state evidence, and broaden real-device coverage without weakening constraints |
| OCR and screen capture | Partial | `AgentScreenCaptureService.kt`, ML Kit dependency, and `AgentVisualGrounding.kt` provide MediaProjection/OCR paths | Formalize per-session consent, capture lifecycle, artifact retention, and secure-surface errors |
| Notifications | Partial | `AgentNotificationNativeTools.kt` registers bounded, redacted reads and guarded `RemoteInput` replies with stale-target rejection, explicit confirmation binding, and dispatch-only receipts | Complete real-device notification read/reply acceptance across supported apps |
| Clipboard and system intents | Partial | `AgentSystemTools.kt` and `AndroidAgentActionExecutor` implement clipboard, app/settings/URL/dialer/composer/picker, alarms, and navigation paths | Distinguish handoff from completion in receipts and migrate to native descriptors |
| Camera, microphone, voice ASR/TTS | Partial | `AgentVisibleCaptureNativeTools.kt` provides explicit foreground photo/audio descriptors, bounded capture activities, conversation artifacts, runtime permissions, and privacy lifecycle tests; ASR/TTS settings and runtimes remain separate | Complete real-device capture, cancellation, process-death, and artifact-retention acceptance |
| General media transcode | Not implemented | `AgentPhoneCapabilityCatalog.kt` explicitly marks `MEDIA_TRANSCODE` not implemented | Add a bounded codec pipeline before advertising the capability |
| Location, sensors, Bluetooth, NFC | Not implemented | Capability catalog explicitly marks each not implemented; manifest lacks their runtime permissions | Add dedicated implementations and policies separately, or continue to advertise unavailable |
| Package installation | Not implemented | Catalog marks installer handoff not implemented; system-tools planner blocks installation/removal and device wipe | Any future installer handoff must remain system/user mediated and separately accepted |
| Device Owner and Shizuku | Privileged only | Capability catalog marks both non-normal-app capabilities and notes no controller/integration | Keep unavailable unless separately provisioned, implemented, reviewed, and policy-scoped |
| Root | Policy blocked | Capability catalog explicitly refuses `su` execution even if a binary is detected | No implementation planned under this specification |
| Direct cloud model contacts | Partial | `CloudModelClient.kt`, `AppStoreAgentConnectorRegistry`, and Android product flows call configured providers directly | Normalize provider contract, privacy metadata, streaming, usage, artifacts, and cancellation |
| Desktop native tools | Partial | `desktop_native_tools.py`, encrypted `desktop_tool_call_*` Link messages, Android `AgentDesktopRemoteNativeTools`, capability manifests, durable receipts, cancellation, and per-phone workspace namespaces provide typed Windows status/process, workspace file/archive, terminal, and Office operations | Complete paired-phone real-device execution, disconnect/reconnect, cancellation, and ambiguous-restart acceptance |
| Desktop Codex, Claude Code, Hermes | Partial | `agent_gateway.py`, `codex_app_server.py`, `agent_task_manager.py`, and Link bridge expose local CLI/App Server tasks | Treat Desktop workspace as noncanonical, adopt phone session protocol, and align approvals with phone policy |
| Desktop task workspace | Implemented for Desktop, not phone-owned | `task_workspace.py` creates isolated Desktop task directories and artifact metadata | Add bounded synchronization/proposals so the phone remains canonical |
| MCP | Partial scaffold | `AgentMcpSession.kt` implements a bounded Android MCP client session contract; `mcp_agent_wrapper.py` exposes Desktop stdio MCP calls | Add concrete Android transports, registry translation, schema pinning, server trust, credentials isolation, and phone-side policy; no native Android stdio host yet |
| Skills | Scaffold | `AgentSkillRuntime.kt` provides bounded manifests, schemas, encrypted installations, validation, dependency ordering, parameter expansion, and tests | Add publisher/signature trust, install-review UI, task-supervisor/native-tool execution, receipts, lifecycle, and product integration |
| Subagents and task supervision | Scaffold | `AgentTaskSupervisor.kt` provides read/reasoning and serialized side-effect lanes, durable workspace transitions, cancellation, checkpoints, and resume hooks; the model planner supports bounded dependency graphs and output handoff | Integrate it with `MobileNativeAgent`, define explicit parent/child sessions, authority scoping, provenance, and cancellation propagation |
| Rich output | Partial | `AgentRichContent.kt`, `AgentRichContentView.kt`, `rich_output.py`, and `Super-Agent-Rich-Output.md` cover native blocks and fallback | Complete authenticated artifact retrieval, action binding, streaming patches, accessibility, and device tests |
| Encrypted cross-device transport | Partial | `SignalASILinkProtocol.kt`, `SignalASICrypto.kt`, MQTT delivery, Desktop sidecar, and protocol tests implement paired encrypted routing | Bind v1 session messages, capability manifests, replay rules, artifacts, and cancellation end to end |
| Background lifecycle | Partial | Android services, boot receiver, task/session stores, Desktop persistent task manager, and Link events exist | Recover phone workspaces and reconcile ambiguous calls across process death, reboot, Doze, and offline periods |
| iOS | Deferred | `apps/ios/README.md` is a future placeholder | Define a separate platform implementation after Android v1 stabilizes |

## 21. Definition of Done

The Mobile-Native Super Agent v1 is done only when:

- The phone creates and recovers the canonical workspace and session.
- All production phone actions use the native registry and local policy gate.
- At least one direct model and one paired-Desktop Agent reason over a bounded phone workspace through the same protocol.
- File proposals and artifacts return to and are committed by the phone.
- MCP, Skills, and subagents cannot expand permissions or bypass confirmation.
- Rich output, task controls, receipts, and evidence are durable and usable on a real phone.
- Platform-blocked and unimplemented Android capabilities remain unavailable in diagnostics and provider manifests.
- Automated and real-device acceptance evidence covers success, denial, interruption, restart, stale confirmation, replay, unavailable resources, and secure-surface behavior.
