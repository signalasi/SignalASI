# Stable-branch protection audit, 2026-09-06

## Scope

Add read-only, repeatable verification of GitHub's effective branch protection to
the release audit. This change does not install or modify repository rulesets,
reviewer requirements, bypass actors, user permissions, or merge settings.
It is one release-gate phase, not completion of the agent reliability goal.

## Live evidence

At `2026-09-06T09:21:31.436Z`, the audit inspected `galaxyssi/GalaxySSI/main`
at `3c48121ac60c04bc6649b64ec985b70ea00ec1d3` using authenticated GET requests.

- Effective ruleset `19381337` was active, applied to branches, and exposed an
  empty bypass-actor list.
- All nine policy checks were required: `repository-check`, `core-regressions`,
  `desktop-source-smoke`, `android-build`, `package-win`, `backend`,
  `desktop-source`, `android`, and `trusted-automation-review`.
- Up-to-date status checks, PR-based changes, deletion prevention, and
  force-push prevention were all verified.
- The legacy protection endpoint returned `Branch not protected`. That response
  does not negate the effective ruleset, so the combined audit correctly passed.
- No permission, configuration or branch mutation was performed.

The audit PR exposed a separate CI trigger mismatch: `backend`, `desktop-source`,
and `android` were required by the ruleset, but the Evolution candidate workflow
had a path filter that excluded tooling-only and documentation-only PRs. Remove
that workflow-level path filter so every PR targeting main runs these checks.
The jobs and their test commands remain unchanged; no synthetic success or skipped
job is substituted for actual verification. This increases CI work for docs-only
changes but keeps the checks consistent with the existing mandatory ruleset.
This follows GitHub's documented treatment of
[skipped required checks](https://docs.github.com/en/pull-requests/how-tos/merge-and-close-pull-requests/troubleshooting-required-status-checks).

The locally retained structured result is
`build/reports/branch-protection.json`. This timestamped observation is not a
claim that configuration will remain unchanged; rerun the audit before release.

## Regression and integration checks

- `npm run test:branch-protection`: 31 tests passed, no failures or skips.
- `node tools/dev/check-repo.js`: repository checks passed.
- Tests cover every required check independently, non-strict rules, bypasses,
  incomplete visibility, stale snapshots, disabled/evaluate-only rules,
  organization and legacy rules, malformed responses, denied reads, and the
  command's explicit unverified failure result.
- Strict release checks also reject empty CI results, absent workflows,
  wrong-commit results, failed, cancelled, skipped, and incomplete runs.
- The release audit now queries CI for the current commit rather than unrelated
  recent branch runs. The live strict integration identified the main commit's
  successful Repository Guard and Windows Package runs, verified branch
  protection, and exited 1 because this development worktree was still dirty.
  That is the expected refusal, not a successful release acceptance.

Repository Guard runs the deterministic tests without an administrative token.
The separate live audit requires existing authorized read access. Unknown
protection evidence exits 2; established policy failures exit 1; verified
configuration exits 0.

## Remaining release acceptance

This audit does not prove that every CI job is currently green, signed artifacts
exist, SBOMs are complete, builds are reproducible, or device/network chaos tests
have passed. Those remain independent evidence requirements. No App or Desktop
runtime is modified, rebuilt, installed or restarted by this tooling change.
