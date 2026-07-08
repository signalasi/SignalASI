#!/usr/bin/env node
"use strict";

const { spawnSync } = require("node:child_process");
const fs = require("node:fs");
const path = require("node:path");

const root = path.resolve(__dirname, "..", "..");
const testingMatrix = path.join(root, "docs", "testing", "README.md");

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
