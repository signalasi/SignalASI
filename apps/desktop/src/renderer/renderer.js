const backendBadge = document.getElementById("backendBadge");
const pairingFrame = document.getElementById("pairingFrame");
const agentList = document.getElementById("agentList");
const refreshPairing = document.getElementById("refreshPairing");
const detectAgentsButton = document.getElementById("detectAgents");
const saveConfigButton = document.getElementById("saveConfig");
const saveModelConfigButton = document.getElementById("saveModelConfig");
const runTestButton = document.getElementById("runTest");
const sendMobileTestButton = document.getElementById("sendMobileTest");
const syncStatusToPhoneButton = document.getElementById("syncStatusToPhone");
const syncStatusToPhoneSecondaryButton = document.getElementById("syncStatusToPhoneSecondary");
const runSelfTestButton = document.getElementById("runSelfTest");
const includeAgentCalls = document.getElementById("includeAgentCalls");
const openClaudeDocsButton = document.getElementById("openClaudeDocs");
const copyDefaultCommandsButton = document.getElementById("copyDefaultCommands");
const useCustomStdinTemplateButton = document.getElementById("useCustomStdinTemplate");
const useCustomMcpTemplateButton = document.getElementById("useCustomMcpTemplate");
const addCustomAgentButton = document.getElementById("addCustomAgent");
const customAgentsList = document.getElementById("customAgentsList");
const copyPairingLinkButton = document.getElementById("copyPairingLink");
const copyPairingLinkSecondaryButton = document.getElementById("copyPairingLinkSecondary");
const copyContactMapButton = document.getElementById("copyContactMap");
const jumpSelfTestButton = document.getElementById("jumpSelfTest");
const pairingSelfTestButton = document.getElementById("pairingSelfTest");
const forgetPhoneButton = document.getElementById("forgetPhone");
const testClaudeConfigButton = document.getElementById("testClaudeConfig");
const testCustomAgentConfigButton = document.getElementById("testCustomAgentConfig");
const useOllamaPresetButton = document.getElementById("useOllamaPreset");
const useLmStudioPresetButton = document.getElementById("useLmStudioPreset");
const testLocalModelButton = document.getElementById("testLocalModel");
const refreshDiagnosticsButton = document.getElementById("refreshDiagnostics");
const refreshRuntimeButton = document.getElementById("refreshRuntime");
const refreshStatusMatrixButton = document.getElementById("refreshStatusMatrix");
const refreshSetupGuideButton = document.getElementById("refreshSetupGuide");
const refreshExecutionLogButton = document.getElementById("refreshExecutionLog");
const testResult = document.getElementById("testResult");
const diagnosticsResult = document.getElementById("diagnosticsResult");
const runtimeResult = document.getElementById("runtimeResult");
const executionLog = document.getElementById("executionLog");
const connectorMatrixRows = document.getElementById("connectorMatrixRows");
const statusSummary = document.getElementById("statusSummary");
const setupChecklist = document.getElementById("setupChecklist");
const setupProgressText = document.getElementById("setupProgressText");
const setupProgressDetail = document.getElementById("setupProgressDetail");
const setupProgressBar = document.getElementById("setupProgressBar");
const pairingState = document.getElementById("pairingState");
const pairingStatusDetail = document.getElementById("pairingStatusDetail");
const navButtons = Array.from(document.querySelectorAll(".nav"));
const sections = Array.from(document.querySelectorAll(".section"));
const languageSelect = document.getElementById("languageSelect");

let lastBackendRunning = false;
let currentLanguage = localStorage.getItem("signalasi.language") || "en";

let localeMessages = {};

async function loadLocale(language) {
  try {
    localeMessages = await window.signalasi.loadLocale(language);
  } catch {
    localeMessages = {};
  }
}

function t(value, vars = {}) {
  const raw = String(value ?? "");
  let translated = localeMessages[raw] || raw;
  for (const [key, replacement] of Object.entries(vars)) {
    translated = translated.replaceAll(`{${key}}`, String(replacement));
  }
  return translated;
}

const fields = {
  cmdHermes: document.getElementById("cmdHermes"),
  cmdCodex: document.getElementById("cmdCodex"),
  cmdClaude: document.getElementById("cmdClaude"),
  customAgentName: document.getElementById("customAgentName"),
  cmdCustomAgent: document.getElementById("cmdCustomAgent"),
  localModelName: document.getElementById("localModelName"),
  localProvider: document.getElementById("localProvider"),
  localUrl: document.getElementById("localUrl"),
  localModel: document.getElementById("localModel"),
  localKey: document.getElementById("localKey"),
  testAgent: document.getElementById("testAgent"),
  testPrompt: document.getElementById("testPrompt")
};

const agentLabels = {
  hermes: "Hermes Agent",
  codex: "Codex Agent",
  claude: "Claude Code",
  "local-llm": "Local LLM",
  "custom-agent": "Custom Agent"
};

const CLAUDE_SETUP_URL = "https://code.claude.com/docs/en/setup";
const DEFAULT_COMMANDS_TEXT = [
  "Hermes: hermes chat -q",
  "Codex: codex exec --skip-git-repo-check -",
  "Claude Code: claude -p",
  "Custom Agent name: Custom Agent",
  "Custom Agent command: python custom_agent_stdio.py -",
  "Additional custom agent: research-agent / Research Agent / python custom_agent_stdio.py -",
  "MCP wrapper command: python mcp_agent_wrapper.py --server \"python your_mcp_server.py\" --tool echo -"
].join("\n");
const CONTACT_MAP_TEXT = [
  "SignalASI Desktop contact map",
  "hermes -> Hermes Agent",
  "codex -> Codex Agent",
  "claude -> Claude Code",
  "local-llm -> Local LLM",
  "custom-agent -> Custom Agent",
  "custom agents -> configured dynamically in SignalASI Desktop"
].join("\n");

const simulationProof = {
  hermes: "Real CLI diagnostics + mobile delivery smoke",
  codex: "Real CLI diagnostics + mobile delivery smoke",
  claude: "Simulated by smoke:e2e until Claude CLI is installed",
  "local-llm": "Simulated by smoke:e2e until a local endpoint is configured",
  "custom-agent": "Simulated by smoke:e2e until a custom command is configured"
};

function escapeHtml(value) {
  return String(value ?? "")
    .replaceAll("&", "&amp;")
    .replaceAll("<", "&lt;")
    .replaceAll(">", "&gt;")
    .replaceAll('"', "&quot;");
}

function translateStaticText() {
  document.documentElement.lang = currentLanguage === "zh-CN" ? "zh-Hans" : "en";
  if (languageSelect) languageSelect.value = currentLanguage;
  const selector = "button,h1,h2,h3,p,strong,span,option,div.hint,div.pairing-state,pre";
  document.querySelectorAll(selector).forEach((element) => {
    if (element.children.length > 0) return;
    const original = element.dataset.i18nOriginal || element.textContent.trim();
    if (!original) return;
    element.dataset.i18nOriginal = original;
    element.textContent = t(original);
  });
  document.querySelectorAll("label > span").forEach((element) => {
    const original = element.dataset.i18nOriginal || element.textContent.trim();
    if (!original) return;
    element.dataset.i18nOriginal = original;
    element.textContent = t(original);
  });
  document.querySelectorAll("input[placeholder]").forEach((element) => {
    const original = element.dataset.i18nPlaceholder || element.getAttribute("placeholder") || "";
    element.dataset.i18nPlaceholder = original;
    element.setAttribute("placeholder", t(original));
  });
  document.querySelectorAll("[aria-label]").forEach((element) => {
    const original = element.dataset.i18nAria || element.getAttribute("aria-label") || "";
    element.dataset.i18nAria = original;
    element.setAttribute("aria-label", t(original));
  });
}

async function setLanguage(language) {
  currentLanguage = language === "en" ? "en" : "zh-CN";
  localStorage.setItem("signalasi.language", currentLanguage);
  await loadLocale(currentLanguage);
  translateStaticText();
  setBackendBadge({ running: lastBackendRunning });
}

function setBackendBadge(status) {
  lastBackendRunning = Boolean(status.running);
  backendBadge.className = `badge ${status.running ? "ok" : "bad"}`;
  backendBadge.textContent = status.running ? t("Backend online") : t("Backend offline");
}

function statusText(status) {
  if (status === "detected" || status === "ready") return t("Ready");
  if (status === "manual" || status === "needs_setup") return t("Needs setup");
  return t("Unavailable");
}

function statusClass(status) {
  if (status === "detected" || status === "ready") return "ok";
  if (status === "manual" || status === "needs_setup") return "pending";
  return "bad";
}

function renderAgents(agents) {
  agentList.innerHTML = "";
  updateTestAgentOptions(agents);
  for (const agent of agents) {
    const normalizedStatus = agent.status === "ready" ? "detected" : agent.status;
    const row = document.createElement("div");
    row.className = "agent-row";
    row.innerHTML = `
      <div class="agent-icon">${escapeHtml(agent.name.slice(0, 1))}</div>
      <div class="agent-main">
        <div class="agent-title">
          <strong>${escapeHtml(agent.name)}</strong>
          <span class="mini ${statusClass(normalizedStatus)}">${statusText(normalizedStatus)}</span>
        </div>
        <p>${escapeHtml(agent.detail || agent.note || "")}</p>
        <p class="agent-setup">${escapeHtml(agent.setup || agent.pairing || "")}</p>
      </div>
      <div class="agent-pairing">${escapeHtml(agent.mobile_contact_id || agent.id)}</div>
    `;
    agentList.appendChild(row);
  }
}

function updateTestAgentOptions(agents) {
  const selected = fields.testAgent.value || "hermes";
  fields.testAgent.innerHTML = "";
  for (const agent of agents || []) {
    const id = agent.mobile_contact_id || agent.id;
    if (!id) continue;
    agentLabels[id] = agent.name || id;
    const option = document.createElement("option");
    option.value = id;
    option.textContent = agent.name || id;
    fields.testAgent.appendChild(option);
  }
  if ([...fields.testAgent.options].some((option) => option.value === selected)) {
    fields.testAgent.value = selected;
  }
}

function renderStatusMatrix(diagnostics) {
  const ready = diagnostics.ready || [];
  const needsSetup = diagnostics.needs_setup || [];
  statusSummary.innerHTML = `
    <span><strong>Protocol</strong>${escapeHtml(diagnostics.protocol || "SignalASI Link Protocol")} v1.0.3</span>
    <span><strong>Pairing</strong>${escapeHtml(diagnostics.pairing_route || "/signalasi/verify")}</span>
    <span><strong>Ready</strong>${escapeHtml(ready.join(", ") || t("none"))}</span>
    <span><strong>Needs setup</strong>${escapeHtml(needsSetup.join(", ") || t("none"))}</span>
  `;

  connectorMatrixRows.innerHTML = "";
  for (const agent of diagnostics.agents || []) {
    const isReady = agent.status === "ready";
    const row = document.createElement("div");
    row.className = "matrix-row";
    row.setAttribute("role", "row");
    row.innerHTML = `
      <span role="cell">
        <strong>${escapeHtml(agent.name)}</strong>
        <em>${escapeHtml(agent.mobile_contact_id || agent.id)}</em>
      </span>
      <span role="cell"><mark class="${isReady ? "ready" : "needs"}">${isReady ? t("Ready") : t("Needs setup")}</mark></span>
      <span role="cell">${escapeHtml(t(simulationProof[agent.id] || "Connector diagnostics"))}</span>
      <span role="cell">${escapeHtml(isReady ? t("Ready for live use") : (agent.setup || agent.detail || t("Configure this connector")))}</span>
    `;
    connectorMatrixRows.appendChild(row);
  }
}

function agentIsReady(diagnostics, id) {
  return (diagnostics.ready || []).includes(id);
}

function setupItem(state, title, detail, actionLabel, action) {
  return { state, title, detail, actionLabel, action };
}

function renderPairingStatus(status) {
  const text = status.paired
    ? t("Phone paired: {name} / fingerprint {fingerprint}", {
      name: status.remote_name || "android",
      fingerprint: status.identity_fingerprint_short || "unknown"
    })
    : (status.token?.active
      ? t("Waiting for phone scan. QR expires in {seconds}s.", { seconds: status.token.expires_in })
      : t("Phone is not paired yet. Open Pairing and refresh the QR code."));
  pairingState.textContent = text;
  pairingStatusDetail.textContent = text;
}

function renderSetupGuide(diagnostics, pairingStatus = {}) {
  const backendReady = lastBackendRunning;
  const phonePaired = Boolean(pairingStatus.paired);
  const hermesReady = agentIsReady(diagnostics, "hermes");
  const codexReady = agentIsReady(diagnostics, "codex");
  const claudeReady = agentIsReady(diagnostics, "claude");
  const localReady = agentIsReady(diagnostics, "local-llm");
  const customReady = agentIsReady(diagnostics, "custom-agent");
  const items = [
    setupItem(
      backendReady ? "done" : "todo",
      t("Start desktop connector"),
      backendReady ? t("Backend, pairing page, and SignalASI Link are online.") : t("Start the local backend before pairing or testing contacts."),
      backendReady ? t("Refresh status") : t("Start backend"),
      "refresh"
    ),
    setupItem(
      phonePaired ? "done" : (backendReady ? "ready" : "todo"),
      t("Pair the mobile app"),
      phonePaired ? t("Paired with {name} fingerprint {fingerprint}.", {
        name: pairingStatus.remote_name || "android",
        fingerprint: pairingStatus.identity_fingerprint_short || "unknown"
      }) : (backendReady ? t("Open the QR page and scan it from SignalASI mobile.") : t("The QR page is available after the backend starts.")),
      t("Open pairing"),
      "pairing"
    ),
    setupItem(
      hermesReady && codexReady ? "done" : "todo",
      t("Enable core agents"),
      hermesReady && codexReady ? t("Hermes and Codex are ready as phone contacts.") : t("Hermes and Codex need working CLI commands."),
      t("Open agents"),
      "agents"
    ),
    setupItem(
      claudeReady ? "done" : "todo",
      t("Add Claude Code"),
      claudeReady ? t("Claude Code is ready through the desktop connector.") : t("Install Claude Code or set a custom command such as claude -p."),
      t("Configure Claude"),
      "agents"
    ),
    setupItem(
      localReady ? "done" : "todo",
      t("Add local model"),
      localReady ? t("Local LLM is ready as a phone contact.") : t("Start Ollama, LM Studio, vLLM, or another local endpoint."),
      t("Open models"),
      "models"
    ),
    setupItem(
      customReady ? "done" : "todo",
      t("Add custom agent"),
      customReady ? t("Custom Agent is ready through the configured command.") : t("Set any CLI or MCP wrapper command in Agent setup."),
      t("Open agents"),
      "agents"
    )
  ];
  const doneCount = items.filter((item) => item.state === "done").length;
  const percent = Math.round((doneCount / items.length) * 100);
  setupProgressText.textContent = t("{done}/{total} setup steps complete", { done: doneCount, total: items.length });
  setupProgressDetail.textContent = doneCount === items.length
    ? t("All connector contacts are ready for live use.")
    : t("Complete the remaining steps to expose every contact on the phone.");
  setupProgressBar.style.width = `${percent}%`;
  setupChecklist.innerHTML = "";

  items.forEach((item, index) => {
    const row = document.createElement("div");
    row.className = `setup-item ${item.state}`;
    row.innerHTML = `
      <div class="setup-index">${item.state === "done" ? "OK" : String(index + 1)}</div>
      <div>
        <strong>${escapeHtml(item.title)}</strong>
        <p>${escapeHtml(item.detail)}</p>
      </div>
      <button data-setup-action="${escapeHtml(item.action)}">${escapeHtml(item.actionLabel)}</button>
    `;
    setupChecklist.appendChild(row);
  });
}

function renderSetupError(error) {
  setupProgressText.textContent = t("Setup guide unavailable");
  setupProgressDetail.textContent = error.message || String(error);
  setupProgressBar.style.width = "0%";
  setupChecklist.innerHTML = "";
  pairingState.textContent = error.message || String(error);
  pairingStatusDetail.textContent = error.message || String(error);
}

function normalizeAgentId(value) {
  return String(value || "")
    .trim()
    .toLowerCase()
    .replace(/[^a-z0-9_-]+/g, "-")
    .replace(/-+/g, "-")
    .replace(/^[-_]+|[-_]+$/g, "")
    .slice(0, 48);
}

function renderCustomAgentRows(agents = []) {
  customAgentsList.innerHTML = "";
  const rows = Array.isArray(agents) ? agents : [];
  if (!rows.length) {
    const empty = document.createElement("div");
    empty.className = "custom-agent-empty";
    empty.textContent = t("No additional agents yet.");
    customAgentsList.appendChild(empty);
    return;
  }
  rows.forEach((agent) => addCustomAgentRow(agent));
}

function addCustomAgentRow(agent = {}) {
  const existingEmpty = customAgentsList.querySelector(".custom-agent-empty");
  if (existingEmpty) existingEmpty.remove();
  const row = document.createElement("div");
  row.className = "custom-agent-row";
  row.innerHTML = `
    <input data-custom-agent-field="id" maxlength="48" placeholder="research-agent" spellcheck="false" value="${escapeHtml(agent.id || "")}">
    <input data-custom-agent-field="name" maxlength="48" placeholder="Research Agent" spellcheck="false" value="${escapeHtml(agent.name || "")}">
    <input data-custom-agent-field="command" placeholder="python custom_agent_stdio.py -" spellcheck="false" value="${escapeHtml(agent.command || "")}">
    <button type="button" data-remove-custom-agent>${escapeHtml(t("Delete"))}</button>
  `;
  row.querySelector('[data-custom-agent-field="id"]').addEventListener("blur", (event) => {
    event.target.value = normalizeAgentId(event.target.value);
  });
  row.querySelector("[data-remove-custom-agent]").addEventListener("click", () => {
    row.remove();
    if (!customAgentsList.querySelector(".custom-agent-row")) {
      renderCustomAgentRows([]);
    }
  });
  customAgentsList.appendChild(row);
}

function readCustomAgentRows() {
  const rows = Array.from(customAgentsList.querySelectorAll(".custom-agent-row"));
  return rows.map((row) => {
    const id = normalizeAgentId(row.querySelector('[data-custom-agent-field="id"]')?.value);
    const name = row.querySelector('[data-custom-agent-field="name"]')?.value.trim() || id.replaceAll("-", " ");
    const command = row.querySelector('[data-custom-agent-field="command"]')?.value.trim();
    return { id, name, command };
  }).filter((agent) => agent.id && agent.command);
}

function fillConfig(config) {
  fields.cmdHermes.value = config.commands?.hermes || "";
  fields.cmdCodex.value = config.commands?.codex || "";
  fields.cmdClaude.value = config.commands?.claude || "";
  fields.customAgentName.value = config.custom_agent?.name || "Custom Agent";
  fields.cmdCustomAgent.value = config.commands?.["custom-agent"] || "";
  renderCustomAgentRows(config.custom_agents || []);
  fields.localModelName.value = config.local_model?.name || "Local LLM";
  fields.localProvider.value = config.local_model?.provider || "auto";
  fields.localUrl.value = config.local_model?.url || "";
  fields.localModel.value = config.local_model?.model || "";
  fields.localKey.value = config.local_model?.api_key || "";
}

function readConfig() {
  return {
    commands: {
      hermes: fields.cmdHermes.value.trim(),
      codex: fields.cmdCodex.value.trim(),
      claude: fields.cmdClaude.value.trim(),
      "custom-agent": fields.cmdCustomAgent.value.trim()
    },
    local_model: {
      name: fields.localModelName.value.trim() || "Local LLM",
      provider: fields.localProvider.value,
      url: fields.localUrl.value.trim(),
      model: fields.localModel.value.trim(),
      api_key: fields.localKey.value.trim()
    },
    custom_agent: {
      name: fields.customAgentName.value.trim() || "Custom Agent"
    },
    custom_agents: readCustomAgentRows()
  };
}

function setActiveSection(target) {
  navButtons.forEach((button) => button.classList.toggle("active", button.dataset.target === target));
  sections.forEach((section) => section.classList.toggle("active-section", section.id === target));
}

function handleSetupAction(action) {
  if (action === "refresh") {
    refreshBackend();
    refreshAgents();
    refreshDiagnostics();
    return;
  }
  if (action === "pairing" || action === "agents" || action === "models") {
    setActiveSection(action);
  }
}

async function copyText(text, label) {
  await window.signalasi.copyText(text);
  testResult.textContent = `${label} copied to clipboard.`;
}

async function copyPairingLink() {
  const url = await window.signalasi.pairingUrl();
  await copyText(url, t("Pairing link"));
}

function scrollToSelfTest() {
  document.querySelector(".test-panel")?.scrollIntoView({ behavior: "smooth", block: "end" });
}

async function refreshConfig() {
  fillConfig(await window.signalasi.getAgentConfig());
}

async function refreshBackend() {
  backendBadge.className = "badge pending";
  backendBadge.textContent = t("Starting");
  const status = await window.signalasi.startBackend();
  setBackendBadge(status);
  pairingFrame.src = `${status.pairingUrl}?t=${Date.now()}`;
  await refreshPairingStatus();
}

async function refreshPairingStatus() {
  const status = await window.signalasi.getPairingStatus();
  renderPairingStatus(status);
  return status;
}

async function clearPairing() {
  if (!confirm(t("Forget this paired phone on this desktop connector? The phone must scan the QR again before it can communicate."))) {
    return;
  }
  forgetPhoneButton.disabled = true;
  forgetPhoneButton.textContent = t("Forgetting");
  try {
    const status = await window.signalasi.clearPairing();
    renderPairingStatus(status);
    await refreshDiagnostics();
    testResult.textContent = t("Paired phone was forgotten. Open Pairing and scan again to reconnect.");
  } catch (error) {
    testResult.textContent = error.message || String(error);
  } finally {
    forgetPhoneButton.disabled = false;
    forgetPhoneButton.textContent = t("Forget phone");
  }
}

async function refreshAgents() {
  detectAgentsButton.disabled = true;
  detectAgentsButton.textContent = t("Detecting");
  try {
    renderAgents(await window.signalasi.detectAgents());
  } finally {
    detectAgentsButton.disabled = false;
    detectAgentsButton.textContent = t("Detect again");
  }
}

function renderExecutionLog(data) {
  const entries = Array.isArray(data?.entries) ? data.entries : [];
  executionLog.innerHTML = "";
  if (!entries.length) {
    executionLog.textContent = data?.error || t("No Agent calls recorded yet.");
    return;
  }
  for (const entry of entries) {
    const row = document.createElement("div");
    row.className = `audit-entry ${entry.ok ? "ok" : "warn"}`;
    const time = entry.ts ? new Date(entry.ts).toLocaleString() : "unknown time";
    row.innerHTML = `
      <div>
        <strong>${escapeHtml(entry.agent_name || entry.contact_id || "Agent")}</strong>
        <span>${escapeHtml(time)}</span>
      </div>
      <div>
        <strong>${escapeHtml(entry.permission || "unknown")}</strong>
        <span>${escapeHtml(entry.kind || "unknown")} / ${entry.ok ? "ok" : "warning"}</span>
      </div>
      <div>
        <strong>prompt ${escapeHtml(entry.prompt_sha256 || "no-hash")}</strong>
        <span>${Number(entry.prompt_chars || 0)} chars in, ${Number(entry.reply_chars || 0)} chars out, ${Number(entry.duration_ms || 0)} ms${entry.error ? `, ${escapeHtml(entry.error)}` : ""}</span>
      </div>
    `;
    executionLog.appendChild(row);
  }
}

async function refreshExecutionLog() {
  refreshExecutionLogButton.disabled = true;
  refreshExecutionLogButton.textContent = t("Refreshing");
  try {
    renderExecutionLog(await window.signalasi.getAgentExecutionLog(30));
  } catch (error) {
    executionLog.textContent = `${t("Execution log unavailable")}: ${error.message || String(error)}`;
  } finally {
    refreshExecutionLogButton.disabled = false;
    refreshExecutionLogButton.textContent = t("Refresh log");
  }
}

async function saveConfig(button = saveConfigButton) {
  button.disabled = true;
  button.textContent = t("Saving");
  try {
    fillConfig(await window.signalasi.saveAgentConfig(readConfig()));
    await refreshAgents();
    await refreshDiagnostics();
    button.textContent = t("Saved");
    setTimeout(() => {
      button.textContent = button === saveModelConfigButton ? t("Save model settings") : t("Save settings");
    }, 1200);
  } catch (error) {
    button.textContent = t("Save failed");
    testResult.textContent = error.message || String(error);
  } finally {
    button.disabled = false;
  }
}

function useCustomStdinTemplate() {
  fields.customAgentName.value = fields.customAgentName.value.trim() || "Custom Agent";
  fields.cmdCustomAgent.value = "python custom_agent_stdio.py -";
  testResult.textContent = "Custom Agent stdin template inserted.";
}

function useCustomMcpTemplate() {
  fields.customAgentName.value = fields.customAgentName.value.trim() || "MCP Agent";
  fields.cmdCustomAgent.value = 'python mcp_agent_wrapper.py --server "python your_mcp_server.py" --tool echo -';
  testResult.textContent = "Custom Agent MCP wrapper template inserted.";
}

function addDefaultCustomAgent() {
  const count = customAgentsList.querySelectorAll(".custom-agent-row").length;
  addCustomAgentRow({
    id: count ? `custom-agent-${count + 2}` : "research-agent",
    name: count ? `Custom Agent ${count + 2}` : "Research Agent",
    command: "python custom_agent_stdio.py -"
  });
}

async function runAgentTest() {
  runTestButton.disabled = true;
  runTestButton.textContent = t("Testing");
  testResult.textContent = t("Calling local agent...");
  try {
    const result = await window.signalasi.testAgent(fields.testAgent.value, fields.testPrompt.value);
    testResult.textContent = result.reply || JSON.stringify(result, null, 2);
    await refreshAgents();
    await refreshExecutionLog();
  } catch (error) {
    testResult.textContent = error.message || String(error);
  } finally {
    runTestButton.disabled = false;
    runTestButton.textContent = t("Test agent");
  }
}

async function saveThenTestAgent(agentId, button, idleText) {
  button.disabled = true;
  button.textContent = t("Testing");
  testResult.textContent = `Saving configuration and testing ${agentLabels[agentId] || agentId}...`;
  try {
    await window.signalasi.saveAgentConfig(readConfig());
    const result = await window.signalasi.testAgent(agentId, "SignalASI connector test. Reply OK only.");
    testResult.textContent = result.reply || JSON.stringify(result, null, 2);
    await refreshConfig();
    await refreshAgents();
    await refreshDiagnostics();
    await refreshExecutionLog();
  } catch (error) {
    testResult.textContent = error.message || String(error);
  } finally {
    button.disabled = false;
    button.textContent = idleText;
  }
}

function useOllamaPreset() {
  fields.localModelName.value = fields.localModelName.value.trim() || "Ollama Local";
  fields.localProvider.value = "ollama";
  fields.localUrl.value = "http://127.0.0.1:11434/api/generate";
  if (!fields.localModel.value.trim()) fields.localModel.value = "qwen2.5:7b";
  testResult.textContent = "Ollama preset loaded. Start Ollama locally, then click Save and test Local LLM.";
}

function useLmStudioPreset() {
  fields.localModelName.value = fields.localModelName.value.trim() || "LM Studio";
  fields.localProvider.value = "openai";
  fields.localUrl.value = "http://127.0.0.1:1234/v1/chat/completions";
  if (!fields.localModel.value.trim()) fields.localModel.value = "local-model";
  testResult.textContent = "LM Studio preset loaded. Start the local server, then click Save and test Local LLM.";
}

async function sendMobileTest() {
  const agentId = fields.testAgent.value;
  const token = `DESKTOP_${agentId}_${Date.now()}`;
  sendMobileTestButton.disabled = true;
  sendMobileTestButton.textContent = t("Sending");
  testResult.textContent = `Sending encrypted diagnostic to ${agentLabels[agentId] || agentId}...\n${token}`;
  try {
    const result = await window.signalasi.sendMobileTest(agentId, token);
    testResult.textContent = JSON.stringify(result, null, 2);
  } catch (error) {
    testResult.textContent = error.message || String(error);
  } finally {
    sendMobileTestButton.disabled = false;
    sendMobileTestButton.textContent = t("Send to phone");
  }
}

async function syncStatusToPhone(button = syncStatusToPhoneButton) {
  for (const item of [syncStatusToPhoneButton, syncStatusToPhoneSecondaryButton]) {
    if (item) {
      item.disabled = true;
      item.textContent = item === syncStatusToPhoneSecondaryButton ? t("Syncing") : t("Syncing status");
    }
  }
  testResult.textContent = t("Syncing connector status to paired phone...");
  try {
    const result = await window.signalasi.syncMobileStatus();
    testResult.textContent = JSON.stringify(result, null, 2);
    await refreshDiagnostics();
  } catch (error) {
    testResult.textContent = error.message || String(error);
  } finally {
    if (syncStatusToPhoneButton) {
      syncStatusToPhoneButton.disabled = false;
      syncStatusToPhoneButton.textContent = t("Sync phone status");
    }
    if (syncStatusToPhoneSecondaryButton) {
      syncStatusToPhoneSecondaryButton.disabled = false;
      syncStatusToPhoneSecondaryButton.textContent = t("Sync status");
    }
  }
}

function renderSelfTest(data) {
  const lines = [
    `${data.protocol} / ${data.connector}`,
    `Pairing route: ${data.pairing_route}`,
    `Ready: ${data.summary.ready.join(", ") || "none"}`,
    `Needs setup: ${data.summary.needs_setup.join(", ") || "none"}`,
    `Mobile delivery OK: ${data.summary.mobile_delivery_ok.join(", ") || "none"}`,
    `Mobile delivery failed: ${data.summary.mobile_delivery_failed.join(", ") || "none"}`,
    ""
  ];
  for (const item of data.results) {
    lines.push(`${item.name} (${item.id})`);
    lines.push(`  overall: ${item.overall}`);
    lines.push(`  setup: ${item.status} - ${item.detail}`);
    lines.push(`  agent call: ${item.agent_call.status} - ${typeof item.agent_call.detail === "string" ? item.agent_call.detail : JSON.stringify(item.agent_call.detail)}`);
    lines.push(`  mobile: ${item.mobile_delivery.status} - ${JSON.stringify(item.mobile_delivery.detail)}`);
    if (item.status !== "ready" && item.setup) lines.push(`  next: ${item.setup}`);
    lines.push("");
  }
  return lines.join("\n");
}

async function runSelfTest() {
  runSelfTestButton.disabled = true;
  runSelfTestButton.textContent = t("Running");
  testResult.textContent = t("Running connector self-test...");
  try {
    const result = await window.signalasi.runAgentSelfTest({
      includeAgentCalls: includeAgentCalls.checked,
      includeMobileDelivery: true
    });
    testResult.textContent = renderSelfTest(result);
    await refreshAgents();
    await refreshDiagnostics();
    await refreshExecutionLog();
  } catch (error) {
    testResult.textContent = error.message || String(error);
  } finally {
    runSelfTestButton.disabled = false;
    runSelfTestButton.textContent = t("Self-test all");
  }
}

async function refreshDiagnostics() {
  refreshDiagnosticsButton.disabled = true;
  refreshStatusMatrixButton.disabled = true;
  refreshSetupGuideButton.disabled = true;
  refreshDiagnosticsButton.textContent = t("Refreshing");
  refreshStatusMatrixButton.textContent = t("Refreshing");
  refreshSetupGuideButton.textContent = t("Refreshing");
  try {
    const diagnostics = await window.signalasi.getAgentDiagnostics();
    const pairingStatus = await refreshPairingStatus();
    renderStatusMatrix(diagnostics);
    renderSetupGuide(diagnostics, pairingStatus);
    diagnosticsResult.textContent = [
      `${diagnostics.protocol} / ${diagnostics.connector}`,
      `Pairing route: ${diagnostics.pairing_route}`,
      `Mobile delivery: ${diagnostics.mobile_delivery}`,
      `Ready: ${diagnostics.ready.join(", ") || "none"}`,
      `Needs setup: ${diagnostics.needs_setup.join(", ") || "none"}`,
      "",
      diagnostics.agents.map((agent) => {
        const state = agent.status === "ready" ? "ready" : "needs setup";
        return `${agent.name} [${state}]
contact_id: ${agent.mobile_contact_id}
${agent.detail}
${agent.setup}`;
      }).join("\n\n")
    ].join("\n");
  } catch (error) {
    diagnosticsResult.textContent = error.message || String(error);
    statusSummary.textContent = error.message || String(error);
    renderSetupError(error);
  } finally {
    refreshDiagnosticsButton.disabled = false;
    refreshStatusMatrixButton.disabled = false;
    refreshSetupGuideButton.disabled = false;
    refreshDiagnosticsButton.textContent = t("Refresh");
    refreshStatusMatrixButton.textContent = t("Refresh status");
    refreshSetupGuideButton.textContent = t("Refresh guide");
  }
}

async function refreshRuntimeDiagnostics() {
  refreshRuntimeButton.disabled = true;
  refreshRuntimeButton.textContent = t("Checking");
  try {
    const data = await window.signalasi.getRuntimeDiagnostics();
    runtimeResult.textContent = [
      `mode: ${data.app.packaged ? "packaged" : "development"}`,
      `app path: ${data.app.appPath}`,
      `resources: ${data.app.resourcesPath}`,
      `backend: ${data.backend.exists ? "found" : "missing"} - ${data.backend.dir}`,
      `sidecar: ${data.backend.sidecarExists ? "found" : "missing"} - ${data.backend.sidecarRuntime}`,
      `python: ${data.python.ok ? "found" : "missing"} - ${data.python.command}`,
      `python version: ${data.python.version || "n/a"}`,
      `backend deps: ${data.python.depsOk ? "ok" : "missing"}`,
      data.python.depsOutput || "",
      "",
      data.installHint
    ].join("\n");
  } catch (error) {
    runtimeResult.textContent = error.message || String(error);
  } finally {
    refreshRuntimeButton.disabled = false;
    refreshRuntimeButton.textContent = t("Check runtime");
  }
}

navButtons.forEach((button) => {
  button.addEventListener("click", () => setActiveSection(button.dataset.target));
});
document.querySelectorAll("[data-nav]").forEach((button) => {
  button.addEventListener("click", () => setActiveSection(button.dataset.nav));
});
refreshPairing.addEventListener("click", refreshBackend);
detectAgentsButton.addEventListener("click", refreshAgents);
saveConfigButton.addEventListener("click", () => saveConfig(saveConfigButton));
saveModelConfigButton.addEventListener("click", () => saveConfig(saveModelConfigButton));
runTestButton.addEventListener("click", runAgentTest);
sendMobileTestButton.addEventListener("click", sendMobileTest);
syncStatusToPhoneButton.addEventListener("click", () => syncStatusToPhone(syncStatusToPhoneButton));
syncStatusToPhoneSecondaryButton.addEventListener("click", () => syncStatusToPhone(syncStatusToPhoneSecondaryButton));
runSelfTestButton.addEventListener("click", runSelfTest);
pairingSelfTestButton.addEventListener("click", () => {
  scrollToSelfTest();
  runSelfTest();
});
forgetPhoneButton.addEventListener("click", clearPairing);
copyPairingLinkButton.addEventListener("click", copyPairingLink);
copyPairingLinkSecondaryButton.addEventListener("click", copyPairingLink);
copyContactMapButton.addEventListener("click", () => copyText(CONTACT_MAP_TEXT, "Contact map"));
copyDefaultCommandsButton.addEventListener("click", () => copyText(DEFAULT_COMMANDS_TEXT, "Default commands"));
jumpSelfTestButton.addEventListener("click", scrollToSelfTest);
openClaudeDocsButton.addEventListener("click", () => window.signalasi.openExternal(CLAUDE_SETUP_URL));
useCustomStdinTemplateButton.addEventListener("click", useCustomStdinTemplate);
useCustomMcpTemplateButton.addEventListener("click", useCustomMcpTemplate);
addCustomAgentButton.addEventListener("click", addDefaultCustomAgent);
testClaudeConfigButton.addEventListener("click", () => saveThenTestAgent("claude", testClaudeConfigButton, "Test Claude Code"));
testCustomAgentConfigButton.addEventListener("click", () => saveThenTestAgent("custom-agent", testCustomAgentConfigButton, "Test Custom Agent"));
useOllamaPresetButton.addEventListener("click", useOllamaPreset);
useLmStudioPresetButton.addEventListener("click", useLmStudioPreset);
testLocalModelButton.addEventListener("click", () => saveThenTestAgent("local-llm", testLocalModelButton, "Save and test Local LLM"));
refreshDiagnosticsButton.addEventListener("click", refreshDiagnostics);
refreshStatusMatrixButton.addEventListener("click", refreshDiagnostics);
refreshSetupGuideButton.addEventListener("click", refreshDiagnostics);
refreshExecutionLogButton.addEventListener("click", refreshExecutionLog);
refreshRuntimeButton.addEventListener("click", refreshRuntimeDiagnostics);
setupChecklist.addEventListener("click", (event) => {
  const button = event.target.closest("[data-setup-action]");
  if (button) handleSetupAction(button.dataset.setupAction);
});

if (languageSelect) {
  languageSelect.addEventListener("change", async () => {
    await setLanguage(languageSelect.value);
    refreshDiagnostics();
  });
}

async function initialize() {
  await refreshBackend();
  const tasks = [
    refreshDiagnostics,
    refreshAgents,
    refreshConfig,
    refreshExecutionLog,
    refreshRuntimeDiagnostics
  ];
  const results = await Promise.allSettled(tasks.map((task) => task()));
  const firstError = results.find((result) => result.status === "rejected");
  if (firstError) {
    testResult.textContent = firstError.reason?.message || String(firstError.reason);
  }
}

async function bootstrap() {
  await loadLocale(currentLanguage);
  translateStaticText();
  await initialize();
}

bootstrap().catch((error) => {
  testResult.textContent = error.message || String(error);
});

