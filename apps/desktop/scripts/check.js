const fs = require("node:fs");
const path = require("node:path");

const root = path.resolve(__dirname, "..");
const workspaceRoot = path.resolve(root, "..");
const backendDir = path.join(root, "core", "signalasi-link", "backend");
const required = [
  "package.json",
  "src/main.js",
  "src/preload.js",
  "src/renderer/index.html",
  "src/renderer/renderer.js",
  "src/renderer/workspace.js",
  "src/renderer/locales/zh-CN.json",
  "src/renderer/locales/en.json",
  "src/renderer/styles.css",
  "core/signalasi-link/backend/desktop_control.py",
  "core/signalasi-link/backend/desktop_super_agent.py",
  "core/signalasi-link/backend/desktop_memory.py",
  "core/signalasi-link/backend/desktop_mcp.py",
  "core/signalasi-link/backend/desktop_skills.py",
  "scripts/package-win.js",
  "scripts/android-adb.js",
  "scripts/smoke.js",
  "scripts/smoke-pairing.js",
  "scripts/smoke-ui.js",
  "scripts/smoke-android-ui.js",
  "scripts/smoke-android-friends.js",
  "scripts/smoke-android-contact-rename.js",
  "scripts/smoke-android-contact-tags.js",
  "scripts/smoke-android-language.js",
  "scripts/smoke-android-cloud-models.js",
  "scripts/smoke-android-background-message.js",
  "scripts/smoke-android-agent-replies.js",
  "scripts/smoke-android-backup-roundtrip.js",
  "scripts/smoke-android-voice-reply.js",
  "scripts/smoke-android-voice-settings.js",
  "scripts/smoke-android-reset.js",
  "scripts/smoke-mqtt-persistence.js",
  "scripts/smoke-agent-push.js",
  "scripts/smoke-voice-stt.js",
  "scripts/smoke-e2e.js",
  "scripts/smoke-packaged.js",
  "scripts/smoke-lock.js",
  "scripts/connector-status.js",
  "docs/CONNECTOR_STATUS.md"
];

for (const file of required) {
  const full = path.join(root, file);
  if (!fs.existsSync(full)) {
    throw new Error(`Missing ${file}`);
  }
}

function listFilesRecursive(dir) {
  const result = [];
  for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
    const full = path.join(dir, entry.name);
    if (entry.isDirectory()) {
      result.push(...listFilesRecursive(full));
    } else {
      result.push(full);
    }
  }
  return result;
}

const main = fs.readFileSync(path.join(root, "src/main.js"), "utf8");
const preload = fs.readFileSync(path.join(root, "src/preload.js"), "utf8");
const html = fs.readFileSync(path.join(root, "src/renderer/index.html"), "utf8");
const renderer = fs.readFileSync(path.join(root, "src/renderer/renderer.js"), "utf8");
const workspaceRenderer = fs.readFileSync(path.join(root, "src/renderer/workspace.js"), "utf8");
const styles = fs.readFileSync(path.join(root, "src/renderer/styles.css"), "utf8");
const localeZh = JSON.parse(fs.readFileSync(path.join(root, "src", "renderer", "locales", "zh-CN.json"), "utf8"));
const localeEn = JSON.parse(fs.readFileSync(path.join(root, "src", "renderer", "locales", "en.json"), "utf8"));
const packageJson = fs.readFileSync(path.join(root, "package.json"), "utf8");
const packager = fs.readFileSync(path.join(root, "scripts/package-win.js"), "utf8");
const androidAdb = fs.readFileSync(path.join(root, "scripts/android-adb.js"), "utf8");
const smoke = fs.readFileSync(path.join(root, "scripts/smoke.js"), "utf8");
const smokePairing = fs.readFileSync(path.join(root, "scripts/smoke-pairing.js"), "utf8");
const smokeUi = fs.readFileSync(path.join(root, "scripts/smoke-ui.js"), "utf8");
const smokeAndroidUi = fs.readFileSync(path.join(root, "scripts/smoke-android-ui.js"), "utf8");
const smokeAndroidFriends = fs.readFileSync(path.join(root, "scripts/smoke-android-friends.js"), "utf8");
const smokeAndroidContactRename = fs.readFileSync(path.join(root, "scripts/smoke-android-contact-rename.js"), "utf8");
const smokeAndroidContactTags = fs.readFileSync(path.join(root, "scripts/smoke-android-contact-tags.js"), "utf8");
const smokeAndroidLanguage = fs.readFileSync(path.join(root, "scripts/smoke-android-language.js"), "utf8");
const smokeAndroidCloudModels = fs.readFileSync(path.join(root, "scripts/smoke-android-cloud-models.js"), "utf8");
const smokeAndroidBackground = fs.readFileSync(path.join(root, "scripts/smoke-android-background-message.js"), "utf8");
const smokeAndroidAgentReplies = fs.readFileSync(path.join(root, "scripts/smoke-android-agent-replies.js"), "utf8");
const smokeAndroidBackup = fs.readFileSync(path.join(root, "scripts/smoke-android-backup-roundtrip.js"), "utf8");
const smokeAndroidVoiceReply = fs.readFileSync(path.join(root, "scripts/smoke-android-voice-reply.js"), "utf8");
const smokeAndroidVoiceSettings = fs.readFileSync(path.join(root, "scripts/smoke-android-voice-settings.js"), "utf8");
const smokeAndroidReset = fs.readFileSync(path.join(root, "scripts/smoke-android-reset.js"), "utf8");
const smokeMqttPersistence = fs.readFileSync(path.join(root, "scripts/smoke-mqtt-persistence.js"), "utf8");
const smokeAgentPush = fs.readFileSync(path.join(root, "scripts/smoke-agent-push.js"), "utf8");
const smokeAgentLifecycle = fs.readFileSync(path.join(root, "scripts/smoke-agent-lifecycle.py"), "utf8");
const smokeVoiceStt = fs.readFileSync(path.join(root, "scripts/smoke-voice-stt.js"), "utf8");
const smokeE2e = fs.readFileSync(path.join(root, "scripts/smoke-e2e.js"), "utf8");
const smokePackaged = fs.readFileSync(path.join(root, "scripts/smoke-packaged.js"), "utf8");
const smokeLock = fs.readFileSync(path.join(root, "scripts/smoke-lock.js"), "utf8");
const connectorStatus = fs.readFileSync(path.join(root, "scripts/connector-status.js"), "utf8");
const statusDoc = fs.readFileSync(path.join(root, "docs/CONNECTOR_STATUS.md"), "utf8");
const backendMain = fs.readFileSync(path.join(backendDir, "main.py"), "utf8");
const backendModels = fs.readFileSync(path.join(backendDir, "models.py"), "utf8");
const backendMqtt = fs.readFileSync(path.join(backendDir, "mqtt_bridge.py"), "utf8");
const backendPairing = fs.readFileSync(path.join(backendDir, "pairing_state.py"), "utf8");
const backendLinkProtocol = fs.readFileSync(path.join(backendDir, "link_protocol.py"), "utf8");
const backendLinkDelivery = fs.readFileSync(path.join(backendDir, "link_delivery.py"), "utf8");
const backendSignalClient = fs.readFileSync(path.join(backendDir, "signalasi_client.py"), "utf8");
const backendGateway = fs.readFileSync(path.join(backendDir, "agent_gateway.py"), "utf8");
const backendTaskManager = fs.readFileSync(path.join(backendDir, "agent_task_manager.py"), "utf8");
const backendAgentConfig = fs.readFileSync(path.join(backendDir, "agent_config.py"), "utf8");
const backendCustomAgent = fs.readFileSync(path.join(backendDir, "custom_agent_stdio.py"), "utf8");
const backendDesktopFileTools = fs.readFileSync(path.join(backendDir, "desktop_file_tools.py"), "utf8");
const backendDesktopControl = fs.readFileSync(path.join(backendDir, "desktop_control.py"), "utf8");
const backendDesktopNativeTools = fs.readFileSync(path.join(backendDir, "desktop_native_tools.py"), "utf8");
const backendDesktopMemory = fs.readFileSync(path.join(backendDir, "desktop_memory.py"), "utf8");
const backendDesktopMcp = fs.readFileSync(path.join(backendDir, "desktop_mcp.py"), "utf8");
const backendDesktopSkills = fs.readFileSync(path.join(backendDir, "desktop_skills.py"), "utf8");
const backendDesktopSuperAgent = fs.readFileSync(path.join(backendDir, "desktop_super_agent.py"), "utf8");
const backendMcpWrapper = fs.readFileSync(path.join(backendDir, "mcp_agent_wrapper.py"), "utf8");
const backendTaskWorkspace = fs.readFileSync(path.join(backendDir, "task_workspace.py"), "utf8");
const backendPushAuth = fs.readFileSync(path.join(backendDir, "push_auth.py"), "utf8");
const backendSignalasiNotify = fs.readFileSync(path.join(backendDir, "signalasi_notify.py"), "utf8");
const backendApiResponse = fs.readFileSync(path.join(backendDir, "api_response.py"), "utf8");
const backendStt = fs.readFileSync(path.join(backendDir, "stt_bridge.py"), "utf8");
const sidecarDir = path.join(backendDir, "signal_sidecar");
const sidecarSourceDir = path.join(sidecarDir, "src", "main", "java");
const sidecarMainSource = fs.readFileSync(path.join(sidecarSourceDir, "com", "signalasi", "link", "SignalSidecar.java"), "utf8");
const sidecarBuildGradle = fs.readFileSync(path.join(sidecarDir, "build.gradle.kts"), "utf8");
const sidecarSettingsGradle = fs.readFileSync(path.join(sidecarDir, "settings.gradle.kts"), "utf8");
const androidMainActivity = fs.readFileSync(path.join(workspaceRoot, "android", "app", "src", "main", "java", "com", "signalasi", "chat", "MainActivity.kt"), "utf8");
const androidMessageService = fs.readFileSync(path.join(workspaceRoot, "android", "app", "src", "main", "java", "com", "signalasi", "chat", "MessageService.kt"), "utf8");
const androidChatHistoryStore = fs.readFileSync(path.join(workspaceRoot, "android", "app", "src", "main", "java", "com", "signalasi", "chat", "ChatHistoryStore.kt"), "utf8");
const androidChatHistoryDatabase = fs.readFileSync(path.join(workspaceRoot, "android", "app", "src", "main", "java", "com", "signalasi", "chat", "ChatHistoryDatabase.kt"), "utf8");
const androidDebugChatHistoryProbe = fs.readFileSync(path.join(workspaceRoot, "android", "app", "src", "main", "java", "com", "signalasi", "chat", "DebugChatHistoryProbe.kt"), "utf8");
const androidChatHistoryProbeScript = fs.readFileSync(path.join(__dirname, "android-chat-history-probe.js"), "utf8");
const androidSignalStore = fs.readFileSync(path.join(workspaceRoot, "android", "app", "src", "main", "java", "com", "signalasi", "chat", "AndroidPersistentSignalStore.kt"), "utf8");
const androidForegroundTracker = fs.readFileSync(path.join(workspaceRoot, "android", "app", "src", "main", "java", "com", "signalasi", "chat", "AppForegroundTracker.kt"), "utf8");
const androidAppStore = fs.readFileSync(path.join(workspaceRoot, "android", "app", "src", "main", "java", "com", "signalasi", "chat", "AppStore.kt"), "utf8");
const androidCrypto = fs.readFileSync(path.join(workspaceRoot, "android", "app", "src", "main", "java", "com", "signalasi", "chat", "SignalASICrypto.kt"), "utf8");
const androidMqtt = fs.readFileSync(path.join(workspaceRoot, "android", "app", "src", "main", "java", "com", "signalasi", "chat", "SignalASIMqttClient.kt"), "utf8");
const androidLinkProtocol = fs.readFileSync(path.join(workspaceRoot, "android", "app", "src", "main", "java", "com", "signalasi", "chat", "SignalASILinkProtocol.kt"), "utf8");
const androidLinkDelivery = fs.readFileSync(path.join(workspaceRoot, "android", "app", "src", "main", "java", "com", "signalasi", "chat", "SignalASILinkDeliveryStore.kt"), "utf8");
const androidVoiceSettings = fs.readFileSync(path.join(workspaceRoot, "android", "app", "src", "main", "java", "com", "signalasi", "chat", "VoiceAssistantSettings.kt"), "utf8");
const androidLocalWhisper = fs.readFileSync(path.join(workspaceRoot, "android", "app", "src", "main", "java", "com", "signalasi", "chat", "LocalWhisperAsr.kt"), "utf8");
const androidWhisperModels = fs.readFileSync(path.join(workspaceRoot, "android", "app", "src", "main", "java", "com", "signalasi", "chat", "WhisperModelManager.kt"), "utf8");
const androidCloudModelClient = fs.readFileSync(path.join(workspaceRoot, "android", "app", "src", "main", "java", "com", "signalasi", "chat", "CloudModelClient.kt"), "utf8");
const androidStringsZh = fs.readFileSync(path.join(workspaceRoot, "android", "app", "src", "main", "res", "values-zh-rCN", "strings.xml"), "utf8");
const androidStringsEn = fs.readFileSync(path.join(workspaceRoot, "android", "app", "src", "main", "res", "values", "strings.xml"), "utf8");
const androidSourceRoot = path.join(workspaceRoot, "android", "app", "src", "main");

if (!styles.includes(".utility-drawer.open") ||
    !styles.includes("box-shadow: none; pointer-events: none") ||
    !styles.includes("pointer-events: auto")) {
  throw new Error("Closed utility drawers must not cast a shadow or intercept pointer input");
}
if (!main.includes("width: 960") || !main.includes("height: 640")) {
  throw new Error("Desktop window must default to 960 x 640");
}
if (main.includes("minWidth:") || main.includes("minHeight:")) {
  throw new Error("Desktop window must not impose a minimum size");
}

for (const resource of ["colors.xml", "styles.xml"]) {
  const localizedResource = path.join(androidSourceRoot, "res", "values-zh-rCN", resource);
  if (fs.existsSync(localizedResource)) {
    throw new Error(`${resource} must not be localized; locale-specific copies override Android night resources`);
  }
}

if (!smokeAndroidReset.includes("SIGNALASI_ALLOW_DESTRUCTIVE_RESET")) {
  throw new Error("Android destructive reset smoke must require an explicit disposable-device opt-in");
}

for (const [name, source, forbidden] of [
  ["Agent config", backendAgentConfig, "LEGACY_CONFIG_PATH"],
  ["Desktop database", backendModels, "Path(__file__).with_name(\"signalasi.db\")"],
  ["Desktop STT", backendStt, "HERMESCHAT_WHISPER_"],
  ["Windows package", packager, "signalasi_agents.json"],
  ["Desktop smoke", smoke, "signalasi_agents.json"],
  ["Desktop e2e", smokeE2e, "signalasi_agents.json"]
]) {
  if (source.includes(forbidden)) {
    throw new Error(`${name} must not fall back to source-tree or Hermes-era data`);
  }
}

for (const marker of [
  "signalasi-packaged-smoke-",
  "SIGNALASI_STATE_DIR",
  "SIGNALASI_DATABASE_PATH",
  "SIGNALASI_CONFIG_PATH",
  "SIGNALASI_DISABLE_EXTERNAL_SERVICES"
]) {
  if (!smokePackaged.includes(marker)) {
    throw new Error(`Packaged smoke must isolate current runtime state: ${marker}`);
  }
}

for (const [name, source] of [
  ["contact tags", smokeAndroidContactTags],
  ["friends", smokeAndroidFriends],
  ["voice settings", smokeAndroidVoiceSettings]
]) {
  if (source.includes("hermes_app_store.xml")) {
    throw new Error(`Android ${name} smoke must use only the current SignalASI app store`);
  }
}

if (!main.includes("/signalasi/verify")) {
  throw new Error("Electron desktop must use /signalasi/verify");
}

const oldRoutes = ["/signal/verify", "/signalagi/verify"];
if (oldRoutes.some((route) => main.includes(route) || html.includes(route))) {
  throw new Error("Old pairing routes must not be used by desktop");
}

if (!backendSignalClient.includes('"type": "signalasi_verify"')) {
  throw new Error("Pairing QR payload must use signalasi_verify");
}

if (!backendMqtt.includes("decrypt_pairing_claim") || !backendLinkProtocol.includes('"signalasi_pairing_ciphertext"')) {
  throw new Error("MQTT pairing must use the encrypted Link v1 bootstrap");
}

for (const source of [backendMqtt, backendPairing, androidMqtt, androidAppStore]) {
  if (source.includes("signalasichat/android/")) {
    throw new Error("Fixed Android MQTT topics are forbidden by SignalASI Link v1");
  }
}

for (const required of ["signalasichat/v1", "server_route_id", "client_route_id", "message_id", "expires_at"]) {
  if (!backendLinkProtocol.includes(required) || !androidLinkProtocol.includes(required)) {
    throw new Error(`Desktop and Android Link protocol implementations must include ${required}`);
  }
}

for (const required of ["inbound_messages", "outbound_messages", "claim_message", "queue_outbound"]) {
  if (!backendLinkDelivery.includes(required)) {
    throw new Error(`Desktop reliable delivery store missing ${required}`);
  }
}

for (const required of ["enqueue", "claimIncoming", "acknowledge", "pending"]) {
  if (!androidLinkDelivery.includes(required)) {
    throw new Error(`Android reliable delivery store missing ${required}`);
  }
}

if (!backendSignalClient.includes("signalasi-link-sidecar") || !main.includes("signalasi-link-sidecar") || !packager.includes("signalasi-link-sidecar") || !smokePackaged.includes("signalasi-link-sidecar")) {
  throw new Error("Desktop runtime, packager, and packaged smoke must use signalasi-link-sidecar");
}

if (!sidecarMainSource.includes("package com.signalasi.link;") || !sidecarBuildGradle.includes("com.signalasi.link.SignalSidecar") || !sidecarSettingsGradle.includes('rootProject.name = "signalasi-link-sidecar"')) {
  throw new Error("Signal sidecar source and Gradle metadata must use SignalASI Link naming");
}

if (fs.existsSync(path.join(sidecarSourceDir, "com", "hermes", "signal"))) {
  throw new Error("Signal sidecar source path must not use com/hermes/signal");
}

if (fs.existsSync(path.join(sidecarDir, "build", "install", "hermes-signal-sidecar"))) {
  throw new Error("Signal sidecar build output must not contain hermes-signal-sidecar");
}

if (renderer.includes("const I18N_ZH")) {
  throw new Error("Desktop renderer translations must live in locale files, not an inline I18N_ZH object");
}

if (!main.includes("function loadLocale") || !main.includes('ipcMain.handle("i18n:load"') || !preload.includes("loadLocale") || !workspaceRenderer.includes("await window.signalasi.loadLocale")) {
  throw new Error("Desktop i18n must load locale JSON through preload IPC");
}

for (const requiredLocaleKey of ["Language", "Desktop Connector", "{done}/{total} setup steps complete", "Detecting", "Super agent"]) {
  if (!localeZh[requiredLocaleKey]) {
    throw new Error(`Chinese desktop locale missing key: ${requiredLocaleKey}`);
  }
}

if (!html.includes('<div class="sidebar-brand-copy"><strong>SignalASI</strong><span data-i18n="Super agent">Super agent</span></div>')) {
  throw new Error("Desktop sidebar brand must use the localized Super agent subtitle");
}

if (
  !/\.sidebar-brand-copy\s*\{[^}]*width:\s*64px;[^}]*text-align:\s*center;/s.test(styles)
  || !/\.sidebar-brand strong,\s*\.sidebar-brand span\s*\{[^}]*width:\s*100%;[^}]*text-align:\s*center;/s.test(styles)
) {
  throw new Error("Desktop sidebar brand title and subtitle must share one fixed centered text column");
}

if (Object.keys(localeEn).length !== 0) {
  throw new Error("English desktop locale should rely on source strings until explicit translations are needed");
}

for (const requiredApiResponseText of [
  "def api_error",
  "def api_ok",
  "\"code\"",
  "\"params\"",
  "phone_not_paired",
  "agent_push_token_invalid"
]) {
  if (!backendApiResponse.includes(requiredApiResponseText)) {
    throw new Error(`Backend API response helper missing: ${requiredApiResponseText}`);
  }
}

for (const requiredBackendCode of [
  "api_error(\"agent_push_token_invalid\"",
  "api_error(\"mobile_status_publish_failed\"",
  "api_error(\"phone_not_paired\"",
  "api_error(\"mqtt_not_initialized\"",
  "api_error(\"mqtt_not_connected\"",
  "api_ok(\"mobile_test_published\"",
  "api_ok(\"agent_push_published\""
]) {
  if (![backendMain, backendMqtt].some((content) => content.includes(requiredBackendCode))) {
    throw new Error(`Backend API code/params response missing: ${requiredBackendCode}`);
  }
}

if (!packager.includes("\"api_response.py\"")) {
  throw new Error("Packaged Desktop backend must include api_response.py");
}
for (const requiredFile of ["link_protocol.py", "link_delivery.py", "task_workspace.py", "desktop_control.py", "desktop_file_tools.py", "desktop_memory.py", "desktop_mcp.py", "desktop_native_tools.py", "desktop_skills.py", "desktop_super_agent.py"]) {
  if (!packager.includes(`"${requiredFile}"`)) {
    throw new Error(`Packaged Desktop backend must include ${requiredFile}`);
  }
}

for (const capabilityContract of [
  [backendDesktopControl, "class DesktopControlManager"],
  [backendDesktopMemory, "class DesktopMemoryStore"],
  [backendDesktopMcp, "class DesktopMcpRegistry"],
  [backendDesktopSkills, "class DesktopSkillRegistry"],
  [backendDesktopSuperAgent, "Using relevant long-term memory"]
]) {
  if (!capabilityContract[0].includes(capabilityContract[1])) {
    throw new Error(`Desktop super-agent capability is incomplete: ${capabilityContract[1]}`);
  }
}

if (!backendMqtt.includes("warm_codex_app_server") || !backendMqtt.includes("_trace_metrics")) {
  throw new Error("Desktop task bridge must prewarm Codex and preserve millisecond latency metrics");
}

if (!backendDesktopFileTools.includes("try_execute_explicit_file_task") || !backendDesktopFileTools.includes("Excel.Application")) {
  throw new Error("Desktop explicit file conversion tool is incomplete");
}

for (const contract of [
  "signalasi.desktop-native-tools/1.0",
  "signalasi.desktop.windows.system.status",
  "signalasi.desktop.windows.app.list",
  "signalasi.desktop.windows.app.launch",
  "signalasi.desktop.files.search",
  "signalasi.desktop.browser.open",
  "signalasi.desktop.web.fetch",
  "signalasi.desktop.workspace.file.write.text",
  "signalasi.desktop.terminal.run",
  "signalasi.desktop.office.document.convert",
  "canonical_input_sha256",
  "idempotency_key_required"
]) {
  if (!backendDesktopNativeTools.includes(contract)) {
    throw new Error(`Desktop native tool contract missing: ${contract}`);
  }
}

for (const taskContract of [
  "/api/desktop/tasks/{task_id}/retry",
  "attachments=attachments",
  "retry_of=str(req.retry_of",
  "desktop_native_tool_registry().cancel_task"
]) {
  if (!backendMain.includes(taskContract)) {
    throw new Error(`Desktop task recovery contract missing: ${taskContract}`);
  }
}

for (const rendererContract of ["retryDesktopTask", "data-retry-task", "task.attachments"]) {
  if (!workspaceRenderer.includes(rendererContract) && !preload.includes(rendererContract)) {
    throw new Error(`Desktop task recovery UI missing: ${rendererContract}`);
  }
}

for (const routeContract of [
  "desktop_tool_call_request",
  "desktop_tool_call_result",
  "DESKTOP_TOOL_REQUEST_SLOTS",
  "scoped_workspace_id"
]) {
  if (!backendMqtt.includes(routeContract)) {
    throw new Error(`Encrypted Desktop tool routing missing: ${routeContract}`);
  }
}

if (!backendMain.includes("custom_agent: dict[str, str]") || !backendAgentConfig.includes("def custom_agent_config") || !backendAgentConfig.includes("def custom_agent_configs")) {
  throw new Error("Agent config API must persist Custom Agent display metadata");
}

if (!backendAgentConfig.includes('"name": "Local LLM"')) {
  throw new Error("Agent config must persist Local model display name");
}

if (!backendCustomAgent.includes("def read_prompt") || !backendMcpWrapper.includes("tools/call")) {
  throw new Error("Backend must include runnable Custom Agent and MCP wrapper scripts");
}

for (const capability of ["model_display_names", "local_model_endpoint_probe", "mobile_cloud_models", "mcp_stdio_wrapper", "multiple_custom_agents", "agent_execution_log", "api_response_codes", "agent_diagnostics_codes"]) {
  if (!backendMain.includes("/api/agents/diagnostics") || !backendGateway.includes(capability)) {
    throw new Error(`Backend diagnostics must advertise capability: ${capability}`);
  }
}

for (const diagnosticsField of ["detail_code", "detail_params", "setup_code", "setup_params", "pairing_code", "pairing_params"]) {
  if (!backendGateway.includes(`"${diagnosticsField}"`)) {
    throw new Error(`Agent diagnostics must include structured i18n field: ${diagnosticsField}`);
  }
}

for (const selfTestField of ['"code": "agent_call_test_disabled"', '"code": "mobile_delivery_test_disabled"', '"code": "agent_not_ready"']) {
  if (!backendGateway.includes(selfTestField)) {
    throw new Error(`Agent self-test must include structured result code: ${selfTestField}`);
  }
}

for (const removedDefaultCloudUi of ['contact: cloud-model', '<option value="cloud-model">']) {
  if (html.includes(removedDefaultCloudUi)) {
    throw new Error(`Desktop must not expose Cloud Model as a default phone contact: ${removedDefaultCloudUi}`);
  }
}

if (renderer.includes("my_agent.py") || html.includes("my_agent.py")) {
  throw new Error("Custom Agent templates must point to packaged runnable scripts, not my_agent.py");
}

for (const oldProtocolType of ["hermes_signal_verify", "hermes_pairing_claim"]) {
  if (backendSignalClient.includes(oldProtocolType) || backendMqtt.includes(oldProtocolType)) {
    throw new Error(`Old protocol type must not be used: ${oldProtocolType}`);
  }
}

for (const requiredText of [
  "/api/agents/config",
  "/api/agents/diagnostics",
  "/api/agents/self-test",
  "/api/mobile/test-message",
  "/api/agent/push",
  "AgentPushReq",
  "verify_agent_push_token",
  "publish_agent_push_message",
  "agent_push_token",
  "signalasi_notify.py",
  "signalasi-notify.bat",
  "X-SignalASI-Token",
  "/api/agents/sync-mobile-status",
  "runtime:diagnostics",
  "getRuntimeDiagnostics",
  "getPairingStatus",
  "clearPairing",
  "saveAgentConfig",
  "getAgentDiagnostics",
  "getAgentExecutionLog",
  "runAgentSelfTest",
  "testAgent",
  "sendMobileTest",
  "syncMobileStatus",
  "Sync phone status",
  "Super agent",
  "New task",
  "Mobile Gateway",
  "conversationStream",
  "promptInput",
  "/api/pairing/status",
  "/api/pairing/clear",
  "pairedClientList",
  "signalasi_verify",
  "signalasi_pairing_ciphertext",
  "connector_agents",
  "publish_connector_status",
  "publish_pairing_revoked",
  "forgotten_by_desktop",
  "SIGNALASI_ALLOW_UNPAIRED_MQTT",
  "Phone is not paired",
  "agentContactList",
  "desktopToolList",
  "agent-execution.jsonl",
  "/api/agents/execution-log",
  "prompt_sha256",
  "local_process",
  "startDesktopTask",
  "listDesktopTasks",
  "cancelDesktopTask",
  "deleteDesktopConversation",
  "/api/desktop/tasks",
  "DesktopTaskStartReq",
  "conversation_messages",
  "customAgentId",
  "saveCustomAgentButton",
  "multiple_custom_agents",
  "Ollama Local",
  "LM Studio",
  "custom_agent_stdio.py",
  "mcp_agent_wrapper.py",
  "custom_agent",
  "clipboard:write",
  "backend dependencies",
  "custom-agent",
  "CUSTOM_AGENT_OK",
  "package:win",
  "package:win:python",
  "smoke",
  "smoke:pairing",
  "smoke:ui",
  "smoke:android-ui",
  "smoke:android-friends",
  "smoke:android-contact-rename",
  "smoke:android-contact-tags",
  "smoke:android-language",
  "smoke:android-cloud-models",
  "smoke:android-background",
  "smoke:android-agent-replies",
  "smoke:android-backup",
  "smoke:android-voice-reply",
  "smoke:android-voice-settings",
  "smoke:android-reset",
  "smoke:mqtt-persistence",
  "smoke:agent-push",
  "smoke:voice-stt",
  "smoke:e2e",
  "smoke:packaged",
  "status:connectors",
  "SignalASI Connector Status",
  "Connector Matrix",
  "SignalASI Link Protocol v1.0.3",
  "CLAUDE_SMOKE_OK",
  "LOCAL_E2E_OK",
  "MCP_E2E_OK",
  "requiredBackendCapabilities",
  "local_model_endpoint_probe",
  "mobile_cloud_models",
  "OpenAI-compatible models API",
  "Local model should not be ready when configured endpoint is unreachable",
  "stale backend detected",
  "fake_mcp_server.py",
  "LOCAL_SMOKE_OK",
  "assertNoSmokeConfigLeak",
  "assertNoE2eConfigLeak",
  "Execution log missing contact",
  "restoreConfigSnapshot",
  "withSignalasiLock",
  "acquireSignalasiLock",
  "Run these scripts sequentially",
  "packageDir",
  "--bundle-python",
  "ensureSignalSidecarRuntime",
  "runGradle",
  "installDist",
  "copyRecursive(path.join(root, \"docs\")",
  "Packaged connector status doc",
  "Packaged UI smoke screenshot",
  "android-agent-page.xml",
  "signalasi_debug_service_payload",
  "signalasi_debug_open_contact",
  "signalasi_debug_open_contact_detail",
  "signalasi_debug_open_new_friends",
  "signalasi_debug_open_group",
  "signalasi_debug_open_create_group",
  "signalasi_debug_open_device",
  "signalasi_debug_open_automation",
  "signalasi_debug_open_local_model",
  "signalasi_debug_open_cloud_providers",
  "signalasi_debug_open_cloud_provider",
  "ChatHistoryStore.appendIncoming",
  "ChatHistoryStore.markNotified",
  "AppForegroundTracker.isForeground",
  "signalasi_chat_history.db",
  "signalasi_debug_chat_history_probe_b64",
  "encrypted_sqlite",
  "signalasi_app_store",
  "signalasi_signal_trust",
  "signalasi_signal_store",
  "ChatHistoryStore.updatedVersion",
  "reloadChatHistoryIfChanged",
  "android-background-message.xml",
  "BG_SERVICE_",
  "background_history",
  "system_notification",
  "markContactRead",
  "offline_qos1_delivery_ok",
  "isCleanSession = false",
  "stableClientId()",
  "SignalASICrypto.localIdentitySha256().take(16)",
  "client_message_id",
  "delivery_trace",
  "delivery_ack",
  "build_delivery_ack_payload",
  "applyDeliveryAck",
  "desktop_reply_publish_queued",
  "desktop_reply_broker_ack",
  "desktop_broker_ack",
  "desktop_agent_push_queued",
  "deliveryTrace",
  "signalasi_debug_pairing",
  "signalasi_debug_open_agents",
  "signalasi_debug_approve_friend",
  "signalasi_debug_delete_contact",
  "approveFriendRequestForSignalasiId",
  "previously_deleted",
  "readd_required",
  "re-added contact did not store readded_at evidence",
  "signalasi_debug_rename_contact",
  "signalasi_debug_rename_name_b64",
  "user_renamed",
  "smoke:android-contact-rename",
  "signalasi_debug_open_contacts",
  "Tag Agent Smoke",
  "Tag Model Smoke",
  "Tag Device Smoke",
  "smoke:android-contact-tags",
  "signalasi_debug_open_language_settings",
  "android-language-default.xml",
  "smoke:android-language",
  "signalasi_debug_cloud_models_roundtrip",
  "cloud_models_roundtrip_result",
  "CLOUD_API_REPLY_",
  "direct_mobile_cloud_api",
  "adb([\"reverse\"",
  "deepseek-v4-flash",
  "smoke:android-cloud-models",
  "signalasi_debug_open_voice_settings",
  "signalasi_debug_open_backup_export",
  "signalasi_debug_open_backup_import",
  "signalasi_debug_open_destroy_data",
  "signalasi_debug_destroy_all_data",
  "smoke:android-agent-replies",
  "AGENT_REPLY_TAIL",
  "signalasi_debug_backup_roundtrip",
  "BACKUP_ROUNDTRIP_MESSAGE",
  "ADB transient failure",
  "signalasi_debug_open_messages",
  "hasTraceStage(message, \"read\")",
  "unread badge 1",
  "list timestamp",
  "bubble or divider timestamp",
  "smoke:android-backup",
  "smoke:android-reset",
  "smoke:android-voice-reply",
  "VOICE_REPLY_TAIL",
  "signalasi_debug_voice_settings_roundtrip",
  "voice_settings_roundtrip_result",
  "zh-CN-XiaoxiaoNeural",
  "smoke:android-voice-settings",
  "Destructive reset did not rotate the local Signal identity store",
  "SIGNALASI_WHISPER_MODEL",
  "SIGNALASI_WHISPER_DEVICE",
  "SIGNALASI_WHISPER_COMPUTE_TYPE",
  "VOICE_STT_SMOKE",
  "clean_audio_reply",
  "signalasi_debug_open_protocol_quality",
  "signalasi_debug_open_signal_link_protocol",
  "signalasi_debug_open_advanced_options",
  "signalasi_backup",
  "Research Agent",
  "SIGNALASI_UI_SMOKE_DIR",
  "app.setPath(\"userData\"",
  "desktop-language-en.png",
  "desktop-language-zh.png",
  "Desktop did not default to English",
  "Desktop Simplified Chinese language switch failed",
  "SIGNALASI_PYTHON",
  "SIGNALASI_PYTHON_VENV",
  "resources\\\\python\\\\venv\\\\Scripts\\\\python.exe",
  "win-x64",
  "install-backend-deps.bat",
  "scripts/smoke.js",
  "SignalASI Link Protocol",
  "agent_task_manager.py",
  "/api/agent/tasks",
  "agent_task_event",
  "agent_task_cancel",
  "status_seq",
  "pending_task_events",
  "taskStatusSeq",
  "smoke:agent-lifecycle"
]) {
if (![main, preload, html, renderer, workspaceRenderer, packageJson, packager, androidAdb, smoke, smokePairing, smokeUi, smokeAndroidUi, smokeAndroidFriends, smokeAndroidContactTags, smokeAndroidLanguage, smokeAndroidCloudModels, smokeAndroidBackground, smokeAndroidAgentReplies, smokeAndroidBackup, smokeAndroidVoiceReply, smokeAndroidReset, smokeMqttPersistence, smokeAgentPush, smokeAgentLifecycle, smokeVoiceStt, smokeE2e, smokePackaged, smokeLock, connectorStatus, statusDoc, backendMain, backendMqtt, backendPairing, backendLinkProtocol, backendGateway, backendTaskManager, backendAgentConfig, backendPushAuth, backendSignalasiNotify, backendStt, androidMainActivity, androidMqtt, androidMessageService, androidChatHistoryStore, androidChatHistoryDatabase, androidDebugChatHistoryProbe, androidChatHistoryProbeScript, androidSignalStore, androidForegroundTracker, androidAppStore].some((content) => content.includes(requiredText))) {
    throw new Error(`Missing desktop connector capability: ${requiredText}`);
  }
}

for (const smokeSource of [
  smokeAndroidUi,
  smokeAndroidCloudModels,
  smokeAndroidBackground,
  smokeAndroidAgentReplies,
  smokeAndroidBackup,
  smokeAndroidVoiceReply,
  smokeAndroidReset
]) {
  if (smokeSource.includes("signalasi_chat_history.xml")) {
    throw new Error("Android smoke tests must not read the removed legacy chat history XML");
  }
}

for (const mojibake of ["\u95ba", "\u95c1", "\u95c2", "\u5a75", "\u7f02", "\u6fde\u5b58\u7c8d\u9368", "\u95b8", "\u95b9"]) {
  if (html.includes(mojibake) || renderer.includes(mojibake) || workspaceRenderer.includes(mojibake)) {
    throw new Error(`Renderer contains mojibake text: ${mojibake}`);
  }
}

for (const cloudSettingId of [
  "cloudProvider",
  "cloudDisplayName",
  "cloudEndpoint",
  "cloudModelId",
  "cloudApiKey",
  "cloudContextWindow",
  "cloudOutputReserve",
  "cloudModelSummary",
  "saveCloudModelButton",
  "testCloudModelButton"
]) {
  if (!html.includes(`id="${cloudSettingId}"`)) {
    throw new Error(`Desktop cloud API setting is missing: ${cloudSettingId}`);
  }
}

for (const cloudSettingContract of [
  "CLOUD_PROVIDER_PRESETS",
  "validateCloudModelSettings",
  "saveCloudModelSettings",
  "context_window_tokens",
  "max_output_tokens",
  "context_model_summary",
  'testAgent("cloud-model"'
]) {
  if (!workspaceRenderer.includes(cloudSettingContract)) {
    throw new Error(`Desktop cloud API behavior is missing: ${cloudSettingContract}`);
  }
}

if (main.includes('id: "claude-code"')) {
  throw new Error("Claude contact id must be claude");
}

if (backendGateway.includes("return [*command, text], None")) {
  throw new Error("Desktop connector must not append prompt text to command-line arguments by default");
}

if (backendCustomAgent.includes("Received: {prompt}")) {
  throw new Error("Custom Agent template must not echo the full user prompt");
}

if (androidMainActivity.includes("publishGroupTextMessage(") || androidMainActivity.includes("AppStore.createGroup(") || androidMainActivity.includes("createGroupWithMembers(")) {
  throw new Error("Group chat is deferred and must not be callable from Android UI");
}

if (androidMainActivity.includes("AppStore.ensureIncomingGroup(") || androidChatHistoryStore.includes("AppStore.ensureIncomingGroup(")) {
  throw new Error("Group chat is deferred; incoming messages must not auto-create group contacts");
}

for (const requiredDeferredGroupText of [
  "badge_unavailable",
  "group_feature_status_subtitle",
  "Group chat implementation is not enabled in this version"
]) {
  if (![androidMainActivity, androidStringsEn].some((content) => content.includes(requiredDeferredGroupText))) {
    throw new Error(`Deferred group UI marker missing: ${requiredDeferredGroupText}`);
  }
}

for (const promptPrivacyText of [
  "Prompt text is sent through stdin by default",
  "Request received. Custom Agent is connected."
]) {
  if (![html, renderer, backendGateway, backendCustomAgent].some((content) => content.includes(promptPrivacyText))) {
    throw new Error(`Missing prompt privacy marker: ${promptPrivacyText}`);
  }
}

for (const [label, content] of [
  ["MainActivity", androidMainActivity],
  ["AppStore", androidAppStore],
  ["SignalASIMqttClient", androidMqtt]
]) {
  if (content.includes('.put("hermes_id"')) {
    throw new Error(`${label} must not write hermes_id; use signalasi_id`);
  }
}

if (androidMainActivity.includes("Hermes ID")) {
  throw new Error("Android UI must display SignalASI ID, not Hermes ID");
}

if (androidMqtt.includes("hermeschat-android")) {
  throw new Error("Android MQTT client id must use SignalASI naming");
}

if ([androidMainActivity, androidAppStore, androidStringsZh, androidStringsEn].some((content) => content.includes("hermes_backup"))) {
  throw new Error("New Android backup artifacts must use SignalASI naming, not hermes_backup");
}

for (const file of listFilesRecursive(androidSourceRoot)) {
  const relative = path.relative(workspaceRoot, file).replace(/\\/g, "/");
  const oldBrandToken = "signal" + "ai";
  if (relative.toLowerCase().includes(oldBrandToken)) {
    throw new Error(`Android resource path must use SignalASI naming: ${relative}`);
  }
  if (!/\.(kt|xml|gradle|properties|txt)$/i.test(file)) continue;
  const content = fs.readFileSync(file, "utf8");
  if (content.toLowerCase().includes(oldBrandToken)) {
    throw new Error(`Android source must use SignalASI naming: ${relative}`);
  }
  for (const oldAndroidInternalName of ["hermes_dark", "DEFAULT_HERMES_SEND_TOPIC"]) {
    if (content.includes(oldAndroidInternalName)) {
      throw new Error(`Android internal resource/constant must use SignalASI naming: ${relative}`);
    }
  }
}

for (const requiredAndroidSignalasiText of [
  "signalasi_id",
  "localSignalasiId",
  "signalasi_contact",
  "SignalASI ID"
]) {
  if (![androidMainActivity, androidAppStore, androidCrypto, androidMqtt, androidStringsZh, androidStringsEn].some((content) => content.includes(requiredAndroidSignalasiText))) {
    throw new Error(`Android SignalASI identity migration missing: ${requiredAndroidSignalasiText}`);
  }
}

for (const requiredVoicePipelineText of [
  "sendVoiceRecordingThroughPipeline",
  "ASR_PROVIDER_LOCAL_WHISPER",
  "LocalWhisperAsr.transcribe",
  "WhisperModelManager",
  "voice_asr_provider",
  "SUPPORTED_WAKE_MODELS",
  "DEFAULT_WAKE_MODEL"
]) {
  if (![androidMainActivity, androidVoiceSettings, androidLocalWhisper, androidWhisperModels, androidStringsZh, androidStringsEn].some((content) => content.includes(requiredVoicePipelineText))) {
    throw new Error(`Android voice pipeline missing: ${requiredVoicePipelineText}`);
  }
}

for (const requiredWorkspaceText of [
  "SignalASIWorkspace",
  "task_workspace(task_id, spec.id)",
  "cwd=str(working_directory)",
  "SIGNALASI_OUTPUT_DIR",
  "task_workspace(task.task_id, agent_id)"
]) {
  if (![backendTaskWorkspace, backendGateway, backendMqtt].some((content) => content.includes(requiredWorkspaceText))) {
    throw new Error(`Agent task workspace isolation missing: ${requiredWorkspaceText}`);
  }
}

if (backendMqtt.includes("server.start_task(task.task_id, content, os.getcwd())")) {
  throw new Error("Codex tasks must not run in the backend source directory");
}

if ([androidMainActivity, androidVoiceSettings].some((content) => content.includes("signalasi.onnx"))) {
  throw new Error("Android voice wake settings must not expose unbundled wake model signalasi.onnx");
}

for (const requiredCloudModelText of [
  "sendOpenAiCompatible",
  "sendAnthropic",
  "sendGemini",
  "\"Authorization\" to \"Bearer",
  "\"x-api-key\"",
  "\"anthropic-version\"",
  "anthropic-dangerous-direct-browser-access",
  "URLEncoder.encode(contact.getString(\"cloud_api_key\")",
  "\"system_instruction\"",
  "\"contents\"",
  "\"generationConfig\"",
  "\"HTTP-Referer\"",
  "\"X-Title\""
]) {
  if (!androidCloudModelClient.includes(requiredCloudModelText)) {
    throw new Error(`Android direct cloud model client missing: ${requiredCloudModelText}`);
  }
}

console.log("SignalASI Desktop structure OK");
