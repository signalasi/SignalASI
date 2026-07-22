const { spawnSync } = require("node:child_process");

const adb = process.env.ADB || "adb";
const applicationId = "com.signalasi.chat";
const runner = `${applicationId}.test/androidx.test.runner.AndroidJUnitRunner`;
const testClass = `${applicationId}.AgentTeamProcessDeathDeviceTest`;
const reportPath = `/sdcard/Android/data/${applicationId}/files/agent-team-process-death-report.json`;

function run(args, options = {}) {
  const result = spawnSync(adb, args, {
    encoding: "utf8",
    windowsHide: true,
    ...options,
  });
  const output = `${result.stdout || ""}${result.stderr || ""}`.trim();
  if (output) process.stdout.write(`${output}\n`);
  if (result.error) throw result.error;
  if (result.status !== 0) {
    throw new Error(`adb ${args.join(" ")} failed with exit code ${result.status}`);
  }
  return output;
}

function instrument(phase) {
  const output = run([
    "shell",
    "am",
    "instrument",
    "-w",
    "-r",
    "-e",
    "signalasi_phase",
    phase,
    "-e",
    "class",
    testClass,
    runner,
  ]);
  if (!/OK \(1 test\)/.test(output)) {
    throw new Error(`Android instrumentation phase ${phase} did not pass`);
  }
}

function relaunchApplication() {
  run(["shell", "am", "force-stop", applicationId]);
  run(["shell", "input", "keyevent", "224"]);
  run(["shell", "wm", "dismiss-keyguard"]);
  run(["shell", "am", "start", "-n", `${applicationId}/.MainActivity`]);
}

let failure;
try {
  const devices = run(["devices"]);
  if (!/^\S+\s+device$/m.test(devices)) {
    throw new Error("No authorized Android device is connected");
  }
  run(["shell", "am", "force-stop", applicationId]);
  instrument("seed");
  run(["shell", "am", "force-stop", applicationId]);
  instrument("recover");
  const reportText = run(["exec-out", "cat", reportPath]);
  const report = JSON.parse(reportText);
  const passed = report.process_recreated === true &&
    report.team_state === "SUCCEEDED" &&
    report.synthetic_response_count === 1 &&
    report.ordinary_chat_leak_count === 0 &&
    report.completed_unapplied_count === 0 &&
    report.duplicate_suppressed === true;
  if (!passed) throw new Error(`Unexpected acceptance report: ${reportText}`);
  process.stdout.write("Android Agent team process-death acceptance passed.\n");
} catch (error) {
  failure = error;
} finally {
  try {
    relaunchApplication();
  } catch (error) {
    if (!failure) failure = error;
  }
}

if (failure) {
  process.stderr.write(`${failure.stack || failure.message || failure}\n`);
  process.exitCode = 1;
}
