const fs = require("node:fs");
const os = require("node:os");
const path = require("node:path");
const { execFileSync } = require("node:child_process");
const { acquireSignalasiLock } = require("./smoke-lock");

const root = path.resolve(__dirname, "..");
const packageMetadata = JSON.parse(fs.readFileSync(path.join(root, "package.json"), "utf8"));
const workspaceRoot = path.resolve(root, "..");
const electronDistCandidates = [
  path.join(root, ".electron-runtime", "node_modules", "electron", "dist"),
  path.join(root, "node_modules", "electron", "dist")
];
const electronDist = electronDistCandidates.find((candidate) => fs.existsSync(candidate)) || electronDistCandidates[0];
const backendSrc = path.join(root, "core", "signalasi-link", "backend");
const outRoot = path.join(root, "dist");
const appName = "SignalASI Desktop";
const packageDir = path.join(outRoot, `${appName}-win-x64`);
const resourcesDir = path.join(packageDir, "resources");
const appDir = path.join(resourcesDir, "app");
const packagedBackendDir = path.join(resourcesDir, "signalasi-link", "backend");
const bundledPythonDir = path.join(resourcesDir, "python", "venv");
const runtimePythonDir = path.join(root, ".runtime-python", "venv");
const bundlePython = process.argv.includes("--bundle-python") || process.env.SIGNALASI_BUNDLE_PYTHON === "1";
const releaseLock = acquireSignalasiLock(bundlePython ? "package:win:python" : "package:win");
const sidecarDir = path.join(backendSrc, "signal_sidecar");
const sidecarRuntimeName = "signalasi-link-sidecar";
const sidecarRuntimeDir = path.join(sidecarDir, "build", "install", sidecarRuntimeName);

process.on("exit", releaseLock);

const backendFiles = [
  "api_response.py",
  "agent_config.py",
  "agent_gateway.py",
  "agent_task_manager.py",
  "backend_instance_lock.py",
  "codex_app_server.py",
  "custom_agent_stdio.py",
  "desktop_agent_adapters.py",
  "desktop_file_tools.py",
  "desktop_native_tools.py",
  "file_server.py",
  "link_delivery.py",
  "link_protocol.py",
  "main.py",
  "mcp_agent_wrapper.py",
  "models.py",
  "mqtt_bridge.py",
  "pairing_state.py",
  "phone_tool_broker.py",
  "push_auth.py",
  "requirements.txt",
  "response_policy.py",
  "rich_output.py",
  "signalasi_client.py",
  "signalasi_notify.py",
  "stt_bridge.py",
  "task_workspace.py",
  "websocket.py"
];

function copyRecursive(src, dest, options = {}) {
  const stat = fs.statSync(src);
  if (stat.isDirectory()) {
    fs.mkdirSync(dest, { recursive: true });
    for (const entry of fs.readdirSync(src)) {
      if (options.ignore?.(path.join(src, entry), entry)) continue;
      copyRecursive(path.join(src, entry), path.join(dest, entry), options);
    }
    return;
  }
  fs.mkdirSync(path.dirname(dest), { recursive: true });
  fs.copyFileSync(src, dest);
}

function removeIfExists(target) {
  fs.rmSync(target, { recursive: true, force: true });
}

function writeJson(target, data) {
  fs.mkdirSync(path.dirname(target), { recursive: true });
  fs.writeFileSync(target, `${JSON.stringify(data, null, 2)}\n`, "utf8");
}

function requirePath(target, label) {
  if (!fs.existsSync(target)) {
    throw new Error(`${label} not found: ${target}`);
  }
}

function run(command, args, options = {}) {
  execFileSync(command, args, {
    cwd: options.cwd || root,
    stdio: options.stdio || "inherit",
    windowsHide: true
  });
}

function findRceditExecutable() {
  const candidates = [
    process.env.RCEDIT_EXE,
    path.join(root, "node_modules", "rcedit", "bin", "rcedit.exe"),
    path.join(root, ".electron-runtime", "node_modules", "rcedit", "bin", "rcedit.exe")
  ].filter(Boolean);
  return candidates.find((candidate) => fs.existsSync(candidate));
}

function findPythonExecutable() {
  const candidates = [
    process.env.SIGNALASI_PYTHON,
    "python",
    path.join(os.homedir(), "AppData", "Local", "hermes", "hermes-agent", "venv", "Scripts", "python.exe"),
    path.join(os.homedir(), "AppData", "Roaming", "uv", "python", "cpython-3.11-windows-x86_64-none", "python.exe")
  ].filter(Boolean);
  return candidates.find((candidate) => candidate === "python" || fs.existsSync(candidate));
}

function findGradleCommand() {
  const wrapper = path.join(workspaceRoot, "android", process.platform === "win32" ? "gradlew.bat" : "gradlew");
  if (fs.existsSync(wrapper)) return wrapper;
  return process.platform === "win32" ? "gradle.bat" : "gradle";
}

function runGradle(args, options = {}) {
  const gradle = findGradleCommand();
  if (process.platform === "win32") {
    run(process.env.ComSpec || "cmd.exe", ["/d", "/c", "call", gradle, ...args], options);
    return;
  }
  run(gradle, args, options);
}

function ensureSignalSidecarRuntime() {
  if (fs.existsSync(sidecarRuntimeDir)) return;
  console.log("Building SignalASI Link sidecar runtime...");
  runGradle(["-p", sidecarDir, "installDist", "--no-daemon"], { cwd: workspaceRoot });
}

function pythonCanImportBackendDeps(pythonExe) {
  try {
    execFileSync(
      pythonExe,
      ["-c", "import fastapi, multipart, uvicorn, sqlalchemy, websockets, paho.mqtt.client, qrcode; print('backend deps ok')"],
      { stdio: "ignore", windowsHide: true }
    );
    return true;
  } catch {
    return false;
  }
}

function ensureRuntimePythonVenv() {
  if (process.env.SIGNALASI_PYTHON_VENV) {
    return process.env.SIGNALASI_PYTHON_VENV;
  }

  const runtimePython = path.join(runtimePythonDir, "Scripts", "python.exe");
  if (!fs.existsSync(runtimePython)) {
    const seedPython = findPythonExecutable();
    if (!seedPython) {
      throw new Error("Python not found. Set SIGNALASI_PYTHON or install Python 3.");
    }
    console.log(`Creating slim backend Python venv with ${seedPython}...`);
    run(seedPython, ["-m", "venv", runtimePythonDir]);
  }

  if (!pythonCanImportBackendDeps(runtimePython)) {
    console.log("Installing backend dependencies into slim Python venv...");
    run(runtimePython, ["-m", "pip", "install", "--upgrade", "pip"]);
    run(runtimePython, ["-m", "pip", "install", "-r", path.join(backendSrc, "requirements.txt")]);
  }

  return runtimePythonDir;
}

requirePath(electronDist, "Electron runtime");
requirePath(backendSrc, "SignalASI backend");
ensureSignalSidecarRuntime();
requirePath(sidecarRuntimeDir, "SignalASI Link sidecar runtime");

removeIfExists(packageDir);
fs.mkdirSync(packageDir, { recursive: true });

copyRecursive(electronDist, packageDir, {
  ignore: (_full, entry) => entry === "default_app.asar"
});

const electronExe = path.join(packageDir, "electron.exe");
const signalExe = path.join(packageDir, `${appName}.exe`);
if (fs.existsSync(electronExe)) {
  fs.renameSync(electronExe, signalExe);
}

copyRecursive(path.join(root, "src"), path.join(appDir, "src"));
copyRecursive(path.join(root, "assets"), path.join(appDir, "assets"));
copyRecursive(path.join(root, "scripts"), path.join(appDir, "scripts"), {
  ignore: (_full, entry) => entry === "package-win.js"
});
copyRecursive(path.join(root, "docs"), path.join(appDir, "docs"));
writeJson(path.join(appDir, "package.json"), {
  name: "signalasi-desktop",
  version: packageMetadata.version,
  main: "src/main.js",
  private: true,
  scripts: {
    check: "node scripts/check.js",
    "status:connectors": "node scripts/connector-status.js",
    "smoke:pairing": "node scripts/smoke-pairing.js",
    "smoke:ui": "node scripts/smoke-ui.js",
    "smoke:android-ui": "node scripts/smoke-android-ui.js",
    "smoke:android-friends": "node scripts/smoke-android-friends.js",
    "smoke:android-contact-rename": "node scripts/smoke-android-contact-rename.js",
    "smoke:android-contact-tags": "node scripts/smoke-android-contact-tags.js",
    "smoke:android-language": "node scripts/smoke-android-language.js",
    "smoke:android-cloud-models": "node scripts/smoke-android-cloud-models.js",
    "smoke:android-background": "node scripts/smoke-android-background-message.js",
    "smoke:android-agent-replies": "node scripts/smoke-android-agent-replies.js",
    "smoke:android-backup": "node scripts/smoke-android-backup-roundtrip.js",
    "smoke:android-voice-reply": "node scripts/smoke-android-voice-reply.js",
    "smoke:android-voice-settings": "node scripts/smoke-android-voice-settings.js",
    "smoke:mqtt-persistence": "node scripts/smoke-mqtt-persistence.js",
    "smoke:agent-push": "node scripts/smoke-agent-push.js",
    "smoke:voice-stt": "node scripts/smoke-voice-stt.js",
    "smoke:e2e": "node scripts/smoke-e2e.js",
    "smoke:packaged": "node scripts/smoke-packaged.js",
    smoke: "node scripts/smoke.js"
  }
});

for (const file of backendFiles) {
  copyRecursive(path.join(backendSrc, file), path.join(packagedBackendDir, file));
}
copyRecursive(
  sidecarRuntimeDir,
  path.join(packagedBackendDir, "signal_sidecar", "build", "install", sidecarRuntimeName)
);

if (bundlePython) {
  const pythonVenvSrc = ensureRuntimePythonVenv();
  requirePath(pythonVenvSrc || "", "Bundled Python source venv");
  copyRecursive(pythonVenvSrc, bundledPythonDir, {
    ignore: (_full, entry) => entry === "__pycache__" || entry === ".pytest_cache" || entry.endsWith(".pyc")
  });
}

const iconPath = path.join(root, "assets", "signalasi.ico");
if (fs.existsSync(iconPath)) {
  const rcedit = findRceditExecutable();
  if (rcedit) {
    try {
      run(rcedit, [signalExe, "--set-icon", iconPath]);
    } catch (error) {
      console.warn(`Unable to embed the executable icon; continuing with the window icon: ${error.message}`);
    }
  } else {
    console.warn("rcedit not found; packaged exe will use the window icon but may keep the default file icon.");
  }
}

writeJson(path.join(packagedBackendDir, "signalasi_agents.json"), {
  commands: {
    hermes: "hermes chat -q",
    codex: "codex exec --skip-git-repo-check --ephemeral --model gpt-5.6-sol -c model_reasoning_effort=\"low\" -",
    claude: "claude -p",
    "custom-agent": ""
  },
  local_model: {
    name: "Local LLM",
    provider: "auto",
    url: "",
    model: "qwen2.5:7b",
    api_key: ""
  },
  custom_agent: {
    name: "Custom Agent"
  },
  custom_agents: []
});

fs.writeFileSync(
  path.join(packageDir, "install-backend-deps.bat"),
  [
    "@echo off",
    "setlocal",
    "cd /d %~dp0",
    "set PYTHON_EXE=%~dp0resources\\python\\venv\\Scripts\\python.exe",
    "if not exist \"%PYTHON_EXE%\" set PYTHON_EXE=python",
    "echo Installing SignalASI backend Python dependencies...",
    "\"%PYTHON_EXE%\" -m pip install -r resources\\signalasi-link\\backend\\requirements.txt",
    "if errorlevel 1 (",
    "  echo.",
    "  echo Failed to install backend dependencies. Install Python 3 and pip, then run this file again.",
    "  pause",
    "  exit /b 1",
    ")",
    "echo.",
    "echo Backend dependencies installed.",
    "pause",
    "endlocal"
  ].join("\r\n"),
  "utf8"
);

fs.writeFileSync(
  path.join(packageDir, "signalasi-notify.bat"),
  [
    "@echo off",
    "setlocal",
    "cd /d %~dp0resources\\signalasi-link\\backend",
    "set PYTHON_EXE=%~dp0resources\\python\\venv\\Scripts\\python.exe",
    "if not exist \"%PYTHON_EXE%\" set PYTHON_EXE=python",
    "\"%PYTHON_EXE%\" signalasi_notify.py %*",
    "exit /b %errorlevel%"
  ].join("\r\n"),
  "utf8"
);

fs.writeFileSync(
  path.join(packageDir, "README.txt"),
  [
    "SignalASI Desktop portable package",
    "",
    `Run \"${appName}.exe\" to start the desktop connector.`,
    "The mobile pairing route is /signalasi/verify.",
    "Agents can push messages with signalasi-notify.bat codex \"Task complete\".",
    "This package includes the Python backend source and the built Signal sidecar runtime.",
    bundlePython
      ? "This package includes a bundled Python venv for the FastAPI backend."
      : "A local Python 3 installation is still required to run the FastAPI backend.",
    "If the Runtime requirements panel reports missing Python packages, run install-backend-deps.bat.",
    ""
  ].join("\r\n"),
  "utf8"
);

console.log(`Packaged ${appName} at ${packageDir}`);
console.log(`Bundled Python: ${bundlePython ? "yes" : "no"}`);
releaseLock();
