const test = require("node:test");
const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const vm = require("node:vm");

const source = fs.readFileSync(path.join(__dirname, "../src/main.js"), "utf8");
const functionSource = source.slice(source.indexOf("async function backendStatus()"),
  source.indexOf("function desktopTaskStreamToken()"));

async function status(payload) {
  const context = vm.createContext({
    fetch: async () => ({ ok: true, status: 200, json: async () => payload }),
    BACKEND_ORIGIN: "http://127.0.0.1:8765", PAIRING_URL: "pairing", BACKEND_DIR: "backend"
  });
  vm.runInContext(functionSource, context);
  return context.backendStatus();
}

test("broker connectivity is distinct from encrypted communication readiness", async () => {
  const result = await status({ protocol: "GalaxySSI Link Protocol", connector: "GalaxySSI Desktop",
    ready: false, message_bridge: { connected: true, running: true },
    signal_sidecar: { ready: false, error_code: "signal_runtime_missing" } });
  assert.equal(result.running, true);
  assert.equal(result.messageBridgeConnected, true);
  assert.equal(result.messageBridgeReady, false);
  assert.equal(result.messageBridgeError, "signal_runtime_missing");
});

test("readiness requires an explicit true result rather than a stale older backend field", async () => {
  for (const value of [undefined, false, "true", 1, true]) {
    const result = await status({ protocol: "GalaxySSI Link Protocol", connector: "GalaxySSI Desktop",
      ready: value, message_bridge: { connected: true, running: true } });
    assert.equal(result.messageBridgeReady, value === true);
  }
});

test("workspace badge uses full readiness, not only the broker connection", () => {
  const workspace = fs.readFileSync(path.join(__dirname, "../src/renderer/workspace.js"), "utf8");
  const refresh = workspace.slice(workspace.indexOf("async function refreshBackend()"),
    workspace.indexOf("function renderAgentMemoryGroup("));
  assert.match(refresh, /const online = backendRunning && state\.backend\?\.messageBridgeReady === true/);
});
