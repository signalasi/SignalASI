const fs = require("node:fs");
const path = require("node:path");
const { createAdb } = require("./android-adb");

const root = path.resolve(__dirname, "..");
const workspaceRoot = path.resolve(root, "..");
const androidDir = path.join(workspaceRoot, "android");
const apkPath = path.join(androidDir, "app", "build", "outputs", "apk", "debug", "app-debug.apk");
const packageName = "com.signalasi.chat";
const activityName = `${packageName}/.MainActivity`;
const languagePrefs = "shared_prefs/signalasi_language.xml";
const outDir = path.join(root, "ui-smoke");
const defaultDump = path.join(outDir, "android-language-default.xml");
const zhDump = path.join(outDir, "android-language-zh.xml");
const enDump = path.join(outDir, "android-language-en.xml");

function log(message) {
  console.log(`[android-language-smoke] ${message}`);
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

function dumpWindowTo(fileName, remoteName) {
  const remotePath = `/sdcard/${remoteName}`;
  let lastError = null;
  for (let attempt = 0; attempt < 4; attempt += 1) {
    try {
      adb(["shell", "rm", "-f", remotePath]);
      adb(["shell", "input", "keyevent", "KEYCODE_WAKEUP"]);
      adb(["shell", "wm", "dismiss-keyguard"]);
      adb(["shell", "uiautomator", "dump", remotePath]);
      adb(["pull", remotePath, fileName]);
      const xml = fs.readFileSync(fileName, "utf8");
      if (xml.includes("<hierarchy")) return xml;
    } catch (error) {
      lastError = error;
    }
    Atomics.wait(new Int32Array(new SharedArrayBuffer(4)), 0, 0, 400);
  }
  throw lastError || new Error(`Could not capture ${remoteName}`);
}

function requireText(xml, text, label, dumpPath) {
  if (!xml.includes(text)) {
    fail(`Language page did not render ${label}. Dump saved at ${dumpPath}`);
  }
}

function escapeRegExp(value) {
  return String(value).replace(/[.*+?^${}()|[\]\\]/g, "\\$&");
}

function tapText(xml, text, label, dumpPath) {
  const pattern = new RegExp(`text="${escapeRegExp(text)}"[^>]*bounds="\\[(\\d+),(\\d+)\\]\\[(\\d+),(\\d+)\\]"`);
  const match = xml.match(pattern);
  if (!match) {
    fail(`Language page did not expose tappable text for ${label}. Dump saved at ${dumpPath}`);
  }
  const left = Number(match[1]);
  const top = Number(match[2]);
  const right = Number(match[3]);
  const bottom = Number(match[4]);
  const x = Math.round((left + right) / 2);
  const y = Math.round((top + bottom) / 2);
  adb(["shell", "input", "tap", String(x), String(y)]);
}

async function openLanguagePage(dumpPath, remoteName) {
  let xml = "";
  for (let attempt = 0; attempt < 4; attempt += 1) {
    adb(["shell", "am", "force-stop", packageName]);
    adb([
      "shell",
      "am",
      "start",
      "-n",
      activityName,
      "--ez",
      "signalasi_debug_open_language_settings",
      "true"
    ]);
    await sleep(3500 + attempt * 500);
    xml = dumpWindowTo(dumpPath, remoteName);
    if (xml.includes("Language") || xml.includes("\u8bed\u8a00")) return xml;
  }
  return xml;
}

function requireEnglish(xml, dumpPath) {
  requireText(xml, "Language", "English title", dumpPath);
  requireText(xml, "Choose the display language for SignalASI", "English subtitle", dumpPath);
  requireText(xml, "Current language", "English current-language row", dumpPath);
  requireText(xml, "English", "English option", dumpPath);
  requireText(xml, "Selected", "English selected state", dumpPath);
}

function requireSimplifiedChinese(xml, dumpPath) {
  requireText(xml, "\u8bed\u8a00", "Simplified Chinese title", dumpPath);
  requireText(xml, "\u9009\u62e9 SignalASI \u7684\u663e\u793a\u8bed\u8a00", "Simplified Chinese subtitle", dumpPath);
  requireText(xml, "\u5f53\u524d\u8bed\u8a00", "Simplified Chinese current-language row", dumpPath);
  requireText(xml, "\u7b80\u4f53\u4e2d\u6587", "Simplified Chinese option", dumpPath);
  requireText(xml, "\u5df2\u9009\u62e9", "Simplified Chinese selected state", dumpPath);
}

async function main() {
  if (!fs.existsSync(apkPath)) {
    fail(`Android debug APK missing. Build it first: ${apkPath}`);
  }
  fs.mkdirSync(outDir, { recursive: true });

  log("installing debug APK");
  adb(["install", "-r", apkPath], { stdio: "inherit" });
  adb(["shell", "input", "keyevent", "KEYCODE_WAKEUP"]);
  adb(["shell", "am", "force-stop", packageName]);

  const originalLanguagePrefs = readAppFile(languagePrefs);

  try {
    log("clearing language preference and verifying English default");
    restoreAppFile(languagePrefs, "");
    const defaultXml = await openLanguagePage(defaultDump, "signalasi-language-default.xml");
    requireEnglish(defaultXml, defaultDump);

    log("switching to Simplified Chinese through the Settings UI");
    tapText(defaultXml, "Simplified Chinese", "Simplified Chinese option", defaultDump);
    await sleep(2000);
    const zhPrefs = readAppFile(languagePrefs);
    if (!zhPrefs.includes(">zh-CN<")) {
      fail("Language preference did not persist Simplified Chinese after tapping the Settings row.");
    }
    const zhXml = await openLanguagePage(zhDump, "signalasi-language-zh.xml");
    requireSimplifiedChinese(zhXml, zhDump);

    log("switching back to English through the Settings UI");
    tapText(zhXml, "English", "English option", zhDump);
    await sleep(2000);
    const enPrefs = readAppFile(languagePrefs);
    if (!enPrefs.includes(">en<")) {
      fail("Language preference did not persist English after tapping the Settings row.");
    }
    const enXml = await openLanguagePage(enDump, "signalasi-language-en.xml");
    requireEnglish(enXml, enDump);

    const finalPrefs = readAppFile(languagePrefs);
    if (!finalPrefs.includes(">en<")) {
      fail("Language preference did not persist English after switching back.");
    }

    log(`OK: Android language defaults and switching verified. Dumps: ${defaultDump}, ${zhDump}, ${enDump}`);
  } finally {
    adb(["shell", "am", "force-stop", packageName]);
    log("restoring original language preference");
    restoreAppFile(languagePrefs, originalLanguagePrefs);
    adb(["shell", "am", "force-stop", packageName]);
  }
}

main().catch((error) => {
  console.error(`[android-language-smoke] failed: ${error.stack || error.message || error}`);
  process.exit(1);
});
