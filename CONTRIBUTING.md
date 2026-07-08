# Contributing

Thank you for contributing to SignalASI.

## Rules

- Use English for code, comments, documentation, commits, pull requests, and release notes.
- Put user-visible localized strings in the proper i18n resource files.
- Do not commit secrets, local device state, generated packages, logs, databases, or temporary screenshots.
- Keep protocol changes documented under `docs/protocol`.
- `npm run check` rejects tracked generated artifacts such as APKs, installers, smoke screenshots, UI dumps, local databases, logs, and pairing state.

## Checks

Run these checks before opening a pull request:

```bash
npm run check
npm run check:android
npm run smoke:android:ui
npm run smoke:android:friends
npm run smoke:android:background
npm run smoke:desktop
npm run smoke:desktop:e2e
npm run smoke:desktop:packaged
```

Smoke commands that touch the Desktop backend, MQTT broker, packaged app, or Android device must run sequentially because they share the same local backend port and test lock.

Use `docs/testing/README.md` as the release test matrix before publishing a build.
