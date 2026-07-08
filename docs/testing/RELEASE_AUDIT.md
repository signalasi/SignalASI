# Release Audit

Release audit documents and scripts keep release readiness tied to the product requirements and testing matrix.

Run the audit before publishing a build:

```bash
npm run audit:release
```

Run the strict audit after local gates and GitHub Actions are expected to be green:

```bash
npm run audit:release:strict
```

Run the local non-device gate bundle before strict audit:

```bash
npm run test:release:local
```

Run the Android device gate bundle before publishing a mobile build:

```bash
npm run test:release:device
```

The audit prints:

- current Git branch and commit
- clean or dirty working tree state
- required local gates
- local non-device gate bundle
- Android device gates
- Android device gate bundle
- Android destructive reset gate
- network-dependent MQTT persistence gate
- manual release checks
- latest public GitHub Actions status for Repository Guard and Windows Package

Strict mode exits non-zero when the working tree is dirty, when a required workflow is not for the current commit, or when a required workflow has not completed successfully.

The audit is a release checklist entry point. It does not replace the individual smoke commands in `docs/testing/README.md`.
