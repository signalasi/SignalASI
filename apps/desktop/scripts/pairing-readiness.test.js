const test = require("node:test");
const assert = require("node:assert/strict");
const fs = require("node:fs");
const vm = require("node:vm");
const path = require("node:path");

const source = fs.readFileSync(path.join(__dirname, "../src/renderer/workspace.js"), "utf8");
const functions = source.slice(source.indexOf("async function refreshGateway()"),
  source.indexOf("function formatControlTime("));

function harness(clients, createdAt = 100) {
  const nodes = new Map();
  function $(id) {
    if (!nodes.has(id)) nodes.set(id, {
      src: id === "#pairingFrame" ? "old-image" : "",
      getAttribute() { return this.src; },
      removeAttribute() { this.src = ""; },
    });
    return nodes.get(id);
  }
  let requests = 0;
  const state = { pairingQrCreatedAt: createdAt, pairingQrExpiresAt: Date.now() / 1000 + 600 };
  const galaxyssi = {
    getPairingStatus: async () => ({ clients }),
    getPairingQr: async () => {
      requests++;
      return { imageDataUrl: "new-image", createdAt: 300, expiresAt: Date.now() / 1000 + 600 };
    },
  };
  const context = vm.createContext({
    state, $, window: { galaxyssi }, t: x => x,
    renderGateway() {}, renderHistory() {}, Date,
  });
  vm.runInContext(functions, context);
  return { context, state, galaxyssi, $, requests: () => requests };
}

test("a QR consumed by a newly paired phone is replaced once", async () => {
  const h = harness([{ paired_at: 200 }]);
  await h.context.refreshGateway();
  assert.equal(h.requests(), 1);
  assert.equal(h.$("#pairingFrame").src, "new-image");
  await h.context.refreshGateway();
  assert.equal(h.requests(), 1);
});

test("existing phones do not constantly rotate an unused QR", async () => {
  const h = harness([{ paired_at: 99 }]);
  await h.context.refreshGateway();
  assert.equal(h.requests(), 0);
});

test("two concurrent QR refreshes cannot overwrite each other", async () => {
  const h = harness([]);
  let release;
  h.galaxyssi.getPairingQr = () => new Promise(resolve => { release = resolve; });
  const pending = h.context.loadPairingFrame(true);
  assert.equal(h.state.pairingQrLoading, true);
  await h.context.loadPairingFrame(true);
  release({ imageDataUrl: "fresh", createdAt: 300, expiresAt: 400 });
  await pending;
  assert.equal(h.state.pairingQrLoading, false);
  assert.equal(h.$("#pairingFrame").src, "fresh");
});

test("failed QR refresh clears the image and permits a retry", async () => {
  const h = harness([]);
  h.galaxyssi.getPairingQr = async () => { throw new Error("offline"); };
  await assert.rejects(h.context.loadPairingFrame(true), /offline/);
  assert.equal(h.state.pairingQrLoading, false);
  assert.equal(h.$("#pairingFrame").src, "");
});

test("a failed QR refresh never erases confirmed gateway clients", async () => {
  const h = harness([{ paired_at: 200 }]);
  h.galaxyssi.getPairingQr = async () => { throw new Error("QR unavailable"); };
  await h.context.refreshGateway();
  assert.equal(h.state.pairing.clients.length, 1);
  assert.equal(h.state.pairingStatusLoading, false);
});

test("gateway status polling coalesces overlapping requests", async () => {
  const h = harness([]);
  let release;
  let requests = 0;
  h.galaxyssi.getPairingStatus = () => {
    requests++;
    return new Promise(resolve => { release = resolve; });
  };
  const pending = h.context.refreshGateway();
  await h.context.refreshGateway();
  assert.equal(requests, 1);
  release({ clients: [] });
  await pending;
  assert.equal(h.state.pairingStatusLoading, false);
});
