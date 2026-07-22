#!/usr/bin/env node
"use strict";

const { spawn, spawnSync } = require("node:child_process");
const path = require("node:path");

const root = path.resolve(__dirname, "..", "..");
const adb = process.env.ADB || "adb";
const applicationId = "com.signalasi.chat";
const runner = `${applicationId}.test/androidx.test.runner.AndroidJUnitRunner`;
const testClass = `${applicationId}.AgentCrossResourceDeviceTest`;
const reportPath = `/sdcard/Android/data/${applicationId}/files/cross-resource-tests/results.json`;
const homeAssistantPort = Number(process.env.SIGNALASI_HA_ACCEPTANCE_PORT || "18123");

function run(args, options = {}) {
  const result = spawnSync(adb, args, {
    cwd: root,
    encoding: "utf8",
    windowsHide: true,
    timeout: 420_000,
    ...options,
  });
  const output = `${result.stdout || ""}${result.stderr || ""}`.trim();
  if (output) process.stdout.write(`${output}\n`);
  if (result.error) throw result.error;
  if (result.status !== 0) {
    throw new Error(`adb ${args.join(" ")} failed with exit code ${result.status}`);
  }
  return output;
}

function startHomeAssistantServer() {
  const serverPath = path.join(__dirname, "fixtures", "home-assistant-acceptance-server.js");
  const child = spawn(process.execPath, [serverPath], {
    cwd: root,
    windowsHide: true,
    env: { ...process.env, SIGNALASI_HA_ACCEPTANCE_PORT: String(homeAssistantPort) },
    stdio: ["ignore", "pipe", "pipe"],
  });
  return new Promise((resolve, reject) => {
    const timer = setTimeout(() => reject(new Error("Home Assistant acceptance server did not start")), 10_000);
    let stderr = "";
    child.stderr.on("data", (chunk) => { stderr += chunk.toString(); });
    child.once("error", (error) => {
      clearTimeout(timer);
      reject(error);
    });
    child.once("exit", (code) => {
      if (code !== null && code !== 0) {
        clearTimeout(timer);
        reject(new Error(`Home Assistant acceptance server exited ${code}: ${stderr}`));
      }
    });
    child.stdout.on("data", (chunk) => {
      const text = chunk.toString();
      process.stdout.write(text);
      if (text.includes("READY")) {
        clearTimeout(timer);
        resolve(child);
      }
    });
  });
}

function stopChild(child) {
  if (!child || child.exitCode !== null) return;
  child.kill("SIGTERM");
}

function sleep(milliseconds) {
  return new Promise((resolve) => setTimeout(resolve, milliseconds));
}

async function fetchJson(url) {
  const response = await fetch(url, { signal: AbortSignal.timeout(10_000) });
  if (!response.ok) {
    throw new Error(`${url} returned HTTP ${response.status}`);
  }
  return response.json();
}

async function establishFreshSecurePairing() {
  const statusBefore = await fetchJson("http://127.0.0.1:8765/api/pairing/status");
  const previousRoute = statusBefore.clients?.[0]?.client_route_id || "";
  const payload = await fetchJson("http://127.0.0.1:8765/api/pairing/payload");
  const encoded = Buffer.from(JSON.stringify(payload), "utf8").toString("base64");
  run(["shell", "am", "force-stop", applicationId]);
  run([
    "shell",
    "am",
    "start",
    "-n",
    `${applicationId}/.MainActivity`,
    "--es",
    "signalasi_debug_scan_payload_b64",
    encoded,
    "--ez",
    "signalasi_debug_auto_confirm_scan",
    "true",
  ]);
  const deadline = Date.now() + 30_000;
  while (Date.now() < deadline) {
    await sleep(500);
    const status = await fetchJson("http://127.0.0.1:8765/api/pairing/status");
    const current = status.clients?.find((client) => client.paired && !client.revoked);
    if (current && current.client_route_id && current.client_route_id !== previousRoute) {
      await sleep(3_000);
      process.stdout.write(`Fresh secure pairing established for route ${current.client_route_id.slice(-8)}.\n`);
      return;
    }
  }
  throw new Error("Fresh SignalASI pairing did not complete");
}

function relaunchApplication() {
  run(["shell", "am", "force-stop", applicationId]);
  run(["shell", "input", "keyevent", "224"]);
  run(["shell", "wm", "dismiss-keyguard"]);
  run(["shell", "am", "start", "-n", `${applicationId}/.MainActivity`]);
}

function verifyReport(report) {
  const checks = [
    report.passed === true,
    report.notification?.read_succeeded === true,
    report.notification?.reply_dispatched === true,
    report.notification?.remote_input_received === true,
    report.notification?.stale_target_rejected === true,
    report.home_assistant?.connection_succeeded === true,
    report.home_assistant?.entity_list_succeeded === true,
    report.home_assistant?.service_call_succeeded === true,
    report.home_assistant?.post_action_state === "on",
    report.desktop?.secure_link === true,
    report.desktop?.windows_status === true,
    report.desktop?.process_inventory === true,
    report.desktop?.workspace_write_read_hash === true,
    report.desktop?.terminal_execution === true,
    report.desktop?.office_inspection === true,
    report.desktop?.office_conversion === true,
    report.desktop?.remote_verification === true,
  ];
  if (checks.some((value) => !value)) {
    throw new Error(`Unexpected cross-resource acceptance report: ${JSON.stringify(report)}`);
  }
}

async function main() {
  let server;
  let failure;
  try {
    const devices = run(["devices"]);
    if (!/^\S+\s+device$/m.test(devices)) {
      throw new Error("No authorized Android device is connected");
    }
    server = await startHomeAssistantServer();
    run(["reverse", `tcp:${homeAssistantPort}`, `tcp:${homeAssistantPort}`]);
    run(["shell", "pm", "grant", applicationId, "android.permission.POST_NOTIFICATIONS"]);
    run([
      "shell",
      "cmd",
      "notification",
      "allow_listener",
      `${applicationId}/.SignalASINotificationListenerService`,
    ]);
    await establishFreshSecurePairing();
    run(["shell", "rm", "-f", reportPath]);
    run(["shell", "am", "force-stop", applicationId]);
    const output = run([
      "shell",
      "am",
      "instrument",
      "-w",
      "-r",
      "-e",
      "signalasi_home_assistant_port",
      String(homeAssistantPort),
      "-e",
      "class",
      testClass,
      runner,
    ]);
    if (!/OK \(1 test\)/.test(output)) {
      throw new Error("Android cross-resource instrumentation did not pass");
    }
    const reportText = run(["exec-out", "cat", reportPath]);
    const report = JSON.parse(reportText);
    verifyReport(report);
    process.stdout.write("Android cross-resource acceptance passed.\n");
  } catch (error) {
    failure = error;
  } finally {
    try {
      run(["reverse", "--remove", `tcp:${homeAssistantPort}`]);
    } catch (error) {
      if (!failure) failure = error;
    }
    stopChild(server);
    try {
      relaunchApplication();
    } catch (error) {
      if (!failure) failure = error;
    }
  }
  if (failure) throw failure;
}

main().catch((error) => {
  process.stderr.write(`${error.stack || error.message || error}\n`);
  process.exitCode = 1;
});
