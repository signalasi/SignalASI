const $ = (selector) => document.querySelector(selector);
const $$ = (selector) => Array.from(document.querySelectorAll(selector));

const TERMINAL_STATES = new Set(["completed", "failed", "cancelled", "timed_out"]);
const PEER_TIME_DIVIDER_GAP_MS = 30 * 60 * 1000;
const DEFAULT_AGENT_CONTACTS = [
  ["codex", "Codex", "local-cli"],
  ["hermes", "Hermes", "local-cli"],
  ["claude", "Claude Code", "local-cli"],
  ["gemini", "Gemini CLI", "local-cli"],
  ["openclaw", "OpenClaw", "local-cli"],
  ["local-llm", "Local LLM", "local-model"]
].map(([id, name, kind]) => ({ id, name, kind, status: "checking", detail: "Checking" }));
const ACP_AGENT_NAMES = Object.freeze({
  hermes: "Hermes",
  codex: "Codex",
  claude: "Claude Code",
  gemini: "Gemini CLI",
  openclaw: "OpenClaw"
});
const CLOUD_PROVIDER_PRESETS = Object.freeze({
  openai: {
    name: "OpenAI",
    endpoint: "https://api.openai.com/v1/chat/completions"
  },
  deepseek: {
    name: "DeepSeek",
    endpoint: "https://api.deepseek.com/chat/completions"
  },
  qwen: {
    name: "Qwen",
    endpoint: "https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions"
  },
  openrouter: {
    name: "OpenRouter",
    endpoint: "https://openrouter.ai/api/v1/chat/completions"
  },
  custom: {
    name: "Cloud Model",
    endpoint: ""
  }
});
const LANGUAGE_POLICY_CHOICES = new Set(["auto", "zh-CN", "en-US", "zh-HK", "zh-TW"]);
const FONT_SCALE_CHOICES = new Set([100, 115, 130, 145, 160]);
const TASK_BUDGET_PROFILES = Object.freeze({
  adaptive: {
    profile: "adaptive",
    max_elapsed_seconds: 0,
    max_cost_micros: 5_000_000,
    max_input_tokens: 1_000_000,
    max_output_tokens: 256_000,
    max_network_bytes: 256 * 1_048_576,
    minimum_battery_percent: 5,
    max_memory_bytes: 0,
    network_policy: "any",
    allow_cloud: true,
    allow_paid_providers: true
  },
  fast: {
    profile: "fast",
    max_elapsed_seconds: 300,
    max_cost_micros: 2_000_000,
    max_input_tokens: 256_000,
    max_output_tokens: 64_000,
    max_network_bytes: 128 * 1_048_576,
    minimum_battery_percent: 10,
    max_memory_bytes: 1536 * 1_048_576,
    network_policy: "any",
    allow_cloud: true,
    allow_paid_providers: true
  },
  economy: {
    profile: "economy",
    max_elapsed_seconds: 0,
    max_cost_micros: 250_000,
    max_input_tokens: 64_000,
    max_output_tokens: 16_000,
    max_network_bytes: 32 * 1_048_576,
    minimum_battery_percent: 15,
    max_memory_bytes: 768 * 1_048_576,
    network_policy: "any",
    allow_cloud: true,
    allow_paid_providers: true
  },
  private: {
    profile: "private",
    max_elapsed_seconds: 0,
    max_cost_micros: 5_000_000,
    max_input_tokens: 128_000,
    max_output_tokens: 32_000,
    max_network_bytes: 64 * 1_048_576,
    minimum_battery_percent: 10,
    max_memory_bytes: 1024 * 1_048_576,
    network_policy: "trusted_only",
    allow_cloud: false,
    allow_paid_providers: false
  }
});
const TASK_BUDGET_NETWORK_POLICIES = new Set([
  "any",
  "unmetered_only",
  "trusted_only",
  "offline_only"
]);

function boundedTaskBudgetNumber(value, fallback, maximum, integer = true) {
  const parsed = Number(value);
  if (!Number.isFinite(parsed)) return fallback;
  const bounded = Math.min(maximum, Math.max(0, parsed));
  return integer ? Math.round(bounded) : bounded;
}

function taskBudgetPreset(profile) {
  const normalized = Object.hasOwn(TASK_BUDGET_PROFILES, profile) ? profile : "adaptive";
  return { version: 1, ...TASK_BUDGET_PROFILES[normalized] };
}

function normalizeTaskBudget(value = {}) {
  const requestedProfile = String(value.profile || "adaptive").trim().toLowerCase();
  const profile = requestedProfile === "custom" || Object.hasOwn(TASK_BUDGET_PROFILES, requestedProfile)
    ? requestedProfile
    : "adaptive";
  const fallback = taskBudgetPreset(profile === "custom" ? "adaptive" : profile);
  return {
    version: 1,
    profile,
    max_elapsed_seconds: boundedTaskBudgetNumber(value.max_elapsed_seconds, fallback.max_elapsed_seconds, 604800, false),
    max_cost_micros: boundedTaskBudgetNumber(value.max_cost_micros, fallback.max_cost_micros, 1_000_000_000),
    max_input_tokens: boundedTaskBudgetNumber(value.max_input_tokens, fallback.max_input_tokens, 10_000_000),
    max_output_tokens: boundedTaskBudgetNumber(value.max_output_tokens, fallback.max_output_tokens, 10_000_000),
    max_network_bytes: boundedTaskBudgetNumber(value.max_network_bytes, fallback.max_network_bytes, 10 * 1_073_741_824),
    minimum_battery_percent: boundedTaskBudgetNumber(value.minimum_battery_percent, fallback.minimum_battery_percent, 100),
    max_memory_bytes: boundedTaskBudgetNumber(value.max_memory_bytes, fallback.max_memory_bytes, 16 * 1_073_741_824),
    network_policy: TASK_BUDGET_NETWORK_POLICIES.has(String(value.network_policy || ""))
      ? String(value.network_policy)
      : fallback.network_policy,
    allow_cloud: typeof value.allow_cloud === "boolean" ? value.allow_cloud : fallback.allow_cloud,
    allow_paid_providers: typeof value.allow_paid_providers === "boolean"
      ? value.allow_paid_providers
      : fallback.allow_paid_providers
  };
}

function loadTaskBudget() {
  try {
    return normalizeTaskBudget(JSON.parse(localStorage.getItem("galaxyssi-desktop-task-budget") || "{}"));
  } catch (_error) {
    return taskBudgetPreset("adaptive");
  }
}

function normalizeLanguagePolicy(value) {
  const candidate = String(value || "").trim();
  return LANGUAGE_POLICY_CHOICES.has(candidate) ? candidate : "auto";
}

function normalizeFontScale(value) {
  const scale = Number.parseInt(String(value ?? ""), 10);
  return FONT_SCALE_CHOICES.has(scale) ? scale : 130;
}

function systemLanguageTag() {
  const language = String(navigator.language || "en-US").replace("_", "-").toLowerCase();
  if (language.startsWith("zh-hk") || language.startsWith("zh-mo")) return "zh-HK";
  if (language.startsWith("zh-tw") || language.startsWith("zh-hant")) return "zh-TW";
  if (language.startsWith("zh")) return "zh-CN";
  return "en-US";
}

function resolveLanguagePolicy(value) {
  const normalized = normalizeLanguagePolicy(value);
  return normalized === "auto" ? systemLanguageTag() : normalized;
}

const savedInterfaceLanguage = localStorage.getItem("galaxyssi-desktop-language") || "auto";
const savedFontScale = normalizeFontScale(
  localStorage.getItem("galaxyssi-desktop-font-scale") || "130"
);
const state = {
  languagePreference: ["auto", "en", "zh-CN"].includes(savedInterfaceLanguage) ? savedInterfaceLanguage : "auto",
  language: savedInterfaceLanguage === "zh-CN" || (savedInterfaceLanguage === "auto" && systemLanguageTag().startsWith("zh")) ? "zh-CN" : "en",
  locale: {},
  fontScale: savedFontScale,
  backend: null,
  agents: DEFAULT_AGENT_CONTACTS,
  agentConfig: null,
  agentPerformance: {
    window: "7d",
    report: null,
    loading: false,
    requestId: 0
  },
  acpRuntime: null,
  pairing: null,
  pairingGrantDesktopExecutor: false,
  desktopControl: null,
  memory: {
    memories: [],
    history: [],
    candidates: [],
    evolution: {},
    visualization: {},
    stats: {},
    view: "overview",
    visualizationView: "state",
    selectedGraphNodeId: "",
    selectedEvidenceChainId: "",
    query: ""
  },
  skills: [],
  mcp: [],
  mcpAudit: [],
  marketplace: {
    items: [],
    summary: {},
    kind: ""
  },
  mcpImport: {
    sources: [],
    fileName: "",
    baseDirectory: "",
    content: "",
    sourceHint: "auto",
    preview: null
  },
  proactiveTasks: [],
  proactiveRuns: [],
  selectedProactiveTaskId: "",
  editingProactiveTaskId: "",
  runtime: { summary: {}, runtimes: [], error: "" },
  commands: { catalog_size: 0, roots: [], commands: [] },
  commandRuns: [],
  evolutionTasks: [],
  evolutionHealth: null,
  tasks: [],
  peerMessages: [],
  peerDirectoryRefreshPromise: null,
  activePeerRouteId: "",
  peerSendPending: false,
  pinnedConversationIds: new Set(JSON.parse(localStorage.getItem("galaxyssi-desktop-pinned-conversations") || "[]")),
  conversationSelectionMode: false,
  selectedConversationIds: new Set(),
  deletingConversationIds: new Set(),
  conversationDeletionPromise: null,
  hiddenEvolutionConversationIds: new Set(
    JSON.parse(localStorage.getItem("galaxyssi-desktop-hidden-evolution-conversations") || "[]")
  ),
  openConversationMenuId: "",
  currentConversationId: crypto.randomUUID(),
  selectedAgentId: "auto",
  selectedAgentName: "Agent",
  executionMode: localStorage.getItem("galaxyssi-desktop-execution-mode") === "plan_only"
    ? "plan_only"
    : "auto_complete",
  taskBudget: loadTaskBudget(),
  recoveryDiagnostics: {},
  attachments: [],
  attachmentDetails: new Map(),
  renderingSignature: "",
  polling: false,
  taskStream: null,
  taskStreamConnected: false,
  taskStreamReconnectTimer: 0,
  expandedTaskOutputs: new Map(),
  emptyConversationIntent: false,
  toastTimer: 0,
  speechRecognition: null,
  peerVoiceRecorder: null,
  peerVoiceStream: null,
  peerVoiceChunks: [],
  peerVoiceCancelled: false,
  peerVoiceStartedAtMs: 0,
  peerVoiceStarting: false,
  peerVoiceHolding: false,
  peerVoicePointerId: null,
  peerVoicePressStartY: null,
  peerVoiceCancelPending: false,
  peerVoiceTimer: 0,
  peerVoiceRouteId: "",
  peerVoicePlayback: null,
  peerImagePreviewCache: new Map(),
  peerImagePreviewLoads: new Map(),
  peerImagePreviewGeneration: 0,
  peerImageViewer: null,
  taskSpeechPlayback: null,
  taskSpeechRequestId: 0,
  agentRefreshPromise: null
};

const elements = {
  history: $("#taskHistory"),
  sidebarTaskSummary: $("#sidebarTaskSummary"),
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
  executionMode: $("#executionModeButton"),
  taskBudgetProfile: $("#taskBudgetProfileSelect"),
  agentCount: $("#agentCount"),
  capabilityCount: $("#capabilityCount"),
  gatewayCount: $("#gatewayCount"),
  desktopVersion: $("#desktopVersion"),
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

window.galaxyssiDesktopI18n = Object.freeze({
  translate: (key, params = {}) => t(key, params),
  language: () => state.language
});

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

function formatLatency(value) {
  const milliseconds = Math.max(0, Number(value || 0));
  if (milliseconds < 1000) return `${Math.round(milliseconds)}ms`;
  if (milliseconds < 10_000) return `${(milliseconds / 1000).toFixed(1)}s`;
  return formatDuration(milliseconds);
}

function relativeTime(timestamp) {
  const delta = Math.max(0, Date.now() - Number(timestamp || Date.now()));
  if (delta < 60_000) return t("Just now");
  if (delta < 3_600_000) return t("{count} min ago", { count: Math.floor(delta / 60_000) });
  if (delta < 86_400_000) return t("{count} hr ago", { count: Math.floor(delta / 3_600_000) });
  return new Date(Number(timestamp)).toLocaleDateString(state.language === "zh-CN" ? "zh-CN" : "en-US", { month: "short", day: "numeric" });
}

function peerDateKey(timestamp) {
  const date = new Date(Number(timestamp || Date.now()));
  return `${date.getFullYear()}-${date.getMonth()}-${date.getDate()}`;
}

function peerTimeLabel(timestamp) {
  const date = new Date(Number(timestamp || Date.now()));
  const now = new Date();
  const yesterday = new Date(now);
  yesterday.setDate(now.getDate() - 1);
  const time = `${String(date.getHours()).padStart(2, "0")}:${String(date.getMinutes()).padStart(2, "0")}`;
  if (peerDateKey(date.getTime()) === peerDateKey(now.getTime())) return time;
  if (peerDateKey(date.getTime()) === peerDateKey(yesterday.getTime())) return `${t("Yesterday")} ${time}`;
  return `${String(date.getMonth() + 1).padStart(2, "0")}/${String(date.getDate()).padStart(2, "0")} ${time}`;
}

function shouldShowPeerTimeDivider(messages, index) {
  if (index === 0) return true;
  const previous = Number(messages[index - 1]?.created_at_ms || 0);
  const current = Number(messages[index]?.created_at_ms || 0);
  return peerDateKey(previous) !== peerDateKey(current)
    || current - previous >= PEER_TIME_DIVIDER_GAP_MS;
}

function syncPromptPlaceholder() {
  const label = t(elements.prompt.dataset.i18nPlaceholder || "Tell GalaxySSI what to do...");
  elements.prompt.placeholder = state.activePeerRouteId ? "" : label;
  elements.prompt.setAttribute("aria-label", label);
  const voiceButton = $("#voiceButton");
  const voiceLabel = t(state.activePeerRouteId ? "Hold to talk" : "Voice input");
  voiceButton.title = voiceLabel;
  voiceButton.setAttribute("aria-label", voiceLabel);
}

function titleFromPrompt(prompt) {
  const clean = String(prompt || t("Attached files")).replace(/\s+/g, " ").trim();
  return clean.length > 42 ? `${clean.slice(0, 42)}...` : clean;
}

function statusLabel(status) {
  const labels = {
    accepted: "Accepted",
    queued: "Queued",
    pausing: "Pausing",
    paused: "Paused",
    takeover: "Manual control",
    recovering: "Recovering",
    waiting_input: "Waiting for input",
    waiting_approval: "Waiting for approval",
    running: "Running",
    completed: "Completed",
    failed: "Failed",
    cancelled: "Cancelled",
    timed_out: "Timed out"
  };
  return t(labels[status] || status || "Ready");
}

function taskStatusLabel(task) {
  if (task?.task_kind !== "self_evolution") return statusLabel(task?.status);
  const labels = {
    proposed: "Preparing",
    preparing: "Preparing",
    running: "Running",
    validating: "Validating",
    waiting_approval: "Candidate ready",
    publishing: "Publishing",
    published: "Pull request created",
    blocked: "Needs attention",
    failed: "Failed",
    cancelled: "Cancelled",
    rolled_back: "Rolled back"
  };
  return t(labels[task.evolution_status] || statusLabel(task.status));
}

function agentName(agentId) {
  if (!agentId || agentId === "auto") return t("Agent");
  if (agentId.startsWith("mcp:")) {
    const connection = state.mcp.find((item) => item.id === agentId.slice(4));
    return connection?.name || agentId.slice(4);
  }
  return state.agents.find((agent) => (agent.mobile_contact_id || agent.id) === agentId)?.name
    || ({ desktop: "GalaxySSI Desktop", "self-evolution": t("Self-evolution"), codex: "Codex", hermes: "Hermes", claude: "Claude Code", openclaw: "OpenClaw", "local-llm": "Local LLM" })[agentId]
    || agentId;
}

function taskRouteName(task) {
  if (!task) return t("Automatic routing");
  const primary = agentName(task.agent_id);
  return task.delegate_agent_id ? `${primary} · ${agentName(task.delegate_agent_id)}` : primary;
}

function taskExecutionView(task) {
  const view = task?.execution_view && typeof task.execution_view === "object"
    ? task.execution_view
    : {};
  const executorId = String(
    view.executor_id || task?.delegate_agent_id || task?.agent_id || "desktop"
  ).trim();
  return {
    executor: agentName(executorId),
    location: String(view.location_name || t("This desktop")).trim(),
    step: String(view.current_step || task?.current_step || taskStatusLabel(task)).trim(),
    cancellable: Boolean(
      view.cancellable ?? (task && !TERMINAL_STATES.has(task.status))
    ),
    pausable: Boolean(view.pausable),
    resumable: Boolean(view.resumable),
    takeoverAvailable: Boolean(view.takeover_available),
    takeoverActive: Boolean(view.takeover_active)
  };
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

function setFontScale(value, persist = true) {
  state.fontScale = normalizeFontScale(value);
  document.documentElement.style.fontSize = `${state.fontScale / 10}px`;
  document.documentElement.dataset.fontScale = String(state.fontScale);
  if (persist) {
    localStorage.setItem("galaxyssi-desktop-font-scale", String(state.fontScale));
  }
  const select = $("#fontScaleSelect");
  if (select) select.value = String(state.fontScale);
}

async function setLanguage(language, persist = true) {
  state.languagePreference = ["auto", "en", "zh-CN"].includes(language) ? language : "auto";
  state.language = state.languagePreference === "zh-CN"
    || (state.languagePreference === "auto" && systemLanguageTag().startsWith("zh"))
    ? "zh-CN"
    : "en";
  state.locale = await window.galaxyssi.loadLocale(state.language);
  document.documentElement.lang = state.language === "zh-CN" ? "zh-Hans" : "en";
  if (persist) localStorage.setItem("galaxyssi-desktop-language", state.languagePreference);
  $$('[data-i18n]').forEach((node) => { node.textContent = t(node.dataset.i18n); });
  $$('[data-i18n-placeholder]').forEach((node) => { node.placeholder = t(node.dataset.i18nPlaceholder); });
  $("#languageSelect").value = state.languagePreference;
  renderHistory();
  renderConversation(true);
  renderEvolutionTasks();
  updateHeaderStatus();
  updateExecutionMode();
  fillTaskBudgetSettings();
  document.dispatchEvent(new CustomEvent("galaxyssi:locale-changed", {
    detail: { language: state.language }
  }));
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

function conversationPreview(value, fallback = "") {
  const clean = String(value || fallback || "")
    .replace(/```[\s\S]*?```/g, " ")
    .replace(/<[^>]+>/g, " ")
    .replace(/\s+/g, " ")
    .trim();
  return clean.length > 62 ? `${clean.slice(0, 62)}...` : clean;
}

function peerConversationPreview(message) {
  const value = window.galaxyssiPeerConversationPreview.messagePreview(message, {
    voice: t("Voice"),
    image: t("Image"),
    file: t("File"),
    fallback: t("Paired device")
  });
  return conversationPreview(value, t("Paired device"));
}

function unifiedConversationGroups() {
  const taskGroups = conversationGroups().map((group) => {
    const ordered = [...group.tasks].sort((a, b) => Number(a.created_at) - Number(b.created_at));
    const latest = group.latest;
    const evolution = group.tasks.every((task) => task.task_kind === "self_evolution");
    return {
      kind: evolution ? "evolution" : "agent",
      id: group.id,
      title: titleFromPrompt(ordered[0]?.prompt),
      preview: conversationPreview(latest.result, latest.prompt || taskStatusLabel(latest)),
      updatedAt: Number(latest.updated_at || latest.created_at || 0),
      pinned: state.pinnedConversationIds.has(group.id),
      running: group.tasks.some((task) => !TERMINAL_STATES.has(task.status)),
      latest,
      tasks: group.tasks
    };
  }).filter((group) => (
    group.kind !== "evolution" || !state.hiddenEvolutionConversationIds.has(group.id)
  ));
  const peerGroups = pairedClients().map((client) => {
    const routeId = client.client_route_id || "";
    const latest = peerMessagesFor(routeId).at(-1);
    return {
      kind: "device",
      id: routeId,
      title: peerClientName(client),
      preview: peerConversationPreview(latest),
      updatedAt: Number(latest?.created_at_ms || client.paired_at_ms || client.updated_at_ms || 0),
      pinned: state.pinnedConversationIds.has(routeId),
      running: false,
      latest
    };
  }).filter((group) => group.id && group.latest);
  return [...taskGroups, ...peerGroups].sort((a, b) => Number(b.pinned) - Number(a.pinned) || b.updatedAt - a.updatedAt);
}

function persistPinnedConversations() {
  localStorage.setItem(
    "galaxyssi-desktop-pinned-conversations",
    JSON.stringify([...state.pinnedConversationIds])
  );
}

function persistHiddenEvolutionConversations() {
  localStorage.setItem(
    "galaxyssi-desktop-hidden-evolution-conversations",
    JSON.stringify([...state.hiddenEvolutionConversationIds])
  );
}

function renderConversationSelectionBar() {
  const bar = $("#conversationSelectionBar");
  bar.hidden = !state.conversationSelectionMode;
  $("#selectedConversationCount").textContent = String(state.selectedConversationIds.size);
  $("#selectAllConversationsButton").disabled = state.deletingConversationIds.size > 0;
  $("#deleteSelectedConversationsButton").disabled = (
    state.selectedConversationIds.size === 0 || state.deletingConversationIds.size > 0
  );
  $("#cancelConversationSelectionButton").disabled = state.deletingConversationIds.size > 0;
}

function renderHistory() {
  const groups = unifiedConversationGroups();
  const runningCount = groups.filter((group) =>
    group.kind !== "device" && group.running
  ).length;
  elements.sidebarTaskSummary.textContent = runningCount > 0
    ? `${runningCount}/${groups.length}`
    : String(groups.length);
  elements.sidebarTaskSummary.classList.toggle("active", runningCount > 0);
  elements.sidebarTaskSummary.title = runningCount > 0
    ? `${runningCount} ${t("running")}`
    : t("Ready");
  elements.sidebarTaskSummary.setAttribute(
    "aria-label",
    `${groups.length} ${t("Chats")}; ${runningCount} ${t("running")}`
  );
  if (!groups.length) {
    elements.history.innerHTML = `<div class="history-empty">${escapeHtml(t("Chats will appear here after you send a task or message a paired device."))}</div>`;
    renderConversationSelectionBar();
    return;
  }
  const todayStart = new Date();
  todayStart.setHours(0, 0, 0, 0);
  let currentSection = "";
  const html = [];
  for (const group of groups) {
    const section = group.pinned ? "Pinned" : (group.updatedAt >= todayStart.getTime() ? "Today" : "Earlier");
    if (section !== currentSection) {
      currentSection = section;
      html.push(`<div class="history-group-label ${section === "Pinned" ? "pinned" : ""}">${escapeHtml(t(section))}</div>`);
    }
    const running = group.kind !== "device" && group.running;
    const typeLabel = group.kind === "device" ? t("Device") : "";
    const latestLabel = running ? taskStatusLabel(group.latest) : relativeTime(group.updatedAt);
    const targetAttribute = group.kind === "device"
      ? `data-peer-route="${escapeHtml(group.id)}"`
      : `data-conversation-id="${escapeHtml(group.id)}"`;
    const active = group.kind === "device"
      ? group.id === state.activePeerRouteId
      : !state.activePeerRouteId && group.id === state.currentConversationId;
    const selecting = state.conversationSelectionMode;
    const selected = state.selectedConversationIds.has(group.id);
    const menuOpen = state.openConversationMenuId === group.id;
    html.push(`<div class="history-item-shell ${selecting ? "selecting" : ""}">
      ${selecting ? `<button class="history-select ${selected ? "selected" : ""}" data-select-conversation="${escapeHtml(group.id)}" aria-label="${escapeHtml(t("Select conversation"))}"></button>` : ""}
      <button class="history-item ${active ? "active" : ""}" ${targetAttribute}>
        <span class="history-title-row"><strong>${escapeHtml(group.title)}</strong>${typeLabel ? `<i>${escapeHtml(typeLabel)}</i>` : ""}<time>${escapeHtml(latestLabel)}</time></span>
        <span class="history-preview ${running ? "running" : ""}">${escapeHtml(group.preview)}</span>
      </button>
      ${!selecting ? `<button class="history-more" data-conversation-menu="${escapeHtml(group.id)}" aria-label="${escapeHtml(t("Conversation actions"))}"></button>` : ""}
      ${menuOpen ? `<div class="history-item-menu">
        <button data-pin-conversation="${escapeHtml(group.id)}">${escapeHtml(t(group.pinned ? "Unpin" : "Pin"))}</button>
        <button class="danger-text" data-delete-conversation="${escapeHtml(group.id)}">${escapeHtml(t("Delete"))}</button>
      </div>` : ""}
    </div>`);
  }
  elements.history.innerHTML = html.join("");
  renderConversationSelectionBar();
}

function pairedClients() {
  return Array.isArray(state.pairing?.clients) ? state.pairing.clients : [];
}

function refreshPeerDirectoryForRoute(routeId) {
  if (window.galaxyssiPeerConversationPreview.hasClientRoute(pairedClients(), routeId)) return null;
  if (state.peerDirectoryRefreshPromise) return state.peerDirectoryRefreshPromise;
  const refresh = refreshGateway();
  const trackedRefresh = refresh.finally(() => {
    if (state.peerDirectoryRefreshPromise === trackedRefresh) state.peerDirectoryRefreshPromise = null;
  });
  state.peerDirectoryRefreshPromise = trackedRefresh;
  return trackedRefresh;
}

function peerClientName(client) {
  return client?.display_name || client?.device_name || client?.profile_name || t("GalaxySSI phone");
}

function peerMessagesFor(routeId = state.activePeerRouteId) {
  return state.peerMessages
    .filter((message) => message.client_route_id === routeId)
    .sort((a, b) => Number(a.created_at_ms) - Number(b.created_at_ms));
}

function renderPeerAttachments(message) {
  const attachments = Array.isArray(message.attachments) ? message.attachments : [];
  if (!attachments.length) return "";
  return `<div class="peer-attachment-list">${attachments.map((file, index) => {
    const extension = String(file.name || "file").split(".").pop().slice(0, 5).toUpperCase();
    const isAudio = String(file.mime_type || "").toLowerCase().startsWith("audio/");
    const isImage = String(file.mime_type || "").toLowerCase().startsWith("image/");
    if (isAudio) {
      const duration = Number(file.duration_ms || 0);
      const label = duration > 0
        ? `${t("Voice message")} · ${formatDuration(duration)}`
        : t("Voice message");
      return `<button class="peer-voice-message" data-play-peer-voice="${index}" data-peer-message-id="${escapeHtml(message.message_id)}" ${file.available === false ? "disabled" : ""} aria-label="${escapeHtml(t("Play voice message"))}">
        <span class="peer-voice-icon" aria-hidden="true">▶</span>
        <span class="peer-voice-wave" aria-hidden="true"><i></i><i></i><i></i><i></i><i></i><i></i><i></i></span>
        <b class="peer-voice-label">${escapeHtml(label)}</b>
      </button>`;
    }
    if (isImage) {
      const name = file.name || t("Image");
      return `<button class="peer-image-attachment" data-view-peer-image="${index}" data-peer-message-id="${escapeHtml(message.message_id)}" data-peer-image-name="${escapeHtml(name)}" ${file.available === false ? "disabled" : ""} aria-label="${escapeHtml(t("Open image"))}">
        <span class="peer-image-placeholder">${escapeHtml(t(file.available === false ? "Image unavailable" : "Loading image"))}</span>
        <img data-peer-image-preview="${index}" data-peer-message-id="${escapeHtml(message.message_id)}" alt="${escapeHtml(name)}">
        <span class="peer-image-meta"><b>${escapeHtml(name)}</b><small>${escapeHtml(formatBytes(file.size_bytes))}</small></span>
      </button>`;
    }
    const kind = extension;
    return `<button class="peer-attachment" data-open-peer-attachment="${index}" data-peer-message-id="${escapeHtml(message.message_id)}" ${file.available === false ? "disabled" : ""}>
      <span>${escapeHtml(kind)}</span><b>${escapeHtml(file.name || t("File"))}</b><small>${escapeHtml(formatBytes(file.size_bytes))}</small>
    </button>`;
  }).join("")}</div>`;
}

function peerImagePreviewKey(messageId, attachmentIndex) {
  return `${String(messageId)}:${Number(attachmentIndex)}`;
}

function ipcBinaryBytes(encoded) {
  if (encoded instanceof ArrayBuffer) return new Uint8Array(encoded);
  if (ArrayBuffer.isView(encoded)) return new Uint8Array(encoded.buffer, encoded.byteOffset, encoded.byteLength);
  if (Array.isArray(encoded?.data)) return Uint8Array.from(encoded.data);
  return new Uint8Array();
}

function releasePeerImagePreviews(keepKeys = new Set()) {
  for (const [key, preview] of state.peerImagePreviewCache.entries()) {
    if (keepKeys.has(key) || state.peerImageViewer?.key === key) continue;
    URL.revokeObjectURL(preview.objectUrl);
    state.peerImagePreviewCache.delete(key);
  }
}

function resetPeerImagePreviews() {
  state.peerImagePreviewGeneration += 1;
  state.peerImagePreviewLoads.clear();
  releasePeerImagePreviews();
}

async function loadPeerImagePreview(messageId, attachmentIndex) {
  const key = peerImagePreviewKey(messageId, attachmentIndex);
  const generation = state.peerImagePreviewGeneration;
  if (state.peerImagePreviewCache.has(key)) return state.peerImagePreviewCache.get(key);
  if (state.peerImagePreviewLoads.has(key)) return state.peerImagePreviewLoads.get(key);
  const loading = (async () => {
    const result = await window.galaxyssi.loadPeerImage(messageId, attachmentIndex);
    const bytes = ipcBinaryBytes(result?.arrayBuffer);
    if (!bytes.byteLength) throw new Error(t("Image unavailable"));
    if (generation !== state.peerImagePreviewGeneration) {
      bytes.fill(0);
      throw new Error(t("Image unavailable"));
    }
    const blob = new Blob([bytes], { type: result.mimeType || "image/jpeg" });
    const preview = {
      key,
      name: result.name || t("Image"),
      mimeType: result.mimeType || "image/jpeg",
      objectUrl: URL.createObjectURL(blob)
    };
    bytes.fill(0);
    state.peerImagePreviewCache.set(key, preview);
    while (state.peerImagePreviewCache.size > 24) {
      const oldestKey = state.peerImagePreviewCache.keys().next().value;
      if (!oldestKey || oldestKey === state.peerImageViewer?.key) break;
      URL.revokeObjectURL(state.peerImagePreviewCache.get(oldestKey).objectUrl);
      state.peerImagePreviewCache.delete(oldestKey);
    }
    return preview;
  })();
  state.peerImagePreviewLoads.set(key, loading);
  try {
    return await loading;
  } finally {
    if (state.peerImagePreviewLoads.get(key) === loading) state.peerImagePreviewLoads.delete(key);
  }
}

function applyPeerImagePreview(image, preview) {
  if (!image?.isConnected) return;
  image.src = preview.objectUrl;
  image.closest(".peer-image-attachment")?.classList.add("loaded");
}

function hydratePeerImagePreviews() {
  const images = Array.from(elements.messages.querySelectorAll("[data-peer-image-preview]"));
  const liveKeys = new Set(images.map((image) => peerImagePreviewKey(
    image.dataset.peerMessageId,
    image.dataset.peerImagePreview
  )));
  releasePeerImagePreviews(liveKeys);
  images.forEach((image) => {
    const messageId = image.dataset.peerMessageId || "";
    const attachmentIndex = Number(image.dataset.peerImagePreview);
    const key = peerImagePreviewKey(messageId, attachmentIndex);
    const cached = state.peerImagePreviewCache.get(key);
    if (cached) {
      applyPeerImagePreview(image, cached);
      return;
    }
    loadPeerImagePreview(messageId, attachmentIndex)
      .then((preview) => {
        const current = elements.messages.querySelector(
          `[data-peer-image-preview="${attachmentIndex}"][data-peer-message-id="${CSS.escape(messageId)}"]`
        );
        applyPeerImagePreview(current, preview);
      })
      .catch((error) => {
        if (!image.isConnected) return;
        const card = image.closest(".peer-image-attachment");
        card?.classList.add("failed");
        const placeholder = card?.querySelector(".peer-image-placeholder");
        if (placeholder) placeholder.textContent = t("Image unavailable");
        card?.setAttribute("title", error.message || String(error));
      });
  });
}

function closePeerImageViewer() {
  const viewer = $("#peerImageViewer");
  viewer.hidden = true;
  $("#peerImageViewerImage").removeAttribute("src");
  state.peerImageViewer = null;
}

async function openPeerImageViewer(button) {
  const messageId = String(button.dataset.peerMessageId || "");
  const attachmentIndex = Number(button.dataset.viewPeerImage);
  const key = peerImagePreviewKey(messageId, attachmentIndex);
  button.classList.add("loading");
  try {
    const preview = await loadPeerImagePreview(messageId, attachmentIndex);
    state.peerImageViewer = {
      key,
      messageId,
      attachmentIndex,
      name: button.dataset.peerImageName || preview.name
    };
    $("#peerImageViewerImage").src = preview.objectUrl;
    $("#peerImageViewerName").textContent = state.peerImageViewer.name;
    $("#peerImageViewer").hidden = false;
    $("#savePeerImageButton").focus();
  } finally {
    button.classList.remove("loading");
  }
}

async function saveViewedPeerImage() {
  const viewer = state.peerImageViewer;
  if (!viewer) return;
  const button = $("#savePeerImageButton");
  button.disabled = true;
  try {
    const result = await window.galaxyssi.savePeerAttachment(viewer.messageId, viewer.attachmentIndex);
    if (result?.ok) showToast(`${t("Saved")}: ${result.name}`);
  } catch (error) {
    showToast(`${t("Save failed")}: ${error.message || error}`);
  } finally {
    button.disabled = false;
  }
}

function renderPeerConversation(force = false) {
  syncPromptPlaceholder();
  const client = pairedClients().find((item) => item.client_route_id === state.activePeerRouteId);
  const messages = peerMessagesFor();
  const signature = JSON.stringify(messages.map((message) => [
    message.message_id,
    message.created_at_ms,
    message.delivery_status,
    message.content,
    (message.attachments || []).map((file) => [
      file.name, file.mime_type, file.size_bytes, file.duration_ms, file.sha256, file.available
    ])
  ]));
  if (!force && signature === state.renderingSignature) return;
  state.renderingSignature = signature;
  const wasNearBottom = elements.stream.scrollHeight - elements.stream.scrollTop - elements.stream.clientHeight < 140;
  elements.empty.hidden = messages.length > 0;
  elements.empty.querySelector("h2").textContent = t("Direct message");
  elements.empty.querySelector("p").textContent = t("Messages and files are end-to-end encrypted between paired devices.");
  elements.messages.innerHTML = messages.map((message, index) => {
    const createdAt = Number(message.created_at_ms) || Date.now();
    const deliveryLabel = message.delivery_status === "sent"
      ? t("Sent")
      : message.delivery_status === "failed"
        ? t("Failed")
        : t("Queued");
    const voiceOnly = !message.content && (message.attachments || []).length > 0 &&
      (message.attachments || []).every((file) => String(file.mime_type || "").toLowerCase().startsWith("audio/"));
    const imageOnly = !message.content && (message.attachments || []).length > 0 &&
      (message.attachments || []).every((file) => String(file.mime_type || "").toLowerCase().startsWith("image/"));
    const timeDivider = shouldShowPeerTimeDivider(messages, index)
      ? `<div class="peer-time-divider"><time datetime="${new Date(createdAt).toISOString()}">${escapeHtml(peerTimeLabel(createdAt))}</time></div>`
      : "";
    return `${timeDivider}<article class="peer-message-row ${message.direction}">
    <div class="peer-message-bubble${voiceOnly ? " voice-only" : ""}${imageOnly ? " image-only" : ""}">
      ${message.content ? `<p>${escapeHtml(message.content)}</p>` : ""}
      ${renderPeerAttachments(message)}
    </div>
    ${message.direction === "outbound" ? `<small class="peer-message-delivery">${escapeHtml(deliveryLabel)}</small>` : ""}
  </article>`;
  }).join("");
  hydratePeerImagePreviews();
  syncPeerVoicePlaybackUi();
  elements.title.textContent = client ? peerClientName(client) : t("Device contact");
  elements.taskState.textContent = "";
  elements.taskState.className = "";
  elements.route.textContent = t("GalaxySSI Link encrypted");
  if (force || wasNearBottom) requestAnimationFrame(() => { elements.stream.scrollTop = elements.stream.scrollHeight; });
}

function openPeerConversation(routeId) {
  if (state.activePeerRouteId !== routeId) {
    closePeerImageViewer();
    resetPeerImagePreviews();
    clearPeerVoicePlayback();
    finishPeerVoiceHold(false);
  }
  state.activePeerRouteId = routeId;
  state.emptyConversationIntent = false;
  state.renderingSignature = "";
  document.querySelector("#agentApp").classList.add("peer-mode");
  renderHistory();
  renderPeerConversation(true);
  elements.prompt.focus();
}

async function refreshPeerMessages() {
  try {
    const response = await window.galaxyssi.listPeerMessages("", 2000);
    state.peerMessages = Array.isArray(response.messages) ? response.messages : [];
    renderHistory();
    if (state.activePeerRouteId) renderPeerConversation();
  } catch (error) {
    if (!state.taskStreamConnected) console.warn("Peer message refresh failed", error);
  }
}

function clearPeerRuntimePlaintext() {
  closePeerImageViewer();
  resetPeerImagePreviews();
  clearPeerVoicePlayback();
  clearTaskSpeechPlayback();
  state.peerVoiceHolding = false;
  state.peerVoiceCancelled = true;
  if (state.peerVoiceRecorder?.state === "recording") state.peerVoiceRecorder.stop();
  state.peerVoiceStream?.getTracks().forEach((track) => track.stop());
  state.peerMessages = [];
  state.attachments = [];
  state.attachmentDetails.clear();
  state.renderingSignature = "";
  renderAttachmentTray();
  if (state.activePeerRouteId) renderPeerConversation(true);
  renderHistory();
}

function peerVoiceButtons(messageId, attachmentIndex) {
  return Array.from(elements.messages.querySelectorAll("[data-play-peer-voice]"))
    .filter((button) => button.dataset.peerMessageId === String(messageId) &&
      Number(button.dataset.playPeerVoice) === Number(attachmentIndex));
}

function syncPeerVoicePlaybackUi() {
  const playback = state.peerVoicePlayback;
  elements.messages.querySelectorAll("[data-play-peer-voice]").forEach((button) => {
    const active = playback &&
      button.dataset.peerMessageId === playback.messageId &&
      Number(button.dataset.playPeerVoice) === playback.attachmentIndex;
    const playing = Boolean(active && !playback.audio.paused && !playback.audio.ended);
    button.classList.toggle("playing", playing);
    const icon = button.querySelector(".peer-voice-icon");
    if (icon) icon.textContent = playing ? "Ⅱ" : "▶";
  });
}

function clearPeerVoicePlayback() {
  const playback = state.peerVoicePlayback;
  if (!playback) return;
  state.peerVoicePlayback = null;
  if (playback.cleanupTimer) clearTimeout(playback.cleanupTimer);
  playback.audio.pause();
  playback.audio.removeAttribute("src");
  playback.audio.load();
  playback.bytes?.fill(0);
  URL.revokeObjectURL(playback.objectUrl);
  syncPeerVoicePlaybackUi();
}

async function togglePeerVoicePlayback(button) {
  const messageId = String(button.dataset.peerMessageId || "");
  const attachmentIndex = Number(button.dataset.playPeerVoice);
  const active = state.peerVoicePlayback;
  if (active && active.messageId === messageId && active.attachmentIndex === attachmentIndex) {
    if (active.audio.paused || active.audio.ended) {
      if (active.audio.ended) active.audio.currentTime = 0;
      await active.audio.play();
    } else {
      active.audio.pause();
    }
    syncPeerVoicePlaybackUi();
    return;
  }

  clearPeerVoicePlayback();
  button.classList.add("loading");
  try {
    const result = await window.galaxyssi.loadPeerVoice(messageId, attachmentIndex);
    const encoded = result?.arrayBuffer;
    const bytes = encoded instanceof ArrayBuffer
      ? new Uint8Array(encoded)
      : ArrayBuffer.isView(encoded)
        ? new Uint8Array(encoded.buffer, encoded.byteOffset, encoded.byteLength)
        : Array.isArray(encoded?.data)
          ? Uint8Array.from(encoded.data)
          : new Uint8Array();
    if (!bytes.byteLength) throw new Error(t("Voice message is unavailable"));
    const isOgg = bytes.byteLength >= 4
      && bytes[0] === 0x4f && bytes[1] === 0x67 && bytes[2] === 0x67 && bytes[3] === 0x53;
    const mimeType = isOgg ? "audio/ogg; codecs=opus" : (result.mimeType || "audio/ogg");
    const blob = new Blob([bytes], { type: mimeType });
    const objectUrl = URL.createObjectURL(blob);
    const audio = new Audio(objectUrl);
    audio.preload = "auto";
    const playback = { messageId, attachmentIndex, audio, objectUrl, bytes, cleanupTimer: 0 };
    state.peerVoicePlayback = playback;
    audio.addEventListener("loadedmetadata", () => {
      playback.bytes?.fill(0);
      playback.bytes = null;
      if (!Number.isFinite(audio.duration) || audio.duration <= 0) return;
      peerVoiceButtons(messageId, attachmentIndex).forEach((voiceButton) => {
        const label = voiceButton.querySelector(".peer-voice-label");
        if (label) label.textContent = `${t("Voice message")} · ${formatDuration(audio.duration * 1000)}`;
      });
    });
    audio.addEventListener("play", syncPeerVoicePlaybackUi);
    audio.addEventListener("pause", syncPeerVoicePlaybackUi);
    audio.addEventListener("ended", () => {
      syncPeerVoicePlaybackUi();
      playback.cleanupTimer = window.setTimeout(() => {
        if (state.peerVoicePlayback === playback) clearPeerVoicePlayback();
      }, 30_000);
    });
    audio.addEventListener("error", () => {
      if (state.peerVoicePlayback === playback) clearPeerVoicePlayback();
      showToast(t("Voice message could not be played"));
    });
    await audio.play();
    syncPeerVoicePlaybackUi();
  } finally {
    button.classList.remove("loading");
  }
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

function renderLatencySummary(task) {
  const latency = task?.latency && typeof task.latency === "object" ? task.latency : null;
  const stages = Array.isArray(latency?.stages) ? latency.stages : [];
  if (!latency || !stages.length) return "";
  const rawFirstOutput = latency.first_output_ms;
  const firstOutput = Number(rawFirstOutput);
  const total = Math.max(0, Number(latency.total_ms || 0));
  return `<div class="latency-summary">
    ${rawFirstOutput != null && Number.isFinite(firstOutput) && firstOutput >= 0
      ? `<span>${escapeHtml(t("First output"))}<strong>${escapeHtml(formatLatency(firstOutput))}</strong></span>`
      : ""}
    <span>${escapeHtml(t("Traced time"))}<strong>${escapeHtml(formatLatency(total))}</strong></span>
  </div>`;
}

function renderRecoveryDiagnostic(taskId) {
  const diagnostic = state.recoveryDiagnostics[taskId];
  if (!diagnostic) return "";
  const available = Array.isArray(diagnostic.available_agent_ids)
    ? diagnostic.available_agent_ids.map(agentName).join(", ")
    : "";
  return `<div class="recovery-diagnostic">
    <strong>${escapeHtml(t("Failure diagnostics"))}</strong>
    <span>${escapeHtml(t("Failure type"))}: ${escapeHtml(diagnostic.failure_kind || "unknown")}</span>
    <span>${escapeHtml(t("Agent status"))}: ${escapeHtml(diagnostic.agent_status || "unknown")}</span>
    ${diagnostic.error ? `<span>${escapeHtml(diagnostic.error)}</span>` : ""}
    ${available ? `<span>${escapeHtml(t("Available Agents"))}: ${escapeHtml(available)}</span>` : ""}
  </div>`;
}

function renderRecoveryActions(task) {
  const actions = Array.isArray(task.recovery_actions)
    ? task.recovery_actions
    : [];
  if (!actions.length) {
    return `<button class="recovery-action" data-recovery-task="${escapeHtml(task.task_id)}" data-recovery-action="retry">${escapeHtml(t("Retry"))}</button>`;
  }
  return `<div class="recovery-panel">
    <strong>${escapeHtml(t("Choose how to continue"))}</strong>
    <div class="recovery-actions">${actions.map((action) => {
      const candidates = Array.isArray(action.candidate_agent_ids) ? action.candidate_agent_ids : [];
      return `<button
        class="recovery-action ${action.recommended ? "recommended" : ""}"
        data-recovery-task="${escapeHtml(task.task_id)}"
        data-recovery-action="${escapeHtml(action.action || "")}"
        data-recovery-agent="${escapeHtml(candidates[0] || "")}"
        title="${escapeHtml(t(action.reason || action.description || ""))}"
        ${action.enabled ? "" : "disabled"}
      >${escapeHtml(t(action.label || action.action || "Continue"))}</button>`;
    }).join("")}</div>
    ${renderRecoveryDiagnostic(task.task_id)}
  </div>`;
}

function renderTaskEvent(event = {}) {
  const metadata = event.metadata && typeof event.metadata === "object"
    ? event.metadata
    : {};
  if (event.kind === "mcp" && metadata.kind === "mcp_tool_call") {
    const permissions = Array.isArray(metadata.permissions) && metadata.permissions.length
      ? metadata.permissions.join(" · ")
      : t("No additional permissions");
    const parameters = metadata.parameter_preview
      && typeof metadata.parameter_preview === "object"
      && Object.keys(metadata.parameter_preview).length
      ? JSON.stringify(metadata.parameter_preview)
      : t("No parameters");
    const risk = String(metadata.risk || "medium");
    const status = event.status === "completed"
      ? "Succeeded"
      : event.status === "failed"
        ? (metadata.status === "denied" ? "Denied" : "Failed")
        : "Running";
    return `<div class="event-row mcp-tool-event ${escapeHtml(event.status || "")}">
      <span></span>
      <div class="mcp-tool-event-body">
        <div class="mcp-tool-event-heading">
          <strong>${escapeHtml(event.title || t("MCP tool"))}</strong>
          <span class="mcp-risk ${escapeHtml(risk)}">${escapeHtml(t(risk.replace(/^./, (value) => value.toUpperCase())))}</span>
        </div>
        <small>${escapeHtml(t(status))} · ${escapeHtml(t("Source"))}: ${escapeHtml(metadata.source || "")}</small>
        <small>${escapeHtml(t("Permissions"))}: ${escapeHtml(permissions)}</small>
        <small>${escapeHtml(t("Parameters"))}</small>
        <code>${escapeHtml(parameters)}</code>
      </div>
    </div>`;
  }
  return `<div class="event-row ${escapeHtml(event.status || "")}">
    <span></span>
    <div><strong>${escapeHtml(t(event.title || "Task step"))}</strong>${event.detail ? `<small>${escapeHtml(event.detail)}</small>` : ""}</div>
  </div>`;
}

function expandedTaskOutput(task) {
  const cached = state.expandedTaskOutputs.get(task.task_id);
  if (!cached || cached.sha256 !== String(task.result_sha256 || "")) return null;
  return cached;
}

function renderTaskOutput(task, fallbackText) {
  const cached = expandedTaskOutput(task);
  const rendered = cached?.chunks?.length
    ? cached.chunks.map((chunk) =>
      `<section class="assistant-output-chunk">${renderMarkdown(chunk)}</section>`
    ).join("")
    : renderMarkdown(fallbackText);
  if (!task.result_chunked) return rendered;
  const buttonLabel = cached?.loading
    ? t("Loading full output")
    : (cached?.error ? t("Retry full output") : t("Show full output"));
  const button = cached?.done
    ? ""
    : `<button class="load-full-output" data-load-task-output="${escapeHtml(task.task_id)}" ${cached?.loading ? "disabled" : ""}>${escapeHtml(buttonLabel)}</button>`;
  const loadedCharacters = cached?.chunks?.reduce(
    (total, chunk) => total + textCharacterCount(chunk),
    0
  ) || 0;
  const progress = cached?.loading || cached?.error
    ? `<small class="output-load-state" data-output-load-state="${escapeHtml(task.task_id)}">${cached?.error ? escapeHtml(cached.error) : escapeHtml(`${loadedCharacters} / ${Number(task.result_length || 0)}`)}</small>`
    : "";
  return `<div class="assistant-output" data-output-task="${escapeHtml(task.task_id)}">
    <div class="assistant-output-chunks" data-output-chunks="${escapeHtml(task.task_id)}">${rendered}</div>
    <div class="assistant-output-controls">${button}${progress}</div>
  </div>`;
}

function renderTurn(task) {
  const statusClass = task.status === "completed" ? "completed" : (TERMINAL_STATES.has(task.status) ? "failed" : "");
  const isEvolution = task.task_kind === "self_evolution";
  const evolutionMetadata = isEvolution && (task.candidate_commit || task.pull_request_url)
    ? `<div class="evolution-result-meta">${task.candidate_commit ? `<span>${escapeHtml(t("Candidate"))} <code>${escapeHtml(task.candidate_commit.slice(0, 10))}</code></span>` : ""}${task.pull_request_url ? `<a href="${escapeHtml(task.pull_request_url)}" data-external-link="${escapeHtml(task.pull_request_url)}">${escapeHtml(t("Open pull request"))}</a>` : ""}</div>`
    : "";
  const answerText = isEvolution
    ? t(task.result || "The self-evolution run completed.")
    : (task.result || t("Task completed."));
  const answer = task.status === "completed"
    ? `<article class="assistant-answer">${renderTaskOutput(task, answerText)}${evolutionMetadata}<div class="assistant-actions"><button data-speak-task="${escapeHtml(task.task_id)}">${escapeHtml(t("Read aloud"))}</button></div></article>${renderArtifacts(task)}`
    : (TERMINAL_STATES.has(task.status)
      ? `<article class="assistant-answer error-answer">${escapeHtml(task.error || task.result || t("The task could not be completed."))}${renderRecoveryActions(task)}</article>`
      : "");
  const events = Array.isArray(task?.run_timeline?.events)
    ? task.run_timeline.events
    : (Array.isArray(task.events) ? task.events : []);
  const latencySummary = renderLatencySummary(task);
  const detail = events.length
    ? `<div class="event-list">${events.map(renderTaskEvent).join("")}</div>`
    : escapeHtml(task.current_step ? t(task.current_step) : `${agentName(task.agent_id)} · ${statusLabel(task.status)}`);
  const attachments = Array.isArray(task.attachments) ? task.attachments : [];
  const attachmentRows = attachments.length
    ? `<div class="user-attachments">${attachments.map((path) => `<span title="${escapeHtml(path)}">${escapeHtml(String(path).split(/[\\/]/).pop() || path)}</span>`).join("")}</div>`
    : "";
  const originLabel = isEvolution
    ? `<small class="task-origin">${escapeHtml(t(task.automatic ? "Automatic self-evolution" : "Self-evolution"))}</small>`
    : "";
  const detailHidden = isEvolution && !TERMINAL_STATES.has(task.status) ? "" : "hidden";
  const execution = taskExecutionView(task);
  const runControls = [
    execution.pausable
      ? `<button class="run-control" data-pause-task="${escapeHtml(task.task_id)}">${escapeHtml(t("Pause"))}</button>`
      : "",
    execution.takeoverAvailable
      ? `<button class="run-control" data-takeover-task="${escapeHtml(task.task_id)}">${escapeHtml(t("Take over"))}</button>`
      : "",
    execution.resumable
      ? `<button class="run-control primary" data-continue-task="${escapeHtml(task.task_id)}">${escapeHtml(t("Continue"))}</button>`
      : "",
    execution.cancellable
      ? `<button class="run-cancel" data-cancel-task="${escapeHtml(task.task_id)}" title="${escapeHtml(t("Stop running task"))}" aria-label="${escapeHtml(t("Stop running task"))}">${escapeHtml(t("Cancel"))}</button>`
      : ""
  ].join("");
  return `
    <article class="task-turn ${isEvolution ? "self-evolution-turn" : ""}" data-task-id="${escapeHtml(task.task_id)}">
      <div class="user-message-row"><div class="user-message ${isEvolution ? "self-evolution-message" : ""}">${originLabel}${escapeHtml(task.prompt || t("Attached files"))}</div></div>${attachmentRows}
      <div class="execution-run-row">
        <button class="run-summary ${statusClass}" data-toggle-run="${escapeHtml(task.task_id)}">
          <span class="status-pulse"></span>
          <span class="run-summary-copy">
            <strong>${escapeHtml(execution.executor)}</strong>
            <small>${escapeHtml(taskStatusLabel(task))} · ${escapeHtml(execution.location)} · ${escapeHtml(execution.step)}</small>
          </span>
          <span class="run-duration" data-elapsed-task="${escapeHtml(task.task_id)}">${escapeHtml(formatDuration(taskElapsed(task)))}</span>
          <span class="chevron" aria-hidden="true"></span>
        </button>
        ${runControls}
      </div>
      <div class="run-detail" data-run-detail="${escapeHtml(task.task_id)}" ${detailHidden}>${latencySummary}${detail}</div>
      ${answer}
    </article>`;
}

function renderConversation(force = false) {
  syncPromptPlaceholder();
  if (state.activePeerRouteId) {
    renderPeerConversation(force);
    return;
  }
  finishPeerVoiceHold(false);
  const tasks = conversationTasks();
  const signature = JSON.stringify(tasks.map((task) => [
    task.task_id,
    task.status,
    task.updated_at,
    task.result?.length,
    task.result_sha256,
    task.result_chunk_count,
    expandedTaskOutput(task)?.chunks?.length,
    expandedTaskOutput(task)?.done,
    task.output_files?.length,
    task.run_timeline?.events?.length ?? task.events?.length,
    task.delivery_trace?.length,
    task.latency?.first_output_ms,
    task.latency?.total_ms,
    task.delegate_agent_id,
    task.current_step,
    task.execution_view?.executor_id,
    task.execution_view?.location_name,
    task.execution_view?.cancellable,
    task.execution_view?.pausable,
    task.execution_view?.resumable,
    task.execution_view?.takeover_available,
    task.execution_view?.takeover_active,
    task.takeover?.lease_id,
    task.recovery_actions?.map((action) => [action.action, action.enabled, action.recommended]),
    state.recoveryDiagnostics[task.task_id]
  ]));
  if (!force && signature === state.renderingSignature) return;
  state.renderingSignature = signature;
  const wasNearBottom = elements.stream.scrollHeight - elements.stream.scrollTop - elements.stream.clientHeight < 140;
  elements.empty.hidden = tasks.length > 0;
  elements.empty.querySelector("h2").textContent = t("What should we work on?");
  elements.empty.querySelector("p").textContent = t("Ask GalaxySSI to work with files, code, the browser, desktop tools, or a paired phone.");
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
  elements.taskState.textContent = active || latest ? taskStatusLabel(active || latest) : statusLabel(status);
  elements.taskState.className = active ? "running" : (latest && latest.status !== "completed" ? "failed" : "");
  elements.route.textContent = latest ? taskRouteName(latest) : t("Automatic routing");
}

async function refreshTasks(force = false) {
  if (state.polling) return;
  state.polling = true;
  try {
    const payload = await window.galaxyssi.listDesktopTasks(200);
    state.tasks = Array.isArray(payload.tasks) ? payload.tasks : [];
    selectActiveEvolutionTask();
    renderHistory();
    renderConversation(force);
  } catch (error) {
    if (force) showToast(`${t("Task history unavailable")}: ${error.message || error}`);
  } finally {
    state.polling = false;
  }
}

function selectActiveEvolutionTask() {
  if (state.emptyConversationIntent || conversationTasks().length || elements.prompt.value.trim()) return;
  const active = state.tasks
    .filter((task) => task.task_kind === "self_evolution" && !TERMINAL_STATES.has(task.status))
    .sort((a, b) => Number(b.updated_at) - Number(a.updated_at))[0];
  if (active) state.currentConversationId = active.conversation_id;
}

function mergeTaskUpdate(task) {
  if (!task?.task_id) return;
  const currentConversationHadTasks = conversationTasks().length > 0;
  const optimisticIndex = state.tasks.findIndex((item) =>
    String(item.task_id || "").startsWith("pending-")
    && item.conversation_id === task.conversation_id
    && item.prompt === task.prompt);
  if (optimisticIndex >= 0) state.tasks.splice(optimisticIndex, 1);

  const index = state.tasks.findIndex((item) => item.task_id === task.task_id);
  const isNewTask = index < 0;
  if (index >= 0) {
    if (
      state.tasks[index].result_sha256
      && state.tasks[index].result_sha256 !== task.result_sha256
    ) {
      state.expandedTaskOutputs.delete(task.task_id);
    }
    state.tasks[index] = { ...state.tasks[index], ...task };
  } else {
    state.tasks.push(task);
  }
  if (
    isNewTask
    && task.task_kind === "self_evolution"
    && !currentConversationHadTasks
    && !state.emptyConversationIntent
    && !elements.prompt.value.trim()
  ) {
    state.currentConversationId = task.conversation_id;
    state.renderingSignature = "";
  }
  if (isNewTask && task.task_kind === "self_evolution") {
    showToast(t(task.automatic ? "Automatic self-evolution started" : "Self-evolution started"));
  }
  renderHistory();
  if (task.conversation_id === state.currentConversationId) renderConversation();
}

function scheduleTaskStreamReconnect() {
  window.clearTimeout(state.taskStreamReconnectTimer);
  state.taskStreamReconnectTimer = window.setTimeout(connectTaskStream, 1500);
}

async function connectTaskStream() {
  if (state.taskStream && [WebSocket.CONNECTING, WebSocket.OPEN].includes(state.taskStream.readyState)) return;
  try {
    const stream = await window.galaxyssi.desktopTaskStreamConfig();
    const socket = new WebSocket(stream.url, stream.protocols);
    state.taskStream = socket;
    socket.addEventListener("open", () => {
      if (state.taskStream !== socket) return;
      state.taskStreamConnected = true;
    });
    socket.addEventListener("message", (event) => {
      if (state.taskStream !== socket) return;
      let payload;
      try {
        payload = JSON.parse(event.data);
      } catch {
        return;
      }
      if (payload.type === "desktop_tasks_snapshot" && Array.isArray(payload.tasks)) {
        state.tasks = payload.tasks;
        state.peerMessages = Array.isArray(payload.peer_messages) ? payload.peer_messages : [];
        selectActiveEvolutionTask();
        renderHistory();
        renderConversation();
      } else if (payload.type === "desktop_task_update") {
        mergeTaskUpdate(payload.task);
      } else if (payload.type === "desktop_peer_message" && payload.message?.message_id) {
        const index = state.peerMessages.findIndex((item) => item.message_id === payload.message.message_id);
        if (index >= 0) state.peerMessages[index] = { ...state.peerMessages[index], ...payload.message };
        else state.peerMessages.push(payload.message);
        const directoryRefresh = refreshPeerDirectoryForRoute(payload.message.client_route_id);
        renderHistory();
        if (payload.message.client_route_id === state.activePeerRouteId) renderPeerConversation();
        directoryRefresh?.then(() => {
          renderHistory();
          if (payload.message.client_route_id === state.activePeerRouteId) renderPeerConversation();
        });
      }
    });
    socket.addEventListener("close", () => {
      if (state.taskStream !== socket) return;
      state.taskStream = null;
      state.taskStreamConnected = false;
      scheduleTaskStreamReconnect();
    });
    socket.addEventListener("error", () => socket.close());
  } catch {
    state.taskStream = null;
    state.taskStreamConnected = false;
    scheduleTaskStreamReconnect();
  }
}

function updateSendState() {
  const ready = Boolean(elements.prompt.value.trim() || state.attachments.length);
  elements.send.classList.toggle("ready", ready);
  elements.send.disabled = !ready || state.peerSendPending;
  elements.prompt.style.height = "35px";
  elements.prompt.style.height = `${Math.min(104, Math.max(35, elements.prompt.scrollHeight))}px`;
}

function renderAttachmentTray() {
  elements.attachments.hidden = state.attachments.length === 0;
  elements.attachments.innerHTML = state.attachments.map((path, index) => {
    const detail = state.attachmentDetails.get(path) || {};
    const name = detail.name || path.split(/[\\/]/).pop() || path;
    const mimeType = String(detail.mimeType || "").toLowerCase();
    const remove = `<button class="composer-attachment-remove" data-remove-attachment="${index}" aria-label="Remove" title="Remove">×</button>`;
    if (mimeType.startsWith("image/") || detail.previewDataUrl) {
      return `<article class="composer-image-attachment" title="${escapeHtml(name)}">
        ${detail.previewDataUrl
          ? `<img src="${escapeHtml(detail.previewDataUrl)}" alt="${escapeHtml(name)}">`
          : `<span class="composer-attachment-placeholder">IMG</span>`}
        <span class="composer-image-caption"><b>${escapeHtml(name)}</b><small>${escapeHtml(formatBytes(detail.sizeBytes))}</small></span>
        ${remove}
      </article>`;
    }
    const extension = name.includes(".") ? name.split(".").pop().slice(0, 5).toUpperCase() : "FILE";
    return `<article class="composer-file-attachment" title="${escapeHtml(path)}">
      <span class="composer-file-kind">${escapeHtml(extension)}</span>
      <span class="composer-file-copy"><b>${escapeHtml(name)}</b><small>${escapeHtml(formatBytes(detail.sizeBytes))}</small></span>
      ${remove}
    </article>`;
  }).join("");
  updateSendState();
}

function rememberAttachmentDetails(details = []) {
  for (const detail of details) {
    if (detail?.path) state.attachmentDetails.set(detail.path, detail);
  }
}

function releaseStagedAttachments(paths) {
  window.galaxyssi.releaseStagedAttachments(paths).catch((error) => {
    console.warn("Could not release staged composer attachment", error);
  });
}

async function addAttachmentPaths(paths, knownDetails = []) {
  const incoming = Array.isArray(paths) ? paths : [];
  const combined = [...state.attachments, ...incoming];
  state.attachments = Array.from(new Set(combined)).slice(0, 12);
  rememberAttachmentDetails(knownDetails);
  const rejected = incoming.filter((path) => !state.attachments.includes(path));
  rejected.forEach((path) => state.attachmentDetails.delete(path));
  if (rejected.length) releaseStagedAttachments(rejected);
  renderAttachmentTray();
  const missing = state.attachments.filter((path) => !state.attachmentDetails.has(path));
  if (!missing.length) return;
  const details = await window.galaxyssi.describeAttachments(missing);
  rememberAttachmentDetails(details);
  renderAttachmentTray();
}

async function addAttachments() {
  try {
    const files = await window.galaxyssi.chooseAttachments();
    await addAttachmentPaths(files);
  } catch (error) {
    showToast(error.message || String(error));
  }
}

async function pasteAttachments(event) {
  const clipboardFiles = Array.from(event.clipboardData?.files || []).slice(0, 12);
  if (!clipboardFiles.length) return;
  event.preventDefault();
  try {
    const directPaths = [];
    const buffered = [];
    for (const file of clipboardFiles) {
      const filePath = window.galaxyssi.clipboardFilePath(file);
      if (filePath) {
        directPaths.push(filePath);
      } else {
        buffered.push({
          name: file.name,
          mimeType: file.type,
          bytes: new Uint8Array(await file.arrayBuffer())
        });
      }
    }
    const staged = buffered.length
      ? await window.galaxyssi.stageClipboardAttachments(buffered)
      : [];
    await addAttachmentPaths(
      [...directPaths, ...staged.map((item) => item.path)],
      staged
    );
  } catch (error) {
    showToast(error.message || String(error));
  }
}

async function sendTask() {
  const prompt = elements.prompt.value.trim();
  if (!prompt && !state.attachments.length) return;
  if (state.activePeerRouteId) {
    if (state.peerSendPending) return;
    state.peerSendPending = true;
    const attachments = [...state.attachments];
    const attachmentMetadata = attachments.map((path) => state.attachmentDetails.get(path) || {});
    elements.prompt.value = "";
    state.attachments = [];
    renderAttachmentTray();
    updateSendState();
    try {
      const result = await window.galaxyssi.sendPeerMessage({
        clientRouteId: state.activePeerRouteId,
        content: prompt,
        attachments,
        attachmentMetadata
      });
      if (result.message) {
        const index = state.peerMessages.findIndex((item) => item.message_id === result.message.message_id);
        if (index >= 0) state.peerMessages[index] = result.message;
        else state.peerMessages.push(result.message);
      }
      renderHistory();
      renderPeerConversation(true);
      releaseStagedAttachments(attachments);
      attachments.forEach((path) => state.attachmentDetails.delete(path));
    } catch (error) {
      elements.prompt.value = prompt;
      state.attachments = attachments;
      renderAttachmentTray();
      showToast(`${t("Could not send message")}: ${error.message || error}`);
    } finally {
      state.peerSendPending = false;
      updateSendState();
    }
    return;
  }
  state.emptyConversationIntent = false;
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
    const task = await window.galaxyssi.startDesktopTask({
      prompt,
      agentId: state.selectedAgentId,
      conversationId: state.currentConversationId,
      attachments,
      executionMode: state.executionMode,
      taskBudget: state.taskBudget,
      responseLanguage: resolveLanguagePolicy(
        state.agentConfig?.language_policy?.response_language || "auto"
      )
    });
    state.tasks = state.tasks.filter((item) => item.task_id !== optimistic.task_id);
    mergeTaskUpdate(task);
    releaseStagedAttachments(attachments);
    attachments.forEach((path) => state.attachmentDetails.delete(path));
    updateSelectedAgent();
    state.renderingSignature = "";
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
  closePeerImageViewer();
  resetPeerImagePreviews();
  state.activePeerRouteId = "";
  document.querySelector("#agentApp").classList.remove("peer-mode");
  state.currentConversationId = crypto.randomUUID();
  state.emptyConversationIntent = true;
  state.selectedAgentId = agentId;
  state.selectedAgentName = name;
  releaseStagedAttachments(state.attachments);
  state.attachments.forEach((path) => state.attachmentDetails.delete(path));
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

function updateExecutionMode() {
  const planOnly = state.executionMode === "plan_only";
  elements.executionMode.textContent = t(planOnly ? "Plan only" : "Auto complete");
  elements.executionMode.classList.toggle("plan-only", planOnly);
  elements.executionMode.setAttribute("aria-pressed", planOnly ? "true" : "false");
}

function taskBudgetProfileLabel(profile) {
  const labels = {
    adaptive: "Adaptive",
    fast: "Fast",
    economy: "Economy",
    private: "Private",
    custom: "Custom"
  };
  return t(labels[profile] || labels.adaptive);
}

function fillTaskBudgetSettings() {
  const budget = normalizeTaskBudget(state.taskBudget);
  state.taskBudget = budget;
  elements.taskBudgetProfile.value = budget.profile;
  $("#taskBudgetSettingsProfile").value = budget.profile;
  $("#taskBudgetSummary").textContent = taskBudgetProfileLabel(budget.profile);
  $("#taskBudgetTime").value = String(Math.round((budget.max_elapsed_seconds / 60) * 100) / 100);
  $("#taskBudgetCost").value = String(Math.round((budget.max_cost_micros / 1_000_000) * 100) / 100);
  $("#taskBudgetInputTokens").value = String(budget.max_input_tokens);
  $("#taskBudgetOutputTokens").value = String(budget.max_output_tokens);
  $("#taskBudgetNetwork").value = String(Math.round(budget.max_network_bytes / 1_048_576));
  $("#taskBudgetMemory").value = String(Math.round(budget.max_memory_bytes / 1_048_576));
  $("#taskBudgetBattery").value = String(budget.minimum_battery_percent);
  $("#taskBudgetNetworkPolicy").value = budget.network_policy;
  $("#taskBudgetAllowCloud").checked = budget.allow_cloud;
  $("#taskBudgetAllowPaid").checked = budget.allow_paid_providers;
}

function persistTaskBudget(value, notify = true) {
  state.taskBudget = normalizeTaskBudget(value);
  localStorage.setItem("galaxyssi-desktop-task-budget", JSON.stringify(state.taskBudget));
  fillTaskBudgetSettings();
  if (notify) showToast(t("Task budget saved."));
}

function selectTaskBudgetProfile(profile) {
  const selected = String(profile || "adaptive");
  if (selected === "custom") {
    persistTaskBudget({ ...state.taskBudget, profile: "custom" });
    return;
  }
  persistTaskBudget(taskBudgetPreset(selected));
}

function readTaskBudgetSettings() {
  return normalizeTaskBudget({
    profile: $("#taskBudgetSettingsProfile").value,
    max_elapsed_seconds: Number($("#taskBudgetTime").value || 0) * 60,
    max_cost_micros: Number($("#taskBudgetCost").value || 0) * 1_000_000,
    max_input_tokens: $("#taskBudgetInputTokens").value,
    max_output_tokens: $("#taskBudgetOutputTokens").value,
    max_network_bytes: Number($("#taskBudgetNetwork").value || 0) * 1_048_576,
    minimum_battery_percent: $("#taskBudgetBattery").value,
    max_memory_bytes: Number($("#taskBudgetMemory").value || 0) * 1_048_576,
    network_policy: $("#taskBudgetNetworkPolicy").value,
    allow_cloud: $("#taskBudgetAllowCloud").checked,
    allow_paid_providers: $("#taskBudgetAllowPaid").checked
  });
}

function markTaskBudgetCustom() {
  $("#taskBudgetSettingsProfile").value = "custom";
}

async function refreshBackend() {
  try {
    state.backend = await window.galaxyssi.startBackend();
  } catch (error) {
    state.backend = { running: false, error: error.message || String(error) };
  }
  const backendRunning = Boolean(state.backend?.running);
  const online = backendRunning && Boolean(state.backend?.messageBridgeConnected);
  elements.backendBadge.className = `state-badge ${online ? "ok" : "bad"}`;
  elements.backendBadge.textContent = t(online ? "Online" : "Offline");
  elements.backendDetail.textContent = online
    ? state.backend.origin
    : backendRunning
      ? (state.backend?.messageBridgeError || t("Message bridge reconnecting"))
      : (state.backend?.error || t("Backend unavailable"));
}

function renderAgentMemoryGroup(selector, values) {
  const target = $(selector);
  const rows = Array.isArray(values) ? values.slice(0, 6) : [];
  target.innerHTML = rows.length
    ? rows.map((item) => `<div class="agent-memory-item" title="${escapeHtml(item.id)}">
        <span>${escapeHtml(item.id)}</span>
        <b>${escapeHtml(formatBytes(item.current_bytes || 0))}${item.estimated ? "*" : ""}</b>
      </div>`).join("")
    : `<div class="agent-memory-item"><span>${escapeHtml(t("No active samples"))}</span></div>`;
}

async function refreshAgentMemoryTelemetry() {
  const summary = $("#agentMemorySummary");
  const sessionSummary = $("#agentSessionMemoryBudget");
  try {
    const snapshot = await window.galaxyssi.getAgentMemoryTelemetry();
    const kind = {
      android_pss: "Android PSS",
      windows_working_set: "Windows Working Set",
      linux_rss: "Linux RSS",
      macos_resident_set: "macOS Resident Set"
    }[snapshot.measurement_kind] || snapshot.measurement_kind || t("Memory");
    summary.textContent = snapshot.sampled_at
      ? t("Current {current} · Peak {peak} · {kind}", {
          current: formatBytes(snapshot.process_current_bytes || 0),
          peak: formatBytes(snapshot.process_peak_bytes || 0),
          kind
        })
      : t("Memory telemetry has not been sampled.");
    const sessionBudget = snapshot.session_budget || {};
    const sessionSamples = Number(sessionBudget.sample_count || 0);
    sessionSummary.textContent = sessionSamples
      ? t("New session {latest} · target under {target} · peak {peak} · {samples} samples", {
          latest: formatBytes(sessionBudget.latest_incremental_bytes || 0),
          target: formatBytes(sessionBudget.target_bytes || 20 * 1_048_576),
          peak: formatBytes(sessionBudget.peak_incremental_bytes || 0),
          samples: sessionSamples
        })
      : t("New session overhead has not been measured.");
    sessionSummary.classList.toggle("over-budget", sessionBudget.within_budget === false);
    renderAgentMemoryGroup("#agentMemoryByAgent", snapshot.by_agent);
    renderAgentMemoryGroup("#agentMemoryBySession", snapshot.by_session);
    renderAgentMemoryGroup("#agentMemoryByProvider", snapshot.by_provider);
  } catch (error) {
    summary.textContent = error.message || String(error);
    sessionSummary.textContent = t("New session overhead has not been measured.");
    sessionSummary.classList.remove("over-budget");
    renderAgentMemoryGroup("#agentMemoryByAgent", []);
    renderAgentMemoryGroup("#agentMemoryBySession", []);
    renderAgentMemoryGroup("#agentMemoryByProvider", []);
  }
}

function updateAgentCounters() {
  elements.agentCount.textContent = String(state.agents.length);
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

function performancePercent(value) {
  if (value === null || value === undefined || value === "") return "\u2014";
  const numeric = Number(value);
  return Number.isFinite(numeric) ? `${Math.round(numeric * 100)}%` : "\u2014";
}

function performanceStateLabel(agent) {
  if (agent.measurement_state === "unavailable") return t("Unavailable");
  if (agent.measurement_state === "no_data") return t("No evidence");
  if (agent.active_tasks > 0 || agent.availability_status === "busy") return t("Busy");
  return {
    insufficient: t("Early result"),
    indicative: t("Indicative"),
    established: t("Established")
  }[agent.confidence] || t("Measured");
}

function renderAgentPerformance() {
  const summaryTarget = $("#agentPerformanceSummary");
  const listTarget = $("#agentPerformanceList");
  const report = state.agentPerformance.report;
  const latencyTarget = $("#agentStageLatency");
  if (latencyTarget && globalThis.GalaxySSIAgentLatency) {
    latencyTarget.innerHTML = globalThis.GalaxySSIAgentLatency.render(report?.stage_latency, {
      escapeHtml, t
    });
  }
  $$("[data-performance-window]").forEach((button) => {
    button.classList.toggle("active", button.dataset.performanceWindow === state.agentPerformance.window);
  });
  if (state.agentPerformance.loading && !report) {
    summaryTarget.innerHTML = `<div class="performance-empty">${escapeHtml(t("Loading performance evidence"))}</div>`;
    listTarget.innerHTML = "";
    return;
  }
  if (!report) {
    summaryTarget.innerHTML = `<div class="performance-empty">${escapeHtml(t("No performance evidence yet."))}</div>`;
    listTarget.innerHTML = "";
    return;
  }
  const summary = report.summary || {};
  const agents = Array.isArray(report.agents) ? report.agents : [];
  const recommended = agents.find((agent) => agent.agent_id === summary.recommended_agent_id);
  const fastest = agents.find((agent) => agent.agent_id === summary.fastest_agent_id);
  summaryTarget.innerHTML = `
    <div class="performance-overview">
      <span><strong>${escapeHtml(performancePercent(summary.success_rate))}</strong><small>${escapeHtml(t("Success rate"))}</small></span>
      <span><strong>${escapeHtml(String(summary.attempts || 0))}</strong><small>${escapeHtml(t("Runs"))}</small></span>
      <span><strong>${escapeHtml(`${summary.available_agents || 0}/${summary.agents || 0}`)}</strong><small>${escapeHtml(t("Available now"))}</small></span>
    </div>
    <p class="performance-leader">${escapeHtml(
      recommended
        ? t("Recommended: {agent}. Fastest: {fastest}.", {
            agent: recommended.display_name,
            fastest: fastest?.display_name || t("Not enough evidence")
          })
        : t("Run an Agent task to begin a measured comparison.")
    )}</p>`;
  listTarget.innerHTML = agents.map((agent) => {
    const rank = Number(agent.rank);
    const measured = agent.measurement_state === "measured";
    const stateClass = agent.measurement_state === "unavailable"
      ? "unavailable"
      : measured ? "measured" : "no-data";
    const successWidth = measured && Number.isFinite(Number(agent.success_rate))
      ? Math.max(0, Math.min(100, Number(agent.success_rate) * 100))
      : 0;
    return `<article class="performance-agent ${stateClass} ${rank === 1 ? "rank-1" : ""}">
      <div class="performance-rank">${escapeHtml(Number.isFinite(rank) && rank > 0 ? String(rank) : "\u2013")}</div>
      <div class="performance-agent-main">
        <div class="performance-agent-header">
          <strong>${escapeHtml(agent.display_name || agent.agent_id)}</strong>
          <span class="performance-state">${escapeHtml(performanceStateLabel(agent))}</span>
        </div>
        <progress class="performance-success-track" value="${successWidth.toFixed(1)}" max="100" aria-label="${escapeHtml(t("Success rate"))}"></progress>
        <div class="performance-metrics">
          <span><b>${escapeHtml(performancePercent(agent.success_rate))}</b><small>${escapeHtml(t("Success"))}</small></span>
          <span><b>${escapeHtml(agent.p50_latency_ms == null ? "\u2014" : formatLatency(agent.p50_latency_ms))}</b><small>P50</small></span>
          <span><b>${escapeHtml(agent.p95_latency_ms == null ? "\u2014" : formatLatency(agent.p95_latency_ms))}</b><small>P95</small></span>
          <span><b>${escapeHtml(String(agent.attempts || 0))}</b><small>${escapeHtml(t("Samples"))}</small></span>
        </div>
      </div>
    </article>`;
  }).join("");
}

async function refreshAgentPerformance(performanceWindow = state.agentPerformance.window) {
  const requestedWindow = ["24h", "7d", "30d", "all"].includes(performanceWindow)
    ? performanceWindow
    : "7d";
  if (state.agentPerformance.loading && requestedWindow === state.agentPerformance.window) return;
  state.agentPerformance.window = requestedWindow;
  state.agentPerformance.loading = true;
  const requestId = ++state.agentPerformance.requestId;
  const refreshButton = $("#refreshAgentPerformanceButton");
  refreshButton.classList.add("loading");
  renderAgentPerformance();
  try {
    const report = await window.galaxyssi.getAgentPerformanceLab(requestedWindow);
    if (requestId !== state.agentPerformance.requestId) return;
    state.agentPerformance.report = report;
  } catch (error) {
    if (requestId !== state.agentPerformance.requestId) return;
    state.agentPerformance.report = null;
    $("#agentPerformanceSummary").innerHTML = `<div class="performance-empty">${escapeHtml(error.message || String(error))}</div>`;
    $("#agentPerformanceList").innerHTML = "";
  } finally {
    if (requestId !== state.agentPerformance.requestId) return;
    state.agentPerformance.loading = false;
    refreshButton.classList.remove("loading");
    if (state.agentPerformance.report) renderAgentPerformance();
  }
}

async function refreshAgents() {
  if (state.agentRefreshPromise) return state.agentRefreshPromise;
  state.agentRefreshPromise = (async () => {
    try {
      state.agents = await window.galaxyssi.detectAgents();
      state.agentConfig = await window.galaxyssi.getAgentConfig();
      renderAgentContacts();
      updateAgentCounters();
      fillAgentSettings();
      await refreshAcpRuntime();
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
  $("#cmdGemini").value = commands.gemini || "";
  $("#cmdOpenClaw").value = commands.openclaw || "";
  fillAcpRuntimeSettings(config.acp_runtime || {});
  fillLanguagePolicySettings(config);
  fillCloudModelSettings(config.cloud_model || {});
  fillWebSearchSettings(config.web_search || {});
  fillTaskBudgetSettings();
}

function acpRuntimeConfig(config = state.agentConfig || {}) {
  const runtime = config.acp_runtime || {};
  return {
    enabled: runtime.enabled !== false,
    max_processes: Number(runtime.max_processes) || 5,
    idle_timeout_seconds: Number(runtime.idle_timeout_seconds) || 600,
    agents: runtime.agents || {}
  };
}

function fillAcpRuntimeSettings(runtime = {}) {
  const config = acpRuntimeConfig({ acp_runtime: runtime });
  $("#acpRuntimeEnabled").checked = config.enabled;
  $("#acpMaxProcesses").value = String(config.max_processes);
  $("#acpIdleTimeout").value = String(config.idle_timeout_seconds);
  renderAcpRuntime();
}

function acpRuntimeStatusLabel(status) {
  const labels = {
    running: "Running",
    ready: "Ready",
    backoff: "Needs attention",
    disabled: "Disabled",
    needs_setup: "Setup",
    unchecked: "Not checked"
  };
  return t(labels[status] || status || "Not checked");
}

function renderAcpRuntime() {
  const config = acpRuntimeConfig();
  const healthRows = new Map(
    (state.acpRuntime?.processes || []).map((item) => [item.agent_id, item])
  );
  const target = $("#acpRuntimeList");
  target.innerHTML = Object.entries(ACP_AGENT_NAMES).map(([agentId, name]) => {
    const saved = config.agents[agentId] || {};
    const health = healthRows.get(agentId) || {};
    const status = String(health.status || "unchecked");
    const enabled = saved.enabled !== false;
    const command = String(saved.command || "");
    const action = status === "running" ? "restart" : "prewarm";
    const actionLabel = action === "restart" ? "Restart" : "Start";
    const readiness = status === "running"
      ? t("Warm in {latency} ms; reused {count} times")
        .replace("{latency}", String(Number(health.startup_latency_ms || 0)))
        .replace("{count}", String(Number(health.warm_reuses || 0)))
      : t("Cold start pending");
    return `<article class="acp-runtime-item" data-acp-agent="${escapeHtml(agentId)}">
      <div class="acp-runtime-heading">
        <label class="check-row"><input data-acp-enabled type="checkbox" ${enabled ? "checked" : ""}><span><strong>${escapeHtml(name)}</strong><small>${escapeHtml(`ACP - ${readiness}`)}</small></span></label>
        <span class="state-badge ${status === "running" ? "ok" : (status === "backoff" ? "bad" : "pending")}">${escapeHtml(acpRuntimeStatusLabel(status))}</span>
      </div>
      <label><span>${escapeHtml(t("ACP command"))}</span><input data-acp-command spellcheck="false" value="${escapeHtml(command)}"></label>
      <div class="acp-runtime-actions">
        <label class="check-row"><input data-acp-prewarm type="checkbox" ${saved.prewarm ? "checked" : ""}><span><strong>${escapeHtml(t("Keep Agent ready"))}</strong><small>${escapeHtml(t("Prewarm on startup and recover the process if it exits."))}</small></span></label>
        <button class="secondary-button compact-button" data-acp-action="${action}">${escapeHtml(t(actionLabel))}</button>
      </div>
    </article>`;
  }).join("");
  const running = [...healthRows.values()].filter((item) => item.status === "running").length;
  const ready = [...healthRows.values()].filter((item) => ["ready", "running"].includes(item.status)).length;
  $("#acpRuntimeSummary").textContent = state.acpRuntime
    ? t("ACP runtime summary").replace("{running}", String(running)).replace("{ready}", String(ready))
    : t("ACP runtime has not been checked.");
}

async function refreshAcpRuntime() {
  try {
    state.acpRuntime = await window.galaxyssi.getAcpRuntime();
  } catch (error) {
    state.acpRuntime = null;
    $("#acpRuntimeSummary").textContent = error.message || String(error);
  }
  renderAcpRuntime();
}

async function saveAcpRuntimeSettings() {
  const config = state.agentConfig || await window.galaxyssi.getAgentConfig();
  const previous = acpRuntimeConfig(config);
  const agents = {};
  $$("#acpRuntimeList [data-acp-agent]").forEach((row) => {
    const agentId = row.dataset.acpAgent;
    agents[agentId] = {
      enabled: Boolean(row.querySelector("[data-acp-enabled]")?.checked),
      command: row.querySelector("[data-acp-command]")?.value.trim() || "",
      prewarm: Boolean(row.querySelector("[data-acp-prewarm]")?.checked)
    };
  });
  config.acp_runtime = {
    ...previous,
    enabled: $("#acpRuntimeEnabled").checked,
    max_processes: boundedInteger("#acpMaxProcesses", 5, 1, 16),
    idle_timeout_seconds: boundedInteger("#acpIdleTimeout", 600, 30, 86400),
    agents
  };
  state.agentConfig = await window.galaxyssi.saveAgentConfig(config);
  fillAcpRuntimeSettings(state.agentConfig.acp_runtime || config.acp_runtime);
  await refreshAcpRuntime();
  showToast(t("ACP runtime settings saved."));
}

async function runAcpRuntimeAction(agentId, action) {
  const result = action === "restart"
    ? await window.galaxyssi.restartAcpAgent(agentId)
    : await window.galaxyssi.prewarmAcpAgent(agentId);
  await refreshAcpRuntime();
  if (result.status === "needs_setup") {
    showToast(result.last_error || t("ACP command is not installed."));
  } else {
    showToast(t(action === "restart" ? "ACP Agent restarted." : "ACP Agent started."));
  }
}

function fillLanguagePolicySettings(config = state.agentConfig || {}) {
  const languagePolicy = config.language_policy || {};
  $("#responseLanguageSelect").value = normalizeLanguagePolicy(languagePolicy.response_language);
  $("#asrLanguageSelect").value = normalizeLanguagePolicy(languagePolicy.asr_language);
  $("#ttsLanguageSelect").value = normalizeLanguagePolicy(languagePolicy.tts_language);
}

async function saveLanguagePolicySettings() {
  const config = state.agentConfig || await window.galaxyssi.getAgentConfig();
  config.language_policy = {
    ...(config.language_policy || {}),
    response_language: normalizeLanguagePolicy($("#responseLanguageSelect").value),
    asr_language: normalizeLanguagePolicy($("#asrLanguageSelect").value),
    tts_language: normalizeLanguagePolicy($("#ttsLanguageSelect").value)
  };
  state.agentConfig = await window.galaxyssi.saveAgentConfig(config);
  fillLanguagePolicySettings(state.agentConfig);
  showToast(t("Voice and language settings saved."));
}

function cloudProviderFor(config) {
  const saved = String(config.provider || "").trim().toLowerCase();
  if (Object.hasOwn(CLOUD_PROVIDER_PRESETS, saved)) return saved;
  const endpoint = String(config.url || "").trim().toLowerCase();
  return Object.entries(CLOUD_PROVIDER_PRESETS)
    .find(([, preset]) => preset.endpoint && endpoint === preset.endpoint.toLowerCase())?.[0] || "custom";
}

function setCloudModelStatus(status, detail = "") {
  const badge = $("#cloudModelBadge");
  const result = $("#cloudModelTestResult");
  const labels = {
    ready: "Ready",
    testing: "Testing...",
    missing: "Not configured",
    error: "Needs attention"
  };
  badge.className = `state-badge ${status === "ready" ? "ok" : (status === "error" ? "bad" : "pending")}`;
  badge.textContent = t(labels[status] || labels.missing);
  if (!detail) {
    result.hidden = true;
    result.textContent = "";
    result.className = "cloud-test-result";
    return;
  }
  result.hidden = false;
  result.textContent = detail;
  result.className = `cloud-test-result ${status === "ready" ? "ok" : (status === "error" ? "bad" : "")}`;
}

function fillCloudModelSettings(config = {}) {
  const provider = cloudProviderFor(config);
  $("#cloudProvider").value = provider;
  $("#cloudDisplayName").value = config.name || CLOUD_PROVIDER_PRESETS[provider].name;
  $("#cloudEndpoint").value = config.url || CLOUD_PROVIDER_PRESETS[provider].endpoint;
  $("#cloudModelId").value = config.model || "";
  $("#cloudApiKey").value = config.api_key || "";
  $("#cloudContextWindow").value = String(config.context_window_tokens || 64000);
  $("#cloudOutputReserve").value = String(config.max_output_tokens || 4096);
  $("#cloudInputPrice").value = priceMicrosAsUsd(config.input_micros_per_million_tokens);
  $("#cloudOutputPrice").value = priceMicrosAsUsd(config.output_micros_per_million_tokens);
  $("#cloudModelSummary").checked = ![false, "", "false", "0"].includes(config.context_model_summary);
  const ready = Boolean($("#cloudEndpoint").value.trim() && $("#cloudModelId").value.trim() && $("#cloudApiKey").value.trim());
  setCloudModelStatus(ready ? "ready" : "missing");
}

function applyCloudProviderPreset() {
  const provider = $("#cloudProvider").value;
  const preset = CLOUD_PROVIDER_PRESETS[provider] || CLOUD_PROVIDER_PRESETS.custom;
  const endpoint = $("#cloudEndpoint");
  const displayName = $("#cloudDisplayName");
  const presetEndpoints = Object.values(CLOUD_PROVIDER_PRESETS).map((item) => item.endpoint).filter(Boolean);
  if (provider === "custom") {
    if (presetEndpoints.includes(endpoint.value.trim())) endpoint.value = "";
  } else {
    endpoint.value = preset.endpoint;
  }
  if (!displayName.value.trim() || Object.values(CLOUD_PROVIDER_PRESETS).some((item) => item.name === displayName.value.trim())) {
    displayName.value = preset.name;
  }
}

function boundedInteger(selector, fallback, minimum, maximum) {
  const parsed = Number.parseInt($(selector).value, 10);
  if (!Number.isFinite(parsed)) return fallback;
  return Math.min(maximum, Math.max(minimum, parsed));
}

function priceMicrosAsUsd(value) {
  if (value === null || value === undefined || value === "") return "";
  const micros = Number(value);
  return Number.isFinite(micros) && micros >= 0
    ? String(micros / 1_000_000)
    : "";
}

function readPriceMicros(selector) {
  const raw = $(selector).value.trim();
  if (!raw) return "";
  const dollars = Number(raw);
  if (!Number.isFinite(dollars) || dollars < 0 || dollars > 1_000_000) return "";
  return Math.round(dollars * 1_000_000);
}

function readCloudModelSettings() {
  return {
    provider: $("#cloudProvider").value,
    name: $("#cloudDisplayName").value.trim() || CLOUD_PROVIDER_PRESETS[$("#cloudProvider").value]?.name || "Cloud Model",
    url: $("#cloudEndpoint").value.trim(),
    model: $("#cloudModelId").value.trim(),
    api_key: $("#cloudApiKey").value.trim(),
    context_window_tokens: boundedInteger("#cloudContextWindow", 64000, 4096, 1000000),
    max_output_tokens: boundedInteger("#cloudOutputReserve", 4096, 512, 128000),
    input_micros_per_million_tokens: readPriceMicros("#cloudInputPrice"),
    output_micros_per_million_tokens: readPriceMicros("#cloudOutputPrice"),
    pricing_currency: "USD",
    context_model_summary: $("#cloudModelSummary").checked ? "true" : ""
  };
}

function validateCloudModelSettings(config) {
  if (!config.url || !config.model || !config.api_key) return t("Enter the endpoint, model ID, and API key.");
  let parsed;
  try {
    parsed = new URL(config.url);
  } catch (_error) {
    return t("Enter a valid endpoint URL.");
  }
  const local = ["localhost", "127.0.0.1", "::1"].includes(parsed.hostname);
  if (parsed.protocol !== "https:" && !(local && parsed.protocol === "http:")) {
    return t("Use HTTPS, except for a local endpoint.");
  }
  if (config.max_output_tokens >= config.context_window_tokens) {
    return t("Reserved output must be smaller than the context window.");
  }
  return "";
}

async function saveCloudModelSettings(testAfterSave = false) {
  const cloudModel = readCloudModelSettings();
  const validation = validateCloudModelSettings(cloudModel);
  if (validation) {
    setCloudModelStatus("error", validation);
    return;
  }
  const config = state.agentConfig || await window.galaxyssi.getAgentConfig();
  config.cloud_model = { ...(config.cloud_model || {}), ...cloudModel };
  setCloudModelStatus(testAfterSave ? "testing" : "ready", testAfterSave ? t("Testing the configured model...") : "");
  try {
    state.agentConfig = await window.galaxyssi.saveAgentConfig(config);
    fillCloudModelSettings(state.agentConfig.cloud_model || cloudModel);
    if (!testAfterSave) {
      showToast(t("Cloud API settings saved."));
      return;
    }
    setCloudModelStatus("testing", t("Testing the configured model..."));
    const response = await window.galaxyssi.testAgent("cloud-model", "Reply with only: GalaxySSI cloud API ready.");
    const reply = String(response?.reply || "").trim();
    if (!reply) throw new Error(t("The model returned an empty response."));
    setCloudModelStatus("ready", `${t("Connected")}: ${reply}`);
    showToast(t("Cloud API is ready."));
  } catch (error) {
    setCloudModelStatus("error", error.message || String(error));
  }
}

function fillWebSearchSettings(config = {}) {
  $("#webBraveApiKey").value = config.brave_api_key || "";
  $("#webGithubToken").value = config.github_token || "";
}

async function saveWebSearchSettings() {
  const config = state.agentConfig || await window.galaxyssi.getAgentConfig();
  config.web_search = {
    ...(config.web_search || {}),
    brave_api_key: $("#webBraveApiKey").value.trim(),
    github_token: $("#webGithubToken").value.trim()
  };
  state.agentConfig = await window.galaxyssi.saveAgentConfig(config);
  fillWebSearchSettings(state.agentConfig.web_search || {});
  showToast(t("Web intelligence source credentials saved."));
}

async function saveAgentCommands() {
  const config = state.agentConfig || await window.galaxyssi.getAgentConfig();
  config.commands = {
    ...(config.commands || {}),
    hermes: $("#cmdHermes").value.trim(),
    codex: $("#cmdCodex").value.trim(),
    claude: $("#cmdClaude").value.trim(),
    gemini: $("#cmdGemini").value.trim(),
    openclaw: $("#cmdOpenClaw").value.trim()
  };
  state.agentConfig = await window.galaxyssi.saveAgentConfig(config);
  showToast(t("Agent commands saved."));
  await refreshAgents();
}

async function saveCustomAgent() {
  const id = $("#customAgentId").value.trim().toLowerCase().replace(/[^a-z0-9._-]+/g, "-").replace(/^-|-$/g, "");
  const name = $("#customAgentName").value.trim();
  const command = $("#customAgentCommand").value.trim();
  const transport = $("#customAgentTransport").value === "galaxyssi-jsonl-v1"
    ? "galaxyssi-jsonl-v1"
    : "oneshot";
  const poolSize = Math.max(1, Math.min(8, Number.parseInt($("#customAgentPoolSize").value, 10) || 1));
  const prewarm = transport === "galaxyssi-jsonl-v1" && $("#customAgentPrewarm").checked;
  if (!id || !name || !command) {
    showToast(t("Complete the agent ID, name, and command."));
    return;
  }
  const config = state.agentConfig || await window.galaxyssi.getAgentConfig();
  const configured = Array.isArray(config.custom_agents) ? config.custom_agents : [];
  const existing = configured.find((item) => item.id === id) || {};
  const rows = configured.filter((item) => item.id !== id);
  rows.push({
    ...existing,
    id,
    name,
    command,
    transport,
    pool_size: poolSize,
    prewarm
  });
  config.custom_agents = rows;
  state.agentConfig = await window.galaxyssi.saveAgentConfig(config);
  $("#customAgentId").value = "";
  $("#customAgentName").value = "";
  $("#customAgentCommand").value = "";
  $("#customAgentTransport").value = "oneshot";
  $("#customAgentPoolSize").value = "1";
  $("#customAgentPrewarm").checked = false;
  showToast(t("Custom agent added."));
  await refreshAgents();
}

function renderGateway() {
  const status = state.pairing || {};
  const clients = Array.isArray(status.clients) ? status.clients : [];
  window.GalaxySSIBlobSettings?.setClients(clients);
  const count = Number(status.client_count || clients.length || 0);
  elements.gatewayCount.textContent = count ? t("{count} online", { count }) : t("Offline");
  $("#gatewaySummary .status-orb").classList.toggle("online", count > 0);
  $("#gatewaySummary p").textContent = count ? t("{count} verified device(s) connected", { count }) : t("No phone paired");
  $("#pairedClientList").innerHTML = clients.length ? clients.map((client) => {
    const id = client.client_route_id || "";
    const access = client.access?.profile === "desktop_executor" ? t("Desktop Executor") : t("Restricted");
    const fingerprint = client.identity_fingerprint_short || id.slice(0, 12) || t("Verified");
    const name = client.display_name || client.device_name || client.profile_name || t("GalaxySSI phone");
    const details = [client.device_model, client.platform, fingerprint, access].filter(Boolean).join(" · ");
    return `<article class="paired-client"><span class="phone-outline"></span><div><strong>${escapeHtml(name)}</strong><small>${escapeHtml(details)}</small></div><div class="paired-client-actions"><button data-chat-client="${escapeHtml(id)}">${escapeHtml(t("Message"))}</button><button data-rename-client="${escapeHtml(id)}" data-client-name="${escapeHtml(name)}">${escapeHtml(t("Rename"))}</button><button data-revoke-client="${escapeHtml(id)}">${escapeHtml(t("Revoke"))}</button></div></article>`;
  }).join("") : `<div class="history-empty">${escapeHtml(t("Scan the QR code below to pair a phone."))}</div>`;
}

async function refreshGateway() {
  if (state.pairingStatusLoading) return;
  state.pairingStatusLoading = true;
  try {
    state.pairing = await window.galaxyssi.getPairingStatus();
    renderGateway();
    renderHistory();
    if (state.pairingQrCreatedAt && (state.pairing.clients || []).some(
      client => Number(client.paired_at || 0) >= state.pairingQrCreatedAt
    )) {
      await loadPairingFrame(true).catch(() => {});
    }
  } catch (error) {
    state.pairing = { clients: [] };
    renderGateway();
    renderHistory();
    $("#gatewaySummary p").textContent = error.message || String(error);
  } finally {
    state.pairingStatusLoading = false;
  }
}

async function loadPairingFrame(force = false) {
  if (state.pairingQrLoading) return;
  const image = $("#pairingFrame");
  const stillValid = Number(state.pairingQrExpiresAt || 0) > (Date.now() / 1000) + 15;
  if (!force && image.getAttribute("src") && stillValid) return;
  state.pairingQrLoading = true;
  if (force) image.removeAttribute("src");
  const fingerprint = $("#pairingFingerprint");
  const accessSummary = $("#pairingAccessSummary");
  const deviceName = $("#pairingDeviceName");
  fingerprint.textContent = t("Preparing secure pairing QR...");
  accessSummary.textContent = "";
  deviceName.textContent = "";
  try {
    const pairing = await window.galaxyssi.getPairingQr(state.pairingGrantDesktopExecutor);
    image.src = pairing.imageDataUrl;
    state.pairingQrExpiresAt = pairing.expiresAt || 0;
    state.pairingQrCreatedAt = pairing.createdAt || 0;
    deviceName.textContent = pairing.desktopDevice?.display_name || t("This Desktop");
    fingerprint.textContent = pairing.fingerprint
      ? t("Computer fingerprint: {fingerprint}", { fingerprint: pairing.fingerprint })
      : "";
    accessSummary.textContent = state.pairingGrantDesktopExecutor
      ? t("Desktop Executor access will be granted; sensitive actions still require approval.")
      : t("Restricted pairing: Agent chat and explicit task attachments only.");
  } catch (error) {
    image.removeAttribute("src");
    state.pairingQrExpiresAt = 0;
    fingerprint.textContent = t("Unable to load the pairing QR. Restart the Desktop backend and try again.");
    throw error;
  } finally {
    state.pairingQrLoading = false;
  }
}

function formatControlTime(value) {
  const timestamp = Number(value || 0);
  if (!timestamp) return t("Never");
  return new Intl.DateTimeFormat(state.language === "zh-CN" ? "zh-CN" : "en", {
    month: "short", day: "numeric", hour: "2-digit", minute: "2-digit"
  }).format(new Date(timestamp));
}

function desktopControlToolLabel(toolId) {
  return t({
    "desktop.screenshot": "View screen",
    "desktop.click_xy": "Click",
    "desktop.type_text": "Type text",
    "desktop.hotkey": "Keyboard shortcut",
    "desktop.scroll": "Scroll",
    "desktop.window_switch": "Switch window",
    "desktop.file_select": "Select file"
  }[toolId] || "Desktop action");
}

function receiptDigest(value) {
  const digest = String(value || "");
  return digest ? `${digest.slice(0, 12)}...${digest.slice(-8)}` : t("None");
}

function renderDesktopActionReceipt(receipt) {
  const controllerName = receipt.controller_name || t("GalaxySSI App");
  const controllerPlatform = receipt.controller_platform || t("Unknown");
  const controllerFingerprint = receipt.controller_fingerprint || "";
  const status = receipt.status === "succeeded" ? t("Succeeded") : t("Failed");
  const details = [
    [t("Who"), `${controllerName} / ${controllerPlatform}`],
    [t("Identity"), receiptDigest(controllerFingerprint)],
    [t("Action"), desktopControlToolLabel(receipt.tool_id)],
    [t("Started"), formatControlTime(receipt.started_at)],
    [t("Completed"), formatControlTime(receipt.completed_at)],
    [t("Duration"), formatDuration(receipt.duration_ms)],
    [t("Result"), `${status} / ${receipt.summary || ""}`],
    [t("Receipt ID"), receiptDigest(receipt.receipt_id)],
    [t("Request digest"), receiptDigest(receipt.request_sha256)],
    [t("Input digest"), receiptDigest(receipt.input_sha256)],
    [t("Output digest"), receiptDigest(receipt.output_sha256)]
  ];
  if (receipt.evidence_sha256) {
    details.push([t("Visual evidence"), receiptDigest(receipt.evidence_sha256)]);
  }
  if (receipt.error_code) {
    details.push([
      t("Failure category"),
      `${receipt.error_code} / ${receipt.error_retryable ? t("Retryable") : t("Final")}`
    ]);
  }
  return `<details class="control-receipt-row">
    <summary>
      <span>
        <strong>${escapeHtml(receipt.summary || desktopControlToolLabel(receipt.tool_id))}</strong>
        <small>${escapeHtml(`${formatControlTime(receipt.completed_at)} · ${t("Verified receipt")} ${String(receipt.receipt_id || "").slice(0, 8)} · ${status}`)}</small>
      </span>
      <span class="receipt-verified">${escapeHtml(t("Verified"))}</span>
    </summary>
    <div class="control-receipt-details">
      ${details.map(([label, value]) => `<div><small>${escapeHtml(label)}</small><span>${escapeHtml(value)}</span></div>`).join("")}
    </div>
  </details>`;
}

function renderDesktopControl() {
  const control = state.desktopControl || { recent_audit: [] };
  const authorizations = Array.isArray(control.authorizations)
    ? [...control.authorizations].sort(
      (left, right) => Number(right.updated_at || 0) - Number(left.updated_at || 0)
    )
    : [];
  $("#authorizedAppList").innerHTML = authorizations.length
    ? authorizations.map((authorization) => {
      const active = authorization.status === "active";
      const appName = authorization.app_name || authorization.phone_name || t("GalaxySSI phone");
      const platform = authorization.app_platform || authorization.platform || t("Unknown");
      const fingerprint = authorization.app_identity_fingerprint
        || authorization.phone_fingerprint
        || "";
      const access = authorization.access_profile === "desktop_executor"
        ? t("Desktop Executor")
        : t("Restricted access");
      const timing = [
        authorization.granted_at
          ? t("Granted {time}", { time: formatControlTime(authorization.granted_at) })
          : "",
        authorization.last_used_at
          ? t("Last used {time}", { time: formatControlTime(authorization.last_used_at) })
          : t("Never used")
      ].filter(Boolean).join(" / ");
      return `<article class="authorized-app-row">
        <span class="phone-outline"></span>
        <div>
          <strong>${escapeHtml(`${appName} / ${platform}`)}</strong>
          <small>${escapeHtml(`${fingerprint.slice(0, 16) || t("Verified")} / ${access}`)}</small>
          <small>${escapeHtml(timing)}</small>
        </div>
        <span class="authorized-app-actions">
          <small class="${active ? "active" : "revoked"}">${escapeHtml(t(active ? "Active" : "Revoked"))}</small>
          ${active ? `<button data-revoke-authorization="${escapeHtml(authorization.authorization_id || "")}">${escapeHtml(t("Revoke"))}</button>` : ""}
        </span>
      </article>`;
    }).join("")
    : `<div class="history-empty">${escapeHtml(t("No app has Desktop execution access."))}</div>`;
  const receipts = Array.isArray(control.recent_receipts)
    ? control.recent_receipts.slice(0, 20)
    : [];
  const audit = Array.isArray(control.recent_audit)
    ? control.recent_audit
      .filter((row) => !["settings_changed", "desktop_action"].includes(row.event_type))
      .slice(0, Math.max(0, 20 - receipts.length))
    : [];
  const activity = [
    ...receipts.map(renderDesktopActionReceipt),
    ...audit.map((row) => `<article class="control-audit-row"><strong>${escapeHtml(row.summary || row.event_type || "")}</strong><small>${escapeHtml(`${formatControlTime(row.created_at)} · ${row.status || ""}`)}</small></article>`)
  ];
  $("#desktopControlAuditList").innerHTML = activity.length
    ? activity.join("")
    : `<div class="history-empty">${escapeHtml(t("No remote-control activity yet."))}</div>`;
}

async function refreshDesktopControl() {
  try {
    state.desktopControl = await window.galaxyssi.getDesktopControl();
    renderDesktopControl();
  } catch (error) {
    $("#desktopControlAuditList").innerHTML = `<div class="history-empty">${escapeHtml(error.message || String(error))}</div>`;
  }
}

function parsePhrases(value) {
  return Array.from(new Set(String(value || "").split(/[,;\n]/).map((item) => item.trim()).filter(Boolean))).slice(0, 32);
}

function memoryStateLabel(value) {
  const labels = {
    current: "Current",
    historical: "Historical",
    planned: "Planned",
    deprecated: "Deprecated",
    pending: "Pending review",
    pending_review: "Pending review",
    conflicted: "Conflict",
    superseded: "Deprecated",
    retracted: "Retracted",
    approved: "Approved",
    rejected: "Rejected",
    auto_merged: "Auto merged"
  };
  return t(labels[value] || value || "Current");
}

function memoryKindLabel(value) {
  const labels = {
    fact: "Fact",
    identity: "Identity",
    preference: "Preference",
    security: "Security",
    decision: "Decision",
    goal: "Goal",
    project_state: "Project state",
    device_state: "Device state",
    episode: "Task evidence",
    explicit: "Explicit memory",
    manual: "Manual memory"
  };
  return t(labels[value] || value || "Memory");
}

function memoryNamespaceLabel(value) {
  const labels = {
    general: "General",
    user: "User",
    project: "Project",
    device: "Device",
    security: "Security"
  };
  const [family, ...scopeParts] = String(value || "general").split(":");
  const label = t(labels[family] || family || "General");
  const scope = scopeParts.join(":");
  return scope ? `${label} · ${scope}` : label;
}

function memoryGraphNodeKindLabel(value) {
  const labels = {
    user: "User",
    device: "Device",
    application: "Application",
    feature: "Feature",
    setting: "Setting",
    agent: "Agent",
    model: "Model",
    tool: "Tool",
    project: "Project",
    concept: "Concept",
    state: "State"
  };
  return t(labels[value] || value || "Concept");
}

function memoryActionLabel(value) {
  const labels = {
    create: "Create",
    strengthen: "Strengthen",
    supersede: "Replace current",
    review_conflict: "Resolve conflict",
    consolidate: "Consolidate",
    link: "Create relationship"
  };
  return t(labels[value] || value || "Create");
}

function memoryRiskLabel(value) {
  const labels = {
    low: "Low risk",
    review_required: "Review required",
    private: "Private content blocked"
  };
  return t(labels[value] || value || "Review required");
}

function memoryFindingLabel(value) {
  const labels = {
    unresolved_conflict: "Unresolved memory conflict",
    stale_candidate: "Candidate waiting too long",
    low_confidence_reused: "Low-confidence memory reused",
    expired: "Expired memory retired",
    duplicate: "Equivalent memory consolidated",
    missing_evidence: "Memory has no evidence reference",
    broken_supersession_chain: "Supersession evidence chain is incomplete"
  };
  return t(labels[value] || value || "Memory needs review");
}

function memoryTime(value) {
  const timestamp = Number(value || 0);
  if (!timestamp) return "";
  return new Intl.DateTimeFormat(state.language === "zh-CN" ? "zh-CN" : "en", {
    month: "short",
    day: "numeric",
    hour: "2-digit",
    minute: "2-digit"
  }).format(new Date(timestamp));
}

function memoryEvidenceLabel(item) {
  const evidence = Array.isArray(item?.evidence) ? item.evidence.length : 0;
  return t("{count} evidence references", { count: evidence });
}

function memoryMatchesQuery(item, query) {
  if (!query) return true;
  const searchable = [
    item.content,
    item.kind,
    item.namespace,
    item.status,
    item.temporal_state
  ].join(" ").toLocaleLowerCase();
  return searchable.includes(query.toLocaleLowerCase());
}

function renderMemoryCandidate(candidate, conflict = false) {
  const currentMemories = Array.isArray(candidate.current_memories)
    ? candidate.current_memories
    : [];
  const comparison = conflict && currentMemories.length ? `
    <div class="memory-comparison" aria-label="${escapeHtml(t("Current and proposed memory"))}">
      <div>
        <span>${escapeHtml(t("Current"))}</span>
        ${currentMemories.map((memory) => `<p>${escapeHtml(memory.content || "")}</p>`).join("")}
      </div>
      <b aria-hidden="true">\u2192</b>
      <div>
        <span>${escapeHtml(t("Proposed"))}</span>
        <p>${escapeHtml(candidate.content || "")}</p>
      </div>
    </div>` : "";
  return `
    <article class="capability-item memory-candidate ${conflict ? "memory-conflict" : ""}">
      <div class="memory-item-body">
        <div class="memory-item-heading">
          <strong>${escapeHtml(memoryKindLabel(candidate.kind))}</strong>
          <span class="memory-status ${escapeHtml(candidate.status)}">${escapeHtml(memoryStateLabel(candidate.status))}</span>
        </div>
        ${comparison || `<p class="memory-content" title="${escapeHtml(candidate.content || "")}">${escapeHtml(String(candidate.content || "").slice(0, 500))}</p>`}
        <div class="memory-facts">
          <span>${escapeHtml(memoryActionLabel(candidate.evolution_action))}</span>
          <span>${escapeHtml(memoryRiskLabel(candidate.risk))}</span>
          <span>${escapeHtml(memoryNamespaceLabel(candidate.namespace))}</span>
          <span>${escapeHtml(memoryStateLabel(candidate.intended_temporal_state))}</span>
          <span>${escapeHtml(memoryEvidenceLabel(candidate))}</span>
          <span>${escapeHtml(memoryTime(candidate.created_at))}</span>
        </div>
      </div>
      <div class="capability-item-actions">
        <button data-reject-memory-candidate="${escapeHtml(candidate.id)}">${escapeHtml(t("Reject"))}</button>
        <button class="primary" data-approve-memory-candidate="${escapeHtml(candidate.id)}">${escapeHtml(t("Approve"))}</button>
      </div>
    </article>`;
}

function renderAcceptedMemoryRows(memories, query, emptyLabel) {
  const rows = memories.filter((memory) => memoryMatchesQuery(memory, query));
  return rows.length ? rows.map((memory) => {
    const stateLabel = memory.status === "active"
      ? (memory.temporal_state || "current")
      : memory.status;
    return `
      <article class="capability-item">
        <div class="memory-item-body">
          <div class="memory-item-heading">
            <strong>${escapeHtml(memoryKindLabel(memory.kind))}</strong>
            <span class="memory-status ${escapeHtml(stateLabel)}">${escapeHtml(memoryStateLabel(stateLabel))}</span>
          </div>
          <p class="memory-content" title="${escapeHtml(memory.content || "")}">${escapeHtml(String(memory.content || "").slice(0, 500))}</p>
          <div class="memory-facts">
            <span>${escapeHtml(memoryNamespaceLabel(memory.namespace))}</span>
            <span>${escapeHtml(memoryEvidenceLabel(memory))}</span>
            <span>${escapeHtml(t("{percent}% confidence", { percent: Math.round(Number(memory.confidence || 0) * 100) }))}</span>
            <span>${escapeHtml(memoryTime(memory.updated_at))}</span>
          </div>
        </div>
        <div class="capability-item-actions"><button data-forget-memory="${escapeHtml(memory.id)}">${escapeHtml(t("Forget"))}</button></div>
      </article>`;
  }).join("") : `<div class="history-empty">${escapeHtml(t(emptyLabel))}</div>`;
}

function memoryVisualizationTabs() {
  const views = [
    ["state", "State"],
    ["timeline", "Timeline"],
    ["graph", "Graph"],
    ["evidence", "Evidence"]
  ];
  return `
    <div class="memory-visualization-tabs" role="tablist" aria-label="${escapeHtml(t("Memory visualization"))}">
      ${views.map(([view, label]) => `
        <button
          class="${state.memory.visualizationView === view ? "active" : ""}"
          data-memory-visualization-view="${view}"
          role="tab"
          aria-selected="${state.memory.visualizationView === view ? "true" : "false"}"
        >${escapeHtml(t(label))}</button>
      `).join("")}
    </div>`;
}

function memoryTimelineEventLabel(value) {
  const labels = {
    memory_recorded: "Memory recorded",
    memory_superseded: "Memory replaced",
    memory_retracted: "Memory removed",
    candidate_created: "Memory candidate created",
    candidate_approved: "Memory candidate accepted",
    candidate_rejected: "Memory candidate rejected",
    critic_completed: "Memory audit completed",
    critic_failed: "Memory audit failed"
  };
  return t(labels[value] || value || "Memory updated");
}

function memoryRelationLabel(value) {
  const normalized = String(value || "related_to").replaceAll("_", " ");
  return t(normalized.charAt(0).toUpperCase() + normalized.slice(1));
}

function shortMemoryLabel(value, maximum = 24) {
  const clean = String(value || "").replace(/\s+/g, " ").trim();
  return clean.length > maximum ? `${clean.slice(0, maximum - 1)}\u2026` : clean;
}

function renderMemoryTimeline(visualization) {
  const events = Array.isArray(visualization.timeline) ? visualization.timeline : [];
  return `
    <section class="memory-visual-panel" aria-label="${escapeHtml(t("Memory timeline"))}">
      <div class="memory-section-heading">
        <strong>${escapeHtml(t("Memory timeline"))}</strong>
        <span>${escapeHtml(t("{count} lifecycle events", { count: events.length }))}</span>
      </div>
      <div class="memory-timeline">
        ${events.length ? events.map((event) => `
          <article class="memory-timeline-event ${escapeHtml(event.event_type || "")}">
            <span class="memory-timeline-marker" aria-hidden="true"></span>
            <div>
              <div class="memory-item-heading">
                <strong>${escapeHtml(memoryTimelineEventLabel(event.event_type))}</strong>
                <time>${escapeHtml(memoryTime(event.occurred_at))}</time>
              </div>
              <p>${escapeHtml(String(event.content || "").slice(0, 500))}</p>
              <div class="memory-facts">
                <span>${escapeHtml(memoryNamespaceLabel(event.namespace))}</span>
                <span>${escapeHtml(memoryStateLabel(event.temporal_state || event.status))}</span>
                <span>${escapeHtml(t("{count} evidence references", {
                  count: Number(event.evidence_count || 0)
                }))}</span>
              </div>
            </div>
          </article>
        `).join("") : `<div class="history-empty">${escapeHtml(t("No memory lifecycle events yet."))}</div>`}
      </div>
    </section>`;
}

function memoryGraphPositions(nodes, width = 680, height = 350) {
  const positions = new Map();
  if (nodes.length === 1) {
    positions.set(nodes[0].id, { x: width / 2, y: height / 2 });
    return positions;
  }
  nodes.forEach((node, index) => {
    const outer = index >= 10;
    const ringIndex = outer ? index - 10 : index;
    const ringCount = outer ? Math.max(1, nodes.length - 10) : Math.min(10, nodes.length);
    const radiusX = outer ? 285 : 178;
    const radiusY = outer ? 135 : 96;
    const angle = -Math.PI / 2 + (Math.PI * 2 * ringIndex) / ringCount;
    positions.set(node.id, {
      x: width / 2 + Math.cos(angle) * radiusX,
      y: height / 2 + Math.sin(angle) * radiusY
    });
  });
  return positions;
}

function renderMemoryGraph(visualization) {
  const graph = visualization.graph || {};
  const nodes = Array.isArray(graph.nodes) ? graph.nodes.slice(0, 24) : [];
  const nodeIds = new Set(nodes.map((node) => node.id));
  const relations = (Array.isArray(graph.relations) ? graph.relations : [])
    .filter((relation) => nodeIds.has(relation.from_node_id) && nodeIds.has(relation.to_node_id));
  if (!nodes.length) {
    return `
      <section class="memory-visual-panel">
        <div class="memory-section-heading"><strong>${escapeHtml(t("Relationship graph"))}</strong></div>
        <div class="history-empty">${escapeHtml(t("No entity relationships yet."))}</div>
      </section>`;
  }
  const selectedId = nodes.some((node) => node.id === state.memory.selectedGraphNodeId)
    ? state.memory.selectedGraphNodeId
    : nodes[0].id;
  const selected = nodes.find((node) => node.id === selectedId);
  const nodeById = new Map(nodes.map((node) => [node.id, node]));
  const positions = memoryGraphPositions(nodes);
  const selectedRelations = relations.filter((relation) => (
    relation.from_node_id === selectedId || relation.to_node_id === selectedId
  ));
  return `
    <section class="memory-visual-panel" aria-label="${escapeHtml(t("Relationship graph"))}">
      <div class="memory-section-heading">
        <strong>${escapeHtml(t("Relationship graph"))}</strong>
        <span>${escapeHtml(t("{nodes} entities - {relations} relationships", {
          nodes: nodes.length,
          relations: relations.length
        }))}</span>
      </div>
      <div class="memory-graph-shell">
        <svg class="memory-graph-canvas" viewBox="0 0 680 350" role="img" aria-label="${escapeHtml(t("Interactive memory relationship graph"))}">
          <g class="memory-graph-edges">
            ${relations.map((relation) => {
              const from = positions.get(relation.from_node_id);
              const to = positions.get(relation.to_node_id);
              if (!from || !to) return "";
              const connected = relation.from_node_id === selectedId || relation.to_node_id === selectedId;
              return `<line class="${connected ? "selected" : ""}" x1="${from.x.toFixed(1)}" y1="${from.y.toFixed(1)}" x2="${to.x.toFixed(1)}" y2="${to.y.toFixed(1)}"></line>`;
            }).join("")}
          </g>
          <g class="memory-graph-nodes">
            ${nodes.map((node) => {
              const position = positions.get(node.id);
              const selectedClass = node.id === selectedId ? "selected" : "";
              return `
                <g
                  class="memory-graph-node ${escapeHtml(node.temporal_state || "current")} ${selectedClass}"
                  data-memory-graph-node="${escapeHtml(node.id)}"
                  role="button"
                  tabindex="0"
                  aria-label="${escapeHtml(node.label)}"
                >
                  <circle cx="${position.x.toFixed(1)}" cy="${position.y.toFixed(1)}" r="${node.id === selectedId ? 25 : 21}"></circle>
                  <text x="${position.x.toFixed(1)}" y="${(position.y + 39).toFixed(1)}" text-anchor="middle">${escapeHtml(shortMemoryLabel(node.label, 18))}</text>
                </g>`;
            }).join("")}
          </g>
        </svg>
        <aside class="memory-graph-detail">
          <div class="memory-item-heading">
            <strong>${escapeHtml(selected?.label || "")}</strong>
            <span class="memory-status ${escapeHtml(selected?.temporal_state || "current")}">${escapeHtml(memoryStateLabel(selected?.temporal_state))}</span>
          </div>
          <p>${escapeHtml(t("{kind} in {namespace}", {
            kind: memoryGraphNodeKindLabel(selected?.kind),
            namespace: memoryNamespaceLabel(selected?.namespace)
          }))}</p>
          <div class="memory-facts">
            <span>${escapeHtml(t("{percent}% confidence", {
              percent: Math.round(Number(selected?.confidence || 0) * 100)
            }))}</span>
            <span>${escapeHtml(t("{count} evidence references", {
              count: Number(selected?.evidence_count || 0)
            }))}</span>
          </div>
          <div class="memory-graph-relations">
            ${selectedRelations.length ? selectedRelations.map((relation) => {
              const from = nodeById.get(relation.from_node_id);
              const to = nodeById.get(relation.to_node_id);
              return `
                <button data-memory-graph-node="${escapeHtml(
                  relation.from_node_id === selectedId ? relation.to_node_id : relation.from_node_id
                )}">
                  <span>${escapeHtml(from?.label || "")} \u2192 ${escapeHtml(to?.label || "")}</span>
                  <small>${escapeHtml(memoryRelationLabel(relation.kind))}</small>
                </button>`;
            }).join("") : `<p>${escapeHtml(t("No visible relationships for this entity."))}</p>`}
          </div>
        </aside>
      </div>
    </section>`;
}

function renderMemoryEvidence(visualization) {
  const chains = Array.isArray(visualization.evidence_chains)
    ? visualization.evidence_chains
    : [];
  if (!chains.length) {
    return `
      <section class="memory-visual-panel">
        <div class="memory-section-heading"><strong>${escapeHtml(t("Evidence chains"))}</strong></div>
        <div class="history-empty">${escapeHtml(t("No evidence chains yet."))}</div>
      </section>`;
  }
  const selectedId = chains.some((chain) => chain.id === state.memory.selectedEvidenceChainId)
    ? state.memory.selectedEvidenceChainId
    : chains[0].id;
  const selected = chains.find((chain) => chain.id === selectedId);
  return `
    <section class="memory-visual-panel" aria-label="${escapeHtml(t("Evidence chains"))}">
      <div class="memory-section-heading">
        <strong>${escapeHtml(t("Evidence chains"))}</strong>
        <span>${escapeHtml(t("{count} traceable memories", { count: chains.length }))}</span>
      </div>
      <div class="memory-evidence-layout">
        <div class="memory-evidence-index">
          ${chains.map((chain) => {
            const current = chain.versions?.find((version) => version.id === chain.current_memory_id)
              || chain.versions?.at(-1);
            return `
              <button class="${chain.id === selectedId ? "active" : ""}" data-memory-evidence-chain="${escapeHtml(chain.id)}">
                <strong>${escapeHtml(shortMemoryLabel(current?.content || chain.memory_key, 52))}</strong>
                <span>${escapeHtml(t("{versions} versions - {evidence} evidence", {
                  versions: Number(chain.versions?.length || 0),
                  evidence: Number(chain.evidence?.length || 0)
                }))}</span>
              </button>`;
          }).join("")}
        </div>
        <div class="memory-evidence-detail">
          <div class="memory-item-heading">
            <strong>${escapeHtml(memoryKindLabel(selected?.kind))}</strong>
            <span class="memory-status ${escapeHtml(selected?.current_temporal_state || "current")}">${escapeHtml(memoryStateLabel(selected?.current_temporal_state))}</span>
          </div>
          <p class="memory-content">${escapeHtml(
            selected?.versions?.find((version) => version.id === selected.current_memory_id)?.content
            || selected?.versions?.at(-1)?.content
            || ""
          )}</p>
          <div class="memory-facts">
            <span>${escapeHtml(memoryNamespaceLabel(selected?.namespace))}</span>
            <span>${escapeHtml(t("{count} graph references", {
              count: Number(selected?.graph_node_ids?.length || 0)
                + Number(selected?.graph_relation_ids?.length || 0)
            }))}</span>
          </div>
          <h4>${escapeHtml(t("Version history"))}</h4>
          <div class="memory-version-chain">
            ${(selected?.versions || []).map((version) => `
              <div>
                <span class="memory-version-dot ${escapeHtml(version.temporal_state || "current")}"></span>
                <p><strong>${escapeHtml(shortMemoryLabel(version.content, 90))}</strong><small>${escapeHtml(memoryTime(version.created_at))} - ${escapeHtml(memoryStateLabel(version.status === "active" ? version.temporal_state : version.status))}</small></p>
              </div>
            `).join("")}
          </div>
          <h4>${escapeHtml(t("Source evidence"))}</h4>
          <div class="memory-source-evidence">
            ${(selected?.evidence || []).length ? selected.evidence.map((evidence) => `
              <div>
                <strong>${escapeHtml(evidence.source || t("Memory evidence"))}</strong>
                <span>${escapeHtml(evidence.kind || t("Observation"))}${
                  evidence.task_id ? ` - ${escapeHtml(shortMemoryLabel(evidence.task_id, 32))}` : ""
                }</span>
                <time>${escapeHtml(memoryTime(evidence.observed_at))}</time>
              </div>
            `).join("") : `<p>${escapeHtml(t("This memory has no source evidence."))}</p>`}
          </div>
        </div>
      </div>
    </section>`;
}

function renderMemory() {
  const stats = state.memory.stats || {};
  const accepted = Array.isArray(state.memory.memories) ? state.memory.memories : [];
  const history = Array.isArray(state.memory.history) ? state.memory.history : [];
  const candidates = Array.isArray(state.memory.candidates) ? state.memory.candidates : [];
  const evolution = state.memory.evolution || {};
  const evolutionSummary = evolution.summary || {};
  const conflicts = Array.isArray(evolution.conflicts) ? evolution.conflicts : [];
  const query = String(state.memory.query || "").trim();
  const current = accepted.filter((memory) => (
    memory.status === "active"
    && String(memory.temporal_state || "current") === "current"
  ));
  const planned = accepted.filter((memory) => (
    memory.status === "active"
    && memory.temporal_state === "planned"
  ));
  const acceptedHistorical = accepted.filter((memory) => (
    memory.status === "active"
    && memory.temporal_state === "historical"
  ));
  const acceptedDeprecated = accepted.filter((memory) => (
    memory.status === "active"
    && memory.temporal_state === "deprecated"
  ));
  const inboxCandidates = candidates.filter((candidate) => candidate.status === "pending_review");
  const pendingCount = Number(evolutionSummary.pending_review || inboxCandidates.length || 0);
  const currentCount = Number(
    evolutionSummary.current ?? stats.temporal_counts?.current ?? current.length
  );
  const plannedCount = Number(
    evolutionSummary.planned ?? stats.temporal_counts?.planned ?? planned.length
  );
  const historyCount = Number(
    evolutionSummary.historical ?? stats.temporal_counts?.historical ?? acceptedHistorical.length
  ) + Number(
    evolutionSummary.deprecated ?? stats.temporal_counts?.deprecated
      ?? (acceptedDeprecated.length + history.length)
  );
  $("#memorySummary").textContent = t("{current} current · {pending} pending · {conflicts} conflicts", {
    current: currentCount,
    pending: pendingCount,
    conflicts: Number(evolutionSummary.conflicted || conflicts.length || 0)
  });
  $("#memoryCurrentCount").textContent = String(currentCount);
  $("#memoryPlannedCount").textContent = String(plannedCount);
  $("#memoryInboxCount").textContent = String(pendingCount);
  $("#memoryConflictCount").textContent = String(Number(evolutionSummary.conflicted || conflicts.length || 0));
  $("#memoryHistoryCount").textContent = String(historyCount);
  $$("[data-memory-view]").forEach((button) => {
    button.classList.toggle("active", button.dataset.memoryView === state.memory.view);
  });
  const searchField = $("#memorySearch")?.closest(".search-field");
  if (searchField) searchField.hidden = state.memory.view === "overview";

  if (state.memory.view === "overview") {
    const visualization = state.memory.visualization || {};
    const visualizationTabs = memoryVisualizationTabs();
    if (state.memory.visualizationView === "timeline") {
      $("#memoryList").innerHTML = `${visualizationTabs}${renderMemoryTimeline(visualization)}`;
      return;
    }
    if (state.memory.visualizationView === "graph") {
      $("#memoryList").innerHTML = `${visualizationTabs}${renderMemoryGraph(visualization)}`;
      return;
    }
    if (state.memory.visualizationView === "evidence") {
      $("#memoryList").innerHTML = `${visualizationTabs}${renderMemoryEvidence(visualization)}`;
      return;
    }
    const health = evolution.health || {};
    const critic = evolution.critic || stats.critic || {};
    const latestCritic = critic.latest || {};
    const graph = evolution.graph || stats.graph || {};
    const findings = Array.isArray(health.findings) ? health.findings : [];
    const recent = Array.isArray(evolution.recent_evolution)
      ? evolution.recent_evolution.slice(0, 6)
      : [];
    const stateMetrics = [
      ["Current", evolutionSummary.current || 0, "current"],
      ["Planned", evolutionSummary.planned || 0, "planned"],
      ["Pending review", evolutionSummary.pending_review || 0, "pending"],
      ["Conflicts", evolutionSummary.conflicted || 0, "conflicted"]
    ];
    $("#memoryList").innerHTML = `${visualizationTabs}
      <section class="memory-dashboard-grid" aria-label="${escapeHtml(t("Memory state"))}">
        ${stateMetrics.map(([label, value, tone]) => `
          <button class="memory-metric ${tone}" data-memory-overview-route="${
            tone === "conflicted"
              ? "conflicts"
              : tone === "pending"
                ? "inbox"
                : tone === "planned"
                  ? "planned"
                  : "current"
          }">
            <strong>${escapeHtml(String(value))}</strong>
            <span>${escapeHtml(t(label))}</span>
          </button>`).join("")}
      </section>
      <section class="memory-health ${health.status === "attention" ? "attention" : "healthy"}">
        <div>
          <div class="memory-health-heading">
            <strong>${escapeHtml(t("Memory health"))}</strong>
            <span>${escapeHtml(health.status === "attention" ? t("Needs attention") : t("Healthy"))}</span>
          </div>
          <button class="memory-audit-button" data-run-memory-critic>
            ${escapeHtml(t("Run audit"))}
          </button>
        </div>
        <p>${escapeHtml(
          critic.last_run_at
            ? t("Last audit {time} - {actions} safe actions", {
              time: memoryTime(critic.last_run_at),
              actions: Number(latestCritic.action_count || 0)
            })
            : t("Memory audit has not run yet.")
        )}</p>
        ${findings.length ? findings.map((finding) => `
          <p><b>${escapeHtml(String(finding.count || 0))}</b> ${escapeHtml(memoryFindingLabel(finding.kind))}</p>
        `).join("") : `<p>${escapeHtml(t("No stale, conflicting, or unsupported memory was found."))}</p>`}
      </section>
      <section class="memory-overview-section">
        <div class="memory-section-heading">
          <strong>${escapeHtml(t("Evidence-backed state"))}</strong>
          <span>${escapeHtml(t("{count} evidence references", { count: Number(evolutionSummary.evidence || 0) }))}</span>
        </div>
        <div class="memory-namespace-grid">
          ${Object.entries(evolution.namespace_counts || {}).map(([namespace, count]) => `
            <div><span>${escapeHtml(memoryNamespaceLabel(namespace))}</span><strong>${escapeHtml(String(count))}</strong></div>
          `).join("") || `<p>${escapeHtml(t("No durable memory yet."))}</p>`}
        </div>
      </section>
      <section class="memory-overview-section">
        <div class="memory-section-heading">
          <strong>${escapeHtml(t("Relationship graph"))}</strong>
          <span>${escapeHtml(t("{nodes} entities · {relations} relationships", {
            nodes: Number(graph.node_count || 0),
            relations: Number(graph.relation_count || 0)
          }))}</span>
        </div>
        <div class="memory-namespace-grid">
          ${Object.entries(graph.node_kinds || {})
            .filter(([, count]) => Number(count) > 0)
            .map(([kind, count]) => `
              <div><span>${escapeHtml(memoryGraphNodeKindLabel(kind))}</span><strong>${escapeHtml(String(count))}</strong></div>
            `).join("") || `<p>${escapeHtml(t("No entity relationships yet."))}</p>`}
        </div>
      </section>
      <section class="memory-overview-section">
        <div class="memory-section-heading">
          <strong>${escapeHtml(t("Recent evolution"))}</strong>
          <span>${escapeHtml(t("Encrypted lifecycle record"))}</span>
        </div>
        <div class="memory-evolution-list">
          ${recent.length ? recent.map((item) => `
            <div>
              <span class="memory-action-mark ${escapeHtml(item.evolution_action || "create")}"></span>
              <p><strong>${escapeHtml(memoryActionLabel(item.evolution_action))}</strong><small>${escapeHtml(String(item.content || "").slice(0, 140))}</small></p>
              <time>${escapeHtml(memoryTime(item.reviewed_at || item.created_at))}</time>
            </div>
          `).join("") : `<p class="history-empty">${escapeHtml(t("No memory evolution has been recorded yet."))}</p>`}
        </div>
      </section>`;
    return;
  }

  if (state.memory.view === "current") {
    $("#memoryList").innerHTML = renderAcceptedMemoryRows(current, query, "No current memory.");
    return;
  }

  if (state.memory.view === "planned") {
    $("#memoryList").innerHTML = renderAcceptedMemoryRows(planned, query, "No planned memory.");
    return;
  }

  if (state.memory.view === "inbox") {
    const rows = inboxCandidates.filter((candidate) => memoryMatchesQuery(candidate, query));
    $("#memoryList").innerHTML = rows.length
      ? rows.map((candidate) => renderMemoryCandidate(candidate)).join("")
      : `<div class="history-empty">${escapeHtml(t("No memory candidates need review."))}</div>`;
    return;
  }

  if (state.memory.view === "conflicts") {
    const rows = conflicts.filter((candidate) => memoryMatchesQuery(candidate, query));
    $("#memoryList").innerHTML = rows.length
      ? rows.map((candidate) => renderMemoryCandidate(candidate, true)).join("")
      : `<div class="history-empty">${escapeHtml(t("No unresolved memory conflicts."))}</div>`;
    return;
  }

  if (state.memory.view === "history") {
    const lifecycle = (Array.isArray(evolution.recent_evolution) ? evolution.recent_evolution : [])
      .filter((item) => !["pending_review", "conflicted"].includes(item.status))
      .filter((item) => memoryMatchesQuery(item, query));
    const historical = Array.from(new Map(
      [...acceptedHistorical, ...acceptedDeprecated, ...history]
        .map((memory) => [memory.id, memory])
    ).values()).filter((memory) => memoryMatchesQuery(memory, query));
    $("#memoryList").innerHTML = lifecycle.length || historical.length ? `
      ${lifecycle.map((item) => `
        <article class="capability-item memory-history-item">
          <span class="memory-action-mark ${escapeHtml(item.evolution_action || "create")}"></span>
          <div class="memory-item-body">
            <div class="memory-item-heading">
              <strong>${escapeHtml(memoryActionLabel(item.evolution_action))}</strong>
              <span class="memory-status ${escapeHtml(item.status)}">${escapeHtml(memoryStateLabel(item.status))}</span>
            </div>
            <p class="memory-content">${escapeHtml(String(item.content || "").slice(0, 500))}</p>
            <div class="memory-facts">
              <span>${escapeHtml(memoryKindLabel(item.kind))}</span>
              <span>${escapeHtml(memoryEvidenceLabel(item))}</span>
              <span>${escapeHtml(memoryTime(item.reviewed_at || item.created_at))}</span>
            </div>
          </div>
        </article>`).join("")}
      ${historical.map((memory) => `
        <article class="capability-item">
          <div class="memory-item-body">
            <div class="memory-item-heading">
              <strong>${escapeHtml(memoryKindLabel(memory.kind))}</strong>
              <span class="memory-status ${escapeHtml(memory.status === "active" ? memory.temporal_state : memory.status)}">${escapeHtml(memoryStateLabel(memory.status === "active" ? memory.temporal_state : memory.status))}</span>
            </div>
            <p class="memory-content">${escapeHtml(String(memory.content || "").slice(0, 500))}</p>
            <div class="memory-facts">
              <span>${escapeHtml(memoryNamespaceLabel(memory.namespace))}</span>
              <span>${escapeHtml(memoryEvidenceLabel(memory))}</span>
              ${memory.superseded_by_id ? `<span>${escapeHtml(t("Replaced by newer memory"))}</span>` : ""}
              <span>${escapeHtml(memoryTime(memory.updated_at))}</span>
            </div>
          </div>
        </article>`).join("")}
    ` : `<div class="history-empty">${escapeHtml(t("No memory evolution has been recorded yet."))}</div>`;
    return;
  }

  $("#memoryList").innerHTML = renderAcceptedMemoryRows(current, query, "No matching memory.");
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

function marketplaceKindLabel(kind) {
  return {
    native_tool: t("Tool"),
    mcp: "MCP",
    automation: t("Automation")
  }[kind] || t("Tool");
}

function marketplaceStateLabel(stateName) {
  return {
    built_in: t("Built in"),
    available: t("Available"),
    installed: t("Installed"),
    needs_setup: t("Needs setup"),
    unavailable: t("Unavailable")
  }[stateName] || stateName;
}

function renderMarketplace() {
  const allItems = Array.isArray(state.marketplace.items) ? state.marketplace.items : [];
  const items = state.marketplace.kind
    ? allItems.filter((item) => item.kind === state.marketplace.kind)
    : allItems;
  const summary = state.marketplace.summary || {};
  $("#marketplaceSummary").textContent = t("{installed} installed of {total}", {
    installed: Number(summary.installed || 0),
    total: Number(summary.total || allItems.length)
  });
  $$("[data-marketplace-kind]").forEach((button) => {
    button.classList.toggle("active", button.dataset.marketplaceKind === state.marketplace.kind);
  });
  $("#marketplaceList").innerHTML = items.length ? items.map((item) => {
    const dependencies = Array.isArray(item.dependencies) ? item.dependencies : [];
    const capabilities = Array.isArray(item.capabilities) ? item.capabilities : [];
    const permissionDiff = item.permission_diff || {};
    const addedPermissions = Array.isArray(permissionDiff.added) ? permissionDiff.added : [];
    const removedPermissions = Array.isArray(permissionDiff.removed) ? permissionDiff.removed : [];
    const endpointRequired = item.kind === "mcp" && dependencies.includes("endpoint");
    const canInstall = (item.install_state === "available" || item.update_available || item.revoked) && !endpointRequired;
    const needsSetup = item.install_state === "needs_setup" || endpointRequired;
    const canRemove = ["installed", "needs_setup"].includes(item.install_state)
      && item.kind !== "native_tool";
    const version = item.installed_version && item.installed_version !== item.available_version
      ? `v${item.installed_version} \u2192 v${item.available_version}`
      : `v${item.available_version || item.version || "1.0.0"}`;
    const detail = [
      item.publisher,
      version,
      item.status_detail
    ].filter(Boolean).join(" \u00b7 ");
    const lifecycle = [
      capabilities.length ? t("{count} capabilities", { count: capabilities.length }) : "",
      addedPermissions.length ? t("{count} new permissions", { count: addedPermissions.length }) : "",
      removedPermissions.length ? t("{count} removed permissions", { count: removedPermissions.length }) : "",
      item.rollback_available
        ? t("Rollback: {versions}", { versions: (item.rollback_versions || []).map((value) => `v${value}`).join(", ") })
        : ""
    ].filter(Boolean).join(" \u00b7 ");
    const installLabel = item.revoked
      ? t("Restore access")
      : item.update_available
        ? t("Update")
        : t("Install");
    return `
      <article class="capability-item marketplace-lifecycle-item ${item.revoked ? "is-revoked" : ""}">
        <div>
          <strong><em class="marketplace-kind">${escapeHtml(marketplaceKindLabel(item.kind))}</em>${escapeHtml(item.name || item.id)}</strong>
          <small>${escapeHtml(item.summary || "")}</small>
          <small>${escapeHtml(detail)}</small>
          ${lifecycle ? `<small class="${addedPermissions.length ? "marketplace-permission-change" : ""}">${escapeHtml(lifecycle)}</small>` : ""}
        </div>
        <div class="capability-item-actions">
          <span class="marketplace-state">${escapeHtml(item.revoked ? t("Access revoked") : marketplaceStateLabel(item.install_state))}</span>
          ${item.rollback_available ? `<button data-rollback-marketplace="${escapeHtml(item.id)}">${escapeHtml(t("Rollback"))}</button>` : ""}
          ${item.revocable && !item.revoked ? `<button data-revoke-marketplace="${escapeHtml(item.id)}">${escapeHtml(t("Revoke"))}</button>` : ""}
          ${canRemove ? `<button data-uninstall-marketplace="${escapeHtml(item.id)}">${escapeHtml(t("Uninstall"))}</button>` : ""}
          ${canInstall ? `<button class="primary" data-install-marketplace="${escapeHtml(item.id)}">${escapeHtml(installLabel)}</button>` : ""}
          ${needsSetup ? `<button data-setup-marketplace="${escapeHtml(item.id)}" data-marketplace-item-kind="${escapeHtml(item.kind)}">${escapeHtml(t("Set up"))}</button>` : ""}
        </div>
      </article>`;
  }).join("") : `<div class="history-empty">${escapeHtml(t("No marketplace items matched this filter."))}</div>`;
}

function renderMcp() {
  const permissionLabels = {
    ask_for_changes: "Ask before changes",
    read_only: "Read only",
    trusted: "Trusted",
    disabled: "Disabled"
  };
  const transportLabels = {
    local_stdio: "Local process (stdio)",
    streamable_http: "Remote server (Streamable HTTP)"
  };
  const stateLabels = {
    configured: "Configured",
    connecting: "Connecting",
    ready: "Ready",
    error: "Needs attention",
    disabled: "Disabled"
  };
  $("#mcpSummary").textContent = t("{count} configured connections", { count: state.mcp.length });
  $("#mcpList").innerHTML = state.mcp.length ? state.mcp.map((connection) => {
    const toolCount = Array.isArray(connection.tool_ids) ? connection.tool_ids.length : 0;
    const server = [connection.server_name, connection.server_version].filter(Boolean).join(" ");
    const details = [
      t(transportLabels[connection.transport] || "Local process (stdio)"),
      t(stateLabels[connection.state] || "Configured"),
      toolCount ? t("{count} tools", { count: toolCount }) : "",
      server,
      connection.last_latency_ms ? `${Number(connection.last_latency_ms)} ms` : "",
      connection.auto_invoke ? t("Auto") : "",
      t(permissionLabels[connection.permission_mode] || "Ask before changes")
    ].filter(Boolean).join(" · ");
    return `
    <article class="capability-item ${connection.state === "error" ? "has-error" : ""}">
      <div><strong>${escapeHtml(connection.name || connection.id)}</strong><small>${escapeHtml(details)}</small>${connection.last_error ? `<small class="capability-error">${escapeHtml(connection.last_error)}</small>` : ""}</div>
      <div class="capability-item-actions">
        <select class="mcp-policy-select" data-mcp-permission="${escapeHtml(connection.id)}" aria-label="${escapeHtml(t("Permission policy"))}">
          ${Object.entries(permissionLabels).map(([value, label]) => `<option value="${escapeHtml(value)}" ${connection.permission_mode === value ? "selected" : ""}>${escapeHtml(t(label))}</option>`).join("")}
        </select>
        <button data-edit-mcp="${escapeHtml(connection.id)}">${escapeHtml(t("Edit"))}</button>
        <button data-probe-mcp="${escapeHtml(connection.id)}">${escapeHtml(t("Test"))}</button>
        <button class="primary" data-chat-mcp="${escapeHtml(connection.id)}">${escapeHtml(t("Chat"))}</button>
        <button data-delete-mcp="${escapeHtml(connection.id)}">${escapeHtml(t("Delete"))}</button>
      </div>
    </article>`;
  }).join("") : `<div class="history-empty">${escapeHtml(t("No MCP connections configured."))}</div>`;
  const audit = Array.isArray(state.mcpAudit) ? state.mcpAudit : [];
  $("#mcpAuditList").innerHTML = audit.length ? audit.slice(0, 40).map((entry) => {
    const parameters = entry.parameter_preview && Object.keys(entry.parameter_preview).length
      ? JSON.stringify(entry.parameter_preview)
      : t("No parameters");
    const permissions = Array.isArray(entry.permissions) && entry.permissions.length
      ? entry.permissions.join(" · ")
      : t("No additional permissions");
    const statusClass = entry.status === "succeeded" ? "ok" : entry.status === "denied" ? "denied" : "failed";
    return `
      <article class="mcp-audit-row ${statusClass}">
        <div class="mcp-audit-row-heading">
          <strong>${escapeHtml(entry.connection_name || entry.connection_id)} · ${escapeHtml(entry.tool_name || t("Unknown tool"))}</strong>
          <span class="mcp-risk ${escapeHtml(entry.risk || "medium")}">${escapeHtml(t((entry.risk || "medium").replace(/^./, (value) => value.toUpperCase())))}</span>
        </div>
        <small>${escapeHtml(t(entry.status === "succeeded" ? "Succeeded" : entry.status === "denied" ? "Denied" : "Failed"))} · ${escapeHtml(entry.source || "")} · ${escapeHtml(`${Number(entry.duration_ms || 0)} ms`)}</small>
        <small>${escapeHtml(t("Permissions"))}: ${escapeHtml(permissions)}</small>
        <code>${escapeHtml(parameters)}</code>
      </article>`;
  }).join("") : `<div class="history-empty">${escapeHtml(t("No MCP tool activity yet."))}</div>`;
  renderMcpImporter();
}

function mcpImportSourceLabel(source) {
  return {
    claude: "Claude",
    codex: "Codex",
    openclaw: "OpenClaw",
    hermes: "Hermes",
    mcp_json: "MCP JSON",
    mcp_toml: "MCP TOML",
    mcp_yaml: "MCP YAML"
  }[source] || "MCP";
}

function renderMcpImporter() {
  const sources = Array.isArray(state.mcpImport.sources) ? state.mcpImport.sources : [];
  $("#mcpDiscoveredSources").innerHTML = sources.length
    ? `<small>${escapeHtml(t("Detected configurations"))}</small>${sources.map((source) => `
      <button class="mcp-import-source" data-mcp-import-path="${escapeHtml(source.path)}" data-mcp-import-hint="${escapeHtml(source.source)}">
        <strong>${escapeHtml(mcpImportSourceLabel(source.source))}</strong>
        <span>${escapeHtml(source.file_name || "")}</span>
      </button>`).join("")}`
    : `<small>${escapeHtml(t("No installed MCP configuration was detected. Choose a file instead."))}</small>`;

  const summary = $("#mcpImportFileSummary");
  const preview = state.mcpImport.preview;
  summary.hidden = !state.mcpImport.fileName;
  summary.textContent = state.mcpImport.fileName
    ? `${state.mcpImport.fileName} · ${mcpImportSourceLabel(preview?.source || state.mcpImport.sourceHint)}`
    : "";
  $("#mcpImportPreview").innerHTML = preview?.candidates?.length
    ? preview.candidates.map((candidate) => {
      const warnings = Array.isArray(candidate.warnings) ? candidate.warnings : [];
      const missing = Array.isArray(candidate.missing_environment)
        ? candidate.missing_environment
        : [];
      const target = candidate.transport === "local_stdio"
        ? candidate.command
        : candidate.endpoint;
      return `
        <label class="mcp-import-candidate ${candidate.importable ? "" : "blocked"}">
          <input type="checkbox" data-mcp-import-id="${escapeHtml(candidate.id)}" ${candidate.importable ? "checked" : "disabled"}>
          <span class="mcp-import-candidate-body">
            <span class="mcp-import-candidate-heading">
              <strong>${escapeHtml(candidate.name || candidate.id)}</strong>
              <span>${escapeHtml(t(candidate.conflict ? "Replace" : candidate.importable ? "Ready to import" : "Needs changes"))}</span>
            </span>
            <small>${escapeHtml(mcpImportSourceLabel(candidate.source))} · ${escapeHtml(target || t("No safe target"))}</small>
            ${missing.length ? `<small class="mcp-import-warning">${escapeHtml(t("Missing environment"))}: ${escapeHtml(missing.join(", "))}</small>` : ""}
            ${warnings.map((warning) => `<small class="mcp-import-warning">${escapeHtml(t(warning))}</small>`).join("")}
          </span>
        </label>`;
    }).join("")
    : "";
  $("#commitMcpImportButton").hidden = !preview?.candidates?.some(
    (candidate) => candidate.importable
  );
}

function proactiveTriggerSummary(trigger = {}) {
  if (trigger.kind === "cron") return `${trigger.cron || "-"} \u00b7 ${trigger.time_zone || "UTC"}`;
  if (trigger.kind === "interval" || trigger.kind === "goal_checkpoint") {
    return t("Every {seconds} seconds", { seconds: Number(trigger.interval_seconds || 0) });
  }
  if (trigger.kind === "webhook") return t("Trusted webhook");
  return t("Manual");
}

function proactiveStatusLabel(status) {
  const labels = {
    queued: "Queued",
    running: "Running",
    waiting: "Waiting",
    retrying: "Retrying",
    completed: "Completed",
    failed: "Failed",
    cancelled: "Cancelled",
    skipped: "Skipped"
  };
  return t(labels[status] || status || "Queued");
}

function renderProactiveTasks() {
  const tasks = Array.isArray(state.proactiveTasks) ? state.proactiveTasks : [];
  const active = tasks.filter((task) => task.enabled).length;
  $("#proactiveSummary").textContent = tasks.length
    ? t("{active} active of {total} proactive tasks", { active, total: tasks.length })
    : t("Durable Cron, goal, webhook, Agent team, workflow, and tool execution");
  $("#proactiveTaskList").innerHTML = tasks.length ? tasks.map((task) => {
    const next = Number(task.next_run_at_millis || 0);
    const detail = [
      proactiveTriggerSummary(task.trigger),
      task.action?.kind ? t(task.action.kind.replaceAll("_", " ")) : "",
      next ? new Date(next).toLocaleString() : ""
    ].filter(Boolean).join(" \u00b7 ");
    return `<article class="capability-item ${task.task_id === state.selectedProactiveTaskId ? "selected" : ""}">
      <div>
        <strong>${escapeHtml(task.name || task.task_id)}</strong>
        <small>${escapeHtml(detail)}</small>
      </div>
      <div class="capability-item-actions">
        <button data-toggle-proactive="${escapeHtml(task.task_id)}" data-enabled="${task.enabled ? "1" : "0"}">${escapeHtml(t(task.enabled ? "Pause" : "Enable"))}</button>
        <button data-edit-proactive="${escapeHtml(task.task_id)}">${escapeHtml(t("Edit"))}</button>
        <button data-runs-proactive="${escapeHtml(task.task_id)}">${escapeHtml(t("Runs"))}</button>
        <button class="primary" data-trigger-proactive="${escapeHtml(task.task_id)}">${escapeHtml(t("Run"))}</button>
        <button data-delete-proactive="${escapeHtml(task.task_id)}">${escapeHtml(t("Delete"))}</button>
      </div>
    </article>`;
  }).join("") : `<div class="history-empty">${escapeHtml(t("No proactive tasks configured."))}</div>`;
  renderProactiveRuns();
}

function renderProactiveRuns() {
  const runs = Array.isArray(state.proactiveRuns) ? state.proactiveRuns : [];
  $("#proactiveRunList").innerHTML = runs.length ? runs.map((run) => {
    const output = String(run.output?.reply || run.error_message || "").trim();
    const active = !["completed", "failed", "cancelled", "skipped"].includes(run.status);
    return `<article class="capability-item">
      <div>
        <strong>${escapeHtml(proactiveStatusLabel(run.status))}</strong>
        <small>${escapeHtml(new Date(Number(run.scheduled_for_millis || Date.now())).toLocaleString())}${output ? ` \u00b7 ${escapeHtml(output.slice(0, 180))}` : ""}</small>
      </div>
      <div class="capability-item-actions">
        ${active ? `<button data-cancel-proactive-run="${escapeHtml(run.run_id)}">${escapeHtml(t("Cancel"))}</button>` : ""}
      </div>
    </article>`;
  }).join("") : "";
}

function updateCapabilityCount() {
  const total = Number(state.memory.stats?.active || 0)
    + state.skills.filter((skill) => skill.enabled).length
    + state.mcp.filter((item) => item.enabled).length
    + state.proactiveTasks.filter((item) => item.enabled).length;
  elements.capabilityCount.textContent = String(total);
}

async function refreshMemory(query = "") {
  const [current, history, inbox, evolution, visualization] = await Promise.all([
    window.galaxyssi.getDesktopMemory(query, 100, "active"),
    window.galaxyssi.getDesktopMemory("", 100, "history"),
    window.galaxyssi.getDesktopMemoryInbox(100),
    window.galaxyssi.getDesktopMemoryEvolution(100),
    window.galaxyssi.getDesktopMemoryVisualization(100)
  ]);
  state.memory = {
    ...state.memory,
    memories: Array.isArray(current.memories) ? current.memories : [],
    history: Array.isArray(history.memories) ? history.memories : [],
    candidates: Array.isArray(inbox.candidates) ? inbox.candidates : [],
    evolution: evolution || {},
    visualization: visualization || {},
    stats: current.stats || inbox.stats || {},
    query
  };
  renderMemory();
  updateCapabilityCount();
}

async function refreshCapabilities() {
  try {
    const [memory, memoryHistory, memoryInbox, memoryEvolution, memoryVisualization, marketplace, skills, mcp, mcpImportSources, proactive, proactiveRuns] = await Promise.all([
      window.galaxyssi.getDesktopMemory("", 100, "active"),
      window.galaxyssi.getDesktopMemory("", 100, "history"),
      window.galaxyssi.getDesktopMemoryInbox(100),
      window.galaxyssi.getDesktopMemoryEvolution(100),
      window.galaxyssi.getDesktopMemoryVisualization(100),
      window.galaxyssi.getToolMarketplace(),
      window.galaxyssi.getDesktopSkills(),
      window.galaxyssi.getDesktopMcp(),
      window.galaxyssi.getDesktopMcpImportSources(),
      window.galaxyssi.listProactiveTasks(200),
      window.galaxyssi.listProactiveRuns(state.selectedProactiveTaskId, 100)
    ]);
    state.memory = {
      ...state.memory,
      memories: Array.isArray(memory.memories) ? memory.memories : [],
      history: Array.isArray(memoryHistory.memories) ? memoryHistory.memories : [],
      candidates: Array.isArray(memoryInbox.candidates) ? memoryInbox.candidates : [],
      evolution: memoryEvolution || {},
      visualization: memoryVisualization || {},
      stats: memory.stats || memoryInbox.stats || {},
      query: ""
    };
    state.marketplace = {
      ...state.marketplace,
      items: Array.isArray(marketplace.items) ? marketplace.items : [],
      summary: marketplace.summary || {}
    };
    state.skills = Array.isArray(skills.skills) ? skills.skills : [];
    state.mcp = Array.isArray(mcp.connections) ? mcp.connections : [];
    state.mcpAudit = Array.isArray(mcp.audit) ? mcp.audit : [];
    state.mcpImport.sources = Array.isArray(mcpImportSources.sources)
      ? mcpImportSources.sources
      : [];
    state.proactiveTasks = Array.isArray(proactive.tasks) ? proactive.tasks : [];
    state.proactiveRuns = Array.isArray(proactiveRuns.runs) ? proactiveRuns.runs : [];
    renderMarketplace();
    renderMemory();
    renderSkills();
    renderMcp();
    renderProactiveTasks();
    updateCapabilityCount();
  } catch (error) {
    $("#memorySummary").textContent = error.message || String(error);
  }
}

function renderCommandCatalog() {
  const summary = $("#commandSummary");
  const catalog = $("#commandCatalog");
  const commands = Array.isArray(state.commands?.commands) ? state.commands.commands : [];
  const roots = Array.isArray(state.commands?.roots) ? state.commands.roots : [];
  if (!summary || !catalog) return;
  summary.innerHTML = `
    <span><strong>${t("Catalog")}</strong>${Number(state.commands?.catalog_size || commands.length)} ${t("commands")}</span>
    <span><strong>${t("Roots")}</strong>${roots.length}</span>
    <span><strong>${t("Handlers")}</strong>${commands.filter((item) => item.handler).length}/${commands.length}</span>
    <span><strong>${t("Shown")}</strong>${Math.min(commands.length, 120)}</span>
  `;
  catalog.innerHTML = "";
  if (!commands.length) {
    catalog.textContent = t("No commands matched the current filter.");
    return;
  }
  for (const command of commands.slice(0, 120)) {
    const row = document.createElement("button");
    row.className = "command-row";
    row.type = "button";
    row.dataset.commandId = command.command_id || "";
    row.innerHTML = `
      <span><strong>${escapeHtml(command.command_id || "")}</strong><em>${escapeHtml((command.aliases || [])[0] || `/${command.root || ""} ${command.action || ""}`)}</em></span>
      <span>${escapeHtml(command.risk || "read")}</span>
      <span>${command.handler ? t("handler") : t("missing")}</span>
      <span>${escapeHtml(command.summary || "")}</span>
    `;
    catalog.appendChild(row);
  }
}

function renderCommandRuns() {
  const target = $("#commandRuns");
  if (!target) return;
  target.innerHTML = "";
  if (!state.commandRuns.length) {
    target.textContent = t("No command runs yet.");
    return;
  }
  for (const run of state.commandRuns.slice(0, 30)) {
    const row = document.createElement("div");
    const status = String(run.status || "unknown");
    row.className = `audit-entry ${status === "completed" ? "ok" : ["failed", "denied", "unavailable", "not_found"].includes(status) ? "warn" : ""}`;
    row.innerHTML = `
      <div><strong>${escapeHtml(run.command_id || "command")}</strong><span>${escapeHtml(status)}</span></div>
      <div><strong>${escapeHtml(run.run_id || "")}</strong><span>${escapeHtml(run.error_code || "ok")}</span></div>
      <div><strong>${escapeHtml(run.completed_at || "")}</strong><span>${escapeHtml(run.message || JSON.stringify(run.data || {}).slice(0, 160))}</span></div>
    `;
    target.appendChild(row);
  }
}

async function refreshCommands() {
  const button = $("#refreshCommandsButton");
  if (button) button.disabled = true;
  try {
    const root = $("#commandRootFilter")?.value.trim() || "";
    state.commands = await window.galaxyssi.listCommands(root);
    renderCommandCatalog();
  } catch (error) {
    $("#commandSummary").textContent = `${t("Command catalog unavailable")}: ${error.message || String(error)}`;
    $("#commandCatalog").textContent = "";
  } finally {
    if (button) button.disabled = false;
  }
}

async function refreshCommandRuns() {
  const button = $("#refreshCommandRunsButton");
  if (button) button.disabled = true;
  try {
    const response = await window.galaxyssi.getCommandRuns(30);
    state.commandRuns = Array.isArray(response?.runs) ? response.runs : [];
    renderCommandRuns();
  } catch (error) {
    $("#commandRuns").textContent = `${t("Command runs unavailable")}: ${error.message || String(error)}`;
  } finally {
    if (button) button.disabled = false;
  }
}

async function executeCommandFromPanel() {
  const input = $("#commandInput");
  const approve = $("#commandApprove");
  const button = $("#executeCommandButton");
  const output = $("#commandResult");
  const value = input?.value.trim() || "";
  if (!value) return;
  if (button) button.disabled = true;
  if (output) output.textContent = t("Executing command...");
  try {
    const payload = value.startsWith("/")
      ? { slash: value, approve: Boolean(approve?.checked), source: "desktop" }
      : { command_id: value, args: {}, approve: Boolean(approve?.checked), source: "desktop" };
    const result = await window.galaxyssi.executeCommand(payload);
    if (output) output.textContent = JSON.stringify(result, null, 2);
    await refreshCommandRuns();
  } catch (error) {
    if (output) output.textContent = error.message || String(error);
  } finally {
    if (button) button.disabled = false;
  }
}

function parseProactiveTeam(value, actionKind = "subagent_team") {
  const team = String(value || "").split(/\r?\n/).map((line) => line.trim()).filter(Boolean).map((line) => {
    const separator = line.indexOf(":");
    if (separator < 1) throw new Error(t("Use role:agent-id for every team member."));
    const member = {
      role: line.slice(0, separator).trim().toLowerCase(),
      agent_id: line.slice(separator + 1).trim(),
      instructions: ""
    };
    if (!["lead", "coordinator", "executor", "specialist", "observer", "verifier"].includes(member.role) || !member.agent_id) {
      throw new Error(t("Use role:agent-id for every team member."));
    }
    return member;
  });
  if (actionKind === "headless_swarm" && !team.length) return [];
  const finalResponders = team.filter((member) => ["lead", "coordinator"].includes(member.role));
  if (finalResponders.length !== 1) {
    throw new Error(t("A team requires exactly one lead or coordinator."));
  }
  const coordinatorMode = finalResponders[0].role === "coordinator";
  if (actionKind === "headless_swarm" && (!coordinatorMode || !team.some((member) => member.role === "specialist"))) {
    throw new Error(t("A headless swarm requires one coordinator and at least one specialist."));
  }
  if (coordinatorMode && !team.some((member) => member.role === "specialist")) {
    throw new Error(t("A coordinator team requires at least one specialist."));
  }
  if (new Set(team.map((member) => member.agent_id)).size !== team.length) {
    throw new Error(t("Each team member must be unique."));
  }
  return team;
}

function proactiveTriggerPayload(kind, schedule, timeZone, name, existing = {}) {
  if (kind === "cron") return { kind, cron: schedule || "0 9 * * *", time_zone: timeZone || "UTC" };
  if (kind === "interval") {
    return { kind, interval_seconds: Math.max(60, Number(schedule || 3600)), time_zone: timeZone || "UTC" };
  }
  if (kind === "goal_checkpoint") {
    return {
      kind,
      interval_seconds: Math.max(60, Number(schedule || 3600)),
      time_zone: timeZone || "UTC",
      goal_id: existing.goal_id || `goal:${String(name || "task").toLowerCase().replace(/[^a-z0-9._-]+/g, "-").slice(0, 80) || Date.now()}`
    };
  }
  if (kind === "webhook") {
    return {
      kind,
      time_zone: timeZone || "UTC",
      ...(existing.webhook_id ? { webhook_id: existing.webhook_id } : {})
    };
  }
  return { kind: "manual", time_zone: timeZone || "UTC" };
}

function resetProactiveEditor() {
  state.editingProactiveTaskId = "";
  $("#proactiveName").value = "";
  $("#proactiveTriggerKind").value = "manual";
  $("#proactiveSchedule").value = "";
  $("#proactiveTimeZone").value = Intl.DateTimeFormat().resolvedOptions().timeZone || "UTC";
  $("#proactiveActionKind").value = "agent";
  $("#proactiveTarget").value = "codex";
  $("#proactivePrompt").value = "";
  $("#proactiveTeam").value = "";
  $("#proactiveArguments").value = "{}";
  $("#proactiveDelivery").value = "store";
  $("#proactiveNetwork").value = "any";
  $("#proactiveAttempts").value = "3";
  $("#proactiveConcurrency").value = "1";
  $("#proactiveCharging").checked = false;
  $("#cancelProactiveEditButton").hidden = true;
  $("#createProactiveButton").textContent = t("Create proactive task");
  syncProactiveFormVisibility();
}

function editProactiveTask(taskId) {
  const task = state.proactiveTasks.find((item) => item.task_id === taskId);
  if (!task) return;
  state.editingProactiveTaskId = taskId;
  $("#proactiveName").value = task.name || "";
  $("#proactiveTriggerKind").value = task.trigger?.kind || "manual";
  $("#proactiveSchedule").value = task.trigger?.kind === "cron"
    ? task.trigger.cron || ""
    : String(task.trigger?.interval_seconds || "");
  $("#proactiveTimeZone").value = task.trigger?.time_zone || "UTC";
  $("#proactiveActionKind").value = task.action?.kind || "agent";
  $("#proactiveTarget").value = task.action?.target_id || "";
  $("#proactivePrompt").value = task.action?.prompt || "";
  $("#proactiveTeam").value = (task.action?.team || [])
    .map((member) => `${member.role}:${member.agent_id}`)
    .join("\n");
  $("#proactiveArguments").value = JSON.stringify(task.action?.arguments || {}, null, 2);
  $("#proactiveDelivery").value = task.action?.delivery?.mode || "store";
  $("#proactiveNetwork").value = task.policy?.network || "any";
  $("#proactiveAttempts").value = String(task.policy?.max_attempts || 3);
  $("#proactiveConcurrency").value = String(task.policy?.max_concurrency || 1);
  $("#proactiveCharging").checked = Boolean(task.policy?.requires_charging);
  $("#cancelProactiveEditButton").hidden = false;
  $("#createProactiveButton").textContent = t("Update proactive task");
  $("#proactiveCreateDetails").open = true;
  syncProactiveFormVisibility();
  $("#proactiveCreateDetails").scrollIntoView({ behavior: "smooth", block: "nearest" });
}

function syncProactiveFormVisibility() {
  const trigger = $("#proactiveTriggerKind").value;
  const action = $("#proactiveActionKind").value;
  $("#proactiveScheduleField").hidden = !["cron", "interval", "goal_checkpoint"].includes(trigger);
  $("#proactiveTimeZoneField").hidden = trigger !== "cron";
  $("#proactiveTargetField").hidden = action === "subagent_team";
  $("#proactiveTeamField").hidden = !["headless_swarm", "subagent_team"].includes(action);
  $("#proactiveArgumentsField").hidden = !["headless_swarm", "native_tool"].includes(action);
  $("#proactivePromptField").hidden = action === "native_tool";
  $("#proactiveTarget").placeholder = action === "headless_swarm"
    ? "pr_review | test_repair | documentation_update"
    : "codex";
  $("#proactiveSchedule").placeholder = trigger === "cron" ? "0 9 * * *" : "3600";
}

async function createProactiveTask() {
  const button = $("#createProactiveButton");
  const name = $("#proactiveName").value.trim();
  const actionKind = $("#proactiveActionKind").value;
  if (!name) return showToast(t("Add a task name."));
  let argumentsValue;
  let team;
  try {
    argumentsValue = JSON.parse($("#proactiveArguments").value.trim() || "{}");
    team = ["headless_swarm", "subagent_team"].includes(actionKind)
      ? parseProactiveTeam($("#proactiveTeam").value, actionKind)
      : [];
  } catch (error) {
    return showToast(error.message || String(error));
  }
  const targetId = $("#proactiveTarget").value.trim();
  if (actionKind !== "subagent_team" && !targetId) return showToast(t("Add a target ID."));
  const prompt = $("#proactivePrompt").value.trim();
  if (["agent", "headless_swarm", "subagent_team"].includes(actionKind) && !prompt) {
    return showToast(t("Add a goal or instructions."));
  }
  const editingTask = state.proactiveTasks.find(
    (task) => task.task_id === state.editingProactiveTaskId
  );
  button.disabled = true;
  try {
    const payload = {
      name,
      trigger: proactiveTriggerPayload(
        $("#proactiveTriggerKind").value,
        $("#proactiveSchedule").value.trim(),
        $("#proactiveTimeZone").value.trim(),
        name,
        editingTask?.trigger
      ),
      action: {
        kind: actionKind,
        target_id: targetId,
        prompt,
        arguments: argumentsValue,
        team,
        delivery: { mode: $("#proactiveDelivery").value }
      },
      policy: {
        max_attempts: Math.max(1, Number($("#proactiveAttempts").value || 3)),
        max_concurrency: Math.max(1, Number($("#proactiveConcurrency").value || 1)),
        network: $("#proactiveNetwork").value,
        requires_charging: $("#proactiveCharging").checked
      },
      enabled: true
    };
    if (editingTask) {
      payload.enabled = Boolean(editingTask.enabled);
      await window.galaxyssi.updateProactiveTask(editingTask.task_id, payload);
    } else {
      await window.galaxyssi.createProactiveTask(payload);
    }
    const message = editingTask ? t("Proactive task updated.") : t("Proactive task created.");
    resetProactiveEditor();
    $("#proactiveCreateDetails").open = false;
    showToast(message);
    await refreshCapabilities();
  } catch (error) {
    showToast(error.message || String(error));
  } finally {
    button.disabled = false;
  }
}

async function handleProactiveAction(event) {
  const toggle = event.target.closest("[data-toggle-proactive]");
  const edit = event.target.closest("[data-edit-proactive]");
  const runs = event.target.closest("[data-runs-proactive]");
  const trigger = event.target.closest("[data-trigger-proactive]");
  const remove = event.target.closest("[data-delete-proactive]");
  const cancelRun = event.target.closest("[data-cancel-proactive-run]");
  const button = toggle || edit || runs || trigger || remove || cancelRun;
  if (!button) return;
  button.disabled = true;
  try {
    if (edit) {
      editProactiveTask(edit.dataset.editProactive);
      return;
    } else if (toggle) {
      await window.galaxyssi.updateProactiveTask(toggle.dataset.toggleProactive, {
        enabled: toggle.dataset.enabled !== "1"
      });
    } else if (runs) {
      state.selectedProactiveTaskId = runs.dataset.runsProactive;
      const response = await window.galaxyssi.listProactiveRuns(state.selectedProactiveTaskId, 100);
      state.proactiveRuns = Array.isArray(response.runs) ? response.runs : [];
      renderProactiveTasks();
      return;
    } else if (trigger) {
      await window.galaxyssi.triggerProactiveTask(trigger.dataset.triggerProactive);
    } else if (remove) {
      if (!window.confirm(t("Delete this proactive task and its run history?"))) return;
      await window.galaxyssi.deleteProactiveTask(remove.dataset.deleteProactive);
      if (state.selectedProactiveTaskId === remove.dataset.deleteProactive) {
        state.selectedProactiveTaskId = "";
        state.proactiveRuns = [];
      }
    } else if (cancelRun) {
      await window.galaxyssi.cancelProactiveRun(cancelRun.dataset.cancelProactiveRun);
    }
    await refreshCapabilities();
  } catch (error) {
    showToast(error.message || String(error));
  } finally {
    button.disabled = false;
  }
}

async function addMemory() {
  const content = $("#memoryContent").value.trim();
  if (!content) return;
  await window.galaxyssi.rememberDesktopMemory({ content, kind: "manual", importance: 0.8 });
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
  await window.galaxyssi.saveDesktopSkill(payload);
  for (const id of ["#skillId", "#skillName", "#skillTriggers", "#skillInstructions"]) $(id).value = "";
  showToast(t("Skill added."));
  await refreshCapabilities();
}

async function saveMcp() {
  const transport = $("#mcpTransport").value;
  const connectionId = $("#mcpId").value.trim().toLowerCase();
  const existing = state.mcp.find((connection) => connection.id === connectionId);
  const command = $("#mcpCommand").value.trim();
  const payload = {
    id: connectionId,
    name: $("#mcpName").value.trim(),
    transport,
    command,
    command_argv: existing?.command === command
      ? (existing.command_argv || [])
      : [],
    environment_env: parseMcpEnvironmentMapping($("#mcpProcessEnv").value),
    endpoint: $("#mcpEndpoint").value.trim(),
    working_directory: $("#mcpWorkingDirectory").value.trim(),
    header_env: parseMcpHeaderEnvironment($("#mcpHeaderEnv").value),
    header_templates: existing?.header_templates || {},
    protocol_version: $("#mcpProtocolVersion").value,
    stdio_framing: "newline",
    allow_insecure_http: $("#mcpAllowInsecureHttp").checked,
    default_tool: $("#mcpTool").value.trim(),
    triggers: parsePhrases($("#mcpTriggers").value),
    enabled: true,
    auto_invoke: $("#mcpAutoInvoke").checked,
    permission_mode: $("#mcpPermissionMode").value,
    timeout_seconds: Number($("#mcpTimeout").value || 20),
    import_source: existing?.import_source || ""
  };
  const targetComplete = transport === "local_stdio" ? payload.command : payload.endpoint;
  if (!payload.id || !payload.name || !targetComplete) {
    return showToast(t("Complete the MCP ID, name, and transport target."));
  }
  await window.galaxyssi.saveDesktopMcp(payload);
  resetMcpEditor();
  $("#mcpAutoInvoke").checked = false;
  $("#mcpPermissionMode").value = "ask_for_changes";
  showToast(t("MCP connection saved."));
  await refreshCapabilities();
}

function parseMcpHeaderEnvironment(value) {
  return parseMcpEnvironmentMapping(
    value,
    "Use one Header=ENVIRONMENT_VARIABLE mapping per line."
  );
}

function parseMcpEnvironmentMapping(
  value,
  errorMessage = "Use one CHILD_VARIABLE=HOST_ENVIRONMENT_VARIABLE mapping per line."
) {
  const result = {};
  String(value || "").split(/\r?\n/).map((line) => line.trim()).filter(Boolean).forEach((line) => {
    const separator = line.indexOf("=");
    if (separator <= 0 || separator === line.length - 1) {
      throw new Error(t(errorMessage));
    }
    result[line.slice(0, separator).trim()] = line.slice(separator + 1).trim();
  });
  return result;
}

function syncMcpTransportFields() {
  const remote = $("#mcpTransport").value === "streamable_http";
  $("#mcpCommandField").hidden = remote;
  $("#mcpWorkingDirectoryField").hidden = remote;
  $("#mcpProcessEnvField").hidden = remote;
  $("#mcpEndpointField").hidden = !remote;
  $("#mcpHeaderEnvField").hidden = !remote;
  $("#mcpInsecureHttpField").hidden = !remote;
}

function resetMcpEditor() {
  for (const id of [
    "#mcpId",
    "#mcpName",
    "#mcpCommand",
    "#mcpEndpoint",
    "#mcpWorkingDirectory",
    "#mcpProcessEnv",
    "#mcpHeaderEnv",
    "#mcpTool",
    "#mcpTriggers"
  ]) $(id).value = "";
  $("#mcpTransport").value = "local_stdio";
  $("#mcpProtocolVersion").value = "2025-11-25";
  $("#mcpTimeout").value = "20";
  $("#mcpAllowInsecureHttp").checked = false;
  $("#mcpAutoInvoke").checked = false;
  $("#mcpPermissionMode").value = "ask_for_changes";
  $("#mcpId").disabled = false;
  syncMcpTransportFields();
}

function editMcp(connection) {
  $("#mcpId").value = connection.id || "";
  $("#mcpId").disabled = true;
  $("#mcpName").value = connection.name || "";
  $("#mcpTransport").value = connection.transport || "local_stdio";
  $("#mcpCommand").value = connection.command || "";
  $("#mcpEndpoint").value = connection.endpoint || "";
  $("#mcpWorkingDirectory").value = connection.working_directory || "";
  $("#mcpProcessEnv").value = Object.entries(connection.environment_env || {})
    .map(([child, environment]) => `${child}=${environment}`)
    .join("\n");
  $("#mcpHeaderEnv").value = Object.entries(connection.header_env || {})
    .map(([header, environment]) => `${header}=${environment}`)
    .join("\n");
  $("#mcpProtocolVersion").value = connection.protocol_version || "2025-11-25";
  $("#mcpTool").value = connection.default_tool || "";
  $("#mcpTriggers").value = (connection.triggers || []).join(", ");
  $("#mcpTimeout").value = String(connection.timeout_seconds || 20);
  $("#mcpAllowInsecureHttp").checked = Boolean(connection.allow_insecure_http);
  $("#mcpAutoInvoke").checked = Boolean(connection.auto_invoke);
  $("#mcpPermissionMode").value = connection.permission_mode || "ask_for_changes";
  syncMcpTransportFields();
  $("#mcpEditor").open = true;
  $("#mcpEditor").scrollIntoView({ behavior: "smooth", block: "nearest" });
}

async function previewMcpImport(file, sourceHint = "auto") {
  if (!file?.content || !file?.fileName) return;
  const preview = await window.galaxyssi.previewDesktopMcpImport({
    content: file.content,
    file_name: file.fileName,
    base_directory: file.baseDirectory || "",
    source_hint: sourceHint
  });
  state.mcpImport = {
    ...state.mcpImport,
    fileName: file.fileName,
    baseDirectory: file.baseDirectory || "",
    content: file.content,
    sourceHint,
    preview
  };
  renderMcpImporter();
}

async function commitMcpImport() {
  const preview = state.mcpImport.preview;
  if (!preview) return;
  const selectedIds = $$("[data-mcp-import-id]:checked")
    .map((input) => input.dataset.mcpImportId)
    .filter(Boolean);
  if (!selectedIds.length) {
    return showToast(t("Select at least one MCP connection."));
  }
  const result = await window.galaxyssi.commitDesktopMcpImport({
    content: state.mcpImport.content,
    file_name: state.mcpImport.fileName,
    base_directory: state.mcpImport.baseDirectory,
    source_hint: state.mcpImport.sourceHint,
    digest: preview.digest,
    selected_ids: selectedIds
  });
  const imported = Array.isArray(result.imported) ? result.imported.length : 0;
  showToast(t("{count} MCP connections imported", { count: imported }));
  state.mcpImport = {
    ...state.mcpImport,
    fileName: "",
    baseDirectory: "",
    content: "",
    sourceHint: "auto",
    preview: null
  };
  await refreshCapabilities();
}

function selectCapabilityTab(name) {
  $$('[data-capability-tab]').forEach((button) => button.classList.toggle("active", button.dataset.capabilityTab === name));
  $$(".capability-pane").forEach((pane) => pane.classList.remove("active"));
  $(`#${name}Capability`)?.classList.add("active");
}

function selectMemoryView(name) {
  if (!["overview", "current", "planned", "inbox", "conflicts", "history"].includes(name)) return;
  state.memory.view = name;
  renderMemory();
}

async function runDiagnostics() {
  const output = $("#diagnosticsOutput");
  output.hidden = false;
  output.textContent = t("Running diagnostics...");
  try {
    const [runtime, agents, pairing, linkTransport] = await Promise.all([
      window.galaxyssi.getRuntimeDiagnostics(),
      window.galaxyssi.getAgentDiagnostics(),
      window.galaxyssi.getPairingStatus(),
      window.galaxyssi.getLinkTransportDiagnostics()
    ]);
    output.textContent = JSON.stringify({ runtime, agents, pairing, linkTransport }, null, 2);
  } catch (error) {
    output.textContent = error.message || String(error);
  }
}

function runtimeStatusLabel(status) {
  if (status === "ready") return "Ready";
  if (status === "partial") return "Partial";
  return "Missing";
}

function renderRuntimeManager() {
  const summary = state.runtime?.summary || {};
  const rows = Array.isArray(state.runtime?.runtimes) ? state.runtime.runtimes : [];
  const summaryNode = $("#runtimeManagerSummary");
  if (state.runtime?.error) {
    summaryNode.textContent = state.runtime.error;
  } else if (rows.length) {
    summaryNode.textContent = t("{ready} ready · {partial} partial · {missing} missing", {
      ready: Number(summary.ready || 0),
      partial: Number(summary.partial || 0),
      missing: Number(summary.missing || 0)
    });
  } else {
    summaryNode.textContent = t("Runtime inventory has not been checked.");
  }
  $("#runtimeManagerList").innerHTML = rows.length ? rows.map((runtime) => {
    const status = String(runtime.status || "missing");
    const detail = runtime.version
      || (runtime.missing_components || []).map((item) => t("Missing {component}", { component: item })).join(", ")
      || runtime.source
      || "";
    return `<article class="runtime-row">
      <div><strong>${escapeHtml(runtime.title || runtime.id)}</strong><small title="${escapeHtml(detail)}">${escapeHtml(detail)}</small></div>
      <span class="state-badge ${status === "ready" ? "ok" : status === "missing" ? "bad" : ""}">${escapeHtml(t(runtimeStatusLabel(status)))}</span>
    </article>`;
  }).join("") : "";
}

async function refreshRuntimeManager(refresh = false) {
  const button = $("#refreshRuntimeButton");
  button.disabled = true;
  try {
    const diagnostics = await window.galaxyssi.getRuntimeDiagnostics(refresh);
    state.runtime = diagnostics.managedRuntime || { summary: {}, runtimes: [], error: "" };
    renderRuntimeManager();
  } catch (error) {
    state.runtime = { summary: {}, runtimes: [], error: error.message || String(error) };
    renderRuntimeManager();
  } finally {
    button.disabled = false;
  }
}

const ACTIVE_EVOLUTION_STATES = new Set(["proposed", "preparing", "running", "validating", "publishing"]);

function evolutionStatusLabel(status) {
  const labels = {
    proposed: "Proposed",
    preparing: "Preparing",
    running: "Editing",
    validating: "Validating",
    waiting_approval: "Ready for review",
    publishing: "Publishing",
    published: "PR created",
    failed: "Failed",
    cancelled: "Cancelled",
    rolled_back: "Rolled back"
  };
  return t(labels[status] || status || "Proposed");
}

function parseEvolutionList(value) {
  return String(value || "")
    .split(/[\n,;]+/)
    .map((item) => item.trim())
    .filter(Boolean);
}

function evolutionGateSummary(task) {
  const attempt = Array.isArray(task.attempts) ? task.attempts.at(-1) : null;
  const gates = Array.isArray(attempt?.gates) ? attempt.gates : [];
  if (!gates.length) return t("Quality gates pending");
  const passed = gates.filter((gate) => gate.status === "passed").length;
  return t("{passed} of {total} quality gates passed", { passed, total: gates.length });
}

function renderEvolutionHealth() {
  const health = state.evolutionHealth && typeof state.evolutionHealth === "object"
    ? state.evolutionHealth
    : null;
  const element = $("#evolutionHealthSummary");
  if (!health || !Number(health.total_tasks || 0)) {
    element.hidden = true;
    element.innerHTML = "";
    return;
  }
  const decidedGates = Number(health.passed_gates || 0) + Number(health.failed_gates || 0);
  const statusCounts = health.status_counts && typeof health.status_counts === "object"
    ? health.status_counts
    : {};
  const decidedTasks = Number(health.successful_tasks || 0)
    + Number(statusCounts.failed || 0)
    + Number(statusCounts.blocked || 0);
  const metrics = [
    [decidedTasks ? `${Number(health.success_percent || 0)}%` : "--", t("Success")],
    [decidedGates ? `${Number(health.gate_pass_percent || 0)}%` : "--", t("Gate pass")],
    [Number(health.retries || 0), t("Retries")],
    [Number(health.attention_tasks || 0), t("Attention")]
  ];
  element.innerHTML = metrics.map(([value, label]) => `
    <span class="evolution-health-metric">
      <strong>${escapeHtml(value)}</strong>
      <small>${escapeHtml(label)}</small>
    </span>
  `).join("");
  element.classList.toggle("attention", Number(health.attention_tasks || 0) > 0);
  element.hidden = false;
}

function renderEvolutionTasks() {
  const tasks = Array.isArray(state.evolutionTasks) ? state.evolutionTasks : [];
  const active = tasks.filter((task) => ACTIVE_EVOLUTION_STATES.has(task.status)).length;
  const ready = tasks.filter((task) => task.status === "waiting_approval").length;
  const badge = $("#evolutionSummaryBadge");
  badge.textContent = tasks.length
    ? (ready ? t("{count} ready for review", { count: ready }) : active ? t("{count} active", { count: active }) : t("{count} candidates", { count: tasks.length }))
    : t("No candidates");
  badge.className = `state-badge ${ready ? "ok" : ""}`;
  renderEvolutionHealth();
  $("#evolutionTaskList").innerHTML = tasks.map((task) => {
    const status = String(task.status || "proposed");
    const candidate = String(task.candidate_commit || "");
    const error = String(task.last_error || "");
    const detail = error
      ? error
      : candidate
        ? `${t("Candidate")} ${candidate.slice(0, 10)}`
        : evolutionGateSummary(task);
    const actions = [];
    if (ACTIVE_EVOLUTION_STATES.has(status) && status !== "publishing") {
      actions.push(`<button class="secondary-button" data-cancel-evolution="${escapeHtml(task.task_id)}">${escapeHtml(t("Cancel"))}</button>`);
    }
    if (status === "waiting_approval") {
      actions.push(`<button class="secondary-button" data-rollback-evolution="${escapeHtml(task.task_id)}">${escapeHtml(t("Rollback"))}</button>`);
      actions.push(`<button class="primary-button" data-publish-evolution="${escapeHtml(task.task_id)}">${escapeHtml(t("Create PR"))}</button>`);
    }
    return `<article class="evolution-task-row" data-evolution-task="${escapeHtml(task.task_id)}">
      <div>
        <strong>${escapeHtml(task.problem || task.task_id)}</strong>
        <small><span class="evolution-gate-summary">${escapeHtml(evolutionStatusLabel(status))}</span> · ${escapeHtml(detail)}</small>
      </div>
      <div class="evolution-task-actions">${actions.join("")}</div>
    </article>`;
  }).join("");
}

async function refreshEvolutionTasks(showError = false) {
  try {
    const response = await window.galaxyssi.listEvolutionTasks(50);
    state.evolutionTasks = Array.isArray(response?.tasks) ? response.tasks : [];
    state.evolutionHealth = response?.health && typeof response.health === "object"
      ? response.health
      : null;
    renderEvolutionTasks();
  } catch (error) {
    if (showError) showToast(error.message || String(error));
  }
}

async function createEvolutionCandidate() {
  const button = $("#createEvolutionButton");
  const problem = $("#evolutionProblem").value.trim();
  const scope = parseEvolutionList($("#evolutionScope").value);
  const acceptance = parseEvolutionList($("#evolutionAcceptance").value);
  if (problem.length < 12 || !scope.length || !acceptance.length) {
    showToast(t("Add a clear problem, allowed source path, and acceptance criteria."));
    return;
  }
  button.disabled = true;
  try {
    await window.galaxyssi.createEvolutionTask({
      problem,
      scope,
      acceptance,
      riskLevel: $("#evolutionRisk").value,
      agentId: $("#evolutionAgent").value,
      maxAttempts: 3,
      start: true
    });
    $("#evolutionProblem").value = "";
    $("#evolutionAcceptance").value = "";
    $(".evolution-create").open = false;
    showToast(t("Isolated candidate started."));
    await refreshEvolutionTasks(true);
  } catch (error) {
    showToast(error.message || String(error));
  } finally {
    button.disabled = false;
  }
}

async function handleEvolutionAction(event) {
  const cancel = event.target.closest("[data-cancel-evolution]");
  const rollback = event.target.closest("[data-rollback-evolution]");
  const publish = event.target.closest("[data-publish-evolution]");
  const button = cancel || rollback || publish;
  if (!button) return;
  button.disabled = true;
  try {
    if (cancel) {
      await window.galaxyssi.cancelEvolutionTask(cancel.dataset.cancelEvolution);
    } else if (rollback) {
      if (!window.confirm(t("Discard this isolated candidate and its worktree?"))) return;
      await window.galaxyssi.rollbackEvolutionTask(rollback.dataset.rollbackEvolution);
    } else {
      const task = state.evolutionTasks.find((item) => item.task_id === publish.dataset.publishEvolution);
      if (!task?.approval_hash || !task?.candidate_commit) {
        showToast(t("Candidate identity is incomplete."));
        return;
      }
      const approved = window.confirm(t("Create a PR for candidate {commit}?\n\nApproval hash:\n{hash}", {
        commit: task.candidate_commit.slice(0, 12),
        hash: task.approval_hash
      }));
      if (!approved) return;
      const result = await window.galaxyssi.publishEvolutionTask(task.task_id, task.approval_hash);
      showToast(result.pull_request_url ? t("Pull request created.") : t("Candidate published."));
      if (result.pull_request_url) await window.galaxyssi.openExternal(result.pull_request_url);
    }
    await refreshEvolutionTasks(true);
  } catch (error) {
    showToast(error.message || String(error));
  } finally {
    button.disabled = false;
  }
}

const PANEL_META = {
  agents: ["Agents", "Private agents and local execution engines"],
  capabilities: ["Capabilities", "Memory, Skills, MCP, and proactive automation"],
  commands: ["Commands", "Deterministic local command catalog"],
  gateway: ["Mobile Gateway", "Trusted phones and GalaxySSI Link"],
  settings: ["Settings", "Language, cloud API, commands, and diagnostics"]
};
let panelOpenSequence = 0;

async function openPanel(name) {
  const panelName = Object.hasOwn(PANEL_META, name) ? name : "settings";
  const sequence = ++panelOpenSequence;
  const meta = PANEL_META[panelName];
  elements.drawerTitle.textContent = t(meta[0]);
  elements.drawerSubtitle.textContent = t(meta[1]);
  $$(".drawer-panel").forEach((panel) => panel.classList.remove("active"));
  $(`#${panelName}Panel`)?.classList.add("active");
  elements.backdrop.hidden = false;
  elements.drawer.classList.add("open");
  elements.drawer.setAttribute("aria-hidden", "false");
  elements.drawer.dataset.panelLoading = "true";
  delete elements.drawer.dataset.panelReady;
  try {
    if (panelName === "agents") {
      await Promise.all([refreshAgents(), refreshAgentPerformance()]);
    }
    if (panelName === "gateway") {
      await Promise.all([refreshGateway(), refreshDesktopControl()]);
      if ($("#blobSettingsSection")?.open) await window.GalaxySSIBlobSettings?.refresh();
      if (!(state.pairing?.client_count > 0)) $("#pairingDetails").open = true;
      await loadPairingFrame();
    }
    if (panelName === "capabilities") await refreshCapabilities();
    if (panelName === "commands") await Promise.all([refreshCommands(), refreshCommandRuns()]);
    if (panelName === "settings") {
      await Promise.all([
        refreshBackend(),
        refreshAgents(),
        refreshRuntimeManager(false),
        refreshEvolutionTasks(false),
        refreshAgentMemoryTelemetry()
      ]);
    }
  } finally {
    if (sequence === panelOpenSequence) {
      elements.drawer.dataset.panelLoading = "false";
      elements.drawer.dataset.panelReady = panelName;
    }
  }
}

function closePanel() {
  window.GalaxySSIBlobSettings?.clearSensitive();
  elements.drawer.classList.remove("open");
  elements.drawer.setAttribute("aria-hidden", "true");
  window.setTimeout(() => { elements.backdrop.hidden = true; }, 180);
}

function latestTask() {
  return conversationTasks().at(-1) || null;
}

async function cancelTask(taskId) {
  const task = state.tasks.find((item) => item.task_id === taskId);
  if (!task) {
    showToast(t("No task is currently running."));
    return;
  }
  await window.galaxyssi.cancelDesktopTask(task.task_id);
  $("#workspaceMenu").hidden = true;
  await refreshTasks(true);
}

async function cancelRunningTask() {
  const task = [...conversationTasks()].reverse().find((item) => !TERMINAL_STATES.has(item.status));
  await cancelTask(task?.task_id);
}

async function controlTask(taskId, action) {
  const methods = {
    pause: window.galaxyssi.pauseDesktopTask,
    takeover: window.galaxyssi.takeOverDesktopTask,
    continue: window.galaxyssi.continueDesktopTask
  };
  const method = methods[action];
  if (typeof method !== "function") return;
  try {
    const response = await method(taskId, {
      reason: action === "pause" ? "Paused from GalaxySSI Desktop" : "",
      leaseSeconds: 900
    });
    const task = response?.task;
    if (task?.task_id) mergeTaskUpdate(task);
    state.renderingSignature = "";
    renderHistory();
    renderConversation(true);
  } catch (error) {
    showToast(`${t("Could not update task")}: ${error.message || error}`);
  }
}

async function retryTask(taskId) {
  return recoverTask(taskId, "retry");
}

async function recoverTask(taskId, action, agentId = "") {
  try {
    const response = await window.galaxyssi.recoverDesktopTask(taskId, action, agentId);
    if (response?.diagnostic) {
      state.recoveryDiagnostics[taskId] = response.diagnostic;
      state.renderingSignature = "";
      renderConversation(true);
      return;
    }
    const task = response?.task || response;
    if (!task?.task_id) throw new Error(t("Recovery did not start a task."));
    if (state.tasks.some((item) => item.task_id === task.task_id)) {
      mergeTaskUpdate(task);
    } else {
      state.tasks.push(task);
    }
    state.renderingSignature = "";
    renderHistory();
    renderConversation(true);
  } catch (error) {
    showToast(`${t("Could not continue task")}: ${error.message || error}`);
  }
}

async function revealWorkspace() {
  const task = latestTask();
  if (!task) return showToast(t("This conversation has no task workspace yet."));
  try { await window.galaxyssi.revealTaskWorkspace(task.task_id); }
  catch (error) { showToast(error.message || String(error)); }
  $("#workspaceMenu").hidden = true;
}

async function deleteConversation() {
  if (!conversationTasks().length) return newTask();
  if (!window.confirm(t("Delete this conversation and its task history?"))) return;
  await window.galaxyssi.deleteDesktopConversation(state.currentConversationId);
  newTask();
  await refreshTasks(true);
  $("#workspaceMenu").hidden = true;
}

function setConversationSelectionMode(enabled) {
  state.conversationSelectionMode = Boolean(enabled);
  state.selectedConversationIds.clear();
  state.openConversationMenuId = "";
  $("#conversationListMenu").hidden = true;
  renderHistory();
}

async function deleteConversationIds(conversationIds) {
  const ids = [...new Set(conversationIds)].filter(Boolean);
  if (!ids.length) return;
  const groupsById = new Map(unifiedConversationGroups().map((group) => [group.id, group]));
  state.deletingConversationIds = new Set(ids);
  renderConversationSelectionBar();
  const results = await Promise.allSettled(ids.map(async (id) => {
    const group = groupsById.get(id);
    if (!group) throw new Error(`Conversation is no longer available: ${id}`);
    if (group.kind === "evolution") {
      state.hiddenEvolutionConversationIds.add(id);
      return { kind: group.kind, hidden: true };
    }
    const response = group.kind === "device"
      ? await window.galaxyssi.deletePeerConversation(id)
      : await window.galaxyssi.deleteDesktopConversation(id);
    const deletedCount = group.kind === "device"
      ? Number(response?.deleted_messages || 0)
      : Array.isArray(response?.deleted_task_ids) ? response.deleted_task_ids.length : 0;
    if (deletedCount <= 0) throw new Error(`Conversation was not deleted: ${id}`);
    return { kind: group.kind, deletedCount };
  }));
  const failedIds = [];
  ids.forEach((id, index) => {
    if (results[index]?.status !== "fulfilled") {
      failedIds.push(id);
      return;
    }
    state.pinnedConversationIds.delete(id);
    if (groupsById.get(id)?.kind === "device") {
      state.peerMessages = state.peerMessages.filter((message) => message.client_route_id !== id);
      if (state.activePeerRouteId === id) {
        state.activePeerRouteId = "";
        document.querySelector("#agentApp").classList.remove("peer-mode");
        newTask();
      }
    }
  });
  persistPinnedConversations();
  persistHiddenEvolutionConversations();
  if (ids.includes(state.currentConversationId)) newTask();
  state.selectedConversationIds = new Set(failedIds);
  state.deletingConversationIds.clear();
  if (failedIds.length) {
    state.conversationSelectionMode = true;
    renderHistory();
    showToast(t("Some conversations could not be deleted."));
  } else {
    setConversationSelectionMode(false);
  }
  await Promise.all([refreshTasks(true), refreshPeerMessages()]);
}

function updatePeerVoiceHoldUi(cancelPending = state.peerVoiceCancelPending) {
  const overlay = $("#peerVoiceHoldOverlay");
  state.peerVoiceCancelPending = Boolean(cancelPending);
  overlay.classList.toggle("cancel-pending", state.peerVoiceCancelPending);
  $("#peerVoiceHoldHint").textContent = t(
    state.peerVoiceCancelPending ? "Release to cancel" : "Release to send · Swipe up to cancel"
  );
}

function showPeerVoiceHoldUi() {
  $("#peerVoiceHoldOverlay").hidden = false;
  $("#agentApp").classList.add("recording-peer-voice");
  $("#voiceButton").classList.add("active");
  $("#voiceButton").setAttribute("aria-pressed", "true");
  $("#peerVoiceHoldTimer").textContent = "00:00";
  updatePeerVoiceHoldUi(false);
}

function hidePeerVoiceHoldUi() {
  window.clearInterval(state.peerVoiceTimer);
  state.peerVoiceTimer = 0;
  $("#peerVoiceHoldOverlay").hidden = true;
  $("#peerVoiceHoldOverlay").classList.remove("cancel-pending");
  $("#agentApp").classList.remove("recording-peer-voice");
  $("#voiceButton").classList.remove("active");
  $("#voiceButton").setAttribute("aria-pressed", "false");
}

function resetPeerVoiceCaptureState() {
  state.peerVoiceStarting = false;
  state.peerVoiceHolding = false;
  state.peerVoicePointerId = null;
  state.peerVoicePressStartY = null;
  state.peerVoiceCancelPending = false;
  state.peerVoiceStartedAtMs = 0;
  state.peerVoiceRouteId = "";
  hidePeerVoiceHoldUi();
}

async function beginPeerVoiceHold(pointerId = null, startY = null) {
  if (!state.activePeerRouteId || state.peerSendPending || state.peerVoiceStarting || state.peerVoiceRecorder) return;
  state.peerVoiceStarting = true;
  state.peerVoiceHolding = true;
  state.peerVoicePointerId = pointerId;
  state.peerVoicePressStartY = Number.isFinite(startY) ? startY : null;
  state.peerVoiceCancelled = false;
  state.peerVoiceRouteId = state.activePeerRouteId;
  showPeerVoiceHoldUi();
  try {
    const speakerPlaybackActive = Boolean(window.speechSynthesis?.speaking)
      || Array.from(document.querySelectorAll("audio,video"))
        .some((element) => !element.paused && !element.ended);
    const stream = await navigator.mediaDevices.getUserMedia({
      audio: {
        sampleRate: 48_000,
        channelCount: 1,
        noiseSuppression: true,
        echoCancellation: speakerPlaybackActive,
        autoGainControl: false
      }
    });
    if (!state.peerVoiceHolding || !state.activePeerRouteId) {
      stream.getTracks().forEach((track) => track.stop());
      resetPeerVoiceCaptureState();
      return;
    }
    state.peerVoiceStream = stream;
    const candidates = ["audio/ogg;codecs=opus", "audio/webm;codecs=opus", "audio/webm"];
    const mimeType = candidates.find((value) => MediaRecorder.isTypeSupported(value)) || "";
    const recorder = new MediaRecorder(
      stream,
      { ...(mimeType ? { mimeType } : {}), audioBitsPerSecond: 48_000 }
    );
    state.peerVoiceRecorder = recorder;
    state.peerVoiceChunks = [];
    state.peerVoiceCancelled = false;
    state.peerVoiceStartedAtMs = Date.now();
    state.peerVoiceStarting = false;
    recorder.addEventListener("dataavailable", (event) => {
      if (event.data?.size) state.peerVoiceChunks.push(event.data);
    });
    recorder.addEventListener("stop", async () => {
      const chunks = state.peerVoiceChunks.splice(0);
      const cancelled = state.peerVoiceCancelled;
      const cancelPending = state.peerVoiceCancelPending;
      const durationMillis = Math.max(0, Date.now() - state.peerVoiceStartedAtMs);
      const completion = window.galaxyssiPeerHoldToTalk.completion({
        durationMs: durationMillis,
        sendRequested: !cancelled,
        cancelPending
      });
      const routeId = state.peerVoiceRouteId;
      state.peerVoiceRecorder = null;
      state.peerVoiceStream = null;
      stream.getTracks().forEach((track) => track.stop());
      resetPeerVoiceCaptureState();
      if (!completion.send || !chunks.length) {
        if (completion.reason === "too_short") showToast(t("Voice message is too short"));
        else if (cancelPending) showToast(t("Recording cancelled"));
        return;
      }
      state.peerSendPending = true;
      updateSendState();
      let audio;
      try {
        const blob = new Blob(chunks, { type: recorder.mimeType || mimeType || "audio/webm" });
        audio = new Uint8Array(await blob.arrayBuffer());
        const result = await window.galaxyssi.sendPeerVoice({
          clientRouteId: routeId,
          mimeType: blob.type,
          audio,
          durationMillis
        });
        if (result.message) {
          const index = state.peerMessages.findIndex((item) => item.message_id === result.message.message_id);
          if (index >= 0) state.peerMessages[index] = result.message;
          else state.peerMessages.push(result.message);
        }
        renderHistory();
        renderPeerConversation(true);
      } catch (error) {
        showToast(`${t("Voice input failed")}: ${error.message || error}`);
      } finally {
        audio?.fill(0);
        state.peerSendPending = false;
        updateSendState();
      }
    }, { once: true });
    recorder.start(250);
    state.peerVoiceTimer = window.setInterval(() => {
      const elapsed = Math.max(0, Date.now() - state.peerVoiceStartedAtMs);
      $("#peerVoiceHoldTimer").textContent = window.galaxyssiPeerHoldToTalk.formatElapsed(elapsed);
      if (elapsed >= window.galaxyssiPeerHoldToTalk.MAX_DURATION_MS) finishPeerVoiceHold(true);
    }, 200);
  } catch (error) {
    state.peerVoiceRecorder = null;
    state.peerVoiceStream?.getTracks().forEach((track) => track.stop());
    state.peerVoiceStream = null;
    state.peerVoiceChunks = [];
    resetPeerVoiceCaptureState();
    showToast(`${t("Voice input failed")}: ${error.message || error}`);
  }
}

function updatePeerVoiceHoldPointer(currentY) {
  if (!state.peerVoiceHolding) return;
  const cancelPending = window.galaxyssiPeerHoldToTalk.isCancelPending(
    state.peerVoicePressStartY,
    currentY
  );
  if (cancelPending !== state.peerVoiceCancelPending) updatePeerVoiceHoldUi(cancelPending);
}

function finishPeerVoiceHold(sendRequested) {
  if (!state.peerVoiceHolding && !state.peerVoiceStarting && !state.peerVoiceRecorder) return;
  state.peerVoiceHolding = false;
  state.peerVoiceCancelled = !sendRequested || state.peerVoiceCancelPending;
  hidePeerVoiceHoldUi();
  if (state.peerVoiceRecorder?.state === "recording") state.peerVoiceRecorder.stop();
}

function startVoiceInput() {
  if (state.activePeerRouteId) return;
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
  recognition.lang = resolveLanguagePolicy(
    state.agentConfig?.language_policy?.asr_language || "auto"
  );
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

function clearTaskSpeechPlayback() {
  state.taskSpeechRequestId += 1;
  const playback = state.taskSpeechPlayback;
  if (!playback) return;
  state.taskSpeechPlayback = null;
  playback.audio.pause();
  playback.audio.removeAttribute("src");
  playback.audio.load();
  URL.revokeObjectURL(playback.objectUrl);
}

function decodeBase64Bytes(encoded) {
  const binary = atob(String(encoded || ""));
  const bytes = new Uint8Array(binary.length);
  for (let index = 0; index < binary.length; index += 1) bytes[index] = binary.charCodeAt(index);
  return bytes;
}

async function speakTaskResult(taskId) {
  const task = state.tasks.find((item) => item.task_id === taskId);
  const expanded = task ? expandedTaskOutput(task) : null;
  const text = String(
    expanded?.done ? expanded.chunks.join("") : (task?.result || "")
  ).trim();
  if (!text || !window.galaxyssi?.synthesizeSpeech) {
    showToast(t("Text-to-speech is not available on this desktop."));
    return;
  }
  const container = document.createElement("div");
  container.innerHTML = renderMarkdown(text);
  clearTaskSpeechPlayback();
  const requestId = state.taskSpeechRequestId;
  try {
    const result = await window.galaxyssi.synthesizeSpeech({
      text: container.textContent || text,
      language: resolveLanguagePolicy(state.agentConfig?.language_policy?.tts_language || "auto")
    });
    if (requestId !== state.taskSpeechRequestId) return;
    const bytes = decodeBase64Bytes(result?.audioBase64);
    if (!bytes.byteLength) throw new Error(t("Text-to-speech returned no audio."));
    const blob = new Blob([bytes], { type: result.mimeType || "audio/mpeg" });
    const objectUrl = URL.createObjectURL(blob);
    const audio = new Audio(objectUrl);
    state.taskSpeechPlayback = { taskId, audio, objectUrl };
    const cleanup = () => {
      if (state.taskSpeechPlayback?.audio === audio) clearTaskSpeechPlayback();
    };
    audio.addEventListener("ended", cleanup, { once: true });
    audio.addEventListener("error", cleanup, { once: true });
    await audio.play();
  } catch (error) {
    if (requestId === state.taskSpeechRequestId) {
      showToast(`${t("Text-to-speech failed")}: ${error.message || error}`);
    }
  }
}

async function sha256Text(value) {
  const encoded = new TextEncoder().encode(value);
  const digest = await crypto.subtle.digest("SHA-256", encoded);
  return Array.from(new Uint8Array(digest))
    .map((byte) => byte.toString(16).padStart(2, "0"))
    .join("");
}

function textCharacterCount(value) {
  return Array.from(String(value || "")).length;
}

function trimExpandedTaskOutputs() {
  while (state.expandedTaskOutputs.size > 8) {
    const oldest = state.expandedTaskOutputs.keys().next().value;
    state.expandedTaskOutputs.delete(oldest);
  }
}

async function loadFullTaskOutput(taskId, button) {
  const task = state.tasks.find((item) => item.task_id === taskId);
  if (!task?.result_chunked) return;
  let cached = expandedTaskOutput(task);
  if (!cached) {
    cached = {
      sha256: String(task.result_sha256 || ""),
      chunks: [],
      loading: false,
      done: false,
      error: ""
    };
    state.expandedTaskOutputs.set(taskId, cached);
    trimExpandedTaskOutputs();
  }
  if (cached.loading || cached.done) return;
  cached.loading = true;
  cached.error = "";
  button.disabled = true;
  button.textContent = t("Loading full output");
  const output = elements.messages.querySelector(
    `[data-output-chunks="${CSS.escape(taskId)}"]`
  );
  let stateLabel = elements.messages.querySelector(
    `[data-output-load-state="${CSS.escape(taskId)}"]`
  );
  if (!stateLabel) {
    stateLabel = document.createElement("small");
    stateLabel.className = "output-load-state";
    stateLabel.dataset.outputLoadState = taskId;
    button.insertAdjacentElement("afterend", stateLabel);
  }
  if (output && cached.chunks.length === 0) output.innerHTML = "";
  try {
    while (!cached.done) {
      const page = await window.galaxyssi.getDesktopTaskOutput(
        taskId,
        cached.chunks.length,
        2
      );
      const chunks = Array.isArray(page.chunks) ? page.chunks : [];
      if (!chunks.length && !page.done) throw new Error(t("Output stream stopped"));
      for (const chunk of chunks) {
        const content = String(chunk.content || "");
        cached.chunks.push(content);
        output?.insertAdjacentHTML(
          "beforeend",
          `<section class="assistant-output-chunk">${renderMarkdown(content)}</section>`
        );
      }
      cached.done = Boolean(page.done);
      if (stateLabel) {
        const loaded = cached.chunks.reduce(
          (total, chunk) => total + textCharacterCount(chunk),
          0
        );
        stateLabel.textContent = `${loaded} / ${Number(page.total_length || task.result_length || 0)}`;
      }
      await new Promise((resolve) => requestAnimationFrame(resolve));
    }
    const complete = cached.chunks.join("");
    if (
      textCharacterCount(complete) !== Number(task.result_length || 0)
      || await sha256Text(complete) !== String(task.result_sha256 || "")
    ) {
      throw new Error(t("Output integrity check failed"));
    }
    button.remove();
    stateLabel?.remove();
  } catch (error) {
    cached.error = error.message || String(error);
    cached.done = false;
    button.disabled = false;
    button.textContent = t("Retry full output");
    if (stateLabel) stateLabel.textContent = cached.error;
    else showToast(cached.error);
  } finally {
    cached.loading = false;
  }
}

function bindEvents() {
  $("#newTaskButton").addEventListener("click", () => newTask());
  $("#closePeerImageViewerButton").addEventListener("click", closePeerImageViewer);
  $("#savePeerImageButton").addEventListener("click", saveViewedPeerImage);
  $("#peerImageViewer").addEventListener("click", (event) => {
    if (event.target === event.currentTarget) closePeerImageViewer();
  });
  document.addEventListener("keydown", (event) => {
    if (event.key === "Escape" && !$("#peerImageViewer").hidden) closePeerImageViewer();
  });
  $("#attachButton").addEventListener("click", addAttachments);
  const voiceButton = $("#voiceButton");
  voiceButton.addEventListener("click", (event) => {
    if (state.activePeerRouteId) {
      event.preventDefault();
      return;
    }
    startVoiceInput();
  });
  voiceButton.addEventListener("pointerdown", (event) => {
    if (!state.activePeerRouteId || event.button !== 0 || !event.isPrimary) return;
    event.preventDefault();
    voiceButton.setPointerCapture(event.pointerId);
    beginPeerVoiceHold(event.pointerId, event.clientY);
  });
  voiceButton.addEventListener("pointermove", (event) => {
    if (event.pointerId === state.peerVoicePointerId) updatePeerVoiceHoldPointer(event.clientY);
  });
  voiceButton.addEventListener("pointerup", (event) => {
    if (event.pointerId !== state.peerVoicePointerId) return;
    event.preventDefault();
    finishPeerVoiceHold(true);
  });
  voiceButton.addEventListener("pointercancel", (event) => {
    if (event.pointerId === state.peerVoicePointerId) finishPeerVoiceHold(false);
  });
  voiceButton.addEventListener("keydown", (event) => {
    if (!state.activePeerRouteId || event.repeat || ![" ", "Enter"].includes(event.key)) return;
    event.preventDefault();
    beginPeerVoiceHold();
  });
  voiceButton.addEventListener("keyup", (event) => {
    if (!state.activePeerRouteId || ![" ", "Enter"].includes(event.key)) return;
    event.preventDefault();
    finishPeerVoiceHold(true);
  });
  $("#agentPickerButton").addEventListener("click", () => openPanel("agents"));
  $("#sendButton").addEventListener("click", sendTask);
  $("#autoModeButton").addEventListener("click", () => { state.selectedAgentId = "auto"; state.selectedAgentName = t("Agent"); updateSelectedAgent(); });
  $("#localModeButton").addEventListener("click", () => { state.selectedAgentId = "desktop"; state.selectedAgentName = t("This desktop"); updateSelectedAgent(); });
  $("#executionModeButton").addEventListener("click", () => {
    state.executionMode = state.executionMode === "plan_only" ? "auto_complete" : "plan_only";
    localStorage.setItem("galaxyssi-desktop-execution-mode", state.executionMode);
    updateExecutionMode();
  });
  $("#taskBudgetProfileSelect").addEventListener("change", (event) => {
    selectTaskBudgetProfile(event.target.value);
  });
  $("#taskBudgetSettingsProfile").addEventListener("change", (event) => {
    selectTaskBudgetProfile(event.target.value);
  });
  [
    "#taskBudgetTime",
    "#taskBudgetCost",
    "#taskBudgetInputTokens",
    "#taskBudgetOutputTokens",
    "#taskBudgetNetwork",
    "#taskBudgetMemory",
    "#taskBudgetBattery",
    "#taskBudgetNetworkPolicy",
    "#taskBudgetAllowCloud",
    "#taskBudgetAllowPaid"
  ].forEach((selector) => $(selector).addEventListener("input", markTaskBudgetCustom));
  $("#saveTaskBudgetButton").addEventListener("click", () => {
    persistTaskBudget(readTaskBudgetSettings());
  });
  elements.prompt.addEventListener("input", updateSendState);
  elements.prompt.addEventListener("paste", pasteAttachments);
  elements.prompt.addEventListener("keydown", (event) => {
    if (event.key === "Enter" && !event.shiftKey && !event.isComposing) {
      event.preventDefault();
      sendTask();
    }
  });
  elements.history.addEventListener("click", (event) => {
    const selectButton = event.target.closest("[data-select-conversation]");
    if (selectButton) {
      const id = selectButton.dataset.selectConversation;
      if (state.selectedConversationIds.has(id)) state.selectedConversationIds.delete(id);
      else state.selectedConversationIds.add(id);
      renderHistory();
      return;
    }
    const menuButton = event.target.closest("[data-conversation-menu]");
    if (menuButton) {
      const id = menuButton.dataset.conversationMenu;
      state.openConversationMenuId = state.openConversationMenuId === id ? "" : id;
      renderHistory();
      return;
    }
    const pinButton = event.target.closest("[data-pin-conversation]");
    if (pinButton) {
      const id = pinButton.dataset.pinConversation;
      if (state.pinnedConversationIds.has(id)) state.pinnedConversationIds.delete(id);
      else state.pinnedConversationIds.add(id);
      persistPinnedConversations();
      state.openConversationMenuId = "";
      renderHistory();
      return;
    }
    const deleteButton = event.target.closest("[data-delete-conversation]");
    if (deleteButton) {
      const id = deleteButton.dataset.deleteConversation;
      if (window.confirm(t("Delete this conversation? The contact or paired device will remain."))) deleteConversationIds([id]);
      return;
    }
    const peer = event.target.closest("[data-peer-route]");
    if (peer) {
      openPeerConversation(peer.dataset.peerRoute);
      return;
    }
    const item = event.target.closest("[data-conversation-id]");
    if (!item) return;
    state.activePeerRouteId = "";
    document.querySelector("#agentApp").classList.remove("peer-mode");
    state.currentConversationId = item.dataset.conversationId;
    state.emptyConversationIntent = false;
    state.renderingSignature = "";
    renderHistory();
    renderConversation(true);
  });
  elements.messages.addEventListener("click", async (event) => {
    const peerVoice = event.target.closest("[data-play-peer-voice]");
    if (peerVoice) {
      try {
        await togglePeerVoicePlayback(peerVoice);
      } catch (error) {
        showToast(error.message || String(error));
      }
      return;
    }
    const pause = event.target.closest("[data-pause-task]");
    if (pause) {
      await controlTask(pause.dataset.pauseTask, "pause");
      return;
    }
    const takeover = event.target.closest("[data-takeover-task]");
    if (takeover) {
      await controlTask(takeover.dataset.takeoverTask, "takeover");
      return;
    }
    const resume = event.target.closest("[data-continue-task]");
    if (resume) {
      await controlTask(resume.dataset.continueTask, "continue");
      return;
    }
    const cancel = event.target.closest("[data-cancel-task]");
    if (cancel) {
      await cancelTask(cancel.dataset.cancelTask);
      return;
    }
    const fullOutput = event.target.closest("[data-load-task-output]");
    if (fullOutput) {
      await loadFullTaskOutput(fullOutput.dataset.loadTaskOutput, fullOutput);
      return;
    }
    const speak = event.target.closest("[data-speak-task]");
    if (speak) {
      await speakTaskResult(speak.dataset.speakTask);
      return;
    }
    const retry = event.target.closest("[data-retry-task]");
    if (retry) {
      await retryTask(retry.dataset.retryTask);
      return;
    }
    const recovery = event.target.closest("[data-recovery-task]");
    if (recovery) {
      await recoverTask(
        recovery.dataset.recoveryTask,
        recovery.dataset.recoveryAction,
        recovery.dataset.recoveryAgent || ""
      );
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
      try { await window.galaxyssi.openTaskArtifact(artifact.dataset.taskId, artifact.dataset.openArtifact); }
      catch (error) { showToast(error.message || String(error)); }
    }
    const peerImage = event.target.closest("[data-view-peer-image]");
    if (peerImage) {
      try {
        await openPeerImageViewer(peerImage);
      } catch (error) {
        showToast(error.message || String(error));
      }
      return;
    }
    const peerAttachment = event.target.closest("[data-open-peer-attachment]");
    if (peerAttachment) {
      try {
        await window.galaxyssi.openPeerAttachment(
          peerAttachment.dataset.peerMessageId,
          Number(peerAttachment.dataset.openPeerAttachment)
        );
      } catch (error) {
        showToast(error.message || String(error));
      }
    }
    const link = event.target.closest("[data-external-link]");
    if (link) {
      event.preventDefault();
      await window.galaxyssi.openExternal(link.dataset.externalLink);
    }
  });
  elements.attachments.addEventListener("click", (event) => {
    const button = event.target.closest("[data-remove-attachment]");
    if (!button) return;
    const [removed] = state.attachments.splice(Number(button.dataset.removeAttachment), 1);
    if (removed) {
      state.attachmentDetails.delete(removed);
      releaseStagedAttachments([removed]);
    }
    renderAttachmentTray();
  });
  $$('[data-open-panel]').forEach((button) => button.addEventListener("click", () => {
    $("#workspaceMenu").hidden = true;
    openPanel(button.dataset.openPanel);
  }));
  $("#closeDrawer").addEventListener("click", closePanel);
  elements.backdrop.addEventListener("click", closePanel);
  $("#refreshAgentsButton").addEventListener("click", () =>
    Promise.all([refreshAgents(), refreshAgentPerformance()]));
  $("#refreshAgentPerformanceButton").addEventListener("click", () =>
    refreshAgentPerformance());
  $$("[data-performance-window]").forEach((button) => {
    button.addEventListener("click", () =>
      refreshAgentPerformance(button.dataset.performanceWindow));
  });
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
  $("#saveAcpRuntimeButton").addEventListener("click", () => saveAcpRuntimeSettings().catch((error) => showToast(error.message || String(error))));
  $("#refreshAcpRuntimeButton").addEventListener("click", () => refreshAcpRuntime().catch((error) => showToast(error.message || String(error))));
  $("#acpRuntimeList").addEventListener("click", (event) => {
    const action = event.target.closest("[data-acp-action]");
    if (!action) return;
    const row = action.closest("[data-acp-agent]");
    runAcpRuntimeAction(row.dataset.acpAgent, action.dataset.acpAction)
      .catch((error) => showToast(error.message || String(error)));
  });
  $("#cloudProvider").addEventListener("change", applyCloudProviderPreset);
  $("#saveCloudModelButton").addEventListener("click", () => saveCloudModelSettings(false));
  $("#testCloudModelButton").addEventListener("click", () => saveCloudModelSettings(true));
  $("#saveWebSearchButton").addEventListener("click", saveWebSearchSettings);
  $("#refreshGatewayButton").addEventListener("click", async () => {
    $("#pairingFrame").removeAttribute("src");
    await Promise.all([refreshGateway(), refreshDesktopControl()]);
    await loadPairingFrame(true);
  });
  $("#pairDeviceButton").addEventListener("click", async () => {
    const details = $("#pairingDetails");
    details.open = true;
    await loadPairingFrame(true);
    details.scrollIntoView({ behavior: "smooth", block: "start" });
  });
  $("#pairingDesktopExecutorEnabled").addEventListener("change", async (event) => {
    state.pairingGrantDesktopExecutor = Boolean(event.target.checked);
    $("#pairingFrame").removeAttribute("src");
    try {
      await loadPairingFrame();
      await refreshDesktopControl();
    } catch (error) {
      showToast(error.message || String(error));
    }
  });
  $("#pairedClientList").addEventListener("click", async (event) => {
    const chatButton = event.target.closest("[data-chat-client]");
    if (chatButton) {
      closePanel();
      openPeerConversation(chatButton.dataset.chatClient);
      return;
    }
    const renameButton = event.target.closest("[data-rename-client]");
    if (renameButton) {
      const value = window.prompt(t("Device name"), renameButton.dataset.clientName || "");
      if (value == null || !value.trim()) return;
      await window.galaxyssi.renamePairedClient(renameButton.dataset.renameClient, value.trim());
      await refreshGateway();
      return;
    }
    const revokeButton = event.target.closest("[data-revoke-client]");
    if (!revokeButton || !window.confirm(t("Revoke this phone? It must scan the QR code again."))) return;
    const revokedRouteId = revokeButton.dataset.revokeClient;
    state.pairing = await window.galaxyssi.clearPairing(revokedRouteId);
    state.peerMessages = state.peerMessages.filter((message) => message.client_route_id !== revokedRouteId);
    if (state.activePeerRouteId === revokedRouteId) {
      state.activePeerRouteId = "";
      document.querySelector("#agentApp").classList.remove("peer-mode");
      state.renderingSignature = "";
      renderConversation(true);
    }
    renderGateway();
    renderHistory();
    await refreshDesktopControl();
  });
  $("#authorizedAppList").addEventListener("click", async (event) => {
    const button = event.target.closest("[data-revoke-authorization]");
    if (!button || !window.confirm(t("Revoke execution access for this app?"))) return;
    await window.galaxyssi.revokeDesktopAuthorization(button.dataset.revokeAuthorization);
    await Promise.all([refreshDesktopControl(), refreshGateway()]);
    showToast(t("App authorization revoked."));
  });
  $$('[data-capability-tab]').forEach((button) => button.addEventListener("click", () => selectCapabilityTab(button.dataset.capabilityTab)));
  $$("[data-marketplace-kind]").forEach((button) => {
    button.addEventListener("click", () => {
      state.marketplace.kind = button.dataset.marketplaceKind || "";
      renderMarketplace();
    });
  });
  $("#refreshMarketplaceButton").addEventListener("click", () =>
    refreshCapabilities().catch((error) => showToast(error.message || String(error))));
  $("#marketplaceList").addEventListener("click", async (event) => {
    const install = event.target.closest("[data-install-marketplace]");
    const setup = event.target.closest("[data-setup-marketplace]");
    const revoke = event.target.closest("[data-revoke-marketplace]");
    const rollback = event.target.closest("[data-rollback-marketplace]");
    const uninstall = event.target.closest("[data-uninstall-marketplace]");
    if (install) {
      try {
        const item = state.marketplace.items.find(
          (value) => value.id === install.dataset.installMarketplace
        ) || {};
        const added = Array.isArray(item.permission_diff?.added)
          ? item.permission_diff.added
          : [];
        if (added.length) {
          const permissionList = added.map(
            (permission) => `\u2022 ${permission.title || permission.id}`
          ).join("\n");
          if (!window.confirm(`${t("This release requests new permissions:")}\n\n${permissionList}\n\n${t("Continue?")}`)) {
            return;
          }
        }
        await window.galaxyssi.installToolMarketplaceItem(
          install.dataset.installMarketplace,
          {},
          added.map((permission) => permission.id)
        );
        showToast(t("Marketplace item installed."));
        await refreshCapabilities();
      } catch (error) {
        showToast(error.message || String(error));
      }
      return;
    }
    if (revoke) {
      if (!window.confirm(t("Revoke this item's access while keeping its configuration?"))) return;
      await window.galaxyssi.revokeToolMarketplaceItem(revoke.dataset.revokeMarketplace);
      showToast(t("Marketplace access revoked."));
      await refreshCapabilities();
      return;
    }
    if (rollback) {
      if (!window.confirm(t("Restore the previous verified version?"))) return;
      await window.galaxyssi.rollbackToolMarketplaceItem(rollback.dataset.rollbackMarketplace);
      showToast(t("Previous marketplace version restored."));
      await refreshCapabilities();
      return;
    }
    if (uninstall) {
      if (!window.confirm(t("Uninstall this item? A verified rollback snapshot will be retained."))) return;
      await window.galaxyssi.uninstallToolMarketplaceItem(uninstall.dataset.uninstallMarketplace);
      showToast(t("Marketplace item uninstalled."));
      await refreshCapabilities();
      return;
    }
    if (setup) {
      selectCapabilityTab(setup.dataset.marketplaceItemKind === "mcp" ? "mcp" : "automation");
      if (setup.dataset.marketplaceItemKind === "mcp") {
        $("#mcpEditor").open = true;
      }
    }
  });
  $$("[data-memory-view]").forEach((button) => {
    button.addEventListener("click", () => selectMemoryView(button.dataset.memoryView));
  });
  $("#refreshMemoryButton").addEventListener("click", () => refreshMemory($("#memorySearch").value.trim()));
  let memorySearchTimer = 0;
  $("#memorySearch").addEventListener("input", () => {
    window.clearTimeout(memorySearchTimer);
    memorySearchTimer = window.setTimeout(() => refreshMemory($("#memorySearch").value.trim()), 240);
  });
  $("#addMemoryButton").addEventListener("click", () => addMemory().catch((error) => showToast(error.message || String(error))));
  $("#memoryList").addEventListener("click", async (event) => {
    const visualizationView = event.target.closest("[data-memory-visualization-view]");
    const graphNode = event.target.closest("[data-memory-graph-node]");
    const evidenceChain = event.target.closest("[data-memory-evidence-chain]");
    const runCritic = event.target.closest("[data-run-memory-critic]");
    const overviewRoute = event.target.closest("[data-memory-overview-route]");
    const forget = event.target.closest("[data-forget-memory]");
    const approve = event.target.closest("[data-approve-memory-candidate]");
    const reject = event.target.closest("[data-reject-memory-candidate]");
    if (visualizationView) {
      state.memory.visualizationView = visualizationView.dataset.memoryVisualizationView;
      renderMemory();
      return;
    }
    if (graphNode) {
      state.memory.selectedGraphNodeId = graphNode.dataset.memoryGraphNode;
      renderMemory();
      return;
    }
    if (evidenceChain) {
      state.memory.selectedEvidenceChainId = evidenceChain.dataset.memoryEvidenceChain;
      renderMemory();
      return;
    }
    if (runCritic) {
      const result = await window.galaxyssi.runDesktopMemoryCritic();
      showToast(t("Memory audit completed with {count} safe actions.", {
        count: Number(result?.run?.action_count || 0)
      }));
      await refreshMemory($("#memorySearch").value.trim());
      return;
    }
    if (overviewRoute) {
      selectMemoryView(overviewRoute.dataset.memoryOverviewRoute);
      return;
    }
    if (forget) {
      await window.galaxyssi.forgetDesktopMemory(forget.dataset.forgetMemory);
    } else if (approve) {
      await window.galaxyssi.reviewDesktopMemoryCandidate(
        approve.dataset.approveMemoryCandidate,
        "approve"
      );
      showToast(t("Memory candidate approved."));
    } else if (reject) {
      await window.galaxyssi.reviewDesktopMemoryCandidate(
        reject.dataset.rejectMemoryCandidate,
        "reject"
      );
      showToast(t("Memory candidate rejected."));
    } else {
      return;
    }
    await refreshMemory($("#memorySearch").value.trim());
  });
  $("#memoryList").addEventListener("keydown", (event) => {
    if (!["Enter", " "].includes(event.key)) return;
    const graphNode = event.target.closest("[data-memory-graph-node]");
    if (!graphNode) return;
    event.preventDefault();
    state.memory.selectedGraphNodeId = graphNode.dataset.memoryGraphNode;
    renderMemory();
  });
  $("#saveSkillButton").addEventListener("click", () => saveSkill().catch((error) => showToast(error.message || String(error))));
  $("#skillList").addEventListener("click", async (event) => {
    const toggle = event.target.closest("[data-toggle-skill]");
    const remove = event.target.closest("[data-delete-skill]");
    if (toggle) await window.galaxyssi.setDesktopSkillEnabled(toggle.dataset.toggleSkill, toggle.dataset.enabled !== "1");
    if (remove && window.confirm(t("Delete this skill?"))) await window.galaxyssi.deleteDesktopSkill(remove.dataset.deleteSkill);
    if (toggle || remove) await refreshCapabilities();
  });
  $("#chooseMcpImportButton").addEventListener("click", async () => {
    try {
      const file = await window.galaxyssi.chooseMcpConfig();
      if (file) await previewMcpImport(file, "auto");
    } catch (error) {
      showToast(error.message || String(error));
    }
  });
  $("#mcpDiscoveredSources").addEventListener("click", async (event) => {
    const source = event.target.closest("[data-mcp-import-path]");
    if (!source) return;
    try {
      const file = await window.galaxyssi.readDiscoveredMcpConfig(
        source.dataset.mcpImportPath
      );
      await previewMcpImport(file, source.dataset.mcpImportHint || "auto");
    } catch (error) {
      showToast(error.message || String(error));
    }
  });
  $("#commitMcpImportButton").addEventListener("click", () =>
    commitMcpImport().catch((error) => showToast(error.message || String(error))));
  $("#saveMcpButton").addEventListener("click", () => saveMcp().catch((error) => showToast(error.message || String(error))));
  $("#mcpTransport").addEventListener("change", syncMcpTransportFields);
  $("#mcpList").addEventListener("click", async (event) => {
    const probe = event.target.closest("[data-probe-mcp]");
    const chat = event.target.closest("[data-chat-mcp]");
    const edit = event.target.closest("[data-edit-mcp]");
    const remove = event.target.closest("[data-delete-mcp]");
    if (probe) {
      const result = await window.galaxyssi.probeDesktopMcp(probe.dataset.probeMcp);
      const names = (result.tools || []).map((tool) => tool.name).join(", ");
      showToast(result.status === "ready" ? `${t("MCP ready")}: ${names}` : `${t("MCP failed")}: ${result.error || ""}`);
    }
    if (chat) {
      const connection = state.mcp.find((item) => item.id === chat.dataset.chatMcp);
      newTask(`mcp:${chat.dataset.chatMcp}`, connection?.name || chat.dataset.chatMcp);
      closePanel();
    }
    if (edit) {
      const connection = state.mcp.find((item) => item.id === edit.dataset.editMcp);
      if (connection) editMcp(connection);
    }
    if (remove && window.confirm(t("Delete this MCP connection?"))) {
      await window.galaxyssi.deleteDesktopMcp(remove.dataset.deleteMcp);
      resetMcpEditor();
      await refreshCapabilities();
    }
  });
  $("#mcpList").addEventListener("change", async (event) => {
    const select = event.target.closest("[data-mcp-permission]");
    if (!select) return;
    const connection = state.mcp.find((item) => item.id === select.dataset.mcpPermission);
    if (!connection) return;
    await window.galaxyssi.saveDesktopMcp({
      ...connection,
      permission_mode: select.value
    });
    showToast(t("MCP permission policy updated."));
    await refreshCapabilities();
  });
  $("#refreshProactiveButton").addEventListener("click", () =>
    refreshCapabilities().catch((error) => showToast(error.message || String(error))));
  $("#createProactiveButton").addEventListener("click", createProactiveTask);
  $("#cancelProactiveEditButton").addEventListener("click", () => {
    resetProactiveEditor();
    $("#proactiveCreateDetails").open = false;
  });
  $("#proactiveTriggerKind").addEventListener("change", syncProactiveFormVisibility);
  $("#proactiveActionKind").addEventListener("change", syncProactiveFormVisibility);
  $("#proactiveTaskList").addEventListener("click", handleProactiveAction);
  $("#proactiveRunList").addEventListener("click", handleProactiveAction);
  $("#runDiagnosticsButton").addEventListener("click", runDiagnostics);
  $("#refreshAgentMemoryButton").addEventListener("click", refreshAgentMemoryTelemetry);
  $("#refreshRuntimeButton").addEventListener("click", () => refreshRuntimeManager(true));
  $("#refreshCommandsButton").addEventListener("click", refreshCommands);
  $("#refreshCommandRunsButton").addEventListener("click", refreshCommandRuns);
  $("#executeCommandButton").addEventListener("click", executeCommandFromPanel);
  $("#commandRootFilter").addEventListener("keydown", (event) => {
    if (event.key === "Enter") refreshCommands();
  });
  $("#commandInput").addEventListener("keydown", (event) => {
    if (event.key === "Enter") executeCommandFromPanel();
  });
  $("#commandCatalog").addEventListener("click", (event) => {
    const row = event.target.closest("[data-command-id]");
    if (!row) return;
    $("#commandInput").value = row.dataset.commandId || "";
    $("#commandResult").textContent = JSON.stringify({ command_id: row.dataset.commandId || "" }, null, 2);
  });
  $("#createEvolutionButton").addEventListener("click", createEvolutionCandidate);
  $("#evolutionTaskList").addEventListener("click", handleEvolutionAction);
  $("#languageSelect").addEventListener("change", (event) => setLanguage(event.target.value));
  $("#fontScaleSelect").addEventListener("change", (event) => setFontScale(event.target.value));
  $("#responseLanguageSelect").addEventListener("change", () => saveLanguagePolicySettings().catch((error) => showToast(error.message || String(error))));
  $("#asrLanguageSelect").addEventListener("change", () => saveLanguagePolicySettings().catch((error) => showToast(error.message || String(error))));
  $("#ttsLanguageSelect").addEventListener("change", () => saveLanguagePolicySettings().catch((error) => showToast(error.message || String(error))));
  $("#workspaceMenuButton").addEventListener("click", () => { $("#workspaceMenu").hidden = !$("#workspaceMenu").hidden; });
  $("#conversationListMenuButton").addEventListener("click", () => {
    $("#conversationListMenu").hidden = !$("#conversationListMenu").hidden;
  });
  $("#selectConversationsButton").addEventListener("click", () => setConversationSelectionMode(true));
  $("#cancelConversationSelectionButton").addEventListener("click", () => setConversationSelectionMode(false));
  $("#selectAllConversationsButton").addEventListener("click", () => {
    const ids = unifiedConversationGroups().map((group) => group.id);
    const allSelected = ids.length > 0 && ids.every((id) => state.selectedConversationIds.has(id));
    state.selectedConversationIds = new Set(allSelected ? [] : ids);
    renderHistory();
  });
  $("#deleteSelectedConversationsButton").addEventListener("click", async () => {
    if (!state.selectedConversationIds.size || state.conversationDeletionPromise) return;
    if (window.confirm(t("Delete the selected conversations? Contacts and paired devices will remain."))) {
      const operation = deleteConversationIds([...state.selectedConversationIds]);
      state.conversationDeletionPromise = operation;
      try {
        await operation;
      } finally {
        if (state.conversationDeletionPromise === operation) state.conversationDeletionPromise = null;
      }
    }
  });
  $("#cancelRunningTask").addEventListener("click", cancelRunningTask);
  $("#revealWorkspaceButton").addEventListener("click", revealWorkspace);
  $("#deleteConversationButton").addEventListener("click", deleteConversation);
  document.addEventListener("click", (event) => {
    if (!event.target.closest("#workspaceMenu") && !event.target.closest("#workspaceMenuButton")) $("#workspaceMenu").hidden = true;
    if (!event.target.closest("#conversationListMenu") && !event.target.closest("#conversationListMenuButton")) $("#conversationListMenu").hidden = true;
    if (!event.target.closest(".history-item-menu") && !event.target.closest("[data-conversation-menu]")) {
      if (state.openConversationMenuId) {
        state.openConversationMenuId = "";
        renderHistory();
      }
    }
  });
}

async function init() {
  bindEvents();
  window.galaxyssi.onSensitiveStateClear?.(clearPeerRuntimePlaintext);
  window.galaxyssi.onSensitiveStateResume?.(() => refreshPeerMessages());
  setFontScale(state.fontScale, false);
  const appVersion = await window.galaxyssi.getAppVersion();
  elements.desktopVersion.textContent = `v${appVersion}`;
  await setLanguage(state.languagePreference, false);
  resetProactiveEditor();
  renderAgentContacts();
  updateAgentCounters();
  updateSelectedAgent();
  updateExecutionMode();
  fillTaskBudgetSettings();
  updateSendState();
  await refreshBackend();
  await Promise.all([
    refreshAgents(),
    refreshGateway(),
    refreshDesktopControl(),
    refreshCapabilities(),
    refreshTasks(true),
    refreshPeerMessages()
  ]);
  connectTaskStream();
  window.setInterval(updateElapsedLabels, 1000);
  window.setInterval(() => {
    if (!state.taskStreamConnected) {
      refreshTasks(false);
      refreshPeerMessages();
    }
  }, 10_000);
  window.setInterval(() => { refreshBackend(); refreshGateway(); }, 30_000);
  window.setInterval(() => {
    if (elements.drawer.classList.contains("open") && $("#gatewayPanel").classList.contains("active")) {
      refreshDesktopControl();
      if ($("#pairingDetails").open) refreshGateway();
    }
    if (elements.drawer.classList.contains("open") && $("#settingsPanel").classList.contains("active")
        && state.evolutionTasks.some((task) => ACTIVE_EVOLUTION_STATES.has(task.status))) {
      refreshEvolutionTasks(false);
    }
  }, 2_000);
  window.galaxyssiConnecting?.finish();
}

init().catch((error) => {
  window.galaxyssiConnecting?.finish();
  showToast(error.stack || error.message || String(error));
});
