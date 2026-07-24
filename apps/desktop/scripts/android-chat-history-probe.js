const crypto = require("node:crypto");

const debugPreferencesPath = "shared_prefs/signalasi_debug.xml";

function sleep(ms) {
  return new Promise((resolve) => setTimeout(resolve, ms));
}

function readAppFile(adb, packageName, file) {
  try {
    return adb(["shell", "run-as", packageName, "cat", file]);
  } catch {
    return "";
  }
}

function decodeXml(value) {
  return String(value)
    .replace(/&quot;/g, '"')
    .replace(/&apos;/g, "'")
    .replace(/&lt;/g, "<")
    .replace(/&gt;/g, ">")
    .replace(/&amp;/g, "&");
}

function preferenceString(xml, name) {
  const pattern = new RegExp(`<string name="${name}">([\\s\\S]*?)<\\/string>`);
  const match = String(xml).match(pattern);
  return match ? decodeXml(match[1]) : "";
}

async function probeChatHistory({
  adb,
  packageName,
  activityName,
  contactId,
  contentToken = "",
  requiredStages = [],
  deleteMatches = false,
  pageSize = 200,
  timeoutMs = 10_000
}) {
  const requestId = crypto.randomUUID();
  const request = {
    request_id: requestId,
    contact_id: contactId,
    content_token: contentToken,
    required_stages: requiredStages,
    delete_matches: deleteMatches,
    page_size: pageSize
  };
  const payload = Buffer.from(JSON.stringify(request), "utf8").toString("base64");
  adb([
    "shell",
    "am",
    "start",
    "-n",
    activityName,
    "--es",
    "signalasi_debug_chat_history_probe_b64",
    payload
  ]);

  const deadline = Date.now() + timeoutMs;
  while (Date.now() < deadline) {
    await sleep(200);
    const xml = readAppFile(adb, packageName, debugPreferencesPath);
    const raw = preferenceString(xml, "chat_history_probe_result");
    if (!raw) continue;
    const result = JSON.parse(raw);
    if (result.request_id !== requestId) continue;
    if (result.error) throw new Error(`Android chat history probe failed: ${result.error}`);
    if (result.storage !== "encrypted_sqlite") {
      throw new Error(`Android chat history probe used unexpected storage: ${result.storage || "unknown"}`);
    }
    return result;
  }
  throw new Error(`Android chat history probe timed out for ${contactId}`);
}

async function waitForChatHistory(options, timeoutMs = 20_000) {
  const deadline = Date.now() + timeoutMs;
  let lastResult = null;
  while (Date.now() < deadline) {
    lastResult = await probeChatHistory({
      ...options,
      timeoutMs: Math.min(Number(options.timeoutMs || 10_000), Math.max(1_000, deadline - Date.now()))
    });
    const missingStages = Array.isArray(lastResult.missing_stages) ? lastResult.missing_stages : [];
    if (Number(lastResult.match_count || 0) > 0 && missingStages.length === 0) return lastResult;
    await sleep(250);
  }
  throw new Error(
    `Android chat history did not reach the expected state: ${JSON.stringify(lastResult || {})}`
  );
}

function requireProbeMatch(result, label, minimumMatches = 1) {
  if (Number(result.match_count || 0) < minimumMatches) {
    throw new Error(`${label}: expected at least ${minimumMatches} matching encrypted history row(s)`);
  }
  const missingStages = Array.isArray(result.missing_stages) ? result.missing_stages : [];
  if (missingStages.length > 0) {
    throw new Error(`${label}: encrypted history is missing delivery stage(s): ${missingStages.join(", ")}`);
  }
}

module.exports = {
  probeChatHistory,
  waitForChatHistory,
  requireProbeMatch
};
