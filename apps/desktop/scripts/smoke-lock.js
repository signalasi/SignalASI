const fs = require("node:fs");
const os = require("node:os");
const path = require("node:path");

const LOCK_PATH = path.join(os.tmpdir(), "signalasi-desktop-test.lock");
const STALE_MS = 15 * 60 * 1000;

function processIsAlive(pid) {
  if (!pid || pid === process.pid) return false;
  try {
    process.kill(pid, 0);
    return true;
  } catch {
    return false;
  }
}

function readLock() {
  try {
    return JSON.parse(fs.readFileSync(LOCK_PATH, "utf8"));
  } catch {
    return {};
  }
}

function acquireSignalasiLock(name) {
  const startedAt = Date.now();
  const payload = {
    name,
    pid: process.pid,
    started_at: new Date(startedAt).toISOString()
  };

  for (let attempt = 0; attempt < 2; attempt += 1) {
    try {
      const fd = fs.openSync(LOCK_PATH, "wx");
      fs.writeFileSync(fd, JSON.stringify(payload, null, 2));
      fs.closeSync(fd);
      let released = false;
      return () => {
        if (released) return;
        released = true;
        const current = readLock();
        if (current.pid === process.pid) {
          fs.rmSync(LOCK_PATH, { force: true });
        }
      };
    } catch (error) {
      if (error.code !== "EEXIST") throw error;
      const current = readLock();
      const age = startedAt - Date.parse(current.started_at || 0);
      if (age > STALE_MS || !processIsAlive(Number(current.pid))) {
        fs.rmSync(LOCK_PATH, { force: true });
        continue;
      }
      throw new Error(
        `Another SignalASI smoke/package task is running: ${current.name || "unknown"} ` +
        `(pid ${current.pid || "unknown"}). Run these scripts sequentially.`
      );
    }
  }
  throw new Error("Could not acquire SignalASI smoke/package lock");
}

async function withSignalasiLock(name, fn) {
  const release = acquireSignalasiLock(name);
  try {
    return await fn();
  } finally {
    release();
  }
}

module.exports = {
  acquireSignalasiLock,
  withSignalasiLock
};
