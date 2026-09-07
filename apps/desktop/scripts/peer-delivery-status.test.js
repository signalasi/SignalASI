const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const test = require("node:test");
const vm = require("node:vm");

const source = fs.readFileSync(path.join(__dirname, "../src/renderer/workspace.js"), "utf8");
const start = source.indexOf("function peerDeliveryLabel(");
const end = source.indexOf("function renderPeerConversation(", start);
assert.ok(start >= 0 && end > start);
const translations = JSON.parse(fs.readFileSync(path.join(__dirname, "../src/renderer/locales/zh-CN.json"), "utf8"));
const context = vm.createContext({ t: (text) => translations[text] || text });
vm.runInContext(source.slice(start, end), context);

test("verified stored receipts are shown as delivered, not queued", () => {
  assert.equal(context.peerDeliveryLabel("delivered"), "\u5df2\u9001\u8fbe");
  assert.equal(context.peerDeliveryLabel("read"), "\u5df2\u8bfb");
});

test("legacy sent and failure statuses preserve their existing translations", () => {
  for (const [status, key] of [["sent", "Sent"], ["failed", "Failed"], ["queued", "Queued"]]) {
    assert.equal(context.peerDeliveryLabel(status), translations[key]);
  }
});

test("unknown states never claim successful delivery", () => {
  for (const status of [undefined, "", "uploading", "constructor", "toString"]) {
    assert.equal(context.peerDeliveryLabel(status), translations.Queued);
  }
});

test("contact rendering uses the shared label function", () => {
  assert.match(source.slice(end, source.indexOf("function openPeerConversation", end)),
    /const deliveryLabel = peerDeliveryLabel\(message\.delivery_status\)/);
});
