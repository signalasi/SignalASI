# Android Agent Development Loop

SignalASI treats the Android phone as an execution authority, not only as a chat client. A configured model may plan work, but the phone validates every tool call, owns the project workspace, runs code in the local Linux guest, and returns verified results and artifacts.

## End-to-end flow

1. The user submits a goal, attachment, voice transcript, or follow-up in an Agent conversation.
2. The planner receives conversation context, available Android tools, runtime-pack state, and resource constraints.
3. The planner produces a bounded `ActionPlan` using registered tool IDs and JSON schemas.
4. Workspace tool inputs are bound to the active conversation by the phone. A model-supplied workspace ID cannot select another conversation.
5. The Agent creates or updates files in the app-private project workspace.
6. `signalasi.runtime.execute` snapshots that project into an isolated Linux run directory.
7. The guest executes the requested language, compiler, test command, or FFmpeg operation with CPU, memory, process, output, disk, and time limits.
8. The host atomically commits the resulting project snapshot back to the durable conversation workspace.
9. A non-zero exit produces structured stderr evidence. Dynamic replanning can patch the project and run verification again.
10. A zero exit produces a concise result plus durable artifact references rendered by the Agent output surface.

## Runtime tools

- `signalasi.runtime.status`: reports backend and toolchain readiness.
- `signalasi.runtime.packs.list`: lists installed signed runtime packs.
- `signalasi.runtime.packs.install`: downloads, verifies, and installs a trusted pack dependency graph.
- `signalasi.runtime.execute`: runs shell, Python, uv, JavaScript, TypeScript, Go, Rust, C, C++, Java, FFmpeg, or FFprobe work.
- `signalasi.workspace.*`: provides structured project file operations without exposing an unrestricted host shell.

## Project continuity

Each Agent conversation maps to a deterministic UUID workspace. Individual user turns and Linux runs use fresh task and request IDs while sharing that conversation workspace. New conversations receive new workspaces. Runtime control files, temporary output streams, and sandbox scratch directories are excluded from the durable project.

The commit is staged and bounded before replacing the previous project snapshot. Concurrent execution against one workspace is serialized, while unrelated conversations may run independently.

## Verification contract

Programming, document, data, media, and software-verification tasks follow these rules:

- Prefer phone-native workspace and runtime tools when the required pack is ready.
- Install only signed runtime packs from the verified catalog.
- Do not report success without an exit code or equivalent tool evidence.
- Use stderr and file state for targeted retries instead of repeating the same call.
- Declare artifact paths that should be returned to the user.
- Preserve full receipts and hashes in task records while showing concise Codex-style output in the conversation.

## Network boundary

The Linux guest has no general network interface. Public retrieval is performed by bounded Android web tools, and retrieved content is treated as untrusted input before it reaches a model or runtime. This keeps network policy, certificates, cancellation, download limits, and audit evidence under phone control.

## Safety tiers

Sandboxed runtime execution is direct because it is isolated, bounded, app-private, and offline. Installing a signed runtime pack uses first-confirm consent. Actions that leave the phone trust boundary, modify security state, send messages, place calls, install Android apps, delete user data, or control high-impact devices continue to use their stricter confirmation tiers.
