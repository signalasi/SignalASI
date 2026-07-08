const fs = require("node:fs");
const path = require("node:path");
const { createAdb } = require("./android-adb");

const root = path.resolve(__dirname, "..");
const workspaceRoot = path.resolve(root, "..");
const androidDir = path.join(workspaceRoot, "android");
const apkPath = path.join(androidDir, "app", "build", "outputs", "apk", "debug", "app-debug.apk");
const packageName = "com.signalasi.chat";
const activityName = `${packageName}/.MainActivity`;
const appStorePrefs = "shared_prefs/signalasi_app_store.xml";
const oldAppStorePrefs = "shared_prefs/hermes_app_store.xml";
const historyPrefs = "shared_prefs/signalasi_chat_history.xml";
const debugPrefs = "shared_prefs/signalasi_debug.xml";
const outDir = path.join(root, "ui-smoke");
const debugDump = path.join(outDir, "android-cloud-models-debug.xml");
const storeDump = path.join(outDir, "android-cloud-models-app-store.xml");
const chatDump = path.join(outDir, "android-cloud-models-chat.xml");

function log(message) {
  console.log(`[android-cloud-models-smoke] ${message}`);
}

function fail(message) {
  throw new Error(message);
}

const adb = createAdb(root, log);

function sleep(ms) {
  return new Promise((resolve) => setTimeout(resolve, ms));
}

function readAppFile(file) {
  try {
    return adb(["shell", "run-as", packageName, "cat", file]);
  } catch {
    return "";
  }
}

function restoreAppFile(file, snapshot) {
  if (!snapshot) {
    adb(["shell", "run-as", packageName, "rm", "-f", file]);
    return;
  }
  adb(["shell", "run-as", packageName, "mkdir", "-p", "shared_prefs"]);
  adb(["shell", "run-as", packageName, "tee", file], { input: snapshot, stdio: ["pipe", "ignore", "pipe"] });
}

function decodeXml(value) {
  return value
    .replace(/&quot;/g, '"')
    .replace(/&apos;/g, "'")
    .replace(/&lt;/g, "<")
    .replace(/&gt;/g, ">")
    .replace(/&amp;/g, "&");
}

function prefString(xml, name, fallback = "") {
  const pattern = new RegExp(`<string name="${name}">([\\s\\S]*?)<\\/string>`);
  const match = xml.match(pattern);
  return match ? decodeXml(match[1]) : fallback;
}

async function waitForRoundtrip(token) {
  for (let attempt = 0; attempt < 12; attempt += 1) {
    await sleep(700);
    const xml = readAppFile(debugPrefs);
    const raw = prefString(xml, "cloud_models_roundtrip_result", "");
    if (!raw) continue;
    const result = JSON.parse(raw);
    if (result.token === token) return { xml, result };
  }
  return { xml: readAppFile(debugPrefs), result: null };
}

function readStore() {
  const xml = readAppFile(appStorePrefs);
  return {
    xml,
    contacts: JSON.parse(prefString(xml, "contacts", "[]"))
  };
}

function cloudContact(store, provider) {
  return store.contacts.find((contact) =>
    contact.delivery_mode === "cloud_api" &&
    String(contact.cloud_provider || "").toLowerCase() === provider.toLowerCase()
  );
}

function dumpWindowTo(fileName, remoteName) {
  adb(["shell", "uiautomator", "dump", `/sdcard/${remoteName}`]);
  adb(["pull", `/sdcard/${remoteName}`, fileName]);
  return fs.readFileSync(fileName, "utf8");
}

async function main() {
  if (!fs.existsSync(apkPath)) {
    fail(`Android debug APK missing. Build it first: ${apkPath}`);
  }
  fs.mkdirSync(outDir, { recursive: true });
  const token = `CLOUD_${Date.now()}`;

  log("installing debug APK");
  adb(["install", "-r", apkPath], { stdio: "inherit" });
  adb(["shell", "input", "keyevent", "KEYCODE_WAKEUP"]);
  adb(["shell", "am", "force-stop", packageName]);

  const originalAppStore = readAppFile(appStorePrefs);
  const originalOldAppStore = readAppFile(oldAppStorePrefs);
  const originalHistory = readAppFile(historyPrefs);
  const originalDebug = readAppFile(debugPrefs);

  try {
    log("resetting app store snapshot for isolated cloud model flow");
    restoreAppFile(appStorePrefs, "");
    restoreAppFile(oldAppStorePrefs, "");
    restoreAppFile(historyPrefs, "");
    restoreAppFile(debugPrefs, "");
    adb(["shell", "am", "force-stop", packageName]);

    log("seeding multiple direct mobile cloud providers and switching selected model");
    adb(["shell", "am", "start", "-n", activityName, "--es", "signalasi_debug_cloud_models_roundtrip", token]);
    const { xml, result } = await waitForRoundtrip(token);
    fs.writeFileSync(debugDump, xml || "");
    if (!result) {
      fail(`Cloud model roundtrip did not report a result. Dump saved at ${debugDump}`);
    }
    if (!result.ok) {
      fail(`Cloud model roundtrip failed: ${JSON.stringify(result)}. Dump saved at ${debugDump}`);
    }
    if (result.selected_model !== "deepseek-v4-flash") {
      fail(`Cloud model switch did not select DeepSeek V4 Flash: ${JSON.stringify(result)}`);
    }
    if (result.deepseek_model_count < 2 || result.openai_model_count < 2) {
      fail(`Cloud model roundtrip did not preserve multiple models: ${JSON.stringify(result)}`);
    }
    if (result.cloud_provider_contacts < 2 || result.desktop_cloud_present) {
      fail(`Cloud providers were not stored as direct mobile provider contacts: ${JSON.stringify(result)}`);
    }

    const store = readStore();
    fs.writeFileSync(storeDump, store.xml || "");
    const deepseek = cloudContact(store, "DeepSeek");
    const openai = cloudContact(store, "OpenAI");
    if (!deepseek || !openai) {
      fail(`Expected DeepSeek and OpenAI cloud contacts. Store dump: ${storeDump}`);
    }
    if (deepseek.name !== "DeepSeek" || openai.name !== "OpenAI") {
      fail(`Cloud contact list should display provider names only. Store dump: ${storeDump}`);
    }
    if (deepseek.selected_cloud_model !== "deepseek-v4-flash" || deepseek.cloud_model !== "deepseek-v4-flash") {
      fail(`Selected DeepSeek model was not persisted. Store dump: ${storeDump}`);
    }
    if ((deepseek.cloud_models || []).length < 2 || (openai.cloud_models || []).length < 2) {
      fail(`Cloud contacts did not keep multiple model configs. Store dump: ${storeDump}`);
    }
    if (store.xml.includes("&quot;agent_id&quot;:&quot;cloud-model&quot;") || store.xml.includes(":cloud-model")) {
      fail(`Desktop cloud-model connector leaked into mobile cloud contacts. Store dump: ${storeDump}`);
    }

    const chatXml = dumpWindowTo(chatDump, "signalasi-cloud-models-chat.xml");
    if (!chatXml.includes("DeepSeek") || !chatXml.includes("DeepSeek V4 Flash")) {
      fail(`Cloud chat header did not show provider and selected model. Dump saved at ${chatDump}`);
    }
    log("OK: direct mobile cloud providers, multiple models, and selected model persistence verified on device");
  } finally {
    adb(["shell", "am", "force-stop", packageName]);
    log("restoring original app state");
    restoreAppFile(appStorePrefs, originalAppStore);
    restoreAppFile(oldAppStorePrefs, originalOldAppStore);
    restoreAppFile(historyPrefs, originalHistory);
    restoreAppFile(debugPrefs, originalDebug);
    adb(["shell", "am", "force-stop", packageName]);
  }
}

main().catch((error) => {
  console.error(`[android-cloud-models-smoke] failed: ${error.stack || error.message || error}`);
  process.exit(1);
});
