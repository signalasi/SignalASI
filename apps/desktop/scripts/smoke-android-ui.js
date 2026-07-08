const { execFileSync } = require("node:child_process");
const crypto = require("node:crypto");
const fs = require("node:fs");
const path = require("node:path");

const root = path.resolve(__dirname, "..");
const workspaceRoot = path.resolve(root, "..");
const androidDir = path.join(workspaceRoot, "android");
const apkPath = path.join(androidDir, "app", "build", "outputs", "apk", "debug", "app-debug.apk");
const outDir = path.join(root, "ui-smoke");
const windowDump = path.join(outDir, "android-agent-page.xml");
const securityDump = path.join(outDir, "android-security-page.xml");
const settingsDump = path.join(outDir, "android-settings-page.xml");
const packageName = "com.signalasi.chat";
const activityName = `${packageName}/.MainActivity`;
const securityTitleTexts = ["Security Center", "\u5b89\u5168\u4e2d\u5fc3"];
const securityRequiredTextGroups = [
  securityTitleTexts,
  ["Privacy &amp; Security", "\u9690\u79c1\u4e0e\u5b89\u5168"],
  ["Phone Fingerprint", "\u624b\u673a\u6307\u7eb9"],
  ["SignalASI ID"],
  ["Paired Devices", "\u5df2\u914d\u5bf9\u8bbe\u5907"]
];
const securityScrolledRequiredTextGroups = [
  ["Agent Permissions", "Agent \u6743\u9650"],
  ["Message Protection", "\u6d88\u606f\u4fdd\u62a4"],
  ["Dual Fingerprint Confirmation", "\u53cc\u7aef\u6307\u7eb9\u786e\u8ba4"],
  ["Revoke All PC Pairings", "\u64a4\u9500\u6240\u6709 PC \u914d\u5bf9"]
];

function log(message) {
  console.log(`[android-ui-smoke] ${message}`);
}

function fail(message) {
  throw new Error(message);
}

function adb(args, options = {}) {
  return execFileSync("adb", args, {
    cwd: options.cwd || root,
    encoding: "utf8",
    windowsHide: true,
    stdio: options.stdio || ["ignore", "pipe", "pipe"]
  });
}

function sleep(ms) {
  return new Promise((resolve) => setTimeout(resolve, ms));
}

function readAppStore() {
  return adb(["shell", "run-as", packageName, "cat", "shared_prefs/signalasi_app_store.xml"]);
}

function slimPairingPayload() {
  const identityKey = crypto.randomBytes(32);
  const fingerprint = crypto.createHash("sha256").update(identityKey).digest("hex");
  return {
    type: "signalasi_verify",
    version: 1,
    device: "pc",
    desktop_id: `desktop_${fingerprint.slice(0, 16)}`,
    desktop_name: "SMOKE-PC",
    device_id: 1,
    identity_key: identityKey.toString("base64"),
    identity_key_sha256: fingerprint,
    created_at: Math.floor(Date.now() / 1000),
    pairing_token: crypto.randomBytes(24).toString("base64url")
  };
}

function hasAnyText(value, texts) {
  return texts.some((text) => value.includes(text));
}

function requireStoreText(store, text, stage) {
  if (!store.includes(text)) {
    fail(`${stage}: app store did not include ${text}`);
  }
}

function requireAnyText(value, texts, label, dumpPath) {
  if (!hasAnyText(value, texts)) {
    fail(`Window dump missing ${label}. Dump saved at ${dumpPath}`);
  }
}

function dumpWindowTo(fileName, remoteName) {
  adb(["shell", "uiautomator", "dump", `/sdcard/${remoteName}`]);
  adb(["pull", `/sdcard/${remoteName}`, fileName]);
  return fs.readFileSync(fileName, "utf8");
}

function readTrustStore() {
  try {
    return adb(["shell", "run-as", packageName, "cat", "shared_prefs/signalasi_signal_trust.xml"]);
  } catch {
    return "";
  }
}

function readHistoryStore() {
  try {
    return adb(["shell", "run-as", packageName, "cat", "shared_prefs/signalasi_chat_history.xml"]);
  } catch {
    return "";
  }
}

async function openDebugPageAndVerify(extraName, titleTexts, requiredGroups) {
  adb([
    "shell",
    "am",
    "start",
    "-n",
    activityName,
    "--ez",
    extraName,
    "true"
  ]);
  let pageXml = "";
  for (let attempt = 0; attempt < 12; attempt += 1) {
    await sleep(1000);
    pageXml = dumpWindowTo(settingsDump, "signalasi-settings.xml");
    if (hasAnyText(pageXml, titleTexts)) break;
  }
  requireAnyText(pageXml, titleTexts, titleTexts[0], settingsDump);

  for (let reset = 0; reset < 3; reset += 1) {
    adb(["shell", "input", "swipe", "520", "520", "520", "1900", "350"]);
    await sleep(500);
  }
  let combinedXml = dumpWindowTo(settingsDump, "signalasi-settings.xml");
  for (let scroll = 0; scroll < 3; scroll += 1) {
    adb(["shell", "input", "swipe", "520", "1900", "520", "520", "450"]);
    await sleep(700);
    combinedXml += dumpWindowTo(settingsDump, "signalasi-settings.xml");
  }
  for (const texts of requiredGroups) {
    requireAnyText(combinedXml, texts, texts[0], settingsDump);
  }
}

async function main() {
  if (!fs.existsSync(apkPath)) {
    fail(`Android debug APK missing. Build it first: ${apkPath}`);
  }
  fs.mkdirSync(outDir, { recursive: true });

  log("installing debug APK");
  adb(["install", "-r", apkPath], { stdio: "inherit" });
  adb(["shell", "input", "keyevent", "KEYCODE_WAKEUP"]);
  adb(["shell", "input", "keyevent", "KEYCODE_BACK"]);
  adb(["shell", "am", "force-stop", packageName]);

  log("scanning slim Desktop QR and verifying default connector contacts");
  const pairingPayload = slimPairingPayload();
  const qrB64 = Buffer.from(JSON.stringify(pairingPayload), "utf8").toString("base64");
  adb([
    "shell",
    "am",
    "start",
    "-n",
    activityName,
    "--es",
    "signalasi_debug_scan_payload_b64",
    qrB64,
    "--ez",
    "signalasi_debug_auto_confirm_scan",
    "true"
  ]);
  await sleep(3000);
  const slimQrStore = readAppStore();
  for (const text of [
    "Hermes Agent",
    "Codex Agent",
    "Claude Code",
    "Local LLM",
    "Custom Agent",
    "SMOKE-PC",
    "pc_connector"
  ]) {
    requireStoreText(slimQrStore, text, "slim QR scan");
  }

  log("opening AI Agent page with debug connector status");
  adb([
    "shell",
    "am",
    "start",
    "-n",
    activityName,
    "--ez",
    "signalasi_debug_pairing",
    "true",
    "--ez",
    "signalasi_debug_status",
    "true",
    "--ez",
    "signalasi_debug_open_agents",
    "true"
  ]);
  let xml = "";
  for (let attempt = 0; attempt < 12; attempt += 1) {
    await sleep(1000);
    adb(["shell", "uiautomator", "dump", "/sdcard/signalasi-window.xml"]);
    adb(["pull", "/sdcard/signalasi-window.xml", windowDump]);
    xml = fs.readFileSync(windowDump, "utf8");
    if (xml.includes("AI Agent")) break;
  }
  for (const text of ["AI Agent", "Hermes", "Codex", "Local LLM"]) {
    if (!xml.includes(text)) {
      fail(`Window dump missing ${text}. Dump saved at ${windowDump}`);
    }
  }
  if (!hasAnyText(xml, ["Add Cloud Model", "\u6dfb\u52a0\u4e91\u7aef\u6a21\u578b"])) {
    fail(`Window dump missing mobile direct cloud model entry. Dump saved at ${windowDump}`);
  }
  if (xml.includes("Desktop Cloud Model")) {
    fail(`Window dump still exposes Desktop Cloud Model. Dump saved at ${windowDump}`);
  }

  const pairedStore = readAppStore();
  if (pairedStore.includes("&quot;agent_id&quot;:&quot;cloud-model&quot;") || pairedStore.includes(":cloud-model")) {
    fail("App store still exposes Desktop Cloud Model as a connector contact");
  }
  for (const text of ["research-agent", "Research Agent", "pc_connector"]) {
    if (!pairedStore.includes(text)) {
      fail(`App store did not include ${text} after debug status sync`);
    }
  }
  let historyStore = "";
  for (let attempt = 0; attempt < 10; attempt += 1) {
    historyStore = readHistoryStore();
    if (historyStore.includes("deliveryTrace") && historyStore.includes("decrypted") && historyStore.includes("persisted")) break;
    await sleep(500);
  }
  for (const text of ["deliveryTrace", "decrypted", "persisted"]) {
    if (!historyStore.includes(text)) {
      fail(`Chat history did not include delivery trace marker ${text}`);
    }
  }
  let pairedTrustStore = "";
  for (let attempt = 0; attempt < 8; attempt += 1) {
    pairedTrustStore = readTrustStore();
    if (pairedTrustStore.includes("DEBUG_PC_FINGERPRINT_FOR_UI_TEST")) break;
    await sleep(500);
  }
  if (!pairedTrustStore.includes("DEBUG_PC_FINGERPRINT_FOR_UI_TEST")) {
    fail("Trust store did not include debug PC fingerprint after debug pairing");
  }

  log("opening Security Center and verifying trust controls");
  adb([
    "shell",
    "am",
    "start",
    "-n",
    activityName,
    "--ez",
    "signalasi_debug_open_security",
    "true"
  ]);
  let securityXml = "";
  for (let attempt = 0; attempt < 12; attempt += 1) {
    await sleep(1000);
    adb(["shell", "uiautomator", "dump", "/sdcard/signalasi-security.xml"]);
    adb(["pull", "/sdcard/signalasi-security.xml", securityDump]);
    securityXml = fs.readFileSync(securityDump, "utf8");
    if (hasAnyText(securityXml, securityTitleTexts)) break;
  }
  for (const texts of securityRequiredTextGroups) {
    requireAnyText(securityXml, texts, texts[0], securityDump);
  }
  let scrolledSecurityXml = securityXml;
  for (let scroll = 0; scroll < 3; scroll += 1) {
    adb(["shell", "input", "swipe", "520", "1900", "520", "520", "450"]);
    await sleep(800);
    adb(["shell", "uiautomator", "dump", "/sdcard/signalasi-security.xml"]);
    adb(["pull", "/sdcard/signalasi-security.xml", securityDump]);
    scrolledSecurityXml += fs.readFileSync(securityDump, "utf8");
  }
  for (const texts of securityScrolledRequiredTextGroups) {
    requireAnyText(scrolledSecurityXml, texts, texts[0], securityDump);
  }

  log("opening Settings feature pages");
  await openDebugPageAndVerify(
    "signalasi_debug_open_voice_settings",
    ["Voice Wake &amp; ASR/TTS", "\u8bed\u97f3\u5524\u9192\u4e0e ASR/TTS"],
    [
      ["Low-power Voice Listening", "\u4f4e\u529f\u8017\u8bed\u97f3\u76d1\u542c"],
      ["Wake Engine", "\u5524\u9192\u5f15\u64ce"],
      ["ASR Provider", "ASR \u63d0\u4f9b\u65b9"],
      ["TTS Provider", "TTS \u63d0\u4f9b\u65b9"]
    ]
  );
  await openDebugPageAndVerify(
    "signalasi_debug_open_backup_export",
    ["Back Up Chat History", "\u5907\u4efd\u804a\u5929\u8bb0\u5f55"],
    [
      ["Encrypted Backup", "\u52a0\u5bc6\u5907\u4efd"],
      ["Backup Password", "\u5907\u4efd\u5bc6\u7801"],
      ["Back up chat history", "\u5907\u4efd\u804a\u5929\u8bb0\u5f55"]
    ]
  );
  await openDebugPageAndVerify(
    "signalasi_debug_open_backup_import",
    ["Import Encrypted Backup", "\u5bfc\u5165\u52a0\u5bc6\u5907\u4efd"],
    [
      ["Enter the password used during export.", "\u8f93\u5165\u5bfc\u51fa\u65f6\u8bbe\u7f6e\u7684\u5bc6\u7801\u3002"],
      ["Password", "\u5bc6\u7801"],
      ["Import", "\u5bfc\u5165"]
    ]
  );
  await openDebugPageAndVerify(
    "signalasi_debug_open_destroy_data",
    ["Clear All Data", "\u6e05\u9664\u6240\u6709\u6570\u636e"],
    [
      ["Dangerous Operation", "\u5371\u9669\u64cd\u4f5c"],
      ["Regenerate Identity", "\u91cd\u65b0\u751f\u6210\u8eab\u4efd"],
      ["Contacts", "\u8054\u7cfb\u4eba"],
      ["Chat History", "\u804a\u5929\u8bb0\u5f55"]
    ]
  );
  await openDebugPageAndVerify(
    "signalasi_debug_open_protocol_quality",
    ["Protocol &amp; Quality", "\u534f\u8bae\u4e0e\u8d28\u91cf"],
    [
      ["Delivery Acknowledgement", "\u9001\u8fbe\u786e\u8ba4"],
      ["Offline Queue", "\u79bb\u7ebf\u961f\u5217"],
      ["Identity Key", "\u8eab\u4efd\u5bc6\u94a5"],
      ["Session Rotation", "\u4f1a\u8bdd\u8f6e\u6362"],
      ["Enabled", "\u542f\u7528"]
    ]
  );
  await openDebugPageAndVerify(
    "signalasi_debug_open_signal_link_protocol",
    ["Signal Link Protocol"],
    [
      ["Version v1.0.3", "\u7248\u672c v1.0.3"],
      ["Identity Layer", "\u8eab\u4efd\u5c42"],
      ["Session Layer", "\u4f1a\u8bdd\u5c42"],
      ["Signal Protocol"]
    ]
  );
  await openDebugPageAndVerify(
    "signalasi_debug_open_advanced_options",
    ["Advanced Options", "\u9ad8\u7ea7\u9009\u9879"],
    [
      ["Diagnostics", "\u8bca\u65ad"],
      ["Protocol Logs", "\u534f\u8bae\u65e5\u5fd7"],
      ["Agent Permission Audit", "Agent \u6743\u9650\u5ba1\u8ba1"],
      ["Experiments", "\u5b9e\u9a8c"]
    ]
  );

  log("revoking debug pairing and verifying cleanup");
  adb(["shell", "am", "start", "-n", activityName, "--ez", "signalasi_debug_revoke", "true"]);
  await sleep(2000);
  const slimRevoke = Buffer.from(JSON.stringify({
    type: "pairing_revoked",
    desktop_id: pairingPayload.desktop_id,
    content: "smoke slim QR revoked"
  }), "utf8").toString("base64");
  adb(["shell", "am", "start", "-n", activityName, "--es", "signalasi_debug_incoming_b64", slimRevoke]);
  await sleep(1200);
  const trustStore = readTrustStore();
  const cleanStore = readAppStore();
  const activeResearchAgent = /research-agent[\s\S]{0,1600}&quot;deleted&quot;:false/.test(cleanStore);
  if (trustStore.includes("verified_pc_identity_sha256") || activeResearchAgent) {
    fail("Debug pairing cleanup failed; trust or dynamic contact remains");
  }

  log(`OK: dynamic Agent UI, Security Center, and Settings feature pages verified. Dumps: ${windowDump}, ${securityDump}, ${settingsDump}`);
}

main().catch((error) => {
  console.error(`[android-ui-smoke] failed: ${error.stack || error.message || error}`);
  process.exit(1);
});
