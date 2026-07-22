const fs = require("node:fs");
const path = require("node:path");
const { spawnSync } = require("node:child_process");

const adb = process.env.ADB || "adb";
const applicationId = "com.signalasi.chat";
const runner = `${applicationId}.test/androidx.test.runner.AndroidJUnitRunner`;
const testClass = `${applicationId}.AgentLifecycleUiDeviceTest`;
const deviceDirectory = `/sdcard/Android/data/${applicationId}/files`;
const aggregateReportFilename = "agent-lifecycle-ui-report.json";
const repositoryRoot = path.resolve(__dirname, "..", "..");
const outputDirectory = path.join(repositoryRoot, "build", "reports", "android-agent-lifecycle-ui");

function execute(args, options = {}) {
  const result = spawnSync(adb, args, {
    encoding: "utf8",
    windowsHide: true,
    ...options,
  });
  if (result.error) throw result.error;
  if (result.status !== 0) {
    const output = `${result.stdout || ""}${result.stderr || ""}`.trim();
    throw new Error(`adb ${args.join(" ")} failed with exit code ${result.status}${output ? `\n${output}` : ""}`);
  }
  return result;
}

function runText(args) {
  const result = execute(args);
  const output = `${result.stdout || ""}${result.stderr || ""}`.trim();
  if (output) process.stdout.write(`${output}\n`);
  return output;
}

function readDeviceFile(filename) {
  const result = execute(["exec-out", "cat", `${deviceDirectory}/${filename}`], { encoding: null });
  return Buffer.from(result.stdout);
}

function relaunchApplication() {
  runText(["shell", "am", "force-stop", applicationId]);
  runText(["shell", "input", "keyevent", "224"]);
  runText(["shell", "wm", "dismiss-keyguard"]);
  runText(["shell", "am", "start", "-n", `${applicationId}/.MainActivity`]);
}

function currentDisplayOverride() {
  const size = runText(["shell", "wm", "size"]);
  const density = runText(["shell", "wm", "density"]);
  return {
    size: /Override size:\s*(\d+x\d+)/.exec(size)?.[1] || null,
    density: Number.parseInt(/Override density:\s*(\d+)/.exec(density)?.[1] || "", 10) || null,
  };
}

function applyViewport(viewport) {
  runText(["shell", "wm", "size", viewport.size]);
  runText(["shell", "wm", "density", String(viewport.density)]);
}

function restoreDisplayOverride(original) {
  runText(["shell", "wm", "size", original.size || "reset"]);
  runText(["shell", "wm", "density", original.density ? String(original.density) : "reset"]);
}

function instrument(viewport) {
  runText(["shell", "am", "force-stop", applicationId]);
  const output = runText([
    "shell",
    "am",
    "instrument",
    "-w",
    "-r",
    "-e",
    "signalasi_viewport",
    viewport.name,
    "-e",
    "class",
    testClass,
    runner,
  ]);
  if (!/OK \(1 test\)/.test(output)) {
    throw new Error(`Android Agent lifecycle UI instrumentation did not pass for ${viewport.name}`);
  }
  return output;
}

let failure;
let originalDisplay;
try {
  const devices = runText(["devices"]);
  if (!/^\S+\s+device$/m.test(devices)) {
    throw new Error("No authorized Android device is connected");
  }
  fs.mkdirSync(outputDirectory, { recursive: true });
  runText(["shell", "input", "keyevent", "224"]);
  runText(["shell", "wm", "dismiss-keyguard"]);
  originalDisplay = currentDisplayOverride();
  const profiles = [
    { name: "compact", size: "720x1280", density: 320 },
    { name: "large", size: "1080x2400", density: 420 },
  ];
  const expectedStates = [
    "QUEUED",
    "RUNNING",
    "SUCCEEDED",
    "COMPLETED_WITH_FAILURES",
    "FAILED",
    "CANCELLED",
    "INTERRUPTED",
  ];
  const viewports = [];
  for (const profile of profiles) {
    applyViewport(profile);
    runText(["shell", "input", "keyevent", "224"]);
    runText(["shell", "wm", "dismiss-keyguard"]);
    instrument(profile);
    const profileReportFilename = `agent-lifecycle-ui-report-${profile.name}.json`;
    const profileReportBuffer = readDeviceFile(profileReportFilename);
    const profileReport = JSON.parse(profileReportBuffer.toString("utf8"));
    const actualStates = new Set(profileReport.states || []);
    if (profileReport.fixture_count !== expectedStates.length ||
        !expectedStates.every((state) => actualStates.has(state)) ||
        profileReport.header_fixed !== true ||
        profileReport.details_verified !== true) {
      throw new Error(`Unexpected lifecycle UI report for ${profile.name}:\n${profileReportBuffer.toString("utf8")}`);
    }
    const viewport = profileReport.viewport;
    viewports.push(viewport);
    fs.writeFileSync(path.join(outputDirectory, profileReportFilename), profileReportBuffer);
    for (const key of ["top_screenshot", "bottom_screenshot", "details_screenshot"]) {
      const filename = viewport[key];
      if (!filename) throw new Error(`Lifecycle UI report is missing ${key} for ${viewport.name}`);
      fs.writeFileSync(path.join(outputDirectory, filename), readDeviceFile(filename));
    }
  }
  const passed =
    viewports.length === 2 &&
    viewports.every((viewport) => viewport.header_fixed === true && viewport.screenshot_height > viewport.screenshot_width) &&
    viewports.some((viewport) => viewport.name === "compact" && viewport.scroll_y_after > 0);
  if (!passed) throw new Error(`Unexpected lifecycle UI viewport evidence:\n${JSON.stringify(viewports, null, 2)}`);
  fs.writeFileSync(path.join(outputDirectory, aggregateReportFilename), JSON.stringify({
    fixture_count: expectedStates.length,
    states: expectedStates,
    viewports,
    header_fixed: true,
    details_verified: true,
  }, null, 2));
  process.stdout.write(`Android Agent lifecycle UI acceptance passed. Evidence: ${outputDirectory}\n`);
} catch (error) {
  failure = error;
} finally {
  try {
    if (originalDisplay) restoreDisplayOverride(originalDisplay);
    relaunchApplication();
  } catch (error) {
    if (!failure) failure = error;
  }
}

if (failure) {
  process.stderr.write(`${failure.stack || failure.message || failure}\n`);
  process.exitCode = 1;
}
