const { spawnSync } = require("node:child_process");

const adb = process.env.ADB || "adb";
const applicationId = "com.signalasi.chat";
const runner = `${applicationId}.test/androidx.test.runner.AndroidJUnitRunner`;
const testClass = `${applicationId}.AgentTeamPairedProcessDeathDeviceTest`;
const reportPath = `/sdcard/Android/data/${applicationId}/files/agent-team-paired-process-death-report.json`;

function run(args, options = {}) {
  const result = spawnSync(adb, args, {
    encoding: "utf8",
    windowsHide: true,
    timeout: 360_000,
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

function instrument(phase, forcedAt = 0) {
  const args = [
    "shell",
    "am",
    "instrument",
    "-w",
    "-r",
    "-e",
    "signalasi_phase",
    phase,
  ];
  if (forcedAt > 0) {
    args.push("-e", "signalasi_forced_at", String(forcedAt));
  }
  args.push("-e", "class", testClass, runner);
  const output = run(args);
  if (!/OK \(1 test\)/.test(output)) {
    throw new Error(`Android paired-team instrumentation phase ${phase} did not pass`);
  }
}

function relaunchApplication() {
  run(["shell", "am", "force-stop", applicationId]);
  run(["shell", "input", "keyevent", "224"]);
  run(["shell", "wm", "dismiss-keyguard"]);
  run(["shell", "am", "start", "-n", `${applicationId}/.MainActivity`]);
}

function deviceTimeMillis() {
  const value = run(["shell", "date", "+%s%3N"]);
  const parsed = Number(value.trim());
  if (!Number.isSafeInteger(parsed) || parsed <= 0) {
    throw new Error(`Could not read Android wall-clock time: ${value}`);
  }
  return parsed;
}

let failure;
try {
  const devices = run(["devices"]);
  if (!/^\S+\s+device$/m.test(devices)) {
    throw new Error("No authorized Android device is connected");
  }
  run(["shell", "rm", "-f", reportPath]);
  run(["shell", "am", "force-stop", applicationId]);
  instrument("seed");
  run(["shell", "am", "force-stop", applicationId]);
  const forcedAt = deviceTimeMillis();
  instrument("recover", forcedAt);

  const reportText = run(["exec-out", "cat", reportPath]);
  const report = JSON.parse(reportText);
  const passed = report.process_recreated === true &&
    report.team_state === "SUCCEEDED" &&
    report.observer_output_verified === true &&
    report.primary_output_verified === true &&
    report.primary_completed_at >= report.forced_at &&
    report.synthetic_response_count === 1 &&
    report.ordinary_chat_leak_count === 0 &&
    report.completed_unapplied_count === 0;
  if (!passed) throw new Error(`Unexpected paired-team acceptance report: ${reportText}`);
  process.stdout.write("Android paired Desktop Agent team process-death acceptance passed.\n");
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
