const { execFileSync } = require("node:child_process");
const fs = require("node:fs");
const path = require("node:path");

const root = path.resolve(__dirname, "..");
const workspaceRoot = path.resolve(root, "..");
const androidDir = path.join(workspaceRoot, "android");
const apkPath = path.join(androidDir, "app", "build", "outputs", "apk", "debug", "app-debug.apk");
const packageName = "com.signalasi.chat";
const activityName = `${packageName}/.MainActivity`;
const outDir = path.join(root, "ui-smoke");
const voiceDump = path.join(outDir, "android-voice-reply.xml");
const chatDump = path.join(outDir, "android-voice-reply-chat.xml");
const historyDump = path.join(outDir, "android-voice-reply-history.xml");
const historyPrefs = "shared_prefs/signalasi_chat_history.xml";
const appStorePrefs = "shared_prefs/signalasi_app_store.xml";
const trustPrefs = "shared_prefs/signalasi_signal_trust.xml";

function log(message) {
  console.log(`[android-voice-reply-smoke] ${message}`);
}

function fail(message) {
  throw new Error(message);
}

function adb(args, options = {}) {
  return execFileSync("adb", args, {
    cwd: options.cwd || root,
    encoding: options.encoding || "utf8",
    windowsHide: true,
    input: options.input,
    stdio: options.stdio || ["pipe", "pipe", "pipe"]
  });
}

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

function dumpWindow(remoteName, targetPath) {
  adb(["shell", "uiautomator", "dump", `/sdcard/${remoteName}`]);
  adb(["pull", `/sdcard/${remoteName}`, targetPath]);
  return fs.readFileSync(targetPath, "utf8");
}

async function waitForWindowText(targetPath, remoteName, requiredText, attempts = 10) {
  let xml = "";
  for (let attempt = 0; attempt < attempts; attempt += 1) {
    await sleep(700);
    xml = dumpWindow(remoteName, targetPath);
    if (xml.includes(requiredText)) return xml;
  }
  return xml;
}

async function main() {
  if (!fs.existsSync(apkPath)) {
    fail(`Android debug APK missing. Build it first: ${apkPath}`);
  }
  fs.mkdirSync(outDir, { recursive: true });

  const token = `VOICE_REPLY_TAIL_${Date.now()}`;
  const longReply = [
    "SignalASI voice reply smoke.",
    "This message intentionally spans many words so the voice response panel must preserve the complete assistant answer.",
    "Segment alpha confirms the beginning.",
    "Segment beta confirms the middle.",
    "Segment gamma confirms the lower panel can scroll without truncating the reply.",
    "Segment delta confirms chat history stores the same text.",
    token
  ].join(" ");
  const payloadB64 = Buffer.from(JSON.stringify({
    sender: "hermes",
    contact_id: "hermes",
    content: longReply,
    delivery_trace: [
      { stage: "desktop_reply_publish_queued", detail: "smoke" },
      { stage: "desktop_reply_broker_ack", detail: "smoke" }
    ]
  }), "utf8").toString("base64");

  log("installing debug APK");
  adb(["install", "-r", apkPath], { stdio: "inherit" });
  adb(["shell", "input", "keyevent", "KEYCODE_WAKEUP"]);
  adb(["shell", "am", "force-stop", packageName]);

  const originalHistory = readAppFile(historyPrefs);
  const originalAppStore = readAppFile(appStorePrefs);
  const originalTrust = readAppFile(trustPrefs);

  try {
    log("opening voice page with debug pairing");
    adb(["shell", "am", "start", "-n", activityName, "--ez", "signalasi_debug_pairing", "true"]);
    await sleep(2500);

    log("injecting a long Hermes reply and verifying the voice reply panel keeps the tail");
    adb(["shell", "am", "start", "-n", activityName, "--es", "signalasi_debug_incoming_b64", payloadB64]);
    await sleep(1200);
    const voiceXml = await waitForWindowText(voiceDump, "signalasi-voice-reply.xml", token, 12);
    if (!voiceXml.includes(token)) {
      fail(`Voice reply panel did not show the complete reply tail ${token}. Dump saved at ${voiceDump}`);
    }

    log("opening Hermes chat and verifying the same reply is persisted");
    adb(["shell", "am", "start", "-n", activityName, "--es", "signalasi_debug_open_contact", "hermes"]);
    const chatXml = await waitForWindowText(chatDump, "signalasi-voice-reply-chat.xml", token, 12);
    const history = readAppFile(historyPrefs);
    fs.writeFileSync(historyDump, history);
    if (!chatXml.includes(token)) {
      fail(`Hermes chat did not show the complete reply tail ${token}. Dumps saved at ${chatDump} and ${historyDump}`);
    }
    if (!history.includes(token) || !history.includes("desktop_reply_publish_queued") || !history.includes("desktop_reply_broker_ack")) {
      fail(`Chat history did not persist the complete reply and delivery trace. Dump saved at ${historyDump}`);
    }

    log(`OK: voice reply panel and Hermes chat preserve full replies. Dumps: ${voiceDump}, ${chatDump}`);
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
  console.error(`[android-voice-reply-smoke] failed: ${error.stack || error.message || error}`);
  process.exit(1);
});
