const { execFileSync } = require("node:child_process");
const os = require("node:os");
const path = require("node:path");
const fs = require("node:fs");
const { withSignalasiLock } = require("./smoke-lock");

const root = path.resolve(__dirname, "..");
const backendDir = path.join(root, "core", "signalasi-link", "backend");

function findPython() {
  const candidates = [
    path.join(root, ".runtime-python", "venv", "Scripts", "python.exe"),
    path.join(os.homedir(), "AppData", "Roaming", "uv", "python", "cpython-3.11-windows-x86_64-none", "python.exe"),
    "python"
  ];
  return candidates.find((candidate) => candidate === "python" || fs.existsSync(candidate)) || "python";
}

function main() {
  console.log("[pairing-smoke] running clean-break Link v1 protocol and multi-client tests");
  execFileSync(
    findPython(),
    ["-m", "unittest", "-v", "test_link_protocol.py", "test_link_pairing_integration.py"],
    { cwd: backendDir, windowsHide: true, stdio: "inherit" }
  );
  console.log("[pairing-smoke] SignalASI Link v1 pairing smoke OK");
}

withSignalasiLock("smoke:pairing", main).catch((error) => {
  console.error(`[pairing-smoke] failed: ${error.stack || error.message || error}`);
  process.exit(1);
});
