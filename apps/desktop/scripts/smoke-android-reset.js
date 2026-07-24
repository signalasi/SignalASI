const fs = require("node:fs");
const path = require("node:path");
const { createAdb } = require("./android-adb");
const { probeChatHistory, requireProbeMatch } = require("./android-chat-history-probe");

const root = path.resolve(__dirname, "..");
const workspaceRoot = path.resolve(root, "..");
const androidDir = path.join(workspaceRoot, "android");
const apkPath = path.join(androidDir, "app", "build", "outputs", "apk", "debug", "app-debug.apk");
const packageName = "com.signalasi.chat";
const activityName = `${packageName}/.MainActivity`;
const appStorePrefs = "shared_prefs/signalasi_app_store.xml";
const trustPrefs = "shared_prefs/signalasi_signal_trust.xml";
const signalStorePrefs = "shared_prefs/signalasi_signal_store.xml";

function log(message) {
  console.log(`[android-reset-smoke] ${message}`);
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

async function waitForChangedSignalStore(before) {
  for (let attempt = 0; attempt < 10; attempt += 1) {
    const current = readAppFile(signalStorePrefs);
    if (current && current !== before) return current;
    await sleep(700);
  }
  return readAppFile(signalStorePrefs);
}

async function main() {
  if (process.env.SIGNALASI_ALLOW_DESTRUCTIVE_RESET !== "1") {
    fail("Destructive reset smoke is disabled. Run only on a disposable test device with SIGNALASI_ALLOW_DESTRUCTIVE_RESET=1.");
  }
  if (!fs.existsSync(apkPath)) {
    fail(`Android debug APK missing. Build it first: ${apkPath}`);
  }

  log("installing debug APK");
  adb(["install", "-r", apkPath], { stdio: "inherit" });
  adb(["shell", "input", "keyevent", "KEYCODE_WAKEUP"]);
  adb(["shell", "am", "force-stop", packageName]);
  adb(["shell", "am", "start", "-n", activityName]);
  await sleep(2500);

  log("seeding contacts before destructive reset");
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
    "true"
  ]);
  await sleep(2500);
  const seededAppStore = readAppFile(appStorePrefs);
  if (!seededAppStore.includes("pc_connector") || !seededAppStore.includes("Codex Agent")) {
    fail("Debug setup did not create connector contacts before reset");
  }

  log("executing debug destructive reset");
  const beforeResetSignalStore = readAppFile(signalStorePrefs);
  adb(["shell", "am", "start", "-n", activityName, "--ez", "signalasi_debug_destroy_all_data", "true"]);
  await sleep(3000);

  const afterSignalStore = await waitForChangedSignalStore(beforeResetSignalStore);
  const afterAppStore = readAppFile(appStorePrefs);
  const afterTrust = readAppFile(trustPrefs);
  const welcomeHistory = await probeChatHistory({
    adb,
    packageName,
    activityName,
    contactId: "system",
    contentToken: "SignalASI"
  });

  if (!afterSignalStore || afterSignalStore === beforeResetSignalStore) {
    fail("Destructive reset did not rotate the local Signal identity store");
  }
  if (afterAppStore.includes("pc_connector") || afterAppStore.includes("Codex Agent") || afterAppStore.includes("Hermes Agent")) {
    fail("Destructive reset left connector contacts in the app store");
  }
  if (!afterAppStore.includes("<string name=\"contacts\">[]</string>")) {
    fail("Destructive reset did not leave an empty contacts list");
  }
  if (!afterAppStore.includes("<string name=\"friend_requests\">[]</string>")) {
    fail("Destructive reset did not leave an empty New Friends list");
  }
  if (afterTrust.includes("verified_pc_identity_sha256") || afterTrust.includes("verified_desktop_identity_sha256")) {
    fail("Destructive reset left trusted PC fingerprints");
  }
  requireProbeMatch(welcomeHistory, "fresh-install welcome history");

  log("destructive reset rotated identity, cleared contacts/trust, and recreated welcome notification");
  adb(["shell", "am", "force-stop", packageName]);
}

main().catch((error) => {
  console.error(`[android-reset-smoke] failed: ${error.stack || error.message || error}`);
  process.exit(1);
});
