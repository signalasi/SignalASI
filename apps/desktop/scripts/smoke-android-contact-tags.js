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
const outDir = path.join(root, "ui-smoke");
const directoryDump = path.join(outDir, "android-contact-tags.xml");

function log(message) {
  console.log(`[android-contact-tags-smoke] ${message}`);
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

function escapeXml(value) {
  return String(value)
    .replace(/&/g, "&amp;")
    .replace(/</g, "&lt;")
    .replace(/>/g, "&gt;")
    .replace(/"/g, "&quot;")
    .replace(/'/g, "&apos;");
}

function appStoreXml(contacts) {
  return [
    "<?xml version='1.0' encoding='utf-8' standalone='yes' ?>",
    "<map>",
    `    <string name="contacts">${escapeXml(JSON.stringify(contacts))}</string>`,
    "    <string name=\"friend_requests\">[]</string>",
    "</map>",
    ""
  ].join("\n");
}

function dumpWindowTo(fileName, remoteName) {
  adb(["shell", "uiautomator", "dump", `/sdcard/${remoteName}`]);
  adb(["pull", `/sdcard/${remoteName}`, fileName]);
  return fs.readFileSync(fileName, "utf8");
}

function includesAny(value, texts) {
  return texts.some((text) => value.includes(text));
}

function requireAnyText(xml, texts, label) {
  if (!includesAny(xml, texts)) {
    fail(`Contacts page did not render ${label}. Dump saved at ${directoryDump}`);
  }
}

async function main() {
  if (!fs.existsSync(apkPath)) {
    fail(`Android debug APK missing. Build it first: ${apkPath}`);
  }
  fs.mkdirSync(outDir, { recursive: true });

  const now = Date.now();
  const contacts = [
    {
      id: "tag-smoke-agent",
      signalasi_id: "tag-smoke-agent",
      name: "Tag Agent Smoke",
      type: "agent",
      agent_kind: "local-cli",
      trust_state: "verified",
      setup_status: "ready",
      created_at: now,
      deleted: false
    },
    {
      id: "cloud:tag-model-smoke",
      signalasi_id: "cloud:tag-model-smoke",
      name: "Tag Model Smoke",
      type: "agent",
      agent_kind: "cloud-api",
      delivery_mode: "cloud_api",
      cloud_provider: "Tag Model Smoke",
      trust_state: "verified",
      setup_status: "ready",
      created_at: now,
      deleted: false
    },
    {
      id: "tag-smoke-device",
      signalasi_id: "tag-smoke-device",
      name: "Tag Device Smoke",
      type: "device",
      agent_kind: "device",
      trust_state: "verified",
      setup_status: "ready",
      created_at: now,
      deleted: false
    }
  ];

  log("installing debug APK");
  adb(["install", "-r", apkPath], { stdio: "inherit" });
  adb(["shell", "input", "keyevent", "KEYCODE_WAKEUP"]);
  adb(["shell", "am", "force-stop", packageName]);

  const originalAppStore = readAppFile(appStorePrefs);
  const originalOldAppStore = readAppFile(oldAppStorePrefs);

  try {
    log("seeding isolated Agent, Model, and Device contacts");
    restoreAppFile(appStorePrefs, appStoreXml(contacts));
    restoreAppFile(oldAppStorePrefs, "");
    adb(["shell", "am", "force-stop", packageName]);

    log("opening Contacts page and verifying type tags");
    adb(["shell", "am", "start", "-n", activityName, "--ez", "signalasi_debug_open_contacts", "true"]);
    await sleep(3500);
    const xml = dumpWindowTo(directoryDump, "signalasi-contact-tags.xml");

    requireAnyText(xml, ["Contacts", "\u901a\u8baf\u5f55"], "Contacts title");
    requireAnyText(xml, ["Tag Agent Smoke"], "Agent contact");
    requireAnyText(xml, ["Tag Model Smoke"], "Model contact");
    requireAnyText(xml, ["Tag Device Smoke"], "Device contact");
    requireAnyText(xml, ["Agent"], "Agent type tag");
    requireAnyText(xml, ["Model", "\u6a21\u578b"], "Model type tag");
    requireAnyText(xml, ["Device", "\u8bbe\u5907"], "Device type tag");

    log(`OK: Agent, Model, and Device contact type tags rendered. Dump: ${directoryDump}`);
  } finally {
    adb(["shell", "am", "force-stop", packageName]);
    log("restoring original app store");
    restoreAppFile(appStorePrefs, originalAppStore);
    restoreAppFile(oldAppStorePrefs, originalOldAppStore);
    adb(["shell", "am", "force-stop", packageName]);
  }
}

main().catch((error) => {
  console.error(`[android-contact-tags-smoke] failed: ${error.stack || error.message || error}`);
  process.exit(1);
});
