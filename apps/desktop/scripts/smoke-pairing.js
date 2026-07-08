const { execFileSync, spawn } = require("node:child_process");
const fs = require("node:fs");
const os = require("node:os");
const path = require("node:path");
const { withSignalasiLock } = require("./smoke-lock");

const root = path.resolve(__dirname, "..");
const workspaceRoot = path.resolve(root, "..");
const backendDir = path.join(root, "core", "signalasi-link", "backend");
const backendOrigin = "http://127.0.0.1:8765";
const statePath = path.join(backendDir, "signalasi_pairing_state.json");

function log(message) {
  console.log(`[pairing-smoke] ${message}`);
}

function findPython() {
  const candidates = [
    path.join(root, ".runtime-python", "venv", "Scripts", "python.exe"),
    path.join(os.homedir(), "AppData", "Local", "hermes", "hermes-agent", "venv", "Scripts", "python.exe"),
    path.join(os.homedir(), "AppData", "Roaming", "uv", "python", "cpython-3.11-windows-x86_64-none", "python.exe"),
    "python"
  ];
  return candidates.find((candidate) => candidate === "python" || fs.existsSync(candidate)) || "python";
}

async function fetchJson(pathname) {
  const response = await fetch(`${backendOrigin}${pathname}`);
  if (!response.ok) throw new Error(`${pathname} returned HTTP ${response.status}`);
  return response.json();
}

async function postJson(pathname) {
  const response = await fetch(`${backendOrigin}${pathname}`, {
    method: "POST",
    headers: { "Content-Type": "application/json" }
  });
  if (!response.ok) throw new Error(`${pathname} returned HTTP ${response.status}`);
  return response.json();
}

async function fetchText(pathname) {
  const response = await fetch(`${backendOrigin}${pathname}`);
  if (!response.ok) throw new Error(`${pathname} returned HTTP ${response.status}`);
  return response.text();
}

async function waitForBackend() {
  let lastError;
  for (let attempt = 0; attempt < 24; attempt += 1) {
    try {
      return await fetchJson("/api/pairing/status");
    } catch (error) {
      lastError = error;
      await new Promise((resolve) => setTimeout(resolve, 500));
    }
  }
  throw lastError || new Error("Backend did not respond");
}

async function startBackendIfNeeded() {
  try {
    await fetchJson("/api/pairing/status");
    return undefined;
  } catch {
    // Start below.
  }
  const child = spawn(findPython(), ["-m", "uvicorn", "main:app", "--host", "0.0.0.0", "--port", "8765"], {
    cwd: backendDir,
    windowsHide: true,
    stdio: "ignore"
  });
  await waitForBackend();
  return child;
}

function restoreState(original) {
  if (original === undefined) {
    fs.rmSync(statePath, { force: true });
  } else {
    fs.writeFileSync(statePath, original, "utf8");
  }
}

async function main() {
  log("starting or reusing backend with pairing status API");
  const originalState = fs.existsSync(statePath) ? fs.readFileSync(statePath, "utf8") : undefined;
  const startedBackend = await startBackendIfNeeded();
  try {
    const initial = await fetchJson("/api/pairing/status");
    if (!("paired" in initial) || !("token" in initial)) {
      throw new Error(`Pairing status shape is invalid: ${JSON.stringify(initial)}`);
    }

    log("generating QR token through /signalasi/verify");
    const html = await fetchText("/signalasi/verify");
    if (!html.includes("SignalASI Secure Pairing")) {
      throw new Error("Pairing QR page did not render expected title");
    }
    if (!html.includes('data-pairing-type="signalasi_verify"')) {
      throw new Error("Pairing QR page did not expose signalasi_verify payload type");
    }
    if (!html.includes('data-pairing-route="/signalasi/verify"')) {
      throw new Error("Pairing QR page did not expose /signalasi/verify route");
    }
    if (!/data-agent-count="[1-9][0-9]*"/.test(html)) {
      throw new Error("Pairing QR page did not expose connector agent count");
    }
    if (html.includes("hermes_signal_verify") || html.includes("/signal/verify")) {
      throw new Error("Pairing QR page leaked an old Hermes pairing protocol name");
    }
    const payload = await fetchJson("/api/pairing/payload");
    if (!Array.isArray(payload.connector_agents) || payload.connector_agents.length < 2) {
      throw new Error(`Pairing payload did not include connector agents: ${JSON.stringify(payload)}`);
    }
    for (const requiredAgentId of ["hermes", "codex"]) {
      if (!payload.connector_agents.some((agent) => agent.agent_id === requiredAgentId || String(agent.id || "").endsWith(`:${requiredAgentId}`))) {
        throw new Error(`Pairing payload missing connector agent ${requiredAgentId}: ${JSON.stringify(payload.connector_agents)}`);
      }
    }
    const waiting = await fetchJson("/api/pairing/status");
    if (!waiting.token || waiting.token.active !== true || waiting.state === "not_paired") {
      throw new Error(`Pairing token was not active after QR render: ${JSON.stringify(waiting)}`);
    }

    log("recording temporary paired phone state");
    execFileSync(findPython(), [
      "-c",
      "from pairing_state import record_pairing_success; record_pairing_success('PAIRING_SMOKE_FINGERPRINT_1234567890', 'android-smoke', 7)"
    ], { cwd: backendDir, windowsHide: true });
    const paired = await fetchJson("/api/pairing/status");
    if (!paired.paired || paired.state !== "paired" || paired.identity_fingerprint_short !== "PAIRING_SMOKE_FI") {
      throw new Error(`Pairing status did not report temporary paired phone: ${JSON.stringify(paired)}`);
    }

    log("clearing temporary paired phone through API");
    const cleared = await postJson("/api/pairing/clear");
    if (cleared.paired || !["waiting_for_scan", "not_paired"].includes(cleared.state)) {
      throw new Error(`Pairing clear did not remove paired state: ${JSON.stringify(cleared)}`);
    }
    if (!cleared.revoke || !("ok" in cleared.revoke) || cleared.revoke.reason !== "forgotten_by_desktop") {
      throw new Error(`Pairing clear did not report revocation attempt: ${JSON.stringify(cleared)}`);
    }

    log("checking MQTT gate rejects mobile diagnostics after clear");
    const gateCheck = execFileSync(findPython(), [
      "-c",
      [
        "import json",
        "import mqtt_bridge",
        "print(json.dumps(mqtt_bridge.publish_mobile_test_message('hermes', 'after clear'), ensure_ascii=False))"
      ].join("; ")
    ], { cwd: backendDir, windowsHide: true, encoding: "utf8" }).trim();
    const gate = JSON.parse(gateCheck);
    if (gate.ok !== false || gate.code !== "phone_not_paired" || !gate.params || gate.params.route !== "/signalasi/verify" || !String(gate.error || "").includes("Phone is not paired")) {
      throw new Error(`MQTT gate did not reject unpaired mobile diagnostic: ${gateCheck}`);
    }
    log("pairing status smoke OK");
  } finally {
    restoreState(originalState);
    if (startedBackend) startedBackend.kill();
  }
}

withSignalasiLock("smoke:pairing", main).catch((error) => {
  console.error(`[pairing-smoke] failed: ${error.stack || error.message || error}`);
  process.exit(1);
});
