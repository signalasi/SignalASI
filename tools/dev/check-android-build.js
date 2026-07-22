#!/usr/bin/env node
"use strict";

const { spawnSync } = require("node:child_process");
const fs = require("node:fs");
const path = require("node:path");

const root = path.resolve(__dirname, "..", "..");
const androidDir = path.join(root, "apps", "android");
const isWindows = process.platform === "win32";
const gradle = path.join(androidDir, isWindows ? "gradlew.bat" : "gradlew");
const command = isWindows ? "cmd.exe" : gradle;
const args = isWindows
  ? ["/c", gradle, ":app:testDebugUnitTest", ":app:assembleDebug", "--no-daemon"]
  : [":app:testDebugUnitTest", ":app:assembleDebug", "--no-daemon"];
const env = { ...process.env };

function setIfMissing(name, value) {
  if (!env[name] && value && fs.existsSync(value)) {
    env[name] = value;
  }
}

if (isWindows) {
  setIfMissing("ANDROID_HOME", path.join(env.LOCALAPPDATA || "", "Android", "Sdk"));
  setIfMissing("ANDROID_SDK_ROOT", env.ANDROID_HOME);
  setIfMissing("JAVA_HOME", path.join("C:", "Program Files", "Android", "Android Studio", "jbr"));
}

const result = spawnSync(command, args, {
  cwd: androidDir,
  env,
  stdio: "inherit",
  shell: false
});

if (result.error) {
  console.error(`Android build failed to start: ${result.error.message}`);
  process.exit(1);
}

process.exit(result.status || 0);
