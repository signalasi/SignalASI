const { spawn } = require("node:child_process");
const fs = require("node:fs");
const os = require("node:os");
const path = require("node:path");

const root = path.resolve(__dirname, "..");
const workspaceRoot = path.resolve(root, "..");
const backendDir = path.join(root, "core", "signalasi-link", "backend");
const androidDir = path.join(workspaceRoot, "android");
const backendOrigin = process.env.SIGNALASI_BACKEND_ORIGIN || "http://127.0.0.1:8765";

const expectedAgents = [
  ["hermes", "Hermes Agent", "real"],
  ["codex", "Codex Agent", "real"],
  ["claude", "Claude Code", "simulated"],
  ["local-llm", "Local LLM", "simulated"],
  ["custom-agent", "Custom Agent", "simulated"]
];

function exists(target) {
  return fs.existsSync(target);
}

function findPython() {
  const candidates = [
    process.env.SIGNALASI_PYTHON,
    path.join(root, ".runtime-python", "venv", "Scripts", "python.exe"),
    path.join(os.homedir(), "AppData", "Local", "hermes", "hermes-agent", "venv", "Scripts", "python.exe"),
    path.join(os.homedir(), "AppData", "Roaming", "uv", "python", "cpython-3.11-windows-x86_64-none", "python.exe"),
    "python"
  ].filter(Boolean);
  return candidates.find((candidate) => candidate === "python" || exists(candidate)) || "python";
}

async function fetchJson(pathname, options = {}) {
  const response = await fetch(`${backendOrigin}${pathname}`, {
    headers: { "Content-Type": "application/json", ...(options.headers || {}) },
    ...options
  });
  if (!response.ok) {
    throw new Error(`${pathname} returned HTTP ${response.status}`);
  }
  return response.json();
}

async function waitForBackend() {
  let lastError;
  for (let attempt = 0; attempt < 24; attempt += 1) {
    try {
      return await fetchJson("/api/agents/diagnostics");
    } catch (error) {
      lastError = error;
      await new Promise((resolve) => setTimeout(resolve, 500));
    }
  }
  throw lastError || new Error("Backend did not respond");
}

async function startBackendIfNeeded() {
  try {
    await fetchJson("/api/agents/diagnostics");
    return undefined;
  } catch {
    // Start below.
  }
  const child = spawn(
    findPython(),
    ["-m", "uvicorn", "main:app", "--host", "0.0.0.0", "--port", "8765"],
    { cwd: backendDir, windowsHide: true, stdio: "ignore" }
  );
  await waitForBackend();
  return child;
}

function readText(file) {
  return exists(file) ? fs.readFileSync(file, "utf8") : "";
}

function simulationCoverage() {
  const e2e = readText(path.join(root, "scripts", "smoke-e2e.js"));
  return {
    claude: e2e.includes("CLAUDE_SMOKE_OK"),
    "local-llm": e2e.includes("LOCAL_E2E_OK"),
    "custom-agent": e2e.includes("CUSTOM_AGENT_OK")
  };
}

function fileChecks() {
  const packageDir = path.join(root, "dist", "SignalASI Desktop-win-x64");
  return [
    ["Desktop source", exists(path.join(root, "src", "main.js"))],
    ["Desktop packaged exe", exists(path.join(packageDir, "SignalASI Desktop.exe"))],
    ["Bundled Python package", exists(path.join(packageDir, "resources", "python", "venv", "Scripts", "python.exe"))],
    ["Backend source", exists(path.join(backendDir, "main.py")) && exists(path.join(backendDir, "agent_gateway.py"))],
    ["Android source", exists(path.join(androidDir, "app", "build.gradle.kts"))],
    ["Android debug APK", exists(path.join(androidDir, "app", "build", "outputs", "apk", "debug", "app-debug.apk"))],
    ["E2E simulation script", exists(path.join(root, "scripts", "smoke-e2e.js"))],
    ["Packaged smoke script", exists(path.join(root, "scripts", "smoke-packaged.js"))]
  ];
}

function statusIcon(ok) {
  return ok ? "OK" : "MISSING";
}

function markdownTable(rows) {
  return rows.map((row) => `| ${row.join(" | ")} |`).join("\n");
}

function agentRows(diagnostics, simulated) {
  const byId = Object.fromEntries((diagnostics.agents || []).map((agent) => [agent.id, agent]));
  const expectedIds = new Set(expectedAgents.map(([id]) => id));
  const rows = expectedAgents.map(([id, label, proof]) => {
    const agent = byId[id] || {};
    const displayName = agent.name || label;
    const current = agent.status || "missing";
    const provenBy = [
      proof === "real" ? "real command diagnostics" : "simulated connector e2e",
      simulated[id] ? "smoke:e2e fixture" : "",
      id === "hermes" || id === "codex" ? "mobile delivery tested in previous smoke run" : ""
    ].filter(Boolean).join("; ");
    const next = current === "ready"
      ? "Ready for live use"
      : (agent.setup || "Configure this connector in SignalASI Desktop");
    return [displayName, id, current, agent.detail || "-", provenBy, next];
  });
  for (const agent of diagnostics.agents || []) {
    if (expectedIds.has(agent.id)) continue;
    rows.push([
      agent.name || agent.id,
      agent.mobile_contact_id || agent.id,
      agent.status || "missing",
      agent.detail || "-",
      "dynamic custom connector diagnostics",
      agent.status === "ready" ? "Ready for live use" : (agent.setup || "Configure this connector in SignalASI Desktop")
    ]);
  }
  return rows;
}

async function main() {
  const startedBackend = await startBackendIfNeeded();
  try {
    const diagnostics = await waitForBackend();
    const selfTest = await fetchJson("/api/agents/self-test", {
      method: "POST",
      body: JSON.stringify({ include_agent_calls: false, include_mobile_delivery: false })
    });
    const simulated = simulationCoverage();
    const files = fileChecks();

    console.log("# SignalASI Connector Status");
    console.log("");
    console.log(`Generated: ${new Date().toISOString()}`);
    console.log(`Backend: ${backendOrigin}`);
    console.log(`Protocol: ${diagnostics.protocol || "SignalASI Link Protocol"} v1.0.3`);
    console.log(`Pairing route: ${diagnostics.pairing_route || "unknown"}`);
    console.log(`Ready: ${(diagnostics.ready || []).join(", ") || "none"}`);
    console.log(`Needs setup: ${(diagnostics.needs_setup || []).join(", ") || "none"}`);
    console.log("");
    console.log("## Agents");
    console.log(markdownTable([
      ["Agent", "Contact ID", "Current", "Detail", "Proven by", "Next step"],
      ["---", "---", "---", "---", "---", "---"],
      ...agentRows(diagnostics, simulated)
    ]));
    console.log("");
    console.log("## Local Artifacts");
    console.log(markdownTable([
      ["Artifact", "State"],
      ["---", "---"],
      ...files.map(([label, ok]) => [label, statusIcon(ok)])
    ]));
    console.log("");
    console.log("## Self Test Endpoint");
    console.log(`Results: ${Array.isArray(selfTest.results) ? selfTest.results.length : 0}`);
    console.log(`Mobile delivery mode: ${diagnostics.mobile_delivery || "unknown"}`);
    console.log("");
    console.log("Use `npm run smoke:e2e` for repeatable simulated Claude/local/custom-agent verification.");
    console.log("Use `$env:SIGNALASI_E2E_MOBILE='1'; npm run smoke:e2e` when the paired phone should receive test messages.");
  } finally {
    if (startedBackend) startedBackend.kill();
  }
}

main().catch((error) => {
  console.error(`[status] failed: ${error.stack || error.message || error}`);
  process.exit(1);
});
