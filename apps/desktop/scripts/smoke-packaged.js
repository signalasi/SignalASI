const fs = require("node:fs");
const os = require("node:os");
const path = require("node:path");
const { spawn, spawnSync } = require("node:child_process");
const net = require("node:net");
const { withSignalasiLock } = require("./smoke-lock");

const root = path.resolve(__dirname, "..");
const packageDir = path.join(root, "dist", "SignalASI Desktop-win-x64");
const exe = path.join(packageDir, "SignalASI Desktop.exe");
const resources = path.join(packageDir, "resources");
const bundledPython = path.join(resources, "python", "venv", "Scripts", "python.exe");
const backendMain = path.join(resources, "signalasi-link", "backend", "main.py");
const packagedBackendInstanceLock = path.join(resources, "signalasi-link", "backend", "backend_instance_lock.py");
const packagedTaskWorkspace = path.join(resources, "signalasi-link", "backend", "task_workspace.py");
const packagedResponsePolicy = path.join(resources, "signalasi-link", "backend", "response_policy.py");
const packagedAgentTaskStore = path.join(resources, "signalasi-link", "backend", "agent_task_store.py");
const packagedConversationContext = path.join(resources, "signalasi-link", "backend", "conversation_context.py");
const packagedBackendDir = path.dirname(backendMain);
const packagedCustomAgent = path.join(packagedBackendDir, "custom_agent_stdio.py");
const packagedDesktopAgentAdapters = path.join(packagedBackendDir, "desktop_agent_adapters.py");
const packagedDesktopControl = path.join(packagedBackendDir, "desktop_control.py");
const packagedDesktopNativeTools = path.join(packagedBackendDir, "desktop_native_tools.py");
const packagedDesktopMemory = path.join(packagedBackendDir, "desktop_memory.py");
const packagedDesktopMcp = path.join(packagedBackendDir, "desktop_mcp.py");
const packagedDesktopSkills = path.join(packagedBackendDir, "desktop_skills.py");
const packagedDesktopSuperAgent = path.join(packagedBackendDir, "desktop_super_agent.py");
const packagedMcpWrapper = path.join(packagedBackendDir, "mcp_agent_wrapper.py");
const packagedPhoneToolBroker = path.join(packagedBackendDir, "phone_tool_broker.py");
const packagedRichOutput = path.join(packagedBackendDir, "rich_output.py");
const packagedStatusScript = path.join(resources, "app", "scripts", "connector-status.js");
const packagedStatusDoc = path.join(resources, "app", "docs", "CONNECTOR_STATUS.md");
const packagedUiSmokeDir = path.join(packageDir, "ui-smoke");
const packagedUiScreenshots = [
  path.join(packagedUiSmokeDir, "desktop-overview.png"),
  path.join(packagedUiSmokeDir, "desktop-status-matrix.png")
];
const sidecar = path.join(
  resources,
  "signalasi-link",
  "backend",
  "signal_sidecar",
  "build",
  "install",
  "signalasi-link-sidecar",
  "bin",
  "signalasi-link-sidecar.bat"
);

function assertExists(target, label) {
  if (!fs.existsSync(target)) {
    throw new Error(`${label} missing: ${target}`);
  }
}

async function fetchOk(url) {
  const response = await fetch(url);
  if (!response.ok) throw new Error(`${url} returned HTTP ${response.status}`);
  return response.text();
}

async function fetchJson(url, options = {}) {
  const response = await fetch(url, {
    headers: { "Content-Type": "application/json", ...(options.headers || {}) },
    ...options
  });
  if (!response.ok) throw new Error(`${url} returned HTTP ${response.status}`);
  return response.json();
}

function findFreePort() {
  return new Promise((resolve, reject) => {
    const server = net.createServer();
    server.on("error", reject);
    server.listen(0, "127.0.0.1", () => {
      const address = server.address();
      const port = typeof address === "object" ? address.port : 0;
      server.close(() => resolve(port));
    });
  });
}

function stopProcessTree(child) {
  if (!child || child.killed) return;
  if (process.platform === "win32" && child.pid) {
    spawnSync("taskkill", ["/pid", String(child.pid), "/T", "/F"], {
      windowsHide: true,
      stdio: "ignore"
    });
    return;
  }
  child.kill();
}

function stopPackagedBackendHelpers() {
  if (process.platform !== "win32") return;
  const escapedBackend = packagedBackendDir.replace(/'/g, "''");
  const command = `
    Get-CimInstance Win32_Process |
      Where-Object {
        $_.Name -in @('python.exe', 'java.exe', 'cmd.exe') -and
        $_.CommandLine -like '*${escapedBackend}*' -and
        ($_.CommandLine -like '*file_server.py*' -or
         $_.CommandLine -like '*signalasi-link-sidecar*' -or
         $_.CommandLine -like '*uvicorn*main:app*')
      } |
      ForEach-Object { Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue }
  `;
  spawnSync("powershell.exe", ["-NoProfile", "-Command", command], {
    windowsHide: true,
    stdio: "ignore"
  });
}

function assertScreenshot(target) {
  assertExists(target, "Packaged UI smoke screenshot");
  const size = fs.statSync(target).size;
  if (size < 1000) {
    throw new Error(`Packaged UI smoke screenshot is too small: ${target}`);
  }
}

function waitForExit(child, timeoutMs) {
  return new Promise((resolve, reject) => {
    const timer = setTimeout(() => {
      stopProcessTree(child);
      reject(new Error("Packaged UI smoke timed out"));
    }, timeoutMs);
    child.on("error", (error) => {
      clearTimeout(timer);
      reject(error);
    });
    child.on("exit", (code) => {
      clearTimeout(timer);
      if (code === 0) resolve();
      else reject(new Error(`Packaged UI smoke exited ${code}`));
    });
  });
}

async function main() {
  console.log("[packaged-smoke] checking portable package layout");
  assertExists(exe, "SignalASI Desktop exe");
  assertExists(backendMain, "Packaged backend");
  assertExists(packagedBackendInstanceLock, "Packaged backend instance lock");
  assertExists(packagedTaskWorkspace, "Packaged task workspace module");
  assertExists(packagedResponsePolicy, "Packaged response policy module");
  assertExists(packagedAgentTaskStore, "Packaged Agent task store");
  assertExists(packagedConversationContext, "Packaged conversation context");
  assertExists(packagedCustomAgent, "Packaged Custom Agent wrapper");
  assertExists(packagedDesktopAgentAdapters, "Packaged Desktop Agent adapters");
  assertExists(packagedDesktopControl, "Packaged Desktop control module");
  assertExists(packagedDesktopNativeTools, "Packaged Desktop native tools");
  assertExists(packagedDesktopMemory, "Packaged Desktop memory");
  assertExists(packagedDesktopMcp, "Packaged Desktop MCP registry");
  assertExists(packagedDesktopSkills, "Packaged Desktop skills");
  assertExists(packagedDesktopSuperAgent, "Packaged Desktop super agent");
  assertExists(packagedMcpWrapper, "Packaged MCP wrapper");
  assertExists(packagedPhoneToolBroker, "Packaged phone tool broker");
  assertExists(packagedRichOutput, "Packaged rich output module");
  assertExists(sidecar, "Packaged Signal sidecar");
  assertExists(bundledPython, "Bundled Python");
  assertExists(packagedStatusScript, "Packaged connector status script");
  assertExists(packagedStatusDoc, "Packaged connector status doc");

  console.log("[packaged-smoke] checking bundled Python dependencies");
  const pythonCheck = spawn(
    bundledPython,
    ["-c", "import cryptography, fastapi, multipart, uvicorn, paho.mqtt.client, sqlalchemy, pydantic, websockets, qrcode, agent_task_store, backend_instance_lock, conversation_context, desktop_agent_adapters, desktop_control, desktop_native_tools, mqtt_bridge, phone_tool_broker, rich_output; print('ok')"],
    { cwd: packagedBackendDir, windowsHide: true }
  );
  await new Promise((resolve, reject) => {
    let stdout = "";
    let stderr = "";
    pythonCheck.stdout.on("data", (chunk) => { stdout = `${stdout}${chunk}`.slice(-4000); });
    pythonCheck.stderr.on("data", (chunk) => { stderr = `${stderr}${chunk}`.slice(-4000); });
    pythonCheck.on("error", reject);
    pythonCheck.on("exit", (code) => {
      if (code === 0) {
        resolve();
        return;
      }
      reject(new Error(
        `Python dependency check exited ${code}\nstdout:\n${stdout.trim() || "<empty>"}\nstderr:\n${stderr.trim() || "<empty>"}`
      ));
    });
  });

  const tempPort = await findFreePort();
  const backendStateDir = fs.mkdtempSync(path.join(os.tmpdir(), "signalasi-packaged-smoke-"));
  console.log(`[packaged-smoke] starting packaged backend on temporary port ${tempPort}`);
  const backend = spawn(
    bundledPython,
    ["-m", "uvicorn", "main:app", "--host", "127.0.0.1", "--port", String(tempPort)],
    {
      cwd: packagedBackendDir,
      windowsHide: true,
      stdio: "ignore",
      env: {
        ...process.env,
        SIGNALASI_STATE_DIR: backendStateDir,
        SIGNALASI_DATA_DIR: path.join(backendStateDir, "pairing"),
        SIGNALASI_DATABASE_PATH: path.join(backendStateDir, "signalasi.db"),
        SIGNALASI_CONFIG_PATH: path.join(backendStateDir, "agents.json"),
        SIGNALASI_DISABLE_EXTERNAL_SERVICES: "1"
      }
    }
  );
  try {
    let backendOk = false;
    for (let attempt = 0; attempt < 60; attempt += 1) {
      await new Promise((resolve) => setTimeout(resolve, 500));
      try {
        await fetchOk(`http://127.0.0.1:${tempPort}/signalasi/verify`);
        const pairingQr = await fetchJson(`http://127.0.0.1:${tempPort}/api/pairing/qr`);
        if (!String(pairingQr.image_data_url || "").startsWith("data:image/png;base64,") || !pairingQr.fingerprint) {
          throw new Error("Packaged Desktop pairing QR API returned an invalid payload");
        }
        const diagnostics = await fetchJson(`http://127.0.0.1:${tempPort}/api/agents/diagnostics`);
        const capabilities = new Set(diagnostics.capabilities || []);
        for (const capability of ["model_display_names", "local_model_endpoint_probe", "mobile_cloud_models", "mcp_stdio_wrapper", "multiple_custom_agents", "api_response_codes", "agent_diagnostics_codes"]) {
          if (!capabilities.has(capability)) {
            throw new Error(`Packaged backend missing capability: ${capability}`);
          }
        }
        for (const agent of diagnostics.agents || []) {
          if (!agent.detail_code || !agent.detail_params || !agent.setup_code || !agent.setup_params || !agent.pairing_code || !agent.pairing_params) {
            throw new Error(`Packaged backend missing structured agent diagnostics: ${JSON.stringify(agent)}`);
          }
        }
        const nativeManifest = await fetchJson(`http://127.0.0.1:${tempPort}/api/desktop-tools`);
        const nativeIds = new Set((nativeManifest.tools || []).map((item) => item.id));
        if (!nativeIds.has("signalasi.desktop.windows.system.status") || !nativeIds.has("signalasi.desktop.office.document.convert")) {
          throw new Error("Packaged Desktop native tool manifest is incomplete");
        }
        const nativeStatus = await fetchJson(`http://127.0.0.1:${tempPort}/api/desktop-tools/invoke`, {
          method: "POST",
          body: JSON.stringify({
            tool_id: "signalasi.desktop.windows.system.status",
            invocation_id: "packaged-native-status",
            task_id: "packaged-smoke-task",
            conversation_id: "packaged-smoke-conversation",
            arguments: {}
          })
        });
        if (nativeStatus.status !== "succeeded" || nativeStatus.verification?.status !== "passed") {
          throw new Error(`Packaged Desktop native status failed: ${JSON.stringify(nativeStatus)}`);
        }
        const desktopControl = await fetchJson(`http://127.0.0.1:${tempPort}/api/desktop-control`);
        if (desktopControl.enabled !== false || !Array.isArray(desktopControl.authorizations)) {
          throw new Error(`Packaged Desktop control status is invalid: ${JSON.stringify(desktopControl)}`);
        }
        backendOk = true;
        break;
      } catch {
        // Keep waiting for uvicorn.
      }
    }
    if (!backendOk) throw new Error("Packaged backend did not answer /signalasi/verify on temporary port");
  } finally {
    stopProcessTree(backend);
    stopPackagedBackendHelpers();
    fs.rmSync(backendStateDir, { recursive: true, force: true, maxRetries: 10, retryDelay: 100 });
  }

  console.log("[packaged-smoke] starting packaged exe UI smoke");
  fs.rmSync(packagedUiSmokeDir, { recursive: true, force: true });
  const child = spawn(exe, [], {
    cwd: packageDir,
    detached: false,
    windowsHide: true,
    stdio: "ignore",
    env: {
      ...process.env,
      SIGNALASI_UI_SMOKE: "1",
      SIGNALASI_UI_SMOKE_DIR: packagedUiSmokeDir
    }
  });

  try {
    let ok = false;
    for (let attempt = 0; attempt < 20; attempt += 1) {
      await new Promise((resolve) => setTimeout(resolve, 500));
      try {
        await fetchOk("http://127.0.0.1:8765/signalasi/verify");
        await fetchJson("http://127.0.0.1:18765/files");
        ok = true;
        break;
      } catch {
        // Keep waiting for Electron to start the backend.
      }
    }
    if (!ok) throw new Error("Packaged backend did not answer /signalasi/verify");
    await waitForExit(child, 60000);
    for (const screenshot of packagedUiScreenshots) {
      assertScreenshot(screenshot);
    }
  } finally {
    stopProcessTree(child);
    stopPackagedBackendHelpers();
  }

  console.log("[packaged-smoke] packaged smoke OK");
}

withSignalasiLock("smoke:packaged", main).catch((error) => {
  console.error(error);
  process.exit(1);
});
