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
- effective GitHub branch rulesets and legacy protection for the configured stable branch
- latest public GitHub Actions status for Repository Guard and Windows Package at the current commit

Strict mode exits non-zero when the working tree is dirty, when a required workflow is not for the current commit, when a required workflow has not completed successfully, or when branch protection is incomplete or cannot be verified.
Missing CI runs, including an empty API result, also fail strict mode. The CI query
is scoped to HEAD rather than unrelated recent runs from other branches. It reads
up to 100 recent runs for that commit; missing evidence fails closed.

## Read-Only Protection Audit

```bash
npm run audit:branch-protection -- --report build/reports/branch-protection.json
npm run test:branch-protection
```

The live audit requires GitHub CLI authentication with sufficient read visibility
for the selected repository's protection and ruleset bypass settings. It performs
GET requests only. No branch protection, reviewer requirement, permission or
merge setting is changed. CI runs deterministic evaluator tests without adding
an administrative token; release operators run the live audit with their existing
authorized account. An unreadable bypass list is not treated as an empty list.

`.github/release-protection-policy.json` declares the stable branch and required
check names. GitHub's effective-rules endpoint resolves applicability, including
organization rules and ref exclusions; this tool does not implement its own glob
matcher. A legacy `Branch not protected` 404 does not mean there are no rulesets.
Other permission, network, malformed-response or missing-branch failures are
reported as unverified (exit 2), not success. Verified policy failures exit 1.

The policy requires non-bypass coverage for PR-based changes, all declared status
checks, up-to-date branches, deletion prevention and force-push prevention. It
does not introduce a human review count or an account-specific merger restriction.
This is a time-stamped configuration audit, not a destructive attempt to push to
main, evidence that CI is green, or proof of signed/reproducible release artifacts.

API references: [effective branch rules](https://docs.github.com/en/rest/repos/rules#get-rules-for-a-branch)
and [legacy branch protection](https://docs.github.com/en/rest/branches/branch-protection#get-branch-protection).

The audit is a release checklist entry point. It does not replace the individual smoke commands in `docs/testing/README.md`.
