import { spawnSync } from "node:child_process";
import { isDeepStrictEqual } from "node:util";

export function validateProtectionPolicy(policy) {
  if (policy?.version !== 1 || typeof policy.repository !== "string" ||
      !/^[A-Za-z0-9_.-]+\/[A-Za-z0-9_.-]+$/.test(policy.repository) ||
      policy.repository.split("/").some((part) => part === "." || part === "..") ||
      typeof policy.branch !== "string" || !policy.branch ||
      /[\x00-\x20*?\[\\]/.test(policy.branch) || policy.branch.startsWith("-")) {
    throw new Error("invalid_protection_policy_target");
  }
  const checks = policy.required_checks;
  if (!Array.isArray(checks) || checks.length === 0 ||
      checks.some((name) => typeof name !== "string" || !name.trim() || name !== name.trim()) ||
      new Set(checks).size !== checks.length) {
    throw new Error("invalid_protection_policy_checks");
  }
  return policy;
}

function noLegacyPullRequestBypass(reviews) {
  const bypass = reviews?.bypass_pull_request_allowances;
  return bypass == null || ["users", "teams", "apps"].every((key) =>
    Array.isArray(bypass[key]) && bypass[key].length === 0);
}

/** Evaluate only effective rules returned by GitHub, never a hand-written ref glob matcher. */
export function evaluateProtection(policy, { effectiveRules, rulesets, legacy }) {
  validateProtectionPolicy(policy);
  if (!Array.isArray(effectiveRules) || !Array.isArray(rulesets)) throw new Error("invalid_protection_evidence");
  if (legacy != null && typeof legacy.enforce_admins?.enabled !== "boolean") {
    throw new Error("invalid_legacy_protection_evidence");
  }
  const accepted = [];
  const excluded = [];
  for (const rule of effectiveRules) {
    const detail = rulesets.find((item) => item.id === rule.ruleset_id &&
      item.source_type === rule.ruleset_source_type && item.source === rule.ruleset_source);
    let reason = "";
    if (!detail || !Array.isArray(detail.rules) || !Array.isArray(detail.bypass_actors)) {
      reason = "ruleset_visibility_incomplete";
    } else if (detail.enforcement !== "active" || detail.target !== "branch") {
      reason = "ruleset_not_enforced";
    } else if (detail.bypass_actors.length) {
      reason = "ruleset_allows_bypass";
    } else if (!detail.rules.some((candidate) => candidate.type === rule.type &&
        isDeepStrictEqual(candidate.parameters ?? {}, rule.parameters ?? {}))) {
      reason = "ruleset_snapshot_mismatch";
    }
    if (reason) excluded.push({ ruleset_id: rule.ruleset_id, type: rule.type, reason });
    else accepted.push(rule);
  }

  const checks = new Set();
  let strict = false;
  for (const rule of accepted.filter((item) => item.type === "required_status_checks")) {
    for (const check of rule.parameters?.required_status_checks ?? []) {
      if (typeof check?.context === "string") checks.add(check.context);
    }
    strict ||= rule.parameters?.strict_required_status_checks_policy === true;
  }
  let pullRequest = accepted.some((rule) => rule.type === "pull_request");
  let noDeletion = accepted.some((rule) => rule.type === "deletion");
  let noForcePush = accepted.some((rule) => rule.type === "non_fast_forward");
  const legacyEnforced = legacy?.enforce_admins?.enabled === true;
  if (legacyEnforced) {
    for (const context of legacy.required_status_checks?.contexts ?? []) {
      if (typeof context === "string") checks.add(context);
    }
    for (const check of legacy.required_status_checks?.checks ?? []) {
      if (typeof check?.context === "string") checks.add(check.context);
    }
    strict ||= legacy.required_status_checks?.strict === true;
    pullRequest ||= Boolean(legacy.required_pull_request_reviews &&
      noLegacyPullRequestBypass(legacy.required_pull_request_reviews));
    noDeletion ||= legacy.allow_deletions?.enabled === false;
    noForcePush ||= legacy.allow_force_pushes?.enabled === false;
  }
  const missingChecks = policy.required_checks.filter((name) => !checks.has(name));
  const failures = [];
  if (missingChecks.length) failures.push("required_checks_missing");
  if (!strict) failures.push("up_to_date_branch_not_required");
  if (!pullRequest) failures.push("pull_request_not_required_without_bypass");
  if (!noDeletion) failures.push("branch_deletion_not_prevented_without_bypass");
  if (!noForcePush) failures.push("force_push_not_prevented_without_bypass");
  const incompleteEvidence = excluded.some((rule) =>
    ["ruleset_visibility_incomplete", "ruleset_snapshot_mismatch"].includes(rule.reason));
  return {
    passed: failures.length === 0,
    status: failures.length === 0 ? "verified" : incompleteEvidence ? "unverified" : "noncompliant",
    repository: policy.repository,
    branch: policy.branch,
    required_checks: policy.required_checks,
    enforced_checks: [...checks].sort(),
    missing_checks: missingChecks,
    strict_required_checks: strict,
    pull_request_required: pullRequest,
    deletion_prevented: noDeletion,
    force_push_prevented: noForcePush,
    ruleset_ids: [...new Set(accepted.map((item) => item.ruleset_id))].sort((a, b) => a - b),
    legacy_present: legacy != null,
    legacy_admin_enforcement: legacyEnforced,
    excluded_rules: excluded,
    failures
  };
}

export function readGithubJson(endpoint, { paged = false, allowUnprotected = false } = {}, run = spawnSync) {
  const args = ["api", "--method", "GET", endpoint, "-H", "Accept: application/vnd.github+json",
    "-H", "X-GitHub-Api-Version: 2022-11-28"];
  if (paged) args.push("--paginate", "--slurp");
  const result = run("gh", args, { encoding: "utf8", shell: false, windowsHide: true,
    timeout: 30_000, maxBuffer: 8 * 1024 * 1024 });
  let value;
  try { value = JSON.parse(result.stdout || "null"); }
  catch { throw new Error("github_invalid_json"); }
  if (result.error || result.status !== 0) {
    if (!result.error && allowUnprotected && [404, "404"].includes(value?.status) && value.message === "Branch not protected") return null;
    throw new Error("github_read_unavailable");
  }
  if (value == null) throw new Error("github_empty_response");
  if (!paged) return value;
  if (!Array.isArray(value) || value.some((page) => !Array.isArray(page))) throw new Error("github_invalid_pages");
  return value.flat();
}

export function auditRepositoryProtection(policy, read = readGithubJson) {
  validateProtectionPolicy(policy);
  const repository = policy.repository;
  const branch = encodeURIComponent(policy.branch);
  const metadata = read(`repos/${repository}/branches/${branch}`);
  if (metadata.name !== policy.branch || !/^[a-f0-9]{40}$/.test(metadata.commit?.sha ?? "")) {
    throw new Error("github_branch_identity_mismatch");
  }
  const effectiveRules = read(`repos/${repository}/rules/branches/${branch}`, { paged: true });
  if (!Array.isArray(effectiveRules)) throw new Error("github_invalid_rules");
  const rulesets = [];
  for (const rule of effectiveRules) {
    if (!Number.isSafeInteger(rule.ruleset_id) || rule.ruleset_id < 1) throw new Error("github_invalid_ruleset_id");
    if (rulesets.some((item) => item.id === rule.ruleset_id)) continue;
    let endpoint;
    if (rule.ruleset_source_type === "Repository" && rule.ruleset_source === repository) {
      endpoint = `repos/${repository}/rulesets/${rule.ruleset_id}`;
    } else if (rule.ruleset_source_type === "Organization" && rule.ruleset_source === repository.split("/")[0]) {
      endpoint = `orgs/${rule.ruleset_source}/rulesets/${rule.ruleset_id}`;
    } else throw new Error("github_unsupported_ruleset_source");
    rulesets.push(read(endpoint));
  }
  const legacy = read(`repos/${repository}/branches/${branch}/protection`, { allowUnprotected: true });
  return { observed_at: new Date().toISOString(), head_sha: metadata.commit.sha,
    ...evaluateProtection(policy, { effectiveRules, rulesets, legacy }) };
}
