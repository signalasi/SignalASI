const { execFileSync, spawn } = require("node:child_process");
const http = require("node:http");
const fs = require("node:fs");
const os = require("node:os");
const path = require("node:path");
const { withSignalasiLock } = require("./smoke-lock");

const root = path.resolve(__dirname, "..");
const workspaceRoot = path.resolve(root, "..");
const backendDir = path.join(root, "core", "signalasi-link", "backend");
let backendPort = 8765;
let backendOrigin = `http://127.0.0.1:${backendPort}`;
const agentConfigPath = path.join(backendDir, "signalasi_agents.json");
const fakeModelPort = 18993;
const fakeModelOrigin = `http://127.0.0.1:${fakeModelPort}`;
const requiredBackendCapabilities = ["model_display_names", "local_model_endpoint_probe", "mobile_cloud_models", "mcp_stdio_wrapper", "multiple_custom_agents", "api_response_codes", "agent_diagnostics_codes"];

function log(message) {
  console.log(`[smoke] ${message}`);
}

function fail(message) {
  throw new Error(message);
}

function run(command, args, options = {}) {
  execFileSync(command, args, {
    stdio: "inherit",
    windowsHide: true,
    ...options
  });
}

function backendHasCapabilities(diagnostics) {
  const capabilities = new Set(diagnostics?.capabilities || []);
  return requiredBackendCapabilities.every((item) => capabilities.has(item));
}

function backendIsCurrentSource(diagnostics) {
  const actual = diagnostics?.backend_dir;
  return typeof actual === "string" && path.resolve(actual).toLowerCase() === path.resolve(backendDir).toLowerCase();
}

function assertStructuredAgentDiagnostics(diagnostics) {
  for (const agent of diagnostics.agents || []) {
    for (const field of ["detail_code", "detail_params", "setup_code", "setup_params", "pairing_code", "pairing_params"]) {
      if (!(field in agent)) {
        fail(`Agent diagnostics missing ${field}: ${JSON.stringify(agent)}`);
      }
    }
    if (!agent.detail_params.agent_id || agent.detail_params.agent_id !== agent.id) {
      fail(`Agent detail_params did not include matching agent_id: ${JSON.stringify(agent)}`);
    }
  }
}

function stopBackendPort(port = backendPort) {
  if (process.platform !== "win32") return;
  execFileSync(
    "powershell",
    [
      "-NoProfile",
      "-Command",
      `$pids=(Get-NetTCPConnection -LocalPort ${port} -State Listen -ErrorAction SilentlyContinue).OwningProcess | Sort-Object -Unique; foreach($p in $pids){Stop-Process -Id $p -Force -ErrorAction SilentlyContinue}`
    ],
    { stdio: "ignore", windowsHide: true }
  );
}

function findPython() {
  const candidates = [
    path.join(os.homedir(), "AppData", "Local", "hermes", "hermes-agent", "venv", "Scripts", "python.exe"),
    path.join(os.homedir(), "AppData", "Roaming", "uv", "python", "cpython-3.11-windows-x86_64-none", "python.exe"),
    "python"
  ];
  return candidates.find((candidate) => candidate === "python" || fs.existsSync(candidate)) || "python";
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

function readConfigSnapshot() {
  return fs.existsSync(agentConfigPath) ? fs.readFileSync(agentConfigPath, "utf8") : undefined;
}

function restoreConfigSnapshot(snapshot) {
  if (snapshot === undefined) {
    fs.rmSync(agentConfigPath, { force: true });
  } else {
    fs.writeFileSync(agentConfigPath, snapshot, "utf8");
  }
}

function assertNoSmokeConfigLeak() {
  const current = readConfigSnapshot() || "";
  for (const marker of ["fake-local", fakeModelOrigin]) {
    if (current.includes(marker)) {
      fail(`Smoke test leaked temporary connector config: ${marker}`);
    }
  }
}

async function waitForBackend() {
  for (let attempt = 0; attempt < 20; attempt += 1) {
    try {
      const diagnostics = await fetchJson("/api/agents/diagnostics");
      return diagnostics;
    } catch {
      await new Promise((resolve) => setTimeout(resolve, 750));
    }
  }
  fail("Backend did not respond on :8765");
}

function startBackendIfNeeded(stateDir = "") {
  return new Promise(async (resolve) => {
    if (!stateDir) {
      try {
        const diagnostics = await fetchJson("/api/agents/diagnostics");
        if (backendHasCapabilities(diagnostics) && backendIsCurrentSource(diagnostics)) {
          resolve(undefined);
          return;
        }
        log(`stale backend detected or foreign backend on :${backendPort}; restarting current source backend`);
        stopBackendPort();
        await new Promise((ready) => setTimeout(ready, 1000));
      } catch {
        // Start below.
      }
    }
    const python = findPython();
    const child = spawn(python, ["-m", "uvicorn", "main:app", "--host", "127.0.0.1", "--port", String(backendPort)], {
      cwd: backendDir,
      windowsHide: true,
      stdio: "ignore",
      env: stateDir ? {
        ...process.env,
        SIGNALASI_STATE_DIR: stateDir,
        SIGNALASI_DISABLE_EXTERNAL_SERVICES: process.env.SIGNALASI_SMOKE_MOBILE === "1" ? "0" : "1",
      } : process.env,
    });
    resolve(child);
  });
}

function findFreeBackendPort() {
  return new Promise((resolve, reject) => {
    const probe = http.createServer();
    probe.once("error", reject);
    probe.listen(0, "127.0.0.1", () => {
      const address = probe.address();
      const port = typeof address === "object" && address ? address.port : 0;
      probe.close(error => error ? reject(error) : resolve(port));
    });
  });
}

async function stopChild(child) {
  if (!child || child.exitCode !== null) return;
  const exited = new Promise(resolve => child.once("exit", resolve));
  if (process.platform === "win32") {
    try {
      execFileSync("taskkill", ["/PID", String(child.pid), "/T", "/F"], { stdio: "ignore", windowsHide: true });
    } catch {
      child.kill();
    }
  } else {
    child.kill();
  }
  await Promise.race([exited, new Promise(resolve => setTimeout(resolve, 3_000))]);
}

function startFakeModelServer() {
  const server = http.createServer((req, res) => {
    let body = "";
    req.on("data", (chunk) => { body += chunk; });
    req.on("end", () => {
      res.setHeader("content-type", "application/json");
      if (req.method === "GET" && req.url.includes("/models")) {
        res.end(JSON.stringify({ data: [{ id: "fake-local" }] }));
        return;
      }
      res.end(JSON.stringify({
        choices: [{
          message: {
            content: "LOCAL_SMOKE_OK"
          }
        }]
      }));
    });
  });
  return new Promise((resolve, reject) => {
    server.once("error", reject);
    server.listen(fakeModelPort, "127.0.0.1", () => resolve(server));
  });
}

async function smoke() {
  log("checking desktop structure");
  run(process.execPath, [path.join(root, "scripts", "check.js")], { cwd: root });

  log("checking JavaScript syntax");
  for (const file of [
    "src/main.js",
    "src/preload.js",
    "src/renderer/renderer.js",
    "scripts/check.js",
    "scripts/package-win.js",
    "scripts/smoke.js"
  ]) {
    run(process.execPath, ["--check", path.join(root, file)], { cwd: root });
  }

  log("checking backend Python syntax");
  const python = findPython();
  run(python, ["-m", "py_compile", "agent_gateway.py", "agent_task_manager.py", "main.py", "mqtt_bridge.py", "agent_config.py", "desktop_native_tools.py"], { cwd: backendDir });

  log("starting or reusing backend");
  const tmpDir = fs.mkdtempSync(path.join(os.tmpdir(), "signalasi-smoke-"));
  backendPort = await findFreeBackendPort();
  backendOrigin = `http://127.0.0.1:${backendPort}`;
  const startedBackend = await startBackendIfNeeded(path.join(tmpDir, "state"));
  try {
    const diagnostics = await waitForBackend();
    if (diagnostics.pairing_route !== "/signalasi/verify") fail("Unexpected pairing route");
    if (!Array.isArray(diagnostics.agents) || diagnostics.agents.length < 5) fail("Agent diagnostics did not include all connector agents");
    assertStructuredAgentDiagnostics(diagnostics);
    log(`diagnostics ready=${diagnostics.ready.join(",") || "none"} needs_setup=${diagnostics.needs_setup.join(",") || "none"}`);

    log("checking manual mobile status sync endpoint");
    const syncResult = await fetchJson("/api/agents/sync-mobile-status", { method: "POST" });
    if (!("ok" in syncResult) || syncResult.reason !== "manual_desktop_sync" || !syncResult.code || !syncResult.params || syncResult.params.reason !== "manual_desktop_sync") {
      fail(`Unexpected sync-mobile-status response: ${JSON.stringify(syncResult)}`);
    }

    log("checking typed Desktop native tool manifest and workspace execution");
    const desktopTools = await fetchJson("/api/desktop-tools");
    const desktopToolIds = new Set((desktopTools.tools || []).map((item) => item.id));
    for (const toolId of [
      "signalasi.desktop.windows.system.status",
      "signalasi.desktop.workspace.file.list",
      "signalasi.desktop.terminal.run",
      "signalasi.desktop.office.document.convert"
    ]) {
      if (!desktopToolIds.has(toolId)) fail(`Desktop native tool manifest missing ${toolId}`);
    }
    const workspaceList = await fetchJson("/api/desktop-tools/invoke", {
      method: "POST",
      body: JSON.stringify({
        tool_id: "signalasi.desktop.workspace.file.list",
        invocation_id: "desktop-smoke-list",
        task_id: "desktop-smoke-task",
        conversation_id: "desktop-smoke-conversation",
        workspace_id: "desktop-smoke-workspace",
        arguments: {}
      })
    });
    if (workspaceList.status !== "succeeded" || workspaceList.verification?.status !== "passed") {
      fail(`Desktop native workspace tool failed: ${JSON.stringify(workspaceList)}`);
    }

    log("running connector self-test without mobile delivery");
    const selfTest = await fetchJson("/api/agents/self-test", {
      method: "POST",
      body: JSON.stringify({ include_agent_calls: false, include_mobile_delivery: false })
    });
    if (!Array.isArray(selfTest.results) || selfTest.results.length < 5) fail("Self-test result is incomplete");
    for (const item of selfTest.results) {
      if (!item.detail_code || !item.detail_params || !item.setup_code || !item.setup_params) {
        fail(`Self-test missing structured setup/status fields: ${JSON.stringify(item)}`);
      }
      for (const probe of ["agent_call", "mobile_delivery"]) {
        if (!item[probe]?.code || !item[probe]?.params?.agent_id) {
          fail(`Self-test ${probe} missing structured code/params: ${JSON.stringify(item[probe])}`);
        }
      }
    }

    log("testing local model configuration with fake OpenAI-compatible server");
    const configSnapshot = readConfigSnapshot();
    const oldConfig = await fetchJson("/api/agents/config");
    const fakeServer = await startFakeModelServer();
    try {
      await fetchJson("/api/agents/config", {
        method: "POST",
        body: JSON.stringify({
          commands: oldConfig.commands,
          local_model: {
            provider: "openai",
            url: `${fakeModelOrigin}/local/v1/chat/completions`,
            model: "fake-local",
            api_key: ""
          }
        })
      });
      const local = await fetchJson("/api/agents/local-llm/test", {
        method: "POST",
        body: JSON.stringify({ prompt: "smoke" })
      });
      if (local.reply !== "LOCAL_SMOKE_OK") fail(`Unexpected local model reply: ${local.reply}`);
    } finally {
      fakeServer.close();
      try {
        const restorePayload = configSnapshot === undefined ? oldConfig : JSON.parse(configSnapshot);
        await fetchJson("/api/agents/config", {
          method: "POST",
          body: JSON.stringify(restorePayload)
        });
      } catch {
        restoreConfigSnapshot(configSnapshot);
      }
      assertNoSmokeConfigLeak();
    }

    if (process.env.SIGNALASI_SMOKE_MOBILE === "1") {
      log("running optional encrypted mobile delivery self-test");
      const mobile = await fetchJson("/api/agents/self-test", {
        method: "POST",
        body: JSON.stringify({ include_agent_calls: false, include_mobile_delivery: true })
      });
      if (mobile.summary.mobile_delivery_failed.length) {
        fail(`Mobile delivery failed for ${mobile.summary.mobile_delivery_failed.join(",")}`);
      }
    } else {
      log("skipping mobile delivery; set SIGNALASI_SMOKE_MOBILE=1 to enable it");
    }

    log("smoke test OK");
  } finally {
    await stopChild(startedBackend);
    fs.rmSync(tmpDir, { recursive: true, force: true, maxRetries: 10, retryDelay: 100 });
  }
}

withSignalasiLock("smoke", smoke).catch((error) => {
  console.error(`[smoke] failed: ${error.stack || error.message || error}`);
  process.exit(1);
});
