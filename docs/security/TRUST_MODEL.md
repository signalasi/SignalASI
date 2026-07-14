# Trust Model

SignalASI treats the network, broker, and agent runtime as separate trust zones. The user explicitly decides which phone, desktop, agent, model, or device becomes a trusted contact.

## Trust Zones

| Zone | Trusted for | Not trusted for |
| --- | --- | --- |
| Android app | User approval, local identity, contact list, mobile cloud model settings, voice settings, message display. | Desktop local process integrity after pairing. |
| Desktop connector | Local agent execution, pairing QR generation, SignalASI Link sidecar, mobile delivery bridge. | User approval on the phone. |
| MQTT broker | Message relay and QoS delivery. | Message confidentiality, identity trust, or authorization decisions. |
| Local agents | Returning task results for their configured contact. | Reading unrelated contacts, pairing secrets, or phone identity state. |
| Cloud model APIs | Processing prompts sent directly by the mobile app for that provider. | Desktop pairing, local agent routing, or other provider credentials. |

## Identity And Pairing

- Android creates a persistent libsignal identity through `AndroidPersistentSignalStore`.
- Desktop exposes a SignalASI Link verification payload at `/signalasi/verify`.
- The QR payload uses `type: signalasi_verify`, includes the Desktop identity key, and includes the SHA-256 fingerprint of that key.
- Android recomputes the fingerprint from the scanned identity key before accepting it.
- The phone stores the verified Desktop fingerprint and processes the Desktop signal bundle only after fingerprint verification.
- Every Server-Client relationship has independent opaque route IDs and an independent Signal session. Pairing a new client does not replace existing clients.
- The bootstrap claim is encrypted with a one-time QR secret before it reaches MQTT; the secret expires after ten minutes and is consumed once.
- Revocation is relationship-scoped. It removes only the selected route, registry entry, and Signal session.
- Deleted contacts must be re-added before communication is allowed again.

## Message Protection

- Android refuses plaintext Desktop publish when no Signal session is ready.
- Android encrypts trusted Desktop and contact payloads as Signal envelopes with `scheme: signal`.
- Desktop decrypts and encrypts through the local SignalASI Link sidecar.
- Agent/contact IDs and message content are inside the Signal ciphertext. The relay can still observe opaque topic names, timing, delivery behavior, and coarse message size.
- Delivery traces and acknowledgements prove routing state; they are not a substitute for identity verification.

## Broker Boundary

SignalASI can use a public MQTT broker for reachability. The broker must be treated as an untrusted relay:

- It may observe topic names, timing, QoS behavior, client reconnects, and message sizes.
- It must not receive plaintext message content after pairing.
- It must not decide whether a phone or Desktop is trusted.
- It must not be the only place where delivery state is stored.
- MQTT transport uses certificate-validated TLS. The public EMQX relay is development-only; production requires an authenticated broker and route-scoped ACLs.

## Local Data Boundary

Local identity and runtime files are device-local state:

- Android stores identity, sessions, contacts, chat history, backup/import data, and voice settings locally.
- Desktop stores pairing state, agent config, push token, execution logs, databases, uploads, and downloads locally.
- Generated packages, local databases, logs, smoke evidence, pairing state, and tokens are blocked from Git by `.gitignore` and `npm run check`.
- Clearing app data must regenerate the Android identity and remove contacts/history from the active app state.

## Agent Permission Boundary

- Desktop agents are contacts, not global administrators.
- Custom Agent and MCP execution should receive the prompt through stdin or the configured wrapper, not by leaking prompts into command-line arguments by default.
- Agent push requires `X-SignalASI-Token`.
- Execution logs record contact ID, command path or process class, prompt hash, and result metadata for auditability.

## Current Security Limits

- Opaque relationship routes reduce correlation but do not make a public MQTT broker anonymous transport.
- Android hardware DSP wake through OEM SoundTrigger paths is deferred.
- Group chat is deferred.
- Cloud model API calls are direct mobile integrations and inherit the selected provider account and API policy.
- Full public security review is required before claiming production-grade cryptographic assurance.

## Required Evidence

- `npm run check`
- `npm run smoke:desktop:pairing`
- `npm run smoke:desktop:e2e`
- `npm run smoke:desktop:voice-stt`
- `npm run smoke:android:ui`
- `npm run smoke:android:friends`
- `npm run smoke:android:contact-rename`
- `npm run smoke:android:contact-tags`
- `npm run smoke:android:cloud-models`
- `npm run smoke:android:background`
- `npm run smoke:android:agent-replies`
- `npm run smoke:android:backup`
- `npm run smoke:android:voice-reply`
- `npm run smoke:android:voice-settings`
- `npm run smoke:android:reset`
