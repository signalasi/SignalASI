import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { auditRepositoryProtection } from "./branch-protection.mjs";

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..", "..");
let policyPath = path.join(root, ".github", "release-protection-policy.json");
let reportPath;
let result;
try {
  const args = process.argv.slice(2);
  for (let index = 0; index < args.length; index += 2) {
    if (!["--policy", "--report"].includes(args[index]) || !args[index + 1]) throw new Error("invalid_arguments");
    if (args[index] === "--policy") policyPath = path.resolve(args[index + 1]);
    else reportPath = path.resolve(args[index + 1]);
  }
  result = auditRepositoryProtection(JSON.parse(fs.readFileSync(policyPath, "utf8")));
  process.exitCode = result.passed ? 0 : result.status === "unverified" ? 2 : 1;
} catch (error) {
  result = { passed: false, status: "unverified", reason: /^[a-z_]+$/.test(error.message) ? error.message : "audit_unavailable" };
  process.exitCode = 2;
}
const json = JSON.stringify(result, null, 2) + "\n";
if (reportPath) {
  fs.mkdirSync(path.dirname(reportPath), { recursive: true });
  fs.writeFileSync(reportPath, json);
}
process.stdout.write(json);
