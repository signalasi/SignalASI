const { execFile, execFileSync, spawn } = require("node:child_process");
const http = require("node:http");
const fs = require("node:fs");
const os = require("node:os");
const path = require("node:path");
const { findBackendPython } = require("./python-runtime");
const { withSignalasiLock } = require("./smoke-lock");

const root = path.resolve(__dirname, "..");
const workspaceRoot = path.resolve(root, "..");
const backendDir = path.join(root, "core", "signalasi-link", "backend");
let backendPort = 8765;
let backendOrigin = `http://127.0.0.1:${backendPort}`;
const agentConfigPath = path.join(backendDir, "signalasi_agents.json");
const requiredBackendCapabilities = ["model_display_names", "local_model_endpoint_probe", "mobile_cloud_models", "mcp_stdio_wrapper", "multiple_custom_agents", "agent_execution_log", "api_response_codes", "agent_diagnostics_codes"];
let backendStateDir = "";

function log(message) {
  console.log(`[e2e] ${message}`);
}

function fail(message) {
  throw new Error(message);
}

function run(command, args, options = {}) {
  execFileSync(command, args, {
    stdio: options.stdio || "inherit",
    windowsHide: true,
    cwd: options.cwd || root
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

async function fetchJson(pathname, options = {}) {
  const response = await fetch(`${backendOrigin}${pathname}`, {
    headers: { "Content-Type": "application/json", ...(options.headers || {}) },
    ...options
  });
  if (!response.ok) {
    const detail = await response.text();
    throw new Error(`${pathname} returned HTTP ${response.status}: ${detail.slice(0, 500)}`);
  }
  return response.json();
}

function runBackendJson(code) {
  return new Promise((resolve, reject) => {
    execFile(findBackendPython(), ["-c", code], {
      cwd: backendDir,
      encoding: "utf8",
      windowsHide: true,
      maxBuffer: 1024 * 1024,
      env: backendStateDir ? { ...process.env, SIGNALASI_STATE_DIR: backendStateDir } : process.env,
    }, (error, stdout) => {
      if (error) {
        reject(error);
        return;
      }
      try {
        resolve(parseJsonFromBackendOutput(stdout));
      } catch (parseError) {
        reject(parseError);
      }
    });
  });
}

function parseJsonFromBackendOutput(output) {
  const lines = output.split(/\r?\n/).map((line) => line.trim()).filter(Boolean);
  const jsonLine = [...lines].reverse().find((line) => line.startsWith("{") || line.startsWith("["));
  if (!jsonLine) {
    fail(`Backend Python command did not emit JSON: ${output}`);
  }
  return JSON.parse(jsonLine);
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

function assertNoE2eConfigLeak() {
  const current = readConfigSnapshot() || "";
  for (const marker of ["fake-local-e2e", "fake_claude.py", "fake_custom_agent.py", "fake_mcp_server.py", "E2E Local Model", "E2E Broken Local Model", "research-agent", "Research Agent", "signalasi-e2e-"]) {
    if (current.includes(marker)) {
      fail(`E2E leaked temporary connector config: ${marker}`);
    }
  }
}

async function waitForBackend() {
  for (let attempt = 0; attempt < 24; attempt += 1) {
    try {
      return await fetchJson("/api/agents/diagnostics");
    } catch {
      await new Promise((resolve) => setTimeout(resolve, 750));
    }
  }
  fail("Backend did not respond on :8765");
}

async function startBackendIfNeeded(stateDir = "") {
  if (!stateDir) {
    try {
      const diagnostics = await fetchJson("/api/agents/diagnostics");
      if (backendHasCapabilities(diagnostics) && backendIsCurrentSource(diagnostics)) {
        return undefined;
      }
      log(`stale backend detected or foreign backend on :${backendPort}; restarting current source backend`);
      stopBackendPort();
      await new Promise((resolve) => setTimeout(resolve, 1000));
    } catch {
      // Start below.
    }
  } else {
    stopBackendPort();
    await new Promise((resolve) => setTimeout(resolve, 1000));
  }
  const python = findBackendPython();
  const child = spawn(python, ["-m", "uvicorn", "main:app", "--host", "127.0.0.1", "--port", String(backendPort)], {
    cwd: backendDir,
    windowsHide: true,
    stdio: process.env.SIGNALASI_E2E_DEBUG === "1" ? "inherit" : "ignore",
    env: stateDir ? {
      ...process.env,
      SIGNALASI_STATE_DIR: stateDir,
      SIGNALASI_DISABLE_EXTERNAL_SERVICES: process.env.SIGNALASI_E2E_MOBILE === "1" ? "0" : "1",
    } : process.env,
  });
  await waitForBackend();
  return child;
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

function createFakeClaude(tmpDir) {
  const script = path.join(tmpDir, "fake_claude.py");
  fs.writeFileSync(
    script,
    [
      "import sys",
      "if sys.argv[1:] != ['-']:",
      "    raise SystemExit('CLAUDE_ARGV_LEAK:' + repr(sys.argv[1:]))",
      "prompt = sys.stdin.read().strip()",
      "print('CLAUDE_SMOKE_OK:' + (prompt[:32] or 'empty'))"
    ].join("\n"),
    "utf8"
  );
  const python = findBackendPython().replaceAll("\\", "/");
  return `"${python}" "${script.replaceAll("\\", "/")}" -`;
}

function createFakeCustomAgent(tmpDir) {
  const script = path.join(tmpDir, "fake_custom_agent.py");
  fs.writeFileSync(
    script,
    [
      "import sys",
      "if sys.argv[1:] != ['-']:",
      "    raise SystemExit('CUSTOM_AGENT_ARGV_LEAK:' + repr(sys.argv[1:]))",
      "prompt = sys.stdin.read().strip()",
      "print('CUSTOM_AGENT_OK:' + (prompt[:32] or 'empty'))"
    ].join("\n"),
    "utf8"
  );
  const python = findBackendPython().replaceAll("\\", "/");
  return `"${python}" "${script.replaceAll("\\", "/")}" -`;
}

function createFakeMcpServer(tmpDir) {
  const script = path.join(tmpDir, "fake_mcp_server.py");
  fs.writeFileSync(
    script,
    [
      "import json, sys",
      "def read_frame():",
      "    headers = {}",
      "    while True:",
      "        line = sys.stdin.buffer.readline()",
      "        if not line: return None",
      "        line = line.decode('ascii', 'replace').strip()",
      "        if not line: break",
      "        if ':' in line:",
      "            k, v = line.split(':', 1)",
      "            headers[k.lower()] = v.strip()",
      "    body = sys.stdin.buffer.read(int(headers.get('content-length', '0')))",
      "    return json.loads(body.decode('utf-8'))",
      "def write_frame(payload):",
      "    body = json.dumps(payload, separators=(',', ':')).encode('utf-8')",
      "    sys.stdout.buffer.write(f'Content-Length: {len(body)}\\r\\n\\r\\n'.encode('ascii') + body)",
      "    sys.stdout.buffer.flush()",
      "while True:",
      "    msg = read_frame()",
      "    if msg is None: break",
      "    if 'id' not in msg: continue",
      "    method = msg.get('method')",
      "    if method == 'initialize':",
      "        write_frame({'jsonrpc':'2.0','id':msg['id'],'result':{'protocolVersion':'2024-11-05','capabilities':{},'serverInfo':{'name':'fake-mcp','version':'0.1'}}})",
      "    elif method == 'tools/list':",
      "        write_frame({'jsonrpc':'2.0','id':msg['id'],'result':{'tools':[{'name':'echo','description':'Echo prompt','inputSchema':{'type':'object','properties':{'prompt':{'type':'string'}}}}]}})",
      "    elif method == 'tools/call':",
      "        prompt = (msg.get('params') or {}).get('arguments', {}).get('prompt', '')",
      "        write_frame({'jsonrpc':'2.0','id':msg['id'],'result':{'content':[{'type':'text','text':'MCP_E2E_OK:' + prompt[-64:]}]}})",
      "    else:",
      "        write_frame({'jsonrpc':'2.0','id':msg['id'],'error':{'code':-32601,'message':'unknown method'}})"
    ].join("\n"),
    "utf8"
  );
  return script;
}

function createMcpWrapperCommand(fakeMcpServer) {
  const python = findBackendPython().replaceAll("\\", "/");
  const wrapper = path.join(backendDir, "mcp_agent_wrapper.py").replaceAll("\\", "/");
  return `"${python}" "${wrapper}" --server-python "${fakeMcpServer.replaceAll("\\", "/")}" --tool echo -`;
}

function startFakeModelServer() {
  const server = http.createServer((req, res) => {
    let body = "";
    req.on("data", (chunk) => { body += chunk; });
    req.on("end", () => {
      res.setHeader("content-type", "application/json");
      if (req.method === "GET" && req.url.includes("/models")) {
        res.end(JSON.stringify({ data: [{ id: "fake-local-e2e" }] }));
        return;
      }
      res.end(JSON.stringify({
        choices: [{
          message: {
            content: "LOCAL_E2E_OK"
          }
        }]
      }));
    });
  });
  return new Promise((resolve, reject) => {
    server.once("error", reject);
    server.listen(0, "127.0.0.1", () => {
      const address = server.address();
      resolve({ server, port: typeof address === "object" ? address.port : 0 });
    });
  });
}

async function main() {
  log("checking syntax before e2e");
  run(process.execPath, ["--check", path.join(root, "scripts", "smoke-e2e.js")]);
  run(findBackendPython(), ["-m", "py_compile", "agent_gateway.py", "agent_config.py", "main.py", "custom_agent_stdio.py", "mcp_agent_wrapper.py"], { cwd: backendDir });

  log("starting or reusing backend");
  const tmpDir = fs.mkdtempSync(path.join(os.tmpdir(), "signalasi-e2e-"));
  backendStateDir = path.join(tmpDir, "state");
  backendPort = await findFreeBackendPort();
  backendOrigin = `http://127.0.0.1:${backendPort}`;
  const startedBackend = await startBackendIfNeeded(backendStateDir);
  const { server, port } = await startFakeModelServer();
  let oldConfig;
  const configSnapshot = readConfigSnapshot();
  try {
    oldConfig = await fetchJson("/api/agents/config");
    const fakeClaudeCommand = createFakeClaude(tmpDir);
    const fakeCustomAgentCommand = createFakeCustomAgent(tmpDir);
    const fakeMcpServer = createFakeMcpServer(tmpDir);
    log("configuring fake Claude CLI, custom agent, and local model");
    await fetchJson("/api/agents/config", {
      method: "POST",
      body: JSON.stringify({
        commands: {
          ...oldConfig.commands,
          claude: fakeClaudeCommand,
          "custom-agent": fakeCustomAgentCommand
        },
        local_model: {
          name: "E2E Local Model",
          provider: "openai",
          url: `http://127.0.0.1:${port}/local/v1/chat/completions`,
          model: "fake-local-e2e",
          api_key: ""
        },
        custom_agent: {
          name: "E2E Custom Agent"
        },
        custom_agents: [
          {
            id: "research-agent",
            name: "Research Agent",
            command: fakeCustomAgentCommand
          }
        ]
      })
    });

    const diagnostics = await fetchJson("/api/agents/diagnostics");
    for (const id of ["claude", "local-llm", "custom-agent", "research-agent"]) {
      if (!diagnostics.ready.includes(id)) {
        const status = diagnostics.agents.find(agent => agent.id === id);
        fail(`${id} should be ready under simulated configuration: ${JSON.stringify(status)}`);
      }
    }
    const customAgentStatus = diagnostics.agents.find((agent) => agent.id === "custom-agent");
    if (!customAgentStatus || customAgentStatus.name !== "E2E Custom Agent") {
      fail(`Custom Agent display name was not propagated: ${JSON.stringify(customAgentStatus)}`);
    }
    const localStatus = diagnostics.agents.find((agent) => agent.id === "local-llm");
    if (!localStatus || localStatus.name !== "E2E Local Model") {
      fail(`Local model display name was not propagated: ${JSON.stringify(localStatus)}`);
    }
    const researchStatus = diagnostics.agents.find((agent) => agent.id === "research-agent");
    if (!researchStatus || researchStatus.name !== "Research Agent" || researchStatus.mobile_contact_id !== "research-agent") {
      fail(`Dynamic custom agent was not exposed: ${JSON.stringify(researchStatus)}`);
    }
    for (const agent of [customAgentStatus, localStatus, researchStatus]) {
      if (!agent.detail_code || !agent.detail_params || !agent.setup_code || !agent.setup_params || !agent.pairing_code || !agent.pairing_params) {
        fail(`Agent diagnostics missing structured i18n fields: ${JSON.stringify(agent)}`);
      }
      if (agent.detail_params.agent_id !== agent.id || agent.setup_params.agent_id !== agent.id || agent.pairing_params.agent_id !== agent.id) {
        fail(`Agent diagnostics params did not preserve agent identity: ${JSON.stringify(agent)}`);
      }
    }
    const mobileAgents = await runBackendJson("import json; from mqtt_bridge import mobile_connector_agents; print(json.dumps(mobile_connector_agents(), ensure_ascii=False))");
    const mobileResearch = mobileAgents.find((agent) => agent.agent_id === "research-agent");
    if (!mobileResearch || mobileResearch.name !== "Research Agent" || mobileResearch.status !== "ready" || mobileResearch.kind !== "custom-cli") {
      fail(`Dynamic custom agent was not included in mobile connector payload: ${JSON.stringify(mobileResearch)}`);
    }
    const traceProbe = await runBackendJson("import json; from mqtt_bridge import _delivery_trace, _trace_event; print(json.dumps(_delivery_trace({'delivery_trace':[{'stage':'created','at':1,'detail':'client'}]}, _trace_event('desktop_received','signalasichat/v1/server/client/up'), _trace_event('agent_started','codex')), ensure_ascii=False))");
    const traceStages = traceProbe.map((event) => event.stage);
    for (const stage of ["created", "desktop_received", "agent_started"]) {
      if (!traceStages.includes(stage)) {
        fail(`Delivery trace helper dropped ${stage}: ${JSON.stringify(traceProbe)}`);
      }
    }
    const ackProbe = await runBackendJson("import json; from mqtt_bridge import build_delivery_ack_payload; print(json.dumps(build_delivery_ack_payload({'source_message_id':'42','contact_id':'codex','agent_id':'codex','delivery_trace':[{'stage':'desktop_reply_publish_queued','at':2,'detail':'down'}]}, 'desktop_reply_broker_ack', 'signalasichat/v1/server/client/down'), ensure_ascii=False))");
    const ackStages = (ackProbe.delivery_trace || []).map((event) => event.stage);
    if (ackProbe.type !== "delivery_ack" || ackProbe.source_message_id !== "42" || !ackStages.includes("desktop_reply_broker_ack")) {
      fail(`Delivery ack payload was not shaped correctly: ${JSON.stringify(ackProbe)}`);
    }

    log("checking local model endpoint probe rejects unreachable URLs");
    await fetchJson("/api/agents/config", {
      method: "POST",
      body: JSON.stringify({
        commands: {
          ...oldConfig.commands,
          claude: fakeClaudeCommand,
          "custom-agent": fakeCustomAgentCommand
        },
        local_model: {
          name: "E2E Broken Local Model",
          provider: "openai",
          url: "http://127.0.0.1:9/local/v1/chat/completions",
          model: "fake-local-e2e",
          api_key: ""
        },
        custom_agent: {
          name: "E2E Custom Agent"
        },
        custom_agents: [
          {
            id: "research-agent",
            name: "Research Agent",
            command: fakeCustomAgentCommand
          }
        ]
      })
    });
    const brokenConfig = await fetchJson("/api/agents/config");
    if (brokenConfig.local_model?.name !== "E2E Broken Local Model" ||
        brokenConfig.local_model?.url !== "http://127.0.0.1:9/local/v1/chat/completions") {
      fail(`Broken local model configuration was not persisted: ${JSON.stringify(brokenConfig.local_model)}`);
    }
    const brokenDiagnostics = await fetchJson("/api/agents/diagnostics");
    if (brokenDiagnostics.ready.includes("local-llm")) {
      const status = brokenDiagnostics.agents.find(agent => agent.id === "local-llm");
      fail(`Local model should not be ready when configured endpoint is unreachable: ${JSON.stringify(status)}`);
    }
    await fetchJson("/api/agents/config", {
      method: "POST",
      body: JSON.stringify({
        commands: {
          ...oldConfig.commands,
          claude: fakeClaudeCommand,
          "custom-agent": fakeCustomAgentCommand
        },
        local_model: {
          name: "E2E Local Model",
          provider: "openai",
          url: `http://127.0.0.1:${port}/local/v1/chat/completions`,
          model: "fake-local-e2e",
          api_key: ""
        },
        custom_agent: {
          name: "E2E Custom Agent"
        },
        custom_agents: [
          {
            id: "research-agent",
            name: "Research Agent",
            command: fakeCustomAgentCommand
          }
        ]
      })
    });

    log("testing simulated Claude, custom agent, and local LLM");
    const claude = await fetchJson("/api/agents/claude/test", {
      method: "POST",
      body: JSON.stringify({ prompt: "SignalASI Claude e2e prompt" })
    });
    const local = await fetchJson("/api/agents/local-llm/test", {
      method: "POST",
      body: JSON.stringify({ prompt: "SignalASI Local e2e prompt" })
    });
    const custom = await fetchJson("/api/agents/custom-agent/test", {
      method: "POST",
      body: JSON.stringify({ prompt: "SignalASI Custom e2e prompt" })
    });
    const research = await fetchJson("/api/agents/research-agent/test", {
      method: "POST",
      body: JSON.stringify({ prompt: "SignalASI Research e2e prompt" })
    });

    if (!String(claude.reply || "").startsWith("CLAUDE_SMOKE_OK")) fail(`Unexpected Claude reply: ${claude.reply}`);
    if (local.reply !== "LOCAL_E2E_OK") fail(`Unexpected local reply: ${local.reply}`);
    if (!String(custom.reply || "").startsWith("CUSTOM_AGENT_OK")) fail(`Unexpected custom agent reply: ${custom.reply}`);
    if (!String(research.reply || "").startsWith("CUSTOM_AGENT_OK")) fail(`Unexpected dynamic custom agent reply: ${research.reply}`);

    log("testing included MCP wrapper through Custom Agent");
    await fetchJson("/api/agents/config", {
      method: "POST",
      body: JSON.stringify({
        commands: {
          ...oldConfig.commands,
          claude: fakeClaudeCommand,
          "custom-agent": createMcpWrapperCommand(fakeMcpServer)
        },
        local_model: {
          name: "E2E Local Model",
          provider: "openai",
          url: `http://127.0.0.1:${port}/local/v1/chat/completions`,
          model: "fake-local-e2e",
          api_key: ""
        },
        custom_agent: {
          name: "E2E MCP Agent"
        },
        custom_agents: [
          {
            id: "research-agent",
            name: "Research Agent",
            command: fakeCustomAgentCommand
          }
        ]
      })
    });
    const mcpDiagnostics = await fetchJson("/api/agents/diagnostics");
    const mcpStatus = mcpDiagnostics.agents.find((agent) => agent.id === "custom-agent");
    if (!mcpStatus || mcpStatus.name !== "E2E MCP Agent" || !mcpDiagnostics.ready.includes("custom-agent")) {
      fail(`MCP Custom Agent status was not ready: ${JSON.stringify(mcpStatus)}`);
    }
    const mcpCustom = await fetchJson("/api/agents/custom-agent/test", {
      method: "POST",
      body: JSON.stringify({ prompt: "SignalASI MCP e2e prompt" })
    });
    if (!String(mcpCustom.reply || "").startsWith("MCP_E2E_OK:") ||
        !String(mcpCustom.reply || "").includes("SignalASI MCP e2e prompt")) {
      fail(`Unexpected MCP custom agent reply: ${mcpCustom.reply}`);
    }

    log("running self-test with simulated agent calls and no mobile delivery");
    const selfTest = await fetchJson("/api/agents/self-test", {
      method: "POST",
      body: JSON.stringify({ include_agent_calls: true, include_mobile_delivery: false })
    });
    for (const id of ["claude", "local-llm", "custom-agent", "research-agent"]) {
      const item = selfTest.results.find((result) => result.id === id);
      if (!item || item.agent_call.ok !== true) {
        fail(`${id} self-test agent call was not ok`);
      }
    }

    log("checking Agent execution audit log");
    const executionLog = await fetchJson("/api/agents/execution-log?limit=40");
    if (!Array.isArray(executionLog.entries) || executionLog.entries.length < 4) {
      fail(`Execution log did not include recent agent calls: ${JSON.stringify(executionLog)}`);
    }
    const loggedIds = new Set(executionLog.entries.map((entry) => entry.contact_id));
    for (const id of ["claude", "local-llm", "custom-agent", "research-agent"]) {
      if (!loggedIds.has(id)) {
        fail(`Execution log missing contact: ${id}; present=${JSON.stringify([...loggedIds])}; path=${executionLog.path}`);
      }
    }
    const serializedLog = JSON.stringify(executionLog);
    for (const leaked of [
      "SignalASI Claude e2e prompt",
      "SignalASI Local e2e prompt",
      "SignalASI Custom e2e prompt",
      "SignalASI Research e2e prompt",
      "SignalASI MCP e2e prompt"
    ]) {
      if (serializedLog.includes(leaked)) {
        fail(`Execution log leaked raw prompt text: ${leaked}`);
      }
    }
    if (!executionLog.entries.some((entry) => entry.prompt_sha256 && entry.prompt_chars > 0 && entry.permission)) {
      fail("Execution log entries must include prompt hash, prompt length, and permission label");
    }

    if (process.env.SIGNALASI_E2E_MOBILE === "1") {
      log("sending encrypted diagnostics to phone for simulated contacts");
      for (const id of ["claude", "local-llm", "custom-agent", "research-agent"]) {
        const token = `E2E_MOBILE_${id}_${Date.now()}`;
        const delivery = await fetchJson("/api/mobile/test-message", {
          method: "POST",
          body: JSON.stringify({ contact_id: id, content: token })
        });
        if (!delivery.ok) fail(`Mobile delivery failed for ${id}: ${JSON.stringify(delivery)}`);
      }
    }

    log("e2e smoke OK");
  } finally {
    server.close();
    if (oldConfig) {
      try {
        const restorePayload = configSnapshot === undefined ? oldConfig : JSON.parse(configSnapshot);
        await fetchJson("/api/agents/config", {
          method: "POST",
          body: JSON.stringify(restorePayload)
        });
      } catch {
        restoreConfigSnapshot(configSnapshot);
      }
    }
    await stopChild(startedBackend);
    fs.rmSync(tmpDir, { recursive: true, force: true, maxRetries: 10, retryDelay: 100 });
    assertNoE2eConfigLeak();
    backendStateDir = "";
  }
}

withSignalasiLock("smoke:e2e", main).catch((error) => {
  console.error(`[e2e] failed: ${error.stack || error.message || error}`);
  process.exit(1);
});
