#!/usr/bin/env node
"use strict";

const { spawnSync } = require("node:child_process");
const https = require("node:https");
const path = require("node:path");

const root = path.resolve(__dirname, "..", "..");
const repo = "signalasi/SignalASI";
const strict = process.argv.includes("--strict");
const requiredWorkflows = ["Repository Guard", "Windows Package"];

const requiredLocalGates = [
  "npm run check",
  "npm run check:android",
  "npm run smoke:desktop",
  "npm run smoke:desktop:pairing",
  "npm run smoke:desktop:agent-push",
  "npm run smoke:desktop:voice-stt",
  "npm run smoke:desktop:ui",
  "npm run smoke:desktop:e2e",
  "npm run package:desktop:win",
  "npm run smoke:desktop:packaged"
];

const localGateBundle = [
  "npm run test:release:local"
];

const deviceGates = [
  "npm run smoke:android:ui",
  "npm run smoke:android:friends",
  "npm run smoke:android:contact-rename",
  "npm run smoke:android:contact-tags",
  "npm run smoke:android:cloud-models",
  "npm run smoke:android:background",
  "npm run smoke:android:agent-replies",
  "npm run smoke:android:voice-reply",
  "npm run smoke:android:voice-settings",
  "npm run smoke:android:backup",
  "npm run smoke:android:reset"
];

const deviceGateBundle = [
  "npm run test:release:device"
];

const networkGates = [
  "npm run smoke:desktop:mqtt-persistence"
];

const manualChecks = [
  "Pair a fresh Android install with a fresh Desktop install using /signalasi/verify. Automated evidence: npm run smoke:desktop:pairing and npm run smoke:android:ui.",
  "Send text messages from Android to Hermes and Codex and confirm live Agent replies arrive on the phone. Automated display evidence: npm run smoke:android:agent-replies.",
  "Send a voice message to Hermes and confirm Desktop STT is used when configured. Automated STT evidence: npm run smoke:desktop:voice-stt. Automated reply-panel evidence: npm run smoke:android:voice-reply.",
  "Exercise the Voice page wake loop on a real microphone and confirm replies are preserved in the voice response panel. Automated preservation evidence: npm run smoke:android:voice-reply.",
  "Clear Android app data and confirm a new identity fingerprint, empty contacts, and a new welcome notification are created. Automated evidence: npm run smoke:android:reset.",
  "Verify exported APK, Desktop EXE, local databases, logs, screenshots, pairing state, tokens, and node_modules are not staged for Git."
];

function runGit(args) {
  const result = spawnSync("git", args, {
    cwd: root,
    encoding: "utf8",
    shell: false
  });
  if (result.status !== 0) {
    return "";
  }
  return result.stdout.trim();
}

function requestJson(url) {
  return new Promise((resolve, reject) => {
    const req = https.request(url, {
      headers: {
        "User-Agent": "SignalASI release audit",
        "Accept": "application/vnd.github+json"
      }
    }, (res) => {
      let body = "";
      res.setEncoding("utf8");
      res.on("data", (chunk) => { body += chunk; });
      res.on("end", () => {
        if (res.statusCode < 200 || res.statusCode >= 300) {
          reject(new Error(`GitHub API ${res.statusCode}: ${body}`));
          return;
        }
        try {
          resolve(JSON.parse(body));
        } catch (error) {
          reject(error);
        }
      });
    });
    req.on("error", reject);
    req.setTimeout(15000, () => req.destroy(new Error("GitHub API timeout")));
    req.end();
  });
}

function section(title) {
  console.log(`\n## ${title}`);
}

function list(items) {
  for (const item of items) {
    console.log(`- ${item}`);
  }
}

async function latestWorkflowRuns() {
  const url = `https://api.github.com/repos/${repo}/actions/runs?per_page=10`;
  const data = await requestJson(url);
  const byName = new Map();
  for (const run of data.workflow_runs || []) {
    if (!byName.has(run.name)) {
      byName.set(run.name, run);
    }
  }
  return [...byName.values()]
    .filter((run) => ["Repository Guard", "Windows Package"].includes(run.name))
    .sort((a, b) => a.name.localeCompare(b.name));
}

async function main() {
  const failures = [];
  const head = runGit(["rev-parse", "--short", "HEAD"]);
  const fullHead = runGit(["rev-parse", "HEAD"]);
  const branch = runGit(["branch", "--show-current"]);
  const status = runGit(["status", "--short"]);

  console.log("# SignalASI Release Audit");
  console.log(`Repository: ${repo}`);
  console.log(`Branch: ${branch || "unknown"}`);
  console.log(`HEAD: ${head || "unknown"}`);
  console.log(`Working tree: ${status ? "dirty" : "clean"}`);
  console.log(`Strict mode: ${strict ? "enabled" : "disabled"}`);
  if (strict && status) {
    failures.push("Working tree must be clean.");
  }

  section("Required Local Gates");
  list(requiredLocalGates);

  section("Local Gate Bundle");
  list(localGateBundle);

  section("Device Gates");
  list(deviceGates);

  section("Device Gate Bundle");
  list(deviceGateBundle);

  section("Network Gate");
  list(networkGates);

  section("Manual Release Checks");
  list(manualChecks);

  section("GitHub Actions");
  try {
    const runs = await latestWorkflowRuns();
    if (runs.length === 0) {
      console.log("- No Repository Guard or Windows Package runs found.");
    } else {
      for (const run of runs) {
        console.log(`- ${run.name}: ${run.status}/${run.conclusion || "pending"} (${run.head_sha.slice(0, 7)}) ${run.html_url}`);
      }
      if (strict) {
        for (const workflowName of requiredWorkflows) {
          const run = runs.find((item) => item.name === workflowName);
          if (!run) {
            failures.push(`${workflowName} has no visible run.`);
          } else if (run.head_sha !== fullHead) {
            failures.push(`${workflowName} latest run is for ${run.head_sha.slice(0, 7)}, not current HEAD ${head}.`);
          } else if (run.status !== "completed" || run.conclusion !== "success") {
            failures.push(`${workflowName} must be completed/success for current HEAD; got ${run.status}/${run.conclusion || "pending"}.`);
          }
        }
      }
    }
  } catch (error) {
    console.log(`- Unable to read GitHub Actions: ${error.message}`);
    if (strict) {
      failures.push(`Unable to read GitHub Actions: ${error.message}`);
    }
  }

  if (status) {
    section("Uncommitted Changes");
    console.log(status);
  }

  if (strict && failures.length > 0) {
    section("Strict Audit Failures");
    list(failures);
    process.exit(1);
  }
}

main().catch((error) => {
  console.error(error.stack || error.message || error);
  process.exit(1);
});
