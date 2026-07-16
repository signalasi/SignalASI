const { execFile, execFileSync } = require("node:child_process");
const http = require("node:http");
const fs = require("node:fs");
const os = require("node:os");
const path = require("node:path");
const { withSignalasiLock } = require("./smoke-lock");

const root = path.resolve(__dirname, "..");
const workspaceRoot = path.resolve(root, "..");
const backendDir = path.join(root, "core", "signalasi-link", "backend");

function log(message) {
  console.log(`[agent-push-smoke] ${message}`);
}

function fail(message) {
  throw new Error(message);
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

function run(command, args, options = {}) {
  return execFileSync(command, args, {
    cwd: options.cwd || root,
    encoding: options.encoding || "utf8",
    stdio: options.stdio || "pipe",
    windowsHide: true
  });
}

function execFileAsync(command, args, options = {}) {
  return new Promise((resolve) => {
    execFile(command, args, {
      cwd: options.cwd || root,
      encoding: options.encoding || "utf8",
      windowsHide: true,
      timeout: options.timeout || 30000
    }, (error, stdout, stderr) => {
      resolve({ status: error ? (error.code || 1) : 0, stdout, stderr, error });
    });
  });
}

async function withServer(handler) {
  const requests = [];
  const server = http.createServer((req, res) => {
    let body = "";
    req.on("data", (chunk) => { body += chunk; });
    req.on("end", () => {
      requests.push({ method: req.method, url: req.url, headers: req.headers, body });
      res.setHeader("content-type", "application/json");
      res.end(JSON.stringify({ ok: true, received: JSON.parse(body || "{}") }));
    });
  });
  await new Promise((resolve, reject) => {
    server.once("error", reject);
    server.listen(0, "127.0.0.1", resolve);
  });
  try {
    const address = server.address();
    return await handler(`http://127.0.0.1:${address.port}/api/agent/push`, requests);
  } finally {
    await new Promise((resolve) => server.close(resolve));
  }
}

async function main() {
  const python = findPython();
  log("checking backend push files compile");
  run(python, ["-m", "py_compile", "main.py", "mqtt_bridge.py", "push_auth.py", "signalasi_notify.py"], { cwd: backendDir });

  log("testing /api/agent/push authorization and encrypted MQTT payload");
  run(python, ["-c", String.raw`
import json
import os
import tempfile
from fastapi import HTTPException

smoke_data_dir = tempfile.TemporaryDirectory(prefix="signalasi-agent-push-smoke-")
os.environ["SIGNALASI_DATA_DIR"] = smoke_data_dir.name

import mqtt_bridge
from main import AgentPushReq, api_agent_push
from pairing_state import record_pairing_success
from push_auth import agent_push_token

published = []

class FakeInfo:
    mid = 77
    rc = 0

class FakeClient:
    def is_connected(self):
        return True
    def publish(self, topic, payload, qos=0, retain=False):
        published.append({"topic": topic, "payload": payload, "qos": qos, "retain": retain})
        return FakeInfo()

mqtt_bridge.client = FakeClient()
mqtt_bridge.encrypt_signal_payload = lambda payload, remote_name="android": {"scheme": "signal", "debug_payload": payload}
record_pairing_success(
    fingerprint="a" * 64,
    remote_name="signalasi:smoke-client",
    client_route_id="c" * 22,
    display_name="Smoke Client",
    platform="test",
)

try:
    api_agent_push(AgentPushReq(contact_id="codex", content="bad"), x_signalasi_token="wrong")
    raise AssertionError("bad token was accepted")
except HTTPException as exc:
    assert exc.status_code == 401, exc.status_code
    assert exc.detail["code"] == "agent_push_token_invalid", exc.detail
    assert isinstance(exc.detail.get("params"), dict), exc.detail

data = api_agent_push(
    AgentPushReq(contact_id="codex", content="Task complete", source="codex", broadcast=True),
    x_signalasi_token=agent_push_token(),
)
assert data["ok"] is True and data["contact_id"] == "codex", data
assert data["code"] == "agent_push_published" and data["params"]["contact_id"] == "codex", data
assert data["params"]["client_count"] == 1, data
assert published, "no MQTT publish captured"
assert published[-1]["topic"].startswith("signalasichat/v1/"), published[-1]
assert published[-1]["topic"].endswith("/" + "c" * 22 + "/down"), published[-1]
wire = json.loads(published[-1]["payload"])
envelope = wire["debug_payload"]
assert envelope["protocol"] == "signalasi-link", envelope
assert envelope["version"] == 1, envelope
payload = envelope["payload"]
assert payload["contact_id"] == "codex", payload
assert payload["content"] == "Task complete", payload
assert payload["agent_push"] is True, payload
assert payload["source"] == "codex", payload
print("agent_push_api_ok")
`], { cwd: backendDir, stdio: "inherit" });

  log("testing signalasi_notify.py command posts with local token");
  await withServer(async (url, requests) => {
    const result = await execFileAsync(
      python,
      ["signalasi_notify.py", "research-agent", "Background", "task", "done", "--source", "research-agent", "--url", url],
      { cwd: backendDir }
    );
    if (result.status !== 0) {
      fail(`signalasi_notify.py failed: ${result.stderr || result.stdout}`);
    }
    if (requests.length !== 1) fail(`Expected one notify request, got ${requests.length}`);
    const request = requests[0];
    const body = JSON.parse(request.body);
    if (request.method !== "POST" || request.url !== "/api/agent/push") fail(`Unexpected request ${request.method} ${request.url}`);
    if (!request.headers["x-signalasi-token"]) fail("signalasi_notify.py did not send X-SignalASI-Token");
    if (body.contact_id !== "research-agent" || body.content !== "Background task done" || body.source !== "research-agent") {
      fail(`Unexpected notify body: ${JSON.stringify(body)}`);
    }
  });

  log("Agent push smoke OK");
}

withSignalasiLock("smoke:agent-push", main).catch((error) => {
  console.error(`[agent-push-smoke] failed: ${error.stack || error.message || error}`);
  process.exit(1);
});
