#!/usr/bin/env node
"use strict";

const { spawnSync } = require("node:child_process");
const path = require("node:path");

const root = path.resolve(__dirname, "..", "..");

const checks = [
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
