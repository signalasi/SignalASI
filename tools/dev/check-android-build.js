#!/usr/bin/env node
"use strict";

const { spawnSync } = require("node:child_process");
const path = require("node:path");

const root = path.resolve(__dirname, "..", "..");
const androidDir = path.join(root, "apps", "android");
const isWindows = process.platform === "win32";
const gradle = path.join(androidDir, isWindows ? "gradlew.bat" : "gradlew");
const command = isWindows ? "cmd.exe" : gradle;
const args = isWindows
  ? ["/c", gradle, "assembleDebug", "--no-daemon"]
  : ["assembleDebug", "--no-daemon"];

const result = spawnSync(command, args, {
  cwd: androidDir,
  stdio: "inherit",
  shell: false
});

if (result.error) {
  console.error(`Android build failed to start: ${result.error.message}`);
  process.exit(1);
}

process.exit(result.status || 0);
