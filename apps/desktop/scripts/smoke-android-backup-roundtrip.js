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
const historyPrefs = "shared_prefs/signalasi_chat_history.xml";
const trustPrefs = "shared_prefs/signalasi_signal_trust.xml";
const signalStorePrefs = "shared_prefs/signalasi_signal_store.xml";
const debugPrefs = "shared_prefs/signalasi_debug.xml";
const outDir = path.join(root, "ui-smoke");
const debugDump = path.join(outDir, "android-backup-roundtrip-debug.xml");

function log(message) {
  console.log(`[android-backup-smoke] ${message}`);
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
    const raw = prefString(xml, "backup_roundtrip_result", "");
    if (!raw) continue;
    const result = JSON.parse(raw);
    if (result.token === token) return { xml, result };
  }
  return { xml: readAppFile(debugPrefs), result: null };
}

async function main() {
  if (!fs.existsSync(apkPath)) {
    fail(`Android debug APK missing. Build it first: ${apkPath}`);
  }
  fs.mkdirSync(outDir, { recursive: true });
  const token = `BACKUP_${Date.now()}`;

  log("installing debug APK");
  adb(["install", "-r", apkPath], { stdio: "inherit" });
  adb(["shell", "input", "keyevent", "KEYCODE_WAKEUP"]);
  adb(["shell", "am", "force-stop", packageName]);

  const originalAppStore = readAppFile(appStorePrefs);
  const originalHistory = readAppFile(historyPrefs);
  const originalTrust = readAppFile(trustPrefs);
  const originalSignalStore = readAppFile(signalStorePrefs);
  const originalDebug = readAppFile(debugPrefs);

  try {
    log("running debug backup export/import roundtrip");
    adb(["shell", "am", "start", "-n", activityName, "--es", "signalasi_debug_backup_roundtrip", token]);
    const { xml, result } = await waitForRoundtrip(token);
    fs.writeFileSync(debugDump, xml || "");
    if (!result) {
      fail(`Backup roundtrip did not report a result. Dump saved at ${debugDump}`);
    }
    if (!result.ok || !result.encrypted_backup || !result.contact_restored || !result.message_restored) {
      fail(`Backup roundtrip failed: ${JSON.stringify(result)}. Dump saved at ${debugDump}`);
    }
    log("OK: encrypted backup exported and restored contacts plus messages on device");
  } finally {
    adb(["shell", "am", "force-stop", packageName]);
    log("restoring original app state");
    restoreAppFile(appStorePrefs, originalAppStore);
    restoreAppFile(historyPrefs, originalHistory);
    restoreAppFile(trustPrefs, originalTrust);
    restoreAppFile(signalStorePrefs, originalSignalStore);
    restoreAppFile(debugPrefs, originalDebug);
    adb(["shell", "am", "force-stop", packageName]);
  }
}

main().catch((error) => {
  console.error(`[android-backup-smoke] failed: ${error.stack || error.message || error}`);
  process.exit(1);
});
