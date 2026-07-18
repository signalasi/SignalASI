# Android On-device Super Agent

## Purpose

SignalASI for Android is an agent operating system, not a thin chat client. It must be able to
observe the phone, reason about a goal, select local or connected capabilities, execute work,
verify results, preserve useful context, and improve under user control.

The design combines the strongest product patterns from coding agents and persistent personal
agents:

- durable tasks, plans, tool receipts, artifacts, validation, steering, and resumability;
- scoped instructions, hooks, reusable skills, subagents, and explicit tool permissions;
- encrypted long-term memory, session search, user preferences, scheduled work, and learning;
- a persistent gateway, capability discovery, health-aware routing, and reviewed extensions.

These are product patterns. SignalASI keeps its own Android-native contracts and does not copy a
vendor's private protocol or runtime.

## Agent loop

Every request enters one task state machine:

1. Build a context envelope from the active conversation, attachments, relevant memories,
   knowledge evidence, current screen, device state, and available capability health.
2. Classify intent, privacy, freshness, latency, cost, and risk.
3. Create or update a typed plan. Each step declares inputs, expected evidence, timeout,
   cancellation behavior, fallback policy, and rollback support.
4. Route each step to the phone, an on-device runtime, an MCP server, a paired agent, a local
   model, or an enabled cloud provider.
5. Stream concise progress events while retaining detailed diagnostics in the audit log.
6. Verify postconditions and either complete, retry, replan, roll back, or request one precise
   clarification.
7. Store the task receipt and artifacts. Send eligible evidence to the learning pipeline.

The router favors phone-native tools for phone state and actions. It considers capability fit,
health, expected latency, privacy, cost, context capacity, and quality. A failed Desktop does not
cause blind retries across every Desktop agent; an enabled phone API or local path is preferred
when it can satisfy the same step.

## Agent gateway and run control plane

External agents remain independently upgradeable and connect through adapters. SignalASI owns the
stable control model around them:

- an encrypted Agent Registry records stable agent, installation, provider, and device identities;
- capabilities, tools, permission scopes, trust, cost, latency, concurrency, health, and failure
  domain drive routing instead of display names;
- protocol ranges and feature sets are negotiated when a connection is established;
- `RESPOND` asks an agent to act, `OBSERVE` adds context without triggering work, and `IGNORE`
  suppresses delivery;
- conversation, message, task, run, step, event, tool call, device, and agent identifiers are
  distinct;
- monotonic run events are encrypted, idempotent by event id, replayable, and reducible to a
  recoverable state;
- heartbeats expose online, idle, busy, degraded, updating, permission-required, and unreachable
  states without frequent MQTT polling;
- structured handoffs carry the target agent, reason, artifacts, checkpoint, and return path.

Concrete adapters bind to a transport boundary rather than embedding vendor runtimes. The adapter
negotiates the common protocol range before a run starts, preserves agent and installation
identity for the lifetime of the connection, suppresses `IGNORE`, rejects unsupported
`OBSERVE`/cancel operations, and only requests recovery when the negotiated feature set permits
it. A bounded idempotency ledger returns the original run handle when a start request is retried,
and an `IGNORE` run never opens or invokes the remote transport. Providers use the same boundary
while exposing multiple independently addressable agents.

Team orchestration has one explicit `RESPOND` primary and any number of `OBSERVE` members. Ignored
members receive nothing. Each participating Agent gets a distinct run under the same task, with
the primary run as the parent for observer runs. A team can remain background-only or expose its
member activity in the Run UI; unavailable and capability-mismatched members are reported without
silently replacing the primary.

`OBSERVE` is deliberately non-interactive. On Android it is stored as encrypted, target-scoped,
short-lived context and is consumed by the next `RESPOND` request to that target. It is never sent
through a legacy transport that could accidentally turn the observation into a reply. The context
is acknowledged only after the response request has been accepted successfully.

Registry heartbeats and advertised concurrency are projected into resource routing. A busy agent
remains visible, but an agent at its declared parallel-run limit is not selected for new work.
After process restart, the recovery policy reconnects durable paired-Desktop runs and restores
user-waiting checkpoints. Non-replayable phone actions and one-shot HTTP model calls fail closed
instead of being repeated and potentially causing duplicate side effects.

Adapters expose connect, status, capabilities, start, steer, cancel, event streaming, and run
recovery. Codex, Claude Code, OpenClaw, cloud providers, paired Desktop agents, Android in-process
agents, and local models implement the same contract while retaining their native execution
engines.

## Memory system

Memory is encrypted at rest and separated from transcripts.

| Layer | Purpose | Retention |
| --- | --- | --- |
| Working | Current plan, observations, and tool results | Task lifetime |
| Episodic | What happened, result, artifacts, and feedback | User-controlled |
| Semantic | Stable facts about people, projects, and devices | Until changed or deleted |
| Preference | Stable response and workflow preferences | Until changed or deleted |
| Procedural | Successful reusable methods | Skill proposal or Skill |
| Safety | Trust, consent, denial, and revocation decisions | Policy-controlled |

Each durable memory records scope, source, confidence, evidence count, last confirmation, last
access, expiration, version lineage, and conflict state. Duplicate evidence strengthens an item;
contradictory evidence creates a visible conflict instead of silently overwriting it. Private mode
never produces long-term memory or learning evidence.

## Automatic learning

Learning is evidence-driven and reversible:

1. Record successful and failed task runs using redacted structured receipts.
2. Extract only stable preferences, corrections, reusable procedures, and failure lessons.
3. Reject secrets, authentication material, one-time codes, private-mode data, and incidental
   attachment contents.
4. Merge repeated evidence by normalized task family.
5. Save low-risk explicit preferences as memory when memory capture is enabled.
6. Create a Skill proposal after repeated successful runs; do not silently activate generated
   code or high-impact behavior.
7. Run manifest validation and regression cases before installation.
8. Preserve version history, provenance, disable, rollback, export, and deletion.

Android Linux runtime runs are eligible evidence only when the completed tool record contains a
matching execution receipt with a successful exit code, bounded timestamps, and valid source,
stdout, and stderr SHA-256 values. Missing or malformed receipts cannot strengthen workflow
memory, generate a Skill, or upgrade one.

## Skill Workshop

A Skill is a declarative workflow with typed parameters, native/MCP tool requirements, examples,
negative examples, permissions, tests, rendering hints, and provenance. Explicit user requests may
save a reviewed Skill immediately. Automatic learning creates a disabled proposal that shows:

- the generalized task family and evidence runs;
- proposed parameters and steps;
- required tools, permissions, network access, and risk;
- redactions and regression tests;
- approve, edit, reject, or defer actions.

Task-specific paths, URLs, identifiers, names, and secrets must become parameters or redacted
values. A Skill cannot contain executable payloads outside declared runtime and tool contracts.

## Android execution runtime

The app targets modern Android, where executing downloaded binaries from writable app storage is
not a dependable production design. SignalASI therefore uses a runtime broker with explicit
backends:

- **QEMU TCG**: universal no-root fallback. The QEMU engine is shipped as an Android native
  component; signed disk and toolchain packs are data interpreted by that engine.
- **Android Virtualization Framework**: optional acceleration for eligible OEM, managed, or
  preinstalled builds. It is not assumed to be available to a normal Play-installed app.
- **Native Android tools**: preferred for phone actions, OCR, media metadata, storage, sensors,
  network, camera, and system intents.

PROot and Termux are not production dependencies.

The Linux guest is persistent and controlled by a small guest agent. SignalASI does not boot a VM
for each command. Host and guest communicate through a bounded request protocol with per-task
workspaces, cancellation, heartbeats, output limits, artifact hashes, and execution receipts.

The Android host contract is implemented as an authenticated, length-prefixed local-socket
protocol. Host and guest negotiate the API version before work begins. Every frame carries a
request id, monotonic sequence, timestamp, and HMAC-SHA256 authentication; oversized, stale,
tampered, and incompatible frames fail closed. Execution supports progress, cancellation,
timeouts, bounded results, explicit network-domain allowlists, and health probes.

The host keeps one authenticated channel open and multiplexes concurrent requests by request id.
The Android QEMU controller builds an argument-only launch plan, keeps credentials out of process
arguments and logs, disables guest networking, mounts only app-private task workspaces, attaches
signed tool packs read-only, rotates bounded logs, cleans stale sockets, and removes ephemeral
bootstrap material after shutdown. It does not silently fall back to a host shell.

Inside the guest, a root-owned broker authenticates the host and delegates each command to a small
native launcher. The launcher creates private mount, PID, network, IPC, and UTS namespaces; bind-mounts
only that request's workspace; hides peer workspaces, fw_cfg secrets, and the host channel; applies
CPU, address-space, process, file-size, descriptor, and core-dump limits; enables `no_new_privs`;
drops supplementary groups and root identity; and only then executes an exact argument vector.
The Python broker never uses shell concatenation or a multithreaded `preexec_fn`.

A persistent lifecycle supervisor separates pack presence from process health. It records blocked,
stopped, starting, ready, degraded, backing-off, and stopping states in encrypted app storage;
starts only through a registered native engine controller; waits for the authenticated guest
health handshake; and applies bounded exponential restart backoff after failures. A user stop
invalidates an in-flight startup immediately, while restart safely serializes behind the previous
attempt. Main UI and foreground-service startup both request recovery, and runtime-pack removal
stops the guest before changing mounted data. The Control Center exposes the real phase, failure
count, next retry, controller identity, and start, stop, or restart controls when those actions are
available.

Each request receives an app-private workspace and an encrypted execution receipt containing the
source digest, runtime-pack versions, resource limits, network scope, terminal state, output
digests, exit code, and verified artifact hashes. Only explicitly requested relative artifacts
are collected. Workspaces expire after a bounded retention period and cannot escape their task
root. Runtime start, progress, and completion callbacks are projected into the unified Run event
stream with distinct step and tool-call identifiers. The durable Run stores sanitized receipt
evidence and artifact references while excluding app-private host paths from learning evidence.

The QEMU controller, guest broker, sandbox launcher, pinned glibc-based Buildroot `linux-base`
recipe, and reproducible Android ARM64 QEMU build and ELF-bundling pipeline are implemented. These
sources do not by themselves make Linux execution available in a release APK. The runtime remains
unavailable until the generated native engine and a built, signed `linux-base` image are installed
and complete the health handshake. SignalASI never reports a placeholder, source recipe, or
manifest-only runtime as ready.

## Runtime packs

The base APK remains small. Runtime components are signed, versioned, and independently removable:

- `linux-base`: minimal AArch64 userspace and guest agent;
- `python-uv`: Python and uv-managed isolated environments;
- `node-js`: Node.js, npm-compatible package execution, JavaScript, and TypeScript;
- `go`, `rust`, `cpp`, and `java`: language toolchains and caches;
- `ffmpeg`: audio, video, and image processing;
- `ocr-extended`: optional models and document-layout components.

Every pack manifest declares platform, architecture, version, compressed and installed size,
SHA-256, signature key, dependencies, licenses, exposed capabilities, and minimum host/guest
versions. Installation is atomic and verified before activation. Network is off by default for a
task and package downloads require an explicit policy decision.

The installable `.sarpack` format is a ZIP archive with `manifest.json` at its root and one signed
runtime image addressed by `image_file`. The signature covers format, pack id, semantic version,
architecture, image path and digest, sorted capabilities and dependencies, declared archive and
installed limits, minimum Android host version, guest API version, license, and signing key id.
Import rejects path traversal, unknown pack ids, incompatible architectures, excessive expansion,
untrusted signatures, unsupported protocol versions, and invalid hashes. Activation uses a
same-volume staging directory and rollback backup; dependencies prevent unsafe removal.

Compatible packs are discovered through a bounded, signed release catalog rather than arbitrary
URLs entered by the planner. Catalog and pack signatures are verified against dedicated public
trust anchors embedded in the APK; debuggable builds may also use the current APK signing identity.
Catalog generations cannot roll back or reuse a timestamp with different content, and an expired
catalog is never offered for installation. Downloads require public
HTTPS endpoints, pin each DNS resolution for the connection, revalidate every redirect, reject
private and special-use destinations, and verify the catalog-declared byte count and SHA-256.
Interrupted downloads retain an app-private partial file and resume with `Range` and `If-Range`;
the completed archive remains cached if installation fails so a verified multi-gigabyte pack does
not need to be downloaded again.

The Control Center exposes catalog refresh, compatibility, size, license, dependencies, progress,
cancellation, atomic activation, update, reinstall, uninstall, and recent execution receipts.
Manual `.sarpack` import remains available for offline deployments. If no signed catalog has been
published or cached, the UI reports that state directly and does not advertise placeholder packs.

Large image hashes are cached only after a full successful verification and are keyed by image
size, modification time, and expected digest. Installation and update always force a complete
verification. The app never treats the runtime as ready until the native engine, `linux-base`, and
the guest bridge are all active.

## Runtime safety

- Separate workspace per task and no direct access to arbitrary app-private data.
- Content URIs are copied into a bounded task workspace only when granted.
- CPU, memory, disk, process, wall-clock, stdout, stderr, and artifact quotas.
- Network disabled by default; domain and duration scope when enabled.
- No Android permission inheritance inside Linux.
- High-impact Android actions remain Android-native and use the global confirmation policy.
- Complete provenance for toolchain, package lock, command, outputs, and artifacts.
- Immediate stop, cleanup, quarantine, and rollback controls.

## OCR and media

OCR is Android-native first and supports Latin, Chinese, Japanese, Korean, and Devanagari script
selection, image orientation, and bounded decoding. Automatic mode merges multiple recognizers
instead of discarding all but one script. Spatial duplicate removal, structured lines and blocks,
language provenance, layout classification, heuristic quality, and warnings are returned to the
planner so mixed-language and low-quality pages can be handled honestly.

The local knowledge importer extracts text from PDF, DOCX, XLSX, PPTX, HTML, images, source code,
configuration files, and common text formats. XLSX and PPTX are parsed from bounded OOXML entries;
archive expansion, entry count, XML document types, external entities, and extracted output are
limited. Images use the same on-device OCR path. Handwriting, equations, complex tables, and
offline visual-model review remain optional future model-pack capabilities.

FFmpeg is exposed through the runtime broker with argument validation, bounded inputs and outputs,
progress events, cancellation, and artifact previews. Media conversion never grants a shell direct
access to the user's shared storage.

## Delivery status

| Capability | Status |
| --- | --- |
| Encrypted memory metadata, ranking, conflict handling, and no-migration storage | Host complete |
| Evidence-based learning, repeated-failure lessons, and reviewed Skill proposals/upgrades | Host complete |
| Unified Agent adapters/providers, delivery modes, idempotent starts, team orchestration, registry heartbeats, and Run events | Host complete |
| Runtime capability, signed-pack catalog/download/install policy, lifecycle supervision, guest protocol, workspace, cancellation, and receipt contracts | Host complete |
| Android QEMU process controller, reproducible ARM64 engine build/bundle pipeline, persistent multiplexed bridge, Linux guest broker, per-task native sandbox launcher, and pinned `linux-base` build recipe | Source complete |
| Control Center pages for memory, learning proposals, runtime packs, and execution receipts | Host complete |
| Mixed-script OCR and local PDF/DOCX/XLSX/PPTX/image/source ingestion | Host complete |
| Signed Android QEMU executable and built, signed `linux-base` release image | Not shipped |
| Reproducible Python/uv, Node/TypeScript, Go, Rust, C/C++, Java, and FFmpeg/ffprobe pack recipes | Source complete; release images not shipped |
| Extended OCR runtime pack beyond the Android-native multi-script OCR engine | Not shipped |
| Handwriting, equation, complex-table, and local visual-model understanding | Planned |
