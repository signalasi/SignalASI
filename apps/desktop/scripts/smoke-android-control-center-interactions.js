const fs = require("fs");
const path = require("path");
const { createAdb } = require("./android-adb");

const root = path.resolve(__dirname, "..");
const packageName = "com.signalasi.chat";
const activityName = `${packageName}/.MainActivity`;
const adb = createAdb(root, message => process.stderr.write(`${message}\n`));
const reportPath = path.join(root, "build", "reports", "android-control-center-interactions.json");

function sleep(ms) {
  return new Promise(resolve => setTimeout(resolve, ms));
}

function decodeXml(value) {
  return value
    .replace(/&quot;/g, '"')
    .replace(/&apos;/g, "'")
    .replace(/&lt;/g, "<")
    .replace(/&gt;/g, ">")
    .replace(/&amp;/g, "&");
}

function uiNodes(xml) {
  return [...xml.matchAll(/<node\b([^>]*)\/?\s*>/g)].map(match => {
    const attributes = {};
    for (const attribute of match[1].matchAll(/([\w-]+)="([^"]*)"/g)) {
      attributes[attribute[1]] = decodeXml(attribute[2]);
    }
    const bounds = /^\[(\d+),(\d+)\]\[(\d+),(\d+)\]$/.exec(attributes.bounds || "");
    return {
      ...attributes,
      bounds: bounds ? bounds.slice(1).map(Number) : null,
    };
  });
}

async function dumpUi(name) {
  const remote = `/sdcard/signalasi-cc-interaction-${name}.xml`;
  for (let attempt = 0; attempt < 4; attempt += 1) {
    try {
      adb(["shell", "rm", "-f", remote]);
      adb(["shell", "uiautomator", "dump", remote]);
      const xml = adb(["shell", "cat", remote]);
      if (xml.includes("<hierarchy")) return xml;
    } catch (_) {
      // UIAutomator can race a page transition; retry against the settled frame.
    }
    await sleep(350);
  }
  throw new Error(`Could not capture UI hierarchy for ${name}`);
}

function findNode(xml, labels) {
  const normalized = labels.map(label => label.toLowerCase());
  return uiNodes(xml).find(node => {
    const text = (node.text || "").trim().toLowerCase();
    return text && normalized.some(label => text === label || text.includes(label));
  });
}

async function tapText(labels, name, scroll = false) {
  for (let attempt = 0; attempt < (scroll ? 5 : 1); attempt += 1) {
    const xml = await dumpUi(`${name}-${attempt}`);
    const node = findNode(xml, labels);
    if (node?.bounds) {
      const [left, top, right, bottom] = node.bounds;
      adb(["shell", "input", "tap", String(Math.round((left + right) / 2)), String(Math.round((top + bottom) / 2))]);
      await sleep(500);
      return;
    }
    if (scroll) {
      adb(["shell", "input", "swipe", "600", "1500", "600", "700", "300"]);
      await sleep(350);
    }
  }
  throw new Error(`Could not find control: ${labels.join(" / ")}`);
}

async function openPage(page) {
  adb(["shell", "am", "force-stop", packageName]);
  adb([
    "shell", "am", "start", "-n", activityName,
    "--es", "signalasi_debug_control_center_page", page,
  ]);
  await sleep(650);
}

async function expectText(labels, name) {
  const xml = await dumpUi(name);
  if (!findNode(xml, labels)) {
    throw new Error(`Expected text not found: ${labels.join(" / ")}`);
  }
  return xml;
}

function ensureHealthy() {
  const pid = adb(["shell", "pidof", packageName]).trim();
  if (!pid) throw new Error("SignalASI process is not running");
  const fatal = adb(["logcat", "-d", "-v", "brief", "AndroidRuntime:E", "*:S"]);
  if (/FATAL EXCEPTION|Process: com\.signalasi\.chat.*has died/i.test(fatal)) {
    throw new Error("AndroidRuntime reported a fatal exception");
  }
}

async function runCase(name, body) {
  const startedAt = Date.now();
  adb(["logcat", "-c"]);
  try {
    await body();
    ensureHealthy();
    return { name, passed: true, elapsed_ms: Date.now() - startedAt };
  } catch (error) {
    return { name, passed: false, elapsed_ms: Date.now() - startedAt, error: error.message };
  }
}

async function main() {
  adb(["shell", "input", "keyevent", "KEYCODE_WAKEUP"]);
  adb(["shell", "wm", "dismiss-keyguard"]);
  adb(["shell", "am", "force-stop", packageName]);
  adb(["shell", "monkey", "-p", packageName, "-c", "android.intent.category.LAUNCHER", "1"]);
  await sleep(1_000);

  const cases = [
    ["memory-capture-roundtrip", async () => {
      await openPage("memory");
      const initial = await expectText([
        "Automatic memory on", "Automatic memory off",
        "\u81ea\u52a8\u8bb0\u5fc6\u5df2\u5f00\u542f", "\u81ea\u52a8\u8bb0\u5fc6\u5df2\u5173\u95ed",
      ], "memory-initial");
      const wasOn = Boolean(findNode(initial, ["Automatic memory on", "\u81ea\u52a8\u8bb0\u5fc6\u5df2\u5f00\u542f"]));
      await tapText(["Remember useful context", "\u8bb0\u4f4f\u6709\u7528\u7684\u4e0a\u4e0b\u6587"], "memory-toggle");
      await expectText([
        wasOn ? "Automatic memory off" : "Automatic memory on",
        wasOn ? "\u81ea\u52a8\u8bb0\u5fc6\u5df2\u5173\u95ed" : "\u81ea\u52a8\u8bb0\u5fc6\u5df2\u5f00\u542f",
      ], "memory-changed");
      await tapText(["Remember useful context", "\u8bb0\u4f4f\u6709\u7528\u7684\u4e0a\u4e0b\u6587"], "memory-restore");
      await expectText([
        wasOn ? "Automatic memory on" : "Automatic memory off",
        wasOn ? "\u81ea\u52a8\u8bb0\u5fc6\u5df2\u5f00\u542f" : "\u81ea\u52a8\u8bb0\u5fc6\u5df2\u5173\u95ed",
      ], "memory-restored");
    }],
    ["memory-management-navigation", async () => {
      await openPage("memory");
      await tapText(["Manage all memories", "\u7ba1\u7406\u5168\u90e8\u8bb0\u5fc6"], "memory-manage", true);
      await expectText(["Personal Memory", "\u4e2a\u4eba\u8bb0\u5fc6"], "memory-management");
      adb(["shell", "input", "keyevent", "KEYCODE_BACK"]);
      await sleep(400);
      await expectText(["Memory & Personalization", "\u8bb0\u5fc6\u4e0e\u4e2a\u6027\u5316"], "memory-back");
    }],
    ["agent-pause-roundtrip", async () => {
      await openPage("agent_core");
      await tapText(["Pause all execution", "\u6682\u505c\u6240\u6709\u6267\u884c"], "agent-pause");
      await tapText(["Pause all execution", "\u6682\u505c\u6240\u6709\u6267\u884c"], "agent-resume");
    }],
    ["native-tool-catalog-navigation", async () => {
      await openPage("phone_capabilities");
      await tapText(["Complete Native Tool Catalog", "\u5b8c\u6574\u539f\u751f\u5de5\u5177\u76ee\u5f55"], "native-tools", true);
      await expectText(["Complete Native Tool Catalog", "\u5b8c\u6574\u539f\u751f\u5de5\u5177\u76ee\u5f55"], "native-tools-open");
    }],
    ["app-adapter-navigation", async () => {
      await openPage("app_tools");
      await tapText(["Manage App Adapters", "\u7ba1\u7406\u5e94\u7528\u9002\u914d\u5668"], "app-adapters");
      await expectText(["App Adapters", "\u5e94\u7528\u9002\u914d\u5668"], "app-adapters-open");
    }],
    ["audit-log-navigation", async () => {
      await openPage("permissions_audit");
      await tapText(["Recent Operations", "\u6700\u8fd1\u64cd\u4f5c"], "audit-operations", true);
      await expectText(["Audit Log", "\u5ba1\u8ba1\u8bb0\u5f55"], "audit-open");
    }],
    ["voice-asr-navigation", async () => {
      await openPage("voice");
      await tapText(["ASR Provider", "ASR \u63d0\u4f9b\u65b9"], "voice-asr", true);
      await expectText(["ASR Provider", "ASR \u63d0\u4f9b\u65b9"], "voice-asr-open");
    }],
    ["voice-listening-roundtrip", async () => {
      await openPage("voice");
      await tapText(["Low-power listening", "\u4f4e\u529f\u8017\u76d1\u542c"], "voice-listening-off");
      await tapText(["Low-power listening", "\u4f4e\u529f\u8017\u76d1\u542c"], "voice-listening-on");
    }],
    ["backup-export-navigation", async () => {
      await openPage("data_backup");
      await tapText(["Create Encrypted Backup", "\u521b\u5efa\u52a0\u5bc6\u5907\u4efd"], "backup-export");
      await expectText(["Backup", "\u5907\u4efd"], "backup-export-open");
    }],
    ["language-navigation", async () => {
      await openPage("general");
      await tapText(["Language", "\u8bed\u8a00"], "language");
      await expectText(["Language", "\u8bed\u8a00"], "language-open");
    }],
    ["chat-history-navigation", async () => {
      await openPage("app_services");
      await tapText(["Chat History", "\u804a\u5929\u8bb0\u5f55"], "chat-history", true);
      await expectText(["Sessions", "\u4f1a\u8bdd"], "chat-history-open");
    }],
    ["advanced-audit-navigation", async () => {
      await openPage("advanced");
      await tapText(["Agent Permission Audit", "Agent \u6743\u9650\u5ba1\u8ba1"], "advanced-audit");
      await expectText(["Audit Log", "\u5ba1\u8ba1\u8bb0\u5f55"], "advanced-audit-open");
    }],
    ["security-guard-roundtrip", async () => {
      await openPage("security");
      await tapText(["High-risk Guard", "\u9ad8\u98ce\u9669\u64cd\u4f5c\u4fdd\u62a4"], "security-guard-off", true);
      await tapText(["High-risk Guard", "\u9ad8\u98ce\u9669\u64cd\u4f5c\u4fdd\u62a4"], "security-guard-on", true);
    }],
    ["cache-cleanup", async () => {
      await openPage("data_backup");
      await tapText(["Clear rebuildable cache", "\u6e05\u7406\u53ef\u91cd\u5efa\u7f13\u5b58"], "cache-cleanup", true);
      await expectText(["Data & Backup", "\u6570\u636e\u4e0e\u5907\u4efd"], "cache-cleanup-complete");
    }],
    ["reset-requires-explicit-phrase", async () => {
      await openPage("reset");
      await tapText(["Review and reset SignalASI", "\u68c0\u67e5\u5e76\u91cd\u7f6e SignalASI"], "reset-review");
      await expectText(["Type RESET", "\u8f93\u5165 RESET"], "reset-dialog");
      await tapText(["Cancel", "\u53d6\u6d88"], "reset-cancel");
      await expectText(["Reset Data", "\u91cd\u7f6e\u6570\u636e"], "reset-still-intact");
    }],
  ];

  const results = [];
  for (const [name, body] of cases) {
    const result = await runCase(name, body);
    results.push(result);
    process.stdout.write(`${result.passed ? "PASS" : "FAIL"} ${name} ${result.elapsed_ms}ms${result.error ? `: ${result.error}` : ""}\n`);
  }

  const report = {
    generated_at: new Date().toISOString(),
    device: adb(["shell", "getprop", "ro.product.model"]).trim(),
    package: packageName,
    total: results.length,
    passed: results.filter(result => result.passed).length,
    failed: results.filter(result => !result.passed).map(result => result.name),
    results,
  };
  fs.mkdirSync(path.dirname(reportPath), { recursive: true });
  fs.writeFileSync(reportPath, `${JSON.stringify(report, null, 2)}\n`);
  process.stdout.write(`Report: ${reportPath}\n`);
  if (report.failed.length > 0) process.exitCode = 1;
}

main().catch(error => {
  process.stderr.write(`${error.stack || error.message}\n`);
  process.exitCode = 1;
});
