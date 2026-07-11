const { app, BrowserWindow, clipboard, ipcMain, shell } = require("electron");
const { spawn, execFile } = require("node:child_process");
const fs = require("node:fs");
const path = require("node:path");
const os = require("node:os");

const BACKEND_PORT = 8765;
const BACKEND_ORIGIN = `http://127.0.0.1:${BACKEND_PORT}`;
const PAIRING_URL = `${BACKEND_ORIGIN}/signalasi/verify`;
const APP_ROOT = path.resolve(__dirname, "..");
const DEV_BACKEND_DIR = path.join(APP_ROOT, "core", "signalasi-link", "backend");
const PACKAGED_BACKEND_DIR = path.resolve(APP_ROOT, "..", "signalasi-link", "backend");
const BACKEND_DIR = fs.existsSync(DEV_BACKEND_DIR) ? DEV_BACKEND_DIR : PACKAGED_BACKEND_DIR;
const RUNTIME_ROOT = fs.existsSync(DEV_BACKEND_DIR) ? APP_ROOT : path.resolve(APP_ROOT, "..");
const UI_SMOKE = process.env.SIGNALASI_UI_SMOKE === "1";
if (UI_SMOKE) {
  const smokeUserData = path.join(process.env.SIGNALASI_UI_SMOKE_DIR || path.join(RUNTIME_ROOT, "ui-smoke"), "user-data");
  app.setPath("userData", smokeUserData);
}

let mainWindow;
let backendProcess;

function createWindow() {
  mainWindow = new BrowserWindow({
    width: 1120,
    height: 760,
    minWidth: 940,
    minHeight: 640,
    title: "SignalASI Desktop",
    icon: path.join(APP_ROOT, "assets", "signalasi-mark.png"),
    backgroundColor: "#f5f6f7",
    autoHideMenuBar: true,
    webPreferences: {
      preload: path.join(__dirname, "preload.js"),
      contextIsolation: true,
      nodeIntegration: false,
      sandbox: false
    }
  });

  mainWindow.loadFile(path.join(__dirname, "renderer", "index.html"));
  if (UI_SMOKE) {
    mainWindow.webContents.once("did-finish-load", runUiSmoke);
  }
}

async function runUiSmoke() {
  const outDir = process.env.SIGNALASI_UI_SMOKE_DIR || path.join(RUNTIME_ROOT, "ui-smoke");
  const overviewPath = path.join(outDir, "desktop-overview.png");
  const languageEnPath = path.join(outDir, "desktop-language-en.png");
  const languageZhPath = path.join(outDir, "desktop-language-zh.png");
  const setupPath = path.join(outDir, "desktop-setup-guide.png");
  const matrixPath = path.join(outDir, "desktop-status-matrix.png");
  const agentsPath = path.join(outDir, "desktop-agents.png");
  try {
    fs.mkdirSync(outDir, { recursive: true });
    let state;
    for (let attempt = 0; attempt < 60; attempt += 1) {
      await new Promise((resolve) => setTimeout(resolve, 500));
      state = await mainWindow.webContents.executeJavaScript(`
        (() => {
          const state = {
            title: document.querySelector(".topbar h2")?.textContent || "",
            setupTitle: document.querySelector(".setup-guide h3")?.textContent || "",
            setupItems: document.querySelectorAll("#setupChecklist .setup-item").length,
            matrixTitle: document.querySelector(".status-matrix h3")?.textContent || "",
            rows: document.querySelectorAll("#connectorMatrixRows .matrix-row").length,
            summary: document.querySelector("#statusSummary")?.textContent || "",
            backend: document.querySelector("#backendBadge")?.textContent || ""
          };
          if ((state.setupItems < 6 || state.rows < 5) && ${attempt % 6 === 0 ? "true" : "false"}) {
            document.querySelector("#refreshDiagnostics")?.click();
          }
          return state;
        })()
      `);
      if (state.setupTitle.trim() && state.setupItems >= 6 && state.matrixTitle.trim() && state.rows >= 5) break;
    }
    if (!state || !state.setupTitle.trim() || state.setupItems < 6 || !state.matrixTitle.trim() || state.rows < 5) {
      throw new Error(`Setup guide or status matrix did not render: ${JSON.stringify(state)}`);
    }
    const defaultLanguage = await mainWindow.webContents.executeJavaScript(`
      (() => ({
        lang: document.documentElement.lang,
        selected: document.querySelector("#languageSelect")?.value || "",
        title: document.querySelector(".topbar h2")?.textContent || ""
      }))()
    `);
    if (defaultLanguage.lang !== "en" || defaultLanguage.selected !== "en" || defaultLanguage.title !== "Local Agent Connector") {
      throw new Error(`Desktop did not default to English: ${JSON.stringify(defaultLanguage)}`);
    }
    await captureSmokeScreenshot(languageEnPath);
    const zhLanguage = await mainWindow.webContents.executeJavaScript(`
      (async () => {
        const select = document.querySelector("#languageSelect");
        select.value = "zh-CN";
        select.dispatchEvent(new Event("change", { bubbles: true }));
        await new Promise((resolve) => setTimeout(resolve, 900));
        return {
          lang: document.documentElement.lang,
          selected: select.value,
          title: document.querySelector(".topbar h2")?.textContent || "",
          overview: document.querySelector('[data-target="overview"]')?.textContent || ""
        };
      })()
    `);
    if (zhLanguage.lang !== "zh-Hans" || zhLanguage.selected !== "zh-CN" || zhLanguage.title !== "\u672c\u5730 Agent \u8fde\u63a5\u5668") {
      throw new Error(`Desktop Simplified Chinese language switch failed: ${JSON.stringify(zhLanguage)}`);
    }
    await captureSmokeScreenshot(languageZhPath);
    const restoredLanguage = await mainWindow.webContents.executeJavaScript(`
      (async () => {
        const select = document.querySelector("#languageSelect");
        select.value = "en";
        select.dispatchEvent(new Event("change", { bubbles: true }));
        await new Promise((resolve) => setTimeout(resolve, 900));
        return {
          lang: document.documentElement.lang,
          selected: select.value,
          title: document.querySelector(".topbar h2")?.textContent || ""
        };
      })()
    `);
    if (restoredLanguage.lang !== "en" || restoredLanguage.selected !== "en" || restoredLanguage.title !== "Local Agent Connector") {
      throw new Error(`Desktop English language restore failed: ${JSON.stringify(restoredLanguage)}`);
    }
    await captureSmokeScreenshot(overviewPath);
    await mainWindow.webContents.executeJavaScript(`
      document.querySelector(".setup-guide")?.scrollIntoView({ block: "start" });
    `);
    await new Promise((resolve) => setTimeout(resolve, 250));
    await captureSmokeScreenshot(setupPath);
    await mainWindow.webContents.executeJavaScript(`
      document.querySelector(".status-matrix")?.scrollIntoView({ block: "start" });
    `);
    await new Promise((resolve) => setTimeout(resolve, 250));
    await captureSmokeScreenshot(matrixPath);
    const agentsState = await mainWindow.webContents.executeJavaScript(`
      (() => {
        document.querySelector('[data-target="agents"]')?.click();
        document.querySelector("#addCustomAgent")?.click();
        return {
          active: document.querySelector("#agents")?.classList.contains("active-section") || false,
          customAgentEditor: Boolean(document.querySelector(".custom-agent-editor")),
          addButton: Boolean(document.querySelector("#addCustomAgent")),
          list: Boolean(document.querySelector("#customAgentsList")),
          rows: document.querySelectorAll("#customAgentsList .custom-agent-row").length,
          auditTitle: document.querySelector(".audit h3")?.textContent || "",
          permissions: document.querySelectorAll(".permission-grid div").length,
          executionLog: Boolean(document.querySelector("#executionLog"))
        };
      })()
    `);
    if (!agentsState.active || !agentsState.customAgentEditor || !agentsState.addButton || !agentsState.list || agentsState.rows < 1 || !agentsState.auditTitle.trim() || agentsState.permissions < 3 || !agentsState.executionLog) {
      throw new Error(`Agents page did not expose custom agent row editor: ${JSON.stringify(agentsState)}`);
    }
    await new Promise((resolve) => setTimeout(resolve, 250));
    await captureSmokeScreenshot(agentsPath);
    console.log(`[ui-smoke] screenshot: ${overviewPath}`);
    console.log(`[ui-smoke] screenshot: ${languageEnPath}`);
    console.log(`[ui-smoke] screenshot: ${languageZhPath}`);
    console.log(`[ui-smoke] screenshot: ${setupPath}`);
    console.log(`[ui-smoke] screenshot: ${matrixPath}`);
    console.log(`[ui-smoke] screenshot: ${agentsPath}`);
    app.exit(0);
  } catch (error) {
    console.error(`[ui-smoke] failed: ${error.stack || error.message || error}`);
    app.exit(1);
  }
}

async function captureSmokeScreenshot(target) {
  for (let attempt = 0; attempt < 8; attempt += 1) {
    await new Promise((resolve) => setTimeout(resolve, attempt === 0 ? 200 : 500));
    const image = await mainWindow.webContents.capturePage();
    const png = image.toPNG();
    if (png.length >= 1000) {
      fs.writeFileSync(target, png);
      return;
    }
  }
  throw new Error(`UI smoke screenshot was empty: ${target}`);
}

function findPython() {
  const bundledPython = path.join(RUNTIME_ROOT, "python", "venv", "Scripts", "python.exe");
  const candidates = [
    process.env.SIGNALASI_PYTHON,
    bundledPython,
    path.join(os.homedir(), "AppData", "Local", "hermes", "hermes-agent", "venv", "Scripts", "python.exe"),
    path.join(os.homedir(), "AppData", "Roaming", "uv", "python", "cpython-3.11-windows-x86_64-none", "python.exe"),
    "python"
  ].filter(Boolean);
  return candidates.find((candidate) => candidate === "python" || fs.existsSync(candidate)) || "python";
}

async function backendStatus() {
  try {
    const response = await fetch(`${BACKEND_ORIGIN}/signalasi/verify`, { method: "GET" });
    return {
      running: response.ok,
      status: response.status,
      origin: BACKEND_ORIGIN,
      pairingUrl: PAIRING_URL,
      backendDir: BACKEND_DIR
    };
  } catch (error) {
    return {
      running: false,
      status: 0,
      origin: BACKEND_ORIGIN,
      pairingUrl: PAIRING_URL,
      backendDir: BACKEND_DIR,
      error: error.message
    };
  }
}

async function startBackend() {
  const current = await backendStatus();
  if (current.running) return current;
  if (!fs.existsSync(path.join(BACKEND_DIR, "main.py"))) {
    return { ...current, error: `Backend not found: ${BACKEND_DIR}` };
  }
  if (backendProcess && !backendProcess.killed) {
    for (let attempt = 0; attempt < 12; attempt += 1) {
      await new Promise((resolve) => setTimeout(resolve, 500));
      const status = await backendStatus();
      if (status.running) return status;
    }
    return backendStatus();
  }

  const python = findPython();
  const signalasiDataDir = path.join(app.getPath("userData"), "runtime");
  fs.mkdirSync(signalasiDataDir, { recursive: true });
  try {
    backendProcess = spawn(python, ["-m", "uvicorn", "main:app", "--host", "0.0.0.0", "--port", String(BACKEND_PORT)], {
      cwd: BACKEND_DIR,
      env: {
        ...process.env,
        SIGNALASI_DATA_DIR: signalasiDataDir
      },
      windowsHide: true,
      stdio: "ignore",
      detached: false
    });
  } catch (error) {
    return { ...current, error: error.message || String(error) };
  }

  backendProcess.on("exit", () => {
    backendProcess = undefined;
  });

  for (let attempt = 0; attempt < 12; attempt += 1) {
    await new Promise((resolve) => setTimeout(resolve, 500));
    const status = await backendStatus();
    if (status.running) return status;
  }
  return backendStatus();
}

function commandExists(command, args = ["--version"]) {
  return new Promise((resolve) => {
    try {
      execFile(command, args, { windowsHide: true, timeout: 2500 }, (error, stdout, stderr) => {
        resolve({
          ok: !error,
          code: error?.code ?? 0,
          output: String(stdout || stderr || error?.message || "").split(/\r?\n/)[0].trim()
        });
      });
    } catch (error) {
      resolve({
        ok: false,
        code: error?.code ?? 1,
        output: error.message || String(error)
      });
    }
  });
}

function runCommand(command, args = [], timeout = 5000) {
  return new Promise((resolve) => {
    execFile(command, args, { windowsHide: true, timeout }, (error, stdout, stderr) => {
      resolve({
        ok: !error,
        code: error?.code ?? 0,
        output: String(stdout || stderr || "").trim()
      });
    });
  });
}

function loadLocale(language) {
  const normalized = language === "en" ? "en" : "zh-CN";
  const localePath = path.join(APP_ROOT, "src", "renderer", "locales", `${normalized}.json`);
  try {
    return JSON.parse(fs.readFileSync(localePath, "utf8"));
  } catch {
    return {};
  }
}

async function runtimeDiagnostics() {
  const python = findPython();
  const pythonVersion = await runCommand(python, ["--version"], 5000);
  const pythonDeps = pythonVersion.ok
    ? await runCommand(python, ["-c", "import fastapi, uvicorn, paho.mqtt.client, sqlalchemy, pydantic; print('backend deps ok')"], 8000)
    : { ok: false, code: 1, output: "Python not found" };
  const sidecarRuntime = path.join(BACKEND_DIR, "signal_sidecar", "build", "install", "signalasi-link-sidecar", "bin", "signalasi-link-sidecar.bat");
  const packaged = Boolean(app.isPackaged);
  return {
    app: {
      packaged,
      appPath: app.getAppPath(),
      resourcesPath: process.resourcesPath,
      backendOrigin: BACKEND_ORIGIN
    },
    backend: {
      dir: BACKEND_DIR,
      exists: fs.existsSync(path.join(BACKEND_DIR, "main.py")),
      sidecarRuntime,
      sidecarExists: fs.existsSync(sidecarRuntime)
    },
    python: {
      command: python,
      ok: pythonVersion.ok,
      version: pythonVersion.output,
      depsOk: pythonDeps.ok,
      depsOutput: pythonDeps.output
    },
    installHint: "If Python deps are missing, run install-backend-deps.bat from the portable package or pip install -r backend/requirements.txt."
  };
}

async function detectAgents() {
  try {
    const status = await startBackend();
    if (status.running) {
      const response = await fetch(`${BACKEND_ORIGIN}/api/agents`);
      if (response.ok) {
        const agents = await response.json();
        return agents.map((agent) => ({
          id: agent.id,
          name: agent.name,
          kind: agent.kind,
          status: agent.status === "ready" ? "detected" : agent.status === "needs_setup" ? "manual" : agent.status,
          detail: agent.detail || agent.note || "",
          pairing: agent.id === "hermes" ? "SignalASI Link QR" : "Connector managed"
        }));
      }
    }
  } catch {
    // Fall back to local command probing below.
  }

  const [hermes, codex, claude, ollama] = await Promise.all([
    commandExists("hermes", ["--version"]),
    commandExists("codex", ["--version"]),
    commandExists("claude", ["--version"]),
    commandExists("ollama", ["--version"])
  ]);

  return [
    {
      id: "hermes",
      name: "Hermes Agent",
      kind: "local-cli",
      status: hermes.ok ? "detected" : "missing",
      detail: hermes.output || "Install Hermes CLI or configure a custom command.",
      pairing: "SignalASI Link QR"
    },
    {
      id: "codex",
      name: "Codex Agent",
      kind: "local-cli",
      status: codex.ok ? "detected" : "missing",
      detail: codex.output || "Use the SignalASI Desktop Connector to wrap Codex as a contact.",
      pairing: "Connector managed"
    },
    {
      id: "claude",
      name: "Claude Code",
      kind: "local-cli",
      status: claude.ok ? "detected" : "missing",
      detail: claude.output || "Install Claude Code CLI or set a custom command later.",
      pairing: "Connector managed"
    },
    {
      id: "local-llm",
      name: "Local LLM",
      kind: "local-model",
      status: ollama.ok ? "detected" : "manual",
      detail: ollama.output || "Ollama not detected. OpenAI-compatible localhost endpoints can be added next.",
      pairing: "Connector managed"
    },
    {
      id: "custom-agent",
      name: "Custom Agent",
      kind: "custom-cli",
      status: "manual",
      detail: "Set any CLI or MCP wrapper command in SignalASI Desktop.",
      pairing: "Connector managed"
    }
  ];
}

async function fetchJson(pathname, options = {}) {
  let lastError;
  for (let attempt = 0; attempt < 8; attempt += 1) {
    try {
      const response = await fetch(`${BACKEND_ORIGIN}${pathname}`, {
        headers: { "Content-Type": "application/json", ...(options.headers || {}) },
        ...options
      });
      if (!response.ok) {
        throw new Error(`HTTP ${response.status}`);
      }
      const contentType = response.headers.get("content-type") || "";
      if (!contentType.includes("application/json")) {
        throw new Error(`Expected JSON from ${pathname}, got ${contentType || "unknown content-type"}`);
      }
      return response.json();
    } catch (error) {
      lastError = error;
      if (attempt === 7) break;
      await startBackend();
      await new Promise((resolve) => setTimeout(resolve, 350));
    }
  }
  throw lastError;
}

async function getAgentConfig() {
  await startBackend();
  return fetchJson("/api/agents/config");
}

async function getAgentDiagnostics() {
  await startBackend();
  return fetchJson("/api/agents/diagnostics");
}

async function getAgentExecutionLog(limit = 50) {
  await startBackend();
  return fetchJson(`/api/agents/execution-log?limit=${encodeURIComponent(limit)}`);
}

async function getPairingStatus() {
  await startBackend();
  return fetchJson("/api/pairing/status");
}

async function clearPairing() {
  await startBackend();
  return fetchJson("/api/pairing/clear", { method: "POST" });
}

async function runAgentSelfTest(options = {}) {
  await startBackend();
  return fetchJson("/api/agents/self-test", {
    method: "POST",
    body: JSON.stringify({
      include_agent_calls: Boolean(options.includeAgentCalls),
      include_mobile_delivery: options.includeMobileDelivery !== false
    })
  });
}

async function saveAgentConfig(config) {
  await startBackend();
  return fetchJson("/api/agents/config", {
    method: "POST",
    body: JSON.stringify(config)
  });
}

async function testAgent(agentId, prompt) {
  await startBackend();
  return fetchJson(`/api/agents/${encodeURIComponent(agentId)}/test`, {
    method: "POST",
    body: JSON.stringify({ prompt: prompt || "hello" })
  });
}

async function sendMobileTestMessage(contactId, content) {
  await startBackend();
  return fetchJson("/api/mobile/test-message", {
    method: "POST",
    body: JSON.stringify({ contact_id: contactId, content: content || `DESKTOP_TEST_${Date.now()}` })
  });
}

async function syncMobileStatus() {
  await startBackend();
  return fetchJson("/api/agents/sync-mobile-status", { method: "POST" });
}

ipcMain.handle("backend:start", startBackend);
ipcMain.handle("backend:status", backendStatus);
ipcMain.handle("runtime:diagnostics", runtimeDiagnostics);
ipcMain.handle("pairing:status", getPairingStatus);
ipcMain.handle("pairing:clear", clearPairing);
ipcMain.handle("agents:detect", detectAgents);
ipcMain.handle("agents:diagnostics", getAgentDiagnostics);
ipcMain.handle("agents:execution-log", (_event, limit) => getAgentExecutionLog(limit));
ipcMain.handle("agents:self-test", (_event, options) => runAgentSelfTest(options));
ipcMain.handle("agents:config:get", getAgentConfig);
ipcMain.handle("agents:config:save", (_event, config) => saveAgentConfig(config));
ipcMain.handle("agents:test", (_event, agentId, prompt) => testAgent(agentId, prompt));
ipcMain.handle("mobile:test-message", (_event, contactId, content) => sendMobileTestMessage(contactId, content));
ipcMain.handle("mobile:sync-status", syncMobileStatus);
ipcMain.handle("i18n:load", (_event, language) => loadLocale(language));
ipcMain.handle("pairing:url", () => PAIRING_URL);
ipcMain.handle("open:external", (_event, url) => shell.openExternal(url));
ipcMain.handle("clipboard:write", (_event, text) => {
  clipboard.writeText(String(text || ""));
  return { ok: true };
});

app.whenReady().then(async () => {
  createWindow();
  startBackend();
});

app.on("window-all-closed", () => {
  if (process.platform !== "darwin") app.quit();
});

app.on("before-quit", () => {
  if (backendProcess && !backendProcess.killed) {
    backendProcess.kill();
  }
});
