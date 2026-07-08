# Product Requirements

SignalASI is a private superintelligence interface for trusted communication between people, mobile devices, desktop computers, agents, models, and local devices. This document defines the current product requirements that must stay aligned with implementation and test coverage.

## Product Principles

- The phone is the primary command surface.
- Every agent, model, device, and desktop endpoint appears as a contact.
- Trust starts with explicit pairing, identity fingerprints, and user confirmation.
- Mobile cloud models are added and called directly from the Android app.
- Desktop connectors expose local agents and tools without requiring those agents to embed SignalASI-specific SDK code.
- Voice interaction must support local wake, command capture, ASR routing, TTS replies, and a visible reply panel.
- Generated packages, local identity state, pairing state, logs, databases, and test evidence must not be committed.

## Android Requirements

| Area | Requirement | Verification |
| --- | --- | --- |
| Main navigation | Android opens to the Voice page and supports the Messages, Contacts, Discover, and Settings tabs. | `npm run smoke:android:ui` |
| Messages | Messages show contacts, unread state, timestamps, delivery evidence, full Agent replies, and persisted conversation history. | `npm run smoke:android:background`, `npm run smoke:android:agent-replies` |
| Contacts | Contacts can represent agents, models, and devices with type labels and editable display names. | `npm run smoke:android:ui` |
| New friends | Newly scanned peers appear in New Friends until approved, and deleted contacts require re-adding before communication. | `npm run smoke:android:friends` |
| Pairing | QR scan accepts `/signalasi/verify` payloads, stores peer fingerprints, and creates trusted contacts only after confirmation. | `npm run smoke:android:ui`, `npm run smoke:desktop:pairing` |
| Security center | The app displays phone and desktop fingerprints, paired devices, protocol quality, and revocation controls. | `npm run smoke:android:ui` |
| Cloud models | The app can add multiple providers as direct mobile model contacts; provider contact lists show provider names, while the chat header switches the selected model. | `npm run smoke:android:ui` |
| Voice page | Local wake mode, ASR provider selection, PC STT routing, TTS settings, and reply preservation are available from the app. | `npm run smoke:android:ui`, `npm run smoke:android:voice-reply` |
| Backup and import | Settings exposes backup export, backup import, and destructive data reset entry points. | `npm run smoke:android:ui` |
| Destructive reset | Clear All Data rotates local identity, clears contacts and trust state, and recreates the welcome system notification. | `npm run smoke:android:reset` |
| Background delivery | MQTT identity, QoS 1 behavior, background history, and local notification history survive app restart. | `npm run smoke:android:background` |
| Agent reply preservation | Hermes and Codex replies stay complete after contact switching and UI refresh, with delivery trace evidence persisted. | `npm run smoke:android:agent-replies` |
| Voice reply preservation | Long Agent replies stay visible on the Voice page response panel and persist into Hermes chat history with delivery trace evidence. | `npm run smoke:android:voice-reply` |

## Desktop Requirements

| Area | Requirement | Verification |
| --- | --- | --- |
| Pairing server | Desktop exposes `/signalasi/verify`, pairing status, pairing clear, and revocation APIs. | `npm run smoke:desktop:pairing` |
| Agent contacts | Hermes, Codex, Claude Code, Local LLM, Custom Agent, and additional custom agents are discoverable as contacts. | `npm run smoke:desktop` |
| Agent execution | Desktop can call local CLI agents, stdin custom agents, MCP wrappers, and local model endpoints. | `npm run smoke:desktop:e2e` |
| Agent push | Long-running agents can call the local push API with `X-SignalASI-Token` and publish results to the paired phone route. | `npm run smoke:desktop:agent-push` |
| Diagnostics | Desktop reports structured status, setup guidance, pairing state, execution logs, and runtime requirements. | `npm run smoke:desktop`, `npm run smoke:desktop:e2e` |
| Packaging | Windows Desktop packaging includes the backend, docs, sidecar hooks, dependency installer, and packaged smoke evidence. | `npm run package:desktop:win`, `npm run smoke:desktop:packaged` |
| Local-only data | Pairing state, tokens, logs, databases, uploads, downloads, screenshots, and packaged binaries remain ignored and untracked. | `npm run check` |

## Protocol And Security Requirements

| Area | Requirement | Verification |
| --- | --- | --- |
| Naming | Public protocol routes and payloads use SignalASI naming only; old Hermes pairing route names are rejected. | `npm run check`, `npm run smoke:desktop:pairing` |
| Trust model | First contact requires QR pairing, identity bundle exchange, fingerprint display, and explicit approval. | `npm run smoke:desktop:pairing`, `npm run smoke:android:ui` |
| Message protection | SignalASI Link messaging tracks client message IDs, delivery acknowledgements, and delivery trace events. | `npm run check`, `npm run smoke:desktop:e2e` |
| Pairing replacement | A newly paired app invalidates the previous paired app through revocation/pairing-state updates. | `npm run smoke:desktop:pairing` |
| Unpaired guard | Desktop refuses phone delivery APIs when no trusted phone is paired. | `npm run smoke:desktop:pairing` |

Security boundaries are documented in `docs/security/TRUST_MODEL.md`.

## Release Requirements

- `npm run check` must pass.
- `npm run check:android` must pass.
- Desktop source smoke and pairing smoke must pass locally and in CI.
- Android device smoke checks should pass before publishing a mobile build.
- Windows package smoke should pass before publishing a Desktop build.
- Manual release checks in `docs/testing/README.md` should be completed before a public release.

## Deferred Scope

- iOS client.
- Native macOS and Linux packaging polish beyond the shared Electron Desktop layout.
- Multi-member group chat.
- Hardware DSP wake integration through OEM-specific Android SoundTrigger paths.
