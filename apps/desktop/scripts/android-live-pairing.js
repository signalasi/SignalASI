"use strict";

function sleep(milliseconds) {
  return new Promise((resolve) => setTimeout(resolve, milliseconds));
}

async function fetchJson(url) {
  const response = await fetch(url, { signal: AbortSignal.timeout(10_000) });
  if (!response.ok) {
    throw new Error(`${url} returned HTTP ${response.status}`);
  }
  return response.json();
}

async function establishFreshSecurePairing({
  adb,
  packageName = "com.signalasi.chat",
  activityName = `${packageName}/.MainActivity`,
  baseUrl = "http://127.0.0.1:8765",
  timeoutMillis = 30_000,
  log = () => {}
}) {
  const statusBefore = await fetchJson(`${baseUrl}/api/pairing/status`);
  const previousRoutes = new Set(
    (statusBefore.clients || [])
      .filter((client) => client.paired && !client.revoked)
      .map((client) => client.client_route_id)
  );
  const payload = await fetchJson(`${baseUrl}/api/pairing/payload`);
  const encoded = Buffer.from(JSON.stringify(payload), "utf8").toString("base64");
  adb(["shell", "am", "force-stop", packageName]);
  adb([
    "shell",
    "am",
    "start",
    "-n",
    activityName,
    "--es",
    "signalasi_debug_scan_payload_b64",
    encoded,
    "--ez",
    "signalasi_debug_auto_confirm_scan",
    "true"
  ]);
  const deadline = Date.now() + timeoutMillis;
  while (Date.now() < deadline) {
    await sleep(500);
    const status = await fetchJson(`${baseUrl}/api/pairing/status`);
    const client = (status.clients || []).find(
      (candidate) => candidate.paired && !candidate.revoked && !previousRoutes.has(candidate.client_route_id)
    );
    if (client) {
      log(`fresh secure pairing established for route ${client.client_route_id.slice(-8)}`);
      return { payload, client };
    }
  }
  throw new Error("Live Desktop QR did not complete secure pairing");
}

module.exports = { establishFreshSecurePairing, fetchJson };
