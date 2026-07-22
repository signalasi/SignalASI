#!/usr/bin/env node
"use strict";

const http = require("node:http");

const host = "127.0.0.1";
const port = Number(process.env.SIGNALASI_HA_ACCEPTANCE_PORT || "18123");
const token = "signalasi-cross-resource-acceptance";
let lightState = "off";

function json(response, status, payload) {
  const body = JSON.stringify(payload);
  response.writeHead(status, {
    "Content-Type": "application/json",
    "Content-Length": Buffer.byteLength(body),
  });
  response.end(body);
}

function entity() {
  return {
    entity_id: "light.qa_lamp",
    state: lightState,
    attributes: { friendly_name: "QA Lamp" },
  };
}

const server = http.createServer((request, response) => {
  if (request.headers.authorization !== `Bearer ${token}`) {
    json(response, 401, { message: "Unauthorized" });
    return;
  }
  const url = new URL(request.url, `http://${host}:${port}`);
  if (request.method === "GET" && url.pathname === "/api/") {
    json(response, 200, { message: "API running" });
    return;
  }
  if (request.method === "GET" && url.pathname === "/api/states") {
    json(response, 200, [
      entity(),
      {
        entity_id: "automation.qa_routine",
        state: "on",
        attributes: { friendly_name: "QA Routine" },
      },
    ]);
    return;
  }
  if (request.method === "GET" && url.pathname === "/api/states/light.qa_lamp") {
    json(response, 200, entity());
    return;
  }
  if (request.method === "POST" && url.pathname === "/api/services/homeassistant/turn_on") {
    let body = "";
    request.setEncoding("utf8");
    request.on("data", (chunk) => { body += chunk; });
    request.on("end", () => {
      let payload;
      try {
        payload = JSON.parse(body || "{}");
      } catch {
        json(response, 400, { message: "Invalid JSON" });
        return;
      }
      if (payload.entity_id !== "light.qa_lamp") {
        json(response, 400, { message: "Unexpected entity" });
        return;
      }
      lightState = "on";
      json(response, 200, [entity()]);
    });
    return;
  }
  json(response, 404, { message: "Not found" });
});

server.listen(port, host, () => {
  process.stdout.write(`READY ${host}:${port}\n`);
});

for (const signal of ["SIGINT", "SIGTERM"]) {
  process.on(signal, () => server.close(() => process.exit(0)));
}
