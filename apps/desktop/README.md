# SignalASI Desktop

SignalASI Desktop is an Electron connector that pairs the mobile app with local agents, local models, custom CLI tools, and MCP wrappers.

## Development

```bash
npm install
npm run check
npm run smoke
```

## Package for Windows

```bash
npm run package:win:python
npm run smoke:packaged
```

The packaged app is written to:

```text
dist/SignalASI Desktop-win-x64/
```

## Layout

```text
src/                         Electron main process, preload, and renderer
scripts/                     Checks, smoke tests, and packaging scripts
core/signalasi-link/backend  FastAPI backend, MQTT bridge, STT bridge, and sidecar launcher
assets/                      Desktop icons and brand assets
docs/                        Desktop connector notes
```

## Pairing Route

The mobile pairing route is:

```text
/signalasi/verify
```

