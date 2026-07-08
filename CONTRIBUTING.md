# Contributing

Thank you for contributing to SignalASI.

## Rules

- Use English for code, comments, documentation, commits, pull requests, and release notes.
- Put user-visible localized strings in the proper i18n resource files.
- Do not commit secrets, local device state, generated packages, logs, databases, or temporary screenshots.
- Keep protocol changes documented under `core/protocol`.

## Checks

Run these checks before opening a pull request:

```bash
node tools/dev/check-no-chinese-outside-i18n.js
cd apps/desktop && npm run check
cd ../android && ./gradlew assembleDebug --no-daemon
```

