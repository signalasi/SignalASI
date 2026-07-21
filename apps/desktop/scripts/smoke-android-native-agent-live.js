const fs = require("fs");
const path = require("path");
const crypto = require("crypto");
const { createAdb } = require("./android-adb");

const root = path.resolve(__dirname, "..");
const packageName = "com.signalasi.chat";
const activityName = `${packageName}/.MainActivity`;
const reportPath = path.join(root, "build", "reports", "android-native-agent-live.json");
const fixturePath = path.join(root, "build", "reports", "signalasi-live-file.txt");
const deviceFixturePath = "/storage/emulated/0/Download/signalasi-live-file.txt";
const adb = createAdb(root, message => process.stderr.write(`${message}\n`));

const cases = [
  {
    id: "concise_calculation",
    prompt: "\u53ea\u7ed9\u51fa 37 + 58 \u7684\u7ed3\u679c\u3002",
    maxMs: 10_000,
    validate: text => /(^|\D)95(\D|$)/.test(text),
  },
  {
    id: "vague_request",
    prompt: "\u5e2e\u6211\u5904\u7406\u4e00\u4e0b\u3002",
    maxMs: 10_000,
    validate: text => /[?\uff1f]/.test(text),
  },
  {
    id: "phone_battery",
    prompt: "\u8bfb\u53d6\u8fd9\u53f0\u624b\u673a\u7684\u5f53\u524d\u7535\u91cf\uff0c\u7b80\u77ed\u56de\u7b54\u3002",
    maxMs: 5_000,
    validate: text => /%|\u7535\u91cf|battery/i.test(text),
  },
  {
    id: "phone_network",
    prompt: "\u67e5\u770b\u8fd9\u53f0\u624b\u673a\u5f53\u524d\u7684\u7f51\u7edc\u8fde\u63a5\u72b6\u6001\uff0c\u7b80\u77ed\u56de\u7b54\u3002",
    maxMs: 5_000,
    validate: text => /Wi-?Fi|\u7f51\u7edc|network|\u5df2\u8fde\u63a5/i.test(text),
  },
  {
    id: "phone_storage",
    prompt: "\u8bfb\u53d6\u8fd9\u53f0\u624b\u673a\u7684\u5b58\u50a8\u5bb9\u91cf\u548c\u5269\u4f59\u7a7a\u95f4\uff0c\u7b80\u77ed\u56de\u7b54\u3002",
    maxMs: 5_000,
    validate: text => /GB|MB|\u5b58\u50a8|\u7a7a\u95f4|storage/i.test(text),
  },
  {
    id: "read_phone_file",
    prompt: `\u8bfb\u53d6 ${deviceFixturePath} \u5e76\u53ea\u544a\u8bc9\u6211\u6587\u4ef6\u4e2d\u7684\u9a8c\u8bc1\u7801\u3002`,
    maxMs: 15_000,
    validate: text => /7319/.test(text),
  },
  {
    id: "current_weather",
    prompt: "\u67e5\u8be2\u4eca\u5929\u4e0a\u6d77\u7684\u5929\u6c14\uff0c\u7ed9\u51fa\u6e29\u5ea6\u548c\u4e00\u53e5\u51fa\u884c\u5efa\u8bae\u3002",
    maxMs: 30_000,
    validate: text => /\u4e0a\u6d77|Shanghai/i.test(text) && /\u2103|\u00b0|\u6e29\u5ea6|temperature/i.test(text),
    timeoutMs: 120_000,
  },
  {
    id: "missing_file",
    prompt: "\u8bfb\u53d6 /storage/emulated/0/Download/signalasi-file-that-does-not-exist.txt \u5e76\u544a\u8bc9\u6211\u7ed3\u679c\u3002",
    maxMs: 10_000,
    validate: text => /\u4e0d\u5b58\u5728|\u627e\u4e0d\u5230|not found|does not exist|unable to read/i.test(text),
  },
  {
    id: "phone_linux_python",
    prompt: "\u8bf7\u5728\u624b\u673a\u672c\u5730 Linux \u4e2d\u521b\u5efa verify_sort.py\uff0c\u7528 Python \u5bf9 [3, 1, 2] \u6392\u5e8f\uff0c\u8fd0\u884c\u5e76\u9a8c\u8bc1\u8f93\u51fa\u6070\u597d\u4e3a 1,2,3\u3002",
    maxMs: 120_000,
    timeoutMs: 180_000,
    validate: text => /verify_sort\.py|1\s*,\s*2\s*,\s*3|Python|\u9a8c\u8bc1|passed/i.test(text),
  },
  {
    id: "codex_agent",
    prompt: "\u8bf7\u8c03\u7528 Codex Agent\uff0c\u5e76\u4e14\u6700\u7ec8\u53ea\u56de\u590d CODEX_OK\u3002",
    maxMs: 180_000,
    timeoutMs: 210_000,
    validate: text => /CODEX_OK/i.test(text),
  },
];

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

function readSnapshot(token) {
  let xml = "";
  try {
    xml = adb(["shell", "run-as", packageName, "cat", "shared_prefs/signalasi_debug_agent.xml"]);
  } catch (_) {
    return null;
  }
  const escaped = token.replace(/[.*+?^${}()|[\]\\]/g, "\\$&");
  const match = xml.match(new RegExp(`<string name="${escaped}">([\\s\\S]*?)<\\/string>`));
  if (!match) return null;
  try {
    return JSON.parse(decodeXml(match[1]));
  } catch (_) {
    return null;
  }
}

function assess(testCase, snapshot, endToEndMs) {
  const entries = Array.isArray(snapshot?.entries) ? snapshot.entries : [];
  const user = entries.find(entry => entry.role === "USER");
  const assistant = [...entries].reverse().find(entry => entry.role === "ASSISTANT");
  const response = String(assistant?.text || "").trim();
  const turnElapsedMs = user?.timestamp && assistant?.timestamp
    ? Math.max(0, assistant.timestamp - user.timestamp)
    : endToEndMs;
  const startupMs = user?.timestamp && snapshot?.started_at
    ? Math.max(0, user.timestamp - snapshot.started_at)
    : 0;
  const process = entries.filter(entry => entry.role === "PROCESS").map(entry => entry.text);
  const lower = response.toLowerCase();
  const forbidden = ["as an ai", "system prompt", "traceback", "mcp_", "preparing tool"]
    .filter(value => lower.includes(value));
  const stylePassed = Boolean(response) && forbidden.length === 0 && response.length <= 4_000;
  const contentPassed = Boolean(response) && testCase.validate(response);
  return {
    id: testCase.id,
    input: testCase.prompt,
    elapsed_ms: turnElapsedMs,
    end_to_end_ms: endToEndMs,
    startup_to_user_ms: startupMs,
    target_ms: testCase.maxMs,
    phase: snapshot?.phase || "",
    response,
    process,
    forbidden,
    content_passed: contentPassed,
    codex_style: stylePassed,
    latency_passed: turnElapsedMs <= testCase.maxMs,
    passed: contentPassed && stylePassed && turnElapsedMs <= testCase.maxMs,
  };
}

async function runCase(testCase, timeoutMs) {
  const token = `live_${testCase.id}_${Date.now()}_${crypto.randomBytes(3).toString("hex")}`;
  const encoded = Buffer.from(testCase.prompt, "utf8").toString("base64");
  const started = Date.now();
  adb([
    "shell", "am", "start", "-n", activityName,
    "--es", "signalasi_debug_agent_goal_b64", encoded,
    "--es", "signalasi_debug_agent_token", token,
    "--ez", "signalasi_debug_agent_new_conversation", "true",
  ]);
  let snapshot = null;
  while (Date.now() - started < timeoutMs) {
    await sleep(250);
    snapshot = readSnapshot(token);
    if (snapshot?.complete) break;
  }
  if (!snapshot?.complete) {
    await sleep(3_000);
    snapshot = readSnapshot(token) || snapshot;
  }
  return assess(testCase, snapshot, Date.now() - started);
}

async function main() {
  const selectedId = process.argv.find(arg => arg.startsWith("--case="))?.split("=", 2)[1];
  const selected = selectedId ? cases.filter(item => item.id === selectedId) : cases;
  if (selected.length === 0) throw new Error(`Unknown case: ${selectedId}`);
  fs.mkdirSync(path.dirname(reportPath), { recursive: true });
  fs.writeFileSync(fixturePath, "SignalASI live file verification code: 7319\n", "utf8");
  adb(["push", fixturePath, deviceFixturePath]);
  const records = [];
  for (const testCase of selected) {
    const timeoutMs = testCase.timeoutMs || Math.max(testCase.maxMs * 3, 45_000);
    const record = await runCase(testCase, timeoutMs);
    records.push(record);
    process.stdout.write(`${JSON.stringify(record)}\n`);
  }
  fs.writeFileSync(reportPath, JSON.stringify({ generated_at: new Date().toISOString(), records }, null, 2));
  const passed = records.filter(record => record.passed).length;
  process.stdout.write(`${JSON.stringify({ passed, total: records.length, report: reportPath })}\n`);
  process.exitCode = passed === records.length ? 0 : 1;
}

main().catch(error => {
  process.stderr.write(`${error.stack || error.message}\n`);
  process.exitCode = 1;
});
