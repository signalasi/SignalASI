# Desktop Native Tool Execution v1

## 1. Scope

Desktop Native Tool Execution v1 lets a paired SignalASI phone invoke a bounded,
typed operation on its paired Windows Desktop. The Desktop owns the computer
workspace and operating-system side effect. The phone remains the authority for
the user task, policy decision, and confirmation.

This protocol does not grant a remote model ambient shell or filesystem access.
Models can propose only tool calls advertised by the live Desktop capability
manifest. The Android host validates each proposal before it is encrypted and
sent.

## 2. Initial Tool Set

The v1 manifest can advertise these tool identifiers:

- `signalasi.desktop.windows.system.status`
- `signalasi.desktop.windows.process.list`
- `signalasi.desktop.workspace.file.list`
- `signalasi.desktop.workspace.file.read.text`
- `signalasi.desktop.workspace.file.write.text`
- `signalasi.desktop.workspace.file.sha256`
- `signalasi.desktop.workspace.archive.create`
- `signalasi.desktop.terminal.run`
- `signalasi.desktop.office.document.inspect`
- `signalasi.desktop.office.document.convert`

Each descriptor includes its JSON input schema, side-effect class, consent class,
timeout, cancellation support, idempotency requirements, and availability state.
Unknown properties are rejected.

## 3. Transport Messages

Messages travel only inside an authenticated SignalASI Link v1 control channel.
The encrypted payload types are:

- `desktop_tool_call_request`
- `desktop_tool_call_result`
- `desktop_tool_call_cancel`
- `desktop_tool_call_cancel_ack`
- `desktop_capability_manifest`

A request binds at least:

- paired source and target Signal identities;
- `conversation_id`, `task_id`, and `invocation_id`;
- tool identifier and schema-conforming arguments;
- phone workspace identifier and revision context;
- idempotency key;
- policy and confirmation metadata when required;
- request creation and expiry timestamps.

The Desktop rejects an unpaired source, a wrong target, a mismatched conversation
or task, an expired request, an unsupported tool, or an invalid confirmation.
There is no downgrade to a public or legacy MQTT topic.

## 4. Capability Discovery

The Desktop publishes its manifest after Link startup, after a capability-status
request, and when availability materially changes. Android persists only the
manifest associated with the currently paired Desktop identity.

A Desktop tool is routable only when all of the following are true:

1. an encrypted v1 pairing exists;
2. the selected Desktop is online;
3. the current manifest advertises the exact tool identifier;
4. the host policy allows the operation;
5. the tool's runtime dependency is available.

Revoking the pairing removes the cached capabilities immediately.

## 5. Workspace Isolation

The model-visible tool schema does not expose `workspace_id`. Android injects the
phone-owned workspace context after planning. The Desktop derives its real
workspace namespace from both the paired phone Signal identity and the injected
workspace identifier:

```text
link-<sha256(phone_signal_identity + NUL + phone_workspace_id)>
```

Paths are relative to that namespace. Absolute paths, traversal, reserved device
names, and symlink or reparse-point escapes are rejected. Two paired phones using
the same model-visible workspace name therefore receive different Desktop
workspaces.

## 6. Confirmation Binding

When policy requires confirmation, Android signs the canonical proposed tool
identifier and arguments. The Desktop first verifies that original digest. It then
replaces the phone workspace identifier with the isolated Desktop namespace and
rebinds the verified confirmation to the exact executable arguments.

This two-stage check prevents transport code from silently changing a confirmed
path, command, target format, or option. A confirmation for one invocation cannot
authorize another invocation.

## 7. Execution Safety

Desktop execution is bounded by:

- strict request, file, archive, process-output, and Office-document limits;
- a command allowlist with argument-vector execution and `shell=false`;
- explicit denial of command interpreters and script hosts such as `cmd`,
  PowerShell, WScript, CScript, and MSHTA;
- serialized side-effect lanes and bounded parallel read-only operations;
- safe ZIP/XML inspection rather than Office macro execution;
- cancellation and child-process termination;
- redacted errors and bounded result payloads.

Office conversion uses installed Windows Office automation only for explicitly
supported input/output pairs. Inspection remains non-executing and rejects
malformed or oversized packages.

## 8. Idempotency And Recovery

Every side-effecting call requires a stable idempotency key. The Desktop claims a
durable receipt before starting the side effect and records one of:

- `in_progress`;
- `completed` with the bounded result and evidence;
- `failed` with a stable public error;
- `cancelled`;
- `ambiguous` when restart recovery cannot prove the outcome.

A completed key returns the stored result without repeating the operation. An
ambiguous key is never retried automatically. The phone must reconcile the
receipt or create an explicitly new invocation.

## 9. Results And Evidence

`desktop_tool_call_result` returns the original correlation identifiers, terminal
status, bounded public output, receipts, provenance, and artifact metadata. The
Android host copies that evidence into its task output and user-visible renderer.
Provider text is not accepted as proof that a Desktop side effect completed.

## 10. Cancellation And Disconnects

Android can send `desktop_tool_call_cancel` for an active invocation. The Desktop
acknowledges the request, signals the registered cancellation token, and terminates
an owned child process when necessary. A transport disconnect does not authorize
automatic replay. Recovery uses the durable idempotency receipt after the pairing
returns.

## 11. Compatibility

This contract is versioned independently from individual tool schemas. A peer
that does not advertise Desktop Native Tool Execution v1 is treated as lacking the
capability. Unsupported fields, tools, and protocol versions fail closed.
