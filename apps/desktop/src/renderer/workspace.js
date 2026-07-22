const $ = (selector) => document.querySelector(selector);
const $$ = (selector) => Array.from(document.querySelectorAll(selector));

const TERMINAL_STATES = new Set(["completed", "failed", "cancelled", "timed_out"]);
const DEFAULT_AGENT_CONTACTS = [
  ["codex", "Codex", "local-cli"],
  ["hermes", "Hermes", "local-cli"],
  ["claude", "Claude Code", "local-cli"],
  ["openclaw", "OpenClaw", "local-cli"],
  ["local-llm", "Local LLM", "local-model"]
].map(([id, name, kind]) => ({ id, name, kind, status: "checking", detail: "Checking" }));
const state = {
  language: localStorage.getItem("signalasi-desktop-language") || "en",
  locale: {},
  backend: null,
  agents: DEFAULT_AGENT_CONTACTS,
  agentConfig: null,
  pairing: null,
  tools: [],
  desktopControl: null,
  memory: { memories: [], stats: {} },
  skills: [],
  mcp: [],
  tasks: [],
  currentConversationId: crypto.randomUUID(),
  selectedAgentId: "auto",
  selectedAgentName: "Agent",
  attachments: [],
  renderingSignature: "",
  polling: false,
  toastTimer: 0,
  speechRecognition: null,
  agentRefreshPromise: null
};

const elements = {
  history: $("#taskHistory"),
  title: $("#conversationTitle"),
  taskState: $("#taskStateText"),
  route: $("#routeText"),
  stream: $("#conversationStream"),
  empty: $("#emptyState"),
  messages: $("#messageList"),
  prompt: $("#promptInput"),
  send: $("#sendButton"),
  attachments: $("#attachmentTray"),
  selectedAgent: $("#selectedAgentLabel"),
  agentCount: $("#agentCount"),
  capabilityCount: $("#capabilityCount"),
  headerAgentCount: $("#headerAgentCount"),
  gatewayCount: $("#gatewayCount"),
  headerGatewayCount: $("#headerGatewayCount"),
  backendDot: $("#backendDot"),
  backendBadge: $("#backendBadge"),
  backendDetail: $("#backendDetail"),
  drawer: $("#utilityDrawer"),
  backdrop: $("#drawerBackdrop"),
  drawerTitle: $("#drawerTitle"),
  drawerSubtitle: $("#drawerSubtitle"),
  toast: $("#toast")
};

function t(key, params = {}) {
  let value = state.locale[key] || key;
  for (const [name, replacement] of Object.entries(params)) {
    value = value.replaceAll(`{${name}}`, String(replacement));
  }
  return value;
}

function escapeHtml(value) {
  return String(value ?? "")
    .replaceAll("&", "&amp;")
    .replaceAll("<", "&lt;")
    .replaceAll(">", "&gt;")
    .replaceAll('"', "&quot;")
    .replaceAll("'", "&#039;");
}

function formatBytes(value) {
  const size = Number(value || 0);
  if (size < 1024) return `${size} B`;
  if (size < 1024 * 1024) return `${(size / 1024).toFixed(1)} KB`;
  return `${(size / 1024 / 1024).toFixed(1)} MB`;
}

function formatDuration(value) {
  const seconds = Math.max(1, Math.floor(Number(value || 0) / 1000));
  if (seconds < 60) return `${seconds}s`;
  const minutes = Math.floor(seconds / 60);
  const remainder = seconds % 60;
  if (minutes < 60) return `${minutes}m ${remainder}s`;
  const hours = Math.floor(minutes / 60);
  return `${hours}h ${minutes % 60}m ${remainder}s`;
}

function relativeTime(timestamp) {
  const delta = Math.max(0, Date.now() - Number(timestamp || Date.now()));
  if (delta < 60_000) return t("Just now");
  if (delta < 3_600_000) return t("{count} min ago", { count: Math.floor(delta / 60_000) });
  if (delta < 86_400_000) return t("{count} hr ago", { count: Math.floor(delta / 3_600_000) });
  return new Date(Number(timestamp)).toLocaleDateString(state.language === "zh-CN" ? "zh-CN" : "en-US", { month: "short", day: "numeric" });
}

function titleFromPrompt(prompt) {
  const clean = String(prompt || t("Attached files")).replace(/\s+/g, " ").trim();
  return clean.length > 42 ? `${clean.slice(0, 42)}...` : clean;
}

function statusLabel(status) {
  const labels = {
    accepted: "Accepted",
    queued: "Queued",
    running: "Running",
    completed: "Completed",
    failed: "Failed",
    cancelled: "Cancelled",
    timed_out: "Timed out"
  };
  return t(labels[status] || status || "Ready");
}

function agentName(agentId) {
  if (!agentId || agentId === "auto") return t("Agent");
  if (agentId.startsWith("mcp:")) {
    const connection = state.mcp.find((item) => item.id === agentId.slice(4));
    return connection?.name || agentId.slice(4);
  }
  return state.agents.find((agent) => (agent.mobile_contact_id || agent.id) === agentId)?.name
    || ({ desktop: "SignalASI Desktop", codex: "Codex", hermes: "Hermes", claude: "Claude Code", openclaw: "OpenClaw", "local-llm": "Local LLM" })[agentId]
    || agentId;
}

function taskRouteName(task) {
  if (!task) return t("Automatic routing");
  const primary = agentName(task.agent_id);
  return task.delegate_agent_id ? `${primary} · ${agentName(task.delegate_agent_id)}` : primary;
}

function applyInlineMarkup(value) {
  return value
    .replace(/`([^`]+)`/g, "<code>$1</code>")
    .replace(/\*\*([^*]+)\*\*/g, "<strong>$1</strong>")
    .replace(/\[([^\]]+)]\((https?:\/\/[^\s)]+)\)/g, '<a href="$2" data-external-link="$2">$1</a>');
}

function renderMarkdown(value) {
  const escaped = escapeHtml(value).replace(/\r\n/g, "\n");
  const chunks = escaped.split(/```/);
  return chunks.map((chunk, index) => {
    if (index % 2 === 1) {
      const lines = chunk.replace(/^\w+\n/, "").replace(/\n$/, "");
      return `<pre><code>${lines}</code></pre>`;
    }
    const lines = chunk.split("\n");
    const output = [];
    let listType = "";
    const closeList = () => {
      if (listType) output.push(`</${listType}>`);
      listType = "";
    };
    for (const line of lines) {
      const bullet = line.match(/^\s*[-*]\s+(.+)$/);
      const numbered = line.match(/^\s*\d+[.)]\s+(.+)$/);
      if (bullet || numbered) {
        const nextType = bullet ? "ul" : "ol";
        if (listType !== nextType) {
          closeList();
          listType = nextType;
          output.push(`<${nextType}>`);
        }
        output.push(`<li>${applyInlineMarkup((bullet || numbered)[1])}</li>`);
        continue;
      }
      closeList();
      if (!line.trim()) continue;
      if (line.startsWith("### ")) output.push(`<h3>${applyInlineMarkup(line.slice(4))}</h3>`);
      else if (line.startsWith("## ")) output.push(`<h2>${applyInlineMarkup(line.slice(3))}</h2>`);
      else if (line.startsWith("# ")) output.push(`<h2>${applyInlineMarkup(line.slice(2))}</h2>`);
      else output.push(`<p>${applyInlineMarkup(line)}</p>`);
    }
    closeList();
    return output.join("");
  }).join("");
}

function showToast(message) {
  window.clearTimeout(state.toastTimer);
  elements.toast.textContent = message;
  elements.toast.hidden = false;
  state.toastTimer = window.setTimeout(() => { elements.toast.hidden = true; }, 3200);
}

async function setLanguage(language, persist = true) {
  state.language = language === "zh-CN" ? "zh-CN" : "en";
  state.locale = await window.signalasi.loadLocale(state.language);
  document.documentElement.lang = state.language === "zh-CN" ? "zh-Hans" : "en";
  if (persist) localStorage.setItem("signalasi-desktop-language", state.language);
  $$('[data-i18n]').forEach((node) => { node.textContent = t(node.dataset.i18n); });
  $$('[data-i18n-placeholder]').forEach((node) => { node.placeholder = t(node.dataset.i18nPlaceholder); });
  $("#languageSelect").value = state.language;
  renderHistory();
  renderConversation(true);
  updateHeaderStatus();
}

function conversationTasks(conversationId = state.currentConversationId) {
  return state.tasks
    .filter((task) => task.conversation_id === conversationId)
    .sort((a, b) => Number(a.created_at) - Number(b.created_at));
}

function conversationGroups() {
  const groups = new Map();
  for (const task of [...state.tasks].sort((a, b) => Number(b.updated_at) - Number(a.updated_at))) {
    const id = task.conversation_id || task.task_id;
    if (!groups.has(id)) groups.set(id, { id, latest: task, tasks: [] });
    groups.get(id).tasks.push(task);
  }
  return Array.from(groups.values());
}

function renderHistory() {
  const groups = conversationGroups();
  if (!groups.length) {
    elements.history.innerHTML = `<div class="history-empty">${escapeHtml(t("Tasks will appear here after you send the first request."))}</div>`;
    return;
  }
  const todayStart = new Date();
  todayStart.setHours(0, 0, 0, 0);
  let currentSection = "";
  const html = [];
  for (const group of groups) {
    const section = Number(group.latest.updated_at) >= todayStart.getTime() ? "Today" : "Earlier";
    if (section !== currentSection) {
      currentSection = section;
      html.push(`<div class="history-group-label">${escapeHtml(t(section))}</div>`);
    }
    const running = group.tasks.some((task) => !TERMINAL_STATES.has(task.status));
    html.push(`
      <button class="history-item ${group.id === state.currentConversationId ? "active" : ""}" data-conversation-id="${escapeHtml(group.id)}">
        <strong>${escapeHtml(titleFromPrompt(group.tasks.sort((a, b) => Number(a.created_at) - Number(b.created_at))[0]?.prompt))}</strong>
        <span class="${running ? "running" : ""}">${escapeHtml(running ? statusLabel("running") : relativeTime(group.latest.updated_at))}</span>
      </button>`);
  }
  elements.history.innerHTML = html.join("");
}

function taskElapsed(task) {
  const start = Number(task.started_at || task.created_at || Date.now());
  const end = Number(task.completed_at || (TERMINAL_STATES.has(task.status) ? task.updated_at : Date.now()));
  return Math.max(1000, end - start);
}

function renderArtifacts(task) {
  const files = Array.isArray(task.output_files) ? task.output_files : [];
  if (!files.length) return "";
  return `<div class="artifact-list">${files.map((file) => {
    const extension = String(file.name || "file").split(".").pop().slice(0, 5).toUpperCase();
    return `<div class="artifact-row"><div class="artifact-icon">${escapeHtml(extension)}</div><div><strong>${escapeHtml(file.name)}</strong><small>${escapeHtml(file.relative_path || "")} · ${escapeHtml(formatBytes(file.size))}</small></div><button data-open-artifact="${escapeHtml(file.relative_path || "")}" data-task-id="${escapeHtml(task.task_id)}">${escapeHtml(t("Open"))}</button></div>`;
  }).join("")}</div>`;
}

function renderTurn(task) {
  const statusClass = task.status === "completed" ? "completed" : (TERMINAL_STATES.has(task.status) ? "failed" : "");
  const answer = task.status === "completed"
    ? `<article class="assistant-answer">${renderMarkdown(task.result || t("Task completed."))}</article>${renderArtifacts(task)}`
    : (TERMINAL_STATES.has(task.status)
      ? `<article class="assistant-answer error-answer">${escapeHtml(task.error || task.result || t("The task could not be completed."))}<button class="retry-task" data-retry-task="${escapeHtml(task.task_id)}">${escapeHtml(t("Retry"))}</button></article>`
      : "");
  const events = Array.isArray(task.events) ? task.events : [];
  const detail = events.length
    ? `<div class="event-list">${events.map((event) => `<div class="event-row ${escapeHtml(event.status || "")}"><span></span><div><strong>${escapeHtml(event.title || t("Task step"))}</strong>${event.detail ? `<small>${escapeHtml(event.detail)}</small>` : ""}</div></div>`).join("")}</div>`
    : escapeHtml(task.current_step || `${agentName(task.agent_id)} · ${statusLabel(task.status)}`);
  const attachments = Array.isArray(task.attachments) ? task.attachments : [];
  const attachmentRows = attachments.length
    ? `<div class="user-attachments">${attachments.map((path) => `<span title="${escapeHtml(path)}">${escapeHtml(String(path).split(/[\\/]/).pop() || path)}</span>`).join("")}</div>`
    : "";
  return `
    <article class="task-turn" data-task-id="${escapeHtml(task.task_id)}">
      <div class="user-message-row"><div class="user-message">${escapeHtml(task.prompt || t("Attached files"))}</div></div>${attachmentRows}
      <button class="run-summary ${statusClass}" data-toggle-run="${escapeHtml(task.task_id)}">
        <span class="status-pulse"></span>
        <strong>${escapeHtml(statusLabel(task.status))} <span data-elapsed-task="${escapeHtml(task.task_id)}">${escapeHtml(formatDuration(taskElapsed(task)))}</span></strong>
        <span>${escapeHtml(taskRouteName(task))}</span><span class="chevron" aria-hidden="true"></span>
      </button>
      <div class="run-detail" data-run-detail="${escapeHtml(task.task_id)}" hidden>${detail}</div>
      ${answer}
    </article>`;
}

function renderConversation(force = false) {
  const tasks = conversationTasks();
  const signature = JSON.stringify(tasks.map((task) => [task.task_id, task.status, task.updated_at, task.result?.length, task.output_files?.length, task.events?.length, task.delegate_agent_id]));
  if (!force && signature === state.renderingSignature) return;
  state.renderingSignature = signature;
  const wasNearBottom = elements.stream.scrollHeight - elements.stream.scrollTop - elements.stream.clientHeight < 140;
  elements.empty.hidden = tasks.length > 0;
  elements.messages.innerHTML = tasks.map(renderTurn).join("");
  const first = tasks[0];
  elements.title.textContent = first ? titleFromPrompt(first.prompt) : t("New task");
  updateHeaderStatus();
  if (force || wasNearBottom) requestAnimationFrame(() => { elements.stream.scrollTop = elements.stream.scrollHeight; });
}

function updateElapsedLabels() {
  for (const node of $$('[data-elapsed-task]')) {
    const task = state.tasks.find((item) => item.task_id === node.dataset.elapsedTask);
    if (task) node.textContent = formatDuration(taskElapsed(task));
  }
}

function updateHeaderStatus() {
  const tasks = conversationTasks();
  const active = [...tasks].reverse().find((task) => !TERMINAL_STATES.has(task.status));
  const latest = tasks[tasks.length - 1];
  const status = active?.status || latest?.status || "ready";
  elements.taskState.textContent = statusLabel(status);
  elements.taskState.className = active ? "running" : (latest && latest.status !== "completed" ? "failed" : "");
  elements.route.textContent = latest ? taskRouteName(latest) : t("Automatic routing");
}

async function refreshTasks(force = false) {
  if (state.polling) return;
  state.polling = true;
  try {
    const payload = await window.signalasi.listDesktopTasks(200);
    state.tasks = Array.isArray(payload.tasks) ? payload.tasks : [];
    renderHistory();
    renderConversation(force);
  } catch (error) {
    if (force) showToast(`${t("Task history unavailable")}: ${error.message || error}`);
  } finally {
    state.polling = false;
  }
}

function updateSendState() {
  const ready = Boolean(elements.prompt.value.trim() || state.attachments.length);
  elements.send.classList.toggle("ready", ready);
  elements.send.disabled = !ready;
  elements.prompt.style.height = "35px";
  elements.prompt.style.height = `${Math.min(104, Math.max(35, elements.prompt.scrollHeight))}px`;
}

function renderAttachmentTray() {
  elements.attachments.hidden = state.attachments.length === 0;
  elements.attachments.innerHTML = state.attachments.map((path, index) => {
    const name = path.split(/[\\/]/).pop() || path;
    return `<div class="attachment-chip"><span title="${escapeHtml(path)}">${escapeHtml(name)}</span><button data-remove-attachment="${index}" aria-label="Remove">×</button></div>`;
  }).join("");
  updateSendState();
}

async function addAttachments() {
  try {
    const files = await window.signalasi.chooseAttachments();
    const combined = [...state.attachments, ...files];
    state.attachments = Array.from(new Set(combined)).slice(0, 12);
    renderAttachmentTray();
  } catch (error) {
    showToast(error.message || String(error));
  }
}

async function sendTask() {
  const prompt = elements.prompt.value.trim();
  if (!prompt && !state.attachments.length) return;
  const attachments = [...state.attachments];
  elements.prompt.value = "";
  state.attachments = [];
  renderAttachmentTray();
  updateSendState();
  const optimistic = {
    task_id: `pending-${Date.now()}`,
    conversation_id: state.currentConversationId,
    source_message_id: "desktop:pending",
    prompt: prompt || t("Attached files"),
    agent_id: state.selectedAgentId,
    status: "accepted",
    created_at: Date.now(),
    updated_at: Date.now(),
    started_at: 0,
    output_files: []
  };
  state.tasks.unshift(optimistic);
  state.renderingSignature = "";
  renderHistory();
  renderConversation(true);
  try {
    const task = await window.signalasi.startDesktopTask({
      prompt,
      agentId: state.selectedAgentId,
      conversationId: state.currentConversationId,
      attachments
    });
    state.tasks = state.tasks.filter((item) => item.task_id !== optimistic.task_id);
    state.tasks.push(task);
    updateSelectedAgent();
    state.renderingSignature = "";
    renderHistory();
    renderConversation(true);
  } catch (error) {
    state.tasks = state.tasks.filter((item) => item.task_id !== optimistic.task_id);
    elements.prompt.value = prompt;
    state.attachments = attachments;
    renderAttachmentTray();
    state.renderingSignature = "";
    renderConversation(true);
    showToast(`${t("Could not start task")}: ${error.message || error}`);
  }
}

function newTask(agentId = "auto", name = "Agent") {
  state.currentConversationId = crypto.randomUUID();
  state.selectedAgentId = agentId;
  state.selectedAgentName = name;
  state.attachments = [];
  state.renderingSignature = "";
  elements.prompt.value = "";
  renderAttachmentTray();
  updateSelectedAgent();
  renderHistory();
  renderConversation(true);
  elements.prompt.focus();
}

function updateSelectedAgent() {
  elements.selectedAgent.textContent = state.selectedAgentId === "auto" ? t("Agent") : state.selectedAgentName;
  $("#autoModeButton").classList.toggle("active", state.selectedAgentId === "auto");
  $("#localModeButton").classList.toggle("active", state.selectedAgentId === "desktop");
}

async function refreshBackend() {
  try {
    state.backend = await window.signalasi.startBackend();
  } catch (error) {
    state.backend = { running: false, error: error.message || String(error) };
  }
  const online = Boolean(state.backend?.running);
  elements.backendDot.classList.toggle("online", online);
  elements.backendBadge.className = `state-badge ${online ? "ok" : "bad"}`;
  elements.backendBadge.textContent = t(online ? "Online" : "Offline");
  elements.backendDetail.textContent = online ? state.backend.origin : (state.backend?.error || t("Backend unavailable"));
}

function updateAgentCounters() {
  const ready = state.agents.filter((agent) => ["ready", "detected"].includes(agent.status)).length;
  elements.agentCount.textContent = String(state.agents.length);
  elements.headerAgentCount.textContent = t("{count} Agents", { count: ready || state.agents.length });
}

function renderAgentContacts() {
  const target = $("#agentContactList");
  if (!state.agents.length) {
    target.innerHTML = `<div class="history-empty">${escapeHtml(t("No agents detected."))}</div>`;
    return;
  }
  target.innerHTML = state.agents.map((agent) => {
    const id = agent.mobile_contact_id || agent.id;
    const ready = ["ready", "detected"].includes(agent.status);
    const checking = agent.status === "checking";
    const initials = String(agent.name || id).split(/\s+/).map((part) => part[0]).join("").slice(0, 2).toUpperCase();
    const stateLabel = checking ? "Checking" : (ready ? "Ready" : "Setup");
    return `<article class="agent-contact"><div class="agent-contact-icon">${escapeHtml(initials)}</div><div><strong>${escapeHtml(agent.name || id)}<span class="contact-state ${ready ? "" : "setup"}">${escapeHtml(t(stateLabel))}</span></strong><small>${escapeHtml(t(agent.detail || agent.note || agent.kind || ""))}</small></div><div class="contact-actions"><button data-use-agent="${escapeHtml(id)}">${escapeHtml(t("Use"))}</button><button class="primary" data-chat-agent="${escapeHtml(id)}">${escapeHtml(t("Chat"))}</button></div></article>`;
  }).join("");
}

async function refreshAgents() {
  if (state.agentRefreshPromise) return state.agentRefreshPromise;
  state.agentRefreshPromise = (async () => {
    try {
      state.agents = await window.signalasi.detectAgents();
      state.agentConfig = await window.signalasi.getAgentConfig();
      renderAgentContacts();
      updateAgentCounters();
      fillAgentSettings();
    } catch (error) {
      $("#agentContactList").innerHTML = `<div class="history-empty">${escapeHtml(error.message || String(error))}</div>`;
    } finally {
      state.agentRefreshPromise = null;
    }
  })();
  return state.agentRefreshPromise;
}

function fillAgentSettings() {
  const config = state.agentConfig || {};
  const commands = config.commands || {};
  $("#cmdHermes").value = commands.hermes || "";
  $("#cmdCodex").value = commands.codex || "";
  $("#cmdClaude").value = commands.claude || "";
  $("#cmdOpenClaw").value = commands.openclaw || "";
}

async function saveAgentCommands() {
  const config = state.agentConfig || await window.signalasi.getAgentConfig();
  config.commands = {
    ...(config.commands || {}),
    hermes: $("#cmdHermes").value.trim(),
    codex: $("#cmdCodex").value.trim(),
    claude: $("#cmdClaude").value.trim(),
    openclaw: $("#cmdOpenClaw").value.trim()
  };
  state.agentConfig = await window.signalasi.saveAgentConfig(config);
  showToast(t("Agent commands saved."));
  await refreshAgents();
}

async function saveCustomAgent() {
  const id = $("#customAgentId").value.trim().toLowerCase().replace(/[^a-z0-9._-]+/g, "-").replace(/^-|-$/g, "");
  const name = $("#customAgentName").value.trim();
  const command = $("#customAgentCommand").value.trim();
  if (!id || !name || !command) {
    showToast(t("Complete the agent ID, name, and command."));
    return;
  }
  const config = state.agentConfig || await window.signalasi.getAgentConfig();
  const rows = Array.isArray(config.custom_agents) ? config.custom_agents.filter((item) => item.id !== id) : [];
  rows.push({ id, name, command });
  config.custom_agents = rows;
  state.agentConfig = await window.signalasi.saveAgentConfig(config);
  $("#customAgentId").value = "";
  $("#customAgentName").value = "";
  $("#customAgentCommand").value = "";
  showToast(t("Custom agent added."));
  await refreshAgents();
}

function renderGateway() {
  const status = state.pairing || {};
  const clients = Array.isArray(status.clients) ? status.clients : [];
  const count = Number(status.client_count || clients.length || 0);
  elements.gatewayCount.textContent = count ? t("{count} online", { count }) : t("Offline");
  elements.headerGatewayCount.textContent = t("{count} phones", { count });
  $("#gatewaySummary .status-orb").classList.toggle("online", count > 0);
  $("#gatewaySummary p").textContent = count ? t("{count} verified phone(s) connected", { count }) : t("No phone paired");
  $("#pairedClientList").innerHTML = clients.length ? clients.map((client) => {
    const id = client.client_route_id || "";
    return `<article class="paired-client"><span class="phone-outline"></span><div><strong>${escapeHtml(client.remote_name || client.device_name || t("SignalASI phone"))}</strong><small>${escapeHtml(client.identity_fingerprint_short || id.slice(0, 12) || t("Verified"))}</small></div><button data-revoke-client="${escapeHtml(id)}">${escapeHtml(t("Revoke"))}</button></article>`;
  }).join("") : `<div class="history-empty">${escapeHtml(t("Scan the QR code below to pair a phone."))}</div>`;
}

async function refreshGateway() {
  try {
    state.pairing = await window.signalasi.getPairingStatus();
    renderGateway();
  } catch (error) {
    state.pairing = { clients: [] };
    renderGateway();
    $("#gatewaySummary p").textContent = error.message || String(error);
  }
}

async function loadPairingFrame() {
  const image = $("#pairingFrame");
  if (image.getAttribute("src")) return;
  const fingerprint = $("#pairingFingerprint");
  fingerprint.textContent = t("Preparing secure pairing QR...");
  try {
    const pairing = await window.signalasi.getPairingQr();
    image.src = pairing.imageDataUrl;
    fingerprint.textContent = pairing.fingerprint
      ? t("Computer fingerprint: {fingerprint}", { fingerprint: pairing.fingerprint })
      : "";
  } catch (error) {
    image.removeAttribute("src");
    fingerprint.textContent = t("Unable to load the pairing QR. Restart the Desktop backend and try again.");
    throw error;
  }
}

function renderDesktopTools() {
  const available = state.tools.filter((tool) => tool.availability?.status === "available").length;
  $("#computerSummary .status-orb").classList.toggle("online", available > 0);
  $("#computerSummary p").textContent = t("{available} of {total} local tools ready", { available, total: state.tools.length });
  $("#desktopToolList").innerHTML = state.tools.map((tool) => {
    const status = tool.availability?.status || "unknown";
    return `<article class="desktop-tool"><strong>${escapeHtml(tool.title || tool.id)}</strong><p>${escapeHtml(tool.description || "")}</p><span class="${escapeHtml(status)}">${escapeHtml(t(status === "available" ? "Available" : "Requires setup"))}</span></article>`;
  }).join("");
}

function formatControlTime(value) {
  const timestamp = Number(value || 0);
  if (!timestamp) return t("Never");
  return new Intl.DateTimeFormat(state.language === "zh-CN" ? "zh-CN" : "en", {
    month: "short", day: "numeric", hour: "2-digit", minute: "2-digit"
  }).format(new Date(timestamp));
}

function renderControlPhone(row, pending = false) {
  const id = escapeHtml(row.authorization_id || "");
  const fingerprint = row.phone_fingerprint_short || String(row.phone_fingerprint || "").slice(0, 16);
  const detail = pending
    ? t("Fingerprint {fingerprint} · requested {time}", { fingerprint, time: formatControlTime(row.requested_at) })
    : t("Fingerprint {fingerprint} · last used {time}", { fingerprint, time: formatControlTime(row.last_used_at) });
  const actions = pending
    ? `<div class="control-phone-actions"><button class="approve-control" data-control-action="approve" data-control-id="${id}">${escapeHtml(t("Allow"))}</button><button data-control-action="reject" data-control-id="${id}">${escapeHtml(t("Reject"))}</button></div>`
    : `<div class="control-phone-actions"><button class="revoke-control" data-control-action="revoke" data-control-id="${id}">${escapeHtml(t("Revoke"))}</button></div>`;
  return `<article class="control-phone"><span class="control-phone-icon"></span><div><strong>${escapeHtml(row.phone_name || t("SignalASI phone"))}</strong><small>${escapeHtml(detail)}</small></div>${actions}</article>`;
}

function renderDesktopControl() {
  const control = state.desktopControl || { authorizations: [], recent_audit: [] };
  const rows = Array.isArray(control.authorizations) ? control.authorizations : [];
  const pending = rows.filter((row) => row.status === "pending");
  const active = rows.filter((row) => row.status === "active");
  const availableTools = state.tools.filter((tool) => tool.availability?.status === "available").length;
  $("#desktopExecutorEnabled").checked = Boolean(control.enabled);
  $("#desktopControlRequireUnlocked").checked = Boolean(control.require_unlocked);
  $("#desktopControlPendingCount").textContent = String(pending.length);
  $("#desktopControlActiveCount").textContent = String(active.length);
  $("#desktopControlPendingList").innerHTML = pending.length
    ? pending.map((row) => renderControlPhone(row, true)).join("")
    : `<div class="history-empty">${escapeHtml(t("No pending authorization requests."))}</div>`;
  $("#desktopControlAuthorizedList").innerHTML = active.length
    ? active.map((row) => renderControlPhone(row, false)).join("")
    : `<div class="history-empty">${escapeHtml(t("No phones are authorized for control."))}</div>`;
  const audit = Array.isArray(control.recent_audit) ? control.recent_audit.slice(0, 20) : [];
  $("#desktopControlAuditList").innerHTML = audit.length
    ? audit.map((row) => `<article class="control-audit-row"><strong>${escapeHtml(row.summary || row.event_type || "")}</strong><small>${escapeHtml(`${formatControlTime(row.created_at)} · ${row.status || ""}`)}</small></article>`).join("")
    : `<div class="history-empty">${escapeHtml(t("No remote-control activity yet."))}</div>`;
  $("#desktopControlHint").textContent = control.enabled
    ? t("The executor is on. Scan the refreshed QR, then approve the phone once.")
    : t("Enable the executor, refresh the pairing QR, then approve the phone once.");
  $("#computerSummary .status-orb").classList.toggle("online", Boolean(control.enabled));
  $("#computerSummary p").textContent = control.enabled
    ? t("Remote control on · {phones} authorized · {available} local tools ready", {
      phones: active.length,
      available: availableTools
    })
    : t("Remote control off · {available} local tools ready", { available: availableTools });
}

async function refreshDesktopControl() {
  try {
    state.desktopControl = await window.signalasi.getDesktopControl();
    renderDesktopControl();
  } catch (error) {
    $("#desktopControlHint").textContent = error.message || String(error);
  }
}

async function updateDesktopControlSetting(field, value) {
  state.desktopControl = await window.signalasi.updateDesktopControl({ [field]: Boolean(value) });
  renderDesktopControl();
  if (field === "enabled") {
    $("#pairingFrame").removeAttribute("src");
    showToast(value ? t("Desktop Executor enabled. Refresh the pairing QR before scanning.") : t("Desktop Executor disabled."));
  }
}

async function showDesktopControlPairingQr() {
  if (!state.desktopControl?.enabled) {
    state.desktopControl = await window.signalasi.updateDesktopControl({ enabled: true });
    renderDesktopControl();
  }
  $("#pairingDetails").open = true;
  $("#pairingFrame").removeAttribute("src");
  await openPanel("gateway");
  showToast(t("Scan this QR code with the SignalASI phone app, then approve the phone here."));
}

async function runDesktopControlAuthorizationAction(id, action) {
  state.desktopControl = await window.signalasi.desktopControlAuthorizationAction(id, action);
  renderDesktopControl();
  showToast(t(action === "approve" ? "Phone authorized for Desktop control." : action === "reject" ? "Authorization request rejected." : "Desktop control authorization revoked."));
}

async function refreshDesktopTools() {
  try {
    const payload = await window.signalasi.getDesktopTools();
    state.tools = Array.isArray(payload.tools) ? payload.tools : [];
    renderDesktopTools();
  } catch (error) {
    $("#computerSummary p").textContent = error.message || String(error);
  }
  await refreshDesktopControl();
}

async function readSystemStatus() {
  const output = $("#systemStatusOutput");
  output.hidden = false;
  output.textContent = t("Reading system status...");
  try {
    const result = await window.signalasi.invokeDesktopTool({
      tool_id: "signalasi.desktop.windows.system.status",
      arguments: {},
      invocation_id: crypto.randomUUID(),
      task_id: `desktop-ui-${Date.now()}`,
      idempotency_key: crypto.randomUUID()
    });
    output.textContent = JSON.stringify(result.output || result, null, 2);
  } catch (error) {
    output.textContent = error.message || String(error);
  }
}

function parsePhrases(value) {
  return Array.from(new Set(String(value || "").split(/[,;\n]/).map((item) => item.trim()).filter(Boolean))).slice(0, 32);
}

function renderMemory() {
  const rows = Array.isArray(state.memory.memories) ? state.memory.memories : [];
  const stats = state.memory.stats || {};
  $("#memorySummary").textContent = t("{count} active memories", { count: Number(stats.active || rows.length || 0) });
  $("#memoryList").innerHTML = rows.length ? rows.map((memory) => `
    <article class="capability-item">
      <div><strong>${escapeHtml(memory.kind || t("Memory"))}</strong><small title="${escapeHtml(memory.content || "")}">${escapeHtml(String(memory.content || "").slice(0, 180))}</small></div>
      <div class="capability-item-actions"><button data-forget-memory="${escapeHtml(memory.id)}">${escapeHtml(t("Forget"))}</button></div>
    </article>`).join("") : `<div class="history-empty">${escapeHtml(t("No matching memory."))}</div>`;
}

function renderSkills() {
  const enabled = state.skills.filter((skill) => skill.enabled).length;
  $("#skillSummary").textContent = t("{enabled} of {total} enabled", { enabled, total: state.skills.length });
  $("#skillList").innerHTML = state.skills.length ? state.skills.map((skill) => `
    <article class="capability-item">
      <div><strong>${escapeHtml(skill.name || skill.id)}</strong><small>${escapeHtml(skill.description || skill.id)}</small></div>
      <div class="capability-item-actions">
        ${skill.source === "user" ? `<button data-delete-skill="${escapeHtml(skill.id)}">${escapeHtml(t("Delete"))}</button>` : ""}
        <button class="capability-toggle ${skill.enabled ? "on" : ""}" data-toggle-skill="${escapeHtml(skill.id)}" data-enabled="${skill.enabled ? "1" : "0"}" aria-label="${escapeHtml(t(skill.enabled ? "Disable" : "Enable"))}"></button>
      </div>
    </article>`).join("") : `<div class="history-empty">${escapeHtml(t("No skills installed."))}</div>`;
}

function renderMcp() {
  $("#mcpSummary").textContent = t("{count} configured connections", { count: state.mcp.length });
  $("#mcpList").innerHTML = state.mcp.length ? state.mcp.map((connection) => `
    <article class="capability-item">
      <div><strong>${escapeHtml(connection.name || connection.id)}</strong><small>${escapeHtml(connection.default_tool || t("Automatic tool selection"))}${connection.auto_invoke ? ` · ${escapeHtml(t("Auto"))}` : ""}</small></div>
      <div class="capability-item-actions">
        <button data-probe-mcp="${escapeHtml(connection.id)}">${escapeHtml(t("Test"))}</button>
        <button class="primary" data-chat-mcp="${escapeHtml(connection.id)}">${escapeHtml(t("Chat"))}</button>
        <button data-delete-mcp="${escapeHtml(connection.id)}">${escapeHtml(t("Delete"))}</button>
      </div>
    </article>`).join("") : `<div class="history-empty">${escapeHtml(t("No MCP connections configured."))}</div>`;
}

function updateCapabilityCount() {
  const total = Number(state.memory.stats?.active || 0) + state.skills.filter((skill) => skill.enabled).length + state.mcp.filter((item) => item.enabled).length;
  elements.capabilityCount.textContent = String(total);
}

async function refreshMemory(query = "") {
  state.memory = await window.signalasi.getDesktopMemory(query, 100);
  renderMemory();
  updateCapabilityCount();
}

async function refreshCapabilities() {
  try {
    const [memory, skills, mcp] = await Promise.all([
      window.signalasi.getDesktopMemory("", 100),
      window.signalasi.getDesktopSkills(),
      window.signalasi.getDesktopMcp()
    ]);
    state.memory = memory;
    state.skills = Array.isArray(skills.skills) ? skills.skills : [];
    state.mcp = Array.isArray(mcp.connections) ? mcp.connections : [];
    renderMemory();
    renderSkills();
    renderMcp();
    updateCapabilityCount();
  } catch (error) {
    $("#memorySummary").textContent = error.message || String(error);
  }
}

async function addMemory() {
  const content = $("#memoryContent").value.trim();
  if (!content) return;
  await window.signalasi.rememberDesktopMemory({ content, kind: "manual", importance: 0.8 });
  $("#memoryContent").value = "";
  showToast(t("Memory saved."));
  await refreshMemory($("#memorySearch").value.trim());
}

async function saveSkill() {
  const payload = {
    id: $("#skillId").value.trim().toLowerCase(),
    name: $("#skillName").value.trim(),
    description: "",
    triggers: parsePhrases($("#skillTriggers").value),
    instructions: $("#skillInstructions").value.trim(),
    enabled: true
  };
  if (!payload.id || !payload.name || !payload.triggers.length || !payload.instructions) {
    return showToast(t("Complete the skill ID, name, triggers, and instructions."));
  }
  await window.signalasi.saveDesktopSkill(payload);
  for (const id of ["#skillId", "#skillName", "#skillTriggers", "#skillInstructions"]) $(id).value = "";
  showToast(t("Skill added."));
  await refreshCapabilities();
}

async function saveMcp() {
  const payload = {
    id: $("#mcpId").value.trim().toLowerCase(),
    name: $("#mcpName").value.trim(),
    command: $("#mcpCommand").value.trim(),
    default_tool: $("#mcpTool").value.trim(),
    triggers: parsePhrases($("#mcpTriggers").value),
    enabled: true,
    auto_invoke: $("#mcpAutoInvoke").checked,
    timeout_seconds: 20
  };
  if (!payload.id || !payload.name || !payload.command) return showToast(t("Complete the MCP ID, name, and server command."));
  await window.signalasi.saveDesktopMcp(payload);
  for (const id of ["#mcpId", "#mcpName", "#mcpCommand", "#mcpTool", "#mcpTriggers"]) $(id).value = "";
  $("#mcpAutoInvoke").checked = false;
  showToast(t("MCP connection added."));
  await refreshCapabilities();
}

function selectCapabilityTab(name) {
  $$('[data-capability-tab]').forEach((button) => button.classList.toggle("active", button.dataset.capabilityTab === name));
  $$(".capability-pane").forEach((pane) => pane.classList.remove("active"));
  $(`#${name}Capability`)?.classList.add("active");
}

async function runDiagnostics() {
  const output = $("#diagnosticsOutput");
  output.hidden = false;
  output.textContent = t("Running diagnostics...");
  try {
    const [runtime, agents, pairing] = await Promise.all([
      window.signalasi.getRuntimeDiagnostics(),
      window.signalasi.getAgentDiagnostics(),
      window.signalasi.getPairingStatus()
    ]);
    output.textContent = JSON.stringify({ runtime, agents, pairing }, null, 2);
  } catch (error) {
    output.textContent = error.message || String(error);
  }
}

const PANEL_META = {
  agents: ["Agents", "Private agents and local execution engines"],
  capabilities: ["Capabilities", "Long-term memory, Skills, and MCP"],
  gateway: ["Mobile Gateway", "Trusted phones and SignalASI Link"],
  computer: ["Computer", "Local tools and desktop permissions"],
  settings: ["Settings", "Language, commands, and diagnostics"]
};

async function openPanel(name) {
  const meta = PANEL_META[name] || PANEL_META.settings;
  elements.drawerTitle.textContent = t(meta[0]);
  elements.drawerSubtitle.textContent = t(meta[1]);
  $$(".drawer-panel").forEach((panel) => panel.classList.remove("active"));
  $(`#${name}Panel`)?.classList.add("active");
  elements.backdrop.hidden = false;
  elements.drawer.classList.add("open");
  elements.drawer.setAttribute("aria-hidden", "false");
  if (name === "agents") await refreshAgents();
  if (name === "gateway") { await refreshGateway(); await loadPairingFrame(); }
  if (name === "computer") await refreshDesktopTools();
  if (name === "capabilities") await refreshCapabilities();
  if (name === "settings") { await refreshBackend(); await refreshAgents(); }
}

function closePanel() {
  elements.drawer.classList.remove("open");
  elements.drawer.setAttribute("aria-hidden", "true");
  window.setTimeout(() => { elements.backdrop.hidden = true; }, 180);
}

function latestTask() {
  return conversationTasks().at(-1) || null;
}

async function cancelRunningTask() {
  const task = [...conversationTasks()].reverse().find((item) => !TERMINAL_STATES.has(item.status));
  if (!task) {
    showToast(t("No task is currently running."));
    return;
  }
  await window.signalasi.cancelDesktopTask(task.task_id);
  $("#workspaceMenu").hidden = true;
  await refreshTasks(true);
}

async function retryTask(taskId) {
  try {
    const task = await window.signalasi.retryDesktopTask(taskId);
    state.tasks.push(task);
    state.renderingSignature = "";
    renderHistory();
    renderConversation(true);
  } catch (error) {
    showToast(`${t("Could not retry task")}: ${error.message || error}`);
  }
}

async function revealWorkspace() {
  const task = latestTask();
  if (!task) return showToast(t("This conversation has no task workspace yet."));
  try { await window.signalasi.revealTaskWorkspace(task.task_id); }
  catch (error) { showToast(error.message || String(error)); }
  $("#workspaceMenu").hidden = true;
}

async function deleteConversation() {
  if (!conversationTasks().length) return newTask();
  if (!window.confirm(t("Delete this conversation and its task history?"))) return;
  await window.signalasi.deleteDesktopConversation(state.currentConversationId);
  newTask();
  await refreshTasks(true);
  $("#workspaceMenu").hidden = true;
}

function startVoiceInput() {
  const Recognition = window.SpeechRecognition || window.webkitSpeechRecognition;
  if (!Recognition) {
    showToast(t("Voice input is not available on this desktop."));
    return;
  }
  if (state.speechRecognition) {
    state.speechRecognition.stop();
    return;
  }
  const recognition = new Recognition();
  state.speechRecognition = recognition;
  recognition.lang = state.language === "zh-CN" ? "zh-CN" : "en-US";
  recognition.interimResults = true;
  $("#voiceButton").classList.add("active");
  recognition.onresult = (event) => {
    elements.prompt.value = Array.from(event.results).map((result) => result[0].transcript).join("");
    updateSendState();
  };
  recognition.onerror = (event) => showToast(`${t("Voice input failed")}: ${event.error}`);
  recognition.onend = () => {
    state.speechRecognition = null;
    $("#voiceButton").classList.remove("active");
  };
  recognition.start();
}

function bindEvents() {
  $("#newTaskButton").addEventListener("click", () => newTask());
  $("#attachButton").addEventListener("click", addAttachments);
  $("#voiceButton").addEventListener("click", startVoiceInput);
  $("#agentPickerButton").addEventListener("click", () => openPanel("agents"));
  $("#sendButton").addEventListener("click", sendTask);
  $("#autoModeButton").addEventListener("click", () => { state.selectedAgentId = "auto"; state.selectedAgentName = t("Agent"); updateSelectedAgent(); });
  $("#localModeButton").addEventListener("click", () => { state.selectedAgentId = "desktop"; state.selectedAgentName = t("This desktop"); updateSelectedAgent(); });
  elements.prompt.addEventListener("input", updateSendState);
  elements.prompt.addEventListener("keydown", (event) => {
    if (event.key === "Enter" && !event.shiftKey && !event.isComposing) {
      event.preventDefault();
      sendTask();
    }
  });
  elements.history.addEventListener("click", (event) => {
    const item = event.target.closest("[data-conversation-id]");
    if (!item) return;
    state.currentConversationId = item.dataset.conversationId;
    state.renderingSignature = "";
    renderHistory();
    renderConversation(true);
  });
  elements.messages.addEventListener("click", async (event) => {
    const retry = event.target.closest("[data-retry-task]");
    if (retry) {
      await retryTask(retry.dataset.retryTask);
      return;
    }
    const toggle = event.target.closest("[data-toggle-run]");
    if (toggle) {
      const detail = elements.messages.querySelector(`[data-run-detail="${CSS.escape(toggle.dataset.toggleRun)}"]`);
      if (detail) detail.hidden = !detail.hidden;
      return;
    }
    const artifact = event.target.closest("[data-open-artifact]");
    if (artifact) {
      try { await window.signalasi.openTaskArtifact(artifact.dataset.taskId, artifact.dataset.openArtifact); }
      catch (error) { showToast(error.message || String(error)); }
    }
    const link = event.target.closest("[data-external-link]");
    if (link) {
      event.preventDefault();
      await window.signalasi.openExternal(link.dataset.externalLink);
    }
  });
  elements.attachments.addEventListener("click", (event) => {
    const button = event.target.closest("[data-remove-attachment]");
    if (!button) return;
    state.attachments.splice(Number(button.dataset.removeAttachment), 1);
    renderAttachmentTray();
  });
  $$('[data-open-panel]').forEach((button) => button.addEventListener("click", () => openPanel(button.dataset.openPanel)));
  $("#closeDrawer").addEventListener("click", closePanel);
  elements.backdrop.addEventListener("click", closePanel);
  $("#refreshAgentsButton").addEventListener("click", refreshAgents);
  $("#agentContactList").addEventListener("click", (event) => {
    const use = event.target.closest("[data-use-agent]");
    const chat = event.target.closest("[data-chat-agent]");
    const button = use || chat;
    if (!button) return;
    const id = button.dataset.useAgent || button.dataset.chatAgent;
    const name = agentName(id);
    if (chat) newTask(id, name);
    else { state.selectedAgentId = id; state.selectedAgentName = name; updateSelectedAgent(); }
    closePanel();
  });
  $("#saveCustomAgentButton").addEventListener("click", saveCustomAgent);
  $("#saveAgentCommandsButton").addEventListener("click", saveAgentCommands);
  $("#refreshGatewayButton").addEventListener("click", async () => { $("#pairingFrame").removeAttribute("src"); await refreshGateway(); await loadPairingFrame(); });
  $("#pairedClientList").addEventListener("click", async (event) => {
    const button = event.target.closest("[data-revoke-client]");
    if (!button || !window.confirm(t("Revoke this phone? It must scan the QR code again."))) return;
    await window.signalasi.clearPairing(button.dataset.revokeClient);
    await refreshGateway();
  });
  $("#readSystemStatusButton").addEventListener("click", readSystemStatus);
  $("#desktopExecutorEnabled").addEventListener("change", (event) =>
    updateDesktopControlSetting("enabled", event.target.checked).catch((error) => showToast(error.message || String(error))));
  $("#desktopControlRequireUnlocked").addEventListener("change", (event) =>
    updateDesktopControlSetting("require_unlocked", event.target.checked).catch((error) => showToast(error.message || String(error))));
  $("#showDesktopControlQrButton").addEventListener("click", () =>
    showDesktopControlPairingQr().catch((error) => showToast(error.message || String(error))));
  $("#computerPanel").addEventListener("click", (event) => {
    const button = event.target.closest("[data-control-action]");
    if (!button) return;
    const action = button.dataset.controlAction;
    if (action === "revoke" && !window.confirm(t("Revoke Desktop control for this phone?"))) return;
    runDesktopControlAuthorizationAction(button.dataset.controlId, action)
      .catch((error) => showToast(error.message || String(error)));
  });
  $$('[data-capability-tab]').forEach((button) => button.addEventListener("click", () => selectCapabilityTab(button.dataset.capabilityTab)));
  $("#refreshMemoryButton").addEventListener("click", () => refreshMemory($("#memorySearch").value.trim()));
  let memorySearchTimer = 0;
  $("#memorySearch").addEventListener("input", () => {
    window.clearTimeout(memorySearchTimer);
    memorySearchTimer = window.setTimeout(() => refreshMemory($("#memorySearch").value.trim()), 240);
  });
  $("#addMemoryButton").addEventListener("click", () => addMemory().catch((error) => showToast(error.message || String(error))));
  $("#memoryList").addEventListener("click", async (event) => {
    const button = event.target.closest("[data-forget-memory]");
    if (!button) return;
    await window.signalasi.forgetDesktopMemory(button.dataset.forgetMemory);
    await refreshMemory($("#memorySearch").value.trim());
  });
  $("#saveSkillButton").addEventListener("click", () => saveSkill().catch((error) => showToast(error.message || String(error))));
  $("#skillList").addEventListener("click", async (event) => {
    const toggle = event.target.closest("[data-toggle-skill]");
    const remove = event.target.closest("[data-delete-skill]");
    if (toggle) await window.signalasi.setDesktopSkillEnabled(toggle.dataset.toggleSkill, toggle.dataset.enabled !== "1");
    if (remove && window.confirm(t("Delete this skill?"))) await window.signalasi.deleteDesktopSkill(remove.dataset.deleteSkill);
    if (toggle || remove) await refreshCapabilities();
  });
  $("#saveMcpButton").addEventListener("click", () => saveMcp().catch((error) => showToast(error.message || String(error))));
  $("#mcpList").addEventListener("click", async (event) => {
    const probe = event.target.closest("[data-probe-mcp]");
    const chat = event.target.closest("[data-chat-mcp]");
    const remove = event.target.closest("[data-delete-mcp]");
    if (probe) {
      const result = await window.signalasi.probeDesktopMcp(probe.dataset.probeMcp);
      const names = (result.tools || []).map((tool) => tool.name).join(", ");
      showToast(result.status === "ready" ? `${t("MCP ready")}: ${names}` : `${t("MCP failed")}: ${result.error || ""}`);
    }
    if (chat) {
      const connection = state.mcp.find((item) => item.id === chat.dataset.chatMcp);
      newTask(`mcp:${chat.dataset.chatMcp}`, connection?.name || chat.dataset.chatMcp);
      closePanel();
    }
    if (remove && window.confirm(t("Delete this MCP connection?"))) {
      await window.signalasi.deleteDesktopMcp(remove.dataset.deleteMcp);
      await refreshCapabilities();
    }
  });
  $("#runDiagnosticsButton").addEventListener("click", runDiagnostics);
  $("#languageSelect").addEventListener("change", (event) => setLanguage(event.target.value));
  $("#workspaceMenuButton").addEventListener("click", () => { $("#workspaceMenu").hidden = !$("#workspaceMenu").hidden; });
  $("#cancelRunningTask").addEventListener("click", cancelRunningTask);
  $("#revealWorkspaceButton").addEventListener("click", revealWorkspace);
  $("#deleteConversationButton").addEventListener("click", deleteConversation);
  document.addEventListener("click", (event) => {
    if (!event.target.closest("#workspaceMenu") && !event.target.closest("#workspaceMenuButton")) $("#workspaceMenu").hidden = true;
  });
}

async function init() {
  bindEvents();
  await setLanguage(state.language, false);
  renderAgentContacts();
  updateAgentCounters();
  updateSelectedAgent();
  updateSendState();
  await refreshBackend();
  await Promise.all([refreshAgents(), refreshGateway(), refreshDesktopTools(), refreshCapabilities(), refreshTasks(true)]);
  window.setInterval(updateElapsedLabels, 1000);
  window.setInterval(() => refreshTasks(false), 1500);
  window.setInterval(() => { refreshBackend(); refreshGateway(); }, 30_000);
  window.setInterval(() => {
    if (elements.drawer.classList.contains("open") && $("#computerPanel").classList.contains("active")) {
      refreshDesktopControl();
    }
  }, 2_000);
}

init().catch((error) => showToast(error.stack || error.message || String(error)));
