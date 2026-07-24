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
const serviceName = `${packageName}/.MessageService`;
const outDir = path.join(root, "ui-smoke");
const windowDump = path.join(outDir, "android-background-message.xml");
const listDump = path.join(outDir, "android-background-list.xml");
const foregroundHistoryDump = path.join(outDir, "android-background-history.json");
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

function dumpWindow(remoteName, targetPath) {
  adb(["shell", "uiautomator", "dump", `/sdcard/${remoteName}`]);
  adb(["pull", `/sdcard/${remoteName}`, targetPath]);
  return fs.readFileSync(targetPath, "utf8");
}

function minuteText(offsetMinutes = 0) {
  const now = new Date(Date.now() + offsetMinutes * 60 * 1000);
  return `${String(now.getHours()).padStart(2, "0")}:${String(now.getMinutes()).padStart(2, "0")}`;
}

function includesAny(value, items) {
  return items.some((item) => value.includes(item));
}

async function main() {
  if (!fs.existsSync(apkPath)) {
    fail(`Android debug APK missing. Build it first: ${apkPath}`);
  }
  fs.mkdirSync(outDir, { recursive: true });
  const token = `BG_SERVICE_${Date.now()}`;
  const agentId = `background-smoke-${Date.now()}`;
  const contactId = `desktop_background_smoke:${agentId}`;
  const payload = JSON.stringify({ sender: contactId, contact_id: contactId, content: token });

  log("installing debug APK");
  adb(["install", "-r", apkPath], { stdio: "inherit" });
  adb(["shell", "input", "keyevent", "KEYCODE_WAKEUP"]);

  log("backing up app state and initializing a debug pairing");
  const originalAppStore = readAppFile(appStorePrefs);
  const originalTrust = readAppFile(trustPrefs);
  log("resetting app state for isolated background delivery flow");
  restoreAppFile(appStorePrefs, "");
  restoreAppFile(trustPrefs, "");
  adb(["shell", "am", "force-stop", packageName]);
  adb(["shell", "am", "start", "-n", activityName, "--ez", "signalasi_debug_pairing", "true"]);
  await sleep(2500);
  const connectorStatus = Buffer.from(JSON.stringify({
    type: "connector_status",
    content: "background smoke connector ready",
    connector_agents: [{
      id: contactId,
      agent_id: agentId,
      name: "Background Smoke",
      display_name: "Background Smoke",
      desktop_id: "desktop_background_smoke",
      desktop_name: "Test Desktop",
      kind: "custom-cli",
      status: "ready",
      detail: "background smoke ready",
      setup: "Ready"
    }]
  }), "utf8").toString("base64");
  adb(["shell", "am", "start", "-n", activityName, "--es", "signalasi_debug_incoming_b64", connectorStatus]);
  await sleep(1000);

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
      "signalasi_debug_service_payload_b64",
      Buffer.from(payload, "utf8").toString("base64")
    ]);
    const backgroundProbe = await probeChatHistory({
      adb,
      packageName,
      activityName,
      contactId,
      contentToken: token,
      requiredStages: ["received", "decrypted", "persisted", "notified"]
    });
    requireProbeMatch(backgroundProbe, "background service history");
    log("background service persisted incoming message");

    log("returning to messages list and verifying unread badge plus list timestamp");
    const expectedTimes = [minuteText(), minuteText(-1)];
    adb(["shell", "am", "start", "-n", activityName, "--ez", "signalasi_debug_open_messages", "true"]);
    await sleep(3500);
    const listXml = dumpWindow("signalasi-bg-list.xml", listDump);
    if (!listXml.includes(token)) {
      fail(`Messages list did not show background token preview ${token}. Dump saved at ${listDump}`);
    }
    if (!listXml.includes('text="1" resource-id="com.signalasi.chat:id/unreadBadge"')) {
      fail(`Messages list did not show unread badge 1. Dump saved at ${listDump}`);
    }
    if (!includesAny(listXml, expectedTimes)) {
      fail(`Messages list did not show current list timestamp ${expectedTimes.join(" or ")}. Dump saved at ${listDump}`);
    }

    log("opening chat and verifying message timestamp plus read trace");
    adb(["shell", "am", "start", "-n", activityName, "--es", "signalasi_debug_open_contact", contactId]);
    await sleep(3500);
    const xml = dumpWindow("signalasi-bg-window.xml", windowDump);
    if (!xml.includes(token)) {
      fail(`Foreground UI did not show background token ${token}. Dump saved at ${windowDump}`);
    }
    if (!includesAny(xml, expectedTimes)) {
      fail(`Foreground chat did not show bubble or divider timestamp ${expectedTimes.join(" or ")}. Dump saved at ${windowDump}`);
    }
    const foregroundProbe = await probeChatHistory({
      adb,
      packageName,
      activityName,
      contactId,
      contentToken: token
    });
    fs.writeFileSync(foregroundHistoryDump, `${JSON.stringify(foregroundProbe, null, 2)}\n`);
    requireProbeMatch(foregroundProbe, "foreground read history");
    if (Number(foregroundProbe.matched_read_count || 0) !== 1) {
      fail("Foreground chat open did not mark the background message as read");
    }
    log(`foreground UI refreshed with background message. Window dump: ${windowDump}`);
  } finally {
    try {
      await probeChatHistory({
        adb,
        packageName,
        activityName,
        contactId,
        contentToken: token,
        deleteMatches: true
      });
    } catch {
      // Preserve the original failure; a unique smoke token cannot affect normal history.
    }
    adb(["shell", "am", "force-stop", packageName]);
    log("restoring original app state");
    restoreAppFile(appStorePrefs, originalAppStore);
    restoreAppFile(trustPrefs, originalTrust);
    adb(["shell", "am", "force-stop", packageName]);
  }
}

main().catch((error) => {
  console.error(`[android-bg-smoke] failed: ${error.stack || error.message || error}`);
  process.exit(1);
});
