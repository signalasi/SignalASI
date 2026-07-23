# Conversation Context Management

SignalASI treats the stored transcript and the model input as separate data
products. The complete transcript remains the source of truth; each model call
receives a compiled context that fits that model's input budget.

## Provider modes

- Codex App Server is stateful. SignalASI sends only the current turn and keeps
  the SignalASI conversation ID mapped to the existing Codex thread. Codex owns
  internal compaction and emits context-compaction lifecycle events.
- Direct mobile cloud APIs, Desktop cloud APIs, and local OpenAI-compatible
  endpoints are stateless. SignalASI compiles their context before every call.
- Tool loops use transient pruning. Completed old tool blocks may be summarized,
  but a tool call and its matching result are never split.

## Compaction pipeline

1. Resolve the model context window and reserve output tokens.
2. Estimate input pressure with CJK-aware token estimates.
3. Keep complete recent turn groups according to a token budget, not a message
   count.
4. Preserve unresolved user turns and pending tool blocks.
5. Remove large historical tool payloads before removing dialogue.
6. Compact the older prefix into a reference-only structured handoff.
7. Refine that handoff with the configured model when available; use the local
   deterministic handoff if the auxiliary request fails.
8. Persist the handoff with a transcript cursor so the same prefix is not
   summarized again.
9. Put the latest user request last and let it override stale goals.

The compiler redacts credentials, embedded Base64 payloads, and long opaque
values before generating a handoff. Exact paths, URLs, decisions, constraints,
errors, verified outcomes, and unresolved work are retained.

## Context policies

Context policy changes token budgets rather than selecting a fixed number of
turns:

- Minimal: 16K context with a 4K output reserve.
- Balanced: 32K context with a 4K output reserve.
- Extended: 64K context with an 8K output reserve.

Direct cloud contacts can override their context window and maximum output
tokens. Desktop local and cloud model configuration exposes the same runtime
fields.

## Design references

The implementation follows the same broad separation used by current agent
systems:

- Hermes Agent combines in-loop compression, a gateway safety threshold,
  token-usage feedback, and an auxiliary compression model.
- OpenClaw keeps a recent token tail, preserves tool-call/result boundaries,
  flushes durable memory before compaction, and retries after context overflow.
- Codex App Server owns thread history and reports automatic context-compaction
  events to clients.

SignalASI keeps a deterministic fallback because mobile networking and private
local endpoints can be unavailable precisely when context pressure is highest.
