const { execFileSync } = require("node:child_process");
const path = require("node:path");
const { findBackendPython } = require("./python-runtime");
const { withSignalasiLock } = require("./smoke-lock");

const root = path.resolve(__dirname, "..");
const backendDir = path.join(root, "core", "signalasi-link", "backend");

function main() {
  console.log("[pairing-smoke] running clean-break Link v1 protocol and multi-client tests");
  execFileSync(
    findBackendPython(),
    ["-m", "unittest", "-v", "test_link_protocol.py", "test_link_pairing_integration.py"],
    { cwd: backendDir, windowsHide: true, stdio: "inherit" }
  );
  console.log("[pairing-smoke] SignalASI Link v1 pairing smoke OK");
}

withSignalasiLock("smoke:pairing", main).catch((error) => {
  console.error(`[pairing-smoke] failed: ${error.stack || error.message || error}`);
  process.exit(1);
});
