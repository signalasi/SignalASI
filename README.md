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
npm run smoke:desktop:e2e
```

Windows desktop package:

```bash
cd apps/desktop
npm run package:win:python
npm run smoke:packaged
```
