# Phone-Native Tool Session Protocol v1

Status: Authoritative protocol specification  
Protocol ID: `signalasi.phone-native-tool-session`  
Major version: `1`  
Native tool contract: `signalasi.phone-native-tools/1.0`

## 1. Purpose

Phone-Native Tool Session v1 defines how SignalASI coordinates a phone-owned Agent session with local, private-network, paired-Desktop, remote, or cloud reasoning resources while preserving phone ownership of workspace, permissions, execution, lifecycle, evidence, and artifacts.

The protocol is transport-independent. Messages MAY be carried:

- Inside a SignalASI Link v1 encrypted application payload.
- Over a direct HTTPS or private-network provider connection initiated by the phone.
- In-process between the Mobile Supervisor and an on-device model or tool.

Transport authentication and encryption do not authorize a tool. The phone MUST apply local availability, risk, permission, consent, policy, and workspace checks to every proposed call.

Protocol fields and errors are English-first. Unknown additive fields MUST be ignored. Unknown major versions MUST be rejected.

## 2. Normative Principles

1. The phone is the session controller and execution authority.
2. Reasoning providers propose; the phone validates and executes.
3. The canonical workspace is app-private phone storage.
4. Remote providers never receive Android permission tokens or direct ambient workspace access.
5. Tool capability advertisement is current state, not a promise of future availability.
6. Every accepted tool call has stable identity, bounded input, policy evidence, a terminal result, and provenance.
7. Consequential completion requires phone/controller verification, not provider assertion.
8. Non-idempotent side effects are never blindly replayed.
9. Cancellation and expiry are first-class lifecycle events.
10. Unsupported and blocked capabilities fail explicitly.

## 3. Terminology and Identities

| Field | Meaning |
| --- | --- |
| `session_id` | One durable phone-owned Agent session; MAY span multiple turns |
| `conversation_id` | User-visible conversation identity |
| `turn_id` | One user request and resulting response within a conversation |
| `task_id` | One executable task; child tasks use distinct IDs |
| `parent_task_id` | Parent supervisor task for a subagent task, or empty |
| `workspace_id` | Canonical app-private phone workspace |
| `plan_id` | One plan identity; revisions share the ID |
| `tool_call_id` | One logical tool call across retries or transport redelivery |
| `invocation_id` | One concrete local execution attempt |
| `artifact_id` | One durable or temporary output/input object |
| `confirmation_id` | One phone-issued action-specific user decision record |
| `message_id` | Transport/application message identity |

All IDs MUST be nonempty opaque strings of at most 160 characters unless a field is explicitly optional. UUIDs are RECOMMENDED. IDs MUST NOT embed secrets, paths, phone numbers, account names, or user content.

`session_id`, `conversation_id`, `turn_id`, `task_id`, and `workspace_id` MUST be present on every state-changing session message.

## 4. Actors

- **Controller:** the Mobile Supervisor on the phone.
- **Provider:** a model or Agent that returns reasoning, plans, or artifacts.
- **Tool Executor:** phone code that implements a registered tool.
- **Desktop Gateway:** a paired SignalASI Desktop endpoint that hosts Agents, models, MCP, or computer tools.
- **MCP Gateway:** an adapter that converts MCP capabilities and results to this protocol.
- **Skill Runtime:** a reviewed workflow that emits bounded task graph nodes.
- **Subagent:** a provider invocation owned by a parent phone task.

Only the Controller can transition a phone tool proposal to accepted execution.

## 5. Session State Machine

### 5.1 Canonical states

| State | Meaning |
| --- | --- |
| `created` | Identity and workspace allocated; no provider dispatch yet |
| `planning` | Local or remote reasoning is producing or revising a plan |
| `waiting_provider` | An asynchronous provider or subagent is running |
| `waiting_confirmation` | The phone requires a bound user decision |
| `executing` | A phone tool invocation is active |
| `verifying` | The phone/controller is collecting post-action evidence |
| `recovering` | The phone is reconciling failure, restart, or ambiguous state |
| `paused` | No new execution may start until resumed |
| `blocked` | Policy, platform, or unmet setup prevents continuation |
| `completed` | Required results and verification are finalized |
| `failed` | The task terminated unsuccessfully |
| `cancelled` | Cancellation is final for the phone-owned task |
| `expired` | Session/task deadline or retention deadline elapsed |

Terminal states are `completed`, `failed`, `cancelled`, and `expired`. `blocked` is nonterminal only when the user can satisfy the stated requirement; otherwise the Controller MAY terminate as `failed`.

### 5.2 Allowed transitions

```text
created -> planning
planning -> waiting_provider | waiting_confirmation | executing | blocked | failed | cancelled
waiting_provider -> planning | waiting_confirmation | verifying | paused | failed | cancelled | expired
waiting_confirmation -> executing | planning | paused | blocked | cancelled | expired
executing -> verifying | recovering | paused | failed | cancelled
verifying -> planning | waiting_confirmation | completed | recovering | failed | cancelled
recovering -> planning | waiting_confirmation | verifying | blocked | failed | cancelled
paused -> planning | waiting_provider | waiting_confirmation | executing | cancelled | expired
```

The Controller MUST persist the new state before dispatching a side effect associated with that state.

### 5.3 Remote lifecycle mapping

SignalASI Link task events map as follows:

| Remote state | Phone session state |
| --- | --- |
| `accepted`, `queued`, `planning`, `running` | `waiting_provider` |
| `waiting_user`, `waiting_approval` | `waiting_confirmation` when a valid bounded request exists; otherwise `waiting_provider` |
| `paused` | `paused` |
| `completed` | `verifying` before phone completion |
| `failed`, `timed_out` | `recovering` or `failed` |
| `cancelled` | `cancelled` when the phone also finalizes cancellation |

## 6. Common Envelope

Every protocol message SHALL use this logical envelope:

```json
{
  "protocol": "signalasi.phone-native-tool-session",
  "version": 1,
  "type": "session_start",
  "message_id": "uuid",
  "session_id": "uuid",
  "conversation_id": "uuid",
  "turn_id": "uuid",
  "task_id": "uuid",
  "parent_task_id": "",
  "workspace_id": "uuid",
  "sequence": 1,
  "sent_at": 1784000000000,
  "expires_at": 1784003600000,
  "payload": {}
}
```

Requirements:

- `sequence` MUST increase monotonically per `task_id` for Controller-authored state events.
- `sent_at` and `expires_at` are Unix epoch milliseconds.
- Receivers MUST reject expired messages and timestamps more than five minutes in the future.
- Repeated `message_id` values MUST return the prior acknowledgement without repeating dispatch.
- The envelope MUST be inside the encrypted SignalASI Link application payload when Link is used.
- Provider-specific metadata MUST remain inside `payload.provider_metadata` and MUST NOT alter protocol semantics.

## 7. Session Start and Policy Snapshot

The Controller creates the workspace and journal before sending `session_start`.

```json
{
  "type": "session_start",
  "payload": {
    "goal": "Inspect the selected project and propose a fix",
    "input_artifacts": ["artifact-input-1"],
    "policy": {
      "mode": "ask_before_action",
      "privacy": "private_or_paired",
      "network": "configured_endpoints_only",
      "max_tool_calls": 40,
      "max_agent_hops": 4,
      "max_parallel_reasoning_tasks": 4,
      "deadline_ms": 1800000,
      "cost_limit_micros": 0
    },
    "workspace": {
      "ownership": "phone",
      "access": "manifest_and_selected_content",
      "revision": 1
    },
    "requested_outputs": ["answer", "artifacts", "evidence"]
  }
}
```

The policy snapshot is informational to a provider and mandatory for provider behavior, but only the Controller enforces it. A provider MUST NOT interpret the snapshot as a permission grant.

The provider responds with `session_ready` or `session_rejected`:

```json
{
  "type": "session_ready",
  "payload": {
    "provider_id": "desktop:codex",
    "provider_session_id": "opaque-provider-id",
    "capabilities": ["reasoning", "code", "tool_proposals", "artifacts"],
    "limits": {
      "max_input_bytes": 262144,
      "max_output_bytes": 262144,
      "supports_streaming": true,
      "supports_cancel": true
    }
  }
}
```

## 8. Capability Manifest

### 8.1 Manifest message

The Controller MAY send `capability_manifest` at session start and whenever live capability state changes.

```json
{
  "type": "capability_manifest",
  "payload": {
    "manifest_revision": 3,
    "generated_at": 1784000000000,
    "tools": [],
    "resources": [],
    "limits": {
      "max_parallel_phone_side_effects": 1,
      "max_parallel_read_tools": 4
    }
  }
}
```

A Provider MUST only propose IDs present in the latest manifest. The Controller MUST still recheck availability at invocation time.

### 8.2 Native tool descriptor

```json
{
  "id": "signalasi.workspace.file.read.text",
  "version": "1.0.0",
  "title": "Read workspace text",
  "description": "Read a bounded UTF-8 file from the phone-owned task workspace",
  "location": "phone",
  "input_schema": {
    "type": "object",
    "properties": {
      "path": {"type": "string", "minLength": 1, "maxLength": 2048}
    },
    "required": ["path"],
    "additionalProperties": false
  },
  "output_schema": {"type": "object"},
  "risk": "low",
  "capabilities": ["workspace_read"],
  "required_permissions": [],
  "required_consents": [],
  "timeout_ms": 30000,
  "idempotency": "idempotent",
  "availability": {
    "status": "available",
    "reason": "",
    "checked_at_epoch_ms": 1784000000000
  }
}
```

Allowed descriptor values:

- `location`: `phone`, `application`, `android_system`, `accessibility_service`, or `unknown`.
- `risk`: `low`, `medium`, `high`, or `blocked`.
- `idempotency`: `non_idempotent`, `idempotent`, or `idempotency_key_required`.
- `availability.status`: `available`, `requires_setup`, or `unavailable`.

Platform diagnostics MAY additionally expose `limited`, `needs_runtime_permission`, `needs_special_access`, `needs_user_consent`, `needs_configuration`, `not_implemented`, `privileged_only`, `unsupported`, `blocked_by_policy`, and `unknown`. Any of those states except a fully satisfied ready state MUST map to non-available execution in the Provider manifest.

Blocked or privileged-only capabilities SHOULD remain visible in local diagnostics but MUST NOT be advertised to a Provider as callable.

## 9. Workspace Manifest and Content Exchange

### 9.1 Workspace manifest

The Controller MAY send `workspace_manifest`:

```json
{
  "type": "workspace_manifest",
  "payload": {
    "revision": 7,
    "entries": [
      {
        "path": "files/app.kt",
        "type": "file",
        "size_bytes": 4200,
        "sha256": "hex",
        "mime_type": "text/x-kotlin",
        "modified_at": 1784000000000,
        "content_available": true
      }
    ],
    "truncated": false
  }
}
```

Paths MUST be workspace-relative POSIX-style paths. Paths MUST NOT contain `..`, empty segments, drive letters, URI schemes, or leading `/`.

### 9.2 Content request

A Provider requests content with `workspace_content_request`:

```json
{
  "type": "workspace_content_request",
  "payload": {
    "requests": [
      {"path": "files/app.kt", "start_byte": 0, "max_bytes": 65536, "expected_sha256": "hex"}
    ],
    "purpose": "Inspect the failing implementation"
  }
}
```

The Controller MAY approve, reduce, redact, or reject the request. Returned content uses `workspace_content` and MUST include current digest and truncation state. A content response does not grant later access.

### 9.3 Mutation proposal

Providers SHALL propose workspace changes using `workspace_patch_proposal` or an artifact, never by claiming direct mutation:

```json
{
  "type": "workspace_patch_proposal",
  "payload": {
    "proposal_id": "uuid",
    "base_revision": 7,
    "operations": [
      {
        "op": "replace_exact",
        "path": "files/app.kt",
        "expected_sha256": "hex",
        "expected_text": "old text",
        "replacement_text": "new text",
        "expected_occurrences": 1
      }
    ],
    "summary": "Handle the null state before rendering",
    "expected_evidence": ["new_sha256", "diff_summary"]
  }
}
```

The Controller validates revision, path, digest, size, policy, and confirmation requirements. It applies accepted operations through registered local file tools and returns receipts. A revision or digest mismatch MUST fail with `workspace_conflict` and MUST NOT apply a partial patch unless the proposal explicitly supports atomic groups and the Controller can guarantee them.

## 10. Plan Proposal

A Provider MAY send `plan_proposal`:

```json
{
  "type": "plan_proposal",
  "payload": {
    "plan_id": "uuid",
    "revision": 1,
    "summary": "Inspect, patch, and verify the workspace",
    "expected_result": "The selected file contains the validated fix",
    "rollback_strategy": "Restore the pre-mutation checkpoint",
    "nodes": [
      {
        "node_id": "inspect",
        "kind": "tool",
        "tool_id": "signalasi.workspace.file.read.text",
        "tool_version": "1.0.0",
        "arguments": {"path": "files/app.kt"},
        "depends_on": [],
        "uses_outputs_from": [],
        "expected_evidence": ["sha256"]
      }
    ]
  }
}
```

Rules:

- Nodes MUST form a directed acyclic graph.
- Dependencies MUST refer to nodes in the same plan or completed bounded subtask results.
- Graph depth, node count, output handoff size, and parallelism are bounded by session policy.
- The Controller resolves tool IDs, versions, schemas, risk, and current availability.
- The Controller MAY reject, edit, split, or reorder a proposal where dependency semantics remain valid.
- Provider-supplied risk and confirmation flags are hints only. The phone classification is authoritative.
- Coordinates, package names, URIs, entity IDs, and other targets MUST resolve against the current phone catalog or explicit user input.

The Controller responds with `plan_accepted`, `plan_rejected`, or `plan_revision_request`.

## 11. Tool Call Proposal and Authorization

### 11.1 Proposal

```json
{
  "type": "tool_call_proposal",
  "payload": {
    "plan_id": "uuid",
    "plan_revision": 1,
    "node_id": "inspect",
    "tool_call_id": "uuid",
    "tool_id": "signalasi.workspace.file.read.text",
    "tool_version": "1.0.0",
    "arguments": {"path": "files/app.kt"},
    "idempotency_key": "session-bound-key",
    "purpose": "Read the selected source file",
    "expected_evidence": ["sha256"]
  }
}
```

The Controller MUST validate:

- Session/task/workspace identity.
- Plan and node identity, if present.
- Tool ID and exact compatible version.
- Input schema and bounded JSON values.
- Current availability and implementation state.
- Workspace/path and target scope.
- Local risk classification.
- Required Android runtime permissions.
- Required special access and per-session platform consent.
- Action-specific user confirmation.
- Idempotency and replay state.
- Deadline, task cancellation, and all budgets.

### 11.2 User confirmation

When required, the Controller emits a local UI prompt and records a `user_decision`. The confirmation record MAY be reported to the Provider without any Android token:

```json
{
  "type": "user_decision",
  "payload": {
    "confirmation_id": "uuid",
    "tool_call_id": "uuid",
    "decision": "approved",
    "arguments_sha256": "hex",
    "tool_id": "signalasi.agent_action.reply.notification",
    "tool_version": "1.0.0",
    "target_summary": "Reply to the selected notification",
    "decided_at": 1784000000000,
    "expires_at": 1784000060000
  }
}
```

Allowed decisions are `approved`, `rejected`, and `expired`.

An approval is valid only for the exact session, task, tool call, tool ID/version, normalized arguments digest, and expiry. It is single-use unless the local UI explicitly grants a bounded repeated policy. Changing any bound value requires a new decision.

The Controller MUST NOT transmit Android permission grant objects, MediaProjection data, notification listener handles, Accessibility handles, credentials, or reusable approval tokens.

### 11.3 Local invocation context

The Controller creates an internal invocation containing:

- `invocation_id`, session, conversation, turn, task, workspace, and tool-call IDs.
- Caller identity.
- Request and deadline timestamps.
- Idempotency key.
- Locally observed permission and consent IDs.
- Confirmation record ID and arguments digest.
- Cancellation token.

The Provider cannot populate the locally granted permission/consent sets.

## 12. Tool Result

```json
{
  "type": "tool_result",
  "payload": {
    "tool_call_id": "uuid",
    "invocation_id": "uuid",
    "status": "succeeded",
    "output": {"path": "files/app.kt", "sha256": "hex"},
    "message": "Read 4200 bytes",
    "metadata": {},
    "error": null,
    "verification": {
      "status": "passed",
      "message": "Digest and byte count match the workspace state",
      "evidence": {"workspace_revision": 7}
    },
    "receipt": {
      "started_at": 1784000000000,
      "finished_at": 1784000000120,
      "duration_ms": 120,
      "input_sha256": "hex",
      "output_sha256": "hex",
      "idempotency_key": "session-bound-key",
      "replayed": false,
      "original_invocation_id": null
    },
    "provenance": {
      "tool_id": "signalasi.workspace.file.read.text",
      "tool_version": "1.0.0",
      "location": "phone",
      "executor_id": "signalasi.phone_native",
      "contract_version": "signalasi.phone-native-tools/1.0"
    },
    "artifacts": []
  }
}
```

Allowed status values:

- `succeeded`
- `failed`
- `verification_failed`
- `rejected`
- `unavailable`
- `cancelled`
- `timed_out`

Allowed verification values are `passed`, `failed`, and `skipped`. A skipped verification MUST include a reason and MUST NOT be presented as verified completion.

Tool output MUST satisfy the advertised output schema. Invalid output becomes `failed` with `invalid_output`.

## 13. Error Contract

Errors use:

```json
{
  "code": "missing_consent",
  "message": "Per-session screen capture consent is required",
  "retryable": true,
  "details": {"consent_id": "media_projection_session"}
}
```

Required stable error codes:

| Code | Meaning |
| --- | --- |
| `unsupported_version` | Unknown protocol or tool major version |
| `invalid_envelope` | Missing or invalid common fields |
| `expired_message` | Message expired |
| `replay_rejected` | Replay is unsafe or conflicts with prior state |
| `unknown_tool` | Tool ID not registered |
| `tool_version_mismatch` | Requested version is not supported |
| `tool_unavailable` | Tool exists but is not currently executable |
| `tool_not_implemented` | Capability has no implementation |
| `privileged_only` | Capability requires a separately provisioned privileged boundary |
| `blocked_by_policy` | Product or user policy forbids the operation |
| `invalid_input` | Input schema or bounded-value validation failed |
| `invalid_output` | Executor output schema validation failed |
| `missing_permission` | Android runtime permission is absent |
| `missing_special_access` | Accessibility, notification listener, or similar access is absent |
| `missing_consent` | Per-session or action-specific consent is absent |
| `confirmation_required` | A bound user decision is required |
| `confirmation_mismatch` | Confirmation does not match current arguments/tool/task |
| `workspace_conflict` | Revision or digest changed |
| `path_escape` | Path is outside the workspace |
| `limit_exceeded` | Size, time, cost, graph, or count limit exceeded |
| `cancelled` | Cancellation was observed |
| `timeout` | Deadline elapsed |
| `verification_failed` | Required postcondition was not observed |
| `provider_unavailable` | Reasoning provider is unavailable |
| `transport_ambiguous` | Delivery outcome cannot safely be inferred |
| `artifact_unavailable` | Artifact is missing, expired, unauthorized, or failed integrity validation |

Messages MUST be safe for user display and MUST NOT include secrets, raw environment variables, private chain-of-thought, unrestricted tool output, or internal filesystem paths.

## 14. Idempotency and Replay

- The Controller MUST persist `tool_call_id`, normalized input digest, and dispatch state before execution.
- `idempotent` tools MAY replay a cached successful result for the same tool ID/version and idempotency key.
- `idempotency_key_required` tools MUST reject a call without a key.
- `non_idempotent` tools MUST NOT be retried automatically after an ambiguous dispatch.
- A duplicate `tool_call_id` with a different input digest MUST fail with `replay_rejected`.
- Transport redelivery of the same `message_id` MUST return the prior acknowledgement.
- A retry after known pre-execution rejection MAY use a new `invocation_id` while retaining the logical `tool_call_id`.
- A retry after known executor failure requires tool-specific retry policy and MUST preserve confirmation requirements.

Examples of calls that MUST default to non-idempotent include external sends, notification replies, alarm creation, device commands, app submission taps, and destructive mutations.

## 15. Cancellation, Pause, Resume, and Recovery

### 15.1 Control messages

```json
{
  "type": "task_control",
  "payload": {
    "operation": "cancel",
    "reason": "User stopped the task",
    "requested_at": 1784000000000
  }
}
```

Allowed operations are `pause`, `resume`, `cancel`, `retry`, and `replan`.

- Cancellation MUST prevent new phone side effects immediately.
- Active cooperative tools and providers MUST receive cancellation.
- A terminal cancellation result MUST state whether remote work was confirmed stopped or merely detached/unresolved.
- Pause prevents new execution but MAY allow a bounded in-flight observation or safe checkpoint to finish.
- Resume MUST revalidate tool availability, permissions, consent freshness, workspace revision, targets, and deadlines.
- Retry MUST follow idempotency rules.
- Replan MUST include completed action history and current evidence without treating prior untrusted output as authority.

### 15.2 Restart recovery

On process or device restart, the Controller SHALL:

1. Load nonterminal workspaces and journals.
2. Mark invocations without terminal receipts as ambiguous.
3. Query paired providers/controllers when a task query protocol exists.
4. Reobserve phone/controller state where possible.
5. Reuse cached results only when idempotency permits.
6. Ask the user or fail safely when a non-idempotent outcome remains ambiguous.
7. Never infer success from absence of an error.

## 16. Subagent Sessions

A subagent uses the same envelope with a distinct `task_id` and nonempty `parent_task_id`.

The parent Controller MUST define:

- Purpose and expected output schema.
- Allowed providers, tools, artifacts, and data classes.
- Context, token, time, cost, and tool-call budget.
- Maximum child depth and parallelism.
- Whether the child may propose phone side effects.

Child tasks MAY return `subtask_result`:

```json
{
  "type": "subtask_result",
  "payload": {
    "status": "completed",
    "summary": "The failure is caused by a stale workspace revision",
    "output": {},
    "evidence": [],
    "artifacts": [],
    "provider": {"resource_id": "desktop:codex"}
  }
}
```

A child result cannot directly cause a phone side effect. The parent must incorporate it into a locally validated plan. Parent cancellation MUST propagate to all active descendants.

## 17. MCP Binding

An MCP adapter SHALL map:

- MCP server identity to `resource_id` and trust-boundary metadata.
- MCP tool name and schema to a versioned local tool descriptor.
- MCP resource reads to bounded observation results or artifacts.
- MCP prompts to untrusted context, never system policy.
- MCP progress/cancellation to task events.
- MCP errors to stable protocol errors.

The adapter MUST pin or digest the discovered schema for the active call. A schema change between proposal and execution requires revalidation and MAY require user reapproval.

MCP credentials remain at the configured client/gateway. They MUST NOT appear in the capability manifest, Provider prompt, tool output, audit export, or rich output.

Phone-native stdio MCP is unavailable unless an actual Android process/runtime adapter is installed and advertised. A Desktop MCP wrapper is a paired-Desktop resource, not phone-native execution.

## 18. Skill Binding

A Skill invocation SHALL include `skill_id`, `skill_version`, package digest, trust state, input schema, requested capabilities, and resolved graph revision.

The Controller expands Skill steps into the same local plan and tool-call protocol. The Skill cannot:

- Add undeclared tools after approval.
- Raise its risk or data scope silently.
- Reuse another task's confirmation.
- Read files outside the workspace or selected content URIs.
- Invoke hidden scripts or arbitrary shell commands.
- Override platform, security, or user policy.

An updated package digest is a different authorization subject and requires revalidation.

## 19. Artifacts and Rich Output

### 19.1 Artifact descriptor

```json
{
  "artifact_id": "uuid",
  "name": "report.md",
  "relative_path": "outputs/report.md",
  "mime_type": "text/markdown",
  "size_bytes": 12345,
  "sha256": "hex",
  "ownership": "phone",
  "retention": "task",
  "created_by": {
    "task_id": "uuid",
    "tool_call_id": "uuid",
    "resource_id": "desktop:hermes"
  }
}
```

Artifact ownership values are `phone`, `desktop`, or `remote`. A non-phone artifact MUST be imported and integrity-checked before it becomes part of the canonical phone workspace.

Remote artifact access MUST use authenticated, task-scoped, expiring references. Raw remote filesystem paths MUST NOT be exposed as Android-openable URIs.

### 19.2 Rich result

Final and incremental results use `result_output`:

```json
{
  "type": "result_output",
  "payload": {
    "content": "Accessible plain-text fallback",
    "rich_output": {"version": 1, "blocks": []},
    "verification_status": "verified",
    "artifacts": [],
    "usage": {
      "input_tokens": null,
      "output_tokens": null,
      "cost_micros": null,
      "latency_ms": 1200
    }
  }
}
```

Allowed verification status values are `verified`, `partially_verified`, `unverified`, and `failed`.

Rich output follows `Super-Agent-Rich-Output.md`. Plain text fallback is mandatory. Interactive verbs remain inert unless the phone binds them to a live local task and passes normal policy checks.

## 20. Streaming

Providers MAY emit:

- `provider_progress`
- `plan_patch`
- `tool_call_proposal`
- `rich_output_patch`
- `usage_update`

Every patch MUST carry a stable target ID and monotonically increasing `revision`. Receivers MUST ignore stale or repeated revisions. Streaming content does not mutate final task/workspace state until the Controller commits it.

Private chain-of-thought, hidden reasoning tokens, secrets, unrestricted command output, and raw environment variables MUST NOT be streamed.

## 21. Limits

Unless a stricter negotiated limit applies:

| Item | Default limit |
| --- | --- |
| Session envelope | 512 KiB |
| Text field | 128 KiB UTF-8 |
| Tool argument or output document | 256 KiB |
| Tool catalog | 1,000 descriptors or 2 MiB, whichever is lower |
| Plan nodes | 100 |
| Graph depth | 8 |
| Active subagents | 8 |
| Parallel reasoning tasks | 4 |
| Parallel phone side effects | 1 |
| Tool calls per task | 100 |
| Event journal retained per workspace | Implementation-bounded, with monotonic sequence preserved |
| Artifact metadata records | 100 per task |
| Rich output | Limits in `Super-Agent-Rich-Output.md` |

Workspace file and archive operations MUST also enforce the local `AgentWorkspaceFilePolicy` or a stricter production profile.

The Controller MAY reduce any limit based on battery, network, privacy, storage, thermal state, user policy, or provider trust.

## 22. Security and Privacy Requirements

- SignalASI Link carriage MUST remain inside the paired encrypted route.
- Provider and MCP output MUST be treated as prompt-injection-capable untrusted data.
- Only the minimum required context crosses a trust boundary.
- Sensitive values SHOULD be redacted before provider dispatch and MUST NOT enter logs.
- Credentials MUST remain in their configured phone, Desktop, MCP, or provider boundary.
- Tool receipts SHOULD store input/output digests rather than secret-bearing raw values.
- Confirmation UI MUST display the actual target and consequence, not provider-authored persuasion.
- Provider instructions MUST NOT alter policy mode or enable Android special access.
- Artifact MIME type, extension, and content MUST be treated as independent untrusted claims.
- Downloaded or generated content MUST NOT auto-execute.
- URI schemes MUST be allowlisted. Arbitrary `file://`, `intent://`, `javascript:`, shell, or executable content MUST be rejected.
- Audit export MUST support user review without exposing secrets or private chain-of-thought.

## 23. Android Capability Rules

The following protocol behavior is REQUIRED:

- Accessibility tools report unavailable until the service is user-enabled and connected.
- MediaProjection tools report consent-required until a live per-session grant exists.
- Secure or hidden capture returns an explicit unavailable/verification result, not synthetic content.
- Notification reply is available only for a current notification with free-form `RemoteInput` and action-specific confirmation.
- Camera and microphone tools require runtime permission and user-visible capture.
- Clipboard tools report limited capability and MUST NOT promise background reads.
- Intent and Settings tools return `handoff_started` evidence until a postcondition can be observed.
- Package installation remains unavailable until a dedicated system-installer handoff is implemented; silent install is never represented.
- Location, sensors, Bluetooth, NFC, and general media transcode remain unavailable until dedicated implementations are registered.
- Device Owner and Shizuku remain privileged-only without separately provisioned integrations.
- Root remains `blocked_by_policy` even if `su` is detected.
- Lock-screen bypass, secure-surface bypass, payment submission, credential approval, and unrestricted third-party sends MUST NOT be exposed as callable tools.

## 24. SignalASI Link Binding

When carried over SignalASI Link v1:

- The common session envelope is the Link application `payload` or a nested object under a session-specific payload type.
- Link `message_id` provides transport idempotency; `tool_call_id` provides logical call identity.
- Link `conversation_id` MUST equal this protocol's `conversation_id`.
- Link task events MUST include this protocol's `task_id` and increasing status sequence.
- Capability discovery MAY include a feature flag `phone_native_tool_session_v1`.
- A Desktop Agent addressed without a matching healthy paired relationship MUST fail visibly.
- No fallback to fixed or legacy MQTT topics is permitted.
- Larger artifacts use the authenticated encrypted file channel and retain independent digest, key, expiry, and relationship authorization.

## 25. Conformance Requirements

A Controller conforms to v1 only if it:

- Owns and persists the canonical workspace and task state.
- Revalidates every provider tool proposal locally.
- Enforces truthful capability availability.
- Binds confirmation to exact action identity and arguments digest.
- Produces terminal structured results and receipts.
- Implements replay-safe idempotency and ambiguous-call recovery.
- Supports pause, cancellation, expiry, and restart reconciliation.
- Preserves artifact integrity and rich-output fallback.
- Enforces all Android blocked/privileged/unimplemented constraints in this document.

A Provider conforms to v1 only if it:

- Uses only advertised IDs and schemas.
- Treats capability manifests as proposals subject to revalidation.
- Does not claim direct phone workspace mutation or permission ownership.
- Produces bounded plan/tool/artifact messages.
- Stops or reports cancellation status when supported.
- Does not transmit private chain-of-thought, secrets, or unrestricted environment data.

A Desktop Gateway conforms to v1 only if it:

- Maintains paired Link identity and task correlation.
- Labels Desktop workspaces and artifacts as non-phone ownership.
- Advertises actual Agent/MCP/Skill readiness separately from transport status.
- Supports query/cancel or explicitly reports that it cannot.
- Never converts Desktop process authority into phone authority.

## 26. Acceptance Test Matrix

| Area | Required test |
| --- | --- |
| Envelope | Reject missing identity, unknown major version, future timestamp, expiry, and malformed payload |
| Replay | Duplicate message returns prior acknowledgement; changed digest under same call ID is rejected |
| Catalog | Blocked, privileged, unimplemented, unpermissioned, and stale capabilities cannot be advertised callable |
| Schema | Invalid input and output fail before result acceptance |
| Permission | Provider cannot populate local granted permission/consent state |
| Confirmation | Changed argument, target, tool version, task, or expired decision requires new approval |
| Workspace | Reject path escape, absolute path, symlink, stale revision, digest mismatch, ZIP slip, and limits |
| Remote patch | Provider proposal is applied only by the phone and produces local receipt/evidence |
| Idempotency | Keyed idempotent call replays safely; ambiguous non-idempotent call is not repeated |
| Cancellation | Phone, provider, tool, and subagent cancellation states reconcile without later side effects |
| Recovery | Process/device restart recovers journal and does not duplicate consequential actions |
| Android UI | Accessibility and OCR operate only after platform consent and report secure surfaces honestly |
| Platform blocks | Root, silent install, lock bypass, Device Owner/Shizuku without provisioning, and unimplemented hardware remain unavailable |
| MCP | Schema change and credential leakage tests fail safely; MCP output cannot bypass phone policy |
| Skills | Package/version/digest change invalidates prior authorization; Skill cannot add hidden tools |
| Subagents | Parent limits, least context, side-effect serialization, provenance, and cancellation propagation are enforced |
| Artifacts | Digest, ownership, expiry, authorization, MIME handling, and import to phone workspace are verified |
| Rich output | Fallback, unknown blocks, inert actions, monotonic patches, and task binding are verified |
| Link | Encrypted paired route, task correlation, capability flag, artifact channel, revocation, and no legacy fallback are verified |

## 27. Current Repository Alignment

The current repository contains meaningful parts of this protocol but is not yet end-to-end conformant:

- `AgentNativeToolRegistry.kt` closely matches the descriptor, schema, permission/consent, availability, timeout, cancellation, idempotency, verification, receipt, provenance, and result contract.
- `AgentPhoneNativeToolCatalog.kt` registers the app-private workspace tools and selected existing Android actions under stable `signalasi.*` tool IDs with live capability probes.
- `AgentWorkspaceStore.kt` provides bounded encrypted workspace state, identity binding, journals, tool-call records, checkpoints, artifacts, revisions, and recovery queries.
- `AgentWorkspaceFileTools.kt` provides app-private confined file operations and archive defenses.
- `AgentPhoneCapabilityCatalog.kt` provides the required honest distinction among ready, limited, setup/consent required, unimplemented, privileged-only, and policy-blocked capabilities.
- `PhoneExecutionAuthority.kt` serializes phone side effects and allows limited concurrent observation.
- `AgentTaskSupervisor.kt` provides bounded concurrent read/reasoning work, serialized side-effect work, durable transitions, checkpoints, cancellation, and recovery hooks.
- `MobileNativeAgent.kt` implements the existing phone plan, confirmation, execution, verification, recovery, and connector loop but does not yet route through the new registry/workspace protocol.
- `GuardedModelAgentPlanner.kt` already treats model plans as locally constrained proposals.
- `SignalASI-Link-Protocol.md`, Android Link code, and Desktop backend provide encrypted transport, task lifecycle, cancellation, and capability foundations.
- Desktop `task_workspace.py` is a Desktop-owned task workspace, not the canonical phone workspace.
- Desktop Codex, Claude Code, Hermes, custom CLI, and MCP wrappers are provider adapters that require this session binding.
- `AgentMcpSession.kt` provides a bounded Android MCP client session with protocol negotiation, tools, resources, prompts, cancellation, limits, and structured errors, but no production transport or native-tool policy integration is wired yet.
- `AgentSkillRuntime.kt` provides bounded Skill manifests, validation, encrypted installation state, dependency ordering, parameter expansion, and tests, but publisher trust, install review, task execution, and receipt integration remain incomplete.
- `Super-Agent-Rich-Output.md`, Android rich-content code, and Desktop `rich_output.py` provide the result format, but authenticated remote artifact opening and streaming patches remain incomplete.
- End-to-end Skill execution and explicit parent/child subagent session binding remain incomplete.

Until integration and conformance tests pass, implementations MUST describe v1 as in development and MUST NOT advertise blocked or unimplemented Android operations as available.
