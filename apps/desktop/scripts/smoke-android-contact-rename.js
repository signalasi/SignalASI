const fs = require("node:fs");
const path = require("node:path");
const { createAdb } = require("./android-adb");
const { establishFreshSecurePairing } = require("./android-live-pairing");

const root = path.resolve(__dirname, "..");
const workspaceRoot = path.resolve(root, "..");
const androidDir = path.join(workspaceRoot, "android");
const apkPath = path.join(androidDir, "app", "build", "outputs", "apk", "debug", "app-debug.apk");
const packageName = "com.signalasi.chat";
const activityName = `${packageName}/.MainActivity`;
const appStorePrefs = "shared_prefs/signalasi_app_store.xml";
const outDir = path.join(root, "ui-smoke");
const storeDump = path.join(outDir, "android-contact-rename-app-store.xml");
const detailDump = path.join(outDir, "android-contact-rename-detail.xml");

function log(message) {
  console.log(`[android-contact-rename-smoke] ${message}`);
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

function prefString(xml, name, fallback) {
  const pattern = new RegExp(`<string name="${name}">([\\s\\S]*?)<\\/string>`);
  const match = xml.match(pattern);
  return match ? decodeXml(match[1]) : fallback;
}

function readStore() {
  const xml = readAppFile(appStorePrefs);
  return {
    xml,
    contacts: JSON.parse(prefString(xml, "contacts", "[]"))
  };
}

function findCodexContact(store) {
  return store.contacts.find((contact) =>
    contact.agent_id === "codex" ||
    contact.id === "codex" ||
    String(contact.id || "").endsWith(":codex") ||
    String(contact.signalasi_id || "").endsWith(":codex")
  );
}

function startWithExtras(extras) {
  const args = ["shell", "am", "start", "-n", activityName];
  for (const extra of extras) args.push(...extra);
  adb(args);
}

function dumpWindowTo(fileName, remoteName) {
  const remotePath = `/sdcard/${remoteName}`;
  let lastError;
  for (let attempt = 0; attempt < 8; attempt += 1) {
    try {
      adb(["shell", "rm", "-f", remotePath]);
      adb(["shell", "uiautomator", "dump", remotePath]);
      adb(["pull", remotePath, fileName]);
      return fs.readFileSync(fileName, "utf8");
    } catch (error) {
      lastError = error;
      Atomics.wait(new Int32Array(new SharedArrayBuffer(4)), 0, 0, 500);
    }
  }
  throw lastError || new Error(`Unable to capture ${remoteName}`);
}

async function main() {
  if (!fs.existsSync(apkPath)) {
    fail(`Android debug APK missing. Build it first: ${apkPath}`);
  }
  fs.mkdirSync(outDir, { recursive: true });
  const renamed = `Codex Renamed Smoke ${Date.now()}`;
  const renamedB64 = Buffer.from(renamed, "utf8").toString("base64");

  log("installing debug APK");
  adb(["install", "-r", apkPath], { stdio: "inherit" });
  adb(["shell", "input", "keyevent", "KEYCODE_WAKEUP"]);
  adb(["shell", "am", "force-stop", packageName]);

  let pairedAppStore = "";

  try {
    log("pairing with the live Desktop and waiting for its verified Codex contact");
    await establishFreshSecurePairing({ adb, packageName, activityName, log });
    let store = { xml: "", contacts: [] };
    let contact;
    const contactDeadline = Date.now() + 20_000;
    while (Date.now() < contactDeadline) {
      store = readStore();
      contact = findCodexContact(store);
      if (contact) break;
      await sleep(500);
    }
    if (!contact) {
      fs.writeFileSync(storeDump, store.xml || "");
      fail(`Live Desktop did not publish a Codex connector contact. Store dump: ${storeDump}`);
    }
    pairedAppStore = store.xml;
    const resolvedContactId = contact.signalasi_id || contact.id;
    if (!resolvedContactId) {
      fs.writeFileSync(storeDump, store.xml || "");
      fail(`Codex connector contact did not include an id. Store dump: ${storeDump}`);
    }
    const originalIdentity = {
      agentId: contact.agent_id,
      desktopId: contact.desktop_id,
      deliveryMode: contact.delivery_mode,
      defaultDisplayName: contact.default_display_name
    };

    log("renaming Codex contact and opening contact detail page");
    startWithExtras([
      ["--es", "signalasi_debug_rename_contact", resolvedContactId],
      ["--es", "signalasi_debug_rename_name_b64", renamedB64],
      ["--es", "signalasi_debug_open_contact_detail", resolvedContactId]
    ]);
    await sleep(2500);

    store = readStore();
    fs.writeFileSync(storeDump, store.xml || "");
    contact = store.contacts.find((item) => item.id === resolvedContactId || item.signalasi_id === resolvedContactId);
    if (!contact) {
      fail(`Renamed contact disappeared. Store dump: ${storeDump}`);
    }
    if (contact.name !== renamed || contact.display_name !== renamed) {
      fail(`Renamed contact did not persist display name. Store dump: ${storeDump}`);
    }
    if (contact.user_renamed !== true) {
      fail(`Renamed contact did not persist user_renamed evidence. Store dump: ${storeDump}`);
    }
    if (contact.agent_id !== originalIdentity.agentId ||
        contact.desktop_id !== originalIdentity.desktopId ||
        contact.delivery_mode !== originalIdentity.deliveryMode) {
      fail(`Rename changed immutable connector routing identity. Store dump: ${storeDump}`);
    }
    if (contact.default_display_name !== originalIdentity.defaultDisplayName) {
      fail(`Rename overwrote the connector's default display name. Store dump: ${storeDump}`);
    }

    const detailXml = dumpWindowTo(detailDump, "signalasi-contact-rename-detail.xml");
    if (!detailXml.includes(renamed)) {
      fail(`Contact detail did not show renamed display name. Dump saved at ${detailDump}`);
    }
    if (!detailXml.includes("SignalASI ID") && !detailXml.includes(resolvedContactId.replace(/&/g, "&amp;"))) {
      fail(`Contact detail did not show identity metadata. Dump saved at ${detailDump}`);
    }
    log("OK: contact display name rename persisted and rendered on device");
  } finally {
    adb(["shell", "am", "force-stop", packageName]);
    if (pairedAppStore) {
      log("restoring the paired app store snapshot");
      restoreAppFile(appStorePrefs, pairedAppStore);
    }
    adb(["shell", "am", "force-stop", packageName]);
  }
}

main().catch((error) => {
  console.error(`[android-contact-rename-smoke] failed: ${error.stack || error.message || error}`);
  process.exit(1);
});
