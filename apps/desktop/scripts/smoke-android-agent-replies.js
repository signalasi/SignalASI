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
const outDir = path.join(root, "ui-smoke");
const appStorePrefs = "shared_prefs/signalasi_app_store.xml";
const trustPrefs = "shared_prefs/signalasi_signal_trust.xml";
const agents = [
  { id: "hermes", name: "Hermes" },
  { id: "codex", name: "Codex" }
];

function log(message) {
  console.log(`[android-agent-replies-smoke] ${message}`);
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

function dumpWindow(remoteName, targetPath) {
  adb(["shell", "uiautomator", "dump", `/sdcard/${remoteName}`]);
  adb(["pull", `/sdcard/${remoteName}`, targetPath]);
  return fs.readFileSync(targetPath, "utf8");
}

async function waitForWindowText(targetPath, remoteName, requiredText, attempts = 12) {
  let xml = "";
  for (let attempt = 0; attempt < attempts; attempt += 1) {
    await sleep(700);
    xml = dumpWindow(remoteName, targetPath);
    if (xml.includes(requiredText)) return xml;
  }
  return xml;
}

function longReplyFor(agentName, token) {
  return [
    `${agentName} agent reply smoke.`,
    "This reply is intentionally long enough to prove the Android chat bubble and persisted history keep the full answer.",
    "The beginning, middle, and final verification marker must survive contact switching and UI refresh.",
    "Delivery trace evidence must also remain attached to the stored message.",
    token
  ].join(" ");
}

async function verifyAgentReply(agent) {
  const token = `AGENT_REPLY_TAIL_${agent.id}_${Date.now()}`;
  const content = longReplyFor(agent.name, token);
  const payloadB64 = Buffer.from(JSON.stringify({
    sender: agent.id,
    contact_id: agent.id,
    content,
    delivery_trace: [
      { stage: "desktop_reply_publish_queued", detail: `${agent.id} smoke` },
      { stage: "desktop_reply_broker_ack", detail: `${agent.id} smoke` }
    ]
  }), "utf8").toString("base64");
  const dumpPath = path.join(outDir, `android-agent-reply-${agent.id}.xml`);

  try {
    log(`injecting long ${agent.name} reply`);
    adb(["shell", "am", "start", "-n", activityName, "--es", "signalasi_debug_incoming_b64", payloadB64]);
    const persisted = await probeChatHistory({
      adb,
      packageName,
      activityName,
      contactId: agent.id,
      contentToken: token,
      requiredStages: [
        "desktop_reply_publish_queued",
        "desktop_reply_broker_ack",
        "received",
        "decrypted",
        "persisted"
      ]
    });
    requireProbeMatch(persisted, `${agent.name} reply history`);

    log(`opening ${agent.name} chat and verifying full reply tail`);
    adb(["shell", "am", "start", "-n", activityName, "--es", "signalasi_debug_open_contact", agent.id]);
    const xml = await waitForWindowText(dumpPath, `signalasi-agent-reply-${agent.id}.xml`, token);
    const historyDump = path.join(outDir, `android-agent-reply-${agent.id}-history.json`);
    fs.writeFileSync(historyDump, `${JSON.stringify(persisted, null, 2)}\n`);
    if (!xml.includes(token)) {
      fail(`${agent.name} chat did not show the complete reply tail ${token}. Dumps saved at ${dumpPath} and ${historyDump}`);
    }
  } finally {
    try {
      await probeChatHistory({
        adb,
        packageName,
        activityName,
        contactId: agent.id,
        contentToken: token,
        deleteMatches: true
      });
    } catch {
      // Preserve the verification failure; smoke messages use unique tokens.
    }
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
  adb(["shell", "am", "force-stop", packageName]);

  const originalAppStore = readAppFile(appStorePrefs);
  const originalTrust = readAppFile(trustPrefs);

  try {
    log("initializing debug pairing and connector status");
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
    await sleep(3000);

    const appStore = readAppFile(appStorePrefs);
    if (!appStore.includes("Hermes Agent") || !appStore.includes("Codex Agent")) {
      fail("Debug connector status did not create Hermes and Codex contacts before reply verification");
    }

    for (const agent of agents) {
      await verifyAgentReply(agent);
    }

    log("OK: Hermes and Codex chat replies preserve full text and delivery trace evidence");
  } finally {
    adb(["shell", "am", "force-stop", packageName]);
    log("restoring original app state");
    restoreAppFile(appStorePrefs, originalAppStore);
    restoreAppFile(trustPrefs, originalTrust);
    adb(["shell", "am", "force-stop", packageName]);
  }
}

main().catch((error) => {
  console.error(`[android-agent-replies-smoke] failed: ${error.stack || error.message || error}`);
  process.exit(1);
});
