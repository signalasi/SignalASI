# SignalASI

SignalASI is a private superintelligence interface that turns phones, computers, agents, models, and devices into a trusted AI mesh.

The project combines a mobile-first command surface, Signal-style trusted pairing, encrypted agent messaging, voice interaction, and a desktop connector that makes local tools, cloud models, and autonomous agents reachable as secure contacts.

## Repository Layout

```text
apps/android      Native Android app
apps/ios          Future iOS app
apps/desktop      Electron desktop app for Windows, macOS, and Linux
docs              Product, protocol, architecture, security, setup, and design docs
assets            Logos, icons, screenshots, and marketing media
tools             Development, diagnostics, release, and migration tools
tests             Cross-platform fixtures and end-to-end tests
```

## Current Apps

- Android app: `apps/android`
- Desktop connector: `apps/desktop`
- SignalASI Link core: `apps/desktop/core/signalasi-link`

## Development

Repository checks:

```bash
npm run check
```

Android:

```bash
npm run check:android
npm run smoke:android:ui
npm run smoke:android:friends
npm run smoke:android:background
npm run smoke:android:agent-replies
npm run smoke:android:backup
npm run smoke:android:voice-reply
npm run smoke:android:reset
```

Desktop:

```bash
cd apps/desktop
npm install
npm run check
npm run smoke
```

Desktop smoke gates from the repository root:

```bash
npm run smoke:desktop
npm run smoke:desktop:voice-stt
npm run smoke:desktop:e2e
```

Run smoke commands sequentially; they share the local backend port and test lock.

Full test coverage is documented in `docs/testing/README.md`.

Product requirements are documented in `docs/product/PRODUCT_REQUIREMENTS.md`.

Local release gates:

```bash
npm run test:release:local
npm run test:release:device
```

Windows desktop package:

```bash
npm run package:desktop:win
npm run smoke:desktop:packaged
```

The Windows package workflow runs the same package and packaged-smoke gates on Desktop packaging changes.

Release audit:

```bash
npm run audit:release
npm run audit:release:strict
```
