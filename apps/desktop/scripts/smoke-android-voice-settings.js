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
const voicePrefs = "shared_prefs/signalasi_voice_assistant.xml";
const debugPrefs = "shared_prefs/signalasi_debug.xml";
const outDir = path.join(root, "ui-smoke");
const debugDump = path.join(outDir, "android-voice-settings-debug.xml");
const voicePrefsDump = path.join(outDir, "android-voice-settings-prefs.xml");
const settingsDump = path.join(outDir, "android-voice-settings-page.xml");

function log(message) {
  console.log(`[android-voice-settings-smoke] ${message}`);
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
    const raw = prefString(xml, "voice_settings_roundtrip_result", "");
    if (!raw) continue;
    const result = JSON.parse(raw);
    if (result.token === token) return { xml, result };
  }
  return { xml: readAppFile(debugPrefs), result: null };
}

function dumpWindowTo(fileName, remoteName) {
  adb(["shell", "uiautomator", "dump", `/sdcard/${remoteName}`]);
  adb(["pull", `/sdcard/${remoteName}`, fileName]);
  return fs.readFileSync(fileName, "utf8");
}

async function collectSettingsXml() {
  let combined = "";
  for (let reset = 0; reset < 2; reset += 1) {
    adb(["shell", "input", "swipe", "520", "520", "520", "1900", "350"]);
    await sleep(400);
  }
  combined += dumpWindowTo(settingsDump, "signalasi-voice-settings.xml");
  for (let scroll = 0; scroll < 4; scroll += 1) {
    adb(["shell", "input", "swipe", "520", "1900", "520", "520", "450"]);
    await sleep(600);
    combined += dumpWindowTo(settingsDump, "signalasi-voice-settings.xml");
  }
  fs.writeFileSync(settingsDump, combined, "utf8");
  return combined;
}

function requireText(value, text, dumpPath) {
  if (!value.includes(text)) {
    fail(`Expected ${text}. Dump saved at ${dumpPath}`);
  }
}

async function main() {
  if (!fs.existsSync(apkPath)) {
    fail(`Android debug APK missing. Build it first: ${apkPath}`);
  }
  fs.mkdirSync(outDir, { recursive: true });
  const token = `VOICE_SETTINGS_${Date.now()}`;

  log("installing debug APK");
  adb(["install", "-r", apkPath], { stdio: "inherit" });
  adb(["shell", "input", "keyevent", "KEYCODE_WAKEUP"]);
  adb(["shell", "am", "force-stop", packageName]);

  const originalAppStore = readAppFile(appStorePrefs);
  const originalOldAppStore = readAppFile(oldAppStorePrefs);
  const originalVoice = readAppFile(voicePrefs);
  const originalDebug = readAppFile(debugPrefs);

  try {
    log("resetting isolated voice settings state");
    restoreAppFile(appStorePrefs, "");
    restoreAppFile(oldAppStorePrefs, "");
    restoreAppFile(voicePrefs, "");
    restoreAppFile(debugPrefs, "");
    adb(["shell", "am", "force-stop", packageName]);

    log("writing non-default wake, ASR, TTS, welcome, and target settings");
    adb(["shell", "am", "start", "-n", activityName, "--es", "signalasi_debug_voice_settings_roundtrip", token]);
    const { xml, result } = await waitForRoundtrip(token);
    fs.writeFileSync(debugDump, xml || "");
    if (!result) {
      fail(`Voice settings roundtrip did not report a result. Dump saved at ${debugDump}`);
    }
    if (!result.ok) {
      fail(`Voice settings roundtrip failed: ${JSON.stringify(result)}. Dump saved at ${debugDump}`);
    }
    if (result.wake_provider !== "android_asr" || result.asr_provider !== "android_asr" || result.tts_provider !== "android") {
      fail(`Voice providers were not persisted: ${JSON.stringify(result)}`);
    }
    if (result.asr_language !== "en-US" || result.speak_replies !== false) {
      fail(`Voice language or speak-replies settings were not persisted: ${JSON.stringify(result)}`);
    }
    if (!String(result.welcome_text || "").includes(token) || !String(result.target_contact_id || "").endsWith(":codex")) {
      fail(`Voice welcome text or target contact did not persist: ${JSON.stringify(result)}`);
    }

    const prefsXml = readAppFile(voicePrefs);
    fs.writeFileSync(voicePrefsDump, prefsXml || "");
    for (const text of ["android_asr", "en-US", "android", "zh-CN-XiaoxiaoNeural", token]) {
      requireText(prefsXml, text, voicePrefsDump);
    }

    const settingsXml = await collectSettingsXml();
    for (const text of [
      "Android",
      "ASR",
      "en-US",
      "TTS",
      "zh-CN-XiaoxiaoNeural",
      token,
      "Codex Agent"
    ]) {
      requireText(settingsXml, text, settingsDump);
    }
    log("OK: voice wake, ASR, TTS, welcome, speak-reply, and target settings persisted and rendered on device");
  } finally {
    adb(["shell", "am", "force-stop", packageName]);
    log("restoring original app state");
    restoreAppFile(appStorePrefs, originalAppStore);
    restoreAppFile(oldAppStorePrefs, originalOldAppStore);
    restoreAppFile(voicePrefs, originalVoice);
    restoreAppFile(debugPrefs, originalDebug);
    adb(["shell", "am", "force-stop", packageName]);
  }
}

main().catch((error) => {
  console.error(`[android-voice-settings-smoke] failed: ${error.stack || error.message || error}`);
  process.exit(1);
});
