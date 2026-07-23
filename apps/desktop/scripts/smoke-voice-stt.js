const { execFileSync } = require("node:child_process");
const path = require("node:path");
const { findBackendPython } = require("./python-runtime");

const root = path.resolve(__dirname, "..");
const backendDir = path.join(root, "core", "signalasi-link", "backend");

function log(message) {
  console.log(`[voice-stt-smoke] ${message}`);
}

function fail(message) {
  throw new Error(message);
}

function runBackendJson(code) {
  const output = execFileSync(findBackendPython(), ["-c", code], {
    cwd: backendDir,
    encoding: "utf8",
    windowsHide: true,
    maxBuffer: 1024 * 1024
  });
  const jsonLine = output.split(/\r?\n/).map((line) => line.trim()).filter(Boolean).reverse()
    .find((line) => line.startsWith("{"));
  if (!jsonLine) fail(`Backend command did not emit JSON: ${output}`);
  return JSON.parse(jsonLine);
}

function main() {
  log("checking Desktop voice STT bridge with mocked transcription");
  const result = runBackendJson(String.raw`
import base64
import json
import os
from pathlib import Path

import mqtt_bridge

calls = []

def fake_transcribe(path):
    p = Path(path)
    if not p.is_file():
        raise AssertionError("inline audio file was not saved")
    calls.append(str(p))
    return "VOICE_STT_SMOKE transcript from desktop"

mqtt_bridge.transcribe_audio = fake_transcribe
transcript = mqtt_bridge._content_from_audio(
    "voice_stt_smoke.m4a",
    "",
    base64.b64encode(b"signalasi fake audio bytes").decode("ascii"),
)
reply = mqtt_bridge.clean_audio_reply(
    "The user sent a voice message.\n\n"
    "Transcript: VOICE_STT_SMOKE transcript from desktop\n\n"
    "Final answer after STT."
)
for item in calls:
    try:
        os.remove(item)
    except OSError:
        pass
print(json.dumps({
    "transcript": transcript,
    "saved_audio_count": len(calls),
    "clean_reply": reply,
}, ensure_ascii=False))
`);

  if (result.transcript !== "VOICE_STT_SMOKE transcript from desktop") {
    fail(`Unexpected transcript: ${JSON.stringify(result)}`);
  }
  if (result.saved_audio_count !== 1) {
    fail(`Inline audio was not saved exactly once: ${JSON.stringify(result)}`);
  }
  if (result.clean_reply !== "Final answer after STT.") {
    fail(`Audio reply cleaning failed: ${JSON.stringify(result)}`);
  }
  log("Desktop voice STT bridge OK");
}

try {
  main();
} catch (error) {
  console.error(`[voice-stt-smoke] failed: ${error.stack || error.message || error}`);
  process.exit(1);
}
