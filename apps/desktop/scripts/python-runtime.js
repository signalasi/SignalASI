const { spawnSync } = require("node:child_process");
const fs = require("node:fs");
const os = require("node:os");
const path = require("node:path");

const desktopRoot = path.resolve(__dirname, "..");
const backendDir = path.join(desktopRoot, "core", "signalasi-link", "backend");
const REQUIRED_BACKEND_IMPORTS = [
  "cryptography",
  "fastapi",
  "multipart",
  "uvicorn",
  "sqlalchemy",
  "websockets",
  "paho.mqtt.client",
  "qrcode"
];

let cachedPython = "";

function runtimePython(relativeRoot) {
  return process.platform === "win32"
    ? path.join(relativeRoot, "Scripts", "python.exe")
    : path.join(relativeRoot, "bin", "python");
}

function pythonCandidates() {
  return [
    process.env.SIGNALASI_PYTHON,
    runtimePython(path.join(desktopRoot, ".runtime-python", "venv")),
    runtimePython(path.join(desktopRoot, "resources", "python", "venv")),
    "python",
    "python3",
    path.join(os.homedir(), "AppData", "Local", "hermes", "hermes-agent", "venv", "Scripts", "python.exe"),
    path.join(os.homedir(), "AppData", "Roaming", "uv", "python", "cpython-3.11-windows-x86_64-none", "python.exe")
  ].filter((candidate, index, all) => candidate && all.indexOf(candidate) === index);
}

function localPathExists(candidate) {
  if (!path.isAbsolute(candidate) && !candidate.includes("/") && !candidate.includes("\\")) {
    return true;
  }
  return fs.existsSync(candidate);
}

function probePython(candidate) {
  if (!localPathExists(candidate)) {
    return { ok: false, detail: "not found" };
  }
  const imports = REQUIRED_BACKEND_IMPORTS.join(", ");
  const result = spawnSync(
    candidate,
    ["-c", `import ${imports}; print("signalasi-backend-python-ok")`],
    {
      cwd: backendDir,
      encoding: "utf8",
      windowsHide: true,
      timeout: 15000
    }
  );
  if (!result.error && result.status === 0) {
    return { ok: true, detail: result.stdout.trim() };
  }
  const detail = [
    result.error?.message,
    result.stderr,
    result.stdout
  ].filter(Boolean).join(" ").replace(/\s+/g, " ").trim();
  return { ok: false, detail: detail.slice(0, 240) || `exit ${result.status}` };
}

function findBackendPython() {
  if (cachedPython) return cachedPython;
  const failures = [];
  const explicit = String(process.env.SIGNALASI_PYTHON || "").trim();
  for (const candidate of pythonCandidates()) {
    const probe = probePython(candidate);
    if (probe.ok) {
      cachedPython = candidate;
      return candidate;
    }
    failures.push(`${candidate}: ${probe.detail}`);
    if (explicit && candidate === explicit) {
      throw new Error(
        `SIGNALASI_PYTHON cannot load Desktop backend dependencies: ${failures[0]}`
      );
    }
  }
  throw new Error(
    `No Python runtime can load the SignalASI Desktop backend. ` +
    `Install apps/desktop/core/signalasi-link/backend/requirements.txt or set SIGNALASI_PYTHON. ` +
    `Checked: ${failures.join(" | ")}`
  );
}

module.exports = {
  REQUIRED_BACKEND_IMPORTS,
  findBackendPython,
  probePython
};
