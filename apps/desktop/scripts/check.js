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
  "src/renderer/locales/zh-CN.json",
  "src/renderer/locales/en.json",
  "src/renderer/styles.css",
  "scripts/package-win.js",
  "scripts/smoke.js",
  "scripts/smoke-pairing.js",
  "scripts/smoke-ui.js",
  "scripts/smoke-android-ui.js",
  "scripts/smoke-android-friends.js",
  "scripts/smoke-android-background-message.js",
  "scripts/smoke-android-reset.js",
  "scripts/smoke-mqtt-persistence.js",
  "scripts/smoke-agent-push.js",
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

const main = fs.readFileSync(path.join(root, "src/main.js"), "utf8");
const preload = fs.readFileSync(path.join(root, "src/preload.js"), "utf8");
const html = fs.readFileSync(path.join(root, "src/renderer/index.html"), "utf8");
const renderer = fs.readFileSync(path.join(root, "src/renderer/renderer.js"), "utf8");
const localeZh = JSON.parse(fs.readFileSync(path.join(root, "src", "renderer", "locales", "zh-CN.json"), "utf8"));
const localeEn = JSON.parse(fs.readFileSync(path.join(root, "src", "renderer", "locales", "en.json"), "utf8"));
const packageJson = fs.readFileSync(path.join(root, "package.json"), "utf8");
const packager = fs.readFileSync(path.join(root, "scripts/package-win.js"), "utf8");
const smoke = fs.readFileSync(path.join(root, "scripts/smoke.js"), "utf8");
const smokePairing = fs.readFileSync(path.join(root, "scripts/smoke-pairing.js"), "utf8");
const smokeUi = fs.readFileSync(path.join(root, "scripts/smoke-ui.js"), "utf8");
const smokeAndroidUi = fs.readFileSync(path.join(root, "scripts/smoke-android-ui.js"), "utf8");
const smokeAndroidFriends = fs.readFileSync(path.join(root, "scripts/smoke-android-friends.js"), "utf8");
const smokeAndroidBackground = fs.readFileSync(path.join(root, "scripts/smoke-android-background-message.js"), "utf8");
const smokeAndroidReset = fs.readFileSync(path.join(root, "scripts/smoke-android-reset.js"), "utf8");
const smokeMqttPersistence = fs.readFileSync(path.join(root, "scripts/smoke-mqtt-persistence.js"), "utf8");
const smokeAgentPush = fs.readFileSync(path.join(root, "scripts/smoke-agent-push.js"), "utf8");
const smokeE2e = fs.readFileSync(path.join(root, "scripts/smoke-e2e.js"), "utf8");
const smokePackaged = fs.readFileSync(path.join(root, "scripts/smoke-packaged.js"), "utf8");
const smokeLock = fs.readFileSync(path.join(root, "scripts/smoke-lock.js"), "utf8");
const connectorStatus = fs.readFileSync(path.join(root, "scripts/connector-status.js"), "utf8");
const statusDoc = fs.readFileSync(path.join(root, "docs/CONNECTOR_STATUS.md"), "utf8");
const backendMain = fs.readFileSync(path.join(backendDir, "main.py"), "utf8");
const backendMqtt = fs.readFileSync(path.join(backendDir, "mqtt_bridge.py"), "utf8");
const backendPairing = fs.readFileSync(path.join(backendDir, "pairing_state.py"), "utf8");
const backendSignalClient = fs.readFileSync(path.join(backendDir, "signalasi_client.py"), "utf8");
const backendGateway = fs.readFileSync(path.join(backendDir, "agent_gateway.py"), "utf8");
const backendAgentConfig = fs.readFileSync(path.join(backendDir, "agent_config.py"), "utf8");
const backendCustomAgent = fs.readFileSync(path.join(backendDir, "custom_agent_stdio.py"), "utf8");
const backendMcpWrapper = fs.readFileSync(path.join(backendDir, "mcp_agent_wrapper.py"), "utf8");
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
const androidSignalStore = fs.readFileSync(path.join(workspaceRoot, "android", "app", "src", "main", "java", "com", "signalasi", "chat", "AndroidPersistentSignalStore.kt"), "utf8");
const androidForegroundTracker = fs.readFileSync(path.join(workspaceRoot, "android", "app", "src", "main", "java", "com", "signalasi", "chat", "AppForegroundTracker.kt"), "utf8");
const androidAppStore = fs.readFileSync(path.join(workspaceRoot, "android", "app", "src", "main", "java", "com", "signalasi", "chat", "AppStore.kt"), "utf8");
const androidCrypto = fs.readFileSync(path.join(workspaceRoot, "android", "app", "src", "main", "java", "com", "signalasi", "chat", "SignalASICrypto.kt"), "utf8");
const androidMqtt = fs.readFileSync(path.join(workspaceRoot, "android", "app", "src", "main", "java", "com", "signalasi", "chat", "SignalASIMqttClient.kt"), "utf8");
const androidVoiceSettings = fs.readFileSync(path.join(workspaceRoot, "android", "app", "src", "main", "java", "com", "signalasi", "chat", "VoiceAssistantSettings.kt"), "utf8");
const androidCloudModelClient = fs.readFileSync(path.join(workspaceRoot, "android", "app", "src", "main", "java", "com", "signalasi", "chat", "CloudModelClient.kt"), "utf8");
const androidStringsZh = fs.readFileSync(path.join(workspaceRoot, "android", "app", "src", "main", "res", "values-zh-rCN", "strings.xml"), "utf8");
const androidStringsEn = fs.readFileSync(path.join(workspaceRoot, "android", "app", "src", "main", "res", "values", "strings.xml"), "utf8");

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

if (!backendMqtt.includes('"signalasi_pairing_claim"')) {
  throw new Error("MQTT pairing claim must use signalasi_pairing_claim");
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

if (!main.includes("function loadLocale") || !main.includes('ipcMain.handle("i18n:load"') || !preload.includes("loadLocale") || !renderer.includes("await window.signalasi.loadLocale")) {
  throw new Error("Desktop i18n must load locale JSON through preload IPC");
}

for (const requiredLocaleKey of ["Language", "Desktop Connector", "{done}/{total} setup steps complete", "Detecting"]) {
  if (!localeZh[requiredLocaleKey]) {
    throw new Error(`Chinese desktop locale missing key: ${requiredLocaleKey}`);
  }
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
  "Local Agent Connector",
  "Mobile pairing",
  "Closed-loop tests",
  "Runtime requirements",
  "Phone contact map",
  "First-run setup",
  "setupChecklist",
  "pairingState",
  "/api/pairing/status",
  "/api/pairing/clear",
  "Forget phone",
  "data-pairing-type",
  "signalasi_verify",
  "signalasi_pairing_claim",
  "connector_agents",
  "publish_connector_status",
  "publish_pairing_revoked",
  "forgotten_by_desktop",
  "SIGNALASI_ALLOW_UNPAIRED_MQTT",
  "Phone is not paired",
  "renderSetupGuide",
  "data-setup-action",
  "Connector status matrix",
  "Agent permissions and execution log",
  "signalasi_agent_execution.jsonl",
  "/api/agents/execution-log",
  "prompt_sha256",
  "local_process",
  "refreshExecutionLog",
  "refreshStatusMatrix",
  "connectorMatrixRows",
  "renderStatusMatrix",
  "Copy pairing link",
  "Copy default commands",
  "Custom Agent name",
  "Local contact name",
  "Additional custom agents",
  "addCustomAgent",
  "customAgentsList",
  "custom-agent-row",
  "multiple_custom_agents",
  "Ollama Local",
  "LM Studio",
  "custom_agent_stdio.py",
  "mcp_agent_wrapper.py",
  "Use stdin template",
  "Use MCP wrapper template",
  "custom_agent",
  "Existing agents do not need SignalASI settings",
  "clipboard:write",
  "backend dependencies",
  "Self-test all",
  "Open Claude Code docs",
  "Use Ollama defaults",
  "Use LM Studio preset",
  "Cloud models are added directly in the mobile app",
  "Save and test Local LLM",
  "Test Custom Agent",
  "custom-agent",
  "CUSTOM_AGENT_OK",
  "package:win",
  "package:win:python",
  "smoke",
  "smoke:pairing",
  "smoke:ui",
  "smoke:android-ui",
  "smoke:android-friends",
  "smoke:android-background",
  "smoke:android-reset",
  "smoke:mqtt-persistence",
  "smoke:agent-push",
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
  "signalasi_chat_history",
  "signalasi_app_store",
  "signalasi_signal_trust",
  "signalasi_signal_store",
  "HISTORY_UPDATED_KEY",
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
  "signalasi_debug_open_voice_settings",
  "signalasi_debug_open_backup_export",
  "signalasi_debug_open_backup_import",
  "signalasi_debug_open_destroy_data",
  "signalasi_debug_destroy_all_data",
  "smoke:android-reset",
  "Destructive reset did not rotate the local Signal identity store",
  "SIGNALASI_WHISPER_MODEL",
  "SIGNALASI_WHISPER_DEVICE",
  "SIGNALASI_WHISPER_COMPUTE_TYPE",
  "signalasi_debug_open_protocol_quality",
  "signalasi_debug_open_signal_link_protocol",
  "signalasi_debug_open_advanced_options",
  "signalasi_backup",
  "Research Agent",
  "SIGNALASI_UI_SMOKE_DIR",
  "SIGNALASI_PYTHON",
  "SIGNALASI_PYTHON_VENV",
  "resources\\\\python\\\\venv\\\\Scripts\\\\python.exe",
  "win-x64",
  "install-backend-deps.bat",
  "scripts/smoke.js",
  "SignalASI Link Protocol"
]) {
  if (![main, preload, html, renderer, packageJson, packager, smoke, smokePairing, smokeUi, smokeAndroidUi, smokeAndroidFriends, smokeAndroidBackground, smokeAndroidReset, smokeMqttPersistence, smokeAgentPush, smokeE2e, smokePackaged, smokeLock, connectorStatus, statusDoc, backendMain, backendMqtt, backendPairing, backendGateway, backendAgentConfig, backendPushAuth, backendSignalasiNotify, backendStt, androidMainActivity, androidMessageService, androidChatHistoryStore, androidSignalStore, androidForegroundTracker, androidAppStore].some((content) => content.includes(requiredText))) {
    throw new Error(`Missing desktop connector capability: ${requiredText}`);
  }
}

for (const mojibake of ["\u95ba", "\u95c1", "\u95c2", "\u5a75", "\u7f02", "\u6fde\u5b58\u7c8d\u9368", "\u95b8", "\u95b9"]) {
  if (html.includes(mojibake) || renderer.includes(mojibake)) {
    throw new Error(`Renderer contains mojibake text: ${mojibake}`);
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
  "ASR_PROVIDER_PC_STT",
  "voice_asr_provider",
  "PC faster-whisper"
]) {
  if (![androidMainActivity, androidVoiceSettings, androidStringsZh, androidStringsEn].some((content) => content.includes(requiredVoicePipelineText))) {
    throw new Error(`Android voice pipeline missing: ${requiredVoicePipelineText}`);
  }
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
