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
const outDir = path.join(root, "ui-smoke");
const storeDump = path.join(outDir, "android-friends-app-store.xml");

function log(message) {
  console.log(`[android-friends-smoke] ${message}`);
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
    contacts: JSON.parse(prefString(xml, "contacts", "[]")),
    friendRequests: JSON.parse(prefString(xml, "friend_requests", "[]"))
  };
}

function findContact(store, id) {
  return store.contacts.find((contact) => contact.signalasi_id === id || contact.id === id);
}

function findRequest(store, id) {
  return store.friendRequests.find((request) => request.signalasi_id === id || request.id === id);
}

function assertNoActiveContact(store, id, stage) {
  const contact = findContact(store, id);
  if (contact && contact.deleted !== true && contact.trust_state === "verified") {
    fail(`${stage}: contact is active before approval`);
  }
}

function assertActiveContact(store, id, stage) {
  const contact = findContact(store, id);
  if (!contact || contact.deleted === true || contact.trust_state !== "verified") {
    fail(`${stage}: contact is not active verified`);
  }
  return contact;
}

function assertDeletedContact(store, id, stage) {
  const contact = findContact(store, id);
  if (!contact || contact.deleted !== true || contact.trust_state !== "deleted") {
    fail(`${stage}: contact is not marked deleted`);
  }
  return contact;
}

function startWithExtras(extras) {
  const args = ["shell", "am", "start", "-n", activityName];
  for (const extra of extras) args.push(...extra);
  adb(args);
}

async function main() {
  if (!fs.existsSync(apkPath)) {
    fail(`Android debug APK missing. Build it first: ${apkPath}`);
  }
  fs.mkdirSync(outDir, { recursive: true });
  const contactId = `signalasi:friend-smoke-${Date.now()}`;
  const qr = {
    type: "signalasi_contact",
    version: 1,
    name: "Friend Smoke",
    signalasi_id: contactId,
    identity_public_key: "SMOKE_PUBLIC_KEY_FOR_FRIEND_READD_FLOW",
    identity_fingerprint: "A1B2C3D4E5F60718293A4B5C6D7E8F90112233445566778899AABBCCDDEEFF00",
    mqtt_topic: `signalasichat/v1/AAAAAAAAAAAAAAAAAAAAAA/BBBBBBBBBBBBBBBBBBBBBB/up`,
    mqtt_inbox_topic: `signalasichat/v1/AAAAAAAAAAAAAAAAAAAAAA/BBBBBBBBBBBBBBBBBBBBBB/down`
  };
  const payloadB64 = Buffer.from(JSON.stringify(qr), "utf8").toString("base64");

  log("installing debug APK");
  adb(["install", "-r", apkPath], { stdio: "inherit" });
  adb(["shell", "input", "keyevent", "KEYCODE_WAKEUP"]);
  adb(["shell", "am", "force-stop", packageName]);

  const originalAppStore = readAppFile(appStorePrefs);

  try {
    log("resetting app store snapshot for isolated friend request flow");
    restoreAppFile(appStorePrefs, "");
    adb(["shell", "am", "force-stop", packageName]);

    log("scanning contact QR: should create a pending New Friend only");
    startWithExtras([
      ["--es", "signalasi_debug_scan_payload_b64", payloadB64]
    ]);
    await sleep(4000);
    let store = readStore();
    fs.writeFileSync(storeDump, store.xml);
    let request = findRequest(store, contactId);
    if (!request || request.status !== "pending") {
      fail(`scan did not create a pending friend request. Store dump: ${storeDump}`);
    }
    assertNoActiveContact(store, contactId, "initial scan");

    log("approving pending request: should create verified contact");
    startWithExtras([
      ["--es", "signalasi_debug_approve_friend", contactId]
    ]);
    await sleep(2500);
    store = readStore();
    request = findRequest(store, contactId);
    if (!request || request.status !== "approved") {
      fail("approval did not mark friend request approved");
    }
    assertActiveContact(store, contactId, "first approval");

    log("deleting contact: communication trust should be revoked");
    startWithExtras([
      ["--es", "signalasi_debug_delete_contact", contactId]
    ]);
    await sleep(2500);
    store = readStore();
    request = findRequest(store, contactId);
    if (!request || request.status !== "deleted" || request.readd_required !== true) {
      fail("delete did not mark the friend request as re-add required");
    }
    assertDeletedContact(store, contactId, "delete");

    log("scanning same QR again: should return to pending New Friend, not active contact");
    startWithExtras([
      ["--es", "signalasi_debug_scan_payload_b64", payloadB64]
    ]);
    await sleep(4000);
    store = readStore();
    request = findRequest(store, contactId);
    if (!request || request.status !== "pending" || request.previously_deleted !== true || request.readd_required !== true) {
      fail("re-scan did not create a pending re-add request");
    }
    assertDeletedContact(store, contactId, "re-scan before approval");

    log("approving re-add request: should restore verified communication");
    startWithExtras([
      ["--es", "signalasi_debug_approve_friend", contactId]
    ]);
    await sleep(2500);
    store = readStore();
    request = findRequest(store, contactId);
    if (!request || request.status !== "approved" || !request.readded_at) {
      fail("re-add approval did not preserve re-added request evidence");
    }
    const contact = assertActiveContact(store, contactId, "re-add approval");
    if (!contact.readded_at) {
      fail("re-added contact did not store readded_at evidence");
    }
    log("OK: New Friends approval and delete/re-add flow verified on device");
  } finally {
    adb(["shell", "am", "force-stop", packageName]);
    log("restoring original app store");
    restoreAppFile(appStorePrefs, originalAppStore);
    adb(["shell", "am", "force-stop", packageName]);
  }
}

main().catch((error) => {
  console.error(`[android-friends-smoke] failed: ${error.stack || error.message || error}`);
  process.exit(1);
});
