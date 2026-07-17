const fs = require("fs");
const os = require("os");
const path = require("path");
const { createAdb } = require("./android-adb");

const root = path.resolve(__dirname, "..");
const packageName = "com.signalasi.chat";
const activityName = `${packageName}/.MainActivity`;
const adb = createAdb(root, message => process.stderr.write(`${message}\n`));
const reportPath = path.join(root, "build", "reports", "android-control-center-live.json");
const artifactRoot = process.env.SIGNALASI_TEST_ARTIFACTS || path.join(
  os.homedir(),
  ".codex",
  "signalasi-test-artifacts",
  new Date().toISOString().slice(0, 10),
  "control-center-live",
);

const pages = [
  ["home", "Control Center", "\u63a7\u5236\u4e2d\u5fc3"],
  ["profile", "My SignalASI", "\u6211\u7684 SignalASI"],
  ["system_status", "System Status", "\u7cfb\u7edf\u72b6\u6001"],
  ["agent_core", "Agent Core", "Agent \u5185\u6838"],
  ["execution_policy", "Execution Policy", "\u6267\u884c\u7b56\u7565"],
  ["resource_routing", "Models & Resource Routing", "\u6a21\u578b\u4e0e\u8d44\u6e90\u8def\u7531"],
  ["memory", "Memory & Personalization", "\u8bb0\u5fc6\u4e0e\u4e2a\u6027\u5316"],
  ["knowledge", "Knowledge", "\u77e5\u8bc6\u5e93"],
  ["skills", "Skills", "\u6280\u80fd"],
  ["tasks", "Task Center", "\u4efb\u52a1\u4e2d\u5fc3"],
  ["phone_capabilities", "Phone Capabilities", "\u624b\u673a\u80fd\u529b"],
  ["app_tools", "Apps & Tools", "\u5e94\u7528\u4e0e\u5de5\u5177"],
  ["smart_spaces", "Smart Spaces", "\u667a\u80fd\u7a7a\u95f4"],
  ["nodes", "Agents, Models & Nodes", "Agent\u3001\u6a21\u578b\u4e0e\u8282\u70b9"],
  ["security", "Security & Trust", "\u5b89\u5168\u4e0e\u4fe1\u4efb"],
  ["permissions_audit", "Permissions & Audit", "\u6743\u9650\u4e0e\u5ba1\u8ba1"],
  ["voice", "Voice & Interaction", "\u8bed\u97f3\u4e0e\u4ea4\u4e92"],
  ["data_backup", "Data & Backup", "\u6570\u636e\u4e0e\u5907\u4efd"],
  ["general", "General", "\u901a\u7528"],
  ["app_services", "Apps & Services", "\u5e94\u7528\u4e0e\u670d\u52a1"],
  ["advanced", "Advanced Options", "\u9ad8\u7ea7\u9009\u9879"],
  ["reset", "Reset Data", "\u91cd\u7f6e\u6570\u636e"],
];

function sleep(ms) {
  return new Promise(resolve => setTimeout(resolve, ms));
}

function safeFileName(value) {
  return value.replace(/[^a-z0-9_-]+/gi, "-");
}

async function dumpUi(remoteName) {
  const remotePath = `/sdcard/${remoteName}`;
  let lastError = null;
  for (let attempt = 0; attempt < 4; attempt += 1) {
    try {
      adb(["shell", "rm", "-f", remotePath]);
      adb(["shell", "input", "keyevent", "KEYCODE_WAKEUP"]);
      adb(["shell", "wm", "dismiss-keyguard"]);
      adb(["shell", "uiautomator", "dump", remotePath]);
      const xml = adb(["shell", "cat", remotePath]);
      if (xml.includes("<hierarchy")) return xml;
    } catch (error) {
      lastError = error;
    }
    await sleep(500);
  }
  throw lastError || new Error(`UI hierarchy was not created at ${remotePath}`);
}

function hasExpectedTitle(xml, english, chinese) {
  return xml.includes(`text="${english}"`) || xml.includes(`text="${chinese}"`);
}

function hasFatalLog(log) {
  return /FATAL EXCEPTION|Process: com\.signalasi\.chat.*has died|AndroidRuntime:.*Exception/i.test(log);
}

async function capturePage(page, english, chinese) {
  const startedAt = Date.now();
  adb(["logcat", "-c"]);
  adb([
    "shell", "am", "start", "-n", activityName,
    "--es", "signalasi_debug_control_center_page", page,
  ]);
  await sleep(900);
  const xml = await dumpUi(`signalasi-cc-${safeFileName(page)}.xml`);
  const pid = adb(["shell", "pidof", packageName]).trim();
  const screenshotRemote = `/sdcard/signalasi-cc-${safeFileName(page)}.png`;
  const screenshotLocal = path.join(artifactRoot, `${safeFileName(page)}.png`);
  adb(["shell", "screencap", "-p", screenshotRemote]);
  adb(["pull", screenshotRemote, screenshotLocal]);
  const log = adb(["logcat", "-d", "-v", "brief", "AndroidRuntime:E", "*:S"]);
  const titlePassed = hasExpectedTitle(xml, english, chinese);
  const alive = Boolean(pid);
  const fatal = hasFatalLog(log);

  let backPassed = true;
  if (page !== "home" && alive && !fatal) {
    adb(["shell", "input", "keyevent", "KEYCODE_BACK"]);
    await sleep(400);
    const backXml = await dumpUi(`signalasi-cc-${safeFileName(page)}-back.xml`);
    backPassed = hasExpectedTitle(backXml, "Control Center", "\u63a7\u5236\u4e2d\u5fc3");
  }

  return {
    page,
    expected_titles: [english, chinese],
    title_passed: titlePassed,
    process_alive: alive,
    fatal_exception: fatal,
    back_to_control_center: backPassed,
    elapsed_ms: Date.now() - startedAt,
    screenshot: screenshotLocal,
    passed: titlePassed && alive && !fatal && backPassed,
  };
}

async function main() {
  fs.mkdirSync(path.dirname(reportPath), { recursive: true });
  fs.mkdirSync(artifactRoot, { recursive: true });
  adb(["shell", "input", "keyevent", "KEYCODE_WAKEUP"]);
  adb(["shell", "wm", "dismiss-keyguard"]);
  adb(["shell", "am", "force-stop", packageName]);
  adb(["shell", "monkey", "-p", packageName, "-c", "android.intent.category.LAUNCHER", "1"]);
  await sleep(1_500);

  const requestedPages = new Set(
    (process.env.SIGNALASI_CC_LIVE_PAGES || "")
      .split(",")
      .map(value => value.trim())
      .filter(Boolean)
  );
  const selectedPages = requestedPages.size > 0
    ? pages.filter(([page]) => requestedPages.has(page))
    : pages;
  const results = [];
  for (const [page, english, chinese] of selectedPages) {
    const result = await capturePage(page, english, chinese);
    results.push(result);
    process.stdout.write(`${result.passed ? "PASS" : "FAIL"} ${page} ${result.elapsed_ms}ms\n`);
  }
  const report = {
    generated_at: new Date().toISOString(),
    device: adb(["shell", "getprop", "ro.product.model"]).trim(),
    package: packageName,
    total: results.length,
    passed: results.filter(result => result.passed).length,
    failed: results.filter(result => !result.passed).map(result => result.page),
    results,
  };
  fs.writeFileSync(reportPath, `${JSON.stringify(report, null, 2)}\n`);
  process.stdout.write(`Report: ${reportPath}\n`);
  if (report.failed.length > 0) process.exitCode = 1;
}

main().catch(error => {
  process.stderr.write(`${error.stack || error.message}\n`);
  process.exitCode = 1;
});
