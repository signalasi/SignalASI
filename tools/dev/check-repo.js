#!/usr/bin/env node
"use strict";

const { spawnSync } = require("node:child_process");
const fs = require("node:fs");
const path = require("node:path");

const root = path.resolve(__dirname, "..", "..");
const testingMatrix = path.join(root, "docs", "testing", "README.md");

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

const checks = [
  {
    name: "testing matrix",
    run: checkTestingMatrix
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
