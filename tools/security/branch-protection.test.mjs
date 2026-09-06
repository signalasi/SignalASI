import assert from "node:assert/strict";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import { spawnSync } from "node:child_process";
import { fileURLToPath } from "node:url";
import test from "node:test";
import { auditRepositoryProtection, evaluateProtection, readGithubJson, validateProtectionPolicy } from "./branch-protection.mjs";
import releaseAudit from "../dev/release-audit.js";

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..", "..");
const policy = JSON.parse(fs.readFileSync(path.join(root, ".github/release-protection-policy.json"), "utf8"));
const sha = "a".repeat(40);

test("strict release audit cannot pass with an empty CI response", () => {
  assert.equal(releaseAudit.workflowAuditFailures([], sha).length, 2);
});

test("strict release audit checks exact commits and successful completion for each workflow", () => {
  const runs = ["Repository Guard", "Windows Package"].map((name) => ({ name, head_sha: sha,
    status: "completed", conclusion: "success" }));
  assert.deepEqual(releaseAudit.workflowAuditFailures(runs, sha), []);
  assert.equal(releaseAudit.workflowAuditFailures(runs.slice(1), sha).length, 1);
  for (const override of [{ head_sha: "b".repeat(40) }, { status: "in_progress" },
    { conclusion: "failure" }, { conclusion: "cancelled" }, { conclusion: "skipped" }]) {
    assert.equal(releaseAudit.workflowAuditFailures([{ ...runs[0], ...override }, runs[1]], sha).length, 1);
  }
});

function fixture() {
  const rules = [
    { type: "deletion" }, { type: "non_fast_forward" },
    { type: "pull_request", parameters: { required_approving_review_count: 0 } },
    { type: "required_status_checks", parameters: { strict_required_status_checks_policy: true,
      required_status_checks: policy.required_checks.map((context) => ({ context })) } }
  ];
  const detail = { id: 7, source_type: "Repository", source: policy.repository,
    enforcement: "active", target: "branch", bypass_actors: [], rules };
  return { legacy: null, rulesets: [detail], effectiveRules: structuredClone(rules).map((rule) => ({
    ...rule, ruleset_id: detail.id, ruleset_source: detail.source, ruleset_source_type: detail.source_type
  })) };
}
function legacy() {
  return { enforce_admins: { enabled: true }, required_status_checks: { strict: true,
    contexts: [...policy.required_checks] }, required_pull_request_reviews: { required_approving_review_count: 0 },
    allow_deletions: { enabled: false }, allow_force_pushes: { enabled: false } };
}

test("active effective rules satisfy the policy without legacy protection or human review counts", () => {
  const result = evaluateProtection(policy, fixture());
  assert.equal(result.passed, true);
  assert.equal(result.status, "verified");
  assert.equal(result.legacy_present, false);
  assert.equal(result.required_checks.length, 9);
  assert.deepEqual(result.ruleset_ids, [7]);
});

for (const [name, change, reason] of [
  ["disabled ruleset", (detail) => { detail.enforcement = "disabled"; }, "ruleset_not_enforced"],
  ["evaluate-only ruleset", (detail) => { detail.enforcement = "evaluate"; }, "ruleset_not_enforced"],
  ["wrong target", (detail) => { detail.target = "tag"; }, "ruleset_not_enforced"],
  ["always bypass", (detail) => { detail.bypass_actors = [{ actor_type: "User", bypass_mode: "always" }]; }, "ruleset_allows_bypass"],
  ["PR-only bypass", (detail) => { detail.bypass_actors = [{ actor_type: "Team", bypass_mode: "pull_request" }]; }, "ruleset_allows_bypass"],
  ["hidden bypass configuration", (detail) => { delete detail.bypass_actors; }, "ruleset_visibility_incomplete"],
  ["wrong ruleset source", (detail) => { detail.source = "other/repository"; }, "ruleset_visibility_incomplete"],
  ["rules changed between API snapshots", (detail) => { detail.rules = []; }, "ruleset_snapshot_mismatch"]
]) {
  test(`${name} cannot be counted as enforced protection`, () => {
    const data = fixture(); change(data.rulesets[0]);
    const result = evaluateProtection(policy, data);
    assert.equal(result.passed, false);
    assert.equal(result.status, ["ruleset_visibility_incomplete", "ruleset_snapshot_mismatch"].includes(reason)
      ? "unverified" : "noncompliant");
    assert.ok(result.excluded_rules.every((rule) => rule.reason === reason));
  });
}

test("a listed ruleset that does not apply to the branch provides no protection", () => {
  const data = fixture(); data.effectiveRules = [];
  assert.equal(evaluateProtection(policy, data).passed, false);
});

test("each required context is checked instead of accepting any green-check policy", () => {
  for (const missing of policy.required_checks) {
    const data = fixture();
    for (const rules of [data.effectiveRules, data.rulesets[0].rules]) {
      rules.at(-1).parameters.required_status_checks = policy.required_checks.filter((name) => name !== missing)
        .map((context) => ({ context }));
    }
    assert.deepEqual(evaluateProtection(policy, data).missing_checks, [missing]);
  }
});

test("checks without an up-to-date-branch requirement do not pass", () => {
  const data = fixture();
  for (const rules of [data.effectiveRules, data.rulesets[0].rules]) {
    rules.at(-1).parameters.strict_required_status_checks_policy = false;
  }
  assert.ok(evaluateProtection(policy, data).failures.includes("up_to_date_branch_not_required"));
});

test("multiple non-bypass rulesets combine without requiring the same ruleset ID", () => {
  const data = fixture();
  const other = structuredClone(data.rulesets[0]); other.id = 8; other.rules = other.rules.splice(3);
  data.rulesets[0].rules.pop(); data.rulesets.push(other); data.effectiveRules.at(-1).ruleset_id = 8;
  assert.equal(evaluateProtection(policy, data).passed, true);
});

test("an additional bypassable ruleset cannot weaken complete non-bypass coverage", () => {
  const data = fixture();
  const other = structuredClone(data.rulesets[0]); other.id = 9; other.bypass_actors = [{}];
  data.rulesets.push(other); data.effectiveRules.push({ ...data.effectiveRules[0], ruleset_id: 9 });
  const result = evaluateProtection(policy, data);
  assert.equal(result.passed, true); assert.equal(result.excluded_rules.length, 1);
});

test("legacy protection works when it includes administrators and all required rules", () => {
  const result = evaluateProtection(policy, { effectiveRules: [], rulesets: [], legacy: legacy() });
  assert.equal(result.passed, true); assert.equal(result.legacy_admin_enforcement, true);
});

test("legacy admin bypass is not mistaken for enforcement", () => {
  const old = legacy(); old.enforce_admins.enabled = false;
  assert.equal(evaluateProtection(policy, { effectiveRules: [], rulesets: [], legacy: old }).passed, false);
});

test("an unreadable legacy administrator setting is unverified, not inferred", () => {
  for (const value of [{}, { enforce_admins: {} }, { enforce_admins: { enabled: "false" } }]) {
    assert.throws(() => evaluateProtection(policy, { effectiveRules: [], rulesets: [], legacy: value }),
      /invalid_legacy_protection_evidence/);
  }
});

test("complete protection remains verified when unrelated evidence is unavailable", () => {
  const data = fixture();
  data.effectiveRules.push({ type: "deletion", ruleset_id: 99 });
  const result = evaluateProtection(policy, data);
  assert.equal(result.passed, true);
  assert.equal(result.status, "verified");
  assert.equal(result.excluded_rules[0].reason, "ruleset_visibility_incomplete");
});

test("legacy force-push and deletion exceptions are reported independently", () => {
  const old = legacy(); old.allow_force_pushes.enabled = true; old.allow_deletions.enabled = true;
  const result = evaluateProtection(policy, { effectiveRules: [], rulesets: [], legacy: old });
  assert.equal(result.passed, false); assert.equal(result.failures.length, 2);
});

test("legacy PR bypass is not hidden by a zero review count", () => {
  const old = legacy(); old.required_pull_request_reviews.bypass_pull_request_allowances = { users: [{}], teams: [], apps: [] };
  assert.equal(evaluateProtection(policy, { effectiveRules: [], rulesets: [], legacy: old }).pull_request_required, false);
});

test("legacy API checks array is supported without legacy contexts", () => {
  const old = legacy(); old.required_status_checks.checks = policy.required_checks.map((context) => ({ context, app_id: 15368 }));
  delete old.required_status_checks.contexts;
  assert.equal(evaluateProtection(policy, { effectiveRules: [], rulesets: [], legacy: old }).passed, true);
});

test("invalid target and empty or duplicated requirements are rejected", () => {
  for (const override of [{ repository: "../repo" }, { branch: "*" }, { branch: "-X POST" },
    { required_checks: [] }, { required_checks: [" "] }, { required_checks: ["a", "a"] }]) {
    assert.throws(() => validateProtectionPolicy({ ...policy, ...override }), /invalid_protection_policy/);
  }
});

test("GitHub reader is GET-only, paginated, bounded and never invokes a shell", () => {
  const result = readGithubJson("repos/owner/repo/rules/branches/main", { paged: true }, (command, args, options) => {
    assert.equal(command, "gh"); assert.deepEqual(args.slice(0, 3), ["api", "--method", "GET"]);
    assert.ok(args.includes("--paginate")); assert.ok(args.includes("--slurp"));
    assert.equal(options.shell, false); assert.equal(options.windowsHide, true); assert.ok(options.timeout > 0);
    return { status: 0, stdout: JSON.stringify([[{ type: "a" }], [{ type: "b" }]]) };
  });
  assert.deepEqual(result, [{ type: "a" }, { type: "b" }]);
});

test("only the explicit unprotected-branch response is optional, not denied or unknown reads", () => {
  const read = (body, options = {}) => readGithubJson("endpoint", options, () => ({ status: 1, stdout: JSON.stringify(body), stderr: "secret-account-detail" }));
  assert.equal(read({ status: "404", message: "Branch not protected" }, { allowUnprotected: true }), null);
  for (const body of [{ status: "403", message: "Forbidden" }, { status: "404", message: "Not Found" }]) {
    assert.throws(() => read(body, { allowUnprotected: true }), /^Error: github_read_unavailable$/);
  }
  assert.throws(() => read({ status: "404", message: "Branch not protected" }), /github_read_unavailable/);
});

test("malformed empty and truncated paged responses cannot become a pass", () => {
  for (const stdout of ["not-json", "null", "{}", "[{}]"]) {
    assert.throws(() => readGithubJson("endpoint", { paged: true }, () => ({ status: 0, stdout })), /github_/);
  }
});

test("live loader uses effective rules and fetches each ruleset only once", () => {
  const data = fixture(); const seen = [];
  const result = auditRepositoryProtection(policy, (endpoint, options) => {
    seen.push(endpoint);
    if (endpoint.endsWith("/protection")) { assert.equal(options.allowUnprotected, true); return null; }
    if (endpoint.includes("/rules/branches/")) { assert.equal(options.paged, true); return data.effectiveRules; }
    if (endpoint.endsWith("/rulesets/7")) return data.rulesets[0];
    return { name: policy.branch, commit: { sha } };
  });
  assert.equal(result.passed, true); assert.equal(result.head_sha, sha);
  assert.equal(seen.length, 4);
});

test("organization rules are inspected through their typed organization source", () => {
  const data = fixture();
  data.rulesets[0].source_type = "Organization"; data.rulesets[0].source = "galaxyssi";
  for (const rule of data.effectiveRules) { rule.ruleset_source_type = "Organization"; rule.ruleset_source = "galaxyssi"; }
  const result = auditRepositoryProtection(policy, (endpoint) => {
    if (endpoint.endsWith("/protection")) return null;
    if (endpoint.includes("/rules/branches/")) return data.effectiveRules;
    if (endpoint.includes("/rulesets/")) { assert.equal(endpoint, "orgs/galaxyssi/rulesets/7"); return data.rulesets[0]; }
    return { name: policy.branch, commit: { sha } };
  });
  assert.equal(result.passed, true);
});

test("a wrong branch or untrusted ruleset source is unverified, not inferred", () => {
  assert.throws(() => auditRepositoryProtection(policy, () => ({ name: "another", commit: { sha } })), /identity_mismatch/);
  assert.throws(() => auditRepositoryProtection(policy, (endpoint) => endpoint.includes("/rules/branches/") ?
    [{ ruleset_id: 7, ruleset_source_type: "Repository", ruleset_source: "attacker/repository" }] :
    { name: policy.branch, commit: { sha } }), /unsupported_ruleset_source/);
});

test("CLI unavailable evidence exits nonzero and writes an explicitly unverified report", () => {
  const temporary = fs.mkdtempSync(path.join(os.tmpdir(), "galaxyssi-protection-"));
  try {
    const report = path.join(temporary, "report.json");
    const result = spawnSync(process.execPath, [path.join(root, "tools/security/audit-branch-protection.mjs"),
      "--policy", path.join(temporary, "missing.json"), "--report", report], { encoding: "utf8", shell: false });
    assert.equal(result.status, 2);
    const value = JSON.parse(fs.readFileSync(report, "utf8"));
    assert.equal(value.passed, false); assert.equal(value.status, "unverified");
    assert.equal(value.reason, "audit_unavailable");
  } finally {
    assert.equal(path.dirname(temporary), path.resolve(os.tmpdir()));
    fs.rmSync(temporary, { recursive: true });
  }
});
