const test = require("node:test");
const assert = require("node:assert/strict");
const { createController, credentialForUpdate, errorLabel } = require("../src/renderer/blob-settings");

class Element {
  constructor() { this.value = ""; this.handlers = {}; this.open = false; }
  addEventListener(name, callback) { (this.handlers[name] ||= []).push(callback); }
  async fire(name) { for (const callback of this.handlers[name] || []) await callback({ preventDefault() {} }); }
  replaceChildren(...options) { this.options = options; this.value = options[0]?.value || ""; }
}
function deferred() {
  let resolve;
  const promise = new Promise((done) => { resolve = done; });
  return { promise, resolve };
}
function fixture(overrides = {}) {
  const elements = new Map();
  const get = (id) => {
    if (!elements.has(id)) elements.set(id, new Element());
    return elements.get(id);
  };
  const doc = { getElementById: get, createElement: () => new Element() };
  const value = (route) => ({ client_route_id: route, identity_fingerprint: "f", identity_binding: "binding",
    revision: 1, enabled: true, origin: "https://relay.test", credential_present: true, client_opted_in: true });
  let saved;
  let clear;
  let resume;
  const api = { getBlobSettings: async (route) => value(route),
    saveBlobSettings: async (route, payload) => { saved = { route, payload: { ...payload } }; return { ...value(route), configuration_queued: true }; },
    onSensitiveStateClear: (callback) => { clear = callback; },
    onSensitiveStateResume: (callback) => { resume = callback; }, ...overrides };
  const controller = createController(doc, api, (key) => key);
  const clients = ["first", "second"].map((id) => ({ paired: true, client_route_id: id, display_name: id }));
  controller.setClients(clients);
  return { get, controller, clients, value, saved: () => saved, clear: () => clear(), resume: () => resume() };
}

test("closed settings do not trigger background configuration reads", () => {
  let calls = 0;
  const f = fixture({ getBlobSettings: async () => { calls++; } });
  assert.equal(calls, 0);
  assert.equal(f.get("blobSettingsSave").disabled, true);
});

test("blank credentials preserve the secret without echoing it into the form", async () => {
  const f = fixture();
  await f.controller.refresh();
  assert.equal(f.get("blobSettingsCredential").value, "");
  await f.get("blobSettingsForm").fire("submit");
  assert.equal(f.saved().payload.provisioning_token, null);
  assert.equal(f.saved().payload.identity_binding, "binding");
  assert.equal(f.get("blobSettingsStatus").textContent, "Configuration queued");
  assert.equal(credentialForUpdate("  abcd  "), "abcd");
});

test("changing selected device rejects late responses from the previous device", async () => {
  const pending = deferred();
  const f = fixture({ getBlobSettings: (route) => route === "first" ? pending.promise : Promise.resolve(f.value(route)) });
  const first = f.controller.refresh();
  f.get("blobSettingsDevice").value = "second";
  await f.controller.refresh();
  pending.resolve({ ...f.value("first"), origin: "https://wrong.test" });
  await first;
  await f.get("blobSettingsForm").fire("submit");
  assert.equal(f.saved().route, "second");
  assert.equal(f.saved().payload.origin, "https://relay.test");
});

test("locking clears credentials and blocks late settings from repopulating the UI", async () => {
  const pending = deferred();
  const f = fixture({ getBlobSettings: () => pending.promise });
  const loading = f.controller.refresh();
  f.get("blobSettingsCredential").value = "private-secret";
  f.clear();
  pending.resolve(f.value("first"));
  await loading;
  assert.equal(f.get("blobSettingsCredential").value, "");
  assert.equal(f.get("blobSettingsOrigin").value, "");
  assert.equal(f.get("blobSettingsSave").disabled, true);
});

test("save targets the captured device and clears the typed credential immediately", async () => {
  const pending = deferred();
  const f = fixture({ saveBlobSettings: () => pending.promise });
  await f.controller.refresh();
  f.get("blobSettingsCredential").value = "private-secret";
  const saving = f.get("blobSettingsForm").fire("submit");
  assert.equal(f.get("blobSettingsCredential").value, "");
  f.get("blobSettingsDevice").value = "second";
  await f.controller.refresh();
  pending.resolve({ ...f.value("first"), origin: "https://wrong.test" });
  await saving;
  assert.equal(f.get("blobSettingsOrigin").value, "https://relay.test");
});

test("queued and saved-but-not-queued states are distinct", async () => {
  const f = fixture({ saveBlobSettings: async () => ({ ...f.value("first"), configuration_queued: false }) });
  await f.controller.refresh();
  await f.get("blobSettingsForm").fire("submit");
  assert.equal(f.get("blobSettingsStatus").textContent, "Saved; waiting for device");
});

test("pair removal clears selection and cannot preserve a stale credential", async () => {
  const f = fixture();
  await f.controller.refresh();
  f.get("blobSettingsCredential").value = "private-secret";
  f.controller.setClients([]);
  assert.equal(f.get("blobSettingsDevice").value, "");
  assert.equal(f.get("blobSettingsCredential").value, "");
  assert.equal(f.get("blobSettingsSave").disabled, true);
});

test("errors use bounded reason labels and never display arbitrary exception content", () => {
  assert.equal(errorLabel(new Error("private-token-value"), "Fallback"), "Fallback");
  assert.equal(errorLabel(new Error("remote: blob_config_revision_conflict"), "Fallback"), "Relay settings changed; refresh");
});

test("resume refreshes only the open active gateway without restoring a credential", async () => {
  let reads = 0;
  const f = fixture({ getBlobSettings: async (route) => { reads++; return f.value(route); } });
  f.resume();
  assert.equal(reads, 0);
  f.get("blobSettingsSection").open = true;
  f.get("gatewayPanel").classList = { contains: () => false };
  f.resume();
  assert.equal(reads, 0);
  f.get("gatewayPanel").classList = { contains: () => true };
  f.resume();
  await new Promise(setImmediate);
  assert.equal(reads, 1);
  assert.equal(f.get("blobSettingsSave").disabled, false);
  assert.equal(f.get("blobSettingsCredential").value, "");
});

test("unchanged device refresh preserves an unsaved credential and origin", async () => {
  const f = fixture();
  await f.controller.refresh();
  f.get("blobSettingsOrigin").value = "https://unsaved.test";
  f.get("blobSettingsCredential").value = "private-secret";
  f.controller.setClients(f.clients.map((item) => ({ ...item, last_seen_at: Date.now() })));
  assert.equal(f.get("blobSettingsOrigin").value, "https://unsaved.test");
  assert.equal(f.get("blobSettingsCredential").value, "private-secret");
});
