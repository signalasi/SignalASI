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
  "smoke:android:contact-rename",
  "smoke:android:contact-tags",
  "smoke:android:language",
  "smoke:android:cloud-models",
  "smoke:android:background",
  "smoke:android:agent-replies",
  "smoke:android:voice-reply",
  "smoke:android:voice-settings",
  "smoke:android:control-center-deep",
  "smoke:android:backup",
  "smoke:android:reset",
  "test:android:agent-lifecycle-ui",
  "test:android:cross-resource",
  "test:android:team-paired-process-death"
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

const fromArgument = process.argv.find((argument) => argument.startsWith("--from="));
const fromGate = fromArgument?.slice("--from=".length).trim();
const fromIndex = fromGate ? gates.indexOf(fromGate) : 0;
if (fromGate && fromIndex < 0) {
  console.error(`[release-device] Unknown --from gate: ${fromGate}`);
  process.exit(2);
}
const selectedGates = gates.slice(fromIndex);

console.log("[release-device] Running Android device release gates sequentially.");
console.log("[release-device] A connected unlocked phone, installed debug and test APKs, and a healthy Desktop backend on 127.0.0.1:8765 are required.");
if (fromGate) console.log(`[release-device] Resuming from npm run ${fromGate}.`);
for (const gate of selectedGates) {
  runGate(gate);
}
console.log("\n[release-device] Android device release gates passed.");
