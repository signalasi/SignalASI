#!/usr/bin/env node
"use strict";

const { spawnSync } = require("node:child_process");
const path = require("node:path");

const root = path.resolve(__dirname, "..", "..");
const isWindows = process.platform === "win32";
const npmCommand = isWindows ? (process.env.ComSpec || "cmd.exe") : "npm";
const gates = [
  "check",
  "check:android",
  "smoke:desktop",
  "smoke:desktop:pairing",
  "smoke:desktop:agent-push",
  "smoke:desktop:mqtt-persistence",
  "smoke:desktop:ui",
  "smoke:desktop:e2e",
  "package:desktop:win",
  "smoke:desktop:packaged"
];

function runGate(scriptName) {
  console.log(`\n[release-local] npm run ${scriptName}`);
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
    console.error(`[release-local] ${scriptName} failed to start after ${seconds}s: ${result.error.message}`);
    process.exit(1);
  }
  if (result.status !== 0) {
    console.error(`[release-local] ${scriptName} failed after ${seconds}s`);
    process.exit(result.status || 1);
  }
  console.log(`[release-local] ${scriptName} passed in ${seconds}s`);
}

if (process.argv.includes("--list")) {
  for (const gate of gates) {
    console.log(`npm run ${gate}`);
  }
  process.exit(0);
}

console.log("[release-local] Running local release gates sequentially.");
console.log("[release-local] Android device gates remain separate because they require a connected device.");
for (const gate of gates) {
  runGate(gate);
}
console.log("\n[release-local] Local release gates passed.");
