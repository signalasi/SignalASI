const { execFileSync } = require("node:child_process");
const fs = require("node:fs");
const path = require("node:path");

const root = path.resolve(__dirname, "..");
const workspaceRoot = path.resolve(root, "..");
const androidDir = path.join(workspaceRoot, "android");
const apkPath = path.join(androidDir, "app", "build", "outputs", "apk", "debug", "app-debug.apk");
const outDir = path.join(root, "ui-smoke");
const windowDump = path.join(outDir, "android-agent-page.xml");
const securityDump = path.join(outDir, "android-security-page.xml");
const packageName = "com.signalasi.chat";
const activityName = `${packageName}/.MainActivity`;
const securityTitle = "\u5b89\u5168\u4e2d\u5fc3";
const securityRequiredTexts = [
  securityTitle,
  "\u9690\u79c1\u4e0e\u5b89\u5168",
  "\u624b\u673a\u6307\u7eb9",
  "SignalASI ID",
  "\u5df2\u914d\u5bf9\u8bbe\u5907"
];
const securityScrolledRequiredTexts = [
  "Agent \u6743\u9650",
  "\u6d88\u606f\u4fdd\u62a4",
  "\u53cc\u7aef\u6307\u7eb9\u786e\u8ba4",
  "\u64a4\u9500\u6240\u6709 PC \u914d\u5bf9"
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
  if (xml.includes("Cloud Model")) {
    fail(`Window dump still exposes Desktop Cloud Model. Dump saved at ${windowDump}`);
  }

  const pairedStore = readAppStore();
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
    if (securityXml.includes(securityTitle)) break;
  }
  for (const text of securityRequiredTexts) {
    if (!securityXml.includes(text)) {
      fail(`Security Center dump missing ${text}. Dump saved at ${securityDump}`);
    }
  }
  adb(["shell", "input", "swipe", "520", "1900", "520", "520", "450"]);
  await sleep(800);
  adb(["shell", "uiautomator", "dump", "/sdcard/signalasi-security.xml"]);
  adb(["pull", "/sdcard/signalasi-security.xml", securityDump]);
  securityXml = fs.readFileSync(securityDump, "utf8");
  for (const text of securityScrolledRequiredTexts) {
    if (!securityXml.includes(text)) {
      fail(`Security Center scrolled dump missing ${text}. Dump saved at ${securityDump}`);
    }
  }

  log("revoking debug pairing and verifying cleanup");
  adb(["shell", "am", "start", "-n", activityName, "--ez", "signalasi_debug_revoke", "true"]);
  await sleep(2000);
  const trustStore = readTrustStore();
  const cleanStore = readAppStore();
  const activeResearchAgent = /research-agent[\s\S]{0,1600}&quot;deleted&quot;:false/.test(cleanStore);
  if (trustStore.includes("verified_pc_identity_sha256") || activeResearchAgent) {
    fail("Debug pairing cleanup failed; trust or dynamic contact remains");
  }

  log(`OK: dynamic Agent UI and Security Center verified. Dumps: ${windowDump}, ${securityDump}`);
}

main().catch((error) => {
  console.error(`[android-ui-smoke] failed: ${error.stack || error.message || error}`);
  process.exit(1);
});
