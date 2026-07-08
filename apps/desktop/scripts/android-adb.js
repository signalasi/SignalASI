const { execFileSync } = require("node:child_process");

function errorText(error) {
  return [
    error?.message,
    error?.stdout?.toString?.(),
    error?.stderr?.toString?.()
  ].filter(Boolean).join("\n");
}

function isTransientAdbError(error) {
  const text = errorText(error).toLowerCase();
  return text.includes("daemon not running") ||
    text.includes("cannot connect to daemon") ||
    text.includes("failed to start daemon") ||
    text.includes("could not read ok from adb server") ||
    text.includes("adb server is out of date") ||
    text.includes("device offline");
}

function restartAdb(log) {
  try {
    execFileSync("adb", ["kill-server"], { encoding: "utf8", windowsHide: true, stdio: ["ignore", "ignore", "ignore"] });
  } catch {
    // Best effort cleanup before start-server.
  }
  try {
    execFileSync("adb", ["start-server"], { encoding: "utf8", windowsHide: true, stdio: ["ignore", "ignore", "ignore"] });
  } catch (error) {
    log?.(`ADB restart failed: ${errorText(error).split("\n")[0]}`);
  }
}

function createAdb(root, log) {
  return function adb(args, options = {}) {
    let lastError = null;
    for (let attempt = 0; attempt < 3; attempt += 1) {
      try {
        return execFileSync("adb", args, {
          cwd: options.cwd || root,
          encoding: options.encoding || "utf8",
          windowsHide: true,
          input: options.input,
          stdio: options.stdio || ["pipe", "pipe", "pipe"]
        });
      } catch (error) {
        lastError = error;
        if (!isTransientAdbError(error) || attempt === 2) break;
        log?.(`ADB transient failure, restarting server and retrying (${attempt + 1}/2)`);
        restartAdb(log);
      }
    }
    throw lastError;
  };
}

module.exports = { createAdb };
