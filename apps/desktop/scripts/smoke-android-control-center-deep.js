const fs = require("node:fs");
const http = require("node:http");
const path = require("node:path");
const { createAdb } = require("./android-adb");

const root = path.resolve(__dirname, "..");
const packageName = "com.signalasi.chat";
const activityName = `${packageName}/.MainActivity`;
const adb = createAdb(root, message => process.stderr.write(`${message}\n`));
const reportPath = path.join(root, "build", "reports", "android-control-center-deep.json");
const debugPreferencesPath = "shared_prefs/signalasi_debug.xml";
const homeAssistantPreferencesPath = "shared_prefs/signalasi_home_assistant.xml";

const labels = {
  back: ["\u2039"],
  save: ["Save", "\u4fdd\u5b58"],
  cancel: ["Cancel", "\u53d6\u6d88"],
  profile: ["My SignalASI", "\u6211\u7684 SignalASI"],
  system: ["System Status", "\u7cfb\u7edf\u72b6\u6001"],
  agent: ["Agent Core", "Agent \u5185\u6838"],
  execution: ["Execution Policy", "\u6267\u884c\u7b56\u7565"],
  routing: ["Models & Resource Routing", "\u6a21\u578b\u4e0e\u8d44\u6e90\u8def\u7531"],
  memory: ["Memory & Personalization", "\u8bb0\u5fc6\u4e0e\u4e2a\u6027\u5316"],
  knowledge: ["Knowledge", "\u77e5\u8bc6\u5e93"],
  skills: ["Skills", "\u6280\u80fd"],
  phone: ["Phone Capabilities", "\u624b\u673a\u80fd\u529b"],
  appTools: ["Apps & Tools", "\u5e94\u7528\u4e0e\u5de5\u5177"],
  spaces: ["Smart Spaces", "\u667a\u80fd\u7a7a\u95f4"],
  security: ["Security & Trust", "\u5b89\u5168\u4e0e\u4fe1\u4efb"],
  permissions: ["Permissions & Audit", "\u6743\u9650\u4e0e\u5ba1\u8ba1"],
  voice: ["Voice & Interaction", "\u8bed\u97f3\u4e0e\u4ea4\u4e92"],
  data: ["Data & Backup", "\u6570\u636e\u4e0e\u5907\u4efd"],
  general: ["General", "\u901a\u7528"],
  services: ["Apps & Services", "\u5e94\u7528\u4e0e\u670d\u52a1"],
  advanced: ["Advanced Options", "\u9ad8\u7ea7\u9009\u9879"],
  reset: ["Reset Data", "\u91cd\u7f6e\u6570\u636e"],
};

function sleep(ms) {
  return new Promise(resolve => setTimeout(resolve, ms));
}

function readAppFile(file) {
  try {
    return adb(["shell", "run-as", packageName, "cat", file]);
  } catch (_) {
    return "";
  }
}

function restoreAppFile(file, snapshot) {
  if (!snapshot) {
    adb(["shell", "run-as", packageName, "rm", "-f", file]);
    return;
  }
  adb(["shell", "run-as", packageName, "mkdir", "-p", "shared_prefs"]);
  adb(["shell", "run-as", packageName, "tee", file], {
    input: snapshot,
    stdio: ["pipe", "ignore", "pipe"],
  });
}

function startFakeHomeAssistant() {
  const requests = [];
  const entities = [
    { entity_id: "light.qa_lamp", state: "on", attributes: { friendly_name: "QA Lamp" } },
    { entity_id: "automation.qa_morning", state: "on", attributes: { friendly_name: "QA Morning" } },
  ];
  const server = http.createServer((request, response) => {
    requests.push({ url: request.url, authorization: request.headers.authorization || "" });
    const entityId = decodeURIComponent(String(request.url || "").replace("/api/states/", ""));
    const body = request.url === "/api/states"
      ? entities
      : entities.find(entity => entity.entity_id === entityId) || {};
    response.writeHead(200, { "Content-Type": "application/json" });
    response.end(JSON.stringify(body));
  });
  return new Promise((resolve, reject) => {
    server.once("error", reject);
    server.listen(0, "127.0.0.1", () => {
      server.off("error", reject);
      resolve({ server, port: server.address().port, requests });
    });
  });
}

function preferenceString(xml, name) {
  const match = xml.match(new RegExp(`<string name="${name}">([\\s\\S]*?)<\\/string>`));
  return match ? decodeXml(match[1]) : "";
}

async function waitForDebugResult(key, token) {
  for (let attempt = 0; attempt < 16; attempt += 1) {
    await sleep(500);
    const raw = preferenceString(readAppFile(debugPreferencesPath), key);
    if (!raw) continue;
    const result = JSON.parse(raw);
    if (result.token === token) return result;
  }
  throw new Error(`Timed out waiting for ${key}`);
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
    return { ...attributes, bounds: bounds ? bounds.slice(1).map(Number) : null };
  });
}

async function dumpUi(name) {
  const remote = `/sdcard/signalasi-cc-deep-${name}.xml`;
  for (let attempt = 0; attempt < 4; attempt += 1) {
    try {
      adb(["shell", "uiautomator", "dump", remote]);
      const xml = adb(["shell", "cat", remote]);
      if (xml.includes("<hierarchy")) return xml;
    } catch (_) {
      // UIAutomator may race a system activity transition.
    }
    await sleep(300);
  }
  throw new Error(`Could not capture UI hierarchy for ${name}`);
}

function findNode(xml, candidates, preferLast = false) {
  const normalized = candidates.map(value => value.toLowerCase());
  const nodes = uiNodes(xml);
  const valuesFor = node => [node.text, node["content-desc"]]
    .filter(Boolean)
    .map(value => value.trim().toLowerCase());
  const exact = nodes.filter(node => valuesFor(node).some(value => normalized.includes(value)));
  if (exact.length > 0) return preferLast ? exact[exact.length - 1] : exact[0];
  const partial = nodes.filter(node => {
    const values = valuesFor(node);
    return values.some(value => normalized.some(candidate => value === candidate || value.includes(candidate)));
  });
  return preferLast ? partial[partial.length - 1] : partial[0];
}

async function scrollToText(candidates, name) {
  for (let attempt = 0; attempt < 7; attempt += 1) {
    const xml = await dumpUi(`${name}-${attempt}`);
    if (findNode(xml, candidates)) return xml;
    adb(["shell", "input", "swipe", "600", "1550", "600", "620", "280"]);
    await sleep(300);
  }
  throw new Error(`Could not scroll to: ${candidates.join(" / ")}`);
}

async function tapText(candidates, name, scroll = false, preferLast = false) {
  for (let attempt = 0; attempt < (scroll ? 7 : 1); attempt += 1) {
    const xml = await dumpUi(`${name}-${attempt}`);
    const node = findNode(xml, candidates, preferLast);
    if (node?.bounds) {
      const [left, top, right, bottom] = node.bounds;
      adb(["shell", "input", "tap", String(Math.round((left + right) / 2)), String(Math.round((top + bottom) / 2))]);
      await sleep(550);
      return;
    }
    if (scroll) {
      adb(["shell", "input", "swipe", "600", "1550", "600", "620", "280"]);
      await sleep(300);
    }
  }
  throw new Error(`Could not find control: ${candidates.join(" / ")}`);
}

async function expectText(candidates, name) {
  const xml = await dumpUi(name);
  if (!findNode(xml, candidates)) {
    throw new Error(`Expected text not found: ${candidates.join(" / ")}`);
  }
  return xml;
}

async function expectNear(labelCandidates, expectedCandidates, name) {
  const xml = await dumpUi(name);
  const nodes = uiNodes(xml);
  const label = findNode(xml, labelCandidates);
  if (!label?.bounds) throw new Error(`Row label not found: ${labelCandidates.join(" / ")}`);
  const centerY = (label.bounds[1] + label.bounds[3]) / 2;
  const nearby = nodes.filter(node => {
    if (!node.bounds || !(node.text || "").trim()) return false;
    const nodeY = (node.bounds[1] + node.bounds[3]) / 2;
    return Math.abs(nodeY - centerY) <= 42;
  }).map(node => node.text.trim().toLowerCase());
  const expected = expectedCandidates.map(value => value.toLowerCase());
  if (!nearby.some(value => expected.some(candidate => value === candidate || value.includes(candidate)))) {
    throw new Error(`Row ${labelCandidates.join(" / ")} did not show ${expectedCandidates.join(" / ")}; saw ${nearby.join(" | ")}`);
  }
  return nearby;
}

async function openPage(page) {
  for (let attempt = 0; attempt < 3; attempt += 1) {
    const focused = currentPackage();
    if (!focused || focused === packageName) break;
    adb(["shell", "input", "keyevent", "KEYCODE_BACK"]);
    await sleep(180);
  }
  try { adb(["shell", "am", "force-stop", "com.google.android.documentsui"]); } catch (_) {}
  adb(["shell", "am", "force-stop", packageName]);
  adb(["shell", "am", "start", "-W", "-n", activityName, "--es", "signalasi_debug_control_center_page", page]);
  await sleep(750);
}

async function openDebugBoolean(extra) {
  for (let attempt = 0; attempt < 3; attempt += 1) {
    const focused = currentPackage();
    if (!focused || focused === packageName) break;
    adb(["shell", "input", "keyevent", "KEYCODE_BACK"]);
    await sleep(180);
  }
  try { adb(["shell", "am", "force-stop", "com.google.android.documentsui"]); } catch (_) {}
  adb(["shell", "am", "force-stop", packageName]);
  adb(["shell", "am", "start", "-W", "-n", activityName, "--ez", extra, "true"]);
  await sleep(750);
}

function currentPackage() {
  const output = adb(["shell", "dumpsys", "window"]);
  const match = output.match(/mCurrentFocus=.*?\s([\w.]+)\//) || output.match(/mFocusedApp=.*?\s([\w.]+)\//);
  return match?.[1] || "";
}

function ensureAppHealthy() {
  const pid = adb(["shell", "pidof", packageName]).trim();
  if (!pid) throw new Error("SignalASI process is not running");
  const fatal = adb(["logcat", "-d", "-v", "brief", "AndroidRuntime:E", "*:S"]);
  if (/FATAL EXCEPTION|Process: com\.signalasi\.chat.*has died/i.test(fatal)) {
    throw new Error("AndroidRuntime reported a fatal exception");
  }
}

function permissionGranted(permission) {
  const output = adb(["shell", "dumpsys", "package", packageName]);
  const escaped = permission.replace(/[.*+?^${}()|[\]\\]/g, "\\$&");
  return new RegExp(`${escaped}: granted=true`).test(output);
}

async function expectSystemPackage(name) {
  await sleep(500);
  const pkg = currentPackage();
  if (!pkg || pkg === packageName) throw new Error(`${name} did not open an Android system surface; current package=${pkg}`);
  adb(["shell", "input", "keyevent", "KEYCODE_BACK"]);
  await sleep(450);
}

async function headerBack(expected, name) {
  await tapText(labels.back, `${name}-back`);
  await expectText(expected, `${name}-parent`);
}

async function runCase(name, area, body) {
  const startedAt = Date.now();
  adb(["logcat", "-c"]);
  try {
    await body();
    ensureAppHealthy();
    return { name, area, passed: true, elapsed_ms: Date.now() - startedAt };
  } catch (error) {
    return { name, area, passed: false, elapsed_ms: Date.now() - startedAt, error: error.message };
  }
}

async function main() {
  adb(["shell", "input", "keyevent", "KEYCODE_WAKEUP"]);
  adb(["shell", "wm", "dismiss-keyguard"]);

  const cases = [
    ["encrypted-settings-and-device-store-roundtrip", "Persistence", async () => {
      const token = `CC_${Date.now()}`;
      adb(["shell", "am", "force-stop", packageName]);
      adb([
        "shell", "am", "start", "-W", "-n", activityName,
        "--es", "signalasi_debug_control_center_roundtrip", token,
      ]);
      const result = await waitForDebugResult("control_center_roundtrip_result", token);
      if (!result.ok || !result.settings_persisted || !result.devices_persisted || !result.restored) {
        throw new Error(`Control Center persistence roundtrip failed: ${JSON.stringify(result)}`);
      }
    }],
    ["profile-edit-persistence-and-recovery", "Identity", async () => {
      await openPage("profile");
      await tapText(["Nickname", "\u6635\u79f0"], "profile-nickname");
      const editorXml = await expectText(["Nickname", "\u6635\u79f0"], "profile-editor");
      const editor = uiNodes(editorXml).find(node => node.class === "android.widget.EditText");
      const original = editor?.text || "";
      if (original && /^[\x20-\x7e]+$/.test(original)) {
        const temporary = `SignalASI-QA-${Date.now().toString().slice(-5)}`;
        try {
          const [left, top, right, bottom] = editor.bounds;
          adb(["shell", "input", "tap", String(Math.round((left + right) / 2)), String(Math.round((top + bottom) / 2))]);
          adb(["shell", "input", "keycombination", "KEYCODE_CTRL_LEFT", "KEYCODE_A"]);
          adb(["shell", "input", "keyevent", "KEYCODE_DEL"]);
          adb(["shell", "input", "text", temporary]);
          await expectText([temporary], "profile-temporary-input");
          await tapText(labels.save, "profile-save", true, true);
          await openPage("profile");
          await expectText([temporary], "profile-persisted");
        } finally {
          await openPage("profile");
          await tapText(["Nickname", "\u6635\u79f0"], "profile-restore-open");
          const restoreXml = await dumpUi("profile-restore-editor");
          const restoreEditor = uiNodes(restoreXml).find(node => node.class === "android.widget.EditText");
          if (!restoreEditor?.bounds) throw new Error("Nickname restore editor was not available");
          const [left, top, right, bottom] = restoreEditor.bounds;
          adb(["shell", "input", "tap", String(Math.round((left + right) / 2)), String(Math.round((top + bottom) / 2))]);
          adb(["shell", "input", "keycombination", "KEYCODE_CTRL_LEFT", "KEYCODE_A"]);
          adb(["shell", "input", "keyevent", "KEYCODE_DEL"]);
          adb(["shell", "input", "text", original.replace(/ /g, "%s")]);
          await tapText(labels.save, "profile-restore-save", true, true);
        }
      } else {
        adb(["shell", "input", "keyevent", "KEYCODE_BACK"]);
      }
      await openPage("profile");
      await tapText(["Identity recovery", "\u8eab\u4efd\u6062\u590d"], "profile-recovery", true);
      await expectText(labels.data, "profile-recovery-data");
      adb(["shell", "input", "keyevent", "KEYCODE_BACK"]);
      await expectText(labels.profile, "profile-recovery-back");
    }],
    ["system-status-drilldown", "Status", async () => {
      await openPage("system_status");
      await tapText(["Agent runtime", "Agent \u8fd0\u884c\u65f6"], "status-runtime", true);
      await expectText(labels.agent, "status-agent");
      adb(["shell", "input", "keyevent", "KEYCODE_BACK"]);
      await expectText(labels.system, "status-back");
      await tapText(["Resource router", "\u8d44\u6e90\u8def\u7531\u5668"], "status-router", true);
      await expectText(labels.routing, "status-routing");
    }],
    ["agent-core-and-policy-navigation", "Agent", async () => {
      await openPage("agent_core");
      await tapText(["Smart Automatic", "\u667a\u80fd\u81ea\u52a8"], "agent-policy", true);
      await expectText(labels.execution, "agent-policy-open");
      await tapText(["Direct execution", "\u76f4\u63a5\u6267\u884c"], "policy-direct", true);
      await expectText(["On-device Agent", "\u624b\u673a\u7aef Agent"], "policy-advanced");
      adb(["shell", "input", "keyevent", "KEYCODE_BACK"]);
      await expectText(labels.execution, "policy-back");
    }],
    ["cloud-provider-model-config-back-stack", "Routing", async () => {
      await openPage("resource_routing");
      await tapText(["Add Cloud Provider", "\u6dfb\u52a0\u4e91\u7aef Provider"], "routing-cloud", true);
      await expectText(["Cloud Models", "\u4e91\u7aef\u6a21\u578b"], "cloud-providers");
      await tapText(["DeepSeek"], "cloud-deepseek", true);
      await expectText(["DeepSeek"], "cloud-models");
      await tapText(["DeepSeek V4 Pro", "DeepSeek V4 Flash", "DeepSeek V3.2"], "cloud-model", true);
      await expectText(["Configure Model", "\u914d\u7f6e\u6a21\u578b"], "cloud-config");
      await headerBack(["DeepSeek"], "cloud-config");
      await headerBack(["Cloud Models", "\u4e91\u7aef\u6a21\u578b"], "cloud-models");
      await headerBack(labels.routing, "cloud-providers");
    }],
    ["memory-category-and-management", "Memory", async () => {
      await openPage("memory");
      await tapText(["Identity & Preferences", "\u8eab\u4efd\u4e0e\u504f\u597d"], "memory-category", true);
      await expectText(["Personal Memory", "\u4e2a\u4eba\u8bb0\u5fc6"], "memory-filtered");
      adb(["shell", "input", "keyevent", "KEYCODE_BACK"]);
      await expectText(labels.memory, "memory-filtered-back");
      await tapText(["Manage all memories", "\u7ba1\u7406\u5168\u90e8\u8bb0\u5fc6"], "memory-manage", true);
      await expectText(["Personal Memory", "\u4e2a\u4eba\u8bb0\u5fc6"], "memory-all");
    }],
    ["knowledge-search-and-import-picker", "Knowledge", async () => {
      await openPage("knowledge");
      await tapText(["Search knowledge", "\u641c\u7d22\u77e5\u8bc6"], "knowledge-search", true);
      const dialog = await dumpUi("knowledge-dialog");
      const input = uiNodes(dialog).find(node => node.class === "android.widget.EditText");
      if (!input) throw new Error("Knowledge search did not open an input dialog");
      adb(["shell", "input", "text", "signalasi_qa_no_match"]);
      await tapText(labels.save, "knowledge-search-save");
      await expectText(["signalasi_qa_no_match"], "knowledge-search-results");
      await openPage("knowledge");
      await tapText(["Import source", "\u5bfc\u5165\u8d44\u6599"], "knowledge-import", true);
      await expectSystemPackage("Knowledge import");
    }],
    ["skill-import-picker", "Skills", async () => {
      await openPage("skills");
      await tapText(["Install Local Skill", "\u5b89\u88c5\u672c\u5730\u6280\u80fd"], "skills-import", true);
      await expectSystemPackage("Skill import");
    }],
    ["native-tool-detail-toolbar-and-hardware-back", "Phone", async () => {
      await openPage("phone_capabilities");
      await tapText(["Complete Native Tool Catalog", "\u5b8c\u6574\u539f\u751f\u5de5\u5177\u76ee\u5f55"], "tool-catalog", true);
      await expectText(["Complete Native Tool Catalog", "\u5b8c\u6574\u539f\u751f\u5de5\u5177\u76ee\u5f55"], "tool-catalog-open");
      const tools = [
        "Append bytes to workspace file",
        "Append text to workspace file",
        "Create workspace directory",
        "Initialize app-private workspace",
      ];
      await tapText(tools, "tool-detail", true);
      await expectText(["Tool ID", "\u5de5\u5177 ID"], "tool-detail-open");
      await headerBack(["Complete Native Tool Catalog", "\u5b8c\u6574\u539f\u751f\u5de5\u5177\u76ee\u5f55"], "tool-detail");
      adb(["shell", "input", "keyevent", "KEYCODE_BACK"]);
      await expectText(labels.phone, "tool-catalog-hardware-back");
    }],
    ["app-adapters-and-accessibility-settings", "Apps", async () => {
      await openPage("app_tools");
      await tapText(["Manage App Adapters", "\u7ba1\u7406\u5e94\u7528\u9002\u914d\u5668"], "app-adapters", true);
      await expectText(["App Adapters", "\u5e94\u7528\u9002\u914d\u5668"], "app-adapters-open");
      adb(["shell", "input", "keyevent", "KEYCODE_BACK"]);
      await expectText(labels.appTools, "app-adapters-back");
      await tapText(["Universal App Executor", "\u901a\u7528\u5e94\u7528\u6267\u884c\u5668"], "app-accessibility", true);
      await expectSystemPackage("Accessibility settings");
    }],
    ["smart-space-device-editor-back-stack", "Devices", async () => {
      await openPage("smart_spaces");
      await tapText(["Home Assistant"], "spaces-configure");
      await expectText(["Device Management", "\u8bbe\u5907\u7ba1\u7406"], "device-management");
      await tapText(["Add Custom Device", "\u6dfb\u52a0\u81ea\u5b9a\u4e49\u8bbe\u5907"], "custom-device-add", true);
      await expectText(["Custom Device", "\u81ea\u5b9a\u4e49\u8bbe\u5907"], "custom-device-editor");
      await headerBack(["Device Management", "\u8bbe\u5907\u7ba1\u7406"], "custom-device-editor");
      await headerBack(labels.spaces, "device-management");
    }],
    ["home-assistant-entities-details-and-automations", "Devices", async () => {
      const original = readAppFile(homeAssistantPreferencesPath);
      const fake = await startFakeHomeAssistant();
      try {
        adb(["reverse", `tcp:${fake.port}`, `tcp:${fake.port}`]);
        adb(["shell", "am", "force-stop", packageName]);
        adb([
          "shell", "am", "start", "-W", "-n", activityName,
          "--es", "signalasi_debug_home_assistant_url", `http://127.0.0.1:${fake.port}`,
        ]);
        await expectText(labels.spaces, "home-assistant-test-ready");
        await tapText(["Entities & Rooms", "\u5b9e\u4f53\u4e0e\u623f\u95f4"], "home-entities", true);
        await sleep(800);
        await expectText(["QA Lamp"], "home-entities-loaded");
        await tapText(["QA Lamp"], "home-entity-detail", true);
        await sleep(700);
        await expectText(["light.qa_lamp"], "home-entity-detail-loaded");
        await headerBack(["Entities & Rooms", "\u5b9e\u4f53\u4e0e\u623f\u95f4"], "home-entity-detail");
        adb(["shell", "input", "keyevent", "KEYCODE_BACK"]);
        await expectText(labels.spaces, "home-entities-back");
        await tapText(["Scenes & Automations", "\u573a\u666f\u4e0e\u81ea\u52a8\u5316"], "home-automations", true);
        await sleep(800);
        await expectText(["QA Morning"], "home-automations-loaded");
        if (!fake.requests.some(request => request.url === "/api/states" && request.authorization === "Bearer signalasi-control-center-test")) {
          throw new Error(`Home Assistant API did not receive an authenticated state request: ${JSON.stringify(fake.requests)}`);
        }
        if (!fake.requests.some(request => request.url === "/api/states/light.qa_lamp")) {
          throw new Error(`Home Assistant entity detail was not requested: ${JSON.stringify(fake.requests)}`);
        }
      } finally {
        adb(["shell", "am", "force-stop", packageName]);
        restoreAppFile(homeAssistantPreferencesPath, original);
        try { adb(["reverse", "--remove", `tcp:${fake.port}`]); } catch (_) {}
        await new Promise(resolve => fake.server.close(resolve));
        adb(["shell", "monkey", "-p", packageName, "-c", "android.intent.category.LAUNCHER", "1"]);
        await sleep(500);
      }
    }],
    ["permission-state-is-real-and-system-links-work", "Permissions", async () => {
      await openPage("permissions_audit");
      const microphoneGranted = permissionGranted("android.permission.RECORD_AUDIO");
      await expectNear(
        ["Microphone", "\u9ea6\u514b\u98ce"],
        microphoneGranted ? ["Allowed", "\u5df2\u5141\u8bb8"] : ["Needs setup", "\u5f85\u914d\u7f6e"],
        "permissions-microphone"
      );
      await tapText(["Accessibility", "\u65e0\u969c\u788d"], "permissions-accessibility", true);
      await expectSystemPackage("Accessibility permission");
      await openDebugBoolean("signalasi_debug_open_on_device_agent");
      const locationGranted = permissionGranted("android.permission.ACCESS_FINE_LOCATION") || permissionGranted("android.permission.ACCESS_COARSE_LOCATION");
      await scrollToText(["Location", "\u4f4d\u7f6e"], "agent-location-scroll");
      await expectNear(
        ["Location", "\u4f4d\u7f6e"],
        locationGranted ? ["While in use", "\u4f7f\u7528\u65f6"] : ["Needs setup", "\u5f85\u914d\u7f6e"],
        "agent-location-status"
      );
    }],
    ["voice-readiness-and-settings", "Voice", async () => {
      await openPage("voice");
      const microphoneGranted = permissionGranted("android.permission.RECORD_AUDIO");
      if (!microphoneGranted) {
        await expectNear(["ASR Provider", "ASR \u63d0\u4f9b\u65b9"], ["Needs setup", "\u5f85\u914d\u7f6e"], "voice-asr-readiness");
      }
      await tapText(["ASR Provider", "ASR \u63d0\u4f9b\u65b9"], "voice-asr", true);
      await expectText(["On-device whisper.cpp", "\u672c\u673a whisper.cpp"], "voice-models");
      await headerBack(labels.voice, "voice-models");
    }],
    ["backup-input-validation-and-picker", "Data", async () => {
      await openPage("data_backup");
      await tapText(["Create Encrypted Backup", "\u521b\u5efa\u52a0\u5bc6\u5907\u4efd"], "backup-export", true);
      await expectText(["Password", "\u5bc6\u7801"], "backup-password");
      await tapText(["Export", "\u5bfc\u51fa"], "backup-empty-export", true, true);
      await expectText(["Password", "\u5bc6\u7801"], "backup-empty-blocked");
      await openPage("data_backup");
      await tapText(["Import Backup", "\u4ece\u5907\u4efd\u5bfc\u5165"], "backup-import", true);
      await expectSystemPackage("Backup import");
    }],
    ["general-system-settings-and-about-security", "General", async () => {
      await openPage("general");
      await tapText(["Appearance", "\u5916\u89c2"], "general-appearance", true);
      await expectSystemPackage("Display settings");
      await openPage("general");
      await tapText(["About SignalASI", "\u5173\u4e8e SignalASI"], "general-about", true);
      await expectText(["About SignalASI", "\u5173\u4e8e SignalASI"], "about-page");
      await tapText(["Security and privacy", "\u5b89\u5168\u4e0e\u9690\u79c1"], "about-security", true);
      await expectText(["Security Center", "\u5b89\u5168\u4e2d\u5fc3"], "about-security-open");
      await headerBack(["About SignalASI", "\u5173\u4e8e SignalASI"], "about-security");
    }],
    ["app-services-navigation", "Services", async () => {
      await openPage("app_services");
      await tapText(["Chat History", "\u804a\u5929\u8bb0\u5f55"], "services-history", true);
      await expectText(["Sessions", "\u4f1a\u8bdd"], "services-sessions");
      adb(["shell", "input", "keyevent", "KEYCODE_BACK"]);
      await openPage("app_services");
      await tapText(["Background Message Connection", "\u540e\u53f0\u6d88\u606f\u8fde\u63a5"], "services-background", true);
      await expectSystemPackage("Background connection settings");
    }],
    ["advanced-live-diagnostics-and-app-details", "Advanced", async () => {
      await openPage("advanced");
      await tapText(["Protocol Diagnostics", "\u534f\u8bae\u8bca\u65ad"], "advanced-protocol", true);
      await expectText(["Signal Link Protocol"], "advanced-protocol-open");
      await expectText(["MQTT", "Disconnected", "\u5df2\u65ad\u5f00"], "advanced-protocol-live-state");
      adb(["shell", "input", "keyevent", "KEYCODE_BACK"]);
      await expectText(labels.advanced, "advanced-protocol-back");
      await tapText(["Android app details", "Android \u5e94\u7528\u8be6\u60c5"], "advanced-app-details", true);
      await expectSystemPackage("Application details");
    }],
    ["reset-rejects-wrong-confirmation", "Safety", async () => {
      await openPage("reset");
      await tapText(["Review and reset SignalASI", "\u68c0\u67e5\u5e76\u91cd\u7f6e SignalASI"], "reset-review", true);
      await expectText(["Type RESET", "\u8f93\u5165 RESET"], "reset-dialog");
      const xml = await dumpUi("reset-input");
      const input = uiNodes(xml).find(node => node.class === "android.widget.EditText");
      if (!input) throw new Error("Reset dialog did not expose its confirmation input");
      adb(["shell", "input", "text", "WRONG"]);
      await tapText(["Reset Data", "\u91cd\u7f6e\u6570\u636e"], "reset-wrong", false, true);
      await expectText(["Type RESET", "\u8f93\u5165 RESET"], "reset-still-blocked");
      await tapText(labels.cancel, "reset-cancel");
      await expectText(labels.reset, "reset-intact");
    }],
  ];

  const requestedCases = new Set(
    (process.env.SIGNALASI_CC_CASES || "")
      .split(",")
      .map(value => value.trim())
      .filter(Boolean)
  );
  const selectedCases = requestedCases.size > 0
    ? cases.filter(([name]) => requestedCases.has(name))
    : cases;
  const results = [];
  for (const [name, area, body] of selectedCases) {
    const result = await runCase(name, area, body);
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
