#!/usr/bin/env node
"use strict";

const { spawnSync } = require("node:child_process");
const fs = require("node:fs");
const path = require("node:path");

const root = path.resolve(__dirname, "..", "..");
const testingMatrix = path.join(root, "docs", "testing", "README.md");
const productRequirements = path.join(root, "docs", "product", "PRODUCT_REQUIREMENTS.md");
const trustModel = path.join(root, "docs", "security", "TRUST_MODEL.md");
const windowsPackageWorkflow = path.join(root, ".github", "workflows", "windows-package.yml");
const releaseAuditDoc = path.join(root, "docs", "testing", "RELEASE_AUDIT.md");
const releaseAuditScript = path.join(root, "tools", "dev", "release-audit.js");

function listTrackedFiles() {
  const result = spawnSync("git", ["ls-files", "-z"], {
    cwd: root,
    encoding: "utf8",
    shell: false
  });

  if (result.error) {
    throw new Error(`Unable to list tracked files: ${result.error.message}`);
  }

  if (result.status !== 0) {
    throw new Error(`Unable to list tracked files: ${result.stderr || result.stdout}`);
  }

  return result.stdout.split("\0").filter(Boolean).map((file) => file.replace(/\\/g, "/"));
}

function checkNoTrackedGeneratedArtifacts() {
  const blocked = [
    { pattern: /^apps\/android\/signalasi-.*\.xml$/, reason: "Android UI dump" },
    { pattern: /^apps\/android\/.*\.(apk|aab|jks|keystore)$/i, reason: "Android package or signing artifact" },
    { pattern: /^apps\/desktop\/(dist|out|release|ui-smoke)\//, reason: "Desktop generated package or smoke artifact" },
    { pattern: /^apps\/desktop\/node_modules\//, reason: "Desktop dependency directory" },
    { pattern: /(^|\/)node_modules\//, reason: "Node dependency directory" },
    { pattern: /\.(exe|msi|dmg|AppImage|deb|rpm)$/i, reason: "packaged installer" },
    { pattern: /\.(db|sqlite|log|jsonl)$/i, reason: "local runtime data" },
    { pattern: /(^|\/)(signalasi_pairing_state\.json|signalasi_agents\.json|signalasi_push_token\.txt|signalasi_chat\.db)$/i, reason: "local identity or pairing state" },
    { pattern: /(^|\/)(uploads|downloads)\//, reason: "local file transfer data" }
  ];

  const offenders = [];
  for (const file of listTrackedFiles()) {
    const match = blocked.find((entry) => entry.pattern.test(file));
    if (match) {
      offenders.push(`${file} (${match.reason})`);
    }
  }

  if (offenders.length > 0) {
    throw new Error(`Generated or local artifacts are tracked:\n${offenders.join("\n")}`);
  }
}

function checkTestingMatrix() {
  if (!fs.existsSync(testingMatrix)) {
    throw new Error("Missing docs/testing/README.md");
  }

  const content = fs.readFileSync(testingMatrix, "utf8");
  const requiredText = [
    "Testing Matrix",
    "Required Gates",
    "Product Coverage",
    "Manual Release Checks",
    "npm run check",
    "npm run check:android",
    "npm run smoke:android:ui",
    "npm run smoke:android:friends",
    "npm run smoke:android:background",
    "npm run smoke:desktop",
    "npm run smoke:desktop:pairing",
    "npm run smoke:desktop:agent-push",
    "npm run smoke:desktop:mqtt-persistence",
    "npm run smoke:desktop:ui",
    "npm run smoke:desktop:e2e",
    "npm run package:desktop:win",
    "npm run smoke:desktop:packaged"
  ];

  for (const text of requiredText) {
    if (!content.includes(text)) {
      throw new Error(`Testing matrix missing: ${text}`);
    }
  }
}

function checkProductRequirements() {
  if (!fs.existsSync(productRequirements)) {
    throw new Error("Missing docs/product/PRODUCT_REQUIREMENTS.md");
  }

  const content = fs.readFileSync(productRequirements, "utf8");
  const requiredText = [
    "Product Requirements",
    "Product Principles",
    "Android Requirements",
    "Desktop Requirements",
    "Protocol And Security Requirements",
    "Release Requirements",
    "Deferred Scope",
    "Voice page",
    "Cloud models",
    "Agent contacts",
    "Pairing replacement",
    "npm run smoke:desktop:pairing",
    "docs/testing/README.md"
  ];

  for (const text of requiredText) {
    if (!content.includes(text)) {
      throw new Error(`Product requirements missing: ${text}`);
    }
  }
}

function checkTrustModel() {
  if (!fs.existsSync(trustModel)) {
    throw new Error("Missing docs/security/TRUST_MODEL.md");
  }

  const content = fs.readFileSync(trustModel, "utf8");
  const requiredText = [
    "Trust Model",
    "Trust Zones",
    "Identity And Pairing",
    "Message Protection",
    "Broker Boundary",
    "Local Data Boundary",
    "Agent Permission Boundary",
    "Current Security Limits",
    "Required Evidence",
    "/signalasi/verify",
    "signalasi_verify",
    "X-SignalASI-Token"
  ];

  for (const text of requiredText) {
    if (!content.includes(text)) {
      throw new Error(`Trust model missing: ${text}`);
    }
  }
}

function checkProtocolSpec() {
  const spec = path.join(root, "docs", "protocol", "SignalASI-Link-Protocol.md");
  if (!fs.existsSync(spec)) {
    throw new Error("Missing docs/protocol/SignalASI-Link-Protocol.md");
  }

  const content = fs.readFileSync(spec, "utf8");
  const requiredText = [
    "Version: v1.0.3",
    "Transport",
    "Pairing QR Payload",
    "Pairing Claim",
    "Signal Envelope",
    "Delivery Trace",
    "Agent Contact Metadata",
    "Compatibility Rules",
    "signalasichat/android/send",
    "signalasichat/android/recv",
    "signalasichat/android/pc",
    "signalasi_pairing_claim",
    "delivery_ack",
    "connector_agents"
  ];

  for (const text of requiredText) {
    if (!content.includes(text)) {
      throw new Error(`Protocol spec missing: ${text}`);
    }
  }
}

function checkWindowsPackageWorkflow() {
  if (!fs.existsSync(windowsPackageWorkflow)) {
    throw new Error("Missing .github/workflows/windows-package.yml");
  }

  const content = fs.readFileSync(windowsPackageWorkflow, "utf8");
  const requiredText = [
    "Windows Package",
    "runs-on: windows-latest",
    "npm --prefix apps/desktop ci",
    "npm run package:desktop:win",
    "npm run smoke:desktop:packaged",
    "gradle/actions/setup-gradle"
  ];

  for (const text of requiredText) {
    if (!content.includes(text)) {
      throw new Error(`Windows package workflow missing: ${text}`);
    }
  }
}

function checkReleaseAudit() {
  if (!fs.existsSync(releaseAuditDoc)) {
    throw new Error("Missing docs/testing/RELEASE_AUDIT.md");
  }
  if (!fs.existsSync(releaseAuditScript)) {
    throw new Error("Missing tools/dev/release-audit.js");
  }

  const doc = fs.readFileSync(releaseAuditDoc, "utf8");
  const script = fs.readFileSync(releaseAuditScript, "utf8");
  const requiredText = [
    "npm run audit:release",
    "Repository Guard",
    "Windows Package",
    "Manual Release Checks",
    "smoke:desktop:mqtt-persistence",
    "smoke:android:ui",
    "smoke:android:friends",
    "smoke:android:background"
  ];

  for (const text of requiredText) {
    if (!doc.includes(text) && !script.includes(text)) {
      throw new Error(`Release audit missing: ${text}`);
    }
  }
}

const checks = [
  {
    name: "testing matrix",
    run: checkTestingMatrix
  },
  {
    name: "product requirements",
    run: checkProductRequirements
  },
  {
    name: "trust model",
    run: checkTrustModel
  },
  {
    name: "protocol spec",
    run: checkProtocolSpec
  },
  {
    name: "windows package workflow",
    run: checkWindowsPackageWorkflow
  },
  {
    name: "release audit",
    run: checkReleaseAudit
  },
  {
    name: "tracked artifact policy",
    run: checkNoTrackedGeneratedArtifacts
  },
  {
    name: "i18n text policy",
    command: process.execPath,
    args: [path.join(root, "tools", "dev", "check-no-chinese-outside-i18n.js")]
  },
  {
    name: "desktop structure",
    command: process.execPath,
    args: [path.join(root, "apps", "desktop", "scripts", "check.js")]
  }
];

for (const check of checks) {
  console.log(`\n[check] ${check.name}`);
  if (check.run) {
    check.run();
    continue;
  }

  const result = spawnSync(check.command, check.args, {
    cwd: root,
    stdio: "inherit",
    shell: false
  });
  if (result.error) {
    console.error(`[check] ${check.name} failed to start: ${result.error.message}`);
    process.exit(1);
  }

  if (result.status !== 0) {
    console.error(`[check] ${check.name} failed`);
    process.exit(result.status || 1);
  }
}

console.log("\nSignalASI repository checks OK");
