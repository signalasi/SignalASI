const fs = require("node:fs");
const path = require("node:path");
const { createAdb } = require("./android-adb");

const root = path.resolve(__dirname, "..");
const workspaceRoot = path.resolve(root, "..");
const androidDir = path.join(workspaceRoot, "android");
const apkPath = path.join(androidDir, "app", "build", "outputs", "apk", "debug", "app-debug.apk");
const packageName = "com.signalasi.chat";
const activityName = `${packageName}/.MainActivity`;
const serviceName = `${packageName}/.MessageService`;
const outDir = path.join(root, "ui-smoke");
const windowDump = path.join(outDir, "android-background-message.xml");
const foregroundHistoryDump = path.join(outDir, "android-background-history.xml");
const historyPrefs = "shared_prefs/signalasi_chat_history.xml";
const appStorePrefs = "shared_prefs/signalasi_app_store.xml";
const trustPrefs = "shared_prefs/signalasi_signal_trust.xml";

function log(message) {
  console.log(`[android-bg-smoke] ${message}`);
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

async function main() {
  if (!fs.existsSync(apkPath)) {
    fail(`Android debug APK missing. Build it first: ${apkPath}`);
  }
  fs.mkdirSync(outDir, { recursive: true });
  const token = `BG_SERVICE_${Date.now()}`;
  const payload = token;

  log("installing debug APK");
  adb(["install", "-r", apkPath], { stdio: "inherit" });
  adb(["shell", "input", "keyevent", "KEYCODE_WAKEUP"]);

  log("backing up app state and initializing a debug pairing");
  const originalHistory = readAppFile(historyPrefs);
  const originalAppStore = readAppFile(appStorePrefs);
  const originalTrust = readAppFile(trustPrefs);
  adb(["shell", "am", "start", "-n", activityName, "--ez", "signalasi_debug_pairing", "true"]);
  await sleep(2500);

  try {
    log("moving app to background and injecting service message");
    adb(["shell", "input", "keyevent", "KEYCODE_HOME"]);
    await sleep(2000);
    adb([
      "shell",
      "run-as",
      packageName,
      "am",
      "startservice",
      "--user",
      "0",
      "-n",
      serviceName,
      "--es",
      "signalasi_debug_service_payload",
      payload
    ]);
    await sleep(2500);

    const updatedHistory = readAppFile(historyPrefs);
    if (!updatedHistory.includes(token)) {
      fail(`Background service did not persist incoming token ${token}`);
    }
    if (!updatedHistory.includes("hermes")) {
      fail("Background service history did not store the Hermes contact bucket");
    }
    if (!updatedHistory.includes("background_history") || !updatedHistory.includes("notified")) {
      fail("Background service history did not record persisted/notified delivery trace stages");
    }
    log("background service persisted incoming message");

    log("returning to foreground and verifying visible refresh");
    adb(["shell", "am", "start", "-n", activityName, "--es", "signalasi_debug_open_contact", "hermes"]);
    await sleep(3500);
    adb(["shell", "uiautomator", "dump", "/sdcard/signalasi-bg-window.xml"]);
    adb(["pull", "/sdcard/signalasi-bg-window.xml", windowDump]);
    const historyAfterForeground = readAppFile(historyPrefs);
    fs.writeFileSync(foregroundHistoryDump, historyAfterForeground);
    const xml = fs.readFileSync(windowDump, "utf8");
    if (!xml.includes(token)) {
      fail(`Foreground UI did not show background token ${token}. historyHasToken=${historyAfterForeground.includes(token)} historyHasRead=${historyAfterForeground.includes("read")}. Dumps saved at ${windowDump} and ${foregroundHistoryDump}`);
    }
    let foregroundHistory = "";
    for (let i = 0; i < 5; i += 1) {
      foregroundHistory = readAppFile(historyPrefs);
      if (foregroundHistory.includes("read")) break;
      await sleep(500);
    }
    if (!foregroundHistory.includes("read")) {
      fail("Foreground chat open did not record read delivery trace stage");
    }
    log(`foreground UI refreshed with background message. Window dump: ${windowDump}`);
  } finally {
    adb(["shell", "am", "force-stop", packageName]);
    log("restoring original app state");
    restoreAppFile(historyPrefs, originalHistory);
    restoreAppFile(appStorePrefs, originalAppStore);
    restoreAppFile(trustPrefs, originalTrust);
    adb(["shell", "am", "force-stop", packageName]);
  }
}

main().catch((error) => {
  console.error(`[android-bg-smoke] failed: ${error.stack || error.message || error}`);
  process.exit(1);
});
