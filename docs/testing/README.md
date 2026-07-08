# Testing Matrix

This matrix maps the current product scope to the checks that prove each area is still working. Run smoke commands sequentially because Android device tests, Desktop backend tests, packaged Desktop tests, and MQTT delivery tests share local ports, device state, broker topics, and a repository test lock.

Product scope is defined in `docs/product/PRODUCT_REQUIREMENTS.md`.

## Required Gates

| Gate | Command | Proves |
| --- | --- | --- |
| Repository policy | `npm run check` | Public docs and code stay English-first outside i18n files, Desktop structure is intact, protocol naming is SignalASI-only, and required capability markers still exist. |
| Android build | `npm run check:android` | The Android app compiles into a debug APK with the current Gradle wrapper and Android project layout. |
| Android UI smoke | `npm run smoke:android:ui` | Main navigation, contacts, cloud model entry, security center, settings entry points, voice settings, backup, protocol quality, and destructive-data screens can be opened on a real device or emulator. |
| Android friend flow | `npm run smoke:android:friends` | New-friend approval, deleted-contact blocking, re-add flow, contact detail routing, and contact deletion evidence work on Android. |
| Android background delivery | `npm run smoke:android:background` | Offline or background message persistence, notification history, unread handling, stable MQTT client identity, and QoS 1 delivery evidence are present. |
| Android destructive reset | `npm run smoke:android:reset` | Clear All Data rotates the local identity, clears contacts and trust state, recreates the welcome system notification, and restores test device state afterward. |
| Desktop connector smoke | `npm run smoke:desktop` | Desktop backend starts, pairing endpoints respond, connector diagnostics are structured, default agents are discoverable, and basic agent/mobile APIs respond. |
| Desktop pairing smoke | `npm run smoke:desktop:pairing` | QR pairing, phone claim handling, pairing status, pairing revocation, and unpaired access guards are enforced. |
| Desktop agent push smoke | `npm run smoke:desktop:agent-push` | Long-running agents and scripts can call the local push API with token validation and publish messages to the paired phone path. |
| Desktop MQTT persistence smoke | `npm run smoke:desktop:mqtt-persistence` | Desktop and Android MQTT topics use persistent client identity and QoS settings required for delayed delivery. |
| Desktop UI smoke | `npm run smoke:desktop:ui` | Electron renderer loads, connector panels are visible, Desktop UI screenshots can be captured, and localized UI wiring does not regress. |
| Desktop end-to-end smoke | `npm run smoke:desktop:e2e` | Hermes, Codex, Claude Code, Local LLM, Custom Agent, MCP wrapper, status matrix, execution log, and setup helpers work together through the source backend. |
| Windows package build | `npm run package:desktop:win` | The Windows Desktop package is assembled with the backend, docs, sidecar runtime hooks, dependency installer, and packaged scripts. |
| Packaged Desktop smoke | `npm run smoke:desktop:packaged` | The packaged Windows Desktop app uses the current source backend layout, includes required runtime files, exposes diagnostics, and captures packaged UI evidence. |

The `Windows Package` GitHub Actions workflow runs the Windows package build and packaged smoke gates for Desktop packaging changes and can also be started manually.

Run `npm run audit:release` to print the release gate checklist and the latest public GitHub Actions status. Run `npm run audit:release:strict` after the local gates and GitHub Actions should be green; strict mode fails if the working tree is dirty or the required workflows are not successful for the current commit.

## Product Coverage

| Product area | Primary evidence |
| --- | --- |
| Mobile chat and contacts | `npm run smoke:android:ui`, `npm run smoke:android:friends`, `npm run smoke:android:background`, `npm run smoke:android:reset` |
| QR pairing and fingerprint trust | `npm run smoke:desktop:pairing`, `npm run smoke:android:ui`, `npm run smoke:desktop:e2e` |
| SignalASI Link encrypted messaging | `npm run check`, `npm run smoke:desktop:pairing`, `npm run smoke:desktop:e2e` |
| Hermes, Codex, Claude Code, Local LLM, Custom Agent, and MCP contacts | `npm run smoke:desktop`, `npm run smoke:desktop:e2e` |
| Direct mobile cloud model contacts | `npm run smoke:android:ui`, `npm run check` |
| Voice wake, voice recording, ASR, and TTS settings | `npm run smoke:android:ui`, `npm run check` |
| Background notification and delayed message handling | `npm run smoke:android:background`, `npm run smoke:desktop:mqtt-persistence` |
| Desktop packaged Windows release | `npm run package:desktop:win`, `npm run smoke:desktop:packaged` |

## Manual Release Checks

- Pair a fresh Android install with a fresh Desktop install using the `/signalasi/verify` QR flow.
- Send text messages from Android to Hermes and Codex and confirm full replies arrive on the phone.
- Send a voice message to Hermes and confirm Desktop STT is used when configured.
- Exercise the Voice page wake loop and confirm replies are preserved in the voice response panel.
- Clear Android app data and confirm a new identity fingerprint, empty contacts, and a new welcome notification are created. Automated evidence: `npm run smoke:android:reset`.
- Verify exported APK, Desktop EXE, local databases, logs, screenshots, pairing state, tokens, and `node_modules` are not staged for Git.
