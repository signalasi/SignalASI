#!/usr/bin/env node
"use strict";

const { spawnSync } = require("node:child_process");
const path = require("node:path");

const root = path.resolve(__dirname, "..", "..");
const isWindows = process.platform === "win32";
const npmCommand = isWindows ? (process.env.ComSpec || "cmd.exe") : "npm";
const gates = [
  "smoke:android:ui",
  "smoke:android:friends",
  "smoke:android:background",
  "smoke:android:reset"
];

function runGate(scriptName) {
  console.log(`\n[release-device] npm run ${scriptName}`);
  const startedAt = Date.now();
  const args = isWindows ? ["/d", "/c", "npm", "run", scriptName] : ["run", scriptName];
  const result = spawnSync(npmCommand, args, {
    cwd: root,
    stdio: "inherit",
    shell: false,
    windowsHide: true
  });
  const seconds = ((Date.now() - startedAt) / 1000).toFixed(1);
  if (result.error) {
    console.error(`[release-device] ${scriptName} failed to start after ${seconds}s: ${result.error.message}`);
    process.exit(1);
  }
  if (result.status !== 0) {
    console.error(`[release-device] ${scriptName} failed after ${seconds}s`);
    process.exit(result.status || 1);
  }
  console.log(`[release-device] ${scriptName} passed in ${seconds}s`);
}

if (process.argv.includes("--list")) {
  for (const gate of gates) {
    console.log(`npm run ${gate}`);
  }
  process.exit(0);
}

console.log("[release-device] Running Android device release gates sequentially.");
console.log("[release-device] A connected unlocked phone or emulator with adb access is required.");
for (const gate of gates) {
  runGate(gate);
}
console.log("\n[release-device] Android device release gates passed.");
